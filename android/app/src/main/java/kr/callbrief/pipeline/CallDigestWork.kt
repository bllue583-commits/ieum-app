package kr.callbrief.pipeline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.callbrief.R
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.report.KakaoReport
import kr.callbrief.pipeline.report.ReportFetch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Notification text composed locally. The expanded notification lists the to-dos (morning) or the
 * calls with their one-line summaries (evening), so the report can be read without opening the app. */
internal object CallDigestText {
    /** [doneId]/[doneRevision]: the check item the body names, finished by the morning report's [다 됐어요] button (r8-6). */
    data class Message(val title:String,val text:String,val lines:List<String> =emptyList(),val doneId:String?=null,val doneRevision:Long=0)
    const val MAX_LINES=7
    private fun more(lines:List<String>)=if(lines.size>MAX_LINES) lines.take(MAX_LINES-1)+"외 ${lines.size-MAX_LINES+1}개" else lines
    /** The body's first line is the latest call, one line ("오후 2:10 △△철강 김민수, 단가를 다시 받기로 했어요"), then the waiting count; the expanded
     * notification lists every call, the latest first. */
    /** v3.5 r15-0 (user 11/6: the report counts recordings): the title is "녹음 n개 중 정리 m개, 새 체크할 일 k개", the same count
     * as home ([TodayStory.countLine]). [recordings]: the day's recordings, the call records and the recordings still without one
     * ([CallTranscriptionStore.withoutRecord]); null: the call records alone (older callers). [logged]: calls in the call log
     * that day; with calls there but no recording at all the title says so without the log's count (kept for the heartbeat). */
    fun evening(calls:List<CallRecord>,newTasks:Int,recordings:Int?=null,logged:Int=0):Message {
        val lines=calls.sortedByDescending { it.startedMs ?: it.createdMs }.map { c ->
            "${CallUi.time(c.startedMs)} ${c.displayName}, "+(c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c))
        }
        val recorded=calls.count { it.kind=="통화" }
        val n=maxOf(recordings ?: recorded,recorded)
        if(n==0 && logged>0) {
            val text=if(calls.isEmpty()) NO_RECORDING_WORDS else lines.first()
            return Message("오늘은 녹음된 통화가 없어요",text,listOf(NO_RECORDING_WORDS)+more(lines))
        }
        val waiting=calls.count { it.summary==null && it.state!=CallRecordStore.FAILED }+(n-recorded)
        val text=if(calls.isEmpty()) "오늘 정리된 통화가 없어요" else lines.first()+(if(waiting>0) ", 아직 정리 중인 통화가 ${waiting}건 있어요" else "")
        return Message("${TodayStory.countLine(n,TodayStory.summarized(calls))}, 새 체크할 일 ${newTasks}개",text,more(lines))
    }
    const val NO_RECORDING_WORDS="녹음이 없어 정리한 통화가 없어요"
    /** Up to [ISSUE_LINES] "진행 중인 일" lead the morning lines (design: 새벽 정리). */
    const val ISSUE_LINES=3
    /** v3.5 r15-3 (DESIGN_V5 2.3-2, review C-12: the first line was the oldest item): the morning report's order, the same as
     * the KakaoTalk report's "오늘 챙길 일" (tidy.md 9-1): the items to check (needs_confirmation, or a number that may be
     * misheard) first, then the ones asked for today or yesterday, then the rest; within each, the latest request first. */
    fun ranked(items:List<CallTodoItem>,today:LocalDate):List<CallTodoItem> {
        fun rank(i:CallTodoItem)=when {
            i.status=="needs_confirmation" || i.tentative -> 0
            i.createdMs>0 && !CallRecordStore.kstDay(i.createdMs).isBefore(today.minusDays(1)) -> 1
            else -> 2 }
        return items.sortedWith(compareBy<CallTodoItem>(::rank).thenByDescending { it.createdMs })
    }
    /** Every open check item in [ranked] order, with when it was asked for. No deadlines, no "지남" (user rule;
     * round 2: before, items without a date never reached this report). An item that may hold a misheard number ends in ", 확인 필요". */
    fun morning(items:List<CallTodoItem>,today:LocalDate,issues:List<Issue> =emptyList()):Message {
        val issueLines=issues.take(ISSUE_LINES).map { "진행 중, ${it.title}"+(if(it.status.isBlank()) "" else ": ${it.status}") }
        val order=ranked(items,today)
        val itemLines=order.map { "${it.company?.let { c -> "[$c] " } ?: ""}${it.title}"+CallUi.requested(it.createdMs).let { r -> if(r.isEmpty()) "" else ", $r" }+
                (if(it.status=="needs_confirmation") ", 끝났는지 확인" else "")+
                // v3.3 (DESIGN_V4 3.1-2): a number or date that may have been misheard, said in words (a notification has no badge).
                (if(it.tentative) ", ${CallUi.UNSURE}" else "") }
        // v3.3 (DESIGN_V4 3.3-4): the line the [다 됐어요] button finishes leads, marked "지금 할 일:"; the issues under way follow
        // the check items (they always fit: the items give way to them).
        val marked=itemLines.mapIndexed { i,l -> if(i==0) NOW_PREFIX+l else l }
        val lines=(if(issueLines.isEmpty()) more(marked) else moreIn(marked,MAX_LINES-issueLines.size))+issueLines
        val first=order.firstOrNull()
        return Message("체크할 일 ${items.size}개",if(items.isEmpty()) "체크할 일이 없어요" else marked.first(),lines,
            first?.id,first?.revision ?: 0)
    }
    /** The words in front of the morning report's first check item, the one its [다 됐어요] button finishes. */
    const val NOW_PREFIX="지금 할 일: "
    private fun moreIn(lines:List<String>,room:Int):List<String> { val keep=(room-1).coerceAtLeast(1)
        return if(lines.size>room.coerceAtLeast(1)) lines.take(keep)+"외 ${lines.size-keep}개" else lines }
    /** The morning report once its last item was finished from the notification: no button any more. */
    fun allDone(issues:List<Issue> =emptyList()):Message {
        val lines=issues.take(ISSUE_LINES).map { "진행 중, ${it.title}"+(if(it.status.isBlank()) "" else ": ${it.status}") }
        return Message("체크할 일을 다 끝냈어요","남은 체크할 일이 없어요",lines)
    }
    fun evening(store:CallRecordStore,day:LocalDate):Message {
        val calls=store.day(day)
        // v3.5 r15-0: the same count as home, by recording; the call log only says whether there were calls at all.
        val logs=runCatching { store.dayLog(day) }.getOrDefault(emptyList())
        val recordings=calls.count { it.kind=="통화" }+CallTranscriptionStore.withoutRecord(day) { sql,args -> store.cardRows(sql,*args) }
        return evening(calls,store.newTaskCount(day),recordings,CallsScreen.visibleLogs(calls,logs).size)
    }
    fun morning(store:CallRecordStore,day:LocalDate)=morning(store.openTodos(),day,store.issues())

    // ---- v3.5 r15-0: a new version waiting for its install ----
    /** The line the morning report and the evening self-check add while a verified new version waits to be installed. */
    const val UPDATE_LINE="새 버전 설치가 기다리고 있어요"
    /** [m] with [UPDATE_LINE] at its end when [waiting] (the lines stay within [MAX_LINES]). */
    fun withUpdate(m:Message,waiting:Boolean):Message {
        if(!waiting) return m
        val lines=if(m.lines.size>=MAX_LINES) m.lines.take(MAX_LINES-1) else m.lines
        return m.copy(lines=lines+UPDATE_LINE)
    }

    // ---- v3.4 (DESIGN_V4 4.2-4 나): calls the evening report could not sum up, told the next morning ----
    /** Settings key: what the last evening report left waiting, a JSON text {"at": when it went out, "day": its day
     * (yyyy-MM-dd), "ids": the records of that day without their result}. Record ids only, never a name or a number. */
    const val EVENING_WAITING_KEY="digestEveningWaiting"
    /** "늦게 정리된 통화 2건": the morning report's last line when calls were summed up after the evening report. */
    fun lateLine(n:Int):String?=if(n>0) "늦게 정리된 통화 ${n}건" else null
    /** What the evening report of [calls] (its [day], sent [at]) leaves waiting, for [EVENING_WAITING_KEY]. */
    fun eveningWaiting(calls:List<CallRecord>,day:LocalDate,at:Long):String =org.json.JSONObject().put("at",at).put("day",day.toString())
        .put("ids",org.json.JSONArray().apply { calls.filter { it.summary==null && it.state!=CallRecordStore.FAILED }.forEach { put(it.recordId) } }).toString()
    /** Calls summed up after the evening report: the ones it left waiting that have their result now, and the calls of its day
     * that began after it went out and have one. 0 without a stored evening. */
    fun lateDone(store:CallRecordStore,saved:String?):Int {
        val o=saved?.takeIf { it.isNotBlank() }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return 0
        val at=o.optLong("at",0);val ids=o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().toSet()
        fun done(r:CallRecord?)=r!=null && r.state==CallRecordStore.ANALYZED && !r.summary.isNullOrBlank()
        val waited=ids.count { id -> done(runCatching { store.record(id) }.getOrNull()) }
        val after=runCatching { LocalDate.parse(o.optString("day")) }.getOrNull()?.let { d ->
            runCatching { store.day(d) }.getOrDefault(emptyList()).count { r -> r.recordId !in ids && (r.startedMs ?: r.createdMs)>=at && r.kind=="통화" && done(r) } } ?: 0
        return waited+after
    }
    /** [m] with the late line at its end (the lines stay within [MAX_LINES]). */
    fun withLate(m:Message,n:Int):Message {
        val line=lateLine(n) ?: return m
        val lines=if(m.lines.size>=MAX_LINES) m.lines.take(MAX_LINES-1) else m.lines
        return m.copy(lines=lines+line)
    }
}

