package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.co.callsummary.core.android.AndroidSqlPort

/** Job ledger for on-device transcription, in the same protected (Keystore-sealed) local database as the
 * recordings it points at. One row per recording version; states QUEUED|RUNNING|DONE|FAILED|SKIPPED. */
internal class CallTranscriptionStore(context:Context,private val now:()->Long=System::currentTimeMillis):AutoCloseable {
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
                // Encrypted originals deleted by GeneralLocalStore.pruneVault (#39); a recording whose blob is gone is not offered again.
                db.execute("CREATE TABLE IF NOT EXISTS vault_pruned(ciphertext_sha256 TEXT PRIMARY KEY,at_ms INTEGER NOT NULL)")
                // Recordings from before 10/1 left queued by an older build (r7-1): one statement, whatever the offer pass does.
                db.execute(SKIP_BEFORE_START_SQL,now(),FirstPass.FROM_MS);null
            }
        } catch(e:Exception){db.close();throw e}
    }

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

    /** The owner's current recording versions with encrypted audio, newest first. */
    fun candidates():List<Candidate> = db.query("$select WHERE r.owner_key=? AND r.presence='PRESENT' AND gone.ciphertext_sha256 IS NULL ORDER BY coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0) DESC",
        GeneralLocalStore.OWNER).map(::candidate)

    /** Idempotent: new versions are inserted; rows that have not started or finished follow the current eligibility.
     * "No speech" was found by transcribing, not by eligibility, so it stays (it was queued again on every pass before). */
    fun offer(c:Candidate,eligible:Boolean,reason:String)=tx {
        val state=if(eligible)"QUEUED" else "SKIPPED"
        db.execute("INSERT OR IGNORE INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,reason,updated_ms) VALUES(?,?,?,?,?,?)",
            c.key.recordingId,c.key.sourceVersion,c.key.audioSha,state,reason,now())
        db.execute("UPDATE call_transcription_jobs SET state=?,reason=?,updated_ms=? WHERE recording_id=? AND source_version=? AND audio_sha=? AND state IN('QUEUED','SKIPPED') AND (state!=? OR reason!=?) AND NOT (state='SKIPPED' AND reason='NO_SPEECH')",
            state,reason,now(),c.key.recordingId,c.key.sourceVersion,c.key.audioSha,state,reason);null
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
    fun recoverRunning(maxAttempts:Int,staleMs:Long=STALE_RUNNING_MS)=db.transaction {
        val limit=now()-staleMs
        db.execute("UPDATE call_transcription_jobs SET state='FAILED',reason='INTERRUPTED',updated_ms=? WHERE state='RUNNING' AND attempts>=? AND updated_ms<?",now(),maxAttempts,limit)
        db.execute("UPDATE call_transcription_jobs SET state='QUEUED',reason='INTERRUPTED',updated_ms=? WHERE state='RUNNING' AND updated_ms<?",now(),limit);null
    }

    /** Newest first; with [oldBefore] (처음 정리) recordings before it come after all newer ones, oldest first, so the
     * old ones reach the analysis in time order. */
    fun nextQueued(oldBefore:Long?=null):Job? = db.query("""SELECT j.state,j.reason,j.attempts,x.* FROM call_transcription_jobs j JOIN ($select WHERE r.owner_key=? AND gone.ciphertext_sha256 IS NULL) x
        ON x.recording_id=j.recording_id AND x.source_version=j.source_version AND x.audio_sha256=j.audio_sha
        WHERE j.state='QUEUED' ORDER BY CASE WHEN coalesce(x.original_started_at_ms,x.modified_at_ms,0)<CAST(? AS INTEGER) THEN 1 ELSE 0 END,
        CASE WHEN coalesce(x.original_started_at_ms,x.modified_at_ms,0)<CAST(? AS INTEGER) THEN coalesce(x.original_started_at_ms,x.modified_at_ms,0) ELSE -coalesce(x.original_started_at_ms,0) END,
        j.updated_ms LIMIT 1""".trimIndent(),GeneralLocalStore.OWNER,oldBefore ?: Long.MIN_VALUE,oldBefore ?: Long.MIN_VALUE)
        .singleOrNull()?.let{Job(candidate(it),it["state"].toString(),it["reason"].toString(),(it["attempts"] as Number).toInt())}

    /** Finished pieces made with piece length [chunkMs]. Rows of any other length (or unknown, NULL) are deleted, with the stop counters
     * that were counted against those piece numbers: the same id then means a different stretch of audio. */
    fun parts(k:Key,chunkMs:Long=PipelineAudioMath.MAX_CHUNK_MS):Map<Int,List<CallTranscript.Segment>> {
        val stale=db.query("SELECT 1 FROM call_transcription_parts WHERE recording_id=? AND source_version=? AND audio_sha=? AND (chunk_ms IS NULL OR chunk_ms!=?) LIMIT 1",
            k.recordingId,k.sourceVersion,k.audioSha,chunkMs).isNotEmpty()
        if(stale)db.transaction {
            db.execute("DELETE FROM call_transcription_parts WHERE recording_id=? AND source_version=? AND audio_sha=? AND (chunk_ms IS NULL OR chunk_ms!=?)",k.recordingId,k.sourceVersion,k.audioSha,chunkMs)
            db.execute("DELETE FROM call_transcription_stalls WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha);null
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
        db.execute("DELETE FROM call_transcription_stalls WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha);null
    }
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

    fun finish(k:Key,state:String,reason:String,refundAttempt:Boolean=false)=db.execute(
        "UPDATE call_transcription_jobs SET state=?,reason=?,attempts=attempts-?,updated_ms=? WHERE recording_id=? AND source_version=? AND audio_sha=?",
        state,reason,if(refundAttempt)1 else 0,now(),k.recordingId,k.sourceVersion,k.audioSha)

    fun attempts(k:Key):Int = db.query("SELECT attempts FROM call_transcription_jobs WHERE recording_id=? AND source_version=? AND audio_sha=?",k.recordingId,k.sourceVersion,k.audioSha)
        .singleOrNull()?.let{(it["attempts"] as Number).toInt()} ?: 0

    /** All rows as (key, state, reason, attempts); for status display and tests. */
    fun rows():List<Triple<Key,String,Pair<String,Int>>> = db.query("SELECT * FROM call_transcription_jobs ORDER BY updated_ms,recording_id").map{
        Triple(Key(it["recording_id"].toString(),(it["source_version"] as Number).toLong(),it["audio_sha"].toString()),it["state"].toString(),
            it["reason"].toString() to (it["attempts"] as Number).toInt())
    }

    override fun close()=db.close()
    companion object {
        const val STALE_RUNNING_MS=11*60_000L
        /** Queued recordings whose time (start or filename time, else the file's modified time) is before 10/1 become
         * SKIPPED with BEFORE_START, the reason the offer pass gives them. Arguments: updated_ms, [FirstPass.FROM_MS]. */
        const val SKIP_BEFORE_START_SQL="""UPDATE call_transcription_jobs SET state='SKIPPED',reason='BEFORE_START',updated_ms=? WHERE state='QUEUED' AND recording_id IN(
            SELECT r.recording_id FROM recordings r LEFT JOIN local_files l ON l.recording_id=r.recording_id
            WHERE COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)>0
            AND COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms,0)<CAST(? AS INTEGER))"""
    }
}
