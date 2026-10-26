package kr.callbrief.kakao.android

import android.content.Context

data class KakaoConfiguration(
    val nativeAppKey: String = "",
    val developerAppId: Int = 0,
    val publicLandingUrl: String = "",
    val connectionEnabled: Boolean = false,
    val liveSendEnabled: Boolean = false
) {
    val canLogin: Boolean get() = connectionEnabled && nativeAppKey.matches(Regex("[a-fA-F0-9]{32}")) && developerAppId > 0
    override fun toString() = "KakaoConfiguration[values withheld]"
    companion object {
        fun fromResources(context: Context) = KakaoConfiguration(
            context.getString(R.string.kakao_native_app_key), context.resources.getInteger(R.integer.kakao_developer_app_id),
            context.getString(R.string.kakao_public_landing_url), context.resources.getBoolean(R.bool.kakao_connection_enabled),
            context.resources.getBoolean(R.bool.kakao_live_send_enabled)
        )
    }
}
