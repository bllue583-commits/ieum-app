package kr.callbrief.pipeline

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kr.callbrief.pipeline.report.MorningBoard
import java.time.LocalDate

/** v4.4 r25-2: the morning tidy's board on home, under "먼저 볼 체크할 일", read from today's report as the phone fetched it at
 * 05:30 ([kr.callbrief.pipeline.report.ReportFetch.REPORT_BODY]); an empty block is not drawn, no report draws nothing.
 * r25 review: a small "오늘 아침 보고 기준" over it; open by default only 전화할 곳, 답을 기다리는 중 and 이번 주 일정 (today and
 * tomorrow), three rows each (the rest of a block opens in place); 진행 중인 일, 확인 필요, 끝났는지 확인할 일 and 어제와 달라진 것
 * wait behind one "아침 현황 더 보기" at the end. No new screen, no new tab. */
internal object HomeBoard {
    /** Rows shown before "외 n개 더 보기". */
    const val FIRST_ROWS=3
    /** 이번 주 일정: the days shown before the fold (today and tomorrow come first). */
    const val FIRST_DAYS=2
    const val TAG_PREFIX="home_board_"
    const val BASIS="오늘 아침 보고 기준"
    const val MORE="아침 현황 더 보기"

    /** Adds the board's blocks to [body]; [opened] keeps the folds that were opened (it outlives one drawing). */
    fun add(a:MainActivity,body:LinearLayout,b:MorningBoard.Board,day:LocalDate,opened:MutableSet<String>) {
        body.addView(CallUi.meta(a,BASIS,CallUi.PAGE_CAPTION).apply { tag=TAG_PREFIX+"basis";setPadding(CallUi.dp(a,4),CallUi.dp(a,20),0,0) })
        block(a,body,"calls","전화할 곳",b.calls.map { p -> { person(a,p,null) } },opened)
        block(a,body,"waiting","답을 기다리는 중",b.waiting.map { p -> { person(a,p,p.days) } },opened)
        val near=b.week.filter { !it.date.isAfter(day.plusDays(1)) }.take(FIRST_DAYS)
        if(near.isNotEmpty()) {
            body.addView(CallUi.section(a,"이번 주 일정").apply { tag=TAG_PREFIX+"week_head" })
            val sheet=CallUi.card(a).apply { tag=TAG_PREFIX+"week" }
            near.forEachIndexed { i,d -> sheet.addView(dayView(a,d,day,i==0)) }
            body.addView(sheet)
        }
        val later=b.week-near.toSet()
        val rest=b.inProgress.isNotEmpty() || b.check.isNotEmpty() || b.doneCheck.isNotEmpty() || b.changed.isNotEmpty() || later.isNotEmpty()
        if(!rest) return
        body.addView(fold(a,"$MORE ›",TAG_PREFIX+"more",opened) {
            val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
            if(later.isNotEmpty()) {
                box.addView(CallUi.section(a,if(near.isEmpty()) "이번 주 일정" else "이번 주 일정, 그 뒤").apply { tag=TAG_PREFIX+"later_head" })
                box.addView(CallUi.card(a).apply { tag=TAG_PREFIX+"later";later.forEachIndexed { i,d -> addView(dayView(a,d,day,i==0)) } })
            }
            block(a,box,"progress","진행 중인 일",b.inProgress.map { i -> { issue(a,i) } },opened)
            block(a,box,"check","확인 필요",b.check.map { c -> { line(a,c.what,c.who) } },opened)
            block(a,box,"done","끝났는지 확인할 일",b.doneCheck.map { c -> { line(a,c.what,c.who) } },opened)
            block(a,box,"changed","어제와 달라진 것",b.changed.map { c -> { line(a,c,"") } },opened)
            listOf(box)
        })
    }

    /** One block: the heading with its count, the first [FIRST_ROWS] rows in one sheet, the rest behind one quiet line. */
    private fun block(a:MainActivity,body:LinearLayout,key:String,title:String,rows:List<()->View>,opened:MutableSet<String>) {
        if(rows.isEmpty()) return
        body.addView(CallUi.section(a,title,if(rows.size>1) "${rows.size}개" else null).apply { tag=TAG_PREFIX+key+"_head" })
        val sheet=CallUi.list(a,rows.take(FIRST_ROWS).map { it() },inset=16).apply { tag=TAG_PREFIX+key }
        body.addView(sheet)
        val rest=rows.drop(FIRST_ROWS)
        if(rest.isNotEmpty()) body.addView(fold(a,"외 ${rest.size}개 더 보기 ›",TAG_PREFIX+key+"_more",opened) { listOf(CallUi.list(a,rest.map { it() },inset=16)) })
    }

