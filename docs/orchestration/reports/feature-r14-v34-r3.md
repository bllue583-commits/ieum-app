# r14-3 보고서 (feature/r14-v34-r3, 13e968e + 4dda580, 기준 8293f5b)

ci는 success다(https://github.com/bllue583-commits/ieum-app/actions/runs/42082265910).

## ieum-inbox에서 할 일 (오케스트레이터)
1. 앱 `docs/routines/hourly.md`(v8)와 `tidy.md`(v6)를 ieum-inbox `routines/`로 복사한다.
2. 앱 `tools/inbox/watchdog.py`를 ieum-inbox `tools/watchdog.py`로 복사한다.
   - 루틴은 `python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox --out /tmp/ieum-watchdog-plan.json`으로 실행한다.
3. `.github/workflows/watchdog.yml`을 지운다. 앱 저장소의 사본은 이미 지웠다.
4. 예약 작업 환경에 gh 이슈 쓰기 권한이 있는지 확인한다. 권한이 없으면 워치독 단계만 건너뛰고 루틴은 계속 돈다.
5. 저장소를 연결하지 않은 예약 작업을 한 번 돌려 "확인 필요: 저장소 접근" 답이 나오는지 확인한다.

## 바꾼 것
- **hourly v8**
  - 0번
    - 저장소 접근이 실패하면 처리하지 않고 끝낸다(pull 실패, pull --rebase 뒤에도 push 실패).
    - 한 번 실행할 때 20건까지만 처리한다.
  - 2번에 renameCompany를 더했다.
  - 요청 버전 7은 과도기로 받는다.
  - 자가 보고는 `status/last-run-hourly.json`에 따로 "status" 커밋으로 남긴다. 필드: `repoOk`, `pushed`, `leftover`, `cron`.
  - 마지막에 워치독을 돌린다. 분석 규칙과 결과 형식은 7과 같다.
- **tidy v6**
  - 저장소 접근 실패 처리와 renameCompany를 더했다.
  - rewrite할 때 숫자를 바꾸지 않는다.
  - 자가 보고는 `status/last-run-tidy.json`에 쓴다.
  - 마지막에 워치독을 돌린다.
  - tidyVersion은 version_fields가 머리 줄을 읽어 자동으로 정한다.
- **`watchdog.py`**
  - `--apply OWNER/REPO`: REST로 이슈를 열고 닫는다. 열린 이슈 목록을 못 읽으면 종료 코드 1로 끝낸다.
  - 자가 보고는 루틴별 새 파일을 먼저 읽고, 없으면 옛 파일을 읽는다.
  - 새 규칙
    - "매시간 분석이 예정 시각에 돌지 않았어요": status 커밋이 2슬롯 연속 없을 때. 40분 경과 기준, −5분~+60분.
    - "루틴이 결과를 저장소에 올리지 못했어요": `pushed=false`일 때.
  - 옛 버전 규칙은 두 루틴을 묶어 하나로 낸다.
  - `watchdog.yml`을 지웠다.
- **`ClaudeCallAnalyzer`**
  - `PROMPT_VERSION=8`. SYSTEM과 SCHEMA는 그대로다.
  - `maskNumbers`가 메일 주소를 `[메일 주소]`로 가린다.
- **`CallRecordWork`**
  - ResultChain: 다음 예정 실행의 +3·+8·+15분에 결과를 받는다. 최대 24시간까지 기다린다.
  - 자가 보고에서 `ROUTINE_ENDED_MS`와 `ROUTINE_CRONS`를 읽는다.
  - `app/version.json`을 ETag로 읽고, 처음 보는 높은 versionCode가 있으면 `CallUpdate.checkSoon`을 부른다.
  - `latency.outliers`와 `RECORD_TIME`을 남긴다.
- **`CallRecordStore`**
  - outliers를 분리하고 recordTime 구간을 둔다: negOver1d, neg, d0, d1, d2to7, d8to30, over30d.
  - renameCompany를 ledger edit으로 남긴다.
  - rewrite가 숫자를 바꾸면 거부한다.
- **`CallUpdate`**: 실패하면 `UPDATE_ERROR_CODE`를 쓰고, 성공하면 ""로 비운다.
- **`MessageWork`**: 메일 오류 코드를 `MAIL_ERROR_CODES`에 센다. 코드는 AUTH, TIMEOUT, NETWORK, FOLDER, SERVER, OTHER다.

## 시험
- **새로 넣은 시험**
  - `R14ThreeTest` 10건
  - `CallRecordWorkChainTest` 7건
  - `RoutineDocTest`(v8, tidy v6)
  - `test_watchdog` V34RoutineTest 12건
- **로컬 결과**
  - `r14-3-test-2`: 99건, 실패 0
  - `tools/inbox` 시험: 42건 OK
  - check_ui_rules 0, scan_export 0

## 설계와 다르게 정한 것
- 메일 주소는 `[메일 주소]`로 가린다. 기존 규칙 17과 맞춰서 SYSTEM이 바뀌지 않게 했다.
- 단가 '@'는 그대로 남긴다.
- cron을 못 읽으면 기본 일정(`34 7-22`)을 쓴다.
- 자가 보고는 결과 커밋과 따로 status 커밋으로 남긴다.
- 새벽 정리에서도 워치독을 돌려, 밤사이 빠진 실행을 아침에 잡는다.
- 워치독에서 gh api를 부르는 일은 `--apply`로 Python 안에서 한다.
- 새 버전은 ETag 변화가 아니라 "처음 보는 높은 versionCode"로 판정한다.

## 못 한 것
- 저장소 미연결 실행 확인은 오케스트레이터가 한다.
- `docs/routines/self-report.md`, `docs/OPERATIONS.md`, `docs/HANDOFF.md`에 아직 옛 last-run.json과 Actions 워치독 설명이 남아 있다. 문서를 정리할 때 고친다.

## 다른 묶음이 알아야 할 것
- **설정 키**
  - 이번에 쓰는 키: `routineEndedMs`, `routineCrons`, `updateErrorCode`, `mailErrorCodes`, `recordTime`, `latency.outliers`
  - 내부 키: `resultChainSinceMs`, `versionSeenCode`
- **r14-2**: 분석 신호등은 `ResultChain.crons(s)`와 `ResultChain.runs(...)`로 만든다.
- **바뀐 시그니처**
  - `ResultChain.next`, `MAX_ROUNDS`, `INTERVAL_MS`를 지웠다.
  - `start`와 `afterRound`에 `read` 인자를, `CallRecordAnalysisRunner`에 `onNewVersion`과 `runningCode` 인자를 더했다. 모두 기본값이 있다.
