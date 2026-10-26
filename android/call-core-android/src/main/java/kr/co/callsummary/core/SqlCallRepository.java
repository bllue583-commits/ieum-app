package kr.co.callsummary.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** All version/lease/receipt writes run the shared SQLite triggers, inside one transaction. */
public final class SqlCallRepository implements CallRepository {
    private final SqlPort db;
    private final String ownerKey;
    private static final Set<String> USER_OPERATIONS=new HashSet<>(Arrays.asList("EDIT","UNLOCK","ACCEPT","TASK_EDIT","CONFIRM"));
    public SqlCallRepository(SqlPort db,String localOwnerKey) { this.db=db;this.ownerKey=localOwnerKey; }
    /** Local profile isolation only, never a successful native login state. */
    public void initializeLocalScope() {
        db.transaction(()->{db.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",ownerKey);return null;});
    }
    /** Invoke only after explicit folder selection; Android collection also checks persisted read permission. */
    public void registerUserSelectedFolder(String grantId,String rootUri,long policyRevision) {
        db.transaction(()->{
            List<Map<String,Object>> existing=db.query("SELECT * FROM folder_grants WHERE grant_id=?",grantId);
            if(!existing.isEmpty()) {
                Map<String,Object> old=existing.get(0);
                if(!ownerKey.equals(old.get("owner_key")) || !rootUri.equals(old.get("root_uri")) || ((Number)old.get("policy_revision")).longValue()!=policyRevision || ((Number)old.get("active")).longValue()!=1)throw new CoreException("FOLDER_POLICY_CHANGED");
            } else db.execute("INSERT INTO folder_grants(grant_id,owner_key,root_uri,policy_revision,active,selected_by_user) VALUES(?,?,?,?,1,1)",grantId,ownerKey,rootUri,policyRevision);
            return null;
        });
    }
    /** IDs must come from an explicit UI assignment. Shared phone groups are never consulted. */
    public void registerExplicitTask(String taskId,String caseId,String personId,String companyId) {
        db.transaction(()->{
            if(!db.query("SELECT task_id FROM tasks WHERE task_id=?",taskId).isEmpty())throw new CoreException("TASK_ID_ALREADY_REGISTERED");
            db.execute("INSERT INTO tasks(task_id,owner_key,case_id,person_id,company_id,status) VALUES(?,?,?,?,?,NULL)",taskId,ownerKey,caseId,personId,companyId);
            db.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",ownerKey);return null;
        });
    }
    public Map<String,Object> receipt(String eventId) {
        List<Map<String,Object>> rows=db.query("SELECT r.* FROM receipts r LEFT JOIN recordings f ON f.recording_id=r.recording_id LEFT JOIN task_history h ON h.event_id=r.event_id LEFT JOIN tasks t ON t.task_id=h.task_id WHERE r.event_id=? AND (f.owner_key=? OR t.owner_key=?)",eventId,ownerKey,ownerKey);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> recording(String id) {
        List<Map<String,Object>> rows=db.query("SELECT * FROM recording_list WHERE recording_id=? AND owner_key=?",id,ownerKey);
        if(rows.isEmpty())throw new CoreException("RECORDING_NOT_FOUND");return rows.get(0);
    }
    public List<Map<String,Object>> listRecordings() {
        return db.query("SELECT * FROM recording_list WHERE owner_key=? ORDER BY original_started_at_ms IS NULL,original_started_at_ms,recording_id",ownerKey);
    }
    public Map<String,Object> observe(Map<String,Object> frozenMetadata,String audioSha256,long nowMs) {
        JsonCanonical.validHash(audioSha256);
        Map<String,Object> p=new LinkedHashMap<>(frozenMetadata);
        if(!ownerKey.equals(p.get("owner_key")))throw new CoreException("LOCAL_SCOPE_DENIED");
        p.put("audio_sha256",audioSha256);p.put("now_ms",nowMs);
        return command("OBSERVE",p);
    }
    public Map<String,Object> claim(String eventId,String recordingId,String processingId,String transcriberVersion,long nowMs) {
        return db.transaction(() -> {
            Map<String,Object> r=receipt(eventId);
            if(r==null)r=recording(recordingId);
            Map<String,Object> p=map("event_id",eventId,"recording_id",recordingId,"source_version",r.get("source_version"),"audio_sha256",r.get("audio_sha256"),"processing_id",processingId,"transcriber_version",transcriberVersion,"now_ms",nowMs,"lease_until_ms",Math.addExact(nowMs,300000L));
            return write("CLAIM",p);
        });
    }
    public AnalysisBinding binding(String recordingId) {
        recording(recordingId);
        List<Map<String,Object>> rows=db.query("SELECT p.*,v.audio_sha256 FROM processing p JOIN recordings r ON r.recording_id=p.recording_id AND r.source_version=p.source_version JOIN source_versions v ON v.recording_id=p.recording_id AND v.source_version=p.source_version WHERE p.recording_id=?",recordingId);
        if(rows.isEmpty() || !"TRANSCRIBING".equals(rows.get(0).get("state")))throw new CoreException("ACTIVE_CLAIM_REQUIRED");
        return new AnalysisBinding(rows.get(0));
    }
    public Map<String,Object> storeWorkerCandidate(String eventId,AnalysisBinding binding,Map<String,Object> sourcePackV1,Map<String,Object> candidateV1,String producerVersion,long nowMs) {
        Map<String,Object> p=WorkerV1Boundary.storageParameters(binding,sourcePackV1,candidateV1,producerVersion);
        p.put("event_id",eventId);p.put("now_ms",nowMs);
        return command("RESULT",p);
    }
    public Map<String,Object> userCommand(String operation,Map<String,Object> parameters) {
        if(!USER_OPERATIONS.contains(operation))throw new CoreException("USER_OPERATION_NOT_ALLOWED");
        return command(operation,new LinkedHashMap<>(parameters));
    }
    public Map<String,Object> markFailed(String eventId,AnalysisBinding binding,String safeCode,long nowMs) {
        return command("FAIL",map("event_id",eventId,"recording_id",binding.text("recording_id"),"source_version",binding.number("source_version"),"processing_id",binding.text("processing_id"),"lease_epoch",binding.number("lease_epoch"),"error_code",safeCode,"now_ms",nowMs));
    }
    public void checkSelectedFolder(String owner,String grantId,String rootUri,long policyRevision) {
        if(!ownerKey.equals(owner))throw new CoreException("LOCAL_SCOPE_DENIED");
        if(db.query("SELECT grant_id FROM folder_grants WHERE grant_id=? AND owner_key=? AND root_uri=? AND policy_revision=? AND active=1 AND selected_by_user=1",grantId,ownerKey,rootUri,policyRevision).isEmpty())throw new CoreException("FOLDER_POLICY_CHANGED");
    }
    public Map<String,Object> sealVerified(String eventId,String recordingId,long version,CanonicalAudioStore.Blob blob,CanonicalAudioStore store,long nowMs) {
        store.verify(blob.sha256,blob.byteCount);
        return command("SEAL",map("event_id",eventId,"recording_id",recordingId,"source_version",version,"audio_sha256",blob.sha256,"storage_ref",blob.storageRef,"byte_count",blob.byteCount,"now_ms",nowMs));
    }
    public Map<String,Object> availability(String eventId,String recordingId,long expectedSourceVersion,String target,boolean available,boolean inventoryComplete,long nowMs) {
        return command("AVAILABILITY",map("event_id",eventId,"recording_id",recordingId,"expected_source_version",expectedSourceVersion,"target",target,"available",available?1:0,"scan_complete",inventoryComplete?1:0,"now_ms",nowMs));
    }
    private Map<String,Object> command(String operation,Map<String,Object> p) {
        return db.transaction(() -> write(operation,p));
    }
    private Map<String,Object> write(String operation,Map<String,Object> p) {
        if("TASK_EDIT".equals(operation)) {
            if(db.query("SELECT task_id FROM tasks WHERE task_id=? AND owner_key=?",p.get("task_id"),ownerKey).isEmpty())throw new CoreException("TASK_NOT_FOUND");
        } else if(!"OBSERVE".equals(operation))recording((String)p.get("recording_id"));
        p.put("request_digest",requestDigest(operation,p));
        StringBuilder columns=new StringBuilder(),placeholders=new StringBuilder();
        Object[] args=new Object[p.size()];int i=0;
        for(Map.Entry<String,Object> entry:p.entrySet()) {
            if(!entry.getKey().matches("[a-z][a-z0-9_]*"))throw new CoreException("INVALID_PARAMETER_NAME");
            if(i>0){columns.append(',');placeholders.append(',');}
            columns.append(entry.getKey());placeholders.append('?');args[i++]=entry.getValue();
        }
        db.execute("INSERT INTO "+operation.toLowerCase(java.util.Locale.ROOT)+"_commands("+columns+") VALUES("+placeholders+")",args);
        Map<String,Object> result=receipt((String)p.get("event_id"));
        if(result==null)throw new CoreException("RECEIPT_MISSING");
        if(Arrays.asList("CLAIM","EDIT","ACCEPT").contains(operation))db.execute("INSERT OR IGNORE INTO request_bindings VALUES(?,?,?)",p.get("event_id"),operation,JsonCanonical.encode(p));
        return result;
    }
    public static String requestDigest(String operation,Map<String,Object> p) {
        Map<String,Object> stable=new LinkedHashMap<>(p);
        for(String key:Arrays.asList("now_ms","lease_until_ms","event_id","request_digest"))stable.remove(key);
        if("OBSERVE".equals(operation))stable.remove("audio_sha256");
        return JsonCanonical.digest(map("operation",operation,"payload",stable));
    }
    public static Map<String,Object> map(Object... entries) {
        if(entries.length%2!=0)throw new IllegalArgumentException();
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;
    }
    public void close(){db.close();}
}
