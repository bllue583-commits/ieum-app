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
 * The phone checks when the app opens and every 6 hours on any connection (v3.1, user's decision 11/5: mobile data too),
 * downloads a newer APK, verifies its SHA-256 and installs it in the background (design 3.3). When Android still wants a tap,
 * the notification and the home banner lead to it. [state] says where it stands for the settings row and the home banner.
 *
 * Installing without a tap (DESIGN_V4 2.2-4) needs all of these; when one is missing Android answers
 * STATUS_PENDING_USER_ACTION and one tap on the notification finishes it ([PipelineSettingsKeys.LAST_UPDATE_ERROR] =
 * [USER_ACTION_REQUIRED] for the watchdog):
 *  - Android 12 (API 31) or later ([installInBackground] does not try below that);
 *  - the manifest asks for android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION (from v3.1; so v3.0 to v3.1 still needs a tap);
 *  - the session updates this app itself (setAppPackageName = our package) with setRequireUserAction(USER_ACTION_NOT_REQUIRED);
 *  - installs from this app are allowed (canRequestPackageInstalls(); otherwise [INSTALL_SOURCE_NOT_ALLOWED] and the optional
 *    setup step [setupStep]);
 *  - the new APK's targetSdk is at least the device's floor for silent updates (Android 15: 33, Android 16: 34; ours is 36);
 *  - no other store holds update ownership of the app (installed by adb or by ourselves, never by a store). */
internal object CallUpdate {
    // ---- v3.1 contract (DESIGN_V4 2.2-4, 2.2-5; key commit). r12-2 fills the bodies, r12-5 puts them on screen. ----
    /** What the settings "앱 버전" row and the home banner say about a new version. [phase]: none | checking | offline |
     * downloading | ready | waitingConfirm | failed. [words]: the Korean sentence to show (empty for none). [percent]: -1 unknown.
     * [canInstall]: show [지금 업데이트]. */
    data class UpdateState(val phase:String="none",val words:String="",val percent:Int=-1,val canInstall:Boolean=false)
    /** r12-2: the state now, from [fetchState], the download progress and the last error. Cheap enough for the screen's
     * one-second ticker: plain preferences, one settings read for the last install error. */
    fun state(c:Context):UpdateState {
        val app=c.applicationContext;val now=System.currentTimeMillis()
        val pr=progress(app);val phase=pr?.optString("phase").orEmpty()
        val err=storedErrorText(app)
        val code=errorCode(err)
        val ready=ready(app)
        if(ready!=null) {
            if(code==USER_ACTION_REQUIRED || phase==P_WAITING_CONFIRM) return UpdateState(P_WAITING_CONFIRM,CONFIRM_WORDS,100,true)
            if(sessionId(app)!=0) return UpdateState(P_WAITING_CONFIRM,INSTALLING_WORDS,100,false)
            errorWords(err)?.takeIf { code!=INSTALL_SOURCE_NOT_ALLOWED }?.let { return UpdateState(P_FAILED,it.sentence,100,true) }
            return UpdateState(P_READY,"새 버전(${shownName(ready)})이 준비됐어요. 지금 업데이트를 누르면 설치돼요.",100,true)
        }
        val f=fetchState(app,now);val avail=available(app)
        when(f.stage) {
            Stage.DOWNLOADING -> {
                val done=pr?.optLong("doneBytes",-1) ?: -1L
                val total=(pr?.optLong("totalBytes",0) ?: 0L).takeIf { it>0 } ?: (avail?.size ?: 0L)
                return UpdateState(P_DOWNLOADING,"새 버전을 받는 중이에요. "+progressWords(f.percent,done,total),f.percent,false)
            }
            Stage.FAILED -> return UpdateState(P_FAILED,f.reason.ifBlank { OTHER_WORDS },-1,avail!=null)
            // A known version waiting to come says which one (integration: clearer than "확인하는 중" for a 50대 reader).
            Stage.WAITING -> return if(!online(app)) UpdateState(P_OFFLINE,OFFLINE_WORDS)
                else if(avail!=null) UpdateState(P_CHECKING,"새 버전(${shownName(avail)})을 받을 준비 중이에요.") else UpdateState(P_CHECKING,CHECKING_WORDS)
            else -> {}
        }
        val fresh=pr!=null && now-pr.optLong("atMs",0)<CHECKING_MS
        if(fresh && phase==P_CHECKING) return UpdateState(P_CHECKING,CHECKING_WORDS)
        if(phase==P_OFFLINE && !online(app)) return UpdateState(P_OFFLINE,OFFLINE_WORDS)
        if(avail!=null) return UpdateState(P_READY,"새 버전(${shownName(avail)})이 나왔어요. 지금 업데이트를 누르면 받아서 설치해요.",-1,true)
        if(phase==P_FAILED && fresh) return UpdateState(P_FAILED,CHECK_FAILED_WORDS)
        errorWords(err)?.takeIf { code!=INSTALL_SOURCE_NOT_ALLOWED && code!=USER_ACTION_REQUIRED }?.let { return UpdateState(P_FAILED,it.sentence) }
        return UpdateState()
    }
    /** r12-2: true while this app may not install updates by itself (canRequestPackageInstalls() false) — the optional
     * first-setup step "새 버전을 저절로 설치하기". */
    fun setupStep(c:Context):Boolean = runCatching { !c.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    /** r12-2: opens ACTION_MANAGE_UNKNOWN_APP_SOURCES for this app (the setup step's button). */
    fun openInstallSourceSettings(a:Activity) {
        runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}"))) }
            .onFailure { runCatching { a.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }
    /** r12-2: [지금 확인] — checks for a new version now, off the main thread; [done] runs on the main thread with the new state.
     * A newer version is downloaded (and installed in the background) in the same go; offline, a check waits for the connection.
     * Also what the app runs when it opens (v3.1, design 2.2-5). */
    fun checkNow(c:Context,done:(UpdateState)->Unit={}) {
        val app=c.applicationContext
        val main=android.os.Handler(android.os.Looper.getMainLooper())
        fun finish() { val s=runCatching { state(app) }.getOrDefault(UpdateState());main.post { done(s) } }
        val hook=storeHook
        if(hook==null && !CallInboxConfig.enabled) { finish();return }
        progress(app,P_CHECKING)
        Thread({
            try {
                val store=if(hook!=null) hook() else CallInboxConfig.store()
                when {
                    store==null -> { noStore(app);progress(app,P_NONE) }
                    !online(app) -> { progress(app,P_OFFLINE);checkSoon(app) }
                    else -> { settleIfInstalled(app);check(app,store) }
                }
            } catch(e:java.io.IOException) {
                checkSoon(app) // once more when the connection is there
            } catch(e:Exception) { progress(app,P_FAILED) }
            finish()
        },"ieum-update-check").start()
    }
    /** r12-2 for r12-1's self-check notice: a verified new version has waited more than a day to be installed. */
    fun notInstalledForADay(c:Context,now:Long=System.currentTimeMillis()):Boolean = runCatching {
        val s=settings(c.applicationContext).snapshot()
        val code=s.optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,0);val since=s.optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,0)
        code>BuildConfig.VERSION_CODE && since>0 && now-since>DAY_MS
    }.getOrDefault(false)

    // ---- v3.1 update progress ([PipelineSettingsKeys.UPDATE_PROGRESS]) ----
    const val P_NONE="none";const val P_CHECKING="checking";const val P_OFFLINE="offline";const val P_DOWNLOADING="downloading"
    const val P_READY="ready";const val P_WAITING_CONFIRM="waitingConfirm";const val P_FAILED="failed"
    /** [PipelineSettingsKeys.LAST_UPDATE_ERROR] codes of v3.1: Android wants a tap / installs from this app are not allowed. */
    const val USER_ACTION_REQUIRED="USER_ACTION_REQUIRED";const val INSTALL_SOURCE_NOT_ALLOWED="INSTALL_SOURCE_NOT_ALLOWED"
    const val CHECKING_WORDS="새 버전이 있는지 확인하는 중이에요.";const val OFFLINE_WORDS="인터넷 연결을 기다리는 중이에요. 연결되면 바로 확인해요."
    const val CONFIRM_WORDS="설치 확인을 기다리는 중이에요. 알림이나 지금 업데이트를 눌러 설치를 마쳐 주세요."
    const val INSTALLING_WORDS="새 버전을 설치하는 중이에요."
    const val CHECK_FAILED_WORDS="새 버전을 확인하지 못했어요. 잠시 뒤 다시 확인해 주세요."
    private const val DAY_MS=24*3_600_000L
    /** A "checking" older than this is over (the process died in the middle). */
    private const val CHECKING_MS=2*60_000L
    /** How often the download writes its progress (design 2.2-5: every second). */
    const val PROGRESS_EVERY_MS=1_000L
    /** For tests: the clock of the progress writes, and the inbox store of [checkNow]. */
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    @Volatile internal var storeHook:(()->CallInboxStore?)?=null
    private const val PROGRESS="progress"
    /** The last progress written, or null. */
    internal fun progress(c:Context):JSONObject? = prefs(c).getString(PROGRESS,null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    /** Writes the progress to the plain preferences (the screen reads it every second) and to settings [PipelineSettingsKeys.UPDATE_PROGRESS]. */
    internal fun progress(c:Context,phase:String,code:Int=0,done:Long=-1,total:Long=-1) {
        val o=JSONObject().put("phase",phase).put("code",code).put("doneBytes",done).put("totalBytes",total).put("atMs",clock())
        prefs(c).edit().putString(PROGRESS,o.toString()).apply()
        runCatching { settings(c).update { it.put(PipelineSettingsKeys.UPDATE_PROGRESS,o.toString()) } }
    }
    /** "42% (3.1MB / 7.0MB)"; only the percent while the sizes are unknown. Also for the speech model ("21% (123MB / 574MB)"). */
    fun progressWords(percent:Int,done:Long,total:Long):String {
        val pct=if(percent>=0) percent else if(total>0 && done>=0) percent(done,total) else -1
        val head=if(pct>=0) "$pct%" else ""
        if(total<=0 || done<0) return head
        return (if(head.isEmpty()) "" else "$head ")+"(${mb(done)} / ${mb(total)})"
    }
    /** "3.1MB" under 10 MB, "123MB" above (decimal megabytes, like [sizeWords]). */
    fun mb(bytes:Long):String { val m=bytes/1_000_000.0;return if(m<10) "%.1fMB".format(java.util.Locale.ROOT,m) else "${Math.round(m)}MB" }
    @Suppress("DEPRECATION")
    internal fun online(c:Context):Boolean = runCatching {
        c.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetworkInfo?.isConnected==true }.getOrDefault(true)
    private fun storedErrorText(c:Context)=runCatching { settings(c).snapshot().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR) }.getOrDefault("")
    private fun errorCode(text:String)=text.trim().substringBefore(' ').substringBefore(':')

    /** "앱 버전 v2.0 (빌드 1050)", plus the downloaded next version: the name from docs/VERSION, the build number to tell builds apart. */
    fun versionLine(name:String,code:Int,next:Info?):String {
        fun label(n:String,c:Int)=if(Regex("""\d+\.\d+""").matches(n)) "v$n (빌드 $c)" else "v$c"
        val nextName=next?.let { if(it.versionName.startsWith("v") && Regex("""v\d+\.\d+""").matches(it.versionName)) "${it.versionName} (빌드 ${it.versionCode})" else "v${it.versionCode}" }
        return "앱 버전 "+label(name,code)+(nextName?.let { ", 새 버전 $it 준비됨" } ?: "")
    }
    data class Info(val versionCode:Int,val versionName:String,val apk:String,val sha256:String,val notes:String,val size:Long=0)
    private const val PREFS="ieum.update";private const val CHANNEL="ieum.update"
    /** When the update file was last read (ms). Before v3.1 the check waited this long for Wi-Fi; kept for the old work's input. */
    const val STALE_MS=6*3_600_000L
    private const val CHECKED="checked_ms"
    fun lastChecked(c:Context):Long=prefs(c).getLong(CHECKED,0)
    fun stale(now:Long,last:Long)=now-last>=STALE_MS
    /** v3.1 (user's decision 11/5 18:26, "무조건 모바일 데이터"): any connection, whatever the last check. */
    @Suppress("UNUSED_PARAMETER") fun networkFor(now:Long,last:Long)=NetworkType.CONNECTED
    /** The constraints of every update work: a connection of any kind (Wi-Fi or mobile data). */
    fun constraints():Constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
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
            USER_ACTION_REQUIRED -> ErrorWords("새 버전 설치에 확인이 한 번 필요해요.","알림이나 홈의 지금 업데이트를 눌러 주세요.")
            INSTALL_SOURCE_NOT_ALLOWED -> ErrorWords("새 버전을 저절로 설치하도록 아직 허용되지 않았어요.","설정에서 이음의 앱 설치 허용을 켜 주세요.")
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
        if(progress(c)?.optString("phase")!=P_CHECKING) progress(c,P_CHECKING)
        val info=try { parse(store.get("app/version.json")) }
            catch(e:Exception) {
                if(fetchState(c).stage==Stage.WAITING) stage(c,Stage.FAILED,reason=failWords(e)) // only when the user is watching
                progress(c,if(e is java.io.IOException && !online(c)) P_OFFLINE else P_FAILED);throw e }
        val p=prefs(c)
        p.edit().putLong(CHECKED,System.currentTimeMillis()).apply()
        if(info.versionCode<=BuildConfig.VERSION_CODE) { saveError(c,null);settleInstalled(c);p.edit().remove(A_CODE).apply();stage(c,Stage.IDLE);progress(c,P_NONE);return false } // up to date: an old install failure is over
        p.edit().putInt(A_CODE,info.versionCode).putString(A_NAME,info.versionName).putString(A_SHA,info.sha256).putString(A_NOTES,info.notes)
            .apply { if(info.size>0) putLong(A_SIZE,info.size) else if(p.getInt(A_CODE,0)!=info.versionCode) remove(A_SIZE) }.apply()
        if(ready(c)?.versionCode==info.versionCode) {
            stage(c,Stage.DONE,100);markReady(c,info.versionCode)
            if(progress(c)?.optString("phase")!=P_WAITING_CONFIRM) progress(c,P_READY,info.versionCode)
            if(!wantsInstall(c) && autoInstall(c)) installInBackground(c) // an earlier try failed or was lost: once more (never a second session)
            return true
        }
        val f=file(c);f.parentFile?.mkdirs();val tmp=File(f.parentFile,"download.tmp")
        stage(c,Stage.DOWNLOADING,0)
        progress(c,P_DOWNLOADING,info.versionCode,0,info.size.takeIf { it>0 } ?: -1)
        try {
            var shown=0;var written=clock()
            store.download(info.apk,tmp) { got,total ->
                val whole=if(info.size>0) info.size else total
                if(total>0 && info.size<=0 && p.getLong(A_SIZE,0)!=total) p.edit().putLong(A_SIZE,total).apply()
                val pct=percent(got,whole).coerceAtMost(99)
                if(pct>shown) { shown=pct;stage(c,Stage.DOWNLOADING,pct) } // a write per whole percent, not per block
                val now=clock()
                if(now-written>=PROGRESS_EVERY_MS) { written=now;progress(c,P_DOWNLOADING,info.versionCode,got,whole) } // bytes, every second
            }
            check(info.size<=0 || tmp.length()==info.size){"UPDATE_SIZE"}
            check(sha256(tmp)==info.sha256){"UPDATE_SHA"}
            check(tmp.renameTo(f) || run { tmp.copyTo(f,true);tmp.delete() }){"UPDATE_MOVE"}
        } catch(e:Exception) {
            tmp.delete();stage(c,Stage.FAILED,reason=failWords(e))
            progress(c,if(e is java.io.IOException && !online(c)) P_OFFLINE else P_FAILED,info.versionCode);throw e }
        p.edit().putInt("code",info.versionCode).putString("name",info.versionName).putString("sha",info.sha256).putString("notes",info.notes).putLong("size",f.length()).apply()
        stage(c,Stage.DONE,100)
        progress(c,P_READY,info.versionCode,f.length(),f.length())
        markReady(c,info.versionCode)
        // Design 3.3: straight on to a background install. Asked for on the screen ([지금 업데이트]): the screen installs it.
        if(wantsInstall(c) || !autoInstall(c) || !installInBackground(c)) notifyReady(c,info)
        return true
    }

    // ---- installing without a tap (design 3.3, round 7 r7-3) ----
    private const val SESSION="session_id";private const val SESSION_CODE="session_code"
    /** Status intent extra: the session was committed by [installInBackground], so a needed confirmation goes to a notification. */
    internal const val EXTRA_BACKGROUND="ieum.update.background"
    private const val NOTIFY_TAG="ieum.update";private const val NOTIFY_ID=701
    private fun settings(c:Context)=kr.callbrief.SecureSettings(CallPipelineStartup.storage(c))
    /** Settings switch "새 버전은 자동으로 설치" ([PipelineSettingsKeys.AUTO_INSTALL]): on unless turned off. */
    fun autoInstall(c:Context):Boolean=runCatching { settings(c).snapshot().optBoolean(PipelineSettingsKeys.AUTO_INSTALL,true) }.getOrDefault(true)
    /** The verified new version for the heartbeat: its code, and since when it waits (kept from the first verification of that code). */
    internal fun markReady(c:Context,code:Int,now:Long=System.currentTimeMillis()) {
        fun fresh(s:JSONObject)=s.optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,0)==code && s.optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,0)>0
        runCatching { if(!fresh(settings(c).snapshot())) settings(c).update {
            if(!fresh(it)) it.put(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,code).put(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,now)
        } }
    }
    /** Installed (or nothing newer): nothing waits any more; the session and the "새 버전" notification are over. */
    internal fun settleInstalled(c:Context) {
        prefs(c).edit().remove(SESSION).remove(SESSION_CODE).remove(PROGRESS).apply()
        runCatching { if(settings(c).snapshot().has(PipelineSettingsKeys.UPDATE_PROGRESS)) settings(c).update { it.remove(PipelineSettingsKeys.UPDATE_PROGRESS) } }
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_TAG,NOTIFY_ID) }
        fun clear(s:JSONObject)=s.optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,0)==0 && s.optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,0)==0L
        runCatching { if(!clear(settings(c).snapshot())) settings(c).update {
            it.put(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,0).put(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,0L)
        } }
    }
    /** The phone already runs the version that was waiting (a self-update replaces the app, so its success broadcast may never come). */
    internal fun settleIfInstalled(c:Context) {
        val code=runCatching { settings(c).snapshot().optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,0) }.getOrDefault(0)
        if(code in 1..BuildConfig.VERSION_CODE) settleInstalled(c)
    }
    /** The id of the committed session whose result has not come yet, or 0. A session the system no longer knows is over. */
    internal fun sessionId(c:Context):Int {
        val id=prefs(c).getInt(SESSION,0);if(id==0) return 0
        val live=runCatching { c.packageManager.packageInstaller.getSessionInfo(id)!=null }.getOrDefault(false)
        if(!live) prefs(c).edit().remove(SESSION).apply()
        return if(live) id else 0
    }
    fun sessionInProgress(c:Context)=sessionId(c)!=0
    /** That session ended. A result without an id leaves the record alone: [sessionId] drops it once the system forgets the session. */
    internal fun sessionDone(c:Context,id:Int) { val p=prefs(c);if(id!=0 && p.getInt(SESSION,0)==id) p.edit().remove(SESSION).remove(SESSION_CODE).apply() }
    /** Commits the verified file to a PackageInstaller session that asks for no user action (API 31+). False when it cannot be
     * tried here (older Android, installs from this app not allowed yet, the file does not match, or an error now kept in
     * [PipelineSettingsKeys.LAST_UPDATE_ERROR]): then the notification and the home banner stay the way in. */
    internal fun installInBackground(c:Context):Boolean {
        if(Build.VERSION.SDK_INT<31) return false
        val r=ready(c) ?: return false
        sessionId(c).takeIf { it!=0 }?.let { old ->
            if(prefs(c).getInt(SESSION_CODE,0)==r.versionCode) return true // one at a time: its result is still coming
            runCatching { c.packageManager.packageInstaller.abandonSession(old) };sessionDone(c,old) // an older version's session gives way
        }
        if(!c.packageManager.canRequestPackageInstalls()) { saveError(c,INSTALL_SOURCE_NOT_ALLOWED);return false } // setup step, notification
        if(errorCode(storedErrorText(c)) in setOf(INSTALL_SOURCE_NOT_ALLOWED,USER_ACTION_REQUIRED)) saveError(c,null) // a new try: that is over
        if(!verified(c)) return false
        return try { commit(c,file(c),background=true);true }
        catch(e:Exception) { saveError(c,errorText(PackageInstaller.STATUS_FAILURE,e.javaClass.simpleName+" "+(e.message ?: "")));false }
    }
    /** Android asked for a tap on a background install: kept for the heartbeat (watchdog) and the screens' "설치 확인을 기다리는 중". */
    internal fun pendingUserAction(c:Context) { progress(c,P_WAITING_CONFIRM,ready(c)?.versionCode ?: 0);saveError(c,USER_ACTION_REQUIRED) }
    /** Writes [f] into a new session and commits it; the result comes to [UpdateStatusReceiver]. The id is kept until it ends. */
    private fun commit(c:Context,f:File,background:Boolean):Int {
        val app=c.applicationContext
        val installer=app.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Tries a silent update first; when Android refuses, the confirmation still comes through the status receiver.
            if(Build.VERSION.SDK_INT>=31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id=installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("ieum",0,f.length()).use { out -> f.inputStream().use { it.copyTo(out) };s.fsync(out) }
                val status=PendingIntent.getBroadcast(app,id,Intent(app,UpdateStatusReceiver::class.java).putExtra(EXTRA_BACKGROUND,background),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                prefs(app).edit().putInt(SESSION,id).putInt(SESSION_CODE,ready(app)?.versionCode ?: 0).commit()
                s.commit(status.intentSender)
            }
        } catch(e:Exception) { runCatching { installer.abandonSession(id) };sessionDone(app,id);throw e }
        return id
    }
    /** Android wants a tap for the background install: a notification that opens the system's confirmation (design 3.3). */
    internal fun notifyConfirm(c:Context,confirm:Intent) {
        val m=channel(c) ?: return
        val open=PendingIntent.getActivity(c,2,confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        m.notify(NOTIFY_TAG,NOTIFY_ID,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
            .setContentTitle("이음 새 버전이 준비됐어요").setContentText("눌러서 설치를 마쳐 주세요")
            .setContentIntent(open).setAutoCancel(true).setLocalOnly(true).build())
    }
    /** The background install failed: the usual "새 버전" notification, so one tap still installs it. */
    internal fun notifyReadyAgain(c:Context) { ready(c)?.let { notifyReady(c,it) } }
    private fun channel(c:Context):NotificationManager? {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val m=c.getSystemService(NotificationManager::class.java) ?: return null
        m.createNotificationChannel(NotificationChannel(CHANNEL,"이음 업데이트",NotificationManager.IMPORTANCE_DEFAULT))
        return m
    }

    /** This build has no mailbox to get versions from (the preview build): nothing is coming. */
    internal fun noStore(c:Context) { if(fetchState(c).stage==Stage.WAITING) stage(c,Stage.IDLE) }
    /** For tests: replaces the WorkManager start of [fetchNow]. */
    @Volatile internal var startHook:((Context)->Unit)?=null
    /** [지금 받기]: starts getting the newer version now, on any connection. [installAfter]: go on to install when done.
     * [metered] only says how the user is connected (the settings note); since v3.1 every connection is used anyway. */
    fun fetchNow(c:Context,installAfter:Boolean,metered:Boolean=false) {
        val running=fetchState(c).stage==Stage.DOWNLOADING
        prefs(c).edit().putBoolean(F_INSTALL,installAfter || wantsInstall(c)).putBoolean(F_METERED,metered).apply()
        if(running) return // already coming: the screen only follows it
        stage(c,Stage.WAITING)
        startHook?.let { it(c);return }
        runCatching {
            WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("ieum.update.now",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CallUpdateWork>().setConstraints(constraints())
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
        val m=channel(c) ?: return
        val open=PendingIntent.getActivity(c,1,Intent(c,MainActivity::class.java).putExtra("install_update",true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        m.notify(NOTIFY_TAG,NOTIFY_ID,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
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
        // A background session waiting for a tap (or stuck) gives way to this one, so there is still only one at a time.
        sessionId(a).takeIf { it!=0 }?.let { old -> runCatching { a.packageManager.packageInstaller.abandonSession(old) };sessionDone(a,old) }
        commit(a,f,background=false)
        return true
    }

    /** Every 6 hours on any connection (v3.1: one work instead of a Wi-Fi one plus a stale-only mobile one). UPDATE replaces the
     * Wi-Fi constraint of a work registered by an older version; the old any-connection work is cancelled. */
    fun schedule(c:Context) {
        if(!CallInboxConfig.enabled)return
        val wm=WorkManager.getInstance(c.applicationContext)
        wm.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,periodicRequest())
        wm.cancelUniqueWork(OLD_ANY)
    }
    internal fun periodicRequest()=PeriodicWorkRequestBuilder<CallUpdateWork>(6,TimeUnit.HOURS).setConstraints(constraints()).build()
    internal fun openRequest()=OneTimeWorkRequestBuilder<CallUpdateWork>().setConstraints(constraints())
        .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build()
    const val PERIODIC="ieum.update";private const val OLD_ANY="ieum.update.any";const val OPEN="ieum.update.open"
    /** A check right when the app process starts (v3.1, design 2.2-5: no 30-second wait), on any connection; offline it waits
     * for one. The screen's [checkNow] at launch shows the same check as it happens. */
    @Suppress("UNUSED_PARAMETER")
    fun checkSoon(c:Context,now:Long=System.currentTimeMillis()) {
        if(!CallInboxConfig.enabled)return
        runCatching {
            WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(OPEN,ExistingWorkPolicy.KEEP,openRequest())
        }
    }
}

internal class CallUpdateWork(context:Context,params:WorkerParameters):Worker(context,params) {
    companion object { const val ONLY_IF_STALE="onlyIfStale" }
    override fun doWork():Result {
        // ONLY_IF_STALE: the any-connection work of v3.0 and earlier, until schedule() cancels it.
        CallUpdate.settleIfInstalled(applicationContext) // the version that was waiting is on: heartbeat keys and notification follow
        if(inputData.getBoolean(ONLY_IF_STALE,false) && !CallUpdate.stale(System.currentTimeMillis(),CallUpdate.lastChecked(applicationContext))) return Result.success()
        val store=CallInboxConfig.store() ?: return Result.success().also { CallUpdate.noStore(applicationContext) }
        return try { CallUpdate.check(applicationContext,store);Result.success() }
        catch(_:java.io.IOException) { Result.retry() } catch(_:Exception) { Result.success() }
    }
}

/** Receives the installer status. Android wants the user's confirmation: after a tap on the screen, its dialog; after the
 * background install, a notification that opens it (the home banner stays too). Success: nothing waits any more. A failed
 * install is kept in [PipelineSettingsKeys.LAST_UPDATE_ERROR] for the settings update light; after a background try the usual
 * "새 버전" notification comes so one tap still installs. */
class UpdateStatusReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,-99)
        val id=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,0)
        val background=intent.getBooleanExtra(CallUpdate.EXTRA_BACKGROUND,false)
        val app=context.applicationContext
        fun later(block:()->Unit) { val pending:PendingResult? =goAsync();Thread { try { runCatching(block) } finally { pending?.finish() } }.start() }
        when {
            status==PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // A background install that Android will not do without a tap (DESIGN_V4 2.2-4): a code for the watchdog.
                if(background) later { CallUpdate.pendingUserAction(app) }
                @Suppress("DEPRECATION") val confirm=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                if(background) CallUpdate.notifyConfirm(app,confirm) // UPDATE_READY_SINCE_MS stays: still not installed
                else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            status==PackageInstaller.STATUS_SUCCESS -> later { CallUpdate.saveError(app,null);CallUpdate.settleInstalled(app) }
            status==-99 -> {}
            CallUpdate.isUserCancel(status) -> CallUpdate.sessionDone(app,id)
            else -> {
                val text=CallUpdate.errorText(status,intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                later { CallUpdate.sessionDone(app,id);CallUpdate.saveError(app,text);if(background) CallUpdate.notifyReadyAgain(app) }
            }
        }
    }
}
