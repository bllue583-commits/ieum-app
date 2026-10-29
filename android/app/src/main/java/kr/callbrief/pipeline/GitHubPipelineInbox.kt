package kr.callbrief.pipeline

import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Minimal HTTP seam so host tests can fake GitHub. Implementations never log headers or bodies. */
internal interface GitHubPipelineHttp {
    class Response(val code:Int,val body:ByteArray)
    /** Network failure = IOException. The exception message must not contain headers, URL query or body. */
    fun execute(method:String,url:String,headers:Map<String,String>,body:ByteArray?):Response
}

internal class GitHubPipelineOkHttp(private val client:OkHttpClient=client()):GitHubPipelineHttp {
    override fun execute(method:String,url:String,headers:Map<String,String>,body:ByteArray?):GitHubPipelineHttp.Response {
        val builder=Request.Builder().url(url)
        headers.forEach{(k,v)->builder.header(k,v)}
        builder.method(method,body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        try {
            client.newCall(builder.build()).execute().use { r ->
                val bytes=r.body?.let{b->
                    val limit=PipelineContract.DOC_LIMIT*4L
                    val length=b.contentLength();if(length>limit)throw IOException("INBOX_RESPONSE_TOO_LARGE")
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    b.byteStream().use{input->while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>limit)throw IOException("INBOX_RESPONSE_TOO_LARGE");out.write(buffer,0,n)}}
                    out.toByteArray()
                } ?: ByteArray(0)
                return GitHubPipelineHttp.Response(r.code,bytes)
            }
        } catch(e:IOException) {
            // OkHttp messages can carry the host; never the token. Replace with a stable code anyway.
            throw IOException(if(e.message?.startsWith("INBOX_")==true)e.message else "INBOX_NETWORK")
        }
    }
    companion object {
        fun client():OkHttpClient=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()
    }
}

/** docs/contracts inbox over the GitHub contents API. The token is read per call and never logged,
 * stored in exceptions, or written anywhere else. */
internal class GitHubPipelineInbox(private val repository:String,private val token:()->String?,
    private val http:GitHubPipelineHttp=GitHubPipelineOkHttp(),private val apiBase:String="https://api.github.com"):PipelineInbox {
    init { require(REPOSITORY.matches(repository)) { "INBOX_REPOSITORY_INVALID" } }
    private fun url(path:String):String {
        PipelineContract.require(PATH.matches(path) && path.split('/').none{it.isEmpty()||it=="."||it==".."},"INBOX_PATH_INVALID")
        return "$apiBase/repos/$repository/contents/$path"
    }
    private fun headers(accept:String):Map<String,String> {
        val t=token()?.trim();if(t.isNullOrEmpty())throw PipelineContractException("INBOX_TOKEN_MISSING")
        PipelineContract.require(t.length<=512 && t.none{it.isWhitespace()||it.code<33||it.code>126},"INBOX_TOKEN_INVALID")
        return linkedMapOf("Authorization" to "Bearer $t","Accept" to accept,"X-GitHub-Api-Version" to "2022-11-28","User-Agent" to "ieum-android-pipeline")
    }
    private fun failure(code:Int):Nothing=when(code) {
        401,403->throw IOException("INBOX_AUTH_$code")
        else->throw IOException("INBOX_HTTP_$code")
    }
    override fun get(path:String):ByteArray? {
        val r=http.execute("GET",url(path),headers(RAW),null)
        return when(r.code) { 200->r.body;404->null;else->failure(r.code) }
    }
    override fun putOnce(path:String,bytes:ByteArray,message:String) {
        PipelineContract.require(bytes.isNotEmpty() && bytes.size<=PipelineContract.DOC_LIMIT*2,"INBOX_SIZE")
        val target=url(path)
        val body=JSONObject().put("message",message.take(200)).put("content",Base64.getEncoder().encodeToString(bytes)).toString().toByteArray(Charsets.UTF_8)
        val r=http.execute("PUT",target,headers(JSON),body)
        when(r.code) {
            200,201->return
            409,422->{ // Already exists (no sha supplied) or concurrent create: compare exact bytes.
                val existing=get(path) ?: failure(r.code)
                if(existing.contentEquals(bytes))return
                throw PipelineContractException("INBOX_CONFLICT")
            }
            else->failure(r.code)
        }
    }
    override fun list(directory:String):List<String> {
        val r=http.execute("GET",url(directory),headers(JSON),null)
        if(r.code==404)return emptyList()
        if(r.code!=200)failure(r.code)
        val array=try{JSONArray(String(r.body,Charsets.UTF_8))}catch(e:org.json.JSONException){throw PipelineContractException("INBOX_LISTING_INVALID")}
        return (0 until array.length()).map{array.getJSONObject(it)}.filter{it.optString("type")=="file"}.map{it.getString("name")}.sorted()
    }
    companion object {
        const val RAW="application/vnd.github.raw+json"
        const val JSON="application/vnd.github+json"
        private val REPOSITORY=Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
        private val PATH=Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
    }
}
