package kr.callagent.drive;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static kr.callagent.drive.WireV0Codec.*;

/** Exact schema used by the parent's real scheduled text run. Independent proof namespace only. */
public final class ScheduledTextProofCodec {
    public static final String SCHEMA="scheduler-proof-v1";
    public static final class Proposal {
        public final String jobId,summary,sourceSha,resultSha;public final Instant producedAt;
        public final List<Map<String,Object>> tasks;
        public final boolean canonicalWriteAllowed=false,applied=false,sttExecuted=false,androidUploadVerified=false,kakaoSent=false;
        public final boolean schedulerTriggerIndependentlyVerified=false,modelIdentityVerified=false;
        Proposal(String job,String summary,String sourceSha,String resultSha,Instant at,List<Map<String,Object>> tasks){this.jobId=job;this.summary=summary;this.sourceSha=sourceSha;this.resultSha=resultSha;this.producedAt=at;this.tasks=Collections.unmodifiableList(tasks);}
    }
    public Proposal validate(byte[] input,byte[] output,String expectedJob,String inputFileId,String expectedSourceSha,String expectedResultSha,Instant notBefore,Instant now){
        identifier(expectedJob);fileId(inputFileId);hash(expectedSourceSha);hash(expectedResultSha);eq(DriveRelay.sha256(input),expectedSourceSha);eq(DriveRelay.sha256(output),expectedResultSha);
        Map<String,Object> source=StrictJson.object(StrictJson.parse(input)),result=StrictJson.object(StrictJson.parseReadOnlyEnvelope(output));
        exact(source,"schema_version","job_id","synthetic","source_id","transcript");eq(source.get("schema_version"),SCHEMA);eq(source.get("job_id"),expectedJob);eq(source.get("synthetic"),true);identifier(source.get("source_id"));
        if(!(source.get("transcript") instanceof List)||((List<?>)source.get("transcript")).isEmpty()||((List<?>)source.get("transcript")).size()>32)throw new IllegalArgumentException("scheduled_TRANSCRIPT");
        List<String> utterances=new ArrayList<>();Set<String> speakers=new HashSet<>();
        for(Object v:(List<?>)source.get("transcript")){Map<String,Object> t=StrictJson.object(v);exact(t,"speaker","text");speakers.add(text(t.get("speaker"),1,80));utterances.add(text(t.get("text"),1,2000));}
        exact(result,"schema_version","job_id","synthetic","source_file_id","produced_at_utc","summary","tasks");eq(result.get("schema_version"),SCHEMA);eq(result.get("job_id"),expectedJob);eq(result.get("synthetic"),true);eq(result.get("source_file_id"),inputFileId);
        Instant produced;
        try{OffsetDateTime at=OffsetDateTime.parse(text(result.get("produced_at_utc"),1,64));eq(at.getOffset(),ZoneOffset.UTC);produced=at.toInstant();}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("scheduled_PRODUCED_AT");}
        if(produced.isBefore(notBefore)||produced.isAfter(now))throw new IllegalArgumentException("scheduled_TIME_BOUNDING");
        String summary=text(result.get("summary"),1,3000);if(!(result.get("tasks") instanceof List)||((List<?>)result.get("tasks")).size()>30)throw new IllegalArgumentException("scheduled_TASKS");
        Set<String> seen=new HashSet<>();List<Map<String,Object>> tasks=new ArrayList<>();
        for(Object v:(List<?>)result.get("tasks")){
            Map<String,Object> t=StrictJson.object(v);exact(t,"title","owner","status","due","timing_text","evidence");String title=text(t.get("title"),1,200),owner=text(t.get("owner"),1,80),timing=text(t.get("timing_text"),1,200);
            member(t.get("status"),set("open","in_progress","waiting","needs_confirmation"));eq(t.get("due"),null);if(!speakers.contains(owner)&&!"미확인".equals(owner)&&!"확인 필요".equals(owner))throw new IllegalArgumentException("scheduled_OWNER_NOT_IN_SOURCE");
            Map<String,Object> e=StrictJson.object(t.get("evidence"));exact(e,"source_id","quote");eq(e.get("source_id"),source.get("source_id"));String quote=text(e.get("quote"),1,2000);
            if(!utterances.stream().anyMatch(s->s.contains(quote))||!quote.contains(timing))throw new IllegalArgumentException("scheduled_QUOTE_OR_TIMING");
            if(!seen.add(StrictJson.stringify(Arrays.asList(title,owner))))throw new IllegalArgumentException("scheduled_DUPLICATE_TASK");
            tasks.add(Collections.unmodifiableMap(t));
        }
        return new Proposal(expectedJob,summary,expectedSourceSha,expectedResultSha,produced,tasks);
    }
}
