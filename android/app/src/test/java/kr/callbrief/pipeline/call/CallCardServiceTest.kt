package kr.callbrief.pipeline.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.PipelineSettingsKeys
import kr.callbrief.pipeline.call.CallSession.Direction
import kr.callbrief.pipeline.call.CallSession.State
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
import org.robolectric.annotation.SQLiteMode
import java.time.Duration

/** v3.0 r11-1 (design 7.2, 7.3): the number of a call that goes out ([OutgoingCallService] into [CallSession.pendingOutgoing]),
 * when the call card is wanted ([CallCardService.wanted]), the service's life (RINGING starts it, IDLE + 3 s stops it, the
 * 10-minute check ends it when no call goes on) and what it tells the card. Synthetic numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallCardServiceTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private var now=1_800_000_000_000L
    private val number="010-0000-3333";private val key="01000003333"
    private val starts=mutableListOf<Intent>()
    private var refuseStart=false
    private val calls=mutableListOf<String>()
    private val withCard=mutableSetOf<String>()
    private var role=false
    private var inCall=true

    private val fakeCard=object:CallCardService.Card {
        override fun show(context:Context,phoneKey:String?,state:State) { calls+="show:$phoneKey:$state" }
        override fun collapse() { calls+="collapse" }
        override fun expand() { calls+="expand" }
        override fun hide() { calls+="hide" }
    }

    @Before fun fresh() {
        CallSession.resetForHost();CallCardService.resetForHost();OutgoingCallService.resetForHost()
        CallSession.clock={ now };CallSession.afterCall={ true };CallSession.isPrivate={ _,k -> k=="01000009999" }
        CallCardService.card=fakeCard
        CallCardService.hasCard={ _,k -> k in withCard }
        CallCardService.roleHeld={ role }
        CallCardService.inCall={ inCall }
        CallCardService.clock={ now }
        CallCardService.startService={ _,i -> if(refuseStart) throw IllegalStateException("background start");synchronized(starts) { starts+=i } }
        withCard+=key
    }
    @After fun done() { CallSession.resetForHost();CallCardService.resetForHost();OutgoingCallService.resetForHost() }

    private fun phone(state:String,raw:String?=null,advance:Long=1_000L) {
        now+=advance
        val intent=Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE,state)
        if(raw!=null) intent.putExtra("incoming_number",raw)
        CallStateReceiver().onReceive(app,intent)
        CallSession.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun settings(block:(org.json.JSONObject)->Unit) { SecureSettings(app).update { block(it) } }
    private val ringing=TelephonyManager.EXTRA_STATE_RINGING
    private val offhook=TelephonyManager.EXTRA_STATE_OFFHOOK
    private val idle=TelephonyManager.EXTRA_STATE_IDLE
    private fun placeCall(raw:String):Int {
        var unmodified=0
        OutgoingCallService.placeUnmodified={ unmodified++ }
        OutgoingCallService.clock={ now }
        val service=Robolectric.buildService(OutgoingCallService::class.java).create().get()
        service.onPlaceCall(Uri.fromParts("tel",raw,null),PhoneAccountHandle(android.content.ComponentName(app,OutgoingCallService::class.java),"sim"),true)
        return unmodified
    }

    // ---- the number of a call that goes out ----
    @Test fun onPlaceCallLetsTheCallGoOnAtOnceAndNotesTheNumber() {
        assertEquals(1,placeCall(number))
        phone(offhook,advance=2_000)
        CallSession.current().let { assertEquals(State.OFFHOOK,it.state);assertEquals(Direction.OUT,it.direction);assertEquals(key,it.phoneKey) }
    }
    @Test fun onPlaceCallGoesOnEvenWhenNotingFails() {
        CallSession.isPrivate={ _,_ -> error("store down") }
        var unmodified=0
        OutgoingCallService.placeUnmodified={ unmodified++ }
        val service=Robolectric.buildService(OutgoingCallService::class.java).create().get()
        // A handle that is not a phone number (voicemail, SIP): still placed, no number kept.
        service.onPlaceCall(Uri.parse("sip:someone@example.invalid"),PhoneAccountHandle(android.content.ComponentName(app,OutgoingCallService::class.java),"sim"),false)
        assertEquals(1,unmodified)
        phone(offhook)
        assertNull(CallSession.current().phoneKey)
    }
    @Test fun aPendingNumberOlderThanTwentySecondsIsNotUsed() {
        placeCall(number)
        phone(offhook,advance=CallSession.OUTGOING_WINDOW_MS+1)
        CallSession.current().let { assertEquals(Direction.OUT,it.direction);assertNull(it.phoneKey) }
    }
    @Test fun aPendingNumberDoesNotTurnARingingCallIntoOneThatGoesOut() {
        placeCall(number)
        phone(ringing,"010-0000-4444");phone(offhook)
        CallSession.current().let { assertEquals(Direction.IN,it.direction);assertEquals("01000004444",it.phoneKey) }
        // Used up or dropped: the next call that goes out has no number from it.
        phone(idle);phone(offhook)
        assertNull(CallSession.current().phoneKey)
    }
    @Test fun aPersonalNumberGoingOutIsIgnoredAndStartsNothing() {
        role=true
        placeCall("010-0000-9999")
        phone(offhook)
        CallSession.current().let { assertTrue(it.ignored);assertNull(it.phoneKey) }
        assertTrue(starts.isEmpty());assertTrue(calls.isEmpty())
    }

    // ---- when a card is wanted ----
    @Test fun noCardForACallThatGoesOutWithoutTheRole() {
        role=false
        placeCall(number);phone(offhook)
        assertFalse(CallCardService.wanted(app,CallSession.current()))
        assertTrue(starts.isEmpty());assertTrue(calls.isEmpty())
        role=true
        assertTrue(CallCardService.wanted(app,CallSession.current()))
        settings { it.put(PipelineSettingsKeys.CARD_OUTGOING,false) }
        assertFalse(CallCardService.wanted(app,CallSession.current()))
    }
    @Test fun switchesAndNumbersWithoutACard() {
        val ring=CallSession.Snapshot(State.RINGING,key,Direction.IN,now)
        assertTrue(CallCardService.wanted(app,ring))
        settings { it.put(PipelineSettingsKeys.CARD_INCOMING,false) }
        assertFalse(CallCardService.wanted(app,ring))
        settings { it.put(PipelineSettingsKeys.CARD_INCOMING,true) }
        val stranger=CallSession.Snapshot(State.RINGING,"01000005555",Direction.IN,now)
        assertFalse("numbers without a card: off by default",CallCardService.wanted(app,stranger))
        settings { it.put(PipelineSettingsKeys.CARD_UNKNOWN,true) }
        assertTrue(CallCardService.wanted(app,stranger))
        assertFalse(CallCardService.wanted(app,CallSession.Snapshot(State.RINGING,null,Direction.IN,now)))
        assertFalse(CallCardService.wanted(app,CallSession.Snapshot(State.RINGING,null,Direction.IN,now,ignored=true)))
        assertFalse(CallCardService.wanted(app,CallSession.Snapshot(State.IDLE,key,Direction.IN,now)))
    }
    @Test fun aPersonalNumberRingingStartsNothing() {
        phone(ringing,"010-0000-9999")
        assertTrue(CallSession.current().ignored)
        assertTrue(starts.isEmpty());assertTrue(calls.isEmpty())
    }

    // ---- the service's life ----
    @Test fun ringingStartsTheServiceWhichShowsTheCardFoldsItAndStopsThreeSecondsAfterIdle() {
        phone(ringing,number)
        assertEquals(1,starts.size)
        assertTrue(starts[0].getBooleanExtra(CallCardService.EXTRA_SHOW,false))
        val extras=starts[0].extras!!
        @Suppress("DEPRECATION") assertTrue("no number in the Intent",extras.keySet().none { extras.get(it).toString().contains("3333") })
        val controller=Robolectric.buildService(CallCardService::class.java,starts[0]).create().startCommand(0,1)
        val service=controller.get()
        assertNotNull("foreground",shadowOf(service).lastForegroundNotification)
        assertEquals(listOf("show:$key:RINGING"),calls)
        // Answered: shown as off hook, folded 3 s later.
        phone(offhook)
        controller.withIntent(starts.last()).startCommand(0,2)
        assertEquals("show:$key:OFFHOOK",calls.last())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.COLLAPSE_AFTER_MS))
        assertEquals("collapse",calls.last())
        // Ended: hidden at once, stopped 3 s later.
        phone(idle)
        controller.withIntent(starts.last()).startCommand(0,3)
        assertEquals("hide",calls.last())
        assertFalse(shadowOf(service).isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.STOP_AFTER_IDLE_MS-100))
        assertFalse(shadowOf(service).isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(CallCardService.active)
    }
    @Test fun aCallAgainWithinThreeSecondsKeepsTheService() {
        phone(ringing,number)
        val controller=Robolectric.buildService(CallCardService::class.java,starts[0]).create().startCommand(0,1)
        phone(idle);controller.withIntent(starts.last()).startCommand(0,2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        phone(ringing,number);controller.withIntent(starts.last()).startCommand(0,3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.STOP_AFTER_IDLE_MS*2))
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("show:$key:RINGING",calls.last())
    }
    @Test fun theServiceEndsItselfWhenNoCallGoesOnAfterTenMinutes() {
        phone(ringing,number);phone(offhook)
        val controller=Robolectric.buildService(CallCardService::class.java,starts.last()).create().startCommand(0,1)
        // The IDLE broadcast never comes. At 10 minutes a call still goes on: the service stays.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.CHECK_MS))
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        inCall=false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.CHECK_MS))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("hide",calls.last())
    }
    @Test fun withoutTheOverlayPermissionTheCardIsStillAskedForSoItCanFallBackToTheNotice() {
        // CallCardOverlay (r11-2) draws a heads-up notice instead of the window when the overlay may not be drawn.
        assertFalse(android.provider.Settings.canDrawOverlays(app))
        phone(ringing,number)
        Robolectric.buildService(CallCardService::class.java,starts[0]).create().startCommand(0,1)
        assertEquals(listOf("show:$key:RINGING"),calls)
    }
    @Test fun aRefusedStartShowsTheCardDirectlyAndHidesItAtIdle() {
        refuseStart=true
        phone(ringing,number)
        assertTrue(starts.isEmpty())
        assertEquals(listOf("show:$key:RINGING"),calls)
        phone(idle)
        assertEquals("hide",calls.last())
    }
    @Test fun aNumberWithoutACardShowsNothingWhileItsSwitchIsOff() {
        phone(ringing,"010-0000-5555")
        assertTrue(starts.isEmpty());assertTrue(calls.isEmpty())
        phone(idle)
        settings { it.put(PipelineSettingsKeys.CARD_UNKNOWN,true) }
        phone(ringing,"010-0000-5555")
        assertEquals(1,starts.size)
    }
    @Test fun outgoingWithTheRoleStartsTheService() {
        role=true
        placeCall(number);phone(offhook)
        assertEquals(1,starts.size)
        Robolectric.buildService(CallCardService::class.java,starts[0]).create().startCommand(0,1)
        assertEquals(listOf("show:$key:OFFHOOK"),calls)
    }
}
