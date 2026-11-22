package kr.callbrief.pipeline

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.report.KakaoReport
import kr.callbrief.pipeline.report.MorningBoard
import kr.callbrief.pipeline.report.ReportFetch
import kr.callbrief.pipeline.report.SiteConfig
import kr.callbrief.pipeline.report.Weather
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** v4.5 r26 (일정에 날씨): the forecast the phone fetches with the morning report (Open-Meteo's daily answer, made-up numbers in
 * its standard shape, never the network), the words beside 이번 주 일정 and their thresholds, the quiet failures, the 36-hour
 * rule, the site from the config, the web view's numbers and the heartbeat. Every schedule line and name is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R26WeatherTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private val day=LocalDate.of(2026,11,24)
    private val at530=ReportFetch.target(day).toEpochMilli()
    private val h=3_600_000L
    private val site=Weather.DEFAULT_SITE

    /** A made-up Open-Meteo daily answer from [start]: each row [code, pop, mm, tmin, tmax, tmean, gust] (null: missing). */
    private fun answer(start:LocalDate,rows:List<List<Number?>>,units:JSONObject=units(),tz:String="Asia/Seoul"):String {
        fun col(i:Int)=JSONArray().apply { rows.forEach { put(it[i] ?: JSONObject.NULL) } }
        return JSONObject().put("latitude",37.45).put("longitude",126.70).put("generationtime_ms",0.1).put("utc_offset_seconds",32400)
            .put("timezone",tz).put("timezone_abbreviation","GMT+9").put("elevation",10.0).put("daily_units",units)
            .put("daily",JSONObject().put("time",JSONArray(rows.indices.map { start.plusDays(it.toLong()).toString() }))
                .put("weather_code",col(0)).put("precipitation_probability_max",col(1)).put("precipitation_sum",col(2))
                .put("temperature_2m_min",col(3)).put("temperature_2m_max",col(4)).put("temperature_2m_mean",col(5)).put("wind_gusts_10m_max",col(6)))
            .toString()
    }
    private fun units()=JSONObject().put("time","iso8601").put("weather_code","wmo code").put("precipitation_probability_max","%")
        .put("precipitation_sum","mm").put("temperature_2m_min","°C").put("temperature_2m_max","°C").put("temperature_2m_mean","°C")
        .put("wind_gusts_10m_max","m/s")
    /** Eight days: today cloudy, tomorrow rain 70% 12mm and cold, then mild. */
    private fun week(start:LocalDate=day)=answer(start,listOf(
        listOf(3,10,0.0,2.2,9.4,5.0,6.0),listOf(61,70,12.0,-1.0,6.0,3.0,12.0),listOf(1,0,0.0,-2.0,4.0,1.0,4.0)
    )+(3 until 8).map { listOf(2,20,0.0,3.0,11.0,7.0,5.0) })

    private class FakeSource(var answer:(String)->Weather.Response):Weather.Source {
        val asked=mutableListOf<String>()
        override fun get(url:String):Weather.Response { asked+=url;return answer(url) }
    }
    private class OkSender:KakaoReport.Sender {
        val sent=mutableListOf<KakaoReport.Message>()
        override suspend fun uploadImage(png:ByteArray):String?="https://k.kakaocdn.net/img/1.png"
        override suspend fun send(message:KakaoReport.Message,imageUrl:String?):String? { sent+=message;return null }
    }
    private val sender=OkSender()
    @Before fun setup() {
        KakaoReport.appKey={ "abc123" };KakaoReport.pause={};KakaoReport.clock={ at530 };ReportFetch.clock={ at530 }
        KakaoReport.senderFactory={ sender };KakaoReport.coverageOf={ _,_,_ -> null };ReportFetch.scheduler={ _,_ -> }
        KakaoReport.webBase={ "https://example.github.io/ieum-app/report/" }
        SecureSettings(app).update { it.put(PipelineSettingsKeys.KAKAO_LINKED,true) }
    }
    @After fun reset() {
        KakaoReport.appKey={ kr.callbrief.BuildConfig.KAKAO_APP_KEY };KakaoReport.pause={ kotlinx.coroutines.delay(it) }
        KakaoReport.clock={ System.currentTimeMillis() };ReportFetch.clock={ System.currentTimeMillis() }
        KakaoReport.senderFactory=null;ReportFetch.sourceFactory=null;ReportFetch.scheduler=null;KakaoReport.coverageOf=null
        KakaoReport.webBase={ kr.callbrief.BuildConfig.KAKAO_WEB_BASE };Weather.sourceFactory=null
    }
    private fun settings()=SecureSettings(app).snapshot()

    // ---- the request and the answer ----
    @Test fun theRequestCarriesTheCoordinatesAndTheVariableNamesOnly() {
        val u=Weather.url(site)
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=37.4563&longitude=126.7052&daily=weather_code,precipitation_probability_max,"+
            "precipitation_sum,temperature_2m_min,temperature_2m_max,temperature_2m_mean,wind_gusts_10m_max&timezone=Asia%2FSeoul&forecast_days=8&wind_speed_unit=ms",u)
        assertTrue(Regex("ieum/[!-~]{1,32}").matches(Weather.userAgent()))
        assertEquals(5L,Weather.TIMEOUT_S)
    }
    @Test fun theAnswerIsReadWithItsUnitsAndKoreanDates() {
        val c=Weather.parse(week(),at530,site)!!
        assertEquals(8,c.days.size)
        val t=c.day(day.plusDays(1))!!
        assertEquals(61,t.code);assertEquals(70,t.pop);assertEquals(12.0,t.mm!!,0.0);assertEquals(-1.0,t.tmin!!,0.0)
        assertEquals(6.0,t.tmax!!,0.0);assertEquals(3.0,t.tmean!!,0.0);assertEquals(12.0,t.gust!!,0.0)
        // Missing values stay missing; the day stays.
        val holes=Weather.parse(answer(day,listOf(listOf(null,null,null,1.0,5.0,null,null))),at530,site)!!.day(day)!!
        assertNull(holes.code);assertNull(holes.pop);assertEquals("1°/5°",Weather.headLine(holes))
        // Another unit (Fahrenheit, km/h), another time zone, no daily, empty arrays, broken JSON: no forecast.
        assertNull(Weather.parse(answer(day,listOf(listOf(0,0,0,1,5,3,4)),units().put("temperature_2m_max","°F")),at530,site))
        assertNull(Weather.parse(answer(day,listOf(listOf(0,0,0,1,5,3,4)),units().put("wind_gusts_10m_max","km/h")),at530,site))
        assertNull(Weather.parse(answer(day,listOf(listOf(0,0,0,1,5,3,4)),tz="GMT"),at530,site))
        assertNull(Weather.parse(answer(day,emptyList()),at530,site))
        assertNull(Weather.parse("{\"daily\":{}}",at530,site))
        assertNull(Weather.parse("{broken",at530,site));assertNull(Weather.parse(null,at530,site))
        // The kept copy reads back the same.
        assertEquals(c,Weather.fromJson(Weather.toJson(c).toString()))
    }
    @Test fun theDaysAreSeoulDays() {
        // 00:30 on the 25th in Seoul is still the 24th in UTC: 오늘 is the 25th.
        val c=Weather.parse(week(),at530,site)!!
        val now=java.time.ZonedDateTime.of(2026,11,25,0,30,0,0,CallRecordStore.KST).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026,11,24),java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate())
        assertEquals("오늘 비 70% -1°/6°, 내일 맑음 -2°/4°",Weather.nearLine(c,CallRecordStore.kstDay(now)))
        assertEquals("오늘 흐림 2°/9°, 내일 비 70% -1°/6°",Weather.nearLine(c,day))
    }

    // ---- the words and their thresholds ----
    private fun d(code:Int?=3,pop:Int?=10,mm:Double?=0.0,tmin:Double?=2.0,tmax:Double?=9.0,tmean:Double?=6.0,gust:Double?=5.0)=
        Weather.Day(day,code,pop,mm,tmin,tmax,tmean,gust)
    private fun next(tmin:Double?)=Weather.Day(day.plusDays(1),tmin=tmin)
    @Test fun theDayHeadSaysTheSkyBelowThirtyAndTheChanceFromIt() {
        assertEquals("흐림  2°/9°",Weather.headLine(d(pop=29)))
        assertEquals("비 30%  2°/9°",Weather.headLine(d(pop=30)))
        assertEquals("눈 70%  -3°/1°",Weather.headLine(d(code=73,pop=70,tmin=-3.0,tmax=1.0)))
        assertEquals("0°/1°",Weather.temps(d(tmin=-0.4,tmax=0.5)))
        assertEquals(listOf("맑음","맑음","구름","흐림","흐림","비","비","눈","소나기","뇌우","눈"),
            listOf(0,1,2,3,45,51,65,75,81,95,86).map { Weather.sky(it) })
        assertNull(Weather.headLine(d(code=null,pop=null,tmin=null)))
        val all=listOf(0,2,3,61,71,80,95).map { Weather.headLine(d(code=it))!! }+Weather.COLD_RULE
        // The screen rules: no emoji, no middle dot, no ellipsis.
        all.forEach { s -> assertFalse(s,s.any { it=='·' || it=='…' || Character.isSurrogate(it) }) }
    }
    @Test fun theColdConcreteLineFollowsKcs142040Exactly() {
        val pour=listOf("3층 슬래브 타설")
        assertEquals(listOf(Weather.KIND_COLD),Weather.kinds(pour,d(tmean=4.0),next(3.0)))
        assertEquals("4.1 is not cold",emptyList<Int>(),Weather.kinds(pour,d(tmean=4.1),next(0.1)))
        assertEquals("next day's 0.0 is",listOf(Weather.KIND_COLD),Weather.kinds(pour,d(tmean=8.0),next(0.0)))
        assertEquals("일평균 3℃, 다음 날 최저 -2℃ 예상 (한중 콘크리트 기준: 일평균 4℃ 이하 또는 타설 뒤 24시간 최저 0℃ 이하)",
            Weather.cautions(pour,d(tmean=3.0),next(-2.0)).single())
        assertEquals("일평균 4.4℃, 다음 날 최저 -0.5℃ 예상 (${Weather.COLD_RULE})",Weather.cautions(listOf("레미콘 입고"),d(tmean=4.4),next(-0.5)).single())
        assertEquals("no next day","일평균 2℃ 예상 (${Weather.COLD_RULE})",Weather.cautions(listOf("버림 콘크리트"),d(tmean=2.0),null).single())
        assertTrue("no concrete word, no line",Weather.kinds(listOf("안전 교육"),d(tmean=-5.0,pop=90,gust=20.0),next(-9.0)).isEmpty())
    }
    @Test fun rainAndWindAreForecastFactsAtSixtyAndTen() {
        val paint=listOf("외벽 도장")
        assertTrue(Weather.kinds(paint,d(pop=59),null).isEmpty())
        assertEquals("비 올 확률 60%, 예상 강수 12mm",Weather.cautions(paint,d(pop=60,mm=12.0),null).single())
        assertEquals("눈 올 확률 80%, 예상 강수 3.5mm",Weather.cautions(listOf("지붕 방수"),d(code=71,pop=80,mm=3.5),null).single())
        val lift=listOf("철골 자재 반입")
        assertTrue(Weather.kinds(lift,d(gust=9.9),null).isEmpty())
        assertEquals("순간풍속 10m/s 예상",Weather.cautions(lift,d(gust=10.0),null).single())
        // At most two a day: cold first, then rain; the wind waits.
        val many=Weather.cautions(listOf("타설","판넬 반입"),d(tmean=1.0,pop=90,mm=20.0,gust=15.0),next(-3.0))
        assertEquals(2,many.size);assertTrue(many[0].startsWith("일평균"));assertTrue(many[1].startsWith("비 올 확률 90%"))
        // No directive words anywhere.
        (many+Weather.cautions(lift,d(gust=12.0),null)).forEach { assertFalse(it,Regex("마세요|하세요|금지|중지").containsMatchIn(it)) }
        assertEquals(listOf("타설","콘크리트","레미콘","양생","몰탈","미장","버림"),Weather.CONCRETE_WORDS)
        assertEquals(Weather.CONCRETE_WORDS+listOf("도장","방수","실링","외부","지붕","판넬","용접"),Weather.WET_WORDS)
        assertEquals(listOf("크레인","양중","상차","하차","반입","철골","데크","판넬","지붕"),Weather.LIFT_WORDS)
    }

    // ---- which forecast, and the quiet failures ----
    @Test fun oneFetchADayAndYesterdaysForThirtySixHoursOnly() {
        val kept=Weather.parse(week(day.minusDays(1)),at530-24*h,site)!!
        var fetched=0
        fun run(k:Weather.Cast?,now:Long,ok:Boolean)=Weather.decide(k,site,now,{ fetched++;if(ok) Weather.parse(week(),now,site) to Weather.ERR_NONE else null to Weather.ERR_HTTP }) { _,_ -> }
        assertSame("fetched today: kept, no second fetch",kept,Weather.decide(kept,site,at530-24*h+h,{ fail("no fetch");null to "" }) { _,_ -> })
        assertSame("failed: yesterday's",kept,run(kept,at530,false));assertEquals(1,fetched)
        assertNull("37 hours: not used",run(kept,at530-24*h+37*h,false))
        assertNotNull(run(kept,at530,true))
        val other=Weather.Site(37.5,126.6,"남동")
        assertNull("another site's is not used",Weather.decide(kept,other,at530,{ null to Weather.ERR_TIMEOUT }) { _,_ -> })
        assertFalse(Weather.fresh(kept.copy(atMs=at530+h),site,at530))
    }
    @Test fun failuresAreCodedAndNeverThrow() {
        fun code(src:Weather.Source)=Weather.fetch(site,at530,src).let { assertNull(it.first);it.second }
        assertEquals(Weather.ERR_TIMEOUT,code(FakeSource { throw java.net.SocketTimeoutException("t") }))
        assertEquals(Weather.ERR_HTTP,code(FakeSource { Weather.Response(500,null) }))
        assertEquals(Weather.ERR_HTTP,code(FakeSource { throw java.net.UnknownHostException("x") }))
        assertEquals(Weather.ERR_HTTP,code(FakeSource { throw IllegalStateException("x") }))
        assertEquals(Weather.ERR_PARSE,code(FakeSource { Weather.Response(200,"{\"daily\":") }))
        assertEquals(Weather.ERR_PARSE,code(FakeSource { Weather.Response(200,answer(day,emptyList())) }))
        assertEquals("too big",Weather.ERR_PARSE,code(FakeSource { Weather.Response(200,null) }))
    }
    @Test fun theMorningReportGoesWithTheWeatherAndWithoutIt() = runBlocking {
        val report=JSONObject().put("day",day.toString()).put("generatedMs",at530-40*60_000L)
            .put("head",JSONObject().put("calls",1).put("newChecks",2).put("todayChecks",2))
            .put("today",JSONArray(listOf(JSONObject().put("what","거푸집 도면 확인").put("who","가나건설"),JSONObject().put("what","안전 서류").put("who","본사"))))
            .put("week",JSONArray(listOf(JSONObject().put("date",day.plusDays(1).toString()).put("items",JSONArray(listOf("2층 슬래브 타설","철근 반입"))),
                JSONObject().put("date",day.plusDays(3).toString()).put("items",JSONArray(listOf("내부 마감"))))))
        ReportFetch.sourceFactory={ object:ReportFetch.Source { override fun get(path:String,etag:String?)=ReportFetch.Response(200,report.toString(),"\"e\"") } }
        // 1. The site does not answer in time: the report goes as before, nothing thrown, the code kept.
        Weather.sourceFactory={ FakeSource { throw java.net.SocketTimeoutException("t") } }
        assertEquals(ReportFetch.Outcome.SENT,ReportFetch.run(app,day,0))
        assertTrue(sender.sent.isNotEmpty())
        assertEquals(Weather.ERR_TIMEOUT,settings().getString(Weather.ERR_KEY))
        val hb=Weather.heartbeat(settings(),at530)
        assertEquals(false,hb.get("ok"));assertEquals(JSONObject.NULL,hb.get("ageH"));assertEquals(0,hb.getInt("days"));assertEquals("timeout",hb.getString("err"))
        // 2. The next morning it answers: the bubbles' web view carries the numbers; the kept report body does not.
        val next=day.plusDays(1);val at=ReportFetch.target(next).toEpochMilli()
        ReportFetch.clock={ at };KakaoReport.clock={ at }
        report.put("day",next.toString())
        val src=FakeSource { Weather.Response(200,week(next)) };Weather.sourceFactory={ src }
        sender.sent.clear()
        assertEquals(ReportFetch.Outcome.SENT,ReportFetch.run(app,next,0))
        assertEquals(1,src.asked.size)
        val card=sender.sent.filterIsInstance<KakaoReport.Message.ListCard>().first()
        val web=JSONObject(KakaoReport.unpackWeb(card.webUrl!!.substringAfter("#d=")))
        val w0=web.getJSONArray("week").getJSONObject(0)
        assertEquals(next.toString(),w0.getString("date"))
        // The schedule's first day is [next] itself (cloudy, mean 5, the day after -1): [code,pop,mm,tmin,tmax,tmean,gust,nextMin].
        assertEquals("[3,10,0,2.2,9.4,5,6,-1]",w0.getJSONArray("x").toString())
        assertEquals("cold for the pour (next day -1)","[1]",w0.getJSONArray("k").toString())
        assertEquals("인천",web.getString("wl"))
        assertFalse(JSONObject(settings().getString(ReportFetch.REPORT_BODY)).has("weather"))
        val beat=Weather.heartbeat(settings(),at+h)
        assertEquals(true,beat.get("ok"));assertEquals(1,beat.getInt("ageH"));assertEquals(2,beat.getInt("days"));assertEquals(1,beat.getInt("cautions"))
        assertEquals("none",beat.getString("err"))
        // The texts (when the list does not go) carry today's and tomorrow's one line; the list's header stays.
        val r=JSONObject(report.toString()).let { Weather.attach(it,Weather.usable(settings(),at)) }
        val chat=KakaoReport.compose(r,picture=false).chat
        assertEquals("오늘 흐림 2°/9°, 내일 비 70% -1°/6°",chat[0].text.lines()[3])
        assertFalse(card.header.contains("°"))
        // A second try the same day asks nothing.
        assertEquals(at,Weather.forReport(app,at+2*h)!!.atMs);assertEquals(1,src.asked.size)
    }
    @Test fun theWeatherLeavesTheWebViewLast() {
        val c=Weather.parse(week(),at530,site)!!
        val o=JSONObject().put("day",day.toString()).put("head",JSONObject().put("todayChecks",10))
            .put("today",JSONArray((1..10).map { JSONObject().put("what","만든 일 $it 서류 챙기기 정리").put("who","업체 $it") }))
            .put("week",JSONArray((0..6).map { JSONObject().put("date",day.plusDays(it.toLong()).toString()).put("items",JSONArray(listOf("타설 준비 $it","판넬 반입 $it"))) }))
            .put("changed",JSONArray((1..6).map { "바뀐 것 $it 일정 조정" }))
        val full=Weather.attach(o,c)
        val big=KakaoReport.web("https://e.x/r/",full,false,6000)!!
        val all=JSONObject(KakaoReport.unpackWeb(big.url.substringAfter("#d=")))
        assertEquals(7,(0 until 7).count { all.getJSONArray("week").getJSONObject(it).has("x") })
        assertEquals(KakaoReport.WEB_CUT_WEATHER,KakaoReport.WEB_CUTS.last().first)
        // Any cap: while a day is there, its weather is there unless every other block already gave up all it could.
        for(max in listOf(400,700,1000,1400,1800)) {
            val w=KakaoReport.web("https://e.x/r/",full,false,max) ?: continue
            val d=JSONObject(KakaoReport.unpackWeb(w.url.substringAfter("#d=")))
            val week=d.optJSONArray("week") ?: JSONArray()
            val weatherGone=(0 until week.length()).any { !week.getJSONObject(it).has("x") }
            if(weatherGone) assertEquals("max $max",0,d.optJSONArray("today")?.length() ?: 0)
        }
        // An old fragment (no weather) is read as before.
        assertFalse(KakaoReport.webData(o,false).getJSONArray("week").getJSONObject(0).has("x"))
    }

    // ---- the site from the config ----
    @Test fun theSiteComesFromTheConfigOrIsIncheon() {
        assertEquals(Weather.Site(37.39,126.64,"송도"),SiteConfig.siteOf("""{"site":{"lat":37.39,"lon":126.64,"label":"송도"}}"""))
        assertEquals(Weather.Site(37.0,127.0,Weather.UNNAMED_SITE),SiteConfig.siteOf("""{"site":{"lat":37,"lon":127}}"""))
        for(bad in listOf("""{"site":{"lat":40.1,"lon":126.6}}""","""{"site":{"lat":37.4,"lon":133}}""","""{"site":{"lat":"37.4","lon":126.6}}""",
                """{"site":{"lat":37.4,"lon":126.6,"label":"아주아주긴현장이름이에요열셋"}}""","""{"site":{"lat":37.4,"lon":126.6,"label":"현장#1"}}""",
                """{"site":{"lat":true,"lon":126.6}}""","""{"site":"인천"}""","""{"power":{"budgetMin":40}}""","{broken"))
            assertNull(bad,SiteConfig.siteOf(bad))
        val st=SecureSettings(app)
        SiteConfig.apply(st,"""{"site":{"lat":37.39,"lon":126.64,"label":"송도"}}""")
        assertEquals("송도",Weather.siteIn(st.snapshot()).label)
        assertTrue(Weather.url(Weather.siteIn(st.snapshot())).contains("latitude=37.3900&longitude=126.6400"))
        SiteConfig.apply(st,"""{"site":{"lat":45,"lon":126.64}}""")
        assertEquals(Weather.DEFAULT_SITE,Weather.siteIn(st.snapshot()));assertFalse(st.snapshot().has(Weather.SITE_KEY))
        assertEquals("날씨 Open-Meteo, 인천 기준",Weather.source(Weather.DEFAULT_SITE))
    }

    // ---- home ----
    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tag(a:android.app.Activity,t:String)=a.window.decorView.findViewWithTag<View>(t)
    private fun settle(check:()->Boolean) { repeat(400) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun home(board:MorningBoard.Board?):MainActivity {
        val a=launchMain(Screen.HOME);settle { tag(a,"home_today")!=null || tag(a,"retry_card")!=null }
        a.home.render(HomeScreen.Snapshot(true,true,"READY",0,0,0,0,0,0,board=board));shadowOf(Looper.getMainLooper()).idle();return a
    }
    @Test fun homeShowsTodayAndTomorrowEvenWithoutASchedule() {
        val today=CallUi.today();val now=System.currentTimeMillis()
        val c=Weather.parse(week(today),now,site)!!
        val s=JSONObject().put(Weather.CAST_KEY,Weather.toJson(c).toString())
        val b=HomeScreen.board(s,today,now)!!
        assertTrue(b.isEmpty)
        val a=home(b)
        assertEquals("오늘 흐림 2°/9°, 내일 비 70% -1°/6°",(tag(a,HomeBoard.TAG_PREFIX+"weather") as TextView).text.toString())
        assertEquals("날씨 Open-Meteo, 인천 기준",(tag(a,HomeBoard.TAG_PREFIX+"week_source") as TextView).text.toString())
        assertNull("no report: no basis line",tag(a,HomeBoard.TAG_PREFIX+"basis"))
        assertNull(tag(a,HomeBoard.TAG_PREFIX+"more"))
        // Kept 37 hours ago: nothing.
        assertNull(HomeScreen.board(JSONObject().put(Weather.CAST_KEY,Weather.toJson(c.copy(atMs=now-37*h)).toString()),today,now))
    }
    @Test fun homeDaysCarryTheirWeatherAndCautions() {
        val today=CallUi.today();val now=System.currentTimeMillis()
        val c=Weather.parse(week(today),now,site)!!
        val r=JSONObject().put("day",today.toString()).put("week",JSONArray(listOf(
            JSONObject().put("date",today.plusDays(1).toString()).put("items",JSONArray(listOf("2층 슬래브 타설","판넬 반입"))),
            JSONObject().put("date",today.plusDays(4).toString()).put("items",JSONArray(listOf("내부 미장"))))))
        val s=JSONObject().put(ReportFetch.REPORT_BODY,r.toString()).put(Weather.CAST_KEY,Weather.toJson(c).toString())
        val a=home(HomeScreen.board(s,today,now))
        val tomorrow=tag(a,"board_day_${today.plusDays(1)}") as ViewGroup
        val texts=views(tomorrow).filterIsInstance<TextView>()
        assertEquals("비 70%  -1°/6°",texts.single { it.tag=="board_day_weather" }.text.toString())
        val cautions=texts.filter { it.tag=="board_day_caution" }.map { it.text.toString() }
        assertEquals(listOf("일평균 3℃, 다음 날 최저 -2℃ 예상 (${Weather.COLD_RULE})","비 올 확률 70%, 예상 강수 12mm"),cautions)
        assertEquals(CallUi.AMBER,texts.first { it.tag=="board_day_caution" }.currentTextColor)
        assertNotNull(tag(a,HomeBoard.TAG_PREFIX+"weather"))
        // The later day sits in the fold with its own source line.
        (tag(a,HomeBoard.TAG_PREFIX+"more") as TextView).performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals("이번 주 일정, 그 뒤",views(tag(a,HomeBoard.TAG_PREFIX+"later_head")).filterIsInstance<TextView>().first().text.toString())
        assertEquals("구름  3°/11°",(views(tag(a,"board_day_${today.plusDays(4)}")).single { it.tag=="board_day_weather" } as TextView).text.toString())
        assertNotNull(tag(a,HomeBoard.TAG_PREFIX+"later_source"))
        val all=views(a.window.decorView).filterIsInstance<TextView>()
        assertTrue(all.none { it.text.contains('·') || it.text.contains('…') })
        assertTrue(all.filter { it !is android.widget.Button }.all { it.textSize/it.resources.displayMetrics.scaledDensity>=((it.getTag(kr.callbrief.R.id.ui_min_sp) as? Float) ?: 18f)-0.01f })
    }
    @Test fun theHeartbeatHasTheWeatherBlockNumbersAndCodesOnly() {
        val hb=Weather.heartbeat(JSONObject(),at530)
        assertEquals("""{"ok":false,"ageH":null,"days":0,"cautions":0,"err":"none"}""",hb.toString())
        val s=JSONObject().put(Weather.CAST_KEY,Weather.toJson(Weather.parse(week(),at530-30*h,site)!!).toString()).put(Weather.ERR_KEY,"parse")
            .put(Weather.DAYS_KEY,3).put(Weather.CAUTIONS_KEY,1)
        val b=Weather.heartbeat(s,at530)
        assertEquals(false,b.get("ok"));assertEquals(30,b.getInt("ageH"));assertEquals(3,b.getInt("days"));assertEquals(1,b.getInt("cautions"));assertEquals("parse",b.getString("err"))
        assertEquals("a strange code is none","none",Weather.heartbeat(JSONObject().put(Weather.ERR_KEY,"그 밖"),at530).getString("err"))
    }
}
