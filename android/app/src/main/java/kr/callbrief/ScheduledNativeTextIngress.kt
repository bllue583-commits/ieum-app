package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object ScheduledNativeTextContract {
    const val LINK_SCHEMA="ieum.automation.scheduled-native-link.v1"
    fun read(bytes:ByteArray,link:Map<String,Any?>,scope:DriveALocalScope,c:AutomationApiConfiguration,enrollment:ByteArray):Pair<ByteArray,ByteArray> {
        val e=StrictJson.`object`(StrictJson.parseReadOnlyEnvelope(bytes));check(e.keys==setOf("schema","body","signature","nativeResult","verification")&&e["schema"]=="ieum.automation.scheduled-native-bundle.v1"){"TEXT_NATIVE_BUNDLE_SCHEMA"}
        val b=StrictJson.`object`(e["body"]);check(b.keys==setOf("link","scope","enrollmentSha","nativeResultSha","verificationSha","inputVersion","resultVersion")){"TEXT_NATIVE_BUNDLE_BINDING"}
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(c.bearerCredential.toByteArray(Charsets.UTF_8),"HmacSHA256"));val expected=mac.doFinal("IEUM_SCHEDULED_NATIVE_LINK_V1\n".toByteArray(Charsets.UTF_8)+StrictJson.bytes(b)).joinToString(""){"%02x".format(it.toInt() and 255)}
        val supplied=e["signature"] as String;check(supplied.matches(Regex("[0-9a-f]{64}"))&&MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII),supplied.toByteArray(Charsets.US_ASCII))){"TEXT_NATIVE_BUNDLE_SIGNATURE"}
        check(b["link"]==link&&b["scope"]==scope.binding()&&b["enrollmentSha"]==DriveRelay.sha256(enrollment)&&listOf("inputVersion","resultVersion").all{(b[it] as? String)?.matches(Regex("[0-9]+"))==true}){"TEXT_NATIVE_BUNDLE_BINDING"}
        val result=StrictJson.bytes(e["nativeResult"]);val proof=StrictJson.bytes(e["verification"])
        check(b["nativeResultSha"]==DriveRelay.sha256(result)&&b["verificationSha"]==DriveRelay.sha256(proof)){"TEXT_NATIVE_BUNDLE_DIGEST"};return result to proof
    }
}
/** Production caller from the actual private Drive text readback into the existing v5 receiver.
 * Only one current, already enrolled native request with the same approved audio may be linked. */
