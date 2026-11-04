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
        const val LEGACY_DATABASE="callbrief-local-v1.db"
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
    private fun rows(sql:String)=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { it.query(sql) }
    private fun reject(code:String,action:()->Unit) {
        try { action();fail("Expected $code") } catch(e:RuntimeException) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(code)) }
    }
    @Test fun constructorDoesNotCreateKeyOrReadOldStores()=io {
        context.getDatabasePath(LEGACY_DATABASE).writeText("historical fixture sentinel")
        host(context,keys).use { assertEquals("EMPTY",it.setupState());assertEquals(0,keys.provisions.get());assertEquals(0,it.snapshot().length()) }
        assertEquals("historical fixture sentinel",context.getDatabasePath(LEGACY_DATABASE).readText())
    }
}
