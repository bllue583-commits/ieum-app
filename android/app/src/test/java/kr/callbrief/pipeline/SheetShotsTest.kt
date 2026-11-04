package kr.callbrief.pipeline

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowDialog

/** Preview pictures of the bottom sheets, for a person to look at (not a check): runs only when IEUM_SHOTS_DIR is set,
 * e.g. IEUM_SHOTS_DIR=/tmp/shots, and writes one PNG per sheet there (the screen behind, dimmed, the sheet at the bottom). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xxhdpi",shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetShotsTest:ProtectedTextHostTestBase() {
    private val out=System.getenv("IEUM_SHOTS_DIR")
    private val app get()=RuntimeEnvironment.getApplication()
    private fun idle() { repeat(30) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) } }

    private fun shot(a:Activity,d:Dialog,name:String) {
        idle()
        val dm=a.resources.displayMetrics;val w=dm.widthPixels;val h=dm.heightPixels
        val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(bmp)
        val page=a.window.decorView
        page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));page.layout(0,0,w,h);page.draw(c)
        c.drawColor(android.graphics.Color.argb((d.window!!.attributes.dimAmount*255).toInt(),0,0,0))
        val sheet=d.window!!.decorView
        sheet.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST))
        sheet.layout(0,0,w,sheet.measuredHeight)
        c.save();c.translate(0f,(h-sheet.measuredHeight).toFloat());sheet.draw(c);c.restore()
        java.io.File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun sheets() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        PreviewSeed.ensure(app)
        val tasks=launchMain(Screen.TASKS);idle()
        tasks.window.decorView.findViewWithTag<View>("task_add").performClick()
        shot(tasks,ShadowDialog.getLatestDialog(),"1_task_add")
        ShadowDialog.getLatestDialog().dismiss()

        val companies=launchMain(Screen.COMPANIES);idle()
        val input=CallUi.sheetInput(companies,"김철주",tag="person_name_input")
        shot(companies,CallUi.sheetDialog(companies,"이름 고치기",message="바른 이름을 적고 [저장]을 누르세요.",body=input,primary="저장",onPrimary={},secondary="취소"),"2_rename")

        val settings=launchMain(Screen.SETTINGS);idle()
        shot(settings,CallUi.sheetDialog(settings,"눌러서 업무 번호로 되돌려요",options=listOf("010-1234-5678  11월 2일","010-9876-5432  10월 30일"),onOption={},secondary="닫기"),"3_personal_list")
        shot(settings,CallUi.sheetDialog(settings,"업무 번호로 되돌리기",message="010-1234-5678 번호를 업무 번호로 되돌릴까요? 앞으로 이 번호의 통화와 문자를 다시 정리해요.",
            primary="되돌리기",onPrimary={},secondary="닫기"),"4_personal_confirm")
    }

    @Config(qualifiers="w393dp-h852dp-night-xxhdpi")
    @Test fun sheetAtNight() {
        assumeTrue("set IEUM_SHOTS_DIR to take the pictures",!out.isNullOrBlank())
        PreviewSeed.ensure(app)
        val settings=launchMain(Screen.SETTINGS);idle()
        val field=CallUi.sheetInput(settings,"",hint="예: 김민지",tag="my_input")
        shot(settings,CallUi.sheetDialog(settings,"이름",body=field,primary="저장",onPrimary={},secondary="닫기"),"5_my_name_night")
    }
}
