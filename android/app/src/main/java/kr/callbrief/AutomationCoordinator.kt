package kr.callbrief

import org.json.JSONArray
import org.json.JSONObject
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson

/** Android owns admission, durable dispatch and consumption. The provider owns execution only.
 * No callback, share intent or Drive publication is an execution/acceptance receipt. */
internal interface AutomationProvider {
    fun gate(): AutomationGate
    /** Metadata-only source admission before native request preparation/publication. */
    fun gate(key:String):AutomationGate=gate()
    fun dispatch(key:String, frozenHandoff:String): AutomationDispatch
    fun reconcile(key:String,frozenHandoff:String): AutomationDispatch
    fun receive(key:String, receipt:String, frozenHandoff:String): AutomationObservation
}
internal data class AutomationGate(val state:String,val reason:String) {
    init { require(state in setOf("READY","UNCONFIGURED","UNAPPROVED","FAILED"));require(reason.matches(Regex("[A-Z0-9_]{1,100}"))) }
}
internal data class AutomationDispatch(val state:String,val receipt:String="",val reason:String) {
    init { require(state in setOf("ACCEPTED","WAITING","UNKNOWN","FAILED"));require(state!="ACCEPTED" || receipt.isNotBlank()) }
}
internal sealed class AutomationObservation {
    data class Waiting(val reason:String):AutomationObservation()
    data class Failed(val reason:String):AutomationObservation()
    /** Assurance is established by the adapter's verified receipt, never by model-authored JSON. */
    data class Result(val result:AutomationAnalysis,val assurance:AutomationAssurance):AutomationObservation()
}
internal data class AutomationAssurance(val origin:String,val verifiedSource:Boolean=false,
    val executionVerified:Boolean=false,val semanticReviewVerified:Boolean=false,val privacyReviewed:Boolean=false,
    val automaticUseAllowed:Boolean=false,val evidenceRef:String="") {
    fun json()=JSONObject().put("origin",origin).put("verifiedSource",verifiedSource).put("executionVerified",executionVerified)
        .put("semanticReviewVerified",semanticReviewVerified).put("privacyReviewed",privacyReviewed).put("automaticUseAllowed",automaticUseAllowed).also{if(evidenceRef.isNotBlank())it.put("evidenceRef",evidenceRef)}
}
internal data class AutomationAnalysis(val revision:Long,val digest:String,val raw:String,val transcript:String,
    val summary:String,val cumulativeSummary:String,val tasks:JSONArray,val frozenRequest:String) {
    init {
        require(revision>0 && digest.matches(Regex("[0-9a-f]{64}")))
        require(raw.toByteArray().size<=524288 && transcript.isNotBlank() && transcript.length<=131072)
        require(summary.isNotBlank() && summary.length<=3000 && cumulativeSummary.isNotBlank() && cumulativeSummary.length<=5000)
        require(tasks.length()<=30)
        val cp=transcript.codePoints().toArray()
        for(i in 0 until tasks.length()) {
            val t=tasks.getJSONObject(i);val start=t.getInt("start");val end=t.getInt("end")
            require(start>=0 && end>start && end<=cp.size && String(cp,start,end-start)==t.getString("quote")) { "AUTOMATION_QUOTE_MISMATCH" }
            require(t.getString("title").isNotBlank() && t.getString("title").length<=200)
            require(t.getString("status") in setOf("open","in_progress","waiting","needs_confirmation","done"))
        }
    }
}
internal interface AutomationRequests:AutoCloseable {
    fun prepare(recording:String,snapshot:JSONObject):String
    fun publish(request:String):String
    fun handoff(request:String):String
    fun state(request:String):String
}
internal object UnconfiguredAutomationProvider:AutomationProvider {
    override fun gate()=AutomationGate("UNCONFIGURED","ANALYSIS_PROVIDER_NOT_CONFIGURED")
    override fun dispatch(key:String,frozenHandoff:String)=error("ANALYSIS_PROVIDER_NOT_CONFIGURED")
    override fun reconcile(key:String,frozenHandoff:String)=AutomationDispatch("UNKNOWN",reason="ANALYSIS_PROVIDER_NOT_CONFIGURED")
    override fun receive(key:String,receipt:String,frozenHandoff:String)=AutomationObservation.Waiting("ANALYSIS_PROVIDER_NOT_CONFIGURED")
}

/** One bounded pass, called after automatic native intake and by the periodic reconciler.
 * Claims are persisted before side effects. An interrupted dispatch is reconciled, never resent.
 * All result/application/outbox writes are one protected transaction in AndroidAutomationStore. */
