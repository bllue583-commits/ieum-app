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
import javax.crypto.spec.SecretKeySpec

/** Production native binding, protected SQLite, export journal and coordinator.
 * Generated WAV/in-memory token/Drive only. No actual OAuth, dot, STT or writer/CAS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code30DriveIntegrityRegressionTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys;private lateinit var source:GeneralLocalStoreTest.Source
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private var recording="";private var request="";private var job="";private var result=""
    private class Memory:GoogleConnectionSession.Persistence {var value=JSONObject();override fun read()=JSONObject(value.toString());override fun write(v:JSONObject){value=JSONObject(v.toString())}}
    private lateinit var memory:Memory
    private fun activate(owner:String="PUBLIC_DRIVE_A_OWNER"){val e=session.begin();session.activate(e,"drive-a@example.invalid","drive-a@example.invalid",owner,"PUBLIC_FIXTURE_RAM_TOKEN")}
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        context=RuntimeEnvironment.getApplication();memory=Memory();session=GoogleConnectionSession(memory);activate()
        scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys()
        source=GeneralLocalStoreTest.Source().apply{put()};drive=GoogleConnectionRegressionTest.FakeDrive()
        val k=SecretKeySpec(ByteArray(32){0x29},"AES");settings=SecureSettings(scoped,object:SecureSettings.KeyAccess {override fun existing()=k;override fun createForFirstEmptyStore()=k})
        settings.update{it.put("treeUri",source.rootUri)}
        GeneralLocalStoreTest.host(scoped,keys).use{s->s.initializeEmpty();s.importSelected(source);val r=s.snapshot().getJSONObject(0);recording=r.getString("file_id");s.edit(recording,r,"PUBLIC_DRIVE_A_DONE\n\"LF 유지\"","done")}
        SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("one"),true)
    }
    private fun db()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun primary()=TextStoreTestSupport.open(scoped,GeneralLocalStore.DATABASE)
    private fun journal()=TextStoreTestSupport.open(scoped,DriveAExportJournal.DATABASE)
    private fun count(table:String)=db().use{it.query("SELECT COUNT(*) AS n FROM $table").single()["n"] as Long}
    private fun snap()=RecordingWorkflowStore(scoped).use{it.snapshot(recording)}
    private fun pipeline(remote:DriveTransport=drive,capable:Boolean=true)=GoogleNativePipeline(context,session,settings,{source},remote,
        postingCapability=if(capable)({_->true})else FrozenDotCapability(context)::supports){GeneralLocalStoreTest.host(it,keys)}
    private inner class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun poll(job:String)=p.poll(job);override fun pollObservation(job:String)=p.pollObservation(job)
        override fun driveAExport(job:String)=p.driveAExport(job);override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job)
        override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun retry(job:String):RecordingAnalysisPort.Sent=error("NO_RETRY");override fun cancel(){};override fun close(){}
    }
    private fun submitted(p:GoogleNativePipeline) {
        AnalysisRequestFlow(context,session,{NativePort(p)}).use{f->request=f.prepare(recording,snap());assertEquals("SENT",f.submit(request));assertEquals("SENT",f.submit(request))}
        job=p.jobs().single().id;result=p.jobs().single().resultId;assertEquals(4,drive.counter)
    }
    private fun port(p:GoogleNativePipeline)=ExistingDriveARequestPort(context,session){NativePort(p)}
    private fun candidate(p:GoogleNativePipeline)=DriveAForegroundCandidate(context,port(p))
    private fun frozen()=drive.bytes.values.single {runCatching{StrictJson.`object`(StrictJson.parse(it))["kind"]=="CALL_ANALYSIS_REQUEST"}.getOrDefault(false)}
    private fun proposal(revision:Long=1,raw:String="합성 \"인용\"\nLF 원문 유지입니다.") {
        val r=WireV0Codec.validateRequest(frozen());val sha=DriveRelay.sha256(raw.toByteArray())
        val provenance=mapOf("source_kind" to "synthetic_contract_fixture","language" to "ko","run_id" to "drive_a_host_fixture","audio_sha256" to r["audioSha"],"model_name" to null,"model_sha256" to null,"engine_sha256" to null,"execution_observed" to false,"reference_used_in_inference" to false,"quality_state" to "raw_review_required","run_started_at_utc" to null,"run_finished_at_utc" to null,"runner_sha256" to null,"source_commit" to null,"dataset" to null,"dataset_revision" to null,"license" to null)
        val src=mapOf("source_id" to r["sourceId"],"kind" to "call_transcript","raw_text" to raw,"text_sha256" to sha,"recorded_at" to null,"timestamp_basis" to "unknown","case_id" to null,"case_link_confirmed" to false,"speaker_assignment_verified" to false,"uncertain_ranges" to emptyList<Any>(),"provenance" to provenance)
        val packet=linkedMapOf<String,Any?>("schema_version" to 1L,"job_id" to r["jobId"],"base_revision" to r["baseRevision"],"scope" to "synthetic_contract_test","offset_unit" to "unicode_code_points","automatic_summary_allowed" to false,"root_semantic_review_required" to true,"sources" to listOf(src));packet["source_pack_sha256"]=DriveRelay.sha256(StrictJson.bytes(packet))
        val c=mapOf("schema_version" to 1L,"job_id" to r["jobId"],"base_revision" to r["baseRevision"],"source_pack_sha256" to packet["source_pack_sha256"],"summary" to "합성 후보 · 실제 모델 실행 아님","claims" to emptyList<Any>())
        val value=WireV0Codec.placeholder(r).toMutableMap();value["state"]="PROPOSAL";value["resultRevision"]=revision
        value["stt"]=mapOf("execution" to "FIXTURE","text" to raw,"textSha" to sha,"evidenceRef" to "drive_a_host_fixture_only")
        value["high"]=mapOf("execution" to "FIXTURE","packetJson" to String(StrictJson.bytes(packet)),"candidateJson" to String(StrictJson.bytes(c)),"evidenceRef" to "drive_a_host_fixture_only")
        value["resultDigest"]=WireV0Codec.digest("result",value);drive.bytes[result]=StrictJson.bytes(value);drive.versions[result]=drive.versions.getValue(result)+1
    }
    private fun preserved(note:String="PUBLIC_DRIVE_A_DONE\n\"LF 유지\""){val s=snap();assertEquals("done",s.getString("manual_status"));assertEquals(note,s.getString("manual_text"));assertTrue(s.isNull("effective_summary_version"));assertEquals(0L,count("summary_versions"))}
    private fun proof(id:String,r:DriveAForegroundCandidate.Result?=null) {
        r?.let{assertFalse(it.providerAcceptanceVerified);assertFalse(it.modelExecutionVerified);assertFalse(it.remoteAtomicCasVerified);assertFalse(it.canonicalWriteAllowed)}
        val v=JSONObject().put("id",id).put("pass",true).put("scope","LOCAL_HOST_PRODUCTION_CODE_GENERATED_AUDIO_FAKE_DRIVE_ONLY").put("actualNetworkCalls",0).put("actualModelExecution",0).put("actualDTStatus","NOT_RUN").put("remoteAtomicCas","NOT_VERIFIED").put("nativeDefaultFactoryChanged",false).put("frozenV0WireChanged",false)
        r?.let{v.put("state",it.state).put("handoffIntentCreated",it.handoffIntentCreated)}
        File("build/reports/drive-a-local").apply{mkdirs()}.let{File(it,"$id.json").writeText(v.toString(2))}
    }
    private fun independentObservation(id:String,body:JSONObject) {
        body.put("scope","API35_NATIVE_SQLITE_JCE_PRIVILEGED_SYNTHETIC_DB_FAULTS_ONLY")
            .put("actualNetworkCalls",0).put("actualModelExecution",0).put("actualDT01_16","NOT_RUN")
        File("build/reports/code29-independent").apply{mkdirs()}.let{File(it,"$id.json").writeText(body.toString(2))}
    }
    private fun headObservation():JSONObject=primary().use{d->d.rawQuery("SELECT schema_version,result_revision,digest FROM drive_profiles_heads",null).use{q->
        if(!q.moveToFirst())JSONObject().put("present",false) else JSONObject().put("present",true).put("schemaType",q.getType(0)).put("schema",q.getDouble(0)).put("revisionType",q.getType(1)).put("revision",q.getDouble(1)).put("digest",q.getString(2))
    }}
    @Test fun R01CorruptionDuringReadIsRejectedInReceiptTransaction()=runBlocking(Dispatchers.IO){
        var armed=false
        val remote=object:DriveTransport by drive {override fun download(id:String,max:Int):DriveTransport.Download {
            val bytes=drive.download(id,max)
            if(armed){armed=false;primary().use{it.execSQL("UPDATE drive_profiles_heads SET digest=?",arrayOf("f".repeat(64)))}}
            return bytes
        }}
        pipeline(remote).use{p->submitted(p);val c=candidate(p);c.prepareExplicit(request);proposal();c.pollExplicit(request);val reads=drive.downloads;proposal(2);armed=true
            val r=c.pollExplicit(request);assertEquals("STORE_REVIEW",r.state);assertEquals(reads+1,drive.downloads);assertEquals(1L,count("drive_profiles_receipts"));assertEquals("f".repeat(64),headObservation().getString("digest"));assertEquals(1.0,headObservation().getDouble("revision"),0.0);preserved()
            independentObservation("C30R01",JSONObject().put("state",r.state).put("readDelta",1).put("existingReceiptCount",1).put("damagedHeadPreserved",true).put("newAdmission",false))
        }
    }
    @Test fun R02EditThenArchiveAfterReceiptUsesCurrentConflictWithoutChangingReceipt()=runBlocking(Dispatchers.IO){pipeline().use{p->
        submitted(p);val c=candidate(p);c.prepareExplicit(request);proposal();val original=c.pollExplicit(request);assertFalse(original.receipt!!.conflict);assertFalse(original.receipt.hidden)
        GeneralLocalStoreTest.host(scoped,keys).use{s->s.edit(recording,s.snapshot().getJSONObject(0),"PUBLIC_DONE_AFTER_RECEIPT_ARCHIVE","done");s.archive(recording,s.snapshot().getJSONObject(0),true)}
        val reopened=candidate(p).load(request);assertTrue(reopened.receipt!!.conflict);assertTrue(reopened.receipt.hidden);assertNull(reopened.receipt.raw);assertNull(reopened.receipt.analysis);assertEquals(original.receipt.digest,reopened.receipt.digest)
        val duplicate=c.pollExplicit(request);assertTrue(duplicate.receipt!!.conflict);assertTrue(duplicate.receipt.hidden);assertNull(duplicate.receipt.raw)
        primary().use{it.rawQuery("SELECT conflict,hidden FROM drive_profiles_receipts",null).use{q->q.moveToFirst();assertEquals(0,q.getInt(0));assertEquals(0,q.getInt(1))}}
        assertEquals(1L,count("drive_profiles_receipts"));preserved("PUBLIC_DONE_AFTER_RECEIPT_ARCHIVE")
        independentObservation("C30R02",JSONObject().put("reopenedConflict",true).put("reopenedHidden",true).put("rawAndAnalysisHidden",true).put("immutableReceiptFlagsPreserved",true).put("summaryWrites",0))
    }}
}
