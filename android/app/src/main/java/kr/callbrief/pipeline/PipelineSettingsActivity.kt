package kr.callbrief.pipeline

import android.widget.CheckBox
import kr.callbrief.SecureSettings
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Explicit user settings for the external AI pipeline. The token is write-only from the UI. */
internal object PipelineSettings {
    fun setConsent(settings:SecureSettings,granted:Boolean) = settings.update { s ->
        if(s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)!=granted || !s.has(PipelineSettingsKeys.CONSENT_EPOCH)) {
            s.put(PipelineSettingsKeys.CONSENT_GRANTED,granted)
            s.put(PipelineSettingsKeys.CONSENT_EPOCH,s.optLong(PipelineSettingsKeys.CONSENT_EPOCH,0)+1)
        }
    }
    fun saveToken(settings:SecureSettings,token:String) {
        val t=token.trim();require(t.length in 20..400 && t.none { it.isWhitespace() || it.code<33 || it.code>126 }) { "INVALID_TOKEN" }
        settings.update { it.put(PipelineSettingsKeys.INBOX_TOKEN,t) }
    }
    fun deleteToken(settings:SecureSettings)=settings.update { it.remove(PipelineSettingsKeys.INBOX_TOKEN) }
    private val time=DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"))
    private fun at(s:JSONObject,key:String)=s.optLong(key,0).takeIf { it>0 }?.let { time.format(Instant.ofEpochMilli(it)) } ?: "아직 없음"
    /** Status lines. Never includes the token itself. */
    fun status(s:JSONObject):List<String> = listOf(
        "외부 AI 분석: "+if(s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false))"동의함" else "동의하지 않음",
        "받은편지함 토큰: "+if(s.optString(PipelineSettingsKeys.INBOX_TOKEN).isNotBlank())"저장됨" else "없음",
        "자막 모델: "+when(s.optString(PipelineSettingsKeys.MODEL_STATE)) { "READY"->"준비됨";"DOWNLOADING"->"내려받는 중";"FAILED"->"내려받기 실패";"UNSUPPORTED"->"이 기기에서 지원하지 않음";else->"아직 내려받지 않음" },
        "마지막 자막 생성: "+at(s,PipelineSettingsKeys.LAST_TRANSCRIBED_MS),
        "마지막 업로드: "+at(s,PipelineSettingsKeys.LAST_UPLOAD_MS),
        "마지막 결과 확인: "+at(s,PipelineSettingsKeys.LAST_FETCH_MS)+statusText(s.optString("pipelineLastFetchStatus")))
    private fun statusText(code:String)=when(code) {
        ""->"";"OK"->" · 정상";"SERVER_KEYS_MISSING"->" · 서버 설정 전";"INBOX_TOKEN_MISSING"->" · 토큰 필요";"DEVICE_NOT_READY"->" · 기기 준비 전"
        "CONSENT_NOT_GRANTED"->" · 동의 필요";"INBOX_UNAVAILABLE"->" · 연결 실패";else->" · 확인 필요" }
    const val EXPLANATION="동의하면 통화 녹음에서 만든 자막(글자)과 업무·할 일 목록을 암호화해 비공개 GitHub 저장소로 보내고, 그곳에서 외부 AI(Claude)가 할 일과 요약을 정리해요. 녹음 파일(음성)은 보내지 않아요. 제외한 통화는 보내지 않아요. 동의는 언제든 철회할 수 있고, 철회하면 이후 자막은 보내지 않고 받은 결과도 적용하지 않아요."
}

class PipelineSettingsActivity:PipelineScreenActivity() {
    private fun settings()=SecureSettings(storage)
    override fun refresh() {
        io("설정을 읽지 못했어요. 저장된 설정은 바꾸지 않았어요.",{ settings().snapshot() }) { render(it) }
    }
    private fun render(s:JSONObject) {
        body.removeAllViews();body.addView(label("AI 분석 설정",true))
        if(message.isNotBlank())body.addView(label(message).apply { tag="pipeline_settings_message" })
        body.addView(label(PipelineSettings.EXPLANATION))
        val consent=CheckBox(this).apply { text="외부 AI 분석 동의";textSize=18f;minHeight=dp(56);isChecked=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false);tag="pipeline_consent";isSaveEnabled=false }
        consent.setOnCheckedChangeListener { _,checked->
            if(busy)return@setOnCheckedChangeListener
            io("동의 상태를 저장하지 못했어요.",{ PipelineSettings.setConsent(settings(),checked) },action=true) { message=if(checked)"외부 AI 분석에 동의했어요." else "동의를 철회했어요. 이후 자막은 보내지 않아요.";refresh() }
        }
        body.addView(consent)
        body.addView(label("받은편지함 토큰",true))
        body.addView(label("ieum-inbox 저장소에만 쓸 수 있는 GitHub 토큰을 넣어 주세요. 저장한 토큰은 다시 보여주지 않아요."))
        val token=field("토큰 붙여넣기",secret=true).apply { tag="pipeline_token" };body.addView(token)
        body.addView(button("토큰 저장") {
            val value=token.text.toString();token.setText("")
            io("토큰 형식을 확인해 주세요. 저장하지 않았어요.",{ PipelineSettings.saveToken(settings(),value) },action=true) { message="토큰을 저장했어요.";refresh() }
        }.apply { tag="pipeline_token_save" })
        body.addView(button("토큰 삭제") { io("토큰을 지우지 못했어요.",{ PipelineSettings.deleteToken(settings()) },action=true) { message="토큰을 지웠어요.";refresh() } }.apply { tag="pipeline_token_delete" })
        body.addView(label("상태",true))
        PipelineSettings.status(s).forEach { body.addView(label(it)) }
        body.addView(button("지금 결과 확인") {
            io("결과를 확인하지 못했어요.",{ PipelineFetchWork.runOnce(this) },action=true) { message="결과 확인을 마쳤어요.";refresh() }
        }.apply { tag="pipeline_fetch_now" })
        body.addView(button("돌아가기") { finish() })
    }
}
