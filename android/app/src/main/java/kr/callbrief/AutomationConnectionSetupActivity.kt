package kr.callbrief

import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.UUID

/** Internal production setup completion. Non-secret plan fields only; credentials never enter views/Intent. */
internal class AutomationConnectionSetupActivity:ComponentActivity() {
    private lateinit var session:GoogleConnectionSession;private lateinit var body:LinearLayout
    private lateinit var status:TextView;private lateinit var queue:EditText;private lateinit var proof:EditText
    private var loadedPlan:ScheduledDotProofPlan?=null
    private var busy=true
    private val setupButtons=mutableListOf<Button>()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        session=GoogleConnectionSession.get(this)
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        setContentView(ScrollView(this).apply{addView(body)})
        fun label(s:String)=body.addView(TextView(this).apply{text=s;textSize=18f})
        label("기존 승인 연결 설정")
        label("현재 계정이 이미 접근할 수 있는 비공개 Drive 폴더로 합성 텍스트 확인을 설정해요. 실제 음성 전사와 원격 dot 예약을 만들지는 않아요.")
        status=TextView(this).apply{textSize=18f};body.addView(status)
        queue=EditText(this).apply{hint="승인된 기존 Drive 폴더 ID";isSingleLine=true};body.addView(queue)
        proof=EditText(this).apply{hint="같은 검증 요청 ID";isSingleLine=true;setText(UUID.randomUUID().toString())};body.addView(proof)
        body.addView(Button(this).apply{text="승인 폴더 확인 후 합성 설정 저장";isEnabled=false;setupButtons+=this;setOnClickListener{
            val key=proof.text.toString().trim();val folder=queue.text.toString().trim()
            val plan=loadedPlan?.copy(enabled=true,proofRequestId=key,queueFolder=folder) ?: ScheduledDotProofPlan(true,key,folder)
            runSetup {
            AutomationApprovedSetup(this@AutomationConnectionSetupActivity,session).applyScheduled(plan)
            "설정을 저장하고 기기의 확인 작업을 등록했어요. dot 예약·분석 실행 완료는 별도 확인이 필요해요."
        }}})
        label("worker 연결은 이미 승인돼 앱 내부에 안전하게 보관된 설정만 적용해요. 여기서 credential이나 새 권한을 받지 않아요.")
        body.addView(Button(this).apply{text="기존 승인 worker 자료 확인";isEnabled=false;setupButtons+=this;setOnClickListener{runSetup {
            when(AutomationApprovedSetup(this@AutomationConnectionSetupActivity,session).receiveExistingProcessingSetup().state){
                ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_STORED,
                ApprovedProcessingSetupWriter.State.PREVIOUS_APPROVED_WORKER_SETUP_ALREADY_STORED->"승인 자료를 보관했어요. 연결 적용은 별도로 확인해 주세요."
                ApprovedProcessingSetupWriter.State.WAITING_PREVIOUS_APPROVED_WORKER_SETUP->"승인된 자료 공급 경로가 준비되지 않았어요. 기존 설정을 유지해요."
                else->"승인 자료와 보호 저장소 확인이 필요해요."
            }
        }}})
        body.addView(Button(this).apply{text="기존 승인 worker 설정 적용";isEnabled=false;setupButtons+=this;setOnClickListener{runSetup {
            when(AutomationApprovedSetup(this@AutomationConnectionSetupActivity,session).applyExistingProcessing()){
                "PREVIOUS_APPROVED_WORKER_BOUND"->"기존 승인 worker 연결을 저장했어요. 결과의 검증 범위는 유지해요."
                "WAITING_PREVIOUS_APPROVED_WORKER_SETUP"->"앱 내부에 기존 승인 worker 자료가 없어요. 운영자의 승인된 연결 준비가 필요해요."
                else->"기존 Google 접근을 먼저 확인해 주세요. worker 연결은 저장하지 않았어요."
            }
        }}})
        body.addView(Button(this).apply{text="뒤로";setOnClickListener{finish()}})
        lifecycleScope.launch {withContext(Dispatchers.IO){runCatching{AutomationConnectionBindings.scheduled(this@AutomationConnectionSetupActivity,session,allowRetainedScope=true)}}.getOrNull()?.let{loadedPlan=it;queue.setText(it.queueFolder);proof.setText(it.proofRequestId)}
            status.text=ExistingGoogleSessionRecoveryRuntime.messageKo(session.recoveryState());busy=false;setupButtons.forEach{it.isEnabled=true}}
    }
    private fun runSetup(work:()->String) {
        if(busy)return
        busy=true;setupButtons.forEach{it.isEnabled=false}
        lifecycleScope.launch {
            status.text="기존 접근과 저장 상태를 확인 중이에요."
            val owner=session.ownerBinding();val epoch=session.epoch()
            val outcome=withContext(Dispatchers.IO){runCatching{work()}}
            busy=false;setupButtons.forEach{it.isEnabled=true}
            if(session.ownerBinding()!=owner || session.epoch()!=epoch){status.text="계정이 바뀌어 설정을 적용하지 않았어요.";return@launch}
            status.text=outcome.getOrElse{"기존 승인 범위·폴더 접근·보호 저장소 확인이 필요해요. 연결 완료로 표시하지 않았어요."}
        }
    }
}
