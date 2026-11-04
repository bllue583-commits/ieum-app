package kr.callbrief.pipeline

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** "통화": one KST day of calls (time, who, the first sentence of the summary), one tap to a detail page. Every call of
 * the phone's call log is listed with them in time order (v2.4, 11/4 user): a missed or rejected call as "부재중 전화"
 * with 다시 걸기, an answered call without a recording as "녹음 없음", a call with a number found personal as "개인 통화"
 * (who and when only). */
internal class CallsScreen(a:MainActivity):AppScreen(a) {
    companion object {
        /** Lines built at once, then per later part (see [fill]). */
        const val FIRST_ROWS=30;const val MORE_ROWS=40
        /** The line under the title: "통화 7건, 부재중 2건, 문자 1건". Personal calls and calls without a recording count as calls. */
        internal fun counts(calls:List<CallRecord>,logs:List<LoggedCall>):String {
            val missed=logs.count { !it.personal && it.kind in setOf(FirstPass.LogKind.MISSED,FirstPass.LogKind.BLOCKED) }
            val n=calls.groupingBy { it.kind }.eachCount().toMutableMap()
            n["통화"]=(n["통화"] ?: 0)+logs.size-missed
            if(missed>0) n["부재중"]=missed
            return n.entries.filter { it.value>0 }.sortedBy { listOf("통화","부재중","문자","메일").indexOf(it.key) }.joinToString(", ") { "${it.key} ${it.value}건" }.ifEmpty { "통화 0건" }
        }
    }
    private val body:LinearLayout get()=a.column
    private var day:LocalDate=CallUi.today()
    /** The call whose detail page is open, or null on the list. */
    override var detail:String?=null
    private var calls:List<CallRecord> =emptyList()
    /** The day's call-log lines that have no record of their own ([CallRecordStore.dayLog]). */
    private var logs:List<LoggedCall> =emptyList()
    /** Read the phone's call log again on the next [load] (once each time the screen comes back). */
    private var refreshLog=true
    /** Where the list was scrolled when a detail page opened from it; put back once the list is drawn again (design G1). */
    private var listY=0
    /** A scroll position to put back after the next list drawing (and its later parts). */
    private var pendingY=0

