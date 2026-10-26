package kr.callagent.drive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen shared v0 text handoff; schemaVersion=0 is independent of worker schema_version=1. */
public final class WireV0Codec implements WireCodec {
    /** Syntax only; the caller must bind this value to the SDK-confirmed Drive owner. */
    public static void account(Object value) {
        if (!(value instanceof String) || ((String)value).length() > 254
                || !((String)value).matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new IllegalArgumentException("invalid_drive_account");
    }
    private static final Set<String> EXECUTIONS = set("NOT_RUN", "FIXTURE", "IMPORTED_UNVERIFIED", "EXECUTED");
    private static final String[] BINDING = {"schemaVersion", "jobId", "ownerBinding", "sourceId", "sourceVersion",
            "audioSha", "baseRevision", "userRevision", "leaseEpoch", "resultFileId", "requestDigest"};
    private static final String[] REQUEST_KEYS = {"schemaVersion", "jobId", "ownerBinding", "sourceId", "sourceVersion",
            "audioSha", "baseRevision", "userRevision", "leaseEpoch", "resultFileId", "kind", "creatorMode",
            "audioBytes", "audioFileId", "requestFileId", "lockRevision", "taskRevision", "archiveRevision",
            "contextJson", "contextSha", "requestDigest"};
    private static final String[] RESULT_KEYS = {"schemaVersion", "jobId", "ownerBinding", "sourceId", "sourceVersion",
            "audioSha", "baseRevision", "userRevision", "leaseEpoch", "resultFileId", "kind", "requestDigest",
            "resultRevision", "state", "stt", "high", "dotReview", "error", "canonicalWriteAllowed", "resultDigest"};

    @Override public Prepared prepare(LocalJobStore.Source s, String jobId, String folderId, String audioId,
                                     String requestId, String resultId) {
        Map<String, Object> request = map("schemaVersion", 0L, "kind", "CALL_ANALYSIS_REQUEST", "jobId", jobId,
                "ownerBinding", map("ownerId", s.ownerBinding, "driveAccount", s.driveAccount), "creatorMode", "ANDROID_APP",
                "sourceId", s.sourceKey, "sourceVersion", s.sourceVersion, "audioSha", s.audioSha,
                "audioBytes", s.audioBytes, "audioFileId", audioId, "requestFileId", requestId, "resultFileId", resultId,
                "baseRevision", s.baseRevision, "userRevision", s.userRevision, "lockRevision", s.lockRevision,
                "taskRevision", s.taskRevision, "archiveRevision", s.archiveRevision, "leaseEpoch", s.leaseEpoch,
                "contextJson", s.contextJson, "contextSha", DriveRelay.sha256(s.contextJson.getBytes(StandardCharsets.UTF_8)));
        request.put("requestDigest", digest("request", request));
        validateRequest(StrictJson.bytes(request));
        Map<String, Object> placeholder = placeholder(request);
        return new Prepared((String) request.get("requestDigest"), StrictJson.bytes(request), StrictJson.bytes(placeholder));
    }

    public static Map<String, Object> validateRequest(byte[] bytes) {
        Map<String, Object> m = StrictJson.object(StrictJson.parse(bytes));
        exact(m, REQUEST_KEYS); common(m);
        eq(m.get("kind"), "CALL_ANALYSIS_REQUEST");
        member(m.get("creatorMode"), set("CONNECTOR_PILOT", "ANDROID_APP"));
        number(m.get("audioBytes"), 1, 10_485_760);
        fileId(m.get("audioFileId")); fileId(m.get("requestFileId"));
        number(m.get("lockRevision"), 0, StrictJson.MAX_INTEGER);
        number(m.get("taskRevision"), 0, StrictJson.MAX_INTEGER);
        number(m.get("archiveRevision"), 0, StrictJson.MAX_INTEGER);
        String context = text(m.get("contextJson"), 2, 65_536);
        StrictJson.context(context); hash(m.get("contextSha"));
        eq(m.get("contextSha"), DriveRelay.sha256(context.getBytes(StandardCharsets.UTF_8)));
        eq(m.get("requestDigest"), digest("request", m));
        return m;
    }

    @Override public Result validateResult(byte[] bytes, LocalJobStore.Job job) {
        Map<String, Object> request = validateRequest(job.requestBytes);
        eq(request.get("requestDigest"), job.requestDigest); eq(request.get("resultFileId"), job.resultFileId);
        Map<String, Object> m = StrictJson.object(StrictJson.parse(bytes));
        exact(m, RESULT_KEYS); common(m); eq(m.get("kind"), "CALL_ANALYSIS_RESULT");
        for (String key : BINDING) eq(m.get(key), request.get(key));
        hash(m.get("resultDigest")); eq(m.get("resultDigest"), digest("result", m));
        eq(m.get("canonicalWriteAllowed"), Boolean.FALSE);
        String state = (String) member(m.get("state"), set("PENDING", "PROPOSAL", "ERROR", "CANCELLED"));
        long revision = number(m.get("resultRevision"), "PENDING".equals(state) ? 0 : 1, StrictJson.MAX_INTEGER);
        Map<String, Object> stt = StrictJson.object(m.get("stt"));
        exact(stt, "execution", "text", "textSha", "evidenceRef");
        member(stt.get("execution"), EXECUTIONS); nullableText(stt.get("text")); nullableText(stt.get("evidenceRef"));
        if (stt.get("textSha") != null) hash(stt.get("textSha"));
        Map<String, Object> high = StrictJson.object(m.get("high"));
        exact(high, "execution", "packetJson", "candidateJson", "evidenceRef");
        member(high.get("execution"), EXECUTIONS);
        for (String key : new String[]{"packetJson", "candidateJson", "evidenceRef"}) nullableText(high.get(key));
        Map<String, Object> dot = StrictJson.object(m.get("dotReview"));
        exact(dot, "state", "notes", "evidenceRef");
        member(dot.get("state"), set("UNREVIEWED", "NEEDS_CONFIRMATION", "REVIEWED")); nullableText(dot.get("evidenceRef"));
        if (!(dot.get("notes") instanceof List)) fail();
        for (Object note : (List<?>) dot.get("notes")) if (!(note instanceof String)) fail();
        if (m.get("error") != null) {
            Map<String, Object> error = StrictJson.object(m.get("error")); exact(error, "code", "message");
            text(error.get("code"), 1, 80); text(error.get("message"), 0, 1000);
        }
        if ("NOT_RUN".equals(stt.get("execution"))) nulls(stt, "text", "textSha", "evidenceRef");
        if ("NOT_RUN".equals(high.get("execution"))) nulls(high, "packetJson", "candidateJson", "evidenceRef");
        if ("PENDING".equals(state)) {
            if (revision != 0) fail();
            eq(stt.get("execution"), "NOT_RUN"); eq(high.get("execution"), "NOT_RUN");
            eq(dot.get("state"), "UNREVIEWED");
            if (!((List<?>) dot.get("notes")).isEmpty() || dot.get("evidenceRef") != null || m.get("error") != null) fail();
        }
        if ("ERROR".equals(state) && m.get("error") == null) fail();
        if ("PROPOSAL".equals(state)) {
            boolean rawPresent = stt.get("text") instanceof String && !((String) stt.get("text")).isEmpty();
            boolean candidatePresent = high.get("candidateJson") instanceof String
                    && ((String) high.get("candidateJson")).codePointCount(0, ((String) high.get("candidateJson")).length()) >= 2;
            if (!rawPresent && !candidatePresent) fail();
        }
        boolean quarantine = false;
        for (Map<String, Object> stage : Arrays.asList(stt, high)) {
            if (!"NOT_RUN".equals(stage.get("execution"))
                    && (!(stage.get("evidenceRef") instanceof String) || ((String) stage.get("evidenceRef")).isEmpty())) quarantine = true;
        }
        if (stt.get("text") != null) {
            String raw = (String) stt.get("text");
            if (stt.get("textSha") == null) quarantine = true;
            else eq(stt.get("textSha"), DriveRelay.sha256(raw.getBytes(StandardCharsets.UTF_8)));
        } else if (!"NOT_RUN".equals(stt.get("execution"))) quarantine = true;
        if (stt.get("text") == null && stt.get("textSha") != null) quarantine = true;
        // A path or EXECUTED label is a claim, not observed execution evidence.
        if (set("EXECUTED", "IMPORTED_UNVERIFIED").contains(stt.get("execution"))) quarantine = true;
        if (set("EXECUTED", "IMPORTED_UNVERIFIED").contains(high.get("execution"))) quarantine = true;
        if (!"NOT_RUN".equals(high.get("execution"))) {
            if (!(high.get("packetJson") instanceof String) || !(high.get("candidateJson") instanceof String)) quarantine = true;
            else {
                try { CandidateInspector.verify((String) high.get("packetJson"), (String) high.get("candidateJson"), request, stt.get("text")); }
                catch (IllegalArgumentException invalidCandidate) { quarantine = true; }
            }
        }
        if ("REVIEWED".equals(dot.get("state")) && dot.get("evidenceRef") == null) quarantine = true;
        return new Result(!"PENDING".equals(state), StrictJson.bytes(m), (String) m.get("resultDigest"), state, revision, quarantine);
    }

    public static Map<String, Object> placeholder(Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : BINDING) result.put(key, request.get(key));
        result.putAll(map("kind", "CALL_ANALYSIS_RESULT", "resultRevision", 0L, "state", "PENDING",
                "stt", map("execution", "NOT_RUN", "text", null, "textSha", null, "evidenceRef", null),
                "high", map("execution", "NOT_RUN", "packetJson", null, "candidateJson", null, "evidenceRef", null),
                "dotReview", map("state", "UNREVIEWED", "notes", Collections.emptyList(), "evidenceRef", null),
                "error", null, "canonicalWriteAllowed", false));
        result.put("resultDigest", digest("result", result)); return result;
    }

