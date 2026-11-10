# 루틴 자가 보고와 루틴 안 워치독 (v3.4부터)

v3.4(11/6, DESIGN_V4 4.3-1, 4.3-2)부터 자가 보고 문단과 워치독 단계는 지시문 원본 안에 있다. 매시간 분석은 `docs/routines/hourly.md`(버전 8)의 "마지막 단계(자가 보고)"와 "워치독", 새벽 정리는 `docs/routines/tidy.md`(버전 8, v3.7)의 같은 두 문단이다. 이 파일은 그 요약이고, 문장을 따로 붙여 넣지 않는다. 고칠 때는 두 지시문 원본을 고친다.

## 자가 보고: 루틴마다 파일 하나

| 파일 | 쓰는 루틴 | 커밋 |
|---|---|---|
| `status/last-run-hourly.json` | 매시간 분석 | 결과 커밋 "analyze N calls" 뒤 따로 "status" 커밋 |
| `status/last-run-tidy.json` | 새벽 정리 | "tidy" 커밋 뒤 따로 "status" 커밋 |
| `status/last-run.json` | v3.3까지 두 루틴이 함께 쓰던 옛 파일 | 더 쓰지 않고 지우지도 않는다. 워치독과 폰은 새 파일이 없을 때만 읽는다 |

- 처리할 것이 없었어도, 일부가 실패했어도 항상 새로 써서 push한다. `endedMs`가 이번 실행 시각이어야 워치독과 폰이 루틴이 살아 있음을 안다.
- 한 줄 JSON이고 시각, 숫자, 모델 이름, 참/거짓, cron 문자열만 쓴다. 자막 원문, 이름, 전화번호, 회사 이름, 요약, 오류 메시지 본문은 쓰지 않는다.
- 공통 키: `routine`, `startedMs`, `endedMs`, `processed`, `skipped`, `failed`, `batches`, `model`, `limitHit`, `ledgerRev`, `promptVersion`, `promptMismatch`, `loggedTranscript`(항상 false), `repoOk`, `pushed`.
- 새벽 정리만(v3.5, 버전 7): `reportWritten` — 실행이 끝날 때 오늘(한국 시각) `report/YYYY-MM-DD.json`이 push되어 있으면 true, 쓰지 않았거나("정리할 것 없음") `tools/report_check.py` 검사를 통과하지 못했거나 push가 실패했으면 false. 워치독의 "아침 보고서가 안 만들어졌어요" 본문이 이 값으로 원인을 적는다.
- 매시간 분석만: `kinds`, `firstPassItems`, `formats`, `requestVersions`, `leftover`(처리 상한 20건, 03:34와 04:34 실행은 40건으로 다음 실행에 남긴 요청 파일 수), `cron`(예약 작업의 실제 cron, v3.7부터 `CRON_TZ=Asia/Seoul 34 3-4,7-22 * * *`, 둘로 나누면 `crons` 목록).
- `repoOk`: 시작할 때 `git pull`이 됐는지. pull이 안 되면 이 파일도 못 쓰고 "확인 필요: 저장소 접근"으로 답하고 끝난다. `pushed`: 결과 커밋을 push했는지(결과 커밋이 없었으면 true).
- status 커밋 push가 실패하면 `git pull --rebase` 뒤 한 번 더 하고, 그래도 실패하면 답 첫 줄을 "확인 필요: 저장소 접근"으로 한다.

폰은 결과를 받을 때 `status/last-run-hourly.json`의 `endedMs`와 `cron`을 읽어 설정 키 `routineEndedMs`, `routineCrons`에 둔다. 결과를 가져갈 시각(다음 예정 실행 +3, +8, +15분, v3.7부터 03:34와 04:34 실행 뒤에는 +25분도)과 신호등 "분석" 줄(예정 실행을 2회 놓치면 주황, 4회면 저녁 자가 진단 알림 `routine_late` — 빨강은 쓰지 않음)이 이 값을 쓴다. 루틴이 죽으면 루틴 안 워치독도 멈추므로 폰 신호등이 둘째 눈이다.

## 워치독: 루틴 안에서 돈다

ieum-inbox의 Actions 워치독(`.github/workflows/watchdog.yml`, 3시간마다)은 v3.4에서 지웠다. 비공개 저장소 Actions의 결제 실패로 11/5 03:40부터 8회 연속 시작조차 못 했기 때문이다. 이제 두 루틴이 자가 보고를 push한 뒤 마지막에 아래 한 줄을 돌린다.

```
python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox --out /tmp/ieum-watchdog-plan.json
```

- 원본은 앱 저장소 `tools/inbox/watchdog.py`(시험 `test_watchdog.py`, ci가 돌림)이고 배포 때 ieum-inbox `tools/watchdog.py`로 복사한다.
- `status/phone.json`, 자가 보고, inbox 파일의 나이만 읽고, 문제마다 라벨 `ieum-watch` 이슈를 `gh api`(REST)로 열고, 정상으로 돌아오면 닫는다. 마지막 줄에 "watchdog: n opened, m closed".
- n이 1 이상이면 답 첫 줄이 "확인 필요: 감시 이슈 n건"이 된다(새벽 정리는 끝에 붙임). 예약 작업 알림은 이렇게 "확인 필요" 답일 때만 의미가 있다.
- 명령이 실패하면(gh 인증 없음 등) 다시 시도하지 않고 넘어간다. 루틴은 계속 돈다.
- v3.4에서 더한 규칙: "매시간 분석이 예정 시각에 돌지 않았어요"(hourly 자가 보고의 status 커밋이 cron상 2슬롯 연속 없음; analyze 커밋 기준이 아니다, 조용한 날엔 원래 없다), "루틴이 결과를 저장소에 올리지 못했어요"(`pushed=false`).
- 새벽 정리에서도 돌려 밤사이 빠진 매시간 실행을 아침에 한 번 더 본다.
- v3.5(DESIGN_V5 2.2-4)에서 더한 규칙: "아침 보고서가 안 만들어졌어요"(한국 시각 05:30이 지났는데 `report/<오늘>.json`이 없음; 새벽 정리 자가 보고가 버전 7 이상일 때만, 파일 내용은 읽지 않고 있는지만 본다), "카카오톡 보고가 안 갔어요"(하트비트 `kakao.linked`가 true인데 09:00이 지나도 `kakao.lastSentMs`가 어제 00:00 전, 즉 어제와 오늘 두 아침 연속; 어제 09:00 전부터 연결돼 있었을 때만). 새벽 정리(v3.7부터 05:05, 그 전 04:46)의 워치독은 05:30 전이라 첫째 규칙을 보지 못하고, 그날 07:34부터의 매시간 분석 워치독이 본다.
