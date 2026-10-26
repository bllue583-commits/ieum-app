import {spawn} from 'node:child_process';
import {DatabaseSync} from 'node:sqlite';
import {createCipheriv,createDecipheriv,randomBytes} from 'node:crypto';
import {constants,openSync,closeSync,fstatSync,readFileSync,opendirSync,lstatSync,realpathSync,mkdtempSync,writeFileSync,rmSync,mkdirSync,chmodSync} from 'node:fs';
import {resolve,join,basename,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {BINDING,canonical,parse,sha,requireThat,Held,validateRequest,validateAnalysis,validateEnrollment} from './contract.mjs';

export const LOCAL_SCHEMA='ieum.local-recording-producer-result.v1';
export const LOCAL_POLICY='IEUM_EXTRACTIVE_SAFE_DERIVED_V1';
export const localDigest=x=>sha('ieum-local-recording-producer-v1\n'+canonical(Object.fromEntries(Object.entries(x).filter(([k])=>k!=='resultDigest'))));

/** Explicit directory only. No recursive search, implicit grant, symlink or filename identity. */
export class RecordingFiles {
  constructor(directory){this.root=realpathSync(directory);requireThat(this.root===resolve(directory)&&lstatSync(this.root).isDirectory(),'LOCAL_AUDIO_DIRECTORY_REQUIRED');this.opens=0;}
  list(maxEntries=256){requireThat(Number.isSafeInteger(maxEntries)&&maxEntries>=1&&maxEntries<=1024,'LOCAL_INVENTORY_LIMIT');const dir=opendirSync(this.root),out=[];this.inventoryComplete=false;
    try{for(let i=0;i<maxEntries;i++){const x=dir.readSync();if(!x){this.inventoryComplete=true;break;}if(x.isFile()&&!x.isSymbolicLink()&&x.name.toLowerCase().endsWith('.wav'))out.push({name:x.name,bytes:lstatSync(join(this.root,x.name)).size});}if(!this.inventoryComplete)this.inventoryComplete=dir.readSync()===null;}
    finally{dir.closeSync();}return out.sort((a,b)=>a.name.localeCompare(b.name));}
  read(name,expectedSha,expectedBytes){
    requireThat(typeof name==='string'&&name===basename(name)&&name.toLowerCase().endsWith('.wav'),'LOCAL_AUDIO_BASENAME_REQUIRED');
    const path=join(this.root,name);requireThat(!lstatSync(path).isSymbolicLink()&&realpathSync(path)===path,'LOCAL_AUDIO_SYMLINK_REJECTED');
    const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);try{
      const before=fstatSync(fd,{bigint:true});requireThat(before.isFile()&&before.size>44n&&before.size<=10485760n,'LOCAL_AUDIO_SIZE');
      this.opens++;const bytes=readFileSync(fd),after=fstatSync(fd,{bigint:true});
      requireThat(['dev','ino','size','mtimeNs','ctimeNs'].every(k=>before[k]===after[k])&&bytes.length===Number(after.size),'LOCAL_AUDIO_CHANGED_DURING_READ');
      requireThat(bytes.length===expectedBytes&&sha(bytes)===expectedSha,'LOCAL_AUDIO_BINDING_CHANGED');
      pcm16(bytes);return bytes;
    }finally{closeSync(fd);}
  }
}

