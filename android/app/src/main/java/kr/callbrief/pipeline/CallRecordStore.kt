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
                              /** When the to-do was asked for (the call it came from). */ val createdMs:Long=0)
internal data class CallTodoItem(val id:String,val title:String,val due:String,val status:String,val revision:Long,val company:String?=null,val createdMs:Long=0)
/** One company of the ledger with what the list screen shows. */
internal data class CompanyRow(val id:String,val name:String,val trade:String?,val openTasks:Int,val lastCallMs:Long?)
internal data class CompanyCall(val recordId:String,val dayMs:Long,val person:String?,val summary:String?)
internal data class PersonRow(val id:String,val name:String,val role:String?,val isStaff:Boolean)
internal data class CompanyDetail(val id:String,val name:String,val trade:String?,val memo:String,val tasks:List<OpenItem>,val calls:List<CompanyCall>,val people:List<PersonRow> =emptyList())

internal class CallRecordStore(context:Context,private val clock:()->Long=System::currentTimeMillis):AutoCloseable {
    data class Applied(val op:String,val itemId:String,val title:String,val due:String?,val applied:Boolean)
    companion object {
        val KST:ZoneId=ZoneId.of("Asia/Seoul")
        const val TRANSCRIBED="TRANSCRIBED";const val ANALYZED="ANALYZED";const val FAILED="FAILED"
        const val WAITING_CONSENT="WAITING_CONSENT";const val WAITING_KEY="WAITING_KEY"
        /** Analyzed transcripts are trimmed after this many days: the whole sealed DB must stay under 16 MB. Summaries stay. */
        const val TRANSCRIPT_KEEP_DAYS=14L
        private val ISO_DATE=Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val SCHEMA=listOf(
            
            "CREATE TABLE IF NOT EXISTS workflow_links(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER NOT NULL,person_id TEXT,company_id TEXT,case_id TEXT NOT NULL,task_id TEXT NOT NULL,phone_normalized TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_items(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),case_id TEXT,kind TEXT NOT NULL CHECK(kind IN ('TODO','DOCUMENT')),title TEXT NOT NULL,edition TEXT NOT NULL,due_text TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,source_key TEXT,evidence_quote TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_item_history(event_id TEXT PRIMARY KEY,item_id TEXT NOT NULL,revision INTEGER NOT NULL,before_json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_records(record_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,started_ms INTEGER,duration_ms INTEGER NOT NULL,counterpart TEXT,counterpart_key TEXT,transcript TEXT NOT NULL,segments_json TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('TRANSCRIBED','ANALYZED','FAILED','WAITING_CONSENT','WAITING_KEY')),summary TEXT,analysis_json TEXT,error TEXT,created_ms INTEGER NOT NULL,analyzed_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS call_task_bindings(item_id TEXT PRIMARY KEY,record_id TEXT NOT NULL REFERENCES call_records(record_id),last_revision INTEGER NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_inbox_sent(record_id TEXT PRIMARY KEY REFERENCES call_records(record_id),sent_ms INTEGER NOT NULL)",
            // Context ledger: what the phone remembers for the model, which has no memory between calls.
            "CREATE TABLE IF NOT EXISTS site_memo(id INTEGER PRIMARY KEY CHECK(id=1),text TEXT NOT NULL,updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS companies(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,trade TEXT,memo TEXT NOT NULL DEFAULT '',updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS people(id TEXT PRIMARY KEY,name TEXT NOT NULL,phone_key TEXT UNIQUE,company_id TEXT,role TEXT,is_staff INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_links(record_id TEXT PRIMARY KEY,person_id TEXT,company_id TEXT)",
            "CREATE TABLE IF NOT EXISTS task_companies(item_id TEXT PRIMARY KEY,company_id TEXT NOT NULL)",
            // Names the user corrected away from (spaces removed), so a repeated mishearing still finds the right person.
            "CREATE TABLE IF NOT EXISTS person_aliases(name_key TEXT PRIMARY KEY,person_id TEXT NOT NULL)",
            // The phone's call log since 10/1 (처음 정리), kept on the phone only, and which recording each entry was.
            "CREATE TABLE IF NOT EXISTS call_log(log_id INTEGER PRIMARY KEY,number_key TEXT,name TEXT,start_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,type INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_record_log(record_id TEXT PRIMARY KEY,log_id INTEGER NOT NULL UNIQUE,direction TEXT)")
        fun recordId(t:CallTranscript)="${t.recordingId}|${t.sourceVersion}|${t.audioSha256}"
        /** Digits only for a phone number, otherwise the trimmed name; null when unknown. */
        fun counterpartKey(counterpart:String?):String? {
            val value=counterpart?.trim().orEmpty();if(value.isEmpty())return null
            return if(value.matches(Regex("^[0-9+\\-(). ]+$"))) value.filter { it.isDigit() }.ifEmpty { null } else value
        }
        /** Name as stored: trimmed, inner whitespace collapsed; null when empty. */
        fun normName(name:String?):String? = name?.trim()?.replace(Regex("\\s+")," ")?.takeIf { it.isNotEmpty() }
        private fun noSpace(name:String)=name.filterNot { it.isWhitespace() }
        const val SITE_MEMO_MAX=600;const val COMPANY_MEMO_MAX=400
        fun kstDay(ms:Long):LocalDate=Instant.ofEpochMilli(ms).atZone(KST).toLocalDate()
        fun kstTime(ms:Long?):String=ms?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)",java.util.Locale.KOREAN).format(Instant.ofEpochMilli(it).atZone(KST)) } ?: "시각 미상"
        private fun startOf(day:LocalDate)=day.atStartOfDay(KST).toInstant().toEpochMilli()
    }
    private val port=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    init {
        try { port.transaction { SCHEMA.forEach { port.execute(it) }
            // Calls stored before v1024 have no start time although the filename had one: fill it in.
            if(rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='local_files'").isNotEmpty())
                port.execute("""UPDATE call_records SET started_ms=(SELECT l.filename_time_candidate_ms FROM local_files l WHERE l.recording_id=call_records.recording_id)
                    WHERE started_ms IS NULL AND recording_id IN (SELECT recording_id FROM local_files WHERE filename_time_candidate_ms IS NOT NULL)""")
            null } } catch(e:Exception) { port.close();throw e }
    }
    override fun close()=port.close()
    private fun rows(sql:String,vararg args:Any?)=port.query(sql,*args)
    private fun Map<String,Any?>.long(k:String)=(this[k] as? Number)?.toLong()
    private fun Map<String,Any?>.str(k:String)=this[k] as? String

    /** Idempotent: the same recording version is stored once. Returns true when it was new. */
    fun insert(t:CallTranscript):Boolean=port.transaction {
        val id=recordId(t)
        if(rows("SELECT 1 FROM call_records WHERE record_id=?",id).isNotEmpty()) return@transaction false
        val segments=JSONArray().apply { t.segments.forEach { put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) } }
        val now=clock()
        port.execute("INSERT INTO call_records(record_id,recording_id,started_ms,duration_ms,counterpart,counterpart_key,transcript,segments_json,state,created_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,t.recordingId,t.originalStartedAtMs,t.durationMs,t.counterpart,counterpartKey(t.counterpart),t.text,segments.toString(),TRANSCRIBED,now)
        port.execute("UPDATE call_records SET transcript='',segments_json='[]' WHERE state='ANALYZED' AND created_ms<CAST(? AS INTEGER) AND transcript!=''",now-TRANSCRIPT_KEEP_DAYS*86_400_000L)
        true
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
        return insert(CallTranscript(id,1,sha,atMs,0,counterpart,text,listOf(CallTranscript.Segment(0,0,text)),"text","0".repeat(64),false))
    }
    /** Phone numbers (digits only) of business contacts: everyone the ledger has a number for, and every caller. */
    fun knownPhones():Set<String> =(rows("SELECT phone_key AS k FROM people WHERE phone_key IS NOT NULL")+rows("SELECT DISTINCT counterpart_key AS k FROM call_records WHERE counterpart_key IS NOT NULL"))
        .mapNotNull { it.str("k") }.filter { k -> k.length>=8 && k.all { it.isDigit() } }.toSet()

    private fun map(r:Map<String,Any?>):CallRecord {
        val json=r.str("analysis_json")?.let { JSONObject(it) }
        val applied=json?.optJSONArray("applied")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Applied(it.getString("op"),it.getString("itemId"),it.getString("title"),it.optString("due").ifEmpty { null },it.getBoolean("applied")) } }.orEmpty()
        return CallRecord(r.str("record_id")!!,r.str("recording_id")!!,r.long("started_ms"),r.long("duration_ms") ?: 0,r.str("counterpart"),r.str("counterpart_key"),
            r.str("transcript").orEmpty(),r.str("state")!!,r.str("summary"),json?.optJSONObject("analysis")?.let { ClaudeCallAnalysis.parse(it) },applied,
            r.str("error"),r.long("created_ms")!!,r.long("analyzed_ms"),r.str("linked_person"))
    }
    /** Subscription mode: when the transcript was last dropped into the inbox. */
    fun sentMs(id:String):Long?=rows("SELECT sent_ms FROM call_inbox_sent WHERE record_id=?",id).singleOrNull()?.long("sent_ms")
    fun markSent(id:String,ms:Long)=port.execute("INSERT OR REPLACE INTO call_inbox_sent VALUES(?,?)",id,ms)
    private val withPerson="SELECT c.*,p.name AS linked_person FROM call_records c LEFT JOIN call_links l ON l.record_id=c.record_id LEFT JOIN people p ON p.id=l.person_id"
    fun record(id:String):CallRecord?=rows("$withPerson WHERE c.record_id=?",id).singleOrNull()?.let(::map)
    fun pending():List<CallRecord> =rows("SELECT * FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT','WAITING_KEY') ORDER BY created_ms").map(::map)
    /** Calls of one KST day, newest first. A call without a start time counts on the day it was stored. */
    fun day(day:LocalDate):List<CallRecord> =rows("$withPerson WHERE COALESCE(c.started_ms,c.created_ms)>=CAST(? AS INTEGER) AND COALESCE(c.started_ms,c.created_ms)<CAST(? AS INTEGER) ORDER BY COALESCE(c.started_ms,c.created_ms) DESC",
        startOf(day),startOf(day.plusDays(1))).map(::map).filter { it.analysis?.business!=false } // personal calls stay hidden
    fun setState(id:String,state:String,error:String?=null)=port.execute("UPDATE call_records SET state=?,error=? WHERE record_id=?",state,error,id)
    /** "모두 다시 분석": waiting and failed records go back to the queue. */
    fun requeueAll():Int=port.transaction {
        val n=rows("SELECT COUNT(*) AS n FROM call_records WHERE state IN ('FAILED','WAITING_CONSENT','WAITING_KEY')").single().long("n") ?: 0
        port.execute("UPDATE call_records SET state='TRANSCRIBED',error=NULL WHERE state IN ('FAILED','WAITING_CONSENT','WAITING_KEY')");n.toInt()
    }
    fun counts():Map<String,Int> =rows("SELECT state,COUNT(*) AS n FROM call_records GROUP BY state").associate { it.str("state")!! to (it.long("n") ?: 0).toInt() }

    // ---- ledger ---------------------------------------------------------------
    private fun personById(id:String?)=if(id==null) null else rows("SELECT * FROM people WHERE id=?",id).firstOrNull()
    private fun companyById(id:String?)=if(id==null) null else rows("SELECT * FROM companies WHERE id=?",id).firstOrNull()
    /** The person behind a counterpart key: the latest linked call first, then phone number, then name. */
    private fun personFor(key:String?):Map<String,Any?>? {
        if(key==null) return null
        rows("SELECT l.person_id FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE c.counterpart_key=? AND l.person_id IS NOT NULL ORDER BY c.created_ms DESC LIMIT 1",key)
            .firstOrNull()?.let { personById(it.str("person_id"))?.let { p -> return p } }
        return if(key.all { it.isDigit() }) rows("SELECT * FROM people WHERE phone_key=?",key).firstOrNull()
        else personNamed(key).firstOrNull()
    }
    /** People with this name, ignoring spaces, or whose earlier wrong name the user corrected. */
    private fun personNamed(name:String)=rows("SELECT * FROM people WHERE REPLACE(name,' ','')=?",noSpace(name))
        .ifEmpty { rows("SELECT p.* FROM person_aliases a JOIN people p ON p.id=a.person_id WHERE a.name_key=?",noSpace(name)) }
    private fun upsertCompany(name:String,now:Long):String {
        val clean=normName(name)!!
        val old=rows("SELECT id FROM companies WHERE REPLACE(name,' ','')=?",noSpace(clean)).firstOrNull()
        if(old!=null) { port.execute("UPDATE companies SET updated_ms=? WHERE id=?",now,old.str("id"));return old.str("id")!! }
        val id="co_"+UUID.randomUUID().toString().replace("-","")
        port.execute("INSERT INTO companies(id,name,trade,memo,updated_ms) VALUES(?,?,NULL,'',?)",id,clean,now);return id
    }
    /** By phone number when the counterpart is a number, otherwise by name; a same-name person without a number adopts it. */
    private fun upsertPerson(cp:ClaudeCallAnalysis.Counterpart,phone:String?,companyId:String?,now:Long):String? {
        val name=normName(cp.personName) ?: return null
        var row=phone?.let { rows("SELECT * FROM people WHERE phone_key=?",it).firstOrNull() }
        if(row==null) {
            val same=personNamed(name).filter { phone==null || it.str("phone_key")==null }
            row=same.firstOrNull { it.str("company_id")==companyId } ?: same.firstOrNull { it.str("company_id")==null || companyId==null }
        }
        val role=normName(cp.role)
        if(row==null) {
            val id="pe_"+UUID.randomUUID().toString().replace("-","")
            port.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms) VALUES(?,?,?,?,?,?,?)",id,name,phone,companyId,role,if(cp.isStaff) 1 else 0,now);return id
        }
        val staff=if(cp.isStaff) 1 else if(companyId!=null) 0 else (row.long("is_staff") ?: 0)
        port.execute("UPDATE people SET phone_key=COALESCE(phone_key,?),company_id=COALESCE(?,company_id),role=COALESCE(?,role),is_staff=?,updated_ms=? WHERE id=?",
            phone,companyId,role,staff,now,row.str("id"));return row.str("id")
    }
    fun siteMemo():String? = rows("SELECT text FROM site_memo WHERE id=1").firstOrNull()?.str("text")?.takeIf { it.isNotBlank() }
    fun companyCount():Int=(rows("SELECT COUNT(*) AS n FROM companies").single().long("n") ?: 0).toInt()
    /** All companies, the one called most recently first. */
    fun companies():List<CompanyRow> =rows("""SELECT co.id,co.name,co.trade,
        (SELECT COUNT(*) FROM task_companies tc JOIN workflow_items i ON i.id=tc.item_id WHERE tc.company_id=co.id AND i.status!='done' AND i.kind='TODO') AS open_n,
        (SELECT MAX(COALESCE(c.started_ms,c.created_ms)) FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE l.company_id=co.id) AS last_ms
        FROM companies co ORDER BY COALESCE(last_ms,0) DESC,co.name""")
        .map { CompanyRow(it.str("id")!!,it.str("name")!!,it.str("trade"),(it.long("open_n") ?: 0).toInt(),it.long("last_ms")) }
    fun companyDetail(id:String):CompanyDetail? {
        val co=companyById(id) ?: return null
        val name=co.str("name")!!
        val tasks=rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.edition,i.created_ms FROM task_companies tc JOIN workflow_items i ON i.id=tc.item_id
            WHERE tc.company_id=? AND i.status!='done' AND i.kind='TODO' ORDER BY i.created_ms DESC""",id)
            .map { OpenItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("edition").orEmpty(),null,name,it.long("created_ms") ?: 0) }
        val calls=rows("""SELECT c.record_id,COALESCE(c.started_ms,c.created_ms) AS day_ms,c.summary,p.name AS person FROM call_links l JOIN call_records c ON c.record_id=l.record_id
            LEFT JOIN people p ON p.id=l.person_id WHERE l.company_id=? ORDER BY day_ms DESC LIMIT 10""",id)
            .map { CompanyCall(it.str("record_id")!!,it.long("day_ms") ?: 0,it.str("person"),it.str("summary")) }
        return CompanyDetail(id,name,co.str("trade"),co.str("memo").orEmpty(),tasks,calls,peopleOf(id))
    }
    private fun personRow(r:Map<String,Any?>)=PersonRow(r.str("id")!!,r.str("name")!!,r.str("role"),r.long("is_staff")==1L)
    /** People of one company, or with [companyId] null those without a company (staff and unknown). */
    fun peopleOf(companyId:String?):List<PersonRow> =
        (if(companyId==null) rows("SELECT * FROM people WHERE company_id IS NULL OR is_staff=1 ORDER BY updated_ms DESC")
         else rows("SELECT * FROM people WHERE company_id=? AND is_staff=0 ORDER BY updated_ms DESC",companyId)).map(::personRow)
    /** The user fixes a misheard name. The old name is kept as an alias so the same mishearing maps back to this person. */
    fun renamePerson(id:String,name:String):Boolean=port.transaction {
        val clean=normName(name)?.take(30) ?: return@transaction false
        val old=personById(id)?.str("name") ?: return@transaction false
        if(old==clean) return@transaction true
        port.execute("UPDATE people SET name=?,updated_ms=? WHERE id=?",clean,clock(),id)
        port.execute("DELETE FROM person_aliases WHERE name_key=?",noSpace(clean))
        if(noSpace(old)!=noSpace(clean)) port.execute("INSERT OR REPLACE INTO person_aliases VALUES(?,?)",noSpace(old),id)
        true
    }

    /** Everything the model should know, because it remembers nothing between calls: the site memo, who is calling
     * (person and company cards), that company's latest calls and open work, related work of the same trade,
     * all other open work with its company, and the name list for typo correction. */
    fun request(r:CallRecord,old:Boolean=false):ClaudeCallRequest {
        val key=r.counterpartKey
        val person=personFor(key)
        val company=companyById(person?.str("company_id")?.takeIf { person.long("is_staff")!=1L })
        val companyId=company?.str("id");val trade=company?.str("trade")
        val sameCounterpart=if(key==null) emptySet() else rows("SELECT b.item_id FROM call_task_bindings b JOIN call_records c ON c.record_id=b.record_id WHERE c.counterpart_key=?",key).mapNotNull { it.str("item_id") }.toSet()
        class Open(val task:ClaudeCallRequest.OpenTask,val companyId:String?,val trade:String?)
        val all=rows("""SELECT i.id,i.title,i.due_text,i.status,co.id AS cid,co.name AS cname,co.trade AS ctrade FROM workflow_items i
            LEFT JOIN task_companies tc ON tc.item_id=i.id LEFT JOIN companies co ON co.id=tc.company_id
            WHERE i.status!='done' AND i.kind='TODO' ORDER BY i.created_ms DESC LIMIT 300""")
            .map { Open(ClaudeCallRequest.OpenTask(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty(),it.str("status")!!,it.str("cname")),it.str("cid"),it.str("ctrade")) }
        val own=all.filter { (companyId!=null && it.companyId==companyId) || it.task.id in sameCounterpart }.take(30)
        val rest=all.filter { o -> own.none { it.task.id==o.task.id } }
        val tradeTasks=if(trade==null) emptyList() else rest.filter { it.trade==trade }.take(10)
        val others=rest.filter { o -> tradeTasks.none { it.task.id==o.task.id } }.take(40)
        val summaries=if(key==null) emptyList() else rows("SELECT summary FROM call_records WHERE counterpart_key=? AND state='ANALYZED' AND record_id!=? ORDER BY COALESCE(started_ms,created_ms) DESC LIMIT 3",key,r.recordId)
            .mapNotNull { it.str("summary") }
        val companyCalls=if(companyId==null) emptyList() else rows("""SELECT COALESCE(c.started_ms,c.created_ms) AS day_ms,c.summary,p.name AS person FROM call_links l JOIN call_records c ON c.record_id=l.record_id
            LEFT JOIN people p ON p.id=l.person_id WHERE l.company_id=? AND c.state='ANALYZED' AND c.record_id!=? AND c.summary IS NOT NULL ORDER BY day_ms DESC LIMIT 5""",companyId,r.recordId)
            .map { ClaudeCallRequest.PastCall(kstDay(it.long("day_ms") ?: 0).toString(),it.str("person"),it.str("summary").orEmpty()) }
        val todayCalls=if(person!=null || company!=null) emptyList() else day(kstDay(r.dayMs)).filter { it.recordId!=r.recordId && it.state==ANALYZED && it.summary!=null }.take(5)
            .map { ClaudeCallRequest.PastCall(CallUi.time(it.startedMs),it.displayName.takeIf { n -> n!="상대 미상" },it.summary!!) }
        val companyNames=rows("SELECT name FROM companies ORDER BY updated_ms DESC LIMIT 60").mapNotNull { it.str("name") }
        val personNames=rows("SELECT DISTINCT name FROM people ORDER BY updated_ms DESC LIMIT 120").mapNotNull { it.str("name") }.take(120-companyNames.size)
        return ClaudeCallRequest(r.transcript,kstTime(r.startedMs),r.counterpart,r.durationMs,own.map { it.task },summaries,
            siteMemo = siteMemo(),
            person = person?.let { ClaudeCallRequest.PersonCard(it.str("name")!!,it.str("role"),it.long("is_staff")==1L,company?.str("name")) },
            company = company?.let { ClaudeCallRequest.CompanyCard(it.str("name")!!,trade,it.str("memo").orEmpty()) },
            companyCalls = companyCalls,tradeTasks = tradeTasks.map { it.task },otherTasks = others.map { it.task },todayCalls = todayCalls,
            companyNames = companyNames,personNames = personNames,kind = r.kind,
            direction = rows("SELECT direction FROM call_record_log WHERE record_id=?",r.recordId).firstOrNull()?.str("direction"),firstPass = old)
    }

    /** Stores the result and applies tasks in one transaction. User-edited and finished items are never changed. */
    fun applyAnalysis(id:String,a:ClaudeCallAnalysis):List<Applied> =port.transaction {
        val record=rows("SELECT recording_id,counterpart_key FROM call_records WHERE record_id=?",id).single()
        val recording=record.str("recording_id")!!
        // Personal, not work: a text or mail is deleted outright; a call keeps only a bare "done" mark (so it is not
        // transcribed again) with its words wiped. Neither reaches the ledger, the lists or the check items.
        if(!a.business) {
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
        val staffCall=cp?.isStaff==true
        var companyId=cp?.companyName?.takeIf { !staffCall && normName(it)!=null }?.let { upsertCompany(it,now) }
        val personId=if(normName(cp?.personName)!=null) upsertPerson(cp!!,ckey?.takeIf { k -> k.all { it.isDigit() } },companyId,now) else personFor(ckey)?.str("id")
        if(companyId==null && !staffCall) companyId=personById(personId)?.takeIf { it.long("is_staff")!=1L }?.str("company_id")
        if(personId!=null || companyId!=null) port.execute("INSERT OR REPLACE INTO call_links VALUES(?,?,?)",id,personId,companyId)
        a.siteMemo?.trim()?.takeIf { it.isNotEmpty() }?.let { port.execute("INSERT OR REPLACE INTO site_memo VALUES(1,?,?)",it.take(SITE_MEMO_MAX),now) }
        if(companyId!=null) {
            val memo=a.companyMemo?.trim()?.takeIf { it.isNotEmpty() }?.take(COMPANY_MEMO_MAX);val trade=normName(a.companyTrade)?.take(20)
            if(memo!=null || trade!=null) port.execute("UPDATE companies SET memo=COALESCE(?,memo),trade=COALESCE(?,trade),updated_ms=? WHERE id=?",memo,trade,now,companyId)
        }
        fun taskCompany(name:String?)=name?.takeIf { normName(it)!=null }?.let { upsertCompany(it,now) }
        val applied=a.tasks.map { t ->
            when(t.op) {
                "create" -> {
                    if(!hasRecording) Applied("create","",t.title,t.due,false) else {
                        val item="wf_item_"+UUID.randomUUID().toString().replace("-","")
                        port.execute("INSERT INTO workflow_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",item,recording,case,"TODO",t.title.take(240),"",t.due.orEmpty(),"open",1,null,t.quote,now)
                        port.execute("INSERT INTO call_task_bindings VALUES(?,?,?,?)",item,id,1,now)
                        (taskCompany(t.company) ?: companyId)?.let { port.execute("INSERT OR REPLACE INTO task_companies VALUES(?,?)",item,it) }
                        Applied("create",item,t.title,t.due,true)
                    }
                }
                else -> {
                    val item=t.taskId!!
                    val owned=rows("SELECT i.revision,i.status,b.last_revision FROM workflow_items i JOIN call_task_bindings b ON b.item_id=i.id WHERE i.id=? AND NOT EXISTS(SELECT 1 FROM workflow_item_history h WHERE h.item_id=i.id)",item).singleOrNull()
                        ?.takeIf { it.long("revision")==it.long("last_revision") && it.str("status")!="done" }
                    if(owned==null) Applied(t.op,item,t.title,t.due,false) else {
                        val next=owned.long("revision")!!+1
                        if(t.op=="update") port.execute("UPDATE workflow_items SET title=?,due_text=?,evidence_quote=?,revision=? WHERE id=?",t.title.take(240),t.due ?: "",t.quote,next,item)
                        else port.execute("UPDATE workflow_items SET status='needs_confirmation',revision=? WHERE id=?",next,item)
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
        val json=JSONObject().put("analysis",a.toJson()).put("applied",JSONArray().apply { applied.forEach {
            put(JSONObject().put("op",it.op).put("itemId",it.itemId).put("title",it.title).put("due",it.due ?: "").put("applied",it.applied)) } })
        port.execute("UPDATE call_records SET state='ANALYZED',summary=?,analysis_json=?,error=NULL,analyzed_ms=? WHERE record_id=?",a.summary,json.toString(),now,id)
        if(applied.any { it.applied }) port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
        applied
    }

    /** Open items (any source) with an ISO due date on or before [today]. */
    fun dueItems(today:LocalDate):List<CallTodoItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.created_ms,
        (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname
        FROM workflow_items i WHERE i.status!='done' AND i.due_text!='' ORDER BY i.due_text,i.created_ms""")
        .filter { val d=it.str("due_text")!!.trim();d.matches(ISO_DATE) && d<=today.toString() }
        .map { CallTodoItem(it.str("id")!!,it.str("title")!!,it.str("due_text")!!.trim(),it.str("status")!!,it.long("revision")!!,it.str("cname"),it.long("created_ms") ?: 0) }
    /** Every open to-do (any source), newest first, with the latest call counterpart of its recording. */
    fun openItems():List<OpenItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.edition,i.created_ms,
        (SELECT c.counterpart FROM call_records c WHERE c.recording_id=i.recording_id ORDER BY c.created_ms DESC LIMIT 1) AS counterpart,
        (SELECT co.name FROM task_companies tc JOIN companies co ON co.id=tc.company_id WHERE tc.item_id=i.id) AS cname
        FROM workflow_items i WHERE i.status!='done' AND i.kind='TODO' ORDER BY i.created_ms DESC LIMIT 300""")
        .map { OpenItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("edition").orEmpty(),it.str("counterpart"),it.str("cname"),it.long("created_ms") ?: 0) }
    /** The user's "완료했어요": marks one item done and records the edit so analysis never touches it again. */
    fun completeItem(item:String,revision:Long)=port.transaction {
        val old=rows("SELECT * FROM workflow_items WHERE id=?",item).single()
        require(old.long("revision")==revision) { "ITEM_REVISION_CONFLICT" }
        port.execute("UPDATE workflow_items SET status='done',revision=revision+1 WHERE id=?",item)
        val before=JSONObject().apply { old.forEach { (k,v)->put(k,v ?: JSONObject.NULL) } }
        port.execute("INSERT INTO workflow_item_history VALUES(?,?,?,?)","wf_"+UUID.randomUUID().toString().replace("-",""),item,revision+1,before.toString())
        port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER);null
    }
    /** AI-created items created during one KST day. */
    fun newTaskCount(day:LocalDate):Int=(rows("SELECT COUNT(*) AS n FROM call_task_bindings WHERE created_ms>=CAST(? AS INTEGER) AND created_ms<CAST(? AS INTEGER)",startOf(day),startOf(day.plusDays(1))).single().long("n") ?: 0).toInt()

    // ---- 처음 정리 -------------------------------------------------------------------------------------------------
    /** Stores the call log and ties each recorded call to its entry. A call known only by a phone-book name gets
     * the entry's number as its key, so it reaches the same person as calls named by number. */
    fun reconcileCallLog(log:List<FirstPass.LogEntry>)=port.transaction {
        log.forEach { port.execute("INSERT OR REPLACE INTO call_log VALUES(?,?,?,?,?,?)",it.id,it.numberKey,it.name,it.startMs,it.durationMs,it.type) }
        val open=rows("""SELECT record_id,started_ms,counterpart,counterpart_key FROM call_records WHERE started_ms IS NOT NULL AND recording_id NOT LIKE 'sms:%' AND recording_id NOT LIKE 'mail:%'
            AND record_id NOT IN (SELECT record_id FROM call_record_log)""").map { FirstPass.RecordForMatch(it.str("record_id")!!,it.long("started_ms")!!,it.str("counterpart"),it.str("counterpart_key")) }
        val free=rows("SELECT * FROM call_log WHERE log_id NOT IN (SELECT log_id FROM call_record_log)")
            .map { FirstPass.LogEntry(it.long("log_id")!!,it.str("number_key"),it.str("name"),it.long("start_ms")!!,it.long("duration_ms")!!,(it.long("type") ?: 0).toInt()) }
        for((recordId,e) in FirstPass.match(open,free)) {
            port.execute("INSERT OR IGNORE INTO call_record_log VALUES(?,?,?)",recordId,e.id,e.direction)
            val number=e.numberKey ?: continue
            port.execute("UPDATE call_records SET counterpart_key=? WHERE record_id=? AND (counterpart_key IS NULL OR counterpart_key GLOB '*[^0-9]*')",number,recordId)
            // The person already linked to this call learns the number, unless someone else has it.
            if(rows("SELECT 1 FROM people WHERE phone_key=?",number).isEmpty())
                port.execute("UPDATE people SET phone_key=? WHERE phone_key IS NULL AND id=(SELECT person_id FROM call_links WHERE record_id=?)",number,recordId)
        };null
    }
    /** Answered calls in the log since 10/1 with no recording. */
    fun unrecordedCalls():Int=(rows("SELECT COUNT(*) AS n FROM call_log WHERE duration_ms>0 AND log_id NOT IN (SELECT log_id FROM call_record_log)").single().long("n") ?: 0).toInt()
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
        val waiting=(rows("SELECT COUNT(*) AS n FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT','WAITING_KEY') AND COALESCE(started_ms,created_ms)<CAST(? AS INTEGER)",old).single().long("n") ?: 0).toInt()
        return FirstPassProgress((jobs?.long("n") ?: 0).toInt(),jobs?.long("b") ?: 0,rate,waiting,unrecordedCalls())
    }

    /** Everything the consolidation reads: every work record since 10/1 in date order, the ledger's names and how often
     * each known person called (from the call log). */
    fun digestInput():FirstPass.DigestInput {
        val lines=rows("""$withPerson WHERE c.state='ANALYZED' AND c.summary IS NOT NULL AND COALESCE(c.started_ms,c.created_ms)>=CAST(? AS INTEGER)
            ORDER BY COALESCE(c.started_ms,c.created_ms)""",FirstPass.FROM_MS).map(::map).filter { it.analysis?.business!=false }.take(2000).map { c ->
            val dir=rows("SELECT direction FROM call_record_log WHERE record_id=?",c.recordId).firstOrNull()?.str("direction")
            val head=listOfNotNull(kstTime(c.dayMs).substring(0,16),c.kind,dir,c.displayName).joinToString(" ")
            "- $head: "+c.summary!!.replace(Regex("\\s+")," ").take(240)
        }
        val companies=rows("SELECT name,trade FROM companies ORDER BY name").map { it.str("name")!!+(it.str("trade")?.let { t -> "($t)" } ?: "") }
        val people=rows("SELECT p.name,p.role,p.is_staff,co.name AS cname FROM people p LEFT JOIN companies co ON co.id=p.company_id ORDER BY p.name").map {
            it.str("name")!!+"("+listOfNotNull(if(it.long("is_staff")==1L) "우리 직원" else it.str("cname"),it.str("role")).joinToString(", ").ifEmpty { "소속 미상" }+")" }
        val counts=rows("""SELECT p.name,COUNT(l.log_id) AS n,COUNT(x.log_id) AS rec FROM people p JOIN call_log l ON l.number_key=p.phone_key
            LEFT JOIN call_record_log x ON x.log_id=l.log_id WHERE l.duration_ms>0 GROUP BY p.id ORDER BY n DESC LIMIT 80""")
            .map { "${it.str("name")} ${it.long("n")}회(녹음 ${it.long("rec")}회)" }
        return FirstPass.DigestInput(lines,companies,people,counts)
    }

    /** Rewrites the ledger from the consolidation. Only names already in the ledger or in the summaries are taken;
     * nobody is created or renamed, and a person the user renamed is never merged away. */
    fun applyDigest(d:FirstPass.Digest)=port.transaction {
        val now=clock()
        val said=noSpace(rows("SELECT summary FROM call_records WHERE summary IS NOT NULL").joinToString(" ") { it.str("summary")!! })
        fun companyId(name:String?):String? { val n=normName(name) ?: return null
            return rows("SELECT id FROM companies WHERE REPLACE(name,' ','')=?",noSpace(n)).firstOrNull()?.str("id") ?: if(said.contains(noSpace(n))) upsertCompany(n,now) else null }
        d.siteMemo?.trim()?.takeIf { it.isNotEmpty() }?.let { port.execute("INSERT OR REPLACE INTO site_memo VALUES(1,?,?)",it.take(SITE_MEMO_MAX),now) }
        for(c in d.companies) { val id=companyId(c.name) ?: continue
            port.execute("UPDATE companies SET trade=COALESCE(?,trade),memo=COALESCE(?,memo),updated_ms=? WHERE id=?",normName(c.trade)?.take(20),c.memo?.trim()?.takeIf { it.isNotEmpty() }?.take(COMPANY_MEMO_MAX),now,id) }
        for(p in d.people) { val row=personNamed(p.name).singleOrNull() ?: continue
            val co=if(p.isStaff) null else companyId(p.company)
            port.execute("UPDATE people SET company_id=CASE WHEN ?=1 THEN NULL ELSE COALESCE(?,company_id) END,role=COALESCE(?,role),is_staff=?,updated_ms=? WHERE id=?",
                if(p.isStaff) 1 else 0,co,normName(p.role)?.take(20),if(p.isStaff) 1 else if(co!=null) 0 else (row.long("is_staff") ?: 0),now,row.str("id")) }
        for((from,to) in d.sameAs) {
            val a=personNamed(from).singleOrNull() ?: continue;val b=personNamed(to).singleOrNull() ?: continue
            val aId=a.str("id")!!;val bId=b.str("id")!!
            if(aId==bId || rows("SELECT 1 FROM person_aliases WHERE person_id=?",aId).isNotEmpty()) continue
            val aPhone=a.str("phone_key");val bPhone=b.str("phone_key")
            if(aPhone!=null && bPhone!=null && aPhone!=bPhone) continue
            port.execute("UPDATE call_links SET person_id=? WHERE person_id=?",bId,aId)
            port.execute("DELETE FROM people WHERE id=?",aId)
            if(aPhone!=null) port.execute("UPDATE people SET phone_key=COALESCE(phone_key,?) WHERE id=?",aPhone,bId)
            port.execute("INSERT OR REPLACE INTO person_aliases VALUES(?,?)",noSpace(a.str("name")!!),bId)
        };null
    }
}
