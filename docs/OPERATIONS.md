# 이음 운영 안내

평소에 돌아가는 것, 확인하는 법, 문제가 생겼을 때 볼 곳을 한 장에 모았다. 설계 근거는 [DESIGN.md](DESIGN.md)(현재 설계도)와 그 전 설계도(Claude Docs)의 "장부의 원본", "새벽 정리", B절(감시), H절(배포 안전)이다. 11/6 우편함과 코드로 대조했다. 이 파일에는 비밀 값, 토큰, 개인 경로를 쓰지 않는다.

## 1. 전체 흐름

```
폰: 통화 녹음 발견 → 폰 안에서 자막 → 우편함 inbox/ 에 요청 (녹음은 폰 밖으로 안 나감)
예약 작업(매시간): 요청 분석 → results/ 에 결과, ledger/ledger.json 장부 고침, status/last-run-hourly.json 자가 보고 → 워치독(루틴 안)
폰(결과 받기: 루틴 cron의 다음 예정 실행 +3, +8, +15분, 그리고 매시간 틱): 장부·story/today.json·app/version.json 받기 → 결과 적용 후 결과 파일 지움
폰(매시간 틱): 새 요청 올림 → status/phone.json 하트비트 → status/calllog.txt
폰(밤 21:00~04:59, 하루 한 번): tidy/state.json 올림(처음 정리 중이면 최근 14일만)
예약 작업(새벽 04:46): 장부 정리 + tidy/proposal.json 제안 + report/YYYY-MM-DD.json 아침 보고서 → 폰이 받아 체크할 일에 반영 → 워치독(루틴 안)
폰(05:30, 없으면 05:35·05:45·06:00, 09:00까지): report/<오늘>.json 받기 → 카카오톡 "나와의 채팅"으로 전송(v3.5) → 06:00 앱 알림
```

우편함은 비공개 저장소 `bllue583-commits/ieum-inbox`이다. 앱 저장소는 `bllue583-commits/ieum-app`이다.

## 2. 예약 작업 세 개 (11/10 확인 — 세 작업 모두 정상 실행 중; 구성은 11/6 기준, 알림 켬(push), 승인 방식 자동. 매시간·새벽 작업은 11/6 새벽 Opus 세션이 ieum-app·ieum-inbox 둘 다 붙여 다시 만들었고, 07:36 실행이 `repoOk=true`로 우편함에 push한 것으로 연결이 확인됨)

| 이름 | 시각(한국) | 모델 | 지시문 원본 | 기억 파일 | 하는 일 |
|---|---|---|---|---|---|
| 이음 통화 분석 (매시간) | 07:34~22:34 매시 34분(하루 16번) | Sonnet 5.5 | `docs/routines/hourly.md`(v8)의 "---" 아래 | `status/last-run-hourly.json` | inbox 요청 분석(한 실행 20건까지), 결과 쓰기, 장부 고치기, 자가 보고, 워치독, 세 줄 보고 |
| 이음 새벽 정리 (매일) | 04:46(v3.5, 전에는 05:16) | Opus 5.5 | `docs/routines/tidy.md`(v7)의 "---" 아래 | `tidy/project.md`, `tidy/history/YYYY-MM.md`, `tidy/briefing.md`, 자가 보고 `status/last-run-tidy.json`(`reportWritten`) | 진행 중인 일 묶기, 팔로업 제안, 중복 합치기, 아침 보고서 `report/YYYY-MM-DD.json`(9-1번, `tools/report_check.py` 통과 뒤 커밋, 30일 지난 것은 지움), 현황 파일과 다섯 줄 알림, 워치독. 폰이 밤에 올린 `tidy/state.json`(24시간 안)이 있어야 일을 한다. v3.1부터 폰이 처음 정리 중이어도 최근 14일 기록을 올리므로 처음 정리를 기다리지 않는다(state가 없거나 오래됐으면 "정리할 것 없음: 이유"가 정상, 같은 날 이미 "tidy" 커밋이 있으면 "이미 정리됨") |
| 이음 통화 목록 검증 (매일) | 09:00 | Opus 5.5 | 예약 작업 프롬프트가 원본 | `status/verify-log.md`(숫자만) | 폰 통화기록(`status/calllog.txt`)과 저장·분석 결과 대조, 검증 파일과 다섯 줄 알림. 프롬프트는 아직 옛 `status/last-run.json`을 읽는다(새 루틴별 파일로 바꾸지 않았음) |

