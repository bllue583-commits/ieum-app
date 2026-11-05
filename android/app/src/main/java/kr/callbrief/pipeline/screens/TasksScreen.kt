package kr.callbrief.pipeline

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The check row of the check list and the company page (design G3, r8-5): the company badge, the title, the META line
 * ("김민수 님, 오늘 요청"), the AI's note, then [다 됐어요] at the end. Only an item a call made opens anything: the whole row and the
 * person's name in the META line open that call. An item the user wrote or the morning tidy made shows no press at all. */
internal object CheckRows {
    /** [personId]: the person of the call that made the item (v2.8): the name in the META line then opens the person page
     * instead of the call (the rest of the row still opens the call). */
    fun row(a:MainActivity,item:OpenItem,status:String=item.status,company:String?=item.company,onDone:((View)->Unit)?=null,personId:String?=null):LinearLayout {
        val open:(()->Unit)?=item.recordId?.let { id -> { a.openCall(id,item.callMs ?: item.createdMs) } }
        val openWho:(()->Unit)?=personId?.let { id -> { a.openPerson(id) } } ?: open
        val outer=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(CallUi.dp(a,16),CallUi.dp(a,14),CallUi.dp(a,16),CallUi.dp(a,14)) }
        if(open!=null) outer.apply { isClickable=true;isFocusable=true;background=CallUi.rowRipple();CallUi.onTap(this,action=open) }
        else outer.apply { isClickable=false;isFocusable=false;background=null }
        // 1. The company badge, and the state ("확인 필요", "진행 중") beside it.
        val pill=CallUi.stateWords(status).takeIf { it.isNotEmpty() }?.let { CallUi.pill(a,it,CallUi.ACCENT) }
        // v3.3: "확인 필요" when a number or date may have been misheard (or the item was only loosely asked for).
        val unsure=if(item.tentative) CallUi.unsureBadge(a) else null
        if(!company.isNullOrBlank() || pill!=null || unsure!=null) outer.addView(LinearLayout(a).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="task_badges"
            if(!company.isNullOrBlank()) addView(CallUi.chip(a,company).apply { tag="task_company" },LinearLayout.LayoutParams(-2,-2).apply { marginEnd=CallUi.dp(a,8) })
            unsure?.let { addView(it,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=CallUi.dp(a,8) }) }
            pill?.let { addView(it,LinearLayout.LayoutParams(-2,-2)) }
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=CallUi.dp(a,8) })
        // 2. The title, without the "○○ 님께" the META line already says.
        val title=CallUi.shortTitle(item.title,item.counterpart)
        outer.addView(CallUi.text(a,title,CallUi.SUMMARY,true).apply { tag="task_title";setPadding(0,0,0,0) })
        // 3. META: who asked and when. The name is a link to that call when a call made the item.
        // A name written with its number shows the name only; a bare number (no name known) stays a number.
        val person=item.counterpart?.takeIf { it.isNotBlank() }?.let { CallRecordStore.noNumber(it) ?: it }?.let(CallUi::who)
        val asked=CallUi.requested(item.createdMs)
        if(person!=null || asked.isNotEmpty()) {
            val meta=CallUi.FitRow(a,flex=if(person!=null && asked.isNotEmpty()) 1 else 0,minFraction=0.3f,gapDp=0).apply { tag="task_meta" }
            if(person!=null) meta.addView(CallUi.meta(a,if(asked.isNotEmpty()) "$person," else person,if(openWho!=null) CallUi.ACCENT else CallUi.SUBTLE,openWho!=null).apply {
                tag=when { personId!=null -> "task_who_person";open!=null -> "task_who_link";else -> "task_who_name" };setPadding(0,0,CallUi.dp(a,4),0)
                if(openWho!=null) { isClickable=true;isFocusable=true;background=CallUi.rowRipple();minHeight=CallUi.dp(a,40);gravity=Gravity.CENTER_VERTICAL
                    contentDescription=if(personId!=null) "$person 보기" else "$person 통화 보기";CallUi.onTap(this,action=openWho) }
            },LinearLayout.LayoutParams(-2,-2))
            if(asked.isNotEmpty()) meta.addView(CallUi.meta(a,asked,CallUi.SUBTLE).apply { tag="task_who";setPadding(0,0,0,0);gravity=Gravity.CENTER_VERTICAL
                if(openWho!=null) minHeight=CallUi.dp(a,40) },LinearLayout.LayoutParams(-2,-2))
            outer.addView(meta,LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,if(openWho!=null) 0 else 6) })
        }
        // 4. The AI's note.
        CallUi.aiNoteRow(a,item.aiNote)?.let { outer.addView(it) }
        // 5. [다 됐어요] at the end of the row.
        onDone?.let { act -> outer.addView(Button(a).apply {
            text="다 됐어요";isAllCaps=false;stateListAnimator=null;textSize=17f;typeface=CallUi.font(a,CallUi.Weight.SEMIBOLD);setTextColor(CallUi.INK);tag="task_done"
            maxLines=1;isSingleLine=true;background=CallUi.ripple(a,CallUi.sheet(a,CallUi.CHIP,radius=14))
            setCompoundDrawablesWithIntrinsicBounds(CallUi.icon(a,"tick",CallUi.SUBTLE,20),null,null,null);compoundDrawablePadding=CallUi.dp(a,6)
            minHeight=CallUi.dp(a,48);minimumHeight=CallUi.dp(a,48);minWidth=0;minimumWidth=0;setPadding(CallUi.dp(a,12),0,CallUi.dp(a,14),0)
            contentDescription="${item.title}, 다 됐어요";CallUi.onTap(this) { act(outer) }
        },LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.END;topMargin=CallUi.dp(a,10) }) }
        outer.contentDescription=listOfNotNull(company,unsure?.let { CallUi.UNSURE },title,person,asked.takeIf { it.isNotEmpty() }).joinToString(", ")+(if(open!=null) ". 눌러서 통화 보기" else "")
        return outer
    }
}

