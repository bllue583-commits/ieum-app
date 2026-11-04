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

/** Interleaved float PCM at its source rate, as parsed from a container. */
internal class PipelineAudioPcm(val sampleRate:Int,val channels:Int,val interleaved:FloatArray) {
    val frames:Int get()=interleaved.size/channels
}

/** Growable float buffer. */
internal class PipelineAudioFloats(initial:Int=16384) {
    private var data=FloatArray(max(16,initial));var size=0;private set
    fun add(values:FloatArray,from:Int=0,to:Int=values.size) {
        val n=to-from;if(n<=0)return
        if(size+n>data.size)data=data.copyOf(max(size+n,(data.size.toLong()*2).coerceAtMost(Int.MAX_VALUE-8L).toInt()))
        System.arraycopy(values,from,data,size,n);size+=n
    }
    fun toArray():FloatArray=data.copyOf(size)
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
    /** Two minutes: a stopped piece costs little (it is redone from its start) and fits well inside WorkManager's time limit. */
    const val MAX_CHUNK_MS=120_000L
    const val CUT_SEARCH_MS=2_000L
    const val CUT_WINDOW_MS=100L
    /** Candidate cut positions are evaluated on this grid. */
    const val CUT_STEP_MS=10L

    /** Average all channels of interleaved PCM into one. A trailing partial frame is dropped. */
    fun downmix(interleaved:FloatArray,channels:Int):FloatArray {
        require(channels>=1){"PIPELINE_AUDIO_CHANNELS"}
        if(channels==1)return interleaved.copyOf()
        val frames=interleaved.size/channels
        val out=FloatArray(frames)
        for(f in 0 until frames){var s=0f;val base=f*channels;for(c in 0 until channels)s+=interleaved[base+c];out[f]=s/channels}
        return out
    }

