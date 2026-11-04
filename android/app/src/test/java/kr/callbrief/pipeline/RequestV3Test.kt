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

/** Round 4 request v3 (design C1 C3 C5 C7 E4 E5 E9 J2), on made-up data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RequestV3Test:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val started=Instant.parse("2026-11-04T01:00:00Z").toEpochMilli() // 수요일 10:00 KST
    private var now=started+3_600_000
    private fun store()=CallRecordStore(context) { now }
    private class FakeInbox:CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha-$it"}
        override fun get(path:String)=files.getValue(path)
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            (1..8).forEach { i -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)","rec$i",GeneralLocalStore.OWNER,"rec$i","rec$i.wav") }
            null } }
    }
    private val text="네 김민수 과장님, 철근 단가는 내일까지 다시 견적서 보내드릴게요. 박대리님도 같이 봐주세요."
    private fun call(i:Int,counterpart:String?="010-1111-0001",at:Long=started+i*60_000L,body:String=text):CallRecord {
        val t=CallTranscript("rec$i",1,"a".repeat(64),at,60_000,counterpart,body,listOf(CallTranscript.Segment(0,1,body)),"m","b".repeat(64),true)
        return store().use { it.insert(t);it.record(CallRecordStore.recordId(t))!! }
    }
    private fun cp(person:String?,company:String?)=ClaudeCallAnalysis.Counterpart(person,company,null,false)
    private fun analysis(cp:ClaudeCallAnalysis.Counterpart?,summary:String="요약",tasks:List<ClaudeCallAnalysis.Task> =emptyList(),business:Boolean=true)=
        ClaudeCallAnalysis(summary,emptyList(),cp?.personName,cp?.companyName,emptyList(),tasks,cp,business=business)
    private fun runner(inbox:FakeInbox,s:JSONObject)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now },inbox,save={ edit -> edit(s) },callLog={ emptyList() },smsAllowed={ false })
    private fun settings()=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)

    // ---- C1: request v3 ----------------------------------------------------------------------------------------
    @Test fun requestsCarryVersionNotRules() {
        val r=ClaudeCallAnalyzerTest.request()
        val job=JSONObject(CallInbox.job("r_1",r,now,started))
        assertEquals(setOf("format","id","recordKey","createdMs","atMs","counterpartId","promptVersion","message")+(if(CallInbox.CARRY_RULES) setOf("system","schema") else emptySet()),job.keys().asSequence().toSet())
        assertEquals(3,job.getInt("format"));assertEquals(ClaudeCallAnalyzer.PROMPT_VERSION,job.getInt("promptVersion"))
        val batch=JSONObject(CallInbox.batch(listOf("r_1" to r,"r_2" to r),now,listOf(1L,2L)))
        assertEquals(CallInbox.CARRY_RULES,batch.has("system")&&batch.has("schema"))
        val item=batch.getJSONArray("items").getJSONObject(1)
        assertEquals(setOf("id","recordKey","createdMs","atMs","counterpartId","promptVersion","message"),item.keys().asSequence().toSet())
        assertEquals(2L,item.getLong("atMs"))
        assertEquals(CallInbox.CARRY_RULES,batch.toString().contains("당신은 업무 통화를 정리해 주는 비서"))
    }

    // ---- C3: certainty ------------------------------------------------------------------------------------------
    @Test fun certaintyIsReadKeptAndShownAndFromIsGone() {
        val schemaItem=ClaudeCallAnalyzer.SCHEMA.getJSONObject("properties").getJSONObject("tasks").getJSONObject("items").getJSONObject("properties")
        assertEquals(listOf("sure","tentative"),schemaItem.getJSONObject("certainty").getJSONArray("enum").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertFalse(schemaItem.has("from"))
        // An old result with "from" and no certainty still reads, as sure.
        val old=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray().put(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지").put("from","transcript"))))
        assertFalse(old.tasks.single().tentative)
        val a=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray().put(ClaudeCallAnalyzerTest.task("create",null,"단가 회신 확인","철근 단가는 내일까지").put("certainty","tentative"))))
        assertTrue(a.tasks.single().tentative)
        assertTrue(ClaudeCallAnalysis.parse(a.toJson()).tasks.single().tentative)
        val r=call(1)
        store().use { db ->
            db.applyAnalysis(r.recordId,analysis(cp("김민수","△△철강"),tasks=a.tasks+ClaudeCallAnalysis.Task("create",null,"견적서 다시 보내기","2026-11-05","다시 견적서 보내드릴게요")))
            assertEquals(mapOf("단가 회신 확인" to true,"견적서 다시 보내기" to false),db.openItems().associate { it.title to it.tentative })
            assertEquals(mapOf("단가 회신 확인" to true,"견적서 다시 보내기" to false),db.openTodos().associate { it.title to it.tentative })
            assertTrue(db.dueItems(LocalDate.of(2026,11,30)).single().title=="견적서 다시 보내기")
        }
    }

    // ---- C5: message body ---------------------------------------------------------------------------------------
    @Test fun calendarTermsTradesAndUserLine() {
        val cal=ClaudeCallAnalyzer.calendar(LocalDate.of(2026,11,4))
        assertTrue(cal.startsWith("11/4(수, 통화일) 11/5(목)"));assertTrue(cal.endsWith("11/17(화)"));assertEquals(14,Regex("\\d+/\\d+\\(").findAll(cal).count())
        assertTrue(ClaudeCallAnalyzer.calendar(LocalDate.of(2026,12,20)).contains("12/25(금, 공휴일)"))
        assertTrue(ClaudeCallAnalyzer.calendar(LocalDate.of(2027,7,15)).contains("7/19(월, 공휴일)"))
        assertTrue(ClaudeCallAnalyzer.TERMS.size in 28..36)
        val m=ClaudeCallAnalyzer.userMessage(ClaudeCallAnalyzerTest.request())
        assertTrue(m.contains("[달력] 10/29(목, 통화일)"));assertTrue(m.contains("[용어] 타설, 양생"));assertTrue(m.contains("[공종 목록] 철근, 형틀"))
        assertTrue(m.startsWith("[사용자] 건설사 직원(부장)"))
        val mine=ClaudeCallAnalyzer.userMessage(ClaudeCallAnalyzerTest.request().copy(user=ClaudeCallRequest.UserInfo("홍길동","가나건설 다라현장","부장")))
        assertTrue(mine.startsWith("[사용자] 이름 홍길동, 가나건설 다라현장, 부장. 건설사 직원"))
        // The call log's real time decides the calendar.
        val logged=ClaudeCallAnalyzerTest.request().copy(callLog=ClaudeCallRequest.CallLogView(null,"건 전화","2026-11-02 09:00 (월)",60_000))
        assertTrue(ClaudeCallAnalyzer.userMessage(logged).contains("[달력] 11/2(월, 통화일)"))
    }

    @Test fun myInfoFromSettingsReachesTheRequest() {
        call(1)
        val inbox=FakeInbox();val s=settings().put(PipelineSettingsKeys.MY_NAME,"홍길동").put(PipelineSettingsKeys.MY_ROLE,"부장")
        assertTrue(runner(inbox,s).runPending())
        val job=JSONObject(inbox.files.values.single())
        assertTrue(job.getString("message").startsWith("[사용자] 이름 홍길동, 부장."))
    }

    // ---- J2: numbers masked -------------------------------------------------------------------------------------
    @Test fun phoneAndAccountNumbersAreMaskedAndQuotesStillMatch() {
        val said="계좌는 110-123-456789 이고 연락처는 010-1234-5678, 사무실은 02 1234 5678 9 입니다. 3공구 철근 15톤은 2026-11-06에 들어옵니다. 01098765432로 문자 주세요."
        val masked=ClaudeCallAnalyzer.maskNumbers(said)
        listOf("110-123-456789","010-1234-5678","01098765432","1234 5678").forEach { assertFalse(it,masked.contains(it)) }
        assertTrue(masked.contains("3공구 철근 15톤은 2026-11-06에"));assertEquals(4,Regex("\\[번호]").findAll(masked).count())
        val r=ClaudeCallAnalyzerTest.request(transcript=said,texts="[11/3 문자]\n입금 계좌 333-22-1111 확인 부탁드립니다")
        val m=ClaudeCallAnalyzer.userMessage(r)
        assertFalse(m.contains("010-1234-5678"));assertFalse(m.contains("333-22-1111"));assertTrue(m.contains("입금 계좌 [번호] 확인"))
        // The model copies what it was sent: the quote with [번호] is found in the masked text.
        val a=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray().put(ClaudeCallAnalyzerTest.task("create",null,"문자 보내기","[번호]로 문자 주세요"))
            .put(ClaudeCallAnalyzerTest.task("create",null,"입금 확인","입금 계좌 [번호] 확인 부탁드립니다"))))
        assertEquals(2,ClaudeCallAnalyzer().filter(a,r).tasks.size)
    }

    // ---- C7: first pass steps back ------------------------------------------------------------------------------
    @Test fun batchSlotsStopWhenResultsStopAndCapADay() {
        val inbox=FakeInbox();val today=Instant.ofEpochMilli(now).atZone(CallRecordStore.KST).toLocalDate().toString()
        val r=runner(inbox,settings())
        assertEquals(2,r.batchSlots(settings(),null,2))
        // Something sent 7 hours ago is still unanswered and no result came for 7 hours: nothing new goes up.
        assertEquals(0,r.batchSlots(settings().put(PipelineSettingsKeys.LAST_ANALYZED_MS,now-7*3_600_000L),now-7*3_600_000L,2))
        // A result came an hour ago: the routine works.
        assertEquals(2,r.batchSlots(settings().put(PipelineSettingsKeys.LAST_ANALYZED_MS,now-3_600_000L),now-7*3_600_000L,2))
        // Day cap of four.
        assertEquals(1,r.batchSlots(settings().put(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY,today).put(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,3),null,2))
        assertEquals(0,r.batchSlots(settings().put(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY,today).put(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,4),null,2))
        assertEquals(2,r.batchSlots(settings().put(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY,"2026-11-01").put(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,4),null,2))
    }

    @Test fun firstPassCountsItsBatchesPerDay() {
        (1..8).forEach { call(it,at=started-2*86_400_000L+it*60_000L) }
        val s=settings().put(PipelineSettingsKeys.FIRST_PASS_START_MS,started-86_400_000L)
        val inbox=FakeInbox()
        assertTrue(runner(inbox,s).runPending())
        assertEquals(1,inbox.files.keys.count { it.startsWith("inbox/b-") })
        assertEquals(1,s.getInt(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY))
    }

    // ---- E4: same person, not same key --------------------------------------------------------------------------
    @Test fun samePersonHistoryJoinsNameAndNumberCalls() {
        val byName=call(1,"김민수")
        store().use { it.applyAnalysis(byName.recordId,analysis(cp("김민수",null),"이름으로 저장된 통화")) }
        val byNumber=call(2,"010-2222-0002")
        store().use { it.applyAnalysis(byNumber.recordId,analysis(cp("김민수",null),"번호로 저장된 통화")) }
        val next=call(3,"010-2222-0002")
        val req=store().use { it.request(next) }
        assertEquals(listOf("번호로 저장된 통화","이름으로 저장된 통화"),req.previousSummaries)
    }

    // ---- E5: duplicates counted ---------------------------------------------------------------------------------
    @Test fun skippedDuplicatesAreCounted() {
        val first=call(1)
        store().use { it.applyAnalysis(first.recordId,analysis(cp("김민수","△△철강"),tasks=listOf(ClaudeCallAnalysis.Task("create",null,"철근 견적서 보내기","2026-11-05","다시 견적서 보내드릴게요")))) }
        val second=call(2)
        val inbox=FakeInbox();val s=settings()
        runner(inbox,s).runPending();inbox.files.clear()
        val a=ClaudeCallAnalyzerTest.analysis(JSONArray().put(ClaudeCallAnalyzerTest.task("create",null,"철근 견적서 보내기","다시 견적서 보내드릴게요")))
        inbox.files["results/x.json"]=JSONObject().put("id",CallInbox.recordKey(second.recordId)).put("analysis",a).toString()
        assertTrue(runner(inbox,s).runPending())
        assertEquals(1L,s.optLong(PipelineSettingsKeys.DUPLICATE_SKIPPED))
        assertEquals(1,store().use { it.openItems().size })
    }

    // ---- E9: personal numbers -----------------------------------------------------------------------------------
    @Test fun aNumberFoundPersonalIsNotSentAgain() {
        val first=call(1,"010-7777-0007");val again=call(5,"010-7777-0007")
        store().use { it.applyAnalysis(first.recordId,analysis(null,"개인 내용",business=false));it.applyAnalysis(again.recordId,analysis(null,"개인 내용",business=false)) }
        assertEquals(listOf("01077770007"),store().use { it.personalNumbers().map { p -> p.first } })
        val second=call(2,"010-7777-0007");val other=call(3,"010-1111-0001")
        store().use { db -> assertTrue(db.insertMessage("sms:01077770007:9",started+200_000L,"010-7777-0007","[11/4 10:03 받은 문자]\n저녁 뭐 먹을까")) }
        val inbox=FakeInbox()
        assertTrue(runner(inbox,settings()).runPending())
        assertEquals("only the business call goes",1,inbox.files.size)
        assertTrue(inbox.files.values.single().contains(CallInbox.recordKey(other.recordId)))
        store().use { db ->
            val kept=db.record(second.recordId)!!
            assertEquals(CallRecordStore.ANALYZED,kept.state);assertEquals(false,kept.analysis?.business);assertEquals("",kept.transcript)
            assertTrue(db.pending().none { it.recordingId.startsWith("sms:") })
            assertEquals("the personal text is gone",0,db.counts().values.sum()-4)
            assertTrue(db.day(LocalDate.of(2026,11,4)).none { it.recordId==second.recordId })
            // Taken back: the next call of that number goes up again.
            db.forgetPersonal("01077770007");assertTrue(db.personalNumbers().isEmpty())
        }
        val third=call(4,"010-7777-0007")
        runner(inbox,settings()).runPending()
        assertTrue(inbox.files.values.any { it.contains(CallInbox.recordKey(third.recordId)) })
    }

    // ---- E9 safety (round 6): two personal verdicts in a row, and no tie to work ---------------------------------------
    private fun personal(r:CallRecord)=store().use { it.applyAnalysis(r.recordId,analysis(null,"개인 내용",business=false)) }
    private fun remembered()=store().use { it.personalNumbers().map { p -> p.first } }
    @Test fun oneVerdictIsNotRemembered() {
        personal(call(1,"010-7777-0007"))
        assertTrue(remembered().isEmpty());assertFalse(store().use { it.isPersonal("01077770007") })
        assertEquals(1,store().use { it.personalStreak("01077770007") })
    }
    @Test fun twoVerdictsInARowAreRemembered() {
        personal(call(1,"010-7777-0007"));personal(call(2,"010-7777-0007"))
        assertEquals(listOf("01077770007"),remembered())
        // Taken back by the user: the run starts again, so one more verdict does not hide it at once.
        store().use { it.forgetPersonal("01077770007") };personal(call(3,"010-7777-0007"))
        assertTrue(remembered().isEmpty())
    }
    @Test fun aNumberOfACompanyIsNotRemembered() {
        val first=call(1,"010-7777-0007")
        store().use { db -> val lid=db.ledgerIdFor(first)!!
            db.applyLedger(JSONObject().put("people",JSONArray().put(JSONObject().put("id",lid).put("name","김민수").put("company","△△철강")))) }
        personal(first);personal(call(2,"010-7777-0007"))
        assertTrue("a company is on this number",remembered().isEmpty())
    }
    @Test fun aWorkCallInBetweenStartsTheRunAgain() {
        personal(call(1,"010-7777-0007"))
        store().use { it.applyAnalysis(call(2,"010-7777-0007").recordId,analysis(null,"자재 문의")) }
        assertEquals(0,store().use { it.personalStreak("01077770007") })
        personal(call(3,"010-7777-0007"))
        assertEquals(1,store().use { it.personalStreak("01077770007") })
        personal(call(4,"010-7777-0007"))
        assertTrue("an earlier work record keeps it from being remembered",remembered().isEmpty())
    }

    // ---- v2.4 #18: the request itself shows no middle dot (rule 5 bans it in answers) -----------------------------------
    @Test fun requestHeadersUseCommasNotMiddleDots() {
        val r=ClaudeCallAnalyzerTest.request().copy(person=ClaudeCallRequest.PersonCard("김민수","과장",false,"△△철강"),
            company=ClaudeCallRequest.CompanyCard("△△철강","철근","메모"))
        val m=ClaudeCallAnalyzer.userMessage(r)
        assertTrue(m,m.contains("[상대] 김민수, 과장, △△철강\n"));assertTrue(m,m.contains("[업체] △△철강, 공종 철근\n"))
        assertFalse(m.contains("·"));assertFalse(CallInbox.job("r_1",r,now,started).contains("·"))
        assertFalse(ClaudeCallAnalyzer.userMessage(r.copy(person=ClaudeCallRequest.PersonCard("박지훈",null,true,null),company=null)).contains("·"))
    }
}