/** "체크할 일": every open to-do, grouped by the day it was asked for, each with one "다 됐어요" button (and a 5 second undo).
 * Completion is always the user's explicit action (the AI only ever marks "확인 필요"). */
internal class TasksScreen(a:MainActivity):AppScreen(a) {
    private val body:LinearLayout get()=a.column
    private var items:List<OpenItem> =emptyList()
    private var done:List<OpenItem> =emptyList()
    private var companies:List<String> =emptyList()
    /** The list was read at least once (so it can be drawn at once when the screen comes back, and read again behind it). */
    private var loaded=false
    private companion object { const val CHECK_GROUP="끝났는지 확인해 주세요";const val LATER_GROUP="그전에 요청한 일" }
    /** Rows drawn at once; the rest follow in parts of this size (review #28: hundreds of open items). */
    internal val FIRST_ROWS=30

    /** The folded lines ("오래된 일", "끝낸 일") that are open; they stay open when the list is drawn again and across a restart. */
    private val opened=mutableSetOf<String>()

    override fun enter() {
        if(loaded) render(true) else { body.removeAllViews();CallUi.band(body,"체크할 일");CallUi.loading(body) }
        load()
    }
    /** The check tab tapped while it is in front: the list from the top, the folds closed. */
    override fun reselect() { opened.clear();if(loaded) render(true);load();CallUi.scrollTo(body,0) }
    override fun save(out:Bundle) { out.putStringArrayList("tasks_open",ArrayList(opened)) }
    override fun restore(saved:Bundle) { opened.clear();saved.getStringArrayList("tasks_open")?.let(opened::addAll) }

