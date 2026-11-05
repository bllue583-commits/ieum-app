package kr.callbrief.pipeline

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.Executors

/** Receives finished transcripts from the transcription side, stores them and queues the analysis. */
internal class CallSinkImpl(context:Context,private val enqueue:(Context)->Unit=CallPipelineStartup::enqueueAnalysis):CallTranscriptSink {
    private val app=context.applicationContext
    override fun onTranscript(transcript:CallTranscript) {
        kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(app)).let { it.insert(transcript) }
        enqueue(app)
    }
}

/** App-start wiring for the call pipeline. Everything that touches WorkManager or storage runs off the main thread. */
internal object CallPipelineStartup {
    private val executor=Executors.newSingleThreadExecutor()
    const val ANALYSIS_WORK="ieum.calls.analysis"
    /** Same account scope as the main screen's local store. */
    fun storage(context:Context):Context=context.applicationContext

    /** Safe to call any number of times (app start, boot, update, the hourly tick): every registration below keeps or updates
     * the one unique work of its name, and nothing here collects anything. Real collection still waits for the consent the
     * workers check themselves. [done] runs after the registrations (a boot receiver waits for it). */
    fun init(context:Context,done:()->Unit={}) {
        CallTranscriptSinks.factory={ ctx -> CallSinkImpl(ctx) }
        val app=context.applicationContext
        executor.execute {
            try {
                // 처음 정리 starts with the first run of this version. The start only marks that it runs (and starts the 3 days
                // old texts may hold it); which records are old is "more than 7 days ago" (r7-1), whenever this was.
                runCatching { kr.callbrief.SecureSettings(app).update { if(FirstPass.start(it)==null) it.put(PipelineSettingsKeys.FIRST_PASS_START_MS,System.currentTimeMillis()) } }
                    .onFailure { Log.w(TAG,"first pass start not saved: ${it.javaClass.simpleName}") }
                ensureWork(app)
            } finally { done() }
        }
    }
    /** The registrations only; also run by the hourly tick so a lost one comes back within the hour. */
    fun ensureWork(app:Context,includeInbox:Boolean=true) {
        step("digest") { CallDigestSchedule.ensure(app) }
        step("analysis") { enqueueAnalysis(app,ExistingWorkPolicy.KEEP) }
        step("update") { CallUpdate.schedule(app) }
        step("messages") { MessageWork.schedule(app) }
        step("vault") { VaultPruneWork.schedule(app) }
        if(!kr.callbrief.BuildConfig.LOCAL_PREVIEW) {
            step("discovery") { kr.callbrief.AutomaticRecordingDiscovery.ensure(app) }
            step("transcription") { CallTranscriptionWork.schedule(app) }
        }
        if(includeInbox && CallInboxConfig.enabled) step("inbox") {
            // Collect the scheduled task's results every hour even when no new call arrives.
            // The hourly tick only queues the one analysis work, so two passes never apply results side by side.
            // UPDATE, not cancel and re-enqueue: starting the app must not push the next tick back. (Phones from before
            // v1026 have the old periodic analysis registered under this name; the update replaces its worker.)
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(INBOX_WORK,ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CallAnalysisTick>(1,java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
    /** A2: once a day (end of the vault prune and of the evening report) check that the periodic works are still registered. */
    private val ACTIVE_STATES=setOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED)
    /** Unique names of the works that have to exist; which of them apply depends on the build and the settings. */
    internal fun expectedWork(app:Context):List<String> = buildList {
        if(!kr.callbrief.BuildConfig.LOCAL_PREVIEW) {
            if(kr.callbrief.AutomaticRecordingDiscovery.capture(app)!=null)add(kr.callbrief.AutomaticRecordingDiscovery.TAG+"."+kr.callbrief.AutomaticRecordingDiscovery.SCOPE)
            add(CallTranscriptionWork.PERIODIC)
        }
        add("ieum.calls.digest.${CallDigestSchedule.EVENING}");add("ieum.calls.digest.${CallDigestSchedule.MORNING}")
        add("ieum.update");add("ieum.messages")
        if(CallInboxConfig.enabled)add(INBOX_WORK)
    }
    /** Names in [expectedWork] without any ENQUEUED/RUNNING/BLOCKED work. */
    internal fun lostWork(app:Context,names:List<String> = expectedWork(app)):List<String> {
        val wm=WorkManager.getInstance(app)
        return names.filter { n -> runCatching { wm.getWorkInfosForUniqueWork(n).get().none { it.state in ACTIVE_STATES } }.getOrDefault(false) }
    }
    /** Registers what is missing again ([ensureWork]) and, when something was missing, records the time in RESCHEDULED_MS. Never throws. */
    fun selfHeal(app:Context) {
        try {
            val lost=lostWork(app)
            ensureWork(app)
            if(lost.isNotEmpty()) {
                Log.w(TAG,"registered again: ${lost.joinToString()}")
                kr.callbrief.SecureSettings(app).update { it.put(PipelineSettingsKeys.RESCHEDULED_MS,System.currentTimeMillis()) }
            }
        } catch(e:Exception) { Log.w(TAG,"self-check failed: ${e.javaClass.simpleName}") }
    }
    private const val INBOX_WORK="ieum.calls.inbox"
    private const val TAG="ieum.startup"
    private inline fun step(name:String,block:()->Unit) {
        try { block() } catch(e:Exception) { Log.w(TAG,"$name not registered: ${e.javaClass.simpleName}") }
    }
    fun enqueueAnalysis(context:Context,policy:ExistingWorkPolicy=ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val request=OneTimeWorkRequestBuilder<CallAnalysisWork>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,java.util.concurrent.TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(ANALYSIS_WORK,policy,request)
    }
    /** Called by the settings screen after the key, consent or hours change. */
    fun settingsChanged(context:Context,hoursChanged:Boolean=false) {
        val app=context.applicationContext
        executor.execute {
            runCatching { enqueueAnalysis(app) }
            runCatching { MessageWork.now(app) }
            if(hoursChanged) runCatching { CallDigestSchedule.reschedule(app) }
        }
    }
}

/** Hourly: queues [CallAnalysisWork] under its one unique name. */
internal class CallAnalysisTick(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { CallPipelineStartup.enqueueAnalysis(applicationContext);CallPipelineStartup.ensureWork(applicationContext,includeInbox=false);PhoneStatus.publish(applicationContext);return Result.success() }
}

// The result chain (CallResultsWork under ieum.calls.results) moved to CallRecordWork.kt as ResultChain (r10-3): every
// 10 minutes after an upload while an answer waits, at most 12 rounds. The class name stays, so works queued by v2.8 still run.
