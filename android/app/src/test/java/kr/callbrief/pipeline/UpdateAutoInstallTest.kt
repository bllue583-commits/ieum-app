package kr.callbrief.pipeline

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Round 7 r7-3 (design 3.3): a downloaded, verified new version installs in the background through a PackageInstaller session
 * that asks for no user action; the switch off keeps the notification only; a needed tap becomes a notification while
 * updateReadySinceMs stays; success sets it back to 0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UpdateAutoInstallTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val next=kr.callbrief.BuildConfig.VERSION_CODE+1
    private val apk=ByteArray(50_000) { (it%251).toByte() }
    private val sha=java.security.MessageDigest.getInstance("SHA-256").digest(apk).joinToString(""){"%02x".format(it)}
    private val installer get()=app.packageManager.packageInstaller
    private fun notes()=shadowOf(app.getSystemService(NotificationManager::class.java))
    private fun s():JSONObject=SecureSettings(app).snapshot()
    private fun settle(check:()->Boolean) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check()) return;Thread.sleep(10) };fail("did not settle") }

    private inner class Store:CallInboxStore {
        var downloads=0
        override fun put(path:String,json:String) {}
        override fun list(dir:String)=emptyList<Pair<String,String>>()
        override fun delete(path:String,sha:String) {}
        override fun get(path:String)=JSONObject().put("versionCode",next).put("versionName","v2.6").put("apk","releases/assets/1")
            .put("sha256",sha).put("notes","새 버전이에요").put("size",apk.size).toString()
        override fun download(path:String,to:File,progress:(Long,Long)->Unit) { downloads++;to.writeBytes(apk);progress(apk.size.toLong(),apk.size.toLong()) }
    }

    @Before fun allow() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun aFinishedDownloadCommitsOneBackgroundSession() {
        val finished=mutableListOf<Pair<Int,Boolean>>()
        installer.registerSessionCallback(object:PackageInstaller.SessionCallback() {
            override fun onCreated(id:Int) {};override fun onBadgingChanged(id:Int) {};override fun onActiveChanged(id:Int,active:Boolean) {}
            override fun onProgressChanged(id:Int,progress:Float) {}
            override fun onFinished(id:Int,success:Boolean) { finished+=id to success }
        },Handler(Looper.getMainLooper()))
        assertTrue(CallUpdate.autoInstall(app)) // on by default
        assertTrue(CallUpdate.check(app,Store()))
        val sessions=installer.allSessions
        assertEquals(1,sessions.size);assertEquals(app.packageName,sessions[0].appPackageName)
        settle { finished.isNotEmpty() }
        assertEquals("commit was called",listOf(sessions[0].sessionId to true),finished)
        assertTrue(CallUpdate.sessionInProgress(app))
        assertEquals(next,s().optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE));val since=s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS)
        assertTrue(since>0)
        // The next check while that session's result is still coming: no second session, the same "since".
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals(1,installer.allSessions.size);assertEquals(since,s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS))
        assertNull("no tap notification while it installs by itself",notes().getNotification("ieum.update",701))
    }

    @Test fun switchedOffItOnlyNotifies() {
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertFalse(CallUpdate.autoInstall(app))
        assertTrue(CallUpdate.check(app,Store()))
        assertTrue("no session, no commit",installer.allSessions.isEmpty())
        assertNotNull(notes().getNotification("ieum.update",701))
        assertEquals(next,s().optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE))
        assertTrue(s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS)>0)
    }

    @Test fun installsNotAllowedYetFallsBackToTheNotification() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertTrue(CallUpdate.check(app,Store()))
        assertTrue(installer.allSessions.isEmpty())
        assertNotNull(notes().getNotification("ieum.update",701))
    }

    @Test fun aNeededTapBecomesANotificationAndTheWaitStays() {
        assertTrue(CallUpdate.check(app,Store()))
        val id=installer.allSessions.single().sessionId
        val since=s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS);assertTrue(since>0)
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID,id).putExtra(CallUpdate.EXTRA_BACKGROUND,true)
            .putExtra(Intent.EXTRA_INTENT,Intent("android.content.pm.action.CONFIRM_INSTALL")))
        val n=notes().getNotification("ieum.update",701)
        assertNotNull(n);assertEquals("이음 새 버전이 준비됐어요",n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertNull("no window pops up by itself from the background",shadowOf(app).nextStartedActivity)
        assertEquals(since,s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS))
        assertTrue("still one at a time",CallUpdate.sessionInProgress(app))
    }

    @Test fun successSetsTheWaitBackToZeroAndClearsTheNotification() {
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertTrue(CallUpdate.check(app,Store()));assertNotNull(notes().getNotification("ieum.update",701))
        CallUpdate.saveError(app,"STATUS_FAILURE_STORAGE (6): x")
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_SUCCESS).putExtra(CallUpdate.EXTRA_BACKGROUND,true))
        settle { s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,-1)==0L }
        assertEquals(0,s().optInt(PipelineSettingsKeys.UPDATE_AVAILABLE_CODE,-1))
        assertEquals("",s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR))
        assertNull(notes().getNotification("ieum.update",701))
    }

    @Test fun aFailureIsKeptEndsTheSessionAndNotifies() {
        assertTrue(CallUpdate.check(app,Store()))
        val id=installer.allSessions.single().sessionId
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_STORAGE)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID,id).putExtra(CallUpdate.EXTRA_BACKGROUND,true)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,"x".repeat(200)))
        settle { s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR).isNotEmpty() }
        val err=s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR)
        assertEquals("STATUS_FAILURE_STORAGE (6): "+"x".repeat(80),err)
        settle { notes().getNotification("ieum.update",701)!=null }
        assertTrue(s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS)>0)
    }
}
