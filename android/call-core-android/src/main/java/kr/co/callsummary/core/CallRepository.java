package kr.co.callsummary.core;

import java.util.List;
import java.util.Map;

public interface CallRepository extends AutoCloseable {
    Map<String,Object> receipt(String eventId);
    Map<String,Object> observe(Map<String,Object> frozenMetadata,String audioSha256,long nowMs);
    Map<String,Object> claim(String eventId,String recordingId,String processingId,String transcriberVersion,long nowMs);
    AnalysisBinding binding(String recordingId);
    Map<String,Object> storeWorkerCandidate(String eventId,AnalysisBinding binding,Map<String,Object> sourcePackV1,Map<String,Object> candidateV1,String producerVersion,long nowMs);
    Map<String,Object> userCommand(String operation,Map<String,Object> parameters);
    List<Map<String,Object>> listRecordings();
    Map<String,Object> recording(String recordingId);
    void close();
}
