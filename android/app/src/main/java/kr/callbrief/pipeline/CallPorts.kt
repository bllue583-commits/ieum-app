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

/** SecureSettings keys. Written only by explicit user action in the settings screen. */
object PipelineSettingsKeys {
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
    /** 처음 정리: when it started (records before are "old") and whether it is over. */
    const val FIRST_PASS_START_MS="firstPassStartMs"
    const val FIRST_PASS_DONE="firstPassDone"
    /** rev of the repository ledger last taken in (design: 장부의 원본). */
    const val LEDGER_REV="ledgerRev"
    /** Morning tidy (design: 새벽 정리): when the nightly state last went up, and the rev of the last proposal applied. */
    const val TIDY_SENT_MS="tidySentMs"
    const val TIDY_REV="tidyRev"
    /** First pass: since when nothing old was left (it ends after 12 quiet hours). */
    const val FIRST_PASS_QUIET_SINCE="firstPassQuietSince"
    /** Check items and promises the phone dropped (quote not found, unknown task): a running count for checking. */
    const val DROPPED_ITEMS="droppedItems"
    /** Day (yyyy-MM-dd) the "정리가 멈췄어요" notice was last shown. */
    const val STALL_WARNED_DAY="stallWarnedDay"
    /** Result files that failed to apply, by name: tries so far (round 2). */
    const val RESULT_FAILS="resultFails"
    /** The inbox refused this phone (401/403/404): day told, and the code. */
    const val DISCONNECT_WARNED_DAY="disconnectWarnedDay"
    const val LAST_INBOX_ERROR="lastInboxError"
    /** When [LAST_INBOX_ERROR] was written (r7-1); 0 once a pass went through. */
    const val LAST_INBOX_ERROR_MS="lastInboxErrorMs"
    /** Mirrors the repository ledger's tidiedOnce: the morning tidy has read everything once. */
    const val TIDIED_ONCE="tidiedOnce"
    /** Reading old texts and mail from 10/1 up to where the regular intake began: progress and end. */
    const val SMS_BACKFILL_CURSOR="firstPassSmsCursor"
    const val SMS_BACKFILL_UNTIL="firstPassSmsUntil"
    const val MAIL_BACKFILL_CURSOR="firstPassMailCursor"
    const val MAIL_BACKFILL_UNTIL="firstPassMailUntil"
    /** A9: hallucinated segments and repeated sentences removed from transcripts (a running count). */
    const val TRANSCRIPT_CLEANED="transcriptCleaned"
    /** A2: when the daily self-check last had to register a lost work again (0/absent = never). */
    const val RESCHEDULED_MS="rescheduledMs"
    /** The last failed self-update install: "code: message" (cleared when an install succeeds). Shown on the settings update light. */
    const val LAST_UPDATE_ERROR="lastUpdateError"
    /** Design 3.3 (r7-3): install a downloaded, verified new version in the background (default on). Off: a notification only. */
    const val AUTO_INSTALL="autoInstall"
    /** The downloaded, verified new version code waiting to be installed (0 once installed or when up to date). */
    const val UPDATE_AVAILABLE_CODE="updateAvailableCode"
    /** When that version's download was verified (ms); 0 once it is installed. The watchdog's "not installed for 24 hours" rule reads it. */
    const val UPDATE_READY_SINCE_MS="updateReadySinceMs"
    /** When the evening or morning report was last actually posted (ms). */
    const val LAST_DIGEST_MS="lastDigestMs"
    /** "내 정보" (design C5 [사용자]): the user's name, company and site, and title. Empty: the request says roles only. */
    const val MY_NAME="myName"
    const val MY_COMPANY="myCompany"
    const val MY_ROLE="myRole"
    /** Check items the AI proposed that were left out as the same as an existing one: a running count (design E5). */
    const val DUPLICATE_SKIPPED="duplicateSkipped"
    /** Near-duplicate check items left out (design 3.4): a JSON array, newest last, at most 50, of [kept item id, record key of
     * the call that proposed the other]; the morning tidy's state carries it as mergeCandidates. */
    const val MERGE_CANDIDATES="mergeCandidates"
    /** People the v3 key migration merged (same number or same company): a JSON array of {"from":id,"into":id}, ledger ids where known. */
    const val MERGE_LOG="mergeLog"
    /** Why recordings did or did not pair with the call log (design 3.4): a JSON string, see [FirstPass.MatchStats.mergeInto]. */
    const val CALLLOG_MATCH_STATS="callLogMatchStats"
    /** First-pass batches put up on one KST day (design C7: at most [FirstPass.BATCHES_PER_DAY]): the day and the count. */
    const val FIRST_PASS_BATCH_DAY="firstPassBatchDay"
    const val FIRST_PASS_BATCHES_TODAY="firstPassBatchesToday"
    /** Design 6.2 (r10-2): one notice per finished work call, "정리하고 있어요" then "정리됐어요" (default on). Off: no notices;
     * results still reach the home screen and the reports. */
    const val FOLLOWUP_NOTICE="followupNotice"
    /** Design 7 (v3.0, r11-1): the call card over the call screen for calls that come in (default on), that go out (default on;
     * only with the call redirection role, which gives the number), and for numbers without a card (default off). */
    const val CARD_INCOMING="cardIncoming"
    const val CARD_OUTGOING="cardOutgoing"
    const val CARD_UNKNOWN="cardUnknown"
    /** Where the user dragged the card (r11-2 writes it); absent: the design's place (38% down the screen). */
    const val CARD_POS_Y="cardPosY"
    /** r11-3: how often the call card showed and why not, once per call ([kr.callbrief.pipeline.call.CardStats]): a JSON object
     * {"shown","noPermission","noNumber","noCard","day"(KST yyyymmdd),"today":{the four}}; the heartbeat's callCard. */
    const val CARD_STATS="cardStats"

