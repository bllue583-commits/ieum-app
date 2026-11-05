package kr.callbrief.pipeline

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import org.json.JSONObject

/** The download bar shared by settings and the home card: a thick horizontal bar in the accent over its soft tint. */
internal object UpdateViews {
    fun bar(a:android.app.Activity,percent:Int)=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate=false;max=100;progress=percent.coerceIn(0,100);tag="update_progress"
        // Rounded track and fill of our own: the theme's bar tints its track nearly invisible on the white sheet.
        progressDrawable=android.graphics.drawable.LayerDrawable(arrayOf(CallUi.sheet(a,CallUi.ACCENT_SOFT,radius=6),
            android.graphics.drawable.ClipDrawable(CallUi.sheet(a,CallUi.ACCENT,radius=6),Gravity.START,android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply {
            setId(0,android.R.id.background);setId(1,android.R.id.progress) }
        contentDescription="새 버전 받기 ${progress}%"
        layoutParams=LinearLayout.LayoutParams(-1,CallUi.dp(a,12)).apply { topMargin=CallUi.dp(a,10);bottomMargin=CallUi.dp(a,6) }
    }
}

/** "설정": Claude key, consent, voice-model download, notification hours and a plain status. The key is never shown again.
 * r8-5: drawing again keeps the scroll place and what is typed in the mail fields; a switch or a slider shows its own state, so
 * changing one saves without drawing the page again (only its words and the status lights change); every action button goes
 * busy on its own ("연결하는 중이에요") and leaves the others alone. */
internal class SettingsScreen(a:MainActivity):AppScreen(a) {
    companion object { const val TICK_MS=1000L
        /** [지금 확인] says "확인하는 중이에요" at most this long. */
        const val CHECK_WAIT_MS=30_000L
        /** A slider is saved this long after it last moved (also when moved with TalkBack, review #31). */
        const val HOUR_SAVE_MS=700L }
    private val page:LinearLayout get()=a.column
    /** The sheet the current settings group is written into. */
    private lateinit var body:LinearLayout
    /** The page was drawn at least once since it came to the front (a redraw keeps its scroll place). */
    private var drawn=false
    /** When the oldest call still waiting for its summary was stored (for the status lights). */
    @Volatile private var oldestWaiting:Long?=null
    /** What the status lights read from the store (queue by entry time, late live calls, failures) and the share of the
     * protected store in use (sealed file and rows, design 3.6), for the status lights and the storage line. */
    @Volatile private var watch:StatusLights.Store?=null
    @Volatile private var folderReadable=false
    @Volatile private var storagePercent:Int?=null
    /** Numbers found personal (design E9), the latest first: their records stay on the phone. */
    @Volatile private var personal:List<Pair<String,Long>> =emptyList()
    // The folder and permission results come back to the Activity, which calls [enter] (a fresh read).
    private val pickFolder get()=a.pickFolder
    private val askSms get()=a.askSms
    private val askCallLog get()=a.askCallLog
    /** v2.9 r10-1: the call state question from the 연결 group. */
    private val askPhoneState=a.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        ReadingPermission.noteResult(a,android.Manifest.permission.READ_PHONE_STATE,ok);if(live()) enter()
    }
    /** v3.0 r11-1: the call redirection role ("전화 걸 때"), asked from the 통화 카드 group. */
    private val askCallRole=a.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if(live()) enter()
    }
    private fun settings()=SecureSettings(CallPipelineStartup.storage(a))
    private fun store()=kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a))

    /** A row to scroll to once the page is drawn (a home banner opens settings at that row, DESIGN_V4 2.5-2). */
    private var focusTag:String?=null
    /** The next drawing scrolls to the view tagged [tag] (or to the group [group] when that row is not there). */
    internal fun show(group:String,tag:String?=null) { focusTag=tag ?: group;focusGroup=group }
    private var focusGroup:String?=null
    /** Settings comes to the front (or the app comes back): read, draw, and follow a download every second. */
    override fun enter() {
        body=page
        if(page.childCount==0) { drawn=false;CallUi.band(page,"설정");CallUi.loading(page) }
        refresh();ticker.removeCallbacks(tick);ticker.postDelayed(tick,TICK_MS)
    }
    /** Leaving: the update follow stops, and a slider moved less than [HOUR_SAVE_MS] ago is saved now. */
    override fun leave() { ticker.removeCallbacks(tick);flushHours() }
    /** A tap on the settings tab while it is in front: the top of the page. */
    override fun reselect() { refresh();CallUi.scrollTo(page,0) }
    override fun save(out:Bundle) { out.putString("settings_mail_service",mailService);out.putString("settings_mail_user",mailUser);out.putString("settings_mail_host",mailHost) }
    override fun restore(saved:Bundle) {
        saved.getString("settings_mail_service")?.let { mailService=it };mailUser=saved.getString("settings_mail_user") ?: "";mailHost=saved.getString("settings_mail_host") ?: "" }

    // ---- 앱 버전: getting the next version, with a bar while it comes (round 6 v2.5) ----
    private val ticker=android.os.Handler(android.os.Looper.getMainLooper())
    /** While the screen is open, the update part follows the download every second. */
    private val tick=object:Runnable { override fun run() { if(!live()) return;fillUpdate();fillModel();ticker.postDelayed(this,TICK_MS) } }
    /** The model's part file and the state drawn, so the line follows the download every second without a read. */
    private var modelPart:java.io.File?=null
    private var modelShown=""
    /** The part file was there at the last tick (its going away means the download ended). */
    private var modelSeen=false
    private fun modelWords(state:String,part:java.io.File?)=HomeScreen.modelLine(state,part?.takeIf { it.isFile }?.length() ?: -1L,
        runCatching { a.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork==null }.getOrDefault(false))
    private fun fillModel() {
        if(modelShown!="DOWNLOADING") return
        // The part file gone: the download ended (ready or failed); the page reads again.
        val here=modelPart?.isFile==true
        if(!here && modelSeen) { modelShown="";modelSeen=false;refresh();return }
        modelSeen=here
        page.findViewWithTag<TextView>("model_state")?.text=modelWords(modelShown,modelPart)
    }
    private var updateBlock:LinearLayout?=null
    private var updateKey:String?=null
    private fun metered()=runCatching { a.getSystemService(android.net.ConnectivityManager::class.java)?.let { it.activeNetwork!=null && it.isActiveNetworkMetered } }.getOrNull()==true
    private fun startFetch() {
        val m=metered()
        CallUpdate.fetchNow(a,installAfter=false,metered=m)
        if(m) Toast.makeText(a,"모바일 데이터로 ${CallUpdate.sizeWords(CallUpdate.available(a)?.size ?: 0)} 받아요.",Toast.LENGTH_LONG).show()
        fillUpdate()
    }
    /** The part under the version line: a bar and "받는 중 42%" while it comes, [지금 설치] when it is ready, [지금 받기] when a newer
     * version is known and not yet here. Rebuilt only when what it shows changes. */
    internal fun fillUpdate() {
        val block=updateBlock?.takeIf { live() } ?: return
        val u=HomeScreen.updateState(a)
        if(u.phase!="none" && u.words.isNotBlank()) { fillState(block,u);return }
        val ready=CallUpdate.ready(a)
        if(ready!=null && CallUpdate.takeInstallWish(a)) { CallUpdate.install(a,metered()) } // asked for from [지금 업데이트]: on to the install
        val f=CallUpdate.fetchState(a);val avail=CallUpdate.available(a)
        val key="$f|${avail?.versionCode}|${ready?.versionCode}"
        if(key==updateKey) return
        updateKey=key;block.removeAllViews()
        val name=(ready ?: avail)?.let(CallUpdate::shownName)
        fun headline(t:String,color:Int=CallUi.INK)=CallUi.text(a,t,CallUi.HEADING,true,color).apply { tag="update_state";fontFeatureSettings="tnum";setPadding(0,CallUi.dp(a,12),0,0) }
        fun note(t:String)=CallUi.text(a,t,CallUi.CAPTION,color=CallUi.SUBTLE).apply { tag="update_note" }
        when {
            ready!=null -> {
                block.addView(headline("새 버전이 준비됐어요",CallUi.ACCENT))
                if(ready.notes.isNotBlank()) block.addView(CallUi.text(a,ready.notes,CallUi.BODY))
                block.addView(CallUi.bigButton(a,"","지금 설치") { CallUpdate.install(a,metered());fillUpdate() }.apply { tag="update_install" })
            }
            f.stage==CallUpdate.Stage.DOWNLOADING -> {
                block.addView(headline(listOfNotNull("새 버전",name,"받는 중 ${f.percent}%").joinToString(" ")))
                block.addView(UpdateViews.bar(a,f.percent))
                block.addView(note(if(f.metered) "모바일 데이터로 ${CallUpdate.sizeWords(avail?.size ?: 0)} 받아요." else "다 받으면 바로 설치할 수 있어요."))
            }
            f.stage==CallUpdate.Stage.WAITING -> {
                block.addView(headline(if(name!=null) "새 버전 $name 받을 준비 중이에요" else "새 버전이 있는지 보고 있어요"))
                block.addView(UpdateViews.bar(a,0))
                block.addView(note("인터넷에 연결되면 바로 시작해요."))
            }
            f.stage==CallUpdate.Stage.FAILED -> {
                block.addView(headline(f.reason.ifBlank { CallUpdate.OTHER_WORDS },CallUi.AMBER))
                block.addView(CallUi.bigButton(a,"","다시 받기") { startFetch() }.apply { tag="update_fetch" })
            }
            avail!=null -> {
                block.addView(headline("새 버전 $name 받을 수 있어요"))
                if(avail.notes.isNotBlank()) block.addView(CallUi.text(a,avail.notes,CallUi.BODY))
                block.addView(CallUi.bigButton(a,"","지금 받기") { startFetch() }.apply { tag="update_fetch" })
                block.addView(note("받는 데 ${CallUpdate.sizeWords(avail.size)}가 들어요. 받은 뒤 설치를 한 번 눌러 주세요."))
            }
            else -> {
                block.addView(note("마지막 확인 "+CallUi.dateTime(CallUpdate.lastChecked(a))).apply { setPadding(0,CallUi.dp(a,6),0,0) })
                block.addView(checkLink())
            }
        }
    }
    /** v3.1 (DESIGN_V4 2.2-5 라): [지금 확인] — checks now and shows the answer in place ("확인하는 중이에요" meanwhile). */
    private var checking=false
    /** No answer this long after [지금 확인]: the link comes back (the check goes on in the background). */
    private val checkGiveUp=Runnable { if(checking) { checking=false;updateKey=null;if(live()) fillUpdate() } }
    private fun checkLink()=CallUi.linkButton(a,if(checking) "확인하는 중이에요" else "지금 확인 ›") {
        if(checking) return@linkButton
        checking=true;updateKey=null;fillUpdate()
        ticker.removeCallbacks(checkGiveUp);ticker.postDelayed(checkGiveUp,CHECK_WAIT_MS)
        HomeScreen.checkNow(a) { checking=false;ticker.removeCallbacks(checkGiveUp);updateKey=null;if(live()) fillUpdate() }
    }.apply { tag="update_check" }.also { it.layoutParams=LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) } }
    /** r12-2's state under the version line: the words as a heading, a bar while it comes, [지금 업데이트] when it can be installed,
     * and [지금 확인] while nothing is happening or after a failure. Rebuilt only when the phase changes; the words and the bar follow
     * every second. */
    private fun fillState(block:LinearLayout,u:CallUpdate.UpdateState) {
        val key="state|${u.phase}|${u.canInstall}|$checking"
        if(key==updateKey) {
            block.findViewWithTag<TextView>("update_state")?.text=u.words
            if(u.percent>=0) block.findViewWithTag<ProgressBar>("update_progress")?.progress=u.percent
            return
        }
        updateKey=key;block.removeAllViews()
        val failed=u.phase=="failed"
        block.addView(CallUi.text(a,u.words,CallUi.HEADING,true,if(failed) CallUi.AMBER else CallUi.INK).apply { tag="update_state";fontFeatureSettings="tnum";setPadding(0,CallUi.dp(a,12),0,0) })
        if(u.phase=="downloading" && u.percent>=0) block.addView(UpdateViews.bar(a,u.percent))
        if(u.canInstall) block.addView(CallUi.bigButton(a,"","지금 업데이트","확인하는 중이에요") {
            a.lifecycleScope.launch {
                val r=withContext(Dispatchers.IO) { runCatching { CallUpdate.install(a,metered()) } }
                (page.findViewWithTag<TextView>("update_install"))?.let(CallUi::idle)
                r.onFailure { Toast.makeText(a,"업데이트를 시작하지 못했어요. 잠시 후 다시 눌러 주세요.",Toast.LENGTH_LONG).show() }
                updateKey=null;if(live()) fillUpdate()
            } }.apply { tag="update_install" })
        if(!u.canInstall && u.phase !in HomeScreen.MOVING) block.addView(checkLink())
    }

    /** Starts a settings group: its name above, a white sheet below that the following views go into. */
    private fun group(name:String) {
        page.addView(CallUi.section(a,name))
        body=CallUi.card(a).apply { tag="group_$name" };page.addView(body)
    }
    /** A small heading inside a group's sheet; a hairline separates it from what is above (not before the first). */
    private fun sub(name:String,first:Boolean=false) {
        if(!first) body.addView(CallUi.divider(a))
        body.addView(CallUi.text(a,name,CallUi.HEADING,true).apply { setPadding(0,0,0,CallUi.dp(a,4)) })
    }
    private fun button(value:String,action:()->Unit)=CallUi.button(a,value,action)

    // ---- actions that take a while: each one busy on its own (review #11) ----
    /** Actions running now, by the tag of their button, with the words it shows meanwhile; a page drawn again meanwhile shows
     * the new button busy too, and the same action is never started twice. Other buttons stay usable. */
    private val running=HashMap<String,String>()
    internal fun isRunning(tag:String)=tag in running
    /** A button for an action that takes a while: pressed, it goes off with [busyWords] at once and [work] runs; [work] calls the
     * function it is given when it is done, and the button comes back. */
    private fun act(label:String,tag:String,busyWords:String,big:Boolean=false,work:(done:()->Unit)->Unit):Button {
        val b=if(big) CallUi.bigButton(a,"",label) {} else CallUi.button(a,label) {}
        b.tag=tag
        CallUi.onTap(b) {
            if(tag in running) return@onTap
            running[tag]=busyWords;CallUi.busy(b,busyWords)
            work {
                running.remove(tag);CallUi.idle(b)
                (page.findViewWithTag<View>(tag) as? TextView)?.takeIf { it!==b }?.let(CallUi::idle)
            }
        }
        running[tag]?.let { CallUi.busy(b,it) }
        return b
    }

    /** Reads everything and draws the page; with [full] false only the status lights are drawn again (after a switch or a slider,
     * whose own state is already on the screen). */
    private fun refresh(full:Boolean=true) {
        a.lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                val s=runCatching { settings().snapshot() }.getOrDefault(JSONObject())
                // One store for the whole refresh (r8-1): the process store, not closed here.
                val db=runCatching { store() }.getOrNull()
                val counts=runCatching { db!!.counts() }.getOrDefault(emptyMap())
                val memo=runCatching { db!!.siteMemo() }.getOrNull()
                oldestWaiting=runCatching { db!!.oldestWaitingMs() }.getOrNull()
                watch=StatusLights.read(CallPipelineStartup.storage(a),s,System.currentTimeMillis())
                folderReadable=StatusLights.folderReadable(a,s)
                storagePercent=watch?.dbPercent
                personal=runCatching { db!!.personalNumbers() }.getOrDefault(emptyList())
                Triple(s,counts,memo)
            } }
            if(!live())return@launch
            result.onSuccess { (s,c,m) ->
                val lights=lightsCard?.takeIf { !full && page.findViewWithTag<View>("settings_status")===it }
                if(lights!=null) fillLights(lights,s,c) else render(s,c,m)
            }.onFailure {
                if(!drawn) { page.removeAllViews();CallUi.band(page,"설정");CallUi.retryCard(page,"설정을 읽지 못했어요. 다시 불러와 주세요.") { page.removeAllViews();enter() } }
                else Toast.makeText(a,"설정을 새로 읽지 못했어요.",Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Runs a settings write off the main thread. [redraw]: the page is read and drawn again after it (its place kept); without,
     * only the status lights are. A failed write always draws the page again, so a switch shows what is really saved. */
    private fun save(hoursChanged:Boolean=false,redraw:Boolean=true,done:()->Unit={},block:(JSONObject)->Unit) {
        a.lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching { settings().update(block) }.isSuccess }
            if(!ok) Toast.makeText(a,"설정을 저장하지 못했어요.",Toast.LENGTH_LONG).show()
            else CallPipelineStartup.settingsChanged(a,hoursChanged)
            done()
            if(live()) refresh(full=redraw || !ok)
        }
    }

    private fun render(s:JSONObject,counts:Map<String,Int>,siteMemo:String?=null) {
        // Drawn again (a save, coming back from another app): the page stays where it was (review #7).
        val keep=if(drawn) CallUi.scrollY(page) else 0
        page.removeAllViews();body=page;drawn=true
        CallUi.band(page,"설정","처음 설정한 것도 여기서 언제든 바꿀 수 있어요")

        // ---- 연결: where the calls, texts and mail come from ----
        group("연결")
        sub("녹음 폴더",first=true)
        val folder=s.optString("treeUri").isNotBlank()
        body.addView(CallUi.text(a,if(folder) "연결됨 ✓  새 통화를 자동으로 가져와요." else "아직 연결하지 않았어요. 통화 녹음 폴더를 골라 주세요.",CallUi.BODY,true,if(folder) CallUi.OK else CallUi.AMBER).apply { tag="folder_state" })
        body.addView(button(if(folder) "폴더 바꾸기" else "녹음 폴더 고르기") { pickFolder.launch(CallFolder.pickIntent()) }.apply { tag="folder_pick" })

        sub("문자")
        val sms=a.checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED
        body.addView(CallUi.text(a,if(sms) "허용됨 ✓  업무 문자도 통화와 함께 정리해요." else "문자를 읽도록 허용하면 업무 문자도 통화와 함께 정리해요.",CallUi.BODY,sms,if(sms) CallUi.OK else CallUi.INK).apply { tag="sms_state" })
        if(!sms) readingButton(android.Manifest.permission.READ_SMS,"문자",askSms,"sms_allow")

        sub("통화기록")
        val log=a.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)==android.content.pm.PackageManager.PERMISSION_GRANTED
        body.addView(CallUi.text(a,if(log) "허용됨 ✓  녹음과 통화기록을 맞춰 상대 번호를 찾아요." else "통화기록을 읽도록 허용하면 녹음과 맞춰 상대 번호와 녹음 없는 통화를 찾아요.",CallUi.BODY,log,if(log) CallUi.OK else CallUi.INK).apply { tag="call_log_state" })
        if(!log) readingButton(android.Manifest.permission.READ_CALL_LOG,"통화기록",askCallLog,"call_log_allow")

        sub("통화 끝 알아차리기")
        val phone=a.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED
        body.addView(CallUi.text(a,if(phone) "허용됨 ✓  통화가 끝나면 바로 정리를 시작해요." else "허용하면 통화가 끝나자마자 녹음을 찾아 정리를 시작해요. 통화 내용은 듣지 않고, 전화가 끝났는지만 알아요.",
            CallUi.BODY,phone,if(phone) CallUi.OK else CallUi.INK).apply { tag="phone_state_state" })
        if(!phone) {
            if(ReadingPermission.blocked(a,android.Manifest.permission.READ_PHONE_STATE)) {
                body.addView(CallUi.text(a,"허용 창이 다시 뜨지 않아요. 앱 정보의 권한에서 전화 항목을 허용해 주세요.",CallUi.CAPTION,color=CallUi.SUBTLE))
                body.addView(button("앱 정보 열기") { ReadingPermission.openAppInfo(a) }.apply { tag="phone_state_allow" })
            } else body.addView(button("전화 상태 허용") { ReadingPermission.markAsked(a,android.Manifest.permission.READ_PHONE_STATE);askPhoneState.launch(android.Manifest.permission.READ_PHONE_STATE) }.apply { tag="phone_state_allow" })
        }

        sub("메일")
        mailGroup(s)

        callCardGroup(s)

        // ---- 정리: what is sent for summaries, and when the reports come ----
        group("정리")
        sub("정리 동의",first=true)
        body.addView(CallUi.text(a,"통화 녹음 파일은 휴대폰 밖으로 나가지 않아요. 휴대폰에서 글자로 바꾼 통화 내용과 업무 문자, 메일의 글만 Claude로 보내 요약하고, 결과는 이 휴대폰에만 저장해요. 언제든 끌 수 있어요."))
        body.addView(switchRow("consent_switch",s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),PipelineSettingsKeys.CONSENT_GRANTED) { on ->
            if(on) "켜짐: 통화 내용을 정리해요" else "꺼짐: 정리하지 않아요" })

        sub("알림 시각")
        hourRow("저녁 통화 정리",PipelineSettingsKeys.EVENING_HOUR,s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),15..23)
        body.addView(CallUi.divider(a))
        hourRow("아침 체크할 일",PipelineSettingsKeys.MORNING_HOUR,s.optInt(PipelineSettingsKeys.MORNING_HOUR,6),4..11)
        body.addView(CallUi.divider(a));body.addView(switchRow("followup_switch",s.optBoolean(PipelineSettingsKeys.FOLLOWUP_NOTICE,true),PipelineSettingsKeys.FOLLOWUP_NOTICE) { "통화마다 알림" })
        // Design 3.3 (r7-3): a verified new version installs by itself; off, a notification only.
        body.addView(CallUi.divider(a))
        body.addView(switchRow("auto_install_switch",s.optBoolean(PipelineSettingsKeys.AUTO_INSTALL,true),PipelineSettingsKeys.AUTO_INSTALL) { "새 버전은 자동으로 설치" })
        if(HomeScreen.installStep(a)) {
            body.addView(CallUi.text(a,"새 버전을 누르지 않고 설치하려면 이음의 설치를 한 번 허용해 주세요. 열리는 화면에서 '이 출처 허용'을 켜 주세요.",CallUi.BODY).apply { tag="install_source_state" })
            body.addView(button("설치 허용") { CallUpdate.openInstallSourceSettings(a) }.apply { tag="install_source_allow" })
        }
        myInfo(s)
        personalNumbers()

        // ---- 상태와 도움 ----
        group("상태와 도움")
        statusLights(s,counts)
        body.addView(CallUi.divider(a))
        // Installed version, and the next one when it is downloaded and waiting (design: 보고와 화면); a failed install shows its reason.
        body.addView(CallUi.text(a,CallUpdate.versionLine(kr.callbrief.BuildConfig.VERSION_NAME,kr.callbrief.BuildConfig.VERSION_CODE,CallUpdate.ready(a)),CallUi.BODY,true).apply { tag="app_version" })
        updateBlock=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="update_block" }.also { body.addView(it) };updateKey=null;fillUpdate()
        CallUpdate.errorWords(s.optString(PipelineSettingsKeys.LAST_UPDATE_ERROR))?.let {
            body.addView(CallUi.text(a,it.reason,CallUi.CAPTION,color=CallUi.AMBER).apply { tag="update_error" }) }
        storagePercent?.let { body.addView(CallUi.text(a,"저장 공간 ${it}% 사용",CallUi.BODY).apply { tag="storage_use" }) }

        val modelState=s.optString(PipelineSettingsKeys.MODEL_STATE)
        sub("음성 인식 모델")
        val part=PipelineModelManager(CallPipelineStartup.storage(a)).partFile
        body.addView(CallUi.text(a,modelWords(modelState,part),CallUi.BODY,true,if(modelState=="READY") CallUi.OK else CallUi.INK).apply { tag="model_state";fontFeatureSettings="tnum" })
        modelPart=part;modelShown=modelState
        if(modelState!="READY"&&modelState!="UNSUPPORTED"&&modelState!="DOWNLOADING") body.addView(button(if(modelState=="FAILED") "다시 받기" else "모델 받기") {
            runCatching { HomeScreen.modelStart(a) };Toast.makeText(a,"음성 인식 모델을 받기 시작해요.",Toast.LENGTH_SHORT).show();refresh()
        }.apply { tag="model_get" })
        if(kr.callbrief.BuildConfig.SYNTHETIC_TOOLS_ENABLED&&!CallInboxConfig.enabled&&modelState=="READY") body.addView(act("속도 측정 (시험용)","benchmark","재는 중이에요") { done -> benchmark(done) })

        sub("현장 상황 메모")
        val memo=CallUi.text(a,siteMemo ?: "첫 통화들에서 자동으로 채워져요",CallUi.BODY,color=if(siteMemo==null) CallUi.SUBTLE else CallUi.INK).apply { tag="site_memo";visibility=View.GONE }
        body.addView(CallUi.linkButton(a,"현장 메모 보기 ›") { memo.visibility=if(memo.visibility==View.GONE) View.VISIBLE else View.GONE }.apply { tag="site_memo_toggle" },
            LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        body.addView(memo)

        sub("처리한 통화")
        val waiting=(counts[CallRecordStore.TRANSCRIBED] ?: 0)+(counts[CallRecordStore.WAITING_CONSENT] ?: 0)
        // A recording with no speech in it (EMPTY_TRANSCRIPT) is no failure that a second try fixes: it is not counted here.
        val allFailed=counts[CallRecordStore.FAILED] ?: 0
        val failed=watch?.failed ?: allFailed
        body.addView(CallUi.text(a,"정리된 통화 ${counts[CallRecordStore.ANALYZED] ?: 0}건, 기다리는 통화 ${waiting}건, 정리 못 한 통화 ${failed}건",CallUi.BODY,true).apply { tag="handled_calls" })
        if(allFailed>failed) body.addView(CallUi.caption(a,"말소리를 찾지 못한 녹음 ${allFailed-failed}건은 다시 정리해도 같아서 세지 않았어요.").apply { tag="handled_empty" })
        if(waiting+failed>0) body.addView(act("기다리는 통화 다시 정리하기","reanalyze_all","다시 정리하는 중이에요") { done ->
            a.lifecycleScope.launch {
                val n=withContext(Dispatchers.IO) { runCatching { store().requeueAll() }.getOrDefault(0) }
                CallPipelineStartup.settingsChanged(a)
                Toast.makeText(a,"${n}건을 다시 정리할게요.",Toast.LENGTH_SHORT).show();done();if(live()) refresh()
            }
        })

        // Contact cards (v2.8): worked out again from the store when one looks wrong (the call card reads them in v3.0).
        body.addView(act("상대 카드 다시 만들기","rebuild_cards","다시 만드는 중이에요") { done ->
            a.lifecycleScope.launch {
                val n=withContext(Dispatchers.IO) { runCatching { ContactCards.rebuildAll(store()) }.getOrNull() }
                done()
                Toast.makeText(a,if(n!=null) "상대 카드 ${n}장을 다시 만들었어요" else "다시 만들지 못했어요",Toast.LENGTH_SHORT).show()
            }
        })
        // Sends the heartbeat now so whoever looks after the app sees the phone's state (design B5).
        body.addView(CallUi.divider(a))
        body.addView(act("문제 알리기","report_problem","보내는 중이에요") { done ->
            a.lifecycleScope.launch {
                val ok=withContext(Dispatchers.IO) { PhoneStatus.report(a) }
                done()
                Toast.makeText(a,if(ok) "관리하는 분께 상태를 보냈어요" else "보내지 못했어요. 인터넷을 확인해 주세요",Toast.LENGTH_SHORT).show()
            }
        })
        a.putBack(keep)
        val want=focusTag;val group=focusGroup;focusTag=null;focusGroup=null
        if(want!=null) page.post { if(live()) { if(page.findViewWithTag<View>(want)!=null) goTo(want) else group?.let(::goTo) } }
    }

    /** A switch that shows its own state: changed, it saves [key] and changes only its words (and the status lights), no redraw. */
    private fun switchRow(tag:String,on:Boolean,key:String,words:(Boolean)->String)=Switch(a).apply {
        text=words(on);textSize=CallUi.SUMMARY;isChecked=on
        minHeight=CallUi.dp(a,64);this.tag=tag;setTextColor(CallUi.INK)
        val tint=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(CallUi.ACCENT,CallUi.FAINT))
        thumbTintList=tint;trackTintList=tint.withAlpha(120)
        setOnCheckedChangeListener { v,checked -> v.text=words(checked);save(redraw=false) { it.put(key,checked) } }
    }

    /** v3.0 r11-1 (design 7.3): "통화 카드": the overlay permission, three switches, and the card's place back to the start. */
    private fun callCardGroup(s:JSONObject) {
        group("통화 카드")
        val overlay=CallCardAsk.overlayAllowed(a)
        body.addView(CallUi.text(a,if(overlay) "허용됨 ✓  전화가 오면 통화 화면 위에 이음 카드를 보여 드려요." else "허용하면 전화가 올 때 통화 화면 위에 상대와 지난 통화, 체크할 일을 보여 드려요.",
            CallUi.BODY,overlay,if(overlay) CallUi.OK else CallUi.INK).apply { tag="card_overlay_state" })
        if(!overlay) body.addView(button("카드 보기 허용") { CallCardAsk.openOverlaySettings(a) }.apply { tag="card_overlay_allow" })
        body.addView(CallUi.divider(a))
        body.addView(switchRow("card_incoming_switch",s.optBoolean(PipelineSettingsKeys.CARD_INCOMING,true),PipelineSettingsKeys.CARD_INCOMING) { "전화 올 때" })
        body.addView(CallUi.divider(a))
        body.addView(switchRow("card_outgoing_switch",s.optBoolean(PipelineSettingsKeys.CARD_OUTGOING,true),PipelineSettingsKeys.CARD_OUTGOING) { "전화 걸 때" })
        if(CallCardAsk.roleAvailable(a) && !CallCardAsk.roleHeld(a)) {
            body.addView(CallUi.text(a,"거는 전화에 카드를 보려면 한 번 허용해 주세요. 전화는 그대로 걸려요.",CallUi.CAPTION,color=CallUi.SUBTLE).apply { tag="card_role_state" })
            body.addView(button("거는 전화도 허용") { CallCardAsk.askRole(a,askCallRole) }.apply { tag="card_role_allow" })
        }
        body.addView(CallUi.divider(a))
        body.addView(switchRow("card_unknown_switch",s.optBoolean(PipelineSettingsKeys.CARD_UNKNOWN,false),PipelineSettingsKeys.CARD_UNKNOWN) { "전화부에 없는 번호도" })
        body.addView(CallUi.divider(a))
        body.addView(button("카드 위치 처음으로") {
            save(redraw=false,done={ Toast.makeText(a,"카드 위치를 처음으로 돌렸어요.",Toast.LENGTH_SHORT).show() }) { it.remove(PipelineSettingsKeys.CARD_POS_Y) }
        }.apply { tag="card_position_reset" })
    }

    private fun notifyAllowed()=android.os.Build.VERSION.SDK_INT<33||a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
    /** The "지금 상태" sheet part, kept so a switch or a slider can draw only the lights again. */
    private var lightsCard:LinearLayout?=null
    /** "지금 상태": five lights (green "정상" or amber with the reason and what to do, never red) at the top of the last group. */
    private fun statusLights(s:JSONObject,counts:Map<String,Int>) {
        val card=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="settings_status" }
        fillLights(card,s,counts);lightsCard=card
        body.addView(card)
    }
    private fun fillLights(card:LinearLayout,s:JSONObject,counts:Map<String,Int>) {
        val now=System.currentTimeMillis();val w=watch
        val input=StatusLights.Input(now,s.optString("treeUri").isNotBlank(),s.optString(PipelineSettingsKeys.MODEL_STATE),
            w?.queue?.count ?: 0,s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0),s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),
            w?.lateLive ?: 0,w?.failed ?: (counts[CallRecordStore.FAILED] ?: 0),w?.oldestLateMs,s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),
            s.optString(PipelineSettingsKeys.LAST_UPDATE_ERROR),CallUpdate.ready(a)!=null,
            folderReadable=folderReadable,lastDiscoveryMs=s.optLong(kr.callbrief.AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,0L),
            oldestQueuedMs=w?.queue?.oldestMs,updateReadySinceMs=s.optLong(PhoneStatus.UPDATE_READY_SINCE_MS,0L),
            cardOverlay=CallCardAsk.overlayAllowed(a),
            phoneState=a.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED,
            cardRole=!CallCardAsk.roleAvailable(a) || CallCardAsk.roleHeld(a),
            cardOn=s.optBoolean(PipelineSettingsKeys.CARD_INCOMING,true) || s.optBoolean(PipelineSettingsKeys.CARD_OUTGOING,true))
        card.removeAllViews()
        card.addView(CallUi.text(a,"지금 상태",CallUi.HEADING,true).apply { setPadding(0,0,0,CallUi.dp(a,4)) })
        // A light that names a group of this page goes there when it is pressed (review #24).
        StatusLights.lights(input).forEach { l -> card.addView(CallUi.signalRow(a,l.label,l.words,l.ok,l.target?.let { t -> { goTo(t) } }).apply { tag=l.tag }) }
    }
    /** Scrolls the page so the group tagged [tag] (its name above its sheet) is at the top. */
    internal fun goTo(tag:String) {
        val target=page.findViewWithTag<View>(tag) ?: return
        val scroll=a.window.decorView.findViewWithTag<ScrollView>("page_scroll") ?: return
        var y=0;var v:View?=target
        while(v!=null && v!==scroll) { y+=v.top;v=v.parent as? View }
        scroll.scrollTo(0,(y-CallUi.dp(a,56)).coerceAtLeast(0))
    }

    /** Test builds only: transcribe the bundled public Korean sample (repeated to about one minute)
     * with the large model and record the speed. No call recording or network is needed. */
    private fun benchmark(done:()->Unit) {
        Toast.makeText(a,"속도를 재는 중이에요. 1~3분 걸릴 수 있어요.",Toast.LENGTH_LONG).show()
        a.lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val storage=CallPipelineStartup.storage(a);val model=PipelineModelManager(storage)
                val file=checkNotNull(model.verifiedModelFile()) { "MODEL_NOT_READY" }
                val one=PipelineAudioMath.decodeWav(a.assets.open("local-stt/public-ko.wav").use{it.readBytes()}).chunks.flatMap{it.second.asList()}
                val pcm=FloatArray(one.size*8){ one[it%one.size] }
                val started=System.currentTimeMillis()
                val segments=WhisperCallTranscriber(kr.callbrief.AndroidWhisperEngine(storage),model).transcribe(file,pcm){false}
                val elapsed=System.currentTimeMillis()-started;val audioMs=pcm.size*1000L/16000
                val perMinute=elapsed*60000/audioMs
                settings().update{ it.put(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,perMinute).put(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,System.currentTimeMillis()) }
                Triple(perMinute,audioMs,segments.joinToString(""){it.text}.trim().take(80))
            } }
            done()
            result.onSuccess { (perMinute,audioMs,text) ->
                CallUi.sheetDialog(a,"속도 측정 결과",
                    message="녹음 ${audioMs/1000}초를 처리했어요.\n녹음 1분당 약 ${perMinute/1000}초 걸려요.\n\n인식된 앞부분: $text",secondary="확인")
            }.onFailure { Toast.makeText(a,if(it.message=="MODEL_NOT_READY") "먼저 음성 인식 모델을 받아주세요." else "측정하지 못했어요.",Toast.LENGTH_LONG).show() }
            if(live()) refresh()
        }
    }

    /** Ask; once asked and still refused, explain the restricted setting and open App info instead. */
    private fun readingButton(permission:String,what:String,ask:androidx.activity.result.ActivityResultLauncher<String>,tag:String) {
        if(ReadingPermission.blocked(a,permission)) {
            body.addView(CallUi.text(a,ReadingPermission.restrictedHelp(what),CallUi.CAPTION,color=CallUi.SUBTLE))
            body.addView(button("앱 정보 열기") { ReadingPermission.openAppInfo(a) }.apply { this.tag=tag })
        } else body.addView(button("$what 읽기 허용") { ReadingPermission.markAsked(a,permission);ask.launch(permission) }.apply { this.tag=tag })
    }

    /** One line under the service buttons: where to switch IMAP on and make the app password. */
    internal fun mailGuide(service:String)=when(service) {
        "네이버" -> "네이버 메일 → 환경설정 → POP3/IMAP 설정에서 IMAP 사용, 앱 비밀번호는 네이버 계정 보안에서 만들어요."
        "Gmail" -> "Gmail 설정 → 전달 및 POP/IMAP에서 IMAP 사용, 앱 비밀번호는 구글 계정 보안에서 만들어요."
        else -> "메일 서비스 설정에서 IMAP을 켜고, 앱 비밀번호를 만들어 아래에 넣어 주세요."
    }

    /** Work mailbox: which service, address, app password, then one [연결하기] that checks before saving. What is typed is kept in
     * these fields, so a redraw (coming back from the mail app with the app password) puts it back (review #8). The password is
     * kept only while the screen lives, never in the saved state. */
    private var mailService="네이버"
    internal var mailUser=""
        private set
    private var mailPassword=""
    private var mailHost=""
    private fun watching(f:EditText,keep:(String)->Unit)=f.addTextChangedListener(object:TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,af:Int) {}
        override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int) {}
        override fun afterTextChanged(s:Editable?) { keep(s?.toString().orEmpty()) }
    })

    private fun mailGroup(s:JSONObject) {
        val user=s.optString(PipelineSettingsKeys.MAIL_USER)
        if(user.isNotBlank() && s.optString(PipelineSettingsKeys.MAIL_PASSWORD).isNotBlank()) {
            val error=s.optString(PipelineSettingsKeys.MAIL_ERROR)
            body.addView(CallUi.text(a,if(error.isBlank()) "연결됨 ✓  $user" else error,CallUi.BODY,true,if(error.isBlank()) CallUi.OK else CallUi.AMBER).apply { tag="mail_state" })
            body.addView(CallUi.text(a,"받은 메일 중 업무 메일을 30분마다 가져와 통화와 함께 정리해요.",CallUi.CAPTION,color=CallUi.SUBTLE))
            body.addView(act("메일 연결 끊기","mail_disconnect","끊는 중이에요") { done ->
                save(done=done) { it.remove(PipelineSettingsKeys.MAIL_HOST);it.remove(PipelineSettingsKeys.MAIL_USER);it.remove(PipelineSettingsKeys.MAIL_PASSWORD);it.remove(PipelineSettingsKeys.MAIL_ERROR) } })
            return
        }
        body.addView(CallUi.text(a,"업무 메일을 연결하면 메일도 업체와 사람별로 함께 정리해요. 메일 서비스 설정에서 IMAP을 켜고 앱 비밀번호를 만들어 아래에 넣어 주세요.",CallUi.BODY).apply { tag="mail_state" })
        val choices=MessageIntake.MAIL_HOSTS.keys.toList()+"직접 입력"
        // Two rows of two, so every name stays on one line at any width and letter size.
        val row=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val lines=List((choices.size+1)/2) { LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL }.also { l -> row.addView(l,LinearLayout.LayoutParams(-1,-2).apply { if(it>0) topMargin=CallUi.dp(a,6) }) } }
        fun field(hint:String,password:Boolean=false)=EditText(a).apply {
            this.hint=hint;textSize=CallUi.BODY;isSingleLine=true;minHeight=CallUi.dp(a,56);importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            inputType=if(password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            typeface=CallUi.font(a,CallUi.Weight.REGULAR)
        }
        val host=field("IMAP 서버 (예: imap.회사.com)").apply { tag="mail_host";setText(mailHost) }
        watching(host) { mailHost=it }
        val guide=CallUi.caption(a,mailGuide(mailService),CallUi.SUBTLE).apply { tag="mail_guide";setPadding(0,CallUi.dp(a,8),0,0) }
        val services=ArrayList<Pair<String,Button>>()
        // A service button changes only the buttons' colours, the guide line and the server field; nothing typed is lost.
        fun paint() {
            services.forEach { (name,b) -> val on=name==mailService
                b.typeface=CallUi.font(a,if(on) CallUi.Weight.SEMIBOLD else CallUi.Weight.MEDIUM)
                b.setTextColor(if(on) CallUi.ON_ACCENT else CallUi.SUBTLE);b.background=CallUi.ripple(a,CallUi.sheet(a,if(on) CallUi.ACCENT else CallUi.CHIP,radius=14))
                b.isSelected=on }
            guide.text=mailGuide(mailService)
            host.visibility=if(mailService=="직접 입력") View.VISIBLE else View.GONE
        }
        choices.forEachIndexed { i,name ->
            val b=Button(a).apply {
                text=name;textSize=16f;isAllCaps=false;stateListAnimator=null;minHeight=CallUi.dp(a,48);minimumHeight=minHeight;setPadding(0,0,0,0);maxLines=1
                tag="mail_service_$i";CallUi.onTap(this) { mailService=name;paint() }
            }
            services+=name to b
            lines[i/2].addView(b,LinearLayout.LayoutParams(0,-2,1f).apply { if(i%2==0) marginEnd=CallUi.dp(a,6) })
        }
        if(choices.size%2==1) lines.last().addView(View(a),LinearLayout.LayoutParams(0,1,1f).apply { marginStart=CallUi.dp(a,0) })
        paint()
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,10) })
        body.addView(guide)
        body.addView(host)
        val address=field("메일 주소").apply { tag="mail_user";setText(mailUser) }
        val password=field("앱 비밀번호",true).apply { tag="mail_password";setText(mailPassword) }
        watching(address) { mailUser=it };watching(password) { mailPassword=it }
        body.addView(address);body.addView(password)
        body.addView(act("연결하기","mail_connect","연결하는 중이에요",big=true) { done ->
            val h=if(mailService=="직접 입력") mailHost.trim() else MessageIntake.MAIL_HOSTS.getValue(mailService)
            val u=mailUser.trim();val p=mailPassword
            if(h.isEmpty()||u.isEmpty()||p.isEmpty()) { done();Toast.makeText(a,"서버, 메일 주소, 앱 비밀번호를 모두 넣어 주세요.",Toast.LENGTH_LONG).show();return@act }
            a.lifecycleScope.launch {
                val problem=withContext(Dispatchers.IO) { MessageIntake.checkMail(h,u,p) }
                if(problem!=null) { done();Toast.makeText(a,problem,Toast.LENGTH_LONG).show();return@launch }
                mailPassword="";mailUser="";mailHost=""
                save(done=done) { it.put(PipelineSettingsKeys.MAIL_HOST,h).put(PipelineSettingsKeys.MAIL_USER,u).put(PipelineSettingsKeys.MAIL_PASSWORD,p).remove(PipelineSettingsKeys.MAIL_ERROR) }
                Toast.makeText(a,"메일을 연결했어요.",Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun hourWords(h:Int)=when { h==0 -> "밤 12시";h<12 -> "오전 ${h}시";h==12 -> "낮 12시";else -> "오후 ${h-12}시" }

    /** Slider saves waiting for [HOUR_SAVE_MS] of quiet, by settings key. */
    private val pendingHours=LinkedHashMap<String,Runnable>()
    /** Saves the sliders still waiting (the screen goes away before the quiet time is over). */
    private fun flushHours() { pendingHours.values.toList().forEach { ticker.removeCallbacks(it);it.run() };pendingHours.clear() }

    /** One alert time: name on the left, the time large on the right, a slider below over a sensible range.
     * Saved [HOUR_SAVE_MS] after the last move (finger, keys or TalkBack), so dragging does not reschedule on every step;
     * the page is not drawn again (the slider and the time already show it). */
    private fun hourRow(name:String,key:String,hour:Int,range:IntRange) {
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(0,CallUi.dp(a,6),0,CallUi.dp(a,10)) }
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(CallUi.text(a,name,CallUi.BODY),LinearLayout.LayoutParams(0,-2,1f))
        val value=CallUi.text(a,hourWords(hour),CallUi.HEADING,true,CallUi.ACCENT).apply { tag="hour_$key" }
        head.addView(value)
        box.addView(head)
        var saved=hour
        box.addView(SeekBar(a).apply {
            min=range.first;max=range.last;progress=hour.coerceIn(range);contentDescription="$name 시각 고르기";tag="hour_bar_$key"
            progressTintList=android.content.res.ColorStateList.valueOf(CallUi.ACCENT);thumbTintList=progressTintList
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(CallUi.MUTED_BAR)
            minimumHeight=CallUi.dp(a,48);setPadding(CallUi.dp(a,8),0,CallUi.dp(a,8),0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar,p:Int,fromUser:Boolean) {
                    value.text=hourWords(p);if(android.os.Build.VERSION.SDK_INT>=30) s.stateDescription=hourWords(p)
                    if(!fromUser) return
                    pendingHours.remove(key)?.let(ticker::removeCallbacks)
                    val later=Runnable { pendingHours.remove(key);if(p!=saved) { saved=p;save(true,redraw=false) { it.put(key,p) } } }
                    pendingHours[key]=later;ticker.postDelayed(later,HOUR_SAVE_MS)
                }
                override fun onStartTrackingTouch(s:SeekBar) {}
                override fun onStopTrackingTouch(s:SeekBar) {}
            })
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,8);marginStart=-CallUi.dp(a,8);marginEnd=-CallUi.dp(a,8) })
        val ends=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL }
        ends.addView(CallUi.caption(a,hourWords(range.first),CallUi.MUTED),LinearLayout.LayoutParams(0,-2,1f))
        ends.addView(CallUi.caption(a,hourWords(range.last),CallUi.MUTED))
        box.addView(ends)
        body.addView(box)
    }
    /** 내 정보 (design C5 [사용자]): who "I" am in the calls, so the summary knows whose check items they are. Empty fields send roles only. */
    private fun myInfo(s:JSONObject) {
        sub("내 정보")
        body.addView(CallUi.caption(a,"통화를 정리할 때 누가 \"나\"인지 알려 줘요. 비워 둬도 돼요.").apply { tag="my_info_note" })
        listOf(Triple("이름",PipelineSettingsKeys.MY_NAME,"예: 김민지"),Triple("회사와 현장",PipelineSettingsKeys.MY_COMPANY,"예: ○○건설 △△현장"),
            Triple("직책",PipelineSettingsKeys.MY_ROLE,"예: 공무 부장")).forEach { (label,key,hint) ->
            val value=s.optString(key).trim()
            body.addView(CallUi.caption(a,label).apply { setPadding(0,CallUi.dp(a,10),0,0) })
            body.addView(CallUi.text(a,value.ifEmpty { "적지 않았어요" },CallUi.BODY,value.isNotEmpty(),if(value.isEmpty()) CallUi.SUBTLE else CallUi.INK).apply { tag="my_$key" })
            body.addView(button(if(value.isEmpty()) "$label 적기" else "$label 고치기") {
                val field=CallUi.sheetInput(a,value,hint,tag="my_input")
                CallUi.sheetDialog(a,label,body=field,primary="저장",
                    onPrimary={ val v=field.text.toString().trim().take(40);save { if(v.isEmpty()) it.remove(key) else it.put(key,v) } },secondary="닫기")
            }.apply { tag="my_edit_$key" })
        }
    }

    /** 개인으로 둔 번호 (design E9): numbers whose calls and texts were judged personal are kept here and not sent. Each can be taken back. */
    private fun personalNumbers() {
        sub("개인으로 둔 번호")
        val list=personal
        body.addView(CallUi.text(a,if(list.isEmpty()) "아직 없어요. 개인 통화로 보이는 번호가 생기면 여기에 모여요."
            else "${list.size}개 번호의 통화와 문자는 정리하지 않고 휴대폰에만 둬요.",CallUi.BODY).apply { tag="personal_state" })
        if(list.isEmpty()) return
        body.addView(button("번호 보기") {
            val labels=list.map { (k,ms) -> CallUi.who(k)+"  "+java.time.format.DateTimeFormatter.ofPattern("M월 d일").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Seoul"))) }.toTypedArray()
            CallUi.sheetDialog(a,"눌러서 업무 번호로 되돌려요",options=labels.toList(),onOption={ i ->
                val key=list[i].first
                CallUi.sheetDialog(a,"업무 번호로 되돌리기",message=CallUi.who(key)+" 번호를 업무 번호로 되돌릴까요? 앞으로 이 번호의 통화와 문자를 다시 정리해요.",
                    primary="되돌리기",onPrimary={
                        a.lifecycleScope.launch {
                            withContext(Dispatchers.IO) { runCatching { store().forgetPersonal(key) } }
                            if(live()) refresh()
                        }
                    },secondary="닫기")
            },secondary="닫기")
        }.apply { tag="personal_list" })
    }

}
