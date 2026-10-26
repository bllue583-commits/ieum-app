package kr.callbrief

import kr.callagent.drive.*
import java.util.UUID

internal data class DriveALocalScope(val owner:String,val account:String,val permissionId:String,val sessionId:String,val epoch:Long) {
    init { require(owner.isNotBlank() && account.isNotBlank() && permissionId==owner && sessionId.isNotBlank() && epoch>=0) }
    fun binding()=mapOf("sessionId" to sessionId,"epoch" to epoch,"ownerBinding" to mapOf("ownerId" to owner,"driveAccount" to account))
    val key:String get()=DriveRelay.sha256(StrictJson.bytes(binding()))
    companion object {
        fun current(session:GoogleConnectionSession):DriveALocalScope? = runCatching {
            val b=DriveACandidateContract.obj(StrictJson.parse(session.nativeSessionBinding().toString().toByteArray()))
            DriveALocalScope(session.ownerBinding(),session.driveAccount(),session.driveOwnerPermissionId(),b["sessionId"] as String,session.epoch())
        }.getOrNull()
    }
}
internal data class DriveAExportSnapshot(val job:String,val folder:String,val request:ByteArray,val registration:String)
internal data class DriveAReadOnlyReceipt(val job:String,val result:String,val kind:String,val schema:Long,val revision:Long,val digest:String,val bytesSha:String,val state:String,val raw:String?,val analysis:String?,val conflict:Boolean,val hidden:Boolean) {
    val writerVerified=false;val executionVerified=false;val canonicalWriteAllowed=false;val remoteAtomicCasVerified=false
    fun metadata()=mapOf("job" to job,"result" to result,"kind" to kind,"schema" to schema,"revision" to revision,"digest" to digest,"bytesSha" to bytesSha,"state" to state,"conflict" to conflict,"hidden" to hidden,"writerVerified" to false,"executionVerified" to false,"canonicalWriteAllowed" to false,"remoteAtomicCasVerified" to false)
}
internal class DriveAIntegrityFailure:IllegalStateException("DRIVE_A_STORE_REVIEW_REQUIRED")
internal object DriveACandidateContract {
    const val SCHEMA="local.drive_a.foreground.handoff.v1"
    const val PURPOSE="ONE_EXISTING_OWNER_EXPLICIT_SYNTHETIC_READONLY"
    fun obj(value:Any?):Map<String,Any?> = StrictJson.`object`(value)
    fun hash(bytes:ByteArray)=DriveRelay.sha256(bytes)
    fun uuid()=UUID.randomUUID().toString()
    fun build(localRequest:String,scope:DriveALocalScope,s:DriveAExportSnapshot):String {
        require(localRequest.isNotBlank() && localRequest.length<=256)
        val request=WireV0Codec.validateRequest(s.request)
        val r=obj(StrictJson.parse(s.registration.toByteArray()))
        require(r.keys==setOf("state","files","jobId","folderId","creatorModes","sessionBinding","driveOwnerPermissionId","sessionEvidence","nativeAppSessionAuthentication","requestDigest"))
        require(r["jobId"]==s.job && r["folderId"]==s.folder && request["jobId"]==s.job && r["requestDigest"]==request["requestDigest"])
        require(r["sessionBinding"]==scope.binding() && r["driveOwnerPermissionId"]==scope.permissionId && request["ownerBinding"]==scope.binding()["ownerBinding"])
        require(r["creatorModes"]==listOf("ANDROID_APP") && r["nativeAppSessionAuthentication"]=="GOOGLE_DRIVE_OWNER_VERIFIED_NOT_OIDC_SERVER_ATTESTED" && r["sessionEvidence"]=="LOCAL_AUTHORIZATIONCLIENT_AND_DRIVE_ABOUT_OWNER_VERIFIED")
        val state=obj(r["state"])
        for(key in listOf("ownerBinding","sourceId","sourceVersion","audioSha","leaseEpoch","contextJson","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision"))require(state[key]==request[key])
        require(state["counterpartConfirmed"]==false && state["syntheticAudioApproved"]==true && state["consentGranted"]==true && state["audioAvailable"]==true)
        require(listOf("excluded","revoked","deleted","archived").all {state[it]==false})
        val files=obj(r["files"]);require(files.keys==setOf("audio","request","result"))
        for(key in files.keys) {val f=obj(files[key]);require(f.keys==setOf("fileId") && f["fileId"]==request[key+"FileId"])}
        // This wrapper is a LOCAL export, not a new executable Drive wire or
        // trusted remote registration. Original v0 request bytes stay exact.
        return StrictJson.stringify(mapOf("schema" to SCHEMA,"purpose" to PURPOSE,"localRequestId" to localRequest,"scope" to scope.binding(),"folderId" to s.folder,"frozenRequestJson" to String(s.request,Charsets.UTF_8),"frozenRequestSha256" to hash(s.request),"nativeRegistration" to r,
            "dotAcceptance" to "NOT_VERIFIED","currentRemoteRevocation" to "NOT_VERIFIED","writerProvenance" to "NOT_VERIFIED","modelExecution" to "NOT_RUN","remoteAtomicCas" to "NOT_VERIFIED","canonicalWriteAllowed" to false))
    }
    fun validateReceiptMetadata(m:Map<String,Any?>) {
        require(m.keys==setOf("job","result","kind","schema","revision","digest","bytesSha","state","conflict","hidden","writerVerified","executionVerified","canonicalWriteAllowed","remoteAtomicCasVerified"))
        require((m["job"] as String).isNotBlank() && (m["result"] as String).isNotBlank())
        require((m["revision"] as Long)>=0 && Regex("^[0-9a-f]{64}$").matches(m["digest"] as String) && Regex("^[0-9a-f]{64}$").matches(m["bytesSha"] as String))
        require((m["kind"]=="CALL_ANALYSIS_RESULT" && m["schema"]==0L && m["state"] in setOf("PENDING","PROPOSAL","ERROR","CANCELLED")) || (m["kind"]==ReadonlyV1Codec.KIND && m["schema"]==1L && m["state"]=="PROPOSAL") || (m["kind"]==AutomationV2Codec.KIND && m["schema"]==2L && m["state"]=="PROPOSAL"))
        require(m["conflict"] is Boolean && m["hidden"] is Boolean && listOf("writerVerified","executionVerified","canonicalWriteAllowed","remoteAtomicCasVerified").all {m[it]==false})
    }
    fun immutableReceiptMetadata(m:Map<String,Any?>)=m.filterKeys {it!="conflict" && it!="hidden"}
    fun validate(payload:String,localRequest:String,scope:DriveALocalScope):Map<String,Any?> {
        val x=obj(StrictJson.parse(payload.toByteArray()));require(x.keys==setOf("schema","purpose","localRequestId","scope","folderId","frozenRequestJson","frozenRequestSha256","nativeRegistration","dotAcceptance","currentRemoteRevocation","writerProvenance","modelExecution","remoteAtomicCas","canonicalWriteAllowed"))
        require(x["schema"]==SCHEMA && x["purpose"]==PURPOSE && x["localRequestId"]==localRequest && x["scope"]==scope.binding())
        val raw=(x["frozenRequestJson"] as String).toByteArray();require(hash(raw)==x["frozenRequestSha256"])
        val r=obj(x["nativeRegistration"]);val rebuilt=build(localRequest,scope,DriveAExportSnapshot(r["jobId"] as String,x["folderId"] as String,raw,StrictJson.stringify(r)))
        require(rebuilt==payload);return x
    }
}
