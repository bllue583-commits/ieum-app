import {canonical,sha,makeResult} from '../src/contract.mjs';
import {Http,DriveReader,OpenAIProcessor} from '../src/providers.mjs';
export const NOW=Date.UTC(2026,9,14,0,0,0);
export const audio=Buffer.from('RIFF__FIXED_PUBLIC_TEST_BYTES__WAVE');
export function fixture(jobId='synthetic_api_job_a') {
  const owner={ownerId:'PUBLIC_SYNTHETIC_OWNER',driveAccount:'synthetic.api@example.invalid'},scope={sessionId:'native_public_synthetic_session',epoch:1,ownerBinding:owner};
  const c={synthetic:true,syntheticCreatorConfirmed:true,nativeBinding:'UNVERIFIED',authSubject:scope,leaseExpiresMs:NOW+30*60000,caseId:'PUBLIC_SYNTHETIC_CASE',originalStartedAtMs:NOW-3600000};
  const r={schemaVersion:0,jobId,ownerBinding:owner,sourceId:'PUBLIC_SYNTHETIC_SOURCE',sourceVersion:1,audioSha:sha(audio),baseRevision:2,userRevision:3,leaseEpoch:1,resultFileId:jobId+'_result',kind:'CALL_ANALYSIS_REQUEST',creatorMode:'ANDROID_APP',audioBytes:audio.length,audioFileId:jobId+'_audio',requestFileId:jobId+'_request',lockRevision:4,taskRevision:5,archiveRevision:6,contextJson:canonical(c),contextSha:sha(canonical(c))};r.requestDigest=sha('call-drive-v0:request\n'+canonical(r));
  const state=Object.fromEntries(['ownerBinding','sourceId','sourceVersion','audioSha','leaseEpoch','contextJson','baseRevision','userRevision','lockRevision','taskRevision','archiveRevision'].map(k=>[k,r[k]]));
  Object.assign(state,{stateRevision:0,counterpartConfirmed:false,syntheticAudioApproved:true,consentGranted:true,audioAvailable:true,excluded:false,revoked:false,deleted:false,archived:false});
  const h={schema:'local.drive_a.foreground.handoff.v1',purpose:'ONE_EXISTING_OWNER_EXPLICIT_SYNTHETIC_READONLY',localRequestId:'public_local_request_'+jobId,scope,folderId:jobId+'_folder',frozenRequestJson:canonical(r),frozenRequestSha256:sha(canonical(r)),nativeRegistration:{state,files:{audio:{fileId:r.audioFileId},request:{fileId:r.requestFileId},result:{fileId:r.resultFileId}},jobId,folderId:jobId+'_folder',creatorModes:['ANDROID_APP'],sessionBinding:scope,driveOwnerPermissionId:owner.ownerId,sessionEvidence:'LOCAL_AUTHORIZATIONCLIENT_AND_DRIVE_ABOUT_OWNER_VERIFIED',nativeAppSessionAuthentication:'GOOGLE_DRIVE_OWNER_VERIFIED_NOT_OIDC_SERVER_ATTESTED',requestDigest:r.requestDigest},dotAcceptance:'NOT_VERIFIED',currentRemoteRevocation:'NOT_VERIFIED',writerProvenance:'NOT_VERIFIED',modelExecution:'NOT_RUN',remoteAtomicCas:'NOT_VERIFIED',canonicalWriteAllowed:false};
  const businessContext={caseId:c.caseId,revision:7,proposedSummary:'',tasks:[]};
  const enrollment={schema:'ieum.automation.enrollment.v1',reportZone:'Etc/UTC',handoff:h,businessContext};
  const cfg={contract:'ieum.automation.configuration.v1',enabled:true,ownerId:owner.ownerId,driveAccount:owner.driveAccount,accountEpoch:1,reportZone:'Etc/UTC',publicOrigin:'https://synthetic-worker.example.invalid',transcriptionModel:'TEST_TRANSCRIPTION_MODEL',summaryModel:'TEST_SUMMARY_MODEL',costApprovalRef:'TEST_ONLY_COST_APPROVAL',approvedAudioSha256:[sha(audio)],approvals:{driveRead:true,externalProcessing:true,encryptedPersistence:true,paidApiCalls:true},maxAudioBytes:10485760,maxPaidCallsPerDay:2};
  return {r,c,h,cfg,enrollment,businessContext};
}
export const transcript='🙂합성 일정은 확인 후 알려드리겠습니다.';
export const analysis={summary:'합성 일정 확인 후 안내 제안',cumulativeSummary:'미확정 합성 일정 확인 대기',tasks:[{taskId:null,title:'합성 일정 확인 후 안내',assignee:'확인 필요',due:null,status:'done',quote:'확인 후 알려드리겠습니다.',start:8,end:Array.from(transcript).length}]};
// Correct codepoint offset is calculated explicitly, not inferred by production code.
analysis.tasks[0].start=Array.from(transcript).findIndex((_,i)=>Array.from(transcript).slice(i).join('').startsWith(analysis.tasks[0].quote));
export function fakeApis(f,options={}) {
  const counts={paid:0,stt:0,summary:0,drive:0};let bytesRead=new Set();
  const meta=(id)=>({id,mimeType:id===f.h.folderId?'application/vnd.google-apps.folder':id===f.r.audioFileId?'audio/wav':'application/json',parents:id===f.h.folderId?['root']:[f.h.folderId],owners:[{permissionId:f.cfg.ownerId}],permissions:options.shared?[{id:f.cfg.ownerId,type:'user',role:'owner'},{id:'PUBLIC_OTHER',type:'user',role:'reader'}]:[{id:f.cfg.ownerId,type:'user',role:'owner'}],trashed:false,isAppAuthorized:true,version:options.changed&&bytesRead.has(id)?'2':'1',size:String(id===f.r.audioFileId?audio.length:id===f.r.requestFileId?Buffer.byteLength(f.h.frozenRequestJson):Buffer.byteLength(canonical({kind:'CALL_ANALYSIS_RESULT',schemaVersion:0,state:'PENDING',resultRevision:0,jobId:f.r.jobId,requestDigest:f.r.requestDigest,resultFileId:f.r.resultFileId})))});
  const fetcher=async(url,request)=>{
    const u=new URL(url);if(u.hostname==='www.googleapis.com'){
      counts.drive++;if(u.pathname.endsWith('/about'))return new Response(JSON.stringify({user:{permissionId:options.wrongOwner?'PUBLIC_OTHER':f.cfg.ownerId,emailAddress:f.cfg.driveAccount}}));
      const id=decodeURIComponent(u.pathname.split('/').at(-1));if(u.searchParams.get('alt')==='media'){bytesRead.add(id);return new Response(id===f.r.audioFileId?audio:id===f.r.requestFileId?f.h.frozenRequestJson:canonical({kind:'CALL_ANALYSIS_RESULT',schemaVersion:0,state:'PENDING',resultRevision:0,jobId:f.r.jobId,requestDigest:f.r.requestDigest,resultFileId:f.r.resultFileId}));}
      return new Response(JSON.stringify(meta(id)));
    }
    if(u.pathname.endsWith('/audio/transcriptions')){counts.paid++;counts.stt++;if(options.uncertain)throw new Error('TEST_ONLY_NETWORK_LOSS');await options.afterStt?.();return new Response(JSON.stringify({text:transcript}),{headers:{'x-request-id':'TEST_ONLY_STT_RESPONSE_ID'}});}
    if(u.pathname.endsWith('/responses')){counts.paid++;counts.summary++;const body=JSON.parse(request.body);if(body.text.format.type!=='json_schema'||body.store!==false)throw new Error('Wrong production request');return new Response(JSON.stringify({id:'TEST_ONLY_SUMMARY_RESPONSE_ID',status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(analysis)}]}]}));}
    throw new Error('UNEXPECTED_TEST_URL');
  };
  const http=new Http(fetcher),drive=new DriveReader(f.cfg,{token:async()=> 'TEST_ONLY_MEMORY_GOOGLE_TOKEN'},http),processor=new OpenAIProcessor(f.cfg,'TEST_ONLY_MEMORY_API_KEY',http);
  return {counts,drive,processor};
}
export function vector() { const f=fixture();return {...f,result:makeResult(f.r,{text:transcript,model:f.cfg.transcriptionModel,requestId:'TEST_ONLY_STT_RESPONSE_ID'},{analysis,model:f.cfg.summaryModel,requestId:'TEST_ONLY_SUMMARY_RESPONSE_ID'},sha(canonical(f.businessContext)))}; }
