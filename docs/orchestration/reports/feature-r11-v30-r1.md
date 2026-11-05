# feature/r11-v30-r1 거는 전화 번호와 통화 카드 서비스 (cf4edb1, 기준 0ca80a6) — 작업자 보고 요약
- 신설 `pipeline/call/OutgoingCallService.kt`(CallRedirectionService, API 29+, `onPlaceCall` → `CallSession.pendingOutgoing` → finally에서 항상 `placeCallUnmodified()`), `pipeline/call/CallCardService.kt`(FGS `phoneCall|specialUse`, 알림 id 741, 채널 `ieum.calls.cardservice` "통화 중 이음 카드" MIN), `CallCardOverlayStub.kt`(통합 때 삭제).
- `CallSession`: `OUTGOING_WINDOW_MS=20_000`, `pendingOutgoing(number,atMs)`(번호 키 메모리만, 첫 OFFHOOK에서 소비). `CallStateReceiver` 끝에 `CallCardService.follow(app,CallSession.current())`.
- 카드 호출(메인 스레드): RINGING에 show(RINGING), OFFHOOK에 show(OFFHOOK) → 3초 뒤 collapse, IDLE에 hide → 3초 뒤 stopSelf. 10분마다 `TelecomManager.isInCall` 점검(통화 없으면 종료), 4시간 무조건 종료. 시작 거부 시 카드 직접 + 0.5초 뒤 재시도. 번호는 Intent에 싣지 않음.
- 조건: ignored 아님, 번호 앎, 방향 스위치 켬(거는 전화는 역할도), ContactCards 있음 또는 `CARD_UNKNOWN` 켬.
- 설정 키 `cardIncoming`(켬), `cardOutgoing`(켬), `cardUnknown`(끔), `cardPosY`. 권한 `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE(_PHONE_CALL/_SPECIAL_USE)`, `MANAGE_OWN_CALLS`, 역할 `CALL_REDIRECTION`(선택).
- 화면: 처음 설정 10단계("통화 화면 위에 이음 카드 보기", "거는 전화에도 카드 보기" — 정리 동의 앞), `CallCardAsk`, 설정 "통화 카드" 묶음(연결과 정리 사이, 스위치 셋, [카드 위치 처음으로]).
- 시험 `CallCardServiceTest` 15, `CallCardSettingsTest` 3. 전체 `r11-1-test-5` 510건 통과, lint 통과, ui rules 0, scan 0.
- PRODUCT.md 문장: "포그라운드 서비스 금지(v2.0)의 예외: v3.0의 CallCardService는 전화가 울리거나 통화 중일 때만 돌고(끊으면 3초 뒤, 상태를 놓쳐도 10분 점검에서 종료), 알림도 그 통화의 카드 내용이라 상시 알림이 생기지 않는다."
