package kr.callbrief

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.co.callsummary.core.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.vault.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real host AES-GCM + host file/directory fsync/rename + Android native SQLite shadow.
 * AndroidKeyStore is explicitly replaced by a PUBLIC FIXTURE KEY, located only in test sources.
 * AndroidVaultFactory's Os directory fsync is replaced by a host FileChannel implementation.
 * These are host API35 tests, NOT Android device/Keystore, power loss, OEM backup or STT tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyntheticPilotIntegrationTest : ProtectedTextHostTestBase() {
    class HostKeys : VaultKeys {
        @Volatile var state = VaultKeys.State.MISSING
        @Volatile var wrong = false
        @Volatile var provisionFailure = false
        val provisions = AtomicInteger()
        val accesses = AtomicInteger()
        fun provision(alias: String) {
            check(state == VaultKeys.State.MISSING)
            if(provisionFailure) throw VaultException("KEY_SERVICE_UNAVAILABLE")
            provisions.incrementAndGet(); state = VaultKeys.State.AVAILABLE
        }
        override fun state(alias: String): VaultKeys.State { accesses.incrementAndGet(); return state }
        private fun checkState() {
            accesses.incrementAndGet()
            when (state) {
                VaultKeys.State.MISSING -> throw VaultException("KEY_UNAVAILABLE")
                VaultKeys.State.INVALIDATED -> throw VaultException("KEY_INVALIDATED")
                VaultKeys.State.LOCKED -> throw VaultException("KEY_LOCKED")
                VaultKeys.State.SERVICE_UNAVAILABLE -> throw VaultException("KEY_SERVICE_UNAVAILABLE")
                else -> Unit
            }
        }
        private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
            checkState()
            val key = SecretKeySpec(ByteArray(32) { if (wrong) 9 else 7 }, "AES")
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
            }
        }
        override fun encrypt(alias: String) = cipher(Cipher.ENCRYPT_MODE)
        override fun decrypt(alias: String, iv: ByteArray) = cipher(Cipher.DECRYPT_MODE, iv)
    }
    companion object {
        @Volatile lateinit var fixture: HostKeys
        fun hostPilot(context: Context, fault: (String) -> Unit = {}, vaultFault: (String) -> Unit = {}) =
            SyntheticLocalPilot(context, { privateContext ->
                val root = privateContext.noBackupFilesDir.toPath().resolve("audio-vault-v2")
                val vault = EncryptedAudioVault(root, fixture, { directory ->
                    FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                }, vaultFault)
                SyntheticLocalPilot.VaultAccess(vault, root, fixture::provision)
            }, fault)
    }
    class HostPilotActivity : SyntheticPilotActivity() { override fun createPilot() = hostPilot(this) }
    private lateinit var context: Context
    private val worker = Executors.newSingleThreadExecutor()
    private fun <T> io(work: () -> T): T {
        try { return worker.submit<T> { work() }.get(20, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
    }
    @Before fun freshGeneratedOnlyStore() {
        context = RuntimeEnvironment.getApplication()
        fixture = HostKeys()
        context.deleteDatabase(SyntheticLocalPilot.DATABASE)
        // Only Robolectric's own generated fixture directory, never a user/real recording path.
        File(context.noBackupFilesDir, "synthetic-local-pilot-v2").deleteRecursively()
    }
    @After fun stopHostWorker() { worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    private fun reject(code: String, operation: () -> Unit) {
        try { operation(); fail("Expected $code") }
        catch (error: RuntimeException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(code)) }
    }
    private fun <T> db(work: (AndroidSqlPort) -> T): T =
        AndroidSqlPort(context, SyntheticLocalPilot.DATABASE, context.assets.open("call_core_schema.sql")).use(work)
    private fun rows(sql: String, vararg args: Any?) = db { it.query(sql, *args) }
    private fun execute(sql: String, vararg args: Any?) = db { it.execute(sql, *args) }
    private fun initialize(p: SyntheticLocalPilot) { p.initializeEmptyVault(); assertEquals(1, fixture.provisions.get()) }
    private fun freshReady(p: SyntheticLocalPilot) { initialize(p); p.collection() }
    private fun number(row: Map<String, Any?>, key: String) = (row[key] as Number).toLong()
    private fun sealed(root: Path) = Files.list(root).use { stream -> stream.filter { it.fileName.toString().endsWith(".vault") }.count() }
    private fun data(p: SyntheticLocalPilot): ByteArray {
        val ref = p.currentReference(); var out = byteArrayOf()
        p.store.vault.withVerifiedAudioForLocalReview(ref, p.token) { a -> out = a.openStream().readBytes() }
        return out
    }
    private fun observation(recording: String, event: String, sequence: Long = 1, time: Long? = 1791417600000L,
                            task: String? = "synthetic-vault-task", identity: Boolean = true, root: String = "content://synthetic-fixture/selected-tree") =
        FileCollector.Observation(SqlCallRepository.map(
            "event_id", event, "recording_id", recording, "owner_key", SyntheticLocalPilot.SCOPE,
            "grant_id", "synthetic-pilot-grant", "policy_revision", 1, "document_id", "document_"+recording,
            "source_seq", sequence, "display_name", "공개 합성 fixture.wav", "mime_type", "audio/wav",
            "byte_count", 52, "modified_at_ms", 1, "original_started_at_ms", time,
            "phone_group_ref", "synthetic-opaque-communication-group", "person_id", if (task == null) null else "synthetic-person",
            "company_id", null, "task_id", task), root, true, identity)

    @Test fun excludedSourceDoesNotOpenProvisionEncryptOrRegister() = io {
        hostPilot(context).use { p ->
            reject("EXCLUDED_BEFORE_READ") { p.collection(excluded = true) }
            assertEquals(0, p.openedSyntheticStreams); assertEquals(0, fixture.provisions.get())
            assertNull(p.current()); assertEquals(0, p.store.pendingEvents().size); assertEquals(0, sealed(p.store.vaultRoot))
        }
    }
    @Test fun failedFirstProvisionIsHeldThenExplicitlyResumedWithTheSameDurableIntent() = io {
        fixture.provisionFailure=true
        lateinit var expectedIntent:String
        hostPilot(context).use { p ->
            reject("KEY_SERVICE_UNAVAILABLE") { p.initializeEmptyVault() }
            expectedIntent=rows("SELECT * FROM vault2_initialization").single()["initialization_id"].toString()
            assertEquals("PENDING",rows("SELECT * FROM vault2_initialization").single()["state"])
            assertTrue(p.snapshot().canRecoverInitialization);assertFalse(p.snapshot().keyReady)
            reject("EMPTY_STORE_INITIALIZATION_REQUIRED") { p.collection() }
            assertEquals(0,p.openedSyntheticStreams);assertEquals(0L,sealed(p.store.vaultRoot))
        }
        fixture.provisionFailure=false
        hostPilot(context).use { p ->
            p.recoverEmptyInitialization();assertTrue(p.snapshot().keyReady)
            assertEquals(expectedIntent,rows("SELECT * FROM vault2_initialization").single()["initialization_id"])
            assertEquals(1,fixture.provisions.get());p.collection();p.verifyCurrent()
            reject("INITIALIZATION_RECOVERY_DENIED") { p.recoverEmptyInitialization() }
            assertEquals(1,fixture.provisions.get())
        }
    }
    @Test fun keyProvisionCompletionBeforeDatabaseAckIsAdoptedWithoutReplacingTheKey() = io {
        var inject=true
        hostPilot(context,fault={ if(inject && it=="AFTER_KEY_SETUP_BEFORE_DB_READY") { inject=false;throw IllegalStateException("SYNTHETIC_SETUP_ACK_LOST") } }).use { p ->
            reject("SYNTHETIC_SETUP_ACK_LOST") { p.initializeEmptyVault() }
            assertEquals(InitializationReport.State.READY,p.store.initializationInspection().state)
            assertEquals("PENDING",rows("SELECT * FROM vault2_initialization").single()["state"])
            assertEquals(1,fixture.provisions.get());assertFalse(p.snapshot().keyReady)
        }
        hostPilot(context).use { p ->
            p.recoverEmptyInitialization();assertTrue(p.snapshot().keyReady);assertEquals(1,fixture.provisions.get())
            p.collection();p.makeMockCandidate();p.accept(1,p.reviewSnapshot())
        }
    }
    @Test fun legacyIdentityAndUnexpectedBlobAreNeverTreatedAsAnEmptyNewKeyStore() = io {
        hostPilot(context).use { p ->
            Files.write(p.store.vaultRoot.resolve(".key-identity"),"callsummary.audio.v2.synthetic_local_pilot".toByteArray(Charsets.US_ASCII))
            assertFalse(p.snapshot().canInitialize);assertFalse(p.snapshot().canRecoverInitialization)
            reject("INITIALIZATION_REVIEW_REQUIRED") { p.initializeEmptyVault() }
            assertEquals(0,fixture.provisions.get());assertTrue(rows("SELECT * FROM vault2_initialization").isEmpty())
        }
    }
    @Test fun priorAudioAfterInterruptedSetupBlocksRecoveryAndPreservesEveryByte() = io {
        fixture.provisionFailure=true
        hostPilot(context).use { p ->
            reject("KEY_SERVICE_UNAVAILABLE") { p.initializeEmptyVault() }
            fixture.provisionFailure=false
            val orphan=p.store.vaultRoot.resolve("a".repeat(64)+".vault");val bytes=byteArrayOf(7,8,9);Files.write(orphan,bytes)
            try { p.recoverEmptyInitialization();fail("nonempty first setup must be held") } catch(expected:VaultException) { }
            assertEquals(0,fixture.provisions.get());assertArrayEquals(bytes,Files.readAllBytes(orphan));assertEquals("PENDING",rows("SELECT * FROM vault2_initialization").single()["state"])
        }
    }
    @Test fun encryptedDomainAndApprovedSummarySurviveRepositoryReopen() = io {
        lateinit var ref: VaultReference
        hostPilot(context).use { p ->
            freshReady(p); ref = p.currentReference(); p.verifyCurrent()
            assertNotEquals(ref.source.plaintextSha256, ref.ciphertextSha256)
            assertEquals(52L, ref.source.plaintextBytes); assertTrue(ref.storageBytes > 52)
            val storage = Files.readAllBytes(p.store.vaultRoot.resolve(ref.ciphertextSha256+".vault"))
            assertEquals("CALLVLT2", String(storage.copyOfRange(0, 8), Charsets.US_ASCII))
            assertTrue(rows("SELECT * FROM canonical_storage").isEmpty())
            assertEquals(ref.source.plaintextSha256, p.current()!!["audio_sha256"])
            p.makeMockCandidate(); assertTrue(p.catalog.listFiles().getJSONObject(0).isNull("summary"))
            p.accept(1, p.reviewSnapshot())
        }
        hostPilot(context).use { p ->
            assertEquals(ref.storageRef(), p.currentReference().storageRef()); p.verifyCurrent()
            assertEquals(1L, p.current()!!["user_revision"])
            assertTrue(p.catalog.listFiles().getJSONObject(0).getString("summary").startsWith("[합성 변경안]"))
            assertEquals(1, fixture.provisions.get())
        }
    }
    @Test fun renameReusesCipherButContentReturnCreatesANewBoundCipherVersion() = io {
        hostPilot(context).use { p ->
            freshReady(p); val a = p.currentReference(); val reads = p.openedSyntheticStreams
            p.collection("합성 새 이름.wav")
            assertEquals(1L, p.current()!!["source_version"]); assertEquals(a.storageRef(), p.currentReference().storageRef())
            assertEquals(reads, p.openedSyntheticStreams)
            p.collection(alternate = true); assertEquals(2L, p.current()!!["source_version"])
            p.collection(); val returned = p.currentReference()
            assertEquals(3L, p.current()!!["source_version"]); assertEquals(a.source.plaintextSha256, returned.source.plaintextSha256)
            assertNotEquals(a.ciphertextSha256, returned.ciphertextSha256); assertEquals(3, p.catalog.history(SyntheticLocalPilot.RECORDING).getJSONArray("versions").length())
            p.store.verify(a); assertEquals(3, rows("SELECT * FROM vault2_refs").size)
        }
    }
    @Test fun idempotentIntakeReusesReceiptAndRejectsDifferentPlaintextHash() = io {
        hostPilot(context).use { p ->
            freshReady(p); val bytes = data(p)
            val obs = observation("synthetic-extra", "replay_event")
            val hash = JsonCanonical.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val first = p.store.collect(obs, hash, { false }, { ByteArrayInputStream(bytes) })
            val count = sealed(p.store.vaultRoot)
            val replay = p.store.collect(obs, hash, { false }, { error("MUST_NOT_REOPEN") })
            assertEquals(first, replay); assertEquals(count, sealed(p.store.vaultRoot))
            reject("IDEMPOTENCY_CONFLICT") { p.store.collect(obs, "a".repeat(64), { false }, { error("MUST_NOT_REOPEN") }) }
        }
    }
    @Test fun manualCompletionAndOldReviewAreProtectedFromNewCandidate() = io {
        hostPilot(context).use { p ->
            freshReady(p); p.makeMockCandidate(); val old = p.reviewSnapshot()
            p.editAndComplete("[합성 직접 수정] 완료 보존", old)
            reject("ACCEPT_REVISION_CONFLICT") { p.accept(1, old) }
            p.collection(alternate = true); p.makeMockCandidate()
            val row = p.catalog.listFiles().getJSONObject(0)
            assertEquals("[합성 직접 수정] 완료 보존", row.getString("summary")); assertEquals("done", row.getString("manual_status"))
            assertEquals("USER_STATE_PRESERVED", p.latestCandidate()!!["repository_review_code"])
            assertEquals("needs_review", row.getString("status")); assertEquals(1, fixture.provisions.get())
        }
    }
    @Test fun explicitHistoricalRestoreVerifiesOldCipherAndCreatesNewUserRevision() = io {
        hostPilot(context).use { p ->
            freshReady(p); p.makeMockCandidate(); p.accept(1, p.reviewSnapshot())
            p.collection(alternate = true); p.makeMockCandidate(); p.accept(2, p.reviewSnapshot())
            val before = number(p.current()!!, "user_revision")
            p.accept(1, p.reviewSnapshot(), historical = true)
            assertEquals(before + 1, p.current()!!["user_revision"]); assertEquals(1L, p.current()!!["effective_summary_version"])
            assertEquals(2, rows("SELECT * FROM vault2_refs").size)
            assertEquals(1L, rows("SELECT restored_summary_version FROM manual_history WHERE restored_summary_version IS NOT NULL").single()["restored_summary_version"])
        }
    }
    @Test fun archiveRestoreWorksWhileMissingKeyPreservesAudioAndManualHistory() = io {
        lateinit var root: Path
        hostPilot(context).use { p ->
            freshReady(p); root = p.store.vaultRoot
            p.editAndComplete("[합성 직접 수정] 보관 유지", p.reviewSnapshot()); p.archive(true, 0)
        }
        fixture.state = VaultKeys.State.MISSING
        hostPilot(context).use { p ->
            assertEquals(EncryptedAudioVault.Status.KEY_UNAVAILABLE, p.store.status(p.currentReference()))
            p.archive(false, 1)
            reject("KEY_UNAVAILABLE") { p.verifyCurrent() }
            reject("PRIOR_REFERENCE_REVIEW_REQUIRED") { p.initializeEmptyVault() }
            assertEquals("[합성 직접 수정] 보관 유지", p.catalog.listFiles().getJSONObject(0).getString("summary"))
            assertEquals(1L, sealed(root)); assertEquals(1, fixture.provisions.get())
        }
    }
    @Test fun missingKeyBlocksCandidateAndApprovalWithoutReplacingAcceptedSummary() = io {
        hostPilot(context).use { p ->
            freshReady(p); p.makeMockCandidate(); p.accept(1, p.reviewSnapshot())
            p.collection(alternate = true); val before = p.current()!!; val files = sealed(p.store.vaultRoot)
            fixture.state = VaultKeys.State.MISSING
            reject("KEY_UNAVAILABLE") { p.makeMockCandidate() }
            reject("KEY_UNAVAILABLE") { p.accept(1, p.reviewSnapshot(), historical = true) }
            assertEquals(before["user_revision"], p.current()!!["user_revision"]); assertEquals(before["effective_summary_version"], p.current()!!["effective_summary_version"])
            assertEquals(files, sealed(p.store.vaultRoot)); assertEquals(1, fixture.provisions.get())
        }
    }
    @Test fun ciphertextHashCannotBeRegisteredAsThePlaintextSourceHash() = io {
        hostPilot(context).use { p ->
            freshReady(p); val ref=p.currentReference(); val before=p.current()!!
            val observed=observation("synthetic-domain-check","domain-check")
            db { SqlCallRepository(it,SyntheticLocalPilot.SCOPE).observe(observed.metadata,ref.source.plaintextSha256,System.currentTimeMillis()) }
            reject("VAULT_SOURCE_BINDING_MISMATCH") {
                execute("INSERT INTO vault2_refs(recording_id,source_version,owner_key,plaintext_sha256,plaintext_bytes,key_alias,ciphertext_sha256,storage_bytes) VALUES(?,?,?,?,?,?,?,?)",
                    "synthetic-domain-check",1,SyntheticLocalPilot.SCOPE,ref.ciphertextSha256,ref.source.plaintextBytes,ref.keyAlias,"a".repeat(64),ref.storageBytes)
            }
            assertEquals(1,rows("SELECT * FROM vault2_refs").size);assertEquals(before,p.current())
            assertTrue(rows("SELECT * FROM canonical_storage").isEmpty())
        }
    }
    @Test fun rawSqlReplaceCannotChangeImmutableReferencesOrReceiptsWithRecursiveTriggersDisabled() = io {
        hostPilot(context).use { p ->
            freshReady(p);execute("PRAGMA recursive_triggers=OFF")
            val ref=p.currentReference();val receipt=rows("SELECT * FROM vault2_intake_receipts").single()
            reject("IMMUTABLE_VAULT_REFERENCE") {
                execute("INSERT OR REPLACE INTO vault2_refs(recording_id,source_version,owner_key,plaintext_sha256,plaintext_bytes,key_alias,ciphertext_sha256,storage_bytes) VALUES(?,?,?,?,?,?,?,?)",
                    ref.source.recordingId,ref.source.sourceVersion,SyntheticLocalPilot.SCOPE,ref.source.plaintextSha256,ref.source.plaintextBytes,"changed_alias",ref.ciphertextSha256,ref.storageBytes)
            }
            reject("IMMUTABLE_VAULT_RECEIPT") {
                execute("INSERT OR REPLACE INTO vault2_intake_receipts(event_id,owner_key,request_digest,recording_id,source_version,ciphertext_sha256) VALUES(?,?,?,?,?,?)",
                    receipt["event_id"],receipt["owner_key"],"b".repeat(64),receipt["recording_id"],receipt["source_version"],receipt["ciphertext_sha256"])
            }
            // Exact duplicate preserves the row and FK relationships without a replacement delete.
            execute("INSERT OR REPLACE INTO vault2_intake_receipts(event_id,owner_key,request_digest,recording_id,source_version,ciphertext_sha256) VALUES(?,?,?,?,?,?)",
                receipt["event_id"],receipt["owner_key"],receipt["request_digest"],receipt["recording_id"],receipt["source_version"],receipt["ciphertext_sha256"])
            assertEquals(receipt,rows("SELECT * FROM vault2_intake_receipts").single());assertEquals(ref.keyAlias,p.currentReference().keyAlias)
        }
    }
    @Test fun rawSqlReplaceCannotRebindInitializationOrDispatchReservation() = io {
        hostPilot(context).use { p ->
            freshReady(p);val b=p.store.claim(SyntheticLocalPilot.RECORDING)
            val setup=rows("SELECT * FROM vault2_initialization").single();execute("PRAGMA recursive_triggers=OFF")
            reject("IMMUTABLE_INITIALIZATION_INTENT") {
                execute("INSERT OR REPLACE INTO vault2_initialization(owner_key,initialization_id,key_alias,state) VALUES(?,?,?,'READY')",setup["owner_key"],"different_intent",setup["key_alias"])
            }
            reject("IMMUTABLE_DISPATCH_RESERVATION") {
                execute("INSERT OR REPLACE INTO vault2_dispatch_reservations(processing_id,recording_id,owner_key,source_version,lease_epoch,visibility_revision) VALUES(?,?,?,?,?,?)",
                    b.text("processing_id"),b.text("recording_id"),SyntheticLocalPilot.SCOPE,b.number("source_version"),b.number("lease_epoch"),99)
            }
            assertEquals(setup,rows("SELECT * FROM vault2_initialization").single())
            reject("IMMUTABLE_INITIALIZATION_STATE") { execute("UPDATE vault2_initialization SET state='PENDING'") }
            assertEquals(setup,rows("SELECT * FROM vault2_initialization").single())
        }
    }
    @Test fun rawSqlReplaceCannotRewriteTheCapturedIntakeFenceOrResetCompletion() = io {
        hostPilot(context).use { p ->
            freshReady(p);execute("PRAGMA recursive_triggers=OFF")
            val before=rows("SELECT * FROM vault2_pending").single()
            reject("IMMUTABLE_INTAKE_SNAPSHOT") {
                execute("INSERT OR REPLACE INTO vault2_pending SELECT event_id,owner_key,request_digest,metadata_json,?,plaintext_sha256,plaintext_bytes,source_version,key_alias,ciphertext_sha256,storage_bytes,completed FROM vault2_pending", "{\"unsafe\":true}")
            }
            reject("IMMUTABLE_INTAKE_SNAPSHOT") {
                execute("INSERT OR REPLACE INTO vault2_pending SELECT event_id,owner_key,request_digest,metadata_json,fence_json,plaintext_sha256,plaintext_bytes,source_version,key_alias,ciphertext_sha256,storage_bytes,0 FROM vault2_pending")
            }
            execute("INSERT OR REPLACE INTO vault2_pending SELECT * FROM vault2_pending")
            assertEquals(before,rows("SELECT * FROM vault2_pending").single())
        }
    }
    @Test fun manualEditAfterWorkerReplyPreservesCandidateAsReviewWithoutOverwritingCompletion() = io {
        lateinit var p:SyntheticLocalPilot;var inject=false
        p=hostPilot(context,fault={ if(inject && it=="BEFORE_CANDIDATE_DB_COMMIT") {
            inject=false;p.editAndComplete("[합성 직접 수정] 완료를 보존해요.",p.reviewSnapshot())
        } })
        p.use {
            freshReady(p);inject=true;val result=p.makeMockCandidate()
            assertEquals("REVIEW_REQUIRED",result["outcome"])
            assertEquals("USER_STATE_PRESERVED",p.latestCandidate()!!["repository_review_code"])
            assertNull(p.current()!!["effective_summary_version"])
            val row=p.catalog.listFiles().getJSONObject(0);assertEquals("done",row.getString("manual_status"))
            assertEquals("[합성 직접 수정] 완료를 보존해요.",row.getString("summary"));assertEquals(1,rows("SELECT * FROM summary_versions").size)
        }
    }
    @Test fun archiveAndRestoreInvalidateAnAlreadyReleasedSyntheticLeaseWithoutDeletingAudio() = io {
        hostPilot(context).use { p ->
            freshReady(p);val binding=p.store.claim(SyntheticLocalPilot.RECORDING);val ref=p.currentReference()
            reject("STALE_RELEASE_BINDING") { p.store.withSyntheticAnalysisAudio(binding) { input ->
                assertTrue(input.read()>=0);p.archive(true,0);input.read()
            } }
            reject("ARCHIVED_PROCESSING_HELD") { p.store.claim(SyntheticLocalPilot.RECORDING) }
            p.archive(false,1)
            reject("STALE_RELEASE_BINDING") { p.store.withSyntheticAnalysisAudio(binding) { error("must not release an old visibility revision") } }
            assertEquals(ref.storageRef(),p.currentReference().storageRef());p.verifyCurrent()
            assertTrue(rows("SELECT * FROM summary_versions").isEmpty());assertEquals(1L,sealed(p.store.vaultRoot))
        }
    }
    @Test fun archiveRestoreBetweenReviewAndApprovalRequiresAnExplicitNewReview() = io {
        hostPilot(context).use { p ->
            freshReady(p);p.makeMockCandidate();val old=p.reviewSnapshot()
            p.archive(true,0);reject("ARCHIVED_PROCESSING_HELD") { p.accept(1,old) }
            p.archive(false,1);reject("ACCEPT_REVISION_CONFLICT") { p.accept(1,old) }
            assertNull(p.current()!!["effective_summary_version"])
            p.accept(1,p.reviewSnapshot());assertEquals(1L,p.current()!!["effective_summary_version"])
        }
    }
    @Test fun unknownTimeAndUnlinkedIdentityRemainSeparateHeldStates() = io {
        hostPilot(context).use { p ->
            freshReady(p); val bytes = data(p); val hash = p.currentReference().source.plaintextSha256
            p.store.collect(observation("synthetic-time-unknown", "unknown_time", time = null), hash, { false }, { ByteArrayInputStream(bytes) })
            p.store.collect(observation("synthetic-unlinked", "unlinked", task = null), hash, { false }, { ByteArrayInputStream(bytes) })
            val list = p.catalog.listFiles(); val unknown = (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("file_id")=="synthetic-time-unknown" }
            assertTrue(unknown.isNull("original_date")); assertEquals("unknown_time", unknown.getString("status"))
            val unlinked = rows("SELECT * FROM processing WHERE recording_id='synthetic-unlinked'").single(); assertEquals("HELD_UNLINKED", unlinked["state"])
            reject("IDENTITY_REVIEW_REQUIRED") { p.store.collect(observation("synthetic-unverified", "unverified", identity=false), hash, { false }, { error("MUST_NOT_OPEN") }) }
            assertTrue(rows("SELECT * FROM recordings WHERE recording_id='synthetic-unverified'").isEmpty())
        }
    }
    @Test fun wrongKeyAndMutatedCipherNeverReachAnAnalysisConsumer() = io {
        hostPilot(context).use { p ->
            freshReady(p); val ref = p.currentReference()
            fixture.wrong = true
            reject("AUTHENTICATION_FAILED") { p.verifyCurrent() }
            fixture.wrong = false
            val file = p.store.vaultRoot.resolve(ref.ciphertextSha256+".vault")
            val bytes = Files.readAllBytes(file); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); Files.write(file, bytes)
            reject("CORRUPT_STORAGE") { p.store.claim(SyntheticLocalPilot.RECORDING) }
            assertEquals(0, rows("SELECT * FROM summary_versions").size); assertTrue(Files.exists(file))
        }
    }
    @Test fun keyInvalidationLockAndServiceFailureAreHeldWithoutProvisioning() = io {
        hostPilot(context).use { p ->
            freshReady(p)
            listOf(VaultKeys.State.INVALIDATED to "KEY_INVALIDATED", VaultKeys.State.LOCKED to "KEY_LOCKED",
                VaultKeys.State.SERVICE_UNAVAILABLE to "KEY_SERVICE_UNAVAILABLE").forEach { (state, code) ->
                fixture.state = state; reject(code) { p.verifyCurrent() }
                assertEquals(1L, sealed(p.store.vaultRoot)); assertEquals(1, fixture.provisions.get())
            }
        }
    }
    @Test fun disconnectedNativeRequestDoesNotConsultKeysReadOrClaim() = io {
        hostPilot(context).use { p ->
            freshReady(p); val accesses = fixture.accesses.get()
            reject("NATIVE_AUTH_NOT_CONNECTED") { p.store.requestNativeAnalysis(SyntheticLocalPilot.RECORDING) }
            assertEquals(accesses, fixture.accesses.get()); assertEquals("PENDING", p.current()!!["state"])
        }
    }
    @Test fun mismatchedSourceBytesLeaveAnEncryptedPendingIntentAndNoPlaintextFallback() = io {
        hostPilot(context).use { p ->
            freshReady(p)
            val originalBytes = data(p)
            val wrong = observation("synthetic-mismatched", "mismatched")
            reject("SOURCE_BINDING_MISMATCH") { p.store.collect(wrong, "f".repeat(64), { false }, { ByteArrayInputStream(originalBytes) }) }
            assertTrue(rows("SELECT * FROM recordings WHERE recording_id='synthetic-mismatched'").isEmpty())
            assertEquals(1L, sealed(p.store.vaultRoot))
            assertTrue(Files.list(p.store.vaultRoot).use { it.noneMatch { file -> file.fileName.toString().endsWith(".audio") || file.fileName.toString().startsWith(".stage-") } })
        }
    }
    @Test fun durableRefJournalRecoversAfterAdapterStopsAndReopens() = io {
        hostPilot(context, fault = { if (it=="AFTER_REF_JOURNAL_BEFORE_ATTACH") throw IllegalStateException("SIMULATED_ADAPTER_STOP") }).use { p ->
            initialize(p); reject("SIMULATED_ADAPTER_STOP") { p.collection() }; assertNull(p.current())
        }
        hostPilot(context).use { p ->
            assertEquals(1, p.recoverPendingSynthetic()); p.verifyCurrent()
            assertEquals(1L, sealed(p.store.vaultRoot)); assertEquals(1, fixture.provisions.get())
            assertEquals(1, rows("SELECT * FROM vault2_intake_receipts").size)
        }
    }
    @Test fun authenticatedRenameOrphanRecoversWhenRefJournalAckWasLost() = io {
        hostPilot(context, fault = { if (it=="AFTER_ENCRYPT_BEFORE_JOURNAL") throw IllegalStateException("SIMULATED_ACK_LOSS") }).use { p ->
            initialize(p); reject("SIMULATED_ACK_LOSS") { p.collection() }; assertNull(p.current())
            assertNull(rows("SELECT ciphertext_sha256 FROM vault2_pending").single()["ciphertext_sha256"])
        }
        hostPilot(context).use { p ->
            assertEquals(1, p.recoverPendingSynthetic()); p.verifyCurrent()
            assertEquals(1L, sealed(p.store.vaultRoot)); assertEquals(1, fixture.provisions.get())
        }
    }
    @Test fun revisionChangeBetweenEncryptionAndAttachmentPreservesUserAndOrphan() = io {
        lateinit var p: SyntheticLocalPilot; var inject = false
        p = hostPilot(context, fault = {
            if (inject && it=="AFTER_REF_JOURNAL_BEFORE_ATTACH") { inject=false; p.editAndComplete("[합성 직접 수정] 경합 보존", p.reviewSnapshot()) }
        })
        p.use {
            freshReady(p); inject=true; reject("INTAKE_REVISION_CONFLICT") { p.collection(alternate=true) }
            assertEquals(1L, p.current()!!["source_version"])
            assertEquals("[합성 직접 수정] 경합 보존", p.catalog.listFiles().getJSONObject(0).getString("summary"))
            assertEquals(2L, sealed(p.store.vaultRoot)); reject("INTAKE_REVISION_CONFLICT") { p.recoverPendingSynthetic() }
        }
    }
    @Test fun exclusionPolicyChangedAfterEncryptionStopsFinalAttachment() = io {
        hostPilot(context).use { p ->
            freshReady(p); val bytes = data(p); val hash = p.currentReference().source.plaintextSha256
            var excluded = false
            reject("EXCLUDED_BEFORE_READ") {
                p.store.collect(observation("synthetic-exclusion-race", "exclusion_race"), hash, { excluded }, {
                    excluded=true; ByteArrayInputStream(bytes)
                })
            }
            assertTrue(rows("SELECT * FROM recordings WHERE recording_id='synthetic-exclusion-race'").isEmpty())
            assertEquals(2L, sealed(p.store.vaultRoot))
        }
    }
    @Test fun revokedFolderAfterEncryptionStopsCommitAndRetainsEncryptedBlob() = io {
        lateinit var p: SyntheticLocalPilot; var inject=false
        p = hostPilot(context, fault = { if (inject && it=="AFTER_REF_JOURNAL_BEFORE_ATTACH") {
            inject=false; execute("UPDATE folder_grants SET active=0 WHERE grant_id='synthetic-pilot-grant'")
        } })
        p.use {
            freshReady(p); inject=true; reject("FOLDER_POLICY_CHANGED") { p.collection(alternate=true) }
            assertEquals(1L, p.current()!!["source_version"]); assertEquals(2L, sealed(p.store.vaultRoot))
        }
    }
    @Test fun manualChangeAtReleaseGatePreventsConsumerFromReceivingAnyBytes() = io {
        lateinit var p: SyntheticLocalPilot; var inject=false; var consumers=0
        p = hostPilot(context, vaultFault = { if (inject && it=="BEFORE_DELIVERY") {
            inject=false; p.editAndComplete("[합성 직접 수정] 전달 경합 보존", p.reviewSnapshot())
        } })
        p.use {
            freshReady(p); val binding = p.store.claim(SyntheticLocalPilot.RECORDING); inject=true
            reject("STALE_RELEASE_BINDING") { p.store.withSyntheticAnalysisAudio(binding) { consumers++; it.read() } }
            assertEquals(0, consumers); assertEquals(0, rows("SELECT * FROM summary_versions").size)
        }
    }
    @Test fun leaseExpiryAndManualRevisionFenceEveryStreamRead() = io {
        hostPilot(context).use { p ->
            freshReady(p); val b = p.store.claim(SyntheticLocalPilot.RECORDING)
            reject("STALE_RELEASE_BINDING") {
                p.store.withSyntheticAnalysisAudio(b) { input ->
                    assertTrue(input.read() >= 0)
                    execute("UPDATE processing SET lease_until_ms=1 WHERE recording_id=? AND source_version=?", SyntheticLocalPilot.RECORDING, 1)
                    input.read()
                }
            }
            assertEquals(0, rows("SELECT * FROM summary_versions").size)
        }
    }
    @Test fun manualRevisionChangedBetweenStreamReadsStopsSubsequentRelease() = io {
        hostPilot(context).use { p ->
            freshReady(p); val b = p.store.claim(SyntheticLocalPilot.RECORDING)
            reject("STALE_RELEASE_BINDING") {
                p.store.withSyntheticAnalysisAudio(b) { input ->
                    assertTrue(input.read() >= 0); p.editAndComplete("[합성 직접 수정] 읽기 경합 보존", p.reviewSnapshot()); input.read()
                }
            }
            assertEquals("[합성 직접 수정] 읽기 경합 보존", p.catalog.listFiles().getJSONObject(0).getString("summary"))
        }
    }
    @Test fun cancellationRejectsTheNextReadAndCandidateCommit() = io {
        hostPilot(context).use { p ->
            freshReady(p); val b = p.store.claim(SyntheticLocalPilot.RECORDING)
            reject("CANCELLED") { p.store.withSyntheticAnalysisAudio(b) { input -> input.read(); p.cancel(); input.read() } }
            reject("CANCELLED") { p.store.storeCandidate(b, emptyMap(), emptyMap()) }
            assertEquals(0, rows("SELECT * FROM summary_versions").size); assertEquals(1L, sealed(p.store.vaultRoot))
        }
    }
    @Test fun lostKeyBeforeResultCommitPreventsStorageAndReferenceMutation() = io {
        hostPilot(context).use { p ->
            freshReady(p); val b = p.store.claim(SyntheticLocalPilot.RECORDING)
            p.store.withSyntheticAnalysisAudio(b) { it.readBytes() }
            fixture.state = VaultKeys.State.MISSING
            reject("KEY_UNAVAILABLE") { p.store.storeCandidate(b, emptyMap(), emptyMap()) }
            assertEquals(0, rows("SELECT * FROM summary_versions").size)
            reject("IMMUTABLE_VAULT_REFERENCE") { execute("UPDATE vault2_refs SET ciphertext_sha256=?", "0".repeat(64)) }
            assertEquals(1, rows("SELECT * FROM vault2_refs").size)
        }
    }
    @Test fun mainThreadCannotInitializeTheEncryptedPilot() {
        reject("VAULT_IO_THREAD_REQUIRED") { hostPilot(context) }
        assertEquals(0, fixture.provisions.get())
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun pump(activity: SyntheticPilotActivity) {
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val ready = descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString()=="다시 읽기" && it.isEnabled }
            if (ready) return
            if (System.nanoTime()>end) fail("Native IO did not settle")
            Thread.sleep(5)
        }
    }
    private fun click(activity: SyntheticPilotActivity, label: String) {
        pump(activity)
        val button = descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString()==label }
        assertTrue(label, button.isEnabled); assertTrue(button.performClick()); shadowOf(Looper.getMainLooper()).idle(); pump(activity)
    }
    private fun snapshot(activity: SyntheticPilotActivity, name: String) {
        pump(activity)
        val view=activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY))
        view.layout(0,0,822,1782)
        val bitmap=Bitmap.createBitmap(822,1782,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val folder=File("build/reports/ui-native").apply { mkdirs() }
        File(folder,name+".png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
    @Test fun nativeButtonsInitializeEncryptReviewApproveArchiveAndReopen() {
        val controller=Robolectric.buildActivity(HostPilotActivity::class.java).setup();val activity=controller.get();pump(activity)
        snapshot(activity,"vault2-empty")
        click(activity,"사전 제외 차단 확인");click(activity,"새 합성 저장소 키 초기화");click(activity,"합성 파일 가져오기")
        click(activity,"mock 변경안 만들기");click(activity,"변경안 검토")
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle();pump(activity);snapshot(activity,"vault2-approved")
        click(activity,"직접 수정·완료")
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle();pump(activity)
        click(activity,"합성 항목 보관");snapshot(activity,"vault2-archived");controller.pause().stop().destroy()
        val restarted=Robolectric.buildActivity(HostPilotActivity::class.java).setup();pump(restarted.get());click(restarted.get(),"합성 항목 복원")
        restarted.pause().stop().destroy()
        io { hostPilot(context).use { p ->
            assertEquals(0L,p.archiveState()["archived"])
            assertEquals("[합성 직접 수정] 내가 확인한 완료 내용을 보존해요.",p.catalog.listFiles().getJSONObject(0).getString("summary"))
            p.verifyCurrent();assertEquals(1,fixture.provisions.get())
        } }
    }
    @Test fun pilotContentAndReturnButtonRespectSuppliedSystemBarInsets() {
        val controller=Robolectric.buildActivity(HostPilotActivity::class.java).setup()
        val activity=controller.get();pump(activity)
        val scroll=activity.window.decorView.findViewWithTag<android.widget.ScrollView>("synthetic_pilot_scroll")
        val insets=android.view.WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0,48,0,60)).build()
        scroll.dispatchApplyWindowInsets(insets)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));scroll.layout(0,0,822,1782)
        assertEquals(48,scroll.paddingTop);assertEquals(60,scroll.paddingBottom)
        val first=descendants(scroll).filterIsInstance<TextView>().first()
        val bounds=android.graphics.Rect();first.getDrawingRect(bounds);scroll.offsetDescendantRectToMyCoords(first,bounds)
        assertTrue(bounds.top>=48)
        val last=descendants(scroll).filterIsInstance<Button>().single { it.text.toString()=="4탭으로 돌아가기" }
        scroll.scrollTo(0,100000)
        last.getDrawingRect(bounds);scroll.offsetDescendantRectToMyCoords(last,bounds)
        assertTrue(bounds.bottom-scroll.scrollY<=1782-60)
        controller.pause().stop().destroy()
    }
    @Test fun queuedDialogApprovalIsFencedByActivityDestruction() {
        val controller=Robolectric.buildActivity(HostPilotActivity::class.java).setup();val activity=controller.get();pump(activity)
        click(activity,"새 합성 저장소 키 초기화");click(activity,"합성 파일 가져오기");click(activity,"mock 변경안 만들기");click(activity,"변경안 검토")
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle()
        io { hostPilot(context).use { p -> assertNull(p.current()!!["effective_summary_version"]);assertEquals(1L,sealed(p.store.vaultRoot)) } }
    }
    @Test fun reopenedNativeScreenShowsMissingKeyWithoutHidingManualHistory() {
        io { hostPilot(context).use { p -> freshReady(p);p.editAndComplete("[합성 직접 수정] 키 소실에도 기록 보존",p.reviewSnapshot()) } }
        fixture.state=VaultKeys.State.MISSING
        val controller=Robolectric.buildActivity(HostPilotActivity::class.java).setup();val activity=controller.get();pump(activity)
        val texts=descendants(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(texts.contains("암호화 키를 찾을 수 없어"));assertTrue(texts.contains("키 소실에도 기록 보존"))
        snapshot(activity,"vault2-missing-key");click(activity,"암호화 원음 검증")
        val after=descendants(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(after.contains("분석·승인을 보류"));controller.pause().stop().destroy()
        assertEquals(1,fixture.provisions.get())
    }
}