    override fun enter() { refreshLog=true;load() }
    /** The calls tab tapped while calls is in front: today's list from the top, no search. */
    override fun reselect() { detail=null;query="";day=CallUi.today();listY=0;pendingY=0;shown=null;load();CallUi.scrollTo(body,0) }
    override fun back():Boolean { if(detail==null) return false;closeDetail();return true }
    /** From another screen (home memo, a check item, a company, a notification): this day, and this call open when [callId] is given. */
    fun show(d:LocalDate,callId:String?) { if(d!=day) { calls=emptyList();logs=emptyList() };day=d;query="";detail=callId;listY=0;pendingY=0;shown=null }
    /** Opens the detail page of a call of the list, keeping where the list was. */
    private fun openDetail(id:String) { if(!a.once("call:$id")) return;listY=CallUi.scrollY(body);detail=id;render() }
    private fun closeDetail() { detail=null;pendingY=listY;listY=0;render() }
    override fun save(out:Bundle) { out.putString("calls_day",day.toString());detail?.let { out.putString("calls_detail",it) };out.putString("calls_query",query) }
    override fun restore(saved:Bundle) {
        saved.getString("calls_day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { day=it }
        detail=saved.getString("calls_detail");query=saved.getString("calls_query") ?: ""
    }

    private fun load() {
        val target=day;val refresh=refreshLog;refreshLog=false
        val app=a.applicationContext
        a.lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(a)).use { s ->
                    // A call that just ended (a missed one too) is in the list at once, not only after the next hourly pass.
                    if(refresh && !kr.callbrief.BuildConfig.LOCAL_PREVIEW) runCatching { s.reconcileCallLog(FirstPass.readCallLog(app,kr.callbrief.SecureSettings(app).snapshot())) }
                    s.day(target) to runCatching { s.dayLog(target) }.getOrDefault(emptyList()) }
            } }
            if(!live()||target!=day)return@launch
            result.onSuccess { calls=it.first;logs=it.second;pool=null;render() }
            result.onFailure { body.removeAllViews();CallUi.band(body,"통화");body.addView(CallUi.text(a,"저장된 통화를 읽지 못했어요. 잠시 후 다시 열어주세요.")) }
        }
    }
    private fun go(d:LocalDate) { day=d;detail=null;load() }

    private var shown:String?=null
    private fun render() {
        val record=detail?.let { id -> calls.firstOrNull { it.recordId==id } }
        val mode=if(record!=null) "detail" else "list"
        val keep=if(mode==shown) CallUi.scrollY(body) else 0;shown=mode
        body.removeAllViews()
        if(record!=null) { renderDetail(record);CallUi.restoreScroll(body,keep);return }
        detail=null
        // Back from a detail page: the list where it was, once the parts of it above that place are drawn (see [fill]).
        val y=if(pendingY>0) pendingY else keep;pendingY=0
        CallUi.band(body,"${CallUi.dayWord(day)}의 통화",counts(calls,logs))
        val search=ScreenParts.searchBox(a,"업체, 사람, 통화 내용 찾기",query,"calls_search") { q -> query=q;fill() }
        body.addView(search)
        listBox=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="calls_box" }
        body.addView(listBox)
        fill()
        a.putBack(y)
    }

    /** What is typed in the search line ("" shows the day). */
    private var query=""
    private lateinit var listBox:LinearLayout
    /** Every call since 10/1 for the search (read once per opening of the screen, off the main thread). */
    private var pool:List<CallRecord>? =null
    private var poolLoading=false

    /** One line of the call-log entries ([LoggedCall]): who and when, and what kind of call it was. Nothing to open. */
    private fun logRow(e:LoggedCall):View {
        val who=e.name ?: e.numberKey?.let(CallUi::who) ?: "모르는 번호"
        val time=CallUi.time(e.startMs)
        val row=when {
            e.personal -> CallUi.logRow(a,time,who,"개인 통화",CallUi.MUTED)
            else -> when(e.kind) {
                FirstPass.LogKind.MISSED -> CallUi.logRow(a,time,who,"부재중 전화",CallUi.AMBER,trailing=CallUi.redialButton(a,e.numberKey,e.name ?: who))
                FirstPass.LogKind.NOT_CONNECTED -> CallUi.logRow(a,time,who,"건 전화, 연결 안 됨",CallUi.SUBTLE,trailing=CallUi.redialButton(a,e.numberKey,e.name ?: who))
                FirstPass.LogKind.BLOCKED -> CallUi.logRow(a,time,who,"차단한 번호의 전화",CallUi.MUTED)
                FirstPass.LogKind.NO_RECORDING -> CallUi.logRow(a,time,who,
                    "${if(e.type==android.provider.CallLog.Calls.OUTGOING_TYPE) "건 전화" else "받은 전화"} ${CallUi.duration(e.durationMs)}, 녹음 없음",CallUi.SUBTLE)
            }
        }
        return row.apply { tag="log_${if(e.personal) "personal" else e.kind.name.lowercase()}_${e.logId}" }
    }

    private fun row(c:CallRecord,searching:Boolean)=
        // One call: who and when, the first sentence of the summary (or its state). Tap for details.
        CallUi.memoRow(a,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c),c.summary==null,
            avatarName=c.linkedPerson ?: c.analysis?.counterpartName) {
            if(searching) { if(a.once("call:"+c.recordId)) { day=CallRecordStore.kstDay(c.dayMs);query="";listY=0;detail=c.recordId;load() } } else openDetail(c.recordId) }
            .apply { tag="call_${c.recordId}" }

    /** The calls below the search line: the day with its switcher, or, once something is typed, the matches among all calls. */
    private fun fill() {
        listBox.removeAllViews()
        if(query.isNotBlank()) { fillSearch();return }
        // Day switcher: ‹ date › in one sheet; "오늘로 돌아가기" only when away from today.
        val nav=CallUi.card(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(CallUi.dp(a,4),CallUi.dp(a,4),CallUi.dp(a,4),CallUi.dp(a,4)) }
        fun arrow(kind:String,label:String,enabled:Boolean,action:()->Unit)=Button(a).apply {
            background=CallUi.ripple(a,null,24,oval=false);stateListAnimator=null;contentDescription=label;isEnabled=enabled
            setCompoundDrawablesWithIntrinsicBounds(CallUi.icon(a,kind,if(enabled) CallUi.INK else CallUi.FAINT,24),null,null,null)
            minWidth=CallUi.dp(a,56);minimumWidth=minWidth;minHeight=CallUi.dp(a,56);minimumHeight=minHeight;setOnClickListener { action() }
        }
        nav.addView(arrow("back","전날 통화 보기",true) { go(day.minusDays(1)) })
        nav.addView(CallUi.text(a,CallUi.date(day),CallUi.SUMMARY,true).apply { gravity=android.view.Gravity.CENTER;tag="today_calls_date" },LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(arrow("chevron","다음날 통화 보기",day<CallUi.today()) { go(day.plusDays(1)) })
        listBox.addView(nav)
        if(day!=CallUi.today()) listBox.addView(CallUi.linkButton(a,"오늘로 돌아가기 ›") { go(CallUi.today()) })

        val lines=merged(calls,logs)
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
    private fun merged(calls:List<CallRecord>,logs:List<LoggedCall>):List<Pair<Long,()->View>> =
        (calls.map { c -> (c.startedMs ?: c.createdMs) to { row(c,false) } }+logs.map { e -> e.startMs to { logRow(e) } }).sortedByDescending { it.first }

    internal fun matches(c:CallRecord,q:String)=ScreenParts.matches(q,c.displayName,c.counterpart,c.analysis?.counterpartName,c.analysis?.counterpartCompany,c.linkedPerson,c.summary,c.analysis?.summary,c.transcript)

    private fun fillSearch() {
        val all=pool
        if(all==null) {
            listBox.addView(CallUi.text(a,"찾는 중이에요",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="calls_search_wait" })
            if(!poolLoading) { poolLoading=true
                a.lifecycleScope.launch {
                    val found=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { s ->
                        val from=CallRecordStore.kstDay(FirstPass.FROM_MS);val to=CallUi.today()
                        generateSequence(to) { it.minusDays(1) }.takeWhile { !it.isBefore(from) }.flatMap { s.day(it) }.toList() } }.getOrDefault(emptyList()) }
                    poolLoading=false
                    if(!live())return@launch
                    pool=found;if(query.isNotBlank()&&detail==null) fill()
                } }
            return
        }
        val hits=all.filter { matches(it,query) }.sortedByDescending { it.startedMs ?: it.createdMs }
        if(hits.isEmpty()) listBox.addView(CallUi.card(a).apply { addView(CallUi.text(a,"찾는 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)).also { tag="calls_search_none" } })
        else listBox.addView(CallUi.list(a,hits.take(60).map { row(it,true) }).apply { setPadding(0,0,0,0) })
    }

    private fun renderDetail(c:CallRecord) {
        CallUi.band(body,c.displayName,"${CallUi.date(CallRecordStore.kstDay(c.dayMs))} ${CallUi.time(c.startedMs)}, "+
            (if(c.kind=="통화") "${CallUi.duration(c.durationMs)} 통화" else c.kind),back={ closeDetail() },trailing=CallUi.dialButton(a,c.counterpartKey,c.displayName))
        // The way on to the company (its people, memo and other calls).
        c.analysis?.counterpartCompany?.takeIf { it.isNotBlank() }?.let { co ->
            body.addView(CallUi.linkButton(a,"$co 보기 ›") { a.openCompany(co) }
                .apply { tag="call_company_link" },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        }
        if(c.state!=CallRecordStore.ANALYZED) {
            // A call that could not be sorted out is an amber sheet with plain words, never red.
            val failed=c.state==CallRecordStore.FAILED
            val s=CallUi.card(a,warn=failed)
            s.addView(CallUi.text(a,CallUi.state(c),CallUi.SUMMARY,true,if(failed) CallUi.AMBER else CallUi.INK))
            when(c.state) {
                CallRecordStore.FAILED -> s.addView(CallUi.button(a,"다시 정리하기") { retry(c.recordId) })
                CallRecordStore.WAITING_CONSENT -> s.addView(CallUi.button(a,"설정 열기") { a.switchTo(Screen.SETTINGS) })
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
                runCatching { CallRecordStore(CallPipelineStartup.storage(a)).use { it.setState(id,CallRecordStore.TRANSCRIBED) } }
                runCatching { CallPipelineStartup.enqueueAnalysis(a) }
            }
            Toast.makeText(a,"다시 정리할게요. 잠시만 기다려 주세요.",Toast.LENGTH_SHORT).show();load()
        }
    }
}
