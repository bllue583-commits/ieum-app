package kr.callbrief.pipeline

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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

/** "설정": Claude key, consent, voice-model download, notification hours and a plain status. The key is never shown again. */
internal class SettingsScreen(a:MainActivity):AppScreen(a) {
    companion object { const val TICK_MS=1000L }
    private val page:LinearLayout get()=a.column
    /** The sheet the current settings group is written into. */
    private lateinit var body:LinearLayout
    private var busy=false
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
    private fun settings()=SecureSettings(CallPipelineStartup.storage(a))

    /** Settings comes to the front (or the app comes back): read, draw, and follow a download every second. */
    override fun enter() { body=page;refresh();ticker.removeCallbacks(tick);ticker.postDelayed(tick,TICK_MS) }
    override fun leave() { ticker.removeCallbacks(tick) }

    // ---- 앱 버전: getting the next version, with a bar while it comes (round 6 v2.5) ----
    private val ticker=android.os.Handler(android.os.Looper.getMainLooper())
    /** While the screen is open, the update part follows the download every second. */
    private val tick=object:Runnable { override fun run() { if(!live()) return;fillUpdate();ticker.postDelayed(this,TICK_MS) } }
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
                block.addView(CallUi.bigButton(a,"","지금 설치") { if(!busy) { CallUpdate.install(a,metered());fillUpdate() } }.apply { tag="update_install" })
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
                block.addView(CallUi.bigButton(a,"","다시 받기") { if(!busy) startFetch() }.apply { tag="update_fetch" })
            }
            avail!=null -> {
                block.addView(headline("새 버전 $name 받을 수 있어요"))
                if(avail.notes.isNotBlank()) block.addView(CallUi.text(a,avail.notes,CallUi.BODY))
                block.addView(CallUi.bigButton(a,"","지금 받기") { if(!busy) startFetch() }.apply { tag="update_fetch" })
                block.addView(note("받는 데 ${CallUpdate.sizeWords(avail.size)}가 들어요. 받은 뒤 설치를 한 번 눌러 주세요."))
            }
            else -> {
                block.addView(note("마지막 확인 "+CallUi.dateTime(CallUpdate.lastChecked(a))).apply { setPadding(0,CallUi.dp(a,6),0,0) })
                block.addView(CallUi.linkButton(a,"새 버전 확인하기 ›") { if(!busy) startFetch() }.apply { tag="update_check" },
                    LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
            }
        }
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
    private fun button(value:String,action:()->Unit)=CallUi.button(a,value) { if(!busy)action() }

    private fun refresh() {
        a.lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                val s=runCatching { settings().snapshot() }.getOrDefault(JSONObject())
                val counts=runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.counts() } }.getOrDefault(emptyMap())
                val memo=runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.siteMemo() } }.getOrNull()
                oldestWaiting=runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.oldestWaitingMs() } }.getOrNull()
                watch=StatusLights.read(CallPipelineStartup.storage(a),s,System.currentTimeMillis())
                folderReadable=StatusLights.folderReadable(a,s)
                storagePercent=watch?.dbPercent
                personal=runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.personalNumbers() } }.getOrDefault(emptyList())
                Triple(s,counts,memo)
            } }
            if(!live())return@launch
            result.onSuccess { (s,c,m) -> render(s,c,m) }
        }
    }

    /** Runs a settings write off the main thread, then refreshes. */
    private fun save(hoursChanged:Boolean=false,block:(JSONObject)->Unit) {
        busy=true
        a.lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching { settings().update(block) }.isSuccess }
            busy=false
            if(!ok) Toast.makeText(a,"설정을 저장하지 못했어요.",Toast.LENGTH_LONG).show()
            else CallPipelineStartup.settingsChanged(a,hoursChanged)
            refresh()
        }
    }

    private fun render(s:JSONObject,counts:Map<String,Int>,siteMemo:String?=null) {
        page.removeAllViews();body=page
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

        sub("메일")
        mailGroup(s)

        // ---- 정리: what is sent for summaries, and when the reports come ----
        group("정리")
        sub("정리 동의",first=true)
        body.addView(CallUi.text(a,"통화 녹음 파일은 휴대폰 밖으로 나가지 않아요. 휴대폰에서 글자로 바꾼 통화 내용과 업무 문자, 메일의 글만 Claude로 보내 요약하고, 결과는 이 휴대폰에만 저장해요. 언제든 끌 수 있어요."))
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        body.addView(Switch(a).apply {
            text=if(consent) "켜짐: 통화 내용을 정리해요" else "꺼짐: 정리하지 않아요";textSize=CallUi.SUMMARY;isChecked=consent
            minHeight=CallUi.dp(a,64);tag="consent_switch";setTextColor(CallUi.INK)
            val tint=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(CallUi.ACCENT,CallUi.FAINT))
            thumbTintList=tint;trackTintList=tint.withAlpha(120)
            setOnCheckedChangeListener { _,checked -> if(checked!=consent) save { it.put(PipelineSettingsKeys.CONSENT_GRANTED,checked) } }
        })

        sub("알림 시각")
        hourRow("저녁 통화 정리",PipelineSettingsKeys.EVENING_HOUR,s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),15..23)
        body.addView(CallUi.divider(a))
        hourRow("아침 체크할 일",PipelineSettingsKeys.MORNING_HOUR,s.optInt(PipelineSettingsKeys.MORNING_HOUR,6),4..11)
        // Design 3.3 (r7-3): a verified new version installs by itself; off, a notification only.
        body.addView(CallUi.divider(a))
        val auto=s.optBoolean(PipelineSettingsKeys.AUTO_INSTALL,true)
        body.addView(Switch(a).apply {
            text="새 버전은 자동으로 설치";textSize=CallUi.SUMMARY;isChecked=auto
            minHeight=CallUi.dp(a,64);tag="auto_install_switch";setTextColor(CallUi.INK)
            val tint=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(CallUi.ACCENT,CallUi.FAINT))
            thumbTintList=tint;trackTintList=tint.withAlpha(120)
            setOnCheckedChangeListener { _,checked -> if(checked!=auto) save { it.put(PipelineSettingsKeys.AUTO_INSTALL,checked) } }
        })
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
        body.addView(CallUi.text(a,when(modelState) {
            "READY" -> "준비됨 ✓";"DOWNLOADING" -> "받는 중이에요 (Wi-Fi 연결 필요, 약 570MB)";"FAILED" -> "받지 못했어요. 다시 받아주세요."
            "UNSUPPORTED" -> "이 휴대폰에서는 사용할 수 없어요.";else -> "아직 받지 않았어요 (Wi-Fi에서 약 570MB)"
        },CallUi.BODY,true,if(modelState=="READY") CallUi.OK else CallUi.INK))
        if(modelState!="READY"&&modelState!="UNSUPPORTED") body.addView(button(if(modelState=="DOWNLOADING") "다시 확인하기" else "모델 받기") {
            PipelineModelDownloadWork.enqueue(a);Toast.makeText(a,"Wi-Fi에 연결되면 받기 시작해요.",Toast.LENGTH_LONG).show()
        })
        if(kr.callbrief.BuildConfig.SYNTHETIC_TOOLS_ENABLED&&!CallInboxConfig.enabled&&modelState=="READY") body.addView(button("속도 측정 (시험용)") { benchmark() }.apply { tag="benchmark" })

        sub("현장 상황 메모")
        val memo=CallUi.text(a,siteMemo ?: "첫 통화들에서 자동으로 채워져요",CallUi.BODY,color=if(siteMemo==null) CallUi.SUBTLE else CallUi.INK).apply { tag="site_memo";visibility=android.view.View.GONE }
        body.addView(CallUi.linkButton(a,"현장 메모 보기 ›") { memo.visibility=if(memo.visibility==android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE }.apply { tag="site_memo_toggle" },
            LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        body.addView(memo)

        sub("처리한 통화")
        val waiting=(counts[CallRecordStore.TRANSCRIBED] ?: 0)+(counts[CallRecordStore.WAITING_CONSENT] ?: 0)
        val failed=counts[CallRecordStore.FAILED] ?: 0
        body.addView(CallUi.text(a,"정리된 통화 ${counts[CallRecordStore.ANALYZED] ?: 0}건, 기다리는 통화 ${waiting}건, 정리 못 한 통화 ${failed}건",CallUi.BODY,true))
        if(waiting+failed>0) body.addView(button("기다리는 통화 다시 정리하기") {
            busy=true
            a.lifecycleScope.launch {
                val n=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.requeueAll() } }.getOrDefault(0) }
                busy=false;CallPipelineStartup.settingsChanged(a)
                Toast.makeText(a,"${n}건을 다시 정리할게요.",Toast.LENGTH_SHORT).show();refresh()
            }
        }.apply { tag="reanalyze_all" })

        // Sends the heartbeat now so whoever looks after the app sees the phone's state (design B5).
        body.addView(CallUi.divider(a))
        body.addView(button("문제 알리기") {
            busy=true
            a.lifecycleScope.launch {
                val ok=withContext(Dispatchers.IO) { PhoneStatus.report(a) }
                busy=false
                Toast.makeText(a,if(ok) "관리하는 분께 상태를 보냈어요" else "보내지 못했어요. 인터넷을 확인해 주세요",Toast.LENGTH_SHORT).show()
            }
        }.apply { tag="report_problem" })
    }

    private fun notifyAllowed()=android.os.Build.VERSION.SDK_INT<33||a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
    /** "지금 상태": five lights (green "정상" or amber with the reason and what to do, never red) at the top of the last group. */
    private fun statusLights(s:JSONObject,counts:Map<String,Int>) {
        val now=System.currentTimeMillis();val w=watch
        val input=StatusLights.Input(now,s.optString("treeUri").isNotBlank(),s.optString(PipelineSettingsKeys.MODEL_STATE),
            w?.queue?.count ?: 0,s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0),s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),
            w?.lateLive ?: 0,w?.failed ?: (counts[CallRecordStore.FAILED] ?: 0),w?.oldestLateMs,s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),notifyAllowed(),
            s.optString(PipelineSettingsKeys.LAST_UPDATE_ERROR),CallUpdate.ready(a)!=null,
            folderReadable=folderReadable,lastDiscoveryMs=s.optLong(kr.callbrief.AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,0L),
            oldestQueuedMs=w?.queue?.oldestMs,updateReadySinceMs=s.optLong(PhoneStatus.UPDATE_READY_SINCE_MS,0L))
        val card=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="settings_status" }
        card.addView(CallUi.text(a,"지금 상태",CallUi.HEADING,true).apply { setPadding(0,0,0,CallUi.dp(a,4)) })
        StatusLights.lights(input).forEach { l -> card.addView(CallUi.signalRow(a,l.label,l.words,l.ok).apply { tag=l.tag }) }
        body.addView(card)
    }

    /** Test builds only: transcribe the bundled public Korean sample (repeated to about one minute)
     * with the large model and record the speed. No call recording or network is needed. */
    private fun benchmark() {
        busy=true;Toast.makeText(a,"속도를 재는 중이에요. 1~3분 걸릴 수 있어요.",Toast.LENGTH_LONG).show()
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
            busy=false
            result.onSuccess { (perMinute,audioMs,text) ->
                CallUi.sheetDialog(a,"속도 측정 결과",
                    message="녹음 ${audioMs/1000}초를 처리했어요.\n녹음 1분당 약 ${perMinute/1000}초 걸려요.\n\n인식된 앞부분: $text",secondary="확인")
            }.onFailure { Toast.makeText(a,if(it.message=="MODEL_NOT_READY") "먼저 음성 인식 모델을 받아주세요." else "측정하지 못했어요.",Toast.LENGTH_LONG).show() }
            refresh()
        }
    }

    /** Work mailbox: which service, address, app password, then one [연결하기] that checks before saving. */
    private var mailService="네이버"
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

    private fun mailGroup(s:JSONObject) {
        val user=s.optString(PipelineSettingsKeys.MAIL_USER)
        if(user.isNotBlank() && s.optString(PipelineSettingsKeys.MAIL_PASSWORD).isNotBlank()) {
            val error=s.optString(PipelineSettingsKeys.MAIL_ERROR)
            body.addView(CallUi.text(a,if(error.isBlank()) "연결됨 ✓  $user" else error,CallUi.BODY,true,if(error.isBlank()) CallUi.OK else CallUi.AMBER).apply { tag="mail_state" })
            body.addView(CallUi.text(a,"받은 메일 중 업무 메일을 30분마다 가져와 통화와 함께 정리해요.",CallUi.CAPTION,color=CallUi.SUBTLE))
            body.addView(button("메일 연결 끊기") { save { it.remove(PipelineSettingsKeys.MAIL_HOST);it.remove(PipelineSettingsKeys.MAIL_USER);it.remove(PipelineSettingsKeys.MAIL_PASSWORD);it.remove(PipelineSettingsKeys.MAIL_ERROR) } }.apply { tag="mail_disconnect" })
            return
        }
        body.addView(CallUi.text(a,"업무 메일을 연결하면 메일도 업체와 사람별로 함께 정리해요. 메일 서비스 설정에서 IMAP을 켜고 앱 비밀번호를 만들어 아래에 넣어 주세요.",CallUi.BODY).apply { tag="mail_state" })
        val choices=MessageIntake.MAIL_HOSTS.keys.toList()+"직접 입력"
        // Two rows of two, so every name stays on one line at any width and letter size.
        val row=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val lines=List((choices.size+1)/2) { LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL }.also { l -> row.addView(l,LinearLayout.LayoutParams(-1,-2).apply { if(it>0) topMargin=CallUi.dp(a,6) }) } }
        fun field(hint:String,password:Boolean=false)=EditText(a).apply {
            this.hint=hint;textSize=CallUi.BODY;isSingleLine=true;minHeight=CallUi.dp(a,56);importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            inputType=if(password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            typeface=CallUi.font(a,CallUi.Weight.REGULAR)
        }
        val host=field("IMAP 서버 (예: imap.회사.com)").apply { tag="mail_host";visibility=if(mailService=="직접 입력") android.view.View.VISIBLE else android.view.View.GONE }
        choices.forEachIndexed { i,name ->
            val on=name==mailService
            lines[i/2].addView(Button(a).apply {
                text=name;textSize=16f;isAllCaps=false;stateListAnimator=null;minHeight=CallUi.dp(a,48);minimumHeight=minHeight;setPadding(0,0,0,0);maxLines=1
                typeface=CallUi.font(a,if(on) CallUi.Weight.SEMIBOLD else CallUi.Weight.MEDIUM)
                setTextColor(if(on) CallUi.ON_ACCENT else CallUi.SUBTLE);background=CallUi.ripple(a,CallUi.sheet(a,if(on) CallUi.ACCENT else CallUi.CHIP,radius=14))
                setOnClickListener { mailService=name;refresh() }
            },LinearLayout.LayoutParams(0,-2,1f).apply { if(i%2==0) marginEnd=CallUi.dp(a,6) })
        }
        if(choices.size%2==1) lines.last().addView(android.view.View(a),LinearLayout.LayoutParams(0,1,1f).apply { marginStart=CallUi.dp(a,0) })
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,10) })
        body.addView(CallUi.caption(a,mailGuide(mailService),CallUi.SUBTLE).apply { tag="mail_guide";setPadding(0,CallUi.dp(a,8),0,0) })
        body.addView(host)
        val address=field("메일 주소").apply { tag="mail_user" }
        val password=field("앱 비밀번호",true).apply { tag="mail_password" }
        body.addView(address);body.addView(password)
        body.addView(CallUi.bigButton(a,"","연결하기") {
            if(busy)return@bigButton
            val h=if(mailService=="직접 입력") host.text.toString().trim() else MessageIntake.MAIL_HOSTS.getValue(mailService)
            val u=address.text.toString().trim();val p=password.text.toString()
            if(h.isEmpty()||u.isEmpty()||p.isEmpty()) { Toast.makeText(a,"서버, 메일 주소, 앱 비밀번호를 모두 넣어 주세요.",Toast.LENGTH_LONG).show();return@bigButton }
            busy=true;Toast.makeText(a,"메일함에 연결해 보는 중이에요.",Toast.LENGTH_SHORT).show()
            a.lifecycleScope.launch {
                val problem=withContext(Dispatchers.IO) { MessageIntake.checkMail(h,u,p) }
                busy=false
                if(problem!=null) { Toast.makeText(a,problem,Toast.LENGTH_LONG).show();return@launch }
                password.text.clear()
                save { it.put(PipelineSettingsKeys.MAIL_HOST,h).put(PipelineSettingsKeys.MAIL_USER,u).put(PipelineSettingsKeys.MAIL_PASSWORD,p).remove(PipelineSettingsKeys.MAIL_ERROR) }
                Toast.makeText(a,"메일을 연결했어요.",Toast.LENGTH_SHORT).show()
            }
        }.apply { tag="mail_connect" })
    }

    private fun hourWords(h:Int)=when { h==0 -> "밤 12시";h<12 -> "오전 ${h}시";h==12 -> "낮 12시";else -> "오후 ${h-12}시" }

    /** One alert time: name on the left, the time large on the right, a slider below over a sensible range.
     * Saved when the finger lifts, so dragging does not reschedule on every step. */
    private fun hourRow(name:String,key:String,hour:Int,range:IntRange) {
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(0,CallUi.dp(a,6),0,CallUi.dp(a,10)) }
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(CallUi.text(a,name,CallUi.BODY),LinearLayout.LayoutParams(0,-2,1f))
        val value=CallUi.text(a,hourWords(hour),CallUi.HEADING,true,CallUi.ACCENT).apply { tag="hour_$key" }
        head.addView(value)
        box.addView(head)
        box.addView(SeekBar(a).apply {
            min=range.first;max=range.last;progress=hour.coerceIn(range);contentDescription="$name 시각 고르기"
            progressTintList=android.content.res.ColorStateList.valueOf(CallUi.ACCENT);thumbTintList=progressTintList
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(CallUi.MUTED_BAR)
            minimumHeight=CallUi.dp(a,48);setPadding(CallUi.dp(a,8),0,CallUi.dp(a,8),0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar,p:Int,fromUser:Boolean) { value.text=hourWords(p);if(android.os.Build.VERSION.SDK_INT>=30) s.stateDescription=hourWords(p) }
                override fun onStartTrackingTouch(s:SeekBar) {}
                override fun onStopTrackingTouch(s:SeekBar) { if(s.progress!=hour) save(true) { it.put(key,s.progress) } }
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
                            withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.forgetPersonal(key) } } }
                            refresh()
                        }
                    },secondary="닫기")
            },secondary="닫기")
        }.apply { tag="personal_list" })
    }

}
