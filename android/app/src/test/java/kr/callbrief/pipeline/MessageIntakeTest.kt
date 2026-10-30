package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text messages and mail are read narrowly: business numbers only, no mass mail, only the new part of a reply. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MessageIntakeTest {
    @Test fun phoneNumbersNormaliseToDigits() {
        assertEquals("01012345678",MessageIntake.phoneKey("010-1234-5678"))
        assertEquals("01012345678",MessageIntake.phoneKey("+82 10-1234-5678"))
        assertNull(MessageIntake.phoneKey("1588"))
        assertNull(MessageIntake.phoneKey("15881234"))
        assertNull(MessageIntake.phoneKey(null))
    }

    @Test fun textsAreGroupedPerNumberAndMachineTextsDropped() {
        fun sms(id:Long,at:Long,phone:String,body:String,sent:Boolean=false)=MessageIntake.Sms(id,at,sent,phone,body)
        val list=listOf(sms(1,3000,"01011112222","견적서 오늘 보낼게요"),sms(2,1000,"01011112222","단가표 부탁드려요",sent=true),
            sms(3,2000,"01099998888","오늘 저녁 뭐 먹어?"),sms(4,4000,"01011112222","  "),
            sms(5,5000,"01055556666","(광고) 가을 세일 무료수신거부 080"),sms(6,6000,"01077778888","[Web발신] 인증번호 [123456]을 입력해 주세요"),
            sms(7,7000,"01033334444","신한카드 승인 52,000원 홍길동"))
        val threads=MessageIntake.threads(list)
        // Personal texts still go to the analysis (it decides work or personal); machine texts never do.
        assertEquals(setOf("01011112222","01099998888"),threads.map { it.first }.toSet())
        val work=threads.first { it.first=="01011112222" }.second
        assertEquals(listOf(2L,1L),work.map { it.id })
        val text=MessageIntake.threadText(work)
        assertTrue(text,text.lines().first().contains("보낸 문자") && text.lines().first().endsWith("단가표 부탁드려요"))
        assertTrue(text.lines()[1].contains("받은 문자"))
    }

    @Test fun personalVerdictSurvivesTheRoundTrip() {
        val a=ClaudeCallAnalysis("개인 내용",emptyList(),null,null,emptyList(),emptyList(),business=false)
        assertFalse(ClaudeCallAnalysis.parse(a.toJson()).business)
        assertTrue(ClaudeCallAnalysis.parse(org.json.JSONObject().put("summary","요약")).business) // older results: work
    }

    @Test fun replyKeepsOnlyTheNewPart() {
        val body="네, 금요일 오전 반입으로 하겠습니다.\n감사합니다.\n\n-----Original Message-----\nFrom: 김민수\n지난 메일 내용"
        assertEquals("네, 금요일 오전 반입으로 하겠습니다.\n감사합니다.",MessageIntake.newPart(body))
        assertEquals("새 내용",MessageIntake.newPart("새 내용\n> 인용된 줄"))
        assertEquals("새 내용",MessageIntake.newPart("새 내용\n보낸 사람: 이영호"))
    }

    @Test fun massMailIsSkipped() {
        assertTrue(MessageIntake.isBulk(true,null,"news@shop.com"))
        assertTrue(MessageIntake.isBulk(false,"bulk","a@b.com"))
        assertTrue(MessageIntake.isBulk(false,null,"no-reply@service.com"))
        assertFalse(MessageIntake.isBulk(false,null,"kim@steel.co.kr"))
    }

    @Test fun recordKindFollowsTheRecordingId() {
        assertEquals("문자",MessageKind.of("sms:01011112222:5"))
        assertEquals("메일",MessageKind.of("mail:1:42"))
        assertEquals("통화",MessageKind.of("abc-recording"))
    }
}