/** Next KST run time for a given hour. */
internal object CallDigestTime {
    fun next(now:Instant,hour:Int):Instant {
        val local=now.atZone(CallRecordStore.KST)
        var target=local.toLocalDate().atTime(hour.coerceIn(0,23),0).atZone(CallRecordStore.KST)
        if(!target.toInstant().isAfter(now)) target=target.plusDays(1)
        return target.toInstant()
    }
    /** The latest time at or before [now] that is [hour] o'clock in KST (stands in for the target of work queued by an older version). */
    fun previous(now:Instant,hour:Int):Instant {
        var target=now.atZone(CallRecordStore.KST).toLocalDate().atTime(hour.coerceIn(0,23),0).atZone(CallRecordStore.KST)
        if(target.toInstant().isAfter(now)) target=target.minusDays(1)
        return target.toInstant()
    }
    /** Work that runs later than this after its target is skipped: a 7 pm report read after midnight would only confuse. */
    const val MAX_LATE_HOURS=6L
    /** [day] is the day of the target, not of whenever the work happened to run; [skip] when it ran too late. */
    data class Plan(val day:LocalDate,val skip:Boolean)
    fun plan(now:Instant,target:Instant):Plan =
        Plan(target.atZone(CallRecordStore.KST).toLocalDate(),Duration.between(target,now)>Duration.ofHours(MAX_LATE_HOURS))
}

