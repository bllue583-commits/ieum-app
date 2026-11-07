# 이음 앱 인수인계 (2026-11-07 18시 기준, v3.5 빌드 1046 배포·아내 폰 v3.5, v3.6 「배터리」 설계 완료·미구현)

> **읽는 순서:** 이 파일(규칙 → 지금 상태 → 다음 할 일 → 함정) → [DESIGN.md](DESIGN.md)(현재 설계, 구현된 그대로) → [OPERATIONS.md](OPERATIONS.md)(예약 작업·우편함·워치독). 앱의 목적·기능·UX는 [PRODUCT.md](PRODUCT.md), 작업 방식·로컬 환경은 [DEVELOPMENT.md](DEVELOPMENT.md).
> 버전별 설계와 구현 대조, 10/30~11/6 세션 기록은 `docs/history/`([DESIGN_V3.md](history/DESIGN_V3.md) v2.6~v3.0, [DESIGN_V4.md](history/DESIGN_V4.md) v3.1~v3.4, [HANDOFF_HISTORY.md](history/HANDOFF_HISTORY.md))와 v3.5 설계 기록 [DESIGN_V5.md](DESIGN_V5.md)(구현 완료, 기록용). **다음 버전 v3.6 「배터리를 녹이지 않는 자막」의 설계는 [DESIGN_V6.md](DESIGN_V6.md)(11/7, 구현 전 — 이 설계대로 개발한다).** 묶음 지시문·작업자 보고서·검토 원문은 `docs/orchestration/`, `docs/reviews/`.

## 0. 작업 규칙 (꼭 지킬 것)
- **보고는 항상 한국어로.** 중간 보고, 최종 보고, 질문, 마무리 문구까지 예외 없음.
- **결정은 묻지 말고 알아서 정해서 반영.** 정한 내용은 보고에 한 줄로 적기. 기준은 "편리함 중점의 최선". (약관 동의·외부 게시·결제처럼 되돌리기 어려운 일만 먼저 확인)
- 사용자는 "시작/중지"만 지시한다. 개발·테스트·판단은 Claude가 직접. **사용자에게 테스트를 부탁하지 말 것**(UI도 직접 실행·스크린샷으로 판단). 예외는 실기기에서만 되는 것(S26 체크리스트, 실녹음 골든 받아쓰기).
- 하위 작업자는 프로젝트 설정대로 **Opus 중간**. Fable이나 Opus high 이상을 쓰면 사용자에게 알릴 것.
- **설계도 먼저, 그다음 개발.** 설계와 구현이 다르면 어느 한쪽을 기준으로 삼지 말고 생각해서 맞는 쪽으로 구현한 뒤 설계도를 고친다. 정한 것은 설계도(DESIGN.md)의 결정 표에 남긴다.
- **버전 규칙:** 이름은 `docs/VERSION` 한 줄. 사용자에게 보이는 변경을 배포할 때마다 0.1씩(v3.9 다음은 v4.0). 문서만 바꾸거나 같은 회차 재배포는 그대로. 빌드 번호(10xx)는 업데이트 순서에만 쓴다. 올릴 때 `docs/UPDATE_NOTE.txt` 첫 줄도 "v3.x: …"(폰 알림 문구).
- **인수인계 문서(이 파일)의 원본은 작업 브랜치 `feature/lean-calls` 하나뿐.** 시험 브랜치에서는 고치지 말고, CI 통과 후 lean-calls에 합친 다음 lean-calls에서만 고친다. 세션을 마칠 때 "지금 상태"와 "다음 할 일"을 갱신해 push.
- 비밀번호·토큰·키는 파일이나 대화에 남기지 말 것. 저장소 생성·클라우드 권한·결제·공개/비공개 전환은 사용자가 직접.
- **"통과했다"고 보고하기 전에 종료 코드와 결과 파일을 직접 확인할 것.** (`| tail`로 종료 코드를 가리면 실패를 놓침)
- 지나치게 방어적이거나 필요 없는 코드는 지워도 된다.

