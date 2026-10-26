package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest

/** Host native-SQLite + synthetic damaged files only. No default-handler deletion is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DailyReportLegacyPreservationTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var legacy:File
    private val now=Instant.parse("2026-10-12T00:00:00Z")
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        // Only the generated Robolectric sandbox is cleared, never an actual app/device DB.
        context.deleteDatabase(DailyReportLedger.DATABASE);context.deleteDatabase("ieum-daily-reports-v1.db")
        legacy=context.getDatabasePath("ieum-daily-reports-v1.db");legacy.parentFile!!.mkdirs()
    }
    private fun fixture(suffix:String,bytes:ByteArray):Pair<File,ByteArray> {
        val file=File(legacy.path+suffix);file.writeBytes(bytes);return file to bytes
    }
    private fun damaged()=ByteArray(4096) { (it%251).toByte() }.apply {
        "PUBLIC_SYNTHETIC_NOT_SQLITE".toByteArray().copyInto(this)
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun preserved(vararg witnesses:Pair<File,ByteArray>) {
        witnesses.forEach { (file,before) ->
            assertTrue("synthetic file still exists: ${file.name}",file.isFile)
            assertEquals(before.size.toLong(),file.length());assertArrayEquals(hash(before),hash(file.readBytes()))
        }
    }
    private fun held() {
        DailyReportLedger(context,"SYN-LEGACY-PRESERVATION").use { ledger ->
            assertTrue(ledger.config().legacyHold);assertFalse(ledger.config().enabled)
            val error=assertThrows(IllegalStateException::class.java) { ledger.setEnabled(true,now,ZoneId.of("Asia/Seoul")) }
            assertEquals("LEGACY_V1_REPORT_REVIEW_REQUIRED",error.message);assertTrue(ledger.list().isEmpty())
        }
    }
    @Test fun corruptDatabaseAloneIsHeldAndPreservedAcrossRepeatedProbes() {
        val witness=fixture("",damaged());repeat(3) { held();preserved(witness) }
    }
    @Test fun corruptDatabaseWalAndShmArePreservedWithoutOpeningLegacy() {
        val db=fixture("",damaged());val wal=fixture("-wal","PUBLIC_SYNTHETIC_WAL".toByteArray())
        val shm=fixture("-shm",ByteArray(32768) { 13 })
        repeat(3) { held();preserved(db,wal,shm) }
    }
    @Test fun truncatedDatabaseWithRollbackJournalIsPreserved() {
        val db=fixture("",byteArrayOf(1,2,3));val journal=fixture("-journal","PUBLIC_SYNTHETIC_JOURNAL".toByteArray())
        held();preserved(db,journal)
    }
    @Test fun orphanWalAndShmHoldWithoutCreatingOrRepairingMainDatabase() {
        val wal=fixture("-wal",byteArrayOf(8,7,6));val shm=fixture("-shm",ByteArray(128) { 14 })
        held();assertFalse(legacy.exists());preserved(wal,shm)
    }
    @Test fun healthyEmptyLegacyWithSidecarsIsConservativelyHeldAndPreserved() {
        context.openOrCreateDatabase(legacy.name,0,null).use { it.execSQL("CREATE TABLE reports(id TEXT)") }
        val db=legacy to legacy.readBytes();val wal=fixture("-wal",byteArrayOf(4,5,6));val shm=fixture("-shm",byteArrayOf(7,8,9))
        held();preserved(db,wal,shm)
    }
    @Test fun healthyEmptyLegacyWithoutSidecarsRetainsV2OptInBehavior() {
        context.openOrCreateDatabase(legacy.name,0,null).use { it.execSQL("CREATE TABLE reports(id TEXT)") }
        val db=legacy to legacy.readBytes()
        DailyReportLedger(context,"SYN-LEGACY-EMPTY").use {
            assertFalse(it.config().legacyHold);assertTrue(it.setEnabled(true,now,ZoneId.of("Asia/Seoul")).enabled)
        }
        preserved(db)
    }
    @Test fun unreadableSchemaHoldsWithoutDeletingDatabase() {
        context.openOrCreateDatabase(legacy.name,0,null).use { it.execSQL("CREATE TABLE synthetic_unrelated(id TEXT)") }
        val db=legacy to legacy.readBytes();held();preserved(db)
    }
    @Test fun handlerSignalsFailureAndDoesNotCloseOrRepairOpenHandle() {
        val db=fixture("",damaged());val wal=fixture("-wal",byteArrayOf(1));val shm=fixture("-shm",byteArrayOf(2))
        SQLiteDatabase.create(null).use { probe ->
            val error=assertThrows(SQLiteDatabaseCorruptException::class.java) { PreserveLegacyReportErrorHandler.onCorruption(probe) }
            assertEquals("LEGACY_V1_REPORT_UNREADABLE_PRESERVED",error.message);assertTrue(probe.isOpen)
            preserved(db,wal,shm)
        }
    }
    @Test fun handlerSignalsFailureEvenWithClosedHandleWithoutInspectingOrDeletingFiles() {
        val db=fixture("",damaged());val wal=fixture("-wal",byteArrayOf(3));val shm=fixture("-shm",byteArrayOf(4))
        val probe=SQLiteDatabase.create(null);probe.close()
        assertThrows(SQLiteDatabaseCorruptException::class.java) { PreserveLegacyReportErrorHandler.onCorruption(probe) }
        assertFalse(probe.isOpen);preserved(db,wal,shm)
    }
}
