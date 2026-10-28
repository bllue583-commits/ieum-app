# 일반 앱 공개 음성 분석·파일·검토 동선 — 2026-10-28

작업 branch는 `feature/siwc-app-analysis-20261028`, 기준은 `4797899132261b3ff0323ee34bc27934bd2cb800`이다. 이 기준은 회전 복구와 인증 취소 `ee472b3`의 cherry-pick `41ca9df`를 포함한다. 기존 `integration/siwc-runtime-20261026`/b2144b0와 원본 미커밋은 보존했다. 승인 시험의 16개 변경 파일은 해시 manifest를 먼저 기록하고 별도 worktree로 옮겼으며 원본이 그대로임을 재대조했다. 기존 feature/main을 변경하지 않는다. 지원층 회수는 `936ce40`에 분리했으며 아래 최종 시험 근거는 새 앱 동선까지 합친 제품 코드에 적용한다.

## 앱 진입과 지원 범위

계정과 연결 → **승인된 공개 음성 ChatGPT 분석** → `SiwcAppAnalysisActivity`다. instrumentation 전용 명령이 아닌 앱의 일반 화면과 버튼으로 실행한다. 현재 허용 선택은 앱에 들어 있는 **한국어 public-ko.wav 한 개**다. 일반 개인 녹음/임의 경로·메일·문자는 이 동선에 들어오지 않는다. 별도 공개 sandbox이며 기존 업무/실사용 DB를 읽거나 이전하지 않는다.

일반 build는 비활성 설명 화면이다. 명시 로컬 `-Pieum.siwcPersonalTrialApproved=true` debug build만 승인된 개인 공개 시험을 활성화한다. release/validation은 이 값이 false다. Intent/preference/remote flag가 활성 권한·source·endpoint·승인 key를 공급하지 않는다. 기존 앱 내 공식 ChatGPT 연결 화면으로 이동할 수 있지만 로그인·refresh·분석을 자동 시작하지 않는다.

실행 순서는 다음과 같다.

1. 선택한 공개 음성의 실제 기기 JNI 전사. 외부 전송0. 표시 원문을 확인한다.
2. 표시한 공개 합성 담당·회사·통합 시험 업무 및 2026-10-01 10:00 UTC에 별도 명시 결속한다. 사람·업무·시각을 음성에서 만들지 않는다.
3. 검증 계정과 계정별 catalog를 확인한다. 요청한 `gpt-6-astra`/`xhigh`가 없으면 MODEL_UNAVAILABLE이며 다른 모델/유료 API로 대체하지 않는다.
4. 표시 전사·합성 snapshot과 모델·사용량을 확인하고 **분석 exact key**를 별도 체크/버튼으로 전송한다. 로그인은 이 전송 동의가 아니다.
5. commit된 분석과 현재 합성 snapshot으로 별도 맥락 key를 준비한다(네트워크0). 다시 별도 체크/버튼으로 **맥락 1회**를 전송한다.
6. 두 완료 journal을 검증해 AEAD 결과파일을 저장·재읽기한다. `Store.accept(REVIEW_REQUIRED)`와 candidate를 보호 DB transaction으로 저장한다. 이 단계는 사용자 승인0이다.
7. 표시한 요약·새 할일을 다시 명시 검토한다. 기존 native source/hash/revision/policy·수동DONE/메모·현재 result head gate를 통과한 새 TASK/DOCUMENT만 확인 필요 상태로 저장한다. 기존 대상 수정/취소는 자동 적용하지 않는다.
8. 저장된 진행 다시 확인은 catalog/POST 없이 journal·파일/현재 후보·원 사용자 영수증을 읽는다. 이미 검토한 요약을 화면에 다시 표시하고 항목을 재생성하지 않는다.

## 수명·저장·호출 제한

`SiwcAppAnalysisModel`이 foreground 동작을 직렬화하며 화면 재생성에도 같은 owner를 유지한다. 새 화면은 체크박스를 자동 확인하지 않는다. 생성/resume는 port를 열거나 새 stage를 실행하지 않는다. 원문·후보를 SavedState/plaintext 파일에 새로 쓰지 않는다. 프로세스 종료 후에는 명시 재열람으로 복구하며 UNKNOWN/HELD/REJECTED를 새 POST로 재시도하지 않는다.

보존된 `SiwcTrialBudget`은 실제 시험의 `PERSONAL_PUBLIC_20261026` 단일 승인에 계정 scope·manifest·snapshot을 불변 결속하고 분석/맥락 각 stage의 시도권을 요청 전 보호 commit한다. 실패·UNKNOWN·중단·검증 거절도 소모한다. 기존 key와 새 attempt key 모두 추가 사용을 허용하지 않는다. UI에 임의 승인 ID/초기화/우회 retry를 추가하지 않았다. fixture는 별도 namespace/예산이며 실제 사용자 시험 예산을 소비하지 않는다.

