package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** v3.1 r12-3 (DESIGN_V4 2.3): the nightly state before the first pass ends, results that never apply, call-to-result
 * latency, and the transcription queue's entry time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R12ThreeTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val night=LocalDate.of(2026,10,28).atTime(22,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val hour=3_600_000L;private val day=86_400_000L
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2").forEach { r -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',10,0,0,1,1,1)",r,GeneralLocalStore.OWNER,r) }
            null
        } }
    }
    private fun store(at:Long)=CallRecordStore(context){ at }
    private fun call(id:String,at:Long)=CallTranscript(id,1,"a".repeat(64),at,60_000,"김과장",ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
    private fun analysis()=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray()))
    private class Inbox:CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
        override fun replace(path:String,json:String){ files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
        override fun get(path:String)=files[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
        override fun delete(path:String,sha:String){ files.remove(path) }
    }

    // ---- 2.3-1: 새벽 정리 분리 ----
    @Test fun theNightlyStateGoesUpWithTheLastFourteenDaysWhileTheFirstPassRuns() {
        val oldId=store(night).use { it.insert(call("rec1",night-20*day));CallRecordStore.recordId(call("rec1",0)) }
        val newId=store(night).use { it.insert(call("rec2",night-day));CallRecordStore.recordId(call("rec2",0)) }
        store(night).use { db -> db.applyAnalysis(oldId,analysis());db.applyAnalysis(newId,analysis()) }
        val inbox=Inbox()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,night-2*day)
        fun run(at:Long)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false }).runPending()
        run(night)
        val state=JSONObject(inbox.files.getValue(CallInbox.TIDY_STATE))
        assertFalse(state.getBoolean("firstPassDone"))
        assertEquals("only the last 14 days",listOf(CallInbox.recordKey(newId)),
            (0 until state.getJSONArray("records").length()).map { state.getJSONArray("records").getJSONObject(it).getString("key") })
        // The first pass ends; the tidy has not read everything yet: the one full read (every record since 10/1).
        s.put(PipelineSettingsKeys.FIRST_PASS_DONE,true)
        run(night+day)
        val full=JSONObject(inbox.files.getValue(CallInbox.TIDY_STATE))
        assertTrue(full.getBoolean("firstPassDone"));assertEquals(2,full.getJSONArray("records").length())
        // The ledger says the tidy read everything: back to the last 14 days.
        s.put(PipelineSettingsKeys.TIDIED_ONCE,true)
        assertFalse(tidyAll(s));assertTrue(tidyAll(JSONObject().put(PipelineSettingsKeys.FIRST_PASS_DONE,true)))
        assertFalse("never all before the first pass ends",tidyAll(JSONObject()))
    }

    // ---- 2.3-2: 결과 고착 해제 ----
    @Test fun aResultThatNeverAppliesGoesToBadThenOnceAgainThenFails() {
        val t0=LocalDate.of(2026,10,28).atTime(10,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val id=store(t0).use { it.insert(call("rec1",t0-hour));CallRecordStore.recordId(call("rec1",0)) }
        store(t0).use { it.markSent(id,t0-hour) }
        val key=CallInbox.recordKey(id)
        val inbox=Inbox()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        var applyCalls=0
        fun run(at:Long)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false },
            afterSend={},apply={ _,_,_ -> applyCalls++;throw IllegalStateException("APPLY_BROKEN") }).runPending()
        fun answer()=inbox.files.put(CallInbox.RESULTS+"/a.json",JSONObject().put("id",key).put("analysis",ClaudeCallAnalyzerTest.analysis()).toString())
        answer()
        run(t0);run(t0+hour);run(t0+2*hour)
        assertEquals(3,applyCalls)
        assertTrue("set aside after the third try",inbox.files.containsKey(CallInbox.BAD+"/a.json"));assertFalse(inbox.files.containsKey(CallInbox.RESULTS+"/a.json"))
        assertEquals(3L,s.getLong(PipelineSettingsKeys.RESULTS_APPLY_FAILED));assertEquals(1L,s.getLong(PipelineSettingsKeys.RESULTS_BAD))
        store(t0).use { db -> assertEquals(t0,db.receivedMs(id));assertEquals(CallRecordStore.TRANSCRIBED,db.record(id)!!.state) }
        // Within the 24 hours nothing goes again.
        run(t0+23*hour)
        assertTrue(inbox.files.keys.none { it.startsWith(CallInbox.INBOX+"/") })
        // 24 hours after the answer came: the mark is released and the record goes once more.
        run(t0+25*hour)
        assertEquals(1L,s.getLong(PipelineSettingsKeys.RESULTS_RECEIVED_NOT_APPLIED))
        assertEquals("sent again, once",1,inbox.files.keys.count { it.startsWith(CallInbox.INBOX+"/") })
        store(t0).use { db -> assertNull(db.receivedMs(id));assertEquals(t0+25*hour,db.sentMs(id)) }
        // The second answer fails the same way: FAILED with RESULT_NOT_APPLIED, not sent again by itself.
        answer()
        run(t0+26*hour);run(t0+27*hour);run(t0+28*hour)
        assertEquals(2L,s.getLong(PipelineSettingsKeys.RESULTS_BAD));assertEquals(6L,s.getLong(PipelineSettingsKeys.RESULTS_APPLY_FAILED))
        store(t0).use { db -> val r=db.record(id)!!
            assertEquals(CallRecordStore.FAILED,r.state);assertEquals(PipelineSettingsKeys.RESULT_NOT_APPLIED,r.error);assertNull(db.receivedMs(id)) }
        inbox.files.keys.filter { it.startsWith(CallInbox.INBOX+"/") }.toList().forEach { inbox.files.remove(it) }
        run(t0+60*hour)
        assertTrue("a failed record waits for [다시 정리하기]",inbox.files.keys.none { it.startsWith(CallInbox.INBOX+"/") })
        assertEquals(1L,s.getLong(PipelineSettingsKeys.RESULTS_RECEIVED_NOT_APPLIED))
        assertFalse("no record id or number in settings",s.getString(RESULT_BAD_RECORDS).contains("rec1"))
    }

    @Test fun aBrokenResultFileCountsAsBad() {
        val inbox=Inbox();inbox.files[CallInbox.RESULTS+"/x.json"]="{not json"
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ night },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false },afterSend={}).runPending()
        assertEquals(1L,s.getLong(PipelineSettingsKeys.RESULTS_BAD));assertTrue(inbox.files.containsKey(CallInbox.BAD+"/x.json"))
    }

    // ---- 2.3-3: 지연 실측 ----
    @Test fun latencyIsTheMedianAndNinetiethOfCallEndToResult() {
        assertEquals(JSONObject().put("n",0).put("atMs",5L).toString(),latency(emptyList(),5).toString())
        val o=latency(listOf(30L,10L,20L,40L,50L,60L,70L,80L,90L,100L),7)
        assertEquals(50L,o.getLong("p50Min"));assertEquals(90L,o.getLong("p90Min"));assertEquals(10,o.getInt("n"));assertEquals(7L,o.getLong("atMs"))
        assertEquals(15L,latency(listOf(15L),1).getLong("p90Min"))
        // The store: a call that started 2 hours before its result, 1 minute long → 119 minutes; one older than 7 days is left out.
        val t=night
        val a=store(t).use { it.insert(call("rec1",t-2*hour));CallRecordStore.recordId(call("rec1",0)) }
        val b=store(t).use { it.insert(call("rec2",t-9*day-hour));CallRecordStore.recordId(call("rec2",0)) }
        store(t).use { it.applyAnalysis(a,analysis()) }
        store(t-9*day).use { it.applyAnalysis(b,analysis()) }
        assertEquals(listOf(119L),store(t).use { it.resultLatencyMinutes(t-7*day) })
        // The runner keeps it in settings for the heartbeat.
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ t },Inbox(),save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false },afterSend={}).runPending()
        val l=JSONObject(s.getString(PipelineSettingsKeys.LATENCY))
        assertEquals(119L,l.getLong("p50Min"));assertEquals(119L,l.getLong("p90Min"));assertEquals(1,l.getInt("n"));assertEquals(t,l.getLong("atMs"))
        // The heartbeat puts it out as latency{p50Min,p90Min,n}.
        val hb=PhoneStatus.build(PhoneStatus.Input(t,"3.1",1039,0,null,0,0,"","","",0,v31=PhoneStatus.V31(latency=l)))
        assertEquals(119L,hb.getJSONObject("latency").getLong("p50Min"))
    }

    // ---- 2.3-4: 자막 대기열 진입 시각 ----
    @Test fun theQueueAgeIsWhenTheJobEnteredNotItsLastTry() {
        var clock=1_800_000_000_000L;var failed=0
        CallTranscriptionStore(context,{ clock },{ failed+=it }).use { s ->
            assertNull(s.oldestQueuedMs())
            val c=CallTranscriptionStore.Candidate(CallTranscriptionStore.Key("rec1",1,"a".repeat(64)),"x.m4a","content://x","audio/mp4",1L,false,true)
            val d=c.copy(key=c.key.copy(recordingId="rec2"))
            val entered=clock
            s.offer(c,true,"");s.offer(d,false,"TOO_SHORT")
            assertEquals(entered,s.oldestQueuedMs())
            clock+=hour;assertTrue(s.claim(c.key));s.finish(c.key,"QUEUED","DECODE")
            clock+=hour;s.offer(c,true,"")
            assertEquals("claim, finish and a new offer leave it",entered,s.oldestQueuedMs())
            // A skipped one that becomes eligible enters the queue now.
            clock+=hour;s.offer(d,true,"")
            assertEquals(entered,s.oldestQueuedMs())
            assertTrue(s.claim(c.key));s.finish(c.key,"FAILED","DECODE")
            assertEquals(1,failed);assertEquals(clock,s.oldestQueuedMs())
            s.finish(d.key,"DONE","")
            assertNull(s.oldestQueuedMs())
            // A RUNNING row left by a dead process at its last attempt fails: counted too.
            val e=c.copy(key=c.key.copy(recordingId="rec3"))
            s.offer(e,true,"");repeat(3) { assertTrue(s.claim(e.key));if(it<2) s.finish(e.key,"QUEUED","X") }
            clock+=CallTranscriptionStore.STALE_RUNNING_MS+1
            s.recoverRunning(CallTranscriptionRunner.MAX_ATTEMPTS)
            assertEquals(2,failed)
        }
    }
}
