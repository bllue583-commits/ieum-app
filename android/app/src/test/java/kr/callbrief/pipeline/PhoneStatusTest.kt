package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneStatusTest {
    private fun input(inbox:String="",update:String="",oldest:Long?=null) = PhoneStatus.Input(1_000L,"v2.1",1034,2,oldest,5,6,"READY",inbox,update,12)

    @Test fun jsonHasWatchdogFieldsAndOmitsEmptyErrors() {
        val o=PhoneStatus.build(input(oldest=500L))
        assertEquals(1_000L,o.getLong("atMs"));assertEquals(500L,o.getLong("oldestQueuedMs"));assertEquals(2,o.getInt("queuedCount"))
        assertEquals("v2.1",o.getString("appVersion"));assertEquals(12,o.getInt("storageUsedPercent"))
        assertFalse(o.has("lastUpdateError"));assertFalse(o.has("lastInboxError"))
    }
    @Test fun nothingWaitingGivesNullAge() { assertTrue(PhoneStatus.build(input()).isNull("oldestQueuedMs")) }
    @Test fun errorsAreCodesOnly() {
        val o=PhoneStatus.build(input(inbox="INBOX_HTTP_401",update="STATUS_FAILURE: 김 부장님 010-1234-5678 통화 내용 ".repeat(5)))
        assertEquals("INBOX_HTTP_401",o.getString("lastInboxError"))
        val u=o.getString("lastUpdateError")
        assertTrue(u.length<=60);assertTrue(u.all { it.code<128 })
        assertFalse(u.contains("010"+" "))
    }
    @Test fun phoneAndOutputHaveNoFreeText() {
        val text=PhoneStatus.build(input(update="한글 이름")).toString()
        assertFalse(Regex("[가-힣]").containsMatchIn(text))
    }

    @Test fun firstPassProgressIsCountsOnly() {
        val o=PhoneStatus.build(input().copy(firstPass=PhoneStatus.FirstPassStatus(false,120,3600,4,2)))
        val f=o.getJSONObject("firstPass")
        assertEquals(120,f.getInt("toTranscribe"));assertEquals(3600L,f.getLong("audioMinutesLeft"));assertEquals(4,f.getInt("waitingForSummary"))
        assertTrue(!PhoneStatus.build(input()).has("firstPass"))
    }
}
