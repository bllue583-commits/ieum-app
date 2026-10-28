# 공개 음성 작업·검토 화면의 수명 복구 — 2026-10-28

기준은 `integration/siwc-runtime-20261026`의 `b2144b0f1b8c6801a43c40f59ff80034f4b2f133`이다. 별도 worktree/branch `fix/core-g01-runtime-20261028`에서 작업한다. 원래 checkout의 승인 시험용 미커밋 변경은 그대로 보존하고 가져오지 않았다. main/기존 integration/다른 feature는 변경하지 않는다.

인증 담당 `ee472b3b69325c64cb1016c3b797c32dc58024f2`의 HTTP 대기 취소 보강을 검토한 뒤 `41ca9df`로 cherry-pick했다. 인터페이스·검증 범위는 [인증 인계](SIWC_AUTH_RESUME_20261028.md)와 같다. 그 외 인증 전용 제품 파일을 수정하지 않았다. 개발 모델/effort는 호출 측이 지정한 Sol xhigh이며 이 작업에서 실제 실행 메타데이터를 별도로 조회할 수는 없다.

## 재현과 원인 구분

이전 승인 시험 APK SHA256 `241686c566b3066812eaef1c4e61b7c68a98587072017aa6b3411b34a70c411f`는 보존한다. 이전 기록에는 JNI 시작 또는 첫 분석 완료를 기다리다 `PUBLIC_G01_UI_TIMEOUT`이 발생했고, 최신 재개에서는 분석 중단은 PASS/검토 중단은 첫 분석 완료 전 FAIL이었다. 이때는 단계·Activity 인스턴스·작업 스택을 기록하지 않았으므로 그 모든 실패를 하나의 원인으로 단정하지 않는다.

이번 별도 worktree의 기준 코드 재실행은 검토 중단 2회 PASS였다. 시간 관측 회차는 첫 JNI 시작 6.084초, 두 번째 JNI 시작 13.254초, 분석 완료 17.722초, 후속 결속 25.404초였다. 기존 60초 한도는 늘리지 않았다.

추가로 **실패 당시 원 APK를 바꾸지 않고 진단 driver만 교체**해 반례를 분리했다. 동일 ActivityScenario 안에서 instance `129943091`→`184260170`으로 바뀌고, 시작 뒤 0.713초에 화면은 running=false/단계0/pilot 없음이 됐다. 별도 실행 스택에는 기존 작업이 공개 파일 준비 중이었다. 기존 waiter라면 이 초기 화면을 60초 동안 완료 대기했을 것이다. 이 재현은 단순 JNI 지연과 다르다. Activity가 작업·단계를 직접 소유하고 onDestroy에서 취소하므로 재생성에 의해 새 화면이 기존 작업을 잃는 문제다.

독립 QA의 UX-QA01도 별도 snapshot에서 검토 후보 단계3→0 초기화를 재현했고, 보호 저장 후보2개는 유지됐다. QA의 실제 시험 수/기기를 이번 실행과 합산하지 않는다. 원 timeout/최초 집중/최종 실행의 로그·APK·JSON은 각각 보존한다.

## 수정과 보호 경계

- `AndroidLocalSttModel`과 `SiwcPublicResultModel`이 작업·단계·현재 표시 내용·후보 및 `PublicAudioOperation`을 소유한다. application context만 사용하며 Activity/View를 보관하지 않는다. I/O는 ViewModel 수명에 속한다. 화면 재생성은 작업을 취소하거나 새 전사/fixture job을 시작하지 않는다.
- 새 화면도 같은 작업의 중단 대상을 사용한다. 명시 중단/닫기·최종 owner 종료는 기존 취소 경계를 사용한다. 중단 뒤 늦은 완료로 단계·사용자 검토를 진행하지 않으며 이미 commit한 이력을 지우지 않는다. 기존 최종 admission 이후 강한 취소 전체의 인수를 주장하지 않는다.
- 후보 단계3과 명시 검토 완료 단계4는 회전 후 그대로 보존한다. 회전/생성/resume가 사용자 승인을 만들지 않는다. 실제 새 사용자 검토는 기존 native current/source/hash/revision·manual DONE/메모·digest/receipt gate를 다시 거친다. frozen SQL·wire·권한·모델·SDK·서명 identity는 변경하지 않는다.
- 유지 상태는 메모리의 구성 변경 수명이다. 전사·후보 원문을 SavedState bundle/plaintext 파일에 새로 저장하지 않는다. 프로세스 종료 후 자동 실행/단계 복원을 추가하지 않는다. 보호 journal·결과파일·원 검토 receipt의 기존 명시 재열람을 사용하고 추가 JNI/응답/항목 생성을 기기 회귀로 확인한다.
- G01 waiter는 단조 시계를 사용하고 인스턴스·단계·JNI 호출별 시간을 기록한다. 종료/보류 상태는 일반 timeout과 구분하고, 실패 시 공개 전용 요청 상태·스택을 ignore된 증거에 기록한다. SQL 진단은 UI thread에서 수행하지 않는다. JNI 호출 관측 counter는 스레드 간 가시성을 갖는다.

