package kr.callbrief.kakao.android

import android.net.Uri
import java.time.LocalDate

/** Read-only navigation. Never turns an incoming intent into authorization or a send request. */
object KakaoDigestLinkRouter {
    fun reportDate(uri: Uri?,config: KakaoConfiguration): LocalDate? = try {
        if(uri==null || !config.canLogin || uri.scheme!="kakao${config.nativeAppKey}" || uri.host!="kakaolink" ||
            uri.port!=-1 || uri.userInfo!=null || uri.fragment!=null || (uri.path.orEmpty()!="" && uri.path!="/") ||
            uri.queryParameterNames!=setOf("screen","date") || uri.getQueryParameters("screen").size!=1 ||
            uri.getQueryParameters("date").size!=1 || uri.getQueryParameter("screen")!="digest") null
        else uri.getQueryParameter("date")?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }?.let { LocalDate.parse(it) }
    } catch(e: Exception) { null }
}
