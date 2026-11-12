package kr.callbrief.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone restarts or the app is updated: register the call pipeline again without opening the app. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // v3.7 (user 11/12): an install the open app started has ended: the new version opens again (or its notification).
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) runCatching { OpenUpdate.afterReplaced(context) }
        val pending = goAsync()
        CallPipelineStartup.init(context) { pending.finish() }
    }
}
