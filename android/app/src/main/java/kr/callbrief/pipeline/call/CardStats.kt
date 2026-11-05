package kr.callbrief.pipeline.call

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.ContactCards
import kr.callbrief.pipeline.PipelineSettingsKeys
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** How often the call card showed and why it did not (design v3.0 7.3, r11-3; v3.4 DESIGN_V4 4.1-5, r14-1): settings
 * [PipelineSettingsKeys.CARD_STATS], the heartbeat's `callCard`. One outcome per call, whoever tells it first: the card
 * ([CallCardOverlay.outcome] into [record]: shown, locked, no permission, no notifications) or the service, which drops calls
 * before they reach the card ([CallCardService.follow] into [follow]: no card for the number, no number, ignored, off).
 *
 * Counts ([KINDS]): `calls` (every call this version saw, so the heartbeat says 0 rather than null once one was seen),
 * `shown`, `shownOutgoing`, `eligibleOutgoing` (calls going out that are not personal, excluded or switched off), `noCard`,
 * `thin` (shown with a thin card), `noNumber` with `noNumberIn`/`noNumberOut`, `noRole` (going out without the optional role,
 * for reference), `ignored` (personal or excluded), `off` (the switch for the direction is off), `locked` (the screen was
 * locked: the notice, not only the window), `cold` (the broadcast started the process), `fgsRefused`, `noPermission` (only
 * when the overlay may not be drawn), `noNotify` (the notice could not be posted), `expanded`, `closedUnder2s`.
 * Times: `ringToCardMs` and `ringToCardMsCold` {p50,max,n} over the last [SAMPLES] calls, from the receiver's
 * [SystemClock.elapsedRealtime] (RINGING in, OFFHOOK out) to the card's outcome (window added or notice posted); `cardOpenMs`
 * the last open of the card file.
 *
 * A call without a number is counted only when it ends: Android sends each call state twice, once without the number, so the
 * first broadcast alone says nothing. Stored as {counts,"day":yyyymmdd (KST),"today":{counts},"ring":[ms],"ringCold":[ms],
 * "cardOpenMs":n}. Counts and milliseconds only, never a number or a name. Written off the caller's thread. */
internal object CardStats {
    const val CALLS="calls";const val SHOWN=CallCardOverlay.SHOWN;const val SHOWN_OUTGOING="shownOutgoing"
    const val ELIGIBLE_OUTGOING="eligibleOutgoing";const val NO_CARD=CallCardOverlay.NO_CARD;const val THIN="thin"
    const val NO_NUMBER=CallCardOverlay.NO_NUMBER;const val NO_NUMBER_IN="noNumberIn";const val NO_NUMBER_OUT="noNumberOut"
    const val NO_ROLE="noRole";const val IGNORED="ignored";const val OFF="off";const val LOCKED="locked";const val COLD="cold"
    const val FGS_REFUSED="fgsRefused";const val NO_PERMISSION=CallCardOverlay.NO_PERMISSION;const val NO_NOTIFY="noNotify"
    const val EXPANDED="expanded";const val CLOSED_UNDER_2S="closedUnder2s"
    /** Every count the heartbeat carries (zeros when missing). */
    val KINDS=listOf(CALLS,SHOWN,SHOWN_OUTGOING,ELIGIBLE_OUTGOING,NO_CARD,THIN,NO_NUMBER,NO_NUMBER_IN,NO_NUMBER_OUT,NO_ROLE,
        IGNORED,OFF,LOCKED,COLD,FGS_REFUSED,NO_PERMISSION,NO_NOTIFY,EXPANDED,CLOSED_UNDER_2S)
    /** What the card may tell [record]. */
    private val OUTCOMES=setOf(SHOWN,LOCKED,NO_PERMISSION,NO_NOTIFY,NO_CARD,NO_NUMBER)
    /** Outcomes where something reached the person (a window or a notice): they carry a ring-to-card time. */
    private val REACHED=setOf(SHOWN,LOCKED,NO_PERMISSION)
    /** Ring-to-card times kept (the newest). */
    const val SAMPLES=50
    /** A broadcast this soon after the process started (and the first one in it) started the process: a cold card. */
    const val COLD_MS=5_000L
    /** A card closed by hand sooner than this after it showed is counted as [CLOSED_UNDER_2S]. */
    const val QUICK_CLOSE_MS=2_000L
    private const val TAG="CardStats"
    private val KST:ZoneId=ZoneId.of("Asia/Seoul")
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** [SystemClock.elapsedRealtime]; replaceable in host tests. */
    @Volatile internal var elapsed:()->Long={ SystemClock.elapsedRealtime() }
    /** When this process started ([android.os.Process.getStartElapsedRealtime]); replaceable in host tests. */
    @Volatile internal var processStart:()->Long={ android.os.Process.getStartElapsedRealtime() }
    /** The probes [record] uses to tell a locked screen and a missing permission apart. Replaceable in host tests. */
    @Volatile internal var canDraw:(Context)->Boolean={ android.provider.Settings.canDrawOverlays(it) }
    @Volatile internal var locked:(Context)->Boolean={ c -> c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked==true }
    @Volatile internal var notifyAllowed:(Context)->Boolean={ c -> c.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()!=false }
    /** The number's card is a thin one (call log only). Memory first ([ContactCards.get]). Replaceable in host tests. */
    @Volatile internal var isThin:(Context,String)->Boolean={ c,k -> ContactCards.get(c,k)?.thin!=null }
    /** The optional outgoing-call role is held ([CallCardService.roleHeld]). */
    @Volatile internal var roleHeld:(Context)->Boolean={ c -> CallCardService.roleHeld(c) }

