package kr.callbrief.pipeline

import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** The stop counter that halves a piece after three stops in a row, and the saved pieces of a long call (B4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallTranscriptionStallTest:ProtectedTextHostTestBase() {
    private val key=CallTranscriptionStore.Key("rec1",1,"a".repeat(64))
    @Test fun stopsAreCountedPerChunkAndClearedWithTheParts() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            assertEquals(0,s.stalls(key,2))
            repeat(3){ s.noteStall(key,2) }
            s.noteStall(key,3)
            assertEquals(3,s.stalls(key,2));assertEquals(1,s.stalls(key,3))
            assertTrue(s.stalls(key,2)>=CallTranscriptionRunner.SPLIT_AFTER_STOPS)
            s.clearParts(key)
            assertEquals(0,s.stalls(key,2));assertEquals(0,s.stalls(key,3))
        }
    }
    @Test fun halvesAreSavedUnderTheirOwnIdsNextToTheWholeChunks() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            s.savePart(key,0,listOf(CallTranscript.Segment(0,1000,"가")))
            s.savePart(key,-3,listOf(CallTranscript.Segment(120000,121000,"나")))   // first half of chunk 1
            s.savePart(key,-4,listOf(CallTranscript.Segment(180000,181000,"다")))   // second half of chunk 1
            val parts=s.parts(key)
            assertEquals(setOf(0,-3,-4),parts.keys)
            assertEquals("나",parts.getValue(-3).single().text)
            s.clearParts(key);assertTrue(s.parts(key).isEmpty())
        }
    }
    @Test fun piecesMadeWithAnotherPieceLengthAreNotReused() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            s.savePart(key,0,listOf(CallTranscript.Segment(0,1000,"옛")),300_000L)      // saved by the 5-minute version
            s.savePart(key,-1,listOf(CallTranscript.Segment(0,500,"옛반")),300_000L)
            s.noteStall(key,0)
            assertTrue(s.parts(key).isEmpty())                                           // current length is 2 minutes
            assertEquals(0,s.stalls(key,0))                                              // counters of the old numbering go too
            s.savePart(key,0,listOf(CallTranscript.Segment(0,1000,"새")))
            assertEquals("새",s.parts(key).getValue(0).single().text)
            assertEquals(setOf(0),s.parts(key).keys)
        }
    }
    @Test fun stallsOfAllPiecesAreTotalledAndLimitTheRefund() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            assertEquals(0,s.totalStalls(key))
            repeat(4){ s.noteStall(key,0) };repeat(2){ s.noteStall(key,1) }
            assertEquals(6,s.totalStalls(key))
        }
        assertTrue(CallTranscriptionRunner.refundStop(5));assertFalse(CallTranscriptionRunner.refundStop(6))
        assertTrue(CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS<540+1)
        assertTrue(CallTranscriptionRunner.requeueNow("MORE",0));assertTrue(CallTranscriptionRunner.requeueNow("STOPPED",1))
        assertFalse(CallTranscriptionRunner.requeueNow("STOPPED",0));assertFalse(CallTranscriptionRunner.requeueNow("IDLE",3))
    }
}
