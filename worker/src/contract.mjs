import {createHash} from 'node:crypto';

export class Held extends Error {
  constructor(code) { super(code); this.code = code; }
}
export const requireThat = (value, code = 'CONTRACT_INVALID') => { if (!value) throw new Held(code); };
export const sha = value => createHash('sha256').update(value).digest('hex');
export const canonical = value => {
  if (value === null || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number') { requireThat(Number.isSafeInteger(value), 'INTEGER_REQUIRED'); return String(value); }
  if (typeof value === 'string') { requireThat(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value), 'INVALID_UNICODE'); return JSON.stringify(value); }
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  requireThat(value && typeof value === 'object', 'OBJECT_REQUIRED');
  return '{' + Object.keys(value).sort().map(k => canonical(k) + ':' + canonical(value[k])).join(',') + '}';
};
export const jsonBytes = value => Buffer.from(canonical(value));
export function exact(x, keys) { requireThat(x && !Array.isArray(x) && typeof x === 'object' && Object.keys(x).sort().join('\0') === [...keys].sort().join('\0'), 'UNEXPECTED_FIELDS'); }
export function integer(n, min = 0, max = Number.MAX_SAFE_INTEGER) { requireThat(Number.isSafeInteger(n) && n >= min && n <= max, 'INTEGER_RANGE'); return n; }
export function text(s, max = 131072) { requireThat(typeof s === 'string' && s.length > 0 && s.length <= max, 'TEXT_REQUIRED'); canonical(s); return s; }
export function identifier(s) { requireThat(typeof s === 'string' && /^[A-Za-z0-9_-]{1,256}$/.test(s), 'IDENTIFIER_REQUIRED'); return s; }
export function hash(s) { requireThat(typeof s === 'string' && /^[0-9a-f]{64}$/.test(s), 'HASH_REQUIRED'); return s; }
export const without = (x, key) => Object.fromEntries(Object.entries(x).filter(([k]) => k !== key));

/** Bounded JSON parser rejects duplicate keys before JSON.parse can discard them. */
export function parse(raw, limit = 524288) {
  const bytes = Buffer.from(raw); requireThat(bytes.length <= limit, 'JSON_SIZE_LIMIT');
  const s = new TextDecoder('utf-8', {fatal: true}).decode(bytes); let i = 0;
  const ws = () => { while (/[ \t\r\n]/.test(s[i] ?? '!')) i++; };
  const str = () => {
    const start = i++; requireThat(s[start] === '"', 'JSON_STRING');
    while (i < s.length) { if (s[i] === '\\') { i += 2; continue; } if (s[i++] === '"') return JSON.parse(s.slice(start, i)); }
    throw new Held('JSON_STRING');
  };
  const value = depth => {
    requireThat(depth <= 32, 'JSON_DEPTH'); ws();
    if (s[i] === '{') {
      i++; const out = Object.create(null), seen = new Set(); ws(); if (s[i] === '}') { i++; return out; }
      for (;;) { ws(); const key = str(); requireThat(!seen.has(key), 'DUPLICATE_JSON_KEY'); seen.add(key); ws(); requireThat(s[i++] === ':', 'JSON_COLON'); out[key] = value(depth + 1); ws(); const ch = s[i++]; if (ch === '}') return out; requireThat(ch === ',', 'JSON_OBJECT'); }
    }
    if (s[i] === '[') {
      i++; const out = []; ws(); if (s[i] === ']') { i++; return out; }
      for (;;) { out.push(value(depth + 1)); ws(); const ch = s[i++]; if (ch === ']') return out; requireThat(ch === ',', 'JSON_ARRAY'); }
    }
    if (s[i] === '"') return str();
    const token = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(s.slice(i)); requireThat(token, 'JSON_VALUE'); i += token[0].length;
    return JSON.parse(token[0]);
  };
  const result = value(0); ws(); requireThat(i === s.length, 'JSON_TRAILING'); canonical(result); return result;
}

const REQUEST = ['schemaVersion','jobId','ownerBinding','sourceId','sourceVersion','audioSha','baseRevision','userRevision','leaseEpoch','resultFileId','kind','creatorMode','audioBytes','audioFileId','requestFileId','lockRevision','taskRevision','archiveRevision','contextJson','contextSha','requestDigest'];
export function validateRequest(raw) {
  const r = parse(raw, 131072); exact(r, REQUEST);
  requireThat(r.schemaVersion === 0 && r.kind === 'CALL_ANALYSIS_REQUEST' && r.creatorMode === 'ANDROID_APP', 'NATIVE_REQUEST_REQUIRED');
  for (const k of ['jobId','sourceId','audioFileId','requestFileId','resultFileId']) identifier(r[k]);
  exact(r.ownerBinding, ['ownerId','driveAccount']); identifier(r.ownerBinding.ownerId); text(r.ownerBinding.driveAccount,254);
  requireThat(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(r.ownerBinding.driveAccount), 'ACCOUNT_REQUIRED');
  integer(r.sourceVersion, 1); integer(r.audioBytes, 1, 10485760);
  for (const k of ['baseRevision','userRevision','leaseEpoch','lockRevision','taskRevision','archiveRevision']) integer(r[k]);
  hash(r.audioSha); hash(r.contextSha); hash(r.requestDigest); text(r.contextJson,65536); parse(Buffer.from(r.contextJson),65536);
  requireThat(sha(r.contextJson) === r.contextSha && sha('call-drive-v0:request\n' + canonical(without(r,'requestDigest'))) === r.requestDigest, 'REQUEST_DIGEST_MISMATCH');
  return r;
}

