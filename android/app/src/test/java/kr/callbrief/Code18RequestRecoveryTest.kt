package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** New recovery/capability controls. Actual native code with protected SQLite/JCE and fake Drive.
 * The fake capability is injected only here, not enabled through settings or production flags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class Code18RequestRecoveryTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys;private lateinit var source:GeneralLocalStoreTest.Source
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive;private lateinit var fault:FaultDrive
    private var recording="";private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(action:()->T):T=try{worker.submit<T>{action()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    private class Memory:GoogleConnectionSession.Persistence {private var v=JSONObject();override fun read()=JSONObject(v.toString());override fun write(value:JSONObject){v=JSONObject(value.toString())}}
    private class FaultDrive(val base:GoogleConnectionRegressionTest.FakeDrive):DriveTransport by base {
        var loseAfter:DriveTransport.Role?=null;var lost=false;var creates=0
        override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata {
            creates++;if(base.specs.containsKey(spec.id))throw DriveTransport.DriveException(DriveTransport.Failure.CONFLICT)
            val value=base.create(spec,content)
            if(!lost&&spec.role==loseAfter){lost=true;throw IOException("PUBLIC_SYNTHETIC_ACK_LOSS")}
            return value
        }
    }
    private fun activate(){val e=session.begin();session.activate(e,"fixture@example.invalid","fixture@example.invalid","PUBLIC_OWNER","PUBLIC_MEMORY_TOKEN")}
    @Before fun setup(){
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory()){1000L};activate();scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys()
        source=GeneralLocalStoreTest.Source().apply{put(name="통화 녹음 합성_${"010"+"0".repeat(8)}_20261010_100000.wav")};drive=GoogleConnectionRegressionTest.FakeDrive();fault=FaultDrive(drive)
        val k=SecretKeySpec(ByteArray(32){0x18},"AES");settings=SecureSettings(scoped,object:SecureSettings.KeyAccess{override fun existing()=k;override fun createForFirstEmptyStore()=k});settings.update{it.put("treeUri",source.rootUri)}
        io{GeneralLocalStoreTest.host(scoped,keys).use{s->s.initializeEmpty();s.importSelected(source);val r=s.snapshot().getJSONObject(0);recording=r.getString("file_id");s.edit(recording,r,"PUBLIC_SYNTHETIC_NOTE_DONE","done")};SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)}
    }
    @After fun stop(){worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    private fun snap()=RecordingWorkflowStore(scoped).use{it.snapshot(recording)}
    private fun db()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun pipeline(capable:Boolean=true):GoogleNativePipeline {val gate:(String)->Boolean=if(capable)({_ ->true})else FrozenDotCapability(context)::supports;return GoogleNativePipeline(context,session,settings,{source},fault,gate){GeneralLocalStoreTest.host(it,keys)}}
    private class NativePort(val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        private fun sent(r:DriveRelay.Reply)=RecordingAnalysisPort.Sent(r.outcome.name,r.jobId)
        override fun submit(recording:String)=sent(p.submitExisting(recording))
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=sent(p.submitExisting(recording,observer))
        override fun resume(job:String,observer:(LocalJobStore.Job)->Unit)=sent(p.resume(job,observer))
        override fun retry(job:String)=sent(p.retry(job))
        override fun retryBound(job:String,observer:(LocalJobStore.Job)->Unit)=sent(p.retry(job,observer))
        override fun poll(job:String)=p.poll(job);override fun jobs()=p.jobs();override fun registration(job:String)=p.registration(job)
        override fun cancel()=p.cancel();override fun close(){}
    }
    private fun request(p:GoogleNativePipeline,flow:AnalysisRequestFlow)=flow.prepare(recording,snap())
    private fun fails(code:String,f:()->Unit){try{f();fail("Expected $code")}catch(e:Exception){assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(code))}}
    @Test fun C01ReservationCapturePrecedesAnyPublication():Unit=io{pipeline().use{p->var captured=false;p.submitExisting(recording){j->assertEquals(0,fault.creates);assertEquals(4,drive.counter);assertTrue(j.requestBytes.isNotEmpty());captured=true};assertTrue(captured)}}
    @Test fun C02CaptureFailureCannotPublishAnyFiles():Unit=io{pipeline().use{p->fails("PUBLIC_CAPTURE_FAILURE"){p.submitExisting(recording){throw IllegalStateException("PUBLIC_CAPTURE_FAILURE")}};assertEquals(0,fault.creates);assertEquals(4,drive.counter);assertEquals(0,drive.specs.size)}}
    @Test fun C03KnownReservedJobReconcilesOnlyCapturedActor():Unit=io{pipeline().use{p->fault.loseAfter=DriveTransport.Role.PRIVATE_FOLDER;AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val id=request(p,f);assertEquals("UNKNOWN",f.submit(id));RecordingWorkflowStore(scoped).use{s->val r=s.request(id);assertTrue(r.getBoolean("has_reserved_binding"));assertEquals(r.getString("job_id"),s.reconcileRemoteJob(id,session.nativeSessionBinding()));assertNull(s.reconcileRemoteJob(id));val actor=JSONObject(session.nativeSessionBinding().toString()).put("epoch",session.epoch()+1);assertNull(s.reconcileRemoteJob(id,actor))}}}}
    @Test fun C04PartialRequestExplicitResumeKeepsAllFourIdsAndDigest():Unit=io{pipeline().use{p->fault.loseAfter=DriveTransport.Role.REQUEST;AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val id=request(p,f);assertEquals("UNKNOWN",f.submit(id));val before=p.jobs().single();val binding=db().use{it.query("SELECT binding_json FROM workflow_analysis_bindings").single()["binding_json"].toString()};val count=drive.counter;assertEquals("UNKNOWN",f.submit(id));assertEquals(count,drive.counter);assertEquals("SENT",f.resume(id));val after=p.jobs().single();assertEquals(before.id,after.id);assertEquals(before.folderId,after.folderId);assertEquals(before.requestId,after.requestId);assertEquals(before.resultId,after.resultId);assertEquals(count,drive.counter);assertEquals(binding,db().use{it.query("SELECT binding_json FROM workflow_analysis_bindings").single()["binding_json"].toString()});assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_SYNTHETIC_NOTE_DONE",snap().getString("manual_text"))}}}
    @Test fun C05ResumeManualChangeBlocksBeforeAnyNewCreate():Unit=io{pipeline().use{p->fault.loseAfter=DriveTransport.Role.PRIVATE_FOLDER;AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val id=request(p,f);f.submit(id);val count=fault.creates;GeneralLocalStoreTest.host(scoped,keys).use{s->s.edit(recording,s.snapshot().getJSONObject(0),"PUBLIC_CONCURRENT_NOTE","done")};fails("REQUEST_BINDING_CHANGED"){f.resume(id)};assertEquals(count,fault.creates);assertEquals("PUBLIC_CONCURRENT_NOTE",snap().getString("manual_text"))}}}
    @Test fun C06ResumeSameOwnerNewEpochCannotUseOldActor():Unit=io{pipeline().use{p->fault.loseAfter=DriveTransport.Role.PRIVATE_FOLDER;val id=AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val r=request(p,f);f.submit(r);r};val count=fault.creates;activate();AnalysisRequestFlow(context,session,{error("NO_NATIVE_FACTORY")}).use{f->fails("REQUEST_ASSOCIATION_REVIEW_REQUIRED"){f.resume(id)}};assertEquals(count,fault.creates)}}
    @Test fun C07AssociationReplaceUpdateDeleteAllRejected():Unit=io{pipeline().use{p->AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val id=request(p,f);assertEquals("SENT",f.submit(id));db().use{d->val before=d.query("SELECT * FROM workflow_analysis_bindings").single();for(sql in listOf("INSERT OR REPLACE INTO workflow_analysis_bindings SELECT * FROM workflow_analysis_bindings","UPDATE workflow_analysis_bindings SET job_id='OTHER'","DELETE FROM workflow_analysis_bindings")){try{d.execute(sql);fail("immutable binding overwritten")}catch(e:kr.co.callsummary.core.CoreException){assertEquals("IMMUTABLE_HISTORY",e.code)}};assertEquals(before,d.query("SELECT * FROM workflow_analysis_bindings").single())}}}}
    @Test fun C08ExplicitRetryKeepsOriginalIntentAndFailure():Unit=io{pipeline().use{p->AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val id=request(p,f);f.submit(id);val old=p.jobs().single();drive.failure(old.resultId);assertEquals("ERROR",f.poll(id));assertEquals("SENT",f.retry(id));val all=snap().getJSONArray("requests");assertEquals(2,all.length());val original=RecordingWorkflowStore(scoped).use{it.request(id)};assertEquals("ERROR",original.getString("state"));assertEquals(old.id,original.getString("job_id"));assertEquals(2,db().use{it.query("SELECT * FROM workflow_analysis_bindings").size});val count=drive.counter;assertEquals("SENT",f.retry(id));assertEquals(count,drive.counter);assertEquals("done",snap().getString("manual_status"))}}}
    @Test fun C09DefaultNativeCapabilityHoldsPublicAccountBeforeRawOrIds():Unit=io{source.opens=0;pipeline(false).use{p->fails("DOT_PROFILE_ACCOUNT_UNSUPPORTED"){p.importAndSubmit(source.documents.getValue("one"),true)};assertEquals(0,source.opens);assertEquals(0,drive.counter);assertEquals(0,fault.creates)}}
    @Test fun C10DefaultRelayCapabilityCannotGenerateOrPublish():Unit=io{GoogleRelayStore(context,session,fault).use{s->fails("DOT_PROFILE_ACCOUNT_UNSUPPORTED"){s.submitSynthetic()};assertEquals(0,drive.counter);assertEquals(0,fault.creates)}}
    @Test fun C11FlowCapabilityHoldNeverClaimsSent():Unit=io{pipeline(false).use{p->AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->val id=request(p,f);assertEquals("HELD_CAPABILITY",f.submit(id));val row=RecordingWorkflowStore(scoped).use{it.request(id)};assertEquals("DOT_PROFILE_ACCOUNT_UNSUPPORTED",row.getString("reason"));assertTrue(row.isNull("job_id"));assertEquals(0,drive.counter);assertEquals("done",snap().getString("manual_status"))}}}
    @Test fun C12NoRuntimeHandoffIsAlwaysExplicitWaitEvenAfterPlaceholderDuplicate():Unit=io{pipeline().use{p->val sent=p.submitExisting(recording);assertEquals("PENDING",p.poll(sent.jobId));assertEquals("PENDING",p.poll(sent.jobId));db().use{d->assertEquals("AWAITING_SUPPORTED_DOT_ENTRY",Code16DotManualSync(session,p,d,null).syncExplicitSynthetic(setOf(sent.jobId),1).single().state)}}}
}
