package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

/** Settings and today's calls screens on an empty store; the settings key comes from the public test shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallSettingsActivityTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun until(a:android.app.Activity,check:()->Boolean) {
        repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle")
    }
    private fun root(a:android.app.Activity)=a.window.decorView

    @Test fun settingsHaveNoApiKeyInputAnyMore() {
        val a=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        until(a) { views(root(a)).filterIsInstance<TextView>().any { it.text.toString()=="정리 동의" } }
        assertNull(root(a).findViewWithTag<View>("api_key_input"));assertNull(root(a).findViewWithTag<View>("api_key_save"))
        assertTrue(views(root(a)).filterIsInstance<TextView>().none { it.text.toString().contains("키 저장") || it.text.toString().contains("platform.claude.com") })
    }

    @Test fun todayCallsOpensEmpty() {
        val a=Robolectric.buildActivity(TodayCallsActivity::class.java).setup().get()
        until(a) { views(root(a)).filterIsInstance<TextView>().any { it.text.toString().endsWith("의 통화") } }
        assertTrue(views(root(a)).filterIsInstance<TextView>().any { it.text.toString().contains("정리된 통화가 없어요") })
    }
}
