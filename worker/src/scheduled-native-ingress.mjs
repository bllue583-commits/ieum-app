import {createHmac} from 'node:crypto';
import {canonical,parse,sha,requireThat,validateEnrollment} from './contract.mjs';
export const TEXT_LINK_SCHEMA='ieum.automation.scheduled-native-link.v1';
export const TEXT_BUNDLE_SCHEMA='ieum.automation.scheduled-native-bundle.v1';
export async function scheduledNativeBundle({cfg,store,drive,job,link,deviceToken,now=Date.now()}){
  requireThat(link.schema===TEXT_LINK_SCHEMA&&link.jobId===job.job,'TEXT_NATIVE_JOB_BINDING');
  const result=store.result(cfg.ownerId,job.job),verification=store.verification(cfg.ownerId,job.job);
  if(!result||!verification)return null;
  requireThat(verification.state==='APPROVED','TEXT_NATIVE_VERIFIED_RESULT_REQUIRED');
  const v=validateEnrollment(job.enrollment,cfg,now);requireThat(v.request.audioSha===link.audioSha,'TEXT_NATIVE_AUDIO_BINDING');
  const native=await drive.source(v);native.audio.fill(0);
  await drive.metadata(link.folder,null,'application/vnd.google-apps.folder');
  const input=await drive.file(link.input,link.folder,'application/json',65536),output=await drive.file(link.result,link.folder,'application/json',65536);
  requireThat(sha(input.bytes)===link.inputSha&&sha(output.bytes)===link.resultSha,'TEXT_NATIVE_FILE_DIGEST');
  const s=parse(input.bytes),p=parse(output.bytes);
  requireThat(s.schema_version==='scheduler-proof-v1'&&p.schema_version==='scheduler-proof-v1'&&s.synthetic===true&&p.synthetic===true&&s.job_id===link.proofJob&&p.job_id===link.proofJob&&p.source_file_id===link.input,'TEXT_NATIVE_PROOF_BINDING');
  requireThat(Array.isArray(s.transcript)&&s.transcript.length>0&&s.transcript.every(t=>t.speaker==='미확인'&&typeof t.text==='string'),'TEXT_NATIVE_UNVERIFIED_SPEAKERS_REQUIRED');
  requireThat(s.transcript.map(t=>t.text).join('\n')===result.stt.text,'TEXT_NATIVE_TRANSCRIPT_MISMATCH');
  requireThat(p.summary===result.analysis.summary&&Array.isArray(p.tasks)&&p.tasks.length===result.analysis.tasks.length,'TEXT_NATIVE_ANALYSIS_MISMATCH');
  for(let i=0;i<p.tasks.length;i++){const t=p.tasks[i],n=result.analysis.tasks[i];requireThat(t.title===n.title&&t.owner==='미확인'&&t.status==='needs_confirmation'&&t.due===null&&t.evidence?.source_id===s.source_id&&t.evidence?.quote===n.quote&&n.quote.includes(t.timing_text),'TEXT_NATIVE_TASK_MISMATCH');}
  const at=Date.parse(p.produced_at_utc);requireThat(Number.isFinite(at)&&at<=now&&at>=now-30*60000,'TEXT_NATIVE_TIME');
  const againInput=await drive.metadata(link.input,link.folder,'application/json'),againResult=await drive.metadata(link.result,link.folder,'application/json');
  requireThat(againInput.version===input.metadata.version&&againResult.version===output.metadata.version,'TEXT_NATIVE_CHANGED_DURING_READ');
  const signedVerification={schema:'ieum.automation.signed-execution-evidence.v1',algorithm:'HMAC-SHA256',body:verification,signature:createHmac('sha256',deviceToken).update('IEUM_AUTOMATIC_USE_V1\n'+canonical(verification)).digest('hex')};
  const body={link,scope:v.enrollment.handoff.scope,enrollmentSha:sha(canonical(job.enrollment)),nativeResultSha:sha(canonical(result)),verificationSha:sha(canonical(signedVerification)),inputVersion:input.metadata.version,resultVersion:output.metadata.version};
  return {schema:TEXT_BUNDLE_SCHEMA,body,signature:createHmac('sha256',deviceToken).update('IEUM_SCHEDULED_NATIVE_LINK_V1\n'+canonical(body)).digest('hex'),nativeResult:result,verification:signedVerification};
}