`observe`의 안전한 BODY_SENT/HTTP 상태코드 hook과 인증 담당의 선택적 `Call.Factory`·blocked I/O 취소를 함께 보존했다. 쿠키/redirect/자동 retry0과 공식 origin은 유지한다. UI 중단은 즉시 atomic stop을 전달해 HTTP 감시자가 취소를 관측하며, 원격 처리/사용량 취소를 증명한다고 표시하지 않는다. 이미 끝난 분석을 단순 활성 맥락 중단으로 무효화하지 않는다. **명시 완료 stage 철회의 기존 파일-read fence는 유지**한다. REJECTED 관측도 보존한다.

실제 계정 scope는 native 검토 transaction의 current predicate에도 결속했다. 계정이 달라진 후보로 늦은 사용자 저장을 할 수 없다. 현재 source·정책·수동 수정·superseded 결과는 기존 gate가 보류한다. 이미 명시 저장한 로컬 영수증의 재열람은 새 모델 결과/새 계정의 분석 완료로 취급하지 않는다. 프로세스 재기동이나 로그인으로 권한을 자동 확대하지 않는다.

## 검증 기록

최초 집중148 중 PASS147/FAIL1이었다. 완료 stage의 취소를 모두 무시하려던 변경이 기존 `SiwcResultFilesTest.changedSourceRevisionAndCancellationFenceRead`에 걸렸다. 원 XML을 `evidence/siwc-app-focused-1-xml/`에 보존했고 명시 완료 철회의 기존 fence를 복원했다. 수정 후 집중 **148 PASS/실패0/오류0/SKIP0**, lint0오류/37경고(기존35+새 UseKtx 스타일 권고2)다. 앞선 수치와 합산하지 않는다.

승인 debug APK `b35e21c56ae7900fb94c6b884825b9770773578c4e7a7dd9fb9d665842b9fbce`에서 새 앱의 실제 버튼·JNI·Keystore/SQL·파일/native review를 실행했다. **OIDC 서버와 모델 응답은 fixture**이며 실제 OpenAI grant/Astra 호출0이다.

- INITIAL: 실제 JNI1, fixture 분석1+맥락1, 후보1. 사용자 검토 전 review/직접 요약0·기존 파생 항목1이다. 새 명시 버튼 이후 review1·요약1·항목2다.
- 분석 전송 전 화면 재생성은 동일 owner·미확인 체크박스, 후보 상태 재생성은 같은 digest를 보존했다. 회전이 추가 응답/사용자 승인을 만들지 않았다.
- force-stop 후 별도 process REOPENED도 PASS다. JNI0·fixture 응답0, 같은 원 summary receipt, 후보1/review1/요약1/항목2로 앞뒤 수가 같고 저장 요약을 실제 화면에 다시 표시했다.
- 이 2회 instrumentation은 host 시험 수나 이전 core의 고유7개/9회 기기 근거와 합산하지 않는다. 공개 전용 read-only AVD5586만 사용하고 종료했다. GUI5584의 설치·로그인·동의와 다른 작업 기기는 건드리지 않았다.

**최종 고정 제품 코드 전체:** `siwc-app-final-full-1.log` BUILD SUCCESSFUL(5분58초). 앱1247 중 **PASS1243/실패0/오류0/SKIP4**, core27·맥락44 PASS. SKIP4는 기존 T81~T84 외부 과거 합성 입력 부재다. 기존 P3302/P3303 host pin 시험은 실행됐으며 Android JNI/실제 Astra 증거로 세지 않는다. lint Fatal0/Error0/Warning37(기존35+새 UseKtx 스타일 권고2), debug/validation/AndroidTest 빌드 성공. 집중148은 전체의 부분집합이다.

**최종 default APK 기기 회귀:** 고유8개 method/재열람 포함10회 instrumentation 모두 PASS다. 실제 계정과 연결 화면의 새 버튼으로 비활성 앱 화면 진입·단계 실행0을 확인했다. 기존 G01 분석/검토 중단·재생성 후 같은 JNI 취소·공개 결과파일 후보/명시 검토·보고와 force-stop 재열람을 포함한다. 모두 화면 없는 공개 read-only AVD5586에서 실행했다.

