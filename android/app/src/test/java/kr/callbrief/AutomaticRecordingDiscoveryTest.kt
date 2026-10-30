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
    @Test fun withoutAPersistedReadGrantTheScanIsHeldBeforeAnyRead() {
        SecureSettings(context).update { it.put("treeUri","content://generated-fixture/tree/test") }
        val binding=checkNotNull(AutomaticRecordingDiscovery.capture(context))
        val result=AutomaticRecordingDiscovery.cycle(context,binding,{false})
        assertEquals("HELD_SOURCE",result.getString("state"));assertEquals("SELECTED_FOLDER_READ_GRANT_REQUIRED",result.getString("reason"))
    }
}
