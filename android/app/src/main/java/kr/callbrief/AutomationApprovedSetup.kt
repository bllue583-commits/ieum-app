package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import org.json.JSONObject

/** Only a prior owner-scoped approval receipt already in the existing AEAD settings store.
 * No credential UI/Intent, token extraction, issuer, download or provisioning is added here. */
internal object ExistingApprovedProcessingSetup {
    fun read(context:Context,session:GoogleConnectionSession,settings:(Context)->SecureSettings={SecureSettings(it)},now:()->Long=System::currentTimeMillis):AutomationApiConfiguration? {
        val s=DriveALocalScope.current(session) ?: return null
        val account=AccountStorageContext(AutomationConnectionBindings.base(context),s.owner)
        val state=settings(account).snapshot()
        val x=state.optJSONObject("approvedAutomationProcessingSetup")
        if(x==null){
            check(!state.has(ApprovedProcessingSetupWriter.SETUP) && !state.has(ApprovedProcessingSetupWriter.MARKER)){"APPROVED_PROCESSING_SETUP_REVIEW"}
            ApprovedProcessingIntakeJournal.checkEmptyReader(account,s)
            return null
        }
        check(x.keys().asSequence().toSet()==setOf("schema","approvalState","scope","origin","zone","credential")) { "APPROVED_PROCESSING_SETUP_REVIEW" }
        check(x.optString("schema")=="ieum.owner-approved-processing-setup.v1" && x.optString("approvalState")=="PREVIOUSLY_APPROVED_SYNTHETIC_ONLY") { "APPROVED_PROCESSING_SETUP_REVIEW" }
        val binding=StrictJson.`object`(StrictJson.parse(x.getJSONObject("scope").toString().toByteArray()))
        check(binding==s.binding()) { "APPROVED_PROCESSING_SETUP_SCOPE_CHANGED" }
        val canonical=ApprovedProcessingSetupWriter.canonicalSetup(x.toString().toByteArray(),s).first
        ApprovedProcessingIntakeJournal.checkReader(account,s,state,canonical,now())
        return AutomationApiConfiguration(true,x.getString("origin"),s.owner,s.account,s.epoch,x.getString("zone"),x.getString("credential")).also{it.validate(s)}
    }
}
internal class AutomationApprovedSetup(private val context:Context,private val session:GoogleConnectionSession,
    private val drive:DriveTransport=DriveRestTransport(session,GoogleDriveHttp(session)),
    private val existingProcessing:(()->AutomationApiConfiguration?)?=null,
    private val capability:(AutomationApiConfiguration)->AutomationProcessingHttp={AutomationWorkerHttp(it)},
    private val approvedSettings:(Context)->SecureSettings={SecureSettings(it)},
    private val approvedNow:()->Long=System::currentTimeMillis) {
    /** Staging only. The disconnected provider cannot populate credentials or invoke apply. */
    fun receiveExistingProcessingSetup()=ApprovedProcessingSetupWriter(context,session).receive()
    fun applyScheduled(plan:ScheduledDotProofPlan) {
        val s=DriveALocalScope.current(session) ?: error("AUTOMATION_CURRENT_NATIVE_GRANT_REQUIRED")
        AutomationConnectionBindings.validateProof(plan)
        val folder=drive.metadata(plan.queueFolder)
        check(folder.id==plan.queueFolder && folder.mimeType==DriveTransport.FOLDER_MIME && folder.ownerBinding==s.owner && folder.privateToOwner && folder.isAppAuthorized && !folder.trashed && !folder.version.isNullOrBlank()) { "APPROVED_QUEUE_PRIVATE_APP_ACCESS_REQUIRED" }
        if(plan.inputMode==ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC)check(PreparedLocalSttFiles.load(context,s,plan)!=null) { "WAITING_PREPARED_STT_INPUT" }
        session.commit(s.epoch,s.owner){AutomationConnectionBindings.bindApprovedScheduled(context,session,plan)}
        ScheduledDotProofRuntime.ensureApproved(context,session,plan)
    }
    fun applyExistingProcessing():String {
        val s=DriveALocalScope.current(session) ?: return "WAITING_USER_REAUTH"
        val configuration=(if(existingProcessing!=null)existingProcessing.invoke() else
            AutomationConnectionBindings.processing(context,session,approvedSettings,approvedNow) ?:
                ExistingApprovedProcessingSetup.read(context,session,approvedSettings,approvedNow)) ?: return "WAITING_PREVIOUS_APPROVED_WORKER_SETUP"
        configuration.validate(s)
        val reply=capability(configuration).call("GET","/v1/automation/capabilities")
        check(reply.status==200) { "APPROVED_WORKER_CAPABILITY_RECHECK_REQUIRED" }
        val x=StrictJson.`object`(StrictJson.parseReadOnlyEnvelope(reply.bytes))
        check(x["schema"]=="ieum.automation.capabilities.v1" && x["ownerId"]==s.owner && x["driveAccount"]==s.account && x["accountEpoch"]==s.epoch && x["reportZone"]==configuration.reportZone && x["resultKind"]==AutomationV2Codec.KIND && x["resultSchemaVersion"]==2L && listOf("dotInvoked","driveResultWritten","canonicalWriteAllowed").all{x[it]==false}) { "APPROVED_WORKER_SCOPE_MISMATCH" }
        session.commit(s.epoch,s.owner){AutomationConnectionBindings.bindApprovedProcessing(context,session,configuration,approvedSettings,approvedNow)}
        AutomaticResultReconciliation.rearmExisting(context,session)
        return "PREVIOUS_APPROVED_WORKER_BOUND"
    }
}
