# r12-4 보고서 (마이그레이션 안전과 통화기록 대조 진단) — feature/r12-v31-r4 9d85e4e, e4b25d2

- **v2.4 → 현재 스키마 직행 마이그레이션 시험 통과(1.4절 0번).** 고치기 전 코드(=157ffab 저장소 코드)에서 r12-4-v24-1 BUILD SUCCESSFUL 2/2. 1033 저장소가 1038에서 열리고 행 손실 0 — 손 설치를 막을 이유 없음. 픽스처는 9668e98 소스(25MB CHECK, 마이그레이션 2), 번호 두 표기 사람, 10/1 전 QUEUED, 시작 시각 없는 통화, 받은 표시·체크·통화기록·개인 번호. 16MB 경계 1건.
- 파일: test/resources/store-v24-schema.sql, V24MigrationTest.kt, StoreSafetyTest.kt(8건) 신설. CallRecordStore.kt(openStage open/schema/migrate/repair/seal/done, 합치기는 같은 번호+같은/빈 이름만, 애매한 쌍은 person_merge_candidates → personCandidates()와 tidyState `personCandidates`, mergeLog에 원래 행(person_aliases 포함) 최근 100개). FirstPass.kt(MatchStats.missGapMin·byEnd, missBin, MISS_FAR, 끝 시각 후보는 원래 창 후보 0이고 번호·이름 일치일 때만, 점수 +500). IeumApplication.kt(STORE_OPEN_ERROR "예외:단계[:보호 코드]", 같은 값마다 알림 1회 "저장된 통화를 열지 못했어요. 앱을 지우지 말고 알려 주세요", 다시 열리면 지움). ProtectedDatabase.takeRecovery(name). CallRecordStoreTest 옛 합치기 기대 고침.
- 로컬: r12-4-test-3 13개 클래스 148건 BUILD SUCCESSFUL, check_ui_rules 0, scan_export 0. 전체 시험은 안 돌림.
- 남은 것: tidy.md 7번에 personCandidates 처리 한 줄(통합 때 오케스트레이터). 되돌리기 화면은 v3.2.
