# feature/r11-v30-r2 이음 카드 화면 (6ebe4ad, 기준 0ca80a6) — 작업자 보고 요약
- 신설 `pipeline/call/CallCardOverlay.kt`(object: `show(context,phoneKey,state)`(IDLE이면 hide), `collapse()`, `expand()`, `hide()` — 어느 스레드든, `buildNotice`, `outcome:(Context,String)->Unit`(통화마다 1번: shown/noPermission/noNumber/noCard, 개인 번호는 없음), 채널 `ieum.calls.card` "이음 통화 카드"(HIGH, 소리·진동 없음, 잠금 화면 "잠금을 풀면 카드가 보여요"), 알림 tag `callcard` id 3011), `CallCardView.kt`(코드로 그린 카드, `Content`).
- 동작: TYPE_APPLICATION_OVERLAY + FLAG_SECURE, 세로 0.38 기본(`cardPosY`, 끌면 저장), 2단계 그리기(이름 → 16ms 뒤 나머지), 받는 전화 3초·거는 전화 15초 뒤 띠, 띠 누르면 펼침(손으로 펼치면 저절로 안 접힘), [닫기]는 그 통화 동안만, 권한 없거나 잠금이면 헤드업 3줄 → USER_PRESENT면 카드로, 모르는 번호는 `cardUnknown` 켤 때만 "전화부에 없는 번호예요"+최근 문자 한 줄, 개인 번호는 아무것도 없음, 번호·자막 안 씀.
- 높이 40% 넘으면 빼는 순서: 진행 중인 일 → 3번째 체크 → 2번째 → 마지막 통화 줄. "지난 통화"·"진행 중인 일"은 내용 앞 16sp 회색, "체크할 일 n개".
- 태그 `card_root`, `card_handle`, `card_name`, `card_company`, `card_last`, `card_summary`, `card_tasks_section`, `card_tasks_label`, `card_task_0~2`, `card_issue`, `card_collapse`, `card_close`, `card_strip`, `card_strip_text`.
- 시험 `CallCardOverlayTest` 12건, 전체 `r11-2-full-1` 504건 통과, ui rules 0, scan 0. 카드 PNG(펼침·접힘·어두운 모드) 오케스트레이터가 직접 봄: 잘림·겹침 없음.
- 통합 때: Stub 삭제, `KEY_UNKNOWN/KEY_POS_Y` → `PipelineSettingsKeys.CARD_UNKNOWN/CARD_POS_Y`.
