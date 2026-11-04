package kr.callbrief.pipeline

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
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

/** Round 3 screens: icon set, night colours, undo of "다 됐어요", dial buttons, the short to-do list on home, status lights, links between screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UiRoundThreeTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun tagged(a:android.app.Activity,prefix:String)=views(a.window.decorView).filter { (it.tag as? String)?.startsWith(prefix)==true }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val english=Regex("[A-Z]{3,}_[A-Z_]+|\\b(WAITING|FAILED|TRANSCRIBED|ANALYZED|null)\\b")
    @After fun backToDay() { CallUi.applyTheme(RuntimeEnvironment.getApplication());TaskActions.resetIo() }

    private fun seeded() { PreviewSeed.ensure(RuntimeEnvironment.getApplication()) }

    @Test fun iconSetHasTheFourteenIconsAndEveryOneParses() {
        assertEquals(14,CallIcons.NAMED.size)
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        CallIcons.NAMED.forEach { k -> assertTrue(k,CallIcons.paths(k).isNotEmpty());assertEquals(CallUi.dp(a,24),CallUi.icon(a,k,CallUi.INK).intrinsicWidth) }
        assertTrue(CallIcons.kinds.containsAll(listOf("tick","chevron","plus")))
    }

    @Test fun captionsAreMarkedAndSmallerThanBody() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        val c=CallUi.caption(a,"오늘 요청");val b=CallUi.text(a,"본문")
        assertEquals(16f,c.getTag(kr.callbrief.R.id.ui_min_sp));assertNull(b.getTag(kr.callbrief.R.id.ui_min_sp))
        assertEquals(CallUi.CAPTION,c.textSize/c.resources.displayMetrics.scaledDensity,0.01f);assertEquals(CallUi.BODY,b.textSize/b.resources.displayMetrics.scaledDensity,0.01f)
        assertEquals(15f,CallUi.pill(a,"정리 중 1건",CallUi.ACCENT).getTag(kr.callbrief.R.id.ui_min_sp))
    }

    @Test fun nightModeSwitchesEveryColourSet() {
        val light=Robolectric.buildActivity(MainActivity::class.java).get();CallUi.page(light)
        assertFalse(CallUi.night);assertEquals(android.graphics.Color.parseColor("#F2F4F6"),CallUi.PAGE);assertEquals(android.graphics.Color.parseColor("#3268F0"),CallUi.ACCENT)
        RuntimeEnvironment.setQualifiers("+night")
        val dark=Robolectric.buildActivity(MainActivity::class.java).get();CallUi.page(dark)
        assertTrue(CallUi.night)
        fun c(h:String)=android.graphics.Color.parseColor(h)
        assertEquals(c("#111418"),CallUi.PAGE);assertEquals(c("#1A1F26"),CallUi.CARD);assertEquals(c("#2A3038"),CallUi.LINE);assertEquals(c("#F2F4F6"),CallUi.INK)
        assertEquals(c("#A4ADB8"),CallUi.SUBTLE);assertEquals(c("#6B93FF"),CallUi.ACCENT);assertEquals(c("#8B95A1"),CallUi.MUTED)
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    @Test fun noRedAnywhereInThePalette() {
        fun red(c:Int)=android.graphics.Color.red(c)>180 && android.graphics.Color.green(c)<110 && android.graphics.Color.blue(c)<110
        listOf(false,true).forEach { n ->
            if(n) RuntimeEnvironment.setQualifiers("+night") else RuntimeEnvironment.setQualifiers("+notnight")
            val a=Robolectric.buildActivity(MainActivity::class.java).get();CallUi.page(a)
            listOf(CallUi.INK,CallUi.SUBTLE,CallUi.MUTED,CallUi.ACCENT,CallUi.ACCENT_SOFT,CallUi.AMBER,CallUi.AMBER_SOFT,CallUi.AMBER_RAIL,CallUi.OK,CallUi.CHIP,CallUi.MUTED_BAR).forEach { assertFalse(java.lang.Integer.toHexString(it),red(it)) }
        }
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    @Test fun dialButtonIsHiddenWithoutANumberAndOpensTheDialerWithOne() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        assertNull(CallUi.dialButton(a,null,"김민수"));assertNull(CallUi.dialButton(a,"",  "김민수"));assertNull(CallUi.dialButton(a,"lee@electric.co.kr","이영호"))
        val b=CallUi.dialButton(a,"01011112222","김민수")!!
        assertEquals(CallUi.dp(a,44),b.layoutParams.width);assertTrue(b.contentDescription.toString().contains("김민수"))
        b.performClick()
        val started=shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL,started.action);assertEquals("tel:01011112222",started.data.toString())
    }

    @Test fun undoDoneBringsAnItemBackAndRefusesAStaleRevision() {
        seeded()
        val ctx=RuntimeEnvironment.getApplication()
        val item=CallRecordStore(ctx).use { it.openItems().first() }
        CallRecordStore(ctx).use { it.completeItem(item.id,item.revision) }
        assertTrue(CallRecordStore(ctx).use { it.openItems() }.none { it.id==item.id })
        try { CallRecordStore(ctx).use { it.undoDone(item.id,item.status,item.revision) };fail("wrong revision") } catch(e:IllegalArgumentException) { assertEquals("ITEM_REVISION_CONFLICT",e.message) }
        CallRecordStore(ctx).use { it.undoDone(item.id,item.status,item.revision+1) }
        val back=CallRecordStore(ctx).use { it.openItems() }.single { it.id==item.id }
        assertEquals(item.status,back.status);assertEquals(item.revision+2,back.revision)
        assertNotNull("the preview call still being summarised",CallRecordStore(ctx).use { it.oldestWaitingMs() })
    }

    @Test fun homeShowsThreeTasksAboveTheCallMemosAndDoneFadesWithUndo() {
        seeded()
        val a=launchMain(Screen.HOME)
        fun homeTasks()=tagged(a,"home_task_").filter { it.tag!="home_task_mix" }
        settle { homeTasks().isNotEmpty() }
        val root=a.window.decorView
        val order=listOf("home_today","home_tasks","home_memos").map { t -> views(root).indexOfFirst { it.tag==t } }
        assertTrue(order.toString(),order.all { it>=0 } && order==order.sorted())
        assertTrue(homeTasks().size<=3)
        val first=homeTasks().first()
        val done=views(first).first { it.tag=="task_done" }
        done.performClick()
        settle { root.findViewWithTag<View>("snack_undo")!=null }
        assertTrue(first.alpha<1f || first.animation!=null || !first.isEnabled)
        root.findViewWithTag<View>("snack_undo").performClick()
        settle { root.findViewWithTag<View>("snack_undo")==null }
        val all=texts(a).joinToString("\n") { it.text }
        assertFalse(all,english.containsMatchIn(all));assertFalse(all,all.contains("·"))
    }

    @Test fun tasksScreenCompletesThenTimeoutRemovesTheRow() {
        seeded()
        val a=launchMain(Screen.TASKS)
        settle { tagged(a,"task_").any { it.tag.toString().startsWith("task_") && it.tag!="task_done" } }
        val before=CallRecordStore(RuntimeEnvironment.getApplication()).use { it.openItems().size }
        val row=tagged(a,"task_").first { it.tag!="task_done" }
        views(row).first { it.tag=="task_done" }.performClick()
        // The bar is there the moment the button is pressed; the store follows.
        assertNotNull(a.window.decorView.findViewWithTag<View>("snack_undo"))
        settle { CallRecordStore(RuntimeEnvironment.getApplication()).use { it.openItems().size }==before-1 }
        // The bar goes after about five seconds and the list is built again without that row.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        settle { a.window.decorView.findViewWithTag<View>("snack_undo")==null }
    }

    @Test fun settingsShowsFiveStatusLightsInTheLastGroup() {
        seeded()
        val a=launchMain(Screen.SETTINGS)
        settle { a.window.decorView.findViewWithTag<View>("settings_status")!=null }
        val root=a.window.decorView
        listOf("status_import","status_transcribe","status_analyze","status_notify","status_update").forEach { assertNotNull(it,root.findViewWithTag<View>(it)) }
        val all=texts(a).joinToString("\n") { it.text }
        assertTrue(all,all.contains("지금 상태") && all.contains("녹음 가져오기") && all.contains("글자로 바꾸기") && all.contains("통화 정리"))
        assertFalse(all,all.contains("녹음 1분을 글자로 바꾸는 데"))
        assertEquals(listOf("연결","정리","상태와 도움"),views(root).mapNotNull { (it.tag as? String)?.takeIf { t -> t.startsWith("group_") }?.removePrefix("group_") })
        assertTrue(views(root).indexOfFirst { it.tag=="settings_status" } > views(root).indexOfFirst { it.tag=="folder_state" })
    }

    @Test fun callDetailLinksToItsCompanyAndCompanyOpensFromTheLink() {
        seeded()
        val ctx=RuntimeEnvironment.getApplication()
        val call=CallRecordStore(ctx).use { s -> s.day(CallUi.today()).first { it.analysis?.counterpartCompany=="△△철강" } }
        val a=launchMain(Screen.CALLS,call.dayMs,call.recordId)
        settle { a.window.decorView.findViewWithTag<View>("call_company_link")!=null }
        a.window.decorView.findViewWithTag<View>("call_company_link").performClick()
        // One Activity (design G1): the company page opens in the same window, no new Activity.
        assertNull(shadowOf(a).nextStartedActivity)
        val c=a;assertEquals(Screen.COMPANIES,a.screen)
        settle { texts(c).any { it.text.toString().startsWith("사람 (") } }
        assertTrue(texts(c).any { it.text.toString()=="△△철강" })
    }

    // ---- v2.7 r8-3: parts that answer at once ----

    @Test fun oncePassesTheFirstPressAndIgnoresASecondWithin600ms() {
        val a=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v=View(a);var n=0
        assertTrue(CallUi.once(v) { n++ })
        assertFalse(CallUi.once(v) { n++ })
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertFalse(CallUi.once(v) { n++ })
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertTrue(CallUi.once(v) { n++ })
        assertEquals(2,n)
        // The shared parts count a press once: a double tap on a row or a button runs its action once.
        var opened=0;var pressed=0
        val row=CallUi.memoRow(a,"오후 2:05","김민수","견적서 보내기로 했어요.") { opened++ }
        row.performClick();row.performClick()
        val b=CallUi.button(a,"저장") { pressed++ }
        b.performClick();b.performClick()
        assertEquals(1,opened);assertEquals(1,pressed)
    }

    @Test fun busyTurnsTheButtonOffWithNewWordsAndIdlePutsItBack() {
        val a=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var ran=0
        val b=CallUi.button(a,"저장","저장하는 중이에요") { ran++ }
        b.performClick()
        assertEquals(1,ran);assertFalse(b.isEnabled);assertEquals("저장하는 중이에요",b.text.toString());assertTrue(CallUi.isBusy(b))
        CallUi.idle(b)
        assertTrue(b.isEnabled);assertEquals("저장",b.text.toString());assertFalse(CallUi.isBusy(b))
        // A view and all of its children go off together.
        val row=CallUi.taskRow(a,"견적서 보내기",System.currentTimeMillis(),"△△철강",onDone={})
        CallUi.enableAll(row,false)
        assertTrue(views(row).none { it.isEnabled })
    }

    @Test fun loadingThenRetryCardWithAButtonThatReloads() {
        val a=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val column=android.widget.LinearLayout(a)
        CallUi.loading(column)
        assertEquals("불러오는 중이에요",(column.findViewWithTag<View>("loading_text") as TextView).text.toString())
        var again=0
        CallUi.retryCard(column) { again++ }
        assertNull("the card takes the place of the loading line",column.findViewWithTag<View>("loading"))
        assertEquals("잠시 후 다시 열어주세요",(column.findViewWithTag<View>("retry_message") as TextView).text.toString())
        val retry=column.findViewWithTag<android.widget.Button>("retry_button")
        assertEquals("다시 불러오기",retry.text.toString())
        retry.performClick();assertEquals(1,again)
        CallUi.retryCard(column) {};assertEquals(1,views(column).count { it.tag=="retry_card" })
    }

    private fun tasksWithBlockedSave(fail:Boolean):Triple<CallTasksActivity,java.util.concurrent.CountDownLatch,java.util.concurrent.atomic.AtomicInteger> {
        seeded()
        val gate=java.util.concurrent.CountDownLatch(1);val calls=java.util.concurrent.atomic.AtomicInteger()
        TaskActions.completeIo={ _,_ -> calls.incrementAndGet();gate.await(5,java.util.concurrent.TimeUnit.SECONDS);if(fail) error("disk") }
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
        settle { tagged(a,"task_done").isNotEmpty() }
        return Triple(a,gate,calls)
    }

    /** The row a [다 됐어요] button sits in (the view the row builder returned: the first one up that is pale after a press). */
    private fun rowOf(done:View):View { var v:View=done;while(v.parent is View && (v.parent as View).tag!="page_holder" && v.alpha==1f) v=v.parent as View;return v }

    @Test fun doneGoesOffAndShowsTheBarBeforeTheStoreAndCountsOnce() {
        val (a,gate,calls)=tasksWithBlockedSave(fail=false)
        val done=tagged(a,"task_done").first()
        done.performClick()
        // Before any IO is done: the button is off, the row is pale and off with all it holds, the bar is up with the G3 words.
        assertFalse(done.isEnabled)
        val row=rowOf(done)
        assertEquals(0.35f,row.alpha,0.01f);assertTrue(views(row).none { it.isEnabled })
        val bar=a.window.decorView.findViewWithTag<View>("snack_bar")!!
        assertTrue(views(bar).filterIsInstance<TextView>().any { it.text.toString()=="끝낸 일로 옮겼어요" })
        // A second press (past the double-tap window, straight to the action) is ignored while the first is being saved.
        val item=CallRecordStore(RuntimeEnvironment.getApplication()).use { it.openItems() }.first { TaskActions.inFlight(it.id) }
        TaskActions.complete(a,item,row) {}
        gate.countDown()
        settle { !TaskActions.inFlight(item.id) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,calls.get())
    }

    @Test fun doneComesBackWhenTheStoreFails() {
        val (a,gate,_)=tasksWithBlockedSave(fail=true)
        val done=tagged(a,"task_done").first()
        done.performClick()
        assertFalse(done.isEnabled);assertNotNull(a.window.decorView.findViewWithTag<View>("snack_undo"))
        gate.countDown()
        settle { done.isEnabled }
        var p:View?=done;while(p!=null && p.tag!="page_holder") { assertTrue(p.isEnabled);assertEquals(1f,p.alpha,0.01f);p=p.parent as? View }
        assertNull("the bar goes",a.window.decorView.findViewWithTag<View>("snack_undo"))
        assertEquals("바꾸지 못했어요. 다시 눌러 주세요.",org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun statusLightsSayWhereTheFixIsAndNameTheGroup() {
        val ok=StatusLights.Input(now=100L*3_600_000,folderConnected=true,modelState="READY",consent=true)
        assertTrue(StatusLights.lights(ok).all { it.target==null })
        val noFolder=StatusLights.lights(ok.copy(folderConnected=false)).single { !it.ok }
        assertTrue(noFolder.words,noFolder.words.contains("위 연결 묶음에서"));assertEquals(StatusLights.GROUP_CONNECT,noFolder.target)
        val noConsent=StatusLights.lights(ok.copy(consent=false)).single { !it.ok }
        assertTrue(noConsent.words,noConsent.words.contains("위 정리 묶음에서"));assertEquals(StatusLights.GROUP_SUMMARY,noConsent.target)
        assertEquals(StatusLights.GROUP_STATUS,StatusLights.lights(ok.copy(modelState="FAILED")).single { !it.ok }.target)
        assertNull("the phone's own settings",StatusLights.lights(ok.copy(notifyAllowed=false)).single { !it.ok }.target)
        StatusLights.lights(ok.copy(folderConnected=false,consent=false,modelState="FAILED")).forEach { assertFalse(it.words,it.words.contains("아래 연결") || it.words.contains("아래 정리")) }
        // The groups the targets name are the ones settings draws.
        seeded()
        val a=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        settle { a.window.decorView.findViewWithTag<View>("settings_status")!=null }
        listOf(StatusLights.GROUP_CONNECT,StatusLights.GROUP_SUMMARY,StatusLights.GROUP_STATUS).forEach { assertNotNull(it,a.window.decorView.findViewWithTag<View>(it)) }
        // A light row given a press action is a button.
        var went=0
        val light=CallUi.signalRow(a,"녹음 가져오기",noFolder.words,false) { went++ }
        assertTrue(light.isClickable);light.performClick();assertEquals(1,went)
        assertFalse(CallUi.signalRow(a,"알림","정상",true).isClickable)
    }

    @Test fun aBareNumberIsShownAsANumberWithoutHonorific() {
        assertEquals("김민수 님",CallUi.who("김민수"))
        assertEquals("010-1111-0001",CallUi.who("01011110001"))
        assertEquals("02-123-4567",CallUi.who("021234567"))
        assertEquals("010-5555-6666",CallUi.who("010-5555-6666"))
    }
}
