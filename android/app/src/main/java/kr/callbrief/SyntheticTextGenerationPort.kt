package kr.callbrief

import kr.callagent.drive.StrictJson
import java.util.concurrent.atomic.AtomicLong

/** Authenticated channel contract only. There is no visitor-authenticated Android
 * implementation in the approved source. Never use legacy Bearer, browser cookies,
 * Drive identity or an authenticated-user header to fill this seam. */
internal interface SyntheticTextMcpChannel {
    val origin:String
    fun binding():SyntheticTextConnectionScope?
    suspend fun exchange(request:ByteArray):ByteArray
}
internal interface SyntheticTextGenerationPort {
    fun binding():SyntheticTextConnectionScope?
    suspend fun call(tool:String,args:Map<String,Any?>):Map<String,Any?>
}
internal class UnavailableNativeSyntheticTextPort:SyntheticTextGenerationPort {
    override fun binding():SyntheticTextConnectionScope?=null
    override suspend fun call(tool:String,args:Map<String,Any?>):Map<String,Any?> = throw SyntheticTextFailure("native_return_unsupported")
}
internal object SyntheticTextMcpResponseMapper {
    private fun reason(value:Any?):String {
        val code=runCatching { SyntheticTextContract.obj(value)["reason"] as? String }.getOrNull()
        return code?.takeIf { it in setOf("missing_identity","access_revoked","job_expired","not_found","revision_conflict","result_conflict","storage_unavailable","storage_integrity_error","invalid_arguments","unknown_tool","unsupported_protocol","protocol_header_mismatch") } ?: "unknown_response"
    }
    // MCP error codes are signed integers. The shared read-only decoder keeps
    // integer tokens distinct from decimals/exponents; convert only safe integer
    // tokens here, without changing the original Drive parser or result bytes.
    private fun integers(value:Any?):Any? = when(value) {
        is java.math.BigInteger -> { require(value.abs()<=java.math.BigInteger.valueOf(StrictJson.MAX_INTEGER));value.toLong() }
        is java.math.BigDecimal -> throw IllegalArgumentException("INTEGER_TOKEN_REQUIRED")
        is Map<*,*> -> value.entries.associate { (key,child) -> (key as String) to integers(child) }
        is List<*> -> value.map(::integers)
        else -> value
    }
    fun read(bytes:ByteArray,id:Long):Map<String,Any?> {
        require(bytes.size<=65_536);val p=SyntheticTextContract.obj(integers(StrictJson.readOnlyArtifact(bytes)));require(p["jsonrpc"]=="2.0" && p["id"]==id)
        if(p.containsKey("error")) { require(!p.containsKey("result"));throw SyntheticTextFailure(reason(SyntheticTextContract.obj(p["error"])["data"])) }
        val outer=SyntheticTextContract.obj(p["result"]);require(outer["resultType"]=="complete" && outer["isError"] is Boolean)
        if(outer["isError"]==true)throw SyntheticTextFailure(reason(runCatching { SyntheticTextContract.obj(outer["structuredContent"])["error"] }.getOrNull()))
        // The textual content is explanatory data. Missing structured content is not
        // repaired by parsing text, latest-generation lookup, or a legacy endpoint.
        return SyntheticTextContract.obj(outer["structuredContent"])
    }
}
internal class McpSyntheticTextGenerationPort(private val channel:SyntheticTextMcpChannel):SyntheticTextGenerationPort {
    private val ids=AtomicLong()
    override fun binding():SyntheticTextConnectionScope?=channel.binding()?.also { scope ->
        require(channel.origin==if(scope.origin==SyntheticTextOrigin.LOCAL_CONTRACT_FIXTURE)SyntheticTextContract.LOCAL_ORIGIN else SyntheticTextContract.SITE)
    }
    override suspend fun call(tool:String,args:Map<String,Any?>):Map<String,Any?> {
        val scope=binding() ?: throw SyntheticTextFailure("missing_identity")
        val allowed=when(tool) { "start_demo_generation"->setOf("sample_id","proof_request_id");"get_demo_job","get_demo_result"->setOf("job_id","generation_id");"save_demo_result"->setOf("job_id","generation_id","expected_revision","result_id","result");else->throw SyntheticTextFailure("unknown_tool") }
        SyntheticTextContract.exact(args,allowed)
        if(tool=="start_demo_generation") { require(args["sample_id"]==SyntheticTextContract.SAMPLE);SyntheticTextContract.uuid(args["proof_request_id"] as String) }
        else { require(args["job_id"]==SyntheticTextContract.JOB);SyntheticTextContract.uuid(args["generation_id"] as String) }
        val id=ids.incrementAndGet();val request=StrictJson.bytes(mapOf("jsonrpc" to "2.0","id" to id,"method" to "tools/call","params" to mapOf("name" to tool,"arguments" to args,"_meta" to mapOf("io.modelcontextprotocol/protocolVersion" to SyntheticTextContract.PROTOCOL,"io.modelcontextprotocol/clientCapabilities" to emptyMap<String,Any>()))))
        require(request.size<=16_384)
        val response=channel.exchange(request);if(binding()!=scope)throw SyntheticTextFailure("scope_changed")
        return SyntheticTextMcpResponseMapper.read(response,id)
    }
}
