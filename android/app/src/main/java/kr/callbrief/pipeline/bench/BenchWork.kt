package kr.callbrief.pipeline.bench

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.callbrief.GeneralLocalStore
import kr.callbrief.LocalPrivacy
import kr.callbrief.RecordingRef
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.BatterySnapshot
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.CallTranscript
import kr.callbrief.pipeline.CallTranscriptionStore
import kr.callbrief.pipeline.CallTranscriptionWork
import kr.callbrief.pipeline.CpuSet
import kr.callbrief.pipeline.NumberGuard
import kr.callbrief.pipeline.PipelineAudioDecoder
import kr.callbrief.pipeline.PipelineAudioInput
import kr.callbrief.pipeline.PipelineAudioMath
import kr.callbrief.pipeline.PipelineBytesInput
import kr.callbrief.pipeline.PipelineModelManager
import kr.callbrief.pipeline.PipelineModelOkHttp
import kr.callbrief.pipeline.PowerPolicy
import kr.callbrief.pipeline.Telemetry
import kr.callbrief.pipeline.TranscriptClean
import kr.callbrief.pipeline.TranscriptionForeground
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** r18-bench (DESIGN_V7 4.4): the engine bench on the phone. Switched on only by the mailbox's app/config.json ([Bench]); the
 * lowest priority of all the app's work: it starts only on the charger, out of a call, with no recording waiting for its
 * text and no transcription run going ([BenchGate]), and a watcher ends it within seconds when any of that changes (the
 * piece under way is dropped, the finished ones are kept and the next run goes on from there). It reads the recordings and
 * the app's own transcripts on the phone and keeps only numbers ([BenchState]). */
class BenchWork(context:Context,params:WorkerParameters):Worker(context,params) {
    @Volatile private var engine:BenchEngine?=null
    @Volatile private var gateCode:String?=null
    override fun onStopped() { runCatching { engine?.cancel() };super.onStopped() }
    private val settings by lazy { SecureSettings(applicationContext) }
    private fun state()=BenchState.parse(settings.snapshot().optString(BenchState.KEY))
    /** Written over the stored state, except that an OFF the heartbeat set meanwhile for the same run stays (review: no
     * overwriting with what was read at the start). */
    private fun save(s:JSONObject)=runCatching { settings.update { cur -> val disk=BenchState.parse(cur.optString(BenchState.KEY))
        if(disk.optInt("run",-1)==s.optInt("run",-2) && disk.optString("state")=="OFF") s.put("state","OFF")
        cur.put(BenchState.KEY,s.toString()) } }
    private fun stopped()=isStopped || gateCode!=null
    /** The foreground of this run (elapsed ms when it began, 0: not in the foreground); counted for the whole doWork. */
    private var fgSince=0L
    private val day get()=LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
    private val began=android.os.SystemClock.elapsedRealtime()
    /** The system's 10-minute limit (or the foreground service's) ended the run. */
    private fun timeLimited():Boolean {
        val code=if(android.os.Build.VERSION.SDK_INT>=31) runCatching { stopReason }.getOrDefault(WorkInfo.STOP_REASON_UNKNOWN) else WorkInfo.STOP_REASON_UNKNOWN
        return isStopped && (code==WorkInfo.STOP_REASON_TIMEOUT || code==WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
            android.os.SystemClock.elapsedRealtime()-began>=TIME_LIMIT_NEAR_MS)
    }

