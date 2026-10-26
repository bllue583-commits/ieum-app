# 미분류 공개 음성의 분석·결속·저장

기준은 `67fac72ccb8ad63655b281c31dcaaae4680511f9`, 담당 branch는 `feature/unclassified-audio`다. 설계 담당의 `8943536defc15fd0c68f9ef293a4b6b9bd0f3176:docs/MILESTONES.md`를 읽고 M1의 로컬 지원 경로를 구현했다. 일반 개인 음성·외부 dot·high 의미 검토·실제 Google/Drive·SMS/네이버/카카오 연결 완료를 뜻하지 않는다.

## 실제 코드에서 확인한 선행 경계

`RecordingWorkflowStore.prepareAnalysis()`는 recording ID에 연결된 sourceVersion/audioSha와 user/lock/head/visibility/board revision의 준비 영수증을 만든다. 이 단계에는 사람·case/task·발생시각이 없어도 된다. 반면 `AndroidAutomationCoordinator.contextReady`, native 발행, `frozenBusinessContext`, worker 등록, `AndroidAutomationStore.accept`에는 업무/시각 또는 사람까지 필요하다. 해당 조건을 완화하거나 가짜 ID/시각을 발급하지 않았다.

새 로컬 경로는 다음 순서다.

1. 기존 `GeneralLocalStore`/discovery가 허용된 합성 source를 기존 암호화 원음 저장소에 접수한다. 수동 import도 partial/virtual 문서를 원음 open 전에 보류한다.
2. `UnclassifiedAudioStore.prepare(recording, snapshot)`가 기존 준비 요청과 정확한 source ID/version/hash, 원 document metadata, 권한 authority, policy, 30분 만료를 결속한다.
3. `analyse(request)`가 완전한 inventory·read grant·제외·source identity·hash/크기·PCM16 mono 16k WAV를 확인하고 실제 지원 transcriber를 호출한다. 전사 raw bytes와 runtime/model/hash/실행 종류는 동일 보호 DB의 불변 결과로 저장한다. 업무·사람·발생시각은 미상으로 남는다.
4. 호출자가 기존 명시적 `confirmTime`과 `confirmWork`를 사용하고, `confirmBinding(request, currentSnapshot, true)`로 해당 source의 결과와 현재 결속을 확인한다. 공개 데모의 사람/회사/업무/시각은 시험 입력이며 STT에서 추론한 사실이 아니다.
5. `boundRunner()`가 같은 STT 결과를 기존 local-recording packet으로 감싼다. `LocalRecordingAutomationProvider → LocalRecordingResultConsumer → Coordinator → Store.accept`를 그대로 거친다. native request의 owner/source/hash/lease/revision/business binding은 기존 방식으로 다시 고정된다.

공유 `call_core_schema.sql`, frozen v0/RO1/SG 및 외부 6필드 wire, `RecordingWorkflowStore`, `AndroidAutomationStore.accept`는 변경하지 않았다. 추가 journal 4개는 기존 `callbrief-local-v2.db`의 Keystore-sealed persistence 안에만 설치하며 별도 평문 queue를 만들지 않는다.

## 다른 기능의 adapter 계약

