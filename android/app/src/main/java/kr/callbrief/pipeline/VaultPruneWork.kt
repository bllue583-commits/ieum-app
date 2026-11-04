package kr.callbrief.pipeline

import android.content.Context
import androidx.work.*
import kr.callbrief.GeneralLocalStore
import java.util.concurrent.TimeUnit

/** Once a day: deletes the encrypted original recordings whose transcript finished 14 days ago (review B21). */
internal class VaultPruneWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result = try {
        GeneralLocalStore(CallPipelineStartup.storage(applicationContext)).use { it.pruneVault() }
        CallPipelineStartup.selfHeal(applicationContext) // A2: daily check that the other works are still registered
        Result.success()
    } catch(e:Exception) { CallPipelineStartup.selfHeal(applicationContext);Result.failure() } // the next daily run tries again; no retry loop on a stuck row

    companion object {
        const val NAME="ieum.calls.vault-prune"
        fun schedule(context:Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VaultPruneWork>(1,TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
