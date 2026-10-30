package kr.callbrief.pipeline

import android.content.Context
import androidx.work.*
import kr.callbrief.AccountStorageContext
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
    fun storage(context:Context):Context=AccountStorageContext.selected(context.applicationContext)

    fun init(context:Context) {
        CallTranscriptSinks.factory={ ctx -> CallSinkImpl(ctx) }
        val app=context.applicationContext
        executor.execute {
            runCatching { CallDigestSchedule.ensure(app) }
            runCatching { enqueueAnalysis(app) }
            runCatching { CallUpdate.schedule(app) }
            if(CallInboxConfig.enabled) runCatching {
                // Collect the scheduled task's results every hour even when no new call arrives.
                WorkManager.getInstance(app).enqueueUniquePeriodicWork("ieum.calls.inbox",ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<CallAnalysisWork>(1,java.util.concurrent.TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            }
        }
    }
    fun enqueueAnalysis(context:Context) {
        val request=OneTimeWorkRequestBuilder<CallAnalysisWork>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,java.util.concurrent.TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(ANALYSIS_WORK,ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    /** Called by the settings screen after the key, consent or hours change. */
    fun settingsChanged(context:Context,hoursChanged:Boolean=false) {
        val app=context.applicationContext
        executor.execute {
            runCatching { enqueueAnalysis(app) }
            if(hoursChanged) runCatching { CallDigestSchedule.reschedule(app) }
        }
    }
}
