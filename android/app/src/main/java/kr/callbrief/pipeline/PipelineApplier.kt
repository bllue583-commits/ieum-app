package kr.callbrief.pipeline

import org.json.JSONObject

/** Applies one verified C2 to the app's own tasks and case summaries under the app's rules
 * (docs/contracts/README.md "App application rules"), in ONE protected transaction.
 * Never infers completion, never touches done, user-created, user-edited or locked tasks,
 * and never applies anything to an archived, excluded or withdrawn recording. */
internal class PipelineApplier(private val store:PipelineApplyStore,private val policy:()->JSONObject) {
    data class Outcome(val analysisId:String,val state:String,val reason:String,val applied:Int,val review:Int,val duplicate:Boolean=false)

    /** [c2] verified and validated against [c1]; [recordingId] from the local transcript index. */
    fun apply(c2:Map<String,Any?>,c1:Map<String,Any?>,recordingId:String):Outcome = store.tx {
        val id=c2["analysisId"].toString()
        store.analysis(id)?.let { return@tx Outcome(id,it["state"].toString(),it["reason"].toString(),0,0,true) }
        val src=PipelineContract.obj(c1["source"]);val pol=PipelineContract.obj(c1["policy"])
        val source=PipelineApplyStore.Source(src["sourceVersion"] as Long,src["audioSha256"].toString(),pol["consentEpoch"] as Long)
        require(src["recordingId"]==recordingId) { "TRANSCRIPT_RECORDING_MISMATCH" }
        store.ineligible(recordingId,source,policy())?.let { reason ->
            store.insertAnalysis(c2,recordingId,source,"REJECTED",reason);return@tx Outcome(id,"REJECTED",reason,0,0)
        }
        val target=c2["targetCaseId"] as String?
        if(target==null) {
            store.insertAnalysis(c2,recordingId,source,"AWAITING_CASE","CASE_UNCONFIRMED")
            return@tx Outcome(id,"AWAITING_CASE","CASE_UNCONFIRMED",0,0)
        }
        store.insertAnalysis(c2,recordingId,source,"REVIEW","PENDING")
        val linked=store.rows("SELECT case_id FROM workflow_links WHERE recording_id=?",recordingId).singleOrNull()?.get("case_id")
        val forced=when {
            linked!=target->"CASE_LINK_CHANGED"
            supersedesApplied(c2)->"NEWER_REVISION_OF_APPLIED_ANALYSIS"
            !store.boardFresh(c2["baseBoardRevision"] as Long)->"BOARD_REVISION_CHANGED"
            else->null
        }
        commit(c2,c1,recordingId,target,forced,updatesAllowed=true)
    }

    /** After the user explicitly confirmed [caseId] for an AWAITING_CASE analysis (lane C confirm). */
    fun applyConfirmed(analysisId:String,caseId:String,suggestedChosen:Boolean,boardWasFresh:Boolean,c1:Map<String,Any?>?):Outcome = store.tx {
        val row=checkNotNull(store.analysis(analysisId)) { "ANALYSIS_UNKNOWN" }
        check(row["state"]=="AWAITING_CASE") { "ANALYSIS_NOT_AWAITING_CASE" }
        val c2=store.payload(row);val recording=row["recording_id"].toString()
        store.ineligible(recording,store.analysisSource(row),policy())?.let { reason ->
            store.setAnalysisState(analysisId,"REJECTED",reason,caseId);return@tx Outcome(analysisId,"REJECTED",reason,0,0)
        }
        val forced=when { supersedesApplied(c2)->"NEWER_REVISION_OF_APPLIED_ANALYSIS";!boardWasFresh->"BOARD_REVISION_CHANGED";else->null }
        commit(c2,c1,recording,caseId,forced,updatesAllowed=suggestedChosen)
    }

    private fun c1Task(c1:Map<String,Any?>?,caseId:String?,taskId:String):Map<String,Any?>? {
        if(c1==null||caseId==null)return null
        val case=(PipelineContract.obj(c1["business"])["cases"] as List<*>).map { PipelineContract.obj(it) }.singleOrNull { it["caseId"]==caseId } ?: return null
        return (case["tasks"] as List<*>).map { PipelineContract.obj(it) }.singleOrNull { it["taskId"]==taskId }
    }
    private fun supersedesApplied(c2:Map<String,Any?>)=store.rows("SELECT analysis_id FROM pipeline_analyses WHERE transcript_id=? AND revision<? AND state IN('APPLIED','PARTIAL_REVIEW')",c2["transcriptId"],c2["revision"]).isNotEmpty()
    private fun summaryProtected(recordingId:String,caseId:String):Boolean =
        store.userReviewedSummary(recordingId) || store.rows("SELECT recording_id FROM workflow_links WHERE case_id=?",caseId).any { store.userReviewedSummary(it["recording_id"].toString()) }

