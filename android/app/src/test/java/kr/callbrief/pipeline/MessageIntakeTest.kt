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
        assertNull(MessageIntake.phoneKey(null))
    }

    @Test fun onlyKnownNumbersAreTakenAndGroupedPerNumber() {
        fun sms(id:Long,at:Long,phone:String,body:String,sent:Boolean=false)=MessageIntake.Sms(id,at,sent,phone,body)
        val list=listOf(sms(1,3000,"01011112222","견적서 오늘 보낼게요"),sms(2,1000,"01011112222","단가표 부탁드려요",sent=true),
            sms(3,2000,"01099998888","개인 문자"),sms(4,4000,"01011112222","  "))
        val threads=MessageIntake.threads(list,setOf("01011112222"))
        assertEquals(1,threads.size)
        assertEquals(listOf(2L,1L),threads.single().second.map { it.id })
        val text=MessageIntake.threadText(threads.single().second)
        assertTrue(text,text.lines().first().contains("보낸 문자") && text.lines().first().endsWith("단가표 부탁드려요"))
        assertTrue(text.lines()[1].contains("받은 문자"))
        assertFalse(text.contains("개인 문자"))
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
