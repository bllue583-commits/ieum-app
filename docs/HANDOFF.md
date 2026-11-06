# 이음 앱 인수인계 (2026-11-06 14시 기준, v3.5 배포)

> **읽는 순서:** 이 파일(규칙 → 지금 상태 → 다음 할 일 → 함정) → [DESIGN.md](DESIGN.md)(현재 설계, 구현된 그대로) → [DESIGN_V5.md](DESIGN_V5.md)(다음 버전 v3.5 설계) → [OPERATIONS.md](OPERATIONS.md)(예약 작업·우편함·워치독). 앱의 목적·기능·UX는 [PRODUCT.md](PRODUCT.md), 작업 방식·로컬 환경은 [DEVELOPMENT.md](DEVELOPMENT.md).
> 버전별 설계와 구현 대조, 10/30~11/6 세션 기록은 `docs/history/`([DESIGN_V3.md](history/DESIGN_V3.md) v2.6~v3.0, [DESIGN_V4.md](history/DESIGN_V4.md) v3.1~v3.4, [HANDOFF_HISTORY.md](history/HANDOFF_HISTORY.md)). 묶음 지시문·작업자 보고서·검토 원문은 `docs/orchestration/`, `docs/reviews/`.

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
→ 통화별 알림(정리됨/녹음 없음), 저녁 19시 정리 알림, 아침 6시 보고 알림
→ 새벽 05:16 정리 작업(Opus 5.5, tidy.md v6)이 진행 중인 일·합치기·체크할 일 정리 제안, 09:00 통화 목록 검증
```
- 분석은 Claude 구독 한도 방식(API 키 없음). 업데이트는 lean-calls push → `release-apk` → 우편함 릴리스·`app/version.json` → 폰이 1시간 안에 확인해 무인 설치(v3.4). 워치독은 Actions가 아니라 루틴 끝에서 돈다(v3.4).

## 3. 저장소·환경
| 항목 | 값 |
|---|---|
| 앱 저장소 | `github.com/bllue583-commits/ieum-app` — 11/5 08시부터 **공개**(Actions 무료 때문, 비밀은 안 들어 있음). 작업 브랜치 `feature/lean-calls`(원본), `main`은 옛 코드 + `cleanup-branches.yml`만 |
| 우편함 저장소 | `github.com/bllue583-commits/ieum-inbox`(비공개) — `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`, `status/`, `routines/`, `tools/`, `app/version.json`, 릴리스 APK |
| 예약 작업 | 매시간 분석 `trig_016vQxos9z6p1pdPJ28MjrsV`(매시 34분 07~22시, Sonnet 5.5) · 새벽 정리 `trig_01Sp6piuvasw52ZR1KPupumB`(05:16, Opus 5.5) · 통화 목록 검증 `trig_01KyuDNZWkmrQpJj8e8cr661`(09:00, Opus 5.5). 프롬프트는 짧은 안내뿐, 지시문은 우편함 `routines/hourly.md`·`tidy.md`(원본 `docs/routines/`, release-apk가 배포 때 복사). 수정은 사용자 승인됨("예약작업은 너가 수정하면 돼") |
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

## 4. 지금 상태 (11/6 14시)
- **v3.5 「카톡으로 오는 아침 보고」 배포 빌드 1046**(11/6 17시, 서명 e4ee1db4…2c586 그대로, 카카오 키 주입·키 해시 일치 확인; update-smoke는 1차에 AVD 이미지 생성 70분 초과로 취소돼 재실행 통과. device-test는 에뮬레이터가 잠기지 않아 lockedNotice만 실패 — 키 커밋에서도 같음, 시험 스크립트에서 잠금 미성립이면 판정 보류로 바꿀 것). 묶음 r15-0(v3.4.x 선행: 실패 자막 재큐, 녹음 기준 숫자, 약속 문구, 신호등, 알림 묶음, 카드 기한 표현 제거, 업데이트 재알림)·r15-1(카카오 SDK 2.24.0, `pipeline/report/`)·r15-2(새벽 정리 v7·`report_check.py`·워치독)·r15-3(설정 "아침 보고" 묶음, 실행 파라미터)·r15-4(독립 검토 반영). 설계 [DESIGN_V5.md](DESIGN_V5.md) 6절에 다르게 정한 것 19가지.
- **카카오 설정(사용자, 11/6 13시):** developers 앱·Android(패키지 `kr.callbrief`, 키 해시 `uMjit7vmEJD9bHZPkMfPaECNZOM=`)·카카오 로그인 ON·`talk_message` 선택 동의·비밀 `IEUM_KAKAO_APP_KEY` 완료. 아내 계정 멤버 초대는 아내 카카오계정 이메일 확인 뒤(필요한지 미확인 — 첫 연결 때 권한 오류가 나면 그때).
- **예약 작업 프롬프트(11/6 12:47):** 매시간 분석·새벽 정리가 pull 갈라짐이면 `fetch` 뒤 `reset --hard origin/<기본>`으로 맞춤(12:34 실행이 얕은 사본 50/50으로 멈췄음, origin은 정상·force push 없음). 새벽 정리 cron은 v3.5 배포와 함께 04:46으로.
- **배포본:** v3.4 빌드 **1045**(11/6 06:55, 1044에서 안 쓰는 코드 12곳만 지움). 서명 인증서는 1039~1045 모두 `e4ee1db4…2c586`. 단위 시험 전체·lint·화면 규칙·비밀 스캔 통과, device-test 통과(통화 대기 시나리오만 에뮬레이터 한계로 null).
- **아내 폰(11:03 하트비트):** 아직 **v3.1(1040)**. 1045 APK는 07:00에 받아 뒀는데(`updateAvailableCode` 1045) 한 시간이 지나도 설치되지 않았고 `lastUpdateError`는 없다 → 1040을 손으로 설치(사이드로드)했기 때문에 앱이 스스로 하는 첫 설치는 사람이 한 번 눌러야 하는 것으로 보인다(그 뒤부터 무인). **아내가 "새 버전 v3.4" 알림(또는 설정 › 앱 버전 [지금 업데이트])을 한 번 누르면 된다.** 권한 넷(전화 상태·오버레이·설치 허용) 참, 역할 없음, 배터리 제한 없음, EXEMPTED.
- **파이프라인 실측(v3.1 기준, 아직 1042~1045 수정이 폰에 없음):** 최근 24시간 업무 통화 25건 모두 녹음 파일은 있으나 기록과 이어진 것 1건(`noMatch`), 자막 대기 39(가장 오래된 10/31), 자막 실패 누계 12, 처음 정리 남은 녹음 40개(86분). 원인은 긴 통화 자막이 끝나지 않는 것(v3.2 수정판 1042가 고침) — **1045가 깔린 뒤 48시간 지표로 확인해야 한다.**
- **루틴:** 11/5 17시 재생성 뒤 저장소 연결이 빠져 22시까지 분석 0건이던 매시간 작업이 **11/6 07:36 정규 실행에서 정상**(4건 처리, 지시문 v8, `repoOk`·`pushed` 참, `status/last-run-hourly.json`). 새벽 정리는 05:16에 v5 지시문으로 돌았고 내일부터 v6·`last-run-tidy.json`. 워치독은 루틴 끝에서 돈다(ieum-inbox의 Actions 워치독은 지움).
- **장부:** rev 11. 사람·업체가 아직 적다(통화기록과 이어진 기록이 적어서).

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
| v3.5 | 1046 | 11/6 17시 | 카톡 아침 보고(나에게 보내기, 피드 이미지+글), 새벽 정리 04:46·보고서 JSON, v3.4.x 묶음, 검토 반영 |
| v3.4 | 1044·1045 | 11/6 06시·07시 | 모든 전화에 카드(역할 조건 제거), 콜드 캐시, 잠금 창+알림, 카드 글 알고리즘, 루틴 실패 알림 길·워치독 자립, 결과 받기 시각 맞춤, 버전 1시간 안 확인, 지시문 v8 |
세션별 상세는 `docs/history/HANDOFF_HISTORY.md`, 설계·구현 대조는 `docs/history/DESIGN_V3.md`·`DESIGN_V4.md`.

## 5. 다음 할 일 (우선순위순)
1. **아내 폰이 1045가 되는지(최우선).** 11:03 기준 1045를 받아 두고 설치 확인 대기 중인데 아내 폰에 알림이 없다(사용자 11/6 11:26). 아내는 최근 앱 목록을 밀어 정리하는 습관이 있고, v3.1은 확인 알림이 한 번 사라지면 다시 알리지 않는다(세션이 열려 있어 "결과 대기"로 간주). **방법: 아내가 이음 앱을 열면 홈 맨 위 배너의 [지금 업데이트]를 누르면 된다**(안드로이드 확인창 → 설치). 사이드로드 뒤 첫 자가 설치만 확인이 필요하고 그다음부터 무인. 재알림은 v3.5 r15-0에서 고친다. 하트비트 `versionCode` 1045와 새 키 `transcription`·`update`가 생기면 됨. 그래도 안 되면 `lastUpdateError`·`updateAvailableCode`를 본다. "서명이 다르다"는 메시지가 나오면 **지우지 말 것**(저장된 통화가 사라짐; 서명은 1039부터 같음).
2. **1045 설치 뒤 48시간 지표**(기준은 DESIGN.md 9절): 녹음 연결 `recordingGap.matched/withFile` ≥ 0.8, 100초 넘는 통화 완료율 `transcription.long48h.ratePct` ≥ 80, 대기열 39 감소, `callCard` null 아님·`shownOutgoing/eligibleOutgoing` ≥ 90%·`ringToCardMs` p50 < 1000·`fgsRefused` 0, `latency` 정상화(지금 p50 3,912분은 `started_ms` 오염 — `recordTime` 진단으로 원인 확인). 결과에 따라 v3.4.x(카드 위치 상수, 1.0배 배지 카드 "외 1개" 줄 넘김, 첫 벨 창 1회 실패 원인).
3. **루틴 감시:** `status/last-run-hourly.json`이 슬롯마다 갱신되고 `pushed=true`인지 하루 지켜본다. 내일 05:16 새벽 정리가 v6·`last-run-tidy.json`을 남기는지. 폰이 1045가 되면 옛 `status/last-run.json`은 지운다.
4. **사용자 할 일:** S26 체크리스트(`docs/REAL_DEVICE_CHECKLIST.md`, 10분) · 실녹음 골든 받아쓰기 3~5건(설정 › 상태와 도움 › 골든 측정; 숫자는 아라비아 숫자, 충전 중 측정) · v3.5 배포 뒤 cleanup-branches 한 번 더(68개는 11/6 아침에 정리됨, 남은 r15-* 5개; 세션의 자동 권한 검사가 원격 삭제를 막음) · GitHub 비밀 `IEUM_SIGNING_SEED`에 현재 토큰 값 복사(서명 키가 우편함 토큰에 묶여 있음) · 저장소 비공개 복귀 전 Actions 분량 조정(DESIGN.md 11절) · 예약 작업 알림 조정(정상 답도 매시간 옴).
5. **Claude Docs 설계도 갱신:** `docs/DESIGN.md`를 기준으로 Docs 쪽 "자동 흐름"·"결정 사항"·"보고와 화면"·"개인정보와 보안" 절을 옮긴다(옮길 목록은 `history/DESIGN_V4.md` 6절).
6. **v3.5 확인:** 아내 폰 1045 이상 → v3.5 자가 설치 → 아내 폰 설정 › 아침 보고 › [카카오톡 연결하기](아내 계정으로) → 다음 날 05:30 "나와의 채팅" 도착·06시 조용한 앱 알림 → 하트비트 `kakao.sent` 3일, 워치독 조용. (옛 메모:) 설계는 [DESIGN_V5.md](DESIGN_V5.md)(11/6 08:40). 매일 04:46 새벽 정리가 `report/YYYY-MM-DD.json`을 쓰고 아내 폰이 05:30에 카카오 "나에게 보내기"로 최대 4통(각 200자) 전송, 실패면 앱 알림. 착수 조건은 v3.4.x 선행 묶음 배포 + 폰 1045 이상. 사용자가 먼저 할 일은 DESIGN_V5 4절(카카오 developers 앱·동의 항목·비밀 `IEUM_KAKAO_APP_KEY`·팀원 등록). 그 밖의 v3.5 이후 재료는 DESIGN.md 11절과 실녹음 골든 결과.

## 6. 알아 둘 함정
- **예약 작업:** 프롬프트는 저장소 파일을 읽는 짧은 안내다. 지시문은 `docs/routines/*.md`를 고치고 배포(release-apk)하면 우편함에 올라간다. 배포 없이 급히 바꾸려면 ieum-inbox `routines/`에 직접 커밋. 예약 작업을 이 세션에서 만들면 이 세션에 붙은 저장소가 소스로 붙는다 — 다른 세션에서 만들면 ieum-inbox가 빠져 "성공"인데 아무것도 못 한다(11/5 17시). 1시간보다 짧은 간격은 불가. `fire_trigger`·끄기·지우기는 자동 권한 검사가 막는 경우가 있어 사용자가 화면에서.
- **세션 권한:** 자동 권한 검사가 원격 브랜치 삭제, 예약 작업 끄기·지우기·지금 실행, `gh repo edit`, billing API를 막는다. 브랜치는 `cleanup-branches` 워크플로, 저장소 공개/비공개와 결제는 사용자.
- **Actions:** 공개 저장소는 무제한. 비공개면 무료 2,000분/월 소진 + 지출 한도 $0에서 "recent account payments have failed…"로 모든 작업이 단계 없이 실패한다(11/5 03시). 에뮬레이터 시험은 한 번에 약 90분 — 꼭 필요할 때만, 골든 비교는 `only=tg_goldenSet`으로 따로. Firebase 실기기는 하루 5번.
- **CI:** 통합 브랜치에 `[skip ci]` 커밋만 올리면 ci가 안 돈다 → 합친 뒤에는 코드가 바뀐 커밋을 머리에 두고 push. 같은 브랜치에 새 push가 오면 진행 중인 device-test는 취소된다. ci 1차 실패가 Robolectric 화면 시험의 경쟁(IO 코루틴이 메인 루퍼 idle 전에 끝남)이면 `CallsScreen.readHook` 같은 게이트를 쓴다.
- **로컬 빌드:** 클라우드 세션은 CPU 2개 — Gradle은 `flock /home/claude/gradle.lock python3 run_gradle.py …`로 하나씩, 작업자는 컴파일·자기 시험만 로컬, 전체 시험·lint는 ci로. repo.maven.apache.org가 429를 자주 내면 `$GRADLE_USER_HOME/init.d/mirror.gradle`에 구글 미러. Windows는 Robolectric 대부분이 `TEXT_STORE_RECOVERY_REVIEW_REQUIRED`로 실패(앱 문제 아님, 판정은 ci로)하고 `make`가 없어 네이티브 빌드 불가. 배포본은 FLAG_SECURE라 그림 캡처는 device-test 산출물(`CallUi.allowCaptureForTests`)로.
- **작업자(Agent worktree):** 가끔 main(67fac72)에서 시작된다 → 지시문에 "HEAD가 기준 브랜치와 다르면 `git fetch origin && git reset --hard origin/<기준>`". 작업자는 worktree 밖에 못 쓰므로 보고서는 마지막 답으로 받아 `docs/orchestration/reports/`에 저장.
- **업데이트·서명:** v2.4는 Wi-Fi에서만 APK를 받았고(v3.1부터 네트워크 무관), 사이드로드한 폰은 처음 한 번 "이 앱의 설치 허용"을 켜야 한다. 서명 시드가 비어 있어 서명 키가 우편함 토큰에서 파생된다 — 토큰을 바꾸면 서명이 달라져 설치가 실패한다(먼저 `IEUM_SIGNING_SEED` 고정). release-apk가 `certSha256`을 version.json에 넣고 이전 APK와 비교한다.
- **하트비트:** 20:46 같은 시각에 `consent:false`·저장 13%인 1039 하트비트가 오면 시험기가 같은 `status/phone.json`을 덮어쓴 것(워치독 오독 가능). `latency` p50이 수천 분이면 `started_ms` 오염이지 실제 지연이 아니다.
- **우편함:** `results/selftest-*.json`은 inbox-e2e가 검증 후 지우고 폰은 무시한다. `status/calllog.txt`에는 이름·번호가 있다(아내 동의, 비공개) — 작업자 지시문에 "읽지 말 것"을 넣는다. 루틴 실행 로그에 통화 내용이 남지 않게 프롬프트에 명시돼 있다.
- **코드 버릇:** `CallRecordStore`의 이름 매칭은 공백 무시(`REPLACE(name,' ','')`), 별칭도 공백 제거 키. 화면은 XML/Compose가 아니라 코드로 View를 만든다(`CallUi.kt`) — 디자인을 바꾸려면 `CallUi`부터. `ContextLedgerTest`·`CallUiSeniorTest`가 버튼 외 글자 18sp 이상, 영어 상태값 노출 금지를 검사한다.
