# r14-4 보고서 (feature/r14-v34-r4, 마지막 d453e22, 기준 1fcb610)

d453e22에서 ci(https://github.com/bllue583-commits/ieum-app/actions/runs/42102144163)와 device-test(42102144430)가 성공했다.

## 결과
- call-card 체크 11개 중 10개가 true이고, 통화 대기 1개는 null이다.
  - 이 에뮬레이터는 통화 중에 둘째 벨이 와도 `mCallState=2`만 알리고 RINGING 방송을 보내지 않는다. 그래서 통화 대기 판정은 host 시험(R14OneCallTest)과 S26에 맡긴다.
- 벨부터 창이 뜰 때까지 걸린 시간(기준 3초)은 모두 기준 안이다.

  | 경우 | 걸린 시간 |
  |---|---|
  | 수신 | 1,081ms |
  | 역할 없이 발신 | 1,276ms |
  | 콜드(`am kill`) | 1,371ms |

- 잠금 상태에서 내용 있는 카드 알림이 뜨는 것은 확인했다. 창이 뜨는지는 r14-2 통합본에서 다시 본다(`info.lockedWindow`).

## 바꾼 파일
- **`tools/device/call_card_test.sh`**
  - 시나리오 4개를 더했다.
    - 역할 없이 발신: 역할을 회수하고 걸었다가 끝나면 다시 준다.
    - 통화 대기: 첫 통화는 카드가 있는 번호다.
    - 콜드: `am kill`을 최대 6번 시도하고, 그래도 안 되면 `run-as kill -9`를 쓴다. force-stop은 쓰지 않는다.
    - 잠금: 알림의 제목과 글 길이만 본다.
  - 시나리오마다 logcat을 `evidence/call-card.log`에 남긴다.
  - JSON에는 체크 11개와 `info`를 쓴다. false가 있을 때만 종료 코드 1을 낸다.
- **`tools/test_call_card_script.py`(새로 만듦)**: 문법, 체크 11개, force-stop이 없는지, logcat을 남기는지, 역할을 회수한 뒤 다시 주는지, 잠금 판정, null 처리를 시험한다.
- **`docs/REAL_DEVICE_CHECKLIST.md`(새로 만듦)**: S26에서 사용자가 10분 동안 할 체크리스트다. 카드 8항목, 문자 3항목, 결과 양식이 있다.
- **`screens/CallsScreen.kt`**: `transcribing()`과 `FRESH_MS`를 지웠다.
- **시험 파일**: `ScreensHomeCallsTest.kt`에서 assert 7줄을 지웠다. `CallLogListTest.kt`는 `FRESH_MS` 대신 리터럴을 쓴다.
- **문서**
  - `docs/DESIGN_V3.md` 7.1, 7.2, 7.5, 8절을 4.1-4 결정에 맞췄다.
  - `docs/PRODUCT.md` 3.1, 4.1, 4.2를 v3.2 홈에 맞췄다.
  - `docs/routines/self-report.md`와 `docs/OPERATIONS.md`를 루틴별 자가 보고와 루틴 안 워치독으로 고쳤다.

## 시험
- 로컬: `r14-4-compile-1`, `r14-4-test-1` 모두 BUILD SUCCESSFUL. tools 시험 16개 OK, `bash -n` OK. check_ui_rules 0, scan_export 0.
- GitHub에서 고친 과정

  | 커밋 | 결과 | 원인 |
  |---|---|---|
  | 63e9e44 | device 실패 | `am kill`이 프로세스를 못 끝냈다 |
  | e5cface | device 실패 | 잠금 판정 정규식이 맞지 않았고, 통화 대기가 `mCallState=2`였다 |
  | d453e22 | 성공 | |

## 설계와 다르게 정한 것
- 통화 대기는 시스템이 RINGING을 보내지 않으면 null로 두고, 실패로 치지 않는다.
- 콜드는 `am kill`을 먼저 쓰고, 그래도 프로세스가 남으면 `kill -9`를 쓴다.
- 잠금 판정은 내용 있는 알림만 본다. 창이 뜨는지는 기록만 한다.

## 워크플로 제안과 처리 (오케스트레이터, v3.4 통합 때)
1. ci에 `IEUM_SHOTS_DIR`를 넣고 `evidence/shots/*.png`를 산출물로 올린다. → 반영함.
2. device-test `paths`에 `tools/device/**`를 넣는다. → 반영함.
3. golden 선택지 `off`에 따옴표를 붙인다(YAML이 false로 읽음). → 반영함.
4. Actions 분량을 조정한다(device-test push 대상, inbox-e2e 하루 2회). → 저장소가 공개인 동안은 무료라 보류. 비공개로 돌리기 전에 반영한다.

## 다른 묶음이 알아야 할 것
- `evidence/call-card.json`에 새 키가 생겼다.
  - `outgoingNoRole`, `callWaiting`, `coldWindow`, `lockedNotice`
  - `info{ringToWindowMs, noRoleDialToWindowMs, coldRingToWindowMs, lockedWindow}`
- 샷 `card_outgoing_norole`, `card_waiting`, `card_cold`, `card_locked`를 더했다.
- 지운 것은 `CallsScreen.transcribing`과 `FRESH_MS`뿐이다.
