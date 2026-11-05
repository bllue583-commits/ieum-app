# 이음 v2.6 → v3.0 설계도 (2026-11-05)

> **보관 문서(2026-11-06 옮김).** v2.6~v3.0의 버전별 설계와 구현 대조 기록이다. 현재 설계(구현된 그대로)는 `docs/DESIGN.md`가 기준이며, 이 문서와 다르면 DESIGN.md를 따른다.

> 기준: 저장소 `feature/r6-v25`(v2.5, 커밋 c6d6c21)와 설계도(Claude Docs "이음 설계도 개정 3"), 실제 폰 상태(우편함 `status/phone.json`, 11/4 23:43). 검증 보고서 원문은 `docs/reviews/20261105_*.md` 세 편(화면, 파이프라인, 데이터). 이 문서가 설계도의 다른 절과 다르면 이 문서가 우선하고, 설계도(Claude Docs)에는 요약 절과 이 파일의 링크를 둔다.
>
> 사용자 지시(11/5): v2.5가 끝나면 v3.0까지 Opus 작업자를 많이 써서 빠르게 개발. 버전마다 메이저한 변화 하나, 버전마다 배포와 HANDOFF 갱신, 기기 시험은 2~3개 버전마다. v3.0의 핵심은 전화가 올 때와 걸 때 통화 앱 위에 이전 진행 상황을 띄우는 것(모모콜 방식). 버튼 무반응 같은 사소한 버그도 잡는다. 기준은 실제 앱의 활용성.

## 0. 한눈에 보기

| 버전 | 이름 | 사용자에게 보이는 큰 변화 | 기술 핵심 | 시험 게이트 | 배포 |
| --- | --- | --- | --- | --- | --- |
| v2.6 | 제대로 정리되는 이음 | 오늘 통화가 오늘 저녁 보고에 들어가고, 10월부터의 처음 정리가 실제로 돌아 끝나고, 새 버전이 저절로 깔린다. 같은 사람이 둘로 안 갈라진다 | 처음 정리 관문(b- 묶음) 풀기, 발견 경량화, 무인 설치, 통화기록 대조와 이름 정규화, 지시문 v6, 감시 정확화 | ci만 | lean-calls |
| v2.7 | 매끄러운 화면 | 누르면 바로 반응한다. 탭을 다시 누르면 목록으로, 알림에서 열어도 뒤로가 꼬이지 않고, 설정 입력이 안 사라지고, 통화 직후 통화가 "정리 중"으로 보인다. 아침 알림에서 바로 [다 됐어요] | 저장소를 프로세스 수명 동안 한 번만 열기, Activity 하나(G1), 즉시 반응 부품, 화면별 31건 수정, 알림 동작 버튼 | **ci + device-test(스크린샷 비교)** | lean-calls |
| v2.8 | 상대 카드와 사람 화면 | 사람을 누르면 그 사람과의 통화·문자·체크할 일·진행 중인 일이 한 화면에, 바로 전화와 문자 | 번호 정규화 한 곳, 번호별 사전 계산 "상대 카드" 저장소(0.1초 조회), 인덱스, 보호 파일 복구, 사람 화면 | ci만 | lean-calls |
| v2.9 | 통화 직후 정리 | 통화가 끝나면 바로 "정리하고 있어요 → 정리됐어요: 한 줄 요약" 알림, 알림에서 메모 남기기. 결과까지 평균 25분 | 전화 상태 감지(READ_PHONE_STATE), 통화 뒤 즉시 발견·자막·업로드, 결과 10분 체인, 루틴 30분 주기 | ci만 (에뮬레이터 통화 시뮬레이션은 돌리되 기다리지 않음) | lean-calls |
| v3.0 | 통화 중 이음 카드 | 전화가 올 때와 걸 때 통화 화면 위에 상대와 지난 통화, 체크할 일이 바로 보인다 | 오버레이 카드(다른 앱 위에 표시), 발신 번호(통화 연결 역할), 통화 중 포그라운드 서비스, 잠금 화면 알림 폴백 | **ci + device-test(통화 시뮬레이션 포함)** + 실기기 확인 | lean-calls |

순서의 이유: v2.6이 없으면 뒤 버전이 보여 줄 데이터가 없다(지금 장부 0건). v2.7의 "저장소 한 번 열기"와 "Activity 하나"는 v2.8 사람 화면과 v3.0 카드의 바탕이다. v2.8의 상대 카드 저장소와 v2.9의 전화 상태 감지는 v3.0이 그대로 쓴다. 기기 시험을 기다리는 두 번(v2.7, v3.0)은 화면이 크게 바뀌는 때와 전화 기능이 들어가는 때다.

## 1. 현재 앱(v2.5) 검증 결과

세 작업자(Opus)가 코드 1만 7천 줄을 읽기 전용으로 검증했고, 실제 폰의 하트비트와 우편함 기록 수치를 대조했다(통화 내용은 보지 않음). 결론부터:

**실제 폰에서 막혀 있는 것 (11/4 23:43 기준, v2.4 빌드 1033)**

| 수치 | 뜻 |
| --- | --- |
| 10/1 이후 통화 979건, 닷새(10/30~11/4) 동안 분석된 통화 약 27건 | 앱이 사실상 일을 못 하고 있음 |
| 자막 대기 녹음 139건(225분), 처음 정리 미완료, 처음 정리 묶음(b-) 0건 | 처음 정리가 시작조차 안 됨 |
| 대기열의 가장 오래된 녹음 2025-08-06 | 10/1 이전 제외가 실제로 안 먹음. 이 행 하나가 묶음 관문의 기준이 되어 관문이 영구히 안 열림 |
| 저장소 장부 rev 3, 업체 0, 사람 0, 진행 중인 일 0 | 맥락이 전혀 안 쌓임(옛 빌드가 상대 이름 없는 요청을 보냈기 때문) |
| 통화기록과 이어진 녹음 0건(녹음 없는 통화로 센 것 678건) | 통화기록 대조가 한 번도 성공 안 함 → 번호, 수신·발신, 날짜 기준이 요청에서 빠짐 |
| 첫 하트비트가 11/4 23:43 | 아내 폰이 그날 밤까지 v2.0 이전 빌드에 머묾. 업데이트가 사람이 눌러야만 설치되기 때문 |
| 마지막 발견 성공 시각 0 | 발견 사이클이 끝까지 돈 적이 없고, 실패는 어디에도 기록되지 않음 |

**근본 원인 셋**

1. **보호 DB 구조.** 파일 하나를 통째로 암호화하고, 열 때마다 전체를 복호화해 메모리 SQLite로 복원하며, 트랜잭션마다 전체를 직렬화·암호화·fsync한다. 쿼리마다 Keystore를 확인하고 읽기·쓰기·백그라운드 작업이 잠금 하나를 쓴다. 홈 화면 하나가 DB를 6~7번 연다. "버튼이 반응 안 한다"의 첫째 원인이고, 자막 후보 정리 루프가 10분 한도를 넘기게 만든 원인이며, 통화 화면 위 0.1초 조회를 불가능하게 하는 원인이다. 16MB·10만 행을 넘으면 모든 쓰기가 조용히 실패하는데 용량 경고는 없는 파일을 재고 있어 절대 울리지 않는다.
2. **처음 정리 관문.** 업데이트 전 기록은 전부 "처음 정리" 묶음으로만 올라가는데, 묶음은 (가) 대기열의 가장 오래된 녹음(2025년) 기준 시각 (나) 15건 꽉 참 (다) 문자 되읽기 완료 조건이 겹쳐 한 번도 나가지 않았다. 발견은 폴더 목록 2000개 한도와 문서별 고비용 때문에 큰 폴더에서 새 통화를 놓칠 수 있다.
3. **사람이 눌러야 하는 것들.** 업데이트 설치, 알림 권한(두 번 거절 뒤 무반응), 실패 통화 다시 정리(소용없음), 설정 저장 뒤 스크롤·입력 유실. "버튼 없는 자동화"가 설치·권한 단계에서 끊긴다.

**영역별 핵심 문제(상세는 검증 보고서)**

| 영역 | 높음 이상 | 대표 문제 |
| --- | --- | --- |
| 화면(31건) | 10건 | 현재 탭 다시 누르면 무반응, 알림에서 열면 뒤로 순환, [다 됐어요] 반응 지연과 두 번 누름 토스트, 탭이 지난 상세를 보여 줌, 통화 직후 "녹음 없음", 설정 스크롤·메일 입력 유실, 저장 공간 % 미표시, 효과 없는 버튼 셋 |
| 파이프라인(21건) | 10건 | 처음 정리 관문, 10/1 이전 대기열, 발견 2000 한도, 무인 설치 없음, DB 전체 직렬화, 발견 실패 무기록, 거짓 초록 신호등, 긴 통화 STALLED, 4xx 오류 무기록 |
| 데이터·분석(22건) | 6건 | DB 열기 비용, .new/.bak 영구 잠금, 통화기록 대조 0건, "김과장"과 "김민수 과장"이 두 사람, 번호 정규화 세 갈래, 장부 사람 추가 규칙 없음 |

설계도와 구현이 다른 것 중 결정이 필요했던 것은 이 문서에서 정했다(편리함 중점). 예: 통화 목록 대조 파일에 이름·번호가 올라가는 것은 사용자 결정(11/4)이므로 설계도 "개인정보" 절에 예외로 적는다. 통화 심사 서비스(CallScreeningService)는 전화부에 있는 상대를 넘기지 않아 쓰지 않는다(7절).
## 2. 진행 방식 (릴리스 트레인)

앱 개발사의 릴리스 방식을 따른다. 버전 하나가 열차 한 대이고, 열차마다 "큰 변화 하나 + 그 버전에 묶인 수정"을 싣는다. 사용자(11/5): 버전마다 메이저한 변화 하나, 버전마다 배포와 HANDOFF 갱신, 기기 시험은 2~3개 버전마다, 작업자(Opus)를 많이 써서 빠르게.

