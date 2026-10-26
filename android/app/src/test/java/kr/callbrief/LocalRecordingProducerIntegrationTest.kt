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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Real protected Android store/coordinator. GMS/Drive are fake. Actual local-model cases
 * launch the pinned Mac CPU engine on public TTS WAVs; other cases explicitly use fixture STT. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalRecordingProducerIntegrationTest:ProtectedTextHostTestBase() {
    private val root=File("../..").canonicalFile
    private val audioDir=File(root,"worker/tests/audio").canonicalFile
    private val pinFile=System.getenv("IEUM_LOCAL_STT_PIN_FILE")?.let(::File)
    private val engine get()=pinFile?.takeIf{it.isFile}?.readText()?.let(::JSONObject) ?: JSONObject().put("binarySha","f".repeat(64)).put("modelSha","e".repeat(64))
    private lateinit var context:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private lateinit var source:GeneralLocalStoreTest.Source;private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private lateinit var scope:DriveALocalScope;private var clock=System.currentTimeMillis()
    private val ids=mutableListOf<String>();private val names=linkedMapOf<String,String>()
    private class Memory:GoogleConnectionSession.Persistence {var x=JSONObject();override fun read()=JSONObject(x.toString());override fun write(v:JSONObject){x=JSONObject(v.toString())}}
    private fun prepare(count:Int=2){
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(Memory());val e=session.begin()
        session.activate(e,"local.producer@example.invalid","local.producer@example.invalid","PUBLIC_LOCAL_PRODUCER_OWNER","PUBLIC_SYNTHETIC_MEMORY_TOKEN");GoogleConnectionSession.installForHost(session)
        scope=checkNotNull(DriveALocalScope.current(session));scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys();source=GeneralLocalStoreTest.Source();drive=GoogleConnectionRegressionTest.FakeDrive()
        val key=SecretKeySpec(ByteArray(32){0x33},"AES");settings=SecureSettings(scoped,object:SecureSettings.KeyAccess{override fun existing()=key;override fun createForFirstEmptyStore()=key});settings.update{it.put("treeUri",source.rootUri)}
        for((index,name) in listOf("public-ko.wav","public-en.wav").take(count).withIndex()){
            val bytes=File(audioDir,name).readBytes();val id="public_$index";source.bytes[id]=bytes;source.documents[id]=SourceDocument(id,"${source.rootUri}/document/$id",name,bytes.size.toLong(),"audio/wav",GeneralLocalStoreTest.NOW-600000)
            names[DriveRelay.sha256(bytes)]=name
        }
        GeneralLocalStoreTest.host(scoped,keys).use{s->s.initializeEmpty();var elapsed=0L
            NativeDiscoverySession(s,source,Selection(GeneralLocalStore.OWNER+"_code33",source.rootUri,1),settings::snapshot,Clock{Moment("PUBLIC_CODE33_BOOT",elapsed)},{GeneralLocalStoreTest.NOW},true,kr.co.ieum.discovery.DiscoveryTypes.Config(10,10000,1000,25000000,2)).use{d->d.cycle(1,1);elapsed=20;d.cycle(1,1);elapsed=40;d.cycle(1,1)
            val rows=s.snapshot();assertEquals(count,rows.length());for(i in 0 until rows.length()){val id=rows.getJSONObject(i).getString("file_id");ids+=id;s.confirmTime(id,s.snapshot().let{a->(0 until a.length()).map{a.getJSONObject(it)}.single{it.getString("file_id")==id}},GeneralLocalStoreTest.NOW-3600000+i*60000)}
            }
        }
        RecordingWorkflowStore(scoped).use{s->var case:String?=null;for(id in ids)case=s.confirmWork(id,s.snapshot(id),"공개 합성 담당","공개 합성 회사","공개 합성 업무","",case)}
        for(doc in source.documents.values)SyntheticApprovedSource.approve(settings,session,source,doc,true)
    }
    @After fun cleanup(){GoogleConnectionSession.resetForHost();AutomationProviderRegistry.create=null}
    private fun sql()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun count(table:String)=sql().use{db->if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isEmpty())0L else (db.query("SELECT count(*) n FROM $table").single()["n"] as Number).toLong()}
    private fun store()=AndroidAutomationStore(scoped,scope.owner,scope.epoch,{DriveALocalScope.current(session)==scope},{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.granted&&s.getString("actual_document_id") !in source.excludedIds&&settings.snapshot().optJSONObject("nativeSyntheticApprovals")?.length()?.let{it>0}==true},{"Etc/UTC"},{clock})
    private fun pipeline()=GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}){GeneralLocalStoreTest.host(it,keys)}
    private inner class NativePort(val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun driveAExport(job:String)=p.driveAExport(job);override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job);override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun poll(job:String)=p.poll(job)
        override fun retry(job:String):RecordingAnalysisPort.Sent=error("NO_AUTOMATIC_RETRY");override fun cancel(){};override fun close(){}
    }
    private fun requests(p:GoogleNativePipeline)=NativeAutomationRequests(context,session){NativePort(p)}
    private inner class Runner(val real:Boolean,val directory:File):LocalRecordingRunner {
        var actualCalls=0;var fixtureCalls=0;var afterRun:()->Unit={};var transform:(JSONObject)->Unit={};var serial=0;var kindOverride:LocalExecutionKind?=null
        private val database=File(directory,"producer.sqlite")
        init{directory.mkdirs()}
        private fun ticket(handoff:String,business:JSONObject)=JSONObject().put("frozenHandoff",handoff).put("businessContext",JSONObject(business.toString())).put("audioFileName",names.getValue(JSONObject(JSONObject(handoff).getString("frozenRequestJson")).getString("audioSha")))
        private fun call(operation:String,handoff:String,business:JSONObject):JSONObject {
            val cfg=JSONObject().put("operation",operation).put("directory",audioDir.path).put("database",database.canonicalPath).put("existingStoreKeyBase64",java.util.Base64.getEncoder().encodeToString(ByteArray(32){0x33})).put("engine",JSONObject(engine.toString()).put("language","auto"))
                .put("scope",JSONObject(handoff).getJSONObject("scope")).put("reportZone","Etc/UTC").put("approvedAudioSha256",JSONArray(names.keys.toList())).put("tickets",JSONArray().put(ticket(handoff,business))).put("id",DriveRelay.sha256(handoff.toByteArray()))
            val node=checkNotNull(System.getenv("IEUM_NODE_BINARY"));val script=File(root,if(real)"worker/src/local-recording-cli.mjs" else "worker/tests/local-recording-fixture-bridge.mjs")
            val out=File(directory,"${serial++}_$operation.json");val err=File(directory,"${serial}_stderr.txt");val b=ProcessBuilder(node,script.path).directory(root).redirectOutput(out).redirectError(err);b.environment().clear();b.environment()["LANG"]="en_US.UTF-8"
            val process=b.start();process.outputStream.use{it.write(cfg.toString().toByteArray())};if(!process.waitFor(100,TimeUnit.SECONDS)){process.destroyForcibly();error("LOCAL_PRODUCER_PROCESS_TIMEOUT")};check(process.exitValue()==0){"LOCAL_PRODUCER_PROCESS_FAILED"}
            return JSONObject(out.readText()).also { if(operation=="run"){val calls=it.getInt("providerCalls");if(real)actualCalls+=calls else fixtureCalls+=calls;assertEquals(2,it.getJSONArray("files").length());afterRun()} }
        }
        private fun dispatch(operation:String,handoff:String,business:JSONObject):AutomationDispatch {
            val r=call(operation,handoff,business).getJSONArray("runs").getJSONObject(0)
            return if(r.getString("state")=="RESULT_READY")AutomationDispatch("ACCEPTED",r.getString("receipt"),"LOCAL_PRODUCER_DURABLE_RESULT_READY") else AutomationDispatch("UNKNOWN",reason="LOCAL_PRODUCER_RECONCILIATION_REQUIRED")
        }
        override fun submit(key:String,handoff:String,business:JSONObject)=dispatch("run",handoff,business)
        override fun reconcile(key:String,handoff:String,business:JSONObject)=dispatch("reconcile",handoff,business)
        override fun receive(key:String,receipt:String,handoff:String,business:JSONObject):LocalRecordingRun {
            val packet=call("read",handoff,business);val result=packet.getJSONObject("result");val o=packet.getJSONObject("observation");transform(result)
            val unsigned=StrictJson.`object`(StrictJson.parse(result.toString().toByteArray())).filterKeys{it!="resultDigest"};result.put("resultDigest",DriveRelay.sha256(("ieum-local-recording-producer-v1\n"+StrictJson.stringify(unsigned)).toByteArray()))
            val kind=kindOverride ?: if(real)if(actualCalls>0)LocalExecutionKind.CURRENT_LOCAL_PROCESS else LocalExecutionKind.TRUSTED_LOCAL_REPLAY else LocalExecutionKind.SYNTHETIC_FIXTURE
            return LocalRecordingRun(StrictJson.bytes(StrictJson.parse(result.toString().toByteArray())),LocalExecutionObservation(kind,o.getString("audioSha"),o.getString("requestSha"),o.getString("businessContextSha"),o.getString("runtimeSha"),o.getString("modelSha"),java.util.Base64.getDecoder().decode(o.getString("rawOutputBase64")),o.getInt("exitCode")))
        }
    }
    private fun folder(label:String)=File(root,"android/app/build/reports/local-recording-producer/$label-${UUID.randomUUID()}")
    private fun provider(s:AndroidAutomationStore,runner:Runner)=LocalRecordingAutomationProvider(s,session,runner,LocalRecordingTrust(engine.getString("binarySha"),engine.getString("modelSha"),names.keys){DriveALocalScope.current(session)==scope})
    private fun proof(dir:File,result:JSONObject){File(dir,"CASE_OBSERVATION.json").writeText(result.put("realDrive",false).put("realGMS",false).put("paidApiCalls",0).put("realPrivateAudioTransfers",0).put("AndroidOnDeviceRunnerVerified",false).put("localHostBridgeOnly",true).toString(2))}
    @Test fun P3301FixtureProducerTraversesRealReceiverButCannotPromoteExecution()=runBlocking(Dispatchers.IO){prepare();val dir=folder("P3301");pipeline().use{p->store().use{s->val r=Runner(false,dir);requests(p).use{val rows=AndroidAutomationCoordinator(s,it,provider(s,r)).cycle();assertEquals(2,rows.size);assertTrue(rows.all{x->x.getString("state")=="REVIEW_REQUIRED"})};assertEquals(2,r.fixtureCalls);assertEquals(0L,count("automation_cumulative_history"));assertEquals(2L,count("automation_results"));proof(dir,JSONObject().put("fixtureProviderCalls",r.fixtureCalls).put("actualLocalSTTCalls",0).put("history",0))}}}
    @Test fun P3302ActualLocalAudioThroughProviderReceiverHistoryAndRestartDuplicate()=runBlocking(Dispatchers.IO){Assume.assumeTrue(pinFile?.isFile==true);prepare();val dir=folder("P3302");pipeline().use{p->store().use{s->val r=Runner(true,dir);AutomationProviderRegistry.create={_,_,st->provider(st,r)}
        requests(p).use{val rows=AndroidAutomationCoordinator(s,it,AutomationProviderRegistry.open(context,session,s)).cycle();assertEquals(2,rows.size);assertTrue(rows.toString(),rows.all{x->x.getString("state")=="APPLIED_SAFE_DERIVED"})}
        assertEquals(2,r.actualCalls);assertEquals(2L,count("automation_cumulative_history"));assertEquals(2L,count("automation_results"));assertEquals(8,drive.counter)
        val before=s.jobs().map{it.getString("id") to it.getString("handoff")};clock+=900001;val reopened=Runner(true,dir)
        requests(p).use{AndroidAutomationCoordinator(s,it,provider(s,reopened)).cycle()};assertEquals(0,reopened.actualCalls);assertEquals(2L,count("automation_cumulative_history"));assertEquals(2L,count("automation_results"));assertEquals(8,drive.counter);assertEquals(before,s.jobs().map{it.getString("id") to it.getString("handoff")})
        proof(dir,JSONObject().put("actualLocalSTTCalls",2).put("reopenedProviderCalls",0).put("history",2).put("nativeFileIDs",8).put("tasks",count("workflow_items")).put("runtimeSha",engine.getString("binarySha")).put("modelSha",engine.getString("modelSha")))
    }}}
    @Test fun P3303ActualLocalSTTCannotOverwriteManualDoneOrNote()=runBlocking(Dispatchers.IO){Assume.assumeTrue(pinFile?.isFile==true);prepare(1);val dir=folder("P3303");pipeline().use{p->store().use{s->val r=Runner(true,dir);r.afterRun={GeneralLocalStoreTest.host(scoped,keys).use{it.edit(ids.single(),it.snapshot().getJSONObject(0),"PUBLIC_MANUAL_NOTE","done")}}
        requests(p).use{val row=AndroidAutomationCoordinator(s,it,provider(s,r)).cycle().single();assertEquals("REVIEW_REQUIRED",row.getString("state"));assertTrue(row.getString("reason").contains("MANUAL"))};assertEquals(1,r.actualCalls);assertEquals(0L,count("automation_cumulative_history"));val snapshot=RecordingWorkflowStore(scoped).use{it.snapshot(ids.single())};assertEquals("done",snapshot.getString("manual_status"));assertEquals("PUBLIC_MANUAL_NOTE",snapshot.getString("manual_text"));proof(dir,JSONObject().put("actualLocalSTTCalls",1).put("manualDonePreserved",true).put("manualNotePreserved",true).put("history",0))}}}
    @Test fun P3304PayloadCannotClaimCurrentExecutionOverFixtureObservation()=runBlocking(Dispatchers.IO){prepare(1);val dir=folder("P3304");pipeline().use{p->store().use{s->val r=Runner(false,dir);r.transform={it.getJSONObject("execution").put("kind","CURRENT_LOCAL_PROCESS");it.getJSONObject("stt").put("provider","WHISPER_CPP_LOCAL")};requests(p).use{assertEquals("FAILED",AndroidAutomationCoordinator(s,it,provider(s,r)).cycle().single().getString("state"))};assertEquals(0L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));proof(dir,JSONObject().put("forgedPayloadRejected",true).put("actualLocalSTTCalls",0))}}}
    @Test fun P3305ChangedRawTranscriptCannotReachHistoryEvenWithNewDigest()=runBlocking(Dispatchers.IO){prepare(1);val dir=folder("P3305");pipeline().use{p->store().use{s->val r=Runner(false,dir);r.transform={val t=it.getJSONObject("stt");t.put("text",t.getString("text")+" 위조");t.put("textSha",DriveRelay.sha256(t.getString("text").toByteArray()))};requests(p).use{assertEquals("FAILED",AndroidAutomationCoordinator(s,it,provider(s,r)).cycle().single().getString("state"))};assertEquals(0L,count("automation_cumulative_history"));proof(dir,JSONObject().put("rawTranscriptChangedRejected",true).put("history",0))}}}
    @Test fun P3306PolicyABAAfterProducerPreventsReceiveAdmission()=runBlocking(Dispatchers.IO){prepare(1);val dir=folder("P3306");pipeline().use{p->store().use{s->val r=Runner(false,dir);r.afterRun={settings.update{it.put("pendingRevocation",true)};settings.update{it.put("pendingRevocation",false)}};requests(p).use{AndroidAutomationCoordinator(s,it,provider(s,r)).cycle()};assertEquals(0L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));proof(dir,JSONObject().put("policyABARejected",true).put("history",0))}}}
    @Test fun P3307SourceWithdrawalAfterProducerCannotReceiveOrRetryNewJob()=runBlocking(Dispatchers.IO){prepare(1);val dir=folder("P3307");pipeline().use{p->store().use{s->val r=Runner(false,dir);r.afterRun={source.granted=false};requests(p).use{AndroidAutomationCoordinator(s,it,provider(s,r)).cycle()};assertEquals(0L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));val calls=r.fixtureCalls;requests(p).use{AndroidAutomationCoordinator(s,it,provider(s,r)).cycle()};assertEquals(calls,r.fixtureCalls);assertEquals(4,drive.counter);proof(dir,JSONObject().put("withdrawalRejected",true).put("producerCallsBeforeWithdrawal",calls).put("additionalCalls",0))}}}
    @Test fun P3308DisconnectedRunnerStopsBeforeNativePublication()=runBlocking(Dispatchers.IO){prepare(1);pipeline().use{p->store().use{s->requests(p).use{val row=AndroidAutomationCoordinator(s,it,LocalRecordingAutomationProvider(s,session,null,null)).cycle().single();assertEquals("WAITING_PROVIDER",row.getString("state"))};assertEquals(0,drive.counter);assertEquals(0L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"))}}}
    @Test fun P3309ImportedPriorOutputIsReadableProposalAndNeverCurrentExecution()=runBlocking(Dispatchers.IO){prepare(1);val dir=folder("P3309");pipeline().use{p->store().use{s->val r=Runner(false,dir);r.kindOverride=LocalExecutionKind.IMPORTED_PRIOR_RUN;r.transform={it.getJSONObject("execution").put("kind","IMPORTED_PRIOR_RUN")};requests(p).use{assertEquals("REVIEW_REQUIRED",AndroidAutomationCoordinator(s,it,provider(s,r)).cycle().single().getString("state"))};assertEquals(1L,count("automation_results"));assertEquals(0L,count("automation_cumulative_history"));proof(dir,JSONObject().put("importedSyntheticOutputOnly",true).put("actualCurrentSTTCalls",0).put("history",0))}}}
}
