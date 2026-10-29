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

/** One look for every call screen, sized for a reader in his 50s: large sp text (scales with the
 * system font size), dark text on a light background, full-width buttons at least 64dp tall, plain Korean. */
internal object CallUi {
    const val TITLE=28f
    const val HEADING=23f
    const val SUMMARY=20f
    const val BODY=19f
    const val SMALL=18f
    val INK=Color.parseColor("#111111")
    val SUBTLE=Color.parseColor("#3A3A3A")
    val ACCENT=Color.parseColor("#0B57D0")
    val WARN=Color.parseColor("#B3261E")
    val OK=Color.parseColor("#1E7A34")
    val PAGE=Color.parseColor("#FFFFFF")
    val CARD=Color.parseColor("#EEF3FB")
    val CARD_WARN=Color.parseColor("#FDEEEC")

    fun dp(a:Activity,v:Int)=(v*a.resources.displayMetrics.density+0.5f).toInt()

    /** A scrolling page with system-bar padding; returns the column to add views to. */
    /** Device tests only: lets UiAutomation capture the screens. The app itself never sets it. */
    @Volatile internal var allowCaptureForTests=false
    fun page(a:Activity):LinearLayout {
        if(!allowCaptureForTests)a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val column=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(a).apply { setBackgroundColor(PAGE);isFillViewport=true;tag="page_scroll" }
        scroll.setOnApplyWindowInsetsListener { v,insets -> val p=dp(a,18);v.setPadding(p,insets.systemWindowInsetTop+p,p,insets.systemWindowInsetBottom+p);insets }
        scroll.addView(column);a.setContentView(scroll);return column
    }
    fun text(a:Activity,value:String,size:Float=BODY,bold:Boolean=false,color:Int=INK)=TextView(a).apply {
        text=value;textSize=size;setTextColor(color);setLineSpacing(0f,1.2f);setPadding(0,dp(a,6),0,dp(a,6))
        if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    fun title(a:Activity,value:String)=text(a,value,TITLE,true)
    fun heading(a:Activity,value:String)=text(a,value,HEADING,true).apply { setPadding(0,dp(a,16),0,dp(a,6)) }

    private fun rounded(color:Int,stroke:Int?=null,a:Activity)=GradientDrawable().apply { setColor(color);cornerRadius=dp(a,14).toFloat();if(stroke!=null)setStroke(dp(a,2),stroke) }
    /** Primary full-width button: icon + plain label, 64dp tall. */
    fun bigButton(a:Activity,icon:String,label:String,action:()->Unit)=Button(a).apply {
        text=if(icon.isEmpty()) label else "$icon  $label";textSize=22f;isAllCaps=false;setTypeface(typeface,Typeface.BOLD)
        setTextColor(Color.WHITE);background=rounded(ACCENT,null,a);minHeight=dp(a,68);gravity=Gravity.CENTER
        setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,8);bottomMargin=dp(a,8) }
    }
    /** Secondary full-width button (outlined), 60dp tall. */
    fun button(a:Activity,label:String,action:()->Unit)=Button(a).apply {
        text=label;textSize=SMALL+1;isAllCaps=false;setTextColor(ACCENT);background=rounded(PAGE,ACCENT,a);minHeight=dp(a,60)
        setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,6);bottomMargin=dp(a,6) }
    }
    fun card(a:Activity,warn:Boolean=false)=LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL;background=rounded(if(warn) CARD_WARN else CARD,null,a);val p=dp(a,16);setPadding(p,p,p,p)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,8);bottomMargin=dp(a,8) }
    }
    fun gap(a:Activity,h:Int=12)=View(a).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(a,h)) }

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
        CallRecordStore.TRANSCRIBED -> "정리 중이에요"
        CallRecordStore.WAITING_KEY -> "설정에서 Claude 키를 넣어주세요"
        CallRecordStore.WAITING_CONSENT -> "설정에서 정리 동의를 켜주세요"
        CallRecordStore.FAILED -> "정리하지 못했어요"
        else -> ""
    }
    fun taskStatus(status:String)=when(status) { "needs_confirmation" -> "확인 필요";"in_progress" -> "진행 중";"waiting" -> "기다리는 중";"done" -> "완료";else -> "" }
}
