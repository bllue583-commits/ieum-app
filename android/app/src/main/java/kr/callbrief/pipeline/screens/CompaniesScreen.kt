package kr.callbrief.pipeline

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "업체별 보기": the companies the phone has learned from calls, the most recently called first.
 * Tap one to see its memo, people, open tasks and recent calls. Misheard people names are fixed here. */
internal class CompaniesScreen(a:MainActivity):AppScreen(a) {
    private val body:LinearLayout get()=a.column
    private var wantedName:String?=null
    private var numbers:Map<String,String> =emptyMap()
    private var shown:String?=null
    private var detailId:String?=null
    override val detail:String? get()=detailId
    private var rows:List<CompanyRow> =emptyList()
    private var loose:List<PersonRow> =emptyList()
    private var info:CompanyDetail?=null
    private var failed=false
    /** Where the list was scrolled when a company opened; put back when its page closes (design G1). */
    private var listY=0
    private var pendingY=0

    override fun enter() { load() }
    /** The companies tab tapped while it is in front: the list from the top, no search. */
    override fun reselect() { closeAll();load();CallUi.scrollTo(body,0) }
    override fun back():Boolean { if(detailId==null && wantedName==null) return false;closeDetail();return true }
    /** Back to the list, nothing open, no search (a tab or a notification). */
    fun closeAll() { detailId=null;wantedName=null;query="";listY=0;pendingY=0;shown=null }
    /** From a call's company link: that company's page (found by name when it is read). */
    fun showByName(name:String) { detailId=null;info=null;wantedName=name;listY=0;pendingY=0 }
    private fun openDetail(id:String) { if(!a.once("company:$id")) return;listY=CallUi.scrollY(body);detailId=id;query="";load() }
    private fun closeDetail() { detailId=null;wantedName=null;pendingY=listY;listY=0;load() }
    override fun save(out:Bundle) { detailId?.let { out.putString("company",it) };out.putString("companies_query",query) }
    override fun restore(saved:Bundle) { detailId=saved.getString("company");query=saved.getString("companies_query") ?: "" }

