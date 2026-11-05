# r13-1 보고서 (feature/r13-v33-r1, 커밋 24c21d9, 기준 07f4b80)

ci 성공: https://github.com/bllue583-commits/ieum-app/actions/runs/42067495462

## 바꾼 파일
- **`NumberGuard.kt`(새 파일, 순수 함수)**
  - 한글 수사와 단위를 정규화한다. "이번", "사장", "구분", "천천히" 같은 말은 숫자로 바꾸지 않는다.
  - quote와 그 앞뒤 30초 구간을 대조한다. 시각이 없으면 ±6줄을 본다.
  - 날짜 판정: 날짜 표현이 없으면 date, 과거 날짜는 null, 60일을 넘으면 date.
  - 그 밖에 asr 판정, 혼동 쌍 메모, 세그먼트 시각 형식 `Times`를 담당한다.
- **`ClaudeCallAnalyzer.kt`**
  - `filter`가 create와 update에 가드를 적용한다. 판정은 `Task.guard`에 남고 tentative가 된다.
  - 인용 일치율이 0.7~0.9이면 버리지 않고 `quote`로 남긴다.
  - `ClaudeCallRequest.segmentTimes`를 더했다.
  - `corrected`는 원문 그대로 저장하고 다시 읽는다.
  - SYSTEM, SCHEMA, PROMPT_VERSION은 바꾸지 않았다.
- **`CallRecordStore.kt`**
  - segments_json에 줄별 시각과 불확실 숫자를 쓴다. 형식은 `{"v":1,"s":[시작 차 초],"d":[길이 초],"u":{"줄":["30"]}}`이다.
    - 글은 넣지 않는다. 1시간 통화가 약 3KB이고 최대 3000줄이다.
    - 14일 뒤 자막과 함께 지운다. 마이그레이션은 하지 않았다.
  - `request()`가 segmentTimes를 실어 보낸다.
  - 같은 업체, 7일 안에서 혼동 쌍이면 tentative로 하고 ai_note에 메모를 남긴다.
  - `GUARD_STATS`에 누계를 쓴다.
- **`screens/TasksScreen.kt`(CheckRows)**
  - "확인 필요" 배지를 단다. 18sp 굵게, 앰버 바탕에 #191F28 글자다.
  - `shortTitle`로 "○○ 님께/에게/한테" 접두를 지운다.
  - needs_confirmation 글을 "끝났는지 확인"으로 바꿨다.
- **`CallDigestWork.kt`**: 아침 알림의 tentative 항목 끝에 ", 확인 필요"를 붙인다.
- **`ContactCards.kt`**
  - thin 카드: 통화기록의 전화부 이름에서 번호를 뺀 이름과 30일간 통화 수를 보인다. 개인 번호와 제외 번호는 쓰지 않는다.
  - `Task.tentative`, `Recent.unsure`를 더했다.
  - 40자로 자를 때 띄어쓰기 경계에서 자르고 말줄임표를 쓰지 않는다.
- **`call/CallCardView.kt`**
  - thin 카드 문구는 "지난 통화 3일 전, 30일간 2번, 정리된 통화 없음"이다.
  - 배지 span을 단다.
  - UNKNOWN 문구는 "아직 정리된 통화가 없는 번호예요"다.
  - `publicText()`는 금액과 긴 숫자를 지운다.
- **`call/CallCardOverlay.kt`**: `LOCK_SCREEN_DETAIL`(기본 켬)이 켜져 있으면 잠금 화면 publicVersion에 이름, 업체, 체크할 일 한 줄을 보인다.

## 시험
- `NumberGuardTest` 9개
- `GuardStoreTest` 6개
- `CallCardOverlayTest` +3개
- 옛 동작을 검사하던 시험 3개를 설계대로 고쳤다: CallRecordStoreTest의 segments_json, ClaudeCallAnalyzerTest의 0.87 인용, UNKNOWN 문구.

## 로컬 결과
- `r13-1-compile-1` BUILD SUCCESSFUL
- `r13-1-test-3` BUILD SUCCESSFUL
- check_ui_rules 0건, scan_export 0건

## 설계와 다르게 정한 것
- 배지는 가드가 낸 tentative와 모델이 낸 tentative("알아볼게요")에 똑같이 보인다. 둘은 같은 칸이다.
- needs_confirmation 글을 "끝났는지 확인"으로 바꿨다. CheckRows 안에서만 바꿨다.
- 숫자는 값과 단위만 대조한다. D/H 접두는 무시한다.
- done_suggested와 옛 형식 promises에는 가드를 적용하지 않는다.
- 혼동 쌍 메모는 ai_note에 남기므로 화면에 "AI 메모:" 줄로 보인다.
- GUARD_STATS는 `CallRecordStore.applyAnalysis`에서 센다.
- thin 카드 덕분에 `hasCard`가 참이 되어 noCard가 줄어든다. 의도한 결과다.

## 넘긴 것
- **r13-3**
  - CheckRows의 배지를 CallUi로 옮긴다.
  - CallsScreen에 배지를 단다.
  - 설정 스위치 글을 바꾸고 `LOCK_SCREEN_DETAIL` 스위치를 넣는다.
- **통합**
  - `PROMPT_VERSION=7`로 올리고 SYSTEM과 SCHEMA에 `corrected`를 반영한다(hourly v7과 함께).
  - PhoneStatus에 `guard` 블록을 넣는다.

## 바뀐 시그니처
모두 뒤에 기본값이 있는 인자를 더한 것이다.
- `ClaudeCallAnalysis.Task(+guard,+corrected)`, `ClaudeCallAnalysis(+corrected)`
- `ClaudeCallRequest(+segmentTimes)`
- `ContactCard(+thin)`, `ContactCard.Task(+tentative)`, `ContactCard.Recent(+unsure)`
- `CallCardView.Content(+summaryUnsure,thin,thinShort)`, `Content.Task(+unsure)`
