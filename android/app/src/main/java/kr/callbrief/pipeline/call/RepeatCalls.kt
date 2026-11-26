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

/** v4.7 r28-1, r28-3: "the person you are talking to again today" — 45% of the recorded calls are with someone already called
 * that day, and in 82% of those the earlier call was not sorted out on the phone yet when the phone rang again. The call card
 * and its notice then say so in one line on top ([line]), facts only:
 *  - "오늘 09:12 통화(3분), 정리 중이에요" / "오늘 2번 통화(마지막 11:40), 정리 중이에요" only while a call's recording is known to be
 *    there and its result is not on the phone yet ([FILE]);
 *  - "오늘 09:12 통화(3분)" / "오늘 2번 통화(마지막 11:40)" otherwise (not known yet, no recording, cannot be sorted out);
 *  - nothing for calls whose result is on the phone ([DONE]: the card already holds them), and they are not counted.
 *
 * What it knows from (nothing new is read on the ringing path):
 *  - every answered call of 10 seconds or more that ended ([onCallEnded], off the ringing path) is kept in the settings under
 *    [KEY] as a fingerprint of the number ([LocalPrivacy.numberHash], never the number), its times and length. A call going out
 *    counts once the call log says it lasted 10 seconds or more (looked up a moment after the call, again at the next
 *    [refresh]); without the call log permission it never counts. Only today's (KST) entries are kept, at most [MAX].
 *  - each entry's state ([Call.status]) is filled in ahead by [refresh], off the ringing path: after every discovery pass
 *    ([RecordingGap.afterPass]: a file in the call's window or none), after every transcription run
 *    ([CallTranscriptionWork][kr.callbrief.pipeline.CallTranscriptionWork]) and after every result pass
 *    ([kr.callbrief.pipeline.CallRecordWork]): the call store's facts in the call's window ([CallRecordStore.repeatFacts]).
 *  - while it rings, the card's [ContactCard.settledMs] and summarised calls and the follow-up state "done" also count as done.
 *
 * A personal or excluded number never gets here (the call is ignored before); a recording found excluded or personal later
 * takes its entry away ([DROP]). */
internal object RepeatCalls {
    /** Settings key: a JSON text (so the heartbeat never carries it) of today's ended calls. */
    const val KEY="repeatCalls"
    const val MAX=80
    /** A record or file whose time is this close to a call (either side) is that call's (as [CallFollowupNotice.MATCH_MS]). */
    const val MATCH_MS=2*60_000L
    /** Calls shorter than this are not counted (a wrong number, a hang-up). */
    const val MIN_TALK_MS=10_000L
    /** A number without a card (numbers without one switched off) gets a card of the line only when one of today's calls was this long. */
    const val STANDALONE_MS=60_000L
    /** A call going out whose call-log line is still missing this long after it ended is given up (not counted). */
    const val OUT_GIVE_UP_MS=10*60_000L
    /** [Call.status]: a recording is there ([FILE]), none was found ([NONE]), it cannot be sorted out ([FINAL]: no speech, its text or
     * its analysis failed for good), its result is on the phone ([DONE]). [DROP] (excluded or personal) takes the entry away. */
    const val FILE="file";const val NONE="none";const val FINAL="final";const val DONE="done";const val DROP="drop"
    private val RANK=mapOf(NONE to 0,FILE to 1,FINAL to 2,DONE to 3)
    private const val TAG="RepeatCalls"

    /** One ended call. [talkMs]: how long it was talked (null: not known); [connected]: counted (an incoming call of 10 s or more,
     * a call going out the call log says lasted 10 s or more); false: not counted; null: not known yet. */
    data class Call(val hash:String,val startedMs:Long,val endedMs:Long,val talkMs:Long?,val out:Boolean,val connected:Boolean?,val status:String?=null) {
        fun json():JSONObject=JSONObject().put("h",hash).put("s",startedMs).put("e",endedMs).put("o",out)
            .apply { talkMs?.let { put("t",it) };connected?.let { put("c",it) };status?.let { put("st",it) } }
        fun covers(t:Long)=t>=startedMs-MATCH_MS && t<=endedMs+MATCH_MS
        fun gap(t:Long)=when { t<startedMs -> startedMs-t;t>endedMs -> t-endedMs;else -> 0L }
        companion object {
            fun parse(o:JSONObject)=Call(o.getString("h"),o.getLong("s"),o.getLong("e"),if(o.has("t")) o.optLong("t") else null,o.optBoolean("o"),
                if(o.has("c")) o.optBoolean("c") else null,o.optString("st").takeIf { it.isNotEmpty() })
        }
    }
    /** One fact of the call store in a call's window ([CallRecordStore.repeatFacts]): at [atMs] a recording ([FILE]), a result
     * ([DONE]), an end without one ([FINAL]) or an exclusion ([DROP]); [key] the record's number when it has one. */
    data class Fact(val atMs:Long,val kind:String,val key:String?=null)

