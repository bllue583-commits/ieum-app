package kr.callbrief.pipeline

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import java.time.LocalDate

/** "통화": one KST day of calls (time, who, the first sentence of the summary), one tap to a detail page. Every call of
 * the phone's call log is listed with them in time order (v2.4, 11/4 user): a missed or rejected call as "부재중 전화"
 * with 다시 걸기, an answered call without a recording as "녹음 없음" (or, right after the call, "녹음을 글자로 바꾸는 중이에요"),
 * a call with a number found personal as "개인 통화" (who and when only). */
internal class CallsScreen(a:MainActivity):AppScreen(a) {
    companion object {
        /** Lines built at once, then per later part (see [fill]). */
        const val FIRST_ROWS=30;const val MORE_ROWS=40
        /** Typing in the search line waits this long before the calls are searched again (design r8-4). */
        const val SEARCH_WAIT_MS=250L
        /** A call-log line within this of a recording's time is that recording. */
        const val NEAR_MS=2*60_000L
        /** An answered call this recent, with the recording folder connected, is still being turned into text. */
        const val FRESH_MS=12*3_600_000L
        /** A call waiting this long for its result says it is late. */
        const val LATE_MS=6*3_600_000L
        const val TRANSCRIBING="녹음을 글자로 바꾸는 중이에요"
        const val NO_SPEECH="녹음에서 말소리를 찾지 못했어요"
        /** The time the call screens measure against; tests set it. */
        @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
        /** Test hook: runs on the IO thread right before a day is read (lets a test hold the read while it looks at the screen). */
        @Volatile internal var readHook:()->Unit={}

        /** The line under the title: "통화 7건, 부재중 2건, 문자 1건". Personal calls and calls without a recording count as calls. */
        internal fun counts(calls:List<CallRecord>,logs:List<LoggedCall>):String {
            val missed=missedCount(logs)
            val n=calls.groupingBy { it.kind }.eachCount().toMutableMap()
            n["통화"]=(n["통화"] ?: 0)+logs.size-missed
            if(missed>0) n["부재중"]=missed
            return n.entries.filter { it.value>0 }.sortedBy { listOf("통화","부재중","문자","메일").indexOf(it.key) }.joinToString(", ") { "${it.key} ${it.value}건" }.ifEmpty { "통화 0건" }
        }
        private fun missedCount(logs:List<LoggedCall>)=logs.count { !it.personal && it.kind in setOf(FirstPass.LogKind.MISSED,FirstPass.LogKind.BLOCKED) }
        /** "오늘 통화" on home: the same "통화 n건" as the call list's line ([counts] of [visibleLogs]), records and call-log lines. */
        internal fun callCount(calls:List<CallRecord>,logs:List<LoggedCall>):Int {
            val shown=visibleLogs(calls,logs)
            return calls.count { it.kind=="통화" }+shown.size-missedCount(shown)
        }
        /** The call-log lines the list shows: an answered call already stored as a record (within [NEAR_MS], and not yet tied
         * to it by the next pass) is that record's line, not a second one. */
        internal fun visibleLogs(calls:List<CallRecord>,logs:List<LoggedCall>):List<LoggedCall> {
            val starts=calls.filter { it.kind=="통화" }.mapNotNull { it.startedMs }
            if(starts.isEmpty()) return logs
            return logs.filterNot { e -> !e.personal && e.kind==FirstPass.LogKind.NO_RECORDING && starts.any { Math.abs(it-e.startMs)<=NEAR_MS } }
        }
        /** An answered or made call with no record yet that is being turned into text: a transcription job of that time
         * (within [NEAR_MS]), or, without one, a call of the last [FRESH_MS] while the recording folder is connected. */
        internal fun transcribing(e:LoggedCall,now:Long,folder:Boolean,jobStarts:List<Long>):Boolean {
            if(e.personal || e.kind!=FirstPass.LogKind.NO_RECORDING) return false
            if(jobStarts.any { Math.abs(it-e.startMs)<=NEAR_MS }) return true
            return folder && now-e.startMs in 0 until FRESH_MS
        }
        /** A record's state in plain words (design r8-4): being sorted out, late after [LATE_MS], or a recording with no
         * speech in it, which trying again cannot change. */
        internal fun stateWords(c:CallRecord,now:Long=clock()):String = when {
            c.state==CallRecordStore.FAILED && c.error=="EMPTY_TRANSCRIPT" -> NO_SPEECH
            c.state==CallRecordStore.TRANSCRIBED && now-c.createdMs>LATE_MS -> "정리가 늦어지고 있어요"
            c.state==CallRecordStore.TRANSCRIBED -> "정리하고 있어요"
            else -> CallUi.state(c.state)
        }
        /** v3.1 (DESIGN_V4 2.1-4, r12-1): an answered or made call-log line with no record yet, in words. Being turned into text
         * only while a transcription job's recording falls in the call's window ([RecordingGap.window]); without one, still
         * being looked for up to [SEARCH_MS] after the call ended, then no recording. Null for lines this does not cover
         * (personal, missed, not connected, blocked). The call list puts it after "받은 전화 3분, " (r12-5). */
        internal fun stateWords(e:LoggedCall,now:Long,jobStarts:List<Long>):String? {
            if(e.personal || e.kind!=FirstPass.LogKind.NO_RECORDING) return null
            val w=RecordingGap.window(e.startMs,e.durationMs)
            return when {
                jobStarts.any { it in w } -> TRANSCRIBING
                now<e.startMs+e.durationMs+SEARCH_MS -> SEARCHING
                else -> NO_RECORDING
            }
        }
        /** After a call ends, its recording is looked for this long before the line says there is none. */
        const val SEARCH_MS=10*60_000L
        const val SEARCHING="녹음을 찾는 중이에요"
        const val NO_RECORDING="녹음 없음"
        /** Whether [다시 정리하기] can help: a failed call, except one whose recording had no speech. */
        internal fun canRetry(c:CallRecord)=c.state==CallRecordStore.FAILED && c.error!="EMPTY_TRANSCRIPT"
        /** One call made searchable once: its record, all its words in lower case, and the same without spaces. */
        internal class Searchable(val record:CallRecord,val words:String,val packed:String) {
            constructor(c:CallRecord,words:String):this(c,words,words.filterNot { it.isWhitespace() })
            fun matches(query:List<String>)=query.all { words.contains(it) || packed.contains(it) }
        }
        internal fun searchable(c:CallRecord)=Searchable(c,listOfNotNull(c.displayName,c.counterpart,c.analysis?.counterpartName,c.analysis?.counterpartCompany,
            c.linkedPerson,c.summary,c.analysis?.summary,c.transcript).joinToString(" ").lowercase())
        internal fun queryWords(q:String)=q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
    }
    private val body:LinearLayout get()=a.column
    private var day:LocalDate=CallUi.today()
    /** The call whose detail page is open, or null on the list. */
    override var detail:String?=null
    private var calls:List<CallRecord> =emptyList()
    /** The day's call-log lines that have no record of their own ([CallRecordStore.dayLog]). */
    private var logs:List<LoggedCall> =emptyList()
    /** When the recordings still waiting for their words were made (this day), and whether the folder is connected. */
    private var jobStarts:List<Long> =emptyList()
    private var folder=false
    /** True from a day change (or the first opening) until that day is read: the list shows "불러오는 중이에요". */
    private var reading=true
    /** Where the list was scrolled when a detail page opened from it; put back once the list is drawn again (design G1). */
    private var listY=0
    /** A scroll position to put back after the next list drawing (and its later parts). */
    private var pendingY=0
    private val handler=Handler(Looper.getMainLooper())
    private val searchLater=Runnable { if(live() && detail==null && ::listBox.isInitialized) fill() }

