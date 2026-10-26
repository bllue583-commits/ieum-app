import {RecordingFiles,LocalProducerStore,LocalRecordingProducer,whisperSegments} from '../src/local-recording-producer.mjs';
import {canonical,parse,sha} from '../src/contract.mjs';
const chunks=[];for await(const b of process.stdin)chunks.push(b);const cfg=parse(Buffer.concat(chunks),1048576);
const store=new LocalProducerStore(cfg.database,Buffer.from(cfg.existingStoreKeyBase64,'base64')),files=new RecordingFiles(cfg.directory);
try{
 let calls=0;const stt={async transcribe(audio){calls++;const raw=Buffer.from(JSON.stringify({transcription:[{offsets:{from:0,to:1000},text:' 공개 합성 견적서를 확인해 주세요.'}]}));return {...whisperSegments(raw),kind:'SYNTHETIC_FIXTURE',provider:'SYNTHETIC_FIXTURE',runtimeSha:cfg.engine.binarySha,modelSha:cfg.engine.modelSha,rawOutput:raw,exitCode:0,audioSha:sha(audio)};}};
 const producer=new LocalRecordingProducer({files,store,stt,approvedAudioSha256:cfg.approvedAudioSha256,scope:cfg.scope,reportZone:cfg.reportZone});let out;
 if(cfg.operation==='run'){const runs=[];for(const t of cfg.tickets)runs.push(await producer.run(t));out={files:files.list(),runs,providerCalls:calls,sourceOpens:files.opens,results:runs.map(r=>r.state==='RESULT_READY'?store.read(r.id):null)};}
 if(cfg.operation==='reconcile')out={runs:cfg.tickets.map(t=>producer.reconcile(t)),providerCalls:0};
 if(cfg.operation==='read')out=store.read(cfg.id);
 process.stdout.write(canonical(out)+'\n');
}finally{store.close();}
