package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import javax.crypto.spec.SecretKeySpec

/** Production Android pipeline/journal/inbox; generated WAV and fake HTTP/Drive, no live grant or model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AutomationApiProcessingIntegrationTest:ProtectedTextHostTestBase() {
    private class Memory:GoogleConnectionSession.Persistence {var saved=JSONObject();override fun read()=JSONObject(saved.toString());override fun write(value:JSONObject){saved=JSONObject(value.toString())}}
    private fun session()=GoogleConnectionSession(Memory()).also{val e=it.begin();it.activate(e,"PUBLIC_API@example.invalid","PUBLIC_API@example.invalid","PUBLIC_API_OWNER","PUBLIC_TEST_MEMORY_TOKEN")}
    @Test fun defaultDisconnectedProcessorDoesNotOpenSourcesOrTransport() {
        val ctx:Context=RuntimeEnvironment.getApplication();var calls=0
        val port=ProductionAutomationProcessingPort(ctx,GoogleConnectionSession(Memory()),source={calls++;error("SOURCE_MUST_STAY_CLOSED")},http={calls++;error("NO_HTTP")})
        assertEquals("PROCESSOR_NOT_CONFIGURED",port.enrollPublished("public",JSONObject()).state);assertEquals("PROCESSOR_NOT_CONFIGURED",port.pollPublished("public").state);assertEquals(0,calls)
        assertFalse(NativeAnalysisAvailability.canTransfer())
    }
    @Test fun nodeProducedProfileHasIdenticalJavaDigestAndRejectsOtherBindingOrCompletion() {
        val r=javaClass.getResourceAsStream("/automation-proofs/api-request-v0.json")!!.readBytes()
        val b=javaClass.getResourceAsStream("/automation-proofs/api-result-v2.json")!!.readBytes()
        val decoded=AutomationV2Codec().validate(b,r);assertEquals("needs_confirmation",JSONObject(decoded.analysisJson).getJSONArray("tasks").getJSONObject(0).getString("status"))
        for(mode in listOf("OWNER","QUOTE","DONE","CANONICAL")) {
            val x=JSONObject(String(b,Charsets.UTF_8));when(mode){"OWNER"->x.getJSONObject("ownerBinding").put("ownerId","PUBLIC_OTHER");"QUOTE"->x.getJSONObject("analysis").getJSONArray("tasks").getJSONObject(0).put("quote","made up");"DONE"->x.getJSONObject("analysis").getJSONArray("tasks").getJSONObject(0).put("status","done");"CANONICAL"->x.put("canonicalWriteAllowed",true)}
            val map=StrictJson.`object`(StrictJson.parse(x.toString().toByteArray())).toMutableMap();map["resultDigest"]=AutomationV2Codec.digest(map)
            try{AutomationV2Codec().validate(StrictJson.bytes(map),r);fail(mode)}catch(_:IllegalArgumentException){}
        }
    }
    private inner class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun poll(job:String)=p.poll(job);override fun pollObservation(job:String)=p.pollObservation(job)
        override fun driveAExport(job:String)=p.driveAExport(job);override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job);override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun retry(job:String):RecordingAnalysisPort.Sent=error("NO_RETRY");override fun cancel(){};override fun close(){}
    }
    @Test fun publishedNativeRequestToApiReadbackPreservesManualDoneAndDriveResult()=runBlocking(Dispatchers.IO) {
        val ctx:Context=RuntimeEnvironment.getApplication();val session=session();val scoped=AccountStorageContext(ctx,session.ownerBinding());val keys=SyntheticPilotIntegrationTest.HostKeys()
        val source=GeneralLocalStoreTest.Source().apply{put()};val drive=GoogleConnectionRegressionTest.FakeDrive()
        val key=SecretKeySpec(ByteArray(32){0x39},"AES");val settings=SecureSettings(scoped,object:SecureSettings.KeyAccess{override fun existing()=key;override fun createForFirstEmptyStore()=key});settings.update{it.put("treeUri",source.rootUri)}
        val recording=GeneralLocalStoreTest.host(scoped,keys).use{s->s.initializeEmpty();s.importSelected(source);val row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,"PUBLIC_USER_DONE","done");row.getString("file_id")}
        RecordingWorkflowStore(scoped).use{it.confirmWork(recording,it.snapshot(recording),"PUBLIC_PERSON","PUBLIC_COMPANY","PUBLIC_CASE","")}
        SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)
        GoogleNativePipeline(ctx,session,settings,{source},drive,postingCapability={true}){GeneralLocalStoreTest.host(it,keys)}.use { p ->
            val request=AnalysisRequestFlow(ctx,session,{NativePort(p)}).use{flow->val id=RecordingWorkflowStore(scoped).use{flow.prepare(recording,it.snapshot(recording))};assertEquals("SENT",flow.submit(id));id}
            val job=p.jobs().single();val initialDrive=drive.bytes[job.resultId]!!.clone();var prepared:Map<String,Any?>?=null
            val c=AutomationApiConfiguration(true,"https://PUBLIC-test.example.invalid",session.ownerBinding(),session.driveAccount(),session.epoch(),"Etc/UTC","PUBLIC_TEST_ONLY_DEVICE_CREDENTIAL_32_CHARS")
            val transport=object:AutomationProcessingHttp {
                override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
                    if(path.endsWith("capabilities"))return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.capabilities.v1","ownerId" to c.owner,"driveAccount" to c.account,"accountEpoch" to c.epoch,"reportZone" to c.reportZone,"resultKind" to AutomationV2Codec.KIND,"resultSchemaVersion" to 2L,"driveResultWritten" to false,"dotInvoked" to false,"canonicalWriteAllowed" to false)))
                    if(method=="PUT") {prepared=StrictJson.`object`(StrictJson.parse(body!!));val h=StrictJson.`object`(prepared!!["handoff"]);return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.job-status.v1","jobId" to job.id,"state" to "QUEUED","requestSha" to h["frozenRequestSha256"],"enrollmentSha" to DriveRelay.sha256(body),"dotInvoked" to false,"driveResultWritten" to false)))}
                    val h=StrictJson.`object`(prepared!!["handoff"]);val r=WireV0Codec.validateRequest((h["frozenRequestJson"] as String).toByteArray());val raw="PUBLIC_SYNTHETIC_TEXT"
                    val x=linkedMapOf<String,Any?>("schemaVersion" to 2L,"requestSchemaVersion" to 0L,"kind" to AutomationV2Codec.KIND,"state" to "PROPOSAL","resultRevision" to 1L,"businessContextSha" to DriveRelay.sha256(StrictJson.bytes(prepared!!["businessContext"])),"stt" to mapOf("provider" to "OPENAI_API","model" to "TEST_ONLY_MODEL","requestId" to "TEST_ONLY_RESPONSE","text" to raw,"textSha" to DriveRelay.sha256(raw.toByteArray())),"analysis" to mapOf("provider" to "OPENAI_API","model" to "TEST_ONLY_MODEL","requestId" to "TEST_ONLY_RESPONSE","summary" to "PUBLIC_PROPOSAL_ONLY","cumulativeSummary" to "PUBLIC_CUMULATIVE_PROPOSAL","tasks" to emptyList<Any>()),"dotExecution" to "NOT_INVOKED","executionEvidence" to "PROVIDER_RESPONSES_RECORDED_NOT_INDEPENDENTLY_ATTESTED","deliveryChannel" to "AUTHENTICATED_WORKER_ENDPOINT","driveResultWritten" to false,"writerVerification" to "UNRESOLVED","nativeBinding" to "UNVERIFIED","displayMode" to "READ_ONLY_PROPOSAL","canonicalWriteAllowed" to false,"applied" to false)
                    for(k in listOf("jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest"))x[k]=r[k]
                    x["resultDigest"]=AutomationV2Codec.digest(x);return AutomationHttpReply(200,StrictJson.bytes(x))
                }
            }
            val business=JSONObject().put("caseId",JSONObject(String(p.driveAFrozenRequest(job.id))).getString("contextJson").let{JSONObject(it).getString("caseId")}).put("revision",0L).put("proposedSummary","").put("tasks",org.json.JSONArray())
            val port=ProductionAutomationProcessingPort(ctx,session,{c},{ExistingDriveARequestPort(ctx,session){NativePort(p)}},{transport},object:AutomationProposalReceiver{override fun receive(job:String,bytes:ByteArray,contextSha:String)=p.receiveAutomationProposal(job,bytes,contextSha)})
            assertEquals("REGISTERED",port.enrollPublished(request,business).state)
            val outcome=port.pollPublished(request);assertEquals("PROVENANCE_PENDING",outcome.state);assertNotNull(outcome.observation)
            assertEquals("DUPLICATE_PROPOSAL",port.pollPublished(request).state);assertArrayEquals(initialDrive,drive.bytes[job.resultId])
            val row=RecordingWorkflowStore(scoped).use{it.snapshot(recording)};assertEquals("done",row.getString("manual_status"));assertEquals("PUBLIC_USER_DONE",row.getString("manual_text"));assertTrue(row.isNull("effective_summary_version"))
            AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{d->assertEquals(0L,d.query("SELECT count(*) AS n FROM summary_versions").single()["n"]);assertEquals(1L,d.query("SELECT count(*) AS n FROM drive_profiles_receipts WHERE schema_version=2").single()["n"])}
            assertFalse(outcome.dotInvoked);assertFalse(outcome.canonicalWriteAllowed);assertFalse(outcome.writerVerified);assertFalse(outcome.driveResultWritten)
        }
    }
}