internal object CallDigestSchedule {
    const val EVENING="evening";const val MORNING="morning"
    fun hoursOf(context:Context)=hours(context)
    private fun hours(context:Context):Pair<Int,Int> {
        val s=runCatching { SecureSettings(CallPipelineStartup.storage(context)).snapshot() }.getOrNull()
        return (s?.optInt(PipelineSettingsKeys.EVENING_HOUR,19) ?: 19) to (s?.optInt(PipelineSettingsKeys.MORNING_HOUR,6) ?: 6)
    }
    /** The input carries the target time, so a run that happens late still reports for the right day. */
    fun request(kind:String,now:Instant,hour:Int):OneTimeWorkRequest {
        val at=CallDigestTime.next(now,hour)
        return OneTimeWorkRequestBuilder<CallDigestWork>()
            .setInitialDelay(Duration.between(now,at).toMillis(),TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("kind" to kind,"targetMs" to at.toEpochMilli())).build()
    }
    private fun enqueue(context:Context,kind:String,policy:ExistingWorkPolicy) {
        val wm=WorkManager.getInstance(context.applicationContext)
        val (evening,morning)=hours(context)
        wm.enqueueUniqueWork("ieum.calls.digest.$kind",policy,
            request(kind,Instant.now(),if(kind==EVENING) evening else morning))
    }
    fun ensure(context:Context) { enqueue(context,EVENING,ExistingWorkPolicy.KEEP);enqueue(context,MORNING,ExistingWorkPolicy.KEEP) }
    fun reschedule(context:Context) { enqueue(context,EVENING,ExistingWorkPolicy.REPLACE);enqueue(context,MORNING,ExistingWorkPolicy.REPLACE) }
    /** From inside the running worker: appended after the current run, so tomorrow's delay starts once this one finishes. */
    fun next(context:Context,kind:String) = enqueue(context,kind,ExistingWorkPolicy.APPEND_OR_REPLACE)
}

