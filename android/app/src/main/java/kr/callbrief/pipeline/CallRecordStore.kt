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
) {
    /** Name for display: analysis name/company first, then the recording's counterpart. */
    val displayName:String get()=listOfNotNull(analysis?.counterpartName,analysis?.counterpartCompany).joinToString(" · ").ifBlank { counterpart?.takeIf { it.isNotBlank() } ?: "상대 미상" }
    val dayMs:Long get()=startedMs ?: createdMs
}

/** An open workflow item shown in "오늘 할 일". */
internal data class OpenItem(val id:String,val title:String,val due:String,val status:String,val revision:Long,val edition:String,val counterpart:String?)
internal data class CallTodoItem(val id:String,val title:String,val due:String,val status:String,val revision:Long)

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
            // Same definitions as RecordingWorkflowStore so either side may create them first.
            "CREATE TABLE IF NOT EXISTS workflow_links(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER NOT NULL,person_id TEXT,company_id TEXT,case_id TEXT NOT NULL,task_id TEXT NOT NULL,phone_normalized TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_items(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),case_id TEXT,kind TEXT NOT NULL CHECK(kind IN ('TODO','DOCUMENT')),title TEXT NOT NULL,edition TEXT NOT NULL,due_text TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,source_key TEXT,evidence_quote TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_item_history(event_id TEXT PRIMARY KEY,item_id TEXT NOT NULL,revision INTEGER NOT NULL,before_json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_records(record_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,started_ms INTEGER,duration_ms INTEGER NOT NULL,counterpart TEXT,counterpart_key TEXT,transcript TEXT NOT NULL,segments_json TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('TRANSCRIBED','ANALYZED','FAILED','WAITING_CONSENT','WAITING_KEY')),summary TEXT,analysis_json TEXT,error TEXT,created_ms INTEGER NOT NULL,analyzed_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS call_task_bindings(item_id TEXT PRIMARY KEY,record_id TEXT NOT NULL REFERENCES call_records(record_id),last_revision INTEGER NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS call_inbox_sent(record_id TEXT PRIMARY KEY REFERENCES call_records(record_id),sent_ms INTEGER NOT NULL)")
        fun recordId(t:CallTranscript)="${t.recordingId}|${t.sourceVersion}|${t.audioSha256}"
        /** Digits only for a phone number, otherwise the trimmed name; null when unknown. */
        fun counterpartKey(counterpart:String?):String? {
            val value=counterpart?.trim().orEmpty();if(value.isEmpty())return null
            return if(value.matches(Regex("^[0-9+\\-(). ]+$"))) value.filter { it.isDigit() }.ifEmpty { null } else value
        }
        fun kstDay(ms:Long):LocalDate=Instant.ofEpochMilli(ms).atZone(KST).toLocalDate()
        fun kstTime(ms:Long?):String=ms?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)",java.util.Locale.KOREAN).format(Instant.ofEpochMilli(it).atZone(KST)) } ?: "시각 미상"
        private fun startOf(day:LocalDate)=day.atStartOfDay(KST).toInstant().toEpochMilli()
    }
    private val port=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    init {
        try { port.transaction { SCHEMA.forEach { port.execute(it) };null } } catch(e:Exception) { port.close();throw e }
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

    private fun map(r:Map<String,Any?>):CallRecord {
        val json=r.str("analysis_json")?.let { JSONObject(it) }
        val applied=json?.optJSONArray("applied")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Applied(it.getString("op"),it.getString("itemId"),it.getString("title"),it.optString("due").ifEmpty { null },it.getBoolean("applied")) } }.orEmpty()
        return CallRecord(r.str("record_id")!!,r.str("recording_id")!!,r.long("started_ms"),r.long("duration_ms") ?: 0,r.str("counterpart"),r.str("counterpart_key"),
            r.str("transcript").orEmpty(),r.str("state")!!,r.str("summary"),json?.optJSONObject("analysis")?.let { ClaudeCallAnalysis.parse(it) },applied,
            r.str("error"),r.long("created_ms")!!,r.long("analyzed_ms"))
    }
    /** Subscription mode: when the transcript was last dropped into the inbox. */
    fun sentMs(id:String):Long?=rows("SELECT sent_ms FROM call_inbox_sent WHERE record_id=?",id).singleOrNull()?.long("sent_ms")
    fun markSent(id:String,ms:Long)=port.execute("INSERT OR REPLACE INTO call_inbox_sent VALUES(?,?)",id,ms)
    fun record(id:String):CallRecord?=rows("SELECT * FROM call_records WHERE record_id=?",id).singleOrNull()?.let(::map)
    fun pending():List<CallRecord> =rows("SELECT * FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT','WAITING_KEY') ORDER BY created_ms").map(::map)
    /** Calls of one KST day, newest first. A call without a start time counts on the day it was stored. */
    fun day(day:LocalDate):List<CallRecord> =rows("SELECT * FROM call_records WHERE COALESCE(started_ms,created_ms)>=CAST(? AS INTEGER) AND COALESCE(started_ms,created_ms)<CAST(? AS INTEGER) ORDER BY COALESCE(started_ms,created_ms) DESC",
        startOf(day),startOf(day.plusDays(1))).map(::map)
    fun setState(id:String,state:String,error:String?=null)=port.execute("UPDATE call_records SET state=?,error=? WHERE record_id=?",state,error,id)
    /** "모두 다시 분석": waiting and failed records go back to the queue. */
    fun requeueAll():Int=port.transaction {
        val n=rows("SELECT COUNT(*) AS n FROM call_records WHERE state IN ('FAILED','WAITING_CONSENT','WAITING_KEY')").single().long("n") ?: 0
        port.execute("UPDATE call_records SET state='TRANSCRIBED',error=NULL WHERE state IN ('FAILED','WAITING_CONSENT','WAITING_KEY')");n.toInt()
    }
    fun counts():Map<String,Int> =rows("SELECT state,COUNT(*) AS n FROM call_records GROUP BY state").associate { it.str("state")!! to (it.long("n") ?: 0).toInt() }

    /** Context for the model: open AI tasks and the last 3 summaries with the same counterpart. */
    fun request(r:CallRecord):ClaudeCallRequest {
        val key=r.counterpartKey
        val tasks=if(key==null) emptyList() else rows("SELECT i.id,i.title,i.due_text,i.status FROM workflow_items i JOIN call_task_bindings b ON b.item_id=i.id JOIN call_records c ON c.record_id=b.record_id WHERE c.counterpart_key=? AND i.status!='done' ORDER BY i.created_ms DESC LIMIT 30",key)
            .map { ClaudeCallRequest.OpenTask(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty(),it.str("status")!!) }
        val summaries=if(key==null) emptyList() else rows("SELECT summary FROM call_records WHERE counterpart_key=? AND state='ANALYZED' AND record_id!=? ORDER BY COALESCE(started_ms,created_ms) DESC LIMIT 3",key,r.recordId)
            .mapNotNull { it.str("summary") }
        return ClaudeCallRequest(r.transcript,kstTime(r.startedMs),r.counterpart,r.durationMs,tasks,summaries)
    }

    /** Stores the result and applies tasks in one transaction. User-edited and finished items are never changed. */
    fun applyAnalysis(id:String,a:ClaudeCallAnalysis):List<Applied> =port.transaction {
        val record=rows("SELECT recording_id FROM call_records WHERE record_id=?",id).single()
        val recording=record.str("recording_id")!!
        // workflow_items needs the recording row; without it the tasks stay suggestions in the analysis only.
        val hasRecording=rows("SELECT 1 FROM recordings WHERE recording_id=?",recording).isNotEmpty()
        val case=rows("SELECT case_id FROM workflow_links WHERE recording_id=?",recording).singleOrNull()?.str("case_id")
        val now=clock()
        val applied=a.tasks.map { t ->
            when(t.op) {
                "create" -> {
                    if(!hasRecording) Applied("create","",t.title,t.due,false) else {
                        val item="wf_item_"+UUID.randomUUID().toString().replace("-","")
                        port.execute("INSERT INTO workflow_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",item,recording,case,"TODO",t.title.take(240),"",t.due.orEmpty(),"open",1,null,t.quote,now)
                        port.execute("INSERT INTO call_task_bindings VALUES(?,?,?,?)",item,id,1,now)
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
    fun dueItems(today:LocalDate):List<CallTodoItem> =rows("SELECT id,title,due_text,status,revision FROM workflow_items WHERE status!='done' AND due_text!='' ORDER BY due_text,created_ms")
        .filter { val d=it.str("due_text")!!.trim();d.matches(ISO_DATE) && d<=today.toString() }
        .map { CallTodoItem(it.str("id")!!,it.str("title")!!,it.str("due_text")!!.trim(),it.str("status")!!,it.long("revision")!!) }
    /** Every open to-do (any source), newest first, with the latest call counterpart of its recording. */
    fun openItems():List<OpenItem> =rows("""SELECT i.id,i.title,i.due_text,i.status,i.revision,i.edition,
        (SELECT c.counterpart FROM call_records c WHERE c.recording_id=i.recording_id ORDER BY c.created_ms DESC LIMIT 1) AS counterpart
        FROM workflow_items i WHERE i.status!='done' AND i.kind='TODO' ORDER BY i.created_ms DESC LIMIT 300""")
        .map { OpenItem(it.str("id")!!,it.str("title")!!,it.str("due_text").orEmpty().trim(),it.str("status")!!,it.long("revision")!!,it.str("edition").orEmpty(),it.str("counterpart")) }
    /** AI-created items created during one KST day. */
    fun newTaskCount(day:LocalDate):Int=(rows("SELECT COUNT(*) AS n FROM call_task_bindings WHERE created_ms>=CAST(? AS INTEGER) AND created_ms<CAST(? AS INTEGER)",startOf(day),startOf(day.plusDays(1))).single().long("n") ?: 0).toInt()
}
