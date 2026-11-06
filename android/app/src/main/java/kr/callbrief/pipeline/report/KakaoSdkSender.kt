package kr.callbrief.pipeline.report

import android.content.Context
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.common.model.ApiError
import com.kakao.sdk.common.model.ApiErrorCause
import com.kakao.sdk.common.model.AuthError
import com.kakao.sdk.common.model.AuthErrorCause
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.share.ShareClient
import com.kakao.sdk.talk.TalkApiClient
import com.kakao.sdk.template.model.Button
import com.kakao.sdk.template.model.Content
import com.kakao.sdk.template.model.FeedTemplate
import com.kakao.sdk.template.model.Link
import com.kakao.sdk.template.model.TextTemplate
import com.kakao.sdk.user.UserApiClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * v3.5 (DESIGN_V5 2.1): the one place that calls the Kakao SDK (v2 2.24.0). Login (KakaoTalk app, else the Kakao account;
 * `talk_message` only), image upload ([ShareClient.uploadImage]) and "나에게 보내기" ([TalkApiClient.sendDefaultMemo]).
 * Everything else in [KakaoReport] runs and is tested without it, through [KakaoReport.Sender]. Errors come back as codes:
 * a token Kakao no longer takes is [KakaoReport.TOKEN_PREFIX]..., so the link goes down.
 */
internal class KakaoSdkSender(private val app:Context):KakaoReport.Sender {
    override suspend fun uploadImage(png:ByteArray):String? {
        val file=File(app.cacheDir,"kakao-report.png")
        file.writeBytes(png)
        try {
            return suspendCancellableCoroutine { c -> ShareClient.instance.uploadImage(file) { result,error ->
                if(error!=null) c.resumeWith(Result.failure(KakaoReport.Failure(code(error,"UPLOAD"))))
                else c.resume(result?.infos?.original?.url) } }
        } finally { file.delete() }
    }
    override suspend fun send(message:KakaoReport.Message,imageUrl:String?):String? {
        val link=link(message.link)
        val template=when(message) {
            is KakaoReport.Message.Feed -> {
                val url=imageUrl ?: return "NO_IMAGE_URL"
                val (w,h)=pngSize(message.png)
                FeedTemplate(content=Content(title=message.title,imageUrl=url,link=link,description=message.body,imageWidth=w,imageHeight=h),
                    buttons=listOf(Button(KakaoReport.BUTTON,link)))
            }
            is KakaoReport.Message.Text -> TextTemplate(text=message.text,link=link,buttons=listOf(Button(KakaoReport.BUTTON,link)))
        }
        return suspendCancellableCoroutine { c -> TalkApiClient.instance.sendDefaultMemo(template) { error ->
            c.resume(error?.let { code(it,"SEND") }) } }
    }

    companion object {
        const val SCOPE="talk_message"
        @Volatile private var started=false
        fun init(app:Context,key:String) {
            if(started) return
            KakaoSdk.init(app,key);started=true
        }
        /** The Kakao login, then `talk_message` when it was not agreed yet. Null on success, else a code. */
        suspend fun login(activity:Context):String? {
            val user=UserApiClient.instance
            val first:Throwable?=if(user.isKakaoTalkLoginAvailable(activity)) {
                val e=suspendCancellableCoroutine<Throwable?> { c -> user.loginWithKakaoTalk(activity) { _,error -> c.resume(error) } }
                // The user closed the KakaoTalk screen: no second login on top of it.
                if(e is ClientError && e.reason==ClientErrorCause.Cancelled) return "CANCELLED"
                if(e is AuthError && e.reason==AuthErrorCause.AccessDenied) return "CANCELLED"
                e?.let { suspendCancellableCoroutine { c -> user.loginWithKakaoAccount(activity) { _,error -> c.resume(error) } } }
            } else suspendCancellableCoroutine { c -> user.loginWithKakaoAccount(activity) { _,error -> c.resume(error) } }
            if(first!=null) return if(first is ClientError && first.reason==ClientErrorCause.Cancelled) "CANCELLED" else code(first,"LOGIN")
            val agreed=suspendCancellableCoroutine<Boolean> { c -> user.scopes(listOf(SCOPE)) { info,error ->
                c.resume(error==null && info?.scopes?.any { it.id==SCOPE && it.agreed }==true) } }
            if(agreed) return null
            val e=suspendCancellableCoroutine<Throwable?> { c -> user.loginWithNewScopes(activity,listOf(SCOPE)) { _,error -> c.resume(error) } }
            return e?.let { if(it is ClientError && it.reason==ClientErrorCause.Cancelled) "CANCELLED" else code(it,"SCOPE") }
        }
        suspend fun logout() { suspendCancellableCoroutine<Unit> { c -> UserApiClient.instance.logout { c.resume(Unit) } } }

        /** "screen=calls&dayMs=…" to the button's Android execution parameters. */
        internal fun params(link:String):Map<String,String> = link.split('&').mapNotNull { p ->
            val i=p.indexOf('=');if(i<=0) null else p.substring(0,i) to p.substring(i+1) }.toMap()
        private fun link(raw:String)=Link(androidExecutionParams=params(raw))
        /** Width and height from a PNG's IHDR (the first chunk). */
        internal fun pngSize(png:ByteArray):Pair<Int,Int> {
            if(png.size<24) return KakaoReport.WIDTH to KakaoReport.MAX_HEIGHT
            fun int(at:Int)=((png[at].toInt() and 0xff) shl 24) or ((png[at+1].toInt() and 0xff) shl 16) or ((png[at+2].toInt() and 0xff) shl 8) or (png[at+3].toInt() and 0xff)
            return int(16) to int(20)
        }
        /** A Kakao error as a code; a token Kakao no longer takes (or a consent taken back) starts with TOKEN_. */
        internal fun code(e:Throwable,stage:String):String = when(e) {
            is ApiError -> when(e.reason) {
                ApiErrorCause.InvalidToken -> "TOKEN_INVALID"
                ApiErrorCause.InsufficientScope -> "TOKEN_SCOPE"
                ApiErrorCause.NotRegisteredUser,ApiErrorCause.AccountDoesNotExist -> "TOKEN_"+e.reason.name.uppercase()
                else -> "${stage}_API_${e.reason.name}"
            }
            is AuthError -> if(e.reason==AuthErrorCause.InvalidGrant) "TOKEN_INVALID_GRANT" else "${stage}_AUTH_${e.reason.name}"
            is ClientError -> if(e.reason==ClientErrorCause.TokenNotFound) "TOKEN_NOT_FOUND" else "${stage}_CLIENT_${e.reason.name}"
            else -> "${stage}_${e.javaClass.simpleName}"
        }
    }
}
