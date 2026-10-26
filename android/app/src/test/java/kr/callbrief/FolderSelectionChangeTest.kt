package kr.callbrief

import org.junit.Assert.*
import org.junit.Test

/** Protocol tests with synthetic grants; not SAF or AndroidKeyStore execution. */
class FolderSelectionChangeTest {
    @Test fun uriAndStatusAreSavedTogetherBeforeOldGrantIsReleased() {
        val steps = mutableListOf<String>(); val grants = mutableSetOf("synthetic-old")
        var state = mapOf("treeUri" to "synthetic-old", "statusMessage" to "old")
        var saves = 0
        val result = FolderSelectionChange.replace("synthetic-new", { state["treeUri"] },
            { grants.add(it); steps.add("acquire") },
            { saves++; state = mapOf("treeUri" to it, "statusMessage" to "saved"); steps.add("save") },
            { assertEquals("synthetic-new", state["treeUri"]); assertEquals("saved", state["statusMessage"]); grants.remove(it); steps.add("release") })
        assertEquals(FolderSelectionChange.Result.SAVED, result); assertEquals(1, saves)
        assertEquals(listOf("acquire", "save", "release"), steps); assertEquals(setOf("synthetic-new"), grants)
    }
    @Test fun deniedGrantLeavesSettingsAndPreviousGrantUntouched() {
        var saved = false; var released = false
        val result = FolderSelectionChange.replace("synthetic-new", { "synthetic-old" },
            { throw SecurityException("fixture denied") }, { saved = true }, { released = true })
        assertEquals(FolderSelectionChange.Result.GRANT_REJECTED, result); assertFalse(saved); assertFalse(released)
    }
    @Test fun failureBeforeStorageRetainsBothGrantsAndReportsUnconfirmed() {
        val grants = mutableSetOf("synthetic-old"); var uri = "synthetic-old"
        val result = FolderSelectionChange.replace("synthetic-new", { uri }, { grants.add(it) },
            { throw IllegalStateException("fixture write failure") }, { grants.remove(it); uri = "WRONG" })
        assertEquals(FolderSelectionChange.Result.SAVE_UNCONFIRMED, result)
        assertEquals("synthetic-old", uri); assertEquals(setOf("synthetic-old", "synthetic-new"), grants)
    }
    @Test fun failureAfterMemoryWriteNeverReleasesGrantsOrClaimsFolderRejected() {
        val grants = mutableSetOf("synthetic-old"); var uri = "synthetic-old"
        val result = FolderSelectionChange.replace("synthetic-new", { uri }, { grants.add(it) },
            { uri = it; throw IllegalStateException("fixture indeterminate disk ack") }, { grants.remove(it) })
        assertEquals(FolderSelectionChange.Result.SAVE_UNCONFIRMED, result)
        assertEquals("synthetic-new", uri); assertEquals(setOf("synthetic-old", "synthetic-new"), grants)
    }
    @Test fun releaseFailureStillReportsTheSuccessfulSelection() {
        var saved = false
        val result = FolderSelectionChange.replace("synthetic-new", { "synthetic-old" }, {}, { saved = true }, { throw SecurityException("fixture release") })
        assertTrue(saved); assertEquals(FolderSelectionChange.Result.SAVED_PREVIOUS_GRANT_RETAINED, result)
    }
    @Test fun sameFolderIsNeverReleasedAndUnreadableStateNeverAcquiresAnything() {
        assertEquals(FolderSelectionChange.Result.SAVED, FolderSelectionChange.replace("same", { "same" }, {}, {}, { error("must not release") }))
        assertEquals(FolderSelectionChange.Result.CURRENT_STATE_UNREADABLE, FolderSelectionChange.replace("new", { throw IllegalStateException("fixture state") }, { error("must not acquire") }, {}, {}))
    }
}
