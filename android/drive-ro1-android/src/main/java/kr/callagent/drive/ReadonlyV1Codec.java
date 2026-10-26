package kr.callagent.drive;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import static kr.callagent.drive.WireV0Codec.*;

/** Frozen read-only v1 deterministic content checks; never attests execution or writer identity. */
public final class ReadonlyV1Codec {
    public static final String KIND="CALL_READ_ONLY_ANALYSIS_RESULT";
    private static final String[] BINDING={"jobId","ownerBinding","sourceId","sourceVersion","audioSha",
            "baseRevision","userRevision","leaseEpoch","resultFileId","requestDigest"};
    public static final class Validated {
        public final String digest,body,rawText; public final long revision; public final int quotes,observations,segments;
        public final byte[] originalBytes; public final Map<String,Object> envelope;
        Validated(byte[] original,Map<String,Object> value,String body,int quotes,int observations,int segments){
            this.originalBytes=original.clone();this.envelope=Collections.unmodifiableMap(value);
            this.digest=(String)value.get("resultDigest");this.revision=(Long)value.get("resultRevision");this.body=body;
            this.rawText=(String)object(value.get("stt")).get("text");this.quotes=quotes;this.observations=observations;this.segments=segments;
        }
    }
    public static byte[] unsignedCanonical(Map<String,Object> value){Map<String,Object> m=new LinkedHashMap<>(value);m.remove("resultDigest");return StrictJson.bytes(m);}
    public static String digest(Map<String,Object> value){
        byte[] prefix="call-drive-readonly-v1:result\n".getBytes(StandardCharsets.UTF_8),body=unsignedCanonical(value);
        byte[] all=new byte[prefix.length+body.length];System.arraycopy(prefix,0,all,0,prefix.length);System.arraycopy(body,0,all,prefix.length,body.length);return DriveRelay.sha256(all);
    }
    public Validated validate(byte[] bytes,byte[] frozenRequest){
        Map<String,Object> request=WireV0Codec.validateRequest(frozenRequest),x=object(StrictJson.parseReadOnlyEnvelope(bytes));
        exact(x,"jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","leaseEpoch","resultFileId","requestDigest","resultRevision","schemaVersion","requestSchemaVersion","kind","state","stt","readOnlyAnalysis","dotReview","canonicalWriteAllowed","applied","nativeBinding","writerVerification","displayMode","resultDigest");
        eq(x.get("schemaVersion"),1L);eq(x.get("requestSchemaVersion"),0L);eq(x.get("kind"),KIND);eq(x.get("state"),"PROPOSAL");
        identifier(x.get("jobId"));identifier(x.get("sourceId"));Map<String,Object> owner=object(x.get("ownerBinding"));exact(owner,"ownerId","driveAccount");identifier(owner.get("ownerId"));account(owner.get("driveAccount"));
        number(x.get("sourceVersion"),1,StrictJson.MAX_INTEGER);hash(x.get("audioSha"));fileId(x.get("resultFileId"));hash(x.get("requestDigest"));
        for(String k:new String[]{"baseRevision","userRevision","leaseEpoch"})number(x.get(k),0,StrictJson.MAX_INTEGER);
        number(x.get("resultRevision"),1,StrictJson.MAX_INTEGER);for(String k:BINDING)eq(x.get(k),request.get(k));
        eq(x.get("canonicalWriteAllowed"),false);eq(x.get("applied"),false);eq(x.get("nativeBinding"),"UNVERIFIED");eq(x.get("writerVerification"),"UNRESOLVED");eq(x.get("displayMode"),"READ_ONLY_PROPOSAL");
        hash(x.get("resultDigest"));eq(x.get("resultDigest"),digest(x));
        Map<String,Object> stt=object(x.get("stt"));exact(stt,"execution","sourceKind","text","textSha","packetRef");
        eq(stt.get("execution"),"EXECUTED");eq(stt.get("sourceKind"),"actual_audio_stt");String raw=text(stt.get("text"),1,131072);hash(stt.get("textSha"));eq(stt.get("textSha"),sha(raw));Map<String,Object> ref=reference(stt.get("packetRef"));
        Map<String,Object> a=object(x.get("readOnlyAnalysis"));exact(a,"execution","rawAnalysisJson","analysisSha","artifactRef","analysisMemberPath","blindInputJson","blindInputSha","blindInputMemberPath","segmentLedgerJson","segmentLedgerSha","segmentLedgerMemberPath","bodyTextSha","textDerivation","segmentDerivation","sourceAlias");
        eq(a.get("execution"),"IMPORTED_UNVERIFIED");reference(a.get("artifactRef"));
        Map<String,Object> analysis=payload(a,"rawAnalysisJson","analysisSha",262144),blind=payload(a,"blindInputJson","blindInputSha",131072),ledger=payload(a,"segmentLedgerJson","segmentLedgerSha",65536);
        for(String key:new String[]{"analysisMemberPath","blindInputMemberPath","segmentLedgerMemberPath"})safeMember(text(a.get(key),1,512));
        Map<String,Object> alias=object(a.get("sourceAlias"));exact(alias,"analysisSourceId","requestSourceId");fileId(alias.get("analysisSourceId"));identifier(alias.get("requestSourceId"));eq(alias.get("analysisSourceId"),request.get("audioFileId"));eq(alias.get("requestSourceId"),request.get("sourceId"));
        eq(blind.get("source_kind"),"actual_audio_stt");eq(blind.get("source_id"),request.get("audioFileId"));String body=text(blind.get("text"),0,131072);hash(a.get("bodyTextSha"));eq(a.get("bodyTextSha"),sha(body));
        member(a.get("textDerivation"),set("IDENTITY","REMOVE_ONE_TERMINAL_LF"));eq(raw,"IDENTITY".equals(a.get("textDerivation"))?body:body+"\n");eq(a.get("segmentDerivation"),"REMOVE_ONE_LEADING_ASCII_SPACE_JOIN_LF");
        List<?> segments=list(blind.get("segments"));if(segments.isEmpty())bad("SEGMENTS");
        Map<Long,Segment> byIndex=new LinkedHashMap<>();StringBuilder joined=new StringBuilder();long last=0;int cursor=0;
        for(Object value:segments){Map<String,Object> s=object(value);long index=integer(s.get("index")),start=integer(s.get("startMs")),end=integer(s.get("endMs"));
            if(byIndex.containsKey(index)||start>end||start<last)bad("SEGMENT_TIME_INDEX");String t=text(s.get("text"),1,131072);if(!t.startsWith(" "))bad("SEGMENT_PREFIX");
            eq(s.get("acceptedForBlindSummary"),true);if(!list(s.get("validationFailures")).isEmpty())bad("SEGMENT_ACCEPTANCE");
            String piece=t.substring(1);if(!byIndex.isEmpty())joined.append('\n');joined.append(piece);
            byIndex.put(index,new Segment(t,cursor,start,end));cursor+=cpLength(piece)+1;last=end;
        }
        eq(joined.toString(),body);Map<String,Object> tv=object(blind.get("timestampValidation"));BigDecimal duration=decimal(tv.get("actualAudioDurationMs"));if(duration.compareTo(BigDecimal.valueOf(last))<0)bad("DURATION");
        eq(tv.get("strictUpperBound"),true);eq(integer(tv.get("acceptedSegments")),(long)segments.size());eq(integer(tv.get("rejectedSegments")),0L);if(!list(tv.get("failClosedFailures")).isEmpty())bad("TIMESTAMP_RECEIPT");eq(tv.get("clampingOrTextRepairPerformed"),false);
        eq(analysis.get("source_kind"),"actual_audio_stt");eq(analysis.get("synthetic_content"),true);eq(analysis.get("is_worker_v1_envelope"),false);
        Map<String,Object> application=object(analysis.get("application"));eq(application.get("candidate_only"),true);eq(application.get("applied"),false);eq(application.get("automatic_summary_allowed"),false);eq(application.get("root_semantic_review_required"),true);eq(integer(application.get("app_writes")),0L);eq(integer(application.get("drive_writes")),0L);
        Map<String,Object> prov=object(analysis.get("provenance"));for(String[] pair:new String[][]{{"library_file_id","libraryFileId"},{"library_version","version"},{"file_id","fileId"},{"packet_bytes","bytes"},{"packet_sha256","sha256"}}){Object expected=ref.get(pair[1]),actual=prov.get(pair[0]);if(expected instanceof Long)eq(integer(actual),expected);else eq(actual,expected);}
        eq(prov.get("body_sha256"),a.get("bodyTextSha"));eq(prov.get("audio_sha256_packet_reported"),request.get("audioSha"));eq(prov.get("body_coordinate_domain"),"blindSummaryInput.text Unicode codepoints [start,end)");
        Map<String,Object> execution=object(analysis.get("execution"));eq(execution.get("summary_model_actual"),null);eq(execution.get("reasoning_effort_actual"),null);
        List<?> observations=list(analysis.get("observations"));Set<String> ids=new HashSet<>();Map<QuoteKey,Map<String,Object>> quotes=new HashMap<>();
        for(Object value:observations){Map<String,Object> o=object(value);String id=text(o.get("claim_id"),1,262144);if(!ids.add(id))bad("CLAIM_ID");List<?> evidence=list(o.get("evidence"));if(evidence.isEmpty())bad("EVIDENCE");
            for(int i=0;i<evidence.size();i++){Map<String,Object> e=object(evidence.get(i));exact(e,"source_id","start","end","quote");eq(e.get("source_id"),alias.get("analysisSourceId"));long start=integer(e.get("start")),end=integer(e.get("end"));eq(slice(body,start,end),text(e.get("quote"),1,262144));quotes.put(new QuoteKey(id,i),e);}
        }
        exact(ledger,"original_packet_sha256","body_sha256","entries");eq(ledger.get("original_packet_sha256"),ref.get("sha256"));eq(ledger.get("body_sha256"),a.get("bodyTextSha"));List<?> entries=list(ledger.get("entries"));if(entries.size()!=quotes.size())bad("LEDGER_COVERAGE");Set<QuoteKey> seen=new HashSet<>();
        for(Object value:entries){Map<String,Object> e=object(value);exact(e,"claim_id","evidence_index","segment_index","segment_startMs","segment_endMs","segment_start","segment_end","source_id","start","end","quote");QuoteKey key=new QuoteKey(text(e.get("claim_id"),1,262144),integer(e.get("evidence_index")));Segment s=byIndex.get(integer(e.get("segment_index")));Map<String,Object> q=quotes.get(key);if(!seen.add(key)||s==null||q==null)bad("LEDGER_KEY");
            for(String k:new String[]{"source_id","quote"})eq(e.get(k),q.get(k));for(String k:new String[]{"start","end"})eq(integer(e.get(k)),integer(q.get(k)));eq(integer(e.get("segment_startMs")),s.startMs);eq(integer(e.get("segment_endMs")),s.endMs);
            long start=integer(e.get("segment_start")),end=integer(e.get("segment_end"));if(start<1)bad("LEDGER_PREFIX");eq(slice(s.text,start,end),e.get("quote"));eq(integer(e.get("start")),(long)s.bodyStart+start-1);eq(integer(e.get("end")),(long)s.bodyStart+end-1);
        }
        Map<String,Object> dot=object(x.get("dotReview"));exact(dot,"state","notes","evidenceRef","scope");member(dot.get("state"),set("UNREVIEWED","NEEDS_CONFIRMATION","REVIEWED"));for(Object note:list(dot.get("notes")))text(note,0,Integer.MAX_VALUE);nullableText(dot.get("evidenceRef"));eq(dot.get("scope"),"READ_ONLY_CONTEXT");
        return new Validated(bytes,x,body,quotes.size(),observations.size(),segments.size());
    }
    public static String sha(String value){return DriveRelay.sha256(value.getBytes(StandardCharsets.UTF_8));}
    static Map<String,Object> object(Object value){return StrictJson.object(value);}
    static List<?> list(Object value){if(!(value instanceof List))bad("ARRAY");return (List<?>)value;}
    static long integer(Object value){if(!(value instanceof BigInteger||value instanceof Long||value instanceof Integer))bad("INTEGER_TOKEN");if(value instanceof BigInteger){BigInteger n=(BigInteger)value;if(n.signum()<0||n.compareTo(BigInteger.valueOf(StrictJson.MAX_INTEGER))>0)bad("INTEGER");return n.longValue();}long v=((Number)value).longValue();if(v<0||v>StrictJson.MAX_INTEGER)bad("INTEGER");return v;}
    static BigDecimal decimal(Object value){if(value instanceof BigDecimal)return (BigDecimal)value;if(value instanceof BigInteger)return new BigDecimal((BigInteger)value);if(value instanceof Long||value instanceof Integer)return BigDecimal.valueOf(((Number)value).longValue());throw new IllegalArgumentException("readonly_NUMBER");}
    private static Map<String,Object> reference(Object value){Map<String,Object> r=object(value);exact(r,"libraryFileId","version","fileId","bytes","sha256");if(!text(r.get("libraryFileId"),1,512).matches("libfile_[A-Za-z0-9]+")||!text(r.get("fileId"),1,512).matches("file_[A-Za-z0-9]+"))bad("REF_ID");number(r.get("version"),0,StrictJson.MAX_INTEGER);number(r.get("bytes"),1,10485760);hash(r.get("sha256"));return r;}
    private static Map<String,Object> payload(Map<String,Object> a,String field,String hashKey,int cap){String raw=text(a.get(field),2,cap);byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);if(bytes.length>cap)bad("CONTENT_SIZE");hash(a.get(hashKey));eq(a.get(hashKey),sha(raw));return StrictJson.readOnlyInner(raw);}
    static void safeMember(String path){if(path.startsWith("/")||path.contains("\\"))bad("MEMBER_PATH");for(String piece:path.split("/",-1))if(piece.isEmpty()||piece.equals(".")||piece.equals(".."))bad("MEMBER_PATH");}
    static int cpLength(String s){return s.codePointCount(0,s.length());}
    static String slice(String s,long start,long end){int length=cpLength(s);if(start<0||start>=end||end>length)bad("QUOTE_RANGE");return s.substring(s.offsetByCodePoints(0,(int)start),s.offsetByCodePoints(0,(int)end));}
    static void bad(String reason){throw new IllegalArgumentException("readonly_"+reason);}
    private static final class Segment{final String text;final int bodyStart;final long startMs,endMs;Segment(String t,int bodyStart,long start,long end){this.text=t;this.bodyStart=bodyStart;this.startMs=start;this.endMs=end;}}
    private static final class QuoteKey{final String id;final long index;QuoteKey(String id,long index){this.id=id;this.index=index;}@Override public boolean equals(Object o){return o instanceof QuoteKey&&id.equals(((QuoteKey)o).id)&&index==((QuoteKey)o).index;}@Override public int hashCode(){return Objects.hash(id,index);}}
}
