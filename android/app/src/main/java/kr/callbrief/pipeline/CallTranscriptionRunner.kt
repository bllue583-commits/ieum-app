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
    interface Session:AutoCloseable { fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> }
}

/** F2: 6 threads while charging or with at least half the battery, otherwise 4 (below 20 % nothing runs: see [FirstPass.powerOk] and the work constraint). */
internal object TranscriptionThreads {
    fun threadsFor(charging:Boolean,percent:Int)=if(charging||percent>=50) 6 else 4
    fun current(context:Context):Int {
        val b=context.getSystemService(android.os.BatteryManager::class.java) ?: return 4
        return threadsFor(b.isCharging,b.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
    }
}

/** Production transcriber: the pinned JNI runtime with the verified large model, Korean. */
internal class WhisperCallTranscriber(private val engine:AndroidWhisperEngine,private val model:PipelineSttModel,private val threadCount:Int?=null,
    /** Thread count read when a recording starts (battery state); ignored when [threadCount] is given. */
    private val threadSource:(()->Int)?=null):CallTranscriber {
    private fun threads()=threadCount ?: threadSource?.invoke() ?: (Runtime.getRuntime().availableProcessors()-1).coerceIn(1,6) // S26: 6 threads 85 s/min, 8 threads ~3x slower
    private fun segmentsOf(raw:ByteArray):List<CallTranscript.Segment> {
        val output=AndroidWhisperEngine.decodeOutput(raw) // no segments = silence; a malformed segment is dropped alone
        if(output.dropped>0)android.util.Log.w("ieum.stt","dropped ${output.dropped} malformed segment(s) of ${output.dropped+output.segments.length()}")
        return (0 until output.segments.length()).map { i -> val s=output.segments.getJSONObject(i)
            CallTranscript.Segment(s.getLong("startMs"),s.getLong("endMs"),s.getString("text")) }
    }
    override fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> = open(model,stopped).use { it.transcribe(pcm,stopped) }
    override fun open(model:File,stopped:()->Boolean):CallTranscriber.Session {
        val session=engine.openVerifiedModel(model,{this.model.verifiedModelFile()!=null},threads(),stopped)
        return object:CallTranscriber.Session {
            override fun transcribe(pcm:FloatArray,stopped:()->Boolean)=segmentsOf(session.transcribe(pcm,"ko",CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS,stopped))
            override fun close()=session.close()
        }
    }
}

/** One bounded pass: queue eligible recordings, transcribe the newest first, hand each transcript to the sink. */
internal class CallTranscriptionRunner(
    private val store:CallTranscriptionStore,
    private val model:PipelineSttModel,
    private val audio:PipelineAudioSource,
    private val transcriber:CallTranscriber,
    private val read:(CallTranscriptionStore.Candidate)->ByteArray,
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
    /** The first-pass start while it runs: older recordings are done after newer ones, oldest first. */
    private val oldBefore:()->Long?={null},
) {
    data class Pass(val state:String,val done:Int,val failed:Int,val pieces:Int=0)
    private class Stopped:RuntimeException()

    /** A stopped run gives its attempt back, unless the recording keeps stalling: then the attempt counts and, at the limit, it fails. */
    private fun endStopped(k:CallTranscriptionStore.Key) {
        val refund=refundStop(store.totalStalls(k))
        if(!refund&&store.attempts(k)>=MAX_ATTEMPTS)store.finish(k,"FAILED","STALLED") else store.finish(k,"QUEUED","STOPPED",refundAttempt=refund)
    }

    fun run(maxJobs:Int=10):Pass {
        store.recoverRunning(MAX_ATTEMPTS);store.skipSuperseded()
        for(c in store.candidates()){val reason=skipReason(c);store.offer(c,reason==null,reason ?: "")}
        val target=sink() ?: return Pass("NO_SINK",0,0)
        val file=model.verifiedModelFile() ?: return Pass("NO_MODEL",0,0)
        var done=0;var failed=0;var pieces=0 // pieces: chunks finished in this run
        repeat(maxJobs) {
            if(stopped())return Pass("STOPPED",done,failed,pieces)
            val job=store.nextQueued(oldBefore()) ?: return Pass("IDLE",done,failed)
            val c=job.candidate
            if(holdOld(c))return Pass("WAIT_POWER",done,failed) // new ones come first: everything after this is old too
            if(!store.claim(c.key))return@repeat
            try {
                val started=now()
                val decoded=audio.decode(read(c),c.mime)
                val segments=mutableListOf<CallTranscript.Segment>()
                val finished=store.parts(c.key) // pieces finished before the worker was stopped last time
                var session:CallTranscriber.Session?=null // the model is loaded on the first piece that needs it and closed below, whatever happens
                try {
                // One piece: skipped when finished earlier; a stop while it runs is counted against [stallId] (the whole chunk).
                fun piece(partId:Int,stallId:Int,offset:Long,pcm:FloatArray):List<CallTranscript.Segment> {
                    finished[partId]?.let { return it }
                    if(stopped())throw Stopped()
                    val raw=try { (session ?: transcriber.open(file,stopped).also{session=it}).transcribe(pcm,stopped) }
                        catch(e:Exception) { if(stopped()){store.noteStall(c.key,stallId);throw Stopped()};throw e } // cancel() ends the native run with an error
                    if(stopped()){store.noteStall(c.key,stallId);throw Stopped()} // a piece cut short is not kept
                    val shifted=raw.map { CallTranscript.Segment(it.startMs+offset,it.endMs+offset,it.text) }
                    store.savePart(c.key,partId,shifted);pieces++;return shifted
                }
                for((index,chunk) in decoded.chunks.withIndex()) {
                    val (offset,pcm)=chunk
                    val kept=finished[index]
                    if(kept!=null) { segments+=kept;continue }
                    if(pcm.size>=MIN_SPLIT_SAMPLES && store.stalls(c.key,index)>=SPLIT_AFTER_STOPS) {
                        // Stopped three times in a row on this piece: do it as two halves (saved separately, ids -1,-2,-3...).
                        val mid=PipelineAudioMath.halfPoint(pcm)
                        segments+=piece(-(2*index+1),index,offset,pcm.copyOfRange(0,mid))
                        segments+=piece(-(2*index+2),index,offset+mid*1000L/PipelineAudioMath.TARGET_RATE,pcm.copyOfRange(mid,pcm.size))
                    } else segments+=piece(index,index,offset,pcm)
                }
                } finally { runCatching { session?.close() } }
                store.clearParts(c.key)
                val cleaned=TranscriptClean.clean(segments) // A9: just before saving; the pieces kept in the store stay raw
                if(cleaned.removed>0)cleanedCount(cleaned.removed)
                val text=cleaned.segments.joinToString(""){it.text}.trim()
                skipReason(c)?.let { store.finish(c.key,"SKIPPED",it);return@repeat }
                if(text.isEmpty()){store.finish(c.key,"SKIPPED","NO_SPEECH");return@repeat}
                target.onTranscript(CallTranscript(c.key.recordingId,c.key.sourceVersion,c.key.audioSha,c.startedAtMs,decoded.durationMs,
                    counterpart(c.displayName),text,cleaned.segments,model.modelName,model.modelSha256,decoded.vadApplied))
                store.finish(c.key,"DONE","")
                if(decoded.durationMs>0)speed((now()-started)*60000/decoded.durationMs)
                done++
            } catch(e:Stopped) { endStopped(c.key);return Pass("STOPPED",done,failed,pieces) }
            catch(e:Exception) {
                if(stopped()){endStopped(c.key);return Pass("STOPPED",done,failed,pieces)}
                val code=e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,80}"))} ?: e.javaClass.simpleName
                if(store.attempts(c.key)>=MAX_ATTEMPTS){store.finish(c.key,"FAILED",code);failed++} else store.finish(c.key,"QUEUED",code)
            }
        }
        return Pass("MORE",done,failed)
    }

