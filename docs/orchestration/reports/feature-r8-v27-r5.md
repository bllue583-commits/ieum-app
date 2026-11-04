# feature/r8-v27-r5 체크·업체·설정 (640a453) — 작업자 보고 요약
- TasksScreen: CheckRows.row(G3: 업체 배지 → 제목 → META "김민수 님, 오늘 요청" → AI 메모 → [다 됐어요] 행 끝 오른쪽). 통화 항목(OpenItem.recordId)만 행·META 이름(task_who_link)이 a.openCall. 직접 적은 일·새벽 팔로업 눌림 없음. 30개 먼저(FIRST_ROWS) + 30개씩 post. loading/retryCard, 직접 적기 시트 validate, IeumApplication.store.
- CompaniesScreen: 상세 머리 "업체 이름 고치기"(company_rename, nameSheet+validate, 입력 company_name_input), 옛 이름 company_aliases, 다른 업체 이름이면 합침. 뒤로 가면 들고 있던 목록 즉시 그린 뒤 재읽기. 체크 행은 CheckRows.row(배지 없이).
- SettingsScreen: render 스크롤 저장·a.putBack 복원, mailUser/mailPassword/mailHost 필드 유지(비밀번호는 Bundle 저장 안 함), 서비스 버튼(mail_service_<i>) 제자리 갱신, 스위치는 글자만+신호등 갱신, 슬라이더(hour_bar_<key>) 700ms 디바운스(떠나면 즉시 저장), 전역 busy 제거 → 동작별 running+CallUi.busy/idle, 신호등 줄 Light.target으로 goTo(56dp 위), 처리한 통화 실패 수 EMPTY 제외(handled_calls/handled_empty), loading/retryCard.
- CallRecordStore: OpenItem.recordingId/recordId/callMs/fromCall(기본값), openItems()·companyDetail() call_records LEFT JOIN 한 번, renameCompany(id,name):String?(company_aliases, board_state.revision+1). StatusLights 변경 없음(이미 EMPTY 제외).
- 테스트 CheckCompanySettingsTest 7개. 관련 시험 통과, ui rules 0. 알려진 경쟁: UiV2Test.aFailedInstallIsKeptForTheSettingsLight(UpdateStatusReceiver later{} 스레드 vs 시험 확인 순서) — 담당 밖, 통합 때 고칠 것.
- 제안: 홈 체크 행에 CheckRows.row 또는 onOpen=fromCall→openCall(recordId,callMs)로 "통화를 찾지 못했어요" 토스트 제거. 업체 이름 고치기는 ledger_edits에 안 올림(renameCompany edit 추가 제안).
- 새 태그: task_who_link, task_who_name, task_badges, company_rename, company_name_input, mail_service_<i>, hour_bar_<key>, handled_calls, handled_empty, tasks_list_<묶음>.
