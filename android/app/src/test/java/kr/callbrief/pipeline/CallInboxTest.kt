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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Subscription mode: transcript goes into the inbox once, the scheduled task's result is applied and removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallInboxTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private var now=1_793_300_000_000L
    private class FakeInbox:CallInboxStore {
        val files=LinkedHashMap<String,String>();var failNext=false
        override fun put(path:String,json:String){ if(failNext){failNext=false;throw java.io.IOException("x")};files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha-$it"}
        override fun get(path:String)=files.getValue(path)
        override fun delete(path:String,sha:String){ assertEquals("sha-$path",sha);files.remove(path) }
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('rec1',?,'g1','rec1','rec1.m4a','audio/mp4',10,0,0,1,1,1)",GeneralLocalStore.OWNER)
            null
        } }
    }
    private fun runner(inbox:CallInboxStore,consent:Boolean=true)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),
        { JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,consent) },{ now },inbox)

    @Test fun sendsOnceThenAppliesResultAndCleansUp() {
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,125_000,"김과장",text,listOf(CallTranscript.Segment(0,5000,text)),"m","b".repeat(64),true)
        val id=CallRecordStore(context){ now }.use { it.insert(t);CallRecordStore.recordId(t) }
        val inbox=FakeInbox()
        assertTrue(runner(inbox,consent=false).runPending())
        assertTrue(inbox.files.isEmpty())
        assertEquals(CallRecordStore.WAITING_CONSENT,CallRecordStore(context).use { it.record(id)!!.state })

        assertTrue(runner(inbox).runPending())
        val job=inbox.files.entries.single();assertTrue(job.key.startsWith("inbox/"))
        assertFalse("no names or numbers in paths",job.key.contains("rec1")||job.key.contains("김과장"))
        val o=JSONObject(job.value);assertEquals(CallInbox.recordKey(id),o.getString("id"));assertFalse(job.value.contains("rec1|"));assertTrue(o.getString("message").contains(text))
        assertEquals("v3 carries the rules until the task runs hourly.md",CallInbox.CARRY_RULES,o.has("schema")&&o.has("system"));assertEquals(3,o.getInt("format"));assertEquals(ClaudeCallAnalyzer.PROMPT_VERSION,o.getInt("promptVersion"))
        runner(inbox).runPending();assertEquals("not re-sent within 6 h",1,inbox.files.size)

        // The scheduled task answers; an invented quote is dropped by the same filter as the API path.
        inbox.files.remove(job.key)
        val analysis=ClaudeCallAnalyzerTest.analysis(JSONArray()
            .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 메일 보내기","견적서는 내일까지 메일로 보내드릴게요",due="2026-10-30"))
            .put(ClaudeCallAnalyzerTest.task("create",null,"지어낸 일","자막에 없는 말")))
        inbox.files["results/"+CallInbox.fileName(id)]=JSONObject().put("id",id).put("analysis",analysis).toString()
        assertTrue(runner(inbox).runPending())
        assertTrue(inbox.files.isEmpty())
        val r=CallRecordStore(context).use { it.record(id)!! }
        assertEquals(CallRecordStore.ANALYZED,r.state)
        assertEquals(listOf("견적서 메일 보내기"),CallRecordStore(context).use { it.openItems().map { i->i.title } })
    }

    /** The pass before a report and the hourly pass can start together: the second waits for the first, then finds the
     * result already applied and deleted, so it is applied once. */
    @Test fun twoPassesAtOnceApplyAResultOnce() {
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,125_000,"김과장",text,listOf(CallTranscript.Segment(0,5000,text)),"m","b".repeat(64),true)
        val id=CallRecordStore(context){ now }.use { it.insert(t);CallRecordStore.recordId(t) }
        val analysis=ClaudeCallAnalyzerTest.analysis(JSONArray().put(ClaudeCallAnalyzerTest.task("create",null,"견적서 메일 보내기","견적서는 내일까지 메일로 보내드릴게요",due="2026-10-30")))
        val path="results/"+CallInbox.fileName(id)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val reads=java.util.concurrent.atomic.AtomicInteger()
        val inbox=object:CallInboxStore {
            val files=java.util.concurrent.ConcurrentHashMap<String,String>().apply { put(path,JSONObject().put("id",id).put("analysis",analysis).toString()) }
            override fun put(path:String,json:String){ files[path]=json }
            override fun list(dir:String):List<Pair<String,String>> {
                val out=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
                if(dir==CallInbox.RESULTS && entered.count>0) { entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS) } // the first pass stops here
                return out
            }
            override fun get(path:String):String { if(path.startsWith("results/")) reads.incrementAndGet();return files.getValue(path) }
            override fun delete(path:String,sha:String){ files.remove(path) }
        }
        val first=java.util.concurrent.FutureTask { runner(inbox).runPending() };Thread(first).start()
        assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
        val second=java.util.concurrent.FutureTask { runner(inbox).runPending() };Thread(second).start()
        var n=0;while(!CallRecordAnalysisRunner.PASS.hasQueuedThreads() && n++<500) Thread.sleep(10)
        assertTrue("the second pass waits for the first",CallRecordAnalysisRunner.PASS.hasQueuedThreads());assertFalse(second.isDone)
        release.countDown()
        assertTrue(first.get(30,java.util.concurrent.TimeUnit.SECONDS));assertTrue(second.get(30,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("the result file is read by one pass only",1,reads.get())
        assertFalse(inbox.files.containsKey(path))
        assertEquals(listOf("견적서 메일 보내기"),CallRecordStore(context).use { it.openItems().map { i->i.title } })
        assertFalse(CallRecordAnalysisRunner.PASS.isLocked)
    }

    @Test fun aRecordWhoseAnswerCameIsNotSentAgainAndAnOldTableGetsTheColumn() {
        // An installation from before B17: the sent table has two columns only.
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("CREATE TABLE IF NOT EXISTS call_inbox_sent(record_id TEXT PRIMARY KEY,sent_ms INTEGER NOT NULL)");null } }
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,125_000,"김과장",text,listOf(CallTranscript.Segment(0,5000,text)),"m","b".repeat(64),true)
        val id=CallRecordStore(context){ now }.use { it.insert(t);CallRecordStore.recordId(t) }
        val inbox=FakeInbox()
        assertTrue(runner(inbox).runPending());assertEquals(1,inbox.files.size)
        CallRecordStore(context).use { assertNull(it.receivedMs(id));it.setReceived(id,now) }
        // Six hours pass without the answer being applied: the clock alone no longer sends it again.
        inbox.files.clear();now+=RESEND_MS+60_000
        assertTrue(runner(inbox).runPending());assertTrue(inbox.files.isEmpty())
        // The answer was set aside as broken: it may be sent again.
        CallRecordStore(context).use { it.setReceived(id,null) }
        assertTrue(runner(inbox).runPending());assertEquals(1,inbox.files.size)
        assertNull(CallRecordStore(context).use { it.receivedMs(id) })
    }

    @Test fun networkFailureIsRetriedLater() {
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,1000,null,text,emptyList(),"m","b".repeat(64),true)
        CallRecordStore(context){ now }.use { it.insert(t) }
        val inbox=FakeInbox().apply { failNext=true }
        assertFalse(runner(inbox).runPending())
        assertTrue(runner(inbox).runPending());assertEquals(1,inbox.files.size)
    }

    @Test fun updateIsDownloadedVerifiedAndOfferedOnlyWhenNewer() {
        val inbox=FakeInbox()
        val apk="fake apk bytes"
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(apk.toByteArray()).joinToString(""){"%02x".format(it)}
        inbox.files["app/ieum.apk"]=apk
        inbox.files["app/version.json"]=JSONObject().put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE).put("apk","app/ieum.apk").put("sha256",sha).toString()
        assertFalse(CallUpdate.check(context,inbox));assertNull(CallUpdate.ready(context))
        inbox.files["app/version.json"]=JSONObject().put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE+1).put("versionName","새 버전").put("apk","app/ieum.apk").put("sha256","0".repeat(64)).toString()
        assertTrue(runCatching { CallUpdate.check(context,inbox) }.isFailure);assertNull("bad checksum is never offered",CallUpdate.ready(context))
        inbox.files["app/version.json"]=JSONObject().put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE+1).put("versionName","새 버전").put("apk","app/ieum.apk").put("sha256",sha).put("notes","할 일 화면 개선").toString()
        assertTrue(CallUpdate.check(context,inbox))
        assertEquals("할 일 화면 개선",CallUpdate.ready(context)!!.notes)
        assertEquals(apk,CallUpdate.file(context).readText())
    }

    // ---- v2.4 #9: the result pass at :45 KST, and only while something waits ---------------------------------------
    private fun kst(s:String)=java.time.LocalDateTime.parse(s).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    @Test fun resultPassRunsAtTheNext45KstAndOnlyWhileSomethingWaits() {
        assertEquals(kst("2026-11-04T10:45:00"),CallResultsWork.nextSlot(kst("2026-11-04T10:31:00")))
        assertEquals(kst("2026-11-04T11:45:00"),CallResultsWork.nextSlot(kst("2026-11-04T10:45:00")))
        assertEquals(kst("2026-11-04T07:45:00"),CallResultsWork.nextSlot(kst("2026-11-04T02:10:00")))
        assertEquals(kst("2026-11-04T22:45:00"),CallResultsWork.nextSlot(kst("2026-11-04T22:00:00")))
        assertEquals(kst("2026-11-05T07:45:00"),CallResultsWork.nextSlot(kst("2026-11-04T22:50:00")))
        // 10:31 KST is 01:31 UTC: the slot is KST 10:45, not UTC.
        assertEquals(14*60_000L,CallResultsWork.delayMs(kst("2026-11-04T10:31:00"),1,true))
        assertNull("nothing waits: nothing is registered",CallResultsWork.delayMs(kst("2026-11-04T10:31:00"),1,false))
        assertNotNull(CallResultsWork.delayMs(kst("2026-11-04T10:31:00"),3,true))
        assertNull("three tries at most",CallResultsWork.delayMs(kst("2026-11-04T10:31:00"),4,true))
    }
    @Test fun aPassSchedulesTheResultPassOnlyWhenItSentSomething() {
        var scheduled=0
        val quiet=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true) },{ now },FakeInbox(),afterSend={ scheduled++ })
        assertTrue(quiet.runPending());assertEquals(0,scheduled);assertEquals(0,quiet.sent)
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,125_000,"김과장",text,listOf(CallTranscript.Segment(0,5000,text)),"m","b".repeat(64),true)
        val id=CallRecordStore(context){ now }.use { it.insert(t);CallRecordStore.recordId(t) }
        val inbox=FakeInbox()
        val busy=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true) },{ now },inbox,afterSend={ scheduled++ })
        assertTrue(busy.runPending());assertEquals(1,scheduled);assertEquals(1,busy.sent)
        assertTrue(CallRecordStore(context){ now }.use { it.awaitingResult() })
        CallRecordStore(context){ now }.use { it.setReceived(id,now) }
        assertFalse(CallRecordStore(context){ now }.use { it.awaitingResult() })
    }

    // ---- v2.4 #9: If-None-Match on what every pass reads -------------------------------------------------------------
    @Test fun unchangedListingIsNotDownloadedAgain() {
        InboxETags.clear()
        val seen=mutableListOf<String?>()
        var listing="[{\"type\":\"file\",\"name\":\"a.json\",\"sha\":\"s1\"}]";var etag="W/\"e1\""
        val server=okhttp3.Interceptor { chain -> val r=chain.request();seen+=r.header("If-None-Match")
            val b=okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).header("ETag",etag)
            if(r.header("If-None-Match")==etag) b.code(304).message("Not Modified").body(ByteArray(0).toResponseBody(null)).build()
            else b.code(200).message("OK").body(listing.toResponseBody(null)).build() }
        val client=okhttp3.OkHttpClient.Builder().addInterceptor(InboxETags).addInterceptor(server).build()
        val store=GitHubInboxStore("o/r","t",client)
        assertEquals(listOf("a.json" to "s1"),store.list(CallInbox.RESULTS))
        assertEquals(listOf("a.json" to "s1"),store.list(CallInbox.RESULTS)) // 304: the kept copy
        listing="[]";etag="W/\"e2\""
        assertEquals(emptyList<Pair<String,String>>(),store.list(CallInbox.RESULTS))
        assertEquals(listOf(null,"W/\"e1\"","W/\"e1\""),seen)
        // Result files themselves (read once, then deleted) are never kept.
        assertFalse(InboxETags.cacheable("/repos/o/r/contents/results/a.json"));assertTrue(InboxETags.cacheable("/repos/o/r/contents/ledger/ledger.json"))
        InboxETags.clear()
    }
}