## 검증

수명 수정 전 중간 고정 코드: 앱1228 중 PASS1224/SKIP4/실패0/오류0, core27·맥락44 PASS, lint Fatal0/Error0/Warning35, debug/validation/AndroidTest 빌드 성공(7분56초). 그 APK의 고유6개 device method/재열람 포함8회도 모두 PASS였다. 이 결과를 아래 최종 수명 수정 코드의 증거로 대신 사용하지 않는다.

새 집중 수명·취소·로그인 비활성 host 회귀는 **8 PASS/실패0/오류0/SKIP0**, lint0오류/35경고다. UI state fixture 시험이며 실제 JNI/서버 호출 시험으로 세지 않는다.

**최종 고정 코드 전체:** `core-lifecycle-final-full-1.log` BUILD SUCCESSFUL(6분7초). 앱1231 중 **PASS1227/실패0/오류0/SKIP4**, core27 PASS, 맥락44 PASS. SKIP4는 기존 T81~T84 외부 과거 합성 입력 미준비다. P3302/P3303는 실행됐으며 Android JNI/실제 Astra 시험으로 세지 않는다. lint Fatal0/Error0/Warning35. debug/validation/AndroidTest 빌드 성공. 새 수명 host3건은 이 앱 전체의 부분집합이다.

**최종 AOSP35 ARM64:** 고유7개 device method, force-stop 재열람 포함9회 instrumentation 모두 `OK (1 test)`다. 첫 회차나 독립 QA와 합산하지 않는다.

- 실제 JNI 실행 중 Activity를 명시 재생성한 새 G01 시험: 동일 ViewModel·pilot을 유지하고 새 화면의 중단 버튼으로 activeIntegrationCancelled=true, 신규 전사/사용자 검토/native 결과0. 기존 분석 중단·내용 검토 중단도 각각 PASS이며 중단 뒤 단계0/2를 보존한다.
- 실제 공개 JNI2→fixture 응답4→보호 파일 수신→후보2. 단계3에서 재생성해 같은 모델/pilot·후보/digest·표시 원문을 유지하고 사용자 검토/요약0·항목 증가0을 확인했다. 새 명시 버튼 후 검토2·항목2 추가, 단계4의 재생성도 같은 완료 상태/항목 수·비활성 버튼을 유지했다. force-stop 재열람은 JNI0/fixture0, 현재 원 사용자 receipt 재열람이다. 실제 서버 응답으로 보고하지 않는다.
- 기존 정상 보고도 JNI2→재기동0, 사용자 검토2, 동일 durable cursor/outbox 두 건·DRY_RUN_RECORDED·외부 발송0으로 PASS. COMPLETE는 보호 commit이 끝난 로컬 검토 저장소의 체크포인트이며 외부 전체 수집이 아니다.
- 로그인 기본 비활성, 합성 OAuth socket callback·rotation·실제 Activity 복귀도 PASS. 외부 OAuth 서버·브라우저 adapter는 모의이며 실제 grant/token은 0회다.
- 최종 회귀용 read-only AVD5586은 종료했다. GUI5584는 이전의 별도 로그인 시험 기기이며 이번 회귀에서 설치/로그인/승인/데이터 변경을 하지 않았다. 그 현재 계정 상태를 새로 읽거나 성공으로 추정하지 않았다. 다른 작업 기기는 건드리지 않았다.

