package kr.callbrief.pipeline

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.report.KakaoReport
import kr.callbrief.pipeline.report.ReportFetch
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
import java.time.LocalDate

/** v3.5 r15-3 (DESIGN_V5 2.3): the settings group "아침 보고", the morning app notice as the KakaoTalk report's fallback, the
 * 알림 light and the evening self-check line for KakaoTalk, and the KakaoTalk button's execution parameters opening the screen.
 * The Kakao SDK is never called: the app key, the login and the sender are test fakes. Generated data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R15ThreeTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val h=3_600_000L
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun put(block:(org.json.JSONObject)->Unit)=SecureSettings(app).update(block)

    /** Records what went; every send succeeds. */
    private class OkSender:KakaoReport.Sender {
        val sent=mutableListOf<KakaoReport.Message>()
        override suspend fun uploadImage(png:ByteArray):String?="https://k.kakaocdn.net/img/1.png"
        override suspend fun send(message:KakaoReport.Message,imageUrl:String?):String? { sent+=message;return null }
    }
    private val sender=OkSender()

    @Before fun setup() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        KakaoReport.pause={};KakaoReport.senderFactory={ sender };CallDigestKakao.sending={ false }
    }
    @After fun reset() {
        KakaoReport.appKey={ kr.callbrief.BuildConfig.KAKAO_APP_KEY };KakaoReport.pause={ kotlinx.coroutines.delay(it) }
        KakaoReport.senderFactory=null;KakaoReport.linker=null;KakaoReport.clock={ System.currentTimeMillis() }
        ReportFetch.clock={ System.currentTimeMillis() };CallDigestKakao.sending=null
        manager.cancelAll()
    }
    private fun withKey()=run { KakaoReport.appKey={ "abc123" } }
    private fun noKey()=run { KakaoReport.appKey={ "" } }
    private fun linked(on:Boolean=true)=put { it.put(PipelineSettingsKeys.KAKAO_LINKED,on) }

    // ---- 1. settings "아침 보고" ----
    @Test fun withoutAKakaoKeyTheGroupHasOnlyTheAppNoticeHour() {
        noKey()
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,StatusLights.GROUP_MORNING)!=null }
        val group=tag(a,StatusLights.GROUP_MORNING) as ViewGroup
        assertNotNull("the app notice hour moved here",group.findViewWithTag<View>("hour_"+PipelineSettingsKeys.MORNING_HOUR))
        listOf("kakao_switch","kakao_state","kakao_link","kakao_send","kakao_unlink").forEach { assertNull(it,tag(a,it)) }
        assertTrue(texts(a).none { it.contains("카카오톡") })
        // The evening hour stays in 정리.
        assertNotNull((tag(a,"group_정리") as ViewGroup).findViewWithTag<View>("hour_"+PipelineSettingsKeys.EVENING_HOUR))
        assertNull((tag(a,"group_정리") as ViewGroup).findViewWithTag<View>("hour_"+PipelineSettingsKeys.MORNING_HOUR))
    }
    @Test fun notLinkedShowsTheConnectButtonAndTheLoginLinksIt() {
        withKey()
        var asked=0;KakaoReport.linker={ asked++;null }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"kakao_link")!=null }
        assertTrue((tag(a,"kakao_switch") as Switch).isChecked)
        assertEquals("카카오톡 연결하기",(tag(a,"kakao_link") as TextView).text.toString())
        tag(a,"kakao_link").performClick()
        settle { (tag(a,"kakao_state") as? TextView)?.text?.toString()=="연결됨, 매일 5:30에 보내요" }
        assertEquals(1,asked);assertTrue(SecureSettings(app).snapshot().optBoolean(PipelineSettingsKeys.KAKAO_LINKED))
        assertEquals("아직 보낸 적이 없어요",(tag(a,"kakao_last") as TextView).text.toString())
        assertNotNull(tag(a,"kakao_send"));assertNotNull(tag(a,"kakao_unlink"))
    }
    @Test fun linkedSendsNowOnceADayAndSaysSoInKorean() {
        withKey();linked()
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"kakao_send")!=null }
        tag(a,"kakao_send").performClick()
        settle { (tag(a,"kakao_send_result") as? TextView)!=null }
        assertEquals(SettingsScreen.sendWords(null),(tag(a,"kakao_send_result") as TextView).text.toString())
        assertTrue(sender.sent.isNotEmpty())
        // r15-4 (M1): a test send is not the 05:30 report: the last send line stays as it was.
        assertEquals("아직 보낸 적이 없어요",(tag(a,"kakao_last") as TextView).text.toString())
        assertFalse(KakaoReport.sentToday(app))
        // The same day once more: nothing goes, and the line says so.
        val before=sender.sent.size
        tag(a,"kakao_send").performClick()
        settle { (tag(a,"kakao_send_result") as? TextView)?.text?.toString()==SettingsScreen.sendWords(KakaoReport.ALREADY_SENT) }
        assertEquals(before,sender.sent.size)
        // 연결 끊기: back to the connect button.
        tag(a,"kakao_unlink").performClick()
        settle { tag(a,"kakao_link")!=null }
        assertFalse(SecureSettings(app).snapshot().optBoolean(PipelineSettingsKeys.KAKAO_LINKED))
    }
    @Test fun aBrokenLinkAsksToConnectAgainAndTheSwitchOffHidesTheRows() {
        withKey();put { it.put(PipelineSettingsKeys.KAKAO_LINKED,false).put(PipelineSettingsKeys.KAKAO_LAST_ERROR,KakaoReport.TOKEN_PREFIX+"InvalidToken") }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"kakao_link")!=null }
        assertEquals("카카오톡 연결이 풀렸어요",(tag(a,"kakao_state") as TextView).text.toString())
        assertEquals("다시 연결",(tag(a,"kakao_link") as TextView).text.toString())
        (tag(a,"kakao_switch") as Switch).isChecked=false
        settle { tag(a,"kakao_off_note")!=null }
        assertNull(tag(a,"kakao_link"))
        assertFalse(SecureSettings(app).snapshot().optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true))
    }
    @Test fun theWordsOfTheGroup() {
        val now=LocalDate.of(2026,11,7).atTime(9,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        fun at(d:Int,hh:Int,mm:Int)=LocalDate.of(2026,11,d).atTime(hh,mm).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        assertEquals("오늘 5:30에 보냈어요",SettingsScreen.lastSentWords(at(7,5,30),now))
        assertEquals("어제 5:30에 보냈어요",SettingsScreen.lastSentWords(at(6,5,30),now))
        assertEquals("11월 4일 오후 1:05에 보냈어요",SettingsScreen.lastSentWords(at(4,13,5),now))
        assertEquals("아직 보낸 적이 없어요",SettingsScreen.lastSentWords(0,now))
        val all=listOf(null,KakaoReport.ALREADY_SENT,KakaoReport.NOT_LINKED,KakaoReport.UNAVAILABLE,KakaoReport.NO_REPORT,"TOKEN_InvalidToken","SEND_API_InternalError")
            .map(SettingsScreen::sendWords)
        assertEquals(all.size,all.toSet().size)
        all.forEach { assertFalse(it,Regex("[A-Za-z·…]").containsMatchIn(it)) }
    }

    // ---- 2. the morning notice ----
    private fun morning()=shadowOf(manager).allNotifications.filter { it.channelId==CallDigestWork.CHANNEL }
    private val message=CallDigestText.Message("체크할 일 1개","지금 할 일: 견적서 보내기",listOf("지금 할 일: 견적서 보내기"))
    @Test fun aFullKakaoSendTodayMeansAQuietMorningNotice() {
        withKey();linked();put { it.put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,System.currentTimeMillis()) }
        assertTrue(CallDigestKakao.covered(app))
        // r15-4 (H2): it still comes, quietly, and says the report went to KakaoTalk.
        assertTrue(CallDigestKakao.postMorning(app,message,waitForSend=true))
        val n=morning().single()
        assertEquals(CallDigestKakao.SENT_LINE,n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.GROUP_ALERT_SUMMARY,n.groupAlertBehavior)
    }
    @Test fun aFailedOrMissingSendKeepsTheMorningNotice() {
        withKey();linked()
        // Failed today: the last full send was yesterday.
        put { it.put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,System.currentTimeMillis()-30*h).put(PipelineSettingsKeys.KAKAO_FAILED_COUNT,1) }
        assertFalse(CallDigestKakao.covered(app))
        assertTrue(CallDigestKakao.postMorning(app,message,waitForSend=true));assertEquals(1,morning().size)
        manager.cancelAll()
        // Sent, but the switch is off: the notice as before.
        put { it.put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,System.currentTimeMillis()).put(PipelineSettingsKeys.MORNING_KAKAO,false) }
        assertTrue(CallDigestKakao.postMorning(app,message));assertEquals(1,morning().size)
        manager.cancelAll()
        // No Kakao key in the build.
        noKey();put { it.put(PipelineSettingsKeys.MORNING_KAKAO,true) }
        assertTrue(CallDigestKakao.postMorning(app,message));assertEquals(1,morning().size)
    }
    @Test fun theNoticeWaitsForASendInProgress() {
        withKey();linked()
        var polls=0
        // The 06:00 try is sending: it finishes while the notice waits.
        CallDigestKakao.sending={ polls++;if(polls>=2) { put { it.put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,System.currentTimeMillis()) };false } else true }
        assertTrue(CallDigestKakao.postMorning(app,message,waitForSend=true))
        assertEquals(CallDigestKakao.SENT_LINE,morning().single().extras.getString(Notification.EXTRA_TEXT));assertEquals(2,polls)
    }
    @Test fun theMorningOrderPutsChecksAndRecentRequestsFirst() {
        val today=LocalDate.of(2026,11,7)
        fun ms(d:LocalDate)=d.atTime(10,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        val items=listOf(CallTodoItem("old","오래된 일","","open",1,null,ms(today.minusDays(9))),
            CallTodoItem("mid","사흘 전 일","","open",1,null,ms(today.minusDays(3))),
            CallTodoItem("yes","어제 요청","","open",1,"△△철강",ms(today.minusDays(1))),
            CallTodoItem("now","오늘 요청","","open",1,null,ms(today)),
            CallTodoItem("chk","확인할 일","","needs_confirmation",1,null,ms(today.minusDays(5))),
            CallTodoItem("unsure","숫자 확인","","open",1,null,ms(today.minusDays(6)),null,true))
        assertEquals(listOf("chk","unsure","now","yes","mid","old"),CallDigestText.ranked(items,today).map { it.id })
        val m=CallDigestText.morning(items,today)
        assertEquals("chk",m.doneId)
        assertTrue(m.lines.first(),m.lines.first().startsWith(CallDigestText.NOW_PREFIX+"확인할 일"))
        assertTrue(m.lines[3],m.lines[3].startsWith("[△△철강] 어제 요청"))
    }

    // ---- 3. the 알림 light and the evening self-check ----
    @Test fun theNotifyLightTellsTheKakaoLink() {
        val now=1_800_000_000_000L
        val ok=StatusLights.Input(now=now,folderConnected=true,modelState="READY",consent=true)
        fun notify(i:StatusLights.Input)=StatusLights.lights(i).single { it.tag=="status_notify" }
        // Linked and sent this morning: green.
        assertTrue(notify(ok.copy(kakao=KakaoReport.Link.LINKED,kakaoLastSentMs=now-4*h)).ok)
        // The link came undone.
        val broken=notify(ok.copy(kakao=KakaoReport.Link.BROKEN))
        assertFalse(broken.ok);assertEquals(StatusLights.GROUP_MORNING,broken.target)
        assertTrue(broken.words,broken.words.startsWith("카카오톡 연결이 풀렸어요") && broken.words.contains("아래 아침 보고 묶음에서"))
        // Three days without a full send while the sends failed.
        val failing=notify(ok.copy(kakao=KakaoReport.Link.LINKED,kakaoLastSentMs=now-73*h,kakaoFailed=3))
        assertFalse(failing.ok);assertTrue(failing.words,failing.words.startsWith("아침 보고가 3일 넘게 카카오톡으로 가지 않았어요"))
        assertTrue("two days is not three",notify(ok.copy(kakao=KakaoReport.Link.LINKED,kakaoLastSentMs=now-50*h,kakaoFailed=2)).ok)
        assertFalse("never sent, three failures",notify(ok.copy(kakao=KakaoReport.Link.LINKED,kakaoFailed=3)).ok)
        assertTrue("never sent, no failure yet (just linked)",notify(ok.copy(kakao=KakaoReport.Link.LINKED)).ok)
        // The switch off, or no key in the build: nothing to say.
        assertTrue(notify(ok.copy(kakao=KakaoReport.Link.BROKEN,kakaoOn=false)).ok)
        assertTrue(notify(ok.copy(kakao=KakaoReport.Link.UNAVAILABLE,kakaoFailed=9)).ok)
        // Notifications off says that first, with no target in the app.
        val off=notify(ok.copy(notifyAllowed=false,kakao=KakaoReport.Link.BROKEN));assertTrue(off.words.startsWith("알림이 꺼져"));assertNull(off.target)
        listOf(broken,failing).forEach { assertFalse(it.words,Regex("[A-Za-z·…]").containsMatchIn(it.words)) }
    }
    @Test fun theLightReadsTheLinkFromTheSettings() {
        withKey()
        fun s(linked:Boolean,error:String)=org.json.JSONObject().put(PipelineSettingsKeys.KAKAO_LINKED,linked).put(PipelineSettingsKeys.KAKAO_LAST_ERROR,error)
        assertEquals(KakaoReport.Link.LINKED,StatusLights.kakaoLink(s(true,"")))
        assertEquals(KakaoReport.Link.BROKEN,StatusLights.kakaoLink(s(false,"TOKEN_InvalidToken")))
        assertEquals(KakaoReport.Link.NOT_LINKED,StatusLights.kakaoLink(s(false,"")))
        noKey();assertEquals(KakaoReport.Link.UNAVAILABLE,StatusLights.kakaoLink(s(true,"")))
    }
    @Test fun theEveningSelfCheckSaysOnceADayThatKakaoDidNotGo() {
        val now=1_800_000_000_000L
        val fine=SelfCheckNotices.Input(now,true,true,"READY",0,null,now,true,null,now,1024)
        assertTrue(SelfCheckNotices.check(fine).isEmpty())
        val found=SelfCheckNotices.check(fine.copy(kakaoMissed=true))
        assertEquals(SelfCheckNotices.KAKAO_MISSED,found.single().code)
        assertEquals("아침 보고가 카카오톡으로 안 갔어요",found.single().title)
        assertFalse(found.single().text,Regex("[A-Za-z·…]").containsMatchIn(found.single().text))
        val s=SelfCheckNotices.remember(org.json.JSONObject(),found,now)
        assertTrue("once a day",SelfCheckNotices.due(found,now+h,s).isEmpty())
        assertEquals(1,SelfCheckNotices.due(found,now+24*h,s).size)
        assertTrue(SelfCheckNotices.kakaoMissed(linked=true,on=true,sentToday=false))
        assertFalse(SelfCheckNotices.kakaoMissed(linked=true,on=true,sentToday=true))
        assertFalse(SelfCheckNotices.kakaoMissed(linked=false,on=true,sentToday=false))
        assertFalse(SelfCheckNotices.kakaoMissed(linked=true,on=false,sentToday=false))
    }

    // ---- 4. the KakaoTalk button's execution parameters ----
    private fun kakao(query:String)=Intent(Intent.ACTION_VIEW,Uri.parse("kakaoabc123://kakaolink?$query"))
    private fun day(a:MainActivity)=Bundle().also { a.calls.save(it) }.getString("calls_day")
    @Test fun theParametersAreRead() {
        val d=1_793_890_800_000L
        assertEquals(Screen.CALLS to d,MainActivity.kakaoTarget(Uri.parse("kakaoabc://kakaolink?screen=calls&dayMs=$d")))
        assertEquals(Screen.CALLS to null,MainActivity.kakaoTarget(Uri.parse("kakaoabc://kakaolink?screen=calls&dayMs=x")))
        assertEquals(Screen.TASKS to null,MainActivity.kakaoTarget(Uri.parse("kakaoabc://kakaolink?screen=tasks")))
        assertEquals(Screen.HOME to null,MainActivity.kakaoTarget(Uri.parse("kakaoabc://kakaolink?screen=settings2")))
        assertEquals(Screen.HOME to null,MainActivity.kakaoTarget(Uri.parse("kakaoabc://kakaolink")))
        assertNull(MainActivity.kakaoTarget(Uri.parse("kakaoabc://oauth?code=1")))
        assertNull(MainActivity.kakaoTarget(Uri.parse("https://kakaolink?screen=tasks")))
        assertNull(MainActivity.kakaoTarget(null))
    }
    @Test fun aKakaoButtonOpensTheCallsOfItsDay() {
        val yesterday=CallUi.today().minusDays(1)
        val ms=yesterday.atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        val a=Robolectric.buildActivity(MainActivity::class.java,kakao("screen=calls&dayMs=$ms")).setup().get()
        assertEquals(Screen.CALLS,a.screen);assertEquals(yesterday.toString(),day(a))
    }
    @Test fun aKakaoButtonOpensTheChecksAndAnUnknownOneHome() {
        val t=Robolectric.buildActivity(MainActivity::class.java,kakao("screen=tasks")).setup().get()
        assertEquals(Screen.TASKS,t.screen)
        val h=Robolectric.buildActivity(MainActivity::class.java,kakao("screen=nothing")).setup().get()
        assertEquals(Screen.HOME,h.screen)
    }
    @Test fun aKakaoButtonOverTheOpenAppSwitchesScreens() {
        val c=Robolectric.buildActivity(MainActivity::class.java,MainActivity.intent(app,Screen.SETTINGS)).setup()
        assertEquals(Screen.SETTINGS,c.get().screen)
        c.newIntent(kakao("screen=tasks"));assertEquals(Screen.TASKS,c.get().screen)
        val ms=CallUi.today().minusDays(2).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        c.newIntent(kakao("screen=calls&dayMs=$ms"));assertEquals(Screen.CALLS,c.get().screen)
        assertEquals(CallUi.today().minusDays(2).toString(),day(c.get()))
        // An unknown screen opens home, the Activity stays.
        c.newIntent(kakao("screen=zzz"));assertEquals(Screen.HOME,c.get().screen)
    }
}
