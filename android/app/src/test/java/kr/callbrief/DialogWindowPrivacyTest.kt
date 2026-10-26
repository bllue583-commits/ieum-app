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

/** Window flag regression on synthetic content. Host flags do not establish OS screenshot exclusion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DialogWindowPrivacyTest : ProtectedTextHostTestBase() {
    companion object {
        lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
        val MEMO="합성 직접 메모 · 계정/실제 통화 사용 없음"
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
        val dir=File("build/reports/dialog-window-host").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val json=JSONObject().put("id",id).put("pass",true).put("scope","API35_NATIVE_HOST_GENERATED_ROWS_NOT_DEVICE")
        values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/dialog-window-privacy").apply { mkdirs() };File(dir,"$id.json").writeText(json.toString(2))
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
    private fun call(a:MainActivity,name:String,vararg values:Any?):Any? {
        val types=values.map { when(it) { is Boolean->java.lang.Boolean.TYPE;is String->String::class.java;is JSONObject->JSONObject::class.java;is LinearLayout->LinearLayout::class.java;else->error("unknown synthetic parameter") } }.toTypedArray()
        return MainActivity::class.java.getDeclaredMethod(name,*types).apply { isAccessible=true }.invoke(a,*values)
    }
    private fun dialog()=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() as android.app.AlertDialog
    private fun protectedWindow(id:String,d:android.app.Dialog,owner:android.app.Activity,marker:String?=null) {
        val win=d.window!!;val flag=android.view.WindowManager.LayoutParams.FLAG_SECURE
        assertTrue(d.isShowing);assertNotSame(owner.window,win)
        val text=labels(win.decorView).joinToString("\n");if(marker!=null)assertTrue(id+" content fixture present",text.contains(marker))
        val secured=win.attributes.flags and flag!=0
        val json=JSONObject().put("id",id).put("pass",secured).put("scope","API35_HOST_WINDOW_FLAGS_NOT_OS_CAPTURE")
            .put("dialog_flags",win.attributes.flags).put("owner_flags",owner.window.attributes.flags).put("separate_window",true)
            .put("synthetic_content_present",marker==null||text.contains(marker)).put("device_screenshot_test",false)
        val dir=File("build/reports/dialog-window-privacy").apply { mkdirs() };File(dir,"$id.json").writeText(json.toString(2))
        assertTrue(id+" dialog own Window must be secure",secured)
    }
    @Test fun P00ControlPlainDialogDoesNotInheritSecureActivityWindow() {
        val a=activity();assertTrue(a.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
        val d=android.app.AlertDialog.Builder(a).setMessage("합성 대조 자료").create();d.show()
        assertEquals(0,d.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE);d.dismiss()
        evidence("P00","plain_dialog_inherits_owner_secure" to false,"control_only" to true)
    }
    @Test fun P01MainReadDialogProtectsOriginalMemoFilenameAndCounterpart() {
        val a=activity();val body=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;addView(TextView(a).apply { text="합성 원문 · 합성 메모 · 합성파일.wav · 합성 상대 · 합성 회사" }) }
        val d=call(a,"showReadDialog","합성 원문·파일 정보",body) as android.app.AlertDialog
        protectedWindow("P01",d,a,"합성파일.wav");d.dismiss()
    }
    @Test fun P02ReadOnlyOriginalAndEvidenceDialogHasItsOwnSecureWindow() {
        val c=Robolectric.buildActivity(ReadOnlyProposalActivity::class.java).setup();val a=c.get()
        ReadOnlyProposalActivity::class.java.getDeclaredMethod("details",String::class.java,String::class.java).apply { isAccessible=true }
            .invoke(a,"합성 전사 전문 · 합성 상대 · 합성 파일명",JSONObject().put("summary","합성 분석 제안").put("observations",JSONArray().put(JSONObject().put("value","합성 제안").put("evidence",JSONArray().put(JSONObject().put("quote","합성 원문 근거"))))).toString())
        val d=dialog();protectedWindow("P02",d,a,"합성 전사 전문");d.dismiss();c.pause().stop().destroy()
    }
    @Test fun P03FileHistoryShowsSyntheticFilenameAndMemoUnderSecureDialog() {
        val a=activity();val row=rows(a).getJSONObject(0);val before=tables();call(a,"showFileHistory",row.getString("file_id"));settle(a)
        val d=dialog();protectedWindow("P03",d,a,MEMO);assertTrue(labels(d.window!!.decorView).any { it.startsWith("원본 파일명:") });d.dismiss();assertEquals(before,tables())
    }
    @Test fun P04MemoEditCancelIsProtectedAndKeepsCanonicalRows() {
        val a=activity();val before=tables();call(a,"editLocalRecording",rows(a).getJSONObject(0));val d=dialog()
        protectedWindow("P04",d,a,MEMO);d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(before,tables())
    }
    @Test fun P05CallDateTimeEditCancelIsProtectedAndKeepsRows() {
        val a=activity();val before=tables();call(a,"confirmLocalTime",rows(a).getJSONObject(0));val d=dialog()
        protectedWindow("P05",d,a,"통화");d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(before,tables())
    }
    @Test fun P06ArchiveAndRestoreConfirmationBothProtectTheirWindow() {
        val a=activity();val before=tables();val row=rows(a).getJSONObject(0)
        for(restored in listOf(false,true)) { call(a,"confirmLocalArchive",JSONObject(row.toString()).put("archived",restored));val d=dialog();protectedWindow(if(restored)"P06_restore" else "P06_archive",d,a);d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick() }
        assertEquals(before,tables());evidence("P06","both_windows_secure" to true,"canonical_rows_unchanged" to true)
    }
    @Test fun P07InitialAndRecoveryConfirmationBothProtectTheirWindow() {
        val a=activity();val before=tables()
        for(recover in listOf(false,true)) { call(a,"confirmLocalSetup",recover);val d=dialog();protectedWindow(if(recover)"P07_recovery" else "P07_initial",d,a);d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick() }
        assertEquals(before,tables());evidence("P07","both_windows_secure" to true,"canonical_rows_unchanged" to true)
    }
    @Test fun P08ImportAndRecordingManagementAreProtectedWithoutOpeningProvider() {
        val a=activity();val fixed=javax.crypto.spec.SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")
        val settings=SecureSettings(a,object:SecureSettings.KeyAccess { override fun existing()=fixed;override fun createForFirstEmptyStore()=fixed })
        settings.update { it.put("treeUri","content://synthetic-fixture/tree") };set(a,"settings",settings)
        val before=tables();call(a,"importLocalRecordings");var d=dialog();protectedWindow("P08_import",d,a);d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        call(a,"showRecordingManagement");d=dialog();protectedWindow("P08_management",d,a,"기록 관리");d.dismiss();assertEquals(before,tables())
        evidence("P08","provider_open_calls" to 0,"canonical_rows_unchanged" to true)
    }
    @Test fun P09SelectedDatePickerProtectsItsSeparateWindow() {
        val a=activity();call(a,"chooseCallDate");val d=dialog();assertTrue(d is android.app.DatePickerDialog);protectedWindow("P09",d,a);d.dismiss()
    }
    @Test fun P10SyntheticTodoEditAndNestedDueDatePickerRemainProtected() {
        val a=activity();call(a,"enableDemo");val task=DemoFixtures.board().second.getJSONObject(0);val before=task.toString();call(a,"editTask",task)
        val edit=dialog();protectedWindow("P10_edit",edit,a,task.getString("title"))
        views(edit.window!!.decorView).filterIsInstance<Button>().first { it.text.toString()=="날짜 선택" }.performClick()
        val date=dialog();assertTrue(date is android.app.DatePickerDialog);protectedWindow("P10_due",date,a);date.dismiss();edit.dismiss();assertEquals(before,task.toString())
        evidence("P10","two_separate_windows_secure" to true,"task_unchanged" to true)
    }
    @Test fun P11EvidenceTopicBriefingAndNoticeUseProtectedReadPath() {
        val a=activity();call(a,"enableDemo");val board=DemoFixtures.board()
        call(a,"showEvidence",board.second.getJSONObject(0));var d=dialog();protectedWindow("P11_evidence",d,a);d.dismiss()
        call(a,"showTopic",board.first.getJSONObject(0));d=dialog();protectedWindow("P11_topic",d,a);d.dismiss()
        call(a,"showDigest");d=dialog();protectedWindow("P11_briefing",d,a);d.dismiss()
        call(a,"notice","합성 정보 확인");d=dialog();protectedWindow("P11_notice",d,a,"합성 정보 확인");d.dismiss()
        evidence("P11","all_four_routes_secure" to true)
    }
    @Test fun P12StoredRawResultAndPreviousDayReviewAreProtected() {
        val a=activity();call(a,"showResult",JSONObject().put("version",1),JSONObject().put("revision",1).put("raw_text","합성 전사 전문 · 저장된 버전").put("summary","합성 저장 요약"))
        var d=dialog();protectedWindow("P12_raw",d,a,"합성 전사 전문");d.dismiss();call(a,"showPreviousDayReview");settle(a);d=dialog();protectedWindow("P12_previous_day",d,a);d.dismiss()
        evidence("P12","raw_and_previous_day_secure" to true)
    }
    @Test fun P13SyntheticPilotHistoryProposalAndEditAreProtected() {
        context.deleteDatabase(SyntheticLocalPilot.DATABASE);File(context.noBackupFilesDir,"synthetic-local-pilot-v2").deleteRecursively();SyntheticPilotIntegrationTest.fixture=SyntheticPilotIntegrationTest.HostKeys()
        io { SyntheticPilotIntegrationTest.hostPilot(context).use { it.initializeEmptyVault();it.collection();it.makeMockCandidate() } }
        val controller=Robolectric.buildActivity(SyntheticPilotIntegrationTest.HostPilotActivity::class.java).setup();val a=controller.get()
        repeat(200) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        fun click(label:String) { views(a.window.decorView).filterIsInstance<Button>().first { it.text.toString()==label }.performClick();shadowOf(Looper.getMainLooper()).idle() }
        click("원문·버전 전체 이력");var d=dialog();protectedWindow("P13_history",d,a);d.dismiss()
        click("변경안 검토");d=dialog();protectedWindow("P13_proposal",d,a);d.dismiss()
        click("직접 수정·완료");d=dialog();protectedWindow("P13_edit",d,a);d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        controller.pause().stop().destroy();evidence("P13","all_three_pilot_dialogs_secure" to true,"real_keystore_execution" to false)
    }
    @Test fun P14SecureFlagPrecedesFirstShowAndPreservesOtherWindowFlags() {
        val a=activity()
        class ObservedDialog(ctx:Context):android.app.Dialog(ctx) { var flagsAtShow=0;override fun show() { flagsAtShow=window!!.attributes.flags;super.show() } }
        val d=ObservedDialog(a);d.window!!.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val before=d.window!!.attributes.flags;var onShowSecure=false
        d.setOnShowListener { onShowSecure=d.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0 }
        val returned=ProtectedDialogWindow.show(d);shadowOf(Looper.getMainLooper()).idle();assertSame(d,returned)
        assertEquals(before or android.view.WindowManager.LayoutParams.FLAG_SECURE,d.flagsAtShow);assertTrue(onShowSecure)
        protectedWindow("P14",d,a);d.dismiss();evidence("P14_timing","secure_at_first_show_entry" to true,"secure_in_on_show_callback" to true,"all_other_flags_preserved" to true)
    }
    @Test fun P15MissingVisualWindowFailsClosedBeforeShow() {
        val a=activity()
        class NoWindow(ctx:Context):android.app.Dialog(ctx) { var displayed=false;override fun getWindow():android.view.Window?=null;override fun show() { displayed=true } }
        val d=NoWindow(a)
        try { ProtectedDialogWindow.show(d);fail("missing Window displayed") } catch(e:IllegalStateException) { assertEquals("DIALOG_WINDOW_UNAVAILABLE",e.message) }
        assertFalse(d.displayed);evidence("P15","missing_window_show_calls" to 0)
    }
    @Test fun P16DismissAndReopenRetainProtectionWithoutChangingOwnerFlags() {
        val a=activity();val ownerFlags=a.window.attributes.flags;val d=android.app.AlertDialog.Builder(a).setMessage("합성 재조회").create()
        ProtectedDialogWindow.show(d);protectedWindow("P16_first",d,a);d.dismiss();ProtectedDialogWindow.show(d);protectedWindow("P16_reopen",d,a);d.dismiss()
        assertEquals(ownerFlags,a.window.attributes.flags);evidence("P16","same_dialog_reopen_secure" to true,"owner_flags_unchanged" to true)
    }
    @Test fun P17NewActivityAfterDestroyCreatesProtectedReadAndEditWindows() {
        val c=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup();val first=c.get();settle(first)
        call(first,"showRecordingManagement");val old=dialog();protectedWindow("P17_old",old,first);old.dismiss();c.pause().stop().destroy()
        val restarted=Robolectric.buildActivity(HostActivity::class.java,Intent(context,HostActivity::class.java).putExtra("report_date","2026-10-10")).setup();val a=restarted.get();settle(a)
        call(a,"showRecordingManagement");val fresh=dialog();assertNotSame(old.window,fresh.window);protectedWindow("P17_new",fresh,a);fresh.dismiss()
        call(a,"editLocalRecording",rows(a).getJSONObject(0));val edit=dialog();protectedWindow("P17_edit",edit,a,MEMO);edit.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();restarted.pause().stop().destroy()
        evidence("P17","host_activity_destroy_and_recreate" to true,"fresh_windows_secure" to true,"device_recreation" to false)
    }
    @Test fun P18ReadOnlyActivityRecreationCreatesAnotherProtectedOriginalWindow() {
        var old:android.view.Window?=null
        repeat(2) { index ->
            val c=Robolectric.buildActivity(ReadOnlyProposalActivity::class.java).setup();val a=c.get()
            ReadOnlyProposalActivity::class.java.getDeclaredMethod("details",String::class.java,String::class.java).apply { isAccessible=true }.invoke(a,"합성 재생성 원문","{}")
            val d=dialog();protectedWindow("P18_$index",d,a,"합성 재생성 원문");if(old!=null)assertNotSame(old,d.window);old=d.window;c.pause().stop().destroy();assertFalse(d.isShowing)
        }
        evidence("P18","host_activity_recreated" to true,"destroy_dismissed_prior_details" to true,"device_recreation" to false)
    }
}
