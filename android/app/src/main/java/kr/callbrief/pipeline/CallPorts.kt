package kr.callbrief.pipeline

/** Lean call pipeline (design revision 2026-10-29 evening):
 * recording -> on-device Whisper transcript -> Claude analysis on the phone -> stored summary/tasks
 * -> "오늘의 통화" screen, 19:00 evening digest, 06:00 morning tasks. Audio never leaves the phone.
 *
 * Seam between the transcription worker (producer) and the analysis/UI worker (consumer). */

/** One finished on-device transcript of one recording version. */
data class CallTranscript(
    val recordingId:String,
    val sourceVersion:Long,
    val audioSha256:String,
    /** Call start time; null when it could not be determined (never invented). */
    val originalStartedAtMs:Long?,
    val durationMs:Long,
    /** Counterpart as found in the recording name or contact (e.g. "홍길동" or "010-1234-5678"); null if unknown. */
    val counterpart:String?,
    val text:String,
    val segments:List<Segment>,
    val modelName:String,
    val modelSha256:String,
    val vadApplied:Boolean,
) {
    data class Segment(val startMs:Long,val endMs:Long,val text:String)
}

/** Implemented by the analysis side: store the transcript durably (idempotent per recordingId+sourceVersion+audioSha256)
 * and schedule its analysis. Called on a background thread right after transcription. */
interface CallTranscriptSink { fun onTranscript(transcript:CallTranscript) }

/** SecureSettings keys. Secrets are only written by explicit user action in the settings screen. */
object PipelineSettingsKeys {
    /** Anthropic API key entered by the user. SecureSettings is Keystore-encrypted; never logged or shown again. */
    const val CLAUDE_API_KEY="callsClaudeApiKey"
    /** User consent to send transcript TEXT (not audio) to the Claude API. Nothing is sent while false. */
    const val CONSENT_GRANTED="callsConsentGranted"
    /** Transcription side status: NOT_DOWNLOADED | DOWNLOADING | READY | FAILED | UNSUPPORTED. */
    const val MODEL_STATE="pipelineModelState"
    const val LAST_TRANSCRIBED_MS="callsLastTranscribedMs"
    /** Measured speed of the last transcription: processing ms per audio minute (for the S25 benchmark). */
    const val LAST_MS_PER_AUDIO_MINUTE="callsLastMsPerAudioMinute"
    const val LAST_ANALYZED_MS="callsLastAnalyzedMs"
    /** Digest hours (Asia/Seoul). Defaults 19 and 6. */
    const val EVENING_HOUR="callsEveningHour"
    const val MORNING_HOUR="callsMorningHour"
    /** Text messages: received/sent time of the newest message already taken in. */
    const val LAST_SMS_MS="messagesLastSmsMs"
    /** Work mailbox (IMAP). The password is entered by the user only, stored encrypted, never shown again. */
    const val MAIL_HOST="mailImapHost"
    const val MAIL_USER="mailUser"
    const val MAIL_PASSWORD="mailAppPassword"
    const val LAST_MAIL_MS="messagesLastMailMs"
    const val MAIL_ERROR="mailLastError"
    /** 처음 정리: when it started (records before are "old"), whether the consolidation is applied, when it was sent. */
    const val FIRST_PASS_START_MS="firstPassStartMs"
    const val FIRST_PASS_DONE="firstPassDone"
    const val FIRST_PASS_CONTEXT_SENT_MS="firstPassContextSentMs"
    /** Reading old texts and mail from 10/1 up to where the regular intake began: progress and end. */
    const val SMS_BACKFILL_CURSOR="firstPassSmsCursor"
    const val SMS_BACKFILL_UNTIL="firstPassSmsUntil"
    const val MAIL_BACKFILL_CURSOR="firstPassMailCursor"
    const val MAIL_BACKFILL_UNTIL="firstPassMailUntil"
}

/** Runtime wiring without a compile-time dependency between the two sides: the analysis side sets
 * [factory] at app start (and in its tests); the transcription worker reads it and stays idle when null. */
object CallTranscriptSinks {
    @Volatile var factory:((android.content.Context)->CallTranscriptSink)?=null
}
