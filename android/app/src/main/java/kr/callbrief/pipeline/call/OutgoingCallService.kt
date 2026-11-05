package kr.callbrief.pipeline.call

import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import androidx.annotation.RequiresApi

/** The number of a call that goes out (design v3.0 7.2, r11-1). With the call redirection role (an optional setup step) the
 * system hands every call the user places to [onPlaceCall] first, numbers in the phone book included. The number is put in
 * [CallSession.pendingOutgoing] (its key only, in memory) and the call goes on at once, unchanged: this service never
 * redirects and never cancels a call, whatever fails. The OFFHOOK that follows within [CallSession.OUTGOING_WINDOW_MS] is then
 * that call, with its number, and the call card can show. */
@RequiresApi(29)
class OutgoingCallService:CallRedirectionService() {
    companion object {
        private const val TAG="OutgoingCallService"
        /** Lets the call go on. Replaceable in host tests (there is no telecom adapter on the host). */
        @Volatile internal var placeUnmodified:(CallRedirectionService)->Unit={ it.placeCallUnmodified() }
        @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
        internal fun resetForHost() { placeUnmodified={ it.placeCallUnmodified() };clock={ System.currentTimeMillis() } }
    }

    override fun onPlaceCall(handle:Uri,initialPhoneAccount:PhoneAccountHandle,allowInteractiveResponse:Boolean) {
        // r11-3: the emulator check (tools/device/call_card_test.sh) looks for these two lines; never the number.
        Log.i(TAG,"onPlaceCall")
        try {
            // tel: numbers only; a SIP address or a voicemail call gives no number to show a card for.
            val number=handle.takeIf { it.scheme=="tel" }?.schemeSpecificPart
            CallSession.pendingOutgoing(number,clock())
        } catch(t:Throwable) {
            Log.w(TAG,"pending failed: ${t.javaClass.simpleName}")
        } finally {
            // Always, and right away: the system waits for this answer before it dials.
            runCatching { placeUnmodified(this);Log.i(TAG,"placeCallUnmodified") }.onFailure { Log.w(TAG,"placeCallUnmodified failed: ${it.javaClass.simpleName}") }
        }
    }
}
