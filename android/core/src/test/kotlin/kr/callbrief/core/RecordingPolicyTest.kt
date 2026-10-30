package kr.callbrief.core

import java.time.Instant
import kotlin.test.*

class RecordingPolicyTest {
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
    @Test fun audioPolicyBoundaries() {
        assertNull(AudioPolicy.mime("unsupported.amr"))
        assertFalse(AudioPolicy.safeSize(25_000_001))
        assertTrue(AudioPolicy.safeSize(25_000_000))
    }
}
