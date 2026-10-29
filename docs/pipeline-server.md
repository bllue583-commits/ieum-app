# 이음 매일 분석 서버 운영 안내 (lane B)

휴대폰(S25)이 통화 녹음을 기기 안에서 Whisper로 전사하고, **전사 텍스트만** 암호화해 비공개 저장소
`ieum-inbox`에 올립니다. `ieum-app`의 GitHub Actions 작업 `ieum-daily`가 매일 새벽 이를 복호화해 Claude로
업무 갱신 제안(C2)과 아침 보고(C3)를 만들고, 휴대폰만 열 수 있게 다시 암호화해 `ieum-inbox`에 씁니다.
음성은 휴대폰 밖으로 나가지 않습니다. 결과는 제안이며 적용 여부는 앱의 보호 규칙이 정합니다.
계약(C0–C3, 봉인 형식, 저장소 배치)은 `docs/contracts/README.md`에 고정되어 있습니다.

## 언제 무엇이 실행되나

| 시각 (KST) | 동작 |
| --- | --- |
| 05:07 매일 (cron `7 20 * * *` UTC) | 본 실행: 새 전사문 분석, C2 작성, 보고서 D(오늘 날짜) r1 작성 |
| 05:37 매일 (cron `37 20 * * *` UTC) | 안전 재실행. 05:07 실행이 지연·실패했거나 그사이 전사문이 더 올라왔을 때만 파일이 바뀝니다 |
| 수동 | Actions 화면의 *Run workflow* 또는 `gh workflow run ieum-daily.yml` |
| 월요일 첫 실행 | `ieum-inbox` 기록을 현재 파일만 담은 commit 하나로 다시 쓰고 force-push합니다 (아래 참고) |

GitHub 예약 실행은 몇 분에서 수십 분 늦을 수 있습니다. `concurrency: ieum-daily` 설정으로 두 실행이 겹치지 않고
뒤 실행은 앞 실행이 끝날 때까지 기다립니다. 같은 입력으로 다시 실행하면 아무 파일도 바뀌지 않습니다(멱등).

### 한 번의 실행 순서 (`worker/src/pipeline/server.mjs`)

1. **기기**: `devices/*.json` 중 처음 등록된 유효한 기기 하나만 신뢰합니다(최초 사용 시 신뢰, 상태에 기록).
   다른 기기는 처리하지 않고 `devicesIgnored`, `ignoredTranscripts`로 셉니다. 휴대폰을 바꿨을 때는
   저장소 변수 `IEUM_ACCEPT_DEVICE_ID`에 새 deviceId를 넣으면 그 기기로 신뢰가 옮겨집니다(휴대폰 토큰으로는 바꿀 수 없음).
2. **전사문**: `inbox/<deviceId>/transcripts/<transcriptId>.sealed.json`을 파일 이름의 aad(`c1:<transcriptId>`)로
   복호화하고 C1 검증, 기기 서명 확인을 합니다. 실패하면 사유 코드만 기록하고 넘어갑니다. 이미 처리한
   transcriptId는 다시 올라와도 분석하지 않습니다(`duplicates`).
3. **Claude 분석**: 전사문·업무 목록·이전 누적 요약을 데이터로 보내고(지시로 취급하지 않도록 시스템 프롬프트에 명시),
   구조화 출력(`output_config.format`, `ANALYSIS_OUTPUT_SCHEMA`)으로 받습니다. 429·5xx·과부하는 최대 3번까지 재시도합니다.
4. **C2 작성**: 인용문이 전사문에 그대로 없는 변경, 대상 업무에 없는 taskId는 버립니다. `done`은 `needs_confirmation`으로 바꿉니다.
   서버 키로 서명하고 기기 키로 봉인해 `outbox/<deviceId>/analysis/<transcriptId>-r1.sealed.json`에 씁니다.
5. **보고서**: 보고 날짜 D = 실행 시각의 KST 날짜, 범위 = 통화 시작 시각 기준 [D-1 00:00, D 00:00) KST.
   업무별로 묶고(업무 미연결은 "업무 미정"), 확인 필요 항목(DONE_CLAIMED, CASE_UNCONFIRMED, MANUAL_TASK, DUE_CONFLICT),
   미처리 수(pending), 최근 7일 요약(추가 API 비용 없이 저장된 요약으로 생성)을 넣습니다. 내용이 같으면 쓰지 않고,
   바뀌면 revision+1(`<D>-r2.sealed.json` …)로 새 파일을 씁니다. 지난 14일 안의 날짜에 늦게 도착한 통화는 그 날짜 보고서도 갱신합니다.
   통화 시작 시각을 모르는 전사문은 분석하지만 보고서에는 `pending.unknownTime`으로만 셉니다.