/** whisper-cli accepts PCM16 WAV; reject empty/header-only or unsupported audio before a call. */
export function pcm16(bytes){
  requireThat(bytes.length>=44&&bytes.toString('ascii',0,4)==='RIFF'&&bytes.toString('ascii',8,12)==='WAVE'&&bytes.readUInt32LE(4)+8===bytes.length,'LOCAL_WAV_REQUIRED');
  let pos=12,fmt=null,data=null;
  while(pos+8<=bytes.length){const n=bytes.readUInt32LE(pos+4),tag=bytes.toString('ascii',pos,pos+4);requireThat(pos+8+n<=bytes.length,'LOCAL_WAV_CHUNK');
    if(tag==='fmt '){requireThat(!fmt&&n>=16,'LOCAL_WAV_FORMAT');fmt=bytes.subarray(pos+8,pos+8+n);}
    if(tag==='data'){requireThat(!data,'LOCAL_WAV_DATA');data=bytes.subarray(pos+8,pos+8+n);}pos+=8+n+(n%2);
  }
  requireThat(pos===bytes.length&&fmt&&data&&fmt.readUInt16LE(0)===1&&fmt.readUInt16LE(2)===1&&fmt.readUInt32LE(4)===16000&&fmt.readUInt16LE(14)===16&&data.length>=3200&&data.length%2===0,'LOCAL_PCM16_MONO_16K_REQUIRED');
  return {frames:data.length/2,durationMs:Math.floor(data.length/32)};
}

export function whisperSegments(raw){
  let x;try{x=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(raw));}catch{throw new Held('LOCAL_STT_JSON_INVALID');}
  requireThat(Array.isArray(x.transcription)&&x.transcription.length>0&&x.transcription.length<=256,'LOCAL_STT_SEGMENTS_REQUIRED');let last=0;
  const segments=x.transcription.map(s=>{const from=s.offsets?.from,to=s.offsets?.to;
    requireThat(Number.isSafeInteger(from)&&Number.isSafeInteger(to)&&from>=last&&to>from&&typeof s.text==='string'&&s.text.trim()&&s.text.length<=8000,'LOCAL_STT_SEGMENT_INVALID');last=to;
    return {startMs:from,endMs:to,text:s.text};});
  const text=segments.map(s=>s.text).join('');requireThat(text.trim()&&text.length<=131072,'LOCAL_STT_TEXT_REQUIRED');return {text,segments};
}

/** Real local process. No shell, stdin prompt/reference transcript, API key, network or GPU. */
export class WhisperCppProvider {
  constructor({binary,model,binarySha,modelSha,language='ko',timeoutMs=90000}){
    this.binary=resolve(binary);this.model=resolve(model);this.binarySha=binarySha;this.modelSha=modelSha;this.language=language;this.timeoutMs=timeoutMs;this.calls=0;
    requireThat(['ko','en','auto'].includes(language)&&Number.isSafeInteger(timeoutMs)&&timeoutMs>=1000&&timeoutMs<=120000,'LOCAL_STT_CONFIGURATION');this.verify();
  }
  verify(){requireThat(!lstatSync(this.binary).isSymbolicLink()&&!lstatSync(this.model).isSymbolicLink()&&sha(readFileSync(this.binary))===this.binarySha&&sha(readFileSync(this.model))===this.modelSha,'LOCAL_RUNTIME_MODEL_PIN_MISMATCH');}
  async transcribe(audio,{cancelled=()=>false}={}){
    pcm16(audio);this.verify();requireThat(!cancelled(),'LOCAL_PRODUCER_STOPPED');const dir=mkdtempSync(join(tmpdir(),'ieum-local-stt-'));chmodSync(dir,0o700);
    try{const input=join(dir,'input.wav'),output=join(dir,'output');writeFileSync(input,audio,{mode:0o600});this.calls++;
      const args=['-m',this.model,'-f',input,'-l',this.language,'-ng','-np','-oj','-of',output,'-t','4'];
      const exit=await new Promise((ok,bad)=>{
        const child=spawn(this.binary,args,{shell:false,stdio:['ignore','pipe','pipe'],env:{PATH:'/usr/bin:/bin',LANG:'en_US.UTF-8'}});let size=0,done=false;
        const finish=(fn,x)=>{if(done)return;done=true;clearTimeout(timer);clearInterval(stop);fn(x);};
        const timer=setTimeout(()=>{child.kill('SIGKILL');finish(bad,new Held('LOCAL_STT_TIMEOUT'));},this.timeoutMs);
        const stop=setInterval(()=>{if(cancelled()){child.kill('SIGKILL');finish(bad,new Held('LOCAL_PRODUCER_STOPPED'));}},25);
        for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{size+=b.length;if(size>2097152){child.kill('SIGKILL');finish(bad,new Held('LOCAL_STT_OUTPUT_LIMIT'));}});
        child.once('error',()=>finish(bad,new Held('LOCAL_STT_START_FAILED')));child.once('close',code=>finish(ok,code));
      });
      requireThat(exit===0&&!cancelled(),'LOCAL_STT_NOT_COMPLETED');const raw=readFileSync(output+'.json');requireThat(raw.length>0&&raw.length<=262144,'LOCAL_STT_OUTPUT_LIMIT');
      const decoded=whisperSegments(raw);this.verify();return {...decoded,kind:'CURRENT_LOCAL_PROCESS',provider:'WHISPER_CPP_LOCAL',runtimeSha:this.binarySha,modelSha:this.modelSha,rawOutput:raw,exitCode:exit,audioSha:sha(audio)};
    }finally{rmSync(dir,{recursive:true,force:true});}
  }
}

