package kr.callbrief

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos

/** Presentation only. No app data, credentials, networking, loading state or stored preferences. */
internal object LaunchIntroPolicy {
    const val APP_NAME = "이음"
    const val INTRODUCTION = "당신만의 업무 기억 에이전트"
    const val FADE_MS = 100L
    const val CROSSFADE_START_MS = 400L
    const val BRAND_HOLD_END_MS = 900L
    const val TOTAL_MS = 1000L
    enum class Scene { GREETING, BRAND, COMPLETE }
    data class Frame(val scene: Scene, val greetingAlpha: Float, val brandAlpha: Float)
    fun greeting(at: Instant, deviceZone: ZoneId): String = when (at.atZone(deviceZone).hour) {
        in 5..11 -> "좋은 아침입니다"
        in 12..17 -> "좋은 오후입니다"
        else -> "좋은 저녁입니다"
    }
    fun frame(elapsedMs: Long): Frame {
        if (elapsedMs >= TOTAL_MS) return Frame(Scene.COMPLETE, 0f, 0f)
        val elapsed = elapsedMs.coerceAtLeast(0L)
        val greeting = when {
            elapsed < FADE_MS -> elapsed.toFloat() / FADE_MS
            elapsed < CROSSFADE_START_MS -> 1f
            else -> 1f - (elapsed - CROSSFADE_START_MS).toFloat() / FADE_MS
        }
        val brand = when {
            elapsed < CROSSFADE_START_MS -> 0f
            elapsed < CROSSFADE_START_MS + FADE_MS -> (elapsed - CROSSFADE_START_MS).toFloat() / FADE_MS
            elapsed <= BRAND_HOLD_END_MS -> 1f
            else -> (TOTAL_MS - elapsed).toFloat() / FADE_MS
        }
        fun smooth(value: Float) = ((1.0 - cos(Math.PI * value.coerceIn(0f, 1f))) / 2.0).toFloat()
        return Frame(if (elapsed < CROSSFADE_START_MS) Scene.GREETING else Scene.BRAND, smooth(greeting), smooth(brand))
    }
    fun motionAllowed(context: Context): Boolean {
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        // Timed text must not interrupt touch exploration. Read global motion state; never change it.
        return ValueAnimator.areAnimatorsEnabled() &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            !accessibility.isTouchExplorationEnabled
    }
}

/** Once per process; saved activity recreation and deep-link entry also consume the opportunity. */
internal object LaunchIntroSession {
    private var consumed = false
    @Synchronized fun claim(launcher: Boolean, restored: Boolean): Boolean {
        val show = !consumed && launcher && !restored
        consumed = true
        return show
    }
    @Synchronized internal fun resetForTests() { consumed = false }
}

internal class LaunchIntro(
    private val context: Context,
    private val host: FrameLayout,
    private val appContent: View,
    at: Instant,
    deviceZone: ZoneId,
    private val available: () -> Boolean,
    private val motionAllowed: () -> Boolean,
    private val monotonicNow: () -> Long = SystemClock::uptimeMillis
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ink = Color.rgb(13, 34, 112)
    private val sky = Color.rgb(221, 241, 248)
    private val oldAccessibility = appContent.importantForAccessibility
    private val greeting = LaunchIntroPolicy.greeting(at, deviceZone)
    private var begunAt = 0L
    private var closed = false
    private val scene = FrameLayout(context).apply { tag = "launch_scene" }
    private fun sceneColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(16), dp(20), dp(16), dp(20)); alpha = 0f
    }
    private val greetingScene = sceneColumn().apply { tag = "launch_greeting_scene" }
    private val brandScene = sceneColumn().apply { tag = "launch_brand_scene" }
    val overlay: FrameLayout = object : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
            if (event.actionMasked == MotionEvent.ACTION_UP &&
                abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                performClick(); return true
            }
            super.dispatchTouchEvent(event)
            return true // Never send a launch-screen gesture to the saved-record screen underneath.
        }
        override fun performClick(): Boolean { super.performClick(); finish(); return true }
    }.apply {
        tag = "launch_intro"; isClickable = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sky, Color.WHITE))
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(24), insets.systemWindowInsetTop + dp(24), dp(24), insets.systemWindowInsetBottom + dp(24))
            insets
        }
    }
    private val scroll = ScrollView(context).apply {
        tag = "launch_scroll"; isFillViewport = true; isVerticalScrollBarEnabled = false
        addView(scene, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val skip = Button(context).apply {
        tag = "launch_skip"; text = "건너뛰기"; contentDescription = "시작 화면 건너뛰기"
        textSize = 18f; isAllCaps = false; minHeight = dp(56); setTextColor(ink)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(28).toFloat(); setStroke(dp(1), Color.rgb(188, 216, 230))
        }
        setOnClickListener { finish() }
    }
    private val footer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val hint = if (context.resources.configuration.fontScale >= 1.25f) "화면을 누르면\n건너뛰어요" else "화면을 누르면 건너뛰어요"
        addView(label(hint, 18f, false).apply { tag = "launch_skip_hint" })
        addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (!available()) { dispose(); return }
            if (!motionAllowed()) { finish(); return }
            val frame = LaunchIntroPolicy.frame(monotonicNow() - begunAt)
            if (frame.scene == LaunchIntroPolicy.Scene.COMPLETE) { finish(); return }
            show(frame)
            handler.postDelayed(this, minOf(16L, LaunchIntroPolicy.TOTAL_MS - (monotonicNow() - begunAt)).coerceAtLeast(1L))
        }
    }
    val isShowing: Boolean get() = !closed && overlay.parent === host
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String, size: Float, bold: Boolean) = TextView(context).apply {
        text = value; textSize = size; gravity = Gravity.CENTER; setTextColor(ink)
        setPadding(0, dp(8), 0, dp(8))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        // No ellipsis, fixed line count or font reduction when the user enlarges text.
    }
    init {
        greetingScene.addView(label(greeting, 32f, true).apply { tag = "launch_greeting" })
        brandScene.addView(label(LaunchIntroPolicy.INTRODUCTION, 22f, false).apply { tag = "launch_introduction" })
        brandScene.addView(label(LaunchIntroPolicy.APP_NAME, 40f, true).apply {
            tag = "launch_name"; setPadding(0, dp(16), 0, dp(16))
        })
        scene.addView(greetingScene, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        scene.addView(brandScene, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        overlay.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(132) })
        overlay.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        footer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = scroll.layoutParams as FrameLayout.LayoutParams
            val required = footer.height + dp(20)
            if (params.bottomMargin != required) { params.bottomMargin = required; scroll.layoutParams = params }
        }
    }
    fun start() {
        if (closed || isShowing) return
        if (!available() || !motionAllowed()) { closed = true; return }
        appContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        host.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.requestApplyInsets()
        begunAt = monotonicNow()
        tick.run()
    }
    private fun show(frame: LaunchIntroPolicy.Frame) {
        greetingScene.alpha = frame.greetingAlpha
        brandScene.alpha = frame.brandAlpha
        greetingScene.importantForAccessibility = if (frame.scene == LaunchIntroPolicy.Scene.GREETING) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        brandScene.importantForAccessibility = if (frame.scene == LaunchIntroPolicy.Scene.BRAND) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    fun finish() {
        if (closed) return
        closed = true; handler.removeCallbacks(tick)
        if (available()) {
            host.removeView(overlay)
            appContent.importantForAccessibility = oldAccessibility
        }
    }
    /** Dead activities only release callbacks. Their window owns any remaining view teardown. */
    fun dispose() { closed = true; handler.removeCallbacks(tick) }
}
