package kr.callbrief

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject

/** Internal selected-request candidate. Opening/returning a share sheet is never
 * a dot receipt, authentication callback, model run or remote CAS guarantee. */
internal open class DriveAForegroundActivity:ComponentActivity() {
    private lateinit var root:LinearLayout
    private lateinit var candidate:DriveAForegroundCandidate
    private var data=DriveAForegroundCandidate.Result("EXPORT_NOT_PREPARED")
    private var request="";private var busy=false;private var closed=false;private var generation=0L;private var work:Job?=null
    private var diagnostics:JSONObject?=null
    protected open fun createCandidate()=DriveAForegroundCandidate(this,ExistingDriveARequestPort(this,GoogleConnectionSession.get(this)))
    protected open fun diagnose()=DriveADeviceDiagnostics.inspect(this)
    private fun alive()=!closed&&!isFinishing&&!isDestroyed
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        request=intent.getStringExtra("local_request_id").orEmpty()
        root=LinearLayout(this).apply {setBackgroundColor(android.graphics.Color.WHITE);orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32);setOnApplyWindowInsetsListener {v,i->v.setPadding(32,i.systemWindowInsetTop+24,32,i.systemWindowInsetBottom+24);i}}
        setContentView(root);candidate=createCandidate();render();run { diagnostics=withContext(Dispatchers.IO){diagnose()};if(request.isBlank())DriveAForegroundCandidate.Result("EXPORT_NOT_PREPARED") else candidate.load(request) }
    }
    private fun label(s:String,size:Float=18f)=TextView(this).apply{text=s;textSize=size;setPadding(0,16,0,16);isSaveEnabled=false;isSaveFromParentEnabled=false}
    private fun button(s:String,tag:String,enabled:Boolean=true,action:()->Unit)=Button(this).apply{text=s;textSize=18f;isAllCaps=false;minHeight=(56*resources.displayMetrics.density).toInt();this.tag=tag;isEnabled=enabled&&!busy;setOnClickListener{if(alive()&&!busy)action()}}
    private fun render() {
        if(!alive())return;root.removeAllViews();root.addView(label("Drive A · 합성 한 건 요청",24f));root.addView(button("돌아가기","drive_a_back"){finish()})
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        body.addView(label("기존 본인 계정·합성 한 건·자기 dot의 직접 처리·읽기 전용 제안 확인 후보입니다."))
        body.addView(label(DriveAForegroundCandidate.text(data.state),22f).apply{tag="drive_a_state"})
        body.addView(label("기존 본인 계정과 자기 dot이 같은 요청 파일을 이용할 수 있어야 해요. 처리·작성자 확인 전에는 업무에 반영하지 않아요."))
        body.addView(button("이 요청의 인계 자료를 기기에 준비","drive_a_prepare",request.isNotBlank()){run{candidate.prepareExplicit(request)}})
        data.export?.let{v ->
            body.addView(button("같은 요청을 공유 화면으로 열기","drive_a_share",v.attempt==null){run {
                val next=candidate.beginHandoffIntentExplicit(request)
                withContext(Dispatchers.Main){if(alive()&&candidate.canOpenShareUi(next)&&next.export!=null)startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT,next.export.payload),"자기 dot에 합성 한 건 요청"))}
                next
            }})
            body.addView(button("이 요청의 읽기 전용 결과 확인","drive_a_poll"){run{candidate.pollExplicit(request)}})
        }
        data.resultReadbackDisposition?.let { d ->
            if(d.startsWith("HELD_")||d in setOf("REVISION_CONFLICT","SOURCE_QUARANTINED","LOCAL_STORE_FAILED"))body.addView(label("새 결과 검증이 보류됐어요. 이전 기록을 유지하고 지정 파일을 확인해 주세요.").apply{tag="drive_a_result_hold"})
            if(d=="HISTORY_ONLY")body.addView(label("이전 순서의 응답이에요. 이미 저장한 최신 결과를 유지해요."))
        }
        data.receipt?.let { r ->
            body.addView(label("${r.state} · writer/모델 실행 출처 미확인 · 업무 미적용"))
            if(r.conflict)body.addView(label("직접 수정·DONE과 충돌하는 후보입니다. 직접 기록을 유지해요."))
            if(r.hidden)body.addView(label("보관·격리된 후보의 본문을 표시하지 않아요.")) else {
                r.raw?.let{body.addView(label("받은 원문 · 실행 출처 미확인"));body.addView(label(it).apply{tag="drive_a_raw"})}
                r.analysis?.let { raw ->body.addView(button("분석 원문 보기 · 업무 미적용","drive_a_analysis"){
                    val content=label(raw).apply{setPadding(32,24,32,24)}
                    android.app.AlertDialog.Builder(this).setTitle("읽기 전용 분석 원문 · 출처 미확인").setView(ScrollView(this).apply{addView(content)}).setPositiveButton("닫기",null).show()
                })}
            }
        }
        diagnostics?.let{d ->body.addView(label("기기 로컬 진단: ${d.optString("package")} · ${d.optString("versionName")} · GMS 상태 ${d.optInt("gmsLocalStatusCode",-1)}"))}
        body.addView(button("진단·정확한 ID 상세 보기","drive_a_details"){
            android.app.AlertDialog.Builder(this).setTitle("로컬 진단과 요청 ID").setMessage((diagnostics?.toString(2) ?: "진단 미확인")+data.export?.let{v->
                val x=JSONObject(v.payload);val r=x.getJSONObject("nativeRegistration");"\n\njob: "+r.getString("jobId")+"\nfolder: "+x.getString("folderId")+"\nrequest/audio/result: "+r.getJSONObject("files").toString()+"\nrequest SHA: "+x.getString("frozenRequestSha256")
            }.orEmpty()).setPositiveButton("닫기",null).show()
        })
    }
    private fun run(action:suspend ()->DriveAForegroundCandidate.Result) {
        if(!alive()||busy)return;val ticket=++generation;busy=true;render();work=lifecycleScope.launch {
            try{val next=action();ensureActive();if(alive()&&ticket==generation)data=next}catch(c:CancellationException){throw c}catch(_:Exception){if(alive()&&ticket==generation)data=DriveAForegroundCandidate.Result("STORE_REVIEW")}
            finally{if(ticket==generation){busy=false;if(alive())render()}}
        }
    }
    override fun onResume(){super.onResume();if(::candidate.isInitialized&&!busy&&request.isNotBlank())run{candidate.load(request)}}
    override fun onStop(){generation++;work?.cancel();busy=false;super.onStop()}
    override fun onDestroy(){closed=true;generation++;work?.cancel();super.onDestroy()}
}
