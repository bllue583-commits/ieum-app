package kr.callbrief.pipeline

import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
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
import org.robolectric.shadows.ShadowDialog

/** Design G2: every question of the app comes up as a bottom sheet (CallUi.sheetDialog), not an AlertDialog. Each sheet
 * opens, does what the old dialog did when a button is pressed, and closes on back or a touch outside. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SheetDialogTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val app get()=RuntimeEnvironment.getApplication()
    @After fun reset() { CallUi.applyTheme(app) }
    private fun sheet():Dialog=ShadowDialog.getLatestDialog().also { assertTrue(it.isShowing) }
    private fun Dialog.find(t:String):View=window!!.decorView.findViewWithTag<View>(t) ?: error("no $t in the sheet")
    private fun Dialog.texts()=views(window!!.decorView).filterIsInstance<TextView>().filter { it !is Button && it !is EditText }.map { it.text.toString() }

    /** The shape every sheet shares: at the bottom, full width, closes on back and outside, buttons stacked and full width. */
    private fun assertSheet(d:Dialog) {
        assertFalse("not an AlertDialog",d is android.app.AlertDialog)
        val lp=d.window!!.attributes
        assertEquals(android.view.Gravity.BOTTOM,lp.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,lp.width)
        assertTrue(shadowOf(d).isCancelableOnTouchOutside)
        val buttons=views(d.window!!.decorView).filterIsInstance<Button>()
        assertTrue(buttons.isNotEmpty())
        for(b in buttons) {
            assertEquals(LinearLayout.LayoutParams.MATCH_PARENT,b.layoutParams.width)
            assertTrue("big buttons",b.minHeight>=CallUi.dp(d.ownerActivity ?: return,56))
        }
        // Read at reading size: the title and the message are 18sp and up.
        val dm=d.context.resources.displayMetrics
        assertTrue((d.find("sheet_title") as TextView).textSize/dm.scaledDensity>=18f)
    }

    @Test fun ownCheckItemIsWrittenInASheet() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"task_add")!=null }
        tag(a,"task_add").performClick()
        val d=sheet();assertSheet(d)
        assertEquals("체크할 일 직접 적기",(d.find("sheet_title") as TextView).text.toString())
        (d.find("add_title") as EditText).setText("시멘트 견적 다시 받기")
        val primary=d.find("sheet_primary");val secondary=d.find("sheet_secondary")
        assertEquals("저장",(primary as Button).text.toString());assertEquals("취소",(secondary as Button).text.toString())
        primary.performClick()
        assertFalse(d.isShowing)
        settle { org.robolectric.shadows.ShadowToast.getTextOfLatestToast()!=null }
        assertEquals("체크할 일에 넣었어요",org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue(CallRecordStore(app).use { s -> s.openItems().any { it.title=="시멘트 견적 다시 받기" && it.company==null } })
    }

    @Test fun backAndCancelLeaveEverythingAsItWas() {
        PreviewSeed.ensure(app)
        val a=launchMain(Screen.TASKS)
        settle { tag(a,"task_add")!=null }
        tag(a,"task_add").performClick()
        var d=sheet();(d.find("add_title") as EditText).setText("적지 않을 일")
        d.onBackPressed();shadowOf(Looper.getMainLooper()).idle()
        assertFalse("back closes the sheet",d.isShowing)
        // A press counts once within 600ms (CallUi.once); a person opens it again later.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        tag(a,"task_add").performClick()
        d=sheet();(d.find("add_title") as EditText).setText("적지 않을 일")
        d.find("sheet_secondary").performClick();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(d.isShowing)
        assertTrue(CallRecordStore(app).use { s -> s.openItems().none { it.title=="적지 않을 일" } })
    }

    @Test fun myInfoIsSavedFromASheet() {
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"my_edit_"+PipelineSettingsKeys.MY_NAME)!=null }
        tag(a,"my_edit_"+PipelineSettingsKeys.MY_NAME).performClick()
        val d=sheet();assertSheet(d)
        assertEquals("이름",(d.find("sheet_title") as TextView).text.toString())
        (d.find("my_input") as EditText).setText("  김민지  ")
        d.find("sheet_primary").performClick()
        settle { SecureSettings(app).snapshot().optString(PipelineSettingsKeys.MY_NAME)=="김민지" }
        settle { (tag(a,"my_"+PipelineSettingsKeys.MY_NAME) as? TextView)?.text?.toString()=="김민지" }
    }

    @Test fun aPersonalNumberIsTakenBackThroughTwoSheets() {
        CallRecordStore(app).use { it.personalNumbers() } // the tables exist
        AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO personal_numbers VALUES(?,?)","01012345678",System.currentTimeMillis());null } }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"personal_list")!=null }
        tag(a,"personal_list").performClick()
        val list=sheet();assertSheet(list)
        assertEquals("눌러서 업무 번호로 되돌려요",(list.find("sheet_title") as TextView).text.toString())
        val option=list.find("sheet_option_0") as Button
        assertTrue(option.text.toString().startsWith("010-1234-5678"))
        option.performClick()
        assertFalse(list.isShowing)
        val ask=sheet();assertNotSame(list,ask);assertSheet(ask)
        assertTrue(ask.texts().any { it.startsWith("010-1234-5678 번호를 업무 번호로 되돌릴까요?") })
        assertEquals("되돌리기",(ask.find("sheet_primary") as Button).text.toString())
        ask.find("sheet_primary").performClick()
        settle { CallRecordStore(app).use { it.personalNumbers() }.isEmpty() }
        settle { (tag(a,"personal_state") as? TextView)?.text?.toString()?.startsWith("아직 없어요")==true }
    }

    @Test fun theSheetIsTitleMessageInputThenStackedButtons() {
        val a=launchMain(Screen.TASKS)
        // The sheet itself, on any screen: message, input and the two buttons, primary above secondary.
        var saved=false
        val input=CallUi.sheetInput(a,"김철주",tag="person_name_input")
        val d=CallUi.sheetDialog(a,"이름 고치기",message="바른 이름을 적고 [저장]을 누르세요.",body=input,primary="저장",onPrimary={ saved=true },secondary="취소")
        shadowOf(Looper.getMainLooper()).idle()
        assertSheet(d)
        val root=d.find("sheet_root") as LinearLayout
        val order=(0 until root.childCount).map { root.getChildAt(it).tag }
        assertTrue(order.toString(),order.indexOf("sheet_title")<order.indexOf("sheet_message") && order.indexOf("sheet_message")<order.indexOf("person_name_input")
            && order.indexOf("person_name_input")<order.indexOf("sheet_primary") && order.indexOf("sheet_primary")<order.indexOf("sheet_secondary"))
        d.find("sheet_primary").performClick()
        assertTrue(saved);assertFalse(d.isShowing)
    }

    @Test fun aFailedCheckKeepsTheSheetOpenWithItsWordsAndTheKeyboardComesUp() {
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
        var saved=0
        val input=CallUi.sheetInput(a,"",tag="item_title_input")
        val d=CallUi.sheetDialog(a,"체크할 일 적기",body=LinearLayout(a).apply { addView(input) },primary="저장",onPrimary={ saved++ },secondary="취소",
            validate={ if(input.text.isNullOrBlank()) "무엇을 할지 적어 주세요" else null })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the field inside the body gets the focus",input.isFocused)
        val mode=d.window!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,mode)
        val problem=d.find("sheet_problem") as TextView
        assertEquals(View.GONE,problem.visibility)
        d.find("sheet_primary").performClick()
        assertTrue("stays open",d.isShowing);assertEquals(0,saved)
        assertEquals(View.VISIBLE,problem.visibility);assertEquals("무엇을 할지 적어 주세요",problem.text.toString())
        assertEquals(CallUi.AMBER_SOFT,(problem.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertTrue(problem.textSize/problem.resources.displayMetrics.scaledDensity>=18f)
        // Past the double-tap window, with a title: saved once and closed.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        input.setText("견적서 보내기")
        d.find("sheet_primary").performClick()
        assertFalse(d.isShowing);assertEquals(1,saved)
    }

    @Test fun aSheetWithoutAFieldDoesNotAskForTheKeyboard() {
        val a=Robolectric.buildActivity(CallTasksActivity::class.java).setup().get()
        val d=CallUi.sheetDialog(a,"속도 측정 결과",message="녹음 40초를 처리했어요.",secondary="확인")
        val mode=d.window!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
        assertNotEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,mode)
        // A quick double tap on a sheet button runs its action once.
        var closed=0
        val e=CallUi.sheetDialog(a,"확인",primary="네",onPrimary={ closed++ })
        e.find("sheet_primary").performClick();e.find("sheet_primary").performClick()
        assertEquals(1,closed);d.dismiss()
    }

    @Config(qualifiers="night")
    @Test fun theSheetFollowsNightMode() {
        val a=launchMain(Screen.TASKS)
        val d=CallUi.sheetDialog(a,"속도 측정 결과",message="녹음 40초를 처리했어요.",secondary="확인")
        assertTrue(CallUi.night)
        val frame=d.find("sheet_root").parent.parent as View
        assertEquals(android.graphics.Color.parseColor("#1A1F26"),(frame.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals(android.graphics.Color.parseColor("#F2F4F6"),(d.find("sheet_title") as TextView).currentTextColor)
        assertNull("one button only",d.window!!.decorView.findViewWithTag<View>("sheet_primary"))
        d.find("sheet_secondary").performClick();assertFalse(d.isShowing)
    }
}
