package kr.callbrief.pipeline

import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Senior-friendly UI: plain words, big text, big buttons, the dashboard and bottom bar on the home screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallUiSeniorTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun sp(t:TextView)=t.textSize/t.resources.displayMetrics.scaledDensity
    /** The round 3 rule (11/4 review 7.2): text to read is 18sp and up; an aside made with CallUi.caption is 16 and up, a badge 15 and up. */
    private fun sizeOk(t:TextView):Boolean { val min=(t.getTag(kr.callbrief.R.id.ui_min_sp) as? Float) ?: 18f;return sp(t)>=min }
    private fun settle(a:android.app.Activity,check:()->Boolean) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val english=Regex("[A-Z]{3,}_[A-Z_]+|\\b(WAITING|FAILED|TRANSCRIBED|ANALYZED|null)\\b")

    private fun home(snapshot:HomeScreen.Snapshot):MainActivity {
        val a=launchMain(Screen.HOME)
        settle(a) { a.window.decorView.findViewWithTag<View>("home_calls_button")!=null }
        a.home.render(snapshot);return a
    }

    @Test fun homeShowsTodayCardThreeDoorsAndTheLineUntilSetupIsDone() {
        val a=home(HomeScreen.Snapshot(folder=true,consent=false,model="DOWNLOADING",calls=3,working=1,newTasks=2,due=4,overdue=1,lastAnalyzedMs=0))
        val root=a.window.decorView
        // v3.1 r12-6: setup is in settings; home has one line that says so while a required step is left.
        for(tag in listOf("home_today","home_calls_button","home_tasks_button","home_settings_button","home_attention"))assertNotNull(tag,root.findViewWithTag<View>(tag))
        assertNull(root.findViewWithTag<View>("home_setup"))
        val all=texts(a).joinToString("\n"){it.text.toString()}
        assertTrue(all,all.contains("오늘 통화"));assertTrue(all.contains("정리 중 1건"));assertTrue(all.contains("오늘 생긴 체크") && all.contains("남은 체크할 일"));assertTrue(all,all.contains("설정에서 확인할 일이 있어요 ›") && !all.contains("처음 설정"))
        assertFalse(all,english.containsMatchIn(all))
        val buttons=views(root).filterIsInstance<Button>().filter{(it.tag as? String)?.startsWith("home_")==true}
        assertTrue(buttons.all{ it.minHeight>=(56*it.resources.displayMetrics.density).toInt() })
        val done=home(HomeScreen.Snapshot(true,true,"READY",0,0,0,0,0,0))
        assertNull(done.window.decorView.findViewWithTag<View>("home_attention"))
    }

    @Test fun mainScreensUseLargeTextAndPlainWords() {
        val a=home(HomeScreen.Snapshot(true,true,"READY",1,0,1,1,0,1_793_300_000_000))
        assertTrue(texts(a).filter{it !is Button}.filterNot(::sizeOk).joinToString { "${it.text} ${sp(it)}" },texts(a).filter{it !is Button}.all(::sizeOk))
        val today=launchMain(Screen.CALLS)
        settle(today) { texts(today).any { it.text.toString().endsWith("의 통화") } }
        assertTrue(texts(today).filter{it !is Button}.all(::sizeOk))
        val settings=launchMain(Screen.SETTINGS)
        settle(settings) { texts(settings).any { it.text.toString()=="설정" } }
        val all=texts(settings).joinToString("\n"){it.text.toString()}
        assertFalse(all,english.containsMatchIn(all));assertTrue(texts(settings).filter{it !is Button && it !is android.widget.EditText}.all(::sizeOk))
    }

    @Test fun stateAndDueWordsArePlainKorean() {
        listOf(CallRecordStore.TRANSCRIBED,CallRecordStore.WAITING_CONSENT,CallRecordStore.FAILED).forEach {
            val w=CallUi.state(it);assertTrue(w.isNotBlank());assertFalse(w,english.containsMatchIn(w))
        }
        val today=CallUi.today()
        assertEquals("오늘까지",CallUi.dueWords(today.toString()));assertEquals("내일까지",CallUi.dueWords(today.plusDays(1).toString()))
        assertEquals("2일 지남",CallUi.dueWords(today.minusDays(2).toString()));assertEquals("",CallUi.dueWords(""));assertEquals("다음 주",CallUi.dueWords("다음 주"))
        assertEquals("확인 필요",CallUi.taskStatus("needs_confirmation"))
    }

    @Test fun tasksAreGroupedByRequestDay() {
        val now=System.currentTimeMillis();val day=86_400_000L
        fun item(id:String,at:Long,status:String="open")=OpenItem(id,id,"",status,1,"",null,createdMs=at)
        val a=launchMain(Screen.TASKS)
        val groups=a.tasks.groups(listOf(item("old",now-5*day),item("today",now),item("yesterday",now-day),item("check",now,"needs_confirmation")))
        assertEquals(listOf("끝났는지 확인해 주세요","오늘 요청한 일","어제 요청한 일","그전에 요청한 일"),groups.map{it.first})
        assertEquals("오늘 요청",CallUi.requested(now))
    }

    /** WCAG relative luminance and contrast of two colours. */
    private fun luminance(c:Int):Double { fun ch(v:Int):Double { val x=v/255.0;return if(x<=0.03928) x/12.92 else Math.pow((x+0.055)/1.055,2.4) }
        return 0.2126*ch(android.graphics.Color.red(c))+0.7152*ch(android.graphics.Color.green(c))+0.0722*ch(android.graphics.Color.blue(c)) }
    private fun contrast(a:Int,b:Int):Double { val (lo,hi)=listOf(luminance(a),luminance(b)).sorted();return (hi+0.05)/(lo+0.05) }

    @Test fun textColoursReachFourAndAHalfToOne() {
        listOf("+notnight","+night").forEach { q ->
            org.robolectric.RuntimeEnvironment.setQualifiers(q)
            CallUi.applyTheme(org.robolectric.RuntimeEnvironment.getApplication())
            // Grey text on a white sheet (rows, captions, the tab words), and captions straight on the grey page.
            listOf("INK" to CallUi.INK,"SUBTLE" to CallUi.SUBTLE,"MUTED" to CallUi.MUTED).forEach { (n,c) ->
                assertTrue("$q $n on the sheet ${contrast(c,CallUi.CARD)}",contrast(c,CallUi.CARD)>=4.5) }
            listOf("INK" to CallUi.INK,"PAGE_CAPTION" to CallUi.PAGE_CAPTION).forEach { (n,c) ->
                assertTrue("$q $n on the page ${contrast(c,CallUi.PAGE)}",contrast(c,CallUi.PAGE)>=4.5) }
        }
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight");CallUi.applyTheme(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(android.graphics.Color.parseColor("#6B7684"),CallUi.MUTED);assertEquals(android.graphics.Color.parseColor("#5F6B78"),CallUi.PAGE_CAPTION)
        assertEquals("lines keep their light grey",android.graphics.Color.parseColor("#B0B8C1"),CallUi.FAINT)
    }

    @Test fun theCurrentTabIsShownByWeightAndAFilledIconNotByAPaleColour() {
        val a=launchMain(Screen.TASKS)
        settle(a) { a.window.decorView.findViewWithTag<View>("home_tasks_button")!=null }
        val on=a.window.decorView.findViewWithTag<Button>("home_tasks_button");val off=a.window.decorView.findViewWithTag<Button>("home_home_button")
        assertEquals(CallUi.font(a,CallUi.Weight.SEMIBOLD),on.typeface);assertEquals(CallUi.font(a,CallUi.Weight.REGULAR),off.typeface)
        assertTrue(contrast(off.currentTextColor,CallUi.CARD)>=4.5)
    }

    @Test fun nextReportTimeIsPlainKorean() {
        val z=java.time.ZoneId.of("Asia/Seoul")
        assertEquals("오늘 저녁 7시 (통화 정리)",CallUi.nextReport(19,6,java.time.ZonedDateTime.of(2026,10,30,9,0,0,0,z)))
        assertEquals("내일 아침 6시 (체크할 일)",CallUi.nextReport(19,6,java.time.ZonedDateTime.of(2026,10,30,20,0,0,0,z)))
    }
}
