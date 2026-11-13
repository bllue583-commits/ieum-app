# 이음 운영 안내

평소에 돌아가는 것, 확인하는 법, 문제가 생겼을 때 볼 곳을 한 장에 모았다. 설계 근거는 [DESIGN.md](DESIGN.md)(현재 설계도)와 그 전 설계도(Claude Docs)의 "장부의 원본", "새벽 정리", B절(감시), H절(배포 안전)이다. 11/6 우편함과 코드로 대조했다. 이 파일에는 비밀 값, 토큰, 개인 경로를 쓰지 않는다.

## 1. 전체 흐름

```
폰: 통화 녹음 발견 → 폰 안에서 자막 → 우편함 inbox/ 에 요청 (녹음은 폰 밖으로 안 나감)
예약 작업(매시간, v3.7부터 03:34·04:34·07:34~22:34): 요청 분석 → results/ 에 결과, ledger/ledger.json 장부 고침, status/last-run-hourly.json 자가 보고 → 워치독(루틴 안)
폰(결과 받기: 루틴 cron의 다음 예정 실행 +3, +8, +15분, 그리고 매시간 틱): 장부·story/today.json·app/version.json 받기 → 결과 적용 후 결과 파일 지움
폰(매시간 틱): 새 요청 올림 → status/phone.json 하트비트 → status/calllog.txt
폰(밤 21:00~05:03, v3.7): 결과를 1건 이상 적용한 패스마다 곧바로, 그 밖에는 내용이 바뀌었을 때 30분에 한 번 tidy/state.json 올림(처음 정리 중이면 최근 14일만; v3.6까지는 21시대 하루 한 번)
예약 작업(새벽 05:05, v3.6까지 04:46): 장부 정리 + tidy/proposal.json 제안 + report/YYYY-MM-DD.json 아침 보고서 → 폰이 받아 체크할 일에 반영 → 워치독(루틴 안)
폰(05:30, 없으면 05:35·05:45·06:00, 09:00까지): report/<오늘>.json 받기 → 카카오톡 "나와의 채팅"으로 전송(v3.5; v3.7부터 보내기 직전에 아직 글자로 바꾸는 중인 어제 통화 수를 다시 세어 첫 줄에 적음) → 06:00 앱 알림
```

우편함은 비공개 저장소 `bllue583-commits/ieum-inbox`이다. 앱 저장소는 `bllue583-commits/ieum-app`이다.

## 2. 예약 작업 세 개 (11/10 확인 — 세 작업 모두 정상 실행 중; 구성은 11/6 기준, 알림 켬(push), 승인 방식 자동. 매시간·새벽 작업은 11/6 새벽 Opus 세션이 ieum-app·ieum-inbox 둘 다 붙여 다시 만들었고, 07:36 실행이 `repoOk=true`로 우편함에 push한 것으로 연결이 확인됨)

| 이름 | 시각(한국) | 모델 | 지시문 원본 | 기억 파일 | 하는 일 |
|---|---|---|---|---|---|
| 이음 통화 분석 (매시간) | 매시 34분, 03:34·04:34와 07:34~22:34(하루 18번, v3.7. v3.6까지 07:34~22:34 16번) | Sonnet 5.5 | `docs/routines/hourly.md`(v9, v3.7에서 메일 종류 삭제)의 "---" 아래 | `status/last-run-hourly.json` | inbox 요청 분석(한 실행 20건까지, 03:34·04:34 밤 실행은 40건까지이고 남으면 07:34로), 결과 쓰기, 장부 고치기, 자가 보고, 워치독, 세 줄 보고 |
| 이음 새벽 정리 (매일) | 05:05(v3.7; v3.5~v3.6은 04:46, 그 전 05:16) | Opus 5.5 | `docs/routines/tidy.md`(v8)의 "---" 아래 | `tidy/project.md`, `tidy/history/YYYY-MM.md`, `tidy/briefing.md`, 자가 보고 `status/last-run-tidy.json`(`reportWritten`) | 진행 중인 일 묶기, 팔로업 제안, 중복 합치기, 아침 보고서 `report/YYYY-MM-DD.json`(9-1번, `tools/report_check.py` 통과 뒤 커밋, 30일 지난 것은 지움), 현황 파일과 다섯 줄 알림, 워치독. 폰이 밤에 올린 `tidy/state.json`(24시간 안)이 있어야 일을 한다. v3.1부터 폰이 처음 정리 중이어도 최근 14일 기록을 올리므로 처음 정리를 기다리지 않는다(state가 없거나 오래됐으면 "정리할 것 없음: 이유"가 정상, 같은 날 이미 "tidy" 커밋이 있으면 "이미 정리됨") |
| 이음 통화 목록 검증 (매일) | 09:00 | Opus 5.5 | 예약 작업 프롬프트가 원본 | `status/verify-log.md`(숫자만) | 폰 통화기록(`status/calllog.txt`)과 저장·분석 결과 대조, 검증 파일과 다섯 줄 알림. 프롬프트는 아직 옛 `status/last-run.json`을 읽는다(새 루틴별 파일로 바꾸지 않았음) |

- **v3.7 시각표(DESIGN_V7 3.2 M1):** 매시간 분석 `CRON_TZ=Asia/Seoul 34 3-4,7-22 * * *`(하루 18번: 03:34, 04:34, 07:34~22:34. 밤에 만든 자막을 새벽 정리와 아침 보고 전에 분석, 밤 두 실행은 처리 상한 40건, 지시문 v9), 새벽 정리 `CRON_TZ=Asia/Seoul 5 5 * * *`(05:05, 지시문 v8), 카톡 05:30은 그대로. 폰 기본값(`CallRecordWork.DEFAULT_CRON`)과 워치독 기본값(`watchdog.py`의 `DEFAULT_CRON`)은 이미 새 cron이다. 폰은 자가 보고 `cron`으로 결과 받는 시각을 정하고, 03시·04시 실행 뒤에는 +3·+8·+15분에 더해 +25분에도 한 번 더 받는다(`NIGHT_EXTRA_MS`).
- **배포 때 cron 바꾸는 절차(v3.7):** 실제 예약 작업의 cron은 v3.7 배포 때 바꾼다 — release-apk가 우편함 `routines/`에 hourly v9·tidy v8을 올리고 `app/version.json`(promptVersion "9", tidyVersion "8")을 올린 뒤, **매시간 분석을 먼저**(`trig_016vQxos9z6p1pdPJ28MjrsV`, `34 7-22` → `34 3-4,7-22`), **새벽 정리를 다음에**(`trig_01Sp6piuvasw52ZR1KPupumB`, `46 4` → `5 5`) 바꾼다. 05:05 새벽 정리는 03:34·04:34 분석이 적용된 state를 재료로 삼으므로, 밤 분석이 먼저 자리 잡게 매시간을 먼저 바꾼다. 바꾼 뒤 예약 작업 목록에서 두 cron을 확인하고, 다음 매시간 자가 보고의 `cron`이 새 문자열인지 본다. 배포 전까지 지금 예약 작업은 옛 cron(매시간 `34 7-22`, 새벽 `46 4`)으로 돈다.
- 매시간·새벽 예약 작업 프롬프트(11/6부터)는 짧은 안내다: ieum-inbox를 pull(실패하면 "[이음 분석 HH:MM] 확인 필요: 저장소 접근", 새벽은 "[이음 새벽 정리 MM/DD HH:MM] 확인 필요: 저장소 접근"만 답함) → 우편함의 `routines/hourly.md`(또는 `routines/tidy.md`)를 Read로 끝까지 읽고 첫 "---" 아래를 지시문으로 따른다. cron은 v3.7 배포 전까지 매시간 `CRON_TZ=Asia/Seoul 34 7-22 * * *`, 새벽 `CRON_TZ=Asia/Seoul 46 4 * * *`이고 배포 때 위 절차로 매시간 `CRON_TZ=Asia/Seoul 34 3-4,7-22 * * *`, 새벽 `CRON_TZ=Asia/Seoul 5 5 * * *`으로 바꾼다. 검증 `CRON_TZ=Asia/Seoul 0 9 * * *`은 그대로. ID는 매시간 `trig_016vQxos9z6p1pdPJ28MjrsV`, 새벽 `trig_01Sp6piuvasw52ZR1KPupumB`, 검증 `trig_01KyuDNZWkmrQpJj8e8cr661`.
- 지시문을 바꿀 때: 이 저장소의 원본 파일(`docs/routines/*.md`)을 고치고 배포한다. release-apk가 version.json보다 먼저 우편함 `routines/`와 `tools/watchdog.py`에 바뀐 것만 올린다. 배포 없이 급히 바꿀 때만 우편함 `routines/`에 직접 커밋한다. 매시간 지시문의 [분석 규칙]과 [결과 형식]은 앱 코드(`ClaudeCallAnalyzer.SYSTEM`, `SCHEMA`)와 같아야 하며 `RoutineDocTest`가 검사한다. 규칙을 바꾸면 `ClaudeCallAnalyzer.PROMPT_VERSION`과 지시문 버전을 함께 올린다.
- 실행 확인: claude.ai의 예약 작업 화면에서 실행 기록을 본다. 실행 로그에는 통화 내용이 남지 않게 지시돼 있다. 빈 실행은 "새 기록 없음" 한 줄로 끝나는 것이 정상이다.
- 우편함에서 확인: 매시간 작업이 처리하면 커밋 "analyze N calls", 새벽 정리는 "tidy"가 생기고, 어느 경우든(할 일이 없었어도) 그 뒤에 자가 보고만 담은 "status" 커밋이 따로 생긴다(매시간 `status/last-run-hourly.json`, 새벽 `status/last-run-tidy.json`). 루틴이 저장소에 닿지 못하면 처리하지 않고 "확인 필요: 저장소 접근"으로 답하고 끝난다. 예약 작업 알림은 "확인 필요" 답일 때만 의미가 있다.
- 종단 시험: 앱 저장소의 `inbox-e2e`를 수동 실행(또는 그 파일 push)하면 `inbox/selftest-<번호>.json`을 넣고, 매시 40분(07:40~23:40)에 결과를 폰과 같은 파서로 검증한 뒤 지운다. 예약 작업은 이 요청으로 장부를 고치지 않고, 폰은 `selftest-` 결과를 무시한다.
- 두 작업과 개발 세션은 같은 Claude 구독 한도를 쓴다(v3.7부터 매시간 분석이 하루 18번; 밤 두 실행은 개발 세션과 잘 겹치지 않음). 한도에 걸리면 실행이 몇 초 만에 실패하고, 한도가 풀린 뒤 다음 실행이 밀린 요청을 이어서 처리한다.

## 3. 우편함 폴더 구조 (ieum-inbox 기본 브랜치)

