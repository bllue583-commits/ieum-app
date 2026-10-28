package kr.callbrief

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Normal in-app entry for the approved public-text 1+1 trial. No Intent supplies a source,
 * account, endpoint, approval key or fake completion. Personal recording admission stays closed. */
class SiwcAppAnalysisActivity:ComponentActivity() {
    private lateinit var model:SiwcAppAnalysisModel
    internal val testState get()=model
    private lateinit var body:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(type:Class<T>)=SiwcAppAnalysisModel(SiwcAppAnalysisRuntime.dependencies(applicationContext)) as T
        })[SiwcAppAnalysisModel::class.java]
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,40,24,24)}
        setContentView(ScrollView(this).apply{addView(body)})
        lifecycleScope.launch{model.view.collect{render(it)}}
    }
    private fun label(value:String)=TextView(this).apply{text=value;textSize=18f;setPadding(0,12,0,12)}
    private fun button(value:String,tagValue:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply{text=value;isAllCaps=false;tag=tagValue;isEnabled=enabled;setOnClickListener{action()}}
    private fun details(title:String,value:String){
        val content=label(value).apply{visibility=View.GONE}
        body.addView(button(title,"siwc_app_details"){content.visibility=if(content.visibility==View.GONE)View.VISIBLE else View.GONE});body.addView(content)
    }
    private fun confirmation(v:SiwcAppAnalysisModel.View,action:String,title:String,consent:String){
        val check=CheckBox(this).apply{text=consent;textSize=18f;tag="siwc_app_confirm"}
        val send=button(title,"siwc_app_"+action.lowercase(),false){model.act(action,check.isChecked,v.approvalKey)}
        check.setOnCheckedChangeListener{_,checked->send.isEnabled=checked && !v.busy}
        body.addView(check);body.addView(send)
    }
    private fun completion(data:JSONObject?,stage:String){
        val c=data?.optJSONObject("completion") ?: data?.optJSONObject("progress")?.optJSONObject(stage) ?: return
        body.addView(label("응답 모델: "+c.optString("actualModel")+" · 응답 effort: "+c.optString("effectiveEffort").let{if(it=="NOT_REPORTED")"미보고 (요청은 xhigh)" else it}))
        val output=c.opt("output");if(output!=null && output!=JSONObject.NULL)details("검증된 응답 내용 보기",output.toString())
    }
    private fun render(v:SiwcAppAnalysisModel.View){
        if(isFinishing || isDestroyed)return
        body.removeAllViews();body.addView(label("ChatGPT로 공개 음성 분석"))
        body.addView(label("선택 자료: 앱에 포함된 한국어 public-ko.wav. 원음은 기기 안에서 전사하고, 표시한 전사문·합성 업무만 ChatGPT에 보내요. 개인 녹음은 선택할 수 없어요."))
        if(v.fixture)body.addView(label("합성 연결 시험 · 실제 ChatGPT/Astra 실행 아님").apply{tag="siwc_app_fixture"})
        if(!v.enabled)body.addView(label("승인된 개인 시험 build에서 사용할 수 있어요. 현재 build는 전사 전송·분석을 실행하지 않아요."))
        val data=v.data
        val transcript=v.transcript.ifBlank{data?.optJSONObject("publicSourcePack")?.optJSONObject("source")?.optString("transcript").orEmpty()}
        if(transcript.isNotBlank())body.addView(label("기기에서 인식한 원문\n$transcript").apply{tag="siwc_app_transcript"})
        data?.optString("accountLabel")?.takeIf{it.isNotBlank()}?.let{body.addView(label("현재 검증 계정: $it"))}
        if(v.enabled && !v.busy)when(v.phase){
            "START"->body.addView(button("이 공개 음성을 기기에서 전사","siwc_app_transcribe"){model.act("TRANSCRIBE")})
            "TRANSCRIBED"->{
                body.addView(label("공개 합성 담당·공개 합성 회사·공개 통합 시험 업무, 2026-10-01 10:00 UTC에 연결해요. 음성에서 이 정보를 추정하지 않아요."))
                confirmation(v,"BIND","표시한 시험 사람·업무·시각 연결","위 합성 결속 정보를 확인했어요.")
            }
            "BOUND","MODEL_UNAVAILABLE"->{
                if(v.phase=="MODEL_UNAVAILABLE")body.addView(label("이 계정의 catalog에 요청한 Astra 모델이 없어요. 다른 모델로 자동 대체하지 않아요."))
                body.addView(button("계정과 Astra 사용 가능 여부 확인","siwc_app_plan"){model.act("PLAN")})
            }
            "ANALYSIS_READY"->{
                body.addView(label("내용 분석 1회 · "+data!!.getString("model")+" / "+data.getString("requestedEffort")+" · 내 ChatGPT 플랜 사용량"))
                details("전송할 공개 전사·합성 업무와 영수증 확인",data.getJSONObject("publicSourcePack").toString(2)+"\n"+data.getJSONObject("approvedSnapshot").toString(2)+"\n입력 SHA: "+data.getString("manifestSha256"))
                confirmation(v,"ANALYSE","공개 전사 분석 1회 실행","표시한 공개 전사문과 합성 업무를 이 계정의 ChatGPT 사용량으로 분석 1회 전송해요.")
            }
            "ANALYSIS_DONE"->{completion(data,"analysis");body.addView(button("별도 맥락 입력 확인","siwc_app_context_plan"){model.act("CONTEXT_PLAN")})}
            "CONTEXT_READY"->{
                completion(data,"analysis");body.addView(label("맥락 처리 1회 · "+data!!.getString("contextModel")+" / "+data.getString("contextEffort")+" · 분석과 별도 사용량"))
                details("맥락에 보낼 완료 분석·현재 합성 업무 확인",data.getJSONObject("progress").getJSONObject("analysis").getString("output")+"\n"+data.getJSONObject("approvedSnapshot").toString(2))
                confirmation(v,"CONTEXT","맥락 처리 1회 실행","완료 분석과 표시한 현재 합성 업무를 별도 맥락 처리 1회로 전송해요.")
            }
            "CONTEXT_DONE"->{completion(data,"context");body.addView(button("결과파일 저장·다시 읽기","siwc_app_receive"){model.act("RECEIVE")})}
            "CANDIDATE"->{
                val candidate=data!!.getJSONObject("candidate");body.addView(label("검토 후보 · 아직 사용자 저장 전이에요."))
                body.addView(label(candidate.getJSONObject("artifact").getJSONObject("analysis").getString("summary")))
                details("근거 인용·할일·보류 내용 확인",candidate.getJSONObject("candidate").toString(2))
                confirmation(v,"REVIEW","표시한 요약·새 할일 검토 저장","표시한 요약과 새 할일을 확인했어요. 확인 필요 상태로 저장해요. 기존 완료·메모는 덮어쓰지 않아요.")
            }
            "REVIEWED"->{
                body.addView(label("명시 검토한 결과를 보호 저장소에 보관했어요. 저장 영수증: "+data!!.getString("summaryReceipt")).apply{tag="siwc_app_reviewed"})
                body.addView(label(data.getJSONObject("candidate").getJSONObject("artifact").getJSONObject("analysis").getString("summary")).apply{tag="siwc_app_saved_summary"})
            }
            "HELD"->body.addView(label("완료가 확인되지 않았거나 현재 출처·계정·검토 조건이 달라 처리를 보류했어요. 새 모델 요청은 자동으로 보내지 않아요."))
        }
        if(v.status.isNotBlank())body.addView(label(when(v.status){"SIWC_SIGN_IN_REQUIRED"->"공식 ChatGPT 연결을 마친 뒤 계정 확인을 선택해 주세요.";else->v.status.takeUnless{it.matches(Regex("[A-Z0-9_]{1,100}"))} ?: "현재 조건을 확인하지 못해 보류했어요. 확인 코드: "+v.status}).apply{tag="siwc_app_status"})
        if(v.busy)body.addView(button("처리 중단 요청","siwc_app_stop"){model.cancel()})
        body.addView(button("ChatGPT 계정 연결","siwc_app_login",!v.busy){startActivity(Intent(this,SiwcRuntimeActivity::class.java))})
        body.addView(button("저장된 진행 다시 확인","siwc_app_restore",v.enabled && !v.busy){model.act("RESTORE")})
        body.addView(button("닫기","siwc_app_close"){model.cancel();finish()})
    }
    override fun onDestroy(){if(isFinishing && ::model.isInitialized)model.cancel();super.onDestroy()}
}
