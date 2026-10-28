# SIWC 앱 연결·결과파일 검토 통합

아래는 b2144b0 차수의 기록이다. 이후 사용자 승인에 따른 native 활성화·횟수 제한·첫 실제 진입과 추가 브라우저 약관에서의 중단은 [승인 시험 기록](SIWC_APPROVED_TRIAL_20261026.md)에 별도로 보존한다.

기준은 `c55b0402d8ddd17d370f6f2a28bc5ff13fccda55`, 작업 branch는 `integration/siwc-runtime-20261026`이다. 원격 feature `5f53fe6ff9bb6cd9b643a593bbc581670d13cfec`를 no-ff merge했다. 기존 integration·feature·main의 원기록과 G01/G02 수정을 보존한다. [인증 모듈](siwc-auth-module.md), [왕복 모듈](astra-audio-roundtrip.md), [기존 인수 계약](RUNTIME_ACCEPTANCE_20261026.md)의 실행/승인 경계를 따른다.

## 앱 경로

계정과 연결 → **ChatGPT 연결 · 동의 대기** → `SiwcRuntimeActivity`다. `Continue with ChatGPT`는 이번 build에서 비활성이다. `SiwcLoginRuntime.dependencies()`의 승인 config가 null이며 Intent·preference·원격 flag로 변경할 수 없다. null에서는 discovery·브라우저·loopback을 시작하지 않는다. 기존 Codex/다른 앱 인증을 읽거나 사용하지 않는다.

`SiwcLoginModel`은 ViewModel로 로그인 owner를 유지한다. 화면 회전·브라우저 전환의 onStop에서 로그인 handle을 없애지 않는다. 실제 resume 때 vault 상태를 다시 읽으며 자동 로그인·refresh·추론은 없다. 명시 취소/닫기/owner 종료는 해당 handle과 listener를 닫는다. callback/복귀 Intent에 token을 담지 않는다. `returnRequested`와 실제 Activity resume를 구분한다. 저장된 검증 등록·발급 후 확인 대기 draft는 선택할 수 있고, 재로그인은 선택한 issued client와 일치하는 명시 native config만 받는다. 선택 자체가 활성 계정 교체가 되지 않는다. 새 identity 검증 전 기존 계정은 유지한다.

기기 안 공개 음성 시험 → **공개 전사·합성 결과파일 검토 시험** → `SiwcPublicResultActivity`다. 단계는 실제 bundled WAV JNI 전사 → 표시한 합성 사람/업무/시각의 명시 결속 → 두 합성 응답의 암호화 파일 저장·재읽기와 후보 확인 → 표시한 요약/할일의 명시 검토 저장이다. **두 응답은 fixture이고 Astra/SIWC 모델 실행 증거가 아니다.** 이 화면은 synthetic-tools build에만 열리며 두 공개 WAV SHA만 허용한다. 기존 SAF 폴더 선택과 Google 연결은 그대로 보존한다.

## native·검증 접점

- `SiwcNativeContract`: checked `SiwcLocalTranscript.sourcePack`과 현재 native snapshot을 결속한다. 모델이 반환할 분석은 기존 **schema_version, job_id, source_pack_sha256, base_revision, summary, claims** 6필드 그대로다. strict UTF-8 JSON/duplicate key·정수·길이·Unicode code-point 인용·source/version/audio SHA·job/revision·주체/날짜 근거를 검증한다. 실제 전사를 `WorkerV1Boundary`의 거짓 synthetic provenance로 바꾸지 않는다. source pack은 `android_local_transcript` sidecar이고 frozen wire/parser는 변경하지 않는다.
- 맥락은 앱 sidecar `ieum.siwc-context.v1`이다([schema](contracts/siwc-context-v1.schema.json)). 완료 analysis key/output SHA와 정확한 승인 snapshot SHA, claim 전체 대응, 기존 task/document의 ID/kind/revision, DONE/manual 충돌, 불확실성·취소 대상을 검사한다. source/event DTO와 기존 `ContextReconciler`의 후보·보류로 변환한다. 수정/취소/기존 대상·담당 지정 등 현재 생성 전용 검토로 적용할 수 없는 내용은 원문과 함께 보존하고 별도 명시 편집 흐름을 요구한다. 후보 JSON이나 model 승인 flag를 사용자 승인으로 사용하지 않는다.
- `SiwcNativeRoundTrip`: 분석은 `gpt-6-astra`/`xhigh`; 맥락도 명시 `gpt-6-astra`/`xhigh`다. 서로 다른 stage key와 입력 digest를 사용한다. `SiwcApprovedInference`는 `SiwcSessionStore.runtimeScope(ownerScope)`·epoch·현재 source/policy/revision·취소를 결속하고, **각 exact invocation key의 전송/사용량 확인** 뒤에만 해당 key를 `inferenceGrant`에 준다. context 준비는 실행이 아니다. 실제 앱의 live 추론 버튼은 아직 활성화하지 않았다.
- `SiwcNativeReviewStore.receive`: `SiwcResultFiles.read`를 직접 호출한다. 실제 committed 두 journal·AEAD bundle·원 manifest·동일 분석 input/policy와 context snapshot을 다시 확인하고 **기존 `AndroidAutomationStore.accept`**에 `REVIEW_REQUIRED` 제안만 넣는다. candidate ledger와 native result는 기존 보호 primary DB의 같은 transaction에 저장한다. 결과파일 pointer와 primary DB가 하나의 transaction이라는 주장은 하지 않는다. file commit 후 primary DB rollback은 같은 immutable file의 재읽기로 재시도하며 모델을 다시 호출하지 않는다.
- `approve/approveSelection`: 표시한 exact candidate digest·현재 원 snapshot·source/hash/revision/policy·native result head를 확인하고, 별도 사용자 동작으로 기존 `RecordingWorkflowStore.createItem/reviewOwnSummary`를 실행한다. 새 TASK/DOCUMENT의 `needs_confirmation` 생성만 지원한다. 기존 DONE/수동 메모/잠금/기존 사용자 요약·superseded result는 보존한다. selection은 모든 snapshot을 같은 transaction에서 먼저 검사하고 자기 쓰기로 생긴 board 증가만 허용한다. 중복 수신/검토는 원 영수증을 재사용하며 기존 history를 바꾸지 않는다.

