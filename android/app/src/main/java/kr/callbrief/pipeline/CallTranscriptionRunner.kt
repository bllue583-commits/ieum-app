package kr.callbrief.pipeline

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.callbrief.AndroidWhisperEngine
import kr.callbrief.GeneralLocalStore
import kr.callbrief.LocalPrivacy
import kr.callbrief.RecordingRef
import kr.callbrief.SecureSettings
import kr.callbrief.SttDecode
import kr.callbrief.SttPrompt
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Whisper on 16 kHz mono chunks; segment times are relative to the chunk start. */
internal fun interface CallTranscriber {
    fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment>
    /** The model is loaded once per recording: pieces of one recording go through one [Session], which the caller always closes.
     * The default (fakes) just calls [transcribe] for every piece. */
    fun open(model:File,stopped:()->Boolean):Session = object:Session {
        override fun transcribe(pcm:FloatArray,stopped:()->Boolean)=this@CallTranscriber.transcribe(model,pcm,stopped)
        override fun close() {}
    }
    interface Session:AutoCloseable {
        fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment>
        /** v3.6 W3: the session goes on to another recording: forget what the previous recording said (B2 context). */
        fun nextRecording() {}
    }
}

/** F2: 6 threads while charging or with at least half the battery, otherwise 4. v3.6: the background run takes its count from
 * [PowerPolicy] (wired 6, wireless and battery 4), capped by the cores the process may use (P7, [CpuSet]); this stays for the
 * golden measurement screen, with the same cap. */
internal object TranscriptionThreads {
    fun threadsFor(charging:Boolean,percent:Int)=if(charging||percent>=50) 6 else 4
    fun current(context:Context):Int {
        val b=context.getSystemService(android.os.BatteryManager::class.java) ?: return 4
        return PowerPolicy.threads(threadsFor(b.isCharging,b.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)),CpuSet.readAllowed())
    }
}

