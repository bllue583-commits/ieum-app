package kr.callbrief.pipeline

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kr.callbrief.pipeline.report.MorningBoard
import kr.callbrief.pipeline.report.Weather
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
        if(!b.isEmpty) body.addView(CallUi.meta(a,BASIS,CallUi.PAGE_CAPTION).apply { tag=TAG_PREFIX+"basis";setPadding(CallUi.dp(a,4),CallUi.dp(a,20),0,0) })
        block(a,body,"calls","전화할 곳",b.calls.map { p -> { person(a,p,null) } },opened)
        block(a,body,"waiting","답을 기다리는 중",b.waiting.map { p -> { person(a,p,p.days) } },opened)
        val near=b.week.filter { !it.date.isAfter(day.plusDays(1)) }.take(FIRST_DAYS)
        // v4.5 r26-2: today's and tomorrow's weather heads the block even on a day without a schedule (site work starts there).
        val wx=b.weather
        val nearLine=Weather.nearLine(wx,day)
        val weekShown=near.isNotEmpty() || nearLine!=null
        if(weekShown) {
            body.addView(CallUi.section(a,"이번 주 일정").apply { tag=TAG_PREFIX+"week_head" })
            val sheet=CallUi.card(a).apply { tag=TAG_PREFIX+"week" }
            nearLine?.let { sheet.addView(CallUi.text(a,it,CallUi.BODY,false,CallUi.SUBTLE).apply { tag=TAG_PREFIX+"weather"
                if(near.isNotEmpty()) setPadding(0,0,0,CallUi.dp(a,14)) }) }
            near.forEachIndexed { i,d -> sheet.addView(dayView(a,d,day,i==0,wx)) }
            body.addView(sheet)
            if(wx!=null) source(a,body,wx,TAG_PREFIX+"week_source",near.any { Weather.hasCold(wx,it) })
        }
        val later=b.week-near.toSet()
        val rest=b.inProgress.isNotEmpty() || b.check.isNotEmpty() || b.doneCheck.isNotEmpty() || b.changed.isNotEmpty() || later.isNotEmpty()
        if(!rest) return
        body.addView(fold(a,"$MORE ›",TAG_PREFIX+"more",opened) {
            val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
            if(later.isNotEmpty()) {
                box.addView(CallUi.section(a,if(!weekShown) "이번 주 일정" else "이번 주 일정, 그 뒤").apply { tag=TAG_PREFIX+"later_head" })
                box.addView(CallUi.card(a).apply { tag=TAG_PREFIX+"later";later.forEachIndexed { i,d -> addView(dayView(a,d,day,i==0,wx)) } })
                if(wx!=null && later.any { wx.day(it.date)!=null })
                    source(a,box,wx,TAG_PREFIX+"later_source",later.any { Weather.hasCold(wx,it) } && near.none { Weather.hasCold(wx,it) })
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
    /** "날씨 Open-Meteo, 인천 기준" under a block that shows the forecast (CC BY 4.0). */
    private fun source(a:MainActivity,into:LinearLayout,wx:Weather.Cast,tag:String,cold:Boolean) {
        into.addView(CallUi.meta(a,Weather.source(wx.site),CallUi.PAGE_CAPTION).apply { this.tag=tag;setPadding(CallUi.dp(a,4),CallUi.dp(a,6),0,0) })
        // r26-3: the cold criterion's words once, on a block with a cold line (the lines say only "한중 콘크리트 기준 해당").
        if(cold) into.addView(CallUi.meta(a,Weather.COLD_RULE,CallUi.PAGE_CAPTION).apply { this.tag=tag+"_rule";setPadding(CallUi.dp(a,4),CallUi.dp(a,2),0,0) })
    }
    /** One day of 이번 주 일정: "오늘 11/22(일)" (오늘 and 내일 in the accent) and v4.5 its weather ("비 70%  -1°/6°"), then its
     * lines, then at most two caution lines of forecast facts ([Weather.cautions]) in amber. */
    private fun dayView(a:MainActivity,d:MorningBoard.Day,day:LocalDate,first:Boolean,wx:Weather.Cast?=null)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL;tag="board_day_${d.date}"
        if(!first) setPadding(0,CallUi.dp(a,14),0,0)
        val near=MorningBoard.near(d.date,day)
        val date=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val head=WrapRow(a).apply { addView(date) }
        if(near.isNotEmpty()) date.addView(CallUi.text(a,near,CallUi.BODY,true,CallUi.ACCENT),LinearLayout.LayoutParams(-2,-2).apply { marginEnd=CallUi.dp(a,8) })
        date.addView(CallUi.text(a,kr.callbrief.pipeline.report.KakaoReport.dayLabel(d.date),CallUi.BODY,near.isEmpty(),if(near.isEmpty()) CallUi.INK else CallUi.SUBTLE))
        val w=wx?.day(d.date)
        val weather=w?.let(Weather::headLine)
        // r26-3: one line always; beside the date when both fit, else under it (large letters, a narrow phone).
        weather?.let { head.addView(CallUi.text(a,it,CallUi.BODY,false,CallUi.SUBTLE).apply { tag="board_day_weather";fontFeatureSettings="tnum";maxLines=1 },
            LinearLayout.LayoutParams(-2,-2).apply { marginStart=CallUi.dp(a,10) }) }
        addView(head)
        d.items.forEach { addView(CallUi.text(a,it,CallUi.BODY).apply { tag="board_day_item";setPadding(CallUi.dp(a,12),CallUi.dp(a,4),0,0) }) }
        val cautions=if(wx==null) emptyList() else Weather.cautionsOf(wx,d)
        cautions.forEach { addView(CallUi.meta(a,it,CallUi.AMBER).apply { tag="board_day_caution";setPadding(CallUi.dp(a,12),CallUi.dp(a,6),0,0) }) }
        contentDescription=(listOf(near,kr.callbrief.pipeline.report.KakaoReport.dayLabel(d.date),weather ?: "").filter { it.isNotEmpty() }+d.items+cautions).joinToString(", ")
    }
}

/** r26-3: a row that becomes a column when its children do not fit side by side in one line each (the date head with its
 * weather at large letter sizes); in a column the later children lose their start margin. */
internal class WrapRow(context:android.content.Context):LinearLayout(context) {
    init { orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val avail=View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
        var need=0
        val free=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        for(i in 0 until childCount) { val c=getChildAt(i);if(c.visibility==View.GONE) continue
            c.measure(free,free);val lp=c.layoutParams as LayoutParams;need+=c.measuredWidth+lp.marginStart+lp.marginEnd }
        val column=View.MeasureSpec.getMode(widthMeasureSpec)!=View.MeasureSpec.UNSPECIFIED && need>avail
        val want=if(column) VERTICAL else HORIZONTAL
        if(orientation!=want) {
            orientation=want
            gravity=if(column) Gravity.START else Gravity.CENTER_VERTICAL
            for(i in 1 until childCount) (getChildAt(i).layoutParams as LayoutParams).apply {
                marginStart=if(column) 0 else (10*resources.displayMetrics.density).toInt();topMargin=if(column) (2*resources.displayMetrics.density).toInt() else 0 }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
