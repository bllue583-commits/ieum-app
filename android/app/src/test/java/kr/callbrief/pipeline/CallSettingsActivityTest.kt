package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
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

/** Generated key text only; the settings key comes from the public test shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallSettingsActivityTest:ProtectedTextHostTestBase() {
    private val secret="sk-ant-SYNTHETIC-TEST-KEY-123456"
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun until(a:android.app.Activity,check:()->Boolean) {
        repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle")
    }
    private fun root(a:android.app.Activity)=a.window.decorView

    @Test fun keyIsNeverShownAfterSavingAndScreenIsProtected() {
        val a=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        until(a) { root(a).findViewWithTag<EditText>("api_key_input")!=null }
        root(a).findViewWithTag<EditText>("api_key_input").setText(secret)
        root(a).findViewWithTag<Button>("api_key_save").performClick()
        until(a) { root(a).findViewWithTag<Button>("api_key_delete")!=null }
        assertEquals(secret,SecureSettings(CallPipelineStartup.storage(a)).snapshot().getString(PipelineSettingsKeys.CLAUDE_API_KEY))
        val texts=views(root(a)).filterIsInstance<TextView>().map { it.text.toString()+"|"+(it.hint ?: "") }
        assertTrue(texts.none { it.contains(secret) || it.contains("SYNTHETIC-TEST-KEY") })
        assertTrue(texts.any { it.startsWith("저장됨") })
        // Recreated screen still shows only the saved state.
        val again=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        until(again) { root(again).findViewWithTag<Button>("api_key_delete")!=null }
        assertTrue(views(root(again)).filterIsInstance<TextView>().none { it.text.toString().contains(secret) })
        root(again).findViewWithTag<Button>("api_key_delete").performClick()
        until(again) { root(again).findViewWithTag<EditText>("api_key_input")!=null }
        assertFalse(SecureSettings(CallPipelineStartup.storage(again)).snapshot().has(PipelineSettingsKeys.CLAUDE_API_KEY))
    }

    @Test fun todayCallsOpensEmptyAndProtected() {
        val a=Robolectric.buildActivity(TodayCallsActivity::class.java).setup().get()
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        until(a) { views(root(a)).filterIsInstance<TextView>().any { it.text.toString()=="오늘 할 일" } }
        assertTrue(views(root(a)).filterIsInstance<TextView>().any { it.text.toString().contains("정리된 통화가 없어요") })
    }
}
