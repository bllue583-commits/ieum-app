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

/** r12-4 (DESIGN_V4 2.4-2, 2.4-3, 2.4-4): the store's open error and its one notice, the narrower automatic merge with the
 * merged rows kept, and the call-log match diagnosis with the end-time reading. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreSafetyTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=Instant.parse("2026-11-05T09:00:00Z").toEpochMilli()
    private val io=Executors.newSingleThreadExecutor()
    private fun <T> off(f:()->T):T=try { io.submit<T> { f() }.get(60,TimeUnit.SECONDS) } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun settings()=SecureSettings(context).snapshot()
    @Before fun setup() { context=RuntimeEnvironment.getApplication();ShadowTextAtomicFile.failNext=false;ProtectedDatabase.takeRecovery() }
    @After fun off() { IeumApplication.shutdown();IeumApplication.tell={ c -> CallDigestNotifier.post(c,CallDigestWork.STATUS,CallDigestText.Message(IeumApplication.STORE_OPEN_TITLE,IeumApplication.STORE_OPEN_TEXT)) };io.shutdown() }

    // ---- 2.4-2 open error ----------------------------------------------------------------------------------------
    @Test fun anOpenThatFailsIsKeptWithItsStageAndToldOnceThenClearedWhenItOpens() {
        off { IeumApplication.store(context) };IeumApplication.shutdown()
        // A sealed file that no longer passes its check, no backup to fall back on.
        val file=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE)
        file.writeBytes(byteArrayOf(1,4,9,16));File(file.path+".bak").writeText("PUBLIC_SYNTHETIC_BAD_BACKUP")
        var told=0;IeumApplication.tell={ told++ }
        repeat(3) { assertThrows(CoreException::class.java) { off { IeumApplication.store(context) } } }
        assertEquals("CoreException:open:TEXT_STORE_RECOVERY_REVIEW_REQUIRED",settings().optString(PipelineSettingsKeys.STORE_OPEN_ERROR))
        assertEquals("one notice for the same error",1,told)
        assertEquals("FAILED",settings().optString(PhoneStatus.DB_RECOVERY_LAST))
        // The heartbeat reads the key by name (PhoneStatusTest covers its top-level code).
        assertEquals("CoreException:open:TEXT_STORE_RECOVERY_REVIEW_REQUIRED",PhoneStatus.code(settings().optString(PipelineSettingsKeys.STORE_OPEN_ERROR)))
        // Fixed (the files are put right): the next open clears the error, and a later new failure is told again.
        file.delete();File(file.path+".bak").delete()
        off { IeumApplication.store(context) }
        assertFalse(settings().has(PipelineSettingsKeys.STORE_OPEN_ERROR))
        IeumApplication.shutdown();file.writeBytes(byteArrayOf(2));File(file.path+".bak").writeText("X")
        assertThrows(CoreException::class.java) { off { IeumApplication.store(context) } }
        assertEquals(2,told)
    }
    @Test fun theErrorNamesTheStageTheOpenReached() {
        assertEquals("IllegalStateException:migrate",IeumApplication.openErrorCode(IllegalStateException("anything 010-1234-5678"),CallRecordStore.STAGE_MIGRATE))
        assertEquals("CoreException:seal:TEXT_STORE_CAPACITY_REQUIRES_REVIEW",IeumApplication.openErrorCode(CoreException("TEXT_STORE_CAPACITY_REQUIRES_REVIEW"),CallRecordStore.STAGE_SEAL))
        assertEquals("CoreException:open",IeumApplication.openErrorCode(CoreException("free text, not a code"),null))
        // A store that opens records how far it got.
        off { CallRecordStore(context) { now }.use { assertEquals(CallRecordStore.STAGE_DONE,CallRecordStore.openStage()) } }
    }
    @Test fun theCallStoreRecoveryCodeIsNotTheCardFiles() {
        off { IeumApplication.store(context) };IeumApplication.shutdown()
        // Only the card file had a stage left over: the call store's heartbeat code stays empty.
        val name="synthetic-cards.db";val cards=ProtectedDatabase.file(context,name)
        fun openCards()=off { AndroidSqlPort(context,name,"CREATE TABLE t(x INTEGER)".byteInputStream()).use { it.execute("INSERT INTO t VALUES(1)") } }
        openCards()
        File(cards.path+".new").writeText("PUBLIC_SYNTHETIC_UNVERIFIED_STAGE")
        openCards()
        assertEquals("DROPPED_NEW",ProtectedDatabase.takeRecovery(name))
        assertNull(ProtectedDatabase.takeRecovery(name))
        File(cards.path+".new").writeText("PUBLIC_SYNTHETIC_UNVERIFIED_STAGE")
        openCards()
        off { IeumApplication.store(context) }
        assertEquals("",settings().optString(PhoneStatus.DB_RECOVERY_LAST))
    }

    // ---- 2.4-3 merge scope -----------------------------------------------------------------------------------------
    private fun person(p:AndroidSqlPort,id:String,name:String,phone:String?,lid:String?=null,company:String?=null,updated:Long=1) {
        p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key,ledger_id) VALUES(?,?,?,?,NULL,0,?,?,?)",id,name,phone,company,updated,NameKey.canon(name),lid)
        if(phone!=null) p.execute("INSERT OR IGNORE INTO people_phones VALUES(?,?)",phone,id)
    }
    @Test fun theMigrationMergesOnlyTheSameNameOrAnEmptyOneOnOneNumberAndKeepsTheRows() {
        off { CallRecordStore(context) { now }.use { } }
        val saved=JSONObject()
        off { port().use { p -> p.transaction {
            p.execute("INSERT INTO companies(id,name,trade,memo,updated_ms,name_key) VALUES('co','△△철강',NULL,'',1,'△△철강')")
            // Same number in two spellings: same name -> one; empty name -> one.
            person(p,"a1","김민수","01011110000","L_a1",updated=9);person(p,"a2","김민수","821011110000","L_a2")
            person(p,"b1","박영희","01022220000",updated=9);person(p,"b2","","821022220000","L_b2")
            // One jamo apart on one number (2 syllables): two people, proposed.
            person(p,"c1","정민","01033330000",updated=9);person(p,"c2","전민","821033330000")
            // Surname and title on one number: two people, proposed.
            person(p,"d1","이영호","01044440000",updated=9);person(p,"d2","이과장","821044440000")
            // Same name, same company, different numbers: two people, proposed.
            person(p,"e1","최철수","01055550000",company="co",updated=9);person(p,"e2","최철수","01066660000",company="co")
            p.execute("INSERT INTO call_links VALUES('call_a2','a2',NULL)");p.execute("INSERT INTO person_aliases VALUES('김민쑤','a2')")
            p.execute("UPDATE app_migration SET version=3");null } } }
        IeumApplication.shutdown()
        val proposed=off { CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(saved) }).use { s -> s.personCandidates().also { assertEquals(it.toString(),s.tidyState(false,now).getJSONArray("personCandidates").toString()) } } }
        val left=port().use { it.query("SELECT id FROM people ORDER BY id").map { r -> r["id"] } }
        assertEquals(listOf("a1","b1","c1","c2","d1","d2","e1","e2"),left)
        assertEquals("a1",port().use { it.query("SELECT person_id FROM call_links WHERE record_id='call_a2'").single()["person_id"] })
        assertEquals(setOf(listOf("c1","c2"),listOf("d1","d2"),listOf("e1","e2")),(0 until proposed.length()).map { i -> proposed.getJSONArray(i).let { listOf(it.getString(0),it.getString(1)) } }.toSet())
        // The merge log carries the rows the merged person had.
        val log=JSONArray(saved.getString(PipelineSettingsKeys.MERGE_LOG))
        assertEquals(setOf("L_a2>L_a1","L_b2>b1"),(0 until log.length()).map { log.getJSONObject(it).let { o -> o.getString("from")+">"+o.getString("into") } }.toSet())
        val a2=(0 until log.length()).map { log.getJSONObject(it) }.single { it.getString("from")=="L_a2" }.getJSONObject("rows")
        assertEquals("김민수",a2.getJSONArray("people").getJSONObject(0).getString("name"))
        // The rows as they were at the merge, in today's phone key (the re-keying ran just before; restoring writes this form).
        assertEquals("01011110000",a2.getJSONArray("people_phones").getJSONObject(0).getString("phone_key"))
        assertEquals("call_a2",a2.getJSONArray("call_links").getJSONObject(0).getString("record_id"))
        assertEquals("김민쑤",a2.getJSONArray("person_aliases").getJSONObject(0).getString("name_key"))
        // A person merged away is no longer proposed; the pairs stay one each on the next open.
        IeumApplication.shutdown()
        off { port().use { p -> p.execute("DELETE FROM people WHERE id='c2'") } }
        assertEquals(2,off { CallRecordStore(context) { now }.use { it.personCandidates().length() } })
    }
    @Test fun theMergeLogKeepsTheLatestEntries() {
        val s=JSONObject().put(PipelineSettingsKeys.MERGE_LOG,JSONArray().apply { repeat(CallRecordStore.MERGE_LOG_MAX) { put(JSONObject().put("from","x$it").put("into","y")) } }.toString())
        off { CallRecordStore(context) { now }.use { } }
        off { port().use { p -> p.transaction { person(p,"a1","김민수","01011110000");person(p,"a2","김민수","821011110000");p.execute("UPDATE app_migration SET version=3");null } } }
        IeumApplication.shutdown()
        off { CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(s) }).use { } }
        val log=JSONArray(s.getString(PipelineSettingsKeys.MERGE_LOG))
        assertEquals(CallRecordStore.MERGE_LOG_MAX,log.length());assertEquals("x1",log.getJSONObject(0).getString("from"));assertEquals("a2",log.getJSONObject(log.length()-1).getString("from"))
    }

    // ---- 2.4-4 match diagnosis ----------------------------------------------------------------------------------
    private val t0=Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
    private fun entry(id:Long,number:String?,name:String?,start:Long,len:Long)=FirstPass.LogEntry(id,number,name,start,len,1)
    @Test fun recordingsNamedByTheEndOfTheCallPairWhenTheNumberOrNameAgrees() {
        val log=ArrayList<FirstPass.LogEntry>();val recs=ArrayList<FirstPass.RecordForMatch>()
        // Ten calls, each 3 to 12 minutes, two hours apart; the file name time is the end of the call.
        for(i in 0 until 10) { val start=t0+i*7_200_000L;val len=(3+i)*60_000L
            log+=entry(i+1L,"0101234000$i",if(i%2==0) "김민수$i" else null,start,len)
            recs+=FirstPass.RecordForMatch("n$i",start+len+20_000L,"010-1234-000$i","0101234000$i",len-15_000L) }
        // Named by the phone book name instead of a number.
        log+=entry(20,"01099990000","박영희",t0+30*3_600_000L,600_000L)
        recs+=FirstPass.RecordForMatch("name",t0+30*3_600_000L+600_000L,"박영희","박영희",590_000L)
        // No number, no name: never by the end time.
        log+=entry(21,"01088880000",null,t0+40*3_600_000L,600_000L)
        recs+=FirstPass.RecordForMatch("bare",t0+40*3_600_000L+600_000L,null,null,600_000L)
        // A number that is not the entry's: not paired.
        log+=entry(22,"01077770000",null,t0+50*3_600_000L,600_000L)
        recs+=FirstPass.RecordForMatch("other",t0+50*3_600_000L+600_000L,"01011112222","01011112222",600_000L)
        var st:FirstPass.MatchStats?=null
        val out=FirstPass.match(recs,log) { st=it }
        for(i in 0 until 10) assertEquals("n$i",i+1L,out["n$i"]?.id)
        assertEquals(20L,out["name"]?.id)
        assertNull(out["bare"]);assertNull(out["other"])
        assertEquals(11,st!!.byEnd);assertEquals(11,st!!.matched);assertEquals(2,st!!.noCandidate)
        // The two left: the nearest entry started 10 minutes before (bin "10").
        assertEquals(mapOf("10" to 2),st!!.missGapMin)
        assertTrue(st!!.gapSec.isEmpty())
    }
    @Test fun theEndTimeReadingIsNotUsedWhenTheUsualWindowHasAnEntry() {
        // An entry in the usual window that fails the length keeps the record "length", even if the end reading would fit.
        val start=t0;val len=600_000L
        val log=listOf(entry(1,"01012340000",null,start,len),entry(2,"01012340000",null,start+len+10_000L,30_000L))
        val r=FirstPass.RecordForMatch("r",start+len+20_000L,"01012340000","01012340000",len)
        var st:FirstPass.MatchStats?=null
        assertTrue(FirstPass.match(listOf(r),log) { st=it }.isEmpty())
        assertEquals(1,st!!.length);assertEquals(0,st!!.byEnd)
        // Two recordings: one in its usual window takes the entry first, the other's end-time reading does not take it away.
        val e=entry(3,"01055550000",null,t0+10*3_600_000L,300_000L)
        val usual=FirstPass.RecordForMatch("usual",e.startMs+10_000L,"01055550000","01055550000",300_000L)
        val late=FirstPass.RecordForMatch("late",e.startMs+300_000L,"01055550000","01055550000",290_000L)
        val out=FirstPass.match(listOf(usual,late),listOf(e))
        assertEquals(3L,out["usual"]?.id);assertNull(out["late"])
    }
    @Test fun missGapsAreBinnedByTenMinutesWithinAnHour() {
        assertEquals("0",FirstPass.missBin(3*60_000L));assertEquals("-10",FirstPass.missBin(-3*60_000L))
        assertEquals("50",FirstPass.missBin(59*60_000L));assertEquals("-60",FirstPass.missBin(-60*60_000L))
        assertEquals(FirstPass.MISS_FAR,FirstPass.missBin(61*60_000L));assertEquals(FirstPass.MISS_FAR,FirstPass.missBin(-61*60_000L))
        val log=listOf(entry(1,"01012340000",null,t0,60_000L))
        var st:FirstPass.MatchStats?=null
        FirstPass.match(listOf(FirstPass.RecordForMatch("x",t0+25*60_000L,null,null,60_000L),FirstPass.RecordForMatch("y",t0+5*3_600_000L,null,null,60_000L)),log) { st=it }
        assertEquals(mapOf("20" to 1,FirstPass.MISS_FAR to 1),st!!.missGapMin)
        // In the stored JSON, and through the heartbeat's counts filter.
        val json=JSONObject(st!!.mergeInto(null,now))
        assertEquals(1,json.getJSONObject("missGapMin").getInt("20"));assertEquals(1,json.getJSONObject("last").getJSONObject("missGapMin").getInt("far"))
        assertEquals(1,PhoneStatus.counts(json).getJSONObject("missGapMin").getInt("20"))
    }
}