| 경로 | 쓰는 쪽 | 내용 |
|---|---|---|
| `inbox/<해시 32자>.json` | 폰 | 새 기록 하나의 분석 요청(통화, 문자. 메일은 v3.7에서 지움). 이름에 번호나 이름이 없다 |
| `inbox/b-<시각>-<해시>.json` | 폰 | 처음 정리(10월 1일부터의 지난 기록) 묶음 요청. 매시간 2개까지 처리 |
| `inbox/selftest-<번호>.json` | CI `inbox-e2e` | 종단 시험 요청. 결과만 쓰고 장부는 고치지 않는다 |
| `results/<같은 이름>.json` | 예약 작업 | 분석 결과. 폰이 적용한 뒤 지운다. `selftest-`는 CI가 검증 후 지운다 |
| `bad/` | 양쪽 | 형식이 깨진 요청이나 세 번 적용에 실패한 결과를 치워 둔 곳 |
| `ledger/ledger.json` | 예약 작업 | 장부(원본). rev가 오르면 폰이 받아 반영 |
| `ledger/edits/*.json` | 폰 | 사용자가 앱에서 고친 이름 등. 예약 작업이 장부에 반영하고 지운다 |
| `tidy/state.json`, `tidy/proposal.json` | 폰 / 새벽 정리 | 새벽 정리의 입력과 제안. v3.7부터 state에 `pending{yesterdayCalls, yesterdayMin}`과 records의 `doneMs` |
| `tidy/project.md`, `tidy/history/YYYY-MM.md`, `tidy/briefing.md` | 새벽 정리 | 새벽 정리의 기억(공사 전체, 날짜별 일지, 오늘 현황판) |
| `report/YYYY-MM-DD.json` | 새벽 정리(v3.5) | 아침 보고서(한국 시각 오늘). v3.7부터 `head.pendingCalls`와 `late[]`(뒤늦게 정리된 통화). 폰이 05:30부터 ETag로 읽어 카카오톡으로 보낸다. 이름·업체·요약이 들어 있으니 내용은 옮기지 않는다 |
| `story/today.json` | 매시간 분석 | 오늘 통화 이야기(v3.2, 홈 맨 위) {day, text, calls, atMs}. 폰이 결과 받기 때 가져감 |
| `status/phone.json` | 폰 | 하트비트 |
| `status/calllog.txt` | 폰 | 통화 목록 대조표(이름·번호 포함, 4절) |
| `status/last-run-hourly.json`, `status/last-run-tidy.json` | 예약 작업 | 루틴별 마지막 실행의 자가 보고(v3.4부터) |
| `status/last-run.json` | 예약 작업(옛) | v3.3까지 두 루틴이 함께 쓰던 자가 보고. 지우지 않고 남아 있으며, 새 파일이 없을 때만 워치독과 폰이 읽는다. 11/6 05:16 새벽 정리는 아직 이 파일에 지시문 5로 썼다(`last-run-tidy.json`은 11/7 05:16부터 생길 예정) |
| `status/verify-log.md` | 통화 목록 검증 | 검증 작업의 기억(숫자만) |
| `routines/hourly.md`, `routines/tidy.md` | release-apk | 예약 작업이 읽는 지시문 사본(원본 `docs/routines/`, 바뀐 것만 올림) |
| `tools/watchdog.py`, `tools/report_check.py` | release-apk | 루틴 안 워치독과 아침 보고서 검사기 사본(원본 `tools/inbox/watchdog.py`, `tools/report_check.py`) |
| `app/signing-lineage.bin` | release-apk(v3.8 r18-lock) | 서명 키 계보(옛 키 → 새 키, 인증서와 서명뿐, 비밀 아님). `IEUM_ROTATE=1`의 첫 배포가 한 번 만들고, 이후 모든 배포가 이것으로 서명한다. 회전 릴리스마다 같은 파일이 자산 `signing-lineage.bin`으로도 붙는다. **지우지 않는다**(5절 "서명 키 교체") |
| `status/secrets-scan.json` | release-apk(v3.8 r18-lock) | 비밀 스캔 결과 `{findings, tool, scope, commit, atMs, rules}`(gitleaks, 전체 이력, 값은 없음). 채점 8번이 `findings`를 읽는다 |

