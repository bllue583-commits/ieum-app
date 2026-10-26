package kr.callbrief

import android.content.Context
import androidx.work.*
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Caller-approved synthetic text only; producer pins never come from the payload itself. */
internal enum class ScheduledDotInputMode { FIXED_PUBLIC_TEXT, PREPARED_LOCAL_STT_SYNTHETIC }
internal data class ScheduledDotProofPlan(val enabled:Boolean=false,val proofRequestId:String="",val queueFolder:String="",
    val inputMode:ScheduledDotInputMode=ScheduledDotInputMode.FIXED_PUBLIC_TEXT,val requestSha:String="",val evidenceSha:String="")
internal interface ScheduledDotProofPort {
    fun publish(key:String):DotScheduledTextExchangePort.Outcome
    fun poll(key:String):DotScheduledTextExchangePort.Outcome
    fun resumeExplicit(key:String):DotScheduledTextExchangePort.Outcome
}
internal class NativeScheduledDotProofPort(private val native:DotScheduledTextExchangePort):ScheduledDotProofPort {
    override fun publish(key:String)=native.publishFixedSynthetic(key)
    override fun poll(key:String)=native.poll(key)
    override fun resumeExplicit(key:String)=native.resumePublication(key)
}
/** Missing metadata version cannot establish the stable read required by this coordinator. */
internal class ScheduledProofVersionCheckedDrive(private val delegate:DriveTransport):DriveTransport by delegate {
    override fun metadata(id:String)=delegate.metadata(id).also{check(!it.version.isNullOrBlank() && it.version!="null"){"SCHEDULED_DOT_METADATA_VERSION_REQUIRED"}}
}
internal class ScheduledDotProofCoordinator(private val context:Context,private val session:GoogleConnectionSession,
    private val plan:ScheduledDotProofPlan,private val port:ScheduledDotProofPort,
    private val current:()->Boolean={true},private val clock:()->Instant=Instant::now,
    private val preparedInput:PreparedWhisperScheduledTextInput?=null,
    private val nativeIngress:((DotScheduledTextExchangePort.Registration,ScheduledTextProofCodec.Proposal)->JSONObject)?=null) {
    fun cycle()=run(false)
    /** Only an explicit original-publication reconciliation may invoke the owner's resume port. */
    fun resumeExplicit()=run(true)
    private fun result(state:String,detail:String="")=JSONObject().put("state",state).put("reason",detail)
        .put("nativeAudioRequestCompleted",false).put("sttExecuted",false).put("canonicalWriteAllowed",false)
        .put("kakaoSent",false).put("schedulerRegistered",false).put("schedulerTriggerIndependentlyVerified",false)
        .put("inputMode",plan.inputMode.name).put("upstreamSttExecutionReported",preparedInput?.upstreamExecutionReported ?: "NOT_AVAILABLE")
        .put("upstreamSttIndependentlyVerified",false).put("publisherSttExecuted",false)
        .put("transcriptionQuality",if(plan.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC)"RAW_REVIEW_REQUIRED" else "NOT_APPLICABLE")
    private fun run(resume:Boolean):JSONObject {
        if(!plan.enabled)return result("DOT_TEXT_EXCHANGE_NOT_CONFIGURED")
        if(!current())return result("LOCAL_WORK_STOPPED")
        val scope=DriveALocalScope.current(session) ?: return result("WAITING_NATIVE_GRANT","DOT_TEXT_CURRENT_NATIVE_GRANT_REQUIRED")
        if(!ProtectedDatabase.file(AccountStorageContext(context,scope.owner),GeneralLocalStore.DATABASE).exists())return result("WAITING_PROTECTED_STORE")
        return try {
            check(UUID.fromString(plan.proofRequestId).toString()==plan.proofRequestId && plan.queueFolder.matches(Regex("[A-Za-z0-9_-]{1,256}"))){"SCHEDULED_DOT_PLAN_REQUIRED"}
            if(plan.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC) {
                val input=preparedInput ?: return result("WAITING_PREPARED_STT_INPUT","NO_FIXED_TEXT_FALLBACK")
                check(plan.requestSha.matches(Regex("[0-9a-f]{64}")) && plan.evidenceSha.matches(Regex("[0-9a-f]{64}"))){"LOCAL_STT_PRODUCER_PINS_REQUIRED"}
                check(DriveRelay.sha256(input.forJob(plan.proofRequestId))==plan.requestSha && input.evidenceSha==plan.evidenceSha){"LOCAL_STT_PRODUCER_PIN_MISMATCH"}
            }
            ScheduledDotCoordinatorStore(context,scope,plan,clock,preparedInput).use { store ->
                store.pin()
                if(!store.claim())return@use result("LOCAL_PROOF_CHECK_ALREADY_RUNNING")
                try {
                    if(!current())return@use result("LOCAL_WORK_STOPPED")
                    val sent=if(resume)port.resumeExplicit(plan.proofRequestId) else port.publish(plan.proofRequestId)
                    store.capture(sent)
                    if(!current())return@use result("LOCAL_WORK_STOPPED",sent.state)
                    if(sent.state !in setOf("SENT_AWAITING_SCHEDULED_DOT","SENT","READ_ONLY_PROPOSAL_RECEIVED"))
                        return@use store.view(result(sent.state,"ORIGINAL_PUBLICATION_ONLY_NO_AUTO_RESUME"))
                    val received=port.poll(plan.proofRequestId)
                    store.capture(received)
                    val ingress=if(received.proposal!=null&&received.registration!=null)nativeIngress?.invoke(received.registration,received.proposal) else null
                    store.view(result(received.state,when(received.state){"DOT_TEXT_PENDING"->"WAITING_OFFICIAL_DOT_SCHEDULER_AND_RESULT";"READ_ONLY_SCHEDULED_TEXT_PROPOSAL"->"TEXT_PROOF_ONLY_NOT_AUDIO_OR_MODEL_ATTESTATION";else->received.state})).also{if(ingress!=null)it.put("nativeTextIngress",ingress)}
                } finally {store.release()}
            }
        } catch(e:Exception) {
            result("HELD",e.message?.takeIf{it.matches(Regex("[A-Za-z0-9_]{1,120}"))} ?: "SCHEDULED_DOT_CHECK_FAILED")
        }
    }
}

