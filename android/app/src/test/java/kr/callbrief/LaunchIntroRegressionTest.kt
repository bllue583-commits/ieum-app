package kr.callbrief

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Only generated demo rows and host settings/shadows. No device, account, transport or OS settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchIntroRegressionTest : ProtectedTextHostTestBase() {
    companion object { var forceMotion: Boolean? = null }
    class HostActivity: MainActivity() {
        override fun launchIntroNow() = Instant.parse("2026-10-12T00:00:00Z")
        override fun launchIntroZone() = ZoneId.of("Asia/Seoul")
        override fun launchIntroMotionAllowed() = forceMotion ?: super.launchIntroMotionAllowed()
    }
    private lateinit var context: Context
    @Before fun syntheticHostOnly() {
        context=RuntimeEnvironment.getApplication();RuntimeEnvironment.setFontScale(1f)
        context.applicationInfo.flags=context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        LaunchIntroSession.resetForTests();forceMotion=null
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        shadowOf(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setTouchExplorationEnabled(false)
        context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun intent(launcher:Boolean=true) = Intent(context,HostActivity::class.java).putExtra("demo",true).apply {
        if(launcher) { action=Intent.ACTION_MAIN;addCategory(Intent.CATEGORY_LAUNCHER) }
    }
    private fun controller(launcher:Boolean=true,saved:Bundle?=null):ActivityController<HostActivity> =
        Robolectric.buildActivity(HostActivity::class.java,intent(launcher)).create(saved).start().resume().visible()
    private fun advance(ms:Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun root(a:MainActivity)=field(a,"root") as ViewGroup
    private fun intro(a:MainActivity)=field(a,"launchIntro") as LaunchIntro?
    private fun overlay(a:MainActivity)=a.window.decorView.findViewWithTag<FrameLayout>("launch_intro")
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val json=JSONObject().put("id",id).put("pass",true).put("scope","API35_ROBOLECTRIC_HOST_SYNTHETIC_NOT_DEVICE")
        values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/launch-intro").apply { mkdirs() };File(dir,"$id.json").writeText(json.toString(2))
    }
    private fun render(v:View,name:String,w:Int=720,h:Int=1600) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        // A second layout settles the footer's measured, font-dependent scroll clearance.
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        val dir=File("build/reports/launch-intro-screens").apply { mkdirs() }
        FileOutputStream(File(dir,"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun S01SixLocalTimeBoundariesAndMidnightUseRequiredGreetings() {
        val zone=ZoneId.of("Asia/Seoul")
        val inputs=mapOf("00:00:00" to "저녁","04:59:59" to "저녁","05:00:00" to "아침","11:59:59" to "아침","12:00:00" to "오후","17:59:59" to "오후","18:00:00" to "저녁","23:59:59" to "저녁")
        inputs.forEach { (time,word) -> assertEquals("좋은 ${word}입니다",LaunchIntroPolicy.greeting(LocalDateTime.parse("2026-10-12T$time").atZone(zone).toInstant(),zone)) }
        evidence("S01","boundary_assertions" to inputs.size,"timezone" to "explicit_device_zone")
    }
    @Test fun S02SameInstantUsesDeviceTimezoneRatherThanReportSeoulTimezone() {
        val at=Instant.parse("2026-10-12T04:30:00Z")
        assertEquals("좋은 오후입니다",LaunchIntroPolicy.greeting(at,ZoneId.of("Asia/Seoul")))
        assertEquals("좋은 저녁입니다",LaunchIntroPolicy.greeting(at,ZoneId.of("America/Los_Angeles")))
        assertEquals("좋은 아침입니다",LaunchIntroPolicy.greeting(at,ZoneId.of("Europe/London")))
        evidence("S02","same_instant_zone_variation" to true,"report_clock_reused" to false)
    }
    @Test fun S03CrossfadeAndHoldHaveOneSecondBoundedTimeline() {
        val start=LaunchIntroPolicy.frame(0);assertEquals(0f,start.greetingAlpha,0f);assertEquals(0f,start.brandAlpha,0f)
        assertEquals(1f,LaunchIntroPolicy.frame(100).greetingAlpha,0f)
        assertEquals(1f,LaunchIntroPolicy.frame(399).greetingAlpha,0f)
        val middle=LaunchIntroPolicy.frame(450);assertEquals(.5f,middle.greetingAlpha,.0001f);assertEquals(.5f,middle.brandAlpha,.0001f)
        assertEquals(0f,LaunchIntroPolicy.frame(500).greetingAlpha,0f);assertEquals(1f,LaunchIntroPolicy.frame(500).brandAlpha,0f)
        assertEquals(1f,LaunchIntroPolicy.frame(900).brandAlpha,0f);assertEquals(.5f,LaunchIntroPolicy.frame(950).brandAlpha,.0001f)
        assertEquals(LaunchIntroPolicy.Scene.COMPLETE,LaunchIntroPolicy.frame(1000).scene)
        assertEquals(LaunchIntroPolicy.Scene.COMPLETE,LaunchIntroPolicy.frame(10000).scene)
        evidence("S03","total_ms" to 1000,"crossfade_ms" to 100,"greeting_hold_ms" to 300,"brand_hold_ms" to 400)
    }
    @Test fun S04RealActivityShowsGreetingThenNameAndRemovesOverlayAtDeadline() {
        forceMotion=true;val a=controller().get();val start=SystemClock.uptimeMillis();assertTrue(intro(a)!!.isShowing)
        assertEquals("좋은 아침입니다",overlay(a).findViewWithTag<TextView>("launch_greeting").text.toString())
        advance(200);assertEquals(1f,overlay(a).findViewWithTag<View>("launch_greeting_scene").alpha,0f)
        advance(500);assertEquals(1f,overlay(a).findViewWithTag<View>("launch_brand_scene").alpha,0f)
        assertEquals("이음",overlay(a).findViewWithTag<TextView>("launch_name").text.toString())
        assertEquals("당신만의 업무 기억 에이전트",overlay(a).findViewWithTag<TextView>("launch_introduction").text.toString())
        advance(300);assertFalse(intro(a)!!.isShowing);assertNull(overlay(a));assertEquals(1000,SystemClock.uptimeMillis()-start)
        assertTrue(views(root(a)).filterIsInstance<TextView>().any { it.text.toString()=="이음" })
        evidence("S04","app_decoration_elapsed_ms" to 1000,"os_launch_time_measured" to false,"sequence" to "greeting_then_ieum")
    }
    @Test fun S05WholeScreenTapSkipsAndDoesNotClickUnderlyingApp() {
        forceMotion=true;val a=controller().get();val before=field(a,"tasks")!!.toString();advance(64)
        var appClicks=0;root(a).setOnClickListener { appClicks++ };val screen=overlay(a)
        val time=SystemClock.uptimeMillis();val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,40f,60f,0)
        val up=MotionEvent.obtain(time,time+1,MotionEvent.ACTION_UP,40f,60f,0)
        screen.dispatchTouchEvent(down);screen.dispatchTouchEvent(up);down.recycle();up.recycle()
        assertNull(overlay(a));assertEquals(0,appClicks);assertEquals(before,field(a,"tasks")!!.toString())
        advance(3000);assertNull(overlay(a))
        evidence("S05","skip_immediate" to true,"underlying_clicks" to 0,"synthetic_rows_unchanged" to true)
    }
    @Test fun S06SkipButtonIsIdempotentAndRestoresContentAccessibility() {
        forceMotion=true;val a=controller().get();assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,root(a).importantForAccessibility)
        overlay(a).findViewWithTag<Button>("launch_skip").performClick();intro(a)!!.finish();intro(a)!!.finish()
        assertNull(overlay(a));assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,root(a).importantForAccessibility)
        evidence("S06","duplicate_skip_safe" to true,"content_accessibility_restored" to true)
    }
    @Test fun S07BackSkipsFirstAndPreservesNormalBackAfterward() {
        forceMotion=true;val a=controller().get();a.onBackPressedDispatcher.onBackPressed();assertFalse(a.isFinishing);assertNull(overlay(a))
        a.onBackPressedDispatcher.onBackPressed();assertTrue(a.isFinishing)
        evidence("S07","first_back_skips" to true,"next_back_normal" to true)
    }
    @Test fun S08HomeStopAndReturnDoNotReplayOrKeepTimers() {
        forceMotion=true;val c=controller();val a=c.get();advance(200);c.pause().stop();assertNull(overlay(a))
        advance(2000);c.start().resume();assertNull(overlay(a));assertFalse(intro(a)!!.isShowing)
        evidence("S08","stop_cancels" to true,"return_replays" to 0)
    }
    @Test fun S09SavedRecreationEntersHomeWithoutRestartingIntro() {
        forceMotion=true;val c=controller();val a=c.get();advance(160);val saved=Bundle();c.saveInstanceState(saved).pause().stop().destroy()
        val recreated=controller(saved=saved).get();assertNull(overlay(recreated));advance(100);assertNull(overlay(recreated))
        assertEquals(field(a,"selectedCallDate"),field(recreated,"selectedCallDate"))
        evidence("S09","recreation_delay_ms" to 0,"selected_date_preserved" to true)
    }
    @Test fun S10NewLauncherActivityInSameProcessDoesNotReplay() {
        forceMotion=true;val c=controller();c.get().onBackPressedDispatcher.onBackPressed();c.pause().stop().destroy()
        val second=controller().get();assertNull(overlay(second));assertNull(intro(second))
        evidence("S10","same_process_replays" to 0)
    }
    @Test fun S11DisabledSystemMotionBypassesDecorationWithoutChangingSetting() {
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        assertFalse(LaunchIntroPolicy.motionAllowed(context));val a=controller().get();assertNull(overlay(a))
        assertEquals(0f,Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE),0f)
        evidence("S11","motion_disabled_delay_ms" to 0,"production_setting_write" to false)
    }
    @Test fun S12TouchExplorationBypassesTimedText() {
        shadowOf(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setTouchExplorationEnabled(true)
        assertFalse(LaunchIntroPolicy.motionAllowed(context));val a=controller().get();assertNull(overlay(a))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,root(a).importantForAccessibility)
        evidence("S12","touch_exploration_delay_ms" to 0,"device_talkback_executed" to false)
    }
    @Test fun S13MotionDisabledDuringIntroStopsOnNextFrame() {
        forceMotion=true;val a=controller().get();advance(200);forceMotion=false;advance(16);assertNull(overlay(a))
        evidence("S13","setting_change_next_frame_stop" to true)
    }
    @Test fun S14DeepLinkEntryAndNewIntentNeverAddDelay() {
        forceMotion=true;val c=controller(launcher=false);assertNull(overlay(c.get()))
        LaunchIntroSession.resetForTests();val a=controller().get();assertNotNull(overlay(a))
        val link=Intent(Intent.ACTION_VIEW,Uri.parse("callbrief://digest?date=2026-10-10"))
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(a,link)
        assertNull(overlay(a));assertEquals("2026-10-10",field(a,"selectedCallDate"))
        evidence("S14","deep_link_delay_ms" to 0,"existing_date_route_preserved" to true)
    }
    @Test fun S15DeadActivityReceivesNoLateViewMutation() {
        forceMotion=true;val c=controller();val a=c.get();val screen=overlay(a);var mutations=0
        (root(a).parent as ViewGroup).setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent:View?,child:View?) { mutations++ }
            override fun onChildViewRemoved(parent:View?,child:View?) { mutations++ }
        })
        c.destroy();assertTrue(a.isDestroyed);val alpha=screen.findViewWithTag<View>("launch_greeting_scene").alpha
        advance(3000);assertEquals(0,mutations);assertEquals(alpha,screen.findViewWithTag<View>("launch_greeting_scene").alpha,0f)
        evidence("S15","late_host_mutations" to 0,"late_alpha_changes" to 0)
    }
    @Test fun S16NormalFontGreetingBrandAndHomeHostRenders() {
        forceMotion=true;val a=controller().get();advance(200);render(overlay(a),"greeting-font100-host")
        advance(500);render(overlay(a),"ieum-font100-host");val skip=overlay(a).findViewWithTag<Button>("launch_skip")
        assertTrue(skip.height>=56*a.resources.displayMetrics.density);assertTrue(skip.width>=56*a.resources.displayMetrics.density)
        advance(300);render(root(a),"home-font100-host")
        evidence("S16","font_scale" to 1,"width_px" to 720,"height_px" to 1600,"renders" to 3)
    }
    @Test fun S17Font200KeepsAllTextAndSkipVisibleWithoutShrinking() {
        RuntimeEnvironment.setFontScale(2f);forceMotion=true;val a=controller().get();advance(200);render(overlay(a),"greeting-font200-host")
        advance(500);render(overlay(a),"ieum-font200-host");val screen=overlay(a);val skip=screen.findViewWithTag<Button>("launch_skip")
        val brand=screen.findViewWithTag<LinearLayout>("launch_brand_scene");val scroll=screen.findViewWithTag<View>("launch_scroll")
        assertTrue(brand.bottom<=scroll.height);assertTrue(skip.bottom<=skip.parent.let { it as View }.height)
        val introduction=screen.findViewWithTag<TextView>("launch_introduction");val name=screen.findViewWithTag<TextView>("launch_name")
        val hint=screen.findViewWithTag<TextView>("launch_skip_hint");assertEquals(2,hint.layout.lineCount)
        assertEquals(hint.text.length,hint.layout.getLineEnd(hint.layout.lineCount-1))
        assertNull(introduction.ellipsize);assertEquals(LaunchIntroPolicy.INTRODUCTION,introduction.text.toString());assertEquals("이음",name.text.toString())
        assertTrue(introduction.layout.lineCount>1);assertEquals(introduction.text.length,introduction.layout.getLineEnd(introduction.layout.lineCount-1))
        val sp22=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,22f,a.resources.displayMetrics)
        assertEquals(sp22,introduction.textSize,.5f);assertTrue(skip.height>=56*a.resources.displayMetrics.density)
        advance(300);render(root(a),"home-font200-host")
        evidence("S17","font_scale" to 2,"font_shrunk" to false,"introduction_lines" to introduction.layout.lineCount,"skip_visible" to true,"renders" to 3)
    }
    @Test fun S18DecorationHasNoProgressOrNetworkSuccessAndDoesNotWriteAppPreferences() {
        forceMotion=true;val before=context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).all.toString()
        val a=controller().get();assertFalse(NativeAnalysisAvailability.canTransfer());assertTrue(views(overlay(a)).none { it is ProgressBar })
        assertFalse(views(overlay(a)).filterIsInstance<TextView>().any { it.text.contains("분석") || it.text.contains("완료") || it.text.contains("%") })
        advance(1000);assertEquals(before,context.getSharedPreferences("local_ui_v2",Context.MODE_PRIVATE).all.toString())
        evidence("S18","progress_widgets" to 0,"native_transfer" to false,"intro_preference_writes" to 0)
    }
    @Test fun S19RestoreInFreshProcessAlsoBypassesIntro() {
        LaunchIntroSession.resetForTests();forceMotion=true;val restored=controller(saved=Bundle()).get();assertNull(overlay(restored));assertNull(intro(restored))
        evidence("S19","saved_state_with_fresh_session_delay_ms" to 0)
    }
    @Test fun S20ScrollGestureDoesNotSkipButSubsequentTapDoes() {
        forceMotion=true;val a=controller().get();val screen=overlay(a);render(screen,"gesture-before-host")
        val time=SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to 800f,MotionEvent.ACTION_MOVE to 500f,MotionEvent.ACTION_UP to 400f).forEach { (action,y) ->
            MotionEvent.obtain(time,time+1,action,300f,y,0).also { screen.dispatchTouchEvent(it);it.recycle() }
        }
        assertNotNull(overlay(a));screen.performClick();assertNull(overlay(a))
        evidence("S20","drag_accidental_skip" to false,"tap_after_drag_skips" to true)
    }
    @Test fun S21NativeHostFrameSamplesMatchOneSecondPreviewAndPreserveFontScales() {
        val samples=listOf(0L,50L,100L,200L,300L,400L,450L,500L,600L,700L,800L,900L,950L,1000L)
        val frames=org.json.JSONArray()
        for (font in listOf(1f,2f)) {
            RuntimeEnvironment.setFontScale(font);LaunchIntroSession.resetForTests();forceMotion=true
            // Native bitmap capture itself advances the host graphics clock. Freeze the component's
            // existing injected monotonic clock for each frame; S04 independently tests its deadline.
            val c=controller(launcher=false);val a=c.get();var sampledMs=0L
            val preview=LaunchIntro(a,root(a).parent as FrameLayout,root(a),
                Instant.parse("2026-10-12T00:00:00Z"),ZoneId.of("Asia/Seoul"),
                { !a.isDestroyed },{ true },{ sampledMs })
            preview.start()
            for (elapsed in samples) {
                sampledMs=elapsed;advance(16)
                val name="frames-font${(font*100).toInt()}-${elapsed.toString().padStart(4,'0')}"
                render(if(elapsed<1000L) overlay(a) else root(a),name)
                frames.put(JSONObject().put("font_scale",font).put("elapsed_ms",elapsed).put("file","$name.png")
                    .put("overlay_present",overlay(a)!=null))
            }
            assertNull(overlay(a));c.pause().stop().destroy()
        }
        val dir=File("build/reports/launch-intro").apply { mkdirs() }
        File(dir,"preview-frames.json").writeText(JSONObject().put("app_decoration_ms",1000).put("capture_clock","injected_frozen_monotonic_per_frame").put("scope","sampled_native_host_graphics_not_avd").put("frames",frames).toString(2))
        evidence("S21","font_scales" to "100%,200%","samples_per_scale" to samples.size,"decoration_ms" to 1000,"native_device_preview" to false)
    }
}
