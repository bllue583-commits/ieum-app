package kr.callbrief.pipeline.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kr.callbrief.R
import kr.callbrief.pipeline.ContactCards
import kr.callbrief.pipeline.PipelineSettingsKeys

/** The call card's foreground service (design v3.0 7.2, r11-1): alive only while a call rings or goes on, so the card can be
 * drawn over the call screen for as long as the call lasts (a receiver has ten seconds). Type `phoneCall`; when the system
 * refuses that type, `specialUse`. The one exception to "no foreground service" (v2.0): it runs only during a call and its
 * notification is about that call.
 *
 * Life: [CallStateReceiver] hands every new call state to [follow] (on the session's thread). RINGING or OFFHOOK with a card
 * wanted ([wanted]) starts the service; IDLE (or a state that wants no card) hides the card and the service stops itself
 * [STOP_AFTER_IDLE_MS] later. Every [CHECK_MS] the service asks the phone whether a call still goes on and stops when none
 * does (the IDLE broadcast was missed); [MAX_LIFE_MS] is the hard end.
 *
 * The card itself is r11-2's [CallCardOverlay] (the overlay window, or a heads-up notification when the overlay may not be
 * drawn or the screen is locked); this service only says when: `show(context, phoneKey, state)` on RINGING and on OFFHOOK,
 * `collapse()` [COLLAPSE_AFTER_MS] after OFFHOOK, `hide()` on IDLE. Personal and excluded numbers ([CallSession.Snapshot.ignored]):
 * nothing, not even the service. No number is put in an Intent or a log; the service reads the key from [CallSession.current].
 *
 * v3.4 (DESIGN_V4 4.1-1, 4.1-3, 4.1-4, r14-1): a call going out no longer needs the call redirection role (the number comes
 * with the OFFHOOK broadcast; the role only brings it sooner and stays an option in settings). The settings are the call's
 * memory snapshot ([CallSession.callSettings]), not read again for every state. During call waiting ([CallSession.Snapshot.waiting])
 * the card goes and nothing shows until IDLE. A start Android refuses is counted ([CardStats.fgsRefused]); a running service
 * hears later states through a static reference in this process ([running]) instead of another start. The service starts
 * whether or not the overlay may be drawn: the notice instead of the card needs the process too. */
class CallCardService:Service() {
    /** What the service tells the card. [CallCardOverlay] by default; replaceable in host tests. */
    internal interface Card {
        fun show(context:Context,phoneKey:String?,state:CallSession.State)
        fun collapse()
        fun expand()
        fun hide()
    }

