package kr.callbrief.pipeline.report

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.OpenItem
import kr.callbrief.pipeline.PhoneStatus
import kr.callbrief.pipeline.PipelineSettingsKeys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** v3.5 r15-1 (DESIGN_V5 2.1-5): compose (six fixed cases), every text 200 characters at most, the picture's height and
 * words, no phone number anywhere, the upload failing (four texts), one day sent once, 404 to the retries to the phone
 * report, a token error taking the link down, and the heartbeat's kakao/report keys. The Kakao SDK is never called: a fake
 * [KakaoReport.Sender] stands in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KakaoReportTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val day=LocalDate.of(2026,11,7)
    private val at530=ReportFetch.target(day).toEpochMilli()
    private val minute=60_000L
    private val phone=Regex("01[016789][- ]?\\d{3,4}[- ]?\\d{4}|0(2|[3-6]\\d)[- ]?\\d{3,4}[- ]?\\d{4}")

    /** Records every message; [failUpload], [failFeed], [failText] (how many text sends fail) and [error] shape the answers. */
    private class FakeSender(val failUpload:Boolean=false,val failFeed:Boolean=false,var failText:Int=0,val error:String="SEND_API_InternalError"):KakaoReport.Sender {
        val sent=mutableListOf<KakaoReport.Message>();var uploads=0;var tries=0
        override suspend fun uploadImage(png:ByteArray):String? { uploads++;return if(failUpload) null else "https://k.kakaocdn.net/img/1.png" }
        override suspend fun send(message:KakaoReport.Message,imageUrl:String?):String? {
            tries++
            if(message is KakaoReport.Message.Feed) { if(failFeed) return error;assertNotNull(imageUrl);sent+=message;return null }
            if(failText>0) { failText--;return error }
            sent+=message;return null
        }
    }
    private var fake=FakeSender()

    @Before fun setup() {
        KakaoReport.appKey={ "abc123" };KakaoReport.pause={};KakaoReport.clock={ at530 };ReportFetch.clock={ at530 }
        KakaoReport.senderFactory={ fake }
        ReportFetch.scheduler={ _,p -> plans+=p }
        link(true)
    }
    @After fun reset() {
        KakaoReport.appKey={ kr.callbrief.BuildConfig.KAKAO_APP_KEY };KakaoReport.pause={ kotlinx.coroutines.delay(it) }
        KakaoReport.clock={ System.currentTimeMillis() };ReportFetch.clock={ System.currentTimeMillis() }
        KakaoReport.senderFactory=null;ReportFetch.sourceFactory=null;ReportFetch.scheduler=null;KakaoReport.linker=null
    }
    private val plans=mutableListOf<ReportFetch.Plan>()
    private fun link(on:Boolean)=SecureSettings(app).update { it.put(PipelineSettingsKeys.KAKAO_LINKED,on) }
    private fun settings()=SecureSettings(app).snapshot()

    // ---- report fixtures ----
    private fun y(who:String,what:String)=JSONObject().put("who",who).put("what",what)
    private fun t(what:String,who:String,since:String="어제 요청",status:String="")=JSONObject().put("id","t_$what").put("what",what).put("who",who)
        .put("since",since).put("status",status).put("check",false)
    private fun report(yesterday:List<JSONObject> =emptyList(),today:List<JSONObject> =emptyList(),inProgress:List<String> =emptyList(),
                       check:List<Pair<String,String>> =emptyList(),calls:Int=yesterday.size)=JSONObject().put("day",day.toString())
        .put("generatedMs",at530-40*minute).put("tidyRev",12)
        .put("head",JSONObject().put("calls",calls).put("newChecks",3).put("todayChecks",today.size).put("issues",inProgress.size))
        .put("yesterday",JSONArray(yesterday)).put("today",JSONArray(today))
        .put("inProgress",JSONArray(inProgress.map { JSONObject().put("what",it) }))
        .put("check",JSONArray(check.map { (w,who) -> JSONObject().put("what",w).put("who",who) }))
    private val one=report(listOf(y("△△철강 김민수 대표","철근 단가 재협의, 견적서 금요일까지 보내기로")),listOf(t("견적서 보내기","△△철강")))
    private fun eight()=report((1..8).map { y("업체$it 담당자","자재 입고 일정 확인하고 검수 입회 요청, 금요일 오전 10시 현장 도착 예정") },
        (1..8).map { t("체크할 일 $it 서류 정리해서 보내기","본사 박과장","${it}일 전 요청",if(it%2==0) "메일 발송 확인 완료" else "") },
        listOf("철근 단가 재견적: 업체 회신 대기, 금요일 계약 예정","타설 일정 조정"),listOf("13층/30층 중 어느 쪽인지" to "△△철강"))
    private fun texts(c:KakaoReport.Composed)=listOfNotNull(c.todayText)+c.fallback
    private fun assertFits(c:KakaoReport.Composed) {
        for(m in texts(c)) {
            assertTrue("over 200: ${m.text.length}",KakaoReport.length(m.text)<=KakaoReport.LIMIT)
            assertFalse("phone number in ${m.text}",phone.containsMatchIn(m.text))
            assertFalse(m.text.contains('·'));assertFalse(m.text.contains('…'))
        }
        c.feed?.let { assertFalse(phone.containsMatchIn(it.title+it.body));assertTrue(KakaoReport.length(it.title)<=40) }
    }

    // ---- compose: the six fixed cases (0건, 1건, 8건, 넘침 "외 n건", 확인 필요, 폴백) ----
    @Test fun composeNoCalls() {
        val c=KakaoReport.compose(report(calls=0))
        assertFits(c)
        assertNotNull(c.feed);assertNull("nothing to check: no second text",c.todayText)
        assertEquals("one fallback text only",1,c.fallback.size)
        assertTrue(c.fallback[0].text.startsWith("[이음 아침 보고] 11/7(토)"))
        assertTrue(c.fallback[0].text.contains("어제 통화 0건"));assertTrue(c.fallback[0].text.contains("어제 업무 통화 없음"))
        assertEquals("[이음 아침 보고] 11/7(토)",c.feed!!.title);assertEquals("어제 통화 0건, 새 체크 3개, 오늘 챙길 일 0개",c.feed!!.body)
    }
    @Test fun composeOneCall() {
        val c=KakaoReport.compose(one)
        assertFits(c)
        assertEquals("[오늘 챙길 일] 11/7(토)\n1. 견적서 보내기 (△△철강, 어제 요청)",c.todayText!!.text)
        assertEquals(KakaoReport.TASKS_LINK,c.todayText!!.link)
        assertEquals(2,c.fallback.size)
        assertEquals("[이음 아침 보고] 11/7(토)\n어제 통화 1건, 새 체크 3개, 오늘 챙길 일 1개\n어제 정리\n1. △△철강 김민수 대표: 철근 단가 재협의, 견적서 금요일까지 보내기로",c.fallback[0].text)
        // The calls button opens yesterday's calls.
        val dayMs=LocalDate.of(2026,11,6).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        assertEquals("screen=calls&dayMs=$dayMs",c.fallback[0].link);assertEquals(c.fallback[0].link,c.feed!!.link)
    }
    @Test fun composeEightCallsSplitsIntoFourTextsWithMore() {
        val c=KakaoReport.compose(eight())
        assertFits(c)
        assertEquals("① ② ③ ④",4,c.fallback.size)
        assertTrue(c.fallback[1].text.startsWith("[어제 정리 계속]"))
        assertTrue(c.fallback[2].text.startsWith("[오늘 챙길 일]"));assertTrue(c.fallback[3].text.startsWith("[진행 중과 확인 필요]"))
        assertTrue(c.fallback[3].text.contains("확인 필요: 13층/30층 중 어느 쪽인지 (△△철강)"))
        // 8 long lines do not fit in ① and ②: what is left is told as "외 n건", and nothing is lost from the count.
        val shown=c.fallback.take(2).sumOf { m -> m.text.lines().count { Regex("^\\d+\\. ").containsMatchIn(it) } }
        val more=c.fallback[1].text.lines().last().let { Regex("^외 (\\d+)건$").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
        assertEquals(8,shown+more)
    }
    @Test fun composeTodayTextIsTopFourAndTellsTheRest() {
        val c=KakaoReport.compose(eight())
        val lines=c.todayText!!.text.lines()
        assertEquals("[오늘 챙길 일] 11/7(토)",lines.first())
        assertTrue(lines.any { it.startsWith("1. 체크할 일 1") })
        assertFalse(lines.any { it.startsWith("5. ") })
        assertTrue("4 left out at least",Regex("^외 (\\d+)건$").find(lines.last())!!.groupValues[1].toInt()>=4)
        assertTrue(lines.any { it.contains("메일 발송 확인 완료") })
    }
    @Test fun composeCheckAndNumbersMasked() {
        val r=report(listOf(y("○○전기 010-1234-5678","배선 자재 내일 입고, 02-123-4567로 연락 달라고 함")),
            listOf(t("견적서 보내기 kim@steel.co.kr","△△철강","오늘 요청","확인 필요")),check=listOf("13층/30층 중 어느 쪽인지" to "031 123 4567"))
        val c=KakaoReport.compose(r)
        assertFits(c)
        val all=texts(c).joinToString("\n") { it.text }
        assertTrue(all.contains("[번호]"));assertFalse(all.contains("1234"));assertFalse(all.contains("@steel"))
        assertTrue(all.contains("확인 필요: 13층/30층 중 어느 쪽인지"))
        val drawn=KakaoReport.renderFull(r).lines.joinToString("\n") { it.text }
        assertFalse(phone.containsMatchIn(drawn));assertTrue(drawn.contains("확인 필요"))
    }
    @Test fun composePhoneFallbackIsOneText() {
        val open=(1..6).map { OpenItem("i$it","서류 $it 보내기","","open",1,"",null,"업체$it",at530-it*86_400_000L) }
        val r=ReportFetch.phoneReport(day,open)
        val c=KakaoReport.compose(r,phoneFallback=true)
        assertFits(c)
        assertNull(c.feed);assertTrue(c.fallback.isEmpty())
        val lines=c.todayText!!.text.lines()
        assertEquals("[이음 아침 보고] 11/7(토) (새벽 정리 없이 폰에서 만든 보고)",lines[0])
        assertEquals("오늘 챙길 일 6개",lines[1])
        assertEquals("1. 서류 1 보내기 (업체1, 어제 요청)",lines[2])
        assertEquals("외 2건",lines.last())
        assertEquals(4,lines.count { Regex("^\\d\\. ").containsMatchIn(it) })
    }
    @Test fun aVeryLongLineIsCutNotDropped() {
        val r=report(listOf(y("업체","가".repeat(300))),listOf(t("나".repeat(300),"본사")))
        val c=KakaoReport.compose(r)
        assertFits(c)
        assertTrue(c.todayText!!.text.contains("1. 나"))
        assertTrue(c.fallback[0].text.contains("1. 업체: 가"))
    }

    // ---- render ----
    @Test fun renderIsTallWideAndReadable() {
        val r=KakaoReport.renderFull(eight(),app)
        val b=BitmapFactory.decodeByteArray(r.png,0,r.png.size)
        assertEquals(1080,b.width);assertEquals(r.height,b.height)
        assertTrue(b.height<=KakaoReport.MAX_HEIGHT);assertTrue(r.png.size<=KakaoReport.MAX_PNG)
        assertTrue(KakaoReport.BODY_SP>=20f)
        val words=r.lines.map { it.text }
        assertEquals("11/7(토) 아침 보고",words[0])
        listOf("어제 정리","오늘 챙길 일","진행 중","확인 필요").forEach { assertTrue(it,words.contains(it)) }
        assertTrue(words.any { it.startsWith("1. 업체1 담당자: ") })
        // Deterministic: the same lines and size each time.
        val again=KakaoReport.renderFull(eight(),app)
        assertEquals(r.lines,again.lines);assertEquals(r.height,again.height)
    }
    @Test fun renderShrinksSectionsToFitAndTellsTheRest() {
        val big=report((1..8).map { y("업체$it 담당자 이름이 꽤 긴 사람","가나다라마바사 ".repeat(9)) },(1..8).map { t("라마바사 ".repeat(12),"본사 박과장") },
            listOf("가".repeat(60),"나".repeat(60),"다".repeat(60)),listOf("라".repeat(50) to "업체","마".repeat(50) to "업체","바".repeat(50) to "업체"))
        val r=KakaoReport.renderFull(big,app)
        assertTrue(r.height<=KakaoReport.MAX_HEIGHT)
        assertTrue(r.lines.any { it.kind==KakaoReport.Kind.NOTE && Regex("^외 \\d+건$").matches(it.text) })
        assertTrue(r.lines.count { it.kind==KakaoReport.Kind.ITEM }>=4)
    }
    @Test fun renderZeroCalls() {
        val r=KakaoReport.renderFull(report(calls=0),app)
        assertTrue(r.lines.any { it.text=="어제 업무 통화 없음" });assertTrue(r.lines.any { it.text=="새로 챙길 일 없음" })
        assertTrue(r.height in 300..1200)
    }

    // ---- send ----
    @Test fun sendsFeedThenTextAndCounts() = runBlocking {
        assertNull(KakaoReport.send(app,eight(),KakaoReport.SOURCE_ROUTINE))
        assertEquals(1,fake.uploads)
        assertEquals(2,fake.sent.size);assertTrue(fake.sent[0] is KakaoReport.Message.Feed);assertTrue(fake.sent[1] is KakaoReport.Message.Text)
        val s=settings()
        assertEquals(day.toString(),s.getString(PipelineSettingsKeys.REPORT_DAY));assertEquals("routine",s.getString(PipelineSettingsKeys.REPORT_SOURCE))
        assertEquals(1L,s.getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT));assertEquals(at530,s.getLong(PipelineSettingsKeys.KAKAO_LAST_SENT_MS))
        assertTrue(KakaoReport.sentToday(app,at530+minute))
        assertFalse(KakaoReport.sentToday(app,at530+86_400_000L))
    }
    @Test fun sameDayIsNeverSentTwice() = runBlocking {
        assertNull(KakaoReport.send(app,one,KakaoReport.SOURCE_ROUTINE))
        val n=fake.sent.size
        assertEquals(KakaoReport.ALREADY_SENT,KakaoReport.send(app,one,KakaoReport.SOURCE_ROUTINE))
        assertEquals(KakaoReport.ALREADY_SENT,KakaoReport.sendNow(app))
        assertEquals(n,fake.sent.size);assertEquals(1L,settings().getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT))
    }
    @Test fun uploadFailureSendsTheFourTexts() = runBlocking {
        fake=FakeSender(failUpload=true)
        assertNull(KakaoReport.send(app,eight(),KakaoReport.SOURCE_ROUTINE))
        assertEquals(4,fake.sent.size);assertTrue(fake.sent.all { it is KakaoReport.Message.Text })
        assertTrue(fake.sent.all { KakaoReport.length((it as KakaoReport.Message.Text).text)<=200 })
        assertEquals(1L,settings().getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT))
    }
    @Test fun feedFailureFallsBackAndAFailedTextIsTriedOnceMore() = runBlocking {
        fake=FakeSender(failFeed=true,failText=1)
        assertNull(KakaoReport.send(app,eight(),KakaoReport.SOURCE_ROUTINE))
        assertEquals(4,fake.sent.size)
        assertEquals("feed + 4 texts + 1 retry",6,fake.tries)
    }
    @Test fun aTextFailingTwiceIsCountedAsFailed() = runBlocking {
        fake=FakeSender(failUpload=true,failText=2)
        assertEquals("SEND_API_InternalError",KakaoReport.send(app,eight(),KakaoReport.SOURCE_ROUTINE))
        assertEquals("the other three still go",3,fake.sent.size)
        val s=settings()
        assertEquals(1L,s.getLong(PipelineSettingsKeys.KAKAO_FAILED_COUNT));assertEquals("SEND_API_InternalError",s.getString(PipelineSettingsKeys.KAKAO_LAST_ERROR))
        assertFalse(KakaoReport.sentToday(app,at530));assertEquals("some went: the day is not sent again",day.toString(),s.getString(PipelineSettingsKeys.REPORT_DAY))
        assertTrue(s.getBoolean(PipelineSettingsKeys.KAKAO_LINKED))
    }
    @Test fun tokenFailureTakesTheLinkDown() = runBlocking {
        fake=FakeSender(failFeed=true,error="TOKEN_INVALID")
        assertEquals("TOKEN_INVALID",KakaoReport.send(app,eight(),KakaoReport.SOURCE_ROUTINE))
        assertTrue("no fallback on a dead token",fake.sent.isEmpty())
        val s=settings()
        assertFalse(s.getBoolean(PipelineSettingsKeys.KAKAO_LINKED));assertFalse(s.has(PipelineSettingsKeys.REPORT_DAY))
        assertEquals(KakaoReport.Link.BROKEN,KakaoReport.linkState(app))
        assertEquals(KakaoReport.NOT_LINKED,KakaoReport.send(app,eight(),KakaoReport.SOURCE_ROUTINE))
    }
    @Test fun linkStatesAndLogin() = runBlocking {
        assertEquals(KakaoReport.Link.LINKED,KakaoReport.linkState(app))
        KakaoReport.linker={ null };link(false)
        assertEquals(KakaoReport.Link.NOT_LINKED,KakaoReport.linkState(app))
        assertNull(KakaoReport.link(app));assertEquals(KakaoReport.Link.LINKED,KakaoReport.linkState(app))
        KakaoReport.linker={ "CANCELLED" };link(false)
        assertEquals("CANCELLED",KakaoReport.link(app));assertEquals(KakaoReport.Link.NOT_LINKED,KakaoReport.linkState(app))
        KakaoReport.appKey={ "" }
        assertFalse(KakaoReport.available());assertEquals(KakaoReport.Link.UNAVAILABLE,KakaoReport.linkState(app))
        assertEquals(KakaoReport.UNAVAILABLE,KakaoReport.send(app,one,KakaoReport.SOURCE_ROUTINE))
    }

    // ---- ReportFetch: 05:30, 404 → 05:35, 05:45, 06:00 → the phone's report ----
    private class Inbox(var answer:(String,String?)->ReportFetch.Response):ReportFetch.Source {
        val asked=mutableListOf<Pair<String,String?>>()
        override fun get(path:String,etag:String?):ReportFetch.Response { asked+=path to etag;return answer(path,etag) }
    }
    @Test fun fetchedReportIsSentAndTheETagKept() = runBlocking {
        val inbox=Inbox { _,etag -> if(etag=="\"e1\"") ReportFetch.Response(304,null,"\"e1\"") else ReportFetch.Response(200,eight().toString(),"\"e1\"") }
        ReportFetch.sourceFactory={ inbox }
        assertEquals(ReportFetch.Outcome.SENT,ReportFetch.run(app,day,0))
        assertEquals("report/2026-11-07.json" to null,inbox.asked[0])
        assertEquals(2,fake.sent.size)
        assertEquals("tomorrow's 05:30",ReportFetch.plan(day.plusDays(1),0),plans.last())
        // A second read asks with the ETag and takes the kept copy on 304.
        assertTrue(ReportFetch.fetch(app,day) is ReportFetch.Fetch.Found);assertEquals("\"e1\"",inbox.asked.last().second)
        assertNotNull(ReportFetch.today(app,day))
    }
    @Test fun missingReportRetriesThenSendsThePhoneReport() = runBlocking {
        ReportFetch.sourceFactory={ Inbox { _,_ -> ReportFetch.Response(404,null,null) } }
        for(attempt in 0..2) {
            ReportFetch.clock={ ReportFetch.plan(day,attempt).atMs }
            assertEquals(ReportFetch.Outcome.RETRY_LATER,ReportFetch.run(app,day,attempt))
            assertEquals(ReportFetch.plan(day,attempt+1),plans.last())
        }
        assertEquals(listOf(0L,5L,15L,30L),(0..3).map { (ReportFetch.plan(day,it).atMs-at530)/minute })
        assertTrue(fake.sent.isEmpty())
        ReportFetch.clock={ ReportFetch.plan(day,3).atMs };KakaoReport.clock={ ReportFetch.plan(day,3).atMs }
        assertEquals(ReportFetch.Outcome.PHONE_SENT,ReportFetch.run(app,day,3))
        assertEquals(1,fake.sent.size)
        assertTrue((fake.sent[0] as KakaoReport.Message.Text).text.contains("새벽 정리 없이 폰에서 만든 보고"))
        assertEquals("phone",settings().getString(PipelineSettingsKeys.REPORT_SOURCE))
        assertEquals(ReportFetch.plan(day.plusDays(1),0),plans.last())
    }
    @Test fun reportOfAnotherDayCountsAsMissing() {
        ReportFetch.sourceFactory={ Inbox { _,_ -> ReportFetch.Response(200,one.put("day","2026-11-06").toString(),"\"x\"") } }
        assertEquals(ReportFetch.Fetch.Missing,ReportFetch.fetch(app,day))
    }
    @Test fun nothingAfterNineAndNothingWhenOff() = runBlocking {
        ReportFetch.sourceFactory={ Inbox { _,_ -> ReportFetch.Response(200,one.toString(),null) } }
        ReportFetch.clock={ at530+3*60*minute+30*minute }
        assertEquals(ReportFetch.Outcome.TOO_LATE,ReportFetch.run(app,day,1))
        ReportFetch.clock={ at530 }
        SecureSettings(app).update { it.put(PipelineSettingsKeys.MORNING_KAKAO,false) }
        assertEquals(ReportFetch.Outcome.OFF,ReportFetch.run(app,day,0))
        assertTrue(fake.sent.isEmpty())
    }
    @Test fun ensureQueuesTheNextHalfPastFive() {
        ReportFetch.clock={ at530-minute }
        ReportFetch.ensure(app);assertEquals(ReportFetch.plan(day,0),plans.last())
        ReportFetch.clock={ at530+minute }
        ReportFetch.ensure(app);assertEquals(ReportFetch.plan(day.plusDays(1),0),plans.last())
        KakaoReport.appKey={ "" };plans.clear()
        ReportFetch.ensure(app);assertTrue(plans.isEmpty())
        assertEquals("ieum.report.morning",ReportFetch.NAME)
    }
    @Test fun phoneReportRanksLikeHome() {
        val open=listOf(OpenItem("a","오래된 일","","open",1,"",null,"업체A",at530-5*86_400_000L),
            OpenItem("b","확인할 일","","needs_confirmation",1,"","김과장",null,at530-3*86_400_000L),
            OpenItem("c","오늘 일","","open",1,"",null,"업체C",at530-minute))
        val today=ReportFetch.phoneReport(day,open).getJSONArray("today")
        assertEquals(listOf("확인할 일","오늘 일","오래된 일"),(0 until today.length()).map { today.getJSONObject(it).getString("what") })
        assertEquals("확인 필요",today.getJSONObject(0).getString("status"));assertEquals("김과장",today.getJSONObject(0).getString("who"))
        assertEquals("5일 전 요청",today.getJSONObject(2).getString("since"))
    }

    // ---- heartbeat ----
    @Test fun heartbeatKakaoAndReportKeys() = runBlocking {
        KakaoReport.send(app,one,KakaoReport.SOURCE_ROUTINE)
        val o=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.5",1050,0,null,5,6,"READY","","",12,v35=PhoneStatus.v35(settings())))
        val k=o.getJSONObject("kakao");val r=o.getJSONObject("report")
        assertTrue(k.getBoolean("linked"));assertEquals(1L,k.getLong("sent"));assertEquals(0L,k.getLong("failed"))
        assertEquals(at530,k.getLong("lastSentMs"));assertTrue(k.isNull("error"))
        assertEquals("2026-11-07",r.getString("day"));assertEquals("routine",r.getString("source"))
        assertFalse(Regex("[가-힣]").containsMatchIn(o.getJSONObject("kakao").toString()+r.toString()))
    }
    @Test fun sdkCodesAndLinks() {
        assertEquals(mapOf("screen" to "calls","dayMs" to "12"),KakaoSdkSender.params("screen=calls&dayMs=12"))
        assertEquals("ABC_1",KakaoReport.code("ABC_1 김 ★"))
        val png=KakaoReport.render(one,app)
        assertEquals(1080,KakaoSdkSender.pngSize(png).first)
    }
}