/** Before a report is composed, one try at the result pass (`ieum.calls.results`: inbox results in), in the same work and at
 * most [TIMEOUT_MS] (v3.1, DESIGN_V4 2.1-5: 3 minutes, no separate 18:45 work): the report then has the latest summaries.
 * Whatever happens (no network, an error, too slow) the report goes out as it is. */
internal object CallDigestPrefetch {
    const val TIMEOUT_MS=3*60_000L
    enum class Outcome { DONE, FAILED, TIMED_OUT }
    /** Runs [block] on its own thread and waits at most [timeoutMs]; a slow block is left to finish by itself. Never throws. */
    fun run(timeoutMs:Long=TIMEOUT_MS,block:()->Boolean):Outcome {
        val task=java.util.concurrent.FutureTask(block)
        return try {
            Thread(task,"ieum-digest-prefetch").apply { isDaemon=true }.start()
            if(task.get(timeoutMs,TimeUnit.MILLISECONDS)) Outcome.DONE else Outcome.FAILED
        } catch(_:java.util.concurrent.TimeoutException) { Outcome.TIMED_OUT }
        catch(_:InterruptedException) { Thread.currentThread().interrupt();Outcome.TIMED_OUT }
        catch(_:Throwable) { Outcome.FAILED }
    }
}

