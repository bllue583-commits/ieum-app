package kr.callbrief.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class CallTranscriptionUnitTest {
    @Test fun counterpartFromSamsungNames() {
        assertEquals("홍길동",CallTranscriptionRunner.counterpart("통화 녹음 홍길동_251029_101500.m4a"))
        assertEquals("김 부장",CallTranscriptionRunner.counterpart("통화 녹음 김 부장_251029_101500.m4a"))
        assertEquals("010-1234-5678",CallTranscriptionRunner.counterpart("통화 녹음 01012345678_251029_101500.m4a"))
        assertEquals("010-1234-5678",CallTranscriptionRunner.counterpart("010-1234-5678_20251029101500.m4a"))
        assertEquals("0215881234",CallTranscriptionRunner.counterpart("Call recording 02-1588-1234_251029_101500.m4a"))
        assertNull(CallTranscriptionRunner.counterpart("20251029101500.m4a"))
    }

    private fun wav(rate:Int,channels:Int,seconds:Double):ByteArray {
        val frames=(rate*seconds).toInt();val data=ByteBuffer.allocate(frames*channels*2).order(ByteOrder.LITTLE_ENDIAN)
        for(i in 0 until frames){val v=(sin(2*PI*440*i/rate)*12000).toInt().toShort();repeat(channels){data.putShort(v)}}
        val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+data.capacity());h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(channels.toShort());h.putInt(rate);h.putInt(rate*channels*2);h.putShort((channels*2).toShort());h.putShort(16)
        h.put("data".toByteArray());h.putInt(data.capacity())
        return h.array()+data.array()
    }
    @Test fun wavIsDecodedTo16kMonoWithOriginalDuration() {
        for(rate in listOf(8000,16000,44100,48000)) for(ch in listOf(1,2)) {
            val d=PipelineAudioMath.decodeWav(wav(rate,ch,3.0))
            val samples=d.chunks.sumOf{it.second.size}
            assertTrue("rate=$rate ch=$ch samples=$samples",samples in 47000..49000)
            assertEquals(0L,d.chunks.first().first)
            assertTrue(d.durationMs in 2990..3010)
        }
    }
    @Test fun longAudioIsChunkedAtMostTwoMinutesWithIncreasingOffsets() {
        val mono=FloatArray(16000*60*12){(sin(2*PI*300*it/16000)*0.3).toFloat()}
        val chunks=PipelineAudioMath.split(mono)
        assertTrue(chunks.size>=6)
        var expected=0L
        for((offset,pcm) in chunks){assertEquals(expected,offset);assertTrue(pcm.size<=16000*120);expected+=pcm.size*1000L/16000}
        assertEquals(mono.size,chunks.sumOf{it.second.size})
    }

    private class FixedModel:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="ggml-large-v3-turbo-q5_0";override fun verifiedModelFile()=File("model.bin") }
    @Test fun chunkSegmentsAreShiftedToOriginalTime() {
        val transcriber=CallTranscriber{ _,pcm,_ -> listOf(CallTranscript.Segment(0,1000,"가"),CallTranscript.Segment(1000,pcm.size*1000L/16000,"나")) }
        val chunks=listOf(0L to FloatArray(16000*2),300000L to FloatArray(16000*3))
        val shifted=chunks.flatMap{(offset,pcm)->transcriber.transcribe(FixedModel().verifiedModelFile(),pcm){false}.map{CallTranscript.Segment(it.startMs+offset,it.endMs+offset,it.text)}}
        assertEquals(listOf(0L,1000L,300000L,301000L),shifted.map{it.startMs})
        assertEquals(303000L,shifted.last().endMs)
    }

    @Test fun speechOnlyDropsLongSilenceAndKeepsOriginalOffsets() {
        val rate=16000
        fun tone(sec:Double)=FloatArray((rate*sec).toInt()){ (0.3*sin(2*PI*440*it/rate)).toFloat() }
        fun quiet(sec:Double)=FloatArray((rate*sec).toInt())
        val pcm=tone(2.0)+quiet(10.0)+tone(3.0)+quiet(0.5)+tone(1.0)
        val out=PipelineAudioMath.speechOnly(PipelineDecodedAudio(pcm.size*1000L/rate,listOf(0L to pcm),false))
        assertTrue(out.vadApplied)
        val kept=out.chunks.sumOf{it.second.size}/rate.toDouble()
        assertTrue("kept $kept s",kept in 6.0..8.5)
        assertEquals(0L,out.chunks.first().first)
        val mostlySpeech=tone(5.0)+quiet(0.4)+tone(5.0)
        val same=PipelineDecodedAudio(10400,listOf(0L to mostlySpeech),false)
        assertTrue(PipelineAudioMath.speechOnly(same)===same)
    }

    @Test fun halfPointFallsInTheQuietestSpotNearTheMiddle() {
        val rate=16000
        val pcm=FloatArray(rate*60){ (0.3*sin(2*PI*440*it/rate)).toFloat() }
        val quietAt=rate*30+rate  // one second after the exact middle
        for(i in quietAt until quietAt+rate/5) pcm[i]=0f
        val cut=PipelineAudioMath.halfPoint(pcm)
        assertTrue("cut $cut",cut in quietAt..quietAt+rate/5)
    }
}
