package kr.callbrief.pipeline

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors

/** v3.7 r17-3 (DESIGN_V7 3.3, SCORECARD item 10): how the app is used, as counts of a KST day only (no screen content, no
 * names, no times of day). The heartbeat's `use{today{..}, prev{..}}`; `prev` is the day before (zeros on a day the app was
 * not opened, null before the first day counted). Kinds: [OPENS] the app came to the front, [HOME_SEC] seconds home was in
 * front, [TASK_DONE] [다 됐어요] pressed (in the app or on the notice), [NOTIF_TAP] the app opened from one of its notices,
 * [CARD_EXPAND] the call card's band opened (the card's own `expanded` count of the day), [KAKAO_OPEN] opened from a
 * KakaoTalk report's button.
 *
 * Counts are gathered in memory ([add], [home]) and written to the sealed settings ([PipelineSettingsKeys.USE_STATS]) only when
 * the app goes to the background ([flushLater]) and at the heartbeat ([flush]): no sealed write on a tap. */
internal object UseStats {
    const val OPENS="opens";const val HOME_SEC="homeSec";const val TASK_DONE="taskDone";const val NOTIF_TAP="notifTap"
    const val CARD_EXPAND="cardExpand";const val KAKAO_OPEN="kakaoOpen"
    val KINDS=listOf(OPENS,HOME_SEC,TASK_DONE,NOTIF_TAP,CARD_EXPAND,KAKAO_OPEN)

    private val lock=Any()
    /** Not yet written: KST day -> kind -> count. */
    private val pending=java.util.TreeMap<String,MutableMap<String,Long>>()
    /** Home in front since (elapsed ms) and on which wall clock day it began; null while not in front. */
    private var homeSince:Long?=null;private var homeDay:String?=null
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    @Volatile internal var elapsed:()->Long={ SystemClock.elapsedRealtime() }
    private val writer by lazy { Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-use").apply { isDaemon=true } } }

    /** One more of [kind] now (any thread; never throws). */
    fun add(kind:String,n:Long=1) { if(kind !in KINDS || n<=0) return
        synchronized(lock) { val m=pending.getOrPut(Telemetry.day(clock())) { HashMap() };m[kind]=(m[kind] ?: 0L)+n } }
    /** Home is in front ([front] true) or not: the seconds in between go to [HOME_SEC] of the day home came to the front. */
    fun home(front:Boolean) = synchronized(lock) {
        val t=elapsed()
        if(front) { if(homeSince==null) { homeSince=t;homeDay=Telemetry.day(clock()) } }
        else homeSince?.let { since -> val s=(t-since)/1000;homeSince=null
            if(s>0) { val m=pending.getOrPut(homeDay ?: Telemetry.day(clock())) { HashMap() };m[HOME_SEC]=(m[HOME_SEC] ?: 0L)+minOf(s,DAY_SEC) } }
    }
    private const val DAY_SEC=86_400L
    private fun take():Map<String,Map<String,Long>> = synchronized(lock) { val c=pending.mapValues { HashMap(it.value) };pending.clear();c }
    private fun putBack(c:Map<String,Map<String,Long>>) = synchronized(lock) { c.forEach { (d,m) -> val p=pending.getOrPut(d) { HashMap() };m.forEach { (k,v) -> p[k]=(p[k] ?: 0L)+v } } }

    /** Writes what was gathered into the settings, on the caller's (background) thread. The call card's `expanded` of today
     * is read from its own counts. Never throws; a failed write keeps the counts for the next one. */
    fun flush(context:Context,now:Long=clock()) {
        val adds=take()
        try {
            val settings=kr.callbrief.SecureSettings(context.applicationContext)
            val s=settings.snapshot()
            val old=stored(s)
            val next=merge(old,adds,now,cardExpandToday(s,now))
            if(old?.toString()!=next.toString()) settings.update { it.put(PipelineSettingsKeys.USE_STATS,next.toString()) }
        } catch(e:Exception) { putBack(adds);android.util.Log.w("ieum.use","not written: ${e.javaClass.simpleName}") }
    }
    /** [flush] on its own thread (the app going to the background). */
    fun flushLater(context:Context) { val app=context.applicationContext;runCatching { writer.execute { flush(app) } } }

    fun stored(s:JSONObject?):JSONObject? = when(val v=s?.opt(PipelineSettingsKeys.USE_STATS)) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null }
    /** The call card's own count of band openings today ([kr.callbrief.pipeline.call.CardStats], read only), null on another day. */
    internal fun cardExpandToday(s:JSONObject,now:Long):Long? = runCatching {
        val c=kr.callbrief.pipeline.call.CardStats.read(s)
        if(c.optInt("day",0)!=kr.callbrief.pipeline.call.CardStats.day(now)) null
        else c.optJSONObject("today")?.optLong(kr.callbrief.pipeline.call.CardStats.EXPANDED,0)
    }.getOrNull()