internal class CallDigestWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    companion object { const val CHANNEL="ieum.calls.digest"
        /** Self-check notices (design B4: 정리가 멈췄어요, 연결이 끊겼어요), apart from the reports. */
        const val STATUS_CHANNEL="ieum.calls.status";const val STATUS="stall"
        /** The evening self-check notice ([SelfCheckNotices]): same channel as [STATUS], its own slot, opens the settings. */
        const val SELF_CHECK="selfcheck";private const val TAG="ieum.digest" }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val kind=inputData.getString("kind") ?: CallDigestSchedule.EVENING
        val now=Instant.now()
        val target=inputData.getLong("targetMs",0L).takeIf { it>0 }?.let(Instant::ofEpochMilli)
            ?: CallDigestSchedule.hoursOf(applicationContext).let { (evening,morning) -> CallDigestTime.previous(now,if(kind==CallDigestSchedule.MORNING) morning else evening) }
        val plan=CallDigestTime.plan(now,target)
        if(plan.skip) Log.w(TAG,"$kind report skipped: ran ${Duration.between(target,now).toHours()} hours after its time")
        else try {
            val fetched=fetchResults()
            if(fetched!=CallDigestPrefetch.Outcome.DONE) Log.w(TAG,"$kind report without fresh results: $fetched")
            // The process store (r8-1): not opened again, not closed here.
            val store=kr.callbrief.IeumApplication.store(applicationContext)
            val settings=SecureSettings(CallPipelineStartup.storage(applicationContext))
            // v3.4 (4.2-4 나): the morning report says how many calls were summed up after the evening report.
            val late=if(kind==CallDigestSchedule.MORNING) runCatching { CallDigestText.lateDone(store,settings.snapshot().optString(CallDigestText.EVENING_WAITING_KEY)) }.getOrDefault(0) else 0
            // v3.5 r15-0: the morning report also says when a new version waits for its install (the home banner stays as it is).
            val update=kind==CallDigestSchedule.MORNING && runCatching { CallUpdate.installWaiting(applicationContext) }.getOrDefault(false)
            val message=if(kind==CallDigestSchedule.MORNING) CallDigestText.withUpdate(CallDigestText.withLate(CallDigestText.morning(store,plan.day),late),update) else CallDigestText.evening(store,plan.day)
            // The evening report carries its day, so a tap the next morning still opens that day's calls.
            // v3.5 r15-3: the morning notice stays away when today's report went out in full over KakaoTalk.
            val posted=if(kind==CallDigestSchedule.MORNING) CallDigestKakao.postMorning(applicationContext,message,waitForSend=true)
                else post(kind,message,plan.day.atTime(12,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli())
            if(kind==CallDigestSchedule.EVENING) {
                if(posted) runCatching { val waiting=CallDigestText.eveningWaiting(store.day(plan.day),plan.day,System.currentTimeMillis())
                    settings.update { it.put(CallDigestText.EVENING_WAITING_KEY,waiting) } }
                CallFollowupNotice.afterEvening(applicationContext) // r10-2: the day's call notices are in the report now
            } else if(posted) runCatching { settings.update { it.remove(CallDigestText.EVENING_WAITING_KEY) } }
        } catch(e:Exception) { Log.w(TAG,"$kind report not posted: ${e.javaClass.simpleName}") } // class only, never a message with names
        CallDigestSchedule.next(applicationContext,kind)
        if(kind==CallDigestSchedule.EVENING) {
            CallPipelineStartup.selfHeal(applicationContext) // A2
            if(!plan.skip) selfCheck() // B4: not in the middle of the night
        }
        Result.success()
    }
    private fun post(kind:String,m:CallDigestText.Message,dayMs:Long?=null)=CallDigestNotifier.post(applicationContext,kind,m,dayMs)

    /** One result pass, the body of `ieum.calls.results` ([ResultChain.NAME]) run here synchronously. When the hourly pass or a
     * chain round is running right now, waits for it instead of starting a second pass beside it. */
    private fun fetchResults():CallDigestPrefetch.Outcome {
        val storage=CallPipelineStartup.storage(applicationContext)
        fun running()=runCatching { val wm=WorkManager.getInstance(storage)
            listOf(CallPipelineStartup.ANALYSIS_WORK,ResultChain.NAME).any { n -> wm.getWorkInfosForUniqueWork(n).get().any { it.state==WorkInfo.State.RUNNING } } }.getOrDefault(false)
        return CallDigestPrefetch.run {
            if(running()) { var n=0;while(running() && n++<CallDigestPrefetch.TIMEOUT_MS/2_000) Thread.sleep(2_000);true }
            else CallRecordAnalysisRunner(storage).runPending()
        }
    }

    /** B4: reads the state, posts what was not told today, records it. Never throws. */
    private fun selfCheck() {
        try {
            val app=CallPipelineStartup.storage(applicationContext);val now=System.currentTimeMillis()
            val settings=SecureSettings(app);val s=settings.snapshot()
            val due=SelfCheckNotices.due(SelfCheckNotices.check(selfCheckInput(app,s,now)),now,s)
            val m=SelfCheckNotices.message(due) ?: return
            if(CallDigestNotifier.post(app,SELF_CHECK,m)) settings.update {
                SelfCheckNotices.remember(it,due,now)
                // r12-1: the recording-gap notice counts its days (1st, 2nd, then every 7th).
                if(due.any { d -> d.code==SelfCheckNotices.RECORDING_GAP }) RecordingGap.rememberNotice(it,now)
            }
        } catch(e:Exception) { Log.w(TAG,"self-check not done: ${e.javaClass.simpleName}") }
    }
    private fun selfCheckInput(app:Context,s:org.json.JSONObject,now:Long):SelfCheckNotices.Input {
        val tree=s.optString("treeUri")
        val readable=StatusLights.folderReadable(app,s)
        // B4 (review data #4): the queue by entry time, recent recordings only, and the store's real sealed file and row count.
        val store=StatusLights.read(app,s,now)
        val queued=store.queue?.newCount ?: 0;val oldestQueued=store.queue?.newOldestMs
        // Recent calls only: old first-pass records may rightly wait for the charger (as the stall notice counts them).
        val oldestWaiting=runCatching { kr.callbrief.IeumApplication.store(app).pending().filter { !FirstPass.isOld(it.dayMs,s) }.minOfOrNull { it.createdMs } }.getOrNull()
        val dbBytes=store.dbBytes
        return SelfCheckNotices.Input(now,tree.isNotBlank(),readable,s.optString(PipelineSettingsKeys.MODEL_STATE),
            queued,oldestQueued,s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0),
            s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false) && CallInboxConfig.enabled && !kr.callbrief.BuildConfig.LOCAL_PREVIEW,
            oldestWaiting,s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),dbBytes,
            s.optString(PipelineSettingsKeys.STALL_WARNED_DAY),s.optString(PipelineSettingsKeys.DISCONNECT_WARNED_DAY),store.dbRows,
            RecordingGap.noticeDue(s,now),runCatching { CallUpdate.notInstalledForADay(app,now) }.getOrDefault(false),
            runCatching { StatusLights.routineMissed(s,now) }.getOrDefault(0),s.optLong(PipelineSettingsKeys.ROUTINE_ENDED_MS,0),
            runCatching { CallUpdate.installWaiting(app) }.getOrDefault(false),
            // v3.5 r15-3: linked and on, yet no full KakaoTalk send today.
            runCatching { SelfCheckNotices.kakaoMissed(KakaoReport.linkState(app)==KakaoReport.Link.LINKED,
                s.optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true),KakaoReport.sentToday(app,now)) }.getOrDefault(false))
    }
}