> **독립 검토(11/13)에서 더해진 주의:** (1) **키 교체를 켠 첫 배포는 폰에서 "설치" 확인을 한 번 눌러야 할 가능성이 크다** — 에뮬레이터(API 34) 리허설에서 옛 키 앱이 교체된 APK를 스스로 설치하려 하자 안드로이드가 설치 확인 화면을 띄웠다(실행 43337382903의 self-install 기록; 키 교체를 끈 경로는 탭 없이 됨). 영영 못 받는 것은 아니고 알림을 한 번 누르면 된다. 켜기 전에 아내에게 알린다. (2) 폰이 실제로 지나갈 경로("옛 키 release 앱이 서명 비교(ANCESTOR)를 거쳐 교체 APK를 직접 설치")는 리허설에 아직 없다 — 켜기 전에 update-smoke에 추가해 한 번 돌린다. (3) 카카오 SDK(2.24.0)는 키 해시를 가장 오래된 인증서로 만들기 때문에 교체 뒤에도 키 해시가 그대로일 것으로 보인다(SDK 바이트코드와 안드로이드 문서로 확인, 카카오톡 앱 로그인 경로는 미확인) — 새 해시 등록은 해롭지 않으니 순서에는 남긴다. (4) 수집 정책 지문(`AutomaticRecordingDiscovery.kt`, `NativeDiscoverySession.kt`의 정책 복사 목록)에는 `personalHashes`·`numberSalt`가 없다. 지금은 대체 목록이 같아 무해하지만, **목록에 키를 더하면 지문이 바뀌어 전체 다시 수집이 일어날 수 있으니**(저장소가 다시 불어난다) 손대려면 그 영향을 먼저 확인한다.

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
| `security` (v3.8 r18-lock) | `certCheck`(설치 전 서명 비교가 이 폰에서 인증서를 읽어 낸다), `certSelf`(시작할 때 자기 APK 판정: `SAME`, 회전 뒤 `ANCESTOR,SAME`, 못 읽으면 `UNKNOWN`), `saltedHash`(기기 소금과 개인 목록 이전 끝남), `legacyHashes`(원래 값이 없어 옛 방식으로도 대조하는 지문 수) | `certCheck=false`면 비교가 늘 통과(막지 못함). `update.error=CERT`면 받은 APK가 이 앱 키와 무관해 설치하지 않음(가짜 업데이트이거나 배포 서명 사고) |
| `update.certUnknown` (v3.8) | 인증서를 읽지 못해 비교 없이 통과시킨 다운로드 수 | 계속 오르면 이 폰에서 비교가 안 됨 |
| `consent` | 통화 정리 동의가 켜져 있는가 | false면 정리 요청이 올라가지 않음(앱 설정에서 켜기) |
| `folder` | 녹음 폴더가 골라져 있고 읽기 권한이 살아 있는가 | false면 새 녹음을 못 가져옴(앱에서 폴더 다시 고르기) |
| `notifyAllowed` | 알림 권한과 앱 알림이 켜져 있는가 | false면 저녁·아침 보고와 자가 진단 알림이 안 보임 |
| `batteryFree` | 배터리 최적화 예외인가 | false면 작업이 자주 미뤄짐 |
| `power` (v3.6, DESIGN_V6 3.1) | 자막 전력 장부(`transcriptionPower`, r16-1이 씀)를 숫자·코드로: `batteryPct`, `plugged`(ac, usb, wireless, none), `status`, `powerSave`, `thermal`, `headroom`, `tempDeciC`, `currentUa`, `cpuset`, `cpusAllowed`; `today`·`prev`{`day`(KST "yyyy-MM-dd" 문자열 — 숫자면 번호 누설 검사에 걸림), `battery`{runs, ms, min, audioMin, mAhScreenOff}, `plugged`{runs, ms, min, audioMin}, `budgetMin`(20), `budgetHit`(min, pct, none), `gapSkips`, `hotSkips`, `inCallSkips`, `morningSkips`, `lowSkips`, `powerSaveSkips`, `chargingOnlySkips`, `budgetSkips`, `plugDropped`, `levelFallingSkips`, `killedChunkMs`, `deadlineMs`, `yieldCount`, `yieldMs`, `modelLoads`, `modelLoadMs`, `decodeAudioMs`, `stopReasons`{코드: 수}}; `chunk`{coldMedianMs, p50Ms, lastMs, threads, tokensLast(null, r16-3 전), perTokenMsLast(null)}; `waiting`{n: 충전을 기다리는 녹음 수, oldestMin: 가장 오래 기다린 분}; `chargingOnly`(스위치 [충전 중에만 글자로 바꾸기]). 장부가 없으면 null·0으로 채워 늘 있음. **v3.6.1:** `today`·`prev`에 `hot`{headroom, temp, slow, severe}(달궈짐으로 쉰 사유별 수). **v3.7(r17-1 T5, r17-3):** `today`·`prev`에 `cpuMs`(자막 실행 동안 쓴 프로세스 전체 CPU 시간 ms, `/proc/self/stat`), `cpuRuns`(잰 실행 수), `batteryUah`(배터리 실행이 쓴 전하 µAh, `CHARGE_COUNTER` 차이), `fg`{runs, min, refused, timeout}(꽂힘 실행을 포그라운드로 돌린 횟수·분·시작 거부·시간 초과), `pieceMedianMs`{fg, bg, fgN, bgN}(그날 2분 조각 중앙값 ms를 포그라운드·백그라운드로 나눈 것과 표본 수); 맨 위에 `fgSetting`(`transcribeForeground` 설정: auto, on, off)과 `fgOn`(마지막 실행이 본 그날이 포그라운드 날인지; auto면 KST 날짜가 짝수인 날) | `today.battery.min` > 60이 2일이면 배터리를 많이 씀(예산 20분), `waiting.oldestMin` > 2160(36시간)이면 충전 대기가 쌓임, `killedChunkMs + deadlineMs`가 실행 ms의 20% 넘음이 2일이면 조각 낭비. `chunk.coldMedianMs` ≤ 280,000, `cpusAllowed` = `threads`가 확인 기준(DESIGN_V6 6절). T5 채택: 켠 밤 `pieceMedianMs.fg`가 끈 밤 `bg`의 70% 이하(DESIGN_V7 3.4), `fg.refused`·`fg.timeout`이 늘면 포그라운드가 막힘 |
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
| `updateAvailableCode`, `updateReadySinceMs`, `update.error` (v3.4) | 폰이 알고 있는 새 버전 번호, 받아 둔 뒤 기다린 시작 시각, 업데이트 오류 코드(v3.7: 확인 오류가 없으면 `blocked`·`noAnswer`도) | 24시간 넘게 설치 안 되면 워치독 이슈 |
| `update.bg`, `update.bgAtMs` (v3.7) | 백그라운드 설치·확인이 마지막으로 내린 결정 부호와 그 시각: `committed`, `sessionWaiting`, `sourceNotAllowed`, `notVerified`, `autoOff`, `wish`, `api`, `noFile`, `front`(앱이 앞에 있어 화면이 설치), `userAction`, `blocked`, `exception:<클래스>`, 세션이 30분 넘게 응답이 없으면 `noAnswer` | `committed`가 오래 그대로면 무인 설치가 조용히 멈춘 것 |
| `update.sessionOpen`, `update.autoInstall` (v3.7) | 설치 세션이 지금 열려 있는지, 스위치 "새 버전은 자동으로 설치" | |
| `update.open`, `update.openInstalls` (v3.7) | 앱을 열 때의 마지막 결정 부호(`start`, `replace`, `inFlight`, `bgFresh`, `downloading`, `inCall`, `declined`, `tried`, `blocked`, `backoff`(24시간 물러남), `failed`, `noAnswer`, `autoOff`)와 앱을 열어 시작한 설치 횟수 | |
| `update.blockAsk`, `update.blockOpen`, `update.blockStatus`, `update.blockCode`, `update.autoBack` (v3.7) | "보안 위험 자동 차단" 시트를 띄운 횟수, [설정 열기] 횟수, 앱을 열어 시작한 설치가 실패한 상태 번호(막힘 2), 막힘 메시지의 부호 부분(예: `INSTALL_FAILED_VERIFICATION_FAILURE`, 원문은 남기지 않음), 설치 뒤 돌아온 방식(`front` 앱이 스스로 열기 시도, `notice` 알림만, `manual` 사람이 돌아옴, `retry` 설정 화면에 있는 동안 다시 시도한 설치가 움직임); `update.blockRetries`는 그 다시 시도 횟수 | 삼성 자동 차단이 실제로 어떤 번호를 주는지 배포 뒤 판정 |
| `lastInboxErrorSinceMs` | 같은 우편함 오류 코드가 이어진 시작 시각(오류가 있을 때만) | 2시간 넘으면 워치독 이슈 |
| `resultChain` | 결과 받기 사슬의 지금 회차(`round`, 0이면 끝)와 다음 시각(`nextMs`) | |
| `callLogMatch` | 녹음과 통화기록 짝짓기 통계(숫자만). v3.7: `callLogMatch.day`{day, runs, 사유별 수(noTime, noCandidate, length, nameNull, nameDiffers, numberDiffers, several, matched, byEnd, byParty, records24h, open24h)} — 그날 패스 중 한 번에 본 가장 큰 수(같은 대기 기록이 패스마다 다시 세어지므로 합이 아님), `runs`는 본 패스 수 | 녹음 연결 91% → 95%의 재료(`noCandidate`·`length`) |
| `callCard` (v3.0, v3.4, v3.6.1) | 통화 카드 횟수: `shown`, `shownOutgoing`, `eligibleOutgoing`, `noCard`, `thin`, `noNumber`, `locked`, `cold`, `fgsRefused`, `noPermission`, `expanded`, `ringToCardMs` 등과 오늘 묶음 `today`. 통화를 본 적 없으면 null. **v3.6.1(DESIGN_V7 K1~K3):** `drawn`(창이 실제로 화면에 그려짐 — 채점 항목 3은 이것만 셈), `fallback`(창을 못 붙였거나 숨겨졌거나 보이기 전에 통화가 끝나 알림으로 대신함), `hidden`(fallback 중 예외 없이 붙었는데 3초 안에 안 보인 것), `unconfirmed`(권한 검사는 거짓인데 보인 것 — 그려진 쪽으로 세지 않고 알림 유지), `recent[]`(최근 20통화, 한 통화에 `day`, `at`(시:분), `result`, `cause`(ok, okRetry, drawnDespiteFalse, hidden, `addView:<예외>` 등), `canDraw`, `canDrawAfter`, `mode`(AppOps 원래 모드), `pid`, `ageS`, `cold`, `locked`, `car`, `btAudio`, `commDevice`, `drawMs`, `error`, `out`만) | `today.fallback + today.unconfirmed`(옛 앱은 `noPermission`)가 하루 3번 이상이면 워치독 이슈 "통화 카드가 통화 순간에 뜨지 않아요"(원인 코드는 `recent[]`) |
| `permissions` (v3.1, v3.6.1) | `phoneState`, `overlay`, `redirectRole`, `installAllowed`; v3.6.1부터 `pid`(하트비트를 쓴 프로세스 번호), `processAgeS`(프로세스 나이 초), `overlayMode`(`SYSTEM_ALERT_WINDOW` AppOps 원래 모드: 0 허용, 1 무시, 2 거부, 3 기본, 4 포그라운드, -1 못 읽음) — 카드 `recent[]`의 `pid`·`mode`와 맞춰 봄 | `phoneState=false` 24시간이면 워치독 이슈 |
| `recordingGap`, `recordingGapVerdict` (v3.1) | 최근 하루 통화와 녹음 파일·연결 수, 판정 코드(`ok`, `noRecording`, `partial`, `noMatch`, `folder`, `tooMany`) | `ok`가 아닌 같은 판정이 2일이면 워치독 이슈 |
| `followup`, `results`, `storeOpenError`, `transcriptionFailed`, `droppedItems`, `duplicateSkipped`, `latency` (v3.1) | 녹음 없음 알림 수, 결과 적용 실패·bad·받고 못 적용한 수, 저장소 열기 오류 코드(있을 때만), 자막 실패 수, 근거 부족으로 버린 수, 중복 건너뜀 수, 통화 끝→결과 지연 분위수(7일 이동) | `latency`의 옛 분위수는 기록 시각 오염 진단 전까지 판단 근거로 쓰지 않음. v3.7 값은 아래 `latency` 줄 |
| `transcription` (v3.2) | 자막 실패 사유별 수, 길이 구간별 대기·완료, `long48h`(100초 넘는 통화 완료율), `oldestWaitMin`, 실행 수 | `long48h.ratePct` 80 미만 24시간이면 워치독 이슈 |
| `latency.segments`, `latency.day`, `latency.over7d` (v3.7 r17-3) | 옛 `latency` 안에 더해짐. `segments`{`endToText`(통화 끝→자막 끝), `textToUpload`(자막 끝→요청 올림), `uploadToApplied`(올림→결과 적용)}, 각각 {p50Min, p90Min, n, over7d}(최근 7일 결과가 적용된 통화, 처음 정리 제외; 7일 넘는 값은 버리지 않고 `over7d`로 셈). `day`{p50Min, p90Min, n, waiting}: 오늘(KST) 끝난 통화만의 통화 끝→결과 적용 분 — 아직 결과가 없는 통화도 지금까지 기다린 시간으로 넣고(하한) `waiting`이 그 수. `over7d`: 통화 끝→적용이 7일 넘은 수 | 채점 항목 2(`day`). 가장 긴 구간이 어디인지는 `segments`와 `trace` |
| `trace[]` (v3.7) | 최근 50통화(통화 끝 기준, 오래된 것부터)의 단계 시각: `endMin`(그날 0시부터 분), `day`, `durS`, `foundS`(녹음 발견), `textStartS`, `textEndS`, `uploadS`, `appliedS` — 모두 통화 끝에서 몇 초 뒤인지, 못 간 단계는 null. id·상대 정보 없음 | 지연이 가장 긴 단계를 하나로 특정(DESIGN_V7 3.4) |
| `transcription.window14` (v3.7) | {created, failed, failedPermanent, days(14)}: 최근 14일에 대기열에 들어온 녹음 수, 그중 FAILED로 끝난 서로 다른 녹음 수, 그중 영구 실패 수(누적 `transcriptionFailed`는 재큐 때마다 부풂) | 채점 항목 9 |
| `golden`, `guard` (v3.3) | 골든 측정 점수, 숫자 가드 계수(숫자만, 측정 전 null) | |
| `recordTime.fileVsRecordMin` (v3.4) | 기록 시각 차이 구간별 수 | |
| `messages.mailError` (v3.4, **v3.7에서 지움**) | 메일 오류 코드별 수였다. 메일 기능을 지우면서 `messages` 묶음이 하트비트에서 빠졌다 | |
| `kakao`, `report` (v3.5) | `kakao{on, linked, sent, failed, lastSentMs, error, texts(마지막 보고가 몇 통이었나, v3.7 빌드 1052), plainRetried(링크 없는 글이 안 가서 앱 링크로 다시 보낸 누계), **r18:** style(마지막 보고가 `list` 리스트 말풍선, `text` 글, `picture` 그림; 보낸 적 없으면 null), cards(마지막 보고의 리스트 말풍선 수), listFallback(리스트가 안 가서 글로 대신 보낸 누계), web(마지막 보고에 [웹으로 보기]가 붙었나), tags{important, confirm, request, regular, optional}(마지막 보고의 챙길 일 분류별 건수, 숫자만)}`: 스위치 "카카오톡으로 받기", 연결, 다 나간 날 누계, 실패 누계, 마지막으로 다 나간 시각, 오류 코드. `report{day, source}`: 마지막으로 보낸 보고 날짜와 출처(`routine` 새벽 정리 보고서, `phone` 06:00 폰 폴백). **v3.7:** `report.coverage`{day(보고 날짜), yesterdayCalls(녹음 있고 개인 번호 아닌 어제 통화), inReport(보고에 들어간 수, 폰 폴백이면 0), pending(아직 글자로 바꾸거나 분석 중인 수), atMs, source}(마지막 보고, 없으면 null), `report.recent[]`{day, yesterdayCalls, inReport, pending}(최근 7일 보고, 오래된 것부터) | `linked=true`인데 `lastSentMs`가 이틀 아침 연속 오늘이 아니면, 또는 `linked=false`에 `error`가 `TOKEN_*`이면 워치독 이슈. `inReport / yesterdayCalls` < 80%가 평일 이틀 연속이면 워치독 이슈 "아침 보고에 빠진 통화가 많아요" |
| `use` (v3.7) | {today, prev}: 그날(KST) 사용 횟수 {day, opens(앱을 앞으로), homeSec(홈을 본 초), taskDone([다 됐어요]), notifTap(알림으로 엶), cardExpand(카드 띠 펼침 — 카드 자체 집계 `callCard.today.expanded` 값을 읽음), kakaoOpen(카톡 보고 버튼으로 엶)}. `prev`는 전날(앱을 안 연 날은 0, 첫 집계 전은 null). 내용·시각 없음 | 채점 항목 10(v4.0) |
| `feedback` (v4.0) | {wrong, undone}: 체크할 일의 [틀렸어요]를 누른 수와 그것을 [되돌리기]한 수, v4.0 설치 뒤 누계(숫자만, 어떤 일인지 없음). 틀린 일의 id는 새벽 정리 state의 `wrong[]`(최근 14일)로만 간다. v4.0 전 앱에는 없음 | 채점 항목 4·10의 실사용 보조 지표. 주 1회 집계(7절) |
| `dbTables` (v3.7) | 봉인 저장소의 표별 크기: {day, atMs, totalRows, totalChars, top[]}, `top`은 글자 수(다음 행 수) 큰 표 8개 {name, rows, chars, dChars(지난 집계 뒤 글자 수 변화, 처음은 null)}. 표 이름만, 내용 없음. 첫 집계 전 null | v3.8 저장소 작업의 재료. `dbPercent` ≥ 70이면 워치독 이슈 본문에 큰 표 3개 |
| `dbPrune` (v3.8) | 마지막 수집 원장 정리(하루 한 번, 업데이트 뒤 첫 실행): {day, atMs, deleted{표 이름: 지운 행 수}(`discovery_intake_requests` 같은 내용 확인 요청, `vault2_pending` 끝난 금고 접수), charsBefore, charsAfter(전체 글자 수, 건너뛰면 null), skipped(건너뛴 사유: `IN_CALL` 통화 중, `QUEUE_UNREADABLE` 수집 줄을 못 읽음, `COPY_FAILED` 사본 실패(3일 이어지면 사본 없이 정리), `NO_LEDGER` 원장 없음; 정리했으면 null. 건너뛰면 1시간 뒤 다시, 하루 최대 6번), ms(봉인까지 걸린 시간), copyAtMs(정리 전 사본을 만든 시각, 사본이 없으면 null), copy(`made` 이번에 만듦, `kept` 보관 중, `deleted` 7일 지나 지움, `none` 사본 없이 정리함, null 정리 전)}. 같은 날 뒤에 끝난 "지운 줄 0"이나 건너뜀은 그날 앞의 실제 삭제 결과를 덮지 않는다. 첫 정리 전 null | `dbTables`는 정리 뒤 다시 센 값. 워치독 저장소 이슈 본문에 한 줄. **판정 시점:** 첫 정리는 직전 3일치를 남기므로 `dbPercent`가 30 근처로 내려가는 것은 업데이트 3~4일 뒤다 |
| `dbFull` (v3.8) | 통화 봉인 저장소가 **전체 한도**(16MiB·10만 행) 때문에 쓰기를 거절한 기록(`TEXT_STORE_CAPACITY_REQUIRES_REVIEW`, 호출부가 삼켜도 셈): {count(누계), firstMs, lastMs}. 거절이 없으면 count 0 | `lastMs`가 24시간 안이면 워치독 이슈, 폰 신호등 "통화 정리" 줄 주황 "저장 공간이 다 찼어요" |
| `dbRowTooBig` (v3.8) | 그 밖의 크기 거절(한 행 1MiB 초과, 또는 카드 파일 등 다른 봉인 저장소): {count, lastMs} | 신호등·워치독에는 쓰지 않음(원인 조사용) |

