import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture,audio} from './fixture.mjs';
import {canonical,parse} from '../src/contract.mjs';
import {AUTOMATIC_POLICY,extractivePolicy,signEvidence} from '../src/verification.mjs';
import {nativeBridge} from './native-adapter-bridge.mjs';
const token='PUBLIC_SYNTHETIC_WORKER_DEVICE_CREDENTIAL';
test('production observed stages create signed evidence without promoting payload authority; uncertain semantics stay held',async()=>{
  const f=fixture();const pending={kind:'CALL_ANALYSIS_RESULT',schemaVersion:0,state:'PENDING',resultRevision:0,jobId:f.r.jobId,requestDigest:f.r.requestDigest,resultFileId:f.r.resultFileId};
  const input={enrollment:f.enrollment,audioBase64:audio.toString('base64'),pendingBase64:Buffer.from(canonical(pending)).toString('base64'),deviceToken:token};
  input.clock=f.c.leaseExpiresMs-60000;
  const run=await nativeBridge(input);assert.equal(run.capabilities.automaticUsePolicy,AUTOMATIC_POLICY);assert.equal(run.verification.body.policy,AUTOMATIC_POLICY);assert.equal(run.verification.body.state,'APPROVED');assert.equal(run.verification.body.stages.length,2);
  assert.equal(run.result.canonicalWriteAllowed,false);assert.equal(run.result.dotExecution,'NOT_INVOKED');assert.equal(run.verification.body.independentModelAttestation,false);assert.equal(run.counts.actualNetworkCalls,0);
  const held=await nativeBridge({...input,nonExtractive:true});assert.equal(held.verification.body.state,'HELD');assert.equal(held.result.canonicalWriteAllowed,false);
});
test('extractive automatic policy rejects inferred due, DONE/target changes and altered cumulative history',()=>{
  const text='원문';const business={proposedSummary:'기존'};const result={stt:{text},analysis:{summary:text,cumulativeSummary:'기존\n원문',tasks:[]}};
  assert.equal(extractivePolicy(result,business),'APPROVED_EXTRACTIVE_POLICY');
  for(const task of [{taskId:null,title:text,quote:text,assignee:'확인 필요',due:null,status:'done',start:0,end:2},{taskId:'OTHER',title:text,quote:text,assignee:'확인 필요',due:null,status:'needs_confirmation',start:0,end:2},{taskId:null,title:text,quote:text,assignee:'확인 필요',due:'2026-10-15',status:'needs_confirmation',start:0,end:2}])assert.notEqual(extractivePolicy({...result,analysis:{...result.analysis,tasks:[task]}},business),'APPROVED_EXTRACTIVE_POLICY');
  assert.notEqual(extractivePolicy({...result,analysis:{...result.analysis,cumulativeSummary:'덮어쓴 누적'}},business),'APPROVED_EXTRACTIVE_POLICY');
});

test('scheduled native production handler reads pinned text and returns only the identical cached verified native result',async()=>{
  const f=fixture(),clock=f.c.leaseExpiresMs-60000,text='합성 견적서를 준비해 주세요.';
  const source={schema_version:'scheduler-proof-v1',job_id:'00000000-0000-4000-8000-000000000041',synthetic:true,source_id:'synthetic_call001',transcript:[{speaker:'미확인',text}]};
  const output={schema_version:'scheduler-proof-v1',job_id:source.job_id,synthetic:true,source_file_id:'PUBLIC_TEXT_INPUT',produced_at_utc:new Date(clock).toISOString(),summary:text,tasks:[{title:text,owner:'미확인',status:'needs_confirmation',due:null,timing_text:text,evidence:{source_id:source.source_id,quote:text}}]};
  const {sha}=await import('../src/contract.mjs');
  const files=[['PUBLIC_TEXT_FOLDER',Buffer.alloc(0),'application/vnd.google-apps.folder'],['PUBLIC_TEXT_INPUT',Buffer.from(canonical(source)),'application/json'],['PUBLIC_TEXT_RESULT',Buffer.from(canonical(output)),'application/json']].map(([id,bytes,mimeType])=>({id,base64:bytes.toString('base64'),metadata:{id,mimeType,parents:id==='PUBLIC_TEXT_FOLDER'?['root']:['PUBLIC_TEXT_FOLDER'],owners:[{permissionId:f.cfg.ownerId}],permissions:[{id:f.cfg.ownerId,type:'user',role:'owner'}],trashed:false,isAppAuthorized:true,size:String(bytes.length),version:'1'}}));
  const pending={kind:'CALL_ANALYSIS_RESULT',schemaVersion:0,state:'PENDING',resultRevision:0,jobId:f.r.jobId,requestDigest:f.r.requestDigest,resultFileId:f.r.resultFileId};
  const scheduledText={schema:'ieum.automation.scheduled-native-link.v1',jobId:f.r.jobId,proofJob:source.job_id,folder:'PUBLIC_TEXT_FOLDER',input:'PUBLIC_TEXT_INPUT',result:'PUBLIC_TEXT_RESULT',inputSha:sha(canonical(source)),resultSha:sha(canonical(output)),audioSha:f.r.audioSha,preparedEvidenceSha:'a'.repeat(64)};
  const input={enrollment:f.enrollment,audioBase64:audio.toString('base64'),pendingBase64:Buffer.from(canonical(pending)).toString('base64'),deviceToken:token,clock,scheduledText,extraFiles:files};
  const run=await nativeBridge(input);assert.equal(run.scheduledBundle.body.link.jobId,f.r.jobId);assert.equal(run.scheduledBundle.nativeResult.resultDigest,run.result.resultDigest);assert.equal(run.counts.syntheticHttpSttResponses,1);assert.equal(run.counts.syntheticHttpSummaryResponses,1);assert.equal(run.counts.actualNetworkCalls,0);
  await assert.rejects(()=>nativeBridge({...input,scheduledText:{...scheduledText,audioSha:'0'.repeat(64)}}),/TEXT_NATIVE_AUDIO_BINDING/);
});
