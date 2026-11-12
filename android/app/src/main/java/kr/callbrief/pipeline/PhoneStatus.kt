package kr.callbrief.pipeline

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONObject

/** The phone's heartbeat for the mailbox (design B1): one small file, status/phone.json, that the watchdog reads.
 * Numbers, times and short codes only. Never a name, a number, a file name or any call content. */
internal object PhoneStatus {
    const val PATH="status/phone.json"
    private const val TAG="ieum.status"
    /** Settings keys written by other parts of the app; read by name so this file does not depend on them. */
    private const val LAST_UPDATE_ERROR="lastUpdateError"
    /** Channel diagnosis of the audio decoder (design F1, written by the v2.4 decoder): channel count and normalized L/R correlation. */
    internal const val AUDIO_CHANNELS="audioChannels"
    internal const val AUDIO_CHANNEL_CORR="audioChannelCorr"
    /** The mailbox token's expiry as last seen by the phone ([CallInbox.tokenExpiresMs]), kept across restarts. */
    internal const val TOKEN_EXPIRES_MS="tokenExpiresMs"
    /** Since when the phone's call log and the stored one have had different counts (0: they agree). */
    internal const val CALL_LOG_MISMATCH_SINCE="callLogMismatchSinceMs"
    /** Settings keys other bundles write (r7-2 discovery, r7-3 update, r7-4 call log matching, the store recovery); read by
     * name only. A key that is not there yet goes up as null. */
    internal const val LAST_DISCOVERY_ATTEMPT_MS="lastDiscoveryAttemptMs"
    internal const val LAST_DISCOVERY_OUTCOME="lastDiscoveryOutcome"
    internal const val INVENTORY_TRUNCATED="inventoryTruncated"
    internal const val HELD_UNSUPPORTED_COUNT="heldUnsupportedCount"
    internal const val UPDATE_AVAILABLE_CODE="updateAvailableCode"
    internal const val UPDATE_READY_SINCE_MS="updateReadySinceMs"
    internal const val CALLLOG_MATCH_STATS="callLogMatchStats"
    internal const val DB_RECOVERY_LAST="dbRecoveryLast"
    /** The lastInboxError code as last sent and since when it has been the same (the watchdog keeps no state of its own). */
    internal const val INBOX_ERROR_SEEN="inboxErrorSeen";internal const val INBOX_ERROR_SINCE_MS="inboxErrorSinceMs"
    /** v3.3 r13-2 (DESIGN_V4 3.2-1): running numbers of the recognizer's pieces ([SttPieceStats.add]); the heartbeat's
     * transcription.decode. */
    internal const val STT_DECODE="sttDecode"

