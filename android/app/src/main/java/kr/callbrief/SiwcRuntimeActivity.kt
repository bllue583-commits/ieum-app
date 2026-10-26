package kr.callbrief

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Only native approved configuration may enable authentication. No Intent, preference,
 * desktop credential or remote flag supplies it. This build deliberately has no approval. */
internal object SiwcLoginRuntime {
    class Dependencies(val transport:SiwcAuthTransport,val browser:SiwcBrowserSurface,
        val approvedConfig:(String?)->SiwcOAuthConfig?)
    fun dependencies(context:Context)=Dependencies(SiwcAuthHttp(),SiwcAndroidBrowser(context,SiwcRuntimeActivity::class.java),{null})
    // In-process instrumentation SPI; never populated from an exported component or result file.
    @Volatile internal var testDependencies:((Context)->Dependencies)?=null
    fun create(context:Context):SiwcLoginModel {
        val test=if(BuildConfig.DEBUG)testDependencies else null
        return SiwcLoginModel(context.applicationContext,test?.invoke(context) ?: dependencies(context))
    }
}

/** Retained across rotation and external-browser onStop. No login/refresh on Activity resume. */
internal class SiwcLoginModel(context:Context,private val deps:SiwcLoginRuntime.Dependencies):ViewModel() {
    data class Choice(val key:String,val client:String,val label:String)
    data class View(val ready:Boolean=false,val enabled:Boolean=false,val busy:Boolean=false,
        val status:String="SIWC_LOADING",val signedIn:Boolean=false,val returnRequested:Boolean=false,
        val choices:List<Choice> = emptyList(),val selected:String?=null)
    val view=MutableStateFlow(View())
    private val io=Executors.newSingleThreadExecutor()
    private val main=Executor{Handler(Looper.getMainLooper()).post(it)}
    private var sessions:SiwcSessionStore?=null
    private var login:SiwcBrowserLogin?=null
    @Volatile private var handle:SiwcBrowserLogin.Handle?=null
    @Volatile private var closed=false
    private val generation=java.util.concurrent.atomic.AtomicLong()
    private var selected:String?=null
    init {io.execute {runCatching {
        val s=SiwcSessionStore(context,deps.transport);sessions=s
        login=SiwcBrowserLogin(s,deps.transport,deps.browser,main)
        publish("SIWC_APPROVAL_REQUIRED")
    }.onFailure{view.value=View(ready=true,status="SIWC_VAULT_UNAVAILABLE")}}}
    private fun publish(status:String,returnRequested:Boolean=false) {
        if(closed)return
        val s=checkNotNull(sessions);val signed=s.registrations().any{it.key==s.activeKey() && it.state=="ACTIVE"}
        val choices=s.registrations().map{Choice(it.key,it.clientId,it.email.ifBlank{"저장된 ChatGPT 등록"}+" · "+it.state)}+
            s.incompleteRegistrationIds().map{Choice("draft:$it",it,"이전 동의 후 확인 대기 등록")}
        if(selected==null)selected=choices.firstOrNull{it.key==s.activeKey()}?.key ?: choices.firstOrNull()?.key
        if(selected!=null && choices.none{it.key==selected})selected=null
        val client=choices.singleOrNull{it.key==selected}?.client
        view.value=View(true,approvedConfig(client)!=null,false,status,signed,returnRequested,choices,selected)
    }
    private fun approvedConfig(client:String?)=deps.approvedConfig(client)?.takeIf{it.issuedClientId==client}
    fun select(key:String?){if(view.value.busy || closed)return;io.execute{
        if(key==null || view.value.choices.any{it.key==key}){selected=key;publish(view.value.status)}
    }}
    fun resume(){io.execute {if(!closed && sessions!=null && !view.value.busy)publish(if(view.value.signedIn)"SIWC_IDENTITY_VERIFIED" else view.value.status)}}
    fun start() {
        if(closed || !view.value.ready || view.value.busy)return
        // This guard runs before metadata discovery or binding a loopback socket.
        val choice=view.value.choices.singleOrNull{it.key==view.value.selected}
        val config=approvedConfig(choice?.client) ?: run{view.value=view.value.copy(status="SIWC_APPROVAL_REQUIRED");return}
        val run=generation.incrementAndGet()
        view.value=view.value.copy(busy=true,status="SIWC_BROWSER_PENDING")
        io.execute {if(!closed && generation.get()==run){
            val started=checkNotNull(login).start(config,choice?.key?.takeUnless{it.startsWith("draft:")},true){outcome->
                if(!closed)io.execute {if(!closed && generation.get()==run)publish(outcome.status,outcome.returnRequested)}
            };handle=started;if(closed || generation.get()!=run)started.close()
        }}
    }
    fun cancel(){generation.incrementAndGet();handle?.close();view.value=view.value.copy(busy=false,status="SIWC_LOGIN_CANCELLED")}
    fun signOut(){if(view.value.busy)return;io.execute{val s=sessions ?: return@execute;val key=s.activeKey();if(key.isNotBlank())publish(s.signOut(key))}}
    override fun onCleared(){closed=true;handle?.close();io.execute{login?.close();sessions?.close()};io.shutdown()}
}

