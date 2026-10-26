package kr.callbrief

import kr.callagent.drive.StrictJson
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** No calls occur on construction. Browser authentication, keys and OAuth are distinct from inference. */
internal interface SiwcAuthTransport {
    fun metadata():SiwcOAuthProtocol.Metadata
    fun jwks(metadata:SiwcOAuthProtocol.Metadata):ByteArray
    fun token(form:Map<String,String>):ByteArray
    fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String):Boolean
}
internal class SiwcAuthHttp:SiwcAuthTransport {
    private val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    private fun read(request:Request):ByteArray=client.newCall(request).execute().use {r->
        val out=java.io.ByteArrayOutputStream();r.body?.byteStream()?.use{input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;check(out.size()+n<=131072) { "SIWC_AUTH_RESPONSE_SIZE" };out.write(b,0,n)}}
        if(r.code!=200) {
            val code=runCatching{AstraAudioContract.obj(StrictJson.readOnlyArtifact(out.toByteArray()))["error"] as? String}.getOrNull()
            val known=setOf("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired","refresh_token_invalidated","refresh_token_reused","invalid_client")
            throw SiwcOAuthProtocol.Failure(if(code in known)"SIWC_OAUTH_"+code!!.uppercase() else if(r.code>=500)"SIWC_OAUTH_TEMPORARY" else "SIWC_OAUTH_HTTP_HELD")
        };out.toByteArray()
    }
    override fun metadata()=SiwcOAuthProtocol.metadata(read(Request.Builder().url(SiwcOAuthProtocol.DISCOVERY).build()))
    override fun jwks(metadata:SiwcOAuthProtocol.Metadata)=read(Request.Builder().url(metadata.jwksUri).build())
    override fun token(form:Map<String,String>):ByteArray {
        SiwcOAuthProtocol.clientId(checkNotNull(form["client_id"]))
        check(form["grant_type"] in setOf("authorization_code","refresh_token") && "client_secret" !in form && form["resource"]==SiwcOAuthProtocol.RESOURCE) { "SIWC_OAUTH_FORM" }
        val body=FormBody.Builder().apply{form.forEach{(k,v)->add(k,v)}}.build()
        return read(Request.Builder().url(SiwcOAuthProtocol.TOKEN).post(body).build())
    }
    override fun revoke(metadata:SiwcOAuthProtocol.Metadata,clientId:String,refreshToken:String):Boolean {
        SiwcOAuthProtocol.clientId(clientId)
        val form=FormBody.Builder().add("token",refreshToken).add("token_type_hint","refresh_token").add("client_id",clientId).build()
        read(Request.Builder().url(metadata.revocationEndpoint).post(form).build());return true
    }
}