    private val lock=Any()
    /** A state other than IDLE was seen since the last IDLE. */
    private var inCall=false
    /** This call's outcome, once told. */
    private var told:String?=null
    /** A state of this call came without its number (it may still come with the next broadcast). */
    private var noNumberSeen=false
    /** This call turned out personal or excluded: nothing but [IGNORED] is counted for it. */
    private var ignoredSeen=false
    /** The card switch for this call's direction is off. */
    private var offSeen=false
    private var direction:CallSession.Direction?=null
    private var key:String?=null
    /** The receiver's time of this call's first state, and whether that broadcast started the process. */
    private var startAt:Long?=null
    private var cold=false
    private var fgsTold=false;private var expandTold=false;private var closeTold=false
    /** The broadcast now being handled ([signal]); its time starts a new call. */
    private var signalAt:Long?=null;private var signalCold=false
    /** No broadcast reached this process yet ([coldNow]). */
    @Volatile private var firstSignal=true

    private val writer=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-card-stats").apply { isDaemon=true } }
    /** Host tests: waits until the counts are written. */
    internal fun awaitIdle() { writer.submit {}.get(10,TimeUnit.SECONDS) }

    /** [CallStateReceiver]: whether the broadcast at [at] (elapsed realtime) started this process (the first one, within [COLD_MS]). */
    fun coldNow(at:Long):Boolean = synchronized(lock) {
        val first=firstSignal;firstSignal=false
        first && at-processStart()<COLD_MS
    }
    /** [CallStateReceiver], before the state is handled: the broadcast's time and whether it was cold. */
    fun signal(at:Long,coldStart:Boolean) = synchronized(lock) { signalAt=at;signalCold=coldStart }

    /** The card's own outcome for this call (once per call; a second one for the same call is dropped). [what]: [SHOWN],
     * [NO_PERMISSION] (shown as a notice instead), [LOCKED], [NO_NOTIFY], [NO_CARD] or [NO_NUMBER]. v3.4: a notice put up because
     * the screen was locked is [LOCKED], not [NO_PERMISSION] (that is only a missing overlay permission); a notice that could not
     * be posted also counts [NO_NOTIFY]. */
    fun record(context:Context,what:String) {
        if(what !in OUTCOMES) return
        val app=context.applicationContext ?: context
        val draw=runCatching { canDraw(app) }.getOrDefault(false)
        val isLocked=runCatching { locked(app) }.getOrDefault(false)
        val notify=runCatching { notifyAllowed(app) }.getOrDefault(true)
        val (kind,noNotify)=classify(what,draw,isLocked,notify)
        val inc=LinkedHashMap<String,Int>();var sample:Pair<Long,Boolean>?=null
        val at=elapsed()
        val k:String?
        synchronized(lock) {
            if(told!=null || ignoredSeen) return
            told=kind;k=key
            inc[kind]=1
            if(noNotify && kind!=NO_NOTIFY) inc[NO_NOTIFY]=1
            if(kind in REACHED) {
                startAt?.let { s -> sample=(at-s).coerceAtLeast(0) to cold }
                if(direction==CallSession.Direction.OUT && kind!=NO_PERMISSION) inc[SHOWN_OUTGOING]=1
            }
        }
        if(kind in REACHED && k!=null && runCatching { isThin(app,k) }.getOrDefault(false)) inc[THIN]=1
        write(app,inc,sample)
    }
    /** [what] the card told, read against the phone now: (the outcome, whether no notice could be posted as well). */
    internal fun classify(what:String,canDraw:Boolean,locked:Boolean,notify:Boolean):Pair<String,Boolean> = when(what) {
        // The card fell back to the notice: for a locked screen when the overlay itself is allowed.
        NO_PERMISSION -> (if(canDraw) LOCKED else NO_PERMISSION) to !notify
        // "Shown", but the window could not be drawn and no notice could be posted either.
        SHOWN -> when {
            (!canDraw || locked) && !notify -> (if(!canDraw) NO_PERMISSION else LOCKED) to true
            canDraw && locked -> LOCKED to false
            else -> SHOWN to false
        }
        else -> what to false
    }

