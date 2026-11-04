package kr.callbrief.pipeline

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.*
import kr.callbrief.BuildConfig
import kr.callbrief.R
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Self-update: CI publishes app/version.json + the APK into the private inbox repo on every push.
 * The phone checks every few hours on Wi-Fi, downloads a newer APK, verifies its SHA-256 and shows
 * "새 버전" (home banner + notification). One tap installs it (Android always asks once to confirm). */
internal object CallUpdate {
    /** "앱 버전 v2.0 (빌드 1050)", plus the downloaded next version: the name from docs/VERSION, the build number to tell builds apart. */
    fun versionLine(name:String,code:Int,next:Info?):String {
        fun label(n:String,c:Int)=if(Regex("""\d+\.\d+""").matches(n)) "v$n (빌드 $c)" else "v$c"
        val nextName=next?.let { if(it.versionName.startsWith("v") && Regex("""v\d+\.\d+""").matches(it.versionName)) "${it.versionName} (빌드 ${it.versionCode})" else "v${it.versionCode}" }
        return "앱 버전 "+label(name,code)+(nextName?.let { ", 새 버전 $it 준비됨" } ?: "")
    }
    data class Info(val versionCode:Int,val versionName:String,val apk:String,val sha256:String,val notes:String)
    private const val PREFS="ieum.update";private const val CHANNEL="ieum.update"
    fun parse(text:String):Info { val o=JSONObject(text);return Info(o.getInt("versionCode"),o.optString("versionName"),o.getString("apk"),o.getString("sha256").lowercase(),o.optString("notes")) }
    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun file(c:Context)=File(c.applicationContext.cacheDir,"update/ieum.apk")
    /** A downloaded, verified newer version waiting to be installed, or null. */
    fun ready(c:Context):Info? {
        val p=prefs(c);val code=p.getInt("code",0)
        if(code<=BuildConfig.VERSION_CODE || !file(c).isFile) return null
        return Info(code,p.getString("name","") ?: "","",p.getString("sha","") ?: "",p.getString("notes","") ?: "")
    }
    fun sha256(f:File)=f.inputStream().use { i -> val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(1 shl 16)
        while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)} }

    /** Returns true when a newer verified APK is ready. */
    fun check(c:Context,store:CallInboxStore):Boolean {
        val info=parse(store.get("app/version.json"))
        if(info.versionCode<=BuildConfig.VERSION_CODE) return false
        if(ready(c)?.versionCode==info.versionCode) return true
        val f=file(c);f.parentFile?.mkdirs();val tmp=File(f.parentFile,"download.tmp")
        store.download(info.apk,tmp)
        check(sha256(tmp)==info.sha256){"UPDATE_SHA"}
        check(tmp.renameTo(f) || run { tmp.copyTo(f,true);tmp.delete() }){"UPDATE_MOVE"}
        prefs(c).edit().putInt("code",info.versionCode).putString("name",info.versionName).putString("sha",info.sha256).putString("notes",info.notes).apply()
        notifyReady(c,info)
        return true
    }

    private fun notifyReady(c:Context,info:Info) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val m=c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CHANNEL,"이음 업데이트",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(c,1,Intent(c,HomeActivity::class.java).putExtra("install_update",true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        m.notify("ieum.update",701,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_callbrief)
            .setContentTitle("이음 새 버전이 준비됐어요").setContentText(info.notes.ifBlank { "눌러서 업데이트하세요" })
            .setContentIntent(open).setAutoCancel(true).setLocalOnly(true).build())
    }

    /** Starts the system install. First time, Android asks to allow installs from this app. */
    fun install(a:Activity) {
        val f=file(a);if(!f.isFile)return
        if(!a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}")));return
        }
        val installer=a.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(a.packageName) }
        val id=installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("ieum",0,f.length()).use { out -> f.inputStream().use { it.copyTo(out) };s.fsync(out) }
            val status=PendingIntent.getBroadcast(a,id,Intent(a,UpdateStatusReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(status.intentSender)
        }
    }

    fun schedule(c:Context) {
        if(!CallInboxConfig.enabled)return
        WorkManager.getInstance(c.applicationContext).enqueueUniquePeriodicWork("ieum.update",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CallUpdateWork>(6,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
    }
}

internal class CallUpdateWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val store=CallInboxConfig.store() ?: return Result.success()
        return try { CallUpdate.check(applicationContext,store);Result.success() }
        catch(_:java.io.IOException) { Result.retry() } catch(_:Exception) { Result.success() }
    }
}

/** Receives the installer status; when Android needs the user's confirmation, shows its dialog. */
class UpdateStatusReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,-99)==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