- **v3.7에서 바뀔 시각표(DESIGN_V7 2.2, 배포와 같은 시각에 적용):** 매시간 분석 cron `34 7-22` → `34 3-4,7-22`(밤에 만든 자막을 아침 보고 전에 분석), 새벽 정리 04:46 → 05:05, 카톡 05:30은 그대로. 지시문 안 cron 문자열과 자가 보고 `cron`도 같이 바꾼다(폰이 그 값으로 결과 받는 시각을 정함).
- 매시간·새벽 예약 작업 프롬프트(11/6부터)는 짧은 안내다: ieum-inbox를 pull(실패하면 "[이음 분석 HH:MM] 확인 필요: 저장소 접근", 새벽은 "[이음 새벽 정리 MM/DD HH:MM] 확인 필요: 저장소 접근"만 답함) → 우편함의 `routines/hourly.md`(또는 `routines/tidy.md`)를 Read로 끝까지 읽고 첫 "---" 아래를 지시문으로 따른다. cron은 매시간 `CRON_TZ=Asia/Seoul 34 7-22 * * *`, 새벽 `CRON_TZ=Asia/Seoul 46 4 * * *`, 검증 `CRON_TZ=Asia/Seoul 0 9 * * *`. ID는 매시간 `trig_016vQxos9z6p1pdPJ28MjrsV`, 새벽 `trig_01Sp6piuvasw52ZR1KPupumB`, 검증 `trig_01KyuDNZWkmrQpJj8e8cr661`.
- 지시문을 바꿀 때: 이 저장소의 원본 파일(`docs/routines/*.md`)을 고치고 배포한다. release-apk가 version.json보다 먼저 우편함 `routines/`와 `tools/watchdog.py`에 바뀐 것만 올린다. 배포 없이 급히 바꿀 때만 우편함 `routines/`에 직접 커밋한다. 매시간 지시문의 [분석 규칙]과 [결과 형식]은 앱 코드(`ClaudeCallAnalyzer.SYSTEM`, `SCHEMA`)와 같아야 하며 `RoutineDocTest`가 검사한다. 규칙을 바꾸면 `ClaudeCallAnalyzer.PROMPT_VERSION`과 지시문 버전을 함께 올린다.
- 실행 확인: claude.ai의 예약 작업 화면에서 실행 기록을 본다. 실행 로그에는 통화 내용이 남지 않게 지시돼 있다. 빈 실행은 "새 기록 없음" 한 줄로 끝나는 것이 정상이다.
- 우편함에서 확인: 매시간 작업이 처리하면 커밋 "analyze N calls", 새벽 정리는 "tidy"가 생기고, 어느 경우든(할 일이 없었어도) 그 뒤에 자가 보고만 담은 "status" 커밋이 따로 생긴다(매시간 `status/last-run-hourly.json`, 새벽 `status/last-run-tidy.json`). 루틴이 저장소에 닿지 못하면 처리하지 않고 "확인 필요: 저장소 접근"으로 답하고 끝난다. 예약 작업 알림은 "확인 필요" 답일 때만 의미가 있다.
- 종단 시험: 앱 저장소의 `inbox-e2e`를 수동 실행(또는 그 파일 push)하면 `inbox/selftest-<번호>.json`을 넣고, 매시 40분(07:40~23:40)에 결과를 폰과 같은 파서로 검증한 뒤 지운다. 예약 작업은 이 요청으로 장부를 고치지 않고, 폰은 `selftest-` 결과를 무시한다.
- 두 작업과 개발 세션은 같은 Claude 구독 한도를 쓴다. 한도에 걸리면 실행이 몇 초 만에 실패하고, 한도가 풀린 뒤 다음 실행이 밀린 요청을 이어서 처리한다.

## 3. 우편함 폴더 구조 (ieum-inbox 기본 브랜치)

| 경로 | 쓰는 쪽 | 내용 |
|---|---|---|
| `inbox/<해시 32자>.json` | 폰 | 새 기록 하나의 분석 요청(통화, 문자, 메일). 이름에 번호나 이름이 없다 |
| `inbox/b-<시각>-<해시>.json` | 폰 | 처음 정리(10월 1일부터의 지난 기록) 묶음 요청. 매시간 2개까지 처리 |
| `inbox/selftest-<번호>.json` | CI `inbox-e2e` | 종단 시험 요청. 결과만 쓰고 장부는 고치지 않는다 |
| `results/<같은 이름>.json` | 예약 작업 | 분석 결과. 폰이 적용한 뒤 지운다. `selftest-`는 CI가 검증 후 지운다 |
| `bad/` | 양쪽 | 형식이 깨진 요청이나 세 번 적용에 실패한 결과를 치워 둔 곳 |
| `ledger/ledger.json` | 예약 작업 | 장부(원본). rev가 오르면 폰이 받아 반영 |
| `ledger/edits/*.json` | 폰 | 사용자가 앱에서 고친 이름 등. 예약 작업이 장부에 반영하고 지운다 |
| `tidy/state.json`, `tidy/proposal.json` | 폰 / 새벽 정리 | 새벽 정리의 입력과 제안 |
| `tidy/project.md`, `tidy/history/YYYY-MM.md`, `tidy/briefing.md` | 새벽 정리 | 새벽 정리의 기억(공사 전체, 날짜별 일지, 오늘 현황판) |
| `report/YYYY-MM-DD.json` | 새벽 정리(v3.5) | 아침 보고서(한국 시각 오늘). 폰이 05:30부터 ETag로 읽어 카카오톡으로 보낸다. 이름·업체·요약이 들어 있으니 내용은 옮기지 않는다 |
| `story/today.json` | 매시간 분석 | 오늘 통화 이야기(v3.2, 홈 맨 위) {day, text, calls, atMs}. 폰이 결과 받기 때 가져감 |
| `status/phone.json` | 폰 | 하트비트 |
| `status/calllog.txt` | 폰 | 통화 목록 대조표(이름·번호 포함, 4절) |
| `status/last-run-hourly.json`, `status/last-run-tidy.json` | 예약 작업 | 루틴별 마지막 실행의 자가 보고(v3.4부터) |
| `status/last-run.json` | 예약 작업(옛) | v3.3까지 두 루틴이 함께 쓰던 자가 보고. 지우지 않고 남아 있으며, 새 파일이 없을 때만 워치독과 폰이 읽는다. 11/6 05:16 새벽 정리는 아직 이 파일에 지시문 5로 썼다(`last-run-tidy.json`은 11/7 05:16부터 생길 예정) |
| `status/verify-log.md` | 통화 목록 검증 | 검증 작업의 기억(숫자만) |
| `routines/hourly.md`, `routines/tidy.md` | release-apk | 예약 작업이 읽는 지시문 사본(원본 `docs/routines/`, 바뀐 것만 올림) |
| `tools/watchdog.py`, `tools/report_check.py` | release-apk | 루틴 안 워치독과 아침 보고서 검사기 사본(원본 `tools/inbox/watchdog.py`, `tools/report_check.py`) |
| `app/version.json`, 릴리스 | release-apk | 최신 APK 정보와 파일(최근 3개만 남김). 11/7 21:44부터 versionCode 1049, versionName v3.6, promptVersion "8", tidyVersion "7", certSha256 |
| `README.md` | release-apk | 빈 저장소에 처음 한 번 만드는 안내 한 줄 |

`.github/` 폴더는 없다(Actions 워치독을 지웠음). `bad/`는 11/6 아침 체크아웃에는 없고 필요할 때 생긴다.

