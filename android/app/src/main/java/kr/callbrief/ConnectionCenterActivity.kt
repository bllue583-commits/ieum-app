package kr.callbrief

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callagent.drive.DriveRestTransport
import org.json.JSONObject

/** Account selection + Drive authorization. Every result remains an unbound read-only proposal. */
internal open class ConnectionCenterActivity : ComponentActivity() {
    private lateinit var session: GoogleConnectionSession
    private var sdk: GoogleConnectActions? = null
    private lateinit var root: LinearLayout
    private var busy = false
    private var closed = false
    private var generation = 0L
    private var task: Job? = null
    private var message = ""
    private var rows = emptyList<GoogleRelayStore.JobView>()
    private var pickerEpoch = -1L
    private var dialog: AlertDialog? = null
    private fun alive() = !closed && !isFinishing && !isDestroyed
    protected open fun createAuthorization(s: GoogleConnectionSession, c: GoogleAuthorizationSdk.Consumer): GoogleConnectActions = GoogleAuthorizationSdk(this,s,c)
    protected open fun createRelay(s: GoogleConnectionSession) = GoogleRelayStore(this,s,DriveRestTransport(s,GoogleDriveHttp(s)))
    private val wavPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(!alive()) return@registerForActivityResult
        if(uri==null) { message="선택을 취소했어요. 이전 전달 기록은 유지해요.";render() }
        else if(session.epoch()!=pickerEpoch) { message="계정이 바뀌어 선택한 자료를 전달하지 않았어요.";render() }
        else runRelay { it.submitSelectedSynthetic(uri,true).outcome.name }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(221,241,248))
            setOnApplyWindowInsetsListener { v,i -> v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i }
        };setContentView(root)
        try { session=GoogleConnectionSession.get(this) } catch(_:Exception) {
            root.addView(label("연결 정보를 읽지 못했어요. 기존 자료를 지우거나 다른 계정에 연결하지 않았어요.",22f,true))
            root.addView(button("뒤로") { finish() });return
        }
        sdk=createAuthorization(session,object:GoogleAuthorizationSdk.Consumer {
            override fun selected(account:String) { if(alive()) { rows=emptyList();message="선택한 계정: $account · Drive 접근 동의를 확인해 주세요.";render() } }
            override fun status(code:String) { if(alive()) { message=statusMessage(code);render() } }
            override fun authorized(epoch:Long,account:String,token:String,refreshingOwner:String?) {
                operation {
                    val owner=withContext(Dispatchers.IO) { GoogleDriveHttp(session).verifySelectedOwner(epoch,account,token) }
                    currentCoroutineContext().ensureActive()
                    check(refreshingOwner==null || refreshingOwner==owner.permissionId) { "GOOGLE_ACCOUNT_MISMATCH" }
                    session.activate(epoch,account,owner.account,owner.permissionId,token)
                    withContext(Dispatchers.IO){
                        ScheduledDotProofRuntime.ensure(this@ConnectionCenterActivity)
                        AutomaticResultReconciliation.rearmExisting(this@ConnectionCenterActivity,session)
                    }
                    rows=emptyList();message="선택한 Google 계정과 Drive 접근을 확인했어요. dot의 분석 실행은 별도로 확인해야 해요."
                }
            }
        })
        render()
        // No consent window is opened on restart. Existing grants may be checked silently once.
        if(session.view().phase==GoogleConnectionSession.Phase.REAUTH_REQUIRED) sdk?.refreshExistingNoPrompt()
        else if(session.view().phase==GoogleConnectionSession.Phase.ACTIVE) reload()
    }
    private fun dp(n:Int)=(resources.displayMetrics.density*n+.5f).toInt()
    private fun label(value:String,size:Float=18f,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(8),0,dp(8));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply {
        text=value;textSize=18f;isAllCaps=false;minHeight=dp(56);isEnabled=!busy
        setOnClickListener { if(alive()&&!busy)action() }
    }
    private fun section(body:LinearLayout,title:String)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(12),0,dp(12));body.addView(this);addView(label(title,22f,true))
    }
    private fun render() {
        if(!alive()||!::session.isInitialized)return
        root.removeAllViews();root.addView(label(if(intent.getBooleanExtra("onboarding",false)) "이음 시작하기" else "계정과 연결",26f,true))
        val scroll=ScrollView(this);val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val v=session.view();val google=section(body,"Google · Drive")
        val chatgpt=section(body,"ChatGPT · 분석 연결")
        chatgpt.addView(button("ChatGPT 연결 · 동의 대기") { startActivity(Intent(this,SiwcRuntimeActivity::class.java)) }.apply{tag="siwc_connection"})
        chatgpt.addView(button("승인된 공개 음성 ChatGPT 분석") { startActivity(Intent(this,SiwcAppAnalysisActivity::class.java)) }.apply{tag="siwc_app_analysis"})
        google.addView(label(if(v.accountLabel.isBlank()) "연결된 Google 계정이 없어요." else "현재 계정: ${v.accountLabel}",bold=true))
        google.addView(label(when(v.phase) {
            GoogleConnectionSession.Phase.ACTIVE -> "계정·Drive 접근 확인됨"
            GoogleConnectionSession.Phase.CHECKING_ACCOUNT -> "선택 계정의 Drive 정보를 확인 중"
            GoogleConnectionSession.Phase.CONSENT_REQUIRED -> "Drive 접근 동의 필요"
            GoogleConnectionSession.Phase.REAUTH_REQUIRED -> "접근 상태 재확인 필요"
            GoogleConnectionSession.Phase.ACCOUNT_SELECTED -> "계정 선택 후 Drive 동의 필요"
            GoogleConnectionSession.Phase.FAILED -> "연결 확인 필요 · 전달 완료 아님"
            else -> "미연결"
        }).apply { tag="google_phase" })
        google.addView(label("계정마다 통화·메모·완료·전달 기록을 따로 보관해요. 계정에 연결하지 않은 기존 기기 기록은 자동으로 이전하지 않아요."))
        google.addView(button(if(v.accountLabel.isBlank()) "Google 계정 선택" else "Google 계정 변경") {
            if(v.accountLabel.isBlank()) sdk?.chooseAccount() else confirm("Google 계정 변경","진행 중인 전달과 결과 회수를 중단해요. 이전 계정 자료는 보존하고 새 계정 목록을 따로 열어요.","변경하기") { rows=emptyList();sdk?.chooseAccount() }
        }.apply { tag="google_choose" })
        if(v.phase!=GoogleConnectionSession.Phase.ACTIVE) google.addView(button("선택 계정으로 Drive 연결") { sdk?.requestDriveConsent() }.apply { tag="google_consent" })
        if(v.accountLabel.isNotBlank()) google.addView(button("이 앱에서 연결 해제") {
            confirm("Google 연결 해제","이 앱의 접근 토큰과 진행 중인 요청을 중단해요. 계정별 저장 자료와 메모·완료는 지우지 않아요. Google 계정의 기존 동의 자체를 철회하는 동작은 아니에요.","연결 해제") { session.localSignOut();rows=emptyList();message="이 앱의 연결을 해제했어요. 저장 자료는 보존돼요.";render() }
        }.apply { tag="google_disconnect" })
        val recovery=ExistingGoogleSessionRecoveryRuntime.messageKo(session.recoveryState())
        if(recovery.isNotBlank())google.addView(label(recovery).apply{tag="background_auth_waiting"})
        google.addView(button("기존 승인 자동 확인 설정") { startActivity(Intent(this,AutomationConnectionSetupActivity::class.java)) }.apply{tag="automation_approved_setup"})
        if(message.isNotBlank()) google.addView(label(message).apply { tag="connection_message" })
        if(busy)google.addView(label("확인 중이에요. 같은 동작을 반복하지 않아도 돼요."))
        if(v.phase==GoogleConnectionSession.Phase.ACTIVE) {
            if(!FrozenDotCapability(this).supports(session.driveAccount()))google.addView(label("Drive 접근은 확인됐지만 현재 dot 인수 계약의 계정 범위와 맞지 않아요. 분석 요청 게시를 보류하며 일반 계정 지원은 별도 확인이 필요해요."))
            val relay=section(body,"합성 자료 전달 · 결과 확인")
            relay.addView(label("합성 WAV만 선택해 주세요. 최대 5MB예요. 실제 통화 자동 전달과 06시 실행은 준비 중이에요. Drive 전달은 dot 실행 완료를 뜻하지 않아요."))
            relay.addView(button("합성 WAV 선택 후 전달") {
                startActivity(Intent(this,NativeGoogleTrialActivity::class.java))
            }.apply { tag="google_wav_picker" })
            relay.addView(button("전달 기록 다시 읽기") { reload() })
            rows.forEach { row ->
                val item=section(body,"전달 기록")
                item.addView(label(when {
                    row.phase=="CANCELLED" -> "이전 시도 중단 · 실패 이력 보존"
                    row.remoteState=="ERROR" -> "분석 오류 수신 · 직접 재시도 가능"
                    row.remoteState=="CANCELLED" -> "외부 작업 중단 수신"
                    row.rawText!=null -> "읽기 전용 제안 수신 · 업무에 미반영"
                    row.phase=="SENT" -> "Drive 요청 전달됨 · dot 결과 대기"
                    else -> "전달 단계 확인 필요"
                },bold=true))
                row.retryOf?.let { item.addView(label("이전 실패에서 시작한 새 시도")) }
                if(row.phase in listOf("SENT","CANDIDATE"))item.addView(button("이 작업 결과 확인") { runRelay { it.pollReadOnly(row.id) } })
                if(row.remoteState in listOf("ERROR","CANCELLED") && row.phase!="CANCELLED") item.addView(button("오류 작업 새로 시도") {
                    confirm("오류 작업 재시도","실패 영수증을 보존하고 이전 시도를 중단한 뒤 새 작업으로 전달해요. 늦은 이전 결과는 새 작업에 반영하지 않아요.","새로 시도") { runRelay { it.retryFailed(row.id).outcome.name } }
                })
                if(row.rawText!=null)item.addView(button("원문·분석 제안 보기") { showProposal(row) })
                item.addView(button("Drive 전달 폴더 열기") { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://drive.google.com/drive/folders/"+row.folderId))) })
            }
        }
        if(BuildConfig.SYNTHETIC_TOOLS_ENABLED) {
            val local=section(body,"기기 안 공개 음성 시험")
            local.addView(label("계정 연결 없이 앱에 포함된 합성 음성을 처리해요. 실제 통화는 사용하지 않아요."))
            local.addView(button("공개 음성으로 기기 요약 시험") { startActivity(Intent(this,AndroidLocalSttActivity::class.java)) }.apply { tag="android_local_stt_trial" })
            local.addView(button("공개 전사·합성 결과파일 검토 시험") { startActivity(Intent(this,SiwcPublicResultActivity::class.java)) }.apply { tag="siwc_public_trial" })
        }
        val fixed=section(body,"고정 합성 텍스트 확인")
        fixed.addView(label("앱의 결과 반환 연결은 아직 확인되지 않았어요. 브라우저 작업과 기기 안 합성 검증을 구분해요."))
        fixed.addView(button("고정 합성 텍스트 연결 확인") { startActivity(Intent(this,SyntheticTextConnectionActivity::class.java)) }.apply { tag="synthetic_text_connection" })
        val other=section(body,"다른 연결")
        other.addView(button("네이버 메일 새 연결 · 미연결") { ScopedNaverMailSettingsActivity.open(this) }.apply { tag="naver_mail_settings" })
        other.addView(button("저장된 네이버 메일 후보 검토") { startActivity(Intent(this,NaverMailReviewActivity::class.java)) })
        other.addView(button("카카오 연결 · 준비 중") { confirm("카카오 미연결","개인별 카카오 로그인과 나와의 채팅 전송 동의가 필요해요. 개발앱 설정과 실제 전송 확인은 아직 남아 있어요.","설정 열기") { startActivity(Intent(this,ScopedKakaoSettingsActivity::class.java)) } })
        root.addView(button(if(intent.getBooleanExtra("onboarding",false)) "지금은 건너뛰고 시작" else "목록으로 돌아가기") { finishOnboarding();finish() })
    }
    private fun confirm(title:String,text:String,positive:String,action:()->Unit) {
        if(!alive())return
        dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle(title).setMessage(text).setNegativeButton("취소",null).setPositiveButton(positive) { _,_ -> if(alive())try { action() } catch(_:Exception) { message="변경 저장을 확인하지 못했어요. 기존 자료는 유지해요.";render() } }.create()
        ProtectedDialogWindow.show(dialog!!)
    }
    private fun showProposal(row:GoogleRelayStore.JobView) {
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
        body.addView(label("읽기 전용 분석 제안 · 사람·업무 연결과 실제 dot 실행 확인 필요",22f,true))
        body.addView(label("전사 원문"));body.addView(label(row.rawText.orEmpty()))
        val analysis=runCatching { JSONObject(row.analysis ?: "{}") }.getOrDefault(JSONObject())
        body.addView(label(analysis.optString("summary","분석 요약 없음")))
        analysis.optJSONArray("observations")?.let { a -> for(i in 0 until a.length()) { val o=a.getJSONObject(i);body.addView(label("분석 제안: "+o.optString("value")));o.optJSONArray("evidence")?.let { e -> for(k in 0 until e.length())body.addView(label("원문 근거: "+e.getJSONObject(k).optString("quote"))) } } }
        dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle("원문·근거").setView(ScrollView(this).apply { addView(body) }).setPositiveButton("닫기",null).create();ProtectedDialogWindow.show(dialog!!)
    }
    private fun operation(action:suspend ()->Unit) {
        if(!alive()||busy)return
        val run=++generation;busy=true;render()
        task=lifecycleScope.launch {
            try { action() }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(alive()&&run==generation)message="연결·전달·수신을 확인하지 못했어요. 완료로 표시하지 않았어요. 계정 접근과 작업 상태를 다시 확인해 주세요." }
            finally { if(run==generation) { busy=false;if(alive())render() } }
        }
    }
    private fun runRelay(action:(GoogleRelayStore)->String)=operation {
        val expected=session.view()
        val result=withContext(Dispatchers.IO) { createRelay(session).use { action(it) to it.list() } }
        currentCoroutineContext().ensureActive();check(expected.ownerId==session.ownerBinding() && expected.epoch==session.epoch())
        rows=result.second;message=statusMessage(result.first)
    }
    private fun reload()=runRelay { "LOCAL_LIST_READ" }
    private fun finishOnboarding() { getSharedPreferences("eum_onboarding_v1",MODE_PRIVATE).edit().putBoolean("seen",true).commit() }
    override fun onStop() {
        if(busy && ::session.isInitialized) {
            session.invalidate(kr.callagent.drive.AuthorizationPort.State.TOKEN_EXPIRED)
            generation++;task?.cancel();busy=false
            message="현재 확인·전달을 중단했어요. 이미 접수된 요청과 저장 영수증은 유지하며 결과는 다시 확인해야 해요."
        }
        dialog?.dismiss();super.onStop()
    }
    override fun onResume() { super.onResume();if(::session.isInitialized)render() }
    override fun onDestroy() { closed=true;generation++;task?.cancel();sdk?.close();dialog?.dismiss();rows=emptyList();super.onDestroy() }
    override fun finish() { if(intent.getBooleanExtra("onboarding",false))finishOnboarding();super.finish() }
    companion object {
        private fun statusMessage(code:String)=when(code) {
            "GOOGLE_PLAY_SERVICES_REQUIRED" -> "Google Play 서비스가 있는 휴대전화에서 연결을 확인해 주세요. Google 연결은 완료되지 않았어요."
            "SENT" -> "Drive에 요청을 전달했어요. dot 실행과 결과는 아직 대기 중이에요."
            "DUPLICATE" -> "같은 작업이 이미 있어요. 새로 중복 전달하지 않았어요."
            "PROVENANCE_PENDING","READ_ONLY_CANDIDATE","LEGACY_CANDIDATE" -> "읽기 전용 제안을 저장했어요. 실제 dot 실행·사람·업무 연결은 확인 필요예요."
            "ERROR" -> "분석 오류를 수신했어요. 실패 이력을 남기고 직접 새로 시도할 수 있어요."
            "LOCAL_LIST_READ" -> "이 계정의 전달 기록을 다시 읽었어요."
            "PENDING" -> "결과를 기다리고 있어요."
            "ACCOUNT_SELECTION_CANCELLED","DRIVE_CONSENT_CANCELLED" -> "연결을 취소했어요. 전달 완료로 표시하지 않았어요."
            "DRIVE_CONSENT_REQUIRED" -> "Drive 동의가 필요해요. 연결 버튼을 눌러 확인해 주세요."
            else -> "연결·전달 상태 확인 필요 · 완료로 표시하지 않음"
        }
    }
}