/** Coordinator metadata only. The owner's journal keeps exact files and append-only proof bytes. */
private class ScheduledDotCoordinatorStore(context:Context,private val scope:DriveALocalScope,
    private val plan:ScheduledDotProofPlan,private val clock:()->Instant,private val prepared:PreparedWhisperScheduledTextInput?):AutoCloseable {
    private val protectedStore=ProtectedDatabase.open(AccountStorageContext(context,scope.owner),GeneralLocalStore.DATABASE,null)
    private val db=protectedStore.database();private val claim=UUID.randomUUID().toString()
    private val stt=plan.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC
    private val table=if(stt)"android_local_stt_dot_coordinator" else "android_scheduled_dot_coordinator"
    init {ProtectedDatabase.transaction(db){
        db.execSQL("CREATE TABLE IF NOT EXISTS $table(scope TEXT PRIMARY KEY,proof_id TEXT NOT NULL,queue TEXT NOT NULL,state TEXT NOT NULL,binding TEXT NOT NULL DEFAULT '',result_digest TEXT NOT NULL DEFAULT '',summary TEXT NOT NULL DEFAULT '',claim TEXT NOT NULL DEFAULT '',claim_until INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL${if(stt)",request_sha TEXT NOT NULL,evidence_sha TEXT NOT NULL,execution TEXT NOT NULL" else ""})")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_binding_immutable BEFORE UPDATE OF scope,proof_id,queue ON $table BEGIN SELECT RAISE(ABORT,'SCHEDULED_DOT_PLAN_IMMUTABLE'); END")
        if(stt)db.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_input_immutable BEFORE UPDATE OF request_sha,evidence_sha,execution ON $table BEGIN SELECT RAISE(ABORT,'LOCAL_STT_INPUT_IMMUTABLE'); END")
    }}
    fun pin()=ProtectedDatabase.transaction(db){
        val old=db.rawQuery("SELECT proof_id,queue${if(stt)",request_sha,evidence_sha,execution" else ""} FROM $table WHERE scope=?",arrayOf(scope.key)).use{c->if(!c.moveToFirst())null else (0 until c.columnCount).map{c.getString(it)}}
        if(old!=null) {
            check(old[0]==plan.proofRequestId && old[1]==plan.queueFolder){"SCHEDULED_DOT_PLAN_CHANGED_REVIEW_REQUIRED"}
            if(stt)check(old[2]==plan.requestSha && old[3]==plan.evidenceSha && old[4]==prepared!!.upstreamExecutionReported){"LOCAL_STT_PIN_CHANGED_REVIEW_REQUIRED"}
        } else if(stt)db.execSQL("INSERT INTO $table(scope,proof_id,queue,state,updated_ms,request_sha,evidence_sha,execution) VALUES(?,?,?,?,?,?,?,?)",arrayOf(scope.key,plan.proofRequestId,plan.queueFolder,"PREPARED_LOCAL_STT_INPUT",clock().toEpochMilli(),plan.requestSha,plan.evidenceSha,prepared!!.upstreamExecutionReported))
        else db.execSQL("INSERT INTO $table(scope,proof_id,queue,state,updated_ms) VALUES(?,?,?,?,?)",arrayOf(scope.key,plan.proofRequestId,plan.queueFolder,"PREPARED_FIXED_PUBLIC_TEXT",clock().toEpochMilli()))
    }
    fun claim():Boolean=ProtectedDatabase.transaction(db){
        val busy=db.rawQuery("SELECT claim_until FROM $table WHERE scope=?",arrayOf(scope.key)).use{c->check(c.moveToFirst());check(c.getType(0)==android.database.Cursor.FIELD_TYPE_INTEGER){"SCHEDULED_DOT_STORE_REVIEW"};c.getLong(0)>clock().toEpochMilli()}
        if(busy)return@transaction false
        db.execSQL("UPDATE $table SET claim=?,claim_until=? WHERE scope=?",arrayOf(claim,clock().toEpochMilli()+120000,scope.key));true
    }
    fun release()=ProtectedDatabase.transaction(db){db.execSQL("UPDATE $table SET claim='',claim_until=0 WHERE scope=? AND claim=?",arrayOf(scope.key,claim))}
    fun capture(outcome:DotScheduledTextExchangePort.Outcome)=ProtectedDatabase.transaction(db){
        val current=db.rawQuery("SELECT binding,result_digest,claim,claim_until FROM $table WHERE scope=?",arrayOf(scope.key)).use{c->check(c.moveToFirst());check(c.getString(2)==claim && c.getLong(3)>clock().toEpochMilli()){ "SCHEDULED_DOT_CLAIM_EXPIRED" };c.getString(0) to c.getString(1)}
        val binding=outcome.registration?.let{r->
            check(r.job==plan.proofRequestId && r.folder==plan.queueFolder && listOf(r.input,r.result,r.descriptor).distinct().size==3){"SCHEDULED_DOT_REGISTRATION_CHANGED"}
            if(stt)check(r.inputSha==plan.requestSha){"LOCAL_STT_REGISTRATION_INPUT_MISMATCH"}
            JSONObject().put("job",r.job).put("folder",r.folder).put("input",r.input).put("result",r.result).put("enrollment",r.descriptor).put("inputSha",r.inputSha).put("enrollmentSha",r.descriptorSha).toString()
        } ?: current.first
        check(current.first.isBlank() || current.first==binding){"SCHEDULED_DOT_REGISTRATION_CHANGED"}
        val digest=outcome.proposal?.resultSha ?: current.second
        check(current.second.isBlank() || current.second==digest){"SCHEDULED_DOT_RESULT_CHANGED"}
        db.execSQL("UPDATE $table SET state=?,binding=?,result_digest=?,summary=CASE WHEN ?='' THEN summary ELSE ? END,updated_ms=? WHERE scope=? AND claim=?",arrayOf(outcome.state,binding,digest,outcome.proposal?.summary.orEmpty(),outcome.proposal?.summary.orEmpty(),clock().toEpochMilli(),scope.key,claim))
    }
    fun view(result:JSONObject):JSONObject=db.rawQuery("SELECT binding,result_digest,summary FROM $table WHERE scope=?",arrayOf(scope.key)).use{c->
        check(c.moveToFirst());if(c.getString(0).isNotBlank())result.put("registration",JSONObject(c.getString(0)))
        result.put("resultSha",c.getString(1)).put("readOnlySummary",c.getString(2))
    }
    override fun close()=protectedStore.close()
}