/** A deterministic extraction, not a language-model/meaning review or a completion inference. */
export function extractiveSummary(text,business){
  requireThat(typeof business.proposedSummary==='string'&&business.proposedSummary.length<=5000,'LOCAL_BUSINESS_CONTEXT');
  const sentences=text.match(/[^.!?。！？\n]+[.!?。！？]?/gu)?.map(s=>s.trim()).filter(Boolean)??[];
  const summary=Array.from(sentences[0]??text.trim()).slice(0,300).join('');requireThat(summary&&text.includes(summary),'LOCAL_SUMMARY_QUOTE');
  const cumulativeSummary=(business.proposedSummary?business.proposedSummary+'\n':'')+summary;requireThat(cumulativeSummary.length<=5000,'LOCAL_CUMULATIVE_CAPACITY');
  const selected=sentences.find(s=>/확인|검토|review|please/i.test(s));const tasks=[];
  if(selected){const quote=Array.from(selected).slice(0,180).join(''),start=Array.from(text.slice(0,text.indexOf(quote))).length;
    tasks.push({taskId:null,title:quote,assignee:'확인 필요',due:null,status:'needs_confirmation',quote,start,end:start+Array.from(quote).length});}
  return validateAnalysis({summary,cumulativeSummary,tasks},text);
}

