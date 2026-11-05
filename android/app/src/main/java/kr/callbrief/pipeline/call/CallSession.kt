package kr.callbrief.pipeline.call

import android.content.Context
import android.telephony.TelephonyManager
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.LocalPrivacy
import kr.callbrief.SecureSettings
import kr.callbrief.core.PhoneKey
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The phone's call state as this app last saw it (design v2.9 6.1, r10-1): one per process, the last state also sealed in
 * [SecureSettings] under [SETTINGS_KEY] so a process that was killed during a call still knows how the call began.
 *
 * `IDLE -> RINGING(number) -> OFFHOOK -> IDLE` for a call that comes in, `IDLE -> OFFHOOK -> IDLE` for one that goes out
 * (the number is then mostly unknown, null). A repeated broadcast of the same state changes nothing, except that a number
 * that was empty is filled in (Android 9 and later send each state twice, the second time with the number when the app
 * may read the call log). Back to IDLE the call's [CallEnd] goes to [listeners]; after an OFFHOOK the discovery work
 * [AutomaticRecordingDiscovery.AFTERCALL_NAME] is queued. A personal number or one of the user's exclusion rules: nothing at
 * all (no number kept, no work, no listener). Only the number's [PhoneKey] is ever kept, and only until the call ends. */
object CallSession {
    enum class State { IDLE, RINGING, OFFHOOK }
    enum class Direction { IN, OUT }
    /** [phoneKey]: [PhoneKey.of] the number, null when not known (yet) or the call is [ignored]. [sinceMs]: when [state]
     * began. [ignored]: a personal or excluded number; such a call shows and keeps nothing. */
    data class Snapshot(val state:State,val phoneKey:String?,val direction:Direction?,val sinceMs:Long,val ignored:Boolean=false)
    /** A call that ended. [startedMs]: when it began to ring (in) or went off hook (out). [answered]: it went off hook; for a
     * call that goes out this means it was placed, not that the other side answered (the phone does not say). */
    data class CallEnd(val phoneKey:String?,val direction:Direction?,val startedMs:Long,val endedMs:Long,val answered:Boolean)

    /** Told about every call that ended (r10-2 per-call notice, v3.0 call card). Called on the session's own thread. */
    val listeners=CopyOnWriteArrayList<(CallEnd)->Unit>()
    /** Settings key with the last state (no content: a state, times, the phone key while a call lasts). */
    const val SETTINGS_KEY="callSession"
    /** A state that is not IDLE but older than this when it is read back was lost (the IDLE broadcast never came). */
    const val STALE_MS=6*3_600_000L

    private class Live(val state:State,val phoneKey:String?,val direction:Direction?,val sinceMs:Long,
                       val startedMs:Long,val answered:Boolean,val ignored:Boolean) {
        fun snapshot()=Snapshot(state,phoneKey,direction,sinceMs,ignored)
        fun json():JSONObject=JSONObject().put("s",state.name).put("k",phoneKey ?: JSONObject.NULL).put("d",direction?.name ?: JSONObject.NULL)
            .put("since",sinceMs).put("start",startedMs).put("ans",answered).put("ign",ignored)
    }
    private val IDLE_AT_START=Live(State.IDLE,null,null,0L,0L,false,false)
    private val lock=Any()
    @Volatile private var live:Live?=null

