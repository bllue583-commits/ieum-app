package kr.callbrief.pipeline

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ways into the person page from the other screens (v2.8 r9-3): the badge and the name of a call row (the rest of the row
 * still opens the call). */
internal object PersonLinks {
    /** Makes the badge and the name of a [CallUi.memoRow] open [personId]'s page; nothing changes without a person. */
    fun onWho(a:MainActivity,row:View,personId:String?,name:String?):View {
        if(personId==null) return row
        val group=row as? LinearLayout ?: return row
        val spoken="${name?.takeIf { it.isNotBlank() } ?: "이 사람"} 님 보기"
        val badge=group.getChildAt(0)
        val who=group.findViewWithTag<TextView>("memo_who")
        listOfNotNull(badge,who).forEach { v ->
            v.isClickable=true;v.isFocusable=true;v.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            v.contentDescription=spoken;CallUi.onTap(v) { a.openPerson(personId) }
        }
        badge?.tag="memo_person";who?.foreground=CallUi.rowRipple()
        return row
    }
    /** One person as a row (company people, call search): avatar, name, role or company under it; the row opens their page. */
    fun row(a:MainActivity,p:PersonRow,under:String?,end:View?=null)=LinearLayout(a).apply {
        tag="person_${p.id}";orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        setPadding(CallUi.dp(a,16),CallUi.dp(a,12),CallUi.dp(a,16),CallUi.dp(a,12));minimumHeight=CallUi.dp(a,64)
        isClickable=true;isFocusable=true;background=CallUi.rowRipple();contentDescription="${p.name} 님 보기";CallUi.onTap(this) { a.openPerson(p.id) }
        addView(CallUi.avatar(a,p.name))
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        words.addView(CallUi.text(a,p.name,CallUi.BODY,true).apply { setPadding(0,0,0,0) })
        under?.takeIf { it.isNotEmpty() }?.let { words.addView(CallUi.caption(a,it).apply { setPadding(0,0,0,0) }) }
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        end?.let { addView(it) }
    }
}

/** "사람" (v2.8 r9-3): one person, opened from a name anywhere. The title is the name, under it the role and company; on the right
 * the round [전화 걸기] [문자 보내기] (the number itself is never drawn: the dialer and the messages app show it). Then the last
 * call, the open check items with [다 됐어요], the threads going on, the latest calls and texts, and at the end two quiet links:
 * [이름 고치기] and [이 번호는 개인으로 두기]. A number several people answer (an office line) says so under the title, with a chip
 * per person to move between them. There is no tab: back goes to the screen the page was opened from. */
