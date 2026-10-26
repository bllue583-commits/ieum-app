package kr.callbrief

import android.content.ContentValues
import android.content.Context
import android.os.Looper
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Explicit upstream configuration only. This class creates no grant, credential or scheduling. */
internal data class AutomationApiConfiguration(val enabled:Boolean,val origin:String,val owner:String,val account:String,
    val epoch:Long,val reportZone:String,val bearerCredential:String) {
    override fun toString()="AutomationApiConfiguration[credential withheld]"
    fun validate(scope:DriveALocalScope) {
        check(enabled) { "AUTOMATION_PROCESSOR_DISABLED" }
        val u=URI(origin);check(u.scheme=="https" && !u.host.isNullOrBlank() && u.userInfo==null && u.query==null && u.fragment==null && u.path.isNullOrEmpty() && origin=="https://"+u.authority) { "AUTOMATION_HTTPS_ORIGIN_REQUIRED" }
        check(owner==scope.owner && account==scope.account && epoch==scope.epoch) { "AUTOMATION_OWNER_EPOCH_MISMATCH" }
        java.time.ZoneId.of(reportZone)
        check(bearerCredential.length>=32 && bearerCredential.all { it.code in 33..126 }) { "AUTOMATION_WORKER_CREDENTIAL_REQUIRED" }
    }
}
internal data class AutomationHttpReply(val status:Int,val bytes:ByteArray)
internal interface AutomationProcessingHttp { fun call(method:String,path:String,body:ByteArray?=null):AutomationHttpReply }
/** Production HTTP adapter. Only caller-approved fixed origin; no redirects/cookies or automatic retries. */
internal class AutomationWorkerHttp(private val configuration:AutomationApiConfiguration):AutomationProcessingHttp {
    private val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
    override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
        check(path=="/v1/automation/capabilities" || Regex("^/v1/automation/jobs/[A-Za-z0-9_-]{1,256}(/result|/cancel|/verification|/scheduled-text)?$").matches(path)) { "AUTOMATION_PATH_REJECTED" }
        val request=Request.Builder().url(configuration.origin+path).header("Authorization","Bearer "+configuration.bearerCredential)
            .header("Accept","application/json").method(method,body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val out=java.io.ByteArrayOutputStream();response.body?.byteStream()?.use { input ->
                val chunk=ByteArray(8192);while(true){val n=input.read(chunk);if(n<0)break;check(out.size()+n<=524288) { "AUTOMATION_RESPONSE_SIZE_LIMIT" };out.write(chunk,0,n)}
            }
            return AutomationHttpReply(response.code,out.toByteArray())
        }
    }
}

