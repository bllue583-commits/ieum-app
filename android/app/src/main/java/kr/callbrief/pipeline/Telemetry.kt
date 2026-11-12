package kr.callbrief.pipeline

import org.json.JSONArray
import org.json.JSONObject

/** v3.7 r17-3 (DESIGN_V7 3.3): the heartbeat's measurement blocks for the scorecard (`docs/SCORECARD.md`, `tools/inbox/score.py`),
 * made from rows the stores read. Pure JSON helpers (JVM-tested). Numbers, KST days ("yyyy-MM-dd") and schema names only:
 * never a name, a number, a record id or any call content. */
internal object Telemetry {
    const val DAY_MS=86_400_000L
    /** Calls kept in the heartbeat's `trace[]` (the newest by call end). */
    const val TRACE_MAX=50
    /** Recordings whose text start is remembered ([PipelineSettingsKeys.TRACE_TEXT_START]). */
    const val TRACE_STARTS=120
    /** Tables kept in `dbTables.top`. */
    const val DB_TOP=8
    /** Report days kept in [PipelineSettingsKeys.REPORT_COVERAGE_DAYS] (heartbeat report.recent). */
    const val COVERAGE_DAYS=7
    /** A segment over this is no routine value: counted as over7d and left out of the percentiles (DESIGN_V7 3.3). */
    const val OVER_MS=7*DAY_MS

    fun day(ms:Long):String=CallRecordStore.kstDay(ms).toString()
    fun dayStart(ms:Long):Long=CallRecordStore.kstDay(ms).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()

    /** One call's stages in epoch ms (null: not reached or not known). [endMs] = started + duration; [applied]: the analysis
     * result is applied (state ANALYZED with a summary); [firstPass]: stored long after its call (the first pass, not timed). */
    data class Stage(val endMs:Long,val durMs:Long?,val foundMs:Long?,val textStartMs:Long?,val textEndMs:Long?,val uploadMs:Long?,
        val appliedMs:Long?,val applied:Boolean,val firstPass:Boolean=false)

    /** {"p50Min","p90Min","n"} of minutes, nearest rank like the 7-day `latency` ([latency]); no p50/p90 when empty. */
    fun percentiles(minutes:List<Long>):JSONObject {
        val o=JSONObject().put("n",minutes.size)
        if(minutes.isEmpty()) return o
        val s=minutes.sorted()
        fun rank(p:Int)=s[((p*s.size+99)/100-1).coerceIn(0,s.size-1)]
        return o.put("p50Min",rank(50)).put("p90Min",rank(90))
    }

    /** `latency.segments`: endToText (call end to the text stored), textToUpload (to the request sent), uploadToApplied (to the
     * result applied), each {p50Min,p90Min,n,over7d}, over the applied calls of [rows] (not the first pass). A negative gap (a
     * clock change) counts as 0; one over 7 days is counted in over7d, not dropped silently. */
    fun segments(rows:List<Stage>):JSONObject {
        val out=JSONObject()
        val live=rows.filter { it.applied && !it.firstPass }
        fun seg(name:String,from:(Stage)->Long?,to:(Stage)->Long?) {
            var over=0;val m=ArrayList<Long>()
            for(r in live) { val a=from(r) ?: continue;val b=to(r) ?: continue
                val d=b-a;if(d>OVER_MS || d< -OVER_MS) { over++;continue };m+=maxOf(0L,d)/60_000L }
            out.put(name,percentiles(m).put("over7d",over))
        }
        seg("endToText",{ it.endMs },{ it.textEndMs })
        seg("textToUpload",{ it.textEndMs },{ it.uploadMs })
        seg("uploadToApplied",{ it.uploadMs },{ it.appliedMs })
        return out
    }
    /** Call end to applied over 7 days among the applied calls of [rows] (the 7-day `latency` drops them as outliers). */
    fun over7d(rows:List<Stage>)=rows.count { it.applied && !it.firstPass && it.appliedMs!=null && it.appliedMs-it.endMs>OVER_MS }

    /** `latency.day` (SCORECARD item 2): call end to result applied for the calls that ended on [now]'s KST day only. A call of
     * today still without its result counts with the time it has waited so far (a lower bound, so a day whose calls are applied
     * only tomorrow cannot look quick); `waiting` says how many of them there are. {p50Min,p90Min,n,waiting,day}. */
    fun dayLatency(rows:List<Stage>,now:Long):JSONObject {
        val from=dayStart(now)
        val today=rows.filter { it.endMs in from..now && !it.firstPass }
        var waiting=0
        val m=today.map { r -> if(r.applied && r.appliedMs!=null) maxOf(0L,r.appliedMs-r.endMs)/60_000L else { waiting++;maxOf(0L,now-r.endMs)/60_000L } }
        return percentiles(m).put("waiting",waiting)
    }

