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
    /** [uncertainNumbers] (v3.3 seam, r13-2 fills, r13-1 reads): number tokens in [text] whose lowest token probability was
     * under 0.5, as they appear in [text] (digits as written by the recognizer). Empty when all were sure or unknown. */
    data class Segment(val startMs:Long,val endMs:Long,val text:String,val uncertainNumbers:List<String> =emptyList())
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
    /** Reading old texts from 10/1 up to where the regular intake began: progress and end. (The mail keys left in v3.7:
     * [MessageIntake.REMOVED_MAIL_KEYS].) */
    const val SMS_BACKFILL_CURSOR="firstPassSmsCursor"
    const val SMS_BACKFILL_UNTIL="firstPassSmsUntil"
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

    // ---- v3.3 (DESIGN_V4 3절, key commit): names only. ----
    /** r13-1: "잠금 화면에서도 이름과 한 줄 보기" (default on). */
    const val LOCK_SCREEN_DETAIL="lockScreenDetail"
    /** r13-1: check items and promises marked tentative by the number guard, by reason, a JSON object
     * {"number","date","asr","quote","confusable"} (running counts; heartbeat guard). */
    const val GUARD_STATS="guardStats"
    /** r13-2: the last golden measurement on real recordings, a JSON string of numbers only (heartbeat golden). */
    const val GOLDEN="golden"

    // ---- v3.4 (DESIGN_V4 4절, key commit): names only, each bundle writes its own value. ----
    /** r14-3: `endedMs` of the hourly routine's self-report (status/last-run-hourly.json) as last read by the results pass;
     * r14-2's 분석 light compares it with the cron slots. */
    const val ROUTINE_ENDED_MS="routineEndedMs"
    /** r14-3: the routine's cron strings as last read from last-run-hourly.json (JSON array of strings), for result timing. */
    const val ROUTINE_CRONS="routineCrons"
    /** r14-3: the last update-check error code (heartbeat update.error); empty once a check goes through. */
    const val UPDATE_ERROR_CODE="updateErrorCode"
    /** r14-3: record time diagnosis, a JSON object {bucket: count} of created_ms − started_ms (heartbeat recordTime.fileVsRecordMin). */
    const val RECORD_TIME="recordTime"
    /** r14-1: the optional outgoing-call role offer in settings ("거는 전화에 번호를 더 빨리 알기") was dismissed. */
    const val ROLE_OFFER_SEEN="roleOfferSeen"

    // ---- v3.5 (DESIGN_V5, key commit): names only, each bundle writes its own value. ----
    /** r15-1: the wife's Kakao account is linked (talk_message agreed); false after an invalid-token error. */
    const val KAKAO_LINKED="kakaoLinked"
    /** r15-1: when the morning report last went out in full over KakaoTalk (epoch ms). */
    const val KAKAO_LAST_SENT_MS="kakaoLastSentMs"
    /** r15-1: the last Kakao send error code (80 chars at most, no text from the report); empty after a success. */
    const val KAKAO_LAST_ERROR="kakaoLastError"
    /** r15-1: running counts of fully sent and failed morning reports. */
    const val KAKAO_SENT_COUNT="kakaoSentCount"
    const val KAKAO_FAILED_COUNT="kakaoFailedCount"
    /** r15-1: the KST day ("YYYY-MM-DD") of the last report sent, so a day is never sent twice. */
    const val REPORT_DAY="reportDay"
    /** r15-1: where that report came from: "routine" (report/<day>.json) or "phone" (the 06:00 fallback). */
    const val REPORT_SOURCE="reportSource"
    /** r15-3: settings switch "카카오톡으로 받기" (default on once linked). */
    const val MORNING_KAKAO="morningKakao"

    // ---- v3.6 (DESIGN_V6 5, r16-0 key commit): names only, each bundle writes its own value. ----
    /** r16-2 settings switch "충전 중에만 글자로 바꾸기" (default off): transcription runs only while plugged in. */
    const val TRANSCRIBE_CHARGING_ONLY="transcribeChargingOnly"
    /** r16-1: the transcription power ledger, a JSON object ([PowerStats]): the heartbeat's `power` block is made from it
     * (today/prev runs and minutes on battery and plugged in, the battery budget, skips, killed/yield ms, model loads, piece
     * speed, the last battery snapshot, plug debounce and level samples, and the power-waiting queue). Numbers and codes only. */
    const val TRANSCRIPTION_POWER="transcriptionPower"

    // ---- v3.7 r17-1 (DESIGN_V7 3.1 T5) ----
    /** The plugged-in transcription run in the foreground (a measurement): "auto" (default: KST even dates only, so nights
     * on and off can be compared), "on", "off" ([ForegroundPolicy]). No switch on screen; set from the settings JSON. */
    const val TRANSCRIBE_FOREGROUND="transcribeForeground"
}

/** Runtime wiring without a compile-time dependency between the two sides: the analysis side sets
 * [factory] at app start (and in its tests); the transcription worker reads it and stays idle when null. */
object CallTranscriptSinks {
    @Volatile var factory:((android.content.Context)->CallTranscriptSink)?=null
}
