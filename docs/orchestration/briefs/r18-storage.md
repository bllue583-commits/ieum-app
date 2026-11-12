# r18-storage: 폰 저장소가 차는 것을 멈춘다 (v3.8, DESIGN_V7 4.1 — 실측으로 방향을 바꿈)

브랜치 `feature/r18-storage`(작업 나무 `/home/claude/wt/store`, 기준 63ff2b8 = v3.7 빌드 1053). 보고는 한국어.

## 왜, 그리고 무엇이 달라졌나
폰의 봉인 DB(`ProtectedDatabase`, 평문 16MiB·10만 행 한도, 쓰기마다 파일 전체를 다시 봉인)가 차고 있다. 11/13 00:34 하트비트: `dbPercent` 63(11/12 아침 57), `dbOpenMs` 1995. 이 속도면 **11/19 전후에 100%**이고, 그 뒤에는 모든 쓰기가 실패하는데 호출부가 오류를 삼켜 조용히 멈춘다(DESIGN_V7 4.1 감사, 추정 포함).

DESIGN_V7 4.1은 자막 원문을 DB 밖으로 빼는 것(S1)을 첫 일로 잡았다. 그런데 v3.7이 처음 올린 표별 크기(`dbTables`, 글자 수)는 다른 것을 가리킨다:

| 표 | 행 | 글자 수 | 비중 |
| --- | --- | --- | --- |
| `discovery_intake_requests` | 2,078 | 5,193,577 | **60%** |
| `discovery_queue_rows` | 739 | 976,715 | 11% |
| `receipts` | 1,944 | 443,619 | 5% |
| `vault2_intake_receipts` | 1,944 | 433,512 | 5% |
| `vault2_pending` | 465 | 412,667 | 5% |
| `call_records` | 237 | 316,302 | 3.6% |
| `local_files` | 333 | 195,568 | 2% |
| `discovery_documents` | 426 | 195,040 | 2% |
| (전체) | 13,269 | 8,684,506 | |

자막(`call_records`)은 3.6%뿐이다. **녹음 수집 원장(discovery, receipts, vault2 계열)이 80% 이상**이다. 그래서 이번 묶음은 S1·S2(자막·조각을 파일로)를 하지 않고, 수집 원장의 끝난 줄을 안전하게 치우고 다시 쌓이지 않게 하는 데 집중한다.

## 조심할 것 (설계 검토에서 "치명"으로 지적된 것)
`discovery_intake_requests.commit_payload`와 `local_intakes.metadata_json`은 재시도 대조(`GeneralLocalStore.kt` 125줄 근처)와 복구(146, 170, 189줄 근처)가 읽는다. **진행 중인 수집의 줄을 지우거나 비우면 그 녹음을 잃는다.** 줄 번호는 바뀌었을 수 있으니 직접 찾아 읽을 것.

## 할 일
1. **감사(코드로 확인, 보고서에 표로):** 위 표 상위 5개(`discovery_intake_requests`, `discovery_queue_rows`, `receipts`, `vault2_intake_receipts`, `vault2_pending`)와 `discovery_documents`에 대해 — 누가 언제 쓰는가, 누가 읽는가, 한 녹음이 끝까지 처리된 뒤에도 읽히는가, "끝났다"를 무엇으로 아는가, 녹음 333개에 요청이 2,078줄인 이유(재시도·재검색마다 새 줄인가), 한 줄이 평균 2,500자인 이유(무엇이 들어 있나). 추측과 확인을 구분.
2. **끝난 줄 치우기:** 감사 결과 "끝난 뒤에는 아무도 읽지 않는다"가 확인된 줄만 지운다.
   - 조건은 보수적으로: 그 수집이 완결 상태이고, 완결된 지 3일이 넘었고, 열린 복구·대기·재시도가 가리키지 않는 줄. 하나라도 불확실하면 남긴다.
   - 하루 한 번(`VaultPruneWork`)과 업데이트 뒤 첫 실행에서. 봉인 DB는 쓰기 트랜잭션마다 전체를 다시 쓰므로 **한 트랜잭션**으로 한다. 여러 번 돌아도 안전(멱등).
   - 첫 정리 전에 `.sealed` 사본을 7일 보관(사본은 원래 경로로 되돌려야만 열린다는 것을 복구 절차에 적는다; 7일 뒤 반드시 삭제 — 그 사이 지운 개인 통화 내용이 사본에 남으므로). `.sealed` 파일 이름·경로, `format=1`, `.bak`/`.new` 처리는 건드리지 않는다.
   - 통화 중이거나 자막 작업이 도는 동안에는 하지 않는다(다음 기회로).
