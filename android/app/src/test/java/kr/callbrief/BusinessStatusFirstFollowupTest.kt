package kr.callbrief

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Status-first follow-up on synthetic local rows. Host pixels only; no device or provider claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BusinessStatusFirstFollowupTest : ProtectedTextHostTestBase() {
    companion object {
        lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
        val MEMO="  직접 NOTE · 합성 업무 내용을 확인해 주세요.\n".repeat(20)
    }
    class HostActivity:MainActivity() { override fun createLocalStore()=GeneralLocalStoreTest.host(this,keys) }
    private lateinit var context:Context
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=executor.submit<T> { f() }.get(30,TimeUnit.SECONDS)
    @Before fun generatedRowsOnly() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();RuntimeEnvironment.setFontScale(1f)
        context.deleteDatabase(GeneralLocalStore.DATABASE);File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        io { GeneralLocalStoreTest.host(context,keys).use { store ->
            store.initializeEmpty();store.importSelected(GeneralLocalStoreTest.Source().apply { put();put("two","합성_날짜미확인.wav",true) })
            val first=store.snapshot().getJSONObject(0);val id=first.getString("file_id")
            store.confirmTime(id,first,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli())
            val fresh=store.snapshot().let { (0 until it.length()).map { index -> it.getJSONObject(index) }.first { it.getString("file_id")==id } }
            store.edit(id,fresh,MEMO,"done")
        } }
    }
    @After fun cleanup() { executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun set(a:MainActivity,n:String,value:Any?)=MainActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.set(a,value)
    private fun root(a:MainActivity)=field(a,"root") as ViewGroup
    private fun body(a:MainActivity)=field(a,"content") as LinearLayout
    private fun rows(a:MainActivity)=field(a,"catalogFiles") as JSONArray
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun settle(a:MainActivity) {
        repeat(250) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"catalogLoading")==false && field(a,"localDetailLoading")==false && field(a,"busy")==false)return;Thread.sleep(10) }
        fail("HOST_NOT_SETTLED")
    }
    private fun activity():HostActivity {
        val a=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup().get()
        settle(a);root(a).findViewWithTag<Button>("primary_tab_1").performClick();settle(a);return a
    }
    private fun redraw(a:MainActivity) { MainActivity::class.java.getDeclaredMethod("renderTopics").apply { isAccessible=true }.invoke(a) }
    private fun render(a:MainActivity,name:String) {
        val v=root(a);val w=720;val h=1600
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        val dir=File("build/reports/business-status-first-screens").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val json=JSONObject().put("id",id).put("pass",true).put("scope","API35_NATIVE_HOST_GENERATED_ROWS_NOT_DEVICE")
        values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/business-status-first").apply { mkdirs() };File(dir,"$id.json").writeText(json.toString(2))
    }
    private fun rowSnapshot()=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().toString() } }
    private fun tables():String {
        val result=JSONObject()
        TextStoreTestSupport.open(context,GeneralLocalStore.DATABASE).use { db ->
            val names=mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use { c -> while(c.moveToNext())names+=c.getString(0) }
            names.forEach { name ->
                val data=JSONArray()
                db.rawQuery("SELECT * FROM \"${name.replace("\"","\"\"")}\"",null).use { c ->
                    while(c.moveToNext()) {
                        val row=JSONArray()
                        for(i in 0 until c.columnCount) row.put(when(c.getType(i)) { 0->JSONObject.NULL;1->c.getLong(i);2->c.getDouble(i);4->android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP);else->c.getString(i) })
                        data.put(row)
                    }
                };result.put(name,data)
            }
        };return result.toString()
    }
    private fun firstCard(a:MainActivity)=views(body(a)).filterIsInstance<LinearLayout>().first { (it.tag as? String)?.startsWith("recording_")==true }
    private fun rect(v:View):JSONObject {
        val r=Rect();val visible=v.getGlobalVisibleRect(r)
        val location=IntArray(2);v.getLocationOnScreen(location)
        return JSONObject().put("visible",visible).put("visible_height",if(visible)r.height() else 0)
            .put("view_height",v.height).put("bounds",JSONArray(listOf(r.left,r.top,r.right,r.bottom)))
            .put("layout_top",location[1]).put("layout_bottom",location[1]+v.height)
    }
    private fun firstScreenComparedWithSameViewAfterScroll(scale:Float,id:String,insetTop:Int=0,insetBottom:Int=0) {
        RuntimeEnvironment.setFontScale(scale)
        val a=activity();val input=rows(a).toString();val before=tables()
        if (insetTop!=0 || insetBottom!=0) root(a).dispatchApplyWindowInsets(android.view.WindowInsets.Builder()
            .setSystemWindowInsets(android.graphics.Insets.of(0,insetTop,0,insetBottom)).build())
        render(a,"${id}-first-screen")
        val c=firstCard(a);val state=c.findViewWithTag<TextView>("call_business_status")
        val viewport=root(a).findViewWithTag<ScrollView>("main_content_scroll")
        val viewportBounds=Rect();assertTrue(viewport.getGlobalVisibleRect(viewportBounds))
        val first=rect(state);val firstStatus=state.text.toString();val identity=System.identityHashCode(state)
        val title=c.findViewWithTag<TextView>("call_title");val contextView=c.findViewWithTag<TextView>("call_context")
        val firstTitle=rect(title);val firstContext=rect(contextView)
        val position=IntArray(2);state.getLocationOnScreen(position)
        // Scroll only: keep the Activity, root, card and status TextView identical.
        val target=(viewport.scrollY+position[1]-viewportBounds.top-(8*a.resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        viewport.scrollTo(0,target);render(a,"${id}-same-view-scrolled")
        val same=root(a).findViewWithTag<LinearLayout>(c.tag).findViewWithTag<TextView>("call_business_status")
        assertSame(state,same)
        val full=rect(same);val firstHeight=first.getInt("visible_height");val fullHeight=full.getInt("visible_height")
        assertEquals("same status is fully visible after scroll",same.height,fullHeight)
        assertEquals(firstStatus,same.text.toString());assertEquals("업무 진행: 완료",firstStatus)
        assertEquals(input,rows(a).toString());assertEquals(before,tables())
        val memo=c.findViewWithTag<TextView>("call_summary");assertEquals(MEMO,memo.text.toString());assertNull(memo.ellipsize)
        assertEquals(memo.text.length,memo.layout.getLineEnd(memo.layout.lineCount-1))
        val refresh=root(a).findViewWithTag<Button>("business_refresh")
        assertTrue(refresh.width>=56*a.resources.displayMetrics.density);assertTrue(refresh.height>=56*a.resources.displayMetrics.density)
        val sp18=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,a.resources.displayMetrics)
        assertTrue(views(root(a)).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.all { it.textSize>=sp18-.5f })
        val result=JSONObject().put("id",id).put("pass",firstHeight==fullHeight).put("scope","API35_NATIVE_HOST_GENERATED_ROWS_NOT_DEVICE")
            .put("font_scale",scale).put("screen_pixels",JSONArray(listOf(720,1600))).put("density",a.resources.displayMetrics.density)
            .put("supplied_system_insets_pixels",JSONArray(listOf(0,insetTop,0,insetBottom)))
            .put("insets_are_host_profile_not_device_metadata",true)
            .put("viewport",JSONArray(listOf(viewportBounds.left,viewportBounds.top,viewportBounds.right,viewportBounds.bottom)))
            .put("first_status",first).put("same_status_after_scroll",full).put("status_text",firstStatus)
            .put("same_view",true).put("view_identity_before",identity).put("view_identity_after",System.identityHashCode(same))
            .put("first_visible_height",firstHeight).put("fully_visible_height_after_scroll",fullHeight)
            .put("first_title",firstTitle).put("first_context",firstContext)
            .put("all_tables_unchanged",true).put("tables_compared",JSONObject(before).length()).put("input_rows_unchanged",true)
            .put("raw_memo_unchanged",true).put("memo_characters",MEMO.length).put("manual_done_unchanged",true)
            .put("font_shrunk",false).put("status_text_size_px",state.textSize).put("refresh_width",refresh.width).put("refresh_height",refresh.height)
        val dir=File("build/reports/business-status-first").apply { mkdirs() };File(dir,"$id.json").writeText(result.toString(2))
        assertEquals("first-screen status height must match this same fully visible View after scroll",fullHeight,firstHeight)
    }
    @Test fun S01Font100SameStatusViewIsFullyVisibleBeforeAndAfterScroll() = firstScreenComparedWithSameViewAfterScroll(1f,"S01")
    @Test fun S02Font200SameStatusViewIsFullyVisibleBeforeAndAfterScroll() = firstScreenComparedWithSameViewAfterScroll(2f,"S02")
    @Test fun S05Font100SameStatusViewWithSuppliedSystemBarInsets() = firstScreenComparedWithSameViewAfterScroll(1f,"S05",34,92)
    @Test fun S06Font200SameStatusViewWithSuppliedSystemBarInsets() = firstScreenComparedWithSameViewAfterScroll(2f,"S06",34,92)
    @Test fun S03BusinessStateTitleContextAnalysisMemoOrderDoesNotRecomputeManualState() {
        val a=activity();val row=rows(a).getJSONObject(0)
        row.put("case_id","fixture-order").put("case_label","합성 확인 업무").put("counterpart_label","합성 상대").put("company_label","합성 회사")
            .put("task_id","fixture-order-task").put("business_status","waiting").put("task_revision",1)
        set(a,"topics",JSONArray().put(JSONObject().put("id","fixture-order").put("topic","합성 확인 업무").put("status","waiting").put("summary","합성 누적 요약")))
        val input=rows(a).toString();val board=(field(a,"topics") as JSONArray).toString();val before=tables();redraw(a);render(a,"S03-manual-done-separate-from-group")
        val c=firstCard(a);val tags=(0 until c.childCount).map { c.getChildAt(it).tag }.filterIsInstance<String>()
        assertEquals(listOf("call_business_status","call_title","call_context","call_analysis_status"),tags.take(4))
        assertTrue(tags.indexOf("call_analysis_status")<tags.indexOf("call_summary"))
        assertEquals("업무 진행: 완료",c.findViewWithTag<TextView>("call_business_status").text.toString())
        assertEquals("업무 진행: 상대방 답변 대기",root(a).findViewWithTag<TextView>("business_group_status").text.toString())
        assertEquals("합성 상대 · 합성 회사 · 10:00",c.findViewWithTag<TextView>("call_context").text.toString())
        assertEquals(MEMO,c.findViewWithTag<TextView>("call_summary").text.toString())
        assertEquals(input,rows(a).toString());assertEquals(board,(field(a,"topics") as JSONArray).toString());assertEquals(before,tables())
        evidence("S03","card_order" to JSONArray(tags),"manual_state_recomputed" to false,"group_and_call_states_separate" to true,"all_tables_unchanged" to true,"display_snapshot_fixture_only" to true)
    }
    @Test fun S04MissingBusinessStateKeepsTitleContextAnalysisAndNoInventedState() {
        val a=activity();val row=rows(a).getJSONObject(0);row.remove("manual_status");row.remove("manual_note");row.put("status","collected")
        set(a,"catalogFiles",JSONArray().put(row));val input=rows(a).toString();val before=tables();redraw(a)
        val c=firstCard(a);val tags=(0 until c.childCount).map { c.getChildAt(it).tag }.filterIsInstance<String>()
        assertEquals(listOf("call_title","call_context","call_analysis_status"),tags.take(3));assertNull(c.findViewWithTag<View>("call_business_status"))
        assertTrue(c.findViewWithTag<TextView>("call_analysis_status").text.startsWith("분석 대기"))
        assertEquals("업무 연결 확인 필요",root(a).findViewWithTag<TextView>("business_group_status").text.toString())
        assertEquals(input,rows(a).toString());assertEquals(before,tables())
        evidence("S04","business_state_invented" to false,"card_order" to JSONArray(tags),"input_rows_unchanged" to true,"all_tables_unchanged" to true,"display_snapshot_fixture_only" to true)
    }
}
