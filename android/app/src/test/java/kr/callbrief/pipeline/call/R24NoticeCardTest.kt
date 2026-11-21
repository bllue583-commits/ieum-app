package kr.callbrief.pipeline.call

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.WindowManager
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import kr.callbrief.pipeline.MainActivity
import kr.callbrief.pipeline.Screen
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/** v4.3 r24-1 (DESIGN_V8 6-3): where the card may not be drawn (the overlay check says no as the call starts) the notice is the
 * card: at once, in the card's order, silent, taken away when the call ends, the lock screen's public version as before, and a
 * tap opens the person's page (else the check list) and is counted. Where the card is drawn there is no second notice.
 * Generated numbers, names and cards only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R24NoticeCardTest:ProtectedTextHostTestBase() {
    private val app:android.app.Application get()=RuntimeEnvironment.getApplication()
    private val wm get()=app.getSystemService(WindowManager::class.java)
    private val windows get()=Shadow.extract<ShadowWindowManagerImpl>(wm).views.filterIsInstance<CallCardView>()
    private val nm get()=shadowOf(app.getSystemService(NotificationManager::class.java))
    private val notices get()=nm.allNotifications.filter { it.channelId==CallCardOverlay.CHANNEL }
    private val number="010-4444-5555";private val key="01044445555"
    private val now=System.currentTimeMillis()
    private val day=86_400_000L
    private val verdicts=mutableListOf<CardStats.Attempt>()
    private val noticeAt=mutableListOf<Long>()
    private var draw=false
    private var elapsed=1_000_000L

    private fun card(tasks:List<ContactCard.Task>,people:List<ContactCard.Person> =listOf(ContactCard.Person("pe_7","박하늘","과장",false)),
                     recent:List<ContactCard.Recent> =listOf(ContactCard.Recent(now-3*day,"통화","자재 단가는 지난번과 같게 하기로 했어요.")))=
        ContactCard(key,people,ContactCard.Company("co_7","가나건설","골조"),now-3*day,"in",recent,tasks,emptyList(),emptyList(),now)
    private fun task(id:String,title:String,ago:Long,due:Long?=null,tentative:Boolean=false)=ContactCard.Task(id,title,now-ago*day,"open",null,tentative=tentative,dueMs=due)

    @Before fun setup() {
        CallSession.resetForHost();CallCardOverlay.resetForHost();CardStats.resetForHost()
        CallSession.isPrivate={ _,_ -> false };CallSession.afterCall={ true }
        CallCardOverlay.canDraw={ draw };CallCardOverlay.locked={ false };CallCardOverlay.serviceUp={ false }
        CallCardOverlay.outcome={ _,_ -> };CallCardOverlay.onExpanded={};CallCardOverlay.onClosed={ _,_ -> }
        CallCardOverlay.drawnNow={ true };CallCardOverlay.probe={ CardStats.Probe(2,4242,30,false,false,null) }
        CallCardOverlay.onVerdict={ _,a -> synchronized(verdicts) { verdicts+=a } };CallCardOverlay.onFailure={ _,_ -> }
        CallCardOverlay.screenOn={ true };CallCardOverlay.lastExit={ null }
        CallCardOverlay.onNotice={ synchronized(noticeAt) { noticeAt+=CallCardOverlay.clock() } }
        CallCardOverlay.clock={ elapsed }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ContactCards.shutdown();ContactCards.clearPending()
        ContactCards.write(app,mapOf(key to card(listOf(task("wf_1","도면 다시 받기",1),task("wf_2","현장 사진 보내기",2),
            task("wf_3","대금 120만원 입금 확인",0,due=now+day,tentative=true),task("wf_4","타설 일정 묻기",4),task("wf_5","장비 반납",5)))),replaceAll=true)
    }
    @After fun reset() { CallCardOverlay.resetForHost();CallSession.resetForHost();ContactCards.shutdown();ContactCards.clearPending();CardStats.resetForHost() }

    private fun settle(ms:Long=0) { CallCardOverlay.awaitIdle();shadowOf(Looper.getMainLooper()).idle();if(ms>0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));CallCardOverlay.awaitIdle() }
    private fun ring() { CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,number);CallCardOverlay.show(app,number,CallSession.State.RINGING) }
    private fun idle() { CallCardOverlay.show(app,null,CallSession.State.IDLE);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"");settle() }
    private fun title(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun line(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun big(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().lines()

    @Test fun deniedAtTheStartTheNoticeComesAtOnceWithoutAWindowOrALaterTry() {
        var tried=0
        CallCardOverlay.addWindow={ w,v,p -> tried++;w.addView(v,p) }
        ring();settle() // nothing waits on the main thread's clock
        assertEquals(1,notices.size);assertTrue(windows.isEmpty());assertEquals(0,tried)
        assertEquals("posted with no delay of the card's own",listOf(elapsed),synchronized(noticeAt) { noticeAt.toList() })
        assertEquals(CallCardOverlay.CAUSE_DENIED,synchronized(verdicts) { verdicts.single() }.cause)
        settle(CallCardOverlay.RETRY_DELAYS_MS.sum()+CallCardOverlay.RETRY_HIDDEN_MS*3)
        assertEquals(0,tried);assertEquals("one notice, told once",1,synchronized(noticeAt) { noticeAt.size })
        // Silent, a heads-up channel, its own slot without the service.
        val ch=app.getSystemService(NotificationManager::class.java).getNotificationChannel(CallCardOverlay.CHANNEL)
        assertNull(ch.sound);assertFalse(ch.shouldVibrate());assertEquals(NotificationManager.IMPORTANCE_HIGH,ch.importance)
        assertNotNull(nm.getNotification(CallCardOverlay.NOTICE_TAG,CallCardOverlay.NOTICE_ID))
        assertEquals(Notification.CATEGORY_CALL,notices.single().category)
        // Gone with the call, as the card goes (at IDLE).
        idle();assertTrue(notices.isEmpty())
    }

    @Test fun allowedTheCardIsDrawnAndNoSecondNotice() {
        draw=true;CallCardOverlay.serviceUp={ true }
        ring();settle(50);settle(CallCardOverlay.HIDDEN_AFTER_MS)
        assertEquals(1,windows.size);assertTrue(notices.isEmpty())
        assertNull("the overlay posts nothing in the service's slot",nm.getNotification(null,CallCardService.NOTIFICATION_ID))
        assertEquals(CardStats.DRAWN,synchronized(verdicts) { verdicts.single() }.result)
        assertTrue(synchronized(noticeAt) { noticeAt.isEmpty() })
        idle();assertTrue(windows.isEmpty())
    }

    @Test fun theNoticeKeepsTheCardsOrderAndAtMostThreeItems() {
        ring();settle()
        val n=notices.single()
        assertEquals("박하늘 과장, 가나건설",title(n))
        assertFalse("the screen rules: no middle dot",title(n).contains("·"))
        // Folded: the first line of the card (the item due soon, with its badge); amounts stay as on the card.
        assertEquals("대금 120만원 입금 확인, 오늘 요청, 확인 필요",line(n))
        val lines=big(n)
        assertEquals(4,lines.size)
        assertEquals("대금 120만원 입금 확인, 오늘 요청, 확인 필요",lines[0])
        assertEquals(listOf("도면 다시 받기, 어제 요청","현장 사진 보내기, 2일 전 요청"),lines.subList(1,3))
        assertEquals("${CallCardView.Content.dayWords(now-3*day)} 통화: 자재 단가는 지난번과 같게 하기로 했어요",lines[3])
        assertEquals("체크할 일 외 2개",n.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
        lines.forEach { assertFalse(it,it.contains("…") || it.contains("4444") || it.contains("5555")) }
        assertNull("no big picture or large icon",n.getLargeIcon())
        assertEquals(kr.callbrief.R.drawable.ic_stat_ieum,n.smallIcon.resId)
        idle()
    }

    @Test fun aCardWithoutItemsShowsTheLastCallAndAThinCardItsOwnLines() {
        ContactCards.write(app,mapOf(key to card(emptyList())),replaceAll=true)
        ring();settle()
        val n=notices.single()
        assertEquals("자재 단가는 지난번과 같게 하기로 했어요",line(n))
        assertEquals(listOf("${CallCardView.Content.dayWords(now-3*day)} 통화: 자재 단가는 지난번과 같게 하기로 했어요"),big(n))
        assertNull(n.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        idle()
        val thin=ContactCard(key,emptyList(),null,now-day,"in",emptyList(),emptyList(),emptyList(),emptyList(),now,thin=ContactCard.Thin("하늘 현장",3))
        ContactCards.write(app,mapOf(key to thin),replaceAll=true)
        ring();settle()
        val t=notices.single()
        assertEquals("하늘 현장",title(t));assertEquals(listOf("어제 통화, 30일간 3번"),big(t))
        idle()
    }

    @Test fun theLockScreenShowsThePublicVersionAsBefore() {
        CallCardOverlay.locked={ true }
        ring();settle()
        val pub=notices.single().publicVersion!!
        assertEquals("박하늘 과장",title(pub))
        val l=line(pub)
        assertTrue(l,l.startsWith("가나건설, 체크할 일: 대금"));assertFalse(l,l.contains("120") || l.contains("만원"))
        assertEquals(Notification.VISIBILITY_PRIVATE,notices.single().visibility)
        idle()
        SecureSettings(app).update { it.put(CallCardOverlay.KEY_LOCK_DETAIL,false) }
        ring();settle()
        assertEquals("이음 통화 카드",title(notices.single().publicVersion!!))
        idle()
    }

    @Test fun aTapOpensThePersonPageElseTheCheckListAndIsCounted() {
        ring();settle()
        // A copy: the host's PendingIntent keeps one Intent and updates it in place.
        val sent=android.content.Intent(shadowOf(notices.single().contentIntent).savedIntent)
        assertEquals("pe_7",sent.getStringExtra(MainActivity.EXTRA_PERSON_ID))
        assertTrue(sent.getBooleanExtra(MainActivity.EXTRA_FROM_CALL_CARD,false))
        assertEquals(MainActivity::class.java.name,sent.component!!.className)
        idle()
        // A company's card without a person: the check list.
        ContactCards.write(app,mapOf(key to card(listOf(task("wf_1","도면 다시 받기",1)),people=emptyList())),replaceAll=true)
        ring();settle()
        val other=shadowOf(notices.single().contentIntent).savedIntent
        assertEquals("",other.getStringExtra(MainActivity.EXTRA_PERSON_ID));assertEquals(Screen.TASKS.name,other.getStringExtra(MainActivity.EXTRA_SCREEN))
        idle()
        // The Activity opened by it: the person's page, and one tap counted.
        val a=Robolectric.buildActivity(MainActivity::class.java,sent).setup().get()
        assertEquals(Screen.PERSON,a.screen);assertEquals("pe_7",a.person.personId)
        CardStats.awaitIdle()
        assertEquals(1,CardStats.read(SecureSettings(app).snapshot()).getInt(CardStats.NOTICE_TAPS))
        a.finish()
    }

    @Test fun theTimeFromTheCallsStartToTheNoticeIsTodaysMedian() {
        val kst=java.time.ZoneId.of("Asia/Seoul")
        var wall=java.time.ZonedDateTime.of(2026,11,21,10,0,0,0,kst).toInstant().toEpochMilli()
        CardStats.clock={ wall };CardStats.elapsed={ elapsed };CardStats.canDrawNow={ false }
        fun call(ms:Long) {
            CardStats.signal(elapsed,false)
            CardStats.follow(app,CallSession.Snapshot(CallSession.State.RINGING,key,CallSession.Direction.IN,wall),CallCardService.SHOW)
            elapsed+=ms;CardStats.noticeShown(app)
            CardStats.follow(app,CallSession.Snapshot(CallSession.State.IDLE,null,null,wall),null)
        }
        call(40);call(70);call(900)
        CardStats.awaitIdle()
        val t=CardStats.heartbeat(SecureSettings(app).snapshot(),wall)!!.getJSONObject("today")
        assertEquals(70L,t.getLong(CardStats.NOTICE_MS))
        assertFalse("the samples stay on the phone",t.has(CardStats.NOTICE_SAMPLES))
        // A new day starts without them.
        wall+=day
        assertFalse(CardStats.heartbeat(SecureSettings(app).snapshot(),wall)!!.getJSONObject("today").has(CardStats.NOTICE_MS))
    }
}
