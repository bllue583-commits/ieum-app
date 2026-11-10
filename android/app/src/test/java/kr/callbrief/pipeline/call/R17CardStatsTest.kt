package kr.callbrief.pipeline.call

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.telephony.TelephonyManager
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.PhoneStatus
import kr.callbrief.pipeline.PipelineSettingsKeys
import kr.callbrief.pipeline.StatusLights
import kr.callbrief.pipeline.call.CallSession.State
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

/** v3.6.1 (DESIGN_V7 K2 to K4): the counts take what the card told (nothing read again), drawn, fallback and hidden today and in
 * total, the last 20 calls in `recent` with codes and numbers only, the heartbeat's permissions with the process and the raw
 * app-op mode, and the "통화 카드" light reading today's counts. Synthetic numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R17CardStatsTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private var now=1_800_000_000_000L
    private val number="010-0000-3333";private val key="01000003333"
    private val fakeCard=object:CallCardService.Card {
        override fun show(context:Context,phoneKey:String?,state:State) {}
        override fun collapse() {}
        override fun expand() {}
        override fun hide() {}
    }
    private val probe=CardStats.Probe(1,4242,30,true,true,7)

    @Before fun fresh() {
        CallSession.resetForHost();CallCardService.resetForHost();CardStats.resetForHost()
        CallSession.clock={ now };CallSession.afterCall={ true };CallSession.isPrivate={ _,_ -> false }
        CallSession.afterCallCards={ _,_ -> }
        CallCardService.card=fakeCard;CallCardService.hasCard={ _,k -> k==key };CallCardService.roleHeld={ false }
        CallCardService.inCall={ true };CallCardService.clock={ now };CallCardService.startService={ _,_ -> }
        CardStats.clock={ now };CardStats.notifyAllowed={ true };CardStats.isThin={ _,_ -> false };CardStats.probe={ probe }
    }
    @After fun done() { CallSession.resetForHost();CallCardService.resetForHost();CardStats.resetForHost() }

    private fun phone(state:String,raw:String?=null) {
        now+=1_000
        val intent=Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE,state)
        if(raw!=null) intent.putExtra("incoming_number",raw)
        CallStateReceiver().onReceive(app,intent)
        CallSession.awaitIdle();CardStats.awaitIdle();shadowOf(Looper.getMainLooper()).idle()
    }
    private fun heartbeat():JSONObject { CardStats.awaitIdle();return CardStats.heartbeat(SecureSettings(app).snapshot(),now)!! }
    private fun attempt(result:String,cause:String,canDraw:Boolean=true)=CardStats.Attempt(result,cause,canDraw,canDraw,probe,error=cause.takeIf { it.startsWith("addView:") })

    @Test fun recordTakesWhatTheCardToldWithoutReadingThePermission() {
        // Nothing to read: the probes are gone; NO_PERMISSION stays NO_PERMISSION whatever the phone says now.
        phone(TelephonyManager.EXTRA_STATE_RINGING,number);CardStats.record(app,CardStats.NO_PERMISSION);phone(TelephonyManager.EXTRA_STATE_IDLE)
        val hb=heartbeat()
        assertEquals(1,hb.getInt(CardStats.NO_PERMISSION));assertEquals(0,hb.getInt(CardStats.LOCKED))
    }
    @Test fun drawnFallbackAndHiddenAreCountedTodayAndInTotal() {
        phone(TelephonyManager.EXTRA_STATE_RINGING,number);CardStats.record(app,CardStats.SHOWN)
        CardStats.verdict(app,attempt(CardStats.DRAWN,CallCardOverlay.CAUSE_OK));phone(TelephonyManager.EXTRA_STATE_IDLE)
        phone(TelephonyManager.EXTRA_STATE_RINGING,number);CardStats.record(app,CardStats.SHOWN)
        CardStats.verdict(app,attempt(CardStats.FALLBACK,CardStats.HIDDEN));phone(TelephonyManager.EXTRA_STATE_IDLE)
        phone(TelephonyManager.EXTRA_STATE_RINGING,number);CardStats.record(app,CardStats.NO_PERMISSION)
        CardStats.verdict(app,attempt(CardStats.FALLBACK,"addView:BadTokenException",canDraw=false));phone(TelephonyManager.EXTRA_STATE_IDLE)
        val hb=heartbeat()
        for(o in listOf(hb,hb.getJSONObject("today"))) {
            assertEquals(1,o.getInt(CardStats.DRAWN));assertEquals(2,o.getInt(CardStats.FALLBACK));assertEquals(1,o.getInt(CardStats.HIDDEN))
            assertEquals("shown keeps its meaning (the window was added)",2,o.getInt(CardStats.SHOWN));assertEquals(1,o.getInt(CardStats.NO_PERMISSION))
        }
        val r=hb.getJSONArray("recent")
        assertEquals(3,r.length())
        val last=r.getJSONObject(2)
        assertEquals("fallback",last.getString("result"));assertEquals("addView:BadTokenException",last.getString("cause"))
        assertEquals("addView:BadTokenException",last.getString("error"));assertFalse(last.getBoolean("canDraw"))
        assertEquals(1,last.getInt("mode"));assertEquals(4242,last.getInt("pid"));assertEquals(30,last.getInt("ageS"))
        assertTrue(last.getBoolean("car"));assertTrue(last.getBoolean("btAudio"));assertEquals(7,last.getInt("commDevice"))
        assertTrue(last.getString("at"),Regex("""\d{2}:\d{2}""").matches(last.getString("at")))
        assertEquals(CardStats.day(now),last.getInt("day"))
    }
    @Test fun anUnconfirmedWindowIsNotDrawnAndTurnsTheLightAmber() {
        // v3.6.1 fix (review, critical): seen although the check said no (drawnDespiteFalse) is unconfirmed, not drawn.
        repeat(3) {
            phone(TelephonyManager.EXTRA_STATE_RINGING,number);CardStats.record(app,CardStats.NO_PERMISSION)
            CardStats.verdict(app,attempt(CardStats.UNCONFIRMED,CallCardOverlay.CAUSE_DRAWN_DESPITE_FALSE,canDraw=false));phone(TelephonyManager.EXTRA_STATE_IDLE)
        }
        phone(TelephonyManager.EXTRA_STATE_RINGING,number);CardStats.record(app,CardStats.SHOWN)
        CardStats.verdict(app,attempt(CardStats.DRAWN,CallCardOverlay.CAUSE_OK));phone(TelephonyManager.EXTRA_STATE_IDLE)
        val hb=heartbeat()
        for(o in listOf(hb,hb.getJSONObject("today"))) {
            assertEquals(3,o.getInt(CardStats.UNCONFIRMED));assertEquals("drawn stays as it was",1,o.getInt(CardStats.DRAWN))
            assertEquals(0,o.getInt(CardStats.FALLBACK))
        }
        assertEquals("unconfirmed",hb.getJSONArray("recent").getJSONObject(0).getString("result"))
        // The heartbeat's number filter carries it.
        assertEquals(3,PhoneStatus.callCardJson(hb).getJSONObject("today").getInt(CardStats.UNCONFIRMED))
        // The light: 4 tried today, 1 drawn: amber.
        val st=CardStats.read(SecureSettings(app).snapshot())
        assertEquals(4 to 1,StatusLights.cardToday(st,now))
        val ok=StatusLights.Input(now=now,folderConnected=true,modelState="READY",consent=true)
        val (tried,drawn)=StatusLights.cardToday(st,now)
        assertFalse(StatusLights.lights(ok.copy(cardTodayTried=tried,cardTodayDrawn=drawn)).single { it.tag=="status_card" }.ok)
    }
    @Test fun callsTheCardNeverReachedAreInRecentToo() {
        phone(TelephonyManager.EXTRA_STATE_RINGING,"010-0000-5555");phone(TelephonyManager.EXTRA_STATE_IDLE) // no card
        val e=heartbeat().getJSONArray("recent").getJSONObject(0)
        assertEquals(CardStats.NO_CARD,e.getString("result"));assertEquals(1,e.getInt("mode"));assertFalse(e.has("canDraw"))
    }
    @Test fun recentKeepsTheNewestTwentyAndNothingOfThePerson() {
        var s=JSONObject()
        repeat(25) { i ->
            val e=CardStats.entry(attempt(CardStats.DRAWN,CallCardOverlay.CAUSE_OK),now+i).put("name","김민수").put("number",number).put("pid",i)
            s=CardStats.add(s,mapOf(CardStats.DRAWN to 1),now+i,entry=e)
        }
        // A stored entry with a text that is not a code.
        s.getJSONArray("recent").getJSONObject(0).put("cause","김 010-0000-3333")
        val hb=PhoneStatus.callCardJson(CardStats.heartbeat(JSONObject().put(PipelineSettingsKeys.CARD_STATS,s),now)!!)
        val r=hb.getJSONArray("recent")
        assertEquals(CardStats.RECENT,r.length())
        assertEquals("the newest kept, oldest first",5,r.getJSONObject(0).getInt("pid"));assertEquals(24,r.getJSONObject(19).getInt("pid"))
        val text=hb.toString()
        assertFalse(text,text.contains("김") || text.contains("3333") || text.contains("name") || text.contains("number"))
        for(i in 0 until r.length()) for(k in r.getJSONObject(i).keys()) assertTrue(k,k in CardStats.RECENT_KEYS)
        assertEquals(25,hb.getInt(CardStats.DRAWN))
    }
    @Test fun theHeartbeatPermissionsCarryTheProcessAndTheRawMode() {
        val p=PhoneStatus.permissions(app)!!
        assertNotNull(p.pid);assertNotNull(p.processAgeS);assertNotNull(p.overlayMode)
        val o=PhoneStatus.build(PhoneStatus.Input(1_000L,"v2.4",1040,0,null,5,6,"READY","","",12).copy(v31=PhoneStatus.V31(p))).getJSONObject("permissions")
        assertEquals(android.os.Process.myPid(),o.getInt("pid"));assertTrue(o.has("processAgeS"));assertTrue(o.has("overlayMode"))
        assertTrue(o.getInt("overlayMode") in -1..4)
    }
    @Test fun theCardLightReadsTodaysCounts() {
        val ok=StatusLights.Input(now=now,folderConnected=true,modelState="READY",consent=true)
        fun card(i:StatusLights.Input)=StatusLights.lights(i).single { it.tag=="status_card" }
        assertTrue(card(ok.copy(cardTodayTried=2,cardTodayDrawn=0)).ok)
        assertTrue("half drawn is not more than half missing",card(ok.copy(cardTodayTried=4,cardTodayDrawn=2)).ok)
        val amber=card(ok.copy(cardTodayTried=11,cardTodayDrawn=0))
        assertFalse(amber.ok);assertTrue(amber.words,amber.words.startsWith("통화 카드가 오늘 안 뜨고 있어요"))
        assertFalse(amber.words,Regex("[·…]").containsMatchIn(amber.words))
        assertFalse(card(ok.copy(cardTodayTried=3,cardTodayDrawn=1)).ok)
        // Today's counts from the stored stats; another day's are zeros.
        val st=JSONObject().put("day",CardStats.day(now)).put("today",JSONObject().put(CardStats.DRAWN,1).put(CardStats.FALLBACK,5))
        assertEquals(6 to 1,StatusLights.cardToday(st,now))
        assertEquals(0 to 0,StatusLights.cardToday(st,now+2*86_400_000L))
        assertEquals(0 to 0,StatusLights.cardToday(null,now))
    }
}
