import {AUTOMATIC_POLICY,executionEvidence} from './verification.mjs';
import {Held,requireThat,validateEnrollment,makeResult} from './contract.mjs';

export function processingGate(cfg) {
  requireThat(cfg.enabled===true,'PROCESSOR_DISABLED');
  requireThat(cfg.automaticUsePolicy==null||cfg.automaticUsePolicy===AUTOMATIC_POLICY,'AUTOMATIC_POLICY_UNSUPPORTED');
  for(const name of ['driveRead','externalProcessing','encryptedPersistence','paidApiCalls'])requireThat(cfg.approvals?.[name]===true,'APPROVAL_REQUIRED_'+name);
  requireThat(typeof cfg.costApprovalRef==='string'&&cfg.costApprovalRef.length>0&&!cfg.costApprovalRef.startsWith('REQUIRES_'),'MODEL_COST_APPROVAL_REQUIRED');
  requireThat(Array.isArray(cfg.approvedAudioSha256)&&cfg.approvedAudioSha256.length>=1&&cfg.approvedAudioSha256.length<=10&&cfg.approvedAudioSha256.every(s=>typeof s==='string'&&/^[0-9a-f]{64}$/.test(s)),'EXACT_SYNTHETIC_AUDIO_APPROVAL_REQUIRED');
  requireThat(typeof cfg.ownerId==='string'&&!cfg.ownerId.startsWith('REQUIRES_'),'OWNER_CONFIGURATION_REQUIRED');
  requireThat(typeof cfg.driveAccount==='string'&&cfg.driveAccount.includes('@'),'ACCOUNT_CONFIGURATION_REQUIRED');
  requireThat(Number.isSafeInteger(cfg.accountEpoch)&&cfg.accountEpoch>=0,'EPOCH_CONFIGURATION_REQUIRED');
  requireThat(Number.isSafeInteger(cfg.maxPaidCallsPerDay)&&cfg.maxPaidCallsPerDay>=2&&cfg.maxPaidCallsPerDay<=100,'PAID_CALL_LIMIT_CONFIGURATION_REQUIRED');
  requireThat(Number.isSafeInteger(cfg.maxAudioBytes)&&cfg.maxAudioBytes>=1&&cfg.maxAudioBytes<=10485760,'AUDIO_LIMIT_CONFIGURATION_REQUIRED');
  for(const m of [cfg.transcriptionModel,cfg.summaryModel])requireThat(typeof m==='string'&&m.length>0&&m.length<=128&&!m.startsWith('REQUIRES_'),'MODEL_SELECTION_REQUIRED');
  try{new Intl.DateTimeFormat('en',{timeZone:cfg.reportZone});}catch{throw new Held('TIMEZONE_CONFIGURATION_REQUIRED');}
  const origin=new URL(cfg.publicOrigin);requireThat(origin.protocol==='https:'&&origin.origin===cfg.publicOrigin&&!origin.username&&!origin.password&&!origin.hostname.toLowerCase().includes('requires_'),'PRIVATE_WORKER_ORIGIN_REQUIRED');
}
export class ProcessingEngine {
  constructor({cfg,store,drive,processor,clock=Date.now}){this.cfg=cfg;this.store=store;this.drive=drive;this.processor=processor;this.clock=clock;this.busy=false;}
  current(job){processingGate(this.cfg);const now=this.clock();requireThat(this.store.job(this.cfg.ownerId,job.job)?.state==='PROCESSING','JOB_CANCELLED_OR_STALE');return validateEnrollment(job.enrollment,this.cfg,now);}
  enroll(body){processingGate(this.cfg);const v=validateEnrollment(body,this.cfg,this.clock());return this.store.enroll(this.cfg.ownerId,v);}
  async paid(job,stage,run){
    const old=this.store.stage(this.cfg.ownerId,job.job,stage);if(old){requireThat(old.status==='DONE','PAID_STAGE_RECONCILE_REQUIRED');return old.value;}
    this.current(job);const day=new Date(this.clock()).toISOString().slice(0,10);this.store.beginStage(this.cfg.ownerId,job.job,stage,day,this.cfg.maxPaidCallsPerDay);
    try{const result=await run();this.store.finishStage(this.cfg.ownerId,job.job,stage,result);return result;}catch(e){this.store.failStage(this.cfg.ownerId,job.job,stage);throw e;}
  }
  async tick(){
    processingGate(this.cfg);if(this.busy)return {state:'BUSY'};this.busy=true;let job,audio;
    try{
      job=this.store.claim(this.cfg.ownerId);if(!job)return {state:'IDLE'};
      let v=this.current(job);const source=await this.drive.source(v);audio=source.audio;this.current(job);
      const stt=await this.paid(job,'STT',()=>this.processor.transcribe(audio));audio.fill(0);audio=null;
      v=this.current(job);await this.drive.unchanged(v,source.snapshot);this.current(job);
      const summary=await this.paid(job,'SUMMARY',()=>this.processor.summarize(stt,{proposedSummary:v.businessContext.proposedSummary,tasks:v.businessContext.tasks},v));
      v=this.current(job);await this.drive.unchanged(v,source.snapshot);this.current(job);
      const known=new Set(v.businessContext.tasks.map(t=>t.id));requireThat(summary.analysis.tasks.every(t=>t.taskId===null||known.has(t.taskId)),'UNKNOWN_TASK_ID');
      const result=makeResult(v.request,stt,summary,v.businessContextSha);const verification=this.cfg.automaticUsePolicy===AUTOMATIC_POLICY?executionEvidence(this.cfg,v,result,stt,summary,{STT:this.store.stage(this.cfg.ownerId,job.job,'STT'),SUMMARY:this.store.stage(this.cfg.ownerId,job.job,'SUMMARY')},this.clock()):null;
      this.store.complete(this.cfg.ownerId,job.job,result,verification);
      return {state:'RESULT_READY',jobId:job.job,resultDigest:result.resultDigest,driveResultWritten:false,dotInvoked:false,canonicalWriteAllowed:false};
    }catch(e){const code=e instanceof Held?e.code:'PROCESSOR_OPERATION_FAILED';if(job)this.store.hold(this.cfg.ownerId,job.job,code);return {state:'HELD',jobId:job?.job??null,code};}
    finally{audio?.fill(0);this.busy=false;}
  }
}