    override fun doWork():Result {
        val app=applicationContext
        val cfg=Bench.config(settings.snapshot()).first
        if(!cfg.on) { Bench.cleanupModels(app);return Result.success() }
        var s=state()
        if(s.optInt("run",-1)!=cfg.run) s=BenchState.fresh(cfg.run,System.currentTimeMillis())
        if(s.optString("state")=="DONE") { Bench.cleanupModels(app);return Result.success() }
        // A piece that was under way when the process died (a native crash, out of memory): counted; twice ends the candidate.
        BenchState.takeLeftover(s)?.let { save(s) }
        val start=now(app)
        if(start!=null) { save(BenchState.setStatus(s,"WAITING",start,System.currentTimeMillis()));later(app);return Result.success() }
        val watcher=Watcher(app).also { it.start() }
        try {
            // 1. Models: only on Wi-Fi (and the charger, which the gate already asked).
            val models=BenchModels(File(app.noBackupFilesDir,BenchModels.DIR),PipelineModelOkHttp()) { runCatching { StatFs(app.noBackupFilesDir.path).availableBytes }.getOrDefault(0L) }
            for(c in cfg.candidates) for(spec in BenchModels.specs(c)) {
                if(BenchState.disabled(s,c)!=null || models.ready(spec)!=null) continue
                if(!unmetered(app)) { save(BenchState.setStatus(BenchState.setModel(s,c,"NO_WIFI"),"WAITING","NO_WIFI",System.currentTimeMillis()));later(app);return Result.success() }
                save(BenchState.setStatus(BenchState.setModel(s,c,"DOWNLOADING"),"DOWNLOADING",null,System.currentTimeMillis()))
                when(models.fetch(spec,::stopped)) {
                    BenchModels.Result.READY -> {}
                    BenchModels.Result.NO_SPACE -> { save(BenchState.setStatus(BenchState.setModel(s,c,"NO_SPACE"),"WAITING","NO_SPACE",System.currentTimeMillis()));later(app,6*60);return Result.success() }
                    BenchModels.Result.FAILED -> {
                        // A file that does not match its pin twice is not fetched again: the candidate ends (MODEL_SHA).
                        if(BenchState.shaFailed(s,spec.name)>=2) { BenchState.disable(s,c,"MODEL_SHA");BenchState.setModel(s,c,"MODEL_SHA");save(s);continue }
                        save(BenchState.setStatus(BenchState.setModel(s,c,"FAILED"),"WAITING","MODEL_FAILED",System.currentTimeMillis()));later(app,6*60);return Result.success() }
                    BenchModels.Result.RETRY -> { save(BenchState.setStatus(s,"WAITING",gateCode ?: "MODEL_RETRY",System.currentTimeMillis()));later(app);return Result.success() }
                }
            }
            for(c in cfg.candidates) if(BenchModels.specs(c).isNotEmpty() && BenchState.disabled(s,c)==null) BenchState.setModel(s,c,"READY")
            if(cfg.candidates.contains("a") && PipelineModelManager(app).verifiedModelFile()==null) BenchState.setModel(s,"a","NO_MODEL")
            // 2. The plan, made once per run: the newest work calls with their recording and the app's own transcript.
            val source=BenchSource(app)
            if(BenchState.plan(s).isEmpty()) {
                val calls=BenchPick.bench(source.calls(System.currentTimeMillis()-PLAN_DAYS*86_400_000L),cfg.calls)
                if(calls.isEmpty()) { save(BenchState.setStatus(s,"WAITING","NO_CALLS",System.currentTimeMillis()));later(app,60);return Result.success() }
                BenchState.setPlan(s,calls)
            }
            save(BenchState.setStatus(s,"RUNNING",null,System.currentTimeMillis()))
            val plan=BenchState.plan(s).map(BenchState::call)
            val refs=source.references(plan.map { it.recordingId }.toSet())
            val names=source.names().map(KoCer::normalize).filter { it.length>=2 }.distinct()
            // 3. (다) first, then (나), then (가); each call from the piece where it stopped.
            for(c in cfg.candidates) {
                // A candidate ended before the plan was made (MODEL_SHA) gets its calls closed now.
                BenchState.disabled(s,c)?.let { code -> BenchState.disable(s,c,code);save(s) }
                for((index,call) in plan.withIndex()) {
                    if(stopped() || BenchState.disabled(s,c)!=null) break
                    if(BenchState.done(s,c,index)) continue
                    val ref=refs[call.recordingId]
                    if(ref==null) { BenchState.finish(s,c,index,call.durationMs,"NO_REFERENCE");save(s);continue }
                    try {
                        measure(app,cfg,s,c,index,call,ref,names,models)
                        if(!stopped()) BenchState.finish(s,c,index,call.durationMs,null)
                    } catch(e:Exception) {
                        val code=codeOf(e)
                        if(stopped()) {
                            // The same piece cut by the time limit twice: this candidate cannot finish it here (BENCH_TIMEOUT).
                            if(timeLimited()) BenchState.noteTimeout(s,c,index,BenchState.nextPiece(s,c,index))
                            break
                        }
                        // A missing runtime or model will not come back by trying again: the call is given up at once.
                        if(code.startsWith("BENCH_C_") || code=="NO_MODEL" || BenchState.tried(s,c,index)>=BenchState.MAX_TRIES) BenchState.finish(s,c,index,call.durationMs,code)
                    } finally { BenchState.clearInFlight(s) }
                    save(s)
                }
                if(stopped()) break
            }
            runCatching { engine?.close() };engine=null
            if(stopped()) { save(BenchState.setStatus(s,"WAITING",gateCode ?: "STOPPED",System.currentTimeMillis()));later(app);return Result.success() }
            val all=cfg.candidates.all { c -> plan.indices.all { BenchState.done(s,c,it) } }
            if(all) { save(BenchState.setStatus(s,"DONE",null,System.currentTimeMillis()));Bench.cleanupModels(app) }
            else { save(BenchState.setStatus(s,"WAITING","MORE",System.currentTimeMillis()));later(app) }
            return Result.success()
        } catch(e:Exception) {
            save(BenchState.setStatus(s,"WAITING",codeOf(e),System.currentTimeMillis()));later(app,60);return Result.success()
        } finally {
            watcher.stop();runCatching { engine?.close() };engine=null
            if(fgSince>0) { BenchState.addFg(s,day,android.os.SystemClock.elapsedRealtime()-fgSince);fgSince=0;save(s) }
        }
    }

