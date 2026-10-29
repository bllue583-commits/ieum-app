// Pipeline contracts C0-C3 (docs/contracts/README.md). Shared by the GitHub Actions
// server and by the golden fixtures that the Android codec is tested against.
// Every validator is strict: exact key sets, bounded sizes, non-negative safe integers.
import {createHash, createPrivateKey, createPublicKey, createECDH, generateKeyPairSync,
  createCipheriv, createDecipheriv, hkdfSync, randomBytes, sign as signRaw, verify as verifyRaw} from 'node:crypto';
import {canonical, parse, Held, requireThat, exact, integer, text, identifier, hash} from '../contract.mjs';

export {Held, canonical, parse};
export const sha = value => createHash('sha256').update(value).digest('hex');
export const DOC_LIMIT = 524288;
export const STATUSES = ['open','in_progress','waiting','needs_confirmation','done'];
export const SCHEMA = {
  status: 'ieum.device-status.v1',
  transcript: 'ieum.transcript.v1',
  analysis: 'ieum.analysis.v3',
  report: 'ieum.daily-report.v1',
  device: 'ieum.device.v1',
  sealed: 'ieum.sealed.v1',
};
const KIND = {[SCHEMA.status]:'status',[SCHEMA.transcript]:'c1',[SCHEMA.analysis]:'c2',[SCHEMA.report]:'c3'};

const codepoints = s => Array.from(s);
const nullableText = (v, max) => { if (v !== null) text(v, max); return v; };
const isoDate = v => { requireThat(typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && new Date(v + 'T00:00:00Z').toISOString().slice(0, 10) === v, 'DATE_REQUIRED'); return v; };
const nullableDate = v => (v === null ? null : isoDate(v));
const status = v => { requireThat(STATUSES.includes(v), 'TASK_STATUS'); return v; };
const bool = v => { requireThat(typeof v === 'boolean', 'BOOLEAN_REQUIRED'); return v; };
const list = (v, max, code = 'LIST_REQUIRED') => { requireThat(Array.isArray(v) && v.length <= max, code); return v; };
const optionalText = (v, max) => { requireThat(typeof v === 'string' && v.length <= max, 'TEXT_RANGE'); canonical(v); return v; };

/** Unsigned body: everything except digest and signature. */
export const unsigned = x => Object.fromEntries(Object.entries(x).filter(([k]) => k !== 'digest' && k !== 'signature'));
export const digestOf = x => sha(`ieum-pipeline-v1:${KIND[x.schema]}\n` + canonical(unsigned(x)));
export const transcriptIdOf = (recordingId, sourceVersion, audioSha256, modelSha256) => sha(canonical([recordingId, sourceVersion, audioSha256, modelSha256]));
export const analysisIdOf = (transcriptId, revision) => sha(canonical(['c2', transcriptId, revision]));
export const reportIdOf = (deviceId, reportDate, revision) => sha(canonical(['c3', deviceId, reportDate, revision]));

// ---- keys -----------------------------------------------------------------
export const spki = key => (typeof key === 'string' ? createPublicKey({key: Buffer.from(key, 'base64'), format: 'der', type: 'spki'}) : key);
export const pkcs8 = key => (typeof key === 'string' ? createPrivateKey({key: Buffer.from(key, 'base64'), format: 'der', type: 'pkcs8'}) : key);
const toPublic = key => (key.type === 'public' ? key : createPublicKey(key));
export const exportPublic = key => toPublic(key).export({format: 'der', type: 'spki'}).toString('base64');
export const exportPrivate = key => key.export({format: 'der', type: 'pkcs8'}).toString('base64');
export const kid = publicKey => sha(toPublic(spki(publicKey)).export({format: 'der', type: 'spki'}));
export function newKeyPair() {
  const {publicKey, privateKey} = generateKeyPairSync('ec', {namedCurve: 'prime256v1'});
  return {publicKey: exportPublic(publicKey), privateKey: exportPrivate(privateKey)};
}

