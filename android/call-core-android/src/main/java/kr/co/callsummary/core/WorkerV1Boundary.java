package kr.co.callsummary.core;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic v1 adapter only. No actual-call scope, STT, entailment, or auto application. */
public final class WorkerV1Boundary {
    private WorkerV1Boundary() {}
    private static final Set<String> CATEGORIES=set("context","date","document","entity","participant","quantity","request","role","status");
    private static final Set<String> STATUSES=set("done","in_progress","needs_confirmation","open","waiting");
    @SuppressWarnings("unchecked")
    public static Map<String,Object> storageParameters(AnalysisBinding b,Map<String,Object> pack,Map<String,Object> candidate,String producerVersion) {
        exact(pack,"schema_version","job_id","base_revision","scope","offset_unit","automatic_summary_allowed","root_semantic_review_required","sources","source_pack_sha256");
        exact(candidate,"schema_version","job_id","source_pack_sha256","base_revision","summary","claims");
        if(number(pack.get("schema_version"))!=1 || number(candidate.get("schema_version"))!=1)fail("INVALID_V1_VERSION");
        if(!"synthetic_contract_test".equals(pack.get("scope")))fail("INPUT_ADAPTER_NOT_SUPPORTED");
        if(!"unicode_code_points".equals(pack.get("offset_unit")) || !Boolean.FALSE.equals(pack.get("automatic_summary_allowed")) || !Boolean.TRUE.equals(pack.get("root_semantic_review_required")))fail("SOURCE_PACK_POLICY_MISMATCH");
        identifier(pack.get("job_id"),false);
        if(!b.text("processing_id").equals(pack.get("job_id")) || !pack.get("job_id").equals(candidate.get("job_id")) || number(pack.get("base_revision"))!=b.number("base_board_revision") || number(candidate.get("base_revision"))!=b.number("base_board_revision"))fail("WORKER_BINDING_MISMATCH");
        Map<String,Object> unsigned=new LinkedHashMap<>(pack);unsigned.remove("source_pack_sha256");
        String packHash=JsonCanonical.digest(unsigned);
        if(!packHash.equals(pack.get("source_pack_sha256")) || !packHash.equals(candidate.get("source_pack_sha256")))fail("SOURCE_PACK_HASH_MISMATCH");
        List<?> sources=list(pack.get("sources"));
        if(sources.size()!=1)fail("SINGLE_RECORDING_ADAPTER_REQUIRED");
        Map<String,Object> source=object(sources.get(0));
        exact(source,"source_id","kind","raw_text","text_sha256","recorded_at","timestamp_basis","case_id","case_link_confirmed","speaker_assignment_verified","uncertain_ranges","provenance");
        if(!"call_transcript".equals(source.get("kind")))fail("SOURCE_KIND_NOT_SUPPORTED");
        String sourceId=b.text("recording_id")+"_v"+b.number("source_version");
        if(!sourceId.equals(source.get("source_id")))fail("SOURCE_BINDING_MISMATCH");
        identifier(source.get("source_id"),false);
        String raw=text(source.get("raw_text"),20000);
        if(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>100000)fail("SOURCE_PACK_TOO_LARGE");
        String textHash=JsonCanonical.textHash(raw);
        if(!textHash.equals(source.get("text_sha256")))fail("RAW_TEXT_HASH_MISMATCH");
        if(!equal(b.values.get("base_case_id"),source.get("case_id")))fail("CASE_BINDING_MISMATCH");
        identifier(source.get("case_id"),true);
        if(!(source.get("case_link_confirmed") instanceof Boolean) || !(source.get("speaker_assignment_verified") instanceof Boolean))fail("INVALID_VERIFICATION_FLAGS");
        if(Boolean.TRUE.equals(source.get("case_link_confirmed")) && source.get("case_id")==null)fail("CONFIRMED_CASE_ID_REQUIRED");
        Object originalMs=b.values.get("base_original_started_at_ms");
        if(source.get("recorded_at")==null) {
            if(originalMs!=null || !"unknown".equals(source.get("timestamp_basis")))fail("TIMESTAMP_BINDING_MISMATCH");
        } else {
            long stamp;
            try {stamp=OffsetDateTime.parse((String)source.get("recorded_at")).toInstant().toEpochMilli();}
            catch(Exception e){throw new CoreException("INVALID_TIMESTAMP",e);}
            if(originalMs==null || stamp!=number(originalMs) || !set("synthetic_fixture_clock","verified_source_metadata").contains(source.get("timestamp_basis")))fail("TIMESTAMP_BINDING_MISMATCH");
        }
        List<?> ranges=list(source.get("uncertain_ranges"));
        if(ranges.size()>100)fail("INVALID_UNCERTAINTY_RANGES");
        for(Object value:ranges) {
            Map<String,Object> range=object(value);exact(range,"start","end","reason");
            span(raw,range.get("start"),range.get("end"));text(range.get("reason"),100);
        }
        Map<String,Object> provenance=object(source.get("provenance"));
        exact(provenance,"source_kind","language","run_id","audio_sha256","model_name","model_sha256","engine_sha256","execution_observed","reference_used_in_inference","quality_state","run_started_at_utc","run_finished_at_utc","runner_sha256","source_commit","dataset","dataset_revision","license");
        if(!"synthetic_contract_fixture".equals(provenance.get("source_kind")) || !"ko".equals(provenance.get("language")) || !Boolean.FALSE.equals(provenance.get("execution_observed")) || !Boolean.FALSE.equals(provenance.get("reference_used_in_inference")))fail("SYNTHETIC_PROVENANCE_REQUIRED");
        identifier(provenance.get("run_id"),false);
        if(!b.text("audio_sha256").equals(provenance.get("audio_sha256")))fail("AUDIO_HASH_BINDING_MISMATCH");
        for(String key:Arrays.asList("model_sha256","engine_sha256"))if(provenance.get(key)!=null)JsonCanonical.validHash(provenance.get(key));
        if(provenance.get("model_name")!=null)text(provenance.get("model_name"),80);
        for(String key:Arrays.asList("run_started_at_utc","run_finished_at_utc","runner_sha256","source_commit","dataset","dataset_revision","license"))if(provenance.get(key)!=null)fail("SYNTHETIC_HAS_NO_OBSERVED_STT");
        if(!set("raw_review_required","quality_blocked").contains(provenance.get("quality_state")))fail("INVALID_QUALITY_STATE");
        String summary=text(candidate.get("summary"),3000);
        List<?> claims=list(candidate.get("claims"));if(claims.size()>50)fail("TOO_MANY_CLAIMS");
        Set<String> ids=new HashSet<>(),replacements=new HashSet<>();
        List<Object> reviews=new ArrayList<>(),diffReviews=new ArrayList<>();
        for(Object value:claims) {
            Map<String,Object> claim=object(value);
            exact(claim,"claim_id","category","subject_id","value","certainty","evidence","status","assignee","due","due_expression","replaces");
            identifier(claim.get("claim_id"),false);
            if(!ids.add((String)claim.get("claim_id")))fail("DUPLICATE_CLAIM_ID");
            if(!CATEGORIES.contains(claim.get("category")))fail("INVALID_CATEGORY");
            identifier(claim.get("subject_id"),true);identifier(claim.get("replaces"),true);
            if(claim.get("subject_id")!=null && !Arrays.asList(b.text("base_person_id"),b.text("base_company_id"),b.text("base_task_id")).contains(claim.get("subject_id")))fail("SUBJECT_NOT_IN_CONFIRMED_CONTEXT");
            if(claim.get("replaces")!=null && !replacements.add((String)claim.get("replaces")))fail("DUPLICATE_REPLACEMENT");
            text(claim.get("value"),500);
            if(!set("explicit","uncertain").contains(claim.get("certainty")))fail("INVALID_CERTAINTY");
            if(claim.get("status")!=null && !STATUSES.contains(claim.get("status")))fail("INVALID_STATUS");
            if(claim.get("assignee")!=null)text(claim.get("assignee"),120);
            if(claim.get("due_expression")!=null)text(claim.get("due_expression"),100);
            if(claim.get("due")!=null) {
                try {LocalDate.parse((String)claim.get("due"));}catch(Exception e){throw new CoreException("INVALID_DUE",e);}
                if(claim.get("due_expression")==null)fail("DUE_EXPRESSION_REQUIRED");
                // Relative/date resolution stays a review; no native timezone inference is performed.
            }
            List<?> evidence=list(claim.get("evidence"));if(evidence.isEmpty() || evidence.size()>10)fail("MISSING_EVIDENCE");
            boolean dueQuoted=claim.get("due_expression")==null;
            for(Object evidenceValue:evidence) {
                Map<String,Object> e=object(evidenceValue);exact(e,"source_id","start","end","quote");
                if(!sourceId.equals(e.get("source_id")))fail("FOREIGN_EVIDENCE");
                String quote=text(e.get("quote"),1000);
                if(!span(raw,e.get("start"),e.get("end")).equals(quote))fail("QUOTE_DOES_NOT_MATCH_RAW");
                if(claim.get("due_expression")!=null && quote.contains((String)claim.get("due_expression")))dueQuoted=true;
            }
            if(!dueQuoted)fail("DATE_EXPRESSION_NOT_IN_EVIDENCE");
            List<Object> reasons=new ArrayList<>();reasons.add("semantic_review_required");
            if(!Boolean.TRUE.equals(source.get("speaker_assignment_verified")) && (claim.get("assignee")!=null || "participant".equals(claim.get("category"))))reasons.add("speaker_assignment_unverified");
            if("done".equals(claim.get("status")))reasons.add("manual_completion_required");
            if(claim.get("replaces")!=null)reasons.add("board_snapshot_adapter_required");
            if(set("entity","participant","role").contains(claim.get("category")))reasons.add("relationship_link_review_required");
            if(claim.get("due")!=null || claim.get("due_expression")!=null)reasons.add("deadline_needs_confirmation");
            if("quality_blocked".equals(provenance.get("quality_state")))reasons.add("stt_quality_blocked");
            reviews.add(SqlCallRepository.map("claim_id",claim.get("claim_id"),"reasons",reasons));
            diffReviews.add(SqlCallRepository.map("claim_id",claim.get("claim_id"),"reason","candidate_requires_review"));
        }
        Map<String,Object> diff=SqlCallRepository.map("base_revision",b.number("base_board_revision"),"changes",new ArrayList<>(),"review",diffReviews,"candidate_only",true,"applied",false,"automatic_summary_allowed",false);
        Map<String,Object> p=new LinkedHashMap<>();
        for(String key:Arrays.asList("recording_id","source_version","audio_sha256","processing_id","lease_epoch","base_user_revision","base_lock_revision","base_task_revision","base_board_revision","base_summary_version","transcriber_version"))p.put(key,b.values.get(key));
        String transcriptId=b.text("cached_transcript_id");
        if(transcriptId==null)transcriptId="tx_"+JsonCanonical.digest(Arrays.asList(b.text("audio_sha256"),b.text("transcriber_version"),textHash));
        p.putAll(SqlCallRepository.map("transcript_id",transcriptId,"text_sha256",textHash,"raw_text",raw,"quality_state",provenance.get("quality_state"),"summary_sha256",JsonCanonical.textHash(summary),"summary_text",summary,"producer_version",text(producerVersion,120),"source_pack_sha256",packHash,"source_pack_json",JsonCanonical.encode(pack),"candidate_v1_json",JsonCanonical.encode(candidate),"change_diff_json",JsonCanonical.encode(diff),"review_reasons_json",JsonCanonical.encode(reviews)));
        return p;
    }
    private static Set<String> set(String...items){return new HashSet<>(Arrays.asList(items));}
    private static boolean equal(Object a,Object b){return java.util.Objects.equals(a,b);}
    private static void fail(String code){throw new CoreException(code);}
    private static void exact(Map<String,Object> m,String...keys){if(!m.keySet().equals(set(keys)))fail("INVALID_V1_SHAPE");}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v){if(!(v instanceof Map))fail("OBJECT_REQUIRED");return (Map<String,Object>)v;}
    private static List<?> list(Object v){if(!(v instanceof List))fail("ARRAY_REQUIRED");return (List<?>)v;}
    private static long number(Object v){if(!(v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte))fail("INTEGER_REQUIRED");return ((Number)v).longValue();}
    private static void identifier(Object v,boolean nullable){if(nullable && v==null)return;if(!(v instanceof String) || !((String)v).matches("[A-Za-z0-9_-]{1,80}"))fail("INVALID_IDENTIFIER");}
    private static String text(Object v,int max){if(!(v instanceof String))fail("TEXT_REQUIRED");String t=(String)v;int n=t.codePointCount(0,t.length());if(n<1 || n>max || t.indexOf('\u0000')>=0)fail("INVALID_TEXT");return t;}
    private static String span(String raw,Object begin,Object end){long a=number(begin),b=number(end);int length=raw.codePointCount(0,raw.length());if(a<0 || a>=b || b>length)fail("INVALID_CODE_POINT_SPAN");return raw.substring(raw.offsetByCodePoints(0,(int)a),raw.offsetByCodePoints(0,(int)b));}
}
