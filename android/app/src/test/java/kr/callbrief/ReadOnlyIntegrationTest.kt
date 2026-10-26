package kr.callbrief

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callagent.drive.ReadonlyV1Codec
import kr.callagent.drive.SqliteProfileInbox.Status
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Same real local-v2 DB, generated native records and frozen imported synthetic result. No OAuth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadOnlyIntegrationTest : ProtectedTextHostTestBase() {
    companion object {
        var block=false;lateinit var entered:CountDownLatch;lateinit var release:CountDownLatch
    }
    internal class HostActivity:ReadOnlyProposalActivity() {
        override fun createStore(check:()->Unit):ReadOnlyProposalStore {
            if(block) { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) }
            return super.createStore(check)
        }
    }
    private lateinit var context:Context
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=executor.submit<T>{ f() }.get(30,TimeUnit.SECONDS)
    @Before fun seedExistingNativeDone() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();RuntimeEnvironment.setFontScale(1f)
        context.deleteDatabase(GeneralLocalStore.DATABASE);File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        block=false;entered=CountDownLatch(1);release=CountDownLatch(1)
        io { GeneralLocalStoreTest.host(context,keys).use { s ->
            s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply { put() });val row=s.snapshot().getJSONObject(0)
            s.confirmTime(row.getString("file_id"),row,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli())
            val fresh=s.snapshot().getJSONObject(0);s.edit(fresh.getString("file_id"),fresh,"합성 수동 NOTE · DONE 유지","done")
        } }
    }
    @After fun done() { block=false;release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun request(selected:Boolean=false)=if(selected)javaClass.getResourceAsStream("/drive-ro1/selected-request.json")!!.use { it.readBytes() } else context.assets.open("ro1-synthetic/request.json").use { it.readBytes() }
    private fun result(selected:Boolean=false)=if(selected)javaClass.getResourceAsStream("/drive-ro1/selected-result.json")!!.use { it.readBytes() } else context.assets.open("ro1-synthetic/result.json").use { it.readBytes() }
    private fun open()=TextStoreTestSupport.open(context,GeneralLocalStore.DATABASE)
    private fun count(table:String):Long=open().use { db ->db.rawQuery("SELECT COUNT(*) FROM $table",null).use { c ->assertTrue(c.moveToFirst());c.getLong(0) } }
    private fun canonical():String=open().use { db ->
        val value=JSONObject();val names=mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'drive_%' AND name NOT LIKE 'eum_ro_%' ORDER BY name",null).use { c ->while(c.moveToNext())names+=c.getString(0) }
        names.forEach { name ->val rows=JSONArray();db.rawQuery("SELECT * FROM \"$name\"",null).use { c ->while(c.moveToNext()){val row=JSONArray();for(i in 0 until c.columnCount)row.put(if(c.isNull(i))JSONObject.NULL else if(c.getType(i)==4)android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP) else c.getString(i));rows.put(row)}};value.put(name,rows) };value.toString()
    }
    private fun evidence(id:String,vararg items:Pair<String,Any?>) {
        val json=JSONObject().put("id",id).put("pass",true).put("scope","API35_NATIVE_HOST_SAME_DB_NOT_DEVICE_OAUTH_OR_DRIVE")
        items.forEach { json.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/ro1-integration").apply { mkdirs() };File(dir,"$id.json").writeText(json.toString(2))
    }
    private fun field(a:ReadOnlyProposalActivity,n:String)=ReadOnlyProposalActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun root(a:ReadOnlyProposalActivity)=field(a,"root") as ViewGroup
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun settle(a:ReadOnlyProposalActivity) { repeat(250){shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10)};fail("RO_UI_NOT_SETTLED") }
    private fun activity()=Robolectric.buildActivity(HostActivity::class.java).setup().also { settle(it.get()) }
    private fun render(a:ReadOnlyProposalActivity,name:String) {
        val v=root(a);val w=720;val h=1600;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val dir=File("build/reports/ro1-integration-screens").apply { mkdirs() };FileOutputStream(File(dir,"$name.png")).use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun R01AdditiveMigrationKeepsAll38CanonicalTablesAndNativeDone() {
        val before=canonical();io{ReadOnlyProposalStore(context).use { assertTrue(it.list().isEmpty()) }}
        assertEquals(38,JSONObject(before).length());assertEquals(before,canonical())
        evidence("R01","canonical_tables_unchanged" to 38,"same_database" to GeneralLocalStore.DATABASE)
    }
    @Test fun R02ExactSelectedRevision3StoreDuplicateAndReopen() {
        val req=request(true);val res=result(true);val before=canonical();val valid=ReadonlyV1Codec().validate(res,req)
        assertEquals(36131,res.size);assertEquals(14,valid.observations);assertEquals(18,valid.quotes);assertEquals(7,valid.segments)
        io { ReadOnlyProposalStore(context).use { s ->
            assertEquals(Status.PROVENANCE_PENDING,s.importSynthetic(req,res).status)
            val v=s.list().single().view!!;assertArrayEquals(res,v.receipt.bytes);assertEquals(3L,v.receipt.revision)
            assertFalse(v.applied);assertFalse(v.canonicalWriteAllowed);assertFalse(v.writerVerified);assertEquals("UNVERIFIED",v.nativeBinding)
            assertEquals(Status.DUPLICATE,s.importSynthetic(req,res).status)
        } }
        io { ReadOnlyProposalStore(context).use { s ->assertArrayEquals(res,s.list().single().view!!.receipt.bytes);assertEquals(Status.DUPLICATE,s.importSynthetic(req,res).status) } }
        assertEquals(1L,count("drive_profiles_receipts"));assertEquals(0L,count("drive_v0_receipts"));assertEquals(before,canonical())
        evidence("R02","selected_bytes" to 36131,"revision" to 3,"observations" to 14,"quotes" to 18,"segments" to 7,"receipt_count" to 1,"byte_identical_reopen" to true,"prior_v0_seeded" to false,"native_binding" to "UNVERIFIED")
    }
    @Test fun R03BundledCodecExampleIsLocalOnlyAndPreservesManualState() {
        val before=canonical();io { ReadOnlyProposalStore(context).use { s ->assertEquals(Status.PROVENANCE_PENDING,s.importSynthetic(request(),result()).status);val v=s.list().single().view!!;assertEquals("READ_ONLY_PROPOSAL",v.displayMode);assertFalse(v.executionVerified);assertFalse(v.writerVerified) } }
        assertEquals(before,canonical());assertFalse(NativeAnalysisAvailability.canTransfer());assertEquals("DRIVE_AUTH_NOT_CONNECTED",ReadOnlyProposalStore.CONNECTION)
        evidence("R03","canonical_write" to false,"native_auth" to "DISCONNECTED","network_calls" to 0)
    }
    @Test fun R04NativeEditAfterIntakeMarksUnboundProposalAndKeepsNewDone() {
        io { ReadOnlyProposalStore(context).use { it.importSynthetic(request(),result()) }
            GeneralLocalStoreTest.host(context,keys).use { s ->val row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,"합성 뒤에 수정한 NOTE","done") }
            ReadOnlyProposalStore(context).use { s ->assertTrue(s.list().single().nativeRecordsChanged);assertEquals(Status.DUPLICATE,s.importSynthetic(request(),result()).status);assertTrue(s.list().single().nativeRecordsChanged) }
        }
        val row=io{GeneralLocalStoreTest.host(context,keys).use { it.snapshot().getJSONObject(0) }};assertEquals("합성 뒤에 수정한 NOTE",row.getString("manual_note"));assertEquals("done",row.getString("manual_status"))
        evidence("R04","native_manual_edit_preserved" to true,"duplicate_does_not_reset_native_baseline" to true)
    }
    @Test fun R05UnapprovedChangedPayloadCannotReplaceCurrentProposal() {
        io { ReadOnlyProposalStore(context).use { s ->s.importSynthetic(request(),result());val before=s.list().single().view!!.receipt.digest
            val changed=result().clone();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte()
            try{s.importSynthetic(request(),changed);fail("unapproved data accepted")}catch(_:IllegalArgumentException){}
            assertEquals(before,s.list().single().view!!.receipt.digest)
        } };assertEquals(1L,count("drive_profiles_receipts"));evidence("R05","unapproved_pin_held" to true,"current_receipt_preserved" to true)
    }
    @Test fun R06CancelAtCasRollsBackImportedSourceJobAndReceipt() {
        val before=canonical();var armed=false
        io { ReadOnlyProposalStore(context) {
            if(armed && Throwable().stackTrace.any { it.className=="kr.callagent.drive.SqliteProfileInbox" && it.methodName=="receive" })throw CancellationException("synthetic_cancel")
        }.use { s ->armed=true;try{s.importSynthetic(request(),result());fail("cancel ignored")}catch(_:CancellationException){} } }
        assertEquals(0L,count("drive_v0_jobs"));assertEquals(0L,count("drive_profiles_receipts"));assertEquals(0L,count("eum_ro_sources"));assertEquals(before,canonical())
        evidence("R06","pre_commit_cancel_rollback" to true,"canonical_unchanged" to true)
    }
    @Test fun R07RawSqlReplaceOfImmutableReceiptIsRejected() {
        io{ReadOnlyProposalStore(context).use { it.importSynthetic(request(),result()) }}
        open().use { db ->try{db.execSQL("INSERT OR REPLACE INTO drive_profiles_receipts SELECT owner_id,job_id,result_id,kind,schema_version,result_revision,digest,payload,conflict,hidden FROM drive_profiles_receipts");fail("replace allowed")}catch(_:android.database.sqlite.SQLiteException){} }
        assertEquals(1L,count("drive_profiles_receipts"));evidence("R07","raw_replace_rejected" to true)
    }
    @Test fun R08InactiveSyntheticScopeCannotExposeOrReplayCachedReceipt() {
        io{ReadOnlyProposalStore(context).use { it.importSynthetic(request(),result()) }}
        open().use { it.execSQL("UPDATE eum_ro_sources SET active=0 WHERE profile_id='codec_example'") }
        io{ReadOnlyProposalStore(context).use { s ->assertNull(s.list().single().view);assertEquals(Status.SOURCE_QUARANTINED,s.importSynthetic(request(),result()).status) }}
        assertEquals(1L,count("drive_profiles_receipts"));evidence("R08","inactive_cache_held" to true,"receipt_preserved" to true)
    }
    @Test fun R09UserScreenDistinguishesDisconnectedLocalAndUnapplied() {
        val before=canonical();val c=activity();val a=c.get();root(a).findViewWithTag<Button>("ro1_import_example").performClick();settle(a);render(a,"ro1-local-example-font100-host")
        val text=labels(root(a));assertTrue(text.contains(ReadOnlyProposalStore.CONNECTION_MESSAGE));assertTrue(text.contains("합성 로컬 인수 · 앱 업무에 미반영"))
        assertTrue(text.any{it.contains("기준 기록 변경 없음")});assertTrue(text.any{it.contains("원자료 출처 확인 필요")})
        assertFalse(text.any{it.contains("연결 완료") || it.contains("분석 완료") || it.contains("전송 완료")});assertEquals(before,canonical())
        evidence("R09","fake_connected" to false,"readonly_unapplied_visible" to true,"canonical_unchanged" to true)
        c.pause().stop().destroy()
    }
    @Test fun R10Font200ReadOnlyScreenKeepsTextAndScroll() {
        RuntimeEnvironment.setFontScale(2f);io{ReadOnlyProposalStore(context).use { it.importSynthetic(request(),result()) }}
        val c=activity();val a=c.get();render(a,"ro1-local-example-font200-host")
        val heading=root(a).findViewWithTag<TextView>("ro1_heading");assertNull(heading.ellipsize)
        assertEquals(heading.text.length,heading.layout.getLineEnd(heading.layout.lineCount-1))
        val titleBounds=android.graphics.Rect();assertTrue(heading.getGlobalVisibleRect(titleBounds));assertEquals(heading.width,titleBounds.width());assertEquals(heading.height,titleBounds.height())
        val summary=root(a).findViewWithTag<TextView>("ro1_summary");assertNotNull(summary);assertNull(summary.ellipsize);assertEquals(summary.text.length,summary.layout.getLineEnd(summary.layout.lineCount-1))
        val scroll=root(a).findViewWithTag<android.widget.ScrollView>("ro1_scroll");assertTrue(scroll.getChildAt(0).height>scroll.height)
        scroll.scrollTo(0,scroll.getChildAt(0).height);render(a,"ro1-font200-bottom-host")
        evidence("R10","font_scale" to 2,"scrollable" to true,"readonly_summary_ellipsized" to false);c.pause().stop().destroy()
    }
    @Test fun R11DestroyWhileStoreConstructionDoesNotRenderOrCreateProposal() {
        block=true;val c=Robolectric.buildActivity(HostActivity::class.java).setup();val a=c.get();assertTrue(entered.await(5,TimeUnit.SECONDS))
        var mutations=0;root(a).setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent:View?,child:View?){mutations++};override fun onChildViewRemoved(parent:View?,child:View?){mutations++}
        });c.pause().stop().destroy();release.countDown();repeat(20){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertEquals(0,mutations);evidence("R11","dead_ui_mutations" to 0,"android_lifecycle_device_execution" to false)
    }
    @Test fun R12SelectedExternalNamespaceNeverInventsNativeMapping() {
        val before=canonical();io{ReadOnlyProposalStore(context).use { s ->s.importSynthetic(request(true),result(true));val e=s.list().single();assertEquals("selected_revision3",e.pin.id);assertEquals("UNVERIFIED",e.view!!.nativeBinding);assertFalse(e.nativeRecordsChanged);assertFalse(e.view.applied) }}
        assertEquals(before,canonical());evidence("R12","native_record_mapping" to "UNVERIFIED","same_db_additive_inbox" to true,"actual_oauth_execution" to 0)
    }
    @Test fun R13PostedFileIntakeAfterStopHasNoNewAdmission() {
        val c=activity();val a=c.get();val before=canonical()
        ReadOnlyProposalActivity::class.java.getDeclaredMethod("import",ByteArray::class.java,ByteArray::class.java).apply { isAccessible=true }.invoke(a,request(),result())
        c.pause().stop();repeat(20){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertEquals(0L,count("drive_profiles_receipts"));assertEquals(0L,count("drive_v0_jobs"));assertEquals(before,canonical())
        evidence("R13","posted_after_stop_admissions" to 0,"canonical_unchanged" to true);c.destroy()
    }
}
