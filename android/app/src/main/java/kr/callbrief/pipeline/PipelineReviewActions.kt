package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.RecordingWorkflowStore

/** Accept / dismiss of review items. Accepting performs the change only through the existing
 * explicit user commands (createItem / editItem); completion needs a separate explicit flag. */
internal class PipelineReviewActions(private val context:Context,private val store:PipelineApplyStore,
    private val workflow:()->RecordingWorkflowStore={RecordingWorkflowStore(context)}) {
    data class Item(val id:String,val analysisId:String,val kind:String,val reason:String,val change:Map<String,Any?>,val recordingId:String)
    fun open():List<Item> = store.openReviews().map { r ->
        Item(r["id"].toString(),r["analysis_id"].toString(),r["kind"].toString(),r["reason"].toString(),
            PipelineContract.parse(r["payload"].toString().toByteArray(Charsets.UTF_8)),r["recording_id"].toString())
    }
    /** Case suggestions: analyses waiting for the user to confirm a case. */
    fun awaitingCase():List<Map<String,Any?>> = store.rows("SELECT * FROM pipeline_analyses WHERE state='AWAITING_CASE' ORDER BY created_ms,analysis_id").map { row ->
        val c2=store.payload(row)
        mapOf("analysisId" to row["analysis_id"],"recordingId" to row["recording_id"],"summary" to c2["summary"],
            "suggestion" to PipelineContract.obj(c2["caseSuggestion"]),"changes" to (c2["taskChanges"] as List<*>).size)
    }
    fun dismiss(id:String)=store.tx { checkOpen(id);store.resolveReview(id,"DISMISSED") }
    private fun checkOpen(id:String)=checkNotNull(store.review(id)?.takeIf { it["state"]=="OPEN" }) { "REVIEW_NOT_OPEN" }
    /** [confirmCompletion] must be true to accept a model's completion claim as done. */
    fun accept(id:String,confirmCompletion:Boolean=false):String = workflow().use { w -> store.tx {
        val r=checkOpen(id);val change=PipelineContract.parse(r["payload"].toString().toByteArray(Charsets.UTF_8))
        val analysis=checkNotNull(store.analysis(r["analysis_id"].toString()))
        val recording=analysis["recording_id"].toString()
        check(!store.archived(recording)) { "RECORDING_ARCHIVED" }
        val result=when(r["kind"]) {
            "SUMMARY"->{
                val case=change["caseId"].toString()
                store.applyCaseSummary(case,"user:"+r["id"],recording,change["summary"].toString(),change["cumulativeSummary"].toString(),"USER_ACCEPTED_PIPELINE_PROPOSAL")
                store.bumpBoard(r["analysis_id"].toString());"SUMMARY_ACCEPTED"
            }
            "CREATE"->{
                val status=change["status"].toString()
                w.createItem(recording,w.snapshot(recording),"TODO",change["title"].toString(),"",(change["due"] as String?).orEmpty(),quote=change["quote"].toString(),initialStatus=status)
            }
            "UPDATE"->{
                val item=checkNotNull(store.item(change["taskId"].toString())) { "TASK_MISSING" }
                check(item["status"]!="done") { "TASK_DONE" }
                val status=if(r["reason"]=="DONE_CLAIMED") { check(confirmCompletion) { "EXPLICIT_COMPLETION_REQUIRED" };"done" } else change["status"].toString()
                w.editItem(item["id"].toString(),PipelineApplyStore.n(item,"revision"),change["title"].toString(),status,(change["due"] as String?).orEmpty(),item["edition"]?.toString().orEmpty())
                "UPDATE_ACCEPTED"
            }
            else->error("REVIEW_KIND_UNKNOWN")
        }
        store.resolveReview(id,"ACCEPTED");result
    } }
}
