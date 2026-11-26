package kr.callbrief.pipeline.call

import android.content.Context
import android.os.Build
import android.util.Log
import kr.callbrief.LocalPrivacy
import kr.callbrief.SecureSettings
import kr.callbrief.core.PhoneKey
import kr.callbrief.core.PrivacyRules
import kr.callbrief.pipeline.CallFollowupNotice
import kr.callbrief.pipeline.CallPipelineStartup
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import kr.callbrief.pipeline.PipelineSettingsKeys
import kr.callbrief.pipeline.RecordingGap
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** v4.7 r28-1: "the person you are talking to again today" — 45% of the recorded calls are with someone already called that day,
 * and in 82% of those the earlier call was not sorted out on the phone yet when the phone rang again. The call card and its
 * notice then say so in one line on top ([line]): "오늘 09:12에 통화했어요(3분). 아직 정리 중이에요" or "오늘 2번 통화했어요(마지막
 * 11:40). 아직 정리 중이에요". Facts only, no forecast.
 *
 * What it knows from (nothing new is read on the ringing path):
 *  - every answered call that ended ([onCallEnded], [CallSession] at IDLE, off the ringing path) is kept in the settings under
 *    [KEY] as a fingerprint of the number ([LocalPrivacy.numberHash], never the number), when it began and ended, how long it
 *    was talked and, for a call going out, whether the call log says it was connected (read a moment later on this object's own
 *    thread). Only today's (KST) entries are kept, at most [MAX]. The settings are read once per call anyway
 *    ([CallSession.callSettings]), so the ringing path reads nothing more.
 *  - "sorted out" ([settled]): the number's card holds the start times of its calls whose result is on the phone
 *    ([ContactCard.settledMs], worked out with the card after every applied result), its summarised calls ([ContactCard.recent])
 *    and the follow-up state of the same call ([CallFollowupNotice.STATE_KEY]: done, or no recording, which will never be sorted
 *    out). While the last recording check says calls are not recorded at all, nothing is said.
 *
 * A personal or excluded number never gets here (the call is ignored before). */
internal object RepeatCalls {
    /** Settings key: a JSON text (so the heartbeat never carries it) of today's ended calls. */
    const val KEY="repeatCalls"
    const val MAX=80
    /** A record whose time is this close to a call (either side) is that call's (as [CallFollowupNotice.MATCH_MS]). */
    const val MATCH_MS=2*60_000L
    private const val TAG="RepeatCalls"

    /** One ended call. [talkMs]: how long it was talked (null: not known); [connected]: an incoming answered call, or a call
     * going out the call log says lasted (false: it did not; null: not known yet). */
    data class Call(val hash:String,val startedMs:Long,val endedMs:Long,val talkMs:Long?,val out:Boolean,val connected:Boolean?) {
        fun json():JSONObject=JSONObject().put("h",hash).put("s",startedMs).put("e",endedMs).put("o",out)
            .apply { talkMs?.let { put("t",it) };connected?.let { put("c",it) } }
        companion object {
            fun parse(o:JSONObject)=Call(o.getString("h"),o.getLong("s"),o.getLong("e"),if(o.has("t")) o.optLong("t") else null,o.optBoolean("o"),
                if(o.has("c")) o.optBoolean("c") else null)
        }
    }
    /** Today's calls with the number ([today]) and those of them not sorted out yet ([pending], oldest first). */
    data class Info(val today:Int,val pending:List<Call>) {
        val line:String? get()=line(today,pending)
    }

    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** The call log's length of a call going out (ms; 0: not connected; null: not there yet). Replaceable in host tests. */
    @Volatile internal var logLength:(Context,String,Long,Long)->Long?={ c,h,s,e -> CallFollowupNotice.logDuration(c,CallFollowupNotice.Entry(0,"",null,h,s,e)) }
    /** Off in host tests: a call going out is looked up in the call log on the caller's thread. */
    @Volatile internal var background:Boolean=Build.FINGERPRINT!="robolectric"
    @Volatile internal var waitMs:Long=1_000L
    const val LOG_TRIES=3
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-repeat-calls").apply { isDaemon=true } } }
    internal fun awaitIdle() { if(background) worker.submit {}.get(10,TimeUnit.SECONDS) }

