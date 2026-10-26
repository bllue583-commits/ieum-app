package kr.callbrief.kakao.android

import android.app.Activity
import android.content.Context
import com.kakao.sdk.auth.AuthApiClient
import com.kakao.sdk.auth.TokenManagerProvider
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import kr.callbrief.kakao.core.DeliveryResult
import kr.callbrief.kakao.core.DeliveryResult.Status
import kr.callbrief.kakao.core.TokenRenewalPolicy
import kr.callbrief.kakao.core.VerifiedSession
import java.time.Instant

/** Invoked only after explicit UI selection. No friend/profile/chat reads, no embedded account ID. */
class KakaoSdkSessionProvider(
    private val context: Context, private val config: KakaoConfiguration, private val tokens: EncryptedKakaoTokenManager
) {
    companion object { private var initializedKey: String?=null; private val initLock=Any() }
    private fun initialize(): Boolean {
        if(!config.canLogin) return false
        synchronized(initLock) {
            check(initializedKey==null || initializedKey==config.nativeAppKey) { "sdk_app_key_changed" }
            if(initializedKey==null) {
                KakaoSdk.init(context.applicationContext,config.nativeAppKey,loggingEnabled=false)
                TokenManagerProvider.instance.manager=tokens
                initializedKey=config.nativeAppKey
            }
        }
        return true
    }
    fun login(activity: Activity, callback: (VerifiedSession?, DeliveryResult?) -> Unit) {
        try {
            if(!initialize()) { callback(null,DeliveryResult(Status.CONFIG_REQUIRED)); return }
            val loggedIn: (OAuthToken?,Throwable?) -> Unit = { token,error ->
                if(error!=null || token==null) callback(null,DeliveryResult(if(error is ClientError && error.reason==ClientErrorCause.Cancelled) Status.CANCELLED else Status.AUTH_REQUIRED))
                else verify { session,problem ->
                    if(problem?.status==Status.SCOPE_REQUIRED) {
                        UserApiClient.instance.loginWithNewScopes(activity,listOf("talk_message")) { granted,grantError ->
                            if(grantError!=null || granted==null) callback(null,DeliveryResult(Status.SCOPE_REQUIRED))
                            else verify(callback)
                        }
                    } else callback(session,problem)
                }
            }
            if(UserApiClient.instance.isKakaoTalkLoginAvailable(activity)) {
                UserApiClient.instance.loginWithKakaoTalk(activity) { token,error ->
                    if(error is ClientError && error.reason==ClientErrorCause.Cancelled) callback(null,DeliveryResult(Status.CANCELLED))
                    else if(error!=null) UserApiClient.instance.loginWithKakaoAccount(activity,callback=loggedIn)
                    else loggedIn(token,null)
                }
            } else UserApiClient.instance.loginWithKakaoAccount(activity,callback=loggedIn)
        } catch(e: Exception) { callback(null,DeliveryResult(Status.AUTH_REQUIRED)) }
    }
    /** Background preparation may refresh existing credentials but never obtains a new interactive grant. */
    fun ready(callback: (VerifiedSession?, DeliveryResult?) -> Unit) {
        try {
            if(!initialize()) { callback(null,DeliveryResult(Status.CONFIG_REQUIRED)); return }
            val token=tokens.getToken()
            when(TokenRenewalPolicy.decide(token?.accessTokenExpiresAt?.toInstant(),token?.refreshTokenExpiresAt?.toInstant(),Instant.now())) {
                TokenRenewalPolicy.Action.USER_LOGIN_REQUIRED -> callback(null,DeliveryResult(Status.AUTH_REQUIRED))
                TokenRenewalPolicy.Action.REFRESH -> AuthApiClient.instance.refreshToken(token!!) { fresh,error ->
                    if(error!=null || fresh==null) callback(null,DeliveryResult(if(error is java.io.IOException) Status.CONNECTION_CHECK_FAILED else Status.AUTH_REQUIRED)) else verify(callback)
                }
                TokenRenewalPolicy.Action.VERIFY -> verify(callback)
                else -> callback(null,DeliveryResult(Status.AUTH_REQUIRED))
            }
        } catch(e: Exception) { callback(null,DeliveryResult(Status.AUTH_REQUIRED)) }
    }
    private fun verify(callback: (VerifiedSession?, DeliveryResult?) -> Unit) {
        UserApiClient.instance.accessTokenInfo { info,error ->
            if(error!=null || info?.id==null || info.expiresIn<=30) { callback(null,DeliveryResult(if(error is java.io.IOException) Status.CONNECTION_CHECK_FAILED else Status.AUTH_REQUIRED)); return@accessTokenInfo }
            if(info.appId!=config.developerAppId) { callback(null,DeliveryResult(Status.CONFIG_REQUIRED)); return@accessTokenInfo }
            val afterInfo=try { tokens.getToken() } catch(e: Exception) { callback(null,DeliveryResult(Status.STORAGE_FAILURE)); return@accessTokenInfo }
            UserApiClient.instance.scopes(listOf("talk_message")) { scopes,scopeError ->
                try {
                    if(scopeError!=null || scopes==null) { callback(null,DeliveryResult(Status.CONNECTION_CHECK_FAILED)); return@scopes }
                    if(scopes.id!=info.id) { callback(null,DeliveryResult(Status.ACCOUNT_MISMATCH)); return@scopes }
                    val scope=scopes.scopes?.firstOrNull { it.id=="talk_message" }
                    if(scope==null || !scope.using) { callback(null,DeliveryResult(Status.CONFIG_REQUIRED)); return@scopes }
                    if(!scope.agreed) { callback(null,DeliveryResult(Status.SCOPE_REQUIRED)); return@scopes }
                    val current=tokens.getToken()
                    if(current==null || afterInfo==null || current.accessToken!=afterInfo.accessToken ||
                        !current.accessTokenExpiresAt.toInstant().isAfter(Instant.now().plusSeconds(30))) {
                        callback(null,DeliveryResult(Status.AUTH_REQUIRED)); return@scopes
                    }
                    callback(VerifiedSession(info.id.toString(),info.appId,current.accessToken,current.accessTokenExpiresAt.toInstant(),true),null)
                } catch(e: Exception) { callback(null,DeliveryResult(Status.STORAGE_FAILURE)) }
            }
        }
    }
}
