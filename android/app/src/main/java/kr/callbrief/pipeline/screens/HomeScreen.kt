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

/** App home, a summary (v3.2, DESIGN_V4 2-1), content first: "오늘 통화 이야기", the to-dos to look
 * at first, today's call memos, then today's numbers, open to-dos per company and this week's calls per day. First setup,
 * progress, updates and missing recordings are in settings; one line at the very top says when there is something to do there. The bottom bar leads to every other screen. */
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
                                 val modelBytes:Long=-1,
                                 /** v3.2 (DESIGN_V4 2-1): "오늘 통화 이야기" at the top of home; null: no call summarized today yet. */
                                 val story:TodayStory.Shown?=null,
                                 /** v3.5 r15-0 (user 11/6: numbers by recording): today's business calls with their summary; [calls] is
                                  * today's recordings (call records and recordings still waiting for their text or failed). */
                                 val summarized:Int=0,
                                 /** v4.0 r20-1: the call card is a required setup step on this phone ([CallCardAsk.cardRequired]). */
                                 val cardRequired:Boolean=false)
    /** [audioMinutesLeft]: how long the recordings still to transcribe are, together. [lowBattery]: not charging and under half
     * the battery, so old recordings wait ([FirstPass.powerOk]). [stalled]: nothing came back for [FirstPass.STALL_MS], so no
     * new batch goes up (design C7). Either one shows the "잠시 쉬고 있어요" line (#20). */
    internal data class FirstPassView(val toTranscribe:Int,val hoursLeft:Double?,val waiting:Int,val unrecorded:Int,
                                      val audioMinutesLeft:Long?=null,val lowBattery:Boolean=false,val stalled:Boolean=false,
                                      /** v3.1 r12-6: recordings of 처음 정리 in all (done and left), for "녹음 n개 중 m개 남음"; 0 unknown. */
                                      val recordings:Int=0)
    /** Where home's one line opens settings (r12-6): the group, and the row inside it. */
    internal data class Attention(val group:String,val tag:String)
    internal companion object {
        /** Minutes of audio as one big number and its unit: under an hour in minutes, then whole hours ("약" is in the label). */
        fun roughLength(minutes:Long):Pair<String,String> = if(minutes<60) "${minutes.coerceAtLeast(1)}" to "분" else "${(minutes+30)/60}" to "시간"
        /** How many to-dos "먼저 볼 체크할 일" shows; the rest are one tap away ("외 n개 보기 ›"). */
        const val FIRST_TASKS=5
        /** v3.5 r15-0: the story's line guard (its words are already whole sentences within [TodayStory.MAX_CHARS]). */
        const val STORY_LINES=7
        /** "먼저 볼 체크할 일" (design G3, review #17): the open to-dos asked for today and the ones asking for a check, those first. */
        internal fun firstLook(open:List<OpenItem>,today:java.time.LocalDate)=
            TaskActions.ordered(open).filter { it.status=="needs_confirmation" || (it.createdMs>0 && CallRecordStore.kstDay(it.createdMs)==today) }
        /** The memo list's words when today has calls but none is sorted out yet. */
        /** v4.0 r20-1: no number here (the story's heading above says "녹음 n개 중 정리 m개" once). */
        fun sortingWords(@Suppress("UNUSED_PARAMETER") calls:Int)="오늘 통화를 정리하고 있어요. 끝나면 여기에 메모가 보여요"

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

        // ---- v3.1 r12-6: home is a summary; what needs the person waits in settings, home says so in one line ----
        /** The one line on home (user 11/5 22:19). */
        const val ATTENTION_WORDS="설정에서 확인할 일이 있어요"
        /** The required first-setup steps not done yet (folder, consent, call log, call state, notifications, battery). */
        internal fun requiredLeft(s:Snapshot)=(listOf(s.folder,s.consent,s.callLog,s.phoneState,s.notify,s.battery)+listOfNotNull(s.overlay.takeIf { s.cardRequired })).count { !it }
        /** An update waits for the person: it can be installed, the install waits for a tap, or it failed. Only being checked,
         * being downloaded or being installed is not (nothing to do). */
        internal fun updateNeedsPerson(u:CallUpdate.UpdateState)=when(u.phase) {
            CallUpdate.P_READY,CallUpdate.P_WAITING_CONFIRM -> u.canInstall
            CallUpdate.P_FAILED -> true
            else -> false
        }
        /** What home's line opens, the topmost one in settings first: required setup steps, the voice model that failed (both in
         * the first group), recordings that do not come, then an update waiting for the person (both in 상태와 도움). Null when
         * there is nothing to do, also while something only moves along (downloading, sorting out). */
        internal fun attention(s:Snapshot):Attention? = when {
            requiredLeft(s)>0 -> Attention(SettingsScreen.GROUP_NOW,SettingsScreen.SETUP_TAG)
            s.model==PipelineModelState.FAILED -> Attention(SettingsScreen.GROUP_NOW,"model_state")
            !s.gap.isNullOrBlank() -> Attention(StatusLights.GROUP_STATUS,"status_import")
            updateNeedsPerson(s.update) -> Attention(StatusLights.GROUP_STATUS,"app_version")
            else -> null
        }
        /** "지난 통화 정리 중, 녹음 59개 남음" (or what it waits for); counted in recordings, never in call-log lines. */
        internal fun firstPassWords(f:FirstPassView)=when {
            f.lowBattery -> "지난 통화 정리, 충전을 기다리는 중"
            f.stalled -> "지난 통화 정리, 결과를 기다리는 중"
            f.toTranscribe>0 && f.recordings>=f.toTranscribe -> "지난 통화 정리 중, 녹음 ${f.recordings}개 중 ${f.toTranscribe}개 남음"
            f.toTranscribe>0 -> "지난 통화 정리 중, 녹음 ${f.toTranscribe}개 남음"
            f.waiting>0 -> "지난 통화 정리 중, 요약 ${f.waiting}건 남음"
            else -> "지난 통화 정리 중"
        }
        /** 처음 정리 while it runs (consent given, not done), else null; read for settings' first group. */
        internal fun readFirstPass(storage:android.content.Context,db:CallRecordStore,s:org.json.JSONObject):FirstPassView? {
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
            return FirstPassView(p.toTranscribe,hours,p.waiting,p.unrecorded,
                perMinute?.let { (p.bytesLeft+it/2)/it },p.toTranscribe>0 && !FirstPass.powerOk(storage),stalled,p.recordings)
        }
    }
    private val body:LinearLayout get()=a.column
    private fun granted(p:String)=a.checkSelfPermission(p)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun notifyAllowed()=NotifyAsk.allowed(a)
    private fun batteryFree()=a.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(a.packageName)==true
    /** [지금 업데이트] is checking the file and copying it into the install session. */
    private var updating=false
    private val ticker=android.os.Handler(android.os.Looper.getMainLooper())
    private var tick:Runnable?=null

    override fun enter() { load() }
    override fun leave() { stopTicker() }
    /** [지금 업데이트] (the "new version" notification): installs the version that is here; otherwise starts getting it and shows
     * its bar in settings. The SHA-256 check and the copy into the install session run off the main thread (review #21). */
    internal fun updateNow() {
        if(updating) return;updating=true
        val m=metered()
        a.lifecycleScope.launch {
            val r=withContext(Dispatchers.IO) { runCatching { CallUpdate.install(a,m) } }
            updating=false
            if(a.isFinishing || a.isDestroyed) return@launch
            r.onFailure { android.widget.Toast.makeText(a,"업데이트를 시작하지 못했어요. 잠시 후 다시 눌러 주세요.",android.widget.Toast.LENGTH_LONG).show() }
            if(r.getOrNull()==false) { a.settings.show(StatusLights.GROUP_STATUS,"app_version");a.switchTo(Screen.SETTINGS) }
        }
    }
    private fun metered()=runCatching { a.getSystemService(android.net.ConnectivityManager::class.java)?.let { it.activeNetwork!=null && it.isActiveNetworkMetered } }.getOrNull()==true

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
        // v3.5 r15-0 (user 11/6): home counts recordings, the call records and the recordings still without one; the call log's
        // own count stays in the heartbeat only.
        val waitingText=CallTranscriptionStore.withoutRecord(today) { sql,args -> db.cardRows(sql,*args) }
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
        val story=runCatching { TodayStory.shown(s.optString(PipelineSettingsKeys.STORY),today,calls) }.getOrNull()
        // v3.1 r12-6: 처음 정리 is shown in settings only, so home does not read it (firstPass stays null).
        return Snapshot(folder,consent,
            model,calls.count { it.kind=="통화" }+waitingText,calls.count{it.state!=CallRecordStore.ANALYZED},newTasks,
            due.size,due.count{CallUi.overdue(it.due)},s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),batteryFree(),percent,next,companies,
            week,byCompany,memos,
            granted(android.Manifest.permission.READ_SMS),granted(android.Manifest.permission.READ_CALL_LOG),granted(android.Manifest.permission.READ_PHONE_STATE),
            null,first,NotifyAsk.blocked(a),mm.partFile.path,people,
            CallCardAsk.overlayAllowed(a),true /* v3.4 (4.1-1): no role step */,installStep(a),gapBanner(a)?.text?.takeIf { it.isNotBlank() },updateState(a),partBytes,story,TodayStory.summarized(calls),
            cardRequired=CallCardAsk.cardRequired(a,s))
    }

    /** While a new version or the voice model is coming, home looks every second whether it now needs the person (the update
     * changed phase, the model's part file went away) and reads again; nothing on home moves meanwhile. */
    private fun startTicker(s:Snapshot) {
        stopTicker()
        val part=java.io.File(s.modelPart)
        val followModel=s.model==PipelineModelState.DOWNLOADING && s.modelPart.isNotEmpty() && part.isFile
        val followUpdate=s.update.phase in MOVING
        if(!followModel && !followUpdate) return
        val r=object:Runnable { override fun run() {
            if(!live() || tick!==this) return
            if(followModel && !part.isFile) { tick=null;load();return }
            if(followUpdate && updateState(a).phase!=s.update.phase) { tick=null;load();return }
            ticker.postDelayed(this,1000)
        } }
        tick=r;ticker.postDelayed(r,1000)
    }
    private fun stopTicker() { tick?.let(ticker::removeCallbacks);tick=null }

    /** The one line above the numbers when the person has something to do in settings (r12-6): an amber sheet, reading size,
     * 56dp tall; a tap opens settings at the first such row. */
    private fun attentionRow(target:Attention)=CallUi.card(a,rail=CallUi.AMBER_RAIL).apply {
        tag="home_attention";isClickable=true;isFocusable=true;foreground=CallUi.rowRipple();minimumHeight=CallUi.dp(a,56)
        contentDescription="$ATTENTION_WORDS. 눌러서 설정 열기"
        addView(CallUi.text(a,"$ATTENTION_WORDS ›",CallUi.BODY,true,CallUi.AMBER).apply { tag="home_attention_text" })
        CallUi.onTap(this) { a.settings.show(target.group,target.tag);a.switchTo(Screen.SETTINGS) }
    }

    internal fun render(s:Snapshot?) {
        val keep=CallUi.scrollY(body)
        stopTicker()
        body.removeAllViews()
        CallUi.band(body,CallUi.greeting(),eyebrow=CallUi.date(CallUi.today()))
        if(s==null) { CallUi.retryCard(body,"정보를 읽지 못했어요. 잠시 후 다시 불러와 주세요.") { body.removeAllViews();load() };return }

        // v3.1 r12-6 (user 11/5 22:19): home is a summary. Setup, progress, updates and missing recordings live in settings; only
        // when the person has to do something there, one line at the very top says so.
        attention(s)?.let { body.addView(attentionRow(it)) }

        // v3.2 (DESIGN_V4 2-1, the wife 11/5 22:56 and the user 22:57): content first. "오늘 통화 이야기", today's calls told in two
        // or three sentences (the hourly task's story, or the phone's own from today's summaries); a tap opens today's calls.
        val left=s.byCompany.sumOf { it.second }
        val story=s.story
        // v4.0 r20-1: "녹음 n개 중 정리 m개" is said here only (it was also a number and a pill in 오늘 한눈에), so with today's
        // recordings and no story yet the heading stands above the none line too.
        if(story==null) body.addView(CallUi.card(a).apply { tag="home_story"
            if(s.calls>0) addView(CallUi.text(a,TodayStory.countLine(s.calls,s.summarized),CallUi.HEADING,true).apply { tag="home_story_title";fontFeatureSettings="tnum" })
            addView(CallUi.text(a,"오늘은 아직 정리된 통화가 없어요",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="home_story_none";if(s.calls>0) setPadding(0,CallUi.dp(a,6),0,0) }) })
        else body.addView(CallUi.card(a,rail=CallUi.ACCENT).apply {
            tag="home_story";isClickable=true;isFocusable=true;foreground=CallUi.rowRipple();minimumHeight=CallUi.dp(a,56)
            val heading=TodayStory.countLine(s.calls,story.calls)
            contentDescription="$heading. ${story.text} 눌러서 오늘 통화 보기"
            val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL }
            head.addView(CallUi.text(a,heading,CallUi.HEADING,true).apply { tag="home_story_title";fontFeatureSettings="tnum" },LinearLayout.LayoutParams(0,-2,1f))
            head.addView(CallUi.caption(a,"전체 보기 ›",CallUi.SUBTLE,true))
            addView(head)
            // v3.5 r15-0: never cut in the middle of a sentence. The words are whole sentences within [TodayStory.MAX_CHARS];
            // the line limit is only a guard against a very narrow screen.
            addView(CallUi.text(a,story.text,CallUi.BODY).apply { tag="home_story_text";maxLines=STORY_LINES;setPadding(0,CallUi.dp(a,6),0,0) })
            CallUi.onTap(this) { a.calls.show(CallUi.today(),null);a.switchTo(Screen.CALLS) }
        })

        // The to-dos to look at first (no scrolling needed): asked for today and asking for a check, at most five, each with its
        // own [다 됐어요]; the rest of the open ones are one tap away (design G3, review #17).
        val first=s.tasks.take(FIRST_TASKS);val more=left-first.size
        body.addView(CallUi.section(a,"먼저 볼 체크할 일",if(more>0) "외 ${more}개 보기 ›" else null) { a.switchTo(Screen.TASKS) }.apply { tag="home_tasks_head" })
        if(first.isEmpty()) body.addView(CallUi.card(a).apply { tag="home_tasks"
            addView(CallUi.text(a,if(left>0) "오늘 요청된 체크할 일이 없어요" else "체크할 일이 없어요",CallUi.BODY,color=CallUi.SUBTLE)) })
        // The same row as the check tab (G3): only an item from a call opens that call, the rest show no press (r8-5).
        else body.addView(CallUi.list(a,first.map { item ->
            CheckRows.row(a,item,status=if(item.status=="needs_confirmation") "" else item.status,onDone={ row -> TaskActions.complete(a,item,row) { load() } },
                onWrong={ row -> TaskActions.wrong(a,item,row) { load() } },
                personId=item.recordId?.let(s.people::get)).apply { tag="home_task_${item.id}" }
        },inset=16).apply { tag="home_tasks" })

        // Today's call memos: who, when, one sentence. Tap a memo for the whole call. With no call today the story line above
        // already says so; while today's calls are being turned into text the memo card says that (review #18).
        if(s.memos.isNotEmpty() || s.calls>0) {
            body.addView(CallUi.section(a,"오늘 통화 메모",if(s.calls>0) "전체 보기 ›" else null) { a.switchTo(Screen.CALLS) })
            if(s.memos.isEmpty()) body.addView(CallUi.card(a).apply { tag="home_memos"
                addView(CallUi.text(a,sortingWords(s.calls),CallUi.BODY,color=CallUi.SUBTLE).apply { tag="home_memos_none" }) })
            else body.addView(CallUi.list(a,s.memos.map { c ->
                CallUi.memoRow(a,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallsScreen.stateWords(c),c.summary==null) {
                    a.openCall(c.recordId,c.dayMs)
                }.apply { tag="home_memo_${c.recordId}" }.also { PersonLinks.onWho(a,it,s.people[c.recordId],c.linkedPerson) }
            }).apply { tag="home_memos" })
        }

        // Today at a glance: two big numbers (only the open to-dos in the accent), then when the next report comes. v4.0 r20-1:
        // today's recordings are counted once, in the story's heading (no "오늘 녹음" number, no count pill here).
        body.addView(CallUi.section(a,"오늘 한눈에"))
        val today=CallUi.card(a).apply { tag="home_today" }
        today.addView(CallUi.stats(a,listOf(Triple("${s.newTasks}","오늘 생긴 체크",CallUi.INK),
            Triple("$left","남은 체크할 일",CallUi.ACCENT)),listOf("개","개")).apply { tag="home_calls" })
        today.addView(CallUi.divider(a))
        today.addView(CallUi.caption(a,s.nextReport.takeIf{it.isNotBlank()}?.let{"다음 알림 $it"} ?: "마지막 정리 "+CallUi.dateTime(s.lastAnalyzedMs)).apply { tag="home_next_report";setPadding(0,0,0,0) })
        body.addView(today)

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
/** v3.0 (r11-1): the call card's two questions, shared by the setup steps and the settings group. v3.4 (DESIGN_V4 4.1-1): the
 * role is no longer a setup step (calls going out show the card without it); settings offer it as an option only. */
internal object CallCardAsk {
    private const val ROLE_ASKED="asked_call_redirection_role"
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    fun overlayAllowed(c:android.content.Context)=runCatching { android.provider.Settings.canDrawOverlays(c) }.getOrDefault(false)
    /** v4.0 r20-1 (DESIGN_V7 6): whether the call card is a required first-setup step on this phone. Decided once and kept
     * ([CARD_REQUIRED]): required on a new install (no recording folder and no consent yet when v4.0 first asks), optional as
     * before on a phone already set up, so an update changes nothing there. */
    const val CARD_REQUIRED="setup_card_required"
    fun cardRequired(c:android.content.Context,s:org.json.JSONObject):Boolean {
        val p=prefs(c)
        if(p.contains(CARD_REQUIRED)) return p.getBoolean(CARD_REQUIRED,false)
        val fresh=s.optString("treeUri").isBlank() && !s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        p.edit().putBoolean(CARD_REQUIRED,fresh).apply()
        return fresh
    }
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
