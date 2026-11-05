package kr.callbrief.pipeline.call

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kr.callbrief.core.PrivacyRules
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.CallUi
import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.ContactCards
import java.time.Instant
import kotlin.math.abs

/** The 이음 call card drawn on top of the phone's call screen (design v3.0 7.1, r11-2), built in code with the [CallUi]
 * tokens and following the phone's night setting. Two looks:
 *  - open: a handle to drag it, the name and role (20 bold) with [접기] [닫기] on the right, company and trade, "마지막 통화 …",
 *    "지난 통화" and the summary's first sentence (19), up to three "체크할 일" with their request day, one "진행 중인 일";
 *  - folded: one strip, "김민수 대표, 지난 통화: 견적서 금요일까지"; a tap opens it again, a drag moves it.
 * Never a phone number, never the transcript. Taller than [maxHeightPx] (40% of the screen) it leaves out the lowest rows
 * (the ongoing work first, then the 3rd and 2nd task, the last call line), so nothing is ever cut in half. */
@SuppressLint("ViewConstructor")
internal class CallCardView(context:Context):LinearLayout(context) {
    /** What the card says, worked out off the main thread from a [ContactCard] (or for a number without one). */
    /** [summaryUnsure] and [Task.unsure]: "확인 필요" beside it (DESIGN_V4 3.1-2). [thin]: a number only the call log knows
     * (v3.3 3.1-5): [lastCall] then says "지난 통화 3일 전, 30일간 2번, 정리된 통화 없음". */
    data class Content(val name:String,val company:String?,val lastCall:String?,val summaryLabel:String,val summary:String?,
                       val tasks:List<Task>,val issue:String?,val unknown:Boolean=false,val taskCount:Int=tasks.size,
                       val summaryUnsure:Boolean=false,val thin:Boolean=false,val thinShort:String?=null) {
        data class Task(val title:String,val requested:String,val unsure:Boolean=false)
        /** The folded strip: the name and the one thing worth remembering. */
        fun strip():String = when {
            summary!=null -> "$name, $summaryLabel: ${short(summary)}"
            tasks.isNotEmpty() -> "$name, 체크할 일: ${tasks[0].title}"
            thinShort!=null -> "$name, $thinShort"
            else -> name
        }
        /** The three lines of the heads-up notice shown instead while the screen is locked. */
        fun noticeLines():List<String> = listOfNotNull(
            listOfNotNull(company,lastCall).joinToString(", ").takeIf { it.isNotEmpty() },
            summary?.let { "$summaryLabel: $it"+if(summaryUnsure) ", $UNSURE" else "" },
            tasks.firstOrNull()?.let { "체크할 일: ${it.title}"+(if(it.unsure) ", $UNSURE" else "")+if(taskCount>1) " 외 ${taskCount-1}개" else "" },
            issue?.let { "진행 중인 일: $it" }).take(3)
        /** What the locked screen may show when the user allows it ("잠금 화면에서도 이름과 한 줄 보기", DESIGN_V4 3.1-5): the name
         * as the title, then the company and one check item, without numbers, amounts or anything of the call's words. */
        fun publicTitle():String = name
        fun publicText():String? = listOfNotNull(company,tasks.firstOrNull()?.let { "체크할 일: "+noAmounts(it.title) }?.takeIf { it.length>"체크할 일: ".length })
            .joinToString(", ").takeIf { it.isNotEmpty() }

        companion object {
            /** At most [STRIP_CHARS] letters, cut at a space and without the closing stop, so the strip stays two lines. */
            const val STRIP_CHARS=18
            fun short(t:String):String {
                val s=t.trim().trimEnd('.','!','?','。')
                if(s.length<=STRIP_CHARS) return s
                val cut=s.lastIndexOf(' ',STRIP_CHARS).takeIf { it>=STRIP_CHARS/2 } ?: STRIP_CHARS
                return s.substring(0,cut).trim()
            }
            /** A number nothing is known about (only when the user asked for those; the settings switch says the same). */
            const val UNKNOWN="아직 정리된 통화가 없는 번호예요"
            /** The badge words, as on the check list. */
            const val UNSURE=kr.callbrief.pipeline.CallUi.UNSURE
            /** Amounts and long numbers taken out of a line the locked screen shows ("50만원", "3,000,000원", "1588"). */
            fun noAmounts(t:String):String =PrivacyRules.removePhoneTokens(t)
                .replace(Regex("[0-9][0-9,.]*\\s*(?:억|천만|백만|십만|만|천)?\\s*원"),"").replace(Regex("[0-9][0-9,.\\-]{3,}"),"")
                .replace(Regex("\\s+")," ").trim()
            /** "지난 통화 3일 전, 30일간 2번, 정리된 통화 없음" (a thin card). */
            fun thinLine(lastMs:Long?,calls30:Int,now:Long=System.currentTimeMillis()):String =
                listOfNotNull(thinWhen(lastMs,now),if(calls30>0) "30일간 ${calls30}번" else null,"정리된 통화 없음").joinToString(", ")
            /** "지난 통화 오늘", "지난 통화 어제", "지난 통화 3일 전"; null without a time. */
            fun thinWhen(lastMs:Long?,now:Long=System.currentTimeMillis()):String? {
                if(lastMs==null || lastMs<=0) return null
                val days=java.time.temporal.ChronoUnit.DAYS.between(CallRecordStore.kstDay(lastMs),CallRecordStore.kstDay(now))
                return "지난 통화 "+when { days<=0 -> "오늘";days==1L -> "어제";else -> "${days}일 전" }
            }
            private fun safe(s:String?)=s?.let { PrivacyRules.removePhoneTokens(it).replace(Regex("\\s+")," ").trim() }?.takeIf { it.isNotEmpty() }
            fun of(card:ContactCard,now:Long=System.currentTimeMillis()):Content {
                card.thin?.let { t -> return Content(safe(t.name) ?: "정리된 통화가 없는 상대예요",null,thinLine(card.lastCallMs,t.calls30,now),"지난 통화",null,emptyList(),null,
                    thin=true,thinShort=thinWhen(card.lastCallMs,now) ?: "정리된 통화 없음") }
                val person=card.people.firstOrNull { safe(it.name)!=null }
                val company=card.company?.let { c -> safe(c.name)?.let { n -> listOfNotNull(n,safe(c.trade)).joinToString(", ") } }
                val name=person?.let { p -> listOfNotNull(safe(p.name),safe(p.role)).joinToString(" ")+
                    card.people.count { safe(it.name)!=null }.let { n -> if(n>1) " 외 ${n-1}명" else "" } }
                    ?: card.company?.let { safe(it.name) } ?: "지난 통화가 있는 상대예요"
                // The company line repeats nothing: with no person the name already is the company, then only its trade.
                val companyLine=if(person!=null) company else card.company?.let { safe(it.trade) }
                val recent=card.recent.firstOrNull { it.kind=="통화" } ?: card.recent.firstOrNull()
                val label=when(recent?.kind) { "문자" -> "지난 문자";"메일" -> "지난 메일";else -> "지난 통화" }
                val all=card.openTasks.mapNotNull { t -> safe(t.title)?.let { Task(it,CallUi.requested(t.requestedMs),t.tentative) } };val tasks=all.take(3)
                val issue=card.issues.firstOrNull()?.let { i -> safe(i.title)?.let { t -> listOfNotNull(t,safe(ContactCards.firstSentence(i.status))).joinToString(", ") } }
                return Content(name,companyLine,lastCallWords(card.lastCallMs,card.lastDirection,now),label,safe(recent?.summaryFirst),tasks,issue,taskCount=all.size,
                    summaryUnsure=recent?.unsure==true)
            }
            /** A number without a card (only when the user asked for those): the latest text with it, if any. */
            fun unknown(text:String?)=Content(UNKNOWN,null,null,"최근 문자",safe(text),emptyList(),null,unknown=true)
            /** "마지막 통화 어제 오후 3시, 받은 전화", "마지막 통화 10월 28일 (화) 오전 9시 30분". */
            fun lastCallWords(ms:Long?,direction:String?,now:Long=System.currentTimeMillis()):String? {
                if(ms==null || ms<=0) return null
                val t=Instant.ofEpochMilli(ms).atZone(CallRecordStore.KST)
                val day=CallRecordStore.kstDay(ms);val today=CallRecordStore.kstDay(now)
                val dayWord=when(day) { today -> "오늘";today.minusDays(1) -> "어제";else -> CallUi.date(day) }
                val h=t.hour%12;val hour=(if(t.hour<12) "오전 " else "오후 ")+(if(h==0) 12 else h)+"시"+if(t.minute>0) " ${t.minute}분" else ""
                val dir=when(direction) { "in" -> "받은 전화";"out" -> "건 전화";"missed" -> "못 받은 전화";"rejected" -> "거절한 전화";else -> null }
                return "마지막 통화 $dayWord $hour"+(dir?.let { ", $it" } ?: "")
            }
        }
    }

