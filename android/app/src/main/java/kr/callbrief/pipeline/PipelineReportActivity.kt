package kr.callbrief.pipeline

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.AccountStorageContext

/** Shared layout for the pipeline screens: large text, capture protection, IO off the main thread. */
abstract class PipelineScreenActivity:ComponentActivity() {
    protected lateinit var body:LinearLayout
    protected var busy=false
    protected lateinit var storage:Context
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll=ScrollView(this).apply { setBackgroundColor(Color.rgb(221,241,248)) }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.setOnApplyWindowInsetsListener { v,i->v.setPadding(dp(16),i.systemWindowInsetTop+dp(12),dp(16),i.systemWindowInsetBottom+dp(12));i }
        scroll.addView(body);setContentView(scroll)
        storage=try { AccountStorageContext.selected(this) } catch(_:Exception) { this }
        refresh()
    }
    protected fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    protected fun label(t:String,bold:Boolean=false)=TextView(this).apply { text=t;textSize=if(bold)22f else 18f;setTextColor(Color.rgb(13,34,112));setPadding(0,dp(6),0,dp(6));if(bold)setTypeface(typeface,Typeface.BOLD);isSaveEnabled=false }
    protected fun button(t:String,action:()->Unit)=Button(this).apply { text=t;textSize=18f;isAllCaps=false;minHeight=dp(56);setOnClickListener { if(!busy&&!isFinishing&&!isDestroyed)action() } }
    protected fun field(hint:String,secret:Boolean=false)=EditText(this).apply {
        this.hint=hint;textSize=18f;minHeight=dp(56);isSaveEnabled=false;isSaveFromParentEnabled=false
        importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    /** Runs [work] on IO, then [done] on the main thread. A failed action ([action]=true) re-renders
     * the screen with [failure]; a failed read shows a static message (no retry loop). */
    protected fun <T> io(failure:String,work:()->T,action:Boolean=false,done:(T)->Unit) {
        if(busy)return;busy=true
        lifecycleScope.launch {
            val r=runCatching { withContext(Dispatchers.IO) { work() } }
            busy=false;if(isFinishing||isDestroyed)return@launch
            r.onSuccess(done).onFailure {
                if(action) { message=failure;refresh() }
                else { body.removeAllViews();body.addView(label(failure));body.addView(button("다시 확인") { message="";refresh() });body.addView(button("돌아가기") { finish() }) }
            }
        }
    }
    protected var message=""
    abstract fun refresh()
}

/** The morning report (C3) fetched from the inbox. Latest revision per date; past dates listed. */
class PipelineReportActivity:PipelineScreenActivity() {
    private var date:String?=null
    override fun onCreate(savedInstanceState:Bundle?) { date=intent.getStringExtra("report_date");super.onCreate(savedInstanceState) }
    override fun refresh() {
        io("아침 보고를 읽지 못했어요. 저장된 보고는 그대로예요.",{
            PipelineApplyStore(storage).use { s ->
                val head=(date?.let { s.reportHead(it) } ?: s.latestReport())?.let { s.payload(it) }
                head to s.reportDates().map { it["report_date"].toString() to PipelineApplyStore.n(it,"revision") }
            }
        }) { (report,dates) -> render(report,dates) }
    }
    private fun render(report:Map<String,Any?>?,dates:List<Pair<String,Long>>) {
        body.removeAllViews();body.addView(label("아침 보고",true))
        if(message.isNotBlank())body.addView(label(message))
        if(report==null) body.addView(label("아직 받은 아침 보고가 없어요. 보고는 매일 오전 6시쯤 만들어져요."))
        else text(report).forEach { (t,b)->body.addView(label(t,b)) }
        body.addView(label("지난 보고",true))
        if(dates.isEmpty())body.addView(label("보관된 보고가 없어요."))
        dates.forEach { (d,r)->body.addView(button("$d · ${r}판") { date=d;refresh() }.apply { tag="pipeline_report_$d" }) }
        body.addView(button("다시 확인") { refresh() });body.addView(button("돌아가기") { finish() })
    }
    companion object {
        private val STATUS=mapOf("open" to "할 일","in_progress" to "진행 중","waiting" to "대기","needs_confirmation" to "확인 필요","done" to "완료")
        private val REASON=mapOf("DONE_CLAIMED" to "완료 여부 확인","MANUAL_TASK" to "직접 관리 중인 할 일","CASE_UNCONFIRMED" to "업무 연결 확인","DUE_CONFLICT" to "기한 확인")
        /** Report text lines (text, bold). Pure, so tests can check the layout content. */
        @Suppress("UNCHECKED_CAST")
        fun text(r:Map<String,Any?>):List<Pair<String,Boolean>> {
            val out=mutableListOf<Pair<String,Boolean>>()
            out+=("${r["reportDate"]} · ${r["revision"]}판" to true)
            val p=PipelineContract.obj(r["pending"])
            out+=("자막 미완료 ${p["untranscribed"]}건 · 분석 대기 ${p["awaitingAnalysis"]}건 · 실패 ${p["failed"]}건 · 시각 미상 ${p["unknownTime"]}건" to false)
            val cases=r["cases"] as List<Any?>
            if(cases.isEmpty())out+=("어제 분석된 통화가 없어요." to false)
            for(cv in cases) {
                val c=PipelineContract.obj(cv)
                out+=((if(c["caseId"]==null)"${c["label"]} · 업무 연결 확인 필요" else c["label"].toString()) to true)
                out+=(c["summary"].toString() to false)
                (c["newTasks"] as List<Any?>).map { PipelineContract.obj(it) }.forEach { out+=("새 할 일: ${it["title"]}${it["due"]?.let { d->" · 기한 $d" } ?: ""} · ${STATUS[it["status"]] ?: it["status"]}" to false) }
                (c["changedTasks"] as List<Any?>).map { PipelineContract.obj(it) }.forEach { out+=("바뀐 할 일: ${it["title"]}${it["due"]?.let { d->" · 기한 $d" } ?: ""} · ${STATUS[it["status"]] ?: it["status"]}" to false) }
                (c["needsConfirmation"] as List<Any?>).map { PipelineContract.obj(it) }.forEach { out+=("확인 필요: ${it["title"]} · ${REASON[it["reason"]] ?: it["reason"]}" to false) }
            }
            val w=PipelineContract.obj(r["weekly"])
            out+=("주간 요약" to true)
            out+=((w["summary"].toString().ifBlank { "이번 주 요약이 아직 없어요." }) to false)
            return out
        }
    }
}