v3.1 이전 앱(11/6 아침 폰은 v3.1, 1040)은 v3.2 이후 키(`transcription`, `update`, `messages`, `recordTime`, `golden`, `guard`)를 보내지 않는다. v3.6 이하 앱은 v3.6.1·v3.7 키(`callCard.drawn` 등, `latency.segments`·`day`, `trace`, `use`, `dbTables`, `report.coverage`)를 보내지 않고, v3.7 앱은 `messages`를 보내지 않으며 v3.8 키(`dbPrune`, `dbFull`)도 없다. 워치독은 키가 없으면 그 규칙을 건너뛴다.

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
| 폰 저장소를 70% 넘게 쓰고 있어요 (v3.7) | `dbPercent`가 70 이상 80 이하(80 넘으면 위 이슈 하나만). 본문에 `dbTables.top`의 큰 표 3개 v3.8부터 `dbPrune`의 마지막 정리 한 줄도 |
| 폰 저장소가 다 차서 저장이 안 돼요 (v3.8) | 하트비트 `dbFull.lastMs`가 24시간 안(저장소가 크기 때문에 쓰기를 거절함). 본문에 누계와 `dbPrune`의 마지막 정리 한 줄(지운 행 수나 건너뛴 사유) |
| 우편함 오류가 계속돼요 | 같은 `lastInboxError`가 `lastInboxErrorSinceMs`부터 2시간 넘음 |
| 통화 카드가 통화 순간에 뜨지 않아요 (v3.6.1, 전 제목 "통화 카드 권한이 꺼져 있어요") | 하트비트 `callCard.today.fallback + unconfirmed`(옛 앱은 `noPermission`)가 하루 3번 이상(잠금이라 알림만 띄운 `locked`는 세지 않음). 본문에 `drawn` 수와 `recent[]`의 최근 원인 코드 |
| 업데이트 실패 | `lastUpdateError`가 있음(단 `USER_ACTION_REQUIRED`, `INSTALL_SOURCE_NOT_ALLOWED`는 아래 옛 버전 규칙이 말함) |
| 폰이 옛 버전에 머물러 있어요 | `app/version.json`의 versionCode가 하트비트 `versionCode`보다 높고, version.json이 마지막으로 바뀐 커밋부터 24시간 넘음(하트비트가 12시간 안일 때만) |
| 루틴 지시문이 옛 버전이에요 | 루틴별 자가 보고의 `promptVersion`이 version.json의 `promptVersion`(매시간)·`tidyVersion`(새벽)과 다름. version.json이 바뀐 뒤 끝난 실행만 본다 |
| 전화 상태 권한이 빠졌어요 | 하트비트 `permissions.phoneState=false`가 24시간(v3.1~ 하트비트만) |
| 통화가 녹음되지 않고 있어요 / 통화가 일부만 녹음되고 있어요 / 녹음 폴더를 읽지 못해요 / 녹음 폴더에 파일이 너무 많아요 / 녹음은 있는데 통화기록과 이어지지 않아요 | `recordingGapVerdict`가 `noRecording` / `partial` / `folder` / `tooMany` / `noMatch`로 2일(48시간) 같음 |
| 긴 통화의 자막이 끝나지 않아요 | `transcription.long48h.ratePct`(100초 넘는 통화 완료율)가 80% 아래로 24시간 |
| 분석이 멈췄어요 | inbox/의 가장 오래된 요청이 루틴이 도는 시간(03~05시와 07~23시, v3.7. 전에는 07~23시)으로만 세어 3시간 넘음(파일이 처음 커밋된 시각 기준) |
| 루틴이 일을 안 해요 | 매시간 자가 보고의 `processed`가 0인데 inbox에 요청이 있음(한도에 걸린 실행은 제외) |
| 루틴이 한도에 걸렸어요 | 매시간 자가 보고의 `limitHit`가 true |
| 루틴 지시문 버전이 달라요 | 매시간 자가 보고의 `promptMismatch`가 true |
| 매시간 분석이 예정 시각에 돌지 않았어요 | `status/last-run-hourly.json`(또는 옛 `last-run.json`)의 "status" 커밋이 자가 보고 cron상 2슬롯 연속 없음(슬롯 40분 뒤부터 셈, analyze 커밋 기준이 아니다. 조용한 날엔 원래 없다). 매시간 자가 보고가 한 번도 없으면 건너뜀 |
| 루틴이 결과를 저장소에 올리지 못했어요 | 매시간·새벽 자가 보고의 `pushed`가 false |
| 우편함 토큰이 곧 만료돼요 | 폰이 올린 `tokenExpiresMs`가 14일 안(없으면 건너뜀) |
| 아침 보고서가 안 만들어졌어요 (v3.5) | 한국 시각 05:30이 지났는데 `report/<오늘>.json`이 없음(파일이 있는지만 봄). 새벽 정리 자가 보고 `promptVersion`이 7 이상일 때만. 새벽 정리(v3.7부터 05:05, 전에는 04:46)의 워치독은 05:30 전이라 실제로는 07:34부터의 매시간 워치독이 본다. 본문에 `reportWritten` |
| 카카오톡 보고가 안 갔어요 (v3.5) | 하트비트 `kakao.on`이 참(키가 없으면 참으로 봄), `kakao.linked=true`, 09:00이 지났는데 `lastSentMs`가 어제 00:00 전(두 아침 연속), 어제 09:00 전부터 연결돼 있었을 때만 |
| 카카오톡 연결이 풀렸어요 (v3.5) | `kakao.on`이 거짓이 아니고 `kakao.linked=false`, `kakao.error`가 `TOKEN_`으로 시작 |
| 폰 배터리를 많이 써요 (v3.6) | 하트비트 `power.today.battery.min`이 60분 넘고, 같은 하트비트의 `power.prev`나 다른 날의 이전 하트비트 `today`도 60분 넘음(2일, 하루 예산 20분의 3배) |
| 충전을 기다리는 녹음이 쌓여요 (v3.6) | `power.waiting.n` > 0이고 `power.waiting.oldestMin`이 36시간(2160분) 넘음. 이 녹음들은 "자막이 밀려요"에서 빼고 센다(`newQueuedCount`가 `waiting.n`보다 많을 때만) |
| 자막 조각이 버려지고 있어요 (v3.6) | `power.today`와 `power.prev` 모두 `killedChunkMs + deadlineMs`가 실행 ms(`battery.ms + plugged.ms`, 없으면 분 × 60,000)의 20% 넘음 |
| 아침 보고에 빠진 통화가 많아요 (v3.7) | 하트비트 `report.coverage`·`report.recent[]`(와 지난 하트비트)로 본 가장 최근 보고와 그 앞 평일 보고가 모두 `inReport / yesterdayCalls` < 80%(통화 날짜 기준 평일 이틀 연속, 주말은 건너뜀). 가장 최근 보고가 어제 보고보다 오래됐으면 건너뜀 |

위가 `tools/inbox/watchdog.py`의 규칙 전부다(11/13, v3.5 규칙 3개, v3.6 규칙 3개, v3.7 규칙 2개, v3.8 규칙 1개 포함 이슈 제목 34개; v3.6.1에서 카드 규칙 제목이 바뀜).

토큰 이슈가 열리면: 새 토큰을 만들어 앱 저장소 비밀 `IEUM_INBOX_TOKEN`을 바꾸고 배포한다. 만료 뒤에는 폰이 업데이트도 못 받으므로 만료 전에 해야 한다. **키 교체(5절 "서명 키 교체")를 끝내기 전에는 토큰을 바꾸지 않는다**: 그 전에는 서명 키가 토큰에서 나오므로(`IEUM_SIGNING_SEED`는 비어 있음) 토큰을 바꾸면 서명이 바뀌어 업데이트가 막힌다. 교체가 끝난 뒤(`app/version.json`의 `seedIndependent=true`를 폰이 설치)에는 서명이 토큰과 무관하다. 폰이 새 버전을 받고 다음 하트비트를 올리면 이슈는 저절로 닫힌다.

