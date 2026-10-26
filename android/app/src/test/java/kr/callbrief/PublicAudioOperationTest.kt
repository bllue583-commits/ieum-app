package kr.callbrief

import org.junit.Assert.*
import org.junit.Test

class PublicAudioOperationTest {
    @Test fun stopCancelsOnlyTheAttachedIntegrationOperationOnce() {
        var inactiveStandardCalls=0;var integrationCalls=0
        val inactive=PublicAudioOperation().apply{attach{inactiveStandardCalls++}}
        val active=PublicAudioOperation().apply{attach{integrationCalls++}}
        active.cancel();active.cancel()
        assertEquals(1,integrationCalls);assertEquals(0,inactiveStandardCalls);assertFalse(inactive.cancelled)
    }
    @Test fun stopBeforePilotCreationCancelsLateAttachmentBeforeWork() {
        val operation=PublicAudioOperation();operation.cancel();var stopped=false;var work=0
        operation.attach{stopped=true}
        try{operation.requireRunning();work++}catch(_:IllegalStateException){}
        assertTrue(stopped);assertEquals(0,work)
    }
    @Test fun standardWorkUsesTheSameCancellationBoundary() {
        val operation=PublicAudioOperation();var calls=0;operation.attach{calls++};operation.requireRunning();operation.cancel()
        assertTrue(operation.cancelled);assertEquals(1,calls)
        try{operation.requireRunning();fail("stopped work cannot resume")}catch(e:IllegalStateException){assertEquals("ANDROID_STT_STOPPED",e.message)}
    }
}
