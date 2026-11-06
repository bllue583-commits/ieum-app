package kr.callbrief.pipeline

import android.os.Looper
import android.provider.CallLog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Duration

/** v2.7 r8-4 home and calls: the number labels and the short check list, home counting calls as the call list does, the
 * three states right after a call, a recording with no speech, the day switcher answering at once, the search kept after a
 * detail page, the model step waiting for Wi-Fi, and the notification step after two refusals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScreensHomeCallsTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun tagged(a:android.app.Activity,prefix:String)=views(a.window.decorView).filter { (it.tag as? String)?.startsWith(prefix)==true }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun back(a:MainActivity) { a.onBackPressedDispatcher.onBackPressed();shadowOf(Looper.getMainLooper()).idle() }
    @After fun reset() { CallUi.applyTheme(app);CallsScreen.clock={ System.currentTimeMillis() };CallsScreen.readHook={} }
    private fun at(h:Int,m:Int)=CallUi.today().atTime(h,m).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private fun home(s:HomeScreen.Snapshot):MainActivity {
        val a=launchMain(Screen.HOME);settle { tag(a,"home_today")!=null || tag(a,"retry_card")!=null };a.home.render(s);return a
    }
    private fun record(state:String,error:String?=null,createdMs:Long=0)=CallRecord("r","rec",at(9,0),60_000,null,null,"말",state,null,null,emptyList(),error,createdMs,null)

    // ---- home ----
    @Test fun homeNumbersSayWhatTheyCountAndTheMemoSaysCallsAreBeingSortedOut() {
        val a=home(HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=3,working=0,newTasks=1,due=0,overdue=0,lastAnalyzedMs=0))
        val all=texts(a)
        listOf("오늘 녹음","오늘 생긴 체크","남은 체크할 일","먼저 볼 체크할 일","녹음 3개 중 정리 0개").forEach { assertTrue(it,all.contains(it)) }
        assertFalse(all.contains("새 체크"));assertFalse(all.contains("오늘 체크할 일"))
        assertEquals("오늘 녹음 3개를 정리하고 있어요",(tag(a,"home_memos_none") as TextView).text.toString())
        val none=home(HomeScreen.Snapshot(true,true,"READY",0,0,0,0,0,0))
        assertEquals("오늘은 아직 정리된 통화가 없어요",(tag(none,"home_story_none") as TextView).text.toString())
    }

    @Test fun homeCountsCallsLikeTheCallListAndShowsTodaysChecksFirst() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.HOME)
        settle { tag(a,"home_today")!=null }
        val snap=a.home.read()
        val db=IeumApplication.store(app);val today=CallUi.today()
        val calls=db.day(today)
        // v3.5 r15-0 (user 11/6): home counts recordings (the call records and the recordings still waiting for their text), not
        // the call log's lines; the summed-up ones are said beside them.
        val waiting=CallTranscriptionStore.withoutRecord(today) { sql,args -> db.cardRows(sql,*args) }
        assertEquals(calls.count { it.kind=="통화" }+waiting,snap.calls)
        assertEquals(TodayStory.summarized(calls),snap.summarized)
        // The short list: asked for today or asking for a check, the checks first, at most five.
        assertTrue(snap.tasks.isNotEmpty())
        assertTrue(snap.tasks.all { it.status=="needs_confirmation" || CallRecordStore.kstDay(it.createdMs)==today })
        val open=db.openItems()
        assertTrue(open.size>snap.tasks.size)
        settle { tagged(a,"home_task_").any { it.tag!="home_task_mix" } }
        val shown=tagged(a,"home_task_").filter { it.tag!="home_task_mix" }
        assertEquals(minOf(HomeScreen.FIRST_TASKS,snap.tasks.size),shown.size)
        val more=open.size-shown.size
        assertTrue(texts(a).contains("외 ${more}개 보기 ›"))
    }

    @Test fun modelLineNeverWaitsForWifi() {
        // v3.1 (user 11/5 18:27): the model comes on any connection; the line shows the percent and the sizes, never "Wi-Fi".
        // r12-6: it is in settings, not on home (the model only coming is nothing to do, so home has no line for it).
        SecureSettings(app).update { it.put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.MODEL_STATE,"") }
        val a=launchMain(Screen.SETTINGS);settle { tag(a,"model_state")!=null }
        assertEquals("음성 인식 모델을 받기 시작해요 (574MB)",(tag(a,"model_state") as TextView).text.toString())
        assertEquals("음성 인식 모델 받는 중 42% (241MB / 574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,241_100_000L))
        val coming=home(HomeScreen.Snapshot(folder=true,consent=true,model="DOWNLOADING",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0,modelPercent=42,modelBytes=241_100_000L))
        assertNull(tag(coming,"home_model"));assertNull(tag(coming,"home_attention"))
        assertTrue(texts(coming).none { it.contains("Wi-Fi") || it.contains("음성 인식 모델") })
    }

    @Test fun notificationsRefusedTwiceOpenTheNotificationSettings() {
        assertFalse(NotifyAsk.allowed(app))
        NotifyAsk.noteResult(app,false);assertFalse("once refused, Android still asks",NotifyAsk.blocked(app))
        NotifyAsk.noteResult(app,false);assertTrue(NotifyAsk.blocked(app))
        // r12-6: first setup is in settings; with folder, consent, call log, call state and battery done, notifications are next.
        SecureSettings(app).update { it.put("treeUri","content://host/tree/call").put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.MODEL_STATE,"READY") }
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALL_LOG,android.Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName,true)
        val a=launchMain(Screen.SETTINGS);settle { tag(a,"setup_next")!=null }
        val button=tag(a,"setup_next") as TextView
        assertEquals("알림 설정 열기",button.text.toString())
        button.performClick()
        val opened=shadowOf(a).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,opened.action)
        assertEquals(app.packageName,opened.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        // Allowed after all: the count starts again.
        NotifyAsk.noteResult(app,true);assertEquals(0,NotifyAsk.refusals(app))
        val asks=launchMain(Screen.SETTINGS);settle { tag(asks,"setup_next")!=null }
        assertEquals("알림 허용하기",(tag(asks,"setup_next") as TextView).text.toString())
    }

    // ---- calls ----
    @Test fun rightAfterACallTheLineSaysItIsBeingTurnedIntoText() {
        val now=at(15,0)
        val answered=LoggedCall(1,"01000000000","박정우",now-3_600_000L,134_000,CallLog.Calls.INCOMING_TYPE,false)
        // Then the record: being sorted out, and after six hours late.
        assertEquals("정리하고 있어요",CallsScreen.stateWords(record(CallRecordStore.TRANSCRIBED,createdMs=now-3_600_000L),now))
        assertEquals("정리가 늦어지고 있어요",CallsScreen.stateWords(record(CallRecordStore.TRANSCRIBED,createdMs=now-7*3_600_000L),now))
        // A record stored and not yet tied to its call-log line is one line, not two.
        val rec=record(CallRecordStore.TRANSCRIBED,createdMs=now).copy(startedMs=answered.startMs+30_000L)
        assertTrue(CallsScreen.visibleLogs(listOf(rec),listOf(answered)).isEmpty())
        assertEquals(1,CallsScreen.callCount(listOf(rec),listOf(answered)))
        // On the list: the seeded answered call of 13:45 seen an hour later.
        PreviewSeed.ensure(app)
        CallsScreen.clock={ at(13,45)+3_600_000L }
        val a=launchMain(Screen.CALLS)
        // v3.1 (DESIGN_V4 2.1-4): an hour later with no transcription job in the call's window it says so, not "being turned into text".
        settle { tag(a,"log_no_recording_900002")!=null }
        assertTrue(views(tag(a,"log_no_recording_900002")).filterIsInstance<TextView>().any { it.text.toString().endsWith(CallsScreen.NO_RECORDING) })
        assertTrue(texts(a).none { it==CallsScreen.TRANSCRIBING })
        // The words: a job in the window, then being looked for up to 10 minutes after the end, then none.
        assertEquals(CallsScreen.TRANSCRIBING,CallsScreen.stateWords(answered,now,listOf(answered.startMs+60_000L)))
        val end=answered.startMs+answered.durationMs
        assertEquals(CallsScreen.SEARCHING,CallsScreen.stateWords(answered,end+5*60_000L,emptyList()))
        assertEquals(CallsScreen.NO_RECORDING,CallsScreen.stateWords(answered,end+11*60_000L,emptyList()))
    }

    @Test fun aRecordingWithNoSpeechSaysSoAndHasNoRetryButton() {
        PreviewSeed.ensure(app)
        val db=IeumApplication.store(app)
        val (empty,other)=db.day(CallUi.today()).filter { it.kind=="통화" }.take(2)
        db.setState(empty.recordId,CallRecordStore.FAILED,"EMPTY_TRANSCRIPT")
        db.setState(other.recordId,CallRecordStore.FAILED,"SOMETHING_ELSE")
        assertFalse(CallsScreen.canRetry(db.record(empty.recordId)!!));assertTrue(CallsScreen.canRetry(db.record(other.recordId)!!))
        val a=launchMain(Screen.CALLS,empty.dayMs,empty.recordId)
        settle { tag(a,"call_state_text")!=null }
        assertEquals(CallsScreen.NO_SPEECH,(tag(a,"call_state_text") as TextView).text.toString())
        assertNull(tag(a,"call_retry"));assertTrue(texts(a).none { it=="다시 정리하기" })
        val b=launchMain(Screen.CALLS,other.dayMs,other.recordId)
        settle { tag(b,"call_state_text")!=null }
        assertEquals("정리하지 못했어요",(tag(b,"call_state_text") as TextView).text.toString())
        assertNotNull(tag(b,"call_retry"))
    }

    @Test fun theDaySwitcherChangesTheDateAtOnce() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"calls_list")!=null }
        // The read of the new day is held until the screen has been looked at (the hook runs on the IO thread).
        val gate=java.util.concurrent.CountDownLatch(1);CallsScreen.readHook={ gate.await(5,java.util.concurrent.TimeUnit.SECONDS) }
        try {
            tag(a,"calls_prev").performClick()
            // No idling: the new date, the arrows and "불러오는 중이에요" are already there.
            val yesterday=CallUi.today().minusDays(1)
            assertEquals(CallUi.date(yesterday),(tag(a,"today_calls_date") as TextView).text.toString())
            assertNotNull(tag(a,"loading"));assertNull(tag(a,"calls_list"))
            assertTrue(tag(a,"calls_next").isEnabled)
        } finally { gate.countDown();CallsScreen.readHook={} }
        settle { tag(a,"loading")==null && tagged(a,"call_").isNotEmpty() }
        assertEquals("어제의 통화",(tag(a,"page_title") as? TextView)?.text?.toString())
    }

    @Test fun theSearchIsKeptAfterADetailPage() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"calls_search")!=null && tag(a,"calls_list")!=null }
        val box=tag(a,"calls_search") as EditText
        box.setText("철강")
        // Nothing searched while typing; after 250 ms the matches.
        assertNotNull(tag(a,"calls_list"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallsScreen.SEARCH_WAIT_MS+50))
        settle { tag(a,"calls_search_wait")==null && tagged(a,"call_").isNotEmpty() && tag(a,"calls_list")==null }
        val hits=tagged(a,"call_").filter { it is ViewGroup }.size
        tagged(a,"call_").first { it is ViewGroup }.performClick()
        settle { texts(a).contains("요약") || tag(a,"call_state")!=null }
        assertNotNull(a.detail)
        back(a)
        settle { tag(a,"calls_search")!=null && tagged(a,"call_").isNotEmpty() }
        assertNull(a.detail)
        assertEquals("철강",(tag(a,"calls_search") as EditText).text.toString())
        assertEquals(hits,tagged(a,"call_").filter { it is ViewGroup }.size)
        // The precomputed words match like the shared rule (spaces ignored, every word needed).
        val c=IeumApplication.store(app).day(CallUi.today()).first { it.analysis?.counterpartCompany=="△△철강" }
        assertTrue(a.calls.matches(c,"철강 김민수"));assertTrue(a.calls.matches(c,"△△ 철강"));assertFalse(a.calls.matches(c,"철강 박지훈"))
    }
}
