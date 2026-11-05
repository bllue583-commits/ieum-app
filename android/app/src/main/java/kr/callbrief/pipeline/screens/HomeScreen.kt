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
                                 /** v2.9: the phone's call state may be read (READ_PHONE_STATE), so a call's end is seen at once. */
                                 val phoneState:Boolean=true,
                                 /** 처음 정리 while it runs, else null. */
                                 val firstPass:FirstPassView?=null,
                                 /** Open to-dos asked for today and the ones asking for a check, those first (the short list on home). */
                                 val tasks:List<OpenItem> =emptyList(),
                                 /** Notifications refused twice, or switched off: the step opens the app's notification settings. */
                                 val notifyBlocked:Boolean=false,
                                 /** The model file being downloaded, for the percent shown every second. */
                                 val modelPart:String="",
                                 /** The person of each memo's and check item's call, by record id: the name opens the person page (v2.8). */
                                 val people:Map<String,String> =emptyMap(),
                                 /** v3.0 (r11-1): may draw the call card over other apps (the overlay permission). */
                                 val overlay:Boolean=true,
                                 /** v3.0 (r11-1): the call redirection role is held, was asked for once, or does not exist. v3.1: no longer
                                  * a setup step (DESIGN_V4 2.5-1); it stays in settings' 통화 카드 group. */
                                 val callRole:Boolean=true,
                                 /** v3.1 (r12-2's [CallUpdate.setupStep]): this app may not install updates by itself yet (optional step). */
                                 val autoInstallStep:Boolean=false,
                                 /** v3.1 (r12-1's [RecordingGap.banner]): the one line about recordings that do not come, or null. */
                                 val gap:String?=null,
                                 /** v3.1 (r12-2's [CallUpdate.state]): what the update banner says; phase "none" draws nothing new. */
                                 val update:CallUpdate.UpdateState=CallUpdate.UpdateState(),
                                 /** Bytes of the model got so far while it comes (-1: unknown), for "받는 중 42% (240MB / 574MB)". */
                                 val modelBytes:Long=-1)
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

        // ---- v3.1 (DESIGN_V4 2.5, r12-5) ----
        /** The voice model's size (the pinned file of [PipelineModelManager]). */
        const val MODEL_BYTES=574_041_195L
        private fun mb(bytes:Long)="${(bytes/1_000_000L).coerceAtLeast(0)}MB"
        /** The model line on home and in settings (user 11/5 18:27: any connection, no Wi-Fi wait): "음성 인식 모델 받는 중 42%
         * (241MB / 574MB)", waiting for a connection, failed, or about to start. [bytes]: got so far, -1 unknown. */
        fun modelLine(state:String,bytes:Long,offline:Boolean=false,total:Long=MODEL_BYTES):String = when(state) {
            PipelineModelState.READY -> "음성 인식 모델 준비됨 ✓"
            PipelineModelState.FAILED -> "음성 인식 모델을 받지 못했어요. 눌러서 다시 받아 주세요"
            PipelineModelState.UNSUPPORTED -> "이 휴대폰에서는 음성 인식을 쓸 수 없어요"
            else -> when {
                offline -> "인터넷에 연결되면 음성 인식 모델을 이어서 받아요 (${mb(total)})"
                state==PipelineModelState.DOWNLOADING && bytes>=0 ->
                    "음성 인식 모델 받는 중 ${(bytes*100/total).toInt().coerceIn(0,99)}% (${mb(bytes)} / ${mb(total)})"
                state==PipelineModelState.DOWNLOADING -> "음성 인식 모델 받는 중이에요 (${mb(total)})"
                else -> "음성 인식 모델을 받기 시작해요 (${mb(total)})"
            }
        }
        /** Starts the model download (after consent, and when it failed). Tests replace it. */
        @androidx.annotation.VisibleForTesting @Volatile internal var modelStart:(android.content.Context)->Unit={ PipelineModelDownloadWork.enqueue(it) }
        /** Test hooks in place of the other bundles' functions (r12-1 [RecordingGap.banner], r12-2 [CallUpdate.state] and
         * [CallUpdate.setupStep]); null uses the real one. Empty values draw nothing. */
        @androidx.annotation.VisibleForTesting @Volatile internal var gapHook:((android.content.Context)->RecordingGap.Banner?)?=null
        @androidx.annotation.VisibleForTesting @Volatile internal var updateHook:((android.content.Context)->CallUpdate.UpdateState)?=null
        @androidx.annotation.VisibleForTesting @Volatile internal var setupStepHook:((android.content.Context)->Boolean)?=null
        internal fun gapBanner(c:android.content.Context)=runCatching { (gapHook ?: RecordingGap::banner)(c) }.getOrNull()
        internal fun updateState(c:android.content.Context)=runCatching { (updateHook ?: CallUpdate::state)(c) }.getOrDefault(CallUpdate.UpdateState())
        internal fun installStep(c:android.content.Context)=runCatching { (setupStepHook ?: CallUpdate::setupStep)(c) }.getOrDefault(false)
        @androidx.annotation.VisibleForTesting @Volatile internal var checkHook:((android.content.Context,(CallUpdate.UpdateState)->Unit)->Unit)?=null
        /** [지금 확인]: r12-2's [CallUpdate.checkNow] ([done] on the main thread with the new state). */
        internal fun checkNow(c:android.content.Context,done:(CallUpdate.UpdateState)->Unit) {
            runCatching { checkHook?.invoke(c,done) ?: CallUpdate.checkNow(c,done) }.onFailure { done(updateState(c)) }
        }
        /** Update phases that move by themselves: home and settings follow them every second. */
        internal val MOVING=setOf("checking","offline","downloading","waitingConfirm")
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
    /** v2.9 r10-1: the call state question, asked from its setup step after the reason is shown. */
    private val askPhoneState=a.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        ReadingPermission.noteResult(a,android.Manifest.permission.READ_PHONE_STATE,ok);if(live()) load()
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
        val partBytes=if(mm.partFile.isFile) mm.partFile.length() else -1L
        val percent=if(partBytes>=0) (partBytes*100/MODEL_BYTES).toInt().coerceIn(0,99) else -1
        val next=CallUi.nextReport(s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),s.optInt(PipelineSettingsKeys.MORNING_HOUR,6))
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        val model=s.optString(PipelineSettingsKeys.MODEL_STATE)
        // v3.1 (user 11/5 18:27): with consent the model comes by itself on any connection; the unique work keeps one at a time.
        if(consent && model!=PipelineModelState.READY && model!=PipelineModelState.UNSUPPORTED && model!=PipelineModelState.FAILED)
            runCatching { modelStart(storage) }
        val memos=calls.sortedByDescending { it.startedMs ?: 0 }.take(4);val first=firstLook(open,today)
        val people=runCatching { db.personIdsOf(memos.map { it.recordId }+first.take(FIRST_TASKS).mapNotNull { it.recordId }) }.getOrDefault(emptyMap())
        return Snapshot(folder,consent,
            model,CallsScreen.callCount(calls,logs),calls.count{it.state!=CallRecordStore.ANALYZED},newTasks,
            due.size,due.count{CallUi.overdue(it.due)},s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),batteryFree(),percent,next,companies,
            week,byCompany,memos,
            granted(android.Manifest.permission.READ_SMS),granted(android.Manifest.permission.READ_CALL_LOG),granted(android.Manifest.permission.READ_PHONE_STATE),
            firstPass(storage,db,s),first,NotifyAsk.blocked(a),mm.partFile.path,people,
            CallCardAsk.overlayAllowed(a),CallCardAsk.roleDone(a),installStep(a),gapBanner(a)?.text?.takeIf { it.isNotBlank() },updateState(a),partBytes)
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

    private fun offline()=runCatching { a.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork==null }.getOrDefault(false)
    /** Every second while something moves on home: the model's percent from the size of its part file, and the update banner from
     * [CallUpdate.state]. A model that stopped coming (the part file gone) or an update that changed phase reads home again. */
    private fun startTicker(s:Snapshot) {
        stopTicker()
        val part=java.io.File(s.modelPart)
        val followModel=s.model==PipelineModelState.DOWNLOADING && s.modelPart.isNotEmpty()
        val followUpdate=s.update.phase in MOVING
        if(!followModel && !followUpdate) return
        val r=object:Runnable { override fun run() {
            if(!live() || tick!==this) return
            if(followModel) {
                if(!part.isFile) { tick=null;load();return }
                val got=part.length()
                body.findViewWithTag<TextView>("home_model_detail")?.text=modelLine(PipelineModelState.DOWNLOADING,got,offline())
                (body.findViewWithTag<View>("home_model_bar") as? android.widget.ProgressBar)?.progress=(got*100/MODEL_BYTES).toInt().coerceIn(0,99)
            }
            if(followUpdate) {
                val u=updateState(a)
                if(u.phase!=s.update.phase) { tick=null;load();return }
                body.findViewWithTag<TextView>("home_update_state")?.text=u.words
                if(u.percent>=0) (body.findViewWithTag<View>("update_progress") as? android.widget.ProgressBar)?.progress=u.percent
            }
            ticker.postDelayed(this,1000)
        } }
        tick=r;ticker.postDelayed(r,1000)
    }
    private fun stopTicker() { tick?.let(ticker::removeCallbacks);tick=null }

    private fun agree() {
        a.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { SecureSettings(CallPipelineStartup.storage(a)).update { it.put(PipelineSettingsKeys.CONSENT_GRANTED,true) } }
                // v3.1: the voice model starts coming right after the consent, on any connection (user 11/5 18:27).
                runCatching { modelStart(a) } }
            CallPipelineStartup.settingsChanged(a);load()
        }
    }

    // ---- v3.1 (DESIGN_V4 2.5): one-line banners, the voice model line, and first setup with required and optional steps ----
    /** First setup is open (null: open by itself only when nothing else is on home yet, the very first start). */
    private var setupOpen:Boolean?=null
    /** 처음 정리 shows what is left (it starts folded to one line). */
    private var firstPassOpen=false
    /** One line in a soft sheet that opens something: the words in reading size and bold, "›" at the end, 56dp tall. */
    private fun bannerRow(words:String,tag:String,color:Int=CallUi.INK,action:()->Unit)=CallUi.card(a,rail=if(color==CallUi.AMBER) CallUi.AMBER_RAIL else CallUi.ACCENT).apply {
        this.tag=tag;isClickable=true;isFocusable=true;foreground=CallUi.rowRipple();minimumHeight=CallUi.dp(a,56)
        contentDescription="$words. 눌러서 보기"
        addView(CallUi.text(a,"$words ›",CallUi.BODY,true,color).apply { this.tag="${tag}_text" })
        CallUi.onTap(this) { action() }
    }
    /** The line that folds a card: its words, and "›" turning to "접기" while open. */
    private fun foldRow(words:String,tag:String,open:Boolean,action:()->Unit)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;this.tag=tag
        isClickable=true;isFocusable=true;background=CallUi.rowRipple();minimumHeight=CallUi.dp(a,48)
        contentDescription=if(open) "$words. 눌러서 접기" else "$words. 눌러서 펼치기"
        addView(CallUi.text(a,words,CallUi.BODY,true).apply { this.tag="${tag}_text";setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        addView(CallUi.caption(a,if(open) "접기" else "펼치기 ›",CallUi.SUBTLE,true).apply { setPadding(CallUi.dp(a,8),0,0,0) })
        CallUi.onTap(this) { action() }
    }
    /** "지난 통화 정리 중, 녹음 59개 남음" (or what it waits for). */
    internal fun firstPassWords(f:FirstPassView)=when {
        f.lowBattery -> "지난 통화 정리, 충전을 기다리는 중"
        f.stalled -> "지난 통화 정리, 결과를 기다리는 중"
        f.toTranscribe>0 -> "지난 통화 정리 중, 녹음 ${f.toTranscribe}개 남음"
        f.waiting>0 -> "지난 통화 정리 중, 기록 ${f.waiting}건 남음"
        else -> "지난 통화 정리 중"
    }

    /** The new version (r12-2's [CallUpdate.state]): what is happening in one heading, a bar while it comes, and [지금 업데이트] when it
     * can be installed or [다시 확인하기] after a failure. Before r12-2 fills the state ("none"), the v2.5 card of a known version. */
    private fun updateBanner(s:Snapshot) {
        val u=s.update
        if(u.phase=="none" || u.words.isBlank()) { legacyUpdateCard();return }
        val c=CallUi.card(a,rail=if(u.phase=="failed") CallUi.AMBER_RAIL else CallUi.ACCENT).apply { tag="home_update" }
        c.addView(CallUi.text(a,u.words,CallUi.HEADING,true,if(u.phase=="failed") CallUi.AMBER else CallUi.INK).apply { tag="home_update_state";fontFeatureSettings="tnum" })
        if(u.phase=="downloading" && u.percent>=0) c.addView(UpdateViews.bar(a,u.percent))
        when {
            u.canInstall -> c.addView(CallUi.bigButton(a,"","지금 업데이트","확인하는 중이에요") { updateNow() }
                .apply { tag="home_update_button";if(updating) CallUi.busy(this,"확인하는 중이에요") })
            u.phase=="failed" -> c.addView(CallUi.button(a,"다시 확인하기","확인하는 중이에요") {
                val b=body.findViewWithTag<TextView>("home_update_button")
                checkNow(a) { b?.let(CallUi::idle);if(live()) load() }
            }.apply { tag="home_update_button" })
        }
        body.addView(c)
    }
    /** v2.5 card: ready to install, coming, or known and not yet here. The one button always does something. */
    private fun legacyUpdateCard() {
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
    }

    /** The voice model while it is not ready (after the consent it comes by itself on any connection, user 11/5 18:27): "음성 인식
     * 모델 받는 중 42% (241MB / 574MB)" with a bar, moving every second; failed, one button gets it again. Nothing before the consent
     * (the consent step says the model comes next) and nothing once it is ready. */
    private fun modelBanner(s:Snapshot) {
        if(!s.consent || s.model==PipelineModelState.READY) return
        val failed=s.model==PipelineModelState.FAILED
        val c=CallUi.card(a,rail=if(failed) CallUi.AMBER_RAIL else CallUi.ACCENT).apply { tag="home_model" }
        c.addView(CallUi.text(a,modelLine(s.model,s.modelBytes,offline()),CallUi.BODY,true,if(failed) CallUi.AMBER else CallUi.INK).apply { tag="home_model_detail";fontFeatureSettings="tnum" })
        if(s.model==PipelineModelState.DOWNLOADING && s.modelBytes>=0)
            c.addView(UpdateViews.bar(a,(s.modelBytes*100/MODEL_BYTES).toInt().coerceIn(0,99)).apply { tag="home_model_bar";contentDescription="음성 인식 모델 받기" })
        if(s.model!=PipelineModelState.UNSUPPORTED) c.addView(CallUi.caption(a,"통화 녹음을 휴대폰 안에서 글자로 바꾸는 데 써요. 한 번만 받아요."))
        if(failed) c.addView(CallUi.button(a,"다시 받기") { runCatching { modelStart(a) };load() }.apply { tag="home_model_retry" })
        body.addView(c)
    }

    /** One step of first setup. [optional] steps have [나중에 할게요] (they stay in settings); [reason] is said above the button. */
    private class Step(val key:String,val done:Boolean,val label:String,val reason:String,val button:String,val busy:Boolean=false,
                       val optional:Boolean=false,val strong:Boolean=false,val action:()->Unit)
    /** First setup (DESIGN_V4 2.5-1): the required steps first (folder, consent, call log, call state, notifications, battery), then
     * the optional ones (texts, the call card over other apps, installing new versions by itself). Folded it is one line, "처음 설정
     * 남은 4단계, 다음 할 일: 알림 허용 ›"; open, the next step with its reason and one button, the rest in grey below. */
    private fun setupCard(s:Snapshot) {
        /** Asked once and still not allowed: Android may be holding it as a restricted setting for a sideloaded app. */
        fun readingStep(key:String,done:Boolean,permission:String,what:String,reason:String,ask:androidx.activity.result.ActivityResultLauncher<String>,optional:Boolean=false)=
            if(!done && !optional && ReadingPermission.blocked(a,permission)) Step(key,false,"$what 읽기 허용",ReadingPermission.restrictedHelp(what),"앱 정보 열기") { ReadingPermission.openAppInfo(a) }
            else Step(key,done,"$what 읽기 허용",reason,"$what 읽기 허용",optional=optional) { ReadingPermission.markAsked(a,permission);ask.launch(permission) }
        val phone=android.Manifest.permission.READ_PHONE_STATE;val sms=android.Manifest.permission.READ_SMS
        val overlayAgain=!s.overlay && SetupSkip.overlayAsked(a)
        val steps=listOf(
            Step("folder",s.folder,"녹음 폴더 연결","통화 녹음 파일이 있는 폴더를 알려 주면 새 통화를 저절로 가져와요. 폴더가 열리면 보통 Recordings › Call을 고르고 아래 [이 폴더 사용]을 눌러 주세요.",
                "녹음 폴더 고르기",busy=savingFolder) { if(!savingFolder) pickFolder.launch(CallFolder.pickIntent()) },
            Step("consent",s.consent,"정리 동의","녹음 파일은 휴대폰에 그대로 두고, 글자로 바꾼 통화 내용만 보내 정리해요. 동의하면 음성 인식 모델(574MB)을 바로 받기 시작해요. 설정에서 언제든 끌 수 있어요.",
                "동의하고 켜기") { agree() },
            readingStep("callLog",s.callLog,android.Manifest.permission.READ_CALL_LOG,"통화기록","녹음과 통화기록을 맞춰서 상대 번호를 찾아요. 통화기록은 휴대폰 밖으로 나가지 않아요.",askCallLog),
            // v2.9 r10-1: the reason first, then the question; refused for good, the step opens the app's settings.
            if(!s.phoneState && ReadingPermission.blocked(a,phone))
                Step("phone",false,"통화가 끝난 걸 바로 알아차리기","허용 창이 다시 뜨지 않아요. 앱 정보의 권한에서 전화 항목을 허용해 주세요.","앱 정보 열기") { ReadingPermission.openAppInfo(a) }
            else Step("phone",s.phoneState,"통화가 끝난 걸 바로 알아차리기","통화가 끝나면 바로 녹음을 찾아 정리를 시작해요. 통화 내용은 듣지 않고, 전화가 끝났는지만 알아요.","전화 상태 허용") {
                ReadingPermission.markAsked(a,phone);askPhoneState.launch(phone) },
            // Refused twice the system no longer asks: the step opens the app's notification settings instead (review #13).
            Step("notify",s.notify,"알림 허용",if(s.notifyBlocked) "알림이 꺼져 있어요. 알림 설정에서 이음 알림을 켜 주세요." else "아침 6시에 체크할 일을, 저녁 7시에 통화 정리를 알림으로 보내 드려요.",
                if(s.notifyBlocked) "알림 설정 열기" else "알림 허용하기") {
                if(s.notifyBlocked || android.os.Build.VERSION.SDK_INT<33) NotifyAsk.openSettings(a) else askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
            Step("battery",s.battery,"배터리 제한 풀기","휴대폰이 쉬는 동안에도 통화를 정리하려면 한 번 허용해 주세요.","제한 풀기") {
                runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:${a.packageName}"))) }
                    .onFailure { a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
            // Optional: a refusal (or [나중에 할게요]) is an answer, the next step opens (DESIGN_V4 2.5 시험).
            readingStep("sms",s.sms || ReadingPermission.asked(a,sms) || SetupSkip.skipped(a,"sms"),sms,"문자",
                "업무 문자도 통화와 함께 정리해요. 문자는 휴대폰 안에서 읽어요.",askSms,optional=true),
            if(overlayAgain) Step("overlay",SetupSkip.skipped(a,"overlay"),"통화 화면 위에 이음 카드 보기","목록에서 '이음'을 찾아 스위치를 켜 주세요.","다시 열기",optional=true,strong=true) {
                CallCardAsk.openOverlaySettings(a) }
            else Step("overlay",s.overlay || SetupSkip.skipped(a,"overlay"),"통화 화면 위에 이음 카드 보기",
                "전화가 오면 통화 화면 위에 상대와 지난 통화, 체크할 일을 보여 드려요. 열리는 화면에서 이음을 찾아 허용해 주세요.","카드 보기 허용",optional=true) {
                SetupSkip.markOverlayAsked(a);CallCardAsk.openOverlaySettings(a) },
            Step("install",!s.autoInstallStep || SetupSkip.skipped(a,"install"),"새 버전을 저절로 설치하기",
                "새 버전이 나오면 누르지 않아도 저절로 설치돼요. 열리는 화면에서 '이 출처 허용'을 켜 주세요.","설치 허용",optional=true) {
                CallUpdate.openInstallSourceSettings(a) })
        val left=steps.filter { !it.done }
        if(left.isEmpty()) return
        val next=left.first()
        val open=setupOpen ?: (s.firstPass==null && s.tasks.isEmpty())
        val c=CallUi.card(a,rail=CallUi.AMBER_RAIL).apply { tag="home_setup" }
        c.addView(foldRow("처음 설정 남은 ${left.size}단계",(if(open) "home_setup_head" else "home_setup_banner"),open) { setupOpen=!open;render(s) }
            .apply { (findViewWithTag<TextView>("${tag}_text"))?.tag="home_setup_count" })
        c.addView(CallUi.text(a,"다음 할 일: ${next.label}",CallUi.BODY,true,CallUi.INK).apply { tag="home_setup_next_label";setPadding(0,CallUi.dp(a,2),0,0) })
        val inner=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="home_setup_body";visibility=if(open) View.VISIBLE else View.GONE }
        if(next.optional) inner.addView(CallUi.caption(a,"선택 단계예요. 하지 않아도 이음은 그대로 돌아가요.").apply { tag="home_setup_optional";setPadding(0,CallUi.dp(a,6),0,0) })
        // The reason right above the button, in reading size and ink (an amber-bold hint when the overlay list came back unchanged).
        inner.addView(CallUi.text(a,next.reason,CallUi.BODY,next.strong,CallUi.INK).apply { tag="home_setup_detail";setPadding(0,CallUi.dp(a,8),0,0) })
        inner.addView(CallUi.bigButton(a,"",next.button,next.action).apply { tag="home_setup_next";if(next.busy) CallUi.busy(this,"확인하는 중이에요") })
        if(next.optional) inner.addView(CallUi.linkButton(a,"나중에 할게요") { SetupSkip.skip(a,next.key);load() }.apply { tag="home_setup_later";minHeight=CallUi.dp(a,56);minimumHeight=minHeight },
            LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        // What waits after it, in grey: the required first, then the optional ones marked so.
        left.drop(1).forEach { st -> inner.addView(CallUi.text(a,st.label+(if(st.optional) " (선택)" else ""),CallUi.BODY,color=CallUi.SUBTLE).apply { setPadding(0,CallUi.dp(a,10),0,0) }) }
        c.addView(inner)
        body.addView(c)
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

        // v3.1 (DESIGN_V4 2.5-2): what needs a look comes as one line each right under the numbers, so the first check row stays
        // on the first screen: recordings that do not come (r12-1), a new version (r12-2), the voice model coming, then first setup
        // and 처음 정리 folded to one line (a tap opens them).
        s.gap?.let { g ->
            body.addView(bannerRow(g,"home_gap",CallUi.AMBER) { a.settings.show(StatusLights.GROUP_CONNECT,"status_import");a.switchTo(Screen.SETTINGS) })
        }
        updateBanner(s)
        modelBanner(s)
        setupCard(s)

        // 처음 정리: what is left before the calls since 10/1 become the context of the next ones. While it rests (battery under
        // half and not charging, or no result for six hours) the card turns amber and says so first, then what is left (#20).
        // v3.1: folded to one line ("지난 통화 정리 중, 녹음 59개 남음 ›"); a tap shows the rest.
        s.firstPass?.let { f ->
            val resting=f.lowBattery || f.stalled
            val c=CallUi.card(a,rail=if(resting) CallUi.AMBER_RAIL else CallUi.ACCENT).apply { tag="home_first_pass" }
            val inner=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="home_first_pass_body";visibility=if(firstPassOpen) View.VISIBLE else View.GONE }
            c.addView(foldRow(firstPassWords(f),"home_first_pass_banner",firstPassOpen) { firstPassOpen=!firstPassOpen;render(s) })
            if(resting) {
                inner.addView(CallUi.text(a,"잠시 쉬고 있어요",CallUi.HEADING,true).apply { tag="home_first_pass_rest" })
                inner.addView(CallUi.text(a,if(f.lowBattery) "충전기에 꽂으면 이어서 정리해요." else "결과가 오면 이어서 정리해요.",CallUi.BODY,true)
                    .apply { tag="home_first_pass_rest_why" })
                inner.addView(CallUi.text(a,"10월 1일부터 밀린 통화를 정리하는 중이에요. 오늘 통화는 지금처럼 바로 정리돼요.",CallUi.BODY,color=CallUi.SUBTLE))
                if(f.toTranscribe>0) {
                    inner.addView(CallUi.divider(a))
                    val items=mutableListOf(Triple("${f.toTranscribe}","남은 녹음",CallUi.INK));val units=mutableListOf("개")
                    f.audioMinutesLeft?.takeIf { it>0 }?.let { m -> val (n,u)=roughLength(m);items+=Triple(n,"남은 녹음 시간 (약)",CallUi.INK);units+=u }
                    inner.addView(CallUi.stats(a,items,units).apply { tag="home_first_pass_left" })
                }
                val rest=buildList {
                    if(f.waiting>0) add("요약을 기다리는 기록 ${f.waiting}건")
                    if(f.unrecorded>0) add("녹음 없는 통화 ${f.unrecorded}건 (통화기록 기준)")
                }
                if(rest.isNotEmpty() && f.toTranscribe==0) inner.addView(CallUi.divider(a))
                rest.forEach { inner.addView(CallUi.text(a,it,CallUi.BODY,true).apply { setPadding(0,CallUi.dp(a,8),0,0) }) }
                if(f.lowBattery) inner.addView(CallUi.caption(a,"배터리가 절반 넘게 차도 이어서 해요.").apply { setPadding(0,CallUi.dp(a,8),0,0) })
            } else {
                inner.addView(CallUi.text(a,"10월 1일부터의 통화와 문자를 시간 순서대로 정리하고 있어요. 오늘 통화는 지금처럼 바로 정리돼요. 충전해 두면 더 빨리 끝나요.",CallUi.BODY))
                val lines=buildList {
                    if(f.toTranscribe>0) add("글자로 바꿀 녹음 ${f.toTranscribe}개"+(f.hoursLeft?.let { h -> ", 약 ${if(h<1) "1시간 안" else "${Math.round(h)}시간"}" } ?: ""))
                    if(f.waiting>0) add("요약을 기다리는 기록 ${f.waiting}건")
                    if(f.toTranscribe==0 && f.waiting==0) add("지난 문자와 메일을 읽고 있어요")
                    if(f.unrecorded>0) add("녹음 없는 통화 ${f.unrecorded}건 (통화기록 기준)")
                }
                lines.forEach { inner.addView(CallUi.text(a,it,CallUi.BODY,true)) }
                if(f.toTranscribe>0) inner.addView(CallUi.caption(a,"지난 녹음은 충전 중이거나 배터리가 절반 넘게 남았을 때 바꿔요."))
            }
            if(!f.mailConnected) inner.addView(CallUi.caption(a,"메일을 연결하면 메일도 함께 정리해요. 설정의 메일에서 연결할 수 있어요.").apply { tag="home_first_pass_mail" })
            c.addView(inner)
            body.addView(c)
        }

        // The to-dos to look at first right under the numbers (no scrolling needed): asked for today and asking for a check,
        // at most five, each with its own [다 됐어요]; the rest of the open ones are one tap away (design G3, review #17).
        val first=s.tasks.take(FIRST_TASKS);val more=left-first.size
        body.addView(CallUi.section(a,"먼저 볼 체크할 일",if(more>0) "외 ${more}개 보기 ›" else null) { a.switchTo(Screen.TASKS) }.apply { tag="home_tasks_head" })
        if(first.isEmpty()) body.addView(CallUi.card(a).apply { tag="home_tasks"
            addView(CallUi.text(a,if(left>0) "오늘 요청된 체크할 일이 없어요" else "체크할 일이 없어요",CallUi.BODY,color=CallUi.SUBTLE)) })
        // The same row as the check tab (G3): only an item from a call opens that call, the rest show no press (r8-5).
        else body.addView(CallUi.list(a,first.map { item ->
            CheckRows.row(a,item,status=if(item.status=="needs_confirmation") "" else item.status,onDone={ row -> TaskActions.complete(a,item,row) { load() } },
                personId=item.recordId?.let(s.people::get)).apply { tag="home_task_${item.id}" }
        },inset=16).apply { tag="home_tasks" })

        // Today's call memos: who, when, one sentence. Tap a memo for the whole call.
        body.addView(CallUi.section(a,"오늘 통화 메모",if(s.calls>0) "전체 보기 ›" else null) { a.switchTo(Screen.CALLS) })
        // No record yet while today has calls (just made, being turned into text): say they are being sorted out (review #18).
        if(s.memos.isEmpty()) body.addView(CallUi.card(a).apply { tag="home_memos"
            addView(CallUi.text(a,if(s.calls>0) sortingWords(s.calls) else "오늘은 아직 정리된 통화가 없어요",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="home_memos_none" }) })
        else body.addView(CallUi.list(a,s.memos.map { c ->
            CallUi.memoRow(a,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallsScreen.stateWords(c),c.summary==null) {
                a.openCall(c.recordId,c.dayMs)
            }.apply { tag="home_memo_${c.recordId}" }.also { PersonLinks.onWho(a,it,s.people[c.recordId],c.linkedPerson) }
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
        startTicker(s)
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

/** v3.1 (DESIGN_V4 2.5-1): optional first-setup steps put off with [나중에 할게요] (they stay in settings), and whether the
 * overlay settings were opened once from the step (coming back without the switch on, the step says where to find it). */
internal object SetupSkip {
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    fun skip(c:android.content.Context,key:String)=prefs(c).edit().putBoolean("setup_later_$key",true).apply()
    fun skipped(c:android.content.Context,key:String)=prefs(c).getBoolean("setup_later_$key",false)
    fun markOverlayAsked(c:android.content.Context)=prefs(c).edit().putBoolean("setup_overlay_asked",true).apply()
    fun overlayAsked(c:android.content.Context)=prefs(c).getBoolean("setup_overlay_asked",false)
}

/** Text and call-log access. A sideloaded app on recent Android can get these refused as "제한된 설정" without a dialog:
 * after one ask, the screens point to App info, where the restriction is lifted and the permission is allowed. */
/** v3.0 (r11-1): the call card's two questions, shared by the setup steps and the settings group. */
internal object CallCardAsk {
    private const val ROLE_ASKED="asked_call_redirection_role"
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    fun overlayAllowed(c:android.content.Context)=runCatching { android.provider.Settings.canDrawOverlays(c) }.getOrDefault(false)
    /** "다른 앱 위에 표시" for this app, else the list of all apps. */
    fun openOverlaySettings(a:android.app.Activity) {
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:${a.packageName}"))) }
            .onFailure { runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }
    }
    fun roleAvailable(c:android.content.Context):Boolean {
        if(android.os.Build.VERSION.SDK_INT<29) return false
        val rm=c.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_REDIRECTION) }.getOrDefault(false)
    }
    fun roleHeld(c:android.content.Context):Boolean {
        if(android.os.Build.VERSION.SDK_INT<29 || !roleAvailable(c)) return false
        val rm=c.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_REDIRECTION) }.getOrDefault(false)
    }
    /** The optional step is done: the role is held, was asked for once (a "no" is an answer), or this phone has no such role. */
    fun roleDone(c:android.content.Context)=!roleAvailable(c) || roleHeld(c) || prefs(c).getBoolean(ROLE_ASKED,false)
    fun askRole(a:android.app.Activity,launcher:androidx.activity.result.ActivityResultLauncher<Intent>) {
        prefs(a).edit().putBoolean(ROLE_ASKED,true).apply()
        if(android.os.Build.VERSION.SDK_INT<29) return
        val rm=a.getSystemService(android.app.role.RoleManager::class.java) ?: return
        runCatching { launcher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_REDIRECTION)) }
    }
}

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
