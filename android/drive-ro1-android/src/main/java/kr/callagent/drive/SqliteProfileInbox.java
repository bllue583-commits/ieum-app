package kr.callagent.drive;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import java.util.Map;
import kr.callagent.drive.LocalJobStore.Job;
import kr.callagent.drive.LocalJobStore.Source;
import kr.callagent.drive.LocalJobStore.Phase;

/**
 * Local consumer of already retrieved result bytes. No auth, raw audio, network or task-state writes.
 * SourceReader must read a caller-approved LOCAL source namespace in this same database transaction,
 * and return null for deleted sources or inactive/expired leases, including an unchanged lease epoch.
 * An imported connector manifest is not a native recording/board snapshot.
 */
public final class SqliteProfileInbox {
    public enum Status { READ_ONLY_CANDIDATE, PROVENANCE_PENDING, LEGACY_CANDIDATE, PENDING, DUPLICATE,
        HISTORY_ONLY, REVISION_CONFLICT, HELD_UNSUPPORTED_RESULT_PROFILE, HELD_INVALID_RESULT,
        HELD_REFERENCE_REJECTED, WRONG_RESULT_FILE_ID, SOURCE_QUARANTINED, CANCELLED, REVOKED,
        UNKNOWN_JOB, LOCAL_STORE_FAILED }
    public enum Evidence { PENDING, VERIFIED_CONTENT, REJECTED }
    public interface TrustedArtifactResolver {
        /** Local caller-pinned official bytes/receipts only. No I/O/auth inferred from JSON claims. */
        Evidence resolve(ReadonlyV1Codec.Validated result,byte[] frozenRequest);
    }
    public interface BeforeCas { void run(); }
    public static final class Receipt {
        public final String ownerId,jobId,resultFileId,kind,digest;public final long schemaVersion,revision;
        public final byte[] bytes;public final boolean conflict,hidden;
        Receipt(Job job,ProfileResultDispatcher.Result result,boolean conflict,boolean hidden){this.ownerId=job.source.ownerBinding;this.jobId=job.jobId;this.resultFileId=job.resultFileId;this.kind=result.kind;this.schemaVersion=result.schemaVersion;this.digest=result.digest;this.revision=result.revision;this.bytes=result.bytes.clone();this.conflict=conflict;this.hidden=hidden;}
    }
    public static final class Reply {
        public final Status status;public final Receipt receipt;Reply(Status s,Receipt r){this.status=s;this.receipt=r;}
    }
    public static final class View {
        public final Receipt receipt;public final String rawText,rawAnalysisJson,blindInputJson,ledgerJson;
        public final boolean conflict,hidden,applied=false,canonicalWriteAllowed=false,executionVerified=false,writerVerified=false;
        public final String nativeBinding="UNVERIFIED",displayMode="READ_ONLY_PROPOSAL";
        public final Evidence provenance;
        View(Receipt r,ProfileResultDispatcher.Result decoded,boolean conflict,Evidence evidence){
            this.receipt=r;this.conflict=conflict;this.hidden=r.hidden;this.provenance=evidence;
            if(decoded.api!=null){this.rawText=decoded.api.rawText;this.rawAnalysisJson=decoded.api.analysisJson;this.blindInputJson=null;this.ledgerJson=null;}
            else if(decoded.readOnly!=null){Map<String,Object> a=ReadonlyV1Codec.object(decoded.readOnly.envelope.get("readOnlyAnalysis"));this.rawText=decoded.readOnly.rawText;this.rawAnalysisJson=(String)a.get("rawAnalysisJson");this.blindInputJson=(String)a.get("blindInputJson");this.ledgerJson=(String)a.get("segmentLedgerJson");}
            else {Map<String,Object> x=StrictJson.object(StrictJson.parse(decoded.bytes));this.rawText=(String)StrictJson.object(x.get("stt")).get("text");this.rawAnalysisJson=null;this.blindInputJson=null;this.ledgerJson=null;}
        }
    }
    private static final class Gate extends RuntimeException {
        private static final long serialVersionUID=1L;final Status status;Gate(Status s){super(s.name());status=s;}
    }
    private final SQLiteDatabase db;private final SqliteJobStore jobs;private final ProfileResultDispatcher decoder=new ProfileResultDispatcher();
    private final TrustedArtifactResolver resolver;private final BeforeCas beforeCas;
    public SqliteProfileInbox(SQLiteDatabase sameDb,SqliteJobStore.SourceReader reader,TrustedArtifactResolver resolver){this(sameDb,reader,resolver,()->{});}
    public SqliteProfileInbox(SQLiteDatabase sameDb,SqliteJobStore.SourceReader reader,TrustedArtifactResolver resolver,BeforeCas hook){
        this.db=sameDb;this.jobs=new SqliteJobStore(sameDb,reader);this.resolver=resolver;this.beforeCas=hook;
        if(resolver==null||hook==null)throw new IllegalArgumentException("inbox_configuration");
    }
    public static void installTables(SQLiteDatabase db){
        SqliteJobStore.installTables(db);
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_profiles_receipts(owner_id TEXT NOT NULL,job_id TEXT NOT NULL,result_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,digest TEXT NOT NULL,payload BLOB NOT NULL,conflict INTEGER NOT NULL,hidden INTEGER NOT NULL,PRIMARY KEY(owner_id,job_id,kind,schema_version,result_revision))");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_profiles_collisions(owner_id TEXT NOT NULL,job_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,digest TEXT NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(owner_id,job_id,kind,schema_version,result_revision,digest))");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_profiles_heads(owner_id TEXT NOT NULL,result_id TEXT NOT NULL,job_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(owner_id,result_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_profiles_prepared(owner_id TEXT NOT NULL,result_id TEXT NOT NULL,job_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(owner_id,result_id,result_revision))");
    }
    /**
     * Explicit local registration from an approved external snapshot. Never derives trusted source
     * identity or native business revisions from this untrusted request. No raw open/read/upload.
     */
    public Reply registerImportedRequest(byte[] bytes,String sourceKey,String folderId){
        try{final byte[] frozen=bytes.clone();Map<String,Object> r=WireV0Codec.validateRequest(frozen);
            return jobs.transaction(tx->{Source trusted=tx.source(sourceKey);policy(tx,trusted);if(!sourceKey.equals(r.get("sourceId")))throw new Gate(Status.SOURCE_QUARANTINED);
                Map<String,Object> owner=StrictJson.object(r.get("ownerBinding"));
                if(!trusted.ownerBinding.equals(owner.get("ownerId"))||!trusted.driveAccount.equals(owner.get("driveAccount"))||!trusted.audioSha.equals(r.get("audioSha"))||trusted.sourceVersion!=(Long)r.get("sourceVersion")||trusted.audioBytes!=(Long)r.get("audioBytes")||trusted.leaseEpoch!=(Long)r.get("leaseEpoch"))throw new Gate(Status.SOURCE_QUARANTINED);
                // Revisions belong to the caller's approved external namespace, not a device board.
                if(trusted.baseRevision!=(Long)r.get("baseRevision")||trusted.userRevision!=(Long)r.get("userRevision")||trusted.lockRevision!=(Long)r.get("lockRevision")||trusted.taskRevision!=(Long)r.get("taskRevision")||trusted.archiveRevision!=(Long)r.get("archiveRevision")||!trusted.contextJson.equals(r.get("contextJson")))throw new Gate(Status.SOURCE_QUARANTINED);
                String id=(String)r.get("jobId");Job existing=tx.job(id);
                if(existing!=null){gate(tx,existing);if(!Arrays.equals(existing.requestBytes,frozen)||!existing.folderId.equals(folderId))return new Reply(Status.HELD_INVALID_RESULT,null);return new Reply(Status.DUPLICATE,null);}
                String active=tx.activeJobId(sourceKey);if(active!=null)throw new Gate(Status.SOURCE_QUARANTINED);
                // A result ID is permanently associated with one frozen job, across all profiles.
                try(Cursor c=db.query("drive_v0_jobs",new String[]{"frozen"},null,null,null,null,null)){
                    while(c.moveToNext()){
                        Map<String,Object> saved=StrictJson.object(StrictJson.parse(c.getBlob(0)));
                        Map<String,Object> old=WireV0Codec.validateRequest(((String)saved.get("requestJson")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        if(old.get("resultFileId").equals(r.get("resultFileId")))throw new Gate(Status.SOURCE_QUARANTINED);
                    }
                }
                Job j=new Job(id,folderId,(String)r.get("audioFileId"),(String)r.get("requestFileId"),(String)r.get("resultFileId"),trusted,0,(String)r.get("requestDigest"),Phase.SENT,"",-1,frozen,StrictJson.bytes(WireV0Codec.placeholder(r)));
                tx.putJob(j);tx.setActiveJobId(sourceKey,id);return new Reply(Status.PENDING,null);
            });
        }catch(Gate g){return new Reply(g.status,null);}catch(IllegalArgumentException malformed){return new Reply(Status.HELD_INVALID_RESULT,null);}catch(LocalJobStore.StoreException failed){return new Reply(Status.LOCAL_STORE_FAILED,null);}
    }
    public Reply receive(String jobId,String observedResultFileId,byte[] resultBytes){
        try{Job expected=jobs.transaction(tx->{Job j=tx.job(jobId);gate(tx,j);return j;});if(!expected.resultFileId.equals(observedResultFileId))return new Reply(Status.WRONG_RESULT_FILE_ID,null);
            ProfileResultDispatcher.Result result=decoder.validate(resultBytes,expected);Evidence evidence=evidence(result,expected);
            if(evidence==Evidence.REJECTED)return new Reply(Status.HELD_REFERENCE_REJECTED,null);beforeCas.run();
            return jobs.transaction(tx->{Job current=tx.job(jobId);Source now=gate(tx,current);if(!Arrays.equals(expected.requestBytes,current.requestBytes))throw new Gate(Status.SOURCE_QUARANTINED);
                Receipt prior=read(current,result.kind,result.schemaVersion,result.revision);
                if(prior!=null){if(prior.digest.equals(result.digest))return new Reply(result.revision<globalHead(current)?Status.HISTORY_ONLY:Status.DUPLICATE,prior);collision(current,result);return new Reply(Status.REVISION_CONFLICT,prior);}
                boolean conflict=current.source.manualConflict(now),hidden=now.archived||current.source.archiveRevision!=now.archiveRevision;
                long floor=globalHead(current);Receipt receipt=new Receipt(current,result,conflict,hidden);insert(receipt);
                if(result.revision<=floor)return new Reply(Status.HISTORY_ONLY,receipt);
                head(current,result);tx.putJob(current.receipt(result.digest,result.revision,result.proposal()));
                Status status=result.api!=null?Status.PROVENANCE_PENDING:result.readOnly!=null?(evidence==Evidence.VERIFIED_CONTENT?Status.READ_ONLY_CANDIDATE:Status.PROVENANCE_PENDING):(result.proposal()?Status.LEGACY_CANDIDATE:Status.PENDING);
                return new Reply(status,receipt);
            });
        }catch(Gate g){return new Reply(g.status,null);}catch(ProfileResultDispatcher.UnsupportedProfile unknown){return new Reply(Status.HELD_UNSUPPORTED_RESULT_PROFILE,null);}catch(IllegalArgumentException malformed){return new Reply(Status.HELD_INVALID_RESULT,null);}catch(LocalJobStore.StoreException failed){return new Reply(Status.LOCAL_STORE_FAILED,null);}
    }
    /** Caller preparation ledger, no remote write. Its revision is never reused across profiles. */
    public Reply rememberPrepared(String jobId,byte[] bytes){
        try{Job expected=jobs.transaction(tx->{Job j=tx.job(jobId);gate(tx,j);return j;});ProfileResultDispatcher.Result result=decoder.validate(bytes,expected);
            return jobs.transaction(tx->{Job j=tx.job(jobId);gate(tx,j);try(Cursor c=db.query("drive_profiles_prepared",null,"owner_id=? AND result_id=? AND result_revision=?",new String[]{j.source.ownerBinding,j.resultFileId,Long.toString(result.revision)},null,null,null)){
                if(c.moveToFirst())return new Reply(c.getString(c.getColumnIndexOrThrow("digest")).equals(result.digest)?Status.DUPLICATE:Status.REVISION_CONFLICT,null);
            }if(result.revision<=globalHead(j))return new Reply(Status.HISTORY_ONLY,null);db.insertOrThrow("drive_profiles_prepared",null,headValues(j,result));return new Reply(Status.PENDING,null);});
        }catch(Gate g){return new Reply(g.status,null);}catch(ProfileResultDispatcher.UnsupportedProfile unknown){return new Reply(Status.HELD_UNSUPPORTED_RESULT_PROFILE,null);}catch(IllegalArgumentException malformed){return new Reply(Status.HELD_INVALID_RESULT,null);}catch(LocalJobStore.StoreException failed){return new Reply(Status.LOCAL_STORE_FAILED,null);}
    }
    public long globalHead(String jobId){return jobs.transaction(tx->{Job j=tx.job(jobId);gate(tx,j);return globalHead(j);});}
    private long globalHead(Job j){long head=j.resultRevision;
        try(Cursor c=db.rawQuery("SELECT MAX(result_revision) FROM drive_v0_receipts WHERE owner_id=? AND job_id=?",new String[]{j.source.ownerBinding,j.jobId})){if(c.moveToFirst()&&!c.isNull(0))head=Math.max(head,c.getLong(0));}
        for(String table:new String[]{"drive_profiles_receipts","drive_profiles_prepared","drive_profiles_heads"})try(Cursor c=db.rawQuery("SELECT MAX(result_revision) FROM "+table+" WHERE owner_id=? AND result_id=?",new String[]{j.source.ownerBinding,j.resultFileId})){if(c.moveToFirst()&&!c.isNull(0))head=Math.max(head,c.getLong(0));}
        return head;
    }
    public View visible(String jobId){
        try{return jobs.transaction(tx->{Job j=tx.job(jobId);Source now=gate(tx,j);Receipt r=currentReceipt(tx,j);if(r==null||r.hidden||now.archived||now.archiveRevision!=j.source.archiveRevision)return null;
            ProfileResultDispatcher.Result decoded=decoder.validate(r.bytes,j);if(!decoded.proposal())return null;Evidence evidence=evidence(decoded,j);if(evidence==Evidence.REJECTED)return null;
            return new View(r,decoded,r.conflict||j.source.manualConflict(now),evidence);
        });}catch(Gate|IllegalArgumentException|LocalJobStore.StoreException blocked){return null;}
    }
    public Reply cancel(String id){return stop(id,false);}
    public Reply revoke(String id){return stop(id,true);}
    private Reply stop(String id,boolean revoke){try{return jobs.transaction(tx->{Job j=tx.job(id);if(j==null)return new Reply(Status.UNKNOWN_JOB,null);if(revoke)tx.revokeOwner(j.source.ownerBinding);tx.putJob(j.phase(revoke?Phase.REVOKED:Phase.CANCELLED));return new Reply(revoke?Status.REVOKED:Status.CANCELLED,null);});}catch(LocalJobStore.StoreException failed){return new Reply(Status.LOCAL_STORE_FAILED,null);}}
    private Evidence evidence(ProfileResultDispatcher.Result r,Job j){if(r.readOnly==null)return Evidence.PENDING;Evidence e=resolver.resolve(r.readOnly,j.requestBytes.clone());return e==null?Evidence.PENDING:e;}
    private static void policy(LocalJobStore.Transaction tx,Source s){if(s==null||!s.participantConfirmed||s.excluded)throw new Gate(Status.SOURCE_QUARANTINED);if(tx.ownerRevoked(s.ownerBinding))throw new Gate(Status.REVOKED);}
    private static Source gate(LocalJobStore.Transaction tx,Job j){if(j==null)throw new Gate(Status.UNKNOWN_JOB);if(j.phase==Phase.CANCELLED)throw new Gate(Status.CANCELLED);if(j.phase==Phase.REVOKED)throw new Gate(Status.REVOKED);if(j.terminal())throw new Gate(Status.SOURCE_QUARANTINED);Source now=tx.source(j.source.sourceKey);policy(tx,now);if(!j.source.sameIdentity(now)||!j.jobId.equals(tx.activeJobId(j.source.sourceKey)))throw new Gate(Status.SOURCE_QUARANTINED);return now;}
    private Receipt currentReceipt(LocalJobStore.Transaction tx,Job j){
        LocalJobStore.Receipt old=j.resultRevision<0?null:tx.receipt(j.source.ownerBinding,j.jobId,j.resultRevision);
        try(Cursor c=db.query("drive_profiles_heads",null,"owner_id=? AND result_id=? AND job_id=?",new String[]{j.source.ownerBinding,j.resultFileId,j.jobId},null,null,null)){
            if(c.moveToFirst()){Receipt r=read(j,c.getString(c.getColumnIndexOrThrow("kind")),c.getLong(c.getColumnIndexOrThrow("schema_version")),c.getLong(c.getColumnIndexOrThrow("result_revision")));if(r==null||!r.digest.equals(c.getString(c.getColumnIndexOrThrow("digest"))))throw new LocalJobStore.StoreException();if(old!=null&&old.revision>r.revision)return new Receipt(j,decoder.validate(old.bytes,j),old.conflict,old.hidden);return r;}
        }
        if(old==null)return null;
        return new Receipt(j,decoder.validate(old.bytes,j),old.conflict,old.hidden);
    }
    private Receipt read(Job j,String kind,long version,long revision){
        try(Cursor c=db.query("drive_profiles_receipts",null,"owner_id=? AND job_id=? AND kind=? AND schema_version=? AND result_revision=?",new String[]{j.source.ownerBinding,j.jobId,kind,Long.toString(version),Long.toString(revision)},null,null,null)){
            if(c.moveToFirst()){ProfileResultDispatcher.Result r=decoder.validate(c.getBlob(c.getColumnIndexOrThrow("payload")),j);if(!r.kind.equals(kind)||r.schemaVersion!=version||r.revision!=revision||!r.digest.equals(c.getString(c.getColumnIndexOrThrow("digest")))||!j.resultFileId.equals(c.getString(c.getColumnIndexOrThrow("result_id"))))throw new LocalJobStore.StoreException();return new Receipt(j,r,c.getInt(c.getColumnIndexOrThrow("conflict"))==1,c.getInt(c.getColumnIndexOrThrow("hidden"))==1);}
        }
        if(ProfileResultDispatcher.V0_KIND.equals(kind)&&version==0)try(Cursor c=db.query("drive_v0_receipts",null,"owner_id=? AND job_id=? AND result_revision=?",new String[]{j.source.ownerBinding,j.jobId,Long.toString(revision)},null,null,null)){
            if(c.moveToFirst()){ProfileResultDispatcher.Result r=decoder.validate(c.getBlob(c.getColumnIndexOrThrow("payload")),j);if(r.revision!=revision||!r.digest.equals(c.getString(c.getColumnIndexOrThrow("digest"))))throw new LocalJobStore.StoreException();return new Receipt(j,r,c.getInt(c.getColumnIndexOrThrow("conflict"))==1,c.getInt(c.getColumnIndexOrThrow("hidden"))==1);}
        }return null;
    }
    private void insert(Receipt r){ContentValues v=new ContentValues();v.put("owner_id",r.ownerId);v.put("job_id",r.jobId);v.put("result_id",r.resultFileId);v.put("kind",r.kind);v.put("schema_version",r.schemaVersion);v.put("result_revision",r.revision);v.put("digest",r.digest);v.put("payload",r.bytes);v.put("conflict",r.conflict?1:0);v.put("hidden",r.hidden?1:0);db.insertOrThrow("drive_profiles_receipts",null,v);}
    private void collision(Job j,ProfileResultDispatcher.Result r){ContentValues v=new ContentValues();v.put("owner_id",j.source.ownerBinding);v.put("job_id",j.jobId);v.put("kind",r.kind);v.put("schema_version",r.schemaVersion);v.put("result_revision",r.revision);v.put("digest",r.digest);v.put("payload",r.bytes);db.insertWithOnConflict("drive_profiles_collisions",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    private static ContentValues headValues(Job j,ProfileResultDispatcher.Result r){ContentValues v=new ContentValues();v.put("owner_id",j.source.ownerBinding);v.put("result_id",j.resultFileId);v.put("job_id",j.jobId);v.put("kind",r.kind);v.put("schema_version",r.schemaVersion);v.put("result_revision",r.revision);v.put("digest",r.digest);return v;}
    private void head(Job j,ProfileResultDispatcher.Result r){ContentValues v=headValues(j,r);try(Cursor c=db.query("drive_profiles_heads",new String[]{"job_id"},"owner_id=? AND result_id=?",new String[]{j.source.ownerBinding,j.resultFileId},null,null,null)){if(c.moveToFirst()&&!j.jobId.equals(c.getString(0)))throw new LocalJobStore.StoreException();}if(db.update("drive_profiles_heads",v,"owner_id=? AND result_id=?",new String[]{j.source.ownerBinding,j.resultFileId})==0)db.insertOrThrow("drive_profiles_heads",null,v);}
}