export function validateEnrollment(x, cfg, now = Date.now()) {
  exact(x, ['schema','reportZone','handoff','businessContext']); requireThat(x.schema === 'ieum.automation.enrollment.v1' && x.reportZone === cfg.reportZone, 'ENROLLMENT_PROFILE_MISMATCH');
  const h = x.handoff;
  exact(h,['schema','purpose','localRequestId','scope','folderId','frozenRequestJson','frozenRequestSha256','nativeRegistration','dotAcceptance','currentRemoteRevocation','writerProvenance','modelExecution','remoteAtomicCas','canonicalWriteAllowed']);
  requireThat(h.schema === 'local.drive_a.foreground.handoff.v1' && h.purpose === 'ONE_EXISTING_OWNER_EXPLICIT_SYNTHETIC_READONLY' && h.canonicalWriteAllowed === false, 'FROZEN_HANDOFF_REQUIRED');
  requireThat(h.dotAcceptance === 'NOT_VERIFIED' && h.currentRemoteRevocation === 'NOT_VERIFIED' && h.writerProvenance === 'NOT_VERIFIED' && h.modelExecution === 'NOT_RUN' && h.remoteAtomicCas === 'NOT_VERIFIED', 'HANDOFF_TRUTH_FLAGS');
  identifier(h.folderId); text(h.localRequestId,256); hash(h.frozenRequestSha256);
  requireThat(sha(h.frozenRequestJson) === h.frozenRequestSha256, 'FROZEN_BYTES_MISMATCH');
  const r = validateRequest(Buffer.from(h.frozenRequestJson));
  requireThat(cfg.approvedAudioSha256?.includes(r.audioSha),'EXACT_SYNTHETIC_AUDIO_NOT_APPROVED');
  exact(h.scope,['sessionId','epoch','ownerBinding']); identifier(h.scope.sessionId); integer(h.scope.epoch);
  requireThat(canonical(h.scope.ownerBinding) === canonical(r.ownerBinding) && r.ownerBinding.ownerId === cfg.ownerId && r.ownerBinding.driveAccount === cfg.driveAccount && h.scope.epoch === cfg.accountEpoch, 'OWNER_EPOCH_MISMATCH');
  const n = h.nativeRegistration;
  exact(n,['state','files','jobId','folderId','creatorModes','sessionBinding','driveOwnerPermissionId','sessionEvidence','nativeAppSessionAuthentication','requestDigest']);
  requireThat(n.jobId === r.jobId && n.folderId === h.folderId && n.requestDigest === r.requestDigest && n.driveOwnerPermissionId === cfg.ownerId && canonical(n.sessionBinding) === canonical(h.scope), 'REGISTRATION_BINDING_MISMATCH');
  requireThat(canonical(n.creatorModes) === '["ANDROID_APP"]' && n.sessionEvidence === 'LOCAL_AUTHORIZATIONCLIENT_AND_DRIVE_ABOUT_OWNER_VERIFIED' && n.nativeAppSessionAuthentication === 'GOOGLE_DRIVE_OWNER_VERIFIED_NOT_OIDC_SERVER_ATTESTED', 'REGISTRATION_PROFILE_MISMATCH');
  exact(n.files,['audio','request','result']); for (const k of ['audio','request','result']) { exact(n.files[k],['fileId']); requireThat(n.files[k].fileId === r[k+'FileId'], 'FILE_BINDING_MISMATCH'); }
  for (const k of ['ownerBinding','sourceId','sourceVersion','audioSha','leaseEpoch','contextJson','baseRevision','userRevision','lockRevision','taskRevision','archiveRevision']) requireThat(canonical(n.state[k]) === canonical(r[k]), 'SOURCE_BINDING_MISMATCH');
  requireThat(n.state.counterpartConfirmed === false && n.state.syntheticAudioApproved === true && n.state.consentGranted === true && n.state.audioAvailable === true && ['excluded','revoked','deleted','archived'].every(k => n.state[k] === false), 'SOURCE_POLICY_HELD');
  const c = parse(Buffer.from(r.contextJson),65536);
  requireThat(c.synthetic === true && c.syntheticCreatorConfirmed === true && c.nativeBinding === 'UNVERIFIED' && canonical(c.authSubject) === canonical(h.scope), 'SYNTHETIC_SOURCE_ONLY');
  integer(c.leaseExpiresMs, now + 1, now + 30*60000); identifier(c.caseId); integer(c.originalStartedAtMs,1,now);
  const b=x.businessContext;exact(b,['caseId','revision','proposedSummary','tasks']);requireThat(b.caseId===c.caseId,'CASE_BINDING_MISMATCH');integer(b.revision);requireThat(typeof b.proposedSummary==='string'&&b.proposedSummary.length<=5000,'CASE_SUMMARY_SIZE');
  requireThat(Array.isArray(b.tasks)&&b.tasks.length<=250,'CASE_TASK_CAPACITY');const ids=new Set();
  for(const t of b.tasks) { exact(t,['id','title','assignee','due','status','manualFields']);identifier(t.id);requireThat(!ids.has(t.id),'CASE_DUPLICATE_TASK');ids.add(t.id);text(t.title,200);text(t.assignee,100);requireThat(t.due===null||typeof t.due==='string','CASE_DUE');requireThat(['open','in_progress','waiting','needs_confirmation','done'].includes(t.status),'CASE_STATUS');requireThat(Array.isArray(t.manualFields)&&t.manualFields.every(k=>['title','assignee','due','status'].includes(k)),'CASE_MANUAL_FIELDS'); }
  return {request:r, context:c, businessContext:b, businessContextSha:sha(canonical(b)), enrollment:x, leaseUntil:c.leaseExpiresMs, requestSha:h.frozenRequestSha256};
}

