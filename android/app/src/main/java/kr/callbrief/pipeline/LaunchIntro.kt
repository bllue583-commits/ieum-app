package kr.callbrief.pipeline

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Launch intro, 1.2 seconds over the home screen: "당신의 통화 에이전트" over "이음" rises in, holds, and the whole sheet fades away.
 * A tap skips it. Shown only once after an install or an update ([LAST_INTRO_VERSION] holds the versionCode it was last shown for),
 * only from the launcher, and never when the system turns animations off or a screen reader is exploring by touch.
 * Presentation only: no data is read. */
internal object LaunchIntro {
    /** Preference key (in "ieum_ui") of the versionCode the intro was last shown for. */
    const val LAST_INTRO_VERSION="LAST_INTRO_VERSION"
    private var consumed=false
    private fun prefs(c:android.content.Context)=c.applicationContext.getSharedPreferences("ieum_ui",android.content.Context.MODE_PRIVATE)
    /** True once per process at most, and only when this versionCode has not had its intro yet; claiming it records the version. */
    @Synchronized fun claim(c:android.content.Context,launcher:Boolean,restored:Boolean,versionCode:Int=kr.callbrief.BuildConfig.VERSION_CODE):Boolean {
        val first=runCatching { prefs(c).getInt(LAST_INTRO_VERSION,0)!=versionCode }.getOrDefault(true)
        val show=!consumed&&launcher&&!restored&&first;consumed=true
        if(show) runCatching { prefs(c).edit().putInt(LAST_INTRO_VERSION,versionCode).apply() }
        return show
    }
    /** Test hook: the next [claim] counts as the first in this process. */
    internal fun resetForTest() { consumed=false }

    private fun motionAllowed(a:Activity)=ValueAnimator.areAnimatorsEnabled()&&
        a.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled!=true

    fun show(a:Activity) {
        if(!motionAllowed(a))return
        val root=a.window.decorView as ViewGroup
        fun column()=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;alpha=0f }
        val brand=column().apply {
            addView(CallUi.text(a,"당신의 통화 에이전트",19f,color=CallUi.SUBTLE).apply { gravity=Gravity.CENTER;tag="launch_introduction" })
            addView(CallUi.text(a,"이음",60f,true,CallUi.INK).apply { gravity=Gravity.CENTER;letterSpacing=-0.03f;tag="launch_name";setPadding(0,CallUi.dp(a,4),0,0) })
            addView(android.view.View(a).apply { background=android.graphics.drawable.GradientDrawable().apply { setColor(CallUi.ACCENT);cornerRadius=CallUi.dp(a,2).toFloat() } },
                LinearLayout.LayoutParams(CallUi.dp(a,28),CallUi.dp(a,4)).apply { topMargin=CallUi.dp(a,14) })
            translationY=CallUi.dp(a,16).toFloat()
        }
        val overlay=FrameLayout(a).apply {
            tag="launch_intro";setBackgroundColor(CallUi.PAGE);isClickable=true;contentDescription="이음 시작 화면, 누르면 넘어가요"
            addView(brand,FrameLayout.LayoutParams(-1,-1))
        }
        val content=root.getChildAt(0)
        content?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        var done=false
        fun close() {
            if(done)return;done=true
            overlay.animate().cancel()
            overlay.animate().alpha(0f).setDuration(300).withEndAction {
                root.removeView(overlay);content?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }.start()
        }
        overlay.setOnClickListener { close() }
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        // 300 ms in, 600 ms held, 300 ms out: 1.2 seconds.
        brand.animate().alpha(1f).translationY(0f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction { overlay.postDelayed({ close() },600) }.start()
    }
}
