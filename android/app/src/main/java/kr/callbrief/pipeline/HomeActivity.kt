package kr.callbrief.pipeline

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.SecureSettings

/** App home: what happened today at a glance, the first-run checklist until setup is done,
 * and three big doors (오늘의 통화 / 할 일 / 설정). Older features sit behind "기타 기능". */
class HomeActivity:ComponentActivity() {
    internal data class Snapshot(val folder:Boolean,val key:Boolean,val consent:Boolean,val model:String,
                                 val calls:Int,val working:Int,val newTasks:Int,val due:Int,val overdue:Int,val lastAnalyzedMs:Long,
                                 val notify:Boolean=true,val battery:Boolean=true,val modelPercent:Int=-1,val nextReport:String="")
    private lateinit var body:LinearLayout
    private val pickFolder=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode==RESULT_OK) CallFolder.save(this,r.data)?.let { android.widget.Toast.makeText(this,it,android.widget.Toast.LENGTH_LONG).show() }
        load()
    }
    private val askNotify=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { load() }
    private fun notifyAllowed()=android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun batteryFree()=getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)==true

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        body=CallUi.page(this)
        CallPipelineStartup.init(this)
    }
    override fun onResume() { super.onResume();load()
        if(intent?.getBooleanExtra("install_update",false)==true) { intent.removeExtra("install_update");CallUpdate.install(this) } }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent) }

    private fun load() {
        lifecycleScope.launch {
            val snap=withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            if(isFinishing||isDestroyed)return@launch
            render(snap)
        }
    }

    private fun read():Snapshot {
        val storage=CallPipelineStartup.storage(this)
        val s=SecureSettings(storage).snapshot()
        val folder=s.optString("treeUri").isNotBlank()
        if(folder) {
            runCatching { AutomaticRecordingDiscovery.ensure(storage) }
            runCatching { CallTranscriptionWork.schedule(storage) }
        }
        val today=CallUi.today()
        val (calls,due,newTasks)=CallRecordStore(storage).use { Triple(it.day(today),it.dueItems(today),it.newTaskCount(today)) }
        val mm=PipelineModelManager(storage)
        val percent=if(mm.partFile.isFile) (mm.partFile.length()*100/574041195L).toInt().coerceIn(0,99) else -1
        val next=CallUi.nextReport(s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),s.optInt(PipelineSettingsKeys.MORNING_HOUR,6))
        return Snapshot(folder,s.optString(PipelineSettingsKeys.CLAUDE_API_KEY).isNotBlank(),s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),
            s.optString(PipelineSettingsKeys.MODEL_STATE),calls.size,calls.count{it.state!=CallRecordStore.ANALYZED},newTasks,
            due.size,due.count{CallUi.overdue(it.due)},s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),batteryFree(),percent,next)
    }

    private fun open(target:Class<*>,setup:Intent.()->Unit={}) = startActivity(Intent(this,target).apply(setup))

    internal fun render(s:Snapshot?) {
        body.removeAllViews()
        body.addView(CallUi.title(this,"이음").apply { tag="home_title" })
        body.addView(CallUi.text(this,CallUi.date(CallUi.today())+" · 오늘의 통화를 대신 기억해요",CallUi.SMALL,color=CallUi.SUBTLE))

        CallUpdate.ready(this)?.let { u ->
            val c=CallUi.card(this).apply { tag="home_update" }
            c.addView(CallUi.text(this,"새 버전이 준비됐어요",CallUi.HEADING,true))
            if(u.notes.isNotBlank()) c.addView(CallUi.text(this,u.notes,CallUi.BODY))
            c.addView(CallUi.bigButton(this,"⬇️","지금 업데이트") { CallUpdate.install(this) }.apply { tag="home_update_button" })
            body.addView(c)
        }
        if(s==null) { body.addView(CallUi.text(this,"정보를 읽지 못했어요. 잠시 후 다시 열어주세요."));doors(null);return }

        val steps=listOf(s.folder,s.key||CallInboxConfig.enabled,s.consent,s.model=="READY",s.notify,s.battery)
        if(!steps.all{it}) {
            val c=CallUi.card(this,rail=CallUi.AMBER_RAIL).apply { tag="home_setup" }
            c.addView(CallUi.text(this,"처음 설정 ${steps.count{it}}/${steps.size}",CallUi.HEADING,true))
            fun step(n:Int,done:Boolean,label:String,detail:String,action:()->Unit) {
                c.addView(CallUi.text(this,(if(done) "✓ " else "$n. ")+label,CallUi.BODY,true,if(done) CallUi.OK else CallUi.INK))
                if(!done) { c.addView(CallUi.text(this,detail,CallUi.SMALL,color=CallUi.SUBTLE));c.addView(CallUi.button(this,"$label 하기",action)) }
            }
            step(1,s.folder,"녹음 폴더 연결","통화 녹음 폴더(보통 Recordings › Call)가 열리면 아래 [이 폴더 사용]을 누르세요.") { pickFolder.launch(CallFolder.pickIntent()) }
            if(!CallInboxConfig.enabled) step(2,s.key,"Claude 키 넣기","통화를 요약하는 데 쓰는 키예요. 설정 화면에 붙여 넣으면 돼요.") { open(CallSettingsActivity::class.java) }
            step(3,s.consent,"정리 동의","녹음은 휴대폰에 그대로 두고, 글자로 바꾼 내용만 보내 정리해요.") { open(CallSettingsActivity::class.java) }
            step(4,s.model=="READY","음성 인식 모델 받기",
                if(s.model=="DOWNLOADING") "받는 중이에요"+(if(s.modelPercent>=0) " ${s.modelPercent}%" else "")+". Wi-Fi에 연결해 두세요. (약 570MB)" else "Wi-Fi에서 한 번만 받아요. (약 570MB)") {
                PipelineModelDownloadWork.enqueue(this);android.widget.Toast.makeText(this,"Wi-Fi에 연결되면 받기 시작해요.",android.widget.Toast.LENGTH_LONG).show();load()
            }
            step(5,s.notify,"알림 허용","아침 6시 할 일, 저녁 7시 통화 정리를 알림으로 보내드려요.") {
                if(android.os.Build.VERSION.SDK_INT>=33)askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            step(6,s.battery,"배터리 제한 풀기","휴대폰이 쉬는 동안에도 통화를 정리하려면 한 번 허용해 주세요.") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:$packageName"))) }
                    .onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            body.addView(c)
        }

        // Today at a glance: one plain sentence per fact, the urgent one in colour.
        val today=CallUi.card(this).apply { tag="home_today" }
        today.addView(CallUi.text(this,"통화 ${s.calls}건"+(if(s.working>0) " (정리 중 ${s.working}건)" else ""),CallUi.HEADING,true).apply { tag="home_calls";setPadding(0,0,0,CallUi.dp(this@HomeActivity,2)) })
        today.addView(CallUi.text(this,"새로 생긴 할 일 ${s.newTasks}개",CallUi.BODY,color=CallUi.SUBTLE))
        when {
            s.overdue>0 -> today.addView(CallUi.pill(this,"기한 지난 일 ${s.overdue}개 · 오늘까지 ${s.due-s.overdue}개",CallUi.WARN))
            s.due>0 -> today.addView(CallUi.pill(this,"오늘까지 할 일 ${s.due}개",CallUi.AMBER))
            else -> today.addView(CallUi.text(this,"오늘까지 할 일은 없어요",CallUi.BODY,color=CallUi.OK))
        }
        val foot=listOfNotNull(s.nextReport.takeIf{it.isNotBlank()}?.let{"다음 알림 $it"},"마지막 정리 "+CallUi.dateTime(s.lastAnalyzedMs))
        today.addView(CallUi.text(this,foot.joinToString("\n"),CallUi.SMALL,color=CallUi.SUBTLE).apply { tag="home_next_report";setPadding(0,CallUi.dp(this@HomeActivity,10),0,0) })
        body.addView(today)
        doors(s)
    }

    private fun doors(s:Snapshot?) {
        body.addView(CallUi.gap(this,6))
        body.addView(CallUi.door(this,"오늘의 통화",s?.let{"오늘 ${it.calls}건"+(if(it.working>0) " · 정리 중 ${it.working}건" else "")} ?: "") { open(TodayCallsActivity::class.java) }.apply { tag="home_calls_button" })
        body.addView(CallUi.door(this,"할 일",s?.let{ if(it.due>0) "오늘까지 ${it.due}개" else "목록 보기" } ?: "") { open(CallTasksActivity::class.java) }.apply { tag="home_tasks_button" })
        body.addView(CallUi.door(this,"설정",s?.let{ if(listOf(it.folder,it.consent,it.model=="READY",it.notify,it.battery).all{x->x}) "녹음 폴더 · 알림 시각" else "처음 설정을 마쳐 주세요" } ?: "") { open(CallSettingsActivity::class.java) }.apply { tag="home_settings_button" })
    }
}
