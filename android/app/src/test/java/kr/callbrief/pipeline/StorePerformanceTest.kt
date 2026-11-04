package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** r8-1 store performance: one open per home read, no second decrypt while the process holds its store, Keystore lookups
 * independent of the number of queries, read-after-write consistency through the held store. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StorePerformanceTest:ProtectedTextHostTestBase() {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private fun port()=AndroidSqlPort(app,GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql"))

    @Test fun homeReadOpensTheStoreOnce() {
        PreviewSeed.ensure(app);IeumApplication.shutdown()
        val home=Robolectric.buildActivity(MainActivity::class.java).get().home
        val opens=ProtectedDatabase.openCount()
        home.read()
        assertEquals(1,ProtectedDatabase.openCount()-opens)
        // The next read uses the held store: no open at all.
        val again=ProtectedDatabase.openCount();home.read()
        assertEquals(0,ProtectedDatabase.openCount()-again)
        assertTrue(SecureSettings(app).snapshot().has(IeumApplication.HOME_READ_MS))
        assertTrue(SecureSettings(app).snapshot().has(IeumApplication.DB_OPEN_MS))
    }

    @Test fun aHeldStoreIsNotDecryptedAgain() {
        port().use { it.execute("CREATE TABLE IF NOT EXISTS perf_probe(x INTEGER)") }
        IeumApplication.shutdown()
        val restores=ProtectedDatabase.restoreCount()
        val first=IeumApplication.store(app)
        assertEquals(1,ProtectedDatabase.restoreCount()-restores)
        assertSame(first,IeumApplication.store(app))
        // Even a separate CallRecordStore opened and closed meanwhile neither restores nor drops the memory state.
        CallRecordStore(app).use { it.counts() }
        CallRecordStore(app).use { it.counts() }
        assertEquals(1,ProtectedDatabase.restoreCount()-restores)
        // Without the process holder, the last close drops the state and the next open reads the file again (old behaviour).
        IeumApplication.shutdown()
        CallRecordStore(app).use { it.counts() }
        assertEquals(2,ProtectedDatabase.restoreCount()-restores)
    }

    @Test fun keystoreLookupsDoNotGrowWithQueries() {
        val s=IeumApplication.store(app)
        s.counts()
        val before=ProtectedDatabase.keyCheckCount()
        repeat(50) { s.counts();s.openItems();s.siteMemo() }
        assertEquals(0,ProtectedDatabase.keyCheckCount()-before)
        // A read-only transaction (nothing changed) seals nothing and asks no key either.
        repeat(5) { s.requeueAll() }
        assertEquals(0,ProtectedDatabase.keyCheckCount()-before)
    }

    @Test fun writesThroughTheHeldStoreAreSealedAndReadBack() {
        val s=IeumApplication.store(app)
        // A write through another handle of the same store is seen by the held one at once (one memory state).
        port().use { it.execute("INSERT OR REPLACE INTO site_memo VALUES(1,?,?)","현장 메모 시험",1L) }
        assertEquals("현장 메모 시험",s.siteMemo())
        // From disk: a fresh load sees the same.
        IeumApplication.shutdown()
        assertEquals("현장 메모 시험",CallRecordStore(app).use { it.siteMemo() })
    }

    @Test fun theStallTestIsOneQuery() {
        val s=IeumApplication.store(app)
        port().use { p -> p.transaction {
            p.execute("INSERT INTO call_records(record_id,recording_id,duration_ms,transcript,segments_json,state,created_ms) VALUES('a','ra',0,'','[]','TRANSCRIBED',1)")
            p.execute("INSERT INTO call_records(record_id,recording_id,duration_ms,transcript,segments_json,state,created_ms) VALUES('b','rb',0,'','[]','TRANSCRIBED',2)")
            p.execute("INSERT INTO call_records(record_id,recording_id,duration_ms,transcript,segments_json,state,created_ms) VALUES('c','rc',0,'','[]','ANALYZED',3)");null } }
        assertNull(s.oldestUnansweredSentMs())
        s.markSent("a",500);s.markSent("b",300);s.markSent("c",100)
        assertEquals(300L,s.oldestUnansweredSentMs())
        s.setReceived("b",900)
        assertEquals(500L,s.oldestUnansweredSentMs())
        // Same answer as the old per-record lookups.
        assertEquals(s.pending().mapNotNull { r -> s.sentMs(r.recordId)?.takeIf { s.receivedMs(r.recordId)==null } }.minOrNull(),s.oldestUnansweredSentMs())
    }
}