설치와 확인(배포 때, 개발 세션):
1. 복사는 release-apk가 한다(`tools/inbox/watchdog.py` → `tools/watchdog.py`, `docs/routines/hourly.md`·`tidy.md` → `routines/`, 바뀐 것만, version.json보다 먼저). 배포 없이 급히 바꿀 때만 손으로 ieum-inbox에 커밋한다.
2. 예약 작업 환경에 ieum-inbox 이슈 쓰기 권한(gh 인증)이 있는지 본다. 없으면 워치독 단계만 건너뛰고 루틴은 계속 돈다.
3. 수동 확인: ieum-inbox를 받은 폴더에서 `--apply` 없이 `python3 tools/watchdog.py --root . --out /tmp/ieum-watchdog-plan.json`을 돌리면 이슈를 열지 않고 지금 보이는 문제 목록만 쓴다. 실제 반영은 예약 작업 실행 기록의 마지막 줄 "watchdog: n opened, m closed"와 `gh issue list -R <owner>/ieum-inbox -l ieum-watch`로 본다.
4. 이슈 본문은 고정 문장과 시각, 개수, 참거짓뿐이다. 우편함 파일 내용은 읽지 않는다.

### 카카오 리스트 보고 설정 (r18, 사용자가 카카오 개발자 콘솔에서 할 일)

빌드는 두 저장소 변수(비밀이 아님, GitHub 저장소 Settings > Secrets and variables > Actions > Variables)를 읽는다: `IEUM_KAKAO_WEB_BASE`(웹 보기 주소, 비면 [웹으로 보기] 없음)와 `IEUM_KAKAO_LIST_TEMPLATE_ID`(사용자 정의 리스트 템플릿 ID, 비면 기본 리스트 3개씩 top 6). 템플릿은 웹 주소가 있을 때만 쓰이므로 **웹 도메인을 먼저**, 템플릿을 다음에 한다. 둘 다 다음 release-apk 빌드부터 들어간다.

웹 도메인 등록(웹 보기를 켤 때):
1. 오케스트레이터가 사용자에게 알린 뒤 앱 저장소의 GitHub Pages를 켠다(기본 브랜치의 `/docs`). 보기 페이지는 `docs/report/index.html`이므로 주소는 `https://<계정>.github.io/ieum-app/report/`이다. 이 페이지가 기본 브랜치에 합쳐져 있어야 한다.
2. 카카오 개발자 콘솔(developers.kakao.com) > 내 애플리케이션 > 이음 앱 > [앱] > [제품 링크 관리] > [웹 도메인]에 `https://<계정>.github.io`를 등록한다(카카오 문서: 등록되지 않은 도메인은 사용할 수 없다).
3. 저장소 변수 `IEUM_KAKAO_WEB_BASE`에 `https://<계정>.github.io/ieum-app/report/`(끝의 `/`까지)를 넣는다.
4. 카카오가 웹 버튼이 붙은 말풍선을 거부하면 폰은 웹 버튼 없이 한 번 더 보낸다(하트비트 `kakao.web=false`로 보임).

사용자 정의 리스트 템플릿(항목 5개, top 10을 보낼 때):
1. 콘솔 [도구] > [메시지 템플릿](`https://developers.kakao.com/tool/template-builder/app`)에서 이음 앱을 고른다(템플릿은 앱별이고 다른 앱의 ID는 쓸 수 없다).
2. 새 템플릿을 **리스트형**으로 만든다.
3. 헤더 제목에 `${H}`를 적는다. 헤더 링크는 앱 실행(Android 실행 파라미터 `screen=tasks`).
4. 아이템 리스트에 아이템을 **5개** 만들고 n번째(1~5) 아이템에 제목 `${Tn}`, 설명 `${Dn}`, 이미지는 [사용자 인자]로 `${In}`를 넣는다(카카오 문서: 이미지를 사용자 인자로 넣으면 보낼 때 이미지 URL을 준다, 리스트 아이템 이미지 최대 800×800, 5MB). 아이템 링크는 공통 링크(앱 실행 `screen=tasks`).
5. 버튼을 2개 둔다. 버튼 1 "웹으로 보기": 개별 링크, 모바일 웹과 PC 웹 모두 `https://<계정>.github.io/${WEB}`(카카오 문서: 웹 링크의 도메인이나 경로에 사용자 인자를 쓸 수 있다). 버튼 2 "앱으로 보기": 개별 링크, Android 앱 실행 파라미터 `screen=tasks`.
6. 저장한 뒤 템플릿 설정 화면의 [메시지 템플릿 정보]에 나오는 템플릿 ID를 저장소 변수 `IEUM_KAKAO_LIST_TEMPLATE_ID`에 넣는다.
7. 인자 이름은 영문, 숫자, `_`만 된다. 폰이 보내는 값: `H` 머리, `T1`~`T5` 제목, `D1`~`D5` 요청일과 업체, `I1`~`I5` 분류 그림 URL(올리기 실패면 빈 값), `WEB` 웹 보기 주소의 도메인 뒤 경로와 `#d=` 조각(예 `ieum-app/report/#d=` 뒤에 조각). 확인하지 못한 것: 인자 값에 `#` 조각이 그대로 살아 링크가 되는지, 빈 이미지 인자가 어떻게 보이는지, 콘솔의 정확한 버튼 문구. 첫 아침에 말풍선을 눌러 보고, 안 되면 템플릿 ID를 비우면 기본 리스트(3개씩)로 돌아간다.

### 엔진 측정대와 측정 표본 (r18-bench, DESIGN_V7 4.4 — 관리자가 우편함에서 켬, 아내 화면에는 아무것도 안 보임)

**켜는 법:** 우편함(ieum-inbox) 기본 브랜치에 `app/config.json`을 둔다. 폰은 하트비트(매시간 틱) 때 이 파일을 한 번 읽는다. 파일이 없거나(404) JSON이 깨졌거나 블록이 없으면 **꺼짐**(기본). 네트워크 오류면 그 시간에는 아무것도 바꾸지 않는다. 켜고 끄는 데 폰을 만질 일은 없다.

```json
{"bench":{"on":true,"candidates":["b","c"],"calls":20,"run":1},
 "samples":{"on":true,"calls":12,"maxMinutes":40}}
```

| 키 | 뜻 |
| --- | --- |
| `bench.on` | 측정대 켜기(JSON 참값 `true`만 켜짐, 문자열 "true"는 꺼짐). false로 바꾸면 다음 하트비트에 작업을 멈추고 받은 측정용 모델(`bench-models/`, 최대 약 1.1GB)을 지운다. 숫자는 남는다(`state:"OFF"`) |
| `bench.candidates` | `a`(지금 q5_0을 같은 틀로 다시 잼, 오래 걸림), `b`(q8_0, 지금 라이브러리, 포그라운드), `c`(SenseVoice int8 + Silero VAD). 순서와 상관없이 c → b → a로 돈다. 기본 `["b","c"]` |
| `bench.calls` | 잴 통화 수(1~40, 기본 20: 1~5분 최신 18건 + 10분 넘는 최신 2건) |
| `bench.run` | 숫자를 바꾸면 처음부터 다시 잰다(같은 숫자면 멈춘 곳에서 이어서) |
| `bench.threads` | 선택. `{"b":6,"c":2}`처럼 후보별 스레드(기본 b·a는 충전 중 자막 실행과 같은 6, c는 2) |
| `bench.fgMinutes` | 선택. b·a가 포그라운드로 도는 하루 분(기본 60, 앱 자막 실행과 시스템 한도를 나눠 쓰므로 작게) |
| `samples.on` | 측정 표본 올리기 켜기(아래). false로 바꾸면 다음 하트비트에 올린 자산·릴리스·태그를 모두 지운다 |
| `samples.calls`, `samples.maxMinutes` | 켜져 있는 동안 올릴 녹음 수(1~30)와 합계 분(1~120) 상한 |

**도는 때:** 측정은 충전 중, 통화 아님, 발열 moderate 미만, 자막 대기열 0, 앱의 자막 작업이 대기·실행 중 아닐 때만. 2초마다 다시 보고 하나라도 깨지면 그 조각을 버리고 멈춘다(끝난 조각은 남음, 10분 뒤 다시). 모델은 켜졌을 때만, 충전 + 와이파이 + 받은 뒤에도 2GB 넘게 남을 때만 받는다(SenseVoice 약 240MB, q8_0 874MB, 이어받기). 하룻밤에 다 못 해도 된다.

**하트비트 `bench`** (`status/phone.json`, 한 번도 켜진 적 없으면 키가 없음. 숫자·부호만):

```json
"bench":{"on":true,"run":1,"state":"RUNNING","code":null,"atMs":0,"planned":20,"candidates":["c","b"],
 "models":{"c":"READY","b":"DOWNLOADING"},
 "cand":{"c":{"calls":12,"audioMin":41.5,"wallRtf":0.05,"cpuRtf":0.09,"cpuSecPerAudioMin":5.4,
   "cerPct":12.3,"refChars":9876,"numberPct":91.0,"numbers":120,"namePct":88.0,"names":25,
   "halluc":3,"hallucPerCall":0.25,"fgSharePct":0,"failed":0,"fail":{},"inProgress":1,
   "mAh":null,"energyBasis":"cpu",
   "byLen":{"m1to3":{"calls":8,"audioMin":16.0,"wallRtf":0.05,"cpuRtf":0.09,"cerPct":11.0,"hallucPerCall":0.3},"m10plus":{}}},
  "b":{}}}
```

