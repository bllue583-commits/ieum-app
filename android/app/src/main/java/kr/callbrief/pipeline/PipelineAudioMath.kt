package kr.callbrief.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Growable float buffer. */
internal class PipelineAudioFloats(initial:Int=16384,/** growth stops here (more is still taken when needed) */ private val limit:Int=Int.MAX_VALUE-8) {
    private var data=FloatArray(max(16,min(initial,limit)));var size=0;private set
    fun add(values:FloatArray,from:Int=0,to:Int=values.size) {
        val n=to-from;if(n<=0)return
        if(size+n>data.size)data=data.copyOf(max(size+n,(data.size.toLong()*2).coerceAtMost(limit.toLong()).toInt()))
        System.arraycopy(values,from,data,size,n);size+=n
    }
    fun toArray():FloatArray=data.copyOf(size)
    /** The backing array (valid up to [size]) for reading in place. */
    fun array():FloatArray=data
    /** Removes the first [n] samples, keeping the backing array. */
    fun dropFirst(n:Int) { require(n in 0..size);System.arraycopy(data,n,data,0,size-n);size-=n }
}

/** Streaming resampler: pushes of any size give exactly the same samples as one batch call.
 * Linear interpolation; when downsampling, a 31-tap Hamming-windowed sinc low-pass (zero padded at both
 * ends) runs first so content above the new Nyquist frequency does not fold into the speech band.
 * Total output length is floor(inputLength * to / from). */
internal class PipelineAudioResampler(private val from:Int,private val to:Int) {
    private val step=from.toDouble()/to
    private val taps=31;private val m=taps/2
    private val filter:DoubleArray?=if(from>to)PipelineAudioMath.lowPassKernel(0.47*to/from,taps) else null
    private var raw=FloatArray(0);private var rawBase=0L;private var rawCount=0L
    private var filt=FloatArray(0);private var filtBase=0L;private var filtCount=0L
    private var nextOut=0L;private var finished=false
    init{require(from>0&&to>0){"PIPELINE_AUDIO_RATE"}}

    private fun rawAt(i:Long):Float=if(i<0||i>=rawCount)0f else raw[(i-rawBase).toInt()]
    private fun appendRaw(x:FloatArray) {
        val keep=(rawCount-rawBase).toInt();val merged=FloatArray(keep+x.size)
        System.arraycopy(raw,0,merged,0,keep);System.arraycopy(x,0,merged,keep,x.size);raw=merged;rawCount+=x.size
    }
    private fun filterUntil(limit:Long) {
        if(limit<=filtCount)return
        val n=(limit-filtCount).toInt();val keep=(filtCount-filtBase).toInt();val merged=FloatArray(keep+n)
        System.arraycopy(filt,0,merged,0,keep)
        for(k in 0 until n){
            val i=filtCount+k
            merged[keep+k]=if(filter==null)rawAt(i) else {var acc=0.0;for(t in 0 until taps)acc+=filter[t]*rawAt(i+t-m);acc.toFloat()}
        }
        filt=merged;filtCount=limit
    }
    private fun filtAt(i:Long):Float=filt[(i-filtBase).toInt()]
    private fun emit(out:PipelineAudioFloats,total:Long?) {
        val buf=FloatArray(1024);var n=0
        while(true){
            if(total!=null&&nextOut>=total)break
            val pos=nextOut*step;val idx=pos.toLong()
            val last=filtCount-1
            if(last<0)break
            if(total==null&&idx+1>last)break
            val i0=min(idx,last);val i1=min(idx+1,last)
            val a=filtAt(i0);val b=filtAt(i1);val frac=(pos-idx).toFloat()
            buf[n++]=a+(b-a)*frac;nextOut++
            if(n==buf.size){out.add(buf);n=0}
        }
        out.add(buf,0,n)
    }
    private fun compact() {
        val needFilt=min((nextOut*step).toLong(),filtCount)
        if(needFilt>filtBase){filt=filt.copyOfRange((needFilt-filtBase).toInt(),(filtCount-filtBase).toInt());filtBase=needFilt}
        val needRaw=max(rawBase,min(filtCount-m,rawCount))
        if(needRaw>rawBase){raw=raw.copyOfRange((needRaw-rawBase).toInt(),(rawCount-rawBase).toInt());rawBase=needRaw}
    }
    fun push(x:FloatArray):FloatArray {
        check(!finished){"PIPELINE_AUDIO_RESAMPLER_FINISHED"}
        if(x.isEmpty())return FloatArray(0)
        appendRaw(x)
        filterUntil(max(filtCount,rawCount-(if(filter==null)0 else m)))
        val out=PipelineAudioFloats(x.size*to/from+4);emit(out,null);compact();return out.toArray()
    }
    fun finish():FloatArray {
        check(!finished){"PIPELINE_AUDIO_RESAMPLER_FINISHED"};finished=true
        filterUntil(rawCount)
        val out=PipelineAudioFloats(64);emit(out,rawCount*to/from);return out.toArray()
    }
}