/** Processing-only seam used by the macro coordinator; never owns intake, case updates or Kakao. */
internal interface AutomationProcessingPort {
    data class Outcome(val state:String,val job:String?=null,val observation:GoogleRelayStore.ResultObservation?=null) {
        val dotInvoked=false;val driveResultWritten=false;val canonicalWriteAllowed=false;val writerVerified=false;val executionIndependentlyVerified=false
    }
    fun enrollPublished(localRequest:String,businessContext:JSONObject):Outcome
    fun pollPublished(localRequest:String):Outcome
}
internal interface AutomationProposalReceiver { fun receive(job:String,bytes:ByteArray,contextSha:String):GoogleRelayStore.ResultObservation }
internal class NativeAutomationProposalReceiver(private val context:Context,private val session:GoogleConnectionSession):AutomationProposalReceiver {
    override fun receive(job:String,bytes:ByteArray,contextSha:String):GoogleRelayStore.ResultObservation {
        val scoped=AccountStorageContext(context,session.ownerBinding());val settings=SecureSettings(scoped)
        return GoogleNativePipeline(context,session,settings,{SafSelectedRecordingSource(scoped,settings,settings.snapshot().getString("treeUri"))},DriveRestTransport(session,GoogleDriveHttp(session))).use {
            it.receiveAutomationProposal(job,bytes,contextSha)
        }
    }
}
internal class ProductionAutomationProcessingPort(private val context:Context,private val session:GoogleConnectionSession,
    private val configuration:()->AutomationApiConfiguration?={null},
    private val source:()->DriveAForegroundPort={ExistingDriveARequestPort(context,session)},
    private val http:(AutomationApiConfiguration)->AutomationProcessingHttp={AutomationWorkerHttp(it)},
    private val receiver:AutomationProposalReceiver=NativeAutomationProposalReceiver(context,session)):AutomationProcessingPort {
    private fun scope():DriveALocalScope=DriveALocalScope.current(session) ?: error("AUTOMATION_CURRENT_NATIVE_GRANT_REQUIRED")
    private fun active():Pair<AutomationApiConfiguration,DriveALocalScope>? {
        val c=configuration() ?: return null
        if(!c.enabled)return null
        val s=scope();c.validate(s);return c to s
    }
    private fun obj(raw:ByteArray)=StrictJson.`object`(StrictJson.parseReadOnlyEnvelope(raw))
    private fun caps(c:AutomationApiConfiguration,s:DriveALocalScope,transport:AutomationProcessingHttp):Map<String,Any?> {
        val response=transport.call("GET","/v1/automation/capabilities");check(response.status==200) { "AUTOMATION_CAPABILITY_NOT_VERIFIED" };val x=obj(response.bytes)
        check(x["schema"]=="ieum.automation.capabilities.v1" && x["ownerId"]==s.owner && x["driveAccount"]==s.account && x["accountEpoch"]==s.epoch && x["reportZone"]==c.reportZone && x["resultKind"]==AutomationV2Codec.KIND && x["resultSchemaVersion"]==2L && listOf("driveResultWritten","dotInvoked","canonicalWriteAllowed").all {x[it]==false}) { "AUTOMATION_CAPABILITY_MISMATCH" }
        c.validate(scope());return x
    }
    override fun enrollPublished(localRequest:String,businessContext:JSONObject):AutomationProcessingPort.Outcome {
        val (c,s)=active() ?: return AutomationProcessingPort.Outcome("PROCESSOR_NOT_CONFIGURED")
        check(Looper.myLooper()!=Looper.getMainLooper()) { "AUTOMATION_IO_THREAD_REQUIRED" }
        val snapshot=source().snapshot(localRequest)
        val handoff=DriveACandidateContract.build(localRequest,s,snapshot)
        val business=StrictJson.`object`(StrictJson.parse(businessContext.toString().toByteArray(Charsets.UTF_8)))
        check(business.keys==setOf("caseId","revision","proposedSummary","tasks") && business["revision"] is Long && (business["revision"] as Long)>=0 && business["proposedSummary"] is String && business["tasks"] is List<*>) { "AUTOMATION_BUSINESS_CONTEXT_REQUIRED" }
        val frozen=WireV0Codec.validateRequest(snapshot.request);val nativeContext=StrictJson.`object`(StrictJson.parse((frozen["contextJson"] as String).toByteArray(Charsets.UTF_8)))
        check(business["caseId"]==nativeContext["caseId"]) { "AUTOMATION_CASE_BINDING_MISMATCH" }
        val bytes=StrictJson.bytes(mapOf("schema" to "ieum.automation.enrollment.v1","reportZone" to c.reportZone,"handoff" to obj(handoff.toByteArray()),"businessContext" to business))
        val contextSha=DriveRelay.sha256(StrictJson.bytes(business))
        return AutomationProcessingJournal(context,s).use { journal ->
            val record=journal.prepare(localRequest,snapshot.job,bytes,contextSha);val transport=http(c);caps(c,s,transport)
            journal.state(localRequest,"REGISTERING");val response=try{transport.call("PUT","/v1/automation/jobs/"+record.job,record.bytes)}catch(_:Exception){journal.state(localRequest,"REGISTERING_UNCERTAIN");return@use AutomationProcessingPort.Outcome("REGISTERING_UNCERTAIN",record.job)}
            if(response.status!=200){journal.state(localRequest,"SERVER_HELD");return@use AutomationProcessingPort.Outcome("SERVER_HELD",record.job)}
            val ack=obj(response.bytes);check(ack["schema"]=="ieum.automation.job-status.v1" && ack["jobId"]==record.job && ack["requestSha"]==frozen.let { DriveRelay.sha256(snapshot.request) } && ack["enrollmentSha"]==record.sha && ack["dotInvoked"]==false && ack["driveResultWritten"]==false && ack["state"] in setOf("QUEUED","PROCESSING","RESULT_READY","HELD","CANCELLED")) { "AUTOMATION_ACK_BINDING_MISMATCH" }
            c.validate(scope());journal.state(localRequest,"REGISTERED");AutomationProcessingPort.Outcome("REGISTERED",record.job)
        }
    }
    /** Existing native job only. A text proof never fabricates an API result or execution receipt. */
    fun receiveScheduledText(localRequest:String,link:Map<String,Any?>):AutomationProcessingPort.Outcome {
        val (c,s)=active() ?: return AutomationProcessingPort.Outcome("PROCESSOR_NOT_CONFIGURED")
        check(Looper.myLooper()!=Looper.getMainLooper()){"AUTOMATION_IO_THREAD_REQUIRED"}
        return AutomationProcessingJournal(context,s).use {journal->
            val record=journal.load(localRequest) ?: return@use AutomationProcessingPort.Outcome("PROCESSOR_JOB_NOT_REGISTERED")
            check(link["jobId"]==record.job && link["schema"]==ScheduledNativeTextContract.LINK_SCHEMA){"TEXT_NATIVE_JOB_BINDING"}
            val transport=http(c);val cap=caps(c,s,transport)
            if(!VerifiedAutomaticResults.advertised(cap)||cap["scheduledTextIngressSchema"]!=ScheduledNativeTextContract.LINK_SCHEMA)return@use AutomationProcessingPort.Outcome("TEXT_NATIVE_BRIDGE_NOT_SUPPORTED",record.job)
            val response=try{transport.call("POST","/v1/automation/jobs/"+record.job+"/scheduled-text",StrictJson.bytes(link))}catch(_:Exception){return@use AutomationProcessingPort.Outcome("PROCESSOR_PENDING",record.job)}
            if(response.status==202||response.status==204||response.status==408||response.status==429||response.status in 500..599)return@use AutomationProcessingPort.Outcome("PROCESSOR_PENDING",record.job)
            if(response.status!=200)return@use AutomationProcessingPort.Outcome("TEXT_NATIVE_HTTP_${response.status}_HELD",record.job)
            c.validate(scope())
            val pair=ScheduledNativeTextContract.read(response.bytes,link,s,c,record.bytes)
            val decoded=AutomationV2Codec().validate(pair.first,(StrictJson.`object`(obj(record.bytes)["handoff"])["frozenRequestJson"] as String).toByteArray(Charsets.UTF_8))
            check(decoded.businessContextSha==record.contextSha){"AUTOMATION_CONTEXT_BINDING_MISMATCH"}
            VerifiedAutomaticResults.record(context,s,c,record.bytes,pair.first,pair.second)
            val observation=receiver.receive(record.job,pair.first,record.contextSha);c.validate(scope())
            AutomationProcessingPort.Outcome(observation.disposition,record.job,observation)
        }
    }

    override fun pollPublished(localRequest:String):AutomationProcessingPort.Outcome {
        val (c,s)=active() ?: return AutomationProcessingPort.Outcome("PROCESSOR_NOT_CONFIGURED")
        check(Looper.myLooper()!=Looper.getMainLooper()) { "AUTOMATION_IO_THREAD_REQUIRED" }
        return AutomationProcessingJournal(context,s).use { journal ->
            val record=journal.load(localRequest) ?: return@use AutomationProcessingPort.Outcome("PROCESSOR_JOB_NOT_REGISTERED")
            val transport=http(c);val capabilities=caps(c,s,transport);val response=try{transport.call("GET","/v1/automation/jobs/"+record.job+"/result")}catch(_:Exception){return@use AutomationProcessingPort.Outcome("RESULT_READ_UNCERTAIN",record.job)}
            if(response.status==204)return@use AutomationProcessingPort.Outcome("PROCESSOR_PENDING",record.job)
            if(response.status!=200)return@use AutomationProcessingPort.Outcome("SERVER_HELD",record.job)
            val x=obj(record.bytes);val handoff=StrictJson.`object`(x["handoff"]);val frozen=(handoff["frozenRequestJson"] as String).toByteArray(Charsets.UTF_8)
            val decoded=AutomationV2Codec().validate(response.bytes,frozen);check(decoded.businessContextSha==record.contextSha) { "AUTOMATION_CONTEXT_BINDING_MISMATCH" }
            if(VerifiedAutomaticResults.advertised(capabilities)) {
                val proof=try{transport.call("GET","/v1/automation/jobs/"+record.job+"/verification")}catch(_:Exception){return@use AutomationProcessingPort.Outcome("PROCESSOR_PENDING",record.job)}
                // Transport availability is not evidence. Retry within the original job/lease;
                // never pin an absent proof as an immutable integrity rejection.
                if(proof.status==202 || proof.status==204 || proof.status==408 || proof.status==429 || proof.status in 500..599)
                    return@use AutomationProcessingPort.Outcome("PROCESSOR_PENDING",record.job)
                if(proof.status!=200)return@use AutomationProcessingPort.Outcome("VERIFICATION_HTTP_${proof.status}_HELD",record.job)
                c.validate(scope())
                VerifiedAutomaticResults.record(context,s,c,record.bytes,response.bytes,proof.bytes)
            }
            c.validate(scope());val observation=receiver.receive(record.job,response.bytes,record.contextSha);c.validate(scope())
            if(observation.disposition in setOf("PROVENANCE_PENDING","DUPLICATE_PROPOSAL","READ_ONLY_CANDIDATE"))journal.state(localRequest,"READ_ONLY_PROPOSAL_RECEIVED")
            AutomationProcessingPort.Outcome(observation.disposition,record.job,observation)
        }
    }
}

