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

/** "통화": one KST day of calls as cards (time, who, the first sentence of the summary), one tap to a detail page. */
class TodayCallsActivity:ComponentActivity() {
    companion object {
        /** Record id of a call to open straight in its detail page (home memo list, a company's calls, the to-do it made). */ const val EXTRA_CALL="call"
        /** Start time of that call, so the page loads the right day. */ const val EXTRA_DAY_MS="day_ms"
    }
    private lateinit var body:LinearLayout
    private var day:LocalDate=CallUi.today()
    private var detail:String?=null
    private var calls:List<CallRecord> =emptyList()

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
    private fun closeDetail() { if(detail!=null && !fromOutside()) { detail=null;render() } else if(fromOutside()) finish() else CallUi.leave(this) }
    override fun onResume() { super.onResume();load() }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);out.putString("day",day.toString());detail?.let { out.putString("detail",it) } }

    private fun load() {
        val target=day
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { it.day(target) }
            } }
            if(isFinishing||isDestroyed||target!=day)return@launch
            result.onSuccess { calls=it;render() }
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
        CallUi.band(body,"${CallUi.dayWord(day)}의 통화",calls.groupingBy { it.kind }.eachCount().entries.sortedBy { listOf("통화","문자","메일").indexOf(it.key) }
            .joinToString(", ") { "${it.key} ${it.value}건" }.ifEmpty { "통화 0건" })
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
        body.addView(nav)
        if(day!=CallUi.today()) body.addView(CallUi.linkButton(this,"오늘로 돌아가기 ›") { go(CallUi.today()) })

        if(calls.isEmpty()) body.addView(CallUi.card(this).apply { addView(CallUi.text(this@TodayCallsActivity,"이 날짜에 정리된 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)) })
        else body.addView(CallUi.list(this,calls.sortedByDescending { it.startedMs ?: 0 }.map { c ->
            // One call: who and when, the first sentence of the summary (or its state). Tap for details.
            CallUi.memoRow(this,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c),c.summary==null,
                avatarName=c.linkedPerson ?: c.analysis?.counterpartName) { detail=c.recordId;render() }
                .apply { tag="call_${c.recordId}" }
        }).apply { setPadding(0,0,0,0) })
        CallUi.restoreScroll(body,keep)
    }

    /** "제가 하기로 함, 수정 견적서 보내기": who promised; no deadline words (user rule). */
    private fun promiseLine(p:ClaudeCallAnalysis.Promise)=when(p.owner) { "me" -> "제가 하기로 함, ";"them" -> "상대가 하기로 함, ";else -> "" }+p.text

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
            if(a.promises.isNotEmpty()) {
                body.addView(CallUi.section(this,"약속"))
                body.addView(CallUi.list(this,a.promises.map { p -> LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL;val pad=CallUi.dp(this@TodayCallsActivity,16);setPadding(pad,CallUi.dp(this@TodayCallsActivity,14),pad,CallUi.dp(this@TodayCallsActivity,14))
                    addView(CallUi.text(this@TodayCallsActivity,promiseLine(p),CallUi.BODY,true))
                    addView(CallUi.caption(this@TodayCallsActivity,"“${p.quote}”"))
                } },inset=16))
            }
            val done=c.applied.filter { it.applied }
            if(done.isNotEmpty()) {
                body.addView(CallUi.section(this,"체크할 일에 넣은 것"))
                body.addView(CallUi.list(this,done.map { t -> CallUi.taskRow(this,t.title,0,null,status=if(t.op=="create"||t.op=="update") "" else "needs_confirmation") },inset=16))
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