    // ---- after a call ------------------------------------------------------------------------------------------------
    /** [CallSession] at IDLE of a call that was not ignored: an answered call is kept for the rest of the day. [answeredMs]: when
     * it went off hook. */
    fun onCallEnded(context:Context,end:CallSession.CallEnd,answeredMs:Long?) {
        if(!end.answered) return
        val key=PhoneKey.of(end.phoneKey) ?: return
        val app=CallPipelineStartup.storage(context)
        val out=end.direction==CallSession.Direction.OUT
        var hash=""
        val now=clock()
        // A call coming in was talked from off hook to the end; a call going out from dialling, so its length comes from the log.
        val talk=if(out) null else answeredMs?.let { (end.endedMs-it).coerceAtLeast(0) }
        settings(app).update { s ->
            hash=LocalPrivacy.numberHash(s,key)
            val c=Call(hash,end.startedMs,end.endedMs,talk,out,if(out) null else true)
            s.put(KEY,write(read(s).filter { !(it.hash==c.hash && it.endedMs==c.endedMs) }+c,now))
        }
        if(!out) return
        val h=hash
        val resolve={ runCatching { resolveOut(app,h,end.startedMs,end.endedMs) }.onFailure { Log.w(TAG,"not resolved: ${it.javaClass.simpleName}") };Unit }
        if(background) worker.execute { resolve() } else resolve()
    }
    /** A call going out: the call log says whether it was connected and how long (it writes the line a moment after the call). */
    private fun resolveOut(app:Context,hash:String,startedMs:Long,endedMs:Long) {
        var length:Long?=null
        for(i in 0 until LOG_TRIES) { length=runCatching { logLength(app,hash,startedMs,endedMs) }.getOrNull();if(length!=null) break
            if(waitMs>0 && i<LOG_TRIES-1) Thread.sleep(waitMs) }
        val l=length ?: return
        // The app may not read the call log: placed is all that is known (shown without a length, as before such calls were told).
        val (connected,talk)=if(l==CallFollowupNotice.UNREADABLE) true to null else (l>0) to l.takeIf { it>0 }
        settings(app).update { s -> s.put(KEY,write(read(s).map { if(it.hash==hash && it.endedMs==endedMs) it.copy(connected=connected,talkMs=talk) else it },clock())) }
    }
    private fun settings(app:Context)=SecureSettings(app)
    internal fun read(s:JSONObject):List<Call> =runCatching { val a=JSONArray(s.optString(KEY,"[]").ifBlank { "[]" })
        (0 until a.length()).mapNotNull { runCatching { Call.parse(a.getJSONObject(it)) }.getOrNull() } }.getOrDefault(emptyList())
    /** Only today's calls (KST), the newest [MAX]. */
    internal fun write(calls:List<Call>,now:Long):String {
        val day=CallRecordStore.kstDay(now)
        return JSONArray().apply { calls.filter { CallRecordStore.kstDay(it.startedMs)==day }.sortedBy { it.startedMs }.takeLast(MAX).forEach { put(it.json()) } }.toString()
    }

