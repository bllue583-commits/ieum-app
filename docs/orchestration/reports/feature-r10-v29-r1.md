# feature/r10-v29-r1 전화 상태 감지 (372adbe, 기준 e368728) — 작업자 보고 요약
- 신설 `pipeline/call/CallSession.kt`(object, SecureSettings 키 `callSession`, 개인·제외 번호 거름, 끝나면 listeners와 aftercall), `pipeline/call/CallStateReceiver.kt`(PHONE_STATE, exported, goAsync, 전용 스레드 순서 처리). 매니페스트 `READ_PHONE_STATE`·수신기.
- `AutomaticRecordingDiscovery.kt`: `AFTERCALL_NAME="ieum.discovery.aftercall"`, `AFTERCALL_WAIT_MS=15_000`, `afterCallRequest`, `scheduleAfterCall(context)`, `AfterCallDiscoveryWorker`(expedited, 지연은 워커 안에서 15초 — WorkManager가 expedited 지연 불허). 안드로이드 11 이하 채널 `ieum.discovery.aftercall`(MIN), 알림 id 731.
- 화면: 처음 설정 "통화가 끝난 걸 바로 알아차리기"(정리 동의 앞, 7→8단계, [전화 상태 허용], 두 번 거절이면 [앱 정보 열기]), 설정 "연결" 묶음 "통화 끝 알아차리기"(`phone_state_state`, `phone_state_allow`). `HomeScreen.Snapshot.phoneState`.
- API: `State{IDLE,RINGING,OFFHOOK}`, `Direction{IN,OUT}`, `Snapshot(state,phoneKey,direction,sinceMs,ignored=false)`, `current()`, `CallEnd(phoneKey,direction,startedMs,endedMs,answered)`, `listeners`(전용 스레드 `ieum-call-session`, 못 받은 전화도 알림), `onPhoneState(context,extraState,rawNumber)`, `STALE_MS`=6시간.
- 다르게 정한 것: 새 녹음은 partial 규칙(2분)으로 aftercall에서는 발견만, 저장은 followup(150초 뒤) → 통화 종료부터 저장까지 약 2.5~3분. 통화 대기 RINGING은 무시. 설정 못 읽으면 개인으로 간주. `pendingOutgoing`은 v3.0(r11-1)로 남김.
- 남은 것: `CallFollowupNotice.onCallEnded` 호출 주석(통합 때 풂). 기기 시험 안 함.
- 시험: `CallSessionTest` 13건, `CallUiSeniorTest` 5/7→6/8. 전체 `r10-1-test-2` 479건 통과 + lint, ui rules 0, scan 0.
