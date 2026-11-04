package kr.co.ieum.discovery;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** App-local discovery DTOs. These are not a worker, account, or transport wire schema. */
public final class DiscoveryTypes {
    private DiscoveryTypes() {}
    public enum Status { WAITING_STABLE, WAITING_PARTIAL, WAITING_METADATA, HELD_EXCLUDED,
        HELD_UNCONFIRMED, HELD_UNSUPPORTED, READY, READING, RETRY, WAITING_SOURCE,
        WAITING_GRANT, WAITING_LOCAL_STORAGE, WAITING_CONTAINER, STORED, UNCHANGED, MISSING_ALIAS }
    public enum Permission { CONFIRMED_ALLOWED, EXCLUDED, UNCONFIRMED }
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
        public Failure(String code, Throwable cause) { super(code,cause); this.code=code; }
    }
    public static final class Selection {
        public final String ownerId, treeUri; public final long revision;
        public Selection(String ownerId,String treeUri,long revision) {
            if(ownerId==null || !ownerId.matches("[A-Za-z0-9_-]{1,80}") || treeUri==null
                    || !treeUri.startsWith("content://") || treeUri.length()>8192 || revision<1)
                throw new Failure("EXPLICIT_SELECTED_TREE_REQUIRED");
            this.ownerId=ownerId;this.treeUri=treeUri;this.revision=revision;
        }
        public boolean equals(Object o) { if(!(o instanceof Selection))return false; Selection s=(Selection)o;
            return ownerId.equals(s.ownerId)&&treeUri.equals(s.treeUri)&&revision==s.revision; }
        public int hashCode(){return Objects.hash(ownerId,treeUri,revision);}
    }
    public static final class Moment {
        /** bootId/elapsedMs: monotonic time inside one boot (stability, leases). wallMs: wall clock, used for the daily recheck across reboots. */
        public final String bootId; public final long elapsedMs, wallMs;
        public Moment(String bootId,long elapsedMs){this(bootId,elapsedMs,System.currentTimeMillis());}
        public Moment(String bootId,long elapsedMs,long wallMs){if(bootId==null||bootId.isEmpty()||elapsedMs<0||wallMs<0)throw new Failure("INVALID_CLOCK");this.bootId=bootId;this.elapsedMs=elapsedMs;this.wallMs=wallMs;}
    }
    public interface Clock { Moment now(); }
    public static final class Config {
        /** Largest recording read into the vault (design 3.2 #5, review pipeline #13; was 25,000,000).
         * Basis, not measured on the phone yet: Samsung call recordings are AAC in .m4a. At 64 kbps that is about
         * 0.48 MB a minute, at 128 kbps about 0.96 MB a minute, so 25 MB stopped calls at roughly 26 to 52 minutes and
         * 60 MB allows roughly 62 to 125 minutes. The real bytes per minute must be checked on the device (file size
         * divided by call length); heartbeat heldUnsupported shows how many files are still over this line.
         * One file is read into one array of this size (RecordingDiscovery.read), which the app heap holds easily.
         * The vault binding (AudioBinding.MAX_BYTES) and the database checks use the same number. */
        public static final long MAX_AUDIO_BYTES=60_000_000L;
        public final long quietMs, leaseMs, recheckMs, maxBytes; public final int pageSize;
        public Config(long quietMs,long leaseMs,long recheckMs,long maxBytes,int pageSize){
            if(quietMs<1||leaseMs<1||recheckMs<quietMs||maxBytes<44||maxBytes>MAX_AUDIO_BYTES||pageSize<1||pageSize>1000)
                throw new Failure("INVALID_DISCOVERY_CONFIG");
            this.quietMs=quietMs;this.leaseMs=leaseMs;this.recheckMs=recheckMs;this.maxBytes=maxBytes;this.pageSize=pageSize;
        }
        /** Same settings with a lower size line (the local store may still carry the old 25 MB database check). */
        public Config withMaxBytes(long limit){return limit>=maxBytes?this:new Config(quietMs,leaseMs,recheckMs,limit,pageSize);}
        /** Files already stored are looked at again at most once a day; the page is large so one inventory covers a big folder. */
        public static Config conservative(){return new Config(20000,120000,86_400_000L,MAX_AUDIO_BYTES,512);}
    }
    public static final class Metadata {
        public final String documentId, displayName, mimeType;
        public final Long bytes, modifiedMs; public final boolean partial, virtualDocument;
        public Metadata(String id,String name,String mime,Long bytes,Long modified,boolean partial,boolean virtual){
            if(id==null||id.isEmpty()||id.length()>8192||name==null||name.length()>4096||mime==null||mime.length()>256)
                throw new Failure("INVALID_DOCUMENT_METADATA");
            this.documentId=id;displayName=name;mimeType=mime;this.bytes=bytes;modifiedMs=modified;
            this.partial=partial;virtualDocument=virtual;
        }
        public boolean equals(Object o){if(!(o instanceof Metadata))return false;Metadata m=(Metadata)o;
            return documentId.equals(m.documentId)&&displayName.equals(m.displayName)&&mimeType.equals(m.mimeType)
                &&Objects.equals(bytes,m.bytes)&&Objects.equals(modifiedMs,m.modifiedMs)&&partial==m.partial&&virtualDocument==m.virtualDocument;}
        public int hashCode(){return Objects.hash(documentId,displayName,mimeType,bytes,modifiedMs,partial,virtualDocument);}
    }
    public static final class Page {
        public final List<Metadata> documents; public final String nextCursor; public final boolean inventoryComplete;
        public Page(List<Metadata> docs,String next){this(docs,next,true);}
        public Page(List<Metadata> docs,String next,boolean complete){documents=new ArrayList<>(docs);nextCursor=next;inventoryComplete=complete;}
    }
    /** Provider must attest selected-tree membership on list/stat/open; no arbitrary URI opener. */
    public interface Documents {
        boolean hasReadGrant(Selection selected);
        Page listPage(Selection selected,String cursor,int limit) throws Exception;
        /** Accepted page cache writes run only inside the admitted queue transaction. */
        default void acceptPage(Selection selected,Page page) throws Exception {}
        /** Around the policy decisions of one page (and its acceptPage): settings can be read once for the whole page. */
        default void beginDecisions(Selection selected) {}
        default void endDecisions(Selection selected) {}
        Metadata stat(Selection selected,String documentId) throws Exception;
        InputStream open(Selection selected,String documentId) throws Exception;
    }
    public static final class Decision {
        public final Permission permission; public final long revision; public final String subjectRef;
        public final boolean synthetic;
        public Decision(Permission permission,long revision,String subjectRef,boolean synthetic){
            if(permission==null||revision<0||(permission==Permission.CONFIRMED_ALLOWED&&(subjectRef==null||subjectRef.isEmpty())))
                throw new Failure("EXPLICIT_SUBJECT_BINDING_REQUIRED");
            this.permission=permission;this.revision=revision;this.subjectRef=subjectRef;this.synthetic=synthetic;
        }
        public boolean equals(Object o){if(!(o instanceof Decision))return false;Decision d=(Decision)o;
            return permission==d.permission&&revision==d.revision&&Objects.equals(subjectRef,d.subjectRef)&&synthetic==d.synthetic;}
        public int hashCode(){return Objects.hash(permission,revision,subjectRef,synthetic);}
    }
    public interface Policy { Decision decide(Selection selected,Metadata metadata); }
    public interface Guard { boolean allowed(); }
    public static final class Seed {
        public final String recordingId;public final List<Version> versions;
        public Seed(String id,List<Version> versions){recordingId=id;this.versions=new ArrayList<>(versions);}
    }
    public interface Bootstrap { Seed find(Selection selected,Metadata metadata,Decision decision); }
    public static final class Commit {
        public final String operationId,recordingId,audioSha256; public final Selection selection;
        public final Metadata metadata; public final Decision decision;
        public final long headRevision,sourceVersion,bytes,originLeaseEpoch;
        public Commit(String operationId,String recordingId,Selection selection,Metadata metadata,Decision decision,
                long head,long version,String sha,long bytes,long epoch){
            this.operationId=operationId;this.recordingId=recordingId;this.selection=selection;this.metadata=metadata;
            this.decision=decision;headRevision=head;sourceVersion=version;audioSha256=sha;this.bytes=bytes;originLeaseEpoch=epoch;
        }
        public boolean equals(Object o){if(!(o instanceof Commit))return false;Commit c=(Commit)o;
            return operationId.equals(c.operationId)&&recordingId.equals(c.recordingId)&&selection.equals(c.selection)
                &&metadata.equals(c.metadata)&&decision.equals(c.decision)&&headRevision==c.headRevision&&sourceVersion==c.sourceVersion
                &&audioSha256.equals(c.audioSha256)&&bytes==c.bytes&&originLeaseEpoch==c.originLeaseEpoch;}
        public int hashCode(){return operationId.hashCode();}
    }
    public static final class Receipt {
        public final Commit commit; public final String storageRef;
        public Receipt(Commit commit,String storageRef){if(commit==null||storageRef==null||storageRef.isEmpty())throw new Failure("INVALID_LOCAL_RECEIPT");this.commit=commit;this.storageRef=storageRef;}
    }
    /** Local-only storage. Production implementation must provide owner-scoped immutable receipt/CAS. */
    public interface Sink {
        Receipt recover(Selection selected,String operationId) throws Exception;
        Receipt publish(Commit frozen,byte[] plaintext,Guard guard) throws Exception;
        default Receipt sameContent(Commit frozen,Guard guard)throws Exception{return null;}
    }
    public static final class DisconnectedSink implements Sink {
        public Receipt recover(Selection s,String id){return null;}
        public Receipt publish(Commit c,byte[] b,Guard g){throw new Failure("NATIVE_STORAGE_NOT_CONNECTED");}
    }
    public interface FaultHook { void reached(String point); }
    public static final class Version {
        public final long sourceVersion, bytes; public final String audioSha256,operationId,storageRef;
        public Version(long version,String sha,long bytes,String op,String ref){sourceVersion=version;audioSha256=sha;this.bytes=bytes;operationId=op;storageRef=ref;}
    }
    public static final class Row {
        public String recordingId; public Metadata metadata; public Decision decision;
        public Status status=Status.WAITING_STABLE; public long headRevision=1,sourceVersion,seenRound,leaseEpoch;
        public String stableBoot,lastHashBoot,leaseBoot,error; public long stableSince,lastHashAt,leaseUntil;
        public int observations; public Commit pending; public List<Version> versions=new ArrayList<>();
        public Row copy(){Row r=new Row();r.recordingId=recordingId;r.metadata=metadata;r.decision=decision;r.status=status;r.headRevision=headRevision;
            r.sourceVersion=sourceVersion;r.seenRound=seenRound;r.leaseEpoch=leaseEpoch;r.stableBoot=stableBoot;r.stableSince=stableSince;
            r.lastHashBoot=lastHashBoot;r.lastHashAt=lastHashAt;r.leaseBoot=leaseBoot;r.leaseUntil=leaseUntil;r.error=error;
            r.observations=observations;r.pending=pending;r.versions=new ArrayList<>(versions);return r;}
        public Version current(){return versions.isEmpty()?null:versions.get(versions.size()-1);}
    }
}
