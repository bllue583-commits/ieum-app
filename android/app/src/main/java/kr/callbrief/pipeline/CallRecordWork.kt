package kr.callbrief.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import org.json.JSONObject

/** Analyzes every stored transcript that is waiting. Key/consent are checked per record, right before sending. */
internal class CallRecordAnalysisRunner(
    private val context:Context,
    private val analyzer:ClaudeCallAnalyzer=ClaudeCallAnalyzer(),
    private val settings:()->JSONObject={ SecureSettings(context).snapshot() },
    private val clock:()->Long=System::currentTimeMillis,
) {
    /** Returns false when a network-type failure should be retried later by WorkManager. */
    fun runPending():Boolean {
        var allDone=true
        val pending=CallRecordStore(context,clock).use { it.pending() }
        for(record in pending) {
            val s=settings()
            val key=s.optString(PipelineSettingsKeys.CLAUDE_API_KEY).trim()
            val state=when {
                !s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false) -> CallRecordStore.WAITING_CONSENT
                key.isEmpty() -> CallRecordStore.WAITING_KEY
                else -> null
            }
            if(state!=null) { CallRecordStore(context,clock).use { it.setState(record.recordId,state) };continue }
            if(record.transcript.isBlank()) { CallRecordStore(context,clock).use { it.setState(record.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT") };continue }
            val request=CallRecordStore(context,clock).use { it.request(record) }
            try {
                val analysis=analyzer.analyze(key,request)
                CallRecordStore(context,clock).use { it.applyAnalysis(record.recordId,analysis) }
                runCatching { SecureSettings(context).update { it.put(PipelineSettingsKeys.LAST_ANALYZED_MS,clock()) } }
            } catch(e:ClaudeCallException) {
                if(e.code=="NETWORK") { allDone=false;continue }
                CallRecordStore(context,clock).use { it.setState(record.recordId,CallRecordStore.FAILED,e.code) }
            } catch(e:Exception) {
                CallRecordStore(context,clock).use { it.setState(record.recordId,CallRecordStore.FAILED,e.javaClass.simpleName) }
            }
        }
        return allDone
    }
}

internal class CallAnalysisWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val storage=CallPipelineStartup.storage(applicationContext)
        if(CallRecordAnalysisRunner(storage).runPending()) Result.success() else Result.retry()
    }
}