    /** One call for one candidate, piece by piece: the decoder's pieces (as the app's run gets them), the candidate on each,
     * the piece's numbers against the app's transcript lines that fall in it. The piece under way is dropped on a stop. */
    private fun measure(app:Context,cfg:BenchConfig,s:JSONObject,c:String,index:Int,call:BenchCall,ref:BenchSource.Reference,
                        names:List<String>,models:BenchModels) {
        val from=BenchState.nextPiece(s,c,index)
        var opened:BenchEngine?=null
        fun open():BenchEngine = opened ?: run {
            val threads=BenchConfig.threadsFor(cfg,c,CpuSet.readAllowed())
            BenchState.setRun(s,c,threads)
            val e=when(c) {
                // (다) never asks for the foreground: it needs no long run, and the notice would only show for nothing.
                "c" -> SenseVoiceBenchEngine(app,models.file(BenchModels.SENSE_VOICE),models.file(BenchModels.SENSE_VOICE_TOKENS),models.file(BenchModels.SILERO_VAD),threads)
                else -> {
                    val (file,bytes)=if(c=="b") models.file(BenchModels.TURBO_Q8) to BenchModels.TURBO_Q8.bytes
                        else (PipelineModelManager(app).verifiedModelFile() ?: error("NO_MODEL")).let { it to it.length() }
                    // (나) and (가) in the foreground like the app's plugged-in run, within the bench's own daily allowance
                    // (counted over the whole run, from the moment it went in).
                    if(fgSince==0L && BenchState.fgUsed(s,day)<cfg.fgMinutes*60_000L && TranscriptionForeground.start(this)) fgSince=android.os.SystemClock.elapsedRealtime()
                    WhisperBenchEngine(app,file,bytes,threads,::stopped)
                }
            }
            opened=e;engine=e;e
        }
        try {
            BenchAudio.read(app,call).use { input ->
                var pending:Triple<Int,Long,FloatArray>?=null
                fun run(p:Triple<Int,Long,FloatArray>,endMs:Long) {
                    val (i,offset,pcm)=p
                    if(i<from || stopped()) return
                    if(pcm.size<PipelineAudioMath.TARGET_RATE/10) { BenchState.addPiece(s,c,index,i,BenchState.Piece(0,0,0,0,0,0,0,0,0,0,0,false));return }
                    val e=open()
                    // The mark of a native run under way: still there at the next start means the process died in it.
                    BenchState.markInFlight(s,c,index,i);save(s)
                    val cpu0=Telemetry.processCpuMs();val t0=android.os.SystemClock.elapsedRealtime()
                    val raw=e.transcribe(pcm,::stopped)
                    val wall=android.os.SystemClock.elapsedRealtime()-t0;val cpu1=Telemetry.processCpuMs()
                    check(!stopped()) { "BENCH_STOPPED" }
                    BenchState.clearInFlight(s)
                    val q=BenchScore.piece(raw,offset,endMs,ref,names)
                    BenchState.addPiece(s,c,index,i,BenchState.Piece(pcm.size*1000L/PipelineAudioMath.TARGET_RATE,wall,
                        if(cpu0!=null && cpu1!=null && cpu1>=cpu0) cpu1-cpu0 else null,q.refChars,q.edits,q.numRef,q.numHit,q.nameRef,q.nameHit,
                        q.halluc,raw.size,fgSince>0,timed=ref.timed))
                    save(s)
                }
                PipelineAudioDecoder().decode(input,call.mime) { i,offset,pcm ->
                    pending?.let { run(it,offset) }
                    pending=Triple(i,offset,pcm)
                }
                pending?.let { run(it,Long.MAX_VALUE) }
            }
        } finally {
            runCatching { opened?.close() };if(engine===opened) engine=null
        }
    }

