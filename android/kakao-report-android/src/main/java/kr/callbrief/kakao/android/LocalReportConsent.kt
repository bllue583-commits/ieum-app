package kr.callbrief.kakao.android

import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.callbrief.kakao.core.VerifiedSession
import org.json.JSONObject
import java.time.Instant

class LocalReportConsent(private val store: PrivateKakaoStore) {
    fun snapshot(): DeliveryCoordinator.Consent {
        val state=store.snapshot()
        val c = state.optJSONObject("reportConsent")
        return DeliveryCoordinator.Consent(c?.optBoolean("enabled") == true,c?.optString("appUserKey").orEmpty(),
            c?.optString("subject").orEmpty(),c?.optString("policyVersion").orEmpty(),readEpoch(state))
    }
    private fun readEpoch(state:JSONObject):Long {
        val value=state.opt("channelEpoch") ?: 0L
        check(value is Int || value is Long) { "invalid_channel_epoch" }
        return (value as Number).toLong().also {check(it>=0 && it<9007199254740991L)}
    }
    fun epoch():Long = readEpoch(store.snapshot())
    private fun advance(value:JSONObject):Long {
        val old=readEpoch(value)
        return (old+1).also { value.put("channelEpoch",it) }
    }
    /** Standing opt-in starts with that calendar day, never drains older reports. */
    fun automaticDateAllowed(appUserKey:String,date:java.time.LocalDate,zone:java.time.ZoneId):Boolean {
        val state=store.snapshot();readEpoch(state)
        val c=state.optJSONObject("reportConsent") ?: return false
        if(!c.optBoolean("enabled") || c.optString("appUserKey")!=appUserKey || c.optString("policyVersion")!=DeliveryCoordinator.POLICY_VERSION)return false
        val activation=Instant.parse(c.getString("confirmedAt"))
        return !date.isBefore(activation.atZone(zone).toLocalDate())
    }
    /** Call only after the app user confirms the disclosure for this freshly verified Kakao account. */
    fun enable(appUserKey: String, session: VerifiedSession,expectedEpoch:Long=epoch()) {
        require(appUserKey.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        require(session.talkMessageAgreed && session.expiresAt.isAfter(Instant.now().plusSeconds(30)))
        store.update {
            check(readEpoch(it)==expectedEpoch) { "consent_changed" }
            check(it.optString("connectedProfile") == appUserKey) { "profile_changed" }
            check(it.optString("connectedSubject") == session.subject) { "account_changed" }
            advance(it)
            it.put("reportConsent",JSONObject().put("enabled",true).put("appUserKey",appUserKey)
                .put("subject",session.subject).put("policyVersion",DeliveryCoordinator.POLICY_VERSION)
                .put("confirmedAt",Instant.now().toString()))
        }
    }
    fun disable() = store.update { advance(it);it.remove("reportConsent") }
    fun beginConnection():Long {
        var generation=0L
        store.update { generation=advance(it);it.remove("reportConsent");it.remove("connectedProfile");it.remove("connectedSubject") }
        return generation
    }
    fun connected(appUserKey: String, session: VerifiedSession,expectedEpoch:Long=epoch()) = store.update {
        check(readEpoch(it)==expectedEpoch) { "connection_changed" }
        it.put("connectedProfile",appUserKey); it.put("connectedSubject",session.subject)
    }
}
