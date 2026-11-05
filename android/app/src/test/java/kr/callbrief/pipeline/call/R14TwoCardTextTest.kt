package kr.callbrief.pipeline.call

import kr.callbrief.pipeline.ContactCard
import kr.callbrief.pipeline.call.CallCardView.Content
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** v3.4 r14-2 (DESIGN_V4 4.2-3): the card's words for three seconds. The first line is picked in a fixed order (due or past >
 * "확인 필요" > the last call's summary within 14 days > work under way > the thin line), within a rank by the earlier due date
 * and then the latest request; two lines of about 18 letters cut at a space, never an ellipsis; phone numbers out, amounts in.
 * The notice and the strip say the same in the same order. Generated cards only; "now" is a Wednesday noon in KST. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class R14TwoCardTextTest {
    private val kst=ZoneId.of("Asia/Seoul")
    /** Wednesday 2026-11-04, 12:00 KST. */
    private val now=ZonedDateTime.of(2026,11,4,12,0,0,0,kst).toInstant().toEpochMilli()
    private val day=86_400_000L
    private fun midnight(plusDays:Long)=ZonedDateTime.of(2026,11,4,0,0,0,0,kst).plusDays(plusDays).toInstant().toEpochMilli()
    private fun task(id:String,title:String,requested:Long=now-day,due:Long?=null,check:String?=null)=
        ContactCard.Task(id,title,requested,"open",null,tentative=check!=null,dueMs=due,checkReason=check)
    private fun card(tasks:List<ContactCard.Task> =emptyList(),summaryAt:Long?=now-2*day,summary:String="단가는 지난번과 같게 하기로 했어요.",
                     issues:List<ContactCard.IssueLine> =emptyList(),last:Long?=now-3*day,calls30:Int=5)=
        ContactCard("k",listOf(ContactCard.Person("pe_1","김민수","대표",false)),ContactCard.Company("co_1","탄탄이엔씨","철근"),last,"in",
            listOfNotNull(summaryAt?.let { ContactCard.Recent(it,"통화",summary) }),tasks,issues,emptyList(),now,calls30d=calls30)
    private fun of(c:ContactCard)=Content.of(c,now)

    // ---- the eight fixed cases of the first line ----
    @Test fun aPastDueItemComesFirst() {
        val c=of(card(listOf(task("a","도면 받기",check="unsure"),task("b","견적서 보내기",due=midnight(-2)))))
        assertEquals("견적서 보내기, 2일 지났어요",c.first);assertEquals(Content.RANK_DUE,c.rank);assertFalse(c.firstUnsure)
    }
    @Test fun anItemDueWithinSevenDaysBeatsACheckAndTheSummary() {
        val c=of(card(listOf(task("a","도면 받기",check="unsure"),task("b","견적서 보내기",due=midnight(2)))))
        assertEquals("견적서 보내기, 금요일까지",c.first);assertEquals(Content.RANK_DUE,c.rank)
        assertEquals("내일까지",Content.dueWords(midnight(1),now));assertEquals("오늘까지",Content.dueWords(midnight(0),now))
        assertEquals("다음 주 수요일까지",Content.dueWords(midnight(7),now));assertEquals("11월 20일까지",Content.dueWords(midnight(16),now))
    }
    @Test fun anItemToCheckBeatsTheSummaryWithTheBadgeInFront() {
        val c=of(card(listOf(task("a","철근 30톤 반입 확인",check="confusable"),task("b","견적서 보내기",due=midnight(10)))))
        assertEquals("철근 30톤 반입 확인",c.first);assertEquals(Content.RANK_CHECK,c.rank);assertTrue(c.firstUnsure)
        assertTrue(c.noticeLines().first().endsWith(", 확인 필요"))
    }
    @Test fun aRecentSummaryBeatsWorkUnderWay() {
        val c=of(card(listOf(task("a","견적서 보내기",due=midnight(10))),issues=listOf(ContactCard.IssueLine("2층 골조 공사","진행 중"))))
        assertEquals("단가는 지난번과 같게 하기로 했어요",c.first);assertEquals(Content.RANK_SUMMARY,c.rank)
        assertEquals("견적서 보내기",c.task)
    }
    @Test fun aSummaryOlderThanFourteenDaysGivesWayToWorkUnderWay() {
        val c=of(card(listOf(task("a","견적서 보내기")),summaryAt=now-15*day,last=now-15*day))
        assertEquals("견적서 보내기",c.first);assertEquals(Content.RANK_ONGOING,c.rank)
    }
    @Test fun anOngoingIssueWhenThereIsNoItem() {
        val c=of(card(summaryAt=null,issues=listOf(ContactCard.IssueLine("2층 골조 공사","다음 주 화요일 타설 예정이에요"))))
        assertEquals("2층 골조 공사, 다음 주 화요일 타설 예정이에요",c.first);assertEquals(Content.RANK_ONGOING,c.rank);assertNull(c.issue)
    }
    @Test fun nothingElseGivesTheThinLine() {
        val c=of(card(summaryAt=null))
        assertEquals("3일 전 통화, 30일간 5번",c.first);assertEquals(Content.RANK_THIN,c.rank);assertFalse(c.hasBody)
        val thin=Content.of(ContactCard("k",emptyList(),null,now-day,"in",emptyList(),emptyList(),emptyList(),emptyList(),now,ContactCard.Thin("현장",2)),now)
        assertEquals("어제 통화, 30일간 2번",thin.first);assertEquals("현장",thin.name)
    }
    @Test fun withinARankTheEarlierDueThenTheLatestRequest() {
        val c=of(card(listOf(task("a","나중 일",due=midnight(5)),task("b","먼저 일",due=midnight(3)))))
        assertEquals("먼저 일, 토요일까지",c.first);assertEquals("나중 일",c.task)
        val same=of(card(listOf(task("a","옛 요청",requested=now-5*day,due=midnight(3)),task("b","새 요청",requested=now-day,due=midnight(3)))))
        assertEquals("새 요청, 토요일까지",same.first)
        val ongoing=of(card(listOf(task("a","옛 요청",requested=now-5*day),task("b","새 요청",requested=now-day)),summaryAt=null))
        assertEquals("새 요청",ongoing.first);assertEquals("옛 요청",ongoing.task)
    }

    // ---- the words ----
    @Test fun theFirstLineIsTwoLinesCutAtASpaceWithoutAnEllipsis() {
        val long="탄탄이엔씨와 삼층 타설 일정을 금요일로 정하고 철근 반입도 같은 날 오전으로 맞추기로 했어요."
        val c=of(card(summary=long))
        assertTrue(c.first,c.first.length<=Content.FIRST_CHARS)
        assertTrue(c.first,long.startsWith(c.first+" "))
        assertFalse(c.first.contains("…") || c.first.contains("..."))
        assertTrue(c.task==null || c.task!!.length<=Content.ROW_CHARS)
    }
    @Test fun phoneNumbersGoAmountsDatesAndCountsStay() {
        val c=of(card(listOf(task("a","010-2222-3333로 철근 대금 50만원 입금 확인, 3톤",due=midnight(1)))))
        assertFalse(c.first,c.first.contains("2222") || c.first.contains("3333"))
        assertTrue(c.first,c.first.contains("50만원") && c.first.contains("내일까지"))
        assertFalse(c.noticeLines().joinToString().contains("3333"))
    }
    @Test fun theSecondLineSaysWhoTheCompanyAndHowLongAgo() {
        assertEquals("김민수 대표, 탄탄이엔씨, 3일 전",of(card()).who)
    }
    @Test fun theNoticeAndTheStripFollowTheCardsOrder() {
        val c=of(card(listOf(task("a","견적서 보내기",due=midnight(2)),task("b","도면 받기"),task("c","단가표 받기")),issues=listOf(ContactCard.IssueLine("골조","진행"))))
        assertEquals(listOf("견적서 보내기, 금요일까지","탄탄이엔씨, 3일 전","체크할 일: 도면 받기 외 1개","진행 중인 일: 골조, 진행"),c.noticeLines())
        assertEquals("김민수 대표, 견적서 보내기, 금요일까지",c.strip())
        assertTrue(c.strip().length<=c.name.length+2+Content.STRIP_CHARS)
        val summary=of(card())
        assertEquals("단가는 지난번과 같게 하기로 했어요",summary.noticeLines().first())
        assertTrue(summary.strip(),summary.strip().startsWith("김민수 대표, 단가는 지난번과"))
    }
}
