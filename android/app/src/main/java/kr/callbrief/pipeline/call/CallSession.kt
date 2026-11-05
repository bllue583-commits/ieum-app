package kr.callbrief.pipeline.call

import android.content.Context
import android.telephony.TelephonyManager
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.LocalPrivacy
import kr.callbrief.SecureSettings
import kr.callbrief.core.PhoneKey
import kr.callbrief.pipeline.ContactCards
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The phone's call state as this app last saw it (design v2.9 6.1, r10-1): one per process, the last state also sealed in
 * [SecureSettings] under [SETTINGS_KEY] so a process that was killed during a call still knows how the call began.
 *
 * `IDLE -> RINGING(number) -> OFFHOOK -> IDLE` for a call that comes in, `IDLE -> OFFHOOK -> IDLE` for one that goes out
 * (the number comes with the OFFHOOK's second broadcast, or from [pendingOutgoing] when the optional role is held). A repeated
 * broadcast of the same state changes nothing, except that a number that was empty is filled in (Android 9 and later send each
 * state twice, the second time with the number when the app may read the call log). Back to IDLE the call's [CallEnd] goes to
 * [listeners]; after an OFFHOOK the discovery work [AutomaticRecordingDiscovery.AFTERCALL_NAME] is queued. A personal number or
 * one of the user's exclusion rules: nothing at all (no number kept, no work, no listener). Only the number's [PhoneKey] is ever
 * kept, and only until the call ends.
 *
 * v3.4 (DESIGN_V4 4.1-2, 4.1-3, r14-1): the ringing path does not open the call store. Settings are read once per call into a
 * memory snapshot ([callSettings]); a personal number is looked up in the small card file's blocked table
 * ([ContactCards.blockedState]); the big store is only read after the call. A second call ringing during a call (call waiting)
 * marks the call [Snapshot.waiting]: the card goes and nothing shows until IDLE, and the first call stays the one that ends. */
object CallSession {
    enum class State { IDLE, RINGING, OFFHOOK }
    enum class Direction { IN, OUT }
    /** [phoneKey]: [PhoneKey.of] the number, null when not known (yet) or the call is [ignored]. [sinceMs]: when [state]
     * began. [ignored]: a personal or excluded number; such a call shows and keeps nothing. [waiting]: v3.4, a second call
     * rang during this one (call waiting); no card until IDLE. */
    data class Snapshot(val state:State,val phoneKey:String?,val direction:Direction?,val sinceMs:Long,val ignored:Boolean=false,
                        val waiting:Boolean=false)
    /** A call that ended. [startedMs]: when it began to ring (in) or went off hook (out). [answered]: it went off hook; for a
     * call that goes out this means it was placed, not that the other side answered (the phone does not say). */
    data class CallEnd(val phoneKey:String?,val direction:Direction?,val startedMs:Long,val endedMs:Long,val answered:Boolean)

    /** Told about every call that ended (r10-2 per-call notice, v3.0 call card). Called on the session's own thread. */
    val listeners=CopyOnWriteArrayList<(CallEnd)->Unit>()
    /** Settings key with the last state (no content: a state, times, the phone key while a call lasts). */
    const val SETTINGS_KEY="callSession"
    /** A state that is not IDLE but older than this when it is read back was lost (the IDLE broadcast never came). */
    const val STALE_MS=6*3_600_000L
    /** v3.0 (r11-1): an OFFHOOK within this time after [pendingOutgoing] is that call going out, with that number. */
    const val OUTGOING_WINDOW_MS=20_000L

    private class Live(val state:State,val phoneKey:String?,val direction:Direction?,val sinceMs:Long,
                       val startedMs:Long,val answered:Boolean,val ignored:Boolean,val waiting:Boolean=false) {
        fun snapshot()=Snapshot(state,phoneKey,direction,sinceMs,ignored,waiting)
        fun json():JSONObject=JSONObject().put("s",state.name).put("k",phoneKey ?: JSONObject.NULL).put("d",direction?.name ?: JSONObject.NULL)
            .put("since",sinceMs).put("start",startedMs).put("ans",answered).put("ign",ignored).apply { if(waiting) put("w",true) }
    }
    private val IDLE_AT_START=Live(State.IDLE,null,null,0L,0L,false,false)
    private val lock=Any()
    @Volatile private var live:Live?=null
    /** The number the call redirection service saw last (v3.0, r11-1): its key and when. Memory only (the call follows within
     * seconds); used by the next OFFHOOK within [OUTGOING_WINDOW_MS], then dropped. */
    private class Pending(val phoneKey:String,val atMs:Long)
    private var pending:Pending?=null
    /** v3.4: the settings of the call going on, read once ([callSettings]); dropped when the call ends. */
    @Volatile private var settingsOfCall:JSONObject?=null
    /** A call goes on as far as this process knows (from its first state that is not IDLE to IDLE). */
    @Volatile private var callOpen=false
    /** The settings read when the state was taken back after a restart: the first call of the process uses them. */
    private var restoredSettings:JSONObject?=null
    /** The state changed in memory and is not in settings yet ([persist]). */
    private var dirty=false
    /** False while [onPhoneState] runs with `persistLater`: [save] then keeps the state in memory only. */
    private var persistNow=true

    /** v3.0 (r11-1): a call is about to go out to [number] ([OutgoingCallService.onPlaceCall], at [atMs]). Only its [PhoneKey]
     * is kept, in memory, until the OFFHOOK that follows within [OUTGOING_WINDOW_MS]; that OFFHOOK then makes a call that goes
     * out with this number (or an ignored one, when the number is personal or excluded). Quick: no file, no settings. */
    fun pendingOutgoing(number:String?,atMs:Long) {
        val key=PhoneKey.of(number)
        synchronized(lock) { pending=key?.let { Pending(it,atMs) } }
    }

    /** Replaceable in host tests. */
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** A personal number (personal_numbers) or one of the user's exclusion rules. Replaceable in host tests. */
    @Volatile internal var isPrivate:(Context,String)->Boolean=::privateNumber
    /** Queues the discovery pass after a call. Replaceable in host tests. */
    @Volatile internal var afterCall:(Context)->Boolean={ c -> AutomaticRecordingDiscovery.scheduleAfterCall(c) }
    /** Reads the whole settings (one decrypt). Replaceable in host tests to count reads. */
    @Volatile internal var readSettings:(Context)->JSONObject={ c -> SecureSettings(c).snapshot() }
    /** The big store's own answer (only before the card file has its blocked table). Replaceable in host tests. */
    @Volatile internal var storePersonal:(Context,String)->Boolean={ c,k -> kr.callbrief.IeumApplication.store(c).isPersonal(k) }
    /** After a call ([ContactCards.afterCall], off this thread). Replaceable in host tests. */
    @Volatile internal var afterCallCards:(Context,String?)->Unit={ c,k -> ContactCards.afterCall(c,k) }

    /** One thread: broadcasts are handled in the order they came. */
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-call-session").apply { isDaemon=true } }
    internal fun post(block:()->Unit) { worker.execute { runCatching(block) } }
    /** Host tests: waits until every broadcast handed to [post] so far is handled. */
    internal fun awaitIdle() { worker.submit {}.get(10,TimeUnit.SECONDS) }

    /** The state now; read back from settings the first time in this process. */
    fun current():Snapshot=state(null).snapshot()

    private fun state(context:Context?):Live = synchronized(lock) {
        live?.let { return it }
        val restored=context?.let { restore(it) } ?: return IDLE_AT_START
        live=restored;callOpen=restored.state!=State.IDLE;restored
    }
    private fun restore(context:Context):Live {
        val all=runCatching { readSettings(context.applicationContext ?: context) }.getOrNull()
        restoredSettings=all
        val o=all?.optJSONObject(SETTINGS_KEY) ?: return IDLE_AT_START
        val s=runCatching { State.valueOf(o.optString("s")) }.getOrNull() ?: return IDLE_AT_START
        val since=o.optLong("since",0L)
        if(s!=State.IDLE && clock()-since>STALE_MS) return IDLE_AT_START
        val key=if(o.isNull("k")) null else PhoneKey.of(o.optString("k"))
        val dir=if(o.isNull("d")) null else runCatching { Direction.valueOf(o.optString("d")) }.getOrNull()
        return Live(s,key,dir,since,o.optLong("start",since),o.optBoolean("ans"),o.optBoolean("ign"),o.optBoolean("w"))
    }
    /** The new state in memory; it goes to settings now, or with [persist] once the card has been told. */
    private fun save(context:Context,next:Live) {
        live=next;dirty=true
        if(persistNow) persistLocked(context)
    }
    private fun persistLocked(context:Context) {
        if(!dirty) return
        val l=live ?: return
        dirty=false
        runCatching { SecureSettings(context).update { it.put(SETTINGS_KEY,l.json()) } }
    }
    /** v3.4: writes the state [onPhoneState] kept in memory ([CallStateReceiver] calls it after the card was told, so the
     * ringing path does not wait for a settings write). */
    fun persist(context:Context) = synchronized(lock) { persistLocked(context.applicationContext ?: context) }

    /** v3.4 (DESIGN_V4 4.1-2): the settings for the call going on, read once per call and kept in memory; outside a call a
     * fresh read (screens and tests change settings between calls). Null when they cannot be read. */
    fun callSettings(context:Context):JSONObject? {
        val app=context.applicationContext ?: context
        if(!callOpen) return runCatching { readSettings(app) }.getOrNull()
        settingsOfCall?.let { return it }
        return runCatching { readSettings(app) }.getOrNull()?.also { settingsOfCall=it }
    }

    /** Host tests: the process died, the next read comes from settings. */
    internal fun forgetMemory() = synchronized(lock) { live=null;settingsOfCall=null;restoredSettings=null;callOpen=false }
    /** Host tests: back to the start, nothing kept. */
    internal fun resetForHost() = synchronized(lock) {
        live=null;pending=null;listeners.clear();clock={ System.currentTimeMillis() };isPrivate=::privateNumber
        afterCall={ c -> AutomaticRecordingDiscovery.scheduleAfterCall(c) }
        settingsOfCall=null;restoredSettings=null;dirty=false;persistNow=true;callOpen=false
        readSettings={ c -> SecureSettings(c).snapshot() };storePersonal={ c,k -> kr.callbrief.IeumApplication.store(c).isPersonal(k) }
        afterCallCards={ c,k -> ContactCards.afterCall(c,k) }
    }

    /** One PHONE_STATE broadcast: [extraState] is [TelephonyManager.EXTRA_STATE], [rawNumber] its incoming number (may be
     * empty). Returns the call that ended with it, if any (also for an ignored one, then without being told to anyone).
     * [persistLater]: the new state stays in memory until [persist] (the receiver tells the card first). */
    fun onPhoneState(context:Context,extraState:String?,rawNumber:String?,persistLater:Boolean=false):CallEnd? {
        val app=context.applicationContext ?: context
        val now=clock()
        val to=when(extraState) {
            TelephonyManager.EXTRA_STATE_RINGING -> State.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> State.OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> State.IDLE
            else -> return null
        }
        val key=PhoneKey.of(rawNumber)
        var ended:CallEnd?=null;var tell=false;var ranOffHook=false;var endedKey:String?=null
        synchronized(lock) {
            persistNow=!persistLater
            try {
                val was=state(app)
                // A new call: its settings are read once, when first needed (the restart's read is used when there is one).
                if(was.state==State.IDLE && to!=State.IDLE) { settingsOfCall=restoredSettings;callOpen=true }
                restoredSettings=null // only as fresh as this broadcast
                when {
                    // The same state again: only a number that was missing is filled in (never during call waiting: a number
                    // then is the second caller's).
                    to==was.state -> {
                        if(to!=State.IDLE && !was.ignored && !was.waiting && was.phoneKey==null && key!=null) save(app,withNumber(app,was,key))
                    }
                    to==State.RINGING && was.state==State.OFFHOOK -> {
                        // v3.4 (4.1-3): a second call while one goes on (call waiting). The phone stays off hook, so do we: the
                        // first call keeps its number and start; the card goes until IDLE.
                        if(!was.waiting) save(app,Live(was.state,was.phoneKey,was.direction,was.sinceMs,was.startedMs,was.answered,was.ignored,true))
                    }
                    to==State.RINGING -> {
                        val ring=Live(State.RINGING,null,Direction.IN,now,now,false,false)
                        save(app,if(key!=null) withNumber(app,ring,key) else ring)
                    }
                    to==State.OFFHOOK -> {
                        // Off hook without ringing first: a call that goes out (design: the number may be unknown, null). v3.0:
                        // the number the call redirection service saw just before, when it is fresh enough. v3.4: without the
                        // role the number comes with this broadcast or its repeat.
                        val out=pending?.takeIf { was.state!=State.RINGING && now-it.atMs in 0..OUTGOING_WINDOW_MS }?.phoneKey
                        pending=null
                        val next=if(was.state==State.RINGING) Live(State.OFFHOOK,was.phoneKey,Direction.IN,now,was.startedMs,true,was.ignored,was.waiting)
                            else Live(State.OFFHOOK,null,Direction.OUT,now,now,true,false)
                        val known=out ?: key
                        save(app,if(next.phoneKey==null && !next.ignored && known!=null) withNumber(app,next,known) else next)
                    }
                    else -> { // IDLE after RINGING (missed or rejected) or after OFFHOOK (the call ended)
                        ended=CallEnd(was.phoneKey,was.direction,was.startedMs,now,was.state==State.OFFHOOK)
                        tell=!was.ignored;ranOffHook=was.state==State.OFFHOOK;endedKey=was.phoneKey
                        save(app,Live(State.IDLE,null,null,now,now,false,false))
                        settingsOfCall=null;callOpen=false
                    }
                }
            } finally { persistNow=true }
        }
        val end=ended ?: return null
        // v3.4 (4.1-2): after the call, off this thread, the big store may teach the card file a number found personal since
        // (and the blocked table is written again when the rules changed). Not for an ignored call: it has no key.
        runCatching { afterCallCards(app,endedKey) }
        if(!tell) return end
        // Only a call that went off hook can have left a recording.
        if(ranOffHook) runCatching { afterCall(app) }
        for(l in listeners) runCatching { l(end) }
        // r10-2: the call's own notice ("…와 통화를 정리하고 있어요"); this thread is not the main one, as it needs.
        runCatching { kr.callbrief.pipeline.CallFollowupNotice.onCallEnded(app,end) }
        return end
    }
    /** [l] with the number known: kept as its key, or the whole call marked ignored when the number is private. */
    private fun withNumber(context:Context,l:Live,key:String):Live =
        if(runCatching { isPrivate(context,key) }.getOrDefault(true)) Live(l.state,null,l.direction,l.sinceMs,l.startedMs,l.answered,true,l.waiting)
        else Live(l.state,key,l.direction,l.sinceMs,l.startedMs,l.answered,false,l.waiting)

    /** The user's exclusion rules (the built-in personal number included) or a number found personal by the analysis.
     * Settings that cannot be read count as private: then nothing happens rather than too much.
     * v3.4 (DESIGN_V4 4.1-2): the rules from the call's settings snapshot, the personal numbers from the card file's blocked
     * table (no call store on the ringing path). A blocked table that cannot be read counts as private too. Only before the
     * table was ever written (the first call after the update) the big store answers, as before. */
    internal fun privateNumber(context:Context,key:String):Boolean {
        val s=callSettings(context) ?: return true
        val rules=runCatching { LocalPrivacy.rules(s) }.getOrNull() ?: return true
        if(rules.excluded(key,key)) return true
        val blocked=try { ContactCards.blockedState(context,key) } catch(e:Exception) { return true }
        return blocked ?: runCatching { storePersonal(context,key) }.getOrDefault(false)
    }
}
