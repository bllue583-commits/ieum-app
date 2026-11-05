# r12-1 보고서 (녹음 공백 판정과 약속 문구) — feature/r12-v31-r1 e057cf8

- 시그니처 변경 없음(RecordingGap.last/banner/lightText).
- RecordingGap.kt: judge(Facts,now,previous) 2.1-1 규칙 전부(창 [시작−2분, 끝+5분], 순서 folder → tooMany → n<3 ok → noRecording → partial → noMatch → ok). afterPass(context,code,startedMs)가 발견마다 판정해 RECORDING_GAP JSON에 저장, 통화별 "녹음 없음"도. 두 번 연속(6시간) 확정 `confirmed`, 48시간 넘은 판정 무시. 자가 진단 1일째·2일째·7일마다.
- StatusLights.kt: Input.gap, 대기 나이는 CallTranscriptionStore.oldestQueuedMs()(null이면 기존).
- SelfCheckNotices.kt: recording_gap, update_stuck("새 버전이 하루 넘게 설치되지 않았어요", CallUpdate.notInstalledForADay).
- CallFollowupNotice.kt: NO_RECORDING("norec") "이 통화는 녹음이 없어 정리할 수 없어요" + [메모 남기기], FOLLOWUP_NO_RECORDING. noRecording 판정 중엔 통화별 알림 없음. "저녁 보고에 넣어 드릴게요"는 기록 있는 통화만.
- CallDigestWork.kt: 저녁 통화 수 CallsScreen.callCount, "오늘 통화 n건, 녹음된 통화 0건", 결과 받기 제한 60초 → 3분.
- AutomaticRecordingDiscovery.kt: cycle() finally에 RecordingGap.afterPass 한 줄.
- CallsScreen companion: stateWords(LoggedCall,now,jobStarts): String?, SEARCH_MS(10분)·SEARCHING·NO_RECORDING.
- 담당 밖: SettingsScreen.kt 한 줄(StatusLights.Input에 gap=RecordingGap.confirmed(s,now)).
- 새 설정 키: recordingGapBadSinceMs, recordingGapNoticeFirstDay, recordingGapNoticeLastDay.
- 시험: RecordingGapTest 10건, SelfCheckNoticesTest·CallFollowupNoticeTest 맞춤. r12-1-test-2(10개 클래스) BUILD SUCCESSFUL, check_ui_rules 0. 전체 시험은 안 돌림.
- 설계와 다르게: 신호등 문구에 m=창 안 파일 있는 통화 수, noRecording 판정 중엔 확정 전이어도 통화별 알림 없음, tooMany는 신호등에만, folder 판정은 HELD_SOURCE:SELECTED_FOLDER_READ_GRANT_REQUIRED / DISCOVERY_CHECKED:FOLDER_READ_GRANT_MISSING, noRecording·partial은 같은 계열로 이어 셈.
- 남은 것(r12-5): 홈 배너 붙이기(누르면 설정 status_import), 통화 목록에 새 stateWords 쓰기.
