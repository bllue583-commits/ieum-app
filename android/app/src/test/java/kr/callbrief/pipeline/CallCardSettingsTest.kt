package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v3.0 r11-1 (design 7.3): the settings group "통화 카드" (its permission line, three switches with their defaults, the card's
 * place back to the start) and the setup card's two steps while the overlay is not allowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallCardSettingsTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun <T:View> tag(a:android.app.Activity,t:String):T? =a.window.decorView.findViewWithTag(t)

    @Test fun settingsGroupHasThreeSwitchesWithTheirDefaultsAndSavesThem() {
        val a=launchMain(Screen.SETTINGS)
        settle { tag<View>(a,"card_incoming_switch")!=null }
        val incoming=tag<Switch>(a,"card_incoming_switch")!!;val outgoing=tag<Switch>(a,"card_outgoing_switch")!!;val unknown=tag<Switch>(a,"card_unknown_switch")!!
        assertEquals("전화 올 때",incoming.text.toString());assertTrue(incoming.isChecked)
        assertEquals("전화 걸 때",outgoing.text.toString());assertTrue(outgoing.isChecked)
        assertEquals("전화부에 없는 번호도",unknown.text.toString());assertFalse(unknown.isChecked)
        // Not allowed yet on the host: the line says what it does and the button opens the overlay setting.
        assertTrue((tag<TextView>(a,"card_overlay_state")!!).text.contains("통화 화면 위에"))
        assertNotNull(tag<View>(a,"card_overlay_allow"))
        unknown.isChecked=true;incoming.isChecked=false
        settle { SecureSettings(app).snapshot().let { it.optBoolean(PipelineSettingsKeys.CARD_UNKNOWN,false) && !it.optBoolean(PipelineSettingsKeys.CARD_INCOMING,true) } }
    }
    @Test fun cardPlaceBackToTheStartForgetsTheDraggedPlace() {
        SecureSettings(app).update { it.put(PipelineSettingsKeys.CARD_POS_Y,0.7) }
        val a=launchMain(Screen.SETTINGS)
        settle { tag<View>(a,"card_position_reset")!=null }
        assertEquals("카드 위치 처음으로",tag<TextView>(a,"card_position_reset")!!.text.toString())
        tag<View>(a,"card_position_reset")!!.performClick()
        settle { !SecureSettings(app).snapshot().has(PipelineSettingsKeys.CARD_POS_Y) }
    }
    @Test fun overlayStepOpensTheOverlaySetting() {
        // v3.1 r12-6: first setup is in settings' first group; every required step done, the overlay step after the texts one.
        SecureSettings(app).update { it.put("treeUri","content://host/tree/call").put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.MODEL_STATE,"READY") }
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALL_LOG,android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.POST_NOTIFICATIONS,android.Manifest.permission.READ_SMS)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName,true)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        HomeScreen.setupStepHook={ false }
        try {
            val a=launchMain(Screen.SETTINGS)
            settle { tag<View>(a,SettingsScreen.SETUP_TAG)!=null }
            val all=views(tag<View>(a,SettingsScreen.SETUP_TAG)!!).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(all,all.contains("통화 화면 위에 이음 카드 보기") && all.contains("처음 설정 남은 1단계"))
            tag<View>(a,"setup_next")!!.performClick()
            assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,shadowOf(a).nextStartedActivity?.action)
            // The overlay allowed: v3.1 (DESIGN_V4 2.5-1) asks for the call redirection role in settings only, not in first setup.
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
            val b=launchMain(Screen.SETTINGS)
            settle { tag<View>(b,"now_none")!=null }
            assertNull(tag<View>(b,SettingsScreen.SETUP_TAG))
        } finally { HomeScreen.setupStepHook=null }
    }
}
