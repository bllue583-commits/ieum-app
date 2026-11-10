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
import android.os.SystemClock
import android.provider.CallLog
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

/** The 이음 call card over the phone's call screen (design v3.0 7.1 and 7.2, r11-2; v3.4 DESIGN_V4 4.2-1 and 4.2-2, r14-2).
 * [CallCardService] (r11-1) calls [show] at every call state, [hide] when the call is over; every function may be called from
 * any thread (the work is handed to the main thread, the card's data is read on the card's own thread).
 *
 * - A window of `TYPE_APPLICATION_OVERLAY`, not focusable, not touch modal, laid out in the screen, `FLAG_SECURE`, as big
 *   as the card only: touches beside it reach the call screen.
 * - Where it sits (4.2-2): while it rings, at most [MAX_HEIGHT] of the screen tall with its lower edge at most at
 *   [BOTTOM_LIMIT] (the answer buttons stay free); its top at [KEY_POS_Y] (default [DEFAULT_POS_Y]), the place the user
 *   dragged it to pulled into that range. A call going out opens at the top ([OUT_TOP] to [OUT_BOTTOM]). Off hook it folds
 *   after [COLLAPSE_AFTER_MS] into a strip whose top is within [STRIP_TOP_MIN] .. [STRIP_TOP_MAX]; a card with nothing to keep
 *   in view during the call (no summary, no work: [CallCardView.Content.hasBody]) goes instead of folding.
 * - Drawn in two steps: the first two lines at once, everything else within [REST_DELAY_MS] (well under 50 ms).
 * - Without the "draw over other apps" permission a silent heads-up notice in channel [CHANNEL] says the same in lines.
 *   v3.6.1 (DESIGN_V7 K1, K2): the window is added whatever the check says (the check was seen to say no at the very moment of
 *   calls while it said yes before and after). Android then shows it, refuses it (an exception: the notice) or adds it hidden
 *   (no exception). So the window counts as drawn only when it was drawn while visible ([drawnNow]), judged [HIDDEN_AFTER_MS]
 *   after it was added (or when it goes sooner); a window not seen by then is taken away and the notice shows instead. While the
 *   check says no, the notice goes up at once beside the window and goes once the window is seen. What became of it goes to
 *   [CardStats.verdict] with a cause code and the phone's state ([CardProbe]).
 *   While the screen is locked (4.2-1) the window is added anyway and the notice goes up beside it (a policy may hide the window
 *   without the view hearing of it); `ACTION_USER_PRESENT` takes the notice away. While [CallCardService] runs, the notice takes
 *   the service's own slot ([CallCardService.NOTIFICATION_ID]): one notice, not two.
 * - A number without a contact card only when the user turned on [KEY_UNKNOWN] ("아직 정리된 통화가 없는 번호예요" and what the
 *   phone's call log says of it). The ringing path reads the card file and the call log only, never the call store.
 * - On the locked screen the notice shows the name, company and one check item unless "잠금 화면에서도 이름과 한 줄 보기" is off.
 *   A personal or excluded number ([CallSession.Snapshot.ignored]): nothing at all.
 * - [CardStats.expanded] when the person opens the strip again, [CardStats.closed] when they close the card. */
internal object CallCardOverlay {
    const val CHANNEL="ieum.calls.card"
    const val CHANNEL_NAME="이음 통화 카드"
    const val NOTICE_TAG="callcard"
    const val NOTICE_ID=3_011
    /** Settings keys, the same strings as r11-1's `PipelineSettingsKeys.CARD_UNKNOWN` / `CARD_POS_Y` (one of them at merge). */
    const val KEY_UNKNOWN=kr.callbrief.pipeline.PipelineSettingsKeys.CARD_UNKNOWN
    const val KEY_POS_Y=kr.callbrief.pipeline.PipelineSettingsKeys.CARD_POS_Y
    /** While it rings: the top's default place, the tallest the card may be and the lowest its lower edge may go (shares of the
     * screen height, the 1.3 times letters included). */
    const val DEFAULT_POS_Y=0.38f
    const val MAX_HEIGHT=0.34f
    const val BOTTOM_LIMIT=0.72f
    /** Off hook: the strip's top stays within these. */
    const val STRIP_TOP_MIN=0.08f
    const val STRIP_TOP=0.10f
    const val STRIP_TOP_MAX=0.20f
    /** A call going out (OFFHOOK is the moment of dialling): open at the top, its lower edge within [OUT_BOTTOM]. */
    const val OUT_TOP=0.04f
    const val OUT_BOTTOM=0.25f
    const val REST_DELAY_MS=16L
    /** Off hook (in or out) the card folds this much later. */
    const val COLLAPSE_AFTER_MS=3_000L
    private const val LOG="CallCardOverlay"

