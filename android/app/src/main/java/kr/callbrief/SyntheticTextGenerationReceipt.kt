package kr.callbrief

import kr.callagent.drive.StrictJson
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

internal enum class SyntheticTextOrigin { LOCAL_CONTRACT_FIXTURE, VERIFIED_SITE_ADAPTER }
/** Supplied only by a trusted channel, never tool arguments, Intent extras or Drive identity. */
internal data class SyntheticTextConnectionScope(val owner:String,val session:String,val epoch:Long,val origin:SyntheticTextOrigin,
    val project:String=SyntheticTextContract.PROJECT) {
    init { require(project==SyntheticTextContract.PROJECT && owner.isNotBlank() && owner.length<=256 && session.isNotBlank() && session.length<=256 && epoch>=0) }
    val key:String get()=SyntheticTextContract.hash(StrictJson.bytes(mapOf("project" to project,"owner" to owner,"session" to session,"epoch" to epoch,"origin" to origin.name)))
}
internal class SyntheticTextFailure(val reason:String):IllegalStateException(reason)
internal data class SyntheticTextGenerationReceipt(val generation:String,val proof:String,val createdAt:String,val expiresAt:Long,val revision:Long,val status:String) {
    fun json()=mapOf("generation" to generation,"proof" to proof,"created" to createdAt,"expires" to expiresAt,"revision" to revision,"status" to status)
}
internal object SyntheticTextContract {
    const val PROJECT="appgprj_6aca46d2dd808191a4a4faa3cd02f37c"
    const val SITE="https://call-memory-private-bridge.wavyplum.chatgpt.site"
    const val LOCAL_ORIGIN="local-fixture://ieum-sg-v1"
    const val SAMPLE="synthetic-call-a";const val JOB="synthetic-job-v1";const val QUEUE="synthetic-v1"
    const val TTL=86_400_000L;const val PROTOCOL="2026-07-28"
    const val TEXT="[합성 대화] 가상 샘플 배송은 13일로 조정할 수 있습니다. 최종 일정은 다시 확인해서 알려드리겠습니다."
    const val GOLDEN="b49c2725bb37fbc51b012fca2c9f2cd1388e435e0d12080f11d98f0d1bbec336"
    val fixture=mapOf("id" to SAMPLE,"date" to "2026-10-09","topic" to "가상 배송 업무","text" to TEXT)
    private val id=Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    fun uuid(value:String) { require(id.matches(value)) { "LOWERCASE_UUID_V4_REQUIRED" } }
    fun hash(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    fun obj(value:Any?):Map<String,Any?> = StrictJson.`object`(value)
    fun exact(value:Map<String,Any?>,keys:Set<String>) { require(value.keys==keys) { "EXACT_SHAPE_REQUIRED" } }
    fun integer(value:Map<String,Any?>,key:String):Long=(value[key] as? Long)?.takeIf { it>=0 && it<=StrictJson.MAX_INTEGER } ?: throw SyntheticTextFailure("invalid_response")
    fun string(value:Map<String,Any?>,key:String):String=value[key] as? String ?: throw SyntheticTextFailure("invalid_response")
    private fun jsSpace(c:Char)=c in '\u0009'..'\u000d' || c==' ' || c=='\u00a0' || c=='\u1680' || c in '\u2000'..'\u200a' || c=='\u2028' || c=='\u2029' || c=='\u202f' || c=='\u205f' || c=='\u3000' || c=='\ufeff'
    private fun text(v:Any?,max:Int):String=(v as? String)?.also { require(it.length<=max && it.trim(::jsSpace).isNotEmpty()) } ?: throw SyntheticTextFailure("invalid_result")
    private fun compare(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"),"")
    /** Same validated-result shape as published v4. Normalize duplicate comparison only,
     * preserve all stored characters, and downgrade done before canonical fingerprinting. */
    fun validateInput(value:Map<String,Any?>):Map<String,Any?> {
        exact(value,setOf("schema_version","demo","summary","tasks"));require(value["schema_version"]==1L && value["demo"]==true)
        text(value["summary"],1200);val tasks=value["tasks"] as? List<*> ?: error("TASK_ARRAY_REQUIRED");require(tasks.size<=10)
        val seen=mutableSetOf<String>();val checked=tasks.map { raw ->
            val t=obj(raw);exact(t,setOf("title","owner","due","status","evidence"));val title=text(t["title"],160);val owner=text(t["owner"],80)
            val status=t["status"] as? String;require(status in listOf("open","in_progress","waiting","needs_confirmation","done"))
            val due=t["due"];if(due!=null) { require(due is String && Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(due));require(LocalDate.parse(due).toString()==due) }
            val e=obj(t["evidence"]);exact(e,setOf("call_id","quote"));val quote=text(e["quote"],600);require(e["call_id"]==SAMPLE && TEXT.contains(quote))
            require(seen.add(StrictJson.stringify(listOf(compare(title),compare(owner),due)))) { "DUPLICATE_TASK" }
            t.toMutableMap().apply { put("status",if(status=="done")"needs_confirmation" else status) }
        }
        return value.toMutableMap().apply { put("tasks",checked);put("applied",false) }.also { StrictJson.bytes(it) }
    }
    fun validatedReadback(value:Any?):Map<String,Any?> {
        val result=obj(value);exact(result,setOf("schema_version","demo","summary","tasks","applied"));require(result["applied"]==false)
        val checked=validateInput(result.filterKeys { it!="applied" });require(StrictJson.stringify(result)==StrictJson.stringify(checked)) { "UNVALIDATED_RESULT" };return checked
    }
    fun fingerprint(value:Map<String,Any?>)=hash(StrictJson.bytes(value))
    fun draft():Map<String,Any?> = mapOf("schema_version" to 1L,"demo" to true,
        "summary" to "가상 샘플 배송을 13일로 조정할 수 있으나 최종 일정은 미확정이며, 확인 후 안내하기로 했습니다.",
        "tasks" to listOf(mapOf("title" to "가상 샘플 배송 최종 일정 확인 후 안내","owner" to "미확인","due" to null,"status" to "open",
            "evidence" to mapOf("call_id" to SAMPLE,"quote" to "최종 일정은 다시 확인해서 알려드리겠습니다."))))
    fun start(value:Map<String,Any?>,proof:String):SyntheticTextGenerationReceipt {
        exact(value,setOf("job_id","generation_id","proof_request_id","revision","status","created_at","expires_at","duplicate","demo"))
        require(value["job_id"]==JOB && value["proof_request_id"]==proof && value["demo"]==true && value["duplicate"] is Boolean);uuid(proof)
        val generation=string(value,"generation_id");uuid(generation);val created=string(value,"created_at");val expires=integer(value,"expires_at")
        val revision=integer(value,"revision");val status=string(value,"status");require(revision==0L&&status=="queued" || revision==1L&&status=="result_ready"&&value["duplicate"]==true)
        return validatedReceipt(SyntheticTextGenerationReceipt(generation,proof,created,expires,revision,status))
    }
    private fun validatedReceipt(receipt:SyntheticTextGenerationReceipt):SyntheticTextGenerationReceipt {
        uuid(receipt.generation);uuid(receipt.proof)
        val instant=Instant.parse(receipt.createdAt);val created=instant.toEpochMilli()
        require(created in 0..8_640_000_000_000_000L && instant.nano%1_000_000==0)
        val iso=DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant)
        val canonical=if(iso.startsWith("+")) { val end=iso.indexOf('-');"+"+iso.substring(1,end).padStart(6,'0')+iso.substring(end) } else iso
        // Published v4 requires Date(created).toISOString() equality, not a
        // normalized or truncated alternative representation of the instant.
        require(receipt.createdAt==canonical && receipt.expiresAt in 0..StrictJson.MAX_INTEGER && receipt.expiresAt-created==TTL)
        require(receipt.revision==0L&&receipt.status=="queued" || receipt.revision==1L&&receipt.status=="result_ready")
        return receipt
    }
    /** Reopen uses the same identity/time/state rules as a fresh wire admission. */
    fun restored(value:Map<String,Any?>):SyntheticTextGenerationReceipt {
        exact(value,setOf("generation","proof","created","expires","revision","status"))
        return validatedReceipt(SyntheticTextGenerationReceipt(string(value,"generation"),string(value,"proof"),string(value,"created"),integer(value,"expires"),integer(value,"revision"),string(value,"status")))
    }
    fun getJob(value:Map<String,Any?>,r:SyntheticTextGenerationReceipt) {
        exact(value,setOf("job_id","queue_id","sample_id","generation_id","proof_request_id","created_at","expires_at","revision","status","result","result_id","result_hash","demo","fixture","existing_checklist","note"))
        require(value["job_id"]==JOB && value["queue_id"]==QUEUE && value["sample_id"]==SAMPLE && value["generation_id"]==r.generation && value["proof_request_id"]==r.proof && value["created_at"]==r.createdAt && value["expires_at"]==r.expiresAt && value["demo"]==true)
        require(obj(value["fixture"])==fixture && value["existing_checklist"]==emptyList<Any>() && value["note"] is String)
        state(value,r)
    }
    private fun state(value:Map<String,Any?>,r:SyntheticTextGenerationReceipt) {
        val revision=integer(value,"revision");val status=string(value,"status");require(revision>=r.revision)
        require(revision==0L&&status=="queued"&&listOf("result","result_id","result_hash").all { value[it]==null } || revision==1L&&status=="result_ready"&&listOf("result","result_id","result_hash").all { value[it]!=null })
    }
    fun result(value:Map<String,Any?>,r:SyntheticTextGenerationReceipt,id:String,hash:String):Map<String,Any?>? {
        exact(value,setOf("job_id","generation_id","revision","status","expires_at","result","result_id","result_hash","demo"))
        require(value["job_id"]==JOB && value["generation_id"]==r.generation && value["expires_at"]==r.expiresAt && value["demo"]==true);state(value,r)
        if(integer(value,"revision")==0L)return null
        val receivedId=string(value,"result_id");GenerationResultId.requireFor(r.generation,receivedId);if(receivedId!=id)throw SyntheticTextFailure("result_conflict")
        val result=validatedReadback(value["result"]);val actual=fingerprint(result);if(actual!=value["result_hash"])throw SyntheticTextFailure("storage_integrity_error");if(actual!=hash)throw SyntheticTextFailure("result_conflict");return result
    }
    fun saveAck(value:Map<String,Any?>,r:SyntheticTextGenerationReceipt) {
        exact(value,setOf("job_id","generation_id","revision","status","duplicate","demo"));require(value["job_id"]==JOB && value["generation_id"]==r.generation && value["revision"]==1L && value["status"]=="result_ready" && value["duplicate"] is Boolean && value["demo"]==true)
    }
}
