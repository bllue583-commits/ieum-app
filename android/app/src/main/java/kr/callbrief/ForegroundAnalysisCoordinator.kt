package kr.callbrief

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bounded foreground execution over the existing durable request/binding/receipt stores.
 * The caller must already have admitted the selected source. No discovery enrollment,
 * scheduler, permission request, retry/resume or model API is added here.
 * The default native handoff remains NONE. Receipt/offer success is never execution proof.
 * Production UI activation is a separate design/operating gate; this adapter is not scheduled.
 */
internal class ForegroundAnalysisCoordinator(private val flow:AnalysisRequestFlow) {
    data class Result(val requestId:String,val state:String,val stage:String,val resultPolls:Int,
        val providerExecutionProved:Boolean=false,val canonicalWriteAllowed:Boolean=false)
    private val running=Mutex()
    suspend fun runApprovedRecording(recording:String,expected:JSONObject,maxResultPolls:Int=8,pollDelayMs:Long=500):Result =
        run(maxResultPolls,pollDelayMs) { flow.prepare(recording,JSONObject(expected.toString())) }
    suspend fun continuePrepared(requestId:String,maxResultPolls:Int=8,pollDelayMs:Long=500):Result =
        run(maxResultPolls,pollDelayMs) { requestId }
    private suspend fun run(maxResultPolls:Int,pollDelayMs:Long,request:()->String):Result {
        require(maxResultPolls in 1..16 && pollDelayMs in 1..10_000) { "BOUNDED_FOREGROUND_REQUIRED" }
        check(running.tryLock()) { "FOREGROUND_ALREADY_RUNNING" }
        try { return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val id=request()
            fun result(stage:String,polls:Int=0)=Result(id,flow.requestState(id),stage,polls)
            var state=flow.requestState(id)
            // Interrupted or uncertain delivery requires the existing explicit reconciliation.
            // HELD states also require their original source/account confirmation, not auto-send.
            if(state=="PREPARED") { state=flow.submit(id);currentCoroutineContext().ensureActive() }
            if(state !in listOf("SENT","PENDING"))return@withContext result("REQUEST_$state")
            val sync=flow.sync(id)
            currentCoroutineContext().ensureActive()
            state=flow.requestState(id)
            if(state in listOf("REVIEW","ERROR","CANCELLED"))return@withContext result("RESULT_$state")
            // Only an existing durable OFFERED handoff permits bounded readback.
            // NONE, uncertain acknowledgement, source/capability hold and unknown sync outcomes stop.
            if(sync!="AWAITING_DOT_OPERATOR")return@withContext result(sync)
            repeat(maxResultPolls) { index->
                delay(pollDelayMs);currentCoroutineContext().ensureActive()
                state=flow.poll(id);currentCoroutineContext().ensureActive()
                if(state !in listOf("SENT","PENDING"))return@withContext result("RESULT_$state",index+1)
            }
            result("BOUNDED_RESULT_WAIT",maxResultPolls)
        } } catch(cancelled:CancellationException) {
            flow.close();throw cancelled
        } finally { running.unlock() }
    }
}