    /** Why a card did or did not show, for r11-3's `CARD_STATS` (callCard:{shown,noPermission,noNumber,noCard}). */
    const val SHOWN="shown";const val NO_PERMISSION="noPermission";const val NO_NUMBER="noNumber";const val NO_CARD="noCard"
    const val LOCKED="locked"
    /** v3.6.1: the window not seen this long after it was added is taken for hidden. */
    const val HIDDEN_AFTER_MS=1_500L
    /** v3.6.1 cause codes ([CardStats.Attempt.cause]); an exception of `addView` is `addView:<its class>`. */
    const val CAUSE_OK="ok";const val CAUSE_DRAWN_DESPITE_FALSE="drawnDespiteFalse";const val CAUSE_SHORT="short"
    /** Added while the screen was locked and never seen until the call ended (the notice was beside it). */
    const val CAUSE_HIDDEN_LOCKED="hiddenLocked"
    /** Told once per call when the window was put up: [SHOWN] (added, unlocked), [LOCKED] (added while locked, the notice beside
     * it), [NO_PERMISSION] (the overlay check said no then, or the window could not be added: the notice), [NO_NUMBER] or
     * [NO_CARD]. Counted in [CardStats] (settings CARD_STATS, the heartbeat's callCard); v3.6.1: as told, nothing read again. */
    @Volatile var outcome:(Context,String)->Unit={ c,w -> CardStats.record(c,w) }
    /** v3.4: the person opened the strip again / closed the card this long after it showed. Replaceable in host tests. */
    @Volatile internal var onExpanded:(Context)->Unit={ CardStats.expanded(it) }
    @Volatile internal var onClosed:(Context,Long)->Unit={ c,ms -> CardStats.closed(c,ms) }

    // Replaceable in host tests.
    @Volatile internal var canDraw:(Context)->Boolean={ Settings.canDrawOverlays(it) }
    @Volatile internal var locked:(Context)->Boolean={ c -> c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked==true }
    /** What the call log says of a number without a card ("3일 전 통화, 30일간 2번"), or null. Off the main thread. */
    @Volatile internal var logLine:(Context,String)->String?=::callLogLine
    /** The card's service is up in this process (its notice slot is then the card's). */
    @Volatile internal var serviceUp:()->Boolean={ CallCardService.running!=null }
    @Volatile internal var clock:()->Long={ SystemClock.elapsedRealtime() }
    /** v3.6.1 (K1): the window was drawn while visible ([CallCardView.seen]). Replaceable in host tests. */
    @Volatile internal var drawnNow:(CallCardView)->Boolean={ it.seen }
    /** v3.6.1: adds the window (an exception is the system's refusal). Replaceable in host tests. */
    @Volatile internal var addWindow:(WindowManager,View,WindowManager.LayoutParams)->Unit={ wm,v,p -> wm.addView(v,p) }
    /** v3.6.1 (K3): the phone's state when the window is added, read on the card's thread. Replaceable in host tests. */
    @Volatile internal var probe:(Context)->CardStats.Probe?={ c -> runCatching { CardProbe.read(c) }.getOrNull() }
    /** v3.6.1 (K2): what became of the window, once per call. Replaceable in host tests. */
    @Volatile internal var onVerdict:(Context,CardStats.Attempt)->Unit={ c,a -> CardStats.verdict(c,a) }

    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-call-card").apply { isDaemon=true } }
    /** Host tests: waits until the card's thread is done with what it was given. */
    internal fun awaitIdle() { worker.submit {}.get(10,TimeUnit.SECONDS) }