## 1. 앱 목적과 사용자 (상세는 PRODUCT.md)
- 아내가 건설사 직원으로 업무 통화를 하루 약 2시간 → 저녁이면 통화 내용을 기억하지 못함 → 통화를 자동으로 정리해 체크할 일·요약·업체/사람 맥락을 보여 주고, 전화가 올 때 그 상대와 약속한 일을 카드로 띄우는 앱.
- 실사용 기기: Galaxy S26, 안드로이드 16(아내 폰). 관리자는 남편(사용자). UI 기준: 50대 남자가 봐도 편하고 어떤 기능이 있는지 바로 알 수 있게, 세련되게.

## 2. 핵심 흐름 (전부 자동, 버튼 없음 — 상세는 DESIGN.md 3절)
```
통화 종료 → 녹음 폴더에서 새 녹음 발견 → 폰에서 Whisper(large-v3-turbo q5_0)로 자막
→ 자막+맥락만 비공개 우편함 ieum-inbox/inbox/ 에 업로드 (녹음 파일은 폰 밖으로 안 나감)
→ 매시간 예약 작업(매시 34분 07~22시, Sonnet 5.5, 지시문 우편함 routines/hourly.md v8) 분석 → results/, ledger/, story/today.json
→ 폰이 루틴 예정 시각 +3·+8·+15분에 결과를 받아 통화 목록·체크할 일·장부 갱신, 결과 파일 삭제
→ 통화별 알림(정리됨/녹음 없음), 저녁 19시 정리 알림
→ 새벽 04:46 정리 작업(Opus 5.5, tidy.md v7)이 진행 중인 일·합치기·체크할 일 정리 제안 + 아침 보고서 report/YYYY-MM-DD.json
→ 폰 05:30 보고서 받기 → 카카오톡 "나와의 채팅"에 피드 이미지 1통 + 오늘 챙길 일 글 1통(v3.5) → 06시 앱 알림(카톡 성공 날은 조용히), 09:00 통화 목록 검증
```
- 분석은 Claude 구독 한도 방식(API 키 없음). 업데이트는 lean-calls push → `release-apk` → 우편함 릴리스·`app/version.json` → 폰이 1시간 안에 확인해 무인 설치(v3.4). 워치독은 Actions가 아니라 루틴 끝에서 돈다(v3.4).

## 3. 저장소·환경
| 항목 | 값 |
|---|---|
| 앱 저장소 | `github.com/bllue583-commits/ieum-app` — 11/5 08시부터 **공개**(Actions 무료 때문, 비밀은 안 들어 있음). 작업 브랜치 `feature/lean-calls`(원본), `main`은 옛 코드 + `cleanup-branches.yml`만 |
| 우편함 저장소 | `github.com/bllue583-commits/ieum-inbox`(비공개) — `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`, `status/`, `routines/`, `tools/`, `app/version.json`, 릴리스 APK |
| 예약 작업 | 매시간 분석 `trig_016vQxos9z6p1pdPJ28MjrsV`(매시 34분 07~22시, Sonnet 5.5) · 새벽 정리 `trig_01Sp6piuvasw52ZR1KPupumB`(04:46, Opus 5.5) · 통화 목록 검증 `trig_01KyuDNZWkmrQpJj8e8cr661`(09:00, Opus 5.5). 프롬프트는 짧은 안내뿐, 지시문은 우편함 `routines/hourly.md`·`tidy.md`(원본 `docs/routines/`, release-apk가 배포 때 복사). 수정은 사용자 승인됨("예약작업은 너가 수정하면 돼") |
| 코드 | `android/app/src/main/java/kr/callbrief/`(패키지 `kr.callbrief`), 파이프라인 `pipeline/`, 화면 `pipeline/screens/`, 통화 카드 `pipeline/call/`, Whisper JNI `android/app/src/main/cpp/ieum_whisper.cpp` |
| 설계도(Claude Docs) | "이음 설계도 개정안" https://claude.ai/artifact/7VeJmAZtXJ8Z5dEJ6sZNqT — **이 저장소의 `docs/DESIGN.md`가 기준이고 Docs 쪽이 오래됐다**(6절 5번) |
| 테스트용 GCP | `ieum-test-2946626`, 서비스 계정 `ftl-runner@…`(Firebase Test Lab, OIDC) |

