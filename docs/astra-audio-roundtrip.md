# Astra xhigh 음성 왕복: 구현과 정확한 지원 경계

기준 `dc0c16b506065a22a12ffaccf3b267aeeacae1fa`, 독립 branch `feature/astra-audio-roundtrip`.
총괄 [RUNTIME_ACCEPTANCE_20261026.md@6869fbb](https://github.com/bllue583-commits/ieum-app/blob/6869fbb1f90c395dadb2c15695da2491899f5f4a/docs/RUNTIME_ACCEPTANCE_20261026.md)의 SIWC·두 단계 계약을 대조했다. 공유 Store/SQL·기존 wire는 수정하지 않았다.
사용자 목표는 **이음 내부 로그인 진입 → 공식 ChatGPT 인증 후 이음 복귀 → 기존 Android 권한/녹음 폴더 선택 → 허용 음성 목록과 실제 bytes 분석 → 앱의 결과 파일 저장/검증/읽기 → 사용자 검토와 기존 업무 저장**이다. 개발 worker를 Astra로 설정하는 것은 이 런타임 기능의 구현이나 실행 증거가 아니다.

## 2026-10-26 실행가능성 판정

공식 문서를 실제 열어 확인했다. 이 표는 계정의 실제 추론 성공을 뜻하지 않는다.

| 질문 | 확인한 사실과 현재 차단점 |
| --- | --- |
| 정확한 모델/effort | [Astra 모델](https://developers.openai.com/api/docs/models/gpt-6-astra)은 `gpt-6-astra`, `xhigh`를 명시한다. 직접 audio modality는 지원하지 않는다. |
| 본인 ChatGPT 사용량으로 추론 | [SIWC Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)는 승인된 OAuth access token으로 `GET https://api.openai.com/v1/models`, `POST https://api.openai.com/v1/responses`를 명시한다. API key만 가능하다는 주장은 맞지 않는다. 계정의 catalog와 해당 요청의 `response.completed`를 각각 확인해야 한다. |
| 이음 안에서 시작하는 로그인 | [Quickstart](https://developers.openai.com/siwc/quickstart)는 open-source 및 **선택된 private client**의 plan usage를 명시한다. [일반 client ID 등록](https://developers.openai.com/siwc/request-client-id)은 선택된 commercial partner 대상이다. [direct 등록](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)은 `dynamic_agent_client`, 고정 host identity, loopback callback과 발급 client ID를 사용한다. 현재 private Android 이음의 적격 등록 방식·승인 client ID·Android callback 검증 증거는 없다. private 앱이 일괄 불가라고 단정하지도, 이음이 이미 허용됐다고 가정하지도 않는다. |
| SIWC로 원음 bytes를 전송해 처리 | [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)는 audio/video input, Files upload API, transcription API를 지원하지 않는다고 명시한다. **직접 음성 분석의 현재 확정 blocker**다. URI나 파일명을 text로 보내도 음성 접근이 생기지 않는다. |
| 이 계정의 dot/로컬 worker를 Android에서 호출 | [공식 app-server](https://learn.chatgpt.com/docs/app-server)는 stdio/WebSocket RPC를 문서화하지만 원격 WebSocket은 실험적이다. 앱에 승인·연결된 원격 listener/인증/권한/음성 도구가 없다. 데스크톱 `create_thread`/`cloud_threads`를 Android API로 노출할 수 있다는 증거도 없다. 새 worker/listener는 만들지 않았다. |
| 결과 파일은 누가 쓰는가 | 검증된 응답을 이음이 `noBackupFilesDir`에 암호화 `.part → .json.enc`로 저장하고 다시 검증해 읽는다. SAF content URI의 실제 `InputStream`도 앱이 연다. 모델이 Android filesystem에 직접 접근하는 구조가 아니다. |
| 비용/권한 | SIWC plan usage는 승인된 계정 사용량·한도에 따른다. 로그인과 사용량 승인은 별개다. 별도 API key/유료 전사·OpenAI API billing으로 자동 대체하지 않았다. 실제 개인 음성·새 grant·등록·약관 수락·결제를 실행하지 않았다. |

사용자는 자신의 계정으로 공개/합성 시험을 승인했다. 그 승인을 기존 데스크톱 토큰/쿠키 추출, 다른 앱 client ID 재사용, 새 OAuth/지속권한 승인, 임의 유료 API 활성화로 확대하지 않았다. 실제 **Astra/SIWC 호출은 0**, 실제 OAuth 등록/교환/로그인도 **0**이다. 이는 자동 approval 거절이 아니라 이음의 등록정보와 인증 adapter가 준비되지 않은 상태다.

## 구체적인 로그인·분석 방향과 남은 결정

1. 이음의 `Continue with ChatGPT` 진입에서 공식 system-browser OAuth를 열고 앱으로 복귀한다. 비밀번호 수집 WebView와 ChatGPT 앱의 로그인/수동 공유는 사용하지 않는다. 등록이 허용된 앱 종류와 callback URI부터 확정한다.
2. 공식 open-source flow가 이음에도 허용된다는 확인을 받는 경우에만 해당 계약을 사용한다. 현재 문서는 `https://auth.openai.com/api/accounts/authorize`, `127.0.0.1` HTTP loopback `/auth/callback`, 요청별 state/nonce/S256 PKCE, 동일 callback의 code exchange를 요구한다. 임의 `callbrief://` redirect를 지원된 것으로 넣지 않는다. 파트너 등록을 쓰면 발급된 Android/public-client 계약을 따른다. 사용자에게 승인할 앱·계정·usage scope를 제시한다.
3. 인증 결과는 서명/issuer/audience/nonce/승인 scope를 검증한 후 계정에 결속해 Keystore 보호 저장한다. 활성 계정은 검증 전 교체하지 않는다. 거부/취소/만료/철회/계정 변경 시 큐를 보류한다. 이 단계의 제품 OAuth 구현은 아직 미연결이다.
4. 계정의 `/v1/models`에 정확한 Astra가 있고 effort를 지원해야 선택을 유지한다. `/v1/responses`는 `store:false`, `stream:true`, `reasoning:{effort:"xhigh"}`와 해당 모델을 사용하며, 완료 이벤트 전에는 성공으로 표시하지 않는다. 일부 delta 뒤 usage-limit/failed/incomplete는 실패/보류다. 별도 model로 자동 대체하지 않는다.
5. **현재 SIWC는 원음 처리가 불가능하다.** 최신 총괄 계약에 따라 Android 로컬 Whisper가 실제 원음을 전사하고, 전사·타임코드·원음 SHA를 SIWC Astra xhigh 분석 입력으로 삼으며, 별도 맥락 job이 승인 snapshot을 대조하는 방향을 구현했다. 이 배치는 Astra가 원음을 직접 듣거나 개인 dot이 자동 수신하는 구조와 다르다. 개인 dot 경로는 OPEN이다. Whisper 단독 출력을 Astra 정리 완료로 기록하지 않는다. 별도 유료 전사 서비스는 도입하지 않았다.
6. 실제 지원 adapter는 응답 ID·actual model/effort·terminal status·byte/전사 근거 hash를 관측하고 아래 결과 계약을 만들어 앱에 전달한다. 서명은 승인된 adapter/앱의 출처 확인이며 OpenAI 자체가 아래 커스텀 JSON에 RSA 서명한다고 가정하지 않는다. 검증된 원시 API 관측 없이 `RUNTIME` 문자열만으로 execution assurance를 올리지 않는다.

## 기존 온보딩과의 관계

`MainActivity`의 선택 폴더 흐름은 이미 `ACTION_OPEN_DOCUMENT_TREE`, read-only persistable URI grant, 계정 epoch 확인, 거부 시 이전 선택 유지, `FolderSelectionChange.replace`를 사용한다. 설정/빈 목록에서 녹음 폴더 선택을 안내한다. `SafSelectedRecordingSource`는 저장된 READ grant, 선택 tree·child membership·제외·권한을 매번 확인한다. 첫 실행의 폴더 선택 결정을 다시 미정으로 돌리지 않는다. 이번 adapter는 그 source를 그대로 받는다.

폴더 READ 권한은 외부 분석 전송 동의가 아니다. `transferApproved(manifestSha)`는 표시된 목록·서비스/계정·분석 목적에 대해 별도 명시 승인된 manifest만 열어야 한다. 이번 구현의 기본값은 false다. 실제 private audio admission은 기존 기기 인수 경계가 열리기 전 계속 차단한다. 개발은 기존 공개 `worker/tests/audio/public-ko.wav`, `public-en.wav`만 쓴다.

## SIWC 경로에 구현된 독립 계층

- `SiwcLocalTranscript`: 기존 `UnclassifiedAudioStore`의 전사·구간·source/version/audio SHA와 관측 runtime/model/raw output SHA를 source pack으로 만든다. 로컬 실제 실행 또는 신뢰된 replay만 허용하며 fixture 전사 증거를 실제로 승격하지 않는다. 이 adapter 자체는 JNI 실행기나 목록 UI를 시작하지 않는다.
- `SiwcAstraProtocol`/`SiwcAstraHttp`: 공식 `/models`와 `/responses`만 사용한다. `gpt-6-astra`/`xhigh`, `store:false`, `stream:true`, input 배열을 구성하고 제한 크기 UTF-8 SSE에서 `response.completed`만 인정한다. 실제 응답 ID/model, stream SHA를 관측한다. 응답에 effort가 없으면 `NOT_REPORTED`이며 요청 effort를 실제 관측값으로 복사하지 않는다. token은 앱이 검증한 scope/expiry/plan usage와 **해당 invocation key 승인**을 갖춘 grant callback에서만 받는다. cookie/redirect/자동 네트워크 재시도는 없다. 기본 grant 공급자·OAuth 화면은 설치하지 않았다.
- `SiwcInferenceJobs`: 별도 AEAD journal에서 분석과 맥락을 다른 key로 실행한다. key는 계정 scope·stage·source manifest·정확한 input/policy·model/effort·attempt를 결속한다. 맥락 입력은 완료된 분석 출력 hash와 승인 snapshot hash를 포함한다. 완료 stage는 재호출0, 불명확한 stream/프로세스 종료는 UNKNOWN, 한도·권한 오류는 HELD다. 자동 fallback/서버 멱등성/결과 재조회 지원을 가정하지 않는다. 명시 재시도는 이전 attempt를 남긴 새 key이며 새 key의 사용량 승인이 필요하다. 취소는 로컬 저장을 막고 원격 중단 미확인을 기록한다. 네트워크 stream 중 취소 감지는 다음 read/check에 적용되며 read timeout은 60초다.
- `SiwcResultFiles`: 두 journal에 COMPLETE로 확정된 분석·맥락만 받아 source manifest·두 출력·native 관측 receipt를 **하나의 불변 암호화 bundle**로 저장한다. 총괄의 4개 개별 파일 제안 대신 네 멤버를 단일 AEAD 파일에 결속해 파일들 사이 partial 상태를 없앴다. scope/stage/source/snapshot/응답 hash를 유지하며 암호화 temp fsync→atomic rename→directory fsync→재읽기/AEAD·내용 대조→별도 보호 pointer commit을 수행한다. DB와 파일이 단일 transaction이라는 주장은 하지 않는다. rename 후 DB 전 orphan은 두 stage journal에 일치할 때만 재연결한다. 손상된 완료 파일은 덮어쓰지 않고, partial만으로 성공하지 않는다. 경로는 `noBackupFilesDir/siwc-results-v1/<scopeSHA>/<bundleKey>.json.enc`다.

`SiwcInferenceJobs.run`과 파일 write/read는 **stage별 schema/원문근거 validator를 필수 인자**로 받는다. 기존 6필드 분석 schema와 맥락 schema를 바꾸지 않는다. 현재 `WorkerV1Boundary`가 synthetic 단일 source에 제한되어 있으므로 실제 provenance를 거짓 synthetic으로 표시하지 않는다. production validator/native mapping은 core·맥락 담당 통합 항목이다. 이 계층의 시험 validator와 SSE port는 합성 fixture이며 실제 Astra 실행 증거가 아니다.

## 별도 bytes transport와 기존 Store 연결

아래 `AstraAudio*` 계층은 처음 요청된 **실제 음성 bytes를 받는 transport**의 독립 계약이다. SIWC의 음성 업로드 API로 사용하지 않는다. 실지원 byte-bearing provider는 미설치다. SIWC 직접 경로의 결과 출처는 위 native HTTPS 관측과 로컬 AEAD이며 아래 원격 producer RSA 서명을 요구하지 않는다.

- `AstraAudioContract`: exact schema·UTF-8·duplicate-key·크기·정수 검사, 배치 source/job/version/hash/revision/handoff 결속, 요구 모델/effort 고정, 완료/receipt 결속, 공개키 pin 기반 `SHA256withRSA`, code-point 원문 근거 검증. 업무/요청/약속, 담당, 기한, 필요서류, 불확실성을 보존한다.
- `AstraAudioJournal`: 기존 `ProtectedDatabase`를 쓰는 별도 AEAD queue. 원 manifest/local source binding은 불변이다. 같은 job의 다른 manifest 재등록을 막는다. 앱 재시작의 DISPATCHING은 UNKNOWN으로 복구해 lookup만 허용한다. 공유 Store/SQL/schema migration은 없다.
- `AstraAudioRoundTrip`: 기존 `SelectedRecordingSource`로 실제 stream을 제한 크기까지 읽고 앞뒤 권한/source/revision을 확인한다. 해시·크기가 맞는 bytes만 transport에 준다. 최대 파일4개, 각10MiB, 결과256KiB다. manifest는 source IDs/hash를 보내며 로컬 URI/파일명은 보내지 않는다. 기본 transport는 정확한 `ASTRA_ANDROID_RUNTIME_ROUTE_UNAVAILABLE`를 반환한다.
- 상태는 PREPARED → DISPATCHING → WAITING_RESULT → PUBLISHING → RESULT_READY다. 대기/재시도에는 WAITING_PROVIDER/OFFLINE/UNKNOWN을 사용한다. backoff는 30초에서 15분으로 증가한다. UNKNOWN을 새 submit으로 재전송하지 않는다. cancel 전이면 CANCELLED, 외부 실행을 시도했으면 CANCEL_REQUESTED이며 확인된 remote cancel 뒤에만 CANCELLED다. 늦은 결과는 읽지 않는다.
- 결과는 app-private no-backup 폴더의 deterministic manifest SHA 파일명이다. 제한된 응답을 메모리에서 서명/계약검사 → 기기 Keystore AEAD 암호화 → ciphertext `.part` fsync → 재복호화 검사 → 보호 journal에 plaintext digest commit → atomic rename `.json.enc` → directory fsync → 다시 읽고 검증 → RESULT_READY로 간다. 평문 temp는 쓰지 않는다. partial 파일/디렉터리 목록만으로 완료를 만들지 않는다. rename 전후 crash는 같은 receipt의 재수신 또는 검증된 완료 파일로 복구한다. 손상·다른job/source·다른 revision·다른 receipt·지원하지 않는 schema/model/effort·unknown signer·symlink는 거절한다.
- `AstraAudioSourceBinding`/`AstraAudioAutomationProvider`: 기존 automation job, native frozen handoff와 lease, current workflow revision을 확인한다. `Coordinator → Store.accept`에 검토후보만 넘긴다. **executionVerified/semanticReviewVerified/automaticUseAllowed는 모두 false**다. 테스트 서명이나 모델 JSON으로 USER_REVIEW receipt를 만들지 않는다. Store가 동일 digest/revision을 한 번만 보관하고 DONE·메모·revision 보호를 유지한다.

구조 스키마: [manifest](contracts/astra-audio-manifest-v1.schema.json), [result](contracts/astra-audio-result-v1.schema.json). 별도 제안 sidecar로서 frozen v0/RO1/SG나 기존 6필드 wire를 바꾸지 않는다. schema 통과만으로 서명·동적 source/lease·원문·실제 execution·사용자 승인이 검증되는 것은 아니다. 서명 대상은 기존 `StrictJson.bytes(payload)`의 키 정렬 UTF-8 bytes다. transcript 인용은 Unicode code point 인덱스이며 날짜/담당은 미확인일 때 null, 불확실성을 별도 기록한다.

## 단일 writer 통합 접점

현재 기본 runtime registry와 UI는 변경하지 않았다. 지원되지 않는 경로를 활성화하거나 앱에 성공 버튼을 만들어 놓지 않는다. 기존 Google/native handoff를 재사용하는 adapter 자체는 테스트에서 연결됐지만, 이것이 SIWC 앱 로그인/직접 음성 전송 경로가 되는 것은 아니다.

SIWC 직접 경로는 `SiwcLocalTranscript.sourcePack` → 승인 manifest로 `Invocation(ANALYSIS)` → `SiwcInferenceJobs.run(분석 validator)` → `context(완료분석, 승인 snapshot, 명시 model/effort)` → `run(맥락 validator)` → `SiwcResultFiles.publish/read`를 연결한다. `current` callback에는 기존 owner·source/권한·policy·snapshot revision·취소 epoch 검사를 전부 결속해야 한다. 통합에서 이를 단순 `true`로 두지 않는다. 파일 결과를 `WorkerV1Boundary`/맥락 native mapping과 기존 DONE/revision 저장 gate에 넘기는 것은 core writer의 통합 영역이다. 두 단계 모델 실행·semantic 검토·사용자 승인 receipt는 별개다.

core writer가 실제 지원 adapter를 수령한 후 적용할 접점:

```kotlin
val binding = AstraAudioSourceBinding(accountContext, store, selectedSource, sessionCurrent)
val queue = AstraAudioRoundTrip(
    accountContext, accountScope, selectedSource, binding::current, approvedPublicHashes,
    transferApproved, approvedTransport, pinnedProducerKeys
)
val provider = AstraAudioAutomationProvider(queue, binding::input)
// Existing AndroidAutomationCoordinator(store, requests, provider).cycle()
// Close queue with the surrounding bounded cycle/lifecycle.
```

복수 선택은 이미 동결된 기존 automation jobs의 `binding.input(job,handoff)`를 모아 `queue.prepare(inputs)`로 등록한다. queue 안의 하나의 job은 하나의 manifest만 가진다. 공유 `AutomationProviderRegistry`를 설치할 때 lifecycle/계정별 queue 종료를 함께 연결해야 한다. 새로운 SIWC 전용 native 요청 경로가 필요하면 기존 source lease/job/receipt 의미를 유지하는 계약을 core writer가 통합한다. private 사용자 목록 UI, SIWC OAuth, production adapter, 일반 의미 후보의 사용자 선택 저장 UI는 이번 branch에서 완료하지 않았다. `reviewOwnSummary`/`createItem`을 모델 결과의 side effect로 호출하지 않는다.

## 검증 증거

합성 transport는 실제 bytes를 받아 크기와 SHA를 대조하고 테스트 RSA key로 명시적 `TEST_FIXTURE` 결과를 만든다. 이는 mock adapter/fixture이며 Astra invocation이 아니다. 실제 Google 인증/Drive도 통합 시험에서는 기존 fake adapter다. 보호 SQLite, 실제 파일 저장/읽기, RSA/JSON validation, 실제 Coordinator/Store.accept는 실행한다.

- 최초 compile: sandbox local socket 차단. 기존 도구로 승인된 로컬 Gradle 실행 후 컴파일했다.
- 초기 집중26개에서 보호 DB 초기 schema 누락 및 strict parser 숫자 타입 사용 오류를 발견했고 수정했다.
- `astra-siwc-final.log`: 앞선 141 PASS/실패0/skip0, `:app:lintDebug` PASS. 이후 bundle/명시 retry/취소 반례를 추가했다.
- `astra-acceptance-final.log`: **151 PASS/실패0/오류0/skip0**, `:app:lintDebug` PASS(Fatal0/Error0/기존 Warning35). AstraAudioRoundTrip32, StoreIntegration2, SIWC protocol10+jobs8+files8, 기존 AndroidAutomation24+AutomationApi3+UnclassifiedAudio24+RecordingWorkflow40이다. 이 실행은 앞선 실행과 합산하지 않는다. ignored 원증거 `evidence/astra-acceptance-final.json`에 suite별 결과를 보관했다.
- 마지막 catalog parser 보완(공식 응답의 관련 없는 소수 메타데이터 허용) 후 `siwc-catalog-final.log`: protocol **11 PASS/실패0/skip0**, lint PASS. 151건과 겹치는 재검증이므로 합산하지 않는다. staged422파일 export scan 발견0, whitespace 검사 PASS.
- 실제 Astra/SIWC invocation/response evidence: NOT_RUN, 0건. 기존 `realPublicHostWhisperBeforeBindingThenExistingStorageAndRestart`는 pin된 **호스트 Whisper 실제 전사 시험**이며 PASS했다. 이를 Astra·Android JNI 실행이라고 보고하지 않는다.
- JNI/APK 빌드는 생략한 host JVM/lint 검증이다. `SiwcLocalTranscript`는 기존 검증 journal을 읽는 adapter이며 새 JNI→SIWC 실종단 검증은 하지 않았다.
- Android 기기/SAF OEM/프로세스 강제종료/S25/UI 로그인: NOT_RUN. host 재생성·protected DB 재열기는 기기 프로세스 사망 시험과 구분한다.
- 외부 파일 업로드·개인자료·새 OAuth/키·유료 결제·새 worker: 0.

재현 명령(준비된 JDK17/SDK36와 offline dependency cache):

```sh
python3 run_gradle.py --offline --log astra-acceptance-final.log \
  :app:testDebugUnitTest --tests 'kr.callbrief.AstraAudio*Test' \
  --tests 'kr.callbrief.Siwc*Test' \
  --tests kr.callbrief.UnclassifiedAudioIntegrationTest \
  --tests kr.callbrief.AndroidAutomationIntegrationTest \
  --tests kr.callbrief.AutomationApiProcessingIntegrationTest \
  --tests kr.callbrief.RecordingWorkflowIntegrationTest :app:lintDebug
python3 tools/scan_export.py
git diff --check
```

현재 정확한 blocker는 다음과 같다. 모두 미실행/미연결 상태이며 서버가 거절했다고 주장하지 않는다.

1. **등록·grant:** private 이음 admission과 발급 client ID가 없다. 새 등록·OAuth grant/지속접근을 하려면 이음 앱/ChatGPT 계정, `resource.invoke`·`chatgpt.tokens.use.direct`·`offline_access`, 공개 전사 입력, 플랜 usage 범위를 제시해 구체 승인을 받아야 한다. 다른 client의 계정 credential을 추출해 대체하지 않는다.
2. **Android 인증 구현:** system-browser/loopback/state/PKCE·OIDC·refresh rotation·revocation·계정 전환 UI/lifecycle 구현과 기기 복귀 시험이 남았다. 공식 지원 Android 전용 callback이라는 증거도 아직 없다.
3. **실제 runtime 인수:** 해당 grant의 account catalog·실제 Astra xhigh completed 응답·별도 맥락 completed 응답이 없다. HTTP 구현 존재와 fixture 성공은 계정별 모델 호출 증거가 아니다.
4. **제품 통합:** core·맥락 담당이 6필드 분석/native provenance·context validator와 결과 candidate mapping, 허용 목록/전송동의 UI, runtime registry·수명, 기존 검토/DONE/revision 저장 gate에 연결해야 한다. 실제 공개 WAV→JNI→SIWC 2회→파일→검토 저장은 NOT_RUN이다.

원음의 실제 접근은 기존 로컬 Whisper가 담당하고 SIWC에는 전사만 전송하는 차이를 유지한다. 개별 dot의 자동 수신·조정은 이 구현으로 완료됐다고 표시하지 않는다.
