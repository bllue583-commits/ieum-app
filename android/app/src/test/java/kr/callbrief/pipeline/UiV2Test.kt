package kr.callbrief.pipeline

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import androidx.work.NetworkType

/** v2 screens: back to home, settings lights and groups, the check list (fold, finished, own items), call detail, search, notifications, intro, update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UiV2Test:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun tagged(a:android.app.Activity,prefix:String)=views(a.window.decorView).filter { (it.tag as? String)?.startsWith(prefix)==true }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val app get()=RuntimeEnvironment.getApplication()
    @After fun reset() { CallUi.applyTheme(app) }
    private fun seeded() { PreviewSeed.ensure(app) }

    // ---- 1. one Activity (design G1): back, tabs, notifications ----
    private fun back(a:MainActivity) { a.onBackPressedDispatcher.onBackPressed();shadowOf(Looper.getMainLooper()).idle() }
    private fun title(a:MainActivity)=(tag(a,"page_title") as? TextView)?.text?.toString()

    /** 체크 → 업체 → 상세 → 뒤로 → 업체 → 뒤로 → 홈 → 뒤로 → 종료. No other Activity is ever started. */
    @Test fun backClosesTheDetailThenGoesHomeThenLeaves() {
        seeded()
        val a=launchMain(Screen.HOME)
        settle { tag(a,"home_today")!=null }
        tag(a,CallUi.Tab.TASKS.tag).performClick();assertEquals(Screen.TASKS,a.screen)
        settle { tag(a,"task_add")!=null }
        tag(a,CallUi.Tab.COMPANIES.tag).performClick();assertEquals(Screen.COMPANIES,a.screen)
        settle { tagged(a,"company_").any { it is ViewGroup } }
        tagged(a,"company_").first { it is ViewGroup }.performClick()
        settle { texts(a).any { it.text.toString().startsWith("사람 (") } }
        assertNotNull(a.detail);assertNotNull("detail has its back icon",tag(a,"page_back"))
        back(a)
        assertEquals(Screen.COMPANIES,a.screen);assertNull(a.detail)
        settle { title(a)=="업체와 사람" }
        back(a)
        assertEquals(Screen.HOME,a.screen);settle { tag(a,"home_today")!=null }
        assertFalse(a.isFinishing)
        back(a)
        assertTrue("back from home leaves the app",a.isFinishing)
        assertNull(shadowOf(a).nextStartedActivity)
    }

    /** Opened from a notification (the app was closed): back goes home, then out; the evening report carries its day. */
    @Test fun aNotificationOpensItsScreenAndBackGoesHomeThenLeaves() {
        seeded()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val yesterday=CallUi.today().minusDays(1).atTime(12,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
        CallDigestNotifier.post(app,CallDigestSchedule.EVENING,CallDigestText.Message("어제 통화","본문"),yesterday)
        CallDigestNotifier.post(app,CallDigestSchedule.MORNING,CallDigestText.Message("체크할 일","본문"))
        val posted=shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        fun opens(kind:String)=shadowOf(posted.single { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)==(if(kind=="e") "어제 통화" else "체크할 일") }.contentIntent).savedIntent
        val evening=opens("e");val morning=opens("m")
        assertEquals(MainActivity::class.java.name,evening.component!!.className);assertEquals("CALLS",evening.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertEquals(yesterday,evening.getLongExtra(MainActivity.EXTRA_DAY_MS,0));assertEquals("TASKS",morning.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertEquals(0,morning.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // The evening one, the next morning: yesterday's calls.
        val a=Robolectric.buildActivity(MainActivity::class.java,evening).setup().get()
        assertEquals(Screen.CALLS,a.screen)
        settle { title(a)=="어제의 통화" }
        back(a);assertEquals(Screen.HOME,a.screen);settle { tag(a,"home_today")!=null }
        back(a);assertTrue(a.isFinishing)
        // The morning one while the app is open on home: the check list comes to the same window (onNewIntent).
        val bc=Robolectric.buildActivity(MainActivity::class.java,MainActivity.intent(app,Screen.HOME)).setup();val b=bc.get()
        settle { tag(b,"home_today")!=null }
        bc.newIntent(morning);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Screen.TASKS,b.screen);settle { tag(b,"task_add")!=null }
        back(b);assertEquals(Screen.HOME,b.screen);back(b);assertTrue(b.isFinishing)
        // A notification with a call: that call's page; back to the list, home, out.
        val call=CallRecordStore(app).use { s -> s.day(CallUi.today()).first { it.analysis!=null } }
        val c=launchMain(Screen.CALLS,call.dayMs,call.recordId)
        settle { texts(c).any { it.text.toString()=="요약" } };assertEquals(call.recordId,c.detail)
        back(c);assertNull(c.detail);assertEquals(Screen.CALLS,c.screen)
        back(c);assertEquals(Screen.HOME,c.screen);back(c);assertTrue(c.isFinishing)
    }

    /** The tab of the screen in front, tapped again: the detail closes, the search and the day are cleared, the list is at the top. */
    @Test fun theCurrentTabTappedAgainGoesBackToTheList() {
        seeded()
        val call=CallRecordStore(app).use { s -> s.day(CallUi.today()).first { it.analysis!=null } }
        val a=launchMain(Screen.CALLS,call.dayMs,call.recordId)
        settle { texts(a).any { it.text.toString()=="요약" } }
        tag(a,CallUi.Tab.CALLS.tag).performClick()
        settle { tag(a,"calls_search")!=null && tag(a,"calls_list")!=null }
        assertNull(a.detail);assertEquals("오늘의 통화",title(a))
        // An earlier day with something typed: the tab brings today back, with an empty search line.
        (tag(a,"calls_search") as EditText).setText("철강")
        tag(a,CallUi.Tab.CALLS.tag).performClick()
        settle { tag(a,"calls_list")!=null };assertEquals("",(tag(a,"calls_search") as EditText).text.toString())
        // Companies: the detail closes on its tab too.
        tag(a,CallUi.Tab.COMPANIES.tag).performClick()
        settle { tagged(a,"company_").any { it is ViewGroup } }
        tagged(a,"company_").first { it is ViewGroup }.performClick()
        settle { a.detail!=null && texts(a).any { it.text.toString().startsWith("사람 (") } }
        tag(a,CallUi.Tab.COMPANIES.tag).performClick()
        settle { title(a)=="업체와 사람" };assertNull(a.detail)
        // A quick double tap on a row opens once (one entry for back).
        tagged(a,"company_").first { it is ViewGroup }.let { it.performClick();it.performClick() }
        settle { a.detail!=null && texts(a).any { it.text.toString().startsWith("사람 (") } }
        back(a);settle { title(a)=="업체와 사람" };assertEquals(Screen.COMPANIES,a.screen)
    }

    /** Closing a detail page puts the list back where it was, also for a long list drawn in parts. */
    @Test fun closingADetailPutsTheListBackWhereItWas() {
        seeded()
        val day=CallUi.today()
        CallRecordStore(app).use { s -> s.reconcileCallLog((1..120).map { i ->
            FirstPass.LogEntry(700_000L+i,"010%08d".format(i),null,day.atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()+i*60_000L,0,android.provider.CallLog.Calls.MISSED_TYPE) }) }
        val a=launchMain(Screen.CALLS)
        settle { tagged(a,"call_").any { it is ViewGroup } && (tag(a,"calls_list") as? ViewGroup)?.childCount ?: 0 > 200 }
        val scroll=tag(a,"page_scroll") as android.widget.ScrollView
        fun lay() { val d=a.window.decorView;d.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));d.layout(0,0,1080,1920) }
        lay();scroll.scrollTo(0,900);lay();val y=scroll.scrollY;assertTrue("page scrolls",y>0)
        tagged(a,"call_").first { it is ViewGroup }.performClick()
        settle { a.detail!=null };lay()
        back(a);lay();shadowOf(Looper.getMainLooper()).idle()
        settle { lay();scroll.scrollY==y }
        assertNull(a.detail)
    }

    @Test fun mainActivityIsTheOnlyScreenAndKeepsItsStateAcrossARestart() {
        val pm=app.packageManager
        val info=pm.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_ACTIVITIES)
        // v3.3 (DESIGN_V4 3.2-4): besides the one screen Activity only the hidden golden measurement tool, not exported.
        val activities=info.activities!!
        assertEquals(listOf(MainActivity::class.java.name),activities.filter { it.name!=GoldenMeasureActivity::class.java.name }.map { it.name })
        activities.firstOrNull { it.name==GoldenMeasureActivity::class.java.name }?.let { assertFalse(it.exported) }
        activities.forEach { assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,it.screenOrientation) }
        seeded()
        val c=Robolectric.buildActivity(MainActivity::class.java,MainActivity.intent(app,Screen.COMPANIES)).setup()
        val a=c.get();settle { tagged(a,"company_").any { it is ViewGroup } }
        (tag(a,"companies_search") as EditText).setText("철강")
        val saved=android.os.Bundle();c.saveInstanceState(saved)
        val b=Robolectric.buildActivity(MainActivity::class.java).setup(saved).get()
        assertEquals(Screen.COMPANIES,b.screen)
        settle { (tag(b,"companies_search") as? EditText)?.text?.toString()=="철강" }
    }

    // ---- 2. settings ----
    @Test fun statusLightsJudgeEachLine() {
        val ok=StatusLights.Input(now=100L*3_600_000,folderConnected=true,modelState="READY",consent=true)
        assertTrue(StatusLights.lights(ok).all { it.ok && it.words=="정상" })
        assertEquals(listOf("녹음 가져오기","글자로 바꾸기","통화 정리","알림","업데이트","통화 카드"),StatusLights.lights(ok).map { it.label })
        fun bad(i:StatusLights.Input,tag:String)=StatusLights.lights(i).single { !it.ok }.also { assertEquals(tag,it.tag) }.words
        assertTrue(bad(ok.copy(folderConnected=false),"status_import").contains("폴더"))
        assertTrue(bad(ok.copy(modelState="FAILED"),"status_transcribe").contains("모델"))
        assertTrue(bad(ok.copy(transcribeBacklog=3,lastTranscribedMs=ok.now-7*3_600_000L),"status_transcribe").contains("충전"))
        assertTrue(StatusLights.lights(ok.copy(transcribeBacklog=3,lastTranscribedMs=ok.now-3_600_000L)).all { it.ok })
        assertTrue(bad(ok.copy(consent=false),"status_analyze").contains("동의"))
        assertTrue(bad(ok.copy(waiting=2,oldestWaitMs=4*3_600_000L),"status_analyze").contains("4시간"))
        assertTrue(bad(ok.copy(failed=1),"status_analyze").contains("1건"))
        assertTrue(bad(ok.copy(notifyAllowed=false),"status_notify").contains("알림"))
        // The stored code stays for the heartbeat; the light reads only Korean, and a cancelled install window is no problem.
        assertEquals("저장 공간이 부족해 설치하지 못했어요. 사진이나 쓰지 않는 앱을 조금 지운 뒤 홈에서 지금 업데이트를 다시 눌러 주세요.",
            bad(ok.copy(updateError="STATUS_FAILURE_STORAGE (6)"),"status_update"))
        assertTrue(StatusLights.lights(ok.copy(updateError="STATUS_FAILURE_ABORTED (3): User rejected permissions")).all { it.ok })
        assertTrue(bad(ok.copy(updateReady=true),"status_update").contains("새 버전"))
        StatusLights.lights(ok.copy(folderConnected=false,consent=false)).forEach { assertFalse(it.words.contains("·")) }
    }

    /** Design 3.6 (review #9, #10, #11): the lights follow the permission, the discovery age and the queue by entry time. */
    @Test fun statusLightsAreNoLongerFalselyGreen() {
        val h=3_600_000L
        val t=FirstPass.FROM_MS+40*24*h // a day in November: after 10/1
        val ok=StatusLights.Input(now=t,folderConnected=true,modelState="READY",consent=true,lastTranscribedMs=t-h,
            folderReadable=true,lastDiscoveryMs=t-h)
        fun bad(i:StatusLights.Input,tag:String)=StatusLights.lights(i).single { !it.ok }.also { assertEquals(tag,it.tag) }.words
        // Normal: all green.
        assertTrue(StatusLights.lights(ok).all { it.ok })
        // Folder chosen but the permission is gone: amber, pointing up to the connection group.
        val lost=bad(ok.copy(folderReadable=false),"status_import")
        assertTrue(lost,lost.contains("풀렸어요") && lost.contains("위 연결 묶음에서"))
        assertTrue(bad(ok.copy(folderConnected=false,folderReadable=false),"status_import").contains("위 연결 묶음에서"))
        // Permission kept but no good discovery cycle for a day (or never): amber.
        assertTrue(bad(ok.copy(lastDiscoveryMs=ok.now-25*h),"status_import").contains("하루"))
        assertTrue(bad(ok.copy(lastDiscoveryMs=0),"status_import").contains("하루"))
        assertTrue(StatusLights.lights(ok.copy(lastDiscoveryMs=ok.now-23*h)).all { it.ok })
        // The first pass is over and a new call has waited 7 hours in the queue: amber (it used to stay green).
        val s=org.json.JSONObject().put(PipelineSettingsKeys.FIRST_PASS_START_MS,FirstPass.FROM_MS+86_400_000L).put(PipelineSettingsKeys.FIRST_PASS_DONE,true)
        val q=StatusLights.queue(listOf(StatusLights.QueuedRow(ok.now-8*h,ok.now-7*h)),s,ok.now)
        assertEquals(1,q.newCount);assertEquals(ok.now-7*h,q.newOldestMs)
        assertTrue(bad(ok.copy(transcribeBacklog=q.count,oldestQueuedMs=q.oldestMs,lastTranscribedMs=ok.now-8*h),"status_transcribe").contains("1개"))
        // A recording that just entered the queue is no backlog, even if the call itself is old; a pre-10/1 one is not counted.
        val fresh=StatusLights.queue(listOf(StatusLights.QueuedRow(ok.now-30*h,ok.now-h),StatusLights.QueuedRow(FirstPass.FROM_MS-86_400_000L,ok.now-90*h)),s,ok.now)
        assertEquals(1,fresh.count);assertEquals(ok.now-h,fresh.oldestMs)
        assertTrue(StatusLights.lights(ok.copy(transcribeBacklog=fresh.count,oldestQueuedMs=fresh.oldestMs,lastTranscribedMs=ok.now-8*h)).all { it.ok })
        // While the first pass runs, recordings of calls more than 7 days ago are old (r7-1); recent ones are live.
        val running=org.json.JSONObject().put(PipelineSettingsKeys.FIRST_PASS_START_MS,ok.now-10*h)
        val split=StatusLights.queue(listOf(StatusLights.QueuedRow(ok.now-10*24*h,ok.now-9*h),StatusLights.QueuedRow(ok.now-2*h,ok.now-2*h)),running,ok.now)
        assertEquals(StatusLights.Queue(1,ok.now-2*h,1,ok.now-9*h),split)
        // Analysis: only live calls without a result for over 6 hours; old first-pass records may wait.
        val (late,age)=StatusLights.lateLive(listOf(StatusLights.WaitingRow(ok.now-7*h,ok.now-7*h),StatusLights.WaitingRow(ok.now-2*h,ok.now-2*h),
            StatusLights.WaitingRow(ok.now-30*h,ok.now-10*24*h)),running,ok.now)
        assertEquals(1,late);assertEquals(7*h,age)
        assertTrue(bad(ok.copy(waiting=late,oldestWaitMs=age),"status_analyze").contains("7시간"))
        // Update: ready for over a day is its own sentence.
        assertTrue(bad(ok.copy(updateReady=true,updateReadySinceMs=ok.now-25*h),"status_update").contains("하루 넘게"))
        assertFalse(bad(ok.copy(updateReady=true,updateReadySinceMs=ok.now-2*h),"status_update").contains("하루 넘게"))
        // Store fill: the fuller of the sealed file and the rows.
        assertEquals(50,StatusLights.dbPercent(CallRecordStore.DB_LIMIT_BYTES/2,10));assertEquals(90,StatusLights.dbPercent(1024,90_000))
        assertNull(StatusLights.dbPercent(null,null));assertEquals(100,StatusLights.dbPercent(CallRecordStore.DB_LIMIT_BYTES*2,null))
        StatusLights.lights(ok.copy(folderReadable=false,updateReady=true,updateReadySinceMs=1)).forEach { assertFalse(it.words,Regex("[·…]").containsMatchIn(it.words)) }
    }

    @Test fun settingsKeepThreeGroupsMailGuideAndMarkers() {
        seeded()
        SecureSettings(app).update { it.put(PipelineSettingsKeys.LAST_UPDATE_ERROR,"STATUS_FAILURE_STORAGE (6): 공간 부족") }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"status_update")!=null }
        assertEquals(listOf(SettingsScreen.GROUP_NOW,"group_연결","group_통화 카드","group_정리","group_상태와 도움"),views(a.window.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith("group_") })
        val update=views(tag(a,"status_update")).filterIsInstance<TextView>().joinToString { it.text }
        assertTrue(update,update.contains("저장 공간이 부족해 설치하지 못했어요") && !update.contains("STATUS") && !update.contains("공간 부족"))
        assertEquals("저장 공간이 부족해 설치하지 못했어요.",(tag(a,"update_error") as TextView).text.toString())
        assertTrue(texts(a).none { it.text.contains("STATUS_FAILURE") })
        assertTrue(texts(a).none { it.text.contains("녹음 1분을") || it.text.contains("·") })
        assertEquals(5,listOf("status_import","status_transcribe","status_analyze","status_notify","status_update").count { tag(a,it)!=null })
        val a2=launchMain(Screen.SETTINGS)
        settle { tag(a2,"status_update")!=null }
        // The preview seed has no mail connected: the guide for the chosen service stands under the buttons.
        val guide=tag(a2,"mail_guide") as TextView
        assertTrue(guide.text.toString(),guide.text.toString().startsWith("네이버 메일 → 환경설정 → POP3/IMAP"))
        assertTrue(a2.settings.mailGuide("Gmail").startsWith("Gmail 설정 → 전달 및 POP/IMAP"))
        // Site memo sits behind its link.
        assertNotNull(tag(a2,"site_memo_toggle"));assertEquals(View.GONE,tag(a2,"site_memo").visibility)
        tag(a2,"site_memo_toggle").performClick();assertEquals(View.VISIBLE,tag(a2,"site_memo").visibility)
    }

    // ---- 3. check list ----
    @Test fun userItemIsStoredWithItsCompanyAndSource() {
        seeded()
        CallRecordStore(app).use { s ->
            assertNull(s.addUserItem("   "))
            val id=s.addUserItem("현장 사진 정리하기","△△철강")!!
            val item=s.openItems().single { it.id==id }
            assertEquals("△△철강",item.company);assertEquals("현장 사진 정리하기",item.title)
            val id2=s.addUserItem("업체 없이 적은 일")!!
            assertNull(s.openItems().single { it.id==id2 }.company)
            s.completeItem(id2,1)
            assertEquals(listOf(id2),s.doneItems(System.currentTimeMillis()-86_400_000L).map { it.id })
            assertTrue(s.doneItems(System.currentTimeMillis()+60_000L).isEmpty())
        }
    }

    @Test fun oldItemsFoldFinishedOnesFoldAndOwnItemsCanBeAdded() {
        seeded()
        val old=System.currentTimeMillis()-30*86_400_000L
        val oldId=CallRecordStore(app) { old }.use { it.addUserItem("한 달 전에 적은 일") }!!
        val doneId=CallRecordStore(app).use { s -> s.addUserItem("끝낸 일 하나")!!.also { s.completeItem(it,1) } }
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"tasks_done")!=null }
        assertNull("old row stays folded away",tag(a,"task_$oldId")?.takeIf { it.isShown })
        val fold=tag(a,"tasks_old") as TextView
        assertEquals("오래된 일 1개 보기 ›",fold.text.toString())
        assertEquals("끝낸 일 보기 (7일) ›",(tag(a,"tasks_done") as TextView).text.toString())
        assertNotNull(tag(a,"task_add"))
        assertTrue(texts(a).any { it.text.toString().endsWith("개 남았어요") })
        fold.performClick();assertTrue(tag(a,"tasks_old_rows").isShown)
        tag(a,"tasks_done").performClick();assertNotNull(tag(a,"done_$doneId"))
        assertTrue(CallRecordStore(app).use { s -> s.doneItems(0).any { it.id==doneId } })
        // Add one of the user's own.
        a.tasks.save("새로 적은 일","○○레미콘")
        settle { CallRecordStore(app).use { s -> s.openItems().any { it.title=="새로 적은 일" && it.company=="○○레미콘" } } }
    }

    // ---- 4. call detail ----
    @Test fun callDetailHasNoPromisesSectionAndNamesTheChecksItMade() {
        seeded()
        val call=CallRecordStore(app).use { s -> s.day(CallUi.today()).first { it.analysis?.counterpartCompany=="△△철강" } }
        val a=launchMain(Screen.CALLS,call.dayMs,call.recordId)
        settle { texts(a).any { it.text.toString()=="요약" } }
        assertTrue(texts(a).none { it.text.toString()=="약속" })
        assertTrue(texts(a).any { it.text.toString()=="이 통화에서 생긴 체크할 일" })
        assertTrue(tagged(a,"call_task_").isNotEmpty())
        assertEquals("제가 하기로",ScreenParts.ownerWords("me"));assertEquals("상대가 하기로",ScreenParts.ownerWords("them"));assertEquals("",ScreenParts.ownerWords("unknown"))
        assertEquals("‘견적서 먼저’",ScreenParts.quoteLine("견적서 먼저",false))
        assertEquals("비슷한 말: ‘견적서 먼저’",ScreenParts.quoteLine("견적서 먼저",true));assertEquals("",ScreenParts.quoteLine(" ",true))
    }

    // ---- 5. search ----
    @Test fun matchRuleIgnoresSpacesAndNeedsEveryWord() {
        assertTrue(ScreenParts.matches("김 민수","△△철강 김민수"));assertTrue(ScreenParts.matches("철강 김민수","△△철강 김민수"))
        assertFalse(ScreenParts.matches("철강 박지훈","△△철강 김민수"));assertTrue(ScreenParts.matches("","x"));assertFalse(ScreenParts.matches("x",null))
    }

    @Test fun callsSearchFindsAcrossDaysAndSaysWhenNothingMatches() {
        seeded()
        val a=launchMain(Screen.CALLS)
        settle { tag(a,"calls_search")!=null && tagged(a,"call_").isNotEmpty() }
        val box=tag(a,"calls_search") as EditText
        box.setText("철강")
        // The search waits until typing stops for a moment (r8-4, 250 ms).
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(CallsScreen.SEARCH_WAIT_MS+50))
        settle { tagged(a,"call_").isNotEmpty() && tag(a,"calls_search_wait")==null }
        assertTrue(tagged(a,"call_").all { it.tag != "calls_search_wait" })
        val hits=tagged(a,"call_").size;assertTrue(hits>=1)
        box.setText("전혀없는말")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(CallsScreen.SEARCH_WAIT_MS+50))
        settle { tag(a,"calls_search_none")!=null }
    }

    @Test fun companiesSearchFiltersTheList() {
        seeded()
        val a=launchMain(Screen.COMPANIES)
        settle { tag(a,"companies_search")!=null && tagged(a,"company_").isNotEmpty() }
        val all=tagged(a,"company_").size;assertTrue(all>=2)
        (tag(a,"companies_search") as EditText).setText("철강")
        assertEquals(1,tagged(a,"company_").size)
        (tag(a,"companies_search") as EditText).setText("없는업체")
        assertNotNull(tag(a,"companies_none"));assertTrue(tagged(a,"company_").isEmpty())
    }

    // ---- 6. theme, icon, intro ----
    @Test fun themeBlocksForcedDarkAndNotificationsUseTheMonochromeMark() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        assertFalse(a.theme.obtainStyledAttributes(intArrayOf(android.R.attr.forceDarkAllowed)).getBoolean(0,true))
        assertNotNull(app.getDrawable(kr.callbrief.R.drawable.ic_stat_ieum))
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        CallDigestNotifier.post(app,CallDigestSchedule.EVENING,CallDigestText.Message("오늘 통화 1건, 새 체크할 일 0개","본문"))
        val n=app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(kr.callbrief.R.drawable.ic_stat_ieum,n.smallIcon.resId)
        assertTrue(SecureSettings(app).snapshot().optLong("lastDigestMs",0)>0)
    }

    @Test fun introShowsOnceAfterAnInstallOrUpdate() {
        LaunchIntro.resetForTest();assertTrue(LaunchIntro.claim(app,true,false,9001))
        LaunchIntro.resetForTest();assertFalse("same version again",LaunchIntro.claim(app,true,false,9001))
        LaunchIntro.resetForTest();assertTrue("after an update",LaunchIntro.claim(app,true,false,9002))
        LaunchIntro.resetForTest();assertFalse("restored",LaunchIntro.claim(app,true,true,9003))
        LaunchIntro.resetForTest()
        assertEquals("LAST_INTRO_VERSION",LaunchIntro.LAST_INTRO_VERSION)
    }

    // ---- 7. evening line, B4 words ----
    @Test fun eveningBodyStartsWithTheLatestCall() {
        fun rec(id:String,start:Long,name:String,summary:String?)=CallRecord(id,id,start,0,name,null,"","ANALYZED",summary,null,emptyList(),null,0,null)
        val m=CallDigestText.evening(listOf(rec("a",1_000L,"가나","첫 번째 요약이에요. 둘째 문장."),rec("b",9_000_000_000L,"라마","마지막 통화 요약이에요.")),1)
        assertTrue(m.text,m.text.contains("라마, 마지막 통화 요약이에요."));assertFalse(m.text.contains("펼치면"))
        assertTrue(m.lines.first().contains("라마"));assertEquals(2,m.lines.size)
    }

    // ---- 9. update ----
    @Test fun updateUsesAnyConnection() { // v3.1 (DESIGN_V4 2.2-5): mobile data too, whenever
        val now=100L*3_600_000
        assertEquals(NetworkType.CONNECTED,CallUpdate.networkFor(now,now-5*3_600_000L))
        assertEquals(NetworkType.CONNECTED,CallUpdate.networkFor(now,now-6*3_600_000L))
        assertEquals(NetworkType.CONNECTED,CallUpdate.networkFor(now,0))
        assertEquals("lastUpdateError",PipelineSettingsKeys.LAST_UPDATE_ERROR);assertEquals("lastDigestMs",PipelineSettingsKeys.LAST_DIGEST_MS)
        assertEquals("STATUS_FAILURE_STORAGE (6): 공간 부족",CallUpdate.errorText(PackageInstaller.STATUS_FAILURE_STORAGE,"공간 부족"))
        assertEquals("STATUS_FAILURE (1)",CallUpdate.errorText(PackageInstaller.STATUS_FAILURE,null))
    }

    @Test fun aFailedInstallIsKeptForTheSettingsLight() {
        val i=Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_INCOMPATIBLE).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,"서명이 달라요")
        UpdateStatusReceiver().onReceive(app,i)
        settle { SecureSettings(app).snapshot().optString("lastUpdateError").startsWith("STATUS_FAILURE_INCOMPATIBLE") }
        assertTrue(SecureSettings(app).snapshot().optString("lastUpdateError").endsWith("서명이 달라요"))
        // A later success clears the error (r7-3: the receiver does it on its own thread, so wait for it).
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_SUCCESS))
        settle { SecureSettings(app).snapshot().optString("lastUpdateError").isEmpty() }
        assertEquals("",SecureSettings(app).snapshot().optString("lastUpdateError"))
    }

    // ---- v2.5 #20: 처음 정리 rests (battery under half and not charging, or no result for six hours) and says so ----
    @Test fun firstPassRestSaysWhyAndWhatIsLeft() {
        // v3.1 r12-6: 처음 정리 is shown in settings' first group, not on home.
        fun show(f:HomeScreen.FirstPassView):Pair<MainActivity,String> {
            SettingsScreen.firstPassHook={ f }
            val a=launchMain(Screen.SETTINGS);settle { tag(a,"first_pass_state")!=null }
            return a to views(tag(a,SettingsScreen.GROUP_NOW)).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() } }
        try {
            val (lowPage,low)=show(HomeScreen.FirstPassView(37,9.0,4,2,audioMinutesLeft=190,lowBattery=true))
            assertTrue(low,low.contains("잠시 쉬고 있어요") && low.contains("충전기에 꽂으면 이어서 정리해요"))
            assertTrue(low,low.contains("남은 녹음") && low.contains("37") && low.contains("남은 녹음 시간") && low.contains("3"))
            assertTrue(low,low.contains("요약을 기다리는 통화 4건"))
            assertFalse("no processing estimate while resting",low.contains("끝나기까지"))
            val (_,stalled)=show(HomeScreen.FirstPassView(0,null,6,0,stalled=true))
            assertTrue(stalled,stalled.contains("잠시 쉬고 있어요") && stalled.contains("결과가 오면 이어서 정리해요") && !stalled.contains("충전기"))
            val (runPage,running)=show(HomeScreen.FirstPassView(37,9.0,4,2,audioMinutesLeft=190))
            assertNull(tag(runPage,"first_pass_rest"));assertTrue(running,running.contains("녹음 37개 남음") && running.contains("끝나기까지 약 9시간"))
            for(t in listOf(low,stalled)) { assertFalse(t,t.contains("·")) }
            // 50대 기준: the rest line is a heading, the reason reading size, the numbers big.
            assertTrue((tag(lowPage,"first_pass_rest") as TextView).textSize/lowPage.resources.displayMetrics.scaledDensity>=CallUi.HEADING)
            assertTrue(views(tag(lowPage,SettingsScreen.GROUP_NOW)).filterIsInstance<TextView>().all { it.textSize/it.resources.displayMetrics.scaledDensity>=16f })
            // Home shows none of it.
            val home=launchMain(Screen.HOME);settle { tag(home,"home_today")!=null }
            home.home.render(HomeScreen.Snapshot(true,true,"READY",2,0,1,0,0,1_793_300_000_000,firstPass=HomeScreen.FirstPassView(37,9.0,4,2)))
            assertNull(tag(home,"home_first_pass"));assertTrue(texts(home).none { it.text.contains("지난 통화 정리") })
        } finally { SettingsScreen.firstPassHook=null }
        assertEquals("40" to "분",HomeScreen.roughLength(40));assertEquals("3" to "시간",HomeScreen.roughLength(190));assertEquals("1" to "분",HomeScreen.roughLength(0))
    }
}
