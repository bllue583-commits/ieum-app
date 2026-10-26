package kr.callbrief

import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Typed local observation/CAS controls; no accounts, remote service or model invocation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code19ResultOrderingTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private var record="";private var request=""
    private val threads=Executors.newFixedThreadPool(2)
    private fun <T> io(f:()->T):T=try{threads.submit<T>{f()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    @Before fun setup(){context=RuntimeEnvironment.getApplication();io{GeneralLocalStoreTest.host(context,SyntheticPilotIntegrationTest.HostKeys()).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});val r=s.snapshot().getJSONObject(0);record=r.getString("file_id");s.edit(record,r,"PUBLIC_ORDERING_MANUAL_DONE","done")};RecordingWorkflowStore(context).use{s->request=s.prepareAnalysis(record,s.snapshot(record));s.transitionRequest(request,"SENT","PUBLIC_JOB")}}}
    @After fun stop(){threads.shutdown();assertTrue(threads.awaitTermination(5,TimeUnit.SECONDS))}
    private fun observation(state:String,revision:Long,digest:Char=('a'.code+revision.toInt()).toChar(),kind:String="CALL_ANALYSIS_RESULT",schema:Long=0)=GoogleRelayStore.ResultObservation("PUBLIC_JOB",state,kind,schema,revision,digest.toString().repeat(64))
    private fun apply(o:GoogleRelayStore.ResultObservation)=RecordingWorkflowStore(context).use{it.commitResultObservation(request,o)}
    private fun state()=RecordingWorkflowStore(context).use{it.request(request).getString("state")}
    @Test fun O01OrderedNewRevisionsCanChangeStateAndOldOnesCannot():Unit=io{assertEquals("PENDING",apply(observation("PENDING",0)));assertEquals("ERROR",apply(observation("ERROR",1)));assertEquals("ERROR",apply(observation("PENDING",0)));assertEquals("REVIEW",apply(observation("LEGACY_CANDIDATE",2)));assertEquals("REVIEW",apply(observation("ERROR",1)));assertEquals("ERROR",apply(observation("ERROR",3)));assertEquals("REVIEW",apply(observation("LEGACY_CANDIDATE",4)));assertEquals("REVIEW",apply(observation("ERROR",3)))}
    @Test fun O02SameRevisionReplayDoesNotRewriteStateOrDigest():Unit=io{assertEquals("ERROR",apply(observation("ERROR",1)));assertEquals("ERROR",apply(observation("LEGACY_CANDIDATE",1)));assertEquals("ERROR",apply(observation("LEGACY_CANDIDATE",1,'f')));val r=RecordingWorkflowStore(context).use{it.request(request)};assertEquals("RESULT_REVISION_CONFLICT",r.getString("reason"))}
    @Test fun O03UnsupportedHigherProfileDoesNotAdvanceCursor():Unit=io{assertEquals("REVIEW",apply(observation("LEGACY_CANDIDATE",2)));assertEquals("REVIEW",apply(observation("HELD_UNSUPPORTED_RESULT_PROFILE",9)));assertEquals("ERROR",apply(observation("ERROR",3)));assertEquals("ERROR",apply(observation("LEGACY_CANDIDATE",2)))}
    @Test fun O04ReopenKeepsDurableHighestAcceptedRevision():Unit=io{apply(observation("ERROR",2));assertEquals("ERROR",apply(observation("PENDING",0)));assertEquals("ERROR",state())}
    @Test fun O05UnversionedAfterAcceptedCursorIsHeld():Unit=io{apply(observation("LEGACY_CANDIDATE",2));assertEquals("REVIEW",apply(GoogleRelayStore.ResultObservation("PUBLIC_JOB","ERROR")));assertEquals("UNVERSIONED_RESULT_HELD",RecordingWorkflowStore(context).use{it.request(request).getString("reason")})}
    @Test fun O06AnotherJobCannotReuseTheCursorNamespace():Unit=io{apply(observation("ERROR",1));try{apply(GoogleRelayStore.ResultObservation("OTHER_JOB","LEGACY_CANDIDATE","CALL_ANALYSIS_RESULT",0,2,"c".repeat(64)));fail()}catch(e:IllegalStateException){assertEquals("RESULT_REQUEST_JOB_MISMATCH",e.message)};assertEquals("ERROR",state())}
    @Test fun O07LateCommitAfterNewerCommitKeepsReview(){val oldReady=CountDownLatch(1);val releaseOld=CountDownLatch(1);val old=threads.submit<String>{oldReady.countDown();check(releaseOld.await(5,TimeUnit.SECONDS));apply(observation("ERROR",1))};assertTrue(oldReady.await(3,TimeUnit.SECONDS));val newer=threads.submit<String>{apply(observation("LEGACY_CANDIDATE",2))};assertEquals("REVIEW",newer.get(5,TimeUnit.SECONDS));releaseOld.countDown();assertEquals("REVIEW",old.get(5,TimeUnit.SECONDS));assertEquals("REVIEW",io{state()})}
    @Test fun O08SyncUsesRevisionAndMetadataFreeHintsCannotDowngrade():Unit=io{apply(observation("LEGACY_CANDIDATE",2));RecordingWorkflowStore(context).use{s->assertEquals("REVIEW",s.commitSyncObservation(request,RecordingAnalysisPort.SyncObservation("EXPLICIT_NEW_JOB_RETRY_REQUIRED",observation("ERROR",1))));assertEquals("REVIEW",s.commitSyncObservation(request,RecordingAnalysisPort.SyncObservation("EXPLICIT_NEW_JOB_RETRY_REQUIRED")))};assertEquals("REVIEW",state())}
    @Test fun O09CrossProfileRevisionAndManualDoneStayIndependent():Unit=io{apply(observation("ERROR",1));assertEquals("REVIEW",apply(observation("READ_ONLY_CANDIDATE",2,'c',"CALL_ANALYSIS_READ_ONLY_RESULT",1)));assertEquals("REVIEW",apply(observation("ERROR",1)));val r=RecordingWorkflowStore(context).use{it.snapshot(record)};assertEquals("done",r.getString("manual_status"));assertEquals("PUBLIC_ORDERING_MANUAL_DONE",r.getString("manual_text"));assertTrue(r.isNull("effective_summary_version"))}
}
