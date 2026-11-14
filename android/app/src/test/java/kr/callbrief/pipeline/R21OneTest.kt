package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.report.KakaoReport
import kr.callbrief.pipeline.report.ReportCoverage
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** v4.0 r21-1 (DESIGN_V8 6 v4.1): a call whose recording could not be turned into text is no silent gap. The day's coverage
 * counts it apart (failed: in yesterdayCalls, never pending nor analysed), the tidy state carries it (pending.yesterdayFailed),
 * the morning report says "글자로 못 바꾼 통화 n건" (nothing when there is none), the call list says so instead of "녹음 없음",
 * and a failure with a code that comes back the same is given another try once in all, not once per build. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R21OneTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val day=LocalDate.of(2026,11,14)
    private fun kst(h:Int,m:Int,d:LocalDate=day)=d.atTime(h,m).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1" to kst(10,0),"rec2" to kst(11,0),"rec3" to kst(12,0),"rec4" to kst(13,0),"rec5" to kst(14,0),"rec6" to kst(9,0,day.minusDays(1))).forEach { (r,at) ->
                p.execute("""INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,
                mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',600000,0,0,1,1,1,?)""",
                r,GeneralLocalStore.OWNER,r,at) }
            null
        } }
    }
    @After fun reset() { CallTranscriptionStore.buildCode={ kr.callbrief.BuildConfig.VERSION_CODE } }
    private fun store(at:Long)=CallRecordStore(context){ at }
    private fun job(rec:String,state:String,sha:String="s") = port().use { p ->
        p.execute("CREATE TABLE IF NOT EXISTS call_transcription_jobs(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,state TEXT NOT NULL,audio_ms INTEGER,reason TEXT,updated_ms INTEGER,PRIMARY KEY(recording_id,source_version,audio_sha))")
        p.execute("CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY,filename_time_candidate_ms INTEGER)")
        p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,audio_ms) VALUES(?,1,?,?,60000)",rec,sha,state) }

    @Test fun aCallWhoseTextCouldNotBeMadeIsCountedApart() {
        job("rec1","FAILED") // failed for good
        job("rec2","FAILED");job("rec2","QUEUED","t") // another version waits: pending, not failed
        job("rec3","QUEUED") // waits
        job("rec4","FAILED");job("rec4","DONE","t") // a version went through (a personal call: its record is gone): not failed
        job("rec6","FAILED") // the day before
        val c=store(kst(23,0)).use { it.dayCoverage(day) }
        assertEquals("rec1 to rec4",4,c.calls);assertEquals("rec2, rec3",2,c.pending);assertEquals("rec1",1,c.failed);assertEquals(0,c.analyzed)
        assertEquals(1,store(kst(23,0)).use { it.dayCoverage(day.minusDays(1)) }.failed)
        // The night's state carries it for the report's head.failedCalls.
        val pending=store(kst(23,0)).use { it.tidyState(true,kst(23,0)) }.getJSONObject("pending")
        assertEquals(2,pending.getInt("yesterdayCalls"));assertEquals(1,pending.getInt("yesterdayFailed"))
        // The heartbeat's report.coverage and report.recent carry it too.
        val o=JSONObject();ReportCoverage.note(o,day.plusDays(1),c,0,"routine",1L)
        val snap=ReportCoverage.snapshot(o)!!;assertEquals(1,snap.getInt("failed"))
        assertEquals("a count from before r21-1 reads 0",0,ReportCoverage.snapshot(JSONObject().put(PipelineSettingsKeys.REPORT_COVERAGE,
            JSONObject().put("day","2026-11-14").put("yesterdayCalls",3).toString()))!!.getInt("failed"))
        val recent=Telemetry.coverageRecent(Telemetry.coverageDays(null,snap));assertEquals(1,recent.getJSONObject(0).getInt("failed"))
        assertEquals("an old history entry of three reads 0",0,Telemetry.coverageRecent(JSONObject().put("2026-11-13",JSONArray().put(3).put(2).put(1))).getJSONObject(0).getInt("failed"))
        // The call list: the call-log line of that call says its text could not be made, not "녹음 없음".
        val starts=store(kst(23,0)).use { it.failedTextStarts(kst(0,0),kst(0,0,day.plusDays(1))) }
        assertEquals(listOf(kst(10,0)),starts)
        val e=LoggedCall(1,"01000000000","x",kst(10,0)-30_000,120_000,android.provider.CallLog.Calls.INCOMING_TYPE,false)
        assertEquals(CallsScreen.TEXT_FAILED,CallsScreen.stateWords(e,kst(23,0),emptyList(),starts))
        assertEquals(CallsScreen.NO_RECORDING,CallsScreen.stateWords(e,kst(23,0),emptyList(),emptyList()))
    }

    private fun report(failed:Int?,today:Int=3)=JSONObject().put("day","2026-11-15").put("generatedMs",1L).put("tidyRev",1)
        .put("head",JSONObject().put("calls",5).put("newChecks",1).put("todayChecks",today).put("issues",0).put("pendingCalls",0).apply { failed?.let { put("failedCalls",it) } })
        .put("yesterday",JSONArray()).put("today",JSONArray().apply { repeat(today) { put(JSONObject().put("id","t$it").put("what","견적서 보내기 $it").put("who","").put("since","어제 요청").put("status","").put("check",false)) } })
        .put("inProgress",JSONArray()).put("check",JSONArray())

    @Test fun theMorningReportSaysHowManyCouldNotBeMade() {
        val with=KakaoReport.compose(report(2),picture=false)
        assertEquals("11/15(일) 챙길 일 3개 · 글자로 못 바꾼 통화 2건",with.list.first().header)
        assertTrue(with.chat.first().text,with.chat.first().text.lines()[1].endsWith("어제 통화 5건을 정리했어요. 글자로 못 바꾼 통화가 2건 있어요"))
        // None: nothing more than before (a v10 report has no key at all).
        for(r in listOf(report(0),report(null))) { val c=KakaoReport.compose(r,picture=false)
            assertEquals("11/15(일) 챙길 일 3개",c.list.first().header);assertFalse(c.chat.first().text.contains("글자로 못"))
            assertEquals("the list itself is the same",KakaoReport.compose(report(2),picture=false).list.map { it.items },c.list.map { it.items }) }
        // The phone's own 06:00 report and a report of one to-do (no list: the texts) say it too.
        assertTrue(KakaoReport.compose(report(1),phoneFallback=true).todayText!!.text.contains("어제 글자로 못 바꾼 통화가 1건 있어요"))
        val one=KakaoReport.compose(report(1,today=1),picture=false);assertTrue(one.list.isEmpty());assertTrue(one.chat.first().text.contains("글자로 못 바꾼 통화가 1건 있어요"))
    }

    @Test fun aFailureThatComesBackTheSameIsTriedOnceInAll() {
        val app=RuntimeEnvironment.getApplication()
        fun candidate(id:String)=CallTranscriptionStore.Candidate(CallTranscriptionStore.Key(id,1,"a".repeat(64)),"x.m4a","content://x","audio/mp4",1L,false,true)
        fun state(s:CallTranscriptionStore,id:String)=s.rows().single { it.first.recordingId==id }.second
        var build=1061;CallTranscriptionStore.buildCode={ build }
        CallTranscriptionStore(app,{ 1_800_000_000_000L }).use { s ->
            val marked=candidate("marked");val codec=candidate("codec");val other=candidate("other")
            listOf(marked,codec,other).forEach { s.offer(it,true,"") }
            assertTrue(s.claim(marked.key));s.failPermanently(marked.key,"CodecException")
            // A codec error the codec called transient, three times: FAILED with the code but not marked.
            repeat(3) { assertTrue(s.claim(codec.key));s.finish(codec.key,if(it<2) "QUEUED" else "FAILED","CodecException") }
            repeat(3) { assertTrue(s.claim(other.key));s.finish(other.key,if(it<2) "QUEUED" else "FAILED","STALLED") }
            for(b in 1062..1065) { build=b
                s.requeueFailed()
                listOf(codec,other).forEach { c -> if(state(s,c.key.recordingId)=="QUEUED") repeat(3) { assertTrue(s.claim(c.key));s.finish(c.key,if(it<2) "QUEUED" else "FAILED",if(c==codec) "CodecException" else "STALLED") } }
            }
            val revived=port().use { p -> p.query("SELECT recording_id,coalesce(requeue_count,0) AS n FROM call_transcription_jobs").associate { it["recording_id"].toString() to (it["n"] as Number).toInt() } }
            assertEquals("marked permanent: never",0,revived["marked"]);assertEquals("a permanent code: once in all",1,revived["codec"])
            assertEquals("any other failure: as before, twice in all",CallTranscriptionStore.MAX_REQUEUES,revived["other"])
        }
    }
}