    // ---- main thread only ----------------------------------------------------------------------------------------
    private var app:Context?=null
    internal var view:CallCardView?=null;private set
    internal var params:WindowManager.LayoutParams?=null;private set
    /** The number the card is for (its key), the call's state, and its content. */
    private var key:String?=null
    private var state:CallSession.State=CallSession.State.IDLE
    internal var content:CallCardView.Content?=null;private set
    /** The heads-up notice is up (instead of the card, or beside it while the screen is locked). */
    internal var noticeUp=false;private set
    /** The notice is in the service's slot ([CallCardService.NOTIFICATION_ID]). */
    internal var noticeMerged=false;private set
    private var presentReceiver:BroadcastReceiver?=null
    /** The user closed the card, or it went at OFFHOOK with nothing to keep in view: nothing more for this call. */
    private var closed=false
    /** The user opened the strip by hand: it is not folded again by itself. */
    private var openedByHand=false
    private var told=false
    /** The data of this call is being read. */
    private var loading=false
    /** Bumped by [hide]: data read for an earlier call is dropped. */
    @Volatile private var generation=0
    private var collapseQueued=false
    /** When the card (or its notice) first showed in this call ([clock]), for [onClosed]. */
    private var shownAt=0L
    /** Where the strip was left in this call (px), so it folds back there. */
    private var stripY:Int?=null
    private val autoCollapse=Runnable { collapseQueued=false;collapse() }
    // v3.6.1: this call's window attempt.
    /** When the window was added ([clock]; 0: not yet), what the overlay check and the lock said then, and its exception. */
    private var addedAt=0L
    private var canDrawAtAdd=true
    private var lockedAtAdd=false
    /** This call's broadcast started the process; the call goes out (both read when the window is put up). */
    private var coldAtAdd=false
    private var outAtAdd=false
    private var addError:String?=null
    /** No window again in this call (it could not be added, or it was hidden). */
    private var windowGone=false
    /** [onVerdict] was told; the window was seen. */
    private var judged=false
    private var seenOnce=false
    /** The phone's state read after the window was added (the card's thread). */
    @Volatile private var probed:CardStats.Probe?=null
    private val judgeLater=Runnable { app?.let { runCatching { judge(it,ending=false) }.onFailure(::warn) } }

