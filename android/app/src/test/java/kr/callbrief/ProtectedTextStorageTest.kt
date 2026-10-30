package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** Actual memory SQLite + JCE AES-GCM and host AtomicFile; actual Android Keystore/OEM NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProtectedTextStorageTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val executor=Executors.newSingleThreadExecutor()
    private val name="privacy-canary.db"
    private val schema="CREATE TABLE notes(id INTEGER PRIMARY KEY,note TEXT,raw BLOB,revision INTEGER NOT NULL,done INTEGER NOT NULL);\n-- statement-break\nCREATE TABLE history(id INTEGER PRIMARY KEY,before_text TEXT,after_text TEXT);\n-- statement-break\nCREATE TRIGGER note_history AFTER UPDATE ON notes BEGIN INSERT INTO history(before_text,after_text) VALUES(OLD.note,NEW.note); END;"
    private val canaries=listOf("SYNTHETIC_MEMO_CANARY_14","SYNTHETIC_CONTACT_FILENAME_14","SYNTHETIC_RAW_TRANSCRIPT_14","SYNTHETIC_SUMMARY_14","SYNTHETIC_TOKEN_CANARY_14")
    @Before fun prepare(){context=RuntimeEnvironment.getApplication();ShadowTextAtomicFile.failNext=false;ShadowTextAtomicFile.failedStage=null;ShadowLog.clear()}
    @After fun stop(){executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    private fun <T> io(f:()->T):T=try{executor.submit<T>{f()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    private fun port(c:Context=context,n:String=name)=AndroidSqlPort(c,n,schema.byteInputStream())
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
    private fun absent(bytes:ByteArray){for(c in canaries)for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)){
        val needle=c.toByteArray(charset);var found=false
        for(i in 0..bytes.size-needle.size){var same=true;for(j in needle.indices)if(bytes[i+j]!=needle[j]){same=false;break};if(same){found=true;break}}
        assertFalse("plaintext canary found",found)
    }}
    private fun checkDisk(){context.noBackupFilesDir.walkTopDown().filter {it.isFile}.forEach {absent(it.readBytes())}}
    private fun blocked(f:()->Unit):String=try{f();fail("Expected protected hold");""}catch(e:CoreException){e.message.orEmpty()}
    private fun row(p:AndroidSqlPort)=p.query("SELECT * FROM notes WHERE id=1").single()
    private fun seed(p:AndroidSqlPort)=p.execute("INSERT INTO notes VALUES(1,?,?,1,1)",canaries[0],canaries[2].toByteArray())
    private fun evidence(id:String,vararg values:Pair<String,Any>){val o=JSONObject().put("id",id).put("scope","JCE_NATIVE_SQLITE_HOST_ONLY").put("pass",true);values.forEach{o.put(it.first,it.second)};File("build/reports/protected-text").apply{mkdirs()}.resolve("$id.json").writeText(o.toString(2))}
    @Test fun T01MemoRawHistoryAreSealedAndReopenExactly()=io{
        port().use {seed(it);it.execute("UPDATE notes SET note=?,revision=2 WHERE id=1",canaries[3]);assertEquals(canaries[0],it.query("SELECT before_text FROM history").single()["before_text"])}
        checkDisk();port().use {val r=row(it);assertEquals(canaries[3],r["note"]);assertArrayEquals(canaries[2].toByteArray(),r["raw"] as ByteArray);assertEquals(2L,r["revision"]);assertEquals(1L,r["done"]);assertEquals(1,it.query("SELECT * FROM history").size)}
        checkDisk();evidence("T01","plain_disk_canaries" to 0,"history_preserved" to true)
    }
    @Test fun T02AllFutureColumnsAndPayloadsUseSameProtection()=io{
        port().use {it.execute("CREATE TABLE arbitrary_future_sink(metadata_json TEXT,receipt BLOB)");it.execute("INSERT INTO arbitrary_future_sink VALUES(?,?)",canaries[1]+canaries[4],canaries[3].toByteArray())}
        checkDisk();port().use {val r=it.query("SELECT * FROM arbitrary_future_sink").single();assertTrue((r["metadata_json"] as String).contains(canaries[4]))};evidence("T02","column_allowlist_gap" to false)
    }
    @Test fun T03NoDatabaseWalJournalOrPlainTempExists()=io{
        port().use {seed(it);it.execute("CREATE TEMP TABLE scratch AS SELECT * FROM notes");assertEquals(canaries[0],it.query("SELECT note FROM scratch").single()["note"])}
        val sealed=ProtectedDatabase.file(context,name);assertTrue(sealed.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath+File.separator));assertFalse(context.getDatabasePath(name).exists())
        listOf("-wal","-shm","-journal").forEach{assertFalse(File(sealed.path+it).exists())};checkDisk();evidence("T03","plaintext_sqlite_file" to false,"noBackup" to true)
    }
    @Test fun T04ControlledCommitFailureKeepsOldStateAndEncryptedStageOnly()=io{
        port().use {p->seed(p);val file=ProtectedDatabase.file(context,name);val prior=sha(file);ShadowTextAtomicFile.failNext=true
            assertEquals("TEXT_STORE_COMMIT_FAILED",blocked{p.execute("UPDATE notes SET note=?,revision=2",canaries[3])});assertEquals(prior,sha(file));assertEquals(canaries[0],row(p)["note"])
            absent(checkNotNull(ShadowTextAtomicFile.failedStage));assertFalse(File(file.path+".new").exists())
        };port().use {assertEquals(1L,row(it)["revision"])};checkDisk();evidence("T04","actual_process_kill" to false,"rollback" to true)
    }
    @Test fun T05MissingKeyDuringOpenCannotReadOrOverwrite()=io{
        port().use {p->seed(p);val file=ProtectedDatabase.file(context,name);val prior=sha(file);val created=ShadowTextKeys.CREATES.get();ShadowTextKeys.KEYS.clear()
            assertEquals("TEXT_STORE_KEY_UNAVAILABLE",blocked{row(p)});assertEquals("TEXT_STORE_KEY_UNAVAILABLE",blocked{p.execute("UPDATE notes SET note=?",canaries[3])});assertEquals(prior,sha(file));assertEquals(created,ShadowTextKeys.CREATES.get())
        };evidence("T05","recreated_key" to 0,"overwrite" to 0)
    }
    @Test fun T06MissingKeyAfterReopenCannotInitializeEmptyReplacement()=io{
        port().use {seed(it)};val file=ProtectedDatabase.file(context,name);val prior=sha(file);val count=ShadowTextKeys.CREATES.get();ShadowTextKeys.KEYS.clear()
        assertEquals("TEXT_STORE_KEY_UNAVAILABLE",blocked{port().close()});assertEquals(count,ShadowTextKeys.CREATES.get());assertEquals(prior,sha(file));evidence("T06","reset_on_missing_key" to false)
    }
    @Test fun T07WrongKeyWhileOpenCannotReplaceCiphertext()=io{
        port().use {p->seed(p);val file=ProtectedDatabase.file(context,name);val prior=sha(file);val alias=ShadowTextKeys.KEYS.keys.single();ShadowTextKeys.KEYS[alias]=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
            assertEquals("TEXT_STORE_AUTHENTICATION_FAILED",blocked{p.execute("UPDATE notes SET note=?",canaries[3])});assertEquals(prior,sha(file))
        };evidence("T07","wrong_key_overwrite" to 0)
    }
    @Test fun T08CorruptCiphertextFailsClosedAndPreservesBytes()=io{
        port().use {seed(it)};val file=ProtectedDatabase.file(context,name);val b=file.readBytes();b[b.lastIndex]=(b.last().toInt() xor 1).toByte();file.writeBytes(b);val prior=sha(file);val count=ShadowTextKeys.CREATES.get()
        assertEquals("TEXT_STORE_AUTHENTICATION_FAILED",blocked{port().close()});assertEquals(prior,sha(file));assertEquals(count,ShadowTextKeys.CREATES.get());evidence("T08","corrupt_replacement" to 0)
    }
    @Test fun T09LegacySqliteAndWalAreHeldWithoutMoveOrDeletion()=io{
        val legacy=context.getDatabasePath(name).apply{parentFile!!.mkdirs()};SQLiteDatabase.openOrCreateDatabase(legacy,null).use {it.enableWriteAheadLogging();it.execSQL("CREATE TABLE old(note TEXT)");it.execSQL("INSERT INTO old VALUES(?)",arrayOf(canaries[0]))
            val before=ProtectedDatabase.legacyFiles(context,name).filter {f->f.isFile}.associate {f->f.path to sha(f)}
            assertEquals("TEXT_STORE_LEGACY_REVIEW_REQUIRED",blocked{port().close()});assertEquals(0,ShadowTextKeys.CREATES.get());assertFalse(ProtectedDatabase.file(context,name).exists());assertEquals(before,ProtectedDatabase.legacyFiles(context,name).filter {f->f.isFile}.associate {f->f.path to sha(f)})
        };evidence("T09","auto_migration" to false,"old_files_deleted" to 0)
    }
    @Test fun T11NestedFailureEvenWhenCaughtCannotCommitPartialState()=io{
        port().use {p->seed(p);val before=sha(ProtectedDatabase.file(context,name));assertEquals("TEXT_STORE_TRANSACTION_ROLLED_BACK",blocked{p.transaction{
            p.execute("UPDATE notes SET revision=2");try{p.transaction<Unit>{p.execute("UPDATE notes SET note=?",canaries[3]);throw IllegalStateException("SYNTHETIC_PRIVATE_ERROR_CANARY")}}catch(_:IllegalStateException){};Unit
        }});assertEquals(before,sha(ProtectedDatabase.file(context,name)));assertEquals(1L,row(p)["revision"]);assertEquals(0,p.query("SELECT * FROM history").size)};evidence("T11","caught_nested_failure_committed" to false)
    }
    @Test fun T12TwoStoreHandlesShareCasStateAndKeepDone()=io{
        port().use {a->seed(a);port().use {b->b.execute("UPDATE notes SET note=?,revision=2 WHERE revision=1",canaries[3]);a.execute("UPDATE notes SET note=? WHERE revision=1",canaries[1]);assertEquals(canaries[3],row(a)["note"]);assertEquals(1L,row(b)["done"])}}
        port().use {assertEquals(2L,row(it)["revision"]);assertEquals(1,it.query("SELECT * FROM history").size)};evidence("T12","manual_done_preserved" to true,"stale_overwrite" to false)
    }
    @Test fun T14NativeMetadataAndManualDoneRoundTripHasZeroDiskCanaries()=io{
        val keys=HostVaultKeys();GeneralLocalStoreTest.host(context,keys).use {s->s.initializeEmpty();val source=GeneralLocalStoreTest.Source().apply{put(name=canaries[1]+".wav")};s.importSelected(source);var row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,canaries[0],"done");row=s.snapshot().getJSONObject(0);s.edit(row.getString("file_id"),row,canaries[3],"done")}
        checkDisk();GeneralLocalStoreTest.host(context,keys).use {s->val row=s.snapshot().getJSONObject(0);assertEquals(canaries[1]+".wav",row.getString("display_name"));assertEquals(canaries[3],row.getString("manual_note"));assertEquals("done",row.getString("manual_status"));assertEquals(2L,row.getLong("user_revision"))};checkDisk();evidence("T14","native_note_filename_disk_canaries" to 0,"done_preserved" to true)
    }
    @Test fun T15UnacceptedSourceScopeIsHeldBeforeOpening()=io {
        val keys=HostVaultKeys();val generated=GeneralLocalStoreTest.Source().apply {put()}
        val unaccepted=object:SelectedRecordingSource by generated {override val explicitlySyntheticScope=false}
        GeneralLocalStoreTest.host(context,keys).use {s->s.initializeEmpty();val r=s.importSelected(unaccepted);assertEquals(1,r.held);assertEquals(0,generated.opens);assertEquals(0,s.snapshot().length())}
        evidence("T15","unaccepted_audio_opens" to 0)
    }
    @Test fun T16CapacityFailureDoesNotTruncateOrOverwriteExisting()=io {
        port().use {p->seed(p);val file=ProtectedDatabase.file(context,name);val prior=sha(file)
            assertEquals("TEXT_STORE_CAPACITY_REQUIRES_REVIEW",blocked{p.execute("UPDATE notes SET note=?", "x".repeat(ProtectedDatabase.MAX_PLAIN_BYTES))})
            assertEquals(prior,sha(file));assertEquals(canaries[0],row(p)["note"])
        };evidence("T16","truncated" to false,"existing_preserved" to true)
    }
    @Test fun T17RejectedConditionalTransactionReturnsStatusWithoutWrites()=io {
        port().use {p->seed(p);val file=ProtectedDatabase.file(context,name);val prior=sha(file)
            ProtectedDatabase.open(context,name,null).use {h->val result=ProtectedDatabase.conditionalTransaction(h.database(),{h.database().execSQL("UPDATE notes SET revision=2");"REJECTED"},{false});assertEquals("REJECTED",result)}
            assertEquals(1L,row(p)["revision"]);assertEquals(prior,sha(file))
        };evidence("T17","rejected_receipt_committed" to false)
    }
    @Test fun T19AtomicBackupWithLostKeyCannotGenerateOrReset()=io {
        port().use {seed(it)};val file=ProtectedDatabase.file(context,name);val backup=File(file.path+".bak");backup.writeBytes(file.readBytes());val prior=sha(backup);val count=ShadowTextKeys.CREATES.get();ShadowTextKeys.KEYS.clear()
        assertEquals("TEXT_STORE_KEY_UNAVAILABLE",blocked{port().close()});assertEquals(prior,sha(backup));assertEquals(count,ShadowTextKeys.CREATES.get());evidence("T19","backup_key_replacement" to 0)
    }
    @Test fun T20UnknownSqlErrorNeverReturnsInjectedMessageOrCause()=io {
        port().use {p->p.execute("CREATE TRIGGER private_failure BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT,'SYNTHETIC_TOKEN_CANARY_14'); END")
            try {seed(p);fail("injected failure expected")} catch(e:CoreException){assertEquals("SQLITE_OPERATION_FAILED",e.message);assertNull(e.cause)}
        };evidence("T20","private_sql_error_echo" to 0)
    }

    @Test fun T21UnregisteredPlainDiskCannotBeUsedAsProtectedRelayStore()=io {
        val raw=context.getDatabasePath("synthetic-unsafe-port.db").apply{parentFile!!.mkdirs()}
        SQLiteDatabase.openOrCreateDatabase(raw,null).use { db->
            assertEquals("TEXT_STORE_UNREGISTERED_DISK_DATABASE",blocked{ProtectedDatabase.transaction(db){db.execSQL("CREATE TABLE rejected(body TEXT)");Unit}})
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='rejected'",null).use {assertFalse(it.moveToFirst())}
        };evidence("T21","unprotected_relay_fallback" to false)
    }

    @Test fun T22ProtectedCapabilityIsRequiredAndLostKeyInvalidatesIt()=io {
        port().use {p->p.requireProtectedPersistence();assertEquals("KEYSTORE_AES_GCM_SQL_SNAPSHOT_V1",AndroidSqlPort.PERSISTENCE_FORMAT);ShadowTextKeys.KEYS.clear();assertEquals("TEXT_STORE_KEY_UNAVAILABLE",blocked{p.requireProtectedPersistence()})}
        SQLiteDatabase.create(null).use {db->assertEquals("TEXT_STORE_PROTECTED_PORT_REQUIRED",blocked{ProtectedDatabase.requireSealedPersistence(db)})}
        evidence("T22","memory_only_fake_capability" to false,"lost_key_capability" to false)
    }

}