    companion object {
        const val MAX_ATTEMPTS=3
        /** The native run ends by itself after this long (the work limit is 10 minutes; one piece must not outlast it). */
        const val NATIVE_DEADLINE_SECONDS=540
        /** From this many stops on one recording the stopped attempt is no longer refunded, so it ends in FAILED instead of looping. */
        const val MAX_REFUNDED_STALLS=6
        fun refundStop(totalStalls:Int)=totalStalls<MAX_REFUNDED_STALLS
        /** Queue the next pass at once only when this one made progress; otherwise the hourly job takes over. */
        fun requeueNow(state:String,pieces:Int)=state=="MORE"||(state=="STOPPED"&&pieces>0)
        /** The same piece stopped this many times in a row is halved; not worth halving under ten seconds. */
        const val SPLIT_AFTER_STOPS=3
        const val MIN_SPLIT_SAMPLES=PipelineAudioMath.TARGET_RATE*10
        private val PREFIXES=listOf("통화 녹음","통화녹음","Call recording","Call Recording")
        /** "통화 녹음 홍길동_251029_101500.m4a" -> "홍길동"; "01012345678_20251029101500.m4a" -> "010-1234-5678"; else null. */
        fun counterpart(displayName:String):String? {
            var s=displayName.substringBeforeLast('.').trim()
            PREFIXES.firstOrNull{s.startsWith(it)}?.let{s=s.removePrefix(it).trim()}
            val parts=s.split('_').map{it.trim()}.filter{it.isNotEmpty()}
            val name=(if(parts.size>1&&parts.drop(1).all{p->p.all{it.isDigit()}})parts.first() else parts.firstOrNull() ?: "").trim()
            if(name.isEmpty()||name.all{it.isDigit()}&&name.length>=12)return null // a bare timestamp
            val digits=name.filter{it.isDigit()}
            if(digits.length>=9&&name.all{it.isDigit()||it=='-'||it==' '||it=='+'}) return when {
                digits.length==11&&digits.startsWith("01")->"${digits.take(3)}-${digits.substring(3,7)}-${digits.takeLast(4)}"
                else->digits
            }
            return name.take(40)
        }
    }
}

