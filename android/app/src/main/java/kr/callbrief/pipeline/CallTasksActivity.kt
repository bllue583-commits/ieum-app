package kr.callbrief.pipeline

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "체크할 일": every open to-do, grouped by the day it was asked for, each with one "다 됐어요" button (and a 5 second undo).
 * Completion is always the user's explicit action (the AI only ever marks "확인 필요"). */
class CallTasksActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var items:List<OpenItem> =emptyList()
    private var done:List<OpenItem> =emptyList()
    private var companies:List<String> =emptyList()
    private companion object { const val CHECK_GROUP="끝났는지 확인해 주세요";const val LATER_GROUP="그전에 요청한 일" }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        body=CallUi.page(this,CallUi.Tab.TASKS)
    }
    override fun onResume() { super.onResume();load() }

    private fun load() {
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallTasksActivity)).use { s ->
                Triple(s.openItems(),runCatching { s.doneItems(System.currentTimeMillis()-7*86_400_000L) }.getOrDefault(emptyList()),
                    runCatching { s.companies().map { it.name } }.getOrDefault(emptyList())) } } }
            if(isFinishing||isDestroyed)return@launch
            items=result.getOrNull()?.first.orEmpty();done=result.getOrNull()?.second.orEmpty();companies=result.getOrNull()?.third.orEmpty();render(result.isSuccess)
        }
    }

    /** Grouped by when they were asked for, newest first; ones the AI thinks are finished come first for a check. */
    internal fun groups(all:List<OpenItem>):List<Pair<String,List<OpenItem>>> {
        val today=CallUi.today()
        fun day(i:OpenItem)=CallRecordStore.kstDay(i.createdMs)
        val check=all.filter { it.status=="needs_confirmation" }
        val rest=(all-check.toSet()).sortedByDescending { it.createdMs }
        return listOf(CHECK_GROUP to check,"오늘 요청한 일" to rest.filter { day(it)==today },
            "어제 요청한 일" to rest.filter { day(it)==today.minusDays(1) },LATER_GROUP to rest.filter { day(it)<today.minusDays(1) })
            .filter { it.second.isNotEmpty() }
    }

    /** Asked for more than this many days ago: folded into one "오래된 일 n개 보기" line. */
    internal val OLD_DAYS=14L
    internal fun isOld(i:OpenItem)=CallRecordStore.kstDay(i.createdMs)<CallUi.today().minusDays(OLD_DAYS)
    private fun taskView(item:OpenItem,check:Boolean)=CallUi.taskRow(this,item.title,item.createdMs,item.company,item.counterpart,if(check) "" else item.status,
        onDone={ row -> TaskActions.complete(this,item,row) { load() } },onOpen={ TaskActions.openSource(this,item) },aiNote=item.aiNote)
        .apply { tag="task_${item.id}" }
    /** A folded line that opens in place: [label] is a quiet link, the rows below it appear when it is tapped. */
    private fun fold(label:String,tag:String,rows:()->List<View>):View {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val holder=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE;this.tag=tag+"_rows" }
        var built=false
        box.addView(CallUi.linkButton(this,label) {
            if(!built) { built=true;holder.addView(CallUi.list(this,rows(),inset=16)) }
            holder.visibility=if(holder.visibility==View.GONE) View.VISIBLE else View.GONE
        }.apply { this.tag=tag },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(this@CallTasksActivity,6) })
        box.addView(holder)
        return box
    }

    private fun render(ok:Boolean) {
        val keep=CallUi.scrollY(body)
        body.removeAllViews()
        CallUi.band(body,"체크할 일",if(ok) "${items.size}개 남았어요" else null)
        if(!ok) { body.addView(CallUi.text(this,"체크할 일을 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        if(items.isEmpty()) body.addView(CallUi.card(this).apply { addView(CallUi.text(this@CallTasksActivity,"체크할 일이 없어요.",CallUi.SUMMARY,color=CallUi.OK)) })
        for((name,list) in groups(items)) {
            val check=name==CHECK_GROUP
            // The oldest requests fold away so the list starts with what is recent.
            val old=if(name==LATER_GROUP) list.filter(::isOld) else emptyList()
            val shown=list-old.toSet()
            body.addView(CallUi.section(this,name,"${list.size}개"))
            if(shown.isNotEmpty()) body.addView(CallUi.list(this,shown.map { taskView(it,check) },inset=16))
            if(old.isNotEmpty()) body.addView(fold("오래된 일 ${old.size}개 보기 ›","tasks_old") { old.map { taskView(it,false) } })
        }
        // Finished in the last 7 days (to look at, not to change), and the user's own items.
        if(done.isNotEmpty()) {
            body.addView(CallUi.gap(this,16))
            body.addView(fold("끝낸 일 보기 (7일) ›","tasks_done") { done.map { d ->
                CallUi.taskRow(this,d.title,d.createdMs,d.company,d.counterpart).apply { tag="done_${d.id}";alpha=0.6f } } })
        }
        body.addView(CallUi.button(this,"+ 직접 적기") { addOwn() }.apply { tag="task_add" },LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(this@CallTasksActivity,16) })
        CallUi.restoreScroll(body,keep)
    }

    /** "직접 적기": a title and a company from the list (or none); saved as the user's own check item. */
    private fun addOwn() {
        val title=EditText(this).apply { hint="무엇을 해야 하나요";textSize=CallUi.BODY;isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT;minHeight=CallUi.dp(this@CallTasksActivity,56);tag="add_title" }
        val names=listOf("업체 없음")+companies
        val pick=Spinner(this).apply { adapter=ArrayAdapter(this@CallTasksActivity,android.R.layout.simple_spinner_dropdown_item,names);tag="add_company";minimumHeight=CallUi.dp(this@CallTasksActivity,56) }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;val pad=CallUi.dp(this@CallTasksActivity,20);setPadding(pad,pad/2,pad,0);addView(title);addView(pick) }
        android.app.AlertDialog.Builder(this).setTitle("체크할 일 직접 적기").setView(box).setNegativeButton("취소",null)
            .setPositiveButton("저장") { _,_ -> save(title.text.toString(),names.getOrNull(pick.selectedItemPosition)?.takeIf { pick.selectedItemPosition>0 }) }.show()
    }
    internal fun save(title:String,company:String?) {
        if(title.isBlank()) { Toast.makeText(this,"적을 내용을 넣어 주세요.",Toast.LENGTH_SHORT).show();return }
        lifecycleScope.launch {
            val id=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallTasksActivity)).use { it.addUserItem(title,company) } }.getOrNull() }
            if(isFinishing||isDestroyed)return@launch
            Toast.makeText(this@CallTasksActivity,if(id!=null) "체크할 일에 넣었어요" else "저장하지 못했어요",Toast.LENGTH_SHORT).show()
            load()
        }
    }
}
