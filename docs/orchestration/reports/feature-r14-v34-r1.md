# r14-1 보고서 (feature/r14-v34-r1, 커밋 7b88d9b, 기준 8293f5b)

## 시험 결과
- ci: success (https://github.com/bllue583-commits/ieum-app/actions/runs/42082638601)
- device-test: failure (42082638602)
  - 실패 원인은 v3.3부터 있던 t4의 1.3배 제목 폭 판정이다. lean-calls 53c41a5에서 고쳤다.
  - call-card: 첫 실행에서 첫 수신 벨이 창을 띄우지 못했다(ringingWindow=false). 다시 돌리니 7개가 모두 true였고, 벨부터 창까지 2,283ms였다(v3.3은 1,095ms). 원인은 정하지 못했다. 앱이 켜진 직후 첫 통화에서 store-open이나 warm과 겹쳤을 것으로 의심한다. 지켜봐야 한다.

## 바꾼 것
- **CallSession**
  - 통화 대기 중이면 `waiting`을 켠다.
  - 설정은 통화마다 `callSettings()` 스냅숏 한 번으로 읽는다.
  - 카드를 알린 뒤에 persist한다.
  - 개인 번호는 `ContactCards.blockedState`로 판정한다. blocked 표가 아직 없을 때만 옛 경로(큰 DB)를 쓴다.
  - 통화가 끝나면 `afterCall`을 부른다.
- **CallStateReceiver**: 방송을 받은 시각과 콜드 여부를 `CardStats.signal`에 넘긴다.
- **CallCardService**
  - 역할 조건을 없앴다.
  - 판정 결과는 SHOW, NO_NUMBER, NO_CARD, IGNORED, OFF, WAITING이다.
  - 이미 도는 서비스는 `running`으로 바로 부른다.
  - 시작이 거부되면 센다.
- **CardStats(새로 씀)**
  - 4.1-5의 키를 모두 센다.
  - ring→card 표본을 50개 남기고, 콜드 표본은 따로 둔다.
  - locked와 noPermission을 나눈다.
  - `expanded()`와 `closed(shownMs)` API를 만들었다. r14-2가 연결한다.
- **ContactCards**
  - blocked·card_meta 표를 두고, 규칙 지문이 바뀌면 다시 쓴다. 통화가 끝나면 개인 번호를 배운다.
  - 카드 JSON을 v2로 바꿨다(dueMs, checkReason, lastDurationMs, calls30d, version). v1은 다음 flush 때 v2로 다시 쓴다.
  - `opens`, `lastOpenMs`를 센다.
- **PhoneStatus** `V34`
  - `callCard`에 키를 모두 올린다.
  - `update.error`, `messages.mailError`, `recordTime.fileVsRecordMin` 자리를 만들었다.
  - 숫자와 코드만 올린다.
- **HomeScreen**: `CallCardAsk.roleDone`을 지웠다.
- **SettingsScreen**
  - 역할은 선택 항목 "거는 전화에 번호를 더 빨리 알기"로만 남겼다([허용하기]/[괜찮아요] → `ROLE_OFFER_SEEN`).
  - 신호등은 발신 `noNumberOut`이 있을 때만 역할을 권한다.

## 시험
- 새로 넣은 시험
  - `R14OneCallTest` 11개: 역할 없는 발신, 통화 대기 2, 시작 거부, null/0, locked, noNotify, ring 시간, 콜드 경로(store open 0, 카드 파일 1, 설정 1), blocked 실패
  - `R14OneCardsTest` 4개
  - `PhoneStatusHealthTest` +1개
- 옛 동작을 기대하던 시험 `CallCardServiceTest`, `CallCardSafetyTest`를 새 동작에 맞게 고쳤다.
- 로컬 결과
  - `r14-1-compile-1`, `r14-1-test-1~3` 모두 실패 0
  - check_ui_rules 0, scan_export 0

## 설계와 다르게 정한 것
- **checkReason**: `check_reason` 열이 없어서, ai_note가 있으면 `confusable`, 나머지 tentative는 `unsure`로 둔다.
- **dueMs**: due_text가 "YYYY-MM-DD"일 때만 채운다.
- **blocked 표 이전**: 표가 한 번도 쓰이지 않았을 때(업데이트 직후 첫 통화)는 큰 DB로 판정한다. 읽기 예외가 나면 개인으로 친다.
- **통화 대기**: OFFHOOK에 `waiting=true`를 둔다. 둘째 통화의 번호로 첫 통화의 빈 번호를 채우지 않는다.
- **locked 판정**: CardStats가 직접 판정한다. 잠금 상태에서 창과 알림을 함께 띄워도 locked로 센다.
- **noNotify, fgsRefused**: 부가 카운트이고, 통화당 1번만 센다.
- **cold**: 프로세스 시작 뒤 5초 안에 첫 방송을 받으면 콜드로 본다.

## 못 한 것
- 역할 없는 발신 에뮬레이터 시나리오는 넣지 못했다(r14-4 소유).
- expanded와 closedUnder2s를 오버레이에 연결하지 못했다(r14-2 소유).
- `CallCardOverlay.storeText`의 모르는 번호 경로는 아직 큰 DB를 연다(r14-2 소유).

## 다른 묶음이 알아야 할 것
- **하트비트 `callCard`**
  - 키: calls, shown, shownOutgoing, eligibleOutgoing, noCard, thin, noNumber, noNumberIn, noNumberOut, noRole, ignored, off, locked, cold, fgsRefused, noPermission, noNotify, expanded, closedUnder2s, day, today{…}, ringToCardMs{p50,max,n}, ringToCardMsCold{…}, cardOpenMs
  - 워치독의 권한 경보(`today.noPermission`)와 호환된다.
- **바뀐 시그니처**: `CallCardService.verdict`, `Snapshot.waiting`, `onPhoneState(persistLater)`, `CardStats.KINDS`, `ContactCard`와 `Task`의 v2 필드.
