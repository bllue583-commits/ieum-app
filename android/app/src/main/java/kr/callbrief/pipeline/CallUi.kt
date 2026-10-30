package kr.callbrief.pipeline

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One look for every call screen (10/30 evening, after Apple- and Toss-class references): one typeface (Pretendard),
 * ink and greys for hierarchy, one blue accent, white rounded sheets with no edges on a light grey page, big numbers
 * with small units, round initial badges in lists, line icons, and a bottom bar of five icon buttons that is the whole app.
 * Sized for a reader in his 50s: text 18sp and up (buttons excepted), touch targets 56dp and up, plain Korean. */
internal object CallUi {
    const val TITLE=28f
    const val HEADING=20f
    const val SUMMARY=19f
    const val BODY=19f
    const val SMALL=18f
    const val NUMBER=34f
    // Quiet palette: ink and greys carry the hierarchy, one blue accent, colour only where it means something.
    val INK=Color.parseColor("#191F28")
    val SUBTLE=Color.parseColor("#6B7684")
    val FAINT=Color.parseColor("#B0B8C1")
    val ACCENT=Color.parseColor("#3268F0")
    val ACCENT_SOFT=Color.parseColor("#EDF2FE")
    val NAVY=INK
    val AMBER=Color.parseColor("#B26B00")
    val AMBER_SOFT=Color.parseColor("#FFF4E0")
    val AMBER_RAIL=Color.parseColor("#F5A524")
    val WARN=Color.parseColor("#D63B3B")
    val WARN_SOFT=Color.parseColor("#FDECEC")
    val OK=Color.parseColor("#0E9F6E")
    val PAGE=Color.parseColor("#F2F4F6")
    val CARD=Color.parseColor("#FFFFFF")
    val LINE=Color.parseColor("#EEF0F2")
    val MUTED_BAR=Color.parseColor("#D9E3FC")

    fun dp(a:Activity,v:Int)=(v*a.resources.displayMetrics.density+0.5f).toInt()

    /** Pretendard (SIL OFL), bundled: one typeface family everywhere, weight for hierarchy. */
    enum class Weight(val res:Int) { REGULAR(kr.callbrief.R.font.pretendard_regular),MEDIUM(kr.callbrief.R.font.pretendard_medium),
        SEMIBOLD(kr.callbrief.R.font.pretendard_semibold),BOLD(kr.callbrief.R.font.pretendard_bold) }
    private val faces=HashMap<Weight,Typeface>()
    fun font(a:android.content.Context,w:Weight):Typeface=faces.getOrPut(w) { runCatching { a.resources.getFont(w.res) }.getOrDefault(Typeface.DEFAULT) }

    // ---- page frame: navy status strip, scrolling band + content, bottom bar ---------------------------------------
    /** The five places of the app. The bottom bar shows all of them on every main screen. */
    enum class Tab(val label:String,val icon:String,val tag:String) {
        HOME("홈","home","home_home_button"),CALLS("통화","call","home_calls_button"),TASKS("체크","task","home_tasks_button"),
        COMPANIES("업체","company","home_companies_button"),SETTINGS("설정","gear","home_settings_button");
        val target:Class<out Activity> get()=when(this) { HOME -> HomeActivity::class.java;CALLS -> TodayCallsActivity::class.java
            TASKS -> CallTasksActivity::class.java;COMPANIES -> CompaniesActivity::class.java;SETTINGS -> CallSettingsActivity::class.java }
    }

