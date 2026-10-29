package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.RecordingWorkflowStore

/** Explicit user confirmation of the case for an analysis that arrived without a linked case.
 * Uses the existing confirmWork user command, then applies per the rules: summary and creates
 * to the chosen case; updates only when the user picked the case the analysis suggested. */
internal class PipelineCaseConfirmation(private val context:Context,private val store:PipelineApplyStore,private val applier:PipelineApplier,
    private val index:PipelineTranscriptIndex?,private val workflow:()->RecordingWorkflowStore={RecordingWorkflowStore(context)}) {
    fun confirm(analysisId:String,chosenCaseId:String?,newCaseLabel:String?):PipelineApplier.Outcome {
        require((chosenCaseId==null)!=(newCaseLabel.isNullOrBlank())) { "ONE_CASE_CHOICE_REQUIRED" }
        return workflow().use { w ->
            store.tx {
                val row=checkNotNull(store.analysis(analysisId)) { "ANALYSIS_UNKNOWN" }
                check(row["state"]=="AWAITING_CASE") { "ANALYSIS_NOT_AWAITING_CASE" }
                val c2=store.payload(row);val recording=row["recording_id"].toString()
                // Freshness is judged before the user's own confirm command bumps the board.
                val fresh=store.boardFresh(PipelineApplyStore.n(row,"base_board_revision"))
                val case=w.confirmWork(recording,w.snapshot(recording),"","",newCaseLabel.orEmpty(),"",chosenCaseId)
                val suggested=PipelineContract.obj(c2["caseSuggestion"])["caseId"] as String?
                applier.applyConfirmed(analysisId,case,chosenCaseId!=null && chosenCaseId==suggested,fresh,index?.transcript(row["transcript_id"].toString()))
            }
        }
    }
    /** The user declines to link this analysis; nothing is applied and the history is kept. */
    fun decline(analysisId:String)=store.tx {
        val row=checkNotNull(store.analysis(analysisId)) { "ANALYSIS_UNKNOWN" }
        check(row["state"]=="AWAITING_CASE") { "ANALYSIS_NOT_AWAITING_CASE" }
        store.setAnalysisState(analysisId,"REJECTED","USER_DECLINED_CASE")
    }
}
