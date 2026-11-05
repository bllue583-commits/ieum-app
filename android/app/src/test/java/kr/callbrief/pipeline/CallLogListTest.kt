package kr.callbrief.pipeline

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
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

/** The call list with every call of the call log (v2.4, 11/4 user): recorded calls, missed calls with 다시 걸기, calls
 * without a recording and personal calls in one list, newest first. With IEUM_SHOTS_DIR set it also writes pictures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallLogListTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val out=System.getenv("IEUM_SHOTS_DIR")
    @After fun reset() { CallUi.applyTheme(app);CallsScreen.clock={ System.currentTimeMillis() } }
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun words(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }

    /** The rows of the list sheet in screen order (hairlines left out). */
    private fun rows(a:Activity)=(tag(a,"calls_list") as ViewGroup).let { s -> (0 until s.childCount).map(s::getChildAt).filter { it.tag is String } }

    @Test fun theFourKindsOfLinesShowInTimeOrder() {
        PreviewSeed.ensure(app)
        // Long after the calls: the answered one with no recording is no longer "being turned into text" (r8-4).
        CallsScreen.clock={ timeOf(13,45)+12*3_600_000L+60_000L }
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"calls_list")!=null && tag(a,"log_missed_900001")!=null }
        val missed=tag(a,"log_missed_900001");val none=tag(a,"log_no_recording_900002");val personal=tag(a,"log_personal_900003");val dead=tag(a,"log_not_connected_900004")
        assertNotNull(none);assertNotNull(personal);assertNotNull(dead)
        assertTrue(words(missed).contains("부재중 전화"))
        assertTrue(words(none).contains("받은 전화 2분 14초, 녹음 없음"))
        assertTrue(words(none).contains("박정우"))
        // A personal call: who and when only, no summary, no button.
        assertEquals(listOf("집",CallUi.time(timeOf(12,40)),"개인 통화"),words(personal).drop(1)) // after the badge letter
        assertTrue(views(personal).none { it.tag=="redial_button" })
        // A missed call: 다시 걸기 opens the dialer with the number.
        views(missed).first { it.tag=="redial_button" }.performClick()
        val dial=shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL,dial.action);assertEquals("01011112222",dial.data!!.schemeSpecificPart)
        // Recorded calls are there too, and everything is newest first.
        val list=rows(a)
        assertTrue(list.any { (it.tag as String).startsWith("call_") })
        val times=list.map { r -> words(r).firstNotNullOf { w -> Regex("(오전|오후) (\\d+):(\\d+)").find(w) }.let { m ->
            (m.groupValues[2].toInt()%12+if(m.groupValues[1]=="오후") 12 else 0)*60+m.groupValues[3].toInt() } }
        assertEquals(times.sortedDescending(),times)
        assertTrue(words(tag(a,"today_calls_date").rootView).any { it.contains("부재중 1건") })
    }

    @Test fun aLongDayIsBuiltInParts() {
        val day=CallUi.today()
        CallRecordStore(app).use { s -> s.reconcileCallLog((1..250).map { i ->
            FirstPass.LogEntry(800_000L+i,"010%08d".format(i),null,day.atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()+i*60_000L,0,android.provider.CallLog.Calls.MISSED_TYPE) }) }
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"calls_list")!=null }
        settle { rows(a).size==250 }
        assertEquals("부재중 250건",CallsScreen.counts(emptyList(),CallRecordStore(app).use { it.dayLog(day) }))
    }

    private fun timeOf(h:Int,m:Int)=CallUi.today().atTime(h,m).atZone(CallRecordStore.KST).toInstant().toEpochMilli()

    // ---- pictures for a person to look at (IEUM_SHOTS_DIR) ----
    private fun shot(a:Activity,name:String) {
        repeat(30) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        val dm=a.resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels
        val page=a.window.decorView
        page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));page.layout(0,0,w,h)
        // The whole scrolled page, not only the first screen.
        val scroll=views(page).filterIsInstance<ScrollView>().first();val inner=scroll.getChildAt(0)
        inner.measure(View.MeasureSpec.makeMeasureSpec(scroll.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        inner.layout(0,0,inner.measuredWidth,inner.measuredHeight)
        val bmp=Bitmap.createBitmap(w,inner.measuredHeight,Bitmap.Config.ARGB_8888);val c=Canvas(bmp)
        c.drawColor(CallUi.PAGE);c.translate(scroll.left.toFloat()+scroll.paddingLeft,0f);inner.draw(c)
        java.io.File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun pictures() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"log_missed_900001")!=null }
        shot(a,"calls_today")
    }

    @Config(qualifiers="w393dp-h852dp-night-xxhdpi")
    @Test fun picturesAtNight() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"log_missed_900001")!=null }
        shot(a,"calls_today_night")
    }
}
