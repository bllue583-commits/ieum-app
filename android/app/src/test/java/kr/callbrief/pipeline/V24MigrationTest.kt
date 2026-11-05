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
import kr.co.callsummary.vault.EncryptedAudioVault
import kr.co.callsummary.vault.VaultKeys
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
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** DESIGN_V4 2.4-1 (r12-4): a store written by v2.4 (versionCode 1033, migration 2) opened by this build in one step, as on
 * the phone that skipped v2.6 to v3.0. The fixture's tables come from commit 9668e98 (test resource store-v24-schema.sql):
 * 25 MB checks in vault2_refs and observe_execute, no ledger_id/name_locked on people before v2.4's columns, no issue_companies,
 * no vault_pruned rows. Rows: people with numbers in several spellings, transcription jobs still QUEUED from before 10/1,
 * a call without a start time, check items, a sent and answered record. Generated rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class V24MigrationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=Instant.parse("2026-11-05T09:00:00Z").toEpochMilli()
    private val oct15=Instant.parse("2026-10-15T01:15:00Z").toEpochMilli() // 10:15 KST
    private val sep20=Instant.parse("2026-09-20T01:00:00Z").toEpochMilli()
    private val io=Executors.newSingleThreadExecutor()
    private fun <T> worker(f:()->T):T=try { io.submit<T> { f() }.get(120,TimeUnit.SECONDS) } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    private class Keys:VaultKeys {
        var key:SecretKey?=null
        override fun state(alias:String)=if(key==null) VaultKeys.State.MISSING else VaultKeys.State.AVAILABLE
        fun create(@Suppress("UNUSED_PARAMETER") alias:String) { check(key==null);key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun encrypt(alias:String)=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,checkNotNull(key)) }
        override fun decrypt(alias:String,iv:ByteArray)=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,checkNotNull(key),GCMParameterSpec(128,iv)) }
    }
    private val keys=Keys()
    private fun local()=GeneralLocalStore(context,{ c ->
        val root=c.noBackupFilesDir.toPath().resolve("audio-vault-v2")
        GeneralLocalStore.VaultAccess(EncryptedAudioVault(root,keys) { p -> FileChannel.open(p,StandardOpenOption.READ).use { it.force(true) } },root,keys::create)
    },{ now })
    private fun v24Schema()=checkNotNull(javaClass.getResourceAsStream("/store-v24-schema.sql"))
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun rows(sql:String,vararg a:Any?)=port().use { it.query(sql,*a) }
    private fun one(sql:String,vararg a:Any?)=rows(sql,*a).single().values.single()
    private fun sealed()=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE)

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();ShadowTextAtomicFile.failNext=false;ProtectedDatabase.takeRecovery()
        File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
    }
    @After fun off() { IeumApplication.shutdown();io.shutdown() }

    /** Writes the v2.4 store: its tables only (the fixture schema), then its rows, then closes it so the next open reads the
     * sealed file from disk as the phone does after the update. */
    private fun seedV24(extra:(AndroidSqlPort)->Unit={}) {
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,v24Schema()).use { p -> p.transaction {
            val o=GeneralLocalStore.OWNER
            p.execute("INSERT INTO board_state(owner_key,revision) VALUES(?,3)",o)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree/Call',1,1,1)",o)
            fun recording(id:String,name:String,startedMs:Long?,fileMs:Long?,sha:String) {
                p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/mp4',4000,?,?,?,1,1,1,?)",
                    id,o,"doc_$id",name,fileMs ?: oct15,oct15,oct15,startedMs)
                p.execute("INSERT INTO content_blobs VALUES(?,4000)",sha)
                p.execute("INSERT INTO source_versions VALUES(?,1,?,4000,?,?)",id,sha,oct15,"obs_$id")
                p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree/Call',?,?,'sig',?,?)",id,"doc_$id","content://generated/document/$id",sha,fileMs)
                p.execute("INSERT INTO local_visibility(recording_id) VALUES(?)",id)
            }
            val shaA="a".repeat(64);val shaB="b".repeat(64);val shaC="c".repeat(64);val shaD="d".repeat(64)
            recording("rA","통화 녹음 김민수 010-1234-5678_261015_101500.m4a",oct15,oct15,shaA)
            // v1026 and earlier: the start time only in the file name (filled at open).
            recording("rB","통화 녹음 박영희_261016_141000.m4a",null,null,shaB)
            recording("rC","통화 녹음 0212345678_260920_100000.m4a",sep20,sep20,shaC) // before 10/1, still QUEUED
            recording("rD","통화 녹음 최철수_261104_090000.m4a",now-86_400_000L,now-86_400_000L,shaD) // after 10/1, QUEUED
            // The encrypted original of rA, under the 25 MB check of v2.4.
            p.execute("INSERT INTO vault2_initialization VALUES(?, 'init1', ?, 'READY')",o,GeneralLocalStore.ALIAS)
            p.execute("INSERT INTO vault2_refs VALUES('rA',1,?,?,4000,?,?,4028)",o,shaA,GeneralLocalStore.ALIAS,"e".repeat(64))
            listOf("rA" to "DONE","rB" to "DONE","rC" to "QUEUED","rD" to "QUEUED").forEach { (r,st) ->
                p.execute("INSERT INTO call_transcription_jobs(recording_id,source_version,audio_sha,state,updated_ms) VALUES(?,1,?,?,?)",r,mapOf("rA" to shaA,"rB" to shaB,"rC" to shaC,"rD" to shaD)[r],st,oct15) }
            p.execute("INSERT INTO call_transcription_parts VALUES('rD',1,?,0,'[]',120000)",shaD)
            // Calls: rA answered and analysed (v2.4 keyed numbers by digits only), rB waiting, a text message.
            val idA="rA|1|$shaA";val idB="rB|1|$shaB"
            p.execute("""INSERT INTO call_records VALUES(?,'rA',?,125000,'010-1234-5678','01012345678','견적서 이야기','[]','ANALYZED','견적서를 내일 보내기로 했어요',?,NULL,?,?)""",
                idA,oct15,"""{"analysis":{"summary":"견적서를 내일 보내기로 했어요","keyPoints":["견적서"],"counterpartName":"김민수","counterpartCompany":"△△철강","business":true},"applied":[]}""",oct15+60_000,oct15+3_600_000)
            p.execute("INSERT INTO call_records VALUES(?,'rB',NULL,61000,'박영희','박영희','단가 문의','[[0,1000,\"x\"]]','TRANSCRIBED',NULL,NULL,NULL,?,NULL)",idB,oct15)
            p.execute("INSERT INTO call_inbox_sent VALUES(?,?,?)",idA,oct15+120_000,oct15+3_600_000)
            p.execute("INSERT INTO call_inbox_sent VALUES(?,?,NULL)",idB,oct15+120_000)
            // People: one person split by two spellings of one number, two people on one office number.
            p.execute("INSERT INTO companies(id,name,trade,memo,updated_ms,name_key) VALUES('co_a','△△철강','철근','',5,'△△철강')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key,ledger_id,name_locked) VALUES('pe_a','김민수','01012345678','co_a','과장',0,5,'김민수','p_a',0)")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key,ledger_id,name_locked) VALUES('pe_b','','821012345678',NULL,NULL,0,9,'','p_b',0)")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key) VALUES('pe_c','박영희','0212345678',NULL,NULL,0,3,'박영희')")
            p.execute("INSERT INTO people(id,name,phone_key,company_id,role,is_staff,updated_ms,name_key) VALUES('pe_d','최철수','820212345678',NULL,NULL,0,2,'최철수')")
            p.execute("INSERT INTO people_phones VALUES('01012345678','pe_a')");p.execute("INSERT INTO people_phones VALUES('821012345678','pe_b')")
            p.execute("INSERT INTO people_phones VALUES('0212345678','pe_c')");p.execute("INSERT INTO people_phones VALUES('820212345678','pe_d')")
            p.execute("INSERT INTO call_links VALUES(?,'pe_a','co_a')",idA);p.execute("INSERT INTO call_links VALUES(?,'pe_b',NULL)",idB)
            p.execute("INSERT INTO person_aliases VALUES('김민쑤','pe_b')")
            // A check item made by rA, the call log and personal numbers in v2.4's digits-only keys.
            p.execute("INSERT INTO workflow_items(id,recording_id,case_id,kind,title,edition,due_text,status,revision,source_key,evidence_quote,created_ms,done_ms,ai_note,source,tentative,last_call_ms) VALUES('t1','rA',NULL,'TODO','△△철강 견적서 보내기','e1','내일','open',1,NULL,'견적서는 내일',?,NULL,NULL,NULL,0,?)",oct15+3_600_000,oct15)
            p.execute("INSERT INTO call_task_bindings VALUES('t1',?,1,?)",idA,oct15+3_600_000)
            p.execute("INSERT INTO task_companies VALUES('t1','co_a')")
            p.execute("INSERT INTO call_log VALUES(1,'821012345678','김민수',?,125000,1)",oct15-20_000)
            p.execute("INSERT INTO call_log VALUES(2,'0212345678',NULL,?,61000,2)",oct15+86_400_000L)
            p.execute("INSERT INTO call_record_log VALUES(?,1,'받은 전화')",idA)
            p.execute("INSERT INTO personal_numbers VALUES('821099998888',1)");p.execute("INSERT INTO personal_streaks VALUES('821077776666',1,1)")
            p.execute("INSERT INTO issues VALUES('i1',0,'철근 단가','견적 기다림','[\"△△철강\"]',1,'[]')")
            p.execute("INSERT INTO site_memo VALUES(1,'현장 메모',1)")
            p.execute("INSERT INTO app_migration VALUES(1,2)")
            extra(p);null } }
        IeumApplication.shutdown()
    }

    /** 1.4-0: the store of 1033 opens in this build and keeps every row; the app's open order (process store, then the
     * local store and the transcription line). */
    @Test fun aV24StoreOpensInThisBuildWithEveryRowKept() {
        seedV24()
        val before=sealed().readBytes()
        val settings=org.json.JSONObject()
        worker { CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(settings) }).use { s ->
            assertEquals(mapOf("ANALYZED" to 1,"TRANSCRIBED" to 1),s.counts())
            assertEquals(listOf("△△철강 견적서 보내기"),s.openItems().map { it.title })
            assertEquals("현장 메모",s.siteMemo())
            assertEquals(listOf("철근 단가"),s.issues("△△철강").map { it.title })
        } }
        assertFalse("the file changed: the migration was sealed",before.contentEquals(sealed().readBytes()))
        worker { local().use { assertEquals(60_000_000L,it.audioByteLimit()) } }
        worker { CallTranscriptionStore(context) { now }.use { t -> assertNotNull(t.candidates()) } }
        worker { IeumApplication.store(context).let { assertTrue(it.usable()) } }
        // Schema: today's columns and indexes, the 60 MB lines, migration 4.
        assertEquals(4L,(one("SELECT version FROM app_migration") as Number).toLong())
        val cols=rows("PRAGMA table_info(people)").map { it["name"] }.toSet()
        assertTrue(cols.containsAll(listOf("ledger_id","name_locked","name_key")))
        val indexes=rows("SELECT name FROM sqlite_master WHERE type='index'").map { it["name"] }.toSet()
        for(i in listOf("call_links_person","call_log_number_start","people_phones_person","issue_companies_company")) assertTrue(i,i in indexes)
        assertTrue(one("SELECT sql FROM sqlite_master WHERE name='vault2_refs'").toString().contains("plaintext_bytes<=60000000"))
        assertTrue(one("SELECT sql FROM sqlite_master WHERE name='observe_execute'").toString().contains("byte_count>60000000"))
        assertEquals(1,rows("SELECT * FROM vault2_refs").size)
        assertEquals(1,rows("SELECT name FROM sqlite_master WHERE name='vault2_ref_no_update'").size)
        // Rows: calls, the received mark, the start time from the file name, the segment copy gone.
        assertEquals(2,rows("SELECT * FROM call_records").size)
        assertNotNull(rows("SELECT result_ms FROM call_inbox_sent WHERE record_id LIKE 'rA|%'").single()["result_ms"])
        assertNotNull(one("SELECT started_ms FROM call_records WHERE recording_id='rB'"))
        assertEquals("[]",one("SELECT segments_json FROM call_records WHERE recording_id='rB'"))
        // The queued job from before 10/1 is skipped, the one after stays queued with its finished piece.
        assertEquals("SKIPPED",one("SELECT state FROM call_transcription_jobs WHERE recording_id='rC'"))
        assertEquals("BEFORE_START",one("SELECT reason FROM call_transcription_jobs WHERE recording_id='rC'"))
        assertEquals("QUEUED",one("SELECT state FROM call_transcription_jobs WHERE recording_id='rD'"))
        assertEquals(1,rows("SELECT * FROM call_transcription_parts").size)
        // One phone key: the person split by "82…" joins the named one; two names on one office number stay two.
        assertEquals(listOf("pe_a","pe_c","pe_d"),rows("SELECT id FROM people ORDER BY id").map { it["id"] })
        assertEquals("pe_a",one("SELECT person_id FROM call_links WHERE record_id LIKE 'rB|%'"))
        assertEquals("pe_a",one("SELECT person_id FROM person_aliases WHERE name_key='김민쑤'"))
        assertEquals(setOf("pe_c","pe_d"),rows("SELECT person_id FROM people_phones WHERE phone_key='0212345678'").map { it["person_id"] }.toSet())
        assertEquals(setOf("01012345678","0212345678"),rows("SELECT DISTINCT number_key FROM call_log").map { it["number_key"] }.toSet())
        assertEquals("01099998888",one("SELECT phone_key FROM personal_numbers"))
        assertEquals("01077776666",one("SELECT phone_key FROM personal_streaks"))
        assertEquals(1,rows("SELECT * FROM issue_companies").size)
        assertEquals(1,rows("SELECT * FROM workflow_items").size);assertEquals(1,rows("SELECT * FROM call_task_bindings").size)
        assertEquals(3L,(one("SELECT revision FROM board_state") as Number).toLong())
        assertEquals("p_b>p_a",org.json.JSONArray(settings.getString(PipelineSettingsKeys.MERGE_LOG)).getJSONObject(0).let { it.getString("from")+">"+it.getString("into") })
        assertEquals("",SecureSettings(context).snapshot().optString(PipelineSettingsKeys.STORE_OPEN_ERROR))
    }

    /** The one boundary case: a v2.4 store filled to just under the 16 MB sealed limit still migrates (the pass adds a few
     * KB of columns and tables and drops the segment copies). */
    @Test fun aV24StoreJustUnderSixteenMegabytesStillMigrates() {
        val big="가".repeat(300_000) // 900 KB of UTF-8, under the 1 MB row limit
        seedV24 { p -> repeat(16) { i -> p.execute("INSERT INTO call_records VALUES(?,'rD',NULL,60000,NULL,NULL,?,'[]','TRANSCRIBED',NULL,NULL,NULL,?,NULL)","big$i",big,oct15) } }
        // Fill to within 16 KB of the limit with smaller rows.
        var n=0
        while(sealed().length()<ProtectedDatabase.MAX_PLAIN_BYTES-16*1024-30) {
            val room=ProtectedDatabase.MAX_PLAIN_BYTES-16*1024-30-sealed().length()
            val chunk=minOf(300_000L,maxOf(1L,(room-200)/3)).toInt()
            AndroidSqlPort(context,GeneralLocalStore.DATABASE,v24Schema()).use { p ->
                p.execute("INSERT INTO call_records VALUES(?,'rD',NULL,60000,NULL,NULL,?,'[]','TRANSCRIBED',NULL,NULL,NULL,?,NULL)","fill${n++}","가".repeat(chunk),oct15) }
            if(chunk<50) break
        }
        IeumApplication.shutdown()
        assertTrue("fixture near the limit: ${sealed().length()}",sealed().length()>ProtectedDatabase.MAX_PLAIN_BYTES-64*1024)
        worker { CallRecordStore(context) { now }.use { s -> assertEquals(18+n,s.counts().values.sum()) } }
        assertEquals(4L,(one("SELECT version FROM app_migration") as Number).toLong())
        assertTrue(sealed().length()<=ProtectedDatabase.MAX_PLAIN_BYTES+29)
    }
}
