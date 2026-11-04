package kr.callbrief.pipeline

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.SecureSettings

/** App home, a dashboard: today's numbers, the first-run checklist until setup is done, today's call memos,
 * open to-dos per company and this week's calls per day. The bottom bar leads to every other screen. */
internal class HomeScreen(a:MainActivity):AppScreen(a) {
    internal data class Snapshot(val folder:Boolean,val consent:Boolean,val model:String,
                                 val calls:Int,val working:Int,val newTasks:Int,val due:Int,val overdue:Int,val lastAnalyzedMs:Long,
                                 val notify:Boolean=true,val battery:Boolean=true,val modelPercent:Int=-1,val nextReport:String="",val companies:Int=0,
                                 /** Calls per day, six days ago .. today. */
                                 val week:List<Int> =emptyList(),
                                 /** Open to-dos per company, most first ("업체 없음" for the rest). */
                                 val byCompany:List<Pair<String,Int>> =emptyList(),
                                 /** Today's calls, latest first, for the memo list. */
                                 val memos:List<CallRecord> =emptyList(),
                                 val sms:Boolean=true,val callLog:Boolean=true,
                                 /** 처음 정리 while it runs, else null. */
                                 val firstPass:FirstPassView?=null,
                                 /** Open to-dos asked for today and the ones asking for a check, those first (the short list on home). */
                                 val tasks:List<OpenItem> =emptyList(),
                                 /** [모델 받기] was pressed and the model is not coming yet (it waits for Wi-Fi). */
                                 val modelWanted:Boolean=false,
                                 /** Notifications refused twice, or switched off: the step opens the app's notification settings. */
                                 val notifyBlocked:Boolean=false,
                                 /** The model file being downloaded, for the percent shown every second. */
                                 val modelPart:String="")
    /** [audioMinutesLeft]: how long the recordings still to transcribe are, together. [lowBattery]: not charging and under half
     * the battery, so old recordings wait ([FirstPass.powerOk]). [stalled]: nothing came back for [FirstPass.STALL_MS], so no
     * new batch goes up (design C7). Either one shows the "잠시 쉬고 있어요" line (#20). */
    internal data class FirstPassView(val toTranscribe:Int,val hoursLeft:Double?,val waiting:Int,val unrecorded:Int,val mailConnected:Boolean=true,
                                      val audioMinutesLeft:Long?=null,val lowBattery:Boolean=false,val stalled:Boolean=false)
    internal companion object {
        /** Minutes of audio as one big number and its unit: under an hour in minutes, then whole hours ("약" is in the label). */
        fun roughLength(minutes:Long):Pair<String,String> = if(minutes<60) "${minutes.coerceAtLeast(1)}" to "분" else "${(minutes+30)/60}" to "시간"
        /** How many to-dos "먼저 볼 체크할 일" shows; the rest are one tap away ("외 n개 보기 ›"). */
        const val FIRST_TASKS=5
        /** "먼저 볼 체크할 일" (design G3, review #17): the open to-dos asked for today and the ones asking for a check, those first. */
        internal fun firstLook(open:List<OpenItem>,today:java.time.LocalDate)=
            TaskActions.ordered(open).filter { it.status=="needs_confirmation" || (it.createdMs>0 && CallRecordStore.kstDay(it.createdMs)==today) }
        /** The memo list's words when today has calls but none is sorted out yet. */
        fun sortingWords(calls:Int)="오늘 통화 ${calls}건을 정리하고 있어요"
    }
    private val body:LinearLayout get()=a.column
    /** The folder picked on home is saved off the main thread (Keystore, the folder permission, WorkManager: review #21); the
     * step's button says "확인하는 중이에요" meanwhile. Registered while the Activity is made, like its own launchers. */
    private val pickFolder=a.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode==android.app.Activity.RESULT_OK) saveFolder(r.data)
    }
    /** The notification question; a refusal is counted so that after two the step opens the notification settings (review #13). */
    private val askNotify=a.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        NotifyAsk.noteResult(a,ok);if(live()) load()
    }
    private val askSms get()=a.askSms
    private val askCallLog get()=a.askCallLog
    private fun granted(p:String)=a.checkSelfPermission(p)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun notifyAllowed()=NotifyAsk.allowed(a)
    private fun batteryFree()=a.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(a.packageName)==true
    /** The picked folder is being saved. */
    private var savingFolder=false
    /** [지금 업데이트] is checking the file and copying it into the install session. */
    private var updating=false
    private val ticker=android.os.Handler(android.os.Looper.getMainLooper())
    private var tick:Runnable?=null

    // Text and call-log access are asked from the setup card, after it says why (round 2), not as the app opens.
    override fun enter() { load() }
    override fun leave() { stopTicker() }
    /** [지금 업데이트]: installs the version that is here; otherwise starts getting it and shows its bar in settings (round 6 v2.5).
     * The SHA-256 check and the copy into the install session run off the main thread (review #21); the button says
     * "확인하는 중이에요" until then, and a second press does nothing. */
    internal fun updateNow() {
        if(updating) return;updating=true
        body.findViewWithTag<TextView>("home_update_button")?.let { if(!CallUi.isBusy(it)) CallUi.busy(it,"확인하는 중이에요") }
        val m=metered()
        a.lifecycleScope.launch {
            val r=withContext(Dispatchers.IO) { runCatching { CallUpdate.install(a,m) } }
            updating=false
            if(a.isFinishing || a.isDestroyed) return@launch
            body.findViewWithTag<TextView>("home_update_button")?.let(CallUi::idle)
            r.onFailure { android.widget.Toast.makeText(a,"업데이트를 시작하지 못했어요. 잠시 후 다시 눌러 주세요.",android.widget.Toast.LENGTH_LONG).show() }
            if(r.getOrNull()==false) a.switchTo(Screen.SETTINGS)
        }
    }
    private fun metered()=runCatching { a.getSystemService(android.net.ConnectivityManager::class.java)?.let { it.activeNetwork!=null && it.isActiveNetworkMetered } }.getOrNull()==true

    private fun saveFolder(data:Intent?) {
        savingFolder=true
        body.findViewWithTag<TextView>("home_setup_next")?.let { CallUi.busy(it,"확인하는 중이에요") }
        a.lifecycleScope.launch {
            val words=withContext(Dispatchers.IO) { runCatching { CallFolder.save(a,data) }.getOrElse { "이 폴더를 연결하지 못했어요. 다시 골라 주세요." } }
            savingFolder=false
            if(a.isFinishing || a.isDestroyed) return@launch
            words?.let { android.widget.Toast.makeText(a,it,android.widget.Toast.LENGTH_LONG).show() }
            if(live()) load()
        }
    }

    private fun load() {
        // Nothing of home on the page yet (it was just opened): the greeting and "불러오는 중이에요" at once.
        if(body.findViewWithTag<View>("home_today")==null && body.findViewWithTag<View>("loading")==null) {
            body.removeAllViews();CallUi.band(body,CallUi.greeting(),eyebrow=CallUi.date(CallUi.today()));CallUi.loading(body)
        }
        a.lifecycleScope.launch {
            val snap=withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            if(!live())return@launch
            render(snap)
        }
    }

    internal fun read():Snapshot {
        val t0=System.nanoTime()
        try { return readOnce() } finally {
            // Measurement only (r8-1): how long one home read took, no content.
            val ms=(System.nanoTime()-t0)/1_000_000L;android.util.Log.i("HomeScreen","read ms=$ms")
            runCatching { SecureSettings(CallPipelineStartup.storage(a)).update { it.put(kr.callbrief.IeumApplication.HOME_READ_MS,ms) } }
        }
    }
    /** One store for the whole read (r8-1): the process store, opened once and not closed here. */
    private fun readOnce():Snapshot {
        val storage=CallPipelineStartup.storage(a)
        if(kr.callbrief.BuildConfig.LOCAL_PREVIEW) PreviewSeed.ensure(storage)
        val s=SecureSettings(storage).snapshot()
        val folder=s.optString("treeUri").isNotBlank()
        if(folder&&!kr.callbrief.BuildConfig.LOCAL_PREVIEW) {
            runCatching { AutomaticRecordingDiscovery.ensure(storage) }
            runCatching { CallTranscriptionWork.schedule(storage) }
        }
        val today=CallUi.today()
        val db=kr.callbrief.IeumApplication.store(storage)
        val (calls,due,newTasks)=Triple(db.day(today),db.dueItems(today),db.newTaskCount(today))
        // "오늘 통화" counts as the call list does: the records and the call-log lines beside them (review #18).
        val logs=runCatching { db.dayLog(today) }.getOrDefault(emptyList())
        val companies=runCatching { db.companyCount() }.getOrDefault(0)
        val week=runCatching { (6 downTo 0).map { db.day(today.minusDays(it.toLong())).size } }.getOrDefault(emptyList())
        val open=runCatching { db.openItems() }.getOrDefault(emptyList())
        val byCompany=open.groupingBy { it.company ?: "업체 없음" }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        val mm=PipelineModelManager(storage)
        val percent=if(mm.partFile.isFile) (mm.partFile.length()*100/574041195L).toInt().coerceIn(0,99) else -1
        val next=CallUi.nextReport(s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),s.optInt(PipelineSettingsKeys.MORNING_HOUR,6))
        val model=s.optString(PipelineSettingsKeys.MODEL_STATE)
        if(model=="READY") ModelWish.clear(a)
        return Snapshot(folder,s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),
            model,CallsScreen.callCount(calls,logs),calls.count{it.state!=CallRecordStore.ANALYZED},newTasks,
            due.size,due.count{CallUi.overdue(it.due)},s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),batteryFree(),percent,next,companies,
            week,byCompany,calls.sortedByDescending { it.startedMs ?: 0 }.take(4),
            granted(android.Manifest.permission.READ_SMS),granted(android.Manifest.permission.READ_CALL_LOG),firstPass(storage,db,s),firstLook(open,today),
            ModelWish.wanted(a),NotifyAsk.blocked(a),mm.partFile.path)
    }
    private fun firstPass(storage:android.content.Context,db:CallRecordStore,s:org.json.JSONObject):FirstPassView? {
        val start=FirstPass.start(s) ?: return null
        if(FirstPass.done(s) || !s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)) return null
        val p=runCatching { db.firstPassProgress(start) }.getOrNull() ?: return null
        val msPerMinute=s.optLong(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,72_000L)
        val perMinute=p.bytesPerMinute?.takeIf { it>0 && p.bytesLeft>0 }
        val hours=perMinute?.let { p.bytesLeft.toDouble()/it*msPerMinute/3_600_000.0 }
        val now=System.currentTimeMillis()
        // The same test as CallRecordWork.batchSlots: something sent waits for STALL_MS and no result came in that long.
        val stalled=p.waiting>0 && runCatching { db.oldestUnansweredSentMs() }.getOrNull()
            ?.let { since -> now-since>=FirstPass.STALL_MS && now-s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0)>=FirstPass.STALL_MS } ?: false
        return FirstPassView(p.toTranscribe,hours,p.waiting,p.unrecorded,s.optString(PipelineSettingsKeys.MAIL_HOST).isNotBlank(),
            perMinute?.let { (p.bytesLeft+it/2)/it },p.toTranscribe>0 && !FirstPass.powerOk(storage),stalled)
    }

    /** The model step's words: coming with its percent, waiting for Wi-Fi after [모델 받기], or what it is. */
    private fun modelWords(state:String,percent:Int,wanted:Boolean)=when {
        state=="DOWNLOADING" -> downloadingWords(percent)
        wanted -> "Wi-Fi에 연결되면 받기 시작해요. (약 570MB)"
        else -> "Wi-Fi에서 한 번만 받아요. (약 570MB)"
    }
    private fun downloadingWords(percent:Int)="받는 중이에요"+(if(percent>=0) " ${percent}%" else "")+". Wi-Fi에 연결해 두세요. (약 570MB)"
    /** Every second while the model comes: the percent from the size of the part file (no store, no settings read). When
     * the part file is gone the download ended (done or failed) and home reads again. */
    private fun startTicker(s:Snapshot) {
        stopTicker()
        val part=java.io.File(s.modelPart)
        val r=object:Runnable { override fun run() {
            if(!live() || tick!==this) return
            if(!part.isFile) { tick=null;load();return }
            val p=(part.length()*100/574041195L).toInt().coerceIn(0,99)
            body.findViewWithTag<TextView>("home_model_detail")?.text=downloadingWords(p)
            ticker.postDelayed(this,1000)
        } }
        tick=r;ticker.postDelayed(r,1000)
    }
    private fun stopTicker() { tick?.let(ticker::removeCallbacks);tick=null }

    private fun agree() {
        a.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { SecureSettings(CallPipelineStartup.storage(a)).update { it.put(PipelineSettingsKeys.CONSENT_GRANTED,true) } } }
            CallPipelineStartup.settingsChanged(a);load()
        }
    }

    internal fun render(s:Snapshot?) {
        val keep=CallUi.scrollY(body)
        stopTicker()
        body.removeAllViews()
        CallUi.band(body,CallUi.greeting(),eyebrow=CallUi.date(CallUi.today()))
        if(s==null) { CallUi.retryCard(body,"정보를 읽지 못했어요. 잠시 후 다시 불러와 주세요.") { body.removeAllViews();load() };return }

        // Today at a glance: three big numbers (only the open to-dos in the accent), then when the next report comes.
        val today=CallUi.card(a).apply { tag="home_today" }
        val left=s.byCompany.sumOf { it.second }
        today.addView(CallUi.stats(a,listOf(Triple("${s.calls}","오늘 통화",CallUi.INK),Triple("${s.newTasks}","오늘 생긴 체크",CallUi.INK),
            Triple("$left","남은 체크할 일",CallUi.ACCENT)),listOf("건","개","개")).apply { tag="home_calls" })
        today.addView(CallUi.divider(a))
        today.addView(CallUi.caption(a,s.nextReport.takeIf{it.isNotBlank()}?.let{"다음 알림 $it"} ?: "마지막 정리 "+CallUi.dateTime(s.lastAnalyzedMs)).apply { tag="home_next_report";setPadding(0,0,0,0) })
        if(s.working>0) today.addView(CallUi.pill(a,"정리 중 ${s.working}건",CallUi.SUBTLE))
        body.addView(today)

        // A newer version: ready to install, coming, or known and not yet here. The one button always does something.
        val ready=CallUpdate.ready(a);val fetch=CallUpdate.fetchState(a)
        (ready ?: CallUpdate.available(a))?.let { u ->
            val c=CallUi.card(a,rail=CallUi.ACCENT).apply { tag="home_update" }
            val coming=ready==null && fetch.stage==CallUpdate.Stage.DOWNLOADING
            c.addView(CallUi.text(a,when { ready!=null -> "새 버전이 준비됐어요";coming -> "새 버전 ${CallUpdate.shownName(u)} 받는 중 ${fetch.percent}%"
                else -> "새 버전 ${CallUpdate.shownName(u)} 받을 수 있어요" },CallUi.HEADING,true).apply { tag="home_update_state" })
            if(coming) c.addView(UpdateViews.bar(a,fetch.percent))
            if(u.notes.isNotBlank()) c.addView(CallUi.text(a,u.notes,CallUi.BODY))
            c.addView(CallUi.bigButton(a,"",if(coming) "받는 상황 보기" else "지금 업데이트",if(coming) null else "확인하는 중이에요") { if(coming) a.switchTo(Screen.SETTINGS) else updateNow() }
                .apply { tag="home_update_button";if(updating && !coming) CallUi.busy(this,"확인하는 중이에요") })
            body.addView(c)
        }

        // First-run checklist: done steps get a tick, only the next step is open (one thing at a time).
        class Step(val done:Boolean,val label:String,val detail:String,val button:String,val busy:Boolean=false,val detailTag:String?=null,val action:()->Unit)
        /** Asked once and still not allowed: Android may be holding it as a restricted setting for a sideloaded app. */
        fun readingStep(done:Boolean,permission:String,what:String,detail:String,ask:androidx.activity.result.ActivityResultLauncher<String>)=
            if(!done && ReadingPermission.blocked(a,permission)) Step(false,"$what 읽기 허용",ReadingPermission.restrictedHelp(what),"앱 정보 열기") { ReadingPermission.openAppInfo(a) }
            else Step(done,"$what 읽기 허용",detail,"$what 읽기 허용") { ReadingPermission.markAsked(a,permission);ask.launch(permission) }
        val steps=listOfNotNull(
            Step(s.folder,"녹음 폴더 연결","통화 녹음 폴더(보통 Recordings › Call)가 열리면 아래 [이 폴더 사용]을 누르세요.","녹음 폴더 고르기",busy=savingFolder) {
                if(!savingFolder) pickFolder.launch(CallFolder.pickIntent()) },
            readingStep(s.sms,android.Manifest.permission.READ_SMS,"문자","업무 문자도 통화와 함께 정리해요. 문자는 휴대폰 안에서 읽어요.",askSms),
            readingStep(s.callLog,android.Manifest.permission.READ_CALL_LOG,"통화기록","녹음과 통화기록을 맞춰서 상대 번호를 찾아요. 통화기록은 휴대폰 밖으로 나가지 않아요.",askCallLog),
            Step(s.consent,"정리 동의","녹음 파일은 휴대폰에 그대로 두고, 글자로 바꾼 통화 내용만 보내 정리해요. 설정에서 언제든 끌 수 있어요.","동의하고 켜기") { agree() },
            // On mobile data the download waits for Wi-Fi: the step says so and keeps saying it (review #23); while it comes,
            // the percent moves every second.
            Step(s.model=="READY","음성 인식 모델 받기",modelWords(s.model,s.modelPercent,s.modelWanted),
                if(s.model=="DOWNLOADING") "다시 확인하기" else "모델 받기",detailTag="home_model_detail") {
                ModelWish.mark(a);PipelineModelDownloadWork.enqueue(a);load()
            },
            // Refused twice the system no longer asks: the step opens the app's notification settings instead (review #13).
            Step(s.notify,"알림 허용",if(s.notifyBlocked) "알림이 꺼져 있어요. 알림 설정에서 이음 알림을 켜 주세요." else "아침 6시에 체크할 일을, 저녁 7시에 통화 정리를 알림으로 보내드려요.",
                if(s.notifyBlocked) "알림 설정 열기" else "알림 허용하기") {
                if(s.notifyBlocked || android.os.Build.VERSION.SDK_INT<33) NotifyAsk.openSettings(a) else askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
            Step(s.battery,"배터리 제한 풀기","휴대폰이 쉬는 동안에도 통화를 정리하려면 한 번 허용해 주세요.","제한 풀기") {
                runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:${a.packageName}"))) }
                    .onFailure { a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
        if(!steps.all{it.done}) {
            val c=CallUi.card(a,rail=CallUi.AMBER_RAIL).apply { tag="home_setup" }
            val doneCount=steps.count{it.done}
            // Title with "4/7" on the right and a progress bar; every step keeps its own number, so the open one reads "1단계".
            c.addView(LinearLayout(a).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
                addView(CallUi.text(a,"처음 설정",CallUi.HEADING,true).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
                addView(CallUi.text(a,"$doneCount/${steps.size}",CallUi.BODY,true,CallUi.SUBTLE).apply { tag="home_setup_count";setPadding(0,0,0,0);fontFeatureSettings="tnum" })
            })
            c.addView(CallUi.StackBar(a,listOf(doneCount to CallUi.AMBER_RAIL,(steps.size-doneCount) to CallUi.CARD),split=false),
                LinearLayout.LayoutParams(-1,CallUi.dp(a,8)).apply { topMargin=CallUi.dp(a,10);bottomMargin=CallUi.dp(a,14) })
            val next=steps.indexOfFirst { !it.done }
            // Finished steps fold away; only the step in front of the user is open (one thing at a time), the rest wait below in grey.
            steps.forEachIndexed { i,st ->
                if(st.done) return@forEachIndexed
                if(i==next) {
                    c.addView(CallUi.caption(a,"${i+1}단계",CallUi.SUBTLE,true).apply { tag="home_setup_step";setPadding(0,0,0,0) })
                    c.addView(CallUi.text(a,st.label,CallUi.BODY,true).apply { setPadding(0,0,0,CallUi.dp(a,4)) })
                    c.addView(CallUi.text(a,st.detail,CallUi.CAPTION,color=CallUi.SUBTLE).apply { tag=st.detailTag ?: "home_setup_detail" })
                    c.addView(CallUi.bigButton(a,"",st.button,st.action).apply { tag="home_setup_next";if(st.busy) CallUi.busy(this,"확인하는 중이에요") })
                    if(st.detailTag=="home_model_detail" && s.model=="DOWNLOADING" && s.modelPart.isNotEmpty()) startTicker(s)
                } else c.addView(CallUi.text(a,"${i+1}단계  "+st.label,CallUi.BODY,color=CallUi.SUBTLE).apply { setPadding(0,CallUi.dp(a,10),0,0) })
            }
            body.addView(c)
        }

        // 처음 정리: what is left before the calls since 10/1 become the context of the next ones. While it rests (battery under
        // half and not charging, or no result for six hours) the card turns amber and says so first, then what is left (#20).
        s.firstPass?.let { f ->
            val resting=f.lowBattery || f.stalled
            val c=CallUi.card(a,rail=if(resting) CallUi.AMBER_RAIL else CallUi.ACCENT).apply { tag="home_first_pass" }
            if(resting) {
                c.addView(CallUi.text(a,"잠시 쉬고 있어요",CallUi.HEADING,true).apply { tag="home_first_pass_rest" })
                c.addView(CallUi.text(a,if(f.lowBattery) "충전기에 꽂으면 이어서 정리해요." else "결과가 오면 이어서 정리해요.",CallUi.BODY,true)
                    .apply { tag="home_first_pass_rest_why" })
                c.addView(CallUi.text(a,"10월 1일부터 밀린 통화를 정리하는 중이에요. 오늘 통화는 지금처럼 바로 정리돼요.",CallUi.BODY,color=CallUi.SUBTLE))
                if(f.toTranscribe>0) {
                    c.addView(CallUi.divider(a))
                    val items=mutableListOf(Triple("${f.toTranscribe}","남은 녹음",CallUi.INK));val units=mutableListOf("개")
                    f.audioMinutesLeft?.takeIf { it>0 }?.let { m -> val (n,u)=roughLength(m);items+=Triple(n,"남은 녹음 시간 (약)",CallUi.INK);units+=u }
                    c.addView(CallUi.stats(a,items,units).apply { tag="home_first_pass_left" })
                }
                val rest=buildList {
                    if(f.waiting>0) add("요약을 기다리는 기록 ${f.waiting}건")
                    if(f.unrecorded>0) add("녹음 없는 통화 ${f.unrecorded}건 (통화기록 기준)")
                }
                if(rest.isNotEmpty() && f.toTranscribe==0) c.addView(CallUi.divider(a))
                rest.forEach { c.addView(CallUi.text(a,it,CallUi.BODY,true).apply { setPadding(0,CallUi.dp(a,8),0,0) }) }
                if(f.lowBattery) c.addView(CallUi.caption(a,"배터리가 절반 넘게 차도 이어서 해요.").apply { setPadding(0,CallUi.dp(a,8),0,0) })
            } else {
                c.addView(CallUi.text(a,"지난 통화를 정리하고 있어요",CallUi.HEADING,true))
                c.addView(CallUi.text(a,"10월 1일부터의 통화와 문자를 시간 순서대로 정리하고 있어요. 오늘 통화는 지금처럼 바로 정리돼요. 충전해 두면 더 빨리 끝나요.",CallUi.BODY))
                val lines=buildList {
                    if(f.toTranscribe>0) add("글자로 바꿀 녹음 ${f.toTranscribe}개"+(f.hoursLeft?.let { h -> ", 약 ${if(h<1) "1시간 안" else "${Math.round(h)}시간"}" } ?: ""))
                    if(f.waiting>0) add("요약을 기다리는 기록 ${f.waiting}건")
                    if(f.toTranscribe==0 && f.waiting==0) add("지난 문자와 메일을 읽고 있어요")
                    if(f.unrecorded>0) add("녹음 없는 통화 ${f.unrecorded}건 (통화기록 기준)")
                }
                lines.forEach { c.addView(CallUi.text(a,it,CallUi.BODY,true)) }
                if(f.toTranscribe>0) c.addView(CallUi.caption(a,"지난 녹음은 충전 중이거나 배터리가 절반 넘게 남았을 때 바꿔요."))
            }
            if(!f.mailConnected) c.addView(CallUi.caption(a,"메일을 연결하면 메일도 함께 정리해요. 설정의 메일에서 연결할 수 있어요.").apply { tag="home_first_pass_mail" })
            body.addView(c)
        }

        // The to-dos to look at first right under the numbers (no scrolling needed): asked for today and asking for a check,
        // at most five, each with its own [다 됐어요]; the rest of the open ones are one tap away (design G3, review #17).
        val first=s.tasks.take(FIRST_TASKS);val more=left-first.size
        body.addView(CallUi.section(a,"먼저 볼 체크할 일",if(more>0) "외 ${more}개 보기 ›" else null) { a.switchTo(Screen.TASKS) }.apply { tag="home_tasks_head" })
        if(first.isEmpty()) body.addView(CallUi.card(a).apply { tag="home_tasks"
            addView(CallUi.text(a,if(left>0) "오늘 요청된 체크할 일이 없어요" else "체크할 일이 없어요",CallUi.BODY,color=CallUi.SUBTLE)) })
        else body.addView(CallUi.list(a,first.map { item ->
            CallUi.taskRow(a,item.title,item.createdMs,item.company,item.counterpart,if(item.status=="needs_confirmation") "" else item.status,
                onDone={ row -> TaskActions.complete(a,item,row) { load() } },onOpen={ TaskActions.openSource(a,item) },aiNote=item.aiNote)
                .apply { tag="home_task_${item.id}" }
        },inset=16).apply { tag="home_tasks" })

        // Today's call memos: who, when, one sentence. Tap a memo for the whole call.
        body.addView(CallUi.section(a,"오늘 통화 메모",if(s.calls>0) "전체 보기 ›" else null) { a.switchTo(Screen.CALLS) })
        // No record yet while today has calls (just made, being turned into text): say they are being sorted out (review #18).
        if(s.memos.isEmpty()) body.addView(CallUi.card(a).apply { tag="home_memos"
            addView(CallUi.text(a,if(s.calls>0) sortingWords(s.calls) else "오늘은 아직 정리된 통화가 없어요",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="home_memos_none" }) })
        else body.addView(CallUi.list(a,s.memos.map { c ->
            CallUi.memoRow(a,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallsScreen.stateWords(c),c.summary==null) {
                a.openCall(c.recordId,c.dayMs)
            }.apply { tag="home_memo_${c.recordId}" }
        }).apply { tag="home_memos" })

        // Open to-dos per company: one bar each, the busiest on top.
        if(left>0) {
            body.addView(CallUi.section(a,"업체별 체크할 일","전체 보기 ›") { a.switchTo(Screen.TASKS) })
            val c=CallUi.card(a).apply { tag="home_task_mix" }
            val max=s.byCompany.maxOf { it.second }
            s.byCompany.take(5).forEach { (name,n) -> c.addView(CallUi.barRow(a,name,n,max)) }
            body.addView(c)
        }

        // This week's calls, one bar per day, today in the accent.
        if(s.week.size==7) {
            body.addView(CallUi.section(a,"이번 주 통화","${s.week.sum()}건"))
            val c=CallUi.card(a).apply { tag="home_week" }
            val days=(6 downTo 0).map { CallUi.today().minusDays(it.toLong()) }
            c.addView(CallUi.BarChart(a,s.week,days.mapIndexed { i,d -> if(i==6) "오늘" else CallUi.weekday(d) }),LinearLayout.LayoutParams(-1,CallUi.dp(a,150)))
            body.addView(c)
        }
        CallUi.restoreScroll(body,keep)
    }
}

/** The notification question on home (review #13). Android stops showing it after two refusals, so after two the step opens
 * the app's notification settings. Allowed means the permission (Android 13 and later) and the app's notification switch. */
internal object NotifyAsk {
    private const val POST="android.permission.POST_NOTIFICATIONS"
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    fun refusals(c:android.content.Context)=prefs(c).getInt("notify_refusals",0)
    fun noteResult(c:android.content.Context,granted:Boolean) {
        prefs(c).edit().putInt("notify_refusals",if(granted) 0 else refusals(c)+1).apply()
    }
    fun allowed(c:android.content.Context):Boolean {
        val permission=android.os.Build.VERSION.SDK_INT<33 || c.checkSelfPermission(POST)==android.content.pm.PackageManager.PERMISSION_GRANTED
        return permission && (c.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() ?: true)
    }
    /** Not allowed, and asking again shows nothing: refused twice, or allowed but the switch is off (or Android 12 and before). */
    fun blocked(c:android.content.Context):Boolean {
        if(allowed(c)) return false
        val permission=android.os.Build.VERSION.SDK_INT<33 || c.checkSelfPermission(POST)==android.content.pm.PackageManager.PERMISSION_GRANTED
        return permission || refusals(c)>=2
    }
    fun settingsIntent(c:android.content.Context)=Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,c.packageName)
    fun openSettings(a:android.app.Activity) {
        runCatching { a.startActivity(settingsIntent(a)) }.onFailure { ReadingPermission.openAppInfo(a) }
    }
}

/** [모델 받기] was pressed: kept until the model is ready, so the step keeps saying it waits for Wi-Fi (review #23). */
internal object ModelWish {
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    fun mark(c:android.content.Context)=prefs(c).edit().putBoolean("model_wanted",true).apply()
    fun wanted(c:android.content.Context)=prefs(c).getBoolean("model_wanted",false)
    fun clear(c:android.content.Context) { if(wanted(c)) prefs(c).edit().remove("model_wanted").apply() }
}

/** Text and call-log access. A sideloaded app on recent Android can get these refused as "제한된 설정" without a dialog:
 * after one ask, the screens point to App info, where the restriction is lifted and the permission is allowed. */
internal object ReadingPermission {
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    fun asked(c:android.content.Context,permission:String)=prefs(c).getBoolean("asked_$permission",false)
    fun markAsked(c:android.content.Context,permission:String)=prefs(c).edit().putBoolean("asked_$permission",true).apply()
    /** Refused and the system will not ask again (or never showed the dialog: a restricted setting). */
    fun blocked(c:android.content.Context,permission:String)=prefs(c).getBoolean("blocked_$permission",false)
    fun noteResult(a:android.app.Activity,permission:String,granted:Boolean) {
        prefs(a).edit().putBoolean("blocked_$permission",!granted && !a.shouldShowRequestPermissionRationale(permission)).apply()
    }
    fun restrictedHelp(what:String)="허용 창이 뜨지 않았다면 앱 정보에서 오른쪽 위 메뉴(⋮)를 눌러 제한된 설정을 허용한 뒤, 권한에서 $what 항목을 허용해 주세요."
    fun openAppInfo(a:android.app.Activity) {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${a.packageName}"))) }
    }
}
