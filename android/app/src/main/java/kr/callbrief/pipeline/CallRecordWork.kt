package kr.callbrief.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject

/** Sends every stored transcript that is waiting to the scheduled task and applies what comes back. Consent is checked per record, right before sending. */
internal class CallRecordAnalysisRunner(
    private val context:Context,
    private val analyzer:ClaudeCallAnalyzer=ClaudeCallAnalyzer(),
    private val settings:()->JSONObject={ SecureSettings(context).snapshot() },
    private val clock:()->Long=System::currentTimeMillis,
    /** The inbox of the scheduled Claude task; without it (a build with no token) nothing is sent. */
    private val inbox:CallInboxStore?=CallInboxConfig.store(),
    private val save:((JSONObject)->Unit)->Unit={ edit -> runCatching { SecureSettings(context).update { edit(it) } } },
    private val callLog:()->List<FirstPass.LogEntry> ={ val rules=kr.callbrief.LocalPrivacy.rules(settings())
        FirstPass.readCallLog(context) { n -> rules.excluded(n,n) } },
    private val smsAllowed:()->Boolean={ context.checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED },
    /** Runs after a pass that sent something: the result pass at the next :45 (design D, v2.4). */
    private val afterSend:()->Unit={ CallResultsWork.schedule(context,1,androidx.work.ExistingWorkPolicy.KEEP) },
) {
    /** Requests this runner put in the inbox (one batch counts once). */
    var sent=0;private set
    /** Returns false when a network-type failure should be retried later by WorkManager (or when another pass held the
     * lock for longer than [PASS_WAIT_MS]). One pass at a time in the process: the hourly work and the pass right before a
     * report ([CallDigestPrefetch]) could otherwise read the same result files together and apply them twice. A second
     * caller waits for the first to finish and then runs; by then the results are applied and deleted, so it finds none. */
    fun runPending():Boolean {
        if(!PASS.tryLock() && !PASS.tryLock(PASS_WAIT_MS,java.util.concurrent.TimeUnit.MILLISECONDS)) return false
        return try { when {
            kr.callbrief.BuildConfig.LOCAL_PREVIEW -> true // preview calls stay as seeded
            inbox!=null -> runInbox(inbox)
            else -> true
        } } finally { PASS.unlock() }
    }
    companion object {
        /** Held for the whole of one analysis pass (see [runPending]). */
        internal val PASS=java.util.concurrent.locks.ReentrantLock()
        /** How long a second caller waits for the running pass before giving up for now. */
        const val PASS_WAIT_MS=10*60_000L
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
            // The answer is here: from now on the record is not sent again, whatever the clock says (B17).
            val recordsOf={ g:Got -> g.results!!.mapNotNull { (id,_) -> byKey[id] ?: db.record(id) } }
            for(g in got.filter { it.results!=null }) recordsOf(g).forEach { r -> if(db.receivedMs(r.recordId)==null) db.setReceived(r.recordId,clock()) }
            // In call-time order, so the ledger and the check items follow the order things happened.
            val items=got.filter { it.results!=null }.flatMap { g -> g.results!!.map { (id,a) -> Triple(g.name,(byKey[id] ?: db.record(id)),a) } }
                .filter { it.second!=null && it.second!!.state!=CallRecordStore.ANALYZED }.sortedBy { it.second!!.dayMs }
            var duplicates=0
            for((name,record,analysis) in items) runCatching {
                val isOld=FirstPass.stale(record!!.dayMs,clock())
                // Checked against what is there now, more widely than when it was sent: what changed since must not drop good items.
                val request=db.request(record,isOld,wide=true)
                // Memos come from the repository ledger, which the task keeps in time order.
                val a=analyzer.filter(analysis,request,db.openTaskIds()).copy(siteMemo=null,companyMemo=null,companyTrade=null)
                val dropped=analysis.tasks.size+analysis.promises.size-a.tasks.size-a.promises.size
                val applied=db.applyAnalysis(record.recordId,if(FirstPass.stale(record.dayMs,clock())) FirstPass.restrict(a,record.dayMs,clock()) else a)
                duplicates+=applied.count { it.duplicate }
                save { it.put(PipelineSettingsKeys.LAST_ANALYZED_MS,clock()).put(PipelineSettingsKeys.DROPPED_ITEMS,it.optLong(PipelineSettingsKeys.DROPPED_ITEMS,0)+dropped) }
            }.onFailure { failedFiles+=name }
            if(duplicates>0) save { it.put(PipelineSettingsKeys.DUPLICATE_SKIPPED,it.optLong(PipelineSettingsKeys.DUPLICATE_SKIPPED,0)+duplicates) }
            for(g in got.filter { it.results!=null }) {
                val path=CallInbox.RESULTS+"/"+g.name
                if(g.name !in failedFiles) { store.delete(path,g.sha);continue }
                // Kept for the next run; after three failed runs it is set aside.
                val tries=JSONObject(settings().optString(PipelineSettingsKeys.RESULT_FAILS,"{}")).optInt(g.name,0)+1
                if(tries>=3) { store.put(CallInbox.BAD+"/"+g.name,g.text);store.delete(path,g.sha);recordsOf(g).forEach { r -> db.setReceived(r.recordId,null) } } // set aside: may be sent again
                save { it.put(PipelineSettingsKeys.RESULT_FAILS,JSONObject(it.optString(PipelineSettingsKeys.RESULT_FAILS,"{}")).put(g.name,tries).toString()) }
            }
            runCatching { takeTidy(store,db,consent) }

            fun due(r:CallRecord):Boolean {
                if(!consent) { db.setState(r.recordId,CallRecordStore.WAITING_CONSENT);return false }
                if(r.state!=CallRecordStore.TRANSCRIBED) db.setState(r.recordId,CallRecordStore.TRANSCRIBED)
                if(r.transcript.isBlank()) { db.setState(r.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT");return false }
                // A number found personal before (design E9): kept here as personal, never sent.
                if(db.isPersonal(r.counterpartKey)) { db.keepPersonal(r.recordId);return false }
                // Sent and answered (the answer waits to be applied): never again. Sent and silent for 6 hours: again.
                if(db.receivedMs(r.recordId)!=null) return false
                return db.sentMs(r.recordId)?.let { clock()-it>=RESEND_MS } ?: true
            }
            val user=userInfo(s)
            fun request(r:CallRecord,old:Boolean)=db.request(r,old).copy(user=user)
            // Live = the last 7 days (r7-1): one file each, whenever the first pass started; older ones wait for its batches.
            val (old,live)=db.pending().partition { FirstPass.isOld(it.dayMs,s,clock()) }
            for(record in live.filter(::due)) {
                db.ledgerIdFor(record)
                // atMs: the task works in call-time order. A late old recording keeps the old-record marker.
                store.put(CallInbox.INBOX+"/"+CallInbox.fileName(record.recordId),CallInbox.job(CallInbox.recordKey(record.recordId),request(record,FirstPass.stale(record.dayMs,clock())),clock(),record.dayMs))
                db.markSent(record.recordId,clock());sent++
            }
            val liveFrom=FirstPass.liveFrom(s,clock())
            if(liveFrom!=null) {
                val inFlight=old.count { r -> db.sentMs(r.recordId)?.let { clock()-it<RESEND_MS }==true }
                // Old records earlier than this are complete; later ones may still be joined by older ones. Recordings
                // before 10/1 and skipped ones never hold it (r7-1).
                val jobBound=db.oldestOldJobMs(liveFrom);val textBound=messagesBound(s)
                val bound=listOfNotNull(jobBound,textBound).minOrNull()
                val waitingSince=db.pending().mapNotNull { r -> db.sentMs(r.recordId)?.takeIf { db.receivedMs(r.recordId)==null } }.minOrNull()
                val free=FirstPass.BATCHES_IN_FLIGHT-(inFlight+FirstPass.BATCH-1)/FirstPass.BATCH
                var slots=batchSlots(s,waitingSince,free)
                val backoff=slots<=0 && free>0
                val ready=old.filter(::due).sortedBy { it.dayMs }
                val sendable=ready.filter { bound==null || it.dayMs<bound }
                // A batch that is not full goes too once its oldest record has waited 6 hours (r7-1): the first pass must
                // not wait for 15 in a row while the bound moves slowly.
                val overdue=sendable.minOfOrNull { it.createdMs }?.let { clock()-it>=FirstPass.PARTIAL_AFTER_MS }==true
                var sentBatches=0
                for(chunk in sendable.chunked(FirstPass.BATCH)) {
                    if(slots<=0 || (chunk.size<FirstPass.BATCH && bound!=null && !overdue)) break
                    val items=chunk.map { r -> db.ledgerIdFor(r);CallInbox.recordKey(r.recordId) to request(r,true) }
                    store.put(CallInbox.INBOX+"/"+CallInbox.batchName(chunk.first().dayMs,items.map { it.first }),CallInbox.batch(items,clock(),chunk.map { it.dayMs }))
                    chunk.forEach { db.markSent(it.recordId,clock()) };sent++;sentBatches++
                    val today=kstDay()
                    save { it.put(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,(if(it.optString(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY)==today) it.optInt(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,0) else 0)+1)
                        .put(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY,today) }
                    slots--
                }
                // For the heartbeat: what holds the old records that are ready but did not go up in this pass.
                val held=sentBatches==0 && ready.isNotEmpty()
                val blocked=when {
                    !held -> FirstPass.BLOCKED_NONE
                    backoff -> FirstPass.BLOCKED_BACKOFF
                    slots<=0 -> FirstPass.BLOCKED_NONE // two batches in flight: their answers are awaited
                    textBound!=null && (jobBound==null || textBound<=jobBound) -> FirstPass.BLOCKED_MESSAGES
                    else -> FirstPass.BLOCKED_TRANSCRIPTS
                }
                noteFirstPass(s,bound,blocked)
                // Over only after 12 quiet hours: discovery brings recordings a few at a time, so the queue empties briefly.
                val quiet=consent && old.isEmpty() && bound==null
                val since=s.optLong(PipelineSettingsKeys.FIRST_PASS_QUIET_SINCE,0)
                when {
                    !quiet -> if(since>0) save { it.put(PipelineSettingsKeys.FIRST_PASS_QUIET_SINCE,0) }
                    since==0L -> save { it.put(PipelineSettingsKeys.FIRST_PASS_QUIET_SINCE,clock()) }
                    clock()-since>=12*3_600_000L -> save { it.put(PipelineSettingsKeys.FIRST_PASS_DONE,true) }
                }
            }
          } finally {
            if(consent) runCatching { warnIfStalled(db) } // from data on the phone, whatever failed above
            if(sent>0) runCatching { afterSend() }
          }
        }
        // A whole pass went through: an error kept from an earlier one is over.
        if(settings().optString(PipelineSettingsKeys.LAST_INBOX_ERROR).isNotEmpty()) save { it.put(PipelineSettingsKeys.LAST_INBOX_ERROR,"").put(PipelineSettingsKeys.LAST_INBOX_ERROR_MS,0L) }
        true
    } catch(e:ClaudeCallException) {
        // Every refusal is kept as its code and time (r7-1, #16), never the reply's text. 401, 403, 404: the inbox refuses
        // this phone (token expired or revoked); retrying will not help, so say so once a day and do not retry now.
        noteInboxError(e.code)
        if(e.code in setOf("INBOX_HTTP_401","INBOX_HTTP_403","INBOX_HTTP_404")) { warnDisconnected();true } else false
    } catch(e:Exception) {
        // Network, rate limit, a server error, the database (#17): the code or the class name only, and WorkManager retries.
        noteInboxError(errorCode(e));false
    }

    /** The code of a failed pass: the exception's message when it is a code (INBOX_HTTP_503), else its class name. */
    private fun errorCode(e:Exception)=e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: e.javaClass.simpleName
    private fun noteInboxError(code:String)=runCatching { save { it.put(PipelineSettingsKeys.LAST_INBOX_ERROR,code).put(PipelineSettingsKeys.LAST_INBOX_ERROR_MS,clock()) } }
    /** The first pass's bound and what holds it, written only when they changed (the heartbeat reads them). */
    private fun noteFirstPass(s:JSONObject,bound:Long?,blocked:String) {
        if(s.optLong(FirstPass.BOUND_KEY,0)==(bound ?: 0L) && s.optString(FirstPass.BLOCKED_KEY)==blocked) return
        save { it.put(FirstPass.BOUND_KEY,bound ?: 0L).put(FirstPass.BLOCKED_KEY,blocked) }
    }

    private fun kstDay()=java.time.Instant.ofEpochMilli(clock()).atZone(CallRecordStore.KST).toLocalDate().toString()
    /** How many first-pass batches may go up now (design C7): none while something sent waits since [waitingSince] and no
     * result came for [FirstPass.STALL_MS] (the routine is held, most likely by the subscription limit); never more than
     * [FirstPass.BATCHES_PER_DAY] a KST day; otherwise [free] (the room left in flight). */
    internal fun batchSlots(s:JSONObject,waitingSince:Long?,free:Int):Int {
        val now=clock()
        if(waitingSince!=null && now-waitingSince>=FirstPass.STALL_MS && now-s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0)>=FirstPass.STALL_MS) return 0
        val today=if(s.optString(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY)==kstDay()) s.optInt(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,0) else 0
        return minOf(free,FirstPass.BATCHES_PER_DAY-today).coerceAtLeast(0)
    }
    /** "내 정보" as the request's [사용자] line takes it; null when nothing is entered. */
    private fun userInfo(s:JSONObject)=ClaudeCallRequest.UserInfo(s.optString(PipelineSettingsKeys.MY_NAME).trim().ifEmpty { null },
        s.optString(PipelineSettingsKeys.MY_COMPANY).trim().ifEmpty { null },s.optString(PipelineSettingsKeys.MY_ROLE).trim().ifEmpty { null }).takeIf { !it.empty }

    /** Once a day, in working hours: records wait and no result came for 6 hours. The analysis may be stopped (the
     * subscription limit held it for three days, 11/1 to 11/3, and nobody knew). */
    private fun warnIfStalled(db:CallRecordStore) {
        val s=settings();val now=clock()
        val at=java.time.Instant.ofEpochMilli(now).atZone(CallRecordStore.KST)
        // When the oldest waiting record was stored: sending time would not do (a resend every 6 hours renews it, and a
        // failed upload has none). Old first-pass records may rightly wait for the charger and are not counted.
        val waiting=db.pending().filter { !FirstPass.isOld(it.dayMs,s,now) }.minOfOrNull { it.createdMs } ?: return
        val last=maxOf(s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0),waiting)
        if(at.hour !in 9..20 || now-last<6*3_600_000L || s.optString(PipelineSettingsKeys.STALL_WARNED_DAY)==at.toLocalDate().toString()) return
        runCatching { CallDigestNotifier.post(context,CallDigestWork.STATUS,CallDigestText.Message("정리가 멈췄어요","통화가 6시간 넘게 정리되지 않고 있어요. 휴대폰이 인터넷에 연결되어 있으면 매시간 다시 확인해요.")) }
        save { it.put(PipelineSettingsKeys.STALL_WARNED_DAY,at.toLocalDate().toString()) }
    }

    private fun warnDisconnected() {
        val at=java.time.Instant.ofEpochMilli(clock()).atZone(CallRecordStore.KST).toLocalDate().toString()
        if(settings().optString(PipelineSettingsKeys.DISCONNECT_WARNED_DAY)==at) return
        runCatching { CallDigestNotifier.post(context,CallDigestWork.STATUS,CallDigestText.Message("연결이 끊겼어요","통화를 정리하는 곳과 연결되지 않아요. 앱을 관리하는 분께 이 알림을 보여 주세요.")) }
        save { it.put(PipelineSettingsKeys.DISCONNECT_WARNED_DAY,at) }
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
        val state=db.tidyState(true,now,all=!s.optBoolean(PipelineSettingsKeys.TIDIED_ONCE,false),mergeCandidates=mergeCandidates(s))
        // The same content as last time is not sent again (round 6): the state on the inbox then gets older than a day and
        // the morning tidy rests by its own rule. The next run tries again, so a change later in the night still goes up.
        val hash=tidyHash(state)
        if(hash==s.optString(TIDY_STATE_HASH)) return
        store.replace(CallInbox.TIDY_STATE,state.toString())
        save { it.put(PipelineSettingsKeys.TIDY_SENT_MS,now).put(TIDY_STATE_HASH,hash) }
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
}

