# feature/r8-v27-r6 알림 — 작업자 보고 요약
- NotificationActions.kt 신설: NotificationActions(액션 Intent, handle, refresh) + NotificationActionReceiver(goAsync, 단일 스레드). 액션 `kr.callbrief.action.TASK_DONE`, extra itemId(String)·revision(Long). 매니페스트 exported=false.
- 아침 알림 본문 첫 항목에 [다 됐어요] 한 개(설계의 상위 2개 대신: 버튼 둘이면 구분 불가). 누르면 completeIo → 같은 자리 다음 항목으로 조용히 갱신(setOnlyAlertOnce, LAST_DIGEST_MS 유지) → 마지막이면 "체크할 일을 다 끝냈어요"(allDone, 버튼 없음). 두 번 누름은 id:revision 집합+단일 스레드+revision 확인. 실패하면 키 지워 재시도 가능.
- CallDigestNotifier.post(context,kind,m,dayMs=null,callId=null,refresh=false); callId면 통화 상세. 상수 ID=700, tag(kind)="ieum.calls.<kind>". 버튼 request code 701 xor itemId.hashCode(), 내용 PendingIntent (kind+callId).hashCode().
- CallDigestText.Message에 doneId/doneRevision(기본값), CallDigestText.allDone(issues).
- CallDigestWork doWork·selfCheckInput이 IeumApplication.store 사용.
- 테스트 NotificationActionsTest 6개. r8-6-test-2 56개 통과, compile OK, ui rules 0.
- 제안: TaskActions.completeIo/undoIo/openSource 기본값이 아직 CallRecordStore(...).use{} → IeumApplication.store로(통합 때 처리). "이음 메모" 표기는 안 바꿈.
- 통화별 알림 보내는 곳은 v2.9 r10-2에서(post(...,callId=) 경로만 준비).
