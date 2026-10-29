package kr.callbrief.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.callbrief.AccountStorageContext
import kr.callbrief.BuildConfig
import kr.callbrief.SecureSettings
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Lane A (or the integrator) supplies the device identity, the inbox client and the transcript
 * index. Until then every fetch stays idle with a status; nothing is read or applied. */
internal interface PipelineFetchProviders {
    fun keys(context:Context):PipelineDeviceKeys?
    /** [token] is the user's inbox token from SecureSettings; never log it. */
    fun inbox(context:Context,token:String):PipelineInbox?
    fun index(context:Context):PipelineTranscriptIndex?
}

/** WorkManager wiring: hourly fetch when connected, plus a one-time fetch at the next 06:10
 * Asia/Seoul (just after the morning report), re-enqueued after each run. */
internal object PipelineFetchWork {
    const val TAG="ieum.pipeline.fetch.v1"
    private val zone:ZoneId=ZoneId.of("Asia/Seoul")
    @Volatile var providers:PipelineFetchProviders?=null
    @Volatile internal var serverKeysOverride:PipelineServerKeys?=null

    fun serverKeys():PipelineServerKeys?=serverKeysOverride ?: runCatching {
        PipelineServerKeys.fromConfig(BuildConfig.PIPELINE_SERVER_SIGNING_KEY,BuildConfig.PIPELINE_SERVER_ENCRYPTION_KEY)
    }.getOrNull()

    fun nextMorning(now:Instant):Instant {
        val local=now.atZone(zone);var target=ZonedDateTime.of(local.toLocalDate(),java.time.LocalTime.of(6,10),zone)
        if(!target.toInstant().isAfter(now))target=target.plusDays(1)
        return target.toInstant()
    }
    private fun constraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** Entry point for the integrator. Safe to call repeatedly. */
    fun schedule(context:Context) {
        val wm=WorkManager.getInstance(context.applicationContext)
        val periodic=PeriodicWorkRequestBuilder<PipelineFetchWorker>(1,TimeUnit.HOURS).setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).setInputData(workDataOf("trigger" to "HOURLY")).build()
        wm.enqueueUniquePeriodicWork("$TAG.hourly",ExistingPeriodicWorkPolicy.UPDATE,periodic)
        scheduleMorning(context)
    }
    fun scheduleMorning(context:Context) {
        val now=Instant.now();val target=nextMorning(now)
        val request=OneTimeWorkRequestBuilder<PipelineFetchWorker>().setConstraints(constraints())
            .setInitialDelay(Duration.between(now,target).toMillis().coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,60,TimeUnit.SECONDS).addTag(TAG).setInputData(workDataOf("trigger" to "MORNING")).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("$TAG.morning.${target.atZone(zone).toLocalDate()}",ExistingWorkPolicy.KEEP,request)
    }
    fun cancel(context:Context) { WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG) }

    /** One bounded pass for the currently selected account storage. Call off the main thread. */
    fun runOnce(context:Context,notify:Boolean=true):PipelineFetcher.Result {
        val storage=AccountStorageContext.selected(context)
        val settings=SecureSettings(storage)
        val state=settings.snapshot()
        val result=if(!state.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)) PipelineFetcher.Result("CONSENT_NOT_GRANTED")
        else {
            val token=state.optString(PipelineSettingsKeys.INBOX_TOKEN)
            val p=providers
            PipelineApplyStore(storage).use { store ->
                val applier=PipelineApplier(store) { settings.snapshot() }
                PipelineFetcher(store,if(token.isBlank())null else p?.inbox(storage,token),p?.keys(storage),serverKeys(),p?.index(storage),applier,
                    onNewReport={ if(notify)PipelineReportNotifier(storage).post(it["reportDate"].toString()) }).run()
            }
        }
        runCatching { settings.update { it.put(PipelineSettingsKeys.LAST_FETCH_MS,System.currentTimeMillis()).put("pipelineLastFetchStatus",result.status) } }
        return result
    }
}

class PipelineFetchWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val outcome=runCatching { PipelineFetchWork.runOnce(applicationContext) }
        if(inputData.getString("trigger")=="MORNING")runCatching { PipelineFetchWork.scheduleMorning(applicationContext) }
        val status=outcome.getOrNull()?.status
        if(status=="INBOX_UNAVAILABLE" && runAttemptCount<3)Result.retry() else Result.success()
    }
}
