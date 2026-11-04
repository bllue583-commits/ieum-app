package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.IeumApplication
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.CoreException
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONArray
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Design v2.8 r9-1 (store foundation): one phone key with its migration, the main-thread check, one transaction per
 * step of the hourly pass, the recovery of half-written sealed files and the issue links. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreFoundationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private var now=Instant.parse("2026-11-04T01:00:00Z").toEpochMilli()
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun store()=CallRecordStore(context) { now }
    @Before fun setup() { context=RuntimeEnvironment.getApplication();ShadowTextAtomicFile.failNext=false;ProtectedDatabase.takeRecovery() }
    @After fun off() { CallRecordStore.MAIN_THREAD_CHECK=false }

    // ---- 1. one phone key ---------------------------------------------------------------------------------------
    @Test fun everySpellingOfANumberGivesOneCounterpartKey() {
        for(raw in listOf("+82 10-1234-5678","010-1234-5678","821012345678","0082 10 1234 5678"))
            assertEquals(raw,"01012345678",CallRecordStore.counterpartKey(raw))
        assertEquals("021234567",CallRecordStore.counterpartKey("+82 2-123-4567"))
        assertEquals("kim@example.com",CallRecordStore.counterpartKey("Kim@Example.com"))
        assertEquals("홍길동",CallRecordStore.counterpartKey(" 홍길동 "))
        // A foreign number keeps its digits: it is still one caller.
        assertEquals("14155550100",CallRecordStore.counterpartKey("+1 415 555 0100"))
        assertEquals("021234567",MessageIntake.phoneKey("+82 2-123-4567"))
        assertEquals("010-1234-5678",CallTranscriptionRunner.counterpart("통화 녹음 +82 10-1234-5678_251029_101500.m4a"))
    }

    @Test fun theMigrationRekeysNumbersAndJoinsOnePersonSplitByTwoSpellings() {
        store().use { }
        val saved=JSONObject()
        port().use { p -> p.transaction {
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key,ledger_id) VALUES('pe_a','김민수','01012345678',NULL,NULL,0,5,'김민수','p_a')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key,ledger_id) VALUES('pe_b','','821012345678',NULL,NULL,0,9,'','p_b')")
            p.execute("INSERT INTO people_phones VALUES('01012345678','pe_a')");p.execute("INSERT INTO people_phones VALUES('821012345678','pe_b')")
            p.execute("INSERT INTO person_aliases VALUES('김민쑤','pe_b')")
            p.execute("INSERT INTO call_links VALUES('r_old','pe_b',NULL)")
            // Two different people on one office number stay two.
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key) VALUES('pe_c','박영희','0212345678',NULL,NULL,0,3,'박영희')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key) VALUES('pe_d','최철수','820212345678',NULL,NULL,0,2,'최철수')")
            p.execute("INSERT INTO call_log VALUES(1,'821012345678','',?,60000,1)",now)
            p.execute("INSERT INTO personal_numbers VALUES('821099998888',1)")
            p.execute("UPDATE app_migration SET version=3");null } }
        IeumApplication.shutdown()
        CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(saved) }).use { }
        port().use { p ->
            val people=p.query("SELECT id,name,phone_key FROM people ORDER BY id").associate { it["id"] to it["phone_key"] }
            assertEquals(mapOf("pe_a" to "01012345678","pe_c" to "0212345678","pe_d" to null),people)
            assertEquals(setOf("01012345678"),p.query("SELECT phone_key FROM people_phones WHERE person_id='pe_a'").map { it["phone_key"] }.toSet())
            assertEquals(setOf("pe_c","pe_d"),p.query("SELECT person_id FROM people_phones WHERE phone_key='0212345678'").map { it["person_id"] }.toSet())
            // The alias and the call link went with the merged person.
            assertEquals("pe_a",p.query("SELECT person_id FROM person_aliases WHERE name_key='김민쑤'").single()["person_id"])
            assertEquals("pe_a",p.query("SELECT person_id FROM call_links WHERE record_id='r_old'").single()["person_id"])
            assertEquals("01012345678",p.query("SELECT number_key FROM call_log WHERE log_id=1").single()["number_key"])
            assertEquals("01099998888",p.query("SELECT phone_key FROM personal_numbers").single()["phone_key"])
            assertEquals(4L,(p.query("SELECT version FROM app_migration").single()["version"] as Number).toLong())
            val indexes=p.query("SELECT name FROM sqlite_master WHERE type='index'").map { it["name"] }.toSet()
            for(i in listOf("call_links_person","call_links_company","task_companies_company","call_log_number_start","call_records_counterpart","workflow_items_status_kind","people_phones_person"))
                assertTrue(i,i in indexes)
        }
        val log=JSONArray(saved.getString(PipelineSettingsKeys.MERGE_LOG))
        assertEquals("p_b>p_a",log.getJSONObject(0).let { it.getString("from")+">"+it.getString("into") })
    }

    // ---- 2. main thread ---------------------------------------------------------------------------------------
    @Test fun theStoreRefusesTheMainThread() {
        val s=store()
        try {
            CallRecordStore.MAIN_THREAD_CHECK=true
            val e=assertThrows(IllegalStateException::class.java) { s.counts() }
            assertEquals("CALL_STORE_MAIN_THREAD",e.message)
            val io=Executors.newSingleThreadExecutor()
            try { assertNotNull(io.submit<Map<String,Int>> { s.counts() }.get(30,TimeUnit.SECONDS)) } finally { io.shutdown() }
            assertTrue(s.usable()) // the process holder may still ask, and close, from the main thread
        } finally { CallRecordStore.MAIN_THREAD_CHECK=false;s.close() }
    }

    // ---- 3. one transaction -----------------------------------------------------------------------------------
    @Test fun sentLedgerEditsAreTakenOffInOneSeal() {
        val hold=store() // keeps the loaded state, so the runner's open does no schema pass
        port().use { p -> p.transaction { (1..5).forEach { i -> p.execute("INSERT INTO ledger_edits VALUES(?,?,?)","e$i","{}",i.toLong()) };null } }
        val put=mutableListOf<String>()
        val inbox=object:CallInboxStore {
            override fun put(path:String,json:String) { put+=path }
            override fun list(dir:String)=emptyList<Pair<String,String>>()
            override fun get(path:String):String=throw java.io.FileNotFoundException(path)
            override fun delete(path:String,sha:String) {}
        }
        val s=JSONObject()
        val before=ShadowTextAtomicFile.writes
        assertTrue(CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now },inbox,save={ e -> e(s) },callLog={ throw IllegalStateException() },smsAllowed={ false }).runPending())
        assertEquals(5,put.size)
        assertEquals(1,ShadowTextAtomicFile.writes-before)
        assertTrue(port().use { it.query("SELECT id FROM ledger_edits") }.isEmpty())
        hold.close()
    }

    // ---- 4. recovery of half-written files --------------------------------------------------------------------
    private val name="synthetic-recovery.db"
    private val io=Executors.newSingleThreadExecutor()
    @After fun stopIo() { io.shutdown() }
    private fun <T> off(f:()->T):T=try { io.submit<T> { f() }.get(30,TimeUnit.SECONDS) } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    private fun notes()=AndroidSqlPort(context,name,"CREATE TABLE notes(id INTEGER PRIMARY KEY,note TEXT)".byteInputStream())
    private fun note(n:Int)=off { notes().use { it.execute("INSERT INTO notes VALUES(?,?)",n,"PUBLIC_SYNTHETIC_$n") } }
    private fun read()=off { notes().use { p -> p.query("SELECT id FROM notes ORDER BY id").map { (it["id"] as Number).toInt() } } }
    private fun main()=ProtectedDatabase.file(context,name)
    private fun side(s:String)=File(main().path+s)

    @Test fun aNewerVerifiedStageLeftByAStoppedWriteIsPromoted() {
        note(1);val older=main().readBytes();note(2);val newer=main().readBytes()
        main().writeBytes(older);side(".new").writeBytes(newer)
        assertEquals(listOf(1,2),read())
        assertFalse(side(".new").exists());assertEquals("PROMOTED_NEW",ProtectedDatabase.takeRecovery())
        assertNull(ProtectedDatabase.takeRecovery())
    }
    @Test fun aStageThatIsTheSameOrDoesNotOpenGoes() {
        note(1);side(".new").writeBytes(main().readBytes())
        assertEquals(listOf(1),read());assertFalse(side(".new").exists());assertEquals("DROPPED_NEW",ProtectedDatabase.takeRecovery())
        side(".new").writeText("PUBLIC_SYNTHETIC_UNVERIFIED_STAGE")
        assertEquals(listOf(1),read());assertFalse(side(".new").exists());assertEquals("DROPPED_NEW",ProtectedDatabase.takeRecovery())
    }
    @Test fun aBackupReplacesOnlyAMainFileThatFailsTheCheck() {
        note(1);val one=main().readBytes();note(2);side(".bak").writeBytes(one)
        assertEquals(listOf(1,2),read());assertFalse(side(".bak").exists());assertEquals("DROPPED_BAK",ProtectedDatabase.takeRecovery())
        note(3);side(".bak").writeBytes(main().readBytes());main().writeBytes(byteArrayOf(1,4,9))
        assertEquals(listOf(1,2,3),read());assertFalse(side(".bak").exists());assertEquals("PROMOTED_BAK",ProtectedDatabase.takeRecovery())
    }
    @Test fun nothingUsableLeftHoldsTheStoreAndKeepsTheFiles() {
        note(1);main().writeBytes(byteArrayOf(1,4,9));side(".bak").writeText("PUBLIC_SYNTHETIC_BAD_BACKUP")
        val error=try { read();"" } catch(e:CoreException) { e.message.orEmpty() }
        assertEquals("TEXT_STORE_RECOVERY_REVIEW_REQUIRED",error);assertTrue(side(".bak").exists());assertEquals("FAILED",ProtectedDatabase.takeRecovery())
    }
    @Test fun theProcessStoreWritesTheRecoveryForTheHeartbeat() {
        off { store().use { } }
        IeumApplication.shutdown()
        val file=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE)
        File(file.path+".new").writeText("PUBLIC_SYNTHETIC_UNVERIFIED_STAGE")
        off { IeumApplication.store(context) }
        assertEquals("DROPPED_NEW",SecureSettings(context).snapshot().optString(PhoneStatus.DB_RECOVERY_LAST))
        IeumApplication.shutdown()
    }

    // ---- 5. storage share -------------------------------------------------------------------------------------
    @Test fun theStorageShareIsTheFullerOfBytesAndRows() {
        assertEquals(85,SelfCheckNotices.storagePercent(CallRecordStore.DB_LIMIT_BYTES/10,85_000))
        assertEquals(50,SelfCheckNotices.storagePercent(CallRecordStore.DB_LIMIT_BYTES/2))
    }

    // ---- 7. issue links ---------------------------------------------------------------------------------------
    @Test fun ledgerIssuesAreLinkedToCompaniesById() {
        store().use { s ->
            s.applyLedger(JSONObject().put("rev",1).put("companies",JSONArray().put(JSONObject().put("name","△△철강")).put(JSONObject().put("name","□□레미콘")))
                .put("issues",JSONArray().put(JSONObject().put("id","i1").put("title","철근 단가").put("status","견적 기다림").put("companies",JSONArray().put("△△철강").put("모르는회사")))))
            // A later tidy finds the two to be one company: merged into "□□레미콘", the issue still follows by id.
            s.applyLedger(JSONObject().put("rev",2).put("companies",JSONArray().put(JSONObject().put("name","△△철강").put("mergedInto","□□레미콘"))))
            assertEquals(listOf("철근 단가"),s.issues("□□레미콘").map { it.title })
        }
        port().use { p -> assertEquals(1,p.query("SELECT * FROM issue_companies").size) }
    }
}
