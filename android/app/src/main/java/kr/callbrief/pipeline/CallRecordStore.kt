package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Stored transcript + Claude result of one recording version. Lives in the protected app DB. */
internal data class CallRecord(
    val recordId:String,
    val recordingId:String,
    val startedMs:Long?,
    val durationMs:Long,
    val counterpart:String?,
    val counterpartKey:String?,
    val transcript:String,
    val state:String,
    val summary:String?,
    val analysis:ClaudeCallAnalysis?,
    val applied:List<CallRecordStore.Applied>,
    val error:String?,
    val createdMs:Long,
    val analyzedMs:Long?,
    /** The ledger person this call is linked to; carries the user's name corrections. */
    val linkedPerson:String?=null,
) {
    /** "△△철강 김민수": company then person, the way it is said aloud; else the recording's counterpart. */
    val displayName:String get()=listOfNotNull(analysis?.counterpartCompany,linkedPerson ?: analysis?.counterpartName).joinToString(" ").ifBlank { counterpart?.takeIf { it.isNotBlank() } ?: "상대 미상" }
    val dayMs:Long get()=startedMs ?: createdMs
    /** "통화", "문자" or "메일": messages are stored like calls under a "sms:" or "mail:" recording id. */
    val kind:String get()=MessageKind.of(recordingId)
}

internal object MessageKind {
    const val SMS="sms:";const val MAIL="mail:"
    fun of(recordingId:String)=when { recordingId.startsWith(SMS) -> "문자";recordingId.startsWith(MAIL) -> "메일";else -> "통화" }
}

/** An open workflow item shown in "오늘 할 일". */
internal data class OpenItem(val id:String,val title:String,val due:String,val status:String,val revision:Long,val edition:String,val counterpart:String?,val company:String?=null,
                              /** When the to-do was asked for (the call it came from). */ val createdMs:Long=0,
                              /** One line from a later call about an item the user had changed ("11/5 통화: 끝난 것으로 보여요"); the item itself is untouched. */ val aiNote:String?=null,
                              /** Asked for only loosely ("알아볼게요", certainty "tentative"). */ val tentative:Boolean=false)
internal data class CallTodoItem(val id:String,val title:String,val due:String,val status:String,val revision:Long,val company:String?=null,val createdMs:Long=0,val aiNote:String?=null,
                                 val tentative:Boolean=false)
/** One company of the ledger with what the list screen shows. */
internal data class CompanyRow(val id:String,val name:String,val trade:String?,val openTasks:Int,val lastCallMs:Long?)
internal data class CompanyCall(val recordId:String,val dayMs:Long,val person:String?,val summary:String?)
internal data class PersonRow(val id:String,val name:String,val role:String?,val isStaff:Boolean)
internal data class CompanyDetail(val id:String,val name:String,val trade:String?,val memo:String,val tasks:List<OpenItem>,val calls:List<CompanyCall>,val people:List<PersonRow> =emptyList(),
                                  val issues:List<Issue> =emptyList())
/** One "진행 중인 일": a thread of calls, texts and mail with its one-line state (design: 새벽 정리). */
internal data class Issue(val title:String,val status:String,val companies:List<String>)

