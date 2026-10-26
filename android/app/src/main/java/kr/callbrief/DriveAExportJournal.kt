package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort
import java.io.ByteArrayInputStream

/** Separate protected audit journal. It does not own Drive results, reservations,
 * request publication, OAuth identity, canonical rows or the remote writer. */
internal class DriveAExportJournal(context:Context,private val scope:DriveALocalScope,private val current:()->DriveALocalScope?):AutoCloseable {
    companion object {
        const val DATABASE="ieum-drive-a-foreground-v1.db"
        private val STATES=setOf("REQUEST_PREPARED","HANDOFF_UNCERTAIN","RESULT_OBSERVATION_RECORDED")
        private const val SCHEMA="""
CREATE TABLE a_exports(scope_key TEXT NOT NULL,request_id TEXT NOT NULL,payload TEXT NOT NULL,payload_sha TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(scope_key,request_id));
-- statement-break
CREATE TABLE a_receipts(id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,request_id TEXT NOT NULL,kind TEXT NOT NULL,digest TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(scope_key,request_id,kind,digest));
-- statement-break
CREATE TRIGGER a_export_identity BEFORE UPDATE ON a_exports WHEN OLD.scope_key!=NEW.scope_key OR OLD.request_id!=NEW.request_id OR OLD.payload!=NEW.payload OR OLD.payload_sha!=NEW.payload_sha BEGIN SELECT RAISE(ABORT,'IMMUTABLE_EXPORT'); END;
-- statement-break
CREATE TRIGGER a_export_no_replace BEFORE INSERT ON a_exports WHEN EXISTS(SELECT 1 FROM a_exports WHERE scope_key=NEW.scope_key AND request_id=NEW.request_id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_EXPORT'); END;
-- statement-break
CREATE TRIGGER a_receipt_no_update BEFORE UPDATE ON a_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
-- statement-break
CREATE TRIGGER a_receipt_no_delete BEFORE DELETE ON a_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
-- statement-break
CREATE TRIGGER a_receipt_no_replace BEFORE INSERT ON a_receipts WHEN EXISTS(SELECT 1 FROM a_receipts WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
"""
    }
    data class HandoffIntent(val view:View,val created:Boolean)
    data class View(val request:String,val payload:String,val sha:String,val state:String,val attempt:String?,val resultMetadata:Map<String,Any?>?)
    private val port:AndroidSqlPort
    init {check(Looper.myLooper()!=Looper.getMainLooper());gate();port=AndroidSqlPort(AccountStorageContext(context,scope.owner),DATABASE,ByteArrayInputStream(SCHEMA.toByteArray()));port.requireProtectedPersistence()}
    private fun gate(){check(current()==scope){"DRIVE_A_SCOPE_CHANGED"}}
    private fun rows(sql:String,vararg args:Any?)=port.query(sql,*args)
    private fun receipts(request:String,kind:String):List<Map<String,Any?>> = rows("SELECT payload,digest FROM a_receipts WHERE scope_key=? AND request_id=? AND kind=?",scope.key,request,kind).map {
        val payload=it["payload"] as String;check(DriveACandidateContract.hash(payload.toByteArray())==it["digest"])
        val value=DriveACandidateContract.obj(StrictJson.parse(payload.toByteArray()));check(StrictJson.stringify(value)==payload);value
    }
    private fun view(request:String):View? { return try {
        val r=rows("SELECT payload,payload_sha,state FROM a_exports WHERE scope_key=? AND request_id=?",scope.key,request).singleOrNull() ?: return null
        val payload=r["payload"] as String;val sha=r["payload_sha"] as String;val state=r["state"] as String
        check(state in STATES && DriveACandidateContract.hash(payload.toByteArray())==sha)
        val export=DriveACandidateContract.validate(payload,request,scope)
        val prepared=receipts(request,"PREPARED");check(prepared.size==1 && prepared.single()==export)
        val attempts=receipts(request,"HANDOFF_INTENT");check(attempts.size<=1)
        val attempt=attempts.singleOrNull()?.let {check(it.keys==setOf("attempt","exportSha","dotAcceptance"));check(it["exportSha"]==sha && it["dotAcceptance"]=="NOT_VERIFIED");(it["attempt"] as String).also {id ->check(Regex("^[0-9a-f-]{36}$").matches(id))}}
        if(state=="HANDOFF_UNCERTAIN")check(attempt!=null)
        val kinds=rows("SELECT DISTINCT kind FROM a_receipts WHERE scope_key=? AND request_id=?",scope.key,request).map {it["kind"]}
        check(kinds.all {it in setOf("PREPARED","HANDOFF_INTENT","SHARE_UI_RETURN","RESULT_OBSERVATION")})
        receipts(request,"SHARE_UI_RETURN").forEach {check(it.keys==setOf("attempt","cancelled","dotAcceptance") && it["attempt"]==attempt && attempt!=null && it["cancelled"] is Boolean && it["dotAcceptance"]=="NOT_VERIFIED")}
        val observations=receipts(request,"RESULT_OBSERVATION")
        if(state=="REQUEST_PREPARED")check(attempt==null && observations.isEmpty())
        if(state=="HANDOFF_UNCERTAIN")check(observations.isEmpty())
        if(state=="RESULT_OBSERVATION_RECORDED")check(observations.isNotEmpty())
        val body=observations.maxByOrNull { (it["revision"] as Long) }
        observations.forEach {DriveACandidateContract.validateReceiptMetadata(it);check(it["job"]==DriveACandidateContract.obj(export["nativeRegistration"])["jobId"] && it["result"]==DriveACandidateContract.obj(kr.callagent.drive.WireV0Codec.validateRequest((export["frozenRequestJson"] as String).toByteArray()))["resultFileId"] && it["canonicalWriteAllowed"]==false && it["writerVerified"]==false && it["executionVerified"]==false && it["remoteAtomicCasVerified"]==false)}
        View(request,payload,sha,state,attempt,body)
    }catch(_:Exception){throw DriveAIntegrityFailure()} }
    fun load(request:String):View?=port.transaction{gate();view(request)}
    private fun append(request:String,kind:String,value:Map<String,Any?>) {
        val payload=StrictJson.stringify(value);val digest=DriveACandidateContract.hash(payload.toByteArray())
        if(rows("SELECT id FROM a_receipts WHERE scope_key=? AND request_id=? AND kind=? AND digest=?",scope.key,request,kind,digest).isEmpty())port.execute("INSERT INTO a_receipts VALUES(?,?,?,?,?,?)",DriveACandidateContract.uuid(),scope.key,request,kind,digest,payload)
    }
    fun prepare(request:String,payload:String):View=port.transaction {
        gate();val value=DriveACandidateContract.validate(payload,request,scope);val known=view(request)
        if(known!=null){check(known.payload==payload){"DRIVE_A_EXPORT_CHANGED"};return@transaction known}
        append(request,"PREPARED",value);port.execute("INSERT INTO a_exports VALUES(?,?,?,?,?)",scope.key,request,payload,DriveACandidateContract.hash(payload.toByteArray()),"REQUEST_PREPARED");gate();checkNotNull(view(request))
    }
    fun handoffIntent(request:String):HandoffIntent=port.transaction {
        gate();val v=checkNotNull(view(request));if(v.attempt!=null)return@transaction HandoffIntent(v,false)
        append(request,"HANDOFF_INTENT",mapOf("attempt" to DriveACandidateContract.uuid(),"exportSha" to v.sha,"dotAcceptance" to "NOT_VERIFIED"));port.execute("UPDATE a_exports SET state='HANDOFF_UNCERTAIN' WHERE scope_key=? AND request_id=?",scope.key,request);gate();HandoffIntent(checkNotNull(view(request)),true)
    }
    fun uiReturn(request:String,cancelled:Boolean):View=port.transaction {
        gate();val v=checkNotNull(view(request));check(v.attempt!=null)
        append(request,"SHARE_UI_RETURN",mapOf("attempt" to v.attempt,"cancelled" to cancelled,"dotAcceptance" to "NOT_VERIFIED"));checkNotNull(view(request))
    }
    fun observation(request:String,result:DriveAReadOnlyReceipt):View=port.transaction {
        gate();val v=checkNotNull(view(request));val expected=DriveACandidateContract.obj(kr.callagent.drive.WireV0Codec.validateRequest((DriveACandidateContract.obj(StrictJson.parse(v.payload.toByteArray()))["frozenRequestJson"] as String).toByteArray()))
        check(result.job==expected["jobId"] && result.result==expected["resultFileId"] && result.revision>=0 && Regex("^[0-9a-f]{64}$").matches(result.digest) && Regex("^[0-9a-f]{64}$").matches(result.bytesSha))
        DriveACandidateContract.validateReceiptMetadata(result.metadata())
        val same=receipts(request,"RESULT_OBSERVATION").filter {it["revision"]==result.revision};check(same.all {it["digest"]==result.digest && it["kind"]==result.kind && it["schema"]==result.schema}) {"DRIVE_A_RESULT_CONFLICT"}
        append(request,"RESULT_OBSERVATION",result.metadata());port.execute("UPDATE a_exports SET state='RESULT_OBSERVATION_RECORDED' WHERE scope_key=? AND request_id=?",scope.key,request);gate();checkNotNull(view(request))
    }
    override fun close()=port.close()
}