/** AEAD-protected Android DB journal; exact enrollment bytes are permanently bound to one source job. */
internal class AutomationProcessingJournal(context:Context,private val scope:DriveALocalScope):AutoCloseable {
    data class Record(val job:String,val bytes:ByteArray,val sha:String,val contextSha:String,val state:String)
    private val protectedStore=ProtectedDatabase.open(AccountStorageContext(context,scope.owner),GeneralLocalStore.DATABASE,null)
    private val db=protectedStore.database()
    init { ProtectedDatabase.transaction(db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS automation_api_enrollments(scope_key TEXT NOT NULL,local_request TEXT NOT NULL,job TEXT NOT NULL,envelope BLOB NOT NULL,envelope_sha TEXT NOT NULL,business_sha TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(scope_key,local_request),UNIQUE(scope_key,job))")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS automation_api_enrollment_immutable BEFORE UPDATE OF scope_key,local_request,job,envelope,envelope_sha,business_sha ON automation_api_enrollments BEGIN SELECT RAISE(ABORT,'AUTOMATION_ENROLLMENT_IMMUTABLE'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS automation_api_enrollment_no_delete BEFORE DELETE ON automation_api_enrollments BEGIN SELECT RAISE(ABORT,'AUTOMATION_ENROLLMENT_IMMUTABLE'); END")
    } }
    fun load(id:String):Record?=db.rawQuery("SELECT job,envelope,envelope_sha,business_sha,state FROM automation_api_enrollments WHERE scope_key=? AND local_request=?",arrayOf(scope.key,id)).use { c ->
        if(!c.moveToFirst())return@use null
        val r=Record(c.getString(0),c.getBlob(1),c.getString(2),c.getString(3),c.getString(4))
        check(DriveRelay.sha256(r.bytes)==r.sha) { "AUTOMATION_JOURNAL_REVIEW" };val x=StrictJson.`object`(StrictJson.parse(r.bytes));val h=StrictJson.`object`(x["handoff"])
        DriveACandidateContract.validate(StrictJson.stringify(h),id,scope)
        check(StrictJson.`object`(h["nativeRegistration"])["jobId"]==r.job && DriveRelay.sha256(StrictJson.bytes(x["businessContext"]))==r.contextSha) { "AUTOMATION_JOURNAL_REVIEW" };r
    }
    fun prepare(id:String,job:String,bytes:ByteArray,contextSha:String):Record=ProtectedDatabase.transaction(db) {
        val old=load(id);if(old!=null){check(old.job==job && old.bytes.contentEquals(bytes) && old.contextSha==contextSha) { "AUTOMATION_ENROLLMENT_CONTEXT_CHANGED" };return@transaction old}
        val v=ContentValues().apply{put("scope_key",scope.key);put("local_request",id);put("job",job);put("envelope",bytes);put("envelope_sha",DriveRelay.sha256(bytes));put("business_sha",contextSha);put("state","PREPARED")}
        db.insertOrThrow("automation_api_enrollments",null,v);checkNotNull(load(id))
    }
    fun state(id:String,value:String)=ProtectedDatabase.transaction(db) { check(load(id)!=null);db.execSQL("UPDATE automation_api_enrollments SET state=? WHERE scope_key=? AND local_request=?",arrayOf(value,scope.key,id)) }
    override fun close()=protectedStore.close()
}
