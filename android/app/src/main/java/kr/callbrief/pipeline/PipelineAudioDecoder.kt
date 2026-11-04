package kr.callbrief.pipeline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** On-device decoder for call recordings: WAV is parsed directly; m4a/AAC, amr/3gp and anything else
 * MediaExtractor understands go through MediaCodec to PCM. Output is 16 kHz mono, chunked at quiet
 * points into pieces of at most two minutes, with long silences dropped; each piece's offset is the original
 * position of its first sample.
 *
 * F6 memory: the recording is decoded twice, both times as a stream of raw two-minute chunks. The first pass only notes
 * where the speech is ([PipelineSpeechPlan]); the second copies it out piece by piece ([PipelineSpeechAssembler]) and
 * hands each piece to the sink before decoding on. So about one piece of PCM is held, never the whole call (a 30-minute
 * call used to be ~115 MB of 16 kHz floats plus a copy). The pieces are exactly those of decoding all at once.
 * Audio stays in memory on this device; nothing is written to disk or sent anywhere. */
internal class PipelineAudioDecoder(
    /** F3 silence floor; the golden set compares [PipelineAudioMath.SPEECH_FLOOR_LOW]. */
    private val speechFloor:Float=PipelineAudioMath.SPEECH_FLOOR,
    /** F1: channel count and left/right numbers of each decoded recording (first pass only). */
    private val channels:(PipelineChannelStats.Result)->Unit={},
):PipelineAudioSource {
    override fun decode(input:PipelineAudioInput,mime:String,sink:PipelineChunkSink):PipelineAudioInfo {
        check(input.size>0){"PIPELINE_AUDIO_EMPTY"}
        val wav=PipelineAudioMath.isWav(input)
        if(!wav)check(!mime.lowercase().let{it=="audio/wav"||it=="audio/x-wav"||it=="audio/wave"}){"PIPELINE_AUDIO_WAV_HEADER"}
        fun pass(onChunk:PipelineAudioView):PipelineAudioStream = decodeRaw(input,wav,onChunk)
        val plan=PipelineSpeechPlan(floor=speechFloor)
        val first=pass{ms,x,from,to->plan.add(ms,x,from,to)} // nothing is copied in the first pass
        runCatching{channels(first.channelStats.result())}
        val vad=plan.applies()
        var index=0;var raw=0
        val assembler=if(vad) PipelineSpeechAssembler(plan){ms,x->sink.chunk(index++,ms,x)} else null
        pass{ms,x,from,to->
            plan.verify(raw,x,from,to)
            if(assembler!=null)assembler.raw(raw,x,from,to) else sink.chunk(index++,ms,x.copyOfRange(from,to))
            raw++
        }
        plan.verifyCount(raw);assembler?.finish()
        return PipelineAudioInfo(first.durationMs,vad,index)
    }

    /** One pass without silence skipping: raw two-minute chunks as they are decoded (device tests measure the old way with it). */
    internal fun decodeRaw(input:PipelineAudioInput,wav:Boolean=PipelineAudioMath.isWav(input),onChunk:PipelineAudioView):PipelineAudioStream =
        if(wav) PipelineAudioMath.streamWav(input,onChunk) else decodeWithMediaCodec(input,onChunk)

    /** MediaExtractor reads the recording where it is (no copy of the file). */
    private class Source(private val input:PipelineAudioInput):MediaDataSource() {
        override fun readAt(position:Long,buffer:ByteArray,offset:Int,size:Int):Int=if(size==0)0 else input.readAt(position,buffer,offset,size)
        override fun getSize():Long=input.size
        override fun close(){}
    }

    private fun decodeWithMediaCodec(input:PipelineAudioInput,onChunk:PipelineAudioView):PipelineAudioStream {
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try {
            extractor.setDataSource(Source(input))
            val track=(0 until extractor.trackCount).firstOrNull{
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            } ?: error("PIPELINE_AUDIO_NO_TRACK")
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            val mime=format.getString(MediaFormat.KEY_MIME)!!
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm=false
            var stream:PipelineAudioStream?=null
            codec=MediaCodec.createDecoderByType(mime)
            codec.configure(format,null,null,0)
            codec.start()
            val info=MediaCodec.BufferInfo()
            var inputDone=false;var outputDone=false;var idle=0
            var scratch=ByteArray(0)
            while(!outputDone){
                if(!inputDone){
                    val i=codec.dequeueInputBuffer(TIMEOUT_US)
                    if(i>=0){
                        val buffer=codec.getInputBuffer(i)!!
                        val n=extractor.readSampleData(buffer,0)
                        if(n<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else{codec.queueInputBuffer(i,0,n,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                val o=codec.dequeueOutputBuffer(info,TIMEOUT_US)
                when {
                    o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f=codec.outputFormat
                        rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm=f.containsKey(MediaFormat.KEY_PCM_ENCODING)&&f.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_FLOAT
                        check(stream==null){"PIPELINE_AUDIO_FORMAT_CHANGED_MIDSTREAM"}
                    }
                    o>=0 -> {
                        idle=0
                        if(info.size>0){
                            val s=stream ?: PipelineAudioStream(rate,channels,onChunk=onChunk).also{stream=it}
                            val buffer=codec.getOutputBuffer(o)!!
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            if(floatPcm){
                                val f=FloatArray(info.size/4);buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);s.push(f)
                            } else {
                                if(scratch.size<info.size)scratch=ByteArray(info.size)
                                buffer.get(scratch,0,info.size);s.pushPcm16(scratch,0,info.size)
                            }
                        }
                        codec.releaseOutputBuffer(o,false)
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)outputDone=true
                    }
                    o==MediaCodec.INFO_TRY_AGAIN_LATER -> if(inputDone&&++idle>MAX_IDLE_POLLS)error("PIPELINE_AUDIO_DECODER_STALLED")
                }
            }
            scratch.fill(0)
            return (stream ?: error("PIPELINE_AUDIO_NO_SAMPLES")).also{it.finish()}
        } finally {
            runCatching{codec?.stop()};runCatching{codec?.release()};runCatching{extractor.release()}
        }
    }

    companion object {
        private const val TIMEOUT_US=10_000L; private const val MAX_IDLE_POLLS=500
        /** F1 settings (the heartbeat reads them by these names): channel count of the last recording, and the running
         * mean of left/right correlation and energy ratio over the stereo recordings measured so far. */
        const val AUDIO_CHANNELS="audioChannels"
        const val AUDIO_CHANNEL_CORR="audioChannelCorr"
        const val AUDIO_CHANNEL_ENERGY="audioChannelEnergyRatio"
        const val AUDIO_CHANNEL_MEASURED="audioChannelMeasured"
        private fun round3(x:Double)=(x*1000).roundToLong()/1000.0
        /** Adds one recording's numbers to the settings snapshot [s] (in place). */
        fun accumulate(s:JSONObject,r:PipelineChannelStats.Result):JSONObject {
            if(r.channels<1)return s
            s.put(AUDIO_CHANNELS,r.channels)
            val corr=r.correlation ?: return s
            val n=s.optLong(AUDIO_CHANNEL_MEASURED,0)+1
            fun mean(key:String,x:Double):Double { val old=s.optDouble(key,Double.NaN).takeUnless{it.isNaN()} ?: x;return round3(old+(x-old)/n) }
            s.put(AUDIO_CHANNEL_CORR,mean(AUDIO_CHANNEL_CORR,corr))
            r.energyRatio?.let{s.put(AUDIO_CHANNEL_ENERGY,mean(AUDIO_CHANNEL_ENERGY,it))}
            s.put(AUDIO_CHANNEL_MEASURED,n)
            return s
        }
    }
}
