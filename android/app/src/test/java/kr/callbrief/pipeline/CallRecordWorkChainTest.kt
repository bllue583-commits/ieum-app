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

/** r10-3: the result chain (v3.4: at the routine's runs +3, +8, +15 minutes, at most 24 hours) and the results listing's
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

    // ---- v3.4 (DESIGN_V4 4.3-3): the chain follows the routine's cron, +3, +8, +15 minutes, at most 24 hours ---------------
    /** now is 17:00 KST; [kst] gives a KST clock time of that day (or [day] days on). */
    private val kstMidnight=now-17*3_600_000L
    private fun kst(h:Int,m:Int,day:Int=0)=kstMidnight+day*86_400_000L+h*3_600_000L+m*60_000L
    private val read:()->JSONObject={ s }
    private fun start(at:Long)=ResultChain.start(context,at,enqueue,save,read)
    private fun after(round:Int,sent:Int,waiting:Boolean,at:Long)=ResultChain.afterRound(context,round,sent,waiting,at,enqueue,save,read)
    /** The times the chain would run at, from an upload at [upload] with no answer ever. */
    private fun looks(upload:Long):List<Long> {
        start(upload)
        val out=mutableListOf<Long>()
        while(true) {
            val next=s.getLong(ResultChain.NEXT_MS_KEY);if(next==0L) break
            out+=next
            val before=plans.size
            after(plans.last().round,0,true,next)
            if(plans.size==before) break
        }
        return out
    }

    @Test fun anUploadWaitsForTheNextRunPlusThreeMinutes() {
        assertEquals("ieum.calls.results",ResultChain.NAME)
        assertEquals(now,kst(17,0))
        start(now)
        assertEquals(listOf(ResultChain.Plan(1,37*60_000L,ExistingWorkPolicy.REPLACE)),plans)
        assertEquals(1,s.getInt(ResultChain.ROUND_KEY));assertEquals(kst(17,37),s.getLong(ResultChain.NEXT_MS_KEY))
        assertEquals(now,s.getLong(ResultChain.SINCE_KEY))
    }

    @Test fun whileARequestWaitsItLooksAtThreeEightAndFifteenMinutesThenTheNextRun() {
        val times=looks(now)
        assertEquals(listOf(kst(17,37),kst(17,42),kst(17,49),kst(18,37),kst(18,42),kst(18,49)),times.take(6))
        assertEquals((1..times.size).toList(),plans.map { it.round })
        assertTrue(plans.drop(1).all { it.policy==ExistingWorkPolicy.APPEND_OR_REPLACE })
        // At most 24 hours after the upload, then only the hourly tick. v3.7 r17-2 (DESIGN_V7 3.2 M1, M2): the night has the
        // 03:34 and 04:34 runs, each with a fourth look at +25 minutes; nothing else between 22:49 and 07:37.
        assertTrue(times.last()<=now+ResultChain.HORIZON_MS)
        assertEquals(kst(16,49,1),times.last())
        assertEquals(listOf(kst(3,37,1),kst(3,42,1),kst(3,49,1),kst(3,59,1),kst(4,37,1),kst(4,42,1),kst(4,49,1),kst(4,59,1)),
            times.filter { it>kst(22,49) && it<kst(7,37,1) })
        assertTrue("the last night look is before the 05:05 tidy",times.filter { it<kst(7,0,1) }.last()<kst(5,5,1))
        assertFalse("no +25 by day",times.any { it==kst(17,59) || it==kst(7,59,1) })
        assertEquals("chain over in settings",0,s.getInt(ResultChain.ROUND_KEY));assertEquals(0L,s.getLong(ResultChain.NEXT_MS_KEY))
        // Half the GitHub calls of v3.3 in the first two hours (12 looks then, 6 now).
        assertEquals(6,times.count { it<now+2*3_600_000L })
    }

    @Test fun anUploadAfterTheLastRunWaitsForTheFirstOneNextDay() {
        // v3.7: the first run of the next day is 03:34.
        start(kst(22,50))
        assertEquals(kst(3,37,1),s.getLong(ResultChain.NEXT_MS_KEY))
        // With the cron before v3.7, 07:34.
        s.put(PipelineSettingsKeys.ROUTINE_CRONS,org.json.JSONArray(listOf("CRON_TZ=Asia/Seoul 34 7-22 * * *")).toString())
        start(kst(22,50))
        assertEquals(kst(7,37,1),s.getLong(ResultChain.NEXT_MS_KEY))
        s.remove(PipelineSettingsKeys.ROUTINE_CRONS)
        // A request that went up before 04:34 and is still waiting at 04:50: the night's fourth look, 04:59.
        s.put(ResultChain.SINCE_KEY,kst(4,20,1))
        after(3,0,true,kst(4,50,1))
        assertEquals(kst(4,59,1),s.getLong(ResultChain.NEXT_MS_KEY))
        // Just before a run: that run.
        plans.clear();start(kst(10,30))
        assertEquals(kst(10,37),s.getLong(ResultChain.NEXT_MS_KEY))
    }

    @Test fun twoCronsTakeTheNearerAndABrokenOneTheConstant() {
        s.put(PipelineSettingsKeys.ROUTINE_CRONS,org.json.JSONArray(listOf("CRON_TZ=Asia/Seoul 34 7-22 * * *","CRON_TZ=Asia/Seoul 4 8-22 * * *")).toString())
        start(now)
        assertEquals(kst(17,7),s.getLong(ResultChain.NEXT_MS_KEY))
        s.put(PipelineSettingsKeys.ROUTINE_CRONS,"[\"매시간\"]")
        start(now)
        assertEquals("unreadable: minute 34, 03 to 04 and 07 to 22",kst(17,37),s.getLong(ResultChain.NEXT_MS_KEY))
        s.remove(PipelineSettingsKeys.ROUTINE_CRONS)
        start(now)
        assertEquals(kst(17,37),s.getLong(ResultChain.NEXT_MS_KEY))
    }

    @Test fun cronsAreReadLikeTheScheduler() {
        val c=ResultChain.parseCron("CRON_TZ=Asia/Seoul 34 7-22 * * *")!!
        assertEquals(listOf(34),c.minutes);assertEquals((7..22).toList(),c.hours);assertEquals(java.time.ZoneId.of("Asia/Seoul"),c.zone)
        assertEquals(listOf(3,4)+(7..22).toList(),ResultChain.parseCron(ResultChain.DEFAULT_CRON)!!.hours)
        assertEquals(listOf(4,34),ResultChain.parseCron("4,34 8-22 * * *")!!.minutes)
        assertEquals(listOf(0,30),ResultChain.parseCron("*/30 7 * * *")!!.minutes)
        assertEquals(java.time.ZoneOffset.UTC,ResultChain.parseCron("34 7 * * *")!!.zone)
        listOf(null,"","x","34 7-22 * * 1-5","61 7 * * *","34 9-7 * * *","CRON_TZ=Nowhere/Here 34 7 * * *","34 7 * *").forEach { assertNull(it,ResultChain.parseCron(it)) }
        assertNull(ResultChain.nextFetch(now,now+ResultChain.HORIZON_MS,ResultChain.crons(JSONObject())))
    }

    @Test fun theChainStopsOnceEveryAnswerIsIn() {
        s.put(ResultChain.SINCE_KEY,now)
        after(3,0,false,now+10*60_000L)
        assertTrue(plans.isEmpty());assertEquals(0,s.getInt(ResultChain.ROUND_KEY));assertEquals(0L,s.getLong(ResultChain.SINCE_KEY))
    }

    @Test fun aRoundThatSentSomethingStartsTheCountAgain() {
        s.put(ResultChain.SINCE_KEY,now-23*3_600_000L)
        after(12,1,true,kst(17,40))
        // What went up at 17:40 missed the 17:34 run: the 18:34 run's first look.
        assertEquals(listOf(ResultChain.Plan(1,57*60_000L,ExistingWorkPolicy.APPEND_OR_REPLACE)),plans)
        assertEquals("counted from now",kst(17,40),s.getLong(ResultChain.SINCE_KEY))
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