    public static byte[] unsignedCanonical(String kind, Map<String, Object> envelope) {
        if (!"request".equals(kind) && !"result".equals(kind)) throw new IllegalArgumentException("digest_kind");
        Map<String, Object> copy = new LinkedHashMap<>(envelope); copy.remove(kind + "Digest");
        return StrictJson.bytes(copy);
    }
    public static String digest(String kind, Map<String, Object> envelope) {
        byte[] prefix = ("call-drive-v0:" + kind + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] canonical = unsignedCanonical(kind, envelope);
        byte[] bytes = new byte[prefix.length + canonical.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length); System.arraycopy(canonical, 0, bytes, prefix.length, canonical.length);
        return DriveRelay.sha256(bytes);
    }
    /** Candidate structure/evidence check only; does not attest model execution or writer. */
    public static boolean candidateStructureValid(byte[] resultBytes, byte[] requestBytes) {
        try {
            Map<String,Object> result=StrictJson.object(StrictJson.parse(resultBytes));
            Map<String,Object> high=StrictJson.object(result.get("high"));
            if (!(high.get("packetJson") instanceof String) || !(high.get("candidateJson") instanceof String)) return false;
            CandidateInspector.verify((String)high.get("packetJson"),(String)high.get("candidateJson"),validateRequest(requestBytes),StrictJson.object(result.get("stt")).get("text"));
            return true;
        } catch(IllegalArgumentException invalid) { return false; }
    }
    private static void common(Map<String, Object> m) {
        eq(m.get("schemaVersion"), 0L); identifier(m.get("jobId")); identifier(m.get("sourceId"));
        Map<String, Object> owner = StrictJson.object(m.get("ownerBinding")); exact(owner, "ownerId", "driveAccount");
        identifier(owner.get("ownerId")); account(owner.get("driveAccount"));
        number(m.get("sourceVersion"), 1, StrictJson.MAX_INTEGER); hash(m.get("audioSha"));
        for (String key : new String[]{"baseRevision", "userRevision", "leaseEpoch"}) number(m.get(key), 0, StrictJson.MAX_INTEGER);
        fileId(m.get("resultFileId")); hash(m.get("requestDigest"));
    }
    static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]); return m;
    }
    static void exact(Map<String, Object> m, String... fields) { if (!m.keySet().equals(set(fields))) fail(); }
    static void eq(Object actual, Object expected) { if (!java.util.Objects.equals(actual, expected)) fail(); }
    static Object member(Object value, Set<String> allowed) { if (!allowed.contains(value)) fail(); return value; }
    static long number(Object value, long min, long max) {
        if (!(value instanceof Long || value instanceof Integer)) fail();
        long n = ((Number) value).longValue(); if (n < min || n > max) fail(); return n;
    }
    static String text(Object value, int min, int max) {
        if (!(value instanceof String)) fail(); String s = (String) value;
        int n = s.codePointCount(0, s.length()); if (n < min || n > max) fail(); return s;
    }
    static void nullableText(Object value) { if (value != null && !(value instanceof String)) fail(); }
    static void identifier(Object value) { if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_-]{1,80}")) fail(); }
    static void fileId(Object value) { if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_-]{1,256}")) fail(); }
    static void hash(Object value) { if (!(value instanceof String) || !((String) value).matches("[0-9a-f]{64}")) fail(); }
    static void nulls(Map<String, Object> m, String... keys) { for (String key : keys) if (m.get(key) != null) fail(); }
    static void fail() { throw new IllegalArgumentException("wire_v0_validation"); }

    /** Structural/quote inspection only. It does not impersonate the upstream native adapter. */
    private static final class CandidateInspector {
        static void verify(String packetJson, String candidateJson, Map<String, Object> request, Object sttText) {
            Map<String, Object> packet = StrictJson.object(StrictJson.parse(packetJson.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> candidate = StrictJson.object(StrictJson.parse(candidateJson.getBytes(StandardCharsets.UTF_8)));
            exact(packet, "schema_version", "job_id", "base_revision", "scope", "offset_unit", "automatic_summary_allowed", "root_semantic_review_required", "sources", "source_pack_sha256");
            eq(packet.get("schema_version"), 1L); eq(packet.get("scope"), "synthetic_contract_test");
            exact(candidate, "schema_version", "job_id", "source_pack_sha256", "base_revision", "summary", "claims");
            eq(candidate.get("schema_version"), 1L); eq(candidate.get("job_id"), request.get("jobId"));
            eq(candidate.get("base_revision"), request.get("baseRevision"));
            text(candidate.get("summary"), 1, 3000); hash(candidate.get("source_pack_sha256"));
            if (!packet.containsKey("source_pack_sha256")) fail();
            Map<String, Object> unsigned = new LinkedHashMap<>(packet); unsigned.remove("source_pack_sha256");
            String sourcePackSha = DriveRelay.sha256(StrictJson.bytes(unsigned));
            eq(packet.get("source_pack_sha256"), sourcePackSha); eq(candidate.get("source_pack_sha256"), sourcePackSha);
            eq(packet.get("job_id"), request.get("jobId")); eq(packet.get("base_revision"), request.get("baseRevision"));
            eq(packet.get("offset_unit"), "unicode_code_points");
            eq(packet.get("automatic_summary_allowed"), false); eq(packet.get("root_semantic_review_required"), true);
            if (!(packet.get("sources") instanceof List) || ((List<?>) packet.get("sources")).size() != 1) fail();
            Map<String, Object> source = StrictJson.object(((List<?>) packet.get("sources")).get(0));
            exact(source, "source_id", "kind", "raw_text", "text_sha256", "recorded_at", "timestamp_basis", "case_id", "case_link_confirmed", "speaker_assignment_verified", "uncertain_ranges", "provenance");
            eq(source.get("kind"), "call_transcript"); eq(source.get("recorded_at"), null);
            eq(source.get("timestamp_basis"), "unknown"); eq(source.get("case_id"), null);
            eq(source.get("case_link_confirmed"), false);
            if (!(source.get("speaker_assignment_verified") instanceof Boolean) || !(source.get("uncertain_ranges") instanceof List)) fail();
            Map<String, Object> provenance = StrictJson.object(source.get("provenance"));
            exact(provenance, "source_kind", "language", "run_id", "audio_sha256", "model_name", "model_sha256", "engine_sha256", "execution_observed", "reference_used_in_inference", "quality_state", "run_started_at_utc", "run_finished_at_utc", "runner_sha256", "source_commit", "dataset", "dataset_revision", "license");
            eq(provenance.get("source_kind"), "synthetic_contract_fixture"); eq(provenance.get("language"), "ko");
            eq(provenance.get("execution_observed"), false); eq(provenance.get("reference_used_in_inference"), false);
            eq(provenance.get("audio_sha256"), request.get("audioSha")); identifier(provenance.get("run_id"));
            member(provenance.get("quality_state"), set("raw_review_required", "quality_blocked"));
            nulls(provenance, "run_started_at_utc", "run_finished_at_utc", "runner_sha256", "source_commit", "dataset", "dataset_revision", "license");
            if (provenance.get("model_name") != null) text(provenance.get("model_name"), 1, 80);
            for (String key : new String[]{"model_sha256", "engine_sha256"}) if (provenance.get(key) != null) hash(provenance.get(key));
            eq(source.get("source_id"), request.get("sourceId"));
            String raw = text(source.get("raw_text"), 1, 20_000);
            eq(source.get("text_sha256"), DriveRelay.sha256(raw.getBytes(StandardCharsets.UTF_8)));
            if (sttText == null) fail(); eq(sttText, raw);
            List<?> ranges = (List<?>) source.get("uncertain_ranges"); if (ranges.size() > 100) fail();
            for (Object value : ranges) {
                Map<String, Object> range = StrictJson.object(value); exact(range, "start", "end", "reason");
                int length = raw.codePointCount(0, raw.length());
                long start = number(range.get("start"), 0, length), end = number(range.get("end"), 1, length);
                if (start >= end) fail(); text(range.get("reason"), 1, 100);
            }
            if (!(candidate.get("claims") instanceof List)) fail();
            List<?> claims = (List<?>) candidate.get("claims"); if (claims.size() > 50) fail();
            Set<String> ids = new HashSet<>(), replacements = new HashSet<>();
            for (Object value : claims) {
                Map<String, Object> claim = StrictJson.object(value);
                exact(claim, "assignee", "category", "certainty", "claim_id", "due", "due_expression", "evidence", "replaces", "status", "subject_id", "value");
                identifier(claim.get("claim_id")); if (!ids.add((String) claim.get("claim_id"))) fail();
                member(claim.get("category"), set("context", "date", "document", "entity", "participant", "quantity", "request", "role", "status"));
                member(claim.get("certainty"), set("explicit", "uncertain")); text(claim.get("value"), 1, 500);
                for (String key : new String[]{"subject_id", "replaces"}) if (claim.get(key) != null) identifier(claim.get(key));
                // Confirmed native entity/snapshot mapping is unconfigured. Preserve such data in quarantine.
                if (claim.get("subject_id") != null || claim.get("replaces") != null) fail();
                if (claim.get("replaces") != null && !replacements.add((String) claim.get("replaces"))) fail();
                if (claim.get("status") != null) member(claim.get("status"), set("done", "in_progress", "needs_confirmation", "open", "waiting"));
                if (claim.get("assignee") != null) text(claim.get("assignee"), 1, 120);
                if (claim.get("due_expression") != null) text(claim.get("due_expression"), 1, 100);
                if (claim.get("due") != null) {
                    try { java.time.LocalDate.parse(text(claim.get("due"), 1, 100)); }
                    catch (java.time.DateTimeException invalidDate) { fail(); }
                    if (claim.get("due_expression") == null) fail();
                }
                if (!(claim.get("evidence") instanceof List)) fail();
                List<?> evidence = (List<?>) claim.get("evidence"); if (evidence.isEmpty() || evidence.size() > 10) fail();
                boolean dueQuoted = claim.get("due_expression") == null;
                for (Object e : evidence) {
                    Map<String, Object> quote = StrictJson.object(e); exact(quote, "source_id", "start", "end", "quote");
                    eq(quote.get("source_id"), request.get("sourceId"));
                    int length = raw.codePointCount(0, raw.length());
                    int start = (int) number(quote.get("start"), 0, length);
                    int end = (int) number(quote.get("end"), 1, length); if (start >= end) fail();
                    String exactQuote = raw.substring(raw.offsetByCodePoints(0, start), raw.offsetByCodePoints(0, end));
                    eq(text(quote.get("quote"), 1, 1000), exactQuote);
                    if (claim.get("due_expression") != null && exactQuote.contains((String) claim.get("due_expression"))) dueQuoted = true;
                }
                if (!dueQuoted) fail();
            }
            // Authentic execution/provenance/confirmed subject context remain upstream adapter gates.
        }
    }
}
