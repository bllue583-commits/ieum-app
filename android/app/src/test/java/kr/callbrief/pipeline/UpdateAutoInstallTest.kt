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

    // ---- v3.7 (user 11/12): the open app installs it; the heartbeat says why a background install did not happen ----
    @Test fun theBackgroundDecisionIsKeptAsACode() {
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals(CallUpdate.BG_COMMITTED,s().optString(PipelineSettingsKeys.UPDATE_BG))
        assertTrue(s().optLong(PipelineSettingsKeys.UPDATE_BG_AT_MS)>0)
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals("the next check waits for that session",CallUpdate.BG_SESSION_WAITING,s().optString(PipelineSettingsKeys.UPDATE_BG))
    }
    @Test fun switchedOffOrNotAllowedIsKeptAsACode() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals(CallUpdate.BG_SOURCE,s().optString(PipelineSettingsKeys.UPDATE_BG))
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals(CallUpdate.BG_AUTO_OFF,s().optString(PipelineSettingsKeys.UPDATE_BG))
    }
    @Test fun withTheAppInFrontTheScreenInstallsIt() {
        var told=0
        OpenUpdate.front=true;OpenUpdate.readyListener={ told++ }
        try {
            assertTrue(CallUpdate.check(app,Store()))
            assertTrue("no background session competes with the screen's",installer.allSessions.isEmpty())
            assertEquals(CallUpdate.BG_FRONT,s().optString(PipelineSettingsKeys.UPDATE_BG))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,told)
            assertNull(notes().getNotification("ieum.update",701))
            val i=CallUpdate.openInput(app)
            assertTrue(i.ready);assertEquals(OpenUpdate.Decision(true,true,"start"),OpenUpdate.decide(i))
        } finally { OpenUpdate.reset() }
    }
    @Test fun aWaitingBackgroundSessionIsSeenByTheOpenApp() {
        assertTrue(CallUpdate.check(app,Store()))
        val v=CallUpdate.sessionView(app)
        assertEquals(OpenUpdate.Session.BACKGROUND,v.kind);assertFalse(v.answered)
        assertEquals("young: it may be installing",OpenUpdate.Decision(false,true,"bgFresh"),OpenUpdate.decide(CallUpdate.openInput(app)))
        val id=installer.allSessions.single().sessionId
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID,id).putExtra(CallUpdate.EXTRA_BACKGROUND,true)
            .putExtra(Intent.EXTRA_INTENT,Intent("android.content.pm.action.CONFIRM_INSTALL")))
        settle { CallUpdate.sessionView(app).answered }
        assertEquals("it waits for a tap: the open app commits its own instead",OpenUpdate.Decision(true,true,"replace"),OpenUpdate.decide(CallUpdate.openInput(app)))
        settle { s().optString(PipelineSettingsKeys.UPDATE_BG)==CallUpdate.BG_USER_ACTION }
    }
    @Test fun aClosedWindowAndABlockAreRememberedForThisOpening() {
        OpenUpdate.reset();OpenUpdate.opened()
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertTrue(CallUpdate.check(app,Store()))
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,true) }
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_ABORTED)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID,0).putExtra(CallUpdate.EXTRA_BACKGROUND,false))
        assertTrue(OpenUpdate.declined(next))
        assertEquals("declined",OpenUpdate.decide(CallUpdate.openInput(app)).reason)
        OpenUpdate.opened()
        assertEquals("the next opening tries once more","start",OpenUpdate.decide(CallUpdate.openInput(app)).reason)
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_BLOCKED)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID,0).putExtra(CallUpdate.EXTRA_BACKGROUND,false))
        assertTrue(OpenUpdate.blocked(next))
        assertEquals("blocked",OpenUpdate.decide(CallUpdate.openInput(app)).reason)
        settle { s().optString(PipelineSettingsKeys.UPDATE_BG)==CallUpdate.BG_BLOCKED }
        settle { s().optString(PipelineSettingsKeys.UPDATE_OPEN)==CallUpdate.BG_BLOCKED }
        settle { s().optInt(PipelineSettingsKeys.UPDATE_BLOCK_STATUS,-1)==PackageInstaller.STATUS_FAILURE_BLOCKED }
        OpenUpdate.reset()
    }
    @Test fun aVerificationRefusalOnASamsungPhoneIsABlock() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("samsung")
        try {
            OpenUpdate.reset();OpenUpdate.opened()
            SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
            assertTrue(CallUpdate.check(app,Store()))
            UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_ABORTED)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID,0).putExtra(CallUpdate.EXTRA_BACKGROUND,false)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,"INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed"))
            assertTrue(OpenUpdate.blocked(next));assertFalse("not taken for a closed window",OpenUpdate.declined(next))
            settle { s().optString(PipelineSettingsKeys.UPDATE_BLOCK_CODE)=="INSTALL_FAILED_VERIFICATION_FAILURE" }
            assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED,s().optInt(PipelineSettingsKeys.UPDATE_BLOCK_STATUS))
            assertTrue(s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR).startsWith("STATUS_FAILURE_BLOCKED"))
            assertFalse("no message words kept",s().toString().contains("Install not allowed"))
        } finally { org.robolectric.shadows.ShadowBuild.reset();OpenUpdate.reset() }
    }
    @Test fun behindTheSettingsTheLoopTriesOneSessionAtATimeAndStopsInFront() {
        OpenUpdate.reset();OpenUpdate.opened()
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertTrue(CallUpdate.check(app,Store()))
        var now=100_000L;BlockRetry.clock={ now }
        fun until(check:()->Boolean) { repeat(300) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));if(check()) return;Thread.sleep(10) };fail("did not settle") }
        try {
            BlockRetry.start(app)
            until { installer.allSessions.size==1 && CallUpdate.sessionId(app)!=0 }
            val first=installer.allSessions.single().sessionId
            assertEquals(1,s().optLong(PipelineSettingsKeys.UPDATE_BLOCK_RETRIES))
            assertTrue("the new version opens again once it is on",app.getSharedPreferences("ieum.update",android.content.Context.MODE_PRIVATE).getLong("relaunch_ms",0)>0)
            // Blocked again: no notification from the loop's tries, the next try after five seconds, still one session.
            UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_BLOCKED)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID,first).putExtra(CallUpdate.EXTRA_BACKGROUND,true).putExtra(CallUpdate.EXTRA_RETRY,true))
            until { CallUpdate.sessionId(app)==0 }
            now+=OpenUpdate.RETRY_EVERY_MS
            until { s().optLong(PipelineSettingsKeys.UPDATE_BLOCK_RETRIES)==2L }
            assertTrue(installer.allSessions.size<=1)
            assertNull("the loop's blocked tries stay quiet",notes().getNotification("ieum.update",701))
            OpenUpdate.front=true
            BlockRetry.stop();assertFalse(BlockRetry.running)
        } finally { OpenUpdate.reset() }
    }
    @Test fun theNewVersionOpensAgainOnlyAfterAnInstallTheOpenAppStarted() {
        val p=app.getSharedPreferences("ieum.update",android.content.Context.MODE_PRIVATE)
        OpenUpdate.afterReplaced(app)
        assertNull("a background install stays quiet",shadowOf(app).nextStartedActivity)
        assertNull(notes().getNotification("ieum.update.done",702))
        p.edit().putLong("relaunch_ms",System.currentTimeMillis()-60_000L).commit()
        OpenUpdate.afterReplaced(app)
        assertEquals(MainActivity::class.java.name,shadowOf(app).nextStartedActivity?.component?.className)
        val n=notes().getNotification("ieum.update.done",702)
        assertNotNull(n);assertEquals(OpenUpdate.DONE_TITLE,n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals("the mark is used once",0L,p.getLong("relaunch_ms",0))
        assertEquals("front",s().optString(PipelineSettingsKeys.UPDATE_AUTO_BACK))
        OpenUpdate.clearDone(app);assertNull(notes().getNotification("ieum.update.done",702))
        p.edit().putLong("relaunch_ms",System.currentTimeMillis()-11*60_000L).commit()
        OpenUpdate.afterReplaced(app)
        assertNull("older than ten minutes",shadowOf(app).nextStartedActivity)
    }

    // ---- v3.1 (DESIGN_V4 2.2-4, 2.2-5) ----
    @org.junit.After fun resetHooks() { CallUpdate.storeHook=null;CallUpdate.clock={ System.currentTimeMillis() } }
    @Suppress("DEPRECATION")
    private fun network(type:Int?) {
        val cm=shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java))
        cm.setActiveNetworkInfo(type?.let { org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,it,0,true,android.net.NetworkInfo.State.CONNECTED) })
    }
    private fun progressSetting()=JSONObject(s().getString(PipelineSettingsKeys.UPDATE_PROGRESS))

    @Test fun installsNotAllowedKeepsACodeAndOffersTheSetupStep() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertTrue(CallUpdate.setupStep(app))
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals(CallUpdate.INSTALL_SOURCE_NOT_ALLOWED,s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR))
        val st=CallUpdate.state(app)
        assertEquals(CallUpdate.P_READY,st.phase);assertTrue(st.canInstall);assertTrue(st.words.contains("준비됐어요"))
        assertNotNull(CallUpdate.errorWords(CallUpdate.INSTALL_SOURCE_NOT_ALLOWED))
        // Allowed later: the setup step goes away and the next try clears the code and installs by itself.
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertFalse(CallUpdate.setupStep(app))
        assertTrue(CallUpdate.check(app,Store()))
        assertEquals("",s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR))
        assertEquals(1,installer.allSessions.size)
    }

    @Test fun pendingUserActionIsKeptAsACodeAndShownAsWaitingForConfirmation() {
        assertTrue(CallUpdate.check(app,Store()))
        val id=installer.allSessions.single().sessionId
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID,id).putExtra(CallUpdate.EXTRA_BACKGROUND,true)
            .putExtra(Intent.EXTRA_INTENT,Intent("android.content.pm.action.CONFIRM_INSTALL")))
        settle { s().optString(PipelineSettingsKeys.LAST_UPDATE_ERROR)==CallUpdate.USER_ACTION_REQUIRED }
        assertEquals(CallUpdate.P_WAITING_CONFIRM,progressSetting().getString("phase"))
        val st=CallUpdate.state(app)
        assertEquals(CallUpdate.P_WAITING_CONFIRM,st.phase);assertTrue(st.canInstall);assertEquals(CallUpdate.CONFIRM_WORDS,st.words)
        assertNotNull(notes().getNotification("ieum.update",701))
        // The heartbeat sends the code as it is (the watchdog's old-version issue names it).
        assertEquals(CallUpdate.USER_ACTION_REQUIRED,PhoneStatus.build(PhoneStatus.Input(1L,"v3.1",1,0,null,0,0,"READY","",CallUpdate.USER_ACTION_REQUIRED,0)).getString("lastUpdateError"))
    }

    @Test fun theDownloadWritesItsProgressEverySecond() {
        var now=1_000_000L;CallUpdate.clock={ now }
        val seen=mutableListOf<Long>()
        val store=object:CallInboxStore by Store() {
            override fun download(path:String,to:File,progress:(Long,Long)->Unit) {
                val step=apk.size/10
                to.outputStream().use { out -> for(i in 1..10) {
                    val got=if(i==10) apk.size else i*step
                    out.write(apk,(i-1)*step,got-(i-1)*step)
                    now+=400 // a block every 0.4 seconds
                    progress(got.toLong(),apk.size.toLong())
                    CallUpdate.progress(app)?.takeIf { it.getString("phase")==CallUpdate.P_DOWNLOADING }?.getLong("doneBytes")?.let { if(seen.lastOrNull()!=it) seen+=it }
                    if(i==5) {
                        val st=CallUpdate.state(app)
                        assertEquals(CallUpdate.P_DOWNLOADING,st.phase);assertFalse(st.canInstall)
                        assertTrue(st.words,st.words.contains("받는 중") && st.words.contains("MB / ") && st.words.contains("%"))
                        assertEquals(CallUpdate.P_DOWNLOADING,progressSetting().getString("phase"))
                        assertEquals(apk.size.toLong(),progressSetting().getLong("totalBytes"))
                    }
                } }
            }
        }
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertTrue(CallUpdate.check(app,store))
        // 0 first, then one write per second of download (every third block of 0.4 s), not one per block.
        assertEquals(listOf(0L,15_000L,30_000L,45_000L),seen)
        val done=progressSetting();assertEquals(CallUpdate.P_READY,done.getString("phase"));assertEquals(apk.size.toLong(),done.getLong("doneBytes"))
        assertEquals(next,done.getInt("code"))
    }

    @Test fun progressWordsShowPercentAndMegabytes() {
        assertEquals("42% (3.1MB / 7.0MB)",CallUpdate.progressWords(42,3_100_000,7_000_000))
        assertEquals("21% (123MB / 574MB)",CallUpdate.progressWords(-1,123_000_000,574_041_195))
        assertEquals("42%",CallUpdate.progressWords(42,-1,0))
        assertEquals("",CallUpdate.progressWords(-1,-1,0))
    }

    @Test fun checkNowGetsTheNewVersionOnMobileData() {
        network(android.net.ConnectivityManager.TYPE_MOBILE)
        CallUpdate.storeHook={ Store() }
        var got:CallUpdate.UpdateState?=null
        CallUpdate.checkNow(app) { got=it }
        settle { got!=null }
        assertNotNull("downloaded on mobile data",CallUpdate.ready(app))
        assertTrue(got!!.phase,got!!.phase in setOf(CallUpdate.P_READY,CallUpdate.P_WAITING_CONFIRM))
        assertEquals(1,installer.allSessions.size) // and on to the background install
    }

    @Test fun checkNowOfflineSaysItWaitsForTheConnection() {
        network(null)
        CallUpdate.storeHook={ Store() }
        var got:CallUpdate.UpdateState?=null
        CallUpdate.checkNow(app) { got=it }
        settle { got!=null }
        assertEquals(CallUpdate.P_OFFLINE,got!!.phase);assertEquals(CallUpdate.OFFLINE_WORDS,got!!.words)
        assertNull(CallUpdate.ready(app))
        assertEquals(CallUpdate.P_OFFLINE,progressSetting().getString("phase"))
    }

    @Test fun upToDateIsNothingToShow() {
        CallUpdate.storeHook={ object:CallInboxStore by Store() {
            override fun get(path:String)=JSONObject(Store().get(path)).put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE).toString() } }
        var got:CallUpdate.UpdateState?=null
        CallUpdate.checkNow(app) { got=it }
        settle { got!=null }
        assertEquals(CallUpdate.UpdateState(),got)
    }

    @Test fun notInstalledForADayCountsFromTheVerification() {
        SecureSettings(app).update { it.put(PipelineSettingsKeys.AUTO_INSTALL,false) }
        assertFalse(CallUpdate.notInstalledForADay(app))
        assertTrue(CallUpdate.check(app,Store()))
        val since=s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS)
        assertFalse(CallUpdate.notInstalledForADay(app,since+23*3_600_000L))
        assertTrue(CallUpdate.notInstalledForADay(app,since+25*3_600_000L))
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_SUCCESS))
        settle { s().optLong(PipelineSettingsKeys.UPDATE_READY_SINCE_MS,-1)==0L }
        assertFalse(CallUpdate.notInstalledForADay(app,since+25*3_600_000L))
    }

    @Test fun everyUpdateAndModelWorkUsesAnyConnection() {
        assertEquals(androidx.work.NetworkType.CONNECTED,CallUpdate.periodicRequest().workSpec.constraints.requiredNetworkType)
        val open=CallUpdate.openRequest().workSpec
        assertEquals(androidx.work.NetworkType.CONNECTED,open.constraints.requiredNetworkType);assertEquals("no 30-second wait",0L,open.initialDelay)
        val model=PipelineModelDownloadWork.request().workSpec.constraints
        assertEquals(androidx.work.NetworkType.CONNECTED,model.requiredNetworkType);assertTrue(model.requiresStorageNotLow())
    }

    @Test fun manifestAsksForUpdatesWithoutUserAction() {
        val info=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
        assertTrue(info.requestedPermissions!!.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES))
    }
}