/** Production transcriber: the pinned JNI runtime with the verified large model, Korean. */
internal class WhisperCallTranscriber(private val engine:AndroidWhisperEngine,private val model:PipelineSttModel,private val threadCount:Int?=null,
    /** Thread count read when a recording starts (battery state); ignored when [threadCount] is given. */
    private val threadSource:(()->Int)?=null,
    /** Native time limit for one piece. Under WorkManager's 10 minutes for the background work; device tests on a slow
     * emulator transcribe a whole recording in one piece and pass a longer limit. */
    private val deadlineSeconds:Int=CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS,
    /** F4 initial prompt, "" for none (the default while [AndroidWhisperEngine.PROMPT_ENABLED] is off); the golden set passes it explicitly. */
    private val prompt:String=AndroidWhisperEngine.defaultPrompt(),
    /** v3.3 golden variants (DESIGN_V4 3.2-5..8); the app keeps every default. Decoding switches (B1, B4, B5). */
    private val decode:SttDecode=SttDecode.DEFAULT,
    /** B3: names for a sentence-style prompt ([SttPrompt.sentence], token-limited); used instead of [prompt] when not null. */
    private val promptNames:List<String>?=null,
    /** B2: the end of the previous piece of the same recording goes in front of the prompt ([SttPrompt.context]). */
    private val carryContext:Boolean=false,
    /** v3.3 (3.2-1): numbers of each finished piece (ms, fallbacks, repaired and dropped segments, uncertain numbers). */
    private val pieceStats:(SttPieceStats)->Unit={}):CallTranscriber {
    // S26 (DESIGN_V6 1.1): a 2-minute piece took 223 s cold and 330-470 s hot on 6 threads in the background (111-195 s per
    // audio minute, not the 85 s/min measured in the foreground on 10/30); 8 threads ~3x slower.
    private fun threads()=threadCount ?: threadSource?.invoke() ?: (Runtime.getRuntime().availableProcessors()-1).coerceIn(1,6)
    private fun segmentsOf(raw:ByteArray):List<CallTranscript.Segment> {
        val output=AndroidWhisperEngine.decodeOutput(raw) // no segments = silence; a malformed segment is dropped alone
        if(output.dropped>0)android.util.Log.w("ieum.stt","dropped ${output.dropped} malformed segment(s) of ${output.dropped+output.segments.length()}")
        val segments=segmentsOf(output)
        runCatching { pieceStats(SttPieceStats.of(output,segments)) }
        return segments
    }
    override fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> = open(model,stopped).use { it.transcribe(pcm,stopped) }
    override fun open(model:File,stopped:()->Boolean):CallTranscriber.Session {
        val session=engine.openVerifiedModel(model,{this.model.verifiedModelFile()!=null},threads(),stopped)
        return object:CallTranscriber.Session {
            private var previous=""
            private val base by lazy { promptNames?.let { SttPrompt.sentence(it,count=session::countTokens) } ?: prompt }
            override fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> {
                val hint=if(!carryContext) base else SttPrompt.join(SttPrompt.context(previous,count=session::countTokens),base,count=session::countTokens)
                val out=segmentsOf(session.transcribe(pcm,"ko",deadlineSeconds,hint,decode,stopped))
                if(carryContext) previous=out.joinToString(" ") { it.text.trim() }.trim()
                return out
            }
            override fun nextRecording() { previous="" }
            override fun close()=session.close()
        }
    }
    companion object {
        /** The segments of a decoded piece, with the numbers the recognizer was unsure of (r13-1's number guard). */
        fun segmentsOf(output:kr.callbrief.AndroidWhisperOutput):List<CallTranscript.Segment> =
            (0 until output.segments.length()).map { i -> val s=output.segments.getJSONObject(i)
                val unsure=s.optJSONArray("uncertainNumbers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                CallTranscript.Segment(s.getLong("startMs"),s.getLong("endMs"),s.getString("text"),unsure) }
    }
}

/** v3.3 (DESIGN_V4 3.2-1): the numbers of one finished piece. Never its text. */
internal data class SttPieceStats(val segments:Int,val repaired:Int,val dropped:Int,val uncertainNumbers:Int,val timings:JSONObject?) {
    companion object {
        fun of(o:kr.callbrief.AndroidWhisperOutput,segments:List<CallTranscript.Segment>)=
            SttPieceStats(segments.size,o.repaired,o.dropped,segments.sumOf { it.uncertainNumbers.size },o.timings)
        /** Running totals under [PhoneStatus.STT_DECODE] for the heartbeat's transcription.decode: {"pieces","segments",
         * "repaired","dropped","uncertainNumbers","timed","encodeMs","decodeMs","batchdMs","promptMs","sampleMs",
         * "fallbackP","fallbackH","last":{the timings of the last piece}}. Numbers only. */
        fun add(previous:JSONObject?,s:SttPieceStats):JSONObject {
            val o=previous?.let { JSONObject(it.toString()) } ?: JSONObject()
            fun plus(k:String,v:Number) { o.put(k,if(v is Long||v is Int) o.optLong(k,0)+v.toLong() else Math.round((o.optDouble(k,0.0).takeIf { it.isFinite() } ?: 0.0)*10+v.toDouble()*10)/10.0) }
            plus("pieces",1);plus("segments",s.segments);plus("repaired",s.repaired);plus("dropped",s.dropped);plus("uncertainNumbers",s.uncertainNumbers)
            s.timings?.let { t ->
                plus("timed",1)
                for(k in kr.callbrief.AndroidWhisperEngine.TIMING_KEYS) if(t.has(k)) { if(k.startsWith("fallback")) plus(k,t.getLong(k)) else plus(k,t.getDouble(k)) }
                o.put("last",JSONObject(t.toString()))
            }
            return o
        }
    }
}

/** One bounded pass: queue eligible recordings, transcribe the newest first, hand each transcript to the sink. */
internal class CallTranscriptionRunner(
    private val store:CallTranscriptionStore,
    private val model:PipelineSttModel,
    private val audio:PipelineAudioSource,
    private val transcriber:CallTranscriber,
    /** The recording's bytes, closed (wiped) when the pass over it ends. */
    private val read:(CallTranscriptionStore.Candidate)->PipelineAudioInput,
    /** null = eligible, otherwise a skip reason (excluded number, archived, folder access removed). */
    private val skipReason:(CallTranscriptionStore.Candidate)->String?,
    private val sink:()->CallTranscriptSink?,
    private val stopped:()->Boolean={false},
    private val now:()->Long=System::currentTimeMillis,
    private val speed:(Long)->Unit={},
    /** Segments and sentences that [TranscriptClean] removed from one transcript (added up in the settings). */
    private val cleanedCount:(Int)->Unit={},
    /** True for an old recording (처음 정리) that has to wait for the charger or more battery. */
    private val holdOld:(CallTranscriptionStore.Candidate)->Boolean={false},
    /** The start of the live window while the first pass runs: older recordings are done after newer ones, oldest first. */
    private val oldBefore:()->Long?={null},
    /** True when the stop is the system's work time limit (about 10 minutes), not a cancel or a constraint (review #12). */
    private val timeLimited:()->Boolean={false},
    /** r12-8 A3: past this much of the run (by [now]) no new recording is begun; the one under way is finished. */
    private val newWorkMs:Long=NEW_WORK_MS,
    /** v3.6 (DESIGN_V6 2.2): on battery, only today's short calls, newest first (null: plugged in, every recording). */
    private val battery:CallTranscriptionStore.BatteryWindow?=null,
    /** v3.6 W1: how long this run may go (9 minutes, or what is left of the day's battery budget). */
    private val runLimitMs:Long=TranscriptionPower.SELF_END_MS,
    /** v3.6 W1/W2: work ms per encoder-window ms of recent pieces (v3.6.1 T1, [PowerPolicy.rate]; null: none measured yet). */
    private val rate:()->Double?={null},
    /** v3.6: the power still allows another piece (checked between pieces; the plug pulled on a plugged-in run ends it). */
    private val keepGoing:()->Boolean={true},
    /** v3.6 (DESIGN_V6 3.1): what the run did, for the power ledger. */
    private val events:Events=Events.NONE,
    /** v3.6: the first piece of this run counts as cold (the phone rested before it). */
    private val coldStart:Boolean=false,
    /** v3.7 T5: the stop under way is one the run is not to blame for (the foreground service's time limit, or a refused
     * foreground start): it ends like a [Yield], the attempt given back and no stop counted against the piece. */
    private val yieldStop:()->Boolean={false},
) {
    /** v3.6 (DESIGN_V6 3.1): numbers only, for the power ledger. */
    interface Events {
        fun piece(ms:Long,audioMs:Long,cold:Boolean) {}
        fun killed(ms:Long) {}
        fun deadline(ms:Long) {}
        fun yielded() {}
        /** v3.7 (T3): the run ended itself ([Yield]) with this much of its time limit left (today's yieldMs). */
        fun yieldLeft(ms:Long) {}
        /** v3.7 (T3): time spent decoding one recording's audio (the decoder's own time, the pieces' work taken out). */
        fun decodeAudio(ms:Long) {}
        fun modelLoad(ms:Long) {}
        /** A piece is about to begin (the first of the run: the battery run's start is written before it). */
        fun started() {}
        /** v3.7 r17-3: a recording's text is begun (claimed), for the heartbeat's trace[].textStartS. */
        fun begun(recordingId:String) {}
        companion object { val NONE=object:Events {} }
    }
    data class Pass(val state:String,val done:Int,val failed:Int,val pieces:Int=0)
    /** The worker was told to stop; finished pieces are kept for the next run. */
    internal class Stopped:RuntimeException("ANDROID_STT_STOPPED")
    /** r12-8 A4: a piece ran into the native deadline; it is marked to be done as two halves and the attempt is given back. */
    internal class Deadline:RuntimeException(CallTranscriptionStore.DEADLINE)
    /** v3.6 W1: the next piece would not fit in the run (or the power changed): the run ends itself before the piece is begun.
     * The attempt is given back and no stop is counted against the piece (stop() would count it, DESIGN_V6 함정 ①). */
    internal class Yield:RuntimeException("ANDROID_STT_YIELD")
    /** Pieces finished in this run (also when it ends stopped). */
    private var pieces=0
    /** Pieces of the current recording finished in this run: a time-limit stop after progress is no stall (review #12). */
    private var recordingPieces=0
    /** r12-8 A3: the current recording is not the first one this run took up. */
    private var laterInRun=false
    /** The last stop of [transcribeRecording] came after progress at the time limit (an hour-long call needs ~10 runs). */
    internal var lastStopHarmless=false;private set
    /** v3.6 W3: the model session, kept from one recording to the next within [run] (closed at its end, or after a stop). */
    private var shared:CallTranscriber.Session?=null
    /** v3.6: pieces begun in this run (W1: the first one always starts; the battery budget counts runs that began one). */
    internal var startedPieces=0;private set
    /** v3.6: audio ms of the pieces finished in this run. */
    internal var doneAudioMs=0L;private set
    private var runStartedMs=0L
    /** v3.7 (T3): work ms of the pieces of the recording being decoded, and the start of the piece under way (0: none). */
    private var pieceWorkMs=0L
    private var workStart=0L
    private fun closeShared() { runCatching { shared?.close() };shared=null }

    /** One recording through the model. F6: pieces come one at a time from the decoder and each is transcribed before the
     * next is decoded, so a long call never sits in memory whole. Pieces finished earlier ([CallTranscriptionStore.parts])
     * are taken as they are; a stop throws [Stopped] after saving every finished piece. Device tests call this directly. */
    internal fun transcribeRecording(key:CallTranscriptionStore.Key,input:PipelineAudioInput,mime:String,file:File,
                                     /** v3.6 W3: [run] keeps the session for the next recording; a direct call closes it at the end. */
                                     keepSession:Boolean=false):Pair<PipelineAudioInfo,List<CallTranscript.Segment>> {
        val segments=mutableListOf<CallTranscript.Segment>()
        val finished=store.parts(key) // pieces finished before the worker was stopped last time
        shared?.nextRecording()
        if(runStartedMs==0L) runStartedMs=now()
        recordingPieces=0;lastStopHarmless=false
        // r12-8 A3: a recording that was not the first of its run and is stopped by the time limit had no fair chance: no stall
        // either. The first one of a run that cannot finish a piece still counts (else it could loop for ever).
        val startedLate=laterInRun
        // A stop is counted against the piece only when it is no time-limit stop after progress (review #12).
        fun stop(stallId:Int):Nothing {
            if(yieldStop()) throw Yield() // v3.7 T5: not this piece's fault
            lastStopHarmless=harmlessStop(timeLimited(),recordingPieces,startedLate)
            if(!lastStopHarmless)store.noteStall(key,stallId)
            throw Stopped()
        }
        // One piece: skipped when finished earlier; a stop while it runs is counted against [stallId] (the whole chunk).
        fun piece(partId:Int,stallId:Int,offset:Long,pcm:FloatArray):List<CallTranscript.Segment> {
            finished[partId]?.let { return it }
            // r12-8 A5: under 0.1 s there is nothing to hear and the runtime would refuse it (ANDROID_STT_PCM_SIZE).
            if(pcm.size<MIN_PIECE_SAMPLES)return emptyList()
            if(stopped()){if(yieldStop()) throw Yield();lastStopHarmless=harmlessStop(timeLimited(),recordingPieces,startedLate);throw Stopped()} // not started: no stall
            // v3.6 W1: a piece that would not fit in what is left of the run is not begun (the first one of a run always is).
            val audioMs=pcm.size*1000L/PipelineAudioMath.TARGET_RATE
            val first=startedPieces==0
            if(!first && !keepGoing() || !PowerPolicy.startPiece(first,now()-runStartedMs,PowerPolicy.expectedPieceMs(audioMs,rate()),runLimitMs)) throw Yield()
            if(startedPieces==0) events.started()
            startedPieces++
            val began=now()
            val raw=try {
                    workStart=now()
                    val session=shared ?: run { val t=now();transcriber.open(file,stopped).also { shared=it;events.modelLoad(now()-t) } }
                    session.transcribe(pcm,stopped)
                } catch(e:Exception) {
                    pieceWorkMs+=now()-began;workStart=0L
                    closeShared() // a handle that was cancelled or failed is not used again (W3)
                    if(stopped()){events.killed(now()-began);stop(stallId)} // cancel() ends the native run with an error
                    // r12-8 A4: the native run ended by its own deadline (not a stop): a whole piece is halved next time.
                    if(deadline(e) && partId>=0 && pcm.size>=MIN_SPLIT_SAMPLES){events.deadline(now()-began);store.noteDeadline(key);store.askSplit(key,stallId);throw Deadline()}
                    if(deadline(e)) { events.deadline(now()-began);store.noteDeadline(key) }
                    throw e
                }
            pieceWorkMs+=now()-began;workStart=0L
            if(stopped()){events.killed(now()-began);closeShared();stop(stallId)} // a piece cut short is not kept
            events.piece(now()-began,audioMs,first && coldStart)
            val shifted=raw.map { it.copy(startMs=it.startMs+offset,endMs=it.endMs+offset) } // v3.3: uncertain numbers go along
            store.savePart(key,partId,shifted);pieces++;recordingPieces++;doneAudioMs+=audioMs;return shifted
        }
        pieceWorkMs=0L;workStart=0L
        val decodeBegan=now()
        try {
            val info=audio.decode(input,mime) { index,offset,pcm ->
                val kept=finished[index]
                if(kept!=null) segments+=kept
                else if(pcm.size>=MIN_SPLIT_SAMPLES && (store.splitAsked(key,index) || store.stalls(key,index)>=SPLIT_AFTER_STOPS ||
                        // v3.6 W2: expected to run into the native deadline: halved before it is begun, and marked so the next
                        // run (perhaps cooler) does not take it whole again and throw the saved halves away.
                        PowerPolicy.splitAhead(PowerPolicy.splitExpectMs(pcm.size*1000L/PipelineAudioMath.TARGET_RATE,rate()),NATIVE_DEADLINE_SECONDS).also { if(it) store.askSplit(key,index) })) {
                    // Ran into the native deadline (r12-8 A4), or stopped three times in a row on this piece: do it as two
                    // halves (saved separately, ids -1,-2,-3...).
                    val mid=PipelineAudioMath.halfPoint(pcm)
                    segments+=piece(-(2*index+1),index,offset,pcm.copyOfRange(0,mid))
                    segments+=piece(-(2*index+2),index,offset+mid*1000L/PipelineAudioMath.TARGET_RATE,pcm.copyOfRange(mid,pcm.size))
                } else segments+=piece(index,index,offset,pcm)
            }
            return info to segments
        } finally {
            // v3.7 (T3): the decoder's own time: the whole decode less the pieces' work in it (a piece still under way at a throw too).
            runCatching { val inPiece=pieceWorkMs+(if(workStart>0) now()-workStart else 0);events.decodeAudio((now()-decodeBegan-inPiece).coerceAtLeast(0)) }
            if(!keepSession) closeShared()
        }
    }

    /** A stopped run gives its attempt back, unless the recording keeps stalling: then the attempt counts and, at the limit, it fails. */
    internal fun endStopped(k:CallTranscriptionStore.Key) {
        // A time-limit stop after progress always gives the attempt back: a long call is not "stalled" (review #12).
        val refund=lastStopHarmless || refundStop(store.totalStalls(k))
        if(!refund&&store.attempts(k)>=MAX_ATTEMPTS)store.finish(k,"FAILED","STALLED") else store.finish(k,"QUEUED","STOPPED",refundAttempt=refund)
    }

    fun run(maxJobs:Int=10):Pass = try { runJobs(maxJobs) } finally { closeShared() }
    private fun runJobs(maxJobs:Int):Pass {
        store.recoverRunning(MAX_ATTEMPTS);store.skipSuperseded()
        store.offerAll(store.candidates(),skipReason,stopped) // one transaction for the whole list (r7-1)
        if(stopped())return Pass("STOPPED",0,0)
        val target=sink() ?: return Pass("NO_SINK",0,0)
        val file=model.verifiedModelFile() ?: return Pass("NO_MODEL",0,0)
        var done=0;var failed=0;pieces=0;startedPieces=0;doneAudioMs=0
        val runStarted=now();var taken=0;runStartedMs=runStarted
        repeat(maxJobs) {
            if(stopped())return Pass("STOPPED",done,failed,pieces)
            // r12-8 A3: late in the run no new recording is begun (it would only be cut off by the time limit); the next run takes it.
            if(taken>0 && now()-runStarted>=minOf(newWorkMs,runLimitMs))return Pass("MORE",done,failed,pieces)
            if(taken>0 && !keepGoing())return Pass("YIELD",done,failed,pieces)
            val job=store.nextQueued(oldBefore(),battery) ?: return Pass("IDLE",done,failed,pieces)
            val c=job.candidate
            if(holdOld(c))return Pass("WAIT_POWER",done,failed) // new ones come first: everything after this is old too
            if(!store.claim(c.key))return@repeat
            lastStopHarmless=false;laterInRun=taken++>0
            runCatching { events.begun(c.key.recordingId) }
            try {
                val started=now()
                val (decoded,segments)=read(c).use { input -> transcribeRecording(c.key,input,c.mime,file,keepSession=true) }
                store.clearParts(c.key)
                if(decoded.durationMs>0)store.noteLength(c.key,decoded.durationMs)
                val cleaned=TranscriptClean.clean(segments) // A9: just before saving; the pieces kept in the store stay raw
                if(cleaned.removed>0)cleanedCount(cleaned.removed)
                // One line per recognised piece (prompt rule 2); quotes are checked on bare text, so the line ends change nothing there.
                val text=cleaned.segments.map{it.text.trim()}.filter{it.isNotEmpty()}.joinToString("\n")
                skipReason(c)?.let { store.finish(c.key,"SKIPPED",it);return@repeat }
                if(text.isEmpty()){store.finish(c.key,"SKIPPED","NO_SPEECH");return@repeat}
                target.onTranscript(CallTranscript(c.key.recordingId,c.key.sourceVersion,c.key.audioSha,c.startedAtMs,decoded.durationMs,
                    counterpart(c.displayName),text,cleaned.segments,model.modelName,model.modelSha256,decoded.vadApplied))
                store.finish(c.key,"DONE","")
                if(decoded.durationMs>0)speed((now()-started)*60000/decoded.durationMs)
                done++
            } catch(e:Stopped) { endStopped(c.key);return Pass("STOPPED",done,failed,pieces) }
            // v3.6 W1: the run ends itself before a piece that would not fit; the recording is first in line next time.
            catch(e:Yield) { events.yielded();events.yieldLeft((runLimitMs-(now()-runStarted)).coerceIn(0,runLimitMs));store.finish(c.key,"QUEUED","STOPPED",refundAttempt=true);return Pass("YIELD",done,failed,pieces) }
            // r12-8 A4: not a used-up attempt; the piece is halved next time. The rest of the run goes on.
            catch(e:Deadline) { store.finish(c.key,"QUEUED",CallTranscriptionStore.DEADLINE,refundAttempt=true) }
            catch(e:Exception) {
                if(stopped() && yieldStop()) { events.yielded();store.finish(c.key,"QUEUED","STOPPED",refundAttempt=true);return Pass("YIELD",done,failed,pieces) }
                if(stopped()){endStopped(c.key);return Pass("STOPPED",done,failed,pieces)}
                val code=e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,80}"))} ?: e.javaClass.simpleName
                // v3.6 W4: a failure in the recording itself comes back the same: failed for good at once, never revived.
                // A codec error the codec calls transient (busy) is not one of them (review r16-1 #5).
                if(CallTranscriptionStore.permanent(code) && !transientCodec(e)){store.failPermanently(c.key,code);failed++}
                else if(store.attempts(c.key)>=MAX_ATTEMPTS){store.finish(c.key,"FAILED",code);failed++} else store.finish(c.key,"QUEUED",code)
            }
        }
        return Pass("MORE",done,failed,pieces)
    }

    companion object {
        const val MAX_ATTEMPTS=3
        /** The native run ends by itself after this long (the work limit is 10 minutes; one piece must not outlast it). */
        const val NATIVE_DEADLINE_SECONDS=540
        /** From this many stops on one recording the stopped attempt is no longer refunded, so it ends in FAILED instead of looping. */
        const val MAX_REFUNDED_STALLS=6
        fun refundStop(totalStalls:Int)=totalStalls<MAX_REFUNDED_STALLS
        /** A stop at the work time limit after at least one piece of the recording was finished in this run. */
        fun harmlessStop(timeLimited:Boolean,piecesThisRun:Int,laterInRun:Boolean=false)=timeLimited && (piecesThisRun>0 || laterInRun)
        /** r12-8 A3: no new recording is begun after this much of a run (WorkManager stops the run at about 10 minutes). */
        const val NEW_WORK_MS=6*60_000L
        /** r12-8 A4: the native run ended by its own [NATIVE_DEADLINE_SECONDS] (ieum_whisper.cpp ends it with
         * ANDROID_STT_NOT_COMPLETED); a stop signal is told apart by the caller before this is asked. */
        fun deadline(e:Exception)=e.message=="ANDROID_STT_NOT_COMPLETED"
        /** A MediaCodec error, here or as a cause, that the codec says may go away on its own. */
        fun transientCodec(e:Throwable):Boolean = generateSequence(e) { it.cause }.take(5).any { it is android.media.MediaCodec.CodecException && it.isTransient }
        /** Without a stop reason (before Android 12): a stop this long after the start is taken as the 10 minute limit. */
        const val TIME_LIMIT_NEAR_MS=9*60_000L
        /** Queue the next pass at once only when this one made progress; otherwise the hourly job takes over. */
        fun requeueNow(state:String,pieces:Int)=state=="MORE"||state=="YIELD"||(state=="STOPPED"&&pieces>0)
        /** The same piece stopped this many times in a row is halved; not worth halving under ten seconds. */
        const val SPLIT_AFTER_STOPS=3
        const val MIN_SPLIT_SAMPLES=PipelineAudioMath.TARGET_RATE*10
        /** The native runtime's smallest piece (ieum_whisper.cpp: 1600 samples, 0.1 s at 16 kHz). */
        const val MIN_PIECE_SAMPLES=1600
        private val PREFIXES=listOf("통화 녹음","통화녹음","Call recording","Call Recording")
        /** "통화 녹음 홍길동_251029_101500.m4a" -> "홍길동"; "01012345678_20251029101500.m4a" -> "010-1234-5678"; else null. */
        fun counterpart(displayName:String):String? {
            var s=displayName.substringBeforeLast('.').trim()
            PREFIXES.firstOrNull{s.startsWith(it)}?.let{s=s.removePrefix(it).trim()}
            val parts=s.split('_').map{it.trim()}.filter{it.isNotEmpty()}
            val name=(if(parts.size>1&&parts.drop(1).all{p->p.all{it.isDigit()}})parts.first() else parts.firstOrNull() ?: "").trim()
            if(name.isEmpty()||name.all{it.isDigit()}&&name.length>=12)return null // a bare timestamp
            val digits=name.filter{it.isDigit()}
            if(digits.length>=9&&name.all{it.isDigit()||it=='-'||it==' '||it=='+'||it=='('||it==')'||it=='.'}) {
                // "+82 10..." and "010..." are one number (PhoneKey, r9-1); a foreign number keeps its digits.
                val key=kr.callbrief.core.PhoneKey.of(name) ?: return digits
                return if(key.length==11&&key.startsWith("01")) "${key.take(3)}-${key.substring(3,7)}-${key.takeLast(4)}" else key
            }
            return name.take(40)
        }
    }
}

/** Hourly safety net (and v3.6's delayed retry, [CallTranscriptionWork.later]): only queues the one-time work under the one
 * name [CallTranscriptionWork] uses (KEEP), so two transcription passes never run side by side and `recoverRunning` never
 * meets another worker's RUNNING row. */
class CallTranscriptionTick(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { CallTranscriptionWork.enqueue(applicationContext,ExistingWorkPolicy.KEEP);return Result.success() }
}

/** v3.6 (DESIGN_V6 2.2-5): the charger trigger. WorkManager starts it once the phone is plugged in and charging; it only
 * queues the run (which has no constraints, so Samsung's battery protection pausing the charge does not stop it). */
class CallTranscriptionPlug(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { CallTranscriptionWork.enqueue(applicationContext);return Result.success() }
}

/** WorkManager entry: runs soon after new recordings are imported; [CallTranscriptionTick] queues it hourly.
 * v3.6 (DESIGN_V6 2): no WorkManager constraint; each wake reads the battery and asks [PowerPolicy] whether and how to run. */
class CallTranscriptionWork(context:Context,params:WorkerParameters):Worker(context,params) {
    @Volatile private var engine:AndroidWhisperEngine?=null
    /** The system asks us to stop (time limit, replaced): end the native run now, not after the piece. */
    override fun onStopped() { runCatching { engine?.cancel() };super.onStopped() }
    /** v3.7 T5: this run is in the foreground now (its pieces are counted as such), and when it went in (elapsed ms). */
    @Volatile private var fgSince=0L

    private fun power(settings:SecureSettings)=PowerStats.parse(settings.snapshot().optString(PipelineSettingsKeys.TRANSCRIPTION_POWER))
    private fun savePower(settings:SecureSettings,change:(JSONObject)->JSONObject)=runCatching {
        settings.update { s -> s.put(PipelineSettingsKeys.TRANSCRIPTION_POWER,change(PowerStats.parse(s.optString(PipelineSettingsKeys.TRANSCRIPTION_POWER))).toString()) }
    }
    private fun chargingOnly(settings:SecureSettings)=settings.snapshot().optBoolean(PipelineSettingsKeys.TRANSCRIBE_CHARGING_ONLY,false)

    override fun doWork():Result {
        val storage=applicationContext
        val model=PipelineModelManager(storage)
        if(model.verifiedModelFile()==null){PipelineModelDownloadWork.enqueue(applicationContext);return Result.success()}
        val settings=SecureSettings(storage)
        val now=System.currentTimeMillis()
        val start=BatterySnapshot.read(storage)
        val cores=CpuSet.readAllowed()
        savePower(settings) { o -> PowerStats.noteSnapshot(PowerStats.notePlug(o,start,now),start,cores,CpuSet.readCode()) }
        val budget=PowerStats.budget(power(settings),start,now,chargingOnly(settings))
        val decision=PowerPolicy.decide(start,budget,now)
        if(start.isPlugged && budget.levelFalling) savePower(settings) { PowerStats.add(it,now,"levelFallingRuns") }
        if(start.isPlugged) armPlug(storage,false) else armPlug(storage,true)
        if(decision is PowerPolicy.Decision.Wait) {
            // Only decided: no run counted, no budget used (DESIGN_V6 함정 ⑥). An explicit delay, not WorkManager's backoff.
            savePower(settings) { o -> var x=PowerStats.noteSkip(o,now,decision.reason)
                if(decision.reason=="budget") PowerPolicy.budgetHit(budget)?.let { x=PowerStats.noteBudgetHit(x,now,it) }
                // v3.6.1 (T1): why it is hot, counted; the rest clears a slow verdict once it is over.
                if(decision.reason=="hot") x=PowerStats.noteHot(x,now,if(start.thermal>=PowerPolicy.SEVERE) "severe" else PowerPolicy.hotWhy(start,budget));x }
            decision.retryInMs?.let { later(storage,it) }
            // The waiting count opens the store: at most every half hour from a wake that only decided.
            if(decision.reason!="gap" && decision.reason!="inCall" && now-(power(settings).optJSONObject("waiting")?.optLong("atMs",0) ?: 0)>=TranscriptionPower.BATTERY_RUN_GAP_MS) noteWaiting(settings,now)
            return Result.success()
        }
        val run=decision as PowerPolicy.Decision.Run
        val threads=PowerPolicy.threads(run.threads,cores)
        val severe={ BatterySnapshot.read(storage).thermal>=PowerManager.THERMAL_STATUS_SEVERE }
        val began=android.os.SystemClock.elapsedRealtime()
        // v3.7 r17-3 (DESIGN_V7 3.3): the run's process CPU time, start to end (/proc/self/stat).
        val cpuAtStart=Telemetry.processCpuMs()
        // v3.6.1 (T1): also the first piece after a hot rest is a cold one (one slow piece, one rest).
        val coldStart=PowerStats.coldStart(power(settings),now)
        var lastCheck=0L;var lastOk=true
        // v3.6.1 (T3a): this battery run's own start mark ([PowerStats.noteBatteryStart]); the checks between pieces do not
        // take it for another run that holds the gap.
        var ownStart:Long?=null
        // Between pieces: the same mode still applies (a plug pulled mid-run hands the rest to the battery rules, and a plug
        // put in on battery hands it to the plugged rules); read at most every 20 seconds.
        val keepGoing={ val t=System.currentTimeMillis()
            if(t-lastCheck>=20_000L) { lastCheck=t;val s=BatterySnapshot.read(storage).also { s -> savePower(settings) { PowerStats.notePlug(it,s,t) } }
                lastOk=PowerStats.stillRuns(power(settings),s,t,chargingOnly(settings),run.mode,ownStart) }
            lastOk }
        // v3.7 T5 (DESIGN_V7 3.1): a plugged-in run on a foreground day goes into the foreground (a measurement).
        val fgSetting=ForegroundPolicy.setting(settings.snapshot().optString(PipelineSettingsKeys.TRANSCRIBE_FOREGROUND))
        savePower(settings) { PowerStats.noteFgSetting(it,fgSetting,ForegroundPolicy.onToday(fgSetting,now)) }
        if(ForegroundPolicy.wanted(fgSetting,run.mode,now,PowerStats.fgUsedMs(power(settings),now),PowerStats.fgRefusedToday(power(settings),now))) {
            if(TranscriptionForeground.start(this)) fgSince=android.os.SystemClock.elapsedRealtime()
            else savePower(settings) { PowerStats.noteFg(it,System.currentTimeMillis(),"refused") }
        }
        val events=object:CallTranscriptionRunner.Events {
            override fun piece(ms:Long,audioMs:Long,cold:Boolean) { savePower(settings) { PowerStats.notePiece(it,ms,audioMs,threads,cold,
                foreground=if(run.mode==PowerPolicy.Mode.PLUGGED) fgSince>0 else null) } }
            override fun started() { if(run.mode==PowerPolicy.Mode.BATTERY) { val t=System.currentTimeMillis();ownStart=t;savePower(settings) { PowerStats.noteBatteryStart(it,t) } } }
            override fun killed(ms:Long) { savePower(settings) { PowerStats.add(it,System.currentTimeMillis(),"killedChunkMs",ms) } }
            override fun deadline(ms:Long) { savePower(settings) { PowerStats.add(it,System.currentTimeMillis(),"deadlineMs",ms) } }
            override fun yielded() { savePower(settings) { PowerStats.add(it,System.currentTimeMillis(),"yieldCount") } }
            override fun yieldLeft(ms:Long) { savePower(settings) { PowerStats.add(it,System.currentTimeMillis(),"yieldMs",ms) } }
            override fun decodeAudio(ms:Long) { savePower(settings) { PowerStats.add(it,System.currentTimeMillis(),"decodeAudioMs",ms) } }
            override fun begun(recordingId:String) { runCatching { settings.update { s -> val prev=runCatching { JSONObject(s.optString(PipelineSettingsKeys.TRACE_TEXT_START)) }.getOrNull()
                if(prev?.has(recordingId)!=true) s.put(PipelineSettingsKeys.TRACE_TEXT_START,Telemetry.noteTextStart(prev,recordingId,System.currentTimeMillis()).toString()) } } }
            override fun modelLoad(ms:Long) { savePower(settings) { o -> PowerStats.add(PowerStats.add(o,System.currentTimeMillis(),"modelLoads"),System.currentTimeMillis(),"modelLoadMs",ms) } }
        }
        return try {
            GeneralLocalStore(storage).use { local -> CallTranscriptionStore(storage).use { store ->
                // v3.6 W5: failed rows get their new-build try only while plugged in.
                if(run.mode==PowerPolicy.Mode.PLUGGED) runCatching { store.requeueFailed() }
                val window=if(run.mode==PowerPolicy.Mode.BATTERY) CallTranscriptionStore.BatteryWindow(PowerPolicy.dayStartMs(now),store.bytesPerMs()) else null
                val runner=CallTranscriptionRunner(store,model,PipelineAudioDecoder(channels={ r -> settings.update{ PipelineAudioDecoder.accumulate(it,r) } }),WhisperCallTranscriber(AndroidWhisperEngine(storage).also { engine=it },model,threadCount=threads,
                        pieceStats={ st -> runCatching { settings.update { s -> s.put(PhoneStatus.STT_DECODE,SttPieceStats.add(runCatching { JSONObject(s.optString(PhoneStatus.STT_DECODE)) }.getOrNull(),st).toString()) } } }),
                    read={ c -> readAudio(local,c) },
                    skipReason={ c -> skipReason(c,settings) },
                    // The app may not have been opened since the phone restarted: the sink is made here then (review 11/4, round 2).
                    sink={ CallTranscriptSinks.factory?.invoke(applicationContext) ?: CallSinkImpl(applicationContext) },
                    stopped={ isStopped },
                    cleanedCount={ n -> settings.update{ it.put(PipelineSettingsKeys.TRANSCRIPT_CLEANED,it.optLong(PipelineSettingsKeys.TRANSCRIPT_CLEANED,0)+n) } },
                    speed={ ms -> settings.update{ it.put(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,ms).put(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,System.currentTimeMillis()) } },
                    // v3.6: old recordings (처음 정리) go only on a plugged-in run; a battery run takes today's calls only (in SQL).
                    holdOld={ false },
                    oldBefore={ FirstPass.liveFrom(settings.snapshot(),System.currentTimeMillis()) },
                    timeLimited={ timeLimitStop(began) },
                    battery=window,
                    runLimitMs=run.runLimitMs,
                    rate={ PowerStats.rate(power(settings),threads) },
                    // SEVERE heat ends the run after the piece under way (it was a stop before; now the piece is kept).
                    keepGoing={ keepGoing() && !severe() },
                    events=events,
                    coldStart=coldStart,
                    // v3.7 T5: the foreground service's time limit (or the user closing it) is no stall of the piece.
                    yieldStop={ fgStop() })
                val pass=runner.run()
                val end=System.currentTimeMillis()
                noteFgEnd(settings)
                noteRun(settings,began,pass.state)
                if(runner.startedPieces>0) {
                    val endSnap=BatterySnapshot.read(storage)
                    // Charge used: battery runs with the screen off at both ends only (the phone's whole use, an upper bound).
                    val uah=if(run.mode==PowerPolicy.Mode.BATTERY && !start.screenOn && !endSnap.screenOn) start.chargeUah?.let { a -> endSnap.chargeUah?.let { b -> (a-b).takeIf { it>0 } } } else null
                    savePower(settings) { o -> var x=PowerStats.noteRun(o,end,run.mode,end-now,runner.doneAudioMs,uah)
                        // Begun as plugged in (or within the plug debounce) but ended on battery: the time since the plug was
                        // last seen goes to the battery budget, with the gap after it (review r16-1 #3).
                        if(run.mode==PowerPolicy.Mode.PLUGGED && !endSnap.isPlugged) {
                            val since=o.optLong("lastPluggedMs",now).coerceIn(now,end)
                            x=PowerStats.noteRun(x,end,PowerPolicy.Mode.BATTERY,end-since,0,null)
                        }
                        if(pass.state=="STOPPED") x=PowerStats.noteStop(x,end,if(timeLimitStop(began)) "timeout" else "other")
                        // v3.7 r17-3: CPU ms of the run and, on battery, the charge counter's drop (whatever the screen did).
                        val drop=if(run.mode==PowerPolicy.Mode.BATTERY) start.chargeUah?.let { a -> endSnap.chargeUah?.let { b -> (a-b).takeIf { it>0 } } } else null
                        PowerStats.noteCpu(x,end,cpuAtStart?.let { a -> Telemetry.processCpuMs()?.let { b -> (b-a).takeIf { it>=0 } } },drop) }
                }
                runCatching { val (n,oldest)=store.powerWaiting(CallTranscriptionStore.BatteryWindow(PowerPolicy.dayStartMs(end),store.bytesPerMs()),chargingOnly(settings))
                    savePower(settings) { PowerStats.noteWaiting(it,n,oldest) } }
                // Stopped part way: queue the next pass (the finished pieces are kept) instead of waiting for the hourly one:
                // at once while plugged in, after the gap on battery (the next wake decides again either way).
                if(CallTranscriptionRunner.requeueNow(pass.state,pass.pieces) || severe()) {
                    if(severe()) later(applicationContext,TranscriptionPower.HOT_DELAY_MS*2)
                    // A battery run that ended because the plug went in goes on at once (review r16-1 #7).
                    else if(run.mode==PowerPolicy.Mode.PLUGGED || BatterySnapshot.read(storage).isPlugged) enqueue(applicationContext) else later(applicationContext,TranscriptionPower.BATTERY_RUN_GAP_MS)
                }
                Result.success()
            } }
        } catch(_:Exception) { noteFgEnd(settings);noteRun(settings,began,"ERROR");if(runAttemptCount<3)Result.retry() else Result.failure() }
    }

    /** v3.7 T5: the stop under way came from the foreground service (its time limit, or the user closing it), not the piece. */
    private fun fgStop():Boolean = isStopped && TranscriptionForeground.yieldStop(stopCode(),fgSince>0)
    /** The system's stop reason (Android 12 and later), else unknown. */
    private fun stopCode():Int = if(android.os.Build.VERSION.SDK_INT>=31) runCatching { stopReason }.getOrDefault(androidx.work.WorkInfo.STOP_REASON_UNKNOWN) else androidx.work.WorkInfo.STOP_REASON_UNKNOWN
    /** v3.7 T5: the foreground time of this run into today.fg (once), and a time-limit stop as fg.timeout. */
    private fun noteFgEnd(settings:SecureSettings) {
        val since=fgSince;if(since<=0) return;fgSince=0
        val ms=android.os.SystemClock.elapsedRealtime()-since
        val timeout=isStopped && stopCode()==androidx.work.WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT
        savePower(settings) { o -> PowerStats.noteFg(o,System.currentTimeMillis(),if(timeout) "timeout" else "run",ms) }
    }

    /** The queue as the battery rules see it, for the settings line, the light and the heartbeat (opens the store). */
    private fun noteWaiting(settings:SecureSettings,now:Long) = runCatching {
        CallTranscriptionStore(applicationContext).use { store ->
            val (n,oldest)=store.powerWaiting(CallTranscriptionStore.BatteryWindow(PowerPolicy.dayStartMs(now),store.bytesPerMs()),chargingOnly(settings))
            savePower(settings) { PowerStats.noteWaiting(it,n,oldest) }
        }
    }

    /** r12-8 A1: one more run of the day for the heartbeat (runs, minutes, how it ended). A failure loses only the count. */
    private fun noteRun(settings:SecureSettings,beganElapsed:Long,state:String) = runCatching {
        val ms=android.os.SystemClock.elapsedRealtime()-beganElapsed
        val end=TranscriptionRuns.endCode(state,state=="STOPPED" && timeLimitStop(beganElapsed))
        val day=TranscriptionRuns.day(System.currentTimeMillis())
        settings.update { s -> s.put(PipelineSettingsKeys.TRANSCRIPTION_RUNS,TranscriptionRuns.add(
            runCatching { org.json.JSONObject(s.optString(PipelineSettingsKeys.TRANSCRIPTION_RUNS)) }.getOrNull(),day,ms,end).toString()) }
    }

    /** The system's time limit stopped the work: its own reason on Android 12 and later, else a run near the 10 minute limit. */
    private fun timeLimitStop(beganElapsed:Long):Boolean {
        // v3.7 T5: the foreground service's own limit (Android 15+) is a time limit too.
        val code=stopCode()
        if(code==androidx.work.WorkInfo.STOP_REASON_TIMEOUT || code==androidx.work.WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) return true
        return android.os.SystemClock.elapsedRealtime()-beganElapsed>=CallTranscriptionRunner.TIME_LIMIT_NEAR_MS
    }

    /** The vault's verified stream read once into one array of the recording's size (no growing buffer, no second copy),
     * and the vault let go at once: its lock must not be held while Whisper runs for many minutes. */
    private fun readAudio(local:GeneralLocalStore,c:CallTranscriptionStore.Candidate):PipelineAudioInput {
        var out:PipelineBytesInput?=null
        local.encrypted.withReadOnlySourceAudio(c.key.recordingId,c.key.sourceVersion,{ a -> a.plaintextSha256==c.key.audioSha && a.sourceVersion==c.key.sourceVersion }) { input -> out=PipelineBytesInput.read(input,c.bytes) }
        return checkNotNull(out){"PIPELINE_AUDIO_EMPTY"}
    }
    private fun skipReason(c:CallTranscriptionStore.Candidate,settings:SecureSettings):String? = when {
        c.archived -> "ARCHIVED"
        FirstPass.beforeRange(c.startedAtMs ?: c.modifiedMs) -> "BEFORE_START" // 처음 정리 reaches back to 10/1 only
        !c.grantActive -> "FOLDER_ACCESS_REMOVED"
        LocalPrivacy.blocked(RecordingRef(Uri.parse(c.uri),c.displayName,c.bytes,c.mime,c.startedAtMs?.let(Instant::ofEpochMilli),c.modifiedMs),settings.snapshot()) -> "EXCLUDED"
        else -> null
    }

    companion object {
        private const val ONCE="calls-transcription"
        /** r18-bench: the engine bench gives way while this unique work is queued or running ([kr.callbrief.pipeline.bench.Bench]). */
        internal const val ONCE_NAME=ONCE
        const val PERIODIC="calls-transcription-hourly"
        /** v3.6: the delayed retry (heat, battery gap) and the charger trigger, each under its own name. */
        const val LATER="calls-transcription-later"
        const val PLUG="ieum.calls.transcribe.plug"
        /** After new recordings arrive. A pass already running is followed by one more. No constraints (v3.6): the run decides. */
        fun enqueue(context:Context,policy:ExistingWorkPolicy=ExistingWorkPolicy.APPEND_OR_REPLACE) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE,policy,OneTimeWorkRequestBuilder<CallTranscriptionWork>().build())
        }
        /** v3.6: wake again after [delayMs] (a tick that queues the run), never holding the run's own chain. */
        fun later(context:Context,delayMs:Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(LATER,ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CallTranscriptionTick>().setInitialDelay(delayMs.coerceAtLeast(60_000L),TimeUnit.MILLISECONDS).build())
        }
        /** v3.6: the charger trigger, armed while on battery (KEEP), dropped while plugged in (it would only queue one more run). */
        fun armPlug(context:Context,on:Boolean) = runCatching {
            val wm=WorkManager.getInstance(context)
            if(on) wm.enqueueUniqueWork(PLUG,ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CallTranscriptionPlug>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build()).build())
            else wm.cancelUniqueWork(PLUG)
        }
        /** App start: hourly safety net, one pass now, and the model download if it is missing. */
        fun schedule(context:Context) {
            // UPDATE: the tick of older builds carried the battery-not-low constraint; v3.6 decides inside the run.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CallTranscriptionTick>(1,TimeUnit.HOURS).build())
            enqueue(context,ExistingWorkPolicy.KEEP)
            // Armed only on battery: while plugged in it would fire at once and add one more run (review r16-1 #8).
            armPlug(context,!BatterySnapshot.read(context).isPlugged)
        }
    }
}

