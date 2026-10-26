package kr.callbrief

import android.content.ContentValues
import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Separate authority from an unchanged read-only v2 payload. Trust is the already approved
 * HTTPS worker/credential plus observed stage receipts and a deliberately extractive policy.
 * This is not independent model/voice accuracy attestation and never fabricates user review. */
internal object VerifiedAutomaticResults {
    const val POLICY="IEUM_EXTRACTIVE_SAFE_DERIVED_V1"
    const val SCHEMA="ieum.automation.execution-evidence.v1"
    const val ORIGIN="AUTHENTICATED_WORKER_EXTRACTIVE_SAFE_DERIVED_V1"
    private val bindings=listOf("jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest")
    private fun obj(x:Any?)=StrictJson.`object`(x)
    private fun parse(bytes:ByteArray)=obj(StrictJson.parseReadOnlyEnvelope(bytes))
    private fun equal(a:Any?,b:Any?)=StrictJson.bytes(a).contentEquals(StrictJson.bytes(b))
    fun advertised(capabilities:Map<String,Any?>)=capabilities["automaticUsePolicy"]==POLICY && capabilities["executionEvidenceSchema"]==SCHEMA
    private fun analysis(x:Map<String,Any?>)=mapOf("summary" to x["summary"],"cumulativeSummary" to x["cumulativeSummary"],"tasks" to x["tasks"])
    private fun semantic(result:Map<String,Any?>,business:Map<String,Any?>):Boolean=runCatching {
        val stt=obj(result["stt"]);val a=obj(result["analysis"]);val raw=stt["text"] as String;val summary=a["summary"] as String
        check(raw.contains(summary) && summary.isNotBlank())
        val prior=business["proposedSummary"] as String
        check(a["cumulativeSummary"]==(if(prior.isEmpty())"" else prior+"\n")+summary)
        for(value in a["tasks"] as List<*>) {val t=obj(value);check(t["taskId"]==null && t["title"]==t["quote"] && t["assignee"]=="확인 필요" && t["due"]==null && t["status"]=="needs_confirmation")}
        true
    }.getOrDefault(false)
    private data class Checks(val source:Boolean,val execution:Boolean,val semantics:Boolean,val privacy:Boolean,val automatic:Boolean,val evidenceRef:String)
    private fun checkProof(scope:DriveALocalScope,c:AutomationApiConfiguration,enrollment:ByteArray,raw:ByteArray,proof:ByteArray):Checks {
        c.validate(scope);check(proof.size in 1..131072){"AUTO_PROOF_SIZE"}
        val e=parse(proof);check(e.keys==setOf("schema","algorithm","body","signature") && e["schema"]=="ieum.automation.signed-execution-evidence.v1" && e["algorithm"]=="HMAC-SHA256"){"AUTO_PROOF_SCHEMA"}
        val body=obj(e["body"]);val signature=e["signature"] as String;check(signature.matches(Regex("[0-9a-f]{64}"))){"AUTO_PROOF_SIGNATURE"}
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(c.bearerCredential.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        val expected=mac.doFinal("IEUM_AUTOMATIC_USE_V1\n".toByteArray(Charsets.UTF_8)+StrictJson.bytes(body)).joinToString(""){"%02x".format(it.toInt() and 255)}
        check(MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII),signature.toByteArray(Charsets.US_ASCII))){"AUTO_PROOF_SIGNATURE"}
        val en=parse(enrollment);val h=obj(en["handoff"]);val frozen=(h["frozenRequestJson"] as String).toByteArray(Charsets.UTF_8)
        val request=WireV0Codec.validateRequest(frozen);val result=parse(raw);AutomationV2Codec().validate(raw,frozen)
        val business=obj(en["businessContext"]);val context=obj(StrictJson.parse((request["contextJson"] as String).toByteArray(Charsets.UTF_8)))
        val stt=obj(result["stt"]);val a=obj(result["analysis"]);val businessSha=DriveRelay.sha256(StrictJson.bytes(business))
        val required=bindings.toSet()+setOf("schema","policy","state","reason","createdAtMs","scope","resultRevision","resultDigest","resultSha","requestSha","enrollmentSha","businessContextSha","caseId","transcriptSha","analysisSha","sourceAuthorization","producer","independentModelAttestation","stages")
        check(body.keys==required && body["schema"]==SCHEMA && body["policy"]==POLICY){"AUTO_PROOF_SCHEMA"}
        val source=bindings.all{equal(body[it],request[it])} && equal(body["scope"],scope.binding()) && equal(h["scope"],scope.binding()) && body["requestSha"]==DriveRelay.sha256(frozen) && body["enrollmentSha"]==DriveRelay.sha256(enrollment) && body["businessContextSha"]==businessSha && result["businessContextSha"]==businessSha && body["caseId"]==business["caseId"] && body["resultRevision"]==result["resultRevision"] && body["resultDigest"]==result["resultDigest"] && body["resultSha"]==DriveRelay.sha256(raw) && body["transcriptSha"]==stt["textSha"] && body["analysisSha"]==DriveRelay.sha256(StrictJson.bytes(analysis(a)))
        check(source){"AUTO_PROOF_BINDING"}
        val created=body["createdAtMs"];check(created is Long && created>0 && created<=System.currentTimeMillis()+300000 && context["leaseExpiresMs"] is Long && created<(context["leaseExpiresMs"] as Long)){"AUTO_PROOF_TIME"}
        check(body["producer"]=="AUTHENTICATED_WORKER_OBSERVED_HTTP" && body["independentModelAttestation"]==false){"AUTO_PROOF_PRODUCER"}
        val stages=body["stages"] as List<*>;check(stages.size==2){"AUTO_PROOF_STAGES"}
        val execution=stages.mapIndexed{i,value->
            val s=obj(value);val stage=if(i==0)"STT" else "SUMMARY";val model=if(i==0)stt["model"] else a["model"]
            val requestId=if(i==0)stt["requestId"] else a["requestId"]
            val input=if(i==0)mapOf("audioSha" to request["audioSha"],"model" to model,"language" to "ko") else mapOf("transcriptSha" to stt["textSha"],"businessContextSha" to businessSha,"sourceId" to request["sourceId"],"model" to model)
            s.keys==setOf("stage","status","schema","endpoint","httpStatus","model","providerRequestId","inputSha","outputSha","rawResponseSha") && s["stage"]==stage && s["status"]=="DONE" && s["schema"]=="ieum.worker.observed-http-stage.v1" && s["endpoint"]==(if(i==0)"/v1/audio/transcriptions" else "/v1/responses") && s["httpStatus"]==200L && s["model"]==model && s["providerRequestId"]==requestId && s["inputSha"]==DriveRelay.sha256(StrictJson.bytes(input)) && s["outputSha"]==(if(i==0)stt["textSha"] else body["analysisSha"]) && (s["rawResponseSha"] as? String)?.matches(Regex("[0-9a-f]{64}"))==true
        }.all{it}
        check(execution){"AUTO_PROOF_STAGE_RECEIPTS"}
        val privacy=context["synthetic"]==true && context["syntheticCreatorConfirmed"]==true && body["sourceAuthorization"]=="EXACT_APPROVED_SYNTHETIC_AUDIO"
        val semantics=semantic(result,business);val automatic=body["state"]=="APPROVED" && body["reason"]=="APPROVED_EXTRACTIVE_POLICY" && semantics
        check(privacy && semantics && automatic){"AUTO_POLICY_REVIEW_REQUIRED"}
        return Checks(source,execution,semantics,privacy,automatic,DriveRelay.sha256(proof))
    }
    private fun <T> database(context:Context,scope:DriveALocalScope,action:(android.database.sqlite.SQLiteDatabase)->T):T=ProtectedDatabase.open(AccountStorageContext(AutomationConnectionBindings.base(context),scope.owner),GeneralLocalStore.DATABASE,null).use{action(it.database())}
    fun record(context:Context,scope:DriveALocalScope,c:AutomationApiConfiguration,enrollment:ByteArray,raw:ByteArray,proof:ByteArray) {
        val x=parse(raw);val key=arrayOf(scope.key,x["jobId"].toString(),x["resultRevision"].toString(),x["resultDigest"].toString())
        val state=try{checkProof(scope,c,enrollment,raw,proof);"VERIFIED"}catch(_:Exception){"HELD"}
        database(context,scope){db->ProtectedDatabase.transaction(db){
            db.execSQL("CREATE TABLE IF NOT EXISTS automation_verified_evidence(scope TEXT NOT NULL,job TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,result_sha TEXT NOT NULL,enrollment BLOB NOT NULL,proof BLOB NOT NULL,state TEXT NOT NULL,origin TEXT NOT NULL,credential_sha TEXT NOT NULL,PRIMARY KEY(scope,job,revision,digest))")
            for(action in listOf("UPDATE","DELETE"))db.execSQL("CREATE TRIGGER IF NOT EXISTS automation_verified_evidence_${action.lowercase()} BEFORE $action ON automation_verified_evidence BEGIN SELECT RAISE(ABORT,'AUTO_VERIFICATION_IMMUTABLE'); END")
            val old=db.rawQuery("SELECT result_sha,enrollment,proof,state,origin,credential_sha FROM automation_verified_evidence WHERE scope=? AND job=? AND revision=? AND digest=?",key).use{q->if(!q.moveToFirst())false else {
                check(q.getString(0)==DriveRelay.sha256(raw) && q.getBlob(1).contentEquals(enrollment) && q.getBlob(2).contentEquals(proof) && q.getString(3)==state && q.getString(4)==c.origin && q.getString(5)==DriveRelay.sha256(c.bearerCredential.toByteArray(Charsets.UTF_8))){"AUTO_VERIFICATION_RECEIPT_CHANGED_REVIEW_REQUIRED"};true
            }}
            if(!old)db.insertOrThrow("automation_verified_evidence",null,ContentValues().apply{put("scope",scope.key);put("job",key[1]);put("revision",x["resultRevision"] as Long);put("digest",key[3]);put("result_sha",DriveRelay.sha256(raw));put("enrollment",enrollment);put("proof",proof);put("state",state);put("origin",c.origin);put("credential_sha",DriveRelay.sha256(c.bearerCredential.toByteArray(Charsets.UTF_8)))})
        }}
    }
    fun map(context:Context,job:JSONObject,raw:ByteArray,business:JSONObject,base:AutomationObservation.Result,allowed:()->Boolean):AutomationObservation.Result {
        if(!allowed())return base
        return runCatching {
            val session=GoogleConnectionSession.get(AutomationConnectionBindings.base(context));val scope=DriveALocalScope.current(session) ?: return base
            val cfg=AutomationConnectionBindings.processing(context,session) ?: return base
            check(job.getString("owner")==scope.owner && job.getLong("epoch")==scope.epoch)
            val result=parse(raw);val proofRecord=database(context,scope){db->
                if(!db.rawQuery("SELECT name FROM sqlite_master WHERE name='automation_verified_evidence'",null).use{it.moveToFirst()})return@database null
                db.rawQuery("SELECT result_sha,enrollment,proof,state,origin,credential_sha FROM automation_verified_evidence WHERE scope=? AND job=? AND revision=? AND digest=?",arrayOf(scope.key,result["jobId"].toString(),result["resultRevision"].toString(),result["resultDigest"].toString())).use{q->
                    if(!q.moveToFirst() || q.getString(3)!="VERIFIED")null else {
                        check(q.getString(0)==DriveRelay.sha256(raw) && q.getString(4)==cfg.origin && q.getString(5)==DriveRelay.sha256(cfg.bearerCredential.toByteArray(Charsets.UTF_8)))
                        q.getBlob(1) to q.getBlob(2)
                    }
                }
            } ?: return base
            val enrollment=parse(proofRecord.first);check(equal(enrollment["businessContext"],obj(StrictJson.parse(business.toString().toByteArray(Charsets.UTF_8)))))
            val h=obj(enrollment["handoff"]);check(h["localRequestId"]==job.getString("request_id") && h["frozenRequestJson"]==base.result.frozenRequest)
            val checks=checkProof(scope,cfg,proofRecord.first,raw,proofRecord.second);check(allowed())
            base.copy(assurance=AutomationAssurance(ORIGIN,checks.source,checks.execution,checks.semantics,checks.privacy,checks.automatic,checks.evidenceRef))
        }.getOrDefault(base)
    }
}
