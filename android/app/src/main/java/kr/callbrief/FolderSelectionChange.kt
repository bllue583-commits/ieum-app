package kr.callbrief

/**
 * SAF permission and settings storage are separate transactions. Persist URI + message once,
 * then release the previous grant. A storage exception may occur after an in-memory/disk write:
 * keep BOTH grants and report an unconfirmed save, rather than claiming the folder was rejected.
 */
internal object FolderSelectionChange {
    enum class Result { SAVED, SAVED_PREVIOUS_GRANT_RETAINED, GRANT_REJECTED, SAVE_UNCONFIRMED, CURRENT_STATE_UNREADABLE }
    fun replace(
        selected: String,
        current: () -> String?,
        acquire: (String) -> Unit,
        saveUriAndMessage: (String) -> Unit,
        release: (String) -> Unit
    ): Result {
        val previous = try { current()?.takeIf { it.isNotBlank() } } catch (_: Exception) { return Result.CURRENT_STATE_UNREADABLE }
        try { acquire(selected) } catch (_: Exception) { return Result.GRANT_REJECTED }
        try { saveUriAndMessage(selected) } catch (_: Exception) { return Result.SAVE_UNCONFIRMED }
        if (previous != null && previous != selected) {
            try { release(previous) } catch (_: Exception) { return Result.SAVED_PREVIOUS_GRANT_RETAINED }
        }
        return Result.SAVED
    }
}