internal class CallRecordStore(context:Context,private val clock:()->Long=System::currentTimeMillis):AutoCloseable {
    /** [note]: set when the item was the user's own and only got an AI note line (then [applied] is false). */
    /** [duplicate]: a create left out because the same item exists (counted in DUPLICATE_SKIPPED, not stored). */
    data class Applied(val op:String,val itemId:String,val title:String,val due:String?,val applied:Boolean,val note:String?=null,val duplicate:Boolean=false,
        /** Why it was not applied, when the store knows: "older" = a call earlier than the last one that changed the item (C5). */
        val reason:String?=null)
    companion object {
        val KST:ZoneId=ZoneId.of("Asia/Seoul")
        const val TRANSCRIBED="TRANSCRIBED";const val ANALYZED="ANALYZED";const val FAILED="FAILED"
        const val WAITING_CONSENT="WAITING_CONSENT"
        /** Transcripts of records that got their result are trimmed after this many days: the whole sealed DB must stay under 16 MB. Summaries stay. */
        const val TRANSCRIPT_KEEP_DAYS=14L
        /** The full analysis of a record is reduced to its summary and names after this many days (review B20). */
        const val ANALYSIS_KEEP_DAYS=90L
        /** Sealed database limit and the share of it that is worth a log line. */
        const val DB_LIMIT_BYTES=16L*1024*1024
        const val DB_WARN_SHARE=0.8
        private const val TAG="CallRecordStore"
        private val ISO_DATE=Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val SCHEMA=listOf(
            
            "CREATE TABLE IF NOT EXISTS workflow_links(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER NOT NULL,person_id TEXT,company_id TEXT,case_id TEXT NOT NULL,task_id TEXT NOT NULL,phone_normalized TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_items(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),case_id TEXT,kind TEXT NOT NULL CHECK(kind IN ('TODO','DOCUMENT')),title TEXT NOT NULL,edition TEXT NOT NULL,due_text TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,source_key TEXT,evidence_quote TEXT NOT NULL,created_ms INTEGER NOT NULL,done_ms INTEGER,ai_note TEXT)",
            "CREATE TABLE IF NOT EXISTS workflow_item_history(event_id TEXT PRIMARY KEY,item_id TEXT NOT NULL,revision INTEGER NOT NULL,before_json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_records(record_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,started_ms INTEGER,duration_ms INTEGER NOT NULL,counterpart TEXT,counterpart_key TEXT,transcript TEXT NOT NULL,segments_json TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('TRANSCRIBED','ANALYZED','FAILED','WAITING_CONSENT')),summary TEXT,analysis_json TEXT,error TEXT,created_ms INTEGER NOT NULL,analyzed_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS call_task_bindings(item_id TEXT PRIMARY KEY,record_id TEXT NOT NULL REFERENCES call_records(record_id),last_revision INTEGER NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_inbox_sent(record_id TEXT PRIMARY KEY REFERENCES call_records(record_id),sent_ms INTEGER NOT NULL)",
            // Context ledger: what the phone remembers for the model, which has no memory between calls.
            // Which migration pass this database has had (PRAGMA user_version did not stay set in the sealed store).
            "CREATE TABLE IF NOT EXISTS app_migration(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS site_memo(id INTEGER PRIMARY KEY CHECK(id=1),text TEXT NOT NULL,updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS companies(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,trade TEXT,memo TEXT NOT NULL DEFAULT '',updated_ms INTEGER NOT NULL,name_key TEXT)",
            "CREATE TABLE IF NOT EXISTS people(id TEXT PRIMARY KEY,name TEXT NOT NULL,phone_key TEXT UNIQUE,company_id TEXT,role TEXT,is_staff INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL,name_key TEXT)",
            "CREATE TABLE IF NOT EXISTS call_links(record_id TEXT PRIMARY KEY,person_id TEXT,company_id TEXT)",
            "CREATE TABLE IF NOT EXISTS task_companies(item_id TEXT PRIMARY KEY,company_id TEXT NOT NULL)",
            // Names the user corrected away from (spaces removed), so a repeated mishearing still finds the right person.
            "CREATE TABLE IF NOT EXISTS person_aliases(name_key TEXT PRIMARY KEY,person_id TEXT NOT NULL)",
            // Other spellings of a company (key from [NameKey.canon]) that were found to mean the same one.
            "CREATE TABLE IF NOT EXISTS company_aliases(name_key TEXT PRIMARY KEY,company_id TEXT NOT NULL)",
            // Every phone number a person answers, so that an office number can belong to several people (review B9);
            // people.phone_key stays the first person's number for the old queries and is unique.
            "CREATE TABLE IF NOT EXISTS people_phones(phone_key TEXT NOT NULL,person_id TEXT NOT NULL,PRIMARY KEY(phone_key,person_id))",
            // The phone's call log since 10/1 (처음 정리), kept on the phone only, and which recording each entry was.
            "CREATE TABLE IF NOT EXISTS call_log(log_id INTEGER PRIMARY KEY,number_key TEXT,name TEXT,start_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,type INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_record_log(record_id TEXT PRIMARY KEY,log_id INTEGER NOT NULL UNIQUE,direction TEXT)",
            // The call list reads the log one day at a time (v2.4: every call is listed, recorded or not).
            "CREATE INDEX IF NOT EXISTS call_log_start ON call_log(start_ms)",
            // The user's corrections waiting to go to the repository ledger (ledger/edits/).
            "CREATE TABLE IF NOT EXISTS ledger_edits(id TEXT PRIMARY KEY,json TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            // "진행 중인 일" from the repository ledger (design: 새벽 정리), in the ledger's order.
            "CREATE TABLE IF NOT EXISTS issues(id TEXT PRIMARY KEY,ord INTEGER NOT NULL,title TEXT NOT NULL,status TEXT NOT NULL,companies TEXT NOT NULL,updated_ms INTEGER NOT NULL,record_keys TEXT NOT NULL DEFAULT '[]')",
            // Numbers found personal (a result with business=false, design E9): their calls and texts stay on the phone.
            "CREATE TABLE IF NOT EXISTS personal_numbers(phone_key TEXT PRIMARY KEY,decided_ms INTEGER NOT NULL)",
            // Personal verdicts in a row per number (E9 safety, round 6): a number is remembered only after [PERSONAL_STREAK].
            "CREATE TABLE IF NOT EXISTS personal_streaks(phone_key TEXT PRIMARY KEY,streak INTEGER NOT NULL,updated_ms INTEGER NOT NULL)")
        /** Columns added after the first release: the repository-ledger id of a person and the user's name lock. */
        private val PEOPLE_COLUMNS=listOf("ledger_id" to "TEXT","name_locked" to "INTEGER NOT NULL DEFAULT 0","name_key" to "TEXT")
        private val COMPANY_COLUMNS=listOf("name_key" to "TEXT")
        private val ITEM_COLUMNS=listOf("done_ms" to "INTEGER","ai_note" to "TEXT","source" to "TEXT","tentative" to "INTEGER NOT NULL DEFAULT 0",
            // When the call that last made or changed the item took place (C5 time rule, v2.4); null on items from before: created_ms then.
            "last_call_ms" to "INTEGER")
        fun recordId(t:CallTranscript)="${t.recordingId}|${t.sourceVersion}|${t.audioSha256}"
        /** Digits only for a phone number, otherwise the trimmed name; null when unknown. */
        fun counterpartKey(counterpart:String?):String? {
            val value=counterpart?.trim().orEmpty();if(value.isEmpty())return null
            if(value.contains('@') && !value.contains(' ')) return value.lowercase()
            return if(value.matches(Regex("^[0-9+\\-(). ]+$"))) value.filter { it.isDigit() }.ifEmpty { null } else value
        }
        /** The one key every name is compared by (spaces, honorifics, company-form marks and punctuation out, lower case). */
        fun canonName(name:String?):String=NameKey.canon(name)
        fun canonCompany(name:String?):String=NameKey.canonCompany(name)
        /** Bump when the key rules or the stored data need one more pass in [migrate]. */
        const val MIGRATION_VERSION=2
        /** Name as stored: trimmed, inner whitespace collapsed; null when empty. */
        fun normName(name:String?):String? = name?.trim()?.replace(Regex("\\s+")," ")?.takeIf { it.isNotEmpty() }
        /** A name with any phone-number-shaped part removed (a filename like "홍길동 010-…"): numbers stay on the phone. */
        fun noNumber(name:String?):String? = name?.let { kr.callbrief.core.PrivacyRules.removePhoneTokens(it).trim().takeIf { t -> t.isNotEmpty() && !t.all { c -> c.isDigit() || c=='-' || c==' ' } } }
        /** A phone number (digits) or a mail address: what identifies a person. Anything else is a name. */
        fun isContact(key:String?)=key!=null && (key.all { it.isDigit() } || key.contains('@'))
        /** Personal verdicts in a row before a number is remembered as personal (one wrong verdict must not hide a work contact). */
        const val PERSONAL_STREAK=2
        const val SITE_MEMO_MAX=600;const val COMPANY_MEMO_MAX=400
        /** Raw texts and mail sent with one request for cross-checking. */
        const val RECENT_TEXT_CHARS=3000
        fun kstDay(ms:Long):LocalDate=Instant.ofEpochMilli(ms).atZone(KST).toLocalDate()
        fun kstTime(ms:Long?):String=ms?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)",java.util.Locale.KOREAN).format(Instant.ofEpochMilli(it).atZone(KST)) } ?: "시각 미상"
        private fun startOf(day:LocalDate)=day.atStartOfDay(KST).toInstant().toEpochMilli()
    }
    private val port=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    init {
        try { port.transaction { SCHEMA.forEach { port.execute(it) }
            fun addColumns(table:String,columns:List<Pair<String,String>>) {
                val have=rows("PRAGMA table_info($table)").mapNotNull { it.str("name") }.toSet()
                columns.filter { it.first !in have }.forEach { (c,t) -> port.execute("ALTER TABLE $table ADD COLUMN $c $t") }
            }
            addColumns("people",PEOPLE_COLUMNS);addColumns("companies",COMPANY_COLUMNS);addColumns("workflow_items",ITEM_COLUMNS)
            addColumns("call_inbox_sent",listOf("result_ms" to "INTEGER")) // B17: answer received, do not resend
            if(rows("PRAGMA table_info(issues)").none { it.str("name")=="record_keys" }) port.execute("ALTER TABLE issues ADD COLUMN record_keys TEXT NOT NULL DEFAULT '[]'")
            port.execute("CREATE UNIQUE INDEX IF NOT EXISTS people_ledger_id ON people(ledger_id)")
            port.execute("CREATE INDEX IF NOT EXISTS people_name_key ON people(name_key)")
            port.execute("CREATE INDEX IF NOT EXISTS companies_name_key ON companies(name_key)")
            migrate()
            // Calls stored before v1026 have no start time although the filename had one: fill it in, first on the
            // file rows automatic discovery left empty, then on the calls.
            if(rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='local_files'").isNotEmpty()) {
                rows("SELECT l.recording_id,r.display_name FROM local_files l JOIN recordings r ON r.recording_id=l.recording_id WHERE l.filename_time_candidate_ms IS NULL")
                    .forEach { f -> kr.callbrief.core.RecordingNames.startedAt(f.str("display_name").orEmpty())?.toEpochMilli()?.let { port.execute("UPDATE local_files SET filename_time_candidate_ms=? WHERE recording_id=?",it,f.str("recording_id")) } }
                port.execute("""UPDATE call_records SET started_ms=(SELECT l.filename_time_candidate_ms FROM local_files l WHERE l.recording_id=call_records.recording_id)
                    WHERE started_ms IS NULL AND recording_id IN (SELECT recording_id FROM local_files WHERE filename_time_candidate_ms IS NOT NULL)""")
            }
            null } } catch(e:Exception) { port.close();throw e }
    }
    /** Brings what older versions stored to the current design (review B9, B10, B20); every step is safe to repeat, and the
     * whole pass runs once per [MIGRATION_VERSION] (table app_migration), not at every open (review L3). */
    private fun migrate() {
        if((rows("SELECT version FROM app_migration WHERE id=1").firstOrNull()?.long("version") ?: 0)>=MIGRATION_VERSION) return
        // One key for every name (B10, M1): every row gets the key of the current rule, and companies that now share a key become one.
        rows("SELECT id,name FROM people").forEach { port.execute("UPDATE people SET name_key=? WHERE id=?",personKey(it.str("name")),it.str("id")) }
        rows("SELECT id,name FROM companies").forEach { port.execute("UPDATE companies SET name_key=? WHERE id=?",companyKey(it.str("name")),it.str("id")) }
        rows("SELECT id,name_key FROM companies ORDER BY updated_ms DESC,id").groupBy { it.str("name_key").orEmpty() }.forEach { (_,same) ->
            same.drop(1).forEach { mergeCompany(it.str("id")!!,same.first().str("id")!!) } }
        rows("SELECT name_key,person_id FROM person_aliases").forEach { a ->
            val old=a.str("name_key")!!;val k=NameKey.canon(old).ifEmpty { old }
            if(k!=old) { port.execute("DELETE FROM person_aliases WHERE name_key=?",old);port.execute("INSERT OR REPLACE INTO person_aliases VALUES(?,?)",k,a.str("person_id")) } }
        // One number, several people (B9).
        port.execute("INSERT OR IGNORE INTO people_phones(phone_key,person_id) SELECT phone_key,id FROM people WHERE phone_key IS NOT NULL")
        // Gone: the API-key route (a record waiting for a key waits for the task like any other) and the unused segment copy (B20).
        port.execute("UPDATE call_records SET state='TRANSCRIBED' WHERE state='WAITING_KEY'")
        port.execute("UPDATE call_records SET segments_json='[]' WHERE segments_json!='[]'")
        // The sealed store holds 16 MB; a log line when it is four fifths full is the only warning (B20).
        runCatching {
            val bytes=(rows("PRAGMA page_count").single().long("page_count") ?: 0)*(rows("PRAGMA page_size").single().long("page_size") ?: 0)
            if(bytes>=DB_LIMIT_BYTES*DB_WARN_SHARE) android.util.Log.w(TAG,"database ${bytes/1024/1024} MB of ${DB_LIMIT_BYTES/1024/1024} MB")
        }
        port.execute("INSERT OR REPLACE INTO app_migration VALUES(1,?)",MIGRATION_VERSION)
    }
    override fun close()=port.close()
    private fun rows(sql:String,vararg args:Any?)=port.query(sql,*args)
    private fun Map<String,Any?>.long(k:String)=(this[k] as? Number)?.toLong()
    private fun Map<String,Any?>.str(k:String)=this[k] as? String

    /** Idempotent: the same recording version is stored once. Returns true when it was new. */
    fun insert(t:CallTranscript):Boolean=port.transaction {
        val id=recordId(t)
        if(rows("SELECT 1 FROM call_records WHERE record_id=?",id).isNotEmpty()) return@transaction false
        val now=clock()
        // The segments are not kept: nothing reads them and they doubled the size of every record (B20).
        port.execute("INSERT INTO call_records(record_id,recording_id,started_ms,duration_ms,counterpart,counterpart_key,transcript,segments_json,state,created_ms) VALUES(?,?,?,?,?,?,?,'[]',?,?)",
            id,t.recordingId,t.originalStartedAtMs,t.durationMs,t.counterpart,counterpartKey(t.counterpart),t.text,TRANSCRIBED,now)
        trim(now)
        true
    }
    /** Keeps the sealed store small (B20). A transcript goes 14 days after the record got its result, whatever state it
     * is in now (one that never got a result keeps its words so it can still be analysed); the full analysis of a call
     * older than 90 days shrinks to its summary and names. */
    fun trim(now:Long) {
        port.execute("UPDATE call_records SET transcript='' WHERE analyzed_ms IS NOT NULL AND created_ms<CAST(? AS INTEGER) AND transcript!=''",now-TRANSCRIPT_KEEP_DAYS*86_400_000L)
        rows("""SELECT record_id,analysis_json FROM call_records WHERE analysis_json LIKE '%"keyPoints"%' AND COALESCE(started_ms,created_ms)<CAST(? AS INTEGER) LIMIT 200""",now-ANALYSIS_KEEP_DAYS*86_400_000L).forEach { r ->
            val root=runCatching { JSONObject(r.str("analysis_json")!!) }.getOrNull() ?: return@forEach
            val a=root.optJSONObject("analysis") ?: return@forEach
            val small=JSONObject().put("summary",a.optString("summary")).put("business",a.optBoolean("business",true))
            listOf("counterpartName","counterpartCompany").forEach { k -> a.optString(k).takeIf { v -> v.isNotEmpty() && v!="null" }?.let { v -> small.put(k,v) } }
            // The items this call made or changed stay known (id and title), so "통화 보기" still finds them (review L2).
            val kept=JSONArray()
            root.optJSONArray("applied")?.let { list -> for(i in 0 until list.length()) { val x=list.optJSONObject(i) ?: continue
                if(x.optString("itemId").isNotEmpty()) kept.put(JSONObject().put("op",x.optString("op")).put("itemId",x.optString("itemId")).put("title",x.optString("title")).put("due","").put("applied",x.optBoolean("applied",false))) } }
            port.execute("UPDATE call_records SET analysis_json=? WHERE record_id=?",JSONObject().put("analysis",small).put("applied",kept).toString(),r.str("record_id")) }
    }

    /** Stores one text-message thread or mail as a record the analysis treats like a call. A pseudo recording row
     * (no audio, no source version, so transcription never sees it) lets its check items attach as for calls.
     * Idempotent per [id] and text. Returns true when it was new. */
    fun insertMessage(id:String,atMs:Long,counterpart:String?,text:String):Boolean {
        port.transaction {
            port.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            port.execute("INSERT OR IGNORE INTO folder_grants VALUES('messages',?,'content://messages',1,1,1)",GeneralLocalStore.OWNER)
            port.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'messages',?,?,'text/plain',?,?,?,1,1,1)",
                id,GeneralLocalStore.OWNER,id,MessageKind.of(id),text.length,atMs,atMs);null
        }
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return insert(CallTranscript(id,1,sha,atMs,0,counterpart,text,emptyList(),"text","0".repeat(64),false)) // text once, not twice (16 MB store)
    }
    /** Phone numbers (digits only) of business contacts: everyone the ledger has a number for, and every caller. */
    fun knownPhones():Set<String> =(rows("SELECT phone_key AS k FROM people_phones")+rows("SELECT DISTINCT counterpart_key AS k FROM call_records WHERE counterpart_key IS NOT NULL"))
        .mapNotNull { it.str("k") }.filter { k -> k.length>=8 && k.all { it.isDigit() } }.toSet()

    private fun map(r:Map<String,Any?>):CallRecord {
        val json=r.str("analysis_json")?.let { JSONObject(it) }
        val applied=json?.optJSONArray("applied")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Applied(it.getString("op"),it.getString("itemId"),it.getString("title"),it.optString("due").ifEmpty { null },it.getBoolean("applied"),it.optString("note").ifEmpty { null },reason=it.optString("reason").ifEmpty { null }) } }.orEmpty()
        return CallRecord(r.str("record_id")!!,r.str("recording_id")!!,r.long("started_ms"),r.long("duration_ms") ?: 0,r.str("counterpart"),r.str("counterpart_key"),
            r.str("transcript").orEmpty(),r.str("state")!!,r.str("summary"),json?.optJSONObject("analysis")?.let { ClaudeCallAnalysis.parse(it) },applied,
            r.str("error"),r.long("created_ms")!!,r.long("analyzed_ms"),r.str("linked_person"))
    }
    /** Subscription mode: when the transcript was last dropped into the inbox. */
    fun sentMs(id:String):Long?=rows("SELECT sent_ms FROM call_inbox_sent WHERE record_id=?",id).singleOrNull()?.long("sent_ms")
    fun markSent(id:String,ms:Long)=port.execute("INSERT OR REPLACE INTO call_inbox_sent(record_id,sent_ms) VALUES(?,?)",id,ms)
    /** When a request last went up to the mailbox (heartbeat lastUploadMs), or null when none ever did. */
    fun lastSentMs():Long?=rows("SELECT MAX(sent_ms) AS m FROM call_inbox_sent").firstOrNull()?.long("m")
    /** When the task's answer for this record was received (null: not yet, or set aside as broken). A received record is not sent again. */
    fun receivedMs(id:String):Long?=rows("SELECT result_ms FROM call_inbox_sent WHERE record_id=?",id).singleOrNull()?.long("result_ms")
    fun setReceived(id:String,ms:Long?)=port.execute("UPDATE call_inbox_sent SET result_ms=? WHERE record_id=?",ms,id)
    /** A record went up and its answer has not come yet (what the :45 result pass waits for, v2.4). */
    fun awaitingResult():Boolean =rows("SELECT 1 FROM call_records c JOIN call_inbox_sent s ON s.record_id=c.record_id WHERE c.state='TRANSCRIBED' AND s.result_ms IS NULL LIMIT 1").isNotEmpty()
    private val withPerson="SELECT c.*,p.name AS linked_person FROM call_records c LEFT JOIN call_links l ON l.record_id=c.record_id LEFT JOIN people p ON p.id=l.person_id"
    fun record(id:String):CallRecord?=rows("$withPerson WHERE c.record_id=?",id).singleOrNull()?.let(::map)
    fun pending():List<CallRecord> =rows("SELECT * FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT') ORDER BY created_ms").map(::map)
    /** Calls of one KST day, newest first. A call without a start time counts on the day it was stored. */
    fun day(day:LocalDate):List<CallRecord> =rows("$withPerson WHERE COALESCE(c.started_ms,c.created_ms)>=CAST(? AS INTEGER) AND COALESCE(c.started_ms,c.created_ms)<CAST(? AS INTEGER) ORDER BY COALESCE(c.started_ms,c.created_ms) DESC",
        startOf(day),startOf(day.plusDays(1))).map(::map).filter { it.analysis?.business!=false } // personal calls stay hidden
    fun setState(id:String,state:String,error:String?=null)=port.execute("UPDATE call_records SET state=?,error=? WHERE record_id=?",state,error,id)
    /** "모두 다시 분석": waiting and failed records go back to the queue. */
    fun requeueAll():Int=port.transaction {
        val n=rows("SELECT COUNT(*) AS n FROM call_records WHERE state IN ('FAILED','WAITING_CONSENT')").single().long("n") ?: 0
        port.execute("UPDATE call_records SET state='TRANSCRIBED',error=NULL WHERE state IN ('FAILED','WAITING_CONSENT')");n.toInt()
    }
    fun counts():Map<String,Int> =rows("SELECT state,COUNT(*) AS n FROM call_records GROUP BY state").associate { it.str("state")!! to (it.long("n") ?: 0).toInt() }

    // ---- ledger ---------------------------------------------------------------
    private fun personById(id:String?)=if(id==null) null else rows("SELECT * FROM people WHERE id=?",id).firstOrNull()
    private fun companyById(id:String?)=if(id==null) null else rows("SELECT * FROM companies WHERE id=?",id).firstOrNull()
    /** The key a person's name is stored and found by (spaces, titles, company-form marks removed). */
    private fun personKey(name:String?):String=NameKey.canon(name).ifEmpty { name.orEmpty().lowercase().filterNot { it.isWhitespace() } }
    /** Companies are keyed without the title removal people get (review L1). */
    private fun companyKey(name:String?):String=NameKey.canonCompany(name).ifEmpty { name.orEmpty().lowercase().filterNot { it.isWhitespace() } }
    /** People who answer this phone number (mail address), the one who answered last first. */
    private fun peopleByNumber(phone:String)=rows("SELECT p.* FROM people_phones x JOIN people p ON p.id=x.person_id WHERE x.phone_key=? ORDER BY p.updated_ms DESC",phone)
    /** Every number of a person: people_phones and the old people.phone_key. */
    private fun numbersOf(p:Map<String,Any?>):Set<String> =(rows("SELECT phone_key FROM people_phones WHERE person_id=?",p.str("id")).mapNotNull { it.str("phone_key") }+listOfNotNull(p.str("phone_key"))).toSet()
    private fun hasNumber(p:Map<String,Any?>)=p.str("phone_key")!=null || rows("SELECT 1 FROM people_phones WHERE person_id=?",p.str("id")).isNotEmpty()
    /** Gives [personId] the number; people.phone_key (unique) is set only when nobody else has it first. */
    private fun addNumber(personId:String,phone:String) {
        port.execute("INSERT OR IGNORE INTO people_phones VALUES(?,?)",phone,personId)
        port.execute("UPDATE people SET phone_key=? WHERE id=? AND phone_key IS NULL AND NOT EXISTS(SELECT 1 FROM people WHERE phone_key=?)",phone,personId,phone)
    }
    /** The person behind a counterpart key: the latest linked call first, then phone number, then name. */
    private fun personFor(key:String?):Map<String,Any?>? {
        if(key==null) return null
        rows("SELECT l.person_id FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE c.counterpart_key=? AND l.person_id IS NOT NULL ORDER BY COALESCE(c.started_ms,c.created_ms) DESC,c.created_ms DESC LIMIT 1",key)
            .firstOrNull()?.let { personById(it.str("person_id"))?.let { p -> return p } }
        return if(isContact(key)) peopleByNumber(key).firstOrNull()
        else personNamed(key).firstOrNull()
    }
    /** People with this name (honorifics and spaces ignored), or whose earlier wrong name the user corrected. */
    private fun personNamed(name:String):List<Map<String,Any?>> {
        val k=personKey(name)
        return rows("SELECT * FROM people WHERE name_key=?",k).ifEmpty { rows("SELECT p.* FROM person_aliases a JOIN people p ON p.id=a.person_id WHERE a.name_key=?",k) }
    }
    private fun isNamed(p:Map<String,Any?>,name:String):Boolean { val k=personKey(name)
        return p.str("name_key")==k || rows("SELECT 1 FROM person_aliases WHERE name_key=? AND person_id=?",k,p.str("id")).isNotEmpty() }
    private fun findCompany(name:String):String? { val k=companyKey(name)
        return rows("SELECT id FROM companies WHERE name_key=? ORDER BY updated_ms DESC",k).firstOrNull()?.str("id")
            ?: rows("SELECT a.company_id AS id FROM company_aliases a JOIN companies c ON c.id=a.company_id WHERE a.name_key=?",k).firstOrNull()?.str("id") }
    /** The company of this name; a spelling one jamo off the company [near] (the one the same number already belongs to)
     * is that company, and is remembered as another spelling of it. Otherwise a new company. */
    private fun upsertCompany(name:String,now:Long,near:String?=null):String {
        val clean=normName(name)!!
        val old=findCompany(clean) ?: near?.takeIf { id -> companyById(id)?.let { NameKey.similar(clean,it.str("name"),company=true) } == true }
            ?.also { port.execute("INSERT OR IGNORE INTO company_aliases VALUES(?,?)",companyKey(clean),it) }
        if(old!=null) { port.execute("UPDATE companies SET updated_ms=? WHERE id=?",now,old);return old }
        val id="co_"+UUID.randomUUID().toString().replace("-","")
        port.execute("INSERT INTO companies(id,name,trade,memo,updated_ms,name_key) VALUES(?,?,NULL,'',?,?)",id,clean,now,companyKey(clean));return id
    }
    /** [from] becomes [into]: its people, calls and check items move over, and its spelling stays as another name. */
    private fun mergeCompany(from:String,into:String) {
        if(from==into) return
        val gone=companyById(from) ?: return
        port.execute("UPDATE people SET company_id=? WHERE company_id=?",into,from);port.execute("UPDATE call_links SET company_id=? WHERE company_id=?",into,from)
        port.execute("UPDATE task_companies SET company_id=? WHERE company_id=?",into,from);port.execute("UPDATE company_aliases SET company_id=? WHERE company_id=?",into,from)
        // Both memos are kept: the surviving company's first, then the other's, cut at the limit (review L1).
        val keepMemo=companyById(into)?.str("memo").orEmpty().trim();val goneMemo=gone.str("memo").orEmpty().trim()
        val memo=when { goneMemo.isEmpty() || goneMemo==keepMemo -> keepMemo;keepMemo.isEmpty() -> goneMemo;else -> (keepMemo+"\n"+goneMemo).take(COMPANY_MEMO_MAX) }
        port.execute("UPDATE companies SET trade=COALESCE(trade,?),memo=? WHERE id=?",gone.str("trade"),memo,into)
        port.execute("DELETE FROM companies WHERE id=?",from)
        gone.str("name_key")?.takeIf { it.isNotEmpty() && it!=companyById(into)?.str("name_key") }?.let { port.execute("INSERT OR IGNORE INTO company_aliases VALUES(?,?)",it,into) }
    }
    /** By phone number when the counterpart is a number, otherwise by name. One number may belong to several people (an
     * office line): the one with this name (a misheard jamo allowed), else one known only by number; a different name
     * is a new person on the same number (B9). A same-name person without a number adopts it; a near name does only
     * in the same company. */
    private fun upsertPerson(cp:ClaudeCallAnalysis.Counterpart,phone:String?,companyId:String?,now:Long):String? {
        val name=normName(cp.personName) ?: return null
        var row:Map<String,Any?>?=null
        if(phone!=null) {
            val sharers=peopleByNumber(phone)
            row=sharers.firstOrNull { isNamed(it,name) } ?: sharers.firstOrNull { NameKey.similar(name,it.str("name")) } ?: sharers.firstOrNull { it.str("name").isNullOrBlank() }
        }
        if(row==null) {
            val free:(Map<String,Any?>)->Boolean={ phone==null || !hasNumber(it) }
            val same=personNamed(name).filter(free)
            row=same.firstOrNull { it.str("company_id")==companyId } ?: same.firstOrNull { it.str("company_id")==null || companyId==null }
            if(row==null && companyId!=null) row=rows("SELECT * FROM people WHERE company_id=? AND is_staff=0 AND name!=''",companyId).firstOrNull { free(it) && NameKey.similar(name,it.str("name")) }
        }
        val role=normName(cp.role)
        if(row==null) {
            val id="pe_"+UUID.randomUUID().toString().replace("-","")
            port.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key) VALUES(?,?,NULL,?,?,?,?,?)",id,name,companyId,role,if(cp.isStaff) 1 else 0,now,personKey(name))
            phone?.let { addNumber(id,it) };return id
        }
        val id=row.str("id")!!
        val staff=if(cp.isStaff) 1 else if(companyId!=null) 0 else (row.long("is_staff") ?: 0)
        port.execute("UPDATE people SET company_id=COALESCE(?,company_id),role=COALESCE(?,role),is_staff=?,updated_ms=? WHERE id=?",companyId,role,staff,now,id)
        phone?.let { addNumber(id,it) }
        // A person known so far only by number gets the name heard in the call (the repository ledger may correct it).
        if(row.str("name").isNullOrBlank()) port.execute("UPDATE people SET name=?,name_key=? WHERE id=? AND name_locked=0",name,personKey(name),id)
        return id
    }
    fun siteMemo():String? = rows("SELECT text FROM site_memo WHERE id=1").firstOrNull()?.str("text")?.takeIf { it.isNotBlank() }
    fun companyCount():Int=(rows("SELECT COUNT(*) AS n FROM companies").single().long("n") ?: 0).toInt()
    /** All companies, the one called most recently first. */
    fun companies():List<CompanyRow> =rows("""SELECT co.id,co.name,co.trade,
        (SELECT COUNT(*) FROM task_companies tc JOIN workflow_items i ON i.id=tc.item_id WHERE tc.company_id=co.id AND i.status NOT IN ('done','merged') AND i.kind='TODO') AS open_n,
        (SELECT MAX(COALESCE(c.started_ms,c.created_ms)) FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE l.company_id=co.id) AS last_ms
        FROM companies co ORDER BY COALESCE(last_ms,0) DESC,co.name""")
        .map { CompanyRow(it.str("id")!!,it.str("name")!!,it.str("trade"),(it.long("open_n") ?: 0).toInt(),it.long("last_ms")) }
    fun companyDetail(id:String):CompanyDetail? {
        val co=companyById(id) ?: return null
        val name=co.str("name")!!
        val tasks=rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.edition,i.created_ms,i.ai_note,i.tentative FROM task_companies tc JOIN workflow_items i ON i.id=tc.item_id
            WHERE tc.company_id=? AND i.status NOT IN ('done','merged') AND i.kind='TODO' ORDER BY i.created_ms DESC""",id)
            .map { OpenItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("edition").orEmpty(),null,name,it.long("created_ms") ?: 0,it.str("ai_note"),it.long("tentative")==1L) }
        val calls=rows("""SELECT c.record_id,COALESCE(c.started_ms,c.created_ms) AS day_ms,c.summary,p.name AS person FROM call_links l JOIN call_records c ON c.record_id=l.record_id
            LEFT JOIN people p ON p.id=l.person_id WHERE l.company_id=? ORDER BY day_ms DESC LIMIT 10""",id)
            .map { CompanyCall(it.str("record_id")!!,it.long("day_ms") ?: 0,it.str("person"),it.str("summary")) }
        return CompanyDetail(id,name,co.str("trade"),co.str("memo").orEmpty(),tasks,calls,peopleOf(id),issues(name))
    }
    private fun personRow(r:Map<String,Any?>)=PersonRow(r.str("id")!!,r.str("name")!!,r.str("role"),r.long("is_staff")==1L)
    /** People of one company, or with [companyId] null those without a company (staff and unknown). */
    fun peopleOf(companyId:String?):List<PersonRow> =
        (if(companyId==null) rows("SELECT * FROM people WHERE (company_id IS NULL OR is_staff=1) AND name!='' ORDER BY updated_ms DESC")
         else rows("SELECT * FROM people WHERE company_id=? AND is_staff=0 AND name!='' ORDER BY updated_ms DESC",companyId)).map(::personRow)
    /** The user fixes a misheard name. The old name is kept as an alias so the same mishearing maps back to this person. */
    fun renamePerson(id:String,name:String):Boolean=port.transaction {
        val clean=normName(name)?.take(30) ?: return@transaction false
        val old=personById(id)?.str("name") ?: return@transaction false
        if(old==clean) return@transaction true
        port.execute("UPDATE people SET name=?,name_key=?,name_locked=1,updated_ms=? WHERE id=?",clean,personKey(clean),clock(),id)
        val lid=ensureLedgerId(id)
        port.execute("INSERT INTO ledger_edits VALUES(?,?,?)","e_"+UUID.randomUUID().toString().replace("-",""),
            JSONObject().put("type","rename").put("id",lid).put("name",clean).put("atMs",clock()).toString(),clock())
        port.execute("DELETE FROM person_aliases WHERE name_key=?",personKey(clean))
        if(personKey(old)!=personKey(clean)) port.execute("INSERT OR REPLACE INTO person_aliases VALUES(?,?)",personKey(old),id)
        true
    }

    /** Everything the model should know, because it remembers nothing between calls: the site memo, who is calling
     * (person and company cards), that company's latest calls and open work, related work of the same trade,
     * all other open work with its company, and the name list for typo correction. */
    /** [wide]: when a result is checked, the texts are read more widely than when it was sent. An old record older than
     * [FirstPass.TASK_DAYS] makes no check items, so its request carries no open work (smaller, round 2). */
    /** Who does item [itemId] as the call [recordId] that made it said: the owner of its task in the stored answer (the applied list
     * runs in the same order as the tasks); null when not stored (an old answer shrunk after 90 days, an item a person wrote). */
    private fun madeOwner(recordId:String,itemId:String):String? {
        val root=rows("SELECT analysis_json FROM call_records WHERE record_id=?",recordId).singleOrNull()?.str("analysis_json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val applied=root.optJSONArray("applied") ?: return null
        val tasks=root.optJSONObject("analysis")?.optJSONArray("tasks") ?: return null
        val i=(0 until applied.length()).firstOrNull { applied.optJSONObject(it)?.optString("itemId")==itemId } ?: return null
        return tasks.optJSONObject(i)?.optString("owner")?.trim()?.lowercase()?.takeIf { it in setOf("me","them","unknown") }
    }
    fun request(r:CallRecord,old:Boolean=false,wide:Boolean=false):ClaudeCallRequest {
        val lean=old && clock()-r.dayMs>FirstPass.TASK_DAYS*86_400_000L
        val key=r.counterpartKey
        val person=personFor(key)
        val company=companyById(person?.str("company_id")?.takeIf { person.long("is_staff")!=1L })
        val companyId=company?.str("id");val trade=company?.str("trade")
        // Open work of other companies and trades is no longer sent (design C5): this company's and this person's only.
        // Only what existed at this call's time (review B7): a record analysed late must not see what came after it.
        val before=r.dayMs
        val sameCounterpart=if(key==null) emptySet() else rows("SELECT b.item_id FROM call_task_bindings b JOIN call_records c ON c.record_id=b.record_id WHERE c.counterpart_key=?",key).mapNotNull { it.str("item_id") }.toSet()
        class Open(val task:ClaudeCallRequest.OpenTask,val companyId:String?,val madeBy:String?)
        // The call that made each item (binding) gives who asked for it, by the name the screens show (design C5); the quote is the item's own evidence.
        val all=rows("""SELECT i.id,i.title,i.due_text,i.status,i.created_ms,i.evidence_quote,co.id AS cid,co.name AS cname,b.record_id AS made_by,p.name AS pname FROM workflow_items i
            LEFT JOIN task_companies tc ON tc.item_id=i.id LEFT JOIN companies co ON co.id=tc.company_id
            LEFT JOIN call_task_bindings b ON b.item_id=i.id LEFT JOIN call_links l ON l.record_id=b.record_id LEFT JOIN people p ON p.id=l.person_id
            WHERE i.status NOT IN ('done','merged') AND i.kind='TODO' AND i.created_ms<=CAST(? AS INTEGER) ORDER BY i.created_ms DESC LIMIT 300""",before)
            .map { Open(ClaudeCallRequest.OpenTask(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty(),it.str("status")!!,it.str("cname"),it.long("created_ms")?.let { ms -> kstDay(ms).toString() },
                person=it.str("pname"),quote=it.str("evidence_quote")),it.str("cid"),it.str("made_by")) }
        val own=if(lean) emptyList() else all.filter { (companyId!=null && it.companyId==companyId) || it.task.id in sameCounterpart }.take(30)
            .map { o -> o.task.copy(owner=o.madeBy?.let { madeOwner(it,o.task.id) }) }
        // The same person's calls (design E4): by the person linked to them, so calls known by name and by number meet; by the key when no person is known.
        val personId=person?.str("id")
        val summaries=if(personId!=null) rows("""SELECT c.summary FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE l.person_id=? AND c.state='ANALYZED' AND c.record_id!=?
            AND c.summary IS NOT NULL AND COALESCE(c.started_ms,c.created_ms)<CAST(? AS INTEGER) ORDER BY COALESCE(c.started_ms,c.created_ms) DESC LIMIT 3""",personId,r.recordId,before).mapNotNull { it.str("summary") }
            else if(key==null) emptyList() else rows("SELECT summary FROM call_records WHERE counterpart_key=? AND state='ANALYZED' AND record_id!=? AND COALESCE(started_ms,created_ms)<CAST(? AS INTEGER) ORDER BY COALESCE(started_ms,created_ms) DESC LIMIT 3",key,r.recordId,before)
            .mapNotNull { it.str("summary") }
        val companyCalls=if(companyId==null) emptyList() else rows("""SELECT COALESCE(c.started_ms,c.created_ms) AS day_ms,c.summary,p.name AS person FROM call_links l JOIN call_records c ON c.record_id=l.record_id
            LEFT JOIN people p ON p.id=l.person_id WHERE l.company_id=? AND c.state='ANALYZED' AND c.record_id!=? AND c.summary IS NOT NULL AND COALESCE(c.started_ms,c.created_ms)<CAST(? AS INTEGER) ORDER BY day_ms DESC LIMIT 5""",companyId,r.recordId,before)
            .map { ClaudeCallRequest.PastCall(kstDay(it.long("day_ms") ?: 0).toString(),it.str("person"),it.str("summary").orEmpty()) }
        val todayCalls=if(person!=null || company!=null) emptyList() else day(kstDay(r.dayMs)).filter { it.recordId!=r.recordId && it.state==ANALYZED && it.summary!=null && it.dayMs<before }.take(5)
            .map { ClaudeCallRequest.PastCall(CallUi.time(it.startedMs),noNumber(it.displayName.takeIf { n -> n!="상대 미상" }),it.summary!!) }
        val companyNames=rows("SELECT name FROM companies ORDER BY updated_ms DESC LIMIT 60").mapNotNull { it.str("name") }
        // The name list carries what is known of each person (company, title, and the wrong spellings the user corrected).
        val aliases=rows("SELECT person_id,name_key FROM person_aliases").groupBy({ it.str("person_id") },{ it.str("name_key").orEmpty() })
        val people=rows("""SELECT p.id,p.name,p.role,p.is_staff,co.name AS cname FROM people p LEFT JOIN companies co ON co.id=p.company_id WHERE p.name!='' ORDER BY p.updated_ms DESC LIMIT 300""")
        val personNames=people.mapNotNull { it.str("name") }.distinct()
        val personDetails=LinkedHashMap<String,String>()
        for(p in people) { val n=p.str("name") ?: continue;if(n in personDetails) continue
            val wrong=aliases[p.str("id")].orEmpty().filter { it.isNotBlank() && it!=personKey(n) }
            val base=listOfNotNull(if(p.long("is_staff")==1L) "우리 회사" else p.str("cname"),p.str("role")).joinToString(" ")
            val about=base+(if(wrong.isEmpty()) "" else (if(base.isEmpty()) "" else ", ")+"오인식 "+wrong.joinToString(" "))
            if(about.isNotBlank()) personDetails[n]=about }
        val phone=person?.let { p -> p.str("phone_key") ?: key?.takeIf { isContact(it) } }
        val sameNumber=if(phone==null) emptyList() else peopleByNumber(phone).filter { it.str("id")!=person?.str("id") && it.str("name").orEmpty().isNotBlank() }
            .map { o -> o.str("name")!!+(listOfNotNull(companyById(o.str("company_id"))?.str("name"),o.str("role")).joinToString(" ").takeIf { it.isNotBlank() }?.let { "($it)" } ?: "") }
        return ClaudeCallRequest(r.transcript,kstTime(r.startedMs),r.counterpart,r.durationMs,own,summaries,
            siteMemo = siteMemo(),
            person = person?.let { ClaudeCallRequest.PersonCard(it.str("name")!!,it.str("role"),it.long("is_staff")==1L,company?.str("name")) },
            company = company?.let { ClaudeCallRequest.CompanyCard(it.str("name")!!,trade,it.str("memo").orEmpty()) },
            companyCalls = companyCalls,todayCalls = todayCalls,
            companyNames = companyNames,personNames = personNames,kind = r.kind,
            direction = rows("SELECT direction FROM call_record_log WHERE record_id=?",r.recordId).firstOrNull()?.str("direction"),firstPass = old,
            counterpartId = person?.str("ledger_id"),
            callLog = rows("SELECT x.direction,l.name,l.start_ms,l.duration_ms FROM call_record_log x JOIN call_log l ON l.log_id=x.log_id WHERE x.record_id=?",r.recordId).firstOrNull()
                ?.let { ClaudeCallRequest.CallLogView(it.str("name"),it.str("direction"),kstTime(it.long("start_ms")),it.long("duration_ms") ?: 0) },
            recentTexts = recentTexts(r,listOfNotNull(key,person?.str("phone_key")).distinct(),if(wide) RECENT_TEXT_CHARS*3 else RECENT_TEXT_CHARS),
            recentlyDone = if(lean) emptyList() else recentlyDone(companyId,before),
            sameDayCompany = if(companyId==null) emptyList() else rows("""SELECT c.started_ms,c.created_ms,c.summary,p.name AS person FROM call_links l JOIN call_records c ON c.record_id=l.record_id
                LEFT JOIN people p ON p.id=l.person_id WHERE l.company_id=? AND c.record_id!=? AND c.state='ANALYZED' AND c.summary IS NOT NULL
                AND COALESCE(c.started_ms,c.created_ms)>=CAST(? AS INTEGER) AND COALESCE(c.started_ms,c.created_ms)<CAST(? AS INTEGER) AND (l.person_id IS NULL OR l.person_id!=?)
                ORDER BY COALESCE(c.started_ms,c.created_ms) DESC LIMIT 5""",companyId,r.recordId,startOf(kstDay(r.dayMs)),before,person?.str("id") ?: "")
                .map { ClaudeCallRequest.PastCall(CallUi.time(it.long("started_ms") ?: it.long("created_ms")),noNumber(it.str("person")),it.str("summary").orEmpty()) },
            personDetails = personDetails,sameNumberPeople = sameNumber)
    }

    /** Work texts and mail with the same person in the 7 days before this record (and the day after), newest first, raw,
     * up to [RECENT_TEXT_CHARS]: what the analysis cross-checks a call against. Personal ones were deleted already. */
    private fun recentTexts(r:CallRecord,keys:List<String>,chars:Int=RECENT_TEXT_CHARS):String {
        if(keys.isEmpty()) return ""
        val marks=keys.joinToString(",") { "?" }
        val parts=mutableListOf<String>();var used=0
        // Newest first; ones not analysed yet are included and marked (a text just before the call matters most).
        for(t in rows("""SELECT recording_id,state,COALESCE(started_ms,created_ms) AS at,transcript,analysis_json FROM call_records WHERE counterpart_key IN ($marks) AND record_id!=?
            AND (recording_id LIKE 'sms:%' OR recording_id LIKE 'mail:%') AND state IN ('ANALYZED','TRANSCRIBED','WAITING_CONSENT') AND transcript!=''
            AND COALESCE(started_ms,created_ms)>=CAST(? AS INTEGER) AND COALESCE(started_ms,created_ms)<CAST(? AS INTEGER) ORDER BY COALESCE(started_ms,created_ms) DESC""",*keys.toTypedArray(),r.recordId,r.dayMs-7*86_400_000L,r.dayMs+86_400_000L)) {
            if(t.str("analysis_json")?.let { JSONObject(it).optJSONObject("analysis")?.optBoolean("business",true) }==false) continue
            val at=t.long("at") ?: 0
            val head="[${DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(at).atZone(KST))} ${MessageKind.of(t.str("recording_id")!!)}"+
                (if(at>r.dayMs) ", 통화 뒤" else "")+(if(t.str("state")!=ANALYZED) ", 아직 정리 전" else "")+"]\n"
            val room=chars-used-head.length;if(room<=40) break
            val body=t.str("transcript")!!.let { if(it.length>room) it.takeLast(room) else it } // the newest lines are kept
            parts+=head+body;used+=head.length+body.length+1
        }
        return parts.joinToString("\n").trim()
    }
    /** Check items finished or merged in the 30 days before [at] (this company first), so they are not made again. Ones asked for
     * after [at] are left out. */
    fun recentlyDone(companyId:String?,at:Long=clock()):List<String> =rows("""SELECT i.title FROM workflow_items i LEFT JOIN task_companies tc ON tc.item_id=i.id
        WHERE i.status IN ('done','merged') AND i.kind='TODO' AND COALESCE(i.done_ms,i.created_ms)>=CAST(? AS INTEGER) AND i.created_ms<=CAST(? AS INTEGER)
        ORDER BY CASE WHEN tc.company_id=? THEN 0 ELSE 1 END,COALESCE(i.done_ms,i.created_ms) DESC LIMIT 15""",at-30*86_400_000L,at,companyId ?: "")
        .mapNotNull { it.str("title") }
    fun openTaskIds():Set<String> =rows("SELECT id FROM workflow_items WHERE status NOT IN ('done','merged') AND kind='TODO'").mapNotNull { it.str("id") }.toSet()

    /** Stores the result and applies tasks in one transaction. User-edited and finished items are never changed. */
    fun applyAnalysis(id:String,a:ClaudeCallAnalysis):List<Applied> =port.transaction {
        val record=rows("SELECT recording_id,counterpart_key,state,COALESCE(started_ms,created_ms) AS day_ms FROM call_records WHERE record_id=?",id).singleOrNull() ?: return@transaction emptyList()
        if(record.str("state")==ANALYZED) return@transaction emptyList() // applied already: never twice
        val recording=record.str("recording_id")!!
        val callMs=record.long("day_ms") ?: clock()
        // Personal, not work: a text or mail is deleted outright; a call keeps only a bare "done" mark (so it is not
        // transcribed again) with its words wiped. Neither reaches the ledger, the lists or the check items.
        if(!a.business) {
            // A number seen to be personal twice in a row, and tied to no work, is remembered: its next calls and texts are not sent (design E9).
            record.str("counterpart_key")?.takeIf { k -> k.length>=8 && k.all { it.isDigit() } }?.let { notePersonal(it,id) }
            if(MessageKind.of(recording)!="통화") {
                port.execute("DELETE FROM call_inbox_sent WHERE record_id=?",id);port.execute("DELETE FROM call_records WHERE record_id=?",id)
                port.execute("DELETE FROM recordings WHERE recording_id=? AND grant_id='messages'",recording)
            } else port.execute("UPDATE call_records SET state='ANALYZED',transcript='',segments_json='[]',summary=NULL,analysis_json=?,analyzed_ms=? WHERE record_id=?",
                JSONObject().put("analysis",JSONObject().put("summary","개인 내용").put("business",false)).toString(),clock(),id)
            return@transaction emptyList()
        }
        // workflow_items needs the recording row; without it the tasks stay suggestions in the analysis only.
        val hasRecording=rows("SELECT 1 FROM recordings WHERE recording_id=?",recording).isNotEmpty()
        val case=rows("SELECT case_id FROM workflow_links WHERE recording_id=?",recording).singleOrNull()?.str("case_id")
        val now=clock()
        // Ledger first: who this was, their company, the memos; tasks then inherit the company.
        val cp=a.counterpart;val ckey=record.str("counterpart_key")
        // A work verdict breaks a run of personal ones (E9 safety).
        ckey?.let { port.execute("DELETE FROM personal_streaks WHERE phone_key=?",it) }
        val staffCall=cp?.isStaff==true
        // The company a person on this number already belongs to: a spelling one jamo off it is that company (B10).
        val onNumber=ckey?.takeIf { isContact(it) }?.let { peopleByNumber(it) }.orEmpty()
        val nearCompany=(onNumber.firstOrNull { NameKey.similar(cp?.personName,it.str("name")) } ?: onNumber.singleOrNull())?.str("company_id")
        var companyId=cp?.companyName?.takeIf { !staffCall && normName(it)!=null }?.let { upsertCompany(it,now,nearCompany) }
        val personId=if(normName(cp?.personName)!=null) upsertPerson(cp!!,ckey?.takeIf { isContact(it) },companyId,now) else personFor(ckey)?.str("id")
        if(companyId==null && !staffCall) companyId=personById(personId)?.takeIf { it.long("is_staff")!=1L }?.str("company_id")
        if(personId!=null || companyId!=null) port.execute("INSERT OR REPLACE INTO call_links VALUES(?,?,?)",id,personId,companyId)
        a.siteMemo?.trim()?.takeIf { it.isNotEmpty() }?.let { port.execute("INSERT OR REPLACE INTO site_memo VALUES(1,?,?)",it.take(SITE_MEMO_MAX),now) }
        if(companyId!=null) {
            val memo=a.companyMemo?.trim()?.takeIf { it.isNotEmpty() }?.take(COMPANY_MEMO_MAX);val trade=normName(a.companyTrade)?.take(20)
            if(memo!=null || trade!=null) port.execute("UPDATE companies SET memo=COALESCE(?,memo),trade=COALESCE(?,trade),updated_ms=? WHERE id=?",memo,trade,now,companyId)
        }
        fun taskCompany(name:String?)=name?.takeIf { normName(it)!=null }?.let { upsertCompany(it,now) }
        var noted=false
        val applied=a.tasks.map { t ->
            when(t.op) {
                "create" -> {
                    // The same open item, a near one of the same company, or one finished in the last 14 days is not made again (B11).
                    val named=t.company?.takeIf { normName(it)!=null }?.let { findCompany(it) ?: "?" }
                    if(!hasRecording) Applied("create","",t.title,t.due,false)
                    else if(isDuplicate(t.title,if(t.company?.let { normName(it) }!=null) named else companyId,now)) Applied("create","",t.title,t.due,false,duplicate=true) else {
                        val item="wf_item_"+UUID.randomUUID().toString().replace("-","")
                        // created_ms is when it was asked for (the call), the "요청 날짜" the screens show.
                        port.execute("INSERT INTO workflow_items(id,recording_id,case_id,kind,title,edition,due_text,status,revision,source_key,evidence_quote,created_ms,tentative,last_call_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",item,recording,case,"TODO",t.title.take(240),"",t.due.orEmpty(),"open",1,null,t.quote,callMs,if(t.tentative) 1 else 0,callMs)
                        port.execute("INSERT INTO call_task_bindings VALUES(?,?,?,?)",item,id,1,now)
                        (taskCompany(t.company) ?: companyId)?.let { port.execute("INSERT OR REPLACE INTO task_companies VALUES(?,?)",item,it) }
                        Applied("create",item,t.title,t.due,true)
                    }
                }
                else -> {
                    val item=t.taskId!!
                    val row=rows("""SELECT i.revision,i.status,i.created_ms,COALESCE(i.last_call_ms,i.created_ms) AS last_call,b.last_revision,(SELECT COUNT(*) FROM workflow_item_history h WHERE h.item_id=i.id) AS edits
                        FROM workflow_items i LEFT JOIN call_task_bindings b ON b.item_id=i.id WHERE i.id=? AND i.kind='TODO'""",item).singleOrNull()
                    if(row==null) Applied(t.op,item,t.title,t.due,false) else when {
                        // Finished, or asked for after this call (a late analysis of an old call must not rewrite what came later, B7).
                        row.str("status") in setOf("done","merged") || (row.long("created_ms") ?: 0)>callMs -> Applied(t.op,item,t.title,t.due,false)
                        // An earlier call than the one that last changed it (a first-pass batch answered after a newer call, C5):
                        // what came later stays, and no note either.
                        (row.long("last_call") ?: 0)>callMs -> Applied(t.op,item,t.title,t.due,false,reason="older")
                        // The user's own (changed by them, or never the AI's): title and date stay; a line says what this call suggests (B12).
                        row.long("last_revision")==null || (row.long("edits") ?: 0)>0 || row.long("revision")!=row.long("last_revision") -> {
                            val note=aiNote(callMs,recording,t)
                            port.execute("UPDATE workflow_items SET ai_note=?,last_call_ms=? WHERE id=?",note,callMs,item);noted=true
                            Applied(t.op,item,t.title,t.due,false,note)
                        }
                        else -> {
                            val next=row.long("revision")!!+1
                            // No date in an update keeps the date; news on an item marked "끝났는지 확인" opens it again.
                            if(t.op=="update") port.execute("UPDATE workflow_items SET title=?,due_text=COALESCE(NULLIF(?,''),due_text),evidence_quote=?,revision=?,status=CASE WHEN status='needs_confirmation' THEN 'open' ELSE status END,ai_note=NULL,tentative=?,last_call_ms=? WHERE id=?",
                                t.title.take(240),t.due.orEmpty(),t.quote,next,if(t.tentative) 1 else 0,callMs,item)
                            else port.execute("UPDATE workflow_items SET status='needs_confirmation',revision=?,last_call_ms=? WHERE id=?",next,callMs,item)
                            port.execute("UPDATE call_task_bindings SET last_revision=? WHERE item_id=?",next,item)
                            if(t.op=="update") {
                                val named=taskCompany(t.company)
                                if(named!=null) port.execute("INSERT OR REPLACE INTO task_companies VALUES(?,?)",item,named)
                                else companyId?.let { port.execute("INSERT OR IGNORE INTO task_companies VALUES(?,?)",item,it) }
                            }
                            Applied(t.op,item,t.title,t.due,true)
                        }
                    }
                }
            }
        }
        val json=JSONObject().put("analysis",a.toJson()).put("applied",JSONArray().apply { applied.forEach {
            put(JSONObject().put("op",it.op).put("itemId",it.itemId).put("title",it.title).put("due",it.due ?: "").put("applied",it.applied).apply { it.note?.let { n -> put("note",n) };it.reason?.let { r -> put("reason",r) } }) } })
        port.execute("UPDATE call_records SET state='ANALYZED',summary=?,analysis_json=?,error=NULL,analyzed_ms=? WHERE record_id=?",a.summary,json.toString(),now,id)
        if(applied.any { it.applied } || noted) port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
        applied
    }
    /** The one line a later call leaves on an item the user changed: "11/5 통화: 끝난 것으로 보여요". */
    private fun aiNote(callMs:Long,recording:String,t:ClaudeCallAnalysis.Task):String {
        val day=DateTimeFormatter.ofPattern("M/d").format(Instant.ofEpochMilli(callMs).atZone(KST))
        return "$day ${MessageKind.of(recording)}: "+if(t.op=="done_suggested") "끝난 것으로 보여요" else "내용이 바뀐 것 같아요, ${t.title.take(40)}"
    }

    /** Every open check item for the morning report, oldest request first. */
    fun openTodos():List<CallTodoItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.created_ms,
        (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname,i.ai_note,i.tentative
        FROM workflow_items i WHERE i.status NOT IN ('done','merged') AND i.kind='TODO' ORDER BY i.created_ms""")
        .map { CallTodoItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("cname"),it.long("created_ms") ?: 0,it.str("ai_note"),it.long("tentative")==1L) }
    /** Open items (any source) with an ISO due date on or before [today]. */
    fun dueItems(today:LocalDate):List<CallTodoItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.created_ms,
        (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname,i.ai_note,i.tentative
        FROM workflow_items i WHERE i.status NOT IN ('done','merged') AND i.due_text!='' ORDER BY i.due_text,i.created_ms""")
        .filter { val d=it.str("due_text")!!.trim();d.matches(ISO_DATE) && d<=today.toString() }
        .map { CallTodoItem(it.str("id")!!,it.str("title")!!,it.str("due_text")!!.trim(),it.str("status")!!,it.long("revision")!!,it.str("cname"),it.long("created_ms") ?: 0,it.str("ai_note"),it.long("tentative")==1L) }
    /** Every open to-do (any source), newest first, with the latest call counterpart of its recording. */
    fun openItems():List<OpenItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.edition,i.created_ms,
        (SELECT c.counterpart FROM call_records c WHERE c.recording_id=i.recording_id ORDER BY c.created_ms DESC LIMIT 1) AS counterpart,
        (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname,i.ai_note,i.tentative
        FROM workflow_items i WHERE i.status NOT IN ('done','merged') AND i.kind='TODO' ORDER BY i.created_ms DESC LIMIT 300""")
        .map { OpenItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("edition").orEmpty(),it.str("counterpart"),it.str("cname"),it.long("created_ms") ?: 0,it.str("ai_note"),it.long("tentative")==1L) }
    /** The user's "완료했어요": marks one item done and records the edit so analysis never touches it again. */
    fun completeItem(item:String,revision:Long)=port.transaction {
        val old=rows("SELECT * FROM workflow_items WHERE id=?",item).single()
        require(old.long("revision")==revision) { "ITEM_REVISION_CONFLICT" }
        port.execute("UPDATE workflow_items SET status='done',revision=revision+1,done_ms=? WHERE id=?",clock(),item)
        val before=JSONObject().apply { old.forEach { (k,v)->put(k,v ?: JSONObject.NULL) } }
        port.execute("INSERT INTO workflow_item_history VALUES(?,?,?,?)","wf_"+UUID.randomUUID().toString().replace("-",""),item,revision+1,before.toString())
        port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER);null
    }
    // ---- screens (round 3): undo of "다 됐어요", dial numbers, oldest waiting call ---------------------------------------------
    /** Takes a [completeItem] back: the item gets its earlier [status] again (it stays marked as touched by the user, so analysis leaves it alone).
     * [revision] is the revision [completeItem] left it at (its old revision plus one); anything else means it changed since. */
    fun undoDone(item:String,status:String,revision:Long)=port.transaction {
        val now=rows("SELECT revision,status FROM workflow_items WHERE id=?",item).single()
        require(now.long("revision")==revision && now.str("status")=="done") { "ITEM_REVISION_CONFLICT" }
        port.execute("UPDATE workflow_items SET status=?,revision=revision+1 WHERE id=?",status,item)
        port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER);null
    }
    /** Phone numbers (digits, maybe a leading plus) of the people the phone knows, by person id, for the dial buttons: the person's own
     * number, else the first of the numbers they answer (an office line shared by several people counts for each, review L6). Read only; never leaves the phone. */
    fun dialNumbers():Map<String,String> {
        fun dialable(k:String?)=k?.trim()?.takeIf { it.length>=6 && it.all { c -> c.isDigit() || c=='+' } }
        val out=LinkedHashMap<String,String>()
        rows("SELECT id,phone_key FROM people WHERE phone_key IS NOT NULL").forEach { r -> dialable(r.str("phone_key"))?.let { out[r.str("id")!!]=it } }
        rows("SELECT person_id,phone_key FROM people_phones ORDER BY rowid").forEach { r -> dialable(r.str("phone_key"))?.let { out.putIfAbsent(r.str("person_id")!!,it) } }
        return out
    }
    // ---- v2 screens: a check item the user wrote down, and the ones finished lately -------------------------------------------
    /** The user's own check item ("+ 직접 적기"): no call made it, so analysis never touches it. [company] is an existing or new company name.
     * Returns the item id, or null for an empty title. */
    fun addUserItem(title:String,company:String?=null):String? = port.transaction {
        val clean=title.trim().take(240);if(clean.isEmpty()) return@transaction null
        val now=clock()
        port.execute("INSERT OR IGNORE INTO folder_grants VALUES('messages',?,'content://messages',1,1,1)",GeneralLocalStore.OWNER)
        port.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('user:items',?,'messages','user:items','직접 적은 일','text/plain',0,?,?,1,1,1)",
            GeneralLocalStore.OWNER,now,now)
        val item="wf_item_"+UUID.randomUUID().toString().replace("-","")
        port.execute("INSERT INTO workflow_items(id,recording_id,case_id,kind,title,edition,due_text,status,revision,source_key,evidence_quote,created_ms,source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            item,"user:items",null,"TODO",clean,"","","open",1,null,"",now,"user")
        company?.takeIf { normName(it)!=null }?.let { port.execute("INSERT OR REPLACE INTO task_companies VALUES(?,?)",item,upsertCompany(it,now)) }
        port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
        item
    }
    /** Check items the user finished at or after [sinceMs], the latest first (the folded "끝낸 일 보기"). Undo works on them like on open ones. */
    fun doneItems(sinceMs:Long):List<OpenItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.edition,i.created_ms,
        (SELECT c.counterpart FROM call_records c WHERE c.recording_id=i.recording_id ORDER BY c.created_ms DESC LIMIT 1) AS counterpart,
        (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname,i.ai_note,i.done_ms
        FROM workflow_items i WHERE i.status='done' AND i.kind='TODO' AND COALESCE(i.done_ms,0)>=CAST(? AS INTEGER) ORDER BY i.done_ms DESC LIMIT 100""",sinceMs)
        .map { OpenItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("edition").orEmpty(),it.str("counterpart"),it.str("cname"),it.long("created_ms") ?: 0,it.str("ai_note")) }
    // ---- personal numbers (design E9) ----------------------------------------------------------------------------
    /** Whether [key] (a counterpart key) is a number found personal: its records are kept on the phone and not sent. */
    fun isPersonal(key:String?):Boolean=key!=null && rows("SELECT 1 FROM personal_numbers WHERE phone_key=?",key).isNotEmpty()
    /** Numbers found personal, the latest first: (key, when). For the settings line "개인으로 둔 번호 n개". */
    fun personalNumbers():List<Pair<String,Long>> =rows("SELECT phone_key,decided_ms FROM personal_numbers ORDER BY decided_ms DESC").map { it.str("phone_key")!! to (it.long("decided_ms") ?: 0) }
    /** Takes a number back from personal: its later records are sent again (earlier ones stay as they were). */
    fun forgetPersonal(key:String)=port.transaction { port.execute("DELETE FROM personal_numbers WHERE phone_key=?",key);port.execute("DELETE FROM personal_streaks WHERE phone_key=?",key);null }
    /** Personal verdicts in a row so far for [key] (0 after a work one). */
    fun personalStreak(key:String):Int=(rows("SELECT streak FROM personal_streaks WHERE phone_key=?",key).firstOrNull()?.long("streak") ?: 0).toInt()
    /** One personal verdict for [key]: the run grows, and at [PERSONAL_STREAK] in a row the number is remembered, unless
     * it is tied to work (round 6). A number already remembered is left as it is. */
    private fun notePersonal(key:String,recordId:String) {
        if(isPersonal(key)) return
        val n=(rows("SELECT streak FROM personal_streaks WHERE phone_key=?",key).firstOrNull()?.long("streak") ?: 0)+1
        port.execute("INSERT OR REPLACE INTO personal_streaks VALUES(?,?,?)",key,n,clock())
        if(n>=PERSONAL_STREAK && !tiedToWork(key,recordId)) port.execute("INSERT OR IGNORE INTO personal_numbers VALUES(?,?)",key,clock())
    }
    /** Whether [key] belongs to work: several people answer it (a shared office number), a company or our own staff is on
     * it, a call of it is linked to a company, or any other record of it was found work (an old one without the mark too). */
    private fun tiedToWork(key:String,recordId:String):Boolean {
        val people=peopleByNumber(key)
        if(people.size>=2 || people.any { it.str("company_id")!=null || it.long("is_staff")==1L }) return true
        if(rows("SELECT 1 FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE c.counterpart_key=? AND l.company_id IS NOT NULL LIMIT 1",key).isNotEmpty()) return true
        return rows("SELECT analysis_json FROM call_records WHERE counterpart_key=? AND record_id!=? AND state='ANALYZED'",key,recordId).any { r ->
            r.str("analysis_json")?.let { j -> runCatching { JSONObject(j).optJSONObject("analysis")?.optBoolean("business",true) }.getOrNull() }!=false }
    }
    /** A waiting record with a personal number: kept as personal here, exactly as a business=false result would leave it. */
    fun keepPersonal(id:String)=applyAnalysis(id,ClaudeCallAnalysis("개인 내용",emptyList(),null,null,emptyList(),emptyList(),business=false))

    /** When the oldest call still waiting for its summary was stored, or null when none waits (settings: "지금 상태"). */
    fun oldestWaitingMs():Long? =rows("SELECT MIN(created_ms) AS m FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT')").firstOrNull()?.long("m")

    /** AI-created items created during one KST day. */
    fun newTaskCount(day:LocalDate):Int=(rows("SELECT COUNT(*) AS n FROM call_task_bindings WHERE created_ms>=CAST(? AS INTEGER) AND created_ms<CAST(? AS INTEGER)",startOf(day),startOf(day.plusDays(1))).single().long("n") ?: 0).toInt()

    // ---- repository ledger (design: 장부의 원본) ----------------------------------------------------------------------
    private fun ensureLedgerId(personId:String):String {
        rows("SELECT ledger_id FROM people WHERE id=?",personId).single().str("ledger_id")?.let { return it }
        val lid="p_"+UUID.randomUUID().toString().replace("-","").take(16)
        port.execute("UPDATE people SET ledger_id=? WHERE id=?",lid,personId);return lid
    }
    /** The repository-ledger id of the person on this record, made on first use. People are told apart by phone number
     * (mail: address); the number stays on the phone and only the id travels. A name alone is the fallback. */
    fun ledgerIdFor(r:CallRecord):String? = port.transaction {
        val key=r.counterpartKey ?: return@transaction null
        val row=personFor(key) ?: run {
            val id="pe_"+UUID.randomUUID().toString().replace("-","")
            val name=if(isContact(key)) "" else normName(r.counterpart) ?: key
            port.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key) VALUES(?,?,NULL,NULL,NULL,0,?,?)",id,name,clock(),personKey(name))
            if(isContact(key)) addNumber(id,key)
            rows("SELECT * FROM people WHERE id=?",id).single()
        }
        if(isContact(key) && !hasNumber(row) && peopleByNumber(key).isEmpty()) addNumber(row.str("id")!!,key)
        ensureLedgerId(row.str("id")!!)
    }
    /** Corrections waiting to be uploaded, oldest first: (id, json). */
    fun pendingEdits():List<Pair<String,String>> =rows("SELECT id,json FROM ledger_edits ORDER BY created_ms").map { it.str("id")!! to it.str("json")!! }
    fun editSent(id:String)=port.execute("DELETE FROM ledger_edits WHERE id=?",id)
    /** Takes the repository ledger: site memo, companies and the people this phone knows by id. A name the user corrected
     * here stays until the ledger carries it too (the edit may not have reached the task yet). */
    fun applyLedger(o:JSONObject)=port.transaction {
        val now=clock()
        fun JSONObject.text(k:String)=if(isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
        o.text("siteMemo")?.let { port.execute("INSERT OR REPLACE INTO site_memo VALUES(1,?,?)",it.take(SITE_MEMO_MAX),now) }
        val companies=o.optJSONArray("companies") ?: JSONArray()
        for(i in 0 until companies.length()) { val c=companies.optJSONObject(i) ?: continue
            val name=normName(c.text("name")) ?: continue
            port.execute("UPDATE companies SET trade=COALESCE(?,trade),memo=COALESCE(?,memo),updated_ms=? WHERE id=?",
                normName(c.text("trade"))?.take(20),c.text("memo")?.take(COMPANY_MEMO_MAX),now,upsertCompany(name,now)) }
        val people=o.optJSONArray("people") ?: JSONArray()
        for(i in 0 until people.length()) { val p=people.optJSONObject(i) ?: continue
            val row=p.text("id")?.let { rows("SELECT * FROM people WHERE ledger_id=?",it).firstOrNull() } ?: continue
            val id=row.str("id")!!
            normName(p.text("name"))?.take(30)?.let { n ->
                if(row.long("name_locked")==1L && n!=row.str("name")) return@let
                // Once the ledger carries the user's name the lock is no longer needed: the ledger is the source again.
                port.execute("UPDATE people SET name=?,name_key=?,name_locked=0,updated_ms=? WHERE id=?",n,personKey(n),now,id) }
            val staff=p.optBoolean("isStaff",false)
            val company=if(staff) null else normName(p.text("company"))?.let { upsertCompany(it,now) }
            port.execute("UPDATE people SET company_id=CASE WHEN ?=1 THEN NULL ELSE COALESCE(?,company_id) END,role=COALESCE(?,role),is_staff=CASE WHEN ?=1 THEN 1 WHEN ? IS NOT NULL THEN 0 ELSE is_staff END WHERE id=?",
                if(staff) 1 else 0,company,normName(p.text("role"))?.take(20),if(staff) 1 else 0,company,id)
            val aliases=p.optJSONArray("aliases") ?: JSONArray()
            for(k in 0 until aliases.length()) { val a=personKey(aliases.optString(k));if(a.isNotEmpty() && a!=personKey(row.str("name"))) port.execute("INSERT OR REPLACE INTO person_aliases VALUES(?,?)",a,id) }
        }
        // Duplicates the morning tidy found: a company spelled two ways, a person known twice without a number between them.
        for(i in 0 until companies.length()) { val c=companies.optJSONObject(i) ?: continue
            val from=normName(c.text("name")) ?: continue;val into=normName(c.text("mergedInto")) ?: continue
            val a=findCompany(from) ?: continue
            val b=upsertCompany(into,now);if(a==b) continue
            mergeCompany(a,b) }
        for(i in 0 until people.length()) { val p=people.optJSONObject(i) ?: continue
            val a=p.text("id")?.let { rows("SELECT * FROM people WHERE ledger_id=?",it).firstOrNull() } ?: continue
            val b=p.text("mergedInto")?.let { rows("SELECT * FROM people WHERE ledger_id=?",it).firstOrNull() } ?: continue
            if(a.str("id")==b.str("id")) continue
            // Two people each with numbers and none in common are two people, whatever the names say (v2.4). New people
            // keep their number in people_phones only (addNumber), so people.phone_key alone missed them.
            val numbers=numbersOf(a);val bNumbers=numbersOf(b)
            if(numbers.isNotEmpty() && bNumbers.isNotEmpty() && numbers.intersect(bNumbers).isEmpty()) continue
            port.execute("UPDATE call_links SET person_id=? WHERE person_id=?",b.str("id"),a.str("id"))
            port.execute("DELETE FROM people_phones WHERE person_id=?",a.str("id"));port.execute("DELETE FROM people WHERE id=?",a.str("id"))
            numbers.forEach { addNumber(b.str("id")!!,it) }
            personKey(a.str("name")).takeIf { it.isNotEmpty() && it!=personKey(b.str("name")) }?.let { port.execute("INSERT OR REPLACE INTO person_aliases VALUES(?,?)",it,b.str("id")) } }
        o.optJSONArray("issues")?.let { list ->
            port.execute("DELETE FROM issues")
            for(i in 0 until list.length()) { val x=list.optJSONObject(i) ?: continue
                val title=x.text("title") ?: continue
                port.execute("INSERT OR REPLACE INTO issues(id,ord,title,status,companies,updated_ms,record_keys) VALUES(?,?,?,?,?,?,?)",x.text("id") ?: "i_$i",i,title.take(80),x.text("status").orEmpty().take(160),
                    (x.optJSONArray("companies") ?: JSONArray()).toString(),now,(x.optJSONArray("recordKeys") ?: JSONArray()).toString()) } };null
    }

    /** "진행 중인 일" in the ledger's order; with [company], only those that name it. */
    fun issues(company:String?=null):List<Issue> =rows("SELECT * FROM issues ORDER BY ord").map { r ->
        val names=JSONArray(r.str("companies") ?: "[]").let { a -> (0 until a.length()).map { a.optString(it) } }
        Issue(r.str("title")!!,r.str("status").orEmpty(),names) }.filter { company==null || it.companies.any { n -> companyKey(n)==companyKey(company) } }

    // ---- 새벽 정리 (design: 새벽 정리) ------------------------------------------------------------------------------
    /** What the morning tidy reads: open check items (marked when the user changed them, which it must leave alone) and
     * every work record since 10/1 as summary only, keyed by a hash so no number shows. */
    /** [all]: every record since 10/1 (the tidy's first full read); otherwise the last 14 days and those tied to an
     * issue, so it does not grow with every record (round 2). */
    fun tidyState(firstPassDone:Boolean,now:Long,all:Boolean=true):JSONObject {
        val linked=rows("SELECT record_keys FROM issues").flatMap { r -> JSONArray(r.str("record_keys") ?: "[]").let { a -> (0 until a.length()).map { a.optString(it) } } }.toSet()
        val tasks=JSONArray()
        rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.created_ms,i.recording_id,b.last_revision,
            (SELECT COUNT(*) FROM workflow_item_history h WHERE h.item_id=i.id) AS edits,
            (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname
            FROM workflow_items i LEFT JOIN call_task_bindings b ON b.item_id=i.id WHERE i.status NOT IN ('done','merged') AND i.kind='TODO' ORDER BY i.created_ms""").forEach { r ->
            val tidyMade=r.str("recording_id")=="tidy:followups" && r.long("last_revision")==null
            val locked=(r.long("edits") ?: 0)>0 || (!tidyMade && (r.long("last_revision")==null || r.long("last_revision")!=r.long("revision")))
            tasks.put(JSONObject().put("id",r.str("id")).put("title",r.str("title")).put("due",r.str("due_text").orEmpty()).put("status",r.str("status"))
                .put("company",r.str("cname") ?: JSONObject.NULL).put("createdMs",r.long("created_ms") ?: 0).put("locked",locked)) }
        val records=JSONArray()
        rows("""$withPerson WHERE c.state='ANALYZED' AND c.summary IS NOT NULL AND COALESCE(c.started_ms,c.created_ms)>=CAST(? AS INTEGER)
            ORDER BY COALESCE(c.started_ms,c.created_ms)""",FirstPass.FROM_MS).map(::map).filter { it.analysis?.business!=false }
            .filter { all || now-it.dayMs<=14*86_400_000L || CallInbox.recordKey(it.recordId) in linked }.forEach { c ->
            val lid=rows("SELECT p.ledger_id FROM call_links l JOIN people p ON p.id=l.person_id WHERE l.record_id=?",c.recordId).firstOrNull()?.str("ledger_id")
            records.put(JSONObject().put("key",CallInbox.recordKey(c.recordId)).put("atMs",c.dayMs).put("kind",c.kind)
                .put("counterpartId",lid ?: JSONObject.NULL).put("company",c.analysis?.counterpartCompany ?: JSONObject.NULL).put("summary",c.summary)
                .put("keyPoints",JSONArray(c.analysis?.keyPoints.orEmpty()))) }
        return JSONObject().put("generatedMs",now).put("firstPassDone",firstPassDone).put("tasks",tasks).put("records",records)
            .put("recentlyDone",JSONArray(recentlyDone(null)))
    }
    /** Applies the morning tidy's proposal to check items the AI still owns (never one the user changed or finished):
     * clearer wording, duplicates merged away, and "끝났는지 확인" where it looks finished. Returns how many changed. */
    fun applyTidy(o:JSONObject):Int=port.transaction {
        // AI-owned: made by an analysis and not changed since, or a follow-up the tidy made (round 2: those can be tidied too).
        fun owned(id:String)=rows("""SELECT i.revision FROM workflow_items i LEFT JOIN call_task_bindings b ON b.item_id=i.id WHERE i.id=?
            AND (i.revision=b.last_revision OR (b.item_id IS NULL AND i.recording_id='tidy:followups'))
            AND i.status NOT IN ('done','merged') AND NOT EXISTS(SELECT 1 FROM workflow_item_history h WHERE h.item_id=i.id)""",id).singleOrNull()?.long("revision")
        fun bump(id:String,rev:Long) { port.execute("UPDATE call_task_bindings SET last_revision=? WHERE item_id=?",rev+1,id) }
        var n=0
        val ops=o.optJSONArray("tasks") ?: JSONArray()
        // Follow-ups the tidy found (a promise with no sign it was kept, a question never answered): new check items.
        for(k in 0 until ops.length()) { val t=ops.optJSONObject(k)?.takeIf { it.optString("op")=="create" } ?: continue
            val title=t.optString("title").trim().take(240);if(title.isEmpty()) continue
            val tidyCompany=t.optString("company").trim().takeIf { it.isNotEmpty() }?.let { findCompany(it) }
            if(isDuplicate(title,tidyCompany,clock())) continue
            val due=t.optString("due").trim().takeIf { ISO_DATE.matches(it) }.orEmpty()
            port.execute("INSERT OR IGNORE INTO folder_grants VALUES('messages',?,'content://messages',1,1,1)",GeneralLocalStore.OWNER)
            port.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('tidy:followups',?,'messages','tidy:followups','새벽 정리','text/plain',0,?,?,1,1,1)",
                GeneralLocalStore.OWNER,clock(),clock())
            val item="wf_item_"+UUID.randomUUID().toString().replace("-","")
            port.execute("INSERT INTO workflow_items(id,recording_id,case_id,kind,title,edition,due_text,status,revision,source_key,evidence_quote,created_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",item,"tidy:followups",null,"TODO",title,"",due,"open",1,null,t.optString("basis").take(240),clock())
            tidyCompany?.let { port.execute("INSERT OR REPLACE INTO task_companies VALUES(?,?)",item,it) }
            n++
        }
        for(k in 0 until ops.length()) { val t=ops.optJSONObject(k) ?: continue
            if(t.optString("op")=="create") continue
            val id=t.optString("id");val rev=owned(id) ?: continue
            when(t.optString("op")) {
                "rewrite" -> { val title=t.optString("title").trim().take(240);if(title.isEmpty()) continue
                    // No "due" in the proposal keeps the date (an omission must not drop it from the morning report).
                    val due=if(!t.has("due") || t.isNull("due")) rows("SELECT due_text FROM workflow_items WHERE id=?",id).single().str("due_text").orEmpty()
                        else t.optString("due").trim().takeIf { d -> d.isEmpty() || ISO_DATE.matches(d) } ?: continue
                    port.execute("UPDATE workflow_items SET title=?,due_text=?,revision=? WHERE id=?",title,due,rev+1,id) }
                "merge" -> { val into=t.optString("into")
                    if(into==id || rows("SELECT 1 FROM workflow_items WHERE id=? AND status NOT IN ('done','merged')",into).isEmpty()) continue
                    // The one kept takes the date when it has none.
                    port.execute("UPDATE workflow_items SET due_text=(SELECT due_text FROM workflow_items WHERE id=?) WHERE id=? AND due_text=''",id,into)
                    port.execute("UPDATE workflow_items SET status='merged',revision=?,done_ms=? WHERE id=?",rev+1,clock(),id) }
                "done_suggested" -> port.execute("UPDATE workflow_items SET status='needs_confirmation',revision=? WHERE id=?",rev+1,id)
                else -> continue
            }
            bump(id,rev);n++
        }
        if(n>0) port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
        n
    }

    // ---- 처음 정리 -------------------------------------------------------------------------------------------------
    /** Stores the call log and ties each recorded call to its entry. A call known only by a phone-book name gets
     * the entry's number as its key, so it reaches the same person as calls named by number. */
    fun reconcileCallLog(log:List<FirstPass.LogEntry>)=port.transaction {
        // Every call since 10/1 is kept (missed, rejected, unrecorded, numbers found personal); only the user's own
        // exclusion rule keeps an entry out, and one stored before the rule was added goes now.
        for(e in log) {
            if(e.excluded) { port.execute("DELETE FROM call_record_log WHERE log_id=?",e.id);port.execute("DELETE FROM call_log WHERE log_id=?",e.id) }
            else port.execute("INSERT OR REPLACE INTO call_log VALUES(?,?,?,?,?,?)",e.id,e.numberKey,e.name,e.startMs,e.durationMs,e.type)
        }
        val open=rows("""SELECT record_id,started_ms,duration_ms,counterpart,counterpart_key FROM call_records WHERE started_ms IS NOT NULL AND recording_id NOT LIKE 'sms:%' AND recording_id NOT LIKE 'mail:%'
            AND record_id NOT IN (SELECT record_id FROM call_record_log)""").map { FirstPass.RecordForMatch(it.str("record_id")!!,it.long("started_ms")!!,it.str("counterpart"),it.str("counterpart_key"),it.long("duration_ms") ?: 0) }
        val free=rows("SELECT * FROM call_log WHERE log_id NOT IN (SELECT log_id FROM call_record_log)")
            .map { FirstPass.LogEntry(it.long("log_id")!!,it.str("number_key"),it.str("name"),it.long("start_ms")!!,it.long("duration_ms")!!,(it.long("type") ?: 0).toInt()) }+log.filter { it.excluded }
        for((recordId,e) in FirstPass.match(open,free)) {
            // A call with an excluded number: its words are wiped here and nothing about it goes anywhere.
            if(e.excluded) { forgetCall(recordId);continue }
            port.execute("INSERT OR IGNORE INTO call_record_log VALUES(?,?,?)",recordId,e.id,e.direction)
            val number=e.numberKey ?: continue
            port.execute("UPDATE call_records SET counterpart_key=? WHERE record_id=? AND (counterpart_key IS NULL OR counterpart_key GLOB '*[^0-9]*')",number,recordId)
            // The person already linked to this call learns the number, unless someone else has it.
            if(peopleByNumber(number).isEmpty())
                rows("SELECT p.id FROM call_links l JOIN people p ON p.id=l.person_id WHERE l.record_id=? AND p.phone_key IS NULL",recordId).firstOrNull()?.str("id")?.let { addNumber(it,number) }
        };null
    }
    /** Time of the oldest recording before [old] still waiting for its transcript, or null when none is left. Old records
     * earlier than this are complete and can go to the task in time order. */
    fun oldestOldJobMs(old:Long):Long? {
        if(rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='call_transcription_jobs'").isEmpty()) return null
        return rows("""SELECT MIN(COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)) AS m FROM call_transcription_jobs j
            JOIN recordings r ON r.recording_id=j.recording_id LEFT JOIN local_files l ON l.recording_id=j.recording_id
            WHERE j.state IN ('QUEUED','RUNNING') AND COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)<CAST(? AS INTEGER)""",old).single().long("m")
    }
    /** A recording found to be with the personal number: the record, its words and anything sent or linked go. The
     * transcription job stays done, so it is not transcribed again. */
    private fun forgetCall(recordId:String) {
        listOf("call_inbox_sent","call_links","call_record_log").forEach { port.execute("DELETE FROM $it WHERE record_id=?",recordId) }
        port.execute("DELETE FROM call_records WHERE record_id=?",recordId)
    }
    /** An item with the same words (spaces and punctuation ignored), or an open one of the same known company that is near it
     * (2-letter pieces, 0.6) and has the same numbers: "3층"/"4층", "11/5"/"11/12" and "2공구"/"3공구" are different jobs. Items
     * finished or merged in the last 14 days (B11) only block an identical title. [companyId] null means the company is unknown:
     * unknown against unknown matches by identical title only; "?" is a company not yet in the ledger, which only an identical
     * title can match. */
    private fun isDuplicate(title:String,companyId:String?,now:Long):Boolean {
        if(NameKey.bare(title).isEmpty()) return false
        return rows("""SELECT i.title,i.status,tc.company_id AS cid FROM workflow_items i LEFT JOIN task_companies tc ON tc.item_id=i.id
            WHERE i.kind='TODO' AND (i.status NOT IN ('done','merged') OR COALESCE(i.done_ms,i.created_ms)>=CAST(? AS INTEGER))""",now-14*86_400_000L)
            .any { val other=it.str("title").orEmpty()
                NameKey.sameWords(title,other) ||
                    (it.str("status") !in setOf("done","merged") && companyId!=null && it.str("cid")==companyId && NameKey.nearTitle(title,other)) }
    }
    /** Call-log entries of one KST day that the call list shows beside the records, newest first: every entry except
     * those tied to a record the list already shows. An entry is [LoggedCall.personal] when its number is one found
     * personal (personal_numbers) or its recording was found personal; such a line shows only who and when. */
    fun dayLog(day:LocalDate):List<LoggedCall> =rows("""SELECT l.*,c.record_id AS rec,c.analysis_json,
            (SELECT 1 FROM personal_numbers n WHERE n.phone_key=l.number_key) AS personal_number,
            (SELECT p.name FROM people_phones pp JOIN people p ON p.id=pp.person_id WHERE pp.phone_key=l.number_key ORDER BY p.updated_ms DESC LIMIT 1) AS person
            FROM call_log l LEFT JOIN call_record_log x ON x.log_id=l.log_id LEFT JOIN call_records c ON c.record_id=x.record_id
            WHERE l.start_ms>=CAST(? AS INTEGER) AND l.start_ms<CAST(? AS INTEGER) ORDER BY l.start_ms DESC,l.log_id DESC""",startOf(day),startOf(day.plusDays(1))).mapNotNull { r ->
        val linked=r.str("rec")
        val personalRecord=linked!=null && r.str("analysis_json")?.let { j -> runCatching { JSONObject(j).optJSONObject("analysis")?.optBoolean("business",true) }.getOrNull() }==false
        if(linked!=null && !personalRecord) return@mapNotNull null // the record's own line shows it
        LoggedCall(r.long("log_id")!!,r.str("number_key"),normName(r.str("person")) ?: normName(r.str("name")),r.long("start_ms")!!,r.long("duration_ms") ?: 0,(r.long("type") ?: 0).toInt(),
            personal=personalRecord || r.long("personal_number")!=null)
    }
    /** Remembers [key] as personal at once (the preview data and tests; the analysis needs [PERSONAL_STREAK] verdicts in a row). */
    internal fun rememberPersonal(key:String)=port.execute("INSERT OR IGNORE INTO personal_numbers VALUES(?,?)",key,clock())
    /** Read only, for the heartbeat's callLog check: the stored call log from [fromMs] (to [toMs]) as counts and a time. */
    data class LogTally(val total:Int,val latestMs:Long?,val recorded:Int,val unrecorded:Int)
    fun callLogTally(fromMs:Long,toMs:Long=Long.MAX_VALUE):LogTally {
        val r=rows("""SELECT COUNT(*) AS n,MAX(start_ms) AS latest,
            SUM(CASE WHEN type IN (1,2) AND log_id IN (SELECT log_id FROM call_record_log) THEN 1 ELSE 0 END) AS rec,
            SUM(CASE WHEN duration_ms>0 AND type IN (1,2) AND log_id NOT IN (SELECT log_id FROM call_record_log) THEN 1 ELSE 0 END) AS unrec
            FROM call_log WHERE start_ms>=CAST(? AS INTEGER) AND start_ms<CAST(? AS INTEGER)""",fromMs,toMs).single()
        return LogTally((r.long("n") ?: 0).toInt(),r.long("latest"),(r.long("rec") ?: 0).toInt(),(r.long("unrec") ?: 0).toInt())
    }
    /** Read only, for status/calllog.txt: each stored call log entry from [fromMs] with the record tied to it, if any. */
    data class StoredLog(val logId:Long,val numberKey:String?,val name:String?,val startMs:Long,val durationMs:Long,val type:Int,
        val recordId:String?,val state:String?,val business:Boolean?,val recordingId:String?)
    fun callLogRows(fromMs:Long):List<StoredLog> =rows("""SELECT l.log_id,l.number_key,l.name,l.start_ms,l.duration_ms,l.type,x.record_id,r.state,r.analysis_json,r.recording_id
        FROM call_log l LEFT JOIN call_record_log x ON x.log_id=l.log_id LEFT JOIN call_records r ON r.record_id=x.record_id
        WHERE l.start_ms>=CAST(? AS INTEGER) ORDER BY l.start_ms""",fromMs).map { r ->
        StoredLog(r.long("log_id")!!,r.str("number_key"),r.str("name"),r.long("start_ms") ?: 0,r.long("duration_ms") ?: 0,(r.long("type") ?: 0).toInt(),
            r.str("record_id"),r.str("state"),r.str("analysis_json")?.let { j -> runCatching { JSONObject(j).optJSONObject("analysis")?.takeIf { it.has("business") }?.optBoolean("business") }.getOrNull() },
            r.str("recording_id")) }
    /** Answered calls in the log since 10/1 with no recording (missed and rejected calls are not counted). */
    fun unrecordedCalls():Int=(rows("SELECT COUNT(*) AS n FROM call_log WHERE duration_ms>0 AND type IN (1,2) AND log_id NOT IN (SELECT log_id FROM call_record_log)").single().long("n") ?: 0).toInt()
    /** Start of the earliest stored text ("sms:") or mail ("mail:") record, or null. */
    fun earliestMessage(prefix:String):Long? =rows("SELECT MIN(started_ms) AS m FROM call_records WHERE recording_id LIKE ?","$prefix%").single().long("m")

    data class FirstPassProgress(val toTranscribe:Int,val bytesLeft:Long,val bytesPerMinute:Long?,val waiting:Int,val unrecorded:Int)
    /** What is left before the consolidation: recordings since 10/1 without a transcript yet, and old records waiting for
     * their summary. [old] is the first-pass start. */
    fun firstPassProgress(old:Long):FirstPassProgress {
        val jobs=if(rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='call_transcription_jobs'").isEmpty()) null
            else rows("""SELECT COUNT(*) AS n,COALESCE(SUM(r.byte_count),0) AS b FROM call_transcription_jobs j JOIN recordings r ON r.recording_id=j.recording_id
                WHERE j.state IN ('QUEUED','RUNNING')""").single()
        val rate=rows("""SELECT SUM(r.byte_count) AS b,SUM(c.duration_ms) AS d FROM call_records c JOIN recordings r ON r.recording_id=c.recording_id
            WHERE c.duration_ms>0 AND r.byte_count>0""").single().let { val b=it.long("b") ?: 0;val d=it.long("d") ?: 0;if(b>0 && d>=60_000) b*60_000/d else null }
        val waiting=(rows("SELECT COUNT(*) AS n FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT') AND COALESCE(started_ms,created_ms)<CAST(? AS INTEGER)",old).single().long("n") ?: 0).toInt()
        return FirstPassProgress((jobs?.long("n") ?: 0).toInt(),jobs?.long("b") ?: 0,rate,waiting,unrecordedCalls())
    }

}

/** A call-log entry the call list shows as its own line (no record to open): a missed or rejected call, an answered one
 * with no recording, or a call with a number found personal. [name]: the person's name in the ledger, else the phone
 * book's, else null (the number is shown). */
internal data class LoggedCall(val logId:Long,val numberKey:String?,val name:String?,val startMs:Long,val durationMs:Long,val type:Int,val personal:Boolean) {
    val kind:FirstPass.LogKind get()=FirstPass.kindOf(type,durationMs)
}
