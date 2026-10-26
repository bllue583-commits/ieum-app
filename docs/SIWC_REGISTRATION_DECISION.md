# Private 이음의 SIWC 등록 경로 판정

검토일 2026-10-26. **Private Git 저장소라는 이유만으로 SIWC 이용이 불가능하거나 소스를 공개해야 하는 것은 아니다.** OpenAI 공식 Cookbook은 OSS 외에 **로컬에서 실행하는 개인 프로젝트**도 플랜 사용 대상으로 명시한다. 따라서 사용자 본인의 기기·계정에서 실행하는 개인 로컬 시험에 대해 문서화된 동적 등록 경로를 확보했다. 실제 계정 등록·Android 인증·추론 성공까지 확인한 것은 아니다. 업무 운영/배포 목적의 범위는 아래처럼 분리한다. [공식 Cookbook의 사용 범위](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt)

이 문서는 [runtime 구현 계약](RUNTIME_ACCEPTANCE_20261026.md)의 B01을 구체화한다. `feature/astra-audio-roundtrip`의 고정 commit `4b3ecff3353642033de3e972ee10f7c50acd2b2d`를 읽었으며 제품 파일은 변경하지 않는다. 새 등록·약관 명시 수락·OAuth grant·키 생성·support 문의/신청 전송·계정 인증정보 읽기는 **실행0**이다. 거절된 요청이 아니라 문서/코드 조사와 미실행 상태다.

## 경로별 판정

