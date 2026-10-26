package kr.co.callsummary.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Pre-exclusion and selected-tree checks happen before opening bytes. */
public final class FileCollector {
    private static final Set<String> KEYS=new HashSet<>(Arrays.asList("event_id","recording_id","owner_key","grant_id","policy_revision","document_id","source_seq","display_name","mime_type","byte_count","modified_at_ms","original_started_at_ms","phone_group_ref","person_id","company_id","task_id"));
    private static final Set<String> MIME=new HashSet<>(Arrays.asList("audio/wav","audio/x-wav","audio/mpeg","audio/mp4","audio/aac","audio/3gpp","audio/ogg","audio/flac","application/ogg"));
    public interface ExclusionFilter { boolean excludes(Observation observation); }
    public static final class Observation {
        public final Map<String,Object> metadata;
        public final String selectedRootUri;
        public final boolean childVerified,identityVerified;
        public Observation(Map<String,Object> frozenMetadata,String rootUri,boolean child,boolean identity) {
            if(!KEYS.equals(frozenMetadata.keySet()))throw new CoreException("INVALID_OBSERVATION_SHAPE");
            for(Object v:frozenMetadata.values())if(v!=null && !(v instanceof String) && !(v instanceof Number))throw new CoreException("INVALID_OBSERVATION_VALUE");
            metadata=Collections.unmodifiableMap(new LinkedHashMap<>(frozenMetadata));
            selectedRootUri=rootUri;childVerified=child;identityVerified=identity;
        }
    }
    private final SqlCallRepository repository;
    private final CanonicalAudioStore audio;
    private final ExclusionFilter exclusions;
    public FileCollector(SqlCallRepository repository,CanonicalAudioStore audio,ExclusionFilter preReadFilter) {
        if(preReadFilter==null)throw new CoreException("PRE_READ_EXCLUSION_POLICY_REQUIRED");
        this.repository=repository;this.audio=audio;exclusions=preReadFilter;
    }
    public Map<String,Object> collect(Observation observation,CanonicalAudioStore.Opener opener,long nowMs) {
        Map<String,Object> p=observation.metadata;
        repository.checkSelectedFolder((String)p.get("owner_key"),(String)p.get("grant_id"),observation.selectedRootUri,((Number)p.get("policy_revision")).longValue());
        if(!observation.childVerified)throw new CoreException("OUTSIDE_SELECTED_FOLDER");
        if(!observation.identityVerified)throw new CoreException("IDENTITY_REVIEW_REQUIRED");
        if(exclusions.excludes(observation))throw new CoreException("EXCLUDED_BEFORE_READ");
        long bytes=((Number)p.get("byte_count")).longValue();
        if(bytes<1 || bytes>25000000 || !MIME.contains(p.get("mime_type")))throw new CoreException("UNSUPPORTED_SOURCE");
        Map<String,Object> receipt=repository.receipt((String)p.get("event_id"));
        if(receipt!=null) {
            if(!"OBSERVE".equals(receipt.get("operation")) || !SqlCallRepository.requestDigest("OBSERVE",p).equals(receipt.get("request_digest")))throw new CoreException("IDEMPOTENCY_CONFLICT");
            return receipt;
        }
        CanonicalAudioStore.Blob blob=audio.stage(opener,bytes);
        Map<String,Object> observed=repository.observe(p,blob.sha256,nowMs);
        repository.sealVerified("seal_"+p.get("event_id"),(String)p.get("recording_id"),((Number)observed.get("source_version")).longValue(),blob,audio,nowMs);
        return observed;
        // Crash after file rename or OBSERVE: recover by verifying the content
        // file and issuing SEAL explicitly. Never report cross-resource atomicity.
    }
}
