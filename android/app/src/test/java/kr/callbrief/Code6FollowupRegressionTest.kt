package kr.callbrief

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.app.AlertDialog
import kr.co.callsummary.core.android.AndroidSqlPort
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Host regression acceptance on a separate code6 source.
 * Original code5 diagnostic assertions and outputs are preserved separately.
 * Generated provider/grant shadow, public JCE key, host SQLite and controller only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code6FollowupRegressionTest : ProtectedTextHostTestBase() {
    companion object {
        lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
        var blockCreation=false
        lateinit var creationEntered:CountDownLatch
        lateinit var creationRelease:CountDownLatch
    }
    class HostActivity:MainActivity() {
        override fun createLocalStore():GeneralLocalStore {
            if(blockCreation) {
                creationEntered.countDown()
                check(creationRelease.await(10,TimeUnit.SECONDS)) { "TEST_CREATION_BARRIER_TIMEOUT" }
            }
            return GeneralLocalStoreTest.host(this,keys)
        }
    }
    class Provider:ContentProvider() {
        val documents=linkedMapOf<String,SourceDocument>()
        var listed:Set<String>?=null
        var loading=false;var error:String?=null;var opens=0
        val children=mutableMapOf<String,Set<String>>()
        val loadingParents=mutableSetOf<String>();val errorParents=mutableSetOf<String>()
        override fun onCreate()=true
        override fun call(method:String,arg:String?,extras:android.os.Bundle?):android.os.Bundle? =
            SyntheticTreeMembershipSupport.response(method,extras,documents.keys)

        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
            val columns=projection!!.map { it }.toTypedArray()
            val c=MatrixCursor(columns)
            val child=uri.pathSegments.contains("children")
            val parent=if(child)DocumentsContract.getDocumentId(uri) else null
            val selectionIds=children[parent] ?: listed
            val rows=if(child)documents.values.filter { selectionIds==null || it.documentId in selectionIds }
                else listOfNotNull(documents[DocumentsContract.getDocumentId(uri)])
            rows.forEach { d -> c.addRow(columns.map { name -> when(name) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID->d.documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME->d.name
                DocumentsContract.Document.COLUMN_MIME_TYPE->d.mime
                DocumentsContract.Document.COLUMN_SIZE->d.size
                DocumentsContract.Document.COLUMN_LAST_MODIFIED->d.modified
                else->null
            } }.toTypedArray()) }
            if(child)c.setExtras(Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING,loading || parent in loadingParents)
                (error ?: if(parent in errorParents)"GENERATED_CHILD_ERROR" else null)?.let { putString(DocumentsContract.EXTRA_ERROR,it) }
            })
            return c
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            check(mode=="r");check(documents.containsKey(DocumentsContract.getDocumentId(uri)));opens++
            val f=File(context!!.cacheDir,"review-generated.wav");f.writeBytes(GeneralLocalStoreTest.wav())
            return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri:Uri)="audio/wav"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("NO_TEST_WRITES")
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=error("NO_TEST_WRITES")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=error("NO_TEST_WRITES")
    }
    private lateinit var context:Context
    private val ioExecutor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=ioExecutor.submit<T> { f() }.get(15,TimeUnit.SECONDS)
    @Before fun generatedSandboxOnly() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys()
        context.deleteDatabase(GeneralLocalStore.DATABASE)
        File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
        blockCreation=false;creationEntered=CountDownLatch(1);creationRelease=CountDownLatch(1)
        io { GeneralLocalStoreTest.host(context,keys).use { it.initializeEmpty() } }
    }
    @After fun end() {
        creationRelease.countDown();blockCreation=false
        ioExecutor.shutdown();assertTrue(ioExecutor.awaitTermination(5,TimeUnit.SECONDS))
    }
    private fun evidence(id:String,vararg fields:Pair<String,Any?>) {
        val v=JSONObject().put("id",id).put("scope","HOST_REGRESSION_NOT_DEVICE_ACCEPTANCE")
            .put("retest_of",if(id.substring(1).toInt()<=10)"code5-$id" else JSONObject.NULL)
        fields.forEach { v.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/code6-followup").apply { mkdirs() }
        File(dir,"$id.json").writeText(v.toString(2))
    }
    private fun provider():Pair<Provider,SafSelectedRecordingSource> {
        val tree=DocumentsContract.buildTreeDocumentUri("review.synthetic.callbrief","root")
        val key=SecretKeySpec(ByteArray(32) { 28 },"AES")
        val settings=SecureSettings(context,object:SecureSettings.KeyAccess {
            override fun existing():SecretKey=key
            override fun createForFirstEmptyStore():SecretKey=error("NO_TEST_KEY_GENERATION")
        })
        settings.update { it.put("treeUri",tree.toString()) }
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val p=Provider();p.attachInfo(context,ProviderInfo().apply { authority="review.synthetic.callbrief";grantUriPermissions=true })
        ShadowContentResolver.registerProviderInternal("review.synthetic.callbrief",p)
        return p to SafSelectedRecordingSource(context,settings,tree.toString())
    }
    private fun put(p:Provider,id:String) {
        val tree=DocumentsContract.buildTreeDocumentUri("review.synthetic.callbrief","root")
        // Supported contact field keeps provider/cancellation checks independent of the new privacy hold gate.
        p.documents[id]=SourceDocument(id,DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),"통화 녹음_합성${id} 01000002222_20261010_100000.wav",52,"audio/wav",GeneralLocalStoreTest.NOW-600000)
    }
    @Test fun R01LoadingPartialInventoryHoldsChangedIdRename()=io {
        val (p,s)=provider();put(p,"old")
        GeneralLocalStoreTest.host(context,keys).use { store ->
            assertEquals(1,store.importSelected(TextStoreTestSupport.generatedSource(s)).newVersions)
            val old=store.snapshot().getJSONObject(0).getString("file_id")
            put(p,"new");p.listed=setOf("new");p.loading=true
            val inv=s.inventory();assertFalse(inv.complete)
            val report=store.importSelected(TextStoreTestSupport.generatedSource(s));val rows=store.snapshot()
            assertEquals(2,p.documents.size);assertEquals(1,rows.length())
            assertEquals(old,rows.getJSONObject(0).getString("file_id"))
            assertTrue(report.incomplete);assertEquals(1,report.held)
            AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use {
                assertEquals("old",it.query("SELECT actual_document_id FROM local_files").single()["actual_document_id"])
            }
            evidence("R01","acceptance_pass" to true,"provider_documents" to 2,"listed_documents" to 1,
                "loading" to true,"inventory_complete" to inv.complete,"saved_recordings" to rows.length(),
                "incomplete_report" to report.incomplete,"expected_safe" to "incomplete=true; changed-ID rename held")
        }
    }
    private fun rejectListing(f:()->Unit) {
        try { f();fail("Expected PROVIDER_LIST_FAILED") }
        catch(e:IllegalStateException) { assertEquals("PROVIDER_LIST_FAILED",e.message) }
    }
    @Test fun R02ErrorWithZeroRowsFailsExplicitlyBeforeAudioRead()=io {
        val (p,s)=provider();p.error="GENERATED_NETWORK_ERROR"
        rejectListing { s.inventory() }
        GeneralLocalStoreTest.host(context,keys).use { store ->
            rejectListing { store.importSelected(TextStoreTestSupport.generatedSource(s)) };assertEquals(0,p.opens);assertEquals(0,store.snapshot().length())
            evidence("R02","acceptance_pass" to true,"typed_error" to "PROVIDER_LIST_FAILED",
                "normal_empty_report" to false,"opens" to p.opens,"expected_safe" to "explicit error; no confirmed-empty success")
        }
    }
    @Test fun R03CompleteCoexistingSameContentControlKeepsTwoRecords()=io {
        val (p,s)=provider();put(p,"old")
        GeneralLocalStoreTest.host(context,keys).use { store ->
            store.importSelected(TextStoreTestSupport.generatedSource(s));put(p,"new")
            val r=store.importSelected(TextStoreTestSupport.generatedSource(s));assertEquals(2,store.snapshot().length());assertFalse(r.incomplete)
            evidence("R03","control_pass" to true,"saved_recordings" to 2,"loading" to false)
        }
    }
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun set(a:MainActivity,name:String,value:Any?)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value)
    private fun method(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(a)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun root(a:MainActivity)=field(a,"root") as View
    private fun labels(a:MainActivity)=views(root(a)).filterIsInstance<TextView>().map { it.text.toString() }
    private fun settle(a:MainActivity,includeBusy:Boolean=true) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if(field(a,"catalogLoading")==false && (!includeBusy || field(a,"busy")==false))return
            Thread.sleep(10)
        };fail("HOST_ACTIVITY_NOT_SETTLED")
    }
    private fun controller()=Robolectric.buildActivity(HostActivity::class.java).setup().also { settle(it.get()) }
    private fun startBlocked(a:MainActivity,operation:((GeneralLocalStore)->String)?=null):CountDownLatch {
        val done=CountDownLatch(1);blockCreation=true
        val action:(GeneralLocalStore)->String={ store ->
            try { operation?.invoke(store) ?: run { store.importSelected(GeneralLocalStoreTest.Source().apply { put() });"GENERATED_REVIEW_RESULT" } }
            finally { done.countDown() }
        }
        val completed:()->Unit={}
        MainActivity::class.java.declaredMethods.single { it.name=="localOperation" && it.parameterCount==2 }
            .apply { isAccessible=true }.invoke(a,action,completed)
        shadowOf(Looper.getMainLooper()).idle();assertTrue(creationEntered.await(5,TimeUnit.SECONDS))
        assertNull(field(a,"activeLocalStore"));return done
    }
    private fun savedRows()=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().length() } }
    private fun localCompleted(a:MainActivity) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if(field(a,"busy")==false) { assertNull(field(a,"activeLocalStore"));return }
            Thread.sleep(10)
        };fail("HOST_LOCAL_OPERATION_NOT_COMPLETED")
    }
    @Test fun R04SettingsStopBeforeStoreCreationCancelsImport() {
        val c=controller();val a=c.get();set(a,"settingsShowing",true);method(a,"buildShell")
        val done=startBlocked(a)
        views(root(a)).filterIsInstance<Button>().single { it.text.toString()=="가져오기 중단" }.performClick()
        val flag=field(a,"localCancelRequested");assertEquals(true,flag)
        creationRelease.countDown();localCompleted(a);assertEquals(1L,done.count);settle(a)
        val count=savedRows();assertEquals(0,count)
        evidence("R04","acceptance_pass" to true,"barrier" to "before createLocalStore returns","cancel_flag" to flag,"action_admitted" to false,
            "saved_after_stop" to count,"expected_safe" to "flag first; subsequent store cancelled before import")
        c.pause().stop().destroy()
    }
    @Test fun R05DestroyBeforeStoreCreationCancelsSynchronousImport() {
        val c=controller();val a=c.get();val done=startBlocked(a)
        c.pause().stop().destroy();val flag=field(a,"localCancelRequested");assertEquals(true,flag)
        creationRelease.countDown();localCompleted(a);assertEquals(1L,done.count)
        val count=savedRows();assertEquals(0,count)
        evidence("R05","acceptance_pass" to true,"barrier" to "before createLocalStore returns","cancel_flag" to flag,"action_admitted" to false,
            "saved_after_controller_destroy" to count,"scope_limit" to "controller lifecycle; not OS process kill",
            "expected_safe" to "onDestroy requests shared cancellation before store publication")
    }
    @Test fun R06TopStopBeforeStoreCreationBlocksImportControl() {
        val c=controller();val a=c.get();val done=startBlocked(a)
        views(root(a)).filterIsInstance<Button>().single { it.text.toString()=="로컬 작업 중단" }.performClick()
        assertEquals(true,field(a,"localCancelRequested"));creationRelease.countDown()
        localCompleted(a);assertEquals(1L,done.count);settle(a);assertEquals(0,savedRows())
        evidence("R06","control_pass" to true,"cancel_flag" to true,"saved_after_stop" to 0,"action_admitted" to false)
        c.pause().stop().destroy()
    }
    private val readError="기기에 저장된 통화 목록을 읽지 못했어요. 목록이 비었다고 판단하지 않았어요."
    private fun catalogPresentation(a:MainActivity,tab:Int,error:Boolean,loading:Boolean=false,loaded:Boolean=true,rows:JSONArray=JSONArray()) {
        set(a,"catalogFiles",rows);set(a,"catalogLoaded",loaded);set(a,"catalogLoading",loading)
        set(a,"catalogError",if(error)readError else null);set(a,"tab",tab);method(a,"buildShell")
    }
    @Test fun R07ArchiveErrorDoesNotDisplayConfirmedEmptyMessage() {
        val c=controller();val a=c.get();catalogPresentation(a,3,true);val texts=labels(a)
        assertTrue(texts.contains(readError));assertFalse(texts.contains("보관한 통화가 없어요."))
        evidence("R07","acceptance_pass" to true,"presenter_state_injected" to true,
            "error_and_confirmed_empty" to false,"labels" to JSONArray(texts),"scope_limit" to "forced failed-read presenter state, no actual DB fault")
        c.pause().stop().destroy()
    }
    @Test fun R08TodoErrorDoesNotDisplayConfirmedEmptyMessage() {
        val c=controller();val a=c.get();catalogPresentation(a,2,true);val texts=labels(a)
        assertTrue(texts.contains(readError));assertFalse(texts.contains("이 상태로 직접 관리한 통화가 없어요. 통화 상세에서 메모와 상태를 저장할 수 있어요."))
        evidence("R08","acceptance_pass" to true,"presenter_state_injected" to true,
            "error_and_confirmed_empty" to false,"labels" to JSONArray(texts),"scope_limit" to "forced failed-read presenter state, no actual DB fault")
        c.pause().stop().destroy()
    }
    @Test fun R09DatesErrorDoesNotClaimConfirmedEmptyControl() {
        val c=controller();val a=c.get();catalogPresentation(a,0,true);val texts=labels(a)
        assertTrue(texts.contains(readError));assertTrue(texts.contains("통화 목록을 확인하지 못했어요."))
        assertFalse(texts.contains("이 날짜로 확인된 통화가 저장 목록에 없어요. 날짜 미확인 항목도 확인해 주세요."))
        evidence("R09","control_pass" to true,"presenter_state_injected" to true,"failed_read_empty_claim" to false)
        c.pause().stop().destroy()
    }
    @Test fun R10ConfirmedEmptyArchiveWithoutErrorControl() {
        val c=controller();val a=c.get();catalogPresentation(a,3,false);val texts=labels(a)
        assertFalse(texts.contains(readError));assertTrue(texts.contains("보관한 통화가 없어요."))
        evidence("R10","control_pass" to true,"presenter_state_injected" to true,"confirmed_empty_without_error" to true)
        c.pause().stop().destroy()
    }
    private fun folder(p:Provider,id:String) {
        val tree=DocumentsContract.buildTreeDocumentUri("review.synthetic.callbrief","root")
        p.documents[id]=SourceDocument(id,DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),id,0,DocumentsContract.Document.MIME_TYPE_DIR,GeneralLocalStoreTest.NOW-600000)
    }
    @Test fun R11NestedLoadingPropagatesToWholeInventory()=io {
        val (p,s)=provider();folder(p,"sub");put(p,"old");put(p,"new")
        p.children["root"]=setOf("sub");p.children["sub"]=setOf("old","new");p.loadingParents.add("sub")
        val inv=s.inventory();assertFalse(inv.complete);assertEquals(2,inv.documents.size)
        evidence("R11","acceptance_pass" to true,"nested_loading" to true,"inventory_complete" to false)
    }
    @Test fun R12NestedErrorFailsListingBeforeAnyAudioRead()=io {
        val (p,s)=provider();folder(p,"sub");put(p,"one")
        p.children["root"]=setOf("sub");p.children["sub"]=setOf("one");p.errorParents.add("sub")
        rejectListing { s.inventory() };assertEquals(0,p.opens)
        evidence("R12","acceptance_pass" to true,"nested_error" to true,"opens" to 0)
    }
    @Test fun R13LoadingZeroRowsIsIncompleteNotConfirmedEmpty()=io {
        val (p,s)=provider();p.loading=true
        assertFalse(s.inventory().complete)
        GeneralLocalStoreTest.host(context,keys).use { store ->
            val report=store.importSelected(TextStoreTestSupport.generatedSource(s));assertTrue(report.incomplete);assertEquals(0,store.snapshot().length())
            evidence("R13","acceptance_pass" to true,"zero_rows" to true,"incomplete_report" to true)
        }
    }
    @Test fun R14ErrorWithRowsDoesNotImportThoseRows()=io {
        val (p,s)=provider();put(p,"one");p.error="GENERATED_ERROR_WITH_ROWS"
        GeneralLocalStoreTest.host(context,keys).use { store ->
            rejectListing { store.importSelected(TextStoreTestSupport.generatedSource(s)) };assertEquals(0,store.snapshot().length());assertEquals(0,p.opens)
            evidence("R14","acceptance_pass" to true,"rows" to 1,"saved" to 0,"opens" to 0)
        }
    }
    @Test fun R15PresentEmptyErrorSignalStillFailsClosed()=io {
        val (p,s)=provider();p.error="";rejectListing { s.inventory() };assertEquals(0,p.opens)
        evidence("R15","acceptance_pass" to true,"present_empty_error_key" to true,"typed_error" to "PROVIDER_LIST_FAILED")
    }
    private val confirmedEmptyLabels=listOf(
        "보관한 통화가 없어요.","이 상태로 직접 관리한 통화가 없어요. 통화 상세에서 메모와 상태를 저장할 수 있어요.",
        "업무가 확인된 저장 통화가 없어요. 업무 미분류 항목은 아래에 따로 표시해요.",
        "이 날짜로 확인된 통화가 저장 목록에 없어요. 날짜 미확인 항목도 확인해 주세요.",
        "현재 저장 목록에서 날짜 확인 대상은 없어요.","이 범위에서 확인된 요약이 없어요. 미처리·날짜 미확인 항목은 별도로 확인해 주세요.")
    @Test fun R16AllFourTabsLoadingAvoidConfirmedEmpty() {
        val c=controller();val a=c.get()
        for(tab in 0..3) {
            catalogPresentation(a,tab,false,loading=true);val texts=labels(a)
            assertTrue(texts.contains("저장 목록을 확인하고 있어요."));assertTrue(confirmedEmptyLabels.none { it in texts })
        }
        evidence("R16","acceptance_pass" to true,"tabs" to 4,"loading_confirmed_empty" to false)
        c.pause().stop().destroy()
    }
    @Test fun R17AllFourTabsUnconfirmedAvoidConfirmedEmpty() {
        val c=controller();val a=c.get()
        for(tab in 0..3) {
            catalogPresentation(a,tab,false,loaded=false);val texts=labels(a)
            assertTrue(texts.contains("아직 저장 목록을 확인하지 않았어요. 새로고침으로 확인해 주세요."));assertTrue(confirmedEmptyLabels.none { it in texts })
        }
        evidence("R17","acceptance_pass" to true,"tabs" to 4,"unconfirmed_empty_claim" to false)
        c.pause().stop().destroy()
    }
    @Test fun R18StaleSnapshotAndManualDoneArePreservedAcrossFourTabs() {
        val rows=io {
            GeneralLocalStoreTest.host(context,keys).use { store ->
                store.importSelected(GeneralLocalStoreTest.Source().apply { put() });val row=store.snapshot().getJSONObject(0)
                store.edit(row.getString("file_id"),row,"합성 수동 완료 보존","done");store.snapshot()
            }
        }
        val before=rows.toString();val c=controller();val a=c.get();set(a,"selectedCallDate",null);set(a,"taskFilter","done")
        for(tab in 0..3) {
            catalogPresentation(a,tab,true,rows=rows);val texts=labels(a)
            assertTrue(texts.contains(readError));assertTrue(texts.contains("아래 기록·건수는 마지막으로 읽은 목록 기준이에요. 새로고침으로 다시 확인해 주세요."))
            assertTrue(confirmedEmptyLabels.none { it in texts });assertEquals(before,rows.toString())
        }
        assertEquals(before,io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot().toString() } })
        evidence("R18","acceptance_pass" to true,"tabs" to 4,"manual_done_and_snapshot_unchanged" to true)
        c.pause().stop().destroy()
    }
    @Test fun R19ActualHostSqliteReadFailureIsNotEmptySuccess() {
        val c=controller();val a=c.get();val before=(field(a,"catalogFiles") as JSONArray).toString()
        // Generated empty DB only. Deliberately missing a query dependency produces a real SQLite exception.
        io { AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { it.execute("DROP TABLE source_versions") } }
        method(a,"refreshLocalCatalog");settle(a)
        assertEquals(readError,field(a,"catalogError"));assertEquals(before,(field(a,"catalogFiles") as JSONArray).toString())
        for(tab in 0..3) {
            set(a,"tab",tab);method(a,"buildShell");val texts=labels(a)
            assertTrue(texts.contains(readError));assertTrue(confirmedEmptyLabels.none { it in texts })
        }
        evidence("R19","acceptance_pass" to true,"actual_host_sqlite_exception" to true,"tabs" to 4,"previous_empty_snapshot_preserved" to true,
            "scope_limit" to "generated empty host DB dependency removed; no device corruption or data-loss test")
        c.pause().stop().destroy()
    }
    @Test fun R20ProviderTypedFailureOffersRetryWithoutRawMessage() {
        val c=controller();val a=c.get();val (p,s)=provider();p.error="GENERATED_PROVIDER_PRIVATE_DETAIL"
        val done=startBlocked(a) { store -> store.importSelected(TextStoreTestSupport.generatedSource(s));"UNREACHABLE_SUCCESS" }
        creationRelease.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));settle(a)
        val d=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val texts=views(d.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("선택 폴더 목록을 읽지 못했어요. 파일이 없다고 판단하지 않았어요. 다시 가져와 확인해 주세요."))
        assertTrue(texts.none { it.contains("GENERATED_PROVIDER_PRIVATE_DETAIL") });assertEquals(0,p.opens);assertEquals(0,savedRows())
        evidence("R20","acceptance_pass" to true,"retry_notice" to true,"raw_provider_error_exposed" to false,"opens" to 0)
        c.pause().stop().destroy()
    }
}
