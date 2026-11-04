package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.CoreException
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Parent's concrete audit boundaries. Synthetic host files/JCE only, never device/private data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProtectedStoreAuditRegressionTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val executor=Executors.newSingleThreadExecutor()
    private val name="synthetic-protected-audit.db"
    private val noteSchema="CREATE TABLE notes(id INTEGER PRIMARY KEY,note TEXT,revision INTEGER NOT NULL)"
    @Before fun setup(){context=RuntimeEnvironment.getApplication();ShadowTextAtomicFile.failNext=false}
    @After fun stop(){executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    private fun <T> io(f:()->T):T=try{executor.submit<T>{f()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    private fun notes()=AndroidSqlPort(context,name,noteSchema.byteInputStream())
    private fun core()=AndroidSqlPort(context,name,context.assets.open("call_core_schema.sql"))
    private fun code(f:()->Unit):String=try{f();""}catch(e:CoreException){e.message.orEmpty()}
    private fun seedNotes(){notes().use {it.execute("INSERT INTO notes VALUES(1,'PUBLIC_SYNTHETIC_MANUAL_NOTE',1)")}}
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private fun witnesses():Map<String,Any> {
        val main=ProtectedDatabase.file(context,name)
        return listOf("",".bak",".new").associate { suffix ->
            val f=File(main.path+suffix);suffix.ifBlank {"main"} to if(f.isFile)mapOf("bytes" to f.length(),"sha256" to sha(f.readBytes())) else "ABSENT"
        }
    }
    private fun evidence(id:String,vararg values:Pair<String,Any>){
        val out=JSONObject().put("case",id).put("scope","API35_ROBOLECTRIC_NATIVE_SQLITE_JCE_SYNTHETIC_ONLY")
        values.forEach {out.put(it.first,it.second)}
        File("build/reports/protected-audit").apply{mkdirs()}.resolve("$id.json").writeText(out.toString(2))
    }
    private fun seedTranscript(p:AndroidSqlPort){
        p.execute("INSERT INTO content_blobs VALUES(?,12)","a".repeat(64))
        p.execute("INSERT INTO transcript_versions VALUES(?,?,?,?,?,?,?)","SYN-T1","a".repeat(64),"fixture-v1","b".repeat(64),"PUBLIC_SYNTHETIC_ORIGINAL","raw_review_required",100)
    }
    @Test fun P01TranscriptPrimaryKeyReplacementIsRejected()=io {
        core().use {p->seedTranscript(p);val before=p.query("SELECT * FROM transcript_versions")
            val error=code {p.execute("INSERT OR REPLACE INTO transcript_versions VALUES(?,?,?,?,?,?,?)","SYN-T1","a".repeat(64),"fixture-v1","c".repeat(64),"PUBLIC_SYNTHETIC_REPLACEMENT","raw_review_required",200)}
            val after=p.query("SELECT * FROM transcript_versions")
            evidence("P01","error" to error,"immutable" to (before==after));assertEquals("IMMUTABLE_HISTORY",error);assertEquals(before,after)
        }
    }
    @Test fun P02TranscriptNaturalKeyCannotReplaceItsIdentity()=io {
        core().use {p->seedTranscript(p);val before=p.query("SELECT * FROM transcript_versions")
            val error=code {p.execute("INSERT OR REPLACE INTO transcript_versions VALUES(?,?,?,?,?,?,?)","SYN-T2","a".repeat(64),"fixture-v1","b".repeat(64),"PUBLIC_SYNTHETIC_ORIGINAL","raw_review_required",200)}
            val after=p.query("SELECT * FROM transcript_versions")
            evidence("P02","error" to error,"immutable" to (before==after));assertEquals("IMMUTABLE_HISTORY",error);assertEquals(before,after)
        }
    }
    @Test fun P03ReceiptReplacementCannotChangeItsDigest()=io {
        core().use {p->p.execute("INSERT INTO receipts VALUES('SYN-E1','RESULT','SYN-DIGEST1',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'REVIEW_REQUIRED',100)")
            val before=p.query("SELECT * FROM receipts")
            val error=code {p.execute("INSERT OR REPLACE INTO receipts VALUES('SYN-E1','RESULT','SYN-DIGEST2',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'REVIEW_REQUIRED',200)")}
            val after=p.query("SELECT * FROM receipts")
            evidence("P03","error" to error,"immutable" to (before==after));assertEquals("IMMUTABLE_HISTORY",error);assertEquals(before,after)
        }
    }
    /** v2.8 r9-1 (review data #3): left-over files are decided at open, with the key in hand, instead of holding the store. */
    private fun recoveryCase(id:String,expected:String,prepare:(File)->Unit) {
        seedNotes();val main=ProtectedDatabase.file(context,name);prepare(main);ProtectedDatabase.takeRecovery()
        val before=witnesses();val creates=ShadowTextKeys.CREATES.get()
        val note=notes().use { it.query("SELECT note FROM notes").single()["note"] }
        val after=witnesses();evidence(id,"recovery" to expected,"before" to before,"after" to after)
        assertEquals("PUBLIC_SYNTHETIC_MANUAL_NOTE",note);assertEquals(expected,ProtectedDatabase.takeRecovery())
        assertEquals("ABSENT",after[".bak"]);assertEquals("ABSENT",after[".new"]);assertEquals(creates,ShadowTextKeys.CREATES.get())
    }
    @Test fun P04ValidBackupReplacesACorruptMain()=io {
        recoveryCase("P04","PROMOTED_BAK") {main->File(main.path+".bak").writeBytes(main.readBytes());main.writeBytes(byteArrayOf(1,4,9))}
    }
    @Test fun P05UnverifiedNewArtifactIsDropped()=io {
        recoveryCase("P05","DROPPED_NEW") {main->File(main.path+".new").writeText("PUBLIC_SYNTHETIC_UNVERIFIED_CIPHERTEXT_STAGE")}
    }
    @Test fun P06BackupOnlyIsPromoted()=io {
        recoveryCase("P06","PROMOTED_BAK") {main->File(main.path+".bak").writeBytes(main.readBytes());assertTrue(main.delete())}
    }
    @Test fun P06bUnusableBackupAndMainStayHeld()=io {
        seedNotes();val main=ProtectedDatabase.file(context,name);main.writeBytes(byteArrayOf(1,4,9));File(main.path+".bak").writeText("PUBLIC_SYNTHETIC_BAD_BACKUP")
        val before=witnesses();val error=code {notes().use{}}
        assertEquals("TEXT_STORE_RECOVERY_REVIEW_REQUIRED",error);assertEquals(before,witnesses());assertEquals("FAILED",ProtectedDatabase.takeRecovery())
    }
    @Test fun P07MissingKeyPreservesMainBackupAndPendingStage()=io {
        seedNotes();val main=ProtectedDatabase.file(context,name);File(main.path+".bak").writeBytes(main.readBytes());File(main.path+".new").writeText("PUBLIC_SYNTHETIC_STAGE")
        val before=witnesses();val creates=ShadowTextKeys.CREATES.get();ShadowTextKeys.KEYS.clear()
        val error=code{notes().use{}};evidence("P07","error" to error,"artifacts_preserved" to (before==witnesses()),"new_keys" to (ShadowTextKeys.CREATES.get()-creates))
        assertEquals("TEXT_STORE_KEY_UNAVAILABLE",error);assertEquals(before,witnesses());assertEquals(creates,ShadowTextKeys.CREATES.get())
    }
    @Test fun P08HealthyMainOnlyReopensWithoutRegression()=io {
        seedNotes();val before=witnesses();notes().use {assertEquals("PUBLIC_SYNTHETIC_MANUAL_NOTE",it.query("SELECT note FROM notes").single()["note"])}
        assertEquals(before,witnesses());evidence("P08","normal_reopen" to true,"artifacts_preserved" to true)
    }
    @Test fun P09RamReadIsNotAdvertisedAsDiskAuthentication()=io {
        notes().use {p->p.execute("INSERT INTO notes VALUES(1,'PUBLIC_SYNTHETIC_MANUAL_NOTE',1)")
            val main=ProtectedDatabase.file(context,name);val damaged=main.readBytes().apply {this[lastIndex]=(last().toInt() xor 1).toByte()};main.writeBytes(damaged)
            val read=p.query("SELECT note FROM notes").single()["note"];p.transaction {p.query("SELECT note FROM notes");Unit}
            assertEquals("PUBLIC_SYNTHETIC_MANUAL_NOTE",read);assertArrayEquals(damaged,main.readBytes())
            val error=code {p.execute("UPDATE notes SET revision=2")};assertEquals("TEXT_STORE_EXTERNAL_CHANGE_REQUIRES_REVIEW",error);assertArrayEquals(damaged,main.readBytes())
            evidence("P09","documented_ram_only_read" to true,"no_op_not_disk_authentication" to true,"changed_commit_error" to error,"actual_device_test" to false)
        }
    }
    @Test fun P10CachedTranscriptDuplicateKeepsOriginalCreationTime()=io {
        core().use {p->seedTranscript(p);val before=p.query("SELECT * FROM transcript_versions")
            p.execute("INSERT OR IGNORE INTO transcript_versions VALUES(?,?,?,?,?,?,?)","SYN-T1","a".repeat(64),"fixture-v1","b".repeat(64),"PUBLIC_SYNTHETIC_ORIGINAL","raw_review_required",200)
            assertEquals(before,p.query("SELECT * FROM transcript_versions"));evidence("P10","cached_replay_preserved" to true,"created_at_ms" to 100)
        }
    }
    @Test fun P11IdenticalReceiptReplayDoesNotAddOrReplaceRows()=io {
        core().use {p->val sql="INSERT OR REPLACE INTO receipts VALUES('SYN-E1','RESULT','SYN-DIGEST1',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'REVIEW_REQUIRED',100)"
            p.execute(sql);val before=p.query("SELECT rowid,* FROM receipts");p.execute(sql)
            assertEquals(before,p.query("SELECT rowid,* FROM receipts"));evidence("P11","receipt_rows" to 1,"identical_replay_preserved" to true)
        }
    }
    @Test fun P12OlderAuthenticatedSchemaUsesRecursiveDeleteFence()=io {
        AndroidSqlPort(context,name,checkNotNull(javaClass.getResourceAsStream("/call-core-v14-frozen.sql"))).use {seedTranscript(it)}
        core().use {p->assertTrue(p.query("SELECT name FROM sqlite_master WHERE name='transcript_versions_immutable_insert'").isEmpty())
            assertEquals(1L,p.query("PRAGMA recursive_triggers").single()["recursive_triggers"])
            val before=p.query("SELECT * FROM transcript_versions")
            val error=code {p.execute("INSERT OR REPLACE INTO transcript_versions VALUES(?,?,?,?,?,?,?)","SYN-T1","a".repeat(64),"fixture-v1","c".repeat(64),"PUBLIC_SYNTHETIC_REPLACEMENT","raw_review_required",200)}
            assertEquals("IMMUTABLE_HISTORY",error);assertEquals(before,p.query("SELECT * FROM transcript_versions"))
            evidence("P12","old_schema_not_replaced" to true,"production_recursive_fence" to true,"immutable" to true)
        }
    }
}
