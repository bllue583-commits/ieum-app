import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ProcessingStore} from '../src/processing-store.mjs';
import {ProcessingEngine,processingGate} from '../src/engine.mjs';
import {createHandler} from '../src/server.mjs';
import {parse,canonical,validateEnrollment,sha} from '../src/contract.mjs';
import {validateScheduledResult,scheduledFixture} from '../src/scheduled-text-proof.mjs';
import {fixture,fakeApis,NOW,vector} from './fixture.mjs';
const setup=(options={})=>{const f=fixture(),store=new ProcessingStore(':memory:',Buffer.alloc(32,0x5a)),apis=fakeApis(f,options);let now=NOW;const engine=new ProcessingEngine({cfg:f.cfg,store,...apis,clock:()=>now});return {f,store,apis,engine,time:n=>{now=n;}};};

test('real Drive/HTTP/provider/store code carries one registered source to immutable proposal; duplicates do not bill',async()=>{
  const {f,store,apis,engine}=setup();try{
    assert.equal(engine.enroll(f.enrollment).duplicate,false);assert.equal(engine.enroll(f.enrollment).duplicate,true);
    const out=await engine.tick();assert.equal(out.state,'RESULT_READY');assert.equal(apis.counts.paid,2);
    const result=store.result(f.cfg.ownerId,f.r.jobId);assert.equal(result.analysis.tasks[0].status,'needs_confirmation');assert.equal(result.businessContextSha,sha(canonical(f.businessContext)));assert.equal(result.dotExecution,'NOT_INVOKED');assert.equal(result.driveResultWritten,false);assert.equal(result.applied,false);
    assert.equal((await engine.tick()).state,'IDLE');assert.equal(apis.counts.paid,2);assert.equal(store.result('PUBLIC_OTHER',f.r.jobId),null);
    const sealed=store.db.prepare('SELECT body FROM stages').all().map(x=>x.body).join('');assert.equal(sealed.includes('합성'),false);
    assert.throws(()=>store.db.prepare('UPDATE results SET digest=?').run('0'.repeat(64)),/RESULT_IMMUTABLE/);
  }finally{store.close();}
});
test('owner/shared-file/version boundaries reject before either paid stage',async()=>{
  for(const options of [{wrongOwner:true},{shared:true},{changed:true}]){const {f,store,apis,engine}=setup(options);try{engine.enroll(f.enrollment);assert.equal((await engine.tick()).state,'HELD');assert.equal(apis.counts.paid,0);assert.equal(store.result(f.cfg.ownerId,f.r.jobId),null);}finally{store.close();}}
});
test('duplicate JSON, mixed owner/epoch and changed frozen registration are rejected',()=>{
  assert.throws(()=>parse(Buffer.from('{"a":1,"a":2}')),/DUPLICATE_JSON_KEY/);const f=fixture();
  for(const modify of [x=>{x.handoff.scope.epoch=2;},x=>{x.handoff.nativeRegistration.files.result.fileId='PUBLIC_OTHER';},x=>{x.businessContext.caseId='PUBLIC_OTHER_CASE';}]){
    const x=JSON.parse(JSON.stringify(f.enrollment));modify(x);assert.throws(()=>validateEnrollment(x,f.cfg,NOW));
  }
  assert.throws(()=>processingGate({...f.cfg,enabled:false}),/PROCESSOR_DISABLED/);
});
test('unknown paid outcome and interrupted durable claim are held without automatic re-execution',async()=>{
  const {f,store,apis,engine}=setup({uncertain:true});try{engine.enroll(f.enrollment);assert.equal((await engine.tick()).state,'HELD');assert.equal(store.stage(f.cfg.ownerId,f.r.jobId,'STT').status,'UNKNOWN');engine.enroll(f.enrollment);assert.equal((await engine.tick()).state,'IDLE');assert.equal(apis.counts.paid,1);}finally{store.close();}
  const s=setup();try{s.engine.enroll(s.f.enrollment);const j=s.store.claim(s.f.cfg.ownerId);s.store.beginStage(s.f.cfg.ownerId,j.job,'STT','2026-10-14',2);s.store.recover();assert.equal(s.store.job(s.f.cfg.ownerId,j.job).state,'HELD');assert.equal(s.store.stage(s.f.cfg.ownerId,j.job,'STT').status,'UNKNOWN');assert.equal((await s.engine.tick()).state,'IDLE');assert.equal(s.apis.counts.paid,0);}finally{s.store.close();}
});
test('in-flight cancel/lease expiry stop summary and native result; completed STT stays encrypted evidence',async()=>{
  for(const expire of [false,true]){let s;s=setup({afterStt:async()=>{if(expire)s.time(NOW+30*60000);else s.store.cancel(s.f.cfg.ownerId,s.f.r.jobId);}});try{s.engine.enroll(s.f.enrollment);assert.equal((await s.engine.tick()).state,'HELD');assert.equal(s.apis.counts.stt,1);assert.equal(s.apis.counts.summary,0);assert.equal(s.store.result(s.f.cfg.ownerId,s.f.r.jobId),null);}finally{s.store.close();}}
});
test('global paid-call allowance covers STT and summary across jobs',async()=>{
  const {f,store,apis,engine}=setup();try{engine.enroll(f.enrollment);await engine.tick();const second=fixture('synthetic_api_job_b');const apis2=fakeApis(second);const e2=new ProcessingEngine({cfg:f.cfg,store,...apis2,clock:()=>NOW});e2.enroll(second.enrollment);const held=await e2.tick();assert.equal(held.code,'PAID_CALL_LIMIT');assert.equal(apis.counts.paid,2);assert.equal(apis2.counts.paid,0);}finally{store.close();}
});
test('production authenticated handler binds immutable registration and returns exact original result without sockets',async()=>{
  const {f,store,engine}=setup(),token='TEST_ONLY_DEVICE_CREDENTIAL_32_BYTES_LONG';const h=createHandler({cfg:f.cfg,store,engine,deviceToken:token});
  const call=async(method,path,body,auth=token)=>{const req={method,url:path,headers:{authorization:'Bearer '+auth},async *[Symbol.asyncIterator](){if(body)yield Buffer.from(canonical(body));}},res={status:0,body:'',writeHead(status){this.status=status;},end(body){this.body=body;}};await h(req,res);return res;};
  try{assert.equal((await call('GET','/v1/automation/capabilities',null,'PUBLIC_WRONG_TOKEN')).status,401);const ack=await call('PUT','/v1/automation/jobs/'+f.r.jobId,f.enrollment);assert.equal(ack.status,200);assert.equal(JSON.parse(ack.body).enrollmentSha,sha(canonical(f.enrollment)));await engine.tick();const result=await call('GET','/v1/automation/jobs/'+f.r.jobId+'/result');assert.equal(result.status,200);assert.equal(result.body,canonical(store.result(f.cfg.ownerId,f.r.jobId)));const changed=JSON.parse(JSON.stringify(f.enrollment));changed.businessContext.proposedSummary='changed';assert.equal((await call('PUT','/v1/automation/jobs/'+f.r.jobId,changed)).status,409);}finally{store.close();}
});
test('scheduled text validator checks content only; it cannot turn local validation into wake/audio/Kakao proof',()=>{
  const x={schema:'ieum.scheduled-text-result.v1',synthetic:true,proof_request_id:'PUBLIC_PARENT_PROOF',source_file_id:'PUBLIC_NEW_FILE',source_sha256:'a'.repeat(64),execution_context:'SCHEDULED_DOT_TEXT_ONLY',summary:'합성 일정 확인 대기',tasks:[{title:'일정 확인 후 안내',owner:'미확인',due:null,status:'open',evidence:{sample_id:scheduledFixture.sample_id,quote:'최종 일정은 다시 확인해서 알려드리겠습니다.'}}],applied:false,canonical_write_allowed:false,stt_executed:false,android_upload_verified:false,kakao_sent:false};const expected={proofRequestId:x.proof_request_id,sourceFileId:x.source_file_id,sourceSha:x.source_sha256};assert.equal(validateScheduledResult(x,expected).automaticInvocationIndependentlyVerified,false);assert.throws(()=>validateScheduledResult({...x,kakao_sent:true},expected));assert.throws(()=>validateScheduledResult({...x,tasks:[{...x.tasks[0],status:'done'}]},expected));assert.equal(vector().result.analysis.tasks[0].status,'needs_confirmation');
});
