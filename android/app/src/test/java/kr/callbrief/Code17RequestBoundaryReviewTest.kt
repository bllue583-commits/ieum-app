package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Independent review only. Failing assertions are unmet safety criteria in unchanged code17.
 * Created 52-byte WAV, public test key, memory authorization and fake Drive only.
 * No Google account, dot/STT/model execution, installed app or remote writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code17RequestBoundaryReviewTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var settings:SecureSettings
    private lateinit var source:GeneralLocalStoreTest.Source
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private var recording=""
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=try { worker.submit<T>{f()}.get(30,TimeUnit.SECONDS) }
        catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    private class Memory:GoogleConnectionSession.Persistence {
        private var value=JSONObject()
        override fun read()=JSONObject(value.toString())
        override fun write(v:JSONObject) { value=JSONObject(v.toString()) }
    }
    private fun activate(owner:String="REVIEW_OWNER_A") {
        val epoch=session.begin()
        session.activate(epoch,"public-fixture@example.invalid","public-fixture@example.invalid",owner,"PUBLIC_SYNTHETIC_MEMORY_TOKEN")
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory()){1000L};activate()
        scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys()
        source=GeneralLocalStoreTest.Source().apply { put(name="통화 녹음 공개합성_01000000000_20261010_100000.wav") }
        drive=GoogleConnectionRegressionTest.FakeDrive()
        val publicKey=SecretKeySpec(ByteArray(32){0x17},"AES")
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess {
            override fun existing()=publicKey
            override fun createForFirstEmptyStore()=publicKey
        });settings.update { it.put("treeUri",source.rootUri) }
        io { GeneralLocalStoreTest.host(scoped,keys).use { store ->
            store.initializeEmpty();store.importSelected(source);val r=store.snapshot().getJSONObject(0);recording=r.getString("file_id")
            store.edit(recording,r,"PUBLIC_SYNTHETIC_MANUAL_DONE","done")
        } }
    }
    @After fun stop() { worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun snap()=RecordingWorkflowStore(scoped).use { it.snapshot(recording) }
    private fun port()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun pipeline()=GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}) { GeneralLocalStoreTest.host(it,keys) }
    private fun proof(name:String,vararg fields:Pair<String,Any?>) {
        val o=JSONObject().put("scope","INDEPENDENT_API35_NATIVE_SQLITE_HOST_ONLY_NO_PROVIDER_EXECUTION")
        fields.forEach { o.put(it.first,it.second ?: JSONObject.NULL) }
        File("build/reports/code17-independent-review").apply { mkdirs() }.resolve(name+".json").writeText(o.toString(2)+"\n")
    }
    private inner class NativeTestPort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override fun submit(recording:String)=p.submitExisting(recording).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override val requiresNativeBinding=true
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording) { j->try { observer(j) } catch(e:Exception) { proof("BIND_DIAG","code" to e.message,"type" to e.javaClass.simpleName,"jobEpoch" to j.authorizationEpoch,"actorEpoch" to session.epoch(),"jobActor" to JSONObject(j.source.contextJson).optJSONObject("authSubject"),"actor" to session.nativeSessionBinding());throw e } }.let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override fun resume(job:String,observer:(LocalJobStore.Job)->Unit)=p.resume(job,observer).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override fun retryBound(job:String,observer:(LocalJobStore.Job)->Unit)=p.retry(job,observer).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override fun poll(job:String)=p.poll(job)
        override fun retry(job:String)=p.retry(job).let { RecordingAnalysisPort.Sent(it.outcome.name,it.jobId) }
        override fun jobs()=p.jobs()
        override fun registration(job:String)=p.registration(job)
        override fun sync(job:String)=port().use { db -> Code16DotManualSync(session,p,db,operatorHandoff=null).syncExplicitSynthetic(setOf(job),1).single().state }
        override fun cancel()=p.cancel()
        override fun close() {} // Outer test owns pipeline, matching per-operation lifetimes without external I/O.
    }
    private fun preparedSent(p:GoogleNativePipeline,flow:AnalysisRequestFlow):String {
        p.importAndSubmit(source.documents.getValue("one"),true)
        val id=flow.prepare(recording,snap());assertEquals("SENT",flow.submit(id));return id
    }
    @Test fun R01DuplicatePendingPlaceholderMustRemainPending()=io {
        pipeline().use { p -> AnalysisRequestFlow(context,session,{NativeTestPort(p)}).use { flow ->
            val id=preparedSent(p,flow);assertEquals("PENDING",flow.poll(id))
            val observed=flow.poll(id)
            val count=port().use { it.query("SELECT count(*) AS n FROM google_native_proposals").single()["n"] }
            proof("R01", "secondPoll" to observed,"proposalCount" to count,"request" to RecordingWorkflowStore(scoped).use { it.request(id) })
            assertEquals("PENDING placeholder revision0 / NOT_RUN cannot mean a proposal was received","PENDING",observed)
        } }
    }
    @Test fun R02ManualSyncDuplicatePendingMustAwaitSupportedEntry()=io {
        pipeline().use { p -> AnalysisRequestFlow(context,session,{NativeTestPort(p)}).use { flow ->
            val id=preparedSent(p,flow);assertEquals("PENDING",flow.poll(id))
            val observed=flow.sync(id)
            proof("R02","syncResult" to observed,"request" to RecordingWorkflowStore(scoped).use { it.request(id) },"manualStatus" to snap().getString("manual_status"))
            assertEquals("Duplicate placeholder is not a read-only analysis result","AWAITING_SUPPORTED_DOT_ENTRY",observed)
        } }
    }
    private fun seedFrozenJob(snapshot:JSONObject,jobId:String="REVIEW_OLD_JOB",phase:LocalJobStore.Phase=LocalJobStore.Phase.SENT,auth:Long=session.epoch()) {
        val s=LocalJobStore.Source(recording,snapshot.getString("audio_sha256"),snapshot.getLong("source_version"),52,
            snapshot.getLong("board_revision"),snapshot.getLong("user_revision"),snapshot.getLong("lock_revision"),0,
            snapshot.getLong("visibility_revision"),1,session.ownerBinding(),session.driveAccount(),session.driveOwnerPermissionId(),"{}",0,true,false,false)
        val wire=WireV0Codec().prepare(s,jobId,"REVIEW_FOLDER","REVIEW_AUDIO","REVIEW_REQUEST","REVIEW_RESULT")
        val j=LocalJobStore.Job(jobId,"REVIEW_FOLDER","REVIEW_AUDIO","REVIEW_REQUEST","REVIEW_RESULT",s,auth,wire.requestDigest,phase,"",-1,wire.requestBytes,wire.placeholderBytes)
        ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use { protected ->
            val db=protected.database();protected.transaction {
                SqliteJobStore.installTables(db)
                SqliteJobStore(db) { _,_->null }.transaction { tx ->tx.putJob(j);tx.setActiveJobId(recording,jobId);null }
            }
        }
    }
    @Test fun R03ReconcileMustRejectOldJobWithDifferentManualRevision()=io {
        val old=snap();seedFrozenJob(old)
        GeneralLocalStoreTest.host(scoped,keys).use { s->s.edit(recording,s.snapshot().getJSONObject(0),"PUBLIC_SYNTHETIC_NEW_MANUAL_DONE","done") }
        RecordingWorkflowStore(scoped).use { s ->
            val fresh=s.snapshot(recording);val id=s.prepareAnalysis(recording,fresh);s.transitionRequest(id,"UNKNOWN")
            val observed=s.reconcileRemoteJob(id)
            proof("R03","oldUserRevision" to old.getLong("user_revision"),"currentUserRevision" to fresh.getLong("user_revision"),"linkedJob" to observed,"request" to s.request(id))
            assertNull("Same source audio/owner is insufficient to reconcile a different prepared binding",observed)
        }
    }
    @Test fun R04ReconcileMustRejectOldAuthorizationSession()=io {
        val auth=session.epoch();seedFrozenJob(snap(),auth=auth);activate()
        RecordingWorkflowStore(scoped).use { s ->val id=s.prepareAnalysis(recording,s.snapshot(recording));s.transitionRequest(id,"UNKNOWN")
            val observed=s.reconcileRemoteJob(id)
            proof("R04","oldEpoch" to auth,"currentEpoch" to session.epoch(),"linkedJob" to observed)
            assertNull("Old auth epoch is blocked by native polling and cannot reconcile the current actor",observed)
        }
    }
    @Test fun R05UnsupportedProfileMustPreserveExistingReviewState()=io {
        pipeline().use { p -> AnalysisRequestFlow(context,session,{NativeTestPort(p)}).use { flow ->
            val id=preparedSent(p,flow);val job=p.jobs().single()
            val value=StrictJson.`object`(StrictJson.parse(drive.bytes.getValue(job.resultId))).toMutableMap()
            val text="공개 합성 전사 자료"
            value["state"]="PROPOSAL";value["resultRevision"]=1L
            value["stt"]=mapOf("execution" to "FIXTURE","text" to text,"textSha" to DriveRelay.sha256(text.toByteArray()),"evidenceRef" to "PUBLIC_SYNTHETIC_FIXTURE_ONLY")
            value["resultDigest"]=WireV0Codec.digest("result",value);drive.bytes[job.resultId]=StrictJson.bytes(value);drive.versions[job.resultId]=2
            assertEquals("REVIEW",flow.poll(id))
            drive.bytes[job.resultId]="{\"kind\":\"UNKNOWN_PROFILE\",\"schemaVersion\":99}".toByteArray();drive.versions[job.resultId]=3
            val observed=flow.poll(id)
            proof("R05","workflowState" to observed,"request" to RecordingWorkflowStore(scoped).use { it.request(id) },"proposals" to snap().getJSONArray("proposals"))
            assertEquals("Unsupported result profile must keep the existing display/manual values and explain the hold","REVIEW",observed)
        } }
    }
    @Test fun R06PartialJobAckLossDoesNotAutoSendButHasNoResumePath()=io {
        var submits=0
        val remote=object:RecordingAnalysisPort {
            override fun submit(recording:String):RecordingAnalysisPort.Sent { submits++;seedFrozenJob(snap(),phase=LocalJobStore.Phase.RESERVED);throw IOException("PUBLIC_SYNTHETIC_ACK_LOSS") }
            override fun poll(job:String):String { throw IllegalStateException("Native poll admits only SENT/CANDIDATE") }
            override fun retry(job:String)=error("EXPLICIT_FAILURE_REQUIRED")
            override fun jobs()=emptyList<GoogleRelayStore.JobView>()
            override fun sync(job:String)="REQUEST_NOT_COMMITTED"
            override fun cancel() {} ;override fun close() {}
        }
        AnalysisRequestFlow(context,session,{remote}).use { flow ->
            val id=flow.prepare(recording,snap());assertEquals("UNKNOWN",flow.submit(id));assertEquals("UNKNOWN",flow.submit(id));assertEquals("UNKNOWN",flow.sync(id));assertEquals(1,submits)
            proof("R06","request" to RecordingWorkflowStore(scoped).use { it.request(id) },"submitCalls" to submits,"frozenJobPhase" to port().use { it.query("SELECT phase FROM drive_v0_jobs").single()["phase"] },"nature" to "UNBOUND_LEGACY_JOB_NOT_INFERRED_NO_AUTOMATIC_RESEND")
        }
    }
    @Test fun R07OwnSummaryKeepsManualDoneAndPendingExecutionStatus()=io {
        RecordingWorkflowStore(scoped).use { s->s.reviewOwnSummary(recording,s.snapshot(recording),"직접 확인 공개 합성 요약") }
        val row=GeneralLocalStore.catalog(scoped).listFiles().getJSONObject(0)
        assertEquals("USER_REVIEWED_LOCAL",row.getString("summary_origin"));assertEquals("PUBLIC_SYNTHETIC_MANUAL_DONE",row.getString("manual_note"));assertEquals("done",row.getString("manual_status"))
        assertFalse(row.getString("status") in setOf("completed","summarized","processed"));assertTrue(snap().isNull("effective_summary_version"))
        proof("R07","summaryOrigin" to row.getString("summary_origin"),"executionStatus" to row.getString("status"),"manualStatus" to row.getString("manual_status"),"canonicalSummaryVersion" to snap().opt("effective_summary_version"))
    }
    @Test fun R08OldActorCannotOpenTransportAfterOwnerChange()=io {
        var factories=0
        AnalysisRequestFlow(context,session,{factories++;error("NO_TRANSPORT_ALLOWED")}).use { flow ->
            val id=flow.prepare(recording,snap());activate("REVIEW_OWNER_B")
            try { flow.submit(id);fail("Old actor must be rejected") } catch(e:IllegalStateException) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains("ACCOUNT_SESSION_CHANGED")) }
            assertEquals(0,factories);assertEquals("done",snap().getString("manual_status"))
            proof("R08","factoriesOpened" to factories,"currentOwner" to session.ownerBinding(),"originalManualStatus" to snap().getString("manual_status"))
        }
    }
}
