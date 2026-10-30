package kr.callbrief.pipeline

import android.content.Intent
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

/** "오늘의 통화": one KST day of calls as big cards (time, who, 2-3 line summary, promises),
 * the urgent to-dos on top, and one tap to a detail page. */
class TodayCallsActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var day:LocalDate=CallUi.today()
    private var detail:String?=null
    private var calls:List<CallRecord> =emptyList()
    private var due:List<CallTodoItem> =emptyList()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("day")?.let { day=LocalDate.parse(it) }
        detail=savedInstanceState?.getString("detail")
        body=CallUi.page(this);(body.parent as View).tag="today_calls_scroll"
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(detail!=null) { detail=null;render() } else finish() }
        })
    }
    override fun onResume() { super.onResume();load() }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);out.putString("day",day.toString());detail?.let { out.putString("detail",it) } }

    private fun load() {
        val target=day
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { it.day(target) to it.dueItems(CallUi.today()) }
            } }
            if(isFinishing||isDestroyed||target!=day)return@launch
            result.onSuccess { (c,d) -> calls=c;due=d;render() }
            result.onFailure { body.removeAllViews();body.addView(CallUi.text(this@TodayCallsActivity,"저장된 통화를 읽지 못했어요. 잠시 후 다시 열어주세요.")) }
        }
    }
    private fun go(d:LocalDate) { day=d;detail=null;load() }

    private fun render() {
        body.removeAllViews()
        val record=detail?.let { id -> calls.firstOrNull { it.recordId==id } }
        if(record!=null) { renderDetail(record);return }
        detail=null
        body.addView(CallUi.title(this,"${CallUi.dayWord(day)}의 통화"))
        body.addView(CallUi.text(this,CallUi.date(day),CallUi.SUMMARY,color=CallUi.SUBTLE).apply { tag="today_calls_date" })
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val half={ LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=CallUi.dp(this@TodayCallsActivity,6) } }
        nav.addView(CallUi.button(this,"◀ 전날") { go(day.minusDays(1)) }.apply { contentDescription="전날 통화 보기" },half())
        if(day!=CallUi.today()) nav.addView(CallUi.button(this,"오늘로") { go(CallUi.today()) },half())
        nav.addView(CallUi.button(this,"다음날 ▶") { go(day.plusDays(1)) }.apply { contentDescription="다음날 통화 보기";isEnabled=day<CallUi.today() },LinearLayout.LayoutParams(0,-2,1f))
        body.addView(nav)

        if(day==CallUi.today()) {
            body.addView(CallUi.heading(this,"오늘 할 일"))
            if(due.isEmpty()) body.addView(CallUi.text(this,"오늘까지 해야 할 일이 없어요."))
            due.take(5).forEach { item ->
                val late=CallUi.overdue(item.due)
                body.addView(CallUi.text(this,"• ${item.company?.let { "[$it] " } ?: ""}${item.title} — ${CallUi.dueWords(item.due)}"+(if(item.status=="needs_confirmation") " (확인 필요)" else ""),
                    CallUi.BODY,late,if(late) CallUi.WARN else CallUi.INK))
            }
            if(due.size>5) body.addView(CallUi.text(this,"외 ${due.size-5}개",CallUi.SMALL,color=CallUi.SUBTLE))
            body.addView(CallUi.button(this,"할 일 전체 보기") { startActivity(Intent(this,CallTasksActivity::class.java)) })
        }

        body.addView(CallUi.heading(this,"통화 ${calls.size}건"))
        if(calls.isEmpty()) body.addView(CallUi.text(this,"이 날짜에 정리된 통화가 없어요."))
        calls.forEach { c ->
            val card=CallUi.card(this,warn=c.state==CallRecordStore.FAILED).apply { isClickable=true;tag="call_${c.recordId}";contentDescription="${c.displayName} 통화 자세히 보기" }
            card.addView(CallUi.text(this,"${CallUi.time(c.startedMs)}  ${c.displayName}",CallUi.HEADING,true))
            if(c.summary!=null) card.addView(CallUi.text(this,c.summary,CallUi.SUMMARY).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END })
            else card.addView(CallUi.text(this,CallUi.state(c.state),CallUi.SUMMARY,true,if(c.state==CallRecordStore.FAILED) CallUi.WARN else CallUi.SUBTLE))
            c.analysis?.promises?.take(3)?.forEach { card.addView(CallUi.text(this,"약속  ${promiseLine(it)}",CallUi.BODY)) }
            card.addView(CallUi.text(this,"눌러서 자세히 보기 ›",CallUi.SMALL,color=CallUi.ACCENT))
            card.setOnClickListener { detail=c.recordId;render() }
            body.addView(card)
        }
    }

    private fun promiseLine(p:ClaudeCallAnalysis.Promise)=p.text+(p.due?.let { " (${CallUi.dueWords(it)})" } ?: "")+when(p.owner) { "me" -> " · 내가 할 일";"them" -> " · 상대가 할 일";else -> "" }

    private fun renderDetail(c:CallRecord) {
        body.addView(CallUi.button(this,"← 통화 목록으로") { detail=null;render() })
        body.addView(CallUi.title(this,c.displayName))
        body.addView(CallUi.text(this,"${CallUi.date(CallRecordStore.kstDay(c.dayMs))} ${CallUi.time(c.startedMs)} · 통화 ${CallUi.duration(c.durationMs)}",CallUi.BODY,color=CallUi.SUBTLE))
        if(c.state!=CallRecordStore.ANALYZED) {
            body.addView(CallUi.text(this,CallUi.state(c.state),CallUi.SUMMARY,true,if(c.state==CallRecordStore.FAILED) CallUi.WARN else CallUi.INK))
            when(c.state) {
                CallRecordStore.FAILED -> body.addView(CallUi.bigButton(this,"","다시 정리하기") { retry(c.recordId) })
                CallRecordStore.WAITING_KEY,CallRecordStore.WAITING_CONSENT -> body.addView(CallUi.bigButton(this,"⚙️","설정 열기") { startActivity(Intent(this,CallSettingsActivity::class.java)) })
            }
        }
        val a=c.analysis
        if(a!=null) {
            body.addView(CallUi.heading(this,"요약"));body.addView(CallUi.text(this,a.summary,CallUi.SUMMARY))
            if(a.keyPoints.isNotEmpty()) { body.addView(CallUi.heading(this,"중요한 내용"));a.keyPoints.forEach { body.addView(CallUi.text(this,"• $it")) } }
            if(a.promises.isNotEmpty()) {
                body.addView(CallUi.heading(this,"약속"))
                a.promises.forEach { body.addView(CallUi.text(this,promiseLine(it),CallUi.BODY,true));body.addView(CallUi.text(this,"“${it.quote}”",CallUi.SMALL,color=CallUi.SUBTLE)) }
            }
            val done=c.applied.filter { it.applied }
            if(done.isNotEmpty()) {
                body.addView(CallUi.heading(this,"할 일 목록에 반영한 것"))
                done.forEach { t -> body.addView(CallUi.text(this,"• "+when(t.op) { "create" -> "새 할 일";"update" -> "바뀐 할 일";else -> "끝났는지 확인 필요" }+": ${t.title}"+(t.due?.takeIf{it.isNotBlank()}?.let { " (${CallUi.dueWords(it)})" } ?: ""))) }
            }
        }
        if(c.transcript.isNotBlank()) {
            val full=CallUi.text(this,c.transcript,CallUi.BODY).apply { visibility=View.GONE }
            body.addView(CallUi.button(this,"통화 내용 전체 보기") { full.visibility=if(full.visibility==View.GONE) View.VISIBLE else View.GONE }.apply { tag="toggle_transcript" })
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