- `state`: `WAITING`(`code`가 이유: `NOT_PLUGGED`, `IN_CALL`, `HOT`, `TRANSCRIBING`, `QUEUE`, `NO_WIFI`, `NO_SPACE`, `MODEL_RETRY`, `MODEL_FAILED`, `NO_CALLS`, `STOPPED`, `MORE`), `DOWNLOADING`, `RUNNING`, `DONE`(모델 지움), `OFF`.
- `wallRtf` = 처리 벽시계 ÷ 녹음 길이, `cpuRtf` = 프로세스 CPU 시간(엔진의 모든 스레드 합) ÷ 녹음 길이. **5.2 규칙의 "폰 실시간 배수"는 `wallRtf`**(1.0 = 녹음 1분에 1분).
- `cerPct`는 (가) 지금 엔진의 저장 자막을 기준으로 한 정규화 글자 오류율(은 골든 — 정확도가 아니라 "지금 엔진과 얼마나 같은가"). 조각마다 그 조각 안에서 시작한 자막 줄과 비교한다. 자막 줄 시각이 줄 수와 맞지 않는 통화는 품질 숫자에서 빼고 `noTimesCalls`로 따로 센다(속도는 셈). `numberPct`·`namePct`는 기준 자막의 숫자 토큰·장부 이름 중 후보 자막에도 있는 비율.
- `halluc`은 1초 미만 말소리 구간에서 1~2글자가 나왔는데 **그 시각에 기준 자막에 글이 없을 때만** 센 수(실제 "네"는 세지 않음). `fgSharePct`는 포그라운드에서 잰 녹음의 비율(b·a, (다)는 포그라운드로 올리지 않음), `threads`는 그 후보가 쓴 스레드 수, `foreground`는 포그라운드에서 잰 조각이 있었는가. (다)를 (나)와 같은 스레드로 한 번 더 재려면 `{"bench":{"on":true,"run":2,"candidates":["c"],"threads":{"c":6}}}`. `cpuRtf`는 프로세스 전체 CPU(같은 프로세스의 다른 일도 섞임 — 측정 중에는 자막·카드가 돌지 않으므로 작지만 상한으로 읽는다. 엔진 스레드만 따로 재지는 못함: whisper·onnxruntime이 스레드를 스스로 만들고 거둠).
- `disabled`/`crashes`: 네이티브 실행 중 프로세스가 죽으면(크래시·메모리 부족) 다음 실행이 그 표식을 보고 세며, 2번이면 그 후보를 `BENCH_CRASH`로 끝낸다. 같은 조각이 시스템 10분 한도로 두 번 끊기면 `BENCH_TIMEOUT`, 고정 sha와 다른 모델 파일이 두 번이면 `MODEL_SHA`. 측정대는 앱과 같은 프로세스에서 돈다(별도 `:bench` 프로세스는 WorkManager 다중 프로세스 의존성·매니페스트 서비스·앱 시작 코드 변경이 필요해 하지 않음).
- 충전 중에만 재므로 `mAh`는 늘 null이고 `energyBasis:"cpu"` — 전력은 `cpuSecPerAudioMin`으로 비교한다(배터리 실행 mAh는 v3.9 배포 뒤 `power` 블록으로).
- `fail`은 포기한 통화의 부호별 수(`BENCH_C_NO_RUNTIME` = 이 폰 ABI에 sherpa 라이브러리 없음, `BENCH_C_RUNTIME_PIN` = 라이브러리 sha 불일치, `NO_REFERENCE` = 기준 자막이 지워짐, `ANDROID_STT_*` = whisper 실패).

**5.2 규칙으로 읽는 법:** 규칙 1 — `cand.b.wallRtf ≤ 1.0`이고 `fgSharePct`가 높을 것(포그라운드 실측), 그리고 128분 날 전력이 배터리 5% 이하인지는 `cpuSecPerAudioMin × 128`을 v3.7 `power`의 배터리 실행 CPU·`batteryUah` 비율로 환산해 본다(직접 mAh는 이 측정대로 못 잼). 규칙 2 — `cand.c.wallRtf ≤ 0.5`, `cand.c.cerPct ≤ 15`, `cand.c.hallucPerCall ≤ 1`. 길이 구간(`byLen`)에서 10분 넘는 통화가 따로 나빠지지 않는지도 본다. 규칙 3은 금 골든(사람 정답 또는 아래 표본으로 만든 기준 자막)이 있어야 한다.

**측정 표본(9번, 사용자 결정 11/13 — 녹음이 폰 밖으로 나가는 유일한 길):** `samples.on`이면 폰이 측정대와 같은 기준의 업무 통화 녹음(개인 판정·개인 번호·제외 번호 빼고, 최근 7일, 1~5분 먼저, 10분 넘는 것 최대 2건, 합계 `maxMinutes` 이하, 같은 녹음은 한 번만)을 **충전 + 와이파이 + 통화·자막 실행 아님**일 때 하나씩 우편함 릴리스 `bench-samples`(없으면 폰이 만듦, 시험판 표시)의 자산으로 올린다. 커밋이 아니므로 저장소 이력에 남지 않는다.
- 자산 이름: `s_<무작위 8자>.wav`와 `s_<같은 id>.json`. 녹음은 원본 파일을 그대로 올리지 않고, 앱의 디코더로 푼 PCM을 **16kHz 모노 16비트 WAV**로 다시 만든다(묵음을 지우기 전 전체 길이). 원본 m4a의 태그(상대 이름·번호가 있을 수 있음)와 원본 파일 이름은 따라가지 않는다. 크기는 녹음 1분에 약 1.9MB(기본 `maxMinutes` 40분이면 약 77MB).
- `s_<id>.json`: `format:"wav"`, `sampleRate`, `channels`, `durationS`, `lines[{text,startS,endS}]`(폰 자막, 우편함 요청과 같이 번호·계좌·메일 가림), `pieces[{startMs,ms}]`(폰 자막 실행과 같은 조각 경계, WAV 시각과 같은 축), `vad`, `engine`, `model`. 이름·번호·경로·통화 시각은 없다(자막 글 안에 말해진 이름은 그대로).
- **지워지는 때(서버 목록 기준):** 하트비트 때마다 폰이 태그로 릴리스를 찾아 자산 목록을 받고, ① 서버의 `created_at`이 7일 넘은 자산 ② 폰에 기록이 없는 자산(응답이 끊겨 다시 올린 것, 저장 전 프로세스 사망, 반쪽 업로드, 설정 유실 — 30분 유예 뒤) ③ 올린 뒤 개인 판정·개인 번호·제외 번호·통화 삭제로 대상이 아니게 된 통화의 자산(`revoked`)을 폰의 기록과 상관없이 지운다. `samples.on`을 false로 바꾸면 폰의 기록과 상관없이 태그로 릴리스를 찾아 자산·릴리스·태그를 모두 지우고, 서버가 빈 것을 본 뒤에야 묻기를 멈춘다(`cleared`). 설정에 `samples` 블록이 아예 없고 한 번도 켠 적 없는 폰은 아무것도 묻지 않는다. 에뮬레이터(ci)는 올리지도 지우지도 않는다. 작업 공간에서 받은 사본은 다 쓴 뒤 지운다.
- 하트비트 `samples{on,uploaded,minutes,deleted,failed,live,overdue,orphans,revoked,cleared,lastCode,deleteCode}`. `overdue` = 7일 넘었는데(또는 꺼졌는데) 지우지 못하고 남은 자산 수(0이 아니면 바로 볼 것), `orphans` = 지운 고아 수, `deleteCode` = 마지막 삭제 실패 부호(업로드 성공으로 지워지지 않고, 실패 없는 정리가 한 번 지나가야 지워짐). `lastCode`가 `SAMPLE_HTTP_403`/`404`면 우편함 토큰에 릴리스 쓰기 권한이 없는 것(폰에서 확인 못 함 — release-apk는 같은 비밀로 릴리스를 만든다). 실패는 녹음당 3번까지 다시 한다.
- 받는 법(작업 공간): `gh release download bench-samples -R <owner>/ieum-inbox -D <임시 폴더>`. 자막·녹음 본문은 숫자만 남기고 결과에 옮기지 않는다.

## 5. 배포와 업데이트 흐름

1. `feature/lean-calls`(또는 `main`)에 push한다. 앱 작업은 시험 브랜치에서 CI를 통과시킨 뒤 합친다.
2. `ci`(android-ci.yml, `feature/**`·`main` push 중 `android/**`, `tools/**`, ci·release 워크플로가 바뀐 것): 도구 시험(`tools/`, `tools/inbox/`, `tools/release/`의 파이썬 시험; 서명 키 교체 스크립트 시험은 임시 씨앗으로 실제 apksigner를 돌린다), 화면 규칙 검사, 내보내기 검사(`tools/scan_export.py`: 비밀 값은 실패, 개인 경로는 경고), 비밀 스캔(v3.8: `tools/secret_scan.sh`, gitleaks 8.24.3을 체크섬 확인 뒤 전체 이력에 돌림, `.gitleaks.toml`의 예외는 커밋 98ab599의 공개 시험 키 하나; 발견 1건 이상이면 실패, 도구를 못 받으면 경고), 업데이트 문구 알림(앱 코드가 바뀌었는데 `docs/UPDATE_NOTE.txt`가 그대로면 경고), JVM 단위 테스트와 lint(debug lint와 v3.8부터 `lintVitalRelease`).
3. `update-smoke`(update-smoke.yml): 같은 push에서 함께 돈다. 마지막으로 성공한 배포 이후 AndroidManifest.xml, IeumApplication, StartupReceiver, CallSinkImpl, 빌드 설정(*.gradle.kts, gradle.properties, Gradle wrapper) 중 하나라도 바뀌었으면(`tools/needs_update_smoke.sh`) 에뮬레이터에 직전 릴리스를 깔고 새 APK를 덮어 설치해 시작, 충돌 없음, 예약 작업 다섯 개 이상을 확인하고, 앱이 스스로 새 APK를 설치하는 경로도 본다(지금은 경고만, `IEUM_SELF_INSTALL_STRICT=1`이면 실패). 아니면 바로 끝난다. 에뮬레이터가 켜지지 않으면(러너 문제) 한 번 자동 재시도하고, 두 번 모두 안 켜지면 "Emulator did not boot (infra)" 단계가 실패로 남는다.
4. `release-apk`: 같은 커밋의 ci 통과를 기다린다(40분). 3의 조건에 해당하면 update-smoke의 "Install, update, start" 단계 통과도 기다린다(70분). 앱 설치·덮어 설치·실행 실패나 시간 초과면 배포하지 않는다. 에뮬레이터가 재시도 뒤에도 켜지지 않은 경우(인프라)만 경고를 남기고 배포한다. v3.8부터 빌드는 `assembleRelease`(디버깅 불가, 합성 도구 꺼짐, 축소 없음, debug와 같은 서명 설정)이다. 서명 키는 저장소 비밀에서 만들고(`IEUM_SIGNING_SEED`가 있으면 그것, 없으면 `IEUM_INBOX_TOKEN`에서; cryptography 50.0.2 고정), 직전 릴리스 APK와 서명 인증서 지문이 다르면 배포하지 않는다. 직전 APK를 못 받으면 version.json의 `certSha256`과 비교하고, 그것도 없으면 경고만 남긴다. `IEUM_ROTATE=1`이면 새 키(`IEUM_SIGNING_SEED_V2`)와 우편함의 계보로 다시 서명하고 계보 사슬을 검사한다(아래 "서명 키 교체"; 직전 배포를 전혀 알 수 없으면 경고가 아니라 배포 중지). 통과하면 ieum-inbox에 릴리스 `v<빌드 번호>`(빌드 번호 = 1000 + 실행 번호)를 만들고, 지시문 둘과 워치독을 `routines/`·`tools/`에 바뀐 것만 올린 뒤, `app/version.json`(versionCode, versionName=`docs/VERSION`, apk, sha256, size=APK 바이트 크기, notes=`UPDATE_NOTE.txt` 첫 줄 120자, promptVersion, tidyVersion, certSha256, v3.8부터 buildType, seedIndependent, 회전 서명이면 lineageSha256)을 올린다. 배포 뒤 비밀 스캔 건수를 `status/secrets-scan.json`에 올린다(실패해도 배포에는 영향 없음). 오래된 릴리스는 3개만 남긴다(정리 실패는 경고만). 트리거는 `feature/lean-calls`·`main` push 중 `android/**`, `tools/**`, release 워크플로가 바뀐 것과 수동 실행.
5. 폰은 결과 받기 때마다 `app/version.json`을 ETag로 읽고(처음 보는 높은 versionCode면 바로 확인), 6시간 주기와 앱을 열 때도 확인한다. 네트워크 종류와 상관없이 받고, 가능하면 누르지 않고 설치하며(API 31+, 설치 허용), 아니면 notes(= `docs/UPDATE_NOTE.txt` 첫 줄)를 알림 문구로 보여 준다. 이름은 `docs/VERSION`(사용자에게 보이는 변경마다 0.1씩), 빌드 번호는 업데이트 순서에만 쓴다.

