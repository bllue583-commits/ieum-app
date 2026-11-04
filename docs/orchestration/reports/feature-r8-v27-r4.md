# feature/r8-v27-r4 홈과 통화 (e88417f) — 작업자 보고 요약
- 홈 라벨 "오늘 통화 / 오늘 생긴 체크 / 남은 체크할 일"(오늘 통화=CallsScreen.callCount, 통화 탭과 같은 기준). "먼저 볼 체크할 일": firstLook(오늘 요청+확인 요청, 확인 먼저), FIRST_TASKS=5, "외 n개 보기 ›". 메모 없으면 sortingWords.
- 통화 직후 3단계: CallsScreen.transcribing(자막 job ±2분 또는 12시간 안+폴더)·stateWords(TRANSCRIBED "정리하고 있어요", createdMs 6시간 넘으면 "정리가 늦어지고 있어요", EMPTY_TRANSCRIPT "녹음에서 말소리를 찾지 못했어요")·canRetry(EMPTY 제외). visibleLogs: ±2분 겹치는 "녹음 없음" 줄 숨김. 태그 log_transcribing_<id>, call_state_text, call_retry.
- 날짜 넘기기 즉시 교체+loading(calls_prev/calls_next). 검색 Searchable 풀 + 250ms 디바운스, 상세에서 돌아와도 검색어·스크롤 유지. 읽기 loading/retryCard.
- [모델 받기] ModelWish.mark, Wi-Fi 대기 문구(약 570MB), 1초 퍼센트. 폴더 저장·updateNow IO("확인하는 중이에요"). 알림: NotifyAsk(allowed=권한+areNotificationsEnabled, blocked=2회 거절 또는 스위치 꺼짐 → ACTION_APP_NOTIFICATION_SETTINGS).
- HomeScreen이 자기 launcher(폴더·알림) 등록(MainActivity 안 고침). MainActivity.pickFolder(설정 화면 폴더 저장)는 아직 메인 스레드 → 통합 때 IO로 옮기면 리뷰 #21 닫힘.
- CallRecordStore.transcribingStarts(fromMs,toMs) 추가. 새 UI prefs `ieum_ui`: notify_refusals(int), model_wanted(bool). HomeScreen.Snapshot에 modelWanted/notifyBlocked/modelPart, calls 뜻 변경. updateNow 비동기.
- 테스트 ScreensHomeCallsTest 8개 + 기존 6개 맞춤(CallLogListTest는 CallsScreen.clock 고정). 전체 r8-4-test-3 통과, ui rules 0.
- 개인 통화·차단 번호 글색 MUTED→SUBTLE.