    private fun commit(c2:Map<String,Any?>,c1:Map<String,Any?>?,recordingId:String,caseId:String,forced:String?,updatesAllowed:Boolean):Outcome {
        val id=c2["analysisId"].toString();var applied=0;var review=0;var summaryApplied=false
        fun hold(index:Long,kind:String,reason:String,payload:Map<String,Any?>) { store.addReview(id,index,kind,reason,payload+mapOf("caseId" to caseId,"recordingId" to recordingId));review++ }
        // Summary / cumulative summary: index -1.
        val summary=mapOf("summary" to c2["summary"],"cumulativeSummary" to c2["cumulativeSummary"])
        when {
            forced!=null->hold(-1,"SUMMARY",forced,summary)
            summaryProtected(recordingId,caseId)->hold(-1,"SUMMARY","USER_REVIEWED_SUMMARY",summary)
            else->{ if(store.applyCaseSummary(caseId,id,recordingId,c2["summary"].toString(),c2["cumulativeSummary"].toString(),"PIPELINE_AUTOMATIC")){applied++;summaryApplied=true} }
        }
        val changes=(c2["taskChanges"] as List<*>).map { PipelineContract.obj(it) }
        val suggested=PipelineContract.obj(c2["caseSuggestion"])["caseId"] as String?
        val taskScope=(c2["targetCaseId"] as String?) ?: suggested
        changes.forEachIndexed { i,t ->
            val index=i.toLong();val payload=LinkedHashMap(t)
            when(t["op"]) {
                "unchanged"->Unit
                "create"->if(forced!=null)hold(index,"CREATE",forced,payload) else { create(id,index,recordingId,caseId,t);applied++ }
                "update"->{
                    val taskId=t["taskId"].toString();val item=store.item(taskId)
                    val reason=forced ?: when {
                        !updatesAllowed->"CASE_CHANGED_BY_USER"
                        item==null->"TASK_MISSING"
                        item["case_id"]!=caseId->"TASK_MOVED"
                        item["status"]=="done"->"TASK_DONE"
                        t["status"]=="needs_confirmation" && item["status"]!="needs_confirmation"->"DONE_CLAIMED"
                        !store.aiOwned(item)->"MANUAL_TASK"
                        store.manualLock(caseId,item["recording_id"].toString())->"MANUAL_LOCK"
                        store.archived(item["recording_id"].toString())->"TASK_SOURCE_ARCHIVED"
                        !matchesSnapshot(item,c1Task(c1,taskScope,taskId))->"TASK_CHANGED_SINCE_TRANSCRIPT"
                        else->null
                    }
                    if(reason!=null)hold(index,"UPDATE",reason,payload) else { update(id,item!!,t);applied++ }
                }
            }
        }
        if(applied>0)store.bumpBoard(id)
        val state=when { review==0->"APPLIED";applied>0->"PARTIAL_REVIEW";else->"REVIEW" }
        val reason=forced ?: if(review==0)"APPLIED_UNDER_APP_RULES" else "SOME_CHANGES_REQUIRE_REVIEW"
        store.setAnalysisState(id,state,reason,caseId,summaryApplied);return Outcome(id,state,reason,applied,review)
    }
    private fun matchesSnapshot(item:Map<String,Any?>,snapshot:Map<String,Any?>?):Boolean {
        if(snapshot==null)return false
        val due=item["due_text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return snapshot["title"]==item["title"].toString().trim() && snapshot["due"]==due && snapshot["status"]==item["status"]
    }
    private fun create(analysisId:String,index:Long,recordingId:String,caseId:String,t:Map<String,Any?>) {
        val item="pl_item_"+PipelineContract.sha(PipelineContract.canonical(listOf("pipeline-item-v1",analysisId,index.toString()))).take(40)
        if(store.item(item)!=null)return
        store.execute("INSERT INTO workflow_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",item,recordingId,caseId,"TODO",t["title"].toString().trim(),"",
            (t["due"] as String?).orEmpty(),t["status"].toString(),1,null,t["quote"].toString(),store.clock())
        store.execute("INSERT INTO pipeline_item_bindings VALUES(?,?,?,?,?,?)",item,1,analysisId,t["assignee"].toString(),store.clock(),store.clock())
    }
    private fun update(analysisId:String,old:Map<String,Any?>,t:Map<String,Any?>) {
        val item=old["id"].toString();val next=PipelineApplyStore.n(old,"revision")+1
        val before=JSONObject().apply { old.forEach { (k,v)->put(k,v ?: JSONObject.NULL) } }
        store.binding(item)?.let { before.put("assignee",it["assignee"]) }
        store.execute("INSERT INTO pipeline_item_history VALUES(?,?,?,?,?,?)","pl_hist_"+PipelineContract.sha(PipelineContract.canonical(listOf(item,next.toString(),analysisId))).take(40),
            item,next,before.toString(),analysisId,store.clock())
        store.execute("UPDATE workflow_items SET title=?,due_text=?,status=?,revision=? WHERE id=?",t["title"].toString().trim(),(t["due"] as String?).orEmpty(),t["status"].toString(),next,item)
        store.execute("UPDATE pipeline_item_bindings SET last_revision=?,analysis_id=?,assignee=?,updated_ms=? WHERE item_id=?",next,analysisId,t["assignee"].toString(),store.clock(),item)
    }
}
