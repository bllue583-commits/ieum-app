import {readFileSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {Http,DriveReader,OpenAIProcessor} from '../src/providers.mjs';
import {ProcessingStore} from '../src/processing-store.mjs';
import {ProcessingEngine} from '../src/engine.mjs';
import {canonical,parse,sha} from '../src/contract.mjs';
import {createHandler} from '../src/server.mjs';
import {AUTOMATIC_POLICY,signEvidence} from '../src/verification.mjs';
/** Test transport boundary only. The engine/provider/Drive reader/evidence builder are production code. */
export async function nativeBridge(input){
  const enrollment=input.enrollment,h=enrollment.handoff,r=JSON.parse(h.frozenRequestJson),audio=Buffer.from(input.audioBase64,'base64'),pending=Buffer.from(input.pendingBase64,'base64');
  const cfg={enabled:true,automaticUsePolicy:AUTOMATIC_POLICY,ownerId:r.ownerBinding.ownerId,driveAccount:r.ownerBinding.driveAccount,accountEpoch:h.scope.epoch,reportZone:enrollment.reportZone,publicOrigin:'https://public-fixture.example.invalid',transcriptionModel:'PUBLIC_OBSERVED_STT_MODEL',summaryModel:'PUBLIC_OBSERVED_SUMMARY_MODEL',costApprovalRef:'SYNTHETIC_TEST_ONLY_NO_PAID_CALLS',approvedAudioSha256:[sha(audio)],approvals:{driveRead:true,externalProcessing:true,encryptedPersistence:true,paidApiCalls:true},maxAudioBytes:10485760,maxPaidCallsPerDay:2};
  const text=input.transcript??'합성 견적서를 준비해 주세요.';const counts={syntheticHttpSttResponses:0,syntheticHttpSummaryResponses:0,actualNetworkCalls:0,actualModelExecutions:0};
  const file=new Map([[r.audioFileId,audio],[r.requestFileId,Buffer.from(h.frozenRequestJson)],[r.resultFileId,pending],[h.folderId,Buffer.alloc(0)]]);
  for(const extra of input.extraFiles??[])file.set(extra.id,Buffer.from(extra.base64,'base64'));
  const fetcher=async(url,options)=>{
    const u=new URL(url);
    if(u.hostname==='www.googleapis.com'){
      if(u.pathname.endsWith('/about'))return new Response(JSON.stringify({user:{permissionId:cfg.ownerId,emailAddress:cfg.driveAccount}}));
      const id=decodeURIComponent(u.pathname.split('/').at(-1));if(!file.has(id))throw new Error('UNEXPECTED_SYNTHETIC_FILE');
      if(u.searchParams.get('alt')==='media')return new Response(file.get(id));
      const extra=(input.extraFiles??[]).find(x=>x.id===id);if(extra)return new Response(JSON.stringify(extra.metadata));
      return new Response(JSON.stringify({id,mimeType:id===h.folderId?'application/vnd.google-apps.folder':id===r.audioFileId?'audio/wav':'application/json',parents:id===h.folderId?['root']:[h.folderId],owners:[{permissionId:cfg.ownerId}],permissions:[{id:cfg.ownerId,type:'user',role:'owner'}],trashed:false,isAppAuthorized:true,size:String(file.get(id).length),version:'1'}));
    }
    if(u.hostname==='api.openai.com'&&u.pathname==='/v1/audio/transcriptions'){
      counts.syntheticHttpSttResponses++;if(options.body.get('language')!=='ko'||options.body.get('model')!==cfg.transcriptionModel)throw new Error('PRODUCTION_STT_REQUEST_MISMATCH');
      return new Response(JSON.stringify({text}),{headers:{'x-request-id':'PUBLIC_SYNTHETIC_STT_HTTP_RECEIPT'}});
    }
    if(u.hostname==='api.openai.com'&&u.pathname==='/v1/responses'){
      counts.syntheticHttpSummaryResponses++;const body=JSON.parse(options.body),data=JSON.parse(body.input);
      const summary=input.nonExtractive?'원문에 없는 의미 추정입니다.':(input.summary??text);const quote=input.summary??text;const start=Array.from(text.slice(0,text.indexOf(quote))).length;
      const analysis={summary,cumulativeSummary:(data.previousProposedSummary?data.previousProposedSummary+'\n':'')+summary,tasks:[{taskId:null,title:quote,assignee:'확인 필요',due:null,status:'needs_confirmation',quote,start,end:start+Array.from(quote).length}]};
      return new Response(JSON.stringify({id:'PUBLIC_SYNTHETIC_SUMMARY_HTTP_RECEIPT',status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(analysis)}]}]}));
    }
    throw new Error('NETWORK_FORBIDDEN_UNEXPECTED_URL');
  };
  const http=new Http(fetcher),store=new ProcessingStore(':memory:',randomBytes(32));
  try{
    const engine=new ProcessingEngine({cfg,store,drive:new DriveReader(cfg,{token:async()=> 'PUBLIC_TEST_DRIVE_TOKEN'},http),processor:new OpenAIProcessor(cfg,'PUBLIC_TEST_API_TOKEN',http),clock:input.clock?()=>input.clock:Date.now});
    engine.enroll(enrollment);const state=await engine.tick();if(state.state!=='RESULT_READY')throw new Error(state.code??'SYNTHETIC_ENGINE_HELD');
    const result=store.result(cfg.ownerId,r.jobId),body=store.verification(cfg.ownerId,r.jobId);if(!body)throw new Error('PRODUCER_VERIFICATION_MISSING');
    const handler=createHandler({cfg,store,engine,deviceToken:input.deviceToken});
    const get=async(path,body=null)=>{let text='',status=0;await handler({method:body?'POST':'GET',url:path,headers:{authorization:'Bearer '+input.deviceToken},async *[Symbol.asyncIterator](){if(body)yield Buffer.from(canonical(body));}},{writeHead(code){status=code;},end(value){text=value??'';}});if(status!==200){let code='PRODUCTION_HANDLER_HELD';try{code=parse(Buffer.from(text)).code??code;}catch{}throw new Error(code);}return parse(Buffer.from(text));};
    const capabilities=await get('/v1/automation/capabilities');const verification=await get('/v1/automation/jobs/'+r.jobId+'/verification');
    const wrong=structuredClone(body);wrong.sourceVersion++;const wrongSourceVerification=signEvidence(wrong,input.deviceToken);
    const scheduledBundle=input.scheduledText?await get('/v1/automation/jobs/'+r.jobId+'/scheduled-text',input.scheduledText):null;
    return {result,verification,wrongSourceVerification,capabilities,counts,producerState:state.state,scheduledBundle};
  }finally{store.close();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try{const input=parse(readFileSync(0),8388608);process.stdout.write(canonical(await nativeBridge(input)));}
  catch(e){process.stderr.write(String(e.code??e.message??'BRIDGE_FAILED')+'\n');process.exitCode=1;}
}
