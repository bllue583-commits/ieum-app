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
internal class Code26UsabilityRegressionTest:ProtectedTextHostTestBase() {
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
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap));val dir=File("build/reports/code26-usability/screens").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any>) { val result=JSONObject().put("id",id).put("pass",true).put("scope","SYNTHETIC_API35_NATIVE_HOST_NOT_AVD_OR_SERVICE")
        values.forEach { result.put(it.first,it.second) };val dir=File("build/reports/code26-usability").apply { mkdirs() };File(dir,"$id.json").writeText(result.toString(2)) }
    private fun seed():String=io { GeneralLocalStoreTest.host(scoped,keys).use { s->s.initializeEmpty();s.importSelected(source);val r=s.snapshot().getJSONObject(0);s.confirmTime(r.getString("file_id"),r,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli());r.getString("file_id") } }
    @Test fun U01ConfirmedEmptyWithoutSelectedFolderOffersDirectNextStep() {
        val a=main().get();assertTrue(field(a,"catalogLoaded")==true)
        assertTrue(texts(mainRoot(a)).contains("녹음 폴더 선택"));assertTrue(views(mainRoot(a)).filterIsInstance<Button>().any { it.text.toString()=="녹음 폴더 선택" })
        render(mainRoot(a),"U01-empty-folder-cta");evidence("U01","automatic_grant" to false)
    }
    @Test fun U02ColdReadyStorageIsReadWithoutManualReconfirmationOrKeyProvision() {
        seed();val before=keys.provisions.get();val beforeReads=source.opens;val c=main();val a=c.get()
        MainActivity::class.java.getDeclaredField("settingsShowing").apply { isAccessible=true }.set(a,true)
        MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible=true }.invoke(a)
        repeat(120) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"localSetupState")!="READY")Thread.sleep(10) }
        assertEquals("READY",field(a,"localSetupState"));assertEquals(before,keys.provisions.get())
        c.pause().stop().destroy();val next=main().get();MainActivity::class.java.getDeclaredField("settingsShowing").apply { isAccessible=true }.set(next,true)
        MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible=true }.invoke(next)
        repeat(120) { shadowOf(Looper.getMainLooper()).idle();if(field(next,"localSetupState")!="READY")Thread.sleep(10) }
        assertEquals("READY",field(next,"localSetupState"));assertEquals(before,keys.provisions.get());assertEquals(beforeReads,source.opens)
        render(mainRoot(next),"U02-ready-after-cold-open");evidence("U02","new_key_provisions" to 0,"folder_grant_or_raw_read_proved" to false)
    }
    @Test fun U03SelectedTodoOpensItsCurrentEditorOnlyOnce() {
        val id=seed();val item=io { RecordingWorkflowStore(scoped).use { s->s.createItem(id,s.snapshot(id),"TODO","선택한 합성 회신","기존 보존값","") } }
        val before=io { RecordingWorkflowStore(scoped).use { it.snapshot(id).toString() } }
        val c=Robolectric.buildActivity(WorkflowHost::class.java,Intent(context,WorkflowHost::class.java).putExtra("recording_id",id).putExtra("edit_todo_id",item)).setup();settle(c.get())
        val dialog=ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog;assertTrue(dialog?.isShowing==true)
        assertTrue(views(dialog!!.window!!.decorView).filterIsInstance<EditText>().any { it.text.toString()=="선택한 합성 회신" })
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();c.pause().stop().restart().start().resume();settle(c.get())
        assertFalse((ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).isShowing);assertEquals(before,io { RecordingWorkflowStore(scoped).use { it.snapshot(id).toString() } })
        evidence("U03","cancel_writes" to 0,"auto_reopened_on_return" to false)
    }
    private fun currentError():String {
        val e=session.begin();session.activate(e,"ui@example.invalid","ui@example.invalid","PUBLIC_ERROR_UI_OWNER","PUBLIC_MEMORY_ONLY_TOKEN");scoped=AccountStorageContext(context,session.ownerBinding())
        val k=javax.crypto.spec.SecretKeySpec(ByteArray(32){26},"AES");val settings=SecureSettings(scoped,object:SecureSettings.KeyAccess { override fun existing()=k;override fun createForFirstEmptyStore()=k });settings.update { it.put("treeUri",source.rootUri) };uiSettings=settings;val drive=GoogleConnectionRegressionTest.FakeDrive()
        val id=seed();io {
            SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)
            GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}) { GeneralLocalStoreTest.host(it,keys) }.use { p->
                val sent=p.submitExisting(id);val result=p.registration(sent.jobId).getJSONObject("files").getJSONObject("result").getString("fileId")
                RecordingWorkflowStore(scoped,policySnapshot={settings.snapshot()},analysisActor={session.nativeSessionBinding()}).use { s->val request=s.prepareAnalysis(id,s.snapshot(id));s.transitionRequest(request,"REVIEW",sent.jobId) }
                drive.failure(result);assertEquals("ERROR",p.poll(sent.jobId))
            }
        }
        return id
    }
    @Test fun U04ValidatedCurrentErrorAppearsInDateListDespiteLaggingObserver() {
        val id=currentError()
        val a=main().get();val card=mainRoot(a).findViewWithTag<ViewGroup>("recording_$id");assertNotNull(card)
        assertTrue(texts(card).any { it.contains("분석 실패") });assertFalse(texts(card).any { it.startsWith("분석 대기") })
        val state=io { RecordingWorkflowStore(scoped).use { it.snapshot(id).getJSONArray("requests").getJSONObject(0).getString("state") } };assertEquals("REVIEW",state)
        render(mainRoot(a),"U04-current-error-date-list");evidence("U04","observer_state_preserved" to "REVIEW","canonical_accept" to 0,"actual_network" to 0)
    }
    private fun settings(a:MainActivity) {
        MainActivity::class.java.getDeclaredField("settingsShowing").apply { isAccessible=true }.set(a,true)
        MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible=true }.invoke(a)
        repeat(200) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"localSetupLoading")==false)return;Thread.sleep(10) };fail("SETUP_READ_NOT_SETTLED")
    }
    @Test fun U05EmptyStorageReadDoesNotInitialize() {
        val a=main().get();settings(a);assertEquals("EMPTY",field(a,"localSetupState"));assertEquals(0,keys.provisions.get());assertEquals(0,source.opens)
        evidence("U05","state" to "EMPTY","key_provisions" to 0,"raw_reads" to 0)
    }
    @Test fun U06InterruptedPreparationStaysPendingWithoutRecovery() {
        io { try { GeneralLocalStoreTest.host(scoped,keys) { if(it=="AFTER_KEY_SETUP_BEFORE_DB_READY")error("SYNTHETIC_HALT") }.use { it.initializeEmpty() };fail("fault not reached") } catch(e:IllegalStateException) { assertEquals("SYNTHETIC_HALT",e.message) } }
        val before=keys.provisions.get();val a=main().get();settings(a);assertEquals("PENDING",field(a,"localSetupState"));assertEquals(before,keys.provisions.get());assertEquals(0,source.opens)
        evidence("U06","state" to "PENDING","automatic_recovery" to false)
    }
    @Test fun U07MissingAudioKeyStaysReviewWithoutReprovision() {
        seed();val before=keys.provisions.get();val reads=source.opens;keys.state=kr.co.callsummary.vault.VaultKeys.State.MISSING
        val a=main().get();settings(a);assertEquals("REVIEW",field(a,"localSetupState"));assertEquals(before,keys.provisions.get());assertEquals(reads,source.opens)
        assertFalse(views(mainRoot(a)).filterIsInstance<Button>().single { it.text.toString()=="선택 폴더에서 가져오기" }.isEnabled)
        evidence("U07","state" to "REVIEW","new_key_provisions" to 0,"new_raw_reads" to 0)
    }
    private fun rejectRoute(id:String,item:String) {
        val before=io { RecordingWorkflowStore(scoped).use { it.snapshot(id).toString() } }
        val c=Robolectric.buildActivity(WorkflowHost::class.java,Intent(context,WorkflowHost::class.java).putExtra("recording_id",id).putExtra("edit_todo_id",item)).setup();settle(c.get())
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing ?: false)
        assertTrue(texts(root(c.get())).any { it.contains("선택한 할일을 현재 통화에서 확인하지 못했어요.") })
        assertEquals(before,io { RecordingWorkflowStore(scoped).use { it.snapshot(id).toString() } });c.pause().stop().destroy()
    }
    @Test fun U08DocumentOrMissingIdCannotBecomeTodoEditor() {
        val id=seed();val doc=io { RecordingWorkflowStore(scoped).use { it.createItem(id,it.snapshot(id),"DOCUMENT","합성 문서","보존판","") } }
        rejectRoute(id,doc);rejectRoute(id,"missing-id");evidence("U08","writes" to 0)
    }
    @Test fun U09OtherRecordingsTodoIsNotEditableFromThisRoute() {
        val id=seed();source.put(id="two",alternate=true);val other=io { GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source);(0 until it.snapshot().length()).map { n->it.snapshot().getJSONObject(n) }.single { r->r.getString("file_id")!=id }.getString("file_id") } }
        val todo=io { RecordingWorkflowStore(scoped).use { it.createItem(other,it.snapshot(other),"TODO","다른 통화 할일","","") } };rejectRoute(id,todo);evidence("U09","cross_record_edit" to false)
    }
    @Test fun U10PreviousSourceErrorDoesNotLabelCurrentVersionFailed() {
        val id=currentError();source.put(alternate=true);io { GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source) } }
        val row=io { GeneralLocalStore.catalog(scoped).listFiles().getJSONObject(0) };assertEquals(2L,row.getLong("source_version"));assertNotEquals("ERROR",row.optString("analysis_result_state"))
        assertEquals("REVIEW",io { RecordingWorkflowStore(scoped).use { it.snapshot(id).getJSONArray("requests").getJSONObject(0).getString("state") } })
        evidence("U10","old_error_current" to false,"observer_state_preserved" to true)
    }
    @Test fun U11DifferentOwnerCannotRevalidateCurrentError() {
        val id=currentError();io {
            RecordingWorkflowStore(scoped).use { w ->val r=w.snapshot(id);val q=r.getJSONArray("requests").getJSONObject(0)
                val p=RecordingWorkflowStore::class.java.getDeclaredField("port").apply { isAccessible=true }.get(w)
                val db=AndroidSqlPort::class.java.getDeclaredField("db").apply { isAccessible=true }.get(p) as android.database.sqlite.SQLiteDatabase
                val request=JSONObject().put("id",q.getString("id")).put("state",q.getString("state")).put("job",q.getString("job_id"))
                val result=CurrentAnalysisResult.read(AccountStorageContext(context,"OTHER_PUBLIC_OWNER"),db,id,r.getLong("source_version"),r.getString("audio_sha256"),request)
                assertEquals("BINDING_REVIEW",result!!.getString("state"));assertEquals("",result.getString("digest"));assertEquals("REVIEW",w.snapshot(id).getJSONArray("requests").getJSONObject(0).getString("state"))
            }
        };evidence("U11","foreign_error_confirmed" to false)
    }
    @Test fun U12OldOwnerSetupCompletionCannotPublishReadyAfterSwitch() {
        seed();val c=main();val a=c.get();readGate=CountDownLatch(1);readStarted=CountDownLatch(1)
        MainActivity::class.java.getDeclaredField("settingsShowing").apply { isAccessible=true }.set(a,true)
        MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible=true }.invoke(a)
        assertTrue(readStarted!!.await(3,TimeUnit.SECONDS));val gate=readGate!!
        val e=session.begin();session.activate(e,"new@example.invalid","new@example.invalid","NEW_PUBLIC_OWNER","PUBLIC_MEMORY_ONLY_TOKEN")
        readGate=null;readStarted=null;c.pause().stop().restart().start().resume();gate.countDown();settle(a);settings(a)
        assertEquals("EMPTY",field(a,"localSetupState"));assertEquals(1,keys.provisions.get());assertEquals("NEW_PUBLIC_OWNER",field(a,"storageScope"));c.pause().stop().destroy()
        evidence("U12","old_owner_ready_published" to false,"new_owner_initialized" to false)
    }

}
