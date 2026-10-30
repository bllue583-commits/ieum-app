package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.vault.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native SQLite shadow + host JCE/fsync, PUBLIC test key, metadata-only fake selected provider.
 * Not a device grant/Keystore/lifecycle or personal-data test. No real filesystem source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GeneralLocalStoreTest : ProtectedTextHostTestBase() {
    companion object {
        val NOW=Instant.parse("2026-10-12T02:00:00Z").toEpochMilli()
        fun wav(alternate:Boolean=false)=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(44);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
            putInt(8000);putInt(16000);putShort(2);putShort(16);put("data".toByteArray());putInt(8)
            repeat(4) { putShort((if(alternate) 100 else 0).toShort()) }
        }.array()
        fun host(context:Context,keys:HostVaultKeys,fault:(String)->Unit={})=
            GeneralLocalStore(context,{ privateContext ->
                val root=privateContext.noBackupFilesDir.toPath().resolve("audio-vault-v2")
                GeneralLocalStore.VaultAccess(EncryptedAudioVault(root,keys,{ directory ->
                    FileChannel.open(directory,StandardOpenOption.READ).use { it.force(true) }
                }),root,keys::provision)
            },{ NOW },fault)
    }
    class Source : SelectedRecordingSource {
        override val explicitlySyntheticScope=true // Created bytes only, test sources never ship.
        override val rootUri="content://generated-fixture/tree/test"
        var granted=true;var complete=true;var opens=0;var onOpen:(Int)->Unit={}
        var onRead:(Int)->Unit={};val excludedIds=mutableSetOf<String>()
        val documents=linkedMapOf<String,SourceDocument>();val bytes=mutableMapOf<String,ByteArray>()
        fun put(id:String="one",name:String="합성_20261010_100000.wav",alternate:Boolean=false,modified:Long=NOW-600000) {
            val data=wav(alternate);bytes[id]=data
            documents[id]=SourceDocument(id,"$rootUri/document/$id",name,data.size.toLong(),"audio/wav",modified)
        }
        override fun checkSelected() { check(granted) { "SELECTED_FOLDER_READ_GRANT_REQUIRED" } }
        override fun inventory()=SourceInventory(documents.values.toList(),documents.keys.toSet(),complete)
        override fun stat(document:SourceDocument)=documents.getValue(document.documentId)
        override fun excluded(document:SourceDocument)=document.documentId in excludedIds
        // Explicit generated-fixture policy lives in test sources only; production has no permissive default.
        override fun checkReadable(document:SourceDocument) { checkSelected();check(!excluded(document)) { "EXCLUDED_BEFORE_READ" } }
        override fun open(document:SourceDocument):InputStream {
            checkSelected();check(!excluded(document));opens++;onOpen(opens)
            return object:ByteArrayInputStream(bytes.getValue(document.documentId)) {
                override fun read(b:ByteArray,off:Int,len:Int):Int { onRead(opens);return super.read(b,off,len) }
            }
        }
    }
    private lateinit var context:Context
    private lateinit var keys:HostVaultKeys
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(action:()->T):T=try { worker.submit<T> { action() }.get(30,TimeUnit.SECONDS) }
        catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    @Before fun fresh() {
        context=RuntimeEnvironment.getApplication();keys=HostVaultKeys()
        context.deleteDatabase(GeneralLocalStore.DATABASE)
        File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively() // Generated test sandbox ONLY.
    }
    @After fun stop() { worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun <T> ready(action:(GeneralLocalStore,Source)->T):T=io {
        host(context,keys).use { s ->s.initializeEmpty();val source=Source().apply { put() };action(s,source) }
    }
    private fun only(s:GeneralLocalStore)=s.snapshot().getJSONObject(0)
    private fun rows(sql:String)=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { it.query(sql) }
    private fun reject(code:String,action:()->Unit) {
        try { action();fail("Expected $code") } catch(e:RuntimeException) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(code)) }
    }
    @Test fun constructorDoesNotCreateKeyOrReadOldStores()=io {
        context.getDatabasePath(LocalRecordingCatalog.DATABASE_NAME).writeText("historical fixture sentinel")
        host(context,keys).use { assertEquals("EMPTY",it.setupState());assertEquals(0,keys.provisions.get());assertEquals(0,it.snapshot().length()) }
        assertEquals("historical fixture sentinel",context.getDatabasePath(LocalRecordingCatalog.DATABASE_NAME).readText())
    }
    @Test fun exclusionBeforeHashOrEncryptionHasZeroOpens()=ready { s,source ->
        source.excludedIds.add("one");val r=s.importSelected(source)
        assertEquals(1,r.excluded);assertEquals(0,source.opens);assertEquals(0,s.snapshot().length());assertEquals(0,rows("SELECT * FROM vault2_pending").size)
    }
    @Test fun revokedGrantHasZeroOpensAndRows()=ready { s,source ->
        source.granted=false;reject("READ_GRANT_REQUIRED") { s.importSelected(source) };assertEquals(0,source.opens);assertEquals(0,s.snapshot().length())
    }
    @Test fun reproV2ActualCanonicalReceiptBeforeAliasFaultLeavesCanonicalRowWithoutAlias()=io {
        var injected=false
        val source=Source().apply { put(name="통화 녹음 합성업무_01000000001_20261010_100000.wav") }
        host(context,keys,{ point ->if(point=="AFTER_CANONICAL_RECEIPT_BEFORE_ALIAS" && !injected) {
            injected=true;throw IllegalStateException("PUBLIC_SYNTHETIC_FAULT")
        } }).use { s ->
            s.initializeEmpty();assertEquals(1,s.importSelected(source).held);assertTrue(injected)
            assertEquals(1,rows("SELECT * FROM recordings").size);assertEquals(0,rows("SELECT * FROM local_files").size)
        }
    }
    @Test fun validEncryptedImportLeavesTimeAndPeopleUnknownAndNoSummary()=ready { s,source ->
        val r=s.importSelected(source);assertEquals(1,r.newVersions);assertEquals(0,r.held)
        val row=only(s);assertEquals(1,row.getInt("source_version"));assertTrue(row.isNull("original_date"));assertTrue(row.isNull("person_id"));assertTrue(row.isNull("task_id"));assertTrue(row.isNull("summary"))
        assertFalse(row.isNull("filename_time_candidate"));assertEquals(1,rows("SELECT * FROM vault2_refs").size)
        assertEquals(0,rows("SELECT * FROM canonical_storage").size);assertEquals(0,rows("SELECT * FROM processing WHERE processing_id IS NOT NULL").size)
        assertEquals(0,File(context.noBackupFilesDir,"local-recordings-v2").walkTopDown().count { it.isFile && it.extension in listOf("wav","pcm","tmp") })
    }
    @Test fun unsupportedFileAndGrowingFileAreHeldBeforeOpen()=ready { s,source ->
        source.documents["one"]=source.documents.getValue("one").copy(mime="text/plain")
        assertEquals(1,s.importSelected(source).held);assertEquals(0,source.opens)
        source.put(modified=NOW-100);assertEquals(1,s.importSelected(source).held);assertEquals(0,source.opens)
    }
    @Test fun metadataMutationAfterHashCannotCommit()=ready { s,source ->
        source.onRead={ source.documents["one"]=source.documents.getValue("one").copy(modified=NOW-400000) }
        assertEquals(1,s.importSelected(source).held);assertEquals(0,s.snapshot().length());assertEquals(0,rows("SELECT * FROM receipts").size)
    }
    @Test fun byteMutationWithUnchangedMetadataCannotCommit()=ready { s,source ->
        source.onOpen={ if(it==2)source.bytes["one"]=wav(true) }
        assertEquals(1,s.importSelected(source).held);assertEquals(0,s.snapshot().length());assertEquals(0,rows("SELECT * FROM receipts").size)
    }
    @Test fun repeatedSelectionReusesVersionAndReceipt()=ready { s,source ->
        s.importSelected(source);val receipts=rows("SELECT * FROM receipts").size
        val r=s.importSelected(source);assertEquals(1,r.sameContent);assertEquals(0,r.newVersions);assertEquals(1,s.snapshot().length());assertEquals(1,only(s).getInt("source_version"));assertEquals(receipts,rows("SELECT * FROM receipts").size)
    }
    @Test fun renameWithStableProviderIdKeepsOneVersion()=ready { s,source ->
        s.importSelected(source);val id=only(s).getString("file_id")
        source.documents["one"]=source.documents.getValue("one").copy(name="이름 변경.wav")
        assertEquals(0,s.importSelected(source).held);assertEquals(id,only(s).getString("file_id"));assertEquals(1,only(s).getInt("source_version"))
    }
    @Test fun renameWithChangedProviderIdRequiresUniqueAbsentPriorId()=ready { s,source ->
        s.importSelected(source);val id=only(s).getString("file_id")
        source.documents.clear();source.put(id="renamed",name="변경한 이름.wav")
        assertEquals(0,s.importSelected(source).held);assertEquals(1,s.snapshot().length());assertEquals(id,only(s).getString("file_id"));assertEquals(1,only(s).getInt("source_version"))
    }
    @Test fun simultaneousSameHashDocumentsRemainSeparate()=ready { s,source ->
        source.put("two","별도 합성.wav");assertEquals(2,s.importSelected(source).newVersions);assertEquals(2,s.snapshot().length())
    }
    @Test fun incompleteInventoryHoldsPotentialRename()=ready { s,source ->
        s.importSelected(source);source.documents.clear();source.put("renamed");source.complete=false
        val r=s.importSelected(source);assertTrue(r.incomplete);assertEquals(1,r.held);assertEquals(1,s.snapshot().length())
    }
    @Test fun abaVersionSequenceProtectsManualDoneAndNote()=ready { s,source ->
        s.importSelected(source);val r=only(s);s.edit(r.getString("file_id"),r,"직접 확인한 합성 메모","done")
        source.put(alternate=true,modified=NOW-500000);assertEquals(0,s.importSelected(source).held)
        source.put(alternate=false,modified=NOW-400000);assertEquals(0,s.importSelected(source).held)
        val after=only(s);assertEquals(3,after.getInt("source_version"));assertEquals("done",after.getString("manual_status"));assertEquals("직접 확인한 합성 메모",after.getString("manual_note"));assertTrue(after.isNull("summary"));assertEquals(3,s.history(after.getString("file_id")).getJSONArray("versions").length())
    }
    @Test fun explicitTimeConfirmationUsesConfirmedDayAndKeepsMemo()=ready { s,source ->
        s.importSelected(source);val r=only(s);s.confirmTime(r.getString("file_id"),r,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli())
        assertEquals("2026-10-10",only(s).getString("original_date"));source.put(alternate=true,modified=NOW-500000);s.importSelected(source)
        assertEquals("2026-10-10",only(s).getString("original_date"))
    }
    @Test fun staleManualAndArchiveSnapshotsAreRejected()=ready { s,source ->
        s.importSelected(source);val r=only(s);s.edit(r.getString("file_id"),r,"보존","done")
        reject("REVISION_CONFLICT") { s.edit(r.getString("file_id"),r,"덮기","open") }
        reject("REVISION_CONFLICT") { s.archive(r.getString("file_id"),r,true) }
        assertEquals("done",only(s).getString("manual_status"))
    }
    @Test fun archiveAndRestoreSameDurableRecordWithoutDeletingAudio()=ready { s,source ->
        s.importSelected(source);val r=only(s);val refs=rows("SELECT * FROM vault2_refs").size
        s.archive(r.getString("file_id"),r,true);assertTrue(only(s).getBoolean("archived"))
        source.put(alternate=true,modified=NOW-500000);s.importSelected(source);assertTrue(only(s).getBoolean("archived"))
        s.archive(r.getString("file_id"),only(s),false);assertFalse(only(s).getBoolean("archived"));assertEquals(refs+1,rows("SELECT * FROM vault2_refs").size);assertEquals(2,rows("SELECT * FROM local_visibility_history").size)
    }
    @Test fun closeAndNewRepositoryReadPreservesManualAndArchive()=io {
        val source=Source().apply { put() };var id=""
        host(context,keys).use { s ->s.initializeEmpty();s.importSelected(source);val r=only(s);id=r.getString("file_id");s.edit(id,r,"재열기 메모","done");s.archive(id,only(s),true) }
        host(context,keys).use { s ->val r=only(s);assertEquals(id,r.getString("file_id"));assertTrue(r.getBoolean("archived"));assertEquals("done",r.getString("manual_status"));assertEquals("재열기 메모",r.getString("manual_note")) }
    }
    @Test fun interruptionAfterEncryptionRecoversSamePendingVersion()=io {
        val source=Source().apply { put() };var injected=false
        host(context,keys,{ point ->if(point=="AFTER_ENCRYPT_BEFORE_JOURNAL" && !injected) { injected=true;throw IllegalStateException("GENERATED_FAULT") } }).use { s ->
            s.initializeEmpty();assertEquals(1,s.importSelected(source).held);assertEquals(0,s.snapshot().length())
        }
        host(context,keys).use { s ->assertEquals(0,s.importSelected(source).held);assertEquals(1,s.snapshot().length());assertEquals(1,rows("SELECT * FROM vault2_refs").size);assertEquals(1,only(s).getInt("source_version")) }
    }
    @Test fun interruptionAfterReceiptBeforeAliasRecoversWithoutDuplicate()=io {
        val source=Source().apply { put() };var injected=false
        host(context,keys,{ p ->if(p=="AFTER_CANONICAL_RECEIPT_BEFORE_ALIAS" && !injected) { injected=true;throw IllegalStateException("GENERATED_FAULT") } }).use { s ->
            s.initializeEmpty();assertEquals(1,s.importSelected(source).held);assertEquals(0,s.snapshot().length());assertEquals(1,rows("SELECT * FROM receipts").size)
        }
        host(context,keys).use { s ->assertEquals(0,s.importSelected(source).held);assertEquals(1,s.snapshot().length());assertEquals(1,rows("SELECT * FROM receipts").size) }
    }
    @Test fun cancellationDuringReadCommitsNoReceipt()=ready { s,source ->
        source.onRead={ s.cancel() };reject("CANCELLED") { s.importSelected(source) };assertEquals(0,rows("SELECT * FROM receipts").size)
    }
    @Test fun newlyExcludedDuringReadDoesNotCommit()=ready { s,source ->
        source.onRead={ source.excludedIds.add("one") };assertEquals(1,s.importSelected(source).held);assertEquals(0,rows("SELECT * FROM receipts").size)
    }
    @Test fun lostKeyDoesNotRegenerateOrReturnSuccessfulImport()=ready { s,source ->
        s.importSelected(source);keys.state=VaultKeys.State.MISSING;reject("NOT_READY") { s.importSelected(source) };assertEquals(1,keys.provisions.get());assertEquals(1,rows("SELECT * FROM vault2_refs").size)
    }
}
