package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.AnalysisBinding
import kr.co.callsummary.core.JsonCanonical
import kr.co.callsummary.core.SqlCallRepository
import kr.co.callsummary.core.android.AndroidSqlPort
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

/** Actual Java repository + Android SQLite driver + UI read adapter; all inputs fabricated.
 * Robolectric API35 is a host test and is not an S25/One UI or APK launch result.
 * API36 was attempted but its sandbox requires Java21; evidence retains that failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35],shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeRepositoryIntegrationTest : ProtectedTextHostTestBase() {
    private lateinit var context: Context
    private lateinit var port: AndroidSqlPort
    private lateinit var repository: SqlCallRepository
    private val scope = LocalRecordingCatalog.LOCAL_SCOPE
    private val now = Instant.parse("2026-10-10T03:00:00Z").toEpochMilli()
    private val original = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()
    private val raw = "📞 합성 통화입니다. 서류는 아직 보내지 않았습니다. 담당자와 기한은 확인이 필요합니다."

    @Before fun openSyntheticDatabase() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(LocalRecordingCatalog.DATABASE_NAME)
        port = AndroidSqlPort(context, LocalRecordingCatalog.DATABASE_NAME, context.assets.open("call_core_schema.sql"))
        repository = SqlCallRepository(port, scope)
        port.execute("INSERT INTO board_state(owner_key) VALUES(?)", scope)
        port.execute("INSERT INTO folder_grants VALUES(?,?,?,?,?,?)", "synthetic_grant", scope, "content://synthetic/selected-folder", 1, 1, 1)
        task("synthetic_task", "synthetic_case", "synthetic_person")
    }
    @After fun closeSyntheticDatabase() { if (::repository.isInitialized) repository.close() }
    private fun task(id: String, case: String, person: String) = port.execute(
        "INSERT INTO tasks(task_id,owner_key,case_id,person_id,status) VALUES(?,?,?,?,?)", id, scope, case, person, "open")
    private fun observe(event: String = "synthetic_observe_1", id: String = "synthetic_recording",
                        sequence: Int = 1, data: String = "A", name: String = "합성 녹음.wav",
                        stamp: Long? = original, task: String = "synthetic_task", person: String = "synthetic_person",
                        at: Long = now) = repository.observe(SqlCallRepository.map(
        "event_id", event, "recording_id", id, "owner_key", scope, "grant_id", "synthetic_grant",
        "policy_revision", 1, "document_id", "doc_$id", "source_seq", sequence,
        "display_name", name, "mime_type", "audio/wav", "byte_count", data.toByteArray().size,
        "modified_at_ms", at - 120000, "original_started_at_ms", stamp,
        "phone_group_ref", "synthetic_opaque_shared_group", "person_id", person, "company_id", null, "task_id", task
    ), JsonCanonical.textHash(data), at)
    private fun count(table: String) = (port.query("SELECT count(*) AS n FROM $table").single()["n"] as Number).toLong()
    private fun candidate(binding: AnalysisBinding): Pair<Map<String, Any>, Map<String, Any>> {
        val source = SqlCallRepository.map(
            "source_id", binding.text("recording_id") + "_v" + binding.number("source_version"),
            "kind", "call_transcript", "raw_text", raw, "text_sha256", JsonCanonical.textHash(raw),
            "recorded_at", Instant.ofEpochMilli(binding.number("base_original_started_at_ms")).toString(),
            "timestamp_basis", "synthetic_fixture_clock", "case_id", binding.text("base_case_id"),
            "case_link_confirmed", true, "speaker_assignment_verified", false, "uncertain_ranges", emptyList<Any>(),
            "provenance", SqlCallRepository.map(
                "source_kind", "synthetic_contract_fixture", "language", "ko", "run_id", "synthetic_native_test",
                "audio_sha256", binding.text("audio_sha256"), "model_name", null, "model_sha256", null, "engine_sha256", null,
                "execution_observed", false, "reference_used_in_inference", false, "quality_state", "raw_review_required",
                "run_started_at_utc", null, "run_finished_at_utc", null, "runner_sha256", null, "source_commit", null,
                "dataset", null, "dataset_revision", null, "license", null
            )
        )
        val packet = SqlCallRepository.map(
            "schema_version", 1, "job_id", binding.text("processing_id"), "base_revision", binding.number("base_board_revision"),
            "scope", "synthetic_contract_test", "offset_unit", "unicode_code_points",
            "automatic_summary_allowed", false, "root_semantic_review_required", true, "sources", listOf(source)
        )
        packet["source_pack_sha256"] = JsonCanonical.digest(packet)
        // This deliberately bad semantic claim has an exact quote. It must remain a review candidate.
        val claim = SqlCallRepository.map(
            "claim_id", "synthetic_claim", "category", "status", "subject_id", binding.text("base_person_id"),
            "value", "서류 발송 완료", "certainty", "explicit",
            "evidence", listOf(SqlCallRepository.map("source_id", source["source_id"], "start", 0,
                "end", raw.codePointCount(0, raw.length), "quote", raw)),
            "status", "done", "assignee", null, "due", null, "due_expression", null, "replaces", null
        )
        val output = SqlCallRepository.map(
            "schema_version", 1, "job_id", binding.text("processing_id"), "source_pack_sha256", packet["source_pack_sha256"],
            "base_revision", binding.number("base_board_revision"), "summary", "[합성 변경안] 서류 발송은 검토가 필요합니다.", "claims", listOf(claim)
        )
        return packet to output
    }
    private fun store(binding: AnalysisBinding, at: Long = now + 30) = candidate(binding).let { (packet, output) ->
        repository.storeWorkerCandidate("synthetic_result_" + binding.text("processing_id"), binding, packet, output, "synthetic_no_stt_v1", at)
    }
    private fun reject(code: String, operation: () -> Unit) {
        try { operation(); fail("Expected $code") }
        catch (error: RuntimeException) { assertTrue(error.message ?: "", error.message.orEmpty().contains(code)) }
    }

    @Test fun sameContentRenamePreservesVersionAndReturningContentCreatesThirdVersion() {
        observe()
        observe("synthetic_rename", sequence = 2, name = "합성 변경 이름.wav")
        assertEquals(1L, repository.recording("synthetic_recording")["source_version"])
        observe("synthetic_changed", sequence = 3, data = "B")
        observe("synthetic_returned", sequence = 4, data = "A")
        assertEquals(3L, repository.recording("synthetic_recording")["source_version"])
        assertEquals(2L, count("content_blobs"))
        assertEquals(3, LocalRecordingCatalog(context).history("synthetic_recording").getJSONArray("versions").length())
    }
    @Test fun unknownTimeAndOriginalDateAreNotReplacedWithCollectionDate() {
        observe(stamp = null)
        val row = LocalRecordingCatalog(context).listFiles().getJSONObject(0)
        assertTrue(row.isNull("original_date")); assertTrue(row.isNull("original_started_at"))
        assertEquals("unknown_time", row.getString("status"))
        reject("ORIGINAL_TIMESTAMP_UNKNOWN") { repository.claim("synthetic_claim", "synthetic_recording", "synthetic_processing", "synthetic_v1", now + 1) }
        val review = LocalRecordingCatalog(context).reviewPreviousDay(Instant.parse("2026-10-11T03:00:00Z"))
        assertEquals("2026-10-10", review.getString("date")); assertFalse(review.getBoolean("applied"))
        assertTrue(review.getJSONArray("file_changes").getJSONObject(0).isNull("original_date"))
    }
    @Test fun sameOpaqueGroupKeepsDifferentPeopleTasksAndBusinessCases() {
        observe()
        task("synthetic_task_b", "synthetic_case_b", "synthetic_person_b")
        observe("synthetic_obs_b", id = "synthetic_recording_b", task = "synthetic_task_b", person = "synthetic_person_b")
        val rows = LocalRecordingCatalog(context).listFiles()
        assertEquals(2, rows.length()); assertEquals(1L, count("content_blobs"))
        assertEquals(rows.getJSONObject(0).getString("counterpart_ref"), rows.getJSONObject(1).getString("counterpart_ref"))
        assertNotEquals(rows.getJSONObject(0).getString("case_id"), rows.getJSONObject(1).getString("case_id"))
        assertNotEquals(rows.getJSONObject(0).getString("person_id"), rows.getJSONObject(1).getString("person_id"))
    }
    @Test fun workerCandidateDoesNotOverwriteManualTextOrCompletedTask() {
        observe(); repository.claim("synthetic_claim", "synthetic_recording", "synthetic_processing", "synthetic_v1", now + 1)
        val binding = repository.binding("synthetic_recording")
        repository.userCommand("EDIT", SqlCallRepository.map(
            "event_id", "synthetic_edit", "recording_id", "synthetic_recording", "expected_user_revision", 0,
            "expected_lock_revision", 0, "edit_text", 1, "new_text", "[합성 직접 수정] 완료 내용 보존",
            "edit_status", 1, "new_status", "done", "now_ms", now + 2))
        repository.userCommand("TASK_EDIT", SqlCallRepository.map(
            "event_id", "synthetic_task_edit", "task_id", "synthetic_task", "expected_task_revision", 0,
            "expected_board_revision", 0, "new_status", "done", "now_ms", now + 3))
        assertEquals("REVIEW_REQUIRED", store(binding)["outcome"])
        val row = LocalRecordingCatalog(context).listFiles().getJSONObject(0)
        assertEquals("[합성 직접 수정] 완료 내용 보존", row.getString("summary")); assertEquals("done", row.getString("manual_status"))
        assertTrue(row.isNull("summary_revision")); assertEquals("needs_review", row.getString("status"))
        assertEquals("done", port.query("SELECT status FROM tasks WHERE task_id=?", "synthetic_task").single()["status"])
        val result = LocalRecordingCatalog(context).history("synthetic_recording").getJSONArray("versions").getJSONObject(0).getJSONArray("results").getJSONObject(0)
        assertEquals(raw, result.getString("raw_text")); assertFalse(result.getBoolean("is_applied")); assertTrue(result.getBoolean("review_required"))
        assertEquals(JsonCanonical.textHash(raw), result.getString("raw_text_sha256"))
    }
    @Test fun changedSourceRejectsOldResultWithoutPartialTranscriptOrSummary() {
        observe(); repository.claim("synthetic_claim", "synthetic_recording", "synthetic_processing", "synthetic_v1", now + 1)
        val binding = repository.binding("synthetic_recording")
        observe("synthetic_change", sequence = 2, data = "B", at = now + 2)
        reject("STALE_SOURCE_OR_POLICY") { store(binding) }
        assertEquals(0L, count("transcript_versions")); assertEquals(0L, count("summary_versions"))
    }
    @Test fun expiredLeaseCannotCommitAfterNewWorkerClaimsTheSameSource() {
        observe(); repository.claim("synthetic_claim_1", "synthetic_recording", "synthetic_processing_1", "synthetic_v1", now + 1)
        val old = repository.binding("synthetic_recording")
        repository.claim("synthetic_claim_2", "synthetic_recording", "synthetic_processing_2", "synthetic_v1", now + 300002)
        reject("STALE_LEASE") { store(old, now + 300003) }
        assertEquals(2L, repository.binding("synthetic_recording").number("lease_epoch")); assertEquals(0L, count("summary_versions"))
    }
    @Test fun previousDayReviewKeepsPastOriginalDateAndReopenPreservesHistory() {
        observe(); observe("synthetic_rename", sequence = 2, name = "합성 이름 변경.wav", at = now + 1)
        repository.close()
        val catalog = LocalRecordingCatalog(context)
        val review = catalog.reviewPreviousDay(Instant.parse("2026-10-11T03:00:00Z"))
        assertFalse(review.getBoolean("applied")); assertEquals(2, review.getJSONArray("file_changes").length())
        assertEquals("2026-10-08", review.getJSONArray("file_changes").getJSONObject(0).getString("original_date"))
        assertTrue(review.getJSONArray("file_changes").getJSONObject(1).getString("description").contains("새 파일 버전을 만들지 않았어요"))
        assertEquals(1, catalog.history("synthetic_recording").getJSONArray("versions").length())
    }
    @Test fun unreadableDatabaseRaisesInsteadOfReportingNoRecordings() {
        repository.close()
        val db = context.getDatabasePath(LocalRecordingCatalog.DATABASE_NAME)
        val fabricated = "synthetic malformed SQLite bytes".repeat(8)
        db.writeText(fabricated)
        try { LocalRecordingCatalog(context).listFiles(); fail("Corrupt database became empty-success") }
        catch (error: RuntimeException) { assertTrue(error.message.orEmpty().contains("TEXT_STORE_LEGACY_REVIEW_REQUIRED")) }
        assertEquals("Corrupt input must not be automatically deleted/recreated", fabricated, db.readText())
    }
    private fun accept(event: String, version: Long, historical: Boolean = false) {
        val current = repository.recording("synthetic_recording")
        val task = port.query("SELECT task_revision FROM tasks WHERE task_id=?", "synthetic_task").single()
        val board = port.query("SELECT revision FROM board_state WHERE owner_key=?", scope).single()
        repository.userCommand("ACCEPT", SqlCallRepository.map(
            "event_id", event, "recording_id", "synthetic_recording", "summary_version", version,
            "expected_user_revision", current["user_revision"], "expected_lock_revision", current["lock_revision"],
            "expected_task_revision", task["task_revision"], "expected_board_revision", board["revision"],
            "semantic_review_confirmed", 1, "restore_historical", if (historical) 1 else 0, "now_ms", now + 50))
    }
    @Test fun explicitHistoricalRestoreCreatesNewUserRevisionAndPreservesImmutableVersions() {
        observe(); repository.claim("synthetic_claim_1", "synthetic_recording", "synthetic_processing_1", "synthetic_v1", now + 1)
        store(repository.binding("synthetic_recording")); accept("synthetic_accept_1", 1)
        observe("synthetic_changed", sequence = 2, data = "B", at = now + 2)
        repository.claim("synthetic_claim_2", "synthetic_recording", "synthetic_processing_2", "synthetic_v1", now + 3)
        store(repository.binding("synthetic_recording"))
        val stale = LocalRecordingCatalog(context).listFiles().getJSONObject(0)
        assertTrue(stale.getBoolean("summary_is_stale")); assertEquals(1, stale.getInt("summary_revision"))
        accept("synthetic_accept_2", 2)
        accept("synthetic_restore_1", 1, historical = true)
        val restored = repository.recording("synthetic_recording")
        assertEquals(3L, restored["user_revision"]); assertEquals(1L, restored["effective_summary_version"])
        assertEquals(2L, count("summary_versions")); assertEquals(2L, count("source_versions"))
        assertEquals(1L, port.query("SELECT restored_summary_version FROM manual_history WHERE event_id=?", "synthetic_restore_1").single()["restored_summary_version"])
        val history = LocalRecordingCatalog(context).history("synthetic_recording").getJSONArray("versions")
        assertTrue(history.getJSONObject(0).getJSONArray("results").getJSONObject(0).getBoolean("is_current"))
        assertFalse(history.getJSONObject(1).getJSONArray("results").getJSONObject(0).getBoolean("is_current"))
    }
}
