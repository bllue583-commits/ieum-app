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

    /** One hourly pass (design: 장부의 원본, 처음 정리): the user's corrections go up, results are applied, the repository
     * ledger is taken in when it moved on, then waiting records are sent (once; again after [RESEND_MS] without an
     * answer). New records go one file each; old ones (처음 정리) in time-ordered batches of [FirstPass.BATCH], at most
     * [FirstPass.BATCHES_IN_FLIGHT] at a time. Every request carries the person's ledger id; the task keeps the ledger. */
    private fun runInbox(store:CallInboxStore):Boolean = try {
        val s=settings()
        val consent=s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false)
        CallRecordStore(context,clock).use { db ->
            runCatching { db.reconcileCallLog(callLog()) }
            for((id,json) in db.pendingEdits()) { store.put(CallInbox.EDITS+"/"+id+".json",json);db.editSent(id) }
            for((name,sha) in store.list(CallInbox.RESULTS)) {
                if(name.startsWith("selftest-")) continue // CI end-to-end check, not a call of this phone
                val path=CallInbox.RESULTS+"/"+name
                for((id,analysis) in runCatching { CallInbox.parseResults(store.get(path)) }.getOrDefault(emptyList())) {
                    val record=db.record(id) ?: continue
                    if(record.state==CallRecordStore.ANALYZED) continue
                    val isOld=FirstPass.isOld(record.dayMs,s)
                    // Memos come from the repository ledger, which the task keeps in time order.
                    val a=analyzer.filter(analysis,db.request(record,isOld)).copy(siteMemo=null,companyMemo=null,companyTrade=null)
                    db.applyAnalysis(record.recordId,if(isOld) FirstPass.restrict(a,record.dayMs,clock()) else a)
                    save { it.put(PipelineSettingsKeys.LAST_ANALYZED_MS,clock()) }
                }
                store.delete(path,sha)
            }
            takeLedger(store,db)

            fun due(r:CallRecord):Boolean {
                if(!consent) { db.setState(r.recordId,CallRecordStore.WAITING_CONSENT);return false }
                if(r.state!=CallRecordStore.TRANSCRIBED) db.setState(r.recordId,CallRecordStore.TRANSCRIBED)
                if(r.transcript.isBlank()) { db.setState(r.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT");return false }
                return db.sentMs(r.recordId)?.let { clock()-it>=RESEND_MS } ?: true
            }
            val (old,live)=db.pending().partition { FirstPass.isOld(it.dayMs,s) }
            for(record in live.filter(::due)) {
                db.ledgerIdFor(record)
                store.put(CallInbox.INBOX+"/"+CallInbox.fileName(record.recordId),CallInbox.job(record.recordId,db.request(record),clock()))
                db.markSent(record.recordId,clock())
            }
            val start=FirstPass.start(s)
            if(start!=null && !FirstPass.done(s)) {
                val inFlight=old.count { r -> db.sentMs(r.recordId)?.let { clock()-it<RESEND_MS }==true }
                // Old records earlier than this are complete; later ones may still be joined by older ones.
                val bound=listOfNotNull(db.oldestOldJobMs(start),messagesBound(s)).minOrNull()
                var slots=FirstPass.BATCHES_IN_FLIGHT-(inFlight+FirstPass.BATCH-1)/FirstPass.BATCH
                for(chunk in old.filter(::due).filter { bound==null || it.dayMs<bound }.sortedBy { it.dayMs }.chunked(FirstPass.BATCH)) {
                    if(slots<=0 || (chunk.size<FirstPass.BATCH && bound!=null)) break
                    val items=chunk.map { r -> db.ledgerIdFor(r);r.recordId to db.request(r,true) }
                    store.put(CallInbox.INBOX+"/"+CallInbox.batchName(chunk.first().dayMs,items.map { it.first }),CallInbox.batch(items,clock()))
                    chunk.forEach { db.markSent(it.recordId,clock()) }
                    slots--
                }
                if(consent && old.isEmpty() && bound==null) save { it.put(PipelineSettingsKeys.FIRST_PASS_DONE,true) }
            }
        }
        true
    } catch(e:java.io.IOException) { false }

    /** Takes the repository ledger when its rev moved on. A missing ledger (nothing analysed yet) is fine. */
    private fun takeLedger(store:CallInboxStore,db:CallRecordStore) {
        val o=runCatching { JSONObject(store.get(CallInbox.LEDGER)) }.getOrNull() ?: return
        val rev=o.optLong("rev",0)
        if(rev<=settings().optLong(PipelineSettingsKeys.LEDGER_REV,0)) return
        db.applyLedger(o)
        save { it.put(PipelineSettingsKeys.LEDGER_REV,rev) }
    }
    /** From when old texts or mail may still come in (their first-pass reading goes forward from 10/1), or null when
     * both are read to the end or cannot be read (no permission, no mailbox, a mailbox that refuses the password). */
    private fun messagesBound(s:JSONObject):Long? {
        fun bound(reading:Boolean,cursorKey:String,untilKey:String):Long? {
            if(!reading) return null
            val until=s.optLong(untilKey,0);val cursor=s.optLong(cursorKey,0)
            return if(until>0 && cursor>=until) null else maxOf(cursor,FirstPass.FROM_MS)
        }
        val mail=s.optString(PipelineSettingsKeys.MAIL_HOST).isNotBlank() && s.optString(PipelineSettingsKeys.MAIL_ERROR).isBlank()
        return listOfNotNull(bound(smsAllowed(),PipelineSettingsKeys.SMS_BACKFILL_CURSOR,PipelineSettingsKeys.SMS_BACKFILL_UNTIL),
            bound(mail,PipelineSettingsKeys.MAIL_BACKFILL_CURSOR,PipelineSettingsKeys.MAIL_BACKFILL_UNTIL)).minOrNull()
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
