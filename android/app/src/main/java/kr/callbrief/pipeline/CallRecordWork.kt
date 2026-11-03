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
    private val save:((JSONObject)->Unit)->Unit={ edit -> runCatching { SecureSettings(context).update { edit(it) } } },
    private val callLog:()->List<FirstPass.LogEntry> ={ val rules=kr.callbrief.LocalPrivacy.rules(settings())
        FirstPass.readCallLog(context) { n -> rules.excluded(n,n) } },
    private val smsAllowed:()->Boolean={ context.checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED },
) {
    /** Returns false when a network-type failure should be retried later by WorkManager. */
    fun runPending():Boolean = when {
        kr.callbrief.BuildConfig.LOCAL_PREVIEW -> true // preview calls stay as seeded
        inbox!=null -> runInbox(inbox)
        else -> runApi()
    }

    /** Sends waiting transcripts (once; again after [RESEND_MS] without an answer), then applies any results.
     * During the first pass, old records go as "b-" files, [FirstPass.OLD_IN_FLIGHT] at a time, oldest first, after
     * the new ones; when none is left the one consolidation ("ctx-") goes up, and its answer rewrites the ledger. */
    private fun runInbox(store:CallInboxStore):Boolean = try {
        val s=settings()
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        CallRecordStore(context,clock).use { db ->
            runCatching { db.reconcileCallLog(callLog()) }
            val (old,live)=db.pending().partition { FirstPass.isOld(it.dayMs,s) }
            var inFlight=old.count { r -> db.sentMs(r.recordId)?.let { clock()-it<RESEND_MS }==true }
            for(record in live+old.sortedBy { it.dayMs }) {
                if(!consent) { db.setState(record.recordId,CallRecordStore.WAITING_CONSENT);continue }
                if(record.state!=CallRecordStore.TRANSCRIBED) db.setState(record.recordId,CallRecordStore.TRANSCRIBED)
                if(record.transcript.isBlank()) { db.setState(record.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT");continue }
                val sent=db.sentMs(record.recordId)
                if(sent!=null && clock()-sent<RESEND_MS) continue
                val isOld=record in old
                if(isOld && inFlight>=FirstPass.OLD_IN_FLIGHT) continue
                store.put(CallInbox.INBOX+"/"+(if(isOld) CallInbox.OLD else "")+CallInbox.fileName(record.recordId),CallInbox.job(record.recordId,db.request(record,isOld),clock()))
                db.markSent(record.recordId,clock())
                if(isOld) inFlight++
            }
            for((name,sha) in store.list(CallInbox.RESULTS)) {
                if(name.startsWith("selftest-")) continue // CI end-to-end check, not a call of this phone
                val path=CallInbox.RESULTS+"/"+name
                val o=runCatching { JSONObject(store.get(path)) }.getOrNull()
                val id=o?.optString("id").orEmpty()
                if(id.startsWith(FirstPass.CONTEXT_PREFIX)) {
                    if(!FirstPass.done(settings()) && runCatching { db.applyDigest(FirstPass.Digest.parse(o!!.getJSONObject("analysis"))) }.isSuccess)
                        save { it.put(PipelineSettingsKeys.FIRST_PASS_DONE,true) }
                } else {
                    val parsed=o?.let { runCatching { id to ClaudeCallAnalysis.parse(it.getJSONObject("analysis")) }.getOrNull() }
                    val record=parsed?.let { db.record(it.first) }
                    if(parsed!=null && record!=null && record.state!=CallRecordStore.ANALYZED) {
                        val isOld=FirstPass.isOld(record.dayMs,s)
                        val a=analyzer.filter(parsed.second,db.request(record,isOld))
                        db.applyAnalysis(record.recordId,if(isOld) FirstPass.restrict(a,record.dayMs,clock()) else a)
                        save { it.put(PipelineSettingsKeys.LAST_ANALYZED_MS,clock()) }
                    }
                }
                store.delete(path,sha)
            }
            if(consent) sendDigest(store,db)
        }
        true
    } catch(e:java.io.IOException) { false }

    /** The consolidation, once every old record is summarised and nothing since 10/1 is left to read or transcribe. */
    private fun sendDigest(store:CallInboxStore,db:CallRecordStore) {
        val s=settings();val start=FirstPass.start(s) ?: return
        if(FirstPass.done(s)) return
        val sent=s.optLong(PipelineSettingsKeys.FIRST_PASS_CONTEXT_SENT_MS,0)
        if(sent>0 && clock()-sent<RESEND_MS) return
        if(!messagesCaughtUp(s)) return
        val p=db.firstPassProgress(start)
        if(p.toTranscribe>0 || p.waiting>0) return
        val input=db.digestInput()
        if(input.lines.isEmpty()) { save { it.put(PipelineSettingsKeys.FIRST_PASS_DONE,true) };return }
        val id=FirstPass.CONTEXT_PREFIX+start
        store.put(CallInbox.INBOX+"/"+CallInbox.CONTEXT+CallInbox.fileName(id),FirstPass.job(id,input,clock()))
        save { it.put(PipelineSettingsKeys.FIRST_PASS_CONTEXT_SENT_MS,clock()) }
    }
    /** Old texts and mail since 10/1 have been read (or cannot be: no permission, no mailbox). */
    private fun messagesCaughtUp(s:JSONObject):Boolean {
        val sms=!smsAllowed() || s.optLong(PipelineSettingsKeys.SMS_BACKFILL_UNTIL,0).let { u -> u>0 && s.optLong(PipelineSettingsKeys.SMS_BACKFILL_CURSOR,0)>=u }
        val mail=s.optString(PipelineSettingsKeys.MAIL_HOST).isBlank() || s.optString(PipelineSettingsKeys.MAIL_ERROR).isNotBlank() || s.optLong(PipelineSettingsKeys.MAIL_BACKFILL_UNTIL,0).let { u -> u>0 && s.optLong(PipelineSettingsKeys.MAIL_BACKFILL_CURSOR,0)>=u }
        return sms && mail
    }

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
    /** The preview build shows the subscription screens but has no token, so nothing is uploaded. */
    val enabled get()=kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.isNotBlank()||kr.callbrief.BuildConfig.LOCAL_PREVIEW
}
internal const val RESEND_MS=6*3_600_000L

internal class CallAnalysisWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val storage=CallPipelineStartup.storage(applicationContext)
        if(CallRecordAnalysisRunner(storage).runPending()) Result.success() else Result.retry()
    }
}
