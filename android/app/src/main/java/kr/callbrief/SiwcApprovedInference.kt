package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground, two separate explicit usage approvals. Login alone cannot authorize either
 * invocation. The app's current build exposes no live inference button before grant approval. */
internal class SiwcApprovedInference(context:Context,private val sessions:SiwcSessionStore,private val ownerScope:String,
    private val native:SiwcNativeReviewStore,private val ticket:SiwcNativeReviewStore.Ticket,
    private val budget:SiwcTrialBudget?=null,inferencePort:SiwcInferencePort?=null):AutoCloseable {
    private val stopped=AtomicBoolean(false)
    private val authScope=sessions.runtimeScope(ownerScope)
    private val approved=mutableSetOf<String>()
    private fun current()=!stopped.get() && native.isCurrent(ticket) && runCatching{sessions.runtimeScope(ownerScope)==authScope}.getOrDefault(false)
    private val http=SiwcAstraHttp(grant={if(current())sessions.inferenceGrant(ownerScope,synchronized(approved){approved.toSet()}) else null},observe={budget?.event(it)})
    private val transport=inferencePort ?: http
    private val port=budget?.port(transport) ?: transport
    init {budget?.bind(authScope,AstraAudioContract.sha(StrictJson.bytes(ticket.contract.pack)),ticket.contract.snapshotSha)}
    private val jobs=SiwcInferenceJobs(context,authScope,port,::current)
    private val files=SiwcResultFiles(context,authScope,jobs,::current)
    val analysis=SiwcNativeRoundTrip.analysis(ticket,authScope)
    fun catalog():List<String>{check(current());return port.models(authScope)}
    fun observedAnalysis()=jobs.observed(analysis)
    fun observedContext()=contextRequest?.let(jobs::observed)
    private var contextRequest:SiwcAstraProtocol.Invocation?=null
    /** Read durable stages without catalog/POST. Preparing a context key is local only. */
    fun progress():Map<String,Any?> {
        check(current()) { "SIWC_NATIVE_CURRENT_REQUIRED" }
        val a=jobs.state(analysis.key())
        val completed=if(a=="COMPLETE")jobs.completed(analysis,SiwcNativeRoundTrip.validateAnalysis(ticket)) else null
        val c=if(completed!=null)prepareContext() else null
        val cs=c?.let{jobs.state(it.key())} ?: "NOT_PREPARED"
        val contextCompleted=if(c!=null && cs=="COMPLETE")jobs.completed(c,SiwcNativeRoundTrip.validateContext(ticket,jobs,analysis)) else null
        fun observation(x:SiwcAstraProtocol.Completion?)=x?.let{mapOf("responseId" to it.responseId,"actualModel" to it.actualModel,"effectiveEffort" to it.effectiveEffort,"output" to it.output,"streamSha256" to it.rawSha256)}
        return mapOf("analysisState" to a,"contextState" to cs,"analysis" to observation(completed),"context" to observation(contextCompleted),"contextKey" to c?.key())
    }
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
    fun receiveCandidate(fixture:Boolean=false):org.json.JSONObject {
        check(current()) { "SIWC_NATIVE_CURRENT_REQUIRED" };val c=checkNotNull(contextRequest)
        files.publish(StrictJson.bytes(ticket.contract.pack),analysis,c,SiwcNativeRoundTrip.validateAnalysis(ticket),SiwcNativeRoundTrip.validateContext(ticket,jobs,analysis))
        return native.receive(ticket,files,analysis,c,fixture)
    }
    /** Safe on the UI thread while HTTP is blocked. Durable cancellation stays on I/O. */
    fun requestStop(){stopped.set(true);synchronized(approved){approved.clear()}}
    fun cancel(){requestStop();if(jobs.state(analysis.key()) in setOf("PREPARED","RUNNING"))jobs.cancel(analysis.key());contextRequest?.let{if(jobs.state(it.key()) in setOf("PREPARED","RUNNING"))jobs.cancel(it.key())}}
    override fun close(){requestStop();files.close();jobs.close()}
}
