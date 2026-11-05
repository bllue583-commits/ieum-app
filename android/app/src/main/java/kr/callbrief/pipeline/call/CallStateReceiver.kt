package kr.callbrief.pipeline.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** The system's PHONE_STATE broadcast (design v2.9 6.1, r10-1). Needs READ_PHONE_STATE; the number comes with it because the
 * app may read the call log. Exported because the sender is the system; anything else is ignored by the action check, and
 * [CallSession] does no more with a false broadcast than queue one discovery pass. The work runs on the session's own
 * thread (settings and the store are read there), inside the receiver's goAsync time. v3.0: then the call card follows the
 * new state ([CallCardService.follow]).
 *
 * v3.4 (DESIGN_V4 4.1-2, 4.1-5, r14-1): the time the broadcast came ([SystemClock.elapsedRealtime]) and whether this process
 * was started by it (cold) go to [CardStats.signal] first, for `ringToCardMs`; the new state is written to settings only after
 * the card was told ([CallSession.persist]). */
class CallStateReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent?) {
        if(intent?.action!=TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val at=CardStats.elapsed()
        val cold=runCatching { CardStats.coldNow(at) }.getOrDefault(false)
        val state=intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION") val number=intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val app=context.applicationContext ?: context
        val pending=runCatching { goAsync() }.getOrNull()
        CallSession.post {
            try {
                runCatching { CardStats.signal(at,cold) }
                CallSession.onPhoneState(app,state,number,persistLater=true)
                // v3.0 (r11-1): the call card follows the state (nothing for an ignored call, see CallCardService.follow).
                runCatching { CallCardService.follow(app,CallSession.current()) }
            } finally { runCatching { CallSession.persist(app) };runCatching { pending?.finish() } }
        }
    }
}
