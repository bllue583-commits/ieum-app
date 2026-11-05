# r12-5 보고서 (처음 설정과 홈, 업데이트 상태 화면) — feature/r12-v31-r5 bcea3e4

- HomeScreen.kt: 처음 설정 필수(폴더, 정리 동의, 통화기록, 전화 상태, 알림, 배터리) → 선택(문자, 카드 보기, 자동 설치), [나중에 할게요](SetupSkip), 거는 전화 역할은 설정에만. "처음 설정 남은 n단계"/"다음 할 일: …", 이유 문장 BODY 19 INK 버튼 위, 카드 보기 미허용 시 굵은 안내+[다시 열기]. 모델은 동의 직후 어떤 연결에서든 받기 시작, 홈 줄 "음성 인식 모델 받는 중 42% (241MB / 574MB)" 1초 갱신, ModelWish·Wi-Fi 문구 삭제. 홈 배너 순서: 녹음 공백(home_gap → 설정 녹음 가져오기) → 업데이트 → 모델 → 처음 설정 → 처음 정리(한 줄로 접힘). 시험 훅 gapHook·updateHook·setupStepHook·checkHook·modelStart.
- SettingsScreen.kt: "앱 버전" 줄에 CallUpdate.state 문구·막대·[지금 업데이트]·[지금 확인], "설치 허용" 줄, 모델 줄 퍼센트, show(group,tag).
- CallsScreen.kt: FAILED+RESULT_NOT_APPLIED에 "정리 결과를 적용하지 못했어요"+[다시 정리하기](CallRetry.resend).
- MainActivity.kt: 앱 시작 때 CallUpdate.checkNow.
- 시험: FirstSetupHomeTest 12건, ScreensHomeCallsTest·CallUiSeniorTest·CallCardSettingsTest·CallCardSafetyTest 맞춤. r12-5-test-3 전체 546 중 2 실패(RoutineDocTest — r12-3이 고침, CheckCompanySettingsTest 슬라이더 — 따로 돌리면 통과).
- 설계와 다르게: 처음 정리 배너에 쉬는 이유, 처음 설정은 첫 실행만 펼침, state가 none이면 v2.5 카드 유지.

## 통합 때 오케스트레이터가 고친 것
- 통화 목록 줄에 r12-1의 stateWords(LoggedCall) 연결(창 안 자막 작업 → "글자로 바꾸는 중", 끝 뒤 10분까지 "녹음을 찾는 중이에요", 그 뒤 "녹음 없음").
- [다시 정리하기]는 CallRecordStore.forgetSent(id)로 보낸 기록을 지워 다음 패스에 바로 보냄(6시간 대기 없음).
- StatusLights 모델 문구의 "Wi-Fi에서" 삭제.
- 설정 "앱 버전": 홈 [지금 업데이트]의 설치 바람(takeInstallWish)을 상태 줄보다 먼저 처리(받은 뒤 바로 설치로 — 회귀 수정).
- CallUpdate.state 문구 조사: "새 버전(v3.1)이 나왔어요/준비됐어요", 받기 대기 중 알려진 버전은 "새 버전(v3.1)을 받을 준비 중이에요."
- UpdateFetchTest 세 건과 ScreensHomeCallsTest 한 건을 v3.1 동작으로.
- 전체 :app:testDebugUnitTest 582건 실패 0(int31-test-6), check_ui_rules 0, scan_export 0.
