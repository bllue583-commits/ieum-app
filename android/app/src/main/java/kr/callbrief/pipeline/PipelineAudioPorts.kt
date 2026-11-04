package kr.callbrief.pipeline
internal interface PipelineSttModel { val modelSha256:String; val runtimeSha256:String; val modelName:String /* "ggml-large-v3-turbo-q5_0" */; fun verifiedModelFile():java.io.File? /* null when not READY */ }

/** Random-access bytes of one recording (the decoder reads it twice: see [PipelineAudioSource]). */
internal interface PipelineAudioInput:AutoCloseable {
    val size:Long
    /** Up to [length] bytes at [position]; -1 at or past the end. */
    fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int
    override fun close() {}
}
/** A recording held as one exactly sized array (the vault's verified bytes copied once); [close] wipes it. */
internal class PipelineBytesInput(private val bytes:ByteArray,private val length:Int=bytes.size):PipelineAudioInput {
    init { require(length in 0..bytes.size){"PIPELINE_AUDIO_INPUT"} }
    override val size:Long get()=length.toLong()
    override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
        if(position>=this.length)return -1
        val n=minOf(length.toLong(),this.length-position).toInt()
        System.arraycopy(bytes,position.toInt(),buffer,offset,n);return n
    }
    override fun close() { bytes.fill(0) }
    companion object {
        /** Reads [input] to its end into one array of [expected] bytes (grown only if the stream is longer), no stream-buffer copies. */
        fun read(input:java.io.InputStream,expected:Long):PipelineBytesInput {
            var buf=ByteArray(expected.coerceIn(1L,Int.MAX_VALUE-8L).toInt());var n=0
            while(true){
                if(n==buf.size){
                    // Full: usually the stream has ended exactly here, so look for one more byte before growing
                    // (growing first doubled every vault copy: 15 MB read held 30 MB).
                    val next=input.read();if(next<0)break
                    check(buf.size<Int.MAX_VALUE-8){"PIPELINE_AUDIO_TOO_LARGE"};val bigger=buf.copyOf(minOf(Int.MAX_VALUE-8L,buf.size*2L).toInt());buf.fill(0);buf=bigger
                    buf[n++]=next.toByte();continue
                }
                val r=input.read(buf,n,buf.size-n);if(r<0)break;n+=r
            }
            return PipelineBytesInput(buf,n)
        }
    }
}

/** One 16 kHz mono piece, in order: [index] 0,1,2..., [offsetMs] the original position of its first sample. */
internal fun interface PipelineChunkSink { fun chunk(index:Int,offsetMs:Long,pcm:FloatArray) }
internal class PipelineAudioInfo(val durationMs:Long,val vadApplied:Boolean,val chunks:Int)
internal interface PipelineAudioSource {
    /** Streams [input] as 16 kHz mono pieces to [sink] while holding about one piece of PCM (never the whole recording).
     * The sink may run Whisper on each piece before the next is decoded. */
    fun decode(input:PipelineAudioInput,mime:String,sink:PipelineChunkSink):PipelineAudioInfo
}
/** A short recording fully in memory (tests, the settings screen check): (original start ms, samples) pieces. */
internal class PipelineDecodedAudio(val durationMs:Long, val chunks:List<Pair<Long,FloatArray>>, val vadApplied:Boolean)
