# feature/r10-v29-r2 통화별 알림 (6265c2a, 기준 e368728) — 작업자 보고 요약
- 신설 `pipeline/CallFollowupNotice.kt`: `onCallEnded(context,end)`(백그라운드, answered=false면 없음, 업무 상대=상대 카드 또는 전화부 이름(CACHED_NAME → call_log.name, READ_CONTACTS 없이)), `onResult(context,recordId,db?)`, `sweep(context,now)`(6시간 넘으면 "정리가 늦어지고 있어요, 저녁 보고에 넣어 드릴게요", 놓친 결과도 "정리됐어요"로), `afterEvening(context,now)`(보고 전 끝난 통화 알림 삭제), `handleMemo(context,intent)`, `idFor(recordId)`.
- 채널 `ieum.calls.followup` "이음 통화 정리"(LOW, 소리·진동 없음, 잠금 화면 "통화 정리 중"), 태그 `ieum.calls.followup`, 액션 `kr.callbrief.action.CALL_MEMO`(RemoteInput `memo`, extra `recordId`·`followupId`, FLAG_MUTABLE) — 기존 `NotificationActionReceiver`.
- 설정 키 `followupNotice`(기본 켬, 설정 "알림 시각" 아래 스위치 "통화마다 알림" `followup_switch`), `callFollowups`(상태 JSON, 번호는 SHA-256만, 최대 40개·36시간), `followupEveningSentMs`.
- 저장소: `addUserItem(title,company,recordId=null)`(통화에 잇되 사용자 것), `bindUserItems(items,recordId)`.
- 호출: `CallRecordWork.runInbox` applyAnalysis 다음 `onResult`, `CallAnalysisWork` 틱 끝 `sweep`, `CallDigestWork` 저녁 보고 뒤 `afterEvening`. 통합 때 `CallSession` IDLE 끝 `onCallEnded` 주석 풂, `CallSessionStub.kt` 삭제.
- 시험: `CallFollowupNoticeTest` 6건 + 관련 60건 통과. 에뮬레이터·기기 RemoteInput 확인 못 함.
