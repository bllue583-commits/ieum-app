// Minimal Anthropic Messages API client for the pipeline server (global fetch, zero dependencies).
// Structured outputs: output_config.format = {type: 'json_schema', schema} (no beta header).
// Retries 429 / 5xx / overloaded with backoff, at most `maxAttempts` requests, each with a timeout.
import {Held} from './contract.mjs';

export const MESSAGES_URL = 'https://api.anthropic.com/v1/messages';
export const API_VERSION = '2023-06-01';

const fail = (code, usage) => Object.assign(new Held(code), usage ? {usage} : {});
const int = v => (Number.isSafeInteger(v) && v >= 0 ? v : 0);
const retryAfter = res => { const s = Number(res.headers.get('retry-after')); return Number.isFinite(s) && s > 0 ? Math.min(s, 60) * 1000 : null; };

export function createClaudeClient({apiKey, fetch: fetchImpl = globalThis.fetch, sleep = ms => new Promise(r => setTimeout(r, ms)),
  timeoutMs = 300000, maxAttempts = 3, baseDelayMs = 2000} = {}) {
  if (!apiKey) throw new Held('ANTHROPIC_API_KEY_REQUIRED');
  return {
    async analyse({model, system, user, schema, maxTokens}) {
      const body = JSON.stringify({model, max_tokens: maxTokens, system, messages: [{role: 'user', content: user}],
        output_config: {format: {type: 'json_schema', schema}}});
      let last = 'CLAUDE_UNAVAILABLE', wait = 0;
      for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        if (attempt > 1) await sleep(wait);
        let res, data;
        try {
          res = await fetchImpl(MESSAGES_URL, {method: 'POST', body, signal: AbortSignal.timeout(timeoutMs),
            headers: {'content-type': 'application/json', 'x-api-key': apiKey, 'anthropic-version': API_VERSION}});
          data = await res.json().catch(() => null);
        } catch { last = 'CLAUDE_NETWORK'; wait = baseDelayMs * 2 ** (attempt - 1); continue; }
        if (res.status === 429 || res.status >= 500 || data?.error?.type === 'overloaded_error') {
          last = `CLAUDE_HTTP_${res.status}`; wait = retryAfter(res) ?? baseDelayMs * 2 ** (attempt - 1); continue;
        }
        if (!res.ok || !data) throw fail(`CLAUDE_HTTP_${res.status}`);
        const u = data.usage ?? {};
        const usage = {inputTokens: int(u.input_tokens) + int(u.cache_creation_input_tokens) + int(u.cache_read_input_tokens), outputTokens: int(u.output_tokens)};
        if (data.stop_reason === 'refusal') throw fail('CLAUDE_REFUSAL', usage);
        if (data.stop_reason === 'max_tokens') throw fail('CLAUDE_MAX_TOKENS', usage);
        let output;
        try { output = JSON.parse((data.content ?? []).find(b => b?.type === 'text').text); } catch { throw fail('CLAUDE_OUTPUT_JSON', usage); }
        const requestId = String(res.headers.get('request-id') || data.id || 'unknown').slice(0, 256);
        return {output, requestId, usage, model};
      }
      throw fail(last);
    },
  };
}
