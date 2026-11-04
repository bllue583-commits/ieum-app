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

## 2. 예약 작업 세 개 (11/4 밤 기준, 소스 ieum-inbox, 알림 켬)

| 이름 | 시각(한국) | 모델 | 지시문 원본 | 기억 파일 | 하는 일 |
|---|---|---|---|---|---|
| 이음 통화 분석 (매시간) | 매시 30분 | Sonnet 5.5 | `docs/routines/hourly.md`(v5)의 "---" 아래 | `status/last-run.json` | inbox 요청 분석, 결과 쓰기, 장부 고치기, 세 줄 보고 |
| 이음 새벽 정리 (매일) | 05:16 | Opus 5.5 | `docs/routines/tidy.md`의 "---" 아래 | `tidy/project.md`, `tidy/history/YYYY-MM.md`, `tidy/briefing.md` | 진행 중인 일 묶기, 팔로업 제안, 중복 합치기, 현황 파일과 다섯 줄 알림. 폰이 처음 정리를 끝낸 뒤 밤에 올리는 `tidy/state.json`이 있어야 일을 한다(그 전에는 "정리할 것 없음"이 정상) |
| 이음 통화 목록 검증 (매일) | 09:00 | Opus 5.5 | 예약 작업 프롬프트가 원본 | `status/verify-log.md`(숫자만) | 폰 통화기록(`status/calllog.txt`)과 저장·분석 결과 대조, 검증 파일과 다섯 줄 알림 |

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
| `status/calllog.txt` | 폰 | 통화 목록 대조표(이름·번호 포함, 4절) |
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
| `consent` | 통화 정리 동의가 켜져 있는가 | false면 정리 요청이 올라가지 않음(앱 설정에서 켜기) |
| `folder` | 녹음 폴더가 골라져 있고 읽기 권한이 살아 있는가 | false면 새 녹음을 못 가져옴(앱에서 폴더 다시 고르기) |
| `notifyAllowed` | 알림 권한과 앱 알림이 켜져 있는가 | false면 저녁·아침 보고와 자가 진단 알림이 안 보임 |
| `batteryFree` | 배터리 최적화 예외인가 | false면 작업이 자주 미뤄짐 |
| `standbyBucket` | 앱 대기 버킷: `ACTIVE`, `WORKING_SET`, `FREQUENT`, `RARE`, `RESTRICTED`(Android 9 미만은 `NONE`) | `RARE`/`RESTRICTED`면 작업이 하루 몇 번만 돎. 앱을 한 번 열면 풀림 |
| `lastDiscoveryMs` | 녹음 폴더를 마지막으로 다 살펴본 시각(0: v2.4 전 앱이거나 아직 없음) | 폰은 살아 있는데 12시간 넘게 그대로면 녹음 찾기가 멈춤 |
| `lastUploadMs` | 정리 요청을 우편함에 마지막으로 올린 시각(0: 없음) | 통화가 있었는데 오래 그대로면 동의, 토큰, 네트워크 확인 |
| `pendingAnalysis` | 정리(요약)를 기다리는 통화 수 | 늘기만 하면 예약 작업이나 우편함 쪽 확인 |
| `rescheduledMs` | 하루 1회 자가 복구가 빠진 작업을 다시 등록한 마지막 시각(0: 없음) | 자주 바뀌면 폰이 작업을 지우고 있음(절전, 제조사 정리 앱) |
| `tokenExpiresMs` | 앱에 든 우편함 토큰의 만료 시각. 폰이 우편함 응답 헤더 `github-authentication-token-expiration`에서 읽음(null: 아직 못 봄, 만료 없는 토큰) | 14일 안이면 워치독 이슈. 새 토큰으로 앱을 다시 배포해야 함 |
| `audioChannels`, `audioChannelCorr` | 녹음의 채널 수와 좌우 상관계수(자막 엔진이 기록한 뒤부터, 그 전에는 null) | 상관계수가 0.5 미만이면 좌우가 다른 사람일 수 있음(설계 F1) |
| `callLog` | 통화 목록을 빠짐없이 읽는지 보는 묶음(아래 표). 숫자와 시각만 | `deviceTotal`과 `storedTotal`이 다르면 앱이 빠뜨린 것 |

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

### 워치독 (ieum-inbox의 감시)
11/5 설치 완료(ieum-inbox 커밋 2d121ae, 첫 수동 실행 41853205350 이상 0건). 아래 설치 절차는 다시 설치할 때만 쓴다.

원본은 이 저장소의 `tools/inbox/watchdog.yml`, `watchdog.py`, 시험은 `test_watchdog.py`(ci가 돌림)다. ieum-inbox에 설치하면 3시간마다(그리고 수동 실행 때) 아래 규칙으로 검사해 문제마다 라벨 `ieum-watch` 이슈를 하나 만들고, 같은 제목의 열린 이슈가 있으면 댓글도 달지 않으며, 정상으로 돌아오면 "정상으로 돌아왔어요." 댓글과 함께 닫는다.

