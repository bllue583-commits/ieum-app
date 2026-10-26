package kr.callbrief

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream

/** Actual code4 renderer on the host: API35, 360x800dp/xhdpi, 720x1600px. Not an AVD or TalkBack test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDateLayoutTest : ProtectedTextHostTestBase() {
    @Before fun normalFont() { RuntimeEnvironment.setFontScale(1f) }
    private fun intent(): Intent {
        val app=RuntimeEnvironment.getApplication()
        app.applicationInfo.flags=app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        return Intent(app,MainActivity::class.java).putExtra("demo",true)
    }
    private fun activity()=Robolectric.buildActivity(MainActivity::class.java,intent()).setup().get().also { idle() }
    private fun idle()=shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun set(a:MainActivity,name:String,value:Any?)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value)
    private fun root(a:MainActivity)=field(a,"root") as ViewGroup
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(v:View)=all(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun clickTag(a:MainActivity,tag:String) { assertTrue(root(a).findViewWithTag<Button>(tag).performClick());idle() }
    private fun render(a:MainActivity,name:String) {
        val view=root(a); val width=720;val height=1600
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE);view.draw(Canvas(bitmap))
        val directory=File("build/reports/ui-native").apply { mkdirs() }
        FileOutputStream(File(directory,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun normalDateControlsUseOneRowAndSummaryIsVisibleBeforeManagementDetails() {
        val a=activity();render(a,"code4-dates-720-font100")
        val controls=root(a).findViewWithTag<LinearLayout>("date_controls")
        assertEquals(3,controls.childCount)
        assertEquals(listOf("date_previous","date_picker","date_next"),(0 until 3).map { controls.getChildAt(it).tag })
        val picker=root(a).findViewWithTag<Button>("date_picker")
        assertTrue(picker.contentDescription.toString().contains("2026년 10월 10일 토요일"))
        val minimum=(56*a.resources.displayMetrics.density).toInt()
        assertTrue((0 until 3).all { controls.getChildAt(it).width>=minimum && controls.getChildAt(it).height>=minimum })
        val card=root(a).findViewWithTag<ViewGroup>("recording_demo-file-a")
        assertEquals(listOf("call_context","call_title","call_summary","call_business_status"),(0 until 4).map { card.getChildAt(it).tag })
        val summary=card.findViewWithTag<TextView>("call_summary");val visible=Rect()
        assertTrue(summary.getGlobalVisibleRect(visible));assertEquals(summary.height,visible.height())
        val nav=root(a).findViewWithTag<View>("primary_navigation");val bounds=Rect();nav.getGlobalVisibleRect(bounds)
        assertTrue(visible.bottom<=bounds.top);assertTrue(summary.text.toString().contains("준비물 확인이 추가"))
        assertFalse(texts(card).any { it.startsWith("원본 통화 날짜:")||it.startsWith("수집:")||it.contains("폴더에서 마지막") })
        assertFalse(summary.text.toString().contains("[합성 예시]"))
        assertTrue(texts(root(a)).contains("합성 예시 · 실제 전송 없음"))
        val geometry=org.json.JSONObject().put("engine","Robolectric native graphics API35").put("width_px",720).put("height_px",1600).put("font_scale",1)
            .put("summary_top_px",visible.top).put("summary_bottom_px",visible.bottom).put("navigation_top_px",bounds.top).put("device_runtime",false)
        File("build/reports/ui-native/code4-layout-geometry.json").writeText(geometry.toString(2))
    }
    @Test fun originalCallAndCollectionTimesRemainDistinctInsideDetails() {
        val a=activity();val sourceBefore=(field(a,"catalogFiles") as JSONArray).toString()
        val first=root(a).findViewWithTag<ViewGroup>("recording_demo-file-a")
        all(first).filterIsInstance<Button>().single().performClick();idle()
        val dialog=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val labels=texts(dialog.window!!.decorView)
        assertTrue(labels.contains("원본·수집 정보"));assertTrue(labels.contains("원본 통화 날짜: 2026-10-10"))
        assertTrue(labels.contains("통화 시작: 10월 10일 10:00"));assertTrue(labels.contains("수집: 10월 10일 14:35"))
        assertTrue(labels.contains("파일 버전: 2"));assertEquals(sourceBefore,(field(a,"catalogFiles") as JSONArray).toString())
    }
    @Test fun unknownDateFilterReturnsToTheSelectedDateAndScrollWithoutRelabelingSources() {
        val a=activity();render(a,"code4-filter-before")
        val scroll=root(a).findViewWithTag<ScrollView>("main_content_scroll");scroll.scrollTo(0,160)
        clickTag(a,"unknown_date_filter");assertNull(field(a,"selectedCallDate"));assertEquals("2026-10-10",field(a,"lastSelectedCallDate"))
        render(a,"code4-dates-unknown-720")
        assertTrue(texts(root(a)).contains("통화 1개"));assertTrue(texts(root(a)).contains("통화 날짜·시각 확인 필요"))
        assertFalse(texts(root(a)).any { it.contains("준비물 확인이 추가") })
        clickTag(a,"return_to_selected_date");render(a,"code4-filter-return")
        assertEquals("2026-10-10",field(a,"selectedCallDate"));assertEquals(160,scroll.scrollY)
        val source=(field(a,"catalogFiles") as JSONArray).getJSONObject(3)
        assertTrue(source.isNull("original_date"));assertTrue(source.isNull("original_started_at"))
    }
    @Test fun systemBackAndRecreationPreserveTheUnknownFilterReturnDate() {
        val controller=Robolectric.buildActivity(MainActivity::class.java,intent()).setup();val a=controller.get();idle()
        clickTag(a,"date_previous");assertEquals("2026-10-09",field(a,"selectedCallDate"));clickTag(a,"unknown_date_filter")
        val saved=Bundle();controller.pause().saveInstanceState(saved).stop().destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java,intent()).create(saved).start().resume().visible().get();idle()
        assertNull(field(restored,"selectedCallDate"));assertEquals("2026-10-09",field(restored,"lastSelectedCallDate"))
        restored.onBackPressedDispatcher.onBackPressed();idle()
        assertFalse(restored.isFinishing);assertEquals("2026-10-09",field(restored,"selectedCallDate"))
    }
    @Test fun noUnknownSourcesMeansNoEmptyWarningFilter() {
        val a=activity();val files=field(a,"catalogFiles") as JSONArray
        val known=JSONArray();for(i in 0 until files.length()) if(!files.getJSONObject(i).isNull("original_date")) known.put(files.getJSONObject(i))
        set(a,"catalogFiles",known)
        MainActivity::class.java.getDeclaredMethod("renderDates").apply { isAccessible=true }.invoke(a)
        assertNull(root(a).findViewWithTag<View>("unknown_date_filter"));assertTrue(texts(root(a)).contains("통화 2개"))
        assertFalse(texts(root(a)).any { it.contains("날짜 확인 필요 · 0개") })
    }
    @Test fun largeFontsExpandLayoutWithoutShrinkingOrClippingSummary() {
        for(scale in listOf(1.5f,2f)) {
            RuntimeEnvironment.setFontScale(scale);val a=activity();render(a,"code4-dates-720-font${(scale*100).toInt()}")
            val controls=root(a).findViewWithTag<LinearLayout>("date_controls");assertEquals(3,controls.childCount)
            assertSame(controls,root(a).findViewWithTag<View>("date_picker").parent)
            val card=root(a).findViewWithTag<ViewGroup>("recording_demo-file-a");val summary=card.findViewWithTag<TextView>("call_summary")
            assertNull(summary.ellipsize);assertTrue(summary.maxLines>2)
            val minimum=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,a.resources.displayMetrics)
            assertTrue(summary.textSize>=minimum-0.5f)
            val scroll=root(a).findViewWithTag<ScrollView>("main_content_scroll");scroll.scrollTo(0,card.top+summary.top);idle()
            val visible=Rect();assertTrue("fontScale $scale summary must be reachable",summary.getGlobalVisibleRect(visible))
            val nav=root(a).findViewWithTag<ViewGroup>("primary_navigation");assertEquals(4,all(nav).filterIsInstance<Button>().size)
            assertTrue(all(nav).filterIsInstance<Button>().all { it.isShown&&it.height>=(56*a.resources.displayMetrics.density).toInt() })
            render(a,"code4-summary-scroll-font${(scale*100).toInt()}")
            a.finish()
        }
    }
    @Test fun navigationAndDetailsNeverRewriteStoredSyntheticTextOrMergeWork() {
        val a=activity();val rawBefore=(field(a,"catalogFiles") as JSONArray).toString();val tasksBefore=(field(a,"tasks") as JSONArray).toString()
        clickTag(a,"date_previous");clickTag(a,"date_next");clickTag(a,"unknown_date_filter");clickTag(a,"return_to_selected_date")
        val card=root(a).findViewWithTag<ViewGroup>("recording_demo-file-a");all(card).filterIsInstance<Button>().single().performClick();idle()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).dismiss();idle()
        assertEquals(rawBefore,(field(a,"catalogFiles") as JSONArray).toString());assertEquals(tasksBefore,(field(a,"tasks") as JSONArray).toString())
        val files=field(a,"catalogFiles") as JSONArray
        assertEquals(files.getJSONObject(0).getString("counterpart_ref"),files.getJSONObject(1).getString("counterpart_ref"))
        assertNotEquals(files.getJSONObject(0).getString("case_id"),files.getJSONObject(1).getString("case_id"))
    }
}
