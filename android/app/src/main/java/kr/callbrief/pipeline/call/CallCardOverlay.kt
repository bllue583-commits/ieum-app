package kr.callbrief.pipeline.call

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kr.callbrief.R
import kr.callbrief.SecureSettings
import kr.callbrief.core.PhoneKey
import kr.callbrief.pipeline.CallUi
import kr.callbrief.pipeline.ContactCards
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The 이음 call card over the phone's call screen (design v3.0 7.1 and 7.2, r11-2). [CallCardService] (r11-1) calls
 * [show] at every call state, [hide] when the call is over; every function may be called from any thread (the work is
 * handed to the main thread, the card's data is read on the card's own thread).
 *
 * - A window of `TYPE_APPLICATION_OVERLAY`, not focusable, not touch modal, laid out in the screen, `FLAG_SECURE`, as big
 *   as the card only: touches beside it reach the call screen. Its top sits at [KEY_POS_Y] of the screen height
 *   (default [DEFAULT_POS_Y]), dragging the handle moves it and the new place is kept.
 * - Drawn in two steps: the name line at once, everything else within [REST_DELAY_MS] (well under 50 ms).
 * - Off hook it folds into a strip after [COLLAPSE_AFTER_MS] (a call that goes out after [COLLAPSE_OUT_MS], so the card is
 *   still open while it rings at the other end).
 * - Without the "draw over other apps" permission, or while the screen is locked (an overlay cannot show above the lock
 *   screen), a silent heads-up notice in channel [CHANNEL] says the same in three lines; once the phone is unlocked
 *   (`ACTION_USER_PRESENT`) during the call the notice becomes the card.
 * - A number without a contact card only when the user turned on [KEY_UNKNOWN] ("전화부에 없는 번호예요" and the latest text).
 *   A personal or excluded number ([CallSession.Snapshot.ignored]): nothing at all. */
internal object CallCardOverlay {
    const val CHANNEL="ieum.calls.card"
    const val CHANNEL_NAME="이음 통화 카드"
    const val NOTICE_TAG="callcard"
    const val NOTICE_ID=3_011
    /** Settings keys, the same strings as r11-1's `PipelineSettingsKeys.CARD_UNKNOWN` / `CARD_POS_Y` (one of them at merge). */
    const val KEY_UNKNOWN="cardUnknown"
    const val KEY_POS_Y="cardPosY"
    const val DEFAULT_POS_Y=0.38f
    /** The card is never taller than this share of the screen. */
    const val MAX_HEIGHT=0.40f
    const val REST_DELAY_MS=16L
    const val COLLAPSE_AFTER_MS=3_000L
    const val COLLAPSE_OUT_MS=15_000L
    private const val LOG="CallCardOverlay"

    /** Why a card did or did not show, for r11-3's `CARD_STATS` (callCard:{shown,noPermission,noNumber,noCard}). */
    const val SHOWN="shown";const val NO_PERMISSION="noPermission";const val NO_NUMBER="noNumber";const val NO_CARD="noCard"
    /** Told once per call: [SHOWN], [NO_PERMISSION] (shown as a notice instead), [NO_NUMBER] or [NO_CARD]. r11-3 may set it. */
    @Volatile var outcome:(Context,String)->Unit={ _,_ -> }

    // Replaceable in host tests.
    @Volatile internal var canDraw:(Context)->Boolean={ Settings.canDrawOverlays(it) }
    @Volatile internal var locked:(Context)->Boolean={ c -> c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked==true }
    @Volatile internal var latestText:(Context,String)->String?=::storeText

    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-call-card").apply { isDaemon=true } }
    /** Host tests: waits until the card's thread is done with what it was given. */
    internal fun awaitIdle() { worker.submit {}.get(10,TimeUnit.SECONDS) }

