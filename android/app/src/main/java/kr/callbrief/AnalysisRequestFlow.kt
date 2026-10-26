package kr.callbrief

import android.content.Context
import kr.callagent.drive.AuthorizationPort
import kr.callagent.drive.DriveRestTransport
import org.json.JSONObject
import java.io.IOException

/** Transport seam reuses the frozen Drive contract and native synthetic source admission. */
internal interface RecordingAnalysisPort:AutoCloseable {
    data class Sent(val outcome:String,val jobId:String)
    fun submit(recording:String):Sent
    val requiresNativeBinding:Boolean get()=false
    fun submitBound(recording:String,observer:(kr.callagent.drive.LocalJobStore.Job)->Unit):Sent=submit(recording)
    fun resume(job:String,observer:(kr.callagent.drive.LocalJobStore.Job)->Unit):Sent=error("NATIVE_RESUME_NOT_CONNECTED")
    fun retryBound(job:String,observer:(kr.callagent.drive.LocalJobStore.Job)->Unit):Sent=retry(job)
    fun poll(job:String):String
    fun pollObservation(job:String)=GoogleRelayStore.ResultObservation(job,poll(job))
    fun retry(job:String):Sent
    fun jobs():List<GoogleRelayStore.JobView>
    fun sync(job:String):String=error("SUPPORTED_SYNC_NOT_CONNECTED")
    data class SyncObservation(val disposition:String,val result:GoogleRelayStore.ResultObservation?=null)
    fun syncObservation(job:String)=SyncObservation(sync(job))
    fun registration(job:String):JSONObject=error("NATIVE_EXPORT_NOT_CONNECTED")
    fun driveAExport(job:String):DriveAExportSnapshot=error("DRIVE_A_LOCAL_EXPORT_NOT_CONNECTED")
    fun driveAReopenedReceipt(job:String):DriveAReadOnlyReceipt?=error("DRIVE_A_LOCAL_RECEIPT_NOT_CONNECTED")
    fun driveAFrozenRequest(job:String):ByteArray=error("DRIVE_A_FROZEN_REQUEST_NOT_CONNECTED")
    fun cancel()
}
internal class NativeRecordingAnalysisPort(context:Context,session:GoogleConnectionSession,
    private val supportedHandoff:((String,String)->Unit)?=null):RecordingAnalysisPort {
    private val session=session
    private val scoped=AccountStorageContext(context,session.ownerBinding())
    private val settings=SecureSettings(scoped)
    private val pipeline=GoogleNativePipeline(context,session,settings,
        { SafSelectedRecordingSource(scoped,settings,settings.snapshot().getString("treeUri")) },DriveRestTransport(session,GoogleDriveHttp(session)))
    override fun submit(recording:String)=pipeline.submitExisting(recording).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
    override val requiresNativeBinding=true
    override fun submitBound(recording:String,observer:(kr.callagent.drive.LocalJobStore.Job)->Unit)=pipeline.submitExisting(recording,observer).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
    override fun resume(job:String,observer:(kr.callagent.drive.LocalJobStore.Job)->Unit)=pipeline.resume(job,observer).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
    override fun retryBound(job:String,observer:(kr.callagent.drive.LocalJobStore.Job)->Unit)=pipeline.retry(job,observer).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
    override fun poll(job:String)=pipeline.poll(job)
    override fun pollObservation(job:String)=pipeline.pollObservation(job)
    override fun retry(job:String)=pipeline.retry(job).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
    override fun jobs()=pipeline.jobs()
    override fun registration(job:String)=pipeline.registration(job)
    override fun driveAExport(job:String)=pipeline.driveAExport(job)
    override fun driveAReopenedReceipt(job:String)=pipeline.driveAReopenedReceipt(job)
    override fun driveAFrozenRequest(job:String)=pipeline.driveAFrozenRequest(job)
    override fun sync(job:String)=syncObservation(job).disposition
    override fun syncObservation(job:String)=kr.co.callsummary.core.android.AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use { db ->
        db.requireProtectedPersistence();val adapter=Code16DotManualSync(session,pipeline,db,operatorHandoff=supportedHandoff)
        val state=adapter.syncExplicitSynthetic(setOf(job),1).single().state
        RecordingAnalysisPort.SyncObservation(state,adapter.resultObservation(job))
    }
    override fun cancel()=pipeline.cancel()
    override fun close()=pipeline.close()
}

/** Durable foreground workflow. PREPARED is metadata only; it never means delivered/analyzed.
 * SUBMITTING recovered without a receipt is UNKNOWN, and never automatically resubmitted. */
