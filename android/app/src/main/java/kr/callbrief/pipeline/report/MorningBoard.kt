package kr.callbrief.pipeline.report

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * v4.4 r25-2: the morning tidy's board (tidy/briefing.md) as the report carries it since tidy.md v12 (r25-1): 전화할 곳
 * (`calls`), 답을 기다리는 중 (`waiting`), 이번 주 일정 (`week`), 진행 중인 일 (`inProgress`, with status and days), 확인 필요
 * (`check`), 끝났는지 확인할 일 (`doneCheck`) and 어제와 달라진 것 (`changed`). Every key is optional: a v11 report (or none)
 * gives empty sections and the phone works as before. A key of the wrong shape drops that section only; a row without its
 * words drops that row only. Every word is masked and cut like the rest of the report ([KakaoReport.clean]).
 * Read by the web view ([KakaoReport.webData]), the list bubble's header ([KakaoReport.list]) and home ([HomeBoard]).
 */
internal object MorningBoard {
    /** The routine's own maxima (tidy.md v12 9-1; tools/report_check.py LIMITS). */
    const val CALLS_MAX=10
    const val WAITING_MAX=20
    const val WEEK_DAYS=7
    const val WEEK_ITEMS=6
    const val IN_PROGRESS_MAX=20
    const val CHECK_MAX=3
    const val DONE_CHECK_MAX=10
    const val CHANGED_MAX=6
    /** A wait longer than this many days is shown stronger (the web view and home). */
    const val LONG_WAIT_DAYS=7

    /** Someone to call, or someone whose answer is awaited ([days] only for that). */
    data class Person(val who:String,val company:String,val what:String,val since:String,val days:Int?=null,val unsure:Boolean=false) {
        /** "가나건설 박하늘 과장" (who alone, or the company alone, when the other is missing). */
        val name:String get()=listOf(company,who).filter { it.isNotEmpty() }.joinToString(" ")
    }
    data class Day(val date:LocalDate,val items:List<String>)
    data class Issue(val what:String,val status:String="",val days:Int?=null,val unsure:Boolean=false)
    data class Line(val what:String,val who:String)
    data class Board(val calls:List<Person> =emptyList(),val waiting:List<Person> =emptyList(),val week:List<Day> =emptyList(),
                     val inProgress:List<Issue> =emptyList(),val check:List<Line> =emptyList(),val doneCheck:List<Line> =emptyList(),
                     val changed:List<String> =emptyList()) {
        val isEmpty:Boolean get()=calls.isEmpty() && waiting.isEmpty() && week.isEmpty() && inProgress.isEmpty() && check.isEmpty() &&
            doneCheck.isEmpty() && changed.isEmpty()
    }