    /** Builds the frame and returns the content column. Call [band] at the top of every render. */
    fun page(a:Activity,tab:Tab?=null):LinearLayout {
        a.window.decorView.setBackgroundColor(PAGE)
        (a as? ComponentActivity)?.enableEdgeToEdge(SystemBarStyle.light(PAGE,PAGE),SystemBarStyle.light(CARD,CARD))
        val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(PAGE) }
        val strip=View(a).apply { setBackgroundColor(PAGE) }
        root.addView(strip,LinearLayout.LayoutParams(-1,0))
        val holder=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="page_holder" }
        holder.addView(LinearLayout(a).apply { tag="page_band" })
        val column=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;val p=dp(a,16);setPadding(p,dp(a,12),p,dp(a,24)) }
        holder.addView(column,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(a).apply { setBackgroundColor(PAGE);isFillViewport=true;tag="page_scroll";clipToPadding=true;addView(holder) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val bar=tab?.let { tabBar(a,it) }
        if(bar!=null) root.addView(bar,LinearLayout.LayoutParams(-1,-2))
        // Insets: the strip takes the status bar, the bottom bar (or the scroll view) the navigation bar. Nothing
        // is drawn under either bar.
        root.setOnApplyWindowInsetsListener { _,insets ->
            strip.layoutParams=strip.layoutParams.apply { height=insets.systemWindowInsetTop }
            root.setPadding(insets.systemWindowInsetLeft,0,insets.systemWindowInsetRight,0)
            if(bar!=null) bar.setPadding(0,dp(a,6),0,dp(a,6)+insets.systemWindowInsetBottom) else scroll.setPadding(0,0,0,insets.systemWindowInsetBottom)
            insets
        }
        a.setContentView(root);return column
    }

    /** Page header on the page itself, no coloured block: a back arrow when needed, a small grey line above
     * ([eyebrow]), a large title, a grey line below. ([overlap] is kept for call sites; the header never overlaps.) */
    fun band(content:LinearLayout,title:String,subtitle:String?=null,back:(()->Unit)?=null,eyebrow:String?=null,
             @Suppress("UNUSED_PARAMETER") overlap:Boolean=false) {
        val a=content.context as Activity
        val holder=content.parent as LinearLayout
        val band=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="page_band";setPadding(dp(a,22),dp(a,if(back!=null) 4 else 20),dp(a,22),dp(a,8)) }
        if(back!=null) band.addView(Button(a).apply {
            text="";contentDescription="뒤로";tag="page_back";stateListAnimator=null;background=null
            setCompoundDrawablesWithIntrinsicBounds(icon(a,"back",INK,28),null,null,null)
            minWidth=dp(a,56);minimumWidth=dp(a,56);minHeight=dp(a,56);minimumHeight=dp(a,56);setPadding(0,0,dp(a,8),0)
            setOnClickListener { back() }
        },LinearLayout.LayoutParams(-2,-2).apply { marginStart=-dp(a,6) })
        eyebrow?.let { band.addView(text(a,it,SMALL,color=SUBTLE).apply { setPadding(0,0,0,dp(a,2)) }) }
        band.addView(text(a,title,TITLE,true).apply { tag="page_title";letterSpacing=-0.02f;setPadding(0,0,0,0) })
        subtitle?.let { band.addView(text(a,it,SMALL,color=SUBTLE).apply { tag="page_subtitle";setPadding(0,dp(a,4),0,0) }) }
        holder.removeViewAt(0);holder.addView(band,0)
    }

    private fun tabBar(a:Activity,current:Tab)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;tag="bottom_bar"
        val wrap=this
        Tab.entries.forEach { t ->
            val on=t==current;val color=if(on) INK else FAINT
            wrap.addView(Button(a).apply {
                text=t.label;textSize=14f;isAllCaps=false;stateListAnimator=null;background=null;setTextColor(color);tag=t.tag
                typeface=font(a,if(on) Weight.SEMIBOLD else Weight.MEDIUM)
                setCompoundDrawablesWithIntrinsicBounds(null,icon(a,t.icon,color,26,if(on) 2.1f else 1.8f),null,null);compoundDrawablePadding=dp(a,3)
                minHeight=dp(a,60);minimumHeight=dp(a,60);minWidth=0;minimumWidth=0;setPadding(0,dp(a,4),0,dp(a,2))
                contentDescription=if(on) "${t.label}, 지금 보는 화면" else t.label
                setOnClickListener { if(!on) go(a,t) }
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        // White bar with a hairline on top.
        background=android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(LINE) },GradientDrawable().apply { setColor(CARD) })).apply { setLayerInset(1,0,dp(a,1),0,0) }
    }
    /** Switches main screens without a slide animation, reusing a screen that is already open. */
    fun go(a:Activity,t:Tab) {
        a.startActivity(Intent(a,t.target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        @Suppress("DEPRECATION") a.overridePendingTransition(0,0)
    }

    // ---- text and sheets ------------------------------------------------------------------------------------------
    fun text(a:Activity,value:String,size:Float=BODY,bold:Boolean=false,color:Int=INK)=TextView(a).apply {
        text=value;textSize=size;setTextColor(color);setLineSpacing(0f,if(size>=24f) 1.15f else 1.24f);setPadding(0,dp(a,2),0,dp(a,2))
        textLocale=Locale.KOREAN // phrase-based line breaking needs the text's language
        if(android.os.Build.VERSION.SDK_INT>=33)lineBreakWordStyle=android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE // wrap Korean at word boundaries
        typeface=font(a,when { !bold -> Weight.REGULAR;size>=24f -> Weight.BOLD;else -> Weight.SEMIBOLD })
        letterSpacing=-0.01f
    }
    fun title(a:Activity,value:String)=text(a,value,TITLE,true).apply { letterSpacing=-0.02f;setPadding(0,dp(a,8),0,dp(a,2)) }
    fun heading(a:Activity,value:String)=text(a,value,HEADING,true).apply { setPadding(dp(a,4),dp(a,22),0,dp(a,8)) }
    /** Section heading with a text link on the right ("전체 보기 ›"). */
    fun section(a:Activity,value:String,link:String?=null,action:(()->Unit)?=null)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(a,6),dp(a,22),0,dp(a,6))
        addView(text(a,value,HEADING,true),LinearLayout.LayoutParams(0,-2,1f))
        if(link!=null) addView(if(action!=null) linkButton(a,link,action) else text(a,link,SMALL,true,SUBTLE).apply { setPadding(0,0,dp(a,4),0) })
    }

    internal fun sheet(a:Activity,fill:Int,stroke:Int?=null,radius:Int=14)=GradientDrawable().apply {
        setColor(fill);cornerRadius=dp(a,radius).toFloat();if(stroke!=null)setStroke(dp(a,1),stroke) }
    private fun Button.base(a:Activity,label:String,action:()->Unit) {
        text=label;isAllCaps=false;stateListAnimator=null;textSize=18f;typeface=font(a,Weight.SEMIBOLD)
        minHeight=dp(a,56);minimumHeight=dp(a,56);setPadding(dp(a,16),0,dp(a,16),0);setOnClickListener { action() }
    }
    /** Primary action (at most one per screen): solid blue. [icon] is kept for call sites; words carry the meaning. */
    fun bigButton(a:Activity,@Suppress("UNUSED_PARAMETER") icon:String,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,action);setTextColor(Color.WHITE);background=sheet(a,ACCENT,radius=16)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,12);bottomMargin=dp(a,2) }
    }
    /** Secondary action: soft blue fill, no outline. */
    fun button(a:Activity,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,action);setTextColor(ACCENT);background=sheet(a,ACCENT_SOFT,radius=16)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,10);bottomMargin=dp(a,2) }
    }
    /** Text link button ("전체 보기 ›"), 48dp tall. */
    fun linkButton(a:Activity,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,action);setTextColor(ACCENT);background=null;textSize=17f;minHeight=dp(a,48);minimumHeight=dp(a,48);setPadding(dp(a,8),0,dp(a,4),0)
    }
    /** A white sheet: rounded, no edge, no shadow. [rail] tints the sheet instead of drawing a stripe (rarely used). */
    fun card(a:Activity,warn:Boolean=false,rail:Int?=if(warn) WARN else null)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL
        background=sheet(a,when(rail) { null -> CARD;WARN -> WARN_SOFT;AMBER_RAIL,AMBER -> AMBER_SOFT;else -> ACCENT_SOFT },radius=22)
        val p=dp(a,20);setPadding(p,dp(a,18),p,dp(a,18))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    /** Small status word on a soft fill (e.g. "정리 중 1건"). */
    fun pill(a:Activity,value:String,color:Int)=text(a,value,SMALL,true,color).apply {
        val fill=when(color) { WARN -> WARN_SOFT;AMBER -> AMBER_SOFT;ACCENT -> ACCENT_SOFT;else -> Color.parseColor("#F2F4F6") }
        background=sheet(a,fill,radius=10);setPadding(dp(a,10),dp(a,3),dp(a,10),dp(a,3));gravity=Gravity.CENTER
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(a,6) }
    }
    fun gap(a:Activity,h:Int=12)=View(a).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(a,h)) }
    fun divider(a:Activity)=View(a).apply { setBackgroundColor(LINE);layoutParams=LinearLayout.LayoutParams(-1,dp(a,1)).apply { topMargin=dp(a,10);bottomMargin=dp(a,8) } }

    /** Urgency colour for a due date: overdue red, today amber, otherwise none. */
    fun railFor(due:String?):Int? = when { overdue(due) -> WARN;due?.trim()==today().toString() -> AMBER_RAIL;else -> null }
    fun dueColor(due:String?)=when(railFor(due)) { WARN -> WARN;null -> SUBTLE;else -> AMBER }

    /** "오늘 요청", "어제 요청", "10월 28일 요청". */
    fun requested(ms:Long)=if(ms<=0) "" else when(val d=CallRecordStore.kstDay(ms)) {
        today() -> "오늘 요청";today().minusDays(1) -> "어제 요청";else -> DateTimeFormatter.ofPattern("M월 d일",Locale.KOREAN).format(d)+" 요청" }

    /** The one to-do row used on every screen: company and person, title, when it was asked for, optional [완료].
     * Calm on purpose: no red, no countdown. */
    fun taskRow(a:Activity,title:String,requestedMs:Long,company:String?,person:String?=null,status:String="",onDone:(()->Unit)?=null):LinearLayout {
        val c=card(a)
        val row=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        words.addView(text(a,title,SUMMARY,true).apply { setPadding(0,0,0,dp(a,2)) })
        // One quiet grey line: who, and when it was asked for.
        listOfNotNull(listOfNotNull(company,person?.let { "$it 님" }).joinToString(" ").takeIf { it.isNotEmpty() },requested(requestedMs).takeIf { it.isNotEmpty() })
            .joinToString(", ").takeIf { it.isNotEmpty() }?.let { words.addView(text(a,it,SMALL,color=SUBTLE).apply { setPadding(0,0,0,0) }) }
        taskStatus(status).takeIf { it.isNotEmpty() }?.let { words.addView(pill(a,it,ACCENT)) }
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        if(onDone!=null) row.addView(Button(a).apply {
            base(a,"확인",onDone);setTextColor(ACCENT);background=sheet(a,ACCENT_SOFT,radius=14)
            setCompoundDrawablesWithIntrinsicBounds(icon(a,"check",ACCENT,20,2.2f),null,null,null);compoundDrawablePadding=dp(a,4)
            setPadding(dp(a,14),0,dp(a,16),0);contentDescription="$title 확인했어요"
        },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(a,12) })
        c.addView(row);return c
    }

    /** Rows grouped in one sheet with hairlines between them, inset past the badge (iOS-style grouped list). */
    fun list(a:Activity,rows:List<View>)=card(a).apply {
        setPadding(0,dp(a,6),0,dp(a,6))
        rows.forEachIndexed { i,r ->
            if(i>0) addView(View(a).apply { setBackgroundColor(LINE) },LinearLayout.LayoutParams(-1,dp(a,1)).apply { marginStart=dp(a,78);marginEnd=dp(a,20) })
            addView(r)
        }
    }

    /** Round initial badge for a person or company: the first letter on a soft tint picked from the name. */
    fun avatar(a:Activity,name:String,sizeDp:Int=44)=TextView(a).apply {
        val tints=listOf("#EDF2FE" to "#3268F0","#E8F7F0" to "#0E9F6E","#FFF4E0" to "#B26B00","#F3EEFD" to "#7B4FD6","#FDEEF0" to "#D0455C")
        val clean=name.filter { it.isLetter() };val (bg,fg)=tints[(clean.hashCode() and 0x7fffffff)%tints.size].let { Color.parseColor(it.first) to Color.parseColor(it.second) }
        text=clean.firstOrNull()?.toString() ?: "";textSize=18f;setTextColor(fg);typeface=font(a,Weight.SEMIBOLD);gravity=Gravity.CENTER
        if(clean.isEmpty()) foreground=android.graphics.drawable.InsetDrawable(icon(a,"call",fg,20),dp(a,(sizeDp-22)/2)) // a number only: phone glyph
        background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(bg) };importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(dp(a,sizeDp),dp(a,sizeDp)).apply { marginEnd=dp(a,14) }
    }

    /** One call memo inside a list sheet: time and who on top, one sentence below, chevron. The whole row is the button. */
    fun memoRow(a:Activity,time:String,who:String,memo:String,quiet:Boolean=false,onOpen:()->Unit)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true
        gravity=Gravity.TOP;setPadding(dp(a,20),dp(a,16),dp(a,20),dp(a,16));minimumHeight=dp(a,72);contentDescription="$time $who, $memo. 눌러서 자세히 보기"
        val tv=android.util.TypedValue();a.theme.resolveAttribute(android.R.attr.selectableItemBackground,tv,true);setBackgroundResource(tv.resourceId)
        addView(avatar(a,who.split(' ').last()))
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(text(a,who,BODY,true).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        head.addView(text(a,time,SMALL,color=FAINT).apply { setPadding(dp(a,8),0,0,0) })
        words.addView(head)
        words.addView(text(a,memo,SMALL,color=if(quiet) FAINT else SUBTLE).apply { setPadding(0,dp(a,2),0,0) })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        setOnClickListener { onOpen() }
    }

    // ---- numbers and charts ---------------------------------------------------------------------------------------
    /** Big numbers in one sheet: grey label on top, the number large with its unit small ("5건"). */
    fun stats(a:Activity,items:List<Triple<String,String,Int>>,units:List<String> =emptyList())=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL
        items.forEachIndexed { i,(number,label,color) ->
            val unit=units.getOrElse(i) { "" }
            val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;contentDescription="$label $number$unit" }
            col.addView(text(a,label,SMALL,color=SUBTLE).apply { setPadding(0,0,0,dp(a,4)) })
            val big=android.text.SpannableString(number+unit).apply {
                if(unit.isNotEmpty()) { setSpan(android.text.style.AbsoluteSizeSpan(19,true),number.length,length,0);setSpan(android.text.style.ForegroundColorSpan(SUBTLE),number.length,length,0) } }
            col.addView(text(a,"",NUMBER,true,color).apply { text=big;letterSpacing=-0.02f;fontFeatureSettings="tnum";setPadding(0,0,0,0);includeFontPadding=false })
            addView(col,LinearLayout.LayoutParams(0,-2,1f))
        }
    }

    /** Vertical bars (e.g. calls per day this week); the last bar is today and drawn in navy. */
    class BarChart(a:Activity,private val values:List<Int>,private val labels:List<String>):View(a) {
        private val d=a.resources.displayMetrics.density
        private val bar=Paint(Paint.ANTI_ALIAS_FLAG)
        private val regular=font(a,Weight.MEDIUM);private val strong=font(a,Weight.BOLD)
        private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;textSize=16*a.resources.displayMetrics.scaledDensity;color=SUBTLE;typeface=regular }
        private val count=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;textSize=16*a.resources.displayMetrics.scaledDensity;color=INK;typeface=strong }
        init { minimumHeight=(150*d).toInt();contentDescription=labels.zip(values).joinToString(", ") { (l,v) -> "$l ${v}건" } }
        override fun onDraw(c:Canvas) {
            if(values.isEmpty())return
            val max=values.max().coerceAtLeast(1)
            val bottomText=label.textSize+6*d;val topText=count.textSize+4*d
            val chartH=height-bottomText-topText;val slot=width/values.size.toFloat();val w=minOf(slot*0.5f,28*d)
            values.forEachIndexed { i,v ->
                val cx=slot*i+slot/2;val h=if(v==0) 3*d else chartH*v/max
                val today=i==values.lastIndex
                bar.color=if(today) ACCENT else MUTED_BAR
                c.drawRoundRect(RectF(cx-w/2,topText+chartH-h,cx+w/2,topText+chartH),8*d,8*d,bar)
                if(v>0) { count.color=if(today) ACCENT else SUBTLE;c.drawText(v.toString(),cx,topText+chartH-h-5*d,count) }
                label.color=if(today) INK else FAINT;label.typeface=if(today) strong else regular
                c.drawText(labels[i],cx,height-4*d,label)
            }
        }
    }

    /** One horizontal bar split into coloured parts (e.g. overdue / today / later / undated to-dos). */
    class StackBar(a:Activity,private val parts:List<Pair<Int,Int>>,private val split:Boolean=true):View(a) {
        private val d=a.resources.displayMetrics.density;private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init { minimumHeight=(14*d).toInt() }
        override fun onDraw(c:Canvas) {
            val total=parts.sumOf { it.first }.coerceAtLeast(1);val gap=if(split) 3*d else 0f;var x=0f
            if(!split) { // a filled track: background first, then the parts over it from the left
                p.color=parts.last().second;c.drawRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),height/2f,height/2f,p)
                val n=parts.first().first;if(n>0) { p.color=parts.first().second;c.drawRoundRect(RectF(0f,0f,width*n/total.toFloat(),height.toFloat()),height/2f,height/2f,p) };return
            }
            val shown=parts.filter { it.first>0 }
            if(shown.isEmpty()) { p.color=LINE;c.drawRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),height/2f,height/2f,p);return }
            val usable=width-gap*(shown.size-1)
            shown.forEach { (n,color) -> val w=usable*n/total;p.color=color
                c.drawRoundRect(RectF(x,0f,x+w,height.toFloat()),height/2f,height/2f,p);x+=w+gap }
        }
    }
    /** Labelled horizontal bar (e.g. open to-dos of one company), scaled to [max]. */
    fun barRow(a:Activity,label:String,n:Int,max:Int)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(a,4),0,dp(a,6));contentDescription="$label ${n}개"
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL }
        head.addView(text(a,label,SMALL).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        head.addView(text(a,"${n}개",SMALL,true).apply { setPadding(0,0,0,0);fontFeatureSettings="tnum" })
        addView(head)
        addView(StackBar(a,listOf(n to ACCENT,(max-n).coerceAtLeast(0) to ACCENT_SOFT),split=false),LinearLayout.LayoutParams(-1,dp(a,10)).apply { topMargin=dp(a,6) })
    }
    /** Legend line: colour dot, label, count on the right. */
    fun legend(a:Activity,label:String,n:Int,color:Int)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(a,4),0,dp(a,4))
        addView(View(a).apply { background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(color) } },LinearLayout.LayoutParams(dp(a,12),dp(a,12)).apply { marginEnd=dp(a,10) })
        addView(text(a,label,SMALL,color=INK).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        addView(text(a,"${n}개",SMALL,true,INK).apply { setPadding(0,0,0,0);fontFeatureSettings="tnum" })
    }

    // ---- line icons (drawn, no image files) -----------------------------------------------------------------------
    fun icon(a:Activity,kind:String,color:Int,sizeDp:Int=24,stroke:Float=1.8f):Drawable = object:Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=stroke;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;this.color=color }
        override fun draw(c:Canvas) {
            val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
            when(kind) {
                "home" -> c.drawPath(Path().apply { moveTo(4f,11f);lineTo(12f,4f);lineTo(20f,11f);lineTo(20f,20f);lineTo(14.5f,20f);lineTo(14.5f,14.5f);lineTo(9.5f,14.5f);lineTo(9.5f,20f);lineTo(4f,20f);close() },paint)
                "call" -> c.drawPath(Path().apply { moveTo(5f,4f);lineTo(9f,4f);lineTo(11f,9f);lineTo(8.5f,10.5f);cubicTo(9.6f,12.8f,11.2f,14.4f,13.5f,15.5f);lineTo(15f,13f);lineTo(20f,15f);lineTo(20f,19f)
                    cubicTo(20f,20.1f,19.1f,21f,18f,21f);cubicTo(10f,20.5f,3.5f,14f,3f,6f);cubicTo(3f,4.9f,3.9f,4f,5f,4f);close() },paint)
                "task" -> { c.drawRoundRect(RectF(4f,4f,20f,20f),3f,3f,paint);c.drawPath(Path().apply { moveTo(8f,12f);lineTo(11f,15f);lineTo(16f,9f) },paint) }
                "company" -> { c.drawPath(Path().apply { moveTo(3f,21f);lineTo(21f,21f);moveTo(5f,21f);lineTo(5f,8f);lineTo(12f,4f);lineTo(19f,8f);lineTo(19f,21f);moveTo(9.5f,21f);lineTo(9.5f,16f);lineTo(14.5f,16f);lineTo(14.5f,21f) },paint)
                    c.drawPoint(9f,11f,paint);c.drawPoint(15f,11f,paint) }
                "gear" -> { c.drawCircle(12f,12f,3.2f,paint);c.drawCircle(12f,12f,7.5f,paint)
                    for(i in 0 until 8) { c.save();c.rotate(i*45f,12f,12f);c.drawLine(12f,2.5f,12f,4.5f,paint);c.restore() } }
                "back" -> c.drawPath(Path().apply { moveTo(15f,5f);lineTo(8f,12f);lineTo(15f,19f) },paint)
                "check" -> c.drawPath(Path().apply { moveTo(5f,12.5f);lineTo(10f,17.5f);lineTo(19f,7f) },paint)
                "chevron" -> c.drawPath(Path().apply { moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f) },paint)
            }
            c.restoreToCount(save)
        }
        override fun setAlpha(alpha:Int) { paint.alpha=alpha }
        override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth()=dp(a,sizeDp)
        override fun getIntrinsicHeight()=dp(a,sizeDp)
    }

    // ---- plain Korean formatting -------------------------------------------------
    private val TIME=DateTimeFormatter.ofPattern("a h:mm",Locale.KOREAN)
    private val DATE=DateTimeFormatter.ofPattern("M월 d일 (E)",Locale.KOREAN)
    /** "오후 2:05" for a call, "문자 오후 2:05" / "메일 오후 2:05" for messages. */
    fun whenLabel(r:CallRecord)=(if(r.kind=="통화") "" else r.kind+" ")+time(r.startedMs)
    fun time(ms:Long?)=ms?.let { TIME.format(Instant.ofEpochMilli(it).atZone(CallRecordStore.KST)) } ?: "시각 모름"
    fun date(d:LocalDate)=DATE.format(d)
    fun dateTime(ms:Long?)=ms?.takeIf{it>0}?.let { date(CallRecordStore.kstDay(it))+" "+time(it) } ?: "아직 없음"
    fun today()=CallRecordStore.kstDay(System.currentTimeMillis())
    fun dayWord(d:LocalDate)=when(d) { today() -> "오늘";today().minusDays(1) -> "어제";today().plusDays(1) -> "내일";else -> date(d) }
    fun weekday(d:LocalDate)=DateTimeFormatter.ofPattern("E",Locale.KOREAN).format(d)
    /** "좋은 아침이에요" by the hour (Seoul). */
    fun greeting(hour:Int=java.time.ZonedDateTime.now(CallRecordStore.KST).hour)=when(hour) { in 5..11 -> "좋은 아침이에요";in 12..17 -> "좋은 오후예요";else -> "좋은 저녁이에요" }
    /** "오늘까지", "내일까지", "2일 지남", "11월 3일까지"; free text is shown as written. */
    fun dueWords(due:String?):String {
        val d=due?.trim().orEmpty();if(d.isEmpty())return "기한 없음"
        val date=runCatching{LocalDate.parse(d)}.getOrNull() ?: return d
        val days=java.time.temporal.ChronoUnit.DAYS.between(today(),date)
        return when { days<0 -> "${-days}일 지남";days==0L -> "오늘까지";days==1L -> "내일까지";else -> "${date(date)}까지" }
    }
    /** A memo line: the first sentence of a summary, so lists never cut text off mid-way; the rest is one tap away. */
    fun firstSentence(summary:String):String {
        val s=summary.trim();val end=Regex("(요|다|음|함)\\.|[.!?](\\s|$)").find(s)?.range?.last ?: return s
        return s.substring(0,end+1).trim()
    }
    fun overdue(due:String?)=runCatching{LocalDate.parse(due?.trim())<today()}.getOrDefault(false)
    fun duration(ms:Long)=if(ms<60_000) "${ms/1000}초" else "${ms/60000}분"+((ms/1000)%60).let { if(it>0) " ${it}초" else "" }
    /** Record state in plain words; no English codes ever reach the screen. */
    fun state(state:String)=when(state) {
        CallRecordStore.TRANSCRIBED -> if(CallInboxConfig.enabled) "정리하고 있어요, 1시간 안에 끝나요" else "정리하고 있어요"
        CallRecordStore.WAITING_KEY -> "설정에서 Claude 키를 넣어주세요"
        CallRecordStore.WAITING_CONSENT -> "설정에서 정리 동의를 켜주세요"
        CallRecordStore.FAILED -> "정리하지 못했어요"
        else -> ""
    }
    /** "오늘 저녁 7시" / "내일 아침 6시": when the next report notification comes. */
    fun nextReport(eveningHour:Int,morningHour:Int,now:java.time.ZonedDateTime=java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))):String {
        fun at(h:Int)=now.toLocalDate().atTime(h,0).atZone(now.zone).let { if(it.isAfter(now)) it else it.plusDays(1) }
        val e=at(eveningHour);val m=at(morningHour);val next=if(e.isBefore(m)) e else m
        val day=if(next.toLocalDate()==now.toLocalDate()) "오늘" else "내일"
        val h=next.hour;val word=when { h<12 -> "아침 ${if(h==0) 12 else h}시";h==12 -> "낮 12시";h<18 -> "오후 ${h-12}시";else -> "저녁 ${h-12}시" }
        return "$day $word"+if(next==e) " (통화 정리)" else " (체크할 일)"
    }
    fun taskStatus(status:String)=when(status) { "needs_confirmation" -> "확인 필요";"in_progress" -> "진행 중";"waiting" -> "기다리는 중";"done" -> "완료";else -> "" }
}
