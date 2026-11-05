# R4 통화 카드·전화 감지 검토 (Opus, 11/5 17시, 기준 157ffab) — 점수 5/10
총평: 구조(번호 미노출, FLAG_SECURE, 카드 안 터치, placeCallUnmodified finally, 시작 거부 시 직접 띄우기+재시도)는 좋음. 그러나 잠긴 폰에서 벨이 울리면 오버레이 대신 내용 가려진 알림만(가장 흔한 상황), 콜드 스타트에서 개인 번호 확인이 큰 DB를 열어 카드 지연, 상대 카드가 있는 번호가 거의 없어 noCard가 대부분. 시험은 잠금 없는 API 34 에뮬레이터·앱 띄운 직후라 S25(안드로이드 15+) 백그라운드 FGS 제한·잠금·콜드·삼성 통화 화면은 미증명.

## 발견(심각도순)
1 높음 잠금 화면: isKeyguardLocked만 보고 오버레이 시도조차 안 함(CallCardOverlay.kt:69,:180,:245-259). 잠금 위 통화 화면(occluded)에서는 오버레이가 보일 수 있음 → 먼저 addView 하고 실제 보이는지로 폴백 판단. S25 실측 필수.
2 높음 통계: 잠금 폴백이 noPermission으로 세어짐(CallCardOverlay.kt:161) → watchdog(:171-179)이 "권한 꺼짐" 틀린 경보. locked 사유 분리, 경보는 canDrawOverlays=false일 때만.
3 높음 콜드 스타트: 벨 → 개인 번호 확인이 보호 DB 전체 복호화(CallSession.kt:160-169 → IeumApplication.kt:39-50). 개인 번호 키 집합을 작은 캐시/설정에. 하트비트에 ringToCardMs·cold.
4 높음 데이터: 카드는 ContactCards에 있는 번호에만(CallCardService.kt:120-122). 장부 사람 4·업체 4 → 거의 noCard. 통화기록 기반 최소 카드("지난 통화 n일 전, 30일 n번") 검토.
5 중간 문구: "전화부에 없는 번호도" 켜면 전화부에 있는 번호도 카드 없으면 "전화부에 없는 번호예요"(CallCardView.kt:59,:76) → "아직 정리된 통화가 없는 번호예요".
6 중간 가림: 거는 전화 카드 15초 펼침, 세로 38~78% → 삼성 통화 중 버튼 격자(스피커·음소거·키패드) 가림, ARS 키패드 막힘(CallCardOverlay.kt:58,:188-189). OFFHOOK 펼친 카드는 위쪽 25% 이하, 거는 전화도 3초 뒤 접기.
7 중간 안드로이드 15+ FGS: SAW 예외는 "보이는 오버레이 창이 있을 때만" → 첫 startForegroundService 거부, 재시도로 살아남(CallCardService.kt:95-102). 잠금이면 재시도도 실패. 설계 7.2·8절 문장 수정, 하트비트 fgsRefused/fgsStarted.
8 중간 서비스 잔류: 상태 전달마다 startForegroundService(CallCardService.kt:90-96,:178-201) → 거부되면 IDLE 못 받고 알림 단 채 최대 10분. 프로세스 안 정적 참조로 전달, start는 첫 시작에만.
9 중간 통화 대기: OFFHOOK 중 RINGING 무시(CallSession.kt:123-128) → 첫 상대 카드가 두 번째 통화 내내 남음. 숨기기.
10 중간 발신 역할 시간 초과 대화상자 가능성(OutgoingCallService.kt:24-36), 콜드 스타트 Application.onCreate 메인 스레드. S25 실측, 하트비트 onPlaceCall→응답 ms.
11 중간 역할 필요성: 안드로이드 9+는 READ_CALL_LOG 있으면 거는 전화 OFFHOOK에도 번호(CallSession.kt:139 known=out ?: key)인데 판정은 역할 없으면 막음(CallCardService.kt:116). 역할 단계 삭제 가능성 검증.
12 중간 3초 가독성: 첫 줄 이름(삼성도 보여 줌), 약속은 19sp 40자, 아래 체크 3줄+진행 중 → 많음(CallCardView.kt:128-157). 첫 줄을 "지난번: 견적서 금요일까지"(20sp 굵게), 이름·업체 회색 한 줄, 체크 1줄+"외 n개".
13 낮음 띠: 근접 센서로 화면 꺼져 스피커폰 때만 보임; 요약·할 일 없으면 띠 숨김.
14 낮음 오버레이 권한 없어도 매 통화 HIGH 헤드업 알림(CallCardService.kt:112-123) — 설계 8절과 다름.
15 낮음 신호등 "통화 카드"가 READ_CALL_LOG·noNumber 비율 미반영(StatusLights.kt:92-100).
16 낮음 잠금 폴백 때 알림 두 개(서비스 741 + 카드 3011).
17 낮음 filterTouchesWhenObscured 가능성 — S25 받기·거절 실측.
18 낮음 버킷 EXEMPTED 아니면 PHONE_STATE 막힘 → 신호등·watchdog 주황.
## 시험이 증명 못 한 것
백그라운드 FGS 제한(앱 띄운 직후 유예), 안드로이드 15 조건(API 34), 잠금 화면, 실제 권한·역할 대화상자(-g/appops/cmd role 강제), 위치·가림(FLAG_SECURE 0바이트), 콜드 프로세스, 통화 대기, 삼성 다이얼러, 발신 시간 초과. CardStats에 시간 측정 없음(CardStats.kt:21-24).
## 설계 대조(다른 것)
걸 때 15초 펼침(7.5), 모르는 번호 = 카드 없음으로 표시, 번호 2초 대기 대신 늦게 온 번호로 채움, "SAW 있으면 FGS 허용"은 14까지, "SAW 없으면 서비스 안 시작" 미구현, 발신 시간 초과 미검증, data #21 공유 번호 "외 n명"만.
## 개정 제안 10
1 잠긴 폰 카드(오버레이 먼저, 보이는지로 폴백) 2 통계 바로잡기+ringToCardMs/locked/fgsRefused/cold 3 콜드 경로에서 큰 DB 빼기(개인 번호 키 캐시, am force-stop 시나리오) 4 최소 카드(통화기록) + 문구 5 카드 글 3초용(약속 먼저) 6 OFFHOOK 위치 25%·거는 전화 3초 접힘 7 역할 필요성 검증 후 단계 삭제 8 서비스 상태 프로세스 안 전달·통화 대기 숨김 9 S25 실기기 체크리스트(real-device-test) 10 설계 문장 바로잡기(7.2·8절).