    // ---- while it rings ----------------------------------------------------------------------------------------------
    /** What to say of [key] at [now] from the call's settings [s] and the number's [card]; null when there was no call with it
     * today (KST). Reads nothing: the settings are the call's snapshot, the card is the one the card shows. */
    fun info(s:JSONObject,key:String,card:ContactCard?,now:Long=clock()):Info? {
        val raw=s.optString(KEY,"");if(raw.isBlank()) return null
        val k=PhoneKey.of(key) ?: return null
        val day=CallRecordStore.kstDay(now)
        val mine=setOf(LocalPrivacy.numberHash(s,k),PrivacyRules.hash(k))
        val calls=read(s).filter { it.connected==true && it.startedMs<=now && it.hash in mine && CallRecordStore.kstDay(it.startedMs)==day }.sortedBy { it.startedMs }
        if(calls.isEmpty()) return null
        // Calls are not being recorded at all (the last check, while fresh): none of them will be sorted out, so nothing is said.
        val notRecorded=runCatching { RecordingGap.last(s)?.let { it.code==PipelineSettingsKeys.GAP_NO_RECORDING && now-it.atMs<RecordingGap.STALE_MS }==true }.getOrDefault(false)
        if(notRecorded) return Info(calls.size,emptyList())
        val follow=followups(s).filter { it.hash in mine }
        val byRecord=settled(calls,card)
        val pending=calls.filter { c -> c !in byRecord && follow.none { f -> f.endedMs==c.endedMs && f.startedMs==c.startedMs &&
            (f.state==CallFollowupNotice.DONE || f.state==CallFollowupNotice.NO_RECORDING) } }
        return Info(calls.size,pending)
    }
    /** The calls of [calls] the card says are sorted out: each time of a result goes to the call it falls nearest to, within [MATCH_MS]. */
    internal fun settled(calls:List<Call>,card:ContactCard?):Set<Call> {
        card ?: return emptySet()
        val times=card.settledMs+card.recent.filter { it.kind=="통화" }.map { it.atMs }
        val out=HashSet<Call>()
        for(t in times) {
            fun gap(c:Call)=when { t<c.startedMs -> c.startedMs-t;t>c.endedMs -> t-c.endedMs;else -> 0L }
            calls.filter { gap(it)<=MATCH_MS }.minByOrNull(::gap)?.let { out+=it }
        }
        return out
    }
    private fun followups(s:JSONObject):List<CallFollowupNotice.Entry> =runCatching {
        val text=s.optString(CallFollowupNotice.STATE_KEY,"");if(text.isBlank()) return emptyList()
        val a=JSONArray(text);(0 until a.length()).mapNotNull { runCatching { CallFollowupNotice.Entry.parse(a.getJSONObject(it)) }.getOrNull() } }.getOrDefault(emptyList())

    /** The call going on: [info] once per call and number (the service's verdict and the card's data ask the same). */
    private var memo:Triple<String,Any?,Info?>?=null
    fun forCall(context:Context,key:String):Info? {
        val s=runCatching { CallSession.callSettings(context) }.getOrNull() ?: return null
        synchronized(this) { memo?.let { (k,o,i) -> if(k==key && o===s) return i } }
        val card=runCatching { ContactCards.get(context,key) }.getOrNull()
        val i=runCatching { info(s,key,card) }.getOrNull()
        synchronized(this) { memo=Triple(key,s,i) }
        return i
    }
    internal fun resetForHost() { synchronized(this) { memo=null };clock={ System.currentTimeMillis() };waitMs=1_000L
        logLength={ c,h,s,e -> CallFollowupNotice.logDuration(c,CallFollowupNotice.Entry(0,"",null,h,s,e)) } }

    // ---- the words ---------------------------------------------------------------------------------------------------
    /** "09:12" (KST). */
    internal fun hm(ms:Long):String { val t=Instant.ofEpochMilli(ms).atZone(CallRecordStore.KST);return "%02d:%02d".format(t.hour,t.minute) }
    /** "(3분)", "(40초)"; empty when the length is not known. */
    internal fun length(ms:Long?):String = when {
        ms==null || ms<=0 -> ""
        ms<60_000L -> "(${(ms/1_000L).coerceAtLeast(1)}초)"
        else -> "(${(ms+30_000L)/60_000L}분)"
    }
    /** The one line: today's [today] calls with the number, [pending] of them not sorted out yet; null when none is pending. */
    internal fun line(today:Int,pending:List<Call>):String? {
        if(pending.isEmpty()) return null
        val p=pending.sortedBy { it.startedMs };val last=p.last()
        val total=maxOf(today,p.size)
        return when {
            total==1 -> "오늘 ${hm(last.startedMs)}에 통화했어요${length(last.talkMs)}. 아직 정리 중이에요"
            p.size==total -> "오늘 ${total}번 통화했어요(마지막 ${hm(last.startedMs)}). 아직 정리 중이에요"
            p.size==1 -> "오늘 ${total}번 통화했어요. ${hm(last.startedMs)} 통화${length(last.talkMs)}는 아직 정리 중이에요"
            else -> "오늘 ${total}번 통화했어요. ${p.size}번은 아직 정리 중이에요(마지막 ${hm(last.startedMs)})"
        }
    }
}
