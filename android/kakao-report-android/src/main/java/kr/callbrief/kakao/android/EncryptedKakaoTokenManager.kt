package kr.callbrief.kakao.android

import com.kakao.sdk.auth.TokenManageable
import com.kakao.sdk.auth.model.OAuthToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** One installed SDK token manager. SDK refreshes overwrite access AND rotated refresh credentials atomically. */
class EncryptedKakaoTokenManager(private val store: PrivateKakaoStore) : TokenManageable {
    override fun getToken(): OAuthToken? {
        val value = store.snapshot().optJSONObject("sdkToken") ?: return null
        val scopes = value.optJSONArray("scopes")?.let { a -> List(a.length()) { a.getString(it) } }
        return OAuthToken(value.getString("access"),Date(value.getLong("accessExpiry")),value.getString("refresh"),
            Date(value.getLong("refreshExpiry")),value.optString("idToken").takeIf { it.isNotBlank() },scopes)
    }
    override fun setToken(token: OAuthToken) = store.update {
        val value = JSONObject().put("access",token.accessToken).put("accessExpiry",token.accessTokenExpiresAt.time)
            .put("refresh",token.refreshToken).put("refreshExpiry",token.refreshTokenExpiresAt.time)
        token.idToken?.let { id -> value.put("idToken",id) }
        token.scopes?.let { s -> value.put("scopes",JSONArray(s)) }
        it.put("sdkToken",value)
    }
    override fun clear() = store.update { it.remove("sdkToken"); it.remove("reportConsent"); it.remove("connectedProfile"); it.remove("connectedSubject") }
    override fun toString() = "EncryptedKakaoTokenManager[credentials withheld]"
}