    override fun enter() {
        // The column was cleared (another screen was in front): what was read before at once, or the title and "불러오는 중이에요"
        // the first time, then the read.
        if(body.childCount==0) { if(calls.isEmpty() && logs.isEmpty()) reading=true;render() }
        load()
    }
    override fun leave() { handler.removeCallbacks(searchLater) }
    /** The calls tab tapped while calls is in front: today's list from the top, no search. */
    override fun reselect() {
        handler.removeCallbacks(searchLater)
        val newDay=CallUi.today()!=day
        detail=null;query="";day=CallUi.today();listY=0;pendingY=0;shown=null
        if(newDay) { calls=emptyList();logs=emptyList();reading=true }
        render();load();CallUi.scrollTo(body,0)
    }
    override fun back():Boolean { if(detail==null) return false;closeDetail();return true }
    /** From another screen (home memo, a check item, a company, a notification): this day, and this call open when [callId] is given. */
    fun show(d:LocalDate,callId:String?) {
        if(d!=day) { calls=emptyList();logs=emptyList();jobStarts=emptyList();reading=true }
        handler.removeCallbacks(searchLater)
        day=d;query="";detail=callId;listY=0;pendingY=0;shown=null
    }
    /** Opens the detail page of a call of the list (or of the search), keeping where the list was and what was typed. */
    private fun openDetail(id:String) { if(!a.once("call:$id")) return;listY=CallUi.scrollY(body);detail=id;render() }
    private fun closeDetail() { detail=null;pendingY=listY;listY=0;render() }
    override fun save(out:Bundle) { out.putString("calls_day",day.toString());detail?.let { out.putString("calls_detail",it) };out.putString("calls_query",query) }
    override fun restore(saved:Bundle) {
        saved.getString("calls_day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { day=it }
        detail=saved.getString("calls_detail");query=saved.getString("calls_query") ?: ""
    }

    private fun load() {
        val target=day
        a.lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                // Reads only (r8-1): the call log is matched in the discovery and upload passes, not at every screen open.
                readHook()
                val storage=CallPipelineStartup.storage(a)
                val s=kr.callbrief.IeumApplication.store(storage)
                val from=target.atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
                val to=target.plusDays(1).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
                val dayCalls=s.day(target)
                Read(dayCalls,runCatching { s.dayLog(target) }.getOrDefault(emptyList()),
                    runCatching { s.transcribingStarts(from-NEAR_MS,to+NEAR_MS) }.getOrDefault(emptyList()),
                    runCatching { SecureSettings(storage).snapshot().optString("treeUri").isNotBlank() }.getOrDefault(false),
                    runCatching { s.personIdsOf(dayCalls.map { it.recordId }) }.getOrDefault(emptyMap()))
            } }
            if(!live()||target!=day)return@launch
            result.onSuccess { calls=it.calls;logs=it.logs;jobStarts=it.jobs;folder=it.folder;people=people+it.people;reading=false;pool=null;render() }
            result.onFailure {
                reading=false
                body.removeAllViews();CallUi.band(body,"${CallUi.dayWord(day)}의 통화")
                CallUi.retryCard(body,"저장된 통화를 읽지 못했어요. 잠시 후 다시 불러와 주세요.") { reading=true;render();load() }
            }
        }
    }
    private class Read(val calls:List<CallRecord>,val logs:List<LoggedCall>,val jobs:List<Long>,val folder:Boolean,val people:Map<String,String>)
    /** The person each shown call is linked to, by record id: the badge and the name open the person page (v2.8). */
    private var people:Map<String,String> =emptyMap()
    /** Named people and their company, read with the search pool: a search shows matching people above the calls (v2.8). */
    private var peoplePool:List<Pair<PersonRow,String?>> =emptyList()
    /** ‹ ›: the new date and arrows at once with "불러오는 중이에요" in place of the list, then the read (review #16). */
    private fun go(d:LocalDate) {
        if(d>CallUi.today()) return
        day=d;detail=null;calls=emptyList();logs=emptyList();jobStarts=emptyList();reading=true;listY=0;pendingY=0
        render();load()
    }

    private var shown:String?=null
    private fun record(id:String)=calls.firstOrNull { it.recordId==id } ?: pool?.firstOrNull { it.record.recordId==id }?.record
    private fun render() {
        val record=detail?.let(::record)
        if(record==null && detail!=null && reading) {
            // A call opened from elsewhere, its day still being read.
            body.removeAllViews();CallUi.band(body,"통화");CallUi.loading(body);return
        }
        val mode=if(record!=null) "detail" else "list"
        val keep=if(mode==shown) CallUi.scrollY(body) else 0;shown=mode
        body.removeAllViews()
        if(record!=null) { renderDetail(record);CallUi.restoreScroll(body,keep);return }
        detail=null
        // Back from a detail page: the list where it was, once the parts of it above that place are drawn (see [fill]).
        val y=if(pendingY>0) pendingY else keep;pendingY=0
        CallUi.band(body,"${CallUi.dayWord(day)}의 통화",if(reading) null else counts(calls,visibleLogs(calls,logs)))
        val search=ScreenParts.searchBox(a,"업체, 사람, 통화 내용 찾기",query,"calls_search") { q ->
            if(q==query) return@searchBox
            query=q;handler.removeCallbacks(searchLater)
            // Back to the day at once; a search waits until typing stops for a moment.
            if(q.isBlank()) fill() else handler.postDelayed(searchLater,SEARCH_WAIT_MS)
        }
        body.addView(search)
        listBox=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="calls_box" }
        body.addView(listBox)
        fill()
        a.putBack(y)
    }

    /** What is typed in the search line ("" shows the day). */
    private var query=""
    private lateinit var listBox:LinearLayout
    /** Every call since 10/1 for the search, each made searchable once (read once per opening of the screen, off the main thread). */
    private var pool:List<Searchable>? =null
    private var poolLoading=false

    /** One line of the call-log entries ([LoggedCall]): who and when, and what kind of call it was. Nothing to open. */
    private fun logRow(e:LoggedCall,now:Long):View {
        val who=e.name ?: e.numberKey?.let(CallUi::who) ?: "모르는 번호"
        val time=CallUi.time(e.startMs)
        // v3.1 (DESIGN_V4 2.1-4): being turned into text only with a job in the call's window, else looked for, then none.
        val words=stateWords(e,now,jobStarts)
        val pending=words==TRANSCRIBING
        val row=when {
            e.personal -> CallUi.logRow(a,time,who,"개인 통화",CallUi.SUBTLE)
            pending -> CallUi.logRow(a,time,who,TRANSCRIBING,CallUi.SUBTLE)
            else -> when(e.kind) {
                FirstPass.LogKind.MISSED -> CallUi.logRow(a,time,who,"부재중 전화",CallUi.AMBER,trailing=CallUi.redialButton(a,e.numberKey,e.name ?: who))
                FirstPass.LogKind.NOT_CONNECTED -> CallUi.logRow(a,time,who,"건 전화, 연결 안 됨",CallUi.SUBTLE,trailing=CallUi.redialButton(a,e.numberKey,e.name ?: who))
                FirstPass.LogKind.BLOCKED -> CallUi.logRow(a,time,who,"차단한 번호의 전화",CallUi.SUBTLE)
                FirstPass.LogKind.NO_RECORDING -> CallUi.logRow(a,time,who,
                    "${if(e.type==android.provider.CallLog.Calls.OUTGOING_TYPE) "건 전화" else "받은 전화"} ${CallUi.duration(e.durationMs)}, ${words ?: NO_RECORDING}",CallUi.SUBTLE)
            }
        }
        return row.apply { tag="log_${when { e.personal -> "personal";pending -> "transcribing";else -> e.kind.name.lowercase() }}_${e.logId}" }
    }

    private fun row(c:CallRecord)=
        // One call: who and when, the first sentence of the summary (or its state). Tap for details; a search stays as typed.
        CallUi.memoRow(a,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: stateWords(c),c.summary==null,
            avatarName=c.linkedPerson ?: c.analysis?.counterpartName) { openDetail(c.recordId) }
            .apply { tag="call_${c.recordId}" }.also { PersonLinks.onWho(a,it,people[c.recordId],c.linkedPerson) }

    /** The calls below the search line: the day with its switcher, or, once something is typed, the matches among all calls. */
    private fun fill() {
        handler.removeCallbacks(searchLater)
        listBox.removeAllViews()
        if(query.isNotBlank()) { fillSearch();return }
        // Day switcher: ‹ date › in one sheet; "오늘로 돌아가기" only when away from today.
        val nav=CallUi.card(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(CallUi.dp(a,4),CallUi.dp(a,4),CallUi.dp(a,4),CallUi.dp(a,4)) }
        fun arrow(kind:String,label:String,t:String,enabled:Boolean,action:()->Unit)=Button(a).apply {
            background=CallUi.ripple(a,null,24,oval=false);stateListAnimator=null;contentDescription=label;isEnabled=enabled;tag=t
            setCompoundDrawablesWithIntrinsicBounds(CallUi.icon(a,kind,if(enabled) CallUi.INK else CallUi.FAINT,24),null,null,null)
            minWidth=CallUi.dp(a,56);minimumWidth=minWidth;minHeight=CallUi.dp(a,56);minimumHeight=minHeight;CallUi.onTap(this) { action() }
        }
        nav.addView(arrow("back","전날 통화 보기","calls_prev",true) { go(day.minusDays(1)) })
        nav.addView(CallUi.text(a,CallUi.date(day),CallUi.SUMMARY,true).apply { gravity=android.view.Gravity.CENTER;tag="today_calls_date" },LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(arrow("chevron","다음날 통화 보기","calls_next",day<CallUi.today()) { go(day.plusDays(1)) })
        listBox.addView(nav)
        if(day!=CallUi.today()) listBox.addView(CallUi.linkButton(a,"오늘로 돌아가기 ›") { go(CallUi.today()) })
        if(reading) { CallUi.loading(listBox);return }

        val lines=merged(calls,visibleLogs(calls,logs),clock())
        if(lines.isEmpty()) { listBox.addView(CallUi.card(a).apply { addView(CallUi.text(a,"이 날짜에 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)) });return }
        // A busy day can hold a hundred lines and more: the first screenful is built at once, the rest a part at a time
        // after it is drawn, so the screen opens without a wait. A newer fill drops the parts still to come.
        val sheet=CallUi.list(a,lines.take(FIRST_ROWS).map { it.second() }).apply { setPadding(0,0,0,0);tag="calls_list" }
        listBox.addView(sheet)
        val generation=++fills
        fun more(from:Int) { if(from>=lines.size) return
            sheet.post { if(generation!=fills) return@post
                CallUi.addRows(a,sheet,lines.subList(from,minOf(lines.size,from+MORE_ROWS)).map { it.second() });more(from+MORE_ROWS) } }
        more(FIRST_ROWS)
    }
    /** Counts each [fill]; parts of a long list still to come belong to the latest one only. */
    private var fills=0

    /** The day's records and call-log lines in one list, newest first; each with the view that draws it. */
    private fun merged(calls:List<CallRecord>,logs:List<LoggedCall>,now:Long):List<Pair<Long,()->View>> =
        (calls.map { c -> (c.startedMs ?: c.createdMs) to { row(c) } }+logs.map { e -> e.startMs to { logRow(e,now) } }).sortedByDescending { it.first }

    internal fun matches(c:CallRecord,q:String)=searchable(c).matches(queryWords(q))
    /** At most five people whose name, role or company holds every typed word. */
    private fun peopleMatching(words:List<String>)=peoplePool.filter { (p,co) ->
        val hay=listOfNotNull(p.name,p.role,co).joinToString(" ").lowercase();val packed=hay.filterNot { it.isWhitespace() }
        words.all { hay.contains(it) || packed.contains(it) } }.take(5)

    private fun fillSearch() {
        ++fills
        val all=pool
        if(all==null) {
            listBox.addView(CallUi.text(a,"찾는 중이에요",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="calls_search_wait" })
            if(!poolLoading) { poolLoading=true
                a.lifecycleScope.launch {
                    val found=withContext(Dispatchers.IO) { runCatching { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a)).let { s ->
                        val from=CallRecordStore.kstDay(FirstPass.FROM_MS);val to=CallUi.today()
                        val all=generateSequence(to) { it.minusDays(1) }.takeWhile { !it.isBefore(from) }.flatMap { s.day(it) }
                            .map(::searchable).toList()
                        Triple(all,runCatching { s.personIdsOf(all.filter { it.record.linkedPerson!=null }.map { it.record.recordId }) }.getOrDefault(emptyMap()),
                            runCatching { s.peopleForSearch() }.getOrDefault(emptyList())) } }.getOrNull() }
                    poolLoading=false
                    if(!live())return@launch
                    if(found==null) { if(query.isNotBlank()&&detail==null) { listBox.removeAllViews()
                        CallUi.retryCard(listBox,"통화를 찾지 못했어요. 잠시 후 다시 해 주세요.") { fill() } };return@launch }
                    pool=found.first;people=people+found.second;peoplePool=found.third;if(query.isNotBlank()&&detail==null) fill()
                } }
            return
        }
        val words=queryWords(query)
        val hits=all.filter { it.matches(words) }.map { it.record }.sortedByDescending { it.startedMs ?: it.createdMs }
        val who=peopleMatching(words)
        if(who.isNotEmpty()) {
            listBox.addView(CallUi.section(a,"사람").apply { tag="calls_search_people" })
            listBox.addView(CallUi.list(a,who.map { (p,co) -> PersonLinks.row(a,p,listOfNotNull(co,p.role,"우리 직원".takeIf { p.isStaff }).joinToString(", ")) }).apply { setPadding(0,0,0,0) })
            if(hits.isNotEmpty()) listBox.addView(CallUi.section(a,"통화"))
        }
        if(hits.isEmpty() && who.isNotEmpty()) return
        if(hits.isEmpty()) listBox.addView(CallUi.card(a).apply { addView(CallUi.text(a,"찾는 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)).also { tag="calls_search_none" } })
        else listBox.addView(CallUi.list(a,hits.take(60).map { row(it) }).apply { setPadding(0,0,0,0) })
    }

    private fun renderDetail(c:CallRecord) {
        CallUi.band(body,c.displayName,"${CallUi.date(CallRecordStore.kstDay(c.dayMs))} ${CallUi.time(c.startedMs)}, "+
            (if(c.kind=="통화") "${CallUi.duration(c.durationMs)} 통화" else c.kind),back={ closeDetail() },trailing=CallUi.dialButton(a,c.counterpartKey,c.displayName))
        // The name in the title, and a link under it, open the person page (v2.8).
        people[c.recordId]?.let { pid ->
            val name=c.linkedPerson ?: c.analysis?.counterpartName ?: "이 사람"
            (body.parent as? LinearLayout)?.findViewWithTag<TextView>("page_title")?.apply {
                isClickable=true;isFocusable=true;foreground=CallUi.rowRipple();contentDescription="$name 님 보기";CallUi.onTap(this) { a.openPerson(pid) } }
            body.addView(CallUi.linkButton(a,"$name 님 보기 ›") { a.openPerson(pid) }.apply { tag="call_person_link" },
                LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        }
        // The way on to the company (its people, memo and other calls).
        c.analysis?.counterpartCompany?.takeIf { it.isNotBlank() }?.let { co ->
            body.addView(CallUi.linkButton(a,"$co 보기 ›") { a.openCompany(co) }
                .apply { tag="call_company_link" },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        }
        if(c.state!=CallRecordStore.ANALYZED) {
            // A call that could not be sorted out is an amber sheet with plain words, never red. A recording with no speech
            // in it says so and has no button: sorting it out again gives the same (review #10).
            val failed=c.state==CallRecordStore.FAILED
            val s=CallUi.card(a,warn=failed).apply { tag="call_state" }
            // v3.1 (DESIGN_V4 2.3-2, 2.5-4): a result that came back but could not be applied, twice, says so in its own words.
            val notApplied=failed && c.error==PipelineSettingsKeys.RESULT_NOT_APPLIED
            s.addView(CallUi.text(a,if(notApplied) CallRetry.NOT_APPLIED else stateWords(c),CallUi.SUMMARY,true,if(failed) CallUi.AMBER else CallUi.INK).apply { tag="call_state_text" })
            if(notApplied) s.addView(CallUi.text(a,"다시 정리하기를 누르면 이 통화를 처음부터 다시 정리해요.",CallUi.BODY).apply { tag="call_state_help" })
            when {
                canRetry(c) -> s.addView(CallUi.button(a,"다시 정리하기","다시 정리하는 중이에요") { retry(c.recordId) }.apply { tag="call_retry" })
                c.state==CallRecordStore.WAITING_CONSENT -> s.addView(CallUi.button(a,"설정 열기") { a.switchTo(Screen.SETTINGS) })
            }
            body.addView(s)
        }
        val an=c.analysis
        if(an!=null) {
            body.addView(CallUi.section(a,"요약"))
            body.addView(CallUi.card(a).apply {
                addView(CallUi.text(a,an.summary,CallUi.SUMMARY))
                if(an.keyPoints.isNotEmpty()) { addView(CallUi.divider(a));an.keyPoints.forEach { addView(CallUi.text(a,it,CallUi.BODY,color=CallUi.SUBTLE)) } }
            })
            val done=c.applied.filter { it.applied }
            if(done.isNotEmpty()) {
                body.addView(CallUi.section(a,"이 통화에서 생긴 체크할 일"))
                body.addView(CallUi.list(a,done.map { t ->
                    val made=an.tasks.firstOrNull { it.title.trim().take(240)==t.title.trim() }
                    CallUi.taskRow(a,t.title,0,null,status=if(t.op=="create"||t.op=="update") "" else "needs_confirmation").apply {
                        tag="call_task_${t.itemId}"
                        ScreenParts.ownerWords(made?.owner).takeIf { it.isNotEmpty() }?.let { w -> addView(CallUi.caption(a,w).apply { tag="task_owner";setPadding(0,CallUi.dp(a,6),0,0) }) }
                        ScreenParts.quoteLine(made?.quote,made?.quoteFuzzy==true).takeIf { it.isNotEmpty() }?.let { q -> addView(CallUi.caption(a,q,CallUi.SUBTLE).apply { tag="task_quote";setPadding(0,CallUi.dp(a,4),0,0) }) }
                    } },inset=16))
            }
        }
        if(c.transcript.isNotBlank()) {
            val full=CallUi.card(a).apply { addView(CallUi.text(a,c.transcript,CallUi.BODY));visibility=View.GONE }
            body.addView(CallUi.button(a,if(c.kind=="통화") "통화 내용 전체 보기" else "${c.kind} 전체 보기") { full.visibility=if(full.visibility==View.GONE) View.VISIBLE else View.GONE }.apply { tag="toggle_transcript" })
            body.addView(full)
        }
    }

    private fun retry(id:String) {
        a.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CallRetry.resend(CallPipelineStartup.storage(a),id) }
                runCatching { CallPipelineStartup.enqueueAnalysis(a) }
            }
            if(!live()) return@launch
            Toast.makeText(a,"다시 정리할게요. 잠시만 기다려 주세요.",Toast.LENGTH_SHORT).show();load()
        }
    }
}

/** v3.1 (r12-5, DESIGN_V4 2.3-2 and 2.5-4): [다시 정리하기] on a call's detail page. */
internal object CallRetry {
    /** FAILED with [PipelineSettingsKeys.RESULT_NOT_APPLIED]: the words of the call's detail page. */
    const val NOT_APPLIED="정리 결과를 적용하지 못했어요"
    /** Test hook in place of [resend] (null: the real one). */
    @androidx.annotation.VisibleForTesting @Volatile internal var hook:((android.content.Context,String)->Unit)?=null
    /** The call goes back to the queue: state TRANSCRIBED and the "result received" mark cleared, so the hourly work sends it
     * again at the next pass: the sent row is forgotten, so the resend wait does not hold it (integration of r12-3/r12-5). */
    fun resend(c:android.content.Context,id:String) {
        hook?.let { it(c,id);return }
        val db=kr.callbrief.IeumApplication.store(c)
        db.inTransaction { db.setState(id,CallRecordStore.TRANSCRIBED);db.forgetSent(id) }
    }
}
