package kr.callbrief.pipeline

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "오늘의 통화": one KST day of analyzed calls, today's due items, and a detail view per call. */
class TodayCallsActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var day:LocalDate=CallRecordStore.kstDay(System.currentTimeMillis())
    private var detail:String?=null
    private var calls:List<CallRecord> =emptyList()
    private var due:List<CallTodoItem> =emptyList()
    private val today get()=CallRecordStore.kstDay(System.currentTimeMillis())

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        savedInstanceState?.getString("day")?.let { day=LocalDate.parse(it) }
        detail=savedInstanceState?.getString("detail")
        val scroll=ScrollView(this).apply { tag="today_calls_scroll" };body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            val p=dp(16);view.setPadding(p,insets.systemWindowInsetTop+p,p,insets.systemWindowInsetBottom+p);insets
        }
        scroll.addView(body);setContentView(scroll)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(detail!=null) { detail=null;render() } else finish() }
        })
    }
    override fun onResume() { super.onResume();load() }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);out.putString("day",day.toString());detail?.let { out.putString("detail",it) } }

    private fun dp(v:Int)=(v*resources.displayMetrics.density+0.5f).toInt()
    private fun label(value:String,size:Float=18f,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value;textSize=18f;isAllCaps=false;minHeight=dp(56);setOnClickListener { action() } }
    private fun divider()=View(this).apply { setBackgroundColor(0x22000000);layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(8);bottomMargin=dp(8) } }
    private fun time(ms:Long?)=ms?.let { DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(CallRecordStore.KST)) } ?: "시각 미상"
    private fun dateTitle(d:LocalDate)=DateTimeFormatter.ofPattern("M월 d일 (E)",Locale.KOREAN).format(d)+when(d) { today -> " · 오늘";today.minusDays(1) -> " · 어제";else -> "" }

    private fun load() {
        val target=day
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { it.day(target) to it.dueItems(today) }
            } }
            if(isFinishing||isDestroyed||target!=day)return@launch
            result.onSuccess { (c,d) -> calls=c;due=d;render() }
            result.onFailure { body.removeAllViews();body.addView(label("저장된 통화를 읽지 못했어요. 잠시 후 다시 열어 주세요.")) }
        }
    }

    private fun render() {
        body.removeAllViews()
        val record=detail?.let { id -> calls.firstOrNull { it.recordId==id } }
        if(record!=null) { renderDetail(record);return }
        detail=null
        body.addView(label("오늘의 통화",26f,true))
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;tag="today_calls_date" }
        header.addView(button("◀") { day=day.minusDays(1);load() }.apply { contentDescription="전날" },LinearLayout.LayoutParams(dp(64),-2))
        header.addView(label(dateTitle(day),20f,true).apply { gravity=android.view.Gravity.CENTER },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("▶") { day=day.plusDays(1);load() }.apply { contentDescription="다음날";isEnabled=day<today },LinearLayout.LayoutParams(dp(64),-2))
        body.addView(header)

        body.addView(label("오늘 할 일",22f,true))
        if(due.isEmpty()) body.addView(label("기한이 오늘이거나 지난 할 일이 없어요."))
        due.forEach { item ->
            val late=item.due<today.toString()
            body.addView(label("• ${item.title}\n   ${if(late) "기한 지남 · " else "오늘까지 · "}${item.due}${if(item.status=="needs_confirmation") " · 끝났는지 확인 필요" else ""}"))
        }
        body.addView(divider())
        body.addView(label("통화 ${calls.size}건",22f,true))
        if(calls.isEmpty()) body.addView(label("이 날짜에 정리된 통화가 없어요."))
        calls.forEach { c ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;isClickable=true;setPadding(0,dp(4),0,dp(4));tag="call_${c.recordId}" }
            card.addView(label("${time(c.startedMs)} · ${c.displayName}",20f,true))
            card.addView(label(c.summary ?: stateText(c.state,c.error)).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END })
            c.analysis?.promises?.forEach { card.addView(label("약속: ${promiseLine(it)}")) }
            card.setOnClickListener { detail=c.recordId;render() }
            body.addView(card);body.addView(divider())
        }
        body.addView(button("통화 정리 설정") { startActivity(Intent(this,CallSettingsActivity::class.java)) })
    }

    private fun promiseLine(p:ClaudeCallAnalysis.Promise)=p.text+(p.due?.let { " ($it)" } ?: "")+when(p.owner) { "me" -> " · 내가";"them" -> " · 상대가";else -> "" }

    private fun stateText(state:String,error:String?)=when(state) {
        CallRecordStore.TRANSCRIBED -> "분석 대기"
        CallRecordStore.WAITING_KEY -> "API 키 필요"
        CallRecordStore.WAITING_CONSENT -> "동의 필요"
        CallRecordStore.FAILED -> "실패: 다시 시도"+(error?.let { " ($it)" } ?: "")
        else -> ""
    }

    private fun renderDetail(c:CallRecord) {
        body.addView(button("← 목록") { detail=null;render() })
        body.addView(label("${time(c.startedMs)} · ${c.displayName}",24f,true))
        body.addView(label("${dateTitle(CallRecordStore.kstDay(c.dayMs))} · ${c.durationMs/60000}분 ${(c.durationMs/1000)%60}초"))
        if(c.state!=CallRecordStore.ANALYZED) {
            body.addView(label(stateText(c.state,c.error),20f,true))
            when(c.state) {
                CallRecordStore.FAILED -> body.addView(button("다시 시도") { retry(c.recordId) })
                CallRecordStore.WAITING_KEY,CallRecordStore.WAITING_CONSENT -> body.addView(button("통화 정리 설정 열기") { startActivity(Intent(this,CallSettingsActivity::class.java)) })
            }
        }
        val a=c.analysis
        if(a!=null) {
            body.addView(label("요약",20f,true));body.addView(label(a.summary))
            if(a.keyPoints.isNotEmpty()) { body.addView(label("핵심 내용",20f,true));a.keyPoints.forEach { body.addView(label("• $it")) } }
            if(a.promises.isNotEmpty()) {
                body.addView(label("약속",20f,true))
                a.promises.forEach { body.addView(label("• ${promiseLine(it)}\n   “${it.quote}”")) }
            }
            val done=c.applied.filter { it.applied }
            if(done.isNotEmpty()) {
                body.addView(label("할 일",20f,true))
                done.forEach { t -> body.addView(label("• "+when(t.op) { "create" -> "새 할 일";"update" -> "수정";else -> "완료 확인 필요" }+": ${t.title}"+(t.due?.let { " ($it)" } ?: ""))) }
            }
        }
        if(c.transcript.isNotBlank()) {
            val full=label(c.transcript).apply { visibility=View.GONE;setTextIsSelectable(false) }
            body.addView(button("전체 자막 보기") { full.visibility=if(full.visibility==View.GONE) View.VISIBLE else View.GONE }.apply { tag="toggle_transcript" })
            body.addView(full)
        }
    }

    private fun retry(id:String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CallRecordStore(CallPipelineStartup.storage(this@TodayCallsActivity)).use { it.setState(id,CallRecordStore.TRANSCRIBED) } }
                runCatching { CallPipelineStartup.enqueueAnalysis(this@TodayCallsActivity) }
            }
            Toast.makeText(this@TodayCallsActivity,"다시 분석을 요청했어요.",Toast.LENGTH_SHORT).show();load()
        }
    }
}
