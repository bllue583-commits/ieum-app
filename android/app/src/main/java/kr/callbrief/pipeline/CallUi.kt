package kr.callbrief.pipeline

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One look for every call screen. Blueprint blue on a cool concrete-grey page, white sheets with a hairline
 * edge (no shadows), safety amber only for "due today". Sized for a reader in his 50s: large sp text that
 * scales with the system font size, touch targets of 60dp and more, plain Korean. */
internal object CallUi {
    const val TITLE=28f
    const val HEADING=21f
    const val SUMMARY=20f
    const val BODY=19f
    const val SMALL=18f
    val INK=Color.parseColor("#1C2731")
    val SUBTLE=Color.parseColor("#56636E")
    val ACCENT=Color.parseColor("#1F5C99")
    val ACCENT_SOFT=Color.parseColor("#E6EEF6")
    val AMBER=Color.parseColor("#A86F00")
    val AMBER_RAIL=Color.parseColor("#E3A21A")
    val WARN=Color.parseColor("#B23A2B")
    val OK=Color.parseColor("#2E7656")
    val PAGE=Color.parseColor("#F2F4F5")
    val CARD=Color.parseColor("#FFFFFF")
    val LINE=Color.parseColor("#DCE1E5")
    val CARD_WARN=CARD

    fun dp(a:Activity,v:Int)=(v*a.resources.displayMetrics.density+0.5f).toInt()