// ---- signatures: ECDSA P-256 / SHA-256 over "<kind>\n<digest>", DER, base64 ----
const signingInput = x => Buffer.from(`${KIND[x.schema]}\n${x.digest}`, 'utf8');
export function seal(doc, signingPrivateKey) {
  const x = {...unsigned(doc)}; x.digest = digestOf(x);
  x.signature = signRaw('sha256', signingInput(x), {key: pkcs8(signingPrivateKey), dsaEncoding: 'der'}).toString('base64');
  return x;
}
export function verifySignature(x, signingPublicKey) {
  hash(x.digest); requireThat(typeof x.signature === 'string' && x.signature.length <= 200, 'SIGNATURE_REQUIRED');
  requireThat(digestOf(x) === x.digest, 'DIGEST_MISMATCH');
  requireThat(verifyRaw('sha256', signingInput(x), {key: spki(signingPublicKey), dsaEncoding: 'der'}, Buffer.from(x.signature, 'base64')), 'SIGNATURE_INVALID');
  return x;
}

// ---- sealed envelope: ECDH P-256 ephemeral-static, HKDF-SHA256, AES-256-GCM ----
const UNCOMPRESSED_SPKI_PREFIX = Buffer.from('3059301306072a8648ce3d020106082a8648ce3d030107034200', 'hex');
const rawPoint = publicKey => { const der = toPublic(spki(publicKey)).export({format: 'der', type: 'spki'}); requireThat(der.length === 91 && der.subarray(0, 26).equals(UNCOMPRESSED_SPKI_PREFIX), 'P256_KEY_REQUIRED'); return der.subarray(26); };
const rawScalar = privateKey => Buffer.from(pkcs8(privateKey).export({format: 'jwk'}).d, 'base64url');
function sharedSecret(privateKey, publicKey) { const e = createECDH('prime256v1'); e.setPrivateKey(rawScalar(privateKey)); return e.computeSecret(rawPoint(publicKey)); }
function contentKey(shared, epkDer, aad) {
  return Buffer.from(hkdfSync('sha256', shared, createHash('sha256').update(epkDer).digest(), Buffer.concat([Buffer.from('ieum-sealed-v1'), Buffer.from([0]), Buffer.from(aad, 'utf8')]), 32));
}
export function encrypt(plain, recipientPublicKey, aad, {ephemeral, iv} = {}) {
  text(aad, 512); const eph = ephemeral ?? newKeyPair(); const epkDer = Buffer.from(eph.publicKey, 'base64');
  const key = contentKey(sharedSecret(eph.privateKey, recipientPublicKey), epkDer, aad); const nonce = iv ?? randomBytes(12);
  const c = createCipheriv('aes-256-gcm', key, nonce); c.setAAD(Buffer.from(aad, 'utf8'));
  const ct = Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]); key.fill(0);
  return {schema: SCHEMA.sealed, kid: kid(recipientPublicKey), epk: eph.publicKey, iv: nonce.toString('base64'), aad, ct: ct.toString('base64')};
}
export function decrypt(envelope, recipientPrivateKey, expectedAad) {
  exact(envelope, ['schema','kid','epk','iv','aad','ct']); requireThat(envelope.schema === SCHEMA.sealed, 'SEALED_SCHEMA');
  requireThat(envelope.aad === expectedAad, 'SEALED_AAD_MISMATCH');
  requireThat(envelope.kid === kid(createPublicKey(pkcs8(recipientPrivateKey))), 'SEALED_RECIPIENT_MISMATCH');
  const epkDer = Buffer.from(envelope.epk, 'base64'), iv = Buffer.from(envelope.iv, 'base64'), ct = Buffer.from(envelope.ct, 'base64');
  requireThat(iv.length === 12 && ct.length > 16 && ct.length <= DOC_LIMIT + 16, 'SEALED_SIZE');
  const key = contentKey(sharedSecret(recipientPrivateKey, envelope.epk), epkDer, envelope.aad);
  try { const d = createDecipheriv('aes-256-gcm', key, iv); d.setAAD(Buffer.from(envelope.aad, 'utf8')); d.setAuthTag(ct.subarray(ct.length - 16));
    return Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]); }
  catch { throw new Held('SEALED_AUTH_FAILED'); } finally { key.fill(0); }
}
export const aadFor = x => ({[SCHEMA.transcript]: () => 'c1:' + x.transcriptId, [SCHEMA.analysis]: () => 'c2:' + x.analysisId,
  [SCHEMA.report]: () => 'c3:' + x.reportId, [SCHEMA.status]: () => `status:${x.deviceId}:${x.generatedAtMs}`}[x.schema])();
