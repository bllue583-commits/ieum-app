package kr.callbrief.pipeline

import org.json.JSONObject

/** The app's own daily self-check (design B4), run at the end of the evening report work, next to the A2 self-heal.
 * Judging is a pure function over what the phone already has; [CallDigestWork] gathers the input and posts the notice
 * on the "이음 상태" channel. Each problem is told at most once a KST day. The two notices the analysis side already
 * sends ("정리가 멈췄어요", "연결이 끊겼어요") are not repeated: when either was shown today, the late-result notice stays quiet. */
internal object SelfCheckNotices {
    /** One problem: [code] is for the once-a-day record (never shown), [title] and [text] are what the user reads. */
    data class Notice(val code:String,val title:String,val text:String)

    data class Input(
        val now:Long,
        /** A folder was chosen once, and Android still lets the app read it. */
        val folderChosen:Boolean,val folderReadable:Boolean,
        val modelState:String,
        /** Recent recordings waiting for their text, when the oldest of them entered the queue (not its call time: a first-pass
         * recording may rightly wait), and when the last text was made (0: never). */
        val queued:Int,val oldestQueuedMs:Long?,val lastTranscribedMs:Long,
        /** Sending is on (consent and a mailbox), when the oldest recent call waiting for its summary was stored, and when the last result came. */
        val sending:Boolean,val oldestWaitingMs:Long?,val lastAnalyzedMs:Long,
        /** Length of the sealed call database file (ProtectedDatabase.file), or null when it could not be read. */
        val dbBytes:Long?,
        /** Days (yyyy-MM-dd, KST) the analysis side last said "정리가 멈췄어요" and "연결이 끊겼어요". */
        val stallWarnedDay:String="",val disconnectWarnedDay:String="",
        /** Rows in the store (a snapshot holds at most [StatusLights.ROW_LIMIT]); null when not read. */
        val dbRows:Long?=null,
        /** v3.1 (DESIGN_V4 2.1-2): a confirmed recording-gap verdict due tonight ([RecordingGap.noticeDue]: day 1, day 2, then
         * every 7 days); null: nothing to tell. */
        val gap:RecordingGap.Verdict?=null,
        /** v3.1 (2.2-4): a verified new version has waited more than a day to be installed ([CallUpdate.notInstalledForADay]). */
        val updateStuck:Boolean=false)

    const val TRANSCRIBE_LATE="transcribe_late";const val MODEL_FAILED="model_failed";const val FOLDER_LOST="folder_lost"
    const val NO_RESULT="no_result";const val STORAGE_FULL="storage_full"
    const val RECORDING_GAP="recording_gap";const val UPDATE_STUCK="update_stuck"
    /** Same thresholds as the status lights and the watchdog (B2, B5). */
    const val TRANSCRIBE_LATE_MS=StatusLights.TRANSCRIBE_LATE_MS
    const val NO_RESULT_MS=StatusLights.NO_RESULT_MS
    const val WAIT_LATE_MS=StatusLights.ANALYZE_LATE_MS
    const val STORAGE_SHARE_PERCENT=80

    /** Settings keys (read and written only here): the KST day of the last self-check notice and the problem codes told that day. */
    const val DAY_KEY="selfCheckDay";const val SHOWN_KEY="selfCheckShown"

    fun day(now:Long):String=java.time.Instant.ofEpochMilli(now).atZone(CallRecordStore.KST).toLocalDate().toString()
    /** The fuller of the file against 16 MB and the rows against 100,000 (review data #4). */
    fun storagePercent(bytes:Long,rows:Long?=null):Int=StatusLights.dbPercent(bytes,rows) ?: 0

