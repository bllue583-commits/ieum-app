package kr.callbrief.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class RecordingPolicyTest {
    @Test fun seoulDayIsCalendarDayNotRolling24Hours() {
        assertEquals(LocalDate.parse("2026-10-09"), SeoulTime.yesterday(Instant.parse("2026-10-09T15:01:00Z")))
        assertEquals(Instant.parse("2026-10-09T21:00:00Z"), SeoulTime.nextReport(Instant.parse("2026-10-09T15:01:00Z")))
        assertEquals(Instant.parse("2026-10-10T21:00:00Z"), SeoulTime.nextReport(Instant.parse("2026-10-09T21:00:00Z")))
    }
    @Test fun samsungAndIsoNames() {
        assertEquals(Instant.parse("2026-10-09T02:22:33Z"), RecordingNames.startedAt("통화 녹음 상대방_261009_112233.m4a"))
        assertEquals(Instant.parse("2026-10-08T15:00:00Z"), RecordingNames.startedAt("call_20261009_000000.wav"))
        assertEquals(Instant.parse("2026-10-09T14:59:59Z"), RecordingNames.startedAt("call_2026-10-09_23-59-59.m4a"))
    }
    @Test fun missingAndInvalidTimesStayUnknown() {
        assertNull(RecordingNames.startedAt("김민수_01012345678.m4a"))
        assertNull(RecordingNames.startedAt("call_20260230_112233.wav"))
        assertNull(RecordingNames.startedAt("call_20261009_250000.m4a"))
    }
    @Test fun scopesDoNotUploadOlderOrStillRecordingFiles() {
        val now = Instant.parse("2026-10-10T02:00:00Z")
        val floor = LocalDate.parse("2026-10-09")
        assertFalse(SeoulTime.eligible(Instant.parse("2026-10-08T14:59:59Z"), floor, now))
        assertTrue(SeoulTime.eligible(Instant.parse("2026-10-08T15:00:00Z"), floor, now))
        assertFalse(SeoulTime.eligible(now.minusSeconds(10), floor, now))
    }
    @Test fun safetyBoundaries() {
        assertFailsWith<IllegalArgumentException> { ServiceEndpoint.normalize("http://example.com") }
        assertFailsWith<IllegalArgumentException> { ServiceEndpoint.normalize("https://token@example.com") }
        assertFailsWith<IllegalArgumentException> { ServiceEndpoint.normalize("https://example.com?token=x") }
        assertEquals("https://example.com/api", ServiceEndpoint.normalize("https://example.com/api/"))
        assertNull(AudioPolicy.mime("unsupported.amr"))
        assertFalse(AudioPolicy.safeSize(25_000_001))
        assertTrue(AudioPolicy.safeSize(25_000_000))
        assertEquals(TaskStatus.NEEDS_CONFIRMATION, TaskStatus.fromWire("maybe"))
    }
    @Test fun oauthBrowserStartIsBoundToConfiguredHttpsOriginAndExactRoute() {
        val state="fabricated-state-for-tests-000000000000000"
        val url="https://example.com/v1/kakao/authorize?state=$state"
        assertEquals(url,ServiceEndpoint.kakaoAuthorization("https://example.com",url))
        assertEquals("https://example.com/api/v1/kakao/authorize?state=$state",ServiceEndpoint.kakaoAuthorization("https://example.com/api","https://example.com/api/v1/kakao/authorize?state=$state"))
        for(unsafe in listOf("http://example.com/v1/kakao/authorize?state=$state","https://other.example/v1/kakao/authorize?state=$state","https://example.com/v1/kakao/authorize?state=$state&token=fixture","https://example.com/v1/kakao/authorize?state=$state#x","https://example.com/v1/kakao/authorize-extra?state=$state","https://kauth.kakao.com/oauth/authorize?state=$state"))
            assertFailsWith<IllegalArgumentException> { ServiceEndpoint.kakaoAuthorization("https://example.com",unsafe) }
    }
}
