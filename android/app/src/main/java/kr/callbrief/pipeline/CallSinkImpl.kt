package kr.callbrief.pipeline

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.Executors

/** Receives finished transcripts from the transcription side, stores them and queues the analysis. */
internal class CallSinkImpl(context:Context,private val enqueue:(Context)->Unit=CallPipelineStartup::enqueueAnalysis):CallTranscriptSink {
    private val app=context.applicationContext
    override fun onTranscript(transcript:CallTranscript) {
        CallRecordStore(CallPipelineStartup.storage(app)).use { it.insert(transcript) }
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
                // 처음 정리 starts with the first run of this version; records older than this are summarised first.
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
            WorkManager.getInstance(app).enqueueUniquePeriodicWork("ieum.calls.inbox",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CallAnalysisTick>(1,java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
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
