package kr.callagent.drive;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static kr.callagent.drive.WireV0Codec.*;

/** New API-worker proposal profile. Frozen v0/RO1 stay exact; JSON is never writer/dot attestation. */
public final class AutomationV2Codec {
    public static final String KIND="CALL_AUTOMATION_API_PROPOSAL";
    private static final String[] BINDING={"jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest"};
    public static final class Validated {
        public final String digest,rawText,analysisJson,businessContextSha;public final long revision;
        public final Map<String,Object> envelope;
        Validated(Map<String,Object> x){envelope=Collections.unmodifiableMap(x);digest=(String)x.get("resultDigest");revision=(Long)x.get("resultRevision");rawText=(String)StrictJson.object(x.get("stt")).get("text");analysisJson=StrictJson.stringify(StrictJson.object(x.get("analysis")));businessContextSha=(String)x.get("businessContextSha");}
    }
    public static String digest(Map<String,Object> x){Map<String,Object> unsigned=new LinkedHashMap<>(x);unsigned.remove("resultDigest");return DriveRelay.sha256(("ieum-automation-v2:result\n"+StrictJson.stringify(unsigned)).getBytes(StandardCharsets.UTF_8));}
    public Validated validate(byte[] bytes,byte[] frozenRequest){
        Map<String,Object> r=WireV0Codec.validateRequest(frozenRequest),x=StrictJson.object(StrictJson.parseReadOnlyEnvelope(bytes));
        exact(x,"jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest","businessContextSha","schemaVersion","requestSchemaVersion","kind","state","resultRevision","stt","analysis","dotExecution","executionEvidence","deliveryChannel","driveResultWritten","writerVerification","nativeBinding","displayMode","canonicalWriteAllowed","applied","resultDigest");
        eq(x.get("schemaVersion"),2L);eq(x.get("requestSchemaVersion"),0L);eq(x.get("kind"),KIND);eq(x.get("state"),"PROPOSAL");
        eq(r.get("creatorMode"),"ANDROID_APP");Map<String,Object> context=StrictJson.object(StrictJson.parse(((String)r.get("contextJson")).getBytes(StandardCharsets.UTF_8)));eq(context.get("synthetic"),true);eq(context.get("syntheticCreatorConfirmed"),true);
        for(String k:BINDING)eq(x.get(k),r.get(k));number(x.get("resultRevision"),1,StrictJson.MAX_INTEGER);hash(x.get("businessContextSha"));hash(x.get("resultDigest"));eq(x.get("resultDigest"),digest(x));
        eq(x.get("dotExecution"),"NOT_INVOKED");eq(x.get("executionEvidence"),"PROVIDER_RESPONSES_RECORDED_NOT_INDEPENDENTLY_ATTESTED");eq(x.get("deliveryChannel"),"AUTHENTICATED_WORKER_ENDPOINT");eq(x.get("driveResultWritten"),false);
        eq(x.get("writerVerification"),"UNRESOLVED");eq(x.get("nativeBinding"),"UNVERIFIED");eq(x.get("displayMode"),"READ_ONLY_PROPOSAL");eq(x.get("canonicalWriteAllowed"),false);eq(x.get("applied"),false);
        Map<String,Object> stt=StrictJson.object(x.get("stt"));exact(stt,"provider","model","requestId","text","textSha");eq(stt.get("provider"),"OPENAI_API");text(stt.get("model"),1,128);text(stt.get("requestId"),1,256);String raw=text(stt.get("text"),1,131072);hash(stt.get("textSha"));eq(stt.get("textSha"),ReadonlyV1Codec.sha(raw));
        Map<String,Object> a=StrictJson.object(x.get("analysis"));exact(a,"provider","model","requestId","summary","cumulativeSummary","tasks");eq(a.get("provider"),"OPENAI_API");text(a.get("model"),1,128);text(a.get("requestId"),1,256);text(a.get("summary"),1,3000);text(a.get("cumulativeSummary"),1,5000);
        if(!(a.get("tasks") instanceof List)||((List<?>)a.get("tasks")).size()>30)throw new IllegalArgumentException("automation_TASK_CAPACITY");Set<String> seen=new HashSet<>();int cp=raw.codePointCount(0,raw.length());
        for(Object value:(List<?>)a.get("tasks")){
            Map<String,Object> t=StrictJson.object(value);exact(t,"taskId","title","assignee","due","status","quote","start","end");if(t.get("taskId")!=null)identifier(t.get("taskId"));String title=text(t.get("title"),1,200),assignee=text(t.get("assignee"),1,100),quote=text(t.get("quote"),1,1000);
            number(t.get("start"),0,cp);number(t.get("end"),1,cp);long start=(Long)t.get("start"),end=(Long)t.get("end");if(end<=start)throw new IllegalArgumentException("automation_QUOTE_RANGE");eq(raw.substring(raw.offsetByCodePoints(0,(int)start),raw.offsetByCodePoints(0,(int)end)),quote);
            member(t.get("status"),set("open","in_progress","waiting","needs_confirmation"));if(t.get("due")!=null){String due=text(t.get("due"),10,10);try{eq(LocalDate.parse(due).toString(),due);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("automation_INVALID_DUE");}}
            String fp=StrictJson.stringify(Arrays.asList(java.text.Normalizer.normalize(title,java.text.Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT),java.text.Normalizer.normalize(assignee,java.text.Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT),t.get("due")));if(!seen.add(fp))throw new IllegalArgumentException("automation_DUPLICATE_TASK");
        }
        return new Validated(x);
    }
}
