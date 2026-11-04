package kr.callbrief.pipeline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/** On-device decoder for call recordings: WAV is parsed directly; m4a/AAC, amr/3gp and anything else
 * MediaExtractor understands go through MediaCodec to PCM. Output is 16 kHz mono, chunked at quiet
 * points into pieces of at most two minutes. Long silences are dropped ([PipelineAudioMath.speechOnly]); each chunk's offset is the original
 * position of its first sample.
 * Audio stays in memory on this device; nothing is written to disk or sent anywhere. */
internal class PipelineAudioDecoder:PipelineAudioSource {
    override fun decode(bytes:ByteArray,mime:String):PipelineDecodedAudio {
        check(bytes.isNotEmpty()){"PIPELINE_AUDIO_EMPTY"}
        if(PipelineAudioMath.isWav(bytes))return PipelineAudioMath.speechOnly(PipelineAudioMath.decodeWav(bytes))
        check(!mime.lowercase().let{it=="audio/wav"||it=="audio/x-wav"||it=="audio/wave"}){"PIPELINE_AUDIO_WAV_HEADER"}
        return PipelineAudioMath.speechOnly(decodeWithMediaCodec(bytes))
    }

    private class Source(private val bytes:ByteArray):MediaDataSource() {
        override fun readAt(position:Long,buffer:ByteArray,offset:Int,size:Int):Int {
            if(position>=bytes.size)return -1
            val n=minOf(size.toLong(),bytes.size-position).toInt()
            System.arraycopy(bytes,position.toInt(),buffer,offset,n);return n
        }
        override fun getSize():Long=bytes.size.toLong()
        override fun close(){}
    }

    private fun decodeWithMediaCodec(bytes:ByteArray):PipelineDecodedAudio {
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try {
            extractor.setDataSource(Source(bytes))
            val track=(0 until extractor.trackCount).firstOrNull{
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            } ?: error("PIPELINE_AUDIO_NO_TRACK")
            extractor.selectTrack(track)
            val input=extractor.getTrackFormat(track)
            val mime=input.getString(MediaFormat.KEY_MIME)!!
            var rate=input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm=false
            var stream:PipelineAudioStream?=null
            codec=MediaCodec.createDecoderByType(mime)
            codec.configure(input,null,null,0)
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
                            val s=stream ?: PipelineAudioStream(rate,channels).also{stream=it}
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
            return (stream ?: error("PIPELINE_AUDIO_NO_SAMPLES")).finish()
        } finally {
            runCatching{codec?.stop()};runCatching{codec?.release()};runCatching{extractor.release()}
        }
    }

    private companion object { const val TIMEOUT_US=10_000L; const val MAX_IDLE_POLLS=500 }
}
