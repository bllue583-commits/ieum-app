package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import java.util.UUID

/** Two separately keyed stages with no automatic retry of an uncertain SIWC request.
 * No server idempotency, response retrieval, remote cancellation or exactly-once billing is assumed.
 * Frozen six-field analysis and context schemas stay owned by their existing validators. */
internal class SiwcInferenceJobs(context:Context,private val scope:String,
    private val port:SiwcInferencePort,private val current:()->Boolean):AutoCloseable {
    private val store=ProtectedDatabase.open(context,"siwc-inference-v1.db",SCHEMA)
    private val db=store.database()
    init {store.transaction {
        db.execSQL("UPDATE siwc_stages SET state='UNKNOWN',reason='SIWC_INTERRUPTED_PROCESS' WHERE scope=? AND state='RUNNING' AND process!=?",arrayOf(scope,PROCESS));null
    }}
    data class Outcome(val state:String,val completion:SiwcAstraProtocol.Completion?=null,val reason:String="")
    fun state(id:String)=ProtectedDatabase.read(db){db.rawQuery("SELECT state FROM siwc_stages WHERE scope=? AND id=?",arrayOf(scope,id)).use{if(it.moveToFirst())it.getString(0) else "NOT_PREPARED"}}
    private fun reason(id:String)=ProtectedDatabase.read(db){db.rawQuery("SELECT reason FROM siwc_stages WHERE scope=? AND id=?",arrayOf(scope,id)).use{if(it.moveToFirst())it.getString(0) else ""}}
    private fun saved(id:String):SiwcAstraProtocol.Completion=ProtectedDatabase.read(db) {
        db.rawQuery("SELECT result FROM siwc_stages WHERE scope=? AND id=? AND state='COMPLETE'",arrayOf(scope,id)).use { c ->
            check(c.moveToFirst());val r=AstraAudioContract.obj(StrictJson.parseReadOnlyEnvelope(c.getBlob(0)))
            SiwcAstraProtocol.Completion(r["responseId"] as String,r["actualModel"] as String,r["effectiveEffort"] as String,r["output"] as String,r["rawSha256"] as String)
        }
    }
    /** Local committed observation only; never performs a catalog or inference request. */
    fun completed(request:SiwcAstraProtocol.Invocation,validate:(String)->Unit):SiwcAstraProtocol.Completion {
        check(request.scope==scope && current() && state(request.key())=="COMPLETE") { "SIWC_COMPLETION_NOT_COMMITTED" }
        return saved(request.key()).also { validate(it.output);check(current()) { "SIWC_SCOPE_CHANGED" } }
    }
    /** Builds, but does not run, a new attempt. UI must explain possible prior usage and obtain
     * approval for this new key before the HTTP grant allows it. Original attempt is retained. */
    fun explicitRetry(request:SiwcAstraProtocol.Invocation):SiwcAstraProtocol.Invocation {
        check(request.scope==scope && current() && state(request.key()) in setOf("UNKNOWN","HELD","REJECTED","CANCELLED_REMOTE_UNKNOWN")) { "SIWC_RETRY_NOT_ALLOWED" }
        return request.nextAttempt()
    }
    /** validator must validate the unchanged stage schema + source quotes/bindings before returning.
     * A model-authored status field is not a validator. Completion receipt contains observed API data. */
    fun run(request:SiwcAstraProtocol.Invocation,validate:(String)->Unit):Outcome {
        check(request.scope==scope && current()) { "SIWC_SCOPE_CHANGED" }
        val id=request.key()
        val old=store.transaction {
            val state=state(id)
            if(state=="NOT_PREPARED")db.execSQL("INSERT INTO siwc_stages VALUES(?,?,?,?,'PREPARED','',NULL,'')",arrayOf(scope,id,request.stage.name,AstraAudioContract.sha(request.body())))
            state(id)
        }
        if(old=="COMPLETE")return Outcome(old,saved(id).also{validate(it.output);check(current()) { "SIWC_SCOPE_CHANGED" }})
        if(old!="PREPARED")return Outcome(old,reason=reason(id))
        // Catalog lookup cannot itself prove entitlement. It is read-only and precedes the billed attempt.
        if(request.model !in port.models(scope))return Outcome("MODEL_UNAVAILABLE",reason="SIWC_MODEL_UNAVAILABLE")
        val claimed=store.transaction {
            check(current()) { "SIWC_SCOPE_CHANGED" }
            if(state(id)!="PREPARED")false else {db.execSQL("UPDATE siwc_stages SET state='RUNNING',process=? WHERE scope=? AND id=?",arrayOf(PROCESS,scope,id));true}
        }
        if(!claimed)return Outcome(state(id))
        var completedObserved=false
        try {
            val response=port.invoke(request){!current() || state(id)=="CANCELLED_REMOTE_UNKNOWN"}
            completedObserved=true
            validate(response.output)
            check(response.actualModel==request.model && (response.effectiveEffort=="NOT_REPORTED" || response.effectiveEffort==request.effort)) { "SIWC_OBSERVATION_CHANGED" }
            val bytes=StrictJson.bytes(mapOf("responseId" to response.responseId,"actualModel" to response.actualModel,
                "effectiveEffort" to response.effectiveEffort,"output" to response.output,"rawSha256" to response.rawSha256))
            store.transaction {
                check(current() && state(id)=="RUNNING") { "SIWC_CANCELLED_OR_SCOPE_CHANGED" }
                db.execSQL("UPDATE siwc_stages SET state='COMPLETE',result=? WHERE scope=? AND id=?",arrayOf(bytes,scope,id));null
            }
            return Outcome("COMPLETE",response)
        } catch(e:Exception) {
            store.transaction {
                if(state(id)=="RUNNING")db.execSQL("UPDATE siwc_stages SET state=?,reason=? WHERE scope=? AND id=?",arrayOf(
                    if(completedObserved)"REJECTED" else if(e is SiwcAstraProtocol.Failure && e.code!="SIWC_COMPLETION_UNKNOWN")"HELD" else "UNKNOWN",if(completedObserved)"SIWC_COMPLETED_RESULT_REJECTED" else (e as? SiwcAstraProtocol.Failure)?.code ?: "SIWC_TRANSPORT_OR_COMPLETION_UNKNOWN",scope,id));null
            }
            return Outcome(state(id),reason=reason(id))
        }
    }
    /** Context is a second invocation, bound to an actually committed analysis output and snapshot.
     * The context model/effort must be supplied explicitly; this code makes no model substitution. */
    fun context(analysis:SiwcAstraProtocol.Invocation,snapshot:Map<String,Any?>,model:String,effort:String,instructions:String):SiwcAstraProtocol.Invocation {
        check(analysis.scope==scope && analysis.stage==SiwcAstraProtocol.Stage.ANALYSIS && state(analysis.key())=="COMPLETE" && current()) { "SIWC_ANALYSIS_NOT_COMMITTED" }
        val output=AstraAudioContract.obj(StrictJson.parse(saved(analysis.key()).output.toByteArray(Charsets.UTF_8)))
        return SiwcAstraProtocol.Invocation(scope,SiwcAstraProtocol.Stage.CONTEXT,model,effort,analysis.sourceManifestSha,
            mapOf("analysisJobId" to analysis.key(),"analysis" to output,"analysisOutputSha256" to AstraAudioContract.sha(StrictJson.bytes(output)),
                "approvedSnapshot" to snapshot,"approvedSnapshotSha256" to AstraAudioContract.sha(StrictJson.bytes(snapshot))),instructions)
    }
    fun cancel(id:String)=store.transaction {
        if(state(id)!="NOT_PREPARED")db.execSQL("UPDATE siwc_stages SET state='CANCELLED_REMOTE_UNKNOWN',reason='SIWC_LOCAL_CANCEL_REMOTE_NOT_CONFIRMED' WHERE scope=? AND id=?",arrayOf(scope,id));null
    }
    override fun close()=store.close()
    companion object {
        private val PROCESS=UUID.randomUUID().toString()
        private const val SCHEMA="CREATE TABLE siwc_stages(scope TEXT NOT NULL,id TEXT NOT NULL,stage TEXT NOT NULL,request_sha TEXT NOT NULL,state TEXT NOT NULL,process TEXT NOT NULL,result BLOB,reason TEXT NOT NULL,PRIMARY KEY(scope,id))"
    }
}
