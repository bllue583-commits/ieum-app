import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {createServer} from 'node:http';
import {parse,Held,requireThat} from './contract.mjs';
import {ProcessingEngine,processingGate} from './engine.mjs';
import {ProcessingStore} from './processing-store.mjs';
import {Http,ExistingGoogleCredential,DriveReader,OpenAIProcessor} from './providers.mjs';
import {createHandler} from './server.mjs';

const [mode,file]=process.argv.slice(2);requireThat(['--check','--run'].includes(mode)&&file,'USAGE_CHECK_OR_RUN_CONFIGURATION');
const cfg=parse(readFileSync(file),16384);
if(mode==='--check') {
  try{processingGate(cfg);process.stdout.write(JSON.stringify({configuration:'READY_FOR_CREDENTIAL_CHECK',networkCalls:0,credentialsRead:0})+'\n');}
  catch(e){process.stdout.write(JSON.stringify({configuration:'HELD',code:e instanceof Held?e.code:'CONFIGURATION_INVALID',networkCalls:0,credentialsRead:0})+'\n');}
}else{
  // This branch is never run in the local implementation task. Credentials are supplied
  // only after separate processing/hosting/grant/cost approval. Do not reuse SIWC tokens.
  processingGate(cfg);requireThat(cfg.contract==='ieum.automation.configuration.v1','CONFIGURATION_VERSION');
  const key=process.env.IEUM_PROCESSING_KEY_B64??'',device=process.env.IEUM_DEVICE_WORKER_TOKEN??'',api=process.env.IEUM_OPENAI_API_KEY??'';
  const keyBytes=Buffer.from(key,'base64');requireThat(keyBytes.length===32,'PERSISTENCE_KEY_REQUIRED');
  requireThat(device.length>=32&&api,'PROCESSING_CREDENTIALS_REQUIRED');
  const http=new Http(),credential=new ExistingGoogleCredential({clientId:process.env.IEUM_GOOGLE_CLIENT_ID,clientSecret:process.env.IEUM_GOOGLE_CLIENT_SECRET,refreshToken:process.env.IEUM_GOOGLE_REFRESH_TOKEN},http);
  const store=new ProcessingStore(resolve(cfg.dataDir,'processing-v1.sqlite'),keyBytes);keyBytes.fill(0);store.recover();
  const engine=new ProcessingEngine({cfg,store,drive:new DriveReader(cfg,credential,http),processor:new OpenAIProcessor(cfg,api,http)});
  const server=createServer(createHandler({cfg,store,engine,deviceToken:device}));
  requireThat(Number.isSafeInteger(cfg.port)&&cfg.port>=1024&&cfg.port<=65535&&Number.isSafeInteger(cfg.intervalSeconds)&&cfg.intervalSeconds>=15&&cfg.intervalSeconds<=300,'SERVER_CONFIGURATION');
  // Private localhost only; deployment must supply a reviewed HTTPS ingress at cfg.publicOrigin.
  server.listen(cfg.port,'127.0.0.1');const interval=setInterval(()=>{engine.tick().catch(()=>{});},cfg.intervalSeconds*1000);
  process.stdout.write(JSON.stringify({state:'LOCAL_PROCESSING_SERVER_STARTED',dotInvoked:false,driveResultWrites:0,canonicalWrites:0,kakaoSends:0})+'\n');
  const stop=()=>{clearInterval(interval);server.close(()=>{store.close();process.exit(0);});};process.once('SIGINT',stop);process.once('SIGTERM',stop);
}
