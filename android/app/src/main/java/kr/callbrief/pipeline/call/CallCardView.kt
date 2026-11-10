package kr.callbrief.pipeline.call

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
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
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** The 이음 call card drawn on top of the phone's call screen (design v3.0 7.1, r11-2; v3.4 DESIGN_V4 4.2-3, r14-2: read in
 * three seconds), built in code with the [CallUi] tokens and following the phone's night setting. Two looks:
 *  - open: a bar to drag it with [접기] [닫기] on its right; then the first line (20 bold, at most two lines: the one promise or
 *    piece of work with this person that matters now, picked by [Content.of]); "김민수 대표, 탄탄이엔씨, 3일 전" (18); one more
 *    "체크할 일" with "외 n개"; one "진행 중인 일". Five lines at most.
 *  - folded: one strip, "김민수 대표, 견적서 보내기, 어제 요청"; a tap opens it again, a drag moves it.
 * Never a phone number, never the transcript (amounts, dates and counts stay). Taller than [maxHeightPx] it leaves out the
 * lowest rows (the ongoing work, then the check item), so nothing is ever cut in half. */
@SuppressLint("ViewConstructor")
internal class CallCardView(context:Context):LinearLayout(context) {
    /** What the card says, worked out off the main thread from a [ContactCard] (or for a number without one).
     * [first]: the first line (already cut to [FIRST_CHARS] at a space); [rank] where it came from ([RANK_DUE] .. [RANK_THIN],
     * [RANK_UNKNOWN]); [firstUnsure]: "확인 필요" in front of it. [who]: the second line. [task] one more check item (not the
     * first line's) with [moreTasks] after it; [issue] one ongoing piece of work. [company] and [publicTask]: what the locked
     * screen may show ([publicText]). */
    data class Content(val name:String,val first:String,val rank:Int,val who:String?,val firstUnsure:Boolean=false,
                       val task:String?=null,val taskUnsure:Boolean=false,val moreTasks:Int=0,val issue:String?=null,
                       val company:String?=null,val publicTask:String?=null,val unknown:Boolean=false,val thin:Boolean=false) {
        /** There is a summary or a piece of work to keep in view during the call: without one the card goes at OFFHOOK
         * instead of folding into a strip (4.2-2). */
        val hasBody:Boolean get()=rank in RANK_DUE..RANK_ONGOING
        /** The folded strip: the name and the first line, cut for two lines. */
        fun strip():String = if(unknown) first else "$name, ${short(first)}"
        /** The lines of the heads-up notice (its title is [name]), in the card's order: the first line, the second line without
         * the name, the check item, the ongoing work. */
        fun noticeLines():List<String> = listOfNotNull(
            first+if(firstUnsure) ", $UNSURE" else "",
            who?.let { w -> if(w.startsWith("$name, ")) w.removePrefix("$name, ") else if(w==name) null else w },
            task?.let { taskLine(it,taskUnsure,moreTasks) },
            issue?.let { "진행 중인 일: $it" })
        /** What the locked screen may show when the user allows it ("잠금 화면에서도 이름과 한 줄 보기", DESIGN_V4 3.1-5): the name
         * as the title, then the company and one check item, without numbers, amounts or anything of the call's words. */
        fun publicTitle():String = name
        fun publicText():String? = listOfNotNull(company,publicTask?.let { "체크할 일: "+noAmounts(it) }?.takeIf { it.length>"체크할 일: ".length })
            .joinToString(", ").takeIf { it.isNotEmpty() }

        companion object {
            /** Where the first line came from (4.2-3), in the order they are picked. */
            const val RANK_DUE=1;const val RANK_CHECK=2;const val RANK_SUMMARY=3;const val RANK_ONGOING=4;const val RANK_THIN=5;const val RANK_UNKNOWN=6
            /** The first line: two lines of about [LINE_CHARS] letters, cut at a space, never with an ellipsis. */
            const val LINE_CHARS=18
            const val FIRST_CHARS=2*LINE_CHARS
            /** One line for the check item and the ongoing work. */
            const val ROW_CHARS=14
            /** A due date this close (days) or past makes an item the first line; a summary this recent (days) comes before work under way. */
            const val DUE_DAYS=7L
            const val SUMMARY_DAYS=14L
            const val DAY_MS=86_400_000L
            /** At most [STRIP_CHARS] letters, cut at a space and without the closing stop, so the strip stays two lines. */
            const val STRIP_CHARS=LINE_CHARS
            fun short(t:String):String=cut(t,STRIP_CHARS)
            /** [t] in at most [max] letters, cut at a space when there is one in its second half, without a closing stop or comma. */
            fun cut(t:String,max:Int):String {
                val s=t.replace(Regex("\\s+")," ").trim().trimEnd('.','!','?','。',',')
                if(s.length<=max) return s
                val at=s.lastIndexOf(' ',max).takeIf { it>=max/2 } ?: max
                return s.substring(0,at).trim().trimEnd(',')
            }
            /** A number nothing is known about (only when the user asked for those; the settings switch says the same). */
            const val UNKNOWN="아직 정리된 통화가 없는 번호예요"
            /** The badge words, as on the check list. */
            const val UNSURE=kr.callbrief.pipeline.CallUi.UNSURE
            fun taskLine(t:String,unsure:Boolean,more:Int)="체크할 일: $t"+(if(unsure) ", $UNSURE" else "")+if(more>0) " 외 ${more}개" else ""
            /** Amounts and long numbers taken out of a line the locked screen shows ("50만원", "3,000,000원", "1588"). */
            fun noAmounts(t:String):String =PrivacyRules.removePhoneTokens(t)
                .replace(Regex("[0-9][0-9,.]*\\s*(?:억|천만|백만|십만|만|천)?\\s*원"),"").replace(Regex("[0-9][0-9,.\\-]{3,}"),"")
                .replace(Regex("\\s+")," ").trim()
            /** "오늘", "어제", "3일 전"; null without a time. */
            fun ago(ms:Long?,now:Long=System.currentTimeMillis()):String? {
                if(ms==null || ms<=0) return null
                val days=ChronoUnit.DAYS.between(CallRecordStore.kstDay(ms),CallRecordStore.kstDay(now))
                return when { days<=0 -> "오늘";days==1L -> "어제";else -> "${days}일 전" }
            }
            /** The thin line: "3일 전 통화, 30일간 5번" (the call log only). */
            fun thinLine(lastMs:Long?,calls30:Int,now:Long=System.currentTimeMillis()):String =
                listOfNotNull(ago(lastMs,now)?.let { "$it 통화" },if(calls30>0) "30일간 ${calls30}번" else null).joinToString(", ").ifEmpty { "정리된 통화가 아직 없어요" }
            /** v3.5 r15-0 (user 11/6: no deadline words, no "지났어요"): where an item stands, from what came after it. A later
             * text, mail or call that left its note on the item says it ("11/5 메일: 끝난 것으로 보여요" -> "메일에서 끝난 것으로
             * 보여요"); an item the analysis thinks finished asks "끝났는지 확인"; otherwise the day it was asked for ("오늘 요청",
             * "어제 요청", "3일 전 요청"). */
            fun statusWords(t:ContactCard.Task,now:Long=System.currentTimeMillis()):String {
                if(t.status=="needs_confirmation") return "끝났는지 확인"
                noteWords(t.aiNote)?.takeIf { t.checkReason==null }?.let { return it }
                return requestedWords(t.requestedMs,now)
            }
            /** "11/5 메일: 끝난 것으로 보여요" -> "메일에서 끝난 것으로 보여요"; the part after a comma (the changed words) is left out. */
            internal fun noteWords(note:String?):String? {
                val m=Regex("^\\s*\\d{1,2}/\\d{1,2}\\s+(\\S+):\\s*(.+)$").find(note?.trim() ?: return null) ?: return null
                val what=m.groupValues[2].substringBefore(',').trim().takeIf { it.isNotEmpty() } ?: return null
                return "${m.groupValues[1]}에서 $what"
            }
            /** "오늘 요청", "어제 요청", "3일 전 요청"; "요청한 일" without a time. */
            fun requestedWords(ms:Long,now:Long=System.currentTimeMillis()):String {
                if(ms<=0) return "요청한 일"
                val days=ChronoUnit.DAYS.between(CallRecordStore.kstDay(ms),CallRecordStore.kstDay(now))
                return when { days<=0 -> "오늘 요청";days==1L -> "어제 요청";else -> "${days}일 전 요청" }
            }
            /** A phone number written into the words, also with a particle stuck to it ("010-2222-3333로"). */
            private val PHONE=Regex("(?<![0-9])(?:\\+?82[- ]?)?0[0-9]{1,2}[- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![0-9])")
            private fun safe(s:String?)=s?.let { PrivacyRules.removePhoneTokens(it).replace(PHONE,"").replace(Regex("\\s+")," ").trim() }?.takeIf { it.isNotEmpty() }
            private fun unsure(t:ContactCard.Task)=t.checkReason!=null || t.tentative
            /** Where an open item ranks (4.2-3): due within [DUE_DAYS] or past ([RANK_DUE]), "확인 필요" ([RANK_CHECK]), else work under way. */
            private fun rank(t:ContactCard.Task,now:Long):Int {
                val due=t.dueMs
                if(due!=null && ChronoUnit.DAYS.between(CallRecordStore.kstDay(now),CallRecordStore.kstDay(due))<=DUE_DAYS) return RANK_DUE
                return if(unsure(t)) RANK_CHECK else RANK_ONGOING
            }
            /** The open items in the order the card names them: by rank, then the earlier due date, then the latest request. */
            internal fun ordered(tasks:List<ContactCard.Task>,now:Long):List<ContactCard.Task> =tasks.filter { safe(it.title)!=null }
                .sortedWith(compareBy<ContactCard.Task>({ rank(it,now) },{ it.dueMs ?: Long.MAX_VALUE }).thenByDescending { it.requestedMs })
            /** An item's words: the title and where it stands ([statusWords]: "견적서 보내기, 메일에서 끝난 것으로 보여요",
             * "견적서 보내기, 어제 요청"), within [max]. v3.5 r15-0: never a deadline. */
            private fun taskWords(t:ContactCard.Task,now:Long,max:Int):String {
                val title=safe(CallUi.clean(t.title))!!
                val status=statusWords(t,now)
                return cut(title,(max-status.length-2).coerceAtLeast(LINE_CHARS/2))+", "+status
            }

            /** The card of [card] at [now] (KST), 4.2-3. The first line is, in this order: ① an open item due within 7 days or
             * past, ② an item marked "확인 필요" (the badge goes in front), ③ the first sentence of the last call's summary when that
             * call is within 14 days, ④ work under way (items without a usable due date, then the ongoing issues), ⑤ the thin
             * line ("3일 전 통화, 30일간 5번"). Within a rank the earlier due date, then the latest request. Only phone numbers are
             * taken out; amounts, dates and counts stay. */
            fun of(card:ContactCard,now:Long=System.currentTimeMillis()):Content {
                val thin=card.thin
                val person=card.people.firstOrNull { safe(it.name)!=null }
                val companyName=card.company?.let { safe(it.name) }
                val name=when {
                    thin!=null -> safe(thin.name) ?: "정리된 통화가 없는 상대예요"
                    person!=null -> listOfNotNull(safe(person.name),safe(person.role)).joinToString(" ")+
                        card.people.count { safe(it.name)!=null }.let { n -> if(n>1) " 외 ${n-1}명" else "" }
                    else -> companyName ?: "지난 통화가 있는 상대예요"
                }
                val calls30=if(thin!=null) maxOf(thin.calls30,card.calls30d) else card.calls30d
                if(thin!=null) return Content(name,thinLine(card.lastCallMs,calls30,now),RANK_THIN,null,thin=true)
                // The second line: who, their company (when the name is a person's), how long ago the last call was.
                val who=listOfNotNull(name,companyName.takeIf { person!=null },ago(card.lastCallMs,now)).joinToString(", ")
                val tasks=ordered(card.openTasks,now)
                val issues=card.issues.mapNotNull { i -> safe(i.title)?.let { t -> listOfNotNull(t,safe(ContactCards.firstSentence(i.status))).joinToString(", ") } }
                val call=card.recent.firstOrNull { it.kind=="통화" && safe(it.summaryFirst)!=null && now-it.atMs<=SUMMARY_DAYS*DAY_MS }
                    ?.takeIf { card.lastCallMs==null || now-card.lastCallMs<=SUMMARY_DAYS*DAY_MS }
                val top=tasks.firstOrNull()?.takeIf { rank(it,now)<=RANK_CHECK }
                var first:String;var rank:Int;var unsure=false;var usedTask:ContactCard.Task?=null;var usedIssue=false
                when {
                    top!=null -> { first=taskWords(top,now,FIRST_CHARS);rank=rank(top,now);unsure=unsure(top);usedTask=top }
                    call!=null -> { first=cut(safe(call.summaryFirst)!!,FIRST_CHARS);rank=RANK_SUMMARY;unsure=call.unsure }
                    tasks.isNotEmpty() -> { val t=tasks.first();first=taskWords(t,now,FIRST_CHARS);rank=RANK_ONGOING;unsure=unsure(t);usedTask=t }
                    issues.isNotEmpty() -> { first=cut(issues.first(),FIRST_CHARS);rank=RANK_ONGOING;usedIssue=true }
                    else -> { first=thinLine(card.lastCallMs,calls30,now);rank=RANK_THIN }
                }
                val rest=tasks.filter { it!==usedTask }
                val next=rest.firstOrNull()
                // Rows 3 and 4 keep to one line: the title only (its due date is the first line's business).
                val issue=(if(usedIssue) issues.drop(1) else issues).firstOrNull()?.let { cut(it,ROW_CHARS) }
                // The badge takes the room of about four letters.
                return Content(name,first,rank,who,unsure,next?.let { cut(safe(CallUi.clean(it.title))!!,if(unsure(it)) ROW_CHARS-4 else ROW_CHARS) },next?.let(::unsure)==true,
                    (rest.size-1).coerceAtLeast(0),issue,companyName.takeIf { person!=null },tasks.firstOrNull()?.let { safe(it.title) })
            }
            /** A number without a card (only when the user asked for those): what the phone's call log says of it, if anything. */
            fun unknown(logLine:String?)=Content(UNKNOWN,UNKNOWN,RANK_UNKNOWN,safe(logLine) ?: "통화기록에 없는 번호예요",unknown=true)
        }
    }

