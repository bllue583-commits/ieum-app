# r14-2 보고서 (feature/r14-v34-r2, 커밋 74d55a4, 기준 1fcb610)

DESIGN_V4 4.2절 1~5와 r14-1에서 넘어온 네 가지를 구현했다(expanded/closed 연결, 벨 경로 큰 DB 제거, v2 필드로 첫 줄, 잠금 창+알림).
- ci: success (https://github.com/bllue583-commits/ieum-app/actions/runs/42094390543)
- 로컬 `r14-2-test-3~5`: 실패 0
- check_ui_rules 0, scan_export 0
- 카드 샷 4종을 다시 찍었다.
  - 1.0배: 배지가 있는 카드에서 "외 1개"가 둘째 줄로 넘어간다. 다시 확인하지 않았다.
  - 1.3배: 6줄이다.

## 바꾼 파일
- **`call/CallCardView.kt`(새로 씀)**
  - 첫 줄 고르기 순서: ① 기한이 지났거나 7일 안 ② 확인 필요 ③ 14일 안 요약 ④ 진행 중인 일 ⑤ thin
  - 첫 줄 규칙
    - 36자 안에서 띄어쓰기 경계로 자르고, 말줄임표는 쓰지 않는다.
    - 기한 표현은 "금요일까지", "2일 지났어요" 꼴이다.
    - 전화번호만 가린다.
  - 둘째 줄: "이름 직책, 업체, 3일 전"
  - 셋째 줄: 체크할 일 + "외 n개". 넷째 줄: 진행 중인 일.
  - `noticeLines()`와 `strip()`도 같은 순서로 보인다.
  - 맨 위 줄에 손잡이, [접기], [닫기]를 둔다.
  - `spoken()`(TalkBack 순서)을 더했다.
- **`call/CallCardOverlay.kt`**
  - 잠금 상태에서는 창과 알림을 함께 띄우고, USER_PRESENT가 오면 알림을 지운다. 서비스가 도는 동안에는 741 자리를 쓴다.
  - 위치 규칙
    - 벨: 높이 34%, 아래 끝 72% 이하. 끌어 옮긴 위치도 이 범위로 당긴다.
    - 통화 중 띠: 위 8~20%.
    - 발신: 위 4~25%에 펼친 채 3초 뒤 접는다. `COLLAPSE_OUT_MS`는 지웠다.
  - 내용 없는 카드는 OFFHOOK에서 숨긴다.
  - 모르는 번호는 카드 파일과 통화기록(`logLine`)만 읽는다. `storeText`는 지웠다.
- **`CallFollowupNotice.kt`**
  - 발신은 통화기록 길이로 판정한다.
    - 0초면 알림을 띄우지 않는다.
    - 길이를 아직 모르면 HOLD로 둔다. 다음 발견에서 확정하거나 10분 뒤 지운다.
  - 수신 0초는 조용히 지운다.
  - 통화기록 권한이 없으면 예전처럼 동작한다.
  - 잠금 글은 상태별로 다르다(정리 중 / 정리됨 / 확인할 통화).
  - Entry에 `d`를 더했다.
- **`CallDigestWork.kt`**
  - 저녁 보고 때 정리 대기 id를 남긴다(`digestEveningWaiting`).
  - 아침 보고 끝에 "늦게 정리된 통화 n건"을 붙인다.
  - 자가 점검 입력에 루틴이 놓친 횟수를 넣는다.
- **`RecordingGap.kt`**
  - 판정 `pendingText`를 더했다. 파일이 있고 자막 작업이 QUEUED나 RUNNING일 때다.
  - `Verdict.pending`, `Facts.pendingTimes`, `pendingText()`를 더했다.
- **`StatusLights.kt`**
  - 카드 줄: 통화기록 권한, 대기 버킷, noNumber 20% 이상(5건부터), noCard 60% 이상을 본다.
  - 녹음 줄: pendingText를 초록 글로 보인다.
  - 정리 줄: 2회 놓치면 주황 "분석이 n시간 안 돌았어요". 저장 공간 80%도 주황이다.
  - `withPhone()`, `routineMissed()`를 더했다.
- **`SelfCheckNotices.kt`**: 4회 놓치면 저녁 자가 점검 `routine_late`를 띄운다.
- **`screens/SettingsScreen.kt`**(최소 수정): `lightsInput`을 `StatusLights.withPhone`으로 감쌌다.
- **시험**
  - 새로: `R14TwoCardTextTest`, `R14TwoTest`
  - 다시 씀: `CallCardOverlayTest`, `CallCardShotsTest`
  - `GuardStoreTest`에서 thin 한 줄을 고쳤다.

## 설계와 다르게 정한 것
1. 가운데 점 대신 쉼표를 쓴다(화면 규칙).
2. 상대 날짜는 줄 끝에 둔다(설계 예시대로).
3. 셋째와 넷째 줄 제목은 14자로 자른다. 기한은 첫 줄에만 쓰고, 배지가 있으면 4자를 더 줄인다.
4. "4회면 빨강"은 빨강 대신 저녁 자가 점검 알림으로 한다(빨강 금지).
5. 슬롯이 지나고 40분이 넘도록 자가 보고가 없으면 놓친 것으로 센다. 받은편지함 오류가 `routineEndedMs`보다 늦으면 세지 않는다.
6. 비율은 누적값으로 낸다(7일 기록이 없어서).
7. 배터리 최적화에서 빠진 앱이면 EXEMPTED로 본다.
8. 741/3011 합치기는 오버레이 쪽에서 한다(서비스 파일은 고치지 않았다).
9. 모르는 번호 카드는 "최근 문자" 대신 통화기록 한 줄을 보인다.
10. 늦게 정리된 통화는 CallDigestWork가 센다.
11. pendingText는 알릴 문제로 보지 않는다.

## 못 한 것
- S26 실측은 체크리스트로 한다. 위치 상수는 일단 안전한 기본값으로 두었다.
- 1.0배 배지 카드에서 "외 1개"가 넘어가는 것은 다시 확인하지 않았다.

## 다른 묶음이 알아야 할 것
- 새 설정 키: `digestEveningWaiting`
- `recordingGap`에 판정 `pendingText`와 `pending` 숫자를 더했다.
- 통화 알림에 Entry `d`와 상태 `hold`를 더했다.
- 바뀐 API
  - `CallCardView.Content`를 새로 만들었다.
  - `CallCardOverlay`: `MAX_HEIGHT` 0.34. `COLLAPSE_OUT_MS`, `latestText`를 지웠다. `logLine`, `serviceUp`, `onExpanded`, `onClosed`, `relayout(force)`를 더했다.
- 카드 태그: `card_first`, `card_who`, `card_task`, `card_issue`, `card_bar`를 쓴다. `card_name`, `card_summary`, `card_last`는 없앴다.
