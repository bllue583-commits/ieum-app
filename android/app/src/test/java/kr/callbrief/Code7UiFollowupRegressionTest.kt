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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Activity callbacks with generated rows, host JCE/SQLite and controller destroy. No device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code7UiFollowupRegressionTest : ProtectedTextHostTestBase() {
    companion object {
        lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
        var block=false
        lateinit var entered:CountDownLatch
        lateinit var release:CountDownLatch
        lateinit var constructed:CountDownLatch
    }
    class HostActivity:MainActivity() {
        override fun createLocalStore():GeneralLocalStore {
            if(block) { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) }
            return GeneralLocalStoreTest.host(this,keys).also { constructed.countDown() }
        }
    }
    private lateinit var context:Context
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=executor.submit<T> { f() }.get(20,TimeUnit.SECONDS)
    @Before fun generatedOnly() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();RuntimeEnvironment.setFontScale(1f)
        context.deleteDatabase(GeneralLocalStore.DATABASE)
        File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
        block=false;entered=CountDownLatch(1);release=CountDownLatch(1);constructed=CountDownLatch(1)
        io { GeneralLocalStoreTest.host(context,keys).use { s ->
            s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply { put("unknown","합성 날짜 미확인.wav",true) })
        } }
    }
    @After fun cleanup() { release.countDown();block=false;executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun root(a:MainActivity)=field(a,"root") as ViewGroup
    private fun labels(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun settle(a:MainActivity) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if(field(a,"busy")==false && field(a,"catalogLoading")==false && field(a,"localDetailLoading")==false)return
            Thread.sleep(10)
        };fail("HOST_UI_NOT_SETTLED")
    }
    private fun controller()=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup().also { settle(it.get()) }
    private fun click(v:View,label:String) { views(v).filterIsInstance<Button>().first { it.text.toString()==label }.performClick();shadowOf(Looper.getMainLooper()).idle() }
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun row()=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().getJSONObject(0) } }
    private fun history(a:MainActivity):AlertDialog {
        click(root(a),"날짜 확인 필요 · 1개");click(root(a),"상세 보기");settle(a)
        return dialog().also { assertTrue(labels(it.window!!.decorView).contains("원본 통화 날짜: 확인 필요")) }
    }
    private fun timeForm(detail:AlertDialog,value:String):AlertDialog {
        click(detail.window!!.decorView,"통화 날짜·시각 직접 확인")
        return dialog().also { views(it.window!!.decorView).filterIsInstance<EditText>().single().setText(value) }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val data=JSONObject().put("id",id).put("acceptance_pass",true)
            .put("scope","API35_ROBOLECTRIC_HOST_GENERATED_ROWS_NOT_ANDROID_DEVICE")
        values.forEach { data.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/code7-followup").apply { mkdirs() };File(dir,"$id.json").writeText(data.toString(2))
    }
    private fun screen(v:View,name:String) {
        val w=822;val h=1782
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.WHITE);v.draw(Canvas(b))
        val dir=File("build/reports/code7-followup-screens").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun U01DestroyBeforeStoreConstructionDoesNotShowRenderOrRefresh() {
        val c=controller();val a=c.get();val before=row().toString();block=true
        var admitted=false
        MainActivity::class.java.declaredMethods.single { it.name=="localOperation" && it.parameterCount==2 }
            .apply { isAccessible=true }.invoke(a,{ _:GeneralLocalStore -> admitted=true;"GENERATED_RESULT" },{})
        shadowOf(Looper.getMainLooper()).idle();assertTrue(entered.await(5,TimeUnit.SECONDS))
        val dialogs=ShadowDialog.getShownDialogs().size;var mutations=0
        root(a).setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent:View?, child:View?) { mutations++ }
            override fun onChildViewRemoved(parent:View?, child:View?) { mutations++ }
        })
        c.pause().stop().destroy();assertTrue(a.isDestroyed);assertFalse(a.isFinishing)
        release.countDown();assertTrue(constructed.await(5,TimeUnit.SECONDS))
        repeat(25) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertFalse(admitted);assertNull(field(a,"activeLocalStore"));assertEquals(false,field(a,"busy"))
        assertEquals(false,field(a,"catalogLoading"));assertEquals(0,mutations)
        assertEquals(dialogs,ShadowDialog.getShownDialogs().size);assertEquals(before,row().toString())
        evidence("U01","is_destroyed" to true,"is_finishing" to false,"action_admitted" to false,
            "new_dialogs" to 0,"root_mutations" to 0,"refresh_started" to false,"busy_cleared" to true,
            "store_reference_cleared" to true,"saved_rows_unchanged" to true,"os_crash_test" to false)
    }
    @Test fun U02ExplicitLateNoticeOnDeadActivityDoesNotOpenWindow() {
        val c=controller();val a=c.get();c.pause().stop().destroy();val before=ShadowDialog.getShownDialogs().size
        MainActivity::class.java.getDeclaredMethod("notice",String::class.java).apply { isAccessible=true }.invoke(a,"GENERATED_LATE_NOTICE")
        assertEquals(before,ShadowDialog.getShownDialogs().size)
        evidence("U02","new_dialogs" to 0,"dead_activity" to true)
    }
    @Test fun U03TimeSaveRefreshesSameOpenDetailAndItsNextEditBaseline() {
        val c=controller();val a=c.get();val detail=history(a)
        timeForm(detail,"2026-10-10 10:00").getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertEquals("2026-10-10",row().getString("original_date"));assertTrue(detail.isShowing)
        assertSame(detail,field(a,"fileHistoryDialog"));assertFalse(labels(detail.window!!.decorView).contains("원본 통화 날짜: 확인 필요"))
        assertTrue(labels(detail.window!!.decorView).contains("통화 시작: 10월 10일 10:00"))
        dialog().dismiss();screen(detail.window!!.decorView,"time-saved-detail-host")
        timeForm(detail,"2026-10-10 11:00").getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertTrue(labels(detail.window!!.decorView).contains("통화 시작: 10월 10일 11:00"))
        evidence("U03","same_detail_instance" to true,"saved_date" to "2026-10-10","unknown_label_removed" to true,
            "second_edit_fresh_revision" to true,"source_version" to row().getInt("source_version"))
        c.pause().stop().destroy()
    }
    @Test fun U04CancelledTimeFormKeepsDbAndOpenDetailUnknown() {
        val c=controller();val a=c.get();val detail=history(a);val before=row().toString()
        timeForm(detail,"2026-10-10 10:00").getButton(AlertDialog.BUTTON_NEGATIVE).performClick();settle(a)
        assertEquals(before,row().toString());assertTrue(detail.isShowing)
        assertTrue(labels(detail.window!!.decorView).contains("원본 통화 날짜: 확인 필요"))
        evidence("U04","db_unchanged" to true,"detail_unchanged" to true,"success_notice" to false)
        c.pause().stop().destroy()
    }
    @Test fun U05InvalidTimeDoesNotWriteOrReplaceOpenDetail() {
        val c=controller();val a=c.get();val detail=history(a);val before=row().toString()
        val form=timeForm(detail,"2026-02-30 10:00");form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertEquals(before,row().toString());assertTrue(form.isShowing)
        assertTrue(labels(dialog().window!!.decorView).any { it.contains("실제 날짜·시각") })
        assertTrue(labels(detail.window!!.decorView).contains("원본 통화 날짜: 확인 필요"))
        evidence("U05","db_unchanged" to true,"detail_unchanged" to true,"invalid_calendar_day_rejected" to true)
        c.pause().stop().destroy()
    }
    @Test fun U06ManualMemoAndDoneRefreshSameDetailAndPreserveSource() {
        val c=controller();val a=c.get();val detail=history(a);val source=row().getInt("source_version")
        click(detail.window!!.decorView,"직접 메모·완료 상태 수정")
        val form=dialog();views(form.window!!.decorView).filterIsInstance<EditText>().single().setText("합성 직접 수정 완료")
        views(form.window!!.decorView).filterIsInstance<Spinner>().single().setSelection(4)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertEquals("done",row().getString("manual_status"));assertEquals(source,row().getInt("source_version"))
        assertTrue(detail.isShowing);assertSame(detail,field(a,"fileHistoryDialog"))
        assertTrue(labels(detail.window!!.decorView).contains("합성 직접 수정 완료"))
        assertTrue(labels(detail.window!!.decorView).contains("완료"))
        dialog().dismiss();screen(detail.window!!.decorView,"manual-done-detail-host")
        evidence("U06","same_detail_instance" to true,"manual_note_visible" to true,"done_visible" to true,"audio_source_version_unchanged" to true)
        c.pause().stop().destroy()
    }
    @Test fun U07ArchiveRefreshesActionAndCanRestoreFromSameDetail() {
        val c=controller();val a=c.get();val detail=history(a);val id=row().getString("file_id")
        click(detail.window!!.decorView,"보관하기");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertTrue(row().getBoolean("archived"));assertTrue(labels(detail.window!!.decorView).contains("목록으로 되돌리기"))
        dialog().dismiss();click(detail.window!!.decorView,"목록으로 되돌리기");dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertFalse(row().getBoolean("archived"));assertEquals(id,row().getString("file_id"))
        assertTrue(labels(detail.window!!.decorView).contains("보관하기"))
        evidence("U07","same_detail_instance" to true,"archive_restore_fresh_revision" to true,"recording_id_preserved" to true,"audio_delete" to false)
        c.pause().stop().destroy()
    }
    @Test fun U08ClosedDetailIsNotReopenedByPendingSuccessfulSave() {
        val c=controller();val a=c.get();val detail=history(a);val count=ShadowDialog.getShownDialogs().count { (it as? AlertDialog)?.let { d -> labels(d.window!!.decorView).contains("이전·현재 요약 비교") } == true }
        val form=timeForm(detail,"2026-10-10 10:00");block=true
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(entered.await(5,TimeUnit.SECONDS))
        detail.dismiss();release.countDown();settle(a)
        assertEquals("2026-10-10",row().getString("original_date"));assertFalse(detail.isShowing);assertNull(field(a,"fileHistoryDialog"))
        val after=ShadowDialog.getShownDialogs().count { (it as? AlertDialog)?.let { d -> labels(d.window!!.decorView).contains("이전·현재 요약 비교") } == true }
        assertEquals(count,after)
        evidence("U08","save_committed" to true,"closed_detail_reopened" to false,"history_dialog_count_unchanged" to true)
        c.pause().stop().destroy()
    }
    @Test fun U09ConcurrentManualEditRejectsStaleTimeWithoutFalseDetailUpdate() {
        val c=controller();val a=c.get();val detail=history(a);val form=timeForm(detail,"2026-10-10 10:00")
        io { GeneralLocalStoreTest.host(context,keys).use { s -> val fresh=s.snapshot().getJSONObject(0);s.edit(fresh.getString("file_id"),fresh,"합성 동시 직접 수정","done") } }
        val before=row().toString();form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a)
        assertEquals(before,row().toString());assertTrue(row().isNull("original_date"));assertTrue(form.isShowing)
        assertTrue(labels(dialog().window!!.decorView).any { it.contains("다른 변경이 먼저 저장") })
        assertTrue(labels(detail.window!!.decorView).contains("원본 통화 날짜: 확인 필요"))
        evidence("U09","concurrent_manual_done_preserved" to true,"stale_time_rejected" to true,"false_detail_success" to false)
        c.pause().stop().destroy()
    }
    @Test fun U10CancelledDetailReadDoesNotShowLateDialogOrRewriteRoot() {
        val c=controller();val a=c.get();var shown=0
        val read:()->JSONObject={ entered.countDown();check(release.await(10,TimeUnit.SECONDS));JSONObject().put("generated",true) }
        MainActivity::class.java.declaredMethods.single { it.name=="readLocalDetail" && it.parameterCount==3 }
            .apply { isAccessible=true }.invoke(a,{ JSONObject() },read,{ _:JSONObject -> shown++ })
        shadowOf(Looper.getMainLooper()).idle();assertTrue(entered.await(5,TimeUnit.SECONDS))
        val dialogs=ShadowDialog.getShownDialogs().size;var mutations=0
        root(a).setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent:View?, child:View?) { mutations++ }
            override fun onChildViewRemoved(parent:View?, child:View?) { mutations++ }
        })
        c.pause().stop().destroy();release.countDown()
        repeat(25) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertEquals(0,shown);assertEquals(0,mutations);assertEquals(dialogs,ShadowDialog.getShownDialogs().size)
        assertEquals(false,field(a,"localDetailLoading"))
        evidence("U10","late_detail_callback" to 0,"root_mutations" to 0,"new_dialogs" to 0,"read_loading_cleared" to true)
    }
}
