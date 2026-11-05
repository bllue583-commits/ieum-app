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
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** v3.1 r12-6 (user 11/5 22:19, "홈 탭엔 요약만"): home shows only the summary; first setup (required and optional steps, r12-5),
 * 지난 통화 정리, the voice model and missing recordings are in settings' first group "지금 할 일과 진행 상황", and home says
 * "설정에서 확인할 일이 있어요 ›" in one line only when the person has something to do there (a tap opens that row). Also the
 * update state in settings with [지금 확인] and [지금 업데이트] (r12-2's functions through test hooks), and [다시 정리하기]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FirstSetupHomeTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(v:View)=views(v).filter { isShown(it) }.filterIsInstance<TextView>().map { it.text.toString() }
    private fun isShown(v:View):Boolean { var p:View?=v;while(p!=null) { if(p.visibility!=View.VISIBLE) return false;p=p.parent as? View };return true }
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun text(a:android.app.Activity,t:String)=(tag(a,t) as TextView).text.toString()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle(check:()->Boolean) { repeat(400) { idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private var modelStarts=0
    @Before fun hooks() {
        HomeScreen.modelStart={ modelStarts++ }
        // The app-start check would draw home again after the test's snapshot (a second read); it answers nothing here.
        HomeScreen.checkHook={ _,_ -> }
    }
    @After fun reset() {
        HomeScreen.modelStart={ PipelineModelDownloadWork.enqueue(it) };HomeScreen.gapHook=null;HomeScreen.updateHook=null
        HomeScreen.setupStepHook=null;HomeScreen.checkHook=null;CallRetry.hook=null;SettingsScreen.firstPassHook=null
    }
    /** Home with [s] drawn after the screen's own read landed (else that read draws over it later, a race on slower runners). */
    private fun home(s:HomeScreen.Snapshot):MainActivity {
        val a=launchMain(Screen.HOME);settle { tag(a,"home_today")!=null || tag(a,"retry_card")!=null };idle()
        a.home.render(s);idle();return a
    }
    private fun settings():MainActivity { val a=launchMain(Screen.SETTINGS);settle { tag(a,SettingsScreen.GROUP_NOW)!=null && tag(a,"app_version")!=null };return a }
    /** Every required step done: the optional ones are left. */
    private val required=HomeScreen.Snapshot(folder=true,consent=true,model="READY",calls=0,working=0,newTasks=0,due=0,overdue=0,lastAnalyzedMs=0)
    /** The phone with every required step done (folder, consent, call log, call state, notifications, battery). */
    private fun requiredDone() {
        SecureSettings(app).update { it.put("treeUri","content://host/tree/call").put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.MODEL_STATE,PipelineModelState.READY) }
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALL_LOG,android.Manifest.permission.READ_PHONE_STATE,android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName,true)
    }
    /** Tags of the status cards home used to draw (r12-5); none of them is on home any more. */
    private val oldHomeCards=listOf("home_setup","home_setup_banner","home_first_pass","home_model","home_update","home_gap")

    // ---- 1. home: a summary, and one line only when there is something to do ----
    @Test fun nothingToDoMeansNoStatusCardOnHome() {
        val a=home(required.copy(sms=false,overlay=false,autoInstallStep=true,
            firstPass=HomeScreen.FirstPassView(59,9.0,4,2,audioMinutesLeft=190)))
        assertNull("only optional steps left: no line",tag(a,"home_attention"))
        oldHomeCards.forEach { assertNull(it,tag(a,it)) }
        val all=texts(a.window.decorView).joinToString("\n")
        listOf("처음 설정","지난 통화 정리","음성 인식 모델","새 버전").forEach { assertFalse(it,all.contains(it)) }
        // The summary itself: three numbers, the check list, today's memos.
        listOf("home_today","home_tasks_head","home_tasks","home_story").forEach { assertNotNull(it,tag(a,it)) }
    }

    @Test fun onlyMovingAlongShowsNoLine() {
        // A new version coming and the voice model coming: nothing for the person to do.
        val a=home(required.copy(update=CallUpdate.UpdateState(CallUpdate.P_DOWNLOADING,"새 버전을 받는 중이에요. 42%",42),
            model=PipelineModelState.DOWNLOADING,modelBytes=241_100_000L))
        assertNull(tag(a,"home_attention"));oldHomeCards.forEach { assertNull(it,tag(a,it)) }
        assertNull(HomeScreen.attention(required.copy(update=CallUpdate.UpdateState(CallUpdate.P_CHECKING,"확인 중"))))
        assertNull(HomeScreen.attention(required.copy(update=CallUpdate.UpdateState(CallUpdate.P_WAITING_CONFIRM,CallUpdate.INSTALLING_WORDS,100,false))))
        assertNull(HomeScreen.attention(required.copy(update=CallUpdate.UpdateState(CallUpdate.P_OFFLINE,CallUpdate.OFFLINE_WORDS))))
    }

    @Test fun whatNeedsThePersonGivesOneLineThatOpensItsRow() {
        // The required steps first, then a failed model, missing recordings, an update waiting for the person.
        val all=required.copy(folder=false,model=PipelineModelState.FAILED,gap="통화 녹음이 꺼져 있는 것 같아요",
            update=CallUpdate.UpdateState(CallUpdate.P_READY,"새 버전이 준비됐어요",100,canInstall=true))
        assertEquals(HomeScreen.Attention(SettingsScreen.GROUP_NOW,SettingsScreen.SETUP_TAG),HomeScreen.attention(all))
        assertEquals(HomeScreen.Attention(SettingsScreen.GROUP_NOW,"model_state"),HomeScreen.attention(all.copy(folder=true)))
        assertEquals(HomeScreen.Attention(StatusLights.GROUP_STATUS,"status_import"),HomeScreen.attention(all.copy(folder=true,model="READY")))
        assertEquals(HomeScreen.Attention(StatusLights.GROUP_STATUS,"app_version"),HomeScreen.attention(all.copy(folder=true,model="READY",gap=null)))
        assertNotNull(HomeScreen.attention(required.copy(update=CallUpdate.UpdateState(CallUpdate.P_WAITING_CONFIRM,CallUpdate.CONFIRM_WORDS,100,true))))
        assertNotNull(HomeScreen.attention(required.copy(update=CallUpdate.UpdateState(CallUpdate.P_FAILED,"받지 못했어요"))))
        // Drawn: one line, above the numbers, in reading size, whatever is waiting.
        val a=home(all)
        val lines=views(a.window.decorView).filter { it.tag=="home_attention" }
        assertEquals(1,lines.size)
        assertEquals("설정에서 확인할 일이 있어요 ›",text(a,"home_attention_text"))
        assertTrue((tag(a,"home_attention_text") as TextView).textSize/a.resources.displayMetrics.scaledDensity>=18f)
        assertTrue(tag(a,"home_attention").minimumHeight>=(56*a.resources.displayMetrics.density).toInt())
        val page=tag(a,"home_today").parent as ViewGroup
        assertTrue(page.indexOfChild(tag(a,"home_attention"))<page.indexOfChild(tag(a,"home_today")))
        oldHomeCards.forEach { assertNull(it,tag(a,it)) }
    }

    @Test fun aRequiredStepLeftOpensFirstSetupInSettings() {
        val a=home(required.copy(notify=false))
        tag(a,"home_attention").performClick()
        settle { a.screen==Screen.SETTINGS && tag(a,SettingsScreen.SETUP_TAG)!=null }
        // The host has no folder: that is the next step there.
        assertEquals("다음 할 일: 녹음 폴더 연결",text(a,"setup_next_label"))
    }

    @Test fun missingRecordingsOpenTheRecordingLightInSettings() {
        assertNull(HomeScreen.gapBanner(app))
        HomeScreen.gapHook={ RecordingGap.Banner("통화 녹음이 꺼져 있는 것 같아요") }
        val a=home(required.copy(gap="통화 녹음이 꺼져 있는 것 같아요"))
        assertNull(tag(a,"home_gap"))
        tag(a,"home_attention").performClick()
        settle { a.screen==Screen.SETTINGS && tag(a,"status_import")!=null }
        // In settings' first group, one line that goes to the light (the words are not said twice).
        assertNotNull(tag(a,"now_gap"))
        assertTrue(text(a,"now_gap").endsWith("›"))
    }

    @Test fun anUpdateWaitingOpensTheVersionRowInSettings() {
        HomeScreen.updateHook={ CallUpdate.UpdateState(CallUpdate.P_READY,"새 버전 v3.1 설치 준비됨",100,canInstall=true) }
        val a=home(required.copy(update=HomeScreen.updateState(app)))
        tag(a,"home_attention").performClick()
        settle { a.screen==Screen.SETTINGS && tag(a,"update_install")!=null }
        assertEquals("지금 업데이트",text(a,"update_install"))
        assertNotNull(tag(a,"app_version"))
    }

    private fun analyzed(id:String,at:Long,who:String,summary:String,recording:String="rec-$id")=CallRecord(id,recording,at,90_000,null,null,"",
        CallRecordStore.ANALYZED,summary,null,emptyList(),null,at,at,linkedPerson=who)
    private val now get()=System.currentTimeMillis()
    private fun story(day:java.time.LocalDate,text:String,calls:Int)=org.json.JSONObject().put("day",day.toString()).put("text",text).put("calls",calls).put("atMs",now).toString()

    @Test fun storyComesFirstAndItAndTheFirstCheckRowAreOnTheFirstScreen() {
        // DESIGN_V4 2-1: the line, "오늘 통화 이야기", the check list, today's memos, then the numbers.
        val shown=TodayStory.shown(story(CallUi.today(),"아침에 철강 김 과장과 철근 단가를 다시 보기로 했어요. 오후에는 타설 일정을 금요일로 옮겼어요.",3),CallUi.today(),emptyList())!!
        assertEquals(3,shown.calls)
        val a=home(required.copy(folder=false,calls=12,newTasks=3,gap="최근 하루 통화 12건 중 녹음 0건",story=shown,
            memos=listOf(analyzed("m1",now,"김민수","철근 단가를 다시 견적 받기로 했어요.")),
            tasks=(1..3).map { OpenItem("t$it","견적서 보내기 $it","","open",1,"",null,createdMs=now) },
            byCompany=listOf("업체 없음" to 3)))
        val page=tag(a,"home_today").parent as ViewGroup
        val order=listOf("home_attention","home_story","home_tasks","home_memos","home_today").map { page.indexOfChild(tag(a,it)) }
        assertFalse(order.toString(),order.contains(-1));assertEquals(order.sorted(),order)
        assertEquals("오늘 통화 3건",text(a,"home_story_title"))
        assertEquals("아침에 철강 김 과장과 철근 단가를 다시 보기로 했어요. 오후에는 타설 일정을 금요일로 옮겼어요.",text(a,"home_story_text"))
        val body=tag(a,"home_story_text") as TextView
        assertTrue(body.textSize/a.resources.displayMetrics.scaledDensity>=CallUi.BODY);assertEquals(4,body.maxLines)
        val dm=a.resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels
        val root=a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
        for(t in listOf("home_story","home_task_t1")) {
            val v=tag(a,t);val at=IntArray(2);v.getLocationInWindow(at)
            assertTrue("$t at y=${at[1]}+${v.height} of $h",at[1]+v.height<h)
        }
        // A tap opens today's calls.
        tag(a,"home_story").performClick();idle()
        assertEquals(Screen.CALLS,a.screen)
    }

    @Test fun yesterdaysStoryGivesThePhonesOwnWordsAndNoCallGivesOneLine() {
        val today=CallUi.today()
        val calls=listOf(analyzed("a",now-3_000,"김민수","철근 단가를 다시 견적 받기로 했어요. 금요일까지예요."),
            analyzed("b",now-2_000,"이영희","타설 일정을 금요일로 옮겼어요."),analyzed("c",now-1_000,"박정우","전기 배선 검측을 마쳤어요."),
            analyzed("d",now-4_000,"최수진","오래된 통화예요."),analyzed("e",now,"문자 상대","문자는 빠져요.",recording="sms:1"))
        val old=TodayStory.shown(story(today.minusDays(1),"어제 이야기예요.",9),today,calls)!!
        assertEquals("박정우: 전기 배선 검측을 마쳤어요. 이영희: 타설 일정을 금요일로 옮겼어요. 김민수: 철근 단가를 다시 견적 받기로 했어요.",old.text)
        assertEquals(4,old.calls)
        assertEquals(old,TodayStory.shown(null,today,calls))
        // The story's count wins when it is bigger, and a middle dot from the task becomes a comma.
        val s=TodayStory.shown(story(today,"철근·타설 일정을 정했어요…",7),today,calls)!!
        assertEquals(7,s.calls);assertEquals("철근, 타설 일정을 정했어요",s.text)
        // No call summarized today: one line instead of the card.
        assertNull(TodayStory.shown(story(today.minusDays(1),"어제 이야기예요.",9),today,emptyList()))
        val a=home(required)
        assertEquals("오늘은 아직 정리된 통화가 없어요",text(a,"home_story_none"))
        assertNull(tag(a,"home_story_title"))
    }

    // ---- 2. settings: 지금 할 일과 진행 상황 ----
    @Test fun settingsFirstGroupHasSetupWithRequiredStepsFirst() {
        ShadowSettings.setCanDrawOverlays(false)
        HomeScreen.setupStepHook={ true }
        val a=settings()
        // The first group on the page, right under the title.
        val groups=views(a.window.decorView).mapNotNull { (it.tag as? String)?.takeIf { t -> t.startsWith("group_") } }
        // v3.3: an amber light puts "지금 상태" above it (DESIGN_V4 3.3-4); then this group.
        assertEquals(SettingsScreen.GROUP_NOW,groups.first { it!="group_"+SettingsScreen.STATUS_NAME })
        assertEquals("다음 할 일: 녹음 폴더 연결",text(a,"setup_next_label"))
        val all=texts(tag(a,SettingsScreen.SETUP_TAG))
        assertTrue(all.toString(),all.contains("처음 설정 남은 9단계"))
        val order=listOf("정리 동의","통화기록 읽기 허용","통화가 끝난 걸 바로 알아차리기","알림 허용","배터리 제한 풀기","문자 읽기 허용 (선택)",
            "통화 화면 위에 이음 카드 보기 (선택)","새 버전을 저절로 설치하기 (선택)")
        assertEquals(order,all.filter { it in order })
        // The reason sits right above the button, in reading size; a required step has no [나중에 할게요].
        val reason=tag(a,"setup_detail") as TextView
        assertTrue(reason.textSize/a.resources.displayMetrics.scaledDensity>=CallUi.BODY)
        val box=reason.parent as ViewGroup;assertEquals(box.indexOfChild(reason)+1,box.indexOfChild(tag(a,"setup_next")))
        assertTrue(reason.text.contains("Recordings"))
        assertNull(tag(a,"setup_later"))
    }

    @Test fun optionalStepsCanBePutOffAndARefusalOpensTheNext() {
        requiredDone();ShadowSettings.setCanDrawOverlays(false);HomeScreen.setupStepHook={ true }
        val a=settings()
        assertEquals("다음 할 일: 문자 읽기 허용",text(a,"setup_next_label"))
        assertNotNull(tag(a,"setup_optional"));assertEquals("나중에 할게요",text(a,"setup_later"))
        // The texts question answered "no" (asked once, still refused): done, the overlay step opens.
        ReadingPermission.markAsked(app,android.Manifest.permission.READ_SMS)
        val b=settings()
        assertEquals("다음 할 일: 통화 화면 위에 이음 카드 보기",text(b,"setup_next_label"))
        assertEquals("카드 보기 허용",text(b,"setup_next"))
        tag(b,"setup_next").performClick()
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,shadowOf(b).nextStartedActivity?.action)
        // Back from the list with the switch still off: bold words that say what to look for, and [다시 열기].
        val c=settings()
        assertEquals("목록에서 '이음'을 찾아 스위치를 켜 주세요.",text(c,"setup_detail"))
        assertSame(CallUi.font(c,CallUi.Weight.SEMIBOLD),(tag(c,"setup_detail") as TextView).typeface)
        assertEquals("다시 열기",text(c,"setup_next"))
        // [나중에 할게요]: the install step is next; put that off too and first setup is gone, "지금 할 일이 없어요" instead.
        tag(c,"setup_later").performClick()
        settle { (tag(c,"setup_next_label") as? TextView)?.text?.toString()=="다음 할 일: 새 버전을 저절로 설치하기" }
        assertTrue(SetupSkip.skipped(app,"overlay"))
        tag(c,"setup_later").performClick()
        settle { tag(c,SettingsScreen.SETUP_TAG)==null && tag(c,"now_none")!=null }
    }

    @Test fun theConsentStepStartsTheModelAndItsLineComesWithAPercentAndABar() {
        SecureSettings(app).update { it.put("treeUri","content://host/tree/call").put(PipelineSettingsKeys.MODEL_STATE,"") }
        val cm=app.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_MOBILE,0,true,android.net.NetworkInfo.State.CONNECTED))
        val a=settings()
        assertEquals("다음 할 일: 정리 동의",text(a,"setup_next_label"))
        tag(a,"setup_next").performClick()
        settle { SecureSettings(app).snapshot().optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false) && modelStarts>0 }
        // The model is coming (on mobile data too): its line in the first group with the percent and a bar, never "Wi-Fi".
        SecureSettings(app).update { it.put(PipelineSettingsKeys.MODEL_STATE,PipelineModelState.DOWNLOADING) }
        val part=PipelineModelManager(app).partFile;part.parentFile?.mkdirs()
        java.io.RandomAccessFile(part,"rw").use { it.setLength(57_404_120L) }
        try {
            val b=settings()
            settle { tag(b,"model_bar")!=null }
            assertEquals("음성 인식 모델 받는 중 10% (57MB / 574MB)",text(b,"model_state"))
            assertEquals(10,(tag(b,"model_bar") as ProgressBar).progress)
            assertTrue(views(tag(b,SettingsScreen.GROUP_NOW)).contains(tag(b,"model_state")))
            assertEquals("다음 할 일: 통화기록 읽기 허용",text(b,"setup_next_label"))
            assertTrue(texts(b.window.decorView).none { it.contains("Wi-Fi") })
            // It follows every second.
            java.io.RandomAccessFile(part,"rw").use { it.setLength(287_030_000L) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SettingsScreen.TICK_MS))
            assertEquals(50,(tag(b,"model_bar") as ProgressBar).progress)
        } finally { part.delete() }
    }

    @Test fun lastCallsSortingIsInSettingsCountedInRecordings() {
        SettingsScreen.firstPassHook={ HomeScreen.FirstPassView(59,9.0,4,1028,audioMinutesLeft=190,recordings=120) }
        val a=settings()
        assertEquals("지난 통화 정리 중, 녹음 120개 중 59개 남음",text(a,"first_pass_state"))
        val group=texts(tag(a,SettingsScreen.GROUP_NOW)).joinToString("\n")
        assertFalse("no call-log count on screen",group.contains("1028") || group.contains("통화기록 기준"))
        assertTrue(group,group.contains("끝나기까지 약 9시간"))
        // Resting on battery: amber, why, and what is left in big numbers.
        SettingsScreen.firstPassHook={ HomeScreen.FirstPassView(37,9.0,4,2,audioMinutesLeft=190,lowBattery=true,recordings=80) }
        val b=settings()
        assertEquals("지난 통화 정리, 충전을 기다리는 중",text(b,"first_pass_state"))
        assertEquals("잠시 쉬고 있어요",text(b,"first_pass_rest"))
        assertTrue((tag(b,"first_pass_rest") as TextView).textSize/b.resources.displayMetrics.scaledDensity>=CallUi.HEADING)
        assertEquals("충전기에 꽂으면 이어서 정리해요.",text(b,"first_pass_rest_why"))
        assertNotNull(tag(b,"first_pass_left"))
        assertTrue(views(tag(b,SettingsScreen.GROUP_NOW)).filterIsInstance<TextView>().all { it.textSize/it.resources.displayMetrics.scaledDensity>=16f })
        assertEquals("지난 통화 정리, 결과를 기다리는 중",HomeScreen.firstPassWords(HomeScreen.FirstPassView(0,null,6,0,stalled=true)))
        assertEquals("지난 통화 정리 중, 녹음 3개 남음",HomeScreen.firstPassWords(HomeScreen.FirstPassView(3,null,0,0)))
    }

    @Test fun nothingToDoSaysSoInSettings() {
        requiredDone();ShadowSettings.setCanDrawOverlays(true);HomeScreen.setupStepHook={ false }
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_SMS)
        val a=settings()
        assertNull(tag(a,SettingsScreen.SETUP_TAG));assertNull(tag(a,"first_pass_state"));assertNull(tag(a,"now_gap"))
        assertEquals("지금 할 일이 없어요. 모두 잘 되고 있어요 ✓",text(a,"now_none"))
        // The ready model is a plain line in 상태와 도움.
        assertFalse(views(tag(a,SettingsScreen.GROUP_NOW)).contains(tag(a,"model_state")))
        assertEquals("음성 인식 모델 준비됨 ✓",text(a,"model_state"))
    }

    @Test fun modelLineShowsPercentAndSizes() {
        assertEquals("음성 인식 모델 받는 중 42% (241MB / 574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,241_100_000L))
        assertEquals("음성 인식 모델 받는 중 0% (0MB / 574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,0))
        assertEquals("인터넷에 연결되면 음성 인식 모델을 이어서 받아요 (574MB)",HomeScreen.modelLine(PipelineModelState.DOWNLOADING,10,offline=true))
        assertTrue(HomeScreen.modelLine(PipelineModelState.FAILED,-1).contains("다시"))
    }

    // ---- 3. the update state in settings ----
    @Test fun settingsVersionRowShowsTheStateWithCheckNow() {
        var checks=0;var answer:((CallUpdate.UpdateState)->Unit)?=null
        HomeScreen.checkHook={ _,done -> checks++;answer=done }
        // r12-2's placeholder ("none"): the version row keeps its last check and [지금 확인].
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"update_check")!=null }
        assertEquals("지금 확인 ›",text(a,"update_check"))
        val before=checks
        tag(a,"update_check").performClick();idle()
        assertEquals(before+1,checks)
        assertEquals("확인하는 중이에요",text(a,"update_check"))
        // The answer comes: shown in the same place.
        HomeScreen.updateHook={ CallUpdate.UpdateState("ready","새 버전 v3.1 설치 준비됨",100,canInstall=true) }
        answer!!(HomeScreen.updateState(app));idle()
        assertEquals("새 버전 v3.1 설치 준비됨",text(a,"update_state"))
        assertEquals("지금 업데이트",text(a,"update_install"))
        // While it comes the percent follows every second.
        HomeScreen.updateHook={ CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 10% (0.7MB / 7.0MB)",10) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SettingsScreen.TICK_MS))
        assertEquals(10,(tag(a,"update_progress") as ProgressBar).progress)
        HomeScreen.updateHook={ CallUpdate.UpdateState("downloading","새 버전 v3.1 받는 중 63% (4.4MB / 7.0MB)",63) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SettingsScreen.TICK_MS))
        assertEquals("새 버전 v3.1 받는 중 63% (4.4MB / 7.0MB)",text(a,"update_state"))
        assertEquals(63,(tag(a,"update_progress") as ProgressBar).progress)
    }

    @Test fun settingsModelLineHasNoWifiWait() {
        SecureSettings(app).update { it.put(PipelineSettingsKeys.MODEL_STATE,"") }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"model_state")!=null }
        assertEquals("음성 인식 모델을 받기 시작해요 (574MB)",text(a,"model_state"))
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
        assertEquals("정리 결과를 적용하지 못했어요",text(a,"call_state_text"))
        assertEquals("다시 정리하기",text(a,"call_retry"))
        tag(a,"call_retry").performClick()
        settle { db.record(call.recordId)?.state==CallRecordStore.TRANSCRIBED }
        assertNull("the received mark goes, so the call is sent again",db.receivedMs(call.recordId))
    }
}