| 대상 | 공식 근거 | 이음에 적용할 판정 |
| --- | --- | --- |
| 본인 기기에서 실행하는 개인 프로젝트 | Cookbook은 OSS·개인 로컬 프로젝트·선택된 Private 앱을 구분하고 개인 로컬 경로를 설명한다. [개요](https://developers.openai.com/siwc/token-sharing-open-source)는 로컬 앱의 사용자별 등록을 설명한다 | **등록 경로 확인.** Git 공개 전환·상업 partner 신청을 모든 개인 시험의 선행조건으로 두지 않는다. 사용자/워크스페이스·모델 admission은 실제 등록 후 별도 검증 |
| 일반 Private 앱 | [Quickstart](https://developers.openai.com/siwc/quickstart)는 선택된 Private client 범위를 명시한다 | Private라는 속성만으로 전면 허용/금지 판정 불가. 개인 로컬 조건 밖의 이음 배포는 선정/접근 조건을 OpenAI에 확인 |
| 유료 또는 원격 제공 앱 | Cookbook은 제공 전에 접근 신청을 안내한다. [상업 client 요청](https://developers.openai.com/siwc/request-client-id)과 [공식 interest form](https://openai.com/form/sign-in-with-chatgpt-interest/)이 있다 | 사용자의 개인 계정으로 다른 사용자의 요청을 처리하는 운영 구조를 만들지 않는다. 이런 배포를 선택할 때 상업 경로를 진행; 이번 작업에서는 신청하지 않음 |
| identity-only 웹/partner 로그인 | [웹 통합 문서](https://developers.openai.com/siwc/website)는 사전 client와 등록 callback을 요구하는 제한 trial을 설명한다 | 이 경로를 개인 로컬 plan-usage 동적 등록의 필수 콘솔 단계로 혼합하지 않음 |
| OAuth public client | [등록 문서](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)의 direct flow는 secret 없는 public client | **public client는 repo 공개 여부가 아니다.** Android APK에 client secret을 넣거나 다른 앱의 client ID/토큰을 재사용하지 않음 |

Quickstart의 간단한 “선택된 Private” 설명만으로 개인 로컬 경로를 배제했던 이전의 넓은 미확인 상태를 위 Cookbook 근거로 좁힌다. 이음이 개인 기기용이라는 사실은 확인됐지만 업무 기억 앱이라는 용도까지 모든 운영 조건을 충족한다고 단정하지 않는다. 공개 저장소 전환은 해결책으로 요구하거나 실행하지 않는다.

## 정확한 등록 절차와 미확인 지점

공식 경로에는 독립적인 `POST /register`나 개발자 콘솔의 self-service Private 앱 생성 단계를 확인하지 못했다. **첫 로그인 자체에 동적 등록이 포함**된다. 아래 authorize/token과 발급 ID 절차는 [공식 등록 명세](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)에 있다. 임의 등록 endpoint·client ID는 만들지 않는다.

1. 이음의 해당 설치에 안정적인 `ext_agent_host_id`를 저장한다. 같은 기기 재시작/재로그인에서 재사용한다. 새 로그인마다 state·nonce·PKCE verifier는 새로 만든다.
2. listener를 먼저 열고 시스템 브라우저로 `https://auth.openai.com/api/accounts/authorize`를 연다. 최초 `client_id=dynamic_agent_client`, 실제 앱 이름 `agent_name_hint=이음`, host ID, code flow/PKCE를 사용한다. 상세 query·scope는 기존 [인증 계약](RUNTIME_ACCEPTANCE_20261026.md)을 따른다.
3. 사용자가 계정/워크스페이스와 plan usage 동의를 확인한다. callback의 state와 오류를 먼저 검사하고 **반환된 issued client ID**를 해당 등록에 보존한다. `dynamic_agent_client`를 발급 ID로 저장하지 않는다.
4. `POST https://auth.openai.com/api/accounts/oauth/token`에 code·발급 client ID·PKCE verifier·동일 redirect/resource를 form으로 교환한다. ID-token 서명/issuer/audience/expiry/nonce와 실제 granted scopes를 검증한다. plan scope가 없으면 identity 로그인만 유지하고 추론하지 않는다.
5. 같은 grant로 `GET https://api.openai.com/v1/models`를 읽고 Astra 가용성을 확인한 뒤 `POST https://api.openai.com/v1/responses`의 terminal completed를 검증한다. 로그인, catalog, completed inference는 각각 다른 증거다. 새 API 키·Platform billing이 이 동적 경로의 필수 요소는 아니다.

**Android callback:** 공개 direct 명세의 URI는 `http://127.0.0.1:<port>/auth/callback`이다. 최초 등록 이후 scheme/host/path는 유지하고 port만 바꿀 수 있으며 각 시도 authorize/token에는 정확히 같은 URI를 사용한다. `localhost`, 임의 custom scheme, Android App Link를 이 값으로 대체할 수 있다고 문서화되어 있지 않다. 확인한 공식 예제는 Node/React와 macOS 앱이며 Android SDK·패키지/서명 등록 방법·인증 후 자동 Activity 복귀 보장은 확인되지 않았다. [DevKit 구성](https://github.com/openai/sign-in-with-chatgpt-devkit)

이음의 public-client 구현 후보는 **같은 Android 기기에서 시스템 브라우저+loopback listener**다. 이는 명세를 적용하는 설계 제안이며 Android 인증 성공 관측이 아니다. browser 전환 중 listener/lifecycle 유지, callback 수신, 앱 복귀, 취소·동시 시도·오래된 callback 거부를 실제 기기에서 검증한다. Android 전용 redirect가 필요하거나 공식 경로가 정책상 거절되면 다른 callback/비공식 endpoint로 우회하지 않고 공식 지원에 확인한다.

## 약관과 DevKit 재사용은 별도 판단

[SIWC 약관](https://openai.com/policies/sign-in-with-chatgpt-terms/)은 앱의 실제 이름·공식 로그인, 사용자 제어 runtime, 자기 요청과 명시 동의한 background 사용, 연결한 앱에만 플랜 사용, 안전한 로컬 token 보관 등을 요구한다. 계정 로그인은 conversations/memory 공유권이나 모든 서비스를 호출할 권한을 주지 않는다. 본인 계정 시험 승인과 새 앱 grant/지속 접근 승인은 구분한다.

공식 DevKit은 일반 MIT/Apache SDK가 아니라 [NONCOMMERCIAL LICENSE](https://github.com/openai/sign-in-with-chatgpt-devkit/blob/main/LICENSE)를 제공한다. 해당 코드/문서의 상업 사용에는 별도 서면 합의가 필요하며, 비상업 정의는 업무·고용주·고객 운영/상업적 이익을 위한 개발·시험도 제외한다. 무료·Private·1인 사용만으로 DevKit의 비상업 조건이 자동 충족되지는 않는다. 같은 라이선스는 독립 작성 소프트웨어가 인터페이스를 호출한다는 이유만으로 Modified Work가 되는 것은 아니라고 구분한다. 이것만으로 SIWC 서비스의 업무 사용 자격까지 허용됐다고 해석하지 않는다.

읽은 기능 commit의 Kotlin/OkHttp 경로에는 DevKit 의존성 추가가 없으며, 이 검토도 DevKit 코드/컴포넌트를 설치·복사하지 않았다. 따라서 DevKit 라이선스 확인을 이유로 독립 Kotlin 인증 구현·합성 contract 검증 전체를 중단할 필요는 없다. **실업무 운영 자격과 DevKit을 실제 채택할 경우의 권리**를 분리해 확인한다. 공개 전환이나 라이선스 교체는 실행하지 않는다.

## 부모가 승인 단계에서 제시할 구체 범위

| 항목 | 제안된 최초 시험 범위 |
| --- | --- |
| 서비스·앱 | OpenAI SIWC direct plan usage; 앱 이름 이음; 본인 Android 로컬 설치. 상업/원격 배포 신청은 별도 |
| 권한 | identity `openid profile email`, plan `resource.invoke chatgpt.tokens.use.direct`, 갱신 `offline_access`; 새 동의 화면에서 실제 부여 범위를 확인 |
| 데이터 | 공개/합성 WAV를 로컬 JNI로 전사한 text·출처 hash/manifest·합성 승인 snapshot만. 실제 개인 음성·SMS·메일·연락처0 |
| 저장 | 인증 토큰은 기기 보호 로컬 영역, 사용자/발급 client별 분리. 분석 결과는 앱 전용 암호화 bundle. 로그·Git·공유 서버에 인증정보0 |
| 사용량 | catalog read 뒤 분석1회·맥락1회, 모델/effort와 데이터 범위를 표시. 사용자의 기존 플랜 한도 사용; 추가 credits 구매/별도 API billing·자동 fallback0. UNKNOWN의 자동 재호출0 |
| 지속성 | refresh grant는 지속 접근이므로 구체 동의 필요. 첫 시험은 사용자 시작 동작에 한정; 무인23시/06시 모델 호출은 별도 명시 background 동의 뒤 |
| 이번 수행 | 위 등록/동의/요청은 실행하지 않음. 문서·공개 소스 조사와 자기 docs branch 편집만 수행 |

이는 검토 가능한 시험 범위다. 이번 문서는 사용자에게 포괄적인 “등록해도 되나요”를 다시 묻는 대신 부모가 해당 단계의 필요한 승인만 받을 수 있게 범위를 고정한다. 사용자의 플랜 종류·한도·grant 유무를 인증정보 파일이나 다른 client의 세션 추출로 확인하지 않는다.

## 공식 문의가 필요한 경우와 질문

**개인 로컬 프로젝트의 문서화된 경로가 존재하는지**는 이번 조사로 답을 얻었으므로 같은 일반 질문을 반복할 필요가 없다. 다음 두 항목은 아직 공개 문서로 종료하지 못했다. 실업무 운영으로 확정하거나 Android 고유 callback이 필요할 때 [OpenAI Support](https://help.openai.com/en/articles/6614161-how-can-i-contact-support)의 도움말 채팅으로 기술·자격 확인을 요청할 수 있다. 유료/원격 제공 또는 commercial client 선정을 원하면 위 공식 interest form을 사용한다. 문의/양식 제출은 이번 작업에서 하지 않았다.

- **범위 질문:** “Private 저장소의 독립 Kotlin Android 앱을 사용자 본인의 기기에서 본인 ChatGPT 플랜으로만 실행합니다. 판매·공유 계정·원격 관리 서버는 없지만 업무 기록을 정리할 목적입니다. 공식 personal-local dynamic registration 범위로 등록 가능한가요, 아니면 selected-private/commercial 사전 승인이 필요한가요? DevKit 코드 미사용인 직접 protocol 구현에 적용되는 추가 조건도 확인해 주세요.”
- **Android 질문:** “문서의 `127.0.0.1:<port>/auth/callback` public-client flow를 Android 시스템 브라우저와 앱 내 listener에 적용하는 것이 지원되나요? Android용 redirect/패키지·서명 등록 또는 별도 사전 설정이 필요하다면 공식 절차와 허용 URI를 알려 주세요.”

문의에는 repo 내용·실제 통화·토큰·쿠키·기존 인증파일을 첨부하지 않는다. 필요하다면 앱 구조/운영 범위·문서 링크·합성 callback 실패의 비밀 없는 코드와 request ID만 제공한다. 문서에 없는 endpoint를 추정하는 대신 미확인 범위를 그대로 남긴다.

## 현재 구현과 다음 handoff

[feature 결과 문서](https://github.com/bllue583-commits/ieum-app/blob/4b3ecff3353642033de3e972ee10f7c50acd2b2d/docs/astra-audio-roundtrip.md) 및 `SiwcAstraProtocol`·`SiwcResultFiles` 소스를 읽었다. catalog/SSE·두 job·결과파일 계층은 있고 실제 OAuth 화면·token 공급자는 미설치다. 결과는 기존 설계의 4개 별도 파일 제안 대신 `noBackupFilesDir/siwc-results-v1/<scopeSHA>/<bundleKey>.json.enc`의 **단일 암호화 bundle**로 결속한다. 소스 확인은 실제 기기/모델 인수가 아니다.

작성자 보고의 관련151 PASS와 마지막 catalog11 PASS는 서로 겹치는 실행으로 유지한다. 이번 문서 담당은 이를 독립 재실행하지 않았다. 실제 Astra0·OAuth0·Android 종단 NOT_RUN은 변함없다. 이전 M1~M6/검토 보호·PARTIAL/outbox 인수 조건도 유지한다.

다음 담당 작업은 **Android public-client 인증 adapter 구현→허용된 새 grant 절차→계정 catalog→합성 전사 분석/맥락 completed→단일 bundle→명시 검토/보호 저장**이다. 부모가 담당 파일 writer를 조정한다. 본 문서는 `docs/milestones-git`만 수정하며 main·통합·기능 branch를 merge하거나 변경하지 않는다.