    companion object {
        private const val TAG="CallCardService"
        /** The service stays this long after the call ended (a call right after reuses it), then stops. */
        const val STOP_AFTER_IDLE_MS=3_000L
        /** After the call is answered (or placed), the card folds into a band this much later (design 7.1). */
        const val COLLAPSE_AFTER_MS=3_000L
        /** How often the service checks that a call still goes on (design: 10 minutes). */
        const val CHECK_MS=10*60_000L
        /** Never longer than this, whatever the phone says. */
        const val MAX_LIFE_MS=4*3_600_000L
        /** The service's own quiet notification; r11-2 may post its lock-screen card under the same id to replace it. */
        const val NOTIFICATION_ID=741
        const val CHANNEL="ieum.calls.cardservice"
        internal const val EXTRA_STATE="state"
        internal const val EXTRA_SHOW="show"

        private var mainHandler:Handler?=null
        /** The main thread's handler (made again when the main looper is a new one, as in host tests). */
        private val main:Handler get()=synchronized(this) {
            mainHandler?.takeIf { it.looper==Looper.getMainLooper() } ?: Handler(Looper.getMainLooper()).also { mainHandler=it } }
        /** The service was asked to start and has not ended yet. */
        @Volatile internal var active=false
        @Volatile internal var card:Card=Overlay
        /** A call goes on (TelecomManager.isInCall, needs READ_PHONE_STATE). Replaceable in host tests. */
        @Volatile internal var inCall:(Context)->Boolean=::telecomInCall
        /** The call redirection role is held (calls that go out give their number sooner; optional since v3.4, counted as
         * [CardStats.NO_ROLE] only). Replaceable in host tests. */
        @Volatile internal var roleHeld:(Context)->Boolean=::redirectionRoleHeld
        /** The number has a contact card (v2.8 [ContactCards]). Replaceable in host tests. */
        @Volatile internal var hasCard:(Context,String)->Boolean={ c,k -> ContactCards.get(c,k)!=null }
        /** Starts the service; throws when Android refuses a start from the background. Replaceable in host tests. */
        @Volatile internal var startService:(Context,Intent)->Unit={ c,i -> c.startForegroundService(i) }
        /** Host tests: how long the running service has been up is measured with this. */
        @Volatile internal var clock:()->Long={ System.currentTimeMillis() }

        /** The card on the main thread, shared by the service and the direct path (when Android refuses the service). */
        internal val driver=Driver()
        /** v3.4 (4.1-4): the service of this process once it is up; later states go to it directly (no new start). */
        @Volatile internal var running:CallCardService?=null

        /** One call state, from [CallStateReceiver] (the session's thread). Starts the service when a card is wanted and it is
         * not running; tells a running one the state in any case. When the start is refused (Android 12 and later from the
         * background), the card is shown directly and the start is tried again once it is up. */
        fun follow(context:Context,snap:CallSession.Snapshot) {
            val app=context.applicationContext ?: context
            val verdict=runCatching { verdict(app,snap) }.getOrNull()
            val show=verdict==SHOW
            // r11-3: calls dropped here never reach the card, so they are counted here (once per call, see CardStats).
            runCatching { stats(app,snap,verdict) }
            if(!active && !show && !driver.shown) return
            val intent=Intent(app,CallCardService::class.java).putExtra(EXTRA_STATE,snap.state.name).putExtra(EXTRA_SHOW,show)
            if(!active && snap.state==CallSession.State.IDLE) {
                // Nothing running but a card shown directly: hide it.
                main.post { driver.apply(app,CallSession.State.IDLE,null,false) };return
            }
            // Already up in this process: tell it directly.
            running?.let { svc -> val state=snap.state;main.post { svc.onState(state,show) };return }
            val started=runCatching { startService(app,intent);true }.onFailure { Log.w(TAG,"start refused: ${it.javaClass.simpleName}") }.getOrDefault(false)
            if(started) { active=true;return }
            runCatching { CardStats.fgsRefused(app) }
            val key=snap.phoneKey
            main.post {
                driver.apply(app,snap.state,key,show)
                // With the card on the screen Android lets the service start (a visible overlay window).
                if(show) main.postDelayed({ if(!active && driver.shown) runCatching { startService(app,intent);active=true } },500L)
            }
        }

        /** Whether this call shows a card: not ignored, not IDLE, not call waiting, the number known, the switch for its
         * direction on, and a card for the number or the switch for numbers without one. */
        internal fun wanted(context:Context,snap:CallSession.Snapshot):Boolean=verdict(context,snap)==SHOW

        /** [wanted] with the reason (r11-3, for [CardStats]): [SHOW]; [CallCardOverlay.NO_NUMBER] (the switch is on but the number
         * is not known); [CallCardOverlay.NO_CARD] (no card for the number and numbers without one are off); [IGNORED] (personal
         * or excluded); [OFF] (the switch for the direction is off, or settings cannot be read); [WAITING] (call waiting); null
         * on IDLE. v3.4 (4.1-1): a call going out needs no role; only the number decides ([CallCardOverlay.NO_NUMBER]). */
        internal fun verdict(context:Context,snap:CallSession.Snapshot):String? {
            if(snap.state==CallSession.State.IDLE) return null
            if(snap.ignored) return IGNORED
            if(snap.waiting) return WAITING
            val s=runCatching { CallSession.callSettings(context) }.getOrNull() ?: return OFF
            val on=when(snap.direction) {
                CallSession.Direction.OUT -> s.optBoolean(PipelineSettingsKeys.CARD_OUTGOING,true)
                else -> s.optBoolean(PipelineSettingsKeys.CARD_INCOMING,true)
            }
            if(!on) return OFF
            val key=snap.phoneKey ?: return CallCardOverlay.NO_NUMBER
            return if(runCatching { hasCard(context,key) }.getOrDefault(false) || s.optBoolean(PipelineSettingsKeys.CARD_UNKNOWN,false)) SHOW
                else CallCardOverlay.NO_CARD
        }
        internal const val SHOW="show";internal const val IGNORED="ignored";internal const val OFF="off";internal const val WAITING="waiting"
        /** Counts what the service decided ([CardStats.follow]). Replaceable in host tests. */
        @Volatile internal var stats:(Context,CallSession.Snapshot,String?)->Unit={ c,snap,reason -> CardStats.follow(c,snap,reason) }

        /** Without READ_PHONE_STATE the phone cannot be asked: then the call counts as over (the service ends, no card stays). */
        private fun telecomInCall(context:Context):Boolean {
            if(context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return try { context.getSystemService(android.telecom.TelecomManager::class.java)?.isInCall==true } catch(_:SecurityException) { false }
        }
        private fun redirectionRoleHeld(context:Context):Boolean {
            if(Build.VERSION.SDK_INT<29) return false
            val rm=context.getSystemService(android.app.role.RoleManager::class.java) ?: return false
            return runCatching { rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_REDIRECTION) && rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_REDIRECTION) }.getOrDefault(false)
        }

