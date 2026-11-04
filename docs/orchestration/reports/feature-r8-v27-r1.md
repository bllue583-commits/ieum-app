# feature/r8-v27-r1 저장소 성능 (98e9ea5) — 작업자 보고 요약
- 홈 read()가 DB를 1번만 엶(전 6~7번), 두 번째부터 0번. IeumApplication.store(context)가 프로세스 수명 CallRecordStore(놓는 곳: shutdown(), onTrimMemory(COMPLETE)). onCreate에서 백그라운드로 한 번 열어 스키마·마이그레이션.
- Keystore 확인은 처음 열 때 한 번(State.verified), persist는 매번 확인. 변경 없는 바깥 트랜잭션은 스냅샷 생략(total_changes/schema_version).
- 스키마·보정 단계는 상태마다 한 번(needs/markDone, AndroidSqlPort.needs/markDone/usable). 읽기 전용 생성자는 두지 않음(설계와 다름, 불필요).
- oldestUnansweredSentMs() 한 쿼리. 설정 refresh 1번 열기. 통화 화면 reconcileCallLog 제거 → CallLogDiscoveryWorker(AutomaticRecordingDiscovery.kt 3줄)에서 대조.
- 측정 설정 키 dbOpenMs, homeReadMs(하트비트에는 아직 안 올림 — 통합 때 PhoneStatus에 추가할 것). 로그 state ms=/transaction ms=(100ms 이상).
- 계측: ProtectedDatabase.openCount/restoreCount/keyCheckCount/lastStateMs/lastTransactionMs.
- 테스트: StorePerformanceTest 5개, ProtectedTextStorageTest.T05 수정(메모리 사본 읽기 통과), TextStoreTestSupport.reset()·NativeAutomaticDiscoveryTest.fresh()가 IeumApplication.shutdown() 호출. 전체 406 통과·9 건너뜀.
- 주의: 시험에서 저장소 파일 지우기 전 IeumApplication.shutdown() 필요. WorkManager 작업의 use{}는 그대로(프로세스 저장소가 refs 잡음).
- 합치기 주의: HomeActivity/TodayCalls/CallSettings/CallTasks/Companies 변경은 r8-2가 screens/*.kt로 옮긴 파일에 적용해야 함.
