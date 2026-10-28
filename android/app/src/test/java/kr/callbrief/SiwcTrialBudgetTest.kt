package kr.callbrief

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SiwcTrialBudgetTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication() as android.content.Context
    private val request=SiwcAstraProtocol.Invocation("PUBLIC_TRIAL_SCOPE",SiwcAstraProtocol.Stage.ANALYSIS,"gpt-6-astra","xhigh","a".repeat(64),mapOf("public" to true),"Public fixture only")
    private var calls=0
    private var fail=false
    private val port=object:SiwcInferencePort {
        override fun models(scope:String)=listOf("gpt-6-astra")
        override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {
            calls++;if(fail)throw java.io.IOException("PUBLIC_FIXTURE_UNKNOWN")
            return SiwcAstraProtocol.Completion("public-fixture-response","gpt-6-astra","NOT_REPORTED","{\"public\":true}","b".repeat(64))
        }
    }
    private fun budget()=SiwcTrialBudget(app)
    private fun bind(b:SiwcTrialBudget)=b.bind(request.scope,request.sourceManifestSha,"c".repeat(64))
    private fun rejects(block:()->Unit){assertNotNull(runCatching(block).exceptionOrNull())}
    @Test fun unboundTrialCannotAttemptAPost()=runBlocking(Dispatchers.IO){budget().use{b->rejects{b.port(port).invoke(request){false}}};assertEquals(0,calls)}
    @Test fun restartCannotRepeatTheSameOrANewAttemptKey()=runBlocking(Dispatchers.IO){
        budget().use{b->bind(b);b.port(port).invoke(request){false}}
        budget().use{b->bind(b);rejects{b.port(port).invoke(request){false}};rejects{b.port(port).invoke(request.nextAttempt()){false}}}
        assertEquals(1,calls)
    }
    @Test fun failedOrUnknownTransportStillConsumesItsOnlySlot()=runBlocking(Dispatchers.IO){
        fail=true;budget().use{b->bind(b);rejects{b.port(port).invoke(request){false}};assertTrue(b.snapshot().toString().contains("FAILED_OR_UNKNOWN"))}
        fail=false;budget().use{b->rejects{b.port(port).invoke(request){false}}};assertEquals(1,calls)
    }
    @Test fun interruptedClaimCannotBeReplayedAsANewPost()=runBlocking(Dispatchers.IO){
        budget().use{b->bind(b);b.claim(request)}
        budget().use{b->rejects{b.port(port).invoke(request){false}};assertTrue(b.snapshot().toString().contains("ATTEMPTING"))};assertEquals(0,calls)
    }
    @Test fun changedAccountManifestOrSnapshotCannotReplaceTheTrialBinding()=runBlocking(Dispatchers.IO){budget().use{b->bind(b)
        rejects{b.bind("OTHER_ACCOUNT",request.sourceManifestSha,"c".repeat(64))};rejects{b.bind(request.scope,"d".repeat(64),"c".repeat(64))};rejects{b.bind(request.scope,request.sourceManifestSha,"d".repeat(64))}
    }}
    @Test fun separateContextHasExactlyOneSeparateSlot()=runBlocking(Dispatchers.IO){budget().use{b->bind(b);val checked=b.port(port)
        SiwcInferenceJobs(app,request.scope,checked){true}.use{jobs->
            assertEquals("COMPLETE",jobs.run(request){}.state)
            val c=jobs.context(request,mapOf("synthetic" to true),"gpt-6-astra","xhigh","Fixture context")
            assertEquals("COMPLETE",jobs.run(c){}.state);assertEquals("COMPLETE",jobs.run(request){}.state);assertEquals("COMPLETE",jobs.run(c){}.state)
            assertEquals(2,calls);assertEquals(2,(b.snapshot()["slots"] as List<*>).size)
        }
    }}
    @Test fun committedJobReplayAfterReopeningDoesNotTouchTheBudgetPort()=runBlocking(Dispatchers.IO){
        budget().use{b->bind(b);SiwcInferenceJobs(app,request.scope,b.port(port)){true}.use{assertEquals("COMPLETE",it.run(request){}.state)}}
        budget().use{b->SiwcInferenceJobs(app,request.scope,b.port(port)){true}.use{assertEquals("COMPLETE",it.run(request){}.state)}};assertEquals(1,calls)
    }
    @Test fun rejectedCompletedOutputIsRetainedOnlyAsAnObservationAndNeverRetried()=runBlocking(Dispatchers.IO){
        var current=true
        budget().use{b->bind(b);SiwcInferenceJobs(app,request.scope,b.port(port)){current}.use{jobs->
            assertEquals("REJECTED",jobs.run(request){error("FIXTURE_SCHEMA_REJECTED")}.state)
            assertEquals("{\"public\":true}",jobs.observed(request)!!["output"]);rejects{jobs.completed(request){}}
            assertEquals("REJECTED",jobs.run(request){}.state);assertEquals(1,calls)
            current=false;rejects{jobs.observed(request)}
        }}
    }
}
