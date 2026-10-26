package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import org.json.JSONObject

/** Install only after a supported transport/auth/audio route is approved. Uses existing jobs,
 * frozen native handoffs and Coordinator -> Store.accept; never calls a user-review command. */
internal class AstraAudioAutomationProvider(
    private val queue:AstraAudioRoundTrip,
    private val input:(String,String)->AstraAudioInput
):AutomationProvider {
    override fun gate()=queue.gate()
    override fun gate(key:String):AutomationGate {
        val id=queue.find(key)
        return if(id!=null && queue.state(id) in setOf("RESULT_READY","PUBLISHING"))
            AutomationGate("READY","ASTRA_LOCAL_RESULT_REVALIDATION_REQUIRED") else gate()
    }
    private fun dispatch(id:String):AutomationDispatch {
        val r=queue.journal.load(id)
        return when {
            r.state in setOf("CANCELLED","CANCEL_REQUESTED","REJECTED") -> AutomationDispatch("FAILED",reason="ASTRA_JOB_HELD")
            r.receipt.isNotBlank() -> AutomationDispatch("ACCEPTED",r.receipt,"ASTRA_TRANSPORT_RECEIPT")
            r.state in setOf("UNKNOWN","DISPATCHING") -> AutomationDispatch("UNKNOWN",reason="ASTRA_DELIVERY_UNCERTAIN")
            else -> AutomationDispatch("WAITING",reason="ASTRA_RUNTIME_OR_TRANSFER_NOT_READY")
        }
    }
    override fun dispatch(key:String,frozenHandoff:String):AutomationDispatch {
        val id=queue.prepare(listOf(input(key,frozenHandoff)));queue.cycle(id);return dispatch(id)
    }
    override fun reconcile(key:String,frozenHandoff:String):AutomationDispatch {
        val id=queue.find(key) ?: return AutomationDispatch("UNKNOWN",reason="ASTRA_JOB_NOT_PREPARED")
        check(queue.journal.load(id).inputs.single{it.job==key}.handoff==frozenHandoff) { "ASTRA_HANDOFF_CHANGED" }
        queue.cycle(id);return dispatch(id)
    }
    override fun receive(key:String,receipt:String,frozenHandoff:String):AutomationObservation {
        val id=queue.find(key) ?: return AutomationObservation.Waiting("ASTRA_JOB_NOT_PREPARED")
        check(queue.journal.load(id).receipt==receipt) { "ASTRA_RECEIPT_CHANGED" }
        queue.cycle(id);return queue.observation(key,frozenHandoff)
    }
}

/** Concrete app binding; the existing SAF source uses its selected-folder persisted READ grant.
 * Existing onboarding owns requesting that grant. This adapter requests no new permission. */
internal class AstraAudioSourceBinding(private val context:Context,private val store:AndroidAutomationStore,
    private val source:SelectedRecordingSource,private val sessionCurrent:()->Boolean) {
    private fun revision(s:JSONObject):String=AstraAudioContract.sha(StrictJson.bytes(
        listOf("user_revision","lock_revision","head_revision","task_revision","visibility_revision","board_revision","link_revision")
            .associateWith{s.optLong(it)}))
    fun input(key:String,handoff:String):AstraAudioInput {
        check(sessionCurrent() && store.allowed(key) && store.resultWindowOpen(key)) { "ASTRA_SOURCE_OR_REVISION_CHANGED" }
        val job=store.job(key);check(job.getString("handoff")==handoff) { "ASTRA_HANDOFF_CHANGED" }
        val id=job.getString("recording_id");val snapshot=RecordingWorkflowStore(context).use{it.snapshot(id)}
        val frozen=JSONObject(JSONObject(handoff).getString("frozenRequestJson"))
        check(frozen.getString("sourceId")==id && frozen.getLong("sourceVersion")==snapshot.getLong("source_version") && frozen.getString("audioSha")==snapshot.getString("audio_sha256")) { "ASTRA_NATIVE_SOURCE_BINDING" }
        val saved=store.sourceSnapshot(key)
        val inventory=source.inventory();check(inventory.complete) { "ASTRA_INCOMPLETE_SOURCE_LIST" }
        val document=inventory.documents.single{it.documentId==saved.getString("actual_document_id") && it.uri==saved.getString("actual_uri")}
        return AstraAudioInput(key,id,snapshot.getLong("source_version"),snapshot.getString("audio_sha256"),revision(snapshot),handoff,document)
    }
    fun current(input:AstraAudioInput):Boolean=runCatching {
        sessionCurrent() && store.allowed(input.job) && store.resultWindowOpen(input.job) && store.job(input.job).getString("handoff")==input.handoff &&
            RecordingWorkflowStore(context).use{it.snapshot(input.sourceId)}.let{s->s.getLong("source_version")==input.sourceVersion && s.getString("audio_sha256")==input.audioSha && revision(s)==input.revisionSha}
    }.getOrDefault(false)
}
