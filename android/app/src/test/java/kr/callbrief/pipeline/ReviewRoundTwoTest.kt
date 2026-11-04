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

/** Review round 2 (11/4, three reviewers): check items, apply order and retries, texts, numbers, lean requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReviewRoundTwoTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=LocalDate.of(2026,10,28).atTime(10,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val day=86_400_000L
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2").forEach { r -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',10,0,0,1,1,1)",r,GeneralLocalStore.OWNER,r) }
            null
        } }
    }
    private fun store(at:Long=now)=CallRecordStore(context){ at }
    private fun call(id:String,at:Long,cp:String?="김과장")=CallTranscript(id,1,"a".repeat(64),at,60_000,cp,ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
    private fun analysis(vararg tasks:JSONObject)=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray().apply { tasks.forEach { put(it) } }))

    @Test fun checkItemsKeepTheirDateTheCallDayAndAreNotMadeTwice() {
        val first=store().use { it.insert(call("rec1",now-2*day));CallRecordStore.recordId(call("rec1",0)) }
        store().use { it.applyAnalysis(first,analysis(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지",due="2026-10-30"))) }
        val item=store().use { it.openItems().single() }
        assertEquals("asked on the call's day, not when analysed",now-2*day,item.createdMs)
        store().use { db -> AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p ->
            p.execute("UPDATE workflow_items SET status='needs_confirmation' WHERE id=?",item.id) } }
        val second=store().use { it.insert(call("rec2",now));CallRecordStore.recordId(call("rec2",0)) }
        store().use { it.applyAnalysis(second,analysis(
            ClaudeCallAnalyzerTest.task("update",item.id,"견적서 메일로 보내기","견적서는 내일까지"),
            ClaudeCallAnalyzerTest.task("create",null,"견적서 메일로 보내기!","견적서는 내일까지"))) }
        val open=store().use { it.openItems() }
        assertEquals("the same words are not made again",1,open.size)
        assertEquals("2026-10-30",open.single().due);assertEquals("open",open.single().status)
        assertEquals("견적서 메일로 보내기",open.single().title)
    }

    @Test fun finishedItemsAreNotMadeAgainAndTidyFollowUpsCanBeTidied() {
        val id=store().use { it.insert(call("rec1",now-day));CallRecordStore.recordId(call("rec1",0)) }
        store().use { it.applyAnalysis(id,analysis(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지"))) }
        val item=store().use { it.openItems().single() }
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.execute("UPDATE workflow_items SET status='done' WHERE id=?",item.id) }
        assertEquals(listOf("견적서 보내기"),store().use { it.request(it.record(id)!!).recentlyDone })
        val again=store().use { it.insert(call("rec2",now));CallRecordStore.recordId(call("rec2",0)) }
        store().use { it.applyAnalysis(again,analysis(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지"))) }
        assertTrue("finished lately: not made again",store().use { it.openItems() }.isEmpty())
        assertTrue(ClaudeCallAnalyzer.userMessage(store().use { it.request(it.record(again)!!) }).contains("[최근 끝낸 체크할 일] 견적서 보내기"))
        store().use { it.applyTidy(JSONObject().put("tasks",JSONArray().put(JSONObject().put("op","create").put("title","계약서 초안 받았는지 확인")))) }
        val f=store().use { it.openItems().single() }
        assertFalse("a tidy follow-up is the AI's",store().use { it.tidyState(true,now) }.getJSONArray("tasks").getJSONObject(0).getBoolean("locked"))
        assertEquals(1,store().use { it.applyTidy(JSONObject().put("tasks",JSONArray().put(JSONObject().put("op","rewrite").put("id",f.id).put("title","김과장 계약서 초안 받았는지 확인")))) })
    }

    @Test fun recentTextsIncludeUnanalysedOnesWithTimeAndNamesNeverCarryNumbers() {
        store().use { db -> db.insertMessage("sms:01011112222:5",now-3_600_000L,"01011112222","[09:00 받은 문자] 견적서 오늘 보냅니다") }
        val id=store().use { it.insert(call("rec1",now,"010-1111-2222"));CallRecordStore.recordId(call("rec1",0,"010-1111-2222")) }
        val r=store().use { it.request(it.record(id)!!) }
        assertTrue(r.recentTexts,r.recentTexts.contains("10/28 09:00 문자, 아직 정리 전"));assertTrue(r.recentTexts.contains("견적서 오늘 보냅니다"))
        assertEquals("홍길동",CallRecordStore.noNumber("홍길동 010-1234-5678"));assertNull(CallRecordStore.noNumber("010-1234-5678"))
        val m=ClaudeCallAnalyzer.userMessage(r.copy(counterpart="홍길동 010-1234-5678"))
        assertFalse(m.contains("1234"))
    }

    @Test fun oldRecordsBeyondSevenDaysCarryNoOpenWork() {
        val id=store().use { it.insert(call("rec1",now-10*day));CallRecordStore.recordId(call("rec1",0)) }
        store().use { it.applyAnalysis(id,analysis(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지"))) }
        val old=store().use { it.insert(call("rec2",now-9*day));CallRecordStore.recordId(call("rec2",0)) }
        store().use { db -> assertTrue(db.request(db.record(old)!!,old=true).let { it.openTasks.isEmpty() && it.recentlyDone.isEmpty() })
            assertFalse(db.request(db.record(old)!!).openTasks.isEmpty()) }
    }

    private class Inbox(val fail:String?=null):CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files.putIfAbsent(path,json) }
        override fun list(dir:String)=if(fail!=null && dir==CallInbox.RESULTS) throw ClaudeCallException(fail) else files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha"}
        override fun get(path:String)=files[path] ?: throw ClaudeCallException("INBOX_HTTP_404")
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    private fun runner(inbox:Inbox,s:JSONObject,at:Long=now)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },inbox,
        save={ e -> e(s) },callLog={ emptyList() },smsAllowed={ false })

    @Test fun aRefusedInboxIsToldOnceADay() {
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        assertTrue(runner(Inbox("INBOX_HTTP_401"),s).runPending())
        assertEquals("2026-10-28",s.getString(PipelineSettingsKeys.DISCONNECT_WARNED_DAY));assertEquals("INBOX_HTTP_401",s.getString(PipelineSettingsKeys.LAST_INBOX_ERROR))
        s.put(PipelineSettingsKeys.LAST_INBOX_ERROR,"")
        runner(Inbox("INBOX_HTTP_401"),s,at=now+3_600_000L).runPending()
        assertEquals("told once a day","",s.getString(PipelineSettingsKeys.LAST_INBOX_ERROR))
    }
}
