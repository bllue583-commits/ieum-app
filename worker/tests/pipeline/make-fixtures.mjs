// Regenerates docs/contracts/fixtures. Keys here are TEST-ONLY and never used in production.
// Run: node tests/pipeline/make-fixtures.mjs  (from worker/). Output is committed; signatures
// and envelopes are randomized, so re-running changes bytes but not validity.
import {writeFileSync, mkdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, join} from 'node:path';
import * as c from '../../src/pipeline/contract.mjs';

const out = join(dirname(fileURLToPath(import.meta.url)), '../../../docs/contracts/fixtures');
mkdirSync(out, {recursive: true});
const keys = {note: 'TEST_ONLY_KEYS_PUBLIC_IN_REPOSITORY', device: {signing: c.newKeyPair(), encryption: c.newKeyPair()}, server: {signing: c.newKeyPair(), encryption: c.newKeyPair()}};
const deviceId = c.kid(keys.device.signing.publicKey);
const text = '안녕하세요, 공개 합성 시험 통화입니다. 견적서는 금요일까지 보내주세요. 계약서 초안도 확인 부탁드립니다.';
const segments = [{startMs: 0, endMs: 2400, text: '안녕하세요, 공개 합성 시험 통화입니다.'}, {startMs: 2400, endMs: 5200, text: ' 견적서는 금요일까지 보내주세요.'}, {startMs: 5200, endMs: 8000, text: ' 계약서 초안도 확인 부탁드립니다.'}];
const modelSha256 = '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2';
const audioSha256 = c.sha('public-synthetic-fixture-audio');
const source = {recordingId: 'public_fixture_1', sourceVersion: 1, audioSha256, durationMs: 8000, originalStartedAtMs: 1793260800000};
const transcript = c.seal({schema: c.SCHEMA.transcript, transcriptId: c.transcriptIdOf(source.recordingId, 1, audioSha256, modelSha256), deviceId, createdAtMs: 1793264400000, source,
  stt: {engine: 'WHISPER_CPP_LOCAL', model: 'ggml-large-v3-turbo-q5_0', modelSha256, runtimeSha256: c.sha('public-fixture-runtime'), language: 'ko', vadApplied: false, segments, text, textSha256: c.sha(Buffer.from(text))},
  business: {boardRevision: 7, linkedCaseId: 'case_public_1', cases: [{caseId: 'case_public_1', label: '공개 시험 업무', summary: '지난 통화: 견적 요청 접수', revision: 3,
    tasks: [{taskId: 'task_quote', title: '견적서 발송', assignee: '확인 필요', due: null, status: 'open', manualFields: []}, {taskId: 'task_manual', title: '현장 방문', assignee: '', due: '2026-11-03', status: 'done', manualFields: ['status']}]}]},
  policy: {epoch: 2, consentEpoch: 1, exclusionChecked: true}}, keys.device.signing.privateKey);
c.validateTranscript(transcript); c.verifySignature(transcript, keys.device.signing.publicKey);
const q = s => ({quote: s, ...c.locateQuote(text, s)});
const analysis = c.seal({schema: c.SCHEMA.analysis, analysisId: c.analysisIdOf(transcript.transcriptId, 1), transcriptId: transcript.transcriptId, deviceId, revision: 1, baseBoardRevision: 7, targetCaseId: 'case_public_1',
  provider: {name: 'ANTHROPIC_API', model: 'claude-sonnet-5-5', requestId: 'fixture_request'}, createdAtMs: 1793300820000,
  caseSuggestion: {caseId: 'case_public_1', confidence: 'high', ...q('견적서는 금요일까지 보내주세요.')},
  summary: '견적서 금요일까지 발송 요청, 계약서 초안 확인 요청.', cumulativeSummary: '지난 통화: 견적 요청 접수\n견적서 금요일까지 발송 요청, 계약서 초안 확인 요청.',
  taskChanges: [{op: 'update', taskId: 'task_quote', title: '견적서 발송', assignee: '확인 필요', due: '2026-10-30', status: 'open', ...q('견적서는 금요일까지 보내주세요.')},
    {op: 'create', taskId: null, title: '계약서 초안 확인', assignee: '확인 필요', due: null, status: 'needs_confirmation', ...q('계약서 초안도 확인 부탁드립니다.')}],
  usage: {inputTokens: 1200, outputTokens: 300}}, keys.server.signing.privateKey);
c.validateAnalysis(analysis, transcript);
const reportDate = '2026-10-30', range = c.reportRange(reportDate);
const report = c.seal({schema: c.SCHEMA.report, reportId: c.reportIdOf(deviceId, reportDate, 1), deviceId, reportDate, zone: 'Asia/Seoul', range, revision: 1, generatedAtMs: 1793306820000,
  cases: [{caseId: 'case_public_1', label: '공개 시험 업무', summary: analysis.summary, cumulativeSummary: analysis.cumulativeSummary,
    newTasks: [{title: '계약서 초안 확인', due: null, status: 'needs_confirmation'}], changedTasks: [{taskId: 'task_quote', title: '견적서 발송', due: '2026-10-30', status: 'open'}],
    needsConfirmation: [{taskId: null, title: '계약서 초안 확인', reason: 'DONE_CLAIMED'}], transcriptIds: [transcript.transcriptId]}],
  pending: {untranscribed: 0, awaitingAnalysis: 0, failed: 0, unknownTime: 0, deviceStatusAtMs: 1793300400000},
  weekly: {fromMs: range.untilMs - 7 * 86400000, untilMs: range.untilMs, summary: '공개 시험 업무: 견적·계약 진행.'}}, keys.server.signing.privateKey);
c.validateReport(report);
const status = c.seal({schema: c.SCHEMA.status, deviceId, generatedAtMs: 1793300400000, pendingTranscription: 0, unknownTime: 0, failedTranscription: 0, lastTranscribedAtMs: 1793264400000, modelSha256}, keys.device.signing.privateKey);
c.validateStatus(status);
const device = {schema: c.SCHEMA.device, deviceId, signingKey: keys.device.signing.publicKey, encryptionKey: keys.device.encryption.publicKey, label: 'public fixture device', registeredAtMs: 1793260000000};
c.validateDevice(device);
const write = (name, x) => writeFileSync(join(out, name), JSON.stringify(x, null, 2) + '\n');
write('test-keys.json', keys); write('c0-status.json', status); write('c1-transcript.json', transcript); write('c2-analysis.json', analysis); write('c3-report.json', report); write('device.json', device);
write('c1-transcript.sealed.json', c.sealTo(transcript, keys.server.encryption.publicKey));
write('c2-analysis.sealed.json', c.sealTo(analysis, keys.device.encryption.publicKey));
write('c3-report.sealed.json', c.sealTo(report, keys.device.encryption.publicKey));
write('c0-status.sealed.json', c.sealTo(status, keys.server.encryption.publicKey));
write('digests.json', Object.fromEntries([['c0', status], ['c1', transcript], ['c2', analysis], ['c3', report]].map(([k, x]) => [k, {digest: x.digest, canonicalUnsignedSha256: c.sha(c.canonical(c.unsigned(x)))}])));
console.log('fixtures written to', out);
