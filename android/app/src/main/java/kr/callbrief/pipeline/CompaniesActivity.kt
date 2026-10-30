package kr.callbrief.pipeline

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "업체별 보기": the companies the phone has learned from calls, the most recently called first.
 * Tap one to see its memo, people, open tasks and recent calls. Misheard people names are fixed here. */
class CompaniesActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var detailId:String?=null
    private var rows:List<CompanyRow> =emptyList()
    private var loose:List<PersonRow> =emptyList()
    private var detail:CompanyDetail?=null
    private var failed=false

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        detailId=savedInstanceState?.getString("company")
        body=CallUi.page(this);(body.parent as View).tag="companies_scroll"
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(detailId!=null) { detailId=null;load() } else finish() }
        })
    }
    override fun onResume() { super.onResume();load() }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);detailId?.let { out.putString("company",it) } }

    private fun load() {
        val target=detailId
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(this@CompaniesActivity)).use { s ->
                    if(target==null) Triple(s.companies(),s.peopleOf(null),null) else Triple(emptyList(),emptyList(),s.companyDetail(target)) }
            } }
            if(isFinishing||isDestroyed||target!=detailId)return@launch
            failed=result.isFailure
            result.onSuccess { (r,p,d) -> rows=r;loose=p;detail=d }
            render()
        }
    }

    private fun render() {
        body.removeAllViews()
        if(failed) { body.addView(CallUi.title(this,"업체·사람 보기"));body.addView(CallUi.text(this,"업체 정보를 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        val d=detail
        if(detailId!=null && d!=null) { renderDetail(d);return }
        detailId=null
        body.addView(CallUi.title(this,"업체·사람 보기"))
        if(rows.isEmpty()) body.addView(CallUi.text(this,"아직 업체가 없어요. 통화가 정리되면 자동으로 채워져요.",CallUi.SUMMARY))
        else body.addView(CallUi.text(this,"업체 ${rows.size}곳 · 최근에 통화한 순서",CallUi.SUMMARY,color=CallUi.SUBTLE))
        rows.forEach { c ->
            val card=CallUi.card(this).apply { isClickable=true;tag="company_${c.id}";contentDescription="${c.name} 자세히 보기" }
            card.addView(CallUi.text(this,c.name,CallUi.HEADING,true))
            c.trade?.let { card.addView(CallUi.pill(this,it,CallUi.ACCENT)) }
            card.addView(CallUi.text(this,"열린 할 일 ${c.openTasks}개",CallUi.BODY,c.openTasks>0,if(c.openTasks>0) CallUi.INK else CallUi.SUBTLE))
            card.addView(CallUi.text(this,"마지막 통화 "+(c.lastCallMs?.let { CallUi.date(CallRecordStore.kstDay(it)) } ?: "아직 없음"),CallUi.SMALL,color=CallUi.SUBTLE))
            card.setOnClickListener { detailId=c.id;load() }
            body.addView(card)
        }
        if(loose.isNotEmpty()) { body.addView(CallUi.heading(this,"업체 없는 사람 (우리 직원 등)"));loose.forEach(::personCard) }
    }

    private fun personCard(p:PersonRow) {
        val card=CallUi.card(this).apply { tag="person_${p.id}" }
        card.addView(CallUi.text(this,p.name,CallUi.HEADING,true))
        listOfNotNull(p.role,"우리 직원".takeIf { p.isStaff }).joinToString(" · ").takeIf { it.isNotEmpty() }
            ?.let { card.addView(CallUi.text(this,it,CallUi.BODY,color=CallUi.SUBTLE)) }
        card.addView(CallUi.button(this,"이름 고치기") { rename(p) }.apply { tag="person_rename_${p.id}" })
        body.addView(card)
    }

    /** Speech recognition mishears names; the fix shows everywhere and the old spelling keeps finding this person. */
    private fun rename(p:PersonRow) {
        val input=EditText(this).apply {
            setText(p.name);setSelection(p.name.length);textSize=CallUi.SUMMARY;isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT
            minHeight=CallUi.dp(this@CompaniesActivity,60);tag="person_name_input"
        }
        val box=LinearLayout(this).apply { val pad=CallUi.dp(this@CompaniesActivity,20);setPadding(pad,pad/2,pad,0);addView(input) }
        AlertDialog.Builder(this).setTitle("이름 고치기").setMessage("바른 이름을 적고 [저장]을 누르세요.").setView(box)
            .setNegativeButton("취소",null)
            .setPositiveButton("저장") { _,_ ->
                val name=input.text.toString()
                lifecycleScope.launch {
                    val ok=runCatching { withContext(Dispatchers.IO) { CallRecordStore(CallPipelineStartup.storage(this@CompaniesActivity)).use { it.renamePerson(p.id,name) } } }.getOrDefault(false)
                    if(isFinishing||isDestroyed)return@launch
                    Toast.makeText(this@CompaniesActivity,if(ok) "이름을 고쳤어요" else "이름을 비워둘 수 없어요",Toast.LENGTH_SHORT).show()
                    load()
                }
            }.show()
    }

    private fun renderDetail(d:CompanyDetail) {
        body.addView(CallUi.button(this,"← 업체 목록으로") { detailId=null;load() }.apply { tag="companies_back" })
        body.addView(CallUi.title(this,d.name))
        d.trade?.let { body.addView(CallUi.pill(this,it,CallUi.ACCENT)) }
        body.addView(CallUi.heading(this,"업체 메모"))
        body.addView(CallUi.text(this,d.memo.ifBlank { "아직 메모가 없어요. 통화가 쌓이면 자동으로 채워져요." },CallUi.BODY,color=if(d.memo.isBlank()) CallUi.SUBTLE else CallUi.INK))
        body.addView(CallUi.heading(this,"사람 (${d.people.size})"))
        if(d.people.isEmpty()) body.addView(CallUi.text(this,"아직 알게 된 사람이 없어요."))
        d.people.forEach(::personCard)
        body.addView(CallUi.heading(this,"열린 할 일 (${d.tasks.size})"))
        if(d.tasks.isEmpty()) body.addView(CallUi.text(this,"남은 할 일이 없어요."))
        d.tasks.forEach { t ->
            val rail=CallUi.railFor(t.due)
            val c=CallUi.card(this,rail=rail).apply { tag="company_task_${t.id}" }
            c.addView(CallUi.text(this,t.title,CallUi.SUMMARY,true))
            c.addView(CallUi.pill(this,CallUi.dueWords(t.due)+(CallUi.taskStatus(t.status).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                when(rail) { CallUi.WARN -> CallUi.WARN;null -> CallUi.SUBTLE;else -> CallUi.AMBER }))
            body.addView(c)
        }
        body.addView(CallUi.heading(this,"최근 통화"))
        if(d.calls.isEmpty()) body.addView(CallUi.text(this,"아직 통화 기록이 없어요."))
        d.calls.forEach { call ->
            val c=CallUi.card(this).apply { tag="company_call_${call.recordId}" }
            c.addView(CallUi.text(this,CallUi.date(CallRecordStore.kstDay(call.dayMs))+(call.person?.let { " · $it" } ?: ""),CallUi.BODY,true))
            c.addView(CallUi.text(this,call.summary ?: "아직 정리 중이에요",CallUi.BODY,color=if(call.summary==null) CallUi.SUBTLE else CallUi.INK))
            body.addView(c)
        }
    }
}
