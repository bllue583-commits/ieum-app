package kr.callbrief.pipeline

import android.content.Context
import androidx.work.*
import kr.callbrief.GeneralLocalStore
import kr.callbrief.LedgerPrune
import java.util.concurrent.TimeUnit

/** Once a day: trims the call records' transcripts ([CallRecordStore.trim], v3.6.1 D1), removes the finished intake ledger
 * lines nothing reads again ([GeneralLocalStore.pruneLedger], v3.8 r18), and deletes the encrypted original recordings whose
 * transcript finished 14 days ago (review B21), and the ones skipped for good 14 days ago (before 10/1, excluded, no speech,
 * an older version: #39), so nothing stays in the vault forever. The ledger keeps the reference rows and notes each deleted
 * blob ([GeneralLocalStore.pruneVault]). v3.8: also once soon after an update ([onceAfterUpdate]). */
internal class VaultPruneWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result = try {
        val now=System.currentTimeMillis()
        // v3.8 r18: the safety copy holds the whole store as it was; it goes 7 days on by its file time, whatever else fails.
        runCatching { LedgerPrune.dropOldCopy(applicationContext,now) }
        // v3.6.1 (D1): the call records' transcripts are trimmed here too, in a transaction of their own (before: only inside
        // an insert, which fails with the store once it is full). A failure here does not stop the vault prune.
        runCatching { trimRecords(applicationContext,now) }
            .onFailure { android.util.Log.w("ieum.prune","trim failed: ${it.javaClass.simpleName}") }
        // v3.8 r18 (DESIGN_V7 4.1): the finished ledger lines, before the tables are counted (dbTables shows the store after it).
        runCatching { pruneLedger(applicationContext,now) }
            .onFailure { android.util.Log.w("ieum.prune","ledger not pruned: ${it.javaClass.simpleName}") }
        // v3.7 r17-3 (DESIGN_V7 3.3): the store's biggest tables, once a day, for the heartbeat's dbTables (after the trim).
        runCatching { noteTables(applicationContext,System.currentTimeMillis()) }
            .onFailure { android.util.Log.w("ieum.prune","tables not counted: ${it.javaClass.simpleName}") }
        GeneralLocalStore(CallPipelineStartup.storage(applicationContext)).use { it.pruneVault() }
        CallPipelineStartup.selfHeal(applicationContext) // A2: daily check that the other works are still registered
        Result.success()
    } catch(e:Exception) { CallPipelineStartup.selfHeal(applicationContext);Result.failure() } // the next daily run tries again; no retry loop on a stuck row

    companion object {
        const val NAME="ieum.calls.vault-prune"
        /** v3.8 r18: the one run soon after an update (unique, so one at a time). */
        const val ONCE="ieum.calls.vault-prune.once"
        /** v3.8 r18: after a skipped prune, one more run an hour on ([RETRY_MAX] a day). */
        const val RETRY="ieum.calls.vault-prune.retry"
        const val RETRY_MAX=6
        const val RETRY_KEY="dbPruneRetry"
        /** v3.6.1 (D1): [CallRecordStore.trim] at the store's present share (7 days, 3 from 60%), on the process store. */
        fun trimRecords(context:Context,now:Long) { val s=kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(context));s.trim(now,s.dbPercent()) }
        /** v3.8 r18: [GeneralLocalStore.pruneLedger], not during a call ([OpenUpdate.inCall]: one seal of the whole store must not
         * hold up the call card). What it did goes to [DbStore.PRUNE] (heartbeat dbPrune; a later result of the same day that
         * deleted nothing keeps the earlier one, [DbStore.keepEarlier]); a run that was not skipped marks this build's first
         * prune done ([DbStore.PRUNED_VERSION]); a skipped one queues one more run an hour on ([RETRY], [RETRY_MAX] a day). */
        fun pruneLedger(context:Context,now:Long):LedgerPrune.Result {
            val app=context.applicationContext
            val busy=if(OpenUpdate.inCall(app)) LedgerPrune.SKIP_IN_CALL else null
            val r=GeneralLocalStore(CallPipelineStartup.storage(app)).use { it.pruneLedger(now,busy) }
            var retry=false
            runCatching { kr.callbrief.SecureSettings(app).update { s ->
                val next=DbStore.pruneJson(r,now)
                if(!DbStore.keepEarlier(s.opt(DbStore.PRUNE),next)) s.put(DbStore.PRUNE,next.toString())
                if(r.skipped==null) s.put(DbStore.PRUNED_VERSION,kr.callbrief.BuildConfig.VERSION_CODE.toLong())
                else {
                    // Skipped (whatever the reason): try again an hour on, at most RETRY_MAX times a KST day.
                    val day=Telemetry.day(now);val o=runCatching { org.json.JSONObject(s.optString(RETRY_KEY)) }.getOrNull()
                    val n=if(o?.optString("day")==day) o.optInt("n",0) else 0
                    if(n<RETRY_MAX) { retry=true;s.put(RETRY_KEY,org.json.JSONObject().put("day",day).put("n",n+1).toString()) }
                } } }
            if(retry) runCatching { WorkManager.getInstance(app).enqueueUniqueWork(RETRY,ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<VaultPruneWork>().setInitialDelay(1,TimeUnit.HOURS).build()) }
            android.util.Log.i("ieum.prune","ledger ${r.deleted} skipped=${r.skipped} ms=${r.ms}")
            return r
        }
        /** v3.7 r17-3: [CallRecordStore.tableSizes] into [PipelineSettingsKeys.DB_TABLES] ([Telemetry.dbTables]); the heartbeat reads it. */
        fun noteTables(context:Context,now:Long) {
            val sizes=kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(context)).tableSizes()
            val settings=kr.callbrief.SecureSettings(context.applicationContext)
            val prev=runCatching { org.json.JSONObject(settings.snapshot().optString(PipelineSettingsKeys.DB_TABLES)) }.getOrNull()
            val next=Telemetry.dbTables(sizes,prev,now)
            settings.update { it.put(PipelineSettingsKeys.DB_TABLES,next.toString()) }
        }
        fun schedule(context:Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VaultPruneWork>(1,TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
            onceAfterUpdate(context)
        }
        /** v3.8 r18: until this build's first ledger prune ran ([DbStore.PRUNED_VERSION]), one run a few minutes on (KEEP: a
         * queued one stays; the hourly registration queues it again after a skipped run). */
        fun onceAfterUpdate(context:Context) {
            val app=context.applicationContext
            val done=runCatching { kr.callbrief.SecureSettings(app).snapshot().optLong(DbStore.PRUNED_VERSION,-1) }.getOrDefault(-1L)
            if(done==kr.callbrief.BuildConfig.VERSION_CODE.toLong()) return
            WorkManager.getInstance(app).enqueueUniqueWork(ONCE,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VaultPruneWork>().setInitialDelay(5,TimeUnit.MINUTES).build())
        }
    }
}
