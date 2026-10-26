package kr.callbrief

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Registration boundary; host tests capture real requests without registering OS work. */
internal interface DailyReportWorkRegistry {
    fun cancel(tag:String)
    fun periodic(name:String,request:PeriodicWorkRequest)
    fun once(name:String,request:OneTimeWorkRequest)
}
private class AndroidDailyReportWorkRegistry(context:Context):DailyReportWorkRegistry {
    private val wm=WorkManager.getInstance(context)
    override fun cancel(tag:String) { wm.cancelAllWorkByTag(tag) }
    override fun periodic(name:String,request:PeriodicWorkRequest) { wm.enqueueUniquePeriodicWork(name,ExistingPeriodicWorkPolicy.UPDATE,request) }
    override fun once(name:String,request:OneTimeWorkRequest) { wm.enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,request) }
}

/** Device-zone 06:00 target plus durable reconciliation. No exact alarms or permission expansion. */
internal object DailyReportSchedule {
    const val TAG="ieum.reviewed.daily.v2"
    const val CONTRACT_VERSION=2
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var hostRegistry:DailyReportWorkRegistry?=null
    internal fun installRegistryForHost(registry:DailyReportWorkRegistry?) { check(BuildConfig.DEBUG);hostRegistry=registry }
    internal fun drainForHost() { check(BuildConfig.DEBUG);executor.submit {}.get(10,TimeUnit.SECONDS) }
    private fun registry(context:Context)=hostRegistry ?: AndroidDailyReportWorkRegistry(context)
    private fun input(scope:DailyReportRuntime.Scope,generation:Long,trigger:String)=workDataOf(
        "profile" to scope.profile,"account_epoch" to scope.accountEpoch,"generation" to generation,
        "report_contract_version" to CONTRACT_VERSION,"trigger" to trigger)
    fun request(now:Instant,zone:ZoneId,scope:DailyReportRuntime.Scope,generation:Long,trigger:String):OneTimeWorkRequest {
        val window=ReportWindow.next(now,zone)
        return OneTimeWorkRequestBuilder<DailyReportWorker>()
            .setInitialDelay(Duration.between(now,window.due).toMillis().coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setInputData(input(scope,generation,trigger))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .addTag(TAG).addTag("$TAG.target.${scope.profile}").build()
    }
    /** KST operating targets share the existing worker/heartbeat and generation fences. */
    fun morningRequest(now:Instant,hour:Int,scope:DailyReportRuntime.Scope,generation:Long):OneTimeWorkRequest {
        require(hour==6 || hour==23)
        return OneTimeWorkRequestBuilder<DailyReportWorker>()
            .setInitialDelay(Duration.between(now,MorningReportWindow.nextTarget(now,hour)).toMillis(),TimeUnit.MILLISECONDS)
            .setInputData(input(scope,generation,if(hour==23)"KST_23_SYNC" else "KST_06_REPORT"))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .addTag(TAG).addTag("$TAG.target.${scope.profile}").build()
    }
    fun ensureAsync(context:Context,trigger:String="APP_REOPEN",resetTarget:Boolean=false,done:()->Unit={}) {
        val app=context.applicationContext
        executor.execute {
            try { ensure(app,trigger,resetTarget) }
            catch(_:Exception) { /* Unreadable account/settings: preserve data and leave work unregistered. */ }
            finally { done() }
        }
    }
    fun ensure(context:Context,trigger:String="APP_REOPEN",resetTarget:Boolean=false) {
        val scope=DailyReportRuntime.scope(context)
        DailyReportLedger(scope.context,scope.profile).use { ledger ->
            val config=ledger.config();if(!config.enabled || !DailyReportRuntime.current(context,scope))return
            val work=registry(context)
            work.cancel("ieum.reviewed.daily.v1")
            if(resetTarget)work.cancel("$TAG.target.${scope.profile}")
            val periodic=PeriodicWorkRequestBuilder<DailyReportWorker>(15,TimeUnit.MINUTES)
                .setInputData(input(scope,config.generation,"PERIODIC_RECONCILE"))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG)
                .addTag("$TAG.profile.${scope.profile}").build()
            work.periodic("$TAG.heartbeat.${scope.profile}",periodic)
            val now=Instant.now();val zone=ZoneId.systemDefault();val target=ReportWindow.next(now,zone)
            // Epoch/generation in identity prevent KEEP from retaining obsolete account/settings requests.
            val fence="${scope.accountEpoch}.${config.generation}"
            work.once("$TAG.target.${scope.profile}.${target.date}.${target.zone.id}.$fence",
                request(now,zone,scope,config.generation,trigger))
            for(hour in listOf(6,23)) {
                val kstTarget=MorningReportWindow.nextTarget(now,hour)
                work.once("$TAG.target.${scope.profile}.KST.$hour.$kstTarget.$fence",morningRequest(now,hour,scope,config.generation))
            }
            // Immediate local reconciliation catches missed days after reboot/time change/app reopening.
            val catchup=OneTimeWorkRequestBuilder<DailyReportWorker>().setInputData(input(scope,config.generation,trigger))
                .addTag(TAG).addTag("$TAG.profile.${scope.profile}").build()
            if(trigger!="NEXT_TARGET")work.once("$TAG.catchup.${scope.profile}.$fence",catchup)
        }
    }
    fun changed(context:Context) {
        val scope=DailyReportRuntime.scope(context)
        registry(context).cancel("$TAG.profile.${scope.profile}")
        registry(context).cancel("$TAG.target.${scope.profile}")
        ensureAsync(context,"USER_SETTING",true)
    }
    fun retire(context:Context,profile:String) {
        if(profile.isBlank())return
        val work=registry(context)
        work.cancel("$TAG.profile.$profile");work.cancel("$TAG.target.$profile")
        work.cancel("ieum.reviewed.daily.v1.profile.$profile");work.cancel("ieum.reviewed.daily.v1.target.$profile")
    }
    /** Every resume also covers a same-owner refresh that did not change epoch. */
    fun accountResumed(context:Context,previousOwner:String,previousEpoch:Long) {
        val app=context.applicationContext
        executor.execute {
            try {
                val previousProfile=ScopedNaverMailSettingsActivity.ownerNamespace(previousOwner)
                val current=runCatching { DailyReportRuntime.scope(app) }.getOrNull()
                if(current==null || previousProfile!=current.profile || previousEpoch!=current.accountEpoch)retire(app,previousProfile)
                if(current!=null)ensure(app,"ACCOUNT_RESUME",true)
            } catch(_:Exception) { /* An unconfirmed/unreadable owner cannot register work. */ }
        }
    }
}

class DailyReportWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val oldProfile=inputData.getString("profile").orEmpty()
        val scope=try { DailyReportRuntime.scope(applicationContext) } catch(_:Exception) { return@withContext Result.success() }
        val stale=oldProfile!=scope.profile || inputData.getLong("account_epoch",-1)!=scope.accountEpoch ||
            inputData.getInt("report_contract_version",-1)!=DailyReportSchedule.CONTRACT_VERSION
        if(stale) {
            try {
                DailyReportSchedule.retire(applicationContext,oldProfile)
                // Repair only a confirmed same owner's reservation, never start another owner's work.
                if(oldProfile==scope.profile)DailyReportSchedule.ensure(applicationContext,"STALE_EPOCH_RECOVERY",true)
                return@withContext Result.success()
            } catch(_:Exception) { return@withContext if(runAttemptCount<3)Result.retry() else Result.failure() }
        }
        val generation=inputData.getLong("generation",-1)
        try {
            DailyReportLedger(scope.context,scope.profile).use { ledger ->
                if(!ledger.enabled(generation))return@withContext Result.success()
                MorningReports.run(applicationContext,scope,ledger,DailyReportRuntime.environment(applicationContext,
                    inputData.getString("trigger") ?: "KST_RECONCILE"),generation,
                    { !isStopped && ledger.enabled(generation) && DailyReportRuntime.current(applicationContext,scope) })
                DailyReportCoordinator(ledger,CanonicalReviewedDailySource(scope.context),KakaoDailyReportSender(scope.context),
                    { DailyReportRuntime.environment(applicationContext,inputData.getString("trigger") ?: "DAILY_06_TARGET") },
                    { !isStopped && ledger.enabled(generation) && DailyReportRuntime.current(applicationContext,scope) }).tick()
                AutomationLocalReports.run(applicationContext,scope,ledger,DailyReportRuntime.environment(applicationContext,"LOCAL_06_REPORT"),generation,
                    { !isStopped && ledger.enabled(generation) && DailyReportRuntime.current(applicationContext,scope) }).getString("state")
            }
            // Work success means a local state check completed, never that a message was sent.
            DailyReportSchedule.ensure(applicationContext,"NEXT_TARGET")
            Result.success() // Existing worker result contract; detailed status is in the protected local report ledger.
        } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(_:Exception) { if(runAttemptCount<3)Result.retry() else Result.failure() }
    }
}

class DailyReportClockReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val trigger=when(intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "AFTER_REBOOT"
            Intent.ACTION_TIMEZONE_CHANGED -> "TIMEZONE_CHANGED"
            Intent.ACTION_TIME_CHANGED -> "DEVICE_TIME_CHANGED"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "APP_UPDATED"
            else -> return
        }
        val pending=goAsync()
        DailyReportSchedule.ensureAsync(context,trigger,true) { pending?.finish() }
    }
}