    /** Everything the file says; [oldestQueuedMs] is the age start of the oldest recording still waiting for its text. */
    data class Input(val nowMs:Long,val appVersion:String,val versionCode:Long,val queuedCount:Int,val oldestQueuedMs:Long?,
        val lastTranscribedMs:Long,val lastResultMs:Long,
        val modelState:String,val lastInboxError:String,val lastUpdateError:String,val storageUsedPercent:Int,
        /** First pass (10/1 on): null before it starts, else whether it is done and what is left. Counts and minutes only. */
        val firstPass:FirstPassStatus?=null,
        /** Why a phone may be quiet (design B1): yes/no, times, counts and one bucket code. */
        val health:Health=Health(),
        /** Is the call log read in full (asked 11/4): counts and times only, never a number or a name. Null: left out. */
        val callLog:CallLogStatus?=null,
        /** Design 3.6 (r7-6): the queue split new/old by entry time, discovery tries, store fill and the update wait. */
        val watch:Watch=Watch(),
        /** DESIGN_V4 2절 (v3.1): permissions, the recording gap, follow-ups, store open, results, counts and latency. */
        val v31:V31=V31(),
        /** DESIGN_V4 3절 (v3.3): the golden measurement and the number guard, numbers only. */
        val v33:V33=V33(),
        /** DESIGN_V4 4절 (v3.4, r14-1 makes the places, r14-3 writes the values): update, messages and record time. */
        val v34:V34=V34(),
        /** DESIGN_V5 (v3.5, key commit makes the places, r15-1 writes the values): the KakaoTalk morning report. */
        val v35:V35=V35(),
        /** DESIGN_V6 3.1 (v3.6, r16-2): the power ledger r16-1 writes ([PipelineSettingsKeys.TRANSCRIPTION_POWER]) and the switch
         * [충전 중에만 글자로 바꾸기]; the heartbeat's `power` block ([PowerText.heartbeat]), always there, numbers and codes only. */
        val powerLedger:JSONObject?=null,val chargingOnly:Boolean=false,
        /** DESIGN_V7 3.3 (v3.7, r17-3): the scorecard's measurement ([V37]). */
        val v37:V37=V37())
    /** v3.7 r17-3 (DESIGN_V7 3.3, SCORECARD): [latency] goes into `latency` beside the 7-day numbers ({segments{endToText,
     * textToUpload,uploadToApplied}, day{p50Min,p90Min,n,waiting}, over7d}); [window14] into `transcription.window14`
     * {created,failed,failedPermanent,days}; [coverage] is `report.coverage` ([kr.callbrief.pipeline.report.ReportCoverage.snapshot])
     * and [coverageRecent] `report.recent[]`; [use] is `use{today,prev}` ([UseStats.heartbeat]); [dbTables] `dbTables`
     * ([Telemetry.dbTablesJson]); [matchDay] `callLogMatch.day` ([Telemetry.matchDayJson]); [trace] `trace[]` ([Telemetry.trace]).
     * Every one is numbers, KST days and codes only. */
    data class V37(val latency:JSONObject?=null,val window14:JSONObject?=null,val coverage:JSONObject?=null,
        val coverageRecent:org.json.JSONArray?=null,val use:JSONObject?=null,val dbTables:JSONObject?=null,val matchDay:JSONObject?=null,
        val trace:org.json.JSONArray?=null,
        /** v3.8 r18 (DESIGN_V7 4.1): `dbPrune` and `dbFull` ([DbStore.pruneHeartbeat], [DbStore.fullHeartbeat]), as stored. */
        val dbPrune:Any?=null,val dbFull:Any?=null,val dbRowTooBig:Any?=null)
    /** v3.5: heartbeat `kakao{on,linked,sent,failed,lastSentMs,error}` and `report{day,source}`; numbers, dates and codes only. */
    data class V35(val kakaoLinked:Boolean=false,val kakaoSent:Long=0,val kakaoFailed:Long=0,val kakaoLastSentMs:Long?=null,
        val kakaoError:String?=null,val reportDay:String?=null,val reportSource:String?=null,
        /** r15-4 (M2): `kakao.on`, the settings switch [카카오톡으로 받기] ([PipelineSettingsKeys.MORNING_KAKAO], default on). */
        val kakaoOn:Boolean=true,
        /** v3.7 (user 11/12): `kakao.texts` (messages of the last report) and `kakao.plainRetried` ([KakaoReport.PLAIN_RETRIED]). */
        val kakaoTexts:Long=0,val kakaoPlainRetried:Long=0)
    /** v3.4 (DESIGN_V4 4.1-5, 4.3-4, 4.3-5, 4.3-8): [updateError] = [PipelineSettingsKeys.UPDATE_ERROR_CODE] (a code, heartbeat
     * `update.error`); [recordTime] = [PipelineSettingsKeys.RECORD_TIME] ({bucket: count}, `recordTime.fileVsRecordMin`), through
     * [counts]. v3.7 (DESIGN_V7 3.3): `messages.mailError` left with the mail intake. */
    data class V34(val updateError:String?=null,val recordTime:JSONObject?=null,
        /** v3.7 (user 11/12): why the background install did (not) happen, codes, numbers and yes/no only: [bg]
         * ([PipelineSettingsKeys.UPDATE_BG], or noAnswer), [bgAtMs], [sessionOpen] (an install session is open now),
         * [autoInstall] (the settings switch), [openInstalls] (installs the open app started), [open] (its last decision). */
        val bg:String?=null,val bgAtMs:Long?=null,val sessionOpen:Boolean?=null,val autoInstall:Boolean?=null,
        val openInstalls:Long=0,val open:String?=null,
        /** v3.7: the "보안 위험 자동 차단" question shown ([blockAsk]) and its [설정 열기] pressed ([blockOpen]), counts. */
        val blockAsk:Long=0,val blockOpen:Long=0,val blockStatus:Int?=null,val autoBack:String?=null,val blockCode:String?=null,val blockRetries:Long=0)
    /** v3.3: [golden] = [PipelineSettingsKeys.GOLDEN] (r13-2, [kr.callbrief.pipeline.GoldenMeasure]), [guard] =
     * [PipelineSettingsKeys.GUARD_STATS] (r13-1). Both go through [counts]: no text, no names, only numbers and yes/no. */
    data class V33(val golden:JSONObject?=null,val guard:JSONObject?=null)
    /** Granted permissions and roles (r12-2); null: not read. */
    data class Permissions(val phoneState:Boolean,val overlay:Boolean,val redirectRole:Boolean,val installAllowed:Boolean,
        /** v3.6.1 (DESIGN_V7 K3): this process (pid, seconds since it started) and the raw app-op mode of drawing over apps
         * ([kr.callbrief.pipeline.call.CardProbe.overlayMode]: 0 allowed, 1 ignored, 2 errored, 3 default, 4 foreground; -1 not read). */
        val pid:Int?=null,val processAgeS:Long?=null,val overlayMode:Int?=null)
    /** The v3.1 keys (DESIGN_V4 2절 머리). Values come from settings keys the bundles write; null/absent where nothing is known yet.
     * [recordingGap] goes through [counts]; its verdict goes up as the top-level code [recordingGapVerdict]. */
    data class V31(val permissions:Permissions?=null,val recordingGap:JSONObject?=null,val recordingGapVerdict:String?=null,
        val followupNoRecording:Long=0,val storeOpenError:String?=null,
        val resultsApplyFailed:Long=0,val resultsBad:Long=0,val resultsReceivedNotApplied:Long=0,
        val transcriptionFailed:Long=0,val droppedItems:Long=0,val duplicateSkipped:Long=0,val latency:JSONObject?=null,
        /** r12-8 (DESIGN_V4 2-3-5,6): {failReasons{code:n},queuedByLen{lt1m,m1to3,m3to10,m10plus,unknown},doneByLen48h{..},
         * long48h{total,done,ratePct},oldestWaitMin,runs{day,runs,minutes,ends{..},prev{..}}} ([TranscriptionStats], [TranscriptionRuns]). */
        val transcription:JSONObject?=null)
    /** Counts, times and short codes; null where the phone does not know (yet). [callLogMatch] is counts only (see [counts]). */
    data class Watch(val newQueuedCount:Int=0,val newOldestQueuedMs:Long?=null,val oldQueuedCount:Int=0,
        val lastDiscoveryAttemptMs:Long?=null,val lastDiscoveryOutcome:String?=null,val inventoryTruncated:Boolean?=null,val heldUnsupported:Int?=null,
        val dbPercent:Int?=null,val updateAvailableCode:Long?=null,val updateReadySinceMs:Long?=null,val callLogMatch:JSONObject?=null,
        val dbRecovery:String?=null,val lastInboxErrorSinceMs:Long?=null,
        /** r8-1: how long the last store open and the last home read took (ms, measurement only). */
        val dbOpenMs:Long?=null,val homeReadMs:Long?=null,
        /** r10-3: the result chain's round now set (0 = ended) and when it runs next; null before the first chain. */
        val resultChainRound:Int?=null,val resultChainNextMs:Long?=null,
        /** r11-3 (design 7.3), v3.4 (DESIGN_V4 4.1-5): the call card's counts, {calls,shown,shownOutgoing,eligibleOutgoing,...,
         * day,today:{the counts},ringToCardMs:{p50,max,n},ringToCardMsCold:{..},cardOpenMs}
         * ([kr.callbrief.pipeline.call.CardStats.heartbeat]); null before this version saw any call. */
        val callCard:JSONObject?=null)
    /** One window (since 10/1, or today KST) of the phone's call log by type, with what the app made of it. */
    data class CallLogWindow(val total:Int=0,val incoming:Int=0,val outgoing:Int=0,val missed:Int=0,val rejected:Int=0,val other:Int=0,
        val recorded:Int=0,val unrecorded:Int=0,val personal:Int=0)
    /** [deviceTotal]/[latestDeviceLogMs]: the CallLog provider (null without the permission); stored*: the app's call_log table. */
    data class CallLogStatus(val allowed:Boolean,val fromMs:Long,val todayFromMs:Long,val since:CallLogWindow,val today:CallLogWindow,
        val latestDeviceLogMs:Long?,val latestStoredLogMs:Long?,val deviceTotal:Int?,val storedTotal:Int,val mismatchSinceMs:Long)
    data class Health(val consent:Boolean=false,val folder:Boolean=false,val notifyAllowed:Boolean=false,val batteryFree:Boolean=false,
        val standbyBucket:String="UNKNOWN",val lastDiscoveryMs:Long=0,val lastUploadMs:Long=0,val pendingAnalysis:Int=0,val rescheduledMs:Long=0,
        /** Mailbox token expiry (from the github-authentication-token-expiration response header); null when not seen yet. */
        val tokenExpiresMs:Long?=null,
        /** Decoder channel diagnosis (design F1); null until a recording has been decoded with it. */
        val audioChannels:Int?=null,val audioChannelCorr:Double?=null)
    /** [bound]: old records before it may go up in batches (null: no bound, all of them may); [blockedBy]: what held the
     * ready old records at the last hourly pass, one of "none", "waiting_messages", "waiting_transcripts", "backoff" (r7-1). */
    data class FirstPassStatus(val done:Boolean,val toTranscribe:Int,val minutesLeft:Long?,val waiting:Int,val batchesToday:Int,
        val bound:Long?=null,val blockedBy:String=FirstPass.BLOCKED_NONE)