    private fun clean(s:String?)=KakaoReport.clean(s)
    /** r25 review L: a text field, "" for null or anything that is not text (JSONObject.optString would give "null"). */
    internal fun text(o:JSONObject,key:String)=clean(o.opt(key) as? String)
    private fun objects(o:JSONObject,key:String):List<JSONObject> {
        val a=o.opt(key) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.opt(it) as? JSONObject }
    }
    /** A whole number of days, 0 or more; anything else (a word, a fraction, true) is no number. */
    private fun days(o:JSONObject):Int? = when(val v=o.opt("days")) {
        is Int -> v.takeIf { it>=0 }
        is Long -> v.takeIf { it in 0..100_000 }?.toInt()
        else -> null
    }
    private fun unsure(o:JSONObject)=o.opt("unsure")==true

    private fun people(o:JSONObject,key:String,max:Int,withDays:Boolean):List<Person> = objects(o,key).mapNotNull { p ->
        val what=text(p,"what").ifEmpty { return@mapNotNull null }
        Person(text(p,"who"),text(p,"company"),what,text(p,"since"),if(withDays) days(p) else null,unsure(p))
    }.take(max)

    /** The board of [o] (the report/YYYY-MM-DD.json object); [day] is the report's day (the week starts there). */
    fun read(o:JSONObject?,day:LocalDate):Board {
        if(o==null) return Board()
        val calls=people(o,"calls",CALLS_MAX,false)
        // Oldest first (the routine writes them so; a report that did not is put in order here, stable).
        val waiting=people(o,"waiting",WAITING_MAX,true).sortedByDescending { it.days ?: -1 }
        val week=objects(o,"week").mapNotNull { d ->
            val date=runCatching { LocalDate.parse(d.optString("date")) }.getOrNull() ?: return@mapNotNull null
            if(date.isBefore(day) || !date.isBefore(day.plusDays(WEEK_DAYS.toLong()))) return@mapNotNull null
            val raw=d.opt("items") as? JSONArray ?: return@mapNotNull null
            val items=(0 until raw.length()).mapNotNull { (raw.opt(it) as? String)?.let(::clean)?.takeIf { s -> s.isNotEmpty() } }
            if(items.isEmpty()) null else Day(date,items)
        }.groupBy { it.date }.map { (date,days) -> Day(date,days.flatMap { it.items }.take(WEEK_ITEMS)) }.sortedBy { it.date }
        val inProgress=objects(o,"inProgress").mapNotNull { i ->
            val what=text(i,"what").ifEmpty { return@mapNotNull null }
            Issue(what,text(i,"status"),days(i),unsure(i))
        }.take(IN_PROGRESS_MAX)
        fun lines(key:String,max:Int)=objects(o,key).mapNotNull { c ->
            val what=text(c,"what").ifEmpty { return@mapNotNull null };Line(what,text(c,"who")) }.take(max)
        val changed=(o.opt("changed") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.let(::clean)?.takeIf { s -> s.isNotEmpty() } } }
            ?.take(CHANGED_MAX) ?: emptyList()
        return Board(calls,waiting,week,inProgress,lines("check",CHECK_MAX),lines("doneCheck",DONE_CHECK_MAX),changed)
    }

    /** "오늘", "내일" or "" for [date] seen from [day]. */
    fun near(date:LocalDate,day:LocalDate)=when(date) { day -> "오늘";day.plusDays(1) -> "내일";else -> "" }
    /** "3일째" (nothing without a number). */
    fun daysWords(days:Int?)=days?.let { "${it}일째" } ?: ""

    // ---- the web view's part of the data (KakaoReport.webData); keys only when there is something in them ----
    internal fun putWeb(out:JSONObject,b:Board,day:LocalDate) {
        fun person(p:Person)=JSONObject().put("who",p.who).put("company",p.company).put("what",p.what).put("since",p.since).apply {
            p.days?.let { put("days",it) };if(p.unsure) put("unsure",true) }
        if(b.calls.isNotEmpty()) out.put("calls",JSONArray().apply { b.calls.forEach { put(person(it)) } })
        if(b.waiting.isNotEmpty()) out.put("waiting",JSONArray().apply { b.waiting.forEach { put(person(it)) } })
        if(b.week.isNotEmpty()) out.put("week",JSONArray().apply { b.week.forEach { d ->
            put(JSONObject().put("date",d.date.toString()).put("label",KakaoReport.dayLabel(d.date)).put("near",near(d.date,day)).put("items",JSONArray(d.items))) } })
        if(b.doneCheck.isNotEmpty()) out.put("doneCheck",JSONArray().apply { b.doneCheck.forEach { put(JSONObject().put("what",it.what).put("who",it.who)) } })
        if(b.changed.isNotEmpty()) out.put("changed",JSONArray(b.changed))
    }
    /** inProgress as the web view had it (v4.3: {what}), with status, days and unsure only when there are some. */
    internal fun webIssues(b:Board)=JSONArray().apply { b.inProgress.forEach { i ->
        put(JSONObject().put("what",i.what).apply { if(i.status.isNotEmpty()) put("status",i.status);i.days?.let { put("days",it) };if(i.unsure) put("unsure",true) }) } }
}
