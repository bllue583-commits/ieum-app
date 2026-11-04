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
    data class Info(val versionCode:Int,val versionName:String,val apk:String,val sha256:String,val notes:String,val size:Long=0)
    private const val PREFS="ieum.update";private const val CHANNEL="ieum.update"
    /** When the update file was last read (ms); after this long without a read, the next check may use mobile data too (design H3). */
    const val STALE_MS=6*3_600_000L
    private const val CHECKED="checked_ms"
    fun lastChecked(c:Context):Long=prefs(c).getLong(CHECKED,0)
    fun stale(now:Long,last:Long)=now-last>=STALE_MS
    /** Wi-Fi only (the APK is about 5 MB) until the last check is [STALE_MS] old, then any connection. */
    fun networkFor(now:Long,last:Long)=if(stale(now,last)) NetworkType.CONNECTED else NetworkType.UNMETERED
    /** "STATUS_FAILURE_STORAGE (6): not enough space": the installer's result as it is kept in [PipelineSettingsKeys.LAST_UPDATE_ERROR]. */
    fun errorText(status:Int,message:String?):String {
        val name=when(status) {
            PackageInstaller.STATUS_FAILURE -> "STATUS_FAILURE";PackageInstaller.STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED";PackageInstaller.STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE";PackageInstaller.STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE";PackageInstaller.STATUS_FAILURE_TIMEOUT -> "STATUS_FAILURE_TIMEOUT"
            else -> "STATUS" }
        return "$name ($status)"+(message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": ${it.take(80)}" } ?: "")
    }
    /** What a failed install means to the user: why, then what to do. [PipelineSettingsKeys.LAST_UPDATE_ERROR] keeps the code for the
     * heartbeat; screens show only these words. */
    data class ErrorWords(val reason:String,val action:String) { val sentence get()="$reason $action" }
    private const val AGAIN="홈에서 지금 업데이트를 다시 눌러 주세요."
    /** The stored code ("STATUS_FAILURE_STORAGE (6): ...") in plain Korean, or null when it is no problem to show: nothing stored,
     * or the user closed the install window (STATUS_FAILURE_ABORTED). Never echoes the code or the installer's message. */
    fun errorWords(stored:String?):ErrorWords? {
        val code=stored?.trim()?.substringBefore(' ')?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return null
        return when(code) {
            "STATUS_FAILURE_ABORTED" -> null
            "STATUS_FAILURE_STORAGE" -> ErrorWords("저장 공간이 부족해 설치하지 못했어요.","사진이나 쓰지 않는 앱을 조금 지운 뒤 $AGAIN")
            "STATUS_FAILURE_BLOCKED" -> ErrorWords("휴대폰이 설치를 막았어요.","관리하는 분께 알려 주세요.")
            "STATUS_FAILURE_CONFLICT","STATUS_FAILURE_INCOMPATIBLE","STATUS_FAILURE_INVALID" -> ErrorWords("새 버전이 이 휴대폰에 맞지 않아 설치하지 못했어요.","관리하는 분께 알려 주세요.")
            else -> ErrorWords("설치가 중간에 멈췄어요.",AGAIN)
        }
    }
    /** The user cancelled the system install window: not an error (no amber light, nothing for the watchdog). */
    fun isUserCancel(status:Int)=status==PackageInstaller.STATUS_FAILURE_ABORTED
    internal fun saveError(c:Context,text:String?) {
        runCatching { kr.callbrief.SecureSettings(CallPipelineStartup.storage(c)).update { if(text.isNullOrBlank()) it.remove(PipelineSettingsKeys.LAST_UPDATE_ERROR) else it.put(PipelineSettingsKeys.LAST_UPDATE_ERROR,text) } }
    }
    /** version.json; "size" (bytes) is optional, 0 when it is not there. */
    fun parse(text:String):Info { val o=JSONObject(text);return Info(o.getInt("versionCode"),o.optString("versionName"),o.getString("apk"),o.getString("sha256").lowercase(),o.optString("notes"),o.optLong("size",0)) }
    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun file(c:Context)=File(c.applicationContext.cacheDir,"update/ieum.apk")
    /** A downloaded, verified newer version waiting to be installed, or null. */
    fun ready(c:Context):Info? {
        val p=prefs(c);val code=p.getInt("code",0)
        if(code<=BuildConfig.VERSION_CODE || !file(c).isFile) return null
        return Info(code,p.getString("name","") ?: "","",p.getString("sha","") ?: "",p.getString("notes","") ?: "",p.getLong("size",0))
    }
    /** The newer version the last check saw (downloaded or not), or null when the phone already runs the newest. */
    fun available(c:Context):Info? {
        val p=prefs(c);val code=p.getInt(A_CODE,0)
        if(code<=BuildConfig.VERSION_CODE) return null
        return Info(code,p.getString(A_NAME,"") ?: "","",p.getString(A_SHA,"") ?: "",p.getString(A_NOTES,"") ?: "",p.getLong(A_SIZE,0))
    }
    /** "v2.5" from docs/VERSION, or "v1061" for an old-style name. */
    fun shownName(i:Info)=if(Regex("""v\d+\.\d+""").matches(i.versionName)) i.versionName else "v${i.versionCode}"
    /** "약 7MB": the APK size rounded up, about 7 MB while the size is still unknown. */
    fun sizeWords(bytes:Long)="약 "+(if(bytes>0) ((bytes+999_999)/1_000_000).coerceAtLeast(1) else 7)+"MB"
    fun sha256(f:File)=f.inputStream().use { i -> val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(1 shl 16)
        while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)} }

    // ---- getting the next version: what settings and home show while it comes (round 6 v2.5) ----
    enum class Stage { IDLE,WAITING,DOWNLOADING,DONE,FAILED }
    /** Where getting the next version stands. [percent] counts by whole percent; [reason] is the Korean words of a failure. */
    data class Fetch(val stage:Stage,val percent:Int=0,val reason:String="",val metered:Boolean=false)
    private const val A_CODE="avail_code";private const val A_NAME="avail_name";private const val A_SHA="avail_sha"
    private const val A_NOTES="avail_notes";private const val A_SIZE="avail_size"
    private const val F_STAGE="fetch_stage";private const val F_PCT="fetch_pct";private const val F_REASON="fetch_reason"
    private const val F_MS="fetch_ms";private const val F_INSTALL="fetch_install";private const val F_METERED="fetch_metered"
    /** No progress for this long while "받는 중": the download died with the app; shown as stopped so [다시 받기] comes back. */
    const val STALL_MS=3*60_000L
    const val NET_WORDS="인터넷이 끊겨 받지 못했어요.";const val SPACE_WORDS="저장 공간이 부족해 받지 못했어요."
    const val BROKEN_WORDS="받은 파일이 깨져 있어 지웠어요.";const val STALL_WORDS="받기가 중간에 멈췄어요."
    const val OTHER_WORDS="새 버전을 받지 못했어요."
    fun fetchState(c:Context,now:Long=System.currentTimeMillis()):Fetch {
        if(ready(c)!=null) return Fetch(Stage.DONE,100)
        val p=prefs(c)
        val stage=runCatching { Stage.valueOf(p.getString(F_STAGE,null) ?: "IDLE") }.getOrDefault(Stage.IDLE).let { if(it==Stage.DONE) Stage.IDLE else it }
        val metered=p.getBoolean(F_METERED,false)
        if(stage==Stage.DOWNLOADING && now-p.getLong(F_MS,now)>STALL_MS) return Fetch(Stage.FAILED,reason=STALL_WORDS)
        return Fetch(stage,p.getInt(F_PCT,0),p.getString(F_REASON,"") ?: "",metered)
    }
    private fun stage(c:Context,s:Stage,percent:Int=0,reason:String="") {
        prefs(c).edit().putString(F_STAGE,s.name).putInt(F_PCT,percent).putString(F_REASON,reason).putLong(F_MS,System.currentTimeMillis()).apply()
    }
    /** True once, after a download the user asked for from [지금 업데이트] has finished: the screen goes on to the install. */
    fun takeInstallWish(c:Context):Boolean { val p=prefs(c);if(!p.getBoolean(F_INSTALL,false)) return false;p.edit().putBoolean(F_INSTALL,false).apply();return true }
    fun wantsInstall(c:Context)=prefs(c).getBoolean(F_INSTALL,false)
    /** Whole percent from bytes; -1 while the size is unknown. */
    fun percent(got:Long,total:Long)=if(total<=0) -1 else (got*100/total).toInt().coerceIn(0,100)
    private fun failWords(e:Throwable)=when {
        e.message=="UPDATE_SHA" || e.message=="UPDATE_SIZE" -> BROKEN_WORDS
        e is java.io.IOException && (e.message ?: "").let { it.contains("ENOSPC") || it.contains("No space",true) } -> SPACE_WORDS
        e is java.io.IOException -> NET_WORDS
        else -> OTHER_WORDS }
    private val lock=Any()

    /** Returns true when a newer verified APK is ready. One at a time: the background check and [지금 받기] share the file. */
    fun check(c:Context,store:CallInboxStore):Boolean = synchronized(lock) {
        val info=try { parse(store.get("app/version.json")) }
            catch(e:Exception) { if(fetchState(c).stage==Stage.WAITING) stage(c,Stage.FAILED,reason=failWords(e));throw e } // only when the user is watching
        val p=prefs(c)
        p.edit().putLong(CHECKED,System.currentTimeMillis()).apply()
        if(info.versionCode<=BuildConfig.VERSION_CODE) { saveError(c,null);p.edit().remove(A_CODE).apply();stage(c,Stage.IDLE);return false } // up to date: an old install failure is over
        p.edit().putInt(A_CODE,info.versionCode).putString(A_NAME,info.versionName).putString(A_SHA,info.sha256).putString(A_NOTES,info.notes)
            .apply { if(info.size>0) putLong(A_SIZE,info.size) else if(p.getInt(A_CODE,0)!=info.versionCode) remove(A_SIZE) }.apply()
        if(ready(c)?.versionCode==info.versionCode) { stage(c,Stage.DONE,100);return true }
        val f=file(c);f.parentFile?.mkdirs();val tmp=File(f.parentFile,"download.tmp")
        stage(c,Stage.DOWNLOADING,0)
        try {
            var shown=0
            store.download(info.apk,tmp) { got,total ->
                val whole=if(info.size>0) info.size else total
                if(total>0 && info.size<=0 && p.getLong(A_SIZE,0)!=total) p.edit().putLong(A_SIZE,total).apply()
                val pct=percent(got,whole).coerceAtMost(99)
                if(pct>shown) { shown=pct;stage(c,Stage.DOWNLOADING,pct) } // a write per whole percent, not per block
            }
            check(info.size<=0 || tmp.length()==info.size){"UPDATE_SIZE"}
            check(sha256(tmp)==info.sha256){"UPDATE_SHA"}
            check(tmp.renameTo(f) || run { tmp.copyTo(f,true);tmp.delete() }){"UPDATE_MOVE"}
        } catch(e:Exception) { tmp.delete();stage(c,Stage.FAILED,reason=failWords(e));throw e }
        p.edit().putInt("code",info.versionCode).putString("name",info.versionName).putString("sha",info.sha256).putString("notes",info.notes).putLong("size",f.length()).apply()
        stage(c,Stage.DONE,100)
        notifyReady(c,info)
        return true
    }

    /** This build has no mailbox to get versions from (the preview build): nothing is coming. */
    internal fun noStore(c:Context) { if(fetchState(c).stage==Stage.WAITING) stage(c,Stage.IDLE) }
    /** For tests: replaces the WorkManager start of [fetchNow]. */
    @Volatile internal var startHook:((Context)->Unit)?=null
    /** [지금 받기]: starts getting the newer version now, on any connection (the user asked). [installAfter]: go on to install when done. */
    fun fetchNow(c:Context,installAfter:Boolean,metered:Boolean=false) {
        val running=fetchState(c).stage==Stage.DOWNLOADING
        prefs(c).edit().putBoolean(F_INSTALL,installAfter || wantsInstall(c)).putBoolean(F_METERED,metered).apply()
        if(running) return // already coming: the screen only follows it
        stage(c,Stage.WAITING)
        startHook?.let { it(c);return }
        runCatching {
            WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("ieum.update.now",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CallUpdateWork>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build())
        }.onFailure { stage(c,Stage.FAILED,reason=OTHER_WORDS) }
    }
    /** The downloaded file is the one version.json named: same size (when known) and SHA-256. */
    fun verified(c:Context):Boolean {
        val r=ready(c) ?: return false;val f=file(c)
        return (r.size<=0 || f.length()==r.size) && runCatching { sha256(f) }.getOrNull()==r.sha256
    }
    /** A file that does not match is deleted and its version forgotten, so it is got again. */
    internal fun discard(c:Context) { file(c).delete();prefs(c).edit().remove("code").apply() }

    private fun notifyReady(c:Context,info:Info) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val m=c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CHANNEL,"이음 업데이트",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(c,1,Intent(c,HomeActivity::class.java).putExtra("install_update",true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        m.notify("ieum.update",701,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
            .setContentTitle("이음 새 버전이 준비됐어요").setContentText(info.notes.ifBlank { "눌러서 업데이트하세요" })
            .setContentIntent(open).setAutoCancel(true).setLocalOnly(true).build())
    }

    /** Starts the system install. First time, Android asks to allow installs from this app. When there is no file yet, or the
     * file does not match version.json, it starts getting the version now (and installs when done) and returns false. */
    fun install(a:Activity,metered:Boolean=false):Boolean {
        if(ready(a)==null) { fetchNow(a,installAfter=true,metered=metered);return false }
        if(!verified(a)) { discard(a);fetchNow(a,installAfter=true,metered=metered);return false }
        val f=file(a)
        if(!a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}")));return true
        }
        val installer=a.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(a.packageName)
            // Tries a silent update first; when Android refuses, the confirmation dialog still comes through the status receiver.
            if(Build.VERSION.SDK_INT>=31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id=installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("ieum",0,f.length()).use { out -> f.inputStream().use { it.copyTo(out) };s.fsync(out) }
            val status=PendingIntent.getBroadcast(a,id,Intent(a,UpdateStatusReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(status.intentSender)
        }
        return true
    }

    /** Every 6 hours on Wi-Fi; a second 6-hourly work on any connection checks only when the last check is 6 hours old. */
    fun schedule(c:Context) {
        if(!CallInboxConfig.enabled)return
        val wm=WorkManager.getInstance(c.applicationContext)
        wm.enqueueUniquePeriodicWork("ieum.update",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CallUpdateWork>(6,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
        wm.enqueueUniquePeriodicWork("ieum.update.any",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CallUpdateWork>(6,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setInputData(workDataOf(CallUpdateWork.ONLY_IF_STALE to true)).build())
    }
    /** One more check 30 seconds after the app starts (design H3), on mobile data too when the last check is 6 hours old. */
    fun checkSoon(c:Context,now:Long=System.currentTimeMillis()) {
        if(!CallInboxConfig.enabled)return
        runCatching {
            WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("ieum.update.open",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CallUpdateWork>().setInitialDelay(30,TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(networkFor(now,lastChecked(c))).build()).build())
        }
    }
}

internal class CallUpdateWork(context:Context,params:WorkerParameters):Worker(context,params) {
    companion object { const val ONLY_IF_STALE="onlyIfStale" }
    override fun doWork():Result {
        // The any-connection work stays out of the way while the Wi-Fi one keeps the check fresh.
        if(inputData.getBoolean(ONLY_IF_STALE,false) && !CallUpdate.stale(System.currentTimeMillis(),CallUpdate.lastChecked(applicationContext))) return Result.success()
        val store=CallInboxConfig.store() ?: return Result.success().also { CallUpdate.noStore(applicationContext) }
        return try { CallUpdate.check(applicationContext,store);Result.success() }
        catch(_:java.io.IOException) { Result.retry() } catch(_:Exception) { Result.success() }
    }
}

/** Receives the installer status; when Android needs the user's confirmation, shows its dialog; a failed install is kept in
 * [PipelineSettingsKeys.LAST_UPDATE_ERROR] for the settings update light. */
class UpdateStatusReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,-99)
        when {
            status==PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            status==PackageInstaller.STATUS_SUCCESS || status==-99 || CallUpdate.isUserCancel(status) -> {}
            else -> {
                val text=CallUpdate.errorText(status,intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                val pending:PendingResult? =goAsync()
                Thread { try { CallUpdate.saveError(context.applicationContext,text) } finally { pending?.finish() } }.start()
            }
        }
    }
}
