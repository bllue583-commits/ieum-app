package kr.callbrief

import android.content.Context
import android.view.WindowManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic broken preference, no real account/token access or WorkManager registration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
class DailyReportStartupFailureTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();GoogleConnectionSession.resetForHost()
        context.getSharedPreferences("eum_connections_v1_private_settings",Context.MODE_PRIVATE).edit().putString("state","synthetic-malformed-state").commit()
    }
    @After fun cleanup() { GoogleConnectionSession.resetForHost();context.getSharedPreferences("eum_connections_v1_private_settings",Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun unreadableAccountSettingsDoNotCrashAsyncSchedulerOrPreventReceiverCompletion() {
        val done=CountDownLatch(1);DailyReportSchedule.ensureAsync(context,"AFTER_REBOOT",true) { done.countDown() }
        assertTrue(done.await(5,TimeUnit.SECONDS))
        assertEquals("synthetic-malformed-state",context.getSharedPreferences("eum_connections_v1_private_settings",Context.MODE_PRIVATE).getString("state",null))
    }
    @Test fun reportScreenFailsClosedAndKeepsWindowSecureWhenAccountStoreCannotBeRead() {
        val controller=Robolectric.buildActivity(DailyReportActivity::class.java).create().start().resume()
        try { assertTrue(controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0) }
        finally { controller.pause().stop().destroy() }
    }
}
