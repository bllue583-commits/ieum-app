package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
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
import java.time.Instant
import java.time.LocalDate

/** Production protected SQLite on generated rows. Fake HTTP; no network or real recordings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallRecordStoreTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val started=Instant.parse("2026-10-29T01:00:00Z").toEpochMilli() // 10:00 KST
    private var now=started+3_600_000
    private val clock={ now }

    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2").forEach { id ->
                p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
                    id,GeneralLocalStore.OWNER,id,"$id.wav")
            }
            null
        } }
    }
    private fun transcript(recording:String="rec1",text:String=ClaudeCallAnalyzerTest.TRANSCRIPT,counterpart:String?="010-1234-5678",startedMs:Long?=started)=
        CallTranscript(recording,1,"a".repeat(64),startedMs,125_000,counterpart,text,listOf(CallTranscript.Segment(0,5000,text)),"ggml-large-v3-turbo-q5_0","b".repeat(64),true)
    private fun store()=CallRecordStore(context,clock)
    private fun item(id:String)=port().use { it.query("SELECT * FROM workflow_items WHERE id=?",id).single() }
    private fun board()=port().use { (it.query("SELECT revision FROM board_state WHERE owner_key=?",GeneralLocalStore.OWNER).single()["revision"] as Number).toLong() }
    private class Fake(var next:()->ClaudeCallResponse):ClaudeCallHttp {
        val bodies=mutableListOf<String>()
        override fun post(apiKey:String,body:String):ClaudeCallResponse { bodies+=body;return next() }
    }
    private val consented=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.CLAUDE_API_KEY,"sk-test")
    private fun runner(fake:Fake,settings:()->JSONObject={ consented })=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(fake) {},settings,clock)
    private fun reply(vararg tasks:JSONObject)=ClaudeCallAnalyzerTest.reply(ClaudeCallAnalyzerTest.analysis(JSONArray(tasks.toList())))
    private val quote="견적서는 내일까지 메일로 보내드릴게요"

    @Test fun insertIsIdempotentAndSinkStores() {
        var enqueued=0
        val sink=CallSinkImpl(context) { enqueued++ }
        CallTranscriptSinks.factory={ ctx -> CallSinkImpl(ctx) { enqueued++ } }
        sink.onTranscript(transcript());sink.onTranscript(transcript())
        assertEquals(2,enqueued)
        store().use { s ->
            val day=s.day(LocalDate.of(2026,10,29));assertEquals(1,day.size)
            assertEquals("rec1|1|"+"a".repeat(64),day.single().recordId);assertEquals("01012345678",day.single().counterpartKey)
            assertEquals(CallRecordStore.TRANSCRIBED,day.single().state)
            assertFalse(s.insert(transcript()))
        }
        assertEquals("홍길동",CallRecordStore.counterpartKey("  홍길동 "));assertNull(CallRecordStore.counterpartKey(" "))
    }

    @Test fun createdTaskIsVisibleInWorkflowItemsAndRerunIsIdempotent() {
        store().use { it.insert(transcript()) }
        val before=board()
        val fake=Fake { reply(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기",quote,"2026-10-30")) }
        assertTrue(runner(fake).runPending())
        val record=store().use { it.day(LocalDate.of(2026,10,29)).single() }
        assertEquals(CallRecordStore.ANALYZED,record.state);assertEquals("김과장",record.displayName)
        val created=record.applied.single();assertTrue(created.applied)
        val row=item(created.itemId)
        assertEquals("TODO",row["kind"]);assertEquals("open",row["status"]);assertEquals("2026-10-30",row["due_text"]);assertEquals(quote,row["evidence_quote"]);assertEquals("rec1",row["recording_id"])
        assertNull(row["case_id"]);assertEquals(before+1,board())
        assertTrue(runner(fake).runPending());assertEquals(1,fake.bodies.size) // already analyzed: nothing sent again
        store().use { s -> s.insert(transcript());assertEquals(1,port().use { it.query("SELECT COUNT(*) AS n FROM workflow_items").single()["n"] as Number }.toInt()) }
    }

    @Test fun completingAnItemMarksItDoneOnceAndProtectsItFromLaterAnalysis() {
        store().use { it.insert(transcript()) }
        runner(Fake { reply(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기",quote)) }).runPending()
        val a=store().use { it.day(LocalDate.of(2026,10,29)).single().applied.single().itemId }
        val before=board()
        store().use { it.completeItem(a,1) }
        assertEquals("done",item(a)["status"]);assertEquals(2L,item(a)["revision"]);assertEquals(before+1,board())
        assertEquals(1,port().use { it.query("SELECT * FROM workflow_item_history WHERE item_id=?",a).size })
        assertTrue(store().use { it.openItems() }.none { it.id==a })
        try { store().use { it.completeItem(a,1) };fail("stale revision must be rejected") } catch(e:IllegalArgumentException) { assertEquals("ITEM_REVISION_CONFLICT",e.message) }
        assertEquals(2L,item(a)["revision"])
    }

    @Test fun updateOfAiOwnedItemAppliesButUserEditedAndDoneItemsAreUntouched() {
        store().use { it.insert(transcript()) }
        runner(Fake { reply(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기",quote),ClaudeCallAnalyzerTest.task("create",null,"계약서 받기","계약서 초안은")) }).runPending()
        val (a,b)=store().use { it.day(LocalDate.of(2026,10,29)).single().applied.map { it.itemId } }
        // A user edit is recorded in workflow_item_history, like RecordingWorkflowStore.editItem does.
        port().use { p -> p.transaction {
            p.execute("UPDATE workflow_items SET title='사용자가 고친 제목',revision=2 WHERE id=?",b)
            p.execute("INSERT INTO workflow_item_history VALUES('ev1',?,2,'{}')",b);null
        } }
        now+=86_400_000
        store().use { it.insert(transcript("rec2",startedMs=started+86_400_000)) }
        val fake=Fake { reply(ClaudeCallAnalyzerTest.task("update",a,"견적서 최종본 보내기",quote,"2026-10-31"),ClaudeCallAnalyzerTest.task("update",b,"AI 제목","계약서 초안은")) }
        runner(fake).runPending()
        assertTrue(fake.bodies.single().contains(a)) // open tasks with the same counterpart are offered to the model
        assertEquals("견적서 최종본 보내기",item(a)["title"]);assertEquals("2026-10-31",item(a)["due_text"]);assertEquals(2L,item(a)["revision"])
        assertEquals("사용자가 고친 제목",item(b)["title"])
        val applied=store().use { it.day(LocalDate.of(2026,10,30)).single().applied };assertEquals(listOf(true,false),applied.map { it.applied })

        // Done by the user (directly) -> not offered and never changed.
        port().use { it.execute("UPDATE workflow_items SET status='done' WHERE id=?",a) }
        val req=store().use { s -> s.request(s.day(LocalDate.of(2026,10,30)).single()) };assertFalse(req.openTasks.any { it.id==a })
        val analysis=ClaudeCallAnalysis("s",emptyList(),null,null,emptyList(),listOf(ClaudeCallAnalysis.Task("update",a,"바뀌면 안 됨",null,quote)))
        store().use { s -> s.applyAnalysis(s.day(LocalDate.of(2026,10,30)).single().recordId,analysis) }
        assertEquals("견적서 최종본 보내기",item(a)["title"]);assertEquals("done",item(a)["status"])
    }

    @Test fun doneSuggestedBecomesNeedsConfirmationNeverDone() {
        store().use { it.insert(transcript()) }
        runner(Fake { reply(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기",quote)) }).runPending()
        val id=store().use { it.day(LocalDate.of(2026,10,29)).single().applied.single().itemId }
        store().use { it.insert(transcript("rec2")) }
        runner(Fake { reply(ClaudeCallAnalyzerTest.task("done_suggested",id,"견적서 보내기",quote)) }).runPending()
        assertEquals("needs_confirmation",item(id)["status"])
    }

    @Test fun waitingKeyAndConsentThenSuccessAfterSettingsChange() {
        store().use { it.insert(transcript()) }
        var s=JSONObject()
        val fake=Fake { reply() }
        runner(fake) { s }.runPending()
        assertEquals(CallRecordStore.WAITING_CONSENT,store().use { it.pending().single().state })
        s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true);runner(fake) { s }.runPending()
        assertEquals(CallRecordStore.WAITING_KEY,store().use { it.pending().single().state })
        assertTrue(fake.bodies.isEmpty())
        s=consented;runner(fake) { s }.runPending()
        assertEquals(1,fake.bodies.size);assertTrue(store().use { it.pending().isEmpty() })
        assertEquals(CallRecordStore.ANALYZED,store().use { it.day(LocalDate.of(2026,10,29)).single().state })
    }

    @Test fun clientErrorFailsAndRequeueRetries() {
        store().use { it.insert(transcript()) }
        var response=ClaudeCallResponse(400,"{}")
        val fake=Fake { response }
        runner(fake).runPending()
        val failed=store().use { it.day(LocalDate.of(2026,10,29)).single() };assertEquals(CallRecordStore.FAILED,failed.state);assertEquals("HTTP_400",failed.error)
        response=reply();assertEquals(1,store().use { it.requeueAll() });runner(fake).runPending()
        assertEquals(CallRecordStore.ANALYZED,store().use { it.day(LocalDate.of(2026,10,29)).single().state })
    }

    @Test fun networkFailureAsksForRetryAndKeepsRecordQueued() {
        store().use { it.insert(transcript()) }
        assertFalse(runner(Fake { throw java.io.IOException("offline") }).runPending())
        assertEquals(CallRecordStore.TRANSCRIBED,store().use { it.pending().single().state })
    }
}
