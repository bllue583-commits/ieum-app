package kr.callbrief.pipeline.call

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.WindowManager
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/** v4.8 r29-1, r29-2: a call coming in whose card may not be drawn gets its notice while it rings (alerting once) and, once
 * answered, the same notice alerting once more [CallCardOverlay.NOTICE_AGAIN_MS] later (once per call, quiet after that).
 * Not for a call hung up first, a call going out, a drawn card, a notice beside a card on the lock screen, or a notice the
 * card took away. Generated numbers, names and cards only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R29NoticeAgainTest:ProtectedTextHostTestBase() {
    private val app:android.app.Application get()=RuntimeEnvironment.getApplication()
    private val wm get()=app.getSystemService(WindowManager::class.java)
    private val windows get()=Shadow.extract<ShadowWindowManagerImpl>(wm).views.filterIsInstance<CallCardView>()
    private val nm get()=shadowOf(app.getSystemService(NotificationManager::class.java))
    private val notices get()=nm.allNotifications.filter { it.channelId==CallCardOverlay.CHANNEL }
    private val number="010-2222-3333";private val key="01022223333"
    private val now=System.currentTimeMillis()
    private val day=86_400_000L
    /** Every post of the card's notice: whether it may alert (no "alert once"), and its title. */
    private val posts=mutableListOf<Pair<Boolean,String>>()
    private val again=mutableListOf<Long>()
    private val lates=mutableListOf<Boolean>()
    private var draw=false
    /** r29-4: the display (null: cannot be read), the listener now registered, the channel made loud by the person. */
    private var screen:Boolean?=true
    private var listener:(()->Unit)?=null
    private var registered=0
    private var loud=false

    private fun card()=ContactCard(key,listOf(ContactCard.Person("pe_9","한가람","대리",false)),ContactCard.Company("co_9","다라상사","설비"),now-2*day,"in",
        listOf(ContactCard.Recent(now-2*day,"통화","배관 자재는 다음 주에 들어와요.")),
        listOf(ContactCard.Task("wf_1","견적서 보내기",now-day,"open",null)),emptyList(),emptyList(),now)

    @Before fun setup() {
        CallSession.resetForHost();CallCardOverlay.resetForHost();CardStats.resetForHost()
        CallSession.isPrivate={ _,_ -> false };CallSession.afterCall={ true }
        CallCardOverlay.canDraw={ draw };CallCardOverlay.locked={ false };CallCardOverlay.serviceUp={ false }
        CallCardOverlay.outcome={ _,_ -> };CallCardOverlay.onExpanded={};CallCardOverlay.onClosed={ _,_ -> }
        CallCardOverlay.drawnNow={ true };CallCardOverlay.probe={ CardStats.Probe(2,4242,30,false,false,null) }
        CallCardOverlay.onVerdict={ _,_ -> };CallCardOverlay.onFailure={ _,_ -> }
        CallCardOverlay.screenOn={ true };CallCardOverlay.lastExit={ null };CallCardOverlay.onNotice={}
        CallCardOverlay.noticeCheck={ _,_ -> true };CallCardOverlay.onFallbackSeen={}
        CallCardOverlay.clock={ SystemClock.uptimeMillis() }
        CallCardOverlay.posted={ n -> synchronized(posts) { posts+=(n.flags and Notification.FLAG_ONLY_ALERT_ONCE==0) to n.extras.getCharSequence(Notification.EXTRA_TITLE).toString() } }
        CallCardOverlay.onNoticeAgain={ _,ms,late -> synchronized(again) { again+=ms;lates+=late } }
        CallCardOverlay.displayOn={ screen }
        CallCardOverlay.watchDisplay={ _,f -> registered++;listener=f;{ registered--;listener=null } }
        CallCardOverlay.channelLoud={ loud }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ContactCards.shutdown();ContactCards.clearPending()
        ContactCards.write(app,mapOf(key to card()),replaceAll=true)
    }
    @After fun reset() { CallCardOverlay.resetForHost();CallSession.resetForHost();ContactCards.shutdown();ContactCards.clearPending();CardStats.resetForHost() }

    private fun settle(ms:Long=0) { CallCardOverlay.awaitIdle();shadowOf(Looper.getMainLooper()).idle();if(ms>0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));CallCardOverlay.awaitIdle() }
    private fun ring() { CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,number);CallCardOverlay.show(app,number,CallSession.State.RINGING) }
    private fun answer() { CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,number);CallCardOverlay.show(app,number,CallSession.State.OFFHOOK) }
    private fun idle() { CallCardOverlay.show(app,null,CallSession.State.IDLE);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"");settle() }
    private fun alerts()=synchronized(posts) { posts.count { it.first } }
    private fun againCount()=synchronized(again) { again.size }

    @Test fun deniedComingInTheNoticeAlertsOnceMoreAfterTheAnswerThenStaysQuiet() {
        ring();settle(50)
        assertTrue(windows.isEmpty());assertEquals(1,notices.size)
        assertEquals("one alert while it rings",1,alerts())
        val before=synchronized(posts) { posts.size }
        answer();settle(50)
        assertEquals("the answer's redrawing is quiet",1,alerts())
        settle(CallCardOverlay.NOTICE_AGAIN_MS-200)
        assertEquals("not before the delay",1,alerts())
        settle(300)
        assertEquals("once more after the answer",2,alerts());assertEquals(1,againCount())
        assertEquals("OFFHOOK to the new alert",CallCardOverlay.NOTICE_AGAIN_MS,synchronized(again) { again.single() })
        // The same notice: one, same slot, same title.
        assertEquals(1,notices.size);assertNotNull(nm.getNotification(CallCardOverlay.NOTICE_TAG,CallCardOverlay.NOTICE_ID))
        assertEquals(setOf("한가람 대리, 다라상사"),synchronized(posts) { posts.map { it.second }.toSet() })
        assertTrue(synchronized(posts) { posts.size }>before)
        // Quiet after that: another state, more time.
        answer();settle(50);settle(5_000)
        assertEquals("once per call",2,alerts());assertEquals(1,againCount())
        assertTrue("the last drawing alerts only once",notices.single().flags and Notification.FLAG_ONLY_ALERT_ONCE!=0)
        idle();assertTrue(notices.isEmpty())
        // The next call starts afresh: once more again.
        synchronized(posts) { posts.clear() }
        ring();settle(50);answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals(2,alerts());assertEquals(2,againCount())
        idle()
    }

    @Test fun hungUpBeforeTheDelayOrNeverAnsweredNoSecondAlert() {
        ring();settle(50);answer();settle(CallCardOverlay.NOTICE_AGAIN_MS/2)
        idle();settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(1,alerts());assertEquals(0,againCount());assertTrue(notices.isEmpty())
        // Rang and ended without an answer.
        synchronized(posts) { posts.clear() }
        ring();settle(50);idle();settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(1,alerts());assertEquals(0,againCount());assertTrue(notices.isEmpty())
    }

    @Test fun aCallGoingOutAlertsOnceAsBefore() {
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,number);CallCardOverlay.show(app,number,CallSession.State.OFFHOOK)
        settle(50);settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(1,notices.size);assertEquals("its first post alerts, nothing more",1,alerts());assertEquals(0,againCount())
        idle()
    }

    @Test fun aDrawnCardHasNoNoticeStill() {
        draw=true
        ring();settle(50);settle(CallCardOverlay.HIDDEN_AFTER_MS);answer();settle(50);settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(1,windows.size);assertTrue(notices.isEmpty());assertEquals(0,synchronized(posts) { posts.size });assertEquals(0,againCount())
        idle()
    }

    @Test fun aNoticeTheCardTookAwayIsNotPutUpAgain() {
        // The window added while the check said yes, the check no for a moment (the notice beside it), yes again when judged.
        draw=true
        ring();settle(50);assertEquals(1,windows.size)
        draw=false;CallCardOverlay.show(app,null,CallSession.State.RINGING);settle(50)
        assertEquals(1,notices.size)
        draw=true;settle(CallCardOverlay.HIDDEN_AFTER_MS)
        assertTrue("the verdict took it away (r27-3)",notices.isEmpty())
        answer();settle(50);settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertTrue(notices.isEmpty());assertEquals(1,alerts());assertEquals(0,againCount())
        idle()
    }

    @Test fun onTheLockScreenTheCardWithItsNoticeStaysAsItWasButANoticeAloneAlertsAgain() {
        CallCardOverlay.locked={ true }
        // Card and notice together (the check says yes): unchanged, no second alert.
        draw=true
        ring();settle(50);assertEquals(1,windows.size);assertEquals(1,notices.size)
        answer();settle(50);settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(1,alerts());assertEquals(0,againCount())
        idle()
        // The check says no: no window, the notice alone: alerts again after the answer.
        synchronized(posts) { posts.clear() }
        draw=false
        ring();settle(50);assertTrue(windows.isEmpty())
        answer();settle(50);settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals(2,alerts());assertEquals(1,againCount())
        idle()
    }

    // ---- r29-4 ----
    private fun display(on:Boolean) { screen=on;listener?.invoke() }
    @Test fun theServicesSlotGetsTheSecondAlertToo() {
        CallCardOverlay.serviceUp={ true }
        ring();settle(50);answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals(CallCardOverlay.CHANNEL,nm.getNotification(null,CallCardService.NOTIFICATION_ID).channelId)
        assertNull(nm.getNotification(CallCardOverlay.NOTICE_TAG,CallCardOverlay.NOTICE_ID))
        assertEquals(2,alerts());assertEquals(1,againCount())
        idle()
    }
    @Test fun theDisplayOffAtTheEarWaitsUntilItComesOnDuringTheCall() {
        ring();settle(50);screen=false;answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals("not on a dark display",1,alerts());assertEquals(1,registered)
        settle(5_000);assertEquals(1,alerts())
        display(false);settle(1_000);assertEquals("another change, still off",1,alerts())
        display(true);settle(CallCardOverlay.NOTICE_AGAIN_ON_MS-100);assertEquals("settles first",1,alerts())
        settle(200)
        assertEquals("once the display is on",2,alerts());assertEquals(listOf(true),synchronized(again) { lates.toList() })
        assertTrue("from the answer, the time at the ear included",synchronized(again) { again.single() }>=6_000)
        assertEquals("the listener goes after the alert",0,registered)
        display(false);display(true);settle(1_000);assertEquals("once per call",2,alerts())
        idle()
    }
    @Test fun aShortTapOfTheSensorDoesNotCount() {
        ring();settle(50);screen=false;answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        display(true);settle(100);display(false);settle(1_000)
        assertEquals(1,alerts());assertEquals(1,registered)
        idle()
    }
    @Test fun endedBeforeTheDisplayCameOnNoAlertAndTheListenerGoes() {
        ring();settle(50);screen=false;answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals(1,registered)
        idle();assertEquals("unregistered with the call",0,registered);assertNull(listener)
        screen=true;settle(2_000)
        assertEquals(1,alerts());assertEquals(0,againCount())
    }
    @Test fun aDisplayThatCannotBeReadOrWatchedAlertsAtOneSecond() {
        screen=null
        ring();settle(50);answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals(2,alerts());assertEquals(listOf(false),synchronized(again) { lates.toList() });assertEquals(0,registered)
        idle()
        synchronized(posts) { posts.clear() }
        screen=false;CallCardOverlay.watchDisplay={ _,_ -> null }
        ring();settle(50);answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals(2,alerts())
        idle()
    }
    @Test fun aLoudChannelIsNotAlertedAgainNorCounted() {
        loud=true
        ring();settle(50);answer();settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(1,alerts());assertEquals(0,againCount())
        idle()
        // What counts as loud: a sound or a vibration on the channel.
        val ch=android.app.NotificationChannel("x","x",NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null,null);ch.enableVibration(false);assertFalse(CallCardOverlay.loud(ch))
        ch.enableVibration(true);assertTrue(CallCardOverlay.loud(ch))
        ch.enableVibration(false);ch.setSound(android.net.Uri.parse("content://x/1"),null);assertTrue(CallCardOverlay.loud(ch))
        assertFalse(CallCardOverlay.loud(null))
    }
    @Test fun aFailedPostIsNotCounted() {
        ring();settle(50)
        CallCardOverlay.post={ _,_,_,n -> if(n.flags and Notification.FLAG_ONLY_ALERT_ONCE==0) throw SecurityException("refused") }
        answer();settle(CallCardOverlay.NOTICE_AGAIN_MS*3)
        assertEquals(0,againCount())
        idle()
    }
    @Test fun aWindowTriedAgainSoonIsNotAlertedOver() {
        // The check says yes but the window is refused twice while it rings (the notice instead); the last try is due 2 s later.
        draw=true;var refuse=true
        CallCardOverlay.addWindow={ w,v,p -> if(refuse) throw android.view.WindowManager.BadTokenException("refused") else w.addView(v,p) }
        ring();settle(50);settle(CallCardOverlay.RETRY_DELAYS_MS[0]+50)
        assertTrue(windows.isEmpty());assertEquals(1,notices.size)
        answer();settle(CallCardOverlay.NOTICE_AGAIN_MS+100)
        assertEquals("not over a window about to be tried",1,alerts());assertEquals(0,againCount())
        refuse=false;settle(CallCardOverlay.RETRY_DELAYS_MS[1])
        assertEquals(1,windows.size);assertEquals(0,againCount())
        idle()
    }

    @Test fun theCountIsOncePerCallAndTodaysMedianOnly() {
        val kst=java.time.ZoneId.of("Asia/Seoul")
        var wall=java.time.ZonedDateTime.of(2026,11,27,10,0,0,0,kst).toInstant().toEpochMilli()
        var elapsed=1_000_000L
        CardStats.clock={ wall };CardStats.elapsed={ elapsed };CardStats.canDrawNow={ false }
        fun call(vararg ms:Long) {
            CardStats.signal(elapsed,false)
            CardStats.follow(app,CallSession.Snapshot(CallSession.State.RINGING,key,CallSession.Direction.IN,wall),CallCardService.SHOW)
            ms.forEach { CardStats.noticeAgain(app,it,late=it>1_300) }
            CardStats.follow(app,CallSession.Snapshot(CallSession.State.IDLE,null,null,wall),null)
            elapsed+=60_000
        }
        call(1_000,1_400);call(1_200);call();call(6_000)
        CardStats.awaitIdle()
        val hb=CardStats.heartbeat(SecureSettings(app).snapshot(),wall)!!
        val t=hb.getJSONObject("today")
        assertEquals("once per call",3,t.getInt(CardStats.NOTICE_AGAIN));assertEquals(3,hb.getInt(CardStats.NOTICE_AGAIN))
        assertEquals("the median of 1000, 1200, 6000",1_200L,t.getLong(CardStats.NOTICE_AGAIN_MS))
        assertEquals("the one that waited for the display",1,t.getInt(CardStats.NOTICE_AGAIN_LATE))
        assertTrue(CardStats.KINDS.contains("noticeAgainLate"))
        assertFalse("the samples stay on the phone",t.has(CardStats.NOTICE_AGAIN_SAMPLES))
        assertTrue(CardStats.KINDS.contains("noticeAgain"));assertEquals("noticeAgainMs",CardStats.NOTICE_AGAIN_MS)
        // A new day: no time, a zero count.
        wall+=day
        val next=CardStats.heartbeat(SecureSettings(app).snapshot(),wall)!!.getJSONObject("today")
        assertFalse(next.has(CardStats.NOTICE_AGAIN_MS));assertEquals(0,next.getInt(CardStats.NOTICE_AGAIN))
    }
}