    private fun load() {
        val target=detailId
        a.lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                CallRecordStore(CallPipelineStartup.storage(a)).use { s ->
                    numbers=runCatching { s.dialNumbers() }.getOrDefault(emptyMap())
                    // A company link from a call: find the company by name (spaces ignored) and open it.
                    val id=target ?: wantedName?.let { n -> s.companies().firstOrNull { it.name.filterNot(Char::isWhitespace)==n.filterNot(Char::isWhitespace) }?.id }
                    if(id==null) Triple(s.companies(),s.peopleOf(null),null) else Triple(emptyList(),emptyList(),s.companyDetail(id))
                }
            } }
            if(!live()||target!=detailId)return@launch
            failed=result.isFailure
            result.onSuccess { (r,p,d) -> rows=r;loose=p;info=d;if(target==null && d!=null) detailId=d.id }
            wantedName=null
            render()
        }
    }

    private fun render() {
        val d=info
        val mode=if(detailId!=null && d!=null) "detail" else "list"
        val keep=if(mode==shown) CallUi.scrollY(body) else 0;shown=mode
        body.removeAllViews()
        if(failed) { CallUi.band(body,"업체와 사람");body.addView(CallUi.text(a,"업체 정보를 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        if(detailId!=null && d!=null) { renderDetail(d);CallUi.restoreScroll(body,keep);return }
        detailId=null
        CallUi.band(body,"업체와 사람",if(rows.isEmpty()) null else "업체 ${rows.size}곳, 최근에 통화한 순서예요")
        if(rows.isEmpty()) body.addView(CallUi.card(a).apply { addView(CallUi.text(a,"아직 업체가 없어요. 통화가 정리되면 자동으로 채워져요.",CallUi.BODY,color=CallUi.SUBTLE)) })
        if(rows.isNotEmpty() || loose.isNotEmpty()) {
            body.addView(ScreenParts.searchBox(a,"업체, 사람 찾기",query,"companies_search") { q -> query=q;fill() })
            listBox=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="companies_box" }
            body.addView(listBox);fill()
        }
        val y=if(pendingY>0) pendingY else keep;pendingY=0
        a.putBack(y)
    }

    private var query=""
    private lateinit var listBox:LinearLayout
    internal fun matches(c:CompanyRow,q:String)=ScreenParts.matches(q,c.name,c.trade)
    /** The company list under the search line: the companies whose name or trade has what is typed (people without a company by their name). */
    private fun fill() {
        listBox.removeAllViews()
        val found=rows.filter { matches(it,query) }
        val people=loose.filter { ScreenParts.matches(query,it.name,it.role) }
        if(found.isEmpty() && people.isEmpty()) { listBox.addView(CallUi.card(a).apply { tag="companies_none";addView(CallUi.text(a,"찾는 업체가 없어요.",CallUi.BODY,color=CallUi.SUBTLE)) });return }
        // One grouped list: rounded-square badge, name, trade on the right, check items and last call below.
        if(found.isNotEmpty()) listBox.addView(CallUi.list(a,found.map { c ->
            CallUi.memoRow(a,c.trade.orEmpty(),c.name,(if(c.openTasks>0) "체크할 일 ${c.openTasks}개" else "체크할 일 없음")+", "+
                (c.lastCallMs?.let { "${CallUi.dayWord(CallRecordStore.kstDay(it))} 마지막 통화" } ?: "통화 기록 없음"),company=true) { openDetail(c.id) }
                .apply { tag="company_${c.id}";contentDescription="${c.name}, 사람과 메모 자세히 보기" }
        }))
        if(people.isNotEmpty()) { listBox.addView(CallUi.section(a,"업체 없는 사람 (우리 직원 등)"));listBox.addView(CallUi.list(a,people.map(::personRow))) }
    }

    /** One person: round badge, name, role, a quiet [이름 고치기] for misheard names, and a phone button when the number is known. */
    private fun personRow(p:PersonRow)=LinearLayout(a).apply {
        tag="person_${p.id}";orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        setPadding(CallUi.dp(a,16),CallUi.dp(a,10),CallUi.dp(a,16),CallUi.dp(a,6))
        addView(CallUi.avatar(a,p.name))
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        words.addView(CallUi.text(a,p.name,CallUi.BODY,true).apply { setPadding(0,0,0,0) })
        listOfNotNull(p.role,"우리 직원".takeIf { p.isStaff }).joinToString(", ").takeIf { it.isNotEmpty() }
            ?.let { words.addView(CallUi.caption(a,it).apply { setPadding(0,0,0,0) }) }
        words.addView(CallUi.caption(a,"이름 고치기",CallUi.MUTED,true).apply {
            tag="person_rename_${p.id}";contentDescription="${p.name} 이름 고치기";isClickable=true;isFocusable=true;background=CallUi.rowRipple()
            minHeight=CallUi.dp(a,44);gravity=android.view.Gravity.CENTER_VERTICAL;setOnClickListener { rename(p) }
        })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        CallUi.dialButton(a,numbers[p.id],p.name)?.let { addView(it) }
    }

    /** Speech recognition mishears names; the fix shows everywhere and the old spelling keeps finding this person. */
    private fun rename(p:PersonRow) {
        val input=CallUi.sheetInput(a,p.name,tag="person_name_input")
        CallUi.sheetDialog(a,"이름 고치기",message="바른 이름을 적고 [저장]을 누르세요.",body=input,secondary="취소",primary="저장",
            onPrimary={
                val name=input.text.toString()
                a.lifecycleScope.launch {
                    val ok=runCatching { withContext(Dispatchers.IO) { CallRecordStore(CallPipelineStartup.storage(a)).use { it.renamePerson(p.id,name) } } }.getOrDefault(false)
                    if(!live())return@launch
                    Toast.makeText(a,if(ok) "이름을 고쳤어요" else "이름을 비워둘 수 없어요",Toast.LENGTH_SHORT).show()
                    load()
                }
            })
    }

    private fun renderDetail(d:CompanyDetail) {
        // The round phone button of the title dials the person of this company called most recently (when a number is known).
        val main=d.people.firstOrNull { numbers[it.id]!=null }
        CallUi.band(body,d.name,d.trade?.let { "공종 $it" },back={ closeDetail() },trailing=main?.let { CallUi.dialButton(a,numbers[it.id],it.name) })
        body.addView(CallUi.section(a,"업체 메모"))
        body.addView(CallUi.card(a).apply { addView(CallUi.text(a,d.memo.ifBlank { "아직 메모가 없어요. 통화가 쌓이면 자동으로 채워져요." },CallUi.BODY,color=if(d.memo.isBlank()) CallUi.SUBTLE else CallUi.INK)) })
        // "진행 중인 일" from the morning tidy (design: 새벽 정리); shown only once there are some.
        if(d.issues.isNotEmpty()) {
            body.addView(CallUi.section(a,"진행 중인 일 (${d.issues.size})"))
            val c=CallUi.card(a).apply { tag="company_issues" }
            d.issues.forEachIndexed { i,x ->
                if(i>0) c.addView(CallUi.divider(a))
                c.addView(CallUi.text(a,x.title,CallUi.BODY,true))
                if(x.status.isNotBlank()) c.addView(CallUi.caption(a,x.status))
            }
            body.addView(c)
        }
        body.addView(CallUi.section(a,"사람 (${d.people.size})"))
        if(d.people.isEmpty()) body.addView(CallUi.text(a,"아직 알게 된 사람이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        if(d.people.isNotEmpty()) body.addView(CallUi.list(a,d.people.map(::personRow)))
        body.addView(CallUi.section(a,"체크할 일 (${d.tasks.size})"))
        if(d.tasks.isEmpty()) body.addView(CallUi.text(a,"체크할 일이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        else body.addView(CallUi.list(a,d.tasks.map { t ->
            CallUi.taskRow(a,t.title,t.createdMs,null,t.counterpart,t.status,onOpen={ TaskActions.openSource(a,t) },aiNote=t.aiNote).apply { tag="company_task_${t.id}" } },inset=16))
        body.addView(CallUi.section(a,"최근 통화"))
        if(d.calls.isEmpty()) body.addView(CallUi.text(a,"아직 통화 기록이 없어요.",CallUi.BODY,color=CallUi.SUBTLE))
        if(d.calls.isNotEmpty()) body.addView(CallUi.list(a,d.calls.map { call ->
            // The whole row opens that call.
            CallUi.memoRow(a,CallUi.dayWord(CallRecordStore.kstDay(call.dayMs)),call.person?.let { "$it 님과 통화" } ?: "통화",call.summary?.let(CallUi::firstSentence) ?: "아직 정리 중이에요",call.summary==null,avatarName=call.person) {
                a.openCall(call.recordId,call.dayMs)
            }.apply { tag="company_call_${call.recordId}" } }))
    }
}
