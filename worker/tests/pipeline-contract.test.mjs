import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, join} from 'node:path';
import * as c from '../src/pipeline/contract.mjs';

const dir = join(dirname(fileURLToPath(import.meta.url)), '../../docs/contracts/fixtures');
const load = n => JSON.parse(readFileSync(join(dir, n), 'utf8'));
const keys = load('test-keys.json'), t = load('c1-transcript.json'), a = load('c2-analysis.json'), r = load('c3-report.json'), s = load('c0-status.json');
const clone = x => JSON.parse(JSON.stringify(x));
const code = (fn, expected) => assert.throws(fn, e => e.code === expected, expected);

test('golden fixtures validate and verify', () => {
  c.validateTranscript(t); c.verifySignature(t, keys.device.signing.publicKey);
  c.validateAnalysis(a, t); c.verifySignature(a, keys.server.signing.publicKey);
  c.validateReport(r); c.verifySignature(r, keys.server.signing.publicKey);
  c.validateStatus(s); c.verifySignature(s, keys.device.signing.publicKey);
  c.validateDevice(load('device.json'));
  const d = load('digests.json'); assert.equal(d.c1.digest, t.digest); assert.equal(d.c2.canonicalUnsignedSha256, c.sha(c.canonical(c.unsigned(a))));
});
test('sealed fixtures open only for their recipient and aad', () => {
  assert.equal(c.canonical(c.openFrom(load('c1-transcript.sealed.json'), keys.server.encryption.privateKey, c.validateTranscript, 'c1:' + t.transcriptId)), c.canonical(t));
  assert.equal(c.canonical(c.openFrom(load('c2-analysis.sealed.json'), keys.device.encryption.privateKey, x => c.validateAnalysis(x), 'c2:' + a.analysisId)), c.canonical(a));
  assert.equal(c.canonical(c.openFrom(load('c3-report.sealed.json'), keys.device.encryption.privateKey, c.validateReport)), c.canonical(r));
  code(() => c.openFrom(load('c1-transcript.sealed.json'), keys.device.encryption.privateKey, c.validateTranscript), 'SEALED_RECIPIENT_MISMATCH');
  code(() => c.openFrom(load('c1-transcript.sealed.json'), keys.server.encryption.privateKey, c.validateTranscript, 'c1:other'), 'SEALED_AAD_MISMATCH');
  const bad = load('c2-analysis.sealed.json'); const ct = Buffer.from(bad.ct, 'base64'); ct[3] ^= 1; bad.ct = ct.toString('base64');
  code(() => c.openFrom(bad, keys.device.encryption.privateKey, x => c.validateAnalysis(x)), 'SEALED_AUTH_FAILED');
  const moved = load('c2-analysis.sealed.json'); moved.aad = 'c2:' + '0'.repeat(64);
  code(() => c.openFrom(moved, keys.device.encryption.privateKey, x => c.validateAnalysis(x)), 'SEALED_AUTH_FAILED');
});
test('tampering is rejected', () => {
  const x = clone(t); x.stt.text += '!'; code(() => c.validateTranscript(x), 'TRANSCRIPT_TEXT_BINDING');
  const y = clone(t); y.policy.exclusionChecked = false; code(() => c.validateTranscript(y), 'EXCLUSION_CHECK_REQUIRED');
  const z = clone(a); z.summary = '다른 요약'; code(() => c.validateAnalysis(z, t), 'DIGEST_MISMATCH');
  const w = c.seal({...clone(a), summary: '다른 요약'}, keys.device.signing.privateKey); code(() => c.verifySignature(w, keys.server.signing.publicKey), 'SIGNATURE_INVALID');
  const q = clone(a); q.taskChanges[0].start += 1; code(() => c.validateAnalysis(c.seal(q, keys.server.signing.privateKey), t), 'QUOTE_MISMATCH');
  const done = clone(a); done.taskChanges[1].status = 'done'; code(() => c.validateAnalysis(c.seal(done, keys.server.signing.privateKey), t), 'DONE_REQUIRES_CONFIRMATION');
  const unknown = clone(a); unknown.taskChanges[0].taskId = 'task_elsewhere'; code(() => c.validateAnalysis(c.seal(unknown, keys.server.signing.privateKey), t), 'TASK_UNKNOWN');
  const extra = clone(r); extra.extra = 1; code(() => c.validateReport(extra), 'UNEXPECTED_FIELDS');
  const rng = clone(r); rng.range.fromMs += 1; code(() => c.validateReport(c.seal(rng, keys.server.signing.privateKey)), 'REPORT_RANGE');
});
test('KST report range and quote location', () => {
  assert.deepEqual(c.reportRange('2026-10-30'), {fromMs: Date.parse('2026-10-28T15:00:00Z'), untilMs: Date.parse('2026-10-29T15:00:00Z')});
  assert.deepEqual(c.locateQuote('가😀나다', '나다'), {start: 2, end: 4});
  assert.equal(c.locateQuote('가나다', '라'), null);
});
