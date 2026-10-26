# 큰 기능 이정표와 Git 통합 계약

**최신 판정:** [runtime 왕복·독립 인수 검토와 SIWC 구현 계약](RUNTIME_ACCEPTANCE_20261026.md)을 우선 읽는다. 통합 `dc0c16b`는 공개 전사·검토 저장·PARTIAL 보고의 로컬 기반 데모다. 앱 내 ChatGPT 로그인·기존 폴더 선택 후 실제 **Whisper 전사→GPT-6 Astra xhigh 내용 분석→별도 맥락 추론→결과파일→앱 검증·보호 저장** 왕복이 최우선 OPEN이며 내부 규칙 추출을 의미 분석 완료로 세지 않는다. SIWC의 원음 직접 입력 미지원과 Private client/Android callback 검증을 명시한다. 신규 담당은 `feature/astra-audio-roundtrip` / `01a177e0-5d8a-72dc-831d-884f96b5a033`이다. 아래 base 기준 코드·초기 통합순서는 과거 출발점으로 보존하고 최신 상태/배치와 구분한다.

[후속 SIWC 등록 판정](SIWC_REGISTRATION_DECISION.md): 공식 개인 로컬 프로젝트 경로를 확인했으며 Private 저장소의 공개 전환은 선행조건이 아니다. 첫 공식 로그인에서 client를 동적으로 등록한다. 업무 운영 범위·Android callback·실제 grant/추론은 별도 검증하며 `4b3ecff`의 fixture 기반 왕복 계층을 실제 Astra 실행으로 세지 않는다.

