package kr.callbrief

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit local synthetic intake. Does not construct Drive transport or an OAuth bridge. */
internal open class ReadOnlyProposalActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private var entries=emptyList<ReadOnlyProposalStore.Entry>()
    private var message: String?=null
    private var busy=false
    private var closed=false
    private var generation=0L
    private var job: Job?=null
    private var cancel=AtomicBoolean(false)
    private var request: ByteArray?=null
    private var detailsDialog: android.app.AlertDialog?=null
    private var accountContext: android.content.Context?=null
    private var accountOwner=""
    private var accountEpoch=-1L
    private fun accountUnchanged(): Boolean = runCatching {
        val v=GoogleConnectionSession.get(this).view();v.ownerId==accountOwner && v.epoch==accountEpoch
    }.getOrDefault(false)
    private fun alive()=!closed && !isFinishing && !isDestroyed
    protected open fun createStore(check:()->Unit)=ReadOnlyProposalStore(checkNotNull(accountContext),check)
    private val requestPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null) { request=null;message="자료 선택을 취소했어요. 저장된 기록은 유지해요.";render() }
        else readJson(uri) { bytes ->
            require(ReadOnlyProposalStore.PINS.any { kr.callagent.drive.DriveRelay.sha256(bytes)==it.requestSha }) { "SYNTHETIC_REQUEST_NOT_APPROVED" }
            request=bytes;message="이제 같은 합성 건의 결과 JSON을 선택해 주세요.";render();resultPicker.launch(arrayOf("application/json"))
        }
    }
    private val resultPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val frozen=request;request=null
        if(uri==null || frozen==null) { message="자료 선택을 취소했어요. 저장된 기록은 유지해요.";render() }
        else readJson(uri) { result -> import(frozen,result) }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            val v=GoogleConnectionSession.get(this).view();accountOwner=v.ownerId;accountEpoch=v.epoch
            accountContext=if(accountOwner.isBlank())this else AccountStorageContext(this,accountOwner)
        } catch(_:Exception) { accountContext=null }
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(221,241,248))
            setOnApplyWindowInsetsListener { v,i -> v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i }
        };setContentView(root);render();reload()
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun label(value:String,size:Float=18f,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply {
        text=if(resources.configuration.fontScale>=1.25f)when(value) {
            "합성 인수 예시 불러오기" -> "합성 인수 예시\n불러오기"
            "선정 합성 자료 JSON 선택" -> "선정 합성 자료\nJSON 선택"
            else -> value
        }else value
        contentDescription=value;textSize=18f;isAllCaps=false;minHeight=dp(56);isEnabled=!busy
        setTextColor(Color.rgb(13,34,112));setPadding(dp(12),dp(8),dp(12),dp(8))
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(28).toFloat();setStroke(dp(1),Color.rgb(188,216,230)) }
        setOnClickListener { if(alive()&&!busy)action() }
    }
    private fun card(parent:LinearLayout)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12))
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=dp(20).toFloat() }
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
    }
    private fun render() {
        if(!alive() || !::root.isInitialized)return
        root.removeAllViews()
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(label("분석 제안",24f,true).apply { tag="ro1_heading" },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("뒤로") { finish() },LinearLayout.LayoutParams(dp(96),-2));root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=true;tag="ro1_scroll" };val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val state=card(body);state.tag="ro1_connection"
        state.addView(label(ReadOnlyProposalStore.CONNECTION_MESSAGE,bold=true))
        state.addView(label("합성 로컬 인수 · 앱 업무에 미반영").apply { tag="ro1_mode" })
        state.addView(label("외부 전송 확인과 휴대전화 인증은 별개예요."))
        message?.let { state.addView(label(it)) };if(busy)state.addView(label("기기에 저장한 자료를 확인하고 있어요."))
        if(entries.isEmpty()&&!busy)card(body).addView(label("저장한 읽기 전용 제안이 없어요. 아래에서 합성 자료만 인수할 수 있어요."))
        entries.forEach { entry ->
            val c=card(body);c.tag="ro1_${entry.pin.id}";c.addView(label(entry.pin.label,22f,true))
            val v=entry.view
            if(v==null) c.addView(label("현재 이 자료를 표시할 수 없어요. 일반 통화·메모·완료 기록은 유지해요."))
            else {
                c.addView(label("읽기 전용 분석 제안 · 기준 기록 변경 없음",bold=true))
                c.addView(label("원자료 출처 확인 필요 · 사람·업무 연결 미확인"))
                if(entry.nativeRecordsChanged)c.addView(label("인수 후 앱 기록이 바뀌었어요. 이 제안은 앱 기록에 연결·반영하지 않았어요.",bold=true))
                val analysis=JSONObject(v.rawAnalysisJson ?: "{}")
                c.addView(label(analysis.optString("summary","전달된 분석 제안의 요약이 없어요.")).apply { tag="ro1_summary" })
                c.addView(button("원문·근거 보기") { details(v.rawText,v.rawAnalysisJson.orEmpty()) })
                c.addView(label("수신본 ${v.receipt.revision}차 · 로컬 저장 영수증 보존"))
            }
        }
        val actions=card(body);actions.addView(label("합성 자료 인수",22f,true))
        actions.addView(label("계정 연결이나 실제 Drive 자동 수신을 시험하는 동작이 아니에요. 업무·완료 상태는 바꾸지 않아요."))
        if(BuildConfig.SYNTHETIC_TOOLS_ENABLED) {
            actions.addView(button("합성 인수 예시 불러오기") { importExample() }.apply { tag="ro1_import_example" })
            actions.addView(button("선정 합성 자료 JSON 선택") { request=null;requestPicker.launch(arrayOf("application/json")) })
        } else actions.addView(label("합성 인수는 개발 검증본에서만 사용할 수 있어요."))
    }
    private fun details(raw:String,analysis:String) {
        if(!alive())return
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
        body.addView(label("전달된 전사 원문",22f,true));body.addView(label(raw))
        body.addView(label("분석 제안과 정확한 인용",22f,true));body.addView(label("인용이 맞아도 의미·상대·업무·기한이 확정된 것은 아니에요."))
        val items=JSONObject(analysis).optJSONArray("observations")
        if(items!=null)for(i in 0 until items.length()) {
            val obs=items.getJSONObject(i);val c=card(body);c.addView(label("분석 제안 ${i+1}",20f,true));c.addView(label(obs.optString("value")))
            val evidence=obs.optJSONArray("evidence");if(evidence!=null)for(k in 0 until evidence.length())c.addView(label("원문 근거: ${evidence.getJSONObject(k).optString("quote")}"))
        }
        val dialog=android.app.AlertDialog.Builder(this).setTitle("읽기 전용 원문·근거").setView(ScrollView(this).apply { addView(body) }).setPositiveButton("닫기",null).create()
        detailsDialog?.dismiss();detailsDialog=dialog
        ProtectedDialogWindow.show(dialog);dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply { textSize=18f;minHeight=dp(56) }
    }
    private fun operation(action: suspend (()->Unit)->Unit) {
        if(!alive()||busy)return
        val current=++generation;cancel=AtomicBoolean(false);val signal=cancel;busy=true;render()
        job=lifecycleScope.launch {
            val check={ if(signal.get() || !accountUnchanged())throw CancellationException("LOCAL_INTAKE_CANCELLED");Unit }
            try { action(check) }
            catch(e:CancellationException){throw e}
            catch(_:Exception){if(alive()&&current==generation)message="이 자료의 인수를 확인하지 못했어요. 기존 기록은 유지해요."}
            finally { if(current==generation){busy=false;if(alive())render()} }
        }
    }
    private fun reload()=operation { check ->
        val loaded=withContext(Dispatchers.IO) { check();createStore(check).use { it.list() } }
        currentCoroutineContext().ensureActive();if(alive())entries=loaded
    }
    private fun importExample()=operation { check ->
        val imported=withContext(Dispatchers.IO) {
            check();val req=ReadOnlyProposalStore.readBounded(assets.open("ro1-synthetic/request.json"));val res=ReadOnlyProposalStore.readBounded(assets.open("ro1-synthetic/result.json"))
            createStore(check).use { store -> val reply=store.importSynthetic(req,res);reply to store.list() }
        }
        currentCoroutineContext().ensureActive();if(alive()){entries=imported.second;message=importMessage(imported.first.status)}
    }
    private fun readJson(uri:Uri,done:(ByteArray)->Unit)=operation { check ->
        require(uri.scheme=="content") { "LOCAL_JSON_DOCUMENT_REQUIRED" }
        val bytes=withContext(Dispatchers.IO) { check();ReadOnlyProposalStore.readBounded(contentResolver.openInputStream(uri) ?: error("LOCAL_JSON_UNREADABLE")) }
        currentCoroutineContext().ensureActive();if(alive())done(bytes)
    }
    private fun import(req:ByteArray,res:ByteArray) {
        // readJson owns the coroutine; dispatch store work in a following UI turn after it releases busy.
        val expected=generation;val pendingSignal=cancel
        root.post { if(alive()&&!busy && expected==generation && !pendingSignal.get() &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))operation { check ->
            val imported=withContext(Dispatchers.IO) { ReadOnlyProposalStore.approved(req,res);createStore(check).use { store -> store.importSynthetic(req,res) to store.list() } }
            currentCoroutineContext().ensureActive();if(alive()){entries=imported.second;message=importMessage(imported.first.status)}
        } }
    }
    private fun importMessage(status:kr.callagent.drive.SqliteProfileInbox.Status)=when(status) {
        kr.callagent.drive.SqliteProfileInbox.Status.DUPLICATE -> "같은 합성 자료가 이미 저장돼 있어요. 기존 영수증을 유지해요."
        kr.callagent.drive.SqliteProfileInbox.Status.PROVENANCE_PENDING,kr.callagent.drive.SqliteProfileInbox.Status.READ_ONLY_CANDIDATE -> "합성 검토 자료를 기기에 저장했어요. 업무·완료 상태에는 반영하지 않았어요."
        else -> "합성 자료 인수가 보류됐어요. 기존 기록은 유지해요."
    }
    override fun onResume() { super.onResume();if(::root.isInitialized && !accountUnchanged()) { entries=emptyList();request=null;detailsDialog?.dismiss();finish() } }
    override fun onStop() { cancel.set(true);job?.cancel();detailsDialog?.dismiss();super.onStop() }
    override fun onDestroy() { closed=true;cancel.set(true);job?.cancel();request=null;detailsDialog?.dismiss();super.onDestroy() }
}