internal object CallInboxConfig {
    fun store():CallInboxStore? = kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.takeIf { it.isNotBlank() }
        ?.let { GitHubInboxStore(kr.callbrief.BuildConfig.IEUM_INBOX_REPO,it,client) }
    /** GitHubInboxStore's own settings plus [InboxETags]. One client for the process, so the kept ETags serve every pass. */
    private val client by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(20,java.util.concurrent.TimeUnit.SECONDS).readTimeout(40,java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(InboxETags).build() }
    /** The preview build shows the subscription screens but has no token, so nothing is uploaded. */
    val enabled get()=kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.isNotBlank()||kr.callbrief.BuildConfig.LOCAL_PREVIEW
}
internal const val RESEND_MS=6*3_600_000L
/** Settings key (r7-4 fills it): id pairs the phone skipped as near duplicates, for the morning tidy's merge judgement. */
internal const val MERGE_CANDIDATES="mergeCandidates"
/** The [MERGE_CANDIDATES] list as kept in settings (a JSON array, or its text), or null when there is none. */
internal fun mergeCandidates(s:JSONObject):JSONArray?=when(val v=s.opt(MERGE_CANDIDATES)) {
    is JSONArray -> v
    is String -> runCatching { JSONArray(v) }.getOrNull()
    else -> null
}?.takeIf { it.length()>0 }
/** Settings key: SHA-256 of the last tidy state that went up, without its time stamp (round 6). */
internal const val TIDY_STATE_HASH="tidyStateHash"
/** The tidy state's content hash: generatedMs is left out, everything else counts. */
internal fun tidyHash(state:JSONObject):String {
    val body=JSONObject(state.toString()).apply { remove("generatedMs") }.toString()
    return java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class CallAnalysisWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val storage=CallPipelineStartup.storage(applicationContext)
        if(CallRecordAnalysisRunner(storage).runPending()) Result.success() else Result.retry()
    }
}

