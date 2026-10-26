# 누적 업무 맥락 후보 엔진

기준 `67fac72ccb8ad63655b281c31dcaaae4680511f9`에서 추가한 독립 Kotlin/JVM 모듈이다. 정규화된 통화 분석·문자·메일 사실을 누적해 약속, 문서판·필요서류, 진행상태, 할일의 **검토용 현재 후보**를 계산한다. 본문 의미 추출, 사람 자동 확정, Android 저장 연결, 실제 서비스 수집을 구현한 것으로 세지 않는다.

공유 `android/settings.gradle.kts`, 앱/코어, Store, SQL, frozen 6필드 wire는 변경하지 않았다. `docs/milestones-git`의 `8943536defc15fd0c68f9ef293a4b6b9bd0f3176:docs/MILESTONES.md`를 읽었으며 M2→기존 저장 연결은 녹음 담당 단일 writer/부모의 통합 작업이다. 해당 문서 branch를 merge하지 않았다.

## 실행

기존 JDK17과 Gradle 8.13을 사용한다. Kotlin은 저장소와 같은 2.1.21이다. 별도 SDK·NDK·모델·키·Android 권한은 필요 없다. 저장소 루트에서:

```sh
export JAVA_HOME=/path/to/prepared/jdk17
export GRADLE_USER_HOME="$PWD/gradle-user"
sh android/gradlew -p android/context-reconciliation --no-daemon --console=plain test syntheticDemo
# 의존성 준비 뒤 동일 명령에 --offline 추가 가능
```

`test`는 `build/test-results/test`와 `build/reports/tests/test`에 결과를 남긴다. `syntheticDemo`는 **test classpath에만** 있는 수동 실행 프로그램으로, 아래 합성 흐름을 candidate-only 메모리 port에 넣고 `build/synthetic-context-demo.json`을 파일 저장·재열람한다. production singleton/Android service/collector 등록은 없다.

| 합성 입력 | 누적 결과 |
| --- | --- |
| 통화 | 견적서 1판 필요, 10월 5일 약속, 제출 할일, 진행중: 후보 4개 |
| 후속 문자 | 1판 수령, 2판 필요/10월 7일 기한, 제출기한 정정: 후보 5개 |
| 변경 메일 | 2판·할일·약속을 10월 9일로 정정, 사업자등록증 필요, 회신 대기: 후보 6개 |

입력은 `SyntheticFixtures.kt`의 새 수작업 합성 문장/식별자이며 `example.invalid` 주소는 전송하지 않는다. 기존 동결 설계 fixture를 수정하거나 그 30건을 실행한 것으로 재분류하지 않는다.

## 실제 구현 계약

