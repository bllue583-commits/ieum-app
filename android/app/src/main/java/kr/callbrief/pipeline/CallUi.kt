package kr.callbrief.pipeline

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One look for every call screen (round 3, after the 11/4 review, section 7.2): one typeface (Pretendard), ink and greys
 * for hierarchy, one blue accent used in one place per screen, no red anywhere, white sheets with two corner sizes on a
 * light grey page (a dark set follows the phone's night setting), people as circles and companies as rounded squares,
 * one family of 2 px line icons, ripples on everything that can be pressed, and a 120 ms fade between screens.
 * Sized for a reader in his 50s: reading text 18sp and up, asides (captions, badges) 16 and 15sp, touch targets 44dp and up,
 * plain Korean. Spacing is a grid of 8: screen sides 20, inside a sheet 16, a row 14 top and bottom, sections 24 apart, sheets 12 apart. */
internal object CallUi {
    // ---- type scale: screen title 30 Bold, big number 36 Bold, section 20 SemiBold, body 19, emphasis 19 SemiBold,
    // caption 16, badge 15 SemiBold. Lines: body 1.3, titles 1.15. -------------------------------------------------------
    const val TITLE=30f
    const val NUMBER=36f
    const val HEADING=20f
    const val BODY=19f
    const val SUMMARY=19f
    const val CAPTION=16f
    const val BADGE=15f
    const val TAB=16f
    /** Old name of [CAPTION], kept so call sites written before round 3 still compile. */
    const val SMALL=CAPTION

    // ---- colours: the light set and the night set; every colour is read from the current set when a view is built. ------
    private class Palette(val page:String,val card:String,val line:String,val ink:String,val subtle:String,val muted:String,val faint:String,
                          val accent:String,val accentSoft:String,val onAccent:String,val amber:String,val amberSoft:String,val amberRail:String,
                          val ok:String,val mutedBar:String,val chip:String,val snack:String)
    private val LIGHT=Palette("#F2F4F6","#FFFFFF","#EEF0F2","#191F28","#6B7684","#8B95A1","#B0B8C1","#3268F0","#EDF2FE","#FFFFFF","#B26B00","#FFF4E0","#F5A524","#0B8A5F","#D9E3FC","#F2F4F6","#2B323B")
    private val NIGHT=Palette("#111418","#1A1F26","#2A3038","#F2F4F6","#A4ADB8","#8B95A1","#4A525C","#6B93FF","#1F2B4A","#0B1020","#F2B84B","#33291A","#F2B84B","#3DD598","#2C3C66","#232A33","#2E3640")
    /** True while the phone is in night mode; set by [page] from the screen's configuration. */
    @Volatile var night=false
        private set
    private val p:Palette get()=if(night) NIGHT else LIGHT
    private fun c(hex:String)=Color.parseColor(hex)
    fun applyTheme(a:android.content.Context) { night=(a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES }
    val PAGE:Int get()=c(p.page)
    val CARD:Int get()=c(p.card)
    /** Hairlines and borders only. */
    val LINE:Int get()=c(p.line)
    val INK:Int get()=c(p.ink)
    val SUBTLE:Int get()=c(p.subtle)
    /** Quietest grey that is still text (asides that may be a little lighter than SUBTLE). */
    val MUTED:Int get()=c(p.muted)
    /** Lines and inactive icons only, never text. */
    val FAINT:Int get()=c(p.faint)
    val ACCENT:Int get()=c(p.accent)
    val ACCENT_SOFT:Int get()=c(p.accentSoft)
    /** Text and icons on a solid [ACCENT] fill. */
    val ON_ACCENT:Int get()=c(p.onAccent)
    val AMBER:Int get()=c(p.amber)
    val AMBER_SOFT:Int get()=c(p.amberSoft)
    val AMBER_RAIL:Int get()=c(p.amberRail)
    val OK:Int get()=c(p.ok)
    val MUTED_BAR:Int get()=c(p.mutedBar)
    /** Fill of neutral chips and buttons. */
    val CHIP:Int get()=c(p.chip)
    /** Pressed-state ripple: the ink colour at about 14%. */
    val RIPPLE:Int get()=(INK and 0x00FFFFFF) or (0x24 shl 24)

    fun dp(a:Activity,v:Int)=(v*a.resources.displayMetrics.density+0.5f).toInt()

    /** Pretendard (SIL OFL), bundled: one typeface family everywhere, weight for hierarchy. */
    enum class Weight(val res:Int) { REGULAR(kr.callbrief.R.font.pretendard_regular),MEDIUM(kr.callbrief.R.font.pretendard_medium),
        SEMIBOLD(kr.callbrief.R.font.pretendard_semibold),BOLD(kr.callbrief.R.font.pretendard_bold) }
    private val faces=HashMap<Weight,Typeface>()
    fun font(a:android.content.Context,w:Weight):Typeface=faces.getOrPut(w) { runCatching { a.resources.getFont(w.res) }.getOrDefault(Typeface.DEFAULT) }

    // ---- page frame: status strip, scrolling band + content, snack bar slot, bottom bar --------------------------------
    /** The five places of the app. The bottom bar shows all of them on every main screen. */
    enum class Tab(val label:String,val icon:String,val tag:String) {
        HOME("홈","home","home_home_button"),CALLS("통화","call","home_calls_button"),TASKS("체크","task","home_tasks_button"),
        COMPANIES("업체","company","home_companies_button"),SETTINGS("설정","gear","home_settings_button");
        val target:Class<out Activity> get()=when(this) { HOME -> HomeActivity::class.java;CALLS -> TodayCallsActivity::class.java
            TASKS -> CallTasksActivity::class.java;COMPANIES -> CompaniesActivity::class.java;SETTINGS -> CallSettingsActivity::class.java }
    }

    /** Builds the frame and returns the content column. Call [band] at the top of every render. */
    fun page(a:Activity,tab:Tab?=null):LinearLayout {
        applyTheme(a)
        a.window.decorView.setBackgroundColor(PAGE)
        (a as? ComponentActivity)?.enableEdgeToEdge(if(night) SystemBarStyle.dark(PAGE) else SystemBarStyle.light(PAGE,PAGE),
            if(night) SystemBarStyle.dark(CARD) else SystemBarStyle.light(CARD,CARD))
        val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(PAGE) }
        val strip=View(a).apply { setBackgroundColor(PAGE) }
        root.addView(strip,LinearLayout.LayoutParams(-1,0))
        val holder=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="page_holder" }
        holder.addView(LinearLayout(a).apply { tag="page_band" })
        val column=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(a,20),dp(a,8),dp(a,20),dp(a,24)) }
        holder.addView(column,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(a).apply { setBackgroundColor(PAGE);isFillViewport=true;tag="page_scroll";clipToPadding=true;addView(holder) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        // The "되돌리기" bar lives here, just above the bottom bar, so it never covers what was just touched.
        root.addView(LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="snack_holder";visibility=View.GONE;setPadding(dp(a,20),dp(a,8),dp(a,20),dp(a,8)) },LinearLayout.LayoutParams(-1,-2))
        val bar=tab?.let { tabBar(a,it) }
        if(bar!=null) root.addView(bar,LinearLayout.LayoutParams(-1,-2))
        // Insets: the strip takes the status bar, the bottom bar (or the scroll view) the navigation bar. Nothing is drawn under either bar.
        root.setOnApplyWindowInsetsListener { _,insets ->
            strip.layoutParams=strip.layoutParams.apply { height=insets.systemWindowInsetTop }
            root.setPadding(insets.systemWindowInsetLeft,0,insets.systemWindowInsetRight,0)
            if(bar!=null) bar.setPadding(0,dp(a,6),0,dp(a,6)+insets.systemWindowInsetBottom) else scroll.setPadding(0,0,0,insets.systemWindowInsetBottom)
            insets
        }
        // Back from a main screen goes home; the screens with a detail view of their own add a callback that wins over this.
        if(tab!=null && tab!=Tab.HOME && a is ComponentActivity)
            a.onBackPressedDispatcher.addCallback(a,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { leave(a) } })
        a.setContentView(root);return column
    }

    /** Page header on the page itself: a back icon when needed, a small grey line above ([eyebrow]), a large title
     * (with an optional round [trailing] button on its right), a grey line below. */
    fun band(content:LinearLayout,title:String,subtitle:String?=null,back:(()->Unit)?=null,eyebrow:String?=null,
             @Suppress("UNUSED_PARAMETER") overlap:Boolean=false,trailing:View?=null) {
        val a=content.context as Activity
        val holder=content.parent as LinearLayout
        val band=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;tag="page_band";setPadding(dp(a,20),dp(a,if(back!=null) 4 else 24),dp(a,20),dp(a,8)) }
        if(back!=null) band.addView(iconButton(a,"back","뒤로",44,24,INK,null) { back() }.apply { tag="page_back" },LinearLayout.LayoutParams(dp(a,44),dp(a,44)).apply { marginStart=-dp(a,10);bottomMargin=dp(a,4) })
        eyebrow?.let { band.addView(caption(a,it,SUBTLE).apply { setPadding(0,0,0,dp(a,2)) }) }
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(text(a,title,TITLE,true).apply { tag="page_title";letterSpacing=-0.02f;setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        if(trailing!=null) head.addView(trailing,LinearLayout.LayoutParams(trailing.layoutParams?.width ?: -2,trailing.layoutParams?.height ?: -2).apply { marginStart=dp(a,12) })
        band.addView(head)
        subtitle?.let { band.addView(caption(a,it,SUBTLE).apply { tag="page_subtitle";setPadding(0,dp(a,6),0,0) }) }
        holder.removeViewAt(0);holder.addView(band,0)
    }

    private fun tabBar(a:Activity,current:Tab)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;tag="bottom_bar"
        val wrap=this
        Tab.entries.forEach { t ->
            val on=t==current;val color=if(on) INK else MUTED
            wrap.addView(Button(a).apply {
                text=t.label;textSize=TAB;isAllCaps=false;stateListAnimator=null;setTextColor(color);tag=t.tag
                background=RippleDrawable(ColorStateList.valueOf(RIPPLE),null,ColorDrawable(Color.BLACK))
                typeface=font(a,if(on) Weight.SEMIBOLD else Weight.MEDIUM)
                setCompoundDrawablesWithIntrinsicBounds(null,icon(a,t.icon,color,26),null,null);compoundDrawablePadding=dp(a,2)
                minHeight=dp(a,60);minimumHeight=dp(a,60);minWidth=0;minimumWidth=0;setPadding(0,dp(a,4),0,dp(a,2))
                contentDescription=if(on) "${t.label}, 지금 보는 화면" else t.label
                setOnClickListener { if(!on) go(a,t) }
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        // The bar is a sheet with a hairline on top.
        background=android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(LINE) },GradientDrawable().apply { setColor(CARD) })).apply { setLayerInset(1,0,dp(a,1),0,0) }
    }
    /** Switches main screens, reusing a screen that is already open. The only motion is the 120 ms fade from the theme. */
    fun go(a:Activity,t:Tab) { a.startActivity(Intent(a,t.target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    /** Back out of a main screen: home, with everything opened since on top of it closed. */
    fun leave(a:Activity) { a.startActivity(Intent(a,HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }

    /** Scroll position of the page now, to put back after a screen is built again (so a list does not jump to the top). */
    fun scrollY(content:View):Int=scrollOf(content)?.scrollY ?: 0
    fun restoreScroll(content:View,y:Int) { val s=scrollOf(content) ?: return;if(y>0) s.post { s.scrollTo(0,y) } }
    private fun scrollOf(content:View):ScrollView? { var v:View?=content;while(v!=null) { if(v is ScrollView) return v;v=v.parent as? View };return null }

    // ---- text ---------------------------------------------------------------------------------------------------------
    fun text(a:Activity,value:String,size:Float=BODY,bold:Boolean=false,color:Int=INK)=TextView(a).apply {
        // Text the analysis wrote is shown as is; the screen rules still hold (no middle dot, no ellipsis).
        text=clean(value);textSize=size;setTextColor(color);setLineSpacing(0f,if(size>=24f) 1.15f else 1.3f);setPadding(0,dp(a,2),0,dp(a,2))
        textLocale=Locale.KOREAN // phrase-based line breaking needs the text's language
        if(android.os.Build.VERSION.SDK_INT>=33)lineBreakWordStyle=android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE // wrap Korean at word boundaries
        typeface=font(a,when { !bold -> Weight.REGULAR;size>=24f -> Weight.BOLD;else -> Weight.SEMIBOLD })
        letterSpacing=-0.01f
        // Anything under reading size says so (the 18sp rule of the tests is "read: 18 and up, aside: 16 and up, badge: 15 and up").
        if(size<18f) setTag(kr.callbrief.R.id.ui_min_sp,if(size<CAPTION) BADGE else CAPTION)
    }
    /** An aside to the text above it (a time, a request date, a trade): 16sp. */
    fun caption(a:Activity,value:String,color:Int=SUBTLE,bold:Boolean=false)=text(a,value,CAPTION,bold,color)
    fun title(a:Activity,value:String)=text(a,value,TITLE,true).apply { letterSpacing=-0.02f;setPadding(0,dp(a,8),0,dp(a,2)) }
    fun heading(a:Activity,value:String)=text(a,value,HEADING,true).apply { setPadding(dp(a,4),dp(a,24),0,dp(a,8)) }
    /** Section heading with a text link on the right ("전체 보기 ›"). */
    fun section(a:Activity,value:String,link:String?=null,action:(()->Unit)?=null)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(a,4),dp(a,24),0,dp(a,4))
        addView(text(a,value,HEADING,true),LinearLayout.LayoutParams(0,-2,1f))
        if(link!=null) addView(if(action!=null) linkButton(a,link,action) else caption(a,link,SUBTLE,true).apply { setPadding(0,0,dp(a,4),0) })
    }

    // ---- sheets and buttons ---------------------------------------------------------------------------------------------
    internal fun sheet(a:Activity,fill:Int,stroke:Int?=null,radius:Int=14)=GradientDrawable().apply {
        setColor(fill);cornerRadius=dp(a,radius).toFloat();if(stroke!=null)setStroke(dp(a,1),stroke) }
    /** [fill] wrapped in a ripple that is cut to the same rounded shape (or a circle). */
    fun ripple(a:Activity,fill:Drawable?,radiusDp:Int=14,oval:Boolean=false):Drawable {
        val mask=GradientDrawable().apply { setColor(Color.BLACK);if(oval) shape=GradientDrawable.OVAL else cornerRadius=dp(a,radiusDp).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE),fill,mask)
    }
    /** Press feedback for a whole row inside a sheet (the sheet clips it to its corners). */
    fun rowRipple():Drawable=RippleDrawable(ColorStateList.valueOf(RIPPLE),null,ColorDrawable(Color.BLACK))
    private fun Button.base(a:Activity,label:String,action:()->Unit) {
        text=label;isAllCaps=false;stateListAnimator=null;textSize=18f;typeface=font(a,Weight.SEMIBOLD)
        minHeight=dp(a,56);minimumHeight=dp(a,56);setPadding(dp(a,16),0,dp(a,16),0);setOnClickListener { action() }
    }
    /** Primary action (at most one per screen): solid blue. [icon] is kept for call sites; words carry the meaning. */
    fun bigButton(a:Activity,@Suppress("UNUSED_PARAMETER") icon:String,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,action);setTextColor(ON_ACCENT);background=ripple(a,sheet(a,ACCENT,radius=14))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,16);bottomMargin=dp(a,2) }
    }
    /** Secondary action: neutral fill, ink text, no outline (blue is kept for the one primary place of a screen). */
    fun button(a:Activity,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,action);setTextColor(INK);background=ripple(a,sheet(a,CHIP,radius=14))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,12);bottomMargin=dp(a,2) }
    }
    /** Cleans a screen text: no middle dot, no ellipsis. */
    fun clean(t:String)=t.replace(" · ",", ").replace("·",", ").replace("…","").replace("...","")
    /** Text link button ("전체 보기 ›"), 48dp tall. */
    fun linkButton(a:Activity,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,action);setTextColor(SUBTLE);textSize=17f;background=ripple(a,null,12);minHeight=dp(a,48);minimumHeight=dp(a,48);setPadding(dp(a,10),0,dp(a,8),0)
    }
    /** A round button with only an icon (44dp by default): dial, back, more. Always has a spoken [description]. */
    fun iconButton(a:Activity,kind:String,description:String,sizeDp:Int=44,iconDp:Int=22,tint:Int=INK,fill:Int?=CHIP,action:()->Unit)=ImageView(a).apply {
        setImageDrawable(icon(a,kind,tint,iconDp));scaleType=ImageView.ScaleType.CENTER;contentDescription=description;isClickable=true;isFocusable=true
        background=ripple(a,fill?.let { GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(it) } },oval=true)
        layoutParams=LinearLayout.LayoutParams(dp(a,sizeDp),dp(a,sizeDp));setOnClickListener { action() }
    }
    /** The 44dp phone button of a person or company: opens the dialer with the number typed in (no permission needed).
     * Null when there is no number, so no empty button is drawn. */
    fun dialButton(a:Activity,number:String?,name:String):View? {
        val n=number?.trim().orEmpty().takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch=='+' || ch=='-' } } ?: return null
        return iconButton(a,"dial","$name 님께 전화 걸기",44,22,ACCENT,ACCENT_SOFT) { dial(a,n) }.apply { tag="dial_button" }
    }
    fun dial(a:Activity,number:String) { runCatching { a.startActivity(Intent(Intent.ACTION_DIAL,android.net.Uri.fromParts("tel",number,null))) } }
    /** A white sheet: rounded 20, no edge, no shadow. [rail] tints the sheet instead of drawing a stripe; [warn] is the amber
     * tint (a call that could not be sorted out): there is no red in the app. */
    fun card(a:Activity,warn:Boolean=false,rail:Int?=if(warn) AMBER else null)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL
        background=sheet(a,when(rail) { null -> CARD;AMBER_RAIL,AMBER -> AMBER_SOFT;else -> ACCENT_SOFT },radius=20);clipToOutline=true
        val p=dp(a,16);setPadding(p,p,p,p)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    /** Small status word on a soft fill (e.g. "정리 중 1건"): 15sp SemiBold, corner 8. */
    fun pill(a:Activity,value:String,color:Int)=text(a,value,BADGE,true,color).apply {
        val fill=when(color) { AMBER -> AMBER_SOFT;ACCENT -> ACCENT_SOFT;else -> CHIP }
        background=sheet(a,fill,radius=8);setPadding(dp(a,10),dp(a,3),dp(a,10),dp(a,3));gravity=Gravity.CENTER
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(a,6) }
    }
    /** Neutral badge for a company inside a row. */
    fun chip(a:Activity,value:String)=text(a,value,BADGE,true,SUBTLE).apply {
        background=sheet(a,CHIP,radius=8);setPadding(dp(a,8),dp(a,2),dp(a,8),dp(a,2));gravity=Gravity.CENTER;setLineSpacing(0f,1.1f)
    }
    fun gap(a:Activity,h:Int=12)=View(a).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(a,h)) }
    fun divider(a:Activity)=View(a).apply { setBackgroundColor(LINE);layoutParams=LinearLayout.LayoutParams(-1,dp(a,1)).apply { topMargin=dp(a,12);bottomMargin=dp(a,12) } }
    /** A grey hairline inside a grouped list, inset by [inset] dp on the left. */
    private fun hairline(a:Activity,inset:Int)=View(a).apply { setBackgroundColor(LINE);layoutParams=LinearLayout.LayoutParams(-1,dp(a,1)).apply { marginStart=dp(a,inset);marginEnd=dp(a,16) } }

    /** "오늘 요청", "어제 요청", "10월 28일 요청". */
    fun requested(ms:Long)=if(ms<=0) "" else when(val d=CallRecordStore.kstDay(ms)) {
        today() -> "오늘 요청";today().minusDays(1) -> "어제 요청";else -> DateTimeFormatter.ofPattern("M월 d일",Locale.KOREAN).format(d)+" 요청" }

    /** The one to-do row used on every screen, meant for the inside of a [list]: the title (what to do), below it a company
     * badge and who and when it was asked for, a [다 됐어요] button on the right, and under it the AI's note when there is one.
     * The whole row opens the call it came from when [onOpen] is given. Calm on purpose: no red, no countdown. */
    fun taskRow(a:Activity,title:String,requestedMs:Long,company:String?,person:String?=null,status:String="",onDone:((View)->Unit)?=null,
                onOpen:(()->Unit)?=null,aiNote:String?=null):LinearLayout {
        val outer=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14)) }
        if(onOpen!=null) outer.apply { isClickable=true;isFocusable=true;background=rowRipple();setOnClickListener { onOpen() };contentDescription=null }
        val row=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        words.addView(text(a,title,SUMMARY,true).apply { setPadding(0,0,0,dp(a,4)) })
        val who=listOfNotNull(person?.let { "$it 님" },requested(requestedMs).takeIf { it.isNotEmpty() }).joinToString(", ")
        if(!company.isNullOrBlank() || who.isNotEmpty()) {
            val meta=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
            if(!company.isNullOrBlank()) meta.addView(chip(a,company),LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(a,8) })
            if(who.isNotEmpty()) meta.addView(caption(a,who,SUBTLE).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
            words.addView(meta)
        }
        taskStatus(status).takeIf { it.isNotEmpty() }?.let { words.addView(pill(a,it,ACCENT)) }
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        if(onDone!=null) row.addView(Button(a).apply {
            text="다 됐어요";isAllCaps=false;stateListAnimator=null;textSize=17f;typeface=font(a,Weight.SEMIBOLD);setTextColor(INK);tag="task_done"
            background=ripple(a,sheet(a,CHIP,radius=14))
            setCompoundDrawablesWithIntrinsicBounds(icon(a,"tick",SUBTLE,20),null,null,null);compoundDrawablePadding=dp(a,6)
            minHeight=dp(a,48);minimumHeight=dp(a,48);minWidth=0;minimumWidth=0;setPadding(dp(a,12),0,dp(a,14),0)
            contentDescription="$title, 다 됐어요";setOnClickListener { onDone(outer) }
        },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(a,12) })
        outer.addView(row)
        aiNoteRow(a,aiNote)?.let { outer.addView(it) }
        return outer
    }

    /** The AI's one-line note on an item the user touched (an aside under the row); null when there is none. */
    fun aiNoteRow(a:Activity,text:String?):View? {
        val t=text?.trim().orEmpty();if(t.isEmpty()) return null
        return caption(a,"AI 메모: "+firstSentence(t),SUBTLE).apply { tag="ai_note";setPadding(0,dp(a,6),0,0) }
    }

    /** Rows grouped in one sheet (corner 16) with hairlines between them, inset by [inset] dp past the badge (iOS-style grouped list). */
    fun list(a:Activity,rows:List<View>,inset:Int=72)=card(a).apply {
        background=sheet(a,CARD,radius=16)
        setPadding(0,0,0,0)
        rows.forEachIndexed { i,r -> if(i>0) addView(hairline(a,inset));addView(r) }
    }

    /** Badge for a person (a circle) or a company (a rounded square): the first letter on a soft tint picked from the name. */
    fun avatar(a:Activity,name:String,sizeDp:Int=44,company:Boolean=false)=TextView(a).apply {
        val fgs=listOf("#3268F0","#0B8A5F","#B26B00","#7B4FD6","#D0455C")
        val night=night
        val clean=name.filter { it.isLetter() }
        val fg=Color.parseColor(if(night) listOf("#6B93FF","#3DD598","#F2B84B","#A98BFF","#FF8296")[(clean.hashCode() and 0x7fffffff)%fgs.size] else fgs[(clean.hashCode() and 0x7fffffff)%fgs.size])
        val bg=blend(fg,CARD,if(night) 0.22f else 0.10f)
        text=clean.firstOrNull()?.toString() ?: "";textSize=18f;setTextColor(fg);typeface=font(a,Weight.SEMIBOLD);gravity=Gravity.CENTER
        if(clean.isEmpty()) foreground=android.graphics.drawable.InsetDrawable(icon(a,if(company) "company" else "person",fg,22),dp(a,(sizeDp-22)/2)) // no letter to show
        background=GradientDrawable().apply { if(company) cornerRadius=dp(a,10).toFloat() else shape=GradientDrawable.OVAL;setColor(bg) };importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(dp(a,sizeDp),dp(a,sizeDp)).apply { marginEnd=dp(a,12) }
    }
    private fun blend(fg:Int,bg:Int,amount:Float):Int {
        fun ch(f:Int,b:Int)=(f*amount+b*(1-amount)).toInt().coerceIn(0,255)
        return Color.rgb(ch(Color.red(fg),Color.red(bg)),ch(Color.green(fg),Color.green(bg)),ch(Color.blue(fg),Color.blue(bg)))
    }

    /** One call (or company) inside a list sheet: badge, who and the time on top, one sentence below. The whole row is the button.
     * [company] draws the badge as a rounded square, [avatarName] picks the badge letter when it is not the first word of [who]. */
    fun memoRow(a:Activity,time:String,who:String,memo:String,quiet:Boolean=false,company:Boolean=false,avatarName:String?=null,onOpen:()->Unit)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;isClickable=true;isFocusable=true;background=rowRipple()
        gravity=Gravity.TOP;setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));minimumHeight=dp(a,72);contentDescription="$time $who, $memo. 눌러서 자세히 보기"
        addView(avatar(a,avatarName ?: who.removePrefix("(주)").trim().split(' ').first(),44,company))
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(text(a,who,BODY,true).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        if(time.isNotEmpty()) head.addView(caption(a,time,SUBTLE).apply { setPadding(dp(a,8),0,0,0) })
        words.addView(head)
        words.addView(text(a,memo,BODY,color=if(quiet) MUTED else SUBTLE).apply { setPadding(0,dp(a,2),0,0) })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        setOnClickListener { onOpen() }
    }

    // ---- the undo bar ----------------------------------------------------------------------------------------------------
    /** A dark bar above the bottom bar: [message] and one [action] (default "되돌리기"), gone after [ms] (about five seconds).
     * [onAction] runs when it is tapped, [onGone] when it times out. A newer bar replaces an older one without calling its callbacks. */
    fun snack(a:Activity,message:String,action:String="되돌리기",ms:Long=5000L,onAction:()->Unit,onGone:()->Unit={}) {
        val holder=a.window.decorView.findViewWithTag<LinearLayout>("snack_holder") ?: return
        (holder.getTag(kr.callbrief.R.id.ui_snack_timer) as? Runnable)?.let(holder::removeCallbacks)
        holder.removeAllViews()
        val bar=LinearLayout(a).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(a,16),dp(a,4),dp(a,6),dp(a,4))
            background=sheet(a,c(p.snack),radius=14);tag="snack_bar"
        }
        bar.addView(TextView(a).apply { text=clean(message);textSize=BODY;setTextColor(Color.parseColor("#F2F4F6"));typeface=font(a,Weight.MEDIUM);setLineSpacing(0f,1.3f) },LinearLayout.LayoutParams(0,-2,1f))
        fun close() { (holder.getTag(kr.callbrief.R.id.ui_snack_timer) as? Runnable)?.let(holder::removeCallbacks);holder.setTag(kr.callbrief.R.id.ui_snack_timer,null);holder.removeAllViews();holder.visibility=View.GONE }
        bar.addView(Button(a).apply {
            text=action;isAllCaps=false;stateListAnimator=null;textSize=18f;typeface=font(a,Weight.SEMIBOLD);setTextColor(Color.parseColor("#9DB8FF"));tag="snack_undo"
            background=RippleDrawable(ColorStateList.valueOf(Color.argb(40,255,255,255)),null,ColorDrawable(Color.BLACK));minHeight=dp(a,48);minimumHeight=dp(a,48);setPadding(dp(a,14),0,dp(a,14),0)
            contentDescription="$message, $action";setOnClickListener { close();onAction() }
        })
        holder.addView(bar);holder.visibility=View.VISIBLE
        val timeout=Runnable { close();onGone() }
        holder.setTag(kr.callbrief.R.id.ui_snack_timer,timeout);holder.postDelayed(timeout,ms)
    }

    // ---- numbers and charts ---------------------------------------------------------------------------------------------
    /** Big numbers in one sheet: grey label on top, the number large with its unit small ("5건"). Only one number is blue. */
    fun stats(a:Activity,items:List<Triple<String,String,Int>>,units:List<String> =emptyList())=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL
        items.forEachIndexed { i,(number,label,color) ->
            val unit=units.getOrElse(i) { "" }
            val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;contentDescription="$label $number$unit" }
            col.addView(caption(a,label,SUBTLE).apply { setPadding(0,0,0,dp(a,4)) })
            val big=android.text.SpannableString(number+unit).apply {
                if(unit.isNotEmpty()) { setSpan(android.text.style.AbsoluteSizeSpan(19,true),number.length,length,0);setSpan(android.text.style.ForegroundColorSpan(SUBTLE),number.length,length,0) } }
            col.addView(text(a,"",NUMBER,true,color).apply { text=big;letterSpacing=-0.02f;fontFeatureSettings="tnum";setPadding(0,0,0,0);includeFontPadding=false })
            addView(col,LinearLayout.LayoutParams(0,-2,1f))
        }
    }

    /** Vertical bars (e.g. calls per day this week); the last bar is today and drawn in the accent. */
    class BarChart(a:Activity,private val values:List<Int>,private val labels:List<String>):View(a) {
        private val d=a.resources.displayMetrics.density
        private val bar=Paint(Paint.ANTI_ALIAS_FLAG)
        private val regular=font(a,Weight.MEDIUM);private val strong=font(a,Weight.BOLD)
        private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;textSize=CAPTION*a.resources.displayMetrics.scaledDensity;color=SUBTLE;typeface=regular }
        private val count=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;textSize=CAPTION*a.resources.displayMetrics.scaledDensity;color=INK;typeface=strong }
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
                label.color=if(today) INK else MUTED;label.typeface=if(today) strong else regular
                c.drawText(labels[i],cx,height-4*d,label)
            }
        }
    }

    /** One horizontal bar split into coloured parts, or a filled track with a part over it. */
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
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(a,6),0,dp(a,8));contentDescription="$label ${n}개"
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL }
        head.addView(text(a,label,BODY).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        head.addView(text(a,"${n}개",BODY,true).apply { setPadding(0,0,0,0);fontFeatureSettings="tnum" })
        addView(head)
        addView(StackBar(a,listOf(n to ACCENT,(max-n).coerceAtLeast(0) to ACCENT_SOFT),split=false),LinearLayout.LayoutParams(-1,dp(a,10)).apply { topMargin=dp(a,8) })
    }
    /** Legend line: colour dot, label, count on the right. */
    fun legend(a:Activity,label:String,n:Int,color:Int)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(a,4),0,dp(a,4))
        addView(View(a).apply { background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(color) } },LinearLayout.LayoutParams(dp(a,12),dp(a,12)).apply { marginEnd=dp(a,10) })
        addView(text(a,label,BODY,color=INK).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        addView(text(a,"${n}개",BODY,true,INK).apply { setPadding(0,0,0,0);fontFeatureSettings="tnum" })
    }
    /** A traffic light for a status line: a round dot (green when [good], amber otherwise), then the [label] and its [value]. */
    fun signalRow(a:Activity,label:String,value:String,good:Boolean)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(a,8),0,dp(a,8))
        val color=if(good) OK else AMBER
        contentDescription="$label, $value, "+(if(good) "정상" else "확인이 필요해요")
        addView(View(a).apply { background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(color) };importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(a,12),dp(a,12)).apply { marginEnd=dp(a,12) })
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        words.addView(caption(a,label,SUBTLE).apply { setPadding(0,0,0,0) })
        words.addView(text(a,value,BODY,true).apply { setPadding(0,0,0,0) })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
    }

    // ---- line icons: the set in [CallIcons], stroked 2 units wide on a 24 unit grid -------------------------------------------
    fun icon(a:Activity,kind:String,color:Int,sizeDp:Int=24,stroke:Float=2f):Drawable = object:Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=stroke;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;this.color=color }
        override fun draw(c:Canvas) {
            val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
            CallIcons.paths(kind).forEach { c.drawPath(it,paint) }
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
        CallRecordStore.WAITING_CONSENT -> "설정에서 정리 동의를 켜주세요"
        CallRecordStore.FAILED -> "정리하지 못했어요"
        else -> ""
    }
    /** Like [state], but a call still waiting hours after it was stored says so instead of promising an hour
     * (the hourly analysis stops while the Claude subscription limit is reached). Nights do not count: it runs 07-23. */
    fun state(c:CallRecord,now:Long=System.currentTimeMillis()):String {
        val hour=Instant.ofEpochMilli(now).atZone(CallRecordStore.KST).hour
        return if(c.state==CallRecordStore.TRANSCRIBED && CallInboxConfig.enabled && now-c.createdMs>3*3_600_000L && hour in 9..23)
            "정리가 늦어지고 있어요, 순서대로 정리해요" else state(c.state)
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
