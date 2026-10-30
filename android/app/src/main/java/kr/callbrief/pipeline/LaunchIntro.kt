package kr.callbrief.pipeline

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Launch intro, about two seconds over the home screen: the greeting fades in and out, then
 * "당신의 통화 에이전트" over "이음" rises in, holds, and the whole sheet fades away. A tap skips it.
 * Shown once per process, only from the launcher, and never when the system turns animations off
 * or a screen reader is exploring by touch. Presentation only: no data is read. */
internal object LaunchIntro {
    private var consumed=false
    @Synchronized fun claim(launcher:Boolean,restored:Boolean):Boolean { val show=!consumed&&launcher&&!restored;consumed=true;return show }

    private fun motionAllowed(a:Activity)=ValueAnimator.areAnimatorsEnabled()&&
        a.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled!=true

    fun show(a:Activity) {
        if(!motionAllowed(a))return
        val root=a.window.decorView as ViewGroup
        fun column()=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;alpha=0f }
        val greeting=column().apply { addView(CallUi.text(a,CallUi.greeting(),26f,true,Color.WHITE).apply { gravity=Gravity.CENTER }) }
        val brand=column().apply {
            addView(CallUi.text(a,"당신의 통화 에이전트",20f,color=Color.parseColor("#B8C7D6")).apply { gravity=Gravity.CENTER;tag="launch_introduction" })
            addView(CallUi.text(a,"이음",56f,true,Color.WHITE).apply { gravity=Gravity.CENTER;letterSpacing=-0.02f;tag="launch_name" })
            translationY=CallUi.dp(a,16).toFloat()
        }
        val overlay=FrameLayout(a).apply {
            tag="launch_intro";setBackgroundColor(CallUi.NAVY);isClickable=true;contentDescription="이음 시작 화면, 누르면 넘어가요"
            addView(greeting,FrameLayout.LayoutParams(-1,-1));addView(brand,FrameLayout.LayoutParams(-1,-1))
        }
        val content=root.getChildAt(0)
        content?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        var done=false
        fun close() {
            if(done)return;done=true
            overlay.animate().cancel()
            overlay.animate().alpha(0f).setDuration(320).withEndAction {
                root.removeView(overlay);content?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }.start()
        }
        overlay.setOnClickListener { close() }
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        greeting.animate().alpha(1f).setDuration(350).withEndAction {
            greeting.animate().alpha(0f).setStartDelay(300).setDuration(300).start()
            brand.animate().alpha(1f).translationY(0f).setStartDelay(450).setDuration(450)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction { overlay.postDelayed({ close() },700) }.start()
        }.start()
    }
}
