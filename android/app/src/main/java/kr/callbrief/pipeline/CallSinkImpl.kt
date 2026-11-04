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

/** The result pass (design D, v2.4, in place of the 10-minute chain): the hourly routine runs at :30 KST from 07:30 to
 * 22:30, so a request that went up is most likely answered by the next :45. One one-time work under [NAME] collects then,
 * and while an answer is still missing it comes back at the following :45, [MAX_TRIES] times in all. A pass that sends
 * something new starts the count again. Nothing is registered while nothing waits. */
internal class CallResultsWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val app=CallPipelineStartup.storage(applicationContext)
        val attempt=inputData.getInt(ATTEMPT,1)
        val runner=CallRecordAnalysisRunner(app)
        runner.runPending()
        val waiting=runCatching { kr.callbrief.IeumApplication.store(app).let { it.awaitingResult() } }.getOrDefault(false)
        // Appended after this one (KEEP would see this work still running and do nothing).
        runCatching { schedule(app,if(runner.sent>0) 1 else attempt+1,ExistingWorkPolicy.APPEND_OR_REPLACE,waiting) }
        Result.success()
    }
    companion object {
        const val NAME="ieum.calls.results"
        const val MAX_TRIES=3
        private const val ATTEMPT="attempt"
        /** First and last :45 of a day (KST), just after the routine's first and last :30. */
        private const val FIRST_HOUR=7;private const val LAST_HOUR=22
        /** The first HH:45 KST after [nowMs] between 07:45 and 22:45 (after 22:45: 07:45 the next day). */
        fun nextSlot(nowMs:Long):Long {
            val now=java.time.Instant.ofEpochMilli(nowMs).atZone(CallRecordStore.KST)
            var at=now.withMinute(45).withSecond(0).withNano(0)
            if(!at.isAfter(now)) at=at.plusHours(1)
            if(at.hour<FIRST_HOUR) at=at.withHour(FIRST_HOUR)
            if(at.hour>LAST_HOUR) at=at.plusDays(1).withHour(FIRST_HOUR)
            return at.toInstant().toEpochMilli()
        }
        /** How long until the result pass of try [attempt]; null when nothing waits or the tries are used up. */
        fun delayMs(nowMs:Long,attempt:Int,waiting:Boolean):Long? = if(!waiting || attempt !in 1..MAX_TRIES) null else nextSlot(nowMs)-nowMs
        fun schedule(context:Context,attempt:Int,policy:ExistingWorkPolicy,waiting:Boolean=true,nowMs:Long=System.currentTimeMillis()) {
            val delay=delayMs(nowMs,attempt,waiting) ?: return
            val request=OneTimeWorkRequestBuilder<CallResultsWork>().setInitialDelay(delay,java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ATTEMPT to attempt))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME,policy,request)
        }
    }
}
