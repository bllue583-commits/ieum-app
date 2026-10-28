# 승인된 개인 SIWC 시험 준비와 첫 실진입 기록

기준 `b2144b0f1b8c6801a43c40f59ff80034f4b2f133`, branch `integration/siwc-runtime-20261026`. 이전 [앱 통합·합성 인수](SIWC_RUNTIME_INTEGRATION_20261026.md)는 원기록으로 보존한다. 이후 부모가 사용자 본인의 계정에 대한 새 공식 SIWC 연결·앱 전용 암호화 token 저장·공개 전사와 합성 snapshot의 Astra/xhigh 분석1회+별도 맥락1회를 승인받아 전달했다. 다른 계정·개인 통화/SMS/메일/연락처·추가 결제·다른 프로젝트에는 확대하지 않았다.

## 활성화·횟수·관측 경계

- 보통 build는 여전히 로그인 비활성이다. 승인된 로컬 debug build만 `-Pieum.siwcPersonalTrialApproved=true`를 명시한다. release는 강제 false다. 활성 APK는 일반 배포용이 아니며 허용된 새 전용 AVD에서만 사용했다.
- 승인 config는 최초 dynamic 등록과 이후 선택된 issued ID 재사용을 구분한다. 이번 AOSP의 유일한 기존 HTTPS handler가 `org.chromium.webview_shell`이어서 사용하지 않았다. 승인 trial의 외부 브라우저만 공식 Firefox로 명시하고 embedded WebView·custom scheme·기존 Codex/다른 앱 credential을 사용하지 않는다.
- `SiwcTrialBudget`은 base-app 보호 DB `siwc-personal-trial-v1.db.sealed`에 승인 하나의 scope/manifest/snapshot binding과 단계별 최초 key/상태를 저장한다. 실제 inference port를 부르기 **전** durable claim을 한다. ANALYSIS와 CONTEXT는 각각 한 slot이며 실패·UNKNOWN·process 중단·새 attempt key도 추가 POST를 허용하지 않는다. 같은 완료 job 재열람은 원 journal을 읽는다. 다른 계정/입력/snapshot으로 binding을 교체할 수 없다.
- native HTTP 관측에는 endpoint 분류·status·POST body 송신 사건 이름만 기록한다. token/header/code/URL query를 기록하지 않는다. 안전한 auth 상태 요약도 boolean·scopes·expiry·registration hash만 반환하며 email/subject/client ID/credential을 반환하지 않는다.
- 실제 완료 응답이 schema에서 거절되면 원 완료 관측은 해당 scope의 암호화 journal에 별도 보존한다. 이것은 COMPLETE나 사용자 승인 영수증이 아니다. source/account가 바뀌면 그 관측도 읽지 못한다. 재호출 없이 원인 확인할 수 있게 한 변경이다.
- opt-in `SiwcApprovedLiveDeviceTrial`은 승인 build+정확한 instrumentation 승인 tag+명시 action이 있어야 실행한다. 일반 회귀에 actual grant/model call을 설치하지 않는다. `AUTH_STATUS`/`OBSERVE`는 재로그인·refresh·추론을 하지 않는다. 입력은 전용 sandbox의 한국어 공개 WAV SHA `69ac671cd328486d14dfdaa413ec0a511c21e5708953fbe3dc2a280a6a2cf20d`의 checked JNI 전사와 합성 업무 snapshot만 선택한다. 분석/맥락은 PLAN으로 표시한 exact key가 있어야 실행한다. 원음 upload0·유료 fallback0이다.

## 시험 환경 준비

자기 workspace의 새 persistent AVD `ieum_siwc_live35_arm64`를 빈 config로 만들었다. 기존 AVD userdata·SDK·모델·서명키를 복제/삭제하지 않았다. grant·issued ID·암호화 token을 재기동 때 잃지 않도록 이번 전용 AVD에는 read-only disposable 모드를 쓰지 않는다. SwiftShader 화면 surface 오류는 보존했고 표준 host GPU 모드에서 surface 오류0·부팅 성공을 확인했다. 보안 설정 변경/인증서 검증 우회는 없다.