    private companion object { const val BADGE_INK=0xFF191F28.toInt() }
    var onCollapse:()->Unit={}
    var onExpand:()->Unit={}
    var onClose:()->Unit={}
    /** The card was dragged by [dy] pixels / let go after a drag. */
    var onMoveBy:(Float)->Unit={}
    var onMoveEnd:()->Unit={}
    /** At most this tall (set by the overlay: 34% of the screen while it rings). */
    var maxHeightPx:Int=Int.MAX_VALUE
    var collapsed=false;private set
    /** Everything is drawn, not just the first two lines (the second step of drawing). */
    var complete=false;private set
    var content:Content?=null;private set
    /** v3.6.1 (DESIGN_V7 K1): the window this card is in is visible ([onWindowVisibilityChanged]) and the card was drawn while it
     * was ([dispatchDraw]): only then is it really on the screen (a window Android adds hidden is neither). */
    @Volatile var seen=false;private set
    private var windowVisible=false

    /** Rows that may be left out when the card is too tall, the first one first. */
    private val optional=mutableListOf<View>()
    private val a=context
    private fun dp(v:Int)=CallUi.dp(a,v)
    /** [CallUi.text] with the card's tighter lines: more of the card fits in its share of the screen. */
    private fun line(value:String,size:Float=CallUi.ASIDE,bold:Boolean=false,color:Int=CallUi.INK)=CallUi.text(a,value,size,bold,color).apply { setLineSpacing(0f,1.12f);setPadding(0,dp(1),0,dp(1)) }