- `EventAdapter.normalize`: owner/channel/namespace/externalId와 version을 보존하고 **정확한 UTF-8 본문** SHA를 계산한다. 수집시각과 발생시각을 분리한다. caller가 제공한 정규화 claim을 받으며 키워드·이름·번호로 사람을 정하지 않는다. 인용 위치는 Unicode code point, 끝은 exclusive다.
- `ContextReconciler`: 원 source별 최고 version을 선택해 후보 전체를 재계산한다. 같은 source/version의 동일 envelope 재전달은 동일 receipt를 돌려주며 observation time만 다른 retry는 중복이다. 본문, claim, 발생시각, binding 등 의미 envelope가 달라지면 같은 hash라도 collision이다. collision 원문은 audit ledger에 남고 해당 head는 격리된다. 다른 channel/id의 같은 bytes는 다른 사건이다.
- 수정 version은 이전 version의 claim을 한 번에 대체한다. tombstone은 해당 source의 파생값을 무효화하고 과거 version이 뒤늦게 와도 복구하지 않는다. 업무 취소는 별도의 `CANCEL` claim이며 명시적 대상 claim이 필요하다. 취소가 DONE을 뜻하지 않는다.
- 발생시각이 늦은 사실이 현재 후보가 된다. 같은 시각의 모순은 해당 field를 보류한다. 정확한 source/version/hash/claim의 `supersedes`는 같은 업무·항목 안에서만 선행 관계가 된다. 미상시각/누락대상/다른 업무/순환은 보류하며, 시간·인과 순서가 모순되어 승자를 고를 수 없어도 보류한다.
- 인용·전달 `origin`은 원 claim의 실제 값/인용과 일치해야 한다. 원본과 사본은 모두 보존하되 근거 origin은 하나로 계산하고, 전달된 날짜로 과거 사실을 최신으로 올리지 않는다.
- `ReviewedLink`는 repository가 제공하는 **후보 연결 metadata**다. source에 결속된 유일한 연결, 현재 관계 revision, 비어 있지 않은 기존 review receipt ref, 현재 허용된 원 인용을 요구한다. 이름·번호·메일 도메인/별칭 일치, 이벤트의 자기 승인 표시는 사용하지 않는다. 사람 근거와 업무 근거는 별도 필드다. 같은 이름·공용번호라도 검토 연결이 없거나 여러 개이면 보류한다.
- `ContextRepositoryAdapter.prepare/commit`: candidate 저장 직전 같은 repository transaction에서 state revision 및 owner/admitted source/binding/lease/user/lock/task/board/relation/archive/item revision/숨김을 전부 다시 비교한다. 중복 재전달도 현재 보호를 재검사한다. 충돌 시 저장 0이며 fresh prepare가 필요하다.
- 기존 DONE 항목은 상태·기한·담당자 전체를 보호한다. 수동 편집·잠금도 항목 전체를 보호하며 다른 항목의 후보만 계산한다. 숨김·보관이면 표시 후보/active sources를 비운다. 철회 후 `prepareRefresh/commit`으로 파생 캐시를 무효화한다.
- `CandidateJson.encode`는 schemaVersion 1의 **로컬 후보 export**이며 새 model wire가 아니다. expected fences, item revision, hold, 원 source/version/hash/claim, 중복 제거한 origin을 포함한다. 본문·연락처 endpoint는 export하지 않는다. 원문 위치와 quote는 보호 ledger의 claim evidence로 역참조한다. `canonicalWriteAllowed`는 항상 false다.

후보는 전체 대체 snapshot이다. 새 snapshot에서 사라진 항목/field는 candidate cache에서도 제거해야 한다. 이를 canonical DB의 삭제 명령이나 기존 수동값에 대한 덮어쓰기로 변환하면 안 된다. 보호된 항목의 기존 사용자 값은 본 모듈이 수정하지 않는다. `ContextState`와 JSON은 저장할 수 있는 데이터일 뿐 승인·실행 증명·암호화를 제공하지 않는다.

## 통합 제안 — 공유 파일 수정은 별도 담당

1. 녹음 담당이 기존 `CurrentAnalysisResult`/`AnalysisBusinessContract.inspect` 및 `RecordingWorkflowStore.businessReview`의 검증된 결과를 읽는 bridge를 추가한다. 일반 STT 추출이 high/dot 의미 검토가 된 것으로 바뀌면 안 된다. 정규화 facts와 필요한 의미 검토를 먼저 준비한다.
2. `localBindingRef`는 원 request/job/result file ID·결과 revision·audio sourceVersion/audioSha·receipt digest를 다시 검증할 수 있는 **기존 immutable receipt 참조**에 대응한다. `SourceRef.sha256`는 본문 SHA이며 audioSha를 대체하지 않는다. 이 모듈의 version은 한 정규화 source의 수정 순서다. 원 recording sourceVersion과 분석 resultRevision은 원 receipt에서 독립 보존해야 하며 서로 같은 값이라고 가정하지 않는다.
3. SMS는 현재 `RelatedReadOnlyStore`의 source/scope revision·provider message ID·content SHA·원시각을, 메일은 현재 `MailIntake`/저장 candidate의 owner/account/mailbox/UID identity·version·선택 scope를 매핑한다. collection cursor/observedAt은 semantic ordering에 사용하지 않는다. 본 모듈은 collector를 호출하지 않는다.
4. repository가 기존 정책·owner/source/version/hash/lease 검사를 통과한 사건만 `RepositoryView.admitted`에 넣는다. `ReviewedLink`는 현재 workflow link와 실제 검토 근거에서 만들며 DTO 자체가 canonical 권한이 되지 않는다. 현재 scope는 owner/person/work/company 단위다. 여러 담당자를 하나의 업무 항목으로 합치는 관계 정책은 기존 repository의 명시적 관계 검토로 결정해야 하며 본 모듈은 사람 병합을 하지 않는다.
5. `ContextRepositoryPort`를 기존 보호 transaction에 구현하고 ledger/collision/immutable 첫 receipt/current candidate를 **함께** 저장한다. DB migration과 암호화·원문 보존/삭제 정책은 이 branch에 없다. 원문을 별도 평문 로그에 남기거나 source 삭제·철회 시 사라져야 할 본문을 audit 이름으로 복제하지 않는다. production port에는 원 source 정책과 동기화된 ledger purge/rebuild가 필요하다.
6. 사용자가 의미를 검토한 항목은 기존 `applyBusinessReview`/`Store.accept`가 요구하는 원 packet·candidate digest·최종 현재성·actor 검사를 거쳐 적용한다. normalized targetId는 후보 키이므로 native task/item ID를 새로 발급하거나 기존 ID라고 가장하지 않는다. 본 adapter에는 canonical 쓰기 API가 없다.
7. 보고 담당은 이 JSON을 확정 보고 입력으로 사용하지 않는다. 기존 ACCEPT/current USER_REVIEW_SUMMARY의 승인 의미를 유지하며 hold·연결필요·시각미상은 coverage로 매핑한다. 승인된 snapshot과 미검토 후보를 별도로 표현한다.

