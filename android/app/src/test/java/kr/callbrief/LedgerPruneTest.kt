package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.CoreException
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.co.callsummary.vault.*
import kr.co.ieum.discovery.DiscoveryTypes.*
import kr.callbrief.pipeline.DbStore
import kr.callbrief.pipeline.PhoneStatus
import kr.callbrief.pipeline.StatusLights
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

/** v3.8 r18 (DESIGN_V7 4.1): the ledger prune on the real protected SQL, host JCE vault and production discovery sink.
 * In-flight, waiting and recovering lines stay, lines finished less than 3 days ago stay, only finished lines nothing reads go,
 * a second run changes nothing, and receipt recovery and collection go on as before afterwards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LedgerPruneTest {
    private val now=Instant.parse("2026-11-13T02:00:00Z").toEpochMilli()
    private val day=86_400_000L
    private lateinit var context:Context
    private val io=Executors.newSingleThreadExecutor()
    private fun <T> worker(action:()->T):T=try{io.submit<T>{action()}.get(120,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    class Keys:VaultKeys {
        var key:SecretKey?=null
        override fun state(alias:String)=if(key==null)VaultKeys.State.MISSING else VaultKeys.State.AVAILABLE
        fun create(alias:String){check(key==null);key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()}
        override fun encrypt(alias:String)=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,checkNotNull(key))}
        override fun decrypt(alias:String,iv:ByteArray):Cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,checkNotNull(key),GCMParameterSpec(128,iv))}
    }
    private lateinit var keys:Keys
    private class Time:Clock {var elapsed=0L;val boot="host_boot_1";val wallBase=1_000_000_000L
        override fun now()=Moment(boot,elapsed,wallBase+elapsed)}
    private class Source:SelectedRecordingSource {
        override val explicitlySyntheticScope=true
        override val rootUri="content://ieum.synthetic/tree/chosen"
        val docs=linkedMapOf<String,SourceDocument>();val bytes=hashMapOf<String,ByteArray>();var opens=0
        override fun checkSelected(){}
        override fun inventory()=SourceInventory(docs.values.toList(),docs.keys.toSet(),true)
        override fun stat(document:SourceDocument):SourceDocument {if(!bytes.containsKey(document.documentId))throw FileNotFoundException("SYNTHETIC_MISSING");return docs.getValue(document.documentId)}
        override fun excluded(document:SourceDocument)=false
        override fun checkReadable(document:SourceDocument){}
        override fun open(document:SourceDocument):InputStream {opens++;return ByteArrayInputStream(bytes.getValue(document.documentId))}
        fun put(id:String="one",sample:Int=0,name:String="SYNTHETIC_LEDGER_$id.wav",samples:Int=4) {
            val b=ByteBuffer.allocate(44+2*samples).order(ByteOrder.LITTLE_ENDIAN).apply{put("RIFF".toByteArray());putInt(36+2*samples);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16);put("data".toByteArray());putInt(2*samples);repeat(samples){putShort(sample.toShort())}}.array()
            bytes[id]=b;docs[id]=SourceDocument(id,"$rootUri/document/$id",name,b.size.toLong(),"audio/wav",1_700_000_000_000L,false)
        }
    }
    /** Quiet 10 ms, lease 10 s, a stored file is read again after 1 s (the daily look of the phone, made short). */
    private val config=Config(10,10000,1000,25000000,4)
    @Before fun fresh(){context=RuntimeEnvironment.getApplication();IeumApplication.shutdown()
        listOf("protected-databases-v1","local-recordings-v2","protected-databases-prune-copy-v1").forEach { File(context.noBackupFilesDir,it).deleteRecursively() }
        ShadowTextKeys.KEYS.clear();ShadowTextKeys.CREATES.set(0);ShadowTextKeys.deny=false;ShadowTextAtomicFile.failNext=false;keys=Keys()}
    @After fun done(){io.shutdown();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS))}
    private fun store(fault:(String)->Unit={})=GeneralLocalStore(context,{c->
        val root=c.noBackupFilesDir.toPath().resolve("audio-vault-v2")
        GeneralLocalStore.VaultAccess(EncryptedAudioVault(root,keys,{p->FileChannel.open(p,StandardOpenOption.READ).use{it.force(true)}}),root,keys::create)
    },{now},fault)
    private fun selected(source:Source)=Selection(GeneralLocalStore.OWNER+"_synthetic",source.rootUri,1)
    private fun session(s:GeneralLocalStore,source:Source,t:Time,fault:FaultHook=FaultHook {})=NativeDiscoverySession(s,source,selected(source),{JSONObject()},t,{now},true,config,fault)
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun rows(sql:String,vararg args:Any?)=port().use{it.requireProtectedPersistence();it.query(sql,*args)}
    private fun count(table:String)=(rows("SELECT count(*) AS n FROM $table").single()["n"] as Number).toInt()
    private fun stored(n:NativeDiscoverySession,t:Time){n.cycle(1,4);t.elapsed+=10;n.cycle(1,4)}
    /** One look at the stored files again (their hash did not change): a same-content request, receipt and vault receipt each. */
    private fun recheck(n:NativeDiscoverySession,t:Time){t.elapsed+=1001;n.cycle(1,4)}
    private val sealed get()=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE)
    private val copy get()=LedgerPrune.copyFile(context)

    @Test fun L01OnlyFinishedSameContentLinesOlderThanThreeDaysGoAndASecondRunChangesNothing()=worker {
        val src=Source().apply{put()};val t=Time()
        store().use{s->s.initializeEmpty();session(s,src,t).use{n->stored(n,t);repeat(3){recheck(n,t)}
            assertEquals(4,src.opens)
            assertEquals(4,count("discovery_intake_requests"));assertEquals(4,count("receipts"));assertEquals(4,count("vault2_intake_receipts"));assertEquals(1,count("vault2_pending"))
            val versionOp=n.engine.rows().single().versions.single().operationId
            // Three days to the millisecond: nothing is old enough yet.
            val early=s.pruneLedger(now+LedgerPrune.KEEP_MS-1)
            assertNull(early.skipped);assertEquals(mapOf(LedgerPrune.REQUESTS to 0,LedgerPrune.PENDING to 0),early.deleted);assertFalse(copy.exists())
            val bytesBefore=sealed.readBytes()
            val r=s.pruneLedger(now+LedgerPrune.KEEP_MS)
            assertNull(r.skipped);assertEquals(mapOf(LedgerPrune.REQUESTS to 3,LedgerPrune.PENDING to 1),r.deleted)
            assertTrue(r.charsAfter in 0 until r.charsBefore)
            // The request a version points at stays, and every immutable receipt and reference stays.
            assertEquals(listOf(versionOp),rows("SELECT operation_id FROM discovery_intake_requests").map{it["operation_id"]})
            assertEquals(4,count("receipts"));assertEquals(4,count("vault2_intake_receipts"));assertEquals(1,count("vault2_refs"));assertEquals(0,count("vault2_pending"))
            assertEquals("the delete guard is back",1,rows("SELECT name FROM sqlite_master WHERE type='trigger' AND name='discovery_request_delete'").size)
            try{rows("DELETE FROM discovery_intake_requests");fail("guarded")}catch(_:RuntimeException){}
            // The file shrinks; the safety copy is the file as it was before the first delete.
            assertTrue(sealed.length()<bytesBefore.size);assertArrayEquals(bytesBefore,copy.readBytes());assertEquals(now+LedgerPrune.KEEP_MS,r.copyAtMs)
            val again=s.pruneLedger(now+LedgerPrune.KEEP_MS+day)
            assertEquals(mapOf(LedgerPrune.REQUESTS to 0,LedgerPrune.PENDING to 0),again.deleted);assertEquals(1,count("discovery_intake_requests"))
            // After the prune the stored version still recovers its receipt and the next look works as before.
            assertEquals("LOCAL_RECEIPT_ALREADY_COMMITTED",n.engine.recoverLocalReceipt("one"))
            assertNotNull(n.engine.localReceipt(versionOp))
            recheck(n,t);assertEquals(5,src.opens);assertEquals(Status.UNCHANGED,n.engine.rows().single().status);assertEquals(2,count("discovery_intake_requests"))
        }}
    }

    @Test fun L02ALookWhoseAcknowledgementWasLostStaysUntilItIsRecovered()=worker {
        val src=Source().apply{put()};val t=Time();var armed=false;var lost=false
        store().use{s->s.initializeEmpty()
            session(s,src,t,FaultHook{if(armed&&it=="AFTER_NATIVE_RECEIPT_BEFORE_DISCOVERY_ACK"&&!lost){lost=true;throw Failure("SYNTHETIC_ACK_LOST")}}).use{n->
                stored(n,t);recheck(n,t);armed=true;t.elapsed+=1001;n.cycle(1,1);assertTrue(lost)
                val open=checkNotNull(n.engine.rows().single().pending).operationId
                assertEquals(1,rows("SELECT * FROM receipts WHERE event_id=?",open).size)
                val r=s.pruneLedger(now+10*day)
                assertEquals("only the finished look goes",1,r.deleted[LedgerPrune.REQUESTS])
                assertEquals(1,rows("SELECT * FROM discovery_intake_requests WHERE operation_id=?",open).size)
                assertEquals("RECEIPT_RECOVERED",n.engine.processNext());assertNull(n.engine.rows().single().pending)
                assertEquals(1,s.pruneLedger(now+10*day).deleted[LedgerPrune.REQUESTS]);assertEquals(1,count("discovery_intake_requests"))
            }}
    }

    @Test fun L03AnOpenVaultIntakeStaysAndFinishesAfterThePrune()=worker {
        val src=Source().apply{put()};val t=Time();var armed=false;var failed=false
        store({p->if(armed&&p=="AFTER_REF_JOURNAL_BEFORE_ATTACH"&&!failed){failed=true;throw IllegalStateException("SYNTHETIC_STOP")}}).use{s->s.initializeEmpty()
            session(s,src,t).use{n->stored(n,t)
                src.put("two",sample=1);armed=true;n.cycle(1,1);t.elapsed+=10;n.cycle(1,1);assertTrue(failed)
                assertEquals(1,count("vault2_pending WHERE completed=0"));assertNotNull(n.engine.rows().single{it.metadata.documentId=="two"}.pending)
                val r=s.pruneLedger(now+10*day)
                assertEquals(1,r.deleted[LedgerPrune.PENDING]);assertEquals(1,count("vault2_pending WHERE completed=0"));assertEquals(0,count("vault2_pending WHERE completed=1"))
                assertEquals(2,count("discovery_intake_requests"))
                assertEquals("STORED_LOCAL",n.engine.processNext());assertEquals(2,count("vault2_refs"));assertEquals(0,count("vault2_pending WHERE completed=0"))
                assertEquals(1,s.pruneLedger(now+10*day).deleted[LedgerPrune.PENDING]);assertEquals(2,count("discovery_intake_requests"))
            }}
    }

    @Test fun L04NotDuringACallOrARunningTranscription()=worker {
        val src=Source().apply{put()};val t=Time()
        store().use{s->s.initializeEmpty();session(s,src,t).use{n->stored(n,t);recheck(n,t)
            val call=s.pruneLedger(now+5*day,LedgerPrune.SKIP_IN_CALL)
            assertEquals(LedgerPrune.SKIP_IN_CALL,call.skipped);assertTrue(call.deleted.isEmpty());assertEquals(2,count("discovery_intake_requests"))
            kr.callbrief.pipeline.CallTranscriptionStore(context).close()
            val ref=rows("SELECT * FROM vault2_refs").single()
            port().use{p->p.requireProtectedPersistence();p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,updated_ms) VALUES(?,?,?,'RUNNING',?)",ref["recording_id"],ref["source_version"],ref["plaintext_sha256"],now+5*day)}
            assertEquals(LedgerPrune.SKIP_TRANSCRIBING,s.pruneLedger(now+5*day).skipped);assertEquals(2,count("discovery_intake_requests"))
            assertNull("a RUNNING line left by a dead worker does not hold it for ever",s.pruneLedger(now+6*day).skipped)
            assertEquals(1,count("discovery_intake_requests"))
        }}
    }

    @Test fun L05TheCopyIsMadeOnceAndDeletedAfterSevenDays()=worker {
        val src=Source().apply{put()};val t=Time()
        store().use{s->s.initializeEmpty();session(s,src,t).use{n->stored(n,t);recheck(n,t)
            val first=s.pruneLedger(now+4*day);assertEquals(now+4*day,first.copyAtMs);assertTrue(copy.isFile)
            assertEquals(now+4*day,s.pruneLedger(now+10*day).copyAtMs);assertTrue(copy.isFile)
            val late=s.pruneLedger(now+11*day);assertNull(late.copyAtMs);assertFalse("7 days on, the copy with the deleted lines goes",copy.exists())
            recheck(n,t)
            val next=s.pruneLedger(now+12*day);assertEquals(1,next.deleted[LedgerPrune.REQUESTS]);assertFalse("only before the first prune",copy.exists())
        }}
        assertFalse(File(copy.path+".tmp").exists())
    }

    /** Thousands of finished lines in one run: one transaction, a time a phone can spend once a day. */
    @Test fun L06AThousandsOfLinesLedgerIsPrunedInOneRun()=worker {
        val src=Source().apply{put()};val t=Time();val n=2500
        store().use{s->s.initializeEmpty();session(s,src,t).use{stored(it,t)
            val ref=rows("SELECT * FROM vault2_refs").single()
            port().use{p->p.requireProtectedPersistence();p.transaction{
                val payload="A".repeat(1500);val meta="{\"display_name\":\""+"합성".repeat(150)+"\"}"
                for(i in 0 until n){val id="synthetic_look_%05d".format(i);val digest="%064x".format(i)
                    p.execute("INSERT INTO receipts VALUES(?,'OBSERVE',?,?,?,1,?,NULL,NULL,NULL,'SAME_CONTENT',?)",id,digest,ref["recording_id"],ref["source_version"],ref["plaintext_sha256"],now)
                    p.execute("INSERT INTO vault2_intake_receipts VALUES(?,?,?,?,?,?)",id,GeneralLocalStore.OWNER,digest,ref["recording_id"],ref["source_version"],ref["ciphertext_sha256"])
                    p.execute("INSERT INTO discovery_intake_requests VALUES(?,?,?,?,?,?,?)",id,GeneralLocalStore.OWNER,payload,meta,digest,src.rootUri,"one")}
                null}}
            val before=sealed.length();val t0=System.nanoTime()
            val r=s.pruneLedger(now+4*day)
            val ms=(System.nanoTime()-t0)/1_000_000L
            assertEquals(n,r.deleted[LedgerPrune.REQUESTS]);assertEquals(1,count("discovery_intake_requests"))
            assertTrue("the file got smaller: $before -> ${sealed.length()}",sealed.length()*2<before)
            assertTrue("one run took $ms ms",ms<60_000)
            File("build/reports/ledger-prune").apply{mkdirs()}.resolve("L06.json").writeText(JSONObject().put("lines",n).put("ms",ms).put("bytesBefore",before).put("bytesAfter",sealed.length()).toString())
        }}
    }

    /** S4: a write the store refuses for its size is counted, written to the settings, said by the light and the heartbeat. */
    @Test fun L07ACapacityRefusalIsCountedAndShown()=worker {
        val before=ProtectedDatabase.capacityFailures()
        AndroidSqlPort(context,"capacity-canary.db","CREATE TABLE t(x TEXT)".byteInputStream()).use{p->p.requireProtectedPersistence()
            try{p.execute("INSERT INTO t VALUES(?)","x".repeat(1_100_000));fail("refused")}catch(e:CoreException){assertEquals(ProtectedDatabase.CAPACITY_CODE,e.code)}}
        assertEquals(before+1,ProtectedDatabase.capacityFailures());assertTrue(ProtectedDatabase.lastCapacityFailureMs()>0)
        DbStore.flush(context)
        val s=SecureSettings(context).snapshot();val full=JSONObject(s.getString(DbStore.FULL))
        assertTrue(full.getLong("count")>=1);assertTrue(full.getLong("lastMs")>0)
        val at=System.currentTimeMillis()
        val ok=StatusLights.Input(now=at,folderConnected=true,modelState="READY",consent=true,lastTranscribedMs=at,lastAnalyzedMs=at,lastDiscoveryMs=at)
        assertTrue(StatusLights.lights(ok).single{it.tag=="status_analyze"}.ok)
        val shown=StatusLights.lights(ok.copy(dbFullMs=DbStore.lastFullMs(s))).single{it.tag=="status_analyze"}
        assertFalse(shown.ok);assertEquals(DbStore.FULL_WORDS,shown.words)
        assertTrue("a day later it is green again",StatusLights.lights(ok.copy(now=at+2*day,lastTranscribedMs=at+2*day,lastAnalyzedMs=at+2*day,lastDiscoveryMs=at+2*day,dbFullMs=DbStore.lastFullMs(s))).single{it.tag=="status_analyze"}.ok)
        val beat=PhoneStatus.build(PhoneStatus.Input(at,"3.8",1L,0,null,0L,0L,"READY","","",1,v37=PhoneStatus.V37(dbFull=s.opt(DbStore.FULL))))
        assertTrue(beat.getJSONObject("dbFull").getLong("count")>=1);assertTrue(beat.isNull("dbPrune"))
    }

    @Test fun L08TheHeartbeatBlockIsNumbersAndCodesOnly() {
        val r=LedgerPrune.Result(mapOf(LedgerPrune.REQUESTS to 3,LedgerPrune.PENDING to 1),900,400,null,12,now)
        val stored=DbStore.pruneJson(r,now).toString()
        val beat=PhoneStatus.build(PhoneStatus.Input(now,"3.8",1L,0,null,0L,0L,"READY","","",1,v37=PhoneStatus.V37(dbPrune=stored))).getJSONObject("dbPrune")
        assertEquals(3,beat.getJSONObject("deleted").getInt("discovery_intake_requests"));assertEquals(1,beat.getJSONObject("deleted").getInt("vault2_pending"))
        assertEquals(900L,beat.getLong("charsBefore"));assertEquals(400L,beat.getLong("charsAfter"));assertTrue(beat.isNull("skipped"));assertEquals(now,beat.getLong("copyAtMs"))
        assertEquals("2026-11-13",beat.getString("day"))
        val skipped=DbStore.pruneHeartbeat(DbStore.pruneJson(LedgerPrune.Result(emptyMap(),-1,-1,"IN_CALL 홍길동",3,null),now))!!
        assertEquals("IN_CALL",skipped.getString("skipped"));assertTrue(skipped.isNull("charsBefore"));assertTrue(skipped.isNull("copyAtMs"))
        assertEquals(0L,PhoneStatus.build(PhoneStatus.Input(now,"3.8",1L,0,null,0L,0L,"READY","","",1)).getJSONObject("dbFull").getLong("count"))
    }

    @Test fun L09TheRecheckOfAStoredFileIsMonthly() {
        assertEquals(30L*day,kr.co.ieum.discovery.DiscoveryTypes.Config.conservative().recheckMs)
    }
}
