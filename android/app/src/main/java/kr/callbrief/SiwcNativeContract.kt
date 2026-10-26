package kr.callbrief

import kr.callagent.drive.StrictJson
import kr.callbrief.context.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Structural/source boundary for the unchanged six-field output. Kept separate from the
 * synthetic-only WorkerV1Boundary: a real local transcript is never relabelled synthetic. */
internal class SiwcNativeContract(source:Map<String,Any?>,snapshot:Map<String,Any?>,val jobId:String,val baseRevision:Long) {
    val source=AstraAudioContract.parse(StrictJson.bytes(source))
    val snapshot=AstraAudioContract.parse(StrictJson.bytes(snapshot))
    val snapshotSha=AstraAudioContract.sha(StrictJson.bytes(this.snapshot))
    val sourceId=(this.source["sourceId"] as String)+"_v"+this.source["sourceVersion"]
    val text=this.source["transcript"] as String
    val pack:Map<String,Any?>
    init {
        id(jobId);id(sourceId);check(baseRevision>=0)
        check(this.source["sourceVersion"] is Long && (this.source["sourceVersion"] as Long)>0)
        check(AstraAudioContract.sha(text.toByteArray(Charsets.UTF_8))==this.source["transcriptSha256"] && text.isNotBlank()) { "SIWC_LOCAL_TEXT_BINDING" }
        AstraAudioContract.hash(this.source["audioSha256"] as String)
        check(this.snapshot["sourceId"]==this.source["sourceId"] && this.snapshot["sourceVersion"]==this.source["sourceVersion"] && this.snapshot["audioSha256"]==this.source["audioSha256"]) { "SIWC_NATIVE_SOURCE_BINDING" }
        val unsigned=mapOf("schema_version" to 1L,"job_id" to jobId,"base_revision" to baseRevision,
            "scope" to "android_local_transcript","offset_unit" to "unicode_code_points","automatic_summary_allowed" to false,
            "root_semantic_review_required" to true,"source" to this.source)
        pack=unsigned+mapOf("source_pack_sha256" to AstraAudioContract.sha(StrictJson.bytes(unsigned)))
    }
    fun analysis(output:String):Map<String,Any?> {
        val a=parse(output);exact(a,"schema_version","job_id","source_pack_sha256","base_revision","summary","claims")
        check(a["schema_version"]==1L && a["job_id"]==jobId && a["source_pack_sha256"]==pack["source_pack_sha256"] && a["base_revision"]==baseRevision) { "SIWC_ANALYSIS_BINDING" }
        text(a["summary"],3000)
        val claims=list(a["claims"],50);val ids=mutableSetOf<String>();val replacements=mutableSetOf<String>()
        for(value in claims) {
            val c=obj(value);exact(c,"assignee","category","certainty","claim_id","due","due_expression","evidence","replaces","status","subject_id","value")
            id(c["claim_id"]);check(ids.add(c["claim_id"] as String)) { "SIWC_DUPLICATE_CLAIM" }
            check(c["category"] in setOf("context","date","document","entity","participant","quantity","request","role","status")) { "SIWC_CLAIM_CATEGORY" }
            check(c["certainty"] in setOf("explicit","uncertain") && c["status"] in setOf(null,"done","in_progress","needs_confirmation","open","waiting")) { "SIWC_CLAIM_STATE" }
            text(c["value"],500);nullableText(c["assignee"],120);nullableText(c["due_expression"],100)
            c["subject_id"]?.let {id(it);check(it in listOf(snapshot["personId"],snapshot["companyId"],snapshot["taskId"])) { "SIWC_FOREIGN_SUBJECT" }}
            c["replaces"]?.let{id(it);check(replacements.add(it as String)) { "SIWC_DUPLICATE_REPLACEMENT" }}
            c["due"]?.let{check(it is String && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it));LocalDate.parse(it);check(c["due_expression"]!=null)}
            val evidence=list(c["evidence"],10);check(evidence.isNotEmpty()) { "SIWC_EVIDENCE_REQUIRED" }
            var dueQuoted=c["due_expression"]==null
            for(e in evidence.map(::obj)) {
                exact(e,"source_id","start","end","quote");check(e["source_id"]==sourceId) { "SIWC_FOREIGN_EVIDENCE" }
                check(quote(e)==text(e["quote"],1000)) { "SIWC_QUOTE_MISMATCH" }
                if(c["due_expression"] is String && (e["quote"] as String).contains(c["due_expression"] as String))dueQuoted=true
            }
            check(dueQuoted) { "SIWC_DATE_NOT_QUOTED" }
        }
        check(claims.map(::obj).all{it["replaces"]==null || it["replaces"] in ids}) { "SIWC_REPLACEMENT_TARGET_UNKNOWN" }
        return a
    }
    /** App-side sidecar; it does not change frozen analysis wire or trust model approval flags. */
    fun context(output:String,analysisJob:String,analysisOutput:String):Map<String,Any?> {
        val a=analysis(analysisOutput);val c=parse(output)
        exact(c,"schema","analysisJobId","analysisOutputSha256","approvedSnapshotSha256","actions")
        check(c["schema"]=="ieum.siwc-context.v1" && c["analysisJobId"]==analysisJob &&
            c["analysisOutputSha256"]==AstraAudioContract.sha(StrictJson.bytes(a)) && c["approvedSnapshotSha256"]==snapshotSha) { "SIWC_CONTEXT_BINDING" }
        val claims=list(a["claims"],50).map(::obj).associateBy{it["claim_id"]};val seen=mutableSetOf<String>()
        for(action in list(c["actions"],50).map(::obj)) {
            exact(action,"claimId","kind","targetId","targetRevision","operation","uncertain","conflict")
            val key=text(action["claimId"],80);check(seen.add(key) && key in claims) { "SIWC_CONTEXT_CLAIM_UNKNOWN_OR_DUPLICATE" }
            check(action["kind"] in setOf("TASK","DOCUMENT","PROGRESS","APPOINTMENT") && action["operation"] in setOf("ASSERT","CANCEL")) { "SIWC_CONTEXT_ACTION" }
            check(action["uncertain"] is Boolean && action["conflict"] is Boolean) { "SIWC_CONTEXT_FLAGS" }
            val claim=claims.getValue(key)
            if(claim["certainty"]=="uncertain")check(action["uncertain"]==true) { "SIWC_UNCERTAINTY_DROPPED" }
            if(action["targetId"]==null)check(action["targetRevision"]==null) { "SIWC_CONTEXT_TARGET_REVISION" }
            else {
                id(action["targetId"]);val target=list(snapshot["items"],250).map(::obj).singleOrNull{it["id"]==action["targetId"]}
                check(target!=null && target["revision"]==action["targetRevision"] && action["targetRevision"] is Long && target["kind"]==action["kind"]) { "SIWC_CONTEXT_TARGET_NOT_CURRENT" }
                if(target["status"]=="done" || target["manual"]==true)check(action["conflict"]==true) { "SIWC_MANUAL_CONFLICT_DROPPED" }
            }
            if(action["operation"]=="CANCEL")check(claim["replaces"]!=null || action["targetId"]!=null) { "SIWC_CANCEL_TARGET_REQUIRED" }
        }
        check(seen==claims.keys) { "SIWC_CONTEXT_CLAIM_OMITTED" };return c
    }
    /** Native DTOs remain proposals. Existing targets and cancellation are held for their
     * explicit edit flow; this adapter only permits deliberate creation of new reviewed items. */
    fun normalized(owner:String,request:String,analysisOutput:String,contextOutput:String,analysisJob:String):NormalizedEvent {
        val a=analysis(analysisOutput);val c=context(contextOutput,analysisJob,analysisOutput)
        val claims=list(a["claims"],50).map(::obj).associateBy{it["claim_id"]}
        return EventAdapter.normalize(SourceId(owner,Channel.CALL_ANALYSIS,"siwc-file",source["sourceId"] as String),
            source["sourceVersion"] as Long,text,(snapshot["originalTimeMs"] as? Long)?.let(Instant::ofEpochMilli),Instant.EPOCH,"",request) { ref ->
            list(c["actions"],50).map(::obj).map { action ->
                val claim=claims.getValue(action["claimId"]);val e=obj(list(claim["evidence"],10).first())
                Claim(claim["claim_id"] as String,Kind.valueOf(action["kind"] as String),action["targetId"] as? String ?: "$request:${claim["claim_id"]}",
                    mapOf(Field.TITLE to claim["value"] as String,Field.STATUS to (claim["status"] as? String ?: "needs_confirmation").uppercase(),Field.DUE to claim["due"] as? String,Field.ASSIGNEE to claim["assignee"] as? String),
                    EventAdapter.evidence(ref,text,(e["start"] as Long).toInt(),(e["end"] as Long).toInt()),
                    if(action["uncertain"]==true || action["conflict"]==true || action["targetId"]!=null || claim["replaces"]!=null)Certainty.NEEDS_REVIEW else Certainty.CLEAR,
                    ClaimOperation.valueOf(action["operation"] as String),supersedes=(claim["replaces"] as? String)?.let{ClaimRef(ref,it)})
            }
        }
    }
    private fun quote(e:Map<String,Any?>):String {
        val start=e["start"] as? Long ?: error("SIWC_QUOTE_INTEGER");val end=e["end"] as? Long ?: error("SIWC_QUOTE_INTEGER")
        check(start>=0 && end>start && end<=text.codePointCount(0,text.length)) { "SIWC_QUOTE_RANGE" }
        return text.substring(text.offsetByCodePoints(0,start.toInt()),text.offsetByCodePoints(0,end.toInt()))
    }
    companion object {
        fun parse(s:String):Map<String,Any?> {check(s.toByteArray(Charsets.UTF_8).size in 1..262144);return AstraAudioContract.parse(s.toByteArray(Charsets.UTF_8))}
        fun obj(v:Any?)=AstraAudioContract.obj(v)
        fun list(v:Any?,max:Int):List<*> {check(v is List<*> && v.size<=max);return v}
        fun exact(x:Map<String,Any?>,vararg keys:String){check(x.keys==keys.toSet()) { "SIWC_EXACT_SCHEMA" }}
        fun text(v:Any?,max:Int):String {check(v is String && v.codePointCount(0,v.length) in 1..max && '\u0000' !in v) { "SIWC_TEXT" };return v}
        fun nullableText(v:Any?,max:Int){if(v!=null){check(v is String && v.codePointCount(0,v.length)<=max && '\u0000' !in v)}}
        fun id(v:Any?){check(v is String && v.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "SIWC_ID" }}
        fun snapshot(s:JSONObject,manualIds:Set<String>):Map<String,Any?> {
            val items=s.getJSONArray("items")
            return mapOf("sourceId" to s.getString("recording_id"),"sourceVersion" to s.getLong("source_version"),"audioSha256" to s.getString("audio_sha256"),
                "personId" to s.optString("person_id").takeUnless{it=="null"||it.isBlank()},"companyId" to s.optString("company_id").takeUnless{it=="null"||it.isBlank()},
                "taskId" to s.optString("task_id").takeUnless{it=="null"||it.isBlank()},"caseId" to s.optString("case_id").takeUnless{it=="null"||it.isBlank()},
                "originalTimeMs" to if(s.isNull("original_started_at_ms"))null else s.getLong("original_started_at_ms"),
                "fences" to listOf("user_revision","lock_revision","head_revision","task_revision","board_revision","link_revision","visibility_revision").associateWith{s.optLong(it)},
                "items" to (0 until items.length()).map {i->val x=items.getJSONObject(i);mapOf("id" to x.getString("id"),"revision" to x.getLong("revision"),"kind" to if(x.getString("kind")=="DOCUMENT")"DOCUMENT" else "TASK","title" to x.getString("title"),"status" to x.getString("status"),"manual" to (x.getString("id") in manualIds))})
        }
    }
}