/** Conditional reads (If-None-Match) of what every pass reads and what seldom changes: the results folder listing, the
 * ledger and the tidy proposal. A 304 is answered with the copy kept from the last 200, so the store sees a plain 200,
 * nothing is downloaded again and GitHub does not count it against the rate limit. Kept in memory only (the ledger holds
 * names, it is not written to disk): a new process starts with one full read. */
internal object InboxETags:okhttp3.Interceptor {
    private class Kept(val etag:String,val body:ByteArray,val type:okhttp3.MediaType?)
    private val kept=java.util.concurrent.ConcurrentHashMap<String,Kept>()
    private val PATHS=listOf(CallInbox.RESULTS,CallInbox.LEDGER,CallInbox.TIDY_PROPOSAL).map { "/contents/$it" }
    internal fun cacheable(path:String)=PATHS.any { path.endsWith(it) }
    internal fun clear()=kept.clear()
    override fun intercept(chain:okhttp3.Interceptor.Chain):okhttp3.Response {
        val r=chain.request()
        if(r.method!="GET" || !cacheable(r.url.encodedPath)) return chain.proceed(r)
        val key=r.url.toString()+"|"+r.header("Accept").orEmpty()
        val k=kept[key]
        val res=chain.proceed(if(k==null) r else r.newBuilder().header("If-None-Match",k.etag).build())
        return when {
            res.code==304 && k!=null -> { res.close();res.newBuilder().code(200).message("OK").body(k.body.toResponseBody(k.type)).build() }
            res.code==200 && res.header("ETag")!=null -> {
                val type=res.body?.contentType();val bytes=res.body?.bytes() ?: ByteArray(0)
                kept[key]=Kept(res.header("ETag")!!,bytes,type)
                res.newBuilder().body(bytes.toResponseBody(type)).build()
            }
            else -> { if(res.code==404) kept.remove(key);res }
        }
    }
}