### GitHub 워크플로
| 파일 | 하는 일 | 소요 |
|---|---|---|
| `android-ci.yml`(ci) | JVM 단위 테스트 + lint. push마다 | 약 5분 |
| `release-apk.yml` | 같은 커밋의 ci가 통과하면 서명 APK 빌드 → 우편함 릴리스·`version.json`(promptVersion·tidyVersion·certSha256) + `routines/*.md`·`tools/watchdog.py` 복사. lean-calls·main에서만 | 약 4분 |
| `device-test.yml` | 에뮬레이터: 모델 확인, 한국어 Whisper, 화면 9장, 알림, 통화 카드 시나리오 11개. 보통은 작은 모델(base), 음성 인식 파일이 바뀐 push나 수동 `full=true`면 전체 | 10~40분 |
| `real-device-test.yml` | Firebase 실기기(S26). **하루 5번 한도**, 수동 | 약 40분 |
| `inbox-e2e.yml` | 합성 통화를 inbox에 넣고 분석 결과를 폰 파서로 검증 후 삭제 | 약 2분 |
| `update-smoke.yml` | 직전 APK 위에 덮어 설치해 시작·자가 설치 확인(수동) | 약 30분 |
| `cleanup-branches.yml` | lean-calls에 합쳐진 `feature/*` 삭제(수동, `dry_run`). main에만 있음 | 1분 |
- 수동 실행(Run workflow)은 **main에 있는 워크플로만** 버튼이 보인다. 다른 것은 REST `gh api repos/<owner>/<repo>/actions/workflows/<file>/dispatches -f ref=feature/lean-calls`.
- 로컬 빌드·시험 환경(클라우드 세션·Windows·Mac)은 [DEVELOPMENT.md](DEVELOPMENT.md).

