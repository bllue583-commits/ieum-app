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

/** Actual renderer over generated local rows. Some case/summary fixtures are display-only snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BusinessLayoutRegressionTest : ProtectedTextHostTestBase() {
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
        val dir=File("build/reports/business-layout-screens").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val json=JSONObject().put("id",id).put("pass",true).put("scope","API35_NATIVE_HOST_GENERATED_ROWS_NOT_DEVICE")
        values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/business-layout").apply { mkdirs() };File(dir,"$id.json").writeText(json.toString(2))
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
    @Test fun W01Font100FirstTitleBusinessAndAnalysisAreInsideBodyViewport() {
        val a=activity();render(a,"business-font100-host");val c=firstCard(a)
        val viewport=Rect();assertTrue(root(a).findViewWithTag<ScrollView>("main_content_scroll").getGlobalVisibleRect(viewport))
        val bounds=JSONObject()
        for(tag in listOf("call_title","call_business_status","call_analysis_status")) {
            val v=c.findViewWithTag<View>(tag);assertNotNull(tag,v);val visible=Rect();assertTrue(tag,v.getGlobalVisibleRect(visible))
            assertEquals(tag,v.height,visible.height());assertTrue(tag,visible.top>=viewport.top && visible.bottom<=viewport.bottom)
            bounds.put(tag,JSONArray(listOf(visible.left,visible.top,visible.right,visible.bottom)))
        }
        assertEquals("업무 진행: 완료",c.findViewWithTag<TextView>("call_business_status").text.toString())
        assertEquals("분석 연결 준비 중",root(a).findViewWithTag<TextView>("business_connection_status").text.toString())
        evidence("W01","font_scale" to 1,"viewport" to JSONArray(listOf(viewport.left,viewport.top,viewport.right,viewport.bottom)),"core_bounds" to bounds,"native_pixel_claim" to false)
    }
    @Test fun W02Font200ScrollsWithoutDroppingMemoOrClippingControls() {
        RuntimeEnvironment.setFontScale(2f);val a=activity();render(a,"business-font200-top-host")
        val scroll=root(a).findViewWithTag<ScrollView>("main_content_scroll");assertTrue(scroll.getChildAt(0).height>scroll.height)
        val c=firstCard(a);val memo=c.findViewWithTag<TextView>("call_summary");assertEquals(MEMO,memo.text.toString());assertNull(memo.ellipsize)
        assertEquals(memo.text.length,memo.layout.getLineEnd(memo.layout.lineCount-1));assertTrue(memo.maxLines>20)
        val refresh=root(a).findViewWithTag<Button>("business_refresh");assertTrue(refresh.height>=56*a.resources.displayMetrics.density)
        assertNull(refresh.ellipsize);assertEquals(refresh.text.length,refresh.layout.getLineEnd(refresh.layout.lineCount-1))
        val sp18=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,a.resources.displayMetrics)
        assertTrue(views(root(a)).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.all { it.textSize>=sp18-.5f })
        scroll.scrollTo(0,scroll.getChildAt(0).height);render(a,"business-font200-bottom-host")
        val guide=root(a).findViewWithTag<View>("business_guide");val visible=Rect();assertTrue(guide.getGlobalVisibleRect(visible));assertEquals(guide.height,visible.height())
        evidence("W02","font_scale" to 2,"scroll_content_height" to scroll.getChildAt(0).height,"viewport_height" to scroll.height,"note_unchanged" to true,"font_shrunk" to false,"guide_reachable" to true)
    }
    @Test fun W03KnownGroupProgressAndCallStatePrecedeStoredSummary() {
        val a=activity();val row=rows(a).getJSONObject(0);row.put("case_id","fixture-work-a").put("case_label","합성 납품 업무").put("summary","합성 저장 요약").put("status","processed")
            .put("task_id","fixture-task-a").put("business_status","waiting").put("task_revision",1)
        set(a,"topics",JSONArray().put(JSONObject().put("id","fixture-work-a").put("topic","합성 납품 업무").put("counterpart","합성 상대").put("status","waiting").put("summary","합성 누적 요약")))
        val input=rows(a).toString();redraw(a);render(a,"business-known-group-host")
        val group=root(a).findViewWithTag<LinearLayout>("business_group_fixture-work-a");val c=firstCard(a)
        val title=group.findViewWithTag<View>("business_group_title");val state=group.findViewWithTag<TextView>("business_group_status")
        assertTrue(group.indexOfChild(title)<group.indexOfChild(state));assertTrue(group.indexOfChild(state)<group.indexOfChild(c))
        assertEquals("업무 진행: 상대방 답변 대기",state.text.toString());assertEquals("업무 진행: 완료",c.findViewWithTag<TextView>("call_business_status").text.toString())
        assertTrue(c.indexOfChild(c.findViewWithTag("call_business_status"))<c.indexOfChild(c.findViewWithTag("call_summary")))
        assertTrue(group.indexOfChild(c)<group.indexOfChild(group.findViewWithTag("business_accumulated_summary")))
        assertEquals(input,rows(a).toString());evidence("W03","display_fixture_only" to true,"group_waiting_separate_from_call_done" to true,"saved_binding_test" to false)
    }
    @Test fun W04CaseIdsAndExistingChronologicalOrderArePreserved() {
        val a=activity();val files=DemoFixtures.catalogFiles();val topics=DemoFixtures.board().first
        set(a,"catalogFiles",files);set(a,"topics",topics);set(a,"demoMode",true);val before=files.toString();val board=topics.toString();redraw(a)
        val group=root(a).findViewWithTag<LinearLayout>("business_group_demo-case-a")
        val order=(0 until group.childCount).map { group.getChildAt(it).tag }.filterIsInstance<String>().filter { it.startsWith("recording_") }
        assertEquals(listOf("recording_demo-file-e","recording_demo-file-c","recording_demo-file-a"),order)
        assertNotNull(root(a).findViewWithTag<View>("business_group_demo-case-b"));assertEquals(before,files.toString());assertEquals(board,topics.toString())
        assertEquals(files.getJSONObject(0).getString("counterpart_ref"),files.getJSONObject(1).getString("counterpart_ref"))
        evidence("W04","same_counterpart_separate_case_groups" to 2,"unchanged_sort" to JSONArray(order),"input_and_board_unchanged" to true)
    }
    @Test fun W05ConfirmedEmptyShowsOneEmptyMessageAndOneBottomGuide() {
        val a=activity();set(a,"catalogFiles",JSONArray());set(a,"catalogLoaded",true);set(a,"catalogLoading",false);set(a,"catalogError",null);redraw(a)
        assertEquals(1,labels(body(a)).count { it=="저장된 업무별 통화가 없어요." })
        assertNull(root(a).findViewWithTag<View>("business_unassigned"));assertEquals(1,views(body(a)).count { it.tag=="business_guide" })
        assertFalse(labels(body(a)).any { it.contains("업무가 확인된 저장 통화가 없어요") })
        evidence("W05","confirmed_empty_messages" to 1,"repeated_empty_group_cards" to 0)
    }
    @Test fun W06HeaderRefreshIsAdjacentAndPreservesAllTables() {
        val a=activity();render(a,"business-refresh-control-host");val before=tables()
        val row=root(a).findViewWithTag<LinearLayout>("business_title_row");val refresh=root(a).findViewWithTag<Button>("business_refresh")
        assertEquals(row,refresh.parent);assertEquals(2,row.childCount);assertEquals("저장 목록 새로고침",refresh.contentDescription.toString())
        assertTrue(refresh.width>=56*a.resources.displayMetrics.density);assertNotNull(refresh.compoundDrawables[0]);refresh.performClick();settle(a);assertEquals(before,tables())
        assertFalse(NativeAnalysisAvailability.canTransfer());evidence("W06","all_tables_unchanged" to true,"tables_compared" to JSONObject(before).length(),"remote_transfer" to false)
    }
    @Test fun W07UserMemoDoneLockAndSourceVersionSurviveTabVisits() {
        val a=activity();val before=rowSnapshot();val first=rows(a).getJSONObject(0)
        assertEquals("done",first.getString("manual_status"));assertEquals(MEMO,first.getString("manual_note"))
        for(tab in listOf(0,2,3,1)) { root(a).findViewWithTag<Button>("primary_tab_$tab").performClick();settle(a) }
        assertEquals(before,rowSnapshot());assertEquals(MEMO,firstCard(a).findViewWithTag<TextView>("call_summary").text.toString())
        evidence("W07","catalog_row_snapshot_unchanged" to true,"manual_done_and_note_preserved" to true,"tabs_visited" to "0,2,3,1")
    }
    @Test fun W08StaleErrorAndLoadingRemainVisibleAndNeverLookLikeConfirmedEmpty() {
        val a=activity();val before=rows(a).toString();set(a,"catalogLoaded",true);set(a,"catalogLoading",true);set(a,"catalogError","합성 목록 오류 · 이전 기록 유지");redraw(a)
        val header=root(a).findViewWithTag<LinearLayout>("business_header");assertTrue(labels(header).contains("기기 저장 목록을 읽는 중이에요."))
        assertTrue(labels(header).contains("합성 목록 오류 · 이전 기록 유지"));assertTrue(labels(header).any { it.contains("마지막으로 읽은 목록 기준") })
        assertTrue(views(body(a)).any { (it.tag as? String)?.startsWith("recording_")==true });assertEquals(before,rows(a).toString())
        set(a,"catalogFiles",JSONArray());redraw(a);assertFalse(labels(body(a)).contains("저장된 업무별 통화가 없어요."));assertTrue(labels(body(a)).contains("통화 목록을 확인하지 못했어요."))
        evidence("W08","stale_error_loading_preserved" to true,"false_empty" to false)
    }
    @Test fun W09AnalysisPendingDoesNotInventBusinessStatus() {
        val a=activity();val row=rows(a).getJSONObject(0);row.remove("manual_status");row.remove("manual_note");row.put("status","collected")
        set(a,"catalogFiles",JSONArray().put(row));redraw(a);val c=firstCard(a)
        assertNull(c.findViewWithTag<View>("call_business_status"));assertTrue(c.findViewWithTag<TextView>("call_analysis_status").text.startsWith("분석 대기"))
        assertEquals("업무 연결 확인 필요",root(a).findViewWithTag<TextView>("business_group_status").text.toString())
        evidence("W09","analysis_to_business_state_promotion" to false,"display_snapshot_fixture" to true)
    }
    @Test fun W10DateCardKeepsSummaryFirstWhileBusinessCardUsesStatusFirst() {
        val a=activity();val row=rows(a).getJSONObject(0);row.put("summary","합성 저장 요약").put("status","processed");set(a,"catalogFiles",JSONArray().put(row));redraw(a)
        var c=firstCard(a);assertTrue(c.indexOfChild(c.findViewWithTag("call_business_status"))<c.indexOfChild(c.findViewWithTag("call_summary")))
        root(a).findViewWithTag<Button>("primary_tab_0").performClick();settle(a);c=firstCard(a)
        assertTrue(c.indexOfChild(c.findViewWithTag("call_summary"))<c.indexOfChild(c.findViewWithTag("call_business_status")))
        evidence("W10","business_status_first" to true,"date_summary_first_preserved" to true,"saved_summary_fixture_only" to true)
    }
    @Test fun W11GuideFollowsCallsAndRetainsConnectionAndGroupingRules() {
        val a=activity();val content=body(a);val guide=root(a).findViewWithTag<LinearLayout>("business_guide");val pending=root(a).findViewWithTag<LinearLayout>("business_unassigned")
        assertTrue(content.indexOfChild(guide)>content.indexOfChild(pending));assertTrue(labels(guide).any { it.contains("사람·업무를 합치지") })
        assertTrue(labels(guide).contains(NativeAnalysisAvailability.MESSAGE));assertEquals("업무 연결 확인 필요",pending.findViewWithTag<TextView>("business_group_status").text.toString())
        evidence("W11","long_guide_after_calls" to true,"connection_and_confirmation_not_hidden" to true)
    }
    @Test fun W12UnassignedInputOrderAndUnknownTimeRemainUnchanged() {
        val a=activity();val before=rows(a).toString();val expected=(0 until rows(a).length()).map { "recording_"+rows(a).getJSONObject(it).getString("file_id") }
        redraw(a);val pending=root(a).findViewWithTag<LinearLayout>("business_unassigned")
        val order=(0 until pending.childCount).map { pending.getChildAt(it).tag }.filterIsInstance<String>().filter { it.startsWith("recording_") }
        assertEquals(expected,order);assertEquals(before,rows(a).toString());assertTrue((0 until rows(a).length()).any { rows(a).getJSONObject(it).isNull("original_date") })
        evidence("W12","unassigned_input_order_unchanged" to true,"unknown_date_preserved" to true)
    }
    @Test fun W13NormalGroupShowsActualStoredBusinessProgressWithoutDemoTopic() {
        val row=io { GeneralLocalStore.catalog(context).listFiles().let { all->(0 until all.length()).map { all.getJSONObject(it) }.first { !it.isNull("original_date") } } }
        val id=row.getString("file_id")
        val case=io { RecordingWorkflowStore(context).use { store->store.confirmWork(id,store.snapshot(id),"합성 담당","합성 회사","합성 실제 저장 업무","").also { store.editBusinessProgress(id,store.snapshot(id),"waiting","합성 승인 대기",true) } } }
        val before=tables();val a=activity();assertEquals(0,(field(a,"topics") as JSONArray).length())
        val group=root(a).findViewWithTag<LinearLayout>("business_group_$case");assertNotNull(group)
        assertEquals("업무 진행: 상대방 답변 대기",group.findViewWithTag<TextView>("business_group_status").text.toString())
        assertEquals(before,tables());assertEquals("done",rows(a).let { all->(0 until all.length()).map { all.getJSONObject(it) }.single { it.getString("file_id")==id } }.getString("manual_status"))
        render(a,"stored-business-progress-host");evidence("W13","actual_protected_sql_task_progress" to true,"demo_topics" to 0,"manual_note_done_preserved" to true)
    }
    @Test fun W14NormalGroupDoesNotPromoteDemoOrConflictingSnapshotToStoredProgress() {
        val a=activity();val input=JSONObject(rows(a).getJSONObject(0).toString()).put("case_id","fixture-shared-case").put("task_id","fixture-shared-task")
            .put("business_status","waiting").put("task_revision",2)
        set(a,"topics",JSONArray().put(JSONObject().put("id","fixture-shared-case").put("status","done")))
        fun status(values:List<JSONObject>):String {
            set(a,"catalogFiles",JSONArray(values));val before=rows(a).toString();redraw(a);assertEquals(before,rows(a).toString())
            return root(a).findViewWithTag<TextView>("business_group_status").text.toString()
        }
        assertEquals("업무 진행: 상대방 답변 대기",status(listOf(input)))
        for(key in listOf("task_id","business_status","task_revision")) {
            val missing=JSONObject(input.toString()).apply { remove(key) };assertEquals(key,"업무 진행 확인 필요",status(listOf(missing)))
        }
        for((key,value) in listOf("task_id" to "fixture-other-task","business_status" to "done","task_revision" to 3)) {
            val other=JSONObject(input.toString()).put("file_id","fixture-other-file").put(key,value)
            assertEquals(key,"업무 진행 확인 필요",status(listOf(input,other)))
        }
        assertEquals("업무 진행 확인 필요",status(listOf(JSONObject(input.toString()).put("business_status","unsupported_status"))))
        assertEquals("업무 진행: 상대방 답변 대기",status(listOf(input,JSONObject(input.toString()).put("file_id","fixture-same-task-second-call"))))
        evidence("W14","display_snapshot_fixture_only" to true,"conflict_and_missing_held" to true,"demo_status_not_promoted" to true,"catalog_inputs_unchanged" to true)
    }
}