    /** Every call state the service sees ([CallCardService.follow]), with its verdict [reason]: [CallCardService.SHOW],
     * [NO_NUMBER], [NO_CARD], [CallCardService.IGNORED], [CallCardService.OFF], [CallCardService.WAITING] or null. IDLE ends the
     * call: then `calls` and what is only known at the end (no number, ignored, off, a call going out) are counted. */
    fun follow(context:Context,snap:CallSession.Snapshot,reason:String?) {
        val inc=LinkedHashMap<String,Int>()
        var askRole=false
        synchronized(lock) {
            val at=signalAt;val c=signalCold;signalAt=null;signalCold=false
            if(snap.state==CallSession.State.IDLE) {
                if(inCall) {
                    inc[CALLS]=1
                    if(cold) inc[COLD]=1
                    when {
                        ignoredSeen -> inc[IGNORED]=1
                        offSeen -> inc[OFF]=1
                        else -> {
                            if(direction==CallSession.Direction.OUT) { inc[ELIGIBLE_OUTGOING]=1;askRole=true }
                            if(noNumberSeen && told==null) {
                                inc[NO_NUMBER]=1
                                inc[if(direction==CallSession.Direction.OUT) NO_NUMBER_OUT else NO_NUMBER_IN]=1
                            }
                        }
                    }
                }
                resetCall()
            } else {
                // A new call: whatever the card told late about the one before is not held against this one.
                if(!inCall) { resetCall();inCall=true;startAt=at;cold=c }
                snap.direction?.let { direction=it }
                snap.phoneKey?.let { key=it }
                if(snap.ignored) ignoredSeen=true
                when(reason) {
                    NO_NUMBER -> noNumberSeen=true
                    CallCardService.IGNORED -> ignoredSeen=true
                    CallCardService.OFF -> offSeen=true
                    NO_CARD -> if(told==null && !ignoredSeen) { told=NO_CARD;inc[NO_CARD]=1 }
                }
            }
        }
        if(askRole && !runCatching { roleHeld(context) }.getOrDefault(false)) inc[NO_ROLE]=1
        if(inc.isNotEmpty()) write(context,inc,null)
    }

    /** The service could not be started from the background ([CallCardService.follow]) or could not go foreground. Once per call. */
    fun fgsRefused(context:Context) { if(synchronized(lock) { if(fgsTold) false else { fgsTold=true;true } }) write(context,mapOf(FGS_REFUSED to 1),null) }
    /** r14-2: the person opened the folded card again. Once per call. */
    fun expanded(context:Context) { if(synchronized(lock) { if(expandTold || !inCall) false else { expandTold=true;true } }) write(context,mapOf(EXPANDED to 1),null) }
    /** r14-2: the person closed the card [shownMs] after it showed; counted when sooner than [QUICK_CLOSE_MS]. Once per call. */
    fun closed(context:Context,shownMs:Long) {
        if(shownMs>=QUICK_CLOSE_MS) return
        if(synchronized(lock) { if(closeTold) false else { closeTold=true;true } }) write(context,mapOf(CLOSED_UNDER_2S to 1),null)
    }

    private fun resetCall() {
        inCall=false;told=null;noNumberSeen=false;ignoredSeen=false;offSeen=false;direction=null;key=null;startAt=null;cold=false
        fgsTold=false;expandTold=false;closeTold=false
    }

    private fun write(context:Context,inc:Map<String,Int>,sample:Pair<Long,Boolean>?) {
        val app=context.applicationContext ?: context
        val now=clock()
        val open=ContactCards.lastOpenMs.takeIf { it>=0 }
        writer.execute { runCatching { SecureSettings(app).update { s -> s.put(PipelineSettingsKeys.CARD_STATS,add(read(s),inc,now,sample,open)) } }
            .onFailure { Log.w(TAG,"not counted: ${it.javaClass.simpleName}") } }
    }

