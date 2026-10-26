package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kr.callbrief.context.*
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject

/** Native file receiver. Result candidates, native automatic history and explicit user commands
 * are separate. The immutable candidate and Store.accept share the primary protected transaction. */
internal class SiwcNativeReviewStore(private val context:Context,private val owner:String,
    private val intake:UnclassifiedAudioStore,private val automation:AndroidAutomationStore,
    private val current:()->Boolean,private val policy:()->JSONObject,private val fault:()->Unit={}):AutoCloseable {
    init {require(context is AccountStorageContext && context.ownerId==owner)}
    private val workflow=RecordingWorkflowStore(context,policySnapshot=policy,scopeCurrent=current)
    private val db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    init {db.requireProtectedPersistence();db.transaction {
        db.execute("CREATE TABLE IF NOT EXISTS siwc_native_candidates(id TEXT PRIMARY KEY,request_id TEXT NOT NULL,job_id TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,snapshot_sha TEXT NOT NULL,policy_sha TEXT NOT NULL,payload TEXT NOT NULL,ledger TEXT NOT NULL,origin TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS siwc_native_user_reviews(id TEXT PRIMARY KEY,summary_receipt TEXT NOT NULL,items_json TEXT NOT NULL)")
        for(table in listOf("siwc_native_candidates","siwc_native_user_reviews")) {
            for(op in listOf("UPDATE","DELETE"))db.execute("CREATE TRIGGER IF NOT EXISTS ${table}_${op.lowercase()} BEFORE $op ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
            db.execute("CREATE TRIGGER IF NOT EXISTS ${table}_no_replace BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
        };null
    }}
    class Ticket internal constructor(val request:String,val job:String,val contract:SiwcNativeContract,val policySha:String,
        internal val frozenHandoff:String,internal val resultRevision:Long,internal val fence:String)
    private fun sha(x:Any?)=AstraAudioContract.sha(StrictJson.bytes(x))
    private fun liveSnapshot(id:String):Pair<JSONObject,Map<String,Any?>> {
        val s=workflow.snapshot(id)
        val manual=db.query("SELECT DISTINCT item_id FROM workflow_item_history").map{it["item_id"].toString()}.toSet()
        return s to SiwcNativeContract.snapshot(s,manual)
    }
    private fun fence(s:JSONObject)=sha(listOf("archived","grant_active","presence","completed_lock","edited_lock","manual_status","manual_text","task_manual_locks","analysis_authority_epoch").associateWith{if(s.isNull(it))null else s.opt(it)})
    fun prepare(request:String):Ticket=db.transaction {
        check(current() && intake.available(request) && intake.status(request)=="BOUND") { "SIWC_BOUND_CURRENT_SOURCE_REQUIRED" }
        val source=SiwcLocalTranscript.sourcePack(intake,request);val id=source["sourceId"] as String
        val job=automation.jobs().single{it.getString("recording_id")==id && it.getLong("source_version")==source["sourceVersion"] && it.getString("audio_sha")==source["audioSha256"]}
        check(job.getString("state") !in setOf("CANCELLED","HELD_SOURCE") && automation.allowed(job.getString("id")) && automation.resultWindowOpen(job.getString("id"))) { "SIWC_NATIVE_JOB_NOT_CURRENT" }
        val native=JSONObject(JSONObject(job.getString("handoff")).getString("frozenRequestJson"))
        val (s,snapshot)=liveSnapshot(id)
        val contract=SiwcNativeContract(source,snapshot,native.getString("jobId"),native.getLong("baseRevision"))
        Ticket(request,job.getString("id"),contract,AndroidAutomationRuntime.policyHash(policy()),job.getString("handoff"),job.getLong("result_revision"),fence(s))
    }
    fun isCurrent(t:Ticket):Boolean=isCurrent(t,false)
    private fun isCurrent(t:Ticket,ownBoardAdvance:Boolean):Boolean=runCatching {
        check(current() && intake.available(t.request) && intake.status(t.request)=="BOUND" && AndroidAutomationRuntime.policyHash(policy())==t.policySha)
        val job=automation.job(t.job);check(job.getString("state") !in setOf("CANCELLED","HELD_SOURCE") && automation.allowed(t.job) && automation.resultWindowOpen(t.job) && job.getString("handoff")==t.frozenHandoff)
        val (s,snapshot)=liveSnapshot(t.contract.source["sourceId"] as String)
        val compared=if(!ownBoardAdvance)snapshot else snapshot+mapOf("fences" to (SiwcNativeContract.obj(snapshot["fences"])+mapOf("board_revision" to SiwcNativeContract.obj(t.contract.snapshot["fences"])["board_revision"])))
        check(sha(compared)==t.contract.snapshotSha && fence(s)==t.fence && sha(SiwcLocalTranscript.sourcePack(intake,t.request))==sha(t.contract.source));true
    }.getOrDefault(false)
    /** Only the authenticated local result-file reader supplies a bundle. No arbitrary JSON path,
     * Intent flag, model receipt or directory listing can manufacture durable completion. */
    fun receive(t:Ticket,files:SiwcResultFiles,analysis:SiwcAstraProtocol.Invocation,contextRequest:SiwcAstraProtocol.Invocation,fixture:Boolean=false):JSONObject {
        check(isCurrent(t)) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        check(analysis.body().contentEquals(SiwcNativeRoundTrip.analysis(t,analysis.scope).body())) { "SIWC_NATIVE_ANALYSIS_INPUT_CHANGED" }
        val va:(String)->Unit={t.contract.analysis(it);Unit}
        val a=files.read(StrictJson.bytes(t.contract.pack),analysis,contextRequest,va) { output ->
            // Read the committed analysis via the bundle's own deterministic output below;
            // expected() already obtains and validates it before this callback.
            val input=SiwcNativeContract.parse(StrictJson.stringify(StrictJson.parse(contextRequest.body())))
            val content=SiwcNativeContract.obj(SiwcNativeContract.list(input["input"],1).single())["content"] as String
            val modelInput=SiwcNativeContract.parse(content)
            check(sha(modelInput["approvedSnapshot"])==t.contract.snapshotSha) { "SIWC_NATIVE_CONTEXT_INPUT_CHANGED" }
            t.contract.context(output,analysis.key(),StrictJson.stringify(modelInput["analysis"]));Unit
        }
        val raw=StrictJson.stringify(a);val digest=sha(a);val id=files.key(analysis,contextRequest)
        val analysisOutput=StrictJson.stringify(a["analysis"]);val contextOutput=StrictJson.stringify(a["context"])
        check(a["scope"]==analysis.scope && a["manifestSha256"]==analysis.sourceManifestSha && SiwcNativeContract.obj(a["receipt"])["userApproved"]==false)
        val event=t.contract.normalized(owner,t.request,analysisOutput,contextOutput,analysis.key()).copy(observedAt=java.time.Instant.now())
        return db.transaction {
            check(isCurrent(t)) { "SIWC_NATIVE_CURRENT_REQUIRED" }
            val old=db.query("SELECT * FROM siwc_native_candidates WHERE id=?",id).singleOrNull()
            if(old!=null){check(old["payload"]==raw && old["digest"]==digest) { "SIWC_NATIVE_ARTIFACT_CONFLICT" };return@transaction candidate(id)}
            check(automation.job(t.job).getLong("result_revision")==t.resultRevision) { "SIWC_NATIVE_RESULT_ADVANCED" }
            val state=project(t,event)
            val origin=if(fixture)"SIWC_FIXTURE_FILE_REVIEW_PROPOSAL" else "SIWC_FILE_REVIEW_PROPOSAL"
            check(automation.claim(t.job)) { "SIWC_NATIVE_CLAIM_BUSY" }
            try {
                val summary=t.contract.analysis(analysisOutput)["summary"] as String
                val result=AutomationAnalysis(t.resultRevision+1,digest,raw,t.contract.text,summary,summary,JSONArray(),JSONObject(t.frozenHandoff).getString("frozenRequestJson"))
                automation.accept(t.job,result,AutomationAssurance(origin,verifiedSource=true,privacyReviewed=true))
                check(automation.job(t.job).let{it.getLong("result_revision")==result.revision && it.getString("result_digest")==digest && it.getString("state")=="REVIEW_REQUIRED"}) { "SIWC_NATIVE_NOT_COMMITTED" }
                db.execute("INSERT INTO siwc_native_candidates VALUES(?,?,?,?,?,?,?,?,?,?)",id,t.request,t.job,result.revision,digest,t.contract.snapshotSha,t.policySha,raw,ProtectedContextCodec.encode(state),origin)
                fault();check(isCurrent(t)) { "SIWC_NATIVE_CURRENT_REQUIRED" };candidate(id)
            } finally {automation.release(t.job)}
        }
    }
    private fun project(t:Ticket,event:NormalizedEvent):ContextState {
        val s=t.contract.snapshot;val f=SiwcNativeContract.obj(s["fences"])
        val fences=Fences(f["user_revision"] as Long,f["lock_revision"] as Long,f["task_revision"] as Long,f["board_revision"] as Long,f["link_revision"] as Long,f["visibility_revision"] as Long,0)
        // Work identity is from an existing explicit workflow command, never the model.
        val relation=db.query("SELECT event_id FROM workflow_user_receipts WHERE recording_id=? AND operation='CONFIRM_WORK' ORDER BY created_ms DESC",event.ref.key.source.externalId).firstOrNull()
        val link=if(relation==null || s["personId"]==null || s["caseId"]==null)emptyList() else {
            val evidence=EventAdapter.evidence(event.ref,event.body,0,event.body.codePointCount(0,event.body.length).coerceAtMost(1000))
            listOf(ReviewedLink(event.ref,WorkScope(owner,s["personId"] as String,s["caseId"] as String,s["companyId"] as? String),fences.relation,evidence,evidence,relation["event_id"].toString()))
        }
        val view=RepositoryView(owner,fences,setOf(AdmittedSource(event.ref,t.request,0)),link)
        return ContextReconciler().ingest(ContextState(owner),event,view).also{check(it.receipt.status==ReceiptStatus.ACCEPTED) { "SIWC_CONTEXT_NATIVE_REJECTED" }}.state
    }
    fun candidate(id:String):JSONObject {
        val r=db.query("SELECT * FROM siwc_native_candidates WHERE id=?",id).single()
        return JSONObject().put("id",id).put("digest",r["digest"]).put("origin",r["origin"]).put("artifact",JSONObject(r["payload"].toString()))
            .put("candidate",JSONObject(CandidateJson.encode(ProtectedContextCodec.decode(r["ledger"].toString()),Fences(0,0,0,0,0,0,0))))
            .put("canonicalWriteAllowed",false).put("userApproved",db.query("SELECT id FROM siwc_native_user_reviews WHERE id=?",id).isNotEmpty())
    }
    /** A historical user receipt is read, never reapplied. A superseding result/summary or
     * revoked source prevents presenting it as the current reviewed candidate on reopening. */
    fun reviewedReceipt(id:String):String?=db.transaction {
        val row=db.query("SELECT * FROM siwc_native_candidates WHERE id=?",id).single()
        val review=db.query("SELECT summary_receipt FROM siwc_native_user_reviews WHERE id=?",id).singleOrNull() ?: return@transaction null
        val request=row["request_id"].toString();check(current() && intake.available(request) && AndroidAutomationRuntime.policyHash(policy())==row["policy_sha"]) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        val job=automation.job(row["job_id"].toString());check(automation.allowed(job.getString("id")) && job.getString("state")=="REVIEW_REQUIRED" && job.getLong("result_revision")==row["revision"] && job.getString("result_digest")==row["digest"]) { "SIWC_REVIEW_RESULT_SUPERSEDED" }
        val v=intake.view(request);val latest=db.query("SELECT receipt,source_version,audio_sha FROM workflow_reviewed_summaries WHERE recording_id=? ORDER BY revision DESC LIMIT 1",v.getString("sourceId")).single()
        check(latest["receipt"]==review["summary_receipt"] && latest["source_version"]==job.getLong("source_version") && latest["audio_sha"]==job.getString("audio_sha")) { "SIWC_REVIEW_SUMMARY_SUPERSEDED" }
        review["summary_receipt"].toString()
    }
    fun approve(t:Ticket,id:String,displayedDigest:String,confirmed:Boolean):String=approveOne(t,id,displayedDigest,confirmed,false)
    fun approveSelection(selection:Map<Ticket,Pair<String,String>>,confirmed:Boolean):List<String> =db.transaction {
        check(confirmed && selection.isNotEmpty()) { "SIWC_EXPLICIT_USER_REVIEW_REQUIRED" }
        if(selection.values.all{db.query("SELECT id FROM siwc_native_user_reviews WHERE id=?",it.first).isNotEmpty()})return@transaction selection.map{(t,v)->
            val row=db.query("SELECT request_id,digest FROM siwc_native_candidates WHERE id=?",v.first).single()
            check(row["request_id"]==t.request && row["digest"]==v.second) { "SIWC_REVIEW_CHANGED" };checkNotNull(reviewedReceipt(v.first))
        }
        check(selection.keys.all{isCurrent(it)}) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        selection.map{(ticket,value)->approveOne(ticket,value.first,value.second,true,true)}
    }
    private fun approveOne(t:Ticket,id:String,displayedDigest:String,confirmed:Boolean,ownBoardAdvance:Boolean):String=db.transaction {
        check(confirmed) { "SIWC_EXPLICIT_USER_REVIEW_REQUIRED" }
        check(current()) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        val row=db.query("SELECT * FROM siwc_native_candidates WHERE id=?",id).single()
        check(row["request_id"]==t.request && row["digest"]==displayedDigest) { "SIWC_REVIEW_CHANGED" }
        val old=db.query("SELECT summary_receipt FROM siwc_native_user_reviews WHERE id=?",id).singleOrNull()
        if(old!=null)return@transaction checkNotNull(reviewedReceipt(id))
        check(row["snapshot_sha"]==t.contract.snapshotSha) { "SIWC_REVIEW_CHANGED" }
        check(isCurrent(t,ownBoardAdvance)) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        val job=automation.job(t.job);check(job.getLong("result_revision")==row["revision"] && job.getString("result_digest")==row["digest"] && job.getString("state")=="REVIEW_REQUIRED") { "SIWC_REVIEW_RESULT_SUPERSEDED" }
        val sourceId=t.contract.source["sourceId"] as String;val s=workflow.snapshot(sourceId)
        check(s.optInt("completed_lock")==0 && s.optInt("edited_lock")==0 && s.optInt("task_manual_locks")==0 && s.optString("task_status")!="done" && s.optString("manual_status")!="done" && s.isNull("manual_text")) { "SIWC_MANUAL_STATE_PROTECTED" }
        check(db.query("SELECT receipt FROM workflow_reviewed_summaries WHERE recording_id=?",sourceId).isEmpty()) { "SIWC_EXISTING_USER_SUMMARY_PROTECTED" }
        val ledger=ProtectedContextCodec.decode(row["ledger"].toString());check(ledger.projection.holds.isEmpty()) { "SIWC_CONTEXT_HELD" }
        val artifact=JSONObject(row["payload"].toString());val actions=artifact.getJSONObject("context").getJSONArray("actions")
        val claims=artifact.getJSONObject("analysis").getJSONArray("claims")
        for(i in 0 until claims.length())check(claims.getJSONObject(i).isNull("replaces") && claims.getJSONObject(i).isNull("assignee")) { "SIWC_EXPLICIT_EDIT_FLOW_REQUIRED" }
        for(i in 0 until actions.length()) {val x=actions.getJSONObject(i)
            check(x.isNull("targetId") && x.getString("operation")=="ASSERT" && !x.getBoolean("uncertain") && !x.getBoolean("conflict") && x.getString("kind") in setOf("TASK","DOCUMENT")) { "SIWC_EXPLICIT_EDIT_FLOW_REQUIRED" }
        }
        val created=JSONArray()
        for((key,item) in ledger.projection.items) {
            val title=checkNotNull(item.fields[Field.TITLE]?.value);check(title.length<=240) { "SIWC_ITEM_TITLE_REVIEW_REQUIRED" }
            check(item.fields[Field.STATUS]?.value=="NEEDS_CONFIRMATION") { "SIWC_EXPLICIT_STATUS_REVIEW_REQUIRED" }
            val quote=ledger.events.values.flatMap{it.claims}.single{it.targetId==key.targetId}.evidence.quote
            created.put(workflow.createItem(sourceId,workflow.snapshot(sourceId),if(key.kind==Kind.DOCUMENT)"DOCUMENT" else "TODO",title,"",item.fields[Field.DUE]?.value.orEmpty(),quote=quote,initialStatus="needs_confirmation"))
        }
        val receipt=workflow.reviewOwnSummary(sourceId,workflow.snapshot(sourceId),artifact.getJSONObject("analysis").getString("summary"))
        db.execute("INSERT INTO siwc_native_user_reviews VALUES(?,?,?)",id,receipt,created.toString());fault()
        check(current() && intake.available(t.request) && AndroidAutomationRuntime.policyHash(policy())==t.policySha) { "SIWC_NATIVE_CURRENT_REQUIRED" };receipt
    }
    override fun close(){workflow.close();db.close()}
}
