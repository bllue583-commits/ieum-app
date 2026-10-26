# KST morning report runtime — implementation handoff

Base: `67fac72ccb8ad63655b281c31dcaaae4680511f9`. Branch: `feature/morning-report-runtime`.
Read alongside the source-audited contracts in the design branch's `docs/MILESTONES.md`
(commit `8943536defc15fd0c68f9ef293a4b6b9bd0f3176`). This branch does not merge that branch
or change the frozen proposal/acceptance fixtures in `docs/plans/`.

## Implemented behavior

- `MorningReportWindow` fixes the reporting calendar to `Asia/Seoul`. Report day D has daily
  `[D-1 00:00, D 00:00)` and rolling seven-day `[D-7 00:00, D 00:00)` windows, due at D 06:00.
  Weekly input queries the source directly; it does not concatenate daily report texts.
- Existing `DailyReportSchedule`/Worker/15-minute heartbeat and boot/time/package receivers now
  also reserve unique KST 23:00 and 06:00 targets. Existing device-zone reviewed daily reports
  and existing automatic local notifications retain their behavior and approval meanings.
- KST 23h primary, pre-report and 15-minute late-result reconciliation create persisted incremental
  sync requests. An unfinished asynchronous request retains its original ID, window and prior cursor.
  Backlog extends the requested lower bound. `ACCEPTED` never advances the durable ingest cursor.
  Only `COMPLETE` with a nonempty committed cursor and `durable=true` can commit it, atomically
  with sync status and the observed last-success timestamp. The port must establish actual durability.
- Catch-up starts at the most recent due report day on initial opt-in, then retains a durable report-day
  cursor. A run processes at most seven overdue days, oldest first, and advances only after both kinds
  have been saved or previously cancelled. Failures retain the day for retry. No 30-day backlog is truncated.
- Each report key binds profile partition + KST + report day + kind. Only a changed visible snapshot
  fingerprint creates a new immutable revision and outbox row in one protected transaction. Cursor-only
  changes do not create revisions. Both previous content and delivery states remain in history.
- Last seven report days are automatically refreshed for late results; an explicit manual refresh can
  also reevaluate an older existing report. Late data retains its original occurrence date. Inputs with
  unknown occurrence times remain unresolved and are never assigned an invented date.
- Known-empty complete coverage and zero local data with unknown/partial coverage have different text.
  Pending, failed, review-required, connection-required and unknown-time counts remain explicit.
- Offline, Doze and battery restrictions defer sync while local generation continues as a partial report.
  Generation errors have a persistent three-attempt budget and 30/60/120-second backoff. Retry eligibility
  uses monotonic elapsed time within a boot; after reboot it uses the persisted wall deadline. WorkManager
  and the heartbeat determine when another attempt actually runs.
- Durable runtime/outbox leases use boot count + elapsed time. Competing ledger connections cannot claim
  the same runtime or outbox. Process death or reboot after a delivery claim produces `UNKNOWN` on the next
  reconciliation, with no blind automatic resend. Generation changes cancel old work and fence late commits.
  An in-flight cancellation remains uncertain; an ACKed or dry-run history is preserved.
- The outbox distinguishes ready, in-flight, dry-run, retry, permanent failure, unknown, ACK and cancellation.
  A simulated/future ACK must match exact idempotency key, destination and body hash, with a nonempty receipt.
  ACKed revisions cannot be manually retried. UNKNOWN requires an explicit duplicate-risk acknowledgement
  in the repository API; the current UI never enables that acknowledgement or a live resend.
- Immediately before adapter invocation the current visible source fingerprint is checked again. A changed
  or unreadable source cancels that queued revision before invocation. Current owner/epoch/generation are
  checked at worker admission, within repository mutation fences, and around external-port boundaries.
- The existing report screen exposes KST report/sync/outbox state, actual observation time and manual refresh.
  It rechecks current source/policy before revealing a saved body and hides drifted content while retaining
  immutable history. The activity's existing secure-window behavior remains in force.

## Actual adapters and current limitations

`CanonicalMorningSnapshotPort` is a real read adapter over `CanonicalReviewedDailySource`: current canonical
ACCEPT or valid USER_REVIEW_SUMMARY receipts, manual status/revision, exclusions/archive and source availability
are reused. It never writes the source DB. `APPLIED_SAFE_DERIVED` or a model's own verified flag is not a new
report approval. The current adapter covers local reviewed calls only, so it always declares PARTIAL (or UNKNOWN
on inventory failure); it does not claim comprehensive SMS/Naver coverage.