    /** KST day of [ms] as yyyymmdd. */
    fun day(ms:Long):Int { val d=Instant.ofEpochMilli(ms).atZone(KST).toLocalDate();return d.year*10_000+d.monthValue*100+d.dayOfMonth }

    /** The stored counts (an object, or the same as text); empty when there are none. */
    fun read(s:JSONObject):JSONObject = when(val v=s.opt(PipelineSettingsKeys.CARD_STATS)) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrDefault(JSONObject())
        else -> JSONObject() }

    /** [stats] with one more [what] at [now]. */
    fun add(stats:JSONObject,what:String,now:Long):JSONObject=add(stats,mapOf(what to 1),now)
    /** [stats] with [inc] added at [now]: the totals and today's (a new KST day starts today's from zero); [sample] a ring-to-card
     * time (ms, cold) kept among the newest [SAMPLES]; [cardOpenMs] the last card file open. */
    fun add(stats:JSONObject,inc:Map<String,Int>,now:Long,sample:Pair<Long,Boolean>?=null,cardOpenMs:Long?=null):JSONObject {
        val out=clean(stats)
        val today=day(now)
        val t=if(stats.optInt("day",0)==today) clean(stats.optJSONObject("today") ?: JSONObject()) else clean(JSONObject())
        inc.forEach { (k,n) -> if(k in KINDS) { out.put(k,out.getInt(k)+n);t.put(k,t.getInt(k)+n) } }
        out.put("day",today).put("today",t)
        for(name in listOf("ring","ringCold")) {
            val kept=numbers(stats.optJSONArray(name)).toMutableList()
            if(sample!=null && (name=="ringCold")==sample.second) kept+=sample.first
            if(kept.isNotEmpty()) out.put(name,JSONArray(kept.takeLast(SAMPLES)))
        }
        (cardOpenMs ?: (stats.opt("cardOpenMs") as? Number)?.toLong())?.let { out.put("cardOpenMs",it) }
        return out
    }
    private fun numbers(a:JSONArray?):List<Long> = a?.let { (0 until it.length()).mapNotNull { i -> (it.opt(i) as? Number)?.toLong()?.takeIf { v -> v>=0 } } }.orEmpty()

    /** The counts only (whole numbers, 0 when missing): what the heartbeat may carry. */
    fun clean(o:JSONObject):JSONObject = JSONObject().apply { KINDS.forEach { k -> put(k,(o.opt(k) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0) } }

    /** {p50,max,n} of [ms] (n 0 and no p50 when empty). */
    internal fun summary(ms:List<Long>):JSONObject {
        val o=JSONObject().put("n",ms.size)
        if(ms.isEmpty()) return o
        val s=ms.sorted()
        return o.put("p50",s[(s.size-1)/2]).put("max",s.last())
    }

    /** The heartbeat's `callCard`: every count (zeros when not counted), today's KST day and today's counts (zeros on a new day,
     * so a quiet day closes the watchdog's issue), `ringToCardMs`, `ringToCardMsCold` and `cardOpenMs`. Null before this
     * version saw any call. [now] null: the day as stored. */
    fun heartbeat(s:JSONObject,now:Long?=null):JSONObject? {
        val st=read(s)
        if(KINDS.none { st.has(it) }) return null
        val stored=st.optInt("day",0);val day=now?.let(::day) ?: stored
        val out=clean(st).put("day",day).put("today",clean(if(day==stored) st.optJSONObject("today") ?: JSONObject() else JSONObject()))
        out.put("ringToCardMs",summary(numbers(st.optJSONArray("ring")))).put("ringToCardMsCold",summary(numbers(st.optJSONArray("ringCold"))))
        (st.opt("cardOpenMs") as? Number)?.toLong()?.let { out.put("cardOpenMs",it) }
        return out
    }

    /** Host tests: back to the start. */
    internal fun resetForHost() {
        synchronized(lock) { resetCall();signalAt=null;signalCold=false;firstSignal=true }
        clock={ System.currentTimeMillis() };elapsed={ SystemClock.elapsedRealtime() };processStart={ android.os.Process.getStartElapsedRealtime() }
        canDraw={ android.provider.Settings.canDrawOverlays(it) }
        locked={ c -> c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked==true }
        notifyAllowed={ c -> c.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()!=false }
        isThin={ c,k -> ContactCards.get(c,k)?.thin!=null };roleHeld={ c -> CallCardService.roleHeld(c) }
    }
}
