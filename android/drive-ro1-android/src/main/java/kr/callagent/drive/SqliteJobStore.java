package kr.callagent.drive;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.util.Map;
import kr.co.callsummary.core.android.ProtectedDatabase;

/**
 * Durable Android store on the SAME existing repository SQLiteDatabase. Constructor performs no
 * migration or I/O. Host must run installTables in its explicit versioned database migration.
 * Host SourceReader reads trusted metadata/revisions inside this transaction, never raw audio.
 * This adapter was compiled/linted, but has not been attached to code8 or device-tested.
 */
public final class SqliteJobStore implements LocalJobStore {
    public interface SourceReader { Source source(SQLiteDatabase sameDatabase, String sourceKey); }
    private final SQLiteDatabase database;
    private final SourceReader sources;
    public SqliteJobStore(SQLiteDatabase existingDatabase, SourceReader sources) {
        if (existingDatabase == null || sources == null) throw new IllegalArgumentException("store_configuration");
        this.database = existingDatabase; this.sources = sources;
    }
    /** Only isolated relay tables. Never modifies approved summary, DONE, tasks, archive or source rows. */
    public static void installTables(SQLiteDatabase db) {
        if (!db.inTransaction()) throw new IllegalArgumentException("host_migration_transaction_required");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_v0_jobs (job_id TEXT PRIMARY KEY NOT NULL, frozen BLOB NOT NULL, phase TEXT NOT NULL, result_digest TEXT NOT NULL, result_revision INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_v0_active (source_id TEXT PRIMARY KEY NOT NULL, job_id TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_v0_receipts (owner_id TEXT NOT NULL, job_id TEXT NOT NULL, result_revision INTEGER NOT NULL, digest TEXT NOT NULL, payload BLOB NOT NULL, conflict INTEGER NOT NULL, hidden INTEGER NOT NULL, writer_verified INTEGER NOT NULL, PRIMARY KEY(owner_id,job_id,result_revision))");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_v0_collisions (owner_id TEXT NOT NULL, job_id TEXT NOT NULL, result_revision INTEGER NOT NULL, digest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(owner_id,job_id,result_revision,digest))");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_v0_revoked (owner_id TEXT PRIMARY KEY NOT NULL)");
    }
    @Override public <T> T transaction(Work<T> work) {
        try {
            return ProtectedDatabase.transaction(database,()->work.run(new Tx()));
        } catch (SQLiteException failed) { throw new StoreException(); }
    }
    private final class Tx implements Transaction {
        @Override public Source source(String key) { return sources.source(database,key); }
        @Override public Job job(String id) {
            try (Cursor c=database.query("drive_v0_jobs",null,"job_id=?",new String[]{id},null,null,null)) {
                if (!c.moveToFirst()) return null;
                return thaw(c.getBlob(c.getColumnIndexOrThrow("frozen")),Phase.valueOf(c.getString(c.getColumnIndexOrThrow("phase"))),
                        c.getString(c.getColumnIndexOrThrow("result_digest")),c.getLong(c.getColumnIndexOrThrow("result_revision")));
            }
        }
        @Override public String activeJobId(String key) {
            try (Cursor c=database.query("drive_v0_active",new String[]{"job_id"},"source_id=?",new String[]{key},null,null,null)) {
                return c.moveToFirst()?c.getString(0):null;
            }
        }
        @Override public void putJob(Job job) {
            Job existing=job(job.jobId); ContentValues state=new ContentValues();
            state.put("phase",job.phase.name());state.put("result_digest",job.acceptedResultDigest);state.put("result_revision",job.resultRevision);
            if (existing==null) {
                state.put("job_id",job.jobId);state.put("frozen",freeze(job));database.insertOrThrow("drive_v0_jobs",null,state);
            } else {
                if (!java.util.Arrays.equals(freeze(existing),freeze(job))) throw new StoreException();
                if (database.update("drive_v0_jobs",state,"job_id=?",new String[]{job.jobId})!=1) throw new StoreException();
            }
        }
        @Override public void setActiveJobId(String key,String id) {
            ContentValues v=new ContentValues();v.put("source_id",key);v.put("job_id",id);
            if (database.update("drive_v0_active",v,"source_id=?",new String[]{key})==0) database.insertOrThrow("drive_v0_active",null,v);
        }
        @Override public Receipt receipt(String owner,String id,long revision) {
            try (Cursor c=database.query("drive_v0_receipts",null,"owner_id=? AND job_id=? AND result_revision=?",
                    new String[]{owner,id,Long.toString(revision)},null,null,null)) {
                if (!c.moveToFirst()) return null;
                Job job=job(id); if (job==null || !job.source.ownerBinding.equals(owner)) throw new StoreException();
                WireCodec.Result result=new WireV0Codec().validateResult(c.getBlob(c.getColumnIndexOrThrow("payload")),job);
                if (!result.resultDigest.equals(c.getString(c.getColumnIndexOrThrow("digest"))) || result.revision!=revision) throw new StoreException();
                return new Receipt(job,result,flag(c,"conflict"),flag(c,"hidden"),flag(c,"writer_verified"),false);
            }
        }
        @Override public boolean ownerRevoked(String owner) {
            try (Cursor c=database.query("drive_v0_revoked",new String[]{"owner_id"},"owner_id=?",new String[]{owner},null,null,null)) {
                return c.moveToFirst();
            }
        }
        @Override public void revokeOwner(String owner) {
            if (ownerRevoked(owner)) return;
            ContentValues v=new ContentValues();v.put("owner_id",owner);database.insertOrThrow("drive_v0_revoked",null,v);
        }
        @Override public void putReceipt(String sourceKey,Receipt r) {
            Job j=job(r.jobId);if (j==null || !j.source.sourceKey.equals(sourceKey) || !j.source.ownerBinding.equals(r.ownerId)) throw new StoreException();
            ContentValues v=values(r);v.put("conflict",r.conflict?1:0);v.put("hidden",r.hidden?1:0);v.put("writer_verified",r.writerVerified?1:0);
            database.insertOrThrow("drive_v0_receipts",null,v); // UNIQUE conflict is never replaced.
        }
        @Override public void putCollision(String sourceKey,Receipt r) {
            Job j=job(r.jobId);if (j==null || !j.source.sourceKey.equals(sourceKey) || !j.source.ownerBinding.equals(r.ownerId)) throw new StoreException();
            database.insertWithOnConflict("drive_v0_collisions",null,values(r),SQLiteDatabase.CONFLICT_IGNORE);
        }
    }
    private static boolean flag(Cursor c,String key) { return c.getInt(c.getColumnIndexOrThrow(key))==1; }
    private static ContentValues values(Receipt r) {
        ContentValues v=new ContentValues();v.put("owner_id",r.ownerId);v.put("job_id",r.jobId);v.put("result_revision",r.revision);
        v.put("digest",r.digest);v.put("payload",r.bytes);return v;
    }
    private static byte[] freeze(Job j) {
        Source s=j.source;
        byte[] bytes=StrictJson.bytes(WireV0Codec.map("requestJson",new String(j.requestBytes,java.nio.charset.StandardCharsets.UTF_8),
                "placeholderJson",new String(j.placeholderBytes,java.nio.charset.StandardCharsets.UTF_8),"folderId",j.folderId,
                "trustedDriveOwnerId",s.trustedDriveOwnerId,"authorizationEpoch",j.authorizationEpoch,"policyRevision",s.policyRevision,
                "participantConfirmed",s.participantConfirmed,"excluded",s.excluded,"archived",s.archived));
        if (bytes.length>StrictJson.MAX_BYTES) throw new StoreException();
        return bytes;
    }
    private static Job thaw(byte[] bytes,Phase phase,String resultDigest,long revision) {
        Map<String,Object> m=StrictJson.object(StrictJson.parse(bytes));
        WireV0Codec.exact(m,"requestJson","placeholderJson","folderId","trustedDriveOwnerId","authorizationEpoch","policyRevision","participantConfirmed","excluded","archived");
        byte[] request=((String)m.get("requestJson")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] placeholder=((String)m.get("placeholderJson")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String,Object> r=WireV0Codec.validateRequest(request), owner=StrictJson.object(r.get("ownerBinding"));
        Source s=new Source((String)r.get("sourceId"),(String)r.get("audioSha"),n(r,"sourceVersion"),n(r,"audioBytes"),n(r,"baseRevision"),
                n(r,"userRevision"),n(r,"lockRevision"),n(r,"taskRevision"),n(r,"archiveRevision"),n(r,"leaseEpoch"),
                (String)owner.get("ownerId"),(String)owner.get("driveAccount"),(String)m.get("trustedDriveOwnerId"),(String)r.get("contextJson"),
                n(m,"policyRevision"),Boolean.TRUE.equals(m.get("participantConfirmed")),Boolean.TRUE.equals(m.get("excluded")),Boolean.TRUE.equals(m.get("archived")));
        Job j=new Job((String)r.get("jobId"),(String)m.get("folderId"),(String)r.get("audioFileId"),(String)r.get("requestFileId"),
                (String)r.get("resultFileId"),s,n(m,"authorizationEpoch"),(String)r.get("requestDigest"),phase,resultDigest,revision,request,placeholder);
        new WireV0Codec().validateResult(placeholder,j);return j;
    }
    private static long n(Map<String,Object> m,String key) { return WireV0Codec.number(m.get(key),0,StrictJson.MAX_INTEGER); }
}
