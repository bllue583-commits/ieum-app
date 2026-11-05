# feature/r10-v29-r3 지연 단축 (289d4c1, 기준 e368728) — 작업자 보고 요약
- `CallRecordWork.kt`: `ResultChain`(업로드 직후 `ieum.calls.results` 10분 뒤, 기다리는 기록 있으면 10분 간격 최대 12회, 새로 보내면 1회차부터, 없으면 멈춤, APPEND_OR_REPLACE), `CallResultsWork`를 `CallSinkImpl.kt`에서 옮김(입력 키 `round`, 옛 `attempt`는 1로), `InboxETags`(빈 결과 목록 ETag만 설정 `resultsListingETag`에 저장, 304면 본문 없이 끝), `ETagSlot`(runInbox 첫머리, 스토어 열기 전).
- r10-2 자리: `applyAnalysis` 다음 줄에 `CallFollowupNotice.onResult(context,recordId)`, `finally`의 `warnIfStalled` 옆에 `sweep(context,now)`.
- `PhoneStatus`: 하트비트 `resultChain:{round,nextMs}`(체인 없으면 null). 설정 키 `resultChainRound`, `resultChainNextMs`.
- `docs/routines/hourly.md` 머리말 "루틴 시간: 30분마다(cron `*/30 7-22 * * *`, Asia/Seoul)". `tools/inbox/watchdog.py` "분석이 멈췄어요" 3시간은 루틴 도는 시간(07~23시)만 셈 → ieum-inbox에 `tools/watchdog.py`로 다시 설치 필요. `docs/DEVELOPMENT.md` "push 때 루틴 깨우기" 제안.
- 시험: `CallRecordWorkChainTest` 8건 신설, PhoneStatusTest·RoutineDocTest 보강, CallInboxTest 옛 :45 슬롯 1건 삭제, 파이썬 watchdog 22건. 전체 `r10-3-test-all-5` 473건 통과, ui rules 0.
- 사용자 할 일: 예약 작업 "이음 통화 분석 (매시간)" 실행 일정 `30 * * * *` → `CRON_TZ=Asia/Seoul */30 7-22 * * *`(프롬프트 본문은 그대로). 바꾼 뒤 HANDOFF·OPERATIONS의 "매시 30분" 문구 고치기.
- 설계와 다르게: ETag는 빈 결과 목록만(장부는 이름이 있어 디스크에 두지 않음), 체인은 시간대 제한 없음(12회면 끝).