| API | 보장·호출 조건 |
| --- | --- |
| `UnclassifiedAudioStore.prepare` | 기존 snapshot CAS. 같은 준비 binding은 같은 request. 다른 source의 동일 hash는 다른 request. `retryOf`는 실패/UNKNOWN/취소된 미완료 부모에 대한 명시적 자식 생성만 허용 |
| `analyse` / `status` / `view` | PREPARED만 실행. 결과/보류/UNKNOWN/취소 재호출은 자동 재실행하지 않음. view는 현재 source/authority/lease를 다시 확인한 뒤 정확 전사에서 추출한 요약·할일 후보를 반환 |
| `confirmBinding` | 사람/case/task/원시각이 명시적으로 결속된 현재 snapshot 필요. 승인 의미는 source 결속이며 모델 의미 검토 권한이 아님. 이미 저장한 binding은 불변 |
| `canRelease` | source ID/version/hash에 단 하나의 유효 BOUND receipt가 있는지 metadata로 확인. 취소·불확실·미결속은 native 요청 준비 전에 보류 |
| `AutomationProvider.gate(key)` | 새 source별 사전 gate. 기본은 기존 `gate()`에 위임하므로 다른 provider는 동작 유지. Coordinator가 준비/발행/수신 전에 호출 |
| `LocalRecordingAutomationProvider.sourceReady` | 선택적 metadata predicate. 미분류 caller는 반드시 `intake::canRelease`를 전달. 기본 기존 caller의 동작은 유지 |
| `LocalRecordingPacket.create` | 기존 JSON 포맷과 관측 객체를 생성하는 내부 코드. 입력 JSON·Intent가 실행 권한을 발급하지 못함. 검증/저장 권한은 기존 consumer/accept에 있음 |
| `UnclassifiedAudioTranscriber` | 신뢰된 앱 코드가 주입. runtime/model pin, 고정 공개 hash allowlist, 실행 종류, 원 raw output을 제공. fixture/imported 종류는 실행 증거로 승격되지 않음 |

`authority`는 재개방에서도 같은 owner/session/authorization epoch/정책 철회 epoch를 구별하는 안정된 불변 fingerprint여야 한다. `current`는 실행 중 그 authority가 여전히 유효한지 검사한다. public pilot은 기존 scope key와 정책 epoch 및 합성 승인 fingerprint를 사용한다. 계정 전환 뒤 새 handle의 `current=true`만으로 과거 결과를 채택하지 않는다.

M2는 `view`의 source/receipt와 검증된 transcript evidence를 읽어 후보를 계산할 수 있다. 이 API는 의미 reconciliation 또는 canonical 적용 권한을 제공하지 않는다. M2의 결과 저장 연결과 M5 snapshot/증분 adapter는 부모가 후속 통합한다. `APPLIED_SAFE_DERIVED`를 검토 완료 보고 입력과 동일하게 취급하지 않는다.

맥락 branch `4b9921d2d64a4ddccceac378226fd51502f212cf`의 `android/context-reconciliation/README.md`도 읽었다. `EventAdapter.normalize`에는 현재 허용 owner와 원 source ID/namespace, `view.transcript`와 `transcriptSha`, 정확 인용/code-point 위치, 별도 원 발생시각을 매핑할 수 있다. `localBindingRef`는 이 준비/실행 영수증 및 후속 native request/result receipt를 역검증할 수 있어야 한다. `sourceVersion`/분석 resultRevision/정규화 event version과 audioSha/본문 SHA는 서로 대체하지 않는다. 현재 workflow의 확인된 사람/업무와 검토 근거는 repository가 별도로 읽어 `ReviewedLink`에 공급해야 한다. 단순 추출에서 의미 claim을 만들어 완료라고 처리하지 않는다. 이 branch는 해당 모듈을 merge하거나 `settings.gradle.kts`에 등록하지 않았다.

## 보호와 재개

미완성/가상 파일·불완전 inventory·동일 document의 충돌·hash/크기 변경은 모델 호출 전에 보류한다. 원음 read마다 grant/제외/현재 source를 재검사한다. 모델 실행 뒤 수동 revision이 바뀌면 전사 결과는 불변 검토 근거로 보존하고 binding은 막는다. 기존 수동 DONE/메모/잠금/보관 보호와 최종 accept transaction은 유지한다.

RUNNING으로 종료된 이전 프로세스는 UNKNOWN이다. 새 handle이나 cycle이 이를 자동 실행하지 않으며 명시 retry는 새 자식 영수증을 만든다. 취소 후 기존 결과는 삭제하지 않고 release를 막는다. 취소가 알려진 미분류 source는 source별 gate에서 native 발행을 시작하지 않는다. 기존 CP08의 최종 admission 이후 강한 AtomicBoolean 취소 한계는 해결했다고 주장하지 않는다.

## 재현과 검증 기록

