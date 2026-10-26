package kr.callbrief

import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import org.json.JSONArray
import org.json.JSONObject
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/** New audio sidecar contract; never changes frozen v0/RO1/SG wire. URIs stay local. */
internal object AstraAudioContract {
    const val MODEL = "gpt-6-astra"
    const val EFFORT = "xhigh"
    const val MAX_RESULT = 262144
    const val MAX_AUDIO = 10 * 1024 * 1024
    const val MAX_BATCH = 4
    fun sha(bytes:ByteArray)=DriveRelay.sha256(bytes)
    fun obj(value:Any?)=StrictJson.`object`(value)
    fun parse(bytes:ByteArray)=obj(StrictJson.parse(bytes))
    fun exact(x:Map<String,Any?>,vararg keys:String) { require(x.keys==keys.toSet()) { "ASTRA_SCHEMA" } }
    fun text(x:Map<String,Any?>,key:String,max:Int=256):String=(x[key] as? String)?.also {
        require(it.isNotBlank() && it.length<=max) { "ASTRA_SCHEMA" }
    } ?: error("ASTRA_SCHEMA")
    fun number(x:Map<String,Any?>,key:String):Long=(x[key] as? Long)?.also { require(it>=0) { "ASTRA_SCHEMA" } } ?: error("ASTRA_SCHEMA")
    fun list(x:Map<String,Any?>,key:String,max:Int):List<Any?> =(x[key] as? List<*>)?.also { require(it.size<=max) { "ASTRA_SCHEMA" } } ?: error("ASTRA_SCHEMA")
    fun hash(value:String) { require(Regex("[0-9a-f]{64}").matches(value)) { "ASTRA_HASH" } }
    fun id(value:String) { require(Regex("[A-Za-z0-9_-]{1,128}").matches(value)) { "ASTRA_ID" } }

