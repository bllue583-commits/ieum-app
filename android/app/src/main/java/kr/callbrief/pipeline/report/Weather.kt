package kr.callbrief.pipeline.report

import android.content.Context
import android.util.Log
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.CallPipelineStartup
import kr.callbrief.pipeline.CallRecordStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v4.5 r26-1 (user 11/23: the sites are all over Incheon): the week's forecast beside 이번 주 일정, so the schedule's weather
 * questions (타설, 양생, 반입) are not checked in a weather app apart. Open-Meteo (no key, CC BY 4.0: the source is written
 * under every place that shows it, [source]). The phone asks once a morning, when it builds the morning report
 * ([ReportFetch]), with the site's coordinates and the variable names only ([url]); never its own place.
 * A failure is quiet: the report goes without weather; yesterday's forecast serves while it is [MAX_AGE_MS] old at most.
 *
 * r26-2: the words. A day head "비 70%  -1°/6°" ([headLine]); under a day with schedule words, at most [CAUTIONS_MAX] lines of
 * forecast facts ([cautions]); the only threshold quoted is KCS 14 20 40's cold-weather concrete ([COLD_RULE]).
 */
internal object Weather {
    const val URL="https://api.open-meteo.com/v1/forecast"
    const val DAILY="weather_code,precipitation_probability_max,precipitation_sum,temperature_2m_min,temperature_2m_max,temperature_2m_mean,wind_gusts_10m_max"
    /** Today and seven days: the seventh day's "다음 날 최저" too. */
    const val FORECAST_DAYS=8
    const val TIMEOUT_S=5L
    /** The whole call (connect, both reads) never holds the report longer than this. */
    const val CALL_TIMEOUT_S=10L
    /** A forecast of 8 days is about 1.5 KB; anything past this is not a forecast. */
    const val MAX_BYTES=64*1024
    /** Yesterday's forecast serves a day that could not fetch while it is this old at most. */
    const val MAX_AGE_MS=36*3_600_000L

    // ---- settings keys (encrypted settings; the heartbeat reads numbers and codes of them only) ----
    /** The forecast in use ([toJson]), with when it was fetched and for which site. */
    const val CAST_KEY="weatherCast"
    /** The last fetch's failure code ([ERR_TIMEOUT], [ERR_HTTP], [ERR_PARSE], or [ERR_NONE]). */
    const val ERR_KEY="weatherErr"
    /** The last report's schedule days with weather and its caution lines (heartbeat weather.days, weather.cautions). */
    const val DAYS_KEY="weatherDays";const val CAUTIONS_KEY="weatherCautions"
    /** The mailbox's app/config.json `site` as last read ([SiteConfig]); absent: [DEFAULT_SITE]. */
    const val SITE_KEY="weatherSite"
    const val SEOUL_OFFSET_S=32400
    /** Units that are plainly not ℃, mm or m/s (lower case). */
    val OTHER_UNITS=setOf("°f","f","mph","inch","in","kn","kt","km/h","kmh")
    /** r26-3: a failed fetch is not tried again within this (only the try's time is kept, [TRY_KEY]). */
    const val RETRY_GAP_MS=30*60_000L
    const val TRY_KEY="weatherTryMs"
    const val ERR_TIMEOUT="timeout";const val ERR_HTTP="http";const val ERR_PARSE="parse";const val ERR_NONE="none"

