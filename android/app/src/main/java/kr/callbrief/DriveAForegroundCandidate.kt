package kr.callbrief

import android.content.Context
import kr.callagent.drive.AuthorizationPort
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DriveAReadbackObservation(val disposition:String,val receipt:DriveAReadOnlyReceipt?)
internal interface DriveAForegroundPort {
    fun scope():DriveALocalScope?
    fun snapshot(request:String):DriveAExportSnapshot
    fun frozenRequest(request:String):ByteArray
    fun reopen(request:String):DriveAReadOnlyReceipt?
    fun pollExplicit(request:String):DriveAReadbackObservation
}
/** Reads the already-bound original local request. No submit/retry/reservation,
 * token export, consent request, operator callback or trust promotion. */
internal class ExistingDriveARequestPort(private val context:Context,private val session:GoogleConnectionSession,
    private val factory:()->RecordingAnalysisPort={NativeRecordingAnalysisPort(context,session)}):DriveAForegroundPort {
    override fun scope()=DriveALocalScope.current(session)
    private fun job(request:String):String {
        val s=scope() ?: error("DRIVE_A_CURRENT_GRANT_NOT_VERIFIED")
        return session.commit(s.epoch,s.owner) {
            RecordingWorkflowStore(AccountStorageContext(context,s.owner),analysisActor={session.nativeSessionBinding()}).use { db ->
                val r=db.request(request);check(r.getString("state") in listOf("SENT","PENDING","REVIEW","ERROR")){"REQUEST_NOT_COMMITTED"}
                db.reconcileRemoteJob(request,session.nativeSessionBinding()) ?: error("REQUEST_ASSOCIATION_REVIEW_REQUIRED")
            }
        }
    }
    override fun snapshot(request:String):DriveAExportSnapshot {val id=job(request);return factory().use { it.driveAExport(id) }}
    override fun frozenRequest(request:String):ByteArray {val id=job(request);return factory().use {it.driveAFrozenRequest(id)}}
    override fun reopen(request:String):DriveAReadOnlyReceipt? {val id=job(request);return factory().use { it.driveAReopenedReceipt(id) }}
    override fun pollExplicit(request:String):DriveAReadbackObservation {
        val s=scope() ?: error("DRIVE_A_SCOPE_CHANGED");val id=job(request);factory().use { p ->val observation=p.pollObservation(id)
            session.commit(s.epoch,s.owner) {RecordingWorkflowStore(AccountStorageContext(context,s.owner)).use {it.commitResultObservation(request,observation)}}
            return DriveAReadbackObservation(observation.disposition,p.driveAReopenedReceipt(id)) }
    }
}
internal class DriveAForegroundCandidate(private val context:Context,private val port:DriveAForegroundPort) {
    data class Result(val state:String,val export:DriveAExportJournal.View?=null,val receipt:DriveAReadOnlyReceipt?=null,
        val handoffIntentCreated:Boolean=false,val providerAcceptanceVerified:Boolean=false,val modelExecutionVerified:Boolean=false,val remoteAtomicCasVerified:Boolean=false,val canonicalWriteAllowed:Boolean=false,val resultReadbackDisposition:String?=null)
    private fun held(e:Exception)=Result(when(e) {
        is DriveAIntegrityFailure->"STORE_REVIEW"
        else->when(e.message){"DRIVE_STORE_REVIEW_REQUIRED"->"STORE_REVIEW";"DRIVE_A_SCOPE_CHANGED","ACCOUNT_RESULT_BLOCKED","ACCOUNT_SESSION_CHANGED"->"SCOPE_HELD";"REQUEST_NOT_COMMITTED"->"REQUEST_NOT_COMMITTED";"DOT_PROFILE_ACCOUNT_UNSUPPORTED"->"CAPABILITY_HELD";"DRIVE_A_RESULT_CONFLICT"->"RESULT_CONFLICT";else->"SOURCE_OR_TRANSPORT_HELD"}
    })
    private fun validateRestored(v:DriveAExportJournal.View,receipt:DriveAReadOnlyReceipt?,frozen:ByteArray) {
        if(receipt==null){check(v.resultMetadata==null){"DRIVE_STORE_REVIEW_REQUIRED"};return}
        val request=kr.callagent.drive.WireV0Codec.validateRequest(frozen)
        check(receipt.job==request["jobId"] && receipt.result==request["resultFileId"]){"DRIVE_STORE_REVIEW_REQUIRED"}
        v.resultMetadata?.let { saved ->
            check((saved["revision"] as Long)<=receipt.revision){"DRIVE_STORE_REVIEW_REQUIRED"}
            if(saved["revision"]==receipt.revision)check(DriveACandidateContract.immutableReceiptMetadata(saved)==DriveACandidateContract.immutableReceiptMetadata(receipt.metadata())){"DRIVE_STORE_REVIEW_REQUIRED"}
        }
    }
    private suspend fun action(request:String,prepare:Boolean=false,attempt:Boolean=false,poll:Boolean=false,cancelled:Boolean?=null):Result=withContext(Dispatchers.IO) {
        val scope=port.scope() ?: return@withContext Result("CURRENT_GRANT_NOT_VERIFIED")
        try {
            val task=currentCoroutineContext()
            DriveAExportJournal(context,scope,{task.ensureActive();port.scope()}).use { journal ->
                var v=if(prepare) {
                    val current=port.snapshot(request);check(port.scope()==scope){"DRIVE_A_SCOPE_CHANGED"}
                    journal.prepare(request,DriveACandidateContract.build(request,scope,current))
                } else journal.load(request)
                if(v==null)return@withContext Result("EXPORT_NOT_PREPARED")
                val saved=DriveACandidateContract.obj(kr.callagent.drive.StrictJson.parse(v.payload.toByteArray()))
                val frozen=port.frozenRequest(request);task.ensureActive();check(DriveACandidateContract.hash(frozen)==saved["frozenRequestSha256"] && String(frozen,Charsets.UTF_8)==saved["frozenRequestJson"]){"DRIVE_A_EXPORT_CHANGED"}
                val restored=port.reopen(request);task.ensureActive();check(port.scope()==scope){"DRIVE_A_SCOPE_CHANGED"}
                validateRestored(v,restored,frozen)
                if(attempt) {
                    val current=port.snapshot(request);check(DriveACandidateContract.build(request,scope,current)==v.payload){"DRIVE_A_EXPORT_CHANGED"}
                }
                var offer=false
                if(attempt){val intent=journal.handoffIntent(request);v=intent.view;offer=intent.created}
                if(cancelled!=null)v=journal.uiReturn(request,cancelled)
                val readback=if(poll)port.pollExplicit(request) else null
                val receipt=if(poll)readback?.receipt else restored
                task.ensureActive();check(port.scope()==scope){"DRIVE_A_SCOPE_CHANGED"}
                validateRestored(v,receipt,frozen)
                if(receipt!=null && poll && readback?.disposition in setOf("PENDING","ERROR","CANCELLED","PROVENANCE_PENDING","READ_ONLY_CANDIDATE","LEGACY_CANDIDATE","DUPLICATE_PROPOSAL","DUPLICATE"))v=journal.observation(request,receipt)
                Result(v.state,v,receipt,handoffIntentCreated=offer,resultReadbackDisposition=readback?.disposition)
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){held(e)}
    }
    fun canOpenShareUi(result:Result):Boolean {
        if(!result.handoffIntentCreated)return false
        val scope=port.scope() ?: return false
        return runCatching {result.export?.let {DriveACandidateContract.validate(it.payload,it.request,scope);it.attempt!=null}==true}.getOrDefault(false)
    }
    suspend fun prepareExplicit(request:String)=action(request,prepare=true)
    suspend fun load(request:String)=action(request)
    suspend fun beginHandoffIntentExplicit(request:String)=action(request,attempt=true)
    suspend fun noteShareUiReturn(request:String,cancelled:Boolean)=action(request,cancelled=cancelled)
    suspend fun pollExplicit(request:String)=action(request,poll=true)
    companion object {
        fun text(state:String)=when(state) {
            "REQUEST_PREPARED"->"자기 dot에 요청할 합성 한 건을 준비했어요. 접수·실행은 미확인입니다."
            "HANDOFF_UNCERTAIN"->"공유·복사 시도를 기록했어요. dot 접수는 미확인이며 같은 요청을 보존해요."
            "RESULT_OBSERVATION_RECORDED"->"읽기 전용 결과 관측을 기록했어요. 작성자·실행 출처와 업무 반영은 별도 확인이 필요해요."
            "CURRENT_GRANT_NOT_VERIFIED"->"기존 본인 계정의 현재 Drive 접근 확인이 필요해요. 새 권한은 요청하지 않았어요."
            "EXPORT_NOT_PREPARED"->"이 요청의 인계 자료를 먼저 기기에 준비해 주세요."
            "STORE_REVIEW"->"저장 근거가 일치하지 않아 보류했어요. 원본 요청과 기록은 유지해요."
            "REQUEST_NOT_COMMITTED"->"원래 요청 게시가 완료되지 않았어요. dot 처리를 시작할 근거가 없어요."
            "CAPABILITY_HELD"->"기존 본인 계정의 고정 계약 범위 확인이 필요해요."
            else->"현재 계정·원음·요청·결과의 일치를 확인해야 해요. 기존 기록은 유지해요."
        }
    }
}
