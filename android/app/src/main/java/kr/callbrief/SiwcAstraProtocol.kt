package kr.callbrief

import kr.callagent.drive.StrictJson
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Official SIWC text inference only. No audio/Files/transcription endpoint or backend-api fallback.
 * Registration/OIDC/plan consent is owned by a future approved Android auth adapter. */
internal object SiwcAstraProtocol {
    const val ORIGIN="https://api.openai.com/v1"
    enum class Stage { ANALYSIS, CONTEXT }
    class Invocation(val scope:String,val stage:Stage,val model:String,val effort:String,
        val sourceManifestSha:String,input:Map<String,Any?>,val instructions:String,val attempt:Int=1,val priorAttempt:String?=null) {
        private val frozenInput=AstraAudioContract.obj(StrictJson.parseReadOnlyEnvelope(StrictJson.bytes(input)))
        init {
            require(scope.isNotBlank() && instructions.isNotBlank());AstraAudioContract.hash(sourceManifestSha)
            require(attempt in 1..100 && (attempt==1)==(priorAttempt==null));priorAttempt?.let(AstraAudioContract::hash)
            require(model.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && effort in setOf("low","medium","high","xhigh","max"))
            if(stage==Stage.ANALYSIS)require(model==AstraAudioContract.MODEL && effort==AstraAudioContract.EFFORT)
            if(stage==Stage.CONTEXT) {
                AstraAudioContract.hash(input["analysisOutputSha256"] as String)
                AstraAudioContract.hash(input["approvedSnapshotSha256"] as String)
                check(AstraAudioContract.sha(StrictJson.bytes(input["analysis"]))==input["analysisOutputSha256"]) { "SIWC_ANALYSIS_BINDING" }
                check(AstraAudioContract.sha(StrictJson.bytes(input["approvedSnapshot"]))==input["approvedSnapshotSha256"]) { "SIWC_SNAPSHOT_BINDING" }
            }
        }
        fun body()=StrictJson.bytes(mapOf("model" to model,"reasoning" to mapOf("effort" to effort),"store" to false,"stream" to true,
            "instructions" to instructions,"input" to listOf(mapOf("role" to "user","content" to StrictJson.stringify(frozenInput)))))
        fun key()=AstraAudioContract.sha(StrictJson.bytes(listOf(scope,stage.name,sourceManifestSha,AstraAudioContract.sha(body()),attempt.toLong(),priorAttempt)))
        fun nextAttempt()=Invocation(scope,stage,model,effort,sourceManifestSha,frozenInput,instructions,attempt+1,key())
        fun requireContextOf(analysis:Invocation,output:String) {
            check(stage==Stage.CONTEXT && analysis.stage==Stage.ANALYSIS && scope==analysis.scope && sourceManifestSha==analysis.sourceManifestSha &&
                frozenInput["analysisJobId"]==analysis.key() && frozenInput["analysisOutputSha256"]==AstraAudioContract.sha(StrictJson.bytes(StrictJson.parse(output.toByteArray(Charsets.UTF_8))))) { "SIWC_CONTEXT_JOB_BINDING" }
        }
        override fun toString()="SiwcInvocation[withheld]"
    }
    data class Completion(val responseId:String,val actualModel:String,val effectiveEffort:String,val output:String,val rawSha256:String)
    class Failure(val code:String):IllegalStateException(code)
    fun catalog(bytes:ByteArray):List<String> = AstraAudioContract.list(AstraAudioContract.obj(StrictJson.readOnlyArtifact(bytes)),"models",300)
        .map(AstraAudioContract::obj).filter{it["visibility"]=="list"}.map{AstraAudioContract.text(it,"slug")}.distinct()
    /** Deltas are never a completed result. Bound total bytes/events, strict UTF-8, consume terminal event. */
    fun completed(stream:InputStream,expected:Invocation,stopped:()->Boolean={false}):Completion {
        val wire=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {check(!stopped()) { "SIWC_LOCALLY_CANCELLED_REMOTE_UNKNOWN" };val n=stream.read(buffer);if(n<0)break
            check(wire.size()+n<=2*1024*1024) { "SIWC_RESPONSE_SIZE" };wire.write(buffer,0,n)}
        val bytes=wire.toByteArray();val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        var done:Completion?=null;var data=mutableListOf<String>();var events=0
        fun event() {
            if(data.isEmpty())return
            val raw=data.joinToString("\n");data=mutableListOf();if(raw=="[DONE]")return
            require(++events<=4096) { "SIWC_RESPONSE_SIZE" }
            val e=AstraAudioContract.obj(StrictJson.readOnlyArtifact(raw.toByteArray(Charsets.UTF_8)))
            when(e["type"]) {
                "response.failed","response.incomplete","error" -> {
                    val response=(e["response"] as? Map<*,*>) ?: e
                    val error=response["error"] as? Map<*,*>
                    val code=error?.get("code") as? String
                    throw Failure(when(code) {"subscription_sharing_usage_limit_exceeded","subscription_sharing_usage_unavailable"->"SIWC_PLAN_USAGE_HELD";else->"SIWC_RESPONSE_FAILED_OR_INCOMPLETE"})
                }
                "response.completed" -> {
                    check(done==null) { "SIWC_DUPLICATE_TERMINAL" }
                    val r=AstraAudioContract.obj(e["response"])
                    check(r["status"]=="completed" && r["error"]==null && r["incomplete_details"]==null) { "SIWC_RESPONSE_NOT_COMPLETE" }
                    val actual=AstraAudioContract.text(r,"model");check(actual==expected.model) { "SIWC_ACTUAL_MODEL_MISMATCH" }
                    val reasoning=r["reasoning"] as? Map<*,*>
                    val effort=reasoning?.get("effort") as? String ?: "NOT_REPORTED"
                    check(effort=="NOT_REPORTED" || effort==expected.effort) { "SIWC_EFFECTIVE_EFFORT_MISMATCH" }
                    val output=StringBuilder()
                    for(item in AstraAudioContract.list(r,"output",100)) {
                        val x=AstraAudioContract.obj(item)
                        if(x["type"]=="message") {
                            check(x["role"]=="assistant" && x["status"]=="completed") { "SIWC_OUTPUT_NOT_COMPLETE" }
                            for(content in AstraAudioContract.list(x,"content",100)) {
                                val c=AstraAudioContract.obj(content);check(c["type"]=="output_text") { "SIWC_REFUSAL_OR_NON_TEXT" }
                                output.append(AstraAudioContract.text(c,"text",262144));check(output.length<=262144) { "SIWC_OUTPUT_SIZE" }
                            }
                        } else check(x["type"]=="reasoning") { "SIWC_UNHANDLED_TOOL_OUTPUT" }
                    }
                    check(output.isNotBlank()) { "SIWC_EMPTY_OUTPUT" }
                    done=Completion(AstraAudioContract.text(r,"id"),actual,effort,output.toString(),AstraAudioContract.sha(bytes))
                }
            }
        }
        for(line in text.replace("\r\n","\n").split('\n')) {
            if(line.isEmpty())event() else if(line.startsWith("data:"))data.add(line.substring(5).removePrefix(" "))
        }
        // A stream truncated before its event delimiter is not an observed terminal event.
        check(data.isEmpty() && !stopped()) { "SIWC_STREAM_INTERRUPTED" }
        return done ?: throw Failure("SIWC_COMPLETION_UNKNOWN")
    }
}