/** v3.5 r15-3 (DESIGN_V5 2.3-2): the app's morning notice is the KakaoTalk report's fallback. With the switch
 * [카카오톡으로 받기] on ([PipelineSettingsKeys.MORNING_KAKAO], default on) and today's report sent in full
 * ([KakaoReport.sentToday]) the notice still comes, but quietly (r15-4 H2: KakaoTalk may raise no alert for "나와의 채팅"),
 * its first line [SENT_LINE]; on a day with the update line or the late line it comes with its sound as usual. A failed or
 * partial send, no link, the switch off or a build without a Kakao key: the notice as before. */
internal object CallDigestKakao {
    /** How long the 06:00 notice waits for a KakaoTalk send that is running right now (the 06:00 try of [ReportFetch]). */
    const val WAIT_MS=2*60_000L
    /** Test hook: whether the morning report work is running now (null: asks WorkManager). */
    @Volatile internal var sending:((Context)->Boolean)?=null
    /** True when the app notice is not needed this morning. Never throws. */
    fun covered(context:Context,nowMs:Long=System.currentTimeMillis()):Boolean = runCatching {
        if(!KakaoReport.available()) return false
        val s=SecureSettings(CallPipelineStartup.storage(context)).snapshot()
        s.optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true) && KakaoReport.sentToday(context,nowMs)
    }.getOrDefault(false)
    /** The KakaoTalk morning work may still be sending: linked, on, not sent today and its work running. */
    private fun mayStillSend(context:Context):Boolean = runCatching {
        if(!KakaoReport.available() || KakaoReport.linkState(context)!=KakaoReport.Link.LINKED) return false
        val s=SecureSettings(CallPipelineStartup.storage(context)).snapshot()
        if(!s.optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true)) return false
        (sending ?: { c -> WorkManager.getInstance(c.applicationContext).getWorkInfosForUniqueWork(ReportFetch.NAME).get().any { it.state==WorkInfo.State.RUNNING } })(context)
    }.getOrDefault(false)
    /** r15-4 (H2): the first line of the morning notice on a day the report went over KakaoTalk. */
    const val SENT_LINE="카카오톡 나와의 채팅에 아침 보고를 보냈어요"
    /** A morning notice that must be heard even on a KakaoTalk day: a new version waits, or calls were summed up late. */
    fun needsSound(m:CallDigestText.Message)=m.lines.any { it==CallDigestText.UPDATE_LINE || it.startsWith("늦게 정리된 통화") }
    /** [m] with [SENT_LINE] first (the body's text and the first of its lines). */
    fun withSentLine(m:CallDigestText.Message)=m.copy(text=SENT_LINE,lines=listOf(SENT_LINE)+m.lines)
    /** Posts the morning notice [m]; [waitForSend] (the scheduled run, off the main thread) first waits up to [WAIT_MS] for a
     * send in progress. When [covered] it goes with [SENT_LINE] first, quietly unless [needsSound]. True when it was posted. */
    fun postMorning(context:Context,m:CallDigestText.Message,waitForSend:Boolean=false):Boolean {
        if(waitForSend && !covered(context)) {
            var waited=0L
            while(waited<WAIT_MS && mayStillSend(context)) { Thread.sleep(2_000);waited+=2_000 }
        }
        if(covered(context)) return CallDigestNotifier.post(context,CallDigestSchedule.MORNING,withSentLine(m),quiet=!needsSound(m))
        return CallDigestNotifier.post(context,CallDigestSchedule.MORNING,m)
    }
}