    /** Device tests only: lets UiAutomation capture the screens. The app itself never sets it. */
    @Volatile internal var allowCaptureForTests=false
    /** A scrolling page with system-bar padding; returns the column to add views to. */
    fun page(a:Activity):LinearLayout {
        if(!allowCaptureForTests)a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        a.window.decorView.setBackgroundColor(PAGE)
        if(android.os.Build.VERSION.SDK_INT>=30) a.window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        val column=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(a).apply { setBackgroundColor(PAGE);isFillViewport=true;tag="page_scroll";clipToPadding=false }
        val p=dp(a,20);scroll.setPadding(p,p,p,p)
        scroll.setOnApplyWindowInsetsListener { v,insets -> v.setPadding(p+insets.systemWindowInsetLeft,insets.systemWindowInsetTop+dp(a,12),p+insets.systemWindowInsetRight,insets.systemWindowInsetBottom+p);insets }
        scroll.addView(column);a.setContentView(scroll);return column
    }
    fun text(a:Activity,value:String,size:Float=BODY,bold:Boolean=false,color:Int=INK)=TextView(a).apply {
        text=value;textSize=size;setTextColor(color);setLineSpacing(0f,1.25f);setPadding(0,dp(a,4),0,dp(a,4))
        textLocale=Locale.KOREAN // phrase-based line breaking needs the text's language
        if(android.os.Build.VERSION.SDK_INT>=33)lineBreakWordStyle=android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE // wrap Korean at word boundaries
        if(bold)setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD))
    }
    fun title(a:Activity,value:String)=text(a,value,TITLE,true).apply { letterSpacing=-0.01f;setPadding(0,dp(a,8),0,dp(a,2)) }
    fun heading(a:Activity,value:String)=text(a,value,HEADING,true).apply { setPadding(0,dp(a,24),0,dp(a,6)) }

    private fun sheet(a:Activity,fill:Int,stroke:Int?=null,radius:Int=14)=GradientDrawable().apply {
        setColor(fill);cornerRadius=dp(a,radius).toFloat();if(stroke!=null)setStroke(dp(a,1),stroke) }
    /** Primary action: solid blueprint blue, 64dp. [icon] is ignored (kept for call sites); words carry the meaning. */
    fun bigButton(a:Activity,@Suppress("UNUSED_PARAMETER") icon:String,label:String,action:()->Unit)=Button(a).apply {
        text=label;textSize=21f;isAllCaps=false;stateListAnimator=null;setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD))
        setTextColor(Color.WHITE);background=sheet(a,ACCENT,radius=12);minHeight=dp(a,64);gravity=Gravity.CENTER
        setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,8);bottomMargin=dp(a,8) }
    }
    /** Secondary action: soft blue fill, 60dp. */
    fun button(a:Activity,label:String,action:()->Unit)=Button(a).apply {
        text=label;textSize=SMALL+1;isAllCaps=false;stateListAnimator=null;setTextColor(ACCENT);background=sheet(a,ACCENT_SOFT,radius=12);minHeight=dp(a,60)
        setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD))
        setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    /** A white sheet with a hairline edge. [rail] draws a schedule-bar stripe on the left (urgency). */
    fun card(a:Activity,warn:Boolean=false,rail:Int?=if(warn) WARN else null)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL
        val base=sheet(a,CARD,LINE)
        background=if(rail==null) base else android.graphics.drawable.LayerDrawable(arrayOf(base,sheet(a,rail,radius=14).apply {
            cornerRadii=floatArrayOf(dp(a,14).toFloat(),dp(a,14).toFloat(),0f,0f,0f,0f,dp(a,14).toFloat(),dp(a,14).toFloat()) })).apply {
            setLayerWidth(1,dp(a,6));setLayerGravity(1,Gravity.START or Gravity.FILL_VERTICAL) }
        val p=dp(a,18);setPadding(p+(if(rail!=null) dp(a,6) else 0),p,p,p)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    /** Navigation row for the home screen: label, one line of status, chevron; 76dp. */
    fun door(a:Activity,label:String,detail:String,action:()->Unit)=LinearLayout(a).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(a,76);isClickable=true;isFocusable=true
        background=sheet(a,CARD,LINE);val p=dp(a,18);setPadding(p,dp(a,12),p,dp(a,12));contentDescription="$label, $detail"
        val words=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        words.addView(text(a,label,SUMMARY+1,true).apply { setPadding(0,0,0,0) })
        if(detail.isNotBlank()) words.addView(text(a,detail,SMALL,color=SUBTLE).apply { setPadding(0,dp(a,2),0,0) })
        addView(words,LinearLayout.LayoutParams(0,-2,1f))
        addView(text(a,"›",28f,color=ACCENT).apply { setPadding(dp(a,8),0,0,dp(a,2)) })
        setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    /** Small status word in a soft pill (e.g. "오늘까지" in amber). */
    fun pill(a:Activity,value:String,color:Int)=text(a,value,SMALL,true,color).apply {
        background=sheet(a,(color and 0x00FFFFFF) or 0x1F000000,radius=20);setPadding(dp(a,12),dp(a,4),dp(a,12),dp(a,4))
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(a,6) }
    }
    fun gap(a:Activity,h:Int=12)=View(a).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(a,h)) }
    /** Urgency colour for a due date: overdue red, today amber, otherwise none. */
    fun railFor(due:String?):Int? = when { overdue(due) -> WARN;due?.trim()==today().toString() -> AMBER_RAIL;else -> null }

    // ---- plain Korean formatting -------------------------------------------------
    private val TIME=DateTimeFormatter.ofPattern("a h:mm",Locale.KOREAN)
    private val DATE=DateTimeFormatter.ofPattern("M월 d일 (E)",Locale.KOREAN)
    fun time(ms:Long?)=ms?.let { TIME.format(Instant.ofEpochMilli(it).atZone(CallRecordStore.KST)) } ?: "시각 모름"
    fun date(d:LocalDate)=DATE.format(d)
    fun dateTime(ms:Long?)=ms?.takeIf{it>0}?.let { date(CallRecordStore.kstDay(it))+" "+time(it) } ?: "아직 없음"
    fun today()=CallRecordStore.kstDay(System.currentTimeMillis())
    fun dayWord(d:LocalDate)=when(d) { today() -> "오늘";today().minusDays(1) -> "어제";today().plusDays(1) -> "내일";else -> date(d) }
    /** "오늘까지", "내일까지", "2일 지남", "11월 3일까지"; free text is shown as written. */
    fun dueWords(due:String?):String {
        val d=due?.trim().orEmpty();if(d.isEmpty())return "기한 없음"
        val date=runCatching{LocalDate.parse(d)}.getOrNull() ?: return d
        val days=java.time.temporal.ChronoUnit.DAYS.between(today(),date)
        return when { days<0 -> "${-days}일 지남";days==0L -> "오늘까지";days==1L -> "내일까지";else -> "${date(date)}까지" }
    }
    fun overdue(due:String?)=runCatching{LocalDate.parse(due?.trim())<today()}.getOrDefault(false)
    fun duration(ms:Long)=if(ms<60_000) "${ms/1000}초" else "${ms/60000}분"+((ms/1000)%60).let { if(it>0) " ${it}초" else "" }
    /** Record state in plain words; no English codes ever reach the screen. */
    fun state(state:String)=when(state) {
        CallRecordStore.TRANSCRIBED -> if(CallInboxConfig.enabled) "정리 중이에요 (보통 1시간 안에 끝나요)" else "정리 중이에요"
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
        return "$day $word"+if(next==e) " (통화 정리)" else " (할 일)"
    }
    fun taskStatus(status:String)=when(status) { "needs_confirmation" -> "확인 필요";"in_progress" -> "진행 중";"waiting" -> "기다리는 중";"done" -> "완료";else -> "" }
}
