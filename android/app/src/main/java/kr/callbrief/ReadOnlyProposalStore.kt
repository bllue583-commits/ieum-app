package kr.callbrief

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kr.co.callsummary.core.android.ProtectedDatabase
import android.os.Looper
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.LocalJobStore
import kr.callagent.drive.SqliteProfileInbox
import kr.callagent.drive.StrictJson
import kr.callagent.drive.WireV0Codec
import kr.co.callsummary.core.JsonCanonical
import org.json.JSONObject

/** Additive RO1 inbox in the existing local-v2 DB. No native mapping, auth, audio or canonical writes. */
internal class ReadOnlyProposalStore(private val context: Context, private val checkCancelled: () -> Unit = {}) : AutoCloseable {
    data class Pin(val id: String, val label: String, val requestSha: String, val resultSha: String)
    data class Entry(val pin: Pin, val status: String, val view: SqliteProfileInbox.View?, val nativeRecordsChanged: Boolean)
    data class Import(val profile: String, val status: SqliteProfileInbox.Status)
    companion object {
        const val CONNECTION = "DRIVE_AUTH_NOT_CONNECTED"
        const val CONNECTION_MESSAGE = "Drive 인증 미연결\n자동 수신 없음"
        val PINS = listOf(
            Pin("codec_example", "합성 인수 예시", "9af8ea7d71570c26d3d8c3dd28ff7a43e6c10068d32619e1769bd6c4865a1448", "98650ee1750df8300cabbcfa4699be409dc353cb47e6cde8482ed741a74f4d23"),
            Pin("selected_revision3", "선정 합성 결과 · 3차", "452a8f50ab095072992c178aea49057e12382ec117fad6da971a6847cf06d9b2", "731a2980ed57650719ca718dc5a5d1c6a477dd466951eea0e4bebaf6a987c4fd")
        )
        fun approved(request: ByteArray, result: ByteArray): Pin = PINS.singleOrNull {
            DriveRelay.sha256(request) == it.requestSha && DriveRelay.sha256(result) == it.resultSha
        } ?: throw IllegalArgumentException("SYNTHETIC_PIN_NOT_APPROVED")
        fun readBounded(input: java.io.InputStream): ByteArray = input.use {
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n=it.read(buffer);if(n<0)break;require(out.size()+n<=524288){"RESULT_SIZE_LIMIT"};out.write(buffer,0,n) }
            out.toByteArray()
        }
    }
    private val protectedStore:ProtectedDatabase
    private val db: SQLiteDatabase
    private val inbox: SqliteProfileInbox
    init {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "READ_ONLY_IO_THREAD_REQUIRED" }
        checkCancelled()
        GeneralLocalStore.catalog(context) // Existing schema/overlay initialization, without audio/key access.
        protectedStore=ProtectedDatabase.open(context,GeneralLocalStore.DATABASE,null)
        db=protectedStore.database()
        db.setForeignKeyConstraintsEnabled(true)
        try {
            ProtectedDatabase.transaction(db) {
            SqliteProfileInbox.installTables(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS eum_ro_schema(version INTEGER PRIMARY KEY CHECK(version=1))")
            db.execSQL("INSERT OR IGNORE INTO eum_ro_schema(version) VALUES(1)")
            db.execSQL("CREATE TABLE IF NOT EXISTS eum_ro_sources(profile_id TEXT PRIMARY KEY,source_id TEXT UNIQUE NOT NULL,request BLOB NOT NULL,active INTEGER NOT NULL CHECK(active IN(0,1)),synthetic_scope_approved INTEGER NOT NULL CHECK(synthetic_scope_approved=1))")
            db.execSQL("CREATE TABLE IF NOT EXISTS eum_ro_imports(profile_id TEXT PRIMARY KEY,job_id TEXT UNIQUE NOT NULL,result_id TEXT NOT NULL,native_digest TEXT NOT NULL,status TEXT NOT NULL)")
            for (table in listOf("drive_profiles_receipts","drive_v0_receipts")) {
                db.execSQL("CREATE TRIGGER IF NOT EXISTS eum_${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'READ_ONLY_RECEIPT_IMMUTABLE'); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS eum_${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'READ_ONLY_RECEIPT_IMMUTABLE'); END")
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS eum_ro1_no_replace BEFORE INSERT ON drive_profiles_receipts WHEN EXISTS(SELECT 1 FROM drive_profiles_receipts WHERE owner_id=NEW.owner_id AND job_id=NEW.job_id AND kind=NEW.kind AND schema_version=NEW.schema_version AND result_revision=NEW.result_revision) BEGIN SELECT RAISE(ABORT,'READ_ONLY_RECEIPT_IMMUTABLE'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS eum_ro0_no_replace BEFORE INSERT ON drive_v0_receipts WHEN EXISTS(SELECT 1 FROM drive_v0_receipts WHERE owner_id=NEW.owner_id AND job_id=NEW.job_id AND result_revision=NEW.result_revision) BEGIN SELECT RAISE(ABORT,'READ_ONLY_RECEIPT_IMMUTABLE'); END")
                checkCancelled()
            }
        } catch(error:Exception) { protectedStore.close();throw error }
        // Missing original packet/ZIP remains pending. Exact quote/hash checks are not writer authentication.
        inbox=SqliteProfileInbox(db,::source,{ _, _ -> SqliteProfileInbox.Evidence.PENDING },checkCancelled)
    }
    private fun source(sameDb: SQLiteDatabase, key: String): LocalJobStore.Source? {
        sameDb.query("eum_ro_sources",null,"source_id=?",arrayOf(key),null,null,null).use { c ->
            if(!c.moveToFirst() || c.getInt(c.getColumnIndexOrThrow("active"))!=1 || c.getInt(c.getColumnIndexOrThrow("synthetic_scope_approved"))!=1)return null
            val pin=PINS.singleOrNull { it.id==c.getString(c.getColumnIndexOrThrow("profile_id")) } ?: return null
            val raw=c.getBlob(c.getColumnIndexOrThrow("request"));if(DriveRelay.sha256(raw)!=pin.requestSha)return null
            val r=WireV0Codec.validateRequest(raw)
            @Suppress("UNCHECKED_CAST") val owner=r["ownerBinding"] as Map<String,Any?>
            fun n(k:String)=(r[k] as Number).toLong()
            // These flags authorize only the explicit frozen synthetic external namespace. They do
            // not attest a real participant, Google grant, native recording ID or device business snapshot.
            return LocalJobStore.Source(key,r["audioSha"] as String,n("sourceVersion"),n("audioBytes"),n("baseRevision"),n("userRevision"),n("lockRevision"),n("taskRevision"),n("archiveRevision"),n("leaseEpoch"),
                owner["ownerId"] as String,owner["driveAccount"] as String,"SYNTHETIC_LOCAL_ALLOWLIST",r["contextJson"] as String,1L,true,false,false)
        }
    }
    fun importSynthetic(request: ByteArray, result: ByteArray): Import {
        checkCancelled();val pin=approved(request,result);val r=WireV0Codec.validateRequest(request)
        val sourceKey=r["sourceId"] as String;val job=r["jobId"] as String;val resultId=r["resultFileId"] as String
        return ProtectedDatabase.conditionalTransaction(db, {
            checkCancelled()
            db.query("eum_ro_sources",null,"profile_id=?",arrayOf(pin.id),null,null,null).use { c ->
                if(!c.moveToFirst()) {
                    val v=ContentValues();v.put("profile_id",pin.id);v.put("source_id",sourceKey);v.put("request",request);v.put("active",1);v.put("synthetic_scope_approved",1)
                    db.insertOrThrow("eum_ro_sources",null,v)
                } else require(c.getBlob(c.getColumnIndexOrThrow("request")).contentEquals(request)) { "FROZEN_REQUEST_CONFLICT" }
            }
            val registered=inbox.registerImportedRequest(request,sourceKey,"SYNTHETIC_LOCAL_IMPORT_NO_DRIVE")
            if(registered.status !in setOf(SqliteProfileInbox.Status.PENDING,SqliteProfileInbox.Status.DUPLICATE)) return@conditionalTransaction Import(pin.id,registered.status)
            // resultId is the fixed local synthetic expectation, not an observed remote Drive receipt.
            val received=inbox.receive(job,resultId,result)
            if(received.status in setOf(SqliteProfileInbox.Status.PROVENANCE_PENDING,SqliteProfileInbox.Status.READ_ONLY_CANDIDATE,SqliteProfileInbox.Status.DUPLICATE)) {
                val v=ContentValues();v.put("profile_id",pin.id);v.put("job_id",job);v.put("result_id",resultId);v.put("native_digest",nativeDigest());v.put("status",received.status.name)
                if(received.status!=SqliteProfileInbox.Status.DUPLICATE)db.insertOrThrow("eum_ro_imports",null,v)
                checkCancelled()
            }
            Import(pin.id,received.status)
        }, { it.status in setOf(SqliteProfileInbox.Status.PROVENANCE_PENDING,SqliteProfileInbox.Status.READ_ONLY_CANDIDATE,SqliteProfileInbox.Status.DUPLICATE) })
    }
    private fun nativeDigest(): String {
        val rows=mutableListOf<List<Any?>>()
        db.rawQuery("SELECT recording_id,source_version,head_revision,user_revision,lock_revision FROM recordings WHERE owner_key=? ORDER BY recording_id",arrayOf(GeneralLocalStore.OWNER)).use { c ->
            while(c.moveToNext())rows+=listOf(c.getString(0),c.getLong(1),c.getLong(2),c.getLong(3),c.getLong(4))
        }
        db.rawQuery("SELECT revision FROM board_state WHERE owner_key=?",arrayOf(GeneralLocalStore.OWNER)).use { c -> if(c.moveToFirst())rows+=listOf("board",c.getLong(0)) }
        db.rawQuery("SELECT recording_id,archived,revision FROM local_visibility ORDER BY recording_id",null).use { c -> while(c.moveToNext())rows+=listOf("visibility",c.getString(0),c.getLong(1),c.getLong(2)) }
        return JsonCanonical.digest(rows)
    }
    fun list(): List<Entry> {
        checkCancelled();val out=mutableListOf<Entry>();val digest=nativeDigest()
        db.query("eum_ro_imports",null,null,null,null,null,"profile_id").use { c ->
            while(c.moveToNext()) {
                val pin=PINS.singleOrNull { it.id==c.getString(c.getColumnIndexOrThrow("profile_id")) } ?: continue
                out+=Entry(pin,c.getString(c.getColumnIndexOrThrow("status")),inbox.visible(c.getString(c.getColumnIndexOrThrow("job_id"))),digest!=c.getString(c.getColumnIndexOrThrow("native_digest")))
            }
        };return out
    }
    override fun close() { protectedStore.close() }
}