internal class PersonScreen(a:MainActivity):AppScreen(a) {
    companion object {
        /** "탄탄이엔씨 대표", "우리 직원 공무"; null when nothing is known. */
        fun metaLine(c:CallRecordStore.PersonCard):String? =
            (if(c.isStaff) listOfNotNull("우리 직원",c.role) else listOfNotNull(c.company,c.role)).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotEmpty() }
        /** "이 번호를 쓰는 사람: 박영희 대리 외 1명": the others first (this person is on the page already). */
        fun sharedLine(id:String,people:List<PersonRow>):String? {
            if(people.size<2) return null
            val first=people.firstOrNull { it.id!=id } ?: people.first()
            return "이 번호를 쓰는 사람: "+listOfNotNull(first.name,first.role).joinToString(" ")+" 외 ${people.size-1}명"
        }
        /** "11월 4일 (화) 오후 2:10, 3분 12초, 받은 전화". */
        fun lastCallLine(l:CallRecordStore.LastCall):String =
            listOfNotNull(CallUi.dateTime(l.atMs),CallUi.duration(l.durationMs).takeIf { l.durationMs>0 },l.direction).joinToString(", ")
        /** The messages app with [number] as the one to write to (no permission needed). */
        fun smsIntent(number:String)=Intent(Intent.ACTION_SENDTO,android.net.Uri.fromParts("smsto",number,null))
        fun text(a:Context,number:String) { runCatching { a.startActivity(smsIntent(number)) } }
        /** Calls, texts and mail shown under "최근 통화와 문자". */
        const val RECENT=10
    }
    private val body:LinearLayout get()=a.column
    /** The person shown, or null before one is opened. */
    var personId:String?=null
        private set
    override val detail:String? get()=personId
    private var card:CallRecordStore.PersonCard?=null
    private var failed=false
    private var missing=false

    /** Which person to show next ([MainActivity.openPerson]). */
    fun show(id:String) { if(id!=personId) { card=null;failed=false;missing=false };personId=id }
    override fun enter() {
        if(card!=null && card?.id==personId && !failed) render()
        else { body.removeAllViews();CallUi.band(body,"사람",back={ a.back() });CallUi.loading(body) }
        load()
    }
    override fun save(out:Bundle) { personId?.let { out.putString("person_id",it) } }
    override fun restore(saved:Bundle) { personId=saved.getString("person_id") }

    private fun load() {
        val id=personId ?: return
        a.lifecycleScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a)).personCard(id) } }
            if(!live() || id!=personId) return@launch
            failed=result.isFailure;missing=result.getOrNull()==null && !failed
            result.getOrNull()?.let { card=it }
            render()
        }
    }

    private fun render() {
        val keep=CallUi.scrollY(body)
        body.removeAllViews()
        val c=card
        if(failed || missing || c==null) {
            CallUi.band(body,"사람",back={ a.back() })
            if(missing) body.addView(CallUi.card(a).apply { tag="person_missing";addView(CallUi.text(a,"이 사람을 찾지 못했어요. 이름이 합쳐졌을 수 있어요.",CallUi.BODY,color=CallUi.SUBTLE)) })
            else CallUi.retryCard(body,"사람 정보를 읽지 못했어요. 다시 불러와 주세요.") { failed=false;enter() }
            return
        }
        // 1. The head: name, role and company, the two round buttons.
        val buttons=c.number?.let { n -> LinearLayout(a).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="person_actions"
            addView(CallUi.iconButton(a,"dial","${c.name} 님께 전화 걸기",44,22,CallUi.ACCENT,CallUi.ACCENT_SOFT) { CallUi.dial(a,n) }.apply { tag="person_call" })
            addView(CallUi.iconButton(a,"sms","${c.name} 님께 문자 보내기",44,22,CallUi.ACCENT,CallUi.ACCENT_SOFT) { text(a,n) }.apply { tag="person_text" },
                LinearLayout.LayoutParams(CallUi.dp(a,44),CallUi.dp(a,44)).apply { marginStart=CallUi.dp(a,10) })
        } }
        CallUi.band(body,c.name,metaLine(c),back={ a.back() },trailing=buttons)
        // A shared number: who else answers it, a chip per person.
        sharedLine(c.id,c.sharers)?.let { line ->
            body.addView(CallUi.caption(a,line).apply { tag="person_shared";setPadding(CallUi.dp(a,4),0,0,CallUi.dp(a,4)) })
            body.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled=false
                addView(LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;tag="person_chips"
                    c.sharers.forEach { p -> addView(chip(p,p.id==c.id),LinearLayout.LayoutParams(-2,-2).apply { marginEnd=CallUi.dp(a,8) }) } })
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,4) })
        }
        // 2. The last call.
        body.addView(CallUi.section(a,"마지막 통화").apply { tag="person_section_last" })
        body.addView(CallUi.card(a).apply { tag="person_last"
            addView(CallUi.text(a,c.lastCall?.let(::lastCallLine) ?: "아직 통화 기록이 없어요",CallUi.BODY,color=if(c.lastCall==null) CallUi.SUBTLE else CallUi.INK)) })
        // 3. Open check items, all of them, each with [다 됐어요].
        body.addView(CallUi.section(a,"체크할 일 (${c.tasks.size})").apply { tag="person_section_tasks" })
        if(c.tasks.isEmpty()) body.addView(CallUi.text(a,"열린 체크할 일이 없어요.",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="person_tasks_none" })
        else body.addView(CallUi.list(a,c.tasks.map { t ->
            // Every item here is this person's: the META line says only when it was asked (never their number).
            CheckRows.row(a,t.copy(counterpart=null),status=if(t.status=="needs_confirmation") "" else t.status,onDone={ row -> TaskActions.complete(a,t,row) { load() } },
                onWrong={ row -> TaskActions.wrong(a,t,row) { load() } }).apply { tag="person_task_${t.id}" }
        },inset=16).apply { tag="person_tasks" })
        // 4. The threads going on with them or their company.
        body.addView(CallUi.section(a,"진행 중인 일").apply { tag="person_section_issues" })
        if(c.issues.isEmpty()) body.addView(CallUi.text(a,"진행 중인 일이 없어요.",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="person_issues_none" })
        else body.addView(CallUi.card(a).apply { tag="person_issues"
            c.issues.forEachIndexed { i,x ->
                if(i>0) addView(CallUi.divider(a))
                addView(CallUi.text(a,x.title,CallUi.BODY,true))
                if(x.status.isNotBlank()) addView(CallUi.caption(a,x.status))
            } })
        // 5. The latest calls, texts and mail; a row opens that call.
        body.addView(CallUi.section(a,"최근 통화와 문자").apply { tag="person_section_recent" })
        if(c.recent.isEmpty()) body.addView(CallUi.text(a,"아직 통화나 문자가 없어요.",CallUi.BODY,color=CallUi.SUBTLE).apply { tag="person_recent_none" })
        else body.addView(CallUi.list(a,c.recent.take(RECENT).map { r ->
            CallUi.memoRow(a,"${CallUi.dayWord(CallRecordStore.kstDay(r.dayMs))} ${CallUi.time(r.startedMs ?: r.createdMs)}",r.kind,
                r.summary?.let(CallUi::firstSentence) ?: CallsScreen.stateWords(r),r.summary==null,avatarName=r.kind) { a.openCall(r.recordId,r.dayMs) }
                .apply { tag="person_recent_${r.recordId}" }
        }).apply { tag="person_recent" })
        // 6. The two quiet links.
        body.addView(CallUi.gap(a,16))
        body.addView(CallUi.linkButton(a,"이름 고치기") { rename(c) }.apply { tag="person_rename";contentDescription="${c.name} 이름 고치기" },
            LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        if(c.number!=null) {
            if(c.personal) body.addView(CallUi.caption(a,"개인으로 둔 번호예요. 설정에서 되돌릴 수 있어요.").apply { tag="person_personal_done";setPadding(CallUi.dp(a,4),CallUi.dp(a,8),0,0) })
            else body.addView(CallUi.linkButton(a,"이 번호는 개인으로 두기") { askPersonal(c) }.apply { tag="person_personal" },
                LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        }
        a.putBack(keep)
    }

    /** One person of a shared number: the one on the page is marked, another opens in its place. */
    private fun chip(p:PersonRow,on:Boolean)=TextView(a).apply {
        text=listOfNotNull(p.name,p.role).joinToString(" ");textSize=CallUi.CAPTION;typeface=CallUi.font(a,if(on) CallUi.Weight.SEMIBOLD else CallUi.Weight.REGULAR)
        setTag(kr.callbrief.R.id.ui_min_sp,CallUi.CAPTION)
        setTextColor(if(on) CallUi.ACCENT else CallUi.INK);gravity=Gravity.CENTER;minHeight=CallUi.dp(a,44);setPadding(CallUi.dp(a,14),0,CallUi.dp(a,14),0)
        background=CallUi.ripple(a,CallUi.sheet(a,if(on) CallUi.ACCENT_SOFT else CallUi.CHIP,radius=22));tag="person_chip_${p.id}"
        contentDescription=if(on) "${p.name}, 지금 보는 사람" else "${p.name} 님 보기";isClickable=true;isFocusable=true
        if(!on) CallUi.onTap(this) { a.openPerson(p.id) }
    }

    /** Speech recognition mishears names; the fix shows everywhere and the old spelling keeps finding this person. */
    private fun rename(c:CallRecordStore.PersonCard)=NameSheet.show(a,"이름 고치기",c.name,"person_name_input","이름을 비워둘 수 없어요") { name ->
        a.lifecycleScope.launch {
            val ok=runCatching { withContext(Dispatchers.IO) { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a)).renamePerson(c.id,name) } }.getOrDefault(false)
            if(!live()) return@launch
            Toast.makeText(a,if(ok) "이름을 고쳤어요" else "이름을 고치지 못했어요",Toast.LENGTH_SHORT).show()
            load()
        }
    }

    /** "이 번호는 개인으로 두기": after a yes, the number goes to personal_numbers; its later calls and texts stay on the phone and
     * never go up (settings "개인으로 둔 번호" takes it back). */
    private fun askPersonal(c:CallRecordStore.PersonCard) {
        val key=c.number ?: return
        CallUi.sheetDialog(a,"이 번호는 개인으로 둘까요?",message="앞으로 이 번호의 통화와 문자는 정리하지 않고 휴대폰에만 둬요. 설정의 \"개인으로 둔 번호\"에서 되돌릴 수 있어요.",
            primary="개인으로 두기",secondary="취소",onPrimary={ keepPersonal(key) })
    }
    internal fun keepPersonal(key:String) {
        a.lifecycleScope.launch {
            val ok=runCatching { withContext(Dispatchers.IO) { kr.callbrief.IeumApplication.store(CallPipelineStartup.storage(a)).rememberPersonal(key) } }.isSuccess
            if(!live()) return@launch
            Toast.makeText(a,if(ok) "이 번호는 개인으로 둘게요" else "개인으로 두지 못했어요",Toast.LENGTH_SHORT).show()
            load()
        }
    }
}