    /** A line that opens the rows below it in place, and closes them again. */
    private fun fold(a:MainActivity,label:String,tag:String,opened:MutableSet<String>,rows:()->List<View>):View {
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val holder=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE;this.tag=tag+"_rows" }
        var built=false
        fun build() { if(!built) { built=true;rows().forEach(holder::addView) } }
        if(tag in opened) { build();holder.visibility=View.VISIBLE }
        box.addView(holder)
        box.addView(CallUi.linkButton(a,if(tag in opened) "접기" else label) {}.also { b ->
            b.tag=tag
            CallUi.onTap(b) {
                build()
                val open=holder.visibility!=View.VISIBLE
                holder.visibility=if(open) View.VISIBLE else View.GONE
                if(open) opened+=tag else opened-=tag
                b.text=if(open) "접기" else label
            }
        },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-CallUi.dp(a,6) })
        return box
    }

    private fun row(a:MainActivity)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP
        setPadding(CallUi.dp(a,16),CallUi.dp(a,14),CallUi.dp(a,16),CallUi.dp(a,14))
    }
    /** The main words (with "확인 필요" when the tidy was not sure of a number or a name), the META line under them, and
     * "n일째" on the right (with [warn], a wait: amber after [MorningBoard.LONG_WAIT_DAYS] days). */
    private fun twoLines(a:MainActivity,main:String,sub:String,days:Int?,unsure:Boolean,warn:Boolean=false):View = row(a).apply {
        val left=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        left.addView(CallUi.text(a,main,CallUi.SUMMARY,true).apply { tag="board_main" })
        if(unsure) left.addView(CallUi.pill(a,CallUi.UNSURE,CallUi.AMBER).apply { tag="board_unsure" })
        if(sub.isNotEmpty()) left.addView(CallUi.meta(a,sub).apply { tag="board_sub";setPadding(0,CallUi.dp(a,4),0,0) })
        addView(left,LinearLayout.LayoutParams(0,-2,1f))
        val n=MorningBoard.daysWords(days)
        if(n.isNotEmpty()) addView(CallUi.text(a,n,CallUi.ASIDE,true,if(warn && (days ?: 0)>MorningBoard.LONG_WAIT_DAYS) CallUi.AMBER else CallUi.SUBTLE).apply {
            tag="board_days";fontFeatureSettings="tnum";gravity=Gravity.END },LinearLayout.LayoutParams(-2,-2).apply { marginStart=CallUi.dp(a,12) })
        contentDescription=listOf(main,if(unsure) CallUi.UNSURE else "",sub,n).filter { it.isNotEmpty() }.joinToString(", ")
    }
    /** 전화할 곳 and 답을 기다리는 중: who first ("가나건설 박하늘 과장"), then what and when. */
    private fun person(a:MainActivity,p:MorningBoard.Person,days:Int?):View {
        val main=p.name.ifEmpty { p.what }
        val when_=if(p.since.isEmpty()) "" else if(days==null) "마지막 연락 ${p.since}" else "${p.since} 약속"
        return twoLines(a,main,listOf(if(p.name.isEmpty()) "" else p.what,when_).filter { it.isNotEmpty() }.joinToString(", "),days,p.unsure,warn=days!=null)
    }
    private fun issue(a:MainActivity,i:MorningBoard.Issue)=twoLines(a,i.what,i.status,i.days,i.unsure)
    private fun line(a:MainActivity,what:String,who:String)=twoLines(a,what,who,null,false)
    /** One day of 이번 주 일정: "오늘 11/22(일)" (오늘 and 내일 in the accent), then its lines. */
    private fun dayView(a:MainActivity,d:MorningBoard.Day,day:LocalDate,first:Boolean)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL;tag="board_day_${d.date}"
        if(!first) setPadding(0,CallUi.dp(a,14),0,0)
        val near=MorningBoard.near(d.date,day)
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        if(near.isNotEmpty()) head.addView(CallUi.text(a,near,CallUi.BODY,true,CallUi.ACCENT),LinearLayout.LayoutParams(-2,-2).apply { marginEnd=CallUi.dp(a,8) })
        head.addView(CallUi.text(a,kr.callbrief.pipeline.report.KakaoReport.dayLabel(d.date),CallUi.BODY,near.isEmpty(),if(near.isEmpty()) CallUi.INK else CallUi.SUBTLE))
        addView(head)
        d.items.forEach { addView(CallUi.text(a,it,CallUi.BODY).apply { tag="board_day_item";setPadding(CallUi.dp(a,12),CallUi.dp(a,4),0,0) }) }
        contentDescription=(listOf(near,kr.callbrief.pipeline.report.KakaoReport.dayLabel(d.date)).filter { it.isNotEmpty() }+d.items).joinToString(", ")
    }
}
