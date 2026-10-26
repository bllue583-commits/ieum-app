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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kr.callbrief.context.ContextRepositoryAdapter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Real sealed Android SQL/workflow/provider/consumer/accept. Fake GMS/Drive and test keys.
 * Only the explicitly named host-Whisper test executes a model; fixture cases cannot attest it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnclassifiedAudioIntegrationTest:ProtectedTextHostTestBase() {
    private val root=File("../..").canonicalFile
    private lateinit var base:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private lateinit var source:GeneralLocalStoreTest.Source;private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private lateinit var scope:DriveALocalScope
    private var clock=System.currentTimeMillis();private var active=true;private var policy="PUBLIC_POLICY_1"
    private class Memory:GoogleConnectionSession.Persistence {var x=JSONObject();override fun read()=JSONObject(x.toString());override fun write(v:JSONObject){x=JSONObject(v.toString())}}
    private fun prepare(count:Int=1,sameHash:Boolean=false):List<String> {
        base=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory());val epoch=session.begin()
        session.activate(epoch,"unclassified@example.invalid","unclassified@example.invalid","PUBLIC_UNCLASSIFIED_OWNER","PUBLIC_SYNTHETIC_NOT_A_GOOGLE_TOKEN")
        GoogleConnectionSession.installForHost(session);scope=checkNotNull(DriveALocalScope.current(session));scoped=AccountStorageContext(base,scope.owner)
        keys=SyntheticPilotIntegrationTest.HostKeys();source=GeneralLocalStoreTest.Source();drive=GoogleConnectionRegressionTest.FakeDrive()
        val key=SecretKeySpec(ByteArray(32){0x44},"AES")
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess{override fun existing()=key;override fun createForFirstEmptyStore()=key})
        settings.update{it.put("treeUri",source.rootUri)}
        repeat(count){i->val name=if(i==0 || sameHash)"public-ko.wav" else "public-en.wav";val data=File(root,"worker/tests/audio/$name").readBytes();val id="public-$i"
            source.bytes[id]=data;source.documents[id]=SourceDocument(id,"${source.rootUri}/document/$id","unclassified-$i.wav",data.size.toLong(),"audio/wav",GeneralLocalStoreTest.NOW-600000)}
        GeneralLocalStoreTest.host(scoped,keys).use{it.initializeEmpty();assertEquals(count,it.importSelected(source).newVersions)}
        source.inventory().documents.forEach{SyntheticApprovedSource.approve(settings,session,source,it,true)}
        return GeneralLocalStore.catalog(scoped).listFiles().let{a->(0 until a.length()).map{a.getJSONObject(it).getString("file_id")}}
    }
    @After fun cleanup(){GoogleConnectionSession.resetForHost()}
    private fun snapshot(id:String)=RecordingWorkflowStore(scoped).use{it.snapshot(id)}
    private fun sql()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun count(table:String)=sql().use{db->if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isEmpty())0 else (db.query("SELECT count(*) n FROM $table").single()["n"] as Number).toInt()}
    private open inner class FixtureTranscriber:UnclassifiedAudioTranscriber {
        override val runtimeSha="a".repeat(64);override val modelSha="b".repeat(64)
        override val kind=LocalExecutionKind.SYNTHETIC_FIXTURE
        override val publicAudio=source.bytes.values.map{DriveRelay.sha256(it)}.toSet()
        var calls=0;var after:()->Unit={};var failure=false
        override fun verify(){}
        override fun transcribe(audio:ByteArray,stopped:()->Boolean):AndroidWhisperOutput {
            calls++;if(failure)error("PUBLIC_TRANSCRIBER_FAILURE");after();check(!stopped()){"UNCLASSIFIED_CANCELLED"}
            return AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":0,"to":3000},"text":" 공개 합성 음성입니다. 자료를 확인해 주세요."}]}""".toByteArray())
        }
    }
    private fun stage(t:UnclassifiedAudioTranscriber)=UnclassifiedAudioStore(scoped,source,t,{active},{policy},scope.key,{clock})
    private fun bind(store:UnclassifiedAudioStore,request:String,id:String,selectedCase:String?=null):String {
        GeneralLocalStoreTest.host(scoped,keys).use{it.confirmTime(id,snapshot(id),GeneralLocalStoreTest.NOW-3600000)}
        val case=RecordingWorkflowStore(scoped).use{it.confirmWork(id,it.snapshot(id),"공개 합성 담당","공개 합성 거래처","공개 합성 업무","",selectedCase)}
        store.confirmBinding(request,snapshot(id),true);return case
    }
    private fun automation()=AndroidAutomationStore(scoped,scope.owner,scope.epoch,{active},{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.granted && s.getString("actual_document_id") !in source.excludedIds},{"Etc/UTC"},{clock})
    private inner class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun driveAExport(job:String)=p.driveAExport(job);override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job);override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun poll(job:String)=p.poll(job)
        override fun retry(job:String):RecordingAnalysisPort.Sent=error("NO_AUTOMATIC_NEW_JOB_RETRY")
        override fun cancel(){};override fun close(){}
    }
    private fun cycle(stage:UnclassifiedAudioStore,t:UnclassifiedAudioTranscriber,afterReceive:()->Unit={}):List<JSONObject> {
        GoogleNativePipeline(base,session,settings,{source},drive,postingCapability={true}){GeneralLocalStoreTest.host(it,keys)}.use{p->
            automation().use{store->NativeAutomationRequests(base,session){NativePort(p)}.use{requests->
                val delegate=stage.boundRunner()
                val runner=object:LocalRecordingRunner by delegate {
                    override fun receive(key:String,receipt:String,handoff:String,business:JSONObject)=delegate.receive(key,receipt,handoff,business).also{afterReceive()}
                }
                return AndroidAutomationCoordinator(store,requests,LocalRecordingAutomationProvider(store,session,runner,LocalRecordingTrust(t.runtimeSha,t.modelSha,t.publicAudio){active},sourceReady=stage::canRelease)).cycle()
            }}
        }
    }
    private fun reject(code:String,block:()->Unit){try{block();fail("Expected $code")}catch(e:Exception){assertTrue(e.toString(),e.message.orEmpty().contains(code))}}
    @Test fun intakeAndAnalysisHaveNoInventedPersonCaseTaskOrTime()=runBlocking(Dispatchers.IO){
        val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
            val before=snapshot(id);for(k in listOf("person_id","case_id","task_id","original_started_at_ms"))assertTrue(k,before.isNull(k))
            val request=s.prepare(id,before);assertEquals(request,s.prepare(id,before));assertEquals("RESULT_READY",s.analyse(request));assertEquals("RESULT_READY",s.analyse(request));assertEquals(1,t.calls)
            val receipt=s.view(request).getJSONObject("receipt");assertEquals(id,receipt.getString("sourceId"));assertEquals(before.getString("audio_sha256"),receipt.getString("audioSha"));assertTrue(receipt.getJSONObject("preparedBinding").has("boardRevision"))
            val evidence=s.view(request);assertEquals(DriveRelay.sha256(evidence.getString("transcript").toByteArray()),evidence.getString("transcriptSha"));assertEquals("SYNTHETIC_FIXTURE",evidence.getJSONObject("observation").getString("executionKind"))
            for(k in listOf("person_id","case_id","task_id","original_started_at_ms"))assertTrue(k,snapshot(id).isNull(k))
            assertEquals(0,count("automation_cumulative_history"));assertEquals(0,drive.counter)
            reject("EXPLICIT_CONTEXT"){s.confirmBinding(request,snapshot(id),true)}
        }
    }
    @Test fun boundFixtureUsesExistingAcceptAndRemainsReviewOnly()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));s.analyse(r);bind(s,r,id)
        assertEquals("REVIEW_REQUIRED",cycle(s,t).single().getString("state"));assertEquals(1,count("automation_results"));assertEquals(0,count("automation_cumulative_history"));assertEquals(1,t.calls)
    }}
    @Test fun sameHashDifferentSourcesKeepSeparateReceiptsAndBindings()=runBlocking(Dispatchers.IO){val ids=prepare(2,true);val t=FixtureTranscriber();stage(t).use{s->
        val requests=ids.map{s.prepare(it,snapshot(it))};assertEquals(2,requests.toSet().size)
        requests.forEach{assertEquals("RESULT_READY",s.analyse(it))};assertEquals(2,t.calls)
        var case:String?=null;ids.zip(requests).forEach{(id,r)->case=bind(s,r,id,case)}
        assertEquals(2,cycle(s,t).size);assertEquals(2,count("automation_results"));assertEquals(0,count("automation_cumulative_history"))
    }}
    @Test fun incompleteOrPartialSourceCannotStartTranscription()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        source.complete=false;reject("INCOMPLETE"){s.prepare(id,snapshot(id))};assertEquals(0,count("unclassified_audio_requests"))
        source.complete=true;val doc=source.documents.values.single();source.documents[doc.documentId]=doc.copy(partial=true)
        reject("DOCUMENT_HELD"){s.prepare(id,snapshot(id))};assertEquals(0,t.calls)
    }}
    @Test fun manualIntakeRejectsPartialAndVirtualBeforeAnyOpen()=runBlocking(Dispatchers.IO){prepare();GeneralLocalStoreTest.host(scoped,keys).use{store->
        val doc=source.documents.values.single();for(changed in listOf(doc.copy(partial=true),doc.copy(virtual=true))){source.documents[doc.documentId]=changed;val opens=source.opens;assertEquals(1,store.importSelected(source).held);assertEquals(opens,source.opens)}
    }}
    @Test fun truncatedOrHashChangedFileIsHeldWithoutModelCall()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));source.bytes["public-0"]=source.bytes.getValue("public-0").copyOf(44)
        assertEquals("HELD",s.analyse(r));assertEquals(0,t.calls);assertEquals(0,count("unclassified_audio_results"))
    }}
    @Test fun explicitRetryHasNewReceiptAndNeverRewritesFailedAttempt()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));t.failure=true;assertEquals("HELD",s.analyse(r));assertEquals("HELD",s.analyse(r));assertEquals(1,t.calls)
        val retry=s.prepare(id,snapshot(id),r);assertNotEquals(r,retry);t.failure=false;assertEquals("RESULT_READY",s.analyse(retry));assertEquals("HELD",s.status(r));assertEquals(2,t.calls)
    }}
    @Test fun cancelDuringExecutionCannotStoreOrReleaseResult()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));t.after={s.cancel(r)};assertEquals("CANCELLED",s.analyse(r));assertEquals("CANCELLED",s.analyse(r));assertEquals(0,count("unclassified_audio_results"))
    }}
    @Test fun scopePolicyLeaseAndSourceWithdrawalStopBeforeAudioRead()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));val opens=source.opens;policy="PUBLIC_POLICY_2";reject("AUTHORITY_OR_LEASE"){s.analyse(r)};assertEquals(opens,source.opens)
        policy="PUBLIC_POLICY_1";clock+=1800000;reject("AUTHORITY_OR_LEASE"){s.analyse(r)};clock-=1800000
        source.excludedIds.add("public-0");reject("EXCLUDED"){s.analyse(r)};source.excludedIds.clear();active=false;reject("AUTHORITY_OR_LEASE"){s.analyse(r)};assertEquals(0,t.calls)
    }}
    @Test fun concurrentManualRevisionPreservesOutputForReviewAndProtectsDone()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));t.after={GeneralLocalStoreTest.host(scoped,keys).use{it.edit(id,snapshot(id),"PUBLIC_MANUAL_NOTE","done")}}
        assertEquals("REVIEW_REQUIRED",s.analyse(r));assertEquals(1,count("unclassified_audio_results"));reject("NOT_READY"){s.confirmBinding(r,snapshot(id),true)}
        assertEquals("done",snapshot(id).getString("manual_status"));assertEquals("PUBLIC_MANUAL_NOTE",snapshot(id).getString("manual_text"))
    }}
    @Test fun newerBindingAndCancellationCannotReuseOldReceipt()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));s.analyse(r);val old=snapshot(id);bind(s,r,id)
        reject("REVISION_CONFLICT"){s.confirmBinding(r,old,true)};s.cancel(r);assertEquals("CANCELLED",s.status(r));assertEquals(1,count("unclassified_audio_results"))
        assertEquals("WAITING_APPROVAL",cycle(s,t).single().getString("state"));assertEquals(0,count("automation_results"));assertEquals(0,drive.counter)
    }}
    @Test fun doneDuringBoundReceiveUsesExistingReviewGateAndKeepsNote()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->
        val r=s.prepare(id,snapshot(id));s.analyse(r);bind(s,r,id)
        val job=cycle(s,t){GeneralLocalStoreTest.host(scoped,keys).use{it.edit(id,snapshot(id),"PUBLIC_NOTE_DURING_RECEIVE","done")}}.single()
        assertEquals("REVIEW_REQUIRED",job.getString("state"));assertTrue(job.getString("reason").contains("MANUAL"));assertEquals(0,count("automation_cumulative_history"))
        assertEquals("done",snapshot(id).getString("manual_status"));assertEquals("PUBLIC_NOTE_DURING_RECEIVE",snapshot(id).getString("manual_text"))
    }}
    @Test fun interruptedExecutionIsUnknownAndOnlyExplicitChildMayExecute()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();val r=stage(t).use{it.prepare(id,snapshot(id))}
        sql().use{it.execute("UPDATE unclassified_audio_requests SET state='RUNNING',process_token='PUBLIC_PRIOR_PROCESS' WHERE id=?",r)}
        stage(t).use{s->assertEquals("UNKNOWN",s.status(r));assertEquals("UNKNOWN",s.analyse(r));assertEquals(0,t.calls)
            val child=s.prepare(id,snapshot(id),r);assertNotEquals(r,child);assertEquals("RESULT_READY",s.analyse(child));assertEquals("UNKNOWN",s.status(r));assertEquals(1,t.calls)}
    }
    @Test fun reopenedNewAuthorityCannotAdoptPreviousExecutionReceipt()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();val r=stage(t).use{val r=it.prepare(id,snapshot(id));it.analyse(r);r}
        val epoch=session.begin();session.activate(epoch,"unclassified@example.invalid","unclassified@example.invalid","PUBLIC_UNCLASSIFIED_OWNER","PUBLIC_SYNTHETIC_NOT_A_GOOGLE_TOKEN");scope=checkNotNull(DriveALocalScope.current(session))
        stage(t).use{s->reject("AUTHORITY_OR_LEASE"){s.view(r)};reject("AUTHORITY_OR_LEASE"){bind(s,r,id)}}
        assertEquals(1,t.calls);assertEquals(0,count("automation_results"))
    }
    private fun contextStore(s:UnclassifiedAudioStore,fault:()->Unit={})=IntegratedContextStore(scoped,s,scope.owner,{active},settings::snapshot,{clock},fault)
    private fun bindContext(s:UnclassifiedAudioStore,c:IntegratedContextStore,r:String,id:String) {
        bind(s,r,id);val v=s.view(r);val text=v.getString("transcript");val quote=v.getJSONObject("analysis").getString("summary");val start=text.codePointCount(0,text.indexOf(quote))
        c.confirmRelation(r,snapshot(id),start,start+quote.codePointCount(0,quote.length),true);c.normalize(r)
    }
    private fun reviewedSource(id:String)=CanonicalReviewedDailySource(scoped,{recording,version,sha->recording==id && version==snapshot(id).getLong("source_version") && sha==snapshot(id).getString("audio_sha256")},settings::snapshot)
    private val reportWindow get()=MorningReportWindow(LocalDate.parse("2026-10-13"),MorningReportKind.DAILY)
    private fun approve(c:IntegratedContextStore,s:UnclassifiedAudioStore,r:String,id:String):String {
        val p=c.candidate();return c.approveSelection(mapOf(r to (snapshot(id) to s.view(r).getJSONObject("analysis").getString("summary"))),IntegratedContextStore.candidateDigest(p),true).single()
    }
    @Test fun integrationCandidateAndSafeResultDoNotAuthorizeReport()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->contextStore(s).use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);val before=c.normalize(r);assertFalse(before.getBoolean("canonicalWriteAllowed"));assertTrue(before.getJSONArray("holds").length()>0)
        bindContext(s,c,r,id);cycle(s,t);val candidate=c.candidate();assertEquals(1,candidate.getJSONArray("items").length());assertEquals(0,count("workflow_reviewed_summaries"))
        val input=CanonicalMorningSnapshotPort(reviewedSource(id)).snapshot(reportWindow);assertEquals(0,input.input.sources.size)
        reject("USER_CONTENT_REVIEW"){c.approveSelection(mapOf(r to (snapshot(id) to "PUBLIC_SUMMARY")),IntegratedContextStore.candidateDigest(candidate),false)};assertEquals(0,c.reviewCount())
    }}}
    @Test fun integrationExplicitReviewCommitsCanonicalSnapshotAndDryRunOutbox()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->contextStore(s).use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);c.normalize(r);bindContext(s,c,r,id);cycle(s,t);val receipt=approve(c,s,r,id)
        val source=reviewedSource(id);val input=CanonicalMorningSnapshotPort(source).snapshot(reportWindow);assertEquals(input.toString(),1,input.input.sources.size);assertEquals(receipt,input.input.sources.single().reviewReceipt);assertTrue(input.input.unresolved.toString(),input.input.fullyReviewed)
        val cipher=object:ReportPayloadCipher {override fun seal(text:String,aad:String,hasExistingPayload:Boolean)=text.toByteArray();override fun open(bytes:ByteArray,aad:String)=String(bytes)}
        val env=ReportEnvironment(Instant.parse("2026-10-12T21:00:00Z"),ZoneId.of("Asia/Seoul"),elapsed=1000,boot=1)
        DailyReportLedger(scoped,scope.owner,cipher).use{ledger->val generation=ledger.setEnabled(true,env.now,env.zone).generation
            MorningReportCoordinator(ledger.morning(),CanonicalMorningSnapshotPort(source),LocalReviewedMorningSync(scoped,scope.owner,{active},source),environment={env},current={active}).tick(generation)
            assertEquals("COMPLETE",ledger.morning().syncStatus());assertTrue(ledger.morning().ingestCursor().startsWith("local-reviewed-v1:"));assertEquals(2,ledger.morning().reports().size);assertTrue(ledger.morning().outbox().all{it.state==MorningDeliveryState.DRY_RUN_RECORDED})
        }
        assertEquals(1,count("workflow_items"));assertEquals(1,c.reviewCount())
        val state=c.candidate().toString();contextStore(s).use{reopened->assertEquals(state,reopened.candidate().toString())}
    }}}
    @Test fun integrationApprovalFailureRollsBackItemsSummaryReceiptAndCandidate()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();var fail=false;stage(t).use{s->contextStore(s){if(fail)error("PUBLIC_INTEGRATION_FAILURE")}.use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);c.normalize(r);bindContext(s,c,r,id);cycle(s,t);c.candidate();fail=true
        reject("PUBLIC_INTEGRATION_FAILURE"){approve(c,s,r,id)};fail=false
        assertEquals(0,c.reviewCount());assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(0,count("workflow_items"));assertEquals(0,count("integrated_applied_items"))
    }}}
    @Test fun integrationCandidateCASAndManualDoneRemainProtected()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->contextStore(s).use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);c.normalize(r);bindContext(s,c,r,id);cycle(s,t);val candidate=c.candidate();val adapter=ContextRepositoryAdapter(c.port);val prepared=adapter.prepareRefresh()
        GeneralLocalStoreTest.host(scoped,keys).use{it.edit(id,snapshot(id),"PUBLIC_MANUAL_DONE","done")}
        assertFalse(adapter.commit(prepared).committed);reject("MANUAL_STATE_PROTECTED"){c.approve(r,snapshot(id),IntegratedContextStore.candidateDigest(c.candidate()),"PUBLIC_SUMMARY",true)}
        assertEquals(0,c.reviewCount());assertEquals("done",snapshot(id).getString("manual_status"));assertEquals("PUBLIC_MANUAL_DONE",snapshot(id).getString("manual_text"));assertFalse(candidate.getBoolean("canonicalWriteAllowed"))
    }}}
    @Test fun integrationSourceRevocationBetweenPrepareAndCommitDoesNotWrite()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->contextStore(s).use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);c.normalize(r);val adapter=ContextRepositoryAdapter(c.port);val prepared=adapter.prepareRefresh();val opens=source.opens;source.excludedIds.add("public-0")
        assertFalse(adapter.commit(prepared).committed);val refreshed=c.candidate();assertEquals(0,refreshed.getJSONArray("items").length());assertEquals(0,refreshed.getJSONArray("activeSources").length());assertEquals(0,c.reviewCount());assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(opens,source.opens)
    }}}
    @Test fun integrationExpiredAnalysisInvalidatesCandidatesAndNewNormalizationKeepsIndependentVersion()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->contextStore(s).use{c->
        val first=s.prepare(id,snapshot(id));s.analyse(first);c.normalize(first);clock+=1800000;assertEquals(0,c.candidate().getJSONArray("activeSources").length())
        RecordingWorkflowStore(scoped).use{it.createItem(id,it.snapshot(id),"TODO","PUBLIC_DIRECT_ITEM","","")}
        val next=s.prepare(id,snapshot(id));assertNotEquals(first,next);s.analyse(next);val p=c.normalize(next);assertEquals(1,p.getJSONArray("activeSources").length());assertEquals(3L,p.getJSONArray("activeSources").getJSONObject(0).getLong("sourceVersion"));assertEquals(1L,s.view(next).getJSONObject("receipt").getJSONObject("source").getLong("source_version"))
    }}}
    @Test fun integrationOwnerMustMatchProtectedContextBeforeOpeningPort()=runBlocking(Dispatchers.IO){prepare();val t=FixtureTranscriber();stage(t).use{s->
        reject("OWNER_CONTEXT_REQUIRED"){IntegratedContextStore(scoped,s,"PUBLIC_OTHER_OWNER",{true},settings::snapshot).close()};assertEquals(0,count("integrated_context_state"))
    }}
    @Test fun integrationSourceRevokedAtFinalReviewCommitRollsBackEverything()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();var revoke=false;stage(t).use{s->contextStore(s){if(revoke)source.granted=false}.use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);c.normalize(r);bindContext(s,c,r,id);cycle(s,t);c.candidate();revoke=true
        reject("SCOPE_OR_SOURCE_CHANGED"){approve(c,s,r,id)};source.granted=true;assertEquals(0,c.reviewCount());assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(0,count("workflow_items"))
    }}}
    @Test fun integrationLocalSyncReturnsDurabilityOnlyAfterSourceCheckpointCommit()=runBlocking(Dispatchers.IO){val id=prepare().single();val t=FixtureTranscriber();stage(t).use{s->contextStore(s).use{c->
        val r=s.prepare(id,snapshot(id));s.analyse(r);c.normalize(r);bindContext(s,c,r,id);cycle(s,t);approve(c,s,r,id)
        val source=reviewedSource(id);val req=MorningSyncRequest("PUBLIC_LOCAL_CHECKPOINT",reportWindow.from,reportWindow.until,"","PUBLIC_LOCAL_REVIEW",1)
        var failed=false;try{LocalReviewedMorningSync(scoped,scope.owner,{active},source){error("PUBLIC_CHECKPOINT_FAILURE")}.sync(req)}catch(_:IllegalStateException){failed=true};assertTrue(failed);assertEquals(0,count("integrated_sync_checkpoints"))
        val port=LocalReviewedMorningSync(scoped,scope.owner,{active},source);val first=port.sync(req);assertEquals(MorningSyncState.COMPLETE,first.state);assertTrue(first.durable);assertEquals(first,port.sync(req));assertEquals(1,count("integrated_sync_checkpoints"))
    }}}
    @Test fun realPublicHostWhisperBeforeBindingThenExistingStorageAndRestart()=runBlocking(Dispatchers.IO){
        val pinFile=System.getenv("IEUM_LOCAL_STT_PIN_FILE")?.let(::File);Assume.assumeTrue(pinFile?.isFile==true)
        val ids=prepare(2);val pin=JSONObject(pinFile!!.readText());val dir=File(root,"android/app/build/reports/unclassified-audio/${UUID.randomUUID()}").apply{mkdirs()}
        val transcriber=object:FixtureTranscriber(){
            override val runtimeSha=pin.getString("binarySha");override val modelSha=pin.getString("modelSha");override val kind=LocalExecutionKind.CURRENT_LOCAL_PROCESS
            override fun verify(){for((path,hash) in listOf("binary" to "binarySha","model" to "modelSha"))assertEquals(pin.getString(hash),File(pin.getString(path)).inputStream().use{input->val d=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;d.update(buffer,0,n)};d.digest().joinToString(""){"%02x".format(it)}})}
            override fun transcribe(audio:ByteArray,stopped:()->Boolean):AndroidWhisperOutput {
                check(!stopped());verify();val input=File(dir,"public-${calls}.wav");input.writeBytes(audio);val output=File(dir,"public-${calls}");calls++
                val p=ProcessBuilder(pin.getString("binary"),"-m",pin.getString("model"),"-f",input.path,"-l","auto","-ng","-np","-oj","-of",output.path,"-t","4").redirectErrorStream(true).redirectOutput(File(dir,"stt-${calls}.log")).start()
                check(p.waitFor(90,TimeUnit.SECONDS)){p.destroyForcibly();"PUBLIC_STT_TIMEOUT"};assertEquals(0,p.exitValue());check(!stopped());return AndroidWhisperEngine.decodeOutput(File(output.path+".json").readBytes())
            }
        }
        var receipts:List<String>;var firstJobs:List<String>
        stage(transcriber).use{s->
            receipts=ids.map{s.prepare(it,snapshot(it))};receipts.forEach{assertEquals("RESULT_READY",s.analyse(it))}
            ids.forEach{id->for(k in listOf("person_id","case_id","task_id","original_started_at_ms"))assertTrue(snapshot(id).isNull(k))};assertEquals(0,count("automation_cumulative_history"));assertEquals(2,transcriber.calls)
            var case:String?=null;ids.zip(receipts).forEach{(id,r)->case=bind(s,r,id,case)}
            val jobs=cycle(s,transcriber);assertTrue(jobs.toString(),jobs.all{it.getString("state")=="APPLIED_SAFE_DERIVED"});firstJobs=jobs.map{it.getString("handoff")}
            assertEquals(2,count("automation_cumulative_history"));assertEquals(2,count("automation_results"));assertEquals(8,drive.counter)
        }
        clock+=900001
        stage(transcriber).use{s->assertEquals(firstJobs,cycle(s,transcriber).map{it.getString("handoff")});assertEquals(2,transcriber.calls);assertEquals(8,drive.counter);assertEquals(2,count("automation_cumulative_history"))}
        File(dir,"OBSERVATION.json").writeText(JSONObject().put("scope","PUBLIC_HOST_WHISPER_ROBOLECTRIC_FAKE_NATIVE_TRANSPORT").put("actualHostSTTCalls",transcriber.calls).put("analysisBeforeBinding",true).put("history",count("automation_cumulative_history")).put("tasks",count("workflow_items")).put("realAndroidJNI",false).put("realDrive",false).put("highDotReview",false).toString(2))
    }
}
