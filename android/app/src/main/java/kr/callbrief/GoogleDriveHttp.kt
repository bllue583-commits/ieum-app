package kr.callbrief

import kr.callagent.drive.AuthorizationPort
import kr.callagent.drive.DriveRestTransport
import kr.callagent.drive.DriveTransport
import kr.callagent.drive.StrictJson
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Authenticator
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.net.URI
import java.util.concurrent.TimeUnit

/** Production HTTPS adapter: fixed Google endpoints, no browser cookies, redirects or token store. */
internal class GoogleDriveHttp(private val session: GoogleConnectionSession, private val client: OkHttpClient = client()) : DriveRestTransport.HttpPort {
    data class Owner(val permissionId: String, val account: String)
    override fun execute(request: DriveRestTransport.Request): DriveRestTransport.Response {
        val epoch = session.epoch()
        if (session.state() != AuthorizationPort.State.ACTIVE || request.authorizationHeader() != "Bearer " + session.accessToken())
            throw DriveTransport.DriveException(DriveTransport.Failure.UNAUTHORIZED)
        val result = executeBounded(request.method, request.url, request.contentType, request.body, request.authorizationHeader(), request.maxResponseBytes, epoch, false)
        if (result.status == 401) session.invalidate(AuthorizationPort.State.TOKEN_EXPIRED)
        return result
    }
    fun verifySelectedOwner(epoch: Long, account: String, accessToken: String): Owner {
        session.requireEpoch(epoch)
        require(accessToken.isNotBlank() && !accessToken.contains('\n') && !accessToken.contains('\r'))
        session.checking(epoch)
        val r = executeBounded("GET", "https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress)", "", byteArrayOf(), "Bearer " + accessToken, 65536, epoch, true)
        check(r.status == 200) { "DRIVE_ACCOUNT_CHECK_FAILED" }
        val user = StrictJson.`object`(StrictJson.`object`(StrictJson.parse(r.body))["user"])
        val email = user["emailAddress"] as? String ?: error("DRIVE_ACCOUNT_UNCONFIRMED")
        val owner = user["permissionId"] as? String ?: error("DRIVE_OWNER_UNCONFIRMED")
        check(account.equals(email, ignoreCase = true)) { "GOOGLE_ACCOUNT_MISMATCH" }
        return Owner(owner, email)
    }
    private fun executeBounded(method: String, url: String, type: String, bytes: ByteArray, bearer: String, maximum: Int, epoch: Long, pending: Boolean): DriveRestTransport.Response {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "www.googleapis.com" && uri.port == -1 && uri.rawUserInfo == null)
        require(uri.path.startsWith("/drive/v3/") || uri.path.startsWith("/upload/drive/v3/"))
        require(maximum in 0..5242880)
        session.requireEpoch(epoch)
        val delegate=bytes.toRequestBody(type.toMediaTypeOrNull())
        val body = if (method == "GET") null else object:RequestBody() {
            override fun contentType()=delegate.contentType()
            override fun contentLength()=delegate.contentLength()
            override fun isOneShot()=true // Also suppresses OkHttp's 503/Retry-After follow-up.
            override fun writeTo(sink:BufferedSink) {
                var offset=0
                while(offset<bytes.size) {
                    session.requireEpoch(epoch);check(session.state()==AuthorizationPort.State.ACTIVE) { "GOOGLE_REAUTH_REQUIRED" }
                    val n=minOf(8192,bytes.size-offset);sink.write(bytes,offset,n);offset+=n
                }
            }
        }
        val call: Call = client.newCall(Request.Builder().url(url).header("Authorization", bearer).method(method, body).build())
        val cancel: () -> Unit = { call.cancel() }
        session.registerCancellation(epoch, cancel, pending)
        try {
            call.execute().use { response ->
                session.requireEpoch(epoch)
                val output = java.io.ByteArrayOutputStream()
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        session.requireEpoch(epoch)
                        val n = input.read(buffer); if (n < 0) break
                        require(output.size() + n <= maximum) { "GOOGLE_RESPONSE_TOO_LARGE" }
                        output.write(buffer, 0, n)
                    }
                }
                session.requireEpoch(epoch)
                val retry = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 60)?.times(1000) ?: 0L
                return DriveRestTransport.Response(response.code, output.toByteArray(), retry)
            }
        } catch (_: java.io.IOException) {
            session.requireEpoch(epoch)
            throw DriveTransport.DriveException(DriveTransport.Failure.TRANSIENT)
        } finally { session.unregisterCancellation(cancel) }
    }
    companion object {
        fun client() = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }
}