## 4. 지금 상태 (11/7 18시)
- **배터리 문제(11/7 15:49 사용자 보고, 실사용 1순위 문제):** 아내 폰 배터리 사용량의 약 60%가 이음(다른 앱 1% 수준). 원인은 자막 작업 스케줄 — v3.5 설치 뒤 20시간 동안 자막 작업 892분(가동률 87%), 2분 조각당 330초라 10분 실행에 조각 1개만 끝나고 둘째 조각은 매번 버려짐, 실행마다 모델 재로드, 배터리 상태는 "부족 아님"만 봄, 발열은 SEVERE만 봄, v3.5 재큐 70건이 방아쇠. 설계는 [DESIGN_V6.md](DESIGN_V6.md)(Opus 2차 토론 반영 확정): 전원 꽂힘(PLUGGED, 삼성 배터리 보호 상태 포함)일 때 몰아서, 배터리로는 오늘 3분 이하 통화만 최신순으로 하루 20분 또는 5%, 실행에 안 들어갈 조각은 시작하지 않기, 한 실행 안 세션 이어 쓰기(모델 로드는 녹음마다였음), 영구 실패 제외, 스레드 = 쓸 수 있는 코어 수(느림의 유력 원인은 background cpuset, 추정), 하트비트 `power`, 발열·무선·아침 규칙, 배포 직후 q8_0·i8mm A/B. 기대 효과 60% → 15% 안팎(추정). **코드는 아직 그대로라 소모가 계속된다** — 5절 1번.
- **배포본 v3.5 「카톡으로 오는 아침 보고」 빌드 1046**(11/6 17시, 서명 e4ee1db4…2c586 그대로, 카카오 키 주입·키 해시 일치 확인; update-smoke는 1차에 AVD 이미지 생성 70분 초과로 취소돼 재실행 통과. device-test는 에뮬레이터가 잠기지 않아 lockedNotice만 실패 — 키 커밋에서도 같음, 5절 4번). 묶음 r15-0(v3.4.x 선행: 실패 자막 재큐, 녹음 기준 숫자, 약속 문구, 신호등, 알림 묶음, 카드 기한 표현 제거, 업데이트 재알림)·r15-1(카카오 SDK 2.24.0, `pipeline/report/`)·r15-2(새벽 정리 v7·`report_check.py`·워치독)·r15-3(설정 "아침 보고" 묶음, 실행 파라미터)·r15-4(독립 검토 반영). 구현 그대로의 설계는 [DESIGN.md](DESIGN.md)(11/7 v3.5 반영, 아침 보고는 3.12절), 설계 기록 [DESIGN_V5.md](DESIGN_V5.md) 6절에 다르게 정한 것 19가지.
- **아내 폰(11/7 검증):** v3.5 최신(1046)으로 올라옴 — 1045 업데이트 대기는 해결됨. 통화기록 983/983 저장, 빠짐 0. **카카오톡 미연결**(아내가 설정 › 아침 보고 › [카카오톡 연결하기]를 한 번 눌러야 함, 그 전에는 06시 앱 알림만).
- **파이프라인 실측(11/7):**
  - 처음 정리 남은 분량 64분 → 126분으로 늘었다. v3.5 FAILED 재큐로 실패 자막 약 70건이 대기열에 다시 들어간 영향으로 추정. 줄마다 총 2회 상한이라 한두 번 돌면 줄어야 하고, 계속 늘면 자막 실패 원인을 조사한다.
  - 녹음-통화 연결 23 → 59건으로 개선 중이나 절반 수준. 1분 넘는 업무 통화 중 녹음이 없는 것 21건.
  - 자막 대기 32, 자막 실패 누적 58.
  - 대조표 `status/calllog.txt`의 "10/1 이후 전체" 목록이 2일째 비어 있다(원인 미확인, 5절 3번).
- **카카오 설정(사용자, 11/6 13시):** developers 앱·Android(패키지 `kr.callbrief`, 키 해시 `uMjit7vmEJD9bHZPkMfPaECNZOM=`)·카카오 로그인 ON·`talk_message` 선택 동의·비밀 `IEUM_KAKAO_APP_KEY` 완료. 아내 계정 팀원 등록이 필요한지는 첫 연결 때 권한 오류가 나면 그때 확인.
- **예약 작업:** 새벽 정리 cron은 04:46(`46 4 * * *`), 지시문 tidy.md v7(9-1 아침 보고서). 매시간 분석·새벽 정리 프롬프트는 pull 갈라짐이면 `fetch` 뒤 `reset --hard origin/<기본>`으로 맞춤(11/6 12:47). 워치독은 루틴 끝에서 돈다(v3.5 규칙 3개 추가: 아침 보고서 없음, 카톡 2일 연속 안 감, 카톡 연결 풀림).
- **장부:** 사람·업체가 아직 적다(녹음과 통화기록 연결이 절반 수준이라).

