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
    /** Writes [path] whether or not it exists (put leaves an existing file alone). */
    fun replace(path:String,json:String) { put(path,json) }
    /** Large binary file (the update APK) streamed to [to]. */
    fun download(path:String,to:java.io.File) { to.writeText(get(path)) }
}

internal class GitHubInboxStore(private val repo:String,private val token:String,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).build()):CallInboxStore {
    private val json="application/json".toMediaType()
    private fun request(path:String)=Request.Builder().url("https://api.github.com/repos/$repo/contents/$path")
        .header("Authorization","Bearer $token").header("X-GitHub-Api-Version","2022-11-28")
    private fun call(r:Request):Pair<Int,String> = client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
    // 409: the repository moved on between read and write (the task pushed): try again next run.
    private fun ok(code:Int,body:String) { if(code !in 200..299) throw if(code>=500||code==429||code==409||code==403&&body.contains("rate limit")) IOException("INBOX_HTTP_$code") else ClaudeCallException("INBOX_HTTP_$code") }

    override fun put(path:String,json:String) {
        val body=JSONObject().put("message","inbox").put("content",Base64.encodeToString(json.toByteArray(),Base64.NO_WRAP))
        val (code,text)=call(request(path).put(body.toString().toRequestBody(this.json)).build())
        if(code==422)return // already there (re-send after a lost reply)
        ok(code,text)
    }
    override fun replace(path:String,json:String) {
        val (code,text)=call(request(path).get().build())
        val sha=if(code==200) JSONObject(text).optString("sha").takeIf { it.isNotEmpty() } else null
        if(code!=200 && code!=404) ok(code,text)
        val body=JSONObject().put("message","state").put("content",Base64.encodeToString(json.toByteArray(),Base64.NO_WRAP)).apply { sha?.let { put("sha",it) } }
        val (c2,t2)=call(request(path).put(body.toString().toRequestBody(this.json)).build())
        ok(c2,t2)
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
    const val INBOX="inbox";const val RESULTS="results";const val BAD="bad"
    /** First-pass batches (처음 정리): "b-<time of the first record>-<hash>.json", so name order is time order. */
    const val OLD="b-"
    /** The repository ledger the task keeps, and the user's corrections waiting for it (design: 장부의 원본). */
    const val LEDGER="ledger/ledger.json";const val EDITS="ledger/edits"
    /** The morning tidy (design: 새벽 정리): what the phone sends it once a night, and what it proposes back. */
    const val TIDY_STATE="tidy/state.json";const val TIDY_PROPOSAL="tidy/proposal.json"
    /** A record as the task and the ledger know it: a hash of its id, so no number or name shows. */
    fun recordKey(recordId:String)="r_"+fileName(recordId).take(24)
    fun batchName(firstMs:Long,recordIds:List<String>)=OLD+"%013d-".format(firstMs)+fileName(recordIds.joinToString("|")).removeSuffix(".json").take(16)+".json"
    /** Repository-safe file name for a record id (no phone numbers or names in paths). */
    fun fileName(recordId:String)=MessageDigest.getInstance("SHA-256").digest(recordId.toByteArray()).joinToString(""){"%02x".format(it)}.take(32)+".json"
    /** While the scheduled task's prompt still reads its rules from each request (it can only be replaced with the user's approval),
     * v3 requests also carry the same rules and answer format, so the current task and the new docs/routines/hourly.md give the
     * same result. Set to false once the task runs hourly.md (design C1, review B6). */
    const val CARRY_RULES=true
    private fun rules(o:JSONObject):JSONObject = if(!CARRY_RULES) o else o.put("system",ClaudeCallAnalyzer.SYSTEM).put("schema",ClaudeCallAnalyzer.SCHEMA)
    /** Request format v3 (design C1): the rules and the answer format live in the routine (docs/routines/hourly.md), not in
     * the request; the request says which version of them it was made for ([ClaudeCallAnalyzer.PROMPT_VERSION]).
     * [key] is the record key ([recordKey]); the task answers with the same id. */
    fun job(key:String,request:ClaudeCallRequest,now:Long,atMs:Long=0):String=rules(JSONObject().put("format",ClaudeCallAnalyzer.FORMAT))
        .put("id",key).put("recordKey",key).put("createdMs",now).put("atMs",atMs)
        .put("counterpartId",request.counterpartId ?: JSONObject.NULL).put("promptVersion",ClaudeCallAnalyzer.PROMPT_VERSION)
        .put("message",ClaudeCallAnalyzer.userMessage(request)).toString()
    /** Several old records in time order; the task analyses them one after another, carrying the ledger along, and
     * answers with {"id": ..., "results": [{"id": record, "analysis": {...}}]}. */
    fun batch(items:List<Pair<String,ClaudeCallRequest>>,now:Long,atMs:List<Long> =emptyList()):String=rules(JSONObject().put("format",ClaudeCallAnalyzer.FORMAT))
        .put("id",FirstPass.BATCH_PREFIX+fileName(items.joinToString("|") { it.first }).removeSuffix(".json"))
        .put("createdMs",now).put("promptVersion",ClaudeCallAnalyzer.PROMPT_VERSION)
        .put("items",JSONArray().apply { items.forEachIndexed { n,(id,r) -> put(JSONObject().put("id",id).put("recordKey",id).put("createdMs",now).put("atMs",atMs.getOrElse(n) { 0L })
            .put("counterpartId",r.counterpartId ?: JSONObject.NULL).put("promptVersion",ClaudeCallAnalyzer.PROMPT_VERSION).put("message",ClaudeCallAnalyzer.userMessage(r))) } }).toString()
    /** Result file: {"id": ..., "analysis": {...}} where analysis matches the schema. */
    fun parseResult(text:String):Pair<String,ClaudeCallAnalysis> { val o=JSONObject(text);return o.getString("id") to ClaudeCallAnalysis.parse(o.getJSONObject("analysis")) }
    /** One result file: a single record, or a batch's list. Broken entries are left out. */
    fun parseResults(text:String):List<Pair<String,ClaudeCallAnalysis>> {
        val o=JSONObject(text)
        val list=o.optJSONArray("results") ?: return listOf(parseResult(text))
        return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { runCatching { it.getString("id") to ClaudeCallAnalysis.parse(it.getJSONObject("analysis")) }.getOrNull() } }
    }
}
