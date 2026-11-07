package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.junit.Assert.*
import org.junit.After
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

/** v3.6 r16-1 (DESIGN_V6 2): the power policy (plugged in: everything; battery: today's short calls within a budget), W1 a run
 * ends itself before a piece that would not fit, W2 a piece expected to hit the deadline is halved ahead, W3 one model session
 * per run, W4 permanent failures and the charger-only mark, W5 revival only on a plugged-in run, P7 threads within the cores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R16OneTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L;private val hour=60*minute
    /** 2026-11-07 14:00 KST (a Saturday afternoon). */
    private val base=LocalDate.of(2026,11,7).atTime(14,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private var clock=base
    private val rate=16000
    private val wavs=HashMap<String,ByteArray>()
    private var seq=0

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        CallTranscriptionStore(context){ clock }.close()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER);null } }
    }
    @After fun reset() { CallTranscriptionStore.buildCode={ kr.callbrief.BuildConfig.VERSION_CODE } }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun wav(seconds:Double):ByteArray {
        val n=(rate*seconds).toInt();val out=ByteArray(44+n*2)
        val h=ByteBuffer.wrap(out,0,44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(n*2)
        for(t in 0 until n){ val v=(sin(2*PI*330*t/rate)*6000).toInt();out[44+2*t]=(v and 0xff).toByte();out[45+2*t]=((v shr 8) and 0xff).toByte() }
        return out
    }
    /** A stored recording (its file size = 1 byte a millisecond, so the size tells the length before it is decoded). */
    private fun recording(id:String,startedMs:Long,seconds:Double) {
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
    private fun key(id:String)=CallTranscriptionStore.Key(id,1,"%064x".format(seqOf[id]!!))
    private val seqOf=HashMap<String,Int>()
    private fun rec(id:String,startedMs:Long,seconds:Double) { recording(id,startedMs,seconds);seqOf[id]=seq }
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=File("m") }
    private val sunk=mutableListOf<String>()
    private val sink=object:CallTranscriptSink { override fun onTranscript(transcript:CallTranscript){ sunk+=transcript.recordingId } }
    private fun state(s:CallTranscriptionStore,id:String)=s.rows().single { it.first.recordingId==id }.let { it.second to it.third }
    private fun runner(s:CallTranscriptionStore,transcriber:CallTranscriber,battery:CallTranscriptionStore.BatteryWindow?=null,rate:Double?=null,
                       limit:Long=TranscriptionPower.SELF_END_MS,keepGoing:()->Boolean={true},events:CallTranscriptionRunner.Events=CallTranscriptionRunner.Events.NONE,stopped:()->Boolean={false})=
        CallTranscriptionRunner(s,model,PipelineAudioDecoder(),transcriber,read={ c -> PipelineBytesInput(wavs.getValue(c.key.recordingId).copyOf()) },
            skipReason={null},sink={sink},stopped=stopped,now={clock},battery=battery,runLimitMs=limit,rate={ rate },keepGoing=keepGoing,events=events)

    // ---- PowerPolicy: pure cases ----
    private fun snap(pct:Int=60,plugged:String="none",status:String="discharging",powerSave:Boolean=false,thermal:Int=0,headroom:Float?=null,temp:Int?=300,inCall:Boolean=false)=
        BatterySnapshot(pct,plugged,status,powerSave,thermal,headroom,temp,inCall)
    private fun run(d:PowerPolicy.Decision)=d as PowerPolicy.Decision.Run
    private fun wait(d:PowerPolicy.Decision)=d as PowerPolicy.Decision.Wait
    @Test fun batteryAtSixtyRunsShortCallsOnFourThreadsWithinTheBudget() {
        val d=run(PowerPolicy.decide(snap(),PowerBudget(),base))
        assertEquals(PowerPolicy.Mode.BATTERY,d.mode);assertEquals(4,d.threads);assertEquals(TranscriptionPower.SELF_END_MS,d.runLimitMs)
        assertEquals("what is left of the budget limits the run",5*minute,run(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=15*minute),base)).runLimitMs)
        assertEquals("budget",wait(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=20*minute),base)).reason)
        assertEquals("5% of the battery","budget",wait(PowerPolicy.decide(snap(),PowerBudget(batteryUah=250_000,capacityUah=5_000_000),base)).reason)
        assertEquals("pct",PowerPolicy.budgetHit(PowerBudget(batteryUah=250_000,capacityUah=5_000_000)))
        assertNull(PowerPolicy.budgetHit(PowerBudget(batteryUah=249_000,capacityUah=5_000_000)))
        assertNull("no capacity: minutes only",PowerPolicy.budgetHit(PowerBudget(batteryUah=10_000_000)))
    }
    @Test fun batteryRunsAreThirtyMinutesApart() {
        val w=wait(PowerPolicy.decide(snap(),PowerBudget(lastBatteryRunEndMs=base-10*minute),base))
        assertEquals("gap",w.reason);assertEquals(20*minute,w.retryInMs)
        assertTrue(PowerPolicy.decide(snap(),PowerBudget(lastBatteryRunEndMs=base-30*minute),base) is PowerPolicy.Decision.Run)
    }
    @Test fun lowSaverInCallAndHotDoNothingOnBattery() {
        assertEquals("low",wait(PowerPolicy.decide(snap(pct=39),PowerBudget(),base)).reason)
        assertEquals("below the old battery-low constraint too","low",wait(PowerPolicy.decide(snap(pct=10),PowerBudget(),base)).reason)
        assertEquals("powerSave",wait(PowerPolicy.decide(snap(powerSave=true),PowerBudget(),base)).reason)
        assertEquals("inCall",wait(PowerPolicy.decide(snap(inCall=true),PowerBudget(),base)).reason)
        assertNull(wait(PowerPolicy.decide(snap(temp=401),PowerBudget(),base)).retryInMs)
        assertTrue(PowerPolicy.decide(snap(pct=40),PowerBudget(),base) is PowerPolicy.Decision.Run)
    }
    @Test fun pluggedInTakesEverythingAtOnceWiredSixWirelessFour() {
        val d=run(PowerPolicy.decide(snap(pct=20,plugged="ac",status="charging"),PowerBudget(batteryRunMs=60*minute,lastBatteryRunEndMs=base-minute),base))
        assertEquals(PowerPolicy.Mode.PLUGGED,d.mode);assertEquals(6,d.threads)
        assertEquals(6,run(PowerPolicy.decide(snap(plugged="usb",status="charging"),PowerBudget(),base)).threads)
        assertEquals(4,run(PowerPolicy.decide(snap(plugged="wireless",status="charging"),PowerBudget(),base)).threads)
        assertTrue("in call, plugged in: runs",PowerPolicy.decide(snap(plugged="ac",inCall=true),PowerBudget(),base) is PowerPolicy.Decision.Run)
    }
    @Test fun samsungBatteryProtectionStillCountsAsPluggedIn() {
        for(st in listOf("notCharging","full")) assertEquals(PowerPolicy.Mode.PLUGGED,run(PowerPolicy.decide(snap(pct=85,plugged="ac",status=st),PowerBudget(),base)).mode)
    }
    @Test fun aPlugAwayForUnderTwoMinutesStillCounts() {
        assertEquals(PowerPolicy.Mode.PLUGGED,run(PowerPolicy.decide(snap(pct=30),PowerBudget(lastPluggedMs=base-90_000),base)).mode)
        assertEquals("low",wait(PowerPolicy.decide(snap(pct=30),PowerBudget(lastPluggedMs=base-3*minute),base)).reason)
    }
    @Test fun aPlugThatLetsTheChargeFallFollowsTheBatteryRules() {
        val d=run(PowerPolicy.decide(snap(pct=60,plugged="usb"),PowerBudget(levelFalling=true),base))
        assertEquals(PowerPolicy.Mode.BATTERY,d.mode)
        assertEquals("low",wait(PowerPolicy.decide(snap(pct=30,plugged="usb"),PowerBudget(levelFalling=true),base)).reason)
        // The sample: plugged at 70% at 14:00, 69% at 14:11 -> falling; 70% -> not.
        var o=PowerStats.notePlug(null,snap(pct=70,plugged="usb"),base)
        assertFalse(PowerStats.levelFalling(o,snap(pct=69,plugged="usb"),base+5*minute))
        o=PowerStats.notePlug(o,snap(pct=69,plugged="usb"),base+11*minute)
        assertTrue(PowerStats.levelFalling(o,snap(pct=69,plugged="usb"),base+11*minute))
        assertFalse(PowerStats.levelFalling(o,snap(pct=70,plugged="usb"),base+11*minute))
    }
    @Test fun hotInThreeWaysWaitsTenMinutesWhilePluggedIn() {
        for(s in listOf(snap(plugged="ac",headroom=0.85f),snap(plugged="ac",temp=400))) {
            val w=wait(PowerPolicy.decide(s,PowerBudget(),base));assertEquals("hot",w.reason);assertEquals(10*minute,w.retryInMs)
        }
        assertEquals("hot",wait(PowerPolicy.decide(snap(plugged="ac"),PowerBudget(slow=true),base)).reason)
        assertTrue(PowerPolicy.decide(snap(plugged="ac",headroom=0.84f,temp=399),PowerBudget(),base) is PowerPolicy.Decision.Run)
        assertEquals("severe rests 20 minutes",20*minute,wait(PowerPolicy.decide(snap(plugged="ac",thermal=3),PowerBudget(),base)).retryInMs)
        // slow = the last piece 1.5x the cold median
        var o=PowerStats.notePiece(null,240_000,120_000,6,cold=true)
        o=PowerStats.notePiece(o,300_000,120_000,6,cold=false);assertFalse(PowerStats.slow(o))
        o=PowerStats.notePiece(o,360_000,120_000,6,cold=false);assertTrue(PowerStats.slow(o))
        assertEquals(240_000L,o.getJSONObject("chunk").getLong("coldMedianMs"))
    }
    @Test fun theMorningIsForChargingUnderNinetyPercent() {
        val six=LocalDate.of(2026,11,8).atTime(6,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val w=wait(PowerPolicy.decide(snap(pct=89,plugged="ac",status="charging"),PowerBudget(),six));assertEquals("morning",w.reason);assertEquals(30*minute,w.retryInMs)
        assertEquals(minute*30,wait(PowerPolicy.decide(snap(pct=89,plugged="ac"),PowerBudget(),six-30*minute)).retryInMs)
        assertTrue(PowerPolicy.decide(snap(pct=90,plugged="ac"),PowerBudget(),six) is PowerPolicy.Decision.Run)
        assertTrue(PowerPolicy.decide(snap(pct=50,plugged="ac"),PowerBudget(),six+hour) is PowerPolicy.Decision.Run)
    }
    @Test fun theSwitchKeepsEverythingForTheCharger() {
        assertEquals("chargingOnly",wait(PowerPolicy.decide(snap(pct=100),PowerBudget(chargingOnly=true),base)).reason)
        assertTrue(PowerPolicy.decide(snap(plugged="ac"),PowerBudget(chargingOnly=true),base) is PowerPolicy.Decision.Run)
    }
    @Test fun threadsStayWithinTheCoresTheProcessMayUse() {
        assertEquals(4,CpuSet.count("0-3"));assertEquals(5,CpuSet.count("0-3,6"));assertEquals(8,CpuSet.count("0-7"));assertNull(CpuSet.count(""));assertNull(CpuSet.count("x"))
        assertEquals(4,CpuSet.allowed("Name:\tx\nCpus_allowed:\tf\nCpus_allowed_list:\t0-3\n"))
        assertEquals("background",CpuSet.code("/background\n"));assertEquals("top-app",CpuSet.code("/top-app"));assertEquals("root",CpuSet.code("/"))
        assertEquals(4,PowerPolicy.threads(6,4));assertEquals(6,PowerPolicy.threads(6,8));assertEquals(6,PowerPolicy.threads(6,null))
    }
    @Test fun theLedgerRollsDaysAndCountsRunsSkipsAndPieces() {
        var o=PowerStats.noteRun(null,base,PowerPolicy.Mode.BATTERY,6*minute,90_000,12_000)
        o=PowerStats.noteRun(o,base+hour,PowerPolicy.Mode.PLUGGED,9*minute,240_000,null)
        o=PowerStats.noteSkip(o,base,"gap");o=PowerStats.noteSkip(o,base,"gap");o=PowerStats.noteBudgetHit(o,base,"min")
        val t=o.getJSONObject("today")
        assertEquals(1,t.getJSONObject("battery").getInt("runs"));assertEquals(6,t.getJSONObject("battery").getInt("min"));assertEquals(12,t.getJSONObject("battery").getInt("mAhScreenOff"))
        assertEquals(9,t.getJSONObject("plugged").getInt("min"));assertEquals(2,t.getInt("gapSkips"));assertEquals("min",t.getString("budgetHit"))
        val b=PowerStats.budget(o,snap(),base+hour,false)
        assertEquals(6*minute,b.batteryRunMs);assertEquals(base,b.lastBatteryRunEndMs)
        // The next KST day starts again; yesterday is prev.
        val next=base+24*hour
        assertEquals(0L,PowerStats.budget(o,snap(),next,false).batteryRunMs)
        o=PowerStats.noteSkip(o,next,"low")
        assertEquals(1,o.getJSONObject("prev").getJSONObject("battery").getInt("runs"));assertFalse(o.getJSONObject("today").has("battery"))
        // A piece on another thread count starts the speed list again.
        var c=PowerStats.notePiece(null,300_000,120_000,6,false);c=PowerStats.notePiece(c,200_000,120_000,4,false)
        assertEquals(1,c.getJSONObject("chunk").getJSONArray("rates").length())
        assertEquals(200_000.0/120_000,PowerStats.rate(c)!!,0.01)
    }

    // ---- the battery queue: today's short calls only, newest first, in SQL ----
    @Test fun aBatteryRunTakesTodaysShortCallsNewestFirstAndLeavesTheRestForTheCharger() {
        val today=PowerPolicy.dayStartMs(base)
        rec("yesterday",today-hour,60.0);rec("long",base-3*hour,240.0);rec("tiny",base-2*hour,10.0)
        rec("short1",base-hour,40.0);rec("short2",base-30*minute,90.0);rec("long2",base-10*minute,200.0)
        val t=CallTranscriber { _,pcm,_ -> clock+=pcm.size/16;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val w=CallTranscriptionStore.BatteryWindow(today,1.0)
            val pass=runner(s,t,battery=w).run()
            assertEquals("IDLE",pass.state);assertEquals(listOf("short2","short1"),sunk)
            for(id in listOf("yesterday","long","tiny","long2")) assertEquals(id,"QUEUED",state(s,id).first)
            val (n,oldest)=s.powerWaiting(w,chargingOnly=false)
            assertEquals(4,n);assertNotNull(oldest)
            assertEquals("the switch: every queued row waits",4,s.powerWaiting(w,chargingOnly=true).first)
            assertEquals("unknown length (no size ratio) is not taken on battery",null,s.nextQueued(null,CallTranscriptionStore.BatteryWindow(today,null)))
        }
    }

    // ---- W1: a run ends itself before a piece that would not fit; the attempt is given back, no stop is counted ----
    @Test fun aPieceThatWouldNotFitIsNotBegunAndTheAttemptIsGivenBack() {
        rec("long",base-hour,5*60.0) // three pieces
        var pieces=0;var yields=0
        val events=object:CallTranscriptionRunner.Events { override fun yielded() { yields++ } }
        val t=CallTranscriber { _,pcm,_ -> pieces++;clock+=pcm.size/16*5/2;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) } // 2.5 x real time
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val pass=runner(s,t,rate=2.5,events=events).run()
            // 300 s for the first piece, then 300+375 > 540: the second is not begun.
            assertEquals("YIELD",pass.state);assertEquals(1,pieces);assertEquals(1,yields);assertEquals(1,pass.pieces)
            assertEquals("QUEUED" to ("STOPPED" to 0),state(s,"long"))
            assertEquals(0,s.totalStalls(key("long")))
            assertTrue(CallTranscriptionRunner.requeueNow(pass.state,pass.pieces))
        }
    }
    @Test fun theFirstPieceOfARunAlwaysStarts() {
        rec("a",base-hour,60.0)
        var pieces=0
        val t=CallTranscriber { _,pcm,_ -> pieces++;clock+=5*minute;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            runner(s,t,rate=4.0,limit=minute).run() // expected 300 s, the run may go 60 s: begun anyway
            assertEquals(1,pieces);assertEquals("DONE",state(s,"a").first)
        }
        assertTrue(PowerPolicy.startPiece(true,0,10*hour,TranscriptionPower.SELF_END_MS))
        assertFalse(PowerPolicy.startPiece(false,5*minute,5*minute,TranscriptionPower.SELF_END_MS))
    }
    @Test fun aPowerChangeEndsTheRunBetweenPieces() {
        rec("long",base-hour,5*60.0)
        var pieces=0;var ok=true
        val t=CallTranscriber { _,pcm,_ -> pieces++;ok=false;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            assertEquals("YIELD",runner(s,t,keepGoing={ ok }).run().state);assertEquals(1,pieces)
            assertEquals("QUEUED",state(s,"long").first);assertEquals(0,s.totalStalls(key("long")))
        }
    }

    // ---- W2: a piece expected to run into the deadline is halved before it is begun, and marked ----
    @Test fun aPieceExpectedToHitTheDeadlineIsHalvedAheadAndMarked() {
        rec("hot",base-hour,120.0)
        val sizes=mutableListOf<Int>()
        val t=CallTranscriber { _,pcm,_ -> sizes+=pcm.size;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            // 120 s x 3 x 1.25 = 450 s > 432 s: two halves from the start.
            runner(s,t,rate=3.0,limit=hour).run()
            assertEquals(2,sizes.size);assertTrue("$sizes",sizes.all { it<=96*rate })
            assertEquals("DONE",state(s,"hot").first)
        }
        assertTrue(PowerPolicy.splitAhead(450_000,540));assertFalse(PowerPolicy.splitAhead(412_500,540))
    }

    // ---- W3: one model session for the whole run; a failed handle is not used again ----
    @Test fun oneModelSessionServesTheWholeRun() {
        rec("a",base-hour,30.0);rec("b",base-50*minute,30.0);rec("c",base-40*minute,30.0)
        var opened=0;var closed=0;var resets=0
        val t=object:CallTranscriber {
            override fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean)=error("unused")
            override fun open(model:File,stopped:()->Boolean):CallTranscriber.Session { opened++
                return object:CallTranscriber.Session {
                    override fun transcribe(pcm:FloatArray,stopped:()->Boolean)=listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말"))
                    override fun nextRecording() { resets++ }
                    override fun close() { closed++ } } }
        }
        var loads=0
        val events=object:CallTranscriptionRunner.Events { override fun modelLoad(ms:Long) { loads++ } }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            assertEquals(3,runner(s,t,events=events).run().done)
            assertEquals(1,opened);assertEquals(1,closed);assertEquals(1,loads);assertEquals("the previous text is forgotten per recording",2,resets)
        }
    }
    @Test fun aSessionThatFailedIsClosedAndANewOneOpened() {
        rec("a",base-hour,30.0);rec("b",base-50*minute,30.0)
        var opened=0;var closed=0;var calls=0
        val t=object:CallTranscriber {
            override fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean)=error("unused")
            override fun open(model:File,stopped:()->Boolean):CallTranscriber.Session { opened++
                return object:CallTranscriber.Session {
                    override fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> { if(calls++==0) throw IllegalStateException("ANDROID_STT_X");return listOf(CallTranscript.Segment(0,1000,"말")) }
                    override fun close() { closed++ } } }
        }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            runner(s,t).run()
            assertEquals(2,opened);assertEquals(2,closed);assertEquals("DONE",state(s,"a").first)
        }
    }

    // ---- W4: permanent failures; the native deadline twice and an interrupted run send a recording to the charger ----
    @Test fun aRecordingThatCannotBeReadFailsForGoodAndIsNeverRevived() {
        rec("bad",base-hour,30.0)
        val t=CallTranscriber { _,_,_ -> throw IllegalStateException("ANDROID_STT_PCM_SIZE") }
        CallTranscriptionStore.buildCode={ 5000 }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val pass=runner(s,t).run();assertEquals(1,pass.failed)
            assertEquals("FAILED" to ("ANDROID_STT_PCM_SIZE" to 1),state(s,"bad"))
        }
        CallTranscriptionStore.buildCode={ 5001 }
        CallTranscriptionStore(context,{ clock },{}).use { s -> assertEquals(0,s.requeueFailed());assertEquals("FAILED",state(s,"bad").first) }
        assertTrue(CallTranscriptionStore.permanent("CodecException"));assertTrue(CallTranscriptionStore.permanent("EMPTY_TRANSCRIPT"));assertFalse(CallTranscriptionStore.permanent("STALLED"))
    }
    @Test fun theDeadlineTwiceSendsTheRecordingToTheCharger() {
        rec("slow",base-hour,30.0)
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val k=key("slow");s.offer(s.candidates().single(),true,"")
            s.noteDeadline(k);assertFalse(s.chargingOnly(k))
            s.noteDeadline(k);assertTrue(s.chargingOnly(k))
            assertNull("a battery run does not take it",s.nextQueued(null,CallTranscriptionStore.BatteryWindow(PowerPolicy.dayStartMs(base),1.0)))
            assertNotNull("a plugged-in run does",s.nextQueued(null,null))
        }
    }
    @Test fun anInterruptedRecordingWaitsForTheCharger() {
        rec("dead",base-hour,30.0)
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val k=key("dead");s.offer(s.candidates().single(),true,"");assertTrue(s.claim(k))
            clock+=CallTranscriptionStore.STALE_RUNNING_MS+1;s.recoverRunning(CallTranscriptionRunner.MAX_ATTEMPTS)
            assertEquals("QUEUED",state(s,"dead").first);assertTrue(s.chargingOnly(k))
        }
    }

    // ---- W5: revival only when asked (the start of a plugged-in run), with the stop counts cleared ----
    @Test fun failedRowsAreRevivedOnlyWhenAPluggedInRunAsks() {
        rec("x",base-hour,30.0)
        CallTranscriptionStore.buildCode={ 6000 }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val k=key("x");s.offer(s.candidates().single(),true,"");s.noteStall(k,0);s.askSplit(k,1)
            repeat(3) { assertTrue(s.claim(k));s.finish(k,if(it<2) "QUEUED" else "FAILED","STALLED") }
        }
        CallTranscriptionStore.buildCode={ 6001 }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val k=key("x")
            assertEquals("opening the store revives nothing","FAILED",state(s,"x").first)
            assertEquals(1,s.requeueFailed());assertEquals("QUEUED",state(s,"x").first)
            assertEquals(0,s.totalStalls(k));assertFalse(s.splitAsked(k,1))
        }
    }
}
