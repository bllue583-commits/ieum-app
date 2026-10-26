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
class ProductionVerifiedAutomaticUseIntegrationTest:ProtectedTextHostTestBase() {
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
        var run:JSONObject?=null;var enrollmentCount=0;var wrongSource=false;var receivedHook:()->Unit={};var proofStatus=200;var nonExtractive=false;var proofTransform:(JSONObject)->JSONObject={it}
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
    @Test fun F51ProductionSignedEvidenceAdapterReceivesAccumulatesAndCreates06ReportWithoutFixtureAssurance()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp();pipeline().use{p->store().use{s->
            val provider=configured(p,http,s);val first=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            assertEquals("APPLIED_SAFE_DERIVED",first.getString("state"));assertEquals(1,http.enrollmentCount)
            assertEquals(1L,count("drive_profiles_receipts"));assertEquals(1L,count("automation_verified_evidence"));assertEquals(1L,count("automation_results"));assertEquals(1L,count("automation_cumulative_history"));assertEquals(1L,count("workflow_items"))
            val assurance=db().use{it.query("SELECT assurance FROM automation_results").single()["assurance"].toString()}.let(::JSONObject)
            assertEquals(VerifiedAutomaticResults.ORIGIN,assurance.getString("origin"));assertTrue(assurance.getString("evidenceRef").matches(Regex("[0-9a-f]{64}")))
            for(flag in listOf("verifiedSource","executionVerified","semanticReviewVerified","privacyReviewed","automaticUseAllowed"))assertTrue(flag,assurance.getBoolean(flag))
            val raw=http.run!!.getJSONObject("result");assertFalse(raw.getBoolean("canonicalWriteAllowed"));assertEquals("NOT_INVOKED",raw.getString("dotExecution"))
            val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"));val batch=s.reportBatch(window);assertTrue(batch.getBoolean("complete"));assertEquals(1,batch.getJSONArray("automatic").length())
            val reportScope=DailyReportRuntime.scope(context);DailyReportLedger(reportScope.context,reportScope.profile).use{it.setEnabled(true,Instant.ofEpochMilli(clock),ZoneId.of("Etc/UTC"))}
            DailyReportSchedule.installRegistryForHost(object:DailyReportWorkRegistry{override fun cancel(tag:String){};override fun periodic(name:String,request:androidx.work.PeriodicWorkRequest){};override fun once(name:String,request:androidx.work.OneTimeWorkRequest){}})
            AndroidAutomationRuntime.bridgeReportQueue(context,s);assertTrue(s.reportQueue().single().getString("report_id").isNotBlank())
            var posts=0;val notice=object:LocalReportNotificationPort{override fun gate()="READY";override fun post(key:String,date:LocalDate){posts++}}
            val reports=AutomationLocalReportCoordinator(s,notice){true};assertEquals("LOCAL_NOTIFY_REQUESTED",reports.run(window,batch,window.due.plusSeconds(1)).getString("state"));reports.run(window,batch,window.due.plusSeconds(60));assertEquals(1,posts)
            clock+=900001;requests(p).use{assertEquals("APPLIED_SAFE_DERIVED",AndroidAutomationCoordinator(s,it,provider).cycle().single().getString("state"))}
            assertEquals(1,http.enrollmentCount);assertEquals(4,drive.counter);assertEquals(1L,count("automation_cumulative_history"));assertEquals(1L,count("workflow_items"))
            proof("F51",JSONObject().put("adapter",VerifiedAutomaticResults.ORIGIN).put("state",first.getString("state")).put("executionEvidence",assurance).put("rawPayloadCanonicalWriteAllowed",false).put("productionObservedStageReceipts",2).put("nativeReceipt",1).put("cumulativeHistory",1).put("tasks",1).put("local06ReportCopies",1).put("notifierCalls",1).put("duplicateChanges",0).put("actualNewSTT","NOT_RUN_SYNTHETIC_HTTP_RESPONSES_ONLY"))
        }}
    }
    @Test fun F52AuthenticatedWrongSourceEvidenceStaysReadonlyAndCannotBeReplacedToPromoteSameVersion()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp().apply{wrongSource=true};pipeline().use{p->store().use{s->
            val provider=configured(p,http,s);val first=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            assertEquals("REVIEW_REQUIRED",first.getString("state"));assertEquals(1L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"))
            http.wrongSource=false;clock+=900001
            requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle()}
            assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"));assertEquals(1L,count("automation_verified_evidence"));assertEquals(1,http.enrollmentCount);assertEquals(4,drive.counter)
            proof("F52",JSONObject().put("validSignatureWrongSourceVersionHeld",true).put("proofReplacementRejected",true).put("automaticWrites",0).put("extraRequests",0))
        }}
    }
    @Test fun F53VerifiedProductionReceiptCannotOverwriteUserDoneOrManualNote()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp();var changed=false
        http.receivedHook={if(!changed){changed=true;GeneralLocalStoreTest.host(scoped,keys).use{it.edit(recording,it.snapshot().getJSONObject(0),"PUBLIC_USER_NOTE_PRESERVE","done")}}}
        pipeline().use{p->store().use{s->
            val provider=configured(p,http,s);val row=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            assertEquals("REVIEW_REQUIRED",row.getString("state"));assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_USER_NOTE_PRESERVE",snap().getString("manual_text"))
            assertEquals(1L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"))
            proof("F53",JSONObject().put("signedProductionReceiptPresent",true).put("manualDonePreserved",true).put("manualNotePreserved",true).put("automaticWrites",0))
        }}
    }
    // Independent transport-only adversarial extensions. Production files remain unchanged.
    private fun resignForIndependentTest(proof:JSONObject,change:(JSONObject)->Unit):JSONObject {
        val copy=JSONObject(proof.toString());change(copy.getJSONObject("body"))
        val mac=javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(credential.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        copy.put("signature",mac.doFinal("IEUM_AUTOMATIC_USE_V1\n".toByteArray(Charsets.UTF_8)+canonical(copy.getJSONObject("body"))).joinToString(""){"%02x".format(it.toInt() and 255)})
        return copy
    }
    private fun independentHeld(id:String,http:ObservedWorkerHttp)=runBlocking(Dispatchers.IO){
        pipeline().use{p->store().use{s->
            val provider=configured(p,http,s)
            val row=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            proof(id,JSONObject().put("state",row.getString("state")).put("reason",row.getString("reason"))
                .put("cumulativeHistory",count("automation_cumulative_history")).put("tasks",count("workflow_items")))
            assertNotEquals("APPLIED_SAFE_DERIVED",row.getString("state"))
            assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"))
        }}
    }
    @Test fun I61InvalidHmacCannotEnableAutomaticUse(){
        val http=ObservedWorkerHttp().apply{proofTransform={p->JSONObject(p.toString()).put("signature","0".repeat(64))}}
        independentHeld("I61",http)
    }
    @Test fun I62ValidHmacWrongOriginalRequestShaIsHeld(){
        val http=ObservedWorkerHttp().apply{proofTransform={p->resignForIndependentTest(p){it.put("requestSha","0".repeat(64))}}}
        independentHeld("I62",http)
    }
    @Test fun I63ValidHmacWrongTranscriptShaIsHeld(){
        val http=ObservedWorkerHttp().apply{proofTransform={p->resignForIndependentTest(p){it.put("transcriptSha","0".repeat(64))}}}
        independentHeld("I63",http)
    }
    @Test fun I64ValidHmacWithoutDoneProducerStageIsHeld(){
        val http=ObservedWorkerHttp().apply{proofTransform={p->resignForIndependentTest(p){it.getJSONArray("stages").getJSONObject(0).put("status","STARTED")}}}
        independentHeld("I64",http)
    }
    @Test fun I65ProducerSignedNonExtractiveSummaryCannotApply(){
        independentHeld("I65",ObservedWorkerHttp().apply{nonExtractive=true})
    }
    @Test fun I66CurrentGrantWithdrawalAfterResponsePreventsAutomaticWrites(){
        val http=ObservedWorkerHttp().apply{receivedHook={source.granted=false}}
        independentHeld("I66",http)
    }
    @Test fun I67ManualNoteWithoutDonePreventsAutomaticWrites()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp();var changed=false
        http.receivedHook={if(!changed){changed=true;GeneralLocalStoreTest.host(scoped,keys).use{it.edit(recording,it.snapshot().getJSONObject(0),"INDEPENDENT_MEMO_ONLY","open")}}}
        independentHeld("I67",http)
        assertEquals("open",snap().getString("manual_status"));assertEquals("INDEPENDENT_MEMO_ONLY",snap().getString("manual_text"))
    }
    @Test fun I68CurrentExclusionAfterResponsePreventsAutomaticWrites(){
        val http=ObservedWorkerHttp().apply{receivedHook={source.excludedIds.add("one")}}
        independentHeld("I68",http)
    }
    @Test fun I69TransientVerification503MustRecoverSameResultWithoutRepublish()=runBlocking(Dispatchers.IO){
        val http=ObservedWorkerHttp().apply{proofStatus=503}
        pipeline().use{p->store().use{s->
            val provider=configured(p,http,s)
            val first=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            http.proofStatus=200;clock+=900001
            val second=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()}
            proof("I69",JSONObject().put("firstState",first.getString("state")).put("firstReason",first.getString("reason"))
                .put("secondState",second.getString("state")).put("secondReason",second.getString("reason"))
                .put("proofRows",count("automation_verified_evidence")).put("cumulativeHistory",count("automation_cumulative_history"))
                .put("tasks",count("workflow_items")).put("enrollments",http.enrollmentCount).put("nativeIds",drive.counter))
            assertEquals(1,http.enrollmentCount);assertEquals(4,drive.counter)
            assertEquals("Transient verification transport failure should not permanently pin absent evidence", "APPLIED_SAFE_DERIVED",second.getString("state"))
            assertEquals(1L,count("automation_cumulative_history"))
        }}
    }

}