3. **다시 쌓이지 않게:** 감사에서 원인이 "끝난 뒤에도 큰 본문을 그대로 둠"이나 "같은 파일을 볼 때마다 새 줄"로 확인되면 원천에서 줄인다(완결 때 본문 비우기, 중복 줄 합치기). 진행 중 수집의 동작은 바꾸지 않는다.
4. **조용히 넘지 않기(S4):** 쓰기 실패 사유 `TEXT_STORE_CAPACITY_REQUIRES_REVIEW`를 삼키지 않고 센다 → 하트비트, 신호등 "저장 공간이 다 찼어요"(주황), 워치독 규칙. 화면 문구는 `tools/check_ui_rules.py` 규칙(말줄임표 금지 등)을 지킨다.
5. **하트비트:** `dbPrune{day, atMs, deleted{표: 행 수}, charsBefore, charsAfter, skipped(사유 부호)}`(숫자·부호만), 기존 `dbTables`는 정리 뒤 다시 센 값. `dbPercent`가 어떻게 계산되는지(파일 길이 비율과 행 수 비율 중 큰 값) 확인하고, 정리 뒤 실제로 내려가는지(파일이 줄어드는지) 코드로 확인.
6. **시험:** 진행 중·대기·복구 중인 줄이 남는다, 완결 3일 전 줄이 남는다, 끝난 줄만 지워진다, 두 번 돌려도 같다, 정리 뒤 재시도 대조와 복구가 그대로 동작한다(기존 수집 시험 통과), 용량 실패가 세어지고 신호등에 뜬다. 큰 합성 DB(수집 원장 수천 줄)에서 정리 한 번의 시간이 합리적인지. 기존 시험은 전부 그대로 통과해야 한다.
7. **문서:** `docs/DESIGN_V7.md` 4.1절을 실측과 이번 결정으로 고쳐 쓰고(S1·S2는 "자막 비중 3.6%라 미룸"으로), 11절에 "다르게 정한 것" 행 추가. `docs/OPERATIONS.md`에 새 하트비트 키, 워치독 규칙, 사본 복구 절차. `docs/VERSION`·`docs/UPDATE_NOTE.txt`는 건드리지 말 것.

확인 기준(배포 뒤 하트비트로 판정): `dbPercent` < 30, `dbOpenMs` < 600, 하루 증가 < 0.3%p, 녹음 유실 0(자막 실패·수집 실패가 늘지 않음).

## 확인 방법
- 컴파일: `cd /home/claude/wt/store && . /home/claude/toolchain/env.sh && flock /home/claude/gradle.lock python3 run_gradle.py --log r18s.log :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` (단위 시험은 로컬에서 못 돌림 — Robolectric 없음; Java 소스가 있으면 해당 컴파일 태스크도).
- 파이썬: `python3 -m unittest discover -s tools/inbox -p 'test_*.py'`, `python3 tools/check_ui_rules.py`, `python3 tools/scan_export.py`.
- ci: `git push -u origin feature/r18-storage` 뒤 `gh api "repos/bllue583-commits/ieum-app/actions/runs?head_sha=<sha>"`에서 이름이 ci인 실행이 success일 때까지. 실패 원인은 `gh api repos/bllue583-commits/ieum-app/actions/jobs/<job id>/logs | grep '##\[error\]'`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 과 `Claude-Session: https://claude.ai/code/session_013kyAU6nSXKy8Jb23SkiER1`. `[skip ci]`를 넣지 말 것.

## 금지
- `feature/lean-calls`, `feature/r17-v37`에 push 금지(폰에 자동 배포된다).
- `/home/claude/ieum-inbox`의 `status/calllog.txt`, `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`, `report/` 본문을 읽지 말 것.
- 비밀번호·토큰·키를 파일에 남기지 말 것. 카톡 보고(`pipeline/report/`), 업데이트(`CallUpdate` 등), 통화 카드는 다른 작업자가 만지고 있으니 건드리지 말 것(`PhoneStatus.kt`는 새 키를 더하는 최소 변경만).

## 보고에 넣을 것
감사 표(표별 수명), 무엇을 어떤 조건으로 지우는지와 그 조건이 안전한 근거(코드 위치), 지우지 않기로 한 것과 이유, 예상 감소량(위 표 기준 몇 %가 지워질 수 있는지 — 폰 데이터를 볼 수 없으니 조건별 추정과 그 한계), 새 하트비트 키, 시험과 ci 결과, 실기기에서만 확인되는 것.
