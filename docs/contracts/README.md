# Pipeline contracts C0–C3

Status: FROZEN for parallel development (design revision 2026-10-29). Changes go through the
integration owner only, with both validators and the golden fixtures updated in the same PR.

Reference implementations (must agree byte-for-byte):

- Node: `worker/src/pipeline/contract.mjs` — tests `worker/tests/pipeline-contract.test.mjs`
- Android: `android/app/src/main/java/kr/callbrief/pipeline/PipelineContract.kt` — tests `PipelineContractTest`
- Golden fixtures: `docs/contracts/fixtures/` (regenerate with `node tests/pipeline/make-fixtures.mjs` in `worker/`).
  `test-keys.json` holds TEST-ONLY keys; never use them outside tests.

## Flow

```
phone  --C0 status, C1 transcript (sealed to server)-->  ieum-inbox repo  --GitHub Actions 05:07/05:37 KST-->
       <--C2 analysis, C3 report (sealed to device)----  ieum-inbox repo  <--Claude API (text only)--
```

Audio never leaves the phone. Only C1 text and the business snapshot are sent, encrypted.

## Common rules

- **Canonical JSON**: object keys sorted, ASCII keys, no whitespace, integers only (0 … 2^53−1),
  no floats, strings exact (no normalization). Same as `StrictJson.stringify` and Node `canonical`.
- **Digest**: `sha256hex("ieum-pipeline-v1:" + kind + "\n" + canonical(doc without digest, signature))`,
  kind = `status` | `c1` | `c2` | `c3`.
- **Signature**: ECDSA P-256 / SHA-256 over UTF-8 `kind + "\n" + digest`, DER, base64.
  C0/C1 are signed by the device signing key, C2/C3 by the server signing key.
- **Key id (kid)**: `sha256hex(SPKI DER)` of a P-256 public key (uncompressed point, 91-byte SPKI).
  `deviceId` = kid of the device signing key.
- **Size**: every document ≤ 524 288 bytes canonical.
- **Validators are exact**: unknown or missing keys are rejected (`UNEXPECTED_FIELDS`).

## Sealed envelope `ieum.sealed.v1`

```json
{"schema":"ieum.sealed.v1","kid":"<recipient kid>","epk":"<b64 SPKI ephemeral P-256>","iv":"<b64 12 bytes>","aad":"<string>","ct":"<b64 ciphertext||16-byte tag>"}
```

- shared = ECDH(ephemeral private, recipient public) x-coordinate (32 bytes)
- key = HKDF-SHA256(ikm = shared, salt = sha256(epk DER), info = `"ieum-sealed-v1" 0x00 aad`, 32 bytes)
- AES-256-GCM, AAD = UTF-8 `aad`, plaintext = canonical JSON of the signed document
- aad: C0 `status:<deviceId>:<generatedAtMs>`, C1 `c1:<transcriptId>`, C2 `c2:<analysisId>`, C3 `c3:<reportId>`.
  The reader passes the aad it expects from the file path; after opening, `aadFor(doc)` must match.

## Inbox repository layout (`ieum-inbox`, private)

| Path | Writer | Content |
| --- | --- | --- |
| `devices/<deviceId>.json` | phone | `ieum.device.v1` public keys (plain JSON) |
| `inbox/<deviceId>/status/<generatedAtMs>.sealed.json` | phone | C0 sealed to server |
| `inbox/<deviceId>/transcripts/<transcriptId>.sealed.json` | phone | C1 sealed to server |
| `outbox/<deviceId>/analysis/<transcriptId>-r<revision>.sealed.json` | server | C2 sealed to device |
| `outbox/<deviceId>/reports/<reportDate>-r<revision>.sealed.json` | server | C3 sealed to device |
| `state/` | server | server-only state, sealed to the server |

The phone token can write only this repository. The server runs in `ieum-app` Actions and reads/writes
`ieum-inbox` with its own token. Server public keys are compiled into the app (`BuildConfig`), not read
from the inbox, so a leaked phone token cannot redirect encryption.

