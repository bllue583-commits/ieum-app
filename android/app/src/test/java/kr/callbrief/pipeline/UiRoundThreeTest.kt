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
    @After fun backToDay() { CallUi.applyTheme(RuntimeEnvironment.getApplication()) }

    private fun seeded() { PreviewSeed.ensure(RuntimeEnvironment.getApplication()) }

    @Test fun iconSetHasTheFourteenIconsAndEveryOneParses() {
        assertEquals(14,CallIcons.NAMED.size)
        val a=Robolectric.buildActivity(HomeActivity::class.java).get()
        CallIcons.NAMED.forEach { k -> assertTrue(k,CallIcons.paths(k).isNotEmpty());assertEquals(CallUi.dp(a,24),CallUi.icon(a,k,CallUi.INK).intrinsicWidth) }
        assertTrue(CallIcons.kinds.containsAll(listOf("tick","chevron","plus")))
    }

    @Test fun captionsAreMarkedAndSmallerThanBody() {
        val a=Robolectric.buildActivity(HomeActivity::class.java).get()
        val c=CallUi.caption(a,"오늘 요청");val b=CallUi.text(a,"본문")
        assertEquals(16f,c.getTag(kr.callbrief.R.id.ui_min_sp));assertNull(b.getTag(kr.callbrief.R.id.ui_min_sp))
        assertEquals(CallUi.CAPTION,c.textSize/c.resources.displayMetrics.scaledDensity,0.01f);assertEquals(CallUi.BODY,b.textSize/b.resources.displayMetrics.scaledDensity,0.01f)
        assertEquals(15f,CallUi.pill(a,"정리 중 1건",CallUi.ACCENT).getTag(kr.callbrief.R.id.ui_min_sp))
    }

    @Test fun nightModeSwitchesEveryColourSet() {
        val light=Robolectric.buildActivity(HomeActivity::class.java).get();CallUi.page(light)
        assertFalse(CallUi.night);assertEquals(android.graphics.Color.parseColor("#F2F4F6"),CallUi.PAGE);assertEquals(android.graphics.Color.parseColor("#3268F0"),CallUi.ACCENT)
        RuntimeEnvironment.setQualifiers("+night")
        val dark=Robolectric.buildActivity(HomeActivity::class.java).get();CallUi.page(dark)
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
            val a=Robolectric.buildActivity(HomeActivity::class.java).get();CallUi.page(a)
            listOf(CallUi.INK,CallUi.SUBTLE,CallUi.MUTED,CallUi.ACCENT,CallUi.ACCENT_SOFT,CallUi.AMBER,CallUi.AMBER_SOFT,CallUi.AMBER_RAIL,CallUi.OK,CallUi.CHIP,CallUi.MUTED_BAR).forEach { assertFalse(java.lang.Integer.toHexString(it),red(it)) }
        }
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    @Test fun dialButtonIsHiddenWithoutANumberAndOpensTheDialerWithOne() {
        val a=Robolectric.buildActivity(HomeActivity::class.java).get()
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
        val a=Robolectric.buildActivity(HomeActivity::class.java).setup().get()
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
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
        settle { tagged(a,"task_").any { it.tag.toString().startsWith("task_") && it.tag!="task_done" } }
        val before=CallRecordStore(RuntimeEnvironment.getApplication()).use { it.openItems().size }
        val row=tagged(a,"task_").first { it.tag!="task_done" }
        views(row).first { it.tag=="task_done" }.performClick()
        settle { a.window.decorView.findViewWithTag<View>("snack_undo")!=null }
        assertEquals(before-1,CallRecordStore(RuntimeEnvironment.getApplication()).use { it.openItems().size })
        // The bar goes after about five seconds and the list is built again without that row.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        settle { a.window.decorView.findViewWithTag<View>("snack_undo")==null }
    }

    @Test fun settingsShowsThreeStatusLightsOnTop() {
        seeded()
        val a=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        settle { a.window.decorView.findViewWithTag<View>("settings_status")!=null }
        val root=a.window.decorView
        listOf("status_waiting","status_oldest","status_last_result").forEach { assertNotNull(it,root.findViewWithTag<View>(it)) }
        val all=texts(a).joinToString("\n") { it.text }
        assertTrue(all,all.contains("지금 상태") && all.contains("마지막으로 결과를 받은 때"))
        assertTrue(views(root).indexOfFirst { it.tag=="settings_status" } < views(root).indexOfFirst { it.tag=="folder_state" })
    }

    @Test fun callDetailLinksToItsCompanyAndCompanyOpensFromTheLink() {
        seeded()
        val ctx=RuntimeEnvironment.getApplication()
        val call=CallRecordStore(ctx).use { s -> s.day(CallUi.today()).first { it.analysis?.counterpartCompany=="△△철강" } }
        val a=Robolectric.buildActivity(TodayCallsActivity::class.java,Intent(ctx,TodayCallsActivity::class.java)
            .putExtra(TodayCallsActivity.EXTRA_CALL,call.recordId).putExtra(TodayCallsActivity.EXTRA_DAY_MS,call.dayMs)).setup().get()
        settle { a.window.decorView.findViewWithTag<View>("call_company_link")!=null }
        a.window.decorView.findViewWithTag<View>("call_company_link").performClick()
        val next=shadowOf(a).nextStartedActivity
        assertEquals("△△철강",next.getStringExtra(CompaniesActivity.EXTRA_COMPANY_NAME))
        val c=Robolectric.buildActivity(CompaniesActivity::class.java,next).setup().get()
        settle { texts(c).any { it.text.toString().startsWith("사람 (") } }
        assertTrue(texts(c).any { it.text.toString()=="△△철강" })
    }

    @Test fun aBareNumberIsShownAsANumberWithoutHonorific() {
        assertEquals("김민수 님",CallUi.who("김민수"))
        assertEquals("010-1111-0001",CallUi.who("01011110001"))
        assertEquals("02-123-4567",CallUi.who("021234567"))
        assertEquals("010-5555-6666",CallUi.who("010-5555-6666"))
    }
}