우편함 파일을 직접 볼 때도 자막, 문자 원문, 장부 내용을 터미널이나 대화에 옮기지 않는다. 상태 확인은 아래 두 상태 파일과 파일 개수, 시각만으로 한다.

## 4. 상태 파일 읽는 법

### status/phone.json (폰 하트비트)
폰이 매시간 틱 끝과 "문제 알리기"에서 덮어쓴다. 시각은 밀리초 에포크, 이름이나 통화 내용은 없다.

| 키 | 뜻 | 이상 신호 |
|---|---|---|
| `atMs` | 폰이 이 파일을 쓴 시각 | 12시간 넘게 그대로면 폰이 조용함(전원, 네트워크, 절전, 토큰) |
| `appVersion`, `versionCode` | 설치된 앱 버전 | `app/version.json`보다 낮은 상태가 오래가면 업데이트가 안 됨 |
| `queuedCount`, `oldestQueuedMs` | 자막을 기다리는 녹음 수와 가장 오래된 것의 시각 | 6시간 넘게 밀리면 자막이 밀림(충전 중 밤에 따라잡는 것이 보통) |
| `lastTranscribedMs`, `lastResultMs` | 마지막 자막, 마지막 결과 적용 시각 | 결과가 오래 없으면 예약 작업 쪽 확인 |
| `modelState` | 음성 인식 모델 상태 코드 | READY가 아니면 모델 다시 받기 필요 |
| `storageUsedPercent` | 저장 공간 사용률 | |
| `firstPass` | 처음 정리 진행: `done`, `toTranscribe`, `audioMinutesLeft`, `waitingForSummary`, `batchesToday`, `bound`, `blockedBy`(`none`, `waiting_messages`, `waiting_transcripts`, `backoff`) | 처음 정리 중에만 의미 |
| `lastInboxError` | 우편함 오류 코드(있을 때만) | INBOX_HTTP_401/403/404면 토큰 만료나 권한 문제 |
| `lastUpdateError` | 업데이트 설치 오류 코드(있을 때만) | 설치 권한, 저장 공간, 서명 불일치 |
| `consent` | 통화 정리 동의가 켜져 있는가 | false면 정리 요청이 올라가지 않음(앱 설정에서 켜기) |
| `folder` | 녹음 폴더가 골라져 있고 읽기 권한이 살아 있는가 | false면 새 녹음을 못 가져옴(앱에서 폴더 다시 고르기) |
| `notifyAllowed` | 알림 권한과 앱 알림이 켜져 있는가 | false면 저녁·아침 보고와 자가 진단 알림이 안 보임 |
| `batteryFree` | 배터리 최적화 예외인가 | false면 작업이 자주 미뤄짐 |
| `power` (v3.6, DESIGN_V6 3.1) | 자막 전력 장부(`transcriptionPower`, r16-1이 씀)를 숫자·코드로: `batteryPct`, `plugged`(ac, usb, wireless, none), `status`, `powerSave`, `thermal`, `headroom`, `tempDeciC`, `currentUa`, `cpuset`, `cpusAllowed`; `today`·`prev`{`day`(KST "yyyy-MM-dd" 문자열 — 숫자면 번호 누설 검사에 걸림), `battery`{runs, ms, min, audioMin, mAhScreenOff}, `plugged`{runs, ms, min, audioMin}, `budgetMin`(20), `budgetHit`(min, pct, none), `gapSkips`, `hotSkips`, `inCallSkips`, `morningSkips`, `lowSkips`, `powerSaveSkips`, `chargingOnlySkips`, `budgetSkips`, `plugDropped`, `levelFallingSkips`, `killedChunkMs`, `deadlineMs`, `yieldCount`, `yieldMs`, `modelLoads`, `modelLoadMs`, `decodeAudioMs`, `stopReasons`{코드: 수}}; `chunk`{coldMedianMs, p50Ms, lastMs, threads, tokensLast(null, r16-3 전), perTokenMsLast(null)}; `waiting`{n: 충전을 기다리는 녹음 수, oldestMin: 가장 오래 기다린 분}; `chargingOnly`(스위치 [충전 중에만 글자로 바꾸기]). 장부가 없으면 null·0으로 채워 늘 있음 | `today.battery.min` > 60이 2일이면 배터리를 많이 씀(예산 20분), `waiting.oldestMin` > 2160(36시간)이면 충전 대기가 쌓임, `killedChunkMs + deadlineMs`가 실행 ms의 20% 넘음이 2일이면 조각 낭비. `chunk.coldMedianMs` ≤ 280,000, `cpusAllowed` = `threads`가 확인 기준(DESIGN_V6 6절) |
| `standbyBucket` | 앱 대기 버킷: `EXEMPTED`(배터리 제한 없음, 가장 좋음), `ACTIVE`, `WORKING_SET`, `FREQUENT`, `RARE`, `RESTRICTED`, `NEVER`, 그 밖은 `B<번호>`, 못 읽으면 `UNKNOWN`(Android 9 미만은 `NONE`) | `RARE`/`RESTRICTED`/`NEVER`면 작업이 하루 몇 번만 돎. 앱을 한 번 열면 풀림 |
| `lastDiscoveryMs` | 녹음 폴더를 마지막으로 다 살펴본 시각(0: v2.4 전 앱이거나 아직 없음) | 폰은 살아 있는데 12시간 넘게 그대로면 녹음 찾기가 멈춤 |
| `lastUploadMs` | 정리 요청을 우편함에 마지막으로 올린 시각(0: 없음) | 통화가 있었는데 오래 그대로면 동의, 토큰, 네트워크 확인 |
| `pendingAnalysis` | 정리(요약)를 기다리는 통화 수 | 늘기만 하면 예약 작업이나 우편함 쪽 확인 |
| `rescheduledMs` | 하루 1회 자가 복구가 빠진 작업을 다시 등록한 마지막 시각(0: 없음) | 자주 바뀌면 폰이 작업을 지우고 있음(절전, 제조사 정리 앱) |
| `tokenExpiresMs` | 앱에 든 우편함 토큰의 만료 시각. 폰이 우편함 응답 헤더 `github-authentication-token-expiration`에서 읽음(null: 아직 못 봄, 만료 없는 토큰) | 14일 안이면 워치독 이슈. 새 토큰으로 앱을 다시 배포해야 함 |
| `audioChannels`, `audioChannelCorr` | 녹음의 채널 수와 좌우 상관계수(자막 엔진이 기록한 뒤부터, 그 전에는 null) | 상관계수가 0.5 미만이면 좌우가 다른 사람일 수 있음(설계 F1) |
| `callLog` | 통화 목록을 빠짐없이 읽는지 보는 묶음(아래 표). 숫자와 시각만 | `deviceTotal`과 `storedTotal`이 다르면 앱이 빠뜨린 것 |
| `newQueuedCount`, `newOldestQueuedMs`, `oldQueuedCount` (v2.6) | 자막 대기를 새 녹음과 처음 정리 녹음으로 나눈 수, 새 녹음 중 가장 오래 기다린 것이 대기열에 들어온 시각 | 워치독 "자막이 밀려요"는 이 값으로 판정 |
| `lastDiscoveryAttemptMs`, `lastDiscoveryOutcome`, `inventoryTruncated`, `heldUnsupported` (v2.6) | 녹음 찾기 마지막 시도 시각과 결과 코드, 폴더 목록이 잘렸는지, 지원 안 하는 형식으로 보류한 수 | |
| `dbPercent`, `dbRecovery`, `dbOpenMs`, `homeReadMs` | 보호 저장소 사용률(봉인 파일 16MB와 행 수 중 큰 쪽), 저장소 복구 코드, 저장소 열기와 홈 읽기 시간(ms) | `dbPercent` 80 넘으면 워치독 이슈 |
| `updateAvailableCode`, `updateReadySinceMs`, `update.error` (v3.4) | 폰이 알고 있는 새 버전 번호, 받아 둔 뒤 기다린 시작 시각, 업데이트 오류 코드 | 24시간 넘게 설치 안 되면 워치독 이슈 |
| `lastInboxErrorSinceMs` | 같은 우편함 오류 코드가 이어진 시작 시각(오류가 있을 때만) | 2시간 넘으면 워치독 이슈 |
| `resultChain` | 결과 받기 사슬의 지금 회차(`round`, 0이면 끝)와 다음 시각(`nextMs`) | |
| `callLogMatch` | 녹음과 통화기록 짝짓기 통계(숫자만) | |
| `callCard` (v3.0, v3.4) | 통화 카드 횟수: `shown`, `shownOutgoing`, `eligibleOutgoing`, `noCard`, `thin`, `noNumber`, `locked`, `cold`, `fgsRefused`, `noPermission`, `ringToCardMs` 등과 오늘 묶음 `today`. 통화를 본 적 없으면 null | `today.noPermission`이 하루 3번 이상이면 워치독 이슈 |
| `permissions` (v3.1) | `phoneState`, `overlay`, `redirectRole`, `installAllowed` | `phoneState=false` 24시간이면 워치독 이슈 |
| `recordingGap`, `recordingGapVerdict` (v3.1) | 최근 하루 통화와 녹음 파일·연결 수, 판정 코드(`ok`, `noRecording`, `partial`, `noMatch`, `folder`, `tooMany`) | `ok`가 아닌 같은 판정이 2일이면 워치독 이슈 |
| `followup`, `results`, `storeOpenError`, `transcriptionFailed`, `droppedItems`, `duplicateSkipped`, `latency` (v3.1) | 녹음 없음 알림 수, 결과 적용 실패·bad·받고 못 적용한 수, 저장소 열기 오류 코드(있을 때만), 자막 실패 수, 근거 부족으로 버린 수, 중복 건너뜀 수, 통화 끝→결과 지연 분위수 | `latency`는 기록 시각 오염 진단 전까지 판단 근거로 쓰지 않음 |
| `transcription` (v3.2) | 자막 실패 사유별 수, 길이 구간별 대기·완료, `long48h`(100초 넘는 통화 완료율), `oldestWaitMin`, 실행 수 | `long48h.ratePct` 80 미만 24시간이면 워치독 이슈 |
| `golden`, `guard` (v3.3) | 골든 측정 점수, 숫자 가드 계수(숫자만, 측정 전 null) | |
| `messages.mailError`, `recordTime.fileVsRecordMin` (v3.4) | 메일 오류 코드별 수, 기록 시각 차이 구간별 수 | |
| `kakao`, `report` (v3.5) | `kakao{on, linked, sent, failed, lastSentMs, error}`: 스위치 "카카오톡으로 받기", 연결, 다 나간 날 누계, 실패 누계, 마지막으로 다 나간 시각, 오류 코드. `report{day, source}`: 마지막으로 보낸 보고 날짜와 출처(`routine` 새벽 정리 보고서, `phone` 06:00 폰 폴백) | `linked=true`인데 `lastSentMs`가 이틀 아침 연속 오늘이 아니면, 또는 `linked=false`에 `error`가 `TOKEN_*`이면 워치독 이슈 |