    /** Replaceable in host tests. */
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** A personal number (personal_numbers) or one of the user's exclusion rules. Replaceable in host tests. */
    @Volatile internal var isPrivate:(Context,String)->Boolean=::privateNumber
    /** Queues the discovery pass after a call. Replaceable in host tests. */
    @Volatile internal var afterCall:(Context)->Boolean={ c -> AutomaticRecordingDiscovery.scheduleAfterCall(c) }

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
        live=restored;restored
    }
    private fun restore(context:Context):Live {
        val o=runCatching { SecureSettings(context.applicationContext ?: context).snapshot().optJSONObject(SETTINGS_KEY) }.getOrNull() ?: return IDLE_AT_START
        val s=runCatching { State.valueOf(o.optString("s")) }.getOrNull() ?: return IDLE_AT_START
        val since=o.optLong("since",0L)
        if(s!=State.IDLE && clock()-since>STALE_MS) return IDLE_AT_START
        val key=if(o.isNull("k")) null else PhoneKey.of(o.optString("k"))
        val dir=if(o.isNull("d")) null else runCatching { Direction.valueOf(o.optString("d")) }.getOrNull()
        return Live(s,key,dir,since,o.optLong("start",since),o.optBoolean("ans"),o.optBoolean("ign"))
    }
    private fun save(context:Context,next:Live) {
        live=next
        runCatching { SecureSettings(context).update { it.put(SETTINGS_KEY,next.json()) } }
    }
    /** Host tests: the process died, the next read comes from settings. */
    internal fun forgetMemory() = synchronized(lock) { live=null }
    /** Host tests: back to the start, nothing kept. */
    internal fun resetForHost() = synchronized(lock) {
        live=null;listeners.clear();clock={ System.currentTimeMillis() };isPrivate=::privateNumber
        afterCall={ c -> AutomaticRecordingDiscovery.scheduleAfterCall(c) }
    }

    /** One PHONE_STATE broadcast: [extraState] is [TelephonyManager.EXTRA_STATE], [rawNumber] its incoming number (may be
     * empty). Returns the call that ended with it, if any (also for an ignored one, then without being told to anyone). */
    fun onPhoneState(context:Context,extraState:String?,rawNumber:String?):CallEnd? {
        val app=context.applicationContext ?: context
        val now=clock()
        val to=when(extraState) {
            TelephonyManager.EXTRA_STATE_RINGING -> State.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> State.OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> State.IDLE
            else -> return null
        }
        val key=PhoneKey.of(rawNumber)
        var ended:CallEnd?=null;var tell=false;var ranOffHook=false
        synchronized(lock) {
            val was=state(app)
            when {
                // The same state again: only a number that was missing is filled in.
                to==was.state -> {
                    if(to!=State.IDLE && !was.ignored && was.phoneKey==null && key!=null) save(app,withNumber(app,was,key))
                }
                to==State.RINGING -> {
                    // A second call while one goes on (call waiting): the phone stays off hook, so do we.
                    if(was.state==State.OFFHOOK) return null
                    val ring=Live(State.RINGING,null,Direction.IN,now,now,false,false)
                    save(app,if(key!=null) withNumber(app,ring,key) else ring)
                }
                to==State.OFFHOOK -> {
                    // Off hook without ringing first: a call that goes out (design: the number may be unknown, null).
                    val next=if(was.state==State.RINGING) Live(State.OFFHOOK,was.phoneKey,Direction.IN,now,was.startedMs,true,was.ignored)
                        else Live(State.OFFHOOK,null,Direction.OUT,now,now,true,false)
                    save(app,if(next.phoneKey==null && !next.ignored && key!=null) withNumber(app,next,key) else next)
                }
                else -> { // IDLE after RINGING (missed or rejected) or after OFFHOOK (the call ended)
                    ended=CallEnd(was.phoneKey,was.direction,was.startedMs,now,was.state==State.OFFHOOK)
                    tell=!was.ignored;ranOffHook=was.state==State.OFFHOOK
                    save(app,Live(State.IDLE,null,null,now,now,false,false))
                }
            }
        }
        val end=ended ?: return null
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
        if(runCatching { isPrivate(context,key) }.getOrDefault(true)) Live(l.state,null,l.direction,l.sinceMs,l.startedMs,l.answered,true)
        else Live(l.state,key,l.direction,l.sinceMs,l.startedMs,l.answered,false)

    /** The user's exclusion rules (the built-in personal number included) or a number found personal by the analysis.
     * Settings that cannot be read count as private: then nothing happens rather than too much. */
    internal fun privateNumber(context:Context,key:String):Boolean {
        val rules=runCatching { LocalPrivacy.rules(SecureSettings(context).snapshot()) }.getOrNull() ?: return true
        if(rules.excluded(key,key)) return true
        return runCatching { kr.callbrief.IeumApplication.store(context).isPersonal(key) }.getOrDefault(false)
    }
}