export const sealTo = (doc, recipientPublicKey, opts) => encrypt(Buffer.from(canonical(doc)), recipientPublicKey, aadFor(doc), opts);
export function openFrom(envelope, recipientPrivateKey, validate, expectedAad) {
  const doc = parse(decrypt(envelope, recipientPrivateKey, expectedAad ?? envelope.aad), DOC_LIMIT); validate(doc);
  requireThat(aadFor(doc) === envelope.aad, 'SEALED_AAD_MISMATCH'); return doc;
}

// ---- C0 device status ------------------------------------------------------
export function validateStatus(x) {
  exact(x, ['schema','deviceId','generatedAtMs','pendingTranscription','unknownTime','failedTranscription','lastTranscribedAtMs','modelSha256','digest','signature']);
  requireThat(x.schema === SCHEMA.status, 'STATUS_SCHEMA'); hash(x.deviceId); integer(x.generatedAtMs, 1);
  for (const k of ['pendingTranscription','unknownTime','failedTranscription']) integer(x[k], 0, 100000);
  if (x.lastTranscribedAtMs !== null) integer(x.lastTranscribedAtMs, 1); if (x.modelSha256 !== null) hash(x.modelSha256);
  requireThat(digestOf(x) === x.digest, 'DIGEST_MISMATCH'); return x;
}

