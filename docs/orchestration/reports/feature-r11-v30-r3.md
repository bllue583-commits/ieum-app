# feature/r11-v30-r3 통화 카드 시험과 안전장치 (f413488, 기준 77b544b) — 작업자 보고 요약
- 신설 `pipeline/call/CardStats.kt`(통화마다 한 번, 누적+오늘(KST), 설정 `cardStats`; 번호 없음은 IDLE까지 번호가 안 왔을 때만; 개인·제외·스위치 꺼짐·역할 없는 발신은 안 셈). `CallCardService.verdict()`·`stats`, `CallCardOverlay.outcome` 기본값 `CardStats.record`. 판정용 로그 4줄(번호·이름 없음): `card shown unknown=…`, `card collapsed`, `onPlaceCall`, `placeCallUnmodified`.
- 하트비트 `callCard:{shown,noPermission,noNumber,noCard,day,today:{…}}`(없으면 null). 신호등 6번째 줄 `status_card` "통화 카드"(오버레이·전화 상태 없으면 주황, 역할 없음은 초록+문구, 스위치 둘 다 꺼짐이면 초록), `GROUP_CARD`. watchdog `T_CARD_PERMISSION`(오늘 noPermission 3회 이상 → "통화 카드 권한이 꺼져 있어요").
- `tools/device/call_card_test.sh`(7.4: 수신 창·3초 띠·끊으면 창과 서비스 없음·발신 `am start ACTION_CALL`·모르는 번호 꺼짐·개인 번호 아무것도 없음 → `evidence/call-card.json`, 하나라도 false면 종료 1), androidTest `CallCardSeedTest`(PreviewSeed 01011112222 카드, 01077778888 개인). FLAG_SECURE라 스크린샷에는 카드가 검게 찍힘 → 판정은 창·로그·서비스·알림, 내용은 Robolectric.
- 시험 `CallCardSafetyTest` 11건 + PhoneStatusTest·UiV2Test·watchdog. 전체 `r11-3-full-1` 533건 통과 + androidTest 컴파일, ui rules 0, scan 0. 에뮬레이터 실행은 device-test에서.
- 잠금 화면 폴백 에뮬레이터 시나리오는 넣지 않음(Robolectric만).
