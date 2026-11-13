package kr.callbrief.pipeline

import android.app.Activity
import android.content.Context
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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 16sp: the canvas labels of a chart and a span inside a line; on its own a TextView only through [meta]. */
    const val CAPTION=16f
    /** The META line of a list row (who asked, when, the time of a call): the one place text may be 16sp (v3.3, DESIGN_V4 3.3-2). */
    const val META=16f
    /** An aside under the text it belongs to (a reason, a note): reading size since v3.3. */
    const val ASIDE=18f
    const val BADGE=15f
    const val TAB=16f
    /** Old name of [CAPTION], kept so call sites written before round 3 still compile. */
    const val SMALL=CAPTION

    // ---- colours: the light set and the night set; every colour is read from the current set when a view is built. ------
    private class Palette(val page:String,val card:String,val line:String,val ink:String,val subtle:String,val muted:String,val faint:String,
                          val accent:String,val accentSoft:String,val onAccent:String,val amber:String,val amberSoft:String,val amberRail:String,
                          val ok:String,val mutedBar:String,val chip:String,val snack:String,val pageCaption:String)
    private val LIGHT=Palette("#F2F4F6","#FFFFFF","#EEF0F2","#191F28","#6B7684","#6B7684","#B0B8C1","#3268F0","#EDF2FE","#FFFFFF","#B26B00","#FFF4E0","#F5A524","#0B8A5F","#D9E3FC","#F2F4F6","#2B323B","#5F6B78")
    private val NIGHT=Palette("#111418","#1A1F26","#2A3038","#F2F4F6","#A4ADB8","#8B95A1","#4A525C","#6B93FF","#1F2B4A","#0B1020","#F2B84B","#33291A","#F2B84B","#3DD598","#2C3C66","#232A33","#2E3640","#A4ADB8")
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
    /** Quietest grey that is still text. In the light set it is the same as SUBTLE (#6B7684, 4.6:1 on white; review #29:
     * the old #8B95A1 was 3.0:1); the night set keeps #8B95A1 (5.4:1 on the night sheet). */
    val MUTED:Int get()=c(p.muted)
    /** A caption straight on the grey page (not on a sheet): #5F6B78, 4.9:1 on #F2F4F6. */
    val PAGE_CAPTION:Int get()=c(p.pageCaption)
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

    fun dp(a:Context,v:Int)=(v*a.resources.displayMetrics.density+0.5f).toInt()

    // ---- immediate response (v2.7 r8-3, review 2 "원인 C"): every press shows something at once and counts once ------------
    /** Time window in which a second press of the same view is ignored. */
    const val ONCE_MS=600L
    /** Runs [action] unless [view] was pressed less than [ms] ago (the last press time is kept in a tag of the view). Returns
     * whether it ran. Every listener of the shared parts goes through here, so a quick double tap never opens a screen twice. */
    fun once(view:View,ms:Long=ONCE_MS,action:()->Unit):Boolean {
        val now=android.os.SystemClock.uptimeMillis()
        val last=view.getTag(kr.callbrief.R.id.ui_last_tap) as? Long
        if(last!=null && now-last<ms) return false
        view.setTag(kr.callbrief.R.id.ui_last_tap,now);action();return true
    }
    /** setOnClickListener through [once]. */
    fun onTap(view:View,ms:Long=ONCE_MS,action:()->Unit) { view.setOnClickListener { once(it,ms,action) } }
    /** The button is working: off at once, its words replaced by [words] ("저장하는 중이에요"). [idle] puts it back. */
    fun busy(button:TextView,words:String) {
        if(button.getTag(kr.callbrief.R.id.ui_busy_text)==null) button.setTag(kr.callbrief.R.id.ui_busy_text,button.text)
        button.isEnabled=false;button.text=clean(words);button.alpha=0.6f
    }
    fun idle(button:TextView) {
        (button.getTag(kr.callbrief.R.id.ui_busy_text) as? CharSequence)?.let { button.text=it }
        button.setTag(kr.callbrief.R.id.ui_busy_text,null);button.isEnabled=true;button.alpha=1f
    }
    fun isBusy(button:View)=button.getTag(kr.callbrief.R.id.ui_busy_text)!=null
    /** [busy] for [button], [io] on a background thread, then [idle] and [done] on the main thread. Runs in the lifecycle of the
     * window the button is in (no Activity needed); nothing runs when the button is not on a screen yet. */
    fun <T> work(button:TextView,words:String,io:()->T,done:(Result<T>)->Unit={}) {
        val owner=button.findViewTreeLifecycleOwner() ?: return
        busy(button,words)
        owner.lifecycle.coroutineScope.launch {
            val r=withContext(Dispatchers.IO) { runCatching(io) }
            idle(button);done(r)
        }
    }
    /** Turns a view and everything inside it on or off (setEnabled alone does not reach the children, review #4). */
    fun enableAll(v:View,on:Boolean) { v.isEnabled=on;if(v is android.view.ViewGroup) for(i in 0 until v.childCount) enableAll(v.getChildAt(i),on) }

    /** "불러오는 중이에요" at the end of [column] while a screen reads; [retryCard] or the real content replaces it. */
    fun loading(column:LinearLayout,words:String="불러오는 중이에요"):View {
        val a=column.context
        column.findViewWithTag<View>("loading")?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val v=LinearLayout(a).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="loading";setPadding(dp(a,4),dp(a,24),dp(a,4),dp(a,24))
            addView(android.widget.ProgressBar(a).apply { isIndeterminate=true;indeterminateTintList=ColorStateList.valueOf(ACCENT) },LinearLayout.LayoutParams(dp(a,24),dp(a,24)).apply { marginEnd=dp(a,12) })
            addView(text(a,words,BODY,color=SUBTLE).apply { tag="loading_text";setPadding(0,0,0,0) })
            contentDescription=words
        }
        column.addView(v,LinearLayout.LayoutParams(-1,-2));return v
    }
    /** A read failed: [message] and one [다시 불러오기] button that runs [onRetry] (review #22). Takes the place of [loading]. */
    fun retryCard(column:LinearLayout,message:String="잠시 후 다시 열어주세요",onRetry:()->Unit):View {
        val a=column.context
        listOf("loading","retry_card").forEach { t -> column.findViewWithTag<View>(t)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } }
        val c=card(a,warn=true).apply { tag="retry_card" }
        c.addView(text(a,message,BODY).apply { tag="retry_message" })
        c.addView(button(a,"다시 불러오기") { onRetry() }.apply { tag="retry_button" })
        column.addView(c);return c
    }

    /** Pretendard (SIL OFL), bundled: one typeface family everywhere, weight for hierarchy. */
    enum class Weight(val res:Int) { REGULAR(kr.callbrief.R.font.pretendard_regular),MEDIUM(kr.callbrief.R.font.pretendard_medium),
        SEMIBOLD(kr.callbrief.R.font.pretendard_semibold),BOLD(kr.callbrief.R.font.pretendard_bold) }
    private val faces=HashMap<Weight,Typeface>()
    fun font(a:android.content.Context,w:Weight):Typeface=faces.getOrPut(w) { runCatching { a.resources.getFont(w.res) }.getOrDefault(Typeface.DEFAULT) }

    // ---- page frame: status strip, scrolling band + content, snack bar slot, bottom bar --------------------------------
    /** The five places of the app. The bottom bar shows all of them on every main screen. */
    enum class Tab(val label:String,val icon:String,val tag:String) {
        HOME("홈","home","home_home_button"),CALLS("통화","call","home_calls_button"),TASKS("체크","task","home_tasks_button"),
        COMPANIES("업체","company","home_companies_button"),SETTINGS("설정","gear","home_settings_button")
    }

    /** Builds the frame and returns the content column. Call [band] at the top of every render. With a [tab], the bottom bar
     * is shown with that tab marked; a tap on any tab (the marked one too, to go back to its list) goes to [onTab] (design G1:
     * one Activity, [MainActivity], switches its screens itself; the tab bar never starts an Activity). */
    fun page(a:Activity,tab:Tab?=null,onTab:(Tab)->Unit={}):LinearLayout {
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
        val bar=tab?.let { tabBar(a,it,onTab) }
        if(bar!=null) root.addView(bar,LinearLayout.LayoutParams(-1,-2))
        // Insets: the strip takes the status bar, the bottom bar (or the scroll view) the navigation bar. Nothing is drawn under either bar.
        root.setOnApplyWindowInsetsListener { _,insets ->
            strip.layoutParams=strip.layoutParams.apply { height=insets.systemWindowInsetTop }
            root.setPadding(insets.systemWindowInsetLeft,0,insets.systemWindowInsetRight,0)
            if(bar!=null) bar.setPadding(0,dp(a,6),0,dp(a,6)+insets.systemWindowInsetBottom) else scroll.setPadding(0,0,0,insets.systemWindowInsetBottom)
            insets
        }
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
        eyebrow?.let { band.addView(caption(a,it,PAGE_CAPTION).apply { setPadding(0,0,0,dp(a,2)) }) }
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        head.addView(text(a,title,TITLE,true).apply { tag="page_title";letterSpacing=-0.02f;setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        if(trailing!=null) head.addView(trailing,LinearLayout.LayoutParams(trailing.layoutParams?.width ?: -2,trailing.layoutParams?.height ?: -2).apply { marginStart=dp(a,12) })
        band.addView(head)
        subtitle?.let { band.addView(caption(a,it,PAGE_CAPTION).apply { tag="page_subtitle";setPadding(0,dp(a,6),0,0) }) }
        holder.removeViewAt(0);holder.addView(band,0)
    }

    private fun tabBar(a:Activity,current:Tab,onTab:(Tab)->Unit)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;tag="bottom_bar"
        val wrap=this
        Tab.entries.forEach { t ->
            wrap.addView(Button(a).apply {
                text=t.label;textSize=TAB;isAllCaps=false;stateListAnimator=null;tag=t.tag
                background=RippleDrawable(ColorStateList.valueOf(RIPPLE),null,ColorDrawable(Color.BLACK))
                tabLook(this,t.icon,t==current);compoundDrawablePadding=dp(a,2)
                minHeight=dp(a,60);minimumHeight=dp(a,60);minWidth=0;minimumWidth=0;setPadding(0,dp(a,4),0,dp(a,2))
                setOnClickListener { onTab(t) }
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        markTab(a,this,current)
        // The bar is a sheet with a hairline on top.
        background=android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(LINE) },GradientDrawable().apply { setColor(CARD) })).apply { setLayerInset(1,0,dp(a,1),0,0) }
    }
    /** Marks [current] in the bottom bar without building the bar again: weight and a filled icon say which tab is on (review #29). */
    fun markTab(a:Activity,bar:View?,current:Tab) {
        val row=bar as? LinearLayout ?: return
        Tab.entries.forEach { t ->
            val b=row.findViewWithTag<Button>(t.tag) ?: return@forEach
            val on=t==current
            tabLook(b,t.icon,on)
            b.contentDescription=if(on) "${t.label}, 지금 보는 화면" else t.label
        }
    }
    /** How a bottom-bar tab shows whether it is the current one: by weight and a filled icon, not by a pale colour (review #29).
     * On: ink, SemiBold, the icon filled; off: [MUTED] (4.6:1), Regular, the icon as a line. */
    fun tabLook(b:TextView,iconKind:String,on:Boolean) {
        val color=if(on) INK else MUTED
        b.setTextColor(color);b.typeface=font(b.context,if(on) Weight.SEMIBOLD else Weight.REGULAR)
        b.setCompoundDrawablesWithIntrinsicBounds(null,icon(b.context,iconKind,color,26,filled=on),null,null)
    }

    /** Scroll position of the page now, to put back after a screen is built again (so a list does not jump to the top). */
    fun scrollY(content:View):Int=scrollOf(content)?.scrollY ?: 0
    fun restoreScroll(content:View,y:Int) { val s=scrollOf(content) ?: return;if(y>0) s.post { s.scrollTo(0,y) } }
    /** Puts the page at [y] now (0: the top, when another screen comes to the front). */
    fun scrollTo(content:View,y:Int) { scrollOf(content)?.scrollTo(0,y) }
    private fun scrollOf(content:View):ScrollView? { var v:View?=content;while(v!=null) { if(v is ScrollView) return v;v=v.parent as? View };return null }

    // ---- text ---------------------------------------------------------------------------------------------------------
    fun text(a:Context,value:String,size:Float=BODY,bold:Boolean=false,color:Int=INK)=TextView(a).apply {
        // Text the analysis wrote is shown as is; the screen rules still hold (no middle dot, no ellipsis).
        text=clean(value);textSize=size;setTextColor(color);setLineSpacing(0f,if(size>=24f) 1.15f else 1.3f);setPadding(0,dp(a,2),0,dp(a,2))
        textLocale=Locale.KOREAN // phrase-based line breaking needs the text's language
        if(android.os.Build.VERSION.SDK_INT>=33)lineBreakWordStyle=android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE // wrap Korean at word boundaries
        typeface=font(a,when { !bold -> Weight.REGULAR;size>=24f -> Weight.BOLD;else -> Weight.SEMIBOLD })
        letterSpacing=-0.01f
        // v3.3 (DESIGN_V4 3.3-2): no size is let off by itself any more. Only [meta] (16sp) and the badges (15sp) say they are
        // smaller on purpose (tag ui_min_sp); any other text under 18sp fails the size rule of the screen tests.
    }
    /** An aside to the text above it (a reason, a note, a trade), in grey at reading size (18sp since v3.3; it was 16sp).
     * New screens use [text] or [meta]; tools/check_ui_rules.py warns about new uses. */
    fun caption(a:Context,value:String,color:Int=SUBTLE,bold:Boolean=false)=text(a,value,ASIDE,bold,color)
    /** The META line of a list row only ("김민수 님, 오늘 요청", the time of a call): 16sp, marked so the size rule lets it be. */
    fun meta(a:Context,value:String,color:Int=SUBTLE,bold:Boolean=false)=text(a,value,META,bold,color).apply { setTag(kr.callbrief.R.id.ui_min_sp,META) }
    /** A badge's words: 15sp SemiBold, marked so the size rule lets it be. */
    private fun badgeText(a:Context,value:String,color:Int)=text(a,value,BADGE,true,color).apply { setTag(kr.callbrief.R.id.ui_min_sp,BADGE) }
    fun title(a:Context,value:String)=text(a,value,TITLE,true).apply { letterSpacing=-0.02f;setPadding(0,dp(a,8),0,dp(a,2)) }
    fun heading(a:Context,value:String)=text(a,value,HEADING,true).apply { setPadding(dp(a,4),dp(a,24),0,dp(a,8)) }
    /** Section heading with a text link on the right ("전체 보기 ›"). */
    fun section(a:Context,value:String,link:String?=null,action:(()->Unit)?=null)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(a,4),dp(a,24),0,dp(a,4))
        addView(text(a,value,HEADING,true),LinearLayout.LayoutParams(0,-2,1f))
        if(link!=null) addView(if(action!=null) linkButton(a,link,action) else caption(a,link,PAGE_CAPTION,true).apply { setPadding(0,0,dp(a,4),0) })
    }

    // ---- sheets and buttons ---------------------------------------------------------------------------------------------
    internal fun sheet(a:Context,fill:Int,stroke:Int?=null,radius:Int=14)=GradientDrawable().apply {
        setColor(fill);cornerRadius=dp(a,radius).toFloat();if(stroke!=null)setStroke(dp(a,1),stroke) }
    /** [fill] wrapped in a ripple that is cut to the same rounded shape (or a circle). */
    fun ripple(a:Context,fill:Drawable?,radiusDp:Int=14,oval:Boolean=false):Drawable {
        val mask=GradientDrawable().apply { setColor(Color.BLACK);if(oval) shape=GradientDrawable.OVAL else cornerRadius=dp(a,radiusDp).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE),fill,mask)
    }
    /** Press feedback for a whole row inside a sheet (the sheet clips it to its corners). */
    fun rowRipple():Drawable=RippleDrawable(ColorStateList.valueOf(RIPPLE),null,ColorDrawable(Color.BLACK))
    /** Every button: one press counts once ([once]); with [busyText] the button goes [busy] the moment it is pressed, and the
     * caller puts it back with [idle] when the work is done (or uses [work], which does both). */
    private fun Button.base(a:Context,label:String,busyText:String?=null,action:()->Unit) {
        text=label;isAllCaps=false;stateListAnimator=null;textSize=18f;typeface=font(a,Weight.SEMIBOLD)
        minHeight=dp(a,56);minimumHeight=dp(a,56);setPadding(dp(a,16),0,dp(a,16),0)
        val self=this
        onTap(this) { if(busyText!=null) busy(self,busyText);action() }
    }
    /** Primary action (at most one per screen): solid blue. [icon] is kept for call sites; words carry the meaning. */
    fun bigButton(a:Context,icon:String,label:String,action:()->Unit)=bigButton(a,icon,label,null,action)
    /** [bigButton] that shows [busyText] the moment it is pressed (one line at the call site; [idle] puts it back). */
    fun bigButton(a:Context,@Suppress("UNUSED_PARAMETER") icon:String,label:String,busyText:String?,action:()->Unit)=Button(a).apply {
        base(a,label,busyText,action);setTextColor(ON_ACCENT);background=ripple(a,sheet(a,ACCENT,radius=14))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,16);bottomMargin=dp(a,2) }
    }
    /** Secondary action: neutral fill, ink text, no outline (blue is kept for the one primary place of a screen). */
    fun button(a:Context,label:String,action:()->Unit)=button(a,label,null,action)
    /** [button] that shows [busyText] the moment it is pressed. */
    fun button(a:Context,label:String,busyText:String?,action:()->Unit)=Button(a).apply {
        base(a,label,busyText,action);setTextColor(INK);background=ripple(a,sheet(a,CHIP,radius=14))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,12);bottomMargin=dp(a,2) }
    }
    /** Cleans a screen text: no middle dot, no ellipsis. */
    fun clean(t:String)=t.replace(" · ",", ").replace("·",", ").replace("…","").replace("...","")
    /** Text link button ("전체 보기 ›"), 48dp tall. */
    fun linkButton(a:Context,label:String,action:()->Unit)=Button(a).apply {
        base(a,label,null,action);setTextColor(SUBTLE);textSize=17f;background=ripple(a,null,12);minHeight=dp(a,48);minimumHeight=dp(a,48);setPadding(dp(a,10),0,dp(a,8),0)
    }
    /** A round button with only an icon (44dp by default): dial, back, more. Always has a spoken [description]. */
    fun iconButton(a:Context,kind:String,description:String,sizeDp:Int=44,iconDp:Int=22,tint:Int=INK,fill:Int?=CHIP,action:()->Unit)=ImageView(a).apply {
        setImageDrawable(icon(a,kind,tint,iconDp));scaleType=ImageView.ScaleType.CENTER;contentDescription=description;isClickable=true;isFocusable=true
        background=ripple(a,fill?.let { GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(it) } },oval=true)
        layoutParams=LinearLayout.LayoutParams(dp(a,sizeDp),dp(a,sizeDp));onTap(this,action=action)
    }
    /** A person as the rows show them: a name gets "님", a bare number is shown as a number (010-1234-5678), never "010… 님". */
    fun who(person:String):String {
        val d=person.filter { it.isDigit() }
        if(d.isEmpty() || person.any { it.isLetter() }) return "$person 님"
        return when {
            d.length==11 && d.startsWith("01") -> "${d.take(3)}-${d.substring(3,7)}-${d.takeLast(4)}"
            d.length==10 && d.startsWith("02") -> "02-${d.substring(2,6)}-${d.takeLast(4)}"
            d.length==9 && d.startsWith("02") -> "02-${d.substring(2,5)}-${d.takeLast(4)}"
            d.length==10 -> "${d.take(3)}-${d.substring(3,6)}-${d.takeLast(4)}"
            else -> person
        }
    }
    /** The 44dp phone button of a person or company: opens the dialer with the number typed in (no permission needed).
     * Null when there is no number, so no empty button is drawn. */
    fun dialButton(a:Context,number:String?,name:String):View? {
        val n=number?.trim().orEmpty().takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch=='+' || ch=='-' } } ?: return null
        return iconButton(a,"dial","$name 님께 전화 걸기",44,22,ACCENT,ACCENT_SOFT) { dial(a,n) }.apply { tag="dial_button" }
    }
    fun dial(a:Context,number:String) { runCatching { a.startActivity(Intent(Intent.ACTION_DIAL,android.net.Uri.fromParts("tel",number,null)).apply { if(a !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } }
    /** A white sheet: rounded 20, no edge, no shadow. [rail] tints the sheet instead of drawing a stripe; [warn] is the amber
     * tint (a call that could not be sorted out): there is no red in the app. */
    fun card(a:Context,warn:Boolean=false,rail:Int?=if(warn) AMBER else null)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL
        background=sheet(a,when(rail) { null -> CARD;AMBER_RAIL,AMBER -> AMBER_SOFT;else -> ACCENT_SOFT },radius=20);clipToOutline=true
        val p=dp(a,16);setPadding(p,p,p,p)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    /** Small status word on a soft fill (e.g. "정리 중 1건"): 15sp SemiBold, corner 8. */
    fun pill(a:Context,value:String,color:Int)=badgeText(a,value,color).apply {
        val fill=when(color) { AMBER -> AMBER_SOFT;ACCENT -> ACCENT_SOFT;else -> CHIP }
        background=sheet(a,fill,radius=8);setPadding(dp(a,10),dp(a,3),dp(a,10),dp(a,3));gravity=Gravity.CENTER
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(a,6) }
    }
    /** Neutral badge for a company inside a row. */
    fun chip(a:Context,value:String)=badgeText(a,value,SUBTLE).apply {
        background=sheet(a,CHIP,radius=8);setPadding(dp(a,8),dp(a,2),dp(a,8),dp(a,2));gravity=Gravity.CENTER;setLineSpacing(0f,1.1f)
    }
    fun gap(a:Context,h:Int=12)=View(a).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(a,h)) }
    fun divider(a:Context)=View(a).apply { setBackgroundColor(LINE);layoutParams=LinearLayout.LayoutParams(-1,dp(a,1)).apply { topMargin=dp(a,12);bottomMargin=dp(a,12) } }
    /** A grey hairline inside a grouped list, inset by [inset] dp on the left. */
    private fun hairline(a:Context,inset:Int)=View(a).apply { setBackgroundColor(LINE);layoutParams=LinearLayout.LayoutParams(-1,dp(a,1)).apply { marginStart=dp(a,inset);marginEnd=dp(a,16) } }

    /** "오늘 요청", "어제 요청", "10월 28일 요청". */
    fun requested(ms:Long)=if(ms<=0) "" else when(val d=CallRecordStore.kstDay(ms)) {
        today() -> "오늘 요청";today().minusDays(1) -> "어제 요청";else -> DateTimeFormatter.ofPattern("M월 d일",Locale.KOREAN).format(d)+" 요청" }

    /** A horizontal row that stacks itself when it is too narrow, so no text is ever squeezed into a column of single letters.
     * Child [flex] takes the room left by the others; the row stays side by side while that room is at least
     * [minFraction] of the width (or all the flex child needs), otherwise each child gets its own line, the later ones at
     * the end ([endWhenStacked]) or the start. Works for every screen width and font size, because it decides at measure time. */
    class FitRow(a:Context,private val flex:Int=0,private val minFraction:Float=0.5f,private val gapDp:Int=12,private val endWhenStacked:Boolean=false):LinearLayout(a) {
        private val gapPx=dp(a,gapDp);private val stackGapPx=dp(a,8)
        /** True while the children are side by side (for tests). */
        var sideBySide=true
            private set
        override fun onMeasure(widthSpec:Int,heightSpec:Int) {
            val shown=(0 until childCount).map(::getChildAt).filter { it.visibility!=View.GONE }
            val f=getChildAt(flex)
            sideBySide=if(MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED || shown.size<2 || f==null || f.visibility==View.GONE) true else {
                val avail=MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight
                val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
                val others=shown.filter { it!==f }.sumOf { it.measure(free,free);it.measuredWidth }+gapPx*(shown.size-1)
                f.measure(free,free)
                avail-others>=minOf(f.measuredWidth,(avail*minFraction).toInt())
            }
            orientation=if(sideBySide) HORIZONTAL else VERTICAL
            shown.forEachIndexed { i,v ->
                val lp=v.layoutParams as LayoutParams
                if(sideBySide) { lp.width=if(v===f) 0 else -2;lp.weight=if(v===f) 1f else 0f;lp.marginStart=if(i>0) gapPx else 0;lp.topMargin=0;lp.gravity=Gravity.CENTER_VERTICAL }
                else { lp.width=if(v===f) -1 else -2;lp.weight=0f;lp.marginStart=0;lp.topMargin=if(i>0) stackGapPx else 0
                    lp.gravity=if(i>0 && endWhenStacked) Gravity.END else Gravity.START
                    if(i>0 && v.getTag(kr.callbrief.R.id.ui_min_sp)!=null) lp.topMargin=dp(context,2) }
            }
            super.onMeasure(widthSpec,heightSpec)
        }
    }

    /** The one to-do row used on every screen, meant for the inside of a [list]: the title (what to do) across the whole row,
     * below it a company badge with who and when it was asked for, and the [다 됐어요] button on the right of that line
     * (or under it, at the end, when the screen is narrow or the letters large); the AI's note at the bottom when there is one.
     * The whole row opens the call it came from when [onOpen] is given. Calm on purpose: no red, no countdown. */
    fun taskRow(a:Context,title:String,requestedMs:Long,company:String?,person:String?=null,status:String="",onDone:((View)->Unit)?=null,
                onOpen:(()->Unit)?=null,aiNote:String?=null,unsure:Boolean=false,
                /** v4.0 r20-2: [틀렸어요] beside [다 됐어요] (only with [onDone]). */ onWrong:((View)->Unit)?=null):LinearLayout {
        val outer=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14)) }
        if(onOpen!=null) outer.apply { isClickable=true;isFocusable=true;background=rowRipple();onTap(this,action=onOpen);contentDescription=null }
        outer.addView(text(a,title,SUMMARY,true).apply { tag="task_title";setPadding(0,0,0,0) })
        val who=listOfNotNull(person?.let { who(it) },requested(requestedMs).takeIf { it.isNotEmpty() }).joinToString(", ")
        // Badge and words side by side while the words still get most of the line; else the words go under the badge.
        val meta=if(company.isNullOrBlank() && who.isEmpty()) null else FitRow(a,flex=if(company.isNullOrBlank()) 0 else 1,minFraction=0.6f,gapDp=8).apply {
            tag="task_meta"
            if(!company.isNullOrBlank()) addView(chip(a,company).apply { tag="task_company" },LinearLayout.LayoutParams(-2,-2))
            if(who.isNotEmpty()) addView(meta(a,who,SUBTLE).apply { tag="task_who";setPadding(0,0,0,0) },LinearLayout.LayoutParams(-2,-2))
        }
        val state=stateWords(status).takeIf { it.isNotEmpty() }?.let { pill(a,it,ACCENT) }
        // v3.3: "확인 필요" (a number or date that may have been misheard) beside the state, in words.
        val pill=if(!unsure) state else LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="task_badges"
            addView(unsureBadge(a),LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(a,6);marginEnd=dp(a,8) })
            state?.let { addView(it) } }
        val done=onDone?.let { taskButtons(a,title,outer,it,onWrong) }
        val left=if(meta!=null && pill!=null) LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;addView(meta);addView(pill) } else meta ?: pill
        if(done!=null) {
            // The button stays on the badge line only while that line fits whole; otherwise it moves under it, at the end.
            val line=FitRow(a,flex=0,minFraction=1f,gapDp=12,endWhenStacked=true).apply { tag="task_line" }
            line.addView(left ?: View(a),LinearLayout.LayoutParams(-2,-2))
            line.addView(done,LinearLayout.LayoutParams(-2,-2))
            outer.addView(line,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,8) })
        } else if(left!=null) outer.addView(left,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,8) })
        aiNoteRow(a,aiNote)?.let { outer.addView(it) }
        return outer
    }

    /** v4.0 r20-2 (DESIGN_V7 6): what [틀렸어요] says and the words of its undo bar. */
    const val WRONG_LABEL="틀렸어요"
    /** [다 됐어요] of a check row ([row] goes to [onDone]); with [onWrong] a quieter [틀렸어요] sits before it on the same line, one
     * tap and no reason asked. Both at least 48dp tall. */
    fun taskButtons(a:Context,title:String,row:View,onDone:(View)->Unit,onWrong:((View)->Unit)?=null):View {
        val done=Button(a).apply {
            text="다 됐어요";isAllCaps=false;stateListAnimator=null;textSize=17f;typeface=font(a,Weight.SEMIBOLD);setTextColor(INK);tag="task_done"
            maxLines=1;isSingleLine=true;background=ripple(a,sheet(a,CHIP,radius=14))
            setCompoundDrawablesWithIntrinsicBounds(icon(a,"tick",SUBTLE,20),null,null,null);compoundDrawablePadding=dp(a,6)
            minHeight=dp(a,48);minimumHeight=dp(a,48);minWidth=0;minimumWidth=0;setPadding(dp(a,12),0,dp(a,14),0)
            contentDescription="$title, 다 됐어요";onTap(this) { onDone(row) }
        }
        if(onWrong==null) return done
        val wrong=Button(a).apply {
            text=WRONG_LABEL;isAllCaps=false;stateListAnimator=null;textSize=17f;typeface=font(a,Weight.MEDIUM);setTextColor(SUBTLE);tag="task_wrong"
            maxLines=1;isSingleLine=true;background=ripple(a,null,14)
            minHeight=dp(a,48);minimumHeight=dp(a,48);minWidth=0;minimumWidth=0;setPadding(dp(a,12),0,dp(a,12),0)
            contentDescription="$title, 틀렸어요. 잘못 뽑힌 일이면 눌러 주세요";onTap(this) { onWrong(row) }
        }
        return LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="task_buttons"
            addView(wrong,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(a,8) });addView(done,LinearLayout.LayoutParams(-2,-2)) }
    }

    /** The AI's one-line note on an item the user touched (an aside under the row); null when there is none. */
    fun aiNoteRow(a:Context,text:String?):View? {
        val t=text?.trim().orEmpty();if(t.isEmpty()) return null
        return caption(a,"AI 메모: "+firstSentence(t),SUBTLE).apply { tag="ai_note";setPadding(0,dp(a,6),0,0) }
    }

    /** Rows grouped in one sheet (corner 16) with hairlines between them, inset by [inset] dp past the badge (iOS-style grouped list). */
    fun list(a:Context,rows:List<View>,inset:Int=72)=card(a).apply {
        background=sheet(a,CARD,radius=16)
        setPadding(0,0,0,0)
        addRows(a,this,rows,inset)
    }
    /** More rows at the end of a [list] sheet (a long day is built a screenful at a time). */
    fun addRows(a:Context,list:LinearLayout,rows:List<View>,inset:Int=72) { rows.forEach { r -> if(list.childCount>0) list.addView(hairline(a,inset));list.addView(r) } }

    /** Badge for a person (a circle) or a company (a rounded square): the first letter on a soft tint picked from the name. */
    fun avatar(a:Context,name:String,sizeDp:Int=44,company:Boolean=false)=TextView(a).apply {
        val fgs=listOf("#3268F0","#0B8A5F","#B26B00","#7B4FD6","#8A5A2B")
        val night=night
        val clean=name.filter { it.isLetter() }
        val fg=Color.parseColor(if(night) listOf("#6B93FF","#3DD598","#F2B84B","#A98BFF","#D9A66B")[(clean.hashCode() and 0x7fffffff)%fgs.size] else fgs[(clean.hashCode() and 0x7fffffff)%fgs.size])
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
    fun memoRow(a:Context,time:String,who:String,memo:String,quiet:Boolean=false,company:Boolean=false,avatarName:String?=null,onOpen:()->Unit)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;isClickable=true;isFocusable=true;background=rowRipple()
        gravity=Gravity.TOP;setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));minimumHeight=dp(a,72);contentDescription="$time $who, $memo. 눌러서 자세히 보기"
        addView(avatar(a,avatarName ?: who.removePrefix("(주)").trim().split(' ').first(),44,company))
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        // The time sits on the right of the name while the name fits on one line next to it; otherwise the name keeps the
        // whole line (a phone number or "업체 사람" is never broken) and the time goes under it as an aside.
        val head=FitRow(a,flex=0,minFraction=1f,gapDp=10).apply { tag="memo_head" }
        head.addView(text(a,who,BODY,true).apply { tag="memo_who";setPadding(0,0,0,0) },LinearLayout.LayoutParams(-2,-2))
        if(time.isNotEmpty()) head.addView(meta(a,time,SUBTLE).apply { tag="memo_time";maxLines=1;setPadding(0,0,0,0) },LinearLayout.LayoutParams(-2,-2))
        words.addView(head)
        words.addView(text(a,memo,BODY,color=if(quiet) MUTED else SUBTLE).apply { setPadding(0,dp(a,2),0,0) })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        onTap(this,action=onOpen)
    }

    /** A call-log line with nothing to open (a missed call, a call with no recording, a personal call): badge, who and
     * the time on top, [label] below in [labelColor], and an optional [trailing] button (다시 걸기) under them. */
    fun logRow(a:Context,time:String,who:String,label:String,labelColor:Int,avatarName:String?=null,trailing:View?=null)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP;setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));minimumHeight=dp(a,72)
        contentDescription="$time $who, $label"
        addView(avatar(a,avatarName ?: who,44))
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val head=FitRow(a,flex=0,minFraction=1f,gapDp=10).apply { tag="memo_head" }
        head.addView(text(a,who,BODY,true).apply { tag="memo_who";setPadding(0,0,0,0) },LinearLayout.LayoutParams(-2,-2))
        head.addView(meta(a,time,SUBTLE).apply { tag="memo_time";maxLines=1;setPadding(0,0,0,0) },LinearLayout.LayoutParams(-2,-2))
        words.addView(head)
        words.addView(text(a,label,BODY,color=labelColor).apply { tag="log_label";setPadding(0,dp(a,2),0,0) })
        // The button goes under the words, so a long number or name always keeps its whole line.
        trailing?.let { words.addView(it,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(a,8) }) }
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
    }
    /** "다시 걸기": a 48dp soft-blue button with the phone icon that opens the dialer with [number] typed in ([dial]). Null
     * when there is no number to call. */
    fun redialButton(a:Context,number:String?,name:String):View? {
        val n=number?.trim().orEmpty().takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch=='+' || ch=='-' } } ?: return null
        return Button(a).apply {
            text="다시 걸기";isAllCaps=false;stateListAnimator=null;textSize=CAPTION;typeface=font(a,Weight.SEMIBOLD);setTextColor(ACCENT)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(a,"dial",ACCENT,18),null,null,null);compoundDrawablePadding=dp(a,6)
            background=ripple(a,sheet(a,ACCENT_SOFT,radius=14));minHeight=dp(a,48);minimumHeight=minHeight;minWidth=0;minimumWidth=0
            setPadding(dp(a,14),0,dp(a,14),0);contentDescription="$name 님께 다시 걸기";tag="redial_button";onTap(this) { dial(a,n) }
        }
    }

    // ---- bottom sheet (design G2: no AlertDialog) -------------------------------------------------------------------------
    /** A sheet that rises from the bottom of the screen instead of a small centred dialog: a grab bar, a [title] (20sp
     * SemiBold), an optional [message] (body 19sp), an optional [body] view (an input), then one big button per entry of
     * [options] (a list to pick from) and the actions stacked under each other, full width, 56dp tall: [primary] in blue on
     * top, [secondary] ("닫기", "취소") in grey below. Every button closes the sheet first and then does its work; back and a
     * touch outside the sheet close it without doing anything. Colours follow the phone's night setting.
     * [validate] runs before [primary] does anything: when it returns words, the sheet stays open and shows them on an amber
     * tint above the buttons (review #26: an empty title closed the sheet and lost what was picked). When [body] holds a text
     * field, it gets the focus and the keyboard comes up by itself. Tags for tests:
     * sheet_root, sheet_title, sheet_message, sheet_problem, sheet_option_<i>, sheet_primary, sheet_secondary. */
    fun sheetDialog(a:Context,title:String,message:String?=null,body:View?=null,options:List<String> =emptyList(),onOption:((Int)->Unit)?=null,
                    primary:String?=null,onPrimary:(()->Unit)?=null,secondary:String?="닫기",onSecondary:(()->Unit)?=null,
                    validate:(()->String?)?=null):android.app.Dialog {
        applyTheme(a)
        val d=android.app.Dialog(a,if(night) android.R.style.Theme_DeviceDefault_Dialog_NoActionBar else android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val side=dp(a,20)
        val sheet=LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL;tag="sheet_root";setPadding(side,dp(a,10),side,dp(a,16))
        }
        // The grab bar says "this came up from below and goes back down".
        sheet.addView(View(a).apply { background=sheet(a,FAINT,radius=2) },LinearLayout.LayoutParams(dp(a,40),dp(a,4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(a,16) })
        sheet.addView(text(a,title,HEADING,true).apply { tag="sheet_title";setPadding(0,0,0,dp(a,4)) })
        message?.let { sheet.addView(text(a,it,BODY,false,SUBTLE).apply { tag="sheet_message";setPadding(0,dp(a,6),0,0) }) }
        body?.let { sheet.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,12) }) }
        // The words of a failed [validate], hidden until there is a problem; it sits right above the buttons.
        val problem=text(a,"",BODY,false,INK).apply {
            tag="sheet_problem";visibility=View.GONE;background=sheet(a,AMBER_SOFT,radius=12);setPadding(dp(a,14),dp(a,10),dp(a,14),dp(a,10))
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        sheet.addView(problem,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,12) })
        fun act(view:Button,tag:String,check:Boolean=false,then:(()->Unit)?):Button=view.apply { this.tag=tag;onTap(this) {
            val wrong=if(check) validate?.invoke()?.takeIf { it.isNotBlank() } else null
            if(wrong!=null) { problem.text=clean(wrong);problem.visibility=View.VISIBLE;return@onTap }
            d.dismiss();then?.invoke() } }
        options.forEachIndexed { i,label ->
            sheet.addView(act(button(a,label) {},"sheet_option_$i") { onOption?.invoke(i) }.apply {
                gravity=Gravity.CENTER_VERTICAL or Gravity.START;setPadding(dp(a,18),dp(a,10),dp(a,18),dp(a,10))
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,if(i==0) 16 else 8) })
        }
        primary?.let { sheet.addView(act(bigButton(a,"",it) {},"sheet_primary",check=true,then=onPrimary),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,20) }) }
        secondary?.let { sheet.addView(act(button(a,it) {},"sheet_secondary",then=onSecondary),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,if(primary==null && options.isEmpty()) 20 else 10) }) }
        // Long contents (many numbers, a long result) scroll inside the sheet; the navigation bar and keyboard never cover it.
        val scroll=ScrollView(a).apply { isFillViewport=true;addView(sheet,android.widget.FrameLayout.LayoutParams(-1,-2)) }
        val frame=android.widget.FrameLayout(a).apply { addView(scroll) }
        frame.background=GradientDrawable().apply { setColor(CARD);val r=dp(a,20).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f) }
        frame.setOnApplyWindowInsetsListener { v,insets -> v.setPadding(0,0,0,insets.systemWindowInsetBottom);insets }
        d.setContentView(frame)
        d.setCancelable(true);d.setCanceledOnTouchOutside(true)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(if(night) 0.6f else 0.4f)
            val field=body?.let { firstField(it) }
            @Suppress("DEPRECATION") setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                (if(field!=null) android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0))
            decorView.setPadding(0,0,0,0)
        }
        d.show()
        // setLayout again after show: some phones reset the width of a dialog window when it is first shown.
        d.window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        body?.let { firstField(it) }?.requestFocus()
        return d
    }
    /** The first text field in [v] (itself or inside it), for the sheet to focus. */
    internal fun firstField(v:View):android.widget.EditText? {
        if(v is android.widget.EditText) return v
        if(v is android.view.ViewGroup) for(i in 0 until v.childCount) firstField(v.getChildAt(i))?.let { return it }
        return null
    }
    /** A text field for a sheet: body size, 56dp tall, single line. */
    fun sheetInput(a:Context,value:String="",hint:String?=null,tag:String?=null)=android.widget.EditText(a).apply {
        setText(value);setSelection(value.length);hint?.let { this.hint=it };textSize=BODY;isSingleLine=true
        inputType=android.text.InputType.TYPE_CLASS_TEXT;typeface=font(a,Weight.REGULAR);setTextColor(INK);setHintTextColor(MUTED)
        minHeight=dp(a,56);setPadding(dp(a,16),0,dp(a,16),0);background=sheet(a,CHIP,radius=14);this.tag=tag
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

    /** Takes the undo bar away at once without calling its callbacks (a [다 됐어요] that could not be saved). */
    fun hideSnack(a:Activity) {
        val holder=a.window.decorView.findViewWithTag<LinearLayout>("snack_holder") ?: return
        (holder.getTag(kr.callbrief.R.id.ui_snack_timer) as? Runnable)?.let(holder::removeCallbacks)
        holder.setTag(kr.callbrief.R.id.ui_snack_timer,null);holder.removeAllViews();holder.visibility=View.GONE
    }

    // ---- numbers and charts ---------------------------------------------------------------------------------------------
    /** Big numbers in one sheet: grey label on top, the number large with its unit small ("5건"). Only one number is blue.
     * The columns share the width equally; when a label would not fit its column on one line (a narrow phone, large letters),
     * the numbers become rows instead (label on the left, number on the right), so labels never run into each other. */
    fun stats(a:Context,items:List<Triple<String,String,Int>>,units:List<String> =emptyList()):LinearLayout=Stats(a,items,units)
    class Stats(a:Context,items:List<Triple<String,String,Int>>,units:List<String>):LinearLayout(a) {
        private val labels=ArrayList<TextView>();private val cols=ArrayList<LinearLayout>()
        private val gapPx=dp(a,12)
        /** True while the numbers sit in columns (for tests). */
        var columns=true
            private set
        init {
            orientation=HORIZONTAL
            items.forEachIndexed { i,(number,label,color) ->
                val unit=units.getOrElse(i) { "" }
                val col=LinearLayout(a).apply { orientation=VERTICAL;contentDescription="$label $number$unit";tag="stat_$i" }
                val l=caption(a,label,SUBTLE).apply { setPadding(0,0,0,dp(a,4));tag="stat_label_$i" }
                col.addView(l,LayoutParams(-2,-2))
                val big=android.text.SpannableString(number+unit).apply {
                    if(unit.isNotEmpty()) { setSpan(android.text.style.RelativeSizeSpan(19f/NUMBER),number.length,length,0);setSpan(android.text.style.ForegroundColorSpan(SUBTLE),number.length,length,0) } }
                col.addView(text(a,"",NUMBER,true,color).apply { text=big;letterSpacing=-0.02f;fontFeatureSettings="tnum";setPadding(0,0,0,0);includeFontPadding=false;maxLines=1 },LayoutParams(-2,-2))
                labels+=l;cols+=col;addView(col,LayoutParams(0,-2,1f))
            }
        }
        override fun onMeasure(widthSpec:Int,heightSpec:Int) {
            if(MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED && cols.isNotEmpty()) {
                val each=(MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight-gapPx*(cols.size-1))/cols.size
                columns=labels.all { it.paint.measureText(it.text.toString())<=each }
            }
            orientation=if(columns) HORIZONTAL else VERTICAL
            cols.forEachIndexed { i,col ->
                val lp=col.layoutParams as LayoutParams
                if(columns) { col.orientation=VERTICAL;col.gravity=Gravity.START;lp.width=0;lp.weight=1f;lp.marginStart=if(i>0) gapPx else 0;lp.topMargin=0
                    (labels[i].layoutParams as LayoutParams).apply { width=-2;weight=0f } }
                else { col.orientation=HORIZONTAL;col.gravity=Gravity.CENTER_VERTICAL;lp.width=-1;lp.weight=0f;lp.marginStart=0;lp.topMargin=if(i>0) dp(context,10) else 0
                    (labels[i].layoutParams as LayoutParams).apply { width=0;weight=1f } }
            }
            super.onMeasure(widthSpec,heightSpec)
        }
    }

    /** Vertical bars (e.g. calls per day this week); the last bar is today and drawn in the accent. */
    class BarChart(a:Context,private val values:List<Int>,private val labels:List<String>):View(a) {
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
    class StackBar(a:Context,private val parts:List<Pair<Int,Int>>,private val split:Boolean=true):View(a) {
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
    fun barRow(a:Context,label:String,n:Int,max:Int)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(a,6),0,dp(a,8));contentDescription="$label ${n}개"
        val head=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL }
        head.addView(text(a,label,BODY).apply { setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        head.addView(text(a,"${n}개",BODY,true).apply { setPadding(0,0,0,0);fontFeatureSettings="tnum" })
        addView(head)
        addView(StackBar(a,listOf(n to ACCENT,(max-n).coerceAtLeast(0) to ACCENT_SOFT),split=false),LinearLayout.LayoutParams(-1,dp(a,10)).apply { topMargin=dp(a,8) })
    }
    /** A traffic light for a status line: the [label] as a grey aside on top, then the [value] in SemiBold with a round dot
     * in front of it (green when [good], amber otherwise). The same shape at every width, so the lights of one sheet line up. */
    fun signalRow(a:Context,label:String,value:String,good:Boolean,onPress:(()->Unit)?=null)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(a,8),0,dp(a,8))
        // A light that names a place on the screen goes there when it is pressed (the screen does the scrolling).
        if(onPress!=null) { isClickable=true;isFocusable=true;background=ripple(a,null,12);onTap(this,action=onPress) }
        val color=if(good) OK else AMBER
        contentDescription="$label, $value, "+(if(good) "정상" else "확인이 필요해요")
        val indent=dp(a,10+10)
        addView(caption(a,label,SUBTLE).apply { setPadding(indent,0,0,0) })
        addView(text(a,value,BODY,true).apply {
            setPadding(0,0,0,0);compoundDrawablePadding=dp(a,10)
            val dot=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(color);setSize(dp(a,10),dp(a,10)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot,null,null,null)
        })
    }

    // ---- line icons: the set in [CallIcons], stroked 2 units wide on a 24 unit grid -------------------------------------------
    /** [filled]: the first (outline) path is also filled, for the current tab of the bottom bar. */
    fun icon(a:Context,kind:String,color:Int,sizeDp:Int=24,stroke:Float=2f,filled:Boolean=false):Drawable = object:Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=stroke;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;this.color=color }
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL;this.color=color;alpha=0x40 }
        override fun draw(c:Canvas) {
            val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
            if(filled) CallIcons.paths(kind).firstOrNull()?.let { c.drawPath(it,fill) }
            CallIcons.paths(kind).forEach { c.drawPath(it,paint) }
            c.restoreToCount(save)
        }
        override fun setAlpha(alpha:Int) { paint.alpha=alpha;fill.alpha=alpha*0x40/0xFF }
        override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter;fill.colorFilter=filter }
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
    /** A memo line: the first sentence of a summary, so lists never cut text off mid-way; the rest is one tap away. */
    fun firstSentence(summary:String):String {
        val s=summary.trim();val end=Regex("(요|다|음|함)\\.|[.!?](\\s|$)").find(s)?.range?.last ?: return s
        return s.substring(0,end+1).trim()
    }
    fun overdue(due:String?)=runCatching{LocalDate.parse(due?.trim())<today()}.getOrDefault(false)
    fun duration(ms:Long)=if(ms<60_000) "${ms/1000}초" else "${ms/60000}분"+((ms/1000)%60).let { if(it>0) " ${it}초" else "" }
    /** Record state in plain words; no English codes ever reach the screen. */
    fun state(state:String)=when(state) {
        // v3.5 r15-0: no promise of when (the analysis runs 07-23 and may wait for the subscription limit).
        CallRecordStore.TRANSCRIBED -> "정리하고 있어요"
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

    // ---- "확인 필요" (DESIGN_V4 3.1-2; moved here from the check rows in v3.3 r13-3) ----------------------------------------
    /** An item whose numbers or date may have been misheard (tentative). */
    const val UNSURE="확인 필요"
    /** Letters of the badge: the same dark ink on the amber of both themes (well above 4.5:1). */
    private const val BADGE_INK=0xFF191F28.toInt()
    /** "확인 필요": 18sp bold, dark letters on [AMBER_RAIL]; the words themselves carry the meaning, not the colour. */
    fun unsureBadge(a:Context)=text(a,UNSURE,18f,true,BADGE_INK).apply {
        tag="task_unsure";background=sheet(a,AMBER_RAIL,radius=8);setPadding(dp(a,10),dp(a,2),dp(a,10),dp(a,2))
        gravity=Gravity.CENTER;contentDescription="숫자나 날짜를 확인해 주세요" }
    /** The pill of a check item's state: "끝났는지 확인" for an item the AI thinks is finished (so it is not mixed up with [UNSURE]). */
    fun stateWords(status:String)=if(status=="needs_confirmation") "끝났는지 확인" else taskStatus(status)
    /** [title] without a leading "김민수 님께" / "김민수 과장에게" when it names [person], the one the META line shows (DESIGN_V4 3.1-4,
     * until rules v7 stop writing it). Nothing else of the title changes; a title that would be empty stays whole. */
    fun shortTitle(title:String,person:String?):String {
        val who=CallRecordStore.noNumber(person)?.trim()?.takeIf { it.isNotEmpty() } ?: return title
        val names=(listOf(who,NameKey.withoutTitle(who))+who.split(Regex("\\s+"))).map { it.trim() }.filter { it.length>=2 }.distinct().sortedByDescending { it.length }
        val roles=NameKey.TITLES.filter { it!="님" && it!="씨" }.joinToString("|") { Regex.escape(it) }
        for(n in names) {
            val re=Regex("^\\s*"+Regex.escape(n)+"\\s*(?:(?:$roles)\\s*)?(?:님|씨)?\\s*(?:께서|께|에게|한테)\\s+")
            val m=re.find(title) ?: continue
            return title.substring(m.range.last+1).trim().takeIf { it.isNotEmpty() } ?: title
        }
        return title
    }
}