The runtime's actual sync adapter is explicitly `UnavailableMorningSync`. Request scheduling, durable checkpoint,
retry and cancellation machinery are implemented; dispatch into production recording/context/SMS/Naver collection
is an integration dependency. The app truthfully shows `UNAVAILABLE` rather than claiming a real synchronization.
The synthetic demo uses a labeled test double for durable completion. The candidate-only contract in
`feature/context-reconciliation` commit `4b9921d2d64a4ddccceac378226fd51502f212cf` was also read;
its `CandidateJson` is not consumed as canonical input here. Wire the production adapter after the parent
integrates recording → context storage → report snapshot/incremental in that order.

`MorningReports.run` hard-wires `MorningDryRun`, destination `local-preview`. No Kakao SDK, account authorization,
remote message or new permission is invoked by this runtime. `DRY_RUN_RECORDED` means local rendering only.
The pre-existing `KakaoDailyReportSender` remains DISABLED. Simulated ACK tests are not provider or recipient evidence.

Android WorkManager target times are earliest desired execution times, not exact alarm promises. Doze, quotas,
battery restrictions, force-stop, OEM policy and device downtime can delay execution. Force-stopped apps may need
an explicit launch before work resumes. Boot/time receivers and app ensure reconcile only when the OS allows it.
See the [official WorkManager timing and backoff documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work). No exact-alarm permission, foreground service, battery exemption or security change was added. Actual S25/AVD
reboot/Doze execution and real 23h/06h operational timing remain NOT_RUN.

The protected store's existing 16 MiB snapshot/row limits remain; history is not automatically deleted. A capacity,
key or seal failure preserves prior data and requires separate storage review. No migration of legacy v1 reports
is attempted; their existing hold remains. New opt-in does not silently backfill dates before its initial due day.

## Integration contract / file ownership

New report-owned files: `MorningReportContract.kt`, `MorningReportRepository.kt`, `MorningReportRuntime.kt`,
`MorningReportRuntimeTest.kt`, `ShadowMorningReportCipher.java`, this document and `tools/demo_morning_report.py`.
Small owned connection changes: `DailyReportLedger.kt`, `DailyReportSchedule.kt`, `DailyReportActivity.kt`,
`DailyReportCanonicalInputTest.kt`, `DailyReportWorkerRecoveryTest.kt`.

No changes to `AndroidAutomationStore`, `RecordingWorkflowStore`, `AndroidAutomationRuntime`, `GeneralLocalStore`,
`SecureSettings`, shared SQL, Gradle/module versions, manifest, original clone, model, SDK, AVD or signing keys.
The additive `morning_*` tables live inside the existing account-scoped sealed DailyReportLedger DB. The old report
schema is unchanged. Its payload-key presence check also recognizes new morning history to prevent key replacement.

Actual internal APIs are in `MorningReportContract.kt`:

| Port | Input / output | Integration obligation |
| --- | --- | --- |
| `MorningSnapshotPort.snapshot` | KST half-open window → `MorningSnapshot(DailyReportInput, coverage, counts, scopeNote)` | Read one consistent current allowed snapshot; original occurrence filter and owner/policy/revision/receipt fencing; never manufacture canonical approval |
| `MorningIncrementalSyncPort.sync` | Stable request ID, window, durable cursor, reason, generation → state/committed cursor/durable flag | Idempotent request and asynchronous reconciliation using the same ID; commit source data through the existing owner/job/lease/accept gates before reporting COMPLETE |
| `MorningDeliveryPort.deliver` | Immutable envelope, key, revision, generation, opaque destination, body hash | Production remains dry-run; any future live approval must add current destination/account/policy checks and provider reconciliation separately |

For the parent-owned source integration, construct `MorningReportCoordinator` with `ledger.morning(current)`,
`MorningSnapshotPort`, `MorningIncrementalSyncPort`, `MorningDryRun`, environment and current-owner predicate.
Do not put tokens in either DTO and do not let this adapter bypass Store.accept. A previously accepted request that
later completes must return its original stable request ID/cursor; its result is fenced again before checkpoint commit.

