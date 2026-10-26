package kr.callbrief

import android.content.Context
import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.callbrief.kakao.core.DeliveryResult
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Protected-store integration with independently reviewed v3 UTC/backlog/epoch; host only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DailyProtectedPortPreparationTest : ProtectedTextHostTestBase() {
    private lateinit var context: Context
    private val leases = mutableListOf<DailyReportLedger>()
    private val profile = "synthetic_daily_storage_profile"
    private val zone = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.parse("2026-10-10")
    private val now = Instant.parse("2026-10-10T21:00:00Z")
    private val canary = "SYNTHETIC_REPORT_BODY_ONLY_6EC981"
    private class FixtureCipher : ReportPayloadCipher {
        private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        var failSeal = false
        override fun seal(text: String, aad: String, hasExistingPayload: Boolean): ByteArray {
            check(!failSeal) { "SYNTHETIC_PAYLOAD_FAILURE" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key); c.updateAAD(aad.toByteArray())
            return c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        }
        override fun open(bytes: ByteArray, aad: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            c.updateAAD(aad.toByteArray())
            return String(c.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    private val fixtureCipher = FixtureCipher()
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Only Robolectric's synthetic sandbox, never an installed user's data.
        context.deleteDatabase(DailyReportLedger.DATABASE)
        context.deleteDatabase(GeneralLocalStore.DATABASE)
    }
    @After fun cleanup() {
        leases.reversed().forEach { it.close() }
        context.deleteDatabase(DailyReportLedger.DATABASE)
        context.deleteDatabase(GeneralLocalStore.DATABASE)
    }
    private fun ledger(c: Context = context): DailyReportLedger =
        DailyReportLedger(c, profile, fixtureCipher).also { leases += it }
    private fun ready() = DailyReportInput(ReportReadiness.REVIEWED, listOf(ReviewedReportSource(
        "SYN-SOURCE-1", "a".repeat(64), 1, 1, DeliveryCoordinator.sha256(canary),
        "SYN-ACCEPT-1", 1, 1, 1, canary)), 1, 0, DeliveryCoordinator.sha256("synthetic-policy"))
    private fun prepare(l: DailyReportLedger): String {
        val config = l.setEnabled(true, now, zone)
        val row = l.ensure(ReportWindow(day, zone), config.generation)
        l.freeze(row.id, ready(), ready().basisHash, canary, true, config.generation)
        return row.id
    }
    private fun digest(f: File) = kr.callagent.drive.DriveRelay.sha256(f.readBytes())
    private fun source(c: Context = context) = CanonicalReviewedDailySource(c) { JSONObject() }
    private fun noDiskText(vararg text: String) {
        context.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val decoded = f.readBytes().toString(Charsets.ISO_8859_1)
            text.forEach { assertFalse("plaintext canary in ${f.name}", decoded.contains(it)) }
        }
        assertFalse(context.getDatabasePath(DailyReportLedger.DATABASE).exists())
        assertFalse(File(context.getDatabasePath(DailyReportLedger.DATABASE).path + "-wal").exists())
    }

    @Test fun P01ReportAndStateReopenFromSealedStoreOnly() {
        val l = ledger(); val id = prepare(l); l.close()
        val reopened = ledger()
        assertEquals(canary, reopened.text(id)); assertEquals(ReportDelivery.SEND_WAITING, reopened.get(id)?.delivery)
        noDiskText(canary, "검토한", profile, "SYN-ACCEPT-1")
    }
    @Test fun P02CursorAndZoneMutationIsDurable() {
        val l = ledger(); l.setEnabled(true, now, zone)
        l.setCursor(day.minusDays(3), ZoneId.of("UTC")); l.close()
        val saved = ledger().config(); assertEquals(day.minusDays(3), saved.cursor); assertEquals("UTC", saved.zone)
    }
    @Test fun P03WaitingMetadataAlsoSealedAndDurable() {
        val l = ledger(); val id = prepare(l); val note = "SYN_PRIVATE_WAIT_NOTE_3981"
        l.waiting(id, note); l.close()
        assertEquals(note, ledger().get(id)?.note); noDiskText(note, canary)
    }
    @Test fun P04UncertainClaimRecoverySurvivesReopen() {
        val l = ledger(); val id = prepare(l)
        val env = ReportEnvironment(now, zone, elapsed = 1000, boot = 1)
        assertNotNull(l.claim(id, ready(), env, l.config().generation))
        l.recover(env.copy(boot = 2)); l.close()
        val row = ledger().get(id)!!
        assertEquals(ReportDelivery.UNKNOWN, row.delivery); assertEquals(1, row.attempts)
    }
    @Test fun P05FrozenPayloadCannotBeReplacedAndPreservesCipher() {
        val l = ledger(); val id = prepare(l); val file = ProtectedDatabase.file(context, DailyReportLedger.DATABASE)
        val before = digest(file)
        assertTrue(runCatching { l.freeze(id, ready(), ready().basisHash, "OTHER_SYN_BODY", true, l.config().generation) }.isFailure)
        assertEquals(before, digest(file)); assertEquals(canary, l.text(id))
    }
    @Test fun P06PayloadFailureRollsBackWithoutPartialSavedState() {
        val l = ledger(); val config = l.setEnabled(true, now, zone)
        val id = l.ensure(ReportWindow(day, zone), config.generation).id
        val file = ProtectedDatabase.file(context, DailyReportLedger.DATABASE); val before = digest(file)
        fixtureCipher.failSeal = true
        assertTrue(runCatching { l.freeze(id, ready(), ready().basisHash, canary, true, config.generation) }.isFailure)
        assertEquals(before, digest(file)); assertEquals(ReportDelivery.NOT_STORED, l.get(id)?.delivery)
    }
    @Test fun P07LostStoreKeyDoesNotRecreateOrOverwrite() {
        val l = ledger(); prepare(l); l.close(); val file = ProtectedDatabase.file(context, DailyReportLedger.DATABASE)
        val before = digest(file); val creates = ShadowTextKeys.CREATES.get(); ShadowTextKeys.KEYS.clear()
        val error = runCatching { ledger() }.exceptionOrNull()
        assertEquals("TEXT_STORE_KEY_UNAVAILABLE", error?.message)
        assertEquals(creates, ShadowTextKeys.CREATES.get()); assertEquals(before, digest(file))
    }
    @Test fun P08ActiveKeyLossBlocksMetadataWriteBeforeMutation() {
        val l = ledger(); val id = prepare(l); val file = ProtectedDatabase.file(context, DailyReportLedger.DATABASE)
        val before = digest(file); val creates = ShadowTextKeys.CREATES.get(); ShadowTextKeys.KEYS.clear()
        assertTrue(runCatching { l.waiting(id, "NEW_SYN_NOTE") }.isFailure)
        assertEquals(creates, ShadowTextKeys.CREATES.get()); assertEquals(before, digest(file))
    }
    @Test fun P09WrongKeyCannotAuthenticateExistingReport() {
        val l = ledger(); prepare(l); l.close(); val file = ProtectedDatabase.file(context, DailyReportLedger.DATABASE)
        val before = digest(file); val alias = ShadowTextKeys.KEYS.keys.single()
        ShadowTextKeys.KEYS[alias] = SecretKeySpec(ByteArray(32) { 11 }, "AES")
        assertTrue(runCatching { ledger() }.isFailure); assertEquals(before, digest(file))
    }
    @Test fun P10DifferentAccountCipherIsRejected() {
        val a = AccountStorageContext(context, "synthetic-account-A")
        val b = AccountStorageContext(context, "synthetic-account-B")
        val first = ledger(a); prepare(first); first.close()
        val second = ledger(b); prepare(second); second.close()
        val af = ProtectedDatabase.file(a, DailyReportLedger.DATABASE)
        val bf = ProtectedDatabase.file(b, DailyReportLedger.DATABASE)
        assertNotEquals(af.canonicalPath, bf.canonicalPath); bf.writeBytes(af.readBytes())
        val copied = digest(bf)
        assertTrue(runCatching { ledger(b) }.isFailure); assertEquals(copied, digest(bf))
    }
    @Test fun P11LegacyReportDbAndWalRemainUnchangedAndHeld() {
        val f = context.getDatabasePath(DailyReportLedger.DATABASE); f.parentFile!!.mkdirs()
        val wal = File(f.path + "-wal"); f.writeText("SYN_LEGACY_REPORT_DB"); wal.writeText("SYN_LEGACY_REPORT_WAL")
        val old = listOf(f, wal).map(::digest); val creates = ShadowTextKeys.CREATES.get()
        assertEquals("TEXT_STORE_LEGACY_REVIEW_REQUIRED", runCatching { ledger() }.exceptionOrNull()?.message)
        assertEquals(old, listOf(f, wal).map(::digest)); assertEquals(creates, ShadowTextKeys.CREATES.get())
    }
    @Test fun P12AbsentCanonicalStoreReturnsNoRecordingsWithoutCreatingKey() {
        val before = ShadowTextKeys.CREATES.get(); val input = source().snapshot(ReportWindow(day, zone))
        assertEquals(ReportReadiness.NO_RECORDINGS, input.readiness); assertEquals(0, input.expectedCount)
        assertEquals(before, ShadowTextKeys.CREATES.get()); assertFalse(ProtectedDatabase.file(context, GeneralLocalStore.DATABASE).exists())
    }
    @Test fun P13LegacyCanonicalDbAndWalAreNotPresentedAsEmpty() {
        val f = context.getDatabasePath(GeneralLocalStore.DATABASE); f.parentFile!!.mkdirs()
        val wal = File(f.path + "-wal"); f.writeText("SYN_LEGACY_CANONICAL_DB"); wal.writeText("SYN_LEGACY_CANONICAL_WAL")
        val old = listOf(f, wal).map(::digest); val creates = ShadowTextKeys.CREATES.get()
        assertEquals("TEXT_STORE_LEGACY_REVIEW_REQUIRED", runCatching { source().snapshot(ReportWindow(day, zone)) }.exceptionOrNull()?.message)
        assertEquals(old, listOf(f, wal).map(::digest)); assertEquals(creates, ShadowTextKeys.CREATES.get())
    }
    @Test fun P14CanonicalKeyLossFailsInsteadOfEmptySuccess() {
        ProtectedDatabase.open(context, GeneralLocalStore.DATABASE, "CREATE TABLE seed(id INTEGER PRIMARY KEY)").close()
        val f = ProtectedDatabase.file(context, GeneralLocalStore.DATABASE); val before = digest(f)
        val creates = ShadowTextKeys.CREATES.get(); ShadowTextKeys.KEYS.clear()
        assertEquals("TEXT_STORE_KEY_UNAVAILABLE", runCatching { source().snapshot(ReportWindow(day, zone)) }.exceptionOrNull()?.message)
        assertEquals(before, digest(f)); assertEquals(creates, ShadowTextKeys.CREATES.get())
    }
    @Test fun P15TamperedCanonicalCipherDoesNotResetOrOpenPlainFallback() {
        ProtectedDatabase.open(context, GeneralLocalStore.DATABASE, "CREATE TABLE seed(id INTEGER PRIMARY KEY)").close()
        val f = ProtectedDatabase.file(context, GeneralLocalStore.DATABASE)
        val bytes = f.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); f.writeBytes(bytes)
        val tampered = digest(f)
        assertTrue(runCatching { source().snapshot(ReportWindow(day, zone)) }.isFailure)
        assertEquals(tampered, digest(f)); assertFalse(context.getDatabasePath(GeneralLocalStore.DATABASE).exists())
    }
    @Test fun P16LiveKakaoGuardStaysClosedWithoutSdkActions() {
        assertFalse(BuildConfig.DAILY_REPORT_KAKAO_LIVE_ENABLED)
        assertEquals(DeliveryResult.Status.DISABLED, KakaoDailyReportSender(context).gate()?.status)
    }
    @Test fun P17RetainedAccountV1PathWithWalBlocksOptInAndPreservesArtifacts() {
        val scoped=AccountStorageContext(context,"SYNTHETIC_OLD_REPORT_OWNER")
        val old=context.getDatabasePath("eum_"+scoped.scope+"_ieum-daily-reports-v1.db").apply {parentFile!!.mkdirs()}
        val wal=File(old.path+"-wal");old.writeText("PUBLIC_SYNTHETIC_RETAINED_V1_REPORT");wal.writeText("PUBLIC_SYNTHETIC_RETAINED_WAL")
        val before=listOf(old,wal).map(::digest);val l=ledger(scoped)
        assertTrue(l.config().legacyHold);assertFalse(l.config().enabled)
        assertEquals("LEGACY_V1_REPORT_REVIEW_REQUIRED",runCatching{l.setEnabled(true,now,zone)}.exceptionOrNull()?.message)
        assertEquals(before,listOf(old,wal).map(::digest))
    }
    @Test fun P18RetainedAccountPlainV2BlocksWithoutCreatingReplacementKey() {
        val scoped=AccountStorageContext(context,"SYNTHETIC_OLD_V2_OWNER")
        val old=context.getDatabasePath("eum_"+scoped.scope+"_"+DailyReportLedger.DATABASE).apply {parentFile!!.mkdirs()}
        val wal=File(old.path+"-wal");old.writeText("PUBLIC_SYNTHETIC_RETAINED_V2_REPORT");wal.writeText("PUBLIC_SYNTHETIC_RETAINED_WAL")
        val before=listOf(old,wal).map(::digest);val creates=ShadowTextKeys.CREATES.get()
        assertEquals("TEXT_STORE_LEGACY_REVIEW_REQUIRED",runCatching{ledger(scoped)}.exceptionOrNull()?.message)
        assertEquals(before,listOf(old,wal).map(::digest));assertEquals(creates,ShadowTextKeys.CREATES.get())
        assertFalse(ProtectedDatabase.file(scoped,DailyReportLedger.DATABASE).exists())
    }
    @Test fun P19ReportFactoryPreservesPendingRecoveryArtifactInsteadOfNormalizingIt() {
        val l=ledger();prepare(l);l.close();val main=ProtectedDatabase.file(context,DailyReportLedger.DATABASE)
        val pending=File(main.path+".new");pending.writeText("PUBLIC_SYNTHETIC_UNVERIFIED_REPORT_CIPHERTEXT")
        val before=listOf(main,pending).map(::digest);val creates=ShadowTextKeys.CREATES.get()
        assertEquals("TEXT_STORE_RECOVERY_REVIEW_REQUIRED",runCatching{ledger()}.exceptionOrNull()?.message)
        assertEquals(before,listOf(main,pending).map(::digest));assertEquals(creates,ShadowTextKeys.CREATES.get())
    }
}