공유 frozen SQL·6필드 wire·권한·모델·SDK·서명 identity·의존성 버전은 변경하지 않았다. app-local immutable sidecar table만 기존 보호 DB에 추가한다. 보고가 현재 검토 source로 SIWC receipt를 소비하는 확장은 이번 작업에 포함하지 않는다. 기존 로컬 `PARTIAL`/dry-run 보고와 G01/G02 회귀를 유지한다.

## 실제 인증 직전 필요한 조작

1. 부모가 [최초 승인안](siwc-auth-module.md#부모가-사용자에게-제시할-최초-grant합성-시험-승인안)을 사용자에게 제시하고 새 dynamic 등록·identity/plan-usage/offline_access grant 및 공개 전사/합성 snapshot의 두 호출 승인을 받는다. 현재 상태는 **승인 대기**, 거절/서버 실패가 아니다.
2. 승인 근거에 맞는 native config 공급 지점을 연결한다. 최초만 dynamic 승인 config, 이후 선택된 실제 issued ID를 재사용한다. null 기본값을 원격 flag/Intent로 켜지 않는다. 해당 공식 브라우저 동의 화면·실제 scopes를 사용자가 확인한다. `SiwcAndroidBrowser`의 실제 외부 브라우저 동작과 OEM foreground 복귀는 그때 추가 인수한다.
3. 실제 grant 검증 뒤 해당 계정 catalog와 정확한 분석 key/input/model/effort를 표시해 승인된 첫 호출을 실행한다. 완료 분석을 보고 별도 context key/snapshot/model/effort를 확인한 후 두 번째 호출을 실행한다. UNKNOWN/한도 오류는 자동 재요청·유료 fallback하지 않는다. `earliest_refresh_at`이 있으면 공식 encoding 확인 전 refresh 보류를 유지한다.
4. 검증된 파일을 다시 읽어 후보를 보여주고 명시 사용자 검토를 받는다. 모델 완료·파일 완료·native 후보 저장·사용자 canonical 저장은 각기 다른 근거다. 원음은 로컬 Whisper에서만 읽으며 SIWC에는 승인된 전사만 보낸다.

이 작업에서 실제 외부 grant·Astra 호출·개인 통화/SMS/메일/연락처·결제·새 worker는 모두 0회다. 물리 S25, 실제 계정 admission/catalog/추론, 실제 외부 브라우저·OEM 복귀는 미실행이다. 직접 원음 SIWC 업로드 경로를 만들지 않았다.

## 실행 근거

초기 native 집중17건은 PASS14/FAIL3이었다. 기존 로컬 추출의 선행 할일과 정책에 포함되지 않는 test key를 잘못 가정한 fixture를 수정하고, 원 XML/log를 `evidence/siwc-native-focused-1*`에 보존했다. 같은 제품 코드의 두 번째 native 집중17건은 PASS17/실패0이다. null 승인 UI와 기존 G01/G02 집중9건은 PASS9다. 이후 수정 제안·superseded native 결과 반례를 추가했다.

선행 SIWC 전체+G01/G02 집중은 91 PASS/실패0/SKIP0(`siwc-pre-device-final.log`)다. 이 수치는 기존 모듈65, 새 시험, 앞선 회차와 겹치므로 합산하지 않는다. 후속 최종 전체·가상기기 결과와 APK SHA는 실제 완료 후 아래에 별도로 기록한다.

최종 결과 재열람 보강 전 전체도 앱1222/실패0/SKIP4였고, 원 XML은 `evidence/siwc-full-1-xml/`에 별도 보존했다. 이후 사용자 검토가 만든 board 증가 때문에 완료 후보를 다시 적용하려 하지 않도록 원 영수증 읽기를 분리했다. 현재 source·정책·native result head·최신 사용자 요약 receipt를 검증하고 재시도/재시작에서도 새 할일이나 요약을 생성하지 않는다. 새 사용자 명령은 여전히 원 snapshot과 exact digest가 필요하다. 이벤트 observedAt은 native 파일 수신 시각이며 모델이 시각을 정하지 않는다.

**고정 최종 코드의 전체 재실행:** `siwc-integration-full-final.log` BUILD SUCCESSFUL(6분11초). 앱 **1222 중 PASS1218/실패0/오류0/SKIP4**, core27 PASS, 맥락44 PASS. SKIP4는 기존 T81~T84 외부 과거 합성 WAV 미준비다. P3302/P3303의 기존 host pin 시험은 실행됐으며 실제 Astra/Android JNI 시험으로 세지 않는다. lint Fatal0/Error0/Warning35, debug/validation/AndroidTest APK 빌드 성공. 새 host21건(구조10·native 보호9·UI2)은 앱 전체의 부분집합이다.

**AOSP35 ARM64 debug:** 최종 APK에서 6개 고유 test method, 총8회 instrumentation이 각각 `OK (1 test)`다. SIWC 기본 비활성, 합성 OAuth socket callback/화면회전/실제 PendingIntent 복귀·Activity resume, 공개 결과파일 UI, G01 분석 중단/내용검토 중단, 기존 정상 보고를 실행했다. 공개 결과파일과 정상 보고는 force-stop 이후 별도 process에서 한 번씩 추가 실행했다. 이 횟수를 JVM 합격 수나 고유 테스트 수에 합산하지 않는다.

- 공개 파일 UI: 실제 JNI2, 합성 응답4(분석2+맥락2), 후보2, 사용자 검토 전 신규 canonical 쓰기0. 명시 버튼 이후 사용자 검토2·할일2 추가(기존2→4). PID1769→2418 재열람은 JNI0/합성 응답0, 동일 candidate ID/digest와 원 사용자 receipt를 재사용했다.
- 로그인 fixture: 실제 `127.0.0.1` socket/PKCE·state·RSA/OIDC/보호 vault를 사용했다. 외부 브라우저 open·token server는 **모의 adapter**이며 실제 OpenAI grant가 아니다. 회전 후 같은 ViewModel, fixture exchange1, return request1, 실제 Activity RESUMED를 확인했다. default 앱은 loginEnabled=false다.
- G01 두 실제 중단 버튼은 활성 integration 취소=true와 해당 새 결과/검토 저장0으로 통과했다. 기존 정상 보고는 PID1762→2363, JNI2→0, 같은 outbox/cursor·외부 발송0으로 통과했다. G02 전체 회귀도 통과했다. G03/CP08 전체 해결을 주장하지 않는다.
- 모든 자기 시험 AVD와 전용 adb 서버는 종료했다. 물리 S25, validation/x86 runtime, 실제 system-browser 승인·OEM 복귀/로그인 중 process death·실계정 catalog/추론은 미실행이다.

기타 영향 검사: 기존 Node29 PASS, Python5 중 PASS3/SKIP2(과거 관측 입력 미준비), 맥락/보고 설계 validator12/18 PASS. 설계 제품 인수30/40으로 세지 않는다. staged449파일 export scan 발견0, whitespace PASS.

최종 APK는 자기 clone에 준비된 실제 pin JNI/model을 재사용했고 두 ABI JNI와 model SHA를 APK 안에서 다시 확인했다. 기기 실행은 ARM64 debug만 실행했다.

| 산출물(ignore된 안정 복사본) | bytes | SHA256 |
| --- | ---: | --- |
| `deliverables/ieum-code34-siwc-runtime-debug.apk` | 41810754 | `c634e2b5893399defbb1018eddd2b7a24bf418d229c4c1d5e499aaff9aea5e9b` |
| `deliverables/ieum-code34-siwc-runtime-validation.apk` | 38624734 | `82ea2d49385586c9b02132ca8ba935d48ed2726a42f7a4c695c215e7b0c13d52` |

원결과: `evidence/siwc-final-results.json`, `siwc-final-xml/{app,core,context}/`, `siwc-final-lint.xml`, `siwc-device-final-summary.json`, `siwc-device-final/`의 log/public JSON이다. 최종 기기 설치 입력은 모두 위 debug SHA와 일치한다. 이전 `integration-*`, `g01-*`, `siwc-device-1/` 원기록을 보존했으며 현재 build/test-results와 혼합하지 않는다.

남은 외부 실행 blocker는 새 grant 승인 대기다. 자동 approval 거절이나 서버 거부가 아니며 실제 등록·grant·catalog·Astra 모델 호출을 실행하지 않았다. 앱의 명시 승인 config 활성화와 실제 per-invocation 확인 화면의 live 활성화는 위 승인 절차 뒤 진행한다. 이번 범위의 새 결과파일 후보/사용자 보호저장은 합성 종단으로 검증됐고, SIWC 사용자 영수증의 보고 snapshot adapter와 기존 대상 수정/취소의 명시 편집 연결은 별도 후속 범위다.
