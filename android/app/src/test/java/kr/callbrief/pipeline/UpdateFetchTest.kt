package kr.callbrief.pipeline

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Round 6 v2.5: getting the next version shows a bar and "받는 중 n%" in settings, [지금 업데이트] never ends silently,
 * a finished download offers [지금 설치], and a file that does not match version.json is got again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateFetchTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val next=kr.callbrief.BuildConfig.VERSION_CODE+1
    private val apk=ByteArray(100_000) { (it%251).toByte() }
    private val sha=java.security.MessageDigest.getInstance("SHA-256").digest(apk).joinToString(""){"%02x".format(it)}
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun idle() { repeat(30) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) } }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun tickOnce() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SettingsScreen.TICK_MS)) }
    private fun state(a:android.app.Activity)=(tag(a,"update_state") as TextView).text.toString()
    private var starts=0
    @After fun reset() { CallUpdate.startHook=null }

    /** The mailbox: version.json and the APK, given out in three pieces; [hold] stops the download at 42% until released. */
    private inner class Store(val hold:CountDownLatch?=null,val reached:CountDownLatch?=null,val badSha:Boolean=false,val failDownload:Boolean=false):CallInboxStore {
        override fun put(path:String,json:String) {}
        override fun list(dir:String)=emptyList<Pair<String,String>>()
        override fun delete(path:String,sha:String) {}
        override fun get(path:String)=JSONObject().put("versionCode",next).put("versionName","v2.5").put("apk","releases/assets/1")
            .put("sha256",if(badSha) "0".repeat(64) else sha).put("notes","업데이트 받기가 보여요").toString()
        override fun download(path:String,to:File,progress:(Long,Long)->Unit) {
            if(failDownload) throw java.io.IOException("reset")
            to.outputStream().use { out ->
                val cut=apk.size*42/100
                out.write(apk,0,cut);out.flush();progress(cut.toLong(),apk.size.toLong())
                reached?.countDown();hold?.await(10,TimeUnit.SECONDS)
                out.write(apk,cut,apk.size-cut);progress(apk.size.toLong(),apk.size.toLong())
            }
        }
    }

    @Test fun percentCountsWholeStepsAndSizeReadsInMegabytes() {
        assertEquals(42,CallUpdate.percent(42_000,100_000));assertEquals(-1,CallUpdate.percent(5,-1));assertEquals(100,CallUpdate.percent(9,9))
        assertEquals("약 7MB",CallUpdate.sizeWords(0));assertEquals("약 7MB",CallUpdate.sizeWords(6_400_000));assertEquals("약 1MB",CallUpdate.sizeWords(10))
        assertEquals(103,CallUpdate.parse("""{"versionCode":2,"apk":"a","sha256":"AB","size":103}""").size)
    }

    @Test fun settingsShowTheBarWhileItComesThenOfferInstall() {
        val hold=CountDownLatch(1);val reached=CountDownLatch(1)
        val work=Thread { runCatching { CallUpdate.check(app,Store(hold,reached)) } }.apply { start() }
        assertTrue(reached.await(10,TimeUnit.SECONDS))
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"update_state")!=null }
        // v3.1 (DESIGN_V4 2.2-5): the words come from CallUpdate.state, with the sizes.
        assertTrue(state(a),state(a).startsWith("새 버전을 받는 중이에요. 42%"))
        assertEquals(42,(tag(a,"update_progress") as ProgressBar).progress)
        assertNull("no install before the file is here",tag(a,"update_install"))
        hold.countDown();work.join(10_000)
        tickOnce()
        assertTrue(state(a),state(a).startsWith("새 버전(v2.5)이 준비됐어요"))
        assertEquals("지금 업데이트",(tag(a,"update_install") as TextView).text.toString())
        assertNull(tag(a,"update_progress"))
        assertTrue(views(a.window.decorView).filterIsInstance<TextView>().none { it.text.contains("·") || it.text.contains("DOWNLOADING") })
    }

    @Test fun homeButtonWithoutTheFileStartsGettingItAndGoesToTheBar() {
        CallUpdate.startHook={ starts++ }
        // A finished download whose file was cleared away (the cache): the home card is still there and its button still works.
        assertTrue(CallUpdate.check(app,Store()));CallUpdate.file(app).delete()
        assertNull(CallUpdate.ready(app));assertNotNull(CallUpdate.available(app))
        val home=launchMain(Screen.HOME)
        settle { tag(home,"home_update_button")!=null }
        assertEquals("새 버전(v2.5)이 나왔어요. 지금 업데이트를 누르면 받아서 설치해요.",(tag(home,"home_update_state") as TextView).text.toString())
        tag(home,"home_update_button").performClick()
        // The check runs off the main thread (r8-4); the button says so meanwhile.
        settle { home.screen==Screen.SETTINGS }
        assertEquals(1,starts)
        assertEquals(CallUpdate.Stage.WAITING,CallUpdate.fetchState(app).stage)
        assertTrue("installs when done",CallUpdate.wantsInstall(app))
        assertEquals("settings comes to the front, in the same Activity",Screen.SETTINGS,home.screen);assertNull(shadowOf(home).nextStartedActivity)
        // The download finishes while settings is open: it goes straight on to the install (first the allow-installs page here).
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"update_state")!=null }
        assertTrue(state(a),state(a).startsWith("새 버전(v2.5)을 받을 준비 중"))
        assertTrue(CallUpdate.check(app,Store()))
        tickOnce()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,shadowOf(a).nextStartedActivity?.action)
        assertFalse("the wish is used once",CallUpdate.wantsInstall(app))
    }

    @Test fun knownVersionNotYetHereOffersGetNow() {
        CallUpdate.startHook={ starts++ }
        assertTrue(runCatching { CallUpdate.check(app,Store(failDownload=true)) }.isFailure)
        assertEquals(CallUpdate.NET_WORDS,CallUpdate.fetchState(app).reason)
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"update_state")!=null }
        assertEquals(CallUpdate.NET_WORDS,state(a))
        // v3.1: a failed download of a known version offers [지금 업데이트], which gets it again (and installs when it is here).
        assertEquals("지금 업데이트",(tag(a,"update_install") as TextView).text.toString())
        tag(a,"update_install").performClick();settle { starts==1 }
        assertEquals(1,starts)
    }

    @Test fun aFileThatDoesNotMatchIsDeletedAndGotAgain() {
        CallUpdate.startHook={ starts++ }
        assertTrue(runCatching { CallUpdate.check(app,Store(badSha=true)) }.isFailure)
        assertEquals(CallUpdate.BROKEN_WORDS,CallUpdate.fetchState(app).reason);assertNull(CallUpdate.ready(app))
        assertFalse(File(CallUpdate.file(app).parentFile,"download.tmp").exists())
        assertTrue(CallUpdate.check(app,Store()));assertTrue(CallUpdate.verified(app))
        CallUpdate.file(app).writeBytes(apk.copyOf().also { it[7]=99 }) // same size, other bytes
        val a=launchMain(Screen.SETTINGS);idle()
        assertFalse(CallUpdate.install(a))
        assertFalse(CallUpdate.file(app).exists());assertNull(CallUpdate.ready(app));assertEquals(1,starts)
        assertTrue(CallUpdate.wantsInstall(app))
    }

    // ---- pictures for a person to look at (IEUM_SHOTS_DIR) ----
    private val out=System.getenv("IEUM_SHOTS_DIR")
    private fun shot(a:android.app.Activity,name:String,anchor:String="app_version") {
        idle()
        val dm=a.resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels
        val page=a.window.decorView
        fun lay() { page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));page.layout(0,0,w,h) }
        lay()
        val block=tag(a,anchor);var y=0;var v:View=block
        while(v.parent is View && v !is ScrollView) { y+=v.top;v=v.parent as View }
        (v as? ScrollView)?.scrollTo(0,(y-h/3).coerceAtLeast(0));lay()
        val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);page.draw(Canvas(bmp))
        File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun pictures() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        PreviewSeed.ensure(app)
        val hold=CountDownLatch(1);val reached=CountDownLatch(1)
        val work=Thread { runCatching { CallUpdate.check(app,Store(hold,reached)) } }.apply { start() }
        assertTrue(reached.await(10,TimeUnit.SECONDS))
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"update_state")!=null }
        shot(a,"1_settings_downloading_42")
        hold.countDown();work.join(10_000);tickOnce()
        shot(a,"2_settings_ready")
        CallUpdate.file(app).delete()
        val b=launchMain(Screen.SETTINGS)
        settle { tag(b,"update_state")!=null }
        shot(b,"3_settings_get_now")
        val home=launchMain(Screen.HOME)
        settle { tag(home,"home_update_button")!=null }
        shot(home,"4_home_card","home_update")
    }
}