v3.1 이전 앱(11/6 아침 폰은 v3.1, 1040)은 v3.2 이후 키(`transcription`, `update`, `messages`, `recordTime`, `golden`, `guard`)를 보내지 않는다. 워치독은 키가 없으면 그 규칙을 건너뛴다.

`callLog` 안:

| 키 | 뜻 |
|---|---|
| `allowed` | 통화 기록 읽기 권한이 있는가(false면 `deviceTotal`, `latestDeviceLogMs`가 null이고 비교하지 않음) |
| `fromMs`, `todayFromMs` | 아래 두 묶음의 시작: 10/1 0시(KST), 오늘 0시(KST) |
| `since`, `today` | 10/1 이후와 오늘의 폰 통화 기록: `total`, `incoming`(받은 전화), `outgoing`(건 전화), `missed`(부재중), `rejected`(거절), `other`(차단·음성사서함 등 나머지), `recorded`(받은/건 통화 중 녹음과 짝지어진 수), `unrecordedCalls`(통화했는데 녹음이 없는 수), `personal`(개인 번호 통화 수). 유형별 수와 `personal`은 폰 통화 기록(CallLog provider)에서, `recorded`와 `unrecordedCalls`는 앱이 저장한 call_log에서 센다 |
| `latestDeviceLogMs`, `latestStoredLogMs` | 폰 통화 기록의 가장 최근 항목 시각, 앱이 저장한 call_log의 가장 최근 시각. 앞이 뒤보다 많이 앞서면 저장이 따라오지 못함(앱 저장은 매시간 우편함 틱에서) |
| `deviceTotal`, `storedTotal` | 10/1 이후 폰 통화 기록 전체 건수(최대 20,000건까지 셈), 앱이 저장한 건수 |
| `mismatchSinceMs` | 두 건수가 처음 달라진 시각(0: 같음). 2시간 넘게 다르면 워치독 이슈 |

