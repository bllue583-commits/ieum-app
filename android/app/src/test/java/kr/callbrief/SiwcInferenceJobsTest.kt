package kr.callbrief

import kr.callagent.drive.StrictJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SiwcInferenceJobsTest:ProtectedTextHostTestBase() {
    private val request=SiwcAstraProtocolTest().request()
    private var current=true
    private inner class FixturePort:SiwcInferencePort {
        var calls=0;var failure:Exception?=null;var after:()->Unit={};var available=true
        override fun models(scope:String)=if(available)listOf("gpt-6-astra") else emptyList()
        override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {
            calls++;after();failure?.let{throw it}
            return SiwcAstraProtocol.completed(SiwcAstraProtocolTest().event().byteInputStream(),request,stopped)
        }
    }
    private fun jobs(port:SiwcInferencePort)=SiwcInferenceJobs(RuntimeEnvironment.getApplication(),request.scope,port){current}
    private fun validate(text:String) {check(AstraAudioContract.parse(text.toByteArray())["summary"]=="공개 후보")}
    @Test fun analysisAndContextAreSeparateDurableCallsAndReplayDoesNotInvoke()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();lateinit var context:SiwcAstraProtocol.Invocation
        jobs(port).use{j->assertEquals("COMPLETE",j.run(request,::validate).state)
            context=j.context(request,mapOf("revision" to 1L,"approvedSummary" to "공개 기존 검토"),"gpt-6-astra","xhigh","Return context deltas with source evidence, never user approval.")
            assertNotEquals(request.key(),context.key());assertEquals("COMPLETE",j.run(context,::validate).state)}
        jobs(port).use{j->assertEquals("COMPLETE",j.run(request,::validate).state);assertEquals("COMPLETE",j.run(context,::validate).state)}
        assertEquals(2,port.calls)
    }
    @Test fun lostCompletionIsUnknownAndNeverAutomaticallyReinvoked()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();port.failure=IOException("public test network drop")
        jobs(port).use{j->assertEquals("UNKNOWN",j.run(request,::validate).state)}
        port.failure=null;jobs(port).use{j->assertEquals("UNKNOWN",j.run(request,::validate).state)};assertEquals(1,port.calls)
    }
    @Test fun planLimitIsDurablyHeldWithoutFallback()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();port.failure=SiwcAstraProtocol.Failure("SIWC_PLAN_USAGE_HELD")
        jobs(port).use{j->assertEquals("HELD",j.run(request,::validate).state)}
        jobs(port).use{j->val out=j.run(request,::validate);assertEquals("HELD",out.state);assertEquals("SIWC_PLAN_USAGE_HELD",out.reason)};assertEquals(1,port.calls)
    }
    @Test fun cancelFencesCompletionAndPreservesRemoteUnknown()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();jobs(port).use{j->port.after={j.cancel(request.key())};assertEquals("CANCELLED_REMOTE_UNKNOWN",j.run(request,::validate).state)
            assertEquals("CANCELLED_REMOTE_UNKNOWN",j.run(request,::validate).state)};assertEquals(1,port.calls)
    }
    @Test fun missingModelStopsBeforeInference()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();port.available=false;jobs(port).use{j->assertEquals("MODEL_UNAVAILABLE",j.run(request,::validate).state)};assertEquals(0,port.calls)
    }
    @Test fun explicitCompletedRevocationKeepsObservationButFencesFiles()=runBlocking(Dispatchers.IO){
        val port=FixturePort();jobs(port).use{j->
            assertEquals("COMPLETE",j.run(request,::validate).state);val before=j.completed(request,::validate)
            j.cancel(request.key());assertEquals("CANCELLED_REMOTE_UNKNOWN",j.state(request.key()));assertNotNull(j.observed(request));assertEquals(before.output,j.observed(request)!!["output"])
            assertNotNull(runCatching{j.completed(request,::validate)}.exceptionOrNull());assertEquals(1,port.calls)
        }
    }
    @Test fun cancellingRejectedObservationKeepsItsOriginalMeaning()=runBlocking(Dispatchers.IO){
        val port=FixturePort();jobs(port).use{j->
            assertEquals("REJECTED",j.run(request){error("INVALID_CANDIDATE_SCHEMA")}.state);j.cancel(request.key());assertEquals("REJECTED",j.state(request.key()));assertNotNull(j.observed(request));assertEquals(1,port.calls)
        }
    }
    @Test fun invalidCompletedSchemaIsRejectedAndContextCannotStart()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();jobs(port).use{j->assertEquals("REJECTED",j.run(request){error("INVALID_CANDIDATE_SCHEMA")}.state)
            assertNotNull(runCatching{j.context(request,mapOf("revision" to 1L),"gpt-6-astra","xhigh","policy")}.exceptionOrNull())};assertEquals(1,port.calls)
    }
    @Test fun changedSnapshotGetsDifferentContextJob()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();jobs(port).use{j->j.run(request,::validate)
            val a=j.context(request,mapOf("revision" to 1L),"gpt-6-astra","xhigh","policy")
            val b=j.context(request,mapOf("revision" to 2L),"gpt-6-astra","xhigh","policy");assertNotEquals(a.key(),b.key())}
    }
    @Test fun explicitRetryKeepsOriginalUnknownAndRequiresNewInvocationKey()=runBlocking(Dispatchers.IO) {
        val port=FixturePort();port.failure=IOException("public uncertain attempt")
        jobs(port).use{j->assertEquals("UNKNOWN",j.run(request,::validate).state)
            val retry=j.explicitRetry(request);assertNotEquals(request.key(),retry.key());assertArrayEquals(request.body(),retry.body())
            assertEquals(request.key(),retry.priorAttempt);assertEquals(2,retry.attempt);port.failure=null
            assertEquals("COMPLETE",j.run(retry,::validate).state);assertEquals("UNKNOWN",j.state(request.key()))
            assertNotNull(runCatching{j.explicitRetry(retry)}.exceptionOrNull())};assertEquals(2,port.calls)
    }
}
