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

/** Senior-friendly UI: plain words, big text, big buttons, the three doors on the home screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallUiSeniorTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun sp(t:TextView)=t.textSize/t.resources.displayMetrics.scaledDensity
    private fun settle(a:android.app.Activity,check:()->Boolean) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val english=Regex("[A-Z]{3,}_[A-Z_]+|\\b(WAITING|FAILED|TRANSCRIBED|ANALYZED|null)\\b")

    private fun home(snapshot:HomeActivity.Snapshot):HomeActivity {
        val a=Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        settle(a) { a.window.decorView.findViewWithTag<View>("home_calls_button")!=null }
        a.render(snapshot);return a
    }

    @Test fun homeShowsTodayCardThreeDoorsAndSetupUntilDone() {
        val a=home(HomeActivity.Snapshot(folder=true,key=false,consent=false,model="DOWNLOADING",calls=3,working=1,newTasks=2,due=4,overdue=1,lastAnalyzedMs=0))
        val root=a.window.decorView
        for(tag in listOf("home_today","home_calls_button","home_tasks_button","home_settings_button","home_more_button","home_setup"))assertNotNull(tag,root.findViewWithTag<View>(tag))
        val all=texts(a).joinToString("\n"){it.text.toString()}
        assertTrue(all,all.contains("통화 3건 (정리 중 1건)"));assertTrue(all.contains("기한 지난 일 1개"));assertTrue(all.contains("처음 설정 3/6"))
        assertFalse(all,english.containsMatchIn(all))
        val buttons=views(root).filterIsInstance<Button>().filter{(it.tag as? String)?.startsWith("home_")==true}
        assertTrue(buttons.all{ it.minHeight>=(56*it.resources.displayMetrics.density).toInt() })
        val done=home(HomeActivity.Snapshot(true,true,true,"READY",0,0,0,0,0,0))
        assertNull(done.window.decorView.findViewWithTag<View>("home_setup"))
    }

    @Test fun mainScreensUseLargeTextAndPlainWords() {
        val a=home(HomeActivity.Snapshot(true,true,true,"READY",1,0,1,1,0,1_793_300_000_000))
        assertTrue(texts(a).filter{it !is Button}.all{ sp(it)>=18f })
        val today=Robolectric.buildActivity(TodayCallsActivity::class.java).setup().get()
        settle(today) { texts(today).any { it.text.toString()=="오늘 할 일" } }
        assertTrue(texts(today).filter{it !is Button}.all{ sp(it)>=18f })
        val settings=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        settle(settings) { texts(settings).any { it.text.toString()=="설정" } }
        val all=texts(settings).joinToString("\n"){it.text.toString()}
        assertFalse(all,english.containsMatchIn(all));assertTrue(texts(settings).filter{it !is Button && it !is android.widget.EditText}.all{ sp(it)>=18f })
    }

    @Test fun stateAndDueWordsArePlainKorean() {
        listOf(CallRecordStore.TRANSCRIBED,CallRecordStore.WAITING_KEY,CallRecordStore.WAITING_CONSENT,CallRecordStore.FAILED).forEach {
            val w=CallUi.state(it);assertTrue(w.isNotBlank());assertFalse(w,english.containsMatchIn(w))
        }
        val today=CallUi.today()
        assertEquals("오늘까지",CallUi.dueWords(today.toString()));assertEquals("내일까지",CallUi.dueWords(today.plusDays(1).toString()))
        assertEquals("2일 지남",CallUi.dueWords(today.minusDays(2).toString()));assertEquals("기한 없음",CallUi.dueWords(""));assertEquals("다음 주",CallUi.dueWords("다음 주"))
        assertEquals("확인 필요",CallUi.taskStatus("needs_confirmation"))
    }

    @Test fun tasksAreGroupedUrgentFirst() {
        val t=CallUi.today()
        fun item(id:String,due:String,status:String="open")=OpenItem(id,id,due,status,1,"",null)
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
        val groups=a.groups(listOf(item("later",t.plusDays(3).toString()),item("none",""),item("late",t.minusDays(1).toString()),item("now",t.toString()),item("check","","needs_confirmation")))
        assertEquals(listOf("기한 지난 일","오늘까지","끝났는지 확인해 주세요","다가오는 일","기한 없는 일"),groups.map{it.first})
    }
}
