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
    private fun runner(inbox:FakeInbox,s:JSONObject,log:List<FirstPass.LogEntry> =emptyList(),at:Long=now)=CallRecordAnalysisRunner(context,
        ClaudeCallAnalyzer(),{ s },{ at },inbox,save={ edit -> edit(s) },callLog={ log },smsAllowed={ false })
    /** The scheduled task answers every request in the inbox. */
    private fun answer(inbox:FakeInbox,analysis:(String)->JSONObject={ ClaudeCallAnalyzerTest.analysis() }) {
        inbox.files.keys.filter { it.startsWith("inbox/") }.toList().forEach { k ->
            val id=JSONObject(inbox.files.remove(k)!!).getString("id")
            inbox.files["results/"+k.removePrefix("inbox/")]=JSONObject().put("id",id).put("analysis",analysis(id)).toString()
        }
    }

    @Test fun oldRecordsGoInTimeOrderedBatchesAfterNewOnes() {
        CallRecordStore(context){ now }.use { db ->
            (1..22).forEach { db.insert(transcript("old$it",now-2*day-it*60_000L)) }
            db.insert(transcript("new",now))
        }
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-3_600_000L)
        val inbox=FakeInbox()
        assertTrue(runner(inbox,s).runPending())
        val batches=inbox.files.keys.filter { it.startsWith("inbox/b-") }.sorted()
        assertEquals(2,batches.size);assertEquals(1,inbox.files.keys.count { !it.startsWith("inbox/b-") })
        val first=JSONObject(inbox.files.getValue(batches[0]));val second=JSONObject(inbox.files.getValue(batches[1]))
        assertTrue(first.getString("id").startsWith(FirstPass.BATCH_PREFIX));assertFalse(first.has("schema")||first.has("system"));assertEquals(ClaudeCallAnalyzer.PROMPT_VERSION,first.getInt("promptVersion"))
        val items=first.getJSONArray("items");assertEquals(15,items.length());assertEquals(7,second.getJSONArray("items").length())
        // Time order: oldest first inside a batch and across batch names.
        assertEquals(CallInbox.recordKey(CallRecordStore.recordId(transcript("old22",0))),items.getJSONObject(0).getString("id"))
        assertTrue(items.getJSONObject(0).getString("message").let { it.startsWith("[사용자]") && it.contains("\n[처음 정리]") })
        assertTrue(items.getJSONObject(0).getString("counterpartId").startsWith("p_"))
        val single=JSONObject(inbox.files.entries.first { !it.key.startsWith("inbox/b-") }.value)
        assertFalse(single.getString("message").contains("[처음 정리]"))
        assertEquals("same person, same id",items.getJSONObject(0).getString("counterpartId"),single.getString("counterpartId"))
        assertFalse(s.optBoolean(PipelineSettingsKeys.FIRST_PASS_DONE))

        // The task answers each batch with one file listing every record.
        inbox.files.keys.filter { it.startsWith("inbox/") }.toList().forEach { k ->
            val job=JSONObject(inbox.files.remove(k)!!)
            val results=JSONArray()
            val list=job.optJSONArray("items")
            if(list==null) results.put(JSONObject().put("id",job.getString("id")).put("analysis",ClaudeCallAnalyzerTest.analysis()))
            else for(n in 0 until list.length()) results.put(JSONObject().put("id",list.getJSONObject(n).getString("id")).put("analysis",ClaudeCallAnalyzerTest.analysis()))
            inbox.files["results/"+k.removePrefix("inbox/")]=JSONObject().put("id",job.getString("id")).put("results",results).toString()
        }
        runner(inbox,s).runPending()
        assertTrue(inbox.files.isEmpty())
        assertEquals(23,CallRecordStore(context).use { it.counts()[CallRecordStore.ANALYZED] })
        assertFalse("not over at the first quiet moment",s.optBoolean(PipelineSettingsKeys.FIRST_PASS_DONE))
        runner(inbox,s,at=now+6*3_600_000L).runPending();assertFalse(s.optBoolean(PipelineSettingsKeys.FIRST_PASS_DONE))
        runner(inbox,s,at=now+12*3_600_000L).runPending()
        assertTrue("12 quiet hours: the first pass is over",s.getBoolean(PipelineSettingsKeys.FIRST_PASS_DONE))
    }

    @Test fun theRepositoryLedgerIsTakenInAndCorrectionsGoUp() {
        CallRecordStore(context){ now }.use { db -> db.insert(transcript("r1",now,"010-1111-2222")) }
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        val inbox=FakeInbox()
        runner(inbox,s).runPending()
        val job=JSONObject(inbox.files.values.single())
        val lid=job.getString("counterpartId");assertTrue(lid.startsWith("p_"))
        assertFalse("the number stays on the phone",inbox.files.values.single().let { it.contains("010-1111-2222") || it.contains("01011112222") })
        inbox.files.clear()
        inbox.files["ledger/ledger.json"]=JSONObject().put("rev",3).put("siteMemo","3공구 철근 반입 중")
            .put("companies",JSONArray().put(JSONObject().put("name","△△철강").put("trade","철근").put("memo","견적 진행")))
            .put("people",JSONArray().put(JSONObject().put("id",lid).put("name","김민수").put("company","△△철강").put("role","과장").put("isStaff",false).put("aliases",JSONArray().put("김민슈")))
                .put(JSONObject().put("id","p_unknown").put("name","남의 사람"))).toString()
        runner(inbox,s).runPending()
        assertEquals(3L,s.getLong(PipelineSettingsKeys.LEDGER_REV))
        CallRecordStore(context).use { db ->
            assertEquals("3공구 철근 반입 중",db.siteMemo())
            val co=db.companies().single();assertEquals("△△철강",co.name);assertEquals("철근",co.trade)
            val p=db.peopleOf(co.id).single();assertEquals("김민수",p.name);assertEquals("과장",p.role)
            assertTrue(db.renamePerson(p.id,"김민서"))
        }
        // The correction goes up as an edit; an older ledger name does not undo it.
        runner(inbox,s).runPending()
        val edit=inbox.files.entries.single { it.key.startsWith("ledger/edits/") }
        assertEquals("김민서",JSONObject(edit.value).getString("name"))
        assertEquals(lid,JSONObject(edit.value).getString("id"))
        inbox.files["ledger/ledger.json"]=JSONObject(inbox.files.getValue("ledger/ledger.json")).put("rev",4).toString()
        runner(inbox,s).runPending()
        assertEquals("김민서",CallRecordStore(context).use { db -> db.peopleOf(db.companies().single().id).single().name })
        assertTrue("sent once",inbox.files.keys.count { it.startsWith("ledger/edits/") }==1)
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

    @Test fun recordingsMatchTheCallLogWithinOneMinuteBeforeToTwoAfterAndSimilarLength() {
        val t=now;val min=60_000L
        fun e(id:Long,n:String?,name:String?,at:Long,dur:Long=60_000,type:Int=1)=FirstPass.LogEntry(id,n,name,at,dur,type)
        fun r(id:String,at:Long,cp:String?,key:String?,dur:Long=60_000)=FirstPass.RecordForMatch(id,at,cp,key,dur)
        // Window edges: the recording may start 1 min before to 2 min after the log time.
        assertEquals(1L,FirstPass.match(listOf(r("a",t-min,null,null)),listOf(e(1,null,null,t)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t-min-1000,null,null)),listOf(e(1,null,null,t)))["a"])
        assertEquals(1L,FirstPass.match(listOf(r("a",t+2*min,null,null)),listOf(e(1,null,null,t)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t+2*min+1000,null,null)),listOf(e(1,null,null,t)))["a"])
        // Lengths must agree within 20% or 15 s.
        assertEquals(1L,FirstPass.match(listOf(r("a",t,null,null,dur=70_000)),listOf(e(1,null,null,t,dur=60_000)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t,null,null,dur=120_000)),listOf(e(1,null,null,t,dur=60_000)))["a"])
        // A recording whose filename gave no number or name pairs only when exactly one entry fits (two fitting ones: the old give-up rule).
        assertNull(FirstPass.match(listOf(r("a",t,null,null)),listOf(e(1,"01011112222",null,t),e(2,"01033334444",null,t+30_000)))["a"])
        assertNull(FirstPass.match(listOf(r("a",t,null,null,dur=60_000)),listOf(e(1,"01011112222",null,t,dur=50_000),e(2,"01033334444",null,t+10_000,dur=60_000)))["a"])
        assertEquals(2L,FirstPass.match(listOf(r("a",t,null,null,dur=60_000)),listOf(e(1,"01011112222",null,t,dur=10_000),e(2,"01033334444",null,t+10_000,dur=60_000)))["a"]?.id) // only one fits in length
        // The personal number counts as a candidate too, and pairs only when it is the only one.
        assertEquals(7L,FirstPass.match(listOf(r("a",t,null,null)),listOf(FirstPass.LogEntry(7,"01055556666",null,t,60_000,1,personal=true)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t,null,null)),listOf(FirstPass.LogEntry(7,"01055556666",null,t,60_000,1,personal=true),e(2,"01033334444",null,t+10_000)))["a"])
        // With a number from the filename the entry is picked even among several.
        assertEquals(2L,FirstPass.match(listOf(r("a",t,"010-3333-4444","01033334444")),listOf(FirstPass.LogEntry(7,"01055556666",null,t,60_000,1,personal=true),e(2,"01033334444",null,t+10_000)))["a"]?.id)
        // A number in the filename picks its entry even with another call nearby; a name picks the phone-book name.
        assertEquals(2L,FirstPass.match(listOf(r("a",t,"010-3333-4444","01033334444")),listOf(e(1,"01011112222",null,t),e(2,"01033334444",null,t+30_000)))["a"]?.id)
        assertEquals(1L,FirstPass.match(listOf(r("a",t,"홍 길동","홍 길동")),listOf(e(1,"01011112222","홍길동",t),e(2,"01033334444","김민수",t)))["a"]?.id)
        assertNull("a name not in the log",FirstPass.match(listOf(r("a",t,"박철수","박철수")),listOf(e(1,"01011112222","홍길동",t)))["a"])
        // Missed and rejected calls are never candidates.
        assertNull(FirstPass.match(listOf(r("a",t,null,null)),listOf(e(1,null,null,t,dur=0,type=3),e(2,null,null,t,type=5)))["a"])
        assertEquals("받은 전화",e(1,null,null,t).direction);assertEquals("건 전화",e(1,null,null,t,type=2).direction)
    }

    @Test fun callsPlacedAgainWithinTwoMinutesAreAssignedAcrossAllRecordingsAndEntries() {
        val t=now;val min=60_000L
        fun e(id:Long,at:Long,dur:Long)=FirstPass.LogEntry(id,"01011112222",null,at,dur,2)
        fun r(id:String,at:Long,dur:Long)=FirstPass.RecordForMatch(id,at,"홍길동","01011112222",dur)
        // The same person called twice a minute apart: both recordings fit both entries in time, the lengths tell them apart.
        val redial=FirstPass.match(listOf(r("a",t,30_000),r("b",t+min,90_000)),listOf(e(1,t,30_000),e(2,t+min,90_000)))
        assertEquals(mapOf("a" to 1L,"b" to 2L),redial.mapValues { it.value.id })
        // Equal lengths: the best pairs are taken first across everything, not recording by recording.
        val swap=FirstPass.match(listOf(r("a",t,60_000),r("b",t+min,60_000)),listOf(e(1,t+min,60_000),e(2,t-30_000,60_000)))
        assertEquals(mapOf("b" to 1L,"a" to 2L),swap.mapValues { it.value.id })
        // One entry, two recordings: it goes to the closer one, the other stays unlinked.
        val one=FirstPass.match(listOf(r("a",t+90_000,60_000),r("b",t+10_000,60_000)),listOf(e(1,t,60_000)))
        assertEquals(mapOf("b" to 1L),one.mapValues { it.value.id })
        assertTrue(FirstPass.match(emptyList(),listOf(e(1,t,1))).isEmpty());assertTrue(FirstPass.match(listOf(r("a",t,1)),emptyList()).isEmpty())
    }

    @Test fun callsWithoutARecordingAreCountedButNotMissedOrRejectedOnes() {
        CallRecordStore(context){ now }.use { db ->
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01011112222",null,now,60_000,1),FirstPass.LogEntry(2,"01011112222",null,now+day,30_000,2),
                FirstPass.LogEntry(3,"01011112222",null,now,0,3),FirstPass.LogEntry(4,"01011112222",null,now,0,5)))
            assertEquals(2,db.unrecordedCalls())
        }
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

    @Test fun oldTextsAreGroupedPerNumberAndDay() {
        fun sms(id:Long,at:Long,phone:String)=MessageIntake.Sms(id,at,false,phone,"내일 반입 가능합니다")
        val list=listOf(sms(1,now,"01011112222"),sms(2,now+60_000,"01011112222"),sms(3,now+day,"01011112222"),sms(-4,now,"01033334444"))
        assertEquals(2,MessageIntake.threads(list).size)
        assertEquals(listOf(2,1,1),MessageIntake.threads(list,byDay=true).map { it.second.size }.sortedDescending())
        assertTrue(MessageIntake.threads(list,byDay=true).all { it.first.all(Char::isDigit) })
    }

    @Test fun batchResultsParseAndBrokenEntriesAreLeftOut() {
        val ok=JSONObject().put("id","a").put("analysis",ClaudeCallAnalyzerTest.analysis())
        val text=JSONObject().put("id","batch:x").put("results",JSONArray().put(ok).put(JSONObject().put("id","b"))).toString()
        assertEquals(listOf("a"),CallInbox.parseResults(text).map { it.first })
        assertEquals(listOf("a"),CallInbox.parseResults(ok.toString()).map { it.first })
        assertTrue(CallInbox.batchName(5,listOf("x")).startsWith("b-0000000000005-"))
        assertTrue(CallInbox.batchName(5,listOf("x"))<CallInbox.batchName(40,listOf("y")))
    }

    @Test fun aRecordWithoutAnyKeyIsForgottenAsPersonalOnlyWhenThePersonalEntryIsItsOnlyCandidate() {
        val id=CallRecordStore.recordId(transcript("x",now,null))
        CallRecordStore(context){ now }.use { db ->
            db.insert(transcript("x",now,null))
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01055556666",null,now,60_000,1,personal=true),FirstPass.LogEntry(2,"01033334444",null,now+10_000,60_000,1)))
            assertNotNull("two candidates: not paired, not forgotten",db.record(id))
            assertEquals(1,db.unrecordedCalls()) // entry 2 stays free; the personal one is never stored
        }
        CallRecordStore(context){ now }.use { db ->
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01055556666",null,now,60_000,1,personal=true)).let { it })
            assertNotNull(db.record(id)) // the ordinary entry is still stored beside it
        }
    }

    @Test fun aRecordWithoutAnyKeyAndOnlyAPersonalCandidateIsForgotten() {
        val id=CallRecordStore.recordId(transcript("y",now,null))
        CallRecordStore(context){ now }.use { db ->
            db.insert(transcript("y",now,null))
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01055556666",null,now,60_000,1,personal=true)))
            assertNull(db.record(id))
        }
    }
}
