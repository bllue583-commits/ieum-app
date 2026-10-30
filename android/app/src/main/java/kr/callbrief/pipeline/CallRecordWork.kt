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
    /** Subscription mode (a scheduled Claude task) when set; otherwise the Claude API key path. */
    private val inbox:CallInboxStore?=CallInboxConfig.store(),
) {
    /** Returns false when a network-type failure should be retried later by WorkManager. */
    fun runPending():Boolean = if(inbox!=null) runInbox(inbox) else runApi()

    /** Sends waiting transcripts (once; again after [RESEND_MS] without an answer), then applies any results. */
    private fun runInbox(store:CallInboxStore):Boolean = try {
        val s=settings()
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        CallRecordStore(context,clock).use { db ->
            for(record in db.pending()) {
                if(!consent) { db.setState(record.recordId,CallRecordStore.WAITING_CONSENT);continue }
                if(record.state!=CallRecordStore.TRANSCRIBED) db.setState(record.recordId,CallRecordStore.TRANSCRIBED)
                if(record.transcript.isBlank()) { db.setState(record.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT");continue }
                val sent=db.sentMs(record.recordId)
                if(sent!=null && clock()-sent<RESEND_MS) continue
                store.put(CallInbox.INBOX+"/"+CallInbox.fileName(record.recordId),CallInbox.job(record.recordId,db.request(record),clock()))
                db.markSent(record.recordId,clock())
            }
            for((name,sha) in store.list(CallInbox.RESULTS)) {
                if(name.startsWith("selftest-")) continue // CI end-to-end check, not a call of this phone
                val path=CallInbox.RESULTS+"/"+name
                val parsed=runCatching { CallInbox.parseResult(store.get(path)) }.getOrNull()
                val record=parsed?.let { db.record(it.first) }
                if(parsed!=null && record!=null && record.state!=CallRecordStore.ANALYZED) {
                    db.applyAnalysis(record.recordId,analyzer.filter(parsed.second,db.request(record)))
                    runCatching { SecureSettings(context).update { it.put(PipelineSettingsKeys.LAST_ANALYZED_MS,clock()) } }
                }
                store.delete(path,sha)
            }
        }
        true
    } catch(e:java.io.IOException) { false }

    private fun runApi():Boolean {
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

internal object CallInboxConfig {
    fun store():CallInboxStore? = kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.takeIf { it.isNotBlank() }
        ?.let { GitHubInboxStore(kr.callbrief.BuildConfig.IEUM_INBOX_REPO,it) }
    val enabled get()=kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.isNotBlank()
}
internal const val RESEND_MS=6*3_600_000L

internal class CallAnalysisWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val storage=CallPipelineStartup.storage(applicationContext)
        if(CallRecordAnalysisRunner(storage).runPending()) Result.success() else Result.retry()
    }
}
