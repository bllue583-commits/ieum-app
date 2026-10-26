package kr.callbrief

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** User-initiated public-audio exercise only; no microphone/storage/account permissions. */
class AndroidLocalSttActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var pilot:AndroidLocalSttPilot?=null
    private var running=false
    private var seen=false
    private var unclassified=false
    private var integrationPilot:AndroidLocalSttPilot?=null
    private var integrationStep=0
    private var integrationResult:JSONObject?=null
    private var integrationStages=org.json.JSONArray()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!BuildConfig.SYNTHETIC_TOOLS_ENABLED){finish();return}
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val scroll=ScrollView(this);body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,40,24,40)};scroll.addView(body);setContentView(scroll);render()
    }
    private fun label(text:String)=TextView(this).apply{this.text=text;textSize=18f;setPadding(0,12,0,12)}
    private fun button(text:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply{this.text=text;textSize=18f;isAllCaps=false;minHeight=64;isEnabled=enabled;setOnClickListener{action()}}
    private fun render(result:JSONObject?=null,message:String="") {
        if(isFinishing||isDestroyed)return
        body.removeAllViews();body.addView(label("공개 음성으로 기기 요약 시험").apply{textSize=25f})
        body.addView(label("앱에 포함된 한국어·영어 합성 음성만 이 기기 안에서 처리해요. 실제 통화나 계정은 사용하지 않아요. 원문에서 뽑은 요약과 확인 필요한 할일을 별도 시험 이력에 보관해요."))
        body.addView(button(if(seen)"같은 결과 다시 확인" else "공개 음성 2개 처리",!running){run()}.apply{tag="android_local_stt_run"})
        body.addView(button("미분류 공개 음성 분석 후 연결",!running){if(!unclassified){pilot?.close();pilot=null;seen=false;unclassified=true};run()}.apply{tag="android_unclassified_stt_run"})
        body.addView(label("미분류 시험은 분석 뒤 공개 합성 담당·회사·시험 업무와 정해진 시험 시각을 연결해요. 음성에서 사람이나 시각을 추정하지 않아요."))
        body.addView(button(when(integrationStep){0->"통합 시험: 음성 분석·후보 확인";1->"시험 사람·업무·시각을 직접 연결";2->"표시한 요약·할일 확인 후 보고 보관";else->"통합 보고 다시 확인"},!running){runIntegration()}.apply{tag="integrated_public_run"})
        body.addView(label("통합 시험의 보고 시계는 2026-10-02 오전 6시(KST)로 가정해요. 실제 정각 실행과 외부 수집·발송은 별도 검증이 필요해요."))
        if(integrationStep in 1..2)body.addView(label("공개 합성 담당·공개 합성 회사·공개 통합 시험 업무, 2026-10-01 10:00 UTC의 시험 사건으로 연결해요. 아래 실제 인식 원문과 요약·확인 필요한 할일을 읽고 다음 단계를 선택해 주세요."))
        for(i in 0 until integrationStages.length()){val stage=integrationStages.getJSONObject(i);body.addView(label("인식 원문\n"+stage.getString("transcript")+"\n\n확인할 요약\n"+stage.getJSONObject("analysis").getString("summary")))}
        integrationResult?.optJSONObject("candidate")?.let{candidate->
            body.addView(label("검토 후보 ${candidate.getJSONArray("items").length()}개 · 연결/검토 보류 ${candidate.getJSONArray("holds").length()}개"))
            val items=candidate.getJSONArray("items");for(i in 0 until items.length())items.getJSONObject(i).getJSONObject("fields").optJSONObject("TITLE")?.let{body.addView(label("확인 필요한 할일 · "+it.getString("value")))}
        }
        integrationResult?.optJSONArray("reports")?.let{reports->for(i in 0 until reports.length())body.addView(label(reports.getJSONObject(i).getString("body")))}
        if(running){body.addView(label("기기 안에서 음성을 읽고 있어요. 완료까지 잠시 기다려 주세요."));body.addView(button("처리 중단"){pilot?.cancel()}.apply{tag="android_local_stt_stop"})}
        if(message.isNotEmpty())body.addView(label(message))
        if(result!=null) {
            body.addView(label("이번 음성 인식 ${result.getInt("actualAndroidSTTCalls")}회 · 저장 이력 ${result.getInt("historyCount")}건 · 할일 ${result.getInt("taskCount")}건").apply{tag="android_local_stt_counts"})
            val rows=result.getJSONArray("results")
            for(i in 0 until rows.length()) {val p=JSONObject(rows.getJSONObject(i).getString("payload"));val a=p.getJSONObject("analysis")
                body.addView(label("인식 원문\n"+p.getJSONObject("stt").getString("text")))
                body.addView(label("추출 요약\n"+a.getString("summary")))
                val tasks=a.getJSONArray("tasks");for(t in 0 until tasks.length())body.addView(label("확인 필요 · "+tasks.getJSONObject(t).getString("title")))
            }
        }
        body.addView(button("닫기"){pilot?.cancel();finish()})
    }
    private fun run(){
        if(running)return;running=true;render()
        lifecycleScope.launch {
            val answer=withContext(Dispatchers.IO){runCatching{val p=pilot?:AndroidLocalSttPilot(this@AndroidLocalSttActivity,if(unclassified)"unclassified-v1" else "public-v1").also{pilot=it};if(unclassified)p.cycleUnclassified(seen) else p.cycle(seen)}}
            running=false
            answer.onSuccess{seen=true;render(it,"일반 통화 목록과 분리된 공개 음성 시험 결과예요. 오인식된 원문은 자동으로 고치지 않아요.")}.onFailure{render(message="처리 완료를 확인하지 못했어요. 이미 저장된 시험 이력은 유지해요.")}
        }
    }
    private fun runIntegration(){
        if(running)return;running=true;render()
        lifecycleScope.launch {
            val answer=withContext(Dispatchers.IO){runCatching{
                val p=integrationPilot?:AndroidLocalSttPilot(this@AndroidLocalSttActivity,"integrated-v1").also{integrationPilot=it}
                when(integrationStep){0->p.integratedAnalyse();1->p.integratedBind(true);else->p.integratedReviewAndReport(IntegratedContextStore.candidateDigest(checkNotNull(integrationResult).getJSONObject("candidate")),true)}
            }}
            running=false;answer.onSuccess{integrationResult=it;if(integrationStep==0)integrationStages=it.getJSONArray("stages");integrationStep=minOf(3,integrationStep+1);render(message=if(integrationStep==3)"현재 기기 안 검토 저장소만 확인한 부분 보고예요. 외부 수집·실제 발송은 하지 않았어요." else "표시된 공개 시험 자료를 확인한 뒤 다음 단계를 선택해 주세요.")}.onFailure{render(message="통합 처리를 보류했어요. 기존 영수증과 저장 자료는 유지해요.")}
        }
    }
    override fun onDestroy(){pilot?.cancel();pilot?.close();integrationPilot?.cancel();integrationPilot?.close();super.onDestroy()}
}
