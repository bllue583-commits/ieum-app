package kr.callbrief.pipeline.call

import android.content.Context
import android.util.Log
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.PipelineSettingsKeys
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** How often the call card showed and why it did not (design v3.0 7.3, r11-3): settings [PipelineSettingsKeys.CARD_STATS], the
 * heartbeat's `callCard`. One count per call, whoever tells it first: the card ([CallCardOverlay.outcome]: shown, no permission)
 * or the service, which drops calls before they reach the card ([CallCardService.follow]: no card for the number, no number).
 *
 * A call without a number is counted only when it ends: Android sends each call state twice, once without the number, so the
 * first broadcast alone says nothing. Personal and excluded numbers, and calls whose switch is off (or that go out without the
 * role), are no failure and are not counted.
 *
 * Stored as {"shown":n,"noPermission":n,"noNumber":n,"noCard":n,"day":yyyymmdd (KST),"today":{the same four for that day}}.
 * Counts only, never a number or a name. Written off the caller's thread (the card tells on the main thread). */
internal object CardStats {
    val KINDS=listOf(CallCardOverlay.SHOWN,CallCardOverlay.NO_PERMISSION,CallCardOverlay.NO_NUMBER,CallCardOverlay.NO_CARD)
    private const val TAG="CardStats"
    private val KST:ZoneId=ZoneId.of("Asia/Seoul")
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }

    private val lock=Any()
    /** This call was counted already. */
    private var told=false
    /** A state of this call came without its number (it may still come with the next broadcast). */
    private var noNumberSeen=false
    /** This call turned out personal or excluded: nothing is counted for it. */
    private var ignoredSeen=false
    /** A state other than IDLE was seen since the last IDLE. */
    private var inCall=false

    private val writer=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-card-stats").apply { isDaemon=true } }
    /** Host tests: waits until the counts are written. */
    internal fun awaitIdle() { writer.submit {}.get(10,TimeUnit.SECONDS) }

    /** The card's own outcome for this call (once per call; a second one for the same call is dropped). */
    fun record(context:Context,what:String) {
        if(what !in KINDS) return
        synchronized(lock) { if(told || ignoredSeen) return;told=true }
        write(context,what)
    }

    /** Every call state the service sees, with [reason] the card was not wanted for ([CallCardService.skipReason]: [CallCardOverlay.NO_NUMBER],
     * [CallCardOverlay.NO_CARD] or null). IDLE ends the call: a call that never got its number (and was not counted otherwise) counts then. */
    fun follow(context:Context,snap:CallSession.Snapshot,reason:String?) {
        var count:String?=null
        synchronized(lock) {
            if(snap.state==CallSession.State.IDLE) {
                if(inCall && noNumberSeen && !told && !ignoredSeen) count=CallCardOverlay.NO_NUMBER
                told=false;noNumberSeen=false;ignoredSeen=false;inCall=false
            } else {
                // A new call: whatever the card told late about the one before is not held against this one.
                if(!inCall) { told=false;noNumberSeen=false;ignoredSeen=false;inCall=true }
                if(snap.ignored) ignoredSeen=true
                when(reason) {
                    CallCardOverlay.NO_NUMBER -> noNumberSeen=true
                    CallCardOverlay.NO_CARD -> if(!told && !ignoredSeen) { told=true;count=reason }
                }
            }
        }
        count?.let { write(context,it) }
    }

    private fun write(context:Context,what:String) {
        val app=context.applicationContext ?: context
        val now=clock()
        writer.execute { runCatching { SecureSettings(app).update { s -> s.put(PipelineSettingsKeys.CARD_STATS,add(read(s),what,now)) } }
            .onFailure { Log.w(TAG,"not counted: ${it.javaClass.simpleName}") } }
    }

    /** KST day of [ms] as yyyymmdd. */
    fun day(ms:Long):Int { val d=Instant.ofEpochMilli(ms).atZone(KST).toLocalDate();return d.year*10_000+d.monthValue*100+d.dayOfMonth }

    /** The stored counts (an object, or the same as text); empty when there are none. */
    fun read(s:JSONObject):JSONObject = when(val v=s.opt(PipelineSettingsKeys.CARD_STATS)) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrDefault(JSONObject())
        else -> JSONObject() }

    /** [stats] with one more [what] at [now]: the total and today's (a new KST day starts today's from zero). */
    fun add(stats:JSONObject,what:String,now:Long):JSONObject {
        val out=clean(stats)
        val today=day(now)
        val t=if(stats.optInt("day",0)==today) clean(stats.optJSONObject("today") ?: JSONObject()) else clean(JSONObject())
        out.put(what,out.getInt(what)+1);t.put(what,t.getInt(what)+1)
        return out.put("day",today).put("today",t)
    }

    /** The four counts only (whole numbers, 0 when missing): what the heartbeat may carry. */
    fun clean(o:JSONObject):JSONObject = JSONObject().apply { KINDS.forEach { k -> put(k,(o.opt(k) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0) } }

    /** The heartbeat's `callCard`: the four totals, today's KST day and today's four (zeros when nothing was counted today, so
     * a quiet day closes the watchdog's issue). Null before any call was counted. [now] null: the day as stored. */
    fun heartbeat(s:JSONObject,now:Long?=null):JSONObject? {
        val st=read(s)
        if(KINDS.none { st.has(it) }) return null
        val stored=st.optInt("day",0);val day=now?.let(::day) ?: stored
        return clean(st).put("day",day).put("today",clean(if(day==stored) st.optJSONObject("today") ?: JSONObject() else JSONObject()))
    }

    /** Host tests: back to the start. */
    internal fun resetForHost() { synchronized(lock) { told=false;noNumberSeen=false;ignoredSeen=false;inCall=false };clock={ System.currentTimeMillis() } }
}
