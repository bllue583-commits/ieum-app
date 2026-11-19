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

/** v4.1 r22 review L: the yield of a run stopped by something it is not to blame for (r21's charger stop is gone, the
 * foreground service's limit is the same path): the piece under way is thrown away and done again next time, the finished
 * pieces stay, no stall and no attempt are counted; stopped before any piece is a yield too. Generated audio only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R22YieldTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L
    private val base=LocalDate.of(2026,11,14).atTime(22,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private var clock=base
    private val rate=16000
    private val wavs=HashMap<String,ByteArray>()

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        CallTranscriptionStore(context){ clock }.close()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER);null } }
    }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun wav(seconds:Double):ByteArray {
        val n=(rate*seconds).toInt();val out=ByteArray(44+n*2)
        val h=ByteBuffer.wrap(out,0,44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(n*2)
        for(t in 0 until n){ val v=(sin(2*PI*330*t/rate)*6000).toInt();out[44+2*t]=(v and 0xff).toByte();out[45+2*t]=((v shr 8) and 0xff).toByte() }
        return out
    }
    private fun recording(id:String,startedMs:Long,seconds:Double):CallTranscriptionStore.Key {
        wavs[id]=wav(seconds)
        val sha="%064x".format(7);val cipher="%064x".format(1007);val bytes=(seconds*1000).toLong()
        port().use { p -> p.transaction {
            val o=GeneralLocalStore.OWNER
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/wav',?,?,?,?,1,1,1,?)",
                id,o,"doc_$id","$id.wav",bytes,startedMs,startedMs,startedMs,startedMs)
            p.execute("INSERT INTO content_blobs VALUES(?,?)",sha,bytes)
            p.execute("INSERT INTO source_versions VALUES(?,1,?,?,?,?)",id,sha,bytes,startedMs,"obs_$id")
            p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree',?,?,'sig',?,?)",id,"doc_$id","content://generated/document/$id",sha,null)
            p.execute("INSERT INTO local_visibility(recording_id) VALUES(?)",id)
            p.execute("INSERT INTO vault2_refs VALUES(?,1,?,?,?,?,?,?)",id,o,sha,bytes,GeneralLocalStore.ALIAS,cipher,bytes+28);null } }
        return CallTranscriptionStore.Key(id,1,sha)
    }
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=File("m") }
    private val sink=object:CallTranscriptSink { override fun onTranscript(transcript:CallTranscript){} }

    @Test fun aRunStopsMidPieceKeepsTheFinishedOneAndCountsNoStall() {
        val key=recording("long",base-60*minute,5*60.0)
        var stop=false;var pieces=0
        val killed=mutableListOf<Long>();var yielded=0
        val events=object:CallTranscriptionRunner.Events { override fun killed(ms:Long) { killed+=ms };override fun yielded() { yielded++ } }
        // The second piece: a stop the run is not to blame for comes while it runs (the foreground service's limit); the
        // native run returns once cancelled (here: once stopped).
        val t=CallTranscriber { _,pcm,stopped -> pieces++
            if(pieces==2) { stop=true;val until=System.currentTimeMillis()+5_000;while(!stopped() && System.currentTimeMillis()<until) Thread.sleep(5) }
            listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val pass=CallTranscriptionRunner(s,model,PipelineAudioDecoder(),t,read={ c -> PipelineBytesInput(wavs.getValue(c.key.recordingId).copyOf()) },
                skipReason={null},sink={sink},stopped={ stop },now={clock},yieldStop={ stop },events=events).run()
            assertTrue(stop);assertEquals("YIELD",pass.state);assertEquals(2,pieces)
            assertEquals("the finished piece stays",1,s.parts(key).size)
            assertEquals("waits for the next run","QUEUED",s.rows().single().second);assertEquals("STOPPED",s.rows().single().third.first)
            assertEquals("no stall against the piece",0,s.totalStalls(key));assertEquals("the attempt given back",0,s.attempts(key))
            assertEquals("the piece under way was thrown away",1,killed.size);assertEquals(1,yielded)
        }
    }

    /** Review L3: stopped before a piece is taken is a yield, not a stop counted as "other". */
    @Test fun stoppedBeforeAnyPieceIsAYield() {
        recording("early",base-60*minute,30.0)
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val pass=CallTranscriptionRunner(s,model,PipelineAudioDecoder(),CallTranscriber { _,_,_ -> error("no piece") },read={ c -> PipelineBytesInput(wavs.getValue(c.key.recordingId).copyOf()) },
                skipReason={null},sink={sink},stopped={ true },now={clock},yieldStop={ true }).run()
            assertEquals("YIELD",pass.state)
        }
    }

}
