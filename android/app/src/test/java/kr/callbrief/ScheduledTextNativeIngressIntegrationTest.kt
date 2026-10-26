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

/** Production native discovery/request, private text readback, signed bundle, v5 receiver and Store.
 * Approved historical synthetic WAV; HTTP/Drive/provider responses are in-memory boundaries.
 * No locally constructed assurance and no current model, external service or OS execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduledTextNativeIngressIntegrationTest:ProtectedTextHostTestBase() {
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
        val audioFile=System.getenv("IEUM_HISTORICAL_SYNTHETIC_AUDIO")?.let(::File)
        Assume.assumeTrue("Approved historical synthetic WAV not provisioned; see docs/ASSETS.md",audioFile?.isFile==true)
        val audio=audioFile!!.readBytes()
        check(DriveRelay.sha256(audio)=="3ee1442ed8ac61b95ac1ff2985d2857126042b99141c05f7947c25ae4f7c56b5")
        val old=source.documents.getValue("one");source.bytes["one"]=audio;source.documents["one"]=SourceDocument(old.documentId,old.uri,old.name,audio.size.toLong(),old.mime,old.modified)
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
    private lateinit var textRegistration:DotScheduledTextExchangePort.Registration
    private lateinit var prepared:PreparedWhisperScheduledTextInput
    private var summary="";private var textResultSha=""
    private var nativePollPending=true;private var tamperBundle=false;private var textPosts=0;private var afterTextResponse:()->Unit={}
    private fun preparedBytes(name:String)=javaClass.getResourceAsStream("/local-stt/$name")!!.readBytes()
    private inner class ObservedWorkerHttp:AutomationProcessingHttp {
        var run:JSONObject?=null;var enrollmentCount=0;var wrongSource=false;var receivedHook:()->Unit={};var proofStatus=200;var nonExtractive=false;var proofTransform:(JSONObject)->JSONObject={it}
        override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
            if(path.endsWith("/capabilities"))return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.capabilities.v1","ownerId" to session.ownerBinding(),"driveAccount" to session.driveAccount(),"accountEpoch" to session.epoch(),"reportZone" to "Etc/UTC","resultKind" to AutomationV2Codec.KIND,"resultSchemaVersion" to 2L,"driveResultWritten" to false,"dotInvoked" to false,"canonicalWriteAllowed" to false,"automaticUsePolicy" to VerifiedAutomaticResults.POLICY,"executionEvidenceSchema" to VerifiedAutomaticResults.SCHEMA,"scheduledTextIngressSchema" to ScheduledNativeTextContract.LINK_SCHEMA)))
            if(method=="PUT") {
                enrollmentCount++;val enrollment=JSONObject(String(body!!,Charsets.UTF_8));val frozen=enrollment.getJSONObject("handoff").getString("frozenRequestJson");val request=JSONObject(frozen)
                val input=JSONObject().put("enrollment",enrollment).put("deviceToken",credential).put("nonExtractive",nonExtractive)
                    .put("audioBase64",java.util.Base64.getEncoder().encodeToString(drive.bytes.getValue(request.getString("audioFileId"))))
                    .put("pendingBase64",java.util.Base64.getEncoder().encodeToString(drive.bytes.getValue(request.getString("resultFileId"))))
                input.put("transcript",JSONObject(String(prepared.forJob(prepared.jobId),Charsets.UTF_8)).getJSONArray("transcript").let{array->(0 until array.length()).joinToString("\n"){array.getJSONObject(it).getString("text")}}).put("summary",summary)
                val extras=JSONArray()
                for(id in listOf(textRegistration.folder,textRegistration.input,textRegistration.result)) {
                    val spec=drive.specs.getValue(id);val bytes=drive.bytes.getValue(id)
                    extras.put(JSONObject().put("id",id).put("base64",java.util.Base64.getEncoder().encodeToString(bytes)).put("metadata",JSONObject().put("id",id).put("mimeType",spec.mimeType).put("parents",JSONArray(if(id==textRegistration.folder)listOf("root") else listOf(textRegistration.folder))).put("owners",JSONArray().put(JSONObject().put("permissionId",session.ownerBinding()))).put("permissions",JSONArray().put(JSONObject().put("id",session.ownerBinding()).put("type","user").put("role","owner"))).put("trashed",false).put("isAppAuthorized",true).put("size",bytes.size.toString()).put("version",drive.versions.getValue(id).toString())))
                }
                input.put("extraFiles",extras).put("scheduledText",JSONObject(link(request.getString("jobId"))))
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
            if(path.endsWith("/scheduled-text")){
                textPosts++;val bundle=JSONObject(run!!.getJSONObject("scheduledBundle").toString());val boundLink=bundle.getJSONObject("body").getJSONObject("link")
                assertEquals("POST",method);assertEquals("/v1/automation/jobs/"+boundLink.getString("jobId")+"/scheduled-text",path)
                assertArrayEquals(canonical(boundLink),body ?: error("TEXT_LINK_BODY_REQUIRED"))
                if(tamperBundle)bundle.put("signature","0".repeat(64));afterTextResponse();return AutomationHttpReply(200,canonical(bundle))
            }
            if(path.endsWith("/verification")) {
                if(proofStatus!=200)return AutomationHttpReply(proofStatus,byteArrayOf())
                return AutomationHttpReply(200,canonical(proofTransform(JSONObject(run!!.getJSONObject(if(wrongSource)"wrongSourceVerification" else "verification").toString()))))
            }
            check(path.endsWith("/result"));if(nativePollPending)return AutomationHttpReply(204,byteArrayOf());receivedHook();return AutomationHttpReply(200,canonical(run!!.getJSONObject("result")))
        }
    }
    private fun configured(p:GoogleNativePipeline,http:ObservedWorkerHttp,store:AndroidAutomationStore):AutomationProvider {
        val cfg=AutomationApiConfiguration(true,"https://public-fixture.example.invalid",session.ownerBinding(),session.driveAccount(),session.epoch(),"Etc/UTC",credential)
        AutomationConnectionBindings.bindApprovedProcessing(context,session,cfg)
        val processing=ProductionAutomationProcessingPort(context,session,{AutomationConnectionBindings.processing(context,session)},{ExistingDriveARequestPort(context,session){NativePort(p)}},{http},object:AutomationProposalReceiver{override fun receive(job:String,bytes:ByteArray,contextSha:String)=p.receiveAutomationProposal(job,bytes,contextSha)})
        return AutomationProcessingProvider(store,processing){cfg.validate(DriveALocalScope.current(session)!!);AutomationGate("READY","EXISTING_SYNTHETIC_APPROVAL_AND_WORKER_CONFIGURATION")}
    }
    private fun link(nativeJob:String)=mapOf("schema" to ScheduledNativeTextContract.LINK_SCHEMA,"jobId" to nativeJob,"proofJob" to textRegistration.job,"folder" to textRegistration.folder,"input" to textRegistration.input,"result" to textRegistration.result,"inputSha" to textRegistration.inputSha,"resultSha" to textResultSha,"audioSha" to prepared.audioSha256,"preparedEvidenceSha" to prepared.evidenceSha)
    private fun prepareText() {
        val evidence=preparedBytes("stt-request-evidence.json");prepared=PreparedWhisperScheduledTextInput(preparedBytes("scheduled-input.json"),evidence,DriveRelay.sha256(evidence))
        val queue="PUBLIC_APPROVED_TEXT_NATIVE_QUEUE";drive.specs[queue]=DriveTransport.FileSpec(queue,null,"public",DriveTransport.FOLDER_MIME,session.ownerBinding(),DriveTransport.Role.PRIVATE_FOLDER);drive.bytes[queue]=byteArrayOf();drive.versions[queue]=1
        val publisher=DotScheduledTextExchangePort(context,session,{DotTextExchangeConfiguration(true,queue)},drive,preparedSyntheticInput={prepared}){Instant.now().minusSeconds(2)}
        textRegistration=publisher.publishFixedSynthetic(prepared.jobId).registration!!
        summary=JSONObject(String(prepared.forJob(prepared.jobId),Charsets.UTF_8)).getJSONArray("transcript").getJSONObject(0).getString("text").take(180)
        val output=JSONObject().put("schema_version","scheduler-proof-v1").put("job_id",prepared.jobId).put("synthetic",true).put("source_file_id",textRegistration.input).put("produced_at_utc",Instant.now().minusMillis(1).toString()).put("summary",summary)
            .put("tasks",JSONArray().put(JSONObject().put("title",summary).put("owner","미확인").put("status","needs_confirmation").put("due",JSONObject.NULL).put("timing_text",summary).put("evidence",JSONObject().put("source_id","synthetic_call001").put("quote",summary))))
        val bytes=output.toString().toByteArray();drive.bytes[textRegistration.result]=bytes;drive.versions[textRegistration.result]=2;textResultSha=DriveRelay.sha256(bytes)
    }
    private fun textCoordinator(ingress:ScheduledNativeTextIngress)=ScheduledDotProofCoordinator(context,session,ScheduledDotProofPlan(true,prepared.jobId,textRegistration.folder,ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC,textRegistration.inputSha,prepared.evidenceSha),NativeScheduledDotProofPort(DotScheduledTextExchangePort(context,session,{DotTextExchangeConfiguration(true,textRegistration.folder)},drive,preparedSyntheticInput={prepared})),preparedInput=prepared,nativeIngress={r,p->ingress.receive(prepared,r,p)})
    private fun ingress(p:GoogleNativePipeline,http:ObservedWorkerHttp)=ScheduledNativeTextIngress(context,session,{store()},{ProductionAutomationProcessingPort(context,session,{AutomationConnectionBindings.processing(context,session)},{ExistingDriveARequestPort(context,session){NativePort(p)}},{http},object:AutomationProposalReceiver{override fun receive(job:String,bytes:ByteArray,contextSha:String)=p.receiveAutomationProposal(job,bytes,contextSha)})})
    @Test fun T81ActualPrivateTextReadbackUsesProductionBindingAndV5AdapterToAccumulateAndReport()=runBlocking(Dispatchers.IO){
        prepareText();val http=ObservedWorkerHttp();pipeline().use{p->
            store().use{s->val provider=configured(p,http,s);val row=requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle().single()};assertEquals("WAITING_RESULT",row.getString("state"))}
            val read=textCoordinator(ingress(p,http)).cycle();val accepted=read.getJSONObject("nativeTextIngress");assertEquals("APPLIED_SAFE_DERIVED",accepted.getString("state"));assertFalse(read.getBoolean("nativeAudioRequestCompleted"));assertFalse(read.getBoolean("sttExecuted"));assertEquals(1,textPosts)
            File("build/reports/verified-production-adapter/T81_SIGNED_NATIVE_TEXT_BUNDLE.json").writeText(http.run!!.getJSONObject("scheduledBundle").toString(2))
            assertEquals(1L,count("automation_cumulative_history"));assertEquals(1L,count("workflow_items"));assertEquals(1L,count("automation_verified_evidence"));assertEquals(1,http.enrollmentCount);assertEquals(7,drive.counter)
            store().use{s->val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"));val batch=s.reportBatch(window);assertTrue(batch.getBoolean("complete"));var posts=0;val notify=object:LocalReportNotificationPort{override fun gate()="READY";override fun post(key:String,date:LocalDate){posts++}};val report=AutomationLocalReportCoordinator(s,notify){true};assertEquals("LOCAL_NOTIFY_REQUESTED",report.run(window,batch,window.due.plusSeconds(1)).getString("state"));assertEquals(1,posts)}
            textCoordinator(ingress(p,http)).cycle();assertEquals(1L,count("automation_cumulative_history"));assertEquals(1L,count("workflow_items"));assertEquals(7,drive.counter)
            proof("T81",JSONObject().put("readback",read).put("nativeEnrollments",1).put("nativeAndTextIds",7).put("cumulativeHistory",1).put("tasks",1).put("localReportCopies",1).put("actualNewSTT","NOT_RUN_PREVIOUS_APPROVED_AUDIO_AND_SYNTHETIC_HTTP"))
        }
    }
    @Test fun T82OrdinaryUnboundTextDoesNotCreateNativeRequestsOrAutomaticAuthority()=runBlocking(Dispatchers.IO){
        prepareText();val read=textCoordinator(ScheduledNativeTextIngress(context,session,{store()})).cycle()
        assertEquals("WAITING_UNIQUE_NATIVE_TEXT_BINDING",read.getJSONObject("nativeTextIngress").getString("state"));assertEquals(0,textPosts);assertEquals(0L,count("automation_cumulative_history"));assertEquals(3,drive.counter)
        proof("T82",JSONObject().put("unboundHeld",true).put("newNativeRequests",0).put("automaticWrites",0))
    }
    @Test fun T83TamperedOuterBindingCannotReachV5EvidenceOrAutomaticStore()=runBlocking(Dispatchers.IO){
        prepareText();tamperBundle=true;val http=ObservedWorkerHttp();pipeline().use{p->
            store().use{s->val provider=configured(p,http,s);requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle()}}
            val read=textCoordinator(ingress(p,http)).cycle();assertEquals("HELD",read.getJSONObject("nativeTextIngress").getString("state"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"));assertEquals(1,http.enrollmentCount)
            proof("T83",JSONObject().put("wrongBindingSignatureHeld",true).put("automaticWrites",0).put("nativeEnrollments",1))
        }
    }
    @Test fun T84SourceRevocationDuringTextResponsePreventsNativeAutomaticAdmission()=runBlocking(Dispatchers.IO){
        prepareText();val http=ObservedWorkerHttp();pipeline().use{p->
            store().use{s->val provider=configured(p,http,s);requests(p).use{AndroidAutomationCoordinator(s,it,provider).cycle()}}
            afterTextResponse={source.granted=false}
            val read=textCoordinator(ingress(p,http)).cycle();assertNotEquals("APPLIED_SAFE_DERIVED",read.getJSONObject("nativeTextIngress").getString("state"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"));assertEquals(1,http.enrollmentCount);assertEquals(7,drive.counter)
            proof("T84",JSONObject().put("sourceRevocationHeld",true).put("automaticWrites",0).put("nativeEnrollments",1).put("sourceLeaseRenewed",false))
        }
    }

}