    private companion object { const val BADGE_INK=0xFF191F28.toInt() }
    var onCollapse:()->Unit={}
    var onExpand:()->Unit={}
    var onClose:()->Unit={}
    /** The card was dragged by [dy] pixels / let go after a drag. */
    var onMoveBy:(Float)->Unit={}
    var onMoveEnd:()->Unit={}
    /** At most this tall (40% of the screen, set by the overlay). */
    var maxHeightPx:Int=Int.MAX_VALUE
    var collapsed=false;private set
    /** Everything is drawn, not just the name line (the second step of drawing). */
    var complete=false;private set
    var content:Content?=null;private set

    /** Rows that may be left out when the card is too tall, the first one first. */
    private val optional=mutableListOf<View>()
    private val a=context
    private fun dp(v:Int)=CallUi.dp(a,v)
    /** [CallUi.text] with the card's tighter lines: more of the card fits in 40% of the screen. */
    private fun line(value:String,size:Float=CallUi.BODY,bold:Boolean=false,color:Int=CallUi.INK)=CallUi.text(a,value,size,bold,color).apply { setLineSpacing(0f,1.12f);setPadding(0,dp(1),0,dp(1)) }
    private fun aside(value:String)=CallUi.caption(a,value).apply { setLineSpacing(0f,1.12f);setPadding(0,dp(1),0,dp(1)) }

