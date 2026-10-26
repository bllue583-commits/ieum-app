# 주요 기능 통합 — 로컬 공개 음성 종단

작업 branch는 `integration/major-features-20261026`, 기준 main은 `67fac72ccb8ad63655b281c31dcaaae4680511f9`다. 아래 네 원격 SHA를 확인하고 각각 merge했다. source feature branch와 main은 변경하지 않는다.

| 내용 | 수신 commit |
| --- | --- |
| 미분류 음성 | `1ac76e3314710c155703aa87dee5caee5b0b85c6` |
| 맥락 후보 | `4b9921d2d64a4ddccceac378226fd51502f212cf` |
| 아침 보고 | `43b0360e36e3194dc3a67ade122b93eec0ac4b2f` |
| 이정표·설계 | `8943536defc15fd0c68f9ef293a4b6b9bd0f3176` |

파일 merge 충돌은 없었다. 의미상 미연결이었던 후보 저장, 명시 검토 영수증, 보고 snapshot/checkpoint를 아래 코드로 연결했다. package/versionCode/versionName, frozen 외부 wire, `call_core_schema.sql`, 모델·SDK·서명 identity와 기존 사용자 저장소는 변경하지 않는다.

## 지원하는 앱 내 경로

초기 설정 → Google 계정 관리 → 공개 음성으로 기기 요약 시험의 **통합 시험**을 사용한다. Google 로그인/새 권한/외부 API가 필요하지 않다. 기본 실제 사용자 저장소와 분리된 `integrated-v1` 합성 sandbox이며 기존 두 공개 음성 시험도 유지한다.

1. **음성 분석·후보 확인:** 앱에 포함된 한국어·영어 공개 WAV를 실제 Android JNI Whisper로 처리한다. 미분류 source/version/audio hash와 실제 실행 관측을 유지한다. 정확한 전사·본문 SHA·code-point 인용을 `EventAdapter`로 정규화하고 보호 DB의 후보 ledger에 저장한다. 미확정 사람/업무·시각은 보류하며 사용자 검토 영수증은 0이다.
2. **시험 사람·업무·시각 직접 연결:** 화면에 명시된 합성 담당/회사/업무와 2026-10-01 10:00 UTC 시험 시각을 사용자가 연결한다. 원문의 실제 인용과 현재 workflow link를 결속한 관계 검토 영수증을 저장한다. 같은 STT 결과가 기존 provider/consumer/Coordinator/`Store.accept`를 통과한다. 맥락 후보는 이 시점에도 `canonicalWriteAllowed=false`다.
3. **표시한 요약·할일 확인 후 보고 보관:** 사용자가 표시된 내용을 직접 확인한 별도 명령만 요약/할일을 저장한다. 한 보호 transaction에서 후보 digest·source/hash·user/lock/task/board/link/archive revision을 재검사하고 기존 `createItem`/`reviewOwnSummary` API를 호출한다. 실제 `USER_REVIEW_SUMMARY` receipt와 정확한 native request/result binding을 함께 보존한다.
4. **승인된 snapshot과 아침 보고:** 기존 `CanonicalReviewedDailySource`가 유효한 사용자 검토 요약만 읽는다. 소스 DB에 실제 durable checkpoint를 commit한 다음에만 `COMPLETE/durable cursor`를 반환한다. 전날/최근7일 보고와 outbox를 실제 보호 보고 DB에 저장하고 `MorningDryRun`으로 로컬 이력을 남긴다.

시험 보고 시계는 **2026-10-02 06:00 KST로 명시적으로 가정**한다. 실제 Android 시스템 시각의 정각 예약/휴면 실행을 시험한 것이 아니다. 입력 범위는 항상 PARTIAL이며 SMS/네이버/외부 수집을 완료했다고 표시하지 않는다. dry-run은 서비스 ACK·실제 전송·수신 증거가 아니다.

## 코드와 계약

