package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
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

/** r8-6: the morning report's [다 됐어요] button, and where each report opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationActionsTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private fun morning()=shadowOf(manager).allNotifications.single { it.channelId==CallDigestWork.CHANNEL && it.extras.getCharSequence(Notification.EXTRA_TITLE)!!.startsWith("체크할 일") }
    private fun text(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun press(n:Notification):Intent { assertEquals(1,n.actions.size);assertEquals("다 됐어요",n.actions[0].title.toString());return Intent(shadowOf(n.actions[0].actionIntent).savedIntent) }
    private fun item(id:String,title:String,created:Long)=CallTodoItem(id,title,"","open",3,"가나건설",created)

    private val open=mutableListOf<CallTodoItem>()
    private val completed=mutableListOf<String>()

    @Before fun fake() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        open.clear();completed.clear()
        open+=listOf(item("a","견적서 보내기",2_000),item("b","도면 다시 받기",1_000)) // v3.5 r15-3: the latest request leads (C-12)
        NotificationActions.read={ _ -> open.toList() to emptyList() }
        TaskActions.completeIo={ _,it -> completed+=it.id+":"+it.revision;open.removeAll { o -> o.id==it.id } }
    }
    @After fun reset() { NotificationActions.resetForTest();TaskActions.resetIo();IeumApplication.shutdown() }

    @Test fun pressFinishesTheFirstItemThenTheNextThenLeavesNoButton() {
        CallDigestNotifier.post(app,CallDigestSchedule.MORNING,CallDigestText.morning(open,CallUi.today()))
        var n=morning();assertTrue(text(n),text(n).contains("견적서 보내기"))
        val first=press(n)
        assertEquals(NotificationActionReceiver::class.java.name,first.component!!.className)
        assertEquals("a",first.getStringExtra(NotificationActions.EXTRA_ITEM))
        assertTrue(NotificationActions.handle(app,first))
        assertEquals(listOf("a:3"),completed)
        // Updated in place: one morning report, now naming the next item, still with a button.
        n=morning();assertTrue(text(n),text(n).contains("도면 다시 받기"));assertEquals("체크할 일 1개",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(NotificationActions.handle(app,press(n)))
        n=morning();assertEquals("체크할 일을 다 끝냈어요",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("남은 체크할 일이 없어요",text(n));assertTrue("no button after the last item",n.actions==null || n.actions.isEmpty())
        assertEquals(listOf("a:3","b:3"),completed)
        assertEquals(1,manager.activeNotifications.size)
    }

    @Test fun aSecondPressOfTheSameButtonDoesNothingMore() {
        CallDigestNotifier.post(app,CallDigestSchedule.MORNING,CallDigestText.morning(open,CallUi.today()))
        val i=press(morning())
        assertTrue(NotificationActions.handle(app,i))
        assertFalse(NotificationActions.handle(app,i))
        // A press from an old copy whose item is already done (in the app) writes nothing either.
        open.add(0,item("c","전화 다시 하기",500).copy(revision=9))
        assertFalse(NotificationActions.handle(app,NotificationActions.doneIntent(app,"c",8)))
        assertEquals(listOf("a:3"),completed)
    }

    @Test fun aFailedWriteKeepsTheButtonForAnotherTry() {
        TaskActions.completeIo={ _,_ -> error("disk") }
        CallDigestNotifier.post(app,CallDigestSchedule.MORNING,CallDigestText.morning(open,CallUi.today()))
        val i=press(morning())
        assertFalse(NotificationActions.handle(app,i))
        assertTrue(text(morning()).contains("견적서 보내기"));press(morning())
        TaskActions.completeIo={ _,it -> completed+=it.id;open.removeAll { o -> o.id==it.id } }
        assertTrue("the same button again, after the failure",NotificationActions.handle(app,i))
    }

    @Test fun reportsOpenTheOneActivityWithTheirScreenAndDay() {
        val day=CallUi.today().minusDays(1).atTime(12,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        CallDigestNotifier.post(app,CallDigestSchedule.EVENING,CallDigestText.Message("어제 통화","본문"),day)
        CallDigestNotifier.post(app,CallDigestSchedule.MORNING,CallDigestText.morning(open,CallUi.today()))
        CallDigestNotifier.post(app,"call",CallDigestText.Message("통화 정리","본문"),day,callId="rec1")
        fun opens(title:String)=shadowOf(shadowOf(manager).allNotifications.single { it.extras.getCharSequence(Notification.EXTRA_TITLE)==title }.contentIntent).savedIntent
        val e=opens("어제 통화");val m=opens("체크할 일 2개");val c=opens("통화 정리")
        listOf(e,m,c).forEach { assertEquals(MainActivity::class.java.name,it.component!!.className) }
        assertEquals("CALLS",e.getStringExtra(MainActivity.EXTRA_SCREEN));assertEquals(day,e.getLongExtra(MainActivity.EXTRA_DAY_MS,0))
        assertEquals("TASKS",m.getStringExtra(MainActivity.EXTRA_SCREEN));assertFalse(m.hasExtra(MainActivity.EXTRA_DAY_MS))
        assertEquals("rec1",c.getStringExtra(MainActivity.EXTRA_CALL_ID));assertEquals(day,c.getLongExtra(MainActivity.EXTRA_DAY_MS,0))
        // The evening report has no button.
        assertTrue(shadowOf(manager).allNotifications.single { it.extras.getCharSequence(Notification.EXTRA_TITLE)=="어제 통화" }.actions.isNullOrEmpty())
    }

    @Test fun receiverIsRegisteredAndNotExported() {
        val info=app.packageManager.getReceiverInfo(android.content.ComponentName(app,NotificationActionReceiver::class.java),0)
        assertFalse(info.exported)
    }

    /** With the real store: the button finishes the seeded oldest item and the report then names another. */
    @Test fun realStoreItemIsFinishedFromTheNotification() {
        NotificationActions.resetForTest();TaskActions.resetIo()
        PreviewSeed.ensure(app)
        val store=IeumApplication.store(app)
        val before=store.openTodos();assertTrue(before.size>=2)
        CallDigestNotifier.post(app,CallDigestSchedule.MORNING,CallDigestText.morning(store,CallUi.today()))
        val i=press(morning());val id=i.getStringExtra(NotificationActions.EXTRA_ITEM)
        assertEquals(CallDigestText.ranked(before,CallUi.today()).first().id,id)
        assertTrue(NotificationActions.handle(app,i))
        val after=IeumApplication.store(app).openTodos()
        assertFalse(after.any { it.id==id });assertEquals(before.size-1,after.size)
        assertEquals("체크할 일 ${after.size}개",morning().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotEquals(id,press(morning()).getStringExtra(NotificationActions.EXTRA_ITEM))
    }
}
