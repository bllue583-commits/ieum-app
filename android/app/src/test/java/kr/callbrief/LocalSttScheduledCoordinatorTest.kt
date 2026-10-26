package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.ProtectedDatabase
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
import java.time.Instant

/** Existing synthetic raw output only. All Drive writes are in-memory; no new model/device execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalSttScheduledCoordinatorTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var session:GoogleConnectionSession
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private val queue="PUBLIC_PREEXISTING_STT_QUEUE"
    private var now=Instant.parse("2026-10-14T09:00:00Z")
    private fun bytes(name:String)=javaClass.getResourceAsStream("/local-stt/$name")!!.readBytes()
    private fun input()=PreparedWhisperScheduledTextInput(bytes("scheduled-input.json"),bytes("stt-request-evidence.json"),DriveRelay.sha256(bytes("stt-request-evidence.json")))
    private fun plan(p:PreparedWhisperScheduledTextInput=input())=ScheduledDotProofPlan(true,p.jobId,queue,ScheduledDotInputMode.PREPARED_LOCAL_STT_SYNTHETIC,DriveRelay.sha256(p.forJob(p.jobId)),p.evidenceSha)
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        context=RuntimeEnvironment.getApplication()
        val memory=object:GoogleConnectionSession.Persistence {var value=JSONObject();override fun read()=JSONObject(value.toString());override fun write(v:JSONObject){value=JSONObject(v.toString())}}
        session=GoogleConnectionSession(memory);val e=session.begin();session.activate(e,"public.stt@example.invalid","public.stt@example.invalid","PUBLIC_STT_COORDINATOR_OWNER","PUBLIC_MEMORY_TOKEN")
        GoogleConnectionSession.installForHost(session);GeneralLocalStore.catalog(AccountStorageContext(context,session.ownerBinding()))
        RecordingWorkflowStore(AccountStorageContext(context,session.ownerBinding())).use{}
        drive=GoogleConnectionRegressionTest.FakeDrive()
        drive.specs[queue]=DriveTransport.FileSpec(queue,null,"PUBLIC_QUEUE",DriveTransport.FOLDER_MIME,session.ownerBinding(),DriveTransport.Role.PRIVATE_FOLDER)
        drive.bytes[queue]=byteArrayOf();drive.versions[queue]=1
    }
    @After fun cleanup(){ScheduledDotProofRegistry.plan=null;ScheduledDotProofRegistry.preparedInput=null;GoogleConnectionSession.resetForHost()}
    private fun coordinator(p:ScheduledDotProofPlan=plan(),prepared:PreparedWhisperScheduledTextInput?=input()):ScheduledDotProofCoordinator {
        val native=DotScheduledTextExchangePort(context,session,{DotTextExchangeConfiguration(p.enabled,p.queueFolder)},ScheduledProofVersionCheckedDrive(drive),preparedSyntheticInput=prepared?.let{v->{job:String->v.also{it.forJob(job)}}}){now}
        return ScheduledDotProofCoordinator(context,session,p,NativeScheduledDotProofPort(native),clock={now},preparedInput=prepared)
    }
    private fun count(table:String):Long=ProtectedDatabase.open(AccountStorageContext(context,session.ownerBinding()),GeneralLocalStore.DATABASE,null).use{store->store.database().rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}}
    private fun proof(id:String,value:JSONObject){
        val out=File("build/reports/local-stt-scheduled").apply{mkdirs()}
        File(out,"$id.json").writeText(value.put("hostSyntheticTest",true).put("newModelExecution","NOT_RUN").put("actualCloudUpload",false).put("actualSchedulerExecution","NOT_RUN").toString(2))
    }
    @Test fun C01ExactSevenSegmentsReachOriginalReadonlySlotAndNeverCompleteAudio()=runBlocking(Dispatchers.IO){
        val p=input();val c=coordinator();val first=c.cycle();assertEquals("DOT_TEXT_PENDING",first.getString("state"));assertEquals(3,drive.counter)
        val registration=first.getJSONObject("registration");val request=drive.bytes.getValue(registration.getString("input"))
        assertArrayEquals(bytes("scheduled-input.json"),request);val rows=JSONObject(String(request,Charsets.UTF_8)).getJSONArray("transcript");assertEquals(7,rows.length())
        val raw=rows.getJSONObject(0).getString("text");val quote=raw.substring(1,minOf(raw.length,18))
        val result=JSONObject().put("schema_version","scheduler-proof-v1").put("job_id",p.jobId).put("synthetic",true).put("source_file_id",registration.getString("input"))
            .put("produced_at_utc","2026-10-14T09:01:00Z").put("summary","HOST_TEST_RAW_REVIEW_REQUIRED")
            .put("tasks",JSONArray().put(JSONObject().put("title","HOST_TEST_UNVERIFIED_PROPOSAL").put("owner","미확인").put("status","needs_confirmation").put("due",JSONObject.NULL).put("timing_text",quote)
                .put("evidence",JSONObject().put("source_id","synthetic_call001").put("quote",quote))))
        val resultId=registration.getString("result");drive.bytes[resultId]=result.toString().toByteArray();drive.versions[resultId]=2;now=Instant.parse("2026-10-14T09:02:00Z")
        val received=coordinator().cycle();assertEquals("READ_ONLY_SCHEDULED_TEXT_PROPOSAL",received.getString("state"))
        assertEquals("IMPORTED_PREVIOUS_ACTUAL_EXECUTION_NOT_CURRENT",received.getString("upstreamSttExecutionReported"))
        for(flag in listOf("nativeAudioRequestCompleted","sttExecuted","publisherSttExecuted","canonicalWriteAllowed","kakaoSent","schedulerRegistered","upstreamSttIndependentlyVerified"))assertFalse(flag,received.getBoolean(flag))
        coordinator().cycle();assertEquals(3,drive.counter);assertEquals(1L,count("scheduled_dot_text_receipts"));assertEquals(0L,count("workflow_analysis_requests"));assertEquals(0L,count("workflow_items"))
        val d=JSONObject(String(drive.bytes.getValue(registration.getString("enrollment")),Charsets.UTF_8));assertEquals(p.evidenceSha,d.getString("local_stt_evidence_sha256"));assertFalse(d.getBoolean("stt_executed"))
        proof("C01",received.put("exactRawSegments",7).put("reservedIds",3).put("canonicalItems",0))
    }
    @Test fun C02MissingInputWaitsWithoutFixedTextFallbackAndRuntimeRejectsBadProducer()=runBlocking(Dispatchers.IO){
        val waiting=coordinator(prepared=null).cycle();assertEquals("WAITING_PREPARED_STT_INPUT",waiting.getString("state"));assertEquals(0,drive.counter);assertEquals(0,drive.downloads)
        ScheduledDotProofRegistry.plan={_,_->plan()}
        assertEquals("WAITING_PREPARED_STT_INPUT",ScheduledDotProofRuntime.reconcile(context).getString("state"))
        ScheduledDotProofRegistry.preparedInput={_,_,_->error("HOST_TEST_BAD_PRODUCER")}
        assertEquals("HELD",ScheduledDotProofRuntime.reconcile(context).getString("state"));assertEquals(0,drive.counter)
        proof("C02",JSONObject().put("missingPreparedInput",waiting).put("fallbackOrReservedIds",0).put("badProducerHeld",true))
    }
    @Test fun C03WrongPinsOrJobAreHeldBeforeAnyPublisherOperation()=runBlocking(Dispatchers.IO){
        val invalid=coordinator(plan().copy(requestSha="0".repeat(64))).cycle();assertEquals("HELD",invalid.getString("state"));assertEquals("LOCAL_STT_PRODUCER_PIN_MISMATCH",invalid.getString("reason"))
        assertEquals("HELD",coordinator(plan().copy(proofRequestId="00000000-0000-4000-8000-000000000043")).cycle().getString("state"))
        assertEquals(0,drive.counter);assertEquals(0,drive.downloads)
        proof("C03",JSONObject().put("pinsAndJobHeldBeforeTransport",true).put("reservedIds",0))
    }
    @Test fun C04ExistingFixedSlotCannotBeRelabeledOrResumedAsLocalStt()=runBlocking(Dispatchers.IO){
        val fixed=plan().copy(inputMode=ScheduledDotInputMode.FIXED_PUBLIC_TEXT,requestSha="",evidenceSha="")
        val original=coordinator(fixed,null).cycle();assertEquals("DOT_TEXT_PENDING",original.getString("state"));val reg=original.getJSONObject("registration");val originalBytes=drive.bytes.getValue(reg.getString("input")).clone()
        val held=coordinator().cycle();assertEquals("HELD",held.getString("state"));assertEquals("LOCAL_STT_EXISTING_SLOT_INPUT_MISMATCH",held.getString("reason"))
        assertEquals("HELD",coordinator().resumeExplicit().getString("state"));assertEquals(3,drive.counter);assertArrayEquals(originalBytes,drive.bytes[reg.getString("input")])
        assertEquals(1L,count("android_scheduled_dot_coordinator"));assertEquals(1L,count("android_local_stt_dot_coordinator"))
        proof("C04",JSONObject().put("fixedRecordPreserved",true).put("sttReuseHeld",true).put("successorIds",0))
    }
    @Test fun C05AppPrivateReaderRequiresExternalPinsAndCurrentNativeScope()=runBlocking(Dispatchers.IO){
        val p=plan();val scope=DriveALocalScope.current(session)!!
        val dir=File(AccountStorageContext(context,scope.owner).noBackupFilesDir,"local-stt-prepared-v1/${scope.key}/${p.proofRequestId}").apply{mkdirs()}
        val request=File(dir,"scheduled-input.json");val evidence=File(dir,"stt-request-evidence.json");request.writeBytes(bytes("scheduled-input.json"));evidence.writeBytes(bytes("stt-request-evidence.json"))
        assertArrayEquals(request.readBytes(),PreparedLocalSttFiles.load(context,scope,p)!!.forJob(p.proofRequestId));assertArrayEquals(bytes("stt-request-evidence.json"),evidence.readBytes())
        assertNull(PreparedLocalSttFiles.load(context,scope.copy(epoch=scope.epoch+1),p))
        request.appendText(" ");try{PreparedLocalSttFiles.load(context,scope,p);fail("changed bytes accepted")}catch(_:IllegalStateException){}
        request.writeBytes(ByteArray(65537));try{PreparedLocalSttFiles.load(context,scope,p);fail("oversized bytes accepted")}catch(_:IllegalStateException){}
        proof("C05",JSONObject().put("readOnlyPinnedLocalFiles",true).put("scopeChangeHeld",true).put("modifiedAndOversizedInputHeld",true))
    }
    @Test fun C06ChangedProducerEvidenceCannotReplacePinnedProofOrResult()=runBlocking(Dispatchers.IO){
        val first=coordinator().cycle();assertEquals("DOT_TEXT_PENDING",first.getString("state"));val evidence=JSONObject(String(bytes("stt-request-evidence.json"),Charsets.UTF_8))
        evidence.getJSONObject("execution").put("observed_run","PUBLIC_CHANGED_REPORTED_PROVENANCE")
        val changedBytes=evidence.toString().toByteArray();val changed=PreparedWhisperScheduledTextInput(bytes("scheduled-input.json"),changedBytes,DriveRelay.sha256(changedBytes))
        val held=coordinator(plan(changed),changed).cycle();assertEquals("HELD",held.getString("state"));assertEquals("LOCAL_STT_PIN_CHANGED_REVIEW_REQUIRED",held.getString("reason"));assertEquals(3,drive.counter)
        assertEquals("DOT_TEXT_PENDING",coordinator().cycle().getString("state"));assertEquals(0L,count("scheduled_dot_text_receipts"))
        proof("C06",JSONObject().put("changedEvidenceHeld",true).put("originalPinnedProofRetained",true).put("newIds",0))
    }
    @Test fun C07ApprovedProcessingBindingSurvivesRegistryResetWithoutNetworkOrNewGrant()=runBlocking(Dispatchers.IO){
        val scope=DriveALocalScope.current(session)!!;val account=AccountStorageContext(context,scope.owner)
        val cfg=AutomationApiConfiguration(true,"https://public-fixture.example.invalid",scope.owner,scope.account,scope.epoch,"Etc/UTC","PUBLIC_EXISTING_CONNECTION_CREDENTIAL_NO_SECRET")
        AndroidAutomationStore(account,scope.owner,scope.epoch,{true},{"PUBLIC_POLICY"},{true},{"Etc/UTC"}).use{store->
            assertSame(UnconfiguredAutomationProvider,AutomationProviderRegistry.open(account,session,store))
            AutomationConnectionBindings.bindApprovedProcessing(account,session,cfg)
            assertEquals(cfg,AutomationConnectionBindings.processing(context,session));AutomationProviderRegistry.create=null
            val opened=AutomationProviderRegistry.open(account,session,store);assertEquals("READY",opened.gate().state)
            assertFalse(opened.gate().toString().contains(cfg.bearerCredential))
            try{AutomationConnectionBindings.bindApprovedProcessing(context,session,cfg.copy(origin="https://changed.example.invalid"));fail("connection replaced")}catch(_:IllegalStateException){}
            AutomationConnectionBindings.withdraw(context,session);assertEquals("UNAPPROVED",opened.gate().state)
            assertSame(UnconfiguredAutomationProvider,AutomationProviderRegistry.open(account,session,store))
            try{AutomationConnectionBindings.bindApprovedProcessing(context,session,cfg);fail("revocation bypassed")}catch(_:IllegalStateException){}
        }
        assertEquals(0,drive.counter);proof("C07",JSONObject().put("approvedConfigurationBootstrap",true).put("credentialLogged",false).put("revocationDurable",true).put("networkCalls",0))
    }
    @Test fun C08ApprovedScheduledPinsSurviveFactoryResetAndNativeEpochChangeHolds()=runBlocking(Dispatchers.IO){
        val p=plan();AutomationConnectionBindings.bindApprovedScheduled(context,session,p);ScheduledDotProofRegistry.plan=null
        assertEquals(p,ScheduledDotProofRegistry.resolve(context,session))
        val e=session.begin();session.activate(e,"public.stt@example.invalid","public.stt@example.invalid","PUBLIC_STT_COORDINATOR_OWNER","PUBLIC_RENEWED_MEMORY_TOKEN")
        assertFalse(ScheduledDotProofRegistry.resolve(context,session).enabled)
        assertEquals(0,drive.counter);proof("C08",JSONObject().put("approvedPlanRestored",true).put("changedNativeEpochHeld",true).put("schedulerRegistrationInTest",false))
    }

}