    private fun onMain(block:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper()) runCatching(block).onFailure(::warn) else main.post { runCatching(block).onFailure(::warn) } }
    private fun warn(t:Throwable) { Log.w(LOG,"card: ${t.javaClass.simpleName}") }

    /** The call of [phoneKey] (any spelling, null when not known) is in [state]: shows, updates or (IDLE) takes away the card. */
    fun show(context:Context,phoneKey:String?,state:CallSession.State) {
        val c=context.applicationContext ?: context
        onMain { showNow(c,PhoneKey.of(phoneKey),state) }
    }
    /** Folds the card into its strip at the top (4.2-2); a card with nothing to keep in view goes instead. */
    fun collapse():Unit = onMain {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        val v=view ?: return@onMain
        if(content?.hasBody==false) { removeAll();closed=true;Log.i(LOG,"card gone: nothing to keep in view");return@onMain }
        if(!v.collapsed) { v.setCollapsed(true);Log.i(LOG,"card collapsed") }
        params?.let { p -> app?.let { c -> p.y=stripY ?: (STRIP_TOP*height(c)).toInt() } }
        relayout(force=true)
    }
    /** Opens the strip into the whole card again. */
    fun expand():Unit = onMain {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        openedByHand=true
        view?.setCollapsed(false);relayout()
    }
    /** Takes the card (and the notice instead of it) away; the next call starts afresh. */
    fun hide():Unit = onMain { removeAll();forgetCall();state=CallSession.State.IDLE }
    private fun forgetCall() { closed=false;openedByHand=false;told=false;toldNoNumber=false;loading=false;key=null;content=null;stripY=null;shownAt=0L;generation++
        main.removeCallbacks(judgeLater);addedAt=0L;canDrawAtAdd=true;lockedAtAdd=false;addError=null;windowGone=false;judged=false;seenOnce=false;probed=null;coldAtAdd=false;outAtAdd=false }

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
        if(content!=null) { place(c);relayout();afterState();return }
        val k=key
        if(k==null) { tell(c,NO_NUMBER,last=false);return }
        if(!newCall && loading) return
        loading=true
        val gen=generation
        worker.execute { runCatching { load(c,k,gen) }.onFailure(::warn) }
    }

    /** The card's data, read off the main thread: the contact card (the card file only), or for an unknown number (when wanted)
     * what the call log says of it. Never the call store: the ringing path stays quick (4.1-2). */
    private fun load(c:Context,phoneKey:String,gen:Int) {
        val card=ContactCards.get(c,phoneKey)
        if(card!=null) { deliver(c,gen,CallCardView.Content.of(card));return }
        val settings=runCatching { CallSession.callSettings(c) }.getOrNull()
        if(settings?.optBoolean(KEY_UNKNOWN,false)!=true) {
            main.post { if(gen==generation) { loading=false;tell(c,NO_CARD) } };return
        }
        // The first line at once, the call log's line once read.
        deliver(c,gen,CallCardView.Content.unknown(null),rest=false)
        val line=runCatching { logLine(c,phoneKey) }.getOrNull()
        deliver(c,gen,CallCardView.Content.unknown(line))
    }
    private fun deliver(c:Context,gen:Int,data:CallCardView.Content,rest:Boolean=true) { main.post {
        runCatching {
            if(gen!=generation || closed || state==CallSession.State.IDLE) return@post
            val first=content==null
            content=data;loading=false
            if(first) { place(c,nameOnly=true);shownAt=clock();tell(c,firstOutcome()) }
            else view?.let { if(it.complete) it.showAll(data) }
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

    /** What the card tells [outcome] when it first shows (v3.6.1: what it found then; [CardStats] reads nothing again). */
    private fun firstOutcome():String = when {
        view==null && noticeUp -> NO_PERMISSION
        view==null -> NO_PERMISSION // no notice either: CardStats counts noNotify
        !canDrawAtAdd -> NO_PERMISSION
        lockedAtAdd -> LOCKED
        else -> SHOWN
    }
    /** The card in its window; with the screen locked the notice beside it too (4.2-1). v3.6.1 (K1): the window is tried whatever
     * the overlay check says; while the check says no and the window was not seen yet, or the window could not be added or was
     * hidden, the notice shows. */
    private fun place(c:Context,nameOnly:Boolean=false) {
        val data=content ?: return
        val draw=runCatching { canDraw(c) }.getOrDefault(false)
        val isLocked=runCatching { locked(c) }.getOrDefault(false)
        if(view==null && !windowGone) addCard(c,data,nameOnly,draw,isLocked)
        when {
            view==null -> notice(c,data)
            isLocked -> { notice(c,data);waitForUnlock(c) }
            !draw && !seenOnce -> notice(c,data)
            noticeUp -> cancelNotice(c)
        }
    }
    /** v3.6.1 (K1, K2): what became of the window, once per call: at [HIDDEN_AFTER_MS] after it was added, or sooner when it goes
     * ([ending]). Seen: drawn (the notice put up for a check that said no goes). Not seen in time: hidden, the window goes and the
     * notice shows. Gone before it was judged: a fallback when the notice was up, else nothing to count. */
    private fun judge(c:Context,ending:Boolean) {
        if(judged) return
        main.removeCallbacks(judgeLater)
        val v=view
        if(addedAt==0L && addError==null) return
        val seen=v!=null && runCatching { drawnNow(v) }.getOrDefault(false)
        if(!seen && !ending && v!=null) {
            // Locked: the lock screen covers the window and the notice is up; it is judged again once unlocked (4.2-1).
            if(runCatching { locked(c) }.getOrDefault(false)) return
            judged=true;windowGone=true
            runCatching { c.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) }
            view=null;params=null
            Log.i(LOG,"card hidden")
            content?.let { notice(c,it) }
            verdict(c,CardStats.FALLBACK,CardStats.HIDDEN,null)
            return
        }
        judged=true
        if(seen) {
            seenOnce=true
            Log.i(LOG,"card drawn")
            verdict(c,CardStats.DRAWN,if(canDrawAtAdd) CAUSE_OK else CAUSE_DRAWN_DESPITE_FALSE,(clock()-addedAt).coerceAtLeast(0))
            if(!ending && noticeUp && !runCatching { locked(c) }.getOrDefault(false)) cancelNotice(c)
        } else verdict(c,if(noticeUp) CardStats.FALLBACK else CardStats.GONE,if(lockedAtAdd) CAUSE_HIDDEN_LOCKED else CAUSE_SHORT,null)
    }
    private fun verdict(c:Context,result:String,cause:String,drawMs:Long?) {
        val after=runCatching { canDraw(c) }.getOrNull()
        val base=CardStats.Attempt(result,cause,canDrawAtAdd,after,null,lockedAtAdd,coldAtAdd,drawMs,addError,outAtAdd)
        // On the card's thread, after the probe read when the window was added.
        worker.execute { runCatching { onVerdict(c,base.copy(probe=probed ?: runCatching { probe(c) }.getOrNull())) }.onFailure(::warn) }
    }
    private fun afterState() {
        val v=view ?: return
        if(state==CallSession.State.OFFHOOK && !v.collapsed && !openedByHand && !collapseQueued) {
            collapseQueued=true
            main.postDelayed(autoCollapse,COLLAPSE_AFTER_MS)
        }
    }
    private fun outgoing()=runCatching { CallSession.current().direction==CallSession.Direction.OUT }.getOrDefault(false)

    private fun addCard(c:Context,data:CallCardView.Content,nameOnly:Boolean,draw:Boolean,isLocked:Boolean) {
        CallUi.applyTheme(c)
        val wm=c.getSystemService(WindowManager::class.java) ?: return
        canDrawAtAdd=draw;lockedAtAdd=isLocked
        coldAtAdd=runCatching { CardStats.callCold() }.getOrDefault(false);outAtAdd=outgoing()
        val dm=c.resources.displayMetrics
        val v=CallCardView(c).apply {
            maxHeightPx=maxHeight(c,this)
            onCollapse={ collapse() };onExpand={ expand();runCatching { onExpanded(c) } };onClose={ close() }
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
            y=clampY(c,if(topOpen(v)) (OUT_TOP*height(c)).toInt() else (positionFraction(c)*height(c)).toInt(),v)
        }
        // A new height (the second drawing step, folding, larger letters) is checked against the limits again.
        v.addOnLayoutChangeListener { _,_,top,_,bottom,_,oldTop,_,oldBottom -> if(bottom-top!=oldBottom-oldTop) main.post { if(view===v) relayout() } }
        val gen=generation
        worker.execute { runCatching { val pr=probe(c);if(gen==generation) probed=pr }.onFailure(::warn) }
        // v3.6.1 (K2): the system's refusal is kept as a cause code, not swallowed (the notice shows instead).
        try { addWindow(wm,v,p) } catch(e:Exception) {
            addError="addView:${e.javaClass.simpleName}";windowGone=true
            Log.w(LOG,"card refused: ${e.javaClass.simpleName} canDraw=$draw")
            verdict(c,CardStats.FALLBACK,addError!!,null);judged=true
            return
        }
        view=v;params=p;addedAt=clock()
        main.postDelayed(judgeLater,HIDDEN_AFTER_MS)
        // r11-3: the emulator check reads these lines (tools/device/call_card_test.sh); no number, no name.
        Log.i(LOG,"card shown unknown=${data.unknown} canDraw=$draw")
    }
    private fun height(c:Context)=c.resources.displayMetrics.heightPixels
    /** The open card of a call going out, not opened again by hand: at the top (4.2-2). */
    private fun topOpen(v:CallCardView)=!v.collapsed && !openedByHand && outgoing()
    private fun maxHeight(c:Context,v:CallCardView):Int =(height(c)*if(topOpen(v)) OUT_BOTTOM-OUT_TOP else MAX_HEIGHT).toInt()
    /** The card's height limit and place checked again; the window is updated only when its place changed (a needless update
     * costs a frame). [force]: update anyway (the place was changed by the caller). */
    internal fun relayout(force:Boolean=false) { val v=view ?: return;val p=params ?: return;val c=app ?: return
        val max=maxHeight(c,v);if(v.maxHeightPx!=max) { v.maxHeightPx=max;v.requestLayout() }
        val y=clampY(c,p.y,v)
        if(y==p.y && !force) return
        p.y=y;runCatching { c.getSystemService(WindowManager::class.java)?.updateViewLayout(v,p) } }
    /** Keeps the card in its range (4.2-2): the strip's top within [STRIP_TOP_MIN] .. [STRIP_TOP_MAX]; a call going out at the
     * top; while it rings (or opened by hand) the lower edge at most at [BOTTOM_LIMIT] (off hook not above the strip's range). */
    internal fun clampY(c:Context,y:Int,v:CallCardView):Int {
        val h=height(c)
        if(v.collapsed) return y.coerceIn((STRIP_TOP_MIN*h).toInt(),(STRIP_TOP_MAX*h).toInt())
        if(topOpen(v)) return (OUT_TOP*h).toInt()
        val tall=if(v.height>0) minOf(v.height,v.maxHeightPx) else v.maxHeightPx.takeIf { it in 1 until Int.MAX_VALUE } ?: (h*MAX_HEIGHT).toInt()
        val min=if(state==CallSession.State.OFFHOOK) (STRIP_TOP_MIN*h).toInt() else 0
        return y.coerceIn(min,maxOf(min,(BOTTOM_LIMIT*h).toInt()-tall))
    }
    private fun moveBy(dy:Float) { val p=params ?: return;p.y+=dy.toInt();relayout(force=true) }
    /** The place is kept for the next ringing card; the strip's only for this call. */
    private fun savePosition() {
        val c=app ?: return;val p=params ?: return;val v=view ?: return
        if(v.collapsed) { stripY=p.y;return }
        if(topOpen(v) || state!=CallSession.State.RINGING) return
        val f=(p.y.toFloat()/height(c)).coerceIn(0f,1f)
        worker.execute { runCatching { SecureSettings(c).update { it.put(KEY_POS_Y,f.toDouble()) } }.onFailure(::warn) }
    }
    /** The card's place while it rings (share of the screen height): the one the user dragged it to, else [DEFAULT_POS_Y]. */
    internal fun positionFraction(c:Context):Float =runCatching { (CallSession.callSettings(c) ?: SecureSettings(c).snapshot()).optDouble(KEY_POS_Y,DEFAULT_POS_Y.toDouble()).toFloat() }
        .getOrDefault(DEFAULT_POS_Y).takeIf { !it.isNaN() }?.coerceIn(0f,1f) ?: DEFAULT_POS_Y
    /** [닫기]: the card goes for the rest of this call. */
    private fun close() {
        val c=app
        if(c!=null && shownAt>0) runCatching { onClosed(c,(clock()-shownAt).coerceAtLeast(0)) }
        removeAll();closed=true
    }
    private fun removeAll() {
        main.removeCallbacks(autoCollapse);collapseQueued=false
        val c=app
        if(c!=null) runCatching { judge(c,ending=true) }.onFailure(::warn)
        view?.let { v -> runCatching { c?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) } }
        view=null;params=null
        if(c!=null) { if(noticeUp) cancelNotice(c);stopWaiting(c) }
    }

    // ---- the notice ------------------------------------------------------------------------------------------------
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
    /** Unlocked during the call: the notice goes, the card stays (or comes, when the window could not be added before). */
    private fun onUnlocked(c:Context) {
        stopWaiting(c)
        if(noticeUp && view!=null && (seenOnce || runCatching { canDraw(c) }.getOrDefault(false))) cancelNotice(c)
        // v3.6.1: a window not judged yet (the lock covered it) is judged a while after the unlock.
        if(!judged && view!=null) { main.removeCallbacks(judgeLater);main.postDelayed(judgeLater,HIDDEN_AFTER_MS) }
        if(state==CallSession.State.IDLE || closed || content==null) return
        place(c);view?.let { v -> content?.let { if(!v.complete) v.showAll(it) } };relayout();afterState()
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
            .setPublicVersion(publicNotice(c,data,lockDetail(c)))
            .build()
    }
    /** "잠금 화면에서도 이름과 한 줄 보기" ([KEY_LOCK_DETAIL], on unless the user turned it off). */
    const val KEY_LOCK_DETAIL=kr.callbrief.pipeline.PipelineSettingsKeys.LOCK_SCREEN_DETAIL
    internal fun lockDetail(c:Context):Boolean =runCatching { (CallSession.callSettings(c) ?: SecureSettings(c).snapshot()).optBoolean(KEY_LOCK_DETAIL,true) }.getOrDefault(true)
    /** What the locked screen shows of the notice (DESIGN_V4 3.1-5, 1.3 #9). With [detail]: the name, the company and one check
     * item ([CallCardView.Content.publicText]: no number, no amount, nothing of the call's words). Without: no name, no summary. */
    internal fun publicNotice(c:Context,data:CallCardView.Content,detail:Boolean):Notification {
        val b=Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
        return if(detail) b.setContentTitle(data.publicTitle()).setContentText(data.publicText() ?: "잠금을 풀면 카드가 보여요").build()
        else b.setContentTitle("이음 통화 카드").setContentText("잠금을 풀면 카드가 보여요").build()
    }
    private fun notice(c:Context,data:CallCardView.Content) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val m=c.getSystemService(NotificationManager::class.java) ?: return
            val n=buildNotice(c,data)
            // One notice while the service runs: the card's takes the service's slot (4.2-1).
            if(runCatching { serviceUp() }.getOrDefault(false)) { m.notify(CallCardService.NOTIFICATION_ID,n);noticeMerged=true }
            else { m.notify(NOTICE_TAG,NOTICE_ID,n);noticeMerged=false }
            noticeUp=true
        }.onFailure(::warn)
    }
    private fun cancelNotice(c:Context) {
        runCatching {
            val m=c.getSystemService(NotificationManager::class.java) ?: return@runCatching
            if(noticeMerged) {
                // The service's slot gets its own quiet words back (a foreground notice cannot be taken away while it runs).
                if(runCatching { serviceUp() }.getOrDefault(false)) m.notify(CallCardService.NOTIFICATION_ID,serviceNotice(c)) else m.cancel(CallCardService.NOTIFICATION_ID)
            } else m.cancel(NOTICE_TAG,NOTICE_ID)
        }
        noticeUp=false;noticeMerged=false
    }
    /** The service's own notice, as [CallCardService] posts it. */
    private fun serviceNotice(c:Context):Notification =Notification.Builder(c,CallCardService.CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
        .setContentTitle("통화 중에 이음 카드를 보여 드려요").setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_CALL).setShowWhen(false).build()

    /** "3일 전 통화, 30일간 2번" for [phoneKey] from the phone's call log (the last 30 days), or null (no permission, not there). */
    private fun callLogLine(c:Context,phoneKey:String):String? {
        if(c.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED) return null
        val now=System.currentTimeMillis();var last:Long?=null;var n=0
        c.contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.DATE),"${CallLog.Calls.DATE}>=?",
            arrayOf((now-30*CallCardView.Content.DAY_MS).toString()),"${CallLog.Calls.DATE} DESC")?.use { cur ->
            var i=0
            while(cur.moveToNext() && i++<2_000) if(PhoneKey.of(cur.getString(0))==phoneKey) { if(last==null) last=cur.getLong(1);n++ }
        }
        return last?.let { CallCardView.Content.thinLine(it,n,now) }
    }

    /** Host tests: back to the start. */
    internal fun resetForHost() {
        main.removeCallbacks(autoCollapse);collapseQueued=false;main.removeCallbacks(judgeLater);judged=true
        app?.let { c -> view?.let { runCatching { c.getSystemService(WindowManager::class.java)?.removeViewImmediate(it) } };stopWaiting(c) }
        view=null;params=null;noticeUp=false;noticeMerged=false;forgetCall()
        state=CallSession.State.IDLE;app=null
        canDraw={ Settings.canDrawOverlays(it) };locked={ c -> c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked==true }
        logLine=::callLogLine;outcome={ c,w -> CardStats.record(c,w) };serviceUp={ CallCardService.running!=null }
        onExpanded={ CardStats.expanded(it) };onClosed={ c,ms -> CardStats.closed(c,ms) };clock={ SystemClock.elapsedRealtime() }
        drawnNow={ it.seen };addWindow={ wm,v,p -> wm.addView(v,p) };probe={ c -> runCatching { CardProbe.read(c) }.getOrNull() }
        onVerdict={ c,a -> CardStats.verdict(c,a) }
    }
}
