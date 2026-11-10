package kr.callbrief.pipeline

import android.content.Context
import androidx.work.*
import kr.callbrief.GeneralLocalStore
import java.util.concurrent.TimeUnit

/** Once a day: trims the call records' transcripts ([CallRecordStore.trim], v3.6.1 D1), and deletes the encrypted original recordings whose transcript finished 14 days ago (review B21), and the ones
 * skipped for good 14 days ago (before 10/1, excluded, no speech, an older version: #39), so nothing stays in the vault
 * forever. The ledger keeps the reference rows and notes each deleted blob ([GeneralLocalStore.pruneVault]). */
internal class VaultPruneWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result = try {
        // v3.6.1 (D1): the call records' transcripts are trimmed here too, in a transaction of their own (before: only inside
        // an insert, which fails with the store once it is full). A failure here does not stop the vault prune.
        runCatching { trimRecords(applicationContext,System.currentTimeMillis()) }
            .onFailure { android.util.Log.w("ieum.prune","trim failed: ${it.javaClass.simpleName}") }
        GeneralLocalStore(CallPipelineStartup.storage(applicationContext)).use { it.pruneVault() }
        CallPipelineStartup.selfHeal(applicationContext) // A2: daily check that the other works are still registered
        Result.success()
    } catch(e:Exception) { CallPipelineStartup.selfHeal(applicationContext);Result.failure() } // the next daily run tries again; no retry loop on a stuck row

    companion object {
        const val NAME="ieum.calls.vault-prune"
        /** v3.6.1 (D1): [CallRecordStore.trim] at the store's present share (7 days, 3 from 60%), on the process store. */
        fun trimRecords(context:Context,now:Long) { val s=kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(context));s.trim(now,s.dbPercent()) }
        fun schedule(context:Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VaultPruneWork>(1,TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
