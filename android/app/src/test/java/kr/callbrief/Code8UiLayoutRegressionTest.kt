package kr.callbrief

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** UI presentation only, generated durable rows; no real provider, transport, or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code8UiLayoutRegressionTest : ProtectedTextHostTestBase() {
    companion object { lateinit var keys:SyntheticPilotIntegrationTest.HostKeys }
    class HostActivity:MainActivity() { override fun createLocalStore()=GeneralLocalStoreTest.host(this,keys) }
    private lateinit var context:Context
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=executor.submit<T>{ f() }.get(30,TimeUnit.SECONDS)
    @Before fun generatedRowsOnly() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();RuntimeEnvironment.setFontScale(1f)
        context.deleteDatabase(GeneralLocalStore.DATABASE)
        File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
        io { GeneralLocalStoreTest.host(context,keys).use { s ->
            s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply { put() })
            val row=s.snapshot().getJSONObject(0)
            s.confirmTime(row.getString("file_id"),row,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli())
            val fresh=s.snapshot().getJSONObject(0);s.edit(fresh.getString("file_id"),fresh,"합성 직접 메모 · 견적 일정 확인","open")
        } }
    }
    @After fun stop() { executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun set(a:MainActivity,name:String,value:Any?)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun root(a:MainActivity)=field(a,"root") as ViewGroup
    private fun texts(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun settle(a:MainActivity) {
        repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"catalogLoading")==false && field(a,"localDetailLoading")==false && field(a,"busy")==false)return;Thread.sleep(10) }
        fail("HOST_UI_NOT_SETTLED")
    }
    private fun activity()=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup().get().also { settle(it) }
    private fun snapshot()=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().toString() } }
    private fun row(a:MainActivity)=(field(a,"catalogFiles") as JSONArray).getJSONObject(0)
    private fun render(a:MainActivity) {
        val v=root(a);v.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));v.layout(0,0,822,1782)
    }
    private fun redraw(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("renderDates").apply { isAccessible=true }.invoke(a)
    private fun card(a:MainActivity)=root(a).findViewWithTag<ViewGroup>("recording_${row(a).optString("file_id")}")
    private fun click(v:View,label:String) { views(v).filterIsInstance<Button>().first { it.text.toString()==label || it.contentDescription?.toString()==label }.performClick();shadowOf(Looper.getMainLooper()).idle() }
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val j=JSONObject().put("id",id).put("pass",true).put("scope","API35_ROBOLECTRIC_HOST_UI_ONLY")
        values.forEach { j.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/code8-ui").apply { mkdirs() };File(dir,"$id.json").writeText(j.toString(2))
    }
    @Test fun L01FilenameStaysInDetailsAndConfirmedTimeNamesUnassignedCall() {
        val a=activity();val before=snapshot();val filename=row(a).getString("display_name")
        assertEquals("10:00 통화",card(a).findViewWithTag<TextView>("call_title").text.toString())
        assertFalse(texts(root(a)).any { it.contains(filename) })
        click(card(a),"상세 보기");settle(a)
        assertTrue(texts(dialog().window!!.decorView).contains("원본 파일명: $filename"));assertEquals(before,snapshot())
        evidence("L01","main_filename_hidden" to true,"detail_filename_exact" to true,"title" to "10:00 통화","db_unchanged" to true)
    }
    @Test fun L02UnconfirmedFilenameOrCollectionTimeCannotBecomeTitle() {
        val a=activity();val row=row(a);val before=row.toString()
        row.put("original_date",JSONObject.NULL).put("original_started_at",JSONObject.NULL)
            .put("filename_time_candidate","2026-10-10T01:00:00Z").put("collected_at","2026-10-10T05:35:00Z")
            .put("display_name","추정 업무_20261010_100000.wav")
        set(a,"selectedCallDate",null);redraw(a)
        assertEquals("시각 확인 필요 통화",card(a).findViewWithTag<TextView>("call_title").text.toString())
        assertFalse(texts(card(a)).any { it.contains("14:35") || it.contains("100000.wav") })
        val source=row.toString();redraw(a);assertEquals(source,row.toString())
        assertNotEquals(before,source) // This test's synthetic presentation input, never a stored edit.
        evidence("L02","neutral_title" to true,"filename_candidate_used" to false,"collection_time_used" to false)
    }
    @Test fun L03ConfirmedWorkTitleTakesPrecedenceAndSavedSummaryIsPreserved() {
        val a=activity();val row=row(a);row.put("case_label","확인된 합성 업무").put("summary","저장·승인된 합성 요약").put("status","processed")
        val before=row.toString();redraw(a)
        assertEquals("확인된 합성 업무",card(a).findViewWithTag<TextView>("call_title").text.toString())
        assertEquals("저장·승인된 합성 요약",card(a).findViewWithTag<TextView>("call_summary").text.toString())
        assertEquals(before,row.toString());assertTrue(card(a).indexOfChild(card(a).findViewWithTag("call_summary"))<card(a).indexOfChild(card(a).findViewWithTag("call_business_status")))
        evidence("L03","confirmed_title_preserved" to true,"saved_summary_preserved" to true,"synthetic_input_unchanged" to true)
    }
    @Test fun L04LongManualMemoFollowsStatusAndItsBytesAreUnchanged() {
        val a=activity();val row=row(a);val memo="직접 메모:  사용자가 쓴 원문\n"+"긴 합성 내용과 공백을 그대로 유지합니다. ".repeat(14)
        row.put("manual_note",memo);val before=snapshot();redraw(a);val c=card(a)
        val note=c.findViewWithTag<TextView>("call_summary")
        assertEquals(memo,note.text.toString());assertNull(c.findViewWithTag<View>("manual_note_label"))
        assertTrue(c.indexOfChild(c.findViewWithTag("call_business_status"))<c.indexOfChild(note))
        assertTrue(c.indexOfChild(c.findViewWithTag("call_analysis_status"))<c.indexOfChild(note))
        assertEquals(before,snapshot());assertEquals(memo,row.getString("manual_note"))
        evidence("L04","manual_bytes_unchanged" to true,"status_precedes_long_note" to true,"extra_note_marker" to 0,"db_unchanged" to true)
    }
    @Test fun L05MemoWithoutMarkerGetsOneSeparateMarkerAndNoPrefixInBody() {
        val a=activity();val row=row(a);val memo="  합성 수정 완료\n후속 확인  ";row.put("manual_note",memo);redraw(a)
        assertEquals(memo,card(a).findViewWithTag<TextView>("call_summary").text.toString())
        assertEquals(1,views(card(a)).count { it.tag=="manual_note_label" });assertNull(card(a).findViewWithTag<TextView>("call_summary").contentDescription)
        evidence("L05","generated_markers" to 1,"body_has_no_generated_prefix" to true,"user_whitespace_preserved" to true)
    }
    @Test fun L06WeeklySummaryImmediatelyFollowsCallsBeforeOneManagementEntry() {
        val a=activity();val content=field(a,"content") as ViewGroup;val c=card(a)
        assertEquals("weekly_summary",content.getChildAt(content.indexOfChild(c)+1).tag)
        assertEquals("record_management",content.getChildAt(content.indexOfChild(c)+2).tag)
        assertEquals(1,views(root(a)).count { it.tag=="open_record_management" })
        assertFalse(texts(root(a)).contains("저장 목록 새로고침"));assertFalse(texts(root(a)).contains("선택 폴더에서 가져오기"));assertFalse(texts(root(a)).contains("어제 수집·변경 확인"))
        val weekly=root(a).findViewWithTag<ViewGroup>("weekly_summary")
        assertTrue(texts(weekly).any { it.contains("확인") && it.contains("요약이 없어요") });assertFalse(texts(weekly).any { it.contains("견적 일정 확인") })
        assertFalse(NativeAnalysisAvailability.canTransfer())
        evidence("L06","weekly_adjacent_to_calls" to true,"management_entries" to 1,"generated_weekly_summary" to false,"can_transfer" to false)
    }
    @Test fun L07ManagementOpenCloseAndRefreshKeepCanonicalRows() {
        val a=activity();val before=snapshot();click(root(a),"기록 관리");val d=dialog()
        assertTrue(texts(d.window!!.decorView).containsAll(listOf("저장 목록 새로고침","선택 폴더에서 가져오기","어제 수집·변경 확인")))
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a);assertEquals(before,snapshot())
        click(root(a),"기록 관리");val refresh=dialog();click(refresh.window!!.decorView,"저장 목록 새로고침");settle(a)
        assertFalse(refresh.isShowing);assertEquals(before,snapshot())
        evidence("L07","three_existing_actions_available" to true,"open_close_db_unchanged" to true,"refresh_db_unchanged" to true)
    }
    @Test fun L08Font200KeepsReadableStatusTouchAreasAndTwoRowNavigation() {
        RuntimeEnvironment.setFontScale(2f);val a=activity();val memo="합성 긴 사용자 내용 ".repeat(30);row(a).put("manual_note",memo);redraw(a);render(a)
        val header=root(a).findViewWithTag<LinearLayout>("app_header");assertEquals(LinearLayout.HORIZONTAL,header.orientation)
        val setting=root(a).findViewWithTag<Button>("open_settings");assertEquals("초기 설정 열기",setting.contentDescription.toString())
        val controls=root(a).findViewWithTag<ViewGroup>("date_controls");assertEquals(3,controls.childCount)
        val min=(56*a.resources.displayMetrics.density).toInt();assertTrue(setting.width>=min && setting.height>=min)
        assertTrue((0 until controls.childCount).all { controls.getChildAt(it).width>=min && controls.getChildAt(it).height>=min })
        assertTrue(root(a).findViewWithTag<Button>("date_picker").contentDescription.toString().contains("2026년 10월 10일"))
        val nav=root(a).findViewWithTag<ViewGroup>("primary_navigation");assertEquals(2,nav.childCount)
        assertEquals(4,views(nav).filterIsInstance<Button>().size);assertTrue(views(nav).filterIsInstance<Button>().all { it.height>=min && it.width>=min })
        val body=card(a).findViewWithTag<TextView>("call_summary");assertEquals(memo,body.text.toString());assertNull(body.ellipsize);assertTrue(body.maxLines>2)
        val minimum=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,a.resources.displayMetrics)
        assertTrue(views(root(a)).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.all { it.textSize>=minimum-0.5f })
        val business=Rect();val analysis=Rect();assertTrue(card(a).findViewWithTag<View>("call_business_status").getGlobalVisibleRect(business));assertTrue(card(a).findViewWithTag<View>("call_analysis_status").getGlobalVisibleRect(analysis))
        assertEquals(card(a).findViewWithTag<View>("call_analysis_status").height,analysis.height())
        val location=IntArray(2);card(a).getLocationOnScreen(location)
        evidence("L08","font_scale" to 2,"width_px" to 822,"height_px" to 1782,"first_card_top_px" to location[1],"header_height_px" to header.height,"date_heading_height_px" to root(a).findViewWithTag<View>("date_heading").height,"navigation_rows" to 2,"min_touch_dp" to 56,"analysis_visible" to true,"font_shrunk" to false)
    }
    @Test fun L09UnknownDateFilterAndDoneTabKeepNeutralTitlesAndManualDone() {
        val a=activity();val row=row(a);row.put("manual_status","done");val before=snapshot()
        root(a).findViewWithTag<Button>("primary_tab_2").performClick();settle(a)
        val spinner=views(root(a)).filterIsInstance<Spinner>().single();spinner.setSelection(2);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(texts(root(a)).contains("todo"));assertTrue(texts(root(a)).contains("업무 진행: 완료"))
        assertTrue(texts(root(a)).contains("10:00 통화"));assertFalse(texts(root(a)).any { it.contains(".wav") })
        assertEquals(before,snapshot());assertEquals("done",row.getString("manual_status"))
        evidence("L09","todo_title_preserved" to true,"done_display_preserved" to true,"file_name_hidden" to true,"db_unchanged" to true)
    }
    @Test fun L10Font200KeepsCountAndUnitTogetherAndUnknownFilterReturns() {
        RuntimeEnvironment.setFontScale(2f);val a=activity();val files=field(a,"catalogFiles") as JSONArray
        val unknown=JSONObject(row(a).toString()).put("file_id","generated-unknown")
            .put("original_date",JSONObject.NULL).put("original_started_at",JSONObject.NULL)
        files.put(unknown);redraw(a);render(a)
        val filter=root(a).findViewWithTag<Button>("unknown_date_filter")
        assertEquals("날짜 확인 필요\n1개",filter.text.toString());assertEquals("날짜 확인 필요 · 1개",filter.contentDescription.toString())
        assertEquals("통화\n1개",root(a).findViewWithTag<TextView>("daily_count").text.toString())
        assertTrue(filter.width>=56*a.resources.displayMetrics.density);filter.performClick();shadowOf(Looper.getMainLooper()).idle()
        assertNull(field(a,"selectedCallDate"));assertTrue(texts(root(a)).contains("시각 확인 필요 통화"))
        root(a).findViewWithTag<Button>("return_to_selected_date").performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals("2026-10-10",field(a,"selectedCallDate"));assertTrue(unknown.isNull("original_date"))
        evidence("L10","count_unit_grouped" to true,"unknown_filter_accessible_label" to true,"return_date_preserved" to true,"unknown_source_not_relabelled" to true)
    }
}
