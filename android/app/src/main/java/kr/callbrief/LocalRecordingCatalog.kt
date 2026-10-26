package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Read projection of the app's canonical SQLite repository, never a worker wire schema.
 * Opens a distinct new store; does not migrate or mutate legacy settings/remote boards.
 * A database/read failure propagates to the UI instead of becoming an empty-success list.
 */
class LocalRecordingCatalog(
    private val context: Context,
    private val databaseName: String = DATABASE_NAME,
    private val localScope: String = LOCAL_SCOPE,
    private val normalMetadata: Boolean = false
) {
    companion object {
        const val DATABASE_NAME = "callbrief-local-v1.db"
        // Local device scope only. This is not a dot account or authentication identity.
        const val LOCAL_SCOPE = "local-device-v1"
    }

    private fun <T> read(block: (AndroidSqlPort) -> T): T {
        val port = AndroidSqlPort(context, databaseName, context.assets.open("call_core_schema.sql"))
        try { return port.transaction { block(port) } }
        finally { port.close() }
    }
    private fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun timestamp(value: Any?): String? = (value as? Number)?.toLong()?.let { Instant.ofEpochMilli(it).toString() }
    private fun state(value: Any?): String = when (value) {
        "PENDING" -> "pending"
        "TRANSCRIBING" -> "processing"
        "REVIEW" -> "needs_review"
        "FAILED" -> "failed"
        "HELD_UNKNOWN_TIME" -> "unknown_time"
        "HELD_UNLINKED" -> "unlinked"
        "WAITING_SOURCE" -> "source_unavailable"
        "RETIRED" -> "retired"
        else -> "unknown"
    }
    fun listFiles(): JSONArray = read { port ->
        val rows = port.query("""
            SELECT r.*, t.case_id,t.status AS business_status,t.task_revision, f.manual_text, f.manual_status, f.display_name,
                   s.summary_text, v.observed_at_ms ${if(normalMetadata) ",l.actual_uri,l.filename_time_candidate_ms,a.archived,a.revision AS visibility_revision" else ""}
            FROM recording_list r JOIN recordings f ON f.recording_id=r.recording_id
            JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
            LEFT JOIN tasks t ON t.task_id=r.task_id
            LEFT JOIN summary_versions s ON s.recording_id=r.recording_id AND s.summary_version=r.effective_summary_version
            ${if(normalMetadata) "LEFT JOIN local_files l ON l.recording_id=r.recording_id LEFT JOIN local_visibility a ON a.recording_id=r.recording_id" else ""}
            WHERE r.owner_key=? ${if(normalMetadata) "AND l.recording_id IS NOT NULL" else ""} ORDER BY r.original_started_at_ms IS NULL,r.original_started_at_ms,r.recording_id
        """.trimIndent(), localScope)
        JSONArray().apply { rows.forEach { row ->
            val item=json(
                "file_id" to row["recording_id"], "source_version" to row["source_version"],
                "actual_uri" to if(normalMetadata)row["actual_uri"] else null,
                "original_started_at" to timestamp(row["original_started_at_ms"]),
                "original_date" to row["original_day"], "collected_at" to timestamp(row["observed_at_ms"]),
                "counterpart_ref" to row["phone_group_ref"], "person_id" to row["person_id"],
                "company_id" to row["company_id"], "case_id" to row["case_id"], "task_id" to row["task_id"],
                "status" to state(row["state"]),
                "presence" to if (row["presence"] == "PRESENT") "present" else "missing",
                "summary" to (if(normalMetadata) row["summary_text"] else row["manual_text"] ?: row["summary_text"]),
                "manual_note" to row["manual_text"], "display_name" to row["display_name"],
                "head_revision" to row["head_revision"],
                "archived" to ((row["archived"] as? Number)?.toInt()==1),
                "visibility_revision" to ((row["visibility_revision"] as? Number)?.toLong() ?: 0L),
                "filename_time_candidate" to timestamp(row["filename_time_candidate_ms"]),
                "summary_revision" to row["effective_summary_version"],
                "summary_is_stale" to ((row["effective_summary_stale"] as? Number)?.toInt() == 1),
                "user_revision" to row["user_revision"], "lock_revision" to row["lock_revision"],
                "manual_status" to row["manual_status"]
            )
            if(normalMetadata)CurrentAnalysisResult.read(context,databaseName,row["recording_id"].toString(),(row["source_version"] as Number).toLong())?.let { result ->
                item.put("analysis_result_state",result.getString("state")).put("analysis_result_digest",result.optString("digest"))
            }
            if(normalMetadata && row["business_status"]!=null)item.put("business_status",row["business_status"]).put("task_revision",row["task_revision"])
            if(normalMetadata && port.query("SELECT name FROM sqlite_master WHERE type='table' AND name='google_native_proposal_heads'").isNotEmpty()) {
                val proposal=port.query("""SELECT p.* FROM google_native_proposals p JOIN google_native_proposal_heads h
                    ON h.job_id=p.job_id AND h.kind=p.kind AND h.schema_version=p.schema_version AND h.result_revision=p.result_revision AND h.digest=p.digest
                    WHERE h.recording_id=? AND p.source_version=? AND p.audio_sha=(SELECT audio_sha256 FROM source_versions WHERE recording_id=? AND source_version=?) AND p.hidden=0""".trimIndent(),row["recording_id"],row["source_version"],row["recording_id"],row["source_version"]).singleOrNull()
                if(proposal!=null) {
                    item.put("google_proposal_summary",proposal["summary_text"] ?: JSONObject.NULL).put("google_proposal_raw",proposal["raw_text"] ?: JSONObject.NULL).put("google_proposal_analysis",proposal["analysis_json"] ?: JSONObject.NULL)
                        .put("google_proposal_job",proposal["job_id"]).put("google_proposal_digest",proposal["digest"]).put("google_proposal_revision",proposal["result_revision"])
                        .put("google_proposal_conflict",proposal["conflict"].toString()=="1" || proposal["base_user_revision"]!=row["user_revision"] || proposal["base_lock_revision"]!=row["lock_revision"])
                        .put("google_proposal_label","읽기 전용 요약 제안 · 작성자·실행 근거 확인 필요 · 업무 미반영")
                }
            }
            if(normalMetadata && port.query("SELECT name FROM sqlite_master WHERE name='workflow_links'").isNotEmpty()) {
                val link=port.query("SELECT * FROM workflow_links WHERE recording_id=?",row["recording_id"]).singleOrNull()
                fun display(key:String)=link?.get(key)?.let { port.query("SELECT label FROM workflow_entities WHERE id=?",it).singleOrNull()?.get("label") }
                val person=listOfNotNull(display("person_id"),display("company_id")).joinToString(" · ")
                if(person.isNotBlank())item.put("counterpart_label",person)
                display("case_id")?.let { item.put("case_label",it) }
                item.put("workflow_items",JSONArray().apply { port.query("SELECT * FROM workflow_items WHERE recording_id=? ORDER BY created_ms,id",row["recording_id"]).forEach { put(JSONObject(it)) } })
                val reviewed=port.query("SELECT * FROM workflow_reviewed_summaries WHERE recording_id=? AND source_version=? AND audio_sha=(SELECT audio_sha256 FROM source_versions WHERE recording_id=? AND source_version=?) ORDER BY revision DESC LIMIT 1",row["recording_id"],row["source_version"],row["recording_id"],row["source_version"]).singleOrNull()
                if(reviewed!=null && listOf("user_revision","lock_revision","head_revision").all { reviewed[it]==row[it] })
                    item.put("summary",reviewed["text"]).put("summary_origin","USER_REVIEWED_LOCAL").put("review_receipt",reviewed["receipt"])
                else if(reviewed!=null)item.put("own_summary_review_stale",true)
            }
            if(normalMetadata)AutomationCatalogProjection.add(context,port,item,row)
            put(item)
        } }
    }

    fun history(fileId: String): JSONObject = read { port ->
        val recording = port.query("SELECT * FROM recordings WHERE recording_id=? AND owner_key=?", fileId, localScope).single()
        val versions = port.query("""
            SELECT v.*,p.state FROM source_versions v JOIN processing p
            ON p.recording_id=v.recording_id AND p.source_version=v.source_version
            WHERE v.recording_id=? ORDER BY v.source_version
        """.trimIndent(), fileId)
        val allResults = port.query("""
            SELECT s.*,t.raw_text,
                   EXISTS(SELECT 1 FROM receipts a WHERE a.recording_id=s.recording_id
                          AND a.operation='ACCEPT' AND a.summary_version=s.summary_version) AS was_accepted
            FROM summary_versions s JOIN transcript_versions t ON t.transcript_id=s.transcript_id
            WHERE s.recording_id=? ORDER BY s.summary_version
        """.trimIndent(), fileId)
        val history = JSONArray()
        versions.forEach { version ->
            val results = JSONArray()
            allResults.filter { it["source_version"] == version["source_version"] }.forEach { result ->
                val accepted = (result["was_accepted"] as Number).toInt() == 1
                results.put(json(
                    "revision" to result["summary_version"], "raw_text" to result["raw_text"],
                    "summary" to result["summary_text"], "raw_text_sha256" to result["text_sha256"],
                    "summary_sha256" to result["summary_sha256"], "captured_at" to timestamp(result["created_at_ms"]),
                    "is_applied" to accepted, "is_current" to (result["summary_version"] == recording["effective_summary_version"]),
                    "review_required" to !accepted, "review_code" to result["repository_review_code"]
                ))
            }
            history.put(json(
                "version" to version["source_version"], "audio_sha256" to version["audio_sha256"],
                "collected_at" to timestamp(version["observed_at_ms"]), "status" to state(version["state"]),
                "results" to results
            ))
        }
        val manualHistory=port.query("SELECT * FROM manual_history WHERE recording_id=? ORDER BY user_revision",fileId)
        val ownReviews=if(port.query("SELECT name FROM sqlite_master WHERE name='workflow_reviewed_summaries'").isEmpty())emptyList() else port.query("""
            SELECT s.* FROM workflow_reviewed_summaries s JOIN workflow_user_receipts e
            ON e.event_id=s.receipt AND e.recording_id=s.recording_id AND e.operation='USER_REVIEW_SUMMARY'
            WHERE s.recording_id=? ORDER BY s.source_version,s.revision
        """.trimIndent(),fileId)
        val latestOwn=ownReviews.lastOrNull { it["source_version"]==recording["source_version"] && it["audio_sha"]==versions.lastOrNull()?.get("audio_sha256") }
        val ownHistory=JSONArray().apply { ownReviews.forEach { own ->
            val matches=own["source_version"]==recording["source_version"] && own["audio_sha"]==versions.lastOrNull()?.get("audio_sha256") &&
                own["receipt"]==latestOwn?.get("receipt") && listOf("user_revision","lock_revision","head_revision").all { own[it]==recording[it] }
            put(json("revision" to own["revision"],"source_version" to own["source_version"],"audio_sha" to own["audio_sha"],"text" to own["text"],
                "receipt" to own["receipt"],"created_at" to timestamp(own["created_ms"]),"origin" to "USER_REVIEWED_LOCAL_NOT_MODEL_ATTESTATION", "is_current" to matches))
        } }
        json("file_id" to fileId, "versions" to history,
             "user_reviewed_history" to ownHistory,
             "manual_history" to JSONArray().apply { manualHistory.forEach { put(JSONObject(it)) } },
             "effective_summary_revision" to recording["effective_summary_version"],
             "manual_text" to recording["manual_text"], "user_revision" to recording["user_revision"]).apply {
            if(normalMetadata)CurrentAnalysisResult.read(context,databaseName,fileId,(recording["source_version"] as Number).toLong())?.let { result ->
                put("analysis_result_state",result.getString("state")).put("analysis_result_digest",result.optString("digest"))
            }
        }
    }

    fun reviewPreviousDay(now: Instant): JSONObject = read { port ->
        val date = kr.callbrief.core.SeoulTime.yesterday(now)
        val from = date.atStartOfDay(kr.callbrief.core.SeoulTime.zone).toInstant().toEpochMilli()
        val until = date.plusDays(1).atStartOfDay(kr.callbrief.core.SeoulTime.zone).toInstant().toEpochMilli()
        val rows = port.query("""
            SELECT e.recording_id,e.source_version,e.outcome,e.created_at_ms,r.original_day,p.state
            FROM receipts e JOIN recordings r ON r.recording_id=e.recording_id
            JOIN processing p ON p.recording_id=r.recording_id AND p.source_version=r.source_version
            WHERE r.owner_key=? AND e.operation='OBSERVE' AND e.created_at_ms>=? AND e.created_at_ms<?
            ORDER BY e.created_at_ms,e.event_id
        """.trimIndent(), localScope, from, until)
        val changes = JSONArray(); val checks = JSONArray()
        rows.forEach { row ->
            val description = if (row["outcome"] == "SAME_CONTENT") "같은 내용 · 수집 정보만 확인됐어요. 새 파일 버전을 만들지 않았어요."
                else if ((row["source_version"] as Number).toInt() == 1) "새 파일 버전이 수집됐어요."
                else "파일 내용이 변경돼 새 버전이 수집됐어요."
            changes.put(json("file_id" to row["recording_id"], "source_version" to row["source_version"],
                             "original_date" to row["original_day"], "description" to description,
                             "observed_at" to timestamp(row["created_at_ms"])))
            val reason = when (row["state"]) {
                "HELD_UNKNOWN_TIME" -> "원본 통화 날짜·시각 확인 필요"
                "HELD_UNLINKED" -> "사람·업무 연결 확인 필요"
                "REVIEW" -> "저장된 변경안 검토 필요 · 아직 자동 적용하지 않았어요."
                "WAITING_SOURCE" -> "원본 위치 확인 필요 · 저장 기록은 유지돼요."
                "FAILED" -> "처리 실패 확인 필요"
                else -> "분석 연결과 처리 결과 확인 필요"
            }
            checks.put(json("file_id" to row["recording_id"], "reason" to reason))
        }
        json("date" to date.toString(), "file_changes" to changes, "review_required" to checks,
             "applied" to false, "coverage" to "stored_observations_only")
    }
}