이름, 전화번호, 파일 이름, 폴더 경로, 통화 내용은 이 파일에 절대 넣지 않는다(PhoneStatusTest가 확인). `droppedSegments`, `cleaned`는 넣지 않는다.

### status/calllog.txt (통화 목록 대조표)
11/4 사용자 결정(아내 동의)으로 폰이 매시간 틱에서 하트비트 다음에 올린다. 사람이 읽는 UTF-8 텍스트이고 **이름과 번호 전체가 들어 있다**. 그래서 비공개 우편함에만 올리고 앱 화면, 로그, 알림, "문제 알리기"에는 쓰지 않는다. 직전에 올린 것과 내용이 같으면(첫 줄 시각 제외, 해시 비교) 올리지 않는다. 폰 통화 기록(CallLog provider)을 직접 읽고, 앱 쪽은 저장된 call_log와 기록을 읽기만 한다. 사용자가 "기록 안 함"으로 정한 번호(LocalPrivacy)는 이름과 번호 자리가 `(제외 번호)`다.

- 첫 줄: 만든 시각(KST)과 앱 버전. 둘째 줄 요약: 폰 기록 건수, 앱 저장 건수, 빠진 건수(폰에 있고 앱에 없음), 앱에만 있는 건수, 중복 건수(같은 번호·같은 시각(초)·같은 통화 시간이 둘 이상, 또는 한 녹음이 두 통화에 짝지어짐). 요약과 아래 세 구역은 매번 10/1 이후 전체로 센다.
- 구역: `[빠진 통화 (폰에 있고 앱에 없음)]`, `[앱에만 있는 기록]`, `[중복]`, `[최근 48시간]`(매번), `[10/1 이후 전체]`(KST 날짜가 바뀐 뒤 첫 틱에만 채우고, 나머지 시간엔 `(하루 한 번 갱신, 마지막 갱신 YYYY-MM-DD HH:MM)`만. 전체 목록은 그 시각의 커밋 이력에서 본다).
- 한 줄: `MM-DD HH:MM | 종류(받은/건/부재중/거절/기타) | 통화 m분 s초 | 전화부 이름(없으면 -) | 번호 전체 | 앱 저장 O/X | 녹음 O/X | 분석 완료/대기/개인/없음 | 기록 id`. 기록 id는 그 기록의 우편함 파일 이름(inbox/, results/의 해시) 앞 8자라 요청·결과 파일과 바로 맞춰 볼 수 있다(기록 id 원문에는 번호가 들 수 있어 쓰지 않음).
- 이 파일 내용은 대화나 터미널 출력에 옮기지 않는다. 확인은 GitHub 화면이나 예약 작업이 하고, 보고에는 건수만 쓴다.

### status/last-run-hourly.json, status/last-run-tidy.json (예약 작업 자가 보고)
v3.4부터 루틴마다 파일이 하나다(새벽 정리가 매시간 분석의 상태를 덮어쓰지 않게). 한 줄 JSON이고 결과 커밋과 따로 "status" 커밋으로 올라온다. 옛 `status/last-run.json`은 v3.3까지 두 루틴이 함께 쓰던 것으로, 워치독과 폰은 새 파일이 없을 때만 읽는다. 자세한 형식은 `docs/routines/self-report.md`와 각 지시문의 "마지막 단계".

| 키 | 뜻 |
|---|---|
| `routine` | `hourly` 또는 `tidy` |
| `startedMs`, `endedMs` | 실행 시작과 끝 |
| `processed`, `skipped`, `failed`, `batches` | 매시간: 결과를 만든 항목 수, 건너뛴 수, 실패 수, 읽은 요청 파일 수. 새벽: 읽은 기록 수, 건너뛴 수, 실패 수, 제안 항목 수 |
| `model`, `limitHit` | 쓴 모델, 사용 한도 오류를 만났는지 |
| `ledgerRev` | 이번에 쓴 장부 rev |
| `promptVersion`, `promptMismatch` | 지시문 버전, 요청의 버전이 달랐는지(폰 업데이트 전 과도기 버전은 제외) |
| `loggedTranscript` | 항상 false여야 한다 |
| `repoOk`, `pushed` | 시작할 때 저장소를 받았는지(pull), 결과 커밋을 push했는지(결과가 없었으면 true) |
| `leftover` (매시간) | 한 실행 처리 상한 20건 때문에 다음 실행으로 남긴 요청 파일 수 |
| `cron` 또는 `crons` (매시간) | 예약 작업의 실제 cron. 폰이 결과를 가져갈 시각(다음 예정 실행 +3, +8, +15분)을 정하고 워치독이 빠진 실행을 센다 |

