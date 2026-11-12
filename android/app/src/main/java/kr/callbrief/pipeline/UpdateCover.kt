package kr.callbrief.pipeline

import android.app.Dialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v3.7 (user 11/12): the screen side of [OpenUpdate]. While the app is in front it decides whether to install the waiting
 * new version now, covers the page with "최신 버전으로 업데이트하는 중이에요" (or the download's progress words), and when the
 * phone blocks the install asks once (Samsung: once more after the settings) to turn the block off. */
internal class UpdateCover(private val a:MainActivity) {
    private val main=Handler(Looper.getMainLooper())
    private var cover:View?=null
    private var title:TextView?=null
    private var note:TextView?=null
    private var bar:ProgressBar?=null
    /** The install's own progress from Android ([android.content.pm.PackageInstaller.SessionCallback]), -1 while none came. */
    @Volatile private var installPercent=-1
    private val progressCallback=object:android.content.pm.PackageInstaller.SessionCallback() {
        override fun onCreated(id:Int) {};override fun onBadgingChanged(id:Int) {};override fun onActiveChanged(id:Int,active:Boolean) {}
        override fun onFinished(id:Int,success:Boolean) {}
        override fun onProgressChanged(id:Int,progress:Float) {
            if(id!=0 && id==CallUpdate.sessionId(a)) installPercent=OpenUpdate.percentOf(progress)
        }
    }
    private var callbackOn=false
    /** A read or a start is under way: the next one waits. */
    private var busy=false
    /** The person closed the cover with [닫고 계속 쓰기]: it stays away while this screen lives (the install goes on). */
    private var closedByHand=false
    private var sheet:Dialog?=null
    /** The version whose "설치가 끝나지 않았어요" sheet was shown on this screen (once). */
    private var noAnswerCode=-1
    private val tick=object:Runnable { override fun run() { follow();if(cover!=null && resumed()) main.postDelayed(this,1_000L) } }
    private val again=Runnable { consider() }

    private fun resumed()=!a.isFinishing && !a.isDestroyed && a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** [MainActivity.onResume]: the app is in front. [look]: decide now (false when the screen already installs by itself). */
    fun resume(look:Boolean=true) {
        OpenUpdate.front=true;OpenUpdate.readyListener={ consider() }
        BlockRetry.stop() // back in front: the onResume path goes on, never two tries at once
        BlockRetry.clearProgress(a)
        OpenUpdate.clearDone(a)
        if(!callbackOn) callbackOn=runCatching { a.packageManager.packageInstaller.registerSessionCallback(progressCallback,main) }.isSuccess
        if(look) consider()
        if(cover!=null) { main.removeCallbacks(tick);main.post(tick) }
    }
    fun pause() { OpenUpdate.front=false;OpenUpdate.readyListener=null
        // Behind the settings after [설정 열기]: the app's own install is asked again until the block is off (BlockRetry).
        if(OpenUpdate.retryPending() && !a.isFinishing) BlockRetry.start(a)
        if(callbackOn) { runCatching { a.packageManager.packageInstaller.unregisterSessionCallback(progressCallback) };callbackOn=false }
        main.removeCallbacks(tick);main.removeCallbacks(again) }

    /** Reads where the update stands (off the main thread) and acts on [OpenUpdate.decide]. */
    fun consider() {
        if(busy || !resumed()) return
        busy=true
        a.lifecycleScope.launch {
            val r=withContext(Dispatchers.IO) { runCatching {
                // Back from the settings after [설정 열기] (the person came back): one more try by itself.
                if(OpenUpdate.takeRetry()) CallUpdate.noteSetting(a,PipelineSettingsKeys.UPDATE_AUTO_BACK,"manual")
                val i=CallUpdate.openInput(a);val d=OpenUpdate.decide(i);val v=CallUpdate.sessionView(a)
                val code=CallUpdate.ready(a)?.versionCode ?: 0
                val noAnswer=i.tried && OpenUpdate.noAnswer(v.kind,v.ageMs,v.answered) && v.kind==OpenUpdate.Session.FOREGROUND
                if(noAnswer || d.reason!="nothing") CallUpdate.noteOpen(a,if(noAnswer) CallUpdate.BG_NO_ANSWER else d.reason) // nothing to install: nothing written
                Triple(d,noAnswer,code)
            }.getOrNull() }
            busy=false
            if(r==null) { hide();return@launch } // review fix: no decision, no cover left up
            if(!resumed()) return@launch
            val (d,noAnswer,code)=r
            when {
                noAnswer -> { hide();askNoAnswer(code) }
                d.reason=="blocked" -> { hide();askBlocked() }
                d.cover -> show()
                else -> hide()
            }
            if(d.start) start()
            // A call, or a background session that may be installing right now: look again in a few seconds.
            if(d.reason=="inCall" || d.reason=="bgFresh") { main.removeCallbacks(again);main.postDelayed(again,5_000L) }
        }
    }

    /** The install of the version that is here, as the open app's own (Android's window comes at once when it wants one). */
    private fun start() {
        if(busy) return
        busy=true;show()
        a.lifecycleScope.launch {
            val r=withContext(Dispatchers.IO) { runCatching { CallUpdate.takeInstallWish(a);CallUpdate.installFromOpen(a) } }
            busy=false
            if(a.isFinishing || a.isDestroyed) return@launch
            r.onFailure { hide();android.widget.Toast.makeText(a,"업데이트를 시작하지 못했어요. 설정의 앱 버전에서 다시 해 주세요.",android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** Every second while the cover is up: its words, then the decision again (download done, window closed, blocked). */
    private fun follow() {
        if(cover==null) return
        val s=runCatching { CallUpdate.state(a) }.getOrNull()
        val downloading=s?.phase==CallUpdate.P_DOWNLOADING
        val pct=if(downloading) s?.percent ?: -1 else installPercent
        title?.text=OpenUpdate.coverTitle(pct)
        note?.text=if(downloading) CallUi.clean(s?.words.orEmpty()) else OpenUpdate.UPDATING_NOTE
        bar?.let { b -> if(pct in 0..100) { b.isIndeterminate=false;b.progress=pct } else b.isIndeterminate=true }
        consider()
    }

    private fun show() {
        if(closedByHand || !resumed()) return
        if(cover!=null) return
        val host=a.findViewById<FrameLayout>(android.R.id.content) ?: return
        val side=CallUi.dp(a,28)
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(side,0,side,0) }
        val pb=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true;max=100;tag="update_cover_bar" }
        box.addView(pb,LinearLayout.LayoutParams(-1,CallUi.dp(a,8)).apply { bottomMargin=CallUi.dp(a,20) })
        val t=CallUi.text(a,OpenUpdate.UPDATING_WORDS,CallUi.TITLE,true).apply { gravity=Gravity.CENTER;tag="update_cover_title" }
        val n=CallUi.text(a,OpenUpdate.UPDATING_NOTE,CallUi.BODY,false,CallUi.SUBTLE).apply { gravity=Gravity.CENTER;tag="update_cover_note";setPadding(0,CallUi.dp(a,10),0,0) }
        box.addView(t);box.addView(n)
        box.addView(CallUi.linkButton(a,"닫고 계속 쓰기") { closedByHand=true;hide() }.apply { tag="update_cover_close" },
            LinearLayout.LayoutParams(-2,-2).apply { topMargin=CallUi.dp(a,24) })
        val frame=FrameLayout(a).apply {
            tag="update_cover";setBackgroundColor(CallUi.PAGE);isClickable=true;isFocusable=true // the page below takes no touch
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // Review fix: scrolls, so [닫고 계속 쓰기] is reachable with very large letters.
            addView(android.widget.ScrollView(a).apply { isFillViewport=true;addView(box,FrameLayout.LayoutParams(-1,-2)) },FrameLayout.LayoutParams(-1,-1))
        }
        t.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        host.addView(frame,FrameLayout.LayoutParams(-1,-1))
        cover=frame;title=t;note=n;bar=pb
        main.removeCallbacks(tick);main.postDelayed(tick,1_000L)
    }
    private fun hide() {
        main.removeCallbacks(tick)
        cover?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        cover=null;title=null;note=null;bar=null
    }

    /** The phone blocked the install: on a Samsung phone the question about 보안 위험 자동 차단 (at most twice per opening,
     * never after [나중에]); on another phone its one line once. */
    private fun askBlocked() {
        if(sheet?.isShowing==true) return
        val samsung=OpenUpdate.samsung()
        if(!OpenUpdate.askBlocked(samsung,OpenUpdate.asksThisOpen(),OpenUpdate.laterThisOpen())) return
        OpenUpdate.noteAsked()
        Thread { CallUpdate.bump(a,PipelineSettingsKeys.UPDATE_BLOCK_ASK) }.start()
        sheet=if(samsung) CallUi.sheetDialog(a,OpenUpdate.BLOCK_ASK_TITLE,OpenUpdate.BLOCK_ASK_WORDS+"\n\n"+OpenUpdate.BLOCK_ASK_PATH+" "+OpenUpdate.BLOCK_ASK_AUTO+"\n\n"+OpenUpdate.BLOCK_ASK_AGAIN,
                primary="설정 열기",onPrimary={
                    OpenUpdate.noteSettingsOpened()
                    Thread { CallUpdate.bump(a,PipelineSettingsKeys.UPDATE_BLOCK_OPEN) }.start()
                    openSecuritySettings() },
                secondary="나중에",onSecondary={ later() })
            else CallUi.sheetDialog(a,OpenUpdate.OTHER_BLOCKED_TITLE,OpenUpdate.OTHER_BLOCKED_WORDS,secondary="닫기",onSecondary={ later() })
        sheet?.setOnCancelListener { later() } // back or a touch outside: the same as [나중에]
    }
    /** [나중에]: not again in this opening, and (review fix) not by itself for a day; the banner's button stays. */
    private fun later() { OpenUpdate.noteLater();Thread { CallUpdate.noteBackoff(a,0,OpenUpdate.EV_LATER) }.start() }
    /** The open app's install got no answer at all for [OpenUpdate.FOREGROUND_WAIT_MS]: once per version on this screen. */
    private fun askNoAnswer(code:Int) {
        if(noAnswerCode==code || sheet?.isShowing==true) return
        noAnswerCode=code
        sheet=CallUi.sheetDialog(a,OpenUpdate.NO_ANSWER_TITLE,if(OpenUpdate.samsung()) OpenUpdate.SAMSUNG_NO_ANSWER_WORDS else OpenUpdate.OTHER_NO_ANSWER_WORDS,
            primary="다시 설치",onPrimary={ OpenUpdate.clearBlocked();start() },secondary="나중에")
    }
    /** 보안 위험 자동 차단 has no public screen of its own: the security settings when the phone has that screen, else the
     * settings. Each one is checked with resolveActivity before it is started. */
    private fun openSecuritySettings() {
        for(action in listOf(Settings.ACTION_SECURITY_SETTINGS,Settings.ACTION_SETTINGS)) {
            val i=Intent(action)
            if(runCatching { i.resolveActivity(a.packageManager)!=null }.getOrDefault(false) && runCatching { a.startActivity(i) }.isSuccess) return
        }
    }
}
