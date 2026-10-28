package kr.callbrief

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Manual public sandbox. The two remote stages are explicitly fixtures in this screen. */
class SiwcPublicResultActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private lateinit var model:SiwcPublicResultModel
    internal val testState get()=model
    private val phase get()=model.phase
    private val busy get()=model.busy
    private val transcripts get()=model.transcripts
    private val candidates get()=model.candidates
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!BuildConfig.SYNTHETIC_TOOLS_ENABLED){finish();return}
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        model=ViewModelProvider(this)[SiwcPublicResultModel::class.java]
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24)}
        setContentView(ScrollView(this).apply{addView(body)})
        lifecycleScope.launch{model.changes.collect{render()}}
    }
    private fun label(value:String)=TextView(this).apply{text=value;textSize=18f;setPadding(0,12,0,12)}
    private fun button(value:String,tagValue:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply{text=value;isAllCaps=false;tag=tagValue;isEnabled=enabled;setOnClickListener{action()}}
    private fun render(message:String=model.message) {
        if(isDestroyed || isFinishing)return
        body.removeAllViews();body.addView(label("공개 음성 · 합성 결과파일 시험"))
        body.addView(label("앱의 공개 한국어·영어 WAV를 실제 기기 JNI로 전사해요. 이후 분석·맥락 응답은 합성 fixture예요. ChatGPT 로그인·외부 모델 호출·원음 전송은 0회예요."))
        body.addView(label("시험 결속: 공개 합성 담당·회사·통합 시험 업무, 2026-10-01 10:00 UTC. 사람·업무·시각은 다음 단계에서 직접 확인해요."))
        body.addView(label(transcripts))
        candidates?.getJSONArray("candidates")?.let{a->for(i in 0 until a.length()) {
            val c=a.getJSONObject(i);body.addView(label(c.getJSONObject("artifact").getJSONObject("analysis").getString("summary")))
            body.addView(label("검토할 할일: "+c.getJSONObject("candidate").getJSONArray("items").toString()))
        }}
        body.addView(button(when(phase){0->"공개 음성 실제 전사";1->"표시한 시험 사람·업무·시각 연결";2->"합성 응답 파일 저장·수신·후보 확인";3->"표시한 합성 요약·할일 직접 검토 후 저장";else->"검토 저장 완료"},"siwc_public_next",!busy && phase<4){model.advance()})
        if(busy)body.addView(button("처리 중단","siwc_public_stop"){model.cancel()})
        body.addView(label(message).apply{tag="siwc_public_status"})
        body.addView(button("닫기","siwc_public_close"){model.cancel();finish()})
    }
    override fun onDestroy(){if(isFinishing && ::model.isInitialized)model.cancel();super.onDestroy()}
}