    fun build(i:Input):JSONObject {
        val o=JSONObject().put("atMs",i.nowMs).put("appVersion",i.appVersion).put("versionCode",i.versionCode)
            .put("queuedCount",i.queuedCount).put("oldestQueuedMs",i.oldestQueuedMs ?: JSONObject.NULL)
            .put("lastTranscribedMs",i.lastTranscribedMs).put("lastResultMs",i.lastResultMs)
            .put("modelState",code(i.modelState)).put("storageUsedPercent",i.storageUsedPercent)
        val h=i.health
        o.put("consent",h.consent).put("folder",h.folder).put("notifyAllowed",h.notifyAllowed).put("batteryFree",h.batteryFree)
            .put("standbyBucket",code(h.standbyBucket)).put("lastDiscoveryMs",h.lastDiscoveryMs).put("lastUploadMs",h.lastUploadMs)
            .put("pendingAnalysis",h.pendingAnalysis).put("rescheduledMs",h.rescheduledMs)
            .put("tokenExpiresMs",h.tokenExpiresMs ?: JSONObject.NULL).put("audioChannels",h.audioChannels ?: JSONObject.NULL)
            .put("audioChannelCorr",h.audioChannelCorr?.takeIf { it.isFinite() }?.let { Math.round(it*1000)/1000.0 } ?: JSONObject.NULL)
        val w=i.watch
        o.put("newQueuedCount",w.newQueuedCount).put("newOldestQueuedMs",w.newOldestQueuedMs ?: JSONObject.NULL).put("oldQueuedCount",w.oldQueuedCount)
            .put("lastDiscoveryAttemptMs",w.lastDiscoveryAttemptMs ?: JSONObject.NULL)
            .put("lastDiscoveryOutcome",w.lastDiscoveryOutcome?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("inventoryTruncated",w.inventoryTruncated ?: JSONObject.NULL).put("heldUnsupported",w.heldUnsupported ?: JSONObject.NULL)
            .put("dbPercent",w.dbPercent ?: JSONObject.NULL).put("updateAvailableCode",w.updateAvailableCode ?: JSONObject.NULL)
            .put("updateReadySinceMs",w.updateReadySinceMs ?: JSONObject.NULL)
            .put("dbOpenMs",w.dbOpenMs ?: JSONObject.NULL).put("homeReadMs",w.homeReadMs ?: JSONObject.NULL)
            .put("resultChain",w.resultChainRound?.let { r -> JSONObject().put("round",r).put("nextMs",w.resultChainNextMs ?: JSONObject.NULL) } ?: JSONObject.NULL)
        val match=w.callLogMatch?.let(::counts)
        // v3.7 r17-3: the day's reasons (a "day" string, so after the counts filter).
        if(match!=null) i.v37.matchDay?.let(Telemetry::matchDayJson)?.let { match.put("day",it) }
        o.put("callLogMatch",match ?: JSONObject.NULL)
        // Counts only (the same filter as the match statistics), whatever is stored under the key.
        o.put("callCard",w.callCard?.let(::callCardJson) ?: JSONObject.NULL)
        w.dbRecovery?.let(::code)?.takeIf { it.isNotEmpty() }?.let { o.put("dbRecovery",it) }
        i.callLog?.let { c -> o.put("callLog",callLogJson(c).also { j -> match?.let { j.put("match",JSONObject(it.toString())) } }) }
        i.firstPass?.let { f -> o.put("firstPass",JSONObject().put("done",f.done).put("toTranscribe",f.toTranscribe)
            .put("audioMinutesLeft",f.minutesLeft ?: JSONObject.NULL).put("waitingForSummary",f.waiting).put("batchesToday",f.batchesToday)
            .put("bound",f.bound ?: JSONObject.NULL).put("blockedBy",code(f.blockedBy).ifEmpty { FirstPass.BLOCKED_NONE })) }
        // Absent when there is nothing to report, so the watchdog's "has an error" test is just a key test.
        code(i.lastInboxError).takeIf { it.isNotEmpty() }?.let { o.put("lastInboxError",it) }
        code(i.lastUpdateError).takeIf { it.isNotEmpty() }?.let { o.put("lastUpdateError",it) }
        // Since when the same inbox error code is reported (watchdog: 2 hours); only next to an error.
        if(o.has("lastInboxError")) w.lastInboxErrorSinceMs?.let { o.put("lastInboxErrorSinceMs",it) }
        putV31(o,i.v31)
        // v3.3: absent (null) until measured or counted; numbers only.
        o.put("golden",i.v33.golden?.let(::counts) ?: JSONObject.NULL)
        o.put("guard",i.v33.guard?.let(::counts) ?: JSONObject.NULL)
        putV34(o,i.v34)
        putV35(o,i.v35)
        o.put("power",runCatching { PowerText.heartbeat(i.powerLedger,i.chargingOnly,i.nowMs) }.getOrElse { JSONObject.NULL })
        putV37(o,i.v37)
        return o
    }
    /** v3.7 r17-3: the scorecard's blocks. `use` and `report.coverage` always (null inside until known), the rest when known. */
    private fun putV37(o:JSONObject,v:V37) {
        v.latency?.let(::counts)?.let { extra -> val lat=o.optJSONObject("latency") ?: JSONObject().also { o.put("latency",it) }
            extra.keys().forEach { k -> lat.put(k,extra.get(k)) } }
        v.window14?.let(::counts)?.let { win -> (o.optJSONObject("transcription") ?: JSONObject().also { o.put("transcription",it) }).put("window14",win) }
        o.optJSONObject("report")?.let { r ->
            r.put("coverage",v.coverage?.let(::coverageJson) ?: JSONObject.NULL)
            r.put("recent",v.coverageRecent?.let(::recentJson) ?: org.json.JSONArray())
        }
        o.put("use",v.use?.let(::useJson) ?: JSONObject().put("today",JSONObject.NULL).put("prev",JSONObject.NULL))
        o.put("dbTables",v.dbTables?.let(Telemetry::dbTablesJson) ?: JSONObject.NULL)
        o.put("trace",v.trace?.let(::traceJson) ?: org.json.JSONArray())
        o.put("dbPrune",DbStore.pruneHeartbeat(v.dbPrune) ?: JSONObject.NULL)
        o.put("dbFull",DbStore.fullHeartbeat(v.dbFull))
        o.put("dbRowTooBig",DbStore.rowTooBigHeartbeat(v.dbRowTooBig))
    }
    private val DAY=Regex("""\d{4}-\d{2}-\d{2}""")
    private fun dayOrNull(v:String?)=v?.takeIf { DAY.matches(it) } ?: JSONObject.NULL
    /** {day, yesterdayCalls, inReport, pending, atMs, source}: numbers, the report day and a letters-only source code. */
    internal fun coverageJson(c:JSONObject):JSONObject = counts(c).put("day",dayOrNull(c.optString("day")))
        .put("source",c.optString("source").filter { it.isLetter() && it.code<128 }.take(16))
    private fun recentJson(a:org.json.JSONArray)=org.json.JSONArray().apply { for(k in 0 until a.length()) a.optJSONObject(k)?.let { e -> put(counts(e).put("day",dayOrNull(e.optString("day")))) } }
    private fun dayBlock(d:JSONObject?)=d?.let { counts(it).put("day",dayOrNull(it.optString("day"))) } ?: JSONObject.NULL
    internal fun useJson(u:JSONObject)=JSONObject().put("today",dayBlock(u.optJSONObject("today"))).put("prev",dayBlock(u.optJSONObject("prev")))
    private fun traceJson(a:org.json.JSONArray)=org.json.JSONArray().apply { for(k in 0 until minOf(a.length(),Telemetry.TRACE_MAX)) a.optJSONObject(k)?.let { e ->
        val c=counts(e)
        // Null stages stay as null (the counts filter drops them).
        for(key in listOf("durS","foundS","textStartS","textEndS","uploadS","appliedS")) if(!c.has(key)) c.put(key,JSONObject.NULL)
        put(c.put("day",dayOrNull(e.optString("day")))) } }
    /** v3.4: always both objects, so the watchdog reads a key test; inside, a code or counts only (null: nothing yet). */
    private fun putV34(o:JSONObject,v:V34) {
        val bg=v.bg?.let(::code)?.takeIf { it.isNotEmpty() }
        // A check error first; else a blocked or unanswered install (v3.7), so the watchdog's one key shows it too.
        val error=v.updateError?.let(::code)?.takeIf { it.isNotEmpty() } ?: listOfNotNull(bg,v.open?.let(::code)).firstOrNull { it==CallUpdate.BG_BLOCKED || it==CallUpdate.BG_NO_ANSWER }
        o.put("update",JSONObject().put("error",error ?: JSONObject.NULL)
            .put("bg",bg ?: JSONObject.NULL).put("bgAtMs",v.bgAtMs ?: JSONObject.NULL)
            .put("sessionOpen",v.sessionOpen ?: JSONObject.NULL).put("autoInstall",v.autoInstall ?: JSONObject.NULL)
            .put("openInstalls",v.openInstalls).put("open",v.open?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("blockAsk",v.blockAsk).put("blockOpen",v.blockOpen).put("blockStatus",v.blockStatus ?: JSONObject.NULL)
            .put("autoBack",v.autoBack?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("blockCode",v.blockCode?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL).put("blockRetries",v.blockRetries))
        o.put("recordTime",JSONObject().put("fileVsRecordMin",v.recordTime?.let(::counts) ?: JSONObject.NULL))
    }
    /** v3.5: always both objects so the watchdog reads a key test. */
    private fun putV35(o:JSONObject,v:V35) {
        o.put("kakao",JSONObject().put("on",v.kakaoOn).put("linked",v.kakaoLinked).put("sent",v.kakaoSent).put("failed",v.kakaoFailed)
            .put("lastSentMs",v.kakaoLastSentMs ?: JSONObject.NULL)
            .put("error",v.kakaoError?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("texts",v.kakaoTexts).put("plainRetried",v.kakaoPlainRetried))
        o.put("report",JSONObject().put("day",v.reportDay?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: JSONObject.NULL)
            .put("source",v.reportSource?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
    }
    private fun putV31(o:JSONObject,v:V31) {
        o.put("permissions",v.permissions?.let { p -> JSONObject().put("phoneState",p.phoneState).put("overlay",p.overlay)
            .put("redirectRole",p.redirectRole).put("installAllowed",p.installAllowed)
            .put("pid",p.pid ?: JSONObject.NULL).put("processAgeS",p.processAgeS ?: JSONObject.NULL).put("overlayMode",p.overlayMode ?: JSONObject.NULL) } ?: JSONObject.NULL)
        o.put("recordingGap",v.recordingGap?.let(::counts) ?: JSONObject.NULL)
        // String values as top-level codes so the counts() filter does not drop them (DESIGN_V4 2절 머리).
        o.put("recordingGapVerdict",v.recordingGapVerdict?.let(::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        o.put("followup",JSONObject().put("noRecording",v.followupNoRecording))
        v.storeOpenError?.let(::code)?.takeIf { it.isNotEmpty() }?.let { o.put("storeOpenError",it) }
        o.put("results",JSONObject().put("applyFailed",v.resultsApplyFailed).put("bad",v.resultsBad).put("receivedNotApplied",v.resultsReceivedNotApplied))
        o.put("transcriptionFailed",v.transcriptionFailed).put("droppedItems",v.droppedItems).put("duplicateSkipped",v.duplicateSkipped)
        o.put("latency",v.latency?.let(::counts) ?: JSONObject.NULL)
        // r12-8: numbers only (failure reasons are code-like keys, the counts() filter keeps them).
        o.put("transcription",v.transcription?.let(::counts) ?: JSONObject.NULL)
    }
    private fun windowJson(w:CallLogWindow)=JSONObject().put("total",w.total).put("incoming",w.incoming).put("outgoing",w.outgoing)
        .put("missed",w.missed).put("rejected",w.rejected).put("other",w.other).put("recorded",w.recorded)
        .put("unrecordedCalls",w.unrecorded).put("personal",w.personal)
    private fun callLogJson(c:CallLogStatus)=JSONObject().put("allowed",c.allowed).put("fromMs",c.fromMs).put("todayFromMs",c.todayFromMs)
        .put("since",windowJson(c.since)).put("today",windowJson(c.today))
        .put("latestDeviceLogMs",c.latestDeviceLogMs ?: JSONObject.NULL).put("latestStoredLogMs",c.latestStoredLogMs ?: JSONObject.NULL)
        .put("deviceTotal",c.deviceTotal ?: JSONObject.NULL).put("storedTotal",c.storedTotal).put("mismatchSinceMs",c.mismatchSinceMs)

    /** Counts of the phone's call log entries from [fromMs] by type (android.provider.CallLog.Calls TYPE codes); recorded and
     * unrecorded come from the store ([stored]), personal from the privacy rules and the personal numbers ([personal]). */
    internal fun window(entries:List<FirstPass.LogEntry>,fromMs:Long,personal:(FirstPass.LogEntry)->Boolean,stored:CallRecordStore.LogTally):CallLogWindow {
        val e=entries.filter { it.startMs>=fromMs }
        fun n(type:Int)=e.count { it.type==type }
        val known=setOf(android.provider.CallLog.Calls.INCOMING_TYPE,android.provider.CallLog.Calls.OUTGOING_TYPE,
            android.provider.CallLog.Calls.MISSED_TYPE,android.provider.CallLog.Calls.REJECTED_TYPE)
        return CallLogWindow(e.size,n(android.provider.CallLog.Calls.INCOMING_TYPE),n(android.provider.CallLog.Calls.OUTGOING_TYPE),
            n(android.provider.CallLog.Calls.MISSED_TYPE),n(android.provider.CallLog.Calls.REJECTED_TYPE),e.count { it.type !in known },
            stored.recorded,stored.unrecorded,e.count(personal))
    }
    /** Keeps the start of a disagreement between the phone's and the stored call log count; 0 once they agree or cannot be compared. */
    /** Since when [code] has been the same error code: kept while it stays, restarted when it changes, 0 without one. */
    internal fun sameErrorSince(previousCode:String,previousSince:Long,code:String,now:Long):Long =
        if(code.isEmpty()) 0L else if(code==previousCode && previousSince>0) previousSince else now
    internal fun mismatchSince(previous:Long,deviceTotal:Int?,storedTotal:Int,now:Long):Long =
        if(deviceTotal==null || deviceTotal==storedTotal) 0L else if(previous>0) previous else now

    /** Error values are codes (INBOX_HTTP_401, STATUS_FAILURE_...). Anything longer or odder is cut so free text cannot leak. */
    internal fun code(raw:String):String = raw.trim().take(60).filter { it.code<128 && it.isLetterOrDigit() || it=='_' || it=='-' || it==':' }
    /** The heartbeat's `callCard`: counts only ([counts]); v3.6.1 (K3) its `recent[]` keeps short codes too, each entry with
     * [kr.callbrief.pipeline.call.CardStats.RECENT_KEYS] only ([kr.callbrief.pipeline.call.CardStats.safe]), the newest 20. */
    internal fun callCardJson(card:JSONObject):JSONObject {
        val out=counts(card)
        val r=card.optJSONArray("recent") ?: return out
        val kept=(0 until r.length()).mapNotNull { r.optJSONObject(it)?.let(kr.callbrief.pipeline.call.CardStats::safe) }
            .takeLast(kr.callbrief.pipeline.call.CardStats.RECENT)
        return out.put("recent",org.json.JSONArray(kept))
    }
    /** Only numbers and yes/no (also in nested objects and arrays) under code-like keys: whatever another part of the app
     * keeps under the match statistics, no name, number text or content can go up with it. */
    internal fun counts(o:JSONObject):JSONObject {
        val out=JSONObject()
        fun value(v:Any?):Any? = when(v) {
            is Number -> v.takeUnless { it is Double && !it.isFinite() }
            is Boolean -> v
            is JSONObject -> counts(v)
            is org.json.JSONArray -> org.json.JSONArray().apply { for(k in 0 until v.length()) value(v.opt(k))?.let { put(it) } }
            else -> null }
        for(k in o.keys()) { val key=code(k).take(40);if(key.isEmpty() || key.any { it.isDigit() } && key.count { it.isDigit() }>=6) continue
            value(o.opt(k))?.let { out.put(key,it) } }
        return out
    }

    /** Where [report] sends; a test swaps in a fake mailbox. */
    @Volatile internal var reportStore:()->CallInboxStore? ={ CallInboxConfig.store() }
    /** "문제 알리기" (design B5): the heartbeat now, on the caller's background thread. False when it could not be sent. */
    fun report(context:Context,now:Long=System.currentTimeMillis()):Boolean = try {
        val store=reportStore() ?: return false
        store.replace(PATH,build(collect(context.applicationContext,now)).toString());rememberToken(context.applicationContext);true
    } catch(e:Exception) { Log.w(TAG,"report not sent: ${e.javaClass.simpleName}");false }

    /** Reads the current state and writes the file. Never throws: the hourly tick must not fail because of this. */
    fun publish(context:Context,store:CallInboxStore? =CallInboxConfig.store(),now:Long=System.currentTimeMillis()) {
        try {
            store ?: return
            val input=collect(context.applicationContext,now)
            store.replace(PATH,build(input).toString())
            rememberToken(context.applicationContext)
            // Names and numbers: the private mailbox only (decided 11/4). Not in "문제 알리기" ([report]).
            CallLogReport.publish(context.applicationContext,store,input.appVersion,now)
        } catch(e:Exception) { Log.w(TAG,"heartbeat not sent: ${e.javaClass.simpleName}") }
    }

    /** The expiry the mailbox replies carried in this process goes to settings, so the next heartbeat has it even after a restart. */
    private fun rememberToken(app:Context) = runCatching {
        val seen=CallInbox.tokenExpiresMs ?: return@runCatching
        val settings=kr.callbrief.SecureSettings(app)
        if(settings.snapshot().optLong(TOKEN_EXPIRES_MS,0L)!=seen) settings.update { it.put(TOKEN_EXPIRES_MS,seen) }
    }

    private fun collect(app:Context,now:Long):Input {
        // v3.7 r17-3: the use counts gathered in memory go to the settings first (this is a background thread).
        UseStats.flush(app,now)
        DbStore.flush(app) // v3.8 r18: capacity refusals seen by this process
        val s=kr.callbrief.SecureSettings(app).snapshot()
        // Review #10, #11: the queue since 10/1 by the time each row entered it (not the call time), new and old apart.
        val store=StatusLights.read(app,s,now)
        val q=store.queue ?: StatusLights.Queue()
        val info=runCatching { app.packageManager.getPackageInfo(app.packageName,0) }.getOrNull()
        @Suppress("DEPRECATION") val code=info?.let { if(android.os.Build.VERSION.SDK_INT>=28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
        return Input(now,info?.versionName ?: "",code,q.count,q.oldestMs,
            lastTranscribedMs = s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0L),
            lastResultMs = s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0L),
            modelState = s.optString(PipelineSettingsKeys.MODEL_STATE,""),
            lastInboxError = s.optString(PipelineSettingsKeys.LAST_INBOX_ERROR,""),
            lastUpdateError = s.optString(LAST_UPDATE_ERROR,""),
            storageUsedPercent = storagePercent(app),
            firstPass = firstPass(app,s),
            health = health(app,s),
            callLog = callLog(app,s,now),
            watch = Watch(q.newCount,q.newOldestMs,q.oldCount,
                (s.opt(LAST_DISCOVERY_ATTEMPT_MS) as? Number)?.toLong(),s.optString(LAST_DISCOVERY_OUTCOME).takeIf { s.has(LAST_DISCOVERY_OUTCOME) },
                s.opt(INVENTORY_TRUNCATED) as? Boolean,(s.opt(HELD_UNSUPPORTED_COUNT) as? Number)?.toInt(),
                store.dbPercent,
                // r7-3 writes 0 once installed: that is "nothing waiting" (null) here.
                (s.opt(UPDATE_AVAILABLE_CODE) as? Number)?.toLong()?.takeIf { it>0 } ?: runCatching { CallUpdate.available(app)?.versionCode?.toLong() }.getOrNull(),
                (s.opt(UPDATE_READY_SINCE_MS) as? Number)?.toLong()?.takeIf { it>0 },
                matchStats(s),s.optString(DB_RECOVERY_LAST).takeIf { it.isNotBlank() },inboxErrorSince(app,s,now),
                (s.opt(kr.callbrief.IeumApplication.DB_OPEN_MS) as? Number)?.toLong(),(s.opt(kr.callbrief.IeumApplication.HOME_READ_MS) as? Number)?.toLong(),
                (s.opt(ResultChain.ROUND_KEY) as? Number)?.toInt(),(s.opt(ResultChain.NEXT_MS_KEY) as? Number)?.toLong()?.takeIf { it>0 },
                runCatching { kr.callbrief.pipeline.call.CardStats.heartbeat(s,now) }.getOrNull()),
            v31 = v31(app,s),
            v33 = V33(jsonOf(s,PipelineSettingsKeys.GOLDEN),jsonOf(s,PipelineSettingsKeys.GUARD_STATS)),
            v34 = v34(app,s),
            v35 = v35(s),
            powerLedger = runCatching { PowerText.ledger(s) }.getOrNull(),
            chargingOnly = PowerText.chargingOnly(s),
            v37 = runCatching { v37(app,s,now) }.onFailure { Log.w(TAG,"v3.7 numbers not read: ${it.javaClass.simpleName}") }.getOrDefault(V37()))
    }
    /** v3.7 r17-3: the scorecard's numbers. The day's match reasons and the report days are folded into the settings here (one
     * write when they changed); the stages come from the process store (never on the ring path or a screen draw). */
    private fun v37(app:Context,s:JSONObject,now:Long):V37 {
        val coverage=runCatching { kr.callbrief.pipeline.report.ReportCoverage.snapshot(s) }.getOrNull()
        val matchDay=runCatching { Telemetry.matchDay(jsonOf(s,PipelineSettingsKeys.CALLLOG_MATCH_DAY),matchStats(s),now) }.getOrNull()
        val covDays=Telemetry.coverageDays(jsonOf(s,PipelineSettingsKeys.REPORT_COVERAGE_DAYS),coverage)
        val matchChanged=matchDay!=null && matchDay.toString()!=jsonOf(s,PipelineSettingsKeys.CALLLOG_MATCH_DAY)?.toString()
        val covChanged=covDays.toString()!=(jsonOf(s,PipelineSettingsKeys.REPORT_COVERAGE_DAYS) ?: JSONObject()).toString()
        if(matchChanged || covChanged) runCatching { kr.callbrief.SecureSettings(app).update { st ->
            if(matchChanged) st.put(PipelineSettingsKeys.CALLLOG_MATCH_DAY,matchDay.toString())
            if(covChanged) st.put(PipelineSettingsKeys.REPORT_COVERAGE_DAYS,covDays.toString()) } }
        val starts=jsonOf(s,PipelineSettingsKeys.TRACE_TEXT_START)
        var latency:JSONObject?=null;var trace:org.json.JSONArray?=null;var window:JSONObject?=null
        runCatching { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(app)).let { db ->
            val rows=db.stageRows(now-LATENCY_DAYS*Telemetry.DAY_MS) { id -> starts?.optLong(id,0)?.takeIf { it>0 } }
            latency=JSONObject().put("segments",Telemetry.segments(rows)).put("day",Telemetry.dayLatency(rows,now)).put("over7d",Telemetry.over7d(rows))
            trace=Telemetry.trace(rows)
            db.transcriptionWindow(now-14*Telemetry.DAY_MS)?.let { (c,f,p) -> window=Telemetry.window14(c,f,p) }
        } }.onFailure { Log.w(TAG,"stages not read: ${it.javaClass.simpleName}") }
        return V37(latency,window,coverage,Telemetry.coverageRecent(covDays),UseStats.heartbeat(UseStats.stored(s),now),
            jsonOf(s,PipelineSettingsKeys.DB_TABLES),matchDay,trace,s.opt(DbStore.PRUNE),s.opt(DbStore.FULL),s.opt(DbStore.ROW_TOO_BIG))
    }
    /** The v3.4 keys r14-3 writes (read by name; absent until written). */
    internal fun v34(s:JSONObject):V34 = V34(s.optString(PipelineSettingsKeys.UPDATE_ERROR_CODE).takeIf { it.isNotBlank() },
        jsonOf(s,PipelineSettingsKeys.RECORD_TIME),s.optString(PipelineSettingsKeys.UPDATE_BG).takeIf { it.isNotBlank() },
        s.optLong(PipelineSettingsKeys.UPDATE_BG_AT_MS,0).takeIf { it>0 },null,s.optBoolean(PipelineSettingsKeys.AUTO_INSTALL,true),
        s.optLong(PipelineSettingsKeys.UPDATE_OPEN_INSTALLS,0),s.optString(PipelineSettingsKeys.UPDATE_OPEN).takeIf { it.isNotBlank() },
        s.optLong(PipelineSettingsKeys.UPDATE_BLOCK_ASK,0),s.optLong(PipelineSettingsKeys.UPDATE_BLOCK_OPEN,0),
        (s.opt(PipelineSettingsKeys.UPDATE_BLOCK_STATUS) as? Number)?.toInt(),s.optString(PipelineSettingsKeys.UPDATE_AUTO_BACK).takeIf { it.isNotBlank() },
        s.optString(PipelineSettingsKeys.UPDATE_BLOCK_CODE).takeIf { it.isNotBlank() },s.optLong(PipelineSettingsKeys.UPDATE_BLOCK_RETRIES,0))
    /** [v34] plus what only the phone knows now: the open session, and `noAnswer` when it waited too long. */
    internal fun v34(app:android.content.Context,s:JSONObject):V34 { val v=v34(s)
        return v.copy(bg=runCatching { CallUpdate.bgCode(app,v.bg) }.getOrDefault(v.bg),sessionOpen=runCatching { CallUpdate.sessionInProgress(app) }.getOrNull()) }
    /** The v3.5 keys r15-1 writes. */
    internal fun v35(s:JSONObject):V35 = V35(s.optBoolean(PipelineSettingsKeys.KAKAO_LINKED,false),
        s.optLong(PipelineSettingsKeys.KAKAO_SENT_COUNT,0),s.optLong(PipelineSettingsKeys.KAKAO_FAILED_COUNT,0),
        s.optLong(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,0).takeIf { it>0 },
        s.optString(PipelineSettingsKeys.KAKAO_LAST_ERROR).takeIf { it.isNotBlank() },
        s.optString(PipelineSettingsKeys.REPORT_DAY).takeIf { it.isNotBlank() },
        s.optString(PipelineSettingsKeys.REPORT_SOURCE).takeIf { it.isNotBlank() },
        s.optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true),
        s.optLong(kr.callbrief.pipeline.report.KakaoReport.LAST_TEXTS,0),s.optLong(kr.callbrief.pipeline.report.KakaoReport.PLAIN_RETRIED,0))
    private fun jsonOf(s:JSONObject,key:String):JSONObject? = when(val v=s.opt(key)) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null }
    private fun v31(app:Context,s:JSONObject):V31 {
        val gap=jsonOf(s,PipelineSettingsKeys.RECORDING_GAP)
        return V31(permissions(app),gap,gap?.optString("verdict")?.takeIf { it.isNotBlank() },
            s.optLong(PipelineSettingsKeys.FOLLOWUP_NO_RECORDING,0),s.optString(PipelineSettingsKeys.STORE_OPEN_ERROR).takeIf { it.isNotBlank() },
            s.optLong(PipelineSettingsKeys.RESULTS_APPLY_FAILED,0),s.optLong(PipelineSettingsKeys.RESULTS_BAD,0),
            s.optLong(PipelineSettingsKeys.RESULTS_RECEIVED_NOT_APPLIED,0),s.optLong(PipelineSettingsKeys.TRANSCRIPTION_FAILED,0),
            s.optLong(PipelineSettingsKeys.DROPPED_ITEMS,0),s.optLong(PipelineSettingsKeys.DUPLICATE_SKIPPED,0),
            jsonOf(s,PipelineSettingsKeys.LATENCY),transcription(app,s))
    }
    /** r12-8: the queue's numbers from the job ledger plus the day's runs from settings; null when neither could be read. */
    private fun transcription(app:Context,s:JSONObject):JSONObject? {
        val o=runCatching { CallTranscriptionStore(app).use { it.heartbeat() } }.onFailure { Log.w(TAG,"transcription not read: ${it.javaClass.simpleName}") }.getOrNull()
        val runs=jsonOf(s,PipelineSettingsKeys.TRANSCRIPTION_RUNS)
        val decode=jsonOf(s,STT_DECODE)
        if(o==null && runs==null && decode==null) return null
        return (o ?: JSONObject()).apply { runs?.let { put("runs",it) };decode?.let { put("decode",it) } }
    }
    /** What is actually granted now (r12-2, design 2.2-1): phone state, drawing over apps, the call redirection role, installs. */
    internal fun permissions(app:Context):Permissions? = runCatching {
        val sdk=android.os.Build.VERSION.SDK_INT
        val phone=app.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlay=android.provider.Settings.canDrawOverlays(app)
        val role=if(android.os.Build.VERSION.SDK_INT>=29) runCatching { app.getSystemService(android.app.role.RoleManager::class.java)
            ?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_REDIRECTION)==true }.getOrDefault(false) else false
        val install=runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        Permissions(phone,overlay,role,install,runCatching { kr.callbrief.pipeline.call.CardProbe.pid() }.getOrNull(),
            runCatching { kr.callbrief.pipeline.call.CardProbe.ageS() }.getOrNull(),kr.callbrief.pipeline.call.CardProbe.overlayMode(app))
    }.onFailure { Log.w(TAG,"permissions not read: ${it.javaClass.simpleName}") }.getOrNull()
    private fun inboxErrorSince(app:Context,s:JSONObject,now:Long):Long? {
        val c=code(s.optString(PipelineSettingsKeys.LAST_INBOX_ERROR,""))
        val since=sameErrorSince(s.optString(INBOX_ERROR_SEEN),s.optLong(INBOX_ERROR_SINCE_MS,0L),c,now)
        if(c!=s.optString(INBOX_ERROR_SEEN) || since!=s.optLong(INBOX_ERROR_SINCE_MS,0L))
            runCatching { kr.callbrief.SecureSettings(app).update { it.put(INBOX_ERROR_SEEN,c).put(INBOX_ERROR_SINCE_MS,since) } }
        return since.takeIf { it>0 }
    }
    /** r7-4 keeps the match statistics as a JSON string (or object). */
    private fun matchStats(s:JSONObject):JSONObject? = when(val v=s.opt(CALLLOG_MATCH_STATS)) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null }
    private fun callLog(app:Context,s:JSONObject,now:Long):CallLogStatus? = runCatching {
        val allowed=app.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val rules=kr.callbrief.LocalPrivacy.rules(s)
        // Read like the hourly pass reads it; the numbers stay in this function and only counts leave it.
        val entries=if(allowed) FirstPass.readCallLog(app) { n -> rules.excluded(n,n) } else emptyList()
        val today=java.time.Instant.ofEpochMilli(now).atZone(CallRecordStore.KST).toLocalDate().atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        val from=FirstPass.FROM_MS
        kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(app)).let { db ->
            val personalKeys=db.personalNumbers().map { it.first }.toSet()
            val personal={ e:FirstPass.LogEntry -> e.excluded || e.numberKey!=null && e.numberKey in personalKeys }
            val all=db.callLogTally(from)
            // Numbers the user excluded are never stored (E9), so they are not expected in the app's count.
            val kept=entries.filterNot { it.excluded }
            val deviceTotal=if(allowed) kept.size else null
            val since=mismatchSince(s.optLong(CALL_LOG_MISMATCH_SINCE,0L),deviceTotal,all.total,now)
            if(since!=s.optLong(CALL_LOG_MISMATCH_SINCE,0L)) kr.callbrief.SecureSettings(app).update { it.put(CALL_LOG_MISMATCH_SINCE,since) }
            CallLogStatus(allowed,from,today,window(entries,from,personal,all),window(entries,today,personal,db.callLogTally(today)),
                kept.maxOfOrNull { it.startMs },all.latestMs,deviceTotal,all.total,since)
        }
    }.onFailure { Log.w(TAG,"call log not read: ${it.javaClass.simpleName}") }.getOrNull()
    private fun health(app:Context,s:JSONObject):Health {
        val folder=StatusLights.folderReadable(app,s)
        val notify=runCatching { (android.os.Build.VERSION.SDK_INT<33 || app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            && app.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled()!=false }.getOrDefault(false)
        val battery=runCatching { app.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)==true }.getOrDefault(false)
        var lastUpload=0L;var pending=0
        runCatching { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(app)).let { db ->
            lastUpload=db.lastSentMs() ?: 0L
            val c=db.counts();pending=(c[CallRecordStore.TRANSCRIBED] ?: 0)+(c[CallRecordStore.WAITING_CONSENT] ?: 0)
        } }.onFailure { Log.w(TAG,"records not read: ${it.javaClass.simpleName}") }
        val seen=CallInbox.tokenExpiresMs ?: s.optLong(TOKEN_EXPIRES_MS,0L).takeIf { it>0 }
        return Health(s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),folder,notify,battery,standbyBucket(app),
            s.optLong(kr.callbrief.AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,0L),lastUpload,pending,s.optLong(PipelineSettingsKeys.RESCHEDULED_MS,0L),
            seen,(s.opt(AUDIO_CHANNELS) as? Number)?.toInt(),(s.opt(AUDIO_CHANNEL_CORR) as? Number)?.toDouble())
    }
    /** App standby bucket (API 28+) as a fixed code: RARE or RESTRICTED explains a phone that runs its work only now and then. */
    internal fun bucketCode(bucket:Int):String = when(bucket) {
        android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        45 -> "RESTRICTED" // STANDBY_BUCKET_RESTRICTED (API 30)
        5 -> "EXEMPTED" // STANDBY_BUCKET_EXEMPTED (hidden): battery optimisation is off for the app, the best bucket (review #19)
        50 -> "NEVER" // STANDBY_BUCKET_NEVER (hidden)
        else -> "B$bucket" }
    private fun standbyBucket(app:Context):String = if(android.os.Build.VERSION.SDK_INT<28) "NONE" else runCatching {
        bucketCode(app.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket) }.getOrDefault("UNKNOWN")
    private fun firstPass(app:Context,s:org.json.JSONObject):FirstPassStatus? = runCatching {
        val start=FirstPass.start(s) ?: return@runCatching null
        if(FirstPass.done(s)) return@runCatching FirstPassStatus(true,0,0,0,0)
        val p=kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(app)).let { it.firstPassProgress(start) }
        val minutes=p.bytesPerMinute?.takeIf { it>0 }?.let { p.bytesLeft/it }
        FirstPassStatus(false,p.toTranscribe,minutes,p.waiting,s.optInt(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,0),
            s.optLong(FirstPass.BOUND_KEY,0).takeIf { it>0 },s.optString(FirstPass.BLOCKED_KEY).ifEmpty { FirstPass.BLOCKED_NONE })
    }.onFailure { Log.w(TAG,"first pass not read: ${it.javaClass.simpleName}") }.getOrNull()
    private fun storagePercent(app:Context):Int = runCatching {
        val st=StatFs(app.filesDir.path);val total=st.totalBytes
        if(total<=0) 0 else (100-st.availableBytes*100/total).toInt().coerceIn(0,100)
    }.getOrDefault(0)
}
