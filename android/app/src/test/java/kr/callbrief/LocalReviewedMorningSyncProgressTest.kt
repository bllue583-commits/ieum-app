package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.AndroidSqlPort
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
import java.time.ZoneId

/** G02: actual canonical local source + sealed source/checkpoint/report stores. No collector,
 * fake COMPLETE sync, model, external service or actual user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalReviewedMorningSyncProgressTest:ProtectedTextHostTestBase() {
    private lateinit var scoped:Context
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private val owner="PUBLIC_G02_LOCAL_OWNER"
    private var active=true
    private val from=Instant.parse("2026-10-01T00:00:00Z")
    private val until=Instant.parse("2026-10-02T00:00:00Z")
    private val source get()=CanonicalReviewedDailySource(scoped){JSONObject().put("treeUri","content://generated-fixture/tree/test")}
    private val window get()=ReportWindow(java.time.LocalDate.parse("2026-10-02"),ZoneId.of("Asia/Seoul"),from,until)
    private fun request(id:String="PUBLIC_EMPTY_CHECKPOINT")=MorningSyncRequest(id,from,until,"","PUBLIC_LOCAL_ONLY",1)
    private fun count(table:String)=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->
        if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isEmpty())0 else (db.query("SELECT count(*) n FROM $table").single()["n"] as Number).toInt()}
    private fun proof(name:String,value:JSONObject){val dir=File("build/reports/g02-local-progress").apply{mkdirs()};File(dir,"$name.json").writeText(value.toString(2))}
    @Before fun prepare()=runBlocking(Dispatchers.IO){
        scoped=AccountStorageContext(RuntimeEnvironment.getApplication(),owner);keys=SyntheticPilotIntegrationTest.HostKeys()
        GeneralLocalStoreTest.host(scoped,keys).use{it.initializeEmpty()}
    }
    @Test fun knownLocalEmptyCreatesDurableCheckpointAndReopensSameReceipt()=runBlocking(Dispatchers.IO){
        val input=source.snapshot(window);assertEquals(ReportReadiness.NO_RECORDINGS,input.readiness);assertEquals(0,input.expectedCount);assertFalse(input.fullyReviewed)
        val result=LocalReviewedMorningSync(scoped,owner,{active},source).sync(request())
        proof("known-empty",JSONObject().put("readiness",input.readiness).put("state",result.state).put("durable",result.durable).put("checkpointCount",count("integrated_sync_checkpoints")))
        assertEquals(MorningSyncState.COMPLETE,result.state);assertTrue(result.durable);assertTrue(result.committedCursor.startsWith("local-reviewed-v1:"));assertEquals(1,count("integrated_sync_checkpoints"))
        assertEquals(result,LocalReviewedMorningSync(scoped,owner,{active},source).sync(request()))
        assertEquals(MorningCoverage.PARTIAL,CanonicalMorningSnapshotPort(source).snapshot(MorningReportWindow(java.time.LocalDate.parse("2026-10-02"),MorningReportKind.DAILY)).coverage)
    }
    @Test fun knownEmptyWindowDoesNotPinLaterReviewedWindowOrReopenedQueue()=runBlocking(Dispatchers.IO){
        val cipher=object:ReportPayloadCipher{override fun seal(text:String,aad:String,hasExistingPayload:Boolean)=text.toByteArray();override fun open(bytes:ByteArray,aad:String)=String(bytes)}
        var env=ReportEnvironment(Instant.parse("2026-10-03T00:00:00Z"),ZoneId.of("Asia/Seoul"),elapsed=1000,boot=1)
        var finalCursor=""
        DailyReportLedger(scoped,owner,cipher).use{ledger->
            val generation=ledger.setEnabled(true,env.now,env.zone).generation;val repo=ledger.morning();var token=checkNotNull(repo.lease(generation,env))
            try {
                val first=checkNotNull(repo.syncRequest("PUBLIC_EMPTY_SLOT","23H_PRIMARY",from,until,generation,token,env));val port=LocalReviewedMorningSync(scoped,owner,{active},source)
                val firstResult=port.sync(first);repo.syncComplete(first,firstResult,generation,token,env)
                repo.release(token);env=env.copy(now=env.now.plusSeconds(901),elapsed=env.elapsed+901000);token=checkNotNull(repo.lease(generation,env))
                // A new, explicitly reviewed local source occurs outside the old fixed empty window.
                val generated=GeneralLocalStoreTest.Source().apply{put(name="통화 녹음_공개합성_01000000001_20261002_110000.wav")}
                val id=GeneralLocalStoreTest.host(scoped,keys).use{s->s.importSelected(generated);val row=s.snapshot().getJSONObject(0);val id=row.getString("file_id");s.confirmTime(id,row,Instant.parse("2026-10-02T02:00:00Z").toEpochMilli());id}
                RecordingWorkflowStore(scoped).use{w->w.confirmWork(id,w.snapshot(id),"공개 합성 담당","공개 합성 회사","G02 합성 업무","");w.reviewOwnSummary(id,w.snapshot(id),"PUBLIC_G02_REVIEWED_NEW_WINDOW")}
                val next=checkNotNull(repo.syncRequest("PUBLIC_NEXT_SLOT","PRE_REPORT",until,env.now,generation,token,env))
                val nextInput=source.snapshot(ReportWindow(java.time.LocalDate.parse("2026-10-03"),env.zone,until,env.now));assertEquals(nextInput.unresolved.toString(),1,nextInput.sources.size)
                proof("queue-progress",JSONObject().put("firstState",firstResult.state).put("firstRequest",first.id).put("nextRequest",next.id).put("nextFrom",next.from).put("expectedNextFrom",until).put("newReviewedSources",nextInput.sources.size))
                assertNotEquals("the old empty request must release the durable queue",first.id,next.id);assertEquals(until,next.from)
                val completed=port.sync(next);assertEquals(MorningSyncState.COMPLETE,completed.state);repo.syncComplete(next,completed,generation,token,env);finalCursor=repo.ingestCursor();assertEquals(completed.committedCursor,finalCursor)
            } finally {repo.release(token)}
        }
        DailyReportLedger(scoped,owner,cipher).use{ledger->val repo=ledger.morning();assertEquals(finalCursor,repo.ingestCursor());val generation=ledger.config().generation;val token=checkNotNull(repo.lease(generation,env));try{assertEquals(env.now,checkNotNull(repo.syncRequest("PUBLIC_RESUMED_SLOT","LATE_RESULT",env.now,env.now.plusSeconds(60),generation,token,env)).from)}finally{repo.release(token)}}
    }
    @Test fun missingUnknownFailedAndPendingAreNotKnownEmpty()=runBlocking(Dispatchers.IO){
        val missing=AccountStorageContext(RuntimeEnvironment.getApplication(),"PUBLIC_G02_MISSING")
        assertEquals(MorningSyncState.UNAVAILABLE,LocalReviewedMorningSync(missing,owner,{true},source).sync(request()).state)
        val unavailableInventory=CanonicalReviewedDailySource(scoped){JSONObject().put("pendingRevocation",true)}
        assertEquals(ReportReadiness.WAITING_REVIEW,unavailableInventory.snapshot(window).readiness)
        assertEquals(MorningSyncState.ACCEPTED,LocalReviewedMorningSync(scoped,owner,{active},unavailableInventory).sync(request("PUBLIC_POLICY_INVENTORY_UNKNOWN")).state)
        for(state in listOf(ReportReadiness.SOURCE_FAILURE,ReportReadiness.WAITING_ANALYSIS,ReportReadiness.WAITING_REVIEW,ReportReadiness.REVIEWED)) {
            val input=DailyReportInput(state,emptyList(),0,0,"a".repeat(64))
            val result=LocalReviewedMorningSync(scoped,owner,{active},ReviewedDailySource{input}).sync(request("PUBLIC_${state.name}"))
            assertNotEquals(state.name,MorningSyncState.COMPLETE,result.state);assertFalse(result.durable);assertEquals("",result.committedCursor)
        }
        assertEquals(0,count("integrated_sync_checkpoints"))
    }
    @Test fun knownEmptyCheckpointFailureDoesNotReturnDurabilityOrLeaveRow()=runBlocking(Dispatchers.IO){
        var failed=false
        try{LocalReviewedMorningSync(scoped,owner,{active},source){error("PUBLIC_G02_COMMIT_FAILURE")}.sync(request())}catch(e:IllegalStateException){failed=e.message=="PUBLIC_G02_COMMIT_FAILURE"}
        assertTrue("known empty must reach the protected checkpoint transaction",failed);assertEquals(0,count("integrated_sync_checkpoints"))
    }
}
