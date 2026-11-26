package kr.callbrief.pipeline.call

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kr.callbrief.LocalPrivacy
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.CallFollowupNotice
import kr.callbrief.pipeline.CallUi
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import kr.callbrief.pipeline.PipelineSettingsKeys
import org.json.JSONArray
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** v4.7 r28-1 to r28-3: a call with someone already called today says so in one line on top of the card and of its notice —
 * "정리 중이에요" only while a recording is known to be there and its result is not on the phone; the facts only otherwise; nothing
 * for calls whose result is on the phone. The heartbeat counts such calls. Made-up numbers, names and cards only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R28RepeatCallTest:ProtectedTextHostTestBase() {
    private val app:android.app.Application get()=RuntimeEnvironment.getApplication()
    private val wm get()=app.getSystemService(WindowManager::class.java)
    private val windows get()=Shadow.extract<ShadowWindowManagerImpl>(wm).views.filterIsInstance<CallCardView>()
    private val nm get()=shadowOf(app.getSystemService(NotificationManager::class.java))
    private val notices get()=nm.allNotifications.filter { it.channelId==CallCardOverlay.CHANNEL }
    private val number="010-4321-8765";private val key="01043218765"
    private val stranger="010-5678-1234";private val strangerKey="01056781234"
    private val kst=ZoneId.of("Asia/Seoul")
    private fun at(h:Int,m:Int,daysAgo:Long=0)=ZonedDateTime.of(2026,11,26,h,m,0,0,kst).minusDays(daysAgo).toInstant().toEpochMilli()
    private var wall=at(14,0)
    private var draw=true
    private val day=86_400_000L
    /** Pictures for a person to look at, only when IEUM_SHOTS_DIR is set (as [CallCardShotsTest]). */
    private val shotsDir=System.getenv("IEUM_SHOTS_DIR")

    private fun card(tasks:List<ContactCard.Task> =listOf(ContactCard.Task("wf_1","도면 다시 받기",wall-day,"open",null)),settled:List<Long> =emptyList(),
                     recent:List<ContactCard.Recent> =listOf(ContactCard.Recent(wall-3*day,"통화","자재 단가는 지난번과 같게 하기로 했어요.")))=
        ContactCard(key,listOf(ContactCard.Person("pe_8","한바다","소장",false)),ContactCard.Company("co_8","다라건설","골조"),wall-3*day,"in",recent,tasks,
            emptyList(),emptyList(),wall,settledMs=settled)

    @Before fun setup() {
        CallSession.resetForHost();CallCardOverlay.resetForHost();CardStats.resetForHost();CallCardService.resetForHost();RepeatCalls.resetForHost()
        CallSession.isPrivate={ _,k -> k=="01000000001" };CallSession.afterCall={ true };CallSession.afterCallCards={ _,_ -> }
        CallSession.clock={ wall };RepeatCalls.clock={ wall };CardStats.clock={ wall };RepeatCalls.waitMs=0
        RepeatCalls.storeFacts={ _,_,_ -> emptyList() }
        CallCardOverlay.canDraw={ draw };CallCardOverlay.locked={ false };CallCardOverlay.serviceUp={ false }
        CallCardOverlay.outcome={ _,_ -> };CallCardOverlay.onExpanded={};CallCardOverlay.onClosed={ _,_ -> }
        CallCardOverlay.drawnNow={ true };CallCardOverlay.probe={ CardStats.Probe(0,4242,30,false,false,null) }
        CallCardOverlay.onVerdict={ _,_ -> };CallCardOverlay.onFailure={ _,_ -> };CallCardOverlay.onNotice={}
        CallCardOverlay.screenOn={ true };CallCardOverlay.lastExit={ null };CallCardOverlay.noticeCheck={ _,_ -> true }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // The per-call follow-up notices are not what is tested here (they read the call store).
        SecureSettings(app).update { it.put(PipelineSettingsKeys.FOLLOWUP_NOTICE,false) }
        ContactCards.shutdown();ContactCards.clearPending()
        ContactCards.write(app,mapOf(key to card()),replaceAll=true)
    }
    @After fun reset() { CallCardOverlay.resetForHost();CallSession.resetForHost();CallCardService.resetForHost();RepeatCalls.resetForHost()
        ContactCards.shutdown();ContactCards.clearPending();CardStats.resetForHost();RuntimeEnvironment.setFontScale(1.0f) }

    private fun settle(ms:Long=0) { CallCardOverlay.awaitIdle();shadowOf(Looper.getMainLooper()).idle();if(ms>0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));CallCardOverlay.awaitIdle() }
    /** One call with [raw] at [h]:[m] talked [talkS] seconds (coming in), through the session as the phone tells it. */
    private fun pastCall(raw:String,h:Int,m:Int,talkS:Long,daysAgo:Long=0) {
        wall=at(h,m,daysAgo);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,raw)
        wall+=10_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,raw)
        wall+=talkS*1_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"")
    }
    /** The phone rings with [raw] now: the service decides, the card follows (as [CallStateReceiver] does). */
    private fun ring(raw:String=number) {
        wall=at(14,0)
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,raw)
        CallCardService.startService={ _,_ -> throw IllegalStateException("host") } // the direct path: the card at once
        CallCardService.follow(app,CallSession.current());settle();settle(50)
    }
    private fun idle() { CallCardService.follow(app,CallSession.Snapshot(CallSession.State.IDLE,null,null,wall));CallCardOverlay.hide()
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"");settle() }
    private fun line(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun big(n:Notification)=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().lines()
    private fun stats():JSONObject { CardStats.awaitIdle();return CardStats.heartbeat(SecureSettings(app).snapshot(),wall)!!.getJSONObject("today") }
    private fun stored()=RepeatCalls.read(SecureSettings(app).snapshot())

    /** Fills in the state of the calls as the passes do: the call store's [facts] (at [h]:[m] plus a few seconds). */
    private fun facts(vararg f:RepeatCalls.Fact) { RepeatCalls.refresh(app,at(13,0)) { _,_ -> f.toList() } }
    private fun fact(h:Int,m:Int,kind:String,key:String?=null)=RepeatCalls.Fact(at(h,m)+12_000,kind,key)
    private fun pending(h:Int,m:Int)=facts(fact(h,m,RepeatCalls.FILE))

    // ---- what the phone keeps ----------------------------------------------------------------------------------------

    @Test fun anAnsweredCallIsKeptAsAFingerprintWithItsTimes() {
        pastCall(number,9,12,175)
        val c=stored().single()
        assertEquals(at(9,12),c.startedMs);assertEquals(175_000L,c.talkMs);assertEquals(true,c.connected);assertFalse(c.out);assertNull(c.status)
        val raw=SecureSettings(app).snapshot().optString(RepeatCalls.KEY)
        assertFalse("never the number",raw.contains(key) || raw.contains("4321") || raw.contains("8765"))
        // A missed call (never off hook) is not kept; a personal number neither; nor a call of 8 seconds.
        wall=at(10,0);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,stranger);wall+=20_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"")
        pastCall("010-0000-0001",10,30,60)
        pastCall(stranger,10,40,8)
        assertEquals(1,stored().size)
        // Only today's calls stay (KST).
        pastCall(number,0,5,30,daysAgo=-1)
        assertEquals(listOf(at(0,5,-1)),stored().map { it.startedMs })
    }

    @Test fun aCallGoingOutCountsOnceTheCallLogSaysItLastedTenSeconds() {
        fun out(raw:String,h:Int,m:Int) { wall=at(h,m);CallSession.pendingOutgoing(raw,wall);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,"")
            wall+=120_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"") }
        val lengths=ArrayDeque(listOf<Long?>(null,95_000L))
        RepeatCalls.logLength={ _,_,_,_ -> lengths.removeFirstOrNull() }
        out(number,11,40)
        val c=stored().single();assertTrue(c.out);assertEquals(true,c.connected);assertEquals(95_000L,c.talkMs)
        // 8 seconds, or the call log may not be read: kept, never counted.
        RepeatCalls.logLength={ _,_,_,_ -> 8_000L };out(stranger,12,10)
        RepeatCalls.logLength={ _,_,_,_ -> CallFollowupNotice.UNREADABLE };out("010-2468-1357",12,20)
        assertEquals(listOf(true,false,false),stored().map { it.connected })
        assertNull(RepeatCalls.info(SecureSettings(app).snapshot(),strangerKey,null,at(14,0)))
        assertNull(RepeatCalls.info(SecureSettings(app).snapshot(),"01024681357",null,at(14,0)))
        // Three tries without the call-log line: asked again at the next pass; still nothing 10 minutes on: not counted.
        RepeatCalls.logLength={ _,_,_,_ -> null };out("010-1357-2468",12,30)
        assertNull(stored().last().connected)
        RepeatCalls.logLength={ _,_,_,_ -> 70_000L };RepeatCalls.refresh(app,at(12,34)) { _,_ -> emptyList() }
        assertEquals(true,stored().last().connected);assertEquals(70_000L,stored().last().talkMs)
        RepeatCalls.logLength={ _,_,_,_ -> null };out("010-9753-1468",12,40);RepeatCalls.refresh(app,at(12,59)) { _,_ -> emptyList() }
        assertEquals(false,stored().last().connected)
    }

    // ---- the states, filled in ahead ----------------------------------------------------------------------------------

    @Test fun aRecordingThereAndNotAnalysedSaysBeingSortedOut() {
        pastCall(number,9,12,175);pending(9,12)
        assertEquals(RepeatCalls.FILE,stored().single().status)
        ring()
        val v=windows.single().apply { measureAt(this) }
        assertEquals("오늘 09:12 통화(3분), 정리 중이에요",text(v,"card_repeat"))
        val spoken=v.spoken().map { it.tag }
        assertEquals(listOf("card_handle","card_collapse","card_close","card_repeat","card_first"),spoken.take(5))
        assertEquals("오늘 09:12 통화(3분), 정리 중이에요",v.findViewWithTag<View>("card_repeat").contentDescription)
        // The second line leaves out "3일 전" under "오늘"; the name and the company stay.
        assertEquals("한바다 소장, 다라건설",text(v,"card_who"))
        idle()
    }

    @Test fun noSpeechNoRecordingOrNotKnownYetSayTheFactsOnly() {
        pastCall(number,9,12,175)
        ring();assertEquals("not known yet (before any pass)","오늘 09:12 통화(3분)",CallCardOverlay.content?.repeat);idle()
        facts(fact(9,12,RepeatCalls.FINAL))
        ring();assertEquals("오늘 09:12 통화(3분)",CallCardOverlay.content?.repeat);idle()
        // No recording: a good discovery pass after the call found no file in its window.
        SecureSettings(app).update { s -> s.put(RepeatCalls.KEY,RepeatCalls.write(RepeatCalls.read(s).map { it.copy(status=null) },at(13,0))) }
        RepeatCalls.afterPass(app,at(9,30),at(9,31),listOf(at(8,0)))
        assertEquals(RepeatCalls.NONE,stored().single().status)
        ring();assertEquals("오늘 09:12 통화(3분)",CallCardOverlay.content?.repeat);idle()
        // A file found later makes it a recording; no speech after that is final, and a final call never goes back.
        RepeatCalls.afterPass(app,at(10,0),at(10,1),listOf(at(9,12)+11_000))
        assertEquals(RepeatCalls.FILE,stored().single().status)
        facts(fact(9,12,RepeatCalls.FINAL));facts(fact(9,12,RepeatCalls.FILE))
        assertEquals(RepeatCalls.FINAL,stored().single().status)
    }

    @Test fun aResultOnThePhoneGivesNoLineAndExcludedTakesTheEntryAway() {
        pastCall(number,9,12,175);pending(9,12)
        facts(fact(9,12,RepeatCalls.DONE,number))
        assertEquals(RepeatCalls.DONE,stored().single().status)
        ring()
        assertNull(windows.single().findViewWithTag<View>("card_repeat"));assertNull(CallCardOverlay.content?.repeat)
        assertTrue("the card's own second line again",CallCardOverlay.content!!.who!!.endsWith("일 전"))
        idle()
        // A result of another number at the same time is not this call's.
        pastCall(number,10,0,60);facts(fact(10,0,RepeatCalls.DONE,stranger))
        assertNull(stored().last().status)
        // Excluded: the entry goes.
        facts(fact(10,0,RepeatCalls.DROP))
        assertEquals(1,stored().size)
        // The card or the follow-up state may say done before the next pass.
        ContactCards.write(app,mapOf(key to card(settled=listOf(at(9,12)+12_000))),replaceAll=true)
        SecureSettings(app).update { s -> s.put(RepeatCalls.KEY,RepeatCalls.write(RepeatCalls.read(s).map { it.copy(status=RepeatCalls.FILE) },at(13,0))) }
        ring();assertNull(CallCardOverlay.content?.repeat);idle()
        ContactCards.write(app,mapOf(key to card()),replaceAll=true)
        SecureSettings(app).update { s -> val c=RepeatCalls.read(s).single()
            s.put(CallFollowupNotice.STATE_KEY,JSONArray().put(CallFollowupNotice.Entry(1,"한바다 소장",null,c.hash,c.startedMs,c.endedMs,state=CallFollowupNotice.DONE).json()).toString()) }
        ring();assertNull(CallCardOverlay.content?.repeat);idle()
    }

    @Test fun anUnknownNumberWhoseRecordingWasAnalysedGetsNothing() {
        // Numbers without a card off, the follow-up notices off: before r28-3 this said "정리 중" until midnight.
        pastCall(stranger,10,5,95);pending(10,5)
        facts(fact(10,5,RepeatCalls.DONE,stranger))
        ring(stranger)
        assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
    }

    @Test fun manyCallsAndMixedSayOneShortLine() {
        pastCall(number,9,12,175);pastCall(number,11,40,40)
        draw=false;ring()
        assertEquals("오늘 2번 통화(마지막 11:40)",line(notices.single()));idle()
        pending(11,40)
        ring()
        val n=notices.single()
        assertEquals("mixed: one being sorted out","오늘 2번 통화(마지막 11:40), 정리 중이에요",line(n))
        assertEquals("오늘 2번 통화(마지막 11:40), 정리 중이에요",big(n).first())
        idle()
        // One of them done: not counted.
        facts(fact(9,12,RepeatCalls.DONE))
        ring();assertEquals("오늘 11:40 통화(40초), 정리 중이에요",line(notices.single()));idle()
        assertEquals("오늘 09:12 통화",RepeatCalls.line(listOf(RepeatCalls.Call("x",at(9,12),at(9,15),null,true,true)),false))
        assertNull(RepeatCalls.line(emptyList(),true))
    }

    @Test fun onlyYesterdaysCallGivesNoLine() {
        SecureSettings(app).update { s -> s.put(RepeatCalls.KEY,JSONArray().put(RepeatCalls.Call(LocalPrivacy.numberHash(s,key),at(17,30,1),at(17,34,1),200_000,false,true,RepeatCalls.FILE).json()).toString()) }
        ring()
        assertNull(CallCardOverlay.content?.repeat);assertNull(windows.single().findViewWithTag<View>("card_repeat"))
        idle()
    }

    @Test fun aNumberWithoutACardGetsTheLineAloneOnlyAfterAMinuteLongCall() {
        pastCall(stranger,10,5,30);pending(10,5)
        ring(stranger)
        assertTrue("30 seconds: not for a delivery call back",windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
        pastCall(stranger,11,0,90)
        ring(stranger)
        val v=windows.single().apply { measureAt(this) }
        assertEquals("오늘 2번 통화(마지막 11:00), 정리 중이에요",text(v,"card_first"))
        assertNull("the line once, not twice",v.findViewWithTag<View>("card_repeat"))
        assertFalse("nothing to keep in view during the call",CallCardOverlay.content!!.hasBody)
        idle()
        draw=false;ring(stranger)
        val n=notices.single()
        assertEquals(CallCardView.Content.UNKNOWN,n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("오늘 2번 통화(마지막 11:00), 정리 중이에요",line(n))
        idle()
        // With numbers without a card switched on, as before: the 30-second call is enough.
        SecureSettings(app).update { s -> s.put(CallCardOverlay.KEY_UNKNOWN,true);s.put(RepeatCalls.KEY,RepeatCalls.write(RepeatCalls.read(s).filter { it.talkMs!!<60_000 },at(13,0))) }
        CallCardOverlay.logLine={ _,_ -> null }
        ring(stranger)
        assertEquals("오늘 10:05 통화(30초), 정리 중이에요",line(notices.single()))
        idle()
    }

    @Test fun aPersonalOrExcludedNumberGetsNothingAndTheSwitchOffStaysOff() {
        pastCall("010-0000-0001",9,0,120)
        assertTrue("never kept",stored().isEmpty())
        SecureSettings(app).update { s -> s.put(RepeatCalls.KEY,JSONArray().put(RepeatCalls.Call(LocalPrivacy.numberHash(s,"01000000001"),at(9,0),at(9,2),120_000,false,true,RepeatCalls.FILE).json()).toString()) }
        ring("010-0000-0001")
        assertTrue(CallSession.current().ignored);assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
        pastCall(number,9,12,175)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.CARD_INCOMING,false) }
        ring()
        assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
        val t=stats()
        assertEquals("the ignored call is not counted",1,t.getInt(CardStats.REPEAT_TODAY))
        assertEquals("no line was due with the switch off",0,t.getInt(CardStats.REPEAT_PENDING)+t.getInt(CardStats.REPEAT_FACT))
        assertEquals(0,t.getInt(CardStats.REPEAT_SHOWN))
    }

    // ---- the notice ----------------------------------------------------------------------------------------------------

    @Test fun theFoldedLineKeepsAnOpenItemFirstElseTakesTheLine() {
        pastCall(number,9,12,175);pending(9,12)
        draw=false
        // The card's first line is the last call's summary: the folded line is today's line.
        ring()
        assertEquals("오늘 09:12 통화(3분), 정리 중이에요",line(notices.single()));idle()
        // The first line is an item due soon: it stays the folded line; today's line is the open notice's first.
        val due=listOf(ContactCard.Task("wf_9","견적서 보내기",wall-day,"open",null,dueMs=at(0,0)+day),ContactCard.Task("wf_1","도면 다시 받기",wall-day,"open",null),
            ContactCard.Task("wf_2","현장 사진 보내기",wall-2*day,"open",null),ContactCard.Task("wf_3","타설 일정 묻기",wall-4*day,"open",null))
        ContactCards.write(app,mapOf(key to card(due)),replaceAll=true)
        ring()
        val n=notices.single();val lines=big(n)
        assertTrue(line(n),line(n).startsWith("견적서 보내기"))
        assertEquals(4,lines.size);assertEquals("오늘 09:12 통화(3분), 정리 중이에요",lines[0])
        assertTrue(lines[1],lines[1].startsWith("견적서 보내기"))
        assertFalse("the last call's line went (four lines)",lines.any { it.contains("통화: 자재 단가") })
        assertFalse(n.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("통화"))
        idle()
    }

    // ---- the card's height -------------------------------------------------------------------------------------------

    private val twoTasks get()=listOf(ContactCard.Task("wf_1","도면 다시 받기",wall-day,"open",null),ContactCard.Task("wf_2","현장 사진 보내기",wall-day,"open",null))
    private fun cardView(c:CallCardView.Content,scale:Float,widthPx:Int?=null):CallCardView {
        RuntimeEnvironment.setFontScale(scale);CallUi.applyTheme(app)
        val v=CallCardView(app).apply { maxHeightPx=(app.resources.displayMetrics.heightPixels*CallCardOverlay.MAX_HEIGHT).toInt();showAll(c) }
        val w=widthPx ?: (app.resources.displayMetrics.widthPixels-2*CallUi.dp(app,12))
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
        return v
    }

    /** r28-4: the name row comes before today's line; at 2.0 times on 360dp the line takes its shortest form in one line. */
    @Test fun largeLettersTakeTheShortestFormAndKeepTheNameRow() {
        val c=CallCardView.Content.of(card(twoTasks),wall).withRepeat("오늘 09:12 통화(3분), 정리 중이에요")
        val v=cardView(c,2.0f)
        val t=v.findViewWithTag<TextView>("card_repeat")
        assertEquals("오늘 통화, 정리 중",t.text.toString());assertEquals(1,t.lineCount);assertEquals(View.VISIBLE,t.visibility)
        assertEquals(View.VISIBLE,v.findViewWithTag<View>("card_who").visibility)
        assertTrue("the name row fits",v.findViewWithTag<View>("card_who").bottom<=v.measuredHeight)
        assertEquals("TalkBack has the whole line","오늘 09:12 통화(3분), 정리 중이에요",t.contentDescription)
        assertNull("never an ellipsis",t.ellipsize)
        assertTrue(CallCardOverlay.repeatOnScreen(v,false))
    }

    @Test fun manyCallsAtTheDefaultSizeTakeTheShortFormInOneLine() {
        val c=CallCardView.Content.of(card(twoTasks),wall).withRepeat("오늘 2번 통화(마지막 11:40), 정리 중이에요")
        val v=cardView(c,1.0f)
        val t=v.findViewWithTag<TextView>("card_repeat")
        assertEquals("오늘 2번 통화, 정리 중이에요",t.text.toString());assertEquals(1,t.lineCount)
        // The one-call line is whole at the default size.
        val one=cardView(c.copy(repeat="오늘 09:12 통화(3분), 정리 중이에요"),1.0f).findViewWithTag<TextView>("card_repeat")
        assertEquals("오늘 09:12 통화(3분), 정리 중이에요",one.text.toString());assertEquals(1,one.lineCount)
        assertEquals(listOf("오늘 2번 통화(마지막 11:40), 정리 중이에요","오늘 2번 통화, 정리 중이에요","오늘 2번 통화, 정리 중"),
            CallCardView.Content.repeatForms("오늘 2번 통화(마지막 11:40), 정리 중이에요"))
        assertEquals(listOf("오늘 09:12 통화(3분)","오늘 통화"),CallCardView.Content.repeatForms("오늘 09:12 통화(3분)"))
    }

    @Test fun aVeryNarrowCardLeavesTheLineOutKeepsTheNameRowAndDoesNotCountIt() {
        val c=CallCardView.Content.of(card(twoTasks),wall).withRepeat("오늘 09:12 통화(3분), 정리 중이에요")
        val v=cardView(c,2.0f,widthPx=CallUi.dp(app,180))
        assertEquals(View.GONE,v.findViewWithTag<View>("card_repeat").visibility)
        assertEquals(View.VISIBLE,v.findViewWithTag<View>("card_who").visibility);assertEquals(View.VISIBLE,v.findViewWithTag<View>("card_first").visibility)
        assertFalse(v.repeatVisible)
        assertFalse("not counted as shown",CallCardOverlay.repeatOnScreen(v,false))
        assertTrue("a notice carries the whole line",CallCardOverlay.repeatOnScreen(v,true))
        // The notice's lines are the whole line whatever the width.
        assertEquals("오늘 09:12 통화(3분), 정리 중이에요",c.noticeBody().first())
    }

    @Test fun theShortFormLeavesOutTheTimeAndLengthOnly() {
        assertEquals("오늘 통화, 정리 중이에요",CallCardView.Content.shortRepeat("오늘 09:12 통화(3분), 정리 중이에요"))
        assertEquals("오늘 통화",CallCardView.Content.shortRepeat("오늘 09:12 통화(40초)"))
        assertEquals("오늘 통화",CallCardView.Content.shortRepeat("오늘 09:12 통화"))
        assertEquals("오늘 2번 통화, 정리 중이에요",CallCardView.Content.shortRepeat("오늘 2번 통화(마지막 11:40), 정리 중이에요"))
    }

    // ---- r28-2: the counts ------------------------------------------------------------------------------------------

    @Test fun theFourCountsOfTheHeartbeat() {
        ring();idle() // not called today yet
        pastCall(number,9,12,175)
        ring();idle() // the facts line
        pending(9,12)
        ring();idle() // being sorted out
        facts(fact(9,12,RepeatCalls.DONE))
        ring();idle() // called today, done: no line
        // A line due but neither window nor notice could go up.
        pastCall(number,10,0,60);pending(10,0);draw=false
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ring();idle()
        val t=stats()
        assertEquals(4,t.getInt(CardStats.REPEAT_TODAY));assertEquals(2,t.getInt(CardStats.REPEAT_PENDING))
        assertEquals(1,t.getInt(CardStats.REPEAT_FACT));assertEquals(2,t.getInt(CardStats.REPEAT_SHOWN))
        assertTrue(CardStats.KINDS.containsAll(listOf("repeatToday","repeatPending","repeatShown","repeatFact")))
    }

    // ---- the ringing path ------------------------------------------------------------------------------------------

    /** The line is worked out from the call's settings snapshot and the card in memory: no file is opened for it. */
    @Test fun theLineReadsNothingAndIsQuick() {
        SecureSettings(app).update { s -> val h=LocalPrivacy.numberHash(s,"01099990000")
            s.put(RepeatCalls.KEY,RepeatCalls.write((0 until 79).map { RepeatCalls.Call(h,at(8,0)+it*60_000L,at(8,0)+it*60_000L+30_000,30_000,false,true) }+
                RepeatCalls.Call(LocalPrivacy.numberHash(s,key),at(13,0),at(13,3),180_000,false,true,RepeatCalls.FILE),wall)) }
        val s=SecureSettings(app).snapshot();val c=card()
        val cardOpens=ContactCards.opens
        RepeatCalls.info(s,key,c,wall)
        val n=2_000;val t0=System.nanoTime()
        repeat(n) { RepeatCalls.info(s,key,c,wall) }
        val perCallUs=(System.nanoTime()-t0)/1_000/n
        println("R28 repeat line: ${perCallUs} us per call (80 entries, host JVM)")
        assertTrue("$perCallUs us",perCallUs<5_000)
        assertEquals(ContactCards.opens,cardOpens)
        assertEquals("오늘 13:00 통화(3분), 정리 중이에요",RepeatCalls.info(s,key,c,wall)!!.line)
    }

    @Test fun aNumberWithoutACardIsAskedOfTheFileOnce() {
        ContactCards.shutdown() // nothing in memory: the file answers
        assertNull(ContactCards.get(app,strangerKey))
        val opens=ContactCards.opens
        assertNull(ContactCards.get(app,strangerKey));assertNull(ContactCards.get(app,strangerKey))
        assertEquals(opens,ContactCards.opens)
        ContactCards.write(app,mapOf(strangerKey to card().copy(phoneKey=strangerKey)),replaceAll=false)
        assertNotNull("a card written since is found",ContactCards.get(app,strangerKey))
    }

    @Test fun pictures() {
        org.junit.Assume.assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!shotsDir.isNullOrBlank())
        val shots=java.io.File(shotsDir!!).apply { mkdirs() }
        fun shot(name:String,content:CallCardView.Content,scale:Float) {
            RuntimeEnvironment.setFontScale(scale);CallUi.applyTheme(app)
            val v=CallCardView(app).apply { maxHeightPx=(app.resources.displayMetrics.heightPixels*CallCardOverlay.MAX_HEIGHT).toInt();showAll(content) };measureAt(v)
            val bmp=Bitmap.createBitmap(v.measuredWidth,v.measuredHeight.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val cv=Canvas(bmp);cv.drawColor(CallUi.PAGE);v.draw(cv)
            shots.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        val tasks=listOf(ContactCard.Task("wf_1","도면 다시 받기",wall-day,"open",null),ContactCard.Task("wf_2","현장 사진 보내기",wall-day,"open",null))
        val base=CallCardView.Content.of(card(tasks),wall)
        shot("v4_pending_one_20",base.withRepeat("오늘 09:12 통화(3분), 정리 중이에요"),2.0f)
        shot("v4_many_10",base.withRepeat("오늘 2번 통화(마지막 11:40), 정리 중이에요"),1.0f)
        assertTrue(shots.resolve("v4_pending_one_20.png").length()>0)
    }

    private fun measureAt(v:View) {
        val dm=app.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels-2*CallUi.dp(app,12),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
    }
    private fun text(v:View,tag:String)=(v.findViewWithTag<View>(tag) as TextView).text.toString().replace(' ',' ')
}