internal object ScheduledDotProofRegistry {
    @Volatile var plan:((Context,GoogleConnectionSession)->ScheduledDotProofPlan)?=null
    fun resolve(context:Context,session:GoogleConnectionSession,allowRetainedScope:Boolean=false)=plan?.invoke(context,session) ?: AutomationConnectionBindings.scheduled(context,session,allowRetainedScope) ?: ScheduledDotProofPlan()
    @Volatile var preparedInput:((Context,GoogleConnectionSession,ScheduledDotProofPlan)->PreparedWhisperScheduledTextInput?)?=null
}
internal object ScheduledDotProofRuntime {
    const val TAG="ieum.scheduled.dot.public-proof.v1"
    @Volatile private var hostWork:((String,PeriodicWorkRequest)->Unit)?=null
    private val restoreExecutor=java.util.concurrent.Executors.newSingleThreadExecutor { r->Thread(r,"ieum-approved-plan-restore").apply{isDaemon=true} }
    internal fun installWorkForHost(work:((String,PeriodicWorkRequest)->Unit)?){check(BuildConfig.DEBUG);hostWork=work}
    fun reconcile(context:Context,cancelled:()->Boolean={false}):JSONObject {
        if(cancelled())return JSONObject().put("state","LOCAL_WORK_STOPPED").put("nativeAudioRequestCompleted",false).put("sttExecuted",false)
        val session=GoogleConnectionSession.get(context)
        val p=ScheduledDotProofRegistry.resolve(context,session)
        if(!p.enabled)return JSONObject().put("state","DOT_TEXT_EXCHANGE_NOT_CONFIGURED").put("nativeAudioRequestCompleted",false).put("sttExecuted",false)
        val epoch=session.epoch();val owner=session.ownerBinding()
        val prepared=try {
            if(p.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC) {
                val provider=ScheduledDotProofRegistry.preparedInput
                if(provider!=null)provider(context,session,p) else DriveALocalScope.current(session)?.let{PreparedLocalSttFiles.load(context,it,p)}
            } else null
        } catch(_:Exception) {return JSONObject().put("state","HELD").put("reason","LOCAL_STT_PREPARED_INPUT_REJECTED").put("nativeAudioRequestCompleted",false).put("sttExecuted",false).put("canonicalWriteAllowed",false)}
        val port=NativeScheduledDotProofPort(DotScheduledTextExchangePort(context,session,{DotTextExchangeConfiguration(p.enabled,p.queueFolder)},ScheduledProofVersionCheckedDrive(DriveRestTransport(session,GoogleDriveHttp(session))),preparedSyntheticInput=prepared?.let{input->{job:String->input.also{it.forJob(job)}}}))
        return ScheduledDotProofCoordinator(context,session,p,port,{!cancelled() && session.epoch()==epoch && session.ownerBinding()==owner},preparedInput=prepared,nativeIngress={registration,proposal->ScheduledNativeTextIngress(context,session).receive(prepared,registration,proposal){!cancelled()&&session.epoch()==epoch&&session.ownerBinding()==owner}}).cycle()
    }
    fun ensure(context:Context) {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) {
            val app=context.applicationContext
            restoreExecutor.execute{runCatching{restoreApproved(app)}}
        }else restoreApproved(context)
    }
    private fun restoreApproved(context:Context) {
        val session=GoogleConnectionSession.get(context);val p=ScheduledDotProofRegistry.resolve(context,session,allowRetainedScope=true)
        if(!p.enabled || p.proofRequestId.isBlank() || p.queueFolder.isBlank())return
        ensureApproved(context,session,p)
    }
    /** Approved setup calls this after durable binding. Existing WorkManager persists across restarts. */
    fun ensureApproved(context:Context,session:GoogleConnectionSession,p:ScheduledDotProofPlan) {
        if(!p.enabled)return
        val owner=session.ownerBinding();if(owner.isBlank())return
        val scope=session.previouslyVerifiedScope() ?: return
        val name="$TAG.${GoogleConnectionSession.scopeKey(owner)}.${scope.epoch}.${p.proofRequestId}"
        val request=PeriodicWorkRequestBuilder<ScheduledDotProofWorker>(15,TimeUnit.MINUTES).setInputData(workDataOf("owner" to owner,"epoch" to scope.epoch,"native_scope" to scope.key,"proof" to p.proofRequestId)).addTag(TAG).build()
        val capture=hostWork
        if(capture!=null)capture(name,request)else WorkManager.getInstance(context).enqueueUniquePeriodicWork(name,ExistingPeriodicWorkPolicy.KEEP,request)
    }
}
class ScheduledDotProofWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    override fun doWork():Result {
        if(isStopped)return Result.failure(workDataOf("scheduled_text_state" to "LOCAL_WORK_STOPPED"))
        val session=GoogleConnectionSession.get(applicationContext)
        val owner=inputData.getString("owner").orEmpty();val epoch=inputData.getLong("epoch",-1)
        val auth=ExistingGoogleSessionRecoveryRuntime.recover(applicationContext,session,owner,epoch,inputData.getString("native_scope")){isStopped}
        if(!auth.ready)return if(auth.retryable && runAttemptCount<6)Result.retry() else Result.failure(workDataOf("scheduled_text_state" to auth.state,"reason" to auth.reason))
        val plan=ScheduledDotProofRegistry.resolve(applicationContext,session)
        if(!plan.enabled || plan.proofRequestId!=inputData.getString("proof"))return Result.failure(workDataOf("scheduled_text_state" to "STALE_OR_WITHDRAWN_PLAN"))
        return try {
            val state=ScheduledDotProofRuntime.reconcile(applicationContext){isStopped};val value=state.getString("state")
            val waiting=value.startsWith("WAITING_") || value in setOf("HELD","DOT_TEXT_EXCHANGE_NOT_CONFIGURED","LOCAL_WORK_STOPPED")
            if(waiting)Result.failure(workDataOf("scheduled_text_state" to value,"reason" to state.optString("reason")))else Result.success(workDataOf("scheduled_text_state" to value))
        }catch(_:Exception){if(runAttemptCount<6)Result.retry()else Result.failure(workDataOf("scheduled_text_state" to "SCHEDULED_CHECK_RETRY_LIMIT"))}
    }
}