    /** Polls the gate every [POLL_MS] while the bench runs and ends the piece under way when it closes. */
    private inner class Watcher(private val app:Context) {
        @Volatile private var on=true
        private val thread=Thread({
            while(on) {
                runCatching { Thread.sleep(POLL_MS) }
                if(!on) break
                val code=runCatching { val b=BatterySnapshot.read(app);BenchGate.stop(b.isPlugged,b.inCall,b.thermal,Bench.transcriptionBusy(app),null) }.getOrNull()
                if(code!=null) { gateCode=code;runCatching { engine?.cancel() } }
            }
        },"ieum-bench-watch").apply { isDaemon=true }
        fun start()=thread.start()
        fun stop() { on=false;thread.interrupt() }
    }

    companion object {
        const val UNIQUE="ieum.bench"
        const val POLL_MS=2_000L
        /** Without a stop reason (before Android 12): a stop this long after the start is taken as the 10-minute limit. */
        const val TIME_LIMIT_NEAR_MS=9*60_000L
        /** The app keeps a call's words 7 days after its result: the plan looks back that far. */
        const val PLAN_DAYS=7L
        private fun codeOf(e:Exception)=e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,60}")) } ?: e.javaClass.simpleName.take(60)
        /** The gate at the start of a run (the store is opened once for the queue). */
        internal fun now(app:Context):String? {
            val b=BatterySnapshot.read(app)
            BenchGate.stop(b.isPlugged,b.inCall,b.thermal,Bench.transcriptionBusy(app),null)?.let { return it }
            val waiting=runCatching { CallTranscriptionStore(app).use { it.oldestQueuedMs()!=null } }.getOrDefault(true)
            return BenchGate.stop(true,false,0,false,waiting)
        }
        fun unmetered(app:Context):Boolean = runCatching {
            val cm=app.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)==true
        }.getOrDefault(false)
        fun constraints():Constraints=Constraints.Builder().setRequiresCharging(true).build()
        /** From the heartbeat: start one if none is waiting or running. */
        fun enqueue(app:Context)=WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BenchWork>().setConstraints(constraints()).build())
        /** After a stop: once more in [minutes] (the charger constraint still holds). */
        fun later(app:Context,minutes:Long=10)=runCatching { WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<BenchWork>().setConstraints(constraints()).setInitialDelay(minutes,TimeUnit.MINUTES).build()) }
        fun cancel(app:Context)=runCatching { WorkManager.getInstance(app).cancelUniqueWork(UNIQUE) }
    }
}

