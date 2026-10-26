import {createHmac} from 'node:crypto';
import {canonical,sha,requireThat,BINDING,validateAnalysis} from './contract.mjs';
export const AUTOMATIC_POLICY='IEUM_EXTRACTIVE_SAFE_DERIVED_V1';
export const EVIDENCE_SCHEMA='ieum.automation.execution-evidence.v1';
export const SIGNED_SCHEMA='ieum.automation.signed-execution-evidence.v1';
export const EVIDENCE_DOMAIN='IEUM_AUTOMATIC_USE_V1\n';
/** The model is never given the worker/device key. Payload read-only flags stay unchanged. */
export function signEvidence(body,deviceToken){requireThat(typeof deviceToken==='string'&&deviceToken.length>=32,'DEVICE_WORKER_CREDENTIAL_REQUIRED');return {schema:SIGNED_SCHEMA,algorithm:'HMAC-SHA256',body,signature:createHmac('sha256',deviceToken).update(EVIDENCE_DOMAIN+canonical(body)).digest('hex')};}
export function extractivePolicy(result,business){
  try {
    const a=validateAnalysis({summary:result.analysis.summary,cumulativeSummary:result.analysis.cumulativeSummary,tasks:result.analysis.tasks},result.stt.text);
    requireThat(result.stt.text.includes(a.summary),'AUTO_SUMMARY_NOT_EXACT_SOURCE');
    requireThat(a.cumulativeSummary===(business.proposedSummary?business.proposedSummary+'\n':'')+a.summary,'AUTO_CUMULATIVE_NOT_EXACT_APPEND');
    for(const t of result.analysis.tasks)requireThat(t.taskId===null&&t.title===t.quote&&t.assignee==='확인 필요'&&t.due===null&&t.status==='needs_confirmation','AUTO_TASK_REQUIRES_REVIEW');
    return 'APPROVED_EXTRACTIVE_POLICY';
  }catch(e){return e.code??'AUTO_POLICY_REVIEW_REQUIRED';}
}
export function executionEvidence(cfg,v,result,stt,summary,stages,createdAtMs){
  const reason=extractivePolicy(result,v.businessContext);let state=reason==='APPROVED_EXTRACTIVE_POLICY'?'APPROVED':'HELD',issue=reason;
  const observations=[];
  for(const [name,value,model,requestId,inputSha,outputSha,endpoint] of [
    ['STT',stt,result.stt.model,result.stt.requestId,sha(canonical({audioSha:v.request.audioSha,model:result.stt.model,language:'ko'})),result.stt.textSha,'/v1/audio/transcriptions'],
    ['SUMMARY',summary,result.analysis.model,result.analysis.requestId,sha(canonical({transcriptSha:result.stt.textSha,businessContextSha:v.businessContextSha,sourceId:v.request.sourceId,model:result.analysis.model})),sha(canonical({summary:result.analysis.summary,cumulativeSummary:result.analysis.cumulativeSummary,tasks:result.analysis.tasks})),'/v1/responses']]){
    const o=value.observed;
    if(stages[name]?.status!=='DONE'||!o||o.schema!=='ieum.worker.observed-http-stage.v1'||o.endpoint!==endpoint||o.httpStatus!==200||o.model!==model||o.providerRequestId!==requestId||o.inputSha!==inputSha||o.outputSha!==outputSha||!/^[0-9a-f]{64}$/.test(o.rawResponseSha??'')){state='HELD';issue='PRODUCER_STAGE_EVIDENCE_REQUIRED';}
    observations.push({stage:name,status:stages[name]?.status??'MISSING',...(o??{})});
  }
  return {schema:EVIDENCE_SCHEMA,policy:AUTOMATIC_POLICY,state,reason:issue,createdAtMs,scope:v.enrollment.handoff.scope,
    ...Object.fromEntries(BINDING.map(k=>[k,v.request[k]])),resultRevision:result.resultRevision,resultDigest:result.resultDigest,resultSha:sha(canonical(result)),requestSha:v.requestSha,
    enrollmentSha:sha(canonical(v.enrollment)),businessContextSha:v.businessContextSha,caseId:v.businessContext.caseId,transcriptSha:result.stt.textSha,
    analysisSha:sha(canonical({summary:result.analysis.summary,cumulativeSummary:result.analysis.cumulativeSummary,tasks:result.analysis.tasks})),
    sourceAuthorization:'EXACT_APPROVED_SYNTHETIC_AUDIO',producer:'AUTHENTICATED_WORKER_OBSERVED_HTTP',independentModelAttestation:false,stages:observations};
}
