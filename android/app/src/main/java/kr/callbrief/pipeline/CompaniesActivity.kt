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
    companion object {
        /** Open the detail page of the company with this name (from a call's company link). */
        const val EXTRA_COMPANY_NAME="company_name"
    }
    private lateinit var body:LinearLayout
    private var wantedName:String?=null
    private var numbers:Map<String,String> =emptyMap()
    private var shown:String?=null
    private var detailId:String?=null
    private var rows:List<CompanyRow> =emptyList()
    private var loose:List<PersonRow> =emptyList()
    private var detail:CompanyDetail?=null
    private var failed=false

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        detailId=savedInstanceState?.getString("company")
        if(savedInstanceState==null) wantedName=intent?.getStringExtra(EXTRA_COMPANY_NAME)
        body=CallUi.page(this,CallUi.Tab.COMPANIES)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { closeDetail() } })
    }
    /** Opened from a call's company link: back returns to that call. Otherwise detail goes back to the list, the list to home. */
    private fun fromOutside()=intent?.hasExtra(EXTRA_COMPANY_NAME)==true
    private fun closeDetail() { if(fromOutside()) finish() else if(detailId!=null) { detailId=null;load() } else CallUi.go(this,CallUi.Tab.HOME) }
    override fun onResume() { super.onResume();load() }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);detailId?.let { out.putString("company",it) } }

    private fun load() {
        val target=detailId
        lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(this@CompaniesActivity)).use { s ->
                    numbers=runCatching { s.dialNumbers() }.getOrDefault(emptyMap())
                    // A company link from a call: find the company by name (spaces ignored) and open it.
                    val id=target ?: wantedName?.let { n -> s.companies().firstOrNull { it.name.filterNot(Char::isWhitespace)==n.filterNot(Char::isWhitespace) }?.id }
                    if(id==null) Triple(s.companies(),s.peopleOf(null),null) else Triple(emptyList(),emptyList(),s.companyDetail(id))
                }
            } }
            if(isFinishing||isDestroyed||target!=detailId)return@launch
            failed=result.isFailure
            result.onSuccess { (r,p,d) -> rows=r;loose=p;detail=d;if(target==null && d!=null) detailId=d.id }
            wantedName=null
            render()
        }
    }

    private fun render() {
        val d=detail
        val mode=if(detailId!=null && d!=null) "detail" else "list"
        val keep=if(mode==shown) CallUi.scrollY(body) else 0;shown=mode
        body.removeAllViews()
        if(failed) { CallUi.band(body,"업체와 사람");body.addView(CallUi.text(this,"업체 정보를 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        if(detailId!=null && d!=null) { renderDetail(d);CallUi.restoreScroll(body,keep);return }
        detailId=null
        CallUi.band(body,"업체와 사람",if(rows.isEmpty()) null else "업체 ${rows.size}곳, 최근에 통화한 순서예요")
        if(rows.isEmpty()) body.addView(CallUi.card(this).apply { addView(CallUi.text(this@CompaniesActivity,"아직 업체가 없어요. 통화가 정리되면 자동으로 채워져요.",CallUi.BODY,color=CallUi.SUBTLE)) })
        if(rows.isNotEmpty() || loose.isNotEmpty()) {
            body.addView(ScreenParts.searchBox(this,"업체, 사람 찾기",query,"companies_search") { q -> query=q;fill() })
            listBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;tag="companies_box" }
            body.addView(listBox);fill()
        }
        CallUi.restoreScroll(body,keep)
    }

    private var query=""
    private lateinit var listBox:LinearLayout
    internal fun matches(c:CompanyRow,q:String)=ScreenParts.matches(q,c.name,c.trade)
    /** The company list under the search line: the companies whose name or trade has what is typed (people without a company by their name). */
    private fun fill() {
        listBox.removeAllViews()
        val found=rows.filter { matches(it,query) }
        val people=loose.filter { ScreenParts.matches(query,it.name,it.role) }
        if(found.isEmpty() && people.isEmpty()) { listBox.addView(CallUi.card(this).apply { tag="companies_none";addView(CallUi.text(this@CompaniesActivity,"찾는 업체가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)) });return }
        // One grouped list: rounded-square badge, name, trade on the right, check items and last call below.
        if(found.isNotEmpty()) listBox.addView(CallUi.list(this,found.map { c ->
            CallUi.memoRow(this,c.trade.orEmpty(),c.name,(if(c.openTasks>0) "체크할 일 ${c.openTasks}개" else "체크할 일 없음")+", "+
                (c.lastCallMs?.let { "${CallUi.dayWord(CallRecordStore.kstDay(it))} 마지막 통화" } ?: "통화 기록 없음"),company=true) { detailId=c.id;query="";load() }
                .apply { tag="company_${c.id}";contentDescription="${c.name}, 사람과 메모 자세히 보기" }
        }))
        if(people.isNotEmpty()) { listBox.addView(CallUi.section(this,"업체 없는 사람 (우리 직원 등)"));listBox.addView(CallUi.list(this,people.map(::personRow))) }
    }

    /** One person: round badge, name, role, a quiet [이름 고치기] for misheard names, and a phone button when the number is known. */
    private fun personRow(p:PersonRow)=LinearLayout(this).apply {
        tag="person_${p.id}";orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        setPadding(CallUi.dp(this@CompaniesActivity,16),CallUi.dp(this@CompaniesActivity,10),CallUi.dp(this@CompaniesActivity,16),CallUi.dp(this@CompaniesActivity,6))
        addView(CallUi.avatar(this@CompaniesActivity,p.name))
        val words=LinearLayout(this@CompaniesActivity).apply { orientation=LinearLayout.VERTICAL }
        words.addView(CallUi.text(this@CompaniesActivity,p.name,CallUi.BODY,true).apply { setPadding(0,0,0,0) })
        listOfNotNull(p.role,"우리 직원".takeIf { p.isStaff }).joinToString(", ").takeIf { it.isNotEmpty() }
            ?.let { words.addView(CallUi.caption(this@CompaniesActivity,it).apply { setPadding(0,0,0,0) }) }
        words.addView(CallUi.caption(this@CompaniesActivity,"이름 고치기",CallUi.MUTED,true).apply {
            tag="person_rename_${p.id}";contentDescription="${p.name} 이름 고치기";isClickable=true;isFocusable=true;background=CallUi.rowRipple()
            minHeight=CallUi.dp(this@CompaniesActivity,44);gravity=android.view.Gravity.CENTER_VERTICAL;setOnClickListener { rename(p) }
        })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        CallUi.dialButton(this@CompaniesActivity,numbers[p.id],p.name)?.let { addView(it) }
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
        // The round phone button of the title dials the person of this company called most recently (when a number is known).
        val main=d.people.firstOrNull { numbers[it.id]!=null }
        CallUi.band(body,d.name,d.trade?.let { "공종 $it" },back={ closeDetail() },trailing=main?.let { CallUi.dialButton(this,numbers[it.id],it.name) })
        body.addView(CallUi.section(this,"업체 메모"))
        body.addView(CallUi.card(this).apply { addView(CallUi.text(this@CompaniesActivity,d.memo.ifBlank { "아직 메모가 없어요. 통화가 쌓이면 자동으로 채워져요." },CallUi.BODY,color=if(d.memo.isBlank()) CallUi.SUBTLE else CallUi.INK)) })
        // "진행 중인 일" from the morning tidy (design: 새벽 정리); shown only once there are some.
        if(d.issues.isNotEmpty()) {
            body.addView(CallUi.section(this,"진행 중인 일 (${d.issues.size})"))
            val c=CallUi.card(this).apply { tag="company_issues" }
            d.issues.forEachIndexed { i,x ->
                if(i>0) c.addView(CallUi.divider(this))
                c.addView(CallUi.text(this,x.title,CallUi.BODY,true))
                if(x.status.isNotBlank()) c.addView(CallUi.caption(this,x.status))
            }
            body.addView(c)
        }
        body.addView(CallUi.section(this,"사람 (${d.people.size})"))
        if(d.people.isEmpty()) body.addView(CallUi.text(this,"아직 알게 된 사람이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        if(d.people.isNotEmpty()) body.addView(CallUi.list(this,d.people.map(::personRow)))
        body.addView(CallUi.section(this,"체크할 일 (${d.tasks.size})"))
        if(d.tasks.isEmpty()) body.addView(CallUi.text(this,"체크할 일이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        else body.addView(CallUi.list(this,d.tasks.map { t ->
            CallUi.taskRow(this,t.title,t.createdMs,null,t.counterpart,t.status,onOpen={ TaskActions.openSource(this,t) },aiNote=t.aiNote).apply { tag="company_task_${t.id}" } },inset=16))
        body.addView(CallUi.section(this,"최근 통화"))
        if(d.calls.isEmpty()) body.addView(CallUi.text(this,"아직 통화 기록이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        if(d.calls.isNotEmpty()) body.addView(CallUi.list(this,d.calls.map { call ->
            // The whole row opens that call.
            CallUi.memoRow(this,CallUi.dayWord(CallRecordStore.kstDay(call.dayMs)),call.person?.let { "$it 님과 통화" } ?: "통화",call.summary?.let(CallUi::firstSentence) ?: "아직 정리 중이에요",call.summary==null,avatarName=call.person) {
                startActivity(android.content.Intent(this,TodayCallsActivity::class.java).putExtra(TodayCallsActivity.EXTRA_CALL,call.recordId).putExtra(TodayCallsActivity.EXTRA_DAY_MS,call.dayMs))
            }.apply { tag="company_call_${call.recordId}" } }))
    }
}