// ---- C1 transcript ------------------------------------------------------------
function validateBusiness(b) {
  exact(b, ['boardRevision','linkedCaseId','cases']); integer(b.boardRevision); if (b.linkedCaseId !== null) identifier(b.linkedCaseId);
  list(b.cases, 50, 'CASE_CAPACITY'); const ids = new Set(); let tasks = 0;
  for (const c of b.cases) {
    exact(c, ['caseId','label','summary','revision','tasks']); identifier(c.caseId); requireThat(!ids.has(c.caseId), 'CASE_DUPLICATE'); ids.add(c.caseId);
    text(c.label, 120); optionalText(c.summary, 5000); integer(c.revision); list(c.tasks, 250, 'TASK_CAPACITY'); tasks += c.tasks.length;
    const tids = new Set();
    for (const t of c.tasks) {
      exact(t, ['taskId','title','assignee','due','status','manualFields']); identifier(t.taskId); requireThat(!tids.has(t.taskId), 'TASK_DUPLICATE'); tids.add(t.taskId);
      text(t.title, 240); optionalText(t.assignee, 100); requireThat(t.due === null || (typeof t.due === 'string' && t.due.length <= 120), 'TASK_DUE'); status(t.status);
      list(t.manualFields, 4); requireThat(t.manualFields.every(f => ['title','assignee','due','status'].includes(f)) && new Set(t.manualFields).size === t.manualFields.length, 'MANUAL_FIELDS');
    }
  }
  requireThat(tasks <= 1000, 'TASK_CAPACITY');
  requireThat(b.linkedCaseId === null || ids.has(b.linkedCaseId), 'LINKED_CASE_MISSING');
}
export function validateTranscript(x) {
  exact(x, ['schema','transcriptId','deviceId','createdAtMs','source','stt','business','policy','digest','signature']);
  requireThat(x.schema === SCHEMA.transcript, 'TRANSCRIPT_SCHEMA'); hash(x.deviceId); integer(x.createdAtMs, 1);
  const s = x.source; exact(s, ['recordingId','sourceVersion','audioSha256','durationMs','originalStartedAtMs']);
  identifier(s.recordingId); integer(s.sourceVersion, 1); hash(s.audioSha256); integer(s.durationMs, 1, 6 * 3600000); if (s.originalStartedAtMs !== null) integer(s.originalStartedAtMs, 1);
  const t = x.stt; exact(t, ['engine','model','modelSha256','runtimeSha256','language','vadApplied','segments','text','textSha256']);
  requireThat(t.engine === 'WHISPER_CPP_LOCAL', 'STT_ENGINE'); text(t.model, 128); hash(t.modelSha256); hash(t.runtimeSha256);
  requireThat(['ko','en','auto'].includes(t.language), 'STT_LANGUAGE'); bool(t.vadApplied); text(t.text, 131072); hash(t.textSha256);
  list(t.segments, 4096, 'SEGMENTS_REQUIRED'); requireThat(t.segments.length > 0, 'SEGMENTS_REQUIRED'); let last = 0, joined = '';
  for (const g of t.segments) { exact(g, ['startMs','endMs','text']); integer(g.startMs, last); integer(g.endMs, g.startMs + 1, s.durationMs + 1000); text(g.text, 8000); last = g.endMs; joined += g.text; }
  requireThat(joined === t.text && sha(Buffer.from(t.text, 'utf8')) === t.textSha256, 'TRANSCRIPT_TEXT_BINDING');
  requireThat(x.transcriptId === transcriptIdOf(s.recordingId, s.sourceVersion, s.audioSha256, t.modelSha256), 'TRANSCRIPT_ID');
  validateBusiness(x.business);
  const p = x.policy; exact(p, ['epoch','consentEpoch','exclusionChecked']); integer(p.epoch); integer(p.consentEpoch); requireThat(p.exclusionChecked === true, 'EXCLUSION_CHECK_REQUIRED');
  requireThat(Buffer.byteLength(canonical(x)) <= DOC_LIMIT, 'DOC_SIZE');
  requireThat(digestOf(x) === x.digest, 'DIGEST_MISMATCH'); return x;
}

