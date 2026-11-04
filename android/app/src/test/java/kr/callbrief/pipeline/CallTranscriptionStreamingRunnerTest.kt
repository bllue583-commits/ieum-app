package kr.callbrief.pipeline

import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** F6 in the runner: pieces are transcribed while the recording is still being decoded, and a stop part way resumes
 * with the same pieces (same ids, same offsets) as an uninterrupted run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallTranscriptionStreamingRunnerTest:ProtectedTextHostTestBase() {
    private val rate=16000
    private fun wav(seconds:Int):ByteArray {
        val r=kotlin.random.Random(11);val n=rate*seconds;val data=ByteBuffer.allocate(n*2).order(ByteOrder.LITTLE_ENDIAN)
        var t=0
        while(t<n){ val speak=rate*(1+r.nextInt(6));val f=200+r.nextInt(300);for(i in 0 until speak){if(t<n)data.putShort((sin(2*PI*f*t/rate)*6000).toInt().toShort());t++}
            val quiet=rate/4+r.nextInt(rate*4);for(i in 0 until quiet){if(t<n)data.putShort((r.nextInt(40)-20).toShort());t++} }
        val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16)
        h.put("data".toByteArray());h.putInt(n*2)
        return h.array()+data.array()
    }
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=File("m") }

    @Test fun aStoppedLongRecordingResumesWithTheSamePieces() {
        val bytes=wav(11*60)
        val seen=mutableListOf<Int>() // pcm sizes in the order transcribed
        val transcriber=CallTranscriber { _,pcm,_ -> seen+=pcm.size;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"조각${pcm.size}")) }
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { store ->
            var stopAfter=Int.MAX_VALUE
            fun runner()=CallTranscriptionRunner(store,model,PipelineAudioDecoder(),transcriber,read={error("unused")},skipReason={null},sink={null},stopped={seen.size>stopAfter}) // the stop lands while a piece runs: that piece is not kept
            // Reference: one uninterrupted run.
            val refKey=CallTranscriptionStore.Key("ref",1,"a".repeat(64))
            val (refInfo,reference)=runner().transcribeRecording(refKey,PipelineBytesInput(bytes),"audio/wav",File("m"))
            val all=seen.toList();seen.clear()
            assertTrue(refInfo.vadApplied);assertEquals(all.size,refInfo.chunks);assertTrue("several pieces",all.size>=5)
            // Stopped while the fourth piece runs (three kept), then resumed.
            val key=CallTranscriptionStore.Key("long",1,"a".repeat(64))
            stopAfter=3
            try { runner().transcribeRecording(key,PipelineBytesInput(bytes),"audio/wav",File("m"));fail() } catch(_:CallTranscriptionRunner.Stopped) {}
            assertEquals(3,store.parts(key).size)
            stopAfter=Int.MAX_VALUE;seen.clear()
            val (_,resumed)=runner().transcribeRecording(key,PipelineBytesInput(bytes),"audio/wav",File("m"))
            assertEquals(all.drop(3),seen) // only the pieces not finished before
            assertEquals(reference,resumed)
        }
    }
}