### 버전 기록
| 버전 | 빌드 | 배포 | 큰 변화 |
|---|---|---|---|
| v2.0~v2.5 | ~1033 | 11/4 | 구독 한도 분석, 장부 원본=저장소, 처음 정리, 통화기록 전부 저장, 자가 진단, 하트비트 |
| v2.6 | 1034 | 11/5 08시 | 처음 정리 관문, 발견 경량화, 무인 설치, 정확도, 지시문 v6, 감시 |
| v2.7 | 1035 | 11/5 09시 | Activity 하나, 즉시 반응 부품, 알림 [다 됐어요] |
| v2.8 | 1036(추정) | 11/5 오전 | 번호 키 통일, 상대 카드 저장소, 사람 화면 |
| v2.9 | 1037(추정) | 11/5 오전 | 전화 상태 감지, 통화별 알림, 결과 10분 체인 |
| v3.0 | 1038 | 11/5 오후 | 통화 중 이음 카드(오버레이), 발신 번호 서비스 |
| v3.1 | 1039·1040 | 11/5 21시 | 녹음 공백 판정, 워치독 규칙, 무인 설치 완성, 처음 설정 필수/선택, 지연 실측 |
| v3.2 | 1041·1042 | 11/5 23시·11/6 01시 | 홈은 요약·오늘 통화 이야기, 파일 이름 우선 짝짓기, 긴 통화 자막 완주(A1~A5·A8) |
| v3.3 | 1043 | 11/6 03시 | 숫자 가드·확인 필요 배지, 토큰 확률, 골든 측정 화면, 지시문 v7, 합치기 되돌리기, 18sp 강제 |
| v3.4 | 1044·1045 | 11/6 06시·07시 | 모든 전화에 카드(역할 조건 제거), 콜드 캐시, 잠금 창+알림, 카드 글 알고리즘, 루틴 실패 알림 길·워치독 자립, 결과 받기 시각 맞춤, 버전 1시간 안 확인, 지시문 v8 |
| v3.5 | 1046 | 11/6 17시 | 카톡 아침 보고(나에게 보내기, 피드 이미지+글), 새벽 정리 04:46·tidy.md v7·보고서 JSON, v3.4.x 묶음, 검토 반영 |
| v3.6 | (미배포) | 설계 11/7 | 배터리: 자막 전력 정책(충전 중 몰아서, 배터리는 오늘 짧은 통화 예산), 낭비 제거, 하트비트 `power`, 발열 조절, 효율 A/B — [DESIGN_V6.md](DESIGN_V6.md) |
세션별 상세는 `docs/history/HANDOFF_HISTORY.md`, 설계·구현 대조는 `docs/history/DESIGN_V3.md`·`DESIGN_V4.md`.

