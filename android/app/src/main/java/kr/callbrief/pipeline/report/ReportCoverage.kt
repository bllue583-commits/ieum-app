package kr.callbrief.pipeline.report

import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.PipelineSettingsKeys
import org.json.JSONObject
import java.time.LocalDate

/**
 * v3.7 r17-2 (DESIGN_V7 3.2 M3, 3.3): what the morning report covered, for the heartbeat's `report.coverage`
 * {yesterdayCalls, inReport, pending} (SCORECARD item 1). The phone counts it ([CallRecordStore.dayCoverage]) right before the
 * 05:30 KakaoTalk send ([KakaoReport.send]), or at the 06:00 app notice on a day nothing was sent. Kept in the settings under
 * [PipelineSettingsKeys.REPORT_COVERAGE]; numbers, a day and a source code only.
 *
 * The base (yesterdayCalls) is "녹음이 있고 개인 번호가 아닌 어제 통화": before the analysis nobody knows whether a call is work.
 * inReport is those of them analysed before the report was made (the routine's generatedMs); pending is those still waiting
 * for their text or their analysis at the count.
 */
object ReportCoverage {
    const val DAY="day";const val CALLS="yesterdayCalls";const val IN_REPORT="inReport";const val PENDING="pending"
    const val AT_MS="atMs";const val SOURCE="source"
    /** [SOURCE] when no report went out (no link, the switch off): counted at the app's morning notice. */
    const val SOURCE_NONE="none"

    /** Writes the coverage of the report of [reportDay] (its "어제" is the day before) into the settings object [o]. */
    internal fun note(o:JSONObject,reportDay:LocalDate,c:CallRecordStore.DayCoverage,inReport:Int,source:String,now:Long) {
        o.put(PipelineSettingsKeys.REPORT_COVERAGE,JSONObject().put(DAY,reportDay.toString()).put(CALLS,c.calls.coerceAtLeast(0))
            .put(IN_REPORT,inReport.coerceIn(0,c.calls.coerceAtLeast(0))).put(PENDING,c.pending.coerceIn(0,c.calls.coerceAtLeast(0)))
            .put(AT_MS,now).put(SOURCE,source.filter { it.isLetter() }.take(16)).toString())
    }

    /** For the heartbeat (r17-3 calls it): the last coverage kept in the settings snapshot [s], as
     * {"day","yesterdayCalls","inReport","pending","atMs","source"}, or null when there is none or it cannot be read.
     * Pure: reads [s] only. A new object each call, so the caller may change it. */
    fun snapshot(s:JSONObject?):JSONObject? {
        val raw=s?.opt(PipelineSettingsKeys.REPORT_COVERAGE) ?: return null
        val o=when(raw) { is JSONObject -> raw;is String -> runCatching { JSONObject(raw) }.getOrNull();else -> null } ?: return null
        val day=o.optString(DAY).takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: return null
        fun n(k:String)=(o.opt(k) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        return JSONObject().put(DAY,day).put(CALLS,n(CALLS)).put(IN_REPORT,n(IN_REPORT)).put(PENDING,n(PENDING))
            .put(AT_MS,(o.opt(AT_MS) as? Number)?.toLong() ?: 0L).put(SOURCE,o.optString(SOURCE).filter { it.isLetter() }.take(16))
    }
    /** True when [s] already holds the coverage of the report of [reportDay]. */
    fun hasDay(s:JSONObject?,reportDay:LocalDate)=snapshot(s)?.optString(DAY)==reportDay.toString()
}