/** Existing caller-supplied key only. No key issuance/plaintext transcript fallback. */
export class LocalProducerStore {
  constructor(path,key){requireThat(Buffer.isBuffer(key)&&key.length===32,'LOCAL_EXISTING_STORE_KEY_REQUIRED');this.key=Buffer.from(key);
    if(path!==':memory:'){mkdirSync(dirname(path),{recursive:true,mode:0o700});chmodSync(dirname(path),0o700);}this.db=new DatabaseSync(path);if(path!==':memory:')chmodSync(path,0o600);
    this.db.exec(`PRAGMA journal_mode=DELETE;PRAGMA synchronous=FULL;
      CREATE TABLE IF NOT EXISTS local_producer_jobs(id TEXT PRIMARY KEY,ticket_sha TEXT NOT NULL,state TEXT NOT NULL,issue TEXT NOT NULL,ticket TEXT NOT NULL,result TEXT,observation TEXT);
      CREATE TRIGGER IF NOT EXISTS local_ticket_immutable BEFORE UPDATE OF ticket_sha,ticket ON local_producer_jobs BEGIN SELECT RAISE(ABORT,'LOCAL_TICKET_IMMUTABLE');END;
      CREATE TRIGGER IF NOT EXISTS local_result_immutable BEFORE UPDATE OF result,observation ON local_producer_jobs WHEN OLD.result IS NOT NULL BEGIN SELECT RAISE(ABORT,'LOCAL_RESULT_IMMUTABLE');END;
      CREATE TRIGGER IF NOT EXISTS local_no_delete BEFORE DELETE ON local_producer_jobs BEGIN SELECT RAISE(ABORT,'LOCAL_RESULT_IMMUTABLE');END;`);
  }
  seal(x,aad){const iv=randomBytes(12),c=createCipheriv('aes-256-gcm',this.key,iv);c.setAAD(Buffer.from(aad));const b=Buffer.concat([c.update(canonical(x)),c.final()]);return Buffer.concat([iv,c.getAuthTag(),b]).toString('base64');}
  open(raw,aad){try{const b=Buffer.from(raw,'base64'),d=createDecipheriv('aes-256-gcm',this.key,b.subarray(0,12));d.setAAD(Buffer.from(aad));d.setAuthTag(b.subarray(12,28));return parse(Buffer.concat([d.update(b.subarray(28)),d.final()]),1048576);}catch{throw new Held('LOCAL_ENCRYPTED_STORE_REVIEW');}}
  tx(f){this.db.exec('BEGIN IMMEDIATE');try{const x=f();this.db.exec('COMMIT');return x;}catch(e){this.db.exec('ROLLBACK');throw e;}}
  record(id){const x=this.db.prepare('SELECT * FROM local_producer_jobs WHERE id=?').get(id);return x&&{...x,ticket:this.open(x.ticket,'ticket:'+id)};}
  enroll(id,ticket){return this.tx(()=>{const h=sha(canonical(ticket)),old=this.record(id);if(old){requireThat(old.ticket_sha===h,'LOCAL_TICKET_CHANGED');return old;}
    this.db.prepare("INSERT INTO local_producer_jobs VALUES(?,?,'QUEUED','',?,NULL,NULL)").run(id,h,this.seal(ticket,'ticket:'+id));return this.record(id);});}
  claim(id){return this.db.prepare("UPDATE local_producer_jobs SET state='RUNNING' WHERE id=? AND state='QUEUED'").run(id).changes===1;}
  recover(){this.db.prepare("UPDATE local_producer_jobs SET state='HELD',issue='LOCAL_INTERRUPTED_RECONCILE_REQUIRED' WHERE state='RUNNING'").run();}
  receipt(id){const r=this.record(id);return r&&sha('ieum-local-producer-receipt-v1\n'+r.ticket_sha);}
  cancel(id){this.db.prepare("UPDATE local_producer_jobs SET state='CANCELLED',issue='LOCAL_STOP_INFLIGHT_NOT_RECALLED' WHERE id=?").run(id);}
  held(id,code){this.db.prepare("UPDATE local_producer_jobs SET state='HELD',issue=? WHERE id=? AND state NOT IN ('CANCELLED','RESULT_READY')").run(code,id);}
  complete(id,result,observation){this.tx(()=>{requireThat(this.record(id)?.state==='RUNNING','LOCAL_JOB_NOT_CURRENT');this.db.prepare("UPDATE local_producer_jobs SET state='RESULT_READY',result=?,observation=? WHERE id=? AND state='RUNNING'").run(this.seal(result,'result:'+id),this.seal(observation,'observation:'+id),id);});}
  read(id){const r=this.record(id);return r?.state==='RESULT_READY'?{result:this.open(r.result,'result:'+id),observation:this.open(r.observation,'observation:'+id)}:null;}
  close(){this.db.close();this.key.fill(0);}
}