## 5. 다음 할 일 (우선순위순)
1. **v3.6 배터리 구현·배포([DESIGN_V6.md](DESIGN_V6.md) 5절 순서대로).** 키 커밋 r16-0 → r16-1(전력 정책·낭비 제거, `CallTranscriptionRunner`·`CallTranscriptionStore`·`FirstPass`·`AndroidWhisperEngine`) ∥ r16-2(하트비트 `power`·화면·워치독) → ci → device-test(에뮬레이터 `dumpsys battery` 시나리오) → 배포. `docs/VERSION` 3.6, `UPDATE_NOTE` 첫 줄은 설계 5절. 배포 뒤 48시간은 6절 확인 기준(하트비트 `power.today.battery.min` ≤ 25, 아내 폰 배터리 화면 이음 ≤ 10% — 사용자 스크린샷 1장). 그 뒤 r16-3 효율 A/B(골든 "전력 측정" 모드)는 측정 결과로 채택. 구현 중 다르게 정한 것은 DESIGN_V6 8절에.
2. **아내 폰 카카오 연결.** 아내가 설정 › 아침 보고 › [카카오톡 연결하기](아내 계정)를 한 번 누르면 다음 날 05:30부터 "나와의 채팅"으로 온다. 확인: 하트비트 `kakao.linked=true`, 다음 날 `kakao.sent` 증가·`report.source="routine"`, 06시 앱 알림은 조용한 것. 3일 연속이면 v3.5 확인 끝(DESIGN.md 9절). 권한 오류가 나면 developers 앱에 아내 계정 팀원 등록.
3. **녹음-통화 연결률과 자막 실패 원인 조사(v3.7 후보).** 연결 59건은 절반 수준, 1분 넘는 업무 통화 녹음 없음 21건, 자막 실패 누적 58. 하트비트 `callLogMatch`(못 이은 이유 계수)·`recordingGap`·`transcription.failReasons`·`requeuedFailed`로 원인을 나눈다. 처음 정리 남은 분량(126분)이 재큐 두 바퀴 뒤에도 늘면 실패 사유부터 본다.
4. **대조표 "10/1 이후 전체" 빈 목록 원인.** `status/calllog.txt`의 그 구역은 KST 날짜가 바뀐 뒤 첫 틱에만 채운다(`CallLogReport`, OPERATIONS.md 4절). 2일째 비어 있으니 날짜 바뀜 판정이나 그 틱의 업로드 생략(같은 내용 해시) 쪽을 본다. 파일 내용은 읽지 말고 커밋 이력의 크기·시각으로 확인.
5. **device-test lockedNotice:** 에뮬레이터가 잠기지 않으면(lock screen on=false) 실패 대신 판정 보류(null)로 바꾸기(`tools/device/call_card_test.sh`).
6. **cleanup-branches(r15-* 5개):** main의 `cleanup-branches` 워크플로를 수동 실행(세션 자동 권한 검사가 원격 삭제를 막음).
7. **`IEUM_SIGNING_SEED`:** GitHub 비밀에 현재 우편함 토큰 값을 복사한 뒤에만 토큰 교체(서명 키가 토큰에서 파생됨, 사용자 할 일).
- 그 밖(전과 같음): S26 체크리스트(`docs/REAL_DEVICE_CHECKLIST.md`, 10분)·실녹음 골든 받아쓰기 3~5건(사용자), 저장소 비공개 복귀 전 Actions 분량 조정(DESIGN.md 11절), 예약 작업 알림 조정, Claude Docs 설계도를 `docs/DESIGN.md` 기준으로 갱신(옮길 목록은 `history/DESIGN_V4.md` 6절), 옛 `status/last-run.json` 정리.
- **완료:** 아내 폰 1045 업데이트 대기(11/7 v3.5 1046으로 올라옴, 업데이트 재알림은 v3.5에 들어감), v3.5 배포(11/6 17시), 새벽 정리 cron 04:46 변경, 카카오 developers 설정, 매시간 분석 저장소 연결 복구(11/6 07:36).