internal class AndroidAutomationCoordinator(private val store:AndroidAutomationStore,
    private val requests:AutomationRequests,private val provider:AutomationProvider,
    private val cancelled:()->Boolean={false}) {
    fun cycle(limit:Int=4,receiveExistingOnly:Boolean=false):List<JSONObject> {
        require(limit in 1..16)
        if(!receiveExistingOnly)store.admitCurrentSources()
        for(initial in store.work(limit,receiveExistingOnly)) {
            if(cancelled())break
            val id=initial.getString("id")
            if(!store.claim(id))continue
            try { if(receiveExistingOnly)processExisting(id) else process(id) }
            catch(e:Exception) {
                val code=e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,100}"))}
                    ?: "AUTOMATION_${e.javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"),"_").take(50)}"
                if(store.ownsClaim(id))store.hold(id,if(store.job(id).getString("state")=="DISPATCHING")"UNKNOWN" else "FAILED",code)
            }
            finally { store.release(id) }
        }
        store.refreshReportQueue()
        return store.jobs()
    }
    private fun providerHeld(id:String,gate:AutomationGate) {
        val j=store.job(id)
        val uncertain=j.optString("receipt").isBlank() && j.getString("state") in setOf("DISPATCHING","UNKNOWN")
        store.hold(id,if(uncertain)"UNKNOWN" else when(gate.state){"UNCONFIGURED"->"WAITING_PROVIDER";"UNAPPROVED"->"WAITING_APPROVAL";else->"FAILED"},gate.reason)
    }
    private fun permitted(id:String,checkProvider:Boolean=true):Boolean {
        if(!store.ownsClaim(id))return false
        if(cancelled()) {
            val state=store.job(id).getString("state")
            store.hold(id,if(state=="DISPATCHING")"UNKNOWN" else "WAITING_LOCAL_RESUME","LOCAL_WORK_STOPPED_RECONCILE_OR_RESUME")
            return false
        }
        if(!store.allowed(id))return false
        if(checkProvider) {
            val gate=provider.gate(id);if(gate.state!="READY"){providerHeld(id,gate);return false}
        }
        return true
    }
    /** This path can only reconcile/receive an already frozen request. No intake, prepare,
     * publish or dispatch, including when the original acceptance outcome was unknown. */
    private fun processExisting(id:String) {
        val j=store.job(id)
        if(!AutomationPendingResults.known(j) || !permitted(id))return
        if(!store.resultWindowOpen(id)){if(store.job(id).getLong("result_revision")==0L)store.hold(id,"FAILED","AUTOMATION_RESULT_WINDOW_EXPIRED_OR_INVALID");return}
        if(j.getString("receipt").isBlank()) {
            val reconciled=provider.reconcile(id,j.getString("handoff"))
            if(!permitted(id))return
            store.dispatchReceipt(id,reconciled)
            if(reconciled.state!="ACCEPTED")return
        }
        receiveExisting(id)
    }
    private fun receiveExisting(id:String) {
        val j=store.job(id)
        if(!permitted(id))return
        if(!store.resultWindowOpen(id)){if(store.job(id).getLong("result_revision")==0L)store.hold(id,"FAILED","AUTOMATION_RESULT_WINDOW_EXPIRED_OR_INVALID");return}
        when(val observation=provider.receive(id,j.getString("receipt"),j.getString("handoff"))) {
            is AutomationObservation.Waiting -> store.hold(id,"WAITING_RESULT",observation.reason)
            is AutomationObservation.Failed -> store.hold(id,"FAILED",observation.reason)
            is AutomationObservation.Result -> if(permitted(id))store.accept(id,observation.result,observation.assurance)
        }
    }
    private fun process(id:String) {
        check(store.ownsClaim(id)) {"AUTOMATION_CLAIM_EXPIRED"}
        if(!permitted(id,false))return
        val gate=provider.gate(id)
        if(gate.state!="READY") { providerHeld(id,gate);return }
        var j=store.job(id)
        if(AutomationPendingResults.known(j) && !store.resultWindowOpen(id)){if(j.getLong("result_revision")==0L)store.hold(id,"FAILED","AUTOMATION_RESULT_WINDOW_EXPIRED_OR_INVALID");return}
        if(j.getString("state") in setOf("DISPATCHING","UNKNOWN")) {
            val reconciled=provider.reconcile(id,j.getString("handoff"))
            if(!permitted(id))return
            store.dispatchReceipt(id,reconciled)
            if(reconciled.state!="ACCEPTED")return
        }
        j=store.job(id)
        var request=j.optString("request_id")
        if(request.isBlank())store.refreshBeforePrepare(id)
        if(!store.contextReady(id)){store.hold(id,"WAITING_CONTEXT","EXISTING_CASE_AND_ORIGINAL_TIME_REQUIRED");return}
        if(request.isBlank()) {
            request=requests.prepare(j.getString("recording_id"),store.sourceSnapshot(id))
            store.bindRequest(id,request)
        }
        j=store.job(id)
        if(j.optString("receipt").isBlank()) {
            // prepare/publish use existing immutable native binding, source lease and UNKNOWN rules.
            if(!permitted(id))return
            val state=requests.publish(request)
            if(state !in setOf("SENT","PENDING","REVIEW")) {
                store.hold(id,if(state=="UNKNOWN")"UNKNOWN_NATIVE" else "WAITING_NATIVE", "NATIVE_$state");return
            }
            if(!permitted(id))return
            val handoff=requests.handoff(request)
            store.freezeHandoff(id,handoff)
            if(!permitted(id))return
            store.frozenBusinessContext(id)
            store.hold(id,"DISPATCHING","PROVIDER_ACCEPTANCE_PENDING")
            val dispatch=provider.dispatch(id,handoff)
            if(!permitted(id))return
            store.dispatchReceipt(id,dispatch)
            if(dispatch.state!="ACCEPTED")return
        }
        receiveExisting(id)
    }
}

/** Validation stays owned by the external processing module; this only maps its typed output. */
internal object AutomationApiV2Consumer {
    fun read(raw:ByteArray,handoff:String,businessContextSha:String):AutomationObservation.Result {
        val frozen=JSONObject(handoff).getString("frozenRequestJson")
        val decoded=kr.callagent.drive.AutomationV2Codec().validate(raw,frozen.toByteArray())
        check(decoded.businessContextSha==businessContextSha) {"AUTOMATION_CONTEXT_BINDING_MISMATCH"}
        val analysis=JSONObject(decoded.analysisJson)
        return AutomationObservation.Result(AutomationAnalysis(decoded.revision,decoded.digest,String(raw,Charsets.UTF_8),decoded.rawText,
            analysis.getString("summary"),analysis.getString("cumulativeSummary"),analysis.getJSONArray("tasks"),frozen),
            AutomationAssurance("OPENAI_API_V2_READ_ONLY_NOT_INDEPENDENTLY_ATTESTED"))
    }
}
