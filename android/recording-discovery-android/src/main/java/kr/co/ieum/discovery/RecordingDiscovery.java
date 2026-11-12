package kr.co.ieum.discovery;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.co.ieum.discovery.DiscoveryTypes.*;

/** Metadata discovery is independent of sign-in. There is deliberately no automatic upload API. */
public final class RecordingDiscovery {
    private final Object scanAdmission=new Object();private long cancellationEpoch;
    private final java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();
    private final Bootstrap bootstrap;private final DiscoveryStore store;private final Documents docs;private final Policy policy;private final Sink sink;
    private final Selection selection;private final Clock clock;private final Config config;private final FaultHook fault;
    public RecordingDiscovery(DiscoveryStore store,Documents docs,Policy policy,Sink sink,Selection selection,Clock clock,Config config){this(store,docs,policy,sink,selection,clock,config,p->{});}
    public RecordingDiscovery(DiscoveryStore store,Documents docs,Policy policy,Sink sink,Selection selection,Clock clock,Config config,FaultHook fault){this(store,docs,policy,sink,selection,clock,config,fault,(s,m,d)->null);}
    public RecordingDiscovery(DiscoveryStore store,Documents docs,Policy policy,Sink sink,Selection selection,Clock clock,Config config,FaultHook fault,Bootstrap bootstrap){
        this.bootstrap=bootstrap;this.store=store;this.docs=docs;this.policy=policy;this.sink=sink;this.selection=selection;this.clock=clock;this.config=config;this.fault=fault;
        store.transact(s->{if(s.selection==null)s.selection=selection;else if(!selection.equals(s.selection))throw new Failure("PINNED_FOLDER_MISMATCH");return null;});
    }
    /** One durable metadata page; caller can run bounded pages off the UI thread. */
    public int scanPage(){
        final long epoch;synchronized(scanAdmission){if(stopped.get())return 0;epoch=cancellationEpoch;}
        if(!docs.hasReadGrant(selection)){pauseGrantUnlessCancelled(epoch);return 0;}
        DiscoveryStore.State before=store.snapshot();String cursor=before.scanCursor;long round=before.scanRound+(cursor==null?1:0);Page page;
        try{page=docs.listPage(selection,cursor,config.pageSize);}
        catch(SecurityException e){pauseGrantUnlessCancelled(epoch);return 0;}catch(Exception e){if(!scanActive(epoch))return 0;throw new Failure("METADATA_SCAN_FAILED",e);}
        if(!scanActive(epoch))return 0;
        if(page==null||page.documents.size()>config.pageSize||(page.nextCursor!=null&&(page.nextCursor.equals(cursor)||page.nextCursor.length()>24000)))throw new Failure("INVALID_PROVIDER_PAGE");
        synchronized(scanAdmission){
            if(!scanActive(epoch))return 0;
            if(!docs.hasReadGrant(selection)){pauseGrantUnlessCancelled(epoch);return 0;}
            try{return store.transact(s->{
                requireScanEpoch(epoch);requireSelection(s);
                if(s.scanRound!=before.scanRound||!java.util.Objects.equals(s.scanCursor,cursor))throw new Failure("SCAN_CHECKPOINT_CHANGED");
                List<Decision> decisions=new ArrayList<>();docs.beginDecisions(selection);
                try{for(Metadata m:page.documents){requireScanEpoch(epoch);decisions.add(decide(m));}
                    requireScanEpoch(epoch);docs.acceptPage(selection,page);}finally{docs.endDecisions(selection);}
                requireScanEpoch(epoch);
                Moment now=clock.now();s.grantPaused=false;s.scanRound=round;
                s.scanRoundComplete=(cursor==null?true:s.scanRoundComplete)&&page.inventoryComplete;
                for(int i=0;i<page.documents.size();i++){requireScanEpoch(epoch);observe(s,page.documents.get(i),decisions.get(i),round,now);}
                s.scanCursor=page.nextCursor;
                if(page.nextCursor==null&&s.scanRoundComplete)for(Row r:s.rows.values())if(r.seenRound!=round){
                    if(r.status!=Status.MISSING_ALIAS){r.headRevision++;r.leaseEpoch++;r.error="SOURCE_ALIAS_MISSING";r.status=Status.MISSING_ALIAS;
                        if(r.pending!=null)audit(s,"PENDING_ALIAS_MISSING:"+r.pending.operationId);}}
                fault.reached("BEFORE_SCAN_COMMIT");requireScanEpoch(epoch);
                if(!docs.hasReadGrant(selection))throw new Failure("SCAN_GRANT_CHANGED");
                return page.documents.size();
            });}catch(Failure e){if(e.code.equals("SCAN_CANCELLED"))return 0;if(e.code.equals("SCAN_GRANT_CHANGED")){pauseGrantUnlessCancelled(epoch);return 0;}throw e;}
        }
    }
    /** The queue header keeps only the latest audit notes; it grew without end before (review pipeline #7). */
    public static final int AUDIT_KEEP=100;
    public static void trimAudit(DiscoveryStore.State s){int extra=s.audit.size()-AUDIT_KEEP;if(extra>0)s.audit.subList(0,extra).clear();}
    private static void audit(DiscoveryStore.State s,String note){s.audit.add(note);trimAudit(s);}
    private boolean scanActive(long epoch){synchronized(scanAdmission){return !stopped.get()&&cancellationEpoch==epoch;}}
    private void requireScanEpoch(long epoch){if(!scanActive(epoch))throw new Failure("SCAN_CANCELLED");}
    private void pauseGrantUnlessCancelled(long epoch){synchronized(scanAdmission){if(scanActive(epoch))pauseGrant();}}
    private Decision decide(Metadata m){Decision d=policy.decide(selection,m);if(d==null)throw new Failure("POLICY_DECISION_REQUIRED");return d;}
    private void requireSelection(DiscoveryStore.State s){if(!selection.equals(s.selection))throw new Failure("PINNED_FOLDER_MISMATCH");}
    private void observe(DiscoveryStore.State s,Metadata m,Decision d,long round,Moment now){
        Row r=s.rows.get(m.documentId);boolean isNew=r==null;
        // Existing code13 exclusions also forbid new filename/URI metadata persistence.
        if(d.permission==Permission.EXCLUDED){if(r!=null){r.status=Status.HELD_EXCLUDED;r.leaseEpoch++;r.decision=d;r.seenRound=round;}return;}
        if(isNew){r=new Row();r.recordingId=recordingId(m.documentId);r.metadata=m;r.decision=d;
            Seed seed=bootstrap.find(selection,m,d);if(seed!=null){r.recordingId=seed.recordingId;r.versions=new ArrayList<>(seed.versions);r.sourceVersion=r.current()==null?0:r.current().sourceVersion;}
            s.rows.put(m.documentId,r);}
        boolean changed=!r.metadata.equals(m);boolean policyChanged=!r.decision.equals(d);boolean returning=r.status==Status.MISSING_ALIAS;
        boolean onlyNameChanged=changed&&!policyChanged&&!returning&&r.metadata.documentId.equals(m.documentId)
            &&r.metadata.mimeType.equals(m.mimeType)&&java.util.Objects.equals(r.metadata.bytes,m.bytes)
            &&java.util.Objects.equals(r.metadata.modifiedMs,m.modifiedMs)&&r.metadata.partial==m.partial&&r.metadata.virtualDocument==m.virtualDocument;
        if(changed||policyChanged||returning){if(changed||returning)r.headRevision++;r.leaseEpoch++;
            if(r.pending!=null&&!onlyNameChanged){audit(s,"SUPERSEDED_PENDING:"+r.pending.operationId);r.pending=null;}r.error=null;}
        boolean fresh=isNew||changed||policyChanged||returning;
        boolean newBoot=!now.bootId.equals(r.stableBoot)||now.elapsedMs<r.stableSince;
        // Same name, size and modified time as the stored version: a reboot alone is no reason to read and hash it again.
        boolean settled=!fresh&&r.pending==null&&r.current()!=null&&(r.status==Status.STORED||r.status==Status.UNCHANGED);
        boolean reset=fresh||(newBoot&&!settled);
        r.metadata=m;r.decision=d;r.seenRound=round;
        if(reset){r.stableBoot=now.bootId;r.stableSince=now.elapsedMs;r.observations=1;r.status=Status.WAITING_STABLE;}
        else if(newBoot){r.stableBoot=now.bootId;r.stableSince=now.elapsedMs;r.observations=1;}
        else r.observations=Math.min(2,r.observations+1);
        Status blocked=blocked(m,d);
        if(blocked!=null){if(r.status==Status.READING){r.leaseEpoch++;}r.status=blocked;return;}
        if(settled&&!recheckDue(r,now))return;
        if(r.pending!=null){if(r.status!=Status.READING)r.status=Status.RETRY;return;}
        if(r.status==Status.READING&&!expired(r,now))return;
        if(r.observations<2||now.elapsedMs-r.stableSince<config.quietMs){r.status=Status.WAITING_STABLE;return;}
        boolean due=r.current()==null||recheckDue(r,now);
        if(changed||reset||due||r.status==Status.WAITING_STABLE||r.status==Status.WAITING_CONTAINER||r.status==Status.RETRY||r.status==Status.WAITING_SOURCE||r.status==Status.WAITING_GRANT||r.status==Status.WAITING_LOCAL_STORAGE)
            r.status=Status.READY;
    }
    /** Wall-clock marker in {@link Row#lastHashBoot}: {@link Row#lastHashAt} then holds wall time, so the daily recheck survives reboots. */
    private static final String WALL_CLOCK="wall";
    /** A stored file is read again at most every {@code recheckMs} of wall time. Rows hashed before this rule count as hashed now. */
    private boolean recheckDue(Row r,Moment now){
        if(!WALL_CLOCK.equals(r.lastHashBoot)||now.wallMs<r.lastHashAt){r.lastHashBoot=WALL_CLOCK;r.lastHashAt=now.wallMs;return false;}
        return now.wallMs-r.lastHashAt>=config.recheckMs+recheckSpread(r,config.recheckMs);
    }
    /** v3.8 r18: files hashed on the same day (all of them, the last day of the daily look) would all come due on the same day
     * again and write their looks at once. A long interval (a week or more) gets a fixed share of up to a quarter of it more
     * per file, at most 7 days, from the document id (the same file always waits the same). A short interval stays exact. */
    public static long recheckSpread(Row r,long recheckMs){
        if(recheckMs<7L*86_400_000L||r.metadata==null)return 0;
        long span=Math.min(7L*86_400_000L,recheckMs/4);
        return Math.floorMod((long)r.metadata.documentId.hashCode()*2654435761L,span);
    }
    private static void hashed(Row r,Moment now){r.lastHashBoot=WALL_CLOCK;r.lastHashAt=now.wallMs;}
    private Status blocked(Metadata m,Decision d){
        // No source read, decrypt, audio hash or sink invocation on either contact hold.
        if(d.permission==Permission.EXCLUDED)return Status.HELD_EXCLUDED;
        if(d.permission!=Permission.CONFIRMED_ALLOWED)return Status.HELD_UNCONFIRMED;
        String name=m.displayName.toLowerCase(Locale.ROOT);
        if(m.partial||name.endsWith(".part")||name.endsWith(".tmp")||name.endsWith(".partial"))return Status.WAITING_PARTIAL;
        if(m.virtualDocument)return Status.HELD_UNSUPPORTED;
        if(m.bytes==null||m.modifiedMs==null||m.bytes<=0||m.modifiedMs<=0)return Status.WAITING_METADATA;
        if(m.bytes>config.maxBytes)return Status.HELD_UNSUPPORTED;
        if(!(name.endsWith(".wav")||name.endsWith(".m4a")||name.endsWith(".mp4"))
                ||!(m.mimeType.startsWith("audio/")||m.mimeType.equals("video/mp4")))return Status.HELD_UNSUPPORTED;
        return null;
    }
    private boolean expired(Row r,Moment now){return r.leaseBoot==null||!r.leaseBoot.equals(now.bootId)||now.elapsedMs>=r.leaseUntil;}
    private void pauseGrant(){store.transact(s->{requireSelection(s);s.grantPaused=true;s.scanCursor=null;
        for(Row r:s.rows.values()){if(r.status==Status.READING)r.leaseEpoch++;if(r.status!=Status.HELD_EXCLUDED&&r.status!=Status.HELD_UNCONFIRMED)r.status=Status.WAITING_GRANT;}return null;});}
    private static final class Claim { final Row row;final long epoch;Claim(Row r){row=r;epoch=r.leaseEpoch;} }
    /** At most one eligible local job, with a durable restart/lease epoch. No account is consulted. */
    public String processNext(){
        if(stopped.get())return "DISCOVERY_RUN_STOPPED";
        if(!docs.hasReadGrant(selection)){pauseGrant();return "FOLDER_READ_GRANT_MISSING";}
        Moment now=clock.now();Claim claim=store.transact(s->{requireSelection(s);if(s.grantPaused)return null;
            List<String> ids=new ArrayList<>(s.rows.keySet());java.util.Collections.sort(ids);
            // A file never stored comes first, the most recently modified first (a call that just ended beats the backlog).
            // One that already failed is left to the round robin below so it cannot hold the line.
            String best=null;long bestModified=Long.MIN_VALUE;
            for(String id:ids){Row r=s.rows.get(id);
                if(r.current()!=null||r.error!=null||!eligible(r,now))continue;
                long modified=r.metadata.modifiedMs==null?0:r.metadata.modifiedMs;
                if(best==null||modified>bestModified){best=id;bestModified=modified;}}
            if(best!=null){Row r=s.rows.get(best);return claim(s,r,best,now);}
            int start=0;
            if(s.claimCursor!=null){while(start<ids.size()&&ids.get(start).compareTo(s.claimCursor)<=0)start++;if(start==ids.size())start=0;}
            for(int step=0;step<ids.size();step++){
                String id=ids.get((start+step)%ids.size());Row r=s.rows.get(id);
                if(!eligible(r,now))continue;
                return claim(s,r,id,now);}
            return null;});
        if(claim==null)return "NO_READY_JOB";
        byte[] bytes=null;
        try{
            requireAllowed(claim);
            // Frozen pending receipts are recovered before any stat/open/hash of the source.
            if(claim.row.pending!=null){Receipt recovered=sink.recover(selection,claim.row.pending.operationId);
                if(recovered!=null){finish(claim,recovered);return "RECEIPT_RECOVERED";}}
            Moment stableNow=clock.now();
            if(!stableNow.bootId.equals(claim.row.stableBoot)||claim.row.observations<2
                    ||stableNow.elapsedMs<claim.row.stableSince||stableNow.elapsedMs-claim.row.stableSince<config.quietMs)
                throw new Failure("SOURCE_NOT_STABLE");
            Metadata start=docs.stat(selection,claim.row.metadata.documentId);if(!claim.row.metadata.equals(start))throw new Failure("SOURCE_CHANGED");
            requireAllowed(claim);
            bytes=read(claim);fault.reached("AFTER_SOURCE_READ");requireAllowed(claim);
            if(!claim.row.metadata.equals(docs.stat(selection,claim.row.metadata.documentId)))throw new Failure("SOURCE_CHANGED");
            AudioContainer.validate(bytes);fault.reached("BEFORE_AUDIO_HASH");requireAllowed(claim);String hash=sha(bytes);fault.reached("AFTER_AUDIO_HASH");
            Version current=claim.row.current();
            if(claim.row.pending==null&&current!=null&&current.audioSha256.equals(hash)){
                Commit observation=new Commit(UUID.randomUUID().toString(),claim.row.recordingId,selection,claim.row.metadata,claim.row.decision,
                    claim.row.headRevision,claim.row.sourceVersion,hash,bytes.length,claim.epoch);
                store.transact(s->{Row r=claimed(s,claim);r.pending=observation;return null;});
                Receipt observed=sink.sameContent(observation,()->allowed(claim));
                if(observed!=null){finish(claim,observed);return "UNCHANGED_BYTES";}
                store.transact(s->{Row r=claimed(s,claim);r.pending=null;r.status=Status.UNCHANGED;hashed(r,clock.now());return null;});return "UNCHANGED_BYTES";
            }
            Commit pending=claim.row.pending;
            if(pending!=null){if(!pending.audioSha256.equals(hash)||pending.bytes!=bytes.length)throw new Failure("PENDING_SOURCE_HASH_CHANGED");}
            else{pending=new Commit(UUID.randomUUID().toString(),claim.row.recordingId,selection,claim.row.metadata,claim.row.decision,
                    claim.row.headRevision,claim.row.sourceVersion+1,hash,bytes.length,claim.epoch);Commit frozen=pending;
                store.transact(s->{Row r=claimed(s,claim);r.pending=frozen;return null;});}
            fault.reached("AFTER_PENDING_DURABLE");requireAllowed(claim);
            Receipt receipt=(pending.sourceVersion==claim.row.sourceVersion?sink.sameContent(pending,()->allowed(claim)):sink.publish(pending,bytes,()->allowed(claim)));fault.reached("AFTER_SINK_COMMIT");
            finish(claim,receipt);fault.reached("AFTER_QUEUE_COMMIT");return "STORED_LOCAL";
        }catch(SecurityException e){pauseGrant();return "FOLDER_READ_GRANT_MISSING";}
        catch(Exception e){String code=e instanceof Failure?((Failure)e).code:
                (e instanceof java.io.FileNotFoundException||e instanceof java.nio.file.NoSuchFileException?"SOURCE_UNAVAILABLE":"LOCAL_IO_FAILED");fail(claim,code);return code;}
        finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private boolean eligible(Row r,Moment now){return r.status==Status.READY||r.status==Status.RETRY||(r.status==Status.READING&&expired(r,now));}
    private Claim claim(DiscoveryStore.State s,Row r,String id,Moment now){
        r.leaseEpoch++;r.leaseBoot=now.bootId;r.leaseUntil=Math.addExact(now.elapsedMs,config.leaseMs);r.status=Status.READING;r.error=null;s.claimCursor=id;return new Claim(r.copy());
    }
    /** True while a metadata round is still half way (the next page continues it). */
    public boolean scanOpen(){return store.snapshot().scanCursor!=null;}
    /** True while some file only waits for its second look or the quiet time. */
    public boolean hasStableWait(){for(Row r:store.snapshot().rows.values())if(r.status==Status.WAITING_STABLE)return true;return false;}
    public boolean isStopped(){return stopped.get();}
    private byte[] read(Claim claim)throws Exception{
        long length=claim.row.metadata.bytes;if(length>config.maxBytes)throw new Failure("SOURCE_TOO_LARGE");byte[] bytes=new byte[(int)length];int offset=0;
        // Access was checked by the caller and by open(); between chunks only the stop flag is looked at (no settings, hash or IPC per read).
        // The full check runs again right after the read, before anything is hashed or stored.
        try(InputStream input=docs.open(selection,claim.row.metadata.documentId)){
            if(input==null)throw new Failure("SOURCE_UNAVAILABLE");
            while(offset<bytes.length){if(stopped.get())throw new Failure("DISCOVERY_ACCESS_CHANGED");int n=input.read(bytes,offset,Math.min(65536,bytes.length-offset));
                if(n<0)throw new Failure("SOURCE_LENGTH_CHANGED");if(n==0)throw new Failure("SOURCE_READ_STALLED");offset+=n;}
            if(input.read()!=-1)throw new Failure("SOURCE_LENGTH_CHANGED");return bytes;
        }catch(Exception e){Arrays.fill(bytes,(byte)0);throw e;}
    }
    private boolean allowed(Claim claim){
        try{if(stopped.get()||!docs.hasReadGrant(selection)||!claim.row.decision.equals(decide(claim.row.metadata))||blocked(claim.row.metadata,claim.row.decision)!=null)return false;
            claimed(store.snapshot(),claim);return true;}catch(Exception e){return false;}
    }
    private void requireAllowed(Claim claim){if(!allowed(claim))throw new Failure("DISCOVERY_ACCESS_CHANGED");}
    private Row claimed(DiscoveryStore.State s,Claim claim){requireSelection(s);Row r=s.rows.get(claim.row.metadata.documentId);
        if(r==null||s.grantPaused||r.leaseEpoch!=claim.epoch||r.status!=Status.READING||r.headRevision!=claim.row.headRevision
                ||!r.metadata.equals(claim.row.metadata)||!r.decision.equals(claim.row.decision)||expired(r,clock.now()))throw new Failure("STALE_DISCOVERY_LEASE");return r;}
    private void finish(Claim claim,Receipt receipt){
        requireAllowed(claim);
        store.transact(s->{Row r=claimed(s,claim);if(receipt==null||r.pending==null||!r.pending.equals(receipt.commit))throw new Failure("RECEIPT_BINDING_MISMATCH");
            Version current=r.current();
            if(current!=null&&receipt.commit.sourceVersion==r.sourceVersion&&current.audioSha256.equals(receipt.commit.audioSha256)){
                r.pending=null;r.status=Status.UNCHANGED;hashed(r,clock.now());return null;}
            if(receipt.commit.sourceVersion!=r.sourceVersion+1)throw new Failure("SOURCE_VERSION_CONFLICT");
            if(current!=null&&current.audioSha256.equals(receipt.commit.audioSha256))throw new Failure("REDUNDANT_CONTENT_VERSION");
            r.versions.add(new Version(receipt.commit.sourceVersion,receipt.commit.audioSha256,receipt.commit.bytes,receipt.commit.operationId,receipt.storageRef));
            r.sourceVersion=receipt.commit.sourceVersion;r.pending=null;r.status=Status.STORED;hashed(r,clock.now());return null;});
    }
    private void fail(Claim claim,String code){store.transact(s->{Row r=s.rows.get(claim.row.metadata.documentId);if(r==null||r.leaseEpoch!=claim.epoch||r.headRevision!=claim.row.headRevision)return null;
        // The queue commit may have succeeded even when its acknowledgement was lost.
        if(r.pending==null&&r.sourceVersion>claim.row.sourceVersion&&r.status==Status.STORED){r.error=code;return null;}
        r.error=code;
        if(!docs.hasReadGrant(selection)){r.status=Status.WAITING_GRANT;s.grantPaused=true;}
        else if(!r.decision.equals(decide(r.metadata))){Decision current=decide(r.metadata);r.decision=current;r.leaseEpoch++;r.status=blocked(r.metadata,current);if(r.status==null)r.status=Status.WAITING_STABLE;}
        else if(code.equals("NATIVE_STORAGE_NOT_CONNECTED"))r.status=Status.WAITING_LOCAL_STORAGE;
        else if(code.equals("INCOMPLETE_CONTAINER")||code.equals("CONTAINER_REVIEW_REQUIRED")||code.equals("SOURCE_LENGTH_CHANGED")||code.equals("PENDING_SOURCE_HASH_CHANGED")){
            r.status=Status.WAITING_CONTAINER;r.pending=null;r.stableSince=clock.now().elapsedMs;r.observations=1;}
        else if(code.equals("SOURCE_UNAVAILABLE"))r.status=Status.WAITING_SOURCE;
        else if(code.equals("SOURCE_CHANGED")){r.status=Status.WAITING_STABLE;r.observations=0;}
        else r.status=Status.RETRY;return null;});}
    /** Explicit owner-scoped recovery of an already committed local receipt, without source access. */
    public String recoverLocalReceipt(String documentId){
        Row before=store.snapshot().rows.get(documentId);
        if(before==null)return "NO_PENDING_RECEIPT";
        if(before.pending==null){return before.current()!=null&&localReceipt(before.current().operationId)!=null?"LOCAL_RECEIPT_ALREADY_COMMITTED":"NO_PENDING_RECEIPT";}
        if(!before.decision.equals(decide(before.metadata))||blocked(before.metadata,before.decision)!=null)return "LOCAL_RECEIPT_POLICY_HELD";
        try{Receipt receipt=sink.recover(selection,before.pending.operationId);if(receipt==null)return "RECEIPT_NOT_COMMITTED";
            return store.transact(s->{requireSelection(s);Row r=s.rows.get(documentId);
                if(r==null||r.pending==null||!r.pending.equals(before.pending)||!r.metadata.equals(before.metadata)
                        ||!r.decision.equals(before.decision)||!before.decision.equals(decide(before.metadata)))throw new Failure("RECEIPT_RECOVERY_FENCE_CHANGED");
                if(!r.pending.equals(receipt.commit))throw new Failure("RECEIPT_BINDING_MISMATCH");
                Version current=r.current();boolean same=receipt.commit.sourceVersion==r.sourceVersion;
                if(same){if(current==null||!current.audioSha256.equals(receipt.commit.audioSha256)||current.bytes!=receipt.commit.bytes||!current.storageRef.equals(receipt.storageRef))throw new Failure("RECEIPT_BINDING_MISMATCH");}
                else {if(receipt.commit.sourceVersion!=r.sourceVersion+1||(current!=null&&current.audioSha256.equals(receipt.commit.audioSha256)))throw new Failure("RECEIPT_BINDING_MISMATCH");
                    r.versions.add(new Version(receipt.commit.sourceVersion,receipt.commit.audioSha256,receipt.commit.bytes,receipt.commit.operationId,receipt.storageRef));r.sourceVersion=receipt.commit.sourceVersion;}
                r.pending=null;r.leaseEpoch++;
                if(r.status!=Status.WAITING_GRANT&&r.status!=Status.MISSING_ALIAS)r.status=same?Status.UNCHANGED:Status.STORED;
                hashed(r,clock.now());return "LOCAL_RECEIPT_RECOVERED";
            });
        }catch(Failure e){return e.code;}catch(Exception e){return "LOCAL_RECEIPT_UNAVAILABLE";}
    }
    public List<Row> rows(){return new ArrayList<>(store.snapshot().rows.values());}
    public Receipt localReceipt(String operationId){
        for(Row r:store.snapshot().rows.values()){
            if(!r.decision.equals(decide(r.metadata))||blocked(r.metadata,r.decision)!=null)continue;
            for(Version v:r.versions)if(v.operationId.equals(operationId))try{
                Receipt receipt=sink.recover(selection,operationId);
                if(receipt==null||!receipt.commit.selection.equals(selection)||!receipt.commit.recordingId.equals(r.recordingId)
                        ||!receipt.commit.audioSha256.equals(v.audioSha256)||receipt.commit.sourceVersion!=v.sourceVersion
                        ||receipt.commit.bytes!=v.bytes||!receipt.storageRef.equals(v.storageRef))throw new Failure("RECEIPT_BINDING_MISMATCH");
                return receipt;
            }catch(Failure e){throw e;}catch(Exception e){throw new Failure("LOCAL_RECEIPT_UNAVAILABLE",e);}
        }return null;
    }
    public void cancelRun(){synchronized(scanAdmission){stopped.set(true);cancellationEpoch++;}}
    public Selection selectedFolder(){return selection;}
    private String recordingId(String documentId){try{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try(java.io.DataOutputStream out=new java.io.DataOutputStream(bytes)){out.writeUTF(selection.ownerId);out.writeUTF(selection.treeUri);out.writeUTF(documentId);}
        return "rec_"+sha(bytes.toByteArray()).substring(0,40);
    }catch(Exception e){throw new Failure("RECORDING_ID_UNAVAILABLE",e);}}
    private static String sha(byte[] bytes){try{byte[] h=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder(64);for(byte b:h)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new Failure("HASH_UNAVAILABLE",e);}}
}
