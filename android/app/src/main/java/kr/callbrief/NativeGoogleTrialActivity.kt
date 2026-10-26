package kr.callbrief

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kr.callagent.drive.DriveRestTransport
import org.json.JSONObject

/** Foreground, explicit synthetic trial through normal SAF and native encrypted storage. */
internal open class NativeGoogleTrialActivity:ComponentActivity() {
    private lateinit var root:LinearLayout
    private lateinit var session:GoogleConnectionSession
    private var documents=emptyList<SourceDocument>()
    private var jobs=emptyList<GoogleRelayStore.JobView>()
    private var setup=""
    private var message=""
    private var busy=false;private var closed=false
    private var generation=0L;private var operation:Job?=null
    @Volatile private var active:GoogleNativePipeline?=null
    private var accountEpoch=-1L;private var accountOwner=""
    private var dialog:AlertDialog?=null
    private fun alive()=!closed && !isDestroyed && !isFinishing
    protected open fun selectedSource(scoped:android.content.Context,settings:SecureSettings):SelectedRecordingSource = SafSelectedRecordingSource(scoped,settings,settings.snapshot().getString("treeUri"))
    protected open fun pipeline(settings:SecureSettings):GoogleNativePipeline = GoogleNativePipeline(this,session,settings,
        { selectedSource(AccountStorageContext(this,session.ownerBinding()),settings) },DriveRestTransport(session,GoogleDriveHttp(session)))
    private fun settings()=SecureSettings(AccountStorageContext(this,session.ownerBinding()))
    private val folderPicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(!alive() || result.resultCode!=RESULT_OK)return@registerForActivityResult
        val uri=result.data?.data ?: return@registerForActivityResult
        if(session.epoch()!=accountEpoch || session.ownerBinding()!=accountOwner)return@registerForActivityResult
        val s=settings()
        val changed=FolderSelectionChange.replace(uri.toString(),{s.snapshot().optString("treeUri")},
            {contentResolver.takePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION)},
            {value->s.update { it.put("treeUri",value);it.put("statusMessage","Google 합성 시험 폴더 선택 · 아직 원음 미전달") }},
            {contentResolver.releasePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION)})
        message=if(changed in listOf(FolderSelectionChange.Result.SAVED,FolderSelectionChange.Result.SAVED_PREVIOUS_GRANT_RETAINED)) "읽기 폴더를 선택했어요. 합성 파일을 확인하고 직접 전달해 주세요." else "폴더 선택 저장을 확인하지 못했어요. 이전 자료는 유지했어요."
        documents=emptyList();render()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v,i->v.setPadding(24,i.systemWindowInsetTop+16,24,i.systemWindowInsetBottom+16);i } };setContentView(root)
        try { session=GoogleConnectionSession.get(this) } catch(_:Exception) { root.addView(TextView(this).apply { text="Google 연결 정보를 읽지 못했어요. 저장된 자료는 유지했어요.";textSize=18f });return }
        render()
    }
    private fun label(t:String,bold:Boolean=false)=TextView(this).apply { text=t;textSize=if(bold)22f else 18f;setPadding(0,16,0,16);if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD) }
    private fun button(t:String,f:()->Unit)=Button(this).apply { text=t;textSize=18f;isAllCaps=false;minHeight=(56*resources.displayMetrics.density).toInt();isEnabled=!busy;setOnClickListener { if(alive()&&!busy)f() } }
    private fun render() {
        if(!alive()||!::session.isInitialized)return
        root.removeAllViews();root.addView(label("Google 합성 음성 검토",true))
        val scroll=ScrollView(this);val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(label("폴더 권한 → 제외 확인 → 암호 사본 → 소스·lease 등록 → Drive 전달 → 결과 확인. 실제 통화와 자동 전송은 사용하지 않아요."))
        if(session.state()!=kr.callagent.drive.AuthorizationPort.State.ACTIVE) {
            body.addView(label("Google 계정과 Drive 접근 확인이 필요해요. 아직 원음을 읽거나 전달하지 않았어요."))
            body.addView(button("Google 계정·Drive 연결 확인") { startActivity(Intent(this,ConnectionCenterActivity::class.java)) })
        } else {
            val state=runCatching { settings().snapshot() }.getOrNull()
            body.addView(label("확인한 계정: ${session.driveAccount()}"))
            body.addView(button(if(state?.optString("treeUri").isNullOrBlank()) "합성 WAV 폴더 선택" else "합성 WAV 폴더 변경") {
                accountEpoch=session.epoch();accountOwner=session.ownerBinding()
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            })
            body.addView(button("원음 저장소 상태 확인") { runPipeline { setup=it.setupState();"저장소 상태: $setup" } })
            if(setup=="EMPTY")body.addView(button("새 빈 원음 저장소 준비") { confirm("새 빈 저장소 준비","이 계정의 새 원음 저장소만 준비해요. 기존 자료와 원본 파일은 지우지 않아요.","준비하기") { runPipeline { it.initializeEmpty();setup=it.setupState();"암호 원음 저장소를 준비했어요." } } })
            body.addView(button("선택 폴더의 합성 WAV 목록 확인") { scan() })
            body.addView(label("저장소 상태: ${setup.ifBlank { "아직 확인하지 않음" }} · 합성 WAV만 최대5MB"))
            documents.forEach { d ->body.addView(button(d.name+" · 확인 후 전달") { confirmSource(d) }) }
            body.addView(button("이 계정의 전달 결과 다시 읽기") { runPipeline { jobs=it.jobs();"전달 기록을 다시 읽었어요." } })
            jobs.forEach { job ->
                body.addView(label(when {job.phase=="CANCELLED"->"이전 시도 중단 · 이력 보존";job.remoteState=="ERROR"->"외부 오류 수신";job.rawText!=null->"읽기 전용 제안 수신 · 기준 기록 미변경";else->"전달 단계: ${job.phase} · dot 결과는 별도 확인"},true))
                if(job.phase in listOf("SENT","CANDIDATE"))body.addView(button("이 작업 결과 회수") { runPipeline { p->val status=p.poll(job.id);jobs=p.jobs();"결과 확인: $status · 업무·완료 미반영" } })
                if(job.remoteState in listOf("ERROR","CANCELLED") && job.phase!="CANCELLED")body.addView(button("오류 작업 명시 재시도") { confirm("새 시도","오류 영수증을 보존하고 이전 시도를 로컬에서 막은 뒤 새 작업으로 전달해요.","재시도") { runPipeline { p->val r=p.retry(job.id);jobs=p.jobs();"새 시도: ${r.outcome}" } } })
                body.addView(button("소스·lease·앱 세션 등록 근거") { runPipeline { p->val registration=p.registration(job.id);showText("로컬 소스 등록 근거",registration.toString(2));"로컬 등록 근거예요. Drive connector가 native 세션을 인증한 것은 아니에요." } })
                if(job.rawText!=null)body.addView(button("전사·분석 제안 원문") { showText("읽기 전용 제안",job.rawText+"\n\n"+job.analysis.orEmpty()) })
            }
        }
        if(message.isNotBlank())body.addView(label(message));if(busy)body.addView(label("확인 중이에요. 화면을 나가면 진행 중인 전달을 중단해요."))
        root.addView(button("목록으로 돌아가기") { finish() })
    }
    private fun showText(title:String,value:String) {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {
            val expected=generation;val e=session.epoch()
            runOnUiThread { if(alive() && generation==expected && session.epoch()==e)showText(title,value) };return
        }
        if(!alive())return
        dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(label(value)) }).setPositiveButton("닫기",null).create();ProtectedDialogWindow.show(dialog!!)
    }
    private fun confirm(title:String,message:String,yes:String,action:()->Unit) {
        dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("취소",null).setPositiveButton(yes) { _,_->if(alive())action() }.create();ProtectedDialogWindow.show(dialog!!)
    }
    private fun confirmSource(d:SourceDocument) {
        val confirm=CheckBox(this).apply { text="승인된 합성 음성이며 실제 전화 통화가 없고, 제가 전달할 권한이 있어요.";textSize=18f }
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(label(d.name));addView(confirm);addView(label("현재 제외 규칙을 먼저 검사한 뒤 이 계정의 암호 원음 사본에서 전달해요. 사람·업무·시각은 추정하지 않아요.")) }
        dialog=AlertDialog.Builder(this).setTitle("선택 합성 원음 전달").setView(form).setNegativeButton("취소",null).setPositiveButton("확인하고 전달",null).create();ProtectedDialogWindow.show(dialog!!)
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!confirm.isChecked)return@setOnClickListener
            dialog?.dismiss();runPipeline { p->val r=p.importAndSubmit(d,true);jobs=p.jobs();"암호 저장소에서 요청 처리: ${r.first.outcome} · dot 실행은 아직 확인 필요" }
        }
    }
    private fun scan()=operate {
        val s=settings();val source=selectedSource(AccountStorageContext(this,session.ownerBinding()),s)
        val inventory=withContext(Dispatchers.IO) { source.inventory() }
        documents=inventory.documents.filter { it.mime in listOf("audio/wav","audio/x-wav") && it.size in 44..5_242_880 && !source.excluded(it) }
        message="메타데이터만 확인했어요. 제외 파일은 읽지 않았어요.${if(!inventory.complete) " 목록 일부 확인 · 이름 변경 추정 보류" else ""}"
    }
    private fun operate(f:suspend ()->Unit) {
        if(!alive()||busy)return
        val run=++generation;busy=true;render()
        operation=lifecycleScope.launch {
            try { f() } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(alive()&&run==generation)message=if(e.message=="DOT_PROFILE_ACCOUNT_UNSUPPORTED")"Drive 계정은 연결됐지만 현재 dot 인수 계약의 계정 범위와 맞지 않아 원음 게시를 보류했어요. 일반 계정 지원은 별도 확인이 필요해요." else "완료를 확인하지 못했어요. Google 연결·폴더 권한·저장소·합성 승인 상태를 확인해 주세요. 기존 기록은 유지했어요." }
            finally { active=null;if(run==generation){busy=false;if(alive())render()} }
        }
    }
    private fun runPipeline(f:(GoogleNativePipeline)->String)=operate {
        val v=session.view();val s=settings()
        val result=withContext(Dispatchers.IO) { pipeline(s).use { p ->active=p;session.commit(v.epoch,v.ownerId) {};f(p) } }
        currentCoroutineContext().ensureActive();session.requireEpoch(v.epoch);message=result
    }
    override fun onResume() { super.onResume();if(::session.isInitialized) { if(accountEpoch!=session.epoch() || accountOwner!=session.ownerBinding()) {documents=emptyList();jobs=emptyList();setup="";accountEpoch=session.epoch();accountOwner=session.ownerBinding()};render() } }
    override fun onStop() { if(busy){active?.cancel();session.invalidate(kr.callagent.drive.AuthorizationPort.State.TOKEN_EXPIRED);generation++;operation?.cancel();busy=false};dialog?.dismiss();super.onStop() }
    override fun onDestroy() { closed=true;active?.cancel();operation?.cancel();dialog?.dismiss();super.onDestroy() }
}
