# v2.6 통합 기록 (feature/r7-v26)

## 묶음 보고 요약
- r7-1 (bca22f6, 합침): SKIP_BEFORE_START_SQL(열 때 10/1 이전 QUEUED→SKIPPED), oldestOldJobMs는 10/1 이후만, offerAll 트랜잭션 1회+중지 신호, live=7일(FirstPass.LIVE_DAYS, liveFrom/isOld(now)/stale; beforeStart 삭제), 덜 찬 묶음 6시간(createdMs 기준), LAST_INBOX_ERROR 모든 코드+LAST_INBOX_ERROR_MS, 하트비트 firstPass.bound/blockedBy(설정 firstPassBoundMs/firstPassBlockedBy). 테스트: FirstPassTest +6, PhoneStatusTest, RequestV3Test·ReviewRoundTwoTest 기준 갱신. 담당 밖: CallPorts 키 1줄, ShadowTextAtomicFile writes 카운터.
- r7-3 (a9a1f48, 합침): CallUpdate.installInBackground(USER_ACTION_NOT_REQUIRED, API31+), 세션 id/코드를 prefs ieum.update에, UpdateStatusReceiver PENDING→알림, SUCCESS→키 0·알림 삭제, FAILURE→LAST_UPDATE_ERROR 80자, settleIfInstalled. 키 UPDATE_AVAILABLE_CODE/UPDATE_READY_SINCE_MS/AUTO_INSTALL(PipelineSettingsKeys). 설정 스위치 auto_install_switch. 테스트 UpdateAutoInstallTest 6개(미판정). update-smoke 제안(디버그 수신기 필요, 별도 묶음).
- r7-5 (373bb96, 합침): hourly.md v6(4번 장부 사람·업체 규칙, 7번 (5)(6), 8번 날짜, 11/12/16/19 보강; 10번 제목 규칙은 v2.7 뒤), ClaudeCallAnalyzer.SYSTEM 동일 갱신, PROMPT_VERSION=6, tidy.md 6·7번, tidyState rev+mergeCandidates(설정 MERGE_CANDIDATES="mergeCandidates"), applyTidy rev 비교. RoutineDocTest·TidyTest 추가. 루틴 교체 전문: hourly "이음 통화 분석 (매시간). 지시문 버전 6."부터, tidy "이음 새벽 정리 (매일)."부터, 둘 다 "... 커밋 메시지에는 건수 외에 내용을 적지 않는다."로 끝.

## 통합 상태
- 03:3x 합침: r1 → r5 → r3 (충돌 없음). 로컬 컴파일 BUILD SUCCESSFUL(int-compile-1). 전체 단위 시험 int-test-1 실행 중.
- GitHub Actions: 계정 결제 문제로 02:00 이후 모든 run 실패("recent account payments have failed"). 사용자에게 알림.
- 남은 묶음: r7-2, r7-4, r7-6.

# v2.7 통합 기록 (feature/r8-v27)
- r8-3 (167b460, 대기): CallUi.once/onTap/busy/idle/work/enableAll/loading/retryCard/hideSnack/tabLook/firstField/PAGE_CAPTION/ONCE_MS, button·bigButton busyText 오버로드, sheetDialog(validate)+키보드, signalRow(onPress), icon(filled); TaskActions 즉시 반응+inFlight+실패 복원+SINGLE_TOP; StatusLights.Light.target(5필드, GROUP_CONNECT/SUMMARY/STATUS, tag "group_"+이름); ScreenParts.searchBox(Context); ids.xml ui_last_tap/ui_busy_text; 밝은 MUTED=#6B7684. 전체 시험 411 통과. 2단계가 할 것: 화면이 loading/retryCard/busyText/validate 쓰기, 신호등 줄→묶음 스크롤, 화면 안 회색 캡션 PAGE_CAPTION. 시험 주의: 600ms 두 번 누름 무시 → idleFor(700ms).
- r8-2 (440b9be, 대기): MainActivity(Screen enum, AppScreen 기반 클래스, openCall/openCompany/switchTo, intent(c,screen,dayMs,callId), EXTRA_SCREEN/DAY_MS/CALL_ID, putBack, once), screens/*Screen.kt(git mv, this→a, read/load 본문 유지, `if(!live())return@launch`), CallUi.page(a,tab,onTab)·markTab·scrollTo(go/Tab.target/leave 삭제), 매니페스트 MainActivity singleTop portrait, CallDigestNotifier.post(..., dayMs)·screenFor, CallUpdate 알림 대상, TaskActions.openSource→openCall, androidTest 스크린샷·update_smoke.sh. 테스트 MainLaunch.launchMain. 전체 405 통과. 2단계 진입: a.switchTo/openCall/openCompany, a.home/calls/tasks/companies/settings.
- 1단계 통합 완료(07:2x): r8-2 → r8-3(CallUi/TaskActions 충돌 해결, 시험 launchMain으로) → r8-1(screens/*.kt에 적용, StorePerformanceTest는 MainActivity.home) → 하트비트 dbOpenMs/homeReadMs 추가. 전체 420 통과·9 건너뜀(int27-test-2). 푸시됨. 2단계 r8-4/r8-5/r8-6 시작.
- r8-6 합침(ad8d98f) + TaskActions 프로세스 저장소(뒤처리). 관련 시험 통과(int27-test-3). r8-4·r8-5 대기.
- v2.7 완성(964fd1b, feature/r8-v27 푸시): r8-5 합침, 뒤처리(홈 체크 행 CheckRows.row, 날짜 넘기기 시험 readHook 게이트, 설치 성공 뒤 오류 지움 시험, 폴더 저장 IO, TaskActions 프로세스 저장소). 전체 441 통과·9 건너뜀(int27-test-7). VERSION 2.7, UPDATE_NOTE 추가. GitHub Actions는 여전히 결제 차단(0 steps).
- v2.8 시작(07:5x): feature/r9-v28 = 964fd1b + PhoneKey(f7d091b). r9-1/r9-2/r9-3 Opus 작업자 띄움. 지시문 /home/claude/briefs/r9-*.md. v2.9·v3.0 지시문(r10-*, r11-*)도 초안 작성(ContactCards API 이름은 r9-2 보고 뒤 확인).
- 남은 뒤처리: HANDOFF 71~73·86행의 개인 경로(scan_export home_path) → HANDOFF 갱신 때 %USERPROFILE%로 바꿀 것.
