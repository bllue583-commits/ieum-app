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
    companion object { /** Record id of today's call to open straight in its detail page (home memo list). */ const val EXTRA_CALL="call" }
    private lateinit var body:LinearLayout
    private var day:LocalDate=CallUi.today()
    private var detail:String?=null
    private var calls:List<CallRecord> =emptyList()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("day")?.let { day=LocalDate.parse(it) }
        detail=savedInstanceState?.getString("detail") ?: intent?.getStringExtra(EXTRA_CALL)
        body=CallUi.page(this,CallUi.Tab.CALLS)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            // Opened from a home memo: back goes straight home again.
            override fun handleOnBackPressed() { if(detail!=null && intent?.hasExtra(EXTRA_CALL)!=true) { detail=null;render() } else finish() }
        })
    }
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

    private fun render() {
        body.removeAllViews()
        val record=detail?.let { id -> calls.firstOrNull { it.recordId==id } }
        if(record!=null) { renderDetail(record);return }
        detail=null
        CallUi.band(body,"${CallUi.dayWord(day)}의 통화",calls.groupingBy { it.kind }.eachCount().entries.sortedBy { listOf("통화","문자","메일").indexOf(it.key) }
            .joinToString(", ") { "${it.key} ${it.value}건" }.ifEmpty { "통화 0건" })
        // Day switcher: ‹ date › in one sheet; "오늘로 돌아가기" only when away from today.
        val nav=CallUi.card(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(CallUi.dp(this@TodayCallsActivity,4),CallUi.dp(this@TodayCallsActivity,4),CallUi.dp(this@TodayCallsActivity,4),CallUi.dp(this@TodayCallsActivity,4)) }
        fun arrow(kind:String,label:String,enabled:Boolean,action:()->Unit)=Button(this).apply {
            background=null;stateListAnimator=null;contentDescription=label;isEnabled=enabled
            setCompoundDrawablesWithIntrinsicBounds(CallUi.icon(this@TodayCallsActivity,kind,if(enabled) CallUi.ACCENT else CallUi.LINE,26),null,null,null)
            minWidth=CallUi.dp(this@TodayCallsActivity,56);minimumWidth=minWidth;minHeight=CallUi.dp(this@TodayCallsActivity,56);minimumHeight=minHeight;setOnClickListener { action() }
        }
        nav.addView(arrow("back","전날 통화 보기",true) { go(day.minusDays(1)) })
        nav.addView(CallUi.text(this,CallUi.date(day),CallUi.SUMMARY,true).apply { gravity=android.view.Gravity.CENTER;tag="today_calls_date" },LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(arrow("chevron","다음날 통화 보기",day<CallUi.today()) { go(day.plusDays(1)) })
        body.addView(nav)
        if(day!=CallUi.today()) body.addView(CallUi.linkButton(this,"오늘로 돌아가기 ›") { go(CallUi.today()) })

        body.addView(CallUi.section(this,"통화 기록"))
        if(calls.isEmpty()) body.addView(CallUi.card(this).apply { addView(CallUi.text(this@TodayCallsActivity,"이 날짜에 정리된 통화가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)) })
        else body.addView(CallUi.list(this,calls.sortedByDescending { it.startedMs ?: 0 }.map { c ->
            // One call: who and when, the first sentence of the summary (or its state). Tap for details.
            CallUi.memoRow(this,CallUi.whenLabel(c),c.displayName,c.summary?.let(CallUi::firstSentence) ?: CallUi.state(c),c.summary==null) { detail=c.recordId;render() }
                .apply { tag="call_${c.recordId}" }
        }))
    }

    /** "제가 할 일, 수정 견적서 보내기 (오늘까지)" */
    private fun promiseLine(p:ClaudeCallAnalysis.Promise)=when(p.owner) { "me" -> "제가 할 일, ";"them" -> "상대가 할 일, ";else -> "" }+p.text+(p.due?.let { " (${CallUi.dueWords(it)})" } ?: "")

    private fun renderDetail(c:CallRecord) {
        CallUi.band(body,c.displayName,"${CallUi.date(CallRecordStore.kstDay(c.dayMs))} ${CallUi.time(c.startedMs)}, "+
            (if(c.kind=="통화") "${CallUi.duration(c.durationMs)} 통화" else c.kind),back={ detail=null;render() })
        if(c.state!=CallRecordStore.ANALYZED) {
            val s=CallUi.card(this,warn=c.state==CallRecordStore.FAILED)
            s.addView(CallUi.text(this,CallUi.state(c),CallUi.SUMMARY,true,if(c.state==CallRecordStore.FAILED) CallUi.WARN else CallUi.INK))
            when(c.state) {
                CallRecordStore.FAILED -> s.addView(CallUi.bigButton(this,"","다시 정리하기") { retry(c.recordId) })
                CallRecordStore.WAITING_KEY,CallRecordStore.WAITING_CONSENT -> s.addView(CallUi.bigButton(this,"","설정 열기") { CallUi.go(this,CallUi.Tab.SETTINGS) })
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
                a.promises.forEach { p -> body.addView(CallUi.card(this).apply {
                    addView(CallUi.text(this@TodayCallsActivity,promiseLine(p),CallUi.BODY,true))
                    addView(CallUi.text(this@TodayCallsActivity,"“${p.quote}”",CallUi.SMALL,color=CallUi.SUBTLE))
                }) }
            }
            val done=c.applied.filter { it.applied }
            if(done.isNotEmpty()) {
                body.addView(CallUi.section(this,"체크할 일에 넣은 것"))
                done.forEach { t -> body.addView(CallUi.taskRow(this,t.title,0,null,status=if(t.op=="create"||t.op=="update") "" else "needs_confirmation")) }
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
