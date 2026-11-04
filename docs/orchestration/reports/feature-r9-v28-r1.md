# feature/r9-v28-r1 저장소 기반 (65e105d, WIP 종료 시점) — 작업자 보고 요약
- PhoneKey.of를 모든 호출처에 적용(counterpartKey, 문자, 통화기록, CallLogReport, 녹음 파일 이름, 제외 규칙). PrivacyRules.normalizePhone 삭제. PhoneKey는 +없는 82 뒤 8자리(서울 7자리)도 허용(PhoneKeyTest 추가). 외국 번호는 counterpartKey에서만 숫자 그대로, 통화기록·문자에서는 키 없음. 문자는 1588류 대표번호를 안 읽음.
- MIGRATION_VERSION=4: 저장된 번호 키 재계산, 같은 번호의 두 표기 사람 합치기(별칭·연결·번호·장부 id 따라감; 이름 다르면 공유 번호 두 사람으로 유지). 새 표·인덱스는 파일 끝 STORE_V4_SCHEMA. 인덱스 7개, issue_companies(applyLedger에서 채움, 회사 합칠 때 따라감).
- CallRecordStore.MAIN_THREAD_CHECK(Robolectric에서는 꺼짐; 실제 앱에서는 메인 스레드 접근 시 예외 → 화면 코드는 IO에서만). inTransaction{}로 runInbox 한 기록 처리·편집 삭제·받음 표시·묶음 보냄 표시 각각 한 번에 저장(업로드 뒤 markSent는 따로).
- 보호 파일 복구 규칙(.new/.bak) → ProtectedDatabase.takeRecovery() → 설정 dbRecoveryLast. WorkManager use{} → IeumApplication.store. 담당 밖 소폭: IeumApplication(복구 결과 기록), CallSinkImpl·PhoneStatus(use{} 교체), CallRecordAnalysisRunner(SYSTEM_CLOCK이면 프로세스 저장소).
- 테스트: StoreFoundationTest 신설, ProtectedStoreAuditRegressionTest P04~P06을 새 복구 규칙으로. r9-1-compile-1 OK, r9-1-test-2(두 클래스) OK. **전체 testDebugUnitTest는 미실행(종료 지시로 중단)** — 통합 때 돌릴 것. 관련 4개 클래스(고친 뒤 재실행 안 함)도 확인 필요.
