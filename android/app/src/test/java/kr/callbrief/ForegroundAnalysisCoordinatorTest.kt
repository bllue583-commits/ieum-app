package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kotlinx.coroutines.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

/** Production coordinator/native protected SQLite/JCE/request/profile intake, generated audio
 * and in-memory Drive/processor only. STT/high execution is explicitly FIXTURE, not observed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ForegroundAnalysisCoordinatorTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys;private lateinit var source:GeneralLocalStoreTest.Source
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private var recording="";private val offers=AtomicInteger();private var prepared=""
    private class Memory:GoogleConnectionSession.Persistence { var v=JSONObject();override fun read()=JSONObject(v.toString());override fun write(value:JSONObject){v=JSONObject(value.toString())} }
    private fun activate(){val e=session.begin();session.activate(e,"coordinator@example.invalid","coordinator@example.invalid","PUBLIC_FOREGROUND_OWNER","PUBLIC_FIXTURE_RAM_TOKEN")}
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory());activate()
        scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys()
        source=GeneralLocalStoreTest.Source().apply { put() };drive=GoogleConnectionRegressionTest.FakeDrive()
        val k=SecretKeySpec(ByteArray(32){0x25},"AES")
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess {override fun existing()=k;override fun createForFirstEmptyStore()=k})
        settings.update { it.put("treeUri",source.rootUri) }
        GeneralLocalStoreTest.host(scoped,keys).use { s->s.initializeEmpty();s.importSelected(source);val r=s.snapshot().getJSONObject(0);recording=r.getString("file_id");s.edit(recording,r,"PUBLIC_FOREGROUND_MANUAL_DONE","done") }
        SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)
    }
    private fun snap()=RecordingWorkflowStore(scoped).use { it.snapshot(recording) }
    private fun db()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun count(name:String)=db().use { it.query("SELECT COUNT(*) AS n FROM $name").single()["n"] as Long }
    private fun pipeline(remote:DriveTransport=drive,capable:Boolean=true)=GoogleNativePipeline(context,session,settings,{source},remote,
        postingCapability=if(capable)({_->true})else FrozenDotCapability(context)::supports) { GeneralLocalStoreTest.host(it,keys) }
    private inner class NativePort(val p:GoogleNativePipeline,val handoff:((String,String)->Unit)?):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override fun poll(job:String)=p.poll(job)
        override fun pollObservation(job:String)=p.pollObservation(job)
        override fun syncObservation(job:String)=db().use { d->
            d.requireProtectedPersistence();val sync=Code16DotManualSync(session,p,d,handoff)
            val result=sync.syncExplicitSynthetic(setOf(job),1).single()
            RecordingAnalysisPort.SyncObservation(result.state,sync.resultObservation(job))
        }
        override fun retry(job:String)=error("NO_AUTOMATIC_RETRY")
        override fun jobs()=p.jobs();override fun cancel()=p.cancel();override fun close(){}
    }
    private fun flow(p:GoogleNativePipeline,hook:((String,String)->Unit)?=null)=AnalysisRequestFlow(context,session,{NativePort(p,hook)})
    private fun resultFile(job:String)=db().use { d->val frozen=d.query("SELECT frozen FROM drive_v0_jobs WHERE job_id=?",job).single()["frozen"] as ByteArray
        JSONObject(JSONObject(String(frozen,Charsets.UTF_8)).getString("requestJson")).getString("resultFileId") }
    private fun proposal(job:String) {
        val file=resultFile(job);val request=drive.bytes.values.mapNotNull { runCatching { StrictJson.`object`(StrictJson.parse(it)) }.getOrNull() }
            .single { it["kind"]=="CALL_ANALYSIS_REQUEST" && it["resultFileId"]==file }
        val raw="합성 비동기 처리의 원문입니다.";val sha=DriveRelay.sha256(raw.toByteArray())
        val provenance=mapOf("source_kind" to "synthetic_contract_fixture","language" to "ko","run_id" to "foreground_host_fixture","audio_sha256" to request["audioSha"],
            "model_name" to null,"model_sha256" to null,"engine_sha256" to null,"execution_observed" to false,"reference_used_in_inference" to false,"quality_state" to "raw_review_required",
            "run_started_at_utc" to null,"run_finished_at_utc" to null,"runner_sha256" to null,"source_commit" to null,"dataset" to null,"dataset_revision" to null,"license" to null)
        val src=mapOf("source_id" to request["sourceId"],"kind" to "call_transcript","raw_text" to raw,"text_sha256" to sha,"recorded_at" to null,"timestamp_basis" to "unknown",
            "case_id" to null,"case_link_confirmed" to false,"speaker_assignment_verified" to false,"uncertain_ranges" to emptyList<Any>(),"provenance" to provenance)
        val packet=linkedMapOf<String,Any?>("schema_version" to 1L,"job_id" to request["jobId"],"base_revision" to request["baseRevision"],"scope" to "synthetic_contract_test",
            "offset_unit" to "unicode_code_points","automatic_summary_allowed" to false,"root_semantic_review_required" to true,"sources" to listOf(src))
        packet["source_pack_sha256"]=DriveRelay.sha256(StrictJson.bytes(packet))
        val candidate=mapOf("schema_version" to 1L,"job_id" to request["jobId"],"base_revision" to request["baseRevision"],"source_pack_sha256" to packet["source_pack_sha256"],
            "summary" to "합성 모의 처리 제안 · 모델 실행 아님","claims" to emptyList<Any>())
        val value=WireV0Codec.placeholder(request).toMutableMap();value["state"]="PROPOSAL";value["resultRevision"]=1L
        value["stt"]=mapOf("execution" to "FIXTURE","text" to raw,"textSha" to sha,"evidenceRef" to "foreground_host_fixture_only")
        value["high"]=mapOf("execution" to "FIXTURE","packetJson" to String(StrictJson.bytes(packet)),"candidateJson" to String(StrictJson.bytes(candidate)),"evidenceRef" to "foreground_host_fixture_only")
        value["resultDigest"]=WireV0Codec.digest("result",value)
        val bytes=StrictJson.bytes(value);assertTrue(WireV0Codec.candidateStructureValid(bytes,StrictJson.bytes(request)))
        drive.bytes[file]=bytes;drive.versions[file]=drive.versions.getValue(file)+1
    }
    private fun processor(action:(String)->Unit={proposal(it)}):(String,String)->Unit={job,registration->
        offers.incrementAndGet();assertEquals(job,JSONObject(registration).getString("jobId"));action(job)
    }
    private fun preserved() { val s=snap();assertEquals("done",s.getString("manual_status"));assertEquals("PUBLIC_FOREGROUND_MANUAL_DONE",s.getString("manual_text"));assertTrue(s.isNull("effective_summary_version"));assertTrue(s.isNull("original_started_at_ms")) }
    private fun evidence(id:String,result:ForegroundAnalysisCoordinator.Result?=null) {
        val v=JSONObject().put("id",id).put("pass",true).put("scope","GENERATED_AUDIO_NATIVE_API35_HOST_FAKE_DRIVE_AND_PROCESSOR_ONLY")
            .put("actualNetworkCalls",0).put("actualModelExecution",0).put("providerExecutionProved",false).put("canonicalWriteAllowed",false).put("offers",offers.get())
        result?.let { v.put("requestId",it.requestId).put("state",it.state).put("stage",it.stage).put("resultPolls",it.resultPolls) }
        val dir=File("build/reports/foreground-analysis").apply { mkdirs() };File(dir,"$id.json").writeText(v.toString(2))
    }
    @Test fun A01OneAsyncStartUsesSameNativeJobAndCandidateWithoutManualSummary()=runBlocking {
        withContext(Dispatchers.IO) { pipeline().use { p->flow(p,processor()).use { f->
            val result=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),3,1)
            assertEquals("REVIEW",result.state);assertEquals("RESULT_REVIEW",result.stage);assertEquals(1,result.resultPolls);assertEquals(1,offers.get())
            assertEquals(1L,count("workflow_analysis_requests"));assertEquals(1L,count("workflow_analysis_bindings"));assertEquals(1L,count("google_native_proposals"));assertEquals(0L,count("summary_versions"))
            val job=p.jobs().single();assertEquals(job.id,db().use { it.query("SELECT job_id FROM workflow_analysis_bindings").single()["job_id"] })
            val audio=p.registration(job.id).getJSONObject("files").getJSONObject("audio").getString("fileId")
            assertEquals(4,drive.counter);assertTrue(drive.bytes.getValue(audio).contentEquals(source.bytes.getValue("one")))
            assertFalse(result.providerExecutionProved);assertFalse(result.canonicalWriteAllowed);preserved();evidence("A01",result)
        } } }
    }
    @Test fun A02NoHandoffStopsAtSupportedEntryWait()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p).use { f->
        val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),3,1);assertEquals("AWAITING_SUPPORTED_DOT_ENTRY",r.stage);assertEquals("PENDING",r.state);assertEquals(0,r.resultPolls);assertEquals(0,offers.get());assertEquals(0L,count("google_native_proposals"));preserved();evidence("A02",r)
    } } }
    @Test fun A03AcceptedHandoffWithoutResultHasBoundedReadback()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor {}).use { f->
        val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);assertEquals("BOUNDED_RESULT_WAIT",r.stage);assertEquals("PENDING",r.state);assertEquals(2,r.resultPolls);assertEquals(1,offers.get());preserved();evidence("A03",r)
    } } }
    @Test fun A04UncertainHandoffNeverOffersAgainAfterReopen()=runBlocking(Dispatchers.IO) { pipeline().use { p->
        prepared=flow(p,processor { throw IOException("PUBLIC_MOCK_HANDOFF_ACK_LOSS") }).use { f->val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);assertEquals("LOCAL_SYNC_RECOVERY_REQUIRED",r.stage);r.requestId }
        flow(p,processor()).use { f->val r=ForegroundAnalysisCoordinator(f).continuePrepared(prepared,2,1);assertEquals("HANDOFF_UNCERTAIN_RECONCILE_REQUIRED",r.stage);assertEquals(0,r.resultPolls);assertEquals(1,offers.get());evidence("A04",r) }
    } }
    @Test fun A05UnknownPublicationDoesNotSubmitOrDispatchAfterReopen()=runBlocking(Dispatchers.IO) {
        var lost=false;var creates=0;val remote=object:DriveTransport by drive { override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata {
            creates++;val value=drive.create(spec,content);if(!lost&&spec.role==DriveTransport.Role.REQUEST){lost=true;throw IOException("PUBLIC_MOCK_PUBLICATION_ACK_LOSS")};return value
        } }
        pipeline(remote).use { p->prepared=flow(p,processor()).use { f->val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);assertEquals("UNKNOWN",r.state);r.requestId }
            val before=creates;flow(p,processor()).use { f->val r=ForegroundAnalysisCoordinator(f).continuePrepared(prepared,2,1);assertEquals("REQUEST_UNKNOWN",r.stage);assertEquals(before,creates);assertEquals(0,offers.get());preserved();evidence("A05",r) }
        }
    }
    @Test fun A06PendingReopenReusesIdsAndDurableOffer()=runBlocking(Dispatchers.IO) { pipeline().use { p->
        prepared=flow(p,processor {}).use { f->ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),1,1).requestId }
        val before=p.jobs().single();val ids=drive.counter;proposal(before.id)
        flow(p,processor()).use { f->val r=ForegroundAnalysisCoordinator(f).continuePrepared(prepared,2,1);assertEquals("REVIEW",r.state);assertEquals(before.id,p.jobs().single().id);assertEquals(ids,drive.counter);assertEquals(1,offers.get());preserved();evidence("A06",r) }
    } }
    @Test fun A07CurrentNativeCapabilityStillBlocksBeforeRawOrRemoteIds()=runBlocking(Dispatchers.IO) { source.opens=0;pipeline(capable=false).use { p->flow(p,processor()).use { f->
        val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);assertEquals("HELD_CAPABILITY",r.state);assertEquals(0,drive.counter);assertEquals(0,source.opens);assertEquals(0,offers.get());preserved();evidence("A07",r)
    } } }
    @Test fun A08OwnerEpochWithdrawalStopsResultReadsAndKeepsManualState()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor { session.localSignOut() }).use { f->
        try { ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);fail("old actor continued") } catch(e:IllegalStateException) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains("ACCOUNT_SESSION_CHANGED")||e.message.orEmpty().contains("OWNER_SESSION_CHANGED")||e.message.orEmpty().contains("GOOGLE")) }
        assertEquals(1,drive.downloads);assertEquals(0L,count("google_native_proposals"));preserved();evidence("A08")
    } } }
    @Test fun A09FolderWithdrawalAfterOfferBlocksLateRead()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor { source.granted=false }).use { f->
        try { ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);fail("revoked source read") } catch(_:IllegalStateException) {}
        assertEquals(1,drive.downloads);assertEquals(0L,count("google_native_proposals"));preserved();evidence("A09")
    } } }
    @Test fun A10ConcurrentManualDoneKeepsCandidateAsConflictNotCanonicalSummary()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor { job->
        GeneralLocalStoreTest.host(scoped,keys).use { n->n.edit(recording,n.snapshot().getJSONObject(0),"PUBLIC_LATE_EDIT_DONE","done") };proposal(job)
    }).use { f->val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);assertEquals("REVIEW",r.state);val row=GeneralLocalStore.catalog(scoped).listFiles().getJSONObject(0)
        assertEquals("PUBLIC_LATE_EDIT_DONE",row.getString("manual_note"));assertEquals("done",row.getString("manual_status"));assertTrue(row.getBoolean("google_proposal_conflict"));assertEquals(0L,count("summary_versions"));evidence("A10",r)
    } } }
    @Test fun A11ArchiveAfterOfferStaysHiddenWhenCandidateArrives()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor { job->
        GeneralLocalStoreTest.host(scoped,keys).use { n->n.archive(recording,n.snapshot().getJSONObject(0),true) };proposal(job)
    }).use { f->val r=ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);val row=GeneralLocalStore.catalog(scoped).listFiles().getJSONObject(0)
        assertTrue(row.getBoolean("archived"));assertFalse(row.has("google_proposal_summary"));assertEquals(1L,count("google_native_proposals"));preserved();evidence("A11",r)
    } } }
    @Test fun A12CoroutineStopDuringWaitDoesNotStartMoreReads()=runBlocking {
        val offered=CountDownLatch(1)
        withContext(Dispatchers.IO) { pipeline().use { p->flow(p,processor { offered.countDown() }).use { f->
            val coordinator=ForegroundAnalysisCoordinator(f)
            val run=launch { coordinator.runApprovedRecording(recording,snap(),3,10_000) }
            assertTrue(withContext(Dispatchers.IO) { offered.await(10,TimeUnit.SECONDS) });val before=drive.downloads
            run.cancelAndJoin();assertEquals(before,drive.downloads);assertEquals(0L,count("google_native_proposals"));preserved();evidence("A12")
        } } }
    }
    @Test fun A13ThirtyMinuteNativeLeaseExpiryIsNotExtendedByCoordinator()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor {
        db().use { it.execute("UPDATE google_native_leases SET expires_ms=0") }
    }).use { f->
        try { ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),2,1);fail("expired lease read") } catch(_:IllegalStateException) {}
        assertEquals(1,drive.downloads);assertEquals(0L,count("google_native_proposals"));assertEquals(0L,db().use { (it.query("SELECT expires_ms FROM google_native_leases").single()["expires_ms"] as Number).toLong() })
        preserved();evidence("A13")
    } } }
    @Test fun A14InvalidBudgetsFailBeforeRequestOrTransportCreation()=runBlocking(Dispatchers.IO) { pipeline().use { p->flow(p,processor()).use { f->
        for((polls,interval) in listOf(0 to 1L,17 to 1L,1 to 0L,1 to 10_001L)) {
            try { ForegroundAnalysisCoordinator(f).runApprovedRecording(recording,snap(),polls,interval);fail("unbounded run") } catch(e:IllegalArgumentException) { assertEquals("BOUNDED_FOREGROUND_REQUIRED",e.message) }
        }
        assertEquals(0L,count("workflow_analysis_requests"));assertEquals(0,drive.counter);assertEquals(0,offers.get());preserved();evidence("A14")
    } } }
}