internal class AnalysisRequestFlow(private val context:Context,private val session:GoogleConnectionSession,
    private val factory:()->RecordingAnalysisPort={NativeRecordingAnalysisPort(context,session)}):AutoCloseable {
    private val view=session.dailyReportScope()
    private val scoped:Context=if(view.ownerId.isBlank())context else AccountStorageContext(context,view.ownerId)
    @Volatile private var stopped=false
    @Volatile private var active:RecordingAnalysisPort?=null
    private fun current() { check(!stopped) { "REQUEST_CANCELLED" };session.requireEpoch(view.epoch);check(session.ownerBinding()==view.ownerId) { "ACCOUNT_SESSION_CHANGED" } }
    fun prepare(recording:String,expected:JSONObject):String {
        current();return RecordingWorkflowStore(scoped).use { it.prepareAnalysis(recording,expected,if(authorized())actor() else null) }
    }
    fun requestState(request:String):String { current();return RecordingWorkflowStore(scoped).use { it.request(request).getString("state") } }
    private fun actor():JSONObject { current();return session.nativeSessionBinding() }
    private fun reserved(request:String,job:kr.callagent.drive.LocalJobStore.Job) { current();RecordingWorkflowStore(scoped).use { it.bindReservedRequest(request,job,actor()) } }
    private fun requireAssociation(port:RecordingAnalysisPort,request:String) { if(port.requiresNativeBinding) {
        val known=RecordingWorkflowStore(scoped).use { it.reconcileRemoteJob(request,actor()) }
        if(known==null) { RecordingWorkflowStore(scoped).use { val old=it.request(request);it.transitionRequest(request,old.getString("state"),reason="REQUEST_ASSOCIATION_REVIEW_REQUIRED") };error("REQUEST_ASSOCIATION_REVIEW_REQUIRED") }
    } }
    private fun authorized():Boolean=session.state()==AuthorizationPort.State.ACTIVE && view.ownerId.isNotBlank()
    fun submit(requestId:String):String {
        current()
        val record=RecordingWorkflowStore(scoped).use { store ->
            val request=store.request(requestId)
            when(request.getString("state")) {
                "SUBMITTING" -> {
                    if(request.optBoolean("claim_current_process"))return "SUBMITTING"
                    store.transitionRequest(requestId,"UNKNOWN",reason="DELIVERY_REQUIRES_RECONCILIATION");store.reconcileRemoteJob(requestId,if(authorized())actor() else null);return "UNKNOWN"
                }
                "UNKNOWN","CANCELLED" -> return request.getString("state")
                "SENT","PENDING","REVIEW" -> return request.getString("state")
            }
            val id=store.assertRequestCurrent(requestId)
            if(!authorized()) { store.transitionRequest(requestId,"HELD_AUTH",reason="GOOGLE_DRIVE_AUTH_REQUIRED");return "HELD_AUTH" }
            store.transitionRequest(requestId,"SUBMITTING");id
        }
        try {
            current();factory().use { port ->
                active=port;current();val sent=port.submitBound(record) { job->reserved(requestId,job) };current()
                val state=when(sent.outcome) { "SENT","DUPLICATE"->"SENT";"AUTH_REQUIRED"->"HELD_AUTH";else->"UNKNOWN" }
                RecordingWorkflowStore(scoped).use { it.transitionRequest(requestId,state,sent.jobId,sent.outcome) };return state
            }
        } catch(e:Exception) {
            val message=e.message.orEmpty()
            val sourceHeld=listOf("NATIVE_SOURCE_POLICY_HELD","NATIVE_SOURCE_APPROVAL_OR_GRANT_REQUIRED","NATIVE_SOURCE_NOT_REGISTERED","EXCLUDED_BEFORE_READ","NATIVE_SOURCE_LEASE_STALE","ANALYSIS_CONTEXT_TOO_LARGE","RELATED_EVIDENCE_HASH_MISMATCH")
            val state=if(message=="DOT_PROFILE_ACCOUNT_UNSUPPORTED")"HELD_CAPABILITY" else if(e !is IOException && sourceHeld.any { it==message })"HELD_SOURCE" else "UNKNOWN"
            runCatching { RecordingWorkflowStore(scoped).use { it.transitionRequest(requestId,state,reason=if(state=="HELD_CAPABILITY")"DOT_PROFILE_ACCOUNT_UNSUPPORTED" else if(state=="HELD_SOURCE")"SYNTHETIC_SOURCE_APPROVAL_REQUIRED" else "DELIVERY_REQUIRES_RECONCILIATION");if(state=="UNKNOWN")it.reconcileRemoteJob(requestId,if(authorized())actor() else null) } }
            if(stopped || session.epoch()!=view.epoch)throw e
            return state
        } finally { active=null }
    }
    fun poll(requestId:String):String {
        current();if(!authorized())return "HELD_AUTH"
        val request=RecordingWorkflowStore(scoped).use { it.request(requestId) }
        check(request.getString("state")!="CANCELLED") { "REQUEST_CANCELLED" }
        val job=request.optString("job_id").takeIf { it.isNotBlank()&&it!="null" } ?: return request.getString("state")
        try { return factory().use { port ->
            active=port;current();requireAssociation(port,requestId);val observation=port.pollObservation(job);current()
            RecordingWorkflowStore(scoped).use { it.commitResultObservation(requestId,observation) }
        } } finally { active=null }
    }
    /** Explicit original-job resume. No successor IDs, automatic retry or re-enrollment. */
    fun resume(requestId:String):String {
        current();check(authorized()) { "GOOGLE_DRIVE_AUTH_REQUIRED" }
        val job=RecordingWorkflowStore(scoped).use { store->
            val request=store.request(requestId);check(request.getString("state") in listOf("UNKNOWN","HELD_SOURCE","HELD_AUTH")) { "EXPLICIT_RECONCILIATION_REQUIRED" }
            store.assertRequestCurrent(requestId)
            val known=store.reconcileRemoteJob(requestId,actor()) ?: error("REQUEST_ASSOCIATION_REVIEW_REQUIRED")
            store.transitionRequest(requestId,"SUBMITTING");known
        }
        try { return factory().use { port->active=port;current();requireAssociation(port,requestId)
            val sent=port.resume(job) { captured->reserved(requestId,captured) };current()
            check(sent.jobId==job) { "RESUME_JOB_ID_CHANGED" }
            val state=when(sent.outcome) { "SENT","DUPLICATE"->"SENT";"AUTHORIZATION_REQUIRED"->"HELD_AUTH";else->"UNKNOWN" }
            RecordingWorkflowStore(scoped).use { it.transitionRequest(requestId,state,job,sent.outcome) };state
        } } catch(e:Exception) {
            runCatching { RecordingWorkflowStore(scoped).use { it.transitionRequest(requestId,"UNKNOWN",job,"EXPLICIT_RESUME_HELD") } };throw e
        } finally { active=null }
    }
    fun retry(requestId:String):String {
        current();check(authorized()) { "GOOGLE_DRIVE_AUTH_REQUIRED" }
        val parent=RecordingWorkflowStore(scoped).use { it.assertRequestCurrent(requestId);it.request(requestId) };check(parent.getString("state")=="ERROR") { "EXPLICIT_FAILURE_REQUIRED" }
        val child=RecordingWorkflowStore(scoped).use { it.prepareAnalysis(parent.getString("recording_id"),it.snapshot(parent.getString("recording_id")),actor(),requestId) }
        val state=RecordingWorkflowStore(scoped).use { it.request(child).getString("state") }
        if(state in listOf("SENT","PENDING","REVIEW","SUBMITTING","UNKNOWN"))return state
        RecordingWorkflowStore(scoped).use { it.transitionRequest(child,"SUBMITTING") }
        try { return factory().use { port->active=port;current();requireAssociation(port,requestId)
            val sent=port.retryBound(parent.getString("job_id")) { reserved->this.reserved(child,reserved) };current()
            val result=if(sent.outcome in listOf("SENT","DUPLICATE"))"SENT" else "UNKNOWN"
            RecordingWorkflowStore(scoped).use { it.transitionRequest(child,result,sent.jobId,sent.outcome) };result
        } } catch(e:Exception) { runCatching { RecordingWorkflowStore(scoped).use { it.transitionRequest(child,"UNKNOWN",reason="RETRY_DELIVERY_REQUIRES_RECONCILIATION") } };throw e }
        finally { active=null }
    }
    fun sync(requestId:String):String {
        current();if(!authorized())return "HELD_AUTH"
        val request=RecordingWorkflowStore(scoped).use { it.request(requestId) }
        check(request.getString("state")!="CANCELLED") { "REQUEST_CANCELLED" }
        val job=request.optString("job_id").takeIf { it.isNotBlank()&&it!="null" } ?: return request.getString("state")
        try { factory().use { port ->active=port;requireAssociation(port,requestId);val observed=port.syncObservation(job);current()
            RecordingWorkflowStore(scoped).use { it.commitSyncObservation(requestId,observed) };return observed.disposition
        } } finally { active=null }
    }
    fun export(requestId:String):String {
        current();check(authorized()) { "GOOGLE_DRIVE_AUTH_REQUIRED" }
        val request=RecordingWorkflowStore(scoped).use { it.request(requestId) };check(request.getString("state")!="CANCELLED") { "REQUEST_CANCELLED" }
        try { return factory().use { port ->active=port;requireAssociation(port,requestId);val value=port.registration(request.getString("job_id"));current();value.toString(2) } }
        finally { active=null }
    }
    fun cancel(requestId:String) { stopped=true;active?.cancel();RecordingWorkflowStore(scoped).use { it.transitionRequest(requestId,"CANCELLED",reason="LOCAL_STOP_REMOTE_COPIES_NOT_RECALLED") } }
    override fun close() { stopped=true;active?.cancel() }
    companion object {
        fun reasonText(reason:String)=when(reason) {
            "HELD_UNSUPPORTED_RESULT_PROFILE"->"새 결과 형식은 아직 지원하지 않아요. 받은 제안과 직접 수정·완료는 유지해요."
            "HELD_INVALID_RESULT","HELD_REFERENCE_REJECTED","WRONG_RESULT_FILE_ID","REVISION_CONFLICT","LOCAL_STORE_FAILED","SOURCE_QUARANTINED","DUPLICATE","HISTORY_ONLY"->"새 결과는 확인 대상으로 보류했어요. 기존 제안·원문·수동 기록은 유지해요."
            "OLD_RESULT_HISTORY_PRESERVED","RESULT_REVISION_CONFLICT","UNVERSIONED_RESULT_HELD"->"이전 또는 순서가 확인되지 않은 응답은 보류했어요. 최신 결과 상태와 수동 기록은 유지해요."
            "REQUEST_ASSOCIATION_REVIEW_REQUIRED"->"원래 요청과 예약된 작업의 연결 확인 필요 · 다른 작업을 추정해 연결하지 않아요."
            "AWAITING_SUPPORTED_DOT_ENTRY"->"지원되는 dot 실행 연결 대기 · 분석 실행 성공이 아니에요."
            "DOT_PROFILE_ACCOUNT_UNSUPPORTED"->"현재 dot 인수 계약의 계정 범위와 맞지 않아요. 원음 게시를 보류했어요."
            "EXPLICIT_RESUME_HELD"->"같은 작업의 게시 재개를 확인하지 못했어요. 자동 재전송 없이 이력을 유지해요."
            else->null
        }
        fun text(state:String)=when(state) {
            "PREPARED"->"분석 요청을 기기에 준비했어요. 녹음은 전달하지 않았어요."
            "HELD_AUTH"->"Google 계정·Drive 동의 확인 필요 · 녹음 미전달"
            "HELD_SOURCE"->"지정 합성 원음 승인·폴더·저장소 확인 필요 · 개인 원음 전달 차단"
            "HELD_CAPABILITY"->"현재 dot 인수 계약의 계정 범위 확인 필요 · 원음 게시 보류"
            "SUBMITTING"->"요청 전달 확인 중 · 분석 완료 아님"
            "SENT","PENDING"->"Drive 요청 게시됨 · dot 실행 연결 대기 · 분석 완료 아님"
            "REVIEW"->"전사·분석 제안 수신 · 사용자 검토 필요 · 업무 자동 반영 없음"
            "ERROR"->"외부 오류 수신 · 이력을 남기고 명시적으로 새 시도 가능"
            "UNKNOWN"->"전달 여부 확인 필요 · 자동 재전송 보류"
            "CANCELLED"->"이 기기의 후속 작업 중단 · 이미 전달된 사본은 회수하지 않음"
            "AWAITING_SUPPORTED_DOT_ENTRY"->"Drive 게시 요청 확인 · 지원되는 foreground dot 실행 경로 연결 필요"
            "AWAITING_DOT_OPERATOR"->"인계 접수 확인 · dot 실행·모델 결과 대기"
            "HANDOFF_UNCERTAIN_RECONCILE_REQUIRED"->"인계 여부 확인 필요 · 중복 인계·자동 재발송 보류"
            "REQUEST_NOT_COMMITTED"->"요청 게시 미완료 · dot 처리 시작 없음"
            "LOCAL_BINDING_HELD"->"현재 원음·계정·소스 연결 확인 필요"
            "READ_ONLY_RESULT_RECEIVED_CONFIRMATION_REQUIRED"->"읽기 전용 결과 회수 · 사용자 검토 필요"
            else->"현재 단계 확인 필요"
        }
    }
}