/** Hourly safety net: only queues the one-time work under the one name [CallTranscriptionWork] uses (KEEP), so two transcription
 * passes never run side by side and `recoverRunning` never meets another worker's RUNNING row. */
class CallTranscriptionTick(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { CallTranscriptionWork.enqueue(applicationContext,ExistingWorkPolicy.KEEP);return Result.success() }
}

/** WorkManager entry: runs soon after new recordings are imported; [CallTranscriptionTick] queues it hourly. */
class CallTranscriptionWork(context:Context,params:WorkerParameters):Worker(context,params) {
    @Volatile private var engine:AndroidWhisperEngine?=null
    /** The system asks us to stop (time limit, constraints, replaced): end the native run now, not after the piece. */
    override fun onStopped() { runCatching { engine?.cancel() };super.onStopped() }
    override fun doWork():Result {
        val storage=applicationContext
        val thermal=(applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        val tooHot={ android.os.Build.VERSION.SDK_INT>=29 && thermal!=null && thermal.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE }
        if(tooHot())return Result.retry()
        val model=PipelineModelManager(storage)
        if(model.verifiedModelFile()==null){PipelineModelDownloadWork.enqueue(applicationContext);return Result.success()}
        val settings=SecureSettings(storage)
        return try {
            GeneralLocalStore(storage).use { local -> CallTranscriptionStore(storage).use { store ->
                val pass=CallTranscriptionRunner(store,model,PipelineAudioDecoder(),WhisperCallTranscriber(AndroidWhisperEngine(storage).also { engine=it },model,threadSource={ TranscriptionThreads.current(storage) }),
                    read={ c -> readAudio(local,c) },
                    skipReason={ c -> skipReason(c,settings) },
                    // The app may not have been opened since the phone restarted: the sink is made here then (review 11/4, round 2).
                    sink={ CallTranscriptSinks.factory?.invoke(applicationContext) ?: CallSinkImpl(applicationContext) },
                    stopped={ isStopped || tooHot() },
                    cleanedCount={ n -> settings.update{ it.put(PipelineSettingsKeys.TRANSCRIPT_CLEANED,it.optLong(PipelineSettingsKeys.TRANSCRIPT_CLEANED,0)+n) } },
                    speed={ ms -> settings.update{ it.put(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,ms).put(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,System.currentTimeMillis()) } },
                    holdOld={ c -> FirstPass.isOld(c.startedAtMs ?: c.modifiedMs,settings.snapshot()) && !FirstPass.powerOk(applicationContext) },
                    oldBefore={ settings.snapshot().let { s -> FirstPass.start(s).takeUnless { FirstPass.done(s) } } }
                ).run()
                // Stopped part way: queue the next pass at once (the finished pieces are kept) instead of waiting for the hourly one.
                if(CallTranscriptionRunner.requeueNow(pass.state,pass.pieces))enqueue(applicationContext)
                Result.success()
            } }
        } catch(_:Exception) { if(runAttemptCount<3)Result.retry() else Result.failure() }
    }

    private fun readAudio(local:GeneralLocalStore,c:CallTranscriptionStore.Candidate):ByteArray {
        val out=java.io.ByteArrayOutputStream()
        local.encrypted.withReadOnlySourceAudio(c.key.recordingId,c.key.sourceVersion,{ a -> a.plaintextSha256==c.key.audioSha && a.sourceVersion==c.key.sourceVersion }) { input -> input.copyTo(out) }
        return out.toByteArray()
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
        const val PERIODIC="calls-transcription-hourly"
        private val constraints get()=Constraints.Builder().setRequiresBatteryNotLow(true).build()
        /** After new recordings arrive. A pass already running is followed by one more. */
        fun enqueue(context:Context,policy:ExistingWorkPolicy=ExistingWorkPolicy.APPEND_OR_REPLACE) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE,policy,
                OneTimeWorkRequestBuilder<CallTranscriptionWork>().setConstraints(constraints).build())
        }
        /** App start: hourly safety net, one pass now, and the model download if it is missing. */
        fun schedule(context:Context) {
            // UPDATE: a phone from before this change has the periodic work registered with the old worker class (a full pass); it is replaced by the tick.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CallTranscriptionTick>(1,TimeUnit.HOURS).setConstraints(constraints).build())
            enqueue(context,ExistingWorkPolicy.KEEP)
        }
    }
}