/** Pure, JVM-testable audio math for the on-device transcription pipeline. No Android APIs. */
internal object PipelineAudioMath {
    const val TARGET_RATE=16000
    /** F3: a 30 ms window is speech only above this level, however quiet the call is. One constant so the golden set
     * (v2.4-2) can compare it with [SPEECH_FLOOR_LOW]; the lower value is adopted only if CER and names improve. */
    const val SPEECH_FLOOR=0.006f
    const val SPEECH_FLOOR_LOW=0.0025f
    /** Two minutes: a stopped piece costs little (it is redone from its start) and fits well inside WorkManager's time limit. */
    const val MAX_CHUNK_MS=120_000L
    const val CUT_SEARCH_MS=2_000L
    const val CUT_WINDOW_MS=100L
    /** Candidate cut positions are evaluated on this grid. */
    const val CUT_STEP_MS=10L
    /** r12-8 A5: the shortest last piece; a shorter one is joined to the piece before it (native minimum 1600 samples at 16 kHz). */
    const val MIN_TAIL_MS=100L
    fun minTail(rate:Int):Long=maxOf(1L,rate*MIN_TAIL_MS/1000)

    /** Average all channels of interleaved PCM into one. A trailing partial frame is dropped. */
    fun downmix(interleaved:FloatArray,channels:Int):FloatArray {
        require(channels>=1){"PIPELINE_AUDIO_CHANNELS"}
        if(channels==1)return interleaved.copyOf()
        val frames=interleaved.size/channels
        val out=FloatArray(frames)
        for(f in 0 until frames){var s=0f;val base=f*channels;for(c in 0 until channels)s+=interleaved[base+c];out[f]=s/channels}
        return out
    }

    /** Hamming-windowed sinc FIR taps, [cutoff] as a fraction of the input sample rate, unity DC gain. */
    fun lowPassKernel(cutoff:Double,taps:Int=31):DoubleArray {
        val m=taps/2
        val h=DoubleArray(taps){k->
            val x=(k-m).toDouble()
            val sinc=if(k==m)2*cutoff else sin(2*PI*cutoff*x)/(PI*x)
            sinc*(0.54-0.46*cos(2*PI*k/(taps-1)))
        }
        val sum=h.sum();for(k in h.indices)h[k]/=sum
        return h
    }

    /** RMS of consecutive non-overlapping windows; a trailing partial window is included. */
    fun rmsWindows(samples:FloatArray,rate:Int,windowMs:Long=CUT_WINDOW_MS,from:Int=0,to:Int=samples.size):FloatArray {
        val w=max(1L,rate*windowMs/1000).toInt()
        val count=(to-from+w-1)/w
        return FloatArray(count){i->
            val start=from+i*w;val end=min(to,start+w);var s=0.0
            for(j in start until end)s+=samples[j].toDouble()*samples[j]
            sqrt(s/(end-start)).toFloat()
        }
    }

