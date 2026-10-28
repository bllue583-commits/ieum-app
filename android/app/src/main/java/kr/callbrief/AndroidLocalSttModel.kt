package kr.callbrief

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A configuration change replaces the view, not the public operation or its cancellation
 * owner. Only application context is retained. Process death still uses the protected journals;
 * no stage, approval or model invocation is started by constructing this model. */
internal class AndroidLocalSttModel(application:Application):AndroidViewModel(application) {
    val changes=MutableStateFlow(0L)
    @Volatile var pilot:AndroidLocalSttPilot?=null;private set
    @Volatile var integrationPilot:AndroidLocalSttPilot?=null;private set
    private var activeOperation:PublicAudioOperation?=null
    var running=false;private set
    var seen=false;private set
    private var unclassified=false
    var integrationStep=0;private set
    var integrationResult:JSONObject?=null;private set
    var integrationStages=JSONArray();private set
    var result:JSONObject?=null;private set
    var message="";private set
    private var closed=false
    private fun publish(){changes.value++}
    fun cancel(){activeOperation?.cancel()}
    fun startUnclassified(){
        if(running || closed)return
        if(!unclassified){pilot?.close();pilot=null;seen=false;unclassified=true}
        run()
    }
    fun run(){
        if(running || closed)return
        val operation=PublicAudioOperation();val existing=pilot;val mode=unclassified;val replay=seen
        if(existing!=null)operation.attach(existing::cancel)
        activeOperation=operation;running=true;result=null;message="";publish()
        viewModelScope.launch {
            try {
                val answer=withContext(Dispatchers.IO){runCatching {
                    val p=existing ?: AndroidLocalSttPilot(getApplication(),if(mode)"unclassified-v1" else "public-v1").also{pilot=it}
                    if(existing==null)operation.attach(p::cancel);operation.requireRunning()
                    if(mode)p.cycleUnclassified(replay) else p.cycle(replay)
                }}
                running=false;activeOperation=null
                if(operation.cancelled)message="처리 중단을 요청했어요. 이미 저장된 시험 이력은 유지해요."
                else answer.onSuccess{seen=true;result=it;message="일반 통화 목록과 분리된 공개 음성 시험 결과예요. 오인식된 원문은 자동으로 고치지 않아요."}
                    .onFailure{message="처리 완료를 확인하지 못했어요. 이미 저장된 시험 이력은 유지해요."}
                publish()
            } finally {if(activeOperation===operation){activeOperation=null;running=false;publish()}}
        }
    }
    fun runIntegration(){
        if(running || closed)return
        val operation=PublicAudioOperation();val existing=integrationPilot;val step=integrationStep
        if(existing!=null)operation.attach(existing::cancel)
        activeOperation=operation;running=true;message="";publish()
        viewModelScope.launch {
            try {
                val answer=withContext(Dispatchers.IO){runCatching {
                    val p=existing ?: AndroidLocalSttPilot(getApplication(),"integrated-v1").also{integrationPilot=it}
                    if(existing==null)operation.attach(p::cancel);operation.requireRunning()
                    when(step){0->p.integratedAnalyse();1->p.integratedBind(true);else->p.integratedReviewAndReport(IntegratedContextStore.candidateDigest(checkNotNull(integrationResult).getJSONObject("candidate")),true)}
                }}
                running=false;activeOperation=null
                if(operation.cancelled)message="통합 처리 중단을 요청했어요. 기존 영수증과 이미 저장된 자료는 유지해요."
                else answer.onSuccess{
                    integrationResult=it;if(step==0)integrationStages=it.getJSONArray("stages")
                    integrationStep=minOf(3,step+1)
                    message=if(integrationStep==3)"현재 기기 안 검토 저장소만 확인한 부분 보고예요. 외부 수집·실제 발송은 하지 않았어요." else "표시된 공개 시험 자료를 확인한 뒤 다음 단계를 선택해 주세요."
                }.onFailure{message="통합 처리를 보류했어요. 기존 영수증과 저장 자료는 유지해요."}
                publish()
            } finally {if(activeOperation===operation){activeOperation=null;running=false;publish()}}
        }
    }
    override fun onCleared(){closed=true;cancel();pilot?.cancel();integrationPilot?.cancel();pilot?.close();integrationPilot?.close();super.onCleared()}
}
