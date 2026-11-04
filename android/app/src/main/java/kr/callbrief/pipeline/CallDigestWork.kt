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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Notification text composed locally. The expanded notification lists the to-dos (morning) or the
 * calls with their one-line summaries (evening), so the report can be read without opening the app. */
internal object CallDigestText {
    data class Message(val title:String,val text:String,val lines:List<String> =emptyList())
    const val MAX_LINES=7
    private fun more(lines:List<String>)=if(lines.size>MAX_LINES) lines.take(MAX_LINES-1)+"외 ${lines.size-MAX_LINES+1}개" else lines
    fun evening(calls:List<CallRecord>,newTasks:Int):Message {
        val names=calls.map { it.displayName }.filter { it!="상대 미상" }.distinct().take(2)
        // Names listed without a particle after them (와/과 depends on the last sound): no wrong grammar.
        val who=if(names.isEmpty()) "" else "연락한 사람 "+names.joinToString(", ")+(if(calls.size>names.size) " 등. " else ". ")
        val lines=calls.sortedBy { it.startedMs ?: 0 }.map { c ->
            "${CallUi.time(c.startedMs)} ${c.displayName}, "+(c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c))
        }
        val waiting=calls.count { it.summary==null && it.state!=CallRecordStore.FAILED }
        val text=if(calls.isEmpty()) "오늘 정리된 통화가 없어요" else "${who}펼치면 통화별 요약이 보여요"+(if(waiting>0) ", 아직 정리 중인 통화가 ${waiting}건 있어요" else "")
        return Message("오늘 통화 ${calls.size}건, 새 체크할 일 ${newTasks}개",text,more(lines))
    }
    /** Up to [ISSUE_LINES] "진행 중인 일" lead the morning lines (design: 새벽 정리). */
    const val ISSUE_LINES=3
    /** Every open check item, oldest request first, with when it was asked for. No deadlines, no "지남" (user rule;
     * round 2: before, items without a date never reached this report). */
    fun morning(items:List<CallTodoItem>,today:LocalDate,issues:List<Issue> =emptyList()):Message {
        val lines=issues.take(ISSUE_LINES).map { "진행 중, ${it.title}"+(if(it.status.isBlank()) "" else ": ${it.status}") }+
            items.sortedBy { it.createdMs }.map { "${it.company?.let { c -> "[$c] " } ?: ""}${it.title}"+CallUi.requested(it.createdMs).let { r -> if(r.isEmpty()) "" else ", $r" }+
                (if(it.status=="needs_confirmation") ", 끝났는지 확인" else "") }
        return Message("체크할 일 ${items.size}개",if(items.isEmpty()) "체크할 일이 없어요" else lines[minOf(issues.take(ISSUE_LINES).size,lines.size-1)],more(lines))
    }
    fun evening(store:CallRecordStore,day:LocalDate)=evening(store.day(day),store.newTaskCount(day))
    fun morning(store:CallRecordStore,day:LocalDate)=morning(store.openTodos(),day,store.issues())
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

internal class CallDigestWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    companion object { const val CHANNEL="ieum.calls.digest";private const val TAG="ieum.digest" }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val kind=inputData.getString("kind") ?: CallDigestSchedule.EVENING
        val now=Instant.now()
        val target=inputData.getLong("targetMs",0L).takeIf { it>0 }?.let(Instant::ofEpochMilli)
            ?: CallDigestSchedule.hoursOf(applicationContext).let { (evening,morning) -> CallDigestTime.previous(now,if(kind==CallDigestSchedule.MORNING) morning else evening) }
        val plan=CallDigestTime.plan(now,target)
        if(plan.skip) Log.w(TAG,"$kind report skipped: ran ${Duration.between(target,now).toHours()} hours after its time")
        else try {
            val message=CallRecordStore(CallPipelineStartup.storage(applicationContext)).use {
                if(kind==CallDigestSchedule.MORNING) CallDigestText.morning(it,plan.day) else CallDigestText.evening(it,plan.day)
            }
            post(kind,message)
        } catch(e:Exception) { Log.w(TAG,"$kind report not posted: ${e.javaClass.simpleName}") } // class only, never a message with names
        CallDigestSchedule.next(applicationContext,kind)
        Result.success()
    }
    private fun post(kind:String,m:CallDigestText.Message)=CallDigestNotifier.post(applicationContext,kind,m)
}

/** Posts the morning/evening report. Also used by the device test. */
internal object CallDigestNotifier {
    fun post(context:Context,kind:String,m:CallDigestText.Message) {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CallDigestWork.CHANNEL,"이음 통화 정리",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,if(kind==CallDigestSchedule.MORNING) CallTasksActivity::class.java else TodayCallsActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,kind.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,CallDigestWork.CHANNEL).setSmallIcon(R.drawable.ic_callbrief)
            .setContentTitle(m.title).setContentText(m.text).setContentIntent(pending).setAutoCancel(true).setLocalOnly(true)
            // Whole lines, wrapped: an inbox style cuts each line with "…" (user rule: nothing cut off).
            .setStyle(Notification.BigTextStyle().setBigContentTitle(m.title).bigText(m.lines.joinToString("\n").ifEmpty { m.text }))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // Lock screen shows only the counts; the list appears once unlocked.
            .setPublicVersion(Notification.Builder(context,CallDigestWork.CHANNEL).setSmallIcon(R.drawable.ic_callbrief).setContentTitle(m.title).setContentText("잠금을 풀면 내용이 보여요").build())
            .build()
        manager.notify("ieum.calls.$kind",700,notification)
    }
}
