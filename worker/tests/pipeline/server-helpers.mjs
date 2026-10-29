// Test helpers for the pipeline server: temporary inbox trees, TEST-ONLY keys, synthetic C1 documents and a fake Claude.
import {readFileSync, writeFileSync, mkdirSync, mkdtempSync, readdirSync, statSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {dirname, join, relative} from 'node:path';
import * as c from '../../src/pipeline/contract.mjs';

const fixtures = join(dirname(fileURLToPath(import.meta.url)), '../../../docs/contracts/fixtures');
export const keys = JSON.parse(readFileSync(join(fixtures, 'test-keys.json'), 'utf8'));
export const DEVICE = {schema: c.SCHEMA.device, deviceId: c.kid(keys.device.signing.publicKey), signingKey: keys.device.signing.publicKey,
  encryptionKey: keys.device.encryption.publicKey, label: 'test device', registeredAtMs: 1793260000000};
export const SERVER = {signingKey: keys.server.signing.privateKey, encryptionKey: keys.server.encryption.privateKey};
export const MODEL_SHA = c.sha('test-model');
/** KST wall clock → epoch ms. */
export const kst = s => Date.parse(s + '+09:00');

export function newInbox() {
  const root = mkdtempSync(join(tmpdir(), 'ieum-inbox-'));
  writeJson(root, c.paths.device(DEVICE.deviceId), DEVICE);
  return root;
}
export function writeJson(root, rel, value) { mkdirSync(dirname(join(root, rel)), {recursive: true}); writeFileSync(join(root, rel), JSON.stringify(value)); }
export const readJson = (root, rel) => JSON.parse(readFileSync(join(root, rel), 'utf8'));

export const CASES = [
  {caseId: 'case_a', label: '시험 업무 A', summary: '이전 요약 A', revision: 1, tasks: [
    {taskId: 'task_quote', title: '견적서 발송', assignee: '확인 필요', due: null, status: 'open', manualFields: []},
    {taskId: 'task_manual', title: '현장 방문', assignee: '', due: '2026-11-03', status: 'open', manualFields: ['due']},
    {taskId: 'task_title', title: '자료 정리', assignee: '', due: null, status: 'open', manualFields: ['title']}]},
  {caseId: 'case_b', label: '시험 업무 B', summary: '', revision: 1, tasks: [{taskId: 'task_other', title: '다른 일', assignee: '', due: null, status: 'open', manualFields: []}]},
];

export function transcript({recordingId = 'rec_1', text = '견적서는 금요일까지 보내주세요. 계약서 초안도 확인 부탁드립니다.', startedAtMs = kst('2026-10-29T10:00:00'),
  linkedCaseId = 'case_a', cases = CASES, boardRevision = 5, signingKey = keys.device.signing.privateKey, deviceId = DEVICE.deviceId} = {}) {
  const audioSha256 = c.sha('audio:' + recordingId);
  const source = {recordingId, sourceVersion: 1, audioSha256, durationMs: 60000, originalStartedAtMs: startedAtMs};
  return c.seal({schema: c.SCHEMA.transcript, transcriptId: c.transcriptIdOf(recordingId, 1, audioSha256, MODEL_SHA), deviceId, createdAtMs: (startedAtMs ?? kst('2026-10-29T12:00:00')) + 120000, source,
    stt: {engine: 'WHISPER_CPP_LOCAL', model: 'ggml-large-v3-turbo', modelSha256: MODEL_SHA, runtimeSha256: c.sha('runtime'), language: 'ko', vadApplied: true,
      segments: [{startMs: 0, endMs: 60000, text}], text, textSha256: c.sha(Buffer.from(text))},
    business: {boardRevision, linkedCaseId, cases}, policy: {epoch: 1, consentEpoch: 1, exclusionChecked: true}}, signingKey);
}
export function upload(root, doc, {recipient = keys.server.encryption.publicKey, aad} = {}) {
  const env = aad ? c.encrypt(Buffer.from(c.canonical(doc)), recipient, aad) : c.sealTo(doc, recipient);
  writeJson(root, c.paths.transcript(doc.deviceId, doc.transcriptId), env); return doc;
}
export function uploadStatus(root, fields = {}) {
  const s = c.seal({schema: c.SCHEMA.status, deviceId: DEVICE.deviceId, generatedAtMs: kst('2026-10-29T23:05:00'), pendingTranscription: 2, unknownTime: 1, failedTranscription: 1,
    lastTranscribedAtMs: kst('2026-10-29T23:00:00'), modelSha256: MODEL_SHA, ...fields}, keys.device.signing.privateKey);
  writeJson(root, c.paths.status(DEVICE.deviceId, s.generatedAtMs), c.sealTo(s, keys.server.encryption.publicKey)); return s;
}

/** Fake Claude: `respond(userData, call)` returns the model output; every call is recorded. */
export function fakeClaude(respond = defaultOutput, {usage = {inputTokens: 1200, outputTokens: 300}, fail} = {}) {
  const calls = [];
  return {calls, async analyse(req) {
    calls.push(req); if (fail) throw fail(req, calls.length);
    const data = JSON.parse(req.user.slice(req.user.indexOf('\n') + 1));
    return {output: respond(data, req), requestId: 'req_' + calls.length, usage: typeof usage === 'function' ? usage(calls.length) : usage, model: req.model};
  }};
}
export function defaultOutput(data) {
  const caseId = data.linkedCaseId ?? 'case_a';
  return {caseSuggestion: {caseId, confidence: 'high', quote: '견적서는 금요일까지 보내주세요.'}, summary: '견적서 금요일 발송 요청.', cumulativeSummary: '이전 요약 A\n견적서 금요일 발송 요청.',
    taskChanges: [{op: 'update', taskId: 'task_quote', title: '견적서 발송', assignee: '확인 필요', due: '2026-10-30', status: 'open', quote: '견적서는 금요일까지 보내주세요.'},
      {op: 'create', taskId: null, title: '계약서 초안 확인', assignee: '확인 필요', due: null, status: 'open', quote: '계약서 초안도 확인 부탁드립니다.'}]};
}

export function files(root) {
  const out = {};
  const walk = d => { for (const n of readdirSync(d)) { const p = join(d, n); if (statSync(p).isDirectory()) walk(p); else out[relative(root, p)] = c.sha(readFileSync(p)); } };
  walk(root); return out;
}
/** contract.parse yields null-prototype objects; normalise for deepStrictEqual. */
export const plain = x => JSON.parse(JSON.stringify(x));
export const list = (root, rel) => { try { return readdirSync(join(root, rel)).sort(); } catch { return []; } };
export function openAnalysis(root, rel, doc) {
  const a = c.openFrom(readJson(root, rel), keys.device.encryption.privateKey, x => c.validateAnalysis(x, doc));
  c.verifySignature(a, keys.server.signing.publicKey); return plain(a);
}
export function openReport(root, reportDate, revision) {
  const r = c.openFrom(readJson(root, c.paths.report(DEVICE.deviceId, reportDate, revision)), keys.device.encryption.privateKey, c.validateReport);
  c.verifySignature(r, keys.server.signing.publicKey); return plain(r);
}
