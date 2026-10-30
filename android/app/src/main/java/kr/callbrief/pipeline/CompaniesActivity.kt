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
        body=CallUi.page(this,CallUi.Tab.COMPANIES)
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
        if(failed) { CallUi.band(body,"업체와 사람");body.addView(CallUi.text(this,"업체 정보를 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        val d=detail
        if(detailId!=null && d!=null) { renderDetail(d);return }
        detailId=null
        CallUi.band(body,"업체와 사람",if(rows.isEmpty()) null else "업체 ${rows.size}곳, 최근에 통화한 순서예요")
        if(rows.isEmpty()) body.addView(CallUi.card(this).apply { addView(CallUi.text(this@CompaniesActivity,"아직 업체가 없어요. 통화가 정리되면 자동으로 채워져요.",CallUi.BODY,color=CallUi.SUBTLE)) })
        // One grouped list: badge, name, trade on the right, check items and last call below.
        if(rows.isNotEmpty()) body.addView(CallUi.list(this,rows.map { c ->
            CallUi.memoRow(this,c.trade.orEmpty(),c.name,(if(c.openTasks>0) "체크할 일 ${c.openTasks}개" else "체크할 일 없음")+", "+
                (c.lastCallMs?.let { "${CallUi.dayWord(CallRecordStore.kstDay(it))} 마지막 통화" } ?: "통화 기록 없음")) { detailId=c.id;load() }
                .apply { tag="company_${c.id}";contentDescription="${c.name}, 사람과 메모 자세히 보기" }
        }))
        if(loose.isNotEmpty()) { body.addView(CallUi.section(this,"업체 없는 사람 (우리 직원 등)"));body.addView(CallUi.list(this,loose.map(::personRow))) }
    }

    /** One person: badge, name, role, and a quiet [이름 고치기] for misheard names. */
    private fun personRow(p:PersonRow)=LinearLayout(this).apply {
        tag="person_${p.id}";orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        setPadding(CallUi.dp(this@CompaniesActivity,20),CallUi.dp(this@CompaniesActivity,10),CallUi.dp(this@CompaniesActivity,8),CallUi.dp(this@CompaniesActivity,10))
        addView(CallUi.avatar(this@CompaniesActivity,p.name))
        val words=LinearLayout(this@CompaniesActivity).apply { orientation=LinearLayout.VERTICAL }
        words.addView(CallUi.text(this@CompaniesActivity,p.name,CallUi.BODY,true).apply { setPadding(0,0,0,0) })
        listOfNotNull(p.role,"우리 직원".takeIf { p.isStaff }).joinToString(", ").takeIf { it.isNotEmpty() }
            ?.let { words.addView(CallUi.text(this@CompaniesActivity,it,CallUi.SMALL,color=CallUi.SUBTLE).apply { setPadding(0,0,0,0) }) }
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        addView(CallUi.linkButton(this@CompaniesActivity,"이름 고치기") { rename(p) }.apply { tag="person_rename_${p.id}";contentDescription="${p.name} 이름 고치기" })
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
        CallUi.band(body,d.name,d.trade?.let { "공종 $it" },back={ detailId=null;load() })
        body.addView(CallUi.section(this,"업체 메모"))
        body.addView(CallUi.card(this).apply { addView(CallUi.text(this@CompaniesActivity,d.memo.ifBlank { "아직 메모가 없어요. 통화가 쌓이면 자동으로 채워져요." },CallUi.BODY,color=if(d.memo.isBlank()) CallUi.SUBTLE else CallUi.INK)) })
        body.addView(CallUi.section(this,"사람 (${d.people.size})"))
        if(d.people.isEmpty()) body.addView(CallUi.text(this,"아직 알게 된 사람이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        if(d.people.isNotEmpty()) body.addView(CallUi.list(this,d.people.map(::personRow)))
        body.addView(CallUi.section(this,"체크할 일 (${d.tasks.size})"))
        if(d.tasks.isEmpty()) body.addView(CallUi.text(this,"체크할 일이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        d.tasks.forEach { t -> body.addView(CallUi.taskRow(this,t.title,t.createdMs,null,t.counterpart,t.status).apply { tag="company_task_${t.id}" }) }
        body.addView(CallUi.section(this,"최근 통화"))
        if(d.calls.isEmpty()) body.addView(CallUi.text(this,"아직 통화 기록이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        d.calls.forEach { call ->
            val c=CallUi.card(this).apply { tag="company_call_${call.recordId}" }
            c.addView(CallUi.text(this,CallUi.dayWord(CallRecordStore.kstDay(call.dayMs))+(call.person?.let { ", $it 님과 통화" } ?: ""),CallUi.SMALL,true,CallUi.ACCENT))
            c.addView(CallUi.text(this,call.summary ?: "아직 정리 중이에요",CallUi.BODY,color=if(call.summary==null) CallUi.SUBTLE else CallUi.INK).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END })
            body.addView(c)
        }
    }
}