    /** `trace[]`: the newest [TRACE_MAX] calls (oldest first), each {endMin (minutes from its KST day's 0:00), day, durS, foundS,
     * textStartS, textEndS, uploadS, appliedS}: seconds from the call's end, null when not reached. No id, no counterpart. */
    fun trace(rows:List<Stage>,max:Int=TRACE_MAX):JSONArray {
        val a=JSONArray()
        rows.sortedBy { it.endMs }.takeLast(max).forEach { r ->
            fun s(ms:Long?)=ms?.let { (it-r.endMs)/1000 } ?: JSONObject.NULL
            a.put(JSONObject().put("endMin",(r.endMs-dayStart(r.endMs))/60_000L).put("day",day(r.endMs))
                .put("durS",r.durMs?.let { it/1000 } ?: JSONObject.NULL).put("foundS",s(r.foundMs)).put("textStartS",s(r.textStartMs))
                .put("textEndS",s(r.textEndMs)).put("uploadS",s(r.uploadMs)).put("appliedS",if(r.applied) s(r.appliedMs) else JSONObject.NULL))
        }
        return a
    }

    /** [prev] ({id: ms}) with [id] begun at [ms] when it is not there yet; the newest [TRACE_STARTS] kept. */
    fun noteTextStart(prev:JSONObject?,id:String,ms:Long):JSONObject {
        val o=prev?.let { JSONObject(it.toString()) } ?: JSONObject()
        if(o.has(id)) return o
        o.put(id,ms)
        val keys=o.keys().asSequence().toList()
        if(keys.size>TRACE_STARTS) keys.map { it to o.optLong(it) }.sortedBy { it.second }.take(keys.size-TRACE_STARTS).forEach { o.remove(it.first) }
        return o
    }

    /** `transcription.window14`: {created, failed, failedPermanent, days}: recordings of the last 14 days that entered the
     * queue, and how many different ones of them ended FAILED (for good: failedPermanent) (SCORECARD item 9). */
    fun window14(created:Int,failed:Int,failedPermanent:Int)=JSONObject().put("created",created).put("failed",failed)
        .put("failedPermanent",failedPermanent).put("days",14)

    /** The call log match reasons of the day: [prev] (stored) with the reasons of the last pass ([stats] "last", "lastMs")
     * folded in: per reason the largest count seen in one pass that day (the records still waiting are counted again in
     * every pass, so a sum would mean nothing), "runs" the passes seen. A new KST day starts from the pass's own counts. */
    fun matchDay(prev:JSONObject?,stats:JSONObject?,now:Long):JSONObject? {
        val last=stats?.optJSONObject("last") ?: return prev?.takeIf { it.optString("day")==day(now) }
        val at=stats.optLong("lastMs",0).takeIf { it>0 } ?: return prev
        val d=day(at)
        val out=if(prev!=null && prev.optString("day")==d) JSONObject(prev.toString()) else JSONObject().put("day",d).put("runs",0)
        if(out.optLong("seenMs",0)>=at) return out // this pass is in already
        out.put("seenMs",at).put("runs",out.optInt("runs",0)+1)
        for(k in MATCH_REASONS) (last.opt(k) as? Number)?.toInt()?.let { out.put(k,maxOf(out.optInt(k,0),it.coerceAtLeast(0))) }
        return out
    }
    val MATCH_REASONS=listOf("noTime","noCandidate","length","nameNull","nameDiffers","numberDiffers","several","matched","byEnd","byParty","records24h","open24h")
    /** The heartbeat's callLogMatch.day: numbers and the day only. */
    fun matchDayJson(o:JSONObject?):JSONObject? {
        o ?: return null
        val out=JSONObject().put("day",o.optString("day").takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: JSONObject.NULL).put("runs",o.optInt("runs",0))
        MATCH_REASONS.forEach { k -> out.put(k,o.optInt(k,0)) }
        return out
    }

