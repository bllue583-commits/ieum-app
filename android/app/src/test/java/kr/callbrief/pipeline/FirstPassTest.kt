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
            (1..22).forEach { db.insert(transcript("old$it",now-10*day-it*60_000L)) }
            db.insert(transcript("new",now))
        }
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-3_600_000L)
        val inbox=FakeInbox()
        assertTrue(runner(inbox,s).runPending())
        val batches=inbox.files.keys.filter { it.startsWith("inbox/b-") }.sorted()
        assertEquals(2,batches.size);assertEquals(1,inbox.files.keys.count { !it.startsWith("inbox/b-") })
        val first=JSONObject(inbox.files.getValue(batches[0]));val second=JSONObject(inbox.files.getValue(batches[1]))
        assertTrue(first.getString("id").startsWith(FirstPass.BATCH_PREFIX));assertEquals(CallInbox.CARRY_RULES,first.has("schema")&&first.has("system"));assertEquals(ClaudeCallAnalyzer.PROMPT_VERSION,first.getInt("promptVersion"))
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
        // r7-1: old = more than 7 days before now while the first pass runs, whenever it started.
        val start=JSONObject().put(PipelineSettingsKeys.FIRST_PASS_START_MS,now)
        assertTrue(FirstPass.isOld(now-7*day-1,start,now));assertFalse(FirstPass.isOld(now-7*day,start,now))
        assertFalse("before the start but this week: live",FirstPass.isOld(now-2*day,start,now))
        assertFalse(FirstPass.isOld(now-30*day,JSONObject(start.toString()).put(PipelineSettingsKeys.FIRST_PASS_DONE,true),now))
        assertFalse("nothing old before the first run",FirstPass.isOld(0,JSONObject(),now))
        assertTrue(FirstPass.stale(now-7*day-1,now));assertFalse(FirstPass.stale(now-7*day,now))
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
        assertEquals(7L,FirstPass.match(listOf(r("a",t,null,null)),listOf(FirstPass.LogEntry(7,"01055556666",null,t,60_000,1,excluded=true)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t,null,null)),listOf(FirstPass.LogEntry(7,"01055556666",null,t,60_000,1,excluded=true),e(2,"01033334444",null,t+10_000)))["a"])
        // With a number from the filename the entry is picked even among several.
        assertEquals(2L,FirstPass.match(listOf(r("a",t,"010-3333-4444","01033334444")),listOf(FirstPass.LogEntry(7,"01055556666",null,t,60_000,1,excluded=true),e(2,"01033334444",null,t+10_000)))["a"]?.id)
        // A number in the filename picks its entry even with another call nearby; a name picks the phone-book name.
        assertEquals(2L,FirstPass.match(listOf(r("a",t,"010-3333-4444","01033334444")),listOf(e(1,"01011112222",null,t),e(2,"01033334444",null,t+30_000)))["a"]?.id)
        assertEquals(1L,FirstPass.match(listOf(r("a",t,"홍 길동","홍 길동")),listOf(e(1,"01011112222","홍길동",t),e(2,"01033334444","김민수",t)))["a"]?.id)
        // A name not in the log (the phone book changed): the only call in time and length is it (design 3.4); two are not.
        assertEquals(1L,FirstPass.match(listOf(r("a",t,"박철수","박철수")),listOf(e(1,"01011112222","홍길동",t)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t,"박철수","박철수")),listOf(e(1,"01011112222","홍길동",t),e(2,"01033334444","김민수",t+20_000)))["a"])
        // Missed and rejected calls are never candidates.
        assertNull(FirstPass.match(listOf(r("a",t,null,null)),listOf(e(1,null,null,t,dur=0,type=3),e(2,null,null,t,type=5)))["a"])
        assertEquals("받은 전화",e(1,null,null,t).direction);assertEquals("건 전화",e(1,null,null,t,type=2).direction)
    }

    /** Design 3.4: an empty CACHED_NAME or another spelling no longer keeps a named recording from its call; the reasons are counted. */
    @Test fun aNamedRecordingPairsByTimeAndLengthWhenTheLogHasNoNameAndTheReasonsAreCounted() {
        val t=now
        fun e(id:Long,name:String?,at:Long,dur:Long=60_000,number:String="01011112222")=FirstPass.LogEntry(id,number,name,at,dur,1)
        fun r(id:String,at:Long,cp:String?,dur:Long=60_000)=FirstPass.RecordForMatch(id,at,cp,CallRecordStore.counterpartKey(cp),dur)
        var st:FirstPass.MatchStats?=null
        val out=FirstPass.match(listOf(r("null",t,"홍길동"),r("spelt",t+3_600_000,"홍길동 과장님"),r("two",t+7_200_000,"박철수"),r("far",t+9_000_000,"최영호"),
                r("long",t+10_800_000,"한소장",dur=300_000),r("both",t+14_400_000,"오세훈")),
            listOf(e(1,null,t+15_000),e(2,"홍길동",t+3_600_000-5_000),e(3,"김민수",t+7_200_000),e(4,"이민호",t+7_200_000+20_000),e(5,"한소장",t+10_800_000),
                e(6,null,t+14_400_000),e(7,null,t+14_400_000+30_000))) { st=it }
        assertEquals(1L,out["null"]?.id);assertEquals(2L,out["spelt"]?.id)
        assertNull(out["two"]);assertNull(out["far"]);assertNull(out["long"]);assertNull(out["both"])
        val s=st!!
        assertEquals(2,s.matched);assertEquals(1,s.nameDiffers);assertEquals(1,s.noCandidate);assertEquals(1,s.length);assertEquals(1,s.nameNull)
        assertEquals(mapOf(-20 to 1,0 to 1),s.gapSec)
        // Stored as one JSON string that grows run by run.
        val once=s.copy(noTime=2).mergeInto(null,100);val twice=s.mergeInto(once,200)
        val o=JSONObject(twice)
        assertEquals(2,o.getInt("runs"));assertEquals(200L,o.getLong("lastMs"));assertEquals(4,o.getInt("matched"))
        assertEquals(0,o.getJSONObject("last").getInt("noTime"));assertEquals(2,o.getJSONObject("gapSec").getInt("-20"))
    }

    @Test fun theStoreKeepsTheMatchCountersInSettings() {
        val saved=JSONObject()
        CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { edit -> edit(saved) }).use { db ->
            db.insert(transcript("unnamed",now,"홍길동"))
            db.reconcileCallLog(listOf(FirstPass.LogEntry(9,"01012345678",null,now-20_000,60_000,2)))
            assertEquals("01012345678",db.record(CallRecordStore.recordId(transcript("unnamed",now,"홍길동")))!!.counterpartKey)
            db.reconcileCallLog(emptyList())
        }
        val o=JSONObject(saved.getString(PipelineSettingsKeys.CALLLOG_MATCH_STATS))
        assertEquals(2,o.getInt("runs"));assertEquals(1,o.getInt("matched"));assertEquals(1,o.getJSONObject("gapSec").getInt("20"))
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
            assertTrue(ClaudeCallAnalyzer.userMessage(db.request(r)).contains("[통화 방향] 건 전화"))
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
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01055556666",null,now,60_000,1,excluded=true),FirstPass.LogEntry(2,"01033334444",null,now+10_000,60_000,1)))
            assertNotNull("two candidates: not paired, not forgotten",db.record(id))
            assertEquals(1,db.unrecordedCalls()) // entry 2 stays free; the personal one is never stored
        }
        CallRecordStore(context){ now }.use { db ->
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01055556666",null,now,60_000,1,excluded=true)).let { it })
            assertNotNull(db.record(id)) // the ordinary entry is still stored beside it
        }
    }

    @Test fun aRecordWithoutAnyKeyAndOnlyAPersonalCandidateIsForgotten() {
        val id=CallRecordStore.recordId(transcript("y",now,null))
        CallRecordStore(context){ now }.use { db ->
            db.insert(transcript("y",now,null))
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01055556666",null,now,60_000,1,excluded=true)))
            assertNull(db.record(id))
        }
    }

    // ---- v2.4: every call is stored and listed (11/4 user) ----
    @Test fun everyCallIsStoredExceptTheUsersOwnExclusion() {
        val missed=android.provider.CallLog.Calls.MISSED_TYPE;val rejected=android.provider.CallLog.Calls.REJECTED_TYPE
        CallRecordStore(context){ now }.use { db ->
            db.rememberPersonal("01077778888")
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01011112222","홍길동",now,0,missed),FirstPass.LogEntry(2,"01033334444",null,now+60_000,0,rejected),
                FirstPass.LogEntry(3,"01055556666",null,now+120_000,90_000,1),FirstPass.LogEntry(4,"01077778888","집",now+180_000,300_000,1),
                FirstPass.LogEntry(5,"01099990000",null,now+240_000,60_000,1,excluded=true)))
            val day=db.dayLog(CallRecordStore.kstDay(now))
            assertEquals(listOf(4L,3L,2L,1L),day.map { it.logId }) // newest first; the excluded number is not there at all
            assertEquals(listOf(FirstPass.LogKind.NO_RECORDING,FirstPass.LogKind.NO_RECORDING,FirstPass.LogKind.MISSED,FirstPass.LogKind.MISSED),day.map { it.kind })
            assertEquals(listOf(true,false,false,false),day.map { it.personal })
            assertEquals("홍길동",day.last().name)
            assertEquals(2,db.unrecordedCalls()) // answered ones only, the personal number's included
        }
        // A number the user excludes later: what was stored of it goes.
        CallRecordStore(context){ now }.use { db ->
            db.reconcileCallLog(listOf(FirstPass.LogEntry(3,"01055556666",null,now+120_000,90_000,1,excluded=true)))
            assertEquals(listOf(4L,2L,1L),db.dayLog(CallRecordStore.kstDay(now)).map { it.logId })
        }
    }

    @Test fun aRecordedCallIsListedOnceAndAPersonalRecordAsAPersonalLine() {
        val work=transcript("work",now,"홍길동");val home=transcript("home",now+3_600_000L,"엄마")
        CallRecordStore(context){ now }.use { db -> db.insert(work);db.insert(home)
            db.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01012345678","홍길동",now-20_000,60_000,2),FirstPass.LogEntry(2,"01022223333","엄마",now+3_600_000L-20_000,60_000,1)))
            db.keepPersonal(CallRecordStore.recordId(home))
            val day=db.dayLog(CallRecordStore.kstDay(now))
            assertEquals(listOf(2L),day.map { it.logId }) // the work call shows as its record
            assertTrue(day.single().personal)
            assertEquals(listOf(CallRecordStore.recordId(work)),db.day(CallRecordStore.kstDay(now)).map { it.recordId })
        }
    }

    @Test fun aPersonalNumbersCallIsStoredButNotSentAndMissedCallsAreNeverSent() {
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        val home=transcript("home",now,"엄마");val work=transcript("work",now+3_600_000L,"홍길동")
        CallRecordStore(context){ now }.use { db -> db.insert(home);db.insert(work);db.rememberPersonal("01022223333") }
        val log=listOf(FirstPass.LogEntry(1,"01022223333","엄마",now-20_000,60_000,1),FirstPass.LogEntry(2,"01012345678","홍길동",now+3_600_000L-20_000,60_000,2),
            FirstPass.LogEntry(3,"01044445555","박부재",now+600_000,0,android.provider.CallLog.Calls.MISSED_TYPE))
        val inbox=FakeInbox()
        assertTrue(runner(inbox,s,log,now+2*3_600_000L).runPending())
        val sent=inbox.files.filterKeys { it.startsWith("inbox/") }.values
        assertEquals(1,sent.size)
        assertTrue(sent.single().contains("[통화 방향] 건 전화"))
        assertFalse(sent.any { it.contains("엄마") || it.contains("박부재") || it.contains("01044445555") || it.contains("부재중") })
        CallRecordStore(context){ now }.use { db ->
            assertEquals(false,db.record(CallRecordStore.recordId(home))!!.analysis?.business) // kept on the phone as personal
            assertEquals(3,db.dayLog(CallRecordStore.kstDay(now)).size+db.day(CallRecordStore.kstDay(now)).size)
        }
    }

    // ---- r7-1: the first-pass gate ---------------------------------------------------------------------------------
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    /** A recording row with a known start time and a transcription job in [state]. */
    private fun job(id:String,startedMs:Long,state:String="QUEUED") {
        CallTranscriptionStore(context).close() // the job table
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/mp4',10,0,0,1,1,1,?)",
                id,GeneralLocalStore.OWNER,id,"$id.m4a",startedMs)
            p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,reason,updated_ms) VALUES(?,1,?,?,'',0)",id,"a".repeat(64),state);null } }
    }
    private fun jobState(id:String)=port().use { it.query("SELECT state,reason FROM call_transcription_jobs WHERE recording_id=?",id).single().let { r -> r["state"].toString() to r["reason"].toString() } }
    private val aug2025=java.time.LocalDateTime.of(2025,8,6,15,3,59).atZone(CallRecordStore.KST).toInstant().toEpochMilli()

    @Test fun aQueuedRecordingFrom2025DoesNotHoldTheBound() {
        job("y2025",aug2025)
        val oct5=java.time.LocalDate.of(2026,10,5).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        job("oct5",oct5)
        CallRecordStore(context){ now }.use { db ->
            assertEquals("skipped when the store opens",("SKIPPED" to "BEFORE_START"),jobState("y2025"))
            assertEquals("QUEUED",jobState("oct5").first)
            assertEquals("the bound is the oldest recording since 10/1",oct5,db.oldestOldJobMs(now-FirstPass.LIVE_MS))
        }
        // The transcription store skips it on open as well.
        port().use { it.execute("UPDATE call_transcription_jobs SET state='QUEUED',reason='' WHERE recording_id='y2025'") }
        CallTranscriptionStore(context).use { assertEquals("SKIPPED",it.rows().single { r -> r.first.recordingId=="y2025" }.second) }
        // Queued again while the store is open (an older build writing): the query alone leaves it out.
        CallRecordStore(context){ now }.use { db ->
            port().use { it.execute("UPDATE call_transcription_jobs SET state='QUEUED',reason='' WHERE recording_id='y2025'") }
            port().use { it.execute("UPDATE call_transcription_jobs SET state='SKIPPED' WHERE recording_id='oct5'") }
            assertNull("skipped rows and rows before 10/1 never count",db.oldestOldJobMs(now-FirstPass.LIVE_MS))
        }
    }

    @Test fun aCallFromThisWeekGoesAloneWhateverTheFirstPassStart() {
        CallRecordStore(context){ now }.use { db -> db.insert(transcript("week",now-3*day));db.insert(transcript("old",now-9*day)) }
        // The first pass started an hour ago: before r7-1 both were "old" and waited for a full batch.
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-3_600_000L)
        job("held",now-8*day) // an old recording still waiting for its words: the bound
        val inbox=FakeInbox()
        assertTrue(runner(inbox,s).runPending())
        val sent=inbox.files.keys.filter { it.startsWith("inbox/") }
        assertEquals(1,sent.size);assertFalse(sent.single().startsWith("inbox/b-"))
        assertEquals(CallInbox.recordKey(CallRecordStore.recordId(transcript("week",0))),JSONObject(inbox.files.getValue(sent.single())).getString("id"))
        assertFalse("a live call is not an old record",inbox.files.getValue(sent.single()).contains("[처음 정리]"))
        assertEquals(now-8*day,s.getLong(FirstPass.BOUND_KEY));assertEquals(FirstPass.BLOCKED_TRANSCRIPTS,s.getString(FirstPass.BLOCKED_KEY))
    }

    @Test fun tenOldRecordsThatWaitedSixHoursGoAsOneBatch() {
        CallRecordStore(context){ now }.use { db -> (1..10).forEach { db.insert(transcript("o$it",now-20*day+it*60_000L)) } }
        job("held",now-10*day) // the bound: later old recordings may still come
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-day)
        val inbox=FakeInbox()
        runner(inbox,s,at=now+3_600_000L).runPending()
        assertTrue("10 of 15, an hour old: they wait",inbox.files.isEmpty())
        assertEquals(FirstPass.BLOCKED_TRANSCRIPTS,s.getString(FirstPass.BLOCKED_KEY))
        runner(inbox,s,at=now+6*3_600_000L).runPending()
        val batch=inbox.files.entries.single();assertTrue(batch.key.startsWith("inbox/b-"))
        assertEquals(10,JSONObject(batch.value).getJSONArray("items").length())
        assertEquals(FirstPass.BLOCKED_NONE,s.getString(FirstPass.BLOCKED_KEY));assertEquals(1,s.getInt(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY))
    }

    @Test fun theDayCapAndTheStepBackStillHold() {
        CallRecordStore(context){ now }.use { db -> (1..10).forEach { db.insert(transcript("o$it",now-20*day+it*60_000L)) } }
        job("held",now-10*day)
        val today=java.time.Instant.ofEpochMilli(now+6*3_600_000L).atZone(CallRecordStore.KST).toLocalDate().toString()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-day)
            .put(PipelineSettingsKeys.FIRST_PASS_BATCH_DAY,today).put(PipelineSettingsKeys.FIRST_PASS_BATCHES_TODAY,FirstPass.BATCHES_PER_DAY)
        val inbox=FakeInbox()
        runner(inbox,s,at=now+6*3_600_000L).runPending()
        assertTrue("four batches today already",inbox.files.isEmpty());assertEquals(FirstPass.BLOCKED_BACKOFF,s.getString(FirstPass.BLOCKED_KEY))
    }

    @Test fun theOfferPassIsOneTransactionAndStops() {
        CallTranscriptionStore(context).use { store ->
            val list=(1..40).map { CallTranscriptionStore.Candidate(CallTranscriptionStore.Key("c$it",1,"a".repeat(64)),"c$it.m4a","content://x","audio/mp4",now,false,true) }
            val before=store.outerTransactions;val writes=kr.callbrief.ShadowTextAtomicFile.writes
            assertEquals(40,store.offerAll(list,{ c -> if(c.key.recordingId=="c1") "EXCLUDED" else null }))
            assertEquals("one outer transaction for 40 candidates",1,store.outerTransactions-before)
            assertEquals("one sealed write",1,kr.callbrief.ShadowTextAtomicFile.writes-writes)
            assertEquals(39,store.rows().count { it.second=="QUEUED" })
            var n=0
            assertEquals("stops between candidates",5,store.offerAll(list,{ null },{ n++>=5 }))
        }
    }

    private class FailingInbox(val fail:Exception):CallInboxStore {
        override fun put(path:String,json:String){}
        override fun list(dir:String):List<Pair<String,String>> = throw fail
        override fun get(path:String):String = throw ClaudeCallException("INBOX_HTTP_404")
        override fun delete(path:String,sha:String){}
    }
    @Test fun everyInboxErrorIsKeptAsItsCodeAndTime() {
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        fun run(e:Exception,at:Long=now)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ at },FailingInbox(e),save={ edit -> edit(s) },callLog={ emptyList() },smsAllowed={ false }).runPending()
        assertFalse("a 422 is retried",run(ClaudeCallException("INBOX_HTTP_422")))
        assertEquals("INBOX_HTTP_422",s.getString(PipelineSettingsKeys.LAST_INBOX_ERROR));assertEquals(now,s.getLong(PipelineSettingsKeys.LAST_INBOX_ERROR_MS))
        assertFalse(run(IllegalStateException("김 부장 010-1234-5678"),now+1))
        assertEquals("the class name only, never the text","IllegalStateException",s.getString(PipelineSettingsKeys.LAST_INBOX_ERROR))
        assertFalse(run(java.io.IOException("INBOX_HTTP_503"),now+2));assertEquals("INBOX_HTTP_503",s.getString(PipelineSettingsKeys.LAST_INBOX_ERROR))
        // A pass that goes through clears it.
        CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now+3 },FakeInbox(),save={ edit -> edit(s) },callLog={ emptyList() },smsAllowed={ false }).runPending()
        assertEquals("",s.getString(PipelineSettingsKeys.LAST_INBOX_ERROR))
    }
}