class SiwcRuntimeActivity:ComponentActivity() {
    private lateinit var model:SiwcLoginModel
    private lateinit var body:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>)=SiwcLoginRuntime.create(applicationContext) as T
        })[SiwcLoginModel::class.java]
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24)}
        setContentView(ScrollView(this).apply{addView(body)})
        lifecycleScope.launch{model.view.collect{render(it)}}
    }
    private fun text(value:String)=TextView(this).apply{text=value;textSize=18f;setPadding(0,12,0,12)}
    private fun button(value:String,tagValue:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply{text=value;isAllCaps=false;tag=tagValue;isEnabled=enabled;setOnClickListener{action()}}
    private fun render(v:SiwcLoginModel.View) {
        body.removeAllViews();body.addView(text("ChatGPT 연결"))
        body.addView(text("이음에서 공식 브라우저 로그인을 시작하고 이 화면으로 돌아와요. Google 연결과 별도로 보관해요."))
        body.addView(text("첫 연결에는 본인 계정의 identity·플랜 사용량·지속 접근 권한 동의가 필요해요. 공개 음성의 전사와 합성 업무 자료만 시험하며 원음은 전송하지 않아요. 추가 credits 구매나 자동 과금 전환은 하지 않아요."))
        body.addView(text(if(!v.enabled)"새 로그인·사용량 동의 승인을 기다리고 있어요. 현재 연결 버튼은 비활성 상태예요." else "로그인 동의와 각 분석의 전사 전송·사용량 승인은 별개예요."))
        body.addView(button("Continue with ChatGPT","siwc_continue",v.ready && v.enabled && !v.busy){model.start()})
        for(choice in v.choices)body.addView(button((if(v.selected==choice.key)"선택됨 · " else "등록 선택 · ")+choice.label,"siwc_choice",!v.busy){model.select(choice.key)})
        if(v.busy)body.addView(button("로그인 중단","siwc_cancel"){model.cancel()})
        if(v.signedIn)body.addView(button("이음에서 ChatGPT 로그아웃","siwc_sign_out",!v.busy){model.signOut()})
        body.addView(text(when(v.status){
            "SIWC_IDENTITY_VERIFIED"->"현재 기기에서 로그인 정보를 검증했어요. 모델 분석 실행은 별도예요."
            "SIWC_LOGIN_CANCELLED"->"로그인을 중단했어요."
            "SIWC_BROWSER_PENDING"->"공식 브라우저의 동의 결과를 기다리고 있어요."
            "SIWC_APPROVAL_REQUIRED","SIWC_LOADING"->"연결 대기 · 외부 분석 미실행"
            else->"연결 상태 확인 필요 · 완료로 표시하지 않았어요."
        }).apply{tag="siwc_status"})
        body.addView(button("닫기","siwc_close"){model.cancel();finish()})
    }
    override fun onResume(){super.onResume();if(::model.isInitialized)model.resume()}
}