    private fun load() {
        a.lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a)).let { s ->
                val open=s.openItems()
                people=runCatching { s.personIdsOf(open.mapNotNull { it.recordId }) }.getOrDefault(emptyMap())
                Triple(open,runCatching { s.doneItems(System.currentTimeMillis()-7*86_400_000L) }.getOrDefault(emptyList()),
                    runCatching { s.companies().map { it.name } }.getOrDefault(emptyList())) } } }
            if(!live())return@launch
            result.onSuccess { (i,d,c) -> items=i;done=d;companies=c;loaded=true }
            render(result.isSuccess)
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
    /** The person of each item's call, by record id: "김민수 님" opens their page (v2.8). */
    @Volatile private var people:Map<String,String> =emptyMap()
    private fun taskView(item:OpenItem,check:Boolean)=CheckRows.row(a,item,status=if(check) "" else item.status,
        onDone={ row -> TaskActions.complete(a,item,row) { load() } },personId=item.recordId?.let(people::get)).apply { tag="task_${item.id}" }
    /** A folded line that opens in place: [label] is a quiet link, the rows below it appear when it is tapped. */
    private fun fold(label:String,tag:String,rows:()->List<View>):View {
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val holder=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE;this.tag=tag+"_rows" }
        var built=false
        fun build() { if(!built) { built=true;holder.addView(CallUi.list(a,rows(),inset=16)) } }
        if(tag in opened) { build();holder.visibility=View.VISIBLE }
        box.addView(CallUi.linkButton(a,label) {
            build()
            holder.visibility=if(holder.visibility==View.GONE) View.VISIBLE else View.GONE
            if(holder.visibility==View.VISIBLE) opened+=tag else opened-=tag
        }.apply { this.tag=tag },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        box.addView(holder)
        return box
    }

    /** Which drawing is the latest: parts of an older drawing still waiting to be added are dropped. */
    private var drawing=0
    private fun render(ok:Boolean) {
        val keep=CallUi.scrollY(body)
        body.removeAllViews();drawing++
        if(!ok && !loaded) { CallUi.band(body,"체크할 일");CallUi.retryCard(body,"체크할 일을 읽지 못했어요. 다시 불러와 주세요.") { enter() };return }
        if(!ok) Toast.makeText(a,"새로 읽지 못해서 전에 읽은 목록을 보여드려요.",Toast.LENGTH_SHORT).show()
        CallUi.band(body,"체크할 일","${items.size}개 남았어요")
        if(items.isEmpty()) body.addView(CallUi.card(a).apply { addView(CallUi.text(a,"체크할 일이 없어요.",CallUi.SUMMARY,color=CallUi.OK)) })
        // The first rows are drawn now; the rest of every group follows a part at a time, in the same sheets.
        val later=ArrayList<Triple<LinearLayout,Boolean,List<OpenItem>>>()
        var budget=FIRST_ROWS
        for((name,list) in groups(items)) {
            val check=name==CHECK_GROUP
            // The oldest requests fold away so the list starts with what is recent.
            val old=if(name==LATER_GROUP) list.filter(::isOld) else emptyList()
            val shown=list-old.toSet()
            body.addView(CallUi.section(a,name,"${list.size}개"))
            if(shown.isNotEmpty()) {
                val now=shown.take(budget.coerceAtLeast(0));budget-=now.size
                val sheet=CallUi.list(a,now.map { taskView(it,check) },inset=16).apply { this.tag="tasks_list_$name" }
                body.addView(sheet)
                if(now.size<shown.size) later+=Triple(sheet,check,shown.drop(now.size))
            }
            if(old.isNotEmpty()) body.addView(fold("오래된 일 ${old.size}개 보기 ›","tasks_old") { old.map { taskView(it,false) } })
        }
        // Finished in the last 7 days (to look at, not to change), and the user's own items.
        if(done.isNotEmpty()) {
            body.addView(CallUi.gap(a,16))
            body.addView(fold("끝낸 일 보기 (7일) ›","tasks_done") { done.map { d -> CheckRows.row(a,d,status="").apply { tag="done_${d.id}";alpha=0.6f } } })
        }
        body.addView(CallUi.button(a,"+ 직접 적기") { addOwn() }.apply { tag="task_add" },LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,16) })
        if(later.isNotEmpty()) drawLater(drawing,later)
        a.putBack(keep)
    }
    /** Adds the rows that were held back, [FIRST_ROWS] per frame, while the same drawing is on the screen. */
    private fun drawLater(gen:Int,parts:List<Triple<LinearLayout,Boolean,List<OpenItem>>>) {
        val queue=ArrayDeque(parts.flatMap { (sheet,check,rows) -> rows.map { Triple(sheet,check,it) } })
        fun next() {
            if(gen!=drawing || !live()) return
            repeat(FIRST_ROWS) { val (sheet,check,item)=queue.removeFirstOrNull() ?: return@repeat;CallUi.addRows(a,sheet,listOf(taskView(item,check)),inset=16) }
            if(queue.isNotEmpty()) body.post { next() }
        }
        body.post { next() }
    }

    /** "직접 적기": a title and a company from the list (or none); saved as the user's own check item. An empty title keeps
     * the sheet open with a word about it (review #26). */
    private fun addOwn() {
        val title=CallUi.sheetInput(a,hint="무엇을 해야 하나요",tag="add_title")
        val names=listOf("업체 없음")+companies
        // The company list in the same big type as the rest of the sheet (the stock spinner rows are small).
        val big=object:ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,names) {
            private fun fit(v:View)=v.apply { (this as? TextView)?.let { t -> t.textSize=CallUi.BODY;t.setTextColor(CallUi.INK);t.typeface=CallUi.font(context,CallUi.Weight.REGULAR)
                t.minHeight=CallUi.dp(a,56);t.gravity=android.view.Gravity.CENTER_VERTICAL
                t.setPaddingRelative(CallUi.dp(a,12),0,CallUi.dp(a,12),0) } }
            // The closed list shows a chevron on the right so it reads as "press to choose".
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup)=fit(super.getView(position,convertView,parent)).apply {
                (this as? TextView)?.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,CallUi.icon(a,"chevron",CallUi.SUBTLE,22),null) }
            override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup)=fit(super.getDropDownView(position,convertView,parent)).apply { setBackgroundColor(CallUi.CARD) }
        }
        val pick=Spinner(a).apply { adapter=big;tag="add_company";minimumHeight=CallUi.dp(a,56);background=CallUi.sheet(a,CallUi.CHIP,radius=14)
            setPopupBackgroundDrawable(CallUi.sheet(a,CallUi.CARD,radius=14));setPadding(CallUi.dp(a,4),0,CallUi.dp(a,4),0) }
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL
            addView(title,LinearLayout.LayoutParams(-1,-2))
            addView(CallUi.text(a,"업체",CallUi.ASIDE,color=CallUi.SUBTLE).apply { setPadding(CallUi.dp(a,4),CallUi.dp(a,14),0,CallUi.dp(a,6)) })
            addView(pick,LinearLayout.LayoutParams(-1,-2)) }
        CallUi.sheetDialog(a,"체크할 일 직접 적기",body=box,primary="저장",
            validate={ if(title.text.isNullOrBlank()) "무엇을 할지 적어 주세요" else null },
            onPrimary={ save(title.text.toString(),names.getOrNull(pick.selectedItemPosition)?.takeIf { pick.selectedItemPosition>0 }) },secondary="취소")
    }
    internal fun save(title:String,company:String?) {
        if(title.isBlank()) { Toast.makeText(a,"적을 내용을 넣어 주세요.",Toast.LENGTH_SHORT).show();return }
        a.lifecycleScope.launch {
            val id=withContext(Dispatchers.IO) { runCatching { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a)).addUserItem(title,company) }.getOrNull() }
            if(!live())return@launch
            Toast.makeText(a,if(id!=null) "체크할 일에 넣었어요" else "저장하지 못했어요",Toast.LENGTH_SHORT).show()
            load()
        }
    }
}
