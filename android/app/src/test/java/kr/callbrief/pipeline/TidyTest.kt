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

    // ---- v2.6 (문제 12): the state carries each item's rev, a proposal on an item changed since is skipped -----------------
    @Test fun stateCarriesRevAndMergeCandidates() {
        seed()
        val a=item("견적서 보내기");val b=item("견적 메일")
        val plain=store().use { it.tidyState(true,now) }
        val revs=(0 until plain.getJSONArray("tasks").length()).map { plain.getJSONArray("tasks").getJSONObject(it) }.associate { it.getString("id") to it.getLong("rev") }
        assertEquals(3,revs.size);assertTrue(revs.values.all { it>=1 })
        val now0=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            p.query("SELECT revision FROM workflow_items WHERE id=?",a.id).single()["revision"].toString().toLong() }
        assertEquals("rev is workflow_items.revision",now0,revs.getValue(a.id))
        assertFalse("no candidates, no key",plain.has("mergeCandidates"))
        // The settings list (r7-4 fills it) goes up as it is; an empty one is left out.
        val pairs=JSONArray().put(JSONArray().put(a.id).put(b.id))
        val s=JSONObject().put(MERGE_CANDIDATES,pairs.toString())
        val state=store().use { it.tidyState(true,now,mergeCandidates=mergeCandidates(s)) }
        assertEquals(pairs.toString(),state.getJSONArray("mergeCandidates").toString())
        assertNull(mergeCandidates(JSONObject().put(MERGE_CANDIDATES,JSONArray())));assertNull(mergeCandidates(JSONObject()))
        assertEquals(pairs.toString(),mergeCandidates(JSONObject().put(MERGE_CANDIDATES,pairs)).toString())
    }

    @Test fun proposalWithAnOldRevIsSkipped() {
        seed()
        val a=item("견적서 보내기");val b=item("견적 메일");val c=item("계약서 초안 받기")
        val state=store().use { it.tidyState(true,now) }
        val rev=(0 until state.getJSONArray("tasks").length()).map { state.getJSONArray("tasks").getJSONObject(it) }.associate { it.getString("id") to it.getLong("rev") }
        // A call that night changes "견적서 보내기" by analysis: still AI-owned, but newer than the state the proposal read.
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("UPDATE workflow_items SET due_text='2026-11-06',revision=revision+1 WHERE id=?",a.id)
            p.execute("UPDATE call_task_bindings SET last_revision=last_revision+1 WHERE item_id=?",a.id);null } }
        val n=store().use { it.applyTidy(JSONObject().put("rev",1).put("tasks",JSONArray()
            .put(JSONObject().put("op","rewrite").put("id",a.id).put("rev",rev.getValue(a.id)).put("title","옛 판단").put("due","2026-10-29"))
            .put(JSONObject().put("op","merge").put("id",a.id).put("rev",rev.getValue(a.id)).put("into",c.id))
            .put(JSONObject().put("op","done_suggested").put("id",a.id).put("rev",rev.getValue(a.id)))
            .put(JSONObject().put("op","rewrite").put("id",b.id).put("rev",rev.getValue(b.id)).put("title","견적서 메일 보내기"))
            .put(JSONObject().put("op","done_suggested").put("id",c.id).put("rev",rev.getValue(c.id)+5))
            .put(JSONObject().put("op","create").put("title","철근 반입 일정 회신 확인").put("basis","10/28 통화")))) }
        assertEquals("the current rewrite and the follow-up only",2,n)
        val open=store().use { it.openItems() }
        val kept=open.single { it.id==a.id }
        assertEquals("견적서 보내기",kept.title);assertEquals("2026-11-06",kept.due);assertEquals("open",kept.status)
        assertEquals("견적서 메일 보내기",open.single { it.id==b.id }.title)
        assertEquals("open",open.single { it.id==c.id }.status)
        assertTrue(open.any { it.title=="철근 반입 일정 회신 확인" })
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
        assertEquals("진행 중, 철근 단가 재견적: 업체 회신 대기, 금요일 계약 예정",m.lines.first());assertEquals("진행 중, 분전반 점검",m.lines[1])
        assertEquals("체크할 일이 없어요",m.text)
        assertFalse(m.lines.any { it.contains("·") })
    }

    @Test fun theNightlyStateGoesUpOnceTheFirstPassIsOver() {
        seed()
        val files=LinkedHashMap<String,String>()
        val inbox=object:CallInboxStore {
            override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
            override fun replace(path:String,json:String){ files[path]=json }
            override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
            override fun get(path:String)=files[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
            override fun delete(path:String,sha:String){ files.remove(path) }
        }
        val night=java.time.LocalDate.of(2026,10,28).atTime(22,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-86_400_000L)
        fun run(at:Long)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false }).runPending()
        run(night);assertFalse("not before the first pass ends",files.containsKey(CallInbox.TIDY_STATE))
        s.put(PipelineSettingsKeys.FIRST_PASS_DONE,true)
        run(night-10*3_600_000L);assertFalse("daytime",files.containsKey(CallInbox.TIDY_STATE))
        run(night);assertTrue(files.containsKey(CallInbox.TIDY_STATE))
        files[CallInbox.TIDY_PROPOSAL]=JSONObject().put("rev",1).put("tasks",JSONArray().put(JSONObject().put("op","done_suggested").put("id",item("견적서 보내기").id))).toString()
        run(night+3_600_000L)
        assertEquals("needs_confirmation",item("견적서 보내기").status);assertEquals(1L,s.getLong(PipelineSettingsKeys.TIDY_REV))
    }

    @Test fun theSameNightlyStateIsNotSentAgain() {
        seed()
        val files=LinkedHashMap<String,String>();var writes=0
        val inbox=object:CallInboxStore {
            override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
            override fun replace(path:String,json:String){ files[path]=json;writes++ }
            override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
            override fun get(path:String)=files[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
            override fun delete(path:String,sha:String){ files.remove(path) }
        }
        val night=java.time.LocalDate.of(2026,10,28).atTime(22,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-86_400_000L).put(PipelineSettingsKeys.FIRST_PASS_DONE,true)
        fun run(at:Long)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false }).runPending()
        run(night);assertEquals(1,writes)
        val first=JSONObject(files.getValue(CallInbox.TIDY_STATE))
        assertEquals(night,first.getLong("generatedMs"));assertEquals(tidyHash(first),s.getString(TIDY_STATE_HASH))
        // Next night nothing changed: only the time stamp would differ, so nothing goes up and the old state ages past a day.
        run(night+86_400_000L);assertEquals("same content, not sent again",1,writes)
        assertEquals(night,JSONObject(files.getValue(CallInbox.TIDY_STATE)).getLong("generatedMs"));assertEquals(night,s.getLong(PipelineSettingsKeys.TIDY_SENT_MS))
        assertEquals("the time stamp alone does not change the hash",tidyHash(first),tidyHash(JSONObject(first.toString()).put("generatedMs",night+1)))
        // The user changes an item: the content differs and the next night it goes up again.
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            p.execute("INSERT INTO workflow_item_history VALUES('h1',?,1,'{}')",item("계약서 초안 받기").id) }
        run(night+2*86_400_000L);assertEquals(2,writes)
        assertEquals(night+2*86_400_000L,JSONObject(files.getValue(CallInbox.TIDY_STATE)).getLong("generatedMs"))
    }

    @Test fun requestsCarryTheRecordKey() {
        val r=ClaudeCallAnalyzerTest.request()
        val key=CallInbox.recordKey("sms:01012345678:7")
        val job=JSONObject(CallInbox.job(key,r,now))
        assertEquals(key,job.getString("id"));assertEquals(key,job.getString("recordKey"));assertTrue(key.startsWith("r_"))
        assertFalse(job.toString().contains("01012345678"))
        val batch=JSONObject(CallInbox.batch(listOf(key to r),now))
        assertEquals(job.getString("recordKey"),batch.getJSONArray("items").getJSONObject(0).getString("recordKey"))
    }

    // ---- v2.4 #25: the merge guard reads people_phones, where new people keep their numbers -------------------------
    @Test fun peopleWithDifferentNumbersAreNotMergedEvenWhenOnlyPeoplePhonesHasThem() {
        store().close() // the ledger tables
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            // As addNumber leaves new people: people.phone_key empty, the number in people_phones only.
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('e','이정민',NULL,NULL,NULL,0,0,'p_e')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('f','이정민',NULL,NULL,NULL,0,0,'p_f')")
            p.execute("INSERT INTO people_phones VALUES('01066660001','e')");p.execute("INSERT INTO people_phones VALUES('01066660002','f')")
            // One with a number and one without: the same person, merged.
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('g','최유진',NULL,NULL,NULL,0,0,'p_g')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,ledger_id) VALUES('h','최유진',NULL,NULL,NULL,0,0,'p_h')")
            p.execute("INSERT INTO people_phones VALUES('01066660003','h')");null
        } }
        store().use { db -> db.applyLedger(JSONObject().put("rev",1).put("people",JSONArray()
            .put(JSONObject().put("id","p_e").put("mergedInto","p_f")).put(JSONObject().put("id","p_g").put("mergedInto","p_h")))) }
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            val ids=p.query("SELECT id FROM people WHERE id IN ('e','f','g','h') ORDER BY id").map { it["id"] }
            assertEquals("different numbers stay two people; a number-less duplicate joins",listOf("e","f","h"),ids)
            assertEquals(listOf("e"),p.query("SELECT person_id FROM people_phones WHERE phone_key='01066660001'").map { it["person_id"] })
        }
    }
}