| 단계 | 하는 일 | 누가 |
| --- | --- | --- |
| 1. 묶음 개발 | 버전의 묶음(r7-1, r7-2 …)마다 브랜치 `feature/r7-v26-<묶음>`을 기준 브랜치에서 만들어 병렬 개발. 묶음마다 단위 테스트를 함께 쓴다 | Opus 작업자(여럿, 동시) |
| 2. 통합 | `feature/r7-v26`에 묶음을 합치고 충돌을 풀고 `docs/VERSION`, `docs/UPDATE_NOTE.txt` 첫 줄을 올린다 | 오케스트레이터(Fable) |
| 3. ci | 통합 브랜치 push → `ci`(JVM 단위 시험 + lint + 화면 규칙 검사, 약 5분) 통과를 종료 코드로 확인 | 자동 + 오케스트레이터 |
| 4. 기기 시험 게이트 | **v2.7과 v3.0에서만** `device-test` 결과를 기다려 본다(v2.6, v2.8, v2.9는 돌아가더라도 기다리지 않음). v3.0은 에뮬레이터 통화 시뮬레이션을 포함 | 오케스트레이터 |
| 5. 배포 | `feature/lean-calls`에 합쳐 push → `release-apk`(ci 통과 확인 후 빌드·서명·릴리스) → 폰 자동 업데이트. 배포 전 `apksigner` 지문 비교는 워크플로가 함 | 오케스트레이터 |
| 6. 인수인계 | lean-calls에서만 `docs/HANDOFF.md`의 "다음 할 일"과 버전 기록을 갱신해 push. 설계도(Claude Docs)의 해당 항목에 "(완료 v번호)" | 오케스트레이터 |
| 7. 확인 | 다음 하트비트(`status/phone.json`)의 `appVersion`이 올라갔는지, 워치독 이슈가 없는지 | 오케스트레이터 |

완료 정의(DoD): 묶음의 "확인 기준" 전부 통과 · ci 종료 코드 0을 직접 봄(`| tail` 금지) · 새 설정 키·표·권한은 설계도와 HANDOFF에 적힘 · 사용자에게 보이는 글은 한국어, 가운데 점·말줄임표·영어 상태값·빨간색 없음(`tools/check_ui_rules.py`) · 비밀·번호·자막은 코드와 로그에 없음.

작업자 운영 규칙: 작업자는 Opus(중간)로 두고, 한 작업자에게 묶음 하나를 통째로 맡긴다(파일 충돌을 줄이려 묶음은 파일 단위로 겹치지 않게 나눴다). 작업자는 자기 브랜치에 커밋·push까지 하고 ci 결과 링크와 바뀐 파일 목록을 보고한다. 같은 파일을 두 묶음이 고쳐야 하면 순서를 정해 뒤 묶음이 앞 묶음 브랜치에서 시작한다. 기준 브랜치는 직전 버전의 통합 브랜치(v2.6은 `feature/r6-v25`가 lean-calls에 합쳐진 시점의 lean-calls).

기기 시험이 없는 버전의 안전장치: 통합 브랜치의 ci와 함께 `update-smoke`(직전 릴리스 위 덮어 설치, 약 10분)는 release-apk 안에서 매번 돈다. 그래서 "앱이 켜지지 않는" 종류의 사고는 배포 전에 걸린다.

되돌리기: 어떤 버전이 폰에서 문제를 일으키면 lean-calls를 직전 버전 태그로 되돌려 push하면 빌드 번호가 올라가며 이전 내용으로 다시 배포된다(덮어 설치는 버전 코드만 본다). 각 버전 배포 커밋에 `v2.6` 같은 태그를 단다.
## 3. v2.6 「제대로 정리되는 이음」

**큰 변화:** 오늘 통화가 오늘 저녁 보고에 들어가고, 10월부터의 지난 통화 정리(처음 정리)가 실제로 시작되어 끝나며, 새 버전이 손대지 않아도 폰에 깔린다. 지금은 셋 다 안 되고 있다(1절). 화면 변화는 작지만 사용자에게는 "앱이 드디어 일을 한다"로 보이는 가장 큰 변화다.

사용자에게 보이는 변화: 홈 "처음 정리" 카드의 숫자가 매일 줄어들고 끝나면 사라진다 · 저녁 보고에 그날 통화가 전부 들어간다 · 업체·사람 화면이 채워진다 · 같은 사람이 둘로 갈라지지 않는다 · 설정 "지금 상태"의 불이 실제 상태를 말한다 · 업데이트 알림을 누를 필요가 없다.

