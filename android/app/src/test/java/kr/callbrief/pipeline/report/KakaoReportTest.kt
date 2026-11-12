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
        KakaoReport.coverageOf={ _,_,_ -> null } // the report's own count unless a test sets one
        ReportFetch.scheduler={ _,p -> plans+=p }
        link(true)
        // The tests below this line were written for the picture report (v3.5 to v3.6); the chat report's own tests turn it off.
        SecureSettings(app).update { it.put(KakaoReport.PICTURE,true) }
    }
    @After fun reset() {
        KakaoReport.appKey={ kr.callbrief.BuildConfig.KAKAO_APP_KEY };KakaoReport.pause={ kotlinx.coroutines.delay(it) }
        KakaoReport.clock={ System.currentTimeMillis() };ReportFetch.clock={ System.currentTimeMillis() }
        KakaoReport.senderFactory=null;ReportFetch.sourceFactory=null;ReportFetch.scheduler=null;KakaoReport.linker=null;KakaoReport.coverageOf=null
        KakaoReport.listTemplateId={ kr.callbrief.BuildConfig.KAKAO_LIST_TEMPLATE_ID };KakaoReport.webBase={ kr.callbrief.BuildConfig.KAKAO_WEB_BASE }
        KakaoReport.tagPng=realTagPng
    }
    private val realTagPng=KakaoReport.tagPng
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
        assertTrue(c.fallback[0].text.contains("어제 정리한 통화가 없어요"));assertTrue(c.fallback[0].text.contains("어제 업무 통화 없음"))
        assertEquals("[이음 아침 보고] 11/7(토)",c.feed!!.title);assertEquals("어제 정리한 통화가 없어요\n새 체크 3개, 오늘 챙길 일 0개",c.feed!!.body)
    }
    @Test fun composeOneCall() {
        val c=KakaoReport.compose(one)
        assertFits(c)
        assertEquals("[오늘 챙길 일] 11/7(토)\n1. 견적서 보내기 (△△철강, 어제 요청)",c.todayText!!.text)
        assertEquals(KakaoReport.TASKS_LINK,c.todayText!!.link)
        assertEquals(2,c.fallback.size)
        assertEquals("[이음 아침 보고] 11/7(토)\n어제 통화 1건을 정리했어요\n새 체크 3개, 오늘 챙길 일 1개\n어제 정리\n1. △△철강 김민수 대표: 철근 단가 재협의, 견적서 금요일까지 보내기로",c.fallback[0].text)
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
        assertEquals(n,fake.sent.size);assertEquals(1L,settings().getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT))
    }
    /** r15-4 (M1): [지금 보내 보기] is a test send: once a day by its own key, and the 05:30 report, its counters and the
     * morning notice's "sent today" stay untouched. */
    @Test fun aTestSendLeavesTheMorningReportAlone() = runBlocking {
        val before=settings()
        assertNull(KakaoReport.sendNow(app))
        assertTrue(fake.sent.isNotEmpty())
        val s=settings()
        assertFalse(s.has(PipelineSettingsKeys.REPORT_DAY));assertFalse(s.has(PipelineSettingsKeys.KAKAO_LAST_SENT_MS))
        assertEquals(before.optLong(PipelineSettingsKeys.KAKAO_SENT_COUNT,0),s.optLong(PipelineSettingsKeys.KAKAO_SENT_COUNT,0))
        assertEquals(day.toString(),s.getString(KakaoReport.TEST_SENT_DAY))
        assertFalse("the app notice is not covered by a test",KakaoReport.sentToday(app,at530))
        // Once a day.
        val n=fake.sent.size
        assertEquals(KakaoReport.ALREADY_SENT,KakaoReport.sendNow(app));assertEquals(n,fake.sent.size)
        // The 05:30 report still goes in full that day.
        assertNull(KakaoReport.send(app,one,KakaoReport.SOURCE_ROUTINE))
        assertTrue(KakaoReport.sentToday(app,at530+minute));assertEquals(1L,settings().getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT))
        // The next day the test button works again.
        KakaoReport.clock={ at530+86_400_000L }
        assertNull(KakaoReport.sendNow(app))
    }
    @Test fun aTestSendWithADeadTokenTakesTheLinkDown() = runBlocking {
        fake=FakeSender(failFeed=true,failText=9,error="TOKEN_INVALID")
        assertEquals("TOKEN_INVALID",KakaoReport.sendNow(app))
        val s=settings()
        assertFalse(s.getBoolean(PipelineSettingsKeys.KAKAO_LINKED));assertFalse(s.has(KakaoReport.TEST_SENT_DAY))
        assertFalse(s.has(PipelineSettingsKeys.KAKAO_FAILED_COUNT))
        assertEquals(KakaoReport.Link.BROKEN,KakaoReport.linkState(app))
    }
    /** r15-4 (L5): a cancelled try (the work stopped) is not swallowed as FAILED, and queues nothing. */
    @Test fun aCancelledTryIsCancelledNotFailed() {
        ReportFetch.sourceFactory={ object:ReportFetch.Source {
            override fun get(path:String,etag:String?):ReportFetch.Response = throw kotlinx.coroutines.CancellationException("stopped") } }
        plans.clear()
        try { runBlocking { ReportFetch.run(app,day,0) };fail("expected the cancellation") } catch(_:kotlinx.coroutines.CancellationException) {}
        assertTrue(plans.isEmpty())
    }
    @Test fun aHeartbeatTellsTheSwitch() {
        assertTrue(PhoneStatus.v35(settings()).kakaoOn)
        SecureSettings(app).update { it.put(PipelineSettingsKeys.MORNING_KAKAO,false) }
        val o=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.5",1050,0,null,5,6,"READY","","",12,v35=PhoneStatus.v35(settings())))
        assertFalse(o.getJSONObject("kakao").getBoolean("on"))
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
        KakaoReport.linker={ "LOGIN_AUTH_Misconfigured" }
        assertEquals("LOGIN_AUTH_Misconfigured",KakaoReport.link(app))
        assertEquals(KakaoReport.Link.NOT_LINKED,KakaoReport.linkState(app))
        assertEquals("LINK_LOGIN_AUTH_Misconfigured",settings().getString(PipelineSettingsKeys.KAKAO_LAST_ERROR))
        KakaoReport.linker={ "TOKEN_NOTREGISTEREDUSER" };KakaoReport.link(app)
        assertEquals(KakaoReport.Link.NOT_LINKED,KakaoReport.linkState(app))
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
        assertEquals(at530,k.getLong("lastSentMs"));assertTrue(k.isNull("error"));assertTrue(k.getBoolean("on"))
        assertEquals("2026-11-07",r.getString("day"));assertEquals("routine",r.getString("source"))
        assertFalse(Regex("[가-힣]").containsMatchIn(o.getJSONObject("kakao").toString()+r.toString()))
    }
    // ---- v3.7 r17-2 (DESIGN_V7 3.2 M3, M4): what the report leaves out, and the calls summed up late ----
    private fun withV8(r:JSONObject,pending:Int,late:List<JSONObject> =emptyList())=r.apply { getJSONObject("head").put("pendingCalls",pending);put("late",JSONArray(late)) }
    @Test fun theFirstLineSaysHowManyStillWait() {
        val c=KakaoReport.compose(withV8(report((1..3).map { y("업체$it","자재 입고") },calls=14),9))
        assertFits(c)
        assertEquals("어제 통화 14건을 정리했어요. 9건은 아직 글자로 바꾸는 중이에요\n새 체크 3개, 오늘 챙길 일 0개",c.feed!!.body)
        assertEquals("어제 통화 14건을 정리했어요. 9건은 아직 글자로 바꾸는 중이에요",c.fallback[0].text.lines()[1])
        assertEquals("nothing waiting: no second sentence","어제 통화 14건을 정리했어요",KakaoReport.headLine(KakaoReport.read(withV8(report(calls=14),0),false)))
        val drawn=KakaoReport.renderFull(withV8(report(calls=14),9),app).lines
        assertEquals(KakaoReport.Kind.HEAD,drawn[1].kind);assertEquals("어제 통화 14건을 정리했어요. 9건은 아직 글자로 바꾸는 중이에요",drawn[1].text)
        // A v7 report (no pendingCalls, no late) reads as nothing waiting.
        assertEquals(0,KakaoReport.read(one,false).pending);assertTrue(KakaoReport.read(one,false).late.isEmpty())
        // The phone's own 06:00 report says it too.
        val phone=ReportFetch.phoneReport(day,emptyList()).apply { getJSONObject("head").put("pendingCalls",2) }
        assertTrue(KakaoReport.compose(phone,phoneFallback=true).todayText!!.text.contains("어제 통화 2건은 아직 글자로 바꾸는 중이에요"))
    }
    @Test fun lateCallsAreTheLastBlock() {
        val late=(1..7).map { y("업체$it 담당자","도면 회신 받음, 다음 주 시공") }
        val r=KakaoReport.read(withV8(eight(),0,late),false)
        assertEquals("five at most",KakaoReport.LATE_ITEMS,r.late.size);assertEquals("업체1 담당자: 도면 회신 받음, 다음 주 시공",r.late[0])
        val c=KakaoReport.compose(withV8(eight(),0,late))
        assertFits(c)
        val last=c.fallback.last()
        assertTrue(last.text,last.text.startsWith("[뒤늦게 정리된 통화]"));assertTrue(last.text.contains("1. 업체1 담당자: 도면 회신 받음"))
        val words=KakaoReport.renderFull(withV8(eight(),0,late),app).lines
        val at=words.indexOfFirst { it.text==KakaoReport.LATE_TITLE && it.kind==KakaoReport.Kind.SECTION }
        assertTrue("the picture's last section",at>words.indexOfFirst { it.text=="확인 필요" })
        // A day with a short to-do list: the daily text ends with the late block.
        val small=KakaoReport.compose(withV8(one,0,late.take(2)))
        assertFits(small)
        assertTrue(small.todayText!!.text,small.todayText!!.text.contains("\n뒤늦게 정리된 통화\n1. 업체1 담당자"))
        // No to-do at all: the late block is the text, and it opens the calls.
        val only=KakaoReport.compose(withV8(report(calls=0),0,late.take(1)))
        assertTrue(only.todayText!!.text.startsWith("[뒤늦게 정리된 통화] 11/7(토)"));assertEquals(KakaoReport.callsLink(day),only.todayText!!.link)
    }
    @Test fun theSendCountsAgainAndKeepsTheCoverage() = runBlocking {
        var asked:Pair<LocalDate,Long?>?=null
        KakaoReport.coverageOf={ _,d,by -> asked=d to by;CallRecordStore.DayCoverage(calls=20,analyzed=11,pending=6,pendingMin=40) }
        assertNull(KakaoReport.send(app,withV8(report((1..3).map { y("업체$it","자재 입고") },calls=11),9),KakaoReport.SOURCE_ROUTINE))
        assertEquals("yesterday, by the report's own time",LocalDate.of(2026,11,6) to (at530-40*minute),asked)
        val feed=fake.sent.first() as KakaoReport.Message.Feed
        assertTrue(feed.body,feed.body.startsWith("어제 통화 11건을 정리했어요. 6건은 아직 글자로 바꾸는 중이에요"))
        val cov=ReportCoverage.snapshot(settings())!!
        assertEquals("2026-11-07",cov.getString("day"));assertEquals(20,cov.getInt("yesterdayCalls"));assertEquals(11,cov.getInt("inReport"))
        assertEquals(6,cov.getInt("pending"));assertEquals(at530,cov.getLong("atMs"));assertEquals("routine",cov.getString("source"))
        assertTrue(ReportCoverage.hasDay(settings(),day))
        assertFalse(Regex("[가-힣]").containsMatchIn(cov.toString()))
    }
    @Test fun theCoverageSnapshotIsPure() {
        assertNull(ReportCoverage.snapshot(null));assertNull(ReportCoverage.snapshot(JSONObject()))
        assertNull(ReportCoverage.snapshot(JSONObject().put(PipelineSettingsKeys.REPORT_COVERAGE,"{")))
        assertNull("no day",ReportCoverage.snapshot(JSONObject().put(PipelineSettingsKeys.REPORT_COVERAGE,JSONObject().put("yesterdayCalls",3).toString())))
        val o=JSONObject();ReportCoverage.note(o,day,CallRecordStore.DayCoverage(5,7,9,1),7,"phone",42)
        val c=ReportCoverage.snapshot(o)!!
        assertEquals("never more than the base",5,c.getInt("inReport"));assertEquals(5,c.getInt("pending"));assertEquals("phone",c.getString("source"))
        assertEquals(listOf("day","yesterdayCalls","inReport","pending","atMs","source"),c.keys().asSequence().toList())
        c.put("day","x");assertEquals("a new object each call","2026-11-07",ReportCoverage.snapshot(o)!!.getString("day"))
    }
    @Test fun sdkCodesAndLinks() {
        assertEquals(mapOf("screen" to "calls","dayMs" to "12"),KakaoSdkSender.params("screen=calls&dayMs=12"))
        assertEquals("ABC_1",KakaoReport.code("ABC_1 김 ★"))
        val png=KakaoReport.render(one,app)
        assertEquals(1080,KakaoSdkSender.pngSize(png).first)
    }

    // ---- v3.7 (user 11/12): the whole report as texts read inside KakaoTalk ----
    @Test fun theChatReportIsThreeTextsAtMostAndOnlyTheLastHasTheButton() {
        val c=KakaoReport.compose(eight(),picture=false)
        assertNull("no picture is drawn",c.feed)
        val chat=c.chat
        assertTrue("two or three texts: ${chat.size}",chat.size in 2..KakaoReport.MAX_TEXTS);assertEquals(3,KakaoReport.MAX_TEXTS)
        chat.forEach { assertTrue(it.text,KakaoReport.length(it.text)<=KakaoReport.LIMIT) }
        assertTrue(chat[0].text.startsWith("[이음 아침 보고] 11/7(토)\n"))
        // Read in the chat: no link but on the last text, which opens the to-dos.
        assertTrue(chat.dropLast(1).all { it.link==KakaoReport.NO_LINK });assertEquals(KakaoReport.TASKS_LINK,chat.last().link)
        val all=chat.joinToString("\n") { it.text }
        assertFalse(all.contains(KakaoReport.CHAT_MORE))
        // Every block is there under its title, the to-dos before yesterday's calls; what did not fit is counted.
        val r=KakaoReport.read(eight(),false)
        listOf("[오늘 챙길 일]","[진행 중]","[확인 필요]","[어제 정리]").forEach { assertTrue(it,all.contains(it)) }
        assertTrue(all.indexOf("[오늘 챙길 일]") in 0 until all.indexOf("[어제 정리]"))
        val numbered=all.lines().count { Regex("^\\d+\\. ").containsMatchIn(it) }
        val more=all.lines().sumOf { Regex("^외 (\\d+)건$").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
        val dots=all.lines().count { it.startsWith("· ") }
        assertEquals("shown and counted lines are all the lines",r.today.size+r.yesterday.size+r.inProgress.size+r.check.size,numbered+dots+more)
        assertTrue("at least one line of each block",numbered>=2 && dots>=2)
        chat.drop(1).forEach { assertTrue(it.text,it.text.startsWith("[")) }
        assertFalse("a title is never the last line of a text",chat.any { it.text.lines().last().startsWith("[") })
    }
    @Test fun aSmallReportIsOneTextWithTheButtonAndEveryLine() {
        val chat=KakaoReport.compose(report(calls=0),picture=false).chat
        assertEquals(1,chat.size);assertEquals(KakaoReport.TASKS_LINK,chat[0].link)
        assertTrue(chat[0].text,chat[0].text.contains("\n\n[어제 정리]\n어제 업무 통화 없음"))
        val few=KakaoReport.compose(one,picture=false).chat
        val all=few.joinToString("\n") { it.text }
        assertFalse("a report that fits loses nothing",Regex("외 \\d+건").containsMatchIn(all))
        KakaoReport.read(one,false).let { r -> (r.today+r.yesterday).forEach { assertTrue(it,all.contains(it)) } }
    }
    @Test fun aVeryLongReportStillFitsThreeTexts() {
        val long=(1..8).map { "아주 긴 일 ".repeat(8)+it }
        val r=KakaoReport.Report(day,40,8,8,long,long,long.take(3),long.take(3),false,0,long.take(5))
        val chat=KakaoReport.chat(r,"11/7(토)")
        assertTrue(chat.size<=KakaoReport.MAX_TEXTS)
        chat.forEach { assertTrue(KakaoReport.length(it.text)<=KakaoReport.LIMIT) }
        assertEquals(KakaoReport.TASKS_LINK,chat.last().link)
        val all=chat.joinToString("\n") { it.text }
        listOf("[오늘 챙길 일]","[어제 정리]","[${KakaoReport.LATE_TITLE}]").forEach { assertTrue(it,all.contains(it)) }
    }
    @Test fun aTextWithoutALinkThatDoesNotGoIsSentAgainWithTheAppLink() = runBlocking {
        val seen=mutableListOf<KakaoReport.Message>()
        val sender=object:KakaoReport.Sender {
            override suspend fun uploadImage(png:ByteArray):String?=null
            override suspend fun send(message:KakaoReport.Message,imageUrl:String?):String? {
                seen+=message;return if(message.link==KakaoReport.NO_LINK) "SEND_API_IllegalParams" else null }
        }
        val c=KakaoReport.compose(eight(),picture=false)
        val out=KakaoReport.deliver(sender,c.copy(feed=null,todayText=null,fallback=c.chat))
        assertNull(out.error);assertEquals(c.chat.size,out.sent);assertEquals(c.chat.size-1,out.plainRetried)
        assertEquals("each text without a link: once as it is, once with the app link",(c.chat.size-1)*2+1,seen.size)
    }
    @Test fun byDefaultTheMorningReportGoesAsTextsOnly() = runBlocking {
        SecureSettings(app).update { it.remove(KakaoReport.PICTURE) }
        // r18: one to-do only (two or more go as the list bubbles, below).
        val oneToDo=eight().apply { put("today",JSONArray().put(getJSONArray("today").getJSONObject(0)));getJSONObject("head").put("todayChecks",1) }
        assertNull(KakaoReport.send(app,oneToDo,KakaoReport.SOURCE_ROUTINE))
        assertEquals("no picture goes up",0,fake.uploads)
        assertTrue(fake.sent.size in 2..3);assertTrue(fake.sent.all { it is KakaoReport.Message.Text })
        assertEquals(KakaoReport.TASKS_LINK,fake.sent.last().link);assertTrue(fake.sent.dropLast(1).all { it.link==KakaoReport.NO_LINK })
        val s=settings()
        assertEquals(1L,s.getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT));assertEquals(fake.sent.size.toLong(),s.getLong(KakaoReport.LAST_TEXTS))
        val k=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.7",1052,0,null,5,6,"READY","","",12,v35=PhoneStatus.v35(s))).getJSONObject("kakao")
        assertEquals(fake.sent.size.toLong(),k.getLong("texts"));assertEquals(0L,k.getLong("plainRetried"))
        assertEquals("text",k.getString("style"));assertEquals(0L,k.getLong("cards"))
    }

    // ---- r18 (user 11/13): the to-dos as list bubbles with a tag's thumbnail ----
    private fun tg(what:String,tag:String?,check:Boolean=false,since:String="어제 요청",who:String="△△철강")=
        t(what,who,since).put("check",check).apply { if(tag!=null) put("tag",tag) }
    private fun tagged(n:Int,todayChecks:Int=n)=report((1..3).map { y("업체$it 담당자","자재 입고 일정 확인") },
        (1..n).map { tg("일 $it",KakaoReport.TAGS[(n-it)%5]) },listOf("타설 일정 조정"),listOf("13층/30층 중 어느 쪽인지" to "△△철강"))
        .apply { getJSONObject("head").put("todayChecks",todayChecks) }
    /** Records the list bubbles and texts; [failCard] decides a bubble's answer (null = it goes), [uploadFails] the thumbnails. */
    private class ListSender(var failCard:(KakaoReport.Message.ListCard,Int)->String?={ _,_ -> null },var uploadFails:Boolean=false):KakaoReport.Sender {
        val sent=mutableListOf<KakaoReport.Message>();val tried=mutableListOf<KakaoReport.Message>();var uploads=0
        override suspend fun uploadImage(png:ByteArray):String? { uploads++;return if(uploadFails) throw KakaoReport.Failure("UPLOAD_API_Unknown") else "https://k.kakaocdn.net/img/t$uploads.png" }
        override suspend fun send(message:KakaoReport.Message,imageUrl:String?):String? {
            tried+=message
            if(message is KakaoReport.Message.ListCard) { val e=failCard(message,tried.count { it is KakaoReport.Message.ListCard });if(e!=null) return e }
            sent+=message;return null
        }
    }
    private fun listOn(sender:ListSender):ListSender { SecureSettings(app).update { it.remove(KakaoReport.PICTURE) };KakaoReport.senderFactory={ sender }
        KakaoReport.tagPng={ _,tag -> tag.toByteArray() };return sender }
    private fun cards(l:List<KakaoReport.Message>)=l.filterIsInstance<KakaoReport.Message.ListCard>()

    @Test fun theToDosAreSortedByTagAndAMissingTagIsConfirmOrRequest() {
        val r=KakaoReport.read(report(today=listOf(tg("선택 일","optional"),tg("요청 일",null),tg("확인 일",null,check=true),tg("옛 긴급","urgent"),
            tg("중요 일","important"),tg("정기 일","regular"),tg("또 중요","important"))),false)
        assertEquals(listOf("중요 일","또 중요","확인 일","요청 일","옛 긴급","정기 일","선택 일"),r.items.map { it.title })
        assertEquals(listOf("important","important","confirm","request","request","regular","optional"),r.items.map { it.tag })
        assertEquals("요청일, then who","어제 요청 · △△철강",r.items[0].desc)
        assertEquals(listOf("important","confirm","request","regular","optional"),KakaoReport.TAGS)
        // Ten to-dos are read now (eight before), and the words are masked like every bubble.
        assertEquals(10,KakaoReport.read(tagged(12),false).items.size)
        val masked=KakaoReport.read(report(today=listOf(tg("010-1234-5678로 회신","request",who="○○전기 02-123-4567"),tg("서류","request"))),false).items
        assertFalse(masked.joinToString { it.title+it.desc }.let { phone.containsMatchIn(it) })
    }
    @Test fun theSplitTableIsTheDesign() {
        val custom=mapOf(0 to listOf(),1 to listOf(),2 to listOf(2),3 to listOf(3),4 to listOf(2,2),5 to listOf(5),6 to listOf(3,3),
            7 to listOf(5,2),8 to listOf(5,3),9 to listOf(5,3),10 to listOf(5,5))
        val plain=mapOf(0 to listOf(),1 to listOf(),2 to listOf(2),3 to listOf(3),4 to listOf(2,2),5 to listOf(3,2),6 to listOf(3,3),
            7 to listOf(3,3),8 to listOf(3,3),9 to listOf(3,3),10 to listOf(3,3))
        custom.forEach { (n,want) -> assertEquals("custom $n",want,KakaoReport.split(n,true)) }
        plain.forEach { (n,want) -> assertEquals("plain $n",want,KakaoReport.split(n,false)) }
    }
    @Test fun theHeadsSayTheDayTheCountAndWhatIsLeftOut() {
        val plain=KakaoReport.compose(tagged(8),picture=false).list
        assertEquals(2,plain.size);assertEquals(listOf(3,3),plain.map { it.items.size })
        assertEquals("11/7(토) 챙길 일 8개",plain[0].header);assertEquals("11/7(토) 챙길 일 이어서 · 외 2개",plain[1].header)
        assertTrue(plain.all { it.templateId==0L && it.webUrl==null && it.link==KakaoReport.TASKS_LINK })
        assertEquals("important first",listOf("important","important","confirm"),plain[0].items.map { it.tag })
        val nine=KakaoReport.compose(tagged(9),picture=false,templateId=77L,webBase="https://example.github.io/ieum-app/report/").list
        assertEquals(listOf(5,3),nine.map { it.items.size });assertEquals(listOf(77L,0L),nine.map { it.templateId })
        assertEquals("11/7(토) 챙길 일 이어서 · 외 1개",nine[1].header)
        val ten=KakaoReport.compose(tagged(10),picture=false,templateId=77L,webBase="https://example.github.io/ieum-app/report/").list
        assertEquals("11/7(토) 챙길 일 이어서",ten[1].header);assertEquals(listOf(77L,77L),ten.map { it.templateId })
        // The head counts head.todayChecks (the phone's report counts every open to-do).
        assertEquals("11/7(토) 챙길 일 30개",KakaoReport.compose(tagged(6,todayChecks=30),picture=false).list[0].header)
        assertEquals("11/7(토) 챙길 일 이어서 · 외 24개",KakaoReport.compose(tagged(6,todayChecks=30),picture=false).list[1].header)
        val two=KakaoReport.compose(tagged(2),picture=false).list
        assertEquals(1,two.size);assertEquals("11/7(토) 챙길 일 2개",two[0].header)
        assertTrue("one to-do: no list",KakaoReport.compose(one,picture=false).list.isEmpty())
        assertTrue("none: no list",KakaoReport.compose(report(calls=0),picture=false).list.isEmpty())
    }
    @Test fun twoOrMoreToDosGoAsTheListAndTheHeartbeatSaysSo() = runBlocking {
        val sender=listOn(ListSender())
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        val c=cards(sender.sent)
        assertEquals("two bubbles and nothing else",2,sender.sent.size);assertEquals(2,c.size)
        assertTrue("every item has its thumbnail",c.flatMap { it.items }.all { it.imageUrl?.startsWith("https://")==true })
        val s=settings()
        assertEquals(day.toString(),s.getString(PipelineSettingsKeys.REPORT_DAY));assertEquals(1L,s.getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT))
        val k=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.7",1054,0,null,5,6,"READY","","",12,v35=PhoneStatus.v35(s))).getJSONObject("kakao")
        assertEquals("list",k.getString("style"));assertEquals(2L,k.getLong("cards"));assertEquals(0L,k.getLong("listFallback"));assertFalse(k.getBoolean("web"))
        val tags=k.getJSONObject("tags")
        assertEquals(KakaoReport.TAGS,tags.keys().asSequence().toList())
        assertEquals(8,KakaoReport.TAGS.sumOf { tags.getInt(it) });assertEquals(2,tags.getInt("important"))
        assertFalse("numbers and codes only",Regex("[가-힣]").containsMatchIn(k.toString()))
        // The same day never goes twice.
        assertEquals(KakaoReport.ALREADY_SENT,KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE));assertEquals(2,sender.sent.size)
    }
    @Test fun oneToDoGoesAsTheTexts() = runBlocking {
        val sender=listOn(ListSender())
        assertNull(KakaoReport.send(app,one,KakaoReport.SOURCE_ROUTINE))
        assertTrue(sender.sent.isNotEmpty() && sender.sent.all { it is KakaoReport.Message.Text })
        assertEquals("text",settings().getString(KakaoReport.LAST_STYLE));assertEquals(0,sender.uploads)
    }
    @Test fun aListThatDoesNotGoIsSentAsTheTextsInstead() = runBlocking {
        val sender=listOn(ListSender(failCard={ _,_ -> "SEND_API_IllegalParams" }))
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertTrue("no bubble went",cards(sender.sent).isEmpty())
        assertEquals("the first bubble tried once (no web button to drop)",1,cards(sender.tried).size)
        val texts=sender.sent.filterIsInstance<KakaoReport.Message.Text>()
        assertEquals(KakaoReport.compose(tagged(8),picture=false).chat.map { it.text },texts.map { it.text })
        val s=settings()
        assertEquals("text",s.getString(KakaoReport.LAST_STYLE));assertEquals(1L,s.getLong(KakaoReport.LIST_FALLBACK))
        assertEquals(1L,s.getLong(PipelineSettingsKeys.KAKAO_SENT_COUNT))
        // The phone's 06:00 report: its one text in the list's place.
        val open=(1..4).map { OpenItem("i$it","서류 $it 보내기","","open",1,"",null,"업체$it",at530-it*86_400_000L) }
        KakaoReport.clock={ at530+86_400_000L };sender.sent.clear()
        assertNull(KakaoReport.send(app,ReportFetch.phoneReport(day.plusDays(1),open),KakaoReport.SOURCE_PHONE))
        assertEquals(1,sender.sent.size);assertTrue((sender.sent[0] as KakaoReport.Message.Text).text.contains("새벽 정리 없이 폰에서 만든 보고"))
        assertEquals(2L,settings().getLong(KakaoReport.LIST_FALLBACK))
    }
    @Test fun whenTheSecondBubbleFailsOnlyItIsTriedOnceMore() = runBlocking {
        val sender=listOn(ListSender(failCard={ _,n -> if(n==2) "SEND_API_Unknown" else null }))
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertEquals(2,cards(sender.sent).size);assertEquals(3,cards(sender.tried).size)
        assertTrue("no text on top of the list",sender.sent.none { it is KakaoReport.Message.Text })
        // It fails twice: an error, and still no text (the first bubble went).
        KakaoReport.clock={ at530+86_400_000L }
        val twice=listOn(ListSender(failCard={ _,n -> if(n>=2) "SEND_API_Unknown" else null }))
        val next=tagged(8).put("day",day.plusDays(1).toString())
        assertEquals("SEND_API_Unknown",KakaoReport.send(app,next,KakaoReport.SOURCE_ROUTINE))
        assertEquals(1,cards(twice.sent).size);assertEquals(3,cards(twice.tried).size);assertTrue(twice.sent.none { it is KakaoReport.Message.Text })
        val s=settings()
        assertEquals("some went: the day is not sent again",day.plusDays(1).toString(),s.getString(PipelineSettingsKeys.REPORT_DAY))
        assertEquals(1L,s.getLong(PipelineSettingsKeys.KAKAO_FAILED_COUNT));assertEquals(1L,s.getLong(KakaoReport.LAST_CARDS))
        assertEquals(0L,s.optLong(KakaoReport.LIST_FALLBACK,0))
    }
    @Test fun aDeadTokenStopsTheListAndTakesTheLinkDown() = runBlocking {
        val sender=listOn(ListSender(failCard={ _,_ -> "TOKEN_INVALID" }))
        assertEquals("TOKEN_INVALID",KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertTrue("nothing else is tried",sender.sent.isEmpty());assertEquals(1,sender.tried.size)
        val s=settings()
        assertFalse(s.getBoolean(PipelineSettingsKeys.KAKAO_LINKED));assertFalse(s.has(PipelineSettingsKeys.REPORT_DAY))
        assertEquals(0L,s.optLong(KakaoReport.LIST_FALLBACK,0))
    }
    @Test fun aThumbnailThatDoesNotGoUpLeavesThePictureOut() = runBlocking {
        val sender=listOn(ListSender(uploadFails=true))
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        val c=cards(sender.sent)
        assertEquals(2,c.size);assertTrue(c.flatMap { it.items }.all { it.imageUrl==null })
        assertFalse("nothing kept",settings().optString(KakaoReport.TAG_IMAGES).contains("https"))
        // The tag's picture that cannot be read: also no picture, the report still goes.
        KakaoReport.clock={ at530+86_400_000L }
        val unread=listOn(ListSender());KakaoReport.tagPng={ _,_ -> throw IllegalStateException("gone") }
        assertNull(KakaoReport.send(app,tagged(8).put("day",day.plusDays(1).toString()),KakaoReport.SOURCE_ROUTINE))
        assertEquals(2,cards(unread.sent).size);assertEquals(0,unread.uploads)
    }
    @Test fun thumbnailsAreKeptAndGoUpAgainAfterTenDays() = runBlocking {
        val sender=listOn(ListSender())
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertEquals("the five tags of the day, once each",5,sender.uploads)
        val kept=JSONObject(settings().getString(KakaoReport.TAG_IMAGES))
        assertEquals(KakaoReport.TAGS.toSet(),kept.keys().asSequence().toSet());assertEquals(at530,kept.getJSONObject("confirm").getLong("atMs"))
        // Nine days on: the kept ones.
        KakaoReport.clock={ at530+9*86_400_000L }
        assertNull(KakaoReport.send(app,tagged(8).put("day",day.plusDays(9).toString()),KakaoReport.SOURCE_ROUTINE))
        assertEquals(5,sender.uploads)
        assertEquals(kept.getJSONObject("request").getString("url"),cards(sender.sent).last().items.first { it.tag=="request" }.imageUrl)
        // Ten days on: up again.
        KakaoReport.clock={ at530+10*86_400_000L }
        assertNull(KakaoReport.send(app,tagged(8).put("day",day.plusDays(10).toString()),KakaoReport.SOURCE_ROUTINE))
        assertEquals(10,sender.uploads)
        // Only the tags a day needs.
        KakaoReport.clock={ at530+30*86_400_000L }
        assertNull(KakaoReport.send(app,report(today=listOf(tg("가","request"),tg("나","request"))).put("day",day.plusDays(30).toString()),KakaoReport.SOURCE_ROUTINE))
        assertEquals(11,sender.uploads)
        assertEquals(10L,KakaoReport.TAG_IMAGE_DAYS)
    }
    @Test fun theTagPicturesAreThereAndSquare() {
        KakaoReport.TAGS.forEach { tag ->
            val png=KakaoReport.tagPng(app,tag)!!
            assertEquals(tag,400 to 400,KakaoSdkSender.pngSize(png))
        }
        assertNull(KakaoReport.tagPng(app,"urgent"))
    }
    @Test fun theWebViewCarriesTheMaskedReportInTheFragment() = runBlocking {
        val base="https://example.github.io/ieum-app/report/"
        val r=withV8(tagged(8),2,listOf(y("○○전기 010-1234-5678","도면 회신 받음")))
        val url=KakaoReport.webUrl(base,r,false)!!
        assertTrue(url.startsWith(base+"#d="));assertTrue(Regex("#d=[A-Za-z0-9_-]+$").containsMatchIn(url))
        val back=JSONObject(KakaoReport.unpackWeb(url.substringAfter("#d=")))
        assertEquals(KakaoReport.webData(r,false).toString(),back.toString())
        assertEquals("2026-11-07",back.getString("day"));assertEquals("11/7(토)",back.getString("label"))
        assertEquals(8,back.getJSONArray("today").length());assertEquals("important",back.getJSONArray("today").getJSONObject(0).getString("tag"))
        assertEquals(1,back.getJSONArray("late").length());assertEquals(2,back.getJSONObject("head").getInt("pendingCalls"))
        assertFalse("masked",phone.containsMatchIn(back.toString()));assertFalse(back.has("cut"))
        // What the page's own decoder reads (tools/test_report_page.py makes this with Python's zlib).
        assertEquals("{\"v\":1,\"day\":\"2026-11-07\",\"today\":[{\"what\":\"견적서 보내기\",\"tag\":\"important\"}]}",
            KakaoReport.unpackWeb("eNqrVipTsjLUUUpJrFSyUjIyMDLTNTTUNTBX0lEqyQcLRlcrlWcklgBlX21a82ZB45uWOQqvN2953bTl1Y4NIGWJ6UC5zNyC_KKSxLwSpdrYWgDQ3iB2"))
        assertNull("no base, no web view",KakaoReport.webUrl("",r,false))
        // A long report: the last blocks give up lines, the URL stays short enough and the page says the rest is in the app.
        // Syllables that do not repeat, so the deflate cannot make them short (5,400 characters before the cut).
        fun line(i:Int,j:Int)=(0 until 60).joinToString("") { k -> String(Character.toChars(0xAC00+((i*7919+j*1543+k*104729)%11172))) }
        val long=report((1..8).map { i -> y("업체$i 담당자",line(i,1)) },(1..10).map { i -> tg(line(i,2),"request",who="업체$i") },
            (1..3).map { line(it,3) },(1..3).map { line(it,4) to "업체$it" }).let { withV8(it,0,(1..5).map { i -> y("업체$i",line(i,5)) }) }
        val cut=KakaoReport.webUrl(base,long,false)!!
        assertTrue(cut.length<=KakaoReport.WEB_MAX)
        val shown=JSONObject(KakaoReport.unpackWeb(cut.substringAfter("#d=")))
        assertTrue(shown.getBoolean("cut"));assertTrue("late gives way first",shown.getJSONArray("late").length()<5)
        // On the bubbles: [웹으로 보기] with that address.
        KakaoReport.webBase={ base }
        val sender=listOn(ListSender())
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertTrue(cards(sender.sent).all { it.webUrl?.startsWith(base+"#d=")==true })
        assertTrue(settings().getBoolean(KakaoReport.LAST_WEB))
    }
    @Test fun aBubbleKakaoDoesNotTakeWithTheWebButtonGoesOnceMoreWithoutIt() = runBlocking {
        KakaoReport.webBase={ "https://example.github.io/ieum-app/report/" }
        val sender=listOn(ListSender(failCard={ c,_ -> if(c.webUrl!=null) "SEND_API_IllegalParams" else null }))
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertEquals(2,cards(sender.sent).size);assertTrue(cards(sender.sent).all { it.webUrl==null })
        assertEquals(4,cards(sender.tried).size);assertTrue(sender.sent.none { it is KakaoReport.Message.Text })
        assertFalse(settings().getBoolean(KakaoReport.LAST_WEB))
    }
    @Test fun theOwnTemplateTakesFiveWithItsArguments() = runBlocking {
        KakaoReport.listTemplateId={ 12345L }
        // Without the web view the own template is not used (its two buttons are fixed in the console).
        assertTrue(KakaoReport.compose(tagged(10),picture=false).list.all { it.templateId==0L })
        KakaoReport.webBase={ "https://example.github.io/ieum-app/report/" }
        val sender=listOn(ListSender())
        assertNull(KakaoReport.send(app,tagged(9),KakaoReport.SOURCE_ROUTINE))
        val c=cards(sender.sent)
        assertEquals(listOf(5,3),c.map { it.items.size });assertEquals(listOf(12345L,0L),c.map { it.templateId })
        val args=KakaoReport.customArgs(c[0])
        assertEquals(listOf("H")+(1..5).flatMap { listOf("T$it","D$it","I$it") }+"WEB",args.keys.toList())
        assertEquals(c[0].header,args["H"]);assertEquals(c[0].items[4].title,args["T5"]);assertEquals(c[0].items[0].desc,args["D1"])
        assertTrue(args["I1"]!!.startsWith("https://"))
        assertTrue(args["WEB"]!!.startsWith("ieum-app/report/#d="))
        assertEquals("a/b/#d=x",KakaoReport.webPath("https://h.example/a/b/#d=x"));assertEquals("",KakaoReport.webPath("https://h.example"))
        // Fewer than five in a bubble: the arguments are empty, never "null".
        assertEquals("",KakaoReport.customArgs(c[1])["T5"]);assertEquals("",KakaoReport.customArgs(c[1].copy(webUrl=null))["WEB"])
    }
    @Test fun thePhoneReportTagsAndGoesAsTheListToo() = runBlocking {
        val open=listOf(OpenItem("a","오늘 마감 서류","2026-11-07","open",1,"",null,"업체A",at530-5*86_400_000L),
            OpenItem("b","확인할 일","","needs_confirmation",1,"","김과장",null,at530-3*86_400_000L),
            OpenItem("c","보통 일","","open",1,"",null,"업체C",at530-minute),
            OpenItem("d","다음 주 일","2026-11-20","open",1,"",null,"업체D",at530-2*minute))
        val r=ReportFetch.phoneReport(day,open)
        val tags=(0 until r.getJSONArray("today").length()).associate { r.getJSONArray("today").getJSONObject(it).let { t -> t.getString("what") to t.getString("tag") } }
        assertEquals(mapOf("오늘 마감 서류" to "important","확인할 일" to "confirm","보통 일" to "request","다음 주 일" to "request"),tags)
        val many=ReportFetch.phoneReport(day,(1..14).map { OpenItem("i$it","서류 $it","","open",1,"",null,"업체$it",at530-it*minute) })
        assertEquals(10,many.getJSONArray("today").length());assertEquals(14,many.getJSONObject("head").getInt("todayChecks"))
        val sender=listOn(ListSender())
        assertNull(KakaoReport.send(app,r,KakaoReport.SOURCE_PHONE))
        assertEquals(listOf(2,2),cards(sender.sent).map { it.items.size });assertEquals("important",cards(sender.sent)[0].items[0].tag)
        assertEquals("phone",settings().getString(PipelineSettingsKeys.REPORT_SOURCE))
    }
    @Test fun thePictureSettingKeepsThePictureForTheTidysReport() = runBlocking {
        // PICTURE=true (setup): the picture and its text, as before; no list.
        assertNull(KakaoReport.send(app,tagged(8),KakaoReport.SOURCE_ROUTINE))
        assertTrue(fake.sent[0] is KakaoReport.Message.Feed);assertTrue(fake.sent.none { it is KakaoReport.Message.ListCard })
        assertEquals("picture",settings().getString(KakaoReport.LAST_STYLE))
    }
}