    /** Sample indices where chunks start, beginning with 0. Every chunk is at most [maxChunkMs].
     * Each cut is placed at the centre of the lowest-energy [windowMs] window whose centre lies within
     * ±[searchMs] of a nominal boundary at (chunk start + max - search), so the cut never exceeds the
     * maximum. Ties go to the candidate nearest the nominal boundary. */
    fun cutPoints(samples:FloatArray,rate:Int=TARGET_RATE,maxChunkMs:Long=MAX_CHUNK_MS,
                  searchMs:Long=CUT_SEARCH_MS,windowMs:Long=CUT_WINDOW_MS,stepMs:Long=CUT_STEP_MS,
                  /** Only the first [length] samples count (a buffer read in place). */ length:Int=samples.size):List<Int> {
        val maxLen=rate*maxChunkMs/1000;val search=rate*searchMs/1000
        val half=max(1L,rate*windowMs/2000);val step=max(1L,rate*stepMs/1000)
        require(maxLen>2*search&&maxLen>0){"PIPELINE_AUDIO_CHUNK_PARAMS"}
        require(length in 0..samples.size){"PIPELINE_AUDIO_CHUNK_PARAMS"}
        val n=length.toLong()
        val cuts=ArrayList<Int>();cuts.add(0)
        var start=0L
        while(n-start>maxLen){
            val nominal=start+maxLen-search
            val lo=max(start+1,nominal-search);val hi=start+maxLen
            // Prefix sums of squared samples over [lo-half, hi+half).
            val pStart=max(0L,lo-half);val pEnd=min(n,hi+half)
            val prefix=DoubleArray((pEnd-pStart+1).toInt())
            for(i in 0 until (pEnd-pStart).toInt()){val v=samples[(pStart+i).toInt()].toDouble();prefix[i+1]=prefix[i]+v*v}
            var best=-1L;var bestEnergy=Double.MAX_VALUE;var bestDistance=Long.MAX_VALUE
            var c=lo
            while(c<=hi){
                val a=max(pStart,c-half);val b=min(pEnd,c+half)
                val e=(prefix[(b-pStart).toInt()]-prefix[(a-pStart).toInt()])/max(1L,b-a)
                val d=abs(c-nominal)
                if(e<bestEnergy-1e-12||(abs(e-bestEnergy)<=1e-12&&d<bestDistance)){best=c;bestEnergy=e;bestDistance=d}
                c+=step
            }
            check(best>start&&best<=start+maxLen){"PIPELINE_AUDIO_CUT"}
            cuts.add(best.toInt());start=best
        }
        // r12-8 A5: a last piece under 0.1 s is joined to the one before it (the native runtime refuses under 1600 samples,
        // ANDROID_STT_PCM_SIZE, which failed the whole recording). That piece may then run 0.1 s over the maximum.
        if(cuts.size>1 && n-cuts.last()<minTail(rate)) cuts.removeAt(cuts.size-1)
        return cuts
    }

    /** Sample index near the middle where the audio is quietest (within 2 s either side), for halving a piece that keeps stalling. */
    fun halfPoint(samples:FloatArray,rate:Int=TARGET_RATE):Int {
        val mid=samples.size/2;val w=max(1,rate/10)
        val lo=max(1,mid-2*rate);val hi=min(samples.size-1,mid+2*rate)
        var best=mid;var bestEnergy=Double.MAX_VALUE;var c=lo
        while(c<=hi){
            val a=max(0,c-w/2);val b=min(samples.size,c+w/2);var e=0.0
            for(j in a until b)e+=samples[j].toDouble()*samples[j]
            e/=max(1,b-a)
            if(e<bestEnergy){bestEnergy=e;best=c}
            c+=w/2
        }
        return best.coerceIn(1,samples.size-1)
    }

    /** Chunks as (original start ms, samples). Lengths sum to the input length. */
    fun split(samples:FloatArray,rate:Int=TARGET_RATE,maxChunkMs:Long=MAX_CHUNK_MS):List<Pair<Long,FloatArray>> {
        if(samples.isEmpty())return emptyList()
        val cuts=cutPoints(samples,rate,maxChunkMs)
        return cuts.mapIndexed{i,s->
            val e=if(i+1<cuts.size)cuts[i+1] else samples.size
            (s.toLong()*1000/rate) to samples.copyOfRange(s,e)
        }
    }


    private fun readFully(input:PipelineAudioInput,position:Long,buffer:ByteArray,length:Int):Int {
        var n=0
        while(n<length){val r=input.readAt(position+n,buffer,n,length-n);if(r<=0)break;n+=r}
        return n
    }
    fun isWav(input:PipelineAudioInput):Boolean {
        if(input.size<12)return false
        val h=ByteArray(12);if(readFully(input,0,h,12)<12)return false
        return String(h,0,4,Charsets.US_ASCII)=="RIFF"&&String(h,8,4,Charsets.US_ASCII)=="WAVE"
    }