### 3.1 묶음 r7-1 처음 정리 관문 풀기 (`CallRecordWork.kt`, `CallRecordStore.kt`의 처음 정리 질의, `CallTranscriptionRunner.kt`·`CallTranscriptionStore.kt`, `FirstPass.kt`의 분류)
1. **10/1 이전 행 일괄 정리.** `CallRecordStore`를 열 때 한 문장으로 `UPDATE call_transcription_jobs SET state='SKIPPED', skip_reason='BEFORE_START' WHERE state='QUEUED' AND <이름 시각 또는 시작 시각> < FirstPass.FROM_MS`를 실행한다(열 이름은 현 스키마에 맞춤). `oldestOldJobMs`(`CallRecordStore.kt:897-902`)는 SKIPPED와 10/1 이전 행을 제외한다.
2. **offer 루프를 트랜잭션 하나로.** `CallTranscriptionRunner.kt:137` 부근의 후보 정리 루프를 `port.transaction{}` 하나로 감싸고 `isStopped`를 본다. 보호 DB는 바깥 트랜잭션마다 전체를 직렬화하므로(검증 보고서 파이프라인 #2) 이것만으로 수백 배 빨라진다.
3. **평소와 처음 정리의 분리.** "old/live" 기준을 `FIRST_PASS_START_MS`가 아니라 **기록 시각이 지금부터 7일 이내면 live**(단건 전송, 충전 조건 없음), 그 이전이면 old(묶음, 충전 또는 50%)로 바꾼다(`FirstPass.kt:32,39-42`, `CallRecordWork.kt:109`). 업데이트 시점과 상관없이 오늘 통화는 오늘 보고에 들어간다.
4. **덜 찬 묶음도 보낸다.** `CallRecordWork.kt:124`: bound가 있어도 가장 오래된 대기 old 기록이 6시간 넘게 기다렸으면 15건 미만 묶음을 보낸다. 하루 4묶음 상한과 C7 후퇴는 유지.
5. **오류 기록.** `runInbox`가 모든 HTTP 오류 코드와 예외 클래스 이름을 `LAST_INBOX_ERROR`(코드와 시각)에 남기고 재시도한다(#16, #17). 내용은 남기지 않는다.
6. 하트비트(`PhoneStatus`)에 `firstPass.bound`, `firstPass.blockedBy`("none" / "waiting_messages" / "waiting_transcripts" / "backoff"), `lastInboxError`를 더한다(필드 이름만 r7-6과 맞춤).

확인 기준: 단위 테스트 — 2025-08 녹음 행이 QUEUED여도 bound가 10/1 이후 기록으로 잡힘 · 7일 이내 기록은 live로 단건 전송 · 6시간 기다린 old 10건이 묶음으로 나감 · offer 루프가 트랜잭션 1회(`ProtectedDatabase` persist 카운터) · 4xx가 `LAST_INBOX_ERROR`에 남음.

### 3.2 묶음 r7-2 발견 경량화 (`SelectedRecordingSource.kt`, `NativeDiscoverySession.kt`, `ProtectedDiscoveryQueue.kt`, `AutomaticRecordingDiscovery.kt`, `recording-discovery-android`)
1. `NativeDiscoverySession.Policy.skipped`에 **파일 이름 시각**(`RecordingNames.startedAt`)을 먼저 보고, 없으면 수정 시각으로 10/1 이전을 `EXCLUDED`(#4).
2. SAF 목록(`SelectedRecordingSource.kt:64`)은 커서를 돌며 10/1 이전 문서를 **세지 않고 버린다**. 2000 한도는 남긴 문서에만 적용하고, 그래도 잘리면 설정 `INVENTORY_TRUNCATED`를 켜 하트비트 `inventoryTruncated:true`(#3).
3. 큐 저장은 바뀐 행만 UPSERT, `audit`는 최근 100개만, 문서 판정의 설정 복호화는 페이지당 1회(#7).
4. 발견 사이클은 **시도마다** `LAST_DISCOVERY_ATTEMPT_MS`와 `LAST_DISCOVERY_OUTCOME`(코드)을 쓴다. 트리거 작업은 예외를 삼키지 않고 코드를 남기며 `finally`에서 항상 재등록(#8, #21). 통화기록 트리거 지연을 45초에서 140초로(#18).
5. 25MB 초과 녹음은 하트비트 `heldUnsupported` 수로 보이게 하고 한도를 60MB로 올린다(금고 `AudioBinding.MAX_BYTES` 포함, #13).

확인 기준: Robolectric — 2500개 중 2000개가 10/1 이전인 폴더에서 새 파일 3개가 모두 잡힘 · 이름 시각이 2025년인 파일은 금고에 안 들어감 · 실패 경로마다 outcome 코드가 남음.

### 3.3 묶음 r7-3 무인 설치 (`CallUpdate.kt`, `UpdateStatusReceiver.kt`, 설정 스위치 1개)
1. 다운로드와 SHA-256 검증이 끝나면 백그라운드에서 `PackageInstaller` 세션을 열고 `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`로 commit한다. `STATUS_PENDING_USER_ACTION`이 오면 지금처럼 알림과 홈 배너로 넘긴다(#5). 설치 결과 코드는 `LAST_UPDATE_ERROR`에.
2. 설정 "정리" 묶음에 스위치 "새 버전은 자동으로 설치"(기본 켬). 끄면 알림만.
3. 하트비트에 `updateAvailableCode`, `updateReadySinceMs`. 워치독에 "새 버전이 24시간 넘게 설치되지 않음" 규칙(r7-6과 필드 이름 맞춤).
4. `update-smoke`에 "백그라운드 설치 경로로 설치되는가"를 1회 포함(에뮬레이터에서는 앱 자신이 설치자이므로 통과 기대).

확인 기준: 에뮬레이터에서 v2.5 APK 위에 v2.6을 앱의 백그라운드 설치 경로로 올리면 사용자 확인 없이 설치됨(`update-smoke`) · 실기기(S25)에서 첫 무인 설치가 되는지는 배포 뒤 하트비트의 `versionCode`로 확인(직접 설치한 APK는 첫 한 번은 확인을 물을 수 있음, 그 뒤는 앱이 설치자).

### 3.4 묶음 r7-4 정확도 (`NameKey.kt`, `FirstPass.kt`의 match, `CallRecordStore.kt`의 upsertPerson·isDuplicate·request·applyLedger, `ClaudeCallAnalyzer.kt`, `CallTranscriptionRunner.kt`의 자막 조립)
1. **통화기록 대조.** `FirstPass.match`: 이름이 다르거나 `CACHED_NAME`이 비어 있어도 시각·길이 창 안 후보가 정확히 하나면 잇는다(`key==null` 규칙과 같게). 이름 비교는 `NameKey.canon` 양쪽 비교 + 한쪽이 다른 쪽을 포함하면 일치. 실패 사유 카운터(시각 없음 / 창 안 후보 0 / 길이 불일치 / 이름 불일치(null·다름) / 후보 2개 이상 / 성공)와 이어진 쌍의 시작 차이 히스토그램(초, 10초 구간)을 설정에 쌓아 하트비트 `callLog.match`로 올린다. 실측 뒤 창을 조정한다(데이터 보고서 7절).
2. **이름.** `NameKey.TITLES`에 기사, 씨, 감리, 선생님, 형님. `canonParts(name)=(key, title?)`로 role 후보를 돌려준다. `similar`: 첫 음절은 초성만 같으면 되고, 2음절은 같은 번호일 때만. **성+직책 규칙**: 같은 번호에서 한쪽이 "성 1음절+직책"이고 다른 쪽 첫 글자가 같으면 같은 사람, 이름은 긴 쪽, 짧은 표기는 별칭(문제 6, 7, 8). "사무실", "현장", "경리", "총무"는 사람 이름으로 만들지 않는다.
3. **중복.** `nearTitle` 비교 전에 제목에서 사람 이름·직책·업체명·동작어(확인, 회신, 보내기, 받기, 공유)를 지우고 비교한다. 유사도 0.6 미만은 새 일, 0.6 이상이면 지금처럼 건너뛰되 건너뛴 쌍을 `DUPLICATE_SKIPPED`와 함께 새벽 정리 state의 `mergeCandidates`로 보낸다(문제 9).
4. **인용.** 반복 인용은 제목의 핵심 명사가 인용 앞뒤 한 문장 안에 있을 때만 통과. 퍼지 검사는 자막을 인용 길이 ±20% 창으로 밀며 창마다 3-gram 포함률을 재고 최댓값 0.9 이상(문제 10).
5. **자막 줄.** `segments.joinToString("\n")`(문제 11). 인용 검증은 `bare()` 비교라 영향 없음.
6. **시간 조건.** `request()`의 열린 일 질의에 `OR COALESCE(done_ms,0) > 기록 시각`(문제 16). `applyLedger` 사람 합치기에서 `person_aliases`도 옮김(문제 13). 장부 `trade`는 `TRADES`에 없으면 "기타". `applyLedger`가 모든 업체의 `updated_ms`를 지금으로 만들지 않음(문제 17).
7. **가리기.** `[통화기록] 전화부 이름`과 메일 보낸 사람에 `noNumber`·`maskNumbers` 적용, 메일은 "보낸 사람: 이름"만. 10자리 유선 번호도 `[번호]`(문제 18, 19).
8. 자막 비우기 기준을 `analyzed_ms`(결과 받은 뒤 14일)로(문제 20).

확인 기준: 단위 테스트 — "김과장"과 "김민수 과장"이 같은 번호에서 한 사람(이름 "김민수", 별칭 "김과장") · "정민수/전민수" 같은 업체면 한 사람 · "김민수 씨"="김민수" · "철근 반입 일정 확인"과 "철근 반입 수량 확인"이 다른 일 · 떨어진 두 구절을 이어 붙인 인용이 버려짐 · CACHED_NAME null인 통화기록이 시각·길이로 이어짐 · 전화부 이름 속 번호가 요청에 없음.

### 3.5 묶음 r7-5 지시문 v6 (`docs/routines/hourly.md`, `tidy.md`, `RoutineDocTest`, `ClaudeCallAnalyzer.PROMPT_VERSION=6`, `applyTidy`의 rev)
- hourly.md 4번(장부)에 사람·업체 추가 규칙을 명시(데이터 보고서 4.4절 문장 그대로). 7번(화자)에 (5) 호칭 규칙과 (6) 첫 인사 규칙, 8번(날짜)에 내일·모레·글피·이번 주 안·주말·다음 달 초·요일만 말한 경우·달력 없음 처리, 11번(certainty)에 "확인해 보고 연락" 예, 12번에 update 조건(날짜·수량·방법이 바뀔 때만), 13~16번에 성+직책만 들릴 때 규칙, 19번에 명절 인사·경조사는 business true. 10번(제목)은 **사람 이름을 title에 넣지 않는다**로 바꾸되, 화면 `taskRow`의 META 줄("김민수 님, 오늘 요청")이 실제로 사람 이름을 보여 주는지 먼저 확인하고 바꾼다(r8-화면과 함께, 안 보이면 v2.7에서 보이게 한 뒤 적용).
- tidy.md 6번: 제안 항목에 state의 `rev`를 그대로 적는다. 폰 `tidyState`가 항목마다 `rev`(workflow_items.revision)를 넣고 `applyTidy`는 rev가 다르면 건너뛴다(문제 12). 7번: 없어지는 업체·사람은 지우지 않고 `mergedInto`만 적는다.
- `promptVersion`을 6으로 올리고 `RoutineDocTest`가 두 문서와 상수를 대조.
- **루틴 교체**: 개발 세션이 `update_trigger`로 "이음 통화 분석 (매시간)"(지금 `30 * * * *`, Sonnet 5.5)과 "이음 새벽 정리 (매일)"(05:16, Opus 5.5)의 프롬프트 본문을 새 문서로 바꾼다(루틴 수정은 11/4 사용자 승인). 바꾼 뒤 이름·시각·링크를 보고하고 HANDOFF 표와 설계도 표를 고친다. 매시간 루틴은 지금 24시간 매시 30분이므로 설계도 표의 "07~23시"를 실제대로 고친다.

확인 기준: `RoutineDocTest` 통과 · `inbox-e2e`에 "결과의 counterpart가 있으면 장부 people에 그 id가 생긴다" 검사 추가(selftest 요청은 장부를 안 고치므로 검증용 요청을 `verify-` 접두로 하나 더 두고 루틴이 그것은 장부에 쓰되 다음 실행에서 지우게 함) · 폰이 rev가 다른 제안을 건너뜀.

### 3.6 묶음 r7-6 감시 정확화 (`PhoneStatus.kt`, `StatusLights.kt`, `CallSettingsActivity.kt`의 신호등 입력, `CallDigestWork.kt`의 용량 검사, `SelfCheckNotices.kt`, `tools/inbox/watchdog.py`, `CallTranscriptionRunner.kt`의 stall 규칙)
1. 폴더 불 = `persistedUriPermissions`에 폴더가 있고 `LAST_DISCOVERY_MS`가 24시간 안(#9). 자막 불 = `call_transcription_jobs` QUEUED(10/1 이후, SKIPPED 제외) 수와 가장 오래된 **대기열 진입 시각**(#10, #11). 정리 불 = 6시간 넘게 결과 없는 live 기록 수. 업데이트 불 = `updateReadySinceMs` 24시간 초과면 노랑.
2. 하트비트: `newQueuedCount`, `newOldestQueuedMs`(진입 시각), `oldQueuedCount`, `lastDiscoveryAttemptMs`, `lastDiscoveryOutcome`, `inventoryTruncated`, `heldUnsupported`, `dbPercent`(sealed 파일 크기와 행 수 중 큰 쪽, 문제 4), `updateAvailableCode`, `updateReadySinceMs`, `lastInboxError`, `callLog.match`, `dbRecovery`. `standbyBucket` 5→"EXEMPTED", 50→"NEVER"(#19).
3. 워치독(`watchdog.py`): 자막 경보는 new만, 발견은 attempt가 있는데 성공이 12시간 없으면, 업데이트 24시간 미설치, `lastInboxError`가 2시간 넘게 같은 코드, `dbPercent` 80 초과. 처음 정리 중에는 old로 경보하지 않음(r6-v25-watchdog 반영분 유지).
4. 용량 80% 알림(B4)이 실제 파일 크기로 울리게(문제 4).
5. 긴 통화: 시간 한도로 중지됐는데 그 실행에서 조각이 1개 이상 끝났으면 stall로 세지 않음(#12). 60분 합성 녹음이 끝까지 자막되는 단위 테스트.

확인 기준: 신호등 단위 테스트(폴더 권한 없음 → 노랑, 처음 정리 끝난 뒤 새 통화 대기 → 노랑, 정상 → 초록) · 하트비트 JSON에 새 필드 · `test_watchdog.py`에 새 규칙 각각 · 설정 "저장 공간 n%"가 숫자를 보임.

### 3.7 v2.6 배포와 보고
- 통합 브랜치 `feature/r7-v26`. `docs/VERSION` 2.6. ci 통과 후 lean-calls에 합쳐 배포. 기기 시험 결과는 기다리지 않는다(v2.7에서 봄). 배포 뒤 다음 하트비트에서 `appVersion`, `firstPass.blockedBy`, `newQueuedCount`, `callLog.match.success`를 보고 처음 정리가 실제로 흐르기 시작했는지 사용자에게 보고한다.
- UPDATE_NOTE: `v2.6: 지난 통화 정리가 막힘 없이 돌고, 오늘 통화가 오늘 저녁 보고에 들어가요. 새 버전은 저절로 설치돼요`
- 설계도(Claude Docs) 갱신: "개인정보와 보안"에 통화 목록 대조 파일(`status/calllog.txt`, 이름·번호 포함)의 예외를 사용자 결정(11/4)으로 적는다. "자동 흐름" 표의 루틴 시각을 "24시간 매시 30분"으로.
## 4. v2.7 「매끄러운 화면」

**큰 변화:** 어디를 눌러도 바로 반응한다. 화면이 Activity 하나로 합쳐져 탭·뒤로·알림 이동이 한결같고, 설정에서 적던 것이 사라지지 않으며, 방금 끝난 통화가 "정리 중"으로 보인다. 아침 알림에서 앱을 열지 않고 [다 됐어요]를 누를 수 있다. 화면 검증 보고서의 31건을 모두 닫는다.

두 단계로 나눈다. 1단계(병렬 셋)가 합쳐진 뒤 2단계(병렬 셋)를 그 위에서 한다. 2단계가 끝나면 통합 브랜치에서 **device-test 결과를 기다려** 스크린샷 7장(홈, 체크, 통화 목록, 통화 상세, 업체 상세, 설정, 어두운 홈)과 글자 1.3배 홈 1장을 `evidence/shots/`에서 보고 종합 검토 목업과 비교한다.

### 4.1 1단계

**r8-1 저장소 성능 (`ProtectedDatabase.java`, `IeumApplication.kt`, `CallRecordStore.kt` 생성자·`firstPass()`, 각 화면의 `read()`)**
1. `IeumApplication`이 `CallRecordStore` 하나를 프로세스 수명 동안 잡아 둔다(`IeumApplication.store()`). 화면과 작업은 그것을 쓰고 `use{}`로 닫지 않는다. refs가 0이 되어도 상태를 버리지 않는다(메모리 DB 유지, 쓰기는 지금처럼 트랜잭션마다 persist).
2. `requireReady()`의 Keystore 확인은 처음 열 때 한 번만 하고 결과를 기억한다.
3. 읽기 전용 생성자(스키마 확인·보정 트랜잭션 생략)를 두고 화면 읽기는 그것을 쓴다. 스키마 확인은 앱 시작 때 한 번.
4. 홈 `read()`는 한 번 열어 모두 읽고, `firstPass()`의 기록별 `sentMs`·`receivedMs` 조회는 `pending JOIN call_inbox_sent` 한 쿼리로. 설정 `refresh()`도 한 번 열기. `TodayCallsActivity`의 통화기록 대조는 화면 진입마다가 아니라 발견·업로드 패스에서만(화면은 읽기만).
5. 측정: `ProtectedDatabase.State` 생성, `transaction`, 홈 `read()` 앞뒤에 `System.nanoTime` 로그(내용 없음)를 남기고 하트비트 `dbOpenMs`, `homeReadMs`로 올린다. 단위 테스트는 "홈 read가 DB를 1번 연다"를 open 카운터로 확인.

**r8-2 화면 하나 (`MainActivity.kt` 신설, `screens/HomeScreen.kt`·`CallsScreen.kt`·`TasksScreen.kt`·`CompaniesScreen.kt`·`SettingsScreen.kt`로 각 Activity의 그리기 코드 이동, 매니페스트)**
- 설계 G1 그대로: `enum Screen { HOME, CALLS, TASKS, COMPANIES, SETTINGS }`와 `detail`(통화 id, 업체 id, 사람 id(v2.8), null)을 상태로 갖고 `render()`가 column을 비우고 다시 그린다. 하단 탭은 `screen`만 바꾼다. **현재 탭을 다시 누르면** 상세를 닫고 목록 맨 위로(검색어·날짜 초기화). 뒤로: `detail!=null`이면 상세 닫기 → `screen!=HOME`이면 홈 → 홈이면 종료. 상세를 열기 전 `scrollY`를 저장해 닫을 때 복원(행이 나누어 그려지므로 그려진 뒤 scrollTo).
- 알림 PendingIntent는 `extra("screen")`, `extra("dayMs")`(저녁 보고 날짜), `extra("callId")`를 싣고 `onNewIntent`에서 읽는다. 앱이 꺼져 있다가 알림으로 열려도 뒤로는 항상 "홈 → 종료".
- 다른 화면에서 여는 상세(홈 메모, 체크 행, 업체 사람 행)는 `detail`만 바꾼다(새 Activity 없음). 두 번 눌러도 한 번만 열린다.
- `android:screenOrientation="portrait"`, `onSaveInstanceState`에 screen·detail·검색어·접힌 절 저장.
- 옛 Activity 4개와 `CallUi.go`, `REORDER_TO_FRONT`를 지운다. 테스트 태그(`home_*`, `task_*`, `company_*`, `call_*`)는 유지하고 `UiV2Test`·`UiRoundThreeTest`·`CallUiSeniorTest`는 `MainActivity` 기준으로 고친다. 뒤로 가기 시나리오 테스트(체크 → 업체 → 상세 → 뒤로 → 업체 → 뒤로 → 홈 → 뒤로 → 종료)와 "알림 Intent로 시작 → 뒤로 → 홈 → 뒤로 → 종료", "현재 탭 다시 누르면 목록"을 추가.
- 화면 전환 120ms 페이드. `LaunchIntro`는 그대로.

**r8-3 즉시 반응 부품 (`CallUi.kt`, `TaskActions.kt`, `StatusLights.kt`, 색 토큰)**
1. `CallUi.once(view, 600ms, action)`: 마지막 누름 시각을 tag에 두어 두 번 누름을 막는다. 모든 행과 버튼 리스너가 쓴다.
2. `CallUi.busy(button, "저장하는 중이에요")`: 누르는 즉시 `isEnabled=false`, 글자 교체, 끝나면 복원. "처리 중" 표시 없는 동작을 없앤다.
3. [다 됐어요]: 누르는 순간 행과 버튼을 함께 끄고 0.35 알파, 되돌리기 띠를 바로 띄운 뒤 IO. 실패하면 되돌리고 토스트. `TaskActions`에 진행 중 item id 집합. 띠 문구 "끝낸 일로 옮겼어요 [되돌리기]".
4. `CallUi.loading(column, "불러오는 중이에요")`와 읽기 실패 카드의 [다시 불러오기].
5. `sheetDialog`에 `validate`(문제가 있으면 닫지 않고 시트 안에 안내), 입력 칸이 있으면 포커스와 키보드 자동.
6. 색: MUTED를 글자에 쓸 때는 `#6B7684`(흰 바탕 4.6:1), 회색 바탕 캡션 `#5F6B78`, 탭의 꺼진 상태는 색 대신 굵기와 아이콘 채움. 어두운 모드 값은 구현값을 설계도 표에 반영.
7. 신호등 문구 "위 연결 묶음에서", 줄을 누르면 그 묶음으로 스크롤.

### 4.2 2단계 (1단계 통합 뒤)

**r8-4 홈과 통화 (`screens/HomeScreen.kt`, `screens/CallsScreen.kt`)**
- 홈 숫자 라벨 "오늘 통화 / 오늘 생긴 체크 / 남은 체크할 일". "오늘 통화"는 통화 탭과 같은 기준(기록 + 통화기록 줄)으로 세고, 메모가 없을 때는 "오늘 통화 n건을 정리하고 있어요". 체크 목록은 "오늘 요청 + 확인 요청" 최대 5개, "외 n개 보기 ›", 절 이름 "먼저 볼 체크할 일".
- 통화 직후: 받은·건 전화이고 길이가 0보다 크고 12시간 안이며 폴더가 연결돼 있으면 "녹음을 글자로 바꾸는 중이에요"(`call_transcription_jobs`의 QUEUED/RUNNING과 ±2분 맞춰 더 정확히). 자막이 끝나 업로드된 뒤에는 "정리하고 있어요", 6시간 넘으면 "정리가 늦어지고 있어요".
- 날짜 넘기기: 누르는 즉시 날짜 글자와 화살표를 바꾸고 "불러오는 중"을 보인 뒤 읽기. 검색: `(record, haystack)`을 미리 만들어 두고 250ms 디바운스, 상세에서 돌아오면 검색어 유지.
- 실패한 통화 중 빈 자막(`EMPTY_TRANSCRIPT`)은 "녹음에서 말소리를 찾지 못했어요"로 보여 주고 [다시 정리하기]를 없앤다. 그 밖의 실패에만 버튼.
- [모델 받기]를 모바일 데이터에서 누르면 "Wi-Fi에 연결되면 받기 시작해요"를 단계 본문에 남기고, 받는 동안 1초마다 퍼센트.
- 녹음 폴더 선택 결과 처리와 [지금 업데이트]의 SHA-256·세션 복사는 IO 스레드로, 버튼은 "확인하는 중이에요".
- [알림 허용하기]: 두 번 거절 뒤에는 "알림 설정 열기"(`ACTION_APP_NOTIFICATION_SETTINGS`)로. 판정은 `areNotificationsEnabled()`까지.

**r8-5 체크, 업체, 설정 (`screens/TasksScreen.kt`, `screens/CompaniesScreen.kt`, `screens/SettingsScreen.kt`)**
- 체크 행: 통화에서 온 항목만 누를 수 있고(`OpenItem.recordingId`로 바로 찾기), 직접 적은 항목과 새벽 팔로업은 눌림 표시 없음. 행 순서는 G3(업체 배지 → 제목 → META "김민수 님, 오늘 요청" → ai_note → [다 됐어요])로 통일하고 META의 사람 이름을 누르면 그 통화. 열린 일이 많으면 30개 먼저 그리고 나머지는 나눠 그리기.
- 업체 상세: 머리에 "업체 이름 고치기" 글자 링크(사람 이름 고치기와 같은 시트, `company_aliases`에 옛 이름). 목록으로 돌아올 때 들고 있던 목록으로 즉시 그린 뒤 백그라운드 갱신.
- 설정: `render`가 스크롤 위치를 저장·복원. 스위치·슬라이더처럼 자기 상태를 보여 주는 조작은 다시 그리지 않고 글자만 고침. 메일 주소·비밀번호 입력은 Activity 필드에 담아 다시 그릴 때 넣고, 서비스 버튼은 버튼 색·안내·host 칸만 바꿈. busy는 동작별 플래그로, 해당 버튼만 "연결하는 중이에요". 슬라이더 저장은 `onProgressChanged(fromUser)` 700ms 디바운스. 신호등 FAILED 수는 `EMPTY_TRANSCRIPT` 제외.

**r8-6 알림 (`CallDigestWork.kt`, `NotificationActions.kt` 신설)**
- 알림은 `MainActivity` 하나를 `screen`·`dayMs`·`callId` extra로 연다(스택 문제 소멸). 저녁 알림은 보고 날짜를 실어 다음 날 눌러도 그날 통화가 열린다.
- **아침 알림 동작 버튼**: 알림 본문의 첫 항목(또는 상위 2개)에 [다 됐어요] 액션(`NotificationActionReceiver` → `completeItem`, 되돌리기는 앱에서). 눌리면 알림을 다음 항목으로 갱신. 앱을 열지 않고 아침 보고를 끝낼 수 있다.
- 되돌리기 띠 문구와 "AI 메모" 표기는 "이음 메모"로 검토(선택).

### 4.3 확인 기준 (v2.7 = 기기 시험 게이트)
- 단위: `CallUiSeniorTest`, `UiV2Test`, `UiRoundThreeTest`, `SheetDialogTest`, `RowFitTest` 통과(MainActivity 기준으로 고침) · 뒤로 시나리오 3종 · 두 번 누름 · 설정 다시 그린 뒤 스크롤·메일 입력 유지 · 저장 공간 % 표시 · 사용자 항목 행 눌림 없음 · 홈 read DB 1회 · 알림 액션이 `completeItem`을 부름.
- device-test: 스크린샷 7장 + 1.3배 홈 1장이 `evidence/shots/`에 남고 글자 잘림 없음 · `adb shell am start`로 알림 extra를 넣어 열고 뒤로 두 번에 종료 · 홈 read 시간 로그가 500ms 아래(에뮬레이터, 시드 데이터).
- `tools/check_ui_rules.py` 통과(가운데 점·말줄임표·영어 상태값·빨강 없음).

UPDATE_NOTE: `v2.7: 화면이 바로 반응하고, 탭과 뒤로 가기가 한결같아졌어요. 아침 알림에서 바로 다 됐어요를 누를 수 있어요`
## 5. v2.8 「상대 카드와 사람 화면」

**큰 변화:** 전화번호 하나로 그 상대의 모든 것(소속, 직책, 마지막 통화, 열린 체크할 일, 진행 중인 일, 최근 통화와 문자)을 한 화면에서 보고, 그 자리에서 전화를 걸고 문자를 보낸다. 같은 자료를 번호별로 미리 계산해 작은 저장소에 두어 v3.0의 통화 카드가 0.1초 안에 뜰 수 있게 한다(검증 보고서 6절: 지금의 보호 DB는 열 때마다 통째로 복호화해 0.5~3초가 걸려 통화 화면에 쓸 수 없음).

사용자에게 보이는 변화: 통화 목록·업체 상세·체크할 일의 사람 이름을 누르면 **사람 화면**이 열린다. 사람 화면 머리에 [전화 걸기] [문자 보내기] 원형 버튼. 홈의 "오늘 통화 메모"에서도 사람으로 바로 간다. 업체 상세의 사람 행 ⋯ 메뉴가 사라지고 행을 누르면 사람 화면이다(이름 고치기는 사람 화면 안).

### 5.1 묶음 r9-1 저장소 기반 (`core`, `CallRecordStore`, `ProtectedDatabase`)
1. **번호 정규화 한 곳.** `core/PhoneKey.kt`에 `PhoneKey.of(raw): String?`를 둔다. NFKC → 숫자와 앞 `+`만 → `+82`·`0082`·`82(11~12자리)`는 떼고 다음 자리가 0이 아니면 0을 붙임, `(0)` 제거 → `PrivacyRules.validKoreanPhone` 통과 시 반환, 15xx·16xx·18xx 대표번호는 8자리 그대로 허용 → 그 밖은 null. `counterpartKey`, `MessageIntake.phoneKey`, `FirstPass.readCallLog`, `CallLogReport`, `PrivacyRules.normalizePhone` 호출처가 모두 이것만 쓴다. `MIGRATION_VERSION`을 올려 `people_phones`, `people.phone_key`, `call_records.counterpart_key`, `call_log.number_key`, `personal_numbers`를 다시 계산하고 키가 같아진 사람은 합친다(별칭·call_links·people_phones 함께 이동, 검증 보고서 문제 13·14·22).
2. **프로세스 수명 동안 한 번만 연다.** `IeumApplication`이 `CallRecordStore`(와 `AndroidSqlPort`)를 하나 만들어 들고, 화면과 작업은 `IeumApplication.store(context)`로 같은 것을 쓴다. `use{}`로 닫던 곳은 닫지 않는다. 쓰기는 지금처럼 트랜잭션마다 persist되므로 안전성은 같다(검증 보고서 문제 1). `CallRecordStore`에 메인 스레드 검사(`Looper.getMainLooper().isCurrentThread`면 IllegalStateException)를 넣는다.
3. **트랜잭션 묶기.** `CallRecordWork.runInbox`의 한 기록 처리(`markSent`, `setState`, `setReceived`, `editSent`)를 `port.transaction{}` 하나로(문제 2).
4. **보호 파일 복구.** `.new`는 AEAD 검증을 통과하고 cipherHash가 main과 다르면 승격, 아니면 삭제. `.bak`은 main 검증이 실패했을 때만 승격. 결과를 설정 `DB_RECOVERY_LAST`에 남겨 하트비트 `dbRecovery`로 올린다(문제 3). Robolectric으로 `.new` 잔존 시나리오 테스트.
5. **용량 측정 고치기.** 저장 공간 %는 `ProtectedDatabase.file(context, DATABASE).length()`로, 행 수(10만 한도) 비율도 함께 `max(바이트%, 행%)`(문제 4). 80% 알림(B4)이 실제로 뜨는지 단위 테스트.
6. **인덱스.** `call_links(person_id)`, `call_links(company_id)`, `task_companies(company_id)`, `call_log(number_key,start_ms)`, `call_records(counterpart_key)`, `workflow_items(status,kind)`, `people_phones(person_id)`.
7. `issue_companies(issue_id, company_id)` 표를 `applyLedger`에서 채운다(issues.companies JSON 이름 의존 제거).

### 5.2 묶음 r9-2 상대 카드 저장소 (`pipeline/ContactCards.kt`, 새 파일)
- 별도 암호 파일 `contact-cards-v1.sealed`(같은 `ProtectedDatabase` 방식, 수백 KB): 표 `contact_cards(phone_key PK, json, updated_ms)`.
- 카드 JSON v1: `people:[{personId,name,role,isStaff}]`(공유 번호면 여럿, 최근 통화 순) · `company:{id,name,trade}|null` · `lastCallMs, lastDirection` · `recent:[{atMs,kind,summaryFirst}]×3`(business만, 요약 첫 문장 40자) · `openTasks:[{id,title,requestedMs,status,aiNote}]×5` · `issues:[{title,status}]×3` · `texts:[{atMs,dir,first}]×2` · `updatedMs`.
- 다시 계산하는 때(바뀐 번호만): `applyAnalysis`, `applyLedger`, `applyTidy`, `completeItem`, `undoDone`, `addUserItem`, `renamePerson`, `reconcileCallLog`, `forgetPersonal`, 문자·메일 저장 뒤. 개인 번호(`personal_numbers`)와 설정의 제외 번호는 카드를 만들지 않고 있던 카드는 지운다.
- `ContactCards.get(phoneKey)`: 메모리 `ConcurrentHashMap` → 카드 파일 순. 앱 시작과 매시간 틱 끝에 미리 읽는다. 목표: 캐시 적중 1ms, 파일 읽기 60ms 이내(단위 테스트로 300장 카드 읽기 시간 기록).
- 전체 재계산 `rebuildAll()`은 마이그레이션과 설정의 [카드 다시 만들기](상태와 도움 묶음)에서.

### 5.3 묶음 r9-3 사람 화면 (`CompaniesActivity` 또는 단일 Activity의 PERSON 화면)
- 머리: 이름(TITLE) + 직책·업체(META, "탄탄이엔씨 대표") + [전화 걸기] [문자 보내기](`ACTION_SENDTO sms:`) 원형 44dp. 번호는 화면에 쓰지 않는다(누르면 다이얼러가 보여 줌).
- "마지막 통화" 한 줄(날짜·길이·방향) → "체크할 일" `taskRow`(열린 것 전부, [끝났어요]) → "진행 중인 일"(issue 제목과 현황) → "최근 통화와 문자" `memoRow` 10건(종류 배지: 통화/문자/메일, 누르면 상세) → 맨 아래 글자 링크: [이름 고치기] [이 번호는 개인으로 두기](확인 시트 → `personal_numbers`, 카드 삭제, 이후 올리지 않음).
- 공유 번호(같은 번호 사람 여럿)는 머리 아래 "이 번호를 쓰는 사람: 박영희 대리 외 1명" 한 줄과 사람 선택 칩.
- 진입: 통화 목록 행의 이름, 통화 상세 머리 이름, 업체 상세 사람 행, 체크할 일 행의 "김민수 님", 검색 결과(사람).
- 테스트 태그 `person_*`. `CallUiSeniorTest` 규칙 적용.

### 5.4 확인 기준
- `PhoneKeyTest`: 검증 보고서 6.3절 표의 6가지 입력이 모두 같은 키 규칙으로 나옴.
- 마이그레이션 테스트: 같은 번호의 두 표기가 한 사람으로 합쳐지고 별칭·연결이 따라옴.
- `ContactCardsTest`: 결과 적용 → 카드 갱신 → `get`이 새 요약을 돌려줌 · 개인 번호에는 카드 없음 · 300장 읽기 시간 로그.
- 홈 화면이 DB를 한 번만 연다(`ProtectedDatabase` open 카운터로 단위 테스트).
- 사람 화면 스크린샷 1장(device-test에 추가하되 v2.8에서는 결과를 기다리지 않음).

UPDATE_NOTE: `v2.8: 사람을 누르면 그 사람과의 통화, 문자, 체크할 일이 한 화면에 보이고 바로 전화와 문자를 보낼 수 있어요`

### 5.5 구현 대조 (11/5 오전, Opus 통합, `feature/r9-v28` e368728)
설계와 구현을 맞춰 보고 다르게 정한 것만 적는다. 나머지는 위 설계 그대로다.
- 전화번호 정규화(5.1-1): 설계대로 `PhoneKey.of` 한 곳. 다만 `+` 없이 `82`로 시작하는 8자리 대표번호(15xx 등)도 같은 키가 되게 넓혔다(실제 통화기록에 이 표기가 있음).
- 마이그레이션 뒤 카드(5.2): 설계는 "마이그레이션에서 `rebuildAll()`". 저장소를 여는 중에 카드 파일까지 쓰면 홈이 늦게 떠서, 마이그레이션이 돈 그 열기에서는 `touchAll`(뒤에서 전부 다시 계산)만 걸도록 정했다.
- [상대 카드 다시 만들기](5.2): 설정 "상태와 도움" 묶음의 "문제 알리기" 바로 위. 결과를 "상대 카드 n장을 다시 만들었어요"로 알린다.
- 사람 화면 위치(5.3): `CompaniesActivity`가 아니라 v2.7의 단일 Activity에 `Screen.PERSON`(탭 없음, 연 화면의 탭이 켜진 채, 뒤로 가면 왔던 화면).
- 검색 결과(사람)(5.3): 통화 검색어가 사람 이름·직책·업체 이름에 모두 들어 있으면 통화 결과 위에 "사람" 절로 최대 5명. 사람만 맞고 통화가 없으면 "찾는 통화가 없어요" 대신 사람만 보인다.
- 업체 상세 "최근 통화": 행 전체는 통화를 열고, 배지와 이름만 사람 화면을 연다(통화 목록과 같은 규칙).
- [이 번호는 개인으로 두기]: 저장과 함께 그 번호의 카드를 바로 지운다(`rememberPersonal` → `ContactCards.touch`).
- 남은 것(v2.9에서 같이 봄): 체크할 일 META의 이름은 통화 상대 원문이라 번호가 그대로 보일 수 있다 → 사람 화면 밖에서도 `CallRecordStore.noNumber` 적용.
- 시험: 전체 단위 시험 466건 통과(`PersonScreenTest`에 검색 결과 사람, 개인 번호 카드 삭제 2건 추가), 화면 규칙 0, 비밀 스캔 0.

## 6. v2.9 「통화 직후 정리」

**큰 변화:** 통화가 끝나면 앱이 바로 알아차려 몇 분 안에 "정리하고 있어요 → 정리됐어요: 한 줄 요약" 알림으로 알려 주고, 알림에서 바로 메모를 남길 수 있다. 저녁 7시까지 기다리지 않아도 되고, 통화 종료부터 결과까지 평균 1시간 넘던 시간을 30분 안쪽으로 줄인다. v3.0의 통화 카드가 쓸 "전화 상태 감지"를 여기서 먼저 만든다.

### 6.1 묶음 r10-1 전화 상태 감지 (`pipeline/call/CallStateReceiver.kt`, `CallSession.kt`)
- 권한 `READ_PHONE_STATE`(런타임). 처음 설정 카드에 단계 "통화가 끝난 걸 바로 알아차리기"(이유 문장 먼저, 그다음 요청). 설정 "연결" 묶음에도.
- 매니페스트 수신기 `android.intent.action.PHONE_STATE`. 상태 기계 `CallSession`(프로세스 싱글턴 + `SecureSettings`에 마지막 상태 저장): `IDLE → RINGING(number) → OFFHOOK → IDLE`, `IDLE → OFFHOOK(outgoing) → IDLE`. 같은 상태의 중복 브로드캐스트는 무시하되 번호가 비어 있다가 채워지면 번호만 갱신. 번호는 `EXTRA_INCOMING_NUMBER`(READ_CALL_LOG가 있어 받을 수 있음)를 `PhoneKey.of`로.
- `IDLE`로 돌아오면(직전이 OFFHOOK): `ieum.discovery.aftercall` 일회성 작업(`setExpedited`, 15초 뒤)을 건다 → 발견 사이클 → 새 파일이 있으면 자막 작업을 즉시 → 자막 끝나면 즉시 업로드. 통화기록 트리거(45초)와 15분 주기는 안전망으로 유지.
- 개인 번호·제외 번호 통화는 아무 일도 하지 않는다(알림도 없음).

### 6.2 묶음 r10-2 통화별 알림 (`pipeline/CallFollowupNotice.kt`)
- 채널 `ieum.calls.followup` "이음 통화 정리"(소리·진동 없음, 잠금 화면에는 "통화 정리 중"만).
- 업무 상대로 확인된 통화(상대 카드가 있거나 전화부 이름이 있음)가 끝나면 알림 1건: 제목 "김민수 대표와 통화를 정리하고 있어요", 본문 "끝나면 여기에 요약이 와요" + 액션 **[메모 남기기]**(RemoteInput, 한 줄) → `addUserItem(title, company, source=user, record 연결)`. 알림 id는 기록 키 기준.
- 결과가 오면 같은 알림을 "정리됐어요" + 요약 첫 문장 + 체크할 일 1줄로 바꾸고(BigText), 누르면 통화 상세. 저녁 보고가 나가면 그날 통화별 알림은 지운다.
- 6시간 안에 결과가 없으면 "정리가 늦어지고 있어요, 저녁 보고에 넣어 드릴게요"로 바꾼다.
- 설정 "정리" 묶음에 스위치 "통화마다 알림"(기본 켬). 끄면 정리 중 알림만 없고 결과는 홈에 반영.

### 6.3 묶음 r10-3 지연 단축 (`CallRecordWork`, 루틴)
- 업로드 직후 고유 작업 `ieum.calls.results`를 10분 뒤로 걸고, 기다리는 기록이 남아 있으면 10분 간격 체인(최대 12회, 그 뒤 1시간 틱으로 복귀). 결과 목록 조회는 `If-None-Match`(ETag).
- 매시간 루틴 cron을 `*/30 7-22 * * *`(30분마다)로 바꾼다. 빈 실행은 즉시 끝나므로 사용량 부담이 작다(루틴 수정은 사용자 승인됨, 바꾸면 이름·시각·링크 보고). `fire_trigger` 류 API로 push 때 루틴을 깨울 수 있는지 개발 세션이 확인하고, 되면 `ieum-inbox`의 `on: push, paths: inbox/**` 워크플로에서 10분 디바운스로 깨우고 cron은 보험용으로 둔다.
- 목표: 통화 종료 → 결과 반영 평균 25분(자막 ≈ 통화 길이 × 1.0, 업로드 즉시, 루틴 대기 평균 15분, 수신 평균 5분).

### 6.4 확인 기준
- Robolectric: PHONE_STATE 브로드캐스트 순서(RINGING→OFFHOOK→IDLE, 중복, 번호 지연)에서 `CallSession` 상태와 `aftercall` 작업 등록이 맞음.
- 에뮬레이터: `adb emu gsm call 01011112222` → `accept` → `cancel` 뒤 20초 안에 발견 사이클 로그, 알림 "정리하고 있어요"가 뜸(`dumpsys notification`).
- RemoteInput 메모가 `workflow_items(source=user)`로 저장되고 체크 탭에 보임.
- 결과 체인이 12회 뒤 1시간 틱으로 돌아감(단위 테스트).

UPDATE_NOTE: `v2.9: 통화가 끝나면 바로 정리를 시작하고, 정리되면 요약을 알림으로 알려 줘요. 알림에서 바로 메모도 남길 수 있어요`

### 6.5 구현 대조 (11/5 오전, Opus 통합, `feature/r10-v29`)
설계와 다르게 정한 것과 이유. 나머지는 위 설계 그대로다.
- aftercall 15초(6.1): WorkManager는 expedited 작업에 처음 지연을 허용하지 않는다 → expedited로 바로 시작하고 워커 안에서 15초 기다린다(멈추면 바로 끝).
- 통화 종료 → 저장(6.1): 발견 엔진의 "2분 미만이면 쓰는 중" 규칙(v2.6 r7-2)을 그대로 두어, aftercall은 새 녹음을 발견만 하고 저장은 followup(150초 뒤)에서 한다. 종료부터 자막 시작까지 약 2.5~3분. 이 규칙을 줄이면 덜 쓴 파일을 읽을 위험이 있어 바꾸지 않았다.
- 못 받은 전화: 상태 기계는 알리지만(answered=false) 녹음이 없으니 aftercall도 통화별 알림도 없다. 통화 중 두 번째 벨은 무시하고 첫 통화를 유지한다. 설정을 못 읽으면 개인 번호로 간주해 아무것도 안 한다.
- `CallSession.CallEnd.direction`은 null일 수 있다(번호도 방향도 모를 때). `Snapshot.ignored`(개인·제외 번호면 true, 번호 키는 null)를 더했다.
- 업무 상대 판정(6.2): 상대 카드가 있거나 전화부 이름(통화기록 CACHED_NAME, 없으면 저장된 call_log 이름)이 있을 때. READ_CONTACTS 권한을 새로 받지 않으려고.
- 통화별 알림 지우기(6.2): 저녁 보고가 나간 시각보다 먼저 끝난 통화 알림을 지운다("그날"의 해석). 36시간 넘은 것도. [메모 남기기]는 통화에 이어진 사용자 체크할 일(분석은 메모만 붙임). 설정 "알림 시각" 아래 스위치 "통화마다 알림"(기본 켬).
- ETag(6.3): 빈 결과 목록의 ETag만 설정에 둔다. 장부에는 이름이 있어 디스크에 두지 않는 원칙을 지킴. 10분 체인은 시간대 제한 없이 12회(2시간)면 끝.
- watchdog "분석이 멈췄어요" 3시간은 루틴이 도는 시간(07~23시)만 센다(밤사이 요청이 아침마다 경보되지 않게).
- 처음 설정 카드는 8단계("통화가 끝난 걸 바로 알아차리기"가 정리 동의 앞).

## 7. v3.0 「통화 중 이음 카드」

**큰 변화 (모모콜 방식):** 전화가 올 때와 걸 때 통화 화면 위에 **이음 카드**가 뜬다. 상대 이름과 소속, 마지막 통화 요약, 열린 체크할 일, 진행 중인 일이 벨이 울리는 동안 보이고, 받으면 작은 띠로 접혀 통화 중에도 남아 있다가 끊으면 사라진다. 통화 전에 "지난번에 뭘 하기로 했지"를 떠올리고, 통화 중에 "그 견적서 보내셨어요?"를 물을 수 있다.

### 7.1 동작
| 순간 | 조건 | 카드 |
| --- | --- | --- |
| 벨 울림(RINGING) | 화면 잠금 아님, 오버레이 권한 있음, 번호에 카드 있음 | 펼친 카드. 목표 1초 안(실제 0.1~0.4초) |
| 벨 울림, 화면 잠김 | 오버레이는 잠금 위에 못 뜸 | 같은 내용의 헤드업 알림(포그라운드 서비스 알림, 소리 없음). 받아서 잠금이 풀리면(`ACTION_USER_PRESENT`) 카드로 바꿈. v3.4: 창과 내용 있는 알림을 **함께** 띄우고 잠금이 풀리면 알림을 지운다(수신 화면이 잠금을 가린 상태에서는 창이 보일 수 있음, DESIGN_V4 4.2-1) |
| 받음(OFFHOOK) | | 3초 뒤 띠로 접힘("김민수 대표, 지난 통화: 견적서 금요일까지"). 띠를 누르면 펼침, 다시 누르면 접힘 |
| 걸 때 | 통화 연결 역할 허용됨(선택 단계) | 다이얼 직후부터 펼친 카드(통화 연결 서비스가 번호를 줌). 역할이 없으면 거는 전화에는 카드 없음. v3.4: 역할 조건을 뺐다. 역할이 없어도 OFFHOOK 방송에 온 번호로 카드를 띄우고, 역할은 설정의 선택 항목 "거는 전화에 번호를 더 빨리 알기"로만 남는다(DESIGN_V4 4.1-1) |
| 끊음(IDLE) | | 카드 사라짐 → v2.9의 통화별 알림으로 이어짐 |
| 모르는 번호(카드 없음) | 설정 "전화부에 없는 번호도" 켬일 때만 | "전화부에 없는 번호예요" + 그 번호와의 최근 문자 한 줄(있으면) |
| 개인 번호·제외 번호 | 항상 | 아무것도 안 뜸, 기록도 없음 |

카드 내용(위부터): 이름과 직책(STRONG 20) · 업체와 공종(META) · "마지막 통화 어제 오후 3시, 2분"(META) · "지난 통화" 요약 첫 문장(BODY 18) · "체크할 일" 최대 3줄(제목, "어제 요청") · "진행 중인 일" 1줄 · 오른쪽 위 [접기] [닫기]. 번호와 자막은 카드에 쓰지 않는다. 높이는 화면의 40% 이하라 삼성 통화 화면의 받기·거절 버튼(아래)과 상대 이름(위)을 가리지 않게 세로 38% 지점에 두고, 손잡이로 끌어 옮기면 위치를 기억한다. 글꼴·색은 `CallUi` 토큰 그대로, 어두운 모드 따라감, `FLAG_SECURE`.

### 7.2 기술 결정
| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 들어오는 번호 | v2.9의 `PHONE_STATE` 수신기 + `EXTRA_INCOMING_NUMBER`(READ_CALL_LOG 보유) | `CallScreeningService`는 사용자가 고른 심사 앱에는 **전화부에 있는 번호를 넘기지 않아** 쓸 수 없다(기본 전화 앱이어야 전부 받음). 아내의 업무 상대는 대부분 전화부에 있다 |
| 거는 번호 | `CallRedirectionService`(역할 `ROLE_CALL_REDIRECTION`, API 29+). `onPlaceCall`에서 번호를 `CallSession.pendingOutgoing`에 적고 **즉시 `placeCallUnmodified()`**. 절대 redirect·cancel하지 않음 | 안드로이드 10부터 `NEW_OUTGOING_CALL`은 없어졌고, 이 역할은 전화부 번호를 포함한 모든 발신에 불린다. 역할 요청은 `RoleManager.createRequestRoleIntent`로 한 번 |
| 카드를 띄우는 주체 | 포그라운드 서비스 `CallCardService`(type `phoneCall`, 권한 `FOREGROUND_SERVICE_PHONE_CALL`+`MANAGE_OWN_CALLS`; 시스템이 거부하면 `specialUse`로 폴백). 수신기에서 `startForegroundService` | 수신기는 10초 안에 끝나야 하고 통화는 길다. 백그라운드 포그라운드 서비스 시작 제한의 예외는 배터리 최적화에서 제외된 앱이다(이음은 처음 설정에서 `batteryFree=true`, 대기 버킷 EXEMPTED는 그 결과). `SYSTEM_ALERT_WINDOW` 예외는 보이는 창이 있을 때의 것이라 근거로 쓰지 않는다(v3.4, DESIGN_V4 4.1-4). 시작이 거부되면 `fgsRefused`로 세고, 거부 뒤에도 IDLE 정리 경로로 끝난다. 이미 도는 서비스에는 프로세스 안 정적 참조로 상태를 넘기고 start는 첫 시작에만. 삼성 "절전 앱"의 동작과 phoneCall 타입의 MANAGE_OWN_CALLS 전제는 S26에서 확인 필요. 서비스 알림이 잠금 화면 폴백 역할을 겸한다 |
| 창 | `WindowManager.addView`, `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN`, 터치는 카드 영역만 | 통화 화면 조작을 막지 않음 |
| 권한 | "다른 앱 위에 표시"(`ACTION_MANAGE_OVERLAY_PERMISSION`)는 처음 설정 카드 단계 "통화 화면 위에 이음 카드 보기". 설정의 신호등에 "통화 카드" 줄 추가 | 한 번 켜면 끝 |
| 자료 | `ContactCards.get`(v2.8). 서비스가 먼저 이름 줄만 그리고 50ms 안에 나머지를 채움 | 콜드 스타트에서도 체감 0.1초 |
| 포그라운드 서비스 금지 결정(v2.0)과의 관계 | 통화 중에만 잠깐 도는 서비스이고 알림은 카드 내용이라 "상시 알림" 혼란이 없다. 설계 결정 사항 표에 예외로 적는다 | |

### 7.3 묶음
- **r11-1 발신 번호와 서비스**: `OutgoingCallService : CallRedirectionService`(매니페스트 `BIND_CALL_REDIRECTION_SERVICE`), `CallCardService`(FGS, 수명: RINGING/OFFHOOK 시작 → IDLE+3초 종료), `CallSession`에 `pendingOutgoing(number, atMs)`(20초 안에 OFFHOOK이 오면 발신으로 확정). 역할 요청·오버레이 권한 요청 흐름은 처음 설정 카드와 설정 "통화 카드" 묶음(스위치: 전화 올 때 / 전화 걸 때 / 전화부에 없는 번호도, [카드 위치 처음으로]).
- **r11-2 카드 화면**: `CallCardView`(코드로 그린 View, `CallUi` 토큰, 펼침·접힘·끌기·닫기, 2단계 그리기). 헤드업 폴백 알림(채널 `ieum.calls.card` "이음 통화 카드", 소리 없음, BigText 3줄). 잠금 해제(`ACTION_USER_PRESENT`) 감지해 카드로 전환.
- **r11-3 시험과 안전장치**: 에뮬레이터 시나리오(아래), 설정 신호등 줄, 권한이 사라지면 처음 설정 카드에 다시 나타남, 서비스가 10분 넘게 살아 있으면 스스로 종료(통화 상태를 놓친 경우), 카드가 뜬 횟수·실패 사유(권한 없음, 번호 없음, 카드 없음)를 설정 `CARD_STATS`에 세어 하트비트 `callCard:{shown,noPermission,noNumber,noCard}`로.

### 7.4 확인 기준 (device-test에 `call-card` 단계 추가, v3.0에서 결과를 기다림)
- 준비: `adb shell appops set kr.callbrief SYSTEM_ALERT_WINDOW allow`, `adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION kr.callbrief`, `PreviewSeed`로 번호 01011112222에 상대 카드를 만들어 둠.
- 수신: `adb emu gsm call 01011112222` → 1초 안에 `dumpsys window windows`에 이음 오버레이 창이 보이고 스크린샷에 카드(이름, 지난 통화, 체크할 일) → `adb emu gsm accept` → 3초 뒤 띠로 접힘 스크린샷 → `adb emu gsm cancel` → 창 없음, `dumpsys activity services`에 `CallCardService` 없음.
- 발신: 다이얼러로 01011112222에 걸기(uiautomator) → 로그에 `onPlaceCall` → `placeCallUnmodified` → 카드 스크린샷 → 끊기.
- 모르는 번호 `adb emu gsm call 01099990000` → 설정 꺼짐이면 창 없음, 켜짐이면 "전화부에 없는 번호예요".
- 개인 번호로 지정한 번호 → 창도 알림도 없음.
- Robolectric: 서비스 수명, 역할 없을 때 발신 카드 없음, 권한 없을 때 알림 폴백, 카드 2단계 그리기.
- 실기기(S25) 확인 항목(배포 뒤 사용자 보고로): 삼성 통화 팝업(다른 앱 쓰는 중 벨)과 전체 화면 모두에서 카드 위치, 잠금 화면 폴백, 거는 전화 카드.

UPDATE_NOTE: `v3.0: 전화가 오거나 걸 때 통화 화면 위에 상대와 지난 통화, 체크할 일이 바로 보여요`

### 7.5 구현 대조 (11/5 오후, Opus 통합, `feature/r11-v30`)
- 거는 전화(7.1): uiautomator 다이얼러 대신 `am start -a android.intent.action.CALL`로 시험(같은 CallRedirectionService 경로). 거는 전화 카드는 15초 뒤 띠로 접힘(신호음 동안 볼 수 있게), 받는 전화는 설계대로 3초. v3.4: 15초 상수는 실제로 쓰이지 않아(서비스가 3초에 접음) 지우고 3초로 통일, 발신은 위쪽 25% 안에 펼친 채 3초 뒤 띠(DESIGN_V4 4.2-2). 역할 없이 거는 시나리오도 에뮬레이터에 더했다(4.4-1).
- 서비스 수명(7.3): "10분 넘게 살아 있으면 종료"는 "10분마다 `TelecomManager.isInCall` 점검, 통화가 없으면 종료, 4시간이면 무조건 종료"로(10분 넘는 실제 통화에서 카드가 사라지지 않게). 시작이 거부되면 카드를 바로 띄우고 0.5초 뒤 한 번 더 시도.
- 카드 글(7.1): 높이 40%를 넘으면 진행 중인 일 → 3번째 체크 → 2번째 → 마지막 통화 줄 순으로 뺀다. "지난 통화"·"진행 중인 일"은 내용 앞 16sp 회색 말머리, 체크할 일 제목에 개수. 2단계 그리기의 둘째 단계는 16ms(한 프레임) 뒤.
- [닫기]는 그 통화 동안만 숨김. 처음 설정은 10단계("통화 화면 위에 이음 카드 보기", "거는 전화에도 카드 보기"는 한 번 물으면 완료).
- 기기 시험(7.4): 카드 창이 `FLAG_SECURE`라 스크린샷에는 검게 찍힌다 → 판정은 창 목록·로그·서비스·알림, 카드 내용은 Robolectric(`CallCardOverlayTest`). 에뮬레이터(swiftshader)는 느려서 "벨 → 창" 기준을 3초로(실기기 목표는 1초 그대로, 배포 뒤 하트비트로 확인). 잠금 화면 폴백은 Robolectric만. v3.4: 에뮬레이터에 콜드(`am kill`), 잠금, 통화 대기, 역할 없이 발신 시나리오를 더하고 시나리오마다 logcat을 남긴다(DESIGN_V4 4.4-1). 실기기 확인은 `docs/REAL_DEVICE_CHECKLIST.md`.
- 통계: 번호 없음은 통화가 끝날 때까지 번호가 안 왔을 때만 센다(첫 브로드캐스트는 번호가 없는 일이 흔함). 스위치를 다 끈 경우 신호등은 초록.
## 8. 위험과 대책

| 위험 | 버전 | 대책 |
| --- | --- | --- |
| 처음 정리 관문을 풀면 10월 기록 수십 건이 한꺼번에 올라가 주간 한도를 당김 | v2.6 | 하루 4묶음 상한 유지(60건/일). 한도에 걸리면 루틴은 `limitHit`를 남기고, 폰은 C7 후퇴로 쉼. 사용자에게 "며칠간 사용량이 늘 수 있음"을 배포 보고에 적음 |
| 무인 설치가 삼성 실기기에서 거부됨(직접 설치한 APK) | v2.6 | 거부되면 지금처럼 알림·배너로 폴백. 첫 무인 설치 뒤에는 앱이 설치자라 다음부터 됨(추정). 하트비트 `updateReadySinceMs`로 24시간 안에 확인 |
| 이름 규칙 변경으로 마이그레이션이 사람을 잘못 합침 | v2.6, v2.8 | 합치기는 같은 번호 또는 같은 업체에서만. 합친 기록을 `merge_log`(id만)에 남겨 설정 "합친 사람 n명 되돌리기"로 되돌릴 수 있게 |
| Activity 하나 전환의 회귀 | v2.7 | 기기 시험 게이트에서 스크린샷과 뒤로 시나리오를 봄. 문제가 크면 lean-calls를 v2.6 태그로 되돌림 |
| 보호 DB 핸들을 계속 쥐면 메모리 사용 증가 | v2.7 | 메모리 SQLite는 지금도 열려 있는 동안 같은 크기. 앱이 백그라운드로 가면 `onTrimMemory`에서 refs가 0일 때만 닫음 |
| 상대 카드 저장소가 큰 DB와 어긋남 | v2.8 | 카드에 `sourceRev`(장부 rev, 마지막 적용 시각)를 넣고, 매시간 틱 끝에 바뀐 번호만 다시 계산. 설정 [카드 다시 만들기] |
| `EXTRA_INCOMING_NUMBER`가 특정 OS에서 비어 옴 | v2.9, v3.0, v3.4 | 따로 기다리지 않는다. 첫 방송에 번호가 없으면 같은 상태의 다음 방송이 번호를 채울 때 카드를 띄운다. 통화가 끝날 때까지 번호가 안 오면 카드 없이 "전화 상태만" 처리하고 통화 뒤 통화기록으로 보완. 하트비트 `callCard.noNumberIn`, `noNumberOut`으로 빈도 확인. 발신에서 잦으면 신호등이 설정의 역할 항목을 권한다(자동으로 묻지 않음, DESIGN_V4 4.1-1) |
| 포그라운드 서비스 시작이 백그라운드에서 거부됨 | v3.0, v3.4 | 배터리 최적화에서 제외된 앱(`batteryFree=true`)은 백그라운드 시작 제한의 예외다. `SYSTEM_ALERT_WINDOW` 예외에 기대지 않는다. 시작이 거부되면 `fgsRefused`로 세고 IDLE 정리 경로로 끝낸다. 오버레이 권한이 없어도 서비스는 시작한다(알림 대체에도 프로세스 유지가 필요해 옛 "권한 없으면 서비스를 시작하지 않음"은 지웠다, DESIGN_V4 4.1-4). S26에서 `fgsRefused`로 실측 |
| 삼성 통화 화면과 카드 위치가 겹침 | v3.0 | 세로 38% 기본 위치, 끌어서 이동, 위치 기억. 실기기 확인 뒤 기본값 조정 |
| 통화 연결 역할(발신)이 통화를 막거나 늦춤 | v3.0, v3.4 | `onPlaceCall`에서 아무 조건 없이 즉시 `placeCallUnmodified()`. 예외가 나도 finally에서 호출. 시간 제한을 넘기면 시스템이 그냥 연결해 준다고 기대하지 않는다(사용자에게 묻거나 통화를 막을 수 있음, 확인 필요). 그래서 응답 앞에 아무 일도 두지 않는다. v3.4부터 역할은 선택 항목이라 역할을 주지 않은 폰에는 이 위험이 없다 |
| 사용량: 루틴 30분 주기 | v2.9 | 빈 실행은 즉시 종료. 한 주 뒤 자가 보고(v3.4부터 `status/last-run-hourly.json`)의 빈 실행 비율을 보고 유지·축소 결정 |

## 9. 사용자가 할 일 (버전마다 배포 보고에 다시 적음)

- v2.6: 없음(루틴 교체는 승인된 범위 안에서 개발 세션이 함, 바꾼 뒤 보고). 배포 뒤 아내 폰이 **처음 한 번** 설치 확인을 물을 수 있음.
- v2.7: 없음.
- v2.8: 없음.
- v2.9: 아내 폰에서 처음 설정 카드의 "통화가 끝난 걸 바로 알아차리기"(전화 상태 권한)를 한 번 허용.
- v3.0: 처음 설정 카드의 "통화 화면 위에 이음 카드 보기"(다른 앱 위에 표시)와 "거는 전화에도 카드 보기"(통화 연결 역할)를 각각 한 번 허용. 실기기 확인 결과(카드 위치, 잠금 화면)를 알려 주면 기본값을 조정.
- 공통(이전부터 남은 것): 비밀 `IEUM_SIGNING_SEED`에 현재 토큰 값 복사, 저장소 "병합 후 브랜치 자동 삭제" 켜기, 옛 시험 브랜치 삭제.

## 10. 설계도(Claude Docs)에 반영할 것

- "자동 흐름" 표: 매시간 루틴은 24시간 매시 30분(지금 실제), v2.9부터 30분마다. 발견은 v2.9부터 통화 종료 15초 뒤.
- "결정 사항" 표에 추가: 포그라운드 서비스는 통화 중에만(v3.0), 통화 심사 서비스는 쓰지 않음(전화부 상대 미전달), 발신 번호는 통화 연결 역할, 오버레이 권한은 처음 설정 단계.
- "개인정보와 보안": 통화 목록 대조 파일(`status/calllog.txt`, 이름·번호 포함)은 사용자 결정(11/4)으로 예외. 통화 카드에는 번호와 자막을 쓰지 않음.
- "보고와 화면": Activity 하나(v2.7), 사람 화면(v2.8), 통화별 알림(v2.9), 통화 카드(v3.0).
- "화면 디자인": 어두운 모드 색값을 구현값으로, MUTED 글자색 상향.
- "v2.0 대조 결정"의 보류 항목(단일 Activity, 결과 10분 확인, 전화 올 때 지난 통화 요약)은 이 문서의 v2.7, v2.9, v3.0으로 옮김.

## 11. 용어

| 말 | 뜻 |
| --- | --- |
| 처음 정리 | 설치 뒤 10월 1일부터의 지난 통화·문자·메일을 시간순 묶음(b-)으로 정리하는 것 |
| live / old | 기록 시각이 7일 이내면 live(단건 전송), 그 이전이면 old(묶음). v2.6부터 |
| 상대 카드 | 번호별로 미리 계산해 작은 저장소에 둔 "이 사람과의 지금 상황"(v2.8). 통화 카드와 사람 화면이 읽음 |
| 통화 카드 | 전화가 올 때·걸 때 통화 화면 위에 뜨는 이음의 오버레이(v3.0) |
| 하트비트 | 폰이 매시간 올리는 `status/phone.json`(숫자와 상태만) |
| 워치독 | ieum-inbox의 3시간 주기 점검 워크플로. 이상이면 GitHub 이슈로 관리자에게 알림 |