공개 화면: 초기 설정 → Google 계정 관리 → 공개 음성으로 기기 요약 시험 → **미분류 공개 음성 분석 후 연결**. 별도 `unclassified-v1` sandbox를 사용한다. 기존 공개 음성 시험은 유지한다. 신규 `UnclassifiedWhisperDeviceTest`는 별도 fresh exercise에서 실제 JNI 2회, 분석 시점의 미결속, 기존 이력/할일, 재개방의 추가 실행/ID/이력 0을 검사한다. 기기 시험의 컴파일과 실제 실행 결과는 따로 기록한다.

실행 근거와 최종 수치는 이 branch의 최종 인계에 기록한다. 로컬 원결과는 ignore된 `evidence/unclassified-*.log`, `android/app/build/test-results/`, `android/app/build/reports/unclassified-audio/`에 보존한다. 모델·JNI·APK·원문·키·실사용 DB는 커밋하지 않는다.

- 최초 집중 회귀: 72건 중 68 PASS / 4 FAIL. 새 경로의 수동 revision 충돌 처리, 미결속 오류 우선순위, 취소 기대값, 변경되는 테스트 allowlist를 확인했다.
- 수정 후 동일 집중 회귀: 72 PASS / 실패0 / skip0. 그중 새 미분류 12건과 기존 60건은 같은 실행의 부분집합이며 별도 합산하지 않는다.
- 공개 실제 host Whisper 종단: 한국어/영어 합성 WAV의 모델 호출2 → 미결속 분석 → 명시 결속 → 기존 이력2/할일2. store 재개방 후 추가 STT0/이력0/native ID0. fake GMS/Drive, Robolectric native SQLite 및 테스트 JCE 키를 사용한 host 결과다.
- 최초 전체 회귀: 앱1054건 중 PASS1043/FAIL7/SKIP4, core27 PASS. 7실패는 기존 Code6/7 합성 SAF 공급자의 누락 flags가 새 partial 보호에 걸린 것이다. 해당 공급자가 각 fixture의 partial/virtual flags를 반환하도록 수정했다. 정보 없는 실제 공급자를 허용하도록 제품 검사를 완화하지 않았다. SKIP4는 기존 과거 합성 음성의 외부 입력 미준비다.
- 실제 새 Android AOSP35 ARM64 AVD: `UnclassifiedWhisperDeviceTest` 최초와 force-stop 후 재실행 각각 `OK (1 test)`. PID2270→2406, 실제 JNI STT2→0, 이력2→2, 할일2→2, 원 native binding 동일, 추가 native ID0. 분석 시점에는 사람/case/task/발생시각이 모두 null임을 확인했다. 원 AVD 데이터는 복사하지 않은 빈 합성 시험 AVD이며 종료했다.
- 위 Android debug APK는 40,642,183 bytes/SHA256 `85e4aff0d9315a76260f7a83d92896dab5910e5391683d6aeb04ff3c506ce872`. 설치된 APK를 다시 읽어 일치를 확인했다. 기존 서명과 재생성 JNI/공개 모델이 포함되며 `IEUM_SKIP_ANDROID_NATIVE=1`로 APK의 JNI 기능을 생략한 시험이 아니다. 빌드 직전 별도 native 생성 도구를 실행했다.
- Node 기존29 PASS; Python adapter PASS3/SKIP2; 설계 입력 validator12/18 PASS. 설계 validator는 제품 시나리오30/40의 실행이 아니다.
- 최종 수정 후 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :core:test :app:testDebugUnitTest --continue`: BUILD SUCCESSFUL. 앱1054건/PASS1050/실패0/오류0/SKIP4, core27 PASS. 새 미분류15건은 이 전체 실행의 부분집합이며 전부 PASS다. SKIP4는 과거 외부 합성 음성 입력 미준비를 유지했다. 서로 다른 실행의 시험 수는 합산하지 않는다.
- 최종 lint: Fatal0/Error0/Warning35, 새 미분류 파일의 lint issue0. staged381파일 export scan 발견0 및 diff whitespace 검사 PASS.

물리 S25, 일반 개인 음성, 실제 SAF 공급자 grant/OS 재부팅 예약, 외부 dot 실행권·high 의미 검토·실제 서비스/메시지 발송·Windows 실행은 별도 인수가 남는다.
