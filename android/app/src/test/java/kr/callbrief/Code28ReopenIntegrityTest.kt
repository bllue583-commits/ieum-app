package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class Code28ReopenIntegrityTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private var now=Instant.parse("2026-10-14T02:00:00Z").toEpochMilli()
    @Before fun setup(){context=RuntimeEnvironment.getApplication()}
    private fun <T> io(block:suspend ()->T)=runBlocking(Dispatchers.IO){withTimeout(20_000){block()}}
    private inner class Channel(name:String):SyntheticTextMcpChannel {
        val scope=SyntheticTextLocalFixtureChannel.LOCAL_SCOPE.copy(session="CODE28_$name")
        private val delegate=SyntheticTextLocalFixtureChannel(context,{now},scope)
        var calls=0
        override val origin=SyntheticTextContract.LOCAL_ORIGIN
        override fun binding()=scope
        override suspend fun exchange(request:ByteArray):ByteArray{calls++;return delegate.exchange(request)}
    }
    private fun run(c:Channel)=SyntheticTextForegroundCoordinator(context,McpSyntheticTextGenerationPort(c),{now})
    private suspend fun ready(c:Channel)=run(c).startExplicit().also{assertEquals("RESULT_RECEIVED",it.state);assertNotNull(it.view?.result)}
    private fun store(c:Channel,block:(SyntheticTextProofStore)->Unit)=SyntheticTextProofStore(context,c.scope,c::binding).use(block)
    private fun mutate(proof:String,block:(JSONObject)->Unit)=TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db ->
        val b=db.rawQuery("SELECT body FROM sg_proofs WHERE proof_id=?",arrayOf(proof)).use{it.moveToFirst();JSONObject(it.getString(0))}
        block(b);db.execSQL("UPDATE sg_proofs SET body=? WHERE proof_id=?",arrayOf(b.toString(),proof))
    }
    private fun receipt(proof:String,kind:String,block:(Map<String,Any?>)->Map<String,Any?>)=TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use{db ->
        val old=db.rawQuery("SELECT payload FROM sg_receipts WHERE proof_id=? AND kind=?",arrayOf(proof,kind)).use{it.moveToFirst();SyntheticTextContract.obj(StrictJson.parse(it.getString(0).toByteArray()))}
        val payload=StrictJson.stringify(block(old));db.execSQL("DROP TRIGGER IF EXISTS sg_receipt_no_update")
        db.execSQL("UPDATE sg_receipts SET payload=?,digest=? WHERE proof_id=? AND kind=?",arrayOf(payload,SyntheticTextContract.hash(payload.toByteArray()),proof,kind))
    }
    private suspend fun heldWithoutMutation(c:Channel) {
        val f=ProtectedDatabase.file(context,SyntheticTextProofStore.DATABASE);val before=f.readBytes();val calls=c.calls
        val result=run(c).load();assertEquals("STORE_REVIEW",result.state);assertNull(result.view);assertFalse(result.nativeConnected);assertFalse(result.canonicalWriteAllowed)
        assertEquals(calls,c.calls);assertArrayEquals("Integrity hold changed the original sealed store",before,f.readBytes())
    }
    private fun evidence(name:String,extra:Map<String,Any?> = emptyMap()) {
        File("build/reports/code28-reopen").apply{mkdirs()}.resolve("$name.json").writeText(JSONObject(extra).put("id",name).put("PASS",true).put("scope","LOCAL_PROTECTED_DATABASE_CORRUPTION_INJECTION_NATIVE_SQLITE_JCE_ONLY").put("actualNativeSite",false).put("businessApplied",false).toString(2))
    }
    @Test fun R01ReceivedLabelRequiresTheCompleteResultChain()=io {
        val c=Channel("STATE_ONLY");var v:SyntheticTextProofStore.View?=null;store(c){v=it.explicitStart()}
        mutate(v!!.proof){it.put("state","RESULT_RECEIVED")};heldWithoutMutation(c)
        val second=Channel("MISSING_RESULT");val done=ready(second);mutate(done.view!!.proof){it.remove("result")};heldWithoutMutation(second)
        evidence("R01")
    }
    @Test fun R02CachedIsoTimeAndExact24HourPeriodAreRevalidated()=io {
        for(mode in listOf("ISO","TTL","NEGATIVE","UNSAFE")) {
            val c=Channel("TIME_$mode");val done=ready(c);mutate(done.view!!.proof){b -> val start=b.getJSONObject("start")
                when(mode){"ISO"->start.put("created","not-an-instant");"TTL"->start.put("created",Instant.ofEpochMilli(now+1).toString());"NEGATIVE"->start.put("created","1969-12-31T23:59:59Z");else->start.put("expires",9_007_199_254_740_992L)}
            };heldWithoutMutation(c)
        };evidence("R02")
    }
    @Test fun R03CoherentlyShiftedCacheAndReadbackStillNeedOriginalStart()=io {
        val c=Channel("SHIFTED_TIME");val done=ready(c);val v=done.view!!
        mutate(v.proof){it.getJSONObject("start").put("created",Instant.ofEpochMilli(now+1).toString()).put("expires",v.start!!.expiresAt+1)}
        receipt(v.proof,"RESULT_READBACK"){it+mapOf("expires_at" to v.start!!.expiresAt+1)}
        heldWithoutMutation(c);evidence("R03",mapOf("coherentCorruptionStillHeld" to true))
    }
    @Test fun R04MissingImmutableStartCannotReopenReceived()=io {
        val c=Channel("NO_START_RECEIPT");val done=ready(c)
        TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use{db ->db.execSQL("DROP TRIGGER sg_receipt_no_delete");db.execSQL("DELETE FROM sg_receipts WHERE proof_id=? AND kind='START'",arrayOf(done.view!!.proof))}
        heldWithoutMutation(c);evidence("R04")
    }
    @Test fun R05StartReceiptDigestAndExactWireShapeRemainRequired()=io {
        val c=Channel("START_DIGEST");val done=ready(c)
        TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use{db ->db.execSQL("DROP TRIGGER IF EXISTS sg_receipt_no_update");db.execSQL("UPDATE sg_receipts SET digest=? WHERE proof_id=? AND kind='START'",arrayOf("0".repeat(64),done.view!!.proof))}
        heldWithoutMutation(c)
        val second=Channel("START_SHAPE");val two=ready(second);receipt(two.view!!.proof,"START"){it+mapOf("unexpected" to true)};heldWithoutMutation(second);evidence("R05")
    }
    @Test fun R06FailedStartReceiptAppendRollsBackAdmissionWithoutNewIds()=io {
        val c=Channel("ATOMIC_START");store(c){s ->
            val v=s.explicitStart();val ticket=s.claim(v.proof)
            TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use{it.execSQL("CREATE TRIGGER c28_injected_start_failure BEFORE INSERT ON sg_receipts WHEN NEW.kind='START' BEGIN SELECT RAISE(ABORT,'INJECTED_START_FAILURE'); END")}
            val wire=mapOf("job_id" to SyntheticTextContract.JOB,"generation_id" to UUID.randomUUID().toString(),"proof_request_id" to v.proof,"revision" to 0L,"status" to "queued","created_at" to DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(now)),"expires_at" to now+SyntheticTextContract.TTL,"duplicate" to false,"demo" to true)
            val before=ProtectedDatabase.file(context,SyntheticTextProofStore.DATABASE).readBytes()
            try{s.started(ticket,SyntheticTextContract.start(wire,v.proof),wire);fail("Injected append failure was accepted")}catch(_:RuntimeException){}
            assertArrayEquals(before,ProtectedDatabase.file(context,SyntheticTextProofStore.DATABASE).readBytes());assertNull(s.get(v.proof).start);assertEquals(0,s.receiptCount());s.finish(ticket,"UNKNOWN")
            assertEquals(v.proof,s.current()!!.proof);assertEquals(v.suffix,s.current()!!.suffix)
        };evidence("R06")
    }
    @Test fun R07UnknownStateAndPartialExpectedIdentityAreHeld()=io {
        for(mode in listOf("STATE","ID","HASH","TYPE")) {
            val c=Channel("CACHE_$mode");val done=ready(c);mutate(done.view!!.proof){when(mode){"STATE"->it.put("state","RECEIVED_SUCCESS");"ID"->it.remove("expected_id");"HASH"->it.remove("expected_hash");else->it.put("expected_hash",42)}};heldWithoutMutation(c)
        };evidence("R07")
    }
    @Test fun R08QueuedToReadyCacheAdvanceRequiresImmutableReadback()=io {
        val c=Channel("NO_PROMOTION_PROOF");store(c){s ->
            val v=s.explicitStart();val ticket=s.claim(v.proof)
            val wire=mapOf("job_id" to SyntheticTextContract.JOB,"generation_id" to UUID.randomUUID().toString(),"proof_request_id" to v.proof,"revision" to 0L,"status" to "queued","created_at" to DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(now)),"expires_at" to now+SyntheticTextContract.TTL,"duplicate" to false,"demo" to true)
            s.started(ticket,SyntheticTextContract.start(wire,v.proof),wire);s.finish(ticket,"UNKNOWN");mutate(v.proof){it.getJSONObject("start").put("revision",1).put("status","result_ready")}
        };heldWithoutMutation(c);evidence("R08")
    }
    @Test fun R09ValidRetainedResultsRemainReadableInUnknownAndExpired()=io {
        for(state in listOf("UNKNOWN","EXPIRED")) {
            val c=Channel("RETAIN_$state");val done=ready(c);val v=done.view!!;mutate(v.proof){it.put("state",state)}
            val before=ProtectedDatabase.file(context,SyntheticTextProofStore.DATABASE).readBytes();val calls=c.calls;val loaded=run(c).load()
            assertEquals(state,loaded.state);assertEquals(v.proof,loaded.view!!.proof);assertEquals(v.expectedId,loaded.view.expectedId);assertEquals(v.result,loaded.view.result);assertEquals(calls,c.calls);assertArrayEquals(before,ProtectedDatabase.file(context,SyntheticTextProofStore.DATABASE).readBytes())
        };evidence("R09",mapOf("originalPayloadRetained" to true))
    }
    @Test fun R10RestoredReceiptRequiresExactShapeUuidAndRevisionStatusPair()=io {
        for(mode in listOf("EXTRA","MISSING","UUID","PAIR")) {
            val c=Channel("SHAPE_$mode");val done=ready(c);mutate(done.view!!.proof){b ->val s=b.getJSONObject("start");when(mode){"EXTRA"->s.put("extra",false);"MISSING"->s.remove("created");"UUID"->s.put("generation","not-a-uuid");"PAIR"->s.put("status","queued");else->s.put("revision",1.0)}};heldWithoutMutation(c)
        }
        val c=Channel("SHAPE_FRACTION");val done=ready(c)
        TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use { db ->
            val old=db.rawQuery("SELECT body FROM sg_proofs WHERE proof_id=?",arrayOf(done.view!!.proof)).use { it.moveToFirst();it.getString(0) }
            val changed=old.replace("\"revision\":1,","\"revision\":1.0,").replace("\"revision\":1}","\"revision\":1.0}")
            check(changed!=old);db.execSQL("UPDATE sg_proofs SET body=? WHERE proof_id=?",arrayOf(changed,done.view.proof))
        };heldWithoutMutation(c);evidence("R10")
    }
    @Test fun R11DuplicateReadyAdmissionDoesNotClaimPayloadReceiptSuccess()=io {
        val c=Channel("READY_DUPLICATE");store(c){s ->
            val v=s.explicitStart();val ticket=s.claim(v.proof)
            val wire=mapOf("job_id" to SyntheticTextContract.JOB,"generation_id" to UUID.randomUUID().toString(),"proof_request_id" to v.proof,"revision" to 1L,"status" to "result_ready","created_at" to DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(now)),"expires_at" to now+SyntheticTextContract.TTL,"duplicate" to true,"demo" to true)
            s.started(ticket,SyntheticTextContract.start(wire,v.proof),wire);s.finish(ticket)
        };val loaded=run(c).load();assertEquals("ADMITTED",loaded.state);assertNull(loaded.view!!.result);assertFalse(loaded.nativeConnected);evidence("R11")
    }
    @Test fun R12ConflictingAdditionalImmutableReceiptsCannotBeIgnored()=io {
        for(kind in listOf("START","RESULT_READBACK")) {
            val c=Channel("CONFLICTING_$kind");val done=ready(c);val v=done.view!!
            TextStoreTestSupport.open(context,SyntheticTextProofStore.DATABASE).use{db ->
                val old=db.rawQuery("SELECT payload FROM sg_receipts WHERE proof_id=? AND kind=?",arrayOf(v.proof,kind)).use{it.moveToFirst();SyntheticTextContract.obj(StrictJson.parse(it.getString(0).toByteArray()))}
                val other=UUID.randomUUID().toString();val value=old+mapOf("generation_id" to other)+if(kind=="RESULT_READBACK")mapOf("result_id" to GenerationResultId.make(other,v.suffix)) else emptyMap()
                val payload=StrictJson.stringify(value);db.execSQL("INSERT INTO sg_receipts VALUES(?,?,?,?,?,?,?)",arrayOf(UUID.randomUUID().toString(),c.scope.key,v.proof,kind,payload,SyntheticTextContract.hash(payload.toByteArray()),now))
            };heldWithoutMutation(c)
        };evidence("R12")
    }
}
