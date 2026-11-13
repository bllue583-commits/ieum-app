package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.call.CallSession
import kr.co.callsummary.core.android.AndroidSqlPort
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

/** r10-2 (design 6.2): one notice per finished work call, "정리하고 있어요" → "정리됐어요" / "늦어지고 있어요", gone after the evening
 * report, and [메모 남기기]. Generated numbers and calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallFollowupNoticeTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private val number="010-2222-3333";private val key="01022223333"
    private val t0=System.currentTimeMillis()-3_600_000L
    private val end get()=CallSession.CallEnd(number,CallSession.Direction.IN,t0,t0+120_000,true)
    private val quote="견적서는 내일까지 메일로 보내드릴게요"

    @Before fun setup() {
        // v4.0 r20-1: these tests check the words and states of every per-call notice, which only "녹음 없음" still posts.
        CallFollowupNotice.shownStates=CallFollowupNotice.ALL_STATES
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ContactCards.shutdown();ContactCards.clearPending()
        CallFollowupNotice.phoneBookName={ _,_ -> null }
        AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec0","rec1","rec2").forEach { id ->
                p.execute("INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
                    id,GeneralLocalStore.OWNER,id,"$id.wav")
            }
            null } }
    }
    @After fun reset() { CallFollowupNotice.resetForTest();NotificationActions.resetForTest();IeumApplication.shutdown();ContactCards.clearPending() }

    private fun store()=IeumApplication.store(app)
    /** Stores one call of [number] at [at] and, with a [summary], applies its result. Returns the record id. */
    private fun call(recording:String,at:Long,summary:String?,vararg tasks:String):String {
        val t=CallTranscript(recording,1,"a".repeat(64),at,120_000,number,"통화 내용",listOf(CallTranscript.Segment(0,5000,"통화 내용")),"m","b".repeat(64),true)
        store().insert(t);val id=CallRecordStore.recordId(t)
        if(summary!=null) store().applyAnalysis(id,ClaudeCallAnalysis(summary,emptyList(),"김민수","탄탄이엔씨",emptyList(),tasks.map { ClaudeCallAnalysis.Task("create",null,it,null,quote) },
            counterpart=ClaudeCallAnalysis.Counterpart("김민수","탄탄이엔씨","대표",false)))
        return id
    }
    /** A known counterpart: an earlier call made the contact card. */
    private fun knownCounterpart() { call("rec0",t0-86_400_000L,"지난주 도면을 받았어요");ContactCards.flushPending(store()) }

    /** The calls' notices (v3.5 r15-0: the bundle's summary, which comes from two notices on, is not one of them). */
    private fun notices()=manager.activeNotifications.filter { it.notification.channelId==CallFollowupNotice.CHANNEL && it.id!=CallFollowupNotice.SUMMARY_ID }
    private fun only()=notices().single()
    private fun title(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun big(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    private fun memo(n:Notification,line:String):Intent {
        val a=n.actions.single();assertEquals("메모 남기기",a.title.toString())
        val input=a.remoteInputs.single();assertEquals(NotificationActions.KEY_MEMO,input.resultKey)
        val i=Intent(shadowOf(a.actionIntent).savedIntent)
        RemoteInput.addResultsToIntent(arrayOf(input),i,Bundle().apply { putCharSequence(NotificationActions.KEY_MEMO,line) })
        return i
    }

    @Test fun workCallGetsOneNoticeThatTurnsIntoTheResult() {
        knownCounterpart()
        CallFollowupNotice.onCallEnded(app,end)
        val first=only()
        assertEquals(CallFollowupNotice.TAG,first.tag);assertEquals(CallFollowupNotice.tempId(key,t0+120_000),first.id)
        assertEquals("김민수 대표와 통화를 정리하고 있어요",title(first.notification))
        assertEquals(CallFollowupNotice.WORKING_WORDS,text(first.notification))
        assertEquals("통화 정리 중",first.notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull("no name on the lock screen",first.notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("메모 남기기",first.notification.actions.single().title.toString())
        val channel=manager.getNotificationChannel(CallFollowupNotice.CHANNEL)
        assertEquals(CallFollowupNotice.CHANNEL_NAME,channel.name.toString());assertNull(channel.sound);assertFalse(channel.shouldVibrate())

        // The record comes, its result is applied: the same notice says what came of it.
        val id=call("rec1",t0+5_000,"견적서를 내일까지 보내기로 했어요. 단가는 지난번과 같아요.","견적서 보내기")
        CallFollowupNotice.onResult(app,id)
        val done=only()
        assertEquals(first.id,done.id)
        assertEquals("김민수 대표와 통화가 정리됐어요",title(done.notification))
        assertEquals("견적서를 내일까지 보내기로 했어요.",text(done.notification))
        assertEquals("견적서를 내일까지 보내기로 했어요.\n체크할 일: 견적서 보내기",big(done.notification))
        val open=shadowOf(done.notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name,open.component!!.className)
        assertEquals(id,open.getStringExtra(MainActivity.EXTRA_CALL_ID));assertEquals("CALLS",open.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertEquals(id,CallFollowupNotice.entries(app).single().record)
        // Nothing about the call or the number in settings beyond the notice's own words.
        val kept=SecureSettings(app).snapshot().optString(CallFollowupNotice.STATE_KEY)
        assertFalse(kept.contains(key) || kept.contains("2222-3333") || kept.contains("통화 내용"))
    }

    @Test fun personalUnknownMissedAndExcludedCallsGetNothing() {
        // A number with no card and no phone-book name.
        CallFollowupNotice.onCallEnded(app,end);assertTrue(notices().isEmpty())
        // Not answered.
        CallFollowupNotice.phoneBookName={ _,_ -> "박지성" }
        CallFollowupNotice.onCallEnded(app,end.copy(answered=false));assertTrue(notices().isEmpty())
        // A personal number.
        store().rememberPersonal(key)
        CallFollowupNotice.onCallEnded(app,end);assertTrue(notices().isEmpty())
        store().forgetPersonal(key)
        // A number the user excluded.
        SecureSettings(app).update { it.put("exclusions",org.json.JSONArray().put(org.json.JSONObject().put("type","phone").put("hash",kr.callbrief.core.PrivacyRules.hash(key)))) }
        CallFollowupNotice.onCallEnded(app,end);assertTrue(notices().isEmpty())
        SecureSettings(app).update { it.remove("exclusions") }
        // The phone book knows the name: a work counterpart.
        CallFollowupNotice.onCallEnded(app,end)
        assertEquals("박지성과 통화를 정리하고 있어요",title(only().notification))
        assertEquals("김민수 대표와",CallFollowupNotice.withParticle("김민수 대표"))
    }

    @Test fun noResultInSixHoursSaysSoThenTheEveningReportTakesItAway() {
        knownCounterpart()
        CallFollowupNotice.onCallEnded(app,end)
        val id=only().id
        CallFollowupNotice.sweep(app,t0+120_000+CallFollowupNotice.LATE_MS-60_000)
        assertEquals(CallFollowupNotice.WORKING_WORDS,text(only().notification))
        CallFollowupNotice.sweep(app,t0+120_000+CallFollowupNotice.LATE_MS+1)
        assertEquals(id,only().id)
        // v3.1 (DESIGN_V4 2.1-3): no record yet, so no promise of the evening report.
        assertEquals("정리가 늦어지고 있어요",text(only().notification))
        assertEquals("김민수 대표와 통화",title(only().notification))
        // A call after the report keeps its notice; the earlier one goes.
        val later=end.copy(phoneKey=number,startedMs=t0+7*3_600_000L,endedMs=t0+7*3_600_000L+60_000)
        CallFollowupNotice.afterEvening(app,t0+7*3_600_000L-1)
        assertTrue(notices().isEmpty());assertTrue(CallFollowupNotice.entries(app).isEmpty())
        CallFollowupNotice.onCallEnded(app,later)
        CallFollowupNotice.sweep(app,t0+7*3_600_000L+120_000)
        assertEquals(CallFollowupNotice.tempId(key,later.endedMs),only().id)
    }

    @Test fun memoFromTheNoticeIsTheUsersOwnCheckItemLinkedToTheCall() {
        knownCounterpart()
        CallFollowupNotice.onCallEnded(app,end)
        val press=memo(only().notification,"  도면 다시 보내 달라고 하기 ")
        assertEquals(NotificationActionReceiver::class.java.name,press.component!!.className)
        assertEquals(NotificationActions.ACTION_MEMO,press.action)
        assertTrue(NotificationActions.handle(app,press))
        val item=store().openItems().single { it.title=="도면 다시 보내 달라고 하기" }
        assertEquals("탄탄이엔씨",item.company)
        assertEquals("user",store().cardRows("SELECT source FROM workflow_items WHERE id=?",item.id).single()["source"])
        assertTrue(big(only().notification).contains("메모 1개를 체크할 일에 넣었어요"))
        // An empty line keeps nothing.
        assertFalse(NotificationActions.handle(app,memo(only().notification,"  ")))
        // The record comes: the memo is bound to its call, and the result does not touch it.
        val id=call("rec1",t0+5_000,"견적서를 내일까지 보내기로 했어요.","견적서 보내기")
        CallFollowupNotice.onResult(app,id)
        assertEquals(id,store().cardRows("SELECT record_id FROM call_task_bindings WHERE item_id=?",item.id).single()["record_id"])
        // A memo on the finished notice is linked at once.
        val second=memo(only().notification,"단가표 받기");assertEquals(id,second.getStringExtra(NotificationActions.EXTRA_RECORD))
        assertTrue(NotificationActions.handle(app,second))
        val item2=store().openItems().single { it.title=="단가표 받기" }
        assertEquals(id,store().cardRows("SELECT record_id FROM call_task_bindings WHERE item_id=?",item2.id).single()["record_id"])
        assertTrue(big(only().notification).contains("메모 2개"))

        // The check tab shows it.
        val a=launchMain(Screen.TASKS)
        repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(a.window.decorView.findViewWithTag<View>("task_${item.id}")!=null) return;Thread.sleep(10) }
        fail("the memo is not on the check tab")
    }

    @Test fun switchOffMeansNoNotices() {
        knownCounterpart()
        CallFollowupNotice.onCallEnded(app,end);assertEquals(1,notices().size)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.FOLLOWUP_NOTICE,false) }
        CallFollowupNotice.sweep(app);assertTrue(notices().isEmpty())
        CallFollowupNotice.onCallEnded(app,end.copy(endedMs=t0+300_000));assertTrue(notices().isEmpty())
        // The result still reaches the store (the home screen), only without a notice.
        val id=call("rec1",t0+5_000,"견적서를 내일까지 보내기로 했어요.")
        CallFollowupNotice.onResult(app,id);assertTrue(notices().isEmpty())
        assertEquals(CallRecordStore.ANALYZED,store().record(id)!!.state)
    }

    @Test fun aPersonalResultTakesTheNoticeAway() {
        CallFollowupNotice.phoneBookName={ _,_ -> "박지성" }
        CallFollowupNotice.onCallEnded(app,end);assertEquals(1,notices().size)
        val id=call("rec2",t0+5_000,null)
        store().keepPersonal(id)
        CallFollowupNotice.onResult(app,id)
        assertTrue(notices().isEmpty());assertTrue(CallFollowupNotice.entries(app).isEmpty())
    }

    // ---- v4.0 r20-1 (DESIGN_V7 6): the per-call notices fold into the evening one; only "녹음 없음" stays ----
    @Test fun threeCallsGiveNoNoticeOfTheirOwnTheEveningOneTellsThemAndNoRecordingStays() {
        CallFollowupNotice.shownStates=CallFollowupNotice.SHOWN
        knownCounterpart()
        val hour=3_600_000L
        val ends=listOf(end,end.copy(startedMs=t0+10*60_000,endedMs=t0+12*60_000),end.copy(startedMs=t0+2*hour,endedMs=t0+2*hour+60_000))
        ends.forEach { CallFollowupNotice.onCallEnded(app,it) }
        assertTrue("no notice right after a call",notices().isEmpty())
        assertEquals(3,CallFollowupNotice.entries(app).size)
        // Two of them get their recording and their result: still nothing on the phone.
        val a=call("rec1",t0+5_000,"견적서를 내일까지 보내기로 했어요.","견적서 보내기")
        val b=call("rec2",t0+10*60_000+5_000,"도면을 다시 받기로 했어요.")
        CallFollowupNotice.onResult(app,a);CallFollowupNotice.onResult(app,b)
        assertTrue("done calls are not told one by one",notices().isEmpty())
        // The third has no recording: that one is told at once, with [메모 남기기] (nothing else will ever tell it).
        val third=ends[2]
        CallFollowupNotice.checkNoRecording(app,third.endedMs+3*60_000,third.endedMs+3*60_000) { _,_ -> false }
        val n=only().notification
        assertEquals("이 통화는 녹음이 없어 정리할 수 없어요",text(n))
        assertEquals("메모 남기기",n.actions.single().title.toString())
        assertEquals(CallFollowupNotice.CHANNEL_NAME,manager.getNotificationChannel(CallFollowupNotice.CHANNEL).name.toString())
        // Later passes leave it as it is (and tell no other call).
        CallFollowupNotice.sweep(app,third.endedMs+CallFollowupNotice.LATE_MS-60_000)
        assertEquals(1,notices().size)
        // The evening report is the one notice that tells the day's calls.
        val m=CallDigestText.evening(store(),CallRecordStore.kstDay(t0+5_000))
        val day=CallRecordStore.kstDay(t0+5_000)
        assertEquals(listOf(t0+5_000,t0+10*60_000+5_000).count { CallRecordStore.kstDay(it)==day },
            CallDigestText.evening(store(),day).lines.count { !it.startsWith("외 ") })
        assertTrue(m.title,m.title.startsWith("녹음 "))
        // After it went out the no-recording notice goes too.
        CallFollowupNotice.afterEvening(app,third.endedMs+hour)
        assertTrue(notices().isEmpty())
    }

    @Test fun aNoticeLeftUpByTheOldVersionIsTakenAwayAtTheNextChange() {
        knownCounterpart()
        CallFollowupNotice.onCallEnded(app,end);assertEquals(1,notices().size)
        CallFollowupNotice.shownStates=CallFollowupNotice.SHOWN
        val id=call("rec1",t0+5_000,"견적서를 내일까지 보내기로 했어요.")
        CallFollowupNotice.onResult(app,id)
        assertTrue(notices().isEmpty())
        assertEquals(CallFollowupNotice.DONE,CallFollowupNotice.entries(app).single().state)
    }
}