/** An app-owned, validated OAuth grant, never populated from a model/result/intent or desktop session.
 * No production constructor/caller is installed until Private client admission + Android callback QA. */
internal class SiwcRuntimeGrant(val scope:String,val accessToken:String,val expiresMs:Long,
    val planUsageApproved:Boolean,approvedInvocationKeys:Set<String>) {
    val approvedInvocationKeys=approvedInvocationKeys.toSet()
    override fun toString()="SiwcRuntimeGrant[credential withheld]"
}
internal interface SiwcInferencePort {
    fun models(scope:String):List<String>
    fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion
}
internal class SiwcAstraHttp(private val grant:()->SiwcRuntimeGrant?,private val now:()->Long=System::currentTimeMillis):SiwcInferencePort {
    private val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).build()
    private fun approved(scope:String,invocationKey:String?=null):SiwcRuntimeGrant {
        val g=grant() ?: error("SIWC_REGISTRATION_AND_GRANT_REQUIRED")
        check(g.scope==scope && g.expiresMs>now() && g.planUsageApproved && (invocationKey==null || invocationKey in g.approvedInvocationKeys)) { "SIWC_CURRENT_APPROVED_GRANT_REQUIRED" }
        check(g.accessToken.length>=32 && g.accessToken.all{it.code in 33..126}) { "SIWC_TOKEN_INVALID" };return g
    }
    private fun status(code:Int) {
        when(code) {200->Unit;401->throw SiwcAstraProtocol.Failure("SIWC_REAUTH_REQUIRED");403->throw SiwcAstraProtocol.Failure("SIWC_ELIGIBILITY_HELD");429->throw SiwcAstraProtocol.Failure("SIWC_PLAN_USAGE_HELD");else->throw SiwcAstraProtocol.Failure("SIWC_HTTP_HELD")}
    }
    override fun models(scope:String):List<String> {
        val g=approved(scope);val request=Request.Builder().url(SiwcAstraProtocol.ORIGIN+"/models").header("Authorization","Bearer "+g.accessToken).build()
        return client.newCall(request).execute().use { response ->
            status(response.code);val input=checkNotNull(response.body).byteStream();val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            input.use{while(true){val n=it.read(buffer);if(n<0)break;check(out.size()+n<=262144){"SIWC_RESPONSE_SIZE"};out.write(buffer,0,n)}}
            check(approved(scope)===g) { "SIWC_ACCOUNT_CHANGED" }
            SiwcAstraProtocol.catalog(out.toByteArray())
        }
    }
    override fun invoke(request:SiwcAstraProtocol.Invocation,stopped:()->Boolean):SiwcAstraProtocol.Completion {
        val g=approved(request.scope,request.key());check(!stopped()) { "SIWC_LOCALLY_CANCELLED_REMOTE_UNKNOWN" }
        val http=Request.Builder().url(SiwcAstraProtocol.ORIGIN+"/responses").header("Authorization","Bearer "+g.accessToken)
            .header("Accept","text/event-stream").post(request.body().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call=client.newCall(http)
        return call.execute().use{response->status(response.code)
            check(response.header("Content-Type").orEmpty().substringBefore(';').trim()=="text/event-stream") { "SIWC_SSE_REQUIRED" }
            checkNotNull(response.body).byteStream().use { input ->
                SiwcAstraProtocol.completed(input,request) { val cancelled=stopped() || runCatching{approved(request.scope,request.key())===g}.getOrDefault(false).not();if(cancelled)call.cancel();cancelled }
            }
        }
    }
}
