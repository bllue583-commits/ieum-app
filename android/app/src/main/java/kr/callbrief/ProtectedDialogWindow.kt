package kr.callbrief

import android.app.Dialog
import android.view.WindowManager

/** A Dialog owns a separate Window; never rely on its Activity to inherit capture protection. */
internal object ProtectedDialogWindow {
    fun <T : Dialog> show(dialog: T): T {
        // Fail closed before first display if there is no visual Window to protect.
        val window = checkNotNull(dialog.window) { "DIALOG_WINDOW_UNAVAILABLE" }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        return dialog
    }
}
