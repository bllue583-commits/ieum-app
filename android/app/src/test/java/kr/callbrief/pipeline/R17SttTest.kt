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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

/** v3.6.1 r17-361-stt (DESIGN_V7 2): T1 the speed per encoder window (a short tail piece is no "slow" piece, one slow piece
 * costs one rest), T3a a battery run goes on past its own start mark, D1 the transcript trim on its own (7 days, 3 from 60%).
 * Generated recordings and calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R17SttTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L;private val hour=60*minute;private val day=24*hour
    /** 2026-11-10 03:00 KST (a night on the charger). */
    private val base=LocalDate.of(2026,11,10).atTime(3,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private var clock=base

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        CallTranscriptionStore(context){ clock }.close()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER);null } }
    }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun snap(pct:Int=60,plugged:String="ac",status:String="charging",headroom:Float?=0.55f,temp:Int?=300)=
        BatterySnapshot(pct,plugged,status,false,0,headroom,temp,false)

    // ---- T1: speed per encoder window ----
    @Test fun aSevenSecondTailPieceIsNoSlowPiece() {
        assertEquals(1L,PowerPolicy.windows(7_000));assertEquals(4L,PowerPolicy.windows(120_000));assertEquals(5L,PowerPolicy.windows(120_001))
        // Cold 2-minute pieces at 240 s, then the 7-second tail of the same recording in 50 s (one whole window encoded).
        var o=PowerStats.notePiece(null,240_000,120_000,6,cold=true,now=base)
        o=PowerStats.notePiece(o,250_000,120_000,6,cold=false,now=base+5*minute)
        o=PowerStats.notePiece(o,50_000,7_000,6,cold=false,now=base+6*minute)
        assertEquals(50_000.0/30_000,o.getJSONObject("chunk").getDouble("lastRate"),0.001)
        assertFalse("the old rate (7.1 per audio ms) was 3.5x the cold one",PowerStats.slow(o,base+7*minute))
        assertNull(PowerPolicy.hotWhy(snap(),PowerStats.budget(o,snap(),base+7*minute,false)))
        assertTrue(PowerPolicy.decide(snap(),PowerStats.budget(o,snap(),base+7*minute,false),base+7*minute) is PowerPolicy.Decision.Run)
    }
    @Test fun aTwoMinutePieceThatReallyRunsSlowIsSlow() {
        var o=PowerStats.notePiece(null,240_000,120_000,6,cold=true,now=base)
        o=PowerStats.notePiece(o,350_000,120_000,6,cold=false,now=base+6*minute);assertFalse(PowerStats.slow(o,base+7*minute))
        o=PowerStats.notePiece(o,360_000,120_000,6,cold=false,now=base+12*minute)
        assertTrue("1.5x the cold piece",PowerStats.slow(o,base+13*minute))
        val b=PowerStats.budget(o,snap(),base+13*minute,false)
        assertEquals("slow",PowerPolicy.hotWhy(snap(),b))
        assertEquals("headroom",PowerPolicy.hotWhy(snap(headroom=0.9f),b));assertEquals("temp",PowerPolicy.hotWhy(snap(temp=400),b))
        val w=PowerPolicy.decide(snap(),b,base+13*minute) as PowerPolicy.Decision.Wait
        assertEquals("hot",w.reason);assertEquals(TranscriptionPower.HOT_DELAY_MS,w.retryInMs)
    }
    @Test fun oneSlowPieceCostsOneRestNotTwo() {
        var o=PowerStats.notePiece(null,240_000,120_000,6,cold=true,now=base)
        val end=base+6*minute
        o=PowerStats.notePiece(o,360_000,120_000,6,cold=false,now=end)
        // The run yields; the next wake at once finds it hot: a 10-minute rest, counted as slow.
        val t1=end+minute
        assertTrue(PowerPolicy.hot(snap(),PowerStats.budget(o,snap(),t1,false)))
        o=PowerStats.noteHot(o,t1,PowerPolicy.hotWhy(snap(),PowerStats.budget(o,snap(),t1,false)))
        assertEquals(1,o.getJSONObject("today").getJSONObject("hot").getInt("slow"))
        // An hourly tick during the rest: still hot, and the rest does not start again.
        val tick=t1+4*minute
        assertTrue(PowerStats.slow(o,tick));o=PowerStats.noteHot(o,tick,"slow");assertEquals(t1,o.getLong("hotRestMs"))
        // After the rest (11 min after the piece, before the old 20-minute cool-off): runs again, and its first piece is cold.
        val t2=t1+TranscriptionPower.HOT_DELAY_MS
        assertTrue(t2-end<TranscriptionPower.COLD_AFTER_MS)
        assertFalse(PowerStats.slow(o,t2))
        assertTrue(PowerPolicy.decide(snap(),PowerStats.budget(o,snap(),t2,false),t2) is PowerPolicy.Decision.Run)
        o.put("lastRunEndMs",end)
        assertTrue("the first piece after a hot rest is cold",PowerStats.coldStart(o,t2))
        assertFalse("no rest, a short break: not cold",PowerStats.coldStart(JSONObject().put("lastRunEndMs",end),end+minute))
        // That cold piece goes in as a sample: one more like the slow one is no longer 1.5x the cold median, no second rest.
        o=PowerStats.notePiece(o,330_000,120_000,6,cold=PowerStats.coldStart(o,t2),now=t2+6*minute)
        assertEquals(2,o.getJSONObject("chunk").getJSONArray("coldRates").length())
        assertFalse(PowerStats.slow(o,t2+7*minute))
        // The heartbeat splits hotSkips by cause.
        val hb=PowerText.heartbeat(o,false,t2+7*minute).getJSONObject("today").getJSONObject("hot")
        assertEquals(1,hb.getInt("slow"));assertEquals(0,hb.getInt("headroom"));assertEquals(0,hb.getInt("temp"));assertEquals(0,hb.getInt("severe"))
    }
    @Test fun theExpectedTimeCountsWindowsSoATailDoesNotSplitTheNextPiece() {
        // A tail piece measured alone (7 s in 50 s) used to read as 7.1 ms per audio ms: 120 s x 7.1 x 1.25 > 432 s split the
        // next 2-minute piece ahead. Per window it is 1.67: 120 s x 1.67 x 1.25 = 250 s.
        val o=PowerStats.notePiece(null,50_000,7_000,6,cold=true,now=base)
        val r=PowerStats.rate(o)!!
        assertEquals(50_000.0/30_000,r,0.001)
        assertFalse(PowerPolicy.splitAhead(PowerPolicy.expectedPieceMs(120_000,r),CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS))
        assertTrue("the old rate would have split it",PowerPolicy.splitAhead((120_000*(50_000.0/7_000)*TranscriptionPower.PIECE_MARGIN).toLong(),CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS))
        // A tail piece is expected by its one window, not by its seconds.
        assertEquals((30_000*2.0*TranscriptionPower.PIECE_MARGIN).toLong(),PowerPolicy.expectedPieceMs(7_000,2.0))
        assertEquals((120_000*TranscriptionPower.DEFAULT_RATE*TranscriptionPower.PIECE_MARGIN).toLong(),PowerPolicy.expectedPieceMs(120_000,null))
    }

    // ---- T3a: a battery run goes on past its own start mark ----
    private val rate=16000
    private val wavs=HashMap<String,ByteArray>()
    private var seq=0
    private fun wav(seconds:Double):ByteArray {
        val n=(rate*seconds).toInt();val out=ByteArray(44+n*2)
        val h=ByteBuffer.wrap(out,0,44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(n*2)
        for(t in 0 until n){ val v=(sin(2*PI*330*t/rate)*6000).toInt();out[44+2*t]=(v and 0xff).toByte();out[45+2*t]=((v shr 8) and 0xff).toByte() }
        return out
    }
    private fun rec(id:String,startedMs:Long,seconds:Double) {
        wavs[id]=wav(seconds)
        val sha="%064x".format(++seq);val cipher="%064x".format(1000+seq);val bytes=(seconds*1000).toLong()
        port().use { p -> p.transaction {
            val o=GeneralLocalStore.OWNER
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/wav',?,?,?,?,1,1,1,?)",
                id,o,"doc_$id","$id.wav",bytes,startedMs,startedMs,startedMs,startedMs)
            p.execute("INSERT INTO content_blobs VALUES(?,?)",sha,bytes)
            p.execute("INSERT INTO source_versions VALUES(?,1,?,?,?,?)",id,sha,bytes,startedMs,"obs_$id")
            p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree',?,?,'sig',?,?)",id,"doc_$id","content://generated/document/$id",sha,null)
            p.execute("INSERT INTO local_visibility(recording_id) VALUES(?)",id)
            p.execute("INSERT INTO vault2_refs VALUES(?,1,?,?,?,?,?,?)",id,o,sha,bytes,GeneralLocalStore.ALIAS,cipher,bytes+28);null } }
    }
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=File("m") }

    /** The worker's wiring (CallTranscriptionWork): the start mark written by `started`, the check between pieces by
     * [PowerStats.stillRuns] with the run's own mark. [own]: false = the v3.6 check that took the mark for another run. */
    private fun batteryRun(own:Boolean):Pair<CallTranscriptionRunner.Pass,List<String>> {
        val afternoon=base+11*hour // 14:00 KST on battery
        clock=afternoon
        rec("a",afternoon-3*hour,40.0);rec("b",afternoon-2*hour,40.0);rec("c",afternoon-hour,40.0)
        val s0=snap(pct=70,plugged="none",status="discharging",headroom=0.4f)
        var ledger=JSONObject()
        val start=PowerPolicy.decide(s0,PowerStats.budget(ledger,s0,clock,false),clock) as PowerPolicy.Decision.Run
        assertEquals(PowerPolicy.Mode.BATTERY,start.mode)
        var ownStart:Long?=null
        val events=object:CallTranscriptionRunner.Events {
            override fun started() { ownStart=clock;ledger=PowerStats.noteBatteryStart(ledger,clock) }
            override fun piece(ms:Long,audioMs:Long,cold:Boolean) { ledger=PowerStats.notePiece(ledger,ms,audioMs,4,cold,clock) }
        }
        val sunk=mutableListOf<String>()
        val sink=object:CallTranscriptSink { override fun onTranscript(transcript:CallTranscript){ sunk+=transcript.recordingId } }
        val t=CallTranscriber { _,pcm,_ -> clock+=pcm.size/16*2;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) } // 2x real time
        val pass=CallTranscriptionStore(context,{ clock },{}).use { s ->
            CallTranscriptionRunner(s,model,PipelineAudioDecoder(),t,read={ c -> PipelineBytesInput(wavs.getValue(c.key.recordingId).copyOf()) },
                skipReason={null},sink={sink},now={clock},battery=CallTranscriptionStore.BatteryWindow(PowerPolicy.dayStartMs(clock),1.0),
                runLimitMs=start.runLimitMs,rate={ PowerStats.rate(ledger) },events=events,
                keepGoing={ PowerStats.stillRuns(ledger,s0,clock,false,PowerPolicy.Mode.BATTERY,if(own) ownStart else null) }).run()
        }
        return pass to sunk
    }
    @Test fun aBatteryRunDoesSeveralShortCallsWithinItsBudget() {
        val (pass,sunk)=batteryRun(own=true)
        assertEquals("IDLE",pass.state);assertEquals(listOf("c","b","a"),sunk);assertTrue(pass.pieces>=2)
    }
    @Test fun theV36CheckStoppedABatteryRunAfterOnePiece() {
        val (pass,sunk)=batteryRun(own=false)
        assertEquals("YIELD",pass.state);assertEquals(1,sunk.size)
    }
    @Test fun anotherRunsStartMarkStillHoldsTheGap() {
        val s=snap(pct=70,plugged="none",status="discharging")
        val o=PowerStats.noteBatteryStart(JSONObject(),base)
        assertEquals("gap",(PowerPolicy.decide(s,PowerStats.budget(o,s,base+minute,false),base+minute) as PowerPolicy.Decision.Wait).reason)
        assertTrue(PowerPolicy.decide(s,PowerStats.budget(o,s,base+minute,false,ownStartMs=base),base+minute) is PowerPolicy.Decision.Run)
        assertFalse("another start",PowerStats.stillRuns(o,s,base+minute,false,PowerPolicy.Mode.BATTERY,base-1))
        // The budget still counts: past 20 minutes of today's battery runs the run ends.
        val spent=JSONObject().put("today",JSONObject().put("day",PowerPolicy.day(base)).put("battery",JSONObject().put("ms",TranscriptionPower.BATTERY_BUDGET_MS)))
        assertFalse(PowerStats.stillRuns(PowerStats.noteBatteryStart(spent,base),s,base+minute,false,PowerPolicy.Mode.BATTERY,base))
    }

    // ---- D1: the trim on its own transaction, 7 days, 3 from 60% ----
    private fun recordings(vararg ids:String) = port().use { p -> p.transaction {
        ids.forEach { id -> p.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
            id,GeneralLocalStore.OWNER,"d_$id","$id.wav") };null } }
    private fun analyzed(s:CallRecordStore,id:String,at:Long):String {
        val t=CallTranscript(id,1,"c".repeat(64),at,90_000,"010-1234-5678","네 견적서는 금요일까지 보내드릴게요",
            listOf(CallTranscript.Segment(0,4_000,"네 견적서는 금요일까지 보내드릴게요")),"m","b".repeat(64),true)
        clock=at;s.insert(t);val rid=CallRecordStore.recordId(t)
        s.applyAnalysis(rid,ClaudeCallAnalysis("견적서를 보내기로 했어요.",emptyList(),null,null,emptyList(),
            listOf(ClaudeCallAnalysis.Task("create",null,"견적서 보내기","2026-11-13","네 견적서는 금요일까지 보내드릴게요",owner="me"))))
        return rid
    }
    @Test fun theTrimRunsWithoutAnInsertAndKeepsSevenDaysOrThree() {
        recordings("r1","r2","r3")
        val start=base-10*day
        var fresh=""
        CallRecordStore(context,{ clock },CallRecordStore.SettingsEdit { }).use { s ->
            // Newest first, so no insert's own trim reaches an older one.
            fresh=analyzed(s,"r3",base-2*day)       // analysed 2 days ago
            val mid=analyzed(s,"r2",base-5*day)     // 5 days ago
            val old=analyzed(s,"r1",start)          // 10 days ago
            clock=base
            s.trim(base) // no insert: the daily prune's call
            fun words(id:String)=s.record(id)!!.transcript
            assertEquals("",words(old));assertTrue(words(mid).isNotEmpty());assertTrue(words(fresh).isNotEmpty())
            // The quote of the check item stays with the analysis.
            assertEquals("네 견적서는 금요일까지 보내드릴게요",s.record(old)!!.analysis!!.tasks.single().quote)
            assertEquals(7L,CallRecordStore.transcriptKeepDays(59));assertEquals(7L,CallRecordStore.transcriptKeepDays(null))
            assertEquals(3L,CallRecordStore.transcriptKeepDays(60))
            s.trim(base,dbPercent=60)
            assertEquals("",words(mid));assertTrue(words(fresh).isNotEmpty())
            assertNotNull(s.dbPercent())
        }
        // The daily work's own call: the process store, at its share (small here: 7 days).
        VaultPruneWork.trimRecords(context,base+day)
        assertTrue(kr.callbrief.IeumApplication.store(context).record(fresh)!!.transcript.isNotEmpty())
        VaultPruneWork.trimRecords(context,base+6*day)
        assertEquals("",kr.callbrief.IeumApplication.store(context).record(fresh)!!.transcript)
    }
}
