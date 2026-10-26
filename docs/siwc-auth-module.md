# SIWC 개인 로컬 인증 모듈과 인수 계약

2026-10-26. `feature/astra-audio-roundtrip`의 `4b3ecff` 이후 추가 구현이다. 총괄 [등록 판정@69531e0](https://github.com/bllue583-commits/ieum-app/blob/69531e0c4d62d69f6ce0d08f0644c2c1e58eaee3/docs/SIWC_REGISTRATION_DECISION.md)을 읽었다. [공식 Cookbook](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt)은 개인 로컬 프로젝트 경로를 명시한다. **개인 합성 시험을 위해 저장소 공개 전환이나 별도 등록 콘솔을 요구하지 않는다.** 실제 사용자/워크스페이스 admission·Android 복귀·업무 운영 적격성은 아직 검증하지 않았다.

이 모듈은 공식 protocol을 독립 Kotlin/OkHttp로 작성했다. DevKit 코드/컴포넌트/의존성을 복제하거나 설치하지 않았다. 새 dependency·manifest·공유 Store/SQL·기존 UI 변경도 없다. 현재 기본 실행 경로에 설치되지 않았으며, 실제 등록·OAuth grant·토큰 교환·Astra 요청은 **0회**다.

## 구현 경계

| 구성 | 구현된 동작 |
| --- | --- |
| `SiwcOAuthConfig` | 기본값/원격 설정 없음. native 호출자가 등록 경로 근거와 선택된 issued ID 또는 명시 승인된 최초 dynamic 방식을 제공해야 한다. null이면 browser/listener/discovery 모두 시작하지 않는다. |
| `SiwcOAuthProtocol` | 공식 authorize URL, 설치별 host ID, 매 시도 256-bit state/nonce/verifier, S256, 동일 loopback redirect/resource 결속. code는 해당 시도에서 한 번만 교환한다. 최초 `dynamic_agent_client` 후 callback의 issued ID를 사용하고 재로그인에서는 그 issued ID를 재사용한다. |
| callback/OIDC | callback target·state·중복 query·issuer·변경 client·오류·만료·재사용 검사. trusted HTTPS JWKS의 RS256/RSA2048+ 서명, issuer/audience/azp/exp/iat/nbf/nonce/sub, 제공된 at_hash/c_hash 검증. JWT의 jku/x5u/jwk/crit와 다른 알고리즘 거부. |
| `SiwcAuthHttp` | 고정 공식 discovery/authorize/token, discovery가 제공한 동일 issuer의 JWKS/revocation만 사용. redirect/cookie/자동 재전송 없음, TLS·응답 크기·30초 call timeout. 사용자 token을 URL·로그에 넣지 않는다. |
| `SiwcSessionStore` | base-app 보호 vault. 계정 identity+issued client별 분리, 같거나 다른 이메일로 계정을 합치지 않는다. 새 ID 검증 전 활성 계정 유지. 전송/맥락 현재성을 위한 epoch 제공. plan scope 없는 정상 identity는 보관하되 inference grant 없음. |
| 갱신 | expiry 근처 한 번의 durable claim, 회전 token·expiry·scope의 원자적 교체. 같은 session의 중복 refresh0. terminal invalid/reused/expired 오류는 token 삭제 후 재인증; 명시 5xx는 기존 credential과 30초 backoff 유지. 결과를 잃은 refresh/프로세스 종료는 `REFRESH_UNKNOWN`, 기존 refresh token 자동 재사용0. |
| 로그아웃 | 먼저 호출/commit을 막고 bounded revocation 1회 시도. 로컬 secret 삭제와 원격 확인/미확인을 구분한다. issued ID·검증 identity·host ID는 유지. 늦은 refresh는 복구0, 늦은 logout은 새 검증 로그인 삭제0. |
| `SiwcLoopback` | `127.0.0.1`의 임시 포트에만 bind, `/auth/callback`, 제한 HTTP header/Host·최대5분·최대32 invalid 요청. code/state만 수신, 고정 비반사 텍스트·no-store 응답, 취소 시 socket 종료. |
| `SiwcBrowserLogin` | listener 준비 후 system-browser 실행, 제한된 IO lifecycle, 취소·동시 시도 fencing. `SiwcAndroidBrowser`는 외부 ACTION_VIEW와 앱 내부 명시적 immutable PendingIntent 복귀 요청을 사용한다. callback/복귀 Intent에 token을 담지 않는다. |

공식 protocol 근거: [sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [accounts/sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference), [recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery). 실제 읽은 [공식 discovery](https://auth.openai.com/.well-known/openid-configuration)는 issuer `https://auth.openai.com`, authorize `/api/accounts/authorize`, token `/api/accounts/oauth/token`, JWKS `/.well-known/jwks.json`, revoke `/api/accounts/oauth/revoke`, RS256·S256·public-client `none`을 제공했다. 독립 등록 API를 추정하지 않는다.

## 저장과 수명

설치 host ID, pending generation, 활성 등록/epoch, identity/client mapping, token set, 실패 상태는 `context.noBackupFilesDir/protected-databases-v1/siwc-auth-v1.db.sealed`에 저장한다. 기존 `ProtectedDatabase`의 Keystore AEAD·AtomicFile·OS 파일 lock을 사용하며 SQLite는 memory-only다. 다른 process의 동시 vault open은 거부된다. Google `AccountStorageContext`를 이 vault에 전달하지 않는다. Google owner와 ChatGPT identity는 별개다.

token response를 받은 시각과 실제 `expires_in`으로 access expiry를 계산하고, rotation 뒤 refresh의 문서상30일 수명을 새로 보관한다. `earliest_refresh_at`의 필드 존재는 공식 문서에서 확인했지만 encoding/단위는 확인하지 못했다. 원 값을 암호화 보존하고 **값이 있으면 검증된 decoder가 제공될 때까지 refresh를 보류**한다(`SIWC_REFRESH_SCHEDULE_UNVERIFIED`). 단위 추측으로 refresh하지 않는다. 이 보류는 아직 유효한 첫 access token의 명시 승인된 사용을 막지는 않는다.

PKCE/state/nonce와 미완료 code는 해당 로그인 시도의 메모리에만 존재한다. process가 죽으면 pending generation을 무효화하고 다시 로그인한다. callback에서 확인된 issued ID는 code 교환 전에 보호 draft로 보관하므로 invalid_grant나 교환 중단 때 새 dynamic client를 반복 등록할 필요가 없다. draft는 검증 identity도 활성 계정도 아니다. UI가 draft ID를 선택해 같은 설치 host로 재로그인해야 한다.

`inferenceGrant(ownerScope, approvedInvocationKeys)`는 현재 검증된 계정·epoch·유효한 plan scope와 expiry로만 만든다. 계정 snapshot과 token은 같은 보호 read 안에서 읽는다. 같은 snapshot에서는 같은 grant 객체를 반환하므로 기존 `SiwcAstraHttp`의 계정변경 검사를 사용할 수 있다. 이 grant가 사용자 음성/전사 전송에 대한 포괄 동의가 되는 것은 아니다. 각각의 승인된 invocation key가 별도로 필요하다.

## core writer가 연결할 지점

1. 사용자에게 아래 시험 동의 범위를 보여준 뒤 `Continue with ChatGPT`를 누를 때만 명시 config를 공급한다. 미승인 시 null 유지. 첫 시험은 개인 로컬 plan-usage 경로로 `requestPlan=true`, 최초 dynamic 승인 config를 사용한다. 사용자에게는 공식 페이지가 실제 scope 동의를 받는다.
2. `SiwcSessionStore`는 base context/IO에서 생성한다. `SiwcBrowserLogin`을 Activity 재생성·브라우저 전환 동안 유지하는 lifecycle owner에 둔다. UI 작업용 main Executor와 `SiwcAndroidBrowser(baseContext, MainActivity::class.java)`를 제공한다. 명시 취소 때 handle.close, 소유 lifecycle 종료 때 login.close 후 vault를 닫는다. UI는 status/key만 받으며 credential을 표시하지 않는다.
3. 복귀 요청이 성공해도 OS foreground 보장은 없다. `Outcome.returnRequested`는 PendingIntent 발송 결과일 뿐이다. 실제 Activity resume 때 vault의 active registration을 다시 읽는다. background Activity 제한/OEM 종료/화면회전/네트워크 단절/Android loopback은 실기기 인수 항목이다. custom scheme이나 WebView로 우회하지 않는다. 모듈은 foreground service·자동 background 호출을 추가하지 않는다.
4. source 선택/권한은 기존 `MainActivity` SAF 온보딩을 유지한다. 같은 계정 epoch와 owner/source/policy/grant/취소·snapshot revision을 모든 `current` callback에 결속한다. 로그아웃/계정전환/취소 후 이전 결과 commit0. `c55b0402d8ddd17d370f6f2a28bc5ff13fccda55`의 G01 active integration 취소와 G02 빈 창 durable 완료를 통합 시 보존한다. 해당 branch를 이번 작업에 merge하지 않았다.
5. `SiwcLocalTranscript.sourcePack`은 checked local STT journal의 실제 전사·구간·원음 hash를 제공한다. native adapter는 여기에 아래 frozen 6필드 입력 및 원문 source binding을 구성한다. 모델 출력의 실행 주장으로 native receipt를 만들지 않는다.
6. `SiwcInferenceJobs.run`/`SiwcResultFiles.publish/read`의 validator는 core·맥락 담당이 제공한다. 분석은 기존 `worker-output.schema.json`의 **schema_version, job_id, source_pack_sha256, base_revision, summary, claims**를 그대로 검증한다. `claims`의 Unicode code-point quote와 source ID/version/audio SHA·작업/현재 revision을 대조한다. 실제 결과를 통과시키려고 `WorkerV1Boundary`의 synthetic/provenance 제한에 거짓 값을 넣지 않는다. 구조 검증과 native 출처 허용 경계를 분리해 통합해야 한다.
7. 별도 context 호출에는 완료 analysis job/output SHA와 현재 승인 snapshot SHA를 넣고 명시 model/effort를 제공한다. 맥락 validator가 기존 task/document 대상, 수정·취소·불확실성·충돌을 판정해야 한다. 임의 후보 자동승인0, `Store.accept`→사용자 명시 검토→DONE/메모/revision 보호 gate를 유지한다. 모델이 파일을 직접 쓰는 것이 아니라 앱이 두 완료 응답을 단일 AEAD bundle로 저장하고 재읽는다.

위 UI·native/context mapping은 공유 파일 owner의 통합 작업이다. 이번 추가 모듈이 앱 버튼에 이미 연결됐거나 실업무 전체가 완성됐다고 보고하지 않는다.

## 부모가 사용자에게 제시할 최초 grant·합성 시험 승인안

- **서비스/앱:** OpenAI SIWC, 앱 표시명 `이음`, 사용자 본인의 Android 로컬 설치·본인 계정. 최초 공식 브라우저 로그인에서 dynamic 등록 후 발급 client ID 사용.
- **권한:** identity `openid profile email`, 추론 `resource.invoke chatgpt.tokens.use.direct`, 갱신·지속접근 `offline_access`. 실제 동의 화면과 반환 scope를 확인한다. 대화/메모리/Library·개인 파일 접근권을 요청하는 것이 아니다.
- **사용량:** 계정 catalog 확인 후 공개 합성 입력의 Astra xhigh 분석1회, 별도 맥락1회. 맥락 시험 모델/effort도 실행 전 명시한다(제안: Astra/xhigh). 기존 플랜 allowance/계정의 앱별 usage·credits 설정을 따르며, 앱은 credits 구매·별도 API billing·자동 fallback을 수행하지 않는다. UNKNOWN은 자동 재호출하지 않는다. 사용량 제어는 ChatGPT Settings → Usage이며 [공식 안내](https://learn.chatgpt.com/docs/sign-in-with-chatgpt)를 따른다.
- **데이터:** `worker/tests/audio/public-ko.wav`(245870 bytes, SHA256 `69ac671cd328486d14dfdaa413ec0a511c21e5708953fbe3dc2a280a6a2cf20d`) 또는 `public-en.wav`(213210 bytes, SHA256 `7d45a0f1af47bc5a212dd2e53d22566c6037b190708bff51782e387caed20df7`)의 로컬 전사·구간·출처 manifest와 합성 승인 snapshot만. SIWC에 원음 업로드0, 개인 통화/SMS/메일/연락처 전송0.
- **저장:** 위 app-private no-backup AEAD vault에 identity/client/token/expiry를 저장한다. 결과는 `siwc-results-v1/<scopeSHA>/<bundleKey>.json.enc`. token은 Git·로그·공유 서버로 복제하지 않으며 기존 Codex 인증을 재사용하지 않는다. 로그아웃과 원격 철회 확인/미확인을 표시한다.
- **조건:** [SIWC 약관](https://openai.com/policies/sign-in-with-chatgpt-terms/)과 연계 [Terms of Use](https://openai.com/policies/terms-of-use/)·[Service Terms](https://openai.com/policies/service-terms/). 이번 요청은 개인 합성 시험이며 업무 운영/배포 적격성 판정, 무인23시/06시 사용, 다른 사람 요청, 결제/추가 credits 구매 승인이 아니다. background 사용은 별도 동의가 필요하다.

현재는 이 승인안을 기록했을 뿐, 사용자 대신 약관 수락·실제 grant·credential 발급을 수행하지 않았다.

## 검증 증거

`siwc-auth-final.log`: 97 PASS/실패0/오류0/skip0. 새 인증36개(OAuth11, session18, loopback3, browser4)와 기존 Astra/SIWC61개의 합성 회귀다. 실제 보호 DB/암호화, RSA 서명, 127.0.0.1 socket callback을 실행했고 브라우저/token endpoint는 모의 adapter, Android 복귀는 Robolectric Intent 캡처다. 이후 token expiry의 수신시각 기준·계정 snapshot 원자 읽기 보완을 포함한 `siwc-auth-refinement.log`는 **SIWC64 PASS/실패0/오류0/skip0**, lint Fatal0/Error0/기존 Warning35다. 전 회차97·151·11건과 겹치는 실행을 합산하지 않는다.

재현: 준비된 JDK17/SDK36/offline cache로 `python3 run_gradle.py --offline --log siwc-auth-final.log :app:testDebugUnitTest --tests 'kr.callbrief.Siwc*Test' --tests 'kr.callbrief.AstraAudio*Test' :app:lintDebug`. 실제 OAuth/OpenAI 추론0, 외부 브라우저/Android 기기 NOT_RUN. 증거는 ignored `evidence/siwc-auth-final.json`과 log에 보관했다.

최종 grant 경계 보완: offline_access 없이 받은 refresh credential 저장을 거부하고, 이미 검증된 등록의 재로그인 실패는 신규 draft로 중복 표시하지 않는다. `siwc-auth-grant-final.log` 및 JSON: **SIWC65 PASS/실패0/오류0/skip0**, lint Fatal0/Error0/Warning35. staged431파일 export scan 발견0, whitespace PASS. 최종 실행은 이전 실행 수와 합산하지 않는다.
