package kr.callbrief.pipeline

import org.json.JSONObject

/** v3.2 (r12-6, user 11/5 22:57): "오늘 통화 이야기" at the top of home. The hourly task writes 2 or 3 sentences about today's
 * calls to story/today.json; the result pass keeps it in [PipelineSettingsKeys.STORY]. When it is not today's (or there is
 * none), the phone tells it itself from today's summaries. */
internal object TodayStory {
    /** What the card shows: its heading count and its words. */
    data class Shown(val calls:Int,val text:String)
    /** The story file as kept: a JSON object with day, text, calls and atMs; null when it is not one. */
    fun parse(json:String?):JSONObject? = runCatching { JSONObject(json ?: return null) }.getOrNull()
        ?.takeIf { it.optString("day").isNotBlank() && it.optString("text").isNotBlank() }
        ?.let { JSONObject().put("day",it.optString("day")).put("text",it.optString("text")).put("calls",it.optInt("calls",0)).put("atMs",it.optLong("atMs",0)) }
    /** Up to three of today's summarized calls, latest first, as "who: first sentence." in one run of words. */
    fun fallback(calls:List<CallRecord>):String = calls.filter { it.kind=="통화" && it.state==CallRecordStore.ANALYZED && !it.summary.isNullOrBlank() }
        .sortedByDescending { it.dayMs }.take(3)
        .joinToString(" ") { "${who(it)}: ${CallUi.firstSentence(it.summary!!)}" }
    /** The person's name if known, else the company, else the number as recorded. */
    private fun who(c:CallRecord)=(c.linkedPerson ?: c.analysis?.counterpartName)?.takeIf { it.isNotBlank() } ?: c.displayName
    /** Today's summarized business calls. */
    fun summarized(calls:List<CallRecord>)=calls.count { it.kind=="통화" && it.state==CallRecordStore.ANALYZED }
    /** The card for [today] from the kept story [kept] and today's records [calls]; null when no call is summarized yet. */
    fun shown(kept:String?,today:java.time.LocalDate,calls:List<CallRecord>):Shown? {
        val story=parse(kept)?.takeIf { it.optString("day")==today.toString() }
        val n=maxOf(story?.optInt("calls",0) ?: 0,summarized(calls))
        if(n==0) return null
        val text=story?.optString("text")?.trim()?.takeIf { it.isNotEmpty() } ?: fallback(calls)
        return Shown(n,clip(clean(text)))
    }
    // ---- v3.5 r15-0 ----
    /** "녹음 5개 중 정리 3개" (user 11/6: the numbers count recordings): the story's heading, home's numbers and the evening report's
     * title say this one count. [recordings]: today's recordings (call records and recordings still without one); never fewer
     * than [summarized]. */
    fun countLine(recordings:Int,summarized:Int)="녹음 ${maxOf(recordings,summarized)}개 중 정리 ${summarized}개"
    /** The card shows at most this many letters (about seven lines at reading size): whole sentences only, so the words never
     * stop in the middle of a sentence (the task may write 200 characters, hourly.md 5-1). */
    const val MAX_CHARS=120
    /** [t] cut to whole sentences within [max] letters; a first sentence longer than that is cut at its last space before [max]. */
    fun clip(t:String,max:Int=MAX_CHARS):String {
        val s=t.trim();if(s.length<=max) return s
        val sentences=Regex("(?<=[.!?])\\s+").split(s)
        val out=StringBuilder()
        for(x in sentences) { val next=if(out.isEmpty()) x else "$out $x";if(next.length>max) break;out.clear();out.append(next) }
        if(out.isNotEmpty()) return out.toString()
        val at=s.lastIndexOf(' ',max).takeIf { it>max/2 } ?: max
        return s.substring(0,at).trim().trimEnd(',')
    }
    /** The screen rules hold for words that come from the task too: no middle dot (a comma instead) and no ellipsis. */
    fun clean(t:String)=t.replace(Regex("\\s*[\u00B7\u30FB\uFF65]\\s*"),", ").replace("\u2026","").replace("...","").trim()
}
