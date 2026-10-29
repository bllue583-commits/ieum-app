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
    private lateinit var body:LinearLayout
    private var busy=false
    private fun settings()=SecureSettings(CallPipelineStartup.storage(this))

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        body=CallUi.page(this)
    }
    override fun onResume() { super.onResume();refresh() }

    private fun button(value:String,action:()->Unit)=CallUi.button(this,value) { if(!busy)action() }

    private fun refresh() {
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                val s=runCatching { settings().snapshot() }.getOrDefault(JSONObject())
                val counts=runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.counts() } }.getOrDefault(emptyMap())
                s to counts
            } }
            if(isFinishing||isDestroyed)return@launch
            result.onSuccess { (s,c) -> render(s,c) }
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

    private fun render(s:JSONObject,counts:Map<String,Int>) {
        body.removeAllViews()
        body.addView(CallUi.title(this,"설정"))

        body.addView(CallUi.heading(this,"1. Claude 키"))
        val hasKey=s.optString(PipelineSettingsKeys.CLAUDE_API_KEY).isNotBlank()
        if(hasKey) {
            body.addView(CallUi.text(this,"저장됨 ✓  (보안을 위해 다시 보여주지 않아요)",CallUi.BODY,true,CallUi.OK).apply { tag="api_key_state" })
            body.addView(button("키 지우기") { save { it.remove(PipelineSettingsKeys.CLAUDE_API_KEY) } }.apply { tag="api_key_delete" })
        } else {
            body.addView(CallUi.text(this,"통화를 요약하는 데 쓰는 키예요. Claude 콘솔(platform.claude.com)에서 만든 키를 아래 칸에 붙여 넣고 [키 저장]을 누르세요. 이 휴대폰에만 암호화해 저장해요.").apply { tag="api_key_state" })
            val input=EditText(this).apply {
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;textSize=CallUi.BODY;hint="여기에 키 붙여넣기";tag="api_key_input"
                isSingleLine=true;minHeight=CallUi.dp(this@CallSettingsActivity,60);importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            body.addView(input)
            body.addView(CallUi.bigButton(this,"","키 저장") {
                if(busy)return@bigButton
                val key=input.text.toString().trim();if(key.isEmpty())return@bigButton
                input.text.clear();save { it.put(PipelineSettingsKeys.CLAUDE_API_KEY,key) }
            }.apply { tag="api_key_save" })
        }

        body.addView(CallUi.heading(this,"2. 정리 동의"))
        body.addView(CallUi.text(this,"통화 녹음 파일은 휴대폰 밖으로 나가지 않아요. 휴대폰에서 글자로 바꾼 통화 내용만 Claude로 보내 요약하고, 결과는 이 휴대폰에만 저장해요. 언제든 끌 수 있어요."))
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        body.addView(Switch(this).apply {
            text=if(consent) "켜짐: 통화 내용을 정리해요" else "꺼짐: 정리하지 않아요";textSize=CallUi.SUMMARY;isChecked=consent
            minHeight=CallUi.dp(this@CallSettingsActivity,64);tag="consent_switch";setTextColor(CallUi.INK)
            setOnCheckedChangeListener { _,checked -> if(checked!=consent) save { it.put(PipelineSettingsKeys.CONSENT_GRANTED,checked) } }
        })

        body.addView(CallUi.heading(this,"3. 음성 인식 모델"))
        val modelState=s.optString(PipelineSettingsKeys.MODEL_STATE)
        body.addView(CallUi.text(this,when(modelState) {
            "READY" -> "준비됨 ✓";"DOWNLOADING" -> "받는 중이에요 (Wi-Fi 연결 필요, 약 570MB)";"FAILED" -> "받지 못했어요. 다시 받아주세요."
            "UNSUPPORTED" -> "이 휴대폰에서는 사용할 수 없어요.";else -> "아직 받지 않았어요 (Wi-Fi에서 약 570MB)"
        },CallUi.BODY,true,if(modelState=="READY") CallUi.OK else CallUi.INK))
        if(modelState!="READY"&&modelState!="UNSUPPORTED") body.addView(button(if(modelState=="DOWNLOADING") "다시 확인하기" else "모델 받기") {
            PipelineModelDownloadWork.enqueue(this);Toast.makeText(this,"Wi-Fi에 연결되면 받기 시작해요.",Toast.LENGTH_LONG).show()
        })

        if(kr.callbrief.BuildConfig.SYNTHETIC_TOOLS_ENABLED&&modelState=="READY") body.addView(button("속도 측정 (시험용)") { benchmark() }.apply { tag="benchmark" })

        body.addView(CallUi.heading(this,"4. 알림 시각"))
        hourRow("저녁 정리 알림",PipelineSettingsKeys.EVENING_HOUR,s.optInt(PipelineSettingsKeys.EVENING_HOUR,19))
        hourRow("아침 할 일 알림",PipelineSettingsKeys.MORNING_HOUR,s.optInt(PipelineSettingsKeys.MORNING_HOUR,6))

        body.addView(CallUi.heading(this,"지금 상태"))
        val speed=s.optLong(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,0)
        body.addView(CallUi.text(this,"녹음 1분을 글자로 바꾸는 데: "+if(speed>0) "약 %.0f초".format(speed/1000.0) else "아직 기록 없음"))
        body.addView(CallUi.text(this,"마지막으로 글자로 바꾼 때: ${CallUi.dateTime(s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0))}"))
        body.addView(CallUi.text(this,"마지막으로 정리한 때: ${CallUi.dateTime(s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0))}"))
        val waiting=(counts[CallRecordStore.TRANSCRIBED] ?: 0)+(counts[CallRecordStore.WAITING_KEY] ?: 0)+(counts[CallRecordStore.WAITING_CONSENT] ?: 0)
        val failed=counts[CallRecordStore.FAILED] ?: 0
        body.addView(CallUi.text(this,"정리된 통화 ${counts[CallRecordStore.ANALYZED] ?: 0}건 · 기다리는 통화 ${waiting}건 · 정리 못 한 통화 ${failed}건",CallUi.BODY,true))
        if(waiting+failed>0) body.addView(button("기다리는 통화 다시 정리하기") {
            busy=true
            lifecycleScope.launch {
                val n=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.requeueAll() } }.getOrDefault(0) }
                busy=false;CallPipelineStartup.settingsChanged(this@CallSettingsActivity)
                Toast.makeText(this@CallSettingsActivity,"${n}건을 다시 정리할게요.",Toast.LENGTH_SHORT).show();refresh()
            }
        }.apply { tag="reanalyze_all" })
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

    private fun hourRow(name:String,key:String,hour:Int) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val words=when { hour==0 -> "밤 12시";hour<12 -> "오전 ${hour}시";hour==12 -> "낮 12시";else -> "오후 ${hour-12}시" }
        row.addView(CallUi.text(this,"$name\n$words",CallUi.BODY,true),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("−1시간") { save(true) { it.put(key,(hour+23)%24) } }.apply { contentDescription="$name 한 시간 앞당기기" },LinearLayout.LayoutParams(CallUi.dp(this,110),-2).apply { marginEnd=CallUi.dp(this@CallSettingsActivity,6) })
        row.addView(button("+1시간") { save(true) { it.put(key,(hour+1)%24) } }.apply { contentDescription="$name 한 시간 늦추기" },LinearLayout.LayoutParams(CallUi.dp(this,110),-2))
        body.addView(row)
    }
}