export const RESULT_KIND = 'CALL_AUTOMATION_API_PROPOSAL';
export const BINDING = ['jobId','ownerBinding','sourceId','sourceVersion','audioSha','baseRevision','userRevision','lockRevision','taskRevision','archiveRevision','leaseEpoch','resultFileId','requestDigest'];
export const ANALYSIS_SCHEMA = {
  type:'object', additionalProperties:false, required:['summary','cumulativeSummary','tasks'], properties:{
    summary:{type:'string'}, cumulativeSummary:{type:'string'}, tasks:{type:'array',items:{type:'object',additionalProperties:false,
      required:['taskId','title','assignee','due','status','quote','start','end'],properties:{
        taskId:{type:['string','null']},title:{type:'string'},assignee:{type:'string'},due:{type:['string','null']},status:{type:'string',enum:['open','in_progress','waiting','needs_confirmation','done']},quote:{type:'string'},start:{type:'integer'},end:{type:'integer'}
      }}}
  }
};
export function validateAnalysis(a, transcript) {
  exact(a,['summary','cumulativeSummary','tasks']); text(a.summary,3000); text(a.cumulativeSummary,5000);
  requireThat(Array.isArray(a.tasks) && a.tasks.length <= 30, 'TASK_CAPACITY'); const cp = Array.from(transcript), seen = new Set();
  for (const t of a.tasks) {
    exact(t,['taskId','title','assignee','due','status','quote','start','end']); if (t.taskId !== null) identifier(t.taskId); text(t.title,200); text(t.assignee,100); text(t.quote,1000);
    integer(t.start,0,cp.length); integer(t.end,t.start+1,cp.length); requireThat(cp.slice(t.start,t.end).join('') === t.quote, 'QUOTE_MISMATCH');
    requireThat(['open','in_progress','waiting','needs_confirmation','done'].includes(t.status), 'TASK_STATUS');
    requireThat(t.due === null || (typeof t.due === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(t.due) && new Date(t.due+'T00:00:00Z').toISOString().slice(0,10) === t.due), 'TASK_DUE');
    const fp = canonical([t.title.normalize('NFKC').trim().toLowerCase(),t.assignee.normalize('NFKC').trim().toLowerCase(),t.due]); requireThat(!seen.has(fp), 'DUPLICATE_TASK'); seen.add(fp);
  }
  return {...a,tasks:a.tasks.map(t=>({...t,status:t.status === 'done' ? 'needs_confirmation' : t.status}))};
}
export function makeResult(r, stt, summary, businessContextSha, revision = 1) {
  text(stt.text); text(stt.requestId,256); text(stt.model,128); text(summary.requestId,256); text(summary.model,128);
  const analysis = validateAnalysis(summary.analysis,stt.text);
  const x = {...Object.fromEntries(BINDING.map(k=>[k,r[k]])), businessContextSha:hash(businessContextSha), schemaVersion:2, requestSchemaVersion:0, kind:RESULT_KIND, state:'PROPOSAL', resultRevision:integer(revision,1),
    stt:{provider:'OPENAI_API',model:stt.model,requestId:stt.requestId,text:stt.text,textSha:sha(stt.text)},
    analysis:{provider:'OPENAI_API',model:summary.model,requestId:summary.requestId,...analysis},
    dotExecution:'NOT_INVOKED', executionEvidence:'PROVIDER_RESPONSES_RECORDED_NOT_INDEPENDENTLY_ATTESTED', deliveryChannel:'AUTHENTICATED_WORKER_ENDPOINT', driveResultWritten:false, writerVerification:'UNRESOLVED', nativeBinding:'UNVERIFIED', displayMode:'READ_ONLY_PROPOSAL', canonicalWriteAllowed:false, applied:false};
  x.resultDigest = sha('ieum-automation-v2:result\n' + canonical(x)); requireThat(jsonBytes(x).length <= 524288,'RESULT_SIZE_LIMIT'); return x;
}