/** The recordings and the app's own transcripts the bench and the samples read (on the phone only). */
internal class BenchSource(private val app:Context) {
    /** The app's text of one call: its lines and when each began (seconds), from call_records. */
    /** The app's text of one call: its lines and when each began and ended (whole seconds, as the store keeps them). A call
     * whose line times do not match its lines has no times ([timed] false): its quality numbers are left out and counted
     * apart, never put on one piece (review 11/13, medium 3c). */
    class Reference(val lines:List<String>,val startsMs:List<Long>?,val endsMs:List<Long>?=null) {
        val timed get()=startsMs!=null && startsMs.size==lines.size
        /** Lines that began in [fromMs, toMs) of the recording; "" without times. */
        fun textBetween(fromMs:Long,toMs:Long):String {
            val starts=startsMs?.takeIf { timed } ?: return ""
            val lo=fromMs/1000*1000;val hi=if(toMs==Long.MAX_VALUE) Long.MAX_VALUE else toMs/1000*1000
            return lines.indices.filter { starts[it]>=lo && starts[it]<hi }.joinToString(" ") { lines[it] }
        }
        /** Whether the app's text has words around [fromMs, toMs] (half a second either side); null without times. */
        fun saysSomething(fromMs:Long,toMs:Long):Boolean? {
            val starts=startsMs?.takeIf { timed } ?: return null
            val ends=endsMs?.takeIf { it.size==lines.size } ?: starts.map { it+1000 }
            return lines.indices.any { lines[it].isNotBlank() && starts[it]<=toMs+500 && ends[it]>=fromMs-500 }
        }
    }
    private fun records(sinceMs:Long,needText:Boolean=true)=kr.callbrief.IeumApplication.store(app).benchRows(sinceMs,needText=needText)
    /** Work calls since [sinceMs] that have their recording in the vault and are not excluded (archived, folder removed, a
     * number the user excluded). [needText] false: also those whose words the store has already cleared (the samples' check). */
    fun calls(sinceMs:Long,needText:Boolean=true):List<BenchCall> {
        val rows=records(sinceMs,needText).associateBy { it.recordingId }
        if(rows.isEmpty()) return emptyList()
        val snapshot=SecureSettings(app).snapshot()
        return CallTranscriptionStore(app).use { it.candidates() }.filter { c -> c.key.recordingId in rows && !c.archived && c.grantActive &&
            !LocalPrivacy.blocked(RecordingRef(Uri.parse(c.uri),c.displayName,c.bytes,c.mime,c.startedAtMs?.let(Instant::ofEpochMilli),c.modifiedMs),snapshot) }
            .map { c -> val r=rows.getValue(c.key.recordingId)
                BenchCall(c.key.recordingId,c.key.sourceVersion,c.key.audioSha,c.mime,c.bytes,r.startedMs,r.durationMs) }
            .distinctBy { it.recordingId }
    }
    fun references(ids:Set<String>):Map<String,Reference> = records(0).filter { it.recordingId in ids }.associate { r ->
        val lines=r.transcript.split("\n")
        val times=NumberGuard.Times.parse(r.segmentsJson)?.takeIf { it.size==lines.size }
        r.recordingId to Reference(lines,times?.startsMs,times?.endsMs)
    }
    fun transcript(id:String):CallRecordStore.BenchRow? = records(0).firstOrNull { it.recordingId==id }
    fun names():List<String> = runCatching { kr.callbrief.IeumApplication.store(app).benchNames() }.getOrDefault(emptyList())
}

/** The vault's verified bytes of one recording version (the same read as the app's run). */
internal object BenchAudio {
    fun read(app:Context,c:BenchCall):PipelineAudioInput {
        var out:PipelineBytesInput?=null
        GeneralLocalStore(app).use { local -> local.encrypted.withReadOnlySourceAudio(c.recordingId,c.sourceVersion,{ a -> a.plaintextSha256==c.audioSha && a.sourceVersion==c.sourceVersion }) { input ->
            out=PipelineBytesInput.read(input,c.bytes) } }
        return checkNotNull(out) { "BENCH_AUDIO_EMPTY" }
    }
}

/** r18-bench: the switch. The heartbeat reads app/config.json ([onHeartbeat]); with nothing there (the default) the bench and
 * the samples do nothing at all: no work is queued, no library is loaded, no model is fetched. */
internal object Bench {
    const val CONFIG_KEY="benchConfig"
    /** The stored config (also settable by hand under the same key in a debug build: the "별도 설정 키"). */
    fun config(s:JSONObject):Pair<BenchConfig,SamplesConfig> = runCatching { JSONObject(s.optString(CONFIG_KEY)) }.getOrNull()
        ?.let { BenchConfig.parse(it.optJSONObject("bench")) to SamplesConfig.parse(it.optJSONObject("samples")) } ?: (BenchConfig() to SamplesConfig())
    fun stored(bench:BenchConfig,samples:SamplesConfig)=JSONObject().put("bench",bench.toJson()).put("samples",samples.toJson()).toString()

    /** The app's transcription run is running or about to (the bench gives way at once). */
    fun transcriptionBusy(app:Context):Boolean = runCatching {
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(CallTranscriptionWork.ONCE_NAME).get(5,TimeUnit.SECONDS)
            .any { it.state==WorkInfo.State.RUNNING || it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED }
    }.getOrDefault(true)

    fun cleanupModels(app:Context)=runCatching { BenchModels(File(app.noBackupFilesDir,BenchModels.DIR),PipelineModelOkHttp()) { 0L }.deleteAll() }

