package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** v3.4 r14-3 (DESIGN_V4 4.3): the routine's self-report read by the results pass, app/version.json on every pass (ETag),
 * latency outliers and the record time buckets, the company rename edit, tidy rewrites that change numbers, mail addresses
 * out of every request, and the mail and update error codes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R14ThreeTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val t=LocalDate.of(2026,11,6).atTime(15,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val hour=3_600_000L;private val day=86_400_000L
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();InboxETags.clear();InboxETags.results=null
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2","rec3","rec4").forEach { r -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',10,0,0,1,1,1)",r,GeneralLocalStore.OWNER,r) }
            null
        } }
    }
    @After fun tearDown() { InboxETags.clear();InboxETags.results=null }
    private fun store(at:Long)=CallRecordStore(context){ at }
    private fun call(id:String,at:Long)=CallTranscript(id,1,"a".repeat(64),at,60_000,"김과장",ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
    private fun analysis(tasks:JSONArray=JSONArray())=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(tasks))
    private class Inbox:CallInboxStore {
        val files=LinkedHashMap<String,String>();val reads=mutableListOf<String>()
        override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
        override fun replace(path:String,json:String){ files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
        override fun get(path:String):String { reads+=path;return files[path] ?: throw ClaudeCallException("INBOX_HTTP_404") }
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    private fun run(inbox:Inbox,s:JSONObject,at:Long=t,running:Int=1040,seen:MutableList<Int> =mutableListOf())=
        CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false },afterSend={},
            onNewVersion={ seen+=it },runningCode=running).runPending()
    private fun settings()=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)

    // ---- 4.3-2, 4.3-3: the hourly self-report ----
    @Test fun theResultsPassKeepsTheRoutineEndAndItsCron() {
        val inbox=Inbox();val s=settings()
        inbox.files[RoutineStatus.HOURLY]=JSONObject().put("routine","hourly").put("endedMs",t-20*60_000L).put("processed",2)
            .put("cron","CRON_TZ=Asia/Seoul 34 7-22 * * *").put("repoOk",true).put("pushed",true).toString()
        run(inbox,s)
        assertEquals(t-20*60_000L,s.getLong(PipelineSettingsKeys.ROUTINE_ENDED_MS))
        assertEquals(listOf("CRON_TZ=Asia/Seoul 34 7-22 * * *"),JSONArray(s.getString(PipelineSettingsKeys.ROUTINE_CRONS)).let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("the chain reads it",listOf(34),ResultChain.crons(s).single().minutes)
        // Two crons (4.3-6) as a list; text that is no cron is never kept.
        inbox.files[RoutineStatus.HOURLY]=JSONObject().put("routine","hourly").put("endedMs",t-5*60_000L)
            .put("crons",JSONArray().put("CRON_TZ=Asia/Seoul 34 7-22 * * *").put("CRON_TZ=Asia/Seoul 4 8-22 * * *").put("김부장 010")).toString()
        run(inbox,s)
        assertEquals(t-5*60_000L,s.getLong(PipelineSettingsKeys.ROUTINE_ENDED_MS))
        assertEquals(2,JSONArray(s.getString(PipelineSettingsKeys.ROUTINE_CRONS)).length())
        assertEquals(2,ResultChain.crons(s).size)
    }

    @Test fun theOldSharedReportCountsOnlyWhenItIsTheHourlyOnes() {
        val inbox=Inbox();val s=settings()
        inbox.files[RoutineStatus.OLD]=JSONObject().put("routine","tidy").put("endedMs",t-hour).toString()
        run(inbox,s)
        assertFalse("the tidy's report is no sign of the hourly routine",s.has(PipelineSettingsKeys.ROUTINE_ENDED_MS))
        inbox.files[RoutineStatus.OLD]=JSONObject().put("routine","hourly").put("endedMs",t-2*hour).put("cron","CRON_TZ=Asia/Seoul 34 7-22 * * *").toString()
        run(inbox,s)
        assertEquals(t-2*hour,s.getLong(PipelineSettingsKeys.ROUTINE_ENDED_MS))
        // The new file wins once it is there.
        inbox.files[RoutineStatus.HOURLY]=JSONObject().put("routine","hourly").put("endedMs",t-hour/2).toString()
        run(inbox,s)
        assertEquals(t-hour/2,s.getLong(PipelineSettingsKeys.ROUTINE_ENDED_MS))
    }

    // ---- 4.3-4: app/version.json on every results pass ----
    @Test fun aNewerVersionStartsTheUpdateCheckOnce() {
        val inbox=Inbox();val s=settings();val seen=mutableListOf<Int>()
        inbox.files[RoutineStatus.VERSION]=JSONObject().put("versionCode",1040).put("apk","x").put("sha256","0").toString()
        run(inbox,s,seen=seen);assertEquals(emptyList<Int>(),seen)
        inbox.files[RoutineStatus.VERSION]=JSONObject().put("versionCode",1045).put("apk","x").put("sha256","0").toString()
        run(inbox,s,seen=seen);run(inbox,s,seen=seen)
        assertEquals("once per version code",listOf(1045),seen)
        inbox.files[RoutineStatus.VERSION]=JSONObject().put("versionCode",1046).put("apk","x").put("sha256","0").toString()
        run(inbox,s,seen=seen);assertEquals(listOf(1045,1046),seen)
        // Already running it: nothing.
        run(inbox,s,running=1046,seen=seen.also { it.clear() });assertEquals(emptyList<Int>(),seen)
    }

    /** The self-report and version.json are conditional reads: an unchanged file is a 304 with no body. */
    @Test fun versionAndReportAreReadWithTheirETag() {
        listOf(RoutineStatus.VERSION,RoutineStatus.HOURLY,RoutineStatus.OLD).forEach { assertTrue(it,InboxETags.cacheable("/repos/o/r/contents/$it")) }
        val seen=mutableListOf<String?>();var bodies=0
        val server=okhttp3.Interceptor { chain -> val r=chain.request();seen+=r.header("If-None-Match")
            val b=okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).header("ETag","W/\"v1\"")
            if(r.header("If-None-Match")=="W/\"v1\"") b.code(304).message("Not Modified").body(ByteArray(0).toResponseBody(null)).build()
            else { bodies++;b.code(200).message("OK").body("{\"versionCode\":1045}".toResponseBody(null)).build() } }
        val store=GitHubInboxStore("o/r","t",okhttp3.OkHttpClient.Builder().addInterceptor(InboxETags).addInterceptor(server).build())
        assertEquals(1045,JSONObject(store.get(RoutineStatus.VERSION)).getInt("versionCode"))
        assertEquals(1045,JSONObject(store.get(RoutineStatus.VERSION)).getInt("versionCode"))
        assertEquals(listOf(null,"W/\"v1\""),seen);assertEquals("304: no second body",1,bodies)
    }

    @Test fun anUpdateCheckErrorIsACodeAndClearsWhenAGoodCheckFollows() {
        assertEquals("INBOX_HTTP_404",CallUpdate.checkErrorCode(ClaudeCallException("INBOX_HTTP_404")))
        assertEquals("UPDATE_SHA",CallUpdate.checkErrorCode(IllegalStateException("UPDATE_SHA")))
        assertEquals("SocketTimeoutException",CallUpdate.checkErrorCode(java.net.SocketTimeoutException("read timed out at host x")))
        assertEquals("IOException",CallUpdate.checkErrorCode(java.io.IOException("김부장 메일 실패")))
    }

    // ---- 4.3-5: latency outliers and record time ----
    @Test fun latencyLeavesOutFirstPassRecordsAndOverAWeek() {
        val a=store(t).use { it.insert(call("rec1",t-2*hour));CallRecordStore.recordId(call("rec1",0)) }      // 119 minutes
        val b=store(t).use { it.insert(call("rec2",t-20*day));CallRecordStore.recordId(call("rec2",0)) }      // first pass: stored 20 days on
        val c=store(t-8*day+hour).use { it.insert(call("rec3",t-8*day));CallRecordStore.recordId(call("rec3",0)) } // answered 8 days on
        store(t).use { it.insert(call("rec4",t+3*day)) }                                                       // a started_ms in the future
        store(t).use { db -> db.applyAnalysis(a,analysis());db.applyAnalysis(b,analysis());db.applyAnalysis(c,analysis()) }
        val sample=store(t).use { it.resultLatency(t-7*day) }
        assertEquals(listOf(119L),sample.minutes);assertEquals(2,sample.outliers)
        val buckets=store(t).use { it.recordTimeBuckets() }
        assertEquals(RECORD_TIME_BUCKETS,buckets.keys().asSequence().toList())
        assertEquals(2,buckets.getInt("d0"));assertEquals(1,buckets.getInt("d8to30"));assertEquals(1,buckets.getInt("negOver1d"));assertEquals(0,buckets.getInt("over30d"))
        assertEquals("neg",recordTimeBucket(-1));assertEquals("d1",recordTimeBucket(day));assertEquals("d2to7",recordTimeBucket(7*day));assertEquals("over30d",recordTimeBucket(31*day))
        // The runner keeps both for the heartbeat: latency with outliers, recordTime as {bucket: count}.
        val s=settings();run(Inbox(),s)
        val l=JSONObject(s.getString(PipelineSettingsKeys.LATENCY))
        assertEquals(119L,l.getLong("p50Min"));assertEquals(1,l.getInt("n"));assertEquals(2,l.getInt("outliers"))
        assertEquals(buckets.toString(),s.getString(PipelineSettingsKeys.RECORD_TIME))
        // Numbers only: the heartbeat's count filter keeps every bucket name.
        assertEquals(RECORD_TIME_BUCKETS.size,PhoneStatus.counts(JSONObject(s.getString(PipelineSettingsKeys.RECORD_TIME))).length())
    }

    // ---- 4.3-7: the ledger ----
    @Test fun aCompanyRenameGoesUpAsALedgerEdit() {
        store(t).use { } // the store makes its tables
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            p.execute("INSERT INTO companies(id,name,trade,memo,updated_ms,name_key) VALUES('co_1','단단이엔씨',NULL,'',0,'k1')")
            p.execute("INSERT INTO companies(id,name,trade,memo,updated_ms,name_key) VALUES('co_2','대한철강',NULL,'',0,'k2')") }
        assertEquals("co_1",store(t).use { it.renameCompany("co_1","탄탄이엔씨") })
        val edits=store(t).use { it.pendingEdits() }.map { JSONObject(it.second) }
        assertEquals(1,edits.size)
        val e=edits.single()
        assertEquals("renameCompany",e.getString("type"));assertEquals("단단이엔씨",e.getString("from"));assertEquals("탄탄이엔씨",e.getString("to"));assertEquals(t,e.getLong("atMs"))
        // The same name again: no edit.
        store(t).use { it.renameCompany("co_1","탄탄이엔씨") };assertEquals(1,store(t).use { it.pendingEdits() }.size)
        // Renamed into another company: the two become one, and the edit says so (to = the existing name).
        assertEquals("co_1",store(t).use { it.renameCompany("co_2","탄탄이엔씨") })
        val last=JSONObject(store(t).use { it.pendingEdits() }.last().second)
        assertEquals("대한철강",last.getString("from"));assertEquals("탄탄이엔씨",last.getString("to"))
    }

    @Test fun aTidyRewriteThatChangesNumbersIsRefused() {
        val tr=CallTranscript("rec1",1,"a".repeat(64),t,60_000,"김과장",ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
        val id=CallRecordStore.recordId(tr)
        store(t).use { db -> db.insert(tr);db.applyAnalysis(id,analysis(JSONArray()
            .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 1,500만 원 2회분 보내기","견적서는 내일까지 메일로 보내드릴게요"))
            .put(ClaudeCallAnalyzerTest.task("create",null,"계약서 초안 받기","계약서 초안은 다음 주 월요일에 받기로 하죠")))) }
        val items=store(t).use { it.openItems() }
        val a=items.single { it.title.startsWith("견적서") };val b=items.single { it.title.startsWith("계약서") }
        fun rewrite(id:String,title:String)=store(t).use { it.applyTidy(JSONObject().put("rev",1).put("tasks",JSONArray()
            .put(JSONObject().put("op","rewrite").put("id",id).put("title",title)))) }
        assertEquals("an amount changed",0,rewrite(a.id,"견적서 1,800만 원 2회분 메일로 보내기"))
        assertEquals("a number dropped",0,rewrite(a.id,"견적서 메일로 보내기"))
        assertEquals("a number added",0,rewrite(b.id,"계약서 초안 3부 받기"))
        assertEquals("same numbers, other words and order",1,rewrite(a.id,"2회분 견적서(1500만 원) 메일로 보내기"))
        assertEquals("no numbers either side",1,rewrite(b.id,"월요일에 계약서 초안 받기"))
        assertEquals(setOf("2회분 견적서(1500만 원) 메일로 보내기","월요일에 계약서 초안 받기"),store(t).use { it.openItems() }.map { it.title }.toSet())
        assertEquals(listOf("1500","2"),titleNumbers("견적 1,500만 원 2회"))
    }

    // ---- 4.3-8: mail addresses ----
    @Test fun noRequestCarriesAMailAddress() {
        val transcript="네 견적서는 kim.ms@steel-co.co.kr 로 보내 주시고 참조는 lee_01@naver 에도요. 단가 @15,000원이고 개당@1,500 입니다. 번호는 010-5555-6666이에요."
        val texts="[10:00 받은 문자] 메일 주소 park2026@gmail.com 입니다"
        val m=ClaudeCallAnalyzer.userMessage(ClaudeCallAnalyzerTest.request(transcript=transcript,texts=texts))
        val body=m.substringAfter("<texts>")
        assertFalse(body.contains("steel-co"));assertFalse(body.contains("naver"));assertFalse(body.contains("gmail"));assertFalse(body.contains("6666"))
        assertFalse("no address shape left",Regex("[A-Za-z0-9._%+-]+@[A-Za-z]").containsMatchIn(m))
        assertEquals(3,Regex(Regex.escape(MessageIntake.ADDRESS_MASK)).findAll(body).count())
        assertTrue("a unit price stays",body.contains("단가 @15,000원"));assertTrue(body.contains("개당@1,500"))
        // Addresses only: with no price in the text no '@' is left at all.
        val plain=ClaudeCallAnalyzer.maskNumbers("회신은 a.b-c@x.co.kr, 그리고 98765432100@hanmail.net 으로")
        assertFalse(plain.contains('@'));assertFalse(plain.contains("98765"))
        assertEquals("회신은 [메일 주소], 그리고 [메일 주소] 으로",plain)
    }
}
