package kr.callbrief.pipeline

import android.content.Context
import android.net.Uri
import android.os.Looper
import kr.callbrief.GeneralLocalStore
import kr.callbrief.LocalPrivacy
import kr.callbrief.RecordingRef
import kr.callbrief.RecordingWorkflowStore
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import java.time.Instant

/** Lane C tables on the existing protected per-owner store (GeneralLocalStore.DATABASE).
 * Machine history (analyses, case summaries, item history, reports, fetch log) is immutable.
 * The app's tasks stay in the existing workflow tables; the server only proposes. */
internal class PipelineApplyStore(val context:Context,val clock:()->Long=System::currentTimeMillis):AutoCloseable {
    /** What the C1 bound the analysis to; rechecked at apply time. */
    data class Source(val sourceVersion:Long,val audioSha:String,val consentEpoch:Long)

    val port:AndroidSqlPort
    init {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "LOCAL_IO_THREAD_REQUIRED" }
        // Installs the canonical and workflow schema without issuing any user command.
        RecordingWorkflowStore(context).close()
        port=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
        try {
            port.requireProtectedPersistence();GeneralLocalStore.overlay(port)
            port.transaction { SCHEMA.forEach { port.execute(it) };null }
        } catch(e:Exception) { port.close();throw e }
    }
    fun rows(sql:String,vararg args:Any?):List<Map<String,Any?>> = port.query(sql,*args)
    fun execute(sql:String,vararg args:Any?) = port.execute(sql,*args)
    fun <T> tx(block:()->T):T = port.transaction { block() }
    override fun close()=port.close()

    // ---- board -----------------------------------------------------------------------
    fun boardRevision():Long=n(rows("SELECT revision FROM board_state WHERE owner_key=?",GeneralLocalStore.OWNER).single(),"revision")
    /** One board revision per committed pipeline change, remembered so later analyses can tell
     * the pipeline's own bumps from user (or other) changes. */
    fun bumpBoard(analysisId:String):Long {
        execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
        val revision=boardRevision()
        if(rows("SELECT revision FROM pipeline_board_bumps WHERE revision=?",revision).isEmpty())
            execute("INSERT INTO pipeline_board_bumps VALUES(?,?,?)",revision,analysisId,clock())
        return revision
    }
    /** The board is current for [base] when nothing but pipeline applications changed it since. */
    fun boardFresh(base:Long):Boolean {
        val current=boardRevision()
        if(base==current)return true
        if(base>current)return false
        val own=rows("SELECT revision FROM pipeline_board_bumps WHERE revision>? AND revision<=?",base,current).size.toLong()
        return own==current-base
    }

    // ---- eligibility ---------------------------------------------------------------------
    /** Null when results may be applied to this recording now; otherwise a stable reason. */
    fun ineligible(recordingId:String,source:Source,policy:JSONObject):String? {
        val r=rows("SELECT r.*,v.audio_sha256,coalesce(a.archived,0) AS archived FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version LEFT JOIN local_visibility a ON a.recording_id=r.recording_id WHERE r.recording_id=? AND r.owner_key=?",
            recordingId,GeneralLocalStore.OWNER).singleOrNull() ?: return "RECORDING_UNAVAILABLE"
        if(n(r,"archived")!=0L)return "RECORDING_ARCHIVED"
        if(n(r,"source_version")!=source.sourceVersion || r["audio_sha256"]!=source.audioSha)return "SOURCE_CHANGED"
        if(policy.optBoolean("pendingRevocation"))return "POLICY_WITHDRAWN"
        if(!policy.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false) || policy.optLong(PipelineSettingsKeys.CONSENT_EPOCH,-1L)!=source.consentEpoch)return "CONSENT_WITHDRAWN"
        val file=rows("SELECT * FROM local_files WHERE recording_id=?",recordingId).singleOrNull()
        if(file!=null) {
            val blocked=runCatching {
                val ref=RecordingRef(Uri.parse(file["actual_uri"].toString()),r["display_name"].toString(),n(r,"byte_count"),r["mime_type"]?.toString(),
                    (r["original_started_at_ms"] as? Number)?.toLong()?.let(Instant::ofEpochMilli),n(r,"modified_at_ms"))
                LocalPrivacy.blocked(ref,policy)
            }.getOrDefault(true)
            if(blocked)return "RECORDING_EXCLUDED"
        }
        return null
    }
    fun archived(recordingId:String)=n(rows("SELECT coalesce(max(archived),0) AS archived FROM local_visibility WHERE recording_id=?",recordingId).single(),"archived")!=0L
    /** A deliberate user-reviewed summary for the recording's current source version. */
    fun userReviewedSummary(recordingId:String):Boolean=rows("""SELECT s.recording_id FROM workflow_reviewed_summaries s JOIN recordings r ON r.recording_id=s.recording_id
        JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        WHERE s.recording_id=? AND s.source_version=r.source_version AND s.audio_sha=v.audio_sha256""".trimIndent(),recordingId).isNotEmpty()

    // ---- items ---------------------------------------------------------------------------
    fun item(id:String)=rows("SELECT * FROM workflow_items WHERE id=? AND kind='TODO'",id).singleOrNull()
    fun binding(id:String)=rows("SELECT * FROM pipeline_item_bindings WHERE item_id=?",id).singleOrNull()
    fun userEdited(id:String)=rows("SELECT item_id FROM workflow_item_history WHERE item_id=?",id).isNotEmpty()
    /** Created by the pipeline, not edited by the user since (no user history, revision unchanged), not done. */
    fun aiOwned(item:Map<String,Any?>):Boolean {
        val id=item["id"].toString();val b=binding(id) ?: return false
        return n(b,"last_revision")==n(item,"revision") && !userEdited(id) && item["status"]!="done"
    }
    /** Manual locks: the case's business progress locks, or a manual DONE on the item's call. */
    fun manualLock(caseId:String,recordingId:String):Boolean =
        rows("SELECT task_id FROM tasks WHERE case_id=? AND owner_key=? AND manual_locks!=0",caseId,GeneralLocalStore.OWNER).isNotEmpty() ||
            rows("SELECT recording_id FROM recordings WHERE recording_id=? AND (completed_lock!=0 OR manual_status='done')",recordingId).isNotEmpty()

    // ---- analyses ------------------------------------------------------------------------
    fun analysis(id:String)=rows("SELECT * FROM pipeline_analyses WHERE analysis_id=?",id).singleOrNull()
    fun insertAnalysis(doc:Map<String,Any?>,recordingId:String,source:Source,state:String,reason:String) {
        execute("INSERT INTO pipeline_analyses(analysis_id,transcript_id,recording_id,revision,digest,payload,source_version,audio_sha,consent_epoch,base_board_revision,state,reason,case_id,summary_applied,created_ms,updated_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
            doc["analysisId"],doc["transcriptId"],recordingId,doc["revision"],doc["digest"],PipelineContract.canonical(doc),source.sourceVersion,source.audioSha,source.consentEpoch,
            doc["baseBoardRevision"],state,reason,doc["targetCaseId"],clock(),clock())
    }
    fun setAnalysisState(id:String,state:String,reason:String,caseId:String?=null,summaryApplied:Boolean=false) {
        execute("UPDATE pipeline_analyses SET state=?,reason=?,case_id=coalesce(?,case_id),summary_applied=max(summary_applied,?),updated_ms=? WHERE analysis_id=?",
            state,reason,caseId,if(summaryApplied)1 else 0,clock(),id)
    }
    fun payload(row:Map<String,Any?>)=PipelineContract.parse(row["payload"].toString().toByteArray(Charsets.UTF_8))
    fun analysisSource(row:Map<String,Any?>)=Source(n(row,"source_version"),row["audio_sha"].toString(),n(row,"consent_epoch"))

    // ---- review items --------------------------------------------------------------------
    fun addReview(analysisId:String,index:Long,kind:String,reason:String,payload:Map<String,Any?>) {
        val id="pl_review_"+PipelineContract.sha(PipelineContract.canonical(listOf("pipeline-review-v1",analysisId,index.toString(),kind))).take(40)
        if(rows("SELECT id FROM pipeline_review_items WHERE id=?",id).isNotEmpty())return
        execute("INSERT INTO pipeline_review_items(id,analysis_id,change_index,kind,reason,payload,state,created_ms) VALUES(?,?,?,?,?,?,'OPEN',?)",
            id,analysisId,index,kind,reason,PipelineContract.canonical(payload),clock())
    }
    fun review(id:String)=rows("SELECT * FROM pipeline_review_items WHERE id=?",id).singleOrNull()
    fun resolveReview(id:String,state:String) {
        require(state in listOf("ACCEPTED","DISMISSED"))
        execute("UPDATE pipeline_review_items SET state=?,resolved_ms=? WHERE id=? AND state='OPEN'",state,clock(),id)
    }
    fun openReviews()=rows("SELECT r.*,a.recording_id,a.payload AS analysis_payload FROM pipeline_review_items r JOIN pipeline_analyses a ON a.analysis_id=r.analysis_id WHERE r.state='OPEN' ORDER BY r.created_ms,r.change_index,r.id")
    fun openReviewCount()=openReviews().size
    fun caseLabels():Map<String,String> = rows("SELECT DISTINCT e.id,e.label FROM workflow_entities e JOIN tasks t ON t.case_id=e.id WHERE e.kind='CASE' AND t.owner_key=? ORDER BY e.label,e.id",GeneralLocalStore.OWNER)
        .associate { it["id"].toString() to it["label"].toString() }

    // ---- case summaries ------------------------------------------------------------------
    fun caseHead(caseId:String)=rows("SELECT s.* FROM pipeline_case_summary_heads h JOIN pipeline_case_summaries s ON s.id=h.summary_id WHERE h.case_id=?",caseId).singleOrNull()
    fun applyCaseSummary(caseId:String,analysisId:String,recordingId:String,summary:String,cumulative:String,origin:String):Boolean {
        val id="pl_summary_"+PipelineContract.sha(PipelineContract.canonical(listOf("pipeline-case-summary-v1",caseId,analysisId))).take(40)
        if(rows("SELECT id FROM pipeline_case_summaries WHERE id=?",id).isNotEmpty())return false
        val head=rows("SELECT * FROM pipeline_case_summary_heads WHERE case_id=?",caseId).singleOrNull()
        val revision=(head?.let { n(it,"revision") } ?: 0L)+1
        execute("INSERT INTO pipeline_case_summaries VALUES(?,?,?,?,?,?,?,?,?)",id,caseId,revision,analysisId,recordingId,summary,cumulative,origin,clock())
        if(head==null)execute("INSERT INTO pipeline_case_summary_heads VALUES(?,?,?)",caseId,id,revision)
        else execute("UPDATE pipeline_case_summary_heads SET summary_id=?,revision=? WHERE case_id=?",id,revision,caseId)
        return true
    }

    // ---- reports and fetch log -----------------------------------------------------------
    /** Stores an immutable report revision. True when it is the new head for its date. */
    fun storeReport(doc:Map<String,Any?>):Boolean {
        val id=doc["reportId"].toString();if(rows("SELECT report_id FROM pipeline_reports WHERE report_id=?",id).isNotEmpty())return false
        val date=doc["reportDate"].toString();val revision=doc["revision"] as Long
        val previous=rows("SELECT revision FROM pipeline_reports WHERE report_date=? ORDER BY revision DESC LIMIT 1",date).singleOrNull()?.let { n(it,"revision") } ?: 0L
        execute("INSERT INTO pipeline_reports VALUES(?,?,?,?,?,?)",id,date,revision,doc["digest"],PipelineContract.canonical(doc),clock())
        return revision>previous
    }
    fun reportDates()=rows("SELECT report_date,max(revision) AS revision FROM pipeline_reports GROUP BY report_date ORDER BY report_date DESC LIMIT 60")
    fun reportHead(date:String)=rows("SELECT * FROM pipeline_reports WHERE report_date=? ORDER BY revision DESC LIMIT 1",date).singleOrNull()
    fun latestReport()=rows("SELECT * FROM pipeline_reports ORDER BY report_date DESC,revision DESC LIMIT 1").singleOrNull()
    fun fetchedPath(path:String)=rows("SELECT path FROM pipeline_fetch_log WHERE path=?",path).isNotEmpty()
    fun logFetch(path:String,digest:String,outcome:String,reason:String) {
        if(rows("SELECT path FROM pipeline_fetch_log WHERE path=? AND digest=?",path,digest).isNotEmpty())return
        execute("INSERT INTO pipeline_fetch_log VALUES(?,?,?,?,?)",path,digest,outcome,reason,clock())
    }
    fun fetchLog(path:String)=rows("SELECT * FROM pipeline_fetch_log WHERE path=?",path)

    // ---- C1 business block -----------------------------------------------------------------
    fun business(recordingId:String):Map<String,Any?> = tx {
        val board=boardRevision()
        val linked=rows("SELECT case_id FROM workflow_links WHERE recording_id=?",recordingId).singleOrNull()?.get("case_id")?.toString()
        val all=rows("""SELECT DISTINCT e.id,e.label,coalesce((SELECT max(i.created_ms) FROM workflow_items i WHERE i.case_id=e.id),0) AS last_ms
            FROM workflow_entities e JOIN tasks t ON t.case_id=e.id WHERE e.kind='CASE' AND t.owner_key=? ORDER BY last_ms DESC,e.id""".trimIndent(),GeneralLocalStore.OWNER)
            .filter { ID.matches(it["id"].toString()) && it["label"].toString().trim().let { l->l.isNotEmpty() && l.length<=120 } }
        val ordered=(all.filter { it["id"]==linked }+all.filter { it["id"]!=linked }).take(50)
        var budget=1000
        val cases=ordered.map { c ->
            val caseId=c["id"].toString()
            val tasks=caseTasks(caseId).take(minOf(250,budget));budget-=tasks.size
            val head=caseHead(caseId)
            linkedMapOf<String,Any?>("caseId" to caseId,"label" to c["label"].toString().trim(),
                "summary" to (head?.get("cumulative_summary")?.toString()?.take(5000) ?: ""),
                "revision" to (rows("SELECT revision FROM pipeline_case_summary_heads WHERE case_id=?",caseId).singleOrNull()?.let { n(it,"revision") } ?: 0L),
                "tasks" to tasks)
        }
        linkedMapOf<String,Any?>("boardRevision" to board,"linkedCaseId" to linked?.takeIf { id->cases.any { it["caseId"]==id } },"cases" to cases)
    }
    private fun caseTasks(caseId:String):List<Map<String,Any?>> =
        rows("""SELECT i.*,b.item_id AS bound,b.last_revision,b.assignee AS bound_assignee,coalesce(a.archived,0) AS archived,
            EXISTS(SELECT 1 FROM workflow_item_history h WHERE h.item_id=i.id) AS edited
            FROM workflow_items i LEFT JOIN pipeline_item_bindings b ON b.item_id=i.id LEFT JOIN local_visibility a ON a.recording_id=i.recording_id
            WHERE i.case_id=? AND i.kind='TODO' ORDER BY i.status='done',i.created_ms DESC,i.id LIMIT 400""".trimIndent(),caseId)
            .filter { n(it,"archived")==0L && ID.matches(it["id"].toString()) && it["title"].toString().trim().let { t->t.isNotEmpty() && t.length<=240 } }
            .take(250).map { i ->
                val status=i["status"].toString().takeIf { it in PipelineContract.STATUSES } ?: "open"
                val manual=i["bound"]==null || n(i,"edited")!=0L || n(i,"last_revision")!=n(i,"revision")
                val fields=when { manual->listOf("title","assignee","due","status");status=="done"->listOf("status");else->emptyList() }
                linkedMapOf<String,Any?>("taskId" to i["id"].toString(),"title" to i["title"].toString().trim(),
                    "assignee" to (i["bound_assignee"]?.toString()?.take(100) ?: ""),
                    "due" to i["due_text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
                    "status" to status,"manualFields" to fields)
            }

    companion object {
        val ID=Regex("[A-Za-z0-9_-]{1,256}")
        fun n(r:Map<String,Any?>,key:String)=(r[key] as? Number)?.toLong() ?: 0L
        private fun immutable(table:String)=listOf(
            "CREATE TRIGGER IF NOT EXISTS ${table}_immutable_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
            "CREATE TRIGGER IF NOT EXISTS ${table}_immutable_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END")
        private val SCHEMA=listOf(
            "CREATE TABLE IF NOT EXISTS pipeline_analyses(analysis_id TEXT PRIMARY KEY,transcript_id TEXT NOT NULL,recording_id TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,payload TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,consent_epoch INTEGER NOT NULL,base_board_revision INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN('APPLIED','PARTIAL_REVIEW','REVIEW','AWAITING_CASE','REJECTED')),reason TEXT NOT NULL,case_id TEXT,summary_applied INTEGER NOT NULL DEFAULT 0,created_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL)",
            "CREATE TRIGGER IF NOT EXISTS pipeline_analyses_frozen BEFORE UPDATE OF analysis_id,transcript_id,recording_id,revision,digest,payload,source_version,audio_sha,consent_epoch,base_board_revision ON pipeline_analyses BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
            "CREATE TRIGGER IF NOT EXISTS pipeline_analyses_no_delete BEFORE DELETE ON pipeline_analyses BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
            "CREATE TABLE IF NOT EXISTS pipeline_item_bindings(item_id TEXT PRIMARY KEY,last_revision INTEGER NOT NULL,analysis_id TEXT NOT NULL,assignee TEXT NOT NULL,created_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pipeline_item_history(id TEXT PRIMARY KEY,item_id TEXT NOT NULL,revision INTEGER NOT NULL,before_json TEXT NOT NULL,analysis_id TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pipeline_case_summaries(id TEXT PRIMARY KEY,case_id TEXT NOT NULL,revision INTEGER NOT NULL,analysis_id TEXT NOT NULL,recording_id TEXT NOT NULL,summary TEXT NOT NULL,cumulative_summary TEXT NOT NULL,origin TEXT NOT NULL,created_ms INTEGER NOT NULL,UNIQUE(case_id,revision))",
            "CREATE TABLE IF NOT EXISTS pipeline_case_summary_heads(case_id TEXT PRIMARY KEY,summary_id TEXT NOT NULL,revision INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pipeline_review_items(id TEXT PRIMARY KEY,analysis_id TEXT NOT NULL,change_index INTEGER NOT NULL,kind TEXT NOT NULL,reason TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('OPEN','ACCEPTED','DISMISSED')),created_ms INTEGER NOT NULL,resolved_ms INTEGER NOT NULL DEFAULT 0,UNIQUE(analysis_id,change_index,kind))",
            "CREATE TRIGGER IF NOT EXISTS pipeline_review_items_frozen BEFORE UPDATE OF id,analysis_id,change_index,kind,reason,payload ON pipeline_review_items BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
            "CREATE TRIGGER IF NOT EXISTS pipeline_review_items_resolved BEFORE UPDATE OF state ON pipeline_review_items WHEN OLD.state!='OPEN' BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
            "CREATE TRIGGER IF NOT EXISTS pipeline_review_items_no_delete BEFORE DELETE ON pipeline_review_items BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
            "CREATE TABLE IF NOT EXISTS pipeline_reports(report_id TEXT PRIMARY KEY,report_date TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,payload TEXT NOT NULL,fetched_ms INTEGER NOT NULL,UNIQUE(report_date,revision))",
            "CREATE TABLE IF NOT EXISTS pipeline_fetch_log(path TEXT NOT NULL,digest TEXT NOT NULL,outcome TEXT NOT NULL,reason TEXT NOT NULL,fetched_ms INTEGER NOT NULL,PRIMARY KEY(path,digest))",
            "CREATE TABLE IF NOT EXISTS pipeline_board_bumps(revision INTEGER PRIMARY KEY,analysis_id TEXT NOT NULL,created_ms INTEGER NOT NULL)")+
            listOf("pipeline_item_history","pipeline_case_summaries","pipeline_reports","pipeline_fetch_log","pipeline_board_bumps").flatMap(::immutable)
    }
}