// ---- C2 analysis ---------------------------------------------------------------
/** quote must equal the transcript code points [start,end). */
export function checkQuote(transcriptText, q) {
  const cp = codepoints(transcriptText); integer(q.start, 0, cp.length); integer(q.end, q.start + 1, cp.length);
  requireThat(cp.slice(q.start, q.end).join('') === q.quote, 'QUOTE_MISMATCH');
}
export function validateAnalysisBody(a) {
  // The part Claude produces (also the structured-output JSON schema below).
  exact(a, ['caseSuggestion','summary','cumulativeSummary','taskChanges']);
  const c = a.caseSuggestion; exact(c, ['caseId','confidence','quote','start','end']); if (c.caseId !== null) identifier(c.caseId);
  requireThat(['high','medium','low'].includes(c.confidence), 'CASE_CONFIDENCE'); text(c.quote, 1000);
  text(a.summary, 3000); text(a.cumulativeSummary, 5000); list(a.taskChanges, 30, 'TASK_CHANGE_CAPACITY');
  for (const t of a.taskChanges) {
    exact(t, ['op','taskId','title','assignee','due','status','quote','start','end']);
    requireThat(['create','update','unchanged'].includes(t.op), 'TASK_OP'); requireThat((t.op === 'create') === (t.taskId === null), 'TASK_ID_FOR_OP');
    if (t.taskId !== null) identifier(t.taskId); text(t.title, 240); optionalText(t.assignee, 100); nullableDate(t.due); status(t.status); text(t.quote, 1000);
  }
  return a;
}
export function validateAnalysis(x, transcript) {
  exact(x, ['schema','analysisId','transcriptId','deviceId','revision','baseBoardRevision','targetCaseId','provider','createdAtMs','caseSuggestion','summary','cumulativeSummary','taskChanges','usage','digest','signature']);
  requireThat(x.schema === SCHEMA.analysis, 'ANALYSIS_SCHEMA'); hash(x.transcriptId); hash(x.deviceId); integer(x.revision, 1); integer(x.baseBoardRevision); integer(x.createdAtMs, 1);
  if (x.targetCaseId !== null) identifier(x.targetCaseId);
  requireThat(x.analysisId === analysisIdOf(x.transcriptId, x.revision), 'ANALYSIS_ID');
  exact(x.provider, ['name','model','requestId']); requireThat(x.provider.name === 'ANTHROPIC_API', 'PROVIDER'); text(x.provider.model, 128); text(x.provider.requestId, 256);
  exact(x.usage, ['inputTokens','outputTokens']); integer(x.usage.inputTokens); integer(x.usage.outputTokens);
  validateAnalysisBody({caseSuggestion: x.caseSuggestion, summary: x.summary, cumulativeSummary: x.cumulativeSummary, taskChanges: x.taskChanges});
  requireThat(x.taskChanges.every(t => t.status !== 'done'), 'DONE_REQUIRES_CONFIRMATION');
  if (transcript) {
    requireThat(transcript.transcriptId === x.transcriptId && transcript.deviceId === x.deviceId && transcript.business.boardRevision === x.baseBoardRevision && transcript.business.linkedCaseId === x.targetCaseId, 'ANALYSIS_TRANSCRIPT_BINDING');
    checkQuote(transcript.stt.text, x.caseSuggestion); for (const t of x.taskChanges) checkQuote(transcript.stt.text, t);
    const cases = new Map(transcript.business.cases.map(c => [c.caseId, c]));
    requireThat(x.caseSuggestion.caseId === null || cases.has(x.caseSuggestion.caseId), 'SUGGESTED_CASE_UNKNOWN');
    const scope = x.targetCaseId ?? x.caseSuggestion.caseId; const known = new Set((cases.get(scope)?.tasks ?? []).map(t => t.taskId));
    requireThat(x.taskChanges.every(t => t.taskId === null || known.has(t.taskId)), 'TASK_UNKNOWN');
  }
  requireThat(digestOf(x) === x.digest, 'DIGEST_MISMATCH'); return x;
}

// ---- C3 daily report -------------------------------------------------------------
export const KST_OFFSET_MS = 9 * 3600000;
export function reportRange(reportDate) {
  isoDate(reportDate); const until = Date.parse(reportDate + 'T00:00:00Z') - KST_OFFSET_MS; return {fromMs: until - 86400000, untilMs: until};
}
export function validateReport(x) {
  exact(x, ['schema','reportId','deviceId','reportDate','zone','range','revision','generatedAtMs','cases','pending','weekly','digest','signature']);
  requireThat(x.schema === SCHEMA.report && x.zone === 'Asia/Seoul', 'REPORT_SCHEMA'); hash(x.deviceId); isoDate(x.reportDate); integer(x.revision, 1); integer(x.generatedAtMs, 1);
  requireThat(x.reportId === reportIdOf(x.deviceId, x.reportDate, x.revision), 'REPORT_ID');
  exact(x.range, ['fromMs','untilMs']); const r = reportRange(x.reportDate); requireThat(x.range.fromMs === r.fromMs && x.range.untilMs === r.untilMs, 'REPORT_RANGE');
  list(x.cases, 100, 'REPORT_CASES');
  for (const c of x.cases) {
    exact(c, ['caseId','label','summary','cumulativeSummary','newTasks','changedTasks','needsConfirmation','transcriptIds']);
    if (c.caseId !== null) identifier(c.caseId); text(c.label, 120); text(c.summary, 3000); optionalText(c.cumulativeSummary, 5000);
    list(c.newTasks, 60); for (const t of c.newTasks) { exact(t, ['title','due','status']); text(t.title, 240); nullableDate(t.due); status(t.status); }
    list(c.changedTasks, 60); for (const t of c.changedTasks) { exact(t, ['taskId','title','due','status']); identifier(t.taskId); text(t.title, 240); nullableDate(t.due); status(t.status); }
    list(c.needsConfirmation, 60); for (const t of c.needsConfirmation) { exact(t, ['taskId','title','reason']); if (t.taskId !== null) identifier(t.taskId); text(t.title, 240); requireThat(['DONE_CLAIMED','MANUAL_TASK','CASE_UNCONFIRMED','DUE_CONFLICT'].includes(t.reason), 'CONFIRM_REASON'); }
    list(c.transcriptIds, 200); c.transcriptIds.forEach(hash);
  }
  const p = x.pending; exact(p, ['untranscribed','awaitingAnalysis','failed','unknownTime','deviceStatusAtMs']);
  for (const k of ['untranscribed','awaitingAnalysis','failed','unknownTime']) integer(p[k], 0, 100000); if (p.deviceStatusAtMs !== null) integer(p.deviceStatusAtMs, 1);
  exact(x.weekly, ['fromMs','untilMs','summary']); integer(x.weekly.fromMs); integer(x.weekly.untilMs, x.weekly.fromMs); optionalText(x.weekly.summary, 5000);
  requireThat(x.weekly.untilMs === x.range.untilMs && x.weekly.fromMs === x.range.untilMs - 7 * 86400000, 'WEEKLY_RANGE');
  requireThat(digestOf(x) === x.digest, 'DIGEST_MISMATCH'); return x;
}

