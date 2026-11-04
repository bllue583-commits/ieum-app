package kr.callbrief.pipeline

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import org.json.JSONObject

/** "설정": Claude key, consent, voice-model download, notification hours and a plain status. The key is never shown again. */
class CallSettingsActivity:ComponentActivity() {
    private lateinit var page:LinearLayout
    /** The sheet the current settings group is written into. */
    private lateinit var body:LinearLayout
    private var busy=false
    /** When the oldest call still waiting for its summary was stored (for the status lights). */
    @Volatile private var oldestWaiting:Long?=null
    private val pickFolder=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode==RESULT_OK) CallFolder.save(this,r.data)?.let { Toast.makeText(this,it,Toast.LENGTH_LONG).show() }
        refresh()
    }
    private val askSms=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> ReadingPermission.noteResult(this,android.Manifest.permission.READ_SMS,ok);refresh() }
    private val askCallLog=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> ReadingPermission.noteResult(this,android.Manifest.permission.READ_CALL_LOG,ok);refresh() }
    private fun settings()=SecureSettings(CallPipelineStartup.storage(this))

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        page=CallUi.page(this,CallUi.Tab.SETTINGS);body=page
    }
    override fun onResume() { super.onResume();refresh() }

    /** Starts a settings group: its name above, a white sheet below that the following views go into. */
    private fun group(heading:TextView) {
        page.addView(CallUi.section(this,heading.text.toString().substringAfter(". ")))
        body=CallUi.card(this);page.addView(body)
    }
    private fun button(value:String,action:()->Unit)=CallUi.button(this,value) { if(!busy)action() }

    private fun refresh() {
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                val s=runCatching { settings().snapshot() }.getOrDefault(JSONObject())
                val counts=runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.counts() } }.getOrDefault(emptyMap())
                val memo=runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.siteMemo() } }.getOrNull()
                oldestWaiting=runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.oldestWaitingMs() } }.getOrNull()
                Triple(s,counts,memo)
            } }
            if(isFinishing||isDestroyed)return@launch
            result.onSuccess { (s,c,m) -> render(s,c,m) }
        }
    }

    /** Runs a settings write off the main thread, then refreshes. */
    private fun save(hoursChanged:Boolean=false,block:(JSONObject)->Unit) {
        busy=true
        lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching { settings().update(block) }.isSuccess }
            busy=false
            if(!ok) Toast.makeText(this@CallSettingsActivity,"설정을 저장하지 못했어요.",Toast.LENGTH_LONG).show()
            else CallPipelineStartup.settingsChanged(this@CallSettingsActivity,hoursChanged)
            refresh()
        }
    }

    private fun render(s:JSONObject,counts:Map<String,Int>,siteMemo:String?=null) {
        page.removeAllViews();body=page
        CallUi.band(page,"설정","처음 설정한 것도 여기서 언제든 바꿀 수 있어요")
        statusLights(s,counts)

        group(CallUi.heading(this,"녹음 폴더"))
        val folder=s.optString("treeUri").isNotBlank()
        body.addView(CallUi.text(this,if(folder) "연결됨 ✓  새 통화를 자동으로 가져와요." else "아직 연결하지 않았어요. 통화 녹음 폴더를 골라 주세요.",CallUi.BODY,true,if(folder) CallUi.OK else CallUi.AMBER).apply { tag="folder_state" })
        body.addView(button(if(folder) "폴더 바꾸기" else "녹음 폴더 고르기") { pickFolder.launch(CallFolder.pickIntent()) }.apply { tag="folder_pick" })

        group(CallUi.heading(this,"문자"))
        val sms=checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED
        body.addView(CallUi.text(this,if(sms) "허용됨 ✓  업무 문자도 통화와 함께 정리해요." else "문자를 읽도록 허용하면 업무 문자도 통화와 함께 정리해요.",CallUi.BODY,sms,if(sms) CallUi.OK else CallUi.INK).apply { tag="sms_state" })
        if(!sms) readingButton(android.Manifest.permission.READ_SMS,"문자",askSms,"sms_allow")

        group(CallUi.heading(this,"통화기록"))
        val log=checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)==android.content.pm.PackageManager.PERMISSION_GRANTED
        body.addView(CallUi.text(this,if(log) "허용됨 ✓  녹음과 통화기록을 맞춰 상대 번호를 찾아요." else "통화기록을 읽도록 허용하면 녹음과 맞춰 상대 번호와 녹음 없는 통화를 찾아요.",CallUi.BODY,log,if(log) CallUi.OK else CallUi.INK).apply { tag="call_log_state" })
        if(!log) readingButton(android.Manifest.permission.READ_CALL_LOG,"통화기록",askCallLog,"call_log_allow")

        group(CallUi.heading(this,"메일"))
        mailGroup(s)

        group(CallUi.heading(this,"정리 동의"))
        body.addView(CallUi.text(this,"통화 녹음 파일은 휴대폰 밖으로 나가지 않아요. 휴대폰에서 글자로 바꾼 통화 내용과 업무 문자, 메일의 글만 Claude로 보내 요약하고, 결과는 이 휴대폰에만 저장해요. 언제든 끌 수 있어요."))
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        body.addView(Switch(this).apply {
            text=if(consent) "켜짐: 통화 내용을 정리해요" else "꺼짐: 정리하지 않아요";textSize=CallUi.SUMMARY;isChecked=consent
            minHeight=CallUi.dp(this@CallSettingsActivity,64);tag="consent_switch";setTextColor(CallUi.INK)
            setOnCheckedChangeListener { _,checked -> if(checked!=consent) save { it.put(PipelineSettingsKeys.CONSENT_GRANTED,checked) } }
        })

        group(CallUi.heading(this,"음성 인식 모델"))
        val modelState=s.optString(PipelineSettingsKeys.MODEL_STATE)
        body.addView(CallUi.text(this,when(modelState) {
            "READY" -> "준비됨 ✓";"DOWNLOADING" -> "받는 중이에요 (Wi-Fi 연결 필요, 약 570MB)";"FAILED" -> "받지 못했어요. 다시 받아주세요."
            "UNSUPPORTED" -> "이 휴대폰에서는 사용할 수 없어요.";else -> "아직 받지 않았어요 (Wi-Fi에서 약 570MB)"
        },CallUi.BODY,true,if(modelState=="READY") CallUi.OK else CallUi.INK))
        if(modelState!="READY"&&modelState!="UNSUPPORTED") body.addView(button(if(modelState=="DOWNLOADING") "다시 확인하기" else "모델 받기") {
            PipelineModelDownloadWork.enqueue(this);Toast.makeText(this,"Wi-Fi에 연결되면 받기 시작해요.",Toast.LENGTH_LONG).show()
        })

        if(kr.callbrief.BuildConfig.SYNTHETIC_TOOLS_ENABLED&&!CallInboxConfig.enabled&&modelState=="READY") body.addView(button("속도 측정 (시험용)") { benchmark() }.apply { tag="benchmark" })

        group(CallUi.heading(this,"알림 시각"))
        hourRow("저녁 통화 정리",PipelineSettingsKeys.EVENING_HOUR,s.optInt(PipelineSettingsKeys.EVENING_HOUR,19),15..23)
        body.addView(CallUi.divider(this))
        hourRow("아침 체크할 일",PipelineSettingsKeys.MORNING_HOUR,s.optInt(PipelineSettingsKeys.MORNING_HOUR,6),4..11)

        group(CallUi.heading(this,"현장 상황 메모"))
        body.addView(CallUi.text(this,siteMemo ?: "첫 통화들에서 자동으로 채워져요",CallUi.BODY,color=if(siteMemo==null) CallUi.SUBTLE else CallUi.INK).apply { tag="site_memo" })

        group(CallUi.heading(this,"앱 정보와 처리 현황"))
        // Installed version, and the next one when it is downloaded and waiting (design: 보고와 화면).
        body.addView(CallUi.text(this,"앱 버전 v${kr.callbrief.BuildConfig.VERSION_CODE}"+(CallUpdate.ready(this)?.let { ", 새 버전 v${it.versionCode} 준비됨" } ?: ""),CallUi.BODY,true).apply { tag="app_version" })
        val speed=s.optLong(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,0)
        body.addView(CallUi.text(this,"녹음 1분을 글자로 바꾸는 데: "+if(speed>0) "약 %.0f초".format(speed/1000.0) else "아직 기록 없음"))
        body.addView(CallUi.text(this,"마지막으로 글자로 바꾼 때: ${CallUi.dateTime(s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0))}"))
        body.addView(CallUi.text(this,"마지막으로 정리한 때: ${CallUi.dateTime(s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0))}"))
        val waiting=(counts[CallRecordStore.TRANSCRIBED] ?: 0)+(counts[CallRecordStore.WAITING_CONSENT] ?: 0)
        val failed=counts[CallRecordStore.FAILED] ?: 0
        body.addView(CallUi.text(this,"정리된 통화 ${counts[CallRecordStore.ANALYZED] ?: 0}건, 기다리는 통화 ${waiting}건, 정리 못 한 통화 ${failed}건",CallUi.BODY,true))
        if(waiting+failed>0) body.addView(button("기다리는 통화 다시 정리하기") {
            busy=true
            lifecycleScope.launch {
                val n=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.requeueAll() } }.getOrDefault(0) }
                busy=false;CallPipelineStartup.settingsChanged(this@CallSettingsActivity)
                Toast.makeText(this@CallSettingsActivity,"${n}건을 다시 정리할게요.",Toast.LENGTH_SHORT).show();refresh()
            }
        }.apply { tag="reanalyze_all" })
    }

    private fun waitWords(ms:Long):String { val m=(ms/60_000).coerceAtLeast(0);return if(m<60) "${m}분" else "${m/60}시간"+(if(m%60>0) " ${m%60}분" else "") }
    /** "지금 상태": three lights at the top (green or amber, never red): calls waiting for their summary, how long the oldest has waited,
     * and when the last result came back. A call waiting over three hours, or no result for six hours while some wait, turns a light amber. */
    private fun statusLights(s:JSONObject,counts:Map<String,Int>) {
        val now=System.currentTimeMillis()
        val waiting=(counts[CallRecordStore.TRANSCRIBED] ?: 0)+(counts[CallRecordStore.WAITING_CONSENT] ?: 0)
        val age=oldestWaiting?.let { now-it }
        val last=s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0)
        val late=waiting>0 && ((age ?: 0)>3*3_600_000L)
        page.addView(CallUi.section(this,"지금 상태"))
        val card=CallUi.card(this).apply { tag="settings_status" }
        card.addView(CallUi.signalRow(this,"정리를 기다리는 통화",if(waiting==0) "없어요" else "${waiting}건",!late).apply { tag="status_waiting" })
        card.addView(CallUi.signalRow(this,"가장 오래 기다린 통화",if(waiting==0 || age==null) "없어요" else waitWords(age),!late).apply { tag="status_oldest" })
        card.addView(CallUi.signalRow(this,"마지막으로 결과를 받은 때",if(last>0) CallUi.dateTime(last) else "아직 없어요",waiting==0 || (last>0 && now-last<6*3_600_000L)).apply { tag="status_last_result" })
        if(late) card.addView(CallUi.caption(this,"정리가 늦어지고 있어요. 순서대로 정리하고 있으니 기다려 주세요.").apply { tag="status_late_note";setPadding(0,CallUi.dp(this@CallSettingsActivity,8),0,0) })
        page.addView(card)
    }

    /** Test builds only: transcribe the bundled public Korean sample (repeated to about one minute)
     * with the large model and record the speed. No call recording or network is needed. */
    private fun benchmark() {
        busy=true;Toast.makeText(this,"속도를 재는 중이에요. 1~3분 걸릴 수 있어요.",Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val storage=CallPipelineStartup.storage(this@CallSettingsActivity);val model=PipelineModelManager(storage)
                val file=checkNotNull(model.verifiedModelFile()) { "MODEL_NOT_READY" }
                val one=PipelineAudioMath.decodeWav(assets.open("local-stt/public-ko.wav").use{it.readBytes()}).chunks.flatMap{it.second.asList()}
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
                android.app.AlertDialog.Builder(this@CallSettingsActivity).setTitle("속도 측정 결과")
                    .setMessage("녹음 ${audioMs/1000}초를 처리했어요.\n녹음 1분당 약 ${perMinute/1000}초 걸려요.\n\n인식된 앞부분: $text").setPositiveButton("확인",null).show()
            }.onFailure { Toast.makeText(this@CallSettingsActivity,if(it.message=="MODEL_NOT_READY") "먼저 음성 인식 모델을 받아주세요." else "측정하지 못했어요.",Toast.LENGTH_LONG).show() }
            refresh()
        }
    }

    /** Work mailbox: which service, address, app password, then one [연결하기] that checks before saving. */
    private var mailService="네이버"
    /** Ask; once asked and still refused, explain the restricted setting and open App info instead. */
    private fun readingButton(permission:String,what:String,ask:androidx.activity.result.ActivityResultLauncher<String>,tag:String) {
        if(ReadingPermission.blocked(this,permission)) {
            body.addView(CallUi.text(this,ReadingPermission.restrictedHelp(what),CallUi.CAPTION,color=CallUi.SUBTLE))
            body.addView(button("앱 정보 열기") { ReadingPermission.openAppInfo(this) }.apply { this.tag=tag })
        } else body.addView(button("$what 읽기 허용") { ReadingPermission.markAsked(this,permission);ask.launch(permission) }.apply { this.tag=tag })
    }

    private fun mailGroup(s:JSONObject) {
        val user=s.optString(PipelineSettingsKeys.MAIL_USER)
        if(user.isNotBlank() && s.optString(PipelineSettingsKeys.MAIL_PASSWORD).isNotBlank()) {
            val error=s.optString(PipelineSettingsKeys.MAIL_ERROR)
            body.addView(CallUi.text(this,if(error.isBlank()) "연결됨 ✓  $user" else error,CallUi.BODY,true,if(error.isBlank()) CallUi.OK else CallUi.AMBER).apply { tag="mail_state" })
            body.addView(CallUi.text(this,"받은 메일 중 업무 메일을 30분마다 가져와 통화와 함께 정리해요.",CallUi.CAPTION,color=CallUi.SUBTLE))
            body.addView(button("메일 연결 끊기") { save { it.remove(PipelineSettingsKeys.MAIL_HOST);it.remove(PipelineSettingsKeys.MAIL_USER);it.remove(PipelineSettingsKeys.MAIL_PASSWORD);it.remove(PipelineSettingsKeys.MAIL_ERROR) } }.apply { tag="mail_disconnect" })
            return
        }
        body.addView(CallUi.text(this,"업무 메일을 연결하면 메일도 업체와 사람별로 함께 정리해요. 메일 서비스 설정에서 IMAP을 켜고 앱 비밀번호를 만들어 아래에 넣어 주세요.",CallUi.BODY).apply { tag="mail_state" })
        val choices=MessageIntake.MAIL_HOSTS.keys.toList()+"직접 입력"
        // Two rows of two, so every name stays on one line at any width and letter size.
        val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val lines=List((choices.size+1)/2) { LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }.also { l -> row.addView(l,LinearLayout.LayoutParams(-1,-2).apply { if(it>0) topMargin=CallUi.dp(this@CallSettingsActivity,6) }) } }
        fun field(hint:String,password:Boolean=false)=EditText(this).apply {
            this.hint=hint;textSize=CallUi.BODY;isSingleLine=true;minHeight=CallUi.dp(this@CallSettingsActivity,56);importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            inputType=if(password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            typeface=CallUi.font(this@CallSettingsActivity,CallUi.Weight.REGULAR)
        }
        val host=field("IMAP 서버 (예: imap.회사.com)").apply { tag="mail_host";visibility=if(mailService=="직접 입력") android.view.View.VISIBLE else android.view.View.GONE }
        choices.forEachIndexed { i,name ->
            val on=name==mailService
            lines[i/2].addView(Button(this).apply {
                text=name;textSize=16f;isAllCaps=false;stateListAnimator=null;minHeight=CallUi.dp(this@CallSettingsActivity,48);minimumHeight=minHeight;setPadding(0,0,0,0);maxLines=1
                typeface=CallUi.font(this@CallSettingsActivity,if(on) CallUi.Weight.SEMIBOLD else CallUi.Weight.MEDIUM)
                setTextColor(if(on) CallUi.ON_ACCENT else CallUi.SUBTLE);background=CallUi.ripple(this@CallSettingsActivity,CallUi.sheet(this@CallSettingsActivity,if(on) CallUi.ACCENT else CallUi.CHIP,radius=14))
                setOnClickListener { mailService=name;refresh() }
            },LinearLayout.LayoutParams(0,-2,1f).apply { if(i%2==0) marginEnd=CallUi.dp(this@CallSettingsActivity,6) })
        }
        if(choices.size%2==1) lines.last().addView(android.view.View(this),LinearLayout.LayoutParams(0,1,1f).apply { marginStart=CallUi.dp(this@CallSettingsActivity,0) })
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(this@CallSettingsActivity,10) })
        body.addView(host)
        val address=field("메일 주소").apply { tag="mail_user" }
        val password=field("앱 비밀번호",true).apply { tag="mail_password" }
        body.addView(address);body.addView(password)
        body.addView(CallUi.bigButton(this,"","연결하기") {
            if(busy)return@bigButton
            val h=if(mailService=="직접 입력") host.text.toString().trim() else MessageIntake.MAIL_HOSTS.getValue(mailService)
            val u=address.text.toString().trim();val p=password.text.toString()
            if(h.isEmpty()||u.isEmpty()||p.isEmpty()) { Toast.makeText(this,"서버, 메일 주소, 앱 비밀번호를 모두 넣어 주세요.",Toast.LENGTH_LONG).show();return@bigButton }
            busy=true;Toast.makeText(this,"메일함에 연결해 보는 중이에요.",Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val problem=withContext(Dispatchers.IO) { MessageIntake.checkMail(h,u,p) }
                busy=false
                if(problem!=null) { Toast.makeText(this@CallSettingsActivity,problem,Toast.LENGTH_LONG).show();return@launch }
                password.text.clear()
                save { it.put(PipelineSettingsKeys.MAIL_HOST,h).put(PipelineSettingsKeys.MAIL_USER,u).put(PipelineSettingsKeys.MAIL_PASSWORD,p).remove(PipelineSettingsKeys.MAIL_ERROR) }
                Toast.makeText(this@CallSettingsActivity,"메일을 연결했어요.",Toast.LENGTH_SHORT).show()
            }
        }.apply { tag="mail_connect" })
    }

    private fun hourWords(h:Int)=when { h==0 -> "밤 12시";h<12 -> "오전 ${h}시";h==12 -> "낮 12시";else -> "오후 ${h-12}시" }

    /** One alert time: name on the left, the time large on the right, a slider below over a sensible range.
     * Saved when the finger lifts, so dragging does not reschedule on every step. */
    private fun hourRow(name:String,key:String,hour:Int,range:IntRange) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(0,CallUi.dp(this@CallSettingsActivity,6),0,CallUi.dp(this@CallSettingsActivity,10)) }
        val head=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(CallUi.text(this,name,CallUi.BODY),LinearLayout.LayoutParams(0,-2,1f))
        val value=CallUi.text(this,hourWords(hour),CallUi.HEADING,true,CallUi.ACCENT).apply { tag="hour_$key" }
        head.addView(value)
        box.addView(head)
        box.addView(SeekBar(this).apply {
            min=range.first;max=range.last;progress=hour.coerceIn(range);contentDescription="$name 시각 고르기"
            progressTintList=android.content.res.ColorStateList.valueOf(CallUi.ACCENT);thumbTintList=progressTintList
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(CallUi.MUTED_BAR)
            minimumHeight=CallUi.dp(this@CallSettingsActivity,48);setPadding(CallUi.dp(this@CallSettingsActivity,8),0,CallUi.dp(this@CallSettingsActivity,8),0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar,p:Int,fromUser:Boolean) { value.text=hourWords(p);if(android.os.Build.VERSION.SDK_INT>=30) s.stateDescription=hourWords(p) }
                override fun onStartTrackingTouch(s:SeekBar) {}
                override fun onStopTrackingTouch(s:SeekBar) { if(s.progress!=hour) save(true) { it.put(key,s.progress) } }
            })
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(this@CallSettingsActivity,8);marginStart=-CallUi.dp(this@CallSettingsActivity,8);marginEnd=-CallUi.dp(this@CallSettingsActivity,8) })
        val ends=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        ends.addView(CallUi.caption(this,hourWords(range.first),CallUi.MUTED),LinearLayout.LayoutParams(0,-2,1f))
        ends.addView(CallUi.caption(this,hourWords(range.last),CallUi.MUTED))
        box.addView(ends)
        body.addView(box)
    }
}