Mozilla [공식 설치 안내](https://support.mozilla.org/en-US/kb/install-firefox-your-phone-or-tablet)와 [공식 배포](https://ftp.mozilla.org/pub/fenix/releases/158.0.2/android/fenix-158.0.2-android-arm64-v8a/)를 확인해 Firefox158.0.2 ARM64를 이 AVD에만 설치했다. APK 135361229bytes, SHA256 `46a588b9f33c261e8cda9549426fc0118fde517cb87087633c11000d3e8099d5`. APK v2 서명 검증 성공, certificate DN Mozilla Corporation, certificate SHA256 `a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04`. Google 계정/Play Store 연결이나 새 Android runtime 권한을 승인하지 않았다.

## 실제 첫 진입과 중단 사유

| 단계 | 관측 |
| --- | --- |
| 공식 discovery | 앱 native HTTP200 1회. 실제 공식 metadata 검증 뒤 loopback/PKCE를 준비하고 Firefox ACTION_VIEW를 실행했다. |
| 브라우저 전면 | 실제 `org.mozilla.firefox/org.mozilla.fenix.HomeActivity`를 확인했다. 이것만으로 OpenAI 로그인 화면 표시를 증명하지 않는다. |
| 실제 화면 확인 | 비밀번호/입력창과 email·opaque 값을 제외한 opt-in 화면 진단으로 **Welcome to Firefox**를 확인했다. `By continuing, you agree to the Firefox Terms of Use`와 Mozilla 진단·상호작용 데이터 전송 안내, Continue가 표시됐다. **OpenAI 로그인 화면에는 아직 도달하지 않았다.** |
| 로그인 대기 종료 | 첫 driver는 약302초 후 실패했다. 저장된 최종 상태는 `SIWC_LOGIN_CANCELLED`, signedIn=false, returnRequested=false다. 대기 시간만 보고 EXPIRED라고 단정하지 않고 원 상태를 보존했다. |
| 재기동 상태 조회 | 별도 process의 AUTH_STATUS가 active=false, epoch0, incompleteRegistrationCount0, scopes=[], refreshStored=false를 반환했다. HTTP 사건은 `SIWC_AUTH_DISCOVERY_HTTP_200` 하나다. token BODY_SENT/HTTP 사건0, model slot0. |
| grant·계정 | native 검증 계정/저장 token/발급 client draft0. 원격 등록 완료·실제 grant 발급은 확인되지 않았다. 기존 세션을 추출하거나 자동 재로그인하지 않았다. |
| catalog·추론·파일 | 실제 catalog GET0, 분석 POST0, 맥락 POST0. 실제 두 응답/결과파일/후보 저장은 **미실행**이다. 이전 합성 fixture 성공을 실제 결과로 세지 않는다. |

초기에는 외부 Firefox가 전면이라는 관측만으로 사용자에게 공식 로그인/MFA를 안내했으나, 실제 화면 검사 뒤 **Firefox 최초 약관 단계에서 멈췄음으로 정정**했다. 새 Firefox 약관과 Mozilla 데이터 안내는 승인된 OpenAI SIWC 조건에 포함되지 않으므로 Continue를 누르지 않았다. 이는 서버의 SIWC 거절이나 자동 approval review 거절이 아니다.

현재 필요한 조작은 사용자가 이 **추가 Firefox 약관·설정**을 확인하고 진행 여부를 정하는 것이다. 그 뒤에만 본인 OpenAI 로그인/MFA를 진행한다. 이전 요청은 종료됐으므로 예전 OpenAI 앱 동의 화면에서 추가 승인을 진행하지 않는다. 먼저 현재 등록 상태를 확인하고 새 유효 요청을 준비해야 한다. 사용자 응답 전 같은 grant나 모델 요청을 자동 재실행하지 않는다.

원증거(ignore): `evidence/siwc-real-login-attempt-1.log`(실패1), `siwc-real-login-1.json`, `siwc-real-auth-status-1.{log,json}`(조회 PASS), `siwc-browser-screen-1.{log,json}`(진단 PASS), `siwc-browser-download.json`. 안전한 상태 요약만 host로 가져왔고 browser session/cookie/token/vault 원본은 가져오지 않았다.

## 독립 검증

`siwc-approved-safety-tests.log`: SIWC 전체+G01/G02 집중 **101 PASS/실패0/오류0/SKIP0**, lint 성공. 새 budget8건은 unbound·binding 변경·성공 중복·transport UNKNOWN·process 중단·각 단계1회·완료 journal 재열람·거절된 완료 관측의 보호를 검증한다. HTTP port/OAuth는 이 host 회귀에서 모의 adapter다. 실제 로그인 실패1건과 합산하거나 숨기지 않는다.

승인 활성 APK 안정 복사본은 `deliverables/ieum-code34-siwc-approved-trial-debug.apk`(SHA256 `db33ee97d00aecd791da7d057bbf4743d3f7f1832d2dd86e4058cb5c8d99b17c`), driver는 `ieum-code34-siwc-approved-trial-driver.apk`(SHA256 `a8e6aa65355509f3a3d88e9deb221aace9c7ca0955eaf2b7eba2acb13e13597e`)다. 일반 비활성 build/validation 결과와 구분한다. 기존 b2144b0 APK와 원 테스트 XML/로그도 보존한다. 후속 최종 전체 회귀 결과는 완료 후 별도 기록한다.
