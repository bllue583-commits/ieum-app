package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
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
import java.time.LocalDate

/** v3.7 r17-3 (DESIGN_V7 3.3): the scorecard's measurement in the heartbeat. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R17MeasureTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val hour=3_600_000L;private val day=86_400_000L;private val min=60_000L
    /** Tuesday 2027-01-19 22:00 KST. */
    private val t=LocalDate.of(2027,1,19).atTime(22,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private val recs=listOf("rec1","rec2","rec3","rec4","rec5","rec6","rec7")
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            recs.forEach { r ->
                // rec5 is a call of 20 days ago (outside the 14-day window); the others are of today.
                val at=if(r=="rec5") t-20*day else t-3*hour
                p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',10,?,?,1,1,1,?)",
                    r,GeneralLocalStore.OWNER,r,at+2*min,at+2*min,at) }
            null
        } }
        UseStats.resetForHost()
    }
    @After fun reset() { CallTranscriptionStore.buildCode={ kr.callbrief.BuildConfig.VERSION_CODE };UseStats.resetForHost() }
    private fun store(at:Long)=CallRecordStore(context){ at }
    private fun call(id:String,at:Long)=CallTranscript(id,1,"a".repeat(64),at,60_000,"김과장",ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
    private fun analysis()=ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis(JSONArray()))
    private fun candidate(id:String)=CallTranscriptionStore.Candidate(CallTranscriptionStore.Key(id,1,"a".repeat(64)),"x.m4a","content://x","audio/mp4",1L,false,true)
    private fun stage(end:Long,applied:Long?,text:Long?=null,up:Long?=null)=Telemetry.Stage(end,60_000,end+min,null,text,up,applied,applied!=null)

    // ---- 1. latency: three segments, the day's value, over7d ----
    @Test fun segmentsAndTheDayAreMadeFromTheStoredTimes() {
        val start=t-2*hour // 1-minute call: it ends at t-119 min
        val id=store(t-90*min).use { it.insert(call("rec1",start));CallRecordStore.recordId(call("rec1",0)) }
        store(t).use { db -> db.markSent(id,t-80*min);db.applyAnalysis(id,analysis()) }
        // rec2: a call of today still waiting for its result (stored, not sent yet).
        store(t-30*min).use { it.insert(call("rec2",t-hour)) }
        val rows=store(t).use { it.stageRows(t-7*day) { r -> if(r=="rec1") t-100*min else null } }
        val seg=Telemetry.segments(rows)
        assertEquals(29L,seg.getJSONObject("endToText").getLong("p50Min"))
        assertEquals(10L,seg.getJSONObject("textToUpload").getLong("p50Min"))
        assertEquals(80L,seg.getJSONObject("uploadToApplied").getLong("p90Min"))
        assertEquals(0,seg.getJSONObject("uploadToApplied").getInt("over7d"))
        // The day: the applied one (119 min) and the waiting one (59 min so far, a lower bound).
        val d=Telemetry.dayLatency(rows,t)
        assertEquals(2,d.getInt("n"));assertEquals(1,d.getInt("waiting"));assertEquals(59L,d.getLong("p50Min"));assertEquals(119L,d.getLong("p90Min"))
        // A call of yesterday is not today's.
        assertEquals(0,Telemetry.dayLatency(rows,t+day).getInt("n"))
        // The trace: oldest first, seconds from the call's end, no id or name.
        val tr=Telemetry.trace(rows)
        assertEquals(2,tr.length())
        val first=tr.getJSONObject(0)
        assertEquals((22*60-119).toLong(),first.getLong("endMin"));assertEquals("2027-01-19",first.getString("day"))
        assertEquals(19*60L,first.getLong("textStartS"));assertEquals(29*60L,first.getLong("textEndS"));assertEquals(39*60L,first.getLong("uploadS"))
        assertEquals(119*60L,first.getLong("appliedS"));assertEquals(60L,first.getLong("durS"))
        assertTrue(tr.getJSONObject(1).isNull("appliedS"));assertTrue(tr.getJSONObject(1).isNull("uploadS"))
        assertFalse(tr.toString().contains("rec"));assertFalse(Regex("[가-힣]").containsMatchIn(tr.toString()))
    }
    @Test fun overSevenDaysIsCountedNotDropped() {
        val rows=listOf(stage(t-9*day,t,text=t-9*day+min,up=t-hour),stage(t-hour,t,text=t-50*min,up=t-40*min))
        val seg=Telemetry.segments(rows)
        assertEquals(1,seg.getJSONObject("textToUpload").getInt("over7d"));assertEquals(1,seg.getJSONObject("textToUpload").getInt("n"))
        assertEquals(1,Telemetry.over7d(rows))
        // The first pass is not timed.
        assertEquals(0,Telemetry.segments(listOf(stage(t-hour,t).copy(firstPass=true))).getJSONObject("endToText").getInt("n"))
        // Fifty at most in the trace, the newest.
        val many=(1..60).map { stage(t-it*hour,null) }
        val tr=Telemetry.trace(many)
        assertEquals(50,tr.length());assertEquals(Telemetry.day(t-50*hour),tr.getJSONObject(0).getString("day"))
    }

    // ---- 3. window14: a recording sent back to the queue and failing again is one ----
    @Test fun windowCountsEachFailedRecordingOnce() {
        var clock=t
        CallTranscriptionStore.buildCode={ 1060 }
        CallTranscriptionStore(context,{ clock }).use { s ->
            listOf("rec2","rec3","rec4","rec5","rec6").forEach { s.offer(candidate(it),true,"") }
            s.offer(candidate("rec7"),false,"EXCLUDED") // skipped before the queue: not "created"
            fun fail(id:String) { repeat(CallTranscriptionRunner.MAX_ATTEMPTS) { assertTrue(s.claim(candidate(id).key));s.finish(candidate(id).key,if(it<CallTranscriptionRunner.MAX_ATTEMPTS-1) "QUEUED" else "FAILED","STALLED") } }
            fail("rec2");fail("rec5")
            assertTrue(s.claim(candidate("rec3").key));s.finish(candidate("rec3").key,"DONE","")
            assertTrue(s.claim(candidate("rec4").key));s.failPermanently(candidate("rec4").key,"DECODE")
        }
        // A new build sends rec2 back, and it fails again.
        clock+=hour;CallTranscriptionStore.buildCode={ 1061 }
        CallTranscriptionStore(context,{ clock }).use { s -> assertTrue(s.requeueFailed()>=1)
            repeat(CallTranscriptionRunner.MAX_ATTEMPTS) { assertTrue(s.claim(candidate("rec2").key));s.finish(candidate("rec2").key,if(it<CallTranscriptionRunner.MAX_ATTEMPTS-1) "QUEUED" else "FAILED","STALLED") } }
        val (created,failed,forGood)=store(clock).use { it.transcriptionWindow(clock-14*day) }!!
        assertEquals("rec2, rec3, rec4 and the still queued rec6 (rec5 is older, rec7 was skipped)",4,created)
        assertEquals(2,failed);assertEquals(1,forGood)
        val hb=PhoneStatus.build(base().copy(v37=PhoneStatus.V37(window14=Telemetry.window14(created,failed,forGood))))
        val w=hb.getJSONObject("transcription").getJSONObject("window14")
        assertEquals(4,w.getInt("created"));assertEquals(2,w.getInt("failed"));assertEquals(1,w.getInt("failedPermanent"))
    }

    // ---- 5. use counts: in memory, rolled at KST midnight ----
    @Test fun useCountsRollAtMidnight() {
        var now=t;var el=1_000_000L
        UseStats.clock={ now };UseStats.elapsed={ el }
        UseStats.add(UseStats.OPENS);UseStats.add(UseStats.TASK_DONE,3);UseStats.add(UseStats.KAKAO_OPEN)
        UseStats.home(true);el+=95_000;UseStats.home(false)
        UseStats.flush(context,now)
        val s1=UseStats.stored(kr.callbrief.SecureSettings(context).snapshot())
        val today=UseStats.heartbeat(s1,now).getJSONObject("today")
        assertEquals("2027-01-19",today.getString("day"));assertEquals(1,today.getInt("opens"));assertEquals(3,today.getInt("taskDone"))
        assertEquals(95,today.getInt("homeSec"));assertEquals(1,today.getInt("kakaoOpen"))
        assertTrue("before the first day counted there is no prev",UseStats.heartbeat(s1,now).isNull("prev"))
        // After midnight (nothing written yet): the heartbeat already shows the day before as prev.
        now=t+3*hour
        val hb=UseStats.heartbeat(s1,now)
        assertEquals("2027-01-20",hb.getJSONObject("today").getString("day"));assertEquals(0,hb.getJSONObject("today").getInt("opens"))
        assertEquals("2027-01-19",hb.getJSONObject("prev").getString("day"));assertEquals(3,hb.getJSONObject("prev").getInt("taskDone"))
        // Counted on the new day; a day with no use at all is a blank prev, not the stale one.
        UseStats.add(UseStats.NOTIF_TAP);UseStats.flush(context,now)
        val s2=UseStats.stored(kr.callbrief.SecureSettings(context).snapshot())
        assertEquals(1,UseStats.heartbeat(s2,now).getJSONObject("today").getInt("notifTap"))
        val later=UseStats.heartbeat(s2,now+2*day)
        assertEquals("2027-01-21",later.getJSONObject("prev").getString("day"));assertEquals(0,later.getJSONObject("prev").getInt("notifTap"))
        // The heartbeat block, as score.py reads it (use.prev.opens, use.prev.taskDone, use.prev.kakaoOpen).
        val o=PhoneStatus.build(base().copy(v37=PhoneStatus.V37(use=hb)))
        val prev=o.getJSONObject("use").getJSONObject("prev")
        for(k in UseStats.KINDS) assertTrue(k,prev.has(k))
        assertEquals(1,prev.getInt("kakaoOpen"));assertEquals("2027-01-19",prev.getString("day"))
    }
    @Test fun countsOfADayBeforePrevAreDropped() {
        val o=UseStats.merge(null,mapOf("2027-01-19" to mapOf(UseStats.OPENS to 2L)),t)
        val next=UseStats.merge(o,mapOf("2027-01-17" to mapOf(UseStats.OPENS to 5L),"2027-01-20" to mapOf(UseStats.OPENS to 1L)),t+day)
        assertEquals(1,next.getJSONObject("today").getInt("opens"));assertEquals(2,next.getJSONObject("prev").getInt("opens"))
        // The card's own count of today.
        assertEquals(4,UseStats.merge(o,emptyMap(),t,cardToday=4).getJSONObject("today").getInt("cardExpand"))
    }

    // ---- 4. report.coverage, 6. dbTables, 7. callLogMatch.day ----
    @Test fun coverageTablesAndMatchDayGoOutAsNumbers() {
        val s=JSONObject().put(PipelineSettingsKeys.REPORT_COVERAGE,JSONObject().put("day","2027-01-19").put("yesterdayCalls",20).put("inReport",17)
            .put("pending",3).put("atMs",t).put("source","routine").toString())
        val cov=kr.callbrief.pipeline.report.ReportCoverage.snapshot(s)
        var days=Telemetry.coverageDays(null,cov)
        (1..9).forEach { d -> days=Telemetry.coverageDays(days,JSONObject().put("day","2027-01-0$d").put("yesterdayCalls",d).put("inReport",d).put("pending",0)) }
        val recent=Telemetry.coverageRecent(days)
        assertEquals(7,recent.length());assertEquals("2027-01-19",recent.getJSONObject(6).getString("day"))
        val sizes=(1..12).map { Triple("table_$it",it*10L,it*1000L) }+Triple("people",3L,0L)
        val tables=Telemetry.dbTables(sizes,null,t)
        val tables2=Telemetry.dbTables(sizes.map { Triple(it.first,it.second,it.third+5) },tables,t+day)
        val stats=JSONObject().put("lastMs",t).put("last",JSONObject().put("noCandidate",4).put("length",2).put("matched",9))
        var md=Telemetry.matchDay(null,stats,t)
        md=Telemetry.matchDay(md,JSONObject().put("lastMs",t+min).put("last",JSONObject().put("noCandidate",1).put("length",3)),t+min)
        assertEquals(2,md!!.getInt("runs"));assertEquals(4,md.getInt("noCandidate"));assertEquals(3,md.getInt("length"))
        assertEquals("the same pass twice is one",2,Telemetry.matchDay(md,JSONObject().put("lastMs",t+min).put("last",JSONObject()),t+min)!!.getInt("runs"))
        assertNull("another day: the old one goes",Telemetry.matchDay(md,null,t+day))
        val o=PhoneStatus.build(base().copy(watch=PhoneStatus.Watch(callLogMatch=stats),
            v35=PhoneStatus.V35(reportDay="2027-01-19"),v37=PhoneStatus.V37(coverage=cov,coverageRecent=recent,dbTables=tables2,matchDay=md)))
        val c=o.getJSONObject("report").getJSONObject("coverage")
        assertEquals(20,c.getInt("yesterdayCalls"));assertEquals(17,c.getInt("inReport"));assertEquals(3,c.getInt("pending"));assertEquals("2027-01-19",c.getString("day"))
        assertEquals(7,o.getJSONObject("report").getJSONArray("recent").length())
        val top=o.getJSONObject("dbTables").getJSONArray("top")
        assertEquals(8,top.length());assertEquals("table_12",top.getJSONObject(0).getString("name"));assertEquals(12_000L,top.getJSONObject(0).getLong("chars"))
        assertEquals(5L,top.getJSONObject(0).getLong("dChars"));assertFalse("every table's count stays on the phone",o.getJSONObject("dbTables").has("all"))
        val day=o.getJSONObject("callLogMatch").getJSONObject("day")
        assertEquals("2027-01-19",day.getString("day"));assertEquals(4,day.getInt("noCandidate"))
        // Nothing known yet: the places are there, empty.
        val empty=PhoneStatus.build(base())
        assertTrue(empty.getJSONObject("report").isNull("coverage"));assertTrue(empty.getJSONObject("use").isNull("prev"))
        assertTrue(empty.isNull("dbTables"));assertEquals(0,empty.getJSONArray("trace").length())
    }

    // ---- the whole heartbeat: no name, no number, small ----
    @Test fun theFullHeartbeatHoldsNoNameOrNumberAndStaysSmall() {
        val rows=(1..60).map { stage(t-it*37*min,if(it%3==0) null else t-it*20*min,text=t-it*30*min,up=t-it*25*min) }
        val names=JSONObject().put("noCandidate",3).put("김민수 010-1234-5678",7)
        val v=PhoneStatus.V37(latency=JSONObject().put("segments",Telemetry.segments(rows)).put("day",Telemetry.dayLatency(rows,t)).put("over7d",0),
            window14=Telemetry.window14(40,1,0),
            coverage=JSONObject().put("day","2027-01-19").put("yesterdayCalls",20).put("inReport",19).put("pending",1).put("source","김민수01012345678"),
            coverageRecent=JSONArray().put(JSONObject().put("day","010-1234-5678").put("yesterdayCalls",1)),
            use=UseStats.heartbeat(null,t),
            dbTables=Telemetry.dbTables(listOf(Triple("김민수",1L,2L),Triple("t01012345678",1L,2L),Triple("call_records",5L,9L)),null,t),
            matchDay=JSONObject().put("day","김민수").put("noCandidate",2).put("runs",1),trace=Telemetry.trace(rows))
        val o=PhoneStatus.build(base().copy(watch=PhoneStatus.Watch(callLogMatch=JSONObject().put("last",names)),
            v31=PhoneStatus.V31(latency=JSONObject().put("p50Min",30).put("p90Min",90).put("n",9),transcription=JSONObject().put("oldestWaitMin",3)),
            v35=PhoneStatus.V35(reportDay="2027-01-19"),v37=v))
        val text=o.toString()
        assertFalse(text,Regex("[가-힣]").containsMatchIn(text))
        // No string value with six digits in a row (a phone number), whatever was stored.
        fun strings(x:Any?):List<String> = when(x) { is JSONObject -> x.keys().asSequence().flatMap { strings(x.opt(it))+it }.toList()
            is JSONArray -> (0 until x.length()).flatMap { strings(x.opt(it)) };is String -> listOf(x);else -> emptyList() }
        strings(o).forEach { assertFalse(it,Regex("\\d{6}").containsMatchIn(it.replace("-",""))&&!Regex("""\d{4}-\d{2}-\d{2}""").matches(it)) }
        assertTrue("20KB at most: ${text.length}",text.toByteArray().size<=20_000)
        // The scorer's keys (tools/inbox/score.py).
        val lat=o.getJSONObject("latency")
        assertEquals(30,lat.getInt("p50Min"));assertTrue(lat.getJSONObject("day").has("p50Min"));assertTrue(lat.getJSONObject("day").has("p90Min"))
        for(k in listOf("endToText","textToUpload","uploadToApplied")) assertTrue(k,lat.getJSONObject("segments").getJSONObject(k).has("over7d"))
        assertEquals(40,o.getJSONObject("transcription").getJSONObject("window14").getInt("created"))
        assertEquals(3,o.getJSONObject("transcription").getInt("oldestWaitMin"))
        assertEquals(20,o.getJSONObject("report").getJSONObject("coverage").getInt("yesterdayCalls"))
        assertTrue(o.getJSONObject("use").has("prev"));assertEquals(50,o.getJSONArray("trace").length())
        assertEquals(1,o.getJSONObject("dbTables").getJSONArray("top").length())
    }

    // ---- 8. CPU and charge per run; 10. words ----
    @Test fun cpuOfARunGoesIntoTheLedger() {
        val stat="1234 (kr.callbrief) S 1 1234 0 0 -1 4194624 1000 0 0 0 250 50 0 0 20 0 30 0 100 0 0"
        assertEquals(3000L,Telemetry.cpuMsOf(stat,100))
        assertNull(Telemetry.cpuMsOf("garbage",100))
        assertNotNull("this process can be read",Telemetry.processCpuMs())
        var l=PowerStats.noteCpu(null,t,1500,null);l=PowerStats.noteCpu(l,t,500,2_000)
        val d=PowerText.heartbeat(l,false,t).getJSONObject("today")
        assertEquals(2000L,d.getLong("cpuMs"));assertEquals(2L,d.getLong("cpuRuns"));assertEquals(2000L,d.getLong("batteryUah"))
        // Text starts: the first one stays, the oldest go past the limit.
        var s=Telemetry.noteTextStart(null,"a",5);s=Telemetry.noteTextStart(s,"a",9)
        assertEquals(5L,s.getLong("a"))
        (1..Telemetry.TRACE_STARTS+5).forEach { s=Telemetry.noteTextStart(s,"r$it",100L+it) }
        assertEquals(Telemetry.TRACE_STARTS,s.length());assertFalse(s.has("a"))
    }
    @Test fun lightsSayBelowForAnyGroupAndScreenWordsKeepNoPromise() {
        assertEquals("아래 상태와 도움 묶음에서 눌러 주세요.",StatusLights.below("위 상태와 도움 묶음에서 눌러 주세요."))
        assertEquals("통화 화면 위에 카드를 띄울 수 없어요. 아래 통화 카드 묶음에서 허용해 주세요.",
            StatusLights.below("통화 화면 위에 카드를 띄울 수 없어요. 위 통화 카드 묶음에서 허용해 주세요."))
        assertEquals("녹음 폴더를 다시 골라 주세요. 아래 연결 묶음에 있어요.",StatusLights.below("녹음 폴더를 다시 골라 주세요. 위 연결 묶음에 있어요."))
        assertFalse(SettingsScreen.FIRST_PASS_GOING.contains("시간"))
    }

    private fun base()=PhoneStatus.Input(t,"3.7",1061,0,null,0,0,"READY","","",10)
}
