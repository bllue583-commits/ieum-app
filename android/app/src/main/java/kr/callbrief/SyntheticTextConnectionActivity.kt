package kr.callbrief

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject

/** Browser handoff and the app's verified native return are distinct. Intent extras
 * cannot supply identity, generation, result, or a connection-success receipt. */
internal open class SyntheticTextConnectionActivity:ComponentActivity() {
    private lateinit var root:LinearLayout
    private lateinit var coordinator:SyntheticTextForegroundCoordinator
    private var data=SyntheticTextForegroundCoordinator.Result("NATIVE_RETURN_UNSUPPORTED",null)
    private var busy=false;private var closed=false;private var generation=0L;private var task:Job?=null
    private var localFixture=false;private var message=""
    protected open fun createPort():SyntheticTextGenerationPort=if(localFixture)McpSyntheticTextGenerationPort(SyntheticTextLocalFixtureChannel(this)) else UnavailableNativeSyntheticTextPort()
    protected open fun createCoordinator(port:SyntheticTextGenerationPort)=SyntheticTextForegroundCoordinator(this,port)
    private fun alive()=!closed&&!isFinishing&&!isDestroyed
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        localFixture=BuildConfig.SYNTHETIC_TOOLS_ENABLED&&intent.getBooleanExtra("local_fixture",false)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(221,241,248));setOnApplyWindowInsetsListener { v,i ->v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i } };setContentView(root)
        coordinator=createCoordinator(createPort());render();load()
    }
    private fun dp(n:Int)=(resources.displayMetrics.density*n+.5f).toInt()
    private fun text(t:String,size:Float=18f)=TextView(this).apply { text=t;textSize=size;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(8),0,dp(8));isSaveEnabled=false;isSaveFromParentEnabled=false }
    private fun button(t:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply { text=t;textSize=18f;isAllCaps=false;minHeight=dp(56);isEnabled=enabled&&!busy;setOnClickListener { if(alive()&&!busy)action() } }
    private fun state(t:String)=when(t) {
        "NATIVE_RETURN_UNSUPPORTED"->"앱의 결과 연결 미확인 · 합성 작업 미전달"
        "IDLE"->"새 합성 확인 시작 전";"PREPARED","STARTING","START_CONFIRMATION_REQUIRED"->"접수 확인 필요 · 같은 시도 보존"
        "RESULT_RECEIVED"->"미적용 합성 제안 수신";"EXPIRED"->"이 합성 확인 작업은 만료됐어요. 저장 기록은 보존해요."
        "AUTH_HELD","SCOPE_HELD"->"현재 연결 범위를 확인해야 해요. 기존 기록은 유지해요."
        "CONFLICT","RESPONSE_REVIEW","STORE_REVIEW"->"결과 일치를 확인하지 못했어요. 이전 기록은 유지해요."
        "UNKNOWN"->"접수·저장 확인 필요 · 같은 키로 다시 확인해요."
        else->"결과 확인 필요 · 완료로 표시하지 않았어요."
    }
    private fun render() {
        if(!alive())return;root.removeAllViews();root.addView(text("고정 합성 텍스트 확인",24f));root.addView(button("연결 화면으로 돌아가기"){finish()})
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        body.addView(text(if(localFixture)"이 기기 안의 합성 계약 검증 · 실제 Site 연결 아님" else "고정 합성 문장으로 연결을 확인해요. 실제 통화·문자·메일은 사용하지 않아요.").apply { tag="sg_origin" })
        body.addView(text(state(data.state),22f).apply { tag="sg_status" })
        if(message.isNotBlank())body.addView(text(message))
        if(!localFixture) {
            body.addView(text("브라우저에서 합성 작업을 열 수 있어요. 이 앱으로 결과를 받아오는 연결은 아직 확인하지 못했어요. 웹 화면을 열어도 앱의 연결·수신 완료로 표시하지 않아요."))
            body.addView(button("비공개 합성 웹 화면 열기") { try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(SyntheticTextContract.SITE)));message="웹 화면 열기를 요청했어요. 앱의 결과 연결 상태는 그대로예요.";render() } catch(_:Exception) { message="웹 화면을 열지 못했어요. 앱의 결과 연결은 확인되지 않았어요.";render() } }.apply { tag="sg_browser_handoff" })
            body.addView(button("앱에서 새 합성 연결 시작",false){}.apply { tag="sg_native_start" })
            if(BuildConfig.SYNTHETIC_TOOLS_ENABLED)body.addView(button("기기 안 합성 흐름 검증") { startActivity(Intent(this,SyntheticTextConnectionActivity::class.java).putExtra("local_fixture",true)) }.apply { tag="sg_local_fixture" })
        } else {
            body.addView(text(SyntheticTextContract.TEXT))
            body.addView(button("새 합성 확인 시작") { run { coordinator.startExplicit() } }.apply { tag="sg_start" })
            data.view?.let { v ->
                body.addView(button("같은 시도 확인·재개",data.state!="EXPIRED") { run { coordinator.resumeExplicit(v.proof) } }.apply { tag="sg_resume" })
                body.addView(button("이번 결과 다시 읽기",data.state!="EXPIRED") { run { coordinator.readOnly(v.proof) } }.apply { tag="sg_read" })
                if(data.state=="RESULT_RECEIVED"&&v.result!=null) {
                    val result=JSONObject(v.result);body.addView(text("합성 요약 · 업무에 미적용",22f));body.addView(text(result.getString("summary")).apply { tag="sg_summary" })
                    val tasks=result.getJSONArray("tasks");for(i in 0 until tasks.length()) { val t=tasks.getJSONObject(i);body.addView(text("합성 할일 제안: "+t.getString("title")));body.addView(text("담당: "+t.getString("owner")+" · "+if(t.isNull("due"))"기한 미확정" else t.getString("due")));body.addView(text("제안 상태: "+kr.callbrief.core.TaskStatus.fromWire(t.getString("status")).label+" · 미적용"));body.addView(text("원문 근거: "+t.getJSONObject("evidence").getString("quote"))) }
                    body.addView(text("실제 모델 실행·업무 완료를 확인한 결과가 아니에요. 통화·업무·완료 기록에 적용하지 않아요."))
                }
            }
        }
        if(busy)body.addView(text("합성 영수증을 확인 중이에요. 같은 동작을 반복하지 않아도 돼요."))
    }
    private fun load()=run { coordinator.load() }
    private fun run(action:suspend ()->SyntheticTextForegroundCoordinator.Result) {
        if(!alive()||busy)return;val ticket=++generation;busy=true;render()
        task=lifecycleScope.launch {
            try { val next=action();ensureActive();if(alive()&&ticket==generation)data=next }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(alive()&&ticket==generation)data=SyntheticTextForegroundCoordinator.Result("STORE_REVIEW",null) }
            finally { if(ticket==generation) { busy=false;if(alive())render() } }
        }
    }
    override fun onResume() { super.onResume();if(::coordinator.isInitialized&&!busy)load() }
    override fun onStop() { generation++;task?.cancel();busy=false;super.onStop() }
    override fun onDestroy() { closed=true;generation++;task?.cancel();super.onDestroy() }
}