**강화된 active fixture driver:** 정상 앱 버튼 왕복/force-stop 재열람을 다시 실행하고, 별도 새 AVD에서 후보 이후 fixture 계정을 로그아웃하는 반례도 PASS였다. 모델 응답2 이후 앱은 HELD로 보류되고 사용자 검토/요약0·기존 항목 수를 그대로 보존했다. 이 그룹은 같은 고유 method의 정상/로그아웃 variant와 재열람 총3회이며 실제 OpenAI grant/Astra 요청0이다. fixture seed는 기존 등록에 실계정이 있으면 거부한다. 최초 active 2회 근거와 합산해 고유 시험 수를 늘리지 않는다. 추가 guard는 instrumentation 소스만 변경했고 고정 제품 APK는 동일하다.

| 산출물(로컬 ignore) | bytes | SHA256 |
| --- | ---: | --- |
| `deliverables/ieum-code34-siwc-app-approved-debug.apk` (승인 개인 시험 활성) | 42171691 | `b35e21c56ae7900fb94c6b884825b9770773578c4e7a7dd9fb9d665842b9fbce` |
| `deliverables/ieum-code34-siwc-app-final-default-debug.apk` (기본 비활성) | 42168806 | `8aeed942234d5e784dde6dfeceec1ebe36f6a04416034f9085239d38d9cdf190` |
| `deliverables/ieum-code34-siwc-app-final-validation.apk` | 38653726 | `4aabc096f294e464dc16d4214b8b8e041c37cafa64c3cc8ce26aba9a737fd33b` |
| `deliverables/ieum-code34-siwc-app-guards-driver.apk` (fixture AVD 전용) | 446433 | `b5f5813d59f1f301c453805f7dc7b98b4b75f18b61c1687075c8f42a889ac128` |

APK 내부 두 ABI JNI·모델 pin을 대조했으며 실제 실행은 AOSP35 ARM64 debug다. validation/x86 runtime·물리 S25는 미실행이다. Node/Python/설계 validator는 변경 없는 선행 core 기록과 구분하며 이번 차수에 중복 실행하지 않았다. 원 XML/lint/JSON은 `evidence/siwc-app-final-{xml,lint.xml,results.json}`, 기기는 `evidence/siwc-app-default-devices-1/`와 `siwc-app-device-guards-{positive,logout}/`다. 원 로그/JSON/XML/APK는 로컬에 별도 보존하며 Git에는 넣지 않는다.

## 실제 계정 시험과 남은 경계

이 차수의 실제 계정 인증·catalog·분석·맥락은 아직 0회다. 마지막 보존 실제 진입은 Firefox 초기 약관/Mozilla 데이터 안내에서 종료됐고, 새로 현재 vault/브라우저 상태를 조회하지 않았다. 기존 SIWC 공개 전사·합성 snapshot 분석1+맥락1 승인과 그 추가 브라우저 조건을 구분한다. 종료된 이전 요청을 재사용하거나 기존 Codex 자격을 추출하지 않는다. 새 앱 동선의 모의 종단을 실제 계정 왕복 성공으로 보고하지 않는다.

새 앱은 기존 실제 시험과 같은 단일 1+1 예산을 재사용한다. 이전 driver의 다른 namespace에서 PLAN/binding이 이미 만들어졌다면 새 입력 결속으로 대체하지 않고 SIWC_TRIAL_BINDING_CHANGED로 보류한다. 예산/원 journal을 지우지 않는다. 실제 시험은 새 앱 동선을 사용하고 이전 driver PLAN과 혼용하지 않는다.

필요한 실제 순서는 현재 진행중 로그인 여부 확인 → 추가 브라우저 초기 조건의 사용자 확인 → 고정 활성 APK 설치/공식 앱 로그인 시작 → 해당 본인 계정 확인·공식 grant → 실제 catalog → 승인된 분석1·별도 맥락1 → 파일/후보/명시 검토/재기동 확인이다. 더 넓은 권한·유료 구매·다른 계정/개인 음성 전송은 이 범위가 아니다. 불확실한 요청은 상태부터 확인하며 자동 재실행하지 않는다.

M2 기존 항목 누적 수정, SIWC 승인 영수증→보고 snapshot, 임의/긴 녹음 포맷, SMS·네이버·S25 운영은 이번 화면 연결로 완료되지 않는다. 강한 최종 admission 이후 취소 전체도 별도 인수다.

공식 기준은 [등록·로그인](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [계정별 catalog와 완료 추론](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview 입력 제한](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)을 10월28일 다시 읽었다. 이 경로는 로컬 전사 후 text 요청이며 원음 upload/transcription API를 추가하지 않았다. 성공 판정은 terminal 완료와 실제 보고된 모델/effort 및 로컬 commit을 구분한다. effort 미보고는 NOT_REPORTED다.
