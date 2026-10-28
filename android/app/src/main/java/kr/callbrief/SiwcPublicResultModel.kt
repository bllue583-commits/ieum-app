package kr.callbrief

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Retains only this public fixture screen's work across recreation. It never starts a stage
 * on construction/resume. Candidate visibility and a later explicit user review remain separate. */
internal class SiwcPublicResultModel(application:Application):AndroidViewModel(application) {
    val changes=MutableStateFlow(0L)
    @Volatile var pilot:AndroidLocalSttPilot?=null;private set
    private var active:PublicAudioOperation?=null
    var phase=0;private set
    var busy=false;private set
    var transcripts="";private set
    var candidates:JSONObject?=null;private set
    var message="";private set
    private var closed=false
    private fun publish(){changes.value++}
    fun cancel(){active?.cancel()}
    fun advance(){
        if(busy || closed || phase>=4)return
        val operation=PublicAudioOperation();val existing=pilot;val step=phase
        if(existing!=null)operation.attach(existing::cancel)
        active=operation;busy=true;message="";publish()
        viewModelScope.launch {
            try {
                val answer=withContext(Dispatchers.IO){runCatching {
                    val p=existing ?: AndroidLocalSttPilot(getApplication(),"siwc-public-v1").also{pilot=it}
                    if(existing==null)operation.attach(p::cancel);operation.requireRunning()
                    when(step){0->p.integratedAnalyse();1->p.integratedBind(true);2->p.siwcPublicCandidates();else->{
                        val a=checkNotNull(candidates).getJSONArray("candidates")
                        p.siwcPublicReview((0 until a.length()).associate{val c=a.getJSONObject(it);c.getString("id") to c.getString("digest")},true)
                    }}
                }}
                busy=false;active=null
                if(operation.cancelled)message="중단을 요청했어요. 이미 저장한 자료는 보존해요."
                else answer.onSuccess{result->
                    if(step==0)transcripts=result.getJSONArray("stages").let{a->(0 until a.length()).joinToString("\n\n"){a.getJSONObject(it).getString("transcript")}}
                    if(step==2)candidates=result
                    phase=step+1
                    message=if(phase==4)"명시 검토한 합성 결과를 기기 보호 저장소에 보관했어요. 실제 Astra 호출은 없었어요." else "표시한 원문·시험 내용을 확인하고 다음 단계를 선택해 주세요."
                }.onFailure{message="현재 출처·revision·검토 gate를 확인하지 못해 보류했어요. 완료로 표시하지 않았어요."}
                publish()
            } finally {if(active===operation){active=null;busy=false;publish()}}
        }
    }
    override fun onCleared(){closed=true;cancel();pilot?.cancel();pilot?.close();super.onCleared()}
}
