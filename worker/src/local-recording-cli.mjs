import {existsSync} from 'node:fs';
import {RecordingFiles,WhisperCppProvider,LocalProducerStore,LocalRecordingProducer} from './local-recording-producer.mjs';
import {parse,canonical,requireThat} from './contract.mjs';

// Explicit local invocation only. The caller provides an existing storage key over stdin.
// No listening port, environment credential discovery, OAuth or service deployment.
let store=null;
try{
const chunks=[];let bytes=0;for await(const b of process.stdin){bytes+=b.length;requireThat(bytes<=1048576,'LOCAL_INPUT_SIZE');chunks.push(b);}
const cfg=parse(Buffer.concat(chunks),1048576);requireThat(['run','reconcile','read','cancel'].includes(cfg.operation),'LOCAL_OPERATION_REQUIRED');
if(['run','reconcile'].includes(cfg.operation))requireThat(Array.isArray(cfg.tickets)&&cfg.tickets.length>=1&&cfg.tickets.length<=4,'LOCAL_BATCH_LIMIT');
if(cfg.operation!=='run')requireThat(existsSync(cfg.database),'LOCAL_EXISTING_DATABASE_REQUIRED');
store=new LocalProducerStore(cfg.database,Buffer.from(cfg.existingStoreKeyBase64,'base64'));
  const files=new RecordingFiles(cfg.directory);let provider=null,producer=null;
  if(cfg.operation==='run'||cfg.operation==='reconcile'){
    provider=cfg.operation==='run'?new WhisperCppProvider(cfg.engine):null;
    producer=new LocalRecordingProducer({files,store,stt:provider,approvedAudioSha256:cfg.approvedAudioSha256,scope:cfg.scope,reportZone:cfg.reportZone});
  }
  let output;
  if(cfg.operation==='run'){const runs=[];for(const t of cfg.tickets)runs.push(await producer.run(t));output={files:files.list(),inventoryComplete:files.inventoryComplete,runs,providerCalls:provider.calls,sourceOpens:files.opens,results:runs.map(r=>r.state==='RESULT_READY'?store.read(r.id):null)};}
  if(cfg.operation==='reconcile')output={runs:cfg.tickets.map(t=>producer.reconcile(t)),providerCalls:0,sourceOpens:0};
  if(cfg.operation==='read')output=store.read(cfg.id);
  if(cfg.operation==='cancel'){store.cancel(cfg.id);output={state:store.record(cfg.id)?.state??'ABSENT',inflightRecalled:false};}
  process.stdout.write(canonical(output)+'\n');
}catch(e){process.stdout.write(canonical({state:'HELD',code:/^[A-Z0-9_]+$/.test(e.code??'')?e.code:'LOCAL_PRODUCER_FAILED'})+'\n');process.exitCode=1;}
finally{store?.close();}