수동 배포(release-apk 수동 실행)는 update-smoke 실행이 없으면 경고만 남기고 진행한다.

에뮬레이터(update-smoke, device-test)에서 도는 앱은 v3.8 r18-lock부터 우편함에 쓰지 않는다(`pipeline/EmulatorInbox.kt`: generic·sdk_gphone 지문, goldfish·ranchu·vbox86 하드웨어 등이면 put·replace·delete를 버리고 읽기만 한다). 11/13 01:57·07:01·09:26에 에뮬레이터의 하트비트(빌드 1054~1056)가 `status/phone.json`에 섞였던 것을 막는다. 다만 덮어 설치 시험에서 먼저 까는 **직전 배포본(옛 코드)**은 v3.8 빌드 B가 배포될 때까지 계속 쓸 수 있다. 일부러 우편함에 쓰는 시험(inbox-e2e)은 앱이 아니라 CI 스크립트가 쓰므로 그대로다.

update-smoke는 v3.8부터 새 APK를 release로 만들고(지금 배포본은 debug), 덮어 설치 전 앱 폴더에 표시 파일을 써 두었다가 설치 뒤 root로 읽어 데이터가 남았는지 본다(root가 없으면 첫 설치 시각이 그대로인지로). 앱이 시작할 때 남기는 서명 자가 확인 로그(IEUM_CERT)가 `UNRELATED`면 실패한다. 수동 실행에서 `rotate_test=true`면 이 실행 안에서만 쓰는 임시 새 키로 첫 회전을 그대로 연습한다: 배포 키로 계보를 만들고 옛 키를 지운 뒤, **새 키 + 계보만으로** 두 빌드를 서명해 지금 배포본 위에 첫 빌드, 그 위에 둘째 빌드를 덮어 설치한다(둘째는 root가 있으면 앱이 직접 설치해 설치 전 서명 비교까지 지난다). 회전 APK에서 옛 인증서의 판정이 `ANCESTOR`가 아니면 실패한다. 수동 실행은 `IEUM_SIGNING_SEED_V2`가 있으면 그 새 키의 카카오 키 해시도 요약에 적는다.

### 서명 키 교체와 토큰 새 발급 (v3.8 r18-lock, 사용자가 할 일 — 이 순서대로만)

왜: 지금 서명 키는 우편함 토큰에서 나오고 토큰은 APK 안에 있다. 키를 토큰과 무관한 새 키로 옮기면, APK가 새어도 가짜 업데이트를 서명할 수 없다. 옛 키가 새 키를 보증하는 "계보"를 첫 회전 배포가 한 번 만들어 우편함에 두고, 이후 배포는 새 키와 그 계보만 쓴다. 그래서 그 뒤에는 토큰을 새로 발급해도 된다. 회전 APK는 Android 9 이상에서만 설치된다(Android 8.x 폰은 없음).