    // ---- main thread only ----------------------------------------------------------------------------------------
    private var app:Context?=null
    internal var view:CallCardView?=null;private set
    private var params:WindowManager.LayoutParams?=null
    /** The number the card is for (its key), the call's state, and its content. */
    private var key:String?=null
    private var state:CallSession.State=CallSession.State.IDLE
    internal var content:CallCardView.Content?=null;private set
    /** The heads-up notice is up instead of the card. */
    internal var noticeUp=false;private set
    private var presentReceiver:BroadcastReceiver?=null
    /** The user closed the card: nothing more for this call. */
    private var closed=false
    /** The user opened the strip by hand: it is not folded again by itself. */
    private var openedByHand=false
    private var told=false
    /** The data of this call is being read. */
    private var loading=false
    /** Bumped by [hide]: data read for an earlier call is dropped. */
    private var generation=0
    private var collapseQueued=false
    private val autoCollapse=Runnable { collapseQueued=false;collapse() }

    private fun onMain(block:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper()) runCatching(block).onFailure(::warn) else main.post { runCatching(block).onFailure(::warn) } }
    private fun warn(t:Throwable) { Log.w(LOG,"card: ${t.javaClass.simpleName}") }

    /** The call of [phoneKey] (any spelling, null when not known) is in [state]: shows, updates or (IDLE) takes away the card. */
    fun show(context:Context,phoneKey:String?,state:CallSession.State) {
        val c=context.applicationContext ?: context
        onMain { showNow(c,PhoneKey.of(phoneKey),state) }
    }
    /** Folds the card into its strip. */
    fun collapse():Unit = onMain {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        view?.setCollapsed(true);relayout()
    }
    /** Opens the strip into the whole card again. */
    fun expand():Unit = onMain {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        openedByHand=true
        view?.setCollapsed(false);relayout()
    }
    /** Takes the card (and the notice instead of it) away; the next call starts afresh. */
    fun hide():Unit = onMain { removeAll();forgetCall();state=CallSession.State.IDLE }
    private fun forgetCall() { closed=false;openedByHand=false;told=false;toldNoNumber=false;loading=false;key=null;content=null;generation++ }

    private fun showNow(c:Context,phoneKey:String?,to:CallSession.State) {
        app=c
        if(to==CallSession.State.IDLE) { hide();return }
        val session=runCatching { CallSession.current() }.getOrNull()
        if(session?.ignored==true) { hide();return }
        // A state without the number (off hook, the number already known from the ringing) is the same call.
        val newCall=phoneKey!=null && phoneKey!=key
        if(newCall) { removeAll();forgetCall() }
        if(phoneKey!=null) key=phoneKey
        state=to
        if(closed) return
        if(content!=null) { place(c);afterState();return }
        val k=key
        if(k==null) { tell(c,NO_NUMBER,last=false);return }
        if(!newCall && loading) return
        loading=true
        val gen=generation
        worker.execute { runCatching { load(c,k,gen) }.onFailure(::warn) }
    }

    /** The card's data, read off the main thread: the contact card, or for an unknown number (when wanted) the latest text. */
    private fun load(c:Context,phoneKey:String,gen:Int) {
        val card=ContactCards.get(c,phoneKey)
        if(card!=null) { deliver(c,gen,CallCardView.Content.of(card));return }
        val settings=runCatching { SecureSettings(c).snapshot() }.getOrNull()
        if(settings?.optBoolean(KEY_UNKNOWN,false)!=true || runCatching { CallSession.isPrivate(c,phoneKey) }.getOrDefault(true)) {
            main.post { if(gen==generation) { loading=false;tell(c,NO_CARD) } };return
        }
        // The name line first ("전화부에 없는 번호예요"), the latest text once read (the call store may take a moment to open).
        deliver(c,gen,CallCardView.Content.unknown(null),rest=false)
        val text=runCatching { latestText(c,phoneKey) }.getOrNull()
        deliver(c,gen,CallCardView.Content.unknown(text))
    }
    private fun deliver(c:Context,gen:Int,data:CallCardView.Content,rest:Boolean=true) { main.post {
        runCatching {
            if(gen!=generation || closed || state==CallSession.State.IDLE) return@post
            val first=content==null
            content=data;loading=false
            if(first) { place(c,nameOnly=true);tell(c,if(noticeUp) NO_PERMISSION else SHOWN) }
            if(rest) main.postDelayed({ runCatching { if(gen==generation && content===data) { view?.showAll(data);relayout();if(noticeUp) notice(c,data) } }.onFailure(::warn) },
                if(first) REST_DELAY_MS else 0L)
            afterState()
        }.onFailure(::warn)
    } }
    /** [last]=false: the number may still come (a later broadcast fills it in), so a card may still be told after it. */
    private var toldNoNumber=false
    private fun tell(c:Context,what:String,last:Boolean=true) {
        if(told) return
        if(!last) { if(toldNoNumber) return;toldNoNumber=true;runCatching { outcome(c,what) };return }
        if(toldNoNumber) return // already counted for this call
        told=true;runCatching { outcome(c,what) }
    }

    /** The card in its window, or the notice when the card cannot show. */
    private fun place(c:Context,nameOnly:Boolean=false) {
        val data=content ?: return
        if(!runCatching { canDraw(c) }.getOrDefault(false)) { notice(c,data);return }
        if(runCatching { locked(c) }.getOrDefault(false)) { notice(c,data);waitForUnlock(c);return }
        if(noticeUp) cancelNotice(c)
        if(view==null) addCard(c,data,nameOnly)
    }
    private fun afterState() {
        val v=view ?: return
        if(state==CallSession.State.OFFHOOK && !v.collapsed && !openedByHand && !collapseQueued) {
            collapseQueued=true
            val out=runCatching { CallSession.current().direction==CallSession.Direction.OUT }.getOrDefault(false)
            main.postDelayed(autoCollapse,if(out) COLLAPSE_OUT_MS else COLLAPSE_AFTER_MS)
        }
    }

    private fun addCard(c:Context,data:CallCardView.Content,nameOnly:Boolean) {
        CallUi.applyTheme(c)
        val wm=c.getSystemService(WindowManager::class.java) ?: return
        val dm=c.resources.displayMetrics
        val v=CallCardView(c).apply {
            maxHeightPx=(dm.heightPixels*MAX_HEIGHT).toInt()
            onCollapse={ collapse() };onExpand={ expand() };onClose={ close() }
            onMoveBy={ dy -> moveBy(dy) };onMoveEnd={ savePosition() }
            if(nameOnly) showName(data) else showAll(data)
        }
        val side=CallUi.dp(c,12)
        val width=minOf(dm.widthPixels-2*side,CallUi.dp(c,560))
        val p=WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title="이음 통화 카드"
            y=clampY(c,(positionFraction(c)*dm.heightPixels).toInt(),v)
        }
        wm.addView(v,p)
        view=v;params=p
    }
    private fun relayout() { val v=view ?: return;val p=params ?: return;val c=app ?: return
        p.y=clampY(c,p.y,v);runCatching { c.getSystemService(WindowManager::class.java)?.updateViewLayout(v,p) } }
    /** Keeps the card wholly on the screen. */
    private fun clampY(c:Context,y:Int,v:View):Int {
        val h=c.resources.displayMetrics.heightPixels
        val tall=if(v.height>0) v.height else (h*(if((v as? CallCardView)?.collapsed==true) 0.1f else MAX_HEIGHT)).toInt()
        return y.coerceIn(0,maxOf(0,h-tall))
    }
    private fun moveBy(dy:Float) { val p=params ?: return;p.y+=dy.toInt();relayout() }
    private fun savePosition() {
        val c=app ?: return;val p=params ?: return
        val f=(p.y.toFloat()/c.resources.displayMetrics.heightPixels).coerceIn(0f,1f)
        worker.execute { runCatching { SecureSettings(c).update { it.put(KEY_POS_Y,f.toDouble()) } }.onFailure(::warn) }
    }
    /** The card's place (share of the screen height): the one the user dragged it to, else [DEFAULT_POS_Y]. */
    internal fun positionFraction(c:Context):Float =runCatching { SecureSettings(c).snapshot().optDouble(KEY_POS_Y,DEFAULT_POS_Y.toDouble()).toFloat() }
        .getOrDefault(DEFAULT_POS_Y).takeIf { !it.isNaN() }?.coerceIn(0f,1f) ?: DEFAULT_POS_Y
    /** [닫기]: the card goes for the rest of this call. */
    private fun close() { removeAll();closed=true }
    private fun removeAll() {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        val c=app
        view?.let { v -> runCatching { c?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) } }
        view=null;params=null
        if(c!=null) { if(noticeUp) cancelNotice(c);stopWaiting(c) }
    }

    // ---- the notice instead of the card ----------------------------------------------------------------------------
    private fun waitForUnlock(c:Context) {
        if(presentReceiver!=null) return
        val r=object:BroadcastReceiver() { override fun onReceive(x:Context,i:Intent) { if(i.action==Intent.ACTION_USER_PRESENT) onMain { onUnlocked(c) } } }
        runCatching {
            if(Build.VERSION.SDK_INT>=33) c.registerReceiver(r,IntentFilter(Intent.ACTION_USER_PRESENT),Context.RECEIVER_NOT_EXPORTED)
            else c.registerReceiver(r,IntentFilter(Intent.ACTION_USER_PRESENT))
            presentReceiver=r
        }.onFailure(::warn)
    }
    private fun stopWaiting(c:Context) { presentReceiver?.let { r -> runCatching { c.unregisterReceiver(r) } };presentReceiver=null }
    private fun onUnlocked(c:Context) {
        stopWaiting(c)
        if(state==CallSession.State.IDLE || closed || content==null) return
        place(c);view?.let { v -> content?.let { if(!v.complete) v.showAll(it) } };afterState()
    }
    /** The heads-up notice of [data] (also what r11-1's service may show as its own notice). */
    fun buildNotice(c:Context,data:CallCardView.Content):Notification {
        val m=c.getSystemService(NotificationManager::class.java)
        m?.createNotificationChannel(NotificationChannel(CHANNEL,CHANNEL_NAME,NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null);enableVibration(false);setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        val lines=data.noticeLines()
        return Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setOnlyAlertOnce(true)
            .setContentTitle(data.name).setContentText(lines.firstOrNull() ?: "이음 통화 카드")
            .setStyle(Notification.BigTextStyle().setBigContentTitle(data.name).bigText(lines.joinToString("\n")))
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setLocalOnly(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // Lock screen without "show content": no name, no summary.
            .setPublicVersion(Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setContentTitle("이음 통화 카드")
                .setContentText("잠금을 풀면 카드가 보여요").build())
            .build()
    }
    private fun notice(c:Context,data:CallCardView.Content) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        runCatching { c.getSystemService(NotificationManager::class.java)?.notify(NOTICE_TAG,NOTICE_ID,buildNotice(c,data));noticeUp=true }.onFailure(::warn)
    }
    private fun cancelNotice(c:Context) { runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_TAG,NOTICE_ID) };noticeUp=false }

    /** The latest text with [phoneKey] (an unknown number), first sentence only. Opens the call store: off the main thread. */
    private fun storeText(c:Context,phoneKey:String):String? {
        val r=kr.callbrief.IeumApplication.store(c).cardRows("""SELECT summary,transcript FROM call_records WHERE counterpart_key=? AND recording_id LIKE 'sms:%'
            ORDER BY COALESCE(started_ms,created_ms) DESC LIMIT 1""",phoneKey).firstOrNull() ?: return null
        val summary=ContactCards.firstSentence(r["summary"] as? String)
        if(summary.isNotEmpty()) return summary
        val last=(r["transcript"] as? String).orEmpty().lines().lastOrNull { it.isNotBlank() }.orEmpty()
        return ContactCards.firstSentence(last.substringAfter("] ",last)).takeIf { it.isNotEmpty() }
    }

    /** Host tests: back to the start. */
    internal fun resetForHost() {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        app?.let { c -> view?.let { runCatching { c.getSystemService(WindowManager::class.java)?.removeViewImmediate(it) } };stopWaiting(c) }
        view=null;params=null;noticeUp=false;forgetCall()
        state=CallSession.State.IDLE;app=null
        canDraw={ Settings.canDrawOverlays(it) };locked={ c -> c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked==true }
        latestText=::storeText;outcome={ _,_ -> }
    }
}
