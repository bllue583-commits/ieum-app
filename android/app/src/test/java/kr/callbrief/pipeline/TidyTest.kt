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
import java.time.LocalDate

/** 새벽 정리: the nightly state the task reads, its proposal applied only to what the AI owns, and "진행 중인 일". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TidyTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=1_793_300_000_000L
    private val text=ClaudeCallAnalyzerTest.TRANSCRIPT
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('rec1',?,'g1','rec1','rec1.m4a','audio/mp4',10,0,0,1,1,1)",GeneralLocalStore.OWNER)
            null
        } }
    }
    private fun store()=CallRecordStore(context){ now }
    /** One analysed call from 010-5555-6666 with three check items. */
    private fun seed():String {
        val t=CallTranscript("rec1",1,"a".repeat(64),now,60_000,"010-5555-6666",text,emptyList(),"m","b".repeat(64),true)
        val id=CallRecordStore.recordId(t)
        store().use { db -> db.insert(t);db.ledgerIdFor(db.record(id)!!)
            db.applyAnalysis(id,ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray()
                .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"))
                .put(ClaudeCallAnalyzerTest.task("create",null,"견적 메일","견적서는 내일까지"))
                .put(ClaudeCallAnalyzerTest.task("create",null,"계약서 초안 받기","계약서 초안은 다음 주 월요일에 받기로 하죠"))))) }
        return id
    }
    private fun item(title:String)=store().use { db -> db.openItems().single { it.title==title } }

    @Test fun nightlyStateHasOpenItemsAndSummariesButNoNumbers() {
        val id=seed()
        val c=item("계약서 초안 받기")
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            p.execute("INSERT INTO workflow_item_history VALUES('h1',?,1,'{}')",c.id) } // the user changed it
        val state=store().use { it.tidyState(true,now) }
        val tasks=state.getJSONArray("tasks");assertEquals(3,tasks.length())
        val locked=(0 until tasks.length()).map { tasks.getJSONObject(it) }.associate { it.getString("title") to it.getBoolean("locked") }
        assertEquals(mapOf("견적서 보내기" to false,"견적 메일" to false,"계약서 초안 받기" to true),locked)
        val rec=state.getJSONArray("records").getJSONObject(0)
        assertEquals(CallInbox.recordKey(id),rec.getString("key"));assertTrue(rec.getString("counterpartId").startsWith("p_"))
        assertTrue(state.getBoolean("firstPassDone"))
        assertFalse(state.toString().contains("5555"));assertFalse(state.toString().contains("rec1"))
    }

    @Test fun proposalTouchesOnlyWhatTheAiOwnsAndNeverCompletes() {
        seed()
        val a=item("견적서 보내기");val b=item("견적 메일");val c=item("계약서 초안 받기")
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            p.execute("INSERT INTO workflow_item_history VALUES('h1',?,1,'{}')",c.id) }
        val n=store().use { it.applyTidy(JSONObject().put("rev",1).put("tasks",JSONArray()
            .put(JSONObject().put("op","rewrite").put("id",a.id).put("title","김과장에게 견적서 메일 보내기").put("due","2026-10-29"))
            .put(JSONObject().put("op","merge").put("id",b.id).put("into",a.id))
            .put(JSONObject().put("op","done_suggested").put("id",c.id))
            .put(JSONObject().put("op","rewrite").put("id",a.id).put("title","나쁜 날짜").put("due","금요일"))
            .put(JSONObject().put("op","done").put("id",a.id)))) }
        assertEquals(2,n)
        val open=store().use { it.openItems() }
        assertEquals(setOf("김과장에게 견적서 메일 보내기","계약서 초안 받기"),open.map { it.title }.toSet())
        assertEquals("2026-10-29",open.single { it.id==a.id }.due);assertEquals("open",open.single { it.id==a.id }.status)
        assertEquals("the user's item is left alone","open",open.single { it.id==c.id }.status)
        // The merged one is gone from every list, the morning report included.
        assertTrue(store().use { it.dueItems(LocalDate.of(2026,10,30)) }.none { it.id==b.id })
    }

    @Test fun ledgerIssuesAndMergesReachTheScreensAndReport() {
        seed()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('a','김민슈',NULL,NULL,NULL,0,0,'p_a')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('b','김민수','01011112222',NULL,NULL,0,0,'p_b')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('c','박재웅','01033334444',NULL,NULL,0,0,'p_c')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('d','박재옹','01055550000',NULL,NULL,0,0,'p_d')")
            p.execute("INSERT INTO call_links VALUES('x','a',NULL)");null
        } }
        store().use { db ->
            db.applyLedger(JSONObject().put("rev",1).put("companies",JSONArray().put(JSONObject().put("name","△△철강")).put(JSONObject().put("name","△△ 철강 주식회사"))))
            db.applyLedger(JSONObject().put("rev",2)
                .put("companies",JSONArray().put(JSONObject().put("name","△△ 철강 주식회사").put("mergedInto","△△철강")))
                .put("people",JSONArray().put(JSONObject().put("id","p_a").put("mergedInto","p_b")).put(JSONObject().put("id","p_c").put("mergedInto","p_d")))
                .put("issues",JSONArray().put(JSONObject().put("id","i_1").put("title","철근 단가 재견적").put("status","업체 회신 대기, 금요일 계약 예정").put("companies",JSONArray().put("△△철강")))
                    .put(JSONObject().put("id","i_2").put("title","분전반 점검").put("status","").put("companies",JSONArray()))))
            assertEquals(listOf("△△철강"),db.companies().map { it.name })
            val names=db.peopleOf(null).map { it.name }.toSet()
            assertFalse("a name-only duplicate joins the person with the number","김민슈" in names);assertTrue("김민수" in names)
            assertTrue("different numbers are never merged",names.containsAll(listOf("박재웅","박재옹")))
            assertEquals(listOf("철근 단가 재견적"),db.companyDetail(db.companies().single().id)!!.issues.map { it.title })
            assertEquals(2,db.issues().size)
        }
        val m=CallDigestText.morning(emptyList(),LocalDate.of(2026,10,29),store().use { it.issues() })
        assertEquals("▸ 철근 단가 재견적: 업체 회신 대기, 금요일 계약 예정",m.lines.first());assertEquals("▸ 분전반 점검",m.lines[1])
        assertEquals("오늘까지 체크할 일이 없어요",m.text)
        assertFalse(m.lines.any { it.contains("·") })
    }

    @Test fun requestsCarryTheRecordKey() {
        val r=ClaudeCallAnalyzerTest.request()
        val job=JSONObject(CallInbox.job("rec|1|x",r,now))
        assertEquals(CallInbox.recordKey("rec|1|x"),job.getString("recordKey"));assertTrue(job.getString("recordKey").startsWith("r_"))
        val batch=JSONObject(CallInbox.batch(listOf("rec|1|x" to r),now))
        assertEquals(job.getString("recordKey"),batch.getJSONArray("items").getJSONObject(0).getString("recordKey"))
    }
}
