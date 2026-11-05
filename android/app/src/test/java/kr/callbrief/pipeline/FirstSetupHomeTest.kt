package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
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
import java.time.Duration

/** v3.1 r12-5 (DESIGN_V4 2.5): first setup with required and optional steps (a refused or put-off optional step opens the next
 * one), the voice model on any connection right after the consent, the one-line banners on home with the first check row still on
 * the first screen of a 393x852dp phone, the update state on home and in settings with [지금 확인] and [지금 업데이트] (r12-2's
 * functions through test hooks; empty values draw nothing), and [다시 정리하기] for a result that could not be applied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FirstSetupHomeTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(v:View)=views(v).filter { it.visibility==View.VISIBLE && isShown(it) }.filterIsInstance<TextView>().map { it.text.toString() }
    private fun isShown(v:View):Boolean { var p:View?=v;while(p!=null) { if(p.visibility!=View.VISIBLE) return false;p=p.parent as? View };return true }
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle(check:()->Boolean) { repeat(400) { idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private var modelStarts=0
    @Before fun hooks() { HomeScreen.modelStart={ modelStarts++ } }
    @After fun reset() {
        HomeScreen.modelStart={ PipelineModelDownloadWork.enqueue(it) };HomeScreen.gapHook=null;HomeScreen.updateHook=null
        HomeScreen.setupStepHook=null;HomeScreen.checkHook=null;CallRetry.hook=null
    }
    private fun home(s:HomeScreen.Snapshot):MainActivity {
        val a=launchMain(Screen.HOME);settle { tag(a,"home_today")!=null || tag(a,"retry_card")!=null };a.home.render(s);idle();return a
    }
    /** Every required step done: the optional ones are left. */
    private val required=HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0)
    private fun nextLabel(a:android.app.Activity)=(tag(a,"home_setup_next_label") as TextView).text.toString()
    /** The button's own home read is drawn (the host has no folder, so its first step is the folder). */
    private fun awaitRead(a:android.app.Activity) { settle { (tag(a,"home_setup_next_label") as? TextView)?.text?.toString()=="다음 할 일: 녹음 폴더 연결" } }

    // ---- 1. first setup ----
    @Test fun requiredStepsComeFirstAndOptionalOnesCanBePutOff() {
        // Nothing done yet: the folder first, and the call redirection role is no step any more (settings only).
        val a=home(HomeScreen.Snapshot(folder=false,consent=false,model="",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0,
            notify=false,battery=false,sms=false,callLog=false,phoneState=false,overlay=false,callRole=false,autoInstallStep=true))
        assertEquals("다음 할 일: 녹음 폴더 연결",nextLabel(a))
        val all=texts(tag(a,"home_setup")).joinToString("\n")
        assertTrue(all,all.contains("처음 설정 남은 9단계"))
        assertFalse(all,all.contains("/10") || all.contains("거는 전화"))
        // Required, in order: folder, consent, call log, call state, notifications, battery.
        val order=listOf("정리 동의","통화기록 읽기 허용","통화가 끝난 걸 바로 알아차리기","알림 허용","배터리 제한 풀기","문자 읽기 허용 (선택)",
            "통화 화면 위에 이음 카드 보기 (선택)","새 버전을 저절로 설치하기 (선택)")
        val shown=texts(tag(a,"home_setup"));assertEquals(order,shown.filter { it in order })
        // The reason sits right above the button, in reading size and ink.
        val reason=tag(a,"home_setup_detail") as TextView
        assertTrue(reason.textSize/a.resources.displayMetrics.scaledDensity>=CallUi.BODY)
        val box=reason.parent as ViewGroup;assertEquals(box.indexOfChild(reason)+1,box.indexOfChild(tag(a,"home_setup_next")))
        assertTrue(reason.text.contains("Recordings"))
        // Required steps have no [나중에 할게요].
        assertNull(tag(a,"home_setup_later"))
        // The required ones done: the texts step is optional and says so.
        val b=home(required.copy(sms=false,overlay=false,autoInstallStep=true))
        assertEquals("다음 할 일: 문자 읽기 허용",nextLabel(b))
        assertNotNull(tag(b,"home_setup_optional"));assertEquals("나중에 할게요",(tag(b,"home_setup_later") as TextView).text.toString())
    }

    @Test fun refusingTextsStillOpensTheNextStep() {
        // DESIGN_V4 2.5 시험: the texts question answered "no" (asked once, still refused): the step is done, the next opens.
        val s=required.copy(sms=false,overlay=false,autoInstallStep=true)
        ReadingPermission.markAsked(app,android.Manifest.permission.READ_SMS)
        val a=home(s)
        assertEquals("다음 할 일: 통화 화면 위에 이음 카드 보기",nextLabel(a))
        // [나중에 할게요] on the overlay step: the install step is next, and the overlay stays in settings.
        tag(a,"home_setup_later").performClick();awaitRead(a)
        a.home.render(s);idle()
        assertEquals("다음 할 일: 새 버전을 저절로 설치하기",nextLabel(a))
        assertTrue(SetupSkip.skipped(app,"overlay"))
        // Install step: the button opens r12-2's install-source setting (a hook stands in for setupStep here).
        HomeScreen.setupStepHook={ true }
        assertTrue(HomeScreen.installStep(app))
        tag(a,"home_setup_later").performClick();awaitRead(a);a.home.render(s);idle()
        assertNull("everything answered: no setup card",tag(a,"home_setup"))
    }

    @Test fun overlayStepComingBackWithoutTheSwitchSaysWhereToFindIt() {
        val s=required.copy(overlay=false)
        val a=home(s)
        assertEquals("카드 보기 허용",(tag(a,"home_setup_next") as TextView).text.toString())
        tag(a,"home_setup_next").performClick()
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,shadowOf(a).nextStartedActivity?.action)
        // Back from the list with the switch still off: bold words that say what to look for, and [다시 열기].
        a.home.render(s);idle()
        val hint=tag(a,"home_setup_detail") as TextView
        assertEquals("목록에서 '이음'을 찾아 스위치를 켜 주세요.",hint.text.toString())
        assertSame(CallUi.font(a,CallUi.Weight.SEMIBOLD),hint.typeface)
        assertEquals("다시 열기",(tag(a,"home_setup_next") as TextView).text.toString())
    }

    @Test fun onMobileDataTheConsentStartsTheModelAndTheNextStepsOpen() {
        // DESIGN_V4 2.5 시험 "모바일 데이터에서 알림, 배터리 단계로 진행": the model never holds a step, on any connection.
        val cm=app.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_MOBILE,0,true,android.net.NetworkInfo.State.CONNECTED))
        val start=required.copy(consent=false,model="",notify=false,battery=false)
        val a=home(start)
        assertEquals("다음 할 일: 정리 동의",nextLabel(a))
        if(tag(a,"home_setup_body").visibility!=View.VISIBLE) tag(a,"home_setup_banner").performClick()
        tag(a,"home_setup_next").performClick()
        settle { SecureSettings(app).snapshot().optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false) && modelStarts>0 }
        awaitRead(a)
        // The model is coming: its own line with the percent, and the setup card goes on to the next step.
        val coming=start.copy(consent=true,model=PipelineModelState.DOWNLOADING,modelBytes=57_404_120L)
        a.home.render(coming);idle()
        assertEquals("다음 할 일: 알림 허용",nextLabel(a))
        assertEquals("음성 인식 모델 받는 중 10% (57MB / 574MB)",(tag(a,"home_model_detail") as TextView).text.toString())
        assertTrue(texts(a.window.decorView).none { it.contains("Wi-Fi") })
        a.home.render(coming.copy(notify=true));idle()
        assertEquals("다음 할 일: 배터리 제한 풀기",nextLabel(a))
    }

    @Test fun modelLineShowsPercentAndSizes() {
        assertEquals("음성 인식 모델 받는 중 42% (241MB / 574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,241_100_000L))
        assertEquals("음성 인식 모델 받는 중 0% (0MB / 574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,0))
        assertEquals("인터넷에 연결되면 음성 인식 모델을 이어서 받아요 (574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,10,offline=true))
        assertTrue(HomeScreen.modelLine(PipelineModelState.FAILED,-1).contains("다시"))
        // No model line before the consent, and none once it is ready.
        assertNull(tag(home(required.copy(consent=false,model="")),"home_model"))
        assertNull(tag(home(required),"home_model"))
        val a=home(required.copy(model=PipelineModelState.DOWNLOADING,modelBytes=287_030_000L))
        assertEquals(50,(tag(a,"home_model_bar") as ProgressBar).progress)
    }

    // ---- 2. home banners ----
    private fun firstPassSnap()=required.copy(sms=false,overlay=false,autoInstallStep=true,calls=12,newTasks=3,
        firstPass=HomeScreen.FirstPassView(59,9.0,4,2,audioMinutesLeft=190),gap="최근 하루 통화 12건 중 녹음 0건",
        update=CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 42% (3.1MB / 7.0MB)",42),
        model=PipelineModelState.DOWNLOADING,modelBytes=241_100_000L,
        tasks=(1..3).map { OpenItem("t$it","견적서 보내기 $it","","open",1,"",null,createdMs=System.currentTimeMillis()) },
        byCompany=listOf("업체 없음" to 3))

    @Test fun bannersFoldSoTheFirstCheckRowIsOnTheFirstScreen() {
        // DESIGN_V4 2.5 시험: with first setup and 처음 정리 open, the first row of "먼저 볼 체크할 일" is still above the fold.
        val a=home(firstPassSnap())
        assertEquals("처음 설정 남은 3단계",(tag(a,"home_setup_count") as TextView).text.toString())
        assertEquals("지난 통화 정리 중, 녹음 59개 남음",(tag(a,"home_first_pass_banner_text") as TextView).text.toString())
        assertEquals(View.GONE,tag(a,"home_setup_body").visibility);assertEquals(View.GONE,tag(a,"home_first_pass_body").visibility)
        val dm=a.resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels
        val root=a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
        val row=tag(a,"home_task_t1");assertNotNull(row)
        val at=IntArray(2);row.getLocationInWindow(at)
        assertTrue("first check row at y=${at[1]} of $h",at[1]+row.height/2<h)
        // A tap opens the folded card, a second one folds it again.
        tag(a,"home_first_pass_banner").performClick();idle()
        assertEquals(View.VISIBLE,tag(a,"home_first_pass_body").visibility)
        assertTrue(texts(tag(a,"home_first_pass")).any { it.contains("글자로 바꿀 녹음 59개") })
        tag(a,"home_first_pass_banner").performClick();idle()
        assertEquals(View.GONE,tag(a,"home_first_pass_body").visibility)
    }

    @Test fun recordingGapBannerOpensSettingsAtTheRecordingRow() {
        // Empty from r12-1's placeholder: nothing drawn.
        assertNull(HomeScreen.gapBanner(app))
        val none=home(required);assertNull(tag(none,"home_gap"))
        HomeScreen.gapHook={ RecordingGap.Banner("통화 녹음이 꺼져 있는 것 같아요") }
        assertEquals("통화 녹음이 꺼져 있는 것 같아요",HomeScreen.gapBanner(app)?.text)
        val a=home(required.copy(gap="통화 녹음이 꺼져 있는 것 같아요"))
        assertEquals("통화 녹음이 꺼져 있는 것 같아요 ›",(tag(a,"home_gap_text") as TextView).text.toString())
        tag(a,"home_gap").performClick()
        settle { a.screen==Screen.SETTINGS && tag(a,"status_import")!=null }
    }

    // ---- 3. the update state ----
    @Test fun homeUpdateBannerShowsTheStateAndNothingWhenEmpty() {
        // r12-2's placeholder state ("none") and no version known: no card.
        assertEquals("none",HomeScreen.updateState(app).phase)
        assertNull(tag(home(required),"home_update"))
        val coming=home(required.copy(update=CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 42% (3.1MB / 7.0MB)",42)))
        assertEquals("새 버전 v3.1 받는 중 42% (3.1MB / 7.0MB)",(tag(coming,"home_update_state") as TextView).text.toString())
        assertEquals(42,(tag(coming,"update_progress") as ProgressBar).progress)
        assertNull("nothing to press while it comes",tag(coming,"home_update_button"))
        // Every second the words and the bar follow r12-2's state.
        HomeScreen.updateHook={ CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 57% (4.0MB / 7.0MB)",57) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals("새 버전 v3.1 받는 중 57% (4.0MB / 7.0MB)",(tag(coming,"home_update_state") as TextView).text.toString())
        assertEquals(57,(tag(coming,"update_progress") as ProgressBar).progress)
        HomeScreen.updateHook=null
        val ready=home(required.copy(update=CallUpdate.UpdateState("ready","설치 준비됨",100,canInstall=true)))
        assertEquals("지금 업데이트",(tag(ready,"home_update_button") as TextView).text.toString())
        var checks=0;HomeScreen.checkHook={ _,done -> checks++;done(CallUpdate.UpdateState()) }
        val failed=home(required.copy(update=CallUpdate.UpdateState("failed","인터넷이 끊겨 받지 못했어요.")))
        assertEquals("다시 확인하기",(tag(failed,"home_update_button") as TextView).text.toString())
        val before=checks
        tag(failed,"home_update_button").performClick();idle()
        assertEquals(before+1,checks)
        assertTrue(texts(failed.window.decorView).none { it.contains("·") || Regex("[A-Z]{4,}").containsMatchIn(it) })
    }

    @Test fun settingsVersionRowShowsTheStateWithCheckNow() {
        var checks=0;var answer:((CallUpdate.UpdateState)->Unit)?=null
        HomeScreen.checkHook={ _,done -> checks++;answer=done }
        // r12-2's placeholder ("none"): the version row keeps its last check and [지금 확인].
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"update_check")!=null }
        assertEquals("지금 확인 ›",(tag(a,"update_check") as TextView).text.toString())
        val before=checks
        tag(a,"update_check").performClick();idle()
        assertEquals(before+1,checks)
        assertEquals("확인하는 중이에요",(tag(a,"update_check") as TextView).text.toString())
        // The answer comes: shown in the same place.
        HomeScreen.updateHook={ CallUpdate.UpdateState("ready","새 버전 v3.1 설치 준비됨",100,canInstall=true) }
        answer!!(HomeScreen.updateState(app));idle()
        assertEquals("새 버전 v3.1 설치 준비됨",(tag(a,"update_state") as TextView).text.toString())
        assertEquals("지금 업데이트",(tag(a,"update_install") as TextView).text.toString())
        // While it comes the percent follows every second.
        HomeScreen.updateHook={ CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 10% (0.7MB / 7.0MB)",10) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SettingsScreen.TICK_MS))
        assertEquals(10,(tag(a,"update_progress") as ProgressBar).progress)
        HomeScreen.updateHook={ CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 63% (4.4MB / 7.0MB)",63) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SettingsScreen.TICK_MS))
        assertEquals("새 버전 v3.1 받는 중 63% (4.4MB / 7.0MB)",(tag(a,"update_state") as TextView).text.toString())
        assertEquals(63,(tag(a,"update_progress") as ProgressBar).progress)
    }

    @Test fun settingsModelLineHasNoWifiWait() {
        SecureSettings(app).update { it.put(PipelineSettingsKeys.MODEL_STATE,"") }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"model_state")!=null }
        assertEquals("음성 인식 모델을 받기 시작해요 (574MB)",(tag(a,"model_state") as TextView).text.toString())
        // The model's own part of the page never waits for Wi-Fi (the status light's words are r12-1's StatusLights).
        val group=tag(a,"model_state").parent as ViewGroup
        val lights=views(tag(a,"settings_status")).toSet()
        assertTrue(views(group).filter { it !in lights }.filterIsInstance<TextView>().none { it.text.contains("Wi-Fi") })
        tag(a,"model_get").performClick();assertEquals(1,modelStarts)
    }

    @Test fun appStartLooksForANewVersion() {
        var checks=0;HomeScreen.checkHook={ _,_ -> checks++ }
        launchMain(Screen.HOME);idle()
        assertEquals(1,checks)
    }

    // ---- 4. a result that could not be applied ----
    @Test fun aResultNotAppliedSaysSoAndSendsTheCallAgain() {
        PreviewSeed.ensure(app)
        val db=IeumApplication.store(app)
        val call=db.day(CallUi.today()).first { it.kind=="통화" }
        db.markSent(call.recordId,System.currentTimeMillis());db.setReceived(call.recordId,System.currentTimeMillis())
        db.setState(call.recordId,CallRecordStore.FAILED,PipelineSettingsKeys.RESULT_NOT_APPLIED)
        val a=launchMain(Screen.CALLS,call.dayMs,call.recordId)
        settle { tag(a,"call_state_text")!=null }
        assertEquals("정리 결과를 적용하지 못했어요",(tag(a,"call_state_text") as TextView).text.toString())
        assertEquals("다시 정리하기",(tag(a,"call_retry") as TextView).text.toString())
        tag(a,"call_retry").performClick()
        settle { db.record(call.recordId)?.state==CallRecordStore.TRANSCRIBED }
        assertNull("the received mark goes, so the call is sent again",db.receivedMs(call.recordId))
    }
}