internal class ScheduledNativeTextIngress(private val context:Context,private val session:GoogleConnectionSession,
    private val openStore:()->AndroidAutomationStore={nativeStore(context,session)},
    private val openPort:()->ProductionAutomationProcessingPort={ProductionAutomationProcessingPort(AutomationConnectionBindings.base(context),session,{AutomationConnectionBindings.processing(context,session)})}) {
    fun receive(prepared:PreparedWhisperScheduledTextInput?,registration:DotScheduledTextExchangePort.Registration,proposal:ScheduledTextProofCodec.Proposal,current:()->Boolean={true}):JSONObject {
        fun state(s:String)=JSONObject().put("state",s).put("nativeAudioRequestCompleted",false).put("newSttExecuted",false)
        val input=prepared ?: return state("WAITING_PREPARED_NATIVE_TEXT_BINDING")
        val scope=DriveALocalScope.current(session) ?: return state("WAITING_NATIVE_GRANT")
        if(!current())return state("LOCAL_WORK_STOPPED")
        return try {
            check(input.jobId==registration.job && DriveRelay.sha256(input.forJob(registration.job))==registration.inputSha && proposal.sourceSha==registration.inputSha){"TEXT_NATIVE_PREPARED_BINDING"}
            openStore().use{store->
                val candidates=store.jobs().filter{j->j.getString("audio_sha")==input.audioSha256&&j.getString("request_id").isNotBlank()&&j.getString("handoff").isNotBlank()&&j.getString("state") !in setOf("FAILED","CANCELLED","HELD_SOURCE")&&AutomationPendingResults.deadline(j)?.let{it>System.currentTimeMillis()}==true}
                if(candidates.size!=1)return@use state("WAITING_UNIQUE_NATIVE_TEXT_BINDING")
                val j=candidates.single();val id=j.getString("id")
                if(!store.claim(id))return@use state("EXISTING_NATIVE_REQUEST_BUSY")
                try {
                    if(!current()||!store.allowed(id))return@use state("HELD_CURRENT_SOURCE")
                    val record=AutomationProcessingJournal(context,scope).use{it.load(j.getString("request_id"))} ?: return@use state("WAITING_NATIVE_PROCESSING_ENROLLMENT")
                    val link=mapOf("schema" to ScheduledNativeTextContract.LINK_SCHEMA,"jobId" to record.job,"proofJob" to registration.job,"folder" to registration.folder,"input" to registration.input,"result" to registration.result,"inputSha" to registration.inputSha,"resultSha" to proposal.resultSha,"audioSha" to input.audioSha256,"preparedEvidenceSha" to input.evidenceSha)
                    pin(context,scope,registration.job,id,link)
                    val outcome=openPort().receiveScheduledText(j.getString("request_id"),link)
                    if(!current()||!store.allowed(id))return@use state("HELD_CURRENT_SOURCE")
                    val observation=outcome.observation ?: return@use state(outcome.state)
                    if(observation.disposition !in setOf("PROVENANCE_PENDING","DUPLICATE_PROPOSAL","READ_ONLY_CANDIDATE"))return@use state(observation.disposition)
                    val verified=store.readNativeObservation(id,observation);store.accept(id,verified.result,verified.assurance)
                    AndroidAutomationRuntime.bridgeReportQueue(context,store)
                    state(store.job(id).getString("state")).put("nativeRequestId",j.getString("request_id")).put("producerReceiptVerified",verified.assurance.automaticUseAllowed)
                }finally{store.release(id)}
            }
        }catch(e:Exception){state("HELD").put("reason",e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,120}"))} ?: "TEXT_NATIVE_INGRESS_REVIEW_REQUIRED")}
    }
    companion object {
        private fun nativeStore(context:Context,session:GoogleConnectionSession):AndroidAutomationStore {
            val base=AutomationConnectionBindings.base(context);val s=checkNotNull(DriveALocalScope.current(session));val ctx=AccountStorageContext(base,s.owner);val settings=SecureSettings(ctx)
            val zone=DailyReportRuntime.scope(base).let{r->DailyReportLedger(r.context,r.profile).use{it.config().takeIf{c->c.enabled}?.zone}}
            return AndroidAutomationStore(ctx,s.owner,s.epoch,{DriveALocalScope.current(session)?.key==s.key},{AndroidAutomationRuntime.policyHash(settings.snapshot())},{AndroidAutomationRuntime.sourceAllowed(ctx,session,it)},{zone})
        }
        private fun pin(context:Context,scope:DriveALocalScope,proof:String,job:String,link:Map<String,Any?>)=ProtectedDatabase.open(AccountStorageContext(AutomationConnectionBindings.base(context),scope.owner),GeneralLocalStore.DATABASE,null).use{p->val db=p.database();ProtectedDatabase.transaction(db){
            db.execSQL("CREATE TABLE IF NOT EXISTS automation_scheduled_native_links(scope TEXT NOT NULL,proof TEXT NOT NULL,job TEXT NOT NULL,link BLOB NOT NULL,PRIMARY KEY(scope,proof))")
            for(action in listOf("UPDATE","DELETE"))db.execSQL("CREATE TRIGGER IF NOT EXISTS automation_scheduled_native_${action.lowercase()} BEFORE $action ON automation_scheduled_native_links BEGIN SELECT RAISE(ABORT,'TEXT_NATIVE_LINK_IMMUTABLE'); END")
            val bytes=StrictJson.bytes(link);val exists=db.rawQuery("SELECT job,link FROM automation_scheduled_native_links WHERE scope=? AND proof=?",arrayOf(scope.key,proof)).use{c->if(!c.moveToFirst())false else {check(c.getString(0)==job&&c.getBlob(1).contentEquals(bytes)){"TEXT_NATIVE_LINK_CHANGED_REVIEW_REQUIRED"};true}}
            if(!exists)db.execSQL("INSERT INTO automation_scheduled_native_links VALUES(?,?,?,?)",arrayOf(scope.key,proof,job,bytes))
        }}
    }
}