| 이슈 제목 | 조건 |
|---|---|
| 폰이 조용해요 | `status/phone.json`이 없거나 `atMs`가 12시간 넘음. 본문에 마지막 하트비트의 `consent`, `folder`, `batteryFree`, `standbyBucket`, `notifyAllowed`, `lastDiscoveryMs`로 짐작한 원인을 붙인다 |
| 녹음 가져오기가 멈췄어요 | 폰은 살아 있는데 `lastDiscoveryMs`가 12시간 넘음(0이면 건너뜀) |
| 통화 목록을 빠뜨리고 있어요 | 폰은 살아 있는데 `callLog.deviceTotal`과 `storedTotal`이 다르고 `mismatchSinceMs`가 2시간 넘음(권한 없으면 건너뜀) |
| 자막이 밀려요 | `oldestQueuedMs`가 6시간 넘음. 단 처음 정리 중(`firstPass.done=false`)에는 옛 녹음이 대기열에 오래 있는 것이 정상이라, 남은 자막이 있는데 마지막 자막(`lastTranscribedMs`)이 24시간 넘었을 때만 |
| 업데이트 실패 | `lastUpdateError`가 있음 |
| 분석이 멈췄어요 | inbox/의 가장 오래된 요청이 3시간 넘음(파일이 처음 커밋된 시각 기준) |
| 루틴이 일을 안 해요 / 루틴이 한도에 걸렸어요 / 루틴 지시문 버전이 달라요 | `status/last-run.json`의 `processed`, `limitHit`, `promptMismatch` |
| 우편함 토큰이 곧 만료돼요 | 폰이 올린 `tokenExpiresMs`가 14일 안(없으면 건너뜀). 워크플로 자체 토큰에는 만료 헤더가 없어서 폰 값을 쓴다 |

토큰 이슈가 열리면: 새 토큰을 만들어 앱 저장소 비밀 `IEUM_INBOX_TOKEN`을 바꾸고 배포한다. 만료 뒤에는 폰이 업데이트도 못 받으므로 만료 전에 해야 한다. `IEUM_SIGNING_SEED` 비밀이 없으면 서명 키가 토큰에서 나오므로 토큰을 바꾸면 서명이 바뀌어 업데이트가 막힌다. 먼저 확인한다. 폰이 새 버전을 받고 다음 하트비트를 올리면 이슈는 저절로 닫힌다.

설치(개발 세션에서 한 번, ieum-inbox 기본 브랜치):
1. 이 저장소의 `tools/inbox/watchdog.yml`을 ieum-inbox의 `.github/workflows/watchdog.yml`로, `tools/inbox/watchdog.py`를 ieum-inbox의 `tools/watchdog.py`로 복사해 커밋하고 push한다. 워크플로 파일을 올릴 권한이 없다고 나오면 `gh auth refresh -s workflow` 뒤 다시 push한다.
2. ieum-inbox 설정 Actions → General에서 Actions가 켜져 있는지 본다(워크플로 권한은 파일의 `permissions: issues: write`로 충분).
3. 수동 실행: `gh workflow run watchdog.yml -R <owner>/ieum-inbox` → `gh run list -R <owner>/ieum-inbox -w watchdog -L 1`로 실행 번호 확인 → `gh run watch <번호> -R <owner>/ieum-inbox --exit-status`.
4. 확인: `gh issue list -R <owner>/ieum-inbox -l ieum-watch`에 지금 상태에 맞는 이슈(폰이 조용하면 "폰이 조용해요" 1건)가 있고, 실행 요약에 "watchdog: N opened, M closed"가 남는다. 한 번 더 수동 실행해 새 이슈가 생기지 않는지(같은 문제는 opened 0) 본다.
5. 이슈 본문은 고정 문장과 시각, 개수, 참거짓뿐이다. 우편함 파일 내용은 읽지 않는다.

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
| 모든 워크플로가 몇 초 만에 실패(단계 없음) | 실행 주석: "recent account payments have failed or your spending limit needs to be increased" | GitHub Actions 사용량·결제 한도. 사용자가 GitHub 설정 Billing에서 해결해야 하며 그동안 ci, 배포, 워치독이 모두 멈춘다. 에뮬레이터 시험(약 90분)이 사용량 대부분을 쓴다 |

정기 점검: 달에 한 번 예약 작업 실행 기록 1건을 열어 통화 원문이 남지 않았는지 확인한다.

관련 파일: `docs/routines/`(지시문), `tools/inbox/`(워치독), `tools/needs_update_smoke.sh`, `tools/update_smoke.sh`, `.github/workflows/`, `docs/DEVELOPMENT.md`(개발 환경).