## 6. 알아 둘 함정
- **예약 작업:** 프롬프트는 저장소 파일을 읽는 짧은 안내다. 지시문은 `docs/routines/*.md`를 고치고 배포(release-apk)하면 우편함에 올라간다. 배포 없이 급히 바꾸려면 ieum-inbox `routines/`에 직접 커밋. 예약 작업을 이 세션에서 만들면 이 세션에 붙은 저장소가 소스로 붙는다 — 다른 세션에서 만들면 ieum-inbox가 빠져 "성공"인데 아무것도 못 한다(11/5 17시). 1시간보다 짧은 간격은 불가. `fire_trigger`·끄기·지우기는 자동 권한 검사가 막는 경우가 있어 사용자가 화면에서.
- **세션 권한:** 자동 권한 검사가 원격 브랜치 삭제, 예약 작업 끄기·지우기·지금 실행, `gh repo edit`, billing API를 막는다. 브랜치는 `cleanup-branches` 워크플로, 저장소 공개/비공개와 결제는 사용자.
- **Actions:** 공개 저장소는 무제한. 비공개면 무료 2,000분/월 소진 + 지출 한도 $0에서 "recent account payments have failed…"로 모든 작업이 단계 없이 실패한다(11/5 03시). 에뮬레이터 시험은 한 번에 약 90분 — 꼭 필요할 때만, 골든 비교는 `only=tg_goldenSet`으로 따로. Firebase 실기기는 하루 5번.
- **CI:** 통합 브랜치에 `[skip ci]` 커밋만 올리면 ci가 안 돈다 → 합친 뒤에는 코드가 바뀐 커밋을 머리에 두고 push. 같은 브랜치에 새 push가 오면 진행 중인 device-test는 취소된다. ci 1차 실패가 Robolectric 화면 시험의 경쟁(IO 코루틴이 메인 루퍼 idle 전에 끝남)이면 `CallsScreen.readHook` 같은 게이트를 쓴다.
- **로컬 빌드:** 클라우드 세션은 CPU 2개 — Gradle은 `flock /home/claude/gradle.lock python3 run_gradle.py …`로 하나씩, 작업자는 컴파일·자기 시험만 로컬, 전체 시험·lint는 ci로. repo.maven.apache.org가 429를 자주 내면 `$GRADLE_USER_HOME/init.d/mirror.gradle`에 구글 미러. Windows는 Robolectric 대부분이 `TEXT_STORE_RECOVERY_REVIEW_REQUIRED`로 실패(앱 문제 아님, 판정은 ci로)하고 `make`가 없어 네이티브 빌드 불가. 통화 카드 창은 FLAG_SECURE라 카드 그림 캡처는 device-test 산출물(`CallUi.allowCaptureForTests`)로.
- **작업자(Agent worktree):** 가끔 main(67fac72)에서 시작된다 → 지시문에 "HEAD가 기준 브랜치와 다르면 `git fetch origin && git reset --hard origin/<기준>`". 작업자는 worktree 밖에 못 쓰므로 보고서는 마지막 답으로 받아 `docs/orchestration/reports/`에 저장.
- **업데이트·서명:** v2.4는 Wi-Fi에서만 APK를 받았고(v3.1부터 네트워크 무관), 사이드로드한 폰은 처음 한 번 "이 앱의 설치 허용"을 켜야 한다. 서명 시드가 비어 있어 서명 키가 우편함 토큰에서 파생된다 — 토큰을 바꾸면 서명이 달라져 설치가 실패한다(먼저 `IEUM_SIGNING_SEED` 고정). release-apk가 `certSha256`을 version.json에 넣고 이전 APK와 비교한다.
- **배터리(11/7):** 자막 작업은 `requiresBatteryNotLow`만 보고 끝나면 바로 다음 실행을 건다(`CallTranscriptionRunner.kt:339,378`). 실패 재큐(v3.5)처럼 대기열을 한꺼번에 늘리는 변경은 폰이 하루 종일 Whisper를 돌리게 만든다 — v3.6 전력 정책이 들어가기 전에는 대기열을 늘리는 배포를 하지 말 것. 조각당 시간이 540초에 가까우면 둘째 조각이 매번 버려진다. `transcription.decode`는 누적 통계(날짜별 초기화 없음)라 그날 양으로 읽지 말 것.
- **하트비트:** 20:46 같은 시각에 `consent:false`·저장 13%인 1039 하트비트가 오면 시험기가 같은 `status/phone.json`을 덮어쓴 것(워치독 오독 가능). `latency` p50이 수천 분이면 `started_ms` 오염이지 실제 지연이 아니다.
- **우편함:** `results/selftest-*.json`은 inbox-e2e가 검증 후 지우고 폰은 무시한다. `status/calllog.txt`에는 이름·번호가 있다(아내 동의, 비공개) — 작업자 지시문에 "읽지 말 것"을 넣는다. 루틴 실행 로그에 통화 내용이 남지 않게 프롬프트에 명시돼 있다.
- **코드 버릇:** `CallRecordStore`의 이름 매칭은 공백 무시(`REPLACE(name,' ','')`), 별칭도 공백 제거 키. 화면은 XML/Compose가 아니라 코드로 View를 만든다(`CallUi.kt`) — 디자인을 바꾸려면 `CallUi`부터. `ContextLedgerTest`·`CallUiSeniorTest`가 버튼 외 글자 18sp 이상, 영어 상태값 노출 금지를 검사한다.