    /** Linear-interpolation resampler (see [PipelineAudioResampler]). Output length is floor(input.size * to / from). */
    fun resample(input:FloatArray,from:Int,to:Int=TARGET_RATE):FloatArray {
        if(from==to)return input.copyOf()
        val r=PipelineAudioResampler(from,to);val out=PipelineAudioFloats()
        out.add(r.push(input));out.add(r.finish());return out.toArray()
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
    fun rmsWindows(samples:FloatArray,rate:Int,windowMs:Long=CUT_WINDOW_MS):FloatArray {
        val w=max(1L,rate*windowMs/1000).toInt()
        val count=(samples.size+w-1)/w
        return FloatArray(count){i->
            val start=i*w;val end=min(samples.size,start+w);var s=0.0
            for(j in start until end)s+=samples[j].toDouble()*samples[j]
            sqrt(s/(end-start)).toFloat()
        }
    }

    /** Sample indices where chunks start, beginning with 0. Every chunk is at most [maxChunkMs].
     * Each cut is placed at the centre of the lowest-energy [windowMs] window whose centre lies within
     * ±[searchMs] of a nominal boundary at (chunk start + max - search), so the cut never exceeds the
     * maximum. Ties go to the candidate nearest the nominal boundary. */
    fun cutPoints(samples:FloatArray,rate:Int=TARGET_RATE,maxChunkMs:Long=MAX_CHUNK_MS,
                  searchMs:Long=CUT_SEARCH_MS,windowMs:Long=CUT_WINDOW_MS,stepMs:Long=CUT_STEP_MS):List<Int> {
        val maxLen=rate*maxChunkMs/1000;val search=rate*searchMs/1000
        val half=max(1L,rate*windowMs/2000);val step=max(1L,rate*stepMs/1000)
        require(maxLen>2*search&&maxLen>0){"PIPELINE_AUDIO_CHUNK_PARAMS"}
        val n=samples.size.toLong()
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
        return cuts
    }

    /** Drops long silences so Whisper only runs on speech. Speech = 30 ms windows louder than
     * max(0.006, min(3 x the 20th-percentile level, 0.3 x the 90th)); gaps under [keepGapMs] are kept, each region gets [padMs]
     * on both sides. Each output chunk's offset is the original position of its first sample; times inside
     * a chunk are on the shortened audio (hence vadApplied=true). Nothing is removed if little is silent. */
    fun speechOnly(audio:PipelineDecodedAudio,rate:Int=TARGET_RATE,keepGapMs:Long=1500,padMs:Long=300,minSavedRatio:Double=0.1):PipelineDecodedAudio {
        val win=rate*30/1000
        data class Piece(val origin:Long,val samples:FloatArray)
        val pieces=ArrayList<Piece>()
        var total=0L;var kept=0L
        for((offset,pcm) in audio.chunks){
            total+=pcm.size
            val rms=rmsWindows(pcm,rate,30)
            if(rms.isEmpty())continue
            val sorted=rms.sorted();val floor=sorted[rms.size/5];val loud=sorted[rms.size*9/10]
            val threshold=max(0.006f,min(floor*3,loud*0.3f)) // continuous speech: the floor is speech itself
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
        kept=pieces.sumOf{it.samples.size.toLong()}
        if(total==0L||kept==0L||kept>total*(1-minSavedRatio))return audio
        // Pack speech pieces into chunks of at most MAX_CHUNK_MS (a single over-long piece is split normally).
        val maxLen=(rate*MAX_CHUNK_MS/1000).toInt()
        val out=ArrayList<Pair<Long,FloatArray>>()
        var buf=PipelineAudioFloats(1024);var bufOrigin=-1L
        fun flush(){ if(buf.size>0){out+=(bufOrigin*1000/rate) to buf.toArray()};buf=PipelineAudioFloats(1024);bufOrigin=-1L }
        for(p in pieces){
            if(p.samples.size>maxLen){ flush();split(p.samples,rate).forEach{(ms,x)->out+=(p.origin*1000/rate+ms) to x};continue }
            if(buf.size+p.samples.size>maxLen)flush()
            if(bufOrigin<0)bufOrigin=p.origin
            buf.add(p.samples)
        }
        flush()
        return PipelineDecodedAudio(audio.durationMs,out,vadApplied=true)
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

    /** Mono PCM at any rate -> 16 kHz chunks. Silence skipping is not applied (vadApplied=false),
     * so every chunk offset is exactly its position in the original recording. */
    fun build(mono:FloatArray,rate:Int):PipelineDecodedAudio {
        val stream=PipelineAudioStream(rate,1);stream.push(mono);return stream.finish()
    }

    fun isWav(bytes:ByteArray):Boolean=bytes.size>=12&&
        String(bytes,0,4,Charsets.US_ASCII)=="RIFF"&&String(bytes,8,4,Charsets.US_ASCII)=="WAVE"

    /** RIFF/WAVE PCM16 header (format 1 or WAVE_FORMAT_EXTENSIBLE). Tolerates wrong RIFF/data sizes written by
     * recorders that never finalised the header by clamping the data chunk to the bytes present. */
    fun parseWavHeader(bytes:ByteArray):PipelineAudioWavInfo {
        check(isWav(bytes)){"PIPELINE_AUDIO_WAV_HEADER"}
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos=12;var rate=0;var channels=0;var format=false
        while(pos+8<=bytes.size){
            val id=String(bytes,pos,4,Charsets.US_ASCII)
            val declared=b.getInt(pos+4).toLong() and 0xffffffffL
            val body=pos+8
            val available=min(declared,(bytes.size-body).toLong()).toInt()
            when(id){
                "fmt "->{
                    check(available>=16){"PIPELINE_AUDIO_WAV_FMT"}
                    val tag=b.getShort(body).toInt() and 0xffff
                    channels=b.getShort(body+2).toInt() and 0xffff
                    rate=b.getInt(body+4)
                    val bits=b.getShort(body+14).toInt() and 0xffff
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
            if(declared>(bytes.size-body).toLong())break
            pos=body+declared.toInt()+(declared.toInt() and 1)
        }
        error("PIPELINE_AUDIO_WAV_DATA")
    }

    /** Whole WAV as interleaved floats (small inputs and tests; the decoder streams instead). */
    fun parseWav(bytes:ByteArray):PipelineAudioPcm {
        val info=parseWavHeader(bytes);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return PipelineAudioPcm(info.sampleRate,info.channels,FloatArray(info.dataBytes/2){b.getShort(info.dataOffset+it*2)/32768f})
    }

    /** Streams a WAV's samples through [PipelineAudioStream] in bounded blocks. */
    fun decodeWav(bytes:ByteArray):PipelineDecodedAudio {
        val info=parseWavHeader(bytes);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stream=PipelineAudioStream(info.sampleRate,info.channels)
        val block=FloatArray(8192*info.channels);var i=0;val total=info.dataBytes/2
        while(i<total){
            val n=min(block.size,total-i)
            for(k in 0 until n)block[k]=b.getShort(info.dataOffset+(i+k)*2)/32768f
            stream.push(if(n==block.size)block else block.copyOf(n));i+=n
        }
        return stream.finish()
    }
}

internal class PipelineAudioWavInfo(val sampleRate:Int,val channels:Int,val dataOffset:Int,val dataBytes:Int)

/** Interleaved PCM of any rate/channel count in, 16 kHz mono chunks out. Chunks are cut as soon as
 * enough audio past a boundary has arrived, giving exactly the cuts of [PipelineAudioMath.split] on the
 * whole signal while holding only one extra chunk of 16 kHz audio (no full-rate or full-length copy). */
internal class PipelineAudioStream(val sampleRate:Int,val channels:Int,private val maxChunkMs:Long=PipelineAudioMath.MAX_CHUNK_MS) {
    private val target=PipelineAudioMath.TARGET_RATE
    private val resampler=PipelineAudioResampler(sampleRate,target)
    private var current=PipelineAudioFloats(1 shl 16)
    private var currentStart=0L
    private val chunks=ArrayList<Pair<Long,FloatArray>>()
    private var pending=FloatArray(0)
    private var frames=0L
    private var finished=false
    /** Samples needed past a chunk start before its cut is final (max length + half window + 1). */
    private val ready=target*maxChunkMs/1000+maxOf(1L,target*PipelineAudioMath.CUT_WINDOW_MS/2000)+1
    init{require(sampleRate in 4000..192000&&channels in 1..8){"PIPELINE_AUDIO_FORMAT"}}
    /** Interleaved samples; a partial frame is held until the next push. */
    fun push(interleaved:FloatArray) {
        check(!finished){"PIPELINE_AUDIO_STREAM_FINISHED"}
        var data=interleaved
        if(pending.isNotEmpty()){data=pending+interleaved;pending=FloatArray(0)}
        val whole=data.size-data.size%channels
        if(whole<data.size)pending=data.copyOfRange(whole,data.size)
        if(whole==0)return
        val mono=PipelineAudioMath.downmix(if(whole==data.size)data else data.copyOf(whole),channels)
        frames+=mono.size;append(resampler.push(mono))
    }
    fun pushPcm16(bytes:ByteArray,offset:Int,length:Int) {
        val n=length/2;val f=FloatArray(n)
        for(i in 0 until n){val lo=bytes[offset+2*i].toInt() and 0xff;val hi=bytes[offset+2*i+1].toInt();f[i]=((hi shl 8) or lo).toShort()/32768f}
        push(f)
    }
    private fun append(x:FloatArray) {
        current.add(x)
        while(current.size>=ready){
            val arr=current.toArray()
            val cut=PipelineAudioMath.cutPoints(arr,target,maxChunkMs)[1]
            emit(arr.copyOfRange(0,cut))
            current=PipelineAudioFloats(maxOf(1 shl 16,arr.size-cut+(1 shl 16)));current.add(arr,cut,arr.size)
        }
    }
    private fun emit(chunk:FloatArray){chunks.add(currentStart*1000/target to chunk);currentStart+=chunk.size}
    fun finish():PipelineDecodedAudio {
        check(!finished){"PIPELINE_AUDIO_STREAM_FINISHED"};finished=true
        append(resampler.finish())
        val rest=current.toArray();current=PipelineAudioFloats(16)
        if(rest.isNotEmpty()){
            val cuts=PipelineAudioMath.cutPoints(rest,target,maxChunkMs)
            for(i in cuts.indices)emit(rest.copyOfRange(cuts[i],if(i+1<cuts.size)cuts[i+1] else rest.size))
        }
        return PipelineDecodedAudio(frames*1000/sampleRate,chunks.toList(),vadApplied=false)
    }
}
