package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.ieum.discovery.DiscoveryTypes.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.spec.SecretKeySpec

/** Actual native discovery/sink, protected SQLite, native immutable requests, coordinator,
 * accumulation and report ledger. Generated WAV + in-memory Drive/provider only.
 * Fixture assurance drives policy branches; it is NOT proof of real STT/dot execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VerificationTransportRetryIntegrationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private lateinit var source:GeneralLocalStoreTest.Source;private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private val at=Instant.parse("2026-10-11T10:00:00Z").toEpochMilli();private var clock=at+600000
    private var recording="";private var policy="PUBLIC_POLICY_1";private var currentScope=true
    private class Memory:GoogleConnectionSession.Persistence {var value=JSONObject();override fun read()=JSONObject(value.toString());override fun write(v:JSONObject){value=JSONObject(v.toString())}}
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory())
        val epoch=session.begin();session.activate(epoch,"automation@example.invalid","automation@example.invalid","PUBLIC_AUTOMATION_OWNER","PUBLIC_SYNTHETIC_RAM_TOKEN")
        GoogleConnectionSession.installForHost(session);scoped=AccountStorageContext(context,session.ownerBinding())
        keys=SyntheticPilotIntegrationTest.HostKeys();source=GeneralLocalStoreTest.Source().apply{put()};drive=GoogleConnectionRegressionTest.FakeDrive()
        val key=SecretKeySpec(ByteArray(32){0x30},"AES")
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess{override fun existing()=key;override fun createForFirstEmptyStore()=key})
        settings.update{it.put("treeUri",source.rootUri)}
        // Initial synthetic setup only; production code never provisions an empty audio key.
        GeneralLocalStoreTest.host(scoped,keys).use {store->
            store.initializeEmpty();var elapsed=0L
            val selected=Selection(GeneralLocalStore.OWNER+"_automation_fixture",source.rootUri,1)
            NativeDiscoverySession(store,source,selected,settings::snapshot,Clock{Moment("PUBLIC_HOST_BOOT",elapsed)},
                {GeneralLocalStoreTest.NOW},true,kr.co.ieum.discovery.DiscoveryTypes.Config(10,10000,1000,25000000,2)).use { discovery ->
                discovery.cycle(1,1);elapsed=20;discovery.cycle(1,1);elapsed=40;discovery.cycle(1,1)
                assertEquals(1,store.snapshot().length());recording=store.snapshot().getJSONObject(0).getString("file_id")
                store.confirmTime(recording,store.snapshot().getJSONObject(0),at)
            }
        }
        RecordingWorkflowStore(scoped).use{it.confirmWork(recording,it.snapshot(recording),"합성 담당","합성 회사","합성 기존 업무","")}
        SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)
    }
    @After fun cleanup() {GoogleConnectionSession.resetForHost();DailyReportSchedule.installRegistryForHost(null);AutomationProviderRegistry.create=null;ScheduledDotProofRegistry.plan=null}
    private fun db()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun count(table:String)=db().use{it.query("SELECT COUNT(*) AS n FROM $table").single()["n"] as Long}
    private fun snap()=RecordingWorkflowStore(scoped).use{it.snapshot(recording)}
    private fun store()=AndroidAutomationStore(scoped,session.ownerBinding(),session.epoch(),{currentScope},{policy},{s->
        source.granted && s.getString("actual_document_id") !in source.excludedIds && settings.snapshot().optJSONObject("nativeSyntheticApprovals")?.length()?.let{it>0}==true
    },{"Etc/UTC"},{clock})
    private fun pipeline()=GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}){GeneralLocalStoreTest.host(it,keys)}
    private inner class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun driveAExport(job:String)=p.driveAExport(job);override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job)
        override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun poll(job:String)=p.poll(job)
        override fun retry(job:String):RecordingAnalysisPort.Sent=error("NO_AUTO_RETRY");override fun cancel(){};override fun close(){}
    }
    private fun requests(p:GoogleNativePipeline)=NativeAutomationRequests(context,session){NativePort(p)}
    private val credential="PUBLIC_SYNTHETIC_WORKER_DEVICE_CREDENTIAL"
    private fun canonical(x:JSONObject)=StrictJson.bytes(StrictJson.parse(x.toString().toByteArray(Charsets.UTF_8)))
    private fun proof(id:String,value:JSONObject){val dir=File("build/reports/verified-production-adapter").apply{mkdirs()};File(dir,"$id.json").writeText(value.put("scope","PRODUCTION_ADAPTER_SYNTHETIC_HTTP_NO_REAL_MODEL_OR_NETWORK").put("actualModelExecutions",0).put("actualExternalTransfers",0).put("actualDeviceOrOSNotification","NOT_RUN").toString(2))}
    private inner class ObservedWorkerHttp:AutomationProcessingHttp {
        var proofReads=0;var run:JSONObject?=null;var enrollmentCount=0;var wrongSource=false;var receivedHook:()->Unit={};var proofStatus=200;var nonExtractive=false;var proofTransform:(JSONObject)->JSONObject={it}
        override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
            if(path.endsWith("/capabilities"))return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.capabilities.v1","ownerId" to session.ownerBinding(),"driveAccount" to session.driveAccount(),"accountEpoch" to session.epoch(),"reportZone" to "Etc/UTC","resultKind" to AutomationV2Codec.KIND,"resultSchemaVersion" to 2L,"driveResultWritten" to false,"dotInvoked" to false,"canonicalWriteAllowed" to false,"automaticUsePolicy" to VerifiedAutomaticResults.POLICY,"executionEvidenceSchema" to VerifiedAutomaticResults.SCHEMA)))
            if(method=="PUT") {
                enrollmentCount++;val enrollment=JSONObject(String(body!!,Charsets.UTF_8));val frozen=enrollment.getJSONObject("handoff").getString("frozenRequestJson");val request=JSONObject(frozen)
                val input=JSONObject().put("enrollment",enrollment).put("deviceToken",credential).put("nonExtractive",nonExtractive)
                    .put("audioBase64",java.util.Base64.getEncoder().encodeToString(drive.bytes.getValue(request.getString("audioFileId"))))
                    .put("pendingBase64",java.util.Base64.getEncoder().encodeToString(drive.bytes.getValue(request.getString("resultFileId"))))
                val node=System.getenv("IEUM_NODE_BINARY") ?: error("EXISTING_NODE_RUNTIME_REQUIRED")
                val root=File("../..").canonicalFile;val output=File("build/reports/verified-production-adapter").apply{mkdirs()}
                val stdout=File(output,"worker-output.json");val stderr=File(output,"worker-stderr.txt")
                val builder=ProcessBuilder(node,File(root,"worker/tests/native-adapter-bridge.mjs").absolutePath).directory(root).redirectOutput(stdout).redirectError(stderr)
                builder.environment().clear();builder.environment()["LANG"]="en_US.UTF-8"
                val process=builder.start();process.outputStream.use{it.write(input.toString().toByteArray(Charsets.UTF_8))}
                if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();error("SYNTHETIC_PRODUCER_TIMEOUT")}
                check(process.exitValue()==0){"SYNTHETIC_PRODUCER_BRIDGE_FAILED"};run=JSONObject(stdout.readText())
                return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.job-status.v1","jobId" to request.getString("jobId"),"requestSha" to DriveRelay.sha256(frozen.toByteArray()),"enrollmentSha" to DriveRelay.sha256(body),"dotInvoked" to false,"driveResultWritten" to false,"state" to "RESULT_READY")))
            }
            if(path.endsWith("/verification")) {
                proofReads++
                if(proofStatus!=200)return AutomationHttpReply(proofStatus,byteArrayOf())
                return AutomationHttpReply(200,canonical(proofTransform(JSONObject(run!!.getJSONObject(if(wrongSource)"wrongSourceVerification" else "verification").toString()))))
            }
            check(path.endsWith("/result"));receivedHook();return AutomationHttpReply(200,canonical(run!!.getJSONObject("result")))
        }
    }
    private fun configured(p:GoogleNativePipeline,http:ObservedWorkerHttp,store:AndroidAutomationStore):AutomationProvider {
        val cfg=AutomationApiConfiguration(true,"https://public-fixture.example.invalid",session.ownerBinding(),session.driveAccount(),session.epoch(),"Etc/UTC",credential)
        AutomationConnectionBindings.bindApprovedProcessing(context,session,cfg)
        val processing=ProductionAutomationProcessingPort(context,session,{AutomationConnectionBindings.processing(context,session)},{ExistingDriveARequestPort(context,session){NativePort(p)}},{http},object:AutomationProposalReceiver{override fun receive(job:String,bytes:ByteArray,contextSha:String)=p.receiveAutomationProposal(job,bytes,contextSha)})
        return AutomationProcessingProvider(store,processing){cfg.validate(DriveALocalScope.current(session)!!);AutomationGate("READY","EXISTING_SYNTHETIC_APPROVAL_AND_WORKER_CONFIGURATION")}
    }
    private fun rowsIfPresent(table:String)=db().use{port->if(port.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isEmpty())0L else (port.query("SELECT COUNT(*) AS n FROM $table").single()["n"] as Long)}
    @Test fun G70Repeated503TimeoutRateLimitAndServerErrorsNeverPersistEvidenceOrRepublish()=runBlocking(Dispatchers.IO){
        val statuses=listOf(503,503,408,429,502);val http=ObservedWorkerHttp();val states=JSONArray()
        pipeline().use{p->store().use{s->
            val provider=configured(p,http,s)
            for(status in statuses){
                http.proofStatus=status
                val row=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()};states.put(row.getString("state"))
                assertEquals("WAITING_RESULT",row.getString("state"));assertEquals(0L,rowsIfPresent("automation_verified_evidence"));assertEquals(0L,rowsIfPresent("drive_profiles_receipts"));assertEquals(0L,count("automation_results"))
                assertEquals(1,http.enrollmentCount);assertEquals(4,drive.counter);clock+=30001
            }
            assertEquals(statuses.size,http.proofReads);assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"))
            proof("G70",JSONObject().put("httpStatuses",JSONArray(statuses)).put("states",states).put("proofRows",0).put("nativeResultReceipts",0).put("cumulativeHistory",0).put("tasks",0).put("enrollments",http.enrollmentCount).put("nativeIds",drive.counter).put("verificationReads",http.proofReads).put("originalLeaseRenewed",false))
        }}
    }
    @Test fun G71PermanentVerificationHttpFailureStopsWithoutPoisoningEvidence()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp().apply{proofStatus=403}
        pipeline().use{p->store().use{s->
            val provider=configured(p,http,s);val first=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            assertEquals("FAILED",first.getString("state"));assertEquals("VERIFICATION_HTTP_403_HELD",first.getString("reason"));assertEquals(0L,rowsIfPresent("automation_verified_evidence"));assertEquals(0L,rowsIfPresent("drive_profiles_receipts"));assertEquals(0L,count("automation_results"))
            http.proofStatus=200;clock+=900001;requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle()}
            assertEquals(1,http.proofReads);assertEquals(1,http.enrollmentCount);assertEquals(4,drive.counter);assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"))
            proof("G71",JSONObject().put("httpStatus",403).put("state",first.getString("state")).put("reason",first.getString("reason")).put("proofRows",0).put("nativeResultReceipts",0).put("automaticWrites",0).put("automaticRetriesAfterPermanentFailure",0).put("enrollments",1).put("nativeIds",4))
        }}
    }
    @Test fun G72Tampered200EvidenceRemainsImmutableWhenAValidSignatureArrivesLater()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp().apply{proofTransform={JSONObject(it.toString()).put("signature","0".repeat(64))}}
        pipeline().use{p->store().use{s->
            val provider=configured(p,http,s);val first=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            assertEquals("REVIEW_REQUIRED",first.getString("state"));assertEquals(1L,rowsIfPresent("automation_verified_evidence"))
            val original=db().use{it.query("SELECT proof,state FROM automation_verified_evidence").single()};val before=DriveRelay.sha256(original["proof"] as ByteArray);assertEquals("HELD",original["state"])
            http.proofTransform={it};clock+=900001
            val next=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            assertEquals("FAILED",next.getString("state"));assertEquals("AUTO_VERIFICATION_RECEIPT_CHANGED_REVIEW_REQUIRED",next.getString("reason"))
            val current=db().use{it.query("SELECT proof,state FROM automation_verified_evidence").single()};assertEquals(before,DriveRelay.sha256(current["proof"] as ByteArray));assertEquals("HELD",current["state"])
            assertEquals(1L,rowsIfPresent("automation_verified_evidence"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"));assertEquals(1,http.enrollmentCount);assertEquals(4,drive.counter)
            proof("G72",JSONObject().put("firstState",first.getString("state")).put("secondState",next.getString("state")).put("secondReason",next.getString("reason")).put("badProofBytesPreserved",true).put("badProofSha256",before).put("proofRows",1).put("cumulativeHistory",0).put("tasks",0).put("enrollments",1).put("nativeIds",4))
        }}
    }
}