    /** RIFF/WAVE PCM16 header (format 1 or WAVE_FORMAT_EXTENSIBLE). Tolerates wrong RIFF/data sizes written by
     * recorders that never finalised the header by clamping the data chunk to the bytes present. */
    fun parseWavHeader(input:PipelineAudioInput):PipelineAudioWavInfo {
        check(isWav(input)){"PIPELINE_AUDIO_WAV_HEADER"}
        val size=input.size;val head=ByteArray(16);val b=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        var pos=12L;var rate=0;var channels=0;var format=false
        while(pos+8<=size){
            check(readFully(input,pos,head,8)==8){"PIPELINE_AUDIO_WAV_HEADER"}
            val id=String(head,0,4,Charsets.US_ASCII)
            val declared=b.getInt(4).toLong() and 0xffffffffL
            val body=pos+8
            val available=min(declared,size-body)
            when(id){
                "fmt "->{
                    check(available>=16&&readFully(input,body,head,16)==16){"PIPELINE_AUDIO_WAV_FMT"}
                    val tag=b.getShort(0).toInt() and 0xffff
                    channels=b.getShort(2).toInt() and 0xffff
                    rate=b.getInt(4)
                    val bits=b.getShort(14).toInt() and 0xffff
                    check(tag==1||tag==0xfffe){"PIPELINE_AUDIO_WAV_NOT_PCM"}
                    check(bits==16){"PIPELINE_AUDIO_WAV_PCM16_ONLY"}
                    check(channels in 1..8){"PIPELINE_AUDIO_WAV_CHANNELS"}
                    check(rate in 4000..192000){"PIPELINE_AUDIO_WAV_RATE"}
                    format=true
                }
                "data"->{
                    check(format){"PIPELINE_AUDIO_WAV_FMT_ORDER"}
                    val frameBytes=2*channels
                    return PipelineAudioWavInfo(rate,channels,body,available-available%frameBytes)
                }
            }
            if(declared>size-body)break
            pos=body+declared+(declared and 1)
        }
        error("PIPELINE_AUDIO_WAV_DATA")
    }

    /** Streams a WAV's samples through a [PipelineAudioStream] in bounded blocks; returns the finished stream. */
    fun streamWav(input:PipelineAudioInput,onChunk:PipelineAudioView):PipelineAudioStream {
        val info=parseWavHeader(input)
        val stream=PipelineAudioStream(info.sampleRate,info.channels,onChunk=onChunk)
        val block=ByteArray(8192*info.channels*2);var done=0L
        while(done<info.dataBytes){
            val n=min(block.size.toLong(),info.dataBytes-done).toInt()
            check(readFully(input,info.dataOffset+done,block,n)==n){"PIPELINE_AUDIO_WAV_DATA"}
            stream.pushPcm16(block,0,n);done+=n
        }
        stream.finish();block.fill(0)
        return stream
    }
    /** A short WAV fully in memory, without silence skipping (tests, the settings screen check). */
    fun decodeWav(bytes:ByteArray):PipelineDecodedAudio {
        val chunks=ArrayList<Pair<Long,FloatArray>>()
        val s=streamWav(PipelineBytesInput(bytes)){ms,x,from,to->chunks+=ms to x.copyOfRange(from,to)}
        return PipelineDecodedAudio(s.durationMs,chunks,vadApplied=false)
    }

    /** Silence skipping on audio already in memory (tests, device tests); the decoder does the same piece by piece
     * ([PipelineSpeechPlan], then [PipelineSpeechAssembler]). Returns [audio] itself when little is silent. */
    fun speechOnly(audio:PipelineDecodedAudio,rate:Int=TARGET_RATE,keepGapMs:Long=1500,padMs:Long=300,minSavedRatio:Double=0.1,
                   floor:Float=SPEECH_FLOOR):PipelineDecodedAudio {
        val plan=PipelineSpeechPlan(rate,keepGapMs,padMs,floor)
        for((offset,pcm) in audio.chunks)plan.add(offset,pcm,0,pcm.size)
        if(!plan.applies(minSavedRatio))return audio
        val out=ArrayList<Pair<Long,FloatArray>>()
        val assembler=PipelineSpeechAssembler(plan,rate){ms,x->out+=ms to x}
        audio.chunks.forEachIndexed{i,(_,pcm)->assembler.raw(i,pcm,0,pcm.size)}
        assembler.finish()
        return PipelineDecodedAudio(audio.durationMs,out,vadApplied=true)
    }
}