export class LocalRecordingProducer {
  constructor({files,store,stt,approvedAudioSha256,scope,reportZone='Etc/UTC',clock=Date.now,cancelled=()=>false}){this.files=files;this.store=store;this.stt=stt;this.approved=new Set(approvedAudioSha256);this.scope=scope;this.reportZone=reportZone;this.clock=clock;this.cancelled=cancelled;}
  validate(ticket){const h=parse(Buffer.from(ticket.frozenHandoff),262144),r=validateRequest(Buffer.from(h.frozenRequestJson));
    requireThat(canonical(h.scope)===canonical(this.scope),'LOCAL_SCOPE_CHANGED');
    validateEnrollment({schema:'ieum.automation.enrollment.v1',reportZone:this.reportZone,handoff:h,businessContext:ticket.businessContext},
      {ownerId:this.scope.ownerBinding.ownerId,driveAccount:this.scope.ownerBinding.driveAccount,accountEpoch:this.scope.epoch,reportZone:this.reportZone,approvedAudioSha256:[...this.approved]},this.clock());
    const c=parse(Buffer.from(r.contextJson),65536);requireThat(c.synthetic===true&&c.syntheticCreatorConfirmed===true&&c.leaseExpiresMs>this.clock()&&canonical(c.authSubject)===canonical(h.scope),'LOCAL_SYNTHETIC_SCOPE_OR_LEASE');
    requireThat(ticket.businessContext.caseId===c.caseId&&this.approved.has(r.audioSha),'LOCAL_SOURCE_APPROVAL_REQUIRED');return {h,r,c};
  }
  async run(ticket){let id=sha(ticket.frozenHandoff),audio;
    try{ticket=parse(Buffer.from(canonical(ticket)),1048576);const {h,r}=this.validate(ticket);requireThat(!this.cancelled(),'LOCAL_PRODUCER_STOPPED');
      audio=this.files.read(ticket.audioFileName,r.audioSha,r.audioBytes);const old=this.store.enroll(id,ticket);
      if(old.state==='RESULT_READY')return {id,receipt:this.store.receipt(id),state:old.state,duplicate:true};
      if(!this.store.claim(id))return {id,receipt:this.store.receipt(id),state:old.state,duplicate:true};
      const observed=await this.stt.transcribe(audio,{cancelled:this.cancelled});requireThat(!this.cancelled(),'LOCAL_PRODUCER_STOPPED');this.validate(ticket);
      const after=this.files.read(ticket.audioFileName,r.audioSha,r.audioBytes);after.fill(0);
      requireThat(observed.audioSha===r.audioSha&&observed.exitCode===0&&['CURRENT_LOCAL_PROCESS','SYNTHETIC_FIXTURE','IMPORTED_PRIOR_RUN'].includes(observed.kind),'LOCAL_STT_OBSERVATION_INVALID');
      const analysis=extractiveSummary(observed.text,ticket.businessContext),rawSha=sha(observed.rawOutput);
      const result={schema:LOCAL_SCHEMA,kind:'LOCAL_RECORDING_PROPOSAL',requestSha:sha(h.frozenRequestJson),businessContextSha:sha(canonical(ticket.businessContext)),binding:Object.fromEntries(BINDING.map(k=>[k,r[k]])),revision:1,
        stt:{provider:observed.provider,runtimeSha:observed.runtimeSha,modelSha:observed.modelSha,text:observed.text,textSha:sha(observed.text),segments:observed.segments},
        analysis:{policy:LOCAL_POLICY,...analysis},execution:{kind:observed.kind,rawOutputSha:rawSha,exitCode:0},canonicalWriteAllowed:false,applied:false,dotInvoked:false};
      result.resultDigest=localDigest(result);
      const witness={kind:observed.kind,audioSha:r.audioSha,requestSha:result.requestSha,businessContextSha:result.businessContextSha,runtimeSha:observed.runtimeSha,modelSha:observed.modelSha,rawOutputSha:rawSha,rawOutputBase64:observed.rawOutput.toString('base64'),exitCode:0};
      requireThat(!this.cancelled(),'LOCAL_PRODUCER_STOPPED');this.validate(ticket);this.store.complete(id,result,witness);
      return {id,receipt:this.store.receipt(id),state:'RESULT_READY',duplicate:false};
    }catch(e){const code=e instanceof Held?e.code:'LOCAL_PRODUCER_FAILED';if(this.store.record(id))this.store.held(id,code);return {id,state:this.store.record(id)?.state==='CANCELLED'?'CANCELLED':'HELD',code};}
    finally{audio?.fill(0);}
  }
  reconcile(ticket){this.validate(ticket);const id=sha(ticket.frozenHandoff),r=this.store.record(id);requireThat(!r||r.ticket_sha===sha(canonical(ticket)),'LOCAL_TICKET_CHANGED');return r?{id,receipt:this.store.receipt(id),state:r.state}:null;}
}
