# 개발·통합 경계

이 이관은 code34 v2 소스 보존과 Git 개발 준비입니다. 신규 앱 기능 구현·의존성 업그레이드·리팩터링은 하지 않았습니다. 후속 기능 작업은 기준 commit을 고정하고 독립 branch/worktree에서 진행합니다. 현재 제품 소스는 2026-10-15 작업의 code34 v2이며 Windows 인계에는 새 제품 구현이 없습니다.

## 공통 계약

- `kr.callbrief`, versionCode/versionName, 서명 identity·원 자료 식별자를 임의로 변경하지 않습니다. 기존 서명키는 Git 외부에 있습니다.
- frozen v0/RO1/SG, 외부 6필드 wire와 shared SQL은 담당자 간 계약 검토 후에만 변경합니다. 의미 parser에 자동 확정 권한을 추가하지 않습니다.
- owner/source/version/hash/lease/revision/receipt 검사, 원음 제외/철회, 숨김·보관, 수동 DONE·메모·잠금·충돌 보호를 유지합니다.
- 개인정보 원본·인증·서명키·실사용 DB·비공개 URL·NAS를 변경하거나 커밋하지 않습니다. 새 OAuth/지속 권한/과금·실제 전송은 사용자 승인과 해당 서비스의 절차를 따릅니다.
- `worker/`는 기존 소스 폴더 이름입니다. 이관은 새 worker/서버/정기 자동화를 만들지 않았습니다.
- build/model/JNI/APK/cache/AVD는 각 checkout의 ignore된 생성물입니다. 공유 원본 경로를 산출물 디렉터리로 사용하지 않습니다.

## 큰 기능별 협업

기존 개발과 신규 설계 담당은 부모가 별도로 지정합니다. Git 기준 M1~M6의 코드 근거·미구현·완료정의와 현재 담당 branch/공유 파일 경계는 [큰 기능 이정표와 Git 통합 계약](MILESTONES.md)을 따릅니다. 해당 문서는 기준 commit의 현황이며 병렬 branch 착수를 구현 완료로 세지 않습니다.

| 작업 | 먼저 확인할 기존 위치 | 독립성/충돌 경계 |
| --- | --- | --- |
| 안전 접수·실제 자동 음성 왕복 | AutomaticRecordingDiscovery, NativeDiscoverySession, AutomationCoordinator, provider/worker | source/job 접수와 분석 후 업무 binding 분리; 모르는 case/task/시각을 합성하지 않음 |
| 문맥·업무/사람·결과 소비 | LocalRecordingProducer, AndroidAutomationStore, RecordingWorkflowStore, 기존 dot 계약 | 같은 raw source를 보존; 의미 검토와 자동 canonical 적용 분리; Store.accept 우회 금지 |
| SMS·네이버·23시/증분/06시 보고 | AndroidSmsReadOnlySource, naver-mail 모듈, DailyReportSchedule/Runtime/Ledger | 기존 스케줄러·장부 재사용; 미처리·부분 보고 표시; 실제 ACK/UNKNOWN 분리 |
| 설계·신규 기능 | docs/plans, 설계 Page sequence54 | 제안·기대값을 구현 완료/시험 PASS로 올리지 않음 |

공유 파일은 한 담당자가 통합합니다. 각 PR은 base SHA, 변경 경로, 입력/관측/실패/재검증과 미실행 범위를 적습니다. 실제 기기·합성 host·계약 validator·운영 수신 결과는 따로 보고합니다. 기존 실패 기록을 지우거나 합격 수를 더해 진척률로 쓰지 않습니다.