internal class PipelineAudioWavInfo(val sampleRate:Int,val channels:Int,val dataOffset:Long,val dataBytes:Long)

/** A chunk lent for the length of the call only: samples data[from until to] (copy them to keep them). */
internal fun interface PipelineAudioView { fun chunk(offsetMs:Long,data:FloatArray,from:Int,to:Int) }

/** 16 kHz mono samples in, chunks out: exactly the cuts of [PipelineAudioMath.split] on the whole signal, made as soon
 * as enough audio past a boundary has arrived. Holds one chunk's buffer and lends each chunk out of it ([emit] gets the
 * first sample index and a view), so there is no new array per chunk unless the receiver copies. */
internal class PipelineChunker(private val rate:Int=PipelineAudioMath.TARGET_RATE,private val maxChunkMs:Long=PipelineAudioMath.MAX_CHUNK_MS,
                               /** (first sample index, data, from, to): valid during the call only */ private val emit:(Long,FloatArray,Int,Int)->Unit) {
    /** Samples needed past a chunk start before its cut is final (max length + the larger of half a window and the shortest
     * last piece (r12-8 A5: a cut made here then always leaves at least that much after it, so it is never the one joined) + 1). */
    private val ready=rate*maxChunkMs/1000+maxOf(maxOf(1L,rate*PipelineAudioMath.CUT_WINDOW_MS/2000),PipelineAudioMath.minTail(rate))+1
    private val current=PipelineAudioFloats(1 shl 16,limit=ready.toInt())
    private var start=0L
    private var finished=false
    fun add(x:FloatArray,from:Int=0,to:Int=x.size) {
        check(!finished){"PIPELINE_AUDIO_STREAM_FINISHED"}
        var i=from
        while(i<to){
            // Never more than one cut's worth past [ready]: the buffer stays near one chunk even for a long add.
            val n=minOf(to-i,maxOf(1L,ready-current.size).toInt());current.add(x,i,i+n);i+=n
            while(current.size>=ready){
                val cut=PipelineAudioMath.cutPoints(current.array(),rate,maxChunkMs,length=current.size)[1]
                out(current.array(),0,cut);current.dropFirst(cut)
            }
        }
    }
    private fun out(data:FloatArray,from:Int,to:Int){val s=start;start+=to-from;emit(s,data,from,to)}
    fun finish() {
        check(!finished){"PIPELINE_AUDIO_STREAM_FINISHED"};finished=true
        val size=current.size
        if(size>0){
            val cuts=PipelineAudioMath.cutPoints(current.array(),rate,maxChunkMs,length=size)
            for(i in cuts.indices)out(current.array(),cuts[i],if(i+1<cuts.size)cuts[i+1] else size)
        }
        current.dropFirst(current.size)
    }
}

/** F1 diagnostics of the first two channels: normalised left/right correlation and the energy ratio (quieter / louder).
 * Numbers only, nothing about the content. */
internal class PipelineChannelStats {
    var channels=0;private set
    private var n=0L;private var sl=0.0;private var sr=0.0;private var sll=0.0;private var srr=0.0;private var slr=0.0
    fun add(interleaved:FloatArray,length:Int,channels:Int) {
        this.channels=channels
        if(channels<2)return
        var f=0
        while(f+channels<=length){val l=interleaved[f].toDouble();val r=interleaved[f+1].toDouble();sl+=l;sr+=r;sll+=l*l;srr+=r*r;slr+=l*r;n++;f+=channels}
    }
    class Result(val channels:Int,val correlation:Double?,val energyRatio:Double?)
    fun result():Result {
        if(channels<2||n==0L)return Result(channels,null,null)
        val vl=sll-sl*sl/n;val vr=srr-sr*sr/n;val c=slr-sl*sr/n
        val corr=if(vl>0&&vr>0)(c/kotlin.math.sqrt(vl*vr)).coerceIn(-1.0,1.0) else null
        val hi=max(vl,vr);val ratio=if(hi>0)min(vl,vr)/hi else null
        return Result(channels,corr,ratio)
    }
}

/** Interleaved PCM of any rate/channel count in, 16 kHz mono chunks out ([PipelineChunker] cuts), each lent to
 * [onChunk] with its original start ms as soon as it is final. No full-rate or full-length copy is kept. */
