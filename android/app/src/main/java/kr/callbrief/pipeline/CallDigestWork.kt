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

/** Notification text composed locally from stored counts. No call content beyond counts and up to 2 names. */
internal object CallDigestText {
    data class Message(val title:String,val text:String)
    fun evening(calls:List<CallRecord>,newTasks:Int):Message {
        val names=calls.map { it.displayName }.filter { it!="상대 미상" }.distinct().take(2)
        val who=if(names.isEmpty()) "" else " · "+names.joinToString(", ")+(if(calls.size>names.size) " 등" else "")
        return Message("오늘 통화 ${calls.size}건 · 새 할 일 ${newTasks}개","탭해서 오늘의 통화를 확인하세요$who")
    }
    fun morning(items:List<CallTodoItem>,today:LocalDate):Message {
        val overdue=items.count { it.due<today.toString() }
        return Message("오늘 할 일 ${items.size}개"+(if(overdue>0) " (지난 기한 ${overdue}개)" else ""),"탭해서 오늘 할 일을 확인하세요")
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
    private fun post(kind:String,m:CallDigestText.Message) {
        val context=applicationContext
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"이음 통화 정리",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,TodayCallsActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,kind.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_callbrief)
            .setContentTitle(m.title).setContentText(m.text).setContentIntent(pending).setAutoCancel(true).setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
        manager.notify("ieum.calls.$kind",700,notification)
    }
}
