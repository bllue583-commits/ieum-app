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
class AndroidAutomationIntegrationTest:ProtectedTextHostTestBase() {
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
    @Test fun A01AutomaticIntakeToDeduplicatedRequestAccumulationAnd06QueueSurvivesReopen()=runBlocking(Dispatchers.IO) {
        val provider=Provider()
        pipeline().use{p->
            val first=run(p,provider).single();assertEquals("APPLIED_SAFE_DERIVED",first.getString("state"))
            assertEquals(1,provider.submitted);assertEquals(4,drive.counter)
            assertEquals(1L,count("discovery_intake_requests"));assertEquals(1L,count("workflow_analysis_requests"));assertEquals(1L,count("automation_results"))
            assertEquals(1L,count("workflow_items"));assertEquals(1L,count("automation_cumulative_history"));assertEquals(1L,count("automation_case_heads"))
            store().use{s->
                val q=s.reportQueue().single();assertEquals("READY_FOR_REPORT_CONSUMER",q.getString("state"))
                assertEquals("2026-10-11",q.getString("day"));assertEquals(Instant.parse("2026-10-12T06:00:00Z").toEpochMilli(),q.getLong("due_ms"))
                val batch=s.reportBatch(ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC")))
                assertTrue(batch.getBoolean("complete"));assertEquals(1,batch.getJSONArray("automatic").length());assertEquals(1,batch.getJSONArray("automatic").getJSONObject(0).getJSONArray("currentItems").length())
                val scope=DailyReportRuntime.scope(context)
                DailyReportLedger(scope.context,scope.profile).use{it.setEnabled(true,Instant.ofEpochMilli(clock),ZoneId.of("Etc/UTC"))}
                val schedules=mutableListOf<String>()
                DailyReportSchedule.installRegistryForHost(object:DailyReportWorkRegistry{
                    override fun cancel(tag:String){};override fun periodic(name:String,request:androidx.work.PeriodicWorkRequest){schedules+=name}
                    override fun once(name:String,request:androidx.work.OneTimeWorkRequest){schedules+=name}
                })
                AndroidAutomationRuntime.bridgeReportQueue(context,s)
                assertTrue(s.reportQueue().single().getString("report_id").isNotBlank());assertTrue(schedules.any{it.contains(".target.")})
                assertEquals(kr.callbrief.kakao.core.DeliveryResult.Status.DISABLED,KakaoDailyReportSender(scoped).gate()!!.status)
                assertFalse(kr.callbrief.kakao.android.KakaoConfiguration.fromResources(scoped).liveSendEnabled)
                val notice=Notice();val local=AutomationLocalReportCoordinator(s,notice){true}
                val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"))
                assertEquals("WAITING_06",local.run(window,batch,window.due.minusSeconds(1)).getString("state"));assertEquals(0,notice.posts)
                assertEquals("LOCAL_NOTIFY_REQUESTED",local.run(window,batch,window.due.plusSeconds(1)).getString("state"))
                local.run(window,batch,window.due.plusSeconds(60));assertEquals(1,notice.posts);assertEquals(1L,count("automation_local_reports"))
                DailyReportLedger(scope.context,scope.profile).use{assertEquals(ReportDelivery.NOT_STORED,it.list().single().delivery)}
                proof("A01",JSONObject().put("automaticStages",JSONArray(listOf("NATIVE_DISCOVERY_AND_ENCRYPTED_INTAKE","DEDUPLICATED_NATIVE_REQUEST","PROVIDER_RECEIPT","RESULT_RECEIPT","SAFE_DERIVED_SUMMARY_AND_TODO","DURABLE_06_QUEUE_EXISTING_REPORT_SLOT","LOCAL_06_COPY_AND_NOTIFICATION_REQUEST")))
                    .put("jobId",first.getString("id")).put("sourceSha",first.getString("audio_sha")).put("requestId",first.getString("request_id"))
                    .put("providerDispatches",1).put("reportQueue",q).put("reportInput",batch).put("kakaoGate","DISABLED")
                    .put("local06ReportCopies",1).put("localNotifierCalls",1).put("localNotifierScope","INJECTED_LOCAL_PORT_NOT_DEVICE")
                    .put("kakaoDispatch","NOT_RUN_REPORT_CONSUMER_CONTRACT_PENDING"))
            }
            clock+=1000;assertEquals("APPLIED_SAFE_DERIVED",run(p,provider).single().getString("state"))
            assertEquals(1,provider.submitted);assertEquals(4,drive.counter);assertEquals(1L,count("automation_jobs"));assertEquals(1L,count("automation_results"));assertEquals(1L,count("workflow_items"));assertEquals(1L,count("automation_applications"))
        }
    }
    @Test fun A02MissingAndUnapprovedProviderPerformZeroPreparationOrTransfer()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val opens=source.opens;assertEquals("WAITING_PROVIDER",run(p,UnconfiguredAutomationProvider).single().getString("state"))
        assertEquals(0L,count("workflow_analysis_requests"));assertEquals(0,drive.counter);assertEquals(opens,source.opens)
        assertEquals("WAITING_APPROVAL",run(p,Provider().apply{approved=false}).single().getString("state"));assertEquals(0,drive.counter)
        store().use{s->assertFalse(s.reportBatch(ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"))).getBoolean("complete"))}
        proof("A02",JSONObject().put("nativeRequests",0).put("driveTransfers",0).put("states",JSONArray(listOf("WAITING_PROVIDER","WAITING_APPROVAL"))))
    }}
    @Test fun A03LostAcceptanceAcknowledgementReconcilesOriginalJobWithoutRedispatch()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{failAck=true};val first=run(p,provider).single();assertEquals("UNKNOWN",first.getString("state"))
        assertEquals(1,provider.submitted);clock+=1000
        assertEquals("APPLIED_SAFE_DERIVED",run(p,provider).single().getString("state"));assertEquals(1,provider.submitted);assertEquals(1,provider.reconciled);assertEquals(4,drive.counter)
        proof("A03",JSONObject().put("dispatches",provider.submitted).put("reconciliations",provider.reconciled).put("originalJobPreserved",true))
    }}
    @Test fun A04ManualDoneDuringResultArrivalRemainsCanonicalAndResultBecomesReview()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{beforeReceive={GeneralLocalStoreTest.host(scoped,keys).use{s->s.edit(recording,s.snapshot().getJSONObject(0),"PUBLIC_MANUAL_DONE_CANARY","done")}}}
        val result=run(p,provider).single();assertEquals(result.getString("reason"),"REVIEW_REQUIRED",result.getString("state"));assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_MANUAL_DONE_CANARY",snap().getString("manual_text"))
        assertEquals(1L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));assertEquals(0L,count("workflow_items"))
        store().use{assertEquals(1,it.reportBatch(ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"))).getJSONArray("unresolved").length())}
        proof("A04",JSONObject().put("manualDonePreserved",true).put("immutableResultPreserved",true).put("automaticChanges",0))
    }}
    @Test fun A05ExclusionAfterDispatchPreventsResultReadAndPersistence()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{afterDispatch={source.excludedIds.add("one")}}
        assertEquals("HELD_SOURCE",run(p,provider).single().getString("state"));assertEquals(0,provider.received);assertEquals(0L,count("automation_results"))
        run(p,provider);assertEquals(1,provider.submitted);assertEquals(0,provider.received)
        proof("A05",JSONObject().put("resultReadsAfterExclusion",0).put("storedResultBodies",0).put("resends",0))
    }}
    @Test fun A06ManualItemEditAndDoneAreNeverOverwrittenByNewerAutomaticResult()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider();run(p,provider)
        val item=snap().getJSONArray("items").getJSONObject(0);val itemId=item.getString("id")
        RecordingWorkflowStore(scoped).use{it.editItem(itemId,item.getLong("revision"),"PUBLIC_ITEM_MANUAL_DONE","done","2026-11-01","")}
        provider.target=itemId;provider.revision=2;clock+=900001;run(p,provider)
        val kept=snap().getJSONArray("items").getJSONObject(0);assertEquals("done",kept.getString("status"));assertEquals("PUBLIC_ITEM_MANUAL_DONE",kept.getString("title"));assertEquals("2026-11-01",kept.getString("due_text"))
        assertEquals(2L,count("automation_results"));assertEquals(1L,count("workflow_item_history"))
        store().use{s->assertFalse(s.reportBatch(ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"))).getBoolean("complete"))}
        proof("A06",JSONObject().put("manualItemDoneTitleDuePreserved",true).put("resultVersions",2).put("reportComplete",false))
    }}
    @Test fun A07SameRevisionDifferentDigestFailsWithoutReplacingHistory()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider();run(p,provider);provider.target="another-target";clock+=900001
        assertEquals("FAILED",run(p,provider).single().getString("state"));assertEquals(1L,count("automation_results"));assertEquals(1L,count("automation_cumulative_history"));assertEquals(1L,count("workflow_items"))
        proof("A07",JSONObject().put("conflictingRevisionRejected",true).put("existingHistoryPreserved",true))
    }}
    @Test fun A08UnverifiedExecutionIsSavedAsProposalWithoutUniversalManualGateForTrustedCase()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{trusted=false};assertEquals("REVIEW_REQUIRED",run(p,provider).single().getString("state"))
        assertEquals(1L,count("automation_results"));assertEquals(0L,count("workflow_items"));assertEquals(0L,count("automation_cumulative_history"))
        proof("A08",JSONObject().put("unattestedProposalPreserved",true).put("automaticWrites",0).put("trustedAutoBranchEvidence","A01"))
    }}
    @Test fun A09ConcurrentClaimAndExpiredLeaseCannotBeClearedByOldWorker()=runBlocking(Dispatchers.IO){
        store().use{a->a.admitCurrentSources();val id=a.jobs().single().getString("id");assertTrue(a.claim(id))
            store().use{b->assertFalse(b.claim(id));clock+=120001;assertTrue(b.claim(id));a.release(id);assertTrue(b.job(id).getString("claim_token").isNotBlank());b.release(id)}
        };proof("A09",JSONObject().put("concurrentClaimBlocked",true).put("oldReleaseCannotClearNewLease",true))
    }
    @Test fun A10PolicyRevocationDuringResultArrivalRollsBackAllResultChanges()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{beforeReceive={policy="PUBLIC_POLICY_REVOKED"}}
        assertEquals("HELD_SOURCE",run(p,provider).single().getString("state"));assertEquals(0L,count("automation_results"));assertEquals(0L,count("workflow_items"))
        proof("A10",JSONObject().put("resultPersistenceAfterPolicyChange",0).put("automaticChanges",0))
    }}
    @Test fun A11ExternalApiV2ExactContractConnectsButCannotClaimDotReviewOrAutomaticAuthority()=runBlocking(Dispatchers.IO){pipeline().use{p->
        var enrollments=0;var frozen="";var raw=byteArrayOf()
        val http=object:AutomationProcessingHttp {
            override fun call(method:String,path:String,body:ByteArray?):AutomationHttpReply {
                if(path.endsWith("/capabilities"))return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.capabilities.v1","ownerId" to session.ownerBinding(),"driveAccount" to session.driveAccount(),"accountEpoch" to session.epoch(),"reportZone" to "Etc/UTC","resultKind" to AutomationV2Codec.KIND,"resultSchemaVersion" to 2L,"driveResultWritten" to false,"dotInvoked" to false,"canonicalWriteAllowed" to false)))
                if(method=="GET")return AutomationHttpReply(200,raw)
                check(method=="PUT");enrollments++;val enrollment=JSONObject(String(checkNotNull(body)))
                assertEquals("ieum.automation.enrollment.v1",enrollment.getString("schema"));assertEquals("Etc/UTC",enrollment.getString("reportZone"))
                frozen=enrollment.getJSONObject("handoff").getString("frozenRequestJson")
                val request=StrictJson.`object`(StrictJson.parse(frozen.toByteArray()))
                val value=linkedMapOf<String,Any?>()
                listOf("jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest").forEach{value[it]=request[it]}
                val businessSha=AndroidAutomationStore.hash(StrictJson.`object`(StrictJson.parse(enrollment.getJSONObject("businessContext").toString().toByteArray())))
                val text="합성 견적서를 준비해 주세요."
                val tasks=listOf(mapOf("taskId" to null,"title" to "합성 견적서 준비","assignee" to "합성 담당","due" to null,"status" to "open","quote" to text,"start" to 0L,"end" to text.codePointCount(0,text.length).toLong()))
                value.putAll(mapOf("businessContextSha" to businessSha,"deliveryChannel" to "AUTHENTICATED_WORKER_ENDPOINT","driveResultWritten" to false,
                    "schemaVersion" to 2L,"requestSchemaVersion" to 0L,"kind" to "CALL_AUTOMATION_API_PROPOSAL","state" to "PROPOSAL","resultRevision" to 1L,
                    "stt" to mapOf("provider" to "OPENAI_API","model" to "PUBLIC_STT_FIXTURE_MODEL","requestId" to "PUBLIC_STT_FIXTURE_REQUEST","text" to text,"textSha" to DriveRelay.sha256(text.toByteArray())),
                    "analysis" to mapOf("provider" to "OPENAI_API","model" to "PUBLIC_SUMMARY_FIXTURE_MODEL","requestId" to "PUBLIC_SUMMARY_FIXTURE_REQUEST","summary" to "합성 견적서 준비가 필요합니다.","cumulativeSummary" to "기존 합성 업무에 견적서 준비가 누적됐습니다.","tasks" to tasks),
                    "dotExecution" to "NOT_INVOKED","executionEvidence" to "PROVIDER_RESPONSES_RECORDED_NOT_INDEPENDENTLY_ATTESTED","writerVerification" to "UNRESOLVED","nativeBinding" to "UNVERIFIED","displayMode" to "READ_ONLY_PROPOSAL","canonicalWriteAllowed" to false,"applied" to false))
                value["resultDigest"]=AutomationV2Codec.digest(value);raw=StrictJson.bytes(value)
                File("build/reports/android-automation").apply{mkdirs()}.let{d->File(d,"A11_V2_RESULT.json").writeBytes(raw);File(d,"A11_FROZEN_REQUEST.json").writeText(frozen);File(d,"A11_ENROLLMENT.json").writeBytes(body)}
                return AutomationHttpReply(200,StrictJson.bytes(mapOf("schema" to "ieum.automation.job-status.v1","jobId" to request["jobId"],"requestSha" to DriveRelay.sha256(frozen.toByteArray()),"enrollmentSha" to DriveRelay.sha256(body),"dotInvoked" to false,"driveResultWritten" to false,"state" to "RESULT_READY")))
            }
        }
        val cfg=AutomationApiConfiguration(true,"https://public-fixture.example.invalid",session.ownerBinding(),session.driveAccount(),session.epoch(),"Etc/UTC","PUBLIC_NON_SECRET_SYNTHETIC_FIXTURE_CREDENTIAL")
        val processing=ProductionAutomationProcessingPort(context,session,{cfg},{ExistingDriveARequestPort(context,session){NativePort(p)}},{http},object:AutomationProposalReceiver{
            override fun receive(job:String,bytes:ByteArray,contextSha:String)=p.receiveAutomationProposal(job,bytes,contextSha)
        })
        store().use{adapterStore->
        val adapter=AutomationProcessingProvider(adapterStore,processing){AutomationGate("READY","PUBLIC_CONTRACT_FIXTURE_ONLY")}
        assertEquals("REVIEW_REQUIRED",requests(p).use{AndroidAutomationCoordinator(adapterStore,it,adapter).cycle()}.single().getString("state"));assertEquals(1,enrollments)
        assertEquals(1L,count("automation_results"));assertEquals(1L,count("drive_profiles_receipts"));assertEquals(0L,count("workflow_items"));assertEquals(0L,count("automation_cumulative_history"))
        val bad=JSONObject(String(raw)).put("dotExecution","VERIFIED")
        assertThrows(IllegalArgumentException::class.java){AutomationV2Codec().validate(bad.toString().toByteArray(),frozen.toByteArray())}
        proof("A11",JSONObject().put("externalV2ConsumerConnected",true).put("nativeProtectedReceipts",1).put("unattestedV2AutomaticWrites",0).put("falseDotClaimRejected",true))
        }
    }}
    @Test fun A12StoppedWorkAfterAcceptanceReconcilesWithoutSecondDispatch()=runBlocking(Dispatchers.IO){pipeline().use{p->
        var stopped=false;val provider=Provider().apply{afterDispatch={stopped=true}}
        store().use{s->requests(p).use{assertEquals("UNKNOWN",AndroidAutomationCoordinator(s,it,provider){stopped}.cycle().single().getString("state"))}}
        assertEquals(1,provider.submitted);assertEquals(0,provider.received);stopped=false
        assertEquals("APPLIED_SAFE_DERIVED",run(p,provider).single().getString("state"));assertEquals(1,provider.submitted);assertEquals(1,provider.reconciled)
        proof("A12",JSONObject().put("stopAfterAcceptance",true).put("dispatches",1).put("reconciliations",1))
    }}
    @Test fun A13RelatedEvidenceAuthorityRevocationPreventsResultPersistence()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{beforeReceive={db().use{it.execute("INSERT INTO workflow_analysis_authority(recording_id,epoch) VALUES(?,1)",recording)}}}
        assertEquals("HELD_SOURCE",run(p,provider).single().getString("state"));assertEquals(0L,count("automation_results"));assertEquals(0L,count("workflow_items"))
        proof("A13",JSONObject().put("resultBodiesAfterEvidenceRevocation",0).put("automaticChanges",0))
    }}
    @Test fun A14ApprovalWithdrawalCannotEraseUncertainAcceptanceOrPermitRedispatch()=runBlocking(Dispatchers.IO){pipeline().use{p->
        val provider=Provider().apply{afterDispatch={approved=false}}
        assertEquals("UNKNOWN",run(p,provider).single().getString("state"));assertEquals(1,provider.submitted);assertEquals(0,provider.received)
        assertEquals("UNKNOWN",run(p,provider).single().getString("state"));assertEquals(0,provider.reconciled)
        provider.approved=true;provider.afterDispatch={}
        assertEquals("APPLIED_SAFE_DERIVED",run(p,provider).single().getString("state"));assertEquals(1,provider.submitted);assertEquals(1,provider.reconciled)
        proof("A14",JSONObject().put("unknownPreservedWhileUnapproved",true).put("dispatches",1).put("reconciliationsAfterApproval",1))
    }}
    @Test fun A15CorruptFractionalQueueCursorIsHeldWithoutNormalizationOrTransfer()=runBlocking(Dispatchers.IO){pipeline().use{p->
        run(p,UnconfiguredAutomationProvider)
        TextStoreTestSupport.open(scoped,GeneralLocalStore.DATABASE).use{it.execSQL("UPDATE automation_jobs SET result_revision=1.5")}
        store().use{s->val error=assertThrows(IllegalStateException::class.java){s.jobs()};assertEquals("AUTOMATION_STORE_REVIEW_REQUIRED",error.message)}
        db().use{assertEquals(1.5,(it.query("SELECT result_revision FROM automation_jobs").single()["result_revision"] as Number).toDouble(),0.0)}
        assertEquals(0,drive.counter);assertEquals(0L,count("workflow_analysis_requests"))
        proof("A15",JSONObject().put("fractionalCursorPreserved",1.5).put("nativeRequests",0).put("transfers",0))
    }}
    @Test fun A16NotificationPermissionWaitPreservesPartialCopyAndDoesNotPromptOrPost()=runBlocking(Dispatchers.IO){pipeline().use{p->
        run(p,Provider().apply{trusted=false})
        store().use{s->val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"));val batch=s.reportBatch(window);assertFalse(batch.getBoolean("complete"))
            val notice=Notice().apply{permission="WAITING_NOTIFICATION_PERMISSION"};val coordinator=AutomationLocalReportCoordinator(s,notice){true}
            assertEquals("WAITING_NOTIFICATION_PERMISSION",coordinator.run(window,batch,window.due).getString("state"));assertEquals(0,notice.posts);assertEquals(1L,count("automation_local_reports"))
            notice.permission="READY";assertEquals("LOCAL_NOTIFY_REQUESTED",coordinator.run(window,batch,window.due.plusSeconds(900)).getString("state"));assertEquals(1,notice.posts);assertEquals(1L,count("automation_local_reports"))
        };proof("A16",JSONObject().put("permissionPromptCalls",0).put("partialCopyPreserved",true).put("notifyBeforePermission",0))
    }}
    @Test fun A17NativeLocalNotificationIsPrivateGenericAndOpensOnlyStoredReportScreen()=runBlocking(Dispatchers.IO){
        val app=context.applicationContext as android.app.Application
        val shadow=org.robolectric.Shadows.shadowOf(app);shadow.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifier=AndroidLocalReportNotifier(context){true};assertEquals("WAITING_NOTIFICATION_PERMISSION",notifier.gate())
        shadow.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS) // Host shadow only, no actual OS permission.
        val manager=context.getSystemService(android.app.NotificationManager::class.java);org.robolectric.Shadows.shadowOf(manager).setNotificationsEnabled(true)
        assertEquals("READY",notifier.gate());notifier.post("PUBLIC_OPAQUE_LOCAL_FIXTURE",LocalDate.parse("2026-10-11"))
        val notification=org.robolectric.Shadows.shadowOf(manager).allNotifications.single()
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility);assertFalse(notification.extras.toString().contains("견적서"))
        val intent=org.robolectric.Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(DailyReportActivity::class.java.name,intent.component!!.className);assertFalse(intent.hasExtra("owner"));assertFalse(intent.hasExtra("send"))
        proof("A17",JSONObject().put("nativeNotifierExecutedInHostShadow",true).put("actualOSPermissionChanges",0).put("lockScreenContainsSourceContent",false))
    }
    @Test fun A18AutomaticKakaoSenderIsUnconditionallyDisabledAndManualInterfaceRemains()=runBlocking(Dispatchers.IO){
        val sender=KakaoDailyReportSender(scoped);assertEquals(kr.callbrief.kakao.core.DeliveryResult.Status.DISABLED,sender.gate()!!.status)
        val report=kr.callbrief.kakao.core.DailyReport("PUBLIC_LOCAL_ONLY_PROFILE",LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"),"PUBLIC_REPORT_NOT_SENT",true,false)
        assertEquals(kr.callbrief.kakao.core.DeliveryResult.Status.DISABLED,sender.send(report).status)
        assertTrue(kr.callbrief.kakao.android.KakaoReportClient::class.java.methods.any{it.name=="sendFinalized"})
        proof("A18",JSONObject().put("automaticKakao","POLICY_HELD_UNCONDITIONAL_DISABLED").put("manualSendInterfacePresent",true).put("actualSends",0))
    }
    @Test fun A19LocalCopyReaderIsReadOnlyAndPolicyChangeHidesContentWithoutDeletingCopy()=runBlocking(Dispatchers.IO){pipeline().use{p->
        run(p,Provider());store().use{s->val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"));s.saveLocalReport(window,s.reportBatch(window),window.due.toEpochMilli())}
        val file=kr.co.callsummary.core.android.ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE);val before=file.readBytes()
        val rows=AutomationLocalReportReader.read(scoped,session.ownerBinding(),{policy},{true});assertTrue(rows.single().second.contains("견적서"));assertArrayEquals(before,file.readBytes())
        val hidden=AutomationLocalReportReader.read(scoped,session.ownerBinding(),{"PUBLIC_POLICY_CHANGED"},{true});assertFalse(hidden.single().second.contains("견적서"));assertArrayEquals(before,file.readBytes());assertEquals(1L,count("automation_local_reports"))
        proof("A19",JSONObject().put("sealedBytesUnchanged",true).put("policyChangedContentHidden",true).put("immutableCopies",1))
    }}
    @Test fun A20InterruptedNotificationIntentIsUnknownAndNeverAutomaticallyRepeated()=runBlocking(Dispatchers.IO){pipeline().use{p->
        run(p,Provider());val window=ReportWindow(LocalDate.parse("2026-10-11"),ZoneId.of("Etc/UTC"))
        store().use{s->val (_,alert)=s.saveLocalReport(window,s.reportBatch(window),window.due.toEpochMilli());assertTrue(s.claimLocalAlert(alert,window.due.toEpochMilli()))}
        store().use{s->val notice=Notice();val result=AutomationLocalReportCoordinator(s,notice){true}.run(window,s.reportBatch(window),window.due.plusSeconds(900));assertEquals("UNKNOWN",result.getString("state"));assertEquals(0,notice.posts)}
        proof("A20",JSONObject().put("interruptedIntent","UNKNOWN").put("automaticRepeatCalls",0).put("copyPreserved",true))
    }}
    private val proofKey="00000000-0000-4000-8000-000000000021"
    private val proofQueue="PUBLIC_APPROVED_DOT_QUEUE"
    private var proofTime=Instant.parse("2026-10-14T08:00:00Z")
    private inner class TextDrive:DriveTransport by drive {
        var loseDescriptorAck=false;var blankVersion=false;val writes=mutableListOf<String>()
        override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata {
            if(spec.id in drive.specs)throw DriveTransport.DriveException(DriveTransport.Failure.CONFLICT)
            val result=drive.create(spec,content);writes+=spec.name
            if(loseDescriptorAck && spec.name.startsWith("enrollment-")){loseDescriptorAck=false;throw DriveTransport.DriveException(DriveTransport.Failure.TRANSIENT)}
            return result
        }
        override fun metadata(id:String):DriveTransport.Metadata {val m=drive.metadata(id);return if(!blankVersion)m else DriveTransport.Metadata(m.id,m.parentId,m.name,m.mimeType,m.ownerBinding,"",m.trashed,m.isAppAuthorized,m.privateToOwner,m.size)}
    }
    private fun textDrive()=TextDrive().also {
        drive.specs[proofQueue]=DriveTransport.FileSpec(proofQueue,null,"PUBLIC_FIXED_TEXT_QUEUE",DriveTransport.FOLDER_MIME,session.ownerBinding(),DriveTransport.Role.PRIVATE_FOLDER)
        drive.bytes[proofQueue]=byteArrayOf();drive.versions[proofQueue]=1
    }
    private fun textCoordinator(tx:TextDrive,plan:ScheduledDotProofPlan=ScheduledDotProofPlan(true,proofKey,proofQueue)):ScheduledDotProofCoordinator {
        val native=DotScheduledTextExchangePort(context,session,{DotTextExchangeConfiguration(plan.enabled,plan.queueFolder)},ScheduledProofVersionCheckedDrive(tx)){proofTime}
        return ScheduledDotProofCoordinator(context,session,plan,NativeScheduledDotProofPort(native),{currentScope},{proofTime})
    }
    private fun writeTextProof(reg:JSONObject) {
        val body=JSONObject(String(javaClass.getResourceAsStream("/automation-proofs/scheduled-result.json")!!.readBytes(),Charsets.UTF_8))
        body.put("job_id",proofKey).put("source_file_id",reg.getString("input")).put("produced_at_utc",Instant.parse("2026-10-14T08:01:00Z").toString())
        drive.bytes[reg.getString("result")]=body.toString().toByteArray();drive.versions[reg.getString("result")]=2;proofTime=Instant.parse("2026-10-14T08:03:00Z")
    }
    @Test fun B21ScheduledFixedTextPublishesAndPollsOriginalSlotWithoutAudioCompletion()=runBlocking(Dispatchers.IO){
        val tx=textDrive();val initial=textCoordinator(tx).cycle();assertEquals("DOT_TEXT_PENDING",initial.getString("state"));assertEquals(3,drive.counter);assertTrue(tx.writes.last().startsWith("enrollment-"))
        val reg=initial.getJSONObject("registration");writeTextProof(reg)
        val read=textCoordinator(tx).cycle();assertEquals("READ_ONLY_SCHEDULED_TEXT_PROPOSAL",read.getString("state"));assertEquals(reg.toString(),read.getJSONObject("registration").toString());assertTrue(read.getString("resultSha").matches(Regex("[0-9a-f]{64}")))
        assertFalse(read.getBoolean("nativeAudioRequestCompleted"));assertFalse(read.getBoolean("sttExecuted"));assertFalse(read.getBoolean("canonicalWriteAllowed"));assertFalse(read.getBoolean("schedulerRegistered"))
        textCoordinator(tx).cycle();assertEquals(3,drive.counter);assertEquals(1L,count("scheduled_dot_text_receipts"));assertEquals(0L,count("workflow_analysis_requests"));assertEquals(0L,count("workflow_items"));assertFalse(drive.specs.values.any{it.role==DriveTransport.Role.AUDIO})
        proof("B21",JSONObject().put("textState",read).put("reservedIds",3).put("canonicalItems",0).put("actualSchedulerExecution","NOT_RUN_HOST_WRITER_FIXTURE_ONLY"))
    }
    @Test fun B22DisabledProofAndChangedPlanNeverCreateSuccessorSlots()=runBlocking(Dispatchers.IO){
        val tx=textDrive();assertEquals("DOT_TEXT_EXCHANGE_NOT_CONFIGURED",textCoordinator(tx,ScheduledDotProofPlan()).cycle().getString("state"));assertEquals(0,drive.counter)
        assertEquals("DOT_TEXT_EXCHANGE_NOT_CONFIGURED",ScheduledDotProofRuntime.reconcile(context).getString("state"))
        textCoordinator(tx).cycle();assertEquals(3,drive.counter)
        val changed=ScheduledDotProofPlan(true,"00000000-0000-4000-8000-000000000022","PUBLIC_OTHER_QUEUE")
        val held=textCoordinator(tx,changed).cycle();assertEquals("HELD",held.getString("state"));assertEquals("SCHEDULED_DOT_PLAN_CHANGED_REVIEW_REQUIRED",held.getString("reason"));assertEquals(3,drive.counter)
        proof("B22",JSONObject().put("disabledRemoteOperations",0).put("changedPlanHeld",true).put("successorIds",0))
    }
    @Test fun B23UncertainTextPublicationRequiresExplicitSameIdsResume()=runBlocking(Dispatchers.IO){
        val tx=textDrive().apply{loseDescriptorAck=true};val initial=textCoordinator(tx).cycle();assertEquals("PUBLICATION_UNCERTAIN",initial.getString("state"));val reg=initial.getJSONObject("registration")
        assertEquals("DOT_TEXT_PUBLICATION_RECONCILE_REQUIRED",textCoordinator(tx).cycle().getString("state"));assertEquals(3,drive.counter)
        val resumed=textCoordinator(tx).resumeExplicit();assertEquals("DOT_TEXT_PENDING",resumed.getString("state"));assertEquals(reg.toString(),resumed.getJSONObject("registration").toString());assertEquals(3,drive.counter);assertEquals(3,tx.writes.size)
        proof("B23",JSONObject().put("uncertainPublicationPreserved",true).put("explicitResumeSameIds",true).put("newIdsAfterUncertainty",0))
    }
    @Test fun B24MissingVersionAndChangedProofAreHeldWithoutOverwritingReceipt()=runBlocking(Dispatchers.IO){
        val tx=textDrive().apply{blankVersion=true};val blocked=textCoordinator(tx).cycle();assertEquals("HELD",blocked.getString("state"));assertEquals("SCHEDULED_DOT_METADATA_VERSION_REQUIRED",blocked.getString("reason"));assertEquals(0,drive.counter)
        tx.blankVersion=false;val sent=textCoordinator(tx).cycle();val reg=sent.getJSONObject("registration");writeTextProof(reg)
        assertEquals("READ_ONLY_SCHEDULED_TEXT_PROPOSAL",textCoordinator(tx).cycle().getString("state"));val id=reg.getString("result")
        val changed=JSONObject(String(drive.bytes.getValue(id))).put("summary","PUBLIC_CHANGED_AFTER_IMMUTABLE_RECEIPT");drive.bytes[id]=changed.toString().toByteArray();drive.versions[id]=3
        val held=textCoordinator(tx).cycle();assertEquals("HELD",held.getString("state"));assertEquals("DOT_TEXT_IMMUTABLE_RESULT_CONFLICT",held.getString("reason"));assertEquals(1L,count("scheduled_dot_text_receipts"));assertEquals(0L,count("workflow_items"))
        proof("B24",JSONObject().put("missingVersionBlocked",true).put("originalReceiptPreserved",true).put("canonicalWrites",0))
    }
}