    fun manifest(scope:String,inputs:List<AstraAudioInput>):ByteArray {
        require(scope.isNotBlank() && scope.length<=2048) { "ASTRA_SCOPE" }
        require(inputs.size in 1..MAX_BATCH && inputs.map{it.job}.distinct().size==inputs.size && inputs.map{it.sourceId}.distinct().size==inputs.size) { "ASTRA_BATCH" }
        val entries=inputs.sortedBy{it.job}.map { i ->
            id(i.job);id(i.sourceId);hash(i.audioSha);hash(i.revisionSha)
            require(i.sourceVersion>0 && i.document.size in 1..MAX_AUDIO.toLong()) { "ASTRA_SOURCE" }
            require(i.document.mime.isNotBlank() && i.document.mime.length<=128) { "ASTRA_MIME" }
            val request=JSONObject(JSONObject(i.handoff).getString("frozenRequestJson"))
            require(request.getString("sourceId")==i.sourceId && request.getLong("sourceVersion")==i.sourceVersion && request.getString("audioSha")==i.audioSha) { "ASTRA_NATIVE_SOURCE_BINDING" }
            mapOf("jobId" to i.job,"sourceId" to i.sourceId,"sourceVersion" to i.sourceVersion,
                "audioSha256" to i.audioSha,"byteLength" to i.document.size,"mime" to i.document.mime,
                "revisionSha256" to i.revisionSha,"handoffSha256" to sha(i.handoff.toByteArray(Charsets.UTF_8)))
        }
        return StrictJson.bytes(mapOf("schema" to "ieum.astra-audio.manifest.v1","scope" to scope,
            "model" to MODEL,"effort" to EFFORT,"files" to entries))
    }
    /** Signature proves pinned producer provenance, not that a model really ran or a user approved. */
    fun verify(raw:ByteArray,manifest:ByteArray,keys:Map<String,PublicKey>,allowFixture:Boolean,receipt:String):Verified {
        require(raw.size<=MAX_RESULT) { "ASTRA_RESULT_SIZE" }
        val packet=parse(raw);exact(packet,"payload","keyId","signature")
        val key=keys[text(packet,"keyId")] ?: error("ASTRA_UNTRUSTED_WRITER")
        val payload=obj(packet["payload"]);val bytes=StrictJson.bytes(payload)
        val signature=Base64.getDecoder().decode(text(packet,"signature",2048))
        require(Signature.getInstance("SHA256withRSA").run { initVerify(key);update(bytes);verify(signature) }) { "ASTRA_SIGNATURE" }
        exact(payload,"schema","manifestSha256","model","effort","status","revision","receipt","execution","results")
        require(payload["schema"]=="ieum.astra-audio.result.v1" && payload["manifestSha256"]==sha(manifest) && payload["model"]==MODEL && payload["effort"]==EFFORT && payload["status"]=="COMPLETE" && payload["receipt"]==receipt && number(payload,"revision")==1L) { "ASTRA_RESULT_BINDING" }
        val execution=obj(payload["execution"]);exact(execution,"kind","responseId","model","effort","audioSha256")
        require(execution["kind"]=="RUNTIME" || allowFixture && execution["kind"]=="TEST_FIXTURE") { "ASTRA_EXECUTION_KIND" }
        text(execution,"responseId");require(execution["model"]==MODEL && execution["effort"]==EFFORT) { "ASTRA_MODEL_MISMATCH" }
        val files=list(parse(manifest),"files",MAX_BATCH).map(::obj)
        require(list(execution,"audioSha256",MAX_BATCH)==files.map{it["audioSha256"]}) { "ASTRA_BYTES_NOT_ACKNOWLEDGED" }
        val results=list(payload,"results",MAX_BATCH).map(::obj)
        require(results.size==files.size && results.map{it["jobId"]}.distinct().size==files.size) { "ASTRA_PARTIAL_RESULT" }
        for(r in results) {
            exact(r,"jobId","sourceId","sourceVersion","audioSha256","revisionSha256","handoffSha256","transcript","summary","claims","uncertainties")
            val f=files.singleOrNull{it["jobId"]==r["jobId"]} ?: error("ASTRA_OTHER_JOB")
            for(k in listOf("jobId","sourceId","sourceVersion","audioSha256","revisionSha256","handoffSha256"))require(f[k]==r[k]) { "ASTRA_SOURCE_BINDING" }
            val transcript=text(r,"transcript",64000);text(r,"summary",3000)
            list(r,"uncertainties",30).forEach{require(it is String && it.isNotBlank() && it.length<=1000) { "ASTRA_SCHEMA" }}
            val claims=list(r,"claims",30).map(::obj)
            require(claims.map{it["id"]}.distinct().size==claims.size) { "ASTRA_DUPLICATE_CLAIM" }
            for(c in claims) {
                exact(c,"id","kind","text","assignee","due","requiredDocuments","uncertainty","evidence")
                id(text(c,"id"));text(c,"text",200)
                require(c["kind"] in setOf("WORK","REQUEST","PROMISE")) { "ASTRA_SCHEMA" }
                for(k in listOf("assignee","due","uncertainty"))require(c[k]==null || c[k] is String && text(c,k,1000).isNotBlank()) { "ASTRA_SCHEMA" }
                list(c,"requiredDocuments",10).forEach{require(it is String && it.isNotBlank() && it.length<=200) { "ASTRA_SCHEMA" }}
                val e=obj(c["evidence"]);exact(e,"start","end","quote")
                val cp=transcript.codePoints().toArray();val start=number(e,"start");val end=number(e,"end")
                require(start<end && end<=cp.size && String(cp,start.toInt(),(end-start).toInt())==text(e,"quote",3000)) { "ASTRA_QUOTE" }
            }
        }
        return Verified(raw.clone(),payload,results,execution["kind"] as String)
    }
    class Verified(val raw:ByteArray,val payload:Map<String,Any?>,val results:List<Map<String,Any?>>,val kind:String) {
        fun observation(job:String,handoff:String):AutomationObservation.Result {
            val result=results.single{it["jobId"]==job}
            require(result["handoffSha256"]==sha(handoff.toByteArray(Charsets.UTF_8))) { "ASTRA_HANDOFF_CHANGED" }
            val tasks=JSONArray()
            list(result,"claims",30).map(::obj).filter{it["kind"]!="WORK"}.forEach { c ->
                val e=obj(c["evidence"])
                tasks.put(JSONObject().put("title",c["text"]).put("status","needs_confirmation")
                    .put("assignee",c["assignee"] ?: JSONObject.NULL).put("due",c["due"] ?: JSONObject.NULL)
                    .put("start",e["start"]).put("end",e["end"]).put("quote",e["quote"]))
            }
            // Keep the exact per-source rich candidate as raw; do not overflow existing result limits with batch envelope.
            val candidate=StrictJson.bytes(result)
            return AutomationObservation.Result(AutomationAnalysis(1,sha(candidate),String(candidate,Charsets.UTF_8),
                text(result,"transcript",64000),text(result,"summary",3000),text(result,"summary",3000),tasks,
                JSONObject(handoff).getString("frozenRequestJson")),
                AutomationAssurance(if(kind=="TEST_FIXTURE") "ASTRA_AUDIO_TEST_FIXTURE" else "ASTRA_AUDIO_SIGNED_CANDIDATE",verifiedSource=true))
        }
    }
}

internal data class AstraAudioInput(val job:String,val sourceId:String,val sourceVersion:Long,val audioSha:String,
    val revisionSha:String,val handoff:String,val document:SourceDocument) {
    override fun toString()="AstraAudioInput[withheld]"
    fun local():Map<String,Any?> = mapOf("job" to job,"sourceId" to sourceId,"version" to sourceVersion,"sha" to audioSha,
        "revisions" to revisionSha,"handoff" to handoff,"document" to mapOf("id" to document.documentId,"uri" to document.uri,
            "name" to document.name,"size" to document.size,"mime" to document.mime,"modified" to document.modified,
            "partial" to document.partial,"virtual" to document.virtual))
    companion object {
        fun restore(x:Map<String,Any?>):AstraAudioInput {
            val d=AstraAudioContract.obj(x["document"])
            return AstraAudioInput(x["job"] as String,x["sourceId"] as String,x["version"] as Long,x["sha"] as String,x["revisions"] as String,x["handoff"] as String,
                SourceDocument(d["id"] as String,d["uri"] as String,d["name"] as String,d["size"] as Long,d["mime"] as String,d["modified"] as Long,d["partial"] as Boolean,d["virtual"] as Boolean))
        }
    }
}
