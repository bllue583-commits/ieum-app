package kr.callbrief

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Typed app commands. There is no command that accepts an audio path, endpoint or new retry. */
internal interface SiwcAppAnalysisPort:AutoCloseable {
    fun run(action:String,key:String="",candidateId:String="",digest:String="",confirmed:Boolean=false):JSONObject
    fun requestStop()
}
internal object SiwcAppAnalysisRuntime {
    class Dependencies(val enabled:Boolean,val fixture:Boolean,val create:()->SiwcAppAnalysisPort)
    @Volatile internal var testDependencies:((Context)->Dependencies)?=null
    fun dependencies(context:Context):Dependencies {
        val app=context.applicationContext
        val test=if(BuildConfig.DEBUG)testDependencies else null
        return test?.invoke(app) ?: Dependencies(BuildConfig.DEBUG && BuildConfig.SIWC_PERSONAL_TRIAL_APPROVED,false){SiwcAppAnalysisBackend(app)}
    }
}

/** Real local JNI, protected stores and two-stage HTTP, unless a debug test supplies its port.
 * Fixtures use a separate public namespace and budget. Credentials never leave SessionStore. */
internal class SiwcAppAnalysisBackend(private val context:Context,
    private val fixturePort:((SiwcNativeContract)->SiwcInferencePort)?=null):SiwcAppAnalysisPort {
    init{check(fixturePort==null || BuildConfig.DEBUG)}
    private val stopped=AtomicBoolean(false)
    @Volatile private var pilot:AndroidLocalSttPilot?=null
    private fun pilot():AndroidLocalSttPilot {
        check(!stopped.get()) { "SIWC_APP_STOPPED" }
        return pilot ?: AndroidLocalSttPilot(context,if(fixturePort==null)"siwc-app-public-v1" else "siwc-app-fixture-v1",true).also{
            pilot=it;if(stopped.get())it.cancel()
        }
    }
    override fun run(action:String,key:String,candidateId:String,digest:String,confirmed:Boolean):JSONObject {
        check(!stopped.get()) { "SIWC_APP_STOPPED" }
        check(BuildConfig.DEBUG && BuildConfig.SIWC_PERSONAL_TRIAL_APPROVED) { "SIWC_PERSONAL_TRIAL_NOT_APPROVED" }
        val p=pilot()
        val result=when(action){
            "TRANSCRIBE"->p.integratedAnalyse().put("phase","TRANSCRIBED")
            "BIND"->{check(confirmed) { "SIWC_EXPLICIT_ACTION_APPROVAL_REQUIRED" };p.integratedBind(true).put("phase","BOUND")}
            else->p.siwcAppAction(action,key,candidateId,digest,confirmed,fixturePort)
        }
        return result.put("fixture",fixturePort!=null)
    }
    override fun requestStop(){stopped.set(true);pilot?.cancel()}
    override fun close(){requestStop();pilot?.close()}
}

/** Retained foreground owner. Creating/resuming a screen never opens the port or runs a stage.
 * Only explicit UI commands enqueue work. A stopped/unknown attempt can only be read again. */
internal class SiwcAppAnalysisModel(private val dependencies:SiwcAppAnalysisRuntime.Dependencies):ViewModel() {
    data class View(val enabled:Boolean,val fixture:Boolean,val phase:String="START",val busy:Boolean=false,
        val status:String="",val transcript:String="",val data:JSONObject?=null,val approvalKey:String="")
    val view=MutableStateFlow(View(dependencies.enabled,dependencies.fixture))
    private val io=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val generation=AtomicLong()
    @Volatile private var closed=false
    @Volatile private var port:SiwcAppAnalysisPort?=null
    private val allowed=mapOf("TRANSCRIBE" to setOf("START"),"BIND" to setOf("TRANSCRIBED"),"PLAN" to setOf("BOUND","MODEL_UNAVAILABLE"),
        "ANALYSE" to setOf("ANALYSIS_READY"),"CONTEXT_PLAN" to setOf("ANALYSIS_DONE"),"CONTEXT" to setOf("CONTEXT_READY"),
        "RECEIVE" to setOf("CONTEXT_DONE"),"REVIEW" to setOf("CANDIDATE"))
    fun act(action:String,confirmed:Boolean=false,displayedKey:String="") {
        val before=view.value
        if(closed || !before.enabled || before.busy || (action!="RESTORE" && before.phase !in allowed[action].orEmpty()))return
        if(action in setOf("ANALYSE","CONTEXT","REVIEW","BIND")) {
            if(!confirmed)return
            if(action in setOf("ANALYSE","CONTEXT") && (before.approvalKey.isBlank() || displayedKey!=before.approvalKey))return
        }
        val run=generation.incrementAndGet()
        view.value=before.copy(busy=true,status="처리 중이에요.")
        io.execute {
            val answer=runCatching {
                check(!closed && generation.get()==run) { "SIWC_APP_STOPPED" }
                if(action=="RESTORE"){port?.close();port=null}
                val p=port ?: dependencies.create().also{port=it;if(closed || generation.get()!=run)it.requestStop()}
                check(!closed && generation.get()==run) { "SIWC_APP_STOPPED" }
                val candidate=before.data?.optJSONObject("candidate")
                p.run(action,displayedKey,candidate?.optString("id").orEmpty(),candidate?.optString("digest").orEmpty(),confirmed)
            }
            main.post {
                if(!closed){
                    if(generation.get()!=run)view.value=view.value.copy(busy=false,phase="HELD",status=if(action in setOf("ANALYSE","CONTEXT"))"중단했어요. 원격 처리·사용량 중단은 확인되지 않았어요. 저장된 진행만 다시 확인할 수 있어요." else "처리를 중단했어요. 저장된 진행만 다시 확인할 수 있어요.",approvalKey="")
                    else answer.onSuccess { result ->
                        val phase=result.getString("phase")
                        val transcript=if(action=="TRANSCRIBE")result.getJSONArray("stages").getJSONObject(0).getString("transcript") else before.transcript
                        val key=when(phase){"ANALYSIS_READY"->result.getString("analysisKey");"CONTEXT_READY"->result.getString("contextKey");else->""}
                        view.value=View(before.enabled,before.fixture,phase,false,result.optString("reason"),transcript,result,key)
                    }.onFailure { failure ->
                        val code=(failure as? SiwcAstraProtocol.Failure)?.code ?: failure.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,100}"))} ?: "SIWC_APP_ACTION_HELD"
                        view.value=before.copy(busy=false,status=code,approvalKey=if(action in setOf("ANALYSE","CONTEXT","REVIEW"))"" else before.approvalKey,
                            phase=if(action in setOf("ANALYSE","CONTEXT","REVIEW","RECEIVE"))"HELD" else before.phase)
                    }
                }
            }
        }
    }
    fun cancel(){if(!view.value.busy)return;generation.incrementAndGet();port?.requestStop();view.value=view.value.copy(status="중단을 요청했어요.",approvalKey="")}
    override fun onCleared(){closed=true;generation.incrementAndGet();port?.requestStop();io.execute{port?.close()};io.shutdown()}
}
