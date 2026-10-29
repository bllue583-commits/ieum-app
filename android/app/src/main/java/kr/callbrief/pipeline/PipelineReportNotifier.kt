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
import kr.callbrief.AndroidLocalReportNotifier
import kr.callbrief.R

/** Local notification on the existing daily-report channel. Content never includes call content. */
internal class PipelineReportNotifier(private val context:Context) {
    companion object { const val TEXT="아침 보고가 도착했어요" }
    fun ready():Boolean {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        val manager=context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(AndroidLocalReportNotifier.CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    fun post(reportDate:String):Boolean {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        if(!ready())return false
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(AndroidLocalReportNotifier.CHANNEL,"이음 일일보고",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,PipelineReportActivity::class.java).putExtra("report_date",reportDate)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,("pipeline-report-"+reportDate).hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,AndroidLocalReportNotifier.CHANNEL).setSmallIcon(R.drawable.ic_callbrief)
            .setContentTitle(TEXT).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
        manager.notify("ieum.pipeline.report",610,notification);return true
    }
}
