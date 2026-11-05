package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
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
import java.time.Instant
import java.time.LocalDate

/** v3.3 r13-1 in the production protected store: segment times kept with a record and gone with its words, confusable numbers
 * of one company, the guard counts for the heartbeat, the thin card of a number only the call log knows, the short title and
 * the morning report's "확인 필요". Generated numbers and calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GuardStoreTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val started=Instant.parse("2026-11-04T01:00:00Z").toEpochMilli()
    private var now=started+3_600_000
    private val clock={ now }
    private val day=86_400_000L

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        ContactCards.shutdown();ContactCards.clearPending()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2","rec3").forEach { id ->
                p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
                    id,GeneralLocalStore.OWNER,id,"$id.wav")
            }
            null } }
    }
    @After fun tearDown() { ContactCards.shutdown();ContactCards.clearPending() }
    private val saved=JSONObject()
    private fun store()=CallRecordStore(context,clock,CallRecordStore.SettingsEdit { e -> synchronized(saved) { e(saved) } })
    private val lines=listOf("네 부장님 탄탄이엔씨입니다","삼 층 타설은 금요일로 할게요","철근은 삼십 톤 들어와요")
    private fun transcript(recording:String,at:Long,uncertain:List<String> =emptyList())=CallTranscript(recording,1,"a".repeat(64),at,125_000,"010-1234-5678",lines.joinToString("\n"),
        lines.mapIndexed { i,t -> CallTranscript.Segment(i*5_000L,i*5_000L+4_000,t,if(i==2) uncertain else emptyList()) },"ggml-large-v3-turbo-q5_0","b".repeat(64),true)
    private fun apply(s:CallRecordStore,recording:String,at:Long,vararg titles:String):String {
        val t=transcript(recording,at);s.insert(t);val id=CallRecordStore.recordId(t)
        val record=s.record(id)!!
        val a=ClaudeCallAnalysis("탄탄이엔씨와 타설 일정을 정했어요.",emptyList(),"김민수","탄탄이엔씨",emptyList(),
            titles.map { ClaudeCallAnalysis.Task("create",null,it,null,"삼 층 타설은 금요일로 할게요",owner="them") },
            counterpart=ClaudeCallAnalysis.Counterpart("김민수","탄탄이엔씨","과장",false))
        s.applyAnalysis(id,ClaudeCallAnalyzer().filter(a,s.request(record)))
        return id
    }
    private fun stats()=JSONObject(saved.optString(PipelineSettingsKeys.GUARD_STATS,"{}"))

    @Test fun segmentTimesGoToTheRequestAndLeaveWithTheWords() = store().use { s ->
        val t=transcript("rec1",started,listOf("30"));s.insert(t);val id=CallRecordStore.recordId(t)
        val times=s.request(s.record(id)!!).segmentTimes!!
        assertEquals(listOf(0L,5_000L,10_000L),times.startsMs);assertEquals(listOf("30"),times.uncertain[2])
        val raw=s.cardRows("SELECT segments_json FROM call_records WHERE record_id=?",id).single()["segments_json"] as String
        assertTrue(raw,raw.startsWith("{") && !raw.contains("타설"))
        s.applyAnalysis(id,ClaudeCallAnalysis("요약",emptyList(),null,null,emptyList(),emptyList()))
        now+=(CallRecordStore.TRANSCRIPT_KEEP_DAYS+1)*day;s.trim(now)
        assertEquals("[]",s.cardRows("SELECT segments_json FROM call_records WHERE record_id=?",id).single()["segments_json"])
        // A text has no segments: nothing kept, the guard reads lines.
        s.insertMessage("sms:t1",started,"010-1234-5678","[10:00 받은 문자] 견적서 보내드릴게요")
        assertNull(s.request(s.pending().first { it.recordingId=="sms:t1" }).segmentTimes)
    }

    @Test fun confusableNumbersOfOneCompanyGetAMemoAndCount() = store().use { s ->
        apply(s,"rec1",started,"3층 타설 확인")
        assertTrue("said as words, written as digits",s.openItems().single().let { !it.tentative && it.aiNote==null })
        // Another call two days later: "13층" said as "삼 층"? The guard marks the number, and 13/30 is no pair with 3.
        apply(s,"rec2",started+2*day,"13층 타설 확인")
        val thirteen=s.openItems().first { it.title=="13층 타설 확인" }
        assertTrue(thirteen.tentative);assertNull(thirteen.aiNote)
        apply(s,"rec3",started+3*day,"30층 타설 확인")
        val thirty=s.openItems().first { it.title=="30층 타설 확인" }
        assertTrue(thirty.tentative);assertEquals("앞의 일과 숫자가 달라요(13층/30층)",thirty.aiNote)
        assertEquals("not merged",3,s.openItems().size)
        val stored=s.record(s.openItems().first { it.title=="30층 타설 확인" }.recordId!!)!!.analysis!!.tasks.single()
        assertEquals(listOf(NumberGuard.NUMBER,NumberGuard.CONFUSABLE),stored.guard)
        val st=stats();assertEquals(2,st.optInt(NumberGuard.NUMBER));assertEquals(1,st.optInt(NumberGuard.CONFUSABLE))
        assertFalse(st.toString(),st.toString().contains("타설"))
    }

    @Test fun aNumberOnlyTheCallLogKnowsHasAThinCard() = store().use { s ->
        s.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01077778888","현장 010-7777-8888",now-3*day,60_000,1),
            FirstPass.LogEntry(2,"01077778888","현장",now-40*day,60_000,2),FirstPass.LogEntry(3,"01077778888","현장",now-2*day-3_600_000,0,3)))
        ContactCards.flushPending(s)
        val card=ContactCards.get(context,"010-7777-8888")!!
        assertTrue(card.people.isEmpty() && card.company==null && card.recent.isEmpty())
        assertEquals(ContactCard.Thin("현장",2),card.thin)
        assertFalse(card.toJson().toString().contains("7777"))
        val c=kr.callbrief.pipeline.call.CallCardView.Content.of(card,now)
        assertEquals("현장",c.name);assertTrue(c.thin)
        // v3.4 (DESIGN_V4 4.2-3 ⑤): the thin card's first line.
        assertEquals("2일 전 통화, 30일간 2번",c.first)
        // A personal number has no card at all.
        s.rememberPersonal("01077778888");ContactCards.flushPending(s)
        assertNull(ContactCards.get(context,"01077778888"))
    }

    @Test fun cardSentencesAreCutAtASpace() {
        val s=ContactCards.firstSentence("탄탄이엔씨와 삼층 타설 일정을 금요일로 정하고 철근 반입도 같은 날 오전으로 맞추기로 했어요")
        assertTrue(s,s.length<=ContactCards.FIRST_CHARS && !s.endsWith("오") && "탄탄이엔씨와 삼층 타설 일정을 금요일로 정하고 철근 반입도 같은 날 오전으로 맞추기로 했어요".startsWith(s+" "))
        assertEquals("견적서는 금요일까지 보내요.",ContactCards.firstSentence("견적서는 금요일까지 보내요. (숫자 확인 필요)"))
        assertEquals("철근 30톤 반입",ContactCards.firstSentence("철근 30톤 반입 (숫자 확인 필요)"))
    }

    @Test fun titlesLoseTheNameTheMetaLineShows() {
        assertEquals("견적서 회신 확인",CallUi.shortTitle("김민수 님께 견적서 회신 확인","김민수"))
        assertEquals("견적서 회신 확인",CallUi.shortTitle("김민수 과장에게 견적서 회신 확인","김민수"))
        assertEquals("견적서 회신 확인",CallUi.shortTitle("김민수께 견적서 회신 확인","김민수 과장"))
        assertEquals("박영희 님께 견적서 회신 확인",CallUi.shortTitle("박영희 님께 견적서 회신 확인","김민수"))
        assertEquals("김민수 과장 견적서 회신 확인",CallUi.shortTitle("김민수 과장 견적서 회신 확인","김민수"))
        assertEquals("김민수 님께",CallUi.shortTitle("김민수 님께","김민수"))
        assertEquals("견적서 보내기",CallUi.shortTitle("견적서 보내기","010-1234-5678"))
        val badge=CallUi.unsureBadge(context)
        assertEquals("확인 필요",badge.text.toString());assertEquals(18f,badge.textSize/context.resources.displayMetrics.scaledDensity,0.1f)
    }

    @Test fun theMorningReportSaysWhichItemsNeedACheck() {
        val today=LocalDate.of(2026,11,5)
        val m=CallDigestText.morning(listOf(CallTodoItem("i1","30층 타설 확인","","open",1,"탄탄이엔씨",started,null,true),
            CallTodoItem("i2","견적서 받기","","open",1,null,started+1,null,false)),today)
        assertTrue(m.lines[0],m.lines[0].startsWith(CallDigestText.NOW_PREFIX+"[탄탄이엔씨] 30층 타설 확인") && m.lines[0].endsWith(", 확인 필요"))
        assertFalse(m.lines[1],m.lines[1].contains("확인 필요"))
    }
}