- `ProtectedContextCodec`: 맥락 전체 상태(events, collisions, 최초 receipts, replacement candidate projection)의 로컬 JSON codec. Java 역직렬화나 외부 model wire를 추가하지 않는다.
- `IntegratedContextStore`: 실제 `ContextRepositoryPort`다. owner와 `AccountStorageContext`를 맞추고 기존 `AndroidSqlPort`의 같은 보호 transaction에서 state revision과 전체 현재 view를 CAS한다. source read 권한·정책·관계·user/item/DONE 상태를 다시 검사한다. source가 만료/철회되면 파생 후보와 active source를 비우며 원 ledger/사용자 값은 덮어쓰지 않는다.
- 정규화 event version은 별도 내구성 순서다. 원 recording sourceVersion, 분석 request/result revision, audio SHA와 본문 SHA를 서로 대체하지 않는다. 동일 audio라도 다른 source는 별도 사건이다.
- `confirmRelation`과 `approveSelection`은 별도 명시 사용자 명령이다. 모델 JSON, CandidateJson, `APPLIED_SAFE_DERIVED`가 이 명령의 확인을 대신하지 못한다. 후보 자체를 canonical SQL patch로 변환하지 않는다. 현재 연결은 원문에서 그대로 뽑은 요약과 확인 필요한 TODO에 한정한다. 일반 의미 추출·서류판/기한 변경·진행 완료를 추론하는 연결이 아니다.
- `IntegratedReviewReceipt`는 정확한 로컬 native 결과와 실제 사용자 요약 receipt를 대조한다. 일치할 때만 그 로컬 요청을 독립적인 미검토 remote 요청으로 중복 보류하지 않는다. 이후 다른 요청/결과, source/hash/revision 또는 정책 변경은 이 예외로 승인되지 않는다.
- `CanonicalReviewedDailySource`의 일반 파일명/제외 규칙은 유지한다. 공개 시험의 파일명 예외는 private public sandbox에서 typed callback으로 전달하는 정확한 bundled source/version/hash만 허용하며, 제외·현재 source·사용자 검토 receipt 검사는 그대로다. 기본 caller의 callback은 false다.
- `LocalReviewedMorningSync`는 외부 collector가 아니다. 기존 보호 소스 DB에 요청 ID/window/generation/prior cursor/basis를 결속한 불변 checkpoint를 실제 저장한다. 미검토/미처리 자료가 있으면 ACCEPTED이며 cursor를 전진시키지 않는다. commit 실패/owner 취소는 COMPLETE를 반환하지 않는다. 같은 요청은 원 cursor로 재조회한다.
- 앱의 `MorningReports`는 이 로컬 checkpoint adapter를 사용한다. 보고 UI의 scope note는 로컬 검토 저장소만 확인했음을 밝힌다. 외부 음성/SMS/메일 수집·외부 dot/high·실제 카카오는 연결하지 않는다.

기존 16 MiB 보호 snapshot/row 한도를 유지하며 history를 임의 삭제하지 않는다. 새 통합 테이블은 기존 account-scoped 소스 DB 안에만 추가한다. report/outbox 테이블은 기존 보고 DB를 재사용한다. source content나 승인되지 않은 후보를 별도 평문 운영 로그에 복제하지 않는다. 원본/모델/SDK/서명키/다른 프로젝트/NAS는 변경하지 않았다.

## 실행 기록과 재현

기존 JDK17/SDK36/BuildTools35/Gradle8.13과 읽기 전용 공개 의존성 cache, 기존 모델/JNI 및 서명을 사용했다. 외부 계정/새 OAuth/유료 API/실제 메시지 전송은 0이다. APK/생성 JNI/모델/기기 로그는 ignore된 로컬 산출물로 둔다.

