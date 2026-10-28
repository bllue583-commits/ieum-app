# 큰 기능별 서비스 인수와 다음 실행 순서 — 2026-10-28

**판정: 로컬 전사·보호 저장·결과파일 검토 기반은 있고, 실제 ChatGPT 계정의 Astra 분석 왕복과 누적 업무 서비스는 미완료다.** M1 실제 왕복부터 닫고 M2 누적 갱신, M3 일반 녹음 자동 처리, M4 문자·네이버 종합, M5 아침보고, M6 S25 운영 인수로 진행한다. 작은 문구·화면 정리와 불필요한 리팩터링은 선행 작업이 아니다. 코드량·시험 수를 진행률로 환산하거나 완료 날짜를 추정하지 않는다.

검토 기준은 2026-10-28 11:48 UTC의 원격 참조와 그 직후 읽은 개발 중 checkout이다. 문서 담당은 자기 `docs/milestones-git`에서만 작업하며 제품·다른 담당 checkout·원격 설계 Page를 수정하지 않는다. 실행 중인 후속 작업은 새 commit과 원증거로 갱신해야 한다.

## Git·설계와 증거 기준

| 기준 | 직접 확인한 SHA / 상태 | 의미 |
| --- | --- | --- |
| `main` | `67fac72ccb8ad63655b281c31dcaaae4680511f9` | 새 기능 미통합. 기능 branch 성공을 main의 상태로 보고하지 않음 |
| `integration/siwc-runtime-20261026` | `b2144b0f1b8c6801a43c40f59ff80034f4b2f133` | 원격 코드 인수 기준. 로그인 기본 비활성, 공개 결과파일 UI의 모델 응답은 fixture |
| `integration/major-features-20261026` | `c55b0402d8ddd17d370f6f2a28bc5ff13fccda55` | 로컬 통합·G01/G02 수정. SIWC 통합의 선행 이력 |
| `feature/astra-audio-roundtrip` | `5f53fe6ff9bb6cd9b643a593bbc581670d13cfec` | 인증·두 job·결과파일 모듈, SIWC 통합에 포함 |
| 초기 녹음 / 맥락 / 보고 | `1ac76e3` / `4b9921d` / `43b0360` | 통합 선행 기능. 시험을 합산하지 않음 |
| 문서 검토 시작점 | `69531e0c4d62d69f6ce0d08f0644c2c1e58eaee3` | 개인 로컬 SIWC 등록 판정까지 반영 |
| core 로컬 미커밋 작업 | `b2144b0` 위 승인 시험 build·1+1 예산·실진입 driver·관측 보존 | 원격 구현으로 세지 않음. 다른 코드/기기 증거 집합 |
| 인증 담당 후속 원격 | `feature/siwc-auth-resume-20261028` / `ee472b3b69325c64cb1016c3b797c32dc58024f2` | 11:58 UTC 재조회에서 push 확인. HTTP 대기 취소 보강·host71 PASS, SIWC 통합에는 아직 미반영 |