1. **비밀 값 만들기.** 앱 저장소 > Settings > Secrets and variables > Actions > Secrets(https://github.com/bllue583-commits/ieum-app/settings/secrets/actions) > New repository secret. 이름 `IEUM_SIGNING_SEED_V2`, 값은 비밀번호 관리자 등으로 만든 **무작위 40자 정도**(32자 미만이면 배포가 거부한다). 값의 사본을 비밀번호 관리자에 따로 보관한다: 회전 뒤 이 값을 잃으면 폰이 다시는 업데이트를 받지 못한다(폰에서 직접 지우고 새로 설치해야 하고, 그러면 폰의 기록이 사라진다).
2. **새 카카오 키 해시 등록.** Actions > update-smoke(https://github.com/bllue583-commits/ieum-app/actions/workflows/update-smoke.yml) > Run workflow(브랜치 `feature/lean-calls`, rotate_test는 꺼도 됨). 실행 요약의 "Kakao key hash of the rotated key" 값을 카카오 개발자 콘솔(https://developers.kakao.com/console/app) > 이음 앱 > [앱] > [플랫폼] > Android > 키 해시에 **추가**한다(옛 해시는 지우지 않는다). 안 하면 회전 배포 뒤 카톡 아침 보고가 멈출 수 있다(카카오 SDK가 어느 인증서로 해시를 만드는지는 확인 못 함 — 둘 다 등록하면 어느 쪽이든 된다).
3. **변수 켜기.** 같은 화면의 Variables 탭(https://github.com/bllue583-commits/ieum-app/settings/variables/actions) > New repository variable. 이름 `IEUM_ROTATE`, 값 `1`. 지켜볼 수 있을 때 켠다: 다음 배포부터 회전 서명이다.
4. **첫 회전 배포 확인.** 다음 release-apk 실행이 성공하고 로그에 "no stored lineage: the first rotated release makes it now", "key lineage stored in the mailbox"가 있는지, 우편함 `app/version.json`에 `"seedIndependent": true`와 `lineageSha256`이, 우편함에 `app/signing-lineage.bin`이 생겼는지 본다.
5. **폰 설치 확인.** 하트비트 `versionCode`가 그 배포의 번호가 되고 `security.certSelf`가 `ANCESTOR,SAME`이면 폰이 새 키로 넘어갔다. (`update.error`가 `CERT`이거나 버전이 오르지 않으면 여기서 멈추고 알린다.)
6. **그 뒤에만 토큰 새 발급.** GitHub > Settings > Developer settings > Personal access tokens > Fine-grained tokens(https://github.com/settings/personal-access-tokens/new): 저장소는 `ieum-inbox` 하나, 권한은 지금 토큰과 같게(Contents 읽기·쓰기, Issues 쓰기가 필요하면 그것도), **만료일을 정해서**. 새 토큰을 앱 저장소 비밀 `IEUM_INBOX_TOKEN`에 덮어쓴다. 옛 토큰은 아직 지우지 않는다.
7. **새 토큰 배포 확인.** 다음 배포(앱 코드 변경이 없으면 release-apk 수동 실행)가 성공하고, 폰 하트비트 `versionCode`가 그 배포 번호가 되는지 본다(그 APK 안에 새 토큰이 들어 있다).
8. **옛 토큰 폐기.** 7이 확인된 뒤에만 Fine-grained tokens 목록(https://github.com/settings/personal-access-tokens)에서 옛 토큰을 Revoke한다. 예약 작업(매시간·새벽)이 옛 토큰을 쓰고 있다면 그쪽도 새 토큰으로 바꾼다.

되돌리는 법:
- 3을 켰지만 폰이 아직 회전 배포를 설치하기 전: `IEUM_ROTATE`를 `0`으로 바꾸거나 지운다. 다음 배포는 예전과 같은 키·같은 지문으로 서명된다. `app/signing-lineage.bin`은 남겨 둬도 된다(같은 `IEUM_SIGNING_SEED_V2`로 다시 켜면 그대로 쓰인다).
- 폰이 회전 배포를 설치한 뒤: **`IEUM_ROTATE`를 끄지 않는다.** 계보는 옛 키로의 되돌림을 허락하지 않으므로 옛 키 배포는 폰에 설치되지 않는다. 고칠 것은 `IEUM_ROTATE=1`인 새 배포로 고친다.
- `IEUM_SIGNING_SEED_V2`를 바꾸지 않는다. 바꾸면 계보의 끝과 맞지 않아 release-apk가 배포를 멈춘다(폰에는 아무 일도 없음). 원래 값으로 되돌리면 다시 된다.

계보 파일(`app/signing-lineage.bin`)을 잃었을 때:
- 6(토큰 새 발급) 전: 아무것도 잃지 않았다. 다음 배포가 같은 두 키로 계보를 다시 만들어 저장한다(인증서가 같아 사슬 검사를 지난다).
- 6 뒤: 옛 키가 없어 계보를 다시 만들 수 없다. 다음 배포는 새 토큰에서 다른 "옛 키"를 만들어 사슬 검사에서 멈춘다(폰은 안전). 우편함 릴리스(최근 3개) 중 회전 릴리스의 자산 `signing-lineage.bin`을 내려받아 `app/signing-lineage.bin`으로 다시 올린다. 릴리스 자산도 모두 없으면 복구할 수 없으므로 이 파일은 지우지 않는다.

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

### 봉인 저장소 정리와 정리 전 사본 (v3.8)

폰은 하루 한 번(`VaultPruneWork`)과 업데이트 5분 뒤 한 번(건너뛰면 1시간 뒤 다시, 하루 최대 6번) 녹음 수집 원장의 끝난 줄을 지운다(설계 DESIGN_V7 4.1 S3): 같은 내용을 다시 확인한 요청 행과 끝난 금고 접수 행 중, 영수증이 3일 넘고 진행 중·대기·복구 중인 수집이 가리키지 않는 것만. 불변 영수증, 새 내용 요청, 수집 줄은 지우지 않는다. 결과는 하트비트 `dbPrune`. 통화 중이면 건너뛰고(`skipped`) 1시간 뒤 다시 한다. 이 빌드의 첫 정리가 끝날 때까지 매시간 등록도 한 번 더 넣는다. 첫 정리는 직전 3일치를 남기므로 `dbPercent`가 30 근처로 내려가는 것은 업데이트 3~4일 뒤에 판정한다.

**정리 전 사본.** 처음으로 무언가를 지우기 직전에 봉인 파일을 그대로 한 번 복사한다: `noBackup/protected-databases-prune-copy-v1/callbrief-local-v2.db.sealed`(원본은 `noBackup/protected-databases-v1/callbrief-local-v2.db.sealed`). 사본은 암호문 그대로지만 그 사이 지운 내용(개인 통화 처리로 지운 것 포함)이 남아 있으므로 **7일 뒤 반드시 지운다** — 매일 작업 맨 앞에서 DB 상태와 무관하게 파일 수정 시각으로도 지운다(`dbPrune.copy`가 `deleted`면 지워진 것). 사본은 다시 만들지 않는다. 사본을 3일 이어서 만들지 못하면(폰 저장공간 부족 등) 사본 없이 정리한다(`copy`=`none`).

**되돌리는 법(정리 때문에 녹음이나 기록이 사라졌다고 판단될 때만, 7일 안).** 사본은 **원래 경로로 되돌려야만 열린다** — Keystore 키 이름과 암호 검증값(AAD)이 원래 파일의 정규 경로에서 나오기 때문이다. 다른 폴더·다른 폰·앱을 지웠다 다시 깐 폰에서는 열리지 않는다(앱을 지우면 키도 사라진다).
1. 이음을 강제 중지한다(설정 → 애플리케이션 → 이음 → 강제 중지). 앱이 도는 동안 파일을 바꾸면 저장소가 "외부 변경"으로 잠긴다.
2. 앱 개인 폴더에서 사본을 원본 자리에 덮어쓴다: `noBackup/protected-databases-prune-copy-v1/callbrief-local-v2.db.sealed` → `noBackup/protected-databases-v1/callbrief-local-v2.db.sealed`. 같은 폴더의 `.lock`은 두고, `.bak`·`.new`가 있으면 지우지 말고 그대로 둔다(열 때 복구 로직이 판정한다). 이름·폴더를 바꾸지 않는다.
3. 앱을 연다. 하트비트 `dbRecovery`·`storeOpenError`가 비어 있고 `dbPercent`가 정리 전 값으로 돌아왔는지 본다.
주의: 정리 뒤부터 되돌릴 때까지 새로 저장된 통화·자막·결과는 사라진다. 다음 날 정리가 같은 줄을 다시 지우므로, 정리 자체가 원인이면 되돌리기 전에 정리를 끈 새 버전을 먼저 배포한다. 출시(release) 빌드는 `adb run-as`가 안 되므로 앱 개인 폴더에 손을 대려면 루팅이나 이 복사만 하는 별도 빌드가 필요하다(지금 앱에는 되돌리기 버튼이 없다).

정기 점검: 달에 한 번 예약 작업 실행 기록 1건을 열어 통화 원문이 남지 않았는지 확인한다.

관련 파일: `docs/routines/`(지시문, 자가 보고 요약 `self-report.md`), `tools/inbox/`(워치독), `tools/needs_update_smoke.sh`, `tools/update_smoke.sh`, `.github/workflows/`, `docs/DEVELOPMENT.md`(개발 환경).

## 7. v4.0 운영 자립 (r20-5, DESIGN_V7 6)

### 7.1 폰 분실·초기화 때 복원 (코드로 확인한 사실대로, 11/13)

**먼저 알아 둘 것.** 앱은 `android:allowBackup="false"`이고 봉인 저장소(`noBackup/protected-databases-v1/`)와 봉인 설정은 그 폰의 Android Keystore 키로만 열린다. 그래서 폰 안의 이음 데이터는 **어떤 방법으로도 새 폰으로 옮길 수 없다**(Smart Switch·Google 백업에도 안 들어가고, 파일을 복사해도 열리지 않는다). 원본으로 남는 것은 우편함 저장소와 폰의 통화 녹음 파일뿐이다.

| 무엇 | 복원되나 | 어디서 |
|---|---|---|
| 업체·사람·진행 중인 일·현장 메모(장부) | **된다.** 새 폰은 `LEDGER_REV`가 0이라 첫 결과 받기에서 우편함 `ledger/ledger.json`을 그대로 적용한다(`CallRecordWork` → `applyLedger`) | 우편함 `ledger/` |
| 지난 통화 요약·체크할 일 | 녹음 파일이 새 폰의 녹음 폴더에 있으면 **처음 정리가 10월 1일부터 다시 만든다**(자막을 폰에서 다시 만들고 매시간 분석이 다시 요약 — 수백 건이면 여러 날과 구독 한도를 씀). 녹음 파일이 없으면 **잃는다**(요약 결과 파일은 폰이 받은 뒤 우편함에서 지운다) | 폰 녹음 폴더(삼성 통화 녹음은 Smart Switch나 삼성 클라우드로 옮겨야 남는다) |
| 끝낸 일, 직접 적은 일, [틀렸어요] 표시, 고친 이름·합친 사람 되돌리기, 개인으로 둔 번호 | **잃는다.** 폰 안에만 있다(직접 고친 이름은 `ledger/edits/`로 올라가 장부에 반영된 만큼만 남는다) | 없음 |
| 카카오톡 연결, 내 정보, 알림 시각, 카드 설정 | **잃는다.** 다시 설정 | 없음 |
| 음성 인식 모델(574MB) | 동의하면 다시 받는다 | 공개 다운로드 |
| 하트비트·채점 숫자의 지난 값 | 우편함 `status/`의 지난 파일과 git 기록에 남는다. 폰 안의 누계(`use`, `feedback`, 카드 통계)는 0부터 | 우편함 `status/` |

**절차(관리하는 사람이 한다, 약 30분 + 처음 정리 며칠):**
1. 새 폰에 이음 APK를 설치한다: 우편함 저장소의 최신 릴리스 자산(`app/version.json`의 `apk`가 가리키는 파일, `sha256`으로 확인)을 받아 설치(출처 허용 필요). 서명이 같으면 이후 업데이트는 저절로 된다.
2. 녹음 파일을 옮겼다면 먼저 녹음 폴더에 있는지 본다(삼성 "통화 녹음" 폴더).
3. 앱의 처음 설정을 끝까지 한다(새 설치라 통화 카드 단계가 필수로 나온다). 정리 동의 → 모델 받기 → 처음 정리가 시작된다.
4. 설정 › 아침 보고에서 카카오톡을 다시 연결한다.
5. 다음 날 아침 하트비트 `status/phone.json`에서 `appVersion`, `firstPass`, `kakao.linked`를 확인하고, 앱의 업체 탭에 장부의 업체가 들어왔는지 본다(장부 적용은 하트비트 키가 따로 없다).
6. 잃어버린 폰은 우편함 토큰이 APK 안에 있으므로, 회수하지 못하면 OPERATIONS 5절 "서명 키 교체와 토큰 새 발급"의 토큰 교체를 한다(새 토큰 → 배포 → 옛 토큰 폐기).

시험기에서 이 절차를 실제로 한 번 돌려 보는 것은 아직 하지 않았다(이 묶음에는 기기가 없음). 처음 한 번은 에뮬레이터나 남는 폰으로 1~3번만 해 보면 된다.

### 7.2 [틀렸어요] 주 1회 집계와 지시문 개선 (절차만)

1. 월요일 아침, 지난 7일의 `status/phone.json` git 기록에서 `feedback.wrong`·`feedback.undone`의 증가분과 `use.today.taskDone`의 합을 적는다. 틀림 비율 = (wrong 증가 - undone 증가) / (그 수 + taskDone 합).
2. 새벽 정리 state의 `wrong[]` id로 장부·`tidy/history/`에서 그 일이 어떤 통화의 어떤 약속이었는지 본다(관리자가 우편함 안에서만, 내용을 다른 곳에 옮기지 않는다).
3. 같은 종류가 2건 이상이면(예: 상대가 할 일을 내 일로, 인사말을 약속으로) `docs/routines/hourly.md`의 해당 규칙을 고치는 안을 만든다. hourly.md를 바꾸는 것은 자막·분석 묶음과 같은 절차(버전 올림, 정답지로 다시 재기, DESIGN_V7 7절)를 따른다.
4. 결과를 HANDOFF 4절 판정표의 채점 항목 4 옆에 한 줄로 적는다.

### 7.3 GitHub Actions 분량 (11/13 기준)

| 워크플로 | 언제 도나 | 바꾼 것(v4.0) |
|---|---|---|
| `ci` | 모든 `feature/**` push | 그대로(약 5분) |
| `device-test` | **`feature/lean-calls` push**와 수동 | 전에는 모든 `feature/**` push마다 10~40분 돌았고, 에뮬레이터 시험의 기존 실패 1건으로 작업 브랜치마다 빨간불이었다. 작업 브랜치는 필요할 때 수동(`gh api repos/<owner>/<repo>/actions/workflows/device-test.yml/dispatches -f ref=<브랜치>`) |
| `inbox-e2e` | 자기 파일 push와 수동 | 매시간 cron을 지웠다. GitHub는 예약 실행을 기본 브랜치(`main`)의 파일로만 하는데 `main`에 이 파일이 없어 cron은 한 번도 돌지 않았다(마지막 실행 11/4, push). 한 번 확인 = 수동 실행 두 번(합성 통화를 넣고, 한 시간쯤 뒤 다시 돌리면 결과를 검사하고 새 것을 넣음). 분석이나 파서를 바꿨을 때만 |
| `release-apk`, `update-smoke` | `feature/lean-calls`·`main` push | 그대로 |
| `real-device-test` | 수동(하루 5번 한도) | 그대로 |

저장소가 공개인 동안 Actions 분은 무료다. 비공개로 돌리면(7.4) 무료 분(계정 플랜의 월 한도)에서 빠지므로 `device-test`(분 단위가 가장 큼)는 통합 브랜치에서도 수동으로 돌리는 것을 먼저 검토한다.

### 7.4 저장소 비공개 복귀 (사용자가 한다)

앱 저장소는 11/5부터 Actions 무료 때문에 공개다(비밀은 들어 있지 않다: 토큰·키는 Actions 비밀과 우편함에만 있음, `tools/scan_export.py`와 gitleaks ci가 검사). 비공개로 돌릴 때는:
1. GitHub 앱 저장소 › Settings › General › Danger Zone › Change visibility › Private. (Claude는 공개·비공개 전환을 하지 않는다.)
2. Settings › Billing에서 Actions 사용량을 확인한다. 한도를 넘으면 모든 워크플로가 몇 초 만에 실패한다(6절 표 마지막 줄).
3. 7.3 표대로 무거운 워크플로를 수동으로만 둘지 정한다.
4. 폰은 우편함 저장소(원래 비공개)만 보므로 앱 동작에는 영향이 없다.

### 7.5 설계도의 원본

설계도의 원본은 이 저장소의 `docs/DESIGN.md`다. Claude Docs의 "이음 설계도 개정안"은 더 이상 고치지 않는 옛 사본이다.

