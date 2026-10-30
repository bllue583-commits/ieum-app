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

/** One-time download of the pinned STT model over an unmetered network. Retries transient failures;
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
        fun enqueue(context:Context) {
            val request=OneTimeWorkRequestBuilder<PipelineModelDownloadWork>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME,ExistingWorkPolicy.KEEP,request)
        }
    }
}