Node29 PASS, Python5 중 PASS3/SKIP2(기존 외부 관측 fixture 부재), 맥락/보고 설계 입력12/18 PASS다. 설계 제품 시나리오30/40의 실행으로 세지 않는다. 최초 일반 shell에는 node가 없어 시험 미실행이었고 기존 명시 Node 경로로 실행했다. SDK/모델/인증/보안 설정의 신규 설치·변경은 없다.

| 최종 산출물(로컬 ignore) | bytes | SHA256 |
| --- | ---: | --- |
| `deliverables/ieum-code34-core-lifecycle-debug.apk` | 42099415 | `ae7336246660275a50ad80445fa756ea034fe019fda98ae473877b1d8148a82d` |
| `deliverables/ieum-code34-core-lifecycle-validation.apk` | 38629474 | `1b3a8c9041eeab25ed6cdc71663f5f88e8c1e5dff6bdc9995832a3c424174057` |
| `deliverables/ieum-code34-core-lifecycle-driver.apk` | 423784 | `98eb710ba79e14e19a729ebd121fc5e8db402ef10252989998506075316e5ace` |

두 ABI JNI·model SHA를 APK 내부 pin과 대조했고 실제 실행은 ARM64 debug만 했다. 최종 설치 입력은 위 debug SHA와 일치한다. 원 XML/lint/JSON은 `evidence/core-lifecycle-final-{xml,lint.xml,results.json}`, 기기는 `evidence/core-final-device-2/`에 별도 보존한다. 이전 `core-final-*`·원래 checkout의 `siwc-*`·원 APK 대조 실패를 덮어쓰지 않는다. 독립 QA의 구형 reflection probe는 이제 Activity 필드 대신 internal testState의 보존 모델을 읽도록 재빌드해야 한다.

## 통합과 남은 큰 기능

[큰 기능 인수 기준](SERVICE_ACCEPTANCE_20261028.md) 및 [등록 판정](SIWC_REGISTRATION_DECISION.md)은 문서 branch `89e11d9f75e67bfdf8c1b61a6465310f4d116587`의 원문을 보존적으로 포함했다. 그 문서의 시간 고정 판정은 이번 후속 결과와 구분한다.

원래 미커밋 승인 시험에는 `SiwcAstraHttp(..., observe=...)`의 안전한 HTTP 관측 hook이 있다. 이번 인증 commit은 선택적 `Call.Factory`와 blocked-call 취소를 추가한다. **원래 dirty checkout에 무충돌로 적용된다는 뜻이 아니다.** 두 기능과 1+1 durable 예산·UNKNOWN/관측 보존을 한 writer가 조정해야 한다. 원래 파일을 reset/stash/덮어쓰기하지 않았다. 이 branch는 원격 b2144b0 기준의 비활성 auth config를 유지한다.

현재 앱 core/파일/보호 Store의 계약은 `SiwcInferencePort.models(scope)` 및 `invoke(Invocation, stopped)`→`Completion`, session runtimeScope/epoch/current, exact invocation 승인, 파일/journal 재읽기와 native REVIEW_REQUIRED 후보→별도 사용자 승인이다. 인증 담당의 transport/session, core의 initiator/source/job/파일/native review 소유권을 유지한다.

이번 core 실행의 실제 외부 OAuth/token/catalog/Astra 요청은 0회다. 공개 WAV의 JNI는 실제이고 모델 응답/로그인 서버는 fixture다. 승인된 실제 계정 분석1+맥락1은 성공하지 않았고 일반 앱의 live initiator 연결도 미완료다. 원래 GUI 시험 AVD `ieum_siwc_live35_arm64`/5584의 로그인·승인은 이번 턴에 조작하지 않았다. 회귀는 화면 없는 공개 전용 read-only AVD `ieum_unclassified35_arm64`/5586에서 수행한다. 사용자 확인 전 새 로그인/승인은 진행하지 않는다.

다음 우선순위는 실제 M1 계정·catalog·별도 두 응답·파일 읽기·후보 검토와 일반 앱 실행 동선이다. M2 기존 대상 누적 수정, SIWC 승인 receipt→보고 snapshot, 일반 포맷/긴 녹음, 문자·네이버 및 S25 실제 운영은 여전히 별도 OPEN이다. 실제 서버 처리/사용량 취소, 물리 S25·x86/validation runtime·실제06시·카카오 수신을 이번 결과로 인수하지 않는다.
