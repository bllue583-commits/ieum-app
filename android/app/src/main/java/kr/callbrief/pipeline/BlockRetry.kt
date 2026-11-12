package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kr.callbrief.R

/** v3.7 (user 11/12): after [설정 열기] of the "보안 위험 자동 차단" sheet the app goes behind the settings. While it is
 * behind, at most [OpenUpdate.RETRY_FOR_MS], it asks Android again every [OpenUpdate.RETRY_EVERY_MS] to install its own
 * waiting version and looks at the answer; it never reads the phone's security settings. Once the person turns the block off,
 * a try goes through, the "opened by the app" mark is there, and [OpenUpdate.afterReplaced] opens the new version. One
 * session at a time: the next try comes only after the last one's answer. Only while this process lives (a handler and one
 * thread, no service). The rules are [OpenUpdate.retryStep]. */
internal object BlockRetry {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-update-retry").apply { isDaemon=true } }
    @Volatile private var app:Context?=null
    @Volatile var running=false
        private set
    @Volatile private var startMs=0L
    @Volatile private var lastTryMs=0L
    @Volatile private var pendingId=0
    @Volatile private var taken=false
    @Volatile private var verifiedOnce=false
    @Volatile private var moved=false
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    private val step=Runnable { worker.execute { runCatching { tick() }.onFailure { finish() } } }
    private val progress=object:PackageInstaller.SessionCallback() {
        override fun onCreated(id:Int) {};override fun onBadgingChanged(id:Int) {};override fun onActiveChanged(id:Int,active:Boolean) {}
        override fun onProgressChanged(id:Int,p:Float) {
            val c=app ?: return
            if(id==0 || id!=pendingId) return
            if(!moved) { moved=true;Thread { CallUpdate.noteSetting(c,PipelineSettingsKeys.UPDATE_AUTO_BACK,"retry") }.start() }
            showProgress(c,OpenUpdate.percentOf(p))
        }
        override fun onFinished(id:Int,success:Boolean) { val c=app ?: return;if(id==pendingId) { clearProgress(c);if(!running) unregister(c) } }
    }
    private var callbackOn=false

    /** The app went behind after [설정 열기] (UpdateCover.pause). */
    fun start(c:Context) {
        if(running) return
        val a=c.applicationContext;app=a
        running=true;startMs=clock();lastTryMs=0;pendingId=0;taken=false;verifiedOnce=false;moved=false
        main.post { if(!callbackOn) callbackOn=runCatching { a.packageManager.packageInstaller.registerSessionCallback(progress,main) }.isSuccess }
        main.post(step)
    }
    /** The app is in front again: onResume goes on from here (no second try at the same time). */
    fun stop() { if(running) finish() }

    private fun tick() {
        val c=app ?: return
        if(!running) return
        val pending=pendingId!=0 && CallUpdate.sessionId(c)==pendingId
        val s=OpenUpdate.retryStep(startMs,clock(),lastTryMs,pending,OpenUpdate.front,OpenUpdate.laterThisOpen(),OpenUpdate.inCall(c),taken)
        when(s.act) {
            OpenUpdate.RetryAct.STOP -> { finish();return }
            OpenUpdate.RetryAct.WAIT -> {}
            OpenUpdate.RetryAct.TRY -> {
                val id=runCatching { CallUpdate.commitRetry(c,verify=!verifiedOnce) }.getOrDefault(0)
                verifiedOnce=true
                if(id==0) { finish();return }
                pendingId=id;lastTryMs=clock();moved=false
            }
        }
        if(running) main.postDelayed(step,1_000L)
    }
    /** The answer to a try (UpdateStatusReceiver). Blocked: the next try comes after [OpenUpdate.RETRY_EVERY_MS]. Anything else
     * (it installs, it wants a tap, another failure) ends the loop; a tap goes to the usual notification. */
    fun result(c:Context,status:Int,blocked:Boolean) {
        if(!running) return
        val id=pendingId;pendingId=0;lastTryMs=clock()
        // A failed try's session is given up for sure before the next one (one session at a time).
        if(status!=PackageInstaller.STATUS_PENDING_USER_ACTION && status!=PackageInstaller.STATUS_SUCCESS && id!=0)
            runCatching { c.packageManager.packageInstaller.abandonSession(id) }
        if(blocked) { clearProgress(c);return }
        taken=true
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION || status==PackageInstaller.STATUS_SUCCESS)
            Thread { CallUpdate.noteSetting(c,PipelineSettingsKeys.UPDATE_AUTO_BACK,"retry") }.start()
        else clearProgress(c)
    }
    private fun finish() {
        running=false;main.removeCallbacks(step)
        val c=app ?: return
        // A try that moves on keeps its progress notification until Android finishes it.
        if(pendingId==0 || !moved) { clearProgress(c);main.post { unregister(c) } }
    }
    private fun unregister(c:Context) {
        if(callbackOn) { runCatching { c.packageManager.packageInstaller.unregisterSessionCallback(progress) };callbackOn=false }
    }

    // ---- the quiet progress notification, seen from the settings screen ----
    private const val CHANNEL="ieum.update.progress";private const val TAG="ieum.update.progress";private const val ID=703
    private fun showProgress(c:Context,percent:Int) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val m=c.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            m.createNotificationChannel(NotificationChannel(CHANNEL,"이음 업데이트 진행",NotificationManager.IMPORTANCE_LOW))
            m.notify(TAG,ID,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
                .setContentTitle(OpenUpdate.coverTitle(percent)).setProgress(100,percent,false)
                .setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true).build())
        }
    }
    internal fun clearProgress(c:Context) { runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(TAG,ID) } }
    /** For tests. */
    internal fun resetForTest() { running=false;main.removeCallbacks(step);pendingId=0;taken=false;app=null;clock={ System.currentTimeMillis() } }
}
