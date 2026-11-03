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
          try {
            runCatching { db.reconcileCallLog(callLog()) }
            for((id,json) in db.pendingEdits()) { store.put(CallInbox.EDITS+"/"+id+".json",json);db.editSent(id) }
            // The ledger first: results then find the names the task used; a broken ledger or proposal stops nothing else.
            runCatching { takeLedger(store,db) }
            // Requests and results name a record by its key (a hash), never by its id, which may hold a phone number.
            val byKey=db.pending().associateBy { CallInbox.recordKey(it.recordId) }
            val files=store.list(CallInbox.RESULTS).filterNot { it.first.startsWith("selftest-") } // CI end-to-end check, not a call of this phone
            class Got(val name:String,val sha:String,val text:String,val results:List<Pair<String,ClaudeCallAnalysis>>?)
            val got=files.map { (name,sha) -> val t=store.get(CallInbox.RESULTS+"/"+name);Got(name,sha,t,runCatching { CallInbox.parseResults(t) }.getOrNull()) }
            for(g in got.filter { it.results==null }) { store.put(CallInbox.BAD+"/"+g.name,g.text);store.delete(CallInbox.RESULTS+"/"+g.name,g.sha) } // broken: kept aside
            val failedFiles=mutableSetOf<String>()
            // In call-time order, so the ledger and the check items follow the order things happened.
            val items=got.filter { it.results!=null }.flatMap { g -> g.results!!.map { (id,a) -> Triple(g.name,(byKey[id] ?: db.record(id)),a) } }
                .filter { it.second!=null && it.second!!.state!=CallRecordStore.ANALYZED }.sortedBy { it.second!!.dayMs }
            for((name,record,analysis) in items) runCatching {
                val isOld=FirstPass.isOld(record!!.dayMs,s)
                // Checked against what is there now, more widely than when it was sent: what changed since must not drop good items.
                val request=db.request(record,isOld,wide=true)
                // Memos come from the repository ledger, which the task keeps in time order.
                val a=analyzer.filter(analysis,request,db.openTaskIds()).copy(siteMemo=null,companyMemo=null,companyTrade=null)
                val dropped=analysis.tasks.size+analysis.promises.size-a.tasks.size-a.promises.size
                db.applyAnalysis(record.recordId,if(FirstPass.beforeStart(record.dayMs,s)) FirstPass.restrict(a,record.dayMs,clock()) else a)
                save { it.put(PipelineSettingsKeys.LAST_ANALYZED_MS,clock()).put(PipelineSettingsKeys.DROPPED_ITEMS,it.optLong(PipelineSettingsKeys.DROPPED_ITEMS,0)+dropped) }
            }.onFailure { failedFiles+=name }
            for(g in got.filter { it.results!=null }) {
                val path=CallInbox.RESULTS+"/"+g.name
                if(g.name !in failedFiles) { store.delete(path,g.sha);continue }
                // Kept for the next run; after three failed runs it is set aside.
                val tries=JSONObject(settings().optString(PipelineSettingsKeys.RESULT_FAILS,"{}")).optInt(g.name,0)+1
                if(tries>=3) { store.put(CallInbox.BAD+"/"+g.name,g.text);store.delete(path,g.sha) }
                save { it.put(PipelineSettingsKeys.RESULT_FAILS,JSONObject(it.optString(PipelineSettingsKeys.RESULT_FAILS,"{}")).put(g.name,tries).toString()) }
            }
            runCatching { takeTidy(store,db,consent) }

            fun due(r:CallRecord):Boolean {
                if(!consent) { db.setState(r.recordId,CallRecordStore.WAITING_CONSENT);return false }
                if(r.state!=CallRecordStore.TRANSCRIBED) db.setState(r.recordId,CallRecordStore.TRANSCRIBED)
                if(r.transcript.isBlank()) { db.setState(r.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT");return false }
                return db.sentMs(r.recordId)?.let { clock()-it>=RESEND_MS } ?: true
            }
            val (old,live)=db.pending().partition { FirstPass.isOld(it.dayMs,s) }
            for(record in live.filter(::due)) {
                db.ledgerIdFor(record)
                // atMs: the task works in call-time order. A late old recording keeps the old-record marker.
                store.put(CallInbox.INBOX+"/"+CallInbox.fileName(record.recordId),CallInbox.job(CallInbox.recordKey(record.recordId),db.request(record,FirstPass.beforeStart(record.dayMs,s)),clock(),record.dayMs))
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
                    val items=chunk.map { r -> db.ledgerIdFor(r);CallInbox.recordKey(r.recordId) to db.request(r,true) }
                    store.put(CallInbox.INBOX+"/"+CallInbox.batchName(chunk.first().dayMs,items.map { it.first }),CallInbox.batch(items,clock(),chunk.map { it.dayMs }))
                    chunk.forEach { db.markSent(it.recordId,clock()) }
                    slots--
                }
                // Over only after 12 quiet hours: discovery brings recordings a few at a time, so the queue empties briefly.
                val quiet=consent && old.isEmpty() && bound==null
                val since=s.optLong(PipelineSettingsKeys.FIRST_PASS_QUIET_SINCE,0)
                when {
                    !quiet -> if(since>0) save { it.put(PipelineSettingsKeys.FIRST_PASS_QUIET_SINCE,0) }
                    since==0L -> save { it.put(PipelineSettingsKeys.FIRST_PASS_QUIET_SINCE,clock()) }
                    clock()-since>=12*3_600_000L -> save { it.put(PipelineSettingsKeys.FIRST_PASS_DONE,true) }
                }
            }
          } finally { if(consent) runCatching { warnIfStalled(db) } } // from data on the phone, whatever failed above
        }
        true
    } catch(e:java.io.IOException) { false }
    catch(e:ClaudeCallException) {
        // 401, 403, 404: the inbox refuses this phone (token expired or revoked). Retrying will not help: say so once a day.
        if(e.code in setOf("INBOX_HTTP_401","INBOX_HTTP_403","INBOX_HTTP_404")) warnDisconnected(e.code);true
    }

    /** Once a day, in working hours: records wait and no result came for 6 hours. The analysis may be stopped (the
     * subscription limit held it for three days, 11/1 to 11/3, and nobody knew). */
    private fun warnIfStalled(db:CallRecordStore) {
        val s=settings();val now=clock()
        val at=java.time.Instant.ofEpochMilli(now).atZone(CallRecordStore.KST)
        // When the oldest waiting record was stored: sending time would not do (a resend every 6 hours renews it, and a
        // failed upload has none). Old first-pass records may rightly wait for the charger and are not counted.
        val waiting=db.pending().filter { !FirstPass.isOld(it.dayMs,s) }.minOfOrNull { it.createdMs } ?: return
        val last=maxOf(s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),waiting)
        if(at.hour !in 9..20 || now-last<6*3_600_000L || s.optString(PipelineSettingsKeys.STALL_WARNED_DAY)==at.toLocalDate().toString()) return
        runCatching { CallDigestNotifier.post(context,"stall",CallDigestText.Message("정리가 멈췄어요","통화가 6시간 넘게 정리되지 않고 있어요. 앱을 관리하는 분께 알려 주세요.")) }
        save { it.put(PipelineSettingsKeys.STALL_WARNED_DAY,at.toLocalDate().toString()) }
    }

    private fun warnDisconnected(code:String) {
        val at=java.time.Instant.ofEpochMilli(clock()).atZone(CallRecordStore.KST).toLocalDate().toString()
        if(settings().optString(PipelineSettingsKeys.DISCONNECT_WARNED_DAY)==at) return
        runCatching { CallDigestNotifier.post(context,"stall",CallDigestText.Message("연결이 끊겼어요","통화를 정리하는 곳과 연결되지 않아요. 앱을 관리하는 분께 이 알림을 보여 주세요.")) }
        save { it.put(PipelineSettingsKeys.DISCONNECT_WARNED_DAY,at).put(PipelineSettingsKeys.LAST_INBOX_ERROR,code) }
    }

    /** Takes the repository ledger when its rev changed (a lower rev means it was rebuilt). A missing ledger is fine. */
    private fun takeLedger(store:CallInboxStore,db:CallRecordStore) {
        val o=runCatching { JSONObject(store.get(CallInbox.LEDGER)) }.getOrNull() ?: return
        val rev=o.optLong("rev",0)
        if(rev==settings().optLong(PipelineSettingsKeys.LEDGER_REV,0)) return
        db.applyLedger(o)
        save { it.put(PipelineSettingsKeys.LEDGER_REV,rev).put(PipelineSettingsKeys.TIDIED_ONCE,o.optBoolean("tidiedOnce",false)) }
    }
    /** The morning tidy's side: its proposal is applied once (by rev), and once a night (21:00 to 05:00) the open check
     * items and record summaries go up for it, from the end of the first pass on. */
    private fun takeTidy(store:CallInboxStore,db:CallRecordStore,consent:Boolean) {
        runCatching { JSONObject(store.get(CallInbox.TIDY_PROPOSAL)) }.getOrNull()?.let { o ->
            val rev=o.optLong("rev",0)
            if(rev>settings().optLong(PipelineSettingsKeys.TIDY_REV,0)) { db.applyTidy(o);save { it.put(PipelineSettingsKeys.TIDY_REV,rev) } }
        }
        val s=settings();val now=clock()
        val hour=java.time.Instant.ofEpochMilli(now).atZone(CallRecordStore.KST).hour
        if(!consent || !FirstPass.done(s) || hour in 5..20 || now-s.optLong(PipelineSettingsKeys.TIDY_SENT_MS,0)<12*3_600_000L) return
        store.replace(CallInbox.TIDY_STATE,db.tidyState(true,now,all=!s.optBoolean(PipelineSettingsKeys.TIDIED_ONCE,false)).toString())
        save { it.put(PipelineSettingsKeys.TIDY_SENT_MS,now) }
    }
    /** From when old texts or mail may still come in (their first-pass reading goes forward from 10/1), or null when
     * both are read to the end or cannot be read (no permission, no mailbox, a mailbox that refuses the password). */
    private fun messagesBound(s:JSONObject):Long? {
        fun bound(reading:Boolean,cursorKey:String,untilKey:String):Long? {
            if(!reading) return null
            val until=s.optLong(untilKey,0);val cursor=s.optLong(cursorKey,0)
            return if(until>0 && cursor>=until) null else maxOf(cursor,FirstPass.FROM_MS)
        }
        // Three days after the first pass started, old texts and mail no longer hold the calls back (a reader that never
        // finishes must not stop the first pass, round 2).
        if(FirstPass.start(s)?.let { clock()-it>3*86_400_000L }==true) return null
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
