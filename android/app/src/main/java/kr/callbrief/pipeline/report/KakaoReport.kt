package kr.callbrief.pipeline.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.CallPipelineStartup
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.ClaudeCallAnalyzer
import kr.callbrief.pipeline.PipelineSettingsKeys
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

/**
 * v3.5 (DESIGN_V5): the morning report over KakaoTalk "나에게 보내기". Key commit: shapes only; r15-1 fills the bodies,
 * r15-3 calls [linkState]/[sendNow] from settings and reads [sentToday] in the morning notice. Signatures stay as they are.
 *
 * Every morning two messages (0-1절 결정 B+A): a feed with the whole report drawn as one picture ([render]) and one text
 * of "오늘 챙길 일" (200 characters at most). When the picture cannot go, the text-only fallback (up to 4 texts). All the
 * words pass [ClaudeCallAnalyzer.maskNumbers] once more, so no phone number reaches Kakao. The Kakao SDK is used only by
 * [KakaoSdkSender]; tests and device-test put a fake [Sender] in [senderFactory].
 */
object KakaoReport {
    /** One KakaoTalk message: either a feed (image + two title lines) or a text template (200 chars at most). */
    sealed class Message {
        abstract val link:String
        data class Feed(val png:ByteArray,val title:String,val body:String,override val link:String):Message()
        data class Text(val text:String,override val link:String):Message()
    }
    /** What [compose] makes from a report: the image report and the "오늘 챙길 일" text, plus the text-only fallback. */
    data class Composed(val feed:Message.Feed?,val todayText:Message.Text?,val fallback:List<Message.Text>)
    /** Sending seam (real Kakao SDK in the app, a fake in tests and device-test). Returns null on success, else an error code.
     * [uploadImage] returns the uploaded image's URL (null, or a thrown [Failure] with its code, when it did not go up). */
    interface Sender {
        suspend fun uploadImage(png:ByteArray):String?
        suspend fun send(message:Message,imageUrl:String?):String?
    }
    /** Link state shown in settings. */
    enum class Link { UNAVAILABLE, NOT_LINKED, LINKED, BROKEN }
    /** A Kakao call that failed with a known code (ASCII, 80 chars at most). */
    class Failure(val code:String):Exception(code)

    @Volatile var senderFactory:((Context)->Sender)?=null

    // ---- limits (DESIGN_V5 0-1절, 1절, 2.1-3) ----
    /** A text template holds 200 characters (a Hangul syllable and a line break count one each). */
    const val LIMIT=200
    /** One line of the report as the routine writes it is 60 characters; longer lines are cut (no ellipsis). */
    const val LINE_MAX=80
    /** "오늘 챙길 일" in the daily text. */
    const val TODAY_TEXT_ITEMS=4
    const val BUTTON="이음에서 보기"
    const val WIDTH=1080
    const val MAX_HEIGHT=2400
    const val MAX_PNG=5*1024*1024
    /** Pause between two messages (the feed and the text; the fallback texts). */
    const val GAP_MS=1_000L
    /** Codes of a token Kakao no longer takes (expired, withdrawn consent): the link is down until the user links again. */
    const val TOKEN_PREFIX="TOKEN_"
    /** The sources of [PipelineSettingsKeys.REPORT_SOURCE]. */
    const val SOURCE_ROUTINE="routine"
    const val SOURCE_PHONE="phone"
    /** Results that are not a failure to count. */
    const val ALREADY_SENT="ALREADY_SENT"
    const val NOT_LINKED="NOT_LINKED"
    const val UNAVAILABLE="UNAVAILABLE"
    const val NO_REPORT="NO_REPORT"

    /** The build's Kakao native app key (CI secret IEUM_KAKAO_APP_KEY); tests replace it. */
    @Volatile internal var appKey:()->String={ kr.callbrief.BuildConfig.KAKAO_APP_KEY }
    /** Waits between messages; tests make it instant. */
    @Volatile internal var pause:suspend (Long)->Unit={ kotlinx.coroutines.delay(it) }
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** The Kakao login (the SDK in the app); tests replace it. Returns null on success, else a code. */
    @Volatile internal var linker:(suspend (Context)->String?)?=null
    /** v3.7 r17-2: counts [day]'s calls for the report (by the report's generatedMs when given); tests replace it. */
    @Volatile internal var coverageOf:((Context,LocalDate,Long?)->CallRecordStore.DayCoverage?)?=null
    private fun storeCoverage(app:Context,day:LocalDate,by:Long?)=kr.callbrief.IeumApplication.store(app).dayCoverage(day,by)
    /** The application context, kept by [init], so [compose] draws with the app's own typeface. */
    @Volatile private var appContext:Context?=null
    private val sending=Mutex()
    private const val TAG="ieum.kakao"

