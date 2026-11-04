package kr.callbrief.pipeline

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** "통화": one KST day of calls (time, who, the first sentence of the summary), one tap to a detail page. Every call of
 * the phone's call log is listed with them in time order (v2.4, 11/4 user): a missed or rejected call as "부재중 전화"
 * with 다시 걸기, an answered call without a recording as "녹음 없음", a call with a number found personal as "개인 통화"
 * (who and when only). */
class TodayCallsActivity:ComponentActivity() {
    companion object {
        /** Record id of a call to open straight in its detail page (home memo list, a company's calls, the to-do it made). */ const val EXTRA_CALL="call"
        /** Start time of that call, so the page loads the right day. */ const val EXTRA_DAY_MS="day_ms"
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
    private lateinit var body:LinearLayout
    private var day:LocalDate=CallUi.today()
    private var detail:String?=null
    private var calls:List<CallRecord> =emptyList()
    /** The day's call-log lines that have no record of their own ([CallRecordStore.dayLog]). */
    private var logs:List<LoggedCall> =emptyList()
    /** Read the phone's call log again on the next [load] (once each time the screen comes back). */
    private var refreshLog=true

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("day")?.let { day=LocalDate.parse(it) }
        detail=savedInstanceState?.getString("detail") ?: intent?.getStringExtra(EXTRA_CALL)
        if(savedInstanceState==null) intent?.getLongExtra(EXTRA_DAY_MS,0L)?.takeIf { it>0 }?.let { day=CallRecordStore.kstDay(it) }
        body=CallUi.page(this,CallUi.Tab.CALLS)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { closeDetail() } })
    }
    /** Opened from another screen (a home memo, a company, a to-do): back returns there. Otherwise detail goes back to the list, the list to home. */
    private fun fromOutside()=intent?.hasExtra(EXTRA_CALL)==true
    private fun closeDetail() { if(detail!=null && !fromOutside()) { detail=null;render() } else if(fromOutside()) finish() else CallUi.go(this,CallUi.Tab.HOME) }
    override fun onResume() { super.onResume();refreshLog=true;load() }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);out.putString("day",day.toString());detail?.let { out.putString("detail",it) } }

    private fun load() {
        val target=day;val refresh=refreshLog;refreshLog=false
        val app=applicationContext
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { s ->
                    // A call that just ended (a missed one too) is in the list at once, not only after the next hourly pass.
                    if(refresh && !kr.callbrief.BuildConfig.LOCAL_PREVIEW) runCatching { s.reconcileCallLog(FirstPass.readCallLog(app,kr.callbrief.SecureSettings(app).snapshot())) }
                    s.day(target) to runCatching { s.dayLog(target) }.getOrDefault(emptyList()) }
            } }
            if(isFinishing||isDestroyed||target!=day)return@launch
            result.onSuccess { calls=it.first;logs=it.second;pool=null;render() }
            result.onFailure { body.removeAllViews();CallUi.band(body,"통화");body.addView(CallUi.text(this@TodayCallsActivity,"저장된 통화를 읽지 못했어요. 잠시 후 다시 열어주세요.")) }
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
        CallUi.band(body,"${CallUi.dayWord(day)}의 통화",counts(calls,logs))
        val search=ScreenParts.searchBox(this,"업체, 사람, 통화 내용 찾기",query,"calls_search") { q -> query=q;fill() }
        body.addView(search)
        listBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;tag="calls_box" }
        body.addView(listBox)
        fill()
        CallUi.restoreScroll(body,keep)
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
            e.personal -> CallUi.logRow(this,time,who,"개인 통화",CallUi.MUTED)
            else -> when(e.kind) {
                FirstPass.LogKind.MISSED -> CallUi.logRow(this,time,who,"부재중 전화",CallUi.AMBER,trailing=CallUi.redialButton(this,e.numberKey,e.name ?: who))
                FirstPass.LogKind.NOT_CONNECTED -> CallUi.logRow(this,time,who,"건 전화, 연결 안 됨",CallUi.SUBTLE,trailing=CallUi.redialButton(this,e.numberKey,e.name ?: who))
                FirstPass.LogKind.BLOCKED -> CallUi.logRow(this,time,who,"차단한 번호의 전화",CallUi.MUTED)
                FirstPass.LogKind.NO_RECORDING -> CallUi.logRow(this,time,who,
                    "${if(e.type==android.provider.CallLog.Calls.OUTGOING_TYPE) "건 전화" else "받은 전화"} ${CallUi.duration(e.durationMs)}, 녹음 없음",CallUi.SUBTLE)
            }
        }
        return row.apply { tag="log_${if(e.personal) "personal" else e.kind.name.lowercase()}_${e.logId}" }
    }

    private fun row(c:CallRecord,searching:Boolean)=
        // One call: who and when, the first sentence of the summary (or its state). Tap for details.
        CallUi.memoRow(this,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c),c.summary==null,
            avatarName=c.linkedPerson ?: c.analysis?.counterpartName) {
            if(searching) { day=CallRecordStore.kstDay(c.dayMs);query="";detail=c.recordId;load() } else { detail=c.recordId;render() } }
            .apply { tag="call_${c.recordId}" }

    /** The calls below the search line: the day with its switcher, or, once something is typed, the matches among all calls. */
    private fun fill() {
        listBox.removeAllViews()
        if(query.isNotBlank()) { fillSearch();return }
        // Day switcher: ‹ date › in one sheet; "오늘로 돌아가기" only when away from today.
        val nav=CallUi.card(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(CallUi.dp(this@TodayCallsActivity,4),CallUi.dp(this@TodayCallsActivity,4),CallUi.dp(this@TodayCallsActivity,4),CallUi.dp(this@TodayCallsActivity,4)) }
        fun arrow(kind:String,label:String,enabled:Boolean,action:()->Unit)=Button(this).apply {
            background=CallUi.ripple(this@TodayCallsActivity,null,24,oval=false);stateListAnimator=null;contentDescription=label;isEnabled=enabled
            setCompoundDrawablesWithIntrinsicBounds(CallUi.icon(this@TodayCallsActivity,kind,if(enabled) CallUi.INK else CallUi.FAINT,24),null,null,null)
            minWidth=CallUi.dp(this@TodayCallsActivity,56);minimumWidth=minWidth;minHeight=CallUi.dp(this@TodayCallsActivity,56);minimumHeight=minHeight;setOnClickListener { action() }
        }
        nav.addView(arrow("back","전날 통화 보기",true) { go(day.minusDays(1)) })
        nav.addView(CallUi.text(this,CallUi.date(day),CallUi.SUMMARY,true).apply { gravity=android.view.Gravity.CENTER;tag="today_calls_date" },LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(arrow("chevron","다음날 통화 보기",day<CallUi.today()) { go(day.plusDays(1)) })
        listBox.addView(nav)
        if(day!=CallUi.today()) listBox.addView(CallUi.linkButton(this,"오늘로 돌아가기 ›") { go(CallUi.today()) })

        val lines=merged(calls,logs)
        if(lines.isEmpty()) { listBox.addView(CallUi.card(this).apply { addView(CallUi.text(this@TodayCallsActivity,"이 날짜에 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)) });return }
        // A busy day can hold a hundred lines and more: the first screenful is built at once, the rest a part at a time
        // after it is drawn, so the screen opens without a wait. A newer fill drops the parts still to come.
        val sheet=CallUi.list(this,lines.take(FIRST_ROWS).map { it.second() }).apply { setPadding(0,0,0,0);tag="calls_list" }
        listBox.addView(sheet)
        val generation=++fills
        fun more(from:Int) { if(from>=lines.size) return
            sheet.post { if(generation!=fills) return@post
                CallUi.addRows(this,sheet,lines.subList(from,minOf(lines.size,from+MORE_ROWS)).map { it.second() });more(from+MORE_ROWS) } }
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
            listBox.addView(CallUi.text(this,"찾는 중이에요",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="calls_search_wait" })
            if(!poolLoading) { poolLoading=true
                lifecycleScope.launch {
                    val found=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { s ->
                        val from=CallRecordStore.kstDay(FirstPass.FROM_MS);val to=CallUi.today()
                        generateSequence(to) { it.minusDays(1) }.takeWhile { !it.isBefore(from) }.flatMap { s.day(it) }.toList() } }.getOrDefault(emptyList()) }
                    poolLoading=false
                    if(isFinishing||isDestroyed)return@launch
                    pool=found;if(query.isNotBlank()&&detail==null) fill()
                } }
            return
        }
        val hits=all.filter { matches(it,query) }.sortedByDescending { it.startedMs ?: it.createdMs }
        if(hits.isEmpty()) listBox.addView(CallUi.card(this).apply { addView(CallUi.text(this@TodayCallsActivity,"찾는 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)).also { tag="calls_search_none" } })
        else listBox.addView(CallUi.list(this,hits.take(60).map { row(it,true) }).apply { setPadding(0,0,0,0) })
    }

    private fun renderDetail(c:CallRecord) {
        CallUi.band(body,c.displayName,"${CallUi.date(CallRecordStore.kstDay(c.dayMs))} ${CallUi.time(c.startedMs)}, "+
            (if(c.kind=="통화") "${CallUi.duration(c.durationMs)} 통화" else c.kind),back={ closeDetail() },trailing=CallUi.dialButton(this,c.counterpartKey,c.displayName))
        // The way on to the company (its people, memo and other calls).
        c.analysis?.counterpartCompany?.takeIf { it.isNotBlank() }?.let { co ->
            body.addView(CallUi.linkButton(this,"$co 보기 ›") { startActivity(android.content.Intent(this,CompaniesActivity::class.java).putExtra(CompaniesActivity.EXTRA_COMPANY_NAME,co)) }
                .apply { tag="call_company_link" },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(this@TodayCallsActivity,6) })
        }
        if(c.state!=CallRecordStore.ANALYZED) {
            // A call that could not be sorted out is an amber sheet with plain words, never red.
            val failed=c.state==CallRecordStore.FAILED
            val s=CallUi.card(this,warn=failed)
            s.addView(CallUi.text(this,CallUi.state(c),CallUi.SUMMARY,true,if(failed) CallUi.AMBER else CallUi.INK))
            when(c.state) {
                CallRecordStore.FAILED -> s.addView(CallUi.button(this,"다시 정리하기") { retry(c.recordId) })
                CallRecordStore.WAITING_CONSENT -> s.addView(CallUi.button(this,"설정 열기") { CallUi.go(this,CallUi.Tab.SETTINGS) })
            }
            body.addView(s)
        }
        val a=c.analysis
        if(a!=null) {
            body.addView(CallUi.section(this,"요약"))
            body.addView(CallUi.card(this).apply {
                addView(CallUi.text(this@TodayCallsActivity,a.summary,CallUi.SUMMARY))
                if(a.keyPoints.isNotEmpty()) { addView(CallUi.divider(this@TodayCallsActivity));a.keyPoints.forEach { addView(CallUi.text(this@TodayCallsActivity,it,CallUi.BODY,color=CallUi.SUBTLE)) } }
            })
            val done=c.applied.filter { it.applied }
            if(done.isNotEmpty()) {
                body.addView(CallUi.section(this,"이 통화에서 생긴 체크할 일"))
                body.addView(CallUi.list(this,done.map { t ->
                    val made=a.tasks.firstOrNull { it.title.trim().take(240)==t.title.trim() }
                    CallUi.taskRow(this,t.title,0,null,status=if(t.op=="create"||t.op=="update") "" else "needs_confirmation").apply {
                        tag="call_task_${t.itemId}"
                        ScreenParts.ownerWords(made?.owner).takeIf { it.isNotEmpty() }?.let { w -> addView(CallUi.caption(this@TodayCallsActivity,w).apply { tag="task_owner";setPadding(0,CallUi.dp(this@TodayCallsActivity,6),0,0) }) }
                        ScreenParts.quoteLine(made?.quote,made?.quoteFuzzy==true).takeIf { it.isNotEmpty() }?.let { q -> addView(CallUi.caption(this@TodayCallsActivity,q,CallUi.SUBTLE).apply { tag="task_quote";setPadding(0,CallUi.dp(this@TodayCallsActivity,4),0,0) }) }
                    } },inset=16))
            }
        }
        if(c.transcript.isNotBlank()) {
            val full=CallUi.card(this).apply { addView(CallUi.text(this@TodayCallsActivity,c.transcript,CallUi.BODY));visibility=View.GONE }
            body.addView(CallUi.button(this,if(c.kind=="통화") "통화 내용 전체 보기" else "${c.kind} 전체 보기") { full.visibility=if(full.visibility==View.GONE) View.VISIBLE else View.GONE }.apply { tag="toggle_transcript" })
            body.addView(full)
        }
    }

    private fun retry(id:String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { it.setState(id,CallRecordStore.TRANSCRIBED) } }
                runCatching { CallPipelineStartup.enqueueAnalysis(this@TodayCallsActivity) }
            }
            Toast.makeText(this@TodayCallsActivity,"다시 정리할게요. 잠시만 기다려 주세요.",Toast.LENGTH_SHORT).show();load()
        }
    }
}
