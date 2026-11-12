package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
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

/** v3.7 r17-2 (DESIGN_V7 3.2 M2 to M4): the tidy state goes up after every applied result at night (21:00 to 04:58, else
 * every 30 minutes when it changed), says what of yesterday still waits (pending) and when each record was summed up (doneMs);
 * the report's base is "녹음이 있고 개인 번호가 아닌 어제 통화"; the morning notice counts the day's calls the evening did not
 * know; the evening's "충전하면 n개" counts today's only. Generated numbers and calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R17TwoTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val day=LocalDate.of(2026,11,9)
    private fun kst(h:Int,m:Int,d:LocalDate=day)=d.atTime(h,m).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val min=60_000L
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            for(r in listOf("rec1","rec2","rec3","rec4","rec5","rec6","rec7")) p.execute("""INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,
                mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',600000,0,0,1,1,1,?)""",
                r,GeneralLocalStore.OWNER,r,kst(12,0))
            null
        } }
    }
    private fun store(at:Long)=CallRecordStore(context){ at }
    private fun call(rec:String,at:Long,who:String="010-2000-30${rec.last()}0",ms:Long=60_000)=
        CallTranscript(rec,1,"a".repeat(64),at,ms,who,ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
    private fun insert(rec:String,at:Long,ms:Long=60_000)=store(at).use { it.insert(call(rec,at,ms=ms));CallRecordStore.recordId(call(rec,at,ms=ms)) }
    private fun analyze(id:String,at:Long)=store(at).use { it.applyAnalysis(id,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(org.json.JSONArray()))) }
    private fun analyzeWithTask(id:String,at:Long)=store(at).use { it.applyAnalysis(id,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(org.json.JSONArray()
        .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"))))) }
    /** Transcription jobs as CallTranscriptionStore keeps them (only the columns read here). */
    private fun job(rec:String,state:String,audioMs:Long?) = port().use { p ->
        p.execute("CREATE TABLE IF NOT EXISTS call_transcription_jobs(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,state TEXT NOT NULL,audio_ms INTEGER,reason TEXT,updated_ms INTEGER,PRIMARY KEY(recording_id,source_version,audio_sha))")
        p.execute("CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY,filename_time_candidate_ms INTEGER)")
        p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,audio_ms) VALUES(?,1,'s',?,?)",rec,state,audioMs) }

    // ---- M3: the report's base and what still waits ----
    @Test fun theDayCoverageCountsRecordedCallsNotPersonalOnes() {
        val done=insert("rec1",kst(10,0));analyze(done,kst(13,0))
        insert("rec2",kst(11,0),ms=90_000) // waiting for its analysis
        val personal=insert("rec3",kst(11,30));store(kst(13,0)).use { it.keepPersonal(personal) } // analysis found it personal
        val known=insert("rec4",kst(11,40));store(kst(13,0)).use { db -> db.rememberPersonal(db.record(known)!!.counterpartKey!!) } // a personal number
        insert("rec7",kst(10,0,day.minusDays(1))) // the day before
        job("rec5","QUEUED",120_000) // no text yet
        job("rec6","FAILED",null) // failed: in the base, not waiting
        store(kst(23,0)).use { it.insertMessage("sms:x:1",kst(9,0),"010-2000-3999","문자 내용") }
        val c=store(kst(23,0)).use { it.dayCoverage(day) }
        assertEquals("rec1, rec2, rec5, rec6",4,c.calls);assertEquals(1,c.analyzed)
        assertEquals("rec2 and rec5",2,c.pending);assertEquals("1.5 + 2 minutes",4,c.pendingMin);assertEquals(1,c.untranscribed)
        assertEquals("not yet analysed when the report was made",0,store(kst(23,0)).use { it.dayCoverage(day,kst(12,0)) }.analyzed)
        assertEquals(1,store(kst(23,0)).use { it.dayCoverage(day,kst(13,0)) }.analyzed)
        // The tidy state carries it for the night's "어제", and when each record was summed up.
        val state=store(kst(23,0)).use { it.tidyState(true,kst(23,0)) }
        assertEquals(2,state.getJSONObject("pending").getInt("yesterdayCalls"));assertEquals(4,state.getJSONObject("pending").getInt("yesterdayMin"))
        val rec=state.getJSONArray("records").getJSONObject(0);assertEquals(kst(13,0),rec.getLong("doneMs"))
        assertFalse(state.toString().contains("2000-30"));assertFalse(state.toString().contains("0102000"))
        // At 04:30 the night's "어제" is still the day before today.
        assertEquals(day,store(0).use { it.tidyPendingDay(kst(4,30,day.plusDays(1))) });assertEquals(day,store(0).use { it.tidyPendingDay(kst(21,0)) })
        assertEquals(day.minusDays(1),store(0).use { it.tidyPendingDay(kst(20,59)) })
    }

    // ---- M2: the night's state goes up after every applied result ----
    @Test fun theNightWindowIsByMinutes() {
        assertTrue(tidyWindow(kst(21,0)));assertTrue(tidyWindow(kst(0,0)));assertTrue(tidyWindow(kst(4,58)))
        assertFalse(tidyWindow(kst(20,59)));assertFalse(tidyWindow(kst(4,59)));assertFalse(tidyWindow(kst(5,0)));assertFalse(tidyWindow(kst(12,0)))
    }
    private class Inbox:CallInboxStore {
        val files=LinkedHashMap<String,String>();var states=0
        override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
        override fun replace(path:String,json:String){ files[path]=json;if(path==CallInbox.TIDY_STATE) states++ }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
        override fun get(path:String)=files[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    @Test fun anAppliedResultSendsTheStateAtOnce() {
        val first=insert("rec1",kst(10,0));analyzeWithTask(first,kst(13,0))
        val waiting=insert("rec2",kst(11,0))
        val inbox=Inbox()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,kst(0,0,day.minusDays(5)))
            .put(PipelineSettingsKeys.FIRST_PASS_DONE,true).put(PipelineSettingsKeys.TIDIED_ONCE,true)
        fun run(at:Long)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false },afterSend={}).runPending()
        val t=kst(3,40,day.plusDays(1))
        run(t);assertEquals(1,inbox.states)
        assertEquals(1,JSONObject(inbox.files.getValue(CallInbox.TIDY_STATE)).getJSONObject("pending").getInt("yesterdayCalls"))
        // 04:42: the 04:34 run's answer comes in; it goes up at once although the last state is two minutes old.
        run(t+2*min);assertEquals("nothing changed: not again",1,inbox.states)
        inbox.files[CallInbox.RESULTS+"/a.json"]=JSONObject().put("id",CallInbox.recordKey(waiting)).put("analysis",ClaudeCallAnalyzerTest.analysis()).toString()
        run(t+3*min);assertEquals("applied: sent at once",2,inbox.states)
        val state=JSONObject(inbox.files.getValue(CallInbox.TIDY_STATE))
        assertEquals(t+3*min,state.getLong("generatedMs"));assertEquals(0,state.getJSONObject("pending").getInt("yesterdayCalls"))
        assertEquals(2,state.getJSONArray("records").length())
        // Something else changes (a check item the user edits): not before 30 minutes, then yes.
        port().use { p -> p.execute("INSERT INTO workflow_item_history VALUES('h1',(SELECT id FROM workflow_items LIMIT 1),1,'{}')") }
        run(t+20*min);assertEquals(2,inbox.states)
        run(t+34*min);assertEquals(3,inbox.states)
        // 04:59 is past the window, whatever comes in.
        port().use { p -> p.execute("INSERT INTO workflow_item_history VALUES('h2',(SELECT id FROM workflow_items LIMIT 1),2,'{}')") }
        run(kst(4,59,day.plusDays(1)));run(kst(12,0,day.plusDays(1)));assertEquals(3,inbox.states)
    }

    // ---- M4: the morning notice counts the day's calls the evening did not know ----
    @Test fun aDaytimeCallTranscribedAfterTheEveningCountsAsLate() {
        val seen=insert("rec1",kst(10,0))
        val evening=kst(19,0)
        val saved=store(evening).use { CallDigestText.eveningWaiting(it.day(day),day,evening) }
        assertTrue(JSONObject(saved).getJSONArray("known").length()==1)
        // A 15:00 call whose text was still being made at 19:00 gets its record and result in the night.
        val daytime=insert("rec2",kst(15,0));analyze(daytime,kst(3,40,day.plusDays(1)))
        analyze(seen,kst(3,40,day.plusDays(1)))
        assertEquals("the waiting one and the daytime one",2,store(kst(6,0,day.plusDays(1))).use { CallDigestText.lateDone(it,saved) })
        // An evening saved by v3.6 (no "known"): counted by start time as before, so the daytime call is not.
        val old=JSONObject(saved).apply { remove("known") }.toString()
        assertEquals(1,store(kst(6,0,day.plusDays(1))).use { CallDigestText.lateDone(it,old) })
    }
    @Test fun theEveningChargerLineCountsTodayOnly() {
        assertEquals(2,CallDigestText.chargerToday(7,2));assertEquals(3,CallDigestText.chargerToday(3,5))
        assertEquals("the store could not be read: the ledger's count",4,CallDigestText.chargerToday(4,null))
        assertEquals(0,CallDigestText.chargerToday(5,0))
        job("rec5","QUEUED",120_000);job("rec6","QUEUED",60_000)
        insert("rec1",kst(10,0,day.minusDays(1))) // yesterday's
        assertEquals(2,store(kst(19,0)).use { it.dayCoverage(day).untranscribed })
    }
}
