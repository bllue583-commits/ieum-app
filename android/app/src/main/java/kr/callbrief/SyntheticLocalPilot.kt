package kr.callbrief

import android.content.Context
import android.content.ContextWrapper
import kr.co.callsummary.core.*
import kr.co.callsummary.vault.*
import kr.co.callsummary.vault.android.AndroidVaultFactory
import kr.co.callsummary.vault.android.AndroidVaultKeys
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kr.co.callsummary.core.android.AndroidSqlPort
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID

/**
 * Debug-only synthetic integration exercise. Never uses a ContentResolver,
 * real selected folder, settings, account, network or transcription engine.
 * New encrypted store only. Existing v1 stores/files are never migrated or read here.
 */
class SyntheticLocalPilot(
    context: Context,
    accessFactory: (Context) -> VaultAccess = { privateContext ->
        val keys = AndroidVaultKeys()
        VaultAccess(AndroidVaultFactory.open(privateContext, keys),
            privateContext.noBackupFilesDir.toPath().resolve("audio-vault-v2"), keys::createNewKey)
    },
    fault: (String) -> Unit = {}
) : AutoCloseable {
    companion object {
        const val DATABASE = "callbrief-synthetic-pilot-v2.db"
        const val SCOPE = "synthetic-local-pilot-v2"
        const val RECORDING = "synthetic-vault-recording"
        private const val TASK = "synthetic-vault-task"
        private const val ROOT = "content://synthetic-fixture/selected-tree"
        private val original = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()
    }
    private val port: AndroidSqlPort
    private val repository: SqlCallRepository
    val store: EncryptedRecordingStore
    val token = Cancellation()
    data class VaultAccess(val vault: EncryptedAudioVault, val root: Path, val provision: EncryptedAudioVault.KeyProvisioner)
    val catalog: LocalRecordingCatalog
    var openedSyntheticStreams = 0
        private set

    init {
        check(BuildConfig.SYNTHETIC_TOOLS_ENABLED) { "SYNTHETIC_TOOLS_DISABLED" }
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "VAULT_IO_THREAD_REQUIRED" }
        port = AndroidSqlPort(context, DATABASE, context.assets.open("call_core_schema.sql"))
        repository = SqlCallRepository(port, SCOPE)
        repository.initializeLocalScope()
        port.execute("""CREATE TABLE IF NOT EXISTS synthetic_local_archive(
            recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),
            archived INTEGER NOT NULL CHECK(archived IN(0,1)), revision INTEGER NOT NULL)""".trimIndent())
        val syntheticContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File =
                File(context.noBackupFilesDir, "synthetic-local-pilot-v2").apply { mkdirs() }
        }
        try {
            val access = accessFactory(syntheticContext)
            store = EncryptedRecordingStore(port, repository, SCOPE, access.vault, access.root,
                "callsummary.audio.v2.synthetic_local_pilot", access.provision, token,
                context.assets.open("vault2_ledger.sql").bufferedReader().use { it.readText() }, fault = fault,
                processingVisibilityRevision = { recording ->
                    val row = port.query("SELECT archived,revision FROM synthetic_local_archive WHERE recording_id=?", recording).firstOrNull()
                    if ((row?.get("archived") as? Number)?.toInt() == 1) null else (row?.get("revision") as? Number)?.toLong() ?: 0L
                })
        } catch (error: RuntimeException) { repository.close(); throw error }
        catalog = LocalRecordingCatalog(context, DATABASE, SCOPE)
    }
    private fun id(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "")
    private fun now() = Instant.now().toEpochMilli()
    fun current(): Map<String, Any?>? = repository.listRecordings().firstOrNull { it["recording_id"] == RECORDING }
    fun collection(name: String = "합성 원음 A.wav", alternate: Boolean = false, excluded: Boolean = false): Map<String, Any?> {
        repository.registerUserSelectedFolder("synthetic-pilot-grant", ROOT, 1)
        if (port.query("SELECT task_id FROM tasks WHERE task_id=?", TASK).isEmpty())
            repository.registerExplicitTask(TASK, "synthetic-case", "synthetic-person", null)
        val at = now()
        val data = generatedWav(alternate)
        val sequence = ((current()?.get("source_seq") as? Number)?.toLong() ?: 0) + 1
        val metadata = SqlCallRepository.map(
            "event_id", id("synthetic_observe"), "recording_id", RECORDING, "owner_key", SCOPE,
            "grant_id", "synthetic-pilot-grant", "policy_revision", 1,
            "document_id", "synthetic-fixed-document", "source_seq", sequence,
            "display_name", name, "mime_type", "audio/wav", "byte_count", data.size,
            "modified_at_ms", at - 120000, "original_started_at_ms", original,
            "phone_group_ref", "synthetic-opaque-communication-group",
            "person_id", "synthetic-person", "company_id", null, "task_id", TASK
        )
        // Flags describe the fixed synthetic fixture; they are not a real SAF grant.
        val observation = FileCollector.Observation(metadata, ROOT, true, true)
        return store.collect(observation, JsonCanonical.hex(MessageDigest.getInstance("SHA-256").digest(data)), { excluded }, {
            openedSyntheticStreams++
            ByteArrayInputStream(data)
        })
    }
    fun makeMockCandidate(): Map<String, Any?> {
        check(current() != null) { "SYNTHETIC_IMPORT_REQUIRED" }
        val at = now()
        val binding = store.claim(RECORDING)
        // Full AEAD and plaintext hash/length check occurs before this mock reads any bytes.
        // These bytes are NOT transcribed; the following raw text is a fixed, separate fixture.
        store.withSyntheticAnalysisAudio(binding) { input ->
            val hash = MessageDigest.getInstance("SHA-256"); var count = 0; val buffer = ByteArray(32)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); count += n }
            check(count == 52 && JsonCanonical.hex(hash.digest()) == binding.text("audio_sha256"))
        }
        val raw = "[합성 원문] 준비물 확인이 필요해요. 납품 날짜와 완료 여부는 확정하지 않았어요."
        val source = SqlCallRepository.map(
            "source_id", RECORDING + "_v" + binding.number("source_version"),
            "kind", "call_transcript", "raw_text", raw, "text_sha256", JsonCanonical.textHash(raw),
            "recorded_at", Instant.ofEpochMilli(binding.number("base_original_started_at_ms")).toString(),
            "timestamp_basis", "synthetic_fixture_clock", "case_id", binding.text("base_case_id"),
            "case_link_confirmed", true, "speaker_assignment_verified", false, "uncertain_ranges", emptyList<Any>(),
            "provenance", SqlCallRepository.map(
                "source_kind", "synthetic_contract_fixture", "language", "ko", "run_id", "synthetic_local_pilot",
                "audio_sha256", binding.text("audio_sha256"), "model_name", null, "model_sha256", null, "engine_sha256", null,
                "execution_observed", false, "reference_used_in_inference", false, "quality_state", "raw_review_required",
                "run_started_at_utc", null, "run_finished_at_utc", null, "runner_sha256", null, "source_commit", null,
                "dataset", null, "dataset_revision", null, "license", null
            )
        )
        val packet = SqlCallRepository.map(
            "schema_version", 1, "job_id", binding.text("processing_id"),
            "base_revision", binding.number("base_board_revision"), "scope", "synthetic_contract_test",
            "offset_unit", "unicode_code_points", "automatic_summary_allowed", false,
            "root_semantic_review_required", true, "sources", listOf(source)
        )
        packet["source_pack_sha256"] = JsonCanonical.digest(packet)
        val mock = AnalysisTransport.Mock { b, p -> SqlCallRepository.map(
            "schema_version", 1, "job_id", b.text("processing_id"), "source_pack_sha256", p["source_pack_sha256"],
            "base_revision", b.number("base_board_revision"),
            "summary", "[합성 변경안] 준비물을 다시 확인해 주세요. 납품 날짜·완료 여부는 확인이 필요해요.",
            "claims", emptyList<Any>()
        ) }
        val reply = mock.request(binding, packet)
        check(reply.synthetic)
        return store.storeCandidate(binding, reply.sourcePackV1, reply.candidateV1)
    }
    fun latestCandidate(): Map<String, Any?>? = port.query(
        "SELECT * FROM summary_versions WHERE recording_id=? ORDER BY summary_version DESC LIMIT 1", RECORDING
    ).firstOrNull()

    /** The UI captures these revisions while reviewing and passes them unchanged. */
    fun reviewSnapshot(): Map<String, Any?> = port.query("""
        SELECT r.source_version,r.head_revision,r.user_revision,r.lock_revision,t.task_revision,b.revision AS board_revision,
        COALESCE(a.revision,0) AS local_visibility_revision
        FROM recordings r JOIN tasks t ON t.task_id=r.task_id JOIN board_state b ON b.owner_key=r.owner_key
        LEFT JOIN synthetic_local_archive a ON a.recording_id=r.recording_id
        WHERE r.recording_id=? AND r.owner_key=?
    """.trimIndent(), RECORDING, SCOPE).single()
    fun accept(version: Long, reviewed: Map<String, Any?>, historical: Boolean = false) =
        store.accept(RECORDING, version, reviewed, historical)
    fun editAndComplete(text: String, reviewed: Map<String, Any?>) = port.transaction {
        repository.userCommand("EDIT", SqlCallRepository.map(
            "event_id", id("synthetic_edit"), "recording_id", RECORDING,
            "expected_user_revision", reviewed["user_revision"], "expected_lock_revision", reviewed["lock_revision"],
            "edit_text", 1, "new_text", text, "edit_status", 1, "new_status", "done", "now_ms", now()
        ))
        repository.userCommand("TASK_EDIT", SqlCallRepository.map(
            "event_id", id("synthetic_task_edit"), "task_id", TASK,
            "expected_task_revision", reviewed["task_revision"], "expected_board_revision", reviewed["board_revision"],
            "new_status", "done", "now_ms", now()
        ))
    }
    fun archiveState(): Map<String, Any?> = port.query(
        "SELECT archived,revision FROM synthetic_local_archive WHERE recording_id=?", RECORDING
    ).firstOrNull() ?: mapOf("archived" to 0L, "revision" to 0L)
    fun archive(value: Boolean, expectedRevision: Long) = port.transaction {
        repository.recording(RECORDING)
        val old = archiveState()
        if ((old["revision"] as Number).toLong() != expectedRevision) throw CoreException("ARCHIVE_REVISION_CONFLICT")
        port.execute("INSERT OR REPLACE INTO synthetic_local_archive(recording_id,archived,revision) VALUES(?,?,?)",
            RECORDING, if (value) 1 else 0, expectedRevision + 1)
    }
    fun initializeEmptyVault() = store.initializeNewEmptySyntheticStore()
    fun recoverEmptyInitialization() = store.recoverEmptySyntheticInitialization()
    fun currentReference(): VaultReference = store.reference(RECORDING, (current()!!["source_version"] as Number).toLong())
    fun verifyCurrent() = store.verify(currentReference())
    fun recoverPendingSynthetic(): Int {
        val events = store.pendingEvents()
        events.forEach { store.recoverPending(it, ROOT, true, true) { false } }
        return events.size
    }
    fun keyInitializationAvailable(): Boolean = store.mayInitialize()
    fun snapshot(): ViewState {
        val r = current()
        val inspection=store.initializationInspection()
        val keyReady=store.initializedKeyReady()
        val storage = if (r == null) when {
            keyReady -> "빈 합성 암호화 저장소의 키가 준비됐어요. 아직 원음은 저장하지 않았어요."
            store.mayRecoverInitialization() -> "첫 키 초기화가 중단됐어요. 기존 DB 참조·원음이 없는지 다시 확인한 뒤 아래 버튼으로 이어갈 수 있어요."
            inspection.state==InitializationReport.State.EMPTY -> "합성 암호화 저장소가 비어 있어요. 새 키 초기화는 아래 버튼으로만 해요."
            else -> "키 초기화 상태 확인이 필요해요. 새 키 생성·원음 가져오기는 보류했어요."
        }
            else try { VaultStatusText.korean(store.status(currentReference())) }
            catch (error: VaultException) { "암호화 저장 확인을 보류했어요 · " + error.code }
        return ViewState(r, archiveState(), latestCandidate(), if (r == null) null else reviewSnapshot(),
            if (r == null) null else catalog.listFiles().getJSONObject(0), if (r == null) null else catalog.history(RECORDING),
            storage, keyInitializationAvailable(), store.pendingEvents().size,store.mayRecoverInitialization(),keyReady)
    }
    data class ViewState(val current: Map<String, Any?>?, val archive: Map<String, Any?>,
        val candidate: Map<String, Any?>?, val reviewed: Map<String, Any?>?, val row: JSONObject?,
        val history: JSONObject?, val storageText: String, val canInitialize: Boolean, val pendingCount: Int,
        val canRecoverInitialization:Boolean, val keyReady:Boolean)
    fun cancel() = store.cancel()
    override fun close() { store.close(); repository.close() }


    private fun generatedWav(alternate: Boolean): ByteArray = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(44)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(8)
        repeat(4) { putShort(if (alternate) 1600 else 0) }
    }.array()
}
