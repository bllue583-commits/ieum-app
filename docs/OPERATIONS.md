# 이음 운영 안내

평소에 돌아가는 것, 확인하는 법, 문제가 생겼을 때 볼 곳을 한 장에 모았다. 설계 근거는 설계도(Claude Docs)의 "장부의 원본", "새벽 정리", B절(감시), H절(배포 안전)이다. 이 파일에는 비밀 값, 토큰, 개인 경로를 쓰지 않는다.

## 1. 전체 흐름

```
폰: 통화 녹음 발견 → 폰 안에서 자막 → 우편함 inbox/ 에 요청 (녹음은 폰 밖으로 안 나감)
예약 작업(매시간): 요청 분석 → results/ 에 결과, ledger/ledger.json 장부 고침, status/last-run.json 자가 보고
폰(매시간 틱): 장부 받기 → 결과 적용 후 결과 파일 지움 → 새 요청 올림 → status/phone.json 하트비트
폰(밤 21~05시, 하루 한 번): tidy/state.json 올림
예약 작업(새벽): 장부 정리 + tidy/proposal.json 제안 → 폰이 받아 체크할 일에 반영
```

우편함은 비공개 저장소 `bllue583-commits/ieum-inbox`이다. 앱 저장소는 `bllue583-commits/ieum-app`이다.

## 2. 예약 작업 두 개

| 이름 | 시각(한국) | 모델 | 지시문 원본 | 하는 일 |
|---|---|---|---|---|
| 이음 통화 분석 (매시간) | 07~23시 매시 정각 | Sonnet 5.5 | `docs/routines/hourly.md`의 "---" 아래 | inbox 요청 분석, 결과 쓰기, 장부(현장 메모, 업체, 사람, issues 현황) 고치기 |
| 이음 새벽 정리 (매일) | 05시 (06시 아침 보고 전) | Opus 5.5 | `docs/routines/tidy.md`의 "---" 아래 | 진행 중인 일 묶기, 팔로업 제안, 업체와 사람 중복 합치기, 메모 다시 쓰기 |

- 지시문을 바꿀 때: 이 저장소의 원본 파일을 먼저 고친다 → 예약 작업 프롬프트에 "---" 아래 전문을 붙여 넣는다 → 이름, 시각, 링크를 사용자에게 알린다. 매시간 지시문의 [분석 규칙]과 [결과 형식]은 앱 코드(`ClaudeCallAnalyzer.SYSTEM`, `SCHEMA`)와 같아야 하며 `RoutineDocTest`가 검사한다. 규칙을 바꾸면 `ClaudeCallAnalyzer.PROMPT_VERSION`과 지시문 버전을 함께 올린다.
- 실행 확인: claude.ai의 예약 작업 화면에서 실행 기록을 본다. 실행 로그에는 통화 내용이 남지 않게 지시돼 있다. 빈 실행은 "새 기록 없음" 한 줄로 끝나는 것이 정상이다.
- 우편함에서 확인: 매시간 작업이 처리하면 커밋 "analyze N calls", 새벽 정리는 "tidy", 할 일이 없었으면 "status"가 생긴다. 어느 경우든 `status/last-run.json`이 새로 써진다.
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
| `status/phone.json` | 폰 | 하트비트 |
| `status/last-run.json` | 예약 작업 | 마지막 실행의 자가 보고 |
| `app/version.json`, 릴리스 | release-apk | 최신 APK 정보와 파일(최근 3개만 남김) |

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
| `firstPass` | 처음 정리 진행: `done`, `toTranscribe`, `audioMinutesLeft`, `waitingForSummary`, `batchesToday` | 처음 정리 중에만 의미 |
| `lastInboxError` | 우편함 오류 코드(있을 때만) | INBOX_HTTP_401/403/404면 토큰 만료나 권한 문제 |
| `lastUpdateError` | 업데이트 설치 오류 코드(있을 때만) | 설치 권한, 저장 공간, 서명 불일치 |

### status/last-run.json (예약 작업 자가 보고)
한 줄 JSON이며 마지막으로 끝난 작업(매시간 또는 새벽)의 것이다.

| 키 | 뜻 |
|---|---|
| `routine` | `hourly` 또는 `tidy` |
| `startedMs`, `endedMs` | 실행 시작과 끝 |
| `processed`, `skipped`, `failed`, `batches` | 매시간: 결과를 만든 항목 수, 건너뛴 수, 실패 수, 읽은 요청 파일 수. 새벽: 읽은 기록 수, 건너뛴 수, 실패 수, 제안 항목 수 |
| `model`, `limitHit` | 쓴 모델, 사용 한도 오류를 만났는지 |
| `ledgerRev` | 이번에 쓴 장부 rev |
| `promptVersion`, `promptMismatch` | 매시간 지시문 버전, 요청의 버전이 달랐는지(폰 업데이트 전 과도기 버전은 제외) |
| `loggedTranscript` | 항상 false여야 한다 |

