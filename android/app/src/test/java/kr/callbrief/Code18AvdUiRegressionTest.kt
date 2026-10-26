package kr.callbrief

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Recreates AVD D01/D02 in actual renderers/native SQLite, not a new device execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code18AvdUiRegressionTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=worker.submit<T>{f()}.get(30,TimeUnit.SECONDS)
    @Before fun setup(){context=RuntimeEnvironment.getApplication();RuntimeEnvironment.setFontScale(1f)}
    @After fun stop(){worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(620,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.AT_MOST));v.layout(0,0,v.measuredWidth,v.measuredHeight)}
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun picture(v:View,name:String){layout(v);val b=Bitmap.createBitmap(v.measuredWidth.coerceAtLeast(1),v.measuredHeight.coerceAtLeast(1),Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val out=File("build/reports/code18-avd-ui-host").apply{mkdirs()};FileOutputStream(File(out,"$name.png")).use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun longText()=(0..1).joinToString("\n\n"){i->"직접 확인 요약\nPUBLIC_SYNTHETIC_TWO_CALL_REPORT_$i\n"+(1..12).joinToString("\n"){"합성 보고 문장 $i-$it · 실제 통화·분석·발송 없음"}+"\n필요 서류: 합성 검증 서류 $i · 직접 입력 기한 2026-10-20"}
    private fun dialog(onSaved:(String)->Unit={}):AlertDialog {val a=Robolectric.buildActivity(Activity::class.java).setup().get();return DailyReportReviewDialog.show(a,ReportWindow(LocalDate.parse("2026-10-12"),ZoneId.of("Asia/Seoul")),longText(),onSaved).also{shadowOf(Looper.getMainLooper()).idle();layout(it.window!!.decorView)}}
    @Test fun D02TwoCallMandatoryConfirmationIsFullyVisible(){val d=dialog();val decor=d.window!!.decorView;val check=decor.findViewWithTag<CheckBox>("daily_report_confirmation");val rect=Rect();assertTrue("mandatory checkbox is hidden",check.getGlobalVisibleRect(rect));assertTrue("mandatory checkbox is clipped",rect.height()>=check.height);assertTrue(views(decor).filterIsInstance<EditText>().single().text.contains("PUBLIC_SYNTHETIC_TWO_CALL_REPORT_1"));picture(decor,"report-two-calls-confirmation-host")}
    @Test fun D02UncheckedAndCancelCannotSave(){var saved=0;val d=dialog{saved++};d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(0,saved);assertTrue(d.isShowing);d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(0,saved)}
    @Test fun D02ConfirmedCallbackPreservesFullEditableReport(){var stored="";val d=dialog{stored=it};val editor=d.window!!.decorView.findViewWithTag<EditText>("daily_report_editor");editor.setText(longText()+"\nPUBLIC_USER_EDIT_AT_END");d.window!!.decorView.findViewWithTag<CheckBox>("daily_report_confirmation").isChecked=true;d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(longText()+"\nPUBLIC_USER_EDIT_AT_END",stored)}
    @Test fun D02Font200KeepsConfirmationVisibleWithoutShrinkingText(){RuntimeEnvironment.setFontScale(2f);val d=dialog();val check=d.window!!.decorView.findViewWithTag<CheckBox>("daily_report_confirmation");val rect=Rect();assertTrue("font200 checkbox hidden",check.getGlobalVisibleRect(rect));assertEquals("font200 checkbox clipped",check.height,rect.height());val minimum=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,check.resources.displayMetrics);assertTrue("font200 text shrunk: actual=${check.textSize}, expected=$minimum",check.textSize>=minimum-.5f);assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled);check.isChecked=true;assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled);picture(d.window!!.decorView,"report-two-calls-font200-host")}
    @Test fun D01OwnSummaryHistoryVisibleWithoutCanonicalAccept():Unit=io{val keys=SyntheticPilotIntegrationTest.HostKeys();val id=GeneralLocalStoreTest.host(context,keys).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});s.snapshot().getJSONObject(0).getString("file_id")};RecordingWorkflowStore(context).use{s->s.reviewOwnSummary(id,s.snapshot(id),"PUBLIC_OWN_REVIEW_HISTORY")};val history=GeneralLocalStore.catalog(context).history(id);val rows=history.optJSONArray("user_reviewed_history");assertNotNull("own reviewed history omitted",rows);assertEquals("PUBLIC_OWN_REVIEW_HISTORY",rows!!.getJSONObject(0).getString("text"));assertTrue(history.isNull("effective_summary_revision"));assertEquals(0,history.getJSONArray("versions").getJSONObject(0).getJSONArray("results").length())}
    @Test fun D01LatestOwnHistoryAndOldContentVersionsStayDistinct():Unit=io{val keys=SyntheticPilotIntegrationTest.HostKeys();val source=GeneralLocalStoreTest.Source().apply{put()};val id=GeneralLocalStoreTest.host(context,keys).use{s->s.initializeEmpty();s.importSelected(source);s.snapshot().getJSONObject(0).getString("file_id")};RecordingWorkflowStore(context).use{s->s.reviewOwnSummary(id,s.snapshot(id),"PUBLIC_OWN_FIRST");s.reviewOwnSummary(id,s.snapshot(id),"PUBLIC_OWN_SECOND")};val first=GeneralLocalStore.catalog(context).history(id).getJSONArray("user_reviewed_history");assertEquals(2,first.length());assertFalse(first.getJSONObject(0).getBoolean("is_current"));assertTrue(first.getJSONObject(1).getBoolean("is_current"));source.put(alternate=true);GeneralLocalStoreTest.host(context,keys).use{it.importSelected(source)};val changed=GeneralLocalStore.catalog(context).history(id).getJSONArray("user_reviewed_history");assertEquals(2,changed.length());assertFalse(changed.getJSONObject(0).getBoolean("is_current"));assertFalse(changed.getJSONObject(1).getBoolean("is_current"))}
}