기준은 `main`의 `67fac72ccb8ad63655b281c31dcaaae4680511f9`와 직접 읽은 [이음 기능 설계도와 검증 일지](https://chatgpt.com/space/page_257b0a73cf808191a03c02362edc2673) sequence54다. 이 문서는 해당 설계의 **M1~M6 이름·완료 게이트를 유지**하고, Mac의 독립 Git checkout에서 확인한 코드와 다음 연결을 대응한다. R01~R23·PT-REQ 번호, 과거 실패와 취소 한계는 유지한다. 진행률·완료 예정일은 산정하지 않는다.

소스 확보가 막혔던 Windows 차수의 제품 변경은 0이다. Mac Git 이관·원격 clone 확인은 [MIGRATION](MIGRATION.md)의 별도 성과이며 M1~M6 제품 완료로 세지 않는다. 이 문서 작성 branch는 `docs/milestones-git`이고 제품코드 변경은 없다. 원격 설계 Page와 동결된 `plans/` 계약·fixture는 수정하지 않는다.

## 증거를 읽는 기준

| 구분 | 여기서 확인한 범위 | 완료로 확대하지 않는 범위 |
| --- | --- | --- |
| 코드 확인 | 아래 base SHA의 함수·호출부·시험 소스 직접 읽기 | 시험 실행 PASS, 운영 연결 |
| 과거 관측 | [code34 인계](CODE34_HANDOFF_KO.txt)의 AOSP35 ARM64 AVD 공개 JNI Whisper 2건 → 제한된 추출 요약 → 이력/할일 및 재열람 중복 방지 | 새 branch의 실행, 물리 S25, x86 실행, high/dot 의미 분석 |
| 이관 시험 | [MIGRATION](MIGRATION.md)의 최초 전체·집중 재검증·SKIP을 각각 유지 | 서로 다른 실행의 합산 PASS, 기능 진척률 |
| 이번 문서 검사 | 문서 링크·변경 범위, 두 설계 validator, export scan, diff 검사 | Android/DB/STT/계정/OS 예약/실수신 인수 |
| 병렬 제품 branch | 아래 담당과 경계만 지정; 검증된 commit·원결과는 아직 이 문서에 미수신 | 착수·코드 존재·모의 ACK를 기능 완료로 기록 |

`plans/context/integration_contract.json`의 sequence53·소스 미확보 표시와 `plans/kst-report/reporting-contract.json`의 `PROPOSED_NOT_PRODUCT_IMPLEMENTED`는 당시 인계 상태다. 지금 소스를 읽었다는 이유로 해당 원문을 구현 완료로 바꾸지 않는다. 맥락 30건·보고 40건은 제품 인수 시나리오이며 validator 통과와 별개로 NOT_RUN이다. [합성 자료 출처](FIXTURES.md)를 유지한다.

## 담당과 파일 소유권

부모가 전달한 현재 Git 담당이다. 아래 경계는 병렬 충돌을 줄이기 위한 초기 계약이며 공유 파일 변경은 부모가 한 writer를 정한 뒤 적용한다. 다른 담당의 branch를 수정하거나 임의 merge하지 않는다.

| 역할 / branch | thread ID | 소유 범위와 전달물 |
| --- | --- | --- |
| 녹음·기존코어 / `feature/unclassified-audio` | `01a17791-4e60-7733-8a42-88fc78e34020` | M1과 M3 수집·job·분석·회수. 기존 discovery/Coordinator/provider/consumer와 관련 회귀. 미분류 source를 분석 후 업무에 연결하는 계약과 기존 accept 통합 |
| 보고·스케줄 / `feature/morning-report-runtime` | `01a17791-8e4d-722f-b668-22d593f8c030` | M3 시간창·증분 요청 접점, M5 `DailyReportContract/Runtime/Schedule/Ledger` 및 전용 보고/outbox 코드·시험. 실제 전송 활성화는 포함하지 않음 |
| 신규 맥락 / `feature/context-reconciliation` | `01a17791-c5d0-732a-8664-7f5bc873902e` | M2 독립 reconciliation 모듈·후보 reducer·전용 시험. M4 정규화된 허용 사건의 입력 계약. 기존 Store/SQL·collector·보고 scheduler를 직접 수정하는 범위는 별도 조정 |
| 문서 / `docs/milestones-git` | 현재 문서 담당 | 이 문서, DEVELOPMENT의 협업 링크, plans README의 Git 이정표 링크. 이정표·인터페이스·완료정의만 수정 |
| 부모 / 통합 조정 | 원 조정 대화 | 공유 파일 writer·계약 결정, 검증된 commit별 통합, 독립 QA 및 실기기/실계정 단계 배정 |

충돌 예상 지점은 `AndroidAutomationStore.kt`의 `accept`와 `reportBatch`, `RecordingWorkflowStore.kt`, `AndroidAutomationRuntime.kt`, `GeneralLocalStore.kt`, `SecureSettings.kt`, `call_core_schema.sql`과 모듈 Gradle 설정이다. 보고용 read adapter가 필요해도 맥락·보고 담당이 이 파일을 동시에 고치지 않는다. `AutomationLocalReportNotifications.kt`의 기존 로컬 사본 경로와 `DailyReportLedger`의 검토 보고 경로도 통합 때 함께 대조한다.

## 기능 사이의 인수 계약

아래는 **논리적 전달 의무**다. 새 DTO 이름·외부 wire·DB 스키마 승인이 아니다. 실제 구현 API와 변경 파일 목록은 담당 branch의 commit에서 확인한다.

| 연결 | 입력과 출력 | 반드시 지킬 경계 / 확인할 시험 |
| --- | --- | --- |
| M1 접수 → 분석 | owner, source ID/version/audio hash, 원 request/job/result file binding, lease/revision, 원 발생시각의 값 또는 미상 | 번호·사람·case/task·발생시각을 임의 생성하지 않음. 제외·철회는 원음 읽기 전 차단. 같은 source/version 재처리와 다른 source의 같은 hash를 구분 |
| M1 → M2 | 원문·원문 hash, 실제 엔진 관측과 모델/runtime pin, 근거 인용/위치, 알려진 연결과 미확정 연결 | `LocalExecutionObservation`과 소비자 검증 수준 유지. fixture/imported 결과의 자체 verified 표시는 승인 아님. 단순 추출과 high/dot 의미 검토를 구분 |
| M2 → 기존 저장 | 후보 delta, 근거 source/version/hash, hold/conflict 이유, 기대 user/task/board/관계 revision | 기존 `Store.accept()`와 최종 현재성 검사·transaction을 재사용. 모듈은 canonical 쓰기 권한을 발급하지 않음. 수동 DONE/메모/잠금·숨김·보관·owner 격리 유지 |
| M3 증분 → M5 | 대상 window, 내구성 수집 cursor, 이유(23시/보고 전/늦은 결과), generation; 완료 상태·저장된 cursor | 요청 ACCEPTED는 수집 COMPLETE가 아님. cursor는 허용 자료의 내구성 저장 후 전진. occurredAt과 observedAt/cursor 분리. 취소·이전 owner/generation은 commit 불가 |
| M2/M4 → M5 | 현재 허용 snapshot과 근거/receipt, source revision·정책 fence, 미처리/실패/검토/연결필요/시각미상·coverage | `CanonicalReviewedDailySource`의 ACCEPT 또는 현재 유효 USER_REVIEW_SUMMARY 소비를 대조. `APPLIED_SAFE_DERIVED`가 이 보고 입력과 같은 승인이라는 가정 금지. 미검토 후보를 확정 보고로 승격하지 않음 |
| M5 생성 → outbox | 불변 보고 본문/hash, owner+KST+보고일+종류, revision, 채널+대상 결속, 멱등 key | 생성·저장·전송시도·서비스 ACK·수신처 도착을 각각 기록. 무응답은 UNKNOWN, dry-run은 실제 ACK 아님. 같은 ACK된 revision 재전송 금지 |

동결 외부 6필드 `schema_version/job_id/source_pack_sha256/base_revision/summary/claims`, v0/RO1/SG 및 원 자료 identity는 유지한다. 새 내부 계약이 필요하면 기존 타입과의 매핑·caller·회귀·migration 영향부터 제시한다. `plans/`의 SnapshotPort/IncrementalSyncPort 등 제안 이름을 실제 존재하는 제품 API로 보고하지 않는다.

## M1 녹음 한 건을 실제 분석하고 업무와 할일에 반영

**코드/기존 증거:** [LocalRecordingProducer](../android/app/src/main/java/kr/callbrief/LocalRecordingProducer.kt)는 runner → 검증 소비자 → [Coordinator](../android/app/src/main/java/kr/callbrief/AutomationCoordinator.kt) → [Store.accept](../android/app/src/main/java/kr/callbrief/AndroidAutomationStore.kt)를 연결한다. [AndroidLocalSttPilot](../android/app/src/main/java/kr/callbrief/AndroidLocalSttPilot.kt)과 JNI는 code34 공개 시험 경로다. 이는 허용 hash의 공개 합성 자료에 제한된 실행·추출 결과다.

**미구현/막힌 연결:** [RecordingWorkflowStore.prepareAnalysis](../android/app/src/main/java/kr/callbrief/RecordingWorkflowStore.kt)는 source/hash/revision 기반 요청을 준비하지만 Coordinator의 `contextReady`는 case/task/원시각을 요구한다. `frozenBusinessContext`도 case를 읽으며 [worker 계약](../worker/src/contract.mjs)의 등록 검증은 case와 유효 원시각을 요구한다. accept는 사람/업무/시각과 승인 수준을 다시 검사한다. 한 곳의 조건 완화만으로 미분류 종단이 완성되지 않는다. 일반 녹음의 생산 Android 연결과 실제 dot 음성 왕복은 OPEN이다.

**의존성/담당:** 녹음 담당이 접수와 분석 후 업무 binding을 분리하고 M2와 연결한다. 기존 엔진·모델/ABI·source 권한·저장 gate가 필요하다. 지원 입력이 파일 선택인지 자동 폴더 수집인지 결과에 명시한다.

**종단 완료정의:** 공개/합성 미분류 녹음 한 건의 동일 source/version/audio hash → 실제 STT → 요약 → 근거 있는 사람·업무 결속 → 기존 이력/할일 → 프로세스 재기동 후 재열람을 연속 영수증으로 확인한다. 불명확한 연결은 후보로 남고 가짜 case/task/번호/시각은 없어야 한다. 동일 사건 재전달은 이중 이력·할일 0, 다른 source의 같은 bytes는 별도 사건으로 보존한다. 제외 원음 읽기 0, 수동 DONE·메모·보관 보존을 함께 인수한다.

**시험/제한:** 기존 [LocalRecordingProducerIntegrationTest](../android/app/src/test/java/kr/callbrief/LocalRecordingProducerIntegrationTest.kt)의 P3301/04/05/06/07/08/09는 fixture·변조·철회 경계다. P3302/03은 로컬 엔진 pin 없으면 SKIP이다. code34 AVD 관측은 과거 근거이며 새 미분류 종단·S25·실제 high/dot 실행은 이번 문서 작업에서 NOT_RUN이다.

## M2 누적 맥락으로 약속과 서류와 할일을 갱신

**코드/기존 증거:** Store의 `automation_cumulative_history`·case head·item binding, RecordingWorkflowStore의 `applyBusinessReview`/수동 편집과 [AnalysisBusinessIntegrationTest](../android/app/src/test/java/kr/callbrief/AnalysisBusinessIntegrationTest.kt), [AndroidAutomationIntegrationTest](../android/app/src/test/java/kr/callbrief/AndroidAutomationIntegrationTest.kt)에 제한된 파생 적용과 충돌 보호가 있다. 현재 로컬 producer는 추출 요약과 확인 필요한 할일만 만든다.

**미구현/막힌 연결:** 여러 사건의 기한 정정·취소·부분 이행·문서판·사람/회사 관계의 일반 의미 reconciliation은 code34 성공으로 입증되지 않았다. 늦은 자료·동명이인·공용번호·번호 재사용 및 관계 철회에 대한 후보 계산과 기존 저장 연결이 필요하다. 사람별 대규모 UI는 이 종단의 선행조건이 아니다.

**의존성/담당:** 맥락 담당은 독립 모듈에서 M1/M4의 허용 사건을 받아 근거와 후보·보류를 반환하고 녹음 담당/부모가 기존 저장에 연결한다. source identity, content hash, sourceVersion, 발생/관측시각, 수동/관계 revision은 서로 다른 축이다. 실제 검토 출처가 없으면 모델 플래그로 권한을 만들지 않는다.

**종단 완료정의:** 두 건 이상의 연속 사건에서 요청 생성 → 기한 정정 → 취소 또는 일부 이행을 구분하고, 현재 상태·서류·할일의 변화마다 원문 위치·출처·발생시점·적용 이력을 재열람한다. 늦은 과거 사건이 현재값을 단순 역행시키지 않고, 같은 근거의 인용/재전달을 독립 사실로 중복 계산하지 않는다. 충분한 근거는 기존 허용 gate로 적용하고 불확실한 사람·업무 연결은 보류한다. DONE·잠금·메모·숨김은 불변, 네 기존 화면의 조회값은 같은 snapshot과 일치해야 한다.

**시험/제한:** [맥락 시나리오](plans/context/synthetic_scenarios.json)의 30건은 NOT_RUN. 순수 reducer → 실제 repository transaction/rollback·재열람 → Android 소비 회귀를 구별해 기록한다. 모듈 단위 합격만으로 canonical 저장·high/dot 의미 검토·실제 채널 연결을 완료로 올리지 않는다.

## M3 백그라운드 수집과 실행을 중단 뒤에도 이어가기

**코드/기존 증거:** [AutomaticRecordingDiscovery](../android/app/src/main/java/kr/callbrief/AutomaticRecordingDiscovery.kt)는 현재 선택·owner/auth/policy fence와 15분 주기 discovery, foreground hint를 가진다. [AutomaticResultReconciliation](../android/app/src/main/java/kr/callbrief/AutomaticResultReconciliation.kt)은 기존 job/lease의 결과 대기를 재개한다. `Worker.Result.success`는 검사 실행 성공이며 분석 완료가 아니다. [복구 회귀](../android/app/src/test/java/kr/callbrief/AutomaticDiscoveryRecoveryIntegrationTest.kt)와 [native discovery 시험](../android/app/src/test/java/kr/callbrief/NativeAutomaticDiscoveryTest.kt)이 존재한다.

**미구현/막힌 연결:** 명시적 KST 23시 1차·보고 전 증분을 실제 생산 M1 처리에 연결해야 한다. 기존 [DailyReportSchedule](../android/app/src/main/java/kr/callbrief/DailyReportSchedule.kt)은 기기 시간대 06시 목표다. 실제 dot bytes 수신·실행권·결과 writer·앱 반환 및 CP08 최종 admission 뒤 강한 취소는 계속 OPEN이다.

**의존성/담당:** 녹음 담당은 discovery/job/분석/회수, 보고 담당은 KST 시각·증분 요청·복구 접점을 맡는다. 기존 lease를 임의 연장하거나 재시도에서 새 job을 만들어 불확실한 전송을 덮지 않는다. WorkManager 등록과 실제 OS 실행, 정확한 시각 보장은 별개다.

**종단 완료정의:** 실제 OS 작업 실행 → 새 허용 source 발견 → 같은 job의 실제 분석·회수 → 저장 receipt를 연결한다. 프로세스 종료·재기동·오프라인·중복 결과·lease 만료·철회 경합 뒤 원 job과 cursor를 보존한다. KST 23시 및 보고 전 증분의 대상창·수집 cursor·미처리 결과를 남기고 지연/실패를 완료로 숨기지 않는다. OS force-stop 중 실행을 보장하지 않고 앱 재개 시 누락 구간을 조정한다.

**시험/제한:** fake clock/host 등록 검사, DB 재열람, 실제 OS 예약·재부팅·휴면 실행을 별도 기록한다. 이번 문서 작업은 새 자동화 등록·기기 실행 0이며 실제 S25/GMS/Drive/dot 경로는 NOT_RUN이다.

## M4 문자와 네이버 메일을 같은 업무 근거로 연결

**코드/기존 증거:** [AndroidSmsReadOnlySource](../android/app/src/main/java/kr/callbrief/AndroidSmsReadOnlySource.kt)는 기존 READ_SMS 권한, 정확 번호/기간/ID 재검사 뒤 본문을 읽고 부분 범위를 표시한다. [MailIntake](../android/naver-mail-core/src/main/kotlin/kr/callbrief/navermail/MailIntake.kt)는 foreground 범위·epoch·명시 선택 plain body와 실계정 승인 policy를 사용한다. RecordingWorkflowStore에 네이버 후보 저장/확인·철회가 있다. 번호·주소·제목 키워드 일치는 사람/업무 사실 확정과 다르다.

**미구현/막힌 연결:** 실제 S25 SMS와 네이버 계정의 허용 범위 읽기, 원 ID/시각/인용 보존, M2의 업무 관련성/갱신까지 이어지는 실연결 인수가 남는다. 네이버는 종합의 필수 의존성이다. 미연결 상태에서도 기존 로컬 기록은 읽되 종합 완료로 표시하지 않는다.

**의존성/담당:** 맥락 담당은 정규화 사건과 관계 근거의 입력 계약만 소유한다. 실제 collector/권한 통합의 파일 담당은 부모가 배정한다. 새 계정·토큰·SMS 권한·실자료 읽기는 이번 Git 개발의 승인 범위가 아니다.

**종단 완료정의:** 별도 승인된 범위에서 통화의 서류 요청 → 허용 SMS의 정정 → 허용 네이버 메일의 수신 근거 → 같은 업무의 M2 갱신을 각각 실제 관측한다. 무관 주소/도메인·인용 전달·중복·만료·철회·계정 전환에서 오연결·본문 노출·중복 갱신은 0이어야 한다. 두 채널의 실제 0건·미연결·거절·부분 수집을 구분한다.

**시험/제한:** 기존 [SMS SQL 회귀](../android/app/src/test/java/kr/callbrief/Code21SmsWidthSqlTest.kt), [메일 intake 시험](../android/naver-mail-core/src/test/kotlin/kr/callbrief/navermail/MailIntakeTest.kt), [loopback 시험](../android/naver-mail-core/src/test/kotlin/kr/callbrief/navermail/RelatedMailboxLoopbackTest.kt)은 합성/로컬 근거다. 실제 채널 및 M4 종단은 NOT_RUN이다.

## M5 오전 6시 업무 보고와 실제 발송

**코드/기존 증거:** [DailyReportContract](../android/app/src/main/java/kr/callbrief/DailyReportContract.kt)는 범위·readiness·delivery를 분리한다. [Runtime](../android/app/src/main/java/kr/callbrief/DailyReportRuntime.kt)은 현재 허용된 canonical/사용자 검토 요약을 읽고, [Ledger](../android/app/src/main/java/kr/callbrief/DailyReportLedger.kt)는 부분 사본·generation·claim·재시도/UNKNOWN을 보존한다. [자동 로컬 사본 경로](../android/app/src/main/java/kr/callbrief/AutomationLocalReportNotifications.kt)도 별도로 존재한다. `KakaoDailyReportSender.gate/send`는 모두 DISABLED다.

**미구현/막힌 연결:** 기기 기본 시간대와 KST 요구의 차이, 전날+최근 7일, 23시/증분과 snapshot·coverage, 늦은 결과 revision·내구성 outbox를 연결해야 한다. 기존 로컬 사본을 전체 종합/실제 발송으로 세지 않는다. 보고의 기본 실발송 경로와 실제 수신은 미완료다.

**의존성/담당:** 보고 담당이 M2의 허용 snapshot, M3의 수집 상태, M4의 채널 상태를 소비한다. `DailyReport*` 바깥 Store/SQL 접점은 부모가 조정한다. 실제 카카오 연결·수신처·전송 범위·권한은 별도 승인 뒤 인수한다. 선택 채널이 없어도 앱 보고 저장은 가능해야 한다.

**종단 완료정의:** 보고일 D를 Asia/Seoul 달력 날짜로 두고 전날 `[D-1 00:00, D 00:00)`, 최근 7일 `[D-7 00:00, D 00:00)`를 사용한다. 06시 미완료 보고에 분석 대기·실패·검토 대기·채널 연결필요·시각미상·수집범위 미확인을 남긴다. 늦은 보완은 같은 보고 key의 새 revision이며 가시 snapshot이 같으면 불필요한 revision/중복 전송이 없어야 한다. 앱 저장 → 시도 → 상관관계가 맞는 서비스 ACK → 승인 수신처 도착을 별도 증거로 닫는다. ACK는 읽음 확인이 아니다.

**시험/제한:** [DailyReportSchedulerTest](../android/app/src/test/java/kr/callbrief/DailyReportSchedulerTest.kt), [WorkerRecovery](../android/app/src/test/java/kr/callbrief/DailyReportWorkerRecoveryTest.kt), [CanonicalInput](../android/app/src/test/java/kr/callbrief/DailyReportCanonicalInputTest.kt), [PartialDailyReportIntegrationTest](../android/app/src/test/java/kr/callbrief/PartialDailyReportIntegrationTest.kt)를 보존하고 새 행동 회귀를 추가한다. 자정 양쪽·늦은 전날 입력·owner/generation 변경·crash/UNKNOWN·중복 ACK 반례가 필요하다. [보고 인수 40건](plans/kst-report/acceptance-cases.json)은 NOT_RUN이며 설계 시간 벡터 PASS와 실제 06시/카카오 수신을 구분한다.

## M6 S25 실사용 인수와 플랫폼 간 재개 인계

**코드/기존 증거:** Mac에서 원격 기준 commit의 독립 clone과 문서/소스 읽기는 완료했다. 기존 JDK/SDK/NDK·JNI/모델·서명·합성 자료 조건은 [README](../README.md), [ASSETS](ASSETS.md), [MIGRATION](MIGRATION.md)에 있다. code34의 AVD ARM64 성공은 물리 S25 및 x86 실행의 대체 증거가 아니다.

**남은 범위/담당:** 부모가 기능 commit 통합·독립 QA·고정 APK/소스 인계를 조정한다. S25의 실제 지원 입력·저장/재열람·백그라운드·권한·보고 경로와 Windows 재빌드는 별도 인수다. 기존 키/모델/SDK/AVD를 삭제하거나 실제 앱 데이터를 지우지 않는다. 필요한 설치·서명·권한 문제가 있으면 정확한 도구/대상/오류와 미실행 범위를 보고한다.

**종단 완료정의:** 같은 source/job/result·receipt로 M1~M5의 지원 범위를 고정 APK에서 확인하고, 재시작·중복·제외·철회·DONE/메모·보관·계정 전환을 포함한다. 인계에는 repo/base/branch/commit, 원격 일치, diff, 명령·시험 원결과·실패/재검증·SKIP, APK/모델 pin·ABI·서명 조건, 미실행과 다음 재개 지점을 남긴다. 다른 컴퓨터에서는 실제 clone/해시·빌드/실행 또는 정확한 차단을 관측해야 재개 완료다.

**시험/제한:** 이번 문서 작업의 Android 빌드·AVD·S25·실계정·실제 전송·Windows 실행은 모두 NOT_RUN이다. 별도 승인 전에는 공개/합성 fixture로만 개발한다. 실자료·인증키·실사용 DB·원본 작업 사본·다른 프로젝트·NAS는 이 branch의 대상이 아니다.

## 제안 통합 순서와 완료 기록

1. 부모가 이 초기 계약과 공유 파일 writer를 확정한다. 문서 branch는 base 기준 현황을 고정하며 제품 완료 선언을 포함하지 않는다.
2. 녹음 branch의 미분류 접수 → 실제 분석 → 검증 소비/기존 저장 경계를 우선 통합한다. 지원 입력과 아직 후보에 머무는 출력을 명시하고 기존 frozen wire·Store 보호 회귀를 실행한다.
3. 맥락 모듈은 병렬 개발할 수 있으나 앱 소비 연결은 녹음의 실제 타입에 맞춰 통합한다. source identity/시간/근거·후보 → 저장 transaction을 연결한 M2 시험이 있어야 종단을 닫는다. 모듈 단독 commit을 먼저 받아도 통합 완료로 세지 않는다.
4. 보고 branch의 독립 시간창·장부/outbox를 검토한 뒤 M2 snapshot 및 M3 증분 접점을 연결한다. 기존 검토 보고와 자동 로컬 사본 두 경로의 승인 의미·정책·보관·revision 호환성을 회귀한다.
5. 통합 commit에서 공개/합성 M1→M2→M3→M5와 M4 합성 adapter를 회귀한다. 실제 SMS/네이버·dot·카카오·S25는 별도 승인/지원 조건을 충족한 뒤 각각 인수한다. 한 계층의 PASS로 다음 계층을 닫지 않는다.
6. 각 기능 branch는 자기 branch만 일반 push하고 부모가 통합 순서를 조정한다. main 직접 push·force push·다른 worker branch 수정·무단 merge는 하지 않는다. PR을 만들면 draft로 둔다.

각 이정표 갱신에는 `base SHA / branch / commit / 변경 파일 / 입력 fixture·hash / 실행 명령·환경 / 관측 receipt·원결과 / 실패와 재검증 / SKIP·NOT_RUN / 외부 의존성 / 다음 통합 접점`을 기록한다. 테스트 수는 실행별로 적고 과거 또는 집중 재검증 수와 합산하지 않는다. branch 산출물을 수령하기 전에는 본 문서의 base 기준 상태를 새 구현 상태로 바꾸지 않는다.

## 문서 branch 검증

문서만 바뀌므로 Android 동작 회귀를 실행한 것으로 보고하지 않는다. Mac의 Python 3.9.6으로 맥락 validator 12 PASS/실패0, 보고 validator 18 PASS/실패0/SKIP0을 각각 실행했다. 문서 3개의 상대 링크 38개와 M1~M6 섹션을 확인했다. 이는 README의 전체 개발 환경 요구 충족이나 제품 빌드 검증이 아니다. validator 생성 JSON은 ignore된 결과이며 제품 소스나 인수 결과를 대체하지 않는다.

staged 378파일 export scan은 발견0, `git diff --cached --check`는 통과했다. 소유 문서 밖 기존 375파일은 기준 commit과 Git blob이 일치했다. 최초 단순 바이트 비교의 `gradlew.bat` 차이는 checkout의 CRLF 규칙이므로 `.gitattributes`를 적용한 hash로 재검사했다. 제품 파일 변경은 0이다.

```sh
python3 docs/plans/context/verify_fixture_integrity.py
python3 docs/plans/kst-report/verify_contract.py
python3 tools/scan_export.py
git diff --check
git diff --name-only 67fac72ccb8ad63655b281c31dcaaae4680511f9
```

문서 내 상대 링크와 함수/시험 경로를 현재 checkout에 대조한다. commit/push 후 `git rev-parse HEAD`와 `git ls-remote origin refs/heads/docs/milestones-git`를 비교하고 clean status를 보고한다. Git 문서 전달 완료와 M1~M6 기능 인수 완료는 별도다.
