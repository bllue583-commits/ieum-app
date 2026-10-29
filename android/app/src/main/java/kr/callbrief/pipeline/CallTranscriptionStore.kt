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
                db.execute("""CREATE TABLE IF NOT EXISTS call_transcription_jobs(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,
                    audio_sha TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('QUEUED','RUNNING','DONE','FAILED','SKIPPED')),
                    reason TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL,
                    PRIMARY KEY(recording_id,source_version,audio_sha))""");null
            }
        } catch(e:Exception){db.close();throw e}
    }

    private val select="""SELECT r.recording_id,r.source_version,v.audio_sha256,r.display_name,r.mime_type,r.original_started_at_ms,
        r.byte_count,r.modified_at_ms,l.actual_uri,a.archived,g.active AS grant_active
        FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        JOIN local_files l ON l.recording_id=r.recording_id JOIN local_visibility a ON a.recording_id=r.recording_id
        JOIN folder_grants g ON g.grant_id=r.grant_id
        JOIN vault2_refs q ON q.recording_id=r.recording_id AND q.source_version=r.source_version AND q.owner_key=r.owner_key AND q.plaintext_sha256=v.audio_sha256"""
    private fun candidate(r:Map<String,Any?>)=Candidate(Key(r["recording_id"].toString(),(r["source_version"] as Number).toLong(),r["audio_sha256"].toString()),
        r["display_name"].toString(),r["actual_uri"].toString(),r["mime_type"].toString(),(r["original_started_at_ms"] as Number?)?.toLong(),
        (r["archived"] as Number).toInt()==1,(r["grant_active"] as Number).toInt()==1,
        (r["byte_count"] as Number?)?.toLong() ?: 0,(r["modified_at_ms"] as Number?)?.toLong() ?: 0)

    /** The owner's current recording versions with encrypted audio, newest first. */
    fun candidates():List<Candidate> = db.query("$select WHERE r.owner_key=? AND r.presence='PRESENT' ORDER BY coalesce(r.original_started_at_ms,r.modified_at_ms,0) DESC",
        GeneralLocalStore.OWNER).map(::candidate)

    /** Idempotent: new versions are inserted; rows that have not started or finished follow the current eligibility. */
    fun offer(c:Candidate,eligible:Boolean,reason:String)=db.transaction {
        val state=if(eligible)"QUEUED" else "SKIPPED"
        db.execute("INSERT OR IGNORE INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,reason,updated_ms) VALUES(?,?,?,?,?,?)",
            c.key.recordingId,c.key.sourceVersion,c.key.audioSha,state,reason,now())
        db.execute("UPDATE call_transcription_jobs SET state=?,reason=?,updated_ms=? WHERE recording_id=? AND source_version=? AND audio_sha=? AND state IN('QUEUED','SKIPPED') AND (state!=? OR reason!=?)",
            state,reason,now(),c.key.recordingId,c.key.sourceVersion,c.key.audioSha,state,reason);null
    }

    /** Queued rows whose recording moved on to a newer version (or vanished) are skipped. */
    fun skipSuperseded()=db.execute("""UPDATE call_transcription_jobs SET state='SKIPPED',reason='NOT_CURRENT',updated_ms=? WHERE state='QUEUED' AND NOT EXISTS(
        SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
        WHERE r.recording_id=call_transcription_jobs.recording_id AND r.source_version=call_transcription_jobs.source_version AND v.audio_sha256=call_transcription_jobs.audio_sha)""",now())

    /** Rows left RUNNING by a process that died. The attempt stays counted, so a crash loop ends in FAILED. */
    fun recoverRunning(maxAttempts:Int)=db.transaction {
        db.execute("UPDATE call_transcription_jobs SET state='FAILED',reason='INTERRUPTED',updated_ms=? WHERE state='RUNNING' AND attempts>=?",now(),maxAttempts)
        db.execute("UPDATE call_transcription_jobs SET state='QUEUED',reason='INTERRUPTED',updated_ms=? WHERE state='RUNNING'",now());null
    }

    fun nextQueued():Job? = db.query("""SELECT j.state,j.reason,j.attempts,x.* FROM call_transcription_jobs j JOIN ($select WHERE r.owner_key=?) x
        ON x.recording_id=j.recording_id AND x.source_version=j.source_version AND x.audio_sha256=j.audio_sha
        WHERE j.state='QUEUED' ORDER BY coalesce(x.original_started_at_ms,0) DESC,j.updated_ms LIMIT 1""".trimIndent(),GeneralLocalStore.OWNER)
        .singleOrNull()?.let{Job(candidate(it),it["state"].toString(),it["reason"].toString(),(it["attempts"] as Number).toInt())}

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
}
