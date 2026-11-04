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
    private val quote="견적서는 내일까지 메일로 보내드릴게요"
    private val quote2="계약서 초안은 다음 주 월요일에 받기로 하죠"
    private fun idOf(recording:String="rec1")=CallRecordStore.recordId(transcript(recording))
    private fun taskJson(op:String,id:String?,title:String,q:String,due:String?=null)=ClaudeCallAnalyzerTest.task(op,id,title,q,due)
    /** What the scheduled task's answer goes through on the phone: the same filter against the request, then the apply. */
    private fun answer(recordId:String,vararg tasks:JSONObject):List<CallRecordStore.Applied> = store().use { s ->
        val a=ClaudeCallAnalyzer().filter(ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray(tasks.toList()))),s.request(s.record(recordId)!!),s.openTaskIds())
        s.applyAnalysis(recordId,a)
    }

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
        // The segments are no longer kept (review B20).
        assertEquals("[]",port().use { it.query("SELECT segments_json FROM call_records").single()["segments_json"] })
    }

    @Test fun createdTaskIsVisibleInWorkflowItemsAndRerunIsIdempotent() {
        store().use { it.insert(transcript()) }
        val before=board()
        val created=answer(idOf(),taskJson("create",null,"견적서 보내기",quote,"2026-10-30")).single();assertTrue(created.applied)
        val record=store().use { it.day(LocalDate.of(2026,10,29)).single() }
        assertEquals(CallRecordStore.ANALYZED,record.state);assertEquals("김과장",record.displayName)
        val row=item(created.itemId)
        assertEquals("TODO",row["kind"]);assertEquals("open",row["status"]);assertEquals("2026-10-30",row["due_text"]);assertEquals(quote,row["evidence_quote"]);assertEquals("rec1",row["recording_id"])
        assertNull(row["case_id"]);assertNull(row["done_ms"]);assertNull(row["ai_note"]);assertEquals(before+1,board())
        assertTrue(answer(idOf(),taskJson("create",null,"견적서 보내기",quote)).isEmpty()) // already analyzed: never applied twice
        store().use { s -> s.insert(transcript());assertEquals(1,port().use { it.query("SELECT COUNT(*) AS n FROM workflow_items").single()["n"] as Number }.toInt()) }
    }

    @Test fun completingAnItemMarksItDoneOnceAndProtectsItFromLaterAnalysis() {
        store().use { it.insert(transcript()) }
        val a=answer(idOf(),taskJson("create",null,"견적서 보내기",quote)).single().itemId
        val before=board()
        store().use { it.completeItem(a,1) }
        assertEquals("done",item(a)["status"]);assertEquals(2L,item(a)["revision"]);assertEquals(before+1,board())
        assertEquals(now,(item(a)["done_ms"] as Number).toLong()) // finished at, for the 14-day guard against making it again
        assertEquals(1,port().use { it.query("SELECT * FROM workflow_item_history WHERE item_id=?",a).size })
        assertTrue(store().use { it.openItems() }.none { it.id==a })
        try { store().use { it.completeItem(a,1) };fail("stale revision must be rejected") } catch(e:IllegalArgumentException) { assertEquals("ITEM_REVISION_CONFLICT",e.message) }
        assertEquals(2L,item(a)["revision"])
    }

    @Test fun updateOfAiOwnedItemAppliesButUserEditedItemOnlyGetsANoteAndDoneItemsAreUntouched() {
        store().use { it.insert(transcript()) }
        val (a,b)=answer(idOf(),taskJson("create",null,"견적서 보내기",quote),taskJson("create",null,"계약서 받기",quote2)).map { it.itemId }
        // A user edit is recorded in workflow_item_history, like RecordingWorkflowStore.editItem does.
        port().use { p -> p.transaction {
            p.execute("UPDATE workflow_items SET title='사용자가 고친 제목',revision=2 WHERE id=?",b)
            p.execute("INSERT INTO workflow_item_history VALUES('ev1',?,2,'{}')",b);null
        } }
        now+=86_400_000
        store().use { it.insert(transcript("rec2",startedMs=started+86_400_000)) }
        val rec2=idOf("rec2")
        assertTrue(store().use { s -> s.request(s.record(rec2)!!) }.openTasks.any { it.id==a }) // open tasks with the same counterpart are offered to the model
        val applied=answer(rec2,taskJson("update",a,"견적서 최종본 보내기",quote,"2026-10-31"),taskJson("done_suggested",b,"AI 제목",quote2))
        assertEquals("견적서 최종본 보내기",item(a)["title"]);assertEquals("2026-10-31",item(a)["due_text"]);assertEquals(2L,item(a)["revision"])
        // The user's item keeps title and date; one line says what the call suggests (review B12), and the model sees it as data.
        assertEquals("사용자가 고친 제목",item(b)["title"]);assertEquals(2L,item(b)["revision"])
        assertEquals("10/30 통화: 끝난 것으로 보여요",item(b)["ai_note"]);assertEquals(listOf(true,false),applied.map { it.applied });assertEquals("10/30 통화: 끝난 것으로 보여요",applied[1].note)
        assertEquals("10/30 통화: 끝난 것으로 보여요",store().use { it.openItems() }.single { it.id==b }.aiNote)
        assertEquals(listOf("10/30 통화: 끝난 것으로 보여요"),store().use { it.day(LocalDate.of(2026,10,30)).single().applied.mapNotNull { x -> x.note } })

        // Done by the user (directly) -> not offered and never changed.
        port().use { it.execute("UPDATE workflow_items SET status='done' WHERE id=?",a) }
        val req=store().use { s -> s.request(s.day(LocalDate.of(2026,10,30)).single()) };assertFalse(req.openTasks.any { it.id==a })
        val analysis=ClaudeCallAnalysis("s",emptyList(),null,null,emptyList(),listOf(ClaudeCallAnalysis.Task("update",a,"바뀌면 안 됨",null,quote)))
        store().use { s -> s.insert(transcript("rec1",startedMs=started+2*86_400_000).copy(sourceVersion=2)) }
        val third=CallRecordStore.recordId(transcript("rec1").copy(sourceVersion=2))
        store().use { s -> s.applyAnalysis(third,analysis) }
        assertEquals("견적서 최종본 보내기",item(a)["title"]);assertEquals("done",item(a)["status"])
    }

    @Test fun doneSuggestedBecomesNeedsConfirmationNeverDone() {
        store().use { it.insert(transcript()) }
        val id=answer(idOf(),taskJson("create",null,"견적서 보내기",quote)).single().itemId
        store().use { it.insert(transcript("rec2",startedMs=started+60_000)) }
        answer(idOf("rec2"),taskJson("done_suggested",id,"견적서 보내기",quote))
        assertEquals("needs_confirmation",item(id)["status"])
    }

    @Test fun anOldCallAnalysedLateDoesNotSeeOrRewriteWhatCameAfterIt() {
        // The later call (day 2) is analysed first, then the older one (day 1) arrives (review B7).
        store().use { it.insert(transcript("rec1",startedMs=started)) }
        store().use { it.insert(transcript("rec2",startedMs=started+86_400_000)) }
        val later=answer(idOf("rec2"),taskJson("create",null,"견적서 최종본 확인",quote,"2026-11-02")).single().itemId
        assertEquals(started+86_400_000,(item(later)["created_ms"] as Number).toLong())
        val req=store().use { s -> s.request(s.record(idOf("rec1"))!!) }
        assertTrue("the later item is not offered to the older call",req.knownTaskIds.isEmpty())
        // Even when the task names it anyway, the update does not apply.
        val a=ClaudeCallAnalysis("s",emptyList(),null,null,emptyList(),listOf(ClaudeCallAnalysis.Task("update",later,"되돌린 제목","2026-10-30",quote)))
        val applied=store().use { it.applyAnalysis(idOf("rec1"),a) }
        assertFalse(applied.single().applied);assertEquals("견적서 최종본 확인",item(later)["title"]);assertEquals("2026-11-02",item(later)["due_text"]);assertNull(item(later)["ai_note"])
        // Summaries and the company's calls of later days are not in the older call's request either.
        assertTrue(store().use { s -> s.request(s.record(idOf("rec1"))!!) }.previousSummaries.isEmpty())
    }

    @Test fun nearTitlesOfTheSameCompanyAreOneItemAndRecentlyFinishedOnesAreNotMadeAgain() {
        store().use { it.insert(transcript("rec1",startedMs=started)) }
        val long1="△△철강 김민수 과장 3층 철근 견적서 회신 확인"
        val first=answer(idOf(),taskJson("create",null,long1,quote)).single();assertTrue(first.applied)
        store().use { it.insert(transcript("rec2",startedMs=started+60_000)) }
        // 2-letter pieces: 0.6 and above is the same item.
        val second=answer(idOf("rec2"),taskJson("create",null,"△△철강 김민수 과장 3층 철근 견적서 회신 여부 확인",quote),taskJson("create",null,"전혀 다른 일 도면 받기",quote2))
        assertEquals(listOf(false,true),second.map { it.applied })
        // Finished 5 days ago: still blocks; 20 days ago: no longer.
        port().use { it.execute("UPDATE workflow_items SET status='done',done_ms=? WHERE id=?",now-5*86_400_000L,first.itemId) }
        store().use { it.insert(transcript("rec1",startedMs=started+120_000).copy(sourceVersion=2)) }
        val third=CallRecordStore.recordId(transcript("rec1").copy(sourceVersion=2))
        assertFalse(answer(third,taskJson("create",null,long1,quote)).single().applied)
        store().use { it.insert(transcript("rec1",startedMs=started+180_000).copy(sourceVersion=3)) }
        port().use { it.execute("UPDATE workflow_items SET done_ms=? WHERE id=?",now-20*86_400_000L,first.itemId) }
        assertTrue(answer(CallRecordStore.recordId(transcript("rec1").copy(sourceVersion=3)),taskJson("create",null,long1,quote)).single().applied)
    }

    @Test fun trimClearsWordsOfAnsweredRecordsOnlyAndShrinksOldAnalysesToTheSummary() {
        store().use { it.insert(transcript("rec1",startedMs=started)) }
        answer(idOf(),taskJson("create",null,"견적서 보내기",quote))
        store().use { it.insert(transcript("rec2",startedMs=started+60_000)) } // never answered
        store().use { it.setState(idOf("rec2"),CallRecordStore.FAILED,"X") }
        now+=15*86_400_000L
        store().use { it.trim(now) }
        assertEquals("",store().use { it.record(idOf())!! }.transcript)
        assertEquals("a failed record keeps its words",ClaudeCallAnalyzerTest.TRANSCRIPT,store().use { it.record(idOf("rec2"))!! }.transcript)
        assertTrue(store().use { it.record(idOf())!! }.analysis!!.keyPoints.isNotEmpty())
        now+=80*86_400_000L
        store().use { it.trim(now) }
        val r=store().use { it.record(idOf())!! }
        assertEquals("김과장과 견적서와 계약서 일정을 정했다.",r.summary);assertEquals("김과장",r.analysis!!.counterpartName)
        assertTrue(r.analysis!!.keyPoints.isEmpty() && r.analysis!!.tasks.isEmpty());assertTrue(r.applied.isEmpty())
    }

    @Test fun oldRowsAreMigratedKeysFilledWaitingKeyRequeuedAndSegmentsDropped() {
        store().use { it.insert(transcript()) }
        port().use { p -> p.transaction {
            p.execute("UPDATE call_records SET segments_json='[[0,5000,\"x\"]]',state='TRANSCRIBED'")
            p.execute("INSERT INTO companies(id,name,trade,memo,updated_ms) VALUES('co_a','(주)△△철강','철근','메모',5)")
            p.execute("INSERT INTO companies(id,name,trade,memo,updated_ms) VALUES('co_b','△△ 철강',NULL,'',9)")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms) VALUES('pe_a','김민수 과장','01011112222','co_a','과장',0,1)")
            p.execute("INSERT INTO person_aliases VALUES('김민서','pe_a')")
            p.execute("INSERT INTO call_links VALUES('x','pe_a','co_a')")
            p.execute("INSERT INTO task_companies VALUES('t1','co_a')");null
        } }
        port().use { p -> p.execute("UPDATE people SET name_key=NULL");p.execute("UPDATE companies SET name_key=NULL");p.execute("DELETE FROM people_phones") }
        store().use { s ->
            assertEquals(1,s.companyCount());assertEquals("김민수",port().use { it.query("SELECT name_key FROM people").single()["name_key"] })
            assertEquals("co_b",port().use { it.query("SELECT id FROM companies").single()["id"] }) // the most recently used spelling stays
            assertEquals("철근",s.companies().single().trade);assertEquals("메모",s.companyDetail("co_b")!!.memo)
            assertEquals("co_b",port().use { it.query("SELECT company_id FROM task_companies").single()["company_id"] });assertEquals("co_b",port().use { it.query("SELECT company_id FROM people").single()["company_id"] })
            assertEquals("△△철강",port().use { it.query("SELECT name_key FROM companies").single()["name_key"] })
        }
        assertEquals("01011112222",port().use { it.query("SELECT phone_key FROM people_phones").single()["phone_key"] })
        assertEquals("[]",port().use { it.query("SELECT segments_json FROM call_records").single()["segments_json"] })
    }
}