// ---- device registration (public, unsigned file in the inbox repository) ----
export function validateDevice(x) {
  exact(x, ['schema','deviceId','signingKey','encryptionKey','label','registeredAtMs']);
  requireThat(x.schema === SCHEMA.device, 'DEVICE_SCHEMA'); rawPoint(x.signingKey); rawPoint(x.encryptionKey);
  requireThat(x.deviceId === kid(x.signingKey), 'DEVICE_ID'); text(x.label, 80); integer(x.registeredAtMs, 1); return x;
}

// ---- inbox repository layout ----------------------------------------------------
export const paths = {
  device: deviceId => `devices/${hash(deviceId)}.json`,
  status: (deviceId, generatedAtMs) => `inbox/${hash(deviceId)}/status/${integer(generatedAtMs, 1)}.sealed.json`,
  transcript: (deviceId, transcriptId) => `inbox/${hash(deviceId)}/transcripts/${hash(transcriptId)}.sealed.json`,
  analysis: (deviceId, transcriptId, revision) => `outbox/${hash(deviceId)}/analysis/${hash(transcriptId)}-r${integer(revision, 1)}.sealed.json`,
  report: (deviceId, reportDate, revision) => `outbox/${hash(deviceId)}/reports/${isoDate(reportDate)}-r${integer(revision, 1)}.sealed.json`,
};

/** Claude returns quotes only; offsets are located here (first exact occurrence, code points). */
export function locateQuote(transcriptText, quote) {
  text(quote, 1000); const i = transcriptText.indexOf(quote); if (i < 0) return null;
  const start = codepoints(transcriptText.slice(0, i)).length; return {start, end: start + codepoints(quote).length};
}

/** JSON schema for Claude structured outputs: the analysis body without offsets. */
export const ANALYSIS_OUTPUT_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['caseSuggestion','summary','cumulativeSummary','taskChanges'],
  properties: {
    caseSuggestion: {type: 'object', additionalProperties: false, required: ['caseId','confidence','quote'], properties: {
      caseId: {type: ['string','null']}, confidence: {type: 'string', enum: ['high','medium','low']}, quote: {type: 'string'}}},
    summary: {type: 'string'}, cumulativeSummary: {type: 'string'},
    taskChanges: {type: 'array', items: {type: 'object', additionalProperties: false, required: ['op','taskId','title','assignee','due','status','quote'], properties: {
      op: {type: 'string', enum: ['create','update','unchanged']}, taskId: {type: ['string','null']}, title: {type: 'string'}, assignee: {type: 'string'},
      due: {type: ['string','null']}, status: {type: 'string', enum: STATUSES}, quote: {type: 'string'}}}},
  },
};