    init { orientation=VERTICAL;tag="card_root";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }

    /** First step: the bar, the first line and who it is (shown at once, the rest follows within 50 ms). */
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
        setPadding(dp(20),0,dp(12),dp(14))
        // The bar: a grey pill to drag the card by, then [접기] [닫기] (44dp each), so the first line has the whole width.
        addView(LinearLayout(a).apply { orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="card_bar"
            addView(LinearLayout(a).apply {
                tag="card_handle";gravity=Gravity.CENTER;contentDescription="카드 옮기기"
                addView(View(a).apply { background=CallUi.sheet(a,CallUi.FAINT,radius=3);layoutParams=LayoutParams(dp(40),dp(5)) })
                layoutParams=LayoutParams(0,dp(44),1f)
                setOnTouchListener(Drag(this,tapOpens=false))
            })
            addView(button("접기","card_collapse") { onCollapse() })
            addView(button("닫기","card_close") { onClose() }.apply { (layoutParams as LayoutParams).marginStart=dp(8) })
            layoutParams=LayoutParams(-1,-2).apply { topMargin=dp(4) }
        })
        // 1. The first line (20 bold, two lines): the badge in front when the item needs a check.
        addView(line(c.first,CallUi.HEADING,bold=true).apply { tag="card_first";text=withBadge(c.first,c.firstUnsure);setPadding(0,dp(4),0,dp(2)) })
        // 2. Who, their company, how long ago (18, ink).
        c.who?.let { addView(line(it).apply { tag="card_who" }) }
        if(!complete) return
        // 3. One more check item, "외 n개"; 4. one piece of work under way. Each one line where it fits.
        val task=c.task?.let { t -> withAside(t,c.moreTasks.takeIf { it>0 }?.let { "외 ${it}개" },lead="체크할 일",unsure=c.taskUnsure).apply { tag="card_task";setPadding(0,dp(6),0,0) } }?.also { addView(it) }
        val issue=c.issue?.let { t -> withAside(t,null,lead="진행 중인 일").apply { tag="card_issue";setPadding(0,dp(4),0,0) } }?.also { addView(it) }
        // The order to leave rows out in when the card is too tall: the ongoing work, then the check item.
        optional.addAll(listOfNotNull(issue,task))
    }
    /** [text] with the "확인 필요" badge (bold dark letters on amber) in front when [unsure]. */
    private fun withBadge(text:String,unsure:Boolean):CharSequence {
        if(!unsure) return CallUi.clean(text)
        val sb=SpannableStringBuilder(" ${Content.UNSURE} ")
        sb.setSpan(android.text.style.BackgroundColorSpan(CallUi.AMBER_RAIL),0,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(BADGE_INK),0,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(CallUi.ASIDE.toInt(),true),0,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb.append(" ").append(CallUi.clean(text))
    }
    /** Reading text with a 16sp grey aside after it ([tail]) or before it ([lead]), in one text that wraps as a whole; [unsure]
     * puts the "확인 필요" badge (bold dark letters on amber, the reading size) right after the main text. */
    private fun withAside(main:String,tail:String?,lead:String?=null,unsure:Boolean=false):TextView {
        val sb=SpannableStringBuilder()
        fun grey(t:String) { val st=sb.length;sb.append(t)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(CallUi.CAPTION.toInt(),true),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.ForegroundColorSpan(CallUi.SUBTLE),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        lead?.let { grey(it);sb.append("  ") }
        sb.append(CallUi.clean(main))
        if(unsure) { sb.append("  ");val st=sb.length;sb.append(" ${Content.UNSURE} ")
            sb.setSpan(android.text.style.BackgroundColorSpan(CallUi.AMBER_RAIL),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.ForegroundColorSpan(BADGE_INK),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD),st,sb.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        // "외 2개" never breaks inside.
        tail?.let { sb.append("  ");grey(it.replace(' ',' ')) }
        return line(main).apply { text=sb }
    }
    private fun button(label:String,tag:String,action:()->Unit)=CallUi.text(a,label,CallUi.ASIDE,bold=true).apply {
        this.tag=tag;gravity=Gravity.CENTER;minHeight=dp(44);minWidth=dp(64);setPadding(dp(14),0,dp(14),0)
        background=CallUi.ripple(a,CallUi.sheet(a,CallUi.CHIP,radius=22),radiusDp=22);isClickable=true;isFocusable=true
        contentDescription="카드 $label";layoutParams=LayoutParams(-2,-2)
        setOnClickListener { action() }
    }

    private fun buildStrip(c:Content) {
        setPadding(dp(20),dp(10),dp(16),dp(10))
        addView(LinearLayout(a).apply {
            tag="card_strip";orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(44)
            contentDescription="카드 펼치기";isClickable=true;setOnClickListener { onExpand() }
            val words=c.strip()
            addView(line(words).apply {
                tag="card_strip_text"
                // The name in bold, the rest plain.
                val s=android.text.SpannableString(text);val end=minOf(c.name.length,s.length)
                if(text.startsWith(c.name)) s.setSpan(StyleSpan(Typeface.BOLD),0,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text=s
                layoutParams=LayoutParams(0,-2,1f)
            })
            addView(ImageView(a).apply { setImageDrawable(CallUi.icon(a,"chevron",CallUi.SUBTLE,24));rotation=90f
                importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams=LayoutParams(dp(32),dp(32)).apply { marginStart=dp(8) } })
            setOnTouchListener(Drag(this,tapOpens=true))
        })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow();windowVisible=windowVisibility==VISIBLE }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);windowVisible=visibility==VISIBLE }
    override fun dispatchDraw(canvas:android.graphics.Canvas) { super.dispatchDraw(canvas);if(windowVisible && isAttachedToWindow) seen=true }

    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        // Every measure starts with all rows: a wider window (or a shorter line) may have room again.
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
    /** What TalkBack reads, in its order (a view's description, else its text), visible views only (host tests). */
    internal fun spoken():List<View> {
        fun walk(v:View):List<View> {
            if(v.visibility!=VISIBLE) return emptyList()
            val self=if((v is TextView && v.text.isNotEmpty()) || v.contentDescription!=null) listOf(v) else emptyList()
            return if(v.contentDescription!=null && v !is TextView) self else self+(if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList())
        }
        return walk(this)
    }
}