## C0 device status `ieum.device-status.v1`

`deviceId, generatedAtMs, pendingTranscription, unknownTime, failedTranscription, lastTranscribedAtMs|null, modelSha256|null, digest, signature`

Uploaded after every device run (at least once after the 23:00 run). The server uses the newest one in
the report `pending` block.

## C1 transcript `ieum.transcript.v1`

| Field | Rule |
| --- | --- |
| transcriptId | `sha256hex(canonical([recordingId, sourceVersion, audioSha256, modelSha256]))` |
| deviceId, createdAtMs | signer kid; creation time |
| source | `recordingId` (identifier), `sourceVersion ≥ 1`, `audioSha256`, `durationMs` (≤ 6 h), `originalStartedAtMs` or null when the call time is unknown |
| stt | `engine "WHISPER_CPP_LOCAL"`, `model`, `modelSha256`, `runtimeSha256`, `language ko|en|auto`, `vadApplied`, `segments [{startMs,endMs,text}]` monotonic, `text` = concatenation of segment texts (≤ 131 072 chars), `textSha256` |
| business | `boardRevision`, `linkedCaseId` or null, `cases` (≤ 50): `caseId, label, summary, revision, tasks (≤ 250): taskId, title, assignee, due (free text or null), status, manualFields ⊆ {title, assignee, due, status}` |
| policy | `epoch`, `consentEpoch`, `exclusionChecked: true` (must be true) |

Same recording + same model ⇒ same transcriptId ⇒ the server ignores duplicates.

## C2 analysis `ieum.analysis.v3`

`analysisId = sha256hex(canonical(["c2", transcriptId, revision]))`. Fields: `transcriptId, deviceId, revision ≥ 1,
baseBoardRevision (= C1 business.boardRevision), targetCaseId (= C1 linkedCaseId), provider {name "ANTHROPIC_API", model, requestId},
createdAtMs, caseSuggestion {caseId|null, confidence high|medium|low, quote, start, end}, summary (≤ 3000), cumulativeSummary (≤ 5000),
taskChanges (≤ 30): {op create|update|unchanged, taskId (null iff create), title, assignee, due YYYY-MM-DD|null, status, quote, start, end},
usage {inputTokens, outputTokens}`.

- `quote` equals the C1 text code points `[start, end)`. Claude returns quotes only; the server locates
  offsets (`locateQuote`) and drops changes whose quote is not in the transcript.
- `status` is never `done`; a model "done" becomes `needs_confirmation`.
- `taskId` must exist in the target case (or the suggested case when `targetCaseId` is null).

App application rules (design: Claude 분석 규칙):

| Change | App behaviour |
| --- | --- |
| summary / cumulativeSummary | auto, unless a user-reviewed summary exists → review |
| create | auto |
| update of an AI-owned task (no manualFields, not done, revision unchanged) | auto |
| update of a user-created or user-edited task | review |
| model claimed done | review (stored as needs_confirmation) |
| `targetCaseId == null` | nothing applied; suggestion shown; apply after the user confirms the case |
| `baseBoardRevision` ≠ current | review |

## C3 daily report `ieum.daily-report.v1`

`reportId = sha256hex(canonical(["c3", deviceId, reportDate, revision]))`, `zone "Asia/Seoul"`,
`range = [D-1 00:00, D 00:00) KST` by call start time, `revision` (1 at 06:00, +1 for each later completion),
`cases [{caseId|null, label, summary, cumulativeSummary, newTasks, changedTasks, needsConfirmation [{taskId|null,title,reason DONE_CLAIMED|MANUAL_TASK|CASE_UNCONFIRMED|DUE_CONFLICT}], transcriptIds}]`,
`pending {untranscribed, awaitingAnalysis, failed, unknownTime, deviceStatusAtMs|null}`,
`weekly {fromMs = untilMs − 7 days, untilMs = range.untilMs, summary}`.

Delivery state (app stored, Kakao attempt, ACK, UNKNOWN) is recorded locally by the app, not in C3.
