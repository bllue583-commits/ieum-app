package kr.callbrief.pipeline.call

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.telephony.TelephonyManager
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.core.PrivacyRules
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import kr.callbrief.pipeline.call.CallSession.Direction
import kr.callbrief.pipeline.call.CallSession.State
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONArray
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

/** v3.4 r14-1 (DESIGN_V4 4.1): a card for every call. A call going out without the role, call waiting, a refused start, the
 * counts behind the heartbeat's callCard, and the cold ringing path (no call store, the card file opened at most once, the
 * settings read once per call, a blocked table that cannot be read counts as personal). Synthetic numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R14OneCallTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private var now=1_800_000_000_000L
    private var el=500_000L
    private val number="010-0000-3333";private val key="01000003333"
    private val second="010-0000-4444";private val secondKey="01000004444"
    private val starts=mutableListOf<Intent>()
    private var refuseStart=false
    private val calls=mutableListOf<String>()
    private val withCard=mutableSetOf(key)
    private val ends=mutableListOf<CallSession.CallEnd>()

    private val fakeCard=object:CallCardService.Card {
        override fun show(context:Context,phoneKey:String?,state:State) { calls+="show:$phoneKey:$state" }
        override fun collapse() { calls+="collapse" }
        override fun expand() { calls+="expand" }
        override fun hide() { calls+="hide" }
    }

    @Before fun fresh() {
        CallSession.resetForHost();CallCardService.resetForHost();CardStats.resetForHost()
        ContactCards.shutdown();ContactCards.clearPending();ContactCards.blockedReadFault=false
        CallSession.clock={ now };CallSession.afterCall={ true };CallSession.isPrivate={ _,k -> k=="01000009999" }
        CallSession.afterCallCards={ _,_ -> }
        CallSession.listeners+= { e:CallSession.CallEnd -> synchronized(ends) { ends+=e } }
        CallCardService.card=fakeCard
        CallCardService.hasCard={ _,k -> k in withCard }
        CallCardService.roleHeld={ false }
        CallCardService.inCall={ true }
        CallCardService.clock={ now }
        CallCardService.startService={ _,i -> if(refuseStart) throw IllegalStateException("background start");synchronized(starts) { starts+=i } }
        CardStats.clock={ now };CardStats.elapsed={ el };CardStats.processStart={ 0L }
        CardStats.canDraw={ true };CardStats.locked={ false };CardStats.notifyAllowed={ true };CardStats.isThin={ _,_ -> false }
    }
    @After fun done() {
        CallSession.resetForHost();CallCardService.resetForHost();CardStats.resetForHost()
        ContactCards.blockedReadFault=false;ContactCards.shutdown();ContactCards.clearPending()
    }

    private fun phone(state:String,raw:String?=null,advance:Long=1_000L) {
        now+=advance;el+=advance
        val intent=Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE,state)
        if(raw!=null) intent.putExtra("incoming_number",raw)
        CallStateReceiver().onReceive(app,intent)
        CallSession.awaitIdle();CardStats.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private val ringing=TelephonyManager.EXTRA_STATE_RINGING
    private val offhook=TelephonyManager.EXTRA_STATE_OFFHOOK
    private val idle=TelephonyManager.EXTRA_STATE_IDLE
    private fun heartbeat():JSONObject? { CardStats.awaitIdle();return CardStats.heartbeat(SecureSettings(app).snapshot(),now) }
    private fun card(state:State=State.RINGING)=Robolectric.buildService(CallCardService::class.java,starts.last()).create().startCommand(0,1)

    // ---- 4.1-1: a call going out without the role ----
    @Test fun aCallGoingOutWithoutTheRoleShowsItsCardOnceTheNumberComes() {
        phone(offhook) // the first broadcast has no number
        assertTrue(starts.isEmpty())
        phone(offhook,number,advance=50)
        assertEquals(1,starts.size)
        card()
        assertEquals(listOf("show:$key:OFFHOOK"),calls)
        CardStats.record(app,CardStats.SHOWN)
        phone(idle)
        val hb=heartbeat()!!
        assertEquals(1,hb.getInt(CardStats.SHOWN));assertEquals(1,hb.getInt(CardStats.SHOWN_OUTGOING));assertEquals(1,hb.getInt(CardStats.ELIGIBLE_OUTGOING))
        assertEquals(0,hb.getInt(CardStats.NO_NUMBER));assertEquals("for reference only",1,hb.getInt(CardStats.NO_ROLE))
        // From the OFFHOOK without number to the card: 50 ms.
        assertEquals(50,hb.getJSONObject("ringToCardMs").getInt("p50"))
    }

    // ---- 4.1-3: call waiting ----
    @Test fun callWaitingHidesTheCardKeepsTheFirstCallAndEndsOnce() {
        phone(ringing,number);val rangAt=now
        card()
        phone(offhook,number)
        assertEquals("show:$key:OFFHOOK",calls.last())
        // A second call rings during the first: the card goes, the first call keeps its number and start.
        phone(ringing,second)
        assertEquals("hide",calls.last())
        CallSession.current().let { assertEquals(State.OFFHOOK,it.state);assertEquals(key,it.phoneKey);assertTrue(it.waiting) }
        // Answered (or turned away): off hook again, still no card, the number not taken over.
        val shown=calls.count { it.startsWith("show") }
        phone(offhook,second);phone(offhook)
        assertEquals(shown,calls.count { it.startsWith("show") })
        assertEquals(key,CallSession.current().phoneKey);assertNotEquals(secondKey,CallSession.current().phoneKey)
        phone(idle)
        assertEquals(listOf(CallSession.CallEnd(key,Direction.IN,rangAt,now,true)),ends)
        assertFalse(CallSession.current().waiting)
        // The next call is an ordinary one again.
        phone(ringing,number)
        assertEquals("show:$key:RINGING",calls.last())
    }
    @Test fun callWaitingDuringACallGoingOutWithoutNumberDoesNotTakeTheSecondNumber() {
        phone(offhook)
        phone(ringing,second);phone(offhook,second)
        CallSession.current().let { assertNull(it.phoneKey);assertTrue(it.waiting);assertEquals(Direction.OUT,it.direction) }
        assertTrue(starts.isEmpty())
        phone(idle)
        assertEquals(1,ends.size);assertNull(ends.single().phoneKey)
    }

    // ---- 4.1-4: a refused start ----
    @Test fun aRefusedStartIsCountedAndIdleHidesAndStopsTheDirectCard() {
        refuseStart=true
        phone(ringing,number)
        assertEquals(listOf("show:$key:RINGING"),calls)
        assertFalse(CallCardService.active)
        phone(idle)
        assertEquals("hide",calls.last());assertFalse(CallCardService.driver.shown);assertFalse(CallCardService.active)
        assertEquals(1,heartbeat()!!.getInt(CardStats.FGS_REFUSED))
    }

    // ---- 4.1-5: the counts ----
    @Test fun nothingSeenIsNullAndOneCallIsZeros() {
        assertNull(heartbeat())
        phone(ringing,"010-0000-5555");phone(idle) // no card for it: noCard
        val hb=heartbeat()!!
        assertEquals(1,hb.getInt(CardStats.CALLS));assertEquals(1,hb.getInt(CardStats.NO_CARD))
        for(k in CardStats.KINDS) assertTrue(k,hb.has(k))
        assertEquals(0,hb.getInt(CardStats.SHOWN));assertEquals(0,hb.getInt(CardStats.LOCKED));assertEquals(0,hb.getJSONObject("ringToCardMs").getInt("n"))
        assertEquals(1,hb.getJSONObject("today").getInt(CardStats.CALLS))
    }
    @Test fun aLockedScreenIsNotAMissingPermission() {
        CardStats.locked={ true }
        phone(ringing,number);CardStats.record(app,CardStats.NO_PERMISSION);phone(idle)
        var hb=heartbeat()!!
        assertEquals(1,hb.getInt(CardStats.LOCKED));assertEquals(0,hb.getInt(CardStats.NO_PERMISSION))
        // The overlay itself not allowed: that is noPermission.
        CardStats.canDraw={ false };CardStats.locked={ false }
        phone(ringing,number);CardStats.record(app,CardStats.NO_PERMISSION);phone(idle)
        hb=heartbeat()!!
        assertEquals(1,hb.getInt(CardStats.LOCKED));assertEquals(1,hb.getInt(CardStats.NO_PERMISSION));assertEquals(0,hb.getInt(CardStats.NO_NOTIFY))
        // A window and a notice when locked (r14-2): still locked.
        CardStats.canDraw={ true };CardStats.locked={ true }
        phone(ringing,number);CardStats.record(app,CardStats.SHOWN);phone(idle)
        assertEquals(2,heartbeat()!!.getInt(CardStats.LOCKED))
    }
    @Test fun noNotificationPermissionIsNoNotify() {
        CardStats.canDraw={ false };CardStats.notifyAllowed={ false }
        // The card fell back to the notice, which could not be posted: the card says "shown", the counts know better.
        phone(ringing,number);CardStats.record(app,CardStats.SHOWN);phone(idle)
        val hb=heartbeat()!!
        assertEquals(1,hb.getInt(CardStats.NO_NOTIFY));assertEquals(1,hb.getInt(CardStats.NO_PERMISSION));assertEquals(0,hb.getInt(CardStats.SHOWN))
        assertEquals(CardStats.LOCKED to true,CardStats.classify(CardStats.SHOWN,canDraw=true,locked=true,notify=false))
        assertEquals(CardStats.SHOWN to false,CardStats.classify(CardStats.SHOWN,canDraw=true,locked=false,notify=false))
    }
    @Test fun ringToCardTimesColdApartThinAndTheCardsActions() {
        // The first broadcast of a process that started 1 s before: cold.
        CardStats.processStart={ el }
        phone(ringing,number,advance=1_000)
        el+=420;CardStats.record(app,CardStats.SHOWN)
        CardStats.expanded(app);CardStats.expanded(app);CardStats.closed(app,1_500)
        phone(idle)
        // A warm call with a thin card.
        CardStats.isThin={ _,k -> k==key }
        phone(ringing,number);el+=180;CardStats.record(app,CardStats.SHOWN);CardStats.closed(app,5_000);phone(idle)
        val hb=heartbeat()!!
        assertEquals(1,hb.getInt(CardStats.COLD));assertEquals(1,hb.getInt(CardStats.THIN));assertEquals(2,hb.getInt(CardStats.SHOWN))
        assertEquals(420,hb.getJSONObject("ringToCardMsCold").getInt("p50"));assertEquals(1,hb.getJSONObject("ringToCardMsCold").getInt("n"))
        assertEquals(180,hb.getJSONObject("ringToCardMs").getInt("max"));assertEquals(1,hb.getJSONObject("ringToCardMs").getInt("n"))
        assertEquals(1,hb.getInt(CardStats.EXPANDED));assertEquals(1,hb.getInt(CardStats.CLOSED_UNDER_2S))
        // Numbers only, whatever is stored.
        assertFalse(hb.toString(),hb.toString().contains("3333"))
        assertEquals(JSONObject().put("n",3).put("p50",20L).put("max",30L).toString(),CardStats.summary(listOf(30L,10L,20L)).toString())
    }

    // ---- 4.1-2: the cold ringing path ----
    private fun quietStore() {
        // The store-open thread of the app start is done before the counting starts.
        Thread.getAllStackTraces().keys.filter { it.name=="ieum-store-open" }.forEach { it.join(10_000) }
    }
    private fun cardOf(k:String)=ContactCard(k,listOf(ContactCard.Person("pe_1","김민수","대표",false)),null,now-86_400_000L,"in",
        emptyList(),emptyList(),emptyList(),emptyList(),now)
    @Test fun theRingingPathOpensNoCallStoreTheCardFileOnceAndReadsSettingsOnce() {
        quietStore()
        val store=IeumApplication.store(app)
        store.rememberPersonal("01000009999")
        ContactCards.write(app,mapOf(key to cardOf(key)),replaceAll=false)
        assertEquals(1,ContactCards.rewriteBlocked(store))
        IeumApplication.shutdown() // the process is gone: neither the store nor the card file is open
        CallSession.isPrivate=CallSession::privateNumber
        CallCardService.hasCard={ c,k -> ContactCards.get(c,k)!=null }
        var storeAsked=0;CallSession.storePersonal={ _,_ -> storeAsked++;false }
        var reads=0;CallSession.readSettings={ c -> reads++;SecureSettings(c).snapshot() }
        val opens0=ProtectedDatabase.openCount();val cardOpens0=ContactCards.opens
        phone(ringing,null);phone(ringing,number);phone(offhook,number)
        assertTrue(starts.isNotEmpty());assertTrue(starts.all { it.getBooleanExtra(CallCardService.EXTRA_SHOW,false) })
        assertEquals("the card file, once",1,ContactCards.opens-cardOpens0)
        assertEquals("no call store on the ringing path",ContactCards.opens-cardOpens0,ProtectedDatabase.openCount()-opens0)
        assertEquals(0,storeAsked)
        assertEquals("settings read once for the call",1,reads)
        assertTrue(ContactCards.lastOpenMs>=0)
        phone(idle)
        // A personal number from the blocked table: nothing shows.
        phone(ringing,"010-0000-9999")
        assertTrue(CallSession.current().ignored);assertFalse(starts.last().getBooleanExtra(CallCardService.EXTRA_SHOW,true))
        assertEquals(0,storeAsked)
        phone(idle)
    }
    @Test fun aBlockedTableThatCannotBeReadCountsAsPersonal() {
        CallSession.isPrivate=CallSession::privateNumber
        ContactCards.blockedReadFault=true
        phone(ringing,number)
        assertTrue(CallSession.current().ignored);assertTrue(starts.isEmpty());assertTrue(calls.isEmpty())
    }
    @Test fun beforeTheBlockedTableWasWrittenTheStoreAnswersAndTheRulesComeFromTheCallsSettings() {
        quietStore();IeumApplication.shutdown()
        CallSession.isPrivate=CallSession::privateNumber
        var storeAsked=0;CallSession.storePersonal={ _,k -> storeAsked++;k==secondKey }
        phone(ringing,second)
        assertTrue(CallSession.current().ignored);assertEquals(1,storeAsked)
        phone(idle)
        // An exclusion rule: caught from the settings snapshot, without asking the store.
        SecureSettings(app).update { it.put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash(key)))) }
        phone(ringing,number)
        assertTrue(CallSession.current().ignored);assertEquals(1,storeAsked)
    }
}
