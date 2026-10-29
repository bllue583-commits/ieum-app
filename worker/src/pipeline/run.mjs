#!/usr/bin/env node
// CLI for the daily pipeline run (.github/workflows/ieum-daily.yml). Prints one JSON line with counts only.
// Env: INBOX_DIR, IEUM_SERVER_SIGNING_KEY, IEUM_SERVER_ENCRYPTION_KEY, ANTHROPIC_API_KEY,
// optional IEUM_MODEL, IEUM_ACCEPT_DEVICE_ID, IEUM_NOW_MS (tests only).
import {existsSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {runServer, DEFAULT_MODEL} from './server.mjs';
import {createClaudeClient} from './claude.mjs';

const REQUIRED = ['IEUM_SERVER_SIGNING_KEY', 'IEUM_SERVER_ENCRYPTION_KEY', 'ANTHROPIC_API_KEY'];

export async function main(env = process.env, {out = s => process.stdout.write(s + '\n'), claude, fetch} = {}) {
  const missing = REQUIRED.filter(k => !env[k]);
  if (!env.INBOX_DIR || !existsSync(env.INBOX_DIR)) missing.push('INBOX_DIR');
  if (missing.length) {
    out(`::notice title=ieum-daily::pipeline not configured (missing: ${missing.join(', ')}); nothing done`);
    out(JSON.stringify({status: 'not_configured', missing}));
    return 0;
  }
  const model = /^claude-[a-z0-9.-]{1,100}$/.test(env.IEUM_MODEL ?? '') ? env.IEUM_MODEL : DEFAULT_MODEL;
  const nowMs = env.IEUM_NOW_MS ? Number(env.IEUM_NOW_MS) : null;
  try {
    const summary = await runServer({root: env.INBOX_DIR, signingKey: env.IEUM_SERVER_SIGNING_KEY, encryptionKey: env.IEUM_SERVER_ENCRYPTION_KEY, model,
      claude: claude ?? createClaudeClient({apiKey: env.ANTHROPIC_API_KEY, fetch}), now: Number.isSafeInteger(nowMs) ? () => nowMs : Date.now,
      acceptDeviceId: /^[0-9a-f]{64}$/.test(env.IEUM_ACCEPT_DEVICE_ID ?? '') ? env.IEUM_ACCEPT_DEVICE_ID : null});
    if (summary.capReached) out(`::warning title=ieum-daily::cost cap reached (${summary.capReached}); ${summary.awaiting} transcript(s) wait for the next run`);
    if (summary.failed) out(`::warning title=ieum-daily::${summary.failed} transcript(s) failed analysis 3 times and were skipped`);
    out(JSON.stringify(summary));
    return 0;
  } catch (e) {
    // never print messages that could carry content or key material: code only
    const code = typeof e?.code === 'string' && /^[A-Z0-9_]{1,64}$/.test(e.code) ? e.code : 'UNEXPECTED';
    out(`::error title=ieum-daily::pipeline run failed (${code})`);
    out(JSON.stringify({status: 'error', code}));
    return 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) process.exitCode = await main();