    /** Today's counted calls with the number ([today], done ones too), those the line speaks of ([active]: not done) and whether
     * one of them is being sorted out ([pending]). */
    data class Info(val today:Int,val active:List<Call>,val pending:Boolean) {
        val line:String? get()=line(active,pending)
        /** A card of the line alone may come for a number without a card: one of the calls was [STANDALONE_MS] or longer. */
        val standalone:Boolean get()=active.any { (it.talkMs ?: 0)>=STANDALONE_MS }
    }

    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** The call log's length of a call going out (ms; 0: not connected; null: not there yet). Replaceable in host tests. */
    @Volatile internal var logLength:(Context,String,Long,Long)->Long?={ c,h,s,e -> CallFollowupNotice.logDuration(c,CallFollowupNotice.Entry(0,"",null,h,s,e)) }
    /** The call store's facts from..to ([CallRecordStore.repeatFacts]). Replaceable in host tests. */
    @Volatile internal var storeFacts:(Context,Long,Long)->List<Fact> ={ c,f,t -> kr.callbrief.IeumApplication.store(c).repeatFacts(f,t) }
    /** Off in host tests: the work after a call runs on the caller's thread. */
    @Volatile internal var background:Boolean=Build.FINGERPRINT!="robolectric"
    @Volatile internal var waitMs:Long=1_000L
    const val LOG_TRIES=3
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-repeat-calls").apply { isDaemon=true } } }
    internal fun awaitIdle() { if(background) worker.submit {}.get(10,TimeUnit.SECONDS) }

    // ---- after a call ------------------------------------------------------------------------------------------------
    /** [CallSession] at IDLE of a call that was not ignored: an answered call is kept for the rest of the day ([answeredMs]: when
     * it went off hook). On this object's own thread: the session's thread goes on to the card and the follow-up at once. */
    fun onCallEnded(context:Context,end:CallSession.CallEnd,answeredMs:Long?) {
        if(!end.answered) return
        val key=PhoneKey.of(end.phoneKey) ?: return
        val app=CallPipelineStartup.storage(context)
        val work={ runCatching { record(app,key,end,answeredMs) }.onFailure { Log.w(TAG,"not kept: ${it.javaClass.simpleName}") };Unit }
        if(background) worker.execute { work() } else work()
    }
    private fun record(app:Context,key:String,end:CallSession.CallEnd,answeredMs:Long?) {
        val out=end.direction==CallSession.Direction.OUT
        // A call coming in was talked from off hook to the end; a call going out from dialling, so its length comes from the log.
        val talk=if(out) null else answeredMs?.let { (end.endedMs-it).coerceAtLeast(0) } ?: return
        if(talk!=null && talk<MIN_TALK_MS) return
        var hash=""
        SecureSettings(app).update { s ->
            hash=LocalPrivacy.numberHash(s,key)
            val c=Call(hash,end.startedMs,end.endedMs,talk,out,if(out) null else true)
            s.put(KEY,write(read(s).filter { !(it.hash==c.hash && it.endedMs==c.endedMs) }+c,clock()))
        }
        if(!out) return
        // The phone writes the call-log line a moment after the call.
        var length:Long?=null
        for(i in 0 until LOG_TRIES) { length=runCatching { logLength(app,hash,end.startedMs,end.endedMs) }.getOrNull();if(length!=null) break
            if(waitMs>0 && i<LOG_TRIES-1) Thread.sleep(waitMs) }
        val l=length ?: return // asked again at the next [refresh]
        SecureSettings(app).update { s -> s.put(KEY,write(read(s).map { if(it.hash==hash && it.endedMs==end.endedMs) resolved(it,l) else it },clock())) }
    }
    /** [c] going out with the call log's length [l]: counted from [MIN_TALK_MS]; without the call log permission never. */
    private fun resolved(c:Call,l:Long):Call = if(l==CallFollowupNotice.UNREADABLE || l<MIN_TALK_MS) c.copy(connected=false) else c.copy(connected=true,talkMs=l)

