package kr.callbrief

import android.content.Context
import kr.callagent.drive.DriveRelay
import org.json.JSONObject

/** Local accepted profile scope. SDK account verification is not a dot processing capability.
 * No OAuth/consent, request or frozen schema is rewritten to accept a new account. */
internal class FrozenDotCapability(context:Context) {
    private val profile=runCatching { context.assets.open("dot_v0_posting_capability.json").use { JSONObject(String(it.readBytes(),Charsets.UTF_8)) } }.getOrNull()
    fun supports(account:String):Boolean=runCatching {
        val p=checkNotNull(profile);p.getString("profile")=="CALL_ANALYSIS_REQUEST" && p.getLong("schemaVersion")==0L &&
            !p.getBoolean("generalAccountsAccepted") && DriveRelay.sha256(account.toByteArray(Charsets.UTF_8))==p.getString("acceptedAccountSha256")
    }.getOrDefault(false)
}
