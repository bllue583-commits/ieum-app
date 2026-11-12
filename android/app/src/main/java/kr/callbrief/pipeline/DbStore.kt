package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.LedgerPrune
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** v3.8 r18 (DESIGN_V7 4.1): what the sealed store's daily ledger prune did ([PRUNE], heartbeat `dbPrune`) and the writes
 * it refused for its size ([FULL], heartbeat `dbFull`, the light "저장 공간이 다 찼어요", S4). Numbers, days and codes only. */
internal object DbStore {
    /** {day, atMs, deleted{table: rows}, charsBefore, charsAfter, skipped, ms, copyAtMs}, the last run. */
    const val PRUNE="dbPrune"
    /** {count, firstMs, lastMs}: capacity refusals (TEXT_STORE_CAPACITY_REQUIRES_REVIEW) counted so far. */
    const val FULL="dbFull"
    /** The version code whose first prune ran (not skipped): the first run after an update is queued until it is this build's. */
    const val PRUNED_VERSION="dbPruneVersion"
    /** The light and the watchdog say "다 찼어요" while the last refusal is at most this old. */
    const val FULL_RECENT_MS=24*3_600_000L
    const val FULL_WORDS="저장 공간이 다 찼어요. 새 통화가 저장되지 않을 수 있어요. 관리하는 분께 알려 주세요."

    private val DAY=Regex("""\d{4}-\d{2}-\d{2}""")
    private fun jsonOf(v:Any?):JSONObject? = when(v) { is JSONObject -> v;is String -> runCatching { JSONObject(v) }.getOrNull();else -> null }
    private fun num(v:Any?):Any = (v as? Number)?.toLong()?.takeIf { it>=0 } ?: JSONObject.NULL

    fun pruneJson(r:LedgerPrune.Result,now:Long):JSONObject {
        val deleted=JSONObject();r.deleted.forEach { (t,n) -> deleted.put(PhoneStatus.code(t).take(40),n) }
        return JSONObject().put("day",Telemetry.day(now)).put("atMs",now).put("deleted",deleted)
            .put("charsBefore",num(r.charsBefore)).put("charsAfter",num(r.charsAfter))
            .put("skipped",r.skipped?.let(PhoneStatus::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("ms",r.ms).put("copyAtMs",num(r.copyAtMs))
    }
    /** The heartbeat's `dbPrune` from the stored value (whatever is stored under the key: numbers and codes only). */
    fun pruneHeartbeat(stored:Any?):JSONObject? {
        val o=jsonOf(stored) ?: return null
        val deleted=JSONObject()
        o.optJSONObject("deleted")?.let { d -> d.keys().forEach { k -> val n=PhoneStatus.code(k).take(40);if(n.isNotEmpty() && n.count(Char::isDigit)<6) deleted.put(n,d.optLong(k)) } }
        return JSONObject().put("day",o.optString("day").takeIf { DAY.matches(it) } ?: JSONObject.NULL).put("atMs",o.optLong("atMs"))
            .put("deleted",deleted).put("charsBefore",num(o.opt("charsBefore"))).put("charsAfter",num(o.opt("charsAfter")))
            .put("skipped",PhoneStatus.code(o.optString("skipped").takeIf { o.opt("skipped") is String } ?: "").takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("ms",num(o.opt("ms"))).put("copyAtMs",num(o.opt("copyAtMs")))
    }
    fun fullHeartbeat(stored:Any?):JSONObject {
        val o=jsonOf(stored) ?: JSONObject()
        return JSONObject().put("count",o.optLong("count",0).coerceAtLeast(0)).put("firstMs",num(o.opt("firstMs"))).put("lastMs",num(o.opt("lastMs")))
    }
    /** The last refusal as stored in the settings ([install] writes each one soon after it happens; no IO here). */
    fun lastFullMs(s:JSONObject):Long = jsonOf(s.opt(FULL))?.optLong("lastMs",0) ?: 0L
    fun fullRecent(lastMs:Long,now:Long):Boolean = lastMs>0 && now-lastMs in 0..FULL_RECENT_MS

    private val lock=Any()
    private var flushed=0L
    private val scheduled=AtomicBoolean()
    private val writer=Executors.newSingleThreadExecutor()
    /** Once per process: each refusal is written to the settings soon after, off the refusing thread (the store is locked there). */
    fun install(context:Context) {
        val app=context.applicationContext ?: context
        ProtectedDatabase.onCapacityFailure { if(scheduled.compareAndSet(false,true)) runCatching { writer.execute { try { flush(app) } finally { scheduled.set(false) } } }.onFailure { scheduled.set(false) } }
    }
    /** The refusals this process counted since the last flush go into [FULL] (the settings are not the sealed store, so this works
     * when the store is full). */
    fun flush(context:Context) = synchronized(lock) {
        val total=ProtectedDatabase.capacityFailures();if(total<=flushed) return@synchronized
        val last=ProtectedDatabase.lastCapacityFailureMs();val add=total-flushed
        runCatching { kr.callbrief.SecureSettings(context.applicationContext ?: context).update { s ->
            val o=jsonOf(s.opt(FULL)) ?: JSONObject()
            o.put("count",o.optLong("count",0)+add).put("lastMs",maxOf(o.optLong("lastMs",0),last))
            if(o.optLong("firstMs",0)<=0) o.put("firstMs",last)
            s.put(FULL,o.toString()) } }.onSuccess { flushed=total }
    }
}