[원 설계 Page](https://chatgpt.com/space/page_257b0a73cf808191a03c02362edc2673)의 sequence54와 핵심 블록 3개를 다시 읽었다. `904c78d4-6434-47a1-973f-997e5b9522ef`는 앱 수집→dot·worker 분석→문자·네이버 종합→앱 반환, `424f7ddf-9a1d-4479-8d6b-ae01710f7818`는 로컬 Whisper를 보조로 명시한다. 원래 목표는 이미 있었고 실연결은 미완료였다. 이후 사용자 지시인 **이음 내 ChatGPT 로그인→전사→Astra xhigh 분석→별도 맥락→결과파일**은 Git 계약에 구체화됐지만 Page 자체는 여전히 sequence54다. Page까지 최신화됐다고 보고하지 않는다.

사용자 지정은 개발 담당 Sol high/xhigh, 설계·진행 점검 Astra xhigh다. 이 감사 세션의 실제 모델/effort 메타데이터는 도구에서 확인되지 않는다. 제품 runtime의 요청 모델과 실제 응답 모델도 따로 기록한다.

## 큰 기능 여섯 개의 현재 위치

| 기능 / 우선순위 | 구현·모의 시험·기기 근거 | 실제 서비스 미완료 | 다음 완료 게이트·의존성 |
| --- | --- | --- | --- |
| **M1 실제 계정 분석 왕복 — 최우선** | 공식 인증 모듈·로그인 UI, 실제 JNI 전사, 두 job·JSON 검증, AEAD 파일, native 후보·명시 검토 저장. b2144b0 AOSP35 ARM64에서 JNI2+fixture4+검토2·재열람 | 마지막 보존 실진입은 discovery200 뒤 Firefox 초기 화면에서 종료. token 교환·catalog·실제 분석/맥락0. 일반 앱 live 분석 실행 동선도 미완료 | 실제 계정→catalog→분석1→맥락1→파일 읽기→후보→사용자 검토→재기동. 시험 driver와 일반 앱 동선을 각각 인수 |
| **M2 사람·거래처·업무별 누적 갱신** | 맥락 모듈44 PASS, source/revision/인용·수동 보호 회귀. 새 TASK/DOCUMENT 검토 생성만 연결 | 여러 기록의 업무 snapshot, 기존 기한 정정·취소·부분 이행·서류 버전 적용 미완료. 실제 Astra 의미 품질 미검증 | M1 위에 기존 item ID·이력·delta를 연결. 연속 합성 사건의 정정/중복/늦은 과거/수동DONE 보존 |
| **M3 일반 녹음·긴 통화 자동 접수/복구** | 기존 SAF 폴더 선택·persisted READ, discovery/lease/취소. G01/G02 수정·회귀 | 새 intake는 허용 공개 WAV·합성 scope 제한. 일반 파일→SIWC 생산 연결, AAC/M4A·긴 통화 분할/재개·실기기 백그라운드 미완료. 최신 미커밋 취소 회귀 잔여 실패 | 기존 온보딩 유지. 공개·합성 파일 목록/hash→decoder→chunk/checkpoint→M1. 철회·중복·process death·망 중단 시 재전송/늦은 저장 방지 |
| **M4 SMS·네이버 근거 종합** | 기존 adapter·정규화/권한·합성 계약 기반. 이번 실제 계정 수집0 | 허용 메시지/메일 실제 수집→근거→M2 갱신 미연결. 네이버는 필수 종합 채널 | M2 사건/업무 identity 후 read-only cursor·철회·중복 인용 보호. 채널 실패를 종합 완료로 표시하지 않음 |
| **M5 전날+최근7일 06시 보고·배송** | 주입 시계의 일정·로컬 승인 snapshot/checkpoint·PARTIAL 보고·dry-run outbox, 재기동 동일 cursor/outbox | SIWC 검토 receipt→보고 adapter, 외부 coverage, 실제 OS06시/휴면·재부팅·실수신 미인수. 카카오 DISABLED | M1/M2 승인 결과→보고 직접 회귀 후 M3/M4 coverage. KST 운영·늦은 결과·UNKNOWN 배송, 생성/시도/ACK/도착 구분. 카카오는 선택 |
| **M6 S25 운영·인계** | Mac Git, JVM·AOSP35 ARM64와 고정 APK | 물리 S25의 권한/포맷/발열·긴 처리/휴면·복귀·실제06시 미실행. Windows 실빌드 별도 | M1부터 공개·합성 실기기 인수 병행. 동일 통합 commit/APK의 실행·복구·재열람 근거, 기존 설치/키/자료 보존 |

## M1의 정확한 경로와 끊긴 연결

| 단계 | 실제 코드·공식 경로 | 검증 범위 / 남은 연결 |
| --- | --- | --- |
| 앱 로그인 시작 | `ConnectionCenterActivity`→`SiwcRuntimeActivity`→`SiwcLoginModel`→`SiwcBrowserLogin` | b2144b0 `approvedConfig=null`: 버튼 비활성·discovery0. 로컬 승인 debug build만 별도 활성화. 시스템 브라우저 사용, 앱에서 비밀번호 수집 없음 |
| 인증·앱 복귀 | authorize `https://auth.openai.com/api/accounts/authorize`; 최초 `dynamic_agent_client`, host ID, state/nonce/PKCE-S256; `http://127.0.0.1:<port>/auth/callback`; token `https://auth.openai.com/api/accounts/oauth/token` | 발급 client ID와 같은 redirect로 교환, OIDC 검증 후 계정 활성화. socket·RSA·회전·실제 Activity 복귀 시험의 외부 서버/브라우저 adapter는 모의. 실제 OpenAI callback/admission 미확인 |
| token 수명 | `SiwcSessionStore`의 앱 보호 DB·키, account/epoch·만료·회전·로그아웃/UNKNOWN | 기존 Codex 세션 추출 없음. 실제 응답 expiry가 기준. 미확인 `earliest_refresh_at`은 refresh 보류, 중단된 회전 token 자동 재사용 금지 |
| 폴더·전사 | `MainActivity.pickFolder/selectRecordingFolder` SAF READ→허용 source 검사→`AndroidWhisperEngine`→`SiwcLocalTranscript.sourcePack` | 첫 실행 폴더 선택은 이미 결정·구현됨. 폴더 권한과 외부 전사 전송 동의는 별개. 현재 JNI는 PCM16 mono16k WAV 최대10MiB. 일반 폴더 source의 새 intake 연결은 없음 |
| Astra 내용 분석 | 동일 계정 token으로 GET `https://api.openai.com/v1/models`→POST `https://api.openai.com/v1/responses`; `gpt-6-astra`/`xhigh`, `store:false`, `stream:true`, `input` 배열 | `SiwcNativeRoundTrip.analysis`·`SiwcApprovedInference.analyse` 구현. 실제 계정 catalog와 요청 수락 필요. b2144b0 live wrapper의 제품 caller 없음. 로컬 후속은 `SiwcApprovedLiveDeviceTrial` 시험 driver의 호출 |
| 별도 맥락 | 완료 분석 key/output SHA+현재 승인 snapshot SHA로 별도 CONTEXT job, 동일 요청 모델/effort | 분석 완료와 구분. snapshot은 **해당 recording의 workflow_items**이며 업무 전체의 여러 통화·관련 메시지를 가져오는 retrieval이 아님 |
| 결과파일 | `noBackupFilesDir/siwc-results-v1/<scopeSHA>/<bundleKey>.json.enc` | 앱이 한 AEAD bundle(manifest+analysis+context+receipt)을 씀. `.part` fsync→검증→atomic rename→directory fsync→재검증→보호 DB pointer. 모델이 기기에 직접 쓰지 않음. 파일과 primary DB는 단일 transaction 아님 |
| 후보·검토·재열람 | `SiwcNativeReviewStore.receive`→파일/journal/source/revision 재검사→`AndroidAutomationStore.accept(REVIEW_REQUIRED)`→`approveSelection` | 후보와 native 이력은 같은 보호 DB transaction. 모델 완료/승인 flag로 사용자 승인 생성 불가. 새 TASK/DOCUMENT만 `needs_confirmation`으로 생성, 기존 대상 수정/취소/담당 변경은 별도 편집 필요. 재열람은 원 receipt 재사용 |

고정 코드 근거: [로그인 UI](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/SiwcRuntimeActivity.kt), [두 요청](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/SiwcNativeRoundTrip.kt), [분석 전송](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/SiwcApprovedInference.kt), [snapshot·검증](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/SiwcNativeContract.kt), [recording snapshot](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/RecordingWorkflowStore.kt), [파일](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/SiwcResultFiles.kt), [native 검토](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/android/app/src/main/java/kr/callbrief/SiwcNativeReviewStore.kt).

분석 JSON은 기존 6필드 `schema_version/job_id/source_pack_sha256/base_revision/summary/claims`, 맥락은 `ieum.siwc-context.v1`이다. 인용·원문 hash·source/version·기존 대상 revision의 **구조 합격은 의미 해석의 정답을 증명하지 않는다.** 요청·약속·기한·필요서류를 실제 응답과 독립 기대값으로 대조해야 한다.

[공식 로그인](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [모델·추론](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview 제한](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)을 10월28일 재확인했다. [공식 안내](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt)에 개인 로컬 프로젝트 경로가 있으며 Private Git 자체는 공개 전환 요구가 아니다. 업무 운영·배포 조건은 [등록 판정](SIWC_REGISTRATION_DECISION.md)의 구분을 유지한다.

**SIWC의 이 경로는 audio/video input·Files upload·transcription API를 지원하지 않는다.** 구체 구현은 로컬 Whisper 후 승인된 text/manifest 전송이다. 개인 dot의 원음 자동수신→runtime worker 실행을 구현한 것이 아니다. 개발 thread나 합성 전용 connector도 제품 runtime API가 아니다. 원래 원음 경로는 별도 지원 수신·실행·응답 회수 인터페이스와 권한을 확정할 때까지 OPEN이다. 없는 endpoint를 만들거나 이 목표를 조용히 제거하지 않는다.

## 실행 증거 독립 대조

문서 담당은 **작성자가 보존한 XML·lint·instrumentation 원문을 다시 읽고 집계**했다. 새 Android/JNI/OAuth/Astra 실행은 0회다. token/vault·개인 음성을 읽지 않았으며 이미 안전하게 내보낸 auth boolean/count만 확인했다.

| 증거 집합 | 이번 대조 결과 | 적용 범위 |
| --- | --- | --- |
| b2144b0 `siwc-final-xml/{app,core,context}` | 앱91 suites, 고유1222 중 PASS1218/SKIP4/실패0; core27·맥락44 PASS. lint Fatal0/Error0/Warning35 | 원격 통합 host 회귀. SKIP4=T81~T84 과거 합성 WAV 미준비. 실제 Astra 수 아님 |
| `siwc-device-final` | inactive/loopback/public-file/analysis/review/normal 원문 각각 `OK (1 test)`; 작성자 summary는 재열람 포함8회·고유6개 | AOSP35 ARM64. 외부 OAuth 서버/브라우저·두 모델 응답 모의, 공개 WAV JNI는 실제 |
| `siwc-device-final-summary.json` | 공개 파일 JNI2·fixture4·검토2, 재열람 JNI0/fixture0·동일 후보/digest/receipt. 기존 보고 동일 outbox/cursor·발송0 | 세부 카운트는 작성자 요약을 확인한 것. 실제 계정 파일·S25 인수 아님 |
| 미커밋 `siwc-approved-final-xml` | 앱92 suites, 고유1230 중 PASS1226/SKIP4/실패0; core27·맥락44 PASS; lint0오류/35경고 | b2144b0 시험 수를 덮어쓰지 않음. 후속 최종 commit에 결속 필요 |
| 미커밋 APK `siwc-approved-default-device-final` | inactive/loopback/public-file PASS, analysis `PUBLIC_G01_UI_TIMEOUT` 실패 원문 | summary의3 PASS만 읽으면 실패를 놓침 |
| 10월28일 `siwc-g01-resume-20261028` | analysis 재실행 PASS, review 원문 `PUBLIC_G01_UI_TIMEOUT` 실패 | 전체 기기 회귀 PASS 아님. 원인·고정 APK·재검증 필요 |
| ee472b3 `siwc-cancel-resume-final-xml` | XML 재집계71시험/실패0/오류0/SKIP0. 새6건은 header/body 대기·catalog 계정/권한 변경·종료 뒤 monitor 해제 | in-process HTTP interceptor 모의. 새 Android/JNI/실계정 실행0, 기존 통합 합격과 합산하지 않음 |
| `siwc-real-auth-status-1.json`·실진입 기록 | active=false, 등록 draft0, model slot0, HTTP 사건 discovery200 하나. token 교환·catalog·실제 분석/맥락 POST0 | 마지막 보존 실진입. 실행 중 작업의 현재 브라우저/후속 성공을 추정하지 않음 |

원격 [SIWC 통합 원기록](https://github.com/bllue583-commits/ieum-app/blob/b2144b0f1b8c6801a43c40f59ff80034f4b2f133/docs/SIWC_RUNTIME_INTEGRATION_20261026.md)과 [G01/G02 수정 기록](https://github.com/bllue583-commits/ieum-app/blob/c55b0402d8ddd17d370f6f2a28bc5ff13fccda55/docs/G01_G02_FIX_20261026.md)을 대조했다. core의 `docs/SIWC_APPROVED_TRIAL_20261026.md`는 이 시점 미추적 파일로 원격에 없었다. 그 승인 이력은 작성자 보고로만 확인했고 이 감사에서 새 grant 승인을 행사하지 않았다.

마지막 실진입은 OpenAI 로그인 화면 도달 전 Firefox 첫 실행 약관·Mozilla 데이터 안내에서 멈췄다. 앱 최종 상태는 `SIWC_LOGIN_CANCELLED`다. 원격 등록 완료는 확인되지 않았고 SIWC 서버 거부나 자동 approval 거절도 아니다. 후속 진행은 부모/실행 담당이 기존 사용자 응답과 현재 상태를 먼저 확인한다. 오래된 상태를 이유로 같은 승인을 다시 요구하거나 종료된 요청을 재사용하지 않는다.

원증거 SHA256: `siwc-final-results.json`=`04fb54166af60b08f10e1bb9c4c05bab379be613b437687f897469879be1712f`; `siwc-approved-final-results.json`=`7a2915ab6b5555ae893d34c7c515f3343c68d811239c16983b08ffb348df7dcb`; `siwc-real-auth-status-1.json`=`e19e248afbccb1cab5ac5ee8f352a516b1004713ff479480b199cf474bafc7d4`. ignore된 원자료는 Git에 올리지 않았다.

### 이전 문제의 현재 판정

- **G01/G02:** c55b040에서 실제 재현 후 수정, b2144b0 회귀에도 포함. 과거 미수정 판정을 현재 상태로 재사용하지 않는다. 최신 미커밋 build의 timeout 때문에 재개 작업 전체 완료는 보류한다. timeout만으로 제품 재발/환경 문제를 단정하지 않는다.
- **로컬 COMPLETE:** 알려진 빈 로컬 창도 내구성 checkpoint 후 완료할 수 있다. 외부 수집 전체 완료는 아니다. `CanonicalMorningSnapshotPort`는 PARTIAL(읽기 실패 UNKNOWN), 기본 배송은 dry-run이다.
- **G03/CP08:** 취소된 보고일의 명시 재개 정책, 최종 admission 뒤 강한 취소 전체는 별도 인수다. HTTP 취소 보강도 상대 서버 처리·과금 취소 확인으로 확대하지 않는다.
- **검토 보호:** 모델 완료→파일 완료→후보→사용자 검토를 구분하고 기존 요약·수동DONE·메모·revision을 지킨다. 이 보호의 합격과 누적 갱신 구현 완료는 다르다.

## 다음 배치·합격 기준

| 순서 | 큰 산출물 / 담당 경계 | 실행·기대 출력·완료 게이트 |
| --- | --- | --- |
| 1 | **M1 실제 계정 왕복**. 인증 담당=transport/session/취소; core=initiator/source/job/파일/검토; 실행 담당=고정 APK 검증 | 현재 auth와 기존 승인 확인→공식 callback·검증 계정→실제 catalog→승인된 공개 전사 분석1+맥락1. 각 요청/응답 SHA·response ID·실제 model·보고된 effort 보존. effort 미보고는 `NOT_REPORTED`, xhigh 실행 관측을 만들어내지 않음 |
| 2 | **M1 일반 앱 동선과 복구**. core 공유 writer | 이음 화면에서 허용 음성 선택·전송 안내→전사→실제 두 단계→파일 읽기→후보 표시·명시 검토→재기동. driver만으로 합격 금지. 재열람 추가 POST0/항목0, 취소·계정전환·철회·수동수정 뒤 늦은 canonical 쓰기0. stream stall 취소·현재 review timeout 검증 |
| 3 | **M2 누적 갱신**, M5 snapshot 접점 | 맥락 담당 work snapshot/delta→core 기존 item 적용→보고 담당 승인 receipt 소비. 같은 업무의 요청→기한 정정→부분 수신→취소, 같은 item ID·근거/이력 유지. 현재 SIWC 검토 결과가 보고 snapshot에 나타나는 직접 회귀 |
| 4 | **M3 포맷·긴 통화·자동 접수** | 기존 SAF 거부/철회/재시작, 공개·합성 AAC/M4A와10MiB 초과 입력 decode/chunk/restart. 목록 manifest·bytes/hash·구간 순서·전사 중복 대조. 백그라운드 동의/usage 제어 확정 전 무인 추론 활성화 금지 |
| 5 | **M4+M5 종합과 실제 시간 운영** | 합성 read adapter 종단 후 승인된 계정 read-only 시험. KST23시/증분/실제06시·휴면·재부팅·오프라인·늦은 결과에서 전날/최근7일·누락·미검토 표시. 불변 outbox·ACK/UNKNOWN/선택 실수신 각각 확인 |
| 6 | **M6 S25 서비스 인수** | 큰 기능 준비마다 공개·합성 실기기 검증 병행, 최종 통합 SHA/APK 전체 연결. 개인 자료·새 권한·유료 계약은 승인 범위 밖에서 임의 실행하지 않음 |

처음 고정 공개 WAV는 **transport·STT·저장 smoke test**다. 업무 의미 품질은 별도 합성 대화와 독립 기대값으로 검사해야 한다. 다음 입력은 제안이며 이번 감사에서 NOT_RUN이다.

| 합성 시나리오 | 기대 결과 / 현재 한계 |
| --- | --- |
| 고정 발생시각+“견적서를 11월 5일까지 보내 주세요. 도면도 필요합니다.” | 요청·기한·필요서류·정확한 근거 인용. 미확인 담당자/사람 생성 금지 |
| 같은 업무 다음 사건 “견적 기한은 11월 7일로 바꾸고 도면 요청은 취소합니다.” | 기존 ID의 정정/취소 제안. 검토 전 변경0, 검토 후 같은 ID의 이력 추가. 현 생성 전용 적용으로는 미완료 |
| “서류 두 개 중 신청서만 받았습니다.”와 뒤늦게 도착한 첫 사건 | 일부 수신·남은 서류 구분, 최신 기한 역행0·중복 item0·근거 보존 |
| 수동DONE/메모 후 늦은 응답·중복 재수신, 다른 owner·철회 폴더 | 수동 상태 보존·충돌 보류·잘못된 파일 저장 차단. 기존 보호 회귀와 실제 누적 종단을 구분 |

모델 unavailable·401/403/429·응답불명·validation 실패 때 규칙/다른 모델·유료 API로 자동 대체하지 않는다. 완료 journal·파일은 재열람하고 UNKNOWN의 새 POST는 별도 승인·정책 없이 재시도하지 않는다. 원음 직접 전송의 별도 공식 경로가 확인되지 않으면 text 왕복 성공과 분리해 OPEN으로 유지한다.

## 통합 주의와 이번 문서 검증

부모가 **인증/transport 취소 고정 commit→core 승인 시험/앱 호출 연결→M1 실제·보호 회귀→M2 기존 대상 갱신→M5 승인 snapshot→채널·운영 인수** 순서로 통합한다. 인증 후속 [ee472b3 인계](https://github.com/bllue583-commits/ieum-app/blob/ee472b3b69325c64cb1016c3b797c32dc58024f2/docs/SIWC_AUTH_RESUME_20261028.md)는 blocked HTTP I/O 중에도 `Call.cancel()`을 요청하도록 보강했다. 미커밋 core와 이 commit이 모두 `SiwcAstraProtocol.kt`를 바꾸므로 한 writer가 관측 hook·1+1 예산과 취소 변경을 함께 보존한다. 기존 b2144b0에 적용 가능하다는 인증 문서 설명을 최신 미커밋 core에 무충돌이라는 뜻으로 확대하지 않는다. `SiwcApprovedInference`, `AndroidLocalSttPilot`, Store/SQL/registry도 기존 공유 소유권을 유지한다. 문서 담당은 제품 충돌을 직접 수정하지 않는다.

새 통합 SHA 없이 과거 APK·새 XML·미커밋 코드를 한 완료 기록으로 묶지 않는다. main/타 branch 무단 merge·push 금지. docs 통합 시 제품 branch의 G01/G02와 원 시험 기록을 보존하고 역사 문서 상단의 최신 링크만 갱신한다.

이번 문서 검증 결과: Python3.9.6의 맥락 validator12 PASS·보고 validator18 PASS, 상대 링크52·고정 Git blob17 확인(깨진 링크0), tracked381파일 export scan 발견0, staged whitespace PASS, 변경3파일 모두 docs다. validator PASS는 제품 시나리오30/40 실행이 아니다. 새 Android·실계정·Astra 실행0, 독립 원증거 재집계와 제품 재실행을 구분한다.