읽는 순서: `status/last-run-hourly.json`의 `endedMs`가 직전 예정 슬롯 뒤인가 → `pushed`가 false면 저장소 쓰기 문제 → `limitHit`가 true면 한도 → inbox에 파일이 있는데 `processed`가 0이면 작업이 일을 안 함(`leftover`가 있으면 다음 실행이 이어서 함) → `promptMismatch`가 true면 폰과 지시문 버전이 어긋남. 폰도 `endedMs`를 설정 키 `routineEndedMs`로 받아 신호등 "분석" 줄에 쓴다(예정 실행을 2회 놓치면 주황).

### 워치독 (루틴 안에서 도는 감시)
v3.4(11/6)부터 워치독은 Actions가 아니라 두 예약 작업 안에서 돈다. 매시간 분석과 새벽 정리가 자가 보고를 push한 뒤 마지막 단계로 `python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox --out /tmp/ieum-watchdog-plan.json`을 실행한다. 예전 ieum-inbox의 Actions 워치독(`.github/workflows/watchdog.yml`, 3시간마다)은 비공개 저장소 Actions 결제 실패로 11/5 03:40부터 8회 연속 시작하지 못해 지웠고, 앱 저장소의 사본 `tools/inbox/watchdog.yml`도 지웠다.

원본은 이 저장소의 `tools/inbox/watchdog.py`, 시험은 `test_watchdog.py`(ci가 돌림)다. release-apk가 배포 때 ieum-inbox의 `tools/watchdog.py`로 올린다(11/6 아침 두 파일 같음). 아래 규칙으로 검사해 문제마다 라벨 `ieum-watch` 이슈를 `gh api`(REST)로 하나 만들고, 같은 제목의 열린 이슈가 있으면 댓글도 달지 않으며, 정상으로 돌아오면 "정상으로 돌아왔어요." 댓글과 함께 닫는다. 새 이슈가 있으면 루틴 답 첫 줄이 "확인 필요: 감시 이슈 n건"이 된다. gh 인증이 없는 등 명령이 실패하면 루틴은 그 단계만 건너뛴다. 루틴 자체가 멈추면 워치독도 멈추므로 폰 신호등 "분석" 줄이 둘째 눈이다.

| 이슈 제목 | 조건 |
|---|---|
| 폰이 조용해요 | `status/phone.json`이 없거나 `atMs`가 12시간 넘음. 본문에 마지막 하트비트의 `consent`, `folder`, `batteryFree`, `standbyBucket`(RARE, RESTRICTED, NEVER), `notifyAllowed`, `lastDiscoveryMs`로 짐작한 원인을 붙인다 |
| 녹음 가져오기가 멈췄어요 | 폰은 살아 있는데 마지막으로 끝까지 살펴본 시각(`lastDiscoveryMs`)이 12시간 넘음. `lastDiscoveryAttemptMs`가 있으면(v2.6~) 한 번도 성공 못 한 0도 셈, 없으면 0은 건너뜀 |
| 통화 목록을 빠뜨리고 있어요 | 폰은 살아 있는데 `callLog.deviceTotal`과 `storedTotal`이 다르고 `mismatchSinceMs`가 2시간 넘음(권한 없으면 건너뜀) |
| 자막이 밀려요 | 처음 정리 중(`firstPass.done=false`)이면 남은 자막이 있는데 마지막 자막(`lastTranscribedMs`)이 24시간 넘었을 때만. 아니면 `newQueuedCount`가 있는 하트비트(v2.6~)는 새 녹음의 `newOldestQueuedMs`가, 옛 하트비트는 `oldestQueuedMs`가 6시간 넘음. v3.6부터 충전을 기다리는 녹음(`power.waiting.n`)은 빼고 센다 |
| 새 버전이 설치되지 않아요 | 폰이 받아 둔 새 버전(`updateAvailableCode` > `versionCode`)이 `updateReadySinceMs`부터 24시간 넘게 그대로 |
| 폰 저장 공간이 거의 찼어요 | `dbPercent`가 80 넘음 |
| 우편함 오류가 계속돼요 | 같은 `lastInboxError`가 `lastInboxErrorSinceMs`부터 2시간 넘음 |
| 통화 카드 권한이 꺼져 있어요 | 하트비트 `callCard.today.noPermission`이 하루 3번 이상(잠금이라 알림만 띄운 `locked`는 세지 않음) |
| 업데이트 실패 | `lastUpdateError`가 있음(단 `USER_ACTION_REQUIRED`, `INSTALL_SOURCE_NOT_ALLOWED`는 아래 옛 버전 규칙이 말함) |
| 폰이 옛 버전에 머물러 있어요 | `app/version.json`의 versionCode가 하트비트 `versionCode`보다 높고, version.json이 마지막으로 바뀐 커밋부터 24시간 넘음(하트비트가 12시간 안일 때만) |
| 루틴 지시문이 옛 버전이에요 | 루틴별 자가 보고의 `promptVersion`이 version.json의 `promptVersion`(매시간)·`tidyVersion`(새벽)과 다름. version.json이 바뀐 뒤 끝난 실행만 본다 |
| 전화 상태 권한이 빠졌어요 | 하트비트 `permissions.phoneState=false`가 24시간(v3.1~ 하트비트만) |
| 통화가 녹음되지 않고 있어요 / 통화가 일부만 녹음되고 있어요 / 녹음 폴더를 읽지 못해요 / 녹음 폴더에 파일이 너무 많아요 / 녹음은 있는데 통화기록과 이어지지 않아요 | `recordingGapVerdict`가 `noRecording` / `partial` / `folder` / `tooMany` / `noMatch`로 2일(48시간) 같음 |
| 긴 통화의 자막이 끝나지 않아요 | `transcription.long48h.ratePct`(100초 넘는 통화 완료율)가 80% 아래로 24시간 |
| 분석이 멈췄어요 | inbox/의 가장 오래된 요청이 루틴이 도는 시간(07~23시)으로만 세어 3시간 넘음(파일이 처음 커밋된 시각 기준) |
| 루틴이 일을 안 해요 | 매시간 자가 보고의 `processed`가 0인데 inbox에 요청이 있음(한도에 걸린 실행은 제외) |
| 루틴이 한도에 걸렸어요 | 매시간 자가 보고의 `limitHit`가 true |
| 루틴 지시문 버전이 달라요 | 매시간 자가 보고의 `promptMismatch`가 true |
| 매시간 분석이 예정 시각에 돌지 않았어요 | `status/last-run-hourly.json`(또는 옛 `last-run.json`)의 "status" 커밋이 자가 보고 cron상 2슬롯 연속 없음(슬롯 40분 뒤부터 셈, analyze 커밋 기준이 아니다. 조용한 날엔 원래 없다). 매시간 자가 보고가 한 번도 없으면 건너뜀 |
| 루틴이 결과를 저장소에 올리지 못했어요 | 매시간·새벽 자가 보고의 `pushed`가 false |
| 우편함 토큰이 곧 만료돼요 | 폰이 올린 `tokenExpiresMs`가 14일 안(없으면 건너뜀) |
| 아침 보고서가 안 만들어졌어요 (v3.5) | 한국 시각 05:30이 지났는데 `report/<오늘>.json`이 없음(파일이 있는지만 봄). 새벽 정리 자가 보고 `promptVersion`이 7 이상일 때만. 04:46 워치독은 05:30 전이라 실제로는 07:34부터의 매시간 워치독이 본다. 본문에 `reportWritten` |
| 카카오톡 보고가 안 갔어요 (v3.5) | 하트비트 `kakao.on`이 참(키가 없으면 참으로 봄), `kakao.linked=true`, 09:00이 지났는데 `lastSentMs`가 어제 00:00 전(두 아침 연속), 어제 09:00 전부터 연결돼 있었을 때만 |
| 카카오톡 연결이 풀렸어요 (v3.5) | `kakao.on`이 거짓이 아니고 `kakao.linked=false`, `kakao.error`가 `TOKEN_`으로 시작 |
| 폰 배터리를 많이 써요 (v3.6) | 하트비트 `power.today.battery.min`이 60분 넘고, 같은 하트비트의 `power.prev`나 다른 날의 이전 하트비트 `today`도 60분 넘음(2일, 하루 예산 20분의 3배) |
| 충전을 기다리는 녹음이 쌓여요 (v3.6) | `power.waiting.n` > 0이고 `power.waiting.oldestMin`이 36시간(2160분) 넘음. 이 녹음들은 "자막이 밀려요"에서 빼고 센다(`newQueuedCount`가 `waiting.n`보다 많을 때만) |
| 자막 조각이 버려지고 있어요 (v3.6) | `power.today`와 `power.prev` 모두 `killedChunkMs + deadlineMs`가 실행 ms(`battery.ms + plugged.ms`, 없으면 분 × 60,000)의 20% 넘음 |

