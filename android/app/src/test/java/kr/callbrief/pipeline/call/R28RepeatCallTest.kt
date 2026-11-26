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

/** v4.7 r28-1, r28-2: a call with someone already called today whose call is not sorted out yet says so in one line on top of
 * the card and of its notice; the heartbeat counts such calls. Made-up numbers, names and cards only. */
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

    // ---- r28-1: what the phone keeps and says ----------------------------------------------------------------------

    @Test fun anAnsweredCallIsKeptAsAFingerprintWithItsTimes() {
        pastCall(number,9,12,175)
        val c=stored().single()
        assertEquals(at(9,12),c.startedMs);assertEquals(175_000L,c.talkMs);assertEquals(true,c.connected);assertFalse(c.out)
        val raw=SecureSettings(app).snapshot().optString(RepeatCalls.KEY)
        assertFalse("never the number",raw.contains(key) || raw.contains("4321") || raw.contains("8765"))
        // A missed call (never off hook) is not kept; a personal number neither.
        wall=at(10,0);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,stranger);wall+=20_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"")
        pastCall("010-0000-0001",10,30,60)
        assertEquals(1,stored().size)
        // Only today's calls stay (KST).
        wall=at(0,5,-1);RepeatCalls.clock={ wall }
        pastCall(number,0,5,30,daysAgo=-1)
        assertEquals(listOf(at(0,5,-1)),stored().map { it.startedMs })
    }

    @Test fun aCallGoingOutCountsOnceTheCallLogSaysItWasConnected() {
        val lengths=ArrayDeque(listOf<Long?>(null,95_000L))
        RepeatCalls.logLength={ _,_,_,_ -> lengths.removeFirstOrNull() }
        wall=at(11,40);CallSession.pendingOutgoing(number,wall);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,"")
        wall+=120_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"")
        val c=stored().single();assertTrue(c.out);assertEquals(true,c.connected);assertEquals(95_000L,c.talkMs)
        // Not connected (0 seconds): kept, but never told.
        RepeatCalls.logLength={ _,_,_,_ -> 0L }
        wall=at(12,10);CallSession.pendingOutgoing(stranger,wall);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,"")
        wall+=30_000;CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"")
        wall=at(14,0)
        assertNull(RepeatCalls.info(SecureSettings(app).snapshot(),strangerKey,null,wall))
        assertEquals(1,RepeatCalls.info(SecureSettings(app).snapshot(),key,null,wall)!!.pending.size)
    }

    @Test fun oneEarlierCallNotSortedOutGivesTheOneCallLine() {
        pastCall(number,9,12,175)
        ring()
        val v=windows.single().apply { measureAt(this) }
        assertEquals("오늘 09:12에 통화했어요(3분). 아직 정리 중이에요",text(v,"card_repeat"))
        // On top: right under the bar, above the first line; TalkBack reads it first.
        val spoken=v.spoken().map { it.tag }
        assertEquals(listOf("card_handle","card_collapse","card_close","card_repeat","card_first"),spoken.take(5))
        assertEquals("오늘 09:12에 통화했어요(3분). 아직 정리 중이에요",v.findViewWithTag<View>("card_repeat").contentDescription)
        idle()
    }

    @Test fun twoEarlierCallsNotSortedOutGiveTheManyCallsLineAlsoInTheNotice() {
        pastCall(number,9,12,175);pastCall(number,11,40,40)
        draw=false;ring()
        val n=notices.single()
        assertEquals("오늘 2번 통화했어요(마지막 11:40). 아직 정리 중이에요",line(n))
        assertEquals("오늘 2번 통화했어요(마지막 11:40). 아직 정리 중이에요",big(n).first())
        assertTrue(big(n)[1],big(n)[1].startsWith("도면 다시 받기, "))
        idle()
    }

    @Test fun anEarlierCallSortedOutGivesNoLine() {
        pastCall(number,9,12,175)
        // The result came in: the card holds that call's time (the recording's own, a few seconds after the ring).
        ContactCards.write(app,mapOf(key to card(settled=listOf(at(9,12)+12_000))),replaceAll=true)
        ring()
        assertNull(windows.single().findViewWithTag<View>("card_repeat"))
        assertNull(CallCardOverlay.content?.repeat)
        idle()
        // Or the follow-up state of the same call says done (the card not worked out again yet), or no recording.
        ContactCards.write(app,mapOf(key to card()),replaceAll=true)
        for(st in listOf(CallFollowupNotice.DONE,CallFollowupNotice.NO_RECORDING)) {
            SecureSettings(app).update { s -> val c=RepeatCalls.read(s).single()
                s.put(CallFollowupNotice.STATE_KEY,JSONArray().put(CallFollowupNotice.Entry(1,"한바다 소장",null,c.hash,c.startedMs,c.endedMs,state=st).json()).toString()) }
            ring();assertNull(st,CallCardOverlay.content?.repeat);idle()
        }
        // Still being sorted out: the line again.
        SecureSettings(app).update { s -> s.remove(CallFollowupNotice.STATE_KEY) }
        ring();assertNotNull(CallCardOverlay.content?.repeat);idle()
    }

    @Test fun oneOfTwoSortedOutSaysWhichIsLeft() {
        pastCall(number,9,12,175);pastCall(number,11,40,40)
        ContactCards.write(app,mapOf(key to card(settled=listOf(at(9,12)+11_000))),replaceAll=true)
        ring()
        assertEquals("오늘 2번 통화했어요. 11:40 통화(40초)는 아직 정리 중이에요",CallCardOverlay.content?.repeat)
        idle()
        // Two calls a minute apart: a result goes to the call it is nearest to, not to both.
        val s=SecureSettings(app).snapshot();val h=LocalPrivacy.numberHash(s,key)
        val a=RepeatCalls.Call(h,at(13,0),at(13,1),50_000,false,true);val b=RepeatCalls.Call(h,at(13,2),at(13,4),110_000,false,true)
        assertEquals(setOf(b),RepeatCalls.settled(listOf(a,b),card(settled=listOf(at(13,2)+8_000))))
        assertEquals("오늘 3번 통화했어요. 2번은 아직 정리 중이에요(마지막 13:00)",RepeatCalls.line(3,listOf(a,RepeatCalls.Call(h,at(9,0),at(9,1),null,false,true))))
    }

    @Test fun onlyYesterdaysCallNotSortedOutGivesNoLine() {
        pastCall(number,17,30,200,daysAgo=1)
        // Kept until the next write (the day's first); read as yesterday's anyway.
        SecureSettings(app).update { s -> s.put(RepeatCalls.KEY,JSONArray().put(RepeatCalls.Call(LocalPrivacy.numberHash(s,key),at(17,30,1),at(17,34,1),200_000,false,true).json()).toString()) }
        ring()
        assertNull(CallCardOverlay.content?.repeat);assertNull(windows.single().findViewWithTag<View>("card_repeat"))
        idle()
    }

    @Test fun anUnknownNumberCalledTodayGetsACardOfTheLineOnly() {
        // Numbers without a card are off (the default): before v4.7 nothing showed.
        ring(stranger)
        assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
        pastCall(stranger,10,5,62)
        ring(stranger)
        val v=windows.single().apply { measureAt(this) }
        assertEquals("오늘 10:05에 통화했어요(1분). 아직 정리 중이에요",text(v,"card_first"))
        assertNull("the line once, not twice",v.findViewWithTag<View>("card_repeat"))
        assertNull(v.findViewWithTag<View>("card_who"))
        assertFalse("nothing to keep in view during the call",CallCardOverlay.content!!.hasBody)
        idle()
        // Its notice (the overlay may not be drawn): the same title as a number without a card, the line as its text.
        draw=false;ring(stranger)
        val n=notices.single()
        assertEquals(CallCardView.Content.UNKNOWN,n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("오늘 10:05에 통화했어요(1분). 아직 정리 중이에요",line(n));assertEquals(listOf("오늘 10:05에 통화했어요(1분). 아직 정리 중이에요"),big(n))
        idle()
    }

    @Test fun aPersonalOrExcludedNumberGetsNothingAndTheSwitchOffStaysOff() {
        pastCall("010-0000-0001",9,0,120)
        assertTrue("never kept",stored().isEmpty())
        // Even with an entry for it (written before it was found personal): ignored is nothing at all.
        SecureSettings(app).update { s -> s.put(RepeatCalls.KEY,JSONArray().put(RepeatCalls.Call(LocalPrivacy.numberHash(s,"01000000001"),at(9,0),at(9,2),120_000,false,true).json()).toString()) }
        ring("010-0000-0001")
        assertTrue(CallSession.current().ignored);assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
        // The card switch for calls coming in is off: nothing, as before.
        pastCall(number,9,12,175)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.CARD_INCOMING,false) }
        ring()
        assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        idle()
        val t=stats()
        assertEquals("the ignored call is not counted",1,t.getInt(CardStats.REPEAT_TODAY))
        assertEquals("the line was not due with the switch off",0,t.getInt(CardStats.REPEAT_PENDING))
        assertEquals(0,t.getInt(CardStats.REPEAT_SHOWN))
    }

    @Test fun inTheNoticesFourLinesTheLineComesFirstAndTheLastGoes() {
        val tasks=listOf(ContactCard.Task("wf_1","도면 다시 받기",wall-day,"open",null),ContactCard.Task("wf_2","현장 사진 보내기",wall-2*day,"open",null),
            ContactCard.Task("wf_3","타설 일정 묻기",wall-4*day,"open",null),ContactCard.Task("wf_4","장비 반납",wall-5*day,"open",null))
        ContactCards.write(app,mapOf(key to card(tasks)),replaceAll=true)
        // Without the line: three items and the last call.
        draw=false;ring()
        val before=big(notices.single());assertEquals(4,before.size);assertTrue(before[3],before[3].contains("통화: 자재 단가"))
        idle()
        pastCall(number,9,12,175)
        ring()
        val n=notices.single();val lines=big(n)
        assertEquals(4,lines.size)
        assertEquals("오늘 09:12에 통화했어요(3분). 아직 정리 중이에요",lines[0])
        assertEquals(before.take(3),lines.drop(1))
        assertEquals("the folded notice's one line is the line",lines[0],line(n))
        // The lock screen's public version is as before: no times of calls on it.
        assertFalse(n.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("통화했어요"))
        idle()
    }

    @Test fun largeLettersWrapTheLineWithoutCuttingIt() {
        val c=CallCardView.Content.of(card(),wall).withRepeat("오늘 2번 통화했어요(마지막 11:40). 아직 정리 중이에요")
        for(scale in listOf(1.0f,1.5f,2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            val v=CallCardView(app).apply { showAll(c) };measureAt(v)
            val t=v.findViewWithTag<TextView>("card_repeat")
            assertEquals(c.repeat,t.text.toString());assertNull("not ellipsized",t.ellipsize)
            val l=t.layout;assertTrue("$scale: all of it laid out",l.getLineEnd(l.lineCount-1)==t.text.length && l.getEllipsisCount(l.lineCount-1)==0)
            if(scale==2.0f) assertTrue("${t.lineCount}",t.lineCount>=2)
        }
    }

    @Test fun wordsAndLengths() {
        assertEquals("",RepeatCalls.length(null));assertEquals("(40초)",RepeatCalls.length(40_000));assertEquals("(1분)",RepeatCalls.length(60_000))
        assertEquals("(3분)",RepeatCalls.length(175_000));assertEquals("(12분)",RepeatCalls.length(12*60_000+10_000))
        val h="x"
        assertEquals("오늘 09:12에 통화했어요. 아직 정리 중이에요",RepeatCalls.line(1,listOf(RepeatCalls.Call(h,at(9,12),at(9,15),null,true,true))))
        assertNull(RepeatCalls.line(2,emptyList()))
        // Not recorded at all (the last check, fresh): nothing is said.
        pastCall(number,9,12,175)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.RECORDING_GAP,JSONObject().put("verdict",PipelineSettingsKeys.GAP_NO_RECORDING).put("atMs",wall)) }
        wall=at(14,0)
        val i=RepeatCalls.info(SecureSettings(app).snapshot(),key,card(),wall)!!
        assertEquals(1,i.today);assertNull(i.line)
    }

    // ---- r28-2: the counts ------------------------------------------------------------------------------------------

    @Test fun theThreeCountsOfTheHeartbeat() {
        draw=true
        ring();idle() // not called today yet
        pastCall(number,9,12,175)
        ring();idle() // the line on the card
        ContactCards.write(app,mapOf(key to card(settled=listOf(at(9,12)+12_000))),replaceAll=true)
        ring();idle() // called today, sorted out
        // The line due but neither window nor notice could go up (notices not allowed, the check says no).
        ContactCards.write(app,mapOf(key to card()),replaceAll=true);draw=false
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ring();idle()
        val t=stats()
        assertEquals(3,t.getInt(CardStats.REPEAT_TODAY));assertEquals(2,t.getInt(CardStats.REPEAT_PENDING));assertEquals(1,t.getInt(CardStats.REPEAT_SHOWN))
        assertTrue(CardStats.KINDS.containsAll(listOf("repeatToday","repeatPending","repeatShown")))
    }

    // ---- the ringing path ------------------------------------------------------------------------------------------

    /** The line is worked out from the call's settings snapshot and the card in memory: no file is opened for it, and the work
     * (a fingerprint and today's list, 80 entries at most) takes well under a millisecond (measured here, on the host). */
    @Test fun theLineReadsNothingAndIsQuick() {
        SecureSettings(app).update { s -> val h=LocalPrivacy.numberHash(s,"01099990000")
            s.put(RepeatCalls.KEY,RepeatCalls.write((0 until 79).map { RepeatCalls.Call(h,at(8,0)+it*60_000L,at(8,0)+it*60_000L+30_000,30_000,false,true) }+
                RepeatCalls.Call(LocalPrivacy.numberHash(s,key),at(13,0),at(13,3),180_000,false,true),wall)) }
        val s=SecureSettings(app).snapshot();val c=card()
        val cardOpens=ContactCards.opens
        RepeatCalls.info(s,key,c,wall) // warm
        val n=2_000;val t0=System.nanoTime()
        repeat(n) { RepeatCalls.info(s,key,c,wall) }
        val perCallUs=(System.nanoTime()-t0)/1_000/n
        println("R28 repeat line: ${perCallUs} us per call (80 entries, host JVM)")
        assertTrue("$perCallUs us",perCallUs<5_000)
        assertEquals(ContactCards.opens,cardOpens)
        assertEquals("오늘 13:00에 통화했어요(3분). 아직 정리 중이에요",RepeatCalls.info(s,key,c,wall)!!.line)
    }

    @Test fun pictures() {
        org.junit.Assume.assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!shotsDir.isNullOrBlank())
        val shots=java.io.File(shotsDir!!).apply { mkdirs() }
        fun shot(name:String,content:CallCardView.Content,scale:Float) {
            RuntimeEnvironment.setFontScale(scale);CallUi.applyTheme(app)
            val v=CallCardView(app).apply { showAll(content) };measureAt(v)
            val bmp=Bitmap.createBitmap(v.measuredWidth,v.measuredHeight.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val cv=Canvas(bmp);cv.drawColor(CallUi.PAGE);v.draw(cv)
            shots.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        val one=CallCardView.Content.of(card(),wall).withRepeat("오늘 09:12에 통화했어요(3분). 아직 정리 중이에요")
        shot("card_repeat_one_10",one,1.0f);shot("card_repeat_one_15",one,1.5f);shot("card_repeat_one_20",one,2.0f)
        shot("card_repeat_many_10",CallCardView.Content.of(card(),wall).withRepeat("오늘 2번 통화했어요(마지막 11:40). 아직 정리 중이에요"),1.0f)
        shot("card_repeat_only_10",CallCardView.Content.repeatOnly("오늘 10:05에 통화했어요(1분). 아직 정리 중이에요"),1.0f)
        // The notice's words as text beside the pictures.
        pastCall(number,9,12,175);pastCall(number,11,40,40);draw=false;ring()
        val n=notices.single()
        shots.resolve("notice_repeat_many.txt").writeText("title: ${n.extras.getCharSequence(Notification.EXTRA_TITLE)}\nfolded: ${line(n)}\nopen:\n"+big(n).joinToString("\n")+"\n")
        idle()
        assertTrue(shots.resolve("card_repeat_one_10.png").length()>0)
    }

    private fun measureAt(v:View) {
        val dm=app.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels-2*CallUi.dp(app,12),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
    }
    private fun text(v:View,tag:String)=(v.findViewWithTag<View>(tag) as TextView).text.toString().replace(' ',' ')
}
