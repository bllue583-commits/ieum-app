package kr.callbrief.pipeline

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** F6: the streaming decoder gives exactly the pieces of the old whole-recording code (split + speechOnly). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PipelineAudioStreamingTest {
    private val rate=16000

    /** The v2.3 speechOnly, kept here verbatim as the reference (it held every piece and re-copied it on each merge). */
    private fun legacySpeechOnly(audio:PipelineDecodedAudio,keepGapMs:Long=1500,padMs:Long=300,minSavedRatio:Double=0.1,floorLevel:Float=0.006f):PipelineDecodedAudio {
        val win=rate*30/1000
        data class Piece(val origin:Long,val samples:FloatArray)
        val pieces=ArrayList<Piece>()
        var total=0L
        for((offset,pcm) in audio.chunks){
            total+=pcm.size
            val rms=PipelineAudioMath.rmsWindows(pcm,rate,30)
            if(rms.isEmpty())continue
            val sorted=rms.sorted();val floor=sorted[rms.size/5];val loud=sorted[rms.size*9/10]
            val threshold=max(floorLevel,min(floor*3,loud*0.3f))
            val gap=(keepGapMs/30).toInt();val pad=(padMs/30).toInt()
            var i=0
            while(i<rms.size){
                if(rms[i]<threshold){i++;continue}
                var end=i;var quiet=0;var j=i
                while(j<rms.size&&quiet<=gap){ if(rms[j]>=threshold){end=j;quiet=0} else quiet++; j++ }
                val a=max(0,(i-pad)*win);val b=min(pcm.size,(end+1+pad)*win)
                if(pieces.isNotEmpty()&&pieces.last().let{it.origin+it.samples.size>=offset*rate/1000+a}) {
                    val last=pieces.removeAt(pieces.size-1);val lastEnd=last.origin+last.samples.size;val start=offset*rate/1000
                    val extra=pcm.copyOfRange(max(a,(lastEnd-start).toInt()),b);pieces.add(Piece(last.origin,last.samples+extra))
                } else pieces.add(Piece(offset*rate/1000+a,pcm.copyOfRange(a,b)))
                i=end+1+pad
            }
        }
        val kept=pieces.sumOf{it.samples.size.toLong()}
        if(total==0L||kept==0L||kept>total*(1-minSavedRatio))return audio
        val maxLen=(rate*PipelineAudioMath.MAX_CHUNK_MS/1000).toInt()
        val out=ArrayList<Pair<Long,FloatArray>>()
        var buf=PipelineAudioFloats(1024);var bufOrigin=-1L
        fun flush(){ if(buf.size>0){out+=(bufOrigin*1000/rate) to buf.toArray()};buf=PipelineAudioFloats(1024);bufOrigin=-1L }
        for(p in pieces){
            if(p.samples.size>maxLen){ flush();PipelineAudioMath.split(p.samples,rate).forEach{(ms,x)->out+=(p.origin*1000/rate+ms) to x};continue }
            if(buf.size+p.samples.size>maxLen)flush()
            if(bufOrigin<0)bufOrigin=p.origin
            buf.add(p.samples)
        }
        flush()
        return PipelineDecodedAudio(audio.durationMs,out,vadApplied=true)
    }

    private fun assertSame(expected:List<Pair<Long,FloatArray>>,actual:List<Pair<Long,FloatArray>>,what:String) {
        assertEquals("$what: piece count",expected.size,actual.size)
        expected.zip(actual).forEachIndexed { i,(e,a) ->
            assertEquals("$what: offset of piece $i",e.first,a.first)
            assertArrayEquals("$what: samples of piece $i",e.second,a.second,0f)
        }
    }

    /** Call-like 16 kHz audio: tone+noise bursts with pauses, optionally one stretch of speech longer than a piece. */
    private fun callLike(seed:Int,seconds:Int,longSpeechSeconds:Int=0,level:Double=1.0):FloatArray {
        val r=Random(seed);val out=PipelineAudioFloats(rate*seconds)
        var t=0
        fun burst(len:Int){ val f=150+r.nextInt(400);val a=(0.05+r.nextDouble()*0.25)*level
            out.add(FloatArray(len){ (a*sin(2*PI*f*(t+it)/rate)+(r.nextDouble()-0.5)*0.02*level).toFloat() });t+=len }
        fun pause(len:Int){ val a=0.0005+r.nextDouble()*0.0025;out.add(FloatArray(len){ ((r.nextDouble()-0.5)*a).toFloat() });t+=len }
        if(longSpeechSeconds>0){ // continuous: short gaps only (never 1.5 s)
            while(t<rate*longSpeechSeconds){ burst(rate/2+r.nextInt(rate*4));pause(r.nextInt(rate/2)) }
        }
        while(t<rate*seconds){ burst(rate/2+r.nextInt(rate*8));pause(rate/5+r.nextInt(rate*7)) }
        return out.toArray()
    }

    @Test fun chunkerCutsExactlyLikeSplitForAnyAddSize() {
        val pcm=callLike(1,7*60+13)
        val expected=PipelineAudioMath.split(pcm)
        for(step in listOf(1013,16000,rate*125,pcm.size)) {
            val got=ArrayList<Pair<Long,FloatArray>>()
            val c=PipelineChunker{s,x,from,to->got+=(s*1000/rate) to x.copyOfRange(from,to)}
            var i=0;while(i<pcm.size){val n=min(step,pcm.size-i);c.add(pcm,i,i+n);i+=n}
            c.finish()
            assertSame(expected,got,"add size $step")
        }
    }

    @Test fun streamOfStereo44kGivesSplitOfTheWholeResampledSignal() {
        val mono44=FloatArray(44100*300){ (0.2*sin(2*PI*220*it/44100)*(if((it/44100)%9<6)1 else 0)).toFloat() }
        val stereo=FloatArray(mono44.size*2){mono44[it/2]}
        val whole=PipelineAudioResampler(44100,rate).let{ it.push(mono44)+it.finish() }
        val got=ArrayList<Pair<Long,FloatArray>>()
        val s=PipelineAudioStream(44100,2){ms,x,from,to->got+=ms to x.copyOfRange(from,to)}
        var i=0;while(i<stereo.size){val n=min(7777,stereo.size-i);s.push(stereo.copyOfRange(i,i+n));i+=n}
        assertEquals(300_000L,s.finish())
        assertSame(PipelineAudioMath.split(whole),got,"44.1 kHz stereo")
        assertEquals(2,s.channelStats.result().channels)
    }

    private fun streamingSpeechOnly(audio:PipelineDecodedAudio,floor:Float=PipelineAudioMath.SPEECH_FLOOR)=PipelineAudioMath.speechOnly(audio,floor=floor)

    @Test fun speechOnlyMatchesTheOldCodeOnCallLikeAudio() {
        val cases=listOf(
            "short call" to callLike(2,95),
            "twelve minutes" to callLike(3,12*60),
            "long speech first" to callLike(4,9*60,longSpeechSeconds=200),
            "quiet speaker" to callLike(5,6*60,level=0.05),
            "all silence" to FloatArray(rate*20),
            "continuous tone" to FloatArray(rate*130){ (0.3*sin(2*PI*440*it/rate)).toFloat() })
        for((name,pcm) in cases) {
            val audio=PipelineDecodedAudio(pcm.size*1000L/rate,PipelineAudioMath.split(pcm),false)
            for(floor in listOf(PipelineAudioMath.SPEECH_FLOOR,PipelineAudioMath.SPEECH_FLOOR_LOW)) {
                val old=legacySpeechOnly(audio,floorLevel=floor);val new=streamingSpeechOnly(audio,floor)
                assertEquals("$name vadApplied",old.vadApplied,new.vadApplied)
                assertSame(old.chunks,new.chunks,"$name floor $floor")
                if(!old.vadApplied)assertTrue(new===audio)
            }
        }
    }

    @Test fun overLongSpeechIsCutLikeSplit() {
        val pcm=callLike(6,8*60,longSpeechSeconds=330)
        val audio=PipelineDecodedAudio(pcm.size*1000L/rate,PipelineAudioMath.split(pcm),false)
        val old=legacySpeechOnly(audio)
        assertTrue(old.vadApplied);assertTrue("needs a piece cut on its own",old.chunks.any{it.second.size>rate*110})
        assertSame(old.chunks,streamingSpeechOnly(audio).chunks,"long speech")
    }

    @Test fun silenceFloorIsOneConstantAndChangesOnlyQuietSpeech() {
        assertEquals(0.006f,PipelineAudioMath.SPEECH_FLOOR);assertEquals(0.0025f,PipelineAudioMath.SPEECH_FLOOR_LOW)
        // Speech at rms ~0.0035 over digital silence: below the default floor, above the low one.
        val quiet=FloatArray(rate*60){ i -> if((i/rate)%10<3) (0.005*sin(2*PI*300*i/rate)).toFloat() else 0f }
        val audio=PipelineDecodedAudio(60_000,PipelineAudioMath.split(quiet),false)
        assertTrue(PipelineAudioMath.speechOnly(audio)===audio) // nothing counts as speech: kept as it is
        val low=PipelineAudioMath.speechOnly(audio,floor=PipelineAudioMath.SPEECH_FLOOR_LOW)
        assertTrue(low.vadApplied);assertTrue(low.chunks.sumOf{it.second.size} in rate*18..rate*26)
    }

    private fun wav(pcm:FloatArray,sampleRate:Int=rate,channels:Int=1):ByteArray {
        val data=ByteBuffer.allocate(pcm.size*2).order(ByteOrder.LITTLE_ENDIAN)
        for(v in pcm)data.putShort((v.coerceIn(-1f,1f)*32767).toInt().toShort())
        val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+data.capacity());h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(channels.toShort());h.putInt(sampleRate);h.putInt(sampleRate*channels*2);h.putShort((channels*2).toShort());h.putShort(16)
        h.put("data".toByteArray());h.putInt(data.capacity())
        return h.array()+data.array()
    }

    /** Counts reads so the test sees that decoding goes on after the first piece was handed over. */
    private class CountingInput(bytes:ByteArray):PipelineAudioInput {
        private val inner=PipelineBytesInput(bytes);var reads=0
        override val size get()=inner.size
        override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int { reads++;return inner.readAt(position,buffer,offset,length) }
    }

    @Test fun decoderStreamsPiecesOneByOneAndMatchesWholeDecoding() {
        val pcm=callLike(7,11*60)
        val bytes=wav(pcm)
        val whole=PipelineAudioMath.decodeWav(bytes)
        val expected=legacySpeechOnly(whole)
        assertTrue(expected.vadApplied)
        val input=CountingInput(bytes)
        val got=ArrayList<Pair<Long,FloatArray>>();val indices=ArrayList<Int>();var readsAtFirst=-1
        val info=PipelineAudioDecoder().decode(input,"audio/wav") { index,offset,x -> if(readsAtFirst<0)readsAtFirst=input.reads;indices+=index;got+=offset to x }
        assertSame(expected.chunks,got,"decoder")
        assertEquals(got.indices.toList(),indices);assertEquals(got.size,info.chunks)
        assertTrue(info.vadApplied);assertEquals(whole.durationMs,info.durationMs)
        assertTrue("the first piece came before the second pass had read the file",input.reads>readsAtFirst+10)
    }

    @Test fun decoderWithoutSilenceGivesTheRawPieces() {
        val pcm=FloatArray(rate*300){ (0.3*sin(2*PI*440*it/rate)).toFloat() }
        val bytes=wav(pcm);val whole=PipelineAudioMath.decodeWav(bytes)
        val got=ArrayList<Pair<Long,FloatArray>>()
        val info=PipelineAudioDecoder().decode(PipelineBytesInput(bytes),"audio/wav"){_,o,x->got+=o to x}
        assertFalse(info.vadApplied);assertSame(whole.chunks,got,"raw");assertEquals(3,got.size)
    }

    @Test fun aStopInsideThePieceSinkEndsDecodingAndPropagates() {
        val bytes=wav(callLike(8,6*60));var calls=0
        val e=runCatching { PipelineAudioDecoder().decode(PipelineBytesInput(bytes),"audio/wav"){_,_,_->if(++calls==2)throw IllegalStateException("STOP")} }.exceptionOrNull()
        assertEquals("STOP",e?.message);assertEquals(2,calls)
    }

    @Test fun channelNumbersAreMeasuredAndAccumulated() {
        val r=Random(9)
        fun stats(left:(Int)->Float,right:(Int)->Float):PipelineChannelStats.Result {
            val st=PipelineChannelStats();val x=FloatArray(2*rate*5){ if(it%2==0)left(it/2) else right(it/2) };st.add(x,x.size,2);return st.result()
        }
        val noise=FloatArray(rate*5){ (r.nextDouble()-0.5).toFloat() };val other=FloatArray(rate*5){ (r.nextDouble()-0.5).toFloat() }
        val same=stats({noise[it]},{noise[it]});assertEquals(1.0,same.correlation!!,1e-6);assertEquals(1.0,same.energyRatio!!,1e-6)
        val split=stats({noise[it]},{other[it]*0.5f});assertEquals(0.0,split.correlation!!,0.05);assertEquals(0.25,split.energyRatio!!,0.03)
        val oneSilent=stats({noise[it]},{0f});assertNull(oneSilent.correlation)
        val mono=PipelineChannelStats().apply{add(noise,noise.size,1)}.result();assertEquals(1,mono.channels);assertNull(mono.correlation)

        val s=JSONObject()
        PipelineAudioDecoder.accumulate(s,mono);assertEquals(1,s.getInt(PipelineAudioDecoder.AUDIO_CHANNELS));assertFalse(s.has(PipelineAudioDecoder.AUDIO_CHANNEL_CORR))
        PipelineAudioDecoder.accumulate(s,same);PipelineAudioDecoder.accumulate(s,split)
        assertEquals(2,s.getInt(PipelineAudioDecoder.AUDIO_CHANNELS));assertEquals(2L,s.getLong(PipelineAudioDecoder.AUDIO_CHANNEL_MEASURED))
        assertEquals(0.5,s.getDouble(PipelineAudioDecoder.AUDIO_CHANNEL_CORR),0.03);assertEquals(0.62,s.getDouble(PipelineAudioDecoder.AUDIO_CHANNEL_ENERGY),0.03)
        // Key names are what the heartbeat reads.
        assertEquals("audioChannels",PipelineAudioDecoder.AUDIO_CHANNELS);assertEquals("audioChannelCorr",PipelineAudioDecoder.AUDIO_CHANNEL_CORR)
    }

    @Test fun decoderReportsChannelsOfAStereoWav() {
        val mono=callLike(10,40);val stereo=FloatArray(mono.size*2){mono[it/2]}
        var seen:PipelineChannelStats.Result?=null;var calls=0
        PipelineAudioDecoder(channels={seen=it;calls++}).decode(PipelineBytesInput(wav(stereo,channels=2)),"audio/wav"){_,_,_->}
        assertEquals(1,calls);assertEquals(2,seen!!.channels);assertEquals(1.0,seen!!.correlation!!,1e-6)
    }

    @Test fun vaultStreamIsReadIntoOneExactArray() {
        val data=ByteArray(100_000){it.toByte()}
        for(expected in listOf(100_000L,1L,0L,250_000L)) {
            val input=PipelineBytesInput.read(java.io.ByteArrayInputStream(data),expected)
            assertEquals(100_000L,input.size)
            val back=ByteArray(100_000);var n=0;while(n<back.size){n+=input.readAt(n.toLong(),back,n,minOf(4096,back.size-n))}
            assertArrayEquals(data,back);assertEquals(-1,input.readAt(100_000,back,0,1))
        }
        val bytes=byteArrayOf(1,2,3);PipelineBytesInput(bytes).close();assertArrayEquals(byteArrayOf(0,0,0),bytes) // wiped
    }

    @Test fun wavHeaderIsReadThroughTheInput() {
        val bytes=wav(FloatArray(rate*3),44100,2)
        val info=PipelineAudioMath.parseWavHeader(PipelineBytesInput(bytes))
        assertEquals(44100,info.sampleRate);assertEquals(2,info.channels);assertEquals(44L,info.dataOffset);assertEquals(rate*3*2L,info.dataBytes)
        assertFalse(PipelineAudioMath.isWav(PipelineBytesInput(ByteArray(8))))
    }
}
