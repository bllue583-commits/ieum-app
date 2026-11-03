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

/** 처음 정리: old records summarised a batch at a time, then one consolidation rewrites the ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FirstPassTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=1_793_300_000_000L // 2026-10-28
    private val day=86_400_000L
    private class FakeInbox:CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha-$it"}
        override fun get(path:String)=files.getValue(path)
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    @Before fun setup() { context=RuntimeEnvironment.getApplication() }
    private fun transcript(id:String,startedMs:Long,counterpart:String?="김과장")=
        CallTranscript(id,1,"a".repeat(64),startedMs,60_000,counterpart,ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
    private fun runner(inbox:FakeInbox,s:JSONObject,log:List<FirstPass.LogEntry> =emptyList())=CallRecordAnalysisRunner(context,
        ClaudeCallAnalyzer({ _,_ -> error("API must not be used") }) {},{ s },{ now },inbox,save={ edit -> edit(s) },callLog={ log },smsAllowed={ false })
    /** The scheduled task answers every request in the inbox. */
    private fun answer(inbox:FakeInbox,analysis:(String)->JSONObject={ ClaudeCallAnalyzerTest.analysis() }) {
        inbox.files.keys.filter { it.startsWith("inbox/") }.toList().forEach { k ->
            val id=JSONObject(inbox.files.remove(k)!!).getString("id")
            inbox.files["results/"+k.removePrefix("inbox/")]=JSONObject().put("id",id).put("analysis",analysis(id)).toString()
        }
    }

    @Test fun oldRecordsGoTwentyAtATimeAfterNewOnesThenOneConsolidation() {
        CallRecordStore(context){ now }.use { db ->
            (1..22).forEach { db.insert(transcript("old$it",now-2*day-it*60_000L)) }
            db.insert(transcript("new",now))
        }
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-3_600_000L)
        val inbox=FakeInbox()
        assertTrue(runner(inbox,s).runPending())
        val sent=inbox.files.keys
        assertEquals(20,sent.count { it.startsWith("inbox/b-") });assertEquals(1,sent.count { !it.startsWith("inbox/b-") })
        assertTrue("old ones say so",inbox.files.filterKeys { it.startsWith("inbox/b-") }.values.all { JSONObject(it).getString("message").startsWith("[처음 정리]") })
        assertFalse(inbox.files.getValue(sent.first { !it.startsWith("inbox/b-") }).contains("[처음 정리]"))

        answer(inbox);runner(inbox,s).runPending()
        assertEquals("the last two old ones follow",2,inbox.files.keys.count { it.startsWith("inbox/b-") })
        answer(inbox);runner(inbox,s).runPending()
        val ctx=inbox.files.entries.single()
        assertTrue(ctx.key.startsWith("inbox/ctx-"))
        val job=JSONObject(ctx.value)
        assertTrue(job.getString("id").startsWith(FirstPass.CONTEXT_PREFIX));assertTrue(job.has("schema"))
        assertEquals(23,job.getString("message").lines().count { it.startsWith("- ") })

        inbox.files.clear()
        inbox.files["results/"+ctx.key.removePrefix("inbox/")]=JSONObject().put("id",job.getString("id")).put("analysis",
            JSONObject().put("siteMemo","3공구 철근 견적과 계약서 일정 조율 중").put("companies",JSONArray()).put("people",JSONArray()).put("sameAs",JSONArray())).toString()
        runner(inbox,s).runPending()
        assertTrue(s.getBoolean(PipelineSettingsKeys.FIRST_PASS_DONE))
        assertEquals("3공구 철근 견적과 계약서 일정 조율 중",CallRecordStore(context).use { it.siteMemo() })
        assertTrue(inbox.files.isEmpty())
        runner(inbox,s).runPending();assertTrue("sent once",inbox.files.isEmpty())
    }

    @Test fun oldResultsKeepNamesButNotMemosOrOldWork() {
        val a=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray().put(ClaudeCallAnalyzerTest.task("create",null,"견적서 보내기","견적서는 내일까지")))
            .put("siteMemo","메모").put("companyMemo","업체 메모"))
        val recent=FirstPass.restrict(a,now-3*day,now);val old=FirstPass.restrict(a,now-10*day,now)
        assertNull(recent.siteMemo);assertNull(recent.companyMemo);assertEquals(1,recent.tasks.size)
        assertTrue(old.tasks.isEmpty());assertEquals("김과장",old.counterpartName)
        val start=JSONObject().put(PipelineSettingsKeys.FIRST_PASS_START_MS,now)
        assertTrue(FirstPass.isOld(now-1,start));assertFalse(FirstPass.isOld(now,start))
        assertFalse(FirstPass.isOld(now-1,JSONObject(start.toString()).put(PipelineSettingsKeys.FIRST_PASS_DONE,true)))
        assertFalse("nothing old before the first run",FirstPass.isOld(0,JSONObject()))
        assertTrue(FirstPass.beforeRange(FirstPass.FROM_MS-1));assertFalse(FirstPass.beforeRange(FirstPass.FROM_MS));assertFalse(FirstPass.beforeRange(null))
    }

    @Test fun recordingsMatchTheCallLogByNumberNameOrTheOnlyAnsweredCall() {
        val t=now
        fun e(id:Long,n:String?,name:String?,at:Long,dur:Long=60_000,type:Int=1)=FirstPass.LogEntry(id,n,name,at,dur,type)
        val m=FirstPass.match(listOf(
            FirstPass.RecordForMatch("byNumber",t,"010-1111-2222","01011112222"),
            FirstPass.RecordForMatch("byName",t+3_600_000,"홍 길동","홍길동"),
            FirstPass.RecordForMatch("only",t+7_200_000,null,null),
            FirstPass.RecordForMatch("none",t+9_000_000,null,null)),
            listOf(e(1,"01099998888",null,t+10_000),e(2,"01011112222",null,t+60_000),
                e(3,"01033334444","홍길동",t+3_600_000+90_000),e(4,"01055556666","다른 사람",t+3_600_000+5_000),
                e(5,"01077778888",null,t+7_200_000-30_000),e(6,"01077778888",null,t+7_200_000+20_000,dur=0,type=3),
                e(7,"01012121212",null,t+9_000_000+400_000)))
        assertEquals(2L,m["byNumber"]?.id);assertEquals(3L,m["byName"]?.id);assertEquals(5L,m["only"]?.id);assertNull(m["none"])
        assertEquals("받은 전화",m["only"]?.direction)
    }

    @Test fun theCallLogGivesANamedRecordingItsNumberAndDirection() {
        val id=CallRecordStore(context){ now }.use { db -> db.insert(transcript("named",now,"홍길동"));CallRecordStore.recordId(transcript("named",now,"홍길동")) }
        CallRecordStore(context){ now }.use { db ->
            db.reconcileCallLog(listOf(FirstPass.LogEntry(9,"01012345678","홍길동",now-20_000,60_000,2),FirstPass.LogEntry(10,"01087654321",null,now-day,30_000,1)))
            val r=db.record(id)!!
            assertEquals("01012345678",r.counterpartKey);assertEquals("홍길동",r.counterpart)
            assertEquals("건 전화",db.request(r).direction)
            assertTrue(ClaudeCallAnalyzer.userMessage(db.request(r)).contains("통화 방향: 건 전화"))
            assertEquals(1,db.unrecordedCalls())
        }
    }

    @Test fun consolidationMergesMisheardPeopleButNeverOneTheUserRenamed() {
        CallRecordStore(context){ now }.use { db -> db.insert(transcript("r1",now)) }
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms) VALUES('a','김민슈',NULL,NULL,NULL,0,0)")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms) VALUES('b','김민수','01011112222',NULL,NULL,0,0)")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms) VALUES('c','박재웅',NULL,NULL,NULL,0,0)")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms) VALUES('d','박재옹',NULL,NULL,NULL,0,0)")
            p.execute("INSERT INTO person_aliases VALUES('박제웅','c')") // the user corrected c's name earlier
            p.execute("INSERT INTO call_links VALUES(?,'a',NULL)",CallRecordStore.recordId(transcript("r1",now)));null
        } }
        CallRecordStore(context){ now }.use { db ->
            db.applyDigest(FirstPass.Digest.parse(JSONObject().put("siteMemo","현장 메모").put("companies",JSONArray()).put("people",JSONArray()
                .put(JSONObject().put("name","김민수").put("company",JSONObject.NULL).put("role","과장").put("isStaff",true)))
                .put("sameAs",JSONArray().put(JSONObject().put("name","김민슈").put("sameAs","김민수")).put(JSONObject().put("name","박재웅").put("sameAs","박재옹")))))
            val people=db.peopleOf(null).associateBy { it.name }
            assertEquals(setOf("김민수","박재웅","박재옹"),people.keys)
            assertEquals("과장",people["김민수"]?.role);assertTrue(people["김민수"]!!.isStaff)
            assertEquals("the call follows the merged person","김민수",db.record(CallRecordStore.recordId(transcript("r1",now)))?.linkedPerson)
        }
    }

    @Test fun oldTextsAreGroupedPerNumberAndDay() {
        fun sms(id:Long,at:Long,phone:String)=MessageIntake.Sms(id,at,false,phone,"내일 반입 가능합니다")
        val list=listOf(sms(1,now,"01011112222"),sms(2,now+60_000,"01011112222"),sms(3,now+day,"01011112222"),sms(-4,now,"01033334444"))
        assertEquals(2,MessageIntake.threads(list).size)
        assertEquals(listOf(2,1,1),MessageIntake.threads(list,byDay=true).map { it.second.size }.sortedDescending())
        assertTrue(MessageIntake.threads(list,byDay=true).all { it.first.all(Char::isDigit) })
    }

    @Test fun digestParsesAndDropsEmptyNames() {
        val d=FirstPass.Digest.parse(JSONObject().put("siteMemo",JSONObject.NULL)
            .put("companies",JSONArray().put(JSONObject().put("name","△△철강").put("trade","철근").put("memo","견적 중")).put(JSONObject().put("name","")))
            .put("people",JSONArray()).put("sameAs",JSONArray().put(JSONObject().put("name","가").put("sameAs","가"))))
        assertNull(d.siteMemo);assertEquals(listOf("△△철강"),d.companies.map { it.name });assertTrue(d.sameAs.isEmpty())
        val schema=FirstPass.SCHEMA;assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(setOf("siteMemo","companies","people","sameAs"),schema.getJSONObject("properties").keys().asSequence().toSet())
    }
}
