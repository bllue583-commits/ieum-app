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
        val watch:Watch=Watch())
    /** Counts, times and short codes; null where the phone does not know (yet). [callLogMatch] is counts only (see [counts]). */
    data class Watch(val newQueuedCount:Int=0,val newOldestQueuedMs:Long?=null,val oldQueuedCount:Int=0,
        val lastDiscoveryAttemptMs:Long?=null,val lastDiscoveryOutcome:String?=null,val inventoryTruncated:Boolean?=null,val heldUnsupported:Int?=null,
        val dbPercent:Int?=null,val updateAvailableCode:Long?=null,val updateReadySinceMs:Long?=null,val callLogMatch:JSONObject?=null,
        val dbRecovery:String?=null,val lastInboxErrorSinceMs:Long?=null,
        /** r8-1: how long the last store open and the last home read took (ms, measurement only). */
        val dbOpenMs:Long?=null,val homeReadMs:Long?=null)
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
        val match=w.callLogMatch?.let(::counts)
        o.put("callLogMatch",match ?: JSONObject.NULL)
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
        return o
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
                (s.opt(kr.callbrief.IeumApplication.DB_OPEN_MS) as? Number)?.toLong(),(s.opt(kr.callbrief.IeumApplication.HOME_READ_MS) as? Number)?.toLong()))
    }
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
