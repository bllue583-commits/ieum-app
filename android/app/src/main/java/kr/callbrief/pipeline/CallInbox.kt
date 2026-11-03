package kr.callbrief.pipeline

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Subscription mode: the phone drops each transcript (as a ready-to-run analysis request) into a private
 * GitHub repository; a scheduled Claude task analyzes it and leaves a result file; the phone collects it.
 * Only transcript text travels; audio never leaves the phone. */
internal interface CallInboxStore {
    fun put(path:String,json:String)
    /** File names under [dir] with their blob sha; empty when the folder does not exist yet. */
    fun list(dir:String):List<Pair<String,String>>
    fun get(path:String):String
    fun delete(path:String,sha:String)
    /** Large binary file (the update APK) streamed to [to]. */
    fun download(path:String,to:java.io.File) { to.writeText(get(path)) }
}

internal class GitHubInboxStore(private val repo:String,private val token:String,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).build()):CallInboxStore {
    private val json="application/json".toMediaType()
    private fun request(path:String)=Request.Builder().url("https://api.github.com/repos/$repo/contents/$path")
        .header("Authorization","Bearer $token").header("X-GitHub-Api-Version","2022-11-28")
    private fun call(r:Request):Pair<Int,String> = client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
    private fun ok(code:Int,body:String) { if(code !in 200..299) throw if(code>=500||code==429||code==403&&body.contains("rate limit")) IOException("INBOX_HTTP_$code") else ClaudeCallException("INBOX_HTTP_$code") }

    override fun put(path:String,json:String) {
        val body=JSONObject().put("message","inbox").put("content",Base64.encodeToString(json.toByteArray(),Base64.NO_WRAP))
        val (code,text)=call(request(path).put(body.toString().toRequestBody(this.json)).build())
        if(code==422)return // already there (re-send after a lost reply)
        ok(code,text)
    }
    override fun list(dir:String):List<Pair<String,String>> {
        val (code,text)=call(request(dir).get().build())
        if(code==404)return emptyList()
        ok(code,text)
        val a=JSONArray(text);return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type")=="file" }.map { it.getString("name") to it.getString("sha") }
    }
    override fun get(path:String):String {
        val (code,text)=call(request(path).header("Accept","application/vnd.github.raw+json").get().build())
        ok(code,text);return text
    }
    override fun download(path:String,to:java.io.File) {
        val big=client.newBuilder().readTimeout(5,TimeUnit.MINUTES).build()
        // Release assets (the update APK) redirect to storage; OkHttp drops the token on the cross-host hop.
        val r0=if(path.startsWith("releases/assets/")) Request.Builder().url("https://api.github.com/repos/$repo/$path")
            .header("Authorization","Bearer $token").header("Accept","application/octet-stream")
            else request(path).header("Accept","application/vnd.github.raw+json")
        big.newCall(r0.get().build()).execute().use { r ->
            ok(r.code,if(r.isSuccessful) "" else r.body?.string() ?: "")
            r.body!!.byteStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        }
    }
    override fun delete(path:String,sha:String) {
        val body=JSONObject().put("message","done").put("sha",sha)
        val (code,text)=call(request(path).delete(body.toString().toRequestBody(json)).build())
        if(code!=404)ok(code,text)
    }
}

internal object CallInbox {
    const val INBOX="inbox";const val RESULTS="results"
    /** First-pass batches (처음 정리): "b-<time of the first record>-<hash>.json", so name order is time order. */
    const val OLD="b-"
    /** The repository ledger the task keeps, and the user's corrections waiting for it (design: 장부의 원본). */
    const val LEDGER="ledger/ledger.json";const val EDITS="ledger/edits"
    fun batchName(firstMs:Long,recordIds:List<String>)=OLD+"%013d-".format(firstMs)+fileName(recordIds.joinToString("|")).removeSuffix(".json").take(16)+".json"
    /** Repository-safe file name for a record id (no phone numbers or names in paths). */
    fun fileName(recordId:String)=MessageDigest.getInstance("SHA-256").digest(recordId.toByteArray()).joinToString(""){"%02x".format(it)}.take(32)+".json"
    /** Self-contained job: the scheduled task follows `system`, reads `message`, answers with JSON matching `schema`. */
    fun job(recordId:String,request:ClaudeCallRequest,now:Long):String=JSONObject().put("id",recordId).put("createdMs",now)
        .put("counterpartId",request.counterpartId ?: JSONObject.NULL)
        .put("system",ClaudeCallAnalyzer.SYSTEM).put("message",ClaudeCallAnalyzer.userMessage(request)).put("schema",ClaudeCallAnalyzer.SCHEMA).toString()
    /** Several old records in time order; the task analyses them one after another, carrying the ledger along, and
     * answers with {"id": ..., "results": [{"id": record, "analysis": {...}}]}. */
    fun batch(items:List<Pair<String,ClaudeCallRequest>>,now:Long):String=JSONObject().put("id",FirstPass.BATCH_PREFIX+fileName(items.joinToString("|") { it.first }).removeSuffix(".json"))
        .put("createdMs",now).put("system",ClaudeCallAnalyzer.SYSTEM).put("schema",ClaudeCallAnalyzer.SCHEMA)
        .put("items",JSONArray().apply { items.forEach { (id,r) -> put(JSONObject().put("id",id).put("counterpartId",r.counterpartId ?: JSONObject.NULL).put("message",ClaudeCallAnalyzer.userMessage(r))) } }).toString()
    /** Result file: {"id": ..., "analysis": {...}} where analysis matches the schema. */
    fun parseResult(text:String):Pair<String,ClaudeCallAnalysis> { val o=JSONObject(text);return o.getString("id") to ClaudeCallAnalysis.parse(o.getJSONObject("analysis")) }
    /** One result file: a single record, or a batch's list. Broken entries are left out. */
    fun parseResults(text:String):List<Pair<String,ClaudeCallAnalysis>> {
        val o=JSONObject(text)
        val list=o.optJSONArray("results") ?: return listOf(parseResult(text))
        return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { runCatching { it.getString("id") to ClaudeCallAnalysis.parse(it.getJSONObject("analysis")) }.getOrNull() } }
    }
}