    // ---- v3.1 (DESIGN_V4 2절 머리, r12-2 key commit): names only, each bundle writes its own value. ----
    /** r12-1 [RecordingGap.judge]: the last verdict as a JSON string {"calls","withFile","matched","newestFileMs","sinceMs",
     * "verdict","atMs"}; verdict is one of [GAP_OK], [GAP_NO_RECORDING], [GAP_PARTIAL], [GAP_NO_MATCH], [GAP_FOLDER], [GAP_TOO_MANY]. */
    const val RECORDING_GAP="recordingGap"
    const val GAP_OK="ok";const val GAP_NO_RECORDING="noRecording";const val GAP_PARTIAL="partial"
    const val GAP_NO_MATCH="noMatch";const val GAP_FOLDER="folder";const val GAP_TOO_MANY="tooMany"
    /** r12-1: calls whose notice said "이 통화는 녹음이 없어 정리할 수 없어요" (a running count; heartbeat followup.noRecording). */
    const val FOLLOWUP_NO_RECORDING="followupNoRecording"
    /** r12-4: why the record store could not be opened, "ExceptionClass:stage" (empty/absent once it opens again). */
    const val STORE_OPEN_ERROR="storeOpenError"
    /** r12-3: result files that failed to apply (a try), went to bad/, and records whose received mark was released after 24 hours. */
    const val RESULTS_APPLY_FAILED="resultsApplyFailed"
    const val RESULTS_BAD="resultsBad"
    const val RESULTS_RECEIVED_NOT_APPLIED="resultsReceivedNotApplied"
    /** The FAILED reason of a record whose result went to bad/ twice (r12-3); the call screen offers [다시 정리하기] (r12-5). */
    const val RESULT_NOT_APPLIED="RESULT_NOT_APPLIED"
    /** r12-3: transcriptions that ended in failure (a running count). */
    const val TRANSCRIPTION_FAILED="transcriptionFailed"
    /** r12-3: call end → result applied, minutes over the last 7 days, as a JSON string {"p50Min","p90Min","n","atMs"}. */
    const val LATENCY="latency"
    /** r12-2 (2.2-5): the update state the settings row and home banner show, a JSON string
     * {"phase","code","doneBytes","totalBytes","atMs"}; phase is checking | offline | downloading | ready | waitingConfirm | failed | none. */
    const val UPDATE_PROGRESS="updateProgress"
    /** v3.2 (r12-6): today's call story the hourly task writes to story/today.json, kept as that file's JSON string
     * {"day":"YYYY-MM-DD" (KST),"text","calls","atMs"} for the top of home. */
    const val STORY="story"
    /** r12-8 (DESIGN_V4 2-3-5 A1): transcription runs of the day (KST), a JSON string {"day":yyyymmdd,"runs","minutes",
     * "ends":{pass end code: count},"prev":{the same for the day before}} ([TranscriptionRuns.add]); the heartbeat's transcription.runs. */
    const val TRANSCRIPTION_RUNS="transcriptionRuns"
}

/** Runtime wiring without a compile-time dependency between the two sides: the analysis side sets
 * [factory] at app start (and in its tests); the transcription worker reads it and stays idle when null. */
object CallTranscriptSinks {
    @Volatile var factory:((android.content.Context)->CallTranscriptSink)?=null
}
