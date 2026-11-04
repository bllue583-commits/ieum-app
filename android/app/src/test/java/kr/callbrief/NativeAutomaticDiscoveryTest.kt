package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.co.callsummary.vault.*
import kr.co.ieum.discovery.DiscoveryTypes.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Actual protected SQL + host JCE audio vault + production native sink. Real Android/Keystore 0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeAutomaticDiscoveryTest {
    private val now=Instant.parse("2026-10-12T02:00:00Z").toEpochMilli()
    private lateinit var context:Context
    private val io=Executors.newSingleThreadExecutor()
    private fun <T> worker(action:()->T):T=try{io.submit<T>{action()}.get(25,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    class Keys:VaultKeys {
        var key:SecretKey?=null;var decrypts=0
        override fun state(alias:String)=if(key==null)VaultKeys.State.MISSING else VaultKeys.State.AVAILABLE
        fun create(alias:String){check(key==null);key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()}
        override fun encrypt(alias:String)=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,checkNotNull(key))}
        override fun decrypt(alias:String,iv:ByteArray):Cipher{decrypts++;return Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,checkNotNull(key),GCMParameterSpec(128,iv))}}
    }
    private lateinit var keys:Keys
    private class Time:kr.co.ieum.discovery.DiscoveryTypes.Clock {var elapsed=0L;var boot="host_boot_1";override fun now()=Moment(boot,elapsed)}
    private class Source:SelectedRecordingSource {
        override val explicitlySyntheticScope=true
        override val rootUri="content://ieum.synthetic/tree/chosen"
        val docs=linkedMapOf<String,SourceDocument>();val bytes=hashMapOf<String,ByteArray>();var grant=true;var allowed=true;var excluded=false;var complete=true
        var opens=0;var stats=0;var onRead:()->Unit={};var onInventory:()->Unit={};val failOpens=mutableSetOf<String>()
        override fun checkSelected(){check(grant){"SELECTED_FOLDER_READ_GRANT_REQUIRED"}}
        override fun inventory():SourceInventory {onInventory();return SourceInventory(docs.values.toList(),docs.keys.toSet(),complete)}
        override fun stat(document:SourceDocument):SourceDocument {stats++;checkSelected();if(!bytes.containsKey(document.documentId))throw FileNotFoundException("SYNTHETIC_MISSING");return docs.getValue(document.documentId)}
        override fun excluded(document:SourceDocument)=excluded
        override fun checkReadable(document:SourceDocument){checkSelected();check(!excluded){"EXCLUDED_BEFORE_READ"};check(allowed){"CONTACT_OR_IDENTITY_REVIEW_REQUIRED"}}
        override fun open(document:SourceDocument):InputStream {checkReadable(document);opens++;if(document.documentId in failOpens)throw IOException("SYNTHETIC_RETRY");return object:ByteArrayInputStream(bytes.getValue(document.documentId)){override fun read(b:ByteArray,o:Int,n:Int):Int{val count=super.read(b,o,n);onRead();return count}}}
        fun put(id:String="one",sample:Int=0,modified:Long=0,partial:Boolean=false,name:String="SYNTHETIC_FILENAME_CANARY.wav") {
            val b=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply{put("RIFF".toByteArray());putInt(44);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16);put("data".toByteArray());putInt(8);repeat(4){putShort(sample.toShort())}}.array()
            bytes[id]=b;docs[id]=SourceDocument(id,"$rootUri/document/$id",name,b.size.toLong(),"audio/wav",modified,partial)
        }
    }
    private val config=Config(10,10000,1000,25000000,2)
    @Before fun fresh(){context=RuntimeEnvironment.getApplication();File(context.noBackupFilesDir,"protected-databases-v1").deleteRecursively();File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively();ShadowTextKeys.KEYS.clear();ShadowTextKeys.CREATES.set(0);ShadowTextKeys.deny=false;ShadowTextAtomicFile.failNext=false;keys=Keys()}
    @After fun done(){io.shutdown();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS))}
    private fun store()=GeneralLocalStore(context,{c->
        val root=c.noBackupFilesDir.toPath().resolve("audio-vault-v2")
        GeneralLocalStore.VaultAccess(EncryptedAudioVault(root,keys,{p->FileChannel.open(p,StandardOpenOption.READ).use{it.force(true)}}),root,keys::create)
    },{now})
    private fun selected(source:Source)=Selection(GeneralLocalStore.OWNER+"_synthetic",source.rootUri,1)
    private fun session(s:GeneralLocalStore,source:Source,t:Time,fault:FaultHook=FaultHook {})=NativeDiscoverySession(s,source,selected(source),{JSONObject()},t,{now},true,config,fault)
    private fun rows(sql:String)=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use{it.requireProtectedPersistence();it.query(sql)}
    private fun only(s:GeneralLocalStore)=s.snapshot().getJSONObject(0)
    private fun ready(s:GeneralLocalStore,source:Source,t:Time,action:(NativeDiscoverySession)->Unit){session(s,source,t).use{it.cycle(1,1);t.elapsed+=10;it.cycle(1,1);action(it)}}
    @Test fun N01NormalSinkCreatesNativeEncryptedSourceAndUnknownTime()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){assertEquals(1L,only(s).getLong("source_version"));assertTrue(only(s).isNull("original_started_at"));assertTrue(only(s).isNull("person_id"));assertTrue(only(s).isNull("task_id"));assertEquals(1,rows("SELECT * FROM vault2_refs").size);assertEquals(1,rows("SELECT * FROM discovery_intake_requests").size)}}
        assertFalse(context.getDatabasePath(GeneralLocalStore.DATABASE).exists());assertTrue(ProtectedDatabase.file(context,GeneralLocalStore.DATABASE).isFile)
    }
    @Test fun N02ExcludedHasZeroOpensAndNoFreshMetadataRow()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000);excluded=true};ready(s,src,Time()){assertEquals(0,src.opens);assertEquals(0,s.snapshot().length());assertEquals(0,it.engine.rows().size);assertEquals(0,rows("SELECT * FROM discovery_documents").size)}}
    }
    @Test fun N03UnknownCounterpartyIsHeldBeforeRead()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000);allowed=false};ready(s,src,Time()){assertEquals(Status.HELD_UNCONFIRMED,it.engine.rows().single().status);assertEquals(0,src.opens);assertEquals(0,rows("SELECT * FROM receipts").size)}}
    }
    @Test fun N04PartialWaitThenCompleteIsImported()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000,partial=true)};val t=Time();session(s,src,t).use{it.cycle(1,1);t.elapsed+=20;it.cycle(1,1);assertEquals(0,src.opens);src.put(modified=now-300000);it.cycle(1,1);t.elapsed+=10;it.cycle(1,1);assertEquals(1,s.snapshot().length())}}
    }
    @Test fun N05BootstrapReusesExistingNativeRecordingAndVersion()=worker {
        val src=Source().apply{put(modified=now-300000)};var original=""
        store().use{s->s.initializeEmpty();val t=Time();session(s,src,t).use{it.cycle(1,1);t.elapsed+=10;it.cycle(1,1)};original=only(s).getString("file_id")}
        store().use{s->ready(s,src,Time()){assertEquals(original,only(s).getString("file_id"));assertEquals(1L,only(s).getLong("source_version"));assertEquals(1L,it.engine.rows().single().sourceVersion);assertEquals(1,rows("SELECT * FROM vault2_refs").size)}}
    }
    @Test fun N07SameHashDifferentDocumentsStaySeparateNativeRecordings()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000);put("two",modified=now-300000,name="SYNTHETIC_SECOND.wav")};val t=Time();session(s,src,t).use{it.cycle(2,2);t.elapsed+=10;it.cycle(2,2);assertEquals(2,s.snapshot().length());assertEquals(2,rows("SELECT * FROM vault2_refs").size)}}
    }
    @Test fun N08NativeReceiptAckLossThenRawMissingUsesZeroReopenAndDecrypt()=worker {
        val src=Source().apply{put(modified=now-300000)};val t=Time();var once=false
        store().use{s->s.initializeEmpty();session(s,src,t,FaultHook{if(it=="AFTER_NATIVE_RECEIPT_BEFORE_DISCOVERY_ACK"&&!once){once=true;throw Failure("SYNTHETIC_NATIVE_ACK_LOST")}}).use{n->n.cycle(1,1);t.elapsed+=10;n.cycle(1,1);assertTrue(once)}}
        src.bytes.clear();src.opens=0;src.stats=0;val decrypts=keys.decrypts
        store().use{s->session(s,src,t).use{assertEquals("RECEIPT_RECOVERED",it.engine.processNext());assertEquals(0,src.opens);assertEquals(0,src.stats);assertEquals(decrypts,keys.decrypts);assertEquals(1,s.snapshot().length());assertEquals(1,rows("SELECT * FROM receipts").size)}}
    }
    @Test fun N09RenameChangesNativeMetadataWithoutExtraContentVersion()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};val t=Time();session(s,src,t).use{n->n.cycle(1,1);t.elapsed+=10;n.cycle(1,1);src.put(modified=now-300000,name="SYNTHETIC_RENAMED_CANARY.wav");n.cycle(1,1);t.elapsed+=10;n.cycle(1,1);assertEquals("SYNTHETIC_RENAMED_CANARY.wav",only(s).getString("display_name"));assertEquals(1L,only(s).getLong("source_version"));assertEquals(1,rows("SELECT * FROM vault2_refs").size)}}
    }
    @Test fun N11LateGrantRevocationCannotCommitNativeReceipt()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000);onRead={grant=false}};ready(s,src,Time()){assertEquals(0,s.snapshot().length());assertEquals(0,rows("SELECT * FROM receipts").size)}}
    }
    @Test fun N12AllQueueMetadataAndReceiptPayloadAreAbsentFromDiskPlaintext()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){}}
        val canary="SYNTHETIC_FILENAME_CANARY".toByteArray();context.noBackupFilesDir.walkTopDown().filter{it.isFile}.forEach{f->val b=f.readBytes();assertFalse(f.name,(0..b.size-canary.size).any{i->canary.indices.all{j->b[i+j]==canary[j]}})}
        assertEquals(0,context.noBackupFilesDir.walkTopDown().count{it.name=="discovery.queue"})
    }
    @Test fun N13ReceiptRequestIsImmutable()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){}}
        try{rows("UPDATE discovery_intake_requests SET commit_payload='changed'");fail("immutable request") }catch(_:RuntimeException){}
        assertEquals(1,rows("SELECT * FROM receipts").size)
    }
    /** First run after consent creates the on-device vault key itself (no setup screen any more). */
    @Test fun N14VaultKeyIsCreatedOnFirstAcceptedRun()=worker {
        store().use{s->val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){assertNotNull(keys.key);assertEquals("READY",s.setupState())}}
    }
    @Test fun N15WithoutConsentNeverOpensPersonalData()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)}
            val general=object:SelectedRecordingSource by src {override val explicitlySyntheticScope=false;override val collectionAccepted=false}
            val t=Time();NativeDiscoverySession(s,general,selected(src),{JSONObject()},t,{now},false,config).use{n->
                n.cycle(1,1);t.elapsed+=20;val report=n.cycle(1,1);assertTrue(report.localOutcomes.contains("PERSONAL_DATA_COLLECTION_NOT_ACCEPTED"));assertEquals(0,src.opens);assertEquals(0,s.snapshot().length())}
        }
    }
    @Test fun N16IncompleteListingNeverRetiresUnseenLocalQueueRows()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};val t=Time();ready(s,src,t){n->src.docs.clear();src.complete=false;n.engine.scanPage();assertNotEquals(Status.MISSING_ALIAS,n.engine.rows().single().status);assertEquals(1,s.snapshot().length())}}
    }
    @Test fun N17SourceScopeChangeRejectsOldReceiptRecovery()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){n->
            val operations=rows("SELECT operation_id FROM discovery_intake_requests");val event=operations.single()["operation_id"].toString()
            try{s.recoverDiscoveryReceipt(Selection("other_owner",src.rootUri,1),event);fail("wrong owner scope")}catch(e:IllegalStateException){assertTrue(e.message.orEmpty().contains("SCOPE_MISMATCH"))}
        }}
    }
    @Test fun N19CancelledInventoryWritesNoProtectedDocumentCacheOrQueue()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)}
            session(s,src,Time()).use{n->src.onInventory={n.engine.cancelRun()};assertEquals(0,n.engine.scanPage());assertEquals(0,n.engine.rows().size);assertEquals(0,rows("SELECT name FROM sqlite_master WHERE type='table' AND name='discovery_documents'").size);assertEquals(0,src.opens)}
        }
    }
    @Test fun N20CancelledInventoryKeepsExistingStoredAliasAndHistory()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){n->
            src.docs.clear();src.opens=0;src.onInventory={n.engine.cancelRun()};assertEquals(0,n.engine.scanPage());assertEquals(Status.STORED,n.engine.rows().single().status);assertEquals(1L,only(s).getLong("source_version"));assertEquals(1,rows("SELECT * FROM discovery_documents").size);assertEquals(0,src.opens)
        }}
    }
    @Test fun N21PartialFirstCompleteFinalPageSurvivesProtectedQueueReopen()=worker {
        val src=Source().apply{put(modified=now-300000)};val t=Time()
        store().use{s->s.initializeEmpty();ready(s,src,t){}}
        src.docs.clear();src.put("a",modified=now-300000);src.put("z",modified=now-300000);var pages=0;src.onInventory={src.complete=++pages>1};src.opens=0
        val paged=Config(10,10000,1000,25000000,1)
        repeat(2){store().use{s->NativeDiscoverySession(s,src,selected(src),{JSONObject()},t,{now},true,paged).use{n->assertEquals(1,n.engine.scanPage())}}}
        store().use{s->session(s,src,t).use{n->assertEquals(Status.STORED,n.engine.rows().single{it.metadata.documentId=="one"}.status);assertEquals(1,s.snapshot().length());assertEquals(1,rows("SELECT * FROM vault2_refs").size);assertEquals(0,src.opens)}}
    }
    @Test fun N22ExplicitSameContentReceiptRecoveryHasNoRawStatOpenOrDecrypt()=worker {
        val src=Source().apply{put(modified=now-300000)};val t=Time();var armed=false;var lost=false
        store().use{s->s.initializeEmpty();session(s,src,t,FaultHook{if(armed&&it=="AFTER_NATIVE_RECEIPT_BEFORE_DISCOVERY_ACK"&&!lost){lost=true;throw Failure("SYNTHETIC_ACK_LOST")}}).use{n->
            n.cycle(1,1);t.elapsed+=10;n.cycle(1,1);armed=true;src.put(modified=now-300000,name="SYNTHETIC_RENAMED.wav");n.cycle(1,1);t.elapsed+=10;n.cycle(1,1);assertTrue(lost);assertNotNull(n.engine.rows().single().pending)
        }}
        src.bytes.clear();src.opens=0;src.stats=0;val decrypts=keys.decrypts
        store().use{s->session(s,src,t).use{n->assertEquals("LOCAL_RECEIPT_RECOVERED",n.engine.recoverLocalReceipt("one"));assertNull(n.engine.rows().single().pending);assertEquals(1L,n.engine.rows().single().sourceVersion);assertEquals(1,rows("SELECT * FROM vault2_refs").size);assertEquals(0,src.opens);assertEquals(0,src.stats);assertEquals(decrypts,keys.decrypts)}}
    }
    @Test fun N23RetryDoesNotStarveReadyNativeJobsAcrossProtectedStoreReopens()=worker {
        val src=Source().apply{put("a",modified=now-300000);put("b",modified=now-300000);put("c",modified=now-300000);failOpens.add("a")};val t=Time();val wide=Config(10,10000,1000,25000000,10)
        store().use{s->s.initializeEmpty();NativeDiscoverySession(s,src,selected(src),{JSONObject()},t,{now},true,wide).use{n->n.engine.scanPage();t.elapsed+=10;n.engine.scanPage();assertEquals("LOCAL_IO_FAILED",n.engine.processNext())}}
        repeat(2){store().use{s->session(s,src,t).use{n->assertEquals("STORED_LOCAL",n.engine.processNext())}}}
        store().use{s->session(s,src,t).use{n->assertEquals(2,s.snapshot().length());assertEquals(0L,n.engine.rows().single{it.metadata.documentId=="a"}.sourceVersion);assertTrue(n.engine.rows().filter{it.metadata.documentId!="a"}.all{it.sourceVersion==1L})}}
    }
    @Test fun N24LateCancellationRollsBackAcceptedCacheAndQueueTogether()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};lateinit var n:NativeDiscoverySession
            n=session(s,src,Time(),FaultHook{if(it=="BEFORE_SCAN_COMMIT")n.engine.cancelRun()})
            n.use{assertEquals(0,n.engine.scanPage());assertEquals(0,n.engine.rows().size);assertEquals(0,rows("SELECT name FROM sqlite_master WHERE type='table' AND name='discovery_documents'").size);assertEquals(0,s.snapshot().length());assertEquals(0,src.opens)}
        }
    }

    @Test fun N25PruneVaultDeletesOriginalOnlyAfterTranscriptWasDoneFor14Days()=worker {
        store().use{s->s.initializeEmpty();val src=Source().apply{put(modified=now-300000)};ready(s,src,Time()){
            val ref=rows("SELECT * FROM vault2_refs").single();val blob=File(context.noBackupFilesDir,"local-recordings-v2/audio-vault-v2/"+ref["ciphertext_sha256"]+".vault")
            assertTrue(blob.isFile);assertEquals(0,s.pruneVault(now+30*86400000L))
            kr.callbrief.pipeline.CallTranscriptionStore(context).close()
            AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use{p->p.requireProtectedPersistence()
                p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,updated_ms) VALUES(?,?,?,'DONE',?)",ref["recording_id"],ref["source_version"],ref["plaintext_sha256"],now)}
            assertEquals(0,s.pruneVault(now+13*86400000L));assertTrue(blob.isFile)
            assertEquals(1,s.pruneVault(now+15*86400000L));assertFalse(blob.exists())
            assertEquals(0,s.pruneVault(now+16*86400000L));assertEquals(1,rows("SELECT * FROM vault_pruned").size);assertEquals(1,rows("SELECT * FROM vault2_refs").size)
        }}
    }
}
