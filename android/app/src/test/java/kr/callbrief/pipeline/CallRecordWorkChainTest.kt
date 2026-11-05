package kr.callbrief.pipeline

import android.content.Context
import androidx.work.ExistingWorkPolicy
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** r10-3: the result chain (ieum.calls.results every 10 minutes after an upload, 12 rounds) and the results listing's
 * If-None-Match that survives a new process. WorkManager and settings are fakes: what would be registered is recorded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CallRecordWorkChainTest {
    private lateinit var context:Context
    private val now=1_800_000_000_000L
    private val plans=mutableListOf<ResultChain.Plan>()
    private val s=JSONObject()
    private val enqueue:(ResultChain.Plan)->Unit={ plans+=it }
    private val save:((JSONObject)->Unit)->Unit={ edit -> edit(s) }
    @Before fun setup() { context=RuntimeEnvironment.getApplication();InboxETags.clear();InboxETags.results=null }
    @After fun tearDown() { InboxETags.clear();InboxETags.results=null }

    @Test fun anUploadSetsTheResultsWorkTenMinutesOn() {
        assertEquals("ieum.calls.results",ResultChain.NAME)
        ResultChain.start(context,now,enqueue,save)
        assertEquals(listOf(ResultChain.Plan(1,10*60_000L,ExistingWorkPolicy.REPLACE)),plans)
        assertEquals(1,s.getInt(ResultChain.ROUND_KEY));assertEquals(now+10*60_000L,s.getLong(ResultChain.NEXT_MS_KEY))
    }

    @Test fun whileARequestWaitsItComesBackEveryTenMinutes() {
        ResultChain.afterRound(context,1,sent=0,waiting=true,nowMs=now,enqueue=enqueue,save=save)
        assertEquals(listOf(ResultChain.Plan(2,10*60_000L,ExistingWorkPolicy.APPEND_OR_REPLACE)),plans)
        assertEquals(2,s.getInt(ResultChain.ROUND_KEY));assertEquals(now+10*60_000L,s.getLong(ResultChain.NEXT_MS_KEY))
    }

    @Test fun afterTwelveRoundsOnlyTheHourlyTickIsLeft() {
        ResultChain.start(context,now,enqueue,save)
        var round=1;var t=now
        // Nothing ever answers: the chain runs its rounds and then registers nothing.
        while(true) {
            t+=10*60_000L
            val before=plans.size
            ResultChain.afterRound(context,round,sent=0,waiting=true,nowMs=t,enqueue=enqueue,save=save)
            if(plans.size==before) break
            round=plans.last().round
        }
        assertEquals((1..12).toList(),plans.map { it.round })
        assertTrue(plans.all { it.delayMs==10*60_000L })
        assertEquals(12,round);assertNull(ResultChain.next(12,0,true))
        assertEquals("chain over in settings",0,s.getInt(ResultChain.ROUND_KEY));assertEquals(0L,s.getLong(ResultChain.NEXT_MS_KEY))
    }

    @Test fun theChainStopsOnceEveryAnswerIsIn() {
        ResultChain.afterRound(context,3,sent=0,waiting=false,nowMs=now,enqueue=enqueue,save=save)
        assertTrue(plans.isEmpty());assertEquals(0,s.getInt(ResultChain.ROUND_KEY))
        assertNull(ResultChain.next(1,0,false))
    }

    @Test fun aRoundThatSentSomethingStartsTheCountAgain() {
        ResultChain.afterRound(context,12,sent=1,waiting=true,nowMs=now,enqueue=enqueue,save=save)
        assertEquals(listOf(ResultChain.Plan(1,10*60_000L,ExistingWorkPolicy.APPEND_OR_REPLACE)),plans)
        assertEquals(1,ResultChain.next(7,2,false))
    }

    /** A pass hands the interceptor its settings slot for the results listing's ETag, before anything is read. */
    @Test fun aPassKeepsTheResultsETagInItsSettings() {
        var started=0
        s.put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        val runner=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now },
            object:CallInboxStore {
                override fun put(path:String,json:String) {}
                override fun list(dir:String)=emptyList<Pair<String,String>>()
                override fun get(path:String):String=throw java.io.FileNotFoundException(path)
                override fun delete(path:String,sha:String) {}
            },save={ edit -> edit(s) },callLog={ emptyList() },smsAllowed={ false },afterSend={ started++ })
        runner.runPending();assertEquals("nothing sent: no chain",0,started)
        val slot=InboxETags.results!!
        assertNull(slot.get())
        slot.set("W/\"x\"");assertEquals("W/\"x\"",s.getString(InboxETags.RESULTS_ETAG_KEY));assertEquals("W/\"x\"",slot.get())
        slot.set(null);assertNull(slot.get())
    }

    // ---- If-None-Match on the results listing, across processes -----------------------------------------------------
    private class FakeServer(var listing:String,var etag:String) {
        val seen=mutableListOf<String?>();var bodies=0
        val interceptor=okhttp3.Interceptor { chain -> val r=chain.request();seen+=r.header("If-None-Match")
            val b=okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).header("ETag",etag)
            if(r.header("If-None-Match")==etag) b.code(304).message("Not Modified").body(ByteArray(0).toResponseBody(null)).build()
            else { bodies++;b.code(200).message("OK").body(listing.toResponseBody(null)).build() } }
    }
    private fun store(server:FakeServer)=GitHubInboxStore("o/r","t",okhttp3.OkHttpClient.Builder().addInterceptor(InboxETags).addInterceptor(server.interceptor).build())

    @Test fun anEmptyResultsListingIsAsked304EvenInANewProcess() {
        var kept:String?=null
        InboxETags.results=ETagSlot({ kept },{ kept=it })
        val server=FakeServer("[]","W/\"e1\"")
        assertEquals(emptyList<Pair<String,String>>(),store(server).list(CallInbox.RESULTS))
        assertEquals("the empty listing's ETag is kept","W/\"e1\"",kept)
        InboxETags.clear() // a new process: nothing in memory, the ETag from settings
        assertEquals(emptyList<Pair<String,String>>(),store(server).list(CallInbox.RESULTS))
        assertEquals(listOf(null,"W/\"e1\""),server.seen);assertEquals("304: no body read",1,server.bodies)
        // A result arrives: a new ETag, a full read, and a listing with files is not kept across processes.
        server.listing="[{\"type\":\"file\",\"name\":\"a.json\",\"sha\":\"s1\"}]";server.etag="W/\"e2\""
        InboxETags.clear()
        assertEquals(listOf("a.json" to "s1"),store(server).list(CallInbox.RESULTS))
        assertNull(kept)
        InboxETags.clear()
        assertEquals("no saved ETag: the result is read again",listOf("a.json" to "s1"),store(server).list(CallInbox.RESULTS))
        assertNull(server.seen.last())
    }

    @Test fun theLedgerIsNeverKeptOutsideTheProcess() {
        var kept:String?="W/\"old\""
        InboxETags.results=ETagSlot({ kept },{ kept=it })
        val server=FakeServer("{\"rev\":1}","W/\"l1\"")
        store(server).get(CallInbox.LEDGER)
        assertEquals("the ledger asks without the results ETag",listOf<String?>(null),server.seen);assertEquals("W/\"old\"",kept)
    }
}
