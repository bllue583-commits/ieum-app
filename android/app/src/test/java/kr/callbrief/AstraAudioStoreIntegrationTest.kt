package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** Actual native request/Coordinator/Store.accept, fake Google transport and signed model fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AstraAudioStoreIntegrationTest:ProtectedTextHostTestBase() {
    private lateinit var base:Context;private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession;private lateinit var settings:SecureSettings
    private val source=GeneralLocalStoreTest.Source()
    private val keys=SyntheticPilotIntegrationTest.HostKeys()
    private val drive=GoogleConnectionRegressionTest.FakeDrive()
    private lateinit var scope:DriveALocalScope
    private var clock=System.currentTimeMillis()
    private var active=true
    private fun prepare():String {
        base=RuntimeEnvironment.getApplication()
        session=GoogleConnectionSession(object:GoogleConnectionSession.Persistence {
            var x=JSONObject();override fun read()=JSONObject(x.toString());override fun write(value:JSONObject){x=JSONObject(value.toString())}
        })
        val epoch=session.begin();session.activate(epoch,"public-astra@example.invalid","public-astra@example.invalid","PUBLIC_ASTRA_OWNER","PUBLIC_SYNTHETIC_NOT_A_GOOGLE_TOKEN")
        GoogleConnectionSession.installForHost(session);scope=checkNotNull(DriveALocalScope.current(session));scoped=AccountStorageContext(base,scope.owner)
        val key=SecretKeySpec(ByteArray(32){0x41},"AES")
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess {override fun existing()=key;override fun createForFirstEmptyStore()=key})
        settings.update{it.put("treeUri",source.rootUri)}
        val bytes=File("../../worker/tests/audio/public-ko.wav").readBytes()
        source.bytes["public-audio"]=bytes;source.documents["public-audio"]=SourceDocument("public-audio","${source.rootUri}/document/public-audio","astra-public.wav",bytes.size.toLong(),"audio/wav",GeneralLocalStoreTest.NOW-600000)
        GeneralLocalStoreTest.host(scoped,keys).use{it.initializeEmpty();assertEquals(1,it.importSelected(source).newVersions)}
        SyntheticApprovedSource.approve(settings,session,source,source.documents.getValue("public-audio"),true)
        val id=GeneralLocalStore.catalog(scoped).listFiles().getJSONObject(0).getString("file_id")
        GeneralLocalStoreTest.host(scoped,keys).use{it.confirmTime(id,snapshot(id),GeneralLocalStoreTest.NOW-3600000)}
        RecordingWorkflowStore(scoped).use{it.confirmWork(id,it.snapshot(id),"공개 합성 담당","공개 합성 회사","공개 합성 업무","")}
        return id
    }
    @After fun cleanup(){GoogleConnectionSession.resetForHost()}
    private fun snapshot(id:String)=RecordingWorkflowStore(scoped).use{it.snapshot(id)}
    private fun automation()=AndroidAutomationStore(scoped,scope.owner,scope.epoch,{active},{AndroidAutomationRuntime.policyHash(settings.snapshot())},
        {s->source.granted && s.getString("actual_document_id") !in source.excludedIds},{"Etc/UTC"},{clock})
    private fun count(table:String)=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->
        if(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",table).isEmpty())0 else (db.query("SELECT count(*) n FROM $table").single()["n"] as Number).toInt()}
    private inner class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun driveAExport(job:String)=p.driveAExport(job)
        override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job)
        override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun poll(job:String)=p.poll(job)
        override fun retry(job:String):RecordingAnalysisPort.Sent=error("NO_AUTOMATIC_NEW_JOB_RETRY")
        override fun cancel(){};override fun close(){}
    }
    private fun cycle(fixture:AstraAudioRoundTripTest,t:AstraAudioRoundTripTest.FixtureTransport):List<JSONObject> {
        GoogleNativePipeline(base,session,settings,{source},drive,postingCapability={true}){GeneralLocalStoreTest.host(it,keys)}.use{p->
            automation().use{store->NativeAutomationRequests(base,session){NativePort(p)}.use{requests->
                val binding=AstraAudioSourceBinding(scoped,store,source){active}
                AstraAudioRoundTrip(scoped,scope.key,source,binding::current,source.bytes.values.map{AstraAudioContract.sha(it)}.toSet(),
                    {true},t,mapOf("public-test-key" to fixture.signing.public),true,{clock}).use{q->
                    return AndroidAutomationCoordinator(store,requests,AstraAudioAutomationProvider(q,binding::input)).cycle()
                }
            }}
        }
    }
    @Test fun signedFileFeedsExistingAcceptExactlyOnceAndNeverAutoApproves()=runBlocking(Dispatchers.IO) {
        prepare();val fixture=AstraAudioRoundTripTest();val t=fixture.FixtureTransport()
        val first=cycle(fixture,t).single();assertEquals(first.toString(),"REVIEW_REQUIRED",first.getString("state"))
        assertEquals(1,count("automation_results"));assertEquals(1,count("automation_applications"));assertEquals(0,count("workflow_reviewed_summaries"));assertEquals(0,count("automation_cumulative_history"));assertEquals(0,count("workflow_items"))
        clock+=1000000;val second=cycle(fixture,t).single();assertEquals("REVIEW_REQUIRED",second.getString("state"))
        assertEquals(1,count("automation_results"));assertEquals(1,count("automation_applications"));assertEquals(1,t.submits)
    }
    @Test fun userDoneAndManualTextSurviveCandidateReception()=runBlocking(Dispatchers.IO) {
        val id=prepare()
        GeneralLocalStoreTest.host(scoped,keys).use{it.edit(id,snapshot(id),"사용자 메모","done")}
        val before=snapshot(id);val fixture=AstraAudioRoundTripTest();val t=fixture.FixtureTransport()
        val jobs=cycle(fixture,t)
        val after=snapshot(id)
        for(k in listOf("manual_text","manual_status","user_revision","lock_revision"))assertEquals(k,before.get(k),after.get(k))
        assertEquals(0,count("automation_cumulative_history"));assertEquals(0,count("workflow_reviewed_summaries"))
        assertTrue(jobs.all{it.optString("state")!="APPLIED_SAFE_DERIVED"})
    }
}
