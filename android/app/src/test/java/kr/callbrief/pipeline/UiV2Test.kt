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

    // ---- 1. back goes home ----
    @Test fun backFromEveryMainScreenGoesHomeAndTabsReorder() {
        seeded()
        listOf(CallTasksActivity::class.java,CallSettingsActivity::class.java,CompaniesActivity::class.java,TodayCallsActivity::class.java).forEach { cls ->
            val a=Robolectric.buildActivity(cls).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            a.onBackPressedDispatcher.onBackPressed()
            val next=shadowOf(a).nextStartedActivity
            assertEquals(cls.simpleName,HomeActivity::class.java.name,next.component!!.className)
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,next.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
        CallUi.Tab.entries.forEach { t ->
            CallUi.go(a,t);val f=shadowOf(a).nextStartedActivity.flags
            if(t==CallUi.Tab.HOME) assertTrue(f and Intent.FLAG_ACTIVITY_CLEAR_TOP!=0) else assertTrue(t.name,f and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT!=0)
        }
    }

    // ---- 2. settings ----
    @Test fun statusLightsJudgeEachLine() {
        val ok=StatusLights.Input(now=100L*3_600_000,folderConnected=true,modelState="READY",consent=true)
        assertTrue(StatusLights.lights(ok).all { it.ok && it.words=="정상" })
        assertEquals(listOf("녹음 가져오기","글자로 바꾸기","통화 정리","알림","업데이트"),StatusLights.lights(ok).map { it.label })
        fun bad(i:StatusLights.Input,tag:String)=StatusLights.lights(i).single { !it.ok }.also { assertEquals(tag,it.tag) }.words
        assertTrue(bad(ok.copy(folderConnected=false),"status_import").contains("폴더"))
        assertTrue(bad(ok.copy(modelState="FAILED"),"status_transcribe").contains("모델"))
        assertTrue(bad(ok.copy(transcribeBacklog=3,lastTranscribedMs=ok.now-7*3_600_000L),"status_transcribe").contains("충전"))
        assertTrue(StatusLights.lights(ok.copy(transcribeBacklog=3,lastTranscribedMs=ok.now-3_600_000L)).all { it.ok })
        assertTrue(bad(ok.copy(consent=false),"status_analyze").contains("동의"))
        assertTrue(bad(ok.copy(waiting=2,oldestWaitMs=4*3_600_000L),"status_analyze").contains("4시간"))
        assertTrue(bad(ok.copy(failed=1),"status_analyze").contains("1건"))
        assertTrue(bad(ok.copy(notifyAllowed=false),"status_notify").contains("알림"))
        assertTrue(bad(ok.copy(updateError="STATUS_FAILURE_STORAGE (6)"),"status_update").contains("STATUS_FAILURE_STORAGE"))
        assertTrue(bad(ok.copy(updateReady=true),"status_update").contains("새 버전"))
        StatusLights.lights(ok.copy(folderConnected=false,consent=false)).forEach { assertFalse(it.words.contains("·")) }
    }

    @Test fun settingsKeepThreeGroupsMailGuideAndMarkers() {
        seeded()
        SecureSettings(app).update { it.put(PipelineSettingsKeys.LAST_UPDATE_ERROR,"STATUS_FAILURE_STORAGE (6): 공간 부족") }
        val a=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        settle { tag(a,"status_update")!=null }
        assertEquals(listOf("group_연결","group_정리","group_상태와 도움"),views(a.window.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith("group_") })
        val update=views(tag(a,"status_update")).filterIsInstance<TextView>().joinToString { it.text }
        assertTrue(update,update.contains("설치가 안 됐어요") && update.contains("STATUS_FAILURE_STORAGE"))
        assertTrue(texts(a).any { it.text.toString().startsWith("마지막 설치가 안 됐어요") })
        assertTrue(texts(a).none { it.text.contains("녹음 1분을") || it.text.contains("·") })
        assertEquals(5,listOf("status_import","status_transcribe","status_analyze","status_notify","status_update").count { tag(a,it)!=null })
        val a2=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        settle { tag(a2,"status_update")!=null }
        // The preview seed has no mail connected: the guide for the chosen service stands under the buttons.
        val guide=tag(a2,"mail_guide") as TextView
        assertTrue(guide.text.toString(),guide.text.toString().startsWith("네이버 메일 → 환경설정 → POP3/IMAP"))
        assertTrue(a2.mailGuide("Gmail").startsWith("Gmail 설정 → 전달 및 POP/IMAP"))
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
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
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
        a.save("새로 적은 일","○○레미콘")
        settle { CallRecordStore(app).use { s -> s.openItems().any { it.title=="새로 적은 일" && it.company=="○○레미콘" } } }
    }

    // ---- 4. call detail ----
    @Test fun callDetailHasNoPromisesSectionAndNamesTheChecksItMade() {
        seeded()
        val call=CallRecordStore(app).use { s -> s.day(CallUi.today()).first { it.analysis?.counterpartCompany=="△△철강" } }
        val a=Robolectric.buildActivity(TodayCallsActivity::class.java,Intent(app,TodayCallsActivity::class.java)
            .putExtra(TodayCallsActivity.EXTRA_CALL,call.recordId).putExtra(TodayCallsActivity.EXTRA_DAY_MS,call.dayMs)).setup().get()
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
        val a=Robolectric.buildActivity(TodayCallsActivity::class.java).setup().get()
        settle { tag(a,"calls_search")!=null && tagged(a,"call_").isNotEmpty() }
        val box=tag(a,"calls_search") as EditText
        box.setText("철강")
        settle { tagged(a,"call_").isNotEmpty() && tag(a,"calls_search_wait")==null }
        assertTrue(tagged(a,"call_").all { it.tag != "calls_search_wait" })
        val hits=tagged(a,"call_").size;assertTrue(hits>=1)
        box.setText("전혀없는말")
        settle { tag(a,"calls_search_none")!=null }
    }

    @Test fun companiesSearchFiltersTheList() {
        seeded()
        val a=Robolectric.buildActivity(CompaniesActivity::class.java).setup().get()
        settle { tag(a,"companies_search")!=null && tagged(a,"company_").isNotEmpty() }
        val all=tagged(a,"company_").size;assertTrue(all>=2)
        (tag(a,"companies_search") as EditText).setText("철강")
        assertEquals(1,tagged(a,"company_").size)
        (tag(a,"companies_search") as EditText).setText("없는업체")
        assertNotNull(tag(a,"companies_none"));assertTrue(tagged(a,"company_").isEmpty())
    }

    // ---- 6. theme, icon, intro ----
    @Test fun themeBlocksForcedDarkAndNotificationsUseTheMonochromeMark() {
        val a=Robolectric.buildActivity(HomeActivity::class.java).get()
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
    @Test fun updateUsesMobileDataOnlyAfterSixStaleHours() {
        val now=100L*3_600_000
        assertEquals(NetworkType.UNMETERED,CallUpdate.networkFor(now,now-5*3_600_000L))
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
        // Success changes nothing; the error is cleared when the phone is up to date again.
        UpdateStatusReceiver().onReceive(app,Intent().putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_SUCCESS))
        assertTrue(SecureSettings(app).snapshot().optString("lastUpdateError").isNotEmpty())
        CallUpdate.saveError(app,null);assertEquals("",SecureSettings(app).snapshot().optString("lastUpdateError"))
    }
}