internal class PipelineAudioStream(val sampleRate:Int,val channels:Int,maxChunkMs:Long=PipelineAudioMath.MAX_CHUNK_MS,
                                   private val onChunk:PipelineAudioView) {
    private val target=PipelineAudioMath.TARGET_RATE
    private val resampler=PipelineAudioResampler(sampleRate,target)
    private val chunker=PipelineChunker(target,maxChunkMs){s,x,from,to->onChunk.chunk(s*1000/target,x,from,to)}
    val channelStats=PipelineChannelStats()
    private var pending=FloatArray(0)
    private var frames=0L
    private var finished=false
    /** Original length; valid after [finish]. */
    var durationMs=0L;private set
    init{require(sampleRate in 4000..192000&&channels in 1..8){"PIPELINE_AUDIO_FORMAT"}}
    /** Interleaved samples; a partial frame is held until the next push. */
    fun push(interleaved:FloatArray) {
        check(!finished){"PIPELINE_AUDIO_STREAM_FINISHED"}
        var data=interleaved
        if(pending.isNotEmpty()){data=pending+interleaved;pending=FloatArray(0)}
        val whole=data.size-data.size%channels
        if(whole<data.size)pending=data.copyOfRange(whole,data.size)
        if(whole==0)return
        channelStats.add(data,whole,channels)
        val mono=PipelineAudioMath.downmix(if(whole==data.size)data else data.copyOf(whole),channels)
        frames+=mono.size;chunker.add(resampler.push(mono))
    }
    fun pushPcm16(bytes:ByteArray,offset:Int,length:Int) {
        val n=length/2;val f=FloatArray(n)
        for(i in 0 until n){val lo=bytes[offset+2*i].toInt() and 0xff;val hi=bytes[offset+2*i+1].toInt();f[i]=((hi shl 8) or lo).toShort()/32768f}
        push(f)
    }
    fun finish():Long {
        check(!finished){"PIPELINE_AUDIO_STREAM_FINISHED"};finished=true
        chunker.add(resampler.finish());chunker.finish()
        durationMs=frames*1000/sampleRate;return durationMs
    }
}

/** First pass of silence skipping, one raw chunk at a time. Speech = 30 ms windows louder than
 * max([floor], min(3 x the chunk's 20th-percentile level, 0.3 x its 90th)); gaps under [keepGapMs] are kept, each region gets
 * [padMs] on both sides. Only where the speech is (chunk index and sample range) is kept, never the samples: the second
 * pass ([PipelineSpeechAssembler]) copies them out of the same chunks decoded again. Each output piece's offset is the
 * original position of its first sample; times inside it are on the shortened audio (hence vadApplied=true). */
internal class PipelineSpeechPlan(private val rate:Int=PipelineAudioMath.TARGET_RATE,private val keepGapMs:Long=1500,private val padMs:Long=300,
                                  private val floor:Float=PipelineAudioMath.SPEECH_FLOOR) {
    class Part(val chunk:Int,val from:Int,val to:Int)
    /** [origin]: start sample on the original timeline (from the chunk's ms offset, as before). */
    class Piece(val origin:Long){ val parts=ArrayList<Part>(2);var length=0L;private set
        fun add(p:Part){parts+=p;length+=p.to-p.from} }
    val pieces=ArrayList<Piece>()
    private val sizes=ArrayList<Int>();private val sums=ArrayList<Int>()
    var total=0L;private set
    val chunks get()=sizes.size

    /** One raw chunk, data[from until to]; parts are kept relative to [from]. */
    fun add(offsetMs:Long,data:FloatArray,from:Int,to:Int) {
        val size=to-from
        val index=sizes.size;sizes+=size;sums+=checksum(data,from,to);total+=size
        val win=rate*30/1000
        val rms=PipelineAudioMath.rmsWindows(data,rate,30,from,to)
        if(rms.isEmpty())return
        val sorted=rms.sorted();val low=sorted[rms.size/5];val loud=sorted[rms.size*9/10]
        val threshold=max(floor,min(low*3,loud*0.3f)) // continuous speech: the floor is speech itself
        val gap=(keepGapMs/30).toInt();val pad=(padMs/30).toInt()
        val start=offsetMs*rate/1000
        var i=0
        while(i<rms.size){
            if(rms[i]<threshold){i++;continue}
            var end=i;var quiet=0;var j=i
            while(j<rms.size&&quiet<=gap){ if(rms[j]>=threshold){end=j;quiet=0} else quiet++; j++ }
            val a=max(0,(i-pad)*win);val b=min(size,(end+1+pad)*win)
            val last=pieces.lastOrNull()
            if(last!=null&&last.origin+last.length>=start+a) {
                val from=max(a.toLong(),last.origin+last.length-start).toInt() // continues the previous piece (no copy now or later)
                if(from<b)last.add(Part(index,from,b))
            } else pieces.add(Piece(start+a).also{it.add(Part(index,a,b))})
            i=end+1+pad
        }
    }
    val kept get()=pieces.sumOf{it.length}
    /** Nothing is removed if little is silent. */
    fun applies(minSavedRatio:Double=0.1):Boolean { val k=kept;return !(total==0L||k==0L||k>total*(1-minSavedRatio)) }
    /** The second decode must give the same chunks as the first. */
    fun verify(index:Int,data:FloatArray,from:Int,to:Int) { check(index<sizes.size&&sizes[index]==to-from&&sums[index]==checksum(data,from,to)){"PIPELINE_AUDIO_DECODE_UNSTABLE"} }
    fun verifyCount(count:Int) { check(count==sizes.size){"PIPELINE_AUDIO_DECODE_UNSTABLE"} }
    private fun checksum(x:FloatArray,from:Int,to:Int):Int { var h=to-from;for(i in from until to)h=31*h+java.lang.Float.floatToRawIntBits(x[i]);return h }
}

