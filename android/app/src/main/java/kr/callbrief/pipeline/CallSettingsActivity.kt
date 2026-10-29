package kr.callbrief.pipeline

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import org.json.JSONObject

/** "통화 정리 설정": Claude API key, consent, digest hours and pipeline status. The key is never shown again. */
class CallSettingsActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var busy=false
    private fun settings()=SecureSettings(CallPipelineStartup.storage(this))

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll=ScrollView(this);body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            val p=dp(16);view.setPadding(p,insets.systemWindowInsetTop+p,p,insets.systemWindowInsetBottom+p);insets
        }
        scroll.addView(body);setContentView(scroll)
    }
    override fun onResume() { super.onResume();refresh() }

    private fun dp(v:Int)=(v*resources.displayMetrics.density+0.5f).toInt()
    private fun label(value:String,size:Float=18f,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value;textSize=18f;isAllCaps=false;minHeight=dp(56);setOnClickListener { if(!busy)action() } }
    private fun time(ms:Long)=if(ms<=0) "없음" else CallRecordStore.kstTime(ms)

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
        body.addView(label("통화 정리 설정",26f,true))

        body.addView(label("Claude API 키",22f,true))
        val hasKey=s.optString(PipelineSettingsKeys.CLAUDE_API_KEY).isNotBlank()
        if(hasKey) {
            body.addView(label("저장됨").apply { tag="api_key_state" })
            body.addView(button("키 삭제") { save { it.remove(PipelineSettingsKeys.CLAUDE_API_KEY) } }.apply { tag="api_key_delete" })
        } else {
            body.addView(label("Anthropic 콘솔에서 만든 API 키를 붙여 넣으세요. 키는 이 휴대폰에 암호화해 저장하고 다시 보여 주지 않아요.").apply { tag="api_key_state" })
            val input=EditText(this).apply {
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;textSize=18f;hint="sk-ant-...";tag="api_key_input"
                isSingleLine=true;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            body.addView(input)
            body.addView(button("키 저장") {
                val key=input.text.toString().trim();if(key.isEmpty())return@button
                input.text.clear();save { it.put(PipelineSettingsKeys.CLAUDE_API_KEY,key) }
            }.apply { tag="api_key_save" })
        }

        body.addView(label("자막 보내기 동의",22f,true))
        body.addView(label("통화 녹음 자체는 휴대폰 밖으로 나가지 않고, 휴대폰에서 만든 자막 텍스트만 Claude로 보내 요약합니다. 요약·약속·할 일은 이 휴대폰에만 저장해요. 언제든 끌 수 있습니다."))
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        body.addView(Switch(this).apply {
            text="자막 텍스트를 Claude로 보내 요약하기";textSize=18f;isChecked=consent;minHeight=dp(56);tag="consent_switch"
            setOnCheckedChangeListener { _,checked -> if(checked!=consent) save { it.put(PipelineSettingsKeys.CONSENT_GRANTED,checked) } }
        })

        body.addView(label("알림 시각",22f,true))
        hourRow("저녁 정리",PipelineSettingsKeys.EVENING_HOUR,s.optInt(PipelineSettingsKeys.EVENING_HOUR,19))
        hourRow("아침 할 일",PipelineSettingsKeys.MORNING_HOUR,s.optInt(PipelineSettingsKeys.MORNING_HOUR,6))

        body.addView(label("상태",22f,true))
        val model=when(s.optString(PipelineSettingsKeys.MODEL_STATE)) {
            "READY" -> "준비됨";"DOWNLOADING" -> "내려받는 중";"FAILED" -> "실패";"UNSUPPORTED" -> "이 기기에서 지원 안 됨";else -> "아직 내려받지 않음"
        }
        body.addView(label("음성 인식 모델: $model"))
        val speed=s.optLong(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,0)
        body.addView(label("녹음 1분당 처리 시간: "+if(speed>0) "%.1f초".format(speed/1000.0) else "아직 없음"))
        body.addView(label("마지막 자막 만들기: ${time(s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0))}"))
        body.addView(label("마지막 분석: ${time(s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0))}"))
        val waiting=(counts[CallRecordStore.TRANSCRIBED] ?: 0)+(counts[CallRecordStore.WAITING_KEY] ?: 0)+(counts[CallRecordStore.WAITING_CONSENT] ?: 0)
        body.addView(label("분석 완료 ${counts[CallRecordStore.ANALYZED] ?: 0}건 · 대기 ${waiting}건 · 실패 ${counts[CallRecordStore.FAILED] ?: 0}건"))
        body.addView(button("모두 다시 분석") {
            busy=true
            lifecycleScope.launch {
                val n=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallSettingsActivity)).use { it.requeueAll() } }.getOrDefault(0) }
                busy=false;CallPipelineStartup.settingsChanged(this@CallSettingsActivity)
                Toast.makeText(this@CallSettingsActivity,"${n}건을 다시 분석해요.",Toast.LENGTH_SHORT).show();refresh()
            }
        }.apply { tag="reanalyze_all" })
    }

    private fun hourRow(name:String,key:String,hour:Int) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL }
        row.addView(label("$name: ${hour}시"),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("−") { save(true) { it.put(key,(hour+23)%24) } }.apply { contentDescription="$name 한 시간 앞당기기" },LinearLayout.LayoutParams(dp(64),-2))
        row.addView(button("+") { save(true) { it.put(key,(hour+1)%24) } }.apply { contentDescription="$name 한 시간 늦추기" },LinearLayout.LayoutParams(dp(64),-2))
        body.addView(row)
    }
}