/** v3.7 T5 (DESIGN_V7 3.1): the plugged-in transcription run in the foreground, a measurement of whether the process then runs
 * on faster cores. mediaProcessing on Android 15 and later, dataSync below (both declared on WorkManager's
 * SystemForegroundService in the manifest). A silent, minimum-importance notice of one line. */
internal object TranscriptionForeground {
    const val CHANNEL="ieum.transcribe.foreground"
    const val NOTIFICATION_ID=0x1E0_57
    const val TEXT="충전하는 동안 통화를 글자로 바꾸고 있어요"
    fun type():Int = when {
        android.os.Build.VERSION.SDK_INT>=35 -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        android.os.Build.VERSION.SDK_INT>=29 -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0 }
    fun info(context:Context):androidx.work.ForegroundInfo {
        val m=context.getSystemService(android.app.NotificationManager::class.java)
        runCatching { m?.createNotificationChannel(android.app.NotificationChannel(CHANNEL,"충전 중 글자로 바꾸기",android.app.NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null,null);enableVibration(false);setShowBadge(false) }) }
        val n=android.app.Notification.Builder(context,CHANNEL).setSmallIcon(kr.callbrief.R.drawable.ic_stat_ieum).setContentTitle(TEXT)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(android.app.Notification.CATEGORY_PROGRESS).build()
        val t=type()
        return if(t!=0) androidx.work.ForegroundInfo(NOTIFICATION_ID,n,t) else androidx.work.ForegroundInfo(NOTIFICATION_ID,n)
    }
    /** Asks WorkManager to run [worker] in the foreground; false when the system refused (ForegroundServiceStartNotAllowedException
     * and the like, or no answer in 10 seconds): the run then goes on in the background. */
    fun start(worker:androidx.work.ListenableWorker):Boolean = runCatching {
        // Review fix: asked only when the app is exempt from battery optimization (the documented exemption for a start from
        // the background). A refused start leaves WorkManager holding the work as foreground: its stops are then ignored.
        val app=worker.applicationContext
        if(app.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)!=true) return@runCatching false
        worker.setForegroundAsync(info(worker.applicationContext)).get(10,TimeUnit.SECONDS);true }.getOrDefault(false)
    /** A stop with [reason] ends the run as a yield, not a stall: the foreground service's time limit, or (while in the
     * foreground) the user stopping the app from its notice or the task manager. */
    fun yieldStop(reason:Int,inForeground:Boolean)=reason==androidx.work.WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
        inForeground && reason==androidx.work.WorkInfo.STOP_REASON_USER
}
