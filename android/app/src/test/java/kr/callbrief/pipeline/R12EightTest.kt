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

/** r12-8 (DESIGN_V4 2-3): long calls are transcribed to the end. A2 order, A3 no late start, A4 the native deadline halves
 * the piece, A5 a tail under 0.1 s joins the piece before it, A1/A8 heartbeat numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R12EightTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L;private val hour=60*minute
    private val base=LocalDate.of(2026,11,5).atTime(12,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private var clock=base
    private val rate=16000
    private val wavs=HashMap<String,ByteArray>()
    private var seq=0

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        CallTranscriptionStore(context){ clock }.close() // job tables and the local overlay
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER);null } }
    }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))

    /** A continuous tone (no silence to skip) of [seconds] at 16 kHz mono. */
    private fun wav(seconds:Double):ByteArray {
        val n=(rate*seconds).toInt();val out=ByteArray(44+n*2)
        val h=ByteBuffer.wrap(out,0,44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(n*2)
        for(t in 0 until n){ val v=(sin(2*PI*330*t/rate)*6000).toInt();out[44+2*t]=(v and 0xff).toByte();out[45+2*t]=((v shr 8) and 0xff).toByte() }
        return out
    }
    /** A stored recording with its encrypted original, so the transcription pass offers it. */
    private fun recording(id:String,startedMs:Long,seconds:Double) {
        wavs[id]=wav(seconds)
        val sha="%064x".format(++seq);val cipher="%064x".format(1000+seq)
        port().use { p -> p.transaction {
            val o=GeneralLocalStore.OWNER
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/wav',4000,?,?,?,1,1,1,?)",
                id,o,"doc_$id","$id.wav",startedMs,startedMs,startedMs,startedMs)
            p.execute("INSERT INTO content_blobs VALUES(?,4000)",sha)
            p.execute("INSERT INTO source_versions VALUES(?,1,?,4000,?,?)",id,sha,startedMs,"obs_$id")
            p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree',?,?,'sig',?,?)",id,"doc_$id","content://generated/document/$id",sha,null)
            p.execute("INSERT INTO local_visibility(recording_id) VALUES(?)",id)
            p.execute("INSERT INTO vault2_refs VALUES(?,1,?,?,4000,?,?,4028)",id,o,sha,GeneralLocalStore.ALIAS,cipher);null } }
    }
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=File("m") }
    private val sunk=mutableListOf<String>()
    private val sink=object:CallTranscriptSink { override fun onTranscript(transcript:CallTranscript){ sunk+=transcript.recordingId } }
    private fun runner(s:CallTranscriptionStore,transcriber:CallTranscriber,stopped:()->Boolean={false},timeLimited:()->Boolean={false},rate:Double?=null)=
        CallTranscriptionRunner(s,model,PipelineAudioDecoder(),transcriber,read={ c -> PipelineBytesInput(wavs.getValue(c.key.recordingId).copyOf()) },
            skipReason={null},sink={sink},stopped=stopped,now={clock},timeLimited=timeLimited,rate={ rate })
    private fun state(s:CallTranscriptionStore,id:String)=s.rows().single { it.first.recordingId==id }.let { it.second to it.third }

    // ---- 확인 기준 1: short new calls and one long recording; the long one is done within two or three runs ----
    @Test fun aLongRecordingFinishesWithinFourRunsWhileNewCallsKeepComing() {
        recording("long",base-2*hour,9*60.0)                                   // 9 minutes: five pieces
        repeat(8) { recording("short$it",base-hour+it*minute,30.0) }
        // About 1.4 x real time per piece (the S26 measures 85 s per audio minute); WorkManager stops a run at 10 minutes.
        val transcriber=CallTranscriber { _,pcm,_ -> clock+=pcm.size*1400L/rate;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        var runStart=0L
        var runs=0
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            // v3.6 W1: a run ends itself at 9 minutes before a piece that would not fit (the measured speed, 1.4 here), so a
            // run holds two such pieces instead of three and a cut-off fourth: four runs, none of them throwing a piece away.
            val r=runner(s,transcriber,stopped={ clock-runStart>=10*minute },timeLimited={ true },rate=1.4)
            for(i in 0 until 4) {
                if(i>0) repeat(3) { recording("new$i$it",clock+it*minute,30.0) } // calls that came in meanwhile
                clock+=hour;runStart=clock;r.run();runs++
                if(state(s,"long").first=="DONE") break
            }
            assertEquals("DONE",state(s,"long").first)
            assertTrue("runs $runs",runs<=4)
            assertEquals(0,s.totalStalls(CallTranscriptionStore.Key("long",1,"%064x".format(1))))
            assertTrue(s.rows().none { it.second=="FAILED" })
        }
        assertTrue("long" in sunk)
    }

    // ---- A3: after 6 minutes of a run no new recording is begun ----
    @Test fun noNewRecordingIsBegunAfterSixMinutes() {
        recording("a",base-2*minute,20.0);recording("b",base-minute,20.0)
        val transcriber=CallTranscriber { _,pcm,_ -> clock+=7*minute;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val pass=runner(s,transcriber).run()
            assertEquals("MORE",pass.state);assertEquals(1,pass.done)
            assertEquals("DONE",state(s,"b").first)                              // the newest went first
            assertEquals("QUEUED" to ("" to 0),state(s,"a"))                      // not claimed: no attempt spent
            assertTrue(CallTranscriptionRunner.requeueNow(pass.state,pass.pieces))
        }
        // A stop at the time limit of a recording that was not the run's first is no stall, even without a finished piece.
        assertTrue(CallTranscriptionRunner.harmlessStop(true,0,laterInRun=true))
        assertFalse(CallTranscriptionRunner.harmlessStop(true,0,laterInRun=false))
        assertFalse(CallTranscriptionRunner.harmlessStop(false,0,laterInRun=true))
    }

    // ---- A4: a piece that runs into the native deadline is halved; the job never fails for it ----
    @Test fun theNativeDeadlineHalvesThePieceInsteadOfFailing() {
        recording("slow",base-minute,180.0)
        val sizes=mutableListOf<Int>()
        // Anything over 90 s outlasts the 540 s native limit on this "phone".
        val transcriber=CallTranscriber { _,pcm,_ -> sizes+=pcm.size
            if(pcm.size>90*rate) throw IllegalStateException("ANDROID_STT_NOT_COMPLETED")
            listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        var failed=0
        CallTranscriptionStore(context,{ clock },{ failed+=it }).use { s ->
            val key=CallTranscriptionStore.Key("slow",1,"%064x".format(1))
            val pass=runner(s,transcriber).run()
            assertEquals(1,pass.done);assertEquals(0,pass.failed);assertEquals(0,failed)
            assertEquals("DONE",state(s,"slow").first)
            assertEquals(1,s.attempts(key))                                       // the deadline gave its attempt back
            assertTrue("whole piece tried, then two halves: $sizes",sizes.size==4 && sizes[0]>90*rate && sizes[1]<=90*rate && sizes[2]<=90*rate)
            assertFalse(s.splitAsked(key,0))                                      // cleared with the parts
        }
        assertTrue(CallTranscriptionRunner.deadline(IllegalStateException("ANDROID_STT_NOT_COMPLETED")))
        assertFalse(CallTranscriptionRunner.deadline(IllegalStateException("ANDROID_STT_PCM_SIZE")))
    }

    @Test fun aDeadlineMarksTheJobForTheNextRunAndItGoesFirst() {
        recording("old",base-3*hour,150.0);recording("fresh",base-minute,20.0)
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            s.offerAll(s.candidates(),{null})
            val key=CallTranscriptionStore.Key("old",1,"%064x".format(1))
            assertEquals("fresh",s.nextQueued()!!.candidate.key.recordingId)     // newest first while nothing is under way
            assertTrue(s.claim(key));s.askSplit(key,0);s.finish(key,"QUEUED",CallTranscriptionStore.DEADLINE,refundAttempt=true)
            // v3.7 T2: a deadline mark alone is no progress; today's newest still goes first (DESIGN_V7 3.1).
            assertEquals("fresh",s.nextQueued()!!.candidate.key.recordingId)
            s.savePart(key,-1,listOf(CallTranscript.Segment(0,1000,"말")))
            assertEquals("old",s.nextQueued()!!.candidate.key.recordingId)       // a half saved: under way, before the new call
            assertEquals(0,s.attempts(key));assertTrue(s.splitAsked(key,0))
        }
    }

    // ---- A2 (v3.7 T2): under way first, then yesterday's and today's calls newest first, then waited over a day ----
    @Test fun theQueueTakesRecordingsUnderWayThenLongWaitsThenTheNewest() {
        recording("newest",base-minute,10.0);recording("newer",base-10*minute,10.0)
        recording("waited",base-40*hour,10.0);recording("begun",base-5*hour,10.0)
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            clock=base-30*hour;s.offer(s.candidates().single { it.key.recordingId=="waited" },true,"")
            clock=base;s.offerAll(s.candidates(),{null})
            val begun=s.candidates().single { it.key.recordingId=="begun" }.key
            s.savePart(begun,0,listOf(CallTranscript.Segment(0,1000,"말")))
            fun next()=s.nextQueued()!!.candidate.key.also { s.claim(it);s.finish(it,"DONE","") }.recordingId
            assertEquals(listOf("begun","newest","newer","waited"),List(4) { next() })
        }
    }

    // ---- A5: a last piece under 0.1 s is joined to the piece before it ----
    @Test fun aTailUnderATenthOfASecondJoinsThePieceBefore() {
        val n=(rate*120.02).toInt()
        val pcm=FloatArray(n){ (0.3*sin(2*PI*330*it/rate)).toFloat() }
        for(i in (rate*119.9).toInt() until rate*120) pcm[i]=0f                  // the quietest spot leaves a 0.07 s tail
        assertEquals(listOf(0),PipelineAudioMath.cutPoints(pcm))
        val pieces=PipelineAudioMath.split(pcm)
        assertEquals(1,pieces.size);assertEquals(n,pieces.single().second.size)
        // The streaming chunker cuts the same way.
        val streamed=mutableListOf<Int>()
        PipelineChunker{ _,_,a,b -> streamed+=b-a }.apply { add(pcm);finish() }
        assertEquals(listOf(n),streamed)
        // A tail of 0.1 s or more stays its own piece; every piece then meets the runtime's 1600 samples.
        val longer=pcm.copyOf(n+rate/10)
        val cuts=PipelineAudioMath.split(longer)
        assertEquals(2,cuts.size);assertTrue(cuts.all { it.second.size>=CallTranscriptionRunner.MIN_PIECE_SAMPLES })
        val again=mutableListOf<Int>();PipelineChunker{ _,_,a,b -> again+=b-a }.apply { add(longer);finish() }
        assertEquals(cuts.map { it.second.size },again)
        // A recording that is all of 0.05 s never reaches the runtime.
        recording("blip",base-minute,0.05)
        var called=0
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            runner(s,CallTranscriber { _,_,_ -> called++;emptyList() }).run()
            assertEquals(0,called);assertEquals("SKIPPED" to ("NO_SPEECH" to 1),state(s,"blip"))
        }
    }

    // ---- A1/A8: heartbeat numbers ----
    @Test fun heartbeatCountsByLengthAndTheLongCallRate() {
        val now=base
        fun row(state:String,len:Long?,reason:String="",at:Long=now-hour,bytes:Long?=null,queued:Long=now-2*hour)=
            TranscriptionStats.Row(state,reason,queued,now-minute,len,bytes,at)
        val o=TranscriptionStats.of(listOf(
            row("DONE",30_000),row("DONE",150_000),row("DONE",700_000),
            row("QUEUED",null,bytes=300_000,queued=now-5*hour),                   // estimated (300 s): 1 byte per ms from the row below
            row("QUEUED",120_000,bytes=120_000),row("RUNNING",400_000,queued=now-30*minute),
            row("FAILED",200_000,"STALLED"),row("FAILED",90_000,"ANDROID_STT_PCM_SIZE"),row("FAILED",null,"STALLED"),
            row("SKIPPED",300_000,"ARCHIVED"),row("SKIPPED",200_000,"NO_SPEECH"),
            row("DONE",500_000,at=now-50*hour)),now)                               // before the 48 hours
        assertEquals(2,o.getJSONObject("failReasons").getInt("STALLED"));assertEquals(1,o.getJSONObject("failReasons").getInt("ANDROID_STT_PCM_SIZE"))
        val q=o.getJSONObject("queuedByLen")
        assertEquals(listOf(0,1,2,0,0),listOf("lt1m","m1to3","m3to10","m10plus","unknown").map { q.getInt(it) })
        assertEquals(1,o.getJSONObject("doneByLen48h").getInt("m10plus"))
        // Over 100 s in 48 hours: DONE 150 s, 700 s, NO_SPEECH 200 s finished; QUEUED 600 s, 120 s, RUNNING, FAILED 200 s not.
        val long=o.getJSONObject("long48h")
        assertEquals(7,long.getInt("total"));assertEquals(3,long.getInt("done"));assertEquals(42,long.getInt("ratePct"))
        assertEquals(300L,o.getLong("oldestWaitMin"))
        assertFalse(TranscriptionStats.of(emptyList(),now).getJSONObject("long48h").has("ratePct"))
        assertFalse(TranscriptionStats.of(emptyList(),now).has("oldestWaitMin"))
        // Through the heartbeat's counts filter, the codes and numbers stay.
        val runs=TranscriptionRuns.add(TranscriptionRuns.add(null,20261105,4*minute,TranscriptionRuns.endCode("STOPPED",true)),20261105,90_000,"IDLE")
        assertEquals(2,runs.getInt("runs"));assertEquals(5L,runs.getLong("minutes"));assertEquals(1,runs.getJSONObject("ends").getInt("STOPPED_TIMEOUT"))
        val next=TranscriptionRuns.add(runs,20261106,minute,"MORE")
        assertEquals(1,next.getInt("runs"));assertEquals(2,next.getJSONObject("prev").getInt("runs"));assertEquals(20261105,next.getJSONObject("prev").getInt("day"))
        assertEquals(20261105,TranscriptionRuns.day(base))
        val beat=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.2",1050,0,null,5,6,"READY","","",12).copy(v31=PhoneStatus.V31(transcription=o.put("runs",next))))
        val t=beat.getJSONObject("transcription")
        assertEquals(42,t.getJSONObject("long48h").getInt("ratePct"));assertEquals(1,t.getJSONObject("runs").getJSONObject("ends").getInt("MORE"))
        assertTrue(PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.2",1050,0,null,5,6,"READY","","",12)).isNull("transcription"))
    }

    @Test fun theStoreGivesTheHeartbeatItsQueueNumbers() {
        recording("long",base-hour,150.0);recording("short",base-minute,20.0)
        val transcriber=CallTranscriber { _,pcm,_ -> listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            s.offerAll(s.candidates(),{null})
            val before=s.heartbeat()
            assertEquals(2,before.getJSONObject("queuedByLen").getInt("unknown"))  // nothing measured yet
            assertEquals(0L,before.getLong("oldestWaitMin"))
            clock+=minute;runner(s,transcriber).run()
            val after=s.heartbeat(clock)
            assertEquals(1,after.getJSONObject("doneByLen48h").getInt("lt1m"));assertEquals(1,after.getJSONObject("doneByLen48h").getInt("m1to3"))
            assertEquals(JSONObject().put("total",1).put("done",1).put("ratePct",100).toString(),after.getJSONObject("long48h").toString())
            assertFalse(after.has("oldestWaitMin"))
        }
    }
}
