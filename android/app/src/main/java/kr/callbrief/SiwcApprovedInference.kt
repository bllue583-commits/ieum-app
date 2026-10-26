package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground, two separate explicit usage approvals. Login alone cannot authorize either
 * invocation. The app's current build exposes no live inference button before grant approval. */
internal class SiwcApprovedInference(context:Context,private val sessions:SiwcSessionStore,private val ownerScope:String,
    private val native:SiwcNativeReviewStore,private val ticket:SiwcNativeReviewStore.Ticket):AutoCloseable {
    private val stopped=AtomicBoolean(false)
    private val authScope=sessions.runtimeScope(ownerScope)
    private val approved=mutableSetOf<String>()
    private fun current()=!stopped.get() && native.isCurrent(ticket) && runCatching{sessions.runtimeScope(ownerScope)==authScope}.getOrDefault(false)
    private val port=SiwcAstraHttp(grant={if(current())sessions.inferenceGrant(ownerScope,synchronized(approved){approved.toSet()}) else null})
    private val jobs=SiwcInferenceJobs(context,authScope,port,::current)
    private val files=SiwcResultFiles(context,authScope,jobs,::current)
    val analysis=SiwcNativeRoundTrip.analysis(ticket,authScope)
    private var contextRequest:SiwcAstraProtocol.Invocation?=null
    fun analyse(displayedKey:String,transferAndUsageConfirmed:Boolean):SiwcInferenceJobs.Outcome {
        check(transferAndUsageConfirmed && displayedKey==analysis.key() && current()) { "SIWC_EXACT_ANALYSIS_USAGE_APPROVAL_REQUIRED" }
        synchronized(approved){approved.add(displayedKey)}
        return jobs.run(analysis,SiwcNativeRoundTrip.validateAnalysis(ticket))
    }
    /** Caller displays this request's distinct model/effort/input digest/usage before approving. */
    fun prepareContext():SiwcAstraProtocol.Invocation {
        check(current()) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        return SiwcNativeRoundTrip.context(ticket,jobs,analysis).also{contextRequest=it}
    }
    fun reconcile(displayedKey:String,transferAndUsageConfirmed:Boolean):SiwcInferenceJobs.Outcome {
        val c=checkNotNull(contextRequest) { "SIWC_CONTEXT_NOT_PREPARED" }
        check(transferAndUsageConfirmed && displayedKey==c.key() && current()) { "SIWC_EXACT_CONTEXT_USAGE_APPROVAL_REQUIRED" }
        synchronized(approved){approved.add(displayedKey)}
        return jobs.run(c,SiwcNativeRoundTrip.validateContext(ticket,jobs,analysis))
    }
    fun receiveCandidate():org.json.JSONObject {
        check(current()) { "SIWC_NATIVE_CURRENT_REQUIRED" };val c=checkNotNull(contextRequest)
        files.publish(StrictJson.bytes(ticket.contract.pack),analysis,c,SiwcNativeRoundTrip.validateAnalysis(ticket),SiwcNativeRoundTrip.validateContext(ticket,jobs,analysis))
        return native.receive(ticket,files,analysis,c)
    }
    fun cancel(){stopped.set(true);jobs.cancel(analysis.key());contextRequest?.let{jobs.cancel(it.key())};synchronized(approved){approved.clear()}}
    override fun close(){stopped.set(true);synchronized(approved){approved.clear()};files.close();jobs.close()}
}
