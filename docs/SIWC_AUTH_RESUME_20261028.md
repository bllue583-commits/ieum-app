# 인증·추론 파일 왕복 후속 인계 (2026-10-28)

## 기준과 소유 경계

- 로컬 macOS 독립 checkout: `task-7/ieum-app`, JDK17/SDK36/offline Gradle8.13. JVM/lint 실행은 `IEUM_SKIP_ANDROID_NATIVE=1`이며 APK/JNI/실기기 재검증을 의미하지 않는다.
- 후속 branch: `feature/siwc-auth-resume-20261028`, base `5f53fe6ff9bb6cd9b643a593bbc581670d13cfec`. 최초 feature 작업 트리는 clean이었다.
- 읽은 원격 통합 HEAD: `integration/siwc-runtime-20261026` = `b2144b0f1b8c6801a43c40f59ff80034f4b2f133`; 기존 integration은 `c55b0402d8ddd17d370f6f2a28bc5ff13fccda55`. 통합 브랜치에는 native/UI 및 G01/G02 연결이 있으며 담당자의 변경을 이 checkout에 merge하지 않았다.
- 수정 대상은 본 작업 소유 `SiwcAstraProtocol.kt`와 새 `SiwcCallCancellation.kt`/테스트/본 문서뿐이다. 공유 core/DB/앱 UI/G01 파일을 수정하지 않았다. 통합 HEAD의 인증·추론·파일 모듈은 base와 동일하므로 이 후속 커밋만 cherry-pick 가능하다. 다른 통합의 테스트 결과는 이번 실행 수에 합산하지 않는다.

## 수정한 문제와 인수 계약

기존 `SiwcAstraHttp.invoke`는 body의 다음 read 전에 취소·grant 현재성을 검사했다. 네트워크 header 또는 body read가 막혀 있으면 검사도 멈춰 사용자가 중단·로그아웃해도 기존 timeout까지 연결이 유지될 수 있었다. 모델 catalog GET도 동일했다.

이번 변경은 활성 HTTP call에 한해 100ms 간격으로 취소·현재 grant 참조·scope·expiry·승인 key를 검사하고, 무효화 시 OkHttp `Call.cancel()`로 blocked I/O를 끊는다. 검사 예외도 취소 처리한다. 단일 app 내부 daemon scheduler를 공유하며 종료된 작업은 queue에서 제거하고 유휴 thread는 종료한다. 별도 서비스/외부 worker/정기 분석 작업이 아니다. 실제 처리 지연은 OS scheduling 및 현재성 검사 시간에 영향받으므로 100ms 완료 SLA를 주장하지 않는다.

- 취소는 **로컬 연결 종료**다. 원격 처리 취소/사용량 미발생을 증명하지 않는다. 기존 job의 UNKNOWN 또는 CANCELLED_REMOTE_UNKNOWN 보존·자동 재호출 금지 계약을 유지한다.
- 기존 `SiwcApprovedInference.cancel/close`, native/source revision 및 session epoch 검사 계약은 변경하지 않는다. 기존 callback을 그대로 전달하면 된다.
- 모델 완료 terminal, 모델/effort 관측, frozen input, manifest/source binding, 별도 두 호출, AEAD temp→완료파일·journal·native 검토 계약은 변경하지 않는다.
- 기존 `SiwcAstraHttp(grant=..., now=...)` 호출을 유지할 수 있다. 선택적 `Call.Factory` 인수는 모의 network fixture 주입용이며 production 기본은 동일한 고정 공식 origin/쿠키0/redirect0/retry0 client다. endpoint·credential을 Intent/모델 출력/원격 flag로 주입하는 기능을 추가하지 않는다.

## 검증

최초 실행 `evidence/siwc-cancel-resume-1.log`: 71 중 PASS68/FAIL3. 새 interceptor 응답이 Content-Type header를 생략해 SSE gate가 body를 읽기 전에 거부한 fixture 오류였다. 원 XML은 `evidence/siwc-cancel-resume-1-xml`에 보존했다. 제품 gate를 완화하지 않고 fixture의 실제 header를 보완했다.

최종 `evidence/siwc-cancel-resume-final.log/json`: **71 PASS / 실패0 / 오류0 / SKIP0**, lint Fatal0/Error0/Warning35. XML은 `evidence/siwc-cancel-resume-final-xml`에 보존했다. 앞선 실행과 합산하지 않는다. 새 6개 시험은 header 대기 취소, stream 대기 취소/로그아웃, catalog의 header 계정교체/body 권한철회, 완료 후 monitor 해제다. in-process interceptor만 사용하며 DNS/TLS/OpenAI 서버에 접근하지 않는다. 기존 SIWC 인증·job·결과파일 시험도 함께 실행한다.

## 실제 로그인/호출 상태와 다음 조건

이번 실행에서 실제 OpenAI OAuth grant/token/catalog/Astra 호출은 **0회**, 데이터 외부 전송 **0건**이다. 공개 음성 전사·합성 기록 각 1회라는 사용자 범위를 초과하지 않았다. 이 checkout은 비활성 기본 config를 유지하며 기존 Codex 인증/쿠키를 읽지 않았다. 서비스에 요청했다가 거부된 상태가 아니다.

기존 [구체 grant 안내](siwc-auth-module.md#부모가-사용자에게-제시할-최초-grant합성-시험-승인안)를 부모가 확인한 뒤 공식 이음 로그인에서 새 identity/plan/offline_access 동의를 받는 단계가 남아 있다. 현재 전달 내용은 공개 합성 시험 범위이며 새 grant 자체의 구체 승인 증거/실제 issued client·토큰은 이 작업에 제공되지 않았다. 따라서 실제 호출 adapter는 연결되지 않았다. 통합 담당자가 승인된 native config를 연결하고 사용자가 공식 브라우저 동의를 마친 뒤 계정 catalog와 정확한 invocation key/input/model/effort를 확인해야 한다. 토큰을 대화/Git으로 전달하지 않는다.

실제 모델 성공은 terminal 응답과 관측 model/effort 및 두 journal·파일 receipt로 별도 증명해야 한다. 원음 입력 지원, Android/OEM system-browser 복귀, 업무 운영 적격성, earliest_refresh_at 단위 등 기존 미검증 조건을 이번 mock 시험으로 해소했다고 주장하지 않는다.
