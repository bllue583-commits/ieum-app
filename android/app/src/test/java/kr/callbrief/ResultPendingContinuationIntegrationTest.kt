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
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ResultPendingContinuationIntegrationTest:ProtectedTextHostTestBase() {
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
    @After fun cleanup() {GoogleConnectionSession.resetForHost();DailyReportSchedule.installRegistryForHost(null);AutomationProviderRegistry.create=null;ScheduledDotProofRegistry.plan=null;AutomaticResultReconciliation.installForHost()}
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
    private inner class Provider:AutomationProvider {
        var submitted=0;var reconciled=0;var received=0;var revision=1L;var target:String?=null
        var failAck=false;var approved=true;var trusted=true;var beforeReceive:()->Unit={};var afterDispatch:()->Unit={}
        var lastHandoff="";var lastRaw="";private val accepted=mutableSetOf<String>()
        override fun gate()=if(approved)AutomationGate("READY","PUBLIC_SYNTHETIC_TEST_PROVIDER") else AutomationGate("UNAPPROVED","PROVIDER_EXECUTION_APPROVAL_REQUIRED")
        override fun dispatch(key:String,frozenHandoff:String):AutomationDispatch {
            submitted++;assertTrue(accepted.add(key));lastHandoff=frozenHandoff
            afterDispatch()
            if(failAck){failAck=false;throw IOException("PUBLIC_ACCEPTANCE_ACK_LOST")}
            return AutomationDispatch("ACCEPTED","PUBLIC_RECEIPT_$key","PUBLIC_SYNTHETIC_ACCEPTED")
        }
        override fun reconcile(key:String,frozenHandoff:String):AutomationDispatch {reconciled++;return if(key in accepted)AutomationDispatch("ACCEPTED","PUBLIC_RECEIPT_$key","PUBLIC_SYNTHETIC_RECONCILED") else AutomationDispatch("UNKNOWN",reason="PUBLIC_NO_RECEIPT")}
        override fun receive(key:String,receipt:String,frozenHandoff:String):AutomationObservation {
            received++;assertEquals("PUBLIC_RECEIPT_$key",receipt);beforeReceive()
            val raw="합성 견적서를 준비해 주세요."
            val tasks=JSONArray().put(JSONObject().put("taskId",target ?: JSONObject.NULL).put("title","합성 견적서 준비").put("assignee","합성 담당")
                .put("due",JSONObject.NULL).put("status","open").put("quote",raw).put("start",0).put("end",raw.codePointCount(0,raw.length)))
            lastRaw=JSONObject().put("fixture","SYNTHETIC_POLICY_BRANCH_ONLY_NOT_ACTUAL_STT").put("revision",revision).put("tasks",tasks).toString()
            return AutomationObservation.Result(AutomationAnalysis(revision,DriveRelay.sha256(lastRaw.toByteArray()),lastRaw,raw,"합성 견적서 준비가 필요합니다.","기존 합성 업무에 견적서 준비가 누적됐습니다.",tasks,JSONObject(frozenHandoff).getString("frozenRequestJson")),
                AutomationAssurance("LOCAL_GENERATED_FIXTURE_ONLY_NOT_REAL_EXECUTION",trusted,trusted,trusted,trusted,trusted))
        }
    }
    private class Notice:LocalReportNotificationPort {
        var permission="READY";var posts=0
        override fun gate()=permission
        override fun post(key:String,date:LocalDate){posts++}
    }
    private fun run(p:GoogleNativePipeline,provider:AutomationProvider)=store().use{s->requests(p).use{r->AndroidAutomationCoordinator(s,r,provider).cycle()}}
    private fun proof(id:String,extra:JSONObject=JSONObject()) {
        extra.put("id",id).put("pass",true).put("executionScope","LOCAL_API35_NATIVE_SQLITE_JCE_GENERATED_WAV_FAKE_DRIVE_PROVIDER")
            .put("actualAccountLogin",0).put("actualPermissionChanges",0).put("actualUploads",0).put("actualModelExecutions",0).put("actualKakaoSends",0).put("actualDeviceOrAVD","NOT_RUN")
        File("build/reports/android-automation").apply{mkdirs()}.let{File(it,"$id.json").writeText(extra.toString(2))}
    }
    private val noNewRequest=object:AutomationRequests {
        override fun prepare(recording:String,snapshot:JSONObject):String=error("RESULT_WORK_MUST_NOT_PREPARE")
        override fun publish(request:String):String=error("RESULT_WORK_MUST_NOT_PUBLISH")
        override fun handoff(request:String):String=error("RESULT_WORK_MUST_NOT_CREATE_HANDOFF")
        override fun state(request:String):String=error("RESULT_WORK_MUST_NOT_OPEN_NATIVE_DELIVERY")
        override fun close(){}
    }
    @Test fun E01PendingResultResumesAfterForegroundClosesWithoutSecondRequestAndReaches06Copy()=runBlocking(Dispatchers.IO){
        val actual=Provider();var waiting=true
        val delayed=object:AutomationProvider by actual {
            override fun receive(key:String,receipt:String,frozenHandoff:String):AutomationObservation {
                if(waiting){waiting=false;return AutomationObservation.Waiting("PUBLIC_MODEL_STILL_PROCESSING")}
                return actual.receive(key,receipt,frozenHandoff)
            }
        }
        val first=pipeline().use{run(it,delayed).single()};assertEquals("WAITING_RESULT",first.getString("state"));assertEquals(1,actual.submitted);assertEquals(0,actual.received);assertEquals(4,drive.counter)
        val scheduled=mutableListOf<androidx.work.OneTimeWorkRequest>();val names=mutableListOf<String>()
        AutomaticResultReconciliation.installForHost(object:AutomaticResultWorkRegistry {override fun once(name:String,request:androidx.work.OneTimeWorkRequest){names+=name;scheduled+=request}})
        AutomaticResultReconciliation.ensure(context,session,listOf(first),clock)
        assertEquals(1,scheduled.size);assertEquals(30000L,scheduled.single().workSpec.initialDelay);assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL,scheduled.single().workSpec.backoffPolicy)
        clock+=30000
        val received=store().use{s->AndroidAutomationCoordinator(s,noNewRequest,delayed).cycle(receiveExistingOnly=true).single()}
        assertEquals("APPLIED_SAFE_DERIVED",received.getString("state"));assertEquals(first.getString("request_id"),received.getString("request_id"));assertEquals(1,actual.submitted);assertEquals(1,actual.received);assertEquals(4,drive.counter)
        assertEquals(1L,count("workflow_analysis_requests"));assertEquals(1L,count("automation_results"));assertEquals(1L,count("automation_cumulative_history"))
        store().use{s->
            assertTrue(s.pendingResults().isEmpty());val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"));val batch=s.reportBatch(window);assertTrue(batch.getBoolean("complete"))
            val notice=Notice();val report=AutomationLocalReportCoordinator(s,notice){true};assertEquals("LOCAL_NOTIFY_REQUESTED",report.run(window,batch,window.due.plusSeconds(1)).getString("state"))
            report.run(window,batch,window.due.plusSeconds(60));assertEquals(1,notice.posts);assertEquals(1L,count("automation_local_reports"))
        }
        clock=AutomationPendingResults.deadline(first)!!+1
        pipeline().use{assertEquals("APPLIED_SAFE_DERIVED",run(it,delayed).single().getString("state"))}
        assertEquals(1,actual.received);assertEquals(4,drive.counter)
        proof("E01",JSONObject().put("completedReceiptPreservedAfterLeaseExpiry",true).put("previousBackgroundDiscoveryCadenceMs",900000).put("existingResultWorkerInitialDelayMs",30000).put("sameRequestResumed",true).put("newRequestsAfterPending",0).put("providerDispatches",1).put("nativeReservedIds",4).put("safeHostCumulativeHistory",1).put("local06Copies",1).put("localNotifierCalls",1).put("actualSTT","NOT_RUN_FIXTURE_PROVIDER_ONLY"))
    }
    @Test fun E02OriginalLeaseExpiryStopsResultReadsAndNeverRenewsOrResubmits()=runBlocking(Dispatchers.IO){
        var reads=0;val actual=Provider();val pending=object:AutomationProvider by actual {override fun receive(key:String,receipt:String,frozenHandoff:String):AutomationObservation {reads++;return AutomationObservation.Waiting("PUBLIC_MODEL_PENDING")}}
        val first=pipeline().use{run(it,pending).single()};assertEquals(1,reads);assertFalse(AutomationPendingResults.known(JSONObject(first.toString()).put("state","WAITING_LOCAL_RESUME").put("receipt","")));clock=AutomationPendingResults.deadline(first)!!+1
        val held=store().use{AndroidAutomationCoordinator(it,noNewRequest,pending).cycle(receiveExistingOnly=true).single()}
        assertEquals(1,reads);assertEquals("FAILED",held.getString("state"));assertEquals("AUTOMATION_RESULT_WINDOW_EXPIRED_OR_INVALID",held.getString("reason"));assertEquals(1,actual.submitted);assertEquals(0,actual.received);assertEquals(4,drive.counter)
        assertEquals(0L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(1L,count("workflow_analysis_requests"))
        proof("E02",JSONObject().put("originalLeaseHonored",true).put("expiredResultReads",0).put("leaseRenewals",0).put("newRequests",0))
    }
    @Test fun E03ActualWorkerRetriesPendingOnlyInsideCurrentNativeScopeAndStopsAfterLogout()=runBlocking(Dispatchers.IO){
        val scope=DriveALocalScope.current(session)!!;var reads=0
        AutomaticResultReconciliation.installForHost(reconcile={_,_->reads++;JSONObject().put("state","WAITING_RESULT").put("pendingExistingResults",1)})
        fun worker(binding:String=scope.key,attempt:Int=0):AutomationPendingResultWorker {
            val executor=java.util.concurrent.Executor{it.run()}
            val parameters=androidx.work.WorkerParameters(java.util.UUID.randomUUID(),androidx.work.workDataOf("owner" to scope.owner,"epoch" to scope.epoch,"native_scope" to binding),emptyList(),androidx.work.WorkerParameters.RuntimeExtras(),attempt,0,
                executor,Dispatchers.Unconfined,androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor(executor),object:androidx.work.WorkerFactory(){override fun createWorker(context:Context,name:String,parameters:androidx.work.WorkerParameters):androidx.work.ListenableWorker?=null},
                androidx.work.ProgressUpdater{_,_,_->error("unused")},androidx.work.ForegroundUpdater{_,_,_->error("unused")})
            return AutomationPendingResultWorker(context,parameters)
        }
        assertEquals(androidx.work.ListenableWorker.Result.retry(),worker().doWork());assertEquals(1,reads)
        assertEquals(androidx.work.ListenableWorker.Result.failure(androidx.work.workDataOf("automation_state" to "STALE_NATIVE_SCOPE","reason" to "")),worker("PUBLIC_STALE_SCOPE").doWork());assertEquals(1,reads)
        assertEquals(androidx.work.ListenableWorker.Result.failure(androidx.work.workDataOf("automation_state" to "RESULT_POLL_BACKOFF_LIMIT")),worker(attempt=6).doWork());assertEquals(1,reads)
        session.localSignOut();assertEquals(androidx.work.ListenableWorker.Result.failure(androidx.work.workDataOf("automation_state" to "STALE_NATIVE_SCOPE","reason" to "")),worker().doWork());assertEquals(1,reads)
        proof("E03",JSONObject().put("workerPendingRetry",true).put("staleScopeAndLogoutReads",0).put("maxAttemptsBounded",true).put("grantOrOAuthRefresh",0))
    }
    @Test fun E04ConfirmedExistingAccountRearmsOnlyOriginalPendingResultInsideItsLease()=runBlocking(Dispatchers.IO){
        val actual=Provider();val pending=object:AutomationProvider by actual {override fun receive(key:String,receipt:String,frozenHandoff:String)=AutomationObservation.Waiting("PUBLIC_MODEL_PENDING")}
        val first=pipeline().use{run(it,pending).single()};val binding=DriveALocalScope.current(session)!!;val deadline=AutomationPendingResults.deadline(first)!!
        val queued=mutableListOf<androidx.work.OneTimeWorkRequest>()
        AutomaticResultReconciliation.installForHost(object:AutomaticResultWorkRegistry{override fun once(name:String,request:androidx.work.OneTimeWorkRequest){queued+=request}})
        session.invalidate(AuthorizationPort.State.TOKEN_EXPIRED)
        AutomaticResultReconciliation.rearmExisting(context,session,clock);assertTrue(queued.isEmpty())
        val epoch=session.begin(true);session.activate(epoch,binding.account,binding.account,binding.owner,"PUBLIC_RECHECKED_RAM_TOKEN")
        AutomaticResultReconciliation.rearmExisting(context,session,clock)
        assertEquals(1,queued.size);assertEquals(binding.key,queued.single().workSpec.input.getString("native_scope"))
        assertEquals(deadline,AutomationPendingResults.deadline(store().use{it.jobs().single()}));assertEquals(1,actual.submitted);assertEquals(4,drive.counter)
        queued.clear();AutomaticResultReconciliation.rearmExisting(context,session,deadline+1);assertTrue(queued.isEmpty())
        assertEquals(1L,count("workflow_analysis_requests"));assertEquals(0L,count("automation_cumulative_history"))
        proof("E04",JSONObject().put("rearmedOriginalResultOnly",true).put("newRequests",0).put("leaseRenewals",0).put("expiredLeaseRearms",0))
    }
}
