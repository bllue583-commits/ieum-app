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

/** App home, a dashboard: today's numbers, the first-run checklist until setup is done, today's call memos,
 * open to-dos per company and this week's calls per day. The bottom bar leads to every other screen. */
class HomeActivity:ComponentActivity() {
    internal data class Snapshot(val folder:Boolean,val key:Boolean,val consent:Boolean,val model:String,
                                 val calls:Int,val working:Int,val newTasks:Int,val due:Int,val overdue:Int,val lastAnalyzedMs:Long,
                                 val notify:Boolean=true,val battery:Boolean=true,val modelPercent:Int=-1,val nextReport:String="",val companies:Int=0,
                                 /** Calls per day, six days ago .. today. */
                                 val week:List<Int> =emptyList(),
                                 /** Open to-dos per company, most first ("업체 없음" for the rest). */
                                 val byCompany:List<Pair<String,Int>> =emptyList(),
                                 /** Today's calls, latest first, for the memo list. */
                                 val memos:List<CallRecord> =emptyList(),
                                 val sms:Boolean=true)
    private lateinit var body:LinearLayout
    private val pickFolder=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode==RESULT_OK) CallFolder.save(this,r.data)?.let { android.widget.Toast.makeText(this,it,android.widget.Toast.LENGTH_LONG).show() }
        load()
    }
    private val askNotify=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { load() }
    private val askSms=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { load() }
    private fun notifyAllowed()=android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun batteryFree()=getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)==true

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        body=CallUi.page(this,CallUi.Tab.HOME)
        CallPipelineStartup.init(this)
        if(LaunchIntro.claim(intent?.action==Intent.ACTION_MAIN,savedInstanceState!=null)) LaunchIntro.show(this)
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
        if(kr.callbrief.BuildConfig.LOCAL_PREVIEW) PreviewSeed.ensure(storage)
        val s=SecureSettings(storage).snapshot()
        val folder=s.optString("treeUri").isNotBlank()
        if(folder&&!kr.callbrief.BuildConfig.LOCAL_PREVIEW) {
            runCatching { AutomaticRecordingDiscovery.ensure(storage) }
            runCatching { CallTranscriptionWork.schedule(storage) }
        }
        val today=CallUi.today()
        val (calls,due,newTasks)=CallRecordStore(storage).use { Triple(it.day(today),it.dueItems(today),it.newTaskCount(today)) }
        val companies=runCatching { CallRecordStore(storage).use { it.companyCount() } }.getOrDefault(0)
        val week=runCatching { CallRecordStore(storage).use { s -> (6 downTo 0).map { s.day(today.minusDays(it.toLong())).size } } }.getOrDefault(emptyList())
        val open=runCatching { CallRecordStore(storage).use { it.openItems() } }.getOrDefault(emptyList())
        val byCompany=open.groupingBy { it.company ?: "업체 없음" }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        val mm=PipelineModelManager(storage)
        val percent=if(mm.partFile.isFile) (mm.partFile.length()*100/574041195L).toInt().coerceIn(0,99) else -1
        val next=CallUi.nextReport(s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),s.optInt(PipelineSettingsKeys.MORNING_HOUR,6))
        return Snapshot(folder,s.optString(PipelineSettingsKeys.CLAUDE_API_KEY).isNotBlank(),s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),
            s.optString(PipelineSettingsKeys.MODEL_STATE),calls.size,calls.count{it.state!=CallRecordStore.ANALYZED},newTasks,
            due.size,due.count{CallUi.overdue(it.due)},s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),batteryFree(),percent,next,companies,
            week,byCompany,calls.sortedByDescending { it.startedMs ?: 0 }.take(4),
            checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    private fun agree() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { SecureSettings(CallPipelineStartup.storage(this@HomeActivity)).update { it.put(PipelineSettingsKeys.CONSENT_GRANTED,true) } } }
            CallPipelineStartup.settingsChanged(this@HomeActivity);load()
        }
    }
    private fun open(target:Class<*>,setup:Intent.()->Unit={}) = startActivity(Intent(this,target).apply(setup))

    internal fun render(s:Snapshot?) {
        body.removeAllViews()
        CallUi.band(body,CallUi.greeting(),eyebrow=CallUi.date(CallUi.today()))
        if(s==null) { body.addView(CallUi.card(this).apply { addView(CallUi.text(this@HomeActivity,"정보를 읽지 못했어요. 잠시 후 다시 열어주세요.")) });return }

        // Today at a glance: three big numbers, then when the next report comes.
        val today=CallUi.card(this).apply { tag="home_today" }
        val left=s.byCompany.sumOf { it.second }
        today.addView(CallUi.stats(this,listOf(Triple("${s.calls}","오늘 통화",CallUi.INK),Triple("${s.newTasks}","새 체크",CallUi.INK),
            Triple("$left","체크할 일",CallUi.ACCENT)),listOf("건","개","개")).apply { tag="home_calls" })
        today.addView(CallUi.divider(this))
        today.addView(CallUi.text(this,s.nextReport.takeIf{it.isNotBlank()}?.let{"다음 알림 $it"} ?: "마지막 정리 "+CallUi.dateTime(s.lastAnalyzedMs),CallUi.SMALL,color=CallUi.SUBTLE).apply { tag="home_next_report" })
        if(s.working>0) today.addView(CallUi.pill(this,"정리 중 ${s.working}건",CallUi.ACCENT))
        body.addView(today)

        CallUpdate.ready(this)?.let { u ->
            val c=CallUi.card(this,rail=CallUi.ACCENT).apply { tag="home_update" }
            c.addView(CallUi.text(this,"새 버전이 준비됐어요",CallUi.HEADING,true))
            if(u.notes.isNotBlank()) c.addView(CallUi.text(this,u.notes,CallUi.BODY))
            c.addView(CallUi.bigButton(this,"","지금 업데이트") { CallUpdate.install(this) }.apply { tag="home_update_button" })
            body.addView(c)
        }

        // First-run checklist: done steps get a tick, only the next step is open (one thing at a time).
        class Step(val done:Boolean,val label:String,val detail:String,val button:String,val action:()->Unit)
        val steps=listOfNotNull(
            Step(s.folder,"녹음 폴더 연결","통화 녹음 폴더(보통 Recordings › Call)가 열리면 아래 [이 폴더 사용]을 누르세요.","녹음 폴더 고르기") { pickFolder.launch(CallFolder.pickIntent()) },
            Step(s.sms,"문자 읽기 허용","업무 문자도 통화와 함께 정리해요. 문자는 휴대폰 안에서 읽어요.","문자 읽기 허용") { askSms.launch(android.Manifest.permission.READ_SMS) },
            if(CallInboxConfig.enabled) null else Step(s.key,"Claude 키 넣기","통화를 요약하는 데 쓰는 키예요. 설정 화면에 붙여 넣으면 돼요.","설정에서 키 넣기") { open(CallSettingsActivity::class.java) },
            Step(s.consent,"정리 동의","녹음 파일은 휴대폰에 그대로 두고, 글자로 바꾼 통화 내용만 보내 정리해요. 설정에서 언제든 끌 수 있어요.","동의하고 켜기") { agree() },
            Step(s.model=="READY","음성 인식 모델 받기",
                if(s.model=="DOWNLOADING") "받는 중이에요"+(if(s.modelPercent>=0) " ${s.modelPercent}%" else "")+". Wi-Fi에 연결해 두세요. (약 570MB)" else "Wi-Fi에서 한 번만 받아요. (약 570MB)",
                if(s.model=="DOWNLOADING") "다시 확인하기" else "모델 받기") {
                PipelineModelDownloadWork.enqueue(this);android.widget.Toast.makeText(this,"Wi-Fi에 연결되면 받기 시작해요.",android.widget.Toast.LENGTH_LONG).show();load()
            },
            Step(s.notify,"알림 허용","아침 6시에 체크할 일을, 저녁 7시에 통화 정리를 알림으로 보내드려요.","알림 허용하기") {
                if(android.os.Build.VERSION.SDK_INT>=33)askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
            Step(s.battery,"배터리 제한 풀기","휴대폰이 쉬는 동안에도 통화를 정리하려면 한 번 허용해 주세요.","제한 풀기") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:$packageName"))) }
                    .onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
        if(!steps.all{it.done}) {
            val c=CallUi.card(this,rail=CallUi.AMBER_RAIL).apply { tag="home_setup" }
            c.addView(CallUi.text(this,"처음 설정 ${steps.count{it.done}}/${steps.size}",CallUi.HEADING,true))
            val next=steps.indexOfFirst { !it.done }
            steps.forEachIndexed { i,st ->
                val color=when { st.done -> CallUi.OK;i==next -> CallUi.INK;else -> CallUi.SUBTLE }
                c.addView(CallUi.text(this,(if(st.done) "✓  " else "${i+1}.  ")+st.label,CallUi.BODY,i==next,color))
                if(i==next) { c.addView(CallUi.text(this,st.detail,CallUi.SMALL,color=CallUi.SUBTLE));c.addView(CallUi.bigButton(this,"",st.button,st.action).apply { tag="home_setup_next" }) }
            }
            body.addView(c)
        }

        // Today's call memos: who, when, one sentence. Tap a memo for the whole call.
        body.addView(CallUi.section(this,"오늘 통화 메모",if(s.calls>0) "전체 보기 ›" else null) { CallUi.go(this,CallUi.Tab.CALLS) })
        val memo=CallUi.card(this).apply { tag="home_memos";setPadding(0,CallUi.dp(this@HomeActivity,6),0,CallUi.dp(this@HomeActivity,6)) }
        if(s.memos.isEmpty()) memo.addView(CallUi.text(this,"오늘은 아직 정리된 통화가 없어요",CallUi.BODY,color=CallUi.SUBTLE).apply { setPadding(CallUi.dp(this@HomeActivity,16),CallUi.dp(this@HomeActivity,10),CallUi.dp(this@HomeActivity,16),CallUi.dp(this@HomeActivity,10)) })
        s.memos.forEachIndexed { i,c ->
            if(i>0) memo.addView(android.view.View(this).apply { setBackgroundColor(CallUi.LINE) },LinearLayout.LayoutParams(-1,CallUi.dp(this,1)).apply { marginStart=CallUi.dp(this@HomeActivity,78);marginEnd=CallUi.dp(this@HomeActivity,20) })
            memo.addView(CallUi.memoRow(this,CallUi.time(c.startedMs),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c.state),c.summary==null) {
                startActivity(Intent(this,TodayCallsActivity::class.java).putExtra(TodayCallsActivity.EXTRA_CALL,c.recordId))
            }.apply { tag="home_memo_${c.recordId}" })
        }
        body.addView(memo)

        // Open to-dos per company: one bar each, the busiest on top.
        if(left>0) {
            body.addView(CallUi.section(this,"업체별 체크할 일","전체 보기 ›") { CallUi.go(this,CallUi.Tab.TASKS) })
            val c=CallUi.card(this).apply { tag="home_task_mix" }
            val max=s.byCompany.maxOf { it.second }
            s.byCompany.take(5).forEach { (name,n) -> c.addView(CallUi.barRow(this,name,n,max)) }
            body.addView(c)
        }

        // This week's calls, one bar per day, today in navy.
        if(s.week.size==7) {
            body.addView(CallUi.section(this,"이번 주 통화","${s.week.sum()}건"))
            val c=CallUi.card(this).apply { tag="home_week" }
            val days=(6 downTo 0).map { CallUi.today().minusDays(it.toLong()) }
            c.addView(CallUi.BarChart(this,s.week,days.mapIndexed { i,d -> if(i==6) "오늘" else CallUi.weekday(d) }),LinearLayout.LayoutParams(-1,CallUi.dp(this,150)))
            body.addView(c)
        }
    }
}