    init { orientation=VERTICAL;tag="card_root";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }

    /** First step: only the name line (shown at once, the rest follows within 50 ms). */
    fun showName(c:Content) { content=c;complete=false;rebuild() }
    /** Second step: everything. */
    fun showAll(c:Content) { content=c;complete=true;rebuild() }
    fun setCollapsed(on:Boolean) { if(collapsed==on) return;collapsed=on;rebuild() }

    private fun rebuild() {
        removeAllViews();optional.clear()
        val c=content ?: return
        background=CallUi.sheet(a,CallUi.CARD,CallUi.LINE,20)
        elevation=dp(8).toFloat()
        if(collapsed) buildStrip(c) else buildOpen(c)
        requestLayout()
    }

    private fun buildOpen(c:Content) {
        setPadding(dp(20),0,dp(16),dp(16))
        // The handle: a grey pill in a 28dp band that can be dragged.
        addView(LinearLayout(a).apply {
            tag="card_handle";gravity=Gravity.CENTER;contentDescription="카드 옮기기"
            addView(View(a).apply { background=CallUi.sheet(a,CallUi.FAINT,radius=3);layoutParams=LayoutParams(dp(40),dp(5)) })
            layoutParams=LayoutParams(-1,dp(20))
            setOnTouchListener(Drag(this,tapOpens=false))
        })
        val nameCol=LinearLayout(a).apply { orientation=VERTICAL
            addView(line(c.name,CallUi.HEADING,bold=true).apply { tag="card_name" })
            if(complete) c.company?.let { addView(aside(it).apply { tag="card_company" }) }
        }
        val buttons=LinearLayout(a).apply { orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            addView(button("접기","card_collapse") { onCollapse() })
            addView(button("닫기","card_close") { onClose() }.apply { (layoutParams as LayoutParams).marginStart=dp(8) })
        }
        addView(CallUi.FitRow(a,flex=0,minFraction=0.45f,gapDp=8,endWhenStacked=true).apply { addView(nameCol);addView(buttons) })
        if(!complete) return
        val last=c.lastCall?.let { aside(it).apply { tag="card_last" } }?.also { addView(it) }
        // "지난 통화  견적서는 …": the label as a grey aside in front, so it takes no line of its own.
        c.summary?.let { s -> addView(withAside(s,null,lead=c.summaryLabel,unsure=c.summaryUnsure).apply { tag="card_summary";setPadding(0,dp(10),0,0) }) }
        // One line each where it fits: the title, then its request day as a grey aside in the same line.
        val taskRows=c.tasks.mapIndexed { i,t -> withAside(t.title,t.requested.takeIf { it.isNotEmpty() },unsure=t.unsure).apply { tag="card_task_$i";setPadding(0,dp(3),0,dp(3)) } }
        if(taskRows.isNotEmpty()) addView(section(if(c.taskCount>1) "체크할 일 ${c.taskCount}개" else "체크할 일","card_tasks_label",taskRows))
        // "진행 중인 일" is one line: its label as the aside before it.
        val issue=c.issue?.let { t -> withAside(t,null,lead="진행 중인 일").apply { tag="card_issue";setPadding(0,dp(10),0,0) } }?.also { addView(it) }
        // The order to leave rows out in when the card is too tall: the ongoing work, the 3rd and 2nd task, the last call line.
        optional.addAll(listOfNotNull(issue)+taskRows.drop(1).reversed()+listOfNotNull(last))
    }
    /** Reading text with a 16sp grey aside after it ([tail]) or before it ([lead]), in one text that wraps as a whole; [unsure]
     * puts the "확인 필요" badge (bold dark letters on amber, the reading size) right after the main text. */
    private fun withAside(main:String,tail:String?,lead:String?=null,unsure:Boolean=false):TextView {
        val sb=android.text.SpannableStringBuilder()
        fun grey(t:String) { val st=sb.length;sb.append(t)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(CallUi.CAPTION.toInt(),true),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.ForegroundColorSpan(CallUi.SUBTLE),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        lead?.let { grey(it);sb.append("  ") }
        sb.append(CallUi.clean(main))
        if(unsure) { sb.append("  ");val st=sb.length;sb.append(" ${Content.UNSURE} ")
            sb.setSpan(android.text.style.BackgroundColorSpan(CallUi.AMBER_RAIL),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.ForegroundColorSpan(BADGE_INK),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        // The request day never breaks inside ("11월 3일 요청" stays whole, on the next line when it must).
        tail?.let { sb.append("  ");grey(it.replace(' ',' ')) }
        return line(main).apply { text=sb }
    }
    private fun section(label:String,labelTag:String,rows:List<View>)=LinearLayout(a).apply {
        orientation=VERTICAL;tag=labelTag.removeSuffix("_label")+"_section";setPadding(0,dp(8),0,0)
        addView(aside(label).apply { tag=labelTag })
        rows.forEach { addView(it) }
    }
    private fun button(label:String,tag:String,action:()->Unit)=CallUi.text(a,label,CallUi.BODY,bold=true).apply {
        this.tag=tag;gravity=Gravity.CENTER;minHeight=dp(44);minWidth=dp(64);setPadding(dp(14),0,dp(14),0)
        background=CallUi.ripple(a,CallUi.sheet(a,CallUi.CHIP,radius=22),radiusDp=22);isClickable=true;isFocusable=true
        contentDescription="카드 $label";layoutParams=LayoutParams(-2,-2)
        setOnClickListener { action() }
    }

    private fun buildStrip(c:Content) {
        setPadding(dp(20),dp(12),dp(16),dp(12))
        addView(LinearLayout(a).apply {
            tag="card_strip";orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(44)
            contentDescription="카드 펼치기";isClickable=true;setOnClickListener { onExpand() }
            val words=if(complete) c.strip() else c.name
            addView(line(words).apply {
                tag="card_strip_text"
                // The name in bold, the rest plain.
                val s=SpannableString(text);val end=minOf(c.name.length,s.length)
                if(text.startsWith(c.name)) s.setSpan(StyleSpan(Typeface.BOLD),0,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text=s
                layoutParams=LayoutParams(0,-2,1f)
            })
            addView(ImageView(a).apply { setImageDrawable(CallUi.icon(a,"chevron",CallUi.SUBTLE,24));rotation=90f
                layoutParams=LayoutParams(dp(32),dp(32)).apply { marginStart=dp(8) } })
            setOnTouchListener(Drag(this,tapOpens=true))
        })
    }

    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        // Every measure starts with all rows: a wider window (or a shorter summary) may have room again.
        optional.forEach { it.visibility=VISIBLE }
        super.onMeasure(widthSpec,free)
        if(MeasureSpec.getSize(widthSpec)>0) {
            var i=0
            while(measuredHeight>maxHeightPx && i<optional.size) { optional[i++].visibility=GONE;super.onMeasure(widthSpec,free) }
        }
        if(measuredHeight>maxHeightPx) setMeasuredDimension(measuredWidth,maxHeightPx)
    }

    /** Dragging moves the card; a press without moving opens the strip ([tapOpens]). */
    private inner class Drag(private val v:View,private val tapOpens:Boolean):OnTouchListener {
        private val slop=ViewConfiguration.get(a).scaledTouchSlop
        private var downY=0f;private var lastY=0f;private var dragging=false
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view:View,e:MotionEvent):Boolean {
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY=e.rawY;lastY=e.rawY;dragging=false }
                MotionEvent.ACTION_MOVE -> {
                    if(!dragging && abs(e.rawY-downY)>slop) dragging=true
                    if(dragging) { onMoveBy(e.rawY-lastY);lastY=e.rawY }
                }
                MotionEvent.ACTION_UP -> { if(dragging) onMoveEnd() else if(tapOpens) v.performClick();dragging=false }
                MotionEvent.ACTION_CANCEL -> { if(dragging) onMoveEnd();dragging=false }
            }
            return true
        }
    }

    /** Every text on the card (host tests: no number, no transcript). */
    internal fun texts():List<String> {
        fun walk(v:View):List<String> = (if(v is TextView) listOf(v.text.toString()) else emptyList())+
            (if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList())
        return walk(this)
    }
}
