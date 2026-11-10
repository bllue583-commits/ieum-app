package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.co.callsummary.core.android.AndroidSqlPort

/** Job ledger for on-device transcription, in the same protected (Keystore-sealed) local database as the
 * recordings it points at. One row per recording version; states QUEUED|RUNNING|DONE|FAILED|SKIPPED. */
internal class CallTranscriptionStore(context:Context,private val now:()->Long=System::currentTimeMillis,
    /** Called with how many jobs just ended FAILED (r12-3: the heartbeat's `transcriptionFailed`); a failure to count loses only the count. */
    private val failed:(Int)->Unit={ n -> runCatching { kr.callbrief.SecureSettings(context).update {
        it.put(PipelineSettingsKeys.TRANSCRIPTION_FAILED,it.optLong(PipelineSettingsKeys.TRANSCRIPTION_FAILED,0)+n) } } }):AutoCloseable {
    data class Key(val recordingId:String,val sourceVersion:Long,val audioSha:String)
    /** A current recording version with encrypted audio in the vault. */
    data class Candidate(val key:Key,val displayName:String,val uri:String,val mime:String,val startedAtMs:Long?,
                         val archived:Boolean,val grantActive:Boolean,val bytes:Long=0,val modifiedMs:Long=0)
    data class Job(val candidate:Candidate,val state:String,val reason:String,val attempts:Int)

    private val db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    init {
        try {
            db.requireProtectedPersistence();GeneralLocalStore.overlay(db)
            val ledger=context.assets.open("vault2_ledger.sql").bufferedReader().use{it.readText()}
            db.transaction{
                ledger.split("\n-- statement-break\n").forEach{db.execute(it)}
                // Finished 2-minute pieces of a transcript, so a long call survives the worker's time limit (review 11/4, round 2).
                db.execute("CREATE TABLE IF NOT EXISTS call_transcription_parts(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,chunk INTEGER NOT NULL,segments_json TEXT NOT NULL,chunk_ms INTEGER,PRIMARY KEY(recording_id,source_version,audio_sha,chunk))")
                // Installs from before the piece length was recorded: old rows get NULL and are never reused (the length changed 5 -> 2 minutes).
                if(db.query("PRAGMA table_info(call_transcription_parts)").none { it["name"]=="chunk_ms" }) db.execute("ALTER TABLE call_transcription_parts ADD COLUMN chunk_ms INTEGER")
                // How many times in a row a worker stop hit one chunk; three halves the chunk (B4).
                db.execute("CREATE TABLE IF NOT EXISTS call_transcription_stalls(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,chunk INTEGER NOT NULL,stops INTEGER NOT NULL,PRIMARY KEY(recording_id,source_version,audio_sha,chunk))")
                db.execute("""CREATE TABLE IF NOT EXISTS call_transcription_jobs(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,
                    audio_sha TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('QUEUED','RUNNING','DONE','FAILED','SKIPPED')),
                    reason TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL,
                    PRIMARY KEY(recording_id,source_version,audio_sha))""")
                // r12-3 (DESIGN_V4 2.3-4): when the row entered the queue; claim and finish leave it, so a retried job keeps
                // its age (updated_ms moved on every try). Rows from before: their updated_ms is the best guess there is.
                if(db.query("PRAGMA table_info(call_transcription_jobs)").none { it["name"]=="queued_ms" }) {
                    db.execute("ALTER TABLE call_transcription_jobs ADD COLUMN queued_ms INTEGER")
                    db.execute("UPDATE call_transcription_jobs SET queued_ms=updated_ms WHERE state IN('QUEUED','RUNNING')")
                }
                // r12-8 (DESIGN_V4 2-3): the decoded length once a recording went through to the end (A1 length buckets, A8 long calls).
                if(db.query("PRAGMA table_info(call_transcription_jobs)").none { it["name"]=="audio_ms" }) db.execute("ALTER TABLE call_transcription_jobs ADD COLUMN audio_ms INTEGER")
                // r12-8 A4: pieces whose native run hit its own deadline; such a piece is done as two halves from the next run on.
                db.execute("CREATE TABLE IF NOT EXISTS call_transcription_splits(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,chunk INTEGER NOT NULL,PRIMARY KEY(recording_id,source_version,audio_sha,chunk))")
                // Encrypted originals deleted by GeneralLocalStore.pruneVault (#39); a recording whose blob is gone is not offered again.
                db.execute("CREATE TABLE IF NOT EXISTS vault_pruned(ciphertext_sha256 TEXT PRIMARY KEY,at_ms INTEGER NOT NULL)")
                // Recordings from before 10/1 left queued by an older build (r7-1): one statement, whatever the offer pass does.
                db.execute(SKIP_BEFORE_START_SQL,now(),FirstPass.FROM_MS)
                // v3.5 r15-0: the build (versionCode) a row last failed under, and the build that last gave a FAILED row another try.
                // v3.5 r15-4 (M4): how often a FAILED row was given another try (NULL: never), capped by [requeueFailedOnce].
                for(col in listOf("failed_build","retried_build","requeue_count")) if(db.query("PRAGMA table_info(call_transcription_jobs)").none { it["name"]==col }) db.execute("ALTER TABLE call_transcription_jobs ADD COLUMN $col INTEGER")
                // v3.6 r16-0 (DESIGN_V6 W4): charging_only = taken only while plugged in (deadline twice, or interrupted once);
                // permanent = a failure that comes back the same however often it is tried (never revived); deadline_count for the first.
                for(col in listOf("charging_only","permanent","deadline_count")) if(db.query("PRAGMA table_info(call_transcription_jobs)").none { it["name"]==col }) db.execute("ALTER TABLE call_transcription_jobs ADD COLUMN $col INTEGER NOT NULL DEFAULT 0")
                // v3.6 W5: failed rows are no longer revived here but at the start of a plugged-in run ([requeueFailed]).
                null
            }
        } catch(e:Exception){db.close();throw e}
    }

    /** v3.5 r15-0 (DESIGN_V5 2, r15-0): FAILED rows go back to the queue once for each new build (a later build may read what an
     * older one could not), with their attempts at 0. A row that fails under build B is marked with it (failed_build), and a row
     * this build revived is marked too (retried_build): either way the same build never revives it again. It runs each time the
     * store opens, so the app start or the day's first transcription run does it; after that it finds nothing.
     * v3.5 r15-4 (M4): at most [MAX_REQUEUES] revivals for a row over all builds, and [MAX_REQUEUES_INTERRUPTED] when it last
     * failed as INTERRUPTED (a crash loop under the native reader would only crash again); requeue_count counts them.
     * Returns how many rows were revived now. */
    internal fun requeueFailedOnce(build:Int):Int {
        if(build<=0) return 0
        val where="state='FAILED' AND permanent=0 AND coalesce(failed_build,0)!=CAST(? AS INTEGER) AND coalesce(retried_build,0)!=CAST(? AS INTEGER)"+
            " AND coalesce(requeue_count,0)<(CASE WHEN reason='INTERRUPTED' THEN $MAX_REQUEUES_INTERRUPTED ELSE $MAX_REQUEUES END)"
        val n=db.query("SELECT count(*) AS n FROM call_transcription_jobs WHERE $where",build,build).single()["n"].let { (it as Number).toInt() }
        if(n>0) db.transaction {
            // v3.6 W5: the stop counts and halving marks of a revived row start again too (its finished pieces stay).
            // A halving mark whose halves are saved stays, else the next run would take the piece whole and drop them (review r16-1 #6).
            for(t in listOf("call_transcription_stalls","call_transcription_splits"))
                db.execute("DELETE FROM $t WHERE EXISTS(SELECT 1 FROM call_transcription_jobs WHERE call_transcription_jobs.recording_id=$t.recording_id AND call_transcription_jobs.source_version=$t.source_version AND call_transcription_jobs.audio_sha=$t.audio_sha AND $where)"+
                    (if(t=="call_transcription_splits") " AND NOT EXISTS(SELECT 1 FROM call_transcription_parts p WHERE p.recording_id=$t.recording_id AND p.source_version=$t.source_version AND p.audio_sha=$t.audio_sha AND p.chunk IN(-(2*$t.chunk+1),-(2*$t.chunk+2)))" else ""),build,build)
            db.execute("UPDATE call_transcription_jobs SET state='QUEUED',reason='$REQUEUED',attempts=0,deadline_count=0,updated_ms=?,queued_ms=?,retried_build=CAST(? AS INTEGER),requeue_count=coalesce(requeue_count,0)+1 WHERE $where",
                now(),now(),build,build,build);null
        }
        return n
    }
    /** v3.6 W5: [requeueFailedOnce] for the running build; called at the start of a plugged-in run. */
    fun requeueFailed()=requeueFailedOnce(buildCode())
    /** Recordings whose text failed (FAILED now): the "글자로 바꾸기" light. */
    fun failedCount():Int = db.query("SELECT count(*) AS n FROM call_transcription_jobs WHERE state='FAILED'").single()["n"].let { (it as Number).toInt() }

    // The call time comes from the recorder's filename (Samsung: "..._yyMMdd_HHmmss"); intake keeps it on local_files.
    private val select="""SELECT r.recording_id,r.source_version,v.audio_sha256,r.display_name,r.mime_type,
        coalesce(r.original_started_at_ms,l.filename_time_candidate_ms) AS original_started_at_ms,
        r.byte_count,r.modified_at_ms,l.actual_uri,a.archived,g.active AS grant_active
        FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        JOIN local_files l ON l.recording_id=r.recording_id JOIN local_visibility a ON a.recording_id=r.recording_id
        JOIN folder_grants g ON g.grant_id=r.grant_id
        JOIN vault2_refs q ON q.recording_id=r.recording_id AND q.source_version=r.source_version AND q.owner_key=r.owner_key AND q.plaintext_sha256=v.audio_sha256
        LEFT JOIN vault_pruned gone ON gone.ciphertext_sha256=q.ciphertext_sha256 AND gone.at_ms>0"""
    private fun candidate(r:Map<String,Any?>)=Candidate(Key(r["recording_id"].toString(),(r["source_version"] as Number).toLong(),r["audio_sha256"].toString()),
        r["display_name"].toString(),r["actual_uri"].toString(),r["mime_type"].toString(),(r["original_started_at_ms"] as Number?)?.toLong(),
        (r["archived"] as Number).toInt()==1,(r["grant_active"] as Number).toInt()==1,
        (r["byte_count"] as Number?)?.toLong() ?: 0,(r["modified_at_ms"] as Number?)?.toLong() ?: 0)

    /** r12-3 (DESIGN_V4 2.3-4): when the oldest job still waiting for its text (QUEUED, or RUNNING now) entered the queue
     * (queued_ms; null: none waits). Jobs of recordings before 10/1 are skipped at open, so they never count. */
    fun oldestQueuedMs():Long? = db.query("SELECT MIN(coalesce(queued_ms,updated_ms)) AS m FROM call_transcription_jobs WHERE state IN('QUEUED','RUNNING')")
        .singleOrNull()?.get("m")?.let { (it as? Number)?.toLong() }
    /** The owner's current recording versions with encrypted audio, newest first. */
    fun candidates():List<Candidate> = db.query("$select WHERE r.owner_key=? AND r.presence='PRESENT' AND gone.ciphertext_sha256 IS NULL ORDER BY coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0) DESC",
        GeneralLocalStore.OWNER).map(::candidate)

    /** Idempotent: new versions are inserted; rows that have not started or finished follow the current eligibility.
     * "No speech" was found by transcribing, not by eligibility, so it stays (it was queued again on every pass before). */
    fun offer(c:Candidate,eligible:Boolean,reason:String)=tx {
        val state=if(eligible)"QUEUED" else "SKIPPED"
        db.execute("INSERT OR IGNORE INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,reason,updated_ms,queued_ms) VALUES(?,?,?,?,?,?,?)",
            c.key.recordingId,c.key.sourceVersion,c.key.audioSha,state,reason,now(),if(eligible) now() else null)
        // A skipped row that becomes eligible enters the queue now; a queued one keeps its entry time.
        db.execute("UPDATE call_transcription_jobs SET queued_ms=CASE WHEN ?='QUEUED' AND state!='QUEUED' THEN ? ELSE queued_ms END,state=?,reason=?,updated_ms=? WHERE recording_id=? AND source_version=? AND audio_sha=? AND state IN('QUEUED','SKIPPED') AND (state!=? OR reason!=?) AND NOT (state='SKIPPED' AND reason='NO_SPEECH')",
            state,now(),state,reason,now(),c.key.recordingId,c.key.sourceVersion,c.key.audioSha,state,reason);null
    }

    /** Every candidate through [offer] inside one transaction (r7-1, review #2): the protected store snapshots the whole
     * database once per outer transaction, so one per candidate made this pass take minutes. Stops between candidates
     * when [stopped] says so; what was offered until then is kept. Returns how many were offered. */
    fun offerAll(candidates:List<Candidate>,skipReason:(Candidate)->String?,stopped:()->Boolean={false}):Int = tx {
        var n=0
        for(c in candidates) { if(stopped()) break;val reason=skipReason(c);offer(c,reason==null,reason ?: "");n++ }
        n
    }
    /** Outer transactions this store opened through [tx] (tests check that the offer pass is one). */
    internal var outerTransactions=0;private set
    private var depth=0
    private fun <T> tx(work:()->T):T {
        if(depth++==0) outerTransactions++
        try { return db.transaction { work() } } finally { depth-- }
    }

    /** Queued rows whose recording moved on to a newer version (or vanished) are skipped. */
    fun skipSuperseded()=db.execute("""UPDATE call_transcription_jobs SET state='SKIPPED',reason='NOT_CURRENT',updated_ms=? WHERE state='QUEUED' AND NOT EXISTS(
        SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        WHERE r.recording_id=call_transcription_jobs.recording_id AND r.source_version=call_transcription_jobs.source_version AND v.audio_sha256=call_transcription_jobs.audio_sha)""",now())

    /** Rows left RUNNING by a process that died. The attempt stays counted, so a crash loop ends in FAILED.
     * Only rows untouched for [staleMs] (a bit over the 10 minute work limit): a RUNNING row younger than that may belong to a worker that is still going. */
    fun recoverRunning(maxAttempts:Int,staleMs:Long=STALE_RUNNING_MS) {
        val n=db.transaction {
            val limit=now()-staleMs
            val n=db.query("SELECT count(*) AS n FROM call_transcription_jobs WHERE state='RUNNING' AND attempts>=? AND updated_ms<?",maxAttempts,limit).single()["n"].let { (it as Number).toInt() }
            db.execute("UPDATE call_transcription_jobs SET state='FAILED',reason='INTERRUPTED',updated_ms=?,failed_build=CAST(? AS INTEGER) WHERE state='RUNNING' AND attempts>=? AND updated_ms<?",now(),buildCode(),maxAttempts,limit)
            // v3.6 W4: a recording the process died under is taken again only while plugged in.
            db.execute("UPDATE call_transcription_jobs SET state='QUEUED',reason='INTERRUPTED',charging_only=1,updated_ms=? WHERE state='RUNNING' AND updated_ms<?",now(),limit);n
        }
        if(n>0) failed(n)
    }

    /** Which recording goes next (r12-8 A2, v3.7 T2 DESIGN_V7 3.1, order in [QueueOrder]). With [oldBefore] (처음 정리)
     * recordings before it come after all newer ones. The rows are read with their order fields and sorted by the pure
     * [QueueOrder.compare] (a few hundred rows at most). */
    fun nextQueued(oldBefore:Long?=null,battery:BatteryWindow?=null):Job? {
        if(battery!=null) return nextShort(battery)
        val at="coalesce(x.original_started_at_ms,x.modified_at_ms,0)"
        val queued="coalesce(j.queued_ms,j.updated_ms)"
        val parts="EXISTS(SELECT 1 FROM call_transcription_parts p WHERE p.recording_id=j.recording_id AND p.source_version=j.source_version AND p.audio_sha=j.audio_sha)"
        val rows=db.query("""SELECT j.state,j.reason,j.attempts,j.updated_ms AS job_updated_ms,x.*,
                CASE WHEN $parts THEN 1 ELSE 0 END AS q_parts,$queued AS q_entered,$at AS q_at
            FROM call_transcription_jobs j JOIN ($select WHERE r.owner_key=? AND gone.ciphertext_sha256 IS NULL) x
            ON x.recording_id=j.recording_id AND x.source_version=j.source_version AND x.audio_sha256=j.audio_sha
            WHERE j.state='QUEUED'""".trimIndent(),GeneralLocalStore.OWNER)
        if(rows.isEmpty()) return null
        val t=now()
        fun n(v:Any?)=(v as? Number)?.toLong() ?: 0L
        val best=rows.map { r -> r to QueueOrder.Row(n(r["q_at"]),n(r["q_entered"]),n(r["job_updated_ms"]),(r["q_parts"] as Number).toInt()==1,
                r["reason"].toString().let { it=="STOPPED" || it==DEADLINE }) }
            .minWithOrNull { a,b -> QueueOrder.compare(a.second,b.second,t,oldBefore) }!!.first
        return Job(candidate(best),best["state"].toString(),best["reason"].toString(),(best["attempts"] as Number).toInt())
    }

    /** v3.6 (DESIGN_V6 2.1): what a battery run may take: calls from [fromMs] (KST midnight) whose length is between
     * [TranscriptionPower.SHORT_MIN_MS] and [TranscriptionPower.SHORT_MAX_MS]. The length is the decoded one, else the call
     * log's, else the file size over [bytesPerMs] (null: unknown, not taken). Never charging_only rows. */
    data class BatteryWindow(val fromMs:Long,val bytesPerMs:Double?)
    /** SQL for a job's length (j = the job row, [at] the recording's time); takes two arguments, bytes per ms twice (bound as
     * text, hence the casts). v3.7 (T3, review r16-1 #2): after the decoded length comes the call log's: the answered entry
     * closest to the recording's time in the usual pairing window ([FirstPass.inWindow]: 2 minutes before to 1 minute after),
     * since a waiting recording has no call record yet; then the call record's; then the size. */
    private fun lengthSql(byteCount:String,at:String):String {
        val tables=db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN('call_records','call_log')").map { it["name"].toString() }.toSet()
        val record=if("call_records" in tables) "(SELECT MAX(c.duration_ms) FROM call_records c WHERE c.recording_id=j.recording_id AND c.duration_ms>0)" else "NULL"
        val logged=if("call_log" in tables) "(SELECT cl.duration_ms FROM call_log cl WHERE cl.duration_ms>0 AND cl.type IN(1,2) AND $at>0 AND cl.start_ms>=$at-$LOG_BEFORE_MS AND cl.start_ms<=$at+$LOG_AFTER_MS ORDER BY abs(cl.start_ms-$at) LIMIT 1)" else "NULL"
        return "coalesce(j.audio_ms,$logged,$record,CASE WHEN CAST(? AS REAL)>0 AND $byteCount>0 THEN CAST($byteCount/CAST(? AS REAL) AS INTEGER) END)"
    }
    /** Short calls of today, newest first: the filter is in SQL, so a long call at the top never ends a battery run (DESIGN_V6 함정 ③). */
    private fun nextShort(w:BatteryWindow):Job? {
        val at="coalesce(x.original_started_at_ms,x.modified_at_ms,0)"
        val bpm=w.bytesPerMs ?: 0.0
        return db.query("""SELECT * FROM (SELECT j.state,j.reason,j.attempts,j.updated_ms AS job_updated_ms,x.*,$at AS q_at,${lengthSql("x.byte_count",at)} AS q_len
            FROM call_transcription_jobs j JOIN ($select WHERE r.owner_key=? AND gone.ciphertext_sha256 IS NULL) x
            ON x.recording_id=j.recording_id AND x.source_version=j.source_version AND x.audio_sha256=j.audio_sha
            WHERE j.state='QUEUED' AND j.charging_only=0) WHERE q_at>=CAST(? AS INTEGER) AND q_len>=CAST(? AS INTEGER) AND q_len<=CAST(? AS INTEGER)
            ORDER BY q_at DESC,job_updated_ms LIMIT 1""".trimIndent(),bpm,bpm,GeneralLocalStore.OWNER,w.fromMs,TranscriptionPower.SHORT_MIN_MS,TranscriptionPower.SHORT_MAX_MS)
            .singleOrNull()?.let{Job(candidate(it),it["state"].toString(),it["reason"].toString(),(it["attempts"] as Number).toInt())}
    }
    /** Bytes per ms of the recordings whose decoded length is known (for [BatteryWindow]); null without any. */
    fun bytesPerMs():Double? = db.query("SELECT sum(r.byte_count) AS b,sum(j.audio_ms) AS ms FROM call_transcription_jobs j JOIN recordings r ON r.recording_id=j.recording_id AND r.source_version=j.source_version WHERE j.audio_ms>0 AND r.byte_count>0")
        .singleOrNull()?.let { r -> val b=(r["b"] as? Number)?.toDouble() ?: 0.0;val ms=(r["ms"] as? Number)?.toDouble() ?: 0.0;if(b>0 && ms>0) b/ms else null }
    /** v3.6 (DESIGN_V6 3.2): queued recordings a battery run would not take (they wait for the charger), and the oldest
     * queue entry among them. With [chargingOnly] every queued row waits. */
    fun powerWaiting(w:BatteryWindow,chargingOnly:Boolean):Pair<Int,Long?> {
        val bpm=w.bytesPerMs ?: 0.0
        val cond=if(chargingOnly) "1=1" else "(charging_only=1 OR q_at<CAST(? AS INTEGER) OR q_len IS NULL OR q_len<CAST(? AS INTEGER) OR q_len>CAST(? AS INTEGER))"
        val args=mutableListOf<Any?>(bpm,bpm);if(!chargingOnly) args.addAll(listOf(w.fromMs,TranscriptionPower.SHORT_MIN_MS,TranscriptionPower.SHORT_MAX_MS))
        val r=db.query("""SELECT count(*) AS n,min(q_entered) AS m FROM (SELECT j.charging_only AS charging_only,coalesce(j.queued_ms,j.updated_ms) AS q_entered,
            coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0) AS q_at,${lengthSql("r.byte_count","coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)")} AS q_len
            FROM call_transcription_jobs j LEFT JOIN recordings r ON r.recording_id=j.recording_id AND r.source_version=j.source_version LEFT JOIN local_files l ON l.recording_id=j.recording_id
            WHERE j.state='QUEUED') WHERE $cond""".trimIndent(),*args.toTypedArray()).single()
        return ((r["n"] as Number).toInt()) to (r["m"] as? Number)?.toLong()
    }
    /** v3.6 W4: the piece ran into the native deadline once more; from the second time the recording waits for the charger. */
    fun noteDeadline(k:Key)=db.execute("UPDATE call_transcription_jobs SET charging_only=CASE WHEN deadline_count+1>=$DEADLINES_TO_CHARGER THEN 1 ELSE charging_only END,deadline_count=deadline_count+1 WHERE recording_id=? AND source_version=? AND audio_sha=?",
        k.recordingId,k.sourceVersion,k.audioSha)
    /** v3.6 W4: the failure is in the recording itself: FAILED for good, never revived. */
    fun failPermanently(k:Key,code:String) {
        db.execute("UPDATE call_transcription_jobs SET permanent=1 WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
        finish(k,"FAILED",code)
    }
    fun chargingOnly(k:Key):Boolean = db.query("SELECT charging_only FROM call_transcription_jobs WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
        .singleOrNull()?.let { (it["charging_only"] as Number).toInt()==1 } ?: false

    /** Finished pieces made with piece length [chunkMs]. Rows of any other length (or unknown, NULL) are deleted, with the stop counters
     * that were counted against those piece numbers: the same id then means a different stretch of audio. */
    fun parts(k:Key,chunkMs:Long=PipelineAudioMath.MAX_CHUNK_MS):Map<Int,List<CallTranscript.Segment>> {
        val stale=db.query("SELECT 1 FROM call_transcription_parts WHERE recording_id=? AND source_version=? AND audio_sha=? AND (chunk_ms IS NULL OR chunk_ms!=?) LIMIT 1",
            k.recordingId,k.sourceVersion,k.audioSha,chunkMs).isNotEmpty()
        if(stale)db.transaction {
            db.execute("DELETE FROM call_transcription_parts WHERE recording_id=? AND source_version=? AND audio_sha=? AND (chunk_ms IS NULL OR chunk_ms!=?)",k.recordingId,k.sourceVersion,k.audioSha,chunkMs)
            db.execute("DELETE FROM call_transcription_stalls WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
            db.execute("DELETE FROM call_transcription_splits WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha);null
        }
        return db.query("SELECT chunk,segments_json FROM call_transcription_parts WHERE recording_id=? AND source_version=? AND audio_sha=? AND chunk_ms=?",
            k.recordingId,k.sourceVersion,k.audioSha,chunkMs).associate { r -> (r["chunk"] as Number).toInt() to org.json.JSONArray(r["segments_json"].toString()).let { a ->
                (0 until a.length()).map { val x=a.getJSONArray(it);CallTranscript.Segment(x.getLong(0),x.getLong(1),x.getString(2)) } } }
    }
    fun savePart(k:Key,chunk:Int,segments:List<CallTranscript.Segment>,chunkMs:Long=PipelineAudioMath.MAX_CHUNK_MS)=db.execute(
        "INSERT OR REPLACE INTO call_transcription_parts(recording_id,source_version,audio_sha,chunk,segments_json,chunk_ms) VALUES(?,?,?,?,?,?)",
        k.recordingId,k.sourceVersion,k.audioSha,chunk,org.json.JSONArray().apply { segments.forEach { put(org.json.JSONArray().put(it.startMs).put(it.endMs).put(it.text)) } }.toString(),chunkMs)
    fun clearParts(k:Key)=db.transaction {
        db.execute("DELETE FROM call_transcription_parts WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
        db.execute("DELETE FROM call_transcription_stalls WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
        db.execute("DELETE FROM call_transcription_splits WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha);null
    }
    /** r12-8 A4: the native run on piece [chunk] ran into its own deadline; the piece is done as two halves from now on. */
    fun askSplit(k:Key,chunk:Int)=db.execute("INSERT OR IGNORE INTO call_transcription_splits VALUES(?,?,?,?)",k.recordingId,k.sourceVersion,k.audioSha,chunk)
    fun splitAsked(k:Key,chunk:Int):Boolean = db.query("SELECT 1 FROM call_transcription_splits WHERE recording_id=? AND source_version=? AND audio_sha=? AND chunk=?",
        k.recordingId,k.sourceVersion,k.audioSha,chunk).isNotEmpty()
    /** r12-8 A1: the recording's decoded length, once it went through to the end. */
    fun noteLength(k:Key,ms:Long)=db.execute("UPDATE call_transcription_jobs SET audio_ms=? WHERE recording_id=? AND source_version=? AND audio_sha=?",
        ms,k.recordingId,k.sourceVersion,k.audioSha)
    fun stalls(k:Key,chunk:Int):Int = db.query("SELECT stops FROM call_transcription_stalls WHERE recording_id=? AND source_version=? AND audio_sha=? AND chunk=?",
        k.recordingId,k.sourceVersion,k.audioSha,chunk).singleOrNull()?.let { (it["stops"] as Number).toInt() } ?: 0
    /** Stops counted against any piece of this recording (the halves use the whole piece's number). */
    fun totalStalls(k:Key):Int = db.query("SELECT coalesce(sum(stops),0) AS n FROM call_transcription_stalls WHERE recording_id=? AND source_version=? AND audio_sha=?",
        k.recordingId,k.sourceVersion,k.audioSha).single()["n"].let { (it as Number).toInt() }
    fun noteStall(k:Key,chunk:Int)=db.transaction {
        db.execute("INSERT OR IGNORE INTO call_transcription_stalls VALUES(?,?,?,?,0)",k.recordingId,k.sourceVersion,k.audioSha,chunk)
        db.execute("UPDATE call_transcription_stalls SET stops=stops+1 WHERE recording_id=? AND source_version=? AND audio_sha=? AND chunk=?",k.recordingId,k.sourceVersion,k.audioSha,chunk);null
    }

    /** QUEUED -> RUNNING, counting the attempt. False when the row is no longer queued. */
    fun claim(k:Key):Boolean = db.transaction {
        val queued=db.query("SELECT 1 FROM call_transcription_jobs WHERE recording_id=? AND source_version=? AND audio_sha=? AND state='QUEUED'",k.recordingId,k.sourceVersion,k.audioSha).isNotEmpty()
        if(queued)db.execute("UPDATE call_transcription_jobs SET state='RUNNING',attempts=attempts+1,updated_ms=? WHERE recording_id=? AND source_version=? AND audio_sha=?",now(),k.recordingId,k.sourceVersion,k.audioSha)
        queued
    }

    fun finish(k:Key,state:String,reason:String,refundAttempt:Boolean=false) {
        db.execute("UPDATE call_transcription_jobs SET state=?,reason=?,attempts=attempts-?,updated_ms=?,failed_build=CASE WHEN ?='FAILED' THEN CAST(? AS INTEGER) ELSE failed_build END WHERE recording_id=? AND source_version=? AND audio_sha=?",
            state,reason,if(refundAttempt)1 else 0,now(),state,buildCode(),k.recordingId,k.sourceVersion,k.audioSha)
        if(state=="FAILED") failed(1)
    }

    fun attempts(k:Key):Int = db.query("SELECT attempts FROM call_transcription_jobs WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
        .singleOrNull()?.let{(it["attempts"] as Number).toInt()} ?: 0

    /** All rows as (key, state, reason, attempts); for status display and tests. */
    fun rows():List<Triple<Key,String,Pair<String,Int>>> = db.query("SELECT * FROM call_transcription_jobs ORDER BY updated_ms,recording_id").map{
        Triple(Key(it["recording_id"].toString(),(it["source_version"] as Number).toLong(),it["audio_sha"].toString()),it["state"].toString(),
            it["reason"].toString() to (it["attempts"] as Number).toInt())
    }

    /** r12-8 A1/A8 (DESIGN_V4 2-3-5,6): the heartbeat's `transcription` numbers ([TranscriptionStats]). Counts and minutes only. */
    fun heartbeat(nowMs:Long=now()):org.json.JSONObject {
        val records=db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='call_records'").isNotEmpty()
        val recordLen=if(records) "(SELECT MAX(c.duration_ms) FROM call_records c WHERE c.recording_id=j.recording_id AND c.duration_ms>0)" else "NULL"
        fun n(v:Any?)=(v as? Number)?.toLong()
        val build=buildCode().toLong()
        val rows=db.query("""SELECT j.state,j.reason,j.updated_ms,coalesce(j.queued_ms,j.updated_ms) AS q,coalesce(j.audio_ms,$recordLen) AS len,r.byte_count AS b,j.retried_build AS rb,
            coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms) AS at
            FROM call_transcription_jobs j LEFT JOIN recordings r ON r.recording_id=j.recording_id LEFT JOIN local_files l ON l.recording_id=j.recording_id""")
            .map { TranscriptionStats.Row(it["state"].toString(),it["reason"].toString(),n(it["q"]),n(it["updated_ms"]) ?: 0,n(it["len"]),n(it["b"]),n(it["at"]),n(it["rb"])==build) }
        return TranscriptionStats.of(rows,nowMs)
    }

    override fun close()=db.close()
    companion object {
        /** The running build's versionCode for [requeueFailedOnce] (replaceable in tests). */
        @Volatile internal var buildCode:()->Int={ kr.callbrief.BuildConfig.VERSION_CODE }
        /** The reason of a FAILED row sent back to the queue by a new build. */
        const val REQUEUED="REQUEUED"
        /** v3.5 r15-4 (M4): revivals of one FAILED row in all, and of one that failed as INTERRUPTED. */
        const val MAX_REQUEUES=2
        const val MAX_REQUEUES_INTERRUPTED=1
        /** v3.5 r15-0 (user 11/6: the numbers count recordings): recordings of the KST day [day] that have no call record yet, the
         * ones still waiting for their text (QUEUED, RUNNING) and the failed ones. With the day's call records they make "녹음 n개"
         * of home and the evening report. [query] runs on the call database (the process store's [CallRecordStore.cardRows] does);
         * 0 when the job table is not there yet. */
        fun withoutRecord(day:java.time.LocalDate,query:(String,Array<Any?>)->List<Map<String,Any?>>):Int = runCatching {
            val from=day.atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli();val to=day.plusDays(1).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
            val at="coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)"
            val records=query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='call_records'",emptyArray()).isNotEmpty()
            val sql="SELECT count(DISTINCT j.recording_id) AS n FROM call_transcription_jobs j JOIN recordings r ON r.recording_id=j.recording_id AND r.source_version=j.source_version "+
                "LEFT JOIN local_files l ON l.recording_id=j.recording_id WHERE j.state IN('QUEUED','RUNNING','FAILED') AND $at>=CAST(? AS INTEGER) AND $at<CAST(? AS INTEGER)"+
                (if(records) " AND NOT EXISTS(SELECT 1 FROM call_records c WHERE c.recording_id=j.recording_id)" else "")
            (query(sql,arrayOf(from,to)).single()["n"] as Number).toInt()
        }.getOrDefault(0)
        /** r12-8 A2: a recording that has waited this long in the queue goes before new calls. */
        const val WAITED_MS=24*3_600_000L
        /** v3.7 (T3): the call-log window for a waiting recording's length, the same as [FirstPass.inWindow]. */
        const val LOG_BEFORE_MS=120_000L
        const val LOG_AFTER_MS=60_000L
        /** r12-8 A4: the reason of a job whose piece ran into the native deadline (the piece is halved, the attempt given back). */
        const val DEADLINE="ANDROID_STT_DEADLINE"
        const val STALE_RUNNING_MS=11*60_000L
        /** v3.6 W4: this many native deadlines on one recording send it to the charger. */
        const val DEADLINES_TO_CHARGER=2
        /** v3.6 W4: failures that come back the same however often they are tried (the m4a cannot be read, too short, nothing in it). */
        fun permanent(code:String)=code=="CodecException" || code=="ANDROID_STT_PCM_SIZE"
        /** Queued recordings whose time (start or filename time, else the file's modified time) is before 10/1 become
         * SKIPPED with BEFORE_START, the reason the offer pass gives them. Arguments: updated_ms, [FirstPass.FROM_MS]. */
        const val SKIP_BEFORE_START_SQL="""UPDATE call_transcription_jobs SET state='SKIPPED',reason='BEFORE_START',updated_ms=? WHERE state='QUEUED' AND recording_id IN(
            SELECT r.recording_id FROM recordings r LEFT JOIN local_files l ON l.recording_id=r.recording_id
            WHERE COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)>0
            AND COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)<CAST(? AS INTEGER))"""
    }
}

/** v3.7 T2 (DESIGN_V7 3.1): the order of the transcription queue, pure (JVM-tested). Yesterday's and today's calls first,
 * so the 05:30 report holds yesterday:
 * - first key `q_old` (처음 정리, the call before [oldBefore]) as before: the newer ones first;
 * - then, for newer ones: (1) under way: a piece is really saved and the call is from [fromMs] on ("next report day" − 1 at
 *   00:00 KST), longest in the queue first; (2) a call from [fromMs] on, newest first; (3) waited over
 *   [CallTranscriptionStore.WAITED_MS], longest in the queue first; (4) the rest, newest first. A recording with only a
 *   STOPPED or deadline mark and no saved piece, and an old one under way, go by their age like any other (review H2: one
 *   long old recording pushed back once would otherwise take the whole night);
 * - old ones (처음 정리) keep r12-8's order: under way (a piece, STOPPED or a deadline) first, then the oldest call. */
internal object QueueOrder {
    /** [atMs] the call time, [enteredMs] the queue entry, [updatedMs] the row's last change (last tie-break), [parts] a piece
     * is saved, [marked] the reason is STOPPED or a native deadline. */
    data class Row(val atMs:Long,val enteredMs:Long,val updatedMs:Long,val parts:Boolean,val marked:Boolean)
    /** The morning report's day: today before 05:00 KST (the report goes out in the 05 o'clock hour), tomorrow from then on. */
    const val REPORT_HOUR=5
    fun reportDay(now:Long):java.time.LocalDate { val z=java.time.Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST)
        return if(z.hour<REPORT_HOUR) z.toLocalDate() else z.toLocalDate().plusDays(1) }
    /** 00:00 KST of the day before [reportDay]: calls from then on are "yesterday and today" for the next report. */
    fun fromMs(now:Long)=reportDay(now).minusDays(1).atStartOfDay(TranscriptionPower.KST).toInstant().toEpochMilli()
    /** (group, key) of a row; smaller goes first. */
    fun rank(r:Row,now:Long,oldBefore:Long?):Triple<Int,Int,Long> {
        if(oldBefore!=null && r.atMs<oldBefore) return if(r.parts || r.marked) Triple(1,0,r.enteredMs) else Triple(1,1,r.atMs)
        val from=fromMs(now)
        return when {
            r.parts && r.atMs>=from -> Triple(0,0,r.enteredMs)
            r.atMs>=from -> Triple(0,1,-r.atMs)
            now-r.enteredMs>=CallTranscriptionStore.WAITED_MS -> Triple(0,2,r.enteredMs)
            else -> Triple(0,3,-r.atMs)
        }
    }
    fun compare(a:Row,b:Row,now:Long,oldBefore:Long?):Int {
        val x=rank(a,now,oldBefore);val y=rank(b,now,oldBefore)
        return compareValuesBy(x,y,{ it.first },{ it.second },{ it.third }).takeIf { it!=0 } ?: a.updatedMs.compareTo(b.updatedMs)
    }
    /** [rows] in queue order (tests). */
    fun sort(rows:List<Row>,now:Long,oldBefore:Long?=null)=rows.sortedWith { a,b -> compare(a,b,now,oldBefore) }
}

/** r12-8 A1 (DESIGN_V4 2-3-5): the day's transcription runs kept under [PipelineSettingsKeys.TRANSCRIPTION_RUNS]. */
internal object TranscriptionRuns {
    /** How a run ended: its pass state, a stop split into the work time limit (TIMEOUT) and anything else (OTHER), or ERROR. */
    fun endCode(state:String,timeLimited:Boolean)=if(state=="STOPPED") (if(timeLimited) "STOPPED_TIMEOUT" else "STOPPED_OTHER") else PhoneStatus.code(state).ifEmpty { "NONE" }
    /** [old] with one more run of [ms] ending as [end] on KST day [day] (yyyymmdd); a new day moves today's numbers to prev. */
    fun add(old:org.json.JSONObject?,day:Int,ms:Long,end:String):org.json.JSONObject {
        val same=old!=null && old.optInt("day")==day
        val o=if(same) org.json.JSONObject(old.toString()) else org.json.JSONObject().put("day",day).put("runs",0).put("ms",0L).put("ends",org.json.JSONObject())
        if(!same && old!=null && old.optInt("day")>0) o.put("prev",org.json.JSONObject().put("day",old.optInt("day")).put("runs",old.optInt("runs"))
            .put("minutes",old.optLong("minutes")).put("ends",old.optJSONObject("ends") ?: org.json.JSONObject()))
        val total=o.optLong("ms")+ms.coerceAtLeast(0)
        val ends=o.optJSONObject("ends") ?: org.json.JSONObject()
        ends.put(end,ends.optInt(end)+1)
        return o.put("runs",o.optInt("runs")+1).put("ms",total).put("minutes",total/60_000).put("ends",ends)
    }
    fun day(now:Long)=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().let { it.year*10000+it.monthValue*100+it.dayOfMonth }
}

/** r12-8 A1/A8 (DESIGN_V4 2-3-5,6): numbers about the transcription queue for the heartbeat's `transcription` object.
 * Pure (JVM-testable). A recording's length is its decoded length where known, else estimated from its size with the
 * bytes per millisecond of the recordings whose length is known; without any, it stays unknown. */
internal object TranscriptionStats {
    /** Calls longer than this are the "long calls" of A8 (DESIGN_V4 2-3: none over 100 s had ever reached the analysis). */
    const val LONG_CALL_MS=100_000L
    const val WINDOW_MS=48*3_600_000L
    /** [requeued]: v3.5 r15-0, the running build gave this row another try ([CallTranscriptionStore.requeueFailedOnce]). */
    data class Row(val state:String,val reason:String,val queuedMs:Long?,val updatedMs:Long,val lengthMs:Long?,val bytes:Long?,val callMs:Long?,val requeued:Boolean=false)
    /** Length bucket: lt1m, m1to3, m3to10, m10plus, unknown. */
    fun bucket(ms:Long?)=when { ms==null || ms<=0 -> "unknown";ms<60_000 -> "lt1m";ms<180_000 -> "m1to3";ms<600_000 -> "m3to10";else -> "m10plus" }
    private val BUCKETS=listOf("lt1m","m1to3","m3to10","m10plus","unknown")
    /** Went through to the end: a transcript, or found to hold no speech. */
    fun finished(r:Row)=r.state=="DONE" || r.state=="SKIPPED" && r.reason=="NO_SPEECH"
    fun of(rows:List<Row>,now:Long):org.json.JSONObject {
        val known=rows.filter { (it.lengthMs ?: 0)>0 && (it.bytes ?: 0)>0 }
        val knownMs=known.sumOf { it.lengthMs!! };val knownBytes=known.sumOf { it.bytes!! }
        val bytesPerMs=if(knownMs>0 && knownBytes>0) knownBytes.toDouble()/knownMs else null
        fun length(r:Row):Long? = r.lengthMs?.takeIf { it>0 } ?: if(bytesPerMs==null) null else r.bytes?.takeIf { it>0 }?.let { (it/bytesPerMs).toLong() }
        fun buckets(of:List<Row>)=org.json.JSONObject().apply { BUCKETS.forEach { put(it,0) };of.forEach { r -> val b=bucket(length(r));put(b,optInt(b)+1) } }
        val waiting=rows.filter { it.state=="QUEUED" || it.state=="RUNNING" }
        val done48=rows.filter { finished(it) && it.updatedMs>=now-WINDOW_MS }
        val fails=org.json.JSONObject().apply { rows.filter { it.state=="FAILED" }.groupingBy { PhoneStatus.code(it.reason).ifEmpty { "NONE" } }.eachCount().forEach { (k,v) -> put(k,v) } }
        // A8: calls of the last 48 hours over 100 s that are to be transcribed (skipped ones aside; "no speech" counts as finished).
        val long=rows.filter { r -> (r.callMs ?: 0) in (now-WINDOW_MS)..now && (r.state!="SKIPPED" || r.reason=="NO_SPEECH") && (length(r) ?: 0)>LONG_CALL_MS }
        val longDone=long.count(::finished)
        val o=org.json.JSONObject().put("failReasons",fails).put("queuedByLen",buckets(waiting)).put("doneByLen48h",buckets(done48))
            .put("long48h",org.json.JSONObject().put("total",long.size).put("done",longDone).apply { if(long.isNotEmpty()) put("ratePct",longDone*100/long.size) })
            // v3.5 r15-0: rows the running build sent back to the queue once (counted on the rows, no settings key).
            .put("requeuedFailed",rows.count { it.requeued })
        waiting.mapNotNull { it.queuedMs }.minOrNull()?.let { o.put("oldestWaitMin",((now-it)/60_000).coerceAtLeast(0)) }
        return o
    }
}
