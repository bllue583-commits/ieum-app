package kr.callagent.drive;

import java.util.*;
import static kr.callagent.drive.WireV0Codec.*;

/** Locally pinned producer artifact. Structure/hash is not independent STT execution attestation. */
public final class PreparedWhisperScheduledTextInput {
    public final String audioSha256="3ee1442ed8ac61b95ac1ff2985d2857126042b99141c05f7947c25ae4f7c56b5";
    private final byte[] input;
    public final String jobId,evidenceSha,upstreamExecutionReported;
    public final boolean canonicalWriteAllowed=false,executionIndependentlyVerified=false,speakerAssignmentVerified=false;
    public PreparedWhisperScheduledTextInput(byte[] input,byte[] evidence,String expectedEvidenceSha){
        hash(expectedEvidenceSha);eq(DriveRelay.sha256(evidence),expectedEvidenceSha);
        Map<String,Object> r=StrictJson.object(StrictJson.parse(input)),e=StrictJson.object(StrictJson.parse(evidence));
        exact(r,"schema_version","job_id","synthetic","source_id","transcript");eq(r.get("schema_version"),ScheduledTextProofCodec.SCHEMA);eq(r.get("synthetic"),true);eq(r.get("source_id"),"synthetic_call001");
        String job=text(r.get("job_id"),1,64);if(!UUID.fromString(job).toString().equals(job))throw new IllegalArgumentException("local_stt_JOB_KEY");
        eq(e.get("schema_version"),"ieum.local-whisper-request-evidence.v1");eq(e.get("job_id"),job);eq(e.get("synthetic"),true);
        eq(e.get("audio_sha256"),"3ee1442ed8ac61b95ac1ff2985d2857126042b99141c05f7947c25ae4f7c56b5");eq(e.get("audio_bytes"),2050244L);
        eq(e.get("engine_sha256"),"69eb69f304a83a86946887ae6afc4c011211027beb24e807764d533f3e949371");eq(e.get("model_sha256"),"1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b");
        eq(e.get("request_sha256"),DriveRelay.sha256(input));for(String key:new String[]{"request_raw_segment_text_modified","raw_artifacts_modified","speaker_assignment_verified","semantic_validity_verified","dot_executed","scheduler_created","cloud_uploaded","canonical_write_allowed","applied"})eq(e.get(key),false);
        eq(e.get("transcription_quality"),"RAW_REVIEW_REQUIRED");
        Map<String,Object> execution=StrictJson.object(e.get("execution"));member(execution.get("state"),set("EXECUTED_IN_THIS_PROCESS","IMPORTED_PREVIOUS_ACTUAL_EXECUTION_NOT_CURRENT"));
        if("EXECUTED_IN_THIS_PROCESS".equals(execution.get("state"))){eq(execution.get("return_code"),0L);eq(execution.get("language"),"ko");eq(execution.get("initial_prompt_used"),false);eq(execution.get("reference_text_used"),false);eq(execution.get("gpu_used"),false);}
        else{eq(execution.get("current_process_model_invocations"),0L);eq(execution.get("return_code_reported"),0L);}
        Map<String,Object> duration=StrictJson.object(e.get("audio_duration_ms"));eq(duration.get("numerator"),256275L);eq(duration.get("denominator"),4L);
        List<?> transcript=ReadonlyV1Codec.list(r.get("transcript")),segments=ReadonlyV1Codec.list(e.get("segments"));if(transcript.isEmpty()||transcript.size()>32||segments.size()!=transcript.size())throw new IllegalArgumentException("local_stt_SEGMENTS");
        long last=0;for(int i=0;i<transcript.size();i++){
            Map<String,Object> t=StrictJson.object(transcript.get(i)),s=StrictJson.object(segments.get(i));exact(t,"speaker","text");eq(t.get("speaker"),"미확인");String raw=text(t.get("text"),1,2000);eq(s.get("index"),(long)i);eq(s.get("text_sha256"),ReadonlyV1Codec.sha(raw));
            number(s.get("start_ms"),last,64068);number(s.get("end_ms"),1,64068);long start=(Long)s.get("start_ms"),end=(Long)s.get("end_ms");if(end<=start)throw new IllegalArgumentException("local_stt_TIME");last=end;
        }
        this.input=input.clone();this.jobId=job;this.evidenceSha=expectedEvidenceSha;this.upstreamExecutionReported=(String)execution.get("state");
    }
    public byte[] forJob(String expectedJob){eq(jobId,expectedJob);return input.clone();}
}
