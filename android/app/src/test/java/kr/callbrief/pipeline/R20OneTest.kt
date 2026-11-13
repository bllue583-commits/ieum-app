package kr.callbrief.pipeline

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowToast

/** v4.0 r20-1 (DESIGN_V7 6): the tools for whoever looks after the app folded into "관리" at the bottom of settings, the
 * per-call switch's new words, and the call card as a required first-setup step on a new install only. Made-up data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R20OneTest:ProtectedTextHostTestBase() {
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun tagged(a:android.app.Activity,prefix:String)=views(a.window.decorView).filter { (it.tag as? String)?.startsWith(prefix)==true }
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private fun ui()=app.getSharedPreferences("ieum_ui",Context.MODE_PRIVATE)
    @After fun reset() { SettingsScreen.goldenHook=null;IeumApplication.shutdown();CallUi.applyTheme(app) }
    /** [v] sits inside [outer] (or is it). */
    private fun inside(v:View,outer:View):Boolean { var p:View?=v;while(p!=null) { if(p===outer) return true;p=p.parent as? View };return false }

    @Test fun theAdminToolsAreFoldedAtTheBottomAndWorkOnceOpened() {
        SettingsScreen.goldenHook={ true }
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,SettingsScreen.ADMIN_TOGGLE)!=null }
        // 관리 is the last group, shut.
        assertEquals("group_${SettingsScreen.ADMIN_NAME}",tagged(a,"group_").last().tag)
        val inner=tag(a,SettingsScreen.ADMIN_BODY)
        assertEquals(View.GONE,inner.visibility)
        val toggle=tag(a,SettingsScreen.ADMIN_TOGGLE) as TextView
        assertEquals(SettingsScreen.ADMIN_OPEN,toggle.text.toString())
        // What was in 상태와 도움 is in it, unchanged; 문제 알리기 and 현장 메모 stay where they were.
        listOf("handled_calls","rebuild_cards","golden_link").forEach { assertTrue(it,inside(tag(a,it),inner)) }
        listOf("report_problem","site_memo_toggle","app_version").forEach { assertFalse(it,inside(tag(a,it),inner)) }
        assertFalse(tag(a,"rebuild_cards").isShown)
        // Opened: shown, and the buttons do what they did.
        toggle.performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE,inner.visibility);assertEquals(SettingsScreen.ADMIN_CLOSE,toggle.text.toString())
        tag(a,"rebuild_cards").performClick()
        settle { ShadowToast.getTextOfLatestToast()?.toString()?.contains("상대 카드")==true }
        // Drawn again (a save), it stays open for this visit.
        (tag(a,"followup_switch") as Switch).performClick()
        settle { SecureSettings(app).snapshot().has(PipelineSettingsKeys.FOLLOWUP_NOTICE) }
        assertEquals(View.VISIBLE,tag(a,SettingsScreen.ADMIN_BODY).visibility)
        // Shut again (a moment later: a quick second tap is ignored on purpose).
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val again=tag(a,SettingsScreen.ADMIN_TOGGLE) as TextView
        again.performClick();assertEquals(View.GONE,tag(a,SettingsScreen.ADMIN_BODY).visibility)
        assertEquals(SettingsScreen.ADMIN_OPEN,again.text.toString())
    }

    @Test fun thePerCallSwitchSaysItIsOnlyForCallsWithoutARecording() {
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"followup_switch")!=null }
        assertEquals(SettingsScreen.FOLLOWUP_SWITCH,(tag(a,"followup_switch") as Switch).text.toString())
        assertEquals(SettingsScreen.FOLLOWUP_NOTE,(tag(a,"followup_note") as TextView).text.toString())
        assertNull(views(a.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.toString()=="통화마다 알림" })
    }

    @Test fun theCallCardIsRequiredOnANewInstallOnly() {
        // A new install: no folder, no consent when v4.0 first asks. Decided once and kept, also after the setup moves on.
        ui().edit().clear().commit()
        assertTrue(CallCardAsk.cardRequired(app,JSONObject()))
        assertTrue(CallCardAsk.cardRequired(app,JSONObject().put("treeUri","content://x").put(PipelineSettingsKeys.CONSENT_GRANTED,true)))
        // A phone set up before v4.0 (the wife's): optional as it was, whatever comes later.
        ui().edit().clear().commit()
        assertFalse(CallCardAsk.cardRequired(app,JSONObject().put("treeUri","content://x").put(PipelineSettingsKeys.CONSENT_GRANTED,true)))
        assertFalse(CallCardAsk.cardRequired(app,JSONObject()))
        // Home counts it with the required steps only when it is required.
        val s=HomeScreen.Snapshot(true,true,"READY",0,0,0,0,0,0,overlay=false)
        assertEquals(0,HomeScreen.requiredLeft(s));assertNull(HomeScreen.attention(s))
        assertEquals(1,HomeScreen.requiredLeft(s.copy(cardRequired=true)))
        assertNotNull(HomeScreen.attention(s.copy(cardRequired=true)))
        assertEquals(0,HomeScreen.requiredLeft(s.copy(cardRequired=true,overlay=true)))
    }

    @Test fun onANewInstallTheCardStepHasNoLaterButton() {
        ui().edit().clear().putBoolean(CallCardAsk.CARD_REQUIRED,true).putBoolean("setup_later_sms",true).commit()
        // Every other required step done: the card is the next one, and it cannot be put off.
        SecureSettings(app).update { it.put("treeUri","content://generated/tree").put(PipelineSettingsKeys.CONSENT_GRANTED,true) }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.READ_CALL_LOG,android.Manifest.permission.READ_PHONE_STATE,android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName,true)
        val a=launchMain(Screen.SETTINGS)
        settle { tag(a,"setup_next_label")!=null }
        assertEquals("다음 할 일: 통화 화면 위에 이음 카드 보기",(tag(a,"setup_next_label") as TextView).text.toString())
        assertNull(tag(a,"setup_later"));assertNull(tag(a,"setup_optional"))
        // The same phone set up before v4.0: optional, with [나중에 할게요].
        ui().edit().putBoolean(CallCardAsk.CARD_REQUIRED,false).commit()
        val b=launchMain(Screen.SETTINGS)
        settle { tag(b,"setup_next_label")!=null }
        assertEquals("다음 할 일: 통화 화면 위에 이음 카드 보기",(tag(b,"setup_next_label") as TextView).text.toString())
        assertNotNull(tag(b,"setup_later"))
    }
}