    // ---- the thresholds and words (one place; R26WeatherTest pins them) ----
    /** Below this chance of rain the head says the sky only; from it, "비 70%". */
    const val POP_SHOW=30
    /** From this chance the rain caution under a day with outdoor or concrete work. */
    const val POP_CAUTION=60
    /** KCS 14 20 40: a day's mean at or below 4℃, or a minimum at or below 0℃ in the 24 hours after the pour. */
    const val COLD_MEAN=4.0
    const val FROST_MIN=0.0
    /** Gusts from this (m/s) under a day with lifting or unloading. */
    const val GUST=10.0
    const val CAUTIONS_MAX=2
    /** r26-3: the criterion's own words, written once beside the source line on a day with a cold line ([COLD_SHORT] in the line). */
    const val COLD_RULE="한중 콘크리트 기준: 일평균 4℃ 이하 또는 타설 후 24시간 최저 0℃ 이하"
    const val COLD_SHORT="한중 콘크리트 기준 해당"
    /** Cold concrete: a line with one of these and none of [INDOOR_WORDS]. */
    val CONCRETE_WORDS=listOf("타설","콘크리트","레미콘","양생","몰탈","미장","버림")
    /** r26-3 (review): rain only for work that is outside (no 미장, 양생, a bare 방수 or 도장: 계약서 도장 is a seal). */
    val WET_WORDS=listOf("타설","레미콘","버림","도장 작업","외벽 도장","도장공사","도장 공사","외부 도장","외부 비계","외벽","외부 마감",
        "외벽 방수","옥상 방수","지붕","실링","판넬","용접")
    /** r26-3: lifting only (반입, 상차, 하차 are deliveries, not lifts). */
    val LIFT_WORDS=listOf("크레인","양중","철골","데크","판넬","지붕","비계")
    /** A line with one of these is indoor work: no rain or cold line for it. */
    val INDOOR_WORDS=listOf("내부","실내")
    /** The web view's caution kinds ([webKinds]); the page writes the same sentences from the numbers. */
    const val KIND_COLD=1;const val KIND_RAIN=2;const val KIND_WIND=3

    data class Site(val lat:Double,val lon:Double,val label:String)
    /** Near Incheon City Hall. */
    val DEFAULT_SITE=Site(37.4563,126.7052,"인천")
    /** A site given coordinates but no name. */
    const val UNNAMED_SITE="현장"
    const val LABEL_MAX=12
    private val LABEL=Regex("[가-힣A-Za-z ]{1,$LABEL_MAX}")

    /** One day of the forecast (KST date); any value may be missing. [pop] %, [mm] mm, temperatures ℃, [gust] m/s. */
    data class Day(val date:LocalDate,val code:Int?=null,val pop:Int?=null,val mm:Double?=null,val tmin:Double?=null,
                   val tmax:Double?=null,val tmean:Double?=null,val gust:Double?=null)
    data class Cast(val atMs:Long,val site:Site,val days:Map<LocalDate,Day>) {
        fun day(d:LocalDate):Day?=days[d]
    }

    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** Tests replace the network with a fake answer. */
    @Volatile internal var sourceFactory:(()->Source)?=null
    interface Source { fun get(url:String):Response }
    /** [code] and, for a 200, the body (null when it was larger than [MAX_BYTES]). */
    data class Response(val code:Int,val body:String?)
    private const val TAG="ieum.weather"

    // ---- the site ----
    /** [v] (the config's `site` object, or the settings' copy) as a site, or null (then the default holds): latitude 33..39,
     * longitude 124..132 (Korea), a label of [LABEL_MAX] Korean or Latin letters at most (absent: [UNNAMED_SITE]). */
    fun validSite(v:Any?):Site? {
        val o=when(v) { is JSONObject -> v;is String -> runCatching { JSONObject(v) }.getOrNull();else -> null } ?: return null
        fun num(k:String)=(o.opt(k) as? Number)?.takeIf { it !is Boolean }?.toDouble()?.takeIf { it.isFinite() }
        val lat=num("lat")?.takeIf { it in 33.0..39.0 } ?: return null
        val lon=num("lon")?.takeIf { it in 124.0..132.0 } ?: return null
        val label=when(val l=o.opt("label")) {
            null,JSONObject.NULL -> UNNAMED_SITE
            is String -> l.trim().takeIf { LABEL.matches(it) && it.isNotBlank() } ?: return null
            else -> return null }
        return Site(lat,lon,label)
    }
    fun siteIn(s:JSONObject?):Site=validSite(s?.opt(SITE_KEY)) ?: DEFAULT_SITE
    fun siteJson(site:Site)=JSONObject().put("lat",site.lat).put("lon",site.lon).put("label",site.label)

    // ---- the request: the coordinates and the variable names, nothing else ----
    fun url(site:Site):String = URL+"?latitude="+coord(site.lat)+"&longitude="+coord(site.lon)+"&daily="+DAILY+
        "&timezone=Asia%2FSeoul&forecast_days=$FORECAST_DAYS&wind_speed_unit=ms"
    private fun coord(v:Double)=String.format(Locale.ROOT,"%.4f",v)
    /** "ieum/4.5": the app and its version only (ASCII). */
    internal fun userAgent()="ieum/"+kr.callbrief.BuildConfig.VERSION_NAME.filter { it.code in 33..126 }.take(32)

