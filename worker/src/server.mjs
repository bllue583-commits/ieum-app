import {TEXT_LINK_SCHEMA,scheduledNativeBundle} from './scheduled-native-ingress.mjs';
import {AUTOMATIC_POLICY,EVIDENCE_SCHEMA,signEvidence} from './verification.mjs';
import {timingSafeEqual} from 'node:crypto';
import {canonical,parse,sha,Held,requireThat,identifier} from './contract.mjs';
import {processingGate} from './engine.mjs';

export function createHandler({cfg,store,engine,deviceToken}) {
  requireThat(typeof deviceToken==='string'&&deviceToken.length>=32,'DEVICE_WORKER_CREDENTIAL_REQUIRED');const pin=Buffer.from(sha(deviceToken),'hex');
  const reply=(res,status,value)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(value===null?'':canonical(value));};
  return async(req,res)=>{
    try{
      const supplied=req.headers.authorization??'',digest=Buffer.from(sha(supplied.startsWith('Bearer ')?supplied.slice(7):''),'hex');requireThat(timingSafeEqual(pin,digest),'WORKER_AUTH_REQUIRED');processingGate(cfg);
      const url=new URL(req.url,'http://127.0.0.1');requireThat(!url.search,'QUERY_REJECTED');
      if(req.method==='GET'&&url.pathname==='/v1/automation/capabilities')return reply(res,200,{schema:'ieum.automation.capabilities.v1',ownerId:cfg.ownerId,driveAccount:cfg.driveAccount,accountEpoch:cfg.accountEpoch,reportZone:cfg.reportZone,resultKind:'CALL_AUTOMATION_API_PROPOSAL',resultSchemaVersion:2,driveResultWritten:false,dotInvoked:false,canonicalWriteAllowed:false,...(cfg.automaticUsePolicy===AUTOMATIC_POLICY?{automaticUsePolicy:AUTOMATIC_POLICY,executionEvidenceSchema:EVIDENCE_SCHEMA,scheduledTextIngressSchema:TEXT_LINK_SCHEMA}:{})});
      const match=/^\/v1\/automation\/jobs\/([A-Za-z0-9_-]{1,256})(?:\/(result|cancel|verification|scheduled-text))?$/.exec(url.pathname);requireThat(match,'ROUTE_NOT_FOUND');const id=identifier(match[1]);
      if(req.method==='PUT'&&!match[2]){
        const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;requireThat(size<=262144,'ENROLLMENT_SIZE_LIMIT');chunks.push(chunk);}
        const body=parse(Buffer.concat(chunks),262144);requireThat(body.handoff?.nativeRegistration?.jobId===id,'ROUTE_JOB_MISMATCH');const r=engine.enroll(body),job=store.job(cfg.ownerId,id);
        return reply(res,200,{schema:'ieum.automation.job-status.v1',...r,requestSha:job.request_sha,enrollmentSha:sha(canonical(job.enrollment)),dotInvoked:false,driveResultWritten:false});
      }
      const job=store.job(cfg.ownerId,id);requireThat(job,'JOB_NOT_FOUND');
      if(req.method==='POST'&&match[2]==='cancel'){store.cancel(cfg.ownerId,id);return reply(res,200,{jobId:id,state:'CANCELLED',inflightRecalled:false});}
      if(req.method==='POST'&&match[2]==='scheduled-text'){
        requireThat(cfg.automaticUsePolicy===AUTOMATIC_POLICY,'AUTOMATIC_POLICY_NOT_ENABLED');requireThat(job.state==='RESULT_READY'||job.state==='PROCESSING'||job.state==='QUEUED','TEXT_NATIVE_JOB_HELD');
        const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;requireThat(size<=16384,'TEXT_LINK_SIZE');chunks.push(chunk);}
        const link=parse(Buffer.concat(chunks),16384),bundle=await scheduledNativeBundle({cfg,store,drive:engine.drive,job,link,deviceToken,now:engine.clock()});
        return reply(res,bundle?200:204,bundle);
      }
      if(req.method==='GET'&&match[2]==='verification'){
        requireThat(cfg.automaticUsePolicy===AUTOMATIC_POLICY,'AUTOMATIC_POLICY_NOT_ENABLED');requireThat(job.state!=='CANCELLED','JOB_CANCELLED');
        const proof=store.verification(cfg.ownerId,id);if(!proof)return reply(res,204,null);
        return reply(res,200,signEvidence(proof,deviceToken));
      }
      if(req.method==='GET'&&match[2]==='result'){
        requireThat(job.state!=='CANCELLED','JOB_CANCELLED');const value=store.result(cfg.ownerId,id);
        if(value)return reply(res,200,value);if(job.state==='HELD')return reply(res,409,{schema:'ieum.automation.held.v1',jobId:id,code:job.issue});return reply(res,204,null);
      }
      if(req.method==='GET'&&!match[2])return reply(res,200,{schema:'ieum.automation.job-status.v1',jobId:id,state:job.state,issue:job.issue,requestSha:job.request_sha,enrollmentSha:sha(canonical(job.enrollment)),dotInvoked:false,driveResultWritten:false});
      throw new Held('ROUTE_NOT_FOUND');
    }catch(e){const code=e instanceof Held?e.code:'WORKER_OPERATION_FAILED';reply(res,code==='WORKER_AUTH_REQUIRED'?401:code==='JOB_NOT_FOUND'||code==='ROUTE_NOT_FOUND'?404:409,{code});}
  };
}