/** Second pass: copies the planned speech out of the raw chunks as they are decoded again and packs it into pieces of at
 * most [PipelineAudioMath.MAX_CHUNK_MS] (a single over-long stretch is cut like [PipelineAudioMath.split], streamed).
 * Holds at most one output piece besides the raw chunk being read. */
internal class PipelineSpeechAssembler(plan:PipelineSpeechPlan,private val rate:Int=PipelineAudioMath.TARGET_RATE,
                                       /** (original start ms, samples) */ private val emit:(Long,FloatArray)->Unit) {
    /** Pieces packed together ([long]=false, origin of the first), or one over-long piece cut on its own. */
    private class Group(val origin:Long,val pieces:List<PipelineSpeechPlan.Piece>,val length:Long,val long:Boolean){
        val parts=pieces.flatMap{it.parts} }
    private val groups=ArrayList<Group>()
    init {
        val maxLen=(rate*PipelineAudioMath.MAX_CHUNK_MS/1000).toInt()
        var buf=ArrayList<PipelineSpeechPlan.Piece>();var size=0L
        fun flush(){ if(size>0)groups+=Group(buf.first().origin,buf,size,false);buf=ArrayList();size=0L }
        for(p in plan.pieces){
            if(p.length>maxLen){ flush();groups+=Group(p.origin,listOf(p),p.length,true);continue }
            if(size+p.length>maxLen)flush()
            buf+=p;size+=p.length
        }
        flush()
    }
    private var g=0;private var part=0
    private var packed:FloatArray?=null;private var filled=0
    private var chunker:PipelineChunker?=null

    /** Raw chunk [index], data[from until to] (lent: only what is needed is copied out). */
    fun raw(index:Int,data:FloatArray,from:Int,to:Int) {
        while(g<groups.size){
            val group=groups[g]
            while(part<group.parts.size&&group.parts[part].chunk==index){
                val p=group.parts[part]
                if(group.long){
                    (chunker ?: PipelineChunker(rate){s,x,a,b->emit(group.origin*1000/rate+s*1000/rate,x.copyOfRange(a,b))}.also{chunker=it}).add(data,from+p.from,from+p.to)
                } else {
                    val buf=packed ?: FloatArray(group.length.toInt()).also{packed=it;filled=0}
                    System.arraycopy(data,from+p.from,buf,filled,p.to-p.from);filled+=p.to-p.from
                }
                part++
            }
            if(part<group.parts.size){ check(group.parts[part].chunk>index){"PIPELINE_AUDIO_PLAN"};return }
            if(group.long){ chunker?.finish();chunker=null } else { emit(group.origin*1000/rate,checkNotNull(packed));packed=null }
            g++;part=0
        }
    }
    fun finish() { check(g==groups.size){"PIPELINE_AUDIO_PLAN"} }
}
