package kr.callbrief.pipeline.call

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.CallUi
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import kr.co.callsummary.core.android.ProtectedDatabase
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

/** v3.0 r11-2 (design 7.1, 7.2), v3.4 r14-2 (DESIGN_V4 4.2-1 .. 4.2-3): the 이음 call card over the call screen. One overlay
 * window, drawn in two steps, folded and opened, dragged and its place kept within its range, the window and the notice together
 * on a locked screen, the notice alone without the permission, unknown and personal numbers, and never a number or a transcript
 * on it. Generated numbers and cards only. A phone 1080x2400 (360x800dp, xxhdpi). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallCardOverlayTest:ProtectedTextHostTestBase() {
    private val app:android.app.Application get()=RuntimeEnvironment.getApplication()
    private val wm get()=app.getSystemService(WindowManager::class.java)
    private val windows get()=Shadow.extract<ShadowWindowManagerImpl>(wm).views.filterIsInstance<CallCardView>()
    private val nm get()=shadowOf(app.getSystemService(NotificationManager::class.java))
    private val notices get()=nm.allNotifications.filter { it.channelId==CallCardOverlay.CHANNEL }
    private val number="010-2222-3333";private val key="01022223333"
    private val stranger="010-9999-0000"
    private val outcomes=mutableListOf<String>()
    private val now=System.currentTimeMillis()
    private val day=86_400_000L
    private val h get()=app.resources.displayMetrics.heightPixels

    private fun yesterdayAt3pm():Long=ZonedDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(1).withHour(15).withMinute(0).withSecond(0).toInstant().toEpochMilli()
    private fun card(k:String=key)=ContactCard(k,listOf(ContactCard.Person("pe_1","김민수","대표",false)),ContactCard.Company("co_1","탄탄이엔씨","철근"),
        yesterdayAt3pm(),"in",
        listOf(ContactCard.Recent(now-day,"통화","견적서는 금요일까지 메일로 보내 드리기로 했어요.")),
        listOf(ContactCard.Task("wf_1","견적서 보내기",now-day,"open",null),ContactCard.Task("wf_2","철근 반입 날짜 다시 확인하기",now-2*day,"open",null),
            ContactCard.Task("wf_3","2층 슬래브 도면 받기",now-3*day,"open",null),ContactCard.Task("wf_4","네 번째 일",now,"open",null)),
        listOf(ContactCard.IssueLine("2층 골조 공사","다음 주 화요일 타설 예정이에요")),
        listOf(ContactCard.Text(now-day,"in","도면 보내 드렸어요")),now)

    @Before fun setup() {
        CallSession.resetForHost();CallCardOverlay.resetForHost()
        CallSession.isPrivate={ _,_ -> false };CallSession.afterCall={ true }
        CallCardOverlay.canDraw={ true };CallCardOverlay.locked={ false };CallCardOverlay.serviceUp={ false }
        CallCardOverlay.outcome={ _,w -> outcomes+=w };CallCardOverlay.onExpanded={};CallCardOverlay.onClosed={ _,_ -> }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ContactCards.shutdown();ContactCards.clearPending()
        ContactCards.write(app,mapOf(key to card()),replaceAll=true)
    }
    @After fun reset() { CallCardOverlay.resetForHost();CallSession.resetForHost();ContactCards.shutdown();ContactCards.clearPending()
        RuntimeEnvironment.setFontScale(1.0f) }

    /** The card's thread, then what it handed the main thread now (not what waits [ms] or more). */
    private fun settle(ms:Long=0) { CallCardOverlay.awaitIdle();shadowOf(Looper.getMainLooper()).idle();if(ms>0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun ring(raw:String=number) {
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_RINGING,raw)
        CallCardOverlay.show(app,raw,CallSession.State.RINGING)
    }
    private fun idle() { CallCardOverlay.show(app,null,CallSession.State.IDLE);CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"");settle() }
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tagged(v:View,tag:String)=v.findViewWithTag<View>(tag)
    private fun text(v:View,tag:String)=(tagged(v,tag) as TextView).text.toString().replace(' ',' ')
    private fun layout(v:View) {
        val dm=app.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels-2*CallUi.dp(app,12),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
    }
    private fun y(v:View)=(v.layoutParams as WindowManager.LayoutParams).y

    @Test fun ringingShowsOneOverlayWindowDrawnInTwoSteps() {
        val t0=SystemClock.uptimeMillis()
        ring();settle()
        assertEquals(1,windows.size)
        val v=windows.single()
        assertFalse("clock moved ${SystemClock.uptimeMillis()-t0} ms",v.complete)
        val p=v.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,p.type)
        val want=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE
        assertEquals(want,p.flags and want)
        assertEquals("only as big as the card",WindowManager.LayoutParams.WRAP_CONTENT,p.height)
        assertTrue(p.width<app.resources.displayMetrics.widthPixels)
        assertEquals((0.38f*h).toInt(),p.y)
        // Step 1: the first line and who it is.
        assertFalse(v.complete)
        assertEquals("견적서는 금요일까지 메일로 보내 드리기로 했어요",text(v,"card_first"))
        assertEquals("김민수 대표, 탄탄이엔씨, 어제",text(v,"card_who"))
        assertNull(tagged(v,"card_task"))
        // Step 2, within 50 ms: everything.
        settle(50)
        assertTrue(v.complete)
        assertEquals("체크할 일  네 번째 일  외 3개",text(v,"card_task"))
        assertEquals("진행 중인 일  2층 골조 공사, 다음 주",text(v,"card_issue"))
        assertNotNull(tagged(v,"card_collapse"));assertNotNull(tagged(v,"card_close"))
        assertEquals(listOf(CallCardOverlay.SHOWN),outcomes)
        assertTrue(notices.isEmpty())
    }

    @Test fun offHookFoldsAfterThreeSecondsIntoTheTopAndTheStripOpensAgain() {
        var expanded=0;CallCardOverlay.onExpanded={ expanded++ }
        ring();settle(50)
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,"")
        CallCardOverlay.show(app,null,CallSession.State.OFFHOOK);settle(2_900)
        val v=windows.single();assertFalse(v.collapsed)
        settle(200);assertTrue(v.collapsed)
        assertEquals("김민수 대표, 견적서는 금요일까지 메일로 보내",text(v,"card_strip_text"))
        assertNull(tagged(v,"card_first"))
        // 4.2-2: the strip's top within 8% .. 20% of the screen.
        assertTrue("${y(v)}",y(v) in (0.08*h).toInt()..(0.20*h).toInt())
        tagged(v,"card_strip").performClick();settle()
        assertFalse(v.collapsed);assertNotNull(tagged(v,"card_first"))
        assertEquals("opening the strip by hand is counted",1,expanded)
        settle(5_000);assertFalse("opened by hand stays open",v.collapsed)
        tagged(v,"card_collapse").performClick();settle();assertTrue(v.collapsed)
        CallCardOverlay.expand();settle();assertFalse(v.collapsed)
        CallCardOverlay.collapse();settle();assertTrue(v.collapsed)
        assertEquals("still one window",1,windows.size)
        // Off the main thread too.
        Thread { CallCardOverlay.expand() }.apply { start();join() };settle();assertFalse(v.collapsed)
        CallCardOverlay.hide();settle()
        assertTrue(windows.isEmpty())
    }

    /** 4.2-2: a call going out opens at the top (within 25%), folds after 3 seconds (the 15 s constant is gone). */
    @Test fun aCallGoingOutOpensAtTheTopThenFoldsAfterThreeSeconds() {
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,number)
        assertEquals(CallSession.Direction.OUT,CallSession.current().direction)
        CallCardOverlay.show(app,number,CallSession.State.OFFHOOK);settle(50)
        val v=windows.single();layout(v);CallCardOverlay.relayout();settle()
        assertFalse(v.collapsed)
        assertTrue("top ${y(v)}",y(v)<=(0.25*h).toInt())
        assertTrue("lower edge ${y(v)+v.measuredHeight}",y(v)+v.measuredHeight<=(0.25*h).toInt()+1)
        settle(2_900);assertFalse(v.collapsed)
        settle(200);assertTrue(v.collapsed)
        assertTrue(y(v) in (0.08*h).toInt()..(0.20*h).toInt())
    }

    /** 4.2-2: no summary and no work (a thin card): at OFFHOOK it goes instead of folding. */
    @Test fun aCardWithNothingToKeepGoesInsteadOfFolding() {
        ContactCards.write(app,mapOf(key to ContactCard(key,emptyList(),null,now-3*day,"in",emptyList(),emptyList(),emptyList(),emptyList(),now,ContactCard.Thin("현장",2))),replaceAll=true)
        ring();settle(50)
        assertEquals(1,windows.size)
        CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_OFFHOOK,"")
        CallCardOverlay.show(app,null,CallSession.State.OFFHOOK);settle(3_100)
        assertTrue(windows.isEmpty())
        CallCardOverlay.show(app,null,CallSession.State.OFFHOOK);settle(50)
        assertTrue("not again in this call",windows.isEmpty())
    }

    /** 4.2-2: with the letters 1.3 times and a full card, the lower edge stays at 72% at most, also where the user dragged it. */
    @Test fun aFullCardWithLargeLettersKeepsTheAnswerButtonsFree() {
        RuntimeEnvironment.setFontScale(1.3f)
        SecureSettings(app).update { it.put(CallCardOverlay.KEY_POS_Y,0.9) }
        val full=card().copy(openTasks=card().openTasks.mapIndexed { i,t -> t.copy(title="아주 긴 체크할 일 제목이 두 줄 세 줄로 넘어가도록 길게 적어 둔 일 $i",dueMs=now+i*day) })
        ContactCards.write(app,mapOf(key to full),replaceAll=true)
        ring();settle(50)
        val v=windows.single();layout(v);CallCardOverlay.relayout();settle()
        assertTrue("height ${v.measuredHeight}",v.measuredHeight<=(0.34*h).toInt())
        assertTrue("lower edge ${y(v)+v.measuredHeight} of $h",y(v)+v.measuredHeight<=(0.72*h).toInt()+1)
    }

    @Test fun closeTakesTheCardAwayForTheRestOfTheCall() {
        val closed=mutableListOf<Long>();CallCardOverlay.onClosed={ _,ms -> closed+=ms }
        ring();settle(50)
        tagged(windows.single(),"card_close").performClick();settle()
        assertTrue(windows.isEmpty())
        assertEquals("closing is counted with how long it showed",1,closed.size);assertTrue(closed.single()<2_000)
        CallCardOverlay.show(app,null,CallSession.State.OFFHOOK);settle(50)
        assertTrue(windows.isEmpty())
        CallCardOverlay.show(app,number,CallSession.State.IDLE);settle()
        ring();settle(50);assertEquals("the next call shows again",1,windows.size)
    }

    @Test fun draggingTheHandleMovesTheCardAndKeepsThePlaceWithinItsRange() {
        ring();settle(50)
        val v=windows.single();layout(v)
        val handle=tagged(v,"card_handle")
        val t=SystemClock.uptimeMillis()
        fun ev(action:Int,y:Float)=MotionEvent.obtain(t,t,action,100f,y,0)
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN,10f))
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE,-150f))
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE,-310f))
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_UP,-310f))
        settle()
        val y=y(v)
        assertEquals((0.38f*h).toInt()-320,y)
        val kept=SecureSettings(app).snapshot().optDouble(CallCardOverlay.KEY_POS_Y)
        assertEquals(y.toDouble()/h,kept,0.001)
        idle()
        ring();settle(50)
        val next=windows.single()
        assertEquals("the next card opens where it was left",y,y(next))
        // Dragged far down: pulled back so the lower edge stays at 72%.
        layout(next)
        val nextHandle=tagged(next,"card_handle")
        nextHandle.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN,0f))
        nextHandle.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE,200f))
        nextHandle.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE,2_000f))
        nextHandle.dispatchTouchEvent(ev(MotionEvent.ACTION_UP,2_000f))
        settle()
        assertTrue("${y(next)}+${next.height}",y(next)+next.height<=(0.72*h).toInt()+1)
    }

    @Test fun withoutPermissionASilentNoticeInTheCardsOrder() {
        CallCardOverlay.canDraw={ false }
        ring();settle(50)
        assertTrue(windows.isEmpty())
        val n=notices.single()
        assertEquals("김민수 대표",n.extras.getString(android.app.Notification.EXTRA_TITLE))
        val big=n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertEquals(listOf("견적서는 금요일까지 메일로 보내 드리기로 했어요","탄탄이엔씨, 어제","체크할 일: 네 번째 일 외 3개","진행 중인 일: 2층 골조 공사, 다음 주"),big.lines())
        assertFalse(big,Regex("\\d{3,4}-?\\d{4}").containsMatchIn(big))
        val ch=app.getSystemService(NotificationManager::class.java).getNotificationChannel(CallCardOverlay.CHANNEL)
        assertEquals(CallCardOverlay.CHANNEL_NAME,ch.name.toString());assertNull(ch.sound);assertFalse(ch.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_HIGH,ch.importance)
        assertEquals(listOf(CallCardOverlay.NO_PERMISSION),outcomes)
        CallCardOverlay.hide();settle();assertTrue(notices.isEmpty())
    }

    /** v3.4 (DESIGN_V4 4.2-1): locked, the window and the notice come together; unlocking takes the notice away. */
    @Test fun lockedShowsTheWindowAndTheNoticeTogether() {
        CallCardOverlay.locked={ true }
        ring();settle(50)
        assertEquals(1,windows.size);assertEquals(1,notices.size)
        assertEquals("the window counts as shown (CardStats tells locked apart)",listOf(CallCardOverlay.SHOWN),outcomes)
        CallCardOverlay.locked={ false }
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT));settle(50)
        assertEquals(1,windows.size);assertTrue(windows.single().complete)
        assertTrue("the notice goes once unlocked",notices.isEmpty())
    }

    /** v3.4 (4.2-1): while the card's service runs, the notice takes its slot: one notice, not two. */
    @Test fun withTheServiceUpTheNoticeTakesTheServiceSlot() {
        CallCardOverlay.locked={ true };CallCardOverlay.serviceUp={ true }
        ring();settle(50)
        assertNull(nm.getNotification(CallCardOverlay.NOTICE_TAG,CallCardOverlay.NOTICE_ID))
        val slot=nm.getNotification(null,CallCardService.NOTIFICATION_ID)
        assertNotNull(slot);assertEquals(CallCardOverlay.CHANNEL,slot.channelId);assertEquals(1,nm.allNotifications.size)
        CallCardOverlay.locked={ false }
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT));settle(50)
        assertEquals("the service's own words come back",CallCardService.CHANNEL,nm.getNotification(null,CallCardService.NOTIFICATION_ID).channelId)
        assertEquals(1,nm.allNotifications.size)
    }

    @Test fun unknownNumberOnlyWhenTurnedOnAndNeverFromTheCallStore() {
        ring(stranger);settle(50)
        assertTrue(windows.isEmpty());assertTrue(notices.isEmpty())
        assertEquals(listOf(CallCardOverlay.NO_CARD),outcomes)
        idle()
        SecureSettings(app).update { it.put(CallCardOverlay.KEY_UNKNOWN,true) }
        CallCardOverlay.logLine={ _,_ -> "3일 전 통화, 30일간 2번" }
        kr.callbrief.IeumApplication.shutdown()
        val opens0=ProtectedDatabase.openCount();val cardOpens0=ContactCards.opens
        ring(stranger);settle(50)
        val v=windows.single()
        assertEquals("아직 정리된 통화가 없는 번호예요",text(v,"card_first"))
        assertEquals("3일 전 통화, 30일간 2번",text(v,"card_who"))
        // 4.1-2 (handed on by r14-1): the ringing path opens no call store, only (at most) the card file.
        assertEquals(ContactCards.opens-cardOpens0,ProtectedDatabase.openCount()-opens0)
    }

    @Test fun personalNumberShowsNothing() {
        CallSession.isPrivate={ _,_ -> true }
        SecureSettings(app).update { it.put(CallCardOverlay.KEY_UNKNOWN,true) }
        ring();settle(50)
        assertTrue(CallSession.current().ignored)
        assertTrue(windows.isEmpty());assertTrue(notices.isEmpty());assertTrue(outcomes.isEmpty())
    }

    @Test fun theCardNeverShowsTheNumberOrTheTranscript() {
        val leaky=card().copy(people=listOf(ContactCard.Person("pe_1","010-2222-3333","",false)),company=null,
            recent=listOf(ContactCard.Recent(now,"통화","010-2222-3333 번호로 다시 걸어 주세요.")))
        ContactCards.write(app,mapOf(key to leaky),replaceAll=true)
        ring();settle(50)
        val all=windows.single().texts().joinToString("\n")
        assertFalse(all,all.contains("2222") || all.contains("3333"))
        assertFalse(all,all.contains("자막"))
        assertTrue(all,all.contains("지난 통화가 있는 상대예요"))
    }

    @Test fun cardTextFollowsTheSeniorRules() {
        listOf("+notnight","+night").forEach { q ->
            RuntimeEnvironment.setQualifiers(q)
            CallCardOverlay.hide();settle()
            ring();settle(50)
            val v=windows.single();layout(v)
            val texts=views(v).filterIsInstance<TextView>()
            texts.forEach { t ->
                val sp=t.textSize/t.resources.displayMetrics.scaledDensity
                val min=(t.getTag(kr.callbrief.R.id.ui_min_sp) as? Float) ?: 18f
                assertTrue("$q ${t.text} $sp",sp>=min)
                assertFalse(t.text.toString(),t.text.contains("·") || t.text.contains("…") || t.text.contains("..."))
                assertTrue("$q ${t.text} contrast",contrast(t.currentTextColor,CallUi.CARD)>=4.5)
            }
            assertTrue("$q first line 20",texts.first { it.tag=="card_first" }.let { it.textSize/it.resources.displayMetrics.scaledDensity }>=20f)
            assertTrue("$q first line bold",texts.first { it.tag=="card_first" }.typeface?.isBold!=false)
            assertTrue("buttons 44dp",listOf("card_collapse","card_close").all { tagged(v,it).measuredHeight>=CallUi.dp(app,44) })
            assertTrue("34% of the screen at most",v.measuredHeight<=0.34*h)
            // 4.2-3: five lines at most (the first line two of them).
            val rows=listOf("card_first","card_who","card_task","card_issue").map { (tagged(v,it) as? TextView)?.takeIf { t -> t.visibility==View.VISIBLE }?.lineCount ?: 0 }
            assertTrue("$q $rows lines",rows.sum()<=5)
            CallCardOverlay.hide();settle()
            CallSession.onPhoneState(app,TelephonyManager.EXTRA_STATE_IDLE,"")
        }
        RuntimeEnvironment.setQualifiers("+notnight");CallUi.applyTheme(app)
    }

    /** 4.2-3: at 1.3 times the five lines render in seven at most. */
    @Test fun largeLettersRenderTheCardInSevenLinesAtMost() {
        RuntimeEnvironment.setFontScale(1.3f)
        val v=CallCardView(app).apply { showAll(CallCardView.Content.of(card(),now)) }
        layout(v)
        val lines=listOf("card_first","card_who","card_task","card_issue").sumOf { (tagged(v,it) as? TextView)?.lineCount ?: 0 }
        assertTrue("$lines",lines<=7)
    }

    /** 4.2-3: TalkBack reads the card in the order it is drawn, top to bottom. */
    @Test fun talkBackReadsInScreenOrder() {
        val v=CallCardView(app).apply { showAll(CallCardView.Content.of(card(),now)) }
        layout(v)
        val spoken=v.spoken()
        fun top(x:View):Int { val at=IntArray(2);x.getLocationInWindow(at);return at[1] }
        val tops=spoken.map(::top)
        assertEquals(listOf("card_handle","card_collapse","card_close","card_first","card_who","card_task","card_issue"),spoken.map { it.tag })
        assertEquals("top to bottom",tops.sorted(),tops)
    }

    @Test fun aTallCardLeavesOutWholeRowsNotHalves() {
        val long=card().copy(recent=listOf(ContactCard.Recent(now,"통화","가".repeat(40))),
            openTasks=List(3) { ContactCard.Task("wf_$it","아주 긴 체크할 일 제목이 두 줄 세 줄로 넘어가도록 길게 적어 둔 일 $it 번째",now,"open",null) })
        val v=CallCardView(app).apply { maxHeightPx=CallUi.dp(app,170);showAll(CallCardView.Content.of(long)) }
        layout(v)
        assertTrue(v.measuredHeight<=CallUi.dp(app,170))
        assertEquals(View.GONE,tagged(v,"card_issue").visibility)
        assertEquals(View.VISIBLE,tagged(v,"card_first").visibility)
    }

    /** v3.3 (DESIGN_V4 3.1-5, 1.3 #9): the locked screen shows the name, the company and one check item, no number or amount,
     * nothing of the call; with "잠금 화면에서도 이름과 한 줄 보기" off, as before. */
    @Test fun lockedScreenShowsTheNameAndOneLineUnlessTurnedOff() {
        val money=card().copy(openTasks=listOf(ContactCard.Task("wf_1","철근 대금 50만원 입금 확인",now,"open",null,tentative=true)))
        ContactCards.write(app,mapOf(key to money),replaceAll=true)
        CallCardOverlay.locked={ true }
        ring();settle(50)
        val pub=notices.single().publicVersion!!
        assertEquals("김민수 대표",pub.extras.getString(android.app.Notification.EXTRA_TITLE))
        val line=pub.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(line,line.contains("탄탄이엔씨") && line.contains("체크할 일: 철근 대금"))
        assertFalse(line,line.contains("50") || line.contains("만원") || line.contains("견적서는 금요일") || line.contains("2222"))
        idle()
        SecureSettings(app).update { it.put(CallCardOverlay.KEY_LOCK_DETAIL,false) }
        ring();settle(50)
        val off=notices.single().publicVersion!!
        assertEquals("이음 통화 카드",off.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("잠금을 풀면 카드가 보여요",off.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        // The private notice itself says which item needs a check (first, as on the card).
        assertTrue(notices.single().extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().startsWith("철근 대금 50만원 입금 확인, 오늘 요청, 확인 필요"))
    }

    /** v3.3 (DESIGN_V4 3.1-5): a number only the call log knows shows a thin card, not "noCard". */
    @Test fun aThinCardShowsWhenTheLogKnowsTheNumber() {
        ContactCards.write(app,mapOf(key to ContactCard(key,emptyList(),null,now-3*day,"in",emptyList(),emptyList(),emptyList(),emptyList(),now,ContactCard.Thin("현장",2))),replaceAll=true)
        ring();settle(50)
        val v=windows.single()
        assertEquals("3일 전 통화, 30일간 2번",text(v,"card_first"))
        assertEquals(listOf(CallCardOverlay.SHOWN),outcomes)
        assertFalse(v.texts().joinToString().contains("2222"))
    }

    /** v3.3 (DESIGN_V4 3.1-2), v3.4 4.2-3 ②: "확인 필요" in front of the first line when its item needs a check. */
    @Test fun unsureItemsCarryTheBadgeOnTheCard() {
        val unsure=card().copy(recent=emptyList(),
            openTasks=listOf(ContactCard.Task("wf_1","철근 30톤 반입 확인",now-day,"open",null,tentative=true,checkReason="confusable"),ContactCard.Task("wf_2","도면 받기",now-day,"open",null)))
        val v=CallCardView(app).apply { showAll(CallCardView.Content.of(unsure)) }
        val first=(tagged(v,"card_first") as TextView).text
        assertTrue(first.toString(),first.toString().trim().startsWith("확인 필요"))
        val spans=(first as android.text.Spanned).getSpans(0,first.length,android.text.style.BackgroundColorSpan::class.java)
        assertEquals(CallUi.AMBER_RAIL,spans.single().backgroundColor)
        assertFalse((tagged(v,"card_task") as TextView).text.toString().contains("확인 필요"))
        assertTrue(contrast(0xFF191F28.toInt(),CallUi.AMBER_RAIL)>=4.5)
    }

    private fun luminance(c:Int):Double { fun ch(v:Int):Double { val x=v/255.0;return if(x<=0.03928) x/12.92 else Math.pow((x+0.055)/1.055,2.4) }
        return 0.2126*ch(android.graphics.Color.red(c))+0.7152*ch(android.graphics.Color.green(c))+0.0722*ch(android.graphics.Color.blue(c)) }
    private fun contrast(a:Int,b:Int):Double { val (lo,hi)=listOf(luminance(a),luminance(b)).sorted();return (hi+0.05)/(lo+0.05) }

    /** Pictures for a person to look at (not a check): with IEUM_CARD_SHOTS_DIR set, the open and folded card on a
     * 1080 px wide dark call-screen stand-in. */
    @Test fun pictures() {
        val out=System.getenv("IEUM_CARD_SHOTS_DIR")
        org.junit.Assume.assumeTrue(!out.isNullOrBlank())
        ring();settle(50)
        val v=windows.single()
        fun shot(name:String) {
            layout(v)
            val w=app.resources.displayMetrics.widthPixels;val m=CallUi.dp(app,12)
            val bmp=Bitmap.createBitmap(w,v.measuredHeight+2*m,Bitmap.Config.ARGB_8888);val c=Canvas(bmp)
            c.drawColor(android.graphics.Color.parseColor("#2B3440"))
            c.save();c.translate(m.toFloat(),m.toFloat());v.draw(c);c.restore()
            java.io.File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        shot("card_expanded")
        CallCardOverlay.collapse();settle();shot("card_collapsed")
    }
}
