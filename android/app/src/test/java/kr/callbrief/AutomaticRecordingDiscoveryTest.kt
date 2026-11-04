package kr.callbrief

import androidx.work.PeriodicWorkRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Scheduling and fence behaviour of the periodic folder scan; no recording bytes are read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AutomaticRecordingDiscoveryTest:ProtectedTextHostTestBase() {
    private val context get()=RuntimeEnvironment.getApplication()
    private val queued=mutableListOf<Pair<String,PeriodicWorkRequest>>()
    @Before fun hostHooks() { AutomaticRecordingDiscovery.installForHost(work={ n,r->queued+=n to r }) }
    @org.junit.After fun releaseHooks() { AutomaticRecordingDiscovery.installForHost() }

    @Test fun nothingIsScheduledWithoutAFolder() {
        AutomaticRecordingDiscovery.ensure(context)
        assertTrue(queued.isEmpty());assertNull(AutomaticRecordingDiscovery.capture(context))
    }
    @Test fun selectedFolderSchedulesOneFifteenMinuteScanCarryingOnlyTheFence() {
        SecureSettings(context).update { it.put("treeUri","content://generated-fixture/tree/test") }
        AutomaticRecordingDiscovery.ensure(context)
        val request=queued.single().second
        assertEquals(900000L,request.workSpec.intervalDuration)
        val binding=checkNotNull(AutomaticRecordingDiscovery.binding(request.workSpec.input))
        assertEquals(AutomaticRecordingDiscovery.capture(context),binding)
        assertEquals(setOf("selection_fence"),request.workSpec.input.keyValueMap.keys)
    }
    @Test fun changedFolderRevisionOrPrivacyRulesChangeTheFenceAndStopAQueuedRun() {
        val settings=SecureSettings(context)
        settings.update { it.put("treeUri","content://generated-fixture/tree/test") }
        val first=checkNotNull(AutomaticRecordingDiscovery.capture(context))
        settings.update { it.put("automaticDiscoverySelectionRevision",2) }
        val second=checkNotNull(AutomaticRecordingDiscovery.capture(context));assertNotEquals(first,second)
        settings.update { it.put("personalPhoneDisabled",true) }
        assertNotEquals(second,AutomaticRecordingDiscovery.capture(context))
        assertEquals("HELD_SOURCE",AutomaticRecordingDiscovery.cycle(context,first,{false}).getString("state"))
    }
    @Test fun missingOrMalformedBindingWaitsForRearm() {
        assertEquals("WAITING_DISCOVERY_BINDING",AutomaticRecordingDiscovery.cycle(context,null,{false}).getString("state"))
        assertNull(AutomaticRecordingDiscovery.binding(androidx.work.workDataOf("selection_fence" to "not-a-hash")))
    }
    // ---- r7-2 (design 3.2 #4): every failure path leaves an attempt time and an outcome code ----
    private fun last()=SecureSettings(context).snapshot().let { it.optLong(SecureSettings.LAST_DISCOVERY_ATTEMPT_MS,0L) to it.optString(SecureSettings.LAST_DISCOVERY_OUTCOME) }
    @Test fun R72EveryFailurePathLeavesAnOutcomeCode() {
        AutomaticRecordingDiscovery.cycle(context,null,{false})
        assertEquals("WAITING_DISCOVERY_BINDING:DISCOVERY_WORK_REARM_REQUIRED",last().second);assertTrue(last().first>0)
        val settings=SecureSettings(context);settings.update { it.put("treeUri","content://generated-fixture/tree/test") }
        val first=checkNotNull(AutomaticRecordingDiscovery.capture(context))
        AutomaticRecordingDiscovery.cycle(context,first,{true});assertEquals("LOCAL_WORK_STOPPED",last().second)
        AutomaticRecordingDiscovery.cycle(context,first,{false});assertEquals("HELD_SOURCE:SELECTED_FOLDER_READ_GRANT_REQUIRED",last().second)
        settings.update { it.put("automaticDiscoverySelectionRevision",2) }
        AutomaticRecordingDiscovery.cycle(context,first,{false});assertEquals("HELD_SOURCE:DISCOVERY_SELECTION_OR_POLICY_CHANGED",last().second)
        assertEquals(0L,SecureSettings(context).snapshot().optLong(AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,0L))
    }
    @Test fun R72ErrorCodesCarryNoMessageText() {
        assertEquals("ERROR:METADATA_SCAN_FAILED",AutomaticRecordingDiscovery.errorCode(kr.co.ieum.discovery.DiscoveryTypes.Failure("METADATA_SCAN_FAILED")))
        assertEquals("ERROR:IOException",AutomaticRecordingDiscovery.errorCode(java.io.IOException("SYNTHETIC private name.m4a")))
        assertEquals("ERROR:IllegalStateException",AutomaticRecordingDiscovery.errorCode(IllegalStateException("some text with a name")))
        assertEquals("ERROR:PROVIDER_LIST_FAILED",AutomaticRecordingDiscovery.errorCode(IllegalStateException("PROVIDER_LIST_FAILED")))
    }
    /** #4 / #21: the call log trigger keeps the code (no swallowing) and is registered again even when stopped or failing. */
    @Test fun R72CallLogTriggerIsRegisteredAgainEvenWhenStoppedOrFailing() {
        var rearms=0
        val failed=AutomaticRecordingDiscovery.runCallLogTrigger(context,{false},run={throw kr.co.ieum.discovery.DiscoveryTypes.Failure("METADATA_SCAN_FAILED")},rearm={rearms++})
        assertEquals("ERROR:METADATA_SCAN_FAILED",failed);assertEquals(1,rearms)
        val stopped=AutomaticRecordingDiscovery.runCallLogTrigger(context,{true},run={throw IllegalStateException("DISCOVERY_RUN_FENCE_CHANGED")},rearm={rearms++})
        assertEquals("LOCAL_WORK_STOPPED",stopped);assertEquals(2,rearms)
        val ok=AutomaticRecordingDiscovery.runCallLogTrigger(context,{true},run={AutomaticRecordingDiscovery.outcome("DISCOVERY_CHECKED")},rearm={rearms++;error("SYNTHETIC_REARM")})
        assertEquals("DISCOVERY_CHECKED",ok);assertEquals(3,rearms)
        // The real path: no folder -> the cycle's own code, trigger registered again.
        assertEquals("WAITING_DISCOVERY_BINDING:DISCOVERY_WORK_REARM_REQUIRED",AutomaticRecordingDiscovery.runCallLogTrigger(context,{false},rearm={rearms++}))
        assertEquals(4,rearms);assertEquals("WAITING_DISCOVERY_BINDING:DISCOVERY_WORK_REARM_REQUIRED",last().second)
    }
    @Test fun withoutAPersistedReadGrantTheScanIsHeldBeforeAnyRead() {
        SecureSettings(context).update { it.put("treeUri","content://generated-fixture/tree/test") }
        val binding=checkNotNull(AutomaticRecordingDiscovery.capture(context))
        val result=AutomaticRecordingDiscovery.cycle(context,binding,{false})
        assertEquals("HELD_SOURCE",result.getString("state"));assertEquals("SELECTED_FOLDER_READ_GRANT_REQUIRED",result.getString("reason"))
    }
}
