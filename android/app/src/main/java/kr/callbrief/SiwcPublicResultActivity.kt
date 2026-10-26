package kr.callbrief

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Manual public sandbox. The two remote stages are explicitly fixtures in this screen. */
class SiwcPublicResultActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var pilot:AndroidLocalSttPilot?=null
    private var active:PublicAudioOperation?=null
    private var phase=0
    private var busy=false
    private var transcripts=""
    private var candidates:JSONObject?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!BuildConfig.SYNTHETIC_TOOLS_ENABLED){finish();return}
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24)}
        setContentView(ScrollView(this).apply{addView(body)});render()
    }
    private fun label(value:String)=TextView(this).apply{text=value;textSize=18f;setPadding(0,12,0,12)}
    private fun button(value:String,tagValue:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply{text=value;isAllCaps=false;tag=tagValue;isEnabled=enabled;setOnClickListener{action()}}
    private fun render(message:String="") {
        if(isDestroyed || isFinishing)return
        body.removeAllViews();body.addView(label("공개 음성 · 합성 결과파일 시험"))
        body.addView(label("앱의 공개 한국어·영어 WAV를 실제 기기 JNI로 전사해요. 이후 분석·맥락 응답은 합성 fixture예요. ChatGPT 로그인·외부 모델 호출·원음 전송은 0회예요."))
        body.addView(label("시험 결속: 공개 합성 담당·회사·통합 시험 업무, 2026-10-01 10:00 UTC. 사람·업무·시각은 다음 단계에서 직접 확인해요."))
        body.addView(label(transcripts))
        candidates?.getJSONArray("candidates")?.let{a->for(i in 0 until a.length()) {
            val c=a.getJSONObject(i);body.addView(label(c.getJSONObject("artifact").getJSONObject("analysis").getString("summary")))
            body.addView(label("검토할 할일: "+c.getJSONObject("candidate").getJSONArray("items").toString()))
        }}
        body.addView(button(when(phase){0->"공개 음성 실제 전사";1->"표시한 시험 사람·업무·시각 연결";2->"합성 응답 파일 저장·수신·후보 확인";3->"표시한 합성 요약·할일 직접 검토 후 저장";else->"검토 저장 완료"},"siwc_public_next",!busy && phase<4){advance()})
        if(busy)body.addView(button("처리 중단","siwc_public_stop"){active?.cancel()})
        body.addView(label(message).apply{tag="siwc_public_status"})
        body.addView(button("닫기","siwc_public_close"){active?.cancel();finish()})
    }
    private fun advance() {
        if(busy)return
        val operation=PublicAudioOperation();val existing=pilot
        if(existing!=null)operation.attach(existing::cancel)
        active=operation;busy=true;render()
        lifecycleScope.launch {
            try {
                val answer=withContext(Dispatchers.IO){runCatching {
                    val p=existing ?: AndroidLocalSttPilot(applicationContext,"siwc-public-v1").also{pilot=it}
                    if(existing==null)operation.attach(p::cancel);operation.requireRunning()
                    when(phase){0->p.integratedAnalyse();1->p.integratedBind(true);2->p.siwcPublicCandidates();else->{
                        val a=checkNotNull(candidates).getJSONArray("candidates")
                        p.siwcPublicReview((0 until a.length()).associate{val c=a.getJSONObject(it);c.getString("id") to c.getString("digest")},true)
                    }}
                }}
                busy=false;active=null
                if(operation.cancelled)render("중단을 요청했어요. 이미 저장한 자료는 보존해요.")
                else answer.onSuccess{result->
                    if(phase==0)transcripts=result.getJSONArray("stages").let{a->(0 until a.length()).joinToString("\n\n"){a.getJSONObject(it).getString("transcript")}}
                    if(phase==2)candidates=result
                    phase++;render(if(phase==4)"명시 검토한 합성 결과를 기기 보호 저장소에 보관했어요. 실제 Astra 호출은 없었어요." else "표시한 원문·시험 내용을 확인하고 다음 단계를 선택해 주세요.")
                }.onFailure{render("현재 출처·revision·검토 gate를 확인하지 못해 보류했어요. 완료로 표시하지 않았어요.")}
            } finally {if(active===operation){active=null;busy=false}}
        }
    }
    override fun onDestroy(){active?.cancel();pilot?.cancel();pilot?.close();super.onDestroy()}
}
