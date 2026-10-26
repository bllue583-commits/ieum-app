package kr.callbrief

import android.content.Context
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The existing per-owner, Keystore-sealed primary store, not a second unencrypted queue.
 * No user-command receipt is fabricated. Machine history and manual history remain distinct. */
internal class AndroidAutomationStore(private val context:Context,private val owner:String,private val epoch:Long,
    private val scopeCurrent:()->Boolean,private val policy:()->String,
    private val sourcePermission:(JSONObject)->Boolean,
    private val reportZone:()->String?,private val now:()->Long=System::currentTimeMillis):AutoCloseable {
    private val db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private val claimToken=UUID.randomUUID().toString()
    init {
        db.requireProtectedPersistence();GeneralLocalStore.overlay(db)
        // Install the app's existing workflow schema without issuing any user command.
        RecordingWorkflowStore(context).close()
        db.transaction {
            schema.forEach {db.execute(it)}
            for(table in listOf("automation_results","automation_applications","automation_events","automation_cumulative_history")) {
                for(action in listOf("UPDATE","DELETE"))db.execute("CREATE TRIGGER IF NOT EXISTS ${table}_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END")
                db.execute("CREATE TRIGGER IF NOT EXISTS ${table}_no_replace BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END")
            };null
        }
    }
    private fun n(r:Map<String,Any?>,key:String)=(r[key] as? Number)?.toLong() ?: 0L
    private fun json(r:Map<String,Any?>)=JSONObject().apply {r.forEach{(k,v)->put(k,v ?: JSONObject.NULL)}}
    private fun sources(id:String?=null)=db.query("""SELECT r.*,v.audio_sha256,a.archived,a.revision AS visibility_revision,
        l.actual_uri,l.actual_document_id,l.root_uri,g.active AS grant_active,g.selected_by_user,
        t.task_revision,t.status AS task_status,t.manual_locks AS task_manual_locks,t.case_id,b.revision AS board_revision,coalesce(auth.epoch,0) AS analysis_authority_epoch,
        EXISTS(SELECT 1 FROM vault2_refs q WHERE q.recording_id=r.recording_id AND q.source_version=r.source_version AND q.plaintext_sha256=v.audio_sha256) AS encrypted_source
        FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        JOIN local_files l ON l.recording_id=r.recording_id JOIN local_visibility a ON a.recording_id=r.recording_id
        JOIN folder_grants g ON g.grant_id=r.grant_id JOIN board_state b ON b.owner_key=r.owner_key
        LEFT JOIN tasks t ON t.task_id=r.task_id LEFT JOIN workflow_analysis_authority auth ON auth.recording_id=r.recording_id WHERE r.owner_key=? ${if(id==null)"" else "AND r.recording_id=?"}""".trimIndent(),
        *if(id==null)arrayOf(GeneralLocalStore.OWNER) else arrayOf(GeneralLocalStore.OWNER,id))
    private fun current(id:String)=sources(id).singleOrNull()?.let(::json)
    fun admitCurrentSources()=db.transaction {
        check(scopeCurrent()) { "AUTOMATION_SCOPE_CHANGED" }
        for(row in sources()) {
            val s=json(row)
            if(n(row,"encrypted_source")!=1L || n(row,"archived")==1L || n(row,"grant_active")!=1L)continue
            val key=hash(listOf("android-automation-v1",owner,epoch,row["recording_id"],row["source_version"],row["audio_sha256"]))
            if(db.query("SELECT id FROM automation_jobs WHERE id=?",key).isNotEmpty())continue
            val permitted=sourcePermission(s)
            db.execute("INSERT INTO automation_jobs(id,owner,epoch,recording_id,source_version,audio_sha,snapshot_json,policy_hash,state,reason,updated_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                key,owner,epoch,row["recording_id"],row["source_version"],row["audio_sha256"],s.toString(),policy(),if(permitted)"QUEUED" else "HELD_SOURCE",if(permitted)"AUTHORIZED_LOCAL_SOURCE_QUEUED" else "SOURCE_PERMISSION_REQUIRED",now())
            event(key,"ADMITTED",if(permitted)"AUTHORIZED_LOCAL_SOURCE_QUEUED" else "SOURCE_PERMISSION_REQUIRED")
        };null
    }
    private fun checkedJob(r:Map<String,Any?>):JSONObject {
        val numeric=listOf("epoch","source_version","result_revision","claim_until","next_poll_ms","updated_ms")
        check(numeric.all{r[it] is Long && (r[it] as Long) in 0..9007199254740991L} && n(r,"source_version")>0) {"AUTOMATION_STORE_REVIEW_REQUIRED"}
        check(r["id"]==hash(listOf("android-automation-v1",r["owner"],r["epoch"],r["recording_id"],r["source_version"],r["audio_sha"]))) {"AUTOMATION_STORE_REVIEW_REQUIRED"}
        check(r["audio_sha"].toString().matches(Regex("[0-9a-f]{64}")) && (n(r,"result_revision")==0L && r["result_digest"]=="" || n(r,"result_revision")>0L && r["result_digest"].toString().matches(Regex("[0-9a-f]{64}")))) {"AUTOMATION_STORE_REVIEW_REQUIRED"}
        check(r["state"] in setOf("QUEUED","HELD_SOURCE","WAITING_PROVIDER","WAITING_APPROVAL","FAILED","DISPATCHING","UNKNOWN","WAITING_RESULT","WAITING_CONTEXT","UNKNOWN_NATIVE","WAITING_NATIVE","APPLIED_SAFE_DERIVED","REVIEW_REQUIRED","CANCELLED","WAITING_LOCAL_RESUME")) {"AUTOMATION_STORE_REVIEW_REQUIRED"}
        val s=JSONObject(r["snapshot_json"].toString())
        check(s.getString("recording_id")==r["recording_id"] && s.getLong("source_version")==n(r,"source_version") && s.getString("audio_sha256")==r["audio_sha"]) {"AUTOMATION_STORE_REVIEW_REQUIRED"}
        return json(r)
    }
    fun jobs()=db.query("SELECT * FROM automation_jobs WHERE owner=? AND epoch=? ORDER BY updated_ms,id",owner,epoch).map(::checkedJob)
    fun job(id:String)=checkedJob(db.query("SELECT * FROM automation_jobs WHERE id=? AND owner=? AND epoch=?",id,owner,epoch).single())
    fun work(limit:Int,existingResultsOnly:Boolean=false)=jobs().filter {it.getString("state") !in setOf("CANCELLED","FAILED","HELD_SOURCE","UNKNOWN_NATIVE") && it.getLong("next_poll_ms")<=now() && (!existingResultsOnly || AutomationPendingResults.known(it))}.take(limit)
    fun pendingResults()=jobs().filter{AutomationPendingResults.active(it,now())}
    fun resultWindowOpen(id:String)=AutomationPendingResults.deadline(job(id))?.let{it>now()}==true
    fun sourceSnapshot(id:String)=JSONObject(job(id).getString("snapshot_json"))
    fun refreshBeforePrepare(id:String)=db.transaction {
        requireClaim(id);val j=job(id)
        if(j.getString("request_id").isBlank())db.execute("UPDATE automation_jobs SET snapshot_json=? WHERE id=?",checkNotNull(current(j.getString("recording_id"))).toString(),id)
        null
    }
    fun contextReady(id:String)=sourceSnapshot(id).let{!it.isNull("case_id") && !it.isNull("task_id") && !it.isNull("original_started_at_ms")}
    fun frozenBusinessContext(id:String):JSONObject=db.transaction {
        requireClaim(id);val j=job(id);val old=j.getString("business_json");if(old.isNotBlank())return@transaction JSONObject(old)
        val s=checkNotNull(current(j.getString("recording_id")));val case=s.getString("case_id")
        val head=db.query("SELECT h.cumulative_text,j.id FROM automation_case_heads c JOIN automation_cumulative_history h ON h.id=c.result_id JOIN automation_jobs j ON j.id=h.job_id WHERE c.case_id=? AND j.owner=? AND j.epoch=? AND j.policy_hash=?",case,owner,epoch,policy()).singleOrNull()
            ?.takeIf{allowed(it["id"].toString())}
        val tasks=JSONArray()
        for(i in db.query("SELECT * FROM workflow_items WHERE case_id=? ORDER BY created_ms,id LIMIT 250",case)) {
            val itemSource=current(i["recording_id"].toString()) ?: continue
            if(itemSource.optInt("archived")!=0 || itemSource.optInt("grant_active")!=1 || !sourcePermission(itemSource))continue
            val owned=db.query("SELECT * FROM automation_item_bindings WHERE item_id=?",i["id"]).singleOrNull()
            val manual=owned==null || n(i,"revision")!=n(owned,"last_revision") || i["status"]=="done" || db.query("SELECT item_id FROM workflow_item_history WHERE item_id=?",i["id"]).isNotEmpty()
            tasks.put(JSONObject().put("id",i["id"]).put("title",i["title"]).put("assignee","미확인")
                .put("due",i["due_text"]?.toString()?.takeIf{it.isNotBlank()} ?: JSONObject.NULL).put("status",i["status"])
                .put("manualFields",JSONArray(if(manual)listOf("title","assignee","due","status") else emptyList<String>())))
        }
        val context=JSONObject().put("caseId",case).put("revision",s.getLong("board_revision"))
            .put("proposedSummary",head?.get("cumulative_text")?.toString().orEmpty()).put("tasks",tasks)
        db.execute("UPDATE automation_jobs SET business_json=? WHERE id=?",context.toString(),id);context
    }
    /** The processing owner has already admitted these bytes through the protected native
     * receipt/head and source gates. Recheck the exact current head before mapping the proposal. */
    fun readNativeObservation(id:String,o:GoogleRelayStore.ResultObservation):AutomationObservation.Result {
        requireClaim(id);val j=job(id);val raw=db.transaction {
            check(allowed(id)) {"AUTOMATION_SOURCE_HELD"}
            val row=db.query("SELECT p.payload FROM drive_profiles_receipts p JOIN drive_profiles_heads h ON h.owner_id=p.owner_id AND h.job_id=p.job_id AND h.kind=p.kind AND h.schema_version=p.schema_version AND h.result_revision=p.result_revision AND h.digest=p.digest WHERE p.owner_id=? AND p.job_id=? AND p.kind=? AND p.schema_version=? AND p.result_revision=? AND p.digest=? AND p.hidden=0",owner,o.jobId,o.kind,o.schemaVersion,o.revision,o.digest).single()
            (row["payload"] as ByteArray).clone()
        }
        val business=frozenBusinessContext(id)
        val mapped=AutomationApiV2Consumer.read(raw,j.getString("handoff"),hash(StrictJson.`object`(StrictJson.parse(business.toString().toByteArray()))))
        RecordingWorkflowStore(context).use{it.commitResultObservation(j.getString("request_id"),o)}
        return VerifiedAutomaticResults.map(context,j,raw,business,mapped){allowed(id)}
    }
    fun claim(id:String):Boolean=db.transaction {
        check(scopeCurrent()) { "AUTOMATION_SCOPE_CHANGED" }
        val old=job(id);if(old.getLong("claim_until")>now() && old.getString("claim_token")!=claimToken)return@transaction false
        db.execute("UPDATE automation_jobs SET claim_token=?,claim_until=? WHERE id=?",claimToken,now()+120000,id);true
    }
    fun release(id:String) {db.execute("UPDATE automation_jobs SET claim_token='',claim_until=0 WHERE id=? AND claim_token=?",id,claimToken)}
    fun ownsClaim(id:String)=job(id).let{it.getString("claim_token")==claimToken && it.getLong("claim_until")>now()}
    private fun requireClaim(id:String) {check(ownsClaim(id)) {"AUTOMATION_CLAIM_EXPIRED"}}
    private fun event(id:String,kind:String,reason:String) {db.execute("INSERT INTO automation_events VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),id,kind,reason,now())}
    fun hold(id:String,state:String,reason:String)=db.transaction {
        val code=reason.takeIf {it.matches(Regex("[A-Z0-9_]{1,120}"))} ?: "PROVIDER_REASON_INVALID"
        val old=job(id)
        if(old.getString("state")!=state || old.getString("reason")!=code)event(id,state,code)
        db.execute("UPDATE automation_jobs SET state=?,reason=?,updated_ms=?,next_poll_ms=CASE WHEN ?='WAITING_RESULT' THEN ? ELSE next_poll_ms END WHERE id=?",state,code,now(),state,now()+30000,id);null
    }
    /** No content read/dispatch/result admission after exclusion, revocation, archive or epoch change. */
    fun allowed(id:String):Boolean=db.transaction {
        val j=job(id);val s=current(j.getString("recording_id"))
        val okay=scopeCurrent() && s!=null && s.getLong("source_version")==j.getLong("source_version") && s.getString("audio_sha256")==j.getString("audio_sha") &&
            s.optInt("archived")==0 && s.optInt("grant_active")==1 && s.optInt("selected_by_user")==1 && s.optInt("encrypted_source")==1 &&
            j.getString("policy_hash")==policy() && s.optLong("analysis_authority_epoch")==sourceSnapshot(id).optLong("analysis_authority_epoch") && sourcePermission(s)
        if(!okay) {hold(id,"HELD_SOURCE","SOURCE_REVOKED_EXCLUDED_OR_CHANGED");db.execute("UPDATE automation_report_queue SET state='HELD_SOURCE' WHERE job_id=?",id)}
        okay
    }
    fun bindRequest(id:String,request:String)=db.transaction {
        requireClaim(id)
        val j=job(id);check(j.getString("request_id").isBlank() || j.getString("request_id")==request)
        db.execute("UPDATE automation_jobs SET request_id=? WHERE id=?",request,id);event(id,"REQUEST_BOUND","EXISTING_NATIVE_REQUEST_BOUND");null
    }
    fun freezeHandoff(id:String,handoff:String)=db.transaction {
        requireClaim(id)
        val j=job(id);val h=JSONObject(handoff);val raw=h.getString("frozenRequestJson");val r=JSONObject(raw)
        val old=j.getString("handoff");check(old.isBlank() || old==handoff) { "AUTOMATION_FROZEN_HANDOFF_CHANGED" }
        check(r.getString("sourceId")==j.getString("recording_id") && r.getLong("sourceVersion")==j.getLong("source_version") && r.getString("audioSha")==j.getString("audio_sha"))
        check(r.getJSONObject("ownerBinding").getString("ownerId")==owner)
        check(h.getString("localRequestId")==j.getString("request_id"))
        db.execute("UPDATE automation_jobs SET handoff=? WHERE id=?",handoff,id);null
    }
    fun dispatchReceipt(id:String,d:AutomationDispatch)=db.transaction {
        requireClaim(id)
        val j=job(id)
        if(d.state=="ACCEPTED") {
            check(j.getString("receipt").isBlank() || j.getString("receipt")==d.receipt) { "AUTOMATION_RECEIPT_CHANGED" }
            db.execute("UPDATE automation_jobs SET receipt=? WHERE id=?",d.receipt,id)
        }
        hold(id,when(d.state){"ACCEPTED"->"WAITING_RESULT";"WAITING"->"WAITING_PROVIDER";"UNKNOWN"->"UNKNOWN";else->"FAILED"},d.reason);null
    }
    fun accept(id:String,result:AutomationAnalysis,assurance:AutomationAssurance)=db.transaction {
        requireClaim(id)
        if(!allowed(id))return@transaction
        val j=job(id);val handoff=JSONObject(j.getString("handoff"));check(result.frozenRequest==handoff.getString("frozenRequestJson")) { "AUTOMATION_RESULT_REQUEST_CHANGED" }
        val oldRevision=j.getLong("result_revision")
        if(result.revision<oldRevision) {hold(id,j.getString("state"),"STALE_RESULT_IGNORED");return@transaction}
        if(result.revision==oldRevision) {
            check(result.digest==j.getString("result_digest")) { "AUTOMATION_RESULT_REVISION_CONFLICT" }
            // Same revision must reproduce the same bytes and the same independently established assurance.
            val old=db.query("SELECT payload,assurance FROM automation_results WHERE job_id=? AND revision=?",id,result.revision).single()
            check(old["payload"]==result.raw && old["assurance"]==assurance.json().toString()) { "AUTOMATION_RESULT_RECEIPT_CHANGED" }
            val previous=db.query("SELECT decision,reason FROM automation_applications WHERE job_id=? AND id IN (SELECT id FROM automation_results WHERE job_id=? AND revision=?)",id,id,result.revision).single()
            val current=checkNotNull(current(j.getString("recording_id")));val base=sourceSnapshot(id)
            val changed=listOf("user_revision","lock_revision","head_revision","task_revision","visibility_revision").any{current.optLong(it)!=base.optLong(it)}
            db.execute("UPDATE automation_jobs SET state=?,reason=?,updated_ms=?,next_poll_ms=? WHERE id=?",if(changed)"REVIEW_REQUIRED" else previous["decision"],if(changed)"CURRENT_MANUAL_STATE_PROTECTED" else previous["reason"],now(),now()+900000,id);return@transaction
        }
        val resultId=hash(listOf(id,result.revision,result.digest))
        db.execute("INSERT INTO automation_results VALUES(?,?,?,?,?,?,?,?)",resultId,id,result.revision,result.digest,result.raw,assurance.json().toString(),result.transcript,now())
        val s=checkNotNull(current(j.getString("recording_id")));val base=sourceSnapshot(id)
        val reasons=mutableListOf<String>()
        if(!assurance.verifiedSource || !assurance.executionVerified || !assurance.semanticReviewVerified || !assurance.privacyReviewed || !assurance.automaticUseAllowed)reasons+="EXECUTION_SEMANTIC_PRIVACY_ASSURANCE_REQUIRED"
        if(s.isNull("task_id") || s.isNull("case_id") || s.isNull("person_id") || s.isNull("original_started_at_ms"))reasons+="IDENTITY_TIME_CONTEXT_REQUIRED"
        if(listOf("user_revision","lock_revision","head_revision","task_revision","visibility_revision").any { s.optLong(it)!=base.optLong(it) })reasons+="MANUAL_OR_CURRENT_REVISION_CONFLICT"
        if(s.optInt("edited_lock")!=0 || s.optInt("completed_lock")!=0 || s.optInt("task_manual_locks")!=0 || s.optString("manual_status")=="done" || s.optString("task_status")=="done" || !s.isNull("manual_text"))reasons+="MANUAL_STATE_PROTECTED"
        // Existing deliberate summary reviews remain canonical. No auto result claims to be a user review.
        if(db.query("SELECT receipt FROM workflow_reviewed_summaries WHERE recording_id=?",j.getString("recording_id")).isNotEmpty())reasons+="USER_REVIEWED_SUMMARY_PROTECTED"
        // Signed automatic-use evidence is scoped to the exact frozen prior cumulative head.
        // A concurrent result cannot silently replace a newer cumulative history.
        if(assurance.evidenceRef.isNotBlank()) {
            val prior=db.query("SELECT c.result_id,j.id FROM automation_case_heads c JOIN automation_cumulative_history h ON h.id=c.result_id JOIN automation_jobs j ON j.id=h.job_id WHERE c.case_id=? AND j.owner=? AND j.epoch=? AND j.policy_hash=?",s.optString("case_id"),owner,epoch,policy()).singleOrNull()
            val expected=JSONObject(j.getString("business_json")).getString("proposedSummary")
            val currentPrior=if(prior==null)"" else if(!allowed(prior["id"].toString()))null else db.query("SELECT cumulative_text FROM automation_cumulative_history WHERE id=?",prior["result_id"]).single()["cumulative_text"] as String
            if(currentPrior!=expected)reasons+="VERIFIED_BUSINESS_CONTEXT_CHANGED"
        }
        var appliedItems=0;var pendingItems=0
        if(reasons.isEmpty()) {
            for(i in 0 until result.tasks.length()) {
                val t=result.tasks.getJSONObject(i)
                if(t.getString("status")=="done") {pendingItems++;continue} // Completion is never inferred over manual DONE.
                val target=t.optString("taskId").takeIf{it.isNotBlank()&&it!="null"}
                val old=target?.let{db.query("SELECT * FROM workflow_items WHERE id=? AND case_id=?",it,s.getString("case_id")).singleOrNull()}
                val binding=target?.let{db.query("SELECT * FROM automation_item_bindings WHERE item_id=?",it).singleOrNull()}
                if(target!=null) {
                    val known=JSONObject(j.getString("business_json")).getJSONArray("tasks")
                    val frozenTarget=(0 until known.length()).map{known.getJSONObject(it)}.singleOrNull{it.getString("id")==target}
                    val targetSource=old?.let{current(it["recording_id"].toString())}
                    if(frozenTarget==null || frozenTarget.getJSONArray("manualFields").length()>0 || targetSource==null || targetSource.optInt("archived")!=0 || targetSource.optInt("grant_active")!=1 || !sourcePermission(targetSource)){pendingItems++;continue}
                }
                if(target!=null && (old==null || binding==null || old["status"]=="done" || n(old,"revision")!=n(binding,"last_revision") || s.getLong("original_started_at_ms")<n(binding,"original_ms") || db.query("SELECT item_id FROM workflow_item_history WHERE item_id=?",target).isNotEmpty())) {pendingItems++;continue}
                val fingerprint=hash(listOf(s.getString("case_id"),t.getString("title").trim(),t.optString("assignee"),t.optString("due")))
                val item=target ?: "auto_item_"+fingerprint.take(40)
                val existing=db.query("SELECT * FROM workflow_items WHERE id=?",item).singleOrNull()
                if(target==null && existing!=null) {
                    val owned=db.query("SELECT * FROM automation_item_bindings WHERE item_id=?",item).singleOrNull()
                    if(owned==null || n(owned,"last_revision")!=n(existing,"revision") || existing["status"]!=t.getString("status") || db.query("SELECT item_id FROM workflow_item_history WHERE item_id=?",item).isNotEmpty())pendingItems++
                    continue
                }
                val revision=(old?.let{n(it,"revision")} ?: 0L)+1
                if(old==null)db.execute("INSERT INTO workflow_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",item,j.getString("recording_id"),s.getString("case_id"),"TODO",t.getString("title"),"",t.optString("due").takeUnless{it=="null"}.orEmpty(),t.getString("status"),revision,null,t.getString("quote"),now())
                else db.execute("UPDATE workflow_items SET title=?,due_text=?,status=?,revision=? WHERE id=?",t.getString("title"),t.optString("due").takeUnless{it=="null"}.orEmpty(),t.getString("status"),revision,item)
                db.execute("INSERT OR REPLACE INTO automation_item_bindings VALUES(?,?,?,?,?)",item,revision,resultId,result.digest,s.getLong("original_started_at_ms"));appliedItems++
            }
            db.execute("INSERT INTO automation_cumulative_history VALUES(?,?,?,?,?,?,?)",resultId,id,s.getString("case_id"),s.getLong("original_started_at_ms"),result.cumulativeSummary,result.summary,now())
            db.execute("INSERT OR REPLACE INTO automation_cumulative_heads VALUES(?,?,?)",j.getString("recording_id"),resultId,result.revision)
            val priorCase=db.query("SELECT * FROM automation_case_heads WHERE case_id=?",s.getString("case_id")).singleOrNull()
            if(priorCase==null || s.getLong("original_started_at_ms")>=n(priorCase,"original_ms"))
                db.execute("INSERT OR REPLACE INTO automation_case_heads VALUES(?,?,?)",s.getString("case_id"),resultId,s.getLong("original_started_at_ms"))
            if(appliedItems>0)db.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
        }
        val decision=if(reasons.isEmpty())"APPLIED_SAFE_DERIVED" else "REVIEW_REQUIRED"
        val reason=if(reasons.isEmpty())if(pendingItems>0)"SAFE_SUMMARY_APPLIED_SOME_TASKS_REQUIRE_REVIEW" else "VERIFIED_SAFE_DERIVED_APPLIED" else reasons.joinToString(";")
        db.execute("INSERT INTO automation_applications VALUES(?,?,?,?,?,?,?)",resultId,id,decision,reason,appliedItems,pendingItems,now())
        db.execute("UPDATE automation_jobs SET result_revision=?,result_digest=?,state=?,reason=?,updated_ms=?,next_poll_ms=? WHERE id=?",result.revision,result.digest,decision,reason,now(),now()+900000,id)
        event(id,decision,if(reasons.isEmpty())"SAFE_DERIVED_COMMITTED" else "PROPOSAL_PRESERVED_REVIEW_REQUIRED")
        check(scopeCurrent() && policy()==j.getString("policy_hash") && sourcePermission(s)) { "AUTOMATION_SCOPE_OR_POLICY_CHANGED" }
        db.execute("UPDATE automation_report_queue SET state='SUPERSEDED_BY_RESULT' WHERE job_id=? AND result_id NOT IN (SELECT id FROM automation_results)",id)
        queueResult(id,resultId,s,decision,pendingItems);null
    }
    private fun queueResult(id:String,resultId:String,s:JSONObject,decision:String,pendingItems:Int) {
        val zone=reportZone();val timestamp=s.optLong("original_started_at_ms",0)
        val day=if(zone!=null && timestamp>0)Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(zone)).toLocalDate().toString() else ""
        val due=if(day.isNotEmpty())ReportWindow(LocalDate.parse(day),ZoneId.of(zone!!)).due.toEpochMilli() else 0L
        db.execute("INSERT INTO automation_report_queue VALUES(?,?,?,?,?,?,?,?,?,?)",resultId,id,day,zone.orEmpty(),due,
            if(day.isBlank())"WAITING_REPORT_TIME_SETTINGS" else if(decision=="APPLIED_SAFE_DERIVED" && pendingItems==0)"READY_FOR_REPORT_CONSUMER" else "PARTIAL_REVIEW_REQUIRED",resultId,"",0,now())
    }
    /** Queues pending/failed work as well, and revalidates policy before every report read. */
    fun refreshReportQueue()=db.transaction {
        for(j in jobs()) {
            val id=j.getString("id");if(!allowed(id))continue
            if(j.getLong("result_revision")==0L) {
                val key=hash(listOf("pending-report",id));val s=checkNotNull(current(j.getString("recording_id")))
                if(db.query("SELECT id FROM automation_report_queue WHERE id=?",key).isEmpty())queueResult(id,key,s,"PENDING",1)
                db.execute("UPDATE automation_report_queue SET state=? WHERE id=?",j.getString("state"),key)
            } else {
                val s=checkNotNull(current(j.getString("recording_id")));val base=sourceSnapshot(id)
                if(listOf("user_revision","lock_revision","head_revision","task_revision","visibility_revision").any{s.optLong(it)!=base.optLong(it)} || s.optInt("task_manual_locks")!=0 || s.optString("manual_status")=="done")
                    db.execute("UPDATE automation_report_queue SET state='CURRENT_STATE_REVIEW_REQUIRED' WHERE job_id=? AND state!='SUPERSEDED_BY_RESULT'",id)
            }
        };null
    }
    fun reportQueue()=db.query("SELECT * FROM automation_report_queue ORDER BY created_ms,id").map(::json)
    fun linkReport(id:String,report:String) {db.execute("UPDATE automation_report_queue SET report_id=?,bridge_ms=? WHERE id=?",report,now(),id)}
    private fun manualItemConflict(case:String)=db.query("SELECT i.id FROM workflow_items i JOIN automation_item_bindings b ON b.item_id=i.id WHERE i.case_id=? AND (i.revision!=b.last_revision OR EXISTS(SELECT 1 FROM workflow_item_history h WHERE h.item_id=i.id))",case).isNotEmpty()
    /** Report owner consumes these automatic sources with their actual origin; no USER_ACCEPT alias. */
    fun reportBatch(window:ReportWindow):JSONObject=db.transaction {
        val entries=JSONArray();val pending=JSONArray()
        for(j in jobs()) {
            if(!allowed(j.getString("id")))continue
            val s=checkNotNull(current(j.getString("recording_id")));val at=s.optLong("original_started_at_ms",0)
            if(at>0 && (at<window.from.toEpochMilli() || at>=window.until.toEpochMilli()))continue
            val app=db.query("SELECT a.*,h.summary,h.cumulative_text FROM automation_applications a LEFT JOIN automation_cumulative_history h ON h.id=a.id WHERE a.job_id=? ORDER BY a.created_ms DESC,a.rowid DESC LIMIT 1",j.getString("id")).singleOrNull()
            val base=sourceSnapshot(j.getString("id"))
            val currentSafe=j.getString("state")=="APPLIED_SAFE_DERIVED" && app?.get("decision")=="APPLIED_SAFE_DERIVED" && n(app,"pending_items")==0L && at>0 &&
                listOf("user_revision","lock_revision","head_revision","task_revision","visibility_revision").all {s.optLong(it)==base.optLong(it)} && s.optString("manual_status")!="done" && s.optInt("task_manual_locks")==0 && !manualItemConflict(s.optString("case_id"))
            val item=JSONObject().put("jobId",j.getString("id")).put("recordingId",j.getString("recording_id")).put("sourceVersion",j.getLong("source_version"))
                .put("audioSha",j.getString("audio_sha")).put("resultDigest",j.getString("result_digest")).put("state",j.getString("state"))
                .put("reason",if(currentSafe)j.getString("reason") else "CURRENT_STATE_REVIEW_OR_ANALYSIS_PENDING").put("origin","AUTOMATIC_DERIVED_NOT_USER_REVIEW")
                .put("userRevision",s.getLong("user_revision")).put("lockRevision",s.getLong("lock_revision")).put("headRevision",s.getLong("head_revision"))
            if(currentSafe) {
                item.put("summary",app!!["summary"]).put("cumulativeSummary",app["cumulative_text"])
                    .put("currentItems",JSONArray(db.query("SELECT id,title,status,revision,due_text FROM workflow_items WHERE case_id=? ORDER BY created_ms,id",s.getString("case_id")).map(::json)))
                entries.put(item)
            } else pending.put(item)
        }
        JSONObject().put("schema","ieum.android.automation.report-input.v1").put("automatic",entries).put("unresolved",pending)
            .put("complete",pending.length()==0 && entries.length()>0).put("kakaoSent",false)
    }
    /** Local copies have automatic provenance. They are never a reviewed-only Kakao payload. */
    fun saveLocalReport(window:ReportWindow,batch:JSONObject,at:Long):Pair<String,String> = db.transaction {
        check(scopeCurrent()) {"AUTOMATION_SCOPE_CHANGED"}
        val payload=StrictJson.stringify(StrictJson.`object`(StrictJson.parse(batch.toString().toByteArray())))
        val digest=DriveRelay.sha256(payload.toByteArray());val alert=hash(listOf("local-daily-alert-v1",owner,window.date.toString(),window.zone.id,window.from.toEpochMilli(),window.until.toEpochMilli()))
        val id=hash(listOf(alert,digest))
        if(db.query("SELECT id FROM automation_local_reports WHERE id=?",id).isEmpty())
            db.execute("INSERT INTO automation_local_reports VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,owner,epoch,window.date.toString(),window.zone.id,window.from.toEpochMilli(),window.until.toEpochMilli(),policy(),digest,payload,at)
        if(db.query("SELECT id FROM automation_local_alerts WHERE id=?",alert).isEmpty())
            db.execute("INSERT INTO automation_local_alerts VALUES(?,?,?,?)",alert,"WAITING_06","LOCAL_REPORT_STORED",at)
        check(scopeCurrent()) {"AUTOMATION_SCOPE_CHANGED"};id to alert
    }
    fun localAlert(id:String)=json(db.query("SELECT * FROM automation_local_alerts WHERE id=?",id).single())
    fun localAlertState(id:String,state:String,reason:String,at:Long) {
        db.execute("UPDATE automation_local_alerts SET state=?,reason=?,updated_ms=? WHERE id=?",state,reason,at,id)
    }
    fun claimLocalAlert(id:String,at:Long):Boolean=db.transaction {
        check(scopeCurrent()) {"AUTOMATION_SCOPE_CHANGED"};val row=localAlert(id)
        if(row.getString("state") in setOf("LOCAL_NOTIFY_REQUESTED","UNKNOWN"))return@transaction false
        if(row.getString("state")=="INTENT") {localAlertState(id,"UNKNOWN","LOCAL_NOTIFY_INTERRUPTED_NO_REPEAT",at);return@transaction false}
        localAlertState(id,"INTENT","LOCAL_NOTIFICATION_INTENT_DURABLE",at);true
    }
    override fun close()=db.close()
    companion object {
        fun hash(value:Any?):String=DriveRelay.sha256(StrictJson.bytes(value))
        private val schema=listOf(
            "CREATE TABLE IF NOT EXISTS automation_jobs(id TEXT PRIMARY KEY,owner TEXT NOT NULL,epoch INTEGER NOT NULL,recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,snapshot_json TEXT NOT NULL,policy_hash TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,request_id TEXT NOT NULL DEFAULT '',handoff TEXT NOT NULL DEFAULT '',business_json TEXT NOT NULL DEFAULT '',receipt TEXT NOT NULL DEFAULT '',result_revision INTEGER NOT NULL DEFAULT 0,result_digest TEXT NOT NULL DEFAULT '',claim_token TEXT NOT NULL DEFAULT '',claim_until INTEGER NOT NULL DEFAULT 0,next_poll_ms INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL,UNIQUE(owner,epoch,recording_id,source_version,audio_sha))",
            "CREATE TABLE IF NOT EXISTS automation_results(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,payload TEXT NOT NULL,assurance TEXT NOT NULL,transcript TEXT NOT NULL,created_ms INTEGER NOT NULL,UNIQUE(job_id,revision))",
            "CREATE TABLE IF NOT EXISTS automation_applications(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,decision TEXT NOT NULL,reason TEXT NOT NULL,applied_items INTEGER NOT NULL,pending_items INTEGER NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS automation_events(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,kind TEXT NOT NULL,reason TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS automation_cumulative_history(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,case_id TEXT NOT NULL,original_ms INTEGER NOT NULL,cumulative_text TEXT NOT NULL,summary TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS automation_cumulative_heads(recording_id TEXT PRIMARY KEY,result_id TEXT NOT NULL,revision INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS automation_case_heads(case_id TEXT PRIMARY KEY,result_id TEXT NOT NULL,original_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS automation_item_bindings(item_id TEXT PRIMARY KEY,last_revision INTEGER NOT NULL,result_id TEXT NOT NULL,result_digest TEXT NOT NULL,original_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS automation_report_queue(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,due_ms INTEGER NOT NULL,state TEXT NOT NULL,result_id TEXT NOT NULL,report_id TEXT NOT NULL,bridge_ms INTEGER NOT NULL,created_ms INTEGER NOT NULL)"
            ,"CREATE TABLE IF NOT EXISTS automation_local_reports(id TEXT PRIMARY KEY,owner TEXT NOT NULL,epoch INTEGER NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,from_ms INTEGER NOT NULL,until_ms INTEGER NOT NULL,policy_hash TEXT NOT NULL,digest TEXT NOT NULL,payload TEXT NOT NULL,created_ms INTEGER NOT NULL)"
            ,"CREATE TABLE IF NOT EXISTS automation_local_alerts(id TEXT PRIMARY KEY,state TEXT NOT NULL,reason TEXT NOT NULL,updated_ms INTEGER NOT NULL)"
            ,"CREATE TRIGGER IF NOT EXISTS automation_local_report_no_update BEFORE UPDATE ON automation_local_reports BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END"
            ,"CREATE TRIGGER IF NOT EXISTS automation_local_report_no_delete BEFORE DELETE ON automation_local_reports BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END"
            ,"CREATE TRIGGER IF NOT EXISTS automation_local_report_no_replace BEFORE INSERT ON automation_local_reports WHEN EXISTS(SELECT 1 FROM automation_local_reports WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END"
        )
    }
}
