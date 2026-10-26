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
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class Code26IndependentUiBoundaryTest:ProtectedTextHostTestBase() {
    companion object { lateinit var session:GoogleConnectionSession;lateinit var keys:SyntheticPilotIntegrationTest.HostKeys;lateinit var scoped:Context;var uiSettings:SecureSettings?=null;@Volatile var readGate:CountDownLatch?=null;@Volatile var readStarted:CountDownLatch?=null }
    class MainHost:MainActivity() {
        override fun createLocalStore()=GeneralLocalStoreTest.host(scoped,keys)
        override fun readLocalSetupState(context:Context):String { val gate=readGate;readStarted?.countDown();gate?.await(15,TimeUnit.SECONDS);return GeneralLocalStoreTest.host(context,keys).use { it.setupState() } }
        override fun startAutomaticDiscovery() {} // No scheduling/provider activity in this UI host probe.
    }
    class WorkflowHost:RecordingWorkflowActivity() { override fun createSession()=session;override fun createStore()=RecordingWorkflowStore(scoped) }
    private lateinit var context:Context;private lateinit var source:GeneralLocalStoreTest.Source
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(action:()->T):T=try { worker.submit<T>{action()}.get(30,TimeUnit.SECONDS) } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(GoogleConnectionRegressionTest.Memory());GoogleConnectionSession.installForHost(session)
        scoped=context;uiSettings=null;readGate=null;readStarted=null;keys=SyntheticPilotIntegrationTest.HostKeys();source=GeneralLocalStoreTest.Source().apply { put() };RuntimeEnvironment.setFontScale(1f)
    }
    @After fun cleanup() { readGate?.countDown();readGate=null;readStarted=null;GoogleConnectionSession.resetForHost();worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun mainRoot(a:MainActivity)=field(a,"root") as ViewGroup
    private fun root(a:RecordingWorkflowActivity)=RecordingWorkflowActivity::class.java.getDeclaredField("root").apply { isAccessible=true }.get(a) as ViewGroup
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun settle(a:MainActivity) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"catalogLoading")==false&&field(a,"busy")==false)return;Thread.sleep(10) };fail("MAIN_NOT_SETTLED") }
    private fun settle(a:RecordingWorkflowActivity) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(RecordingWorkflowActivity::class.java.getDeclaredField("busy").apply { isAccessible=true }.get(a)==false)return;Thread.sleep(10) };fail("WORKFLOW_NOT_SETTLED") }
    private fun main()=Robolectric.buildActivity(MainHost::class.java,Intent(context,MainHost::class.java).putExtra("report_date","2026-10-10")).create().also { c -> uiSettings?.let { MainActivity::class.java.getDeclaredField("settings").apply { isAccessible=true }.set(c.get(),it) };c.start().resume().visible();settle(c.get()) }
    private fun render(v:View,name:String) {
        val width=720;val height=1600;v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap));val dir=File("build/reports/code26-independent-ui/screens").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any>) { val result=JSONObject().put("id",id).put("pass",true).put("scope","SYNTHETIC_API35_NATIVE_HOST_NOT_AVD_OR_SERVICE")
        values.forEach { result.put(it.first,it.second) };val dir=File("build/reports/code26-independent-ui").apply { mkdirs() };File(dir,"$id.json").writeText(result.toString(2)) }
    private fun seed():String=io { GeneralLocalStoreTest.host(scoped,keys).use { s->s.initializeEmpty();s.importSelected(source);val r=s.snapshot().getJSONObject(0);s.confirmTime(r.getString("file_id"),r,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli());r.getString("file_id") } }
    private fun settings(a:MainActivity) {
        MainActivity::class.java.getDeclaredField("settingsShowing").apply { isAccessible=true }.set(a,true)
        MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible=true }.invoke(a)
        repeat(200) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"localSetupLoading")==false)return;Thread.sleep(10) };fail("SETUP_READ_NOT_SETTLED")
    }

    private fun openTodo(id:String,item:String):Pair<org.robolectric.android.controller.ActivityController<WorkflowHost>,AlertDialog> {
        val c=Robolectric.buildActivity(WorkflowHost::class.java,Intent(context,WorkflowHost::class.java).putExtra("recording_id",id).putExtra("edit_todo_id",item)).setup();settle(c.get());val dialog=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog;assertTrue(dialog.isShowing);return c to dialog
    }
    @Test fun Q01ReadyDoesNotRestoreRevokedFolderOrReadAudio() {
        seed();source.granted=false;val opens=source.opens;val provisions=keys.provisions.get();val c=main();settings(c.get())
        assertEquals("READY",field(c.get(),"localSetupState"));assertFalse(source.granted);assertEquals(opens,source.opens);assertEquals(provisions,keys.provisions.get())
        io {try{GeneralLocalStoreTest.host(scoped,keys).use{it.importSelected(source)};fail("Revoked folder read admitted")}catch(_:IllegalStateException){}}
        assertEquals(opens,source.opens);c.pause().stop().destroy();evidence("Q01","ready_does_not_grant_folder" to true)
    }
    @Test fun Q02ReadyDisplayDoesNotClearPendingRevocationOrGrantConsent() {
        seed();val fixed=javax.crypto.spec.SecretKeySpec(ByteArray(32){26},"AES");val prefs=SecureSettings(scoped,object:SecureSettings.KeyAccess { override fun existing()=fixed;override fun createForFirstEmptyStore():javax.crypto.SecretKey=error("NO_TEST_KEY_PROVISION") });prefs.update{it.put("pendingRevocation",true).put("treeUri",source.rootUri)};uiSettings=prefs;val before=prefs.snapshot().toString();val opens=source.opens;val provisions=keys.provisions.get();val c=main();settings(c.get())
        assertTrue(prefs.snapshot().getBoolean("pendingRevocation"));assertEquals(before,prefs.snapshot().toString());assertEquals(opens,source.opens);assertEquals(provisions,keys.provisions.get());assertNotNull(field(c.get(),"localSetupState"));c.pause().stop().destroy();evidence("Q02","policy_and_consent_unchanged" to true)
    }
    @Test fun Q03DestroyedSettingsReadCannotPublishLateReady() {
        seed();val c=main();val a=c.get();readGate=CountDownLatch(1);readStarted=CountDownLatch(1);MainActivity::class.java.getDeclaredField("settingsShowing").apply{isAccessible=true}.set(a,true);MainActivity::class.java.getDeclaredMethod("renderSettings").apply{isAccessible=true}.invoke(a);assertTrue(readStarted!!.await(3,TimeUnit.SECONDS));val gate=readGate!!;c.pause().stop().destroy();gate.countDown();readGate=null;readStarted=null
        repeat(80){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};assertNull(field(a,"localSetupState"));evidence("Q03","late_ready_after_destroy" to false)
    }
    @Test fun Q04SameTitleSelectionEditsOnlyExactTodoId() {
        val id=seed();val pair=io{RecordingWorkflowStore(scoped).use{s->s.createItem(id,s.snapshot(id),"TODO","동일 합성 제목","","") to s.createItem(id,s.snapshot(id),"TODO","동일 합성 제목","","")}};val(c,d)=openTodo(id,pair.second);views(d.window!!.decorView).filterIsInstance<EditText>().first().setText("SELECTED_SECOND_ONLY");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(c.get())
        val rows=io{RecordingWorkflowStore(scoped).use{it.snapshot(id).getJSONArray("items")}};val map=(0 until rows.length()).associate{rows.getJSONObject(it).getString("id") to rows.getJSONObject(it)};assertEquals("동일 합성 제목",map.getValue(pair.first).getString("title"));assertEquals(1L,map.getValue(pair.first).getLong("revision"));assertEquals("SELECTED_SECOND_ONLY",map.getValue(pair.second).getString("title"));assertEquals(2L,map.getValue(pair.second).getLong("revision"));c.pause().stop().destroy();evidence("Q04","exact_id_edit_only" to true)
    }
    @Test fun Q05StaleOpenEditorCannotOverwriteLaterManualDone() {
        val id=seed();val item=io{RecordingWorkflowStore(scoped).use{it.createItem(id,it.snapshot(id),"TODO","원래 합성 제목","","")}};val(c,d)=openTodo(id,item);io{RecordingWorkflowStore(scoped).use{it.editItem(item,1,"SURVIVING_MANUAL_DONE","done","수동 기한","")}};views(d.window!!.decorView).filterIsInstance<EditText>().first().setText("STALE_EDITOR_OVERWRITE");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(c.get())
        val row=io{RecordingWorkflowStore(scoped).use{it.snapshot(id).getJSONArray("items").getJSONObject(0)}};assertEquals("SURVIVING_MANUAL_DONE",row.getString("title"));assertEquals("done",row.getString("status"));assertEquals(2L,row.getLong("revision"));c.pause().stop().destroy();evidence("Q05","manual_done_after_open_preserved" to true)
    }
    @Test fun Q06AccountEpochChangeRejectsOldDialogSave() {
        val id=seed();val item=io{RecordingWorkflowStore(scoped).use{it.createItem(id,it.snapshot(id),"TODO","OWNER_A_ONLY","","")}};val(c,d)=openTodo(id,item);val before=io{RecordingWorkflowStore(scoped).use{it.snapshot(id).toString()}};val e=session.begin();session.activate(e,"other@example.invalid","other@example.invalid","OTHER_SYN_OWNER","RAM_ONLY_FAKE_TOKEN");views(d.window!!.decorView).filterIsInstance<EditText>().first().setText("WRONG_OWNER_SAVE");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();repeat(30){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)};assertEquals(before,io{RecordingWorkflowStore(scoped).use{it.snapshot(id).toString()}});c.pause().stop().destroy();evidence("Q06","old_owner_dialog_save" to false)
    }
}
