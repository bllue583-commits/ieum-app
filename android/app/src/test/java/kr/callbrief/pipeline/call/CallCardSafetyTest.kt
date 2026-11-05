package kr.callbrief.pipeline.call

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.HomeScreen
import kr.callbrief.pipeline.PipelineSettingsKeys
import kr.callbrief.pipeline.Screen
import kr.callbrief.pipeline.StatusLights
import kr.callbrief.pipeline.call.CallSession.State
import kr.callbrief.pipeline.launchMain
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** v3.0 r11-3 (design 7.3, 7.4): the safety nets around the call card. The counts behind the heartbeat's callCard (once per
 * call, whoever tells first: the card or the service), the service's hard end, the settings light "통화 카드", and the setup
 * step that comes back when the overlay permission goes. Synthetic numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallCardSafetyTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private var now=1_800_000_000_000L
    private val number="010-0000-3333";private val key="01000003333"
    private val starts=mutableListOf<Intent>()
    private val withCard=mutableSetOf(key)
    private var inCall=true
    private val fakeCard=object:CallCardService.Card {
        override fun show(context:Context,phoneKey:String?,state:State) {}
        override fun collapse() {}
        override fun expand() {}
        override fun hide() {}
    }

    @Before fun fresh() {
        CallSession.resetForHost();CallCardService.resetForHost();CardStats.resetForHost()
        CallSession.clock={ now };CallSession.afterCall={ true };CallSession.isPrivate={ _,k -> k=="01000009999" }
        CallCardService.card=fakeCard
        CallCardService.hasCard={ _,k -> k in withCard }
        CallCardService.roleHeld={ false }
        CallCardService.inCall={ inCall }
        CallCardService.clock={ now }
        CallCardService.startService={ _,i -> synchronized(starts) { starts+=i } }
        CardStats.clock={ now }
    }
    @After fun done() { CallSession.resetForHost();CallCardService.resetForHost();CardStats.resetForHost();ShadowSettings.setCanDrawOverlays(false) }

    private fun phone(state:String,raw:String?=null,advance:Long=1_000L) {
        now+=advance
        val intent=Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE,state)
        if(raw!=null) intent.putExtra("incoming_number",raw)
        CallStateReceiver().onReceive(app,intent)
        CallSession.awaitIdle();CardStats.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private val ringing=TelephonyManager.EXTRA_STATE_RINGING
    private val offhook=TelephonyManager.EXTRA_STATE_OFFHOOK
    private val idle=TelephonyManager.EXTRA_STATE_IDLE
    private fun stats():JSONObject=CardStats.read(SecureSettings(app).snapshot())
    private fun counts()=CardStats.KINDS.associateWith { stats().optInt(it,0) }.filterValues { it>0 }

    // ---- counts (settings CARD_STATS, the heartbeat's callCard) ----
    @Test fun theCardsOwnOutcomeIsCountedOncePerCall() {
        phone(ringing,number)
        CardStats.record(app,CallCardOverlay.SHOWN);CardStats.record(app,CallCardOverlay.NO_PERMISSION);CardStats.awaitIdle()
        phone(offhook);phone(idle)
        assertEquals(mapOf(CallCardOverlay.SHOWN to 1),counts())
        // The next call is counted again, also when its card falls back to the notice.
        phone(ringing,number);CardStats.record(app,CallCardOverlay.NO_PERMISSION);CardStats.awaitIdle();phone(idle)
        assertEquals(mapOf(CallCardOverlay.SHOWN to 1,CallCardOverlay.NO_PERMISSION to 1),counts())
        assertEquals(CardStats.day(now),stats().getInt("day"))
        assertEquals(1,stats().getJSONObject("today").getInt(CallCardOverlay.NO_PERMISSION))
    }
    @Test fun theOverlayTellsTheCountsByDefault() {
        CallCardOverlay.resetForHost()
        try {
            phone(ringing,number)
            CallCardOverlay.outcome(app,CallCardOverlay.SHOWN);CardStats.awaitIdle()
            assertEquals(mapOf(CallCardOverlay.SHOWN to 1),counts())
        } finally { CallCardOverlay.resetForHost() }
    }
    @Test fun aNumberWithoutACardIsCountedByTheServiceOnceEvenWhenTheStateRepeats() {
        phone(ringing,"010-0000-5555");phone(ringing,"010-0000-5555");phone(offhook);phone(idle)
        assertTrue("never reaches the card",starts.isEmpty())
        assertEquals(mapOf(CallCardOverlay.NO_CARD to 1),counts())
    }
    @Test fun aCallWithoutItsNumberCountsOnlyWhenTheNumberNeverCame() {
        // Android sends the state twice, first without the number: the call with it is no failure.
        phone(ringing,null);phone(ringing,number)
        CardStats.record(app,CallCardOverlay.SHOWN);CardStats.awaitIdle()
        phone(idle)
        assertEquals(mapOf(CallCardOverlay.SHOWN to 1),counts())
        phone(ringing,null);phone(offhook);phone(idle)
        assertEquals(mapOf(CallCardOverlay.SHOWN to 1,CallCardOverlay.NO_NUMBER to 1),counts())
    }
    @Test fun personalNumbersSwitchedOffCardsAndCallsGoingOutWithoutTheRoleAreNotFailures() {
        phone(ringing,"010-0000-9999");CardStats.record(app,CallCardOverlay.NO_CARD);phone(idle)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.CARD_INCOMING,false) }
        phone(ringing,"010-0000-5555");phone(idle)
        phone(offhook);phone(idle) // a call going out, no role
        assertEquals(emptyMap<String,Int>(),counts())
        assertNull(CardStats.heartbeat(SecureSettings(app).snapshot()))
    }
    @Test fun todayStartsAgainOnANewKoreanDay() {
        var s=JSONObject()
        val day1=1_800_000_000_000L
        s=CardStats.add(s,CallCardOverlay.NO_PERMISSION,day1);s=CardStats.add(s,CallCardOverlay.NO_PERMISSION,day1+60_000)
        assertEquals(2,s.getJSONObject("today").getInt(CallCardOverlay.NO_PERMISSION))
        s=CardStats.add(s,CallCardOverlay.SHOWN,day1+86_400_000L)
        assertEquals(2,s.getInt(CallCardOverlay.NO_PERMISSION));assertEquals(1,s.getInt(CallCardOverlay.SHOWN))
        assertEquals(0,s.getJSONObject("today").getInt(CallCardOverlay.NO_PERMISSION));assertEquals(1,s.getJSONObject("today").getInt(CallCardOverlay.SHOWN))
        assertEquals(CardStats.day(day1)+1,s.getInt("day"))
        // The heartbeat of a later day says today had none (the watchdog's daily rule then closes its issue).
        val later=CardStats.heartbeat(JSONObject().put(PipelineSettingsKeys.CARD_STATS,s),day1+2*86_400_000L)!!
        assertEquals(1,later.getInt(CallCardOverlay.SHOWN));assertEquals(0,later.getJSONObject("today").getInt(CallCardOverlay.SHOWN))
        assertEquals(CardStats.day(day1+2*86_400_000L),later.getInt("day"))
        assertEquals(1,CardStats.heartbeat(JSONObject().put(PipelineSettingsKeys.CARD_STATS,s),day1+86_400_000L)!!.getJSONObject("today").getInt(CallCardOverlay.SHOWN))
        // A stored text form is read too; anything but the four counts is dropped.
        val text=JSONObject().put(PipelineSettingsKeys.CARD_STATS,s.put("note","김").toString())
        assertFalse(CardStats.heartbeat(text).toString().contains("김"))
    }

    // ---- the service's life ----
    @Test fun theServiceEndsAfterFourHoursEvenWhileThePhoneSaysACallGoesOn() {
        phone(ringing,number);phone(offhook)
        val controller=Robolectric.buildService(CallCardService::class.java,starts.last()).create().startCommand(0,1)
        inCall=true
        // 23 checks of 10 minutes: still up.
        repeat(23) { now+=CallCardService.CHECK_MS;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.CHECK_MS)) }
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        now=now+CallCardService.MAX_LIFE_MS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallCardService.CHECK_MS))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf);assertFalse(CallCardService.active)
    }
    @Test fun withoutTheCallStatePermissionTheServiceTreatsTheCallAsOver() {
        CallCardService.resetForHost()
        shadowOf(app as android.app.Application).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
        assertFalse(CallCardService.inCall(app))
    }

    // ---- the settings light "통화 카드" ----
    @Test fun theCardLightIsAmberWithoutTheOverlayOrTheCallStateAndGreenWithoutTheRole() {
        val ok=StatusLights.Input(now=100L*3_600_000,folderConnected=true,modelState="READY",consent=true)
        val card=StatusLights.lights(ok).single { it.tag=="status_card" }
        assertEquals("통화 카드",card.label);assertTrue(card.ok);assertEquals("정상",card.words);assertNull(card.target)
        val noOverlay=StatusLights.lights(ok.copy(cardOverlay=false)).single { !it.ok }
        assertEquals("status_card",noOverlay.tag);assertEquals(StatusLights.GROUP_CARD,noOverlay.target);assertTrue(noOverlay.words,noOverlay.words.contains("통화 카드 묶음"))
        val noState=StatusLights.lights(ok.copy(phoneState=false)).single { !it.ok }
        assertEquals("status_card",noState.tag);assertEquals(StatusLights.GROUP_CONNECT,noState.target);assertTrue(noState.words,noState.words.contains("전화 상태"))
        val noRole=StatusLights.lights(ok.copy(cardRole=false)).single { it.tag=="status_card" }
        assertTrue("the role is optional",noRole.ok);assertTrue(noRole.words,noRole.words.contains("거는 전화"))
        assertTrue("both switches off: nothing to fix",StatusLights.lights(ok.copy(cardOn=false,cardOverlay=false)).all { it.ok })
        StatusLights.lights(ok.copy(cardOverlay=false)).forEach { assertFalse(it.words,Regex("[·…]").containsMatchIn(it.words)) }
    }
    @Test fun settingsShowTheCardLightAndItGoesToTheCardGroup() {
        val a=launchMain(Screen.SETTINGS)
        repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(a.window.decorView.findViewWithTag<View>("status_card")!=null) return@repeat;Thread.sleep(10) }
        val row=a.window.decorView.findViewWithTag<View>("status_card")
        assertNotNull(row)
        // On the host the overlay is not allowed: amber, and pressing it goes to the group that is drawn on this page.
        assertTrue(row.isClickable)
        assertNotNull(a.window.decorView.findViewWithTag<View>(StatusLights.GROUP_CARD))
        val words=texts(row).joinToString(" ")
        assertTrue(words,words.contains("통화 카드"))
    }

    // ---- the setup card again when the permission goes ----
    @Test fun theOverlayPermissionGoneBringsTheSetupStepBack() {
        // Home reads the permission itself every time it is entered (no remembered "done"): allowed, the step folds away.
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(kr.callbrief.pipeline.CallCardAsk.overlayAllowed(app))
        val step="통화 화면 위에 이음 카드 보기"
        fun setupText(a:android.app.Activity):String? {
            var t:String?=null
            repeat(400) { shadowOf(Looper.getMainLooper()).idle()
                val c=a.window.decorView.findViewWithTag<View>("home_setup");if(c!=null) { t=texts(c).joinToString("\n");return t };Thread.sleep(10) }
            return t
        }
        val a=launchMain(Screen.HOME)
        val before=setupText(a)
        assertNotNull("other steps are still open on the host",before)
        assertFalse(before,before!!.contains(step))
        // Turned off in the phone's settings: back in the app, the step is open again.
        ShadowSettings.setCanDrawOverlays(false)
        a.home.enter()
        var after=""
        repeat(400) { shadowOf(Looper.getMainLooper()).idle();after=setupText(a).orEmpty();if(after.contains(step)) return@repeat;Thread.sleep(10) }
        assertTrue(after,after.contains(step))
        // The same in the rendered card for a phone with every other step done.
        val all=HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0)
        a.home.render(all);assertNull(a.window.decorView.findViewWithTag<View>("home_setup"))
        a.home.render(all.copy(overlay=false))
        val one=texts(a.window.decorView.findViewWithTag<View>("home_setup")).joinToString("\n")
        assertTrue(one,one.contains(step) && one.contains("9/10"))
    }

    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(v:View)=views(v).filterIsInstance<TextView>().map { it.text.toString() }
}
