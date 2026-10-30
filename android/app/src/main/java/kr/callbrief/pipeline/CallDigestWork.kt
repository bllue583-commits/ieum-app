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
        val who=if(names.isEmpty()) "" else names.joinToString(", ")+(if(calls.size>names.size) " 등" else "")+"과 통화했어요. "
        val lines=calls.sortedBy { it.startedMs ?: 0 }.map { c ->
            "${CallUi.time(c.startedMs)} ${c.displayName} — "+(c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c.state))
        }
        return Message("오늘 통화 ${calls.size}건, 새 할 일 ${newTasks}개",if(calls.isEmpty()) "오늘 정리된 통화가 없어요" else "${who}펼치면 통화별 요약이 보여요",more(lines))
    }
    fun morning(items:List<CallTodoItem>,today:LocalDate):Message {
        val overdue=items.count { it.due<today.toString() }
        val lines=items.sortedBy { it.due }.map { "• ${it.company?.let { c -> "[$c] " } ?: ""}${it.title} — ${CallUi.dueWords(it.due)}"+(if(it.status=="needs_confirmation") " (확인 필요)" else "") }
        return Message("오늘 할 일 ${items.size}개"+(if(overdue>0) " (지난 기한 ${overdue}개)" else ""),
            if(items.isEmpty()) "오늘까지 할 일이 없어요" else lines.first().removePrefix("• "),more(lines))
    }
    fun evening(store:CallRecordStore,day:LocalDate)=evening(store.day(day),store.newTaskCount(day))
    fun morning(store:CallRecordStore,day:LocalDate)=morning(store.dueItems(day),day)
}

/** Next KST run time for a given hour. */
internal object CallDigestTime {
    fun next(now:Instant,hour:Int):Instant {
        val local=now.atZone(CallRecordStore.KST)
        var target=local.toLocalDate().atTime(hour.coerceIn(0,23),0).atZone(CallRecordStore.KST)
        if(!target.toInstant().isAfter(now)) target=target.plusDays(1)
        return target.toInstant()
    }
}

internal object CallDigestSchedule {
    const val EVENING="evening";const val MORNING="morning"
    private fun hours(context:Context):Pair<Int,Int> {
        val s=runCatching { SecureSettings(CallPipelineStartup.storage(context)).snapshot() }.getOrNull()
        return (s?.optInt(PipelineSettingsKeys.EVENING_HOUR,19) ?: 19) to (s?.optInt(PipelineSettingsKeys.MORNING_HOUR,6) ?: 6)
    }
    fun request(kind:String,now:Instant,hour:Int):OneTimeWorkRequest=OneTimeWorkRequestBuilder<CallDigestWork>()
        .setInitialDelay(Duration.between(now,CallDigestTime.next(now,hour)).toMillis(),TimeUnit.MILLISECONDS)
        .setInputData(workDataOf("kind" to kind)).build()
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
    companion object { const val CHANNEL="ieum.calls.digest" }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val kind=inputData.getString("kind") ?: CallDigestSchedule.EVENING
        runCatching {
            val today=CallRecordStore.kstDay(System.currentTimeMillis())
            val message=CallRecordStore(CallPipelineStartup.storage(applicationContext)).use {
                if(kind==CallDigestSchedule.MORNING) CallDigestText.morning(it,today) else CallDigestText.evening(it,today)
            }
            post(kind,message)
        }
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
            .setStyle(Notification.InboxStyle().setBigContentTitle(m.title).also { st -> m.lines.forEach { st.addLine(it) } })
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // Lock screen shows only the counts; the list appears once unlocked.
            .setPublicVersion(Notification.Builder(context,CallDigestWork.CHANNEL).setSmallIcon(R.drawable.ic_callbrief).setContentTitle(m.title).setContentText("잠금을 풀면 내용이 보여요").build())
            .build()
        manager.notify("ieum.calls.$kind",700,notification)
    }
}
