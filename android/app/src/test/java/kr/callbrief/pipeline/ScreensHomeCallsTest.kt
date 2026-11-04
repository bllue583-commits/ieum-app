package kr.callbrief.pipeline

import android.os.Looper
import android.provider.CallLog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
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
    @After fun reset() { CallUi.applyTheme(app);CallsScreen.clock={ System.currentTimeMillis() } }
    private fun at(h:Int,m:Int)=CallUi.today().atTime(h,m).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private fun home(s:HomeScreen.Snapshot):MainActivity {
        val a=launchMain(Screen.HOME);settle { tag(a,"home_today")!=null || tag(a,"retry_card")!=null };a.home.render(s);return a
    }
    private fun record(state:String,error:String?=null,createdMs:Long=0)=CallRecord("r","rec",at(9,0),60_000,null,null,"말",state,null,null,emptyList(),error,createdMs,null)

    // ---- home ----
    @Test fun homeNumbersSayWhatTheyCountAndTheMemoSaysCallsAreBeingSortedOut() {
        val a=home(HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=3,working=0,newTasks=1,due=0,overdue=0,lastAnalyzedMs=0))
        val all=texts(a)
        listOf("오늘 통화","오늘 생긴 체크","남은 체크할 일","먼저 볼 체크할 일").forEach { assertTrue(it,all.contains(it)) }
        assertFalse(all.contains("새 체크"));assertFalse(all.contains("오늘 체크할 일"))
        assertEquals("오늘 통화 3건을 정리하고 있어요",(tag(a,"home_memos_none") as TextView).text.toString())
        val none=home(HomeScreen.Snapshot(true,true,"READY",0,0,0,0,0,0))
        assertEquals("오늘은 아직 정리된 통화가 없어요",(tag(none,"home_memos_none") as TextView).text.toString())
    }

    @Test fun homeCountsCallsLikeTheCallListAndShowsTodaysChecksFirst() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.HOME)
        settle { tag(a,"home_today")!=null }
        val snap=a.home.read()
        val db=IeumApplication.store(app);val today=CallUi.today()
        val calls=db.day(today);val logs=db.dayLog(today)
        assertEquals(CallsScreen.callCount(calls,logs),snap.calls)
        // The call list's line says the same number.
        assertTrue(CallsScreen.counts(calls,CallsScreen.visibleLogs(calls,logs)).startsWith("통화 ${snap.calls}건"))
        assertTrue("the call-log lines count too",snap.calls>calls.count { it.kind=="통화" })
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

    @Test fun modelStepKeepsSayingItWaitsForWifi() {
        val a=home(HomeScreen.Snapshot(folder=true,consent=true,model="",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0,modelWanted=true))
        assertEquals("Wi-Fi에 연결되면 받기 시작해요. (약 570MB)",(tag(a,"home_model_detail") as TextView).text.toString())
        val coming=home(HomeScreen.Snapshot(folder=true,consent=true,model="DOWNLOADING",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0,modelPercent=42,modelWanted=true))
        assertTrue((tag(coming,"home_model_detail") as TextView).text.toString().startsWith("받는 중이에요 42%"))
        // Pressing it is remembered until the model is ready.
        assertFalse(ModelWish.wanted(app));ModelWish.mark(app);assertTrue(ModelWish.wanted(app));ModelWish.clear(app);assertFalse(ModelWish.wanted(app))
    }

    @Test fun notificationsRefusedTwiceOpenTheNotificationSettings() {
        assertFalse(NotifyAsk.allowed(app))
        NotifyAsk.noteResult(app,false);assertFalse("once refused, Android still asks",NotifyAsk.blocked(app))
        NotifyAsk.noteResult(app,false);assertTrue(NotifyAsk.blocked(app))
        val a=home(HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0,notify=false,notifyBlocked=true))
        val button=tag(a,"home_setup_next") as TextView
        assertEquals("알림 설정 열기",button.text.toString())
        button.performClick()
        val opened=shadowOf(a).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,opened.action)
        assertEquals(app.packageName,opened.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        // Allowed after all: the count starts again.
        NotifyAsk.noteResult(app,true);assertEquals(0,NotifyAsk.refusals(app))
        val asks=home(HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0,notify=false))
        assertEquals("알림 허용하기",(tag(asks,"home_setup_next") as TextView).text.toString())
    }

    // ---- calls ----
    @Test fun rightAfterACallTheLineSaysItIsBeingTurnedIntoText() {
        val now=at(15,0)
        val answered=LoggedCall(1,"01000000000","박정우",now-3_600_000L,134_000,CallLog.Calls.INCOMING_TYPE,false)
        assertTrue(CallsScreen.transcribing(answered,now,folder=true,jobStarts=emptyList()))
        assertFalse("no folder, no job: nothing is coming",CallsScreen.transcribing(answered,now,folder=false,jobStarts=emptyList()))
        assertTrue("a waiting job of that time",CallsScreen.transcribing(answered,now,folder=false,jobStarts=listOf(answered.startMs+60_000L)))
        assertFalse("a job further away",CallsScreen.transcribing(answered,now,folder=false,jobStarts=listOf(answered.startMs+5*60_000L)))
        assertFalse("after 12 hours",CallsScreen.transcribing(answered,answered.startMs+CallsScreen.FRESH_MS+1,folder=true,jobStarts=emptyList()))
        assertFalse("a missed call",CallsScreen.transcribing(answered.copy(durationMs=0,type=CallLog.Calls.MISSED_TYPE),now,true,emptyList()))
        assertFalse("a personal call",CallsScreen.transcribing(answered.copy(personal=true),now,true,emptyList()))
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
        settle { tag(a,"log_transcribing_900002")!=null }
        assertTrue(views(tag(a,"log_transcribing_900002")).filterIsInstance<TextView>().any { it.text.toString()==CallsScreen.TRANSCRIBING })
        assertTrue(texts(a).none { it.contains("녹음 없음") })
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
        tag(a,"calls_prev").performClick()
        // No idling: the new date, the arrows and "불러오는 중이에요" are already there.
        val yesterday=CallUi.today().minusDays(1)
        assertEquals(CallUi.date(yesterday),(tag(a,"today_calls_date") as TextView).text.toString())
        assertNotNull(tag(a,"loading"));assertNull(tag(a,"calls_list"))
        assertTrue(tag(a,"calls_next").isEnabled)
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
