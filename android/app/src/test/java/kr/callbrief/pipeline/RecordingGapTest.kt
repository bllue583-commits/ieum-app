package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import android.provider.CallLog
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.call.CallSession
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** r12-1 (DESIGN_V4 2.1): the recording-gap verdict, who is told and when, the per-call "녹음 없음" notice, the call list's
 * words and the evening title. Generated numbers and times only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordingGapTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val h=3_600_000L;private val min=60_000L
    /** 15:00 KST on 11/5. */
    private val now=LocalDate.of(2026,11,5).atTime(15,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()

    @Before fun setup() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ContactCards.shutdown();ContactCards.clearPending()
        CallFollowupNotice.phoneBookName={ _,_ -> "박지성" }
        AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER);null } }
        SecureSettings(app).update { it.put("treeUri","content://generated/tree") }
    }
    @After fun reset() { CallFollowupNotice.resetForTest();IeumApplication.shutdown();ContactCards.clearPending() }

    private fun settings()=SecureSettings(app).snapshot()
    /** [n] answered calls of 3 minutes, an hour apart, the last one ending [lastEndAgo] before [at]. */
    private fun seedCalls(n:Int,at:Long,lastEndAgo:Long=2*h) {
        IeumApplication.store(app).reconcileCallLog((0 until n).map { i ->
            FirstPass.LogEntry(900_000L+i,"0109%07d".format(i),null,at-lastEndAgo-3*min-i*h,3*min,CallLog.Calls.INCOMING_TYPE) })
    }
    private fun seedRecording(id:String,startedMs:Long) = AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p ->
        p.execute("""INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms)
            VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1,?)""",id,GeneralLocalStore.OWNER,id,"$id.wav",startedMs) }
    /** A good discovery pass that started at [at]. */
    private fun pass(at:Long) {
        SecureSettings(app).update { it.put(AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,at) }
        RecordingGap.afterPass(app,RecordingGap.CHECKED,at,at)
    }

    @Test fun tenCallsNoRecordingsIsNoRecordingAndTheWifeHearsFromTheSecondJudgementOn() {
        seedCalls(10,now)
        pass(now)
        val first=RecordingGap.last(settings())!!
        assertEquals(PipelineSettingsKeys.GAP_NO_RECORDING,first.code)
        assertEquals(10,first.calls);assertEquals(0,first.withFile);assertEquals(0,first.matched)
        // The heartbeat reads the same JSON: counts only, the verdict on top.
        val hb=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.1",1040,0,null,5,6,"READY","","",12).copy(v31=PhoneStatus.V31(recordingGap=JSONObject(settings().getString(PipelineSettingsKeys.RECORDING_GAP)),recordingGapVerdict=first.code)))
        assertEquals("noRecording",hb.getString("recordingGapVerdict"));assertEquals(10,hb.getJSONObject("recordingGap").getInt("calls"))
        // One judgement is not enough: no orange, no banner, no notice.
        assertNull(RecordingGap.confirmed(settings(),now));assertNull(RecordingGap.banner(settings(),now));assertNull(RecordingGap.lightText(settings()))
        assertNull(RecordingGap.noticeDue(settings(),now))
        assertTrue(StatusLights.lights(okLights(now,RecordingGap.confirmed(settings(),now))).all { it.ok })
        // A second one, more than 6 hours later, still with no recordings.
        val later=now+6*h+min
        pass(later)
        val v=RecordingGap.confirmed(settings(),later)!!
        assertEquals(PipelineSettingsKeys.GAP_NO_RECORDING,v.code)
        val light=StatusLights.lights(okLights(later,v)).single { !it.ok }
        assertEquals("status_import",light.tag)
        assertEquals("최근 하루 통화 ${v.calls}건 중 녹음은 0건이에요. 전화 앱 › 설정 › 통화 녹음 › 자동으로 통화 녹음 › 모든 통화를 켜 주세요.",light.words)
        assertNull("the phone's own switch: nothing in the app to scroll to",light.target)
        assertTrue(RecordingGap.banner(settings(),later)!!.text.startsWith("통화 녹음이 꺼져 있는 것 같아요."))
        // The evening self-check: once that day, again the next day, then not until 7 days later.
        val day1=later+3*h
        val due=RecordingGap.noticeDue(settings(),day1)!!
        val notices=SelfCheckNotices.check(selfInput(day1,due))
        assertEquals(listOf(SelfCheckNotices.RECORDING_GAP),notices.map { it.code })
        assertEquals("통화 녹음이 꺼져 있는 것 같아요",notices.single().title)
        SecureSettings(app).update { RecordingGap.rememberNotice(it,day1) }
        assertNull(RecordingGap.noticeDue(settings(),day1+min))
        fun judgedAt(t:Long) { SecureSettings(app).update { s -> RecordingGap.keep(s,RecordingGap.last(s)!!.copy(atMs=t)) } }
        judgedAt(day1+24*h);assertNotNull(RecordingGap.noticeDue(settings(),day1+24*h))
        SecureSettings(app).update { RecordingGap.rememberNotice(it,day1+24*h) }
        for(d in 2..7) { judgedAt(day1+d*24*h);assertNull("day ${d+1}",RecordingGap.noticeDue(settings(),day1+d*24*h)) }
        judgedAt(day1+8*24*h);assertNotNull(RecordingGap.noticeDue(settings(),day1+8*24*h))
        // Recordings come back: the run ends, nothing is said any more.
        SecureSettings(app).update { s -> RecordingGap.keep(s,RecordingGap.Verdict(PipelineSettingsKeys.GAP_OK,atMs=day1+8*24*h)) }
        assertNull(RecordingGap.banner(settings(),day1+8*24*h));assertFalse(settings().has(RecordingGap.BAD_SINCE_KEY))
        assertFalse(settings().has(RecordingGap.NOTICE_FIRST_DAY_KEY))
    }

    @Test fun filesThereButNotPairedIsNoMatchAndTheWifeIsNotTold() {
        seedCalls(5,now)
        // A file inside every call's window; none tied to the call log.
        (0 until 5).forEach { i -> seedRecording("r$i",now-2*h-3*min-i*h+10_000L) }
        pass(now);pass(now+7*h)
        val v=RecordingGap.last(settings())!!
        assertEquals(PipelineSettingsKeys.GAP_NO_MATCH,v.code);assertEquals(5,v.withFile);assertEquals(0,v.matched)
        assertNull(RecordingGap.confirmed(settings(),now+7*h));assertNull(RecordingGap.banner(settings(),now+7*h))
        assertNull(RecordingGap.noticeDue(settings(),now+7*h))
        assertNull(SelfCheckNotices.gapNotice(v))
    }

    @Test fun theRuleInOrder() {
        fun call(i:Int,matched:Boolean=false,dur:Long=3*min,type:Int=1,personal:Boolean=false)=RecordingGap.GapCall(now-3*h-i*h,dur,type,personal,matched)
        val calls=(0 until 10).map { call(it) }
        fun judge(files:List<Long>,c:List<RecordingGap.GapCall> =calls,err:Boolean=false,cut:Boolean=false)=
            RecordingGap.judge(RecordingGap.Facts(c,files,err,cut,now),now).code
        fun fileFor(c:RecordingGap.GapCall)=c.startMs+min
        assertEquals("folder",judge(emptyList(),err=true,cut=true))
        assertEquals("tooMany",judge(emptyList(),cut=true))
        assertEquals("fewer than 3 targets","ok",judge(emptyList(),calls.take(2)))
        // Short, missed, personal calls and those not yet settled before the last pass are no targets.
        assertEquals("ok",judge(emptyList(),listOf(call(0,dur=20_000),call(1,type=3),call(2,personal=true),RecordingGap.GapCall(now-10*min,3*min,1,false,false))))
        assertEquals("noRecording",judge(calls.take(2).map(::fileFor)))
        assertEquals("partial",judge(calls.take(5).map(::fileFor)))
        val paired=calls.mapIndexed { i,c -> c.copy(matched=i<3) }
        assertEquals("noMatch",judge(paired.map(::fileFor),paired))
        val good=calls.map { it.copy(matched=true) }
        assertEquals("ok",judge(good.map(::fileFor),good))
        // The window: 2 minutes before the start to 5 minutes after the end.
        val c=calls[0];val w=RecordingGap.window(c.startMs,c.durationMs)
        assertEquals(c.startMs-2*min,w.first);assertEquals(c.startMs+c.durationMs+5*min,w.last)
        assertTrue(RecordingGap.anyIn(listOf(w.first),w.first,w.last));assertFalse(RecordingGap.anyIn(listOf(w.last+1),w.first,w.last))
        // The same code keeps its "since"; a new code starts it again.
        val one=RecordingGap.judge(RecordingGap.Facts(calls,emptyList(),lastDiscoveryMs=now),now)
        assertEquals(one.sinceMs,RecordingGap.judge(RecordingGap.Facts(calls,emptyList(),lastDiscoveryMs=now+h),now+h,one).sinceMs)
        assertEquals(now+h,RecordingGap.judge(RecordingGap.Facts(calls,emptyList(),true,lastDiscoveryMs=now+h),now+h,one).sinceMs)
    }

    @Test fun theFolderAndTooManyWords() {
        val folder=RecordingGap.Verdict("folder",atMs=now)
        assertEquals("녹음 폴더를 다시 골라 주세요. 위 연결 묶음에 있어요.",RecordingGap.lightWords(folder))
        assertEquals(StatusLights.GROUP_CONNECT,RecordingGap.lightTarget(folder))
        assertTrue(RecordingGap.lightWords(RecordingGap.Verdict("tooMany"))!!.startsWith("녹음 폴더에 파일이 너무 많아요"))
        assertTrue(RecordingGap.lightWords(RecordingGap.Verdict("partial",10,5))!!.startsWith("일부만 녹음되고 있어요. 최근 하루 통화 10건 중 녹음은 5건이에요."))
        // A folder error from the pass itself is judged as such.
        RecordingGap.afterPass(app,"HELD_SOURCE:SELECTED_FOLDER_READ_GRANT_REQUIRED",now,now)
        assertEquals("folder",RecordingGap.last(settings())!!.code)
        // A stopped pass leaves the verdict as it was.
        RecordingGap.afterPass(app,"LOCAL_WORK_STOPPED",now+h,now+h)
        assertEquals(now,RecordingGap.last(settings())!!.atMs)
        listOf("최근 하루","녹음 폴더","일부만").forEach { w -> assertFalse(Regex("[·…]").containsMatchIn(w)) }
    }

    @Test fun aCallWithoutRecordingSaysSoAfterTheNextPass() {
        val manager=app.getSystemService(NotificationManager::class.java)
        fun notices()=manager.activeNotifications.filter { it.notification.channelId==CallFollowupNotice.CHANNEL }
        val t0=now-30*min
        val end=CallSession.CallEnd("010-5555-6666",CallSession.Direction.IN,t0,t0+3*min,true)
        CallFollowupNotice.onCallEnded(app,end)
        assertEquals("끝나면 여기에 요약이 와요",notices().single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // A pass right after the call does not decide yet (the file may still be written).
        RecordingGap.afterPass(app,RecordingGap.CHECKED,end.endedMs+30_000,end.endedMs+30_000)
        assertEquals(CallFollowupNotice.WORKING,CallFollowupNotice.entries(app).single().state)
        // The next good pass finds nothing in the call's window.
        pass(end.endedMs+3*min)
        val n=notices().single().notification
        assertEquals("이 통화는 녹음이 없어 정리할 수 없어요",n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("메모 남기기",n.actions.single().title.toString())
        assertFalse(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("저녁 보고"))
        assertEquals(1L,settings().optLong(PipelineSettingsKeys.FOLLOWUP_NO_RECORDING))
        // Counted once: a later pass does not count it again.
        pass(end.endedMs+20*min);assertEquals(1L,settings().optLong(PipelineSettingsKeys.FOLLOWUP_NO_RECORDING))
        // A call whose recording is there keeps "정리하고 있어요".
        val t1=now-10*min;val end2=end.copy(phoneKey="010-5555-7777",startedMs=t1,endedMs=t1+2*min)
        CallFollowupNotice.onCallEnded(app,end2);seedRecording("rx",t1+5_000)
        pass(end2.endedMs+3*min)
        assertEquals(CallFollowupNotice.WORKING,CallFollowupNotice.entries(app).single { it.endedMs==end2.endedMs }.state)
    }

    @Test fun whileNothingIsRecordedNoNoticePerCallAndLateOnlyPromisesTheReportWithARecord() {
        SecureSettings(app).update { RecordingGap.keep(it,RecordingGap.Verdict(PipelineSettingsKeys.GAP_NO_RECORDING,10,0,0,null,now-h,now-h)) }
        CallFollowupNotice.onCallEnded(app,CallSession.CallEnd("010-5555-6666",CallSession.Direction.IN,now-5*min,now-2*min,true))
        assertTrue(CallFollowupNotice.entries(app).isEmpty())
        val late=CallFollowupNotice.Entry(1,"박지성",null,"h",now-7*h,now-7*h+min,state=CallFollowupNotice.LATE)
        assertEquals("정리가 늦어지고 있어요",CallFollowupNotice.words(late,null).text)
        assertEquals("정리가 늦어지고 있어요, 저녁 보고에 넣어 드릴게요",CallFollowupNotice.words(late.copy(record="rec"),null).text)
    }

    @Test fun theCallListLineWithoutARecord() {
        val e=LoggedCall(1,"01000000000","박정우",now-h,3*min,CallLog.Calls.INCOMING_TYPE,false)
        val ended=e.startMs+e.durationMs
        assertEquals(CallsScreen.TRANSCRIBING,CallsScreen.stateWords(e,now,listOf(e.startMs+min)))
        assertEquals("a job outside the window","녹음 없음",CallsScreen.stateWords(e,now,listOf(ended+6*min)))
        assertEquals("녹음을 찾는 중이에요",CallsScreen.stateWords(e,ended+9*min,emptyList()))
        assertEquals("녹음 없음",CallsScreen.stateWords(e,ended+10*min,emptyList()))
        assertNull(CallsScreen.stateWords(e.copy(personal=true),now,emptyList()))
        assertNull(CallsScreen.stateWords(e.copy(durationMs=0,type=CallLog.Calls.MISSED_TYPE),now,emptyList()))
    }

    @Test fun eveningTitleWithCallsButNoRecording() {
        val m=CallDigestText.evening(emptyList(),0,4)
        assertEquals("오늘 통화 4건, 녹음된 통화 0건",m.title)
        assertEquals("녹음이 없어 정리한 통화가 없어요",m.text)
        // From the store: the call log only, the same count as home.
        val day=LocalDate.of(2026,11,5)
        seedCalls(3,now)
        val store=IeumApplication.store(app)
        assertEquals(CallsScreen.callCount(store.day(day),store.dayLog(day)),3)
        assertEquals("오늘 통화 3건, 녹음된 통화 0건",CallDigestText.evening(store,day).title)
        // With recorded calls the title stays as it was.
        assertEquals("오늘 통화 0건, 새 체크할 일 0개",CallDigestText.evening(emptyList(),0,0).title)
        assertEquals(180_000L,CallDigestPrefetch.TIMEOUT_MS)
    }

    @Test fun theQueueAgeComesFromTheQueueWhenKnown() {
        val q=StatusLights.Queue(2,now-h,0,null)
        assertEquals(now-h,StatusLights.withQueuedSince(q,null).oldestMs)
        assertEquals(now-9*h,StatusLights.withQueuedSince(q,now-9*h).oldestMs)
        assertNull(StatusLights.withQueuedSince(StatusLights.Queue(),now).oldestMs)
    }

    @Test fun updateStuckSelfCheck() {
        val n=SelfCheckNotices.check(selfInput(now,null).copy(updateStuck=true))
        assertEquals("새 버전이 하루 넘게 설치되지 않았어요",n.single().title)
    }

    private fun okLights(at:Long,gap:RecordingGap.Verdict?)=StatusLights.Input(now=at,folderConnected=true,modelState="READY",consent=true,
        lastTranscribedMs=at-h,lastAnalyzedMs=at-h,lastDiscoveryMs=at-min,gap=gap)
    private fun selfInput(at:Long,gap:RecordingGap.Verdict?)=SelfCheckNotices.Input(at,folderChosen=true,folderReadable=true,modelState="READY",
        queued=0,oldestQueuedMs=null,lastTranscribedMs=at-h,sending=true,oldestWaitingMs=null,lastAnalyzedMs=at-h,dbBytes=1024L,gap=gap)
}