    // ---- the answer ----
    /** The days of an Open-Meteo daily answer, or null when it is not one: no `daily.time`, no day, a unit that is not
     * ℃, mm, % or m/s, or a time zone other than Seoul. Values that are null stay missing. */
    fun parse(body:String?,atMs:Long,site:Site):Cast? = runCatching {
        val o=JSONObject(body ?: return null)
        // r26-3: Seoul's dates by name or by offset (+9 h); another offset would put the days on the wrong dates.
        val offset=(o.opt("utc_offset_seconds") as? Number)?.toInt()
        val tz=o.opt("timezone") as? String
        if(tz!=null && tz!="Asia/Seoul" && offset!=SEOUL_OFFSET_S) return null
        if(tz==null && offset!=null && offset!=SEOUL_OFFSET_S) return null
        val daily=o.optJSONObject("daily") ?: return null
        val units=o.optJSONObject("daily_units")
        val time=daily.optJSONArray("time") ?: return null
        // r26-3: a column in a plainly other unit (°F, mph, inch, kn, km/h) is left empty; an unknown spelling is taken.
        fun col(k:String)=(daily.opt(k) as? JSONArray)?.takeIf { (units?.opt(k) as? String)?.trim()?.lowercase() !in OTHER_UNITS }
        fun num(a:JSONArray?,i:Int)=(a?.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val code=col("weather_code");val pop=col("precipitation_probability_max");val mm=col("precipitation_sum")
        val lo=col("temperature_2m_min");val hi=col("temperature_2m_max");val mean=col("temperature_2m_mean");val gust=col("wind_gusts_10m_max")
        val days=linkedMapOf<LocalDate,Day>()
        for(i in 0 until minOf(time.length(),16)) {
            val d=runCatching { LocalDate.parse(time.optString(i)) }.getOrNull() ?: continue
            days[d]=Day(d,num(code,i)?.toInt()?.takeIf { it in 0..99 },num(pop,i)?.roundToInt()?.coerceIn(0,100),num(mm,i)?.coerceAtLeast(0.0),
                num(lo,i),num(hi,i),num(mean,i),num(gust,i)?.coerceAtLeast(0.0))
        }
        if(days.isEmpty()) null else Cast(atMs,site,days)
    }.getOrNull()

    fun toJson(c:Cast):JSONObject = JSONObject().put("atMs",c.atMs).put("site",siteJson(c.site)).put("days",JSONArray().apply {
        c.days.values.forEach { d -> put(JSONObject().put("d",d.date.toString()).put("x",numbers(d))) } })
    fun fromJson(v:Any?):Cast? = runCatching {
        val o=when(v) { is JSONObject -> v;is String -> JSONObject(v);else -> return null }
        val site=validSite(o.optJSONObject("site")) ?: return null
        val a=o.optJSONArray("days") ?: return null
        val days=linkedMapOf<LocalDate,Day>()
        for(i in 0 until minOf(a.length(),16)) { val e=a.optJSONObject(i) ?: continue
            val d=runCatching { LocalDate.parse(e.optString("d")) }.getOrNull() ?: continue
            val x=e.optJSONArray("x") ?: continue
            fun n(k:Int)=(x.opt(k) as? Number)?.toDouble()?.takeIf { it.isFinite() }
            days[d]=Day(d,n(0)?.toInt(),n(1)?.roundToInt(),n(2),n(3),n(4),n(5),n(6)) }
        if(days.isEmpty()) null else Cast(o.optLong("atMs",0),site,days)
    }.getOrNull()
    /** [code, pop, mm, tmin, tmax, tmean, gust], one decimal, null where missing. */
    private fun numbers(d:Day)=JSONArray().put(d.code ?: JSONObject.NULL).put(d.pop ?: JSONObject.NULL).put(one(d.mm)).put(one(d.tmin))
        .put(one(d.tmax)).put(one(d.tmean)).put(one(d.gust))
    private fun one(v:Double?):Any = v?.let { Math.round(it*10)/10.0 } ?: JSONObject.NULL

    // ---- which forecast is used ----
    /** [c] may serve at [nowMs]: fetched for [site], not in the future and [MAX_AGE_MS] old at most. */
    fun fresh(c:Cast?,site:Site,nowMs:Long):Boolean = c!=null && c.site==site && c.atMs<=nowMs && nowMs-c.atMs<=MAX_AGE_MS
    /** The forecast the screens use now (home): the kept one when [fresh]. */
    fun usable(s:JSONObject?,nowMs:Long=clock()):Cast? = fromJson(s?.opt(CAST_KEY)).takeIf { fresh(it,siteIn(s),nowMs) }
    private fun kstDay(ms:Long)=Instant.ofEpochMilli(ms).atZone(CallRecordStore.KST).toLocalDate()
    /** What one morning does: the kept forecast when it was fetched today for this site (no second fetch the same day); else a
     * fetch, kept when it reads; when it does not, the kept one while [fresh]. [save] gets what to keep (cast or null, code). */
    internal fun decide(kept:Cast?,site:Site,nowMs:Long,fetch:()->Pair<Cast?,String>,lastTryMs:Long=0,save:(Cast?,String)->Unit):Cast? {
        if(kept!=null && kept.site==site && kept.atMs<=nowMs && kstDay(kept.atMs)==kstDay(nowMs)) return kept
        // r26-3: a try within the last 30 minutes failed (else the kept one would be today's): no new try yet.
        if(lastTryMs in (nowMs-RETRY_GAP_MS+1)..nowMs) return kept?.takeIf { fresh(it,site,nowMs) }
        val (got,err)=fetch()
        save(got,if(got!=null) ERR_NONE else err)
        return got ?: kept?.takeIf { fresh(it,site,nowMs) }
    }
    /** One fetch: the cast, or null and its failure code. Never throws. */
    internal fun fetch(site:Site,nowMs:Long,source:Source):Pair<Cast?,String> {
        val r=try { source.get(url(site)) } catch(e:InterruptedIOException) { return null to ERR_TIMEOUT }
            catch(e:IOException) { return null to ERR_HTTP } catch(e:Exception) { return null to ERR_HTTP }
        if(r.code!=200) return null to ERR_HTTP
        val c=parse(r.body,nowMs,site) ?: return null to ERR_PARSE
        return c to ERR_NONE
    }
    /** The morning report's forecast ([ReportFetch]): never throws, never takes longer than one call's timeouts. */
    fun forReport(app:Context,nowMs:Long=clock()):Cast? = try {
        val st=SecureSettings(CallPipelineStartup.storage(app))
        val s=runCatching { st.snapshot() }.getOrNull()
        val site=siteIn(s)
        decide(fromJson(s?.opt(CAST_KEY)),site,nowMs,{
            runCatching { st.update { it.put(TRY_KEY,nowMs) } }
            fetch(site,nowMs,(sourceFactory ?: ::okSource)()) },s?.optLong(TRY_KEY,0) ?: 0) { c,err ->
            runCatching { st.update { o -> if(c!=null) o.put(CAST_KEY,toJson(c).toString());o.put(ERR_KEY,err) } } }
    } catch(e:Exception) { Log.w(TAG,"no forecast: ${e.javaClass.simpleName}");null }

    /** The report sent with its forecast ([CAST_KEY] shape under "weather"); the kept report body stays without it. */
    fun attach(report:JSONObject,c:Cast?):JSONObject = if(c==null) report else JSONObject(report.toString()).put("weather",toJson(c))
    /** Heartbeat weather.days and weather.cautions of the report [sent] (its own day [day]). */
    fun noteUse(app:Context,sent:JSONObject,day:LocalDate) { runCatching {
        val b=MorningBoard.read(sent,day)
        val c=b.weather
        val days=if(c==null) 0 else b.week.count { c.day(it.date)!=null }
        val cautions=if(c==null) 0 else b.week.sumOf { cautionsOf(c,it).size }
        SecureSettings(CallPipelineStartup.storage(app)).update { it.put(DAYS_KEY,days).put(CAUTIONS_KEY,cautions) }
    } }

    private val client by lazy { OkHttpClient.Builder().connectTimeout(TIMEOUT_S,TimeUnit.SECONDS).readTimeout(TIMEOUT_S,TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S,TimeUnit.SECONDS).callTimeout(CALL_TIMEOUT_S,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
    private fun okSource():Source = object:Source {
        override fun get(url:String):Response {
            require(url.startsWith("https://"))
            val req=Request.Builder().url(url).header("User-Agent",userAgent()).header("Accept","application/json").get().build()
            return client.newCall(req).execute().use { r ->
                if(r.code!=200) return Response(r.code,null)
                val src=r.body?.source() ?: return Response(r.code,null)
                if(src.request(MAX_BYTES+1L)) return Response(200,null)
                Response(200,src.buffer.readUtf8())
            }
        }
    }

    // ---- the words (r26-2) ----
    /** weather_code (WMO) as one Korean word: 맑음, 구름, 흐림, 비, 눈, 소나기, 뇌우. */
    fun sky(code:Int?):String? = when(code) {
        null -> null
        0,1 -> "맑음"
        2 -> "구름"
        3,45,48 -> "흐림"
        in 51..67 -> "비"
        in 71..77,85,86 -> "눈"
        in 80..82 -> "소나기"
        in 95..99 -> "뇌우"
        else -> null }
    fun snowy(code:Int?)=code!=null && (code in 71..77 || code==85 || code==86)
    /** "-1°/6°" (whole degrees, never "-0"). */
    fun temps(d:Day):String? = if(d.tmin==null || d.tmax==null) null else "${deg(d.tmin)}°/${deg(d.tmax)}°"
    private fun deg(v:Double)=v.roundToInt().let { if(it==0) 0 else it }.toString()
    /** "비 70%" from [POP_SHOW], else the sky word. */
    fun skyOrRain(d:Day):String? = if(d.pop!=null && d.pop>=POP_SHOW) (if(snowy(d.code)) "눈" else "비")+" ${d.pop}%" else sky(d.code)
    /** The day head's weather: "비 70%  -1°/6°", "흐림  2°/9°"; null when nothing is known. */
    fun headLine(d:Day):String? = listOfNotNull(skyOrRain(d),temps(d)).joinToString("  ").ifEmpty { null }
    private fun short(d:Day)=listOfNotNull(skyOrRain(d),temps(d)).joinToString(" ").ifEmpty { null }
    /** Home's line over 이번 주 일정: "오늘 흐림 2°/9°, 내일 비 70% -1°/6°"; null without either day. */
    fun nearLine(c:Cast?,today:LocalDate):String? {
        if(c==null) return null
        return listOfNotNull(c.day(today)?.let(::short)?.let { "오늘 $it" },c.day(today.plusDays(1))?.let(::short)?.let { "내일 $it" })
            .joinToString(", ").ifEmpty { null }
    }
    /** r26-3: the texts' line, today only: "오늘 흐림 2°/9°". */
    fun todayLine(c:Cast?,today:LocalDate):String? = c?.day(today)?.let(::short)?.let { "오늘 $it" }
    /** "날씨 Open-Meteo, 인천 기준" (CC BY 4.0's attribution). */
    fun source(site:Site)="날씨 Open-Meteo, ${site.label} 기준"
    /** "3", "-2", "4.4" (one decimal only when it is not whole). */
    fun num(v:Double):String { val r=Math.round(v*10)/10.0
        return if(abs(r-Math.rint(r))<1e-9) Math.rint(r).toInt().let { if(it==0) 0 else it }.toString() else String.format(Locale.ROOT,"%.1f",r) }

    private fun has(items:List<String>,words:List<String>,outdoor:Boolean=false)=items.any { i ->
        words.any { i.contains(it) } && !(outdoor && INDOOR_WORDS.any { i.contains(it) }) }
    /** The caution kinds of a day with [items] ([KIND_COLD], [KIND_RAIN], [KIND_WIND]), [CAUTIONS_MAX] at most, in that order. */
    fun kinds(items:List<String>,d:Day,next:Day?):List<Int> {
        val out=mutableListOf<Int>()
        val concrete=has(items,CONCRETE_WORDS,outdoor=true)
        if(concrete && ((d.tmean!=null && d.tmean<=COLD_MEAN) || (next?.tmin!=null && next.tmin<=FROST_MIN))) out+=KIND_COLD
        if(has(items,WET_WORDS,outdoor=true) && d.pop!=null && d.pop>=POP_CAUTION) out+=KIND_RAIN
        if(has(items,LIFT_WORDS) && d.gust!=null && d.gust>=GUST) out+=KIND_WIND
        return out.take(CAUTIONS_MAX)
    }
    /** The caution lines of a day: forecast facts only (no "하지 마세요"). */
    fun cautions(items:List<String>,d:Day,next:Day?):List<String> = kinds(items,d,next).map { k -> when(k) {
        // r26-3: only the numbers that meet the criterion; its words go once beside the source line ([COLD_RULE]).
        KIND_COLD -> listOfNotNull(d.tmean?.takeIf { it<=COLD_MEAN }?.let { "일평균 ${num(it)}℃" },
            next?.tmin?.takeIf { it<=FROST_MIN }?.let { "다음 날 최저 ${num(it)}℃" }).joinToString(", ")+" 예상 ($COLD_SHORT)"
        KIND_RAIN -> (if(snowy(d.code)) "눈" else "비")+" 올 확률 ${d.pop}%"+(d.mm?.let { ", 예상 강수 ${num(it)}mm" } ?: "")
        else -> "순간풍속 ${num(d.gust ?: 0.0)}m/s 예상" } }
    /** r26-3: the day has the cold line (then [COLD_RULE] is written once beside the source line). */
    fun hasCold(c:Cast?,day:MorningBoard.Day):Boolean = c?.day(day.date)?.let { KIND_COLD in kinds(day.items,it,c.day(day.date.plusDays(1))) } ?: false
    fun cautionsOf(c:Cast,day:MorningBoard.Day):List<String> = c.day(day.date)?.let { cautions(day.items,it,c.day(day.date.plusDays(1))) } ?: emptyList()

    // ---- the web view's numbers (MorningBoard.putWeb): x [code,pop,mm,tmin,tmax,tmean,gust,nextMin], k the kinds ----
    fun webX(d:Day,next:Day?):JSONArray = numbers(d).put(one(next?.tmin))

    // ---- the heartbeat: weather{ok, ageH, days, cautions, err}, numbers and codes only ----
    fun heartbeat(s:JSONObject?,nowMs:Long=clock()):JSONObject {
        val kept=fromJson(s?.opt(CAST_KEY))
        val inUse=kept?.takeIf { fresh(it,siteIn(s),nowMs) }
        val err=s?.optString(ERR_KEY)?.takeIf { it in setOf(ERR_TIMEOUT,ERR_HTTP,ERR_PARSE,ERR_NONE) } ?: ERR_NONE
        return JSONObject().put("ok",kept!=null && kept.atMs<=nowMs && kstDay(kept.atMs)==kstDay(nowMs))
            .put("ageH",inUse?.let { ((nowMs-it.atMs)/3_600_000L).toInt() } ?: JSONObject.NULL)
            .put("days",s?.optInt(DAYS_KEY,0)?.coerceAtLeast(0) ?: 0).put("cautions",s?.optInt(CAUTIONS_KEY,0)?.coerceAtLeast(0) ?: 0)
            .put("err",err)
    }
}

/** v4.5 r26-1: `site{lat, lon, label}` of the mailbox's app/config.json, read with the bench's switch at every heartbeat like
 * [kr.callbrief.pipeline.bench.KakaoConfig]: a valid block ([Weather.validSite]) is kept in settings ([Weather.SITE_KEY]);
 * no file, no block or a value out of range means Incheon ([Weather.DEFAULT_SITE]). */
internal object SiteConfig {
    fun siteOf(text:String?):Weather.Site? = runCatching { JSONObject(text ?: return null).opt("site") }.getOrNull()?.let(Weather::validSite)
    fun apply(settings:SecureSettings,text:String?) {
        val want=siteOf(text)
        val snap=settings.snapshot()
        val stored=Weather.validSite(snap.opt(Weather.SITE_KEY))
        if(want!=stored || (want==null && snap.has(Weather.SITE_KEY)))
            settings.update { if(want==null) it.remove(Weather.SITE_KEY) else it.put(Weather.SITE_KEY,Weather.siteJson(want).toString()) }
    }
}