통합 후 필요한 시험은 실제 보호 SQLite transaction의 rollback/CAS·프로세스 재기동·원 source 철회/본문 삭제, 기존 분석→사용자 검토→저장 종단, Android 네 화면/보고 snapshot 일치다. 이 모듈의 메모리 port rollback과 파일 재열람 시험은 그 증거를 대신하지 않는다.

## 이 branch에서 관측한 검증

2026-10-26 Mac, 기존 JDK17/Gradle8.13. 최초 sandbox 실행은 Gradle 로컬 소켓 `Operation not permitted`로 컴파일 전 실패했다. 기존 공개 의존성 캐시를 독립 checkout에 복사한 뒤 offline 시도는 누락된 `error_prone_annotations:2.27.0` 때문에 설정 단계에서 실패했다. 로컬 소켓과 공개 Maven 의존성 다운로드를 허용한 재실행은 **44 tests PASS / failures 0 / errors 0 / skipped 0**이다. 공개 의존성은 이 checkout 캐시에만 준비했으며 SDK·모델·AVD·서명키는 변경하지 않았다.

- reducer 33개: 통화→문자→메일, 6가지 도착순서, 출처 인용, 미상시각, 모호한 연결, owner 분리, 중복/hash 충돌, version 수정, tombstone/업무취소, 누락 인과대상, 순환, 복사 근거 중복, DONE/수동/잠금/숨김·보관/철회, Unicode/변조 검증.
- adapter 11개: 재열람·재전달, 7종 fence의 최종 CAS(최초와 중복 모두), source/owner/item 변경, 동시 prepare 충돌, 모의 transaction 실패 rollback, 철회 refresh, 수동 DONE 후 retry, 입력 변조, mutable 입력 분리, deterministic JSON/escaping.
- 데모: 후보 4→5→6, JSON 저장·재열람과 별도 Python JSON parse/필드값 검증 PASS. 실제 서비스 호출 0.
- `evidence/context-first-build.log` 및 모듈 `build/` 결과는 ignore된 로컬 실행 근거다.
- 의존성 준비 후 최종 `--offline test syntheticDemo --rerun-tasks`도 **44 PASS / failures 0 / errors 0 / skipped 0**으로 종료했다(`evidence/context-final-offline.log`). 두 실행을 88개 고유 시험으로 합산하지 않는다. staged 388파일 export scan 발견0, diff whitespace 검사 PASS, 변경 범위는 이 모듈 11파일뿐이다.

미실행: Android 앱 빌드/기기·S25·실제 STT/high/dot, 실제 보호 SQLite bridge, 실제 SMS/네이버/카카오, 실제 06시 보고·전송, Windows. 기존 설계 fixture 30건과 별개로 새 테스트 44개를 실행했다. M2/M4 제품 종단 완료를 주장하지 않는다.
