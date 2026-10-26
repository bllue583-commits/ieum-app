package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import java.util.UUID

/** Separate, AEAD sealed queue; no shared workflow SQL migration or canonical writes. */
internal class AstraAudioJournal(context:Context,private val scope:String):AutoCloseable {
    private val store=ProtectedDatabase.open(context,"astra-audio-v1.db",SCHEMA)
    private val db=store.database()
    data class Record(val id:String,val manifest:ByteArray,val inputs:List<AstraAudioInput>,val state:String,
        val receipt:String,val digest:String,val attempts:Int,val next:Long,val claim:String)
    init { store.transaction {
        db.execSQL(SCHEMA)
        db.execSQL("CREATE TRIGGER IF NOT EXISTS astra_manifest_immutable BEFORE UPDATE OF scope,id,manifest,inputs ON astra_jobs BEGIN SELECT RAISE(ABORT,'ASTRA_IMMUTABLE');END")
        db.execSQL("UPDATE astra_jobs SET state=CASE WHEN state='DISPATCHING' THEN 'UNKNOWN' ELSE state END,claim='' WHERE scope=? AND claim!='' AND claim!=?",arrayOf(scope,PROCESS))
        null
    } }
    fun load(id:String):Record = ProtectedDatabase.read(db) { db.rawQuery("SELECT manifest,inputs,state,receipt,digest,attempts,next_ms,claim FROM astra_jobs WHERE scope=? AND id=?",arrayOf(scope,id)).use { c ->
        check(c.moveToFirst()) { "ASTRA_UNKNOWN_JOB" }
        val manifest=c.getBlob(0);check(AstraAudioContract.sha(manifest)==id) { "ASTRA_JOURNAL_INTEGRITY" }
        val inputs=(StrictJson.parse(c.getBlob(1)) as List<*>).map{AstraAudioInput.restore(AstraAudioContract.obj(it))}
        check(AstraAudioContract.manifest(scope,inputs).contentEquals(manifest)) { "ASTRA_JOURNAL_BINDING" }
        Record(id,manifest,inputs,c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getLong(6),c.getString(7))
    } }
    fun prepare(inputs:List<AstraAudioInput>):String=store.transaction {
        val bytes=AstraAudioContract.manifest(scope,inputs);val id=AstraAudioContract.sha(bytes)
        if(db.rawQuery("SELECT id FROM astra_jobs WHERE scope=? AND id=?",arrayOf(scope,id)).use{it.moveToFirst()}) {
            check(StrictJson.bytes(load(id).inputs.map{it.local()}).contentEquals(StrictJson.bytes(inputs.sortedBy{it.job}.map{it.local()}))) { "ASTRA_LOCAL_SOURCE_CHANGED" }
        } else {
            // One existing automation job cannot silently acquire a second dispatch/manifest.
            for(old in ids())check(load(old).inputs.none{a->inputs.any{it.job==a.job}}) { "ASTRA_JOB_ALREADY_ENROLLED" }
            db.execSQL("INSERT INTO astra_jobs VALUES(?,?,?,?,'PREPARED','','',0,0,'')",arrayOf(scope,id,bytes,StrictJson.bytes(inputs.sortedBy{it.job}.map{it.local()})))
        }
        id
    }
    fun ids():List<String> = ProtectedDatabase.read(db) { db.rawQuery("SELECT id FROM astra_jobs WHERE scope=? ORDER BY id",arrayOf(scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}} }
    fun find(job:String):String?=ids().singleOrNull{id->load(id).inputs.any{it.job==job}}
    fun claim(id:String,now:Long):Boolean=store.transaction {
        val r=load(id)
        if(r.claim.isNotBlank() || r.next>now || r.state in setOf("CANCELLED","REJECTED"))false
        else {db.execSQL("UPDATE astra_jobs SET claim=? WHERE scope=? AND id=?",arrayOf(PROCESS,scope,id));true}
    }
    fun release(id:String)=store.transaction {db.execSQL("UPDATE astra_jobs SET claim='' WHERE scope=? AND id=? AND claim=?",arrayOf(scope,id,PROCESS));null}
    fun state(id:String,state:String,receipt:String?=null,digest:String?=null,next:Long=0,attempt:Boolean=false)=store.transaction {
        val old=load(id)
        check(old.claim==PROCESS && old.state !in setOf("CANCELLED","CANCEL_REQUESTED")) { "ASTRA_CANCELLED_OR_CLAIM_LOST" }
        check(receipt==null || old.receipt.isEmpty() || receipt==old.receipt) { "ASTRA_RECEIPT_CHANGED" }
        check(digest==null || old.digest.isEmpty() || digest==old.digest) { "ASTRA_RESULT_CONFLICT" }
        db.execSQL("UPDATE astra_jobs SET state=?,receipt=?,digest=?,attempts=?,next_ms=? WHERE scope=? AND id=?",arrayOf(state,receipt ?: old.receipt,digest ?: old.digest,old.attempts+if(attempt)1 else 0,next,scope,id));null
    }
    fun cancel(id:String)=store.transaction {
        val r=load(id);if(r.state in setOf("CANCELLED","CANCEL_REQUESTED"))return@transaction null
        val state=if(r.state in setOf("PREPARED","WAITING_PROVIDER","OFFLINE"))"CANCELLED" else "CANCEL_REQUESTED"
        db.execSQL("UPDATE astra_jobs SET state=?,next_ms=0 WHERE scope=? AND id=?",arrayOf(state,scope,id));null
    }
    fun cancelAcknowledged(id:String)=store.transaction {
        check(load(id).state=="CANCEL_REQUESTED");db.execSQL("UPDATE astra_jobs SET state='CANCELLED' WHERE scope=? AND id=?",arrayOf(scope,id));null
    }
    override fun close()=store.close()
    companion object {
        private const val SCHEMA="CREATE TABLE IF NOT EXISTS astra_jobs(scope TEXT NOT NULL,id TEXT NOT NULL,manifest BLOB NOT NULL,inputs BLOB NOT NULL,state TEXT NOT NULL,receipt TEXT NOT NULL,digest TEXT NOT NULL,attempts INTEGER NOT NULL,next_ms INTEGER NOT NULL,claim TEXT NOT NULL,PRIMARY KEY(scope,id))"
        private val PROCESS=UUID.randomUUID().toString() }
}