    /** Every problem found now, in the order they are listed. */
    fun check(i:Input):List<Notice> = buildList {
        val today=day(i.now)
        // v3.1: calls are not being recorded comes first, it is why nothing else happens. A lost folder grant has its own line below.
        i.gap?.let { v -> gapNotice(v) }?.takeIf { !(i.gap.code==PipelineSettingsKeys.GAP_FOLDER && i.folderChosen && !i.folderReadable) }?.let(::add)
        if(i.modelState=="READY" && i.queued>0 && (i.oldestQueuedMs ?: i.now)<=i.now-TRANSCRIBE_LATE_MS && i.now-i.lastTranscribedMs>TRANSCRIBE_LATE_MS)
            add(Notice(TRANSCRIBE_LATE,"통화 글자 바꾸기가 밀려 있어요","충전기에 꽂아 두면 밤에 끝나요."))
        if(i.modelState=="FAILED")
            add(Notice(MODEL_FAILED,"음성 인식 모델을 다시 받아야 해요","와이파이에 연결한 뒤 이음 설정을 열어 주세요."))
        if(i.folderChosen && !i.folderReadable)
            add(Notice(FOLDER_LOST,"녹음 폴더 연결이 풀렸어요","이음 설정에서 녹음 폴더를 다시 골라 주세요."))
        if(i.sending && i.oldestWaitingMs!=null && i.now-i.oldestWaitingMs>=WAIT_LATE_MS && i.now-i.lastAnalyzedMs>=NO_RESULT_MS &&
            i.stallWarnedDay!=today && i.disconnectWarnedDay!=today)
            add(Notice(NO_RESULT,"정리가 늦어지고 있어요","통화 정리 결과가 오래 오지 않아요. 휴대폰이 인터넷에 연결되어 있으면 매시간 다시 받아 봐요."))
        StatusLights.dbPercent(i.dbBytes,i.dbRows)?.takeIf { it>=STORAGE_SHARE_PERCENT }?.let { p ->
            add(Notice(STORAGE_FULL,"이음 저장 공간이 거의 찼어요","저장 공간을 ${p}% 쓰고 있어요. 관리하는 분께 이 알림을 보여 주세요.")) }
        if(i.updateStuck)
            add(Notice(UPDATE_STUCK,"새 버전이 하루 넘게 설치되지 않았어요","이음 설정의 앱 버전에서 지금 업데이트를 눌러 주세요."))
    }
    /** The self-check notice of a recording-gap verdict (spoken codes only). */
    fun gapNotice(v:RecordingGap.Verdict):Notice? = when(v.code) {
        PipelineSettingsKeys.GAP_NO_RECORDING -> Notice(RECORDING_GAP,"통화 녹음이 꺼져 있는 것 같아요","${RecordingGap.counted(v)} ${RecordingGap.PHONE_PATH}.")
        PipelineSettingsKeys.GAP_PARTIAL -> Notice(RECORDING_GAP,"통화가 일부만 녹음되고 있어요","${RecordingGap.counted(v)} ${RecordingGap.PHONE_PATH}.")
        PipelineSettingsKeys.GAP_FOLDER -> Notice(RECORDING_GAP,"녹음 폴더를 다시 골라 주세요","이음 설정의 연결 묶음에서 녹음 폴더를 다시 골라 주세요.")
        else -> null
    }

    /** The problems not yet told today, given the settings snapshot [s]. */
    fun due(found:List<Notice>,now:Long,s:JSONObject):List<Notice> {
        val shown=if(s.optString(DAY_KEY)==day(now)) s.optString(SHOWN_KEY).split(',').filter { it.isNotBlank() }.toSet() else emptySet()
        return found.filter { it.code !in shown }
    }
    /** Records [told] as shown today, keeping what was already told today. */
    fun remember(s:JSONObject,told:List<Notice>,now:Long):JSONObject {
        val today=day(now)
        val before=if(s.optString(DAY_KEY)==today) s.optString(SHOWN_KEY).split(',').filter { it.isNotBlank() } else emptyList()
        return s.put(DAY_KEY,today).put(SHOWN_KEY,(before+told.map { it.code }).distinct().joinToString(","))
    }

    /** One notification for everything due: a single problem reads as itself; several get a count as the title and one line each. */
    fun message(due:List<Notice>):CallDigestText.Message? = when(due.size) {
        0 -> null
        1 -> CallDigestText.Message(due[0].title,due[0].text,listOf(due[0].text))
        else -> CallDigestText.Message("이음에서 살펴볼 일이 ${due.size}가지 있어요",due[0].title,due.map { "${it.title}. ${it.text}" })
    }
}