    /** False when the build has no Kakao app key: the feature hides itself in settings. */
    fun available():Boolean = appKey().isNotBlank()

    /** Once, from the app's start ([kr.callbrief.IeumApplication]): the Kakao SDK learns the app key. Never throws. */
    fun init(app:Context) {
        appContext=app.applicationContext
        if(!available()) return
        runCatching { KakaoSdkSender.init(app.applicationContext,appKey()) }.onFailure { Log.w(TAG,"sdk not started: ${it.javaClass.simpleName}") }
    }

    fun linkState(app:Context):Link {
        if(!available()) return Link.UNAVAILABLE
        val s=settings(app) ?: return Link.NOT_LINKED
        return when {
            s.optBoolean(PipelineSettingsKeys.KAKAO_LINKED,false) -> Link.LINKED
            s.optString(PipelineSettingsKeys.KAKAO_LAST_ERROR).startsWith(TOKEN_PREFIX) -> Link.BROKEN
            else -> Link.NOT_LINKED
        }
    }

    /** Settings [카카오톡으로 받기] (r15-3): the Kakao login with the KakaoTalk app when it is there, else the account login,
     * asking for `talk_message` only. [activity] must be an Activity. Null on success (linked), else a code ("CANCELLED" …). */
    suspend fun link(activity:Context):String? {
        if(!available()) return UNAVAILABLE
        val code=runCatching { (linker ?: KakaoSdkSender::login)(activity) }.getOrElse { (it as? Failure)?.code ?: "SDK_"+it.javaClass.simpleName }
        // r15-4 (L7): the encrypted settings are written off the main thread (link runs from a button's coroutine).
        if(code==null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            save(activity) { it.put(PipelineSettingsKeys.KAKAO_LINKED,true).put(PipelineSettingsKeys.KAKAO_LAST_ERROR,"") } }
        // v3.6.1: a failed login keeps its reason for the heartbeat (`kakao.error`), so a Kakao console problem (login off,
        // key hash, consent, member) shows as a code instead of nothing. "LINK_" keeps it from reading as a broken token.
        else if(code!="CANCELLED") kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            save(activity) { it.put(PipelineSettingsKeys.KAKAO_LINKED,false).put(PipelineSettingsKeys.KAKAO_LAST_ERROR,code("LINK_"+code)) } }
        return code?.let(::code)
    }
    /** Turns the link off on this phone (the SDK token is dropped too). */
    suspend fun unlink(app:Context) {
        if(linker==null && available()) runCatching { KakaoSdkSender.logout() }
        save(app) { it.put(PipelineSettingsKeys.KAKAO_LINKED,false).put(PipelineSettingsKeys.KAKAO_LAST_ERROR,"") }
    }

    // ---- compose (DESIGN_V5 1절 and 0-1절) ----------------------------------------------------------------------------
    /** One report read with every word masked once more; lists already cut to the routine's own maxima. */
    internal data class Report(val day:LocalDate,val calls:Int,val newChecks:Int,val todayChecks:Int,
                               val yesterday:List<String>,val today:List<String>,val inProgress:List<String>,val check:List<String>,val phone:Boolean,
                               /** v3.7 r17-2 (M3): yesterday's calls still waiting for their text or analysis (head.pendingCalls, recounted by the phone right before the send). */
                               val pending:Int=0,
                               /** v3.7 r17-2 (M4): calls from before yesterday summed up after the last report ("late", 5 at most), "who: what". */
                               val late:List<String> =emptyList())
    private val WEEK=arrayOf("월","화","수","목","금","토","일")
    /** "11/7(금)". */
    internal fun dayLabel(d:LocalDate)="${d.monthValue}/${d.dayOfMonth}(${WEEK[d.dayOfWeek.value-1]})"
    /** Masked, on one line, cut to [LINE_MAX]. */
    internal fun clean(raw:String?):String {
        val one=(raw ?: "").replace(Regex("\\s+")," ").trim()
        return cut(ClaudeCallAnalyzer.maskNumbers(one).trim(),LINE_MAX)
    }
    internal fun length(s:String)=s.codePointCount(0,s.length)
    private fun cut(s:String,max:Int):String = if(length(s)<=max) s else s.substring(0,s.offsetByCodePoints(0,max.coerceAtLeast(0))).trimEnd()
    private fun objects(a:JSONArray?):List<JSONObject> = if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    internal fun read(o:JSONObject,phoneFallback:Boolean):Report {
        val day=runCatching { LocalDate.parse(o.optString("day")) }.getOrElse { CallRecordStore.kstDay(clock()) }
        val head=o.optJSONObject("head") ?: JSONObject()
        val yesterday=objects(o.optJSONArray("yesterday")).take(8).mapNotNull { y ->
            val who=clean(y.optString("who"));val what=clean(y.optString("what"))
            when { what.isEmpty() -> who.ifEmpty { null };who.isEmpty() -> what;else -> clean("$who: $what") } }
        val today=objects(o.optJSONArray("today")).take(8).mapNotNull { t ->
            val what=clean(t.optString("what")).ifEmpty { return@mapNotNull null }
            // The context line: who asked, and the state when there is one, else when it was asked (DESIGN_V5 1절 ③).
            val note=listOf(clean(t.optString("who")),clean(t.optString("status")).ifEmpty { clean(t.optString("since")) }).filter { it.isNotEmpty() }
            if(note.isEmpty()) what else clean("$what (${note.joinToString(", ")})") }
        val inProgress=objects(o.optJSONArray("inProgress")).take(3).map { clean(it.optString("what")) }.filter { it.isNotEmpty() }
        val check=objects(o.optJSONArray("check")).take(3).mapNotNull { c ->
            val what=clean(c.optString("what")).ifEmpty { return@mapNotNull null };val who=clean(c.optString("who"))
            if(who.isEmpty()) what else clean("$what ($who)") }
        val late=objects(o.optJSONArray("late")).take(LATE_ITEMS).mapNotNull { y ->
            val who=clean(y.optString("who"));val what=clean(y.optString("what"))
            when { what.isEmpty() -> who.ifEmpty { null };who.isEmpty() -> what;else -> clean("$who: $what") } }
        fun n(key:String,fallback:Int)=head.optInt(key,-1).takeIf { it>=0 } ?: fallback
        return Report(day,n("calls",yesterday.size),n("newChecks",0),n("todayChecks",today.size),yesterday,today,inProgress,check,phoneFallback,
            n("pendingCalls",0),late)
    }
    /** v3.7 r17-2 (M3): "어제 통화 14건을 정리했어요. 9건은 아직 글자로 바꾸는 중이에요" (no second sentence when nothing waits). */
    internal fun headLine(r:Report)=(if(r.calls>0) "어제 통화 ${r.calls}건을 정리했어요" else "어제 정리한 통화가 없어요")+pendingSentence(r.pending)
    internal fun pendingSentence(n:Int)=if(n>0) ". ${n}건은 아직 글자로 바꾸는 중이에요" else ""
    /** The counts under [headLine] (v3.6 and before they were on the same line). */
    internal fun countsLine(r:Report)="새 체크 ${r.newChecks}개, 오늘 챙길 일 ${r.todayChecks}개"
    /** v3.7 r17-2 (M4): the last block of the text and of the picture. */
    const val LATE_TITLE="뒤늦게 정리된 통화"
    const val LATE_ITEMS=5
    private fun numbered(items:List<String>)=items.mapIndexed { i,s -> "${i+1}. $s" }
    /** Execution parameters of the [이음에서 보기] button (r15-3 reads them in MainActivity). */
    internal fun callsLink(day:LocalDate)="screen=calls&dayMs="+day.minusDays(1).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
    const val TASKS_LINK="screen=tasks"

    /** [head] lines, then as many of [items] as fit in [limit]; the rest (and [more] left out before) is told as "외 n건".
     * Returns the text and how many items went in. A first item too long for the room left is cut, so a message always
     * carries at least one item. */
    internal fun fit(head:List<String>,items:List<String>,limit:Int=LIMIT,more:Int=0):Pair<String,Int> {
        val lines=head.toMutableList();var used=0
        fun text(extra:List<String>,rest:Int)=(lines+extra+(if(rest>0) listOf("외 ${rest}건") else emptyList())).joinToString("\n")
        for((i,item) in items.withIndex()) {
            val rest=items.size-i-1+more
            if(length(text(listOf(item),rest))<=limit) { lines+=item;used++;continue }
            if(used==0) { val room=limit-length(text(listOf(""),rest));if(room>=8) { lines+=cut(item,room);used++;continue } }
            break
        }
        return cut(text(emptyList(),items.size-used+more),limit) to used
    }

    /** Builds the messages for [report] (the report/YYYY-MM-DD.json object, DESIGN_V5 1절); [phoneFallback] marks the 06:00 phone report. */
    fun compose(report:JSONObject,phoneFallback:Boolean=false):Composed {
        val r=read(report,phoneFallback)
        val label=dayLabel(r.day)
        if(phoneFallback) {
            // 1절: no report from the night's tidy: one text only, from what the phone has.
            val head=listOfNotNull("[이음 아침 보고] $label (새벽 정리 없이 폰에서 만든 보고)",
                r.pending.takeIf { it>0 }?.let { "어제 통화 ${it}건은 아직 글자로 바꾸는 중이에요" },"오늘 챙길 일 ${r.todayChecks}개")
            val items=numbered(r.today.take(TODAY_TEXT_ITEMS)).ifEmpty { listOf("새로 챙길 일 없음") }
            val more=(r.today.size-TODAY_TEXT_ITEMS).coerceAtLeast(0)
            return Composed(null,Message.Text(fit(head,items,more=more).first,TASKS_LINK),emptyList())
        }
        val feed=runCatching { render(report,appContext) }.onFailure { Log.w(TAG,"report not drawn: ${it.javaClass.simpleName}") }.getOrNull()
            ?.takeIf { it.size<=MAX_PNG }
            ?.let { Message.Feed(it,"[이음 아침 보고] $label",headLine(r)+"\n"+countsLine(r),callsLink(r.day)) }
        return Composed(feed,todayText(r,label),fallback(r,label))
    }
    /** The text after the picture: "오늘 챙길 일" (top [TODAY_TEXT_ITEMS], the rest told), then as its last block
     * [LATE_TITLE] (r17-2 M4) when it still fits in [LIMIT]; null when there is neither. */
    private fun todayText(r:Report,label:String):Message.Text? {
        val late=numbered(r.late)
        val first=if(r.today.isEmpty()) null else fit(listOf("[오늘 챙길 일] $label"),numbered(r.today.take(TODAY_TEXT_ITEMS)),
            more=(r.today.size-TODAY_TEXT_ITEMS).coerceAtLeast(0)).first
        if(late.isEmpty()) return first?.let { Message.Text(it,TASKS_LINK) }
        if(first==null) return Message.Text(fit(listOf("[$LATE_TITLE] $label"),late).first,callsLink(r.day))
        // The late block only when its title and a short first line still fit after the to-dos.
        if(length(first)+length(LATE_TITLE)+2+12>LIMIT) return Message.Text(first,TASKS_LINK)
        return Message.Text(fit(listOf(first,LATE_TITLE),late).first,TASKS_LINK)
    }
    /** The text-only fallback (1절 ① to ④): only the messages with something in them, in order, each [LIMIT] at most. */
    private fun fallback(r:Report,label:String):List<Message.Text> {
        val out=mutableListOf<Message.Text>()
        val calls=callsLink(r.day)
        val ys=numbered(r.yesterday)
        val head=listOf("[이음 아침 보고] $label",headLine(r),countsLine(r),"어제 정리")
        if(ys.isEmpty()) out+=Message.Text(fit(head,listOf("어제 업무 통화 없음")).first,calls)
        else {
            // ① takes what it can without "외 n건": the rest goes to ②, which then tells what is still left.
            var used=0
            for(i in 1..ys.size) if(length((head+ys.take(i)).joinToString("\n"))<=LIMIT) used=i else break
            if(used==0) used=1
            if(used>=ys.size) out+=Message.Text(fit(head,ys).first,calls)
            else {
                out+=Message.Text(fit(head,ys.take(used)).first,calls)
                out+=Message.Text(fit(listOf("[어제 정리 계속]"),ys.drop(used)).first,calls)
            }
        }
        if(r.today.isNotEmpty()) out+=Message.Text(fit(listOf("[오늘 챙길 일]"),numbered(r.today)).first,TASKS_LINK)
        val rest=r.inProgress.map { "진행 중: $it" }+r.check.map { "확인 필요: $it" }
        if(rest.isNotEmpty()) out+=Message.Text(fit(listOf("[진행 중과 확인 필요]"),rest).first,TASKS_LINK)
        if(r.late.isNotEmpty()) out+=Message.Text(fit(listOf("[$LATE_TITLE]"),numbered(r.late)).first,calls)
        return out
    }

    // ---- render (DESIGN_V5 2.1-3): the whole report as one tall picture ------------------------------------------------
    /** A picture drawn at 420 dpi (2.625 px per dp, a 1080 px wide phone), so 20sp is 52.5 px. */
    private const val DENSITY=2.625f
    internal const val TITLE_SP=28f
    internal const val SECTION_SP=22f
    /** The smallest text in the picture: 20sp, above the app's 18sp reading size (5절). */
    internal const val BODY_SP=20f
    private const val SIDE=56f
    private const val TOP=64f
    private const val LINE=1.3f
    internal enum class Kind { TITLE, HEAD, SECTION, ITEM, NOTE }
    internal data class Line(val text:String,val kind:Kind)
    /** What [render] drew: the picture, its height and the logical lines (before wrapping), for tests and device-test. */
    internal data class Rendered(val png:ByteArray,val height:Int,val lines:List<Line>)

    /** The report's lines with at most [caps] items per section (yesterday, today, in progress, check); a cut section ends
     * with "외 n건". */
    internal fun lines(r:Report,caps:IntArray=intArrayOf(8,8,3,3,LATE_ITEMS)):List<Line> {
        val out=mutableListOf(Line("${dayLabel(r.day)} 아침 보고",Kind.TITLE),Line(headLine(r),Kind.HEAD),Line(countsLine(r),Kind.HEAD))
        if(r.phone) out+=Line("새벽 정리 없이 폰에서 만든 보고",Kind.NOTE)
        fun section(title:String,items:List<String>,cap:Int,empty:String?) {
            if(items.isEmpty() && empty==null) return
            out+=Line(title,Kind.SECTION)
            if(items.isEmpty()) { out+=Line(empty!!,Kind.NOTE);return }
            items.take(cap).forEach { out+=Line(it,Kind.ITEM) }
            if(items.size>cap) out+=Line("외 ${items.size-cap}건",Kind.NOTE)
        }
        section("어제 정리",numbered(r.yesterday),caps[0],"어제 업무 통화 없음")
        section("오늘 챙길 일",numbered(r.today),caps[1],"새로 챙길 일 없음")
        section("진행 중",r.inProgress,caps[2],null)
        section("확인 필요",r.check,caps[3],null)
        section(LATE_TITLE,numbered(r.late),caps.getOrElse(4) { LATE_ITEMS },null)
        return out
    }
    private fun px(sp:Float)=sp*DENSITY
    private class Faces(val regular:Typeface,val bold:Typeface)
    private fun faces(c:Context?):Faces {
        fun font(id:Int,fallback:Typeface)=c?.let { runCatching { it.resources.getFont(id) }.getOrNull() } ?: fallback
        return Faces(font(kr.callbrief.R.font.pretendard_regular,Typeface.DEFAULT),font(kr.callbrief.R.font.pretendard_bold,Typeface.DEFAULT_BOLD))
    }
    private fun paint(kind:Kind,f:Faces)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        when(kind) {
            Kind.TITLE -> { textSize=px(TITLE_SP);typeface=f.bold;color=INK }
            Kind.SECTION -> { textSize=px(SECTION_SP);typeface=f.bold;color=ACCENT }
            Kind.HEAD -> { textSize=px(BODY_SP);typeface=f.regular;color=SUBTLE }
            Kind.NOTE -> { textSize=px(BODY_SP);typeface=f.regular;color=SUBTLE }
            Kind.ITEM -> { textSize=px(BODY_SP);typeface=f.regular;color=INK }
        }
    }
    private val INK=Color.rgb(0x19,0x1F,0x28)
    private val SUBTLE=Color.rgb(0x6B,0x76,0x84)
    private val ACCENT=Color.rgb(0x32,0x68,0xF0)
    private val PAGE=Color.rgb(0xF2,0xF4,0xF6)
    /** Breaks [text] into lines no wider than [width], at a space when there is one in the line, else between letters. */
    internal fun wrap(text:String,p:Paint,width:Float):List<String> {
        val out=mutableListOf<String>();var rest=text
        while(rest.isNotEmpty()) {
            var n=p.breakText(rest,true,width,null).coerceAtLeast(1)
            if(n<rest.length) { val sp=rest.lastIndexOf(' ',n);if(sp>n/2) n=sp+1 }
            out+=rest.substring(0,n).trimEnd();rest=rest.substring(n).trimStart()
        }
        return out.ifEmpty { listOf("") }
    }
    private fun gapBefore(k:Kind)=when(k) { Kind.SECTION -> px(18f);Kind.HEAD -> px(4f);else -> 0f }
    private fun measure(ls:List<Line>,f:Faces,width:Float):Float =
        TOP*2+ls.sumOf { l -> val p=paint(l.kind,f);(gapBefore(l.kind)+wrap(l.text,p,width).size*p.textSize*LINE).toDouble() }.toFloat()

    /** The picture of [report] (1080 px wide, 2400 px at most, text 20sp and up); sections lose their last items ("외 n건")
     * until it fits. Deterministic for one report: the same lines every time (tests check lines and words, not pixels). */
    internal fun renderFull(report:JSONObject,context:Context?=appContext,phoneFallback:Boolean=false):Rendered {
        val r=read(report,phoneFallback);val f=faces(context)
        val width=WIDTH-SIDE*2
        val caps=intArrayOf(8,8,3,3,LATE_ITEMS)
        var ls=lines(r,caps)
        while(measure(ls,f,width)>MAX_HEIGHT) {
            val counts=intArrayOf(minOf(caps[0],r.yesterday.size),minOf(caps[1],r.today.size),minOf(caps[2],r.inProgress.size),minOf(caps[3],r.check.size),
                minOf(caps[4],r.late.size))
            val i=counts.indices.maxByOrNull { counts[it] }!!
            if(counts[i]<=1) break
            caps[i]=counts[i]-1;ls=lines(r,caps)
        }
        val height=measure(ls,f,width).toInt().coerceIn(1,MAX_HEIGHT)
        val bmp=Bitmap.createBitmap(WIDTH,height,Bitmap.Config.ARGB_8888)
        val c=Canvas(bmp)
        c.drawColor(PAGE)
        c.drawRoundRect(RectF(SIDE/2,TOP/2,WIDTH-SIDE/2,height-TOP/2),px(16f),px(16f),Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE })
        var y=TOP
        for(l in ls) {
            val p=paint(l.kind,f);y+=gapBefore(l.kind)
            for(part in wrap(l.text,p,width)) { y+=p.textSize*LINE;if(y-p.descent()>height) break;c.drawText(part,SIDE,y-p.textSize*(LINE-1f)/2-p.descent(),p) }
        }
        val png=ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() }
        bmp.recycle()
        return Rendered(png,height,ls)
    }
    /** PNG of the whole report (1080 px wide, at most 2400 px high and 5 MB). */
    fun render(report:JSONObject,context:Context?=appContext):ByteArray=renderFull(report,context).png

    // ---- send (DESIGN_V5 2.1-3) ---------------------------------------------------------------------------------------
    /** Sends today's report once (REPORT_DAY guards a second send); returns null on success, else an error code. */
    suspend fun send(app:Context,report:JSONObject,source:String):String? = send(app,report,source,test=false)
    /** [test] (settings [지금 보내 보기], r15-4 M1): guarded by [TEST_SENT_DAY] alone, once a day, and it writes neither
     * REPORT_DAY nor the last send nor the counters, so a press at 4 a.m. keeps the 05:30 report and the app notice as they
     * are. A token error still takes the link down (the next 05:30 would fail the same way). */
    private suspend fun send(app:Context,report:JSONObject,source:String,test:Boolean):String? = sending.withLock {
        if(!available()) return@withLock UNAVAILABLE
        if(appContext==null) appContext=app.applicationContext
        val s=settings(app) ?: return@withLock "SETTINGS"
        val day=runCatching { LocalDate.parse(report.optString("day")) }.getOrNull()?.toString() ?: CallRecordStore.kstDay(clock()).toString()
        val today=CallRecordStore.kstDay(clock()).toString()
        if(test) { if(s.optString(TEST_SENT_DAY)==today) return@withLock ALREADY_SENT }
        else if(s.optString(PipelineSettingsKeys.REPORT_DAY)==day) return@withLock ALREADY_SENT
        if(!s.optBoolean(PipelineSettingsKeys.KAKAO_LINKED,false)) return@withLock NOT_LINKED
        // v3.7 r17-2 (M3): right before the send the phone counts again what of yesterday still waits (the tidy counted at
        // night), so the first line says it as it is at 05:30. The report's own count stays when the store cannot be read.
        val reportDay=runCatching { LocalDate.parse(day) }.getOrElse { CallRecordStore.kstDay(clock()) }
        val gen=report.optLong("generatedMs",0).takeIf { it>0 && source==SOURCE_ROUTINE }
        val cov=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { (coverageOf ?: ::storeCoverage)(app,reportDay.minusDays(1),gen) }.getOrNull() }
        val sent=if(cov==null) report else JSONObject(report.toString()).apply {
            put("head",(optJSONObject("head")?.let { JSONObject(it.toString()) } ?: JSONObject()).put("pendingCalls",cov.pending)) }
        val composed=compose(sent,source==SOURCE_PHONE)
        val sender=senderFactory?.invoke(app) ?: KakaoSdkSender(app.applicationContext)
        val out=deliver(sender,composed)
        val now=clock()
        if(test) {
            save(app) { o ->
                if(out.sent>0) o.put(TEST_SENT_DAY,today)
                if(out.error?.startsWith(TOKEN_PREFIX)==true) o.put(PipelineSettingsKeys.KAKAO_LINKED,false).put(PipelineSettingsKeys.KAKAO_LAST_ERROR,out.error)
            }
            return@withLock out.error
        }
        save(app) { o ->
            if(out.sent>0) o.put(PipelineSettingsKeys.REPORT_DAY,day).put(PipelineSettingsKeys.REPORT_SOURCE,source)
            // The heartbeat's report.coverage (DESIGN_V7 3.3): the phone's own count; the phone's 06:00 report lists no call of yesterday.
            if(out.sent>0 && cov!=null) ReportCoverage.note(o,reportDay,cov,if(source==SOURCE_PHONE) 0 else cov.analyzed,source,now)
            if(out.error==null) {
                o.put(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,now).put(PipelineSettingsKeys.KAKAO_SENT_COUNT,o.optLong(PipelineSettingsKeys.KAKAO_SENT_COUNT,0)+1)
                    .put(PipelineSettingsKeys.KAKAO_LAST_ERROR,"")
            } else {
                o.put(PipelineSettingsKeys.KAKAO_FAILED_COUNT,o.optLong(PipelineSettingsKeys.KAKAO_FAILED_COUNT,0)+1)
                    .put(PipelineSettingsKeys.KAKAO_LAST_ERROR,out.error)
                if(out.error.startsWith(TOKEN_PREFIX)) o.put(PipelineSettingsKeys.KAKAO_LINKED,false)
            }
        }
        out.error
    }
    /** How a delivery went: messages that went out, and the first error that stayed (null: all went). */
    internal data class Outcome(val sent:Int,val error:String?)
    /** The feed, then after a pause the text; when the picture or the feed fails, the text fallback, each text tried twice.
     * A token error stops at once (nothing else would go either). */
    internal suspend fun deliver(sender:Sender,c:Composed):Outcome {
        var sent=0
        suspend fun once(m:Message,url:String?):String? =
            runCatching { sender.send(m,url) }.getOrElse { (it as? Failure)?.code ?: "SEND_"+it.javaClass.simpleName }?.let(::code)
        /** One text, and once more after a pause when it failed (not for a token error). */
        suspend fun twice(m:Message):String? {
            val e=once(m,null) ?: run { sent++;return null }
            if(e.startsWith(TOKEN_PREFIX)) return e
            pause(GAP_MS)
            return once(m,null) ?: run { sent++;null }
        }
        suspend fun texts(list:List<Message.Text>):String? {
            var first:String?=null
            for((i,t) in list.withIndex()) {
                if(i>0) pause(GAP_MS)
                val e=twice(t) ?: continue
                if(first==null) first=e
                if(e.startsWith(TOKEN_PREFIX)) break
            }
            return first
        }
        val feed=c.feed
        if(feed!=null) {
            val upload:Pair<String?,String?> = runCatching { sender.uploadImage(feed.png) }.fold(
                { url -> if(url.isNullOrBlank()) null to "UPLOAD_FAILED" else url to null },
                { e -> null to ((e as? Failure)?.code?.let(::code) ?: "UPLOAD_"+e.javaClass.simpleName) })
            val error=upload.second ?: once(feed,upload.first)
            if(error==null) {
                sent++
                val t=c.todayText ?: return Outcome(sent,null)
                pause(GAP_MS)
                val e=twice(t)
                return Outcome(sent,e)
            }
            if(error.startsWith(TOKEN_PREFIX)) return Outcome(0,error)
            Log.w(TAG,"feed not sent ($error): text fallback")
            val list=c.fallback.ifEmpty { listOfNotNull(c.todayText) }
            if(list.isEmpty()) return Outcome(0,error)
            val e=texts(list)
            return Outcome(sent,e)
        }
        // No picture (the phone's own report, or a drawing that failed): the texts.
        val list=c.fallback.ifEmpty { listOfNotNull(c.todayText) }
        if(list.isEmpty()) return Outcome(0,NO_REPORT)
        val e=texts(list)
        return Outcome(sent,e)
    }

    /** Settings [지금 보내 보기]: today's report if fetched, else the phone fallback, as a test send (once a day by
     * [TEST_SENT_DAY]; the 05:30 report, its counters and the morning notice are left as they are). */
    suspend fun sendNow(app:Context):String? {
        if(!available()) return UNAVAILABLE
        val today=CallRecordStore.kstDay(clock())
        val report=ReportFetch.today(app,today)
        return if(report!=null) send(app,report,SOURCE_ROUTINE,test=true) else send(app,ReportFetch.phoneReport(app,today),SOURCE_PHONE,test=true)
    }
    /** r15-4 (M1): settings key (inside this file, as [ReportFetch.REPORT_BODY]): the KST day of the last test send. */
    const val TEST_SENT_DAY="kakaoTestSentDay"
    /** True when today's (KST) report already went out over KakaoTalk; the 06:00 app notice stays quiet then. */
    fun sentToday(app:Context,nowMs:Long=System.currentTimeMillis()):Boolean {
        val at=settings(app)?.optLong(PipelineSettingsKeys.KAKAO_LAST_SENT_MS,0) ?: 0
        return at>0 && CallRecordStore.kstDay(at)==CallRecordStore.kstDay(nowMs)
    }

    /** A code as kept and sent up in the heartbeat: ASCII letters, digits, '_', '-', ':' only, 80 at most. */
    internal fun code(raw:String):String = raw.filter { it.code<128 && (it.isLetterOrDigit() || it=='_' || it=='-' || it==':') }.take(80).ifEmpty { "UNKNOWN" }
    private fun settings(app:Context):JSONObject? = runCatching { SecureSettings(CallPipelineStartup.storage(app)).snapshot() }.getOrNull()
    private fun save(app:Context,block:(JSONObject)->Unit) { runCatching { SecureSettings(CallPipelineStartup.storage(app)).update(block) }
        .onFailure { Log.w(TAG,"settings not saved: ${it.javaClass.simpleName}") } }
    /** Epoch ms to a KST day (for callers outside the store). */
    internal fun kstDay(ms:Long):LocalDate=Instant.ofEpochMilli(ms).atZone(CallRecordStore.KST).toLocalDate()
}