위가 `tools/inbox/watchdog.py`의 규칙 전부다(11/7, v3.5 규칙 3개와 v3.6 규칙 3개 포함 이슈 제목 31개).

토큰 이슈가 열리면: 새 토큰을 만들어 앱 저장소 비밀 `IEUM_INBOX_TOKEN`을 바꾸고 배포한다. 만료 뒤에는 폰이 업데이트도 못 받으므로 만료 전에 해야 한다. `IEUM_SIGNING_SEED` 비밀이 없으면 서명 키가 토큰에서 나오므로 토큰을 바꾸면 서명이 바뀌어 업데이트가 막힌다. 먼저 확인한다. 폰이 새 버전을 받고 다음 하트비트를 올리면 이슈는 저절로 닫힌다.

설치와 확인(배포 때, 개발 세션):
1. 복사는 release-apk가 한다(`tools/inbox/watchdog.py` → `tools/watchdog.py`, `docs/routines/hourly.md`·`tidy.md` → `routines/`, 바뀐 것만, version.json보다 먼저). 배포 없이 급히 바꿀 때만 손으로 ieum-inbox에 커밋한다.
2. 예약 작업 환경에 ieum-inbox 이슈 쓰기 권한(gh 인증)이 있는지 본다. 없으면 워치독 단계만 건너뛰고 루틴은 계속 돈다.
3. 수동 확인: ieum-inbox를 받은 폴더에서 `--apply` 없이 `python3 tools/watchdog.py --root . --out /tmp/ieum-watchdog-plan.json`을 돌리면 이슈를 열지 않고 지금 보이는 문제 목록만 쓴다. 실제 반영은 예약 작업 실행 기록의 마지막 줄 "watchdog: n opened, m closed"와 `gh issue list -R <owner>/ieum-inbox -l ieum-watch`로 본다.
4. 이슈 본문은 고정 문장과 시각, 개수, 참거짓뿐이다. 우편함 파일 내용은 읽지 않는다.

## 5. 배포와 업데이트 흐름

