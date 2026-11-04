package kr.callbrief.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** F6 memory on the JVM: a 30-minute WAV made on the fly (nothing held but what the decoder keeps) goes through the
 * streaming decoder; at every piece the sink collects garbage on the decoding thread itself and reads the live heap, so
 * the number is exactly what is held. The whole call as 16 kHz floats would be 115 MB; one piece plus the chunker's
 * buffer is about 15 MB. Also: reading the vault copy into memory takes one array of its size, not double. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PipelineAudioMemoryTest {
    private class SynthWav(val rate:Int,val seconds:Int):PipelineAudioInput {
        val frames=rate.toLong()*seconds
        override val size:Long=44+frames*2
        private val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt((size-8).toInt());put("WAVE".toByteArray());put("fmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(rate);putInt(rate*2);putShort(2);putShort(16);put("data".toByteArray());putInt((frames*2).toInt())
        }.array()
        private fun sample(i:Long):Short { val t=i%(rate*7L);return if(t<rate*3L) (sin(i*0.07)*8000+((i*7919)%400-200)).toInt().toShort() else (((i*7919)%60)-30).toInt().toShort() }
        override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
            if(position>=size)return -1
            val n=minOf(length.toLong(),size-position).toInt()
            for(k in 0 until n){ val p=position+k
                buffer[offset+k]=if(p<44) header[p.toInt()] else { val s=sample((p-44)/2).toInt();if((p-44)%2==0L)(s and 0xff).toByte() else (s shr 8).toByte() } }
            return n
        }
        override fun close(){}
    }
    private fun used()=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
    private fun live():Long { repeat(2){System.gc()};return used() }
    @Test fun thirtyMinuteCallHoldsAboutOnePiece() {
        for(rate in listOf(16000,44100)) {
            val base=live();var peak=0L;var pieces=0
            PipelineAudioDecoder().decode(SynthWav(rate,1800),"audio/wav"){_,_,pcm-> pieces++;peak=maxOf(peak,live()-base);assertTrue(pcm.size<=16000*120) }
            println("F6 memory: rate=$rate pieces=$pieces held=${peak/1048576} MB")
            assertTrue("held ${peak/1048576} MB at a piece for a 30-minute call (whole call would be 115 MB)",peak<40L*1048576)
        }
    }
    @Test fun vaultCopyIsReadIntoOneArrayOfItsSize() {
        val bytes=ByteArray(15_000_000){(it%251).toByte()}
        val base=live()
        val input=PipelineBytesInput.read(bytes.inputStream(),bytes.size.toLong())
        val held=live()-base
        assertTrue(input.size==bytes.size.toLong())
        println("vault copy: ${bytes.size/1048576} MB read, ${held/1048576} MB held")
        assertTrue("reading ${bytes.size} bytes held $held",held<bytes.size*1.3)
        val b=ByteArray(16);assertTrue(input.readAt(bytes.size-16L,b,0,16)==16&&b.last()==bytes.last())
        input.close()
        // A stream longer than expected still reads whole.
        val longer=PipelineBytesInput.read(bytes.inputStream(),1000);assertTrue(longer.size==bytes.size.toLong());longer.close()
    }
}
