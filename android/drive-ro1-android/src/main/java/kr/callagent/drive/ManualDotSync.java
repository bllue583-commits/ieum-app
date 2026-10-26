package kr.callagent.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User-requested, bounded sync only. No dot HTTP API, OAuth, raw audio or scheduler. */
public final class ManualDotSync {
    public static final class Scope {
        public final String ownerId, account, permissionId, sessionId;
        public final long epoch; public final boolean active;
        public Scope(String ownerId,String account,String permissionId,String sessionId,long epoch,boolean active) {
            this.ownerId=ownerId;this.account=account;this.permissionId=permissionId;this.sessionId=sessionId;this.epoch=epoch;this.active=active;
        }
        public boolean same(Scope b) { return b!=null && active && b.active && ownerId.equals(b.ownerId)
            && account.equals(b.account) && permissionId.equals(b.permissionId) && sessionId.equals(b.sessionId) && epoch==b.epoch; }
        public String key(){return ownerId+"/"+sessionId+"/"+epoch;}
    }
    public static final class Job {
        public final String id,phase,remoteState,requestDigest,registrationJson,registrationSha,unavailableReason;
        public Job(String id,String phase,String remoteState,String requestDigest,String registrationJson,String registrationSha) {
            this.id=id;this.phase=phase;this.remoteState=remoteState;this.requestDigest=requestDigest;
            this.registrationJson=registrationJson;this.registrationSha=registrationSha;this.unavailableReason=null;
        }
        private Job(String id,String phase,String remoteState){this.id=id;this.phase=phase;this.remoteState=remoteState;
            this.requestDigest=null;this.registrationJson=null;this.registrationSha=null;this.unavailableReason="REGISTRATION_NOT_CURRENT";}
        public static Job unavailable(String id,String phase,String remoteState){return new Job(id,phase,remoteState);}
        private Job(String id,String phase,String remoteState,String reason){this.id=id;this.phase=phase;this.remoteState=remoteState;
            this.requestDigest=null;this.registrationJson=null;this.registrationSha=null;this.unavailableReason=reason;}
        public static Job unavailable(String id,String phase,String remoteState,String reason){return new Job(id,phase,remoteState,reason);}
    }
    public static final class Record {
        public String digest,status="NEW",handoff="NONE",registrationSha;
        public Record copy(){Record r=new Record();r.digest=digest;r.status=status;r.handoff=handoff;r.registrationSha=registrationSha;return r;}
    }
    public interface Port {
        Scope currentScope();
        List<Job> currentJobs(Set<String> approvedSyntheticJobIds);
        String pollReadOnly(String jobId);
        String retryFailed(String jobId);
    }
    /** Android implementation MUST transact in the existing protected owner-scoped DB and session fence. */
    public interface Store {
        interface Action<T>{T run(Tx tx);}
        interface Tx{Record get(String job);void put(String job,Record record);String cursor();void cursor(String job);}
        <T>T transact(Scope scope,Action<T> action);
    }
    public enum Entry { NONE, FOREGROUND_OPERATOR_HANDOFF }
    public interface Handoff {
        Entry supportedEntry();
        /** Metadata-only descriptor. Returning means received, never model execution or API authorization. */
        void offer(Scope scope,Job job);
    }
    public static final class Result {
        public final String jobId,state; public final boolean providerExecutionProved=false,canonicalWriteAllowed=false;
        Result(String job,String state){this.jobId=job;this.state=state;}
    }
    private final Port port;private final Store store;private final Handoff handoff;
    public ManualDotSync(Port port,Store store,Handoff handoff){this.port=port;this.store=store;this.handoff=handoff;}
    private void fence(Scope scope){if(!scope.same(port.currentScope()))throw new IllegalStateException("OWNER_SESSION_CHANGED");}
    private Record record(Scope scope,Job job){return store.transact(scope,tx->{
        fence(scope);Record r=tx.get(job.id);
        if(r!=null && !job.requestDigest.equals(r.digest))throw new IllegalStateException("IMMUTABLE_REQUEST_CHANGED");
        if(r==null){r=new Record();r.digest=job.requestDigest;r.registrationSha=job.registrationSha;tx.put(job.id,r);}
        return r.copy();});}
    private void status(Scope scope,Job job,String value,String handoffState){store.transact(scope,tx->{
        fence(scope);Record r=tx.get(job.id);if(r==null || !job.requestDigest.equals(r.digest))throw new IllegalStateException("IMMUTABLE_REQUEST_CHANGED");
        r.status=value;if(handoffState!=null)r.handoff=handoffState;tx.put(job.id,r);return null;});}
    public List<Result> sync(Set<String> explicitlyApprovedSyntheticJobs,int budget){
        if(budget<1||budget>16)throw new IllegalArgumentException("SYNC_BUDGET_REQUIRED");
        Scope scope=port.currentScope();fence(scope);
        if(scope.ownerId.isEmpty()||scope.permissionId.isEmpty()||scope.sessionId.isEmpty())throw new IllegalStateException("VERIFIED_NATIVE_SCOPE_REQUIRED");
        Set<String> approved=new HashSet<>(explicitlyApprovedSyntheticJobs);
        List<Job> jobs=new ArrayList<>(port.currentJobs(Collections.unmodifiableSet(approved)));fence(scope);
        jobs.removeIf(j->!approved.contains(j.id));jobs.sort(Comparator.comparing(j->j.id));
        String cursor=store.transact(scope,tx->tx.cursor());
        List<Job> order=new ArrayList<>();for(Job j:jobs)if(j.id.compareTo(cursor)>0)order.add(j);
        for(Job j:jobs)if(j.id.compareTo(cursor)<=0)order.add(j);
        List<Result> out=new ArrayList<>();
        for(Job job:order.subList(0,Math.min(budget,order.size()))){
            fence(scope);
            if(job.unavailableReason!=null){
                store.transact(scope,tx->{fence(scope);tx.cursor(job.id);return null;});out.add(new Result(job.id,"DOT_PROFILE_ACCOUNT_UNSUPPORTED".equals(job.unavailableReason)?job.unavailableReason:"LOCAL_BINDING_HELD"));continue;
            }
            if(!job.requestDigest.matches("[0-9a-f]{64}") || !job.registrationSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("REGISTRATION_PIN_REQUIRED");
            Record existing=record(scope,job);String result;
            try {
                if(job.phase.equals("CANCELLED")||job.phase.equals("REVOKED")||job.phase.equals("STALE")||job.phase.equals("REJECTED"))result="TERMINAL_HISTORY_PRESERVED";
                else if(!job.phase.equals("SENT")&&!job.phase.equals("CANDIDATE"))result="REQUEST_NOT_COMMITTED";
                else if(job.remoteState.equals("ERROR")||job.remoteState.equals("CANCELLED"))result="EXPLICIT_NEW_JOB_RETRY_REQUIRED";
                else {
                    String polled=port.pollReadOnly(job.id);fence(scope);
                    if(polled.equals("ERROR")||polled.equals("REMOTE_ERROR")||polled.equals("REMOTE_CANCELLED"))result="EXPLICIT_NEW_JOB_RETRY_REQUIRED";
                    else if(polled.equals("STORED")||polled.equals("CANDIDATE_STORED")||polled.equals("WRITER_UNVERIFIED"))result="READ_ONLY_RESULT_RECEIVED_CONFIRMATION_REQUIRED";
                    else if(polled.equals("DUPLICATE"))result="RESULT_HELD_HISTORY_PRESERVED";
                    else if(polled.equals("HISTORY_ONLY")||polled.equals("QUARANTINED")||polled.equals("RESULT_REVISION_CONFLICT"))result="RESULT_HELD_HISTORY_PRESERVED";
                    else if(polled.equals("PENDING")) {
                        if(handoff.supportedEntry()!=Entry.FOREGROUND_OPERATOR_HANDOFF)result="AWAITING_SUPPORTED_DOT_ENTRY";
                        else if(existing.handoff.equals("UNCERTAIN"))result="HANDOFF_UNCERTAIN_RECONCILE_REQUIRED";
                        else if(existing.handoff.equals("OFFERED"))result="AWAITING_DOT_OPERATOR";
                        else {
                            // Durable intent before callback; response loss cannot cause an automatic duplicate offer.
                            status(scope,job,"HANDOFF_UNCERTAIN_RECONCILE_REQUIRED","UNCERTAIN");fence(scope);
                            handoff.offer(scope,job);fence(scope);
                            status(scope,job,"AWAITING_DOT_OPERATOR","OFFERED");result="AWAITING_DOT_OPERATOR";
                        }
                    } else result="TRANSPORT_OR_SOURCE_HELD";
                }
            } catch(Exception changed){fence(scope);result="LOCAL_SYNC_RECOVERY_REQUIRED";}
            status(scope,job,result,null);
            store.transact(scope,tx->{fence(scope);tx.cursor(job.id);return null;});out.add(new Result(job.id,result));
        }
        return out;
    }
    /** Explicit user action delegates code16's existing cancel-old/new-job/retryOf implementation. */
    public String retry(String failedJobId,Set<String> approved){
        Scope scope=port.currentScope();fence(scope);
        if(!approved.contains(failedJobId))throw new IllegalArgumentException("UNAPPROVED_JOB");
        Job failed=null;for(Job job:port.currentJobs(approved))if(job.id.equals(failedJobId))failed=job;
        fence(scope);if(failed==null||!(failed.remoteState.equals("ERROR")||failed.remoteState.equals("CANCELLED")))throw new IllegalStateException("EXPLICIT_FAILURE_REQUIRED");
        String next=port.retryFailed(failedJobId);fence(scope);return next;
    }
    /** Only after the operator confirms the handoff outcome; no automatic retry from a timeout. */
    public void reconcile(Job job,boolean accepted){Scope scope=port.currentScope();fence(scope);
        Record r=record(scope,job);if(!r.handoff.equals("UNCERTAIN"))throw new IllegalStateException("NO_UNCERTAIN_HANDOFF");
        status(scope,job,accepted?"AWAITING_DOT_OPERATOR":"HANDOFF_NOT_ACCEPTED_USER_RETRY_READY",accepted?"OFFERED":"NONE");}
}