    /** `dbTables`: the [DB_TOP] tables with the most characters (then rows) of [sizes] (name, rows, chars), each {name, rows,
     * chars, dChars} (dChars: the change since [prev], the last count; null without one). Schema names only, cut to codes. */
    fun dbTables(sizes:List<Triple<String,Long,Long>>,prev:JSONObject?,now:Long):JSONObject {
        val before=HashMap<String,Long>()
        prev?.optJSONArray("all")?.let { a -> for(i in 0 until a.length()) a.optJSONArray(i)?.let { e -> before[e.optString(0)]=e.optLong(2) } }
        val clean=sizes.map { (n,r,c) -> Triple(PhoneStatus.code(n).take(40),r.coerceAtLeast(0),c.coerceAtLeast(0)) }.filter { it.first.isNotEmpty() }
        val top=JSONArray()
        clean.sortedWith(compareByDescending<Triple<String,Long,Long>> { it.third }.thenByDescending { it.second }).take(DB_TOP).forEach { (n,r,c) ->
            top.put(JSONObject().put("name",n).put("rows",r).put("chars",c).put("dChars",before[n]?.let { c-it } ?: JSONObject.NULL)) }
        // Every table's count is kept in the settings ("all") for tomorrow's dChars; the heartbeat takes "top" only.
        val all=JSONArray();clean.forEach { (n,r,c) -> all.put(JSONArray().put(n).put(r).put(c)) }
        return JSONObject().put("atMs",now).put("day",day(now)).put("top",top).put("all",all)
            .put("totalChars",clean.sumOf { it.third }).put("totalRows",clean.sumOf { it.second })
    }
    /** The heartbeat's `dbTables` from the stored count: {day, atMs, totalRows, totalChars, top[]}, null before the first count. */
    fun dbTablesJson(o:JSONObject?):JSONObject? {
        o ?: return null
        val top=JSONArray()
        o.optJSONArray("top")?.let { a -> for(i in 0 until minOf(a.length(),DB_TOP)) a.optJSONObject(i)?.let { e ->
            val name=PhoneStatus.code(e.optString("name")).take(40);if(name.isEmpty() || name.count(Char::isDigit)>=6) return@let
            top.put(JSONObject().put("name",name).put("rows",e.optLong("rows")).put("chars",e.optLong("chars"))
                .put("dChars",(e.opt("dChars") as? Number) ?: JSONObject.NULL)) } }
        return JSONObject().put("day",o.optString("day").takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: JSONObject.NULL)
            .put("atMs",o.optLong("atMs")).put("totalRows",o.optLong("totalRows")).put("totalChars",o.optLong("totalChars")).put("top",top)
    }

    /** [prev] ({day: [calls, inReport, pending]}) with the coverage [snapshot] ([kr.callbrief.pipeline.report.ReportCoverage.snapshot])
     * kept under its report day; the newest [COVERAGE_DAYS] days. */
    fun coverageDays(prev:JSONObject?,snapshot:JSONObject?):JSONObject {
        val o=prev?.let { JSONObject(it.toString()) } ?: JSONObject()
        val d=snapshot?.optString("day")?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: return o
        o.put(d,JSONArray().put(snapshot.optInt("yesterdayCalls")).put(snapshot.optInt("inReport")).put(snapshot.optInt("pending")))
        o.keys().asSequence().toList().sorted().dropLast(COVERAGE_DAYS).forEach { o.remove(it) }
        return o
    }
    /** The heartbeat's `report.recent`: [{day, yesterdayCalls, inReport, pending}], oldest first. */
    fun coverageRecent(o:JSONObject?):JSONArray {
        val a=JSONArray();o ?: return a
        o.keys().asSequence().filter { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }.sorted().forEach { d -> val v=o.optJSONArray(d) ?: return@forEach
            a.put(JSONObject().put("day",d).put("yesterdayCalls",v.optInt(0)).put("inReport",v.optInt(1)).put("pending",v.optInt(2))) }
        return a
    }

    /** CPU time of this process so far in ms (utime + stime of /proc/self/stat), null when it cannot be read. */
    fun processCpuMs():Long? = runCatching { cpuMsOf(java.io.File("/proc/self/stat").readText(),clockTicks()) }.getOrNull()
    /** utime + stime (fields 14 and 15) of a /proc/<pid>/stat line in ms at [ticks] per second. */
    fun cpuMsOf(stat:String,ticks:Long):Long? {
        val rest=stat.substringAfterLast(')',"").trim().split(Regex("\\s+"))
        // rest[0] is field 3 (state): utime is field 14, stime 15.
        val u=rest.getOrNull(11)?.toLongOrNull() ?: return null;val s=rest.getOrNull(12)?.toLongOrNull() ?: return null
        return (u+s)*1000/ticks.coerceAtLeast(1)
    }
    private fun clockTicks():Long = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it>0 } ?: 100L
}
