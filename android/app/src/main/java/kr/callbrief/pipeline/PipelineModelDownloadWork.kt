package kr.callbrief.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** One-time download of the pinned STT model (about 574 MB) on any connection: v3.1, user's decision 11/5 18:27 ("무조건"),
 * Wi-Fi or mobile data, started right after the consent. Retries transient failures (resuming the part file);
 * a verified hash mismatch ends as FAILED (the user can schedule again). */
class PipelineModelDownloadWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val manager=PipelineModelManager(applicationContext)
        return when(manager.download{isStopped}) {
            PipelineModelManager.Result.READY -> {CallTranscriptionWork.enqueue(applicationContext);Result.success()}
            PipelineModelManager.Result.UNSUPPORTED -> Result.success()
            PipelineModelManager.Result.RETRY -> if(isStopped||runAttemptCount<MAX_ATTEMPTS)Result.retry() else {manager.markFailed();Result.failure()}
            PipelineModelManager.Result.FAILED -> Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME="pipeline-model-download"
        private const val MAX_ATTEMPTS=20
        /** Any connection (never a Wi-Fi wait) with room on the phone. */
        fun constraints():Constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()
        /** A running download is left alone (KEEP); one that is only waiting (an older version made it wait for Wi-Fi) is
         * replaced, so it starts on the connection there is now. The part file keeps what came already. */
        internal fun request()=OneTimeWorkRequestBuilder<PipelineModelDownloadWork>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES)
            .build()
        fun enqueue(context:Context) {
            val request=request()
            val wm=WorkManager.getInstance(context)
            val running=runCatching { wm.getWorkInfosForUniqueWork(UNIQUE_NAME).get().any { it.state==androidx.work.WorkInfo.State.RUNNING } }.getOrDefault(true)
            wm.enqueueUniqueWork(UNIQUE_NAME,if(running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,request)
        }
    }
}
