package kr.callbrief

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kr.co.callsummary.vault.EncryptedAudioVault
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
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual normal activity with synthetic durable rows. Host graphics/JCE/SQLite, not device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GeneralLocalUiTest : ProtectedTextHostTestBase() {
    companion object { lateinit var keys:SyntheticPilotIntegrationTest.HostKeys }
    class HostActivity:MainActivity() { override fun createLocalStore()=GeneralLocalStoreTest.host(this,keys) }
    private lateinit var context:Context
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=executor.submit<T> { f() }.get(30,TimeUnit.SECONDS)
    @Before fun seedOnlyGeneratedRows() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys()
        RuntimeEnvironment.setFontScale(1f)
        context.deleteDatabase(GeneralLocalStore.DATABASE)
        File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
        io {
            GeneralLocalStoreTest.host(context,keys).use { s ->
                s.initializeEmpty();val source=GeneralLocalStoreTest.Source().apply { put();put("unknown","합성 날짜 미확인.wav",true) }
                assertEquals(2,s.importSelected(source).newVersions)
                val row=s.snapshot().getJSONObject(0)
                s.confirmTime(row.getString("file_id"),row,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli())
                val fresh=s.snapshot().getJSONObject(0)
                s.edit(fresh.getString("file_id"),fresh,"합성 직접 메모 · 견적 일정 확인","open")
            }
        }
    }
    @After fun stop() { executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun root(a:MainActivity)=field(a,"root") as View
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun idle(a:MainActivity) {
        repeat(250) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"catalogLoading")==false && field(a,"localDetailLoading")==false && field(a,"busy")==false) return;Thread.sleep(10) }
        fail("Host UI did not settle")
    }
    private fun activity()=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup().get().also { idle(it) }
    private fun click(v:View,label:String) { views(v).filterIsInstance<Button>().first { it.text.toString()==label || it.contentDescription?.toString()==label }.performClick();shadowOf(Looper.getMainLooper()).idle() }
    private fun tab(a:MainActivity,index:Int) { root(a).findViewWithTag<Button>("primary_tab_$index").performClick();idle(a) }
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun row()=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().getJSONObject(0) } }
    private fun render(v:View,name:String) {
        val w=822;val h=1782
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.WHITE);v.draw(Canvas(b))
        val dir=File("build/reports/ui-normal-local").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun normalDateAndBusinessTabsUseCanonicalRowsAndNoDemo() {
        val a=activity();assertEquals(false,field(a,"demoMode"));val text=labels(root(a))
        assertTrue(text.contains("통화 1개"));assertTrue(text.contains("합성 직접 메모 · 견적 일정 확인"))
        assertFalse(text.any { it.contains("저장된 요약 있음") });assertTrue(text.any { it.contains("분석 대기") })
        render(root(a),"normal-date-synthetic-host")
        tab(a,1);assertTrue(labels(root(a)).contains("업무 미분류 · 2개"));render(root(a),"normal-work-synthetic-host")
        tab(a,2);assertTrue(labels(root(a)).contains("todo"));assertTrue(labels(root(a)).contains("합성 직접 메모 · 견적 일정 확인"));render(root(a),"normal-todo-synthetic-host")
    }
    @Test fun cancelEditAndBackDoNotWriteCanonicalRows() {
        val a=activity();val before=row().toString();click(root(a),"상세 보기");idle(a)
        click(dialog().window!!.decorView,"직접 메모·완료 상태 수정")
        val edit=dialog();views(edit.window!!.decorView).filterIsInstance<EditText>().single().setText("취소할 합성 입력")
        edit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle(a);assertEquals(before,row().toString())
    }
    @Test fun normalEditSavesSameDbAndDoneFilterShowsIt() {
        val a=activity();click(root(a),"상세 보기");idle(a);click(dialog().window!!.decorView,"직접 메모·완료 상태 수정")
        val edit=dialog();views(edit.window!!.decorView).filterIsInstance<EditText>().single().setText("합성 수정 완료")
        val status=views(edit.window!!.decorView).filterIsInstance<Spinner>().single();status.setSelection(4)
        edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a)
        assertEquals("done",row().getString("manual_status"));assertEquals("합성 수정 완료",row().getString("manual_note"))
        dialog().dismiss();tab(a,2)
        val filter=views(root(a)).filterIsInstance<Spinner>().single();filter.setSelection(2);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(labels(root(a)).contains("합성 수정 완료"));render(root(a),"normal-done-synthetic-host")
    }
    @Test fun archiveAndRestoreButtonsUseSameCanonicalRecord() {
        val a=activity();val id=row().getString("file_id");click(root(a),"상세 보기");idle(a)
        click(dialog().window!!.decorView,"보관하기");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a)
        assertTrue(row().getBoolean("archived"));dialog().dismiss();tab(a,3)
        assertTrue(labels(root(a)).any { it.contains("합성 직접 메모") });render(root(a),"normal-archive-synthetic-host")
        click(root(a),"상세 보기");idle(a);click(dialog().window!!.decorView,"목록으로 되돌리기")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(a)
        assertFalse(row().getBoolean("archived"));assertEquals(id,row().getString("file_id"))
    }
    @Test fun unknownFilterAndProcessStyleActivityRecreationPreserveSelection() {
        val c=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup()
        val a=c.get();idle(a);click(root(a),"날짜 확인 필요 · 1개");assertNull(field(a,"selectedCallDate"))
        render(root(a),"normal-unknown-synthetic-host");c.pause().stop().destroy()
        val next=Robolectric.buildActivity(HostActivity::class.java).setup().get();idle(next)
        assertNull(field(next,"selectedCallDate"));assertTrue(labels(root(next)).contains("선택한 날짜로 돌아가기"))
        click(root(next),"선택한 날짜로 돌아가기");assertEquals("2026-10-10",field(next,"selectedCallDate"))
    }
    @Test fun normalDetailPreservesSourceMetadataAndVersionWithoutFakeSummary() {
        val a=activity();click(root(a),"상세 보기");idle(a);val d=dialog();val text=labels(d.window!!.decorView)
        assertTrue(text.any { it=="원본 통화 날짜: 2026-10-10" });assertTrue(text.any { it=="파일 버전: 1" })
        assertTrue(text.any { it=="새 원음 사본·목록·메모: 암호화 저장 · 기존 자료 별도 검토 · 실기기 검증 전" })
        assertFalse(text.any { it.contains("DB/WAL은 평문") })
        assertTrue(text.any { it.contains("저장된 요약 이력이 없어요") });render(d.window!!.decorView,"normal-detail-synthetic-host")
    }
    @Test fun normalLargeFontKeepsFourTabsAndDirectMemoReadable() {
        RuntimeEnvironment.setFontScale(2f);val a=activity();render(root(a),"normal-date-font200-synthetic-host")
        val nav=root(a).findViewWithTag<ViewGroup>("primary_navigation");assertEquals(2,nav.childCount)
        val minimum=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,a.resources.displayMetrics)
        assertTrue(views(root(a)).filterIsInstance<TextView>().filter { it.text.isNotBlank() }.all { it.textSize>=minimum-0.5f })
    }
    @Test fun ownReviewedSummaryAppearsInActualDetailWithoutFalseEmptyLabel() {
        val id=row().getString("file_id");io {RecordingWorkflowStore(context).use { it.reviewOwnSummary(id,it.snapshot(id),"PUBLIC_OWN_DETAIL_REVIEW") }}
        val a=activity();click(root(a),"상세 보기");idle(a);val text=labels(dialog().window!!.decorView)
        assertTrue(text.contains("직접 확인 요약 이력"));assertTrue(text.contains("PUBLIC_OWN_DETAIL_REVIEW"));assertFalse(text.any { it.startsWith("저장된 요약 이력이 없어요") })
        assertTrue(text.any { it.contains("모델 결과 이력은 아직 없어요") });render(dialog().window!!.decorView,"own-review-detail-host")
    }
}