읽는 순서: `endedMs`가 최근인가 → `limitHit`가 true면 한도 → inbox에 파일이 있는데 `processed`가 0이면 작업이 일을 안 함 → `promptMismatch`가 true면 폰과 지시문 버전이 어긋남.

워치독(원본 `tools/inbox/watchdog.yml`, `watchdog.py`)을 ieum-inbox에 설치하면 3시간마다 위 규칙으로 검사해 라벨 `ieum-watch` 이슈를 만들고 정상으로 돌아오면 닫는다. 폰 하트비트가 올라오기 시작한 뒤에 설치한다(그 전에는 거짓 경보).

## 5. 배포와 업데이트 흐름

1. `feature/lean-calls`(또는 `main`)에 push한다. 앱 작업은 시험 브랜치에서 CI를 통과시킨 뒤 합친다.
2. `ci`(android-ci.yml): 도구 시험, 화면 규칙 검사, 내보내기 검사(`tools/scan_export.py`: 비밀 값은 실패, 개인 경로는 경고), 업데이트 문구 알림(앱 코드가 바뀌었는데 `docs/UPDATE_NOTE.txt`가 그대로면 경고), JVM 단위 테스트와 lint.
3. `update-smoke`(update-smoke.yml): 같은 push에서 함께 돈다. 마지막으로 성공한 배포 이후 AndroidManifest.xml, IeumApplication, StartupReceiver, CallSinkImpl, 빌드 설정(*.gradle.kts, gradle.properties, Gradle wrapper) 중 하나라도 바뀌었으면(`tools/needs_update_smoke.sh`) 에뮬레이터에 직전 릴리스를 깔고 새 APK를 덮어 설치해 시작, 충돌 없음, 예약 작업 다섯 개를 확인한다. 아니면 바로 끝난다.
4. `release-apk`: 같은 커밋의 ci 통과를 기다린다(40분). 3의 조건에 해당하면 update-smoke의 "Install, update, start" 단계 통과도 기다린다(50분). 서명 키는 저장소 비밀에서 만들고(cryptography 버전 고정), 직전 릴리스와 서명 인증서 지문이 다르면 배포하지 않는다. 통과하면 ieum-inbox에 릴리스 `v<빌드 번호>`와 `app/version.json`을 올리고 오래된 릴리스는 3개만 남긴다(정리 실패는 경고만).
5. 폰은 `app/version.json`을 보고 새 버전이면 `docs/UPDATE_NOTE.txt` 첫 줄을 알림 문구로 보여 주고 설치한다. 이름은 `docs/VERSION`(사용자에게 보이는 변경마다 0.1씩), 빌드 번호는 업데이트 순서에만 쓴다.

수동 배포(release-apk 수동 실행)는 update-smoke 실행이 없으면 경고만 남기고 진행한다.

## 6. 문제가 생겼을 때 볼 곳

| 증상 | 먼저 볼 곳 | 흔한 원인 |
|---|---|---|
| 통화가 정리되지 않음 | `status/last-run.json`, 예약 작업 실행 기록, inbox 파일 개수와 가장 오래된 시각 | 구독 한도, 예약 작업 소스에서 ieum-inbox 빠짐, 지시문 교체 누락 |
| 결과는 있는데 폰에 안 보임 | `status/phone.json`의 `atMs`, `lastResultMs`, `lastInboxError`; `results/`와 `bad/` | 폰 절전이나 네트워크, 토큰 만료, 결과 형식 오류 |
| 자막이 밀림 | `status/phone.json`의 `queuedCount`, `oldestQueuedMs`, `modelState` | 충전 안 함, 모델 문제, 처음 정리 중 |
| 업데이트가 안 됨 | GitHub Actions의 release-apk와 update-smoke 실행, `app/version.json`, 하트비트의 `lastUpdateError` | ci 또는 smoke 실패, 서명 지문 불일치, 설치 권한 |
| 장부 이름이나 업체가 이상함 | `ledger/ledger.json`의 rev와 updatedMs(내용은 옮기지 않고 앱에서 확인), `ledger/edits/` | 수정 반영 대기, 새벽 정리 전 |
| CI 실패 | 실행의 "Summarize results" 요약과 `android-test-results` 산출물 | 단위 테스트, lint, 내보내기 검사 |

관련 파일: `docs/routines/`(지시문), `tools/inbox/`(워치독), `tools/needs_update_smoke.sh`, `tools/update_smoke.sh`, `.github/workflows/`, `docs/DEVELOPMENT.md`(개발 환경).