- 초기 연결 컴파일과 맥락44시험 PASS.
- 통합 집중 회귀 두 실행 각각 앱112 PASS/실패0/SKIP0, 맥락44 PASS. 앱112 안에 신규 보호 DB 통합6개와 기존 미분류15개가 포함되며 합산 고유 시험 수로 세지 않는다.
- 최초 실제 AOSP35 ARM64 통합 데모: JNI2 → 미분류/후보 → 명시 관계 검토 → 별도 내용 승인2 → 현재 승인 snapshot → 일일/7일 보고 r1 두 개 → DRY_RUN_RECORDED 두 개. force-stop PID2169→2361 뒤 추가 STT0, 사용자 검토2/보고 revision/outbox 동일, 로컬 sync COMPLETE. 이는 이후 추가한 최종 CAS/만료 보호 전의 실행이며 최종 APK는 다시 검증한다.
- 최초 기기 실행의 증거 수집 폴더 철자 오류를 수정해 두 공개 JSON을 정상 회수했다. 최초/재실행 instrumentation 자체는 각각 `OK (1 test)`였으며 수집 오류를 실행 실패나 추가 성공으로 합산하지 않는다.
- 마지막 commit fence 보강 전 전체 실행: 앱1094/PASS1090/실패0/오류0/SKIP4, core27 PASS, 맥락44 PASS, debug/validation/기기 시험 APK 및 lint 성공. 이후 최종 source/CAS guard와 추가 회귀를 포함한 전체 실행은 별도로 기록한다.
- 최종 Android debug APK: 41,661,592 bytes/SHA256 `31b4946e6e4c3f7d75254e09b7ef8b1e514bba9d96b1ba0020fff0c920e9b138`. AOSP35 ARM64 최초/force-stop 재실행 각각 `OK (1 test)`, PID2455→2579. JNI2→0, 분석/결속 단계의 사용자 검토 영수증0→내용 승인 후2, 보고 DAILY/ROLLING_7_DAY 각각 r1/PARTIAL, outbox 두 개 모두 DRY_RUN_RECORDED. 두 프로세스의 outbox와 local cursor가 같았다. 설치 APK SHA가 위 빌드와 일치하며 두 ABI의 JNI 및 model pin도 APK 내용과 일치한다. x86 실행과 validation APK runtime은 미실행이다. 작업 전용 AVD는 종료했다.
- Node29 PASS, Python adapter PASS3/SKIP2, 설계 입력 검사12/18 PASS를 각각 재실행했다. 과거 입력이 필요한 Python2시험은 SKIP이며 설계30/40 제품 시나리오 실행으로 세지 않는다.
- 최종 코드 전체 실행 `integration-final-full-2.log`: **앱1095건/PASS1091/실패0/오류0/SKIP4**, core27 PASS, 맥락44 PASS. 미분류24건(기능 branch15+새 통합9)은 이 앱 전체의 부분집합이며 전부 PASS다. SKIP4는 기존 과거 합성 WAV 입력 미준비이며 기존 local engine pin을 제공해 P3302/P3303는 실제 실행했다. 최종 소스의 debug/validation/기기 시험 APK 빌드와 lint가 성공했다. lint Fatal0/Error0/Warning35다. 서로 다른 실행 수를 합산하지 않는다.
- 최종 validation APK: 38,523,290 bytes/SHA256 `523f2588ca0d3f559585fc3627b3a97142bf74fdd49da0fad5dac8ff86da50de`. 빌드 성공이며 실제 실행 증거는 위 debug APK에만 해당한다.
- staged405파일 export scan 발견0, diff whitespace 검사 PASS. 원결과는 `evidence/integration-final-results.json`, `evidence/integration-*.log`, `evidence/integration-device-final/summary.json`, `android/**/build/test-results/`에 보존한다. 새 기능 소스·시험·문서 외 모델/JNI/APK/캐시/키·실사용 DB는 커밋하지 않는다.

전체 검증 명령:

```sh
python3 run_gradle.py --offline --log integration-final-unique.log \
  :app:assembleDebug :app:assembleValidation :app:assembleDebugAndroidTest \
  :context-reconciliation:test :core:test :app:testDebugUnitTest :app:lintDebug --continue
```

기기 시험 클래스는 `kr.callbrief.IntegratedMajorFeaturesDeviceTest`다. 별도 허용된 합성 AVD에서 `integrationExercise`에 고유 ID를 넣어 실행하고, 앱 force-stop 후 같은 ID와 `integrationReplayOnly=true`로 다시 실행한다. 실제 JNI 여부는 컴파일 성공과 구분한다. 읽어오는 증거는 그 시험의 공개 합성 결과만이다.

미검증/남은 범위: 물리 S25·일반 개인 녹음, 실제 SAF/OEM grant·휴면/재부팅/정각06 운영 인수, 실제 SMS/네이버 수집·high/dot 의미 분석, M2의 일반 문서판/기한·취소/부분 이행을 canonical 저장까지 적용하는 adapter, 실제 배송/ACK/수신, Windows 실행. 기존 plans의 맥락30/보고40 인수는 새 통합 시험과 별개이며 임의로 PASS로 바꾸지 않는다.
