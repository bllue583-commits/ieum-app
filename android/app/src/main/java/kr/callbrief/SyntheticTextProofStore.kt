package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/** Separate sealed store. No recording/Drive/business tables or plaintext fallback. */
internal class SyntheticTextProofStore(context:Context,private val scope:SyntheticTextConnectionScope,
    private val scopeNow:()->SyntheticTextConnectionScope?,private val uuid:()->String={UUID.randomUUID().toString()},private val clock:()->Long=System::currentTimeMillis):AutoCloseable {
    companion object {
        const val DATABASE="ieum-sg-text-proof-v1.db"
        private val PROCESS=UUID.randomUUID().toString()
        private val STORED_STATES=setOf("PREPARED","STARTING","START_CONFIRMATION_REQUIRED","ADMITTED","SAVE_PREPARED","SAVING","READBACK_REQUIRED","RESULT_RECEIVED","WAITING","UNKNOWN","AUTH_HELD","SCOPE_HELD","EXPIRED","CONFLICT","NATIVE_RETURN_UNSUPPORTED","STORE_REVIEW","RESPONSE_REVIEW")
        private val NEED_START=setOf("ADMITTED","SAVE_PREPARED","SAVING","READBACK_REQUIRED","RESULT_RECEIVED","WAITING")
        private val NO_START=setOf("PREPARED","STARTING","START_CONFIRMATION_REQUIRED")
        private val NEED_EXPECTED=setOf("SAVE_PREPARED","SAVING","READBACK_REQUIRED","RESULT_RECEIVED","WAITING")
        private const val SCHEMA="""
CREATE TABLE IF NOT EXISTS sg_proofs(binding_key TEXT NOT NULL,proof_id TEXT NOT NULL,result_suffix TEXT NOT NULL,local_revision INTEGER NOT NULL,body TEXT NOT NULL,claim_token TEXT,claim_process TEXT,PRIMARY KEY(binding_key,proof_id),UNIQUE(binding_key,result_suffix));
-- statement-break
CREATE TABLE IF NOT EXISTS sg_active(binding_key TEXT PRIMARY KEY,proof_id TEXT NOT NULL);
-- statement-break
CREATE TABLE IF NOT EXISTS sg_receipts(receipt_id TEXT PRIMARY KEY,binding_key TEXT NOT NULL,proof_id TEXT NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL,created_ms INTEGER NOT NULL,UNIQUE(binding_key,proof_id,kind,digest));
-- statement-break
CREATE TRIGGER IF NOT EXISTS sg_proof_no_replace BEFORE INSERT ON sg_proofs WHEN EXISTS(SELECT 1 FROM sg_proofs WHERE binding_key=NEW.binding_key AND proof_id=NEW.proof_id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROOF_ID'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS sg_proof_identity BEFORE UPDATE ON sg_proofs WHEN OLD.binding_key!=NEW.binding_key OR OLD.proof_id!=NEW.proof_id OR OLD.result_suffix!=NEW.result_suffix BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROOF_ID'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS sg_receipt_no_update BEFORE UPDATE ON sg_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS sg_receipt_no_delete BEFORE DELETE ON sg_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS sg_receipt_no_replace BEFORE INSERT ON sg_receipts WHEN EXISTS(SELECT 1 FROM sg_receipts WHERE receipt_id=NEW.receipt_id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
"""
    }
    data class View(val proof:String,val suffix:String,val state:String,val start:SyntheticTextGenerationReceipt?,val expectedId:String?,val expectedHash:String?,val result:String?,val scope:SyntheticTextConnectionScope)
    data class Claim(val proof:String,val token:String)
    private val port:AndroidSqlPort
    init { check(Looper.myLooper()!=Looper.getMainLooper()) { "SG_IO_REQUIRED" };check(scopeNow()==scope) { "SG_SCOPE_CHANGED" };port=AndroidSqlPort(context,DATABASE,ByteArrayInputStream(SCHEMA.toByteArray()));port.requireProtectedPersistence() }
    private fun gate(){if(scopeNow()!=scope)throw SyntheticTextFailure("scope_changed")}
    private fun rows(sql:String,vararg args:Any?)=port.query(sql,*args)
    private fun active()=rows("SELECT proof_id FROM sg_active WHERE binding_key=?",scope.key).singleOrNull()?.get("proof_id") as? String
    private fun row(proof:String)=rows("SELECT * FROM sg_proofs WHERE binding_key=? AND proof_id=?",scope.key,proof).singleOrNull() ?: throw SyntheticTextFailure("not_found")
    private fun checkedBody(row:Map<String,Any?>):Map<String,Any?> {
        // Parse original stored bytes before JSONObject can coerce tokens or
        // rewrite a decimal/exponent into a superficially valid integer.
        val value=SyntheticTextContract.obj(StrictJson.parse((row["body"] as String).toByteArray()))
        check(value["job"]==SyntheticTextContract.JOB && value["sample"]==SyntheticTextContract.SAMPLE && value["contract"]=="ieum.synthetic_text_generation.v1" && value["project"]==scope.project && value["owner"]==scope.owner && value["session"]==scope.session && value["epoch"]==scope.epoch && value["origin"]==scope.origin.name) { "SG_STORAGE_INTEGRITY" }
        SyntheticTextContract.uuid(row["proof_id"].toString());SyntheticTextContract.uuid(row["result_suffix"].toString())
        return value
    }
    private fun body(row:Map<String,Any?>)=JSONObject(checkedBody(row))
    /** Read-only integrity checks. A corrupt row remains sealed and untouched;
     * its cached label cannot authorize a result or a new generation. */
    private fun immutable(proof:String,kind:String):List<Map<String,Any?>> =
        rows("SELECT payload,digest FROM sg_receipts WHERE binding_key=? AND proof_id=? AND kind=?",scope.key,proof,kind).map { row ->
            val payload=row["payload"] as String
            check(SyntheticTextContract.hash(payload.toByteArray())==row["digest"])
            val value=SyntheticTextContract.obj(StrictJson.parse(payload.toByteArray()))
            check(StrictJson.stringify(value)==payload)
            value
        }
    private fun nullableString(value:Map<String,Any?>,key:String):String? = value[key]?.let {
        check(it is String && it.isNotEmpty());it as String
    }
    private fun view(r:Map<String,Any?>):View = try {
        val cached=checkedBody(r)
        val state=SyntheticTextContract.string(cached,"state");check(state in STORED_STATES)
        val start=cached["start"]?.let { SyntheticTextContract.restored(SyntheticTextContract.obj(it)) }
        val proof=r["proof_id"].toString();val suffix=r["result_suffix"].toString()
        val id=nullableString(cached,"expected_id");val hash=nullableString(cached,"expected_hash");val result=nullableString(cached,"result")
        check((id==null)==(hash==null));if(hash!=null)check(Regex("^[0-9a-f]{64}$").matches(hash))
        if(state in NEED_START)check(start!=null)
        if(state in NO_START)check(start==null)
        if(state in NEED_EXPECTED)check(id!=null && hash!=null)
        if(state=="RESULT_RECEIVED")check(result!=null)
        val admissions=immutable(proof,"START")
        if(start==null) {
            check(id==null && result==null && admissions.isEmpty() && immutable(proof,"RESULT_READBACK").isEmpty())
        } else {
            check(start.proof==proof && admissions.isNotEmpty())
            val checked=admissions.map { SyntheticTextContract.start(it,proof) }
            // Every immutable START must agree on the original identity/time tuple.
            // A cached queued0 -> ready1 advance requires a verified readback below.
            check(checked.all { it.generation==start.generation && it.createdAt==start.createdAt && it.expiresAt==start.expiresAt && it.revision<=start.revision })
            if(start.revision==1L && checked.all { it.revision==0L })check(result!=null)
            if(id!=null)check(id==GenerationResultId.make(start.generation,suffix))
        }
        if(result!=null) {
            check(start!=null && start.revision==1L && start.status=="result_ready" && id!=null && hash!=null)
            val decoded=SyntheticTextContract.validatedReadback(StrictJson.parse(result.toByteArray()))
            check(SyntheticTextContract.fingerprint(decoded)==hash)
            val readbacks=immutable(proof,"RESULT_READBACK");check(readbacks.isNotEmpty())
            check(readbacks.all {
                val saved=SyntheticTextContract.result(it,start,id,hash)
                saved!=null && StrictJson.stringify(saved)==result
            })
        }
        View(proof,suffix,state,start,id,hash,result,scope)
    } catch(_:Exception) { throw SyntheticTextFailure("storage_integrity_error") }
    fun current():View?=port.transaction { gate();active()?.let { view(row(it)) } }
    fun get(proof:String):View=port.transaction { gate();view(row(proof)) }
    fun explicitStart():View=port.transaction {
        gate();active()?.let { key ->val old=row(key);if(old["claim_token"]!=null&&old["claim_process"]==PROCESS)throw SyntheticTextFailure("already_running") }
        val proof=uuid();val suffix=uuid();SyntheticTextContract.uuid(proof);SyntheticTextContract.uuid(suffix)
        val b=JSONObject().put("contract","ieum.synthetic_text_generation.v1").put("job",SyntheticTextContract.JOB).put("sample",SyntheticTextContract.SAMPLE).put("project",scope.project).put("owner",scope.owner).put("session",scope.session).put("epoch",scope.epoch).put("origin",scope.origin.name).put("state","PREPARED")
        port.execute("INSERT INTO sg_proofs VALUES(?,?,?,0,?,NULL,NULL)",scope.key,proof,suffix,b.toString())
        if(active()==null)port.execute("INSERT INTO sg_active VALUES(?,?)",scope.key,proof) else port.execute("UPDATE sg_active SET proof_id=? WHERE binding_key=?",proof,scope.key)
        view(row(proof))
    }
    fun startAndClaim():Pair<View,Claim> = port.transaction { val v=explicitStart();v to claim(v.proof) }
    fun claim(proof:String):Claim=port.transaction {
        gate();check(active()==proof) { "SG_SELECTED_PROOF_CHANGED" };val r=row(proof)
        if(r["claim_token"]!=null&&r["claim_process"]==PROCESS)throw SyntheticTextFailure("already_running")
        val token=UUID.randomUUID().toString();port.execute("UPDATE sg_proofs SET claim_token=?,claim_process=? WHERE binding_key=? AND proof_id=?",token,PROCESS,scope.key,proof);Claim(proof,token)
    }
    private fun update(claim:Claim,state:String,action:(JSONObject)->Unit={}):View=port.transaction {
        gate();check(active()==claim.proof) { "SG_SELECTED_PROOF_CHANGED" };val r=row(claim.proof);check(r["claim_token"]==claim.token && r["claim_process"]==PROCESS) { "SG_CLAIM_CHANGED" }
        val b=body(r);action(b);b.put("state",state);gate()
        port.execute("UPDATE sg_proofs SET body=?,local_revision=local_revision+1 WHERE binding_key=? AND proof_id=? AND claim_token=?",b.toString(),scope.key,claim.proof,claim.token);view(row(claim.proof))
    }
    fun state(claim:Claim,state:String)=update(claim,state)
    fun started(claim:Claim,start:SyntheticTextGenerationReceipt,value:Map<String,Any?>):View=port.transaction {
        val old=get(claim.proof).start;if(old!=null)check(old.generation==start.generation && old.createdAt==start.createdAt && old.expiresAt==start.expiresAt && start.revision>=old.revision) { "SG_GENERATION_CHANGED" }
        check(SyntheticTextContract.start(value,claim.proof)==start)
        receipt(claim,"START",value)
        update(claim,"ADMITTED") { it.put("start",JSONObject(start.json())) }
    }
    fun expected(claim:Claim,id:String,hash:String):View=update(claim,"SAVE_PREPARED") { b ->
        val v=get(claim.proof);check(v.start!=null && id==GenerationResultId.make(v.start.generation,v.suffix));check(v.expectedId==null || v.expectedId==id&&v.expectedHash==hash);b.put("expected_id",id).put("expected_hash",hash)
    }
    fun receipt(claim:Claim,kind:String,value:Map<String,Any?>)=port.transaction {
        gate();val r=row(claim.proof);check(r["claim_token"]==claim.token && active()==claim.proof)
        val payload=StrictJson.stringify(value);val hash=SyntheticTextContract.hash(payload.toByteArray())
        if(rows("SELECT receipt_id FROM sg_receipts WHERE binding_key=? AND proof_id=? AND kind=? AND digest=?",scope.key,claim.proof,kind,hash).isEmpty())
            port.execute("INSERT INTO sg_receipts VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),scope.key,claim.proof,kind,payload,hash,clock())
    }
    fun received(claim:Claim,value:Map<String,Any?>,result:Map<String,Any?>):View=port.transaction {
        val v=get(claim.proof);check(v.start!=null && v.expectedId!=null && v.expectedHash!=null)
        check(SyntheticTextContract.result(value,v.start,v.expectedId,v.expectedHash)!=null)
        receipt(claim,"RESULT_READBACK",value)
        update(claim,"RESULT_RECEIVED") { it.put("result",StrictJson.stringify(result));it.put("start",JSONObject(v.start!!.copy(revision=1,status="result_ready").json())) }
    }
    /** Claim cleanup uses its unguessable local ticket. It never accepts content after
     * an owner/session change and never deletes a receipt or prior readback. */
    fun finish(claim:Claim,state:String?=null)=port.transaction {
        val r=row(claim.proof);if(r["claim_token"]!=claim.token)return@transaction
        val b=body(r);if(state!=null)b.put("state",state)
        port.execute("UPDATE sg_proofs SET body=?,local_revision=local_revision+1,claim_token=NULL,claim_process=NULL WHERE binding_key=? AND proof_id=? AND claim_token=?",b.toString(),scope.key,claim.proof,claim.token)
    }
    fun receiptCount()=port.transaction { gate();(rows("SELECT count(*) AS n FROM sg_receipts WHERE binding_key=?",scope.key).single()["n"] as Number).toInt() }
    override fun close()=port.close()
}