    // ---- filled in ahead --------------------------------------------------------------------------------------------
    /** Fills in today's entries' states, off the ringing path, in one settings write (none when nothing changed): the call store's
     * facts in each call's window, and after a discovery pass that started at [passStartedMs] with the folder's [fileTimes] a
     * file or none for every call that ended 2 minutes before it. Calls going out still unknown are looked up in the call log
     * once more. Never throws. */
    fun refresh(context:Context,now:Long=clock(),fileTimes:List<Long>?=null,passStartedMs:Long?=null,facts:((Long,Long)->List<Fact>)?=null) {
        try {
            val app=CallPipelineStartup.storage(context)
            val settings=SecureSettings(app)
            val snap=settings.snapshot()
            val day=CallRecordStore.kstDay(now)
            val calls=read(snap).filter { CallRecordStore.kstDay(it.startedMs)==day }
            if(calls.isEmpty()) return
            val logs=calls.filter { it.out && it.connected==null }.associate { c -> (c.hash to c.endedMs) to runCatching { logLength(app,c.hash,c.startedMs,c.endedMs) }.getOrNull() }
            val from=day.atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()-MATCH_MS
            val fs=runCatching { (facts ?: { f,t -> storeFacts(app,f,t) })(from,now+MATCH_MS) }.onFailure { Log.w(TAG,"facts: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
            val before=write(read(snap),now)
            if(next(snap,now,logs,fs,fileTimes,passStartedMs)==before) return
            settings.update { s -> s.put(KEY,next(s,now,logs,fs,fileTimes,passStartedMs)) }
        } catch(e:Exception) { Log.w(TAG,"not refreshed: ${e.javaClass.simpleName}") }
    }
    /** [KEY] of [s] with the facts applied (see [refresh]). */
    internal fun next(s:JSONObject,now:Long,logs:Map<Pair<String,Long>,Long?>,facts:List<Fact>,fileTimes:List<Long>?,passStartedMs:Long?):String {
        var calls=read(s).map { c -> if(!c.out || c.connected!=null) c else when(val l=logs[c.hash to c.endedMs]) {
            null -> if(now-c.endedMs>=OUT_GIVE_UP_MS) c.copy(connected=false) else c
            else -> resolved(c,l) } }
        val found=HashMap<Call,MutableSet<String>>()
        fun put(t:Long,kind:String,among:List<Call>) { among.filter { it.covers(t) }.minByOrNull { it.gap(t) }?.let { found.getOrPut(it) { HashSet() }+=kind } }
        for(f in facts) {
            val k=PhoneKey.of(f.key)
            val among=if(k==null) calls else setOf(LocalPrivacy.numberHash(s,k),PrivacyRules.hash(k)).let { h -> calls.filter { it.hash in h } }
            put(f.atMs,f.kind,among)
        }
        fileTimes?.forEach { put(it,FILE,calls) }
        calls=calls.mapNotNull { c ->
            val kinds=found[c].orEmpty()
            if(DROP in kinds) return@mapNotNull null
            var st=c.status
            for(k in kinds) if((RANK[k] ?: -1)>(RANK[st] ?: -1)) st=k
            // A good discovery pass after the call found no file in its window: no recording (a later file still makes it [FILE]).
            if(st==null && fileTimes!=null && passStartedMs!=null && passStartedMs>=c.endedMs+MATCH_MS) st=NONE
            c.copy(status=st)
        }
        return write(calls,now)
    }
    /** After a good discovery pass ([RecordingGap.afterPass]): the folder's file times and the call store's facts. */
    fun afterPass(context:Context,passStartedMs:Long,now:Long,fileTimes:List<Long>) = refresh(context,now,fileTimes,passStartedMs)

    internal fun read(s:JSONObject):List<Call> =runCatching { val a=JSONArray(s.optString(KEY,"[]").ifBlank { "[]" })
        (0 until a.length()).mapNotNull { runCatching { Call.parse(a.getJSONObject(it)) }.getOrNull() } }.getOrDefault(emptyList())
    /** Only today's calls (KST), the newest [MAX]. */
    internal fun write(calls:List<Call>,now:Long):String {
        val day=CallRecordStore.kstDay(now)
        return JSONArray().apply { calls.filter { CallRecordStore.kstDay(it.startedMs)==day }.sortedBy { it.startedMs }.takeLast(MAX).forEach { put(it.json()) } }.toString()
    }

    // ---- while it rings ----------------------------------------------------------------------------------------------
    /** What to say of [key] at [now] from the call's settings [s] and the number's [card]; null when no call with it today (KST)
     * counts. Reads nothing: the settings are the call's snapshot, the card is the one the card shows. */
    fun info(s:JSONObject,key:String,card:ContactCard?,now:Long=clock()):Info? {
        val raw=s.optString(KEY,"");if(raw.isBlank()) return null
        val k=PhoneKey.of(key) ?: return null
        val day=CallRecordStore.kstDay(now)
        val mine=setOf(LocalPrivacy.numberHash(s,k),PrivacyRules.hash(k))
        val calls=read(s).filter { it.connected==true && it.startedMs<=now && it.hash in mine && CallRecordStore.kstDay(it.startedMs)==day }.sortedBy { it.startedMs }
        if(calls.isEmpty()) return null
        // Done also by what the card and the follow-up state say (a result applied since the last refresh).
        val follow=followups(s).filter { it.hash in mine }
        val byCard=settled(calls,card)
        fun done(c:Call)=c.status==DONE || c in byCard || follow.any { f -> f.endedMs==c.endedMs && f.startedMs==c.startedMs && f.state==CallFollowupNotice.DONE }
        fun noRecording(c:Call)=follow.any { f -> f.endedMs==c.endedMs && f.startedMs==c.startedMs && f.state==CallFollowupNotice.NO_RECORDING }
        val active=calls.filterNot(::done)
        // Calls are not being recorded at all (the last check, while fresh): nothing is being sorted out.
        val notRecorded=runCatching { RecordingGap.last(s)?.let { it.code==PipelineSettingsKeys.GAP_NO_RECORDING && now-it.atMs<RecordingGap.STALE_MS }==true }.getOrDefault(false)
        val pending=!notRecorded && active.any { it.status==FILE && !noRecording(it) }
        return Info(calls.size,active,pending)
    }
    /** The calls of [calls] the card says are sorted out: each time of a result goes to the call it falls nearest to, within [MATCH_MS]. */
    internal fun settled(calls:List<Call>,card:ContactCard?):Set<Call> {
        card ?: return emptySet()
        val times=card.settledMs+card.recent.filter { it.kind=="통화" }.map { it.atMs }
        val out=HashSet<Call>()
        for(t in times) calls.filter { it.covers(t) }.minByOrNull { it.gap(t) }?.let { out+=it }
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
        logLength={ c,h,s,e -> CallFollowupNotice.logDuration(c,CallFollowupNotice.Entry(0,"",null,h,s,e)) }
        storeFacts={ c,f,t -> kr.callbrief.IeumApplication.store(c).repeatFacts(f,t) } }

    // ---- the words ---------------------------------------------------------------------------------------------------
    /** "09:12" (KST). */
    internal fun hm(ms:Long):String { val t=Instant.ofEpochMilli(ms).atZone(CallRecordStore.KST);return "%02d:%02d".format(t.hour,t.minute) }
    /** "(3분)", "(40초)"; empty when the length is not known. */
    internal fun length(ms:Long?):String = when {
        ms==null || ms<=0 -> ""
        ms<60_000L -> "(${(ms/1_000L).coerceAtLeast(1)}초)"
        else -> "(${(ms+30_000L)/60_000L}분)"
    }
    /** The one line about [active] (today's calls not done); ", 정리 중이에요" when one of them is being sorted out ([pending]). */
    internal fun line(active:List<Call>,pending:Boolean):String? {
        if(active.isEmpty()) return null
        val last=active.maxBy { it.startedMs }
        val head=if(active.size==1) "오늘 ${hm(last.startedMs)} 통화${length(last.talkMs)}" else "오늘 ${active.size}번 통화(마지막 ${hm(last.startedMs)})"
        return if(pending) "$head, 정리 중이에요" else head
    }
}
