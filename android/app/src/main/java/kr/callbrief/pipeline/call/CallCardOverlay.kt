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
 * - v4.7 r28-1: a call with someone already called today whose call is not sorted out yet ([RepeatCalls]) has that line on top of
 *   the card and of the notice ("오늘 09:12에 통화했어요(3분). 아직 정리 중이에요"); a number without a card gets a card of that
 *   line only, also with [KEY_UNKNOWN] off. Read from the call's settings snapshot and the card: nothing more on the ringing path.
 * - On the locked screen the notice shows the name, company and one check item unless "잠금 화면에서도 이름과 한 줄 보기" is off.
 *   A personal or excluded number ([CallSession.Snapshot.ignored]): nothing at all.
 * - [CardStats.expanded] when the person opens the strip again, [CardStats.closed] when they close the card.
 * - v4.0 r21-2 (DESIGN_V8 6 v4.2): a window that could not be added (an exception of `addView`) or was added but not seen
 *   ([CardStats.HIDDEN]) is put up again [RETRY_DELAYS_MS] later (0.6 s, then 2 s after the next failure), the same view and
 *   window parameters, only while the call still rings or is under way and the card was not closed; the notice stays up in
 *   between. Seen on a later try: [CardStats.DRAWN] with [CAUSE_OK_RETRY] and [CardStats.RETRIED]. Every failure moment goes to
 *   [CardStats.failure] as one cause code ([failCode]: what failed, the overlay check, the raw app-op mode, a cold process,
 *   the lock, the screen, and on a cold process how the last one ended).
 * - v4.3 r24-1 (DESIGN_V8 6-3): Samsung's Telecom denies the overlay app-op of apps from unknown sources while a phone call goes on,
 *   so a window put up while the check says no is refused (`add:BadToken:d0`) every time, also 0.6 s and 2 s later (the
 *   watched phone: `unconfirmed` 0 in every call so far, i.e. a window was never seen while the check said no). Now a call
 *   whose check says no when the card is first put up gets no window and no later try: the notice at once ([CAUSE_DENIED]).
 *   A later try is left only for a window that failed while the check said yes. The notice is the card where the card may not
 *   be drawn: the card's title and first line, its open check items (at most three) and the last call's line with its day,
 *   silent, and a tap opens the person's page (else the check list) without touching the call ([tapIntent]). */
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
    /** v3.6.1: the window not seen this long after it was added is taken for hidden. v3.6.1 fix (review, medium): 1.5 s -> 3 s
     * (a cold start or the ring's CPU rush can hold the first frame past 1.5 s), and a hidden window is tried once more when the
     * call's state changes ([retryLeft]). */
    const val HIDDEN_AFTER_MS=3_000L
    /** v3.6.1 cause codes ([CardStats.Attempt.cause]); an exception of `addView` is `addView:<its class>`. */
    const val CAUSE_OK="ok";const val CAUSE_DRAWN_DESPITE_FALSE="drawnDespiteFalse";const val CAUSE_SHORT="short"
    /** v3.6.1 fix: seen on the one second try after the first window was taken for hidden. v4.0 r21-2: seen on a later try
     * ([RETRY_DELAYS_MS]) after the window could not be added or was hidden. */
    const val CAUSE_OK_RETRY="okRetry"
    /** v4.0 r21-2: after a failure the window is put up again this much later, once per entry (0.6 s, then 2 s). */
    val RETRY_DELAYS_MS=listOf(600L,2_000L)
    /** v4.0 r21-2: a window put up again is judged this long after it was added. Review L5: the same 3 s as the first try (a
     * slow moment can hold the first frame past 1.5 s, and a window about to draw would be taken away); shorter once numbers say so. */
    const val RETRY_HIDDEN_MS=HIDDEN_AFTER_MS
    /** Added while the screen was locked and never seen until the call ended (the notice was beside it). */
    const val CAUSE_HIDDEN_LOCKED="hiddenLocked"
    /** v4.3 r24-1: the overlay check said no when the card was first put up: no window, the notice at once (a fallback). */
    const val CAUSE_DENIED="denied"
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
    /** v4.0 r21-2: one failure moment's cause code ([failCode]), on the card's thread. Replaceable in host tests. */
    @Volatile internal var onFailure:(Context,String)->Unit={ c,code -> CardStats.failure(c,code) }
    /** v4.0 r21-2: the screen is on ([android.os.PowerManager.isInteractive]). Replaceable in host tests. */
    @Volatile internal var screenOn:(Context)->Boolean={ c -> c.getSystemService(android.os.PowerManager::class.java)?.isInteractive!=false }
    /** v4.3 r24-1: the card's notice was posted for the first time in this call (once per call). Replaceable in host tests. */
    @Volatile internal var onNotice:(Context)->Unit={ c -> CardStats.noticeShown(c) }
    /** v4.0 r21-2: how this app's last process ended (reason, epoch ms), read once a process on the card's thread. Replaceable in host tests. */
    @Volatile internal var lastExit:(Context)->Pair<Int,Long>?={ c -> CardProbe.lastExit(c) }

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
    /** No window now in this call (it could not be added, or it was hidden; a try may still be due: [retryLater]). */
    private var windowGone=false
    /** v3.6.1 fix (review, medium), v4.0 r21-2: the window failed and a later try is due; the verdict waits for it (or the
     * call's end), so a call is still told once. */
    private var hiddenPending=false
    /** v4.0 r21-2: the tries of this call after a failure ([RETRY_DELAYS_MS]), and the window now up is one of them. */
    private var retries=0
    private var retrying=false
    /** v4.0 r21-2: the last failure's code ([CardStats.HIDDEN] or `addView:<class>`), and every failure of this call ("-" between). */
    private var lastFail:String?=null
    private var fails=""
    /** v4.0 r21-2: the phone at this call's first failure, for its `recent` entry. */
    private var failScreenOn:Boolean?=null;private var failState:String?=null
    /** The window kept for a later try (taken away or refused), so the card is the same and never on the screen twice. */
    private var spare:Pair<CallCardView,WindowManager.LayoutParams>?=null
    /** [onVerdict] was told; the window was seen. */
    private var judged=false
    private var seenOnce=false
    /** The phone's state read after the window was added (the card's thread). */
    @Volatile private var probed:CardStats.Probe?=null
    private val judgeLater=Runnable { app?.let { runCatching { judge(it,ending=false) }.onFailure(::warn) } }
    private val retryLater=Runnable { app?.let { runCatching { retryNow(it) }.onFailure(::warn) } }

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
        main.removeCallbacks(judgeLater);addedAt=0L;canDrawAtAdd=true;lockedAtAdd=false;addError=null;windowGone=false;judged=false;seenOnce=false;probed=null;coldAtAdd=false;outAtAdd=false
        main.removeCallbacks(retryLater);hiddenPending=false;retries=0;retrying=false;lastFail=null;fails="";failScreenOn=null;failState=null;spare=null
        noticeTold=false;seen=Seen();repeatTold=false }
    /** v4.3 r24-1: [onNotice] was told for this call (also: the first notice of the call may alert, r24 review 1). */
    private var noticeTold=false
    /** r24 review 5: this call was not drawn (a fallback or unconfirmed verdict) and its notice was found up: counted once as
     * [CardStats.FALLBACK_SEEN]. One object per call, so a check that comes back after the call ended still counts for it. */
    private class Seen { var notDrawn=false;var noticeFound=false;var told=false }
    private var seen=Seen()
    private fun seenNow(c:Context,s:Seen) { if(s.notDrawn && s.noticeFound && !s.told) { s.told=true;worker.execute { runCatching { onFallbackSeen(c) }.onFailure(::warn) } } }
    /** r24 review 5: a call not drawn whose notice was seen. Replaceable in host tests. */
    @Volatile internal var onFallbackSeen:(Context)->Unit={ c -> CardStats.fallbackSeen(c) }

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
        // v4.7 r28-1: today's line, from the call's settings snapshot and this card (nothing more is read).
        val repeat=runCatching { repeatLine(c,phoneKey) }.getOrNull()
        if(card!=null) { deliver(c,gen,CallCardView.Content.of(card).withRepeat(repeat));return }
        val settings=runCatching { CallSession.callSettings(c) }.getOrNull()
        if(settings?.optBoolean(KEY_UNKNOWN,false)!=true) {
            // v4.7 r28-1: a number without a card that was called today and is not sorted out yet: a card of that line only.
            if(repeat!=null) { deliver(c,gen,CallCardView.Content.repeatOnly(repeat));return }
            main.post { if(gen==generation) { loading=false;tell(c,NO_CARD) } };return
        }
        // The first line at once, the call log's line once read.
        deliver(c,gen,CallCardView.Content.unknown(null).withRepeat(repeat),rest=false)
        val line=runCatching { logLine(c,phoneKey) }.getOrNull()
        deliver(c,gen,CallCardView.Content.unknown(line).withRepeat(repeat))
    }
    /** v4.7 r28-1: today's line of [phoneKey] ([RepeatCalls.forCall]), or null. Replaceable in host tests. */
    @Volatile internal var repeatLine:(Context,String)->String?={ c,k -> RepeatCalls.forCall(c,k)?.line }
    /** v4.7 r28-2: today's line went up in this call's window or notice (once per call, [CardStats.repeatShown]). Replaceable in host tests. */
    @Volatile internal var onRepeatShown:(Context)->Unit={ c -> CardStats.repeatShown(c) }
    private var repeatTold=false
    private fun deliver(c:Context,gen:Int,data:CallCardView.Content,rest:Boolean=true) { main.post {
        runCatching {
            if(gen!=generation || closed || state==CallSession.State.IDLE) return@post
            val first=content==null
            content=data;loading=false
            if(first) { place(c,nameOnly=true);shownAt=clock();tell(c,firstOutcome())
                if(data.repeat!=null && !repeatTold && (view!=null || noticeUp)) { repeatTold=true;worker.execute { runCatching { onRepeatShown(c) }.onFailure(::warn) } } }
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
        // v4.3 r24-1: the check says no (Samsung's in-call restriction, DESIGN_V8 6-3): no window, the notice at once.
        if(view==null && !windowGone) { if(draw) addCard(c,data,nameOnly,draw,isLocked) else deny(c,isLocked) }
        when {
            view==null -> notice(c,data)
            isLocked -> { notice(c,data);waitForUnlock(c) }
            // v3.6.1 fix (review, critical): added while the check said no, the window may be hidden by the system without the
            // view hearing of it: the notice stays until the call ends, seen or not.
            // r24 review 4: the check saying no now (also after the card was seen once): the notice, whatever was seen before.
            !canDrawAtAdd || !draw -> notice(c,data)
            // The second try after a hidden window: the notice stays until that window is seen.
            retrying && !seenOnce -> notice(c,data)
            noticeUp -> cancelNotice(c)
        }
    }
    /** v4.3 r24-1: the overlay check says no as the window is due: none is added (it would be refused, DESIGN_V8 6-3) and none
     * is tried later; the notice shows ([place]) and the call is told at once as a fallback, [CAUSE_DENIED] (or, on a later try
     * after a failure with the check saying yes, that failure's cause). */
    private fun deny(c:Context,isLocked:Boolean) {
        canDrawAtAdd=false;lockedAtAdd=isLocked
        if(!retrying) { coldAtAdd=runCatching { CardStats.callCold() }.getOrDefault(false);outAtAdd=outgoing() }
        windowGone=true;spare=null
        main.removeCallbacks(retryLater)
        if(judged) return
        judged=true
        val cause=if(hiddenPending) lastFail ?: CAUSE_DENIED else CAUSE_DENIED
        hiddenPending=false
        Log.i(LOG,"card not drawn: the check says no")
        verdict(c,CardStats.FALLBACK,cause,null)
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
            windowGone=true
            runCatching { c.getSystemService(WindowManager::class.java)?.removeViewImmediate(v) }
            spare=params?.let { v to it }
            view=null;params=null
            Log.i(LOG,"card hidden")
            content?.let { notice(c,it) }
            // v4.0 r21-2: put up again later while the call goes on; told now when no try is left.
            fail(c,CardStats.HIDDEN)
            return
        }
        judged=true
        if(seen) {
            seenOnce=true;hiddenPending=false
            Log.i(LOG,"card drawn")
            // v3.6.1 fix (review, critical): seen although the check said no is not trusted as drawn (the system may hide the
            // window on its side only): unconfirmed, and the notice stays until the call ends.
            if(canDrawAtAdd) verdict(c,CardStats.DRAWN,if(retrying) CAUSE_OK_RETRY else CAUSE_OK,(clock()-addedAt).coerceAtLeast(0))
            else verdict(c,CardStats.UNCONFIRMED,CAUSE_DRAWN_DESPITE_FALSE,(clock()-addedAt).coerceAtLeast(0))
            // v4.6 r27-3 (review H2): and the check still says yes now: a notice put up because it turned to no after the window
            // was added stays (an error reading it counts as no).
            if(canDrawAtAdd && !ending && noticeUp && !runCatching { locked(c) }.getOrDefault(false) && runCatching { canDraw(c) }.getOrDefault(false)) cancelNotice(c)
        } else if(hiddenPending) { hiddenPending=false;verdict(c,CardStats.FALLBACK,lastFail ?: CardStats.HIDDEN,null) }
        else verdict(c,if(noticeUp) CardStats.FALLBACK else CardStats.GONE,if(lockedAtAdd) CAUSE_HIDDEN_LOCKED else CAUSE_SHORT,null)
    }
    /** v4.0 r21-2: one failure moment ([CardStats.HIDDEN] or `addView:<class>`): its cause code goes to [onFailure], and the
     * window is put up again after the next [RETRY_DELAYS_MS] while the call rings or is under way (the verdict waits); with no
     * try left (or the call over, or the card closed) the call is told now as a fallback. */
    private fun fail(c:Context,cause:String) {
        lastFail=cause;fails=if(fails.isEmpty()) cause else "$fails-$cause"
        val draw=runCatching { canDraw(c) }.getOrDefault(false);val lock=runCatching { locked(c) }.getOrDefault(false)
        val on=runCatching { screenOn(c) }.getOrDefault(true)
        if(failState==null) { failScreenOn=on;failState=state.name }
        val cold=runCatching { CardStats.elapsed()-CardStats.processStart()<CardStats.COLD_MS }.getOrDefault(false)
        worker.execute { runCatching {
            val p=runCatching { probe(c) }.getOrNull()
            onFailure(c,failCode(cause,draw,p?.mode ?: -1,cold,lock,on,if(cold) exitOnce(c) else null,System.currentTimeMillis(),p?.raw ?: -1,p?.update,p?.installer))
        }.onFailure(::warn) }
        val live=state==CallSession.State.RINGING || state==CallSession.State.OFFHOOK
        // v4.3 r24-1: tried again only while the check says yes (a window it refuses is refused again, DESIGN_V8 6-3).
        if(draw && retries<RETRY_DELAYS_MS.size && live && !closed && content!=null) {
            hiddenPending=true;windowGone=true
            main.removeCallbacks(retryLater);main.postDelayed(retryLater,RETRY_DELAYS_MS[retries])
        } else { judged=true;hiddenPending=false;verdict(c,CardStats.FALLBACK,cause,null) }
    }
    /** v4.0 r21-2: the window again, the same one ([spare]); nothing when the call is over, the card closed or already seen. */
    private fun retryNow(c:Context) {
        val live=state==CallSession.State.RINGING || state==CallSession.State.OFFHOOK
        if(judged || closed || !live || content==null || view!=null) return
        retries++;retrying=true;windowGone=false;addError=null
        Log.i(LOG,"card tried again ($retries)")
        place(c);relayout();afterState()
    }
    /** How this app's last process ended, read at most once per process (a binder call, on the card's thread). */
    @Volatile private var exitRead:Pair<Int,Long>?=null;@Volatile private var exitDone=false
    private fun exitOnce(c:Context):Pair<Int,Long>? { if(!exitDone) { exitRead=runCatching { lastExit(c) }.getOrNull();exitDone=true };return exitRead }
    private fun verdict(c:Context,result:String,cause:String,drawMs:Long?) {
        if(result==CardStats.FALLBACK || result==CardStats.UNCONFIRMED) { val s=seen;s.notDrawn=true;seenNow(c,s) }
        val after=runCatching { canDraw(c) }.getOrNull()
        val base=CardStats.Attempt(result,cause,canDrawAtAdd,after,null,lockedAtAdd,coldAtAdd,drawMs,(addError ?: lastFail?.takeIf { it.startsWith("addView:") }).takeIf { result!=CardStats.DRAWN },outAtAdd,
            retries=retries,fails=fails.ifEmpty { null },screenOn=failScreenOn,callState=failState)
        // v4.0 r21-2: a call not drawn without a failure moment of its own (seen although the check said no, gone before it was
        // seen with the notice up, hidden behind the lock) gets its cause code too.
        val own=result!=CardStats.DRAWN && result!=CardStats.GONE && fails.isEmpty()
        val on=if(own) runCatching { screenOn(c) }.getOrDefault(true) else true
        val draw=canDrawAtAdd;val cold=coldAtAdd;val lock=lockedAtAdd
        // On the card's thread, after the probe read when the window was added.
        worker.execute { runCatching {
            val ex=if(cold) exitOnce(c) else null
            val a=base.copy(probe=probed ?: runCatching { probe(c) }.getOrNull(),exit=ex?.first,exitAgo=ex?.let { agoBucket(System.currentTimeMillis()-it.second) })
            onVerdict(c,a)
            if(own) onFailure(c,failCode(cause,draw,a.probe?.mode ?: -1,cold,lock,on,ex,System.currentTimeMillis(),a.probe?.raw ?: -1,a.probe?.update,a.probe?.installer))
        }.onFailure(::warn) }
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
        if(!retrying) { coldAtAdd=runCatching { CardStats.callCold() }.getOrDefault(false);outAtAdd=outgoing() }
        // v4.0 r21-2: a later try puts up the same window again (never two cards).
        spare?.let { (v,p) -> spare=null
            runCatching { wm.removeViewImmediate(v) } // not attached: nothing to take away
            v.maxHeightPx=maxHeight(c,v);if(!v.complete) { if(nameOnly) v.showName(data) else v.showAll(data) }
            attach(c,wm,v,p,data,draw);return }
        val dm=c.resources.displayMetrics
        val v=CallCardView(c).apply {
            maxHeightPx=maxHeight(c,this)
            onCollapse={ collapse() };onExpand={ expand();runCatching { onExpanded(c) } };onClose={ close() }
            onMoveBy={ dy -> moveBy(dy) };onMoveEnd={ savePosition() }
            // r24 review 4: a window that stops being visible during the call: the notice when the check now says no or the lock is on.
            onHidden={ main.post { runCatching { val x=app;if(x!=null && view===this && !closed && content!=null && state!=CallSession.State.IDLE) place(x) }.onFailure(::warn) } }
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
        attach(c,wm,v,p,data,draw)
    }
    private fun attach(c:Context,wm:WindowManager,v:CallCardView,p:WindowManager.LayoutParams,data:CallCardView.Content,draw:Boolean) {
        val gen=generation
        if(!retrying) worker.execute { runCatching { val pr=probe(c);if(gen==generation) probed=pr }.onFailure(::warn) }
        // v3.6.1 (K2): the system's refusal is kept as a cause code, not swallowed (the notice shows instead).
        // v4.0 r21-2: and the window is put up again a little later ([fail]).
        try { addWindow(wm,v,p) } catch(e:Exception) {
            addError="addView:${e.javaClass.simpleName}";windowGone=true;spare=v to p
            Log.w(LOG,"card refused: ${e.javaClass.simpleName} canDraw=$draw")
            fail(c,addError!!)
            return
        }
        view=v;params=p;addedAt=clock()
        main.postDelayed(judgeLater,if(retrying) RETRY_HIDDEN_MS else HIDDEN_AFTER_MS)
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
        if(noticeUp && view!=null && canDrawAtAdd && (seenOnce || runCatching { canDraw(c) }.getOrDefault(false))) cancelNotice(c)
        // v3.6.1: a window not judged yet (the lock covered it) is judged a while after the unlock.
        if(!judged && view!=null) { main.removeCallbacks(judgeLater);main.postDelayed(judgeLater,HIDDEN_AFTER_MS) }
        if(state==CallSession.State.IDLE || closed || content==null) return
        place(c);view?.let { v -> content?.let { if(!v.complete) v.showAll(it) } };relayout();afterState()
    }
    /** The heads-up notice of [data] (also what r11-1's service may show as its own notice). */
    fun buildNotice(c:Context,data:CallCardView.Content,alertOnce:Boolean=true):Notification {
        val m=c.getSystemService(NotificationManager::class.java)
        m?.createNotificationChannel(NotificationChannel(CHANNEL,CHANNEL_NAME,NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null);enableVibration(false);setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        // v4.3 r24-1: the card's order. Folded: the title and the first line; open: the open items, then the last call. Big
        // text, not an inbox: an inbox cuts every line with an ellipsis (the user's rule: nothing cut off), big text wraps.
        val title=data.noticeTitle()
        val big=Notification.BigTextStyle().setBigContentTitle(title).bigText(data.noticeBody().joinToString("\n"))
        data.noticeSummary()?.let { big.setSummaryText(it) }
        return Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setColor(accent(c)).setOnlyAlertOnce(alertOnce)
            .setContentTitle(title).setContentText(data.noticeText())
            .setStyle(big)
            // Kept: no Samsung paper says how it treats a call-category notice of another app during a call (DESIGN_V8 6-3, r24-1).
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setLocalOnly(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(tapIntent(c,data))
            .setPublicVersion(publicNotice(c,data,lockDetail(c)))
            .build()
    }
    /** r24 review 6: the action of the notice's tap ([tapIntent]). */
    const val TAP_ACTION="kr.callbrief.CALL_CARD"
    /** v4.3 r24-1: the app's accent (light set: the notice shade is the phone's own). */
    private fun accent(c:Context):Int { CallUi.applyTheme(c);return CallUi.ACCENT }
    /** v4.3 r24-1: what a tap on the notice opens: the person's page when the card has a person ([MainActivity.EXTRA_PERSON_ID]),
     * else the check list; counted as [CardStats.NOTICE_TAPS] ([MainActivity.EXTRA_FROM_CALL_CARD]). The call is not touched:
     * the app comes in front of the call screen, the call goes on. */
    internal fun tapIntent(c:Context,data:CallCardView.Content):android.app.PendingIntent {
        val i=kr.callbrief.pipeline.MainActivity.intent(c,kr.callbrief.pipeline.Screen.TASKS).putExtra(kr.callbrief.pipeline.MainActivity.EXTRA_FROM_CALL_CARD,true)
            // Always put (empty without a person): the one PendingIntent is updated in place, and no person of an earlier call may stay.
            .putExtra(kr.callbrief.pipeline.MainActivity.EXTRA_PERSON_ID,data.personId ?: "")
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(TAP_ACTION) // r24 review 6: its own PendingIntent, never matched with another notice's
        return android.app.PendingIntent.getActivity(c,NOTICE_ID,i,android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    }
    /** "잠금 화면에서도 이름과 한 줄 보기" ([KEY_LOCK_DETAIL], on unless the user turned it off). */
    const val KEY_LOCK_DETAIL=kr.callbrief.pipeline.PipelineSettingsKeys.LOCK_SCREEN_DETAIL
    internal fun lockDetail(c:Context):Boolean =runCatching { (CallSession.callSettings(c) ?: SecureSettings(c).snapshot()).optBoolean(KEY_LOCK_DETAIL,true) }.getOrDefault(true)
    /** What the locked screen shows of the notice (DESIGN_V4 3.1-5, 1.3 #9). With [detail]: the name, the company and one check
     * item ([CallCardView.Content.publicText]: no number, no amount, nothing of the call's words). Without: no name, no summary. */
    internal fun publicNotice(c:Context,data:CallCardView.Content,detail:Boolean):Notification {
        val b=Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setColor(accent(c))
        return if(detail) b.setContentTitle(data.publicTitle()).setContentText(data.publicText() ?: "잠금을 풀면 카드가 보여요").build()
        else b.setContentTitle("이음 통화 카드").setContentText("잠금을 풀면 카드가 보여요").build()
    }
    private fun notice(c:Context,data:CallCardView.Content) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            worker.execute { runCatching { CardStats.fallbackNotice(c,false,true) } };return } // v4.1 r22-2: muted
        runCatching {
            val m=c.getSystemService(NotificationManager::class.java) ?: return
            // r24 review 1: the call's first notice may alert (a heads-up; the channel has no sound or vibration), later
            // drawings of it do not: an update with "only alert once" may not come up as a heads-up again.
            val n=buildNotice(c,data,alertOnce=noticeTold)
            val merge=runCatching { serviceUp() }.getOrDefault(false)
            // r24 review 3: the slot changed during the call (the service came or went): the old one goes first, so no
            // ongoing notice is left behind when the call ends.
            if(noticeUp && noticeMerged!=merge) cancelNotice(c)
            // One notice while the service runs: the card's takes the service's slot (4.2-1).
            if(merge) { m.notify(CallCardService.NOTIFICATION_ID,n);noticeMerged=true }
            else { m.notify(NOTICE_TAG,NOTICE_ID,n);noticeMerged=false }
            noticeUp=true
            // v4.3 r24-1: the first notice of this call: the time from the call's start (CardStats knows it).
            if(!noticeTold) { noticeTold=true;worker.execute { runCatching { onNotice(c) }.onFailure(::warn) } }
            // v4.1 r22-2: a second later, whether the notice is really up and whether it could be heard (counts only).
            val merged=noticeMerged;val s=seen
            main.postDelayed({ worker.execute { runCatching { if(noticeCheck(c,merged)) main.post { s.noticeFound=true;seenNow(c,s) } }.onFailure(::warn) } },NOTICE_CHECK_MS)
        }.onFailure(::warn)
    }
    /** v4.1 r22-2: how long after posting the fallback notice it is looked for among the active ones. */
    const val NOTICE_CHECK_MS=1_000L
    /** v4.1 r22-2: the fallback notice found among this app's active notices, and notices off or the card's channel below
     * high importance (no heads-up: the person may never see it). Nothing is changed in the settings. Replaceable in host tests. */
    private val defaultNoticeCheck:(Context,Boolean)->Boolean={ c,merged ->
        val m=c.getSystemService(NotificationManager::class.java)
        val shown=m?.activeNotifications?.any { if(merged) it.id==CallCardService.NOTIFICATION_ID else it.id==NOTICE_ID && it.tag==NOTICE_TAG }==true
        val channel=m?.getNotificationChannel(CHANNEL) // the card's notice is in its own channel also in the service's slot
        val muted=m==null || !m.areNotificationsEnabled() || channel!=null && channel.importance<NotificationManager.IMPORTANCE_HIGH
        CardStats.fallbackNotice(c,shown,muted)
        shown
    }
    /** Returns whether the notice was found up (r24 review 5). */
    @Volatile internal var noticeCheck:(Context,Boolean)->Boolean=defaultNoticeCheck
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

    /** v4.0 r21-2: one failure moment as one short code (at most 40 characters, the heartbeat's key length): what failed
     * (`hidden` or `add:<exception class without "Exception">`), then `:d0` when the overlay check said no, `:m<mode>` when
     * the raw app-op mode was read and is not 0 (allowed), `:cold` within [CardStats.COLD_MS] of the process start, `:lk`
     * on a locked screen, `:off` with the screen off, and on a cold process `:x<reason>` how the last process ended
     * ([android.app.ApplicationExitInfo] REASON_*, 10 = the user, as from the recent apps) with `:<bucket>` how long ago
     * ([agoBucket]). E.g. `add:BadToken:cold:x10:m5`. v4.1 r22-2: after the mode, `:r<raw>` when the raw mode was read and
     * differs from it (the system's restriction, not the user's setting), the time since the last install (`:u1` … `:uo`,
     * [CardProbe.updateBucket]) and the installer's kind (`:is` … `:io`, [CardProbe.installerKind]):
     * `add:BadToken:d0:m2:r0:u48:is`. */
    internal fun failCode(cause:String,draw:Boolean,mode:Int,cold:Boolean,locked:Boolean,screenOn:Boolean,exit:Pair<Int,Long>?,now:Long,
                          raw:Int=-1,update:String?=null,installer:String?=null):String {
        val base=if(cause.startsWith("addView:")) "add:"+cause.removePrefix("addView:").removeSuffix("Exception").take(16) else cause.take(16)
        val items=buildList {
            if(!draw) add(":d0")
            if(mode>0) add(":m$mode")
            if(raw>=0 && mode>=0 && raw!=mode) add(":r$raw")
            update?.let { add(":$it") };installer?.let { add(":$it") }
            if(cold) add(":cold")
            if(locked) add(":lk")
            if(!screenOn) add(":off")
            if(cold && exit!=null) add(":x${exit.first}:${agoBucket(now-exit.second)}")
        }
        // Review L6: past 40 characters whole items go from the end (":x10" never becomes ":x1").
        var out=base
        for(i in items) if(out.length+i.length<=40) out+=i else break
        return out
    }
    /** How long ago, in buckets: m5 (under 5 minutes), m30, h2, h12 (under 12 hours), old. */
    internal fun agoBucket(ms:Long):String = when { ms<5*60_000L -> "m5";ms<30*60_000L -> "m30";ms<2*3_600_000L -> "h2";ms<12*3_600_000L -> "h12";else -> "old" }

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
        onVerdict={ c,a -> CardStats.verdict(c,a) };onFailure={ c,code -> CardStats.failure(c,code) }
        screenOn={ c -> c.getSystemService(android.os.PowerManager::class.java)?.isInteractive!=false };lastExit={ c -> CardProbe.lastExit(c) }
        exitRead=null;exitDone=false;noticeCheck=defaultNoticeCheck;onNotice={ c -> CardStats.noticeShown(c) };onFallbackSeen={ c -> CardStats.fallbackSeen(c) }
        repeatLine={ c,k -> RepeatCalls.forCall(c,k)?.line };onRepeatShown={ c -> CardStats.repeatShown(c) }
    }
}