1. `feature/lean-calls`(또는 `main`)에 push한다. 앱 작업은 시험 브랜치에서 CI를 통과시킨 뒤 합친다.
2. `ci`(android-ci.yml, `feature/**`·`main` push 중 `android/**`, `tools/**`, ci·release 워크플로가 바뀐 것): 도구 시험(`tools/`, `tools/inbox/`의 파이썬 시험), 화면 규칙 검사, 내보내기 검사(`tools/scan_export.py`: 비밀 값은 실패, 개인 경로는 경고), 업데이트 문구 알림(앱 코드가 바뀌었는데 `docs/UPDATE_NOTE.txt`가 그대로면 경고), JVM 단위 테스트와 lint.
3. `update-smoke`(update-smoke.yml): 같은 push에서 함께 돈다. 마지막으로 성공한 배포 이후 AndroidManifest.xml, IeumApplication, StartupReceiver, CallSinkImpl, 빌드 설정(*.gradle.kts, gradle.properties, Gradle wrapper) 중 하나라도 바뀌었으면(`tools/needs_update_smoke.sh`) 에뮬레이터에 직전 릴리스를 깔고 새 APK를 덮어 설치해 시작, 충돌 없음, 예약 작업 다섯 개 이상을 확인하고, 앱이 스스로 새 APK를 설치하는 경로도 본다(지금은 경고만, `IEUM_SELF_INSTALL_STRICT=1`이면 실패). 아니면 바로 끝난다. 에뮬레이터가 켜지지 않으면(러너 문제) 한 번 자동 재시도하고, 두 번 모두 안 켜지면 "Emulator did not boot (infra)" 단계가 실패로 남는다.
4. `release-apk`: 같은 커밋의 ci 통과를 기다린다(40분). 3의 조건에 해당하면 update-smoke의 "Install, update, start" 단계 통과도 기다린다(70분). 앱 설치·덮어 설치·실행 실패나 시간 초과면 배포하지 않는다. 에뮬레이터가 재시도 뒤에도 켜지지 않은 경우(인프라)만 경고를 남기고 배포한다. 서명 키는 저장소 비밀에서 만들고(`IEUM_SIGNING_SEED`가 있으면 그것, 없으면 `IEUM_INBOX_TOKEN`에서; cryptography 50.0.2 고정), 직전 릴리스 APK와 서명 인증서 지문이 다르면 배포하지 않는다. 직전 APK를 못 받으면 version.json의 `certSha256`과 비교하고, 그것도 없으면 경고만 남긴다(`IEUM_ROTATE=1`이면 키 계보 검사). 통과하면 ieum-inbox에 릴리스 `v<빌드 번호>`(빌드 번호 = 1000 + 실행 번호)를 만들고, 지시문 둘과 워치독을 `routines/`·`tools/`에 바뀐 것만 올린 뒤, `app/version.json`(versionCode, versionName=`docs/VERSION`, apk, sha256, size=APK 바이트 크기, notes=`UPDATE_NOTE.txt` 첫 줄 120자, promptVersion, tidyVersion, certSha256)을 올린다. 오래된 릴리스는 3개만 남긴다(정리 실패는 경고만). 트리거는 `feature/lean-calls`·`main` push 중 `android/**`, `tools/**`, release 워크플로가 바뀐 것과 수동 실행.
5. 폰은 결과 받기 때마다 `app/version.json`을 ETag로 읽고(처음 보는 높은 versionCode면 바로 확인), 6시간 주기와 앱을 열 때도 확인한다. 네트워크 종류와 상관없이 받고, 가능하면 누르지 않고 설치하며(API 31+, 설치 허용), 아니면 notes(= `docs/UPDATE_NOTE.txt` 첫 줄)를 알림 문구로 보여 준다. 이름은 `docs/VERSION`(사용자에게 보이는 변경마다 0.1씩), 빌드 번호는 업데이트 순서에만 쓴다.

수동 배포(release-apk 수동 실행)는 update-smoke 실행이 없으면 경고만 남기고 진행한다.

## 6. 문제가 생겼을 때 볼 곳

| 증상 | 먼저 볼 곳 | 흔한 원인 |
|---|---|---|
| 통화가 정리되지 않음 | `status/last-run-hourly.json`(`endedMs`, `repoOk`, `pushed`, `leftover`), 예약 작업 실행 기록("확인 필요" 답), inbox 파일 개수와 가장 오래된 시각 | 구독 한도, 예약 작업 소스에서 ieum-inbox 빠짐, 저장소 접근 실패, 지시문 교체 누락 |
| 결과는 있는데 폰에 안 보임 | `status/phone.json`의 `atMs`, `lastResultMs`, `lastInboxError`; `results/`와 `bad/` | 폰 절전이나 네트워크, 토큰 만료, 결과 형식 오류 |
| 자막이 밀림 | `status/phone.json`의 `queuedCount`, `oldestQueuedMs`, `modelState` | 충전 안 함, 모델 문제, 처음 정리 중 |
| 폰 배터리가 빨리 닳음(이음이 배터리 사용량 상위) | `transcription.runs`(그날 실행 수·분; 60% 넘으면 자막이 하루 종일 돈 것), `transcription.decode`(조각당 ms, 누적), v3.6부터 `power` | 대기열이 한꺼번에 늘어남(재큐·처음 정리), 조각당 시간이 540초에 가까워 둘째 조각이 버려짐, 발열 스로틀링. v3.6 전력 정책 전에는 앱이 배터리 상태를 거의 보지 않음(DESIGN_V6 1절) |
| 업데이트가 안 됨 | GitHub Actions의 release-apk와 update-smoke 실행, `app/version.json`, 하트비트의 `lastUpdateError` | ci 또는 smoke 실패, 서명 지문 불일치, 설치 권한 |
| 장부 이름이나 업체가 이상함 | `ledger/ledger.json`의 rev와 updatedMs(내용은 옮기지 않고 앱에서 확인), `ledger/edits/` | 수정 반영 대기, 새벽 정리 전 |
| CI 실패 | 실행의 "Summarize results" 요약과 `android-test-results` 산출물 | 단위 테스트, lint, 내보내기 검사 |
| 모든 워크플로가 몇 초 만에 실패(단계 없음) | 실행 주석: "recent account payments have failed or your spending limit needs to be increased" | GitHub Actions 사용량·결제 한도. 사용자가 GitHub 설정 Billing에서 해결해야 하며 그동안 ci와 배포가 멈춘다(워치독은 v3.4부터 루틴 안에서 돌아 영향 없음). 에뮬레이터 시험(약 90분)이 사용량 대부분을 쓴다 |

정기 점검: 달에 한 번 예약 작업 실행 기록 1건을 열어 통화 원문이 남지 않았는지 확인한다.

관련 파일: `docs/routines/`(지시문, 자가 보고 요약 `self-report.md`), `tools/inbox/`(워치독), `tools/needs_update_smoke.sh`, `tools/update_smoke.sh`, `.github/workflows/`, `docs/DEVELOPMENT.md`(개발 환경).
