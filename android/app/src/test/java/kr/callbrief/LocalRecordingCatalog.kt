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
        json("file_id" to fileId, "versions" to history,
             "manual_history" to JSONArray().apply { manualHistory.forEach { put(JSONObject(it)) } },
             "effective_summary_revision" to recording["effective_summary_version"],
             "manual_text" to recording["manual_text"], "user_revision" to recording["user_revision"])
    }
}

/** Test-only reader of the store's recordings (the product has no list screen over this projection any more). */
fun GeneralLocalStore.snapshot(): JSONArray =
    LocalRecordingCatalog(org.robolectric.RuntimeEnvironment.getApplication(), GeneralLocalStore.DATABASE, GeneralLocalStore.OWNER, true).listFiles()