6. **보존 기간 정리**와 **상태 저장**(아래).

## 비밀값과 변수 (ieum-app → Settings → Secrets and variables → Actions)

| 이름 | 종류 | 내용 | 만드는 방법 |
| --- | --- | --- | --- |
| `IEUM_SERVER_SIGNING_KEY` | secret | 서버 서명 개인키 (PKCS#8 base64) | `tools/setup_pipeline.py` |
| `IEUM_SERVER_ENCRYPTION_KEY` | secret | 서버 복호화 개인키 (PKCS#8 base64) | `tools/setup_pipeline.py` |
| `ANTHROPIC_API_KEY` | secret | Anthropic API 키 | 스크립트가 입력받아 설정 |
| `IEUM_INBOX_TOKEN` | secret | Actions용 fine-grained 토큰 (`ieum-inbox`만, Contents 읽기/쓰기) | GitHub에서 생성 후 스크립트에 입력 |
| `IEUM_INBOX_REPOSITORY` | variable (선택) | 기본값 `bllue583-commits/ieum-inbox` | 다른 저장소일 때만 |
| `IEUM_MODEL` | variable (선택) | 기본 모델 변경 (기본 `claude-sonnet-5-5`) | 보통 비워 둠 |
| `IEUM_ACCEPT_DEVICE_ID` | variable (선택) | 신뢰할 기기를 새 휴대폰으로 옮길 때 그 deviceId | 기기 교체 시 |

비밀값이 하나라도 없으면 작업은 `::notice::`를 남기고 성공(0)으로 끝납니다. 아무것도 처리하지 않습니다.
서버 **공개** 키 두 개는 `config/pipeline.json`에 있고 앱에 빌드되어 들어갑니다(inbox에서 읽지 않으므로 휴대폰 토큰이 새도 암호화 대상을 바꿀 수 없음).

### 최초 설정

```sh
gh auth login            # 본인 계정, ieum-app 관리자 권한
python3 tools/setup_pipeline.py --dry-run   # 할 일 미리 보기
python3 tools/setup_pipeline.py             # ieum-inbox 생성, 키 생성·비밀값 설정, 공개 키 기록
git add config/pipeline.json && git commit -m "Set pipeline server public keys" && git push
```

- 키가 이미 있으면 다시 만들지 않습니다. 키 교체는 `--rotate`(교체 후 앱을 새 공개 키로 다시 빌드해야 하고, 교체 전 봉인된 미처리 전사문은 열 수 없음).
- API 키·토큰 교체는 `--update-secrets`. 비밀값은 표준입력으로만 `gh secret set`에 전달되고 화면·명령줄에 나타나지 않습니다.
- 토큰은 두 개를 따로 만듭니다: 휴대폰용(앱에 입력)과 Actions용(`IEUM_INBOX_TOKEN`). 둘 다 *Only select repositories* → `ieum-inbox` 하나, *Contents: Read and write*만, 만료일 지정.

## 비용

가격(100k 토큰 이하 프롬프트, 100만 토큰당): Sonnet 5.5 입력 $2 / 출력 $10, Haiku 5.5 입력 $0.10 / 출력 $0.50.
통화 1건(전사문 약 3천~1만 토큰, 출력 약 1천 토큰)은 Sonnet 기준 대략 $0.02~0.05입니다.

| 규칙 | 동작 |
| --- | --- |
| 하루 상한 $3 (KST 날짜 기준) | 다음 호출 예상 비용이 상한을 넘으면 새 분석을 멈추고 남은 전사문은 다음 실행으로 넘김 |
| 월 상한 $30 (KST 월 기준) | 같은 방식으로 멈춤. 다음 달 1일부터 재개 |
| 월 사용액 80%($24) 도달 | 그 달 남은 기간은 Haiku 5.5로 분석 |

장부는 서버 상태에 실제 응답의 사용 토큰으로 기록합니다(실패 응답의 토큰도 포함). 상한 때문에 멈춘 전사문은 보고서
`pending.awaitingAnalysis`에 나타나고 실행 요약에 `capReached`(`CAP_DAILY`/`CAP_MONTHLY`)와 경고가 남습니다.
Anthropic 콘솔에도 별도 월 사용 한도를 걸어 두는 것을 권장합니다.

## 실행 요약 읽기

각 실행은 Actions 로그와 *Summary*에 JSON 한 줄을 남깁니다. 숫자·코드만 있고 통화 내용, 키, 토큰은 들어가지 않습니다.

| 필드 | 뜻 |
| --- | --- |
| `status` | `ok`, `not_configured`(비밀값 없음), `error`(+`code`) |
| `devices` / `devicesIgnored` / `devicesInvalid` | 등록 파일 수 / 신뢰하지 않는 기기 / 형식 오류 |
| `deviceKeyChanged` | 신뢰한 기기의 등록 파일 키가 바뀜(무시됨, 기기 교체라면 `IEUM_ACCEPT_DEVICE_ID`) |
| `transcriptsSeen` / `analysed` / `duplicates` | 본 전사문 / 이번에 분석 / 이미 처리된 것의 재업로드 |
| `rejected` | 거부 사유별 개수 (예: `SIGNATURE_INVALID`, `SEALED_AAD_MISMATCH`, `SEALED_RECIPIENT_MISMATCH`, `SEALED_AUTH_FAILED`, `TRANSCRIPT_TEXT_BINDING`) |
| `awaiting` / `failed` | 상한·오류로 다음 실행을 기다리는 수 / 3번 실패해 포기한 수 |
| `droppedChanges` | 버린 업무 변경: `quote`(인용 불일치), `task`(없는 taskId), `other`(형식) |
| `capReached`, `models`, `inputTokens`, `outputTokens` | 상한 도달 여부, 모델별 호출 수, 사용 토큰 |
| `spentTodayUsdE7`, `spentMonthUsdE7` | 오늘/이번 달 사용액 × 10⁷ USD (예: `30000000` = $3) |
| `analysisWritten`, `reportsWritten`, `filesDeleted`, `stateWritten` | 쓴 C2·C3, 지운 파일, 저장한 상태 파일 수 |
| `nothingToDo` | `true`면 바뀐 파일 없음 |

## 상태와 보존 기간

- 서버 상태 `state/server-state.sealed.json`(aad `state:v1`)과 날짜별 분석 색인 `state/analyses/<D>.sealed.json`은
  서버 자신의 암호화 키로 봉인됩니다. 담는 것: 신뢰 기기, 처리 색인(transcriptId·파일 해시·처리 시각), 업무별 최신 누적 요약,
  90일 분석 요약 색인, 비용 장부, 보고서 revision. 전사문 원문은 저장하지 않습니다.
- 처리한 inbox 전사문 파일은 처리 7일 후 삭제, 거부된 파일은 7일 후 삭제, 오래된 C0 상태 파일은 최신 하나만 남기고 7일 후 삭제.
- 상태 항목, C2·C3 결과 파일은 90일 후 삭제.
- Git은 지운 파일도 기록에 남기므로, **매주 월요일 첫 실행**이 `ieum-inbox`의 기록을 현재 파일만 담은 orphan commit 하나로
  바꿔 force-push합니다(`--force-with-lease`; 그 사이 휴대폰이 push했다면 건너뛰고 05:37 실행이 다시 시도). 그래서 삭제가 실제
  삭제가 되며, 휴대폰 앱은 `ieum-inbox`를 받을 때 기록이 바뀔 수 있다는 것을 전제로 해야 합니다(fast-forward 대신 최신 tree 기준).
  GitHub가 내부적으로 보관하는 도달 불가능한 객체의 정리는 GitHub 일정에 따릅니다.

## 실패 처리

| 상황 | 결과 | 할 일 |
| --- | --- | --- |
| 비밀값 없음 | notice, 성공 종료 | 최초 설정 실행 |
| `STATE_UNREADABLE` 오류 | 실행 실패(1), 아무것도 쓰지 않음 | `IEUM_SERVER_ENCRYPTION_KEY`가 상태를 만든 키와 같은지 확인. 키를 잃었으면 `state/`를 지우고 `--rotate`(처리 색인이 사라져 남은 전사문은 다시 분석됨) |
| Claude 429/5xx/과부하 | 실행 안에서 최대 3번 재시도, 그래도 실패하면 다음 실행에서 다시 시도, 총 3번 실행 실패 시 `failed` | 계속되면 Anthropic 상태·키·잔액 확인 |
| 상한 도달 | `capReached`, 남은 전사문은 `awaiting` | 다음 날/다음 달 자동 재개. 상한 변경은 코드(`CAPS`) 수정 |
| 전사문 거부 | `rejected`에 사유 코드 | 앱이 계약에 맞게 봉인했는지, 서버 공개 키가 최신인지 확인 |
| inbox push 실패 | 3번 rebase 후 재시도, 실패 시 오류 | 토큰 만료·권한 확인. 다음 실행이 같은 작업을 다시 함(멱등) |

## 시험

```sh
cd worker && node --test tests/*.test.mjs       # 서버: 가짜 Claude, TEST-ONLY 키
python3 -m unittest discover -s tests -v        # 설정 스크립트: gh/node 모의
```

실제 Anthropic 호출과 실제 GitHub Actions 실행은 이 시험에 포함되지 않습니다.
