package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** One explicit foreground journey; no scheduler, automatic new generation, lease
 * extension, audio intake, browser credential extraction or business application. */
internal class SyntheticTextForegroundCoordinator(private val context:Context,private val port:SyntheticTextGenerationPort,
    private val clock:()->Long=System::currentTimeMillis,private val factory:(SyntheticTextConnectionScope)->SyntheticTextProofStore={SyntheticTextProofStore(context,it,port::binding)}) {
    data class Result(val state:String,val view:SyntheticTextProofStore.View?,val nativeConnected:Boolean=false,val canonicalWriteAllowed:Boolean=false)
    private val running=Mutex()
    suspend fun load():Result=withContext(Dispatchers.IO) {
        val scope=port.binding() ?: return@withContext Result("NATIVE_RETURN_UNSUPPORTED",null)
        try {
            factory(scope).use { store ->val v=store.current();Result(if(v?.start!=null&&clock()>=v.start.expiresAt)"EXPIRED" else v?.state ?: "IDLE",v) }
        } catch(e:SyntheticTextFailure) {
            if(e.reason!="storage_integrity_error")throw e
            // Hide unverified cached data without rewriting, deleting or reopening
            // it under fresh keys. Existing key/storage failures still propagate.
            Result("STORE_REVIEW",null)
        }
    }
    suspend fun startExplicit():Result=run(null,true,true)
    suspend fun resumeExplicit(proof:String):Result=run(proof,false,true)
    suspend fun readOnly(proof:String):Result=run(proof,false,false)
    private fun failure(reason:String)=when(reason) {
        "missing_identity","access_revoked"->"AUTH_HELD";"scope_changed"->"SCOPE_HELD";"job_expired"->"EXPIRED"
        "result_conflict","revision_conflict"->"CONFLICT";"unknown_tool","unsupported_protocol","native_return_unsupported"->"NATIVE_RETURN_UNSUPPORTED"
        "storage_integrity_error"->"STORE_REVIEW";"invalid_arguments","invalid_response","invalid_result","protocol_header_mismatch"->"RESPONSE_REVIEW";else->"UNKNOWN"
    }
    private suspend fun run(proof:String?,new:Boolean,allowWrite:Boolean):Result {
        if(!running.tryLock())return Result("BUSY",null)
        try { return withContext(Dispatchers.IO) {
            val scope=port.binding() ?: return@withContext Result("NATIVE_RETURN_UNSUPPORTED",null)
            val operation=currentCoroutineContext()
            fun gate() { operation.ensureActive();if(port.binding()!=scope)throw SyntheticTextFailure("scope_changed") }
            factory(scope).use { store ->
                gate();val prepared=try { if(new)store.startAndClaim() else { val old=store.get(requireNotNull(proof));old to store.claim(old.proof) } }
                catch(e:SyntheticTextFailure) { return@withContext Result(if(e.reason=="already_running")"BUSY" else failure(e.reason),null) }
                var v=prepared.first;val claim=prepared.second
                try {
                    var start=v.start
                    if(start==null) {
                        if(!allowWrite) { store.finish(claim,"START_CONFIRMATION_REQUIRED");return@withContext Result("START_CONFIRMATION_REQUIRED",store.get(v.proof)) }
                        store.state(claim,"STARTING");gate()
                        val reply=port.call("start_demo_generation",mapOf("sample_id" to SyntheticTextContract.SAMPLE,"proof_request_id" to v.proof));gate()
                        start=SyntheticTextContract.start(reply,v.proof);v=store.started(claim,start,reply)
                    }
                    val receipt=requireNotNull(start)
                    if(clock()>=receipt.expiresAt)throw SyntheticTextFailure("job_expired")
                    val tuple=mapOf("job_id" to SyntheticTextContract.JOB,"generation_id" to receipt.generation)
                    gate();val job=port.call("get_demo_job",tuple);gate();SyntheticTextContract.getJob(job,receipt)
                    val validated=SyntheticTextContract.validateInput(SyntheticTextContract.draft());val hash=SyntheticTextContract.fingerprint(validated);check(hash==SyntheticTextContract.GOLDEN)
                    val id=GenerationResultId.make(receipt.generation,v.suffix);v=store.expected(claim,id,hash)
                    suspend fun read():Map<String,Any?>? {
                        gate();val reply=port.call("get_demo_result",tuple);gate();if(clock()>=receipt.expiresAt)throw SyntheticTextFailure("job_expired")
                        val result=SyntheticTextContract.result(reply,receipt,id,hash)
                        if(result!=null) { v=store.received(claim,reply,result) } else store.receipt(claim,"QUEUED_READBACK",reply)
                        return result
                    }
                    if(read()!=null) { store.finish(claim);return@withContext Result("RESULT_RECEIVED",v) }
                    if(!allowWrite) { store.finish(claim,"WAITING");return@withContext Result("WAITING",store.get(v.proof)) }
                    gate();if(clock()>=receipt.expiresAt)throw SyntheticTextFailure("job_expired");store.state(claim,"SAVING")
                    val ack=port.call("save_demo_result",tuple+mapOf("expected_revision" to 0L,"result_id" to id,"result" to SyntheticTextContract.draft()));gate()
                    SyntheticTextContract.saveAck(ack,receipt);store.receipt(claim,"SAVE_ACK",ack);store.state(claim,"READBACK_REQUIRED")
                    val got=read();store.finish(claim,if(got==null)"WAITING" else null);Result(if(got==null)"WAITING" else "RESULT_RECEIVED",store.get(v.proof))
                } catch(cancelled:CancellationException) { withContext(NonCancellable){store.finish(claim,"UNKNOWN")};throw cancelled }
                catch(e:Exception) {
                    val state=when(e) { is SyntheticTextFailure->failure(e.reason);is java.io.IOException->"UNKNOWN";else->"RESPONSE_REVIEW" }
                    store.finish(claim,state)
                    Result(state,if(port.binding()==scope)runCatching { store.get(v.proof) }.getOrNull() else null)
                }
            }
        } } finally { running.unlock() }
    }
}
