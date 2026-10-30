package kr.callbrief

import android.os.Looper
import kr.co.callsummary.core.*
import kr.co.callsummary.core.android.JsonBridge
import kr.co.callsummary.vault.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Encrypted recording vault adapter.
 * No plaintext disk fallback, v1 migration, real recording opener or outgoing transport.
 * File commit precedes DB CAS. An interrupted intake remains recoverable in its own ledger.
 */
class EncryptedRecordingStore(
    private val port: SqlPort,
    private val repository: SqlCallRepository,
    private val owner: String,
    val vault: EncryptedAudioVault,
    val vaultRoot: Path,
    private val alias: String,
    private val provisioner: EncryptedAudioVault.KeyProvisioner,
    private val token: Cancellation,
    ledgerSql: String,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val fault: (String) -> Unit = {},
    private val processingVisibilityRevision: (String) -> Long? = { 0L }
) : AutoCloseable {
    init {
        requireIo()
        port.transaction { ledgerSql.split("\n-- statement-break\n").forEach { port.execute(it) } }
        vault.cleanupAbandonedStages(token)
    }
    private fun requireIo() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "VAULT_IO_THREAD_REQUIRED" }
        token.check()
    }
    private fun number(row: Map<String, Any?>, key: String) = (row[key] as Number).toLong()
    private fun fail(code: String): Nothing = throw VaultException(code)
    private fun id(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "")
    private fun emptyInitializationDatabase(): Boolean =
        port.query("SELECT recording_id FROM vault2_refs LIMIT 1").isEmpty() &&
            port.query("SELECT event_id FROM vault2_pending LIMIT 1").isEmpty() &&
            port.query("SELECT recording_id FROM source_versions LIMIT 1").isEmpty()
    private fun initializationRow(): Map<String,Any?>? = port.query("SELECT * FROM vault2_initialization WHERE owner_key=?",owner).singleOrNull()
    fun initializationInspection(): InitializationReport { requireIo();return vault.initializationStatus(token) }
    fun mayInitialize(): Boolean { requireIo();return port.transaction { emptyInitializationDatabase() && initializationRow()==null && initializationInspection().state==InitializationReport.State.EMPTY } }
    fun mayRecoverInitialization(): Boolean { requireIo();return port.transaction {
        emptyInitializationDatabase() && initializationRow()?.get("state")=="PENDING" && initializationInspection().state in setOf(InitializationReport.State.EMPTY,InitializationReport.State.PENDING,InitializationReport.State.READY)
    } }
    fun initializedKeyReady(): Boolean { requireIo(); val inspection=initializationInspection()
        return initializationRow()?.get("state")=="READY" && inspection.state in setOf(InitializationReport.State.READY,InitializationReport.State.USED) && inspection.keyState==VaultKeys.State.AVAILABLE
    }
    fun initializeNewEmptySyntheticStore() {
        requireIo()
        val intent = port.transaction {
            if (!emptyInitializationDatabase()) fail("PRIOR_REFERENCE_REVIEW_REQUIRED")
            if (initializationRow()!=null) fail("VAULT_ALREADY_INITIALIZED")
            if (initializationInspection().state!=InitializationReport.State.EMPTY) fail("INITIALIZATION_REVIEW_REQUIRED")
            val first=InitializationIntent(id("keyinit"),alias)
            port.execute("INSERT INTO vault2_initialization(owner_key,initialization_id,key_alias,state) VALUES(?,?,?,'PENDING')",owner,first.initializationId,alias)
            first
        }
        finishInitialization(intent,recover=false)
    }
    fun recoverEmptySyntheticInitialization() {
        requireIo()
        val row=initializationRow() ?: fail("INITIALIZATION_REVIEW_REQUIRED")
        if(row["state"]!="PENDING") fail("INITIALIZATION_RECOVERY_DENIED")
        finishInitialization(InitializationIntent(row["initialization_id"].toString(),row["key_alias"].toString()),recover=true)
    }
    private fun finishInitialization(intent: InitializationIntent,recover:Boolean) = port.transaction {
        // PENDING was committed first. Hold this SQLite transaction during FS/key provisioning;
        // ordinary collection requires READY and cannot create references during this reservation.
        val reservation=EncryptedAudioVault.EmptyInitializationReservation { expected ->
            val row=initializationRow()
            expected==intent && expected.keyAlias==alias && row?.get("initialization_id")==intent.initializationId &&
                row["key_alias"]==alias && row["state"]=="PENDING" && emptyInitializationDatabase()
        }
        if(!reservation.stillEmpty(intent)) fail("PRIOR_REFERENCE_REVIEW_REQUIRED")
        if(recover) {
            val report=initializationInspection()
            when(report.state) {
                InitializationReport.State.EMPTY -> vault.beginInitialization(intent,reservation,provisioner,token)
                InitializationReport.State.PENDING -> vault.recoverPendingInitialization(intent,reservation,provisioner,token)
                InitializationReport.State.READY -> if(report.intent!=intent || report.keyState!=VaultKeys.State.AVAILABLE) fail("INITIALIZATION_RECOVERY_DENIED")
                else -> fail("INITIALIZATION_RECOVERY_DENIED")
            }
        } else vault.beginInitialization(intent,reservation,provisioner,token)
        val ready=initializationInspection()
        if(ready.intent!=intent || ready.state!=InitializationReport.State.READY || ready.keyState!=VaultKeys.State.AVAILABLE) fail("INITIALIZATION_NOT_READY")
        fault("AFTER_KEY_SETUP_BEFORE_DB_READY")
        token.check();port.execute("UPDATE vault2_initialization SET state='READY' WHERE owner_key=? AND initialization_id=? AND state='PENDING'",owner,intent.initializationId)
    }
    private fun source(row: Map<String, Any?>) = AudioBinding(owner, row["recording_id"].toString(),
        number(row, "source_version"), row["plaintext_sha256"].toString(), number(row, "plaintext_bytes"))
    private fun reference(row: Map<String, Any?>) = VaultReference(source(row), row["key_alias"].toString(),
        row["ciphertext_sha256"].toString(), number(row, "storage_bytes"))
    fun reference(recording: String, version: Long): VaultReference {
        requireIo()
        val row = port.query("SELECT * FROM vault2_refs WHERE recording_id=? AND source_version=? AND owner_key=?",
            recording, version, owner).singleOrNull() ?: fail("ENCRYPTED_REFERENCE_REQUIRED")
        return reference(row)
    }
    private fun mark(ref: VaultReference, status: String) {
        port.execute("INSERT OR REPLACE INTO vault2_health(ciphertext_sha256,status,checked_at_ms) VALUES(?,?,?)",
            ref.ciphertextSha256, status, now())
    }
    fun status(ref: VaultReference): EncryptedAudioVault.Status {
        requireIo()
        val status = vault.status(ref, token)
        mark(ref, if (status == EncryptedAudioVault.Status.ENCRYPTED_REFERENCE_PRESENT) "PRESENT_UNVERIFIED" else status.name)
        return status
    }
    fun verify(ref: VaultReference) {
        requireIo()
        try {
            vault.withVerifiedAudioForLocalReview(ref, token) { /* Validate full tag+hash; do not retain clear bytes. */ }
            mark(ref, "VERIFIED")
        } catch (error: VaultException) { mark(ref, error.code); throw error }
    }
    private fun preRead(observation: FileCollector.Observation, excluded: () -> Boolean) {
        val p = observation.metadata
        repository.checkSelectedFolder(p["owner_key"].toString(), p["grant_id"].toString(),
            observation.selectedRootUri, number(p, "policy_revision"))
        if (!observation.childVerified) fail("OUTSIDE_SELECTED_FOLDER")
        if (!observation.identityVerified) fail("IDENTITY_REVIEW_REQUIRED")
        if (excluded()) fail("EXCLUDED_BEFORE_READ")
        if (initializationRow()?.get("state") != "READY") fail("EMPTY_STORE_INITIALIZATION_REQUIRED")
        if (number(p, "byte_count") !in 1..AudioBinding.MAX_BYTES || p["mime_type"] !in setOf(
                "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/aac", "audio/3gpp", "audio/ogg", "audio/flac", "application/ogg")) fail("UNSUPPORTED_SOURCE")
    }
    // A snapshot includes source/head plus user, lock, task, entity and board state.
    private fun fence(recording: String, task: Any?): String = JsonCanonical.encode(SqlCallRepository.map(
        "recording", port.query("SELECT * FROM recordings WHERE recording_id=? AND owner_key=?", recording, owner).firstOrNull(),
        "task", if (task == null) null else port.query("SELECT * FROM tasks WHERE task_id=? AND owner_key=?", task, owner).firstOrNull(),
        "board", port.query("SELECT revision FROM board_state WHERE owner_key=?", owner).single()))
    private fun digest(p: Map<String, Any?>, hash: String) = JsonCanonical.digest(SqlCallRepository.map("metadata", p, "plaintext_sha256", hash))
    private fun committed(event: String, requestDigest: String): Map<String, Any?>? {
        val r = port.query("SELECT * FROM vault2_intake_receipts WHERE event_id=? AND owner_key=?", event, owner).firstOrNull() ?: return null
        if (r["request_digest"] != requestDigest) fail("IDEMPOTENCY_CONFLICT")
        return repository.receipt(event) ?: fail("RECEIPT_MISSING")
    }
    fun collect(observation: FileCollector.Observation, expectedPlaintextHash: String,
                excluded: () -> Boolean, opener: EncryptedAudioVault.Opener): Map<String, Any?> {
        requireIo(); JsonCanonical.validHash(expectedPlaintextHash)
        val p = observation.metadata; val event = p["event_id"].toString(); val request = digest(p, expectedPlaintextHash)
        val pending = port.transaction {
            preRead(observation, excluded)
            val done = committed(event, request)
            if (done != null) return@transaction null
            val old = port.query("SELECT * FROM vault2_pending WHERE event_id=? AND owner_key=?", event, owner).firstOrNull()
            if (old != null) { if (old["request_digest"] != request) fail("IDEMPOTENCY_CONFLICT"); return@transaction old }
            if (repository.receipt(event) != null) fail("UNBOUND_LEGACY_RECEIPT")
            val current = repository.listRecordings().firstOrNull { it["recording_id"] == p["recording_id"] }
            val version = if (current == null) 1L else number(current, "source_version") + if (current["audio_sha256"] == expectedPlaintextHash) 0 else 1
            val snapshot = fence(p["recording_id"].toString(), p["task_id"])
            port.execute("""INSERT INTO vault2_pending(event_id,owner_key,request_digest,metadata_json,fence_json,plaintext_sha256,plaintext_bytes,source_version,key_alias)
                VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent(), event, owner, request, JsonCanonical.encode(p), snapshot,
                expectedPlaintextHash, number(p, "byte_count"), version, alias)
            port.query("SELECT * FROM vault2_pending WHERE event_id=? AND owner_key=?", event, owner).single()
        }
        if (pending == null) {
            val done = committed(event, request)!!
            verify(reference(p["recording_id"].toString(), number(done, "source_version")))
            return done
        }
        val binding = AudioBinding(owner, p["recording_id"].toString(), number(pending, "source_version"), expectedPlaintextHash, number(p, "byte_count"))
        val ref = when {
            pending["ciphertext_sha256"] != null -> reference(pending + ("recording_id" to p["recording_id"]))
            port.query("SELECT * FROM vault2_refs WHERE recording_id=? AND source_version=? AND owner_key=?", binding.recordingId, binding.sourceVersion, owner).isNotEmpty() -> reference(binding.recordingId, binding.sourceVersion)
            else -> vault.encrypt(binding, alias, opener, token)
        }
        fault("AFTER_ENCRYPT_BEFORE_JOURNAL")
        remember(event, ref)
        fault("AFTER_REF_JOURNAL_BEFORE_ATTACH")
        // Receipt/reference reuse still requires complete AEAD/plaintext verification.
        vault.recoverReference(ref.ciphertextSha256, binding, alias, token)
        return attach(pending, observation, excluded, ref)
    }
    private fun remember(event: String, ref: VaultReference) = port.transaction {
        val old = port.query("SELECT * FROM vault2_pending WHERE event_id=? AND owner_key=?", event, owner).single()
        if (old["ciphertext_sha256"] != null && old["ciphertext_sha256"] != ref.ciphertextSha256) fail("PENDING_REFERENCE_CONFLICT")
        port.execute("UPDATE vault2_pending SET ciphertext_sha256=?,storage_bytes=? WHERE event_id=? AND owner_key=?",
            ref.ciphertextSha256, ref.storageBytes, event, owner)
    }
    private fun attach(pending: Map<String, Any?>, observation: FileCollector.Observation,
                       excluded: () -> Boolean, ref: VaultReference): Map<String, Any?> = port.transaction {
        token.check(); val p = observation.metadata; preRead(observation, excluded)
        val event = pending["event_id"].toString(); val request = pending["request_digest"].toString()
        committed(event, request)?.let { return@transaction it }
        if (fence(ref.source.recordingId, p["task_id"]) != pending["fence_json"]) fail("INTAKE_REVISION_CONFLICT")
        val observed = repository.observe(p, ref.source.plaintextSha256, now())
        if (number(observed, "source_version") != ref.source.sourceVersion) fail("VAULT_SOURCE_BINDING_MISMATCH")
        val existing = port.query("SELECT * FROM vault2_refs WHERE recording_id=? AND source_version=? AND owner_key=?",
            ref.source.recordingId, ref.source.sourceVersion, owner).firstOrNull()
        if (existing == null) port.execute("INSERT INTO vault2_refs VALUES(?,?,?,?,?,?,?,?)", ref.source.recordingId,
            ref.source.sourceVersion, owner, ref.source.plaintextSha256, ref.source.plaintextBytes, ref.keyAlias, ref.ciphertextSha256, ref.storageBytes)
        else if (reference(existing).storageRef() != ref.storageRef()) fail("IMMUTABLE_VAULT_REFERENCE")
        mark(ref, "VERIFIED")
        port.execute("INSERT INTO vault2_intake_receipts VALUES(?,?,?,?,?,?)", event, owner, request,
            ref.source.recordingId, ref.source.sourceVersion, ref.ciphertextSha256)
        port.execute("UPDATE vault2_pending SET completed=1 WHERE event_id=? AND owner_key=?", event, owner)
        token.check(); observed
    }
    /** Recover a journaled ref, or scan only this private vault for an authenticated rename orphan.
     * Never deletes sealed blobs; never changes the captured DB fence to make a stale job fit.
     */
    fun recoverPending(event: String, selectedRoot: String, childVerified: Boolean, identityVerified: Boolean,
                       excluded: () -> Boolean): Map<String, Any?> {
        requireIo()
        val pending = port.query("SELECT * FROM vault2_pending WHERE event_id=? AND owner_key=?", event, owner).singleOrNull() ?: fail("PENDING_INTAKE_NOT_FOUND")
        val p = JsonBridge.parseObject(pending["metadata_json"].toString())
        // Caller must reconfirm selection/identity. This pilot has a fixed synthetic fixture.
        val observation = FileCollector.Observation(p, selectedRoot, childVerified, identityVerified)
        preRead(observation, excluded)
        val binding = AudioBinding(owner, p["recording_id"].toString(), number(pending, "source_version"), pending["plaintext_sha256"].toString(), number(pending, "plaintext_bytes"))
        val ref = if (pending["ciphertext_sha256"] != null) vault.recoverReference(pending["ciphertext_sha256"].toString(), binding, alias, token)
        else {
            val matches = mutableListOf<VaultReference>()
            Files.list(vaultRoot).use { stream ->
                val files = stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.vault")) }.limit(1001).toArray()
                if (files.size > 1000) fail("ORPHAN_REVIEW_REQUIRED")
                files.map { it as Path }.sorted().forEach { file ->
                    token.check()
                    try { matches += vault.recoverReference(file.fileName.toString().removeSuffix(".vault"), binding, alias, token) }
                    catch (error: VaultException) { if (error.code != "BINDING_MISMATCH") throw error }
                }
            }
            matches.firstOrNull() ?: fail("ORPHAN_REFERENCE_NOT_FOUND")
        }
        remember(event, ref)
        return attach(pending, observation, excluded, ref)
    }
    /** Real external auth is checked BEFORE any key, read, claim, or decrypted release. */
    internal fun withReadOnlySourceAudio(recording:String,version:Long,gate:(AudioBinding)->Boolean,consumer:(InputStream)->Unit) {
        requireIo();token.check()
        val ref=reference(recording,version)
        vault.withVerifiedAudio(ref,token,gate) { verified ->
            verified.openStream().use { stream ->
                consumer(object:InputStream() {
                    override fun read():Int { token.check();check(gate(verified.source)) { "NATIVE_READ_ONLY_LEASE_BLOCKED" };return stream.read() }
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int { token.check();check(gate(verified.source)) { "NATIVE_READ_ONLY_LEASE_BLOCKED" };return stream.read(bytes,offset,length) }
                })
                token.check();check(gate(verified.source)) { "NATIVE_READ_ONLY_LEASE_BLOCKED" }
            }
        }
    }
    fun cancel() = token.cancel()
    fun pendingEvents(): List<String> {
        requireIo()
        return port.query("SELECT event_id FROM vault2_pending WHERE owner_key=? AND completed=0 ORDER BY event_id", owner).map { it["event_id"].toString() }
    }
    override fun close() { cancel(); vault.close() }
}