The UI initially offers existing daily-report opt-in; that same durable setting controls KST work. Disable is persisted
before OS cancellation. Existing automatic local notification copies and reviewed user-send reports are separate
histories, not approvals to send the new aggregate. Parent integration must retain that distinction.

## Local demo and verification

Use the README's already prepared JDK17, SDK36/build-tools35, Gradle and offline dependency cache; no installs
are performed. Prepare the public Robolectric JAR with `tools/prepare_host_tests.py`. Then:

```sh
python3 tools/demo_morning_report.py --log morning-demo-unique.log
```

The runnable demo exercises the actual Kotlin coordinator, sealed report repository and outbox under Robolectric
with synthetic source facts, mock sync completion and zero actual external sends. It starts offline/Doze three hours
late, saves partial daily/week reports, reconnects with a late revised fact, and saves r2 while preserving r1 dry-run
history. Output: `android/app/build/reports/morning-demo.txt`. This is a host runtime demo, not an OS/physical-device demo.

Focused regression command:

```sh
IEUM_SKIP_ANDROID_NATIVE=1 python3 run_gradle.py --offline --log morning-focused-unique.log \
  :app:testDebugUnitTest \
  --tests kr.callbrief.MorningReportRuntimeTest \
  --tests kr.callbrief.DailyReportWorkerRecoveryTest \
  --tests kr.callbrief.DailyReportSchedulerTest \
  --tests kr.callbrief.DailyReportCanonicalInputTest \
  --tests kr.callbrief.PartialDailyReportIntegrationTest \
  --tests kr.callbrief.DailyReportLegacyPreservationTest :app:lintDebug
```

2026-10-26 Mac execution record (existing JDK17/SDK36/Gradle8.13/public offline caches):

| Run | Observed result |
| --- | --- |
| Initial build preparation | Sandbox socket restriction, missing/mispointed offline cache, then three Kotlin syntax errors; corrected locally with existing public caches, no SDK/model/key installation |
| First runnable focused run (`morning-focused-4.log`) | 114 tests: 111 PASS, 3 failures. Two synthetic fixture expectations were corrected (retry deadline and a newly enabled kind); the actual Worker test received a test-only JCE Keystore shadow because it now creates encrypted morning payloads |
| Focused correction (`morning-focused-5.log`) | 118 PASS / failures0 / skipped0 |
| Additional checkpoint/source-fence focused run (`morning-final-focused.log`) | 121 PASS / failures0 / skipped0; lint errors0 / warnings35 |
| Full JVM regression (`morning-full-regression.log`) | core 27 PASS; app 1068 total, 1062 PASS / failures0 / errors0 / skipped6. This run preceded the final small backward-clock/deferred-cancel/UTF8-size guards |
| Final guards + compatibility (`morning-clock-fence-regression.log`) | 145 PASS / failures0 / errors0 / skipped0, including 29 new MorningReportRuntimeTest cases. Final lint errors0 / warnings35; no warning locations in new Morning files |
| Runnable CLI demo (`morning-demo-executed.log`) | CLI executed successfully: 1 host test PASS / failures0 / skipped0; daily+weekly r1 partial → r2 complete under mock sync, four DRY_RUN_RECORDED history rows, actual external sends0 |

The full run's six SKIPs are the four existing T81–T84 tests requiring externally prepared historical synthetic
WAV, and P3302/P3303 requiring the existing local STT engine pin. Those inputs were not read or substituted.
Final source changes after the full run were revalidated in the focused 145-test run; no claim is made that the
full 1068 suite was rerun after those guards. Counts are separate runs, not additive unique test counts.

Ignored local evidence: `evidence/morning-full-results.json`, `evidence/morning-full-app-xml/`,
`evidence/morning-full-core-xml/`, `evidence/morning-final-focused-results.json`,
`evidence/morning-final-focused-xml/`, the named logs, lint XML/HTML and `android/app/build/reports/morning-demo.txt`.
The focused summary preserves final source SHA256 values; the full summary identifies its earlier staged tree.

Frozen fixture validators independently passed context12/report18; these are not the 30/40 product acceptance
cases, which remain NOT_RUN. Export scan of the staged 384-file tree found0; diff whitespace check passed.
No device/AVD/S25, actual microphone/recordings/messages/mail/contacts, real OAuth, service send, actual Kakao ACK,
JNI STT, Windows or APK signing/install verification was performed. The local demo/test double does not close
those operational acceptance gates.