/** Posts the morning/evening report. Also used by the device test. */
internal object CallDigestNotifier {
    /** False when notifications are not allowed (nothing was posted). */
    /** The screen a notification of [kind] opens: the morning report the check list, the self-check settings, the others the calls. */
    fun screenFor(kind:String)=when(kind) { CallDigestSchedule.MORNING -> Screen.TASKS;CallDigestWork.SELF_CHECK -> Screen.SETTINGS;else -> Screen.CALLS }
    /** The slot of a report of [kind]: one notification per kind (tag "ieum.calls.<kind>", id [ID]); a new one replaces the old. */
    const val ID=700
    /** r15-4 (H2): the group of a [quiet] report (it never gets a summary, so it never alerts). */
    const val QUIET_GROUP="ieum.calls.quiet"
    fun tag(kind:String)="ieum.calls.$kind"
    /** [dayMs]: a moment of the day the evening report tells about (now when not given). [callId]: a notice about one call opens
     * that call's page. [refresh]: the same report updated after its button was pressed (quiet, not counted as a new report).
     * [quiet] (r15-4 H2): a new report without sound or vibration (the KakaoTalk morning has told it already). */
    fun post(context:Context,kind:String,m:CallDigestText.Message,dayMs:Long?=null,callId:String?=null,refresh:Boolean=false,quiet:Boolean=false):Boolean {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CallDigestWork.CHANNEL,"이음 통화 정리",NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CallDigestWork.STATUS_CHANNEL,"이음 상태",NotificationManager.IMPORTANCE_DEFAULT))
        val channel=if(kind==CallDigestWork.STATUS||kind==CallDigestWork.SELF_CHECK) CallDigestWork.STATUS_CHANNEL else CallDigestWork.CHANNEL
        // One Activity (design G1): the notification names the screen, and the evening report its day, so a tap the next morning
        // still opens that day's calls. Back from there goes home, then out of the app, however the app was opened.
        val intent=MainActivity.intent(context,screenFor(kind),dayMs=if(kind==CallDigestSchedule.EVENING||callId!=null) dayMs ?: System.currentTimeMillis() else null,callId=callId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,(kind+(callId ?: "")).hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_stat_ieum).setOnlyAlertOnce(refresh)
            .setContentTitle(m.title).setContentText(m.text).setContentIntent(pending).setAutoCancel(true).setLocalOnly(true)
            // Whole lines, wrapped: an inbox style cuts each line with "…" (user rule: nothing cut off).
            .setStyle(Notification.BigTextStyle().setBigContentTitle(m.title).bigText(m.lines.joinToString("\n").ifEmpty { m.text }))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // Lock screen shows only the counts; the list appears once unlocked.
            .setPublicVersion(Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_stat_ieum).setContentTitle(m.title).setContentText("잠금을 풀면 내용이 보여요").build())
        // The morning report finishes its first item from the notification itself (r8-6); undo is in the app.
        if(kind==CallDigestSchedule.MORNING) m.doneId?.let { id ->
            val done=PendingIntent.getBroadcast(context,NotificationActions.requestCode(id),NotificationActions.doneIntent(context,id,m.doneRevision),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context,R.drawable.ic_stat_ieum),"다 됐어요",done).build())
        }
        // Silent on every Android from 8: a lone member of a group that alerts only for its summary makes no sound and no
        // vibration (what setSilent does on 12 and up), and the channel stays the user's own.
        if(quiet) builder.setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        manager.notify(tag(kind),ID,builder.build())
        if(!refresh && (kind==CallDigestSchedule.EVENING||kind==CallDigestSchedule.MORNING)) runCatching { SecureSettings(CallPipelineStartup.storage(context)).update { it.put(PipelineSettingsKeys.LAST_DIGEST_MS,System.currentTimeMillis()) } }
        return true
    }
}
