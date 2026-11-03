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

/** Whisper on one 16 kHz mono chunk; segment times are relative to the chunk start. */
internal fun interface CallTranscriber { fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> }

/** Production transcriber: the pinned JNI runtime with the verified large model, Korean. */
internal class WhisperCallTranscriber(private val engine:AndroidWhisperEngine,private val model:PipelineSttModel,private val threadCount:Int?=null):CallTranscriber {
    override fun transcribe(model:File,pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> {
        val threads=threadCount ?: (Runtime.getRuntime().availableProcessors()-1).coerceIn(1,6) // S26: 6 threads 85 s/min, 8 threads ~3x slower
        val raw=engine.transcribeVerifiedModel(model,{this.model.verifiedModelFile()!=null},pcm,"ko",threads,1800,stopped)
        val output=runCatching{AndroidWhisperEngine.decodeOutput(raw)}.getOrNull() ?: return emptyList() // silence: no segments
        return (0 until output.segments.length()).map { i -> val s=output.segments.getJSONObject(i)
            CallTranscript.Segment(s.getLong("startMs"),s.getLong("endMs"),s.getString("text")) }
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
    /** True for an old recording (처음 정리) that has to wait for the charger or more battery. */
    private val holdOld:(CallTranscriptionStore.Candidate)->Boolean={false},
    /** The first-pass start while it runs: older recordings are done after newer ones, oldest first. */
    private val oldBefore:()->Long?={null},
) {
    data class Pass(val state:String,val done:Int,val failed:Int)
    private class Stopped:RuntimeException()

    fun run(maxJobs:Int=10):Pass {
        store.recoverRunning(MAX_ATTEMPTS);store.skipSuperseded()
        for(c in store.candidates()){val reason=skipReason(c);store.offer(c,reason==null,reason ?: "")}
        val target=sink() ?: return Pass("NO_SINK",0,0)
        val file=model.verifiedModelFile() ?: return Pass("NO_MODEL",0,0)
        var done=0;var failed=0
        repeat(maxJobs) {
            if(stopped())return Pass("STOPPED",done,failed)
            val job=store.nextQueued(oldBefore()) ?: return Pass("IDLE",done,failed)
            val c=job.candidate
            if(holdOld(c))return Pass("WAIT_POWER",done,failed) // new ones come first: everything after this is old too
            if(!store.claim(c.key))return@repeat
            try {
                val started=now()
                val decoded=audio.decode(read(c),c.mime)
                val segments=mutableListOf<CallTranscript.Segment>()
                val finished=store.parts(c.key) // pieces finished before the worker was stopped last time
                for((index,chunk) in decoded.chunks.withIndex()) {
                    val (offset,pcm)=chunk
                    val kept=finished[index]
                    if(kept!=null) { segments+=kept;continue }
                    if(stopped())throw Stopped()
                    val piece=transcriber.transcribe(file,pcm,stopped).map { CallTranscript.Segment(it.startMs+offset,it.endMs+offset,it.text) }
                    if(stopped())throw Stopped() // a piece cut short is not kept
                    store.savePart(c.key,index,piece);segments+=piece
                }
                store.clearParts(c.key)
                val text=segments.joinToString(""){it.text}.trim()
                skipReason(c)?.let { store.finish(c.key,"SKIPPED",it);return@repeat }
                if(text.isEmpty()){store.finish(c.key,"SKIPPED","NO_SPEECH");return@repeat}
                target.onTranscript(CallTranscript(c.key.recordingId,c.key.sourceVersion,c.key.audioSha,c.startedAtMs,decoded.durationMs,
                    counterpart(c.displayName),text,segments,model.modelName,model.modelSha256,decoded.vadApplied))
                store.finish(c.key,"DONE","")
                if(decoded.durationMs>0)speed((now()-started)*60000/decoded.durationMs)
                done++
            } catch(e:Stopped) { store.finish(c.key,"QUEUED","STOPPED",refundAttempt=true);return Pass("STOPPED",done,failed) }
            catch(e:Exception) {
                val code=e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,80}"))} ?: e.javaClass.simpleName
                if(store.attempts(c.key)>=MAX_ATTEMPTS){store.finish(c.key,"FAILED",code);failed++} else store.finish(c.key,"QUEUED",code)
            }
        }
        return Pass("MORE",done,failed)
    }

    companion object {
        const val MAX_ATTEMPTS=3
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

/** WorkManager entry: runs soon after new recordings are imported, and hourly as a safety net. */
class CallTranscriptionWork(context:Context,params:WorkerParameters):Worker(context,params) {
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
                val pass=CallTranscriptionRunner(store,model,PipelineAudioDecoder(),WhisperCallTranscriber(AndroidWhisperEngine(storage),model),
                    read={ c -> readAudio(local,c) },
                    skipReason={ c -> skipReason(c,settings) },
                    // The app may not have been opened since the phone restarted: the sink is made here then (review 11/4, round 2).
                    sink={ CallTranscriptSinks.factory?.invoke(applicationContext) ?: CallSinkImpl(applicationContext) },
                    stopped={ isStopped || tooHot() },
                    speed={ ms -> settings.update{ it.put(PipelineSettingsKeys.LAST_MS_PER_AUDIO_MINUTE,ms).put(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,System.currentTimeMillis()) } },
                    holdOld={ c -> FirstPass.isOld(c.startedAtMs ?: c.modifiedMs,settings.snapshot()) && !FirstPass.powerOk(applicationContext) },
                    oldBefore={ settings.snapshot().let { s -> FirstPass.start(s).takeUnless { FirstPass.done(s) } } }
                ).run()
                if(pass.state=="MORE")enqueue(applicationContext)
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
        private const val PERIODIC="calls-transcription-hourly"
        private val constraints get()=Constraints.Builder().setRequiresBatteryNotLow(true).build()
        /** After new recordings arrive. A pass already running is followed by one more. */
        fun enqueue(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE,ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CallTranscriptionWork>().setConstraints(constraints).build())
        }
        /** App start: hourly safety net, one pass now, and the model download if it is missing. */
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CallTranscriptionWork>(1,TimeUnit.HOURS).setConstraints(constraints).build())
            enqueue(context)
        }
    }
}