    /** Called by the heartbeat ([kr.callbrief.pipeline.PhoneStatus.publish]) with the mailbox. Missing file (404) or unreadable
     * content = off; a network error changes nothing this hour. Never throws. */
    fun onHeartbeat(app:Context,store:kr.callbrief.pipeline.CallInboxStore,now:Long=System.currentTimeMillis(),
                    samples:(Context,SamplesConfig,Long)->Unit={ a,c,t -> Samples.onHeartbeat(a,c,t) },
                    work:BenchScheduler=BenchScheduler.REAL) = runCatching {
        val text=try { store.get(BenchConfigs.PATH) } catch(e:Exception) {
            if(e.message=="INBOX_HTTP_404") null else return@runCatching }
        val (bench,sample)=BenchConfigs.parse(text)
        val settings=SecureSettings(app)
        val snap=settings.snapshot()
        val (oldBench,oldSamples)=config(snap)
        val changed=oldBench!=bench || oldSamples!=sample
        // Off and never on: nothing is written, nothing is queued (the app runs exactly as without the bench).
        val everOn=snap.has(CONFIG_KEY) || snap.has(BenchState.KEY) || snap.has(Samples.KEY)
        if(!bench.on && !sample.on && !sample.present && !everOn) return@runCatching
        if(changed) settings.update { it.put(CONFIG_KEY,stored(bench,sample)) }
        if(bench.on) work.benchOn(app) else if(oldBench.on || snap.has(BenchState.KEY)) {
            work.benchOff(app)
            settings.update { s -> val st=BenchState.parse(s.optString(BenchState.KEY));if(st.has("run") && st.optString("state")!="OFF") s.put(BenchState.KEY,BenchState.setStatus(st,"OFF",null,now).toString()) }
        }
        samples(app,sample,now)
    }

    private val SAFE_KEY=Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
    private val SAFE_CODE=Regex("[A-Za-z0-9_]{1,40}")
    /** What may go up from the bench and the samples: numbers, yes/no, null and short codes ([A-Za-z0-9_], no 6-digit runs),
     * under code-like keys, in nested objects and arrays. Any other string (a text, a name, a path) is dropped. */
    fun safe(o:JSONObject):JSONObject {
        fun ok(v:String)=SAFE_CODE.matches(v) && !Regex("\\d{6,}").containsMatchIn(v)
        fun value(v:Any?):Any? = when(v) {
            null,JSONObject.NULL -> JSONObject.NULL
            is Number -> v.takeUnless { it is Double && !it.isFinite() }
            is Boolean -> v
            is String -> v.takeIf(::ok)
            is JSONObject -> safe(v)
            is org.json.JSONArray -> org.json.JSONArray().apply { for(k in 0 until v.length()) value(v.opt(k))?.let { put(it) } }
            else -> null }
        val out=JSONObject()
        for(k in o.keys()) { if(!SAFE_KEY.matches(k)) continue;value(o.opt(k))?.let { out.put(k,it) } }
        return out
    }

    /** The heartbeat's `bench` block (null while it was never on). */
    fun heartbeat(s:JSONObject):JSONObject? = runCatching { BenchState.heartbeat(BenchState.parse(s.optString(BenchState.KEY)),config(s).first) }.getOrNull()
}

/** Test seam for what [Bench.onHeartbeat] queues. */
internal interface BenchScheduler {
    fun benchOn(app:Context)
    fun benchOff(app:Context)
    companion object {
        val REAL=object:BenchScheduler {
            override fun benchOn(app:Context) { BenchWork.enqueue(app) }
            override fun benchOff(app:Context) { BenchWork.cancel(app);Bench.cleanupModels(app) }
        }
    }
}

/** r18-bench: the numbers of one piece against the app's text (pure, the host tests pin it). */
internal object BenchScore {
    data class Q(val refChars:Int,val edits:Int,val numRef:Int,val numHit:Int,val nameRef:Int,val nameHit:Int,val halluc:Int)
    /** [segments] relative to the piece starting at [offset]; quality only for a call with line times. A short output counts
     * as a silence hallucination only where the app's own text has no words at that time (a real "네" is no hallucination). */
    fun piece(segments:List<CallTranscript.Segment>,offset:Long,endMs:Long,ref:BenchSource.Reference,names:List<String>):Q {
        val halluc=segments.count { g -> KoCer.hallucination(g.endMs-g.startMs,g.text) && ref.saysSomething(offset+g.startMs,offset+g.endMs)==false }
        if(!ref.timed) return Q(0,0,0,0,0,0,0)
        val hyp=TranscriptClean.clean(segments).segments.joinToString(" ") { it.text.trim() }
        val r=KoCer.normalize(ref.textBetween(offset,endMs));val h=KoCer.normalize(hyp)
        val (numHit,numRef)=KoCer.numberHits(r,h);val (nameHit,nameRef)=KoCer.nameHits(r,h,names)
        return Q(KoCer.length(r),KoCer.edits(r,h),numRef,numHit,nameRef,nameHit,halluc)
    }
}
