# r12-3 보고서 (새벽 정리 분리, 결과 고착 해제, 지연 실측) — feature/r12-v31-r3 d908e9b

- 시그니처 변경 없음. `oldestQueuedMs()`는 몸통만 채움(QUEUED·RUNNING의 가장 오래된 coalesce(queued_ms,updated_ms)).
- CallRecordWork.kt: takeTidy가 FirstPass.done과 무관(밤 21~05시), `tidyAll(s)`=done && !TIDIED_ONCE, 그 밖엔 최근 14일. 계수 RESULTS_APPLY_FAILED·RESULTS_BAD·RESULTS_RECEIVED_NOT_APPLIED(`releaseStuck()`, 받은 지 24시간 넘은 비ANALYZED). 기록별 bad 횟수 `resultBadRecords`(최근 200), 두 번째면 FAILED+RESULT_NOT_APPLIED·받은 표시 해제. `noteLatency()` → LATENCY {p50Min,p90Min,n,atMs}(바뀌었거나 1시간마다). 생성자에 `apply` 주입(기본 db.applyAnalysis).
- CallTranscriptionStore.kt: `queued_ms` ALTER(기존 QUEUED/RUNNING은 updated_ms로), offer가 새로 넣거나 SKIPPED→QUEUED일 때만 씀. FAILED 전환을 TRANSCRIPTION_FAILED로 셈(생성자 3번째 인자 `failed:(Int)->Unit`, 기본값 있음).
- CallRecordStore.kt: `// ---- r12-3 ----` 묶음에 `fun resultLatencyMinutes(sinceMs:Long):List<Long>` 하나.
- tidy.md: "firstPassDone false면 중단" 삭제, 처음 정리 중엔 최근 14일·tidiedOnce 안 씀(3·7·9번). hourly.md 자가 보고 끝에 cron 한 줄.
- 시험: R12ThreeTest 5건, RoutineDocTest·TidyTest·CallInboxTest·FirstPassTest 맞춤. 로컬 r12-3-test-3 전체 :app:testDebugUnitTest BUILD SUCCESSFUL(539, 실패 0, 건너뜀 10). check_ui_rules 0, scan_export 0.
- 설계와 다르게: (1) 첫 bad 뒤 즉시 재전송하지 않고 24시간 뒤 releaseStuck이 풂 (2) 두 번째 bad로 FAILED면 받은 표시도 풂 (3) 3회 실패 파일의 RESULT_FAILS 계수 지움 (4) 지연 표본은 통화만, 음수는 0분 (5) oldestQueuedMs에 RUNNING 포함.
- 남은 것: StatusLights.kt:151 대기 나이를 oldestQueuedMs로(r12-1). `sawNew` 한 줄(오케스트레이터): AutomaticRecordingDiscovery.kt 106행 `if(!cancelled())runCatching{...CallTranscriptionWork.enqueue(context)}` → `if(sawNew&&!cancelled())...`.
- r12-5: FAILED+RESULT_NOT_APPLIED에 [다시 정리하기] → `setState(id,TRANSCRIBED)` 또는 `requeueAll()`.
- 배포 시: tidy.md, hourly.md 전문을 각 루틴에 다시 붙여넣기. last-run.json에 `cron` 문자열 필드.
