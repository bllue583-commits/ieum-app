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
class SiwcNativeStoreIntegrationTest:ProtectedTextHostTestBase() {
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
        // Injectable local execution observation is simulated in this host fixture.
        override val kind=LocalExecutionKind.CURRENT_LOCAL_PROCESS
        override val publicAudio=source.bytes.values.map{DriveRelay.sha256(it)}.toSet()
        var calls=0;var after:()->Unit={};var failure=false
        override fun verify(){}
        override fun transcribe(audio:ByteArray,stopped:()->Boolean):AndroidWhisperOutput {
            calls++;if(failure)error("PUBLIC_TRANSCRIBER_FAILURE");after();check(!stopped()){"UNCLASSIFIED_CANCELLED"}
            return AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":0,"to":3000},"text":" 공개 합성 음성입니다."}]}""".toByteArray())
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
    private fun withGate(fault:()->Unit={},body:(UnclassifiedAudioStore,SiwcNativeReviewStore,SiwcNativeReviewStore.Ticket,String)->Unit) {
        val id=prepare().single();val t=FixtureTranscriber()
        stage(t).use{intake->val request=intake.prepare(id,snapshot(id));intake.analyse(request);bind(intake,request,id);cycle(intake,t)
            automation().use{store->SiwcNativeReviewStore(scoped,scope.owner,intake,store,{active},settings::snapshot,fault).use{native->body(intake,native,native.prepare(request),id)}}
        }
    }
    private fun bundle(native:SiwcNativeReviewStore,t:SiwcNativeReviewStore.Ticket,body:(SiwcResultFiles,SiwcAstraProtocol.Invocation,SiwcAstraProtocol.Invocation,SiwcPublicFixturePort)->Unit) {
        val port=SiwcPublicFixturePort(t.contract);val account="SIMULATED_SIWC_FIXTURE:"+scope.key
        val current={active && native.isCurrent(t)}
        SiwcInferenceJobs(scoped,account,port,current).use{jobs->
            val a=SiwcNativeRoundTrip.analysis(t,account);val va=SiwcNativeRoundTrip.validateAnalysis(t)
            assertEquals("COMPLETE",jobs.run(a,va).state)
            val c=SiwcNativeRoundTrip.context(t,jobs,a);val vc=SiwcNativeRoundTrip.validateContext(t,jobs,a)
            assertEquals("COMPLETE",jobs.run(c,vc).state)
            SiwcResultFiles(scoped,account,jobs,current).use{files->files.publish(StrictJson.bytes(t.contract.pack),a,c,va,vc);body(files,a,c,port)}
        }
    }
    @Test fun actualEncryptedFileEntersExistingAcceptButOnlyExplicitReviewCreatesUserCommands()=runBlocking(Dispatchers.IO){withGate{_,native,t,id->bundle(native,t){files,a,c,port->
        val received=native.receive(t,files,a,c,true);val key=received.getString("id");val digest=received.getString("digest")
        assertEquals(2,port.calls);assertEquals(2,count("automation_results"));assertEquals(0,count("workflow_items"));assertEquals(0,count("workflow_reviewed_summaries"))
        assertFalse(received.getBoolean("canonicalWriteAllowed"));assertFalse(received.getBoolean("userApproved"));assertEquals("SIWC_FIXTURE_FILE_REVIEW_PROPOSAL",received.getString("origin"))
        assertEquals(key,native.receive(t,files,a,c,true).getString("id"));assertEquals(2,count("automation_results"))
        reject("EXPLICIT_USER_REVIEW"){native.approve(t,key,digest,false)}
        reject("REVIEW_CHANGED"){native.approve(t,key,"a".repeat(64),true)}
        val receipt=native.approve(t,key,digest,true);assertEquals(1,count("workflow_reviewed_summaries"));assertEquals(1,count("workflow_items"));assertEquals(1,count("siwc_native_user_reviews"))
        assertEquals(receipt,native.approve(t,key,digest,true));assertEquals(1,count("workflow_items"));assertTrue(snapshot(id).getString("reviewed_summary").startsWith("합성 결과파일 후보"))
        assertEquals(receipt,native.approve(native.prepare(t.request),key,digest,true));assertEquals(receipt,native.reviewedReceipt(key));assertEquals(1,count("workflow_items"))
        assertEquals(listOf(receipt),native.approveSelection(mapOf(t to (key to digest)),true));assertEquals(1,count("workflow_items"))
    }}}
    @Test fun nativeTransactionFailureRollsBackResultAndCandidateAfterFileCommit()=runBlocking(Dispatchers.IO){withGate({error("FIXTURE_TRANSACTION_ABORT")}){_,native,t,_->bundle(native,t){files,a,c,_->
        reject("FIXTURE_TRANSACTION_ABORT"){native.receive(t,files,a,c,true)}
        assertEquals(1,count("automation_results"));assertEquals(0,count("siwc_native_candidates"));assertEquals(0,count("siwc_native_user_reviews"))
    }}}
    @Test fun sourceWithdrawalCancellationAndPolicyChangesRejectFileAdmission()=runBlocking(Dispatchers.IO){withGate{_,native,t,_->bundle(native,t){files,a,c,_->
        active=false;reject("CURRENT"){native.receive(t,files,a,c,true)};active=true
        source.excludedIds.add("public-0");reject("CURRENT"){native.receive(t,files,a,c,true)};source.excludedIds.clear()
        settings.update{it.put("pendingRevocation",true)};assertFalse(native.isCurrent(t))
        assertEquals(1,count("automation_results"));assertEquals(0,count("siwc_native_candidates"))
    }}}
    @Test fun manualDoneAfterFilePreventsAdmissionAndKeepsUserState()=runBlocking(Dispatchers.IO){withGate{_,native,t,id->bundle(native,t){files,a,c,_->
        GeneralLocalStoreTest.host(scoped,keys).use{it.edit(id,snapshot(id),"사용자 보존 메모","done")}
        reject("CURRENT"){native.receive(t,files,a,c,true)};assertEquals("done",snapshot(id).getString("manual_status"));assertEquals("사용자 보존 메모",snapshot(id).getString("manual_text"))
        assertEquals(0,count("siwc_native_user_reviews"))
    }}}
    @Test fun manualDoneAfterCandidatePreventsReviewAndDoesNotEraseCandidate()=runBlocking(Dispatchers.IO){withGate{_,native,t,id->bundle(native,t){files,a,c,_->
        val got=native.receive(t,files,a,c,true)
        GeneralLocalStoreTest.host(scoped,keys).use{it.edit(id,snapshot(id),"직접 수정","done")}
        reject("CURRENT"){native.approve(t,got.getString("id"),got.getString("digest"),true)}
        assertEquals(1,count("siwc_native_candidates"));assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(0,count("siwc_native_user_reviews"))
    }}}
    @Test fun corruptFinalFileCannotProduceCandidateOrOverwriteHistory()=runBlocking(Dispatchers.IO){withGate{_,native,t,_->bundle(native,t){files,a,c,_->
        val key=files.key(a,c);val directory=File(scoped.noBackupFilesDir,"siwc-results-v1/"+AstraAudioContract.sha(a.scope.toByteArray()))
        val file=File(directory,key+".json.enc");val damaged=file.readBytes().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()};file.writeBytes(damaged)
        assertNotNull(runCatching{native.receive(t,files,a,c,true)}.exceptionOrNull());assertArrayEquals(damaged,file.readBytes())
        assertEquals(1,count("automation_results"));assertEquals(0,count("siwc_native_candidates"))
    }}}
    @Test fun userReviewTransactionFaultRollsBackAllCanonicalCommands()=runBlocking(Dispatchers.IO){
        var fail=false
        withGate({if(fail)error("REVIEW_TRANSACTION_ABORT")}){_,native,t,_->bundle(native,t){files,a,c,_->
            val got=native.receive(t,files,a,c,true);fail=true
            reject("REVIEW_TRANSACTION_ABORT"){native.approve(t,got.getString("id"),got.getString("digest"),true)}
            assertEquals(0,count("workflow_items"));assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(0,count("siwc_native_user_reviews"));assertEquals(1,count("siwc_native_candidates"))
        }}
    }
    @Test fun fixtureTranscriptCannotMasqueradeAsActualLocalStt()=runBlocking(Dispatchers.IO){
        val id=prepare().single();val fake=object:FixtureTranscriber(){override val kind=LocalExecutionKind.SYNTHETIC_FIXTURE}
        stage(fake).use{s->val r=s.prepare(id,snapshot(id));s.analyse(r);reject("REAL_LOCAL_TRANSCRIPT_REQUIRED"){SiwcLocalTranscript.sourcePack(s,r)}}
    }
    @Test fun aNewNativeResultRevisionSupersedesTheDisplayedCandidate()=runBlocking(Dispatchers.IO){withGate{_,native,t,_->bundle(native,t){files,a,c,_->
        val got=native.receive(t,files,a,c,true)
        automation().use{store->assertTrue(store.claim(t.job));try{
            store.accept(t.job,AutomationAnalysis(3,"c".repeat(64),"{\"fixture\":\"later result\"}",t.contract.text,"별도 후보","별도 후보",org.json.JSONArray(),JSONObject(t.frozenHandoff).getString("frozenRequestJson")),AutomationAssurance("PUBLIC_TEST_LATER_RESULT"))
        }finally{store.release(t.job)}}
        reject("SUPERSEDED"){native.approve(t,got.getString("id"),got.getString("digest"),true)}
        assertEquals(0,count("siwc_native_user_reviews"));assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(1,count("siwc_native_candidates"))
    }}}
}
