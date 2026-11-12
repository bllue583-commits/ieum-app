package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.call.CallSession
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** v3.4 r14-2 (DESIGN_V4 4.2-4, 4.2-5): the call notices put right (a call going out that was not connected gets nothing, the
 * morning report counts the calls summed up after the evening one, the locked screen says the notice's state), and the lights
 * (the card line, "녹음은 있는데 아직 글자로 바꾸는 중", the analysis routine's missed runs at 2 and 4, the store at 80%).
 * Generated numbers and calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R14TwoTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private val number="010-2222-3333"
    private val t0=System.currentTimeMillis()-3_600_000L
    private val h=3_600_000L;private val min=60_000L
    private fun outCall()=CallSession.CallEnd(number,CallSession.Direction.OUT,t0,t0+120_000,true)
    private fun inCall()=CallSession.CallEnd(number,CallSession.Direction.IN,t0,t0+120_000,true)
    private var length:Long?=null

    @Before fun setup() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ContactCards.shutdown();ContactCards.clearPending()
        CallFollowupNotice.phoneBookName={ _,_ -> "박지성" }
        CallFollowupNotice.logWaitMs=0;CallFollowupNotice.logDuration={ _,_ -> length }
        AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2","rec3").forEach { id ->
                p.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
                    id,GeneralLocalStore.OWNER,id,"$id.wav")
            }
            null } }
    }
    @After fun reset() { CallFollowupNotice.resetForTest();IeumApplication.shutdown();ContactCards.clearPending() }

    private fun notices()=manager.activeNotifications.filter { it.notification.channelId==CallFollowupNotice.CHANNEL }
    private fun title(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun publicTitle(n:Notification)=n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    // ---- 4.2-4 가: a call going out that was not connected gets no notice ----
    @Test fun aCallGoingOutTheLogSaysWasNotConnectedGetsNothing() {
        length=0L
        CallFollowupNotice.onCallEnded(app,outCall())
        assertTrue(notices().isEmpty());assertTrue(CallFollowupNotice.entries(app).isEmpty())
    }
    @Test fun aCallGoingOutWaitsForTheLogThenGoesQuietlyWhenItWasNotConnected() {
        length=null
        CallFollowupNotice.onCallEnded(app,outCall())
        assertTrue("no notice before the log says so",notices().isEmpty())
        assertEquals(CallFollowupNotice.HOLD,CallFollowupNotice.entries(app).single().state)
        length=0L
        CallFollowupNotice.checkNoRecording(app,t0+120_000+3*min,t0+120_000+3*min) { _,_ -> false }
        assertTrue("no '녹음이 없어' either",notices().isEmpty());assertTrue(CallFollowupNotice.entries(app).isEmpty())
        assertEquals(0L,SecureSettings(app).snapshot().optLong(PipelineSettingsKeys.FOLLOWUP_NO_RECORDING,0))
    }
    @Test fun aCallGoingOutTheLogConfirmsGetsItsNotice() {
        length=null
        CallFollowupNotice.onCallEnded(app,outCall())
        length=60_000L
        CallFollowupNotice.checkNoRecording(app,t0+120_000+3*min,t0+120_000+3*min) { _,_ -> true }
        val n=notices().single().notification
        assertEquals("박지성과 통화를 정리하고 있어요",title(n));assertEquals("통화 정리 중",publicTitle(n))
        // Connected at once: the notice comes straight away.
        manager.cancelAll();CallFollowupNotice.sweep(app,Long.MAX_VALUE/2)
        CallFollowupNotice.onCallEnded(app,outCall().copy(endedMs=t0+200_000))
        assertEquals(1,notices().size)
    }
    @Test fun aConnectedCallGoingOutWithoutAFileSaysSo() {
        length=null
        CallFollowupNotice.onCallEnded(app,outCall())
        length=60_000L
        CallFollowupNotice.checkNoRecording(app,t0+120_000+3*min,t0+120_000+3*min) { _,_ -> false }
        val n=notices().single().notification
        assertEquals("이 통화는 녹음이 없어 정리할 수 없어요",text(n))
        // 4.2-4 다: the locked screen says which state, not always "통화 정리 중".
        assertEquals("확인할 통화",publicTitle(n))
    }
    @Test fun anAnsweredCallTheLogSaysWasZeroSecondsGoesQuietly() {
        length=null
        CallFollowupNotice.onCallEnded(app,inCall())
        assertEquals(1,notices().size)
        length=0L
        CallFollowupNotice.checkNoRecording(app,t0+120_000+3*min,t0+120_000+3*min) { _,_ -> false }
        assertTrue(notices().isEmpty());assertTrue(CallFollowupNotice.entries(app).isEmpty())
    }
    @Test fun theLockedScreenWordsFollowTheState() {
        assertEquals("통화 정리 중",CallFollowupNotice.publicTitle(CallFollowupNotice.WORKING))
        assertEquals("통화 정리됨",CallFollowupNotice.publicTitle(CallFollowupNotice.DONE))
        assertEquals("확인할 통화",CallFollowupNotice.publicTitle(CallFollowupNotice.LATE))
        assertEquals("확인할 통화",CallFollowupNotice.publicTitle(CallFollowupNotice.NO_RECORDING))
    }

    // ---- 4.2-4 나: the morning report counts calls summed up after the evening report ----
    private fun store()=IeumApplication.store(app)
    private fun call(recording:String,at:Long,summary:String?):String {
        val t=CallTranscript(recording,1,"a".repeat(64),at,120_000,number,"통화 내용",listOf(CallTranscript.Segment(0,5000,"통화 내용")),"m","b".repeat(64),true)
        store().insert(t);val id=CallRecordStore.recordId(t)
        if(summary!=null) analyze(id,summary)
        return id
    }
    private fun analyze(id:String,summary:String)=store().applyAnalysis(id,ClaudeCallAnalysis(summary,emptyList(),"김민수","탄탄이엔씨",emptyList(),emptyList(),
        counterpart=ClaudeCallAnalysis.Counterpart("김민수","탄탄이엔씨","대표",false)))
    @Test fun theMorningReportSaysHowManyCallsWereSummedUpLate() {
        // Yesterday noon (KST), so every call is on the same day whenever the test runs.
        val today=CallRecordStore.kstDay(System.currentTimeMillis()).minusDays(1)
        val b=today.atTime(12,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val waiting=call("rec1",b,null)
        call("rec2",b+10*min,"견적서를 보내기로 했어요.")
        val eveningAt=b+20*min
        val saved=CallDigestText.eveningWaiting(store().day(today),today,eveningAt)
        assertTrue(saved,saved.contains(waiting));assertFalse("record ids only",saved.contains("2222") || saved.contains("김민수"))
        assertEquals(0,CallDigestText.lateDone(store(),saved))
        analyze(waiting,"단가를 다시 받기로 했어요.")
        val after=call("rec3",b+30*min,"도면을 보내 드렸어요.")
        assertNotNull(after)
        assertEquals(2,CallDigestText.lateDone(store(),saved))
        val m=CallDigestText.withLate(CallDigestText.morning(emptyList(),today),2)
        assertEquals("늦게 정리된 통화 2건",m.lines.last())
        assertEquals(m,CallDigestText.withLate(m,0))
        assertEquals(0,CallDigestText.lateDone(store(),null))
    }

    // ---- 4.2-5: pendingText on the recording light ----
    private fun gapCall(at:Long,matched:Boolean=false)=RecordingGap.GapCall(at,60_000,1,false,matched)
    @Test fun pendingTextThreeWays() {
        val now=System.currentTimeMillis()
        val calls=listOf(gapCall(now-5*h),gapCall(now-4*h),gapCall(now-3*h))
        val files=calls.map { it.startMs+10_000 }
        // Two of the three files still wait for their text: not "no match", but pendingText.
        val pending=RecordingGap.judge(RecordingGap.Facts(calls,files,lastDiscoveryMs=now,pendingTimes=files.take(2)),now)
        assertEquals(RecordingGap.PENDING_TEXT,pending.code);assertEquals(2,pending.pending)
        // Their text failed (or the kind is not supported): no job waits, "no match" as before.
        assertEquals(PipelineSettingsKeys.GAP_NO_MATCH,RecordingGap.judge(RecordingGap.Facts(calls,files,lastDiscoveryMs=now),now).code)
        // Paired with their records: fine.
        assertEquals(PipelineSettingsKeys.GAP_OK,RecordingGap.judge(RecordingGap.Facts(calls.map { it.copy(matched=true) },files,lastDiscoveryMs=now,pendingTimes=files),now).code)
        val s=RecordingGap.keep(JSONObject(),pending)
        assertEquals("녹음은 있는데 아직 글자로 바꾸는 중 2건이에요",RecordingGap.pendingText(s,now))
        assertNull("not told as a problem",RecordingGap.confirmed(s,now+7*h))
        assertNull("stale",RecordingGap.pendingText(s,now+RecordingGap.STALE_MS+h))
        val light=StatusLights.lights(okLights(now).copy(pendingText=RecordingGap.pendingText(s,now))).single { it.tag=="status_import" }
        assertTrue(light.ok);assertEquals("녹음은 있는데 아직 글자로 바꾸는 중 2건이에요",light.words)
        assertEquals(2,RecordingGap.last(s)!!.pending)
    }

    // ---- 4.2-5: the analysis routine's missed runs ----
    private fun kst(day:Int,hour:Int,minute:Int)=LocalDate.of(2026,11,day).atTime(hour,minute).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private fun okLights(at:Long)=StatusLights.Input(now=at,folderConnected=true,modelState="READY",consent=true,
        lastTranscribedMs=at-h,lastAnalyzedMs=at-h,lastDiscoveryMs=at-min)
    @Test fun theAnalysisLightCountsMissedRunsAtTwoAndFour() {
        val crons=ResultChain.crons(JSONObject())
        val ended=kst(5,10,36)
        assertEquals(1,StatusLights.routineMissed(ended,crons,kst(5,12,20)))
        assertEquals(2,StatusLights.routineMissed(ended,crons,kst(5,13,20)))
        assertEquals(4,StatusLights.routineMissed(ended,crons,kst(5,15,20)))
        assertEquals("never reported",0,StatusLights.routineMissed(0,crons,kst(5,15,20)))
        // Before the first morning run the evening's was the last: nothing missed (the cron before v3.7).
        val old=listOf(ResultChain.parseCron("CRON_TZ=Asia/Seoul 34 7-22 * * *")!!)
        assertEquals(0,StatusLights.routineMissed(kst(4,22,37),old,kst(5,7,50)))
        assertEquals(0,StatusLights.routineMissed(kst(4,22,37),old,kst(5,8,10)))
        // v3.7 r17-2 (DESIGN_V7 3.2 M1): 03:34 and 04:34 are runs too; after 04:34 reported, 07:50 misses nothing.
        assertEquals(2,StatusLights.routineMissed(kst(4,22,37),crons,kst(5,7,50)))
        assertEquals(0,StatusLights.routineMissed(kst(5,4,37),crons,kst(5,7,50)))
        fun analyze(missed:Int)=StatusLights.lights(okLights(kst(5,15,20)).copy(routineMissed=missed,routineEndedMs=ended)).single { it.tag=="status_analyze" }
        assertTrue(analyze(1).ok)
        assertFalse(analyze(2).ok);assertEquals("분석이 4시간 안 돌았어요. 관리하는 분께 알려 주세요.",analyze(2).words)
        // The evening self-check speaks from four.
        val self=SelfCheckNotices.Input(kst(5,19,0),true,true,"READY",0,null,kst(5,18,0),true,null,kst(5,18,0),1024L)
        assertFalse(SelfCheckNotices.check(self.copy(routineMissed=3,routineEndedMs=ended)).any { it.code==SelfCheckNotices.ROUTINE_LATE })
        assertEquals("분석이 8시간 안 돌았어요",SelfCheckNotices.check(self.copy(routineMissed=4,routineEndedMs=ended)).single { it.code==SelfCheckNotices.ROUTINE_LATE }.title)
        // From the settings: the phone's own inbox error newer than the report keeps it quiet.
        val s=JSONObject().put(PipelineSettingsKeys.ROUTINE_ENDED_MS,ended)
        assertEquals(4,StatusLights.routineMissed(s,kst(5,15,20)))
        assertEquals(0,StatusLights.routineMissed(s.put(PipelineSettingsKeys.LAST_INBOX_ERROR_MS,ended+h),kst(5,15,20)))
        // The routine's own crons (two a hour) count both.
        val twice=JSONObject().put(PipelineSettingsKeys.ROUTINE_CRONS,JSONArray().put("CRON_TZ=Asia/Seoul 34 7-22 * * *").put("CRON_TZ=Asia/Seoul 4 8-22 * * *").toString())
        assertEquals(4,StatusLights.routineMissed(ended,ResultChain.crons(twice),kst(5,13,20)))
    }
    @Test fun theStoreAtEightyPercentTurnsTheLightAmber() {
        val now=kst(5,15,0)
        assertTrue(StatusLights.lights(okLights(now).copy(dbPercent=79)).all { it.ok })
        val l=StatusLights.lights(okLights(now).copy(dbPercent=80)).single { !it.ok }
        assertEquals("status_analyze",l.tag);assertTrue(l.words,l.words.startsWith("저장 공간이 거의 찼어요"));assertFalse("no number (user 11/13)",l.words.contains("%"))
    }

    // ---- 4.2-5: the card line ----
    @Test fun theCardLineLooksAtTheCallLogTheStandbyBucketAndTheShares() {
        val now=kst(5,15,0)
        fun card(i:StatusLights.Input)=StatusLights.lights(i).single { it.tag=="status_card" }
        assertTrue(card(okLights(now).copy(standbyBucket="EXEMPTED",cardCalls=10,cardNoNumber=1,cardNoCard=2)).ok)
        assertFalse(card(okLights(now).copy(callLog=false)).let { it.ok || !it.words.contains("통화기록") })
        assertFalse(card(okLights(now).copy(standbyBucket="RARE")).let { it.ok || !it.words.contains("제한 없음") })
        assertFalse(card(okLights(now).copy(standbyBucket="ACTIVE")).ok)
        val noNumber=card(okLights(now).copy(cardCalls=10,cardNoNumber=2))
        assertFalse(noNumber.ok);assertEquals("통화 10건 중 2건은 번호를 몰라 카드가 뜨지 않았어요.",noNumber.words)
        assertTrue("too few calls to judge",card(okLights(now).copy(cardCalls=4,cardNoNumber=4)).ok)
        assertFalse(card(okLights(now).copy(cardCalls=10,cardNoCard=6)).ok)
        StatusLights.lights(okLights(now).copy(callLog=false,routineMissed=2,routineEndedMs=now-4*h,dbPercent=90)).forEach { assertFalse(it.words,Regex("[·…]").containsMatchIn(it.words)) }
    }
    @Test fun withPhoneFillsTheNewParts() {
        val s=JSONObject().put(PipelineSettingsKeys.ROUTINE_ENDED_MS,1L)
        val i=StatusLights.withPhone(okLights(kst(5,15,0)),app,s,85)
        assertFalse(i.callLog);assertEquals(85,i.dbPercent);assertEquals(1L,i.routineEndedMs)
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        assertTrue(StatusLights.withPhone(okLights(kst(5,15,0)),app,s,null).callLog)
    }
}