        /** Host tests: back to the start. */
        internal fun resetForHost() {
            active=false;running=null;card=Overlay;inCall=::telecomInCall;roleHeld=::redirectionRoleHeld;clock={ System.currentTimeMillis() }
            hasCard={ c,k -> ContactCards.get(c,k)!=null };mainHandler=null
            startService={ c,i -> c.startForegroundService(i) }
            stats={ c,snap,reason -> CardStats.follow(c,snap,reason) }
            driver.reset()
        }
    }

    /** [CallCardOverlay] (r11-2) behind [Card]. */
    private object Overlay:Card {
        override fun show(context:Context,phoneKey:String?,state:CallSession.State)=CallCardOverlay.show(context,phoneKey,state)
        override fun collapse()=CallCardOverlay.collapse()
        override fun expand()=CallCardOverlay.expand()
        override fun hide()=CallCardOverlay.hide()
    }

    /** Shows, folds and hides the card as the states come; main thread only. A state already shown is not shown again (the
     * phone repeats each state), except when the number came late. */
    internal class Driver {
        @Volatile var shown=false;private set
        private var lastState:CallSession.State?=null
        private var lastKey:String?=null
        private val collapse=Runnable { if(shown && lastState==CallSession.State.OFFHOOK) runCatching { card.collapse() } }
        fun apply(context:Context,state:CallSession.State,key:String?,show:Boolean) {
            if(!show || state==CallSession.State.IDLE || key==null) {
                main.removeCallbacks(collapse)
                if(shown) runCatching { card.hide() }
                shown=false;lastState=null;lastKey=null;return
            }
            if(shown && state==lastState && key==lastKey) return
            runCatching { card.show(context,key,state) }.onFailure { Log.w(TAG,"card failed: ${it.javaClass.simpleName}") }
            if(state==CallSession.State.OFFHOOK && lastState!=CallSession.State.OFFHOOK) { main.removeCallbacks(collapse);main.postDelayed(collapse,COLLAPSE_AFTER_MS) }
            shown=true;lastState=state;lastKey=key
        }
        fun reset() { main.removeCallbacks(collapse);shown=false;lastState=null;lastKey=null }
    }

    private var startedMs=0L
    private val stop=Runnable { driver.apply(this,CallSession.State.IDLE,null,false);finish() }
    private val check=object:Runnable { override fun run() {
        if(clock()-startedMs>=MAX_LIFE_MS || !runCatching { inCall(this@CallCardService) }.getOrDefault(false)) {
            Log.i(TAG,"no call any more: stopping");driver.apply(this@CallCardService,CallSession.State.IDLE,null,false);finish();return
        }
        main.postDelayed(this,CHECK_MS)
    } }

    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate();active=true;startedMs=clock()
        main.postDelayed(check,CHECK_MS)
    }
    /** Up as a foreground service (the first start went through [foreground]). */
    private var up=false
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!up) {
            if(!foreground()) { runCatching { CardStats.fgsRefused(this) };driver.apply(this,CallSession.State.IDLE,null,false);finish();return START_NOT_STICKY }
            up=true;running=this
        }
        val state=runCatching { CallSession.State.valueOf(intent?.getStringExtra(EXTRA_STATE) ?: "") }.getOrNull() ?: CallSession.current().state
        onState(state,intent?.getBooleanExtra(EXTRA_SHOW,false)==true)
        return START_NOT_STICKY
    }
    /** One call state for the card (main thread): from a start's Intent, or directly from [follow] once the service is up. */
    internal fun onState(state:CallSession.State,show:Boolean) {
        // The number from the session itself (never through the Intent); a state that moved on since is handled by its own call.
        val key=CallSession.current().takeIf { it.state==state && !it.ignored }?.phoneKey
        driver.apply(this,state,key,show && key!=null)
        main.removeCallbacks(stop)
        if(!driver.shown) main.postDelayed(stop,STOP_AFTER_IDLE_MS)
    }
    override fun onDestroy() {
        main.removeCallbacks(stop);main.removeCallbacks(check)
        if(driver.shown) driver.apply(this,CallSession.State.IDLE,null,false)
        if(running===this) running=null
        active=false;super.onDestroy()
    }
    private fun finish() {
        main.removeCallbacks(stop);main.removeCallbacks(check)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if(running===this) running=null
        active=false;stopSelf()
    }

    /** Goes foreground as `phoneCall`, else `specialUse` (Android 14 and later), else without a type. False when all fail. */
    private fun foreground():Boolean {
        val n=notification()
        if(Build.VERSION.SDK_INT<29) return runCatching { startForeground(NOTIFICATION_ID,n);true }.getOrDefault(false)
        if(runCatching { startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);true }
                .onFailure { Log.w(TAG,"phoneCall refused: ${it.javaClass.simpleName}") }.getOrDefault(false)) return true
        if(Build.VERSION.SDK_INT>=34 && runCatching { startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);true }
                .onFailure { Log.w(TAG,"specialUse refused: ${it.javaClass.simpleName}") }.getOrDefault(false)) return true
        return false
    }
    private fun notification():Notification {
        val manager=getSystemService(NotificationManager::class.java)
        runCatching {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL,"통화 중 이음 카드",NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null,null);enableVibration(false);setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        }
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setContentTitle("통화 중에 이음 카드를 보여 드려요").setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_CALL).setShowWhen(false).build()
    }
}
