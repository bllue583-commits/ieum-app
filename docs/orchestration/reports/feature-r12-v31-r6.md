# r12-6 보고서 (v3.2 홈은 요약, 맨 위에 오늘 이야기) — feature/r12-v31-r6 e819f69

- 기준 DESIGN_V4 2-1절. 시그니처 변경 없음(데이터 클래스에 기본값 필드만: HomeScreen.Snapshot.story, FirstPassView.recordings, CallRecordStore.FirstPassProgress.recordings).
- HomeScreen: 순서 = (할 일 있을 때만) "설정에서 확인할 일이 있어요 ›" → 오늘 통화 이야기 → 먼저 볼 체크할 일 5 → 오늘 통화 메모 → 숫자 셋("오늘 한눈에") → 업체별 → 이번 주. 처음 설정·처음 정리·모델·업데이트·녹음 공백 카드는 홈에서 삭제. ATTENTION_WORDS, Attention(group,tag), attention(Snapshot), requiredLeft, updateNeedsPerson, readFirstPass, firstPassWords("녹음 n개 중 m개 남음"). 1초 ticker는 단계가 바뀔 때만 다시 읽음.
- SettingsScreen: 맨 위 "지금 할 일과 진행 상황"(GROUP_NOW, SETUP_TAG) — 녹음 공백 한 줄(now_gap → status_import), 처음 설정(r12-5 동작 그대로), 지난 통화 정리(녹음 기준), 모델 퍼센트·막대, 할 일 없으면 "지금 할 일이 없어요. 모두 잘 되고 있어요 ✓".
- TodayStory.kt(신설): parse, shown, fallback, summarized, clean. CallRecordWork.takeStory: 결과 받기 때 story/today.json GET(404·깨짐 조용히, 바뀔 때만 저장, ETag). CallInbox.STORY, PipelineSettingsKeys.STORY="story".
- VERSION 3.2, UPDATE_NOTE v3.2.
- 시험: FirstSetupHomeTest 새로 짬, CallInboxTest story 받기·404, ScreensHomeCallsTest·UiV2Test·UiRoundThreeTest·CallCardSettingsTest·CallUiSeniorTest·CallCardSafetyTest·UpdateFetchTest·HomeRestShotsTest 맞춤. 로컬 r12-6-ci-1(:core:test :app:testDebugUnitTest :app:lintDebug) BUILD SUCCESSFUL 589건 실패 0, check_ui_rules 0, scan_export 0.
- 2-1절과 다르게: 본문 maxLines=4(말줄임 없음, 200자 규칙에 기댐), 0건이면 메모 묶음 생략, 숫자 셋에 "오늘 한눈에" 제목, 처음 설정은 늘 펼침, 대체 글 상대 이름은 장부 이름 우선.