    private fun blank(day:String)=JSONObject().put("day",day).apply { KINDS.forEach { put(it,0) } }
    private fun prevDay(day:String)=java.time.LocalDate.parse(day).minusDays(1).toString()
    /** [o] moved on to [day]: an older today becomes prev when it was the day before, else prev is a blank day before [day]
     * (no use that day), or nothing before the first day counted ("since"). */
    private fun roll(o:JSONObject,day:String) {
        val t=o.optJSONObject("today")
        if(t!=null && t.optString("day")>=day) return
        val before=prevDay(day)
        val since=o.optString("since").ifEmpty { day }
        o.put("prev",when { t!=null && t.optString("day")==before -> t;before>=since -> blank(before);else -> JSONObject.NULL })
        o.put("today",blank(day))
    }
    /** Pure: [old] with [adds] (day -> kind -> count) counted and rolled on to [now]'s KST day; [cardToday] sets today's
     * [CARD_EXPAND] (the card keeps its own count of the day). Counts of a day before prev are dropped. */
    fun merge(old:JSONObject?,adds:Map<String,Map<String,Long>>,now:Long,cardToday:Long?=null):JSONObject {
        val o=old?.let { JSONObject(it.toString()) } ?: JSONObject()
        val first=(adds.keys+Telemetry.day(now)).minOrNull()!!
        if(o.optString("since").isEmpty()) o.put("since",first)
        for((d,m) in adds.toSortedMap()) {
            if(o.optJSONObject("today")==null) o.put("today",blank(d))
            roll(o,d)
            val target=listOfNotNull(o.optJSONObject("today"),o.optJSONObject("prev")).firstOrNull { it.optString("day")==d } ?: continue
            m.forEach { (k,v) -> if(k in KINDS) target.put(k,target.optLong(k,0)+v) }
        }
        if(o.optJSONObject("today")==null) o.put("today",blank(Telemetry.day(now)))
        roll(o,Telemetry.day(now))
        cardToday?.let { c -> o.getJSONObject("today").let { t -> t.put(CARD_EXPAND,maxOf(t.optLong(CARD_EXPAND,0),c)) } }
        return o
    }
    /** The heartbeat's `use`: {today{day,..}, prev{day,..}|null}, as of [now] (a stored day not yet rolled is rolled here). */
    fun heartbeat(stored:JSONObject?,now:Long):JSONObject {
        val o=merge(stored,emptyMap(),now)
        fun clean(d:JSONObject?)=d?.let { JSONObject().put("day",it.optString("day")).apply { KINDS.forEach { k -> put(k,it.optLong(k,0).coerceAtLeast(0)) } } }
        return JSONObject().put("today",clean(o.optJSONObject("today"))).put("prev",clean(o.optJSONObject("prev")) ?: JSONObject.NULL)
    }
    internal fun resetForHost() = synchronized(lock) { pending.clear();homeSince=null;homeDay=null;clock={ System.currentTimeMillis() };elapsed={ SystemClock.elapsedRealtime() } }
}
