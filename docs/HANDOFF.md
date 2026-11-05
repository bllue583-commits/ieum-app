# 이음 앱 인수인계 (2026-11-06 새벽 기준, v3.4 배포)

> 다음 세션은 이 파일부터 읽고 "4-3. v2.0"과 "6. 다음 할 일"부터 진행하세요. 설계도(Claude Docs)가 기준이며, 모든 변경은 설계도를 먼저 고친 뒤 개발합니다(11/4 사용자: "모든 과정에서 설계도를 철저히 반영").
> 앱의 목적·기능·UX 설계 상세는 [PRODUCT.md](PRODUCT.md)에 있습니다.
> **11/6 새벽 (Opus 오케스트레이션, 이 세션 마무리): v3.2 수정판·v3.3·v3.4를 lean-calls에 합쳐 배포(4-11~4-13).** 설계는 DESIGN_V4 2-1~2-3(v3.2), 3절(v3.3, 다르게 정한 것 3.5), 4절(v3.4, 다르게 정한 것 4.6). 예약 작업은 짧은 안내 프롬프트가 ieum-inbox `routines/hourly.md`·`tidy.md`를 읽는 방식으로 바꿨고, release-apk가 배포 때마다 `docs/routines/*.md`와 `tools/inbox/watchdog.py`를 우편함에 올린다(3절 표). 다음 세션은 **6절**부터.
> **11/5 밤 (Opus 오케스트레이션): v3.1 「스스로 말하는 이음」 lean-calls에 합쳐 배포(4-10).** 묶음 r12-1~r12-5 모두 Opus 작업자, 통합 `feature/r12-v31` ad03b16, 전체 단위 시험 582건·lint 통과. 아내 폰은 11/5 18:43 하트비트 기준 아직 v2.4(1033) — 손 설치 안내함(DESIGN_V4 1.4). 다음은 6절.
> **11/5 저녁 (Fable 평가 세션 종료 → 다음 세션이 v3.1부터 개발):** v3.0을 Opus 검토자 넷 + 설계 검토자로 평가해 **[DESIGN_V4.md](DESIGN_V4.md)**(v3.0 평가, v3.1~v3.3 설계)를 썼다. 다음 세션은 **6절 → DESIGN_V4.md 0절(착수 조건)·1.4절(사용자 할 일)·2절(v3.1 묶음 r12-1~r12-5)** 순으로 읽고 시작한다. 사용자 결정(11/5 18:26): 새 버전 확인·다운로드와 **Whisper 모델(570MB) 다운로드 모두 네트워크 종류 무관**(Wi-Fi 조건·스위치 없음, "무조건"), 앱 열 때 즉시, 진행 상황을 설정·홈에 표시(DESIGN_V4 2.2-5). 아내 폰은 아직 v2.4(1033)이며 "업데이트 눌러도 안 됨"은 v2.4가 Wi-Fi에서만 APK를 받기 때문 — Wi-Fi에서 앱을 열어 두라고 안내함.
> **11/5 오후 (Opus): v3.0 lean-calls에 합쳐 배포(4-9).** v2.6~v3.0 모두 배포. 다음은 아내 폰 권한 확인과 저장소 비공개 복귀(6절).
> **11/5 09시 (Opus 오케스트레이션 세션):** 저장소를 공개로 바꾼 뒤 **v2.6(빌드 1034)·v2.7(1035) 배포, v2.8은 lean-calls에 합쳐 배포 중.** v2.8 통합 기록은 "4-7", 다르게 정한 것은 설계 [DESIGN_V3.md](DESIGN_V3.md) 5.5절. v2.9 묶음 셋(r10-1~3)은 `feature/r10-v29` 위에서 작업자가 개발 중. 로컬 빌드는 메이븐 429 때문에 `$GRADLE_USER_HOME/init.d/mirror.gradle`(구글 미러, 저장소 밖)을 둠 — "7. 알아 둘 함정" 참고. 설계도는 개발하면서 같이 검증하고 다르게 정한 것을 설계 쪽에 적는다(사용자 11/5).
> **11/5 08시 (Fable 오케스트레이션 세션 종료 → Opus가 이어받음):** v2.6은 lean-calls에 합쳐 배포 대기(ca9acf2), v2.7은 코드 완성(`feature/r8-v27` 964fd1b, 기기 시험 대기), v2.8은 묶음 셋이 브랜치에 올라와 통합 대기. **"4-6"·"4-7"과 "6. 다음 할 일"부터 읽으세요.** 작업자 지시문·보고서·통합 기록은 `docs/orchestration/`에 있습니다(사용자: "handoff 만들어주고 오케스트레이션 종료, 오푸스에게 이어가게").
> **11/5 새벽 (v2.2~v2.5 마무리):** "4-4"에 v2.4 배포, v2.5 완성(배포는 GitHub Actions 결제 차단으로 대기), 예약 작업 세 개, 워치독 설치를 적었습니다. 6절 0번(v2.5 배포)부터 하세요.
> **11/5 (Fable 세션, v2.6~v3.0 설계):** v2.5 이후의 개발 지시는 [DESIGN_V3.md](DESIGN_V3.md)입니다(버전마다 큰 변화 하나: v2.6 처음 정리 관문·무인 설치·정확도, v2.7 Activity 하나와 즉시 반응, v2.8 상대 카드와 사람 화면, v2.9 통화 직후 정리, v3.0 통화 중 이음 카드). 설계도(Claude Docs)의 다른 절과 다르면 이 파일이 우선합니다. 현재 앱 검증 원문은 `docs/reviews/20261105_ui.md`, `20261105_pipeline.md`, `20261105_data.md`입니다. 진행 방식은 DESIGN_V3.md 2절(기기 시험은 v2.7과 v3.0에서만 기다림, 나머지는 ci 통과 후 배포).
> **11/4 낮 (채팅 세션, 설계도 개정):** 설계도에 "3회차 상세 설계 (11/4 낮, 종합 검토 반영)" 절이 추가됐습니다("남은 일" 바로 앞). 다른 절과 다르면 그 절이 우선하며, 사용자 결정 대기였던 항목(토큰 위치, 개인 문자, 낮 시간 자막, 새벽 Opus 빈도, 18sp 규칙, Activity 구조, promises 삭제)은 그 절의 "결정 사항" 표로 모두 확정됐습니다(사용자: "편리함 중점으로 정하라"). 묶음 r3-1~r3-9의 상세 지시와 묶음별 "확인 기준"이 거기 있으니, 3회차 통합 브랜치(`feature/r3-integrate`)를 합치기 전에 그 절과 대조하고, 사용자가 할 일 두 가지(비밀 `IEUM_SIGNING_SEED`에 현재 토큰 값 복사, "병합 후 브랜치 자동 삭제" 켜기)는 완료 보고에 적어 주세요. 종합 검토 원문은 [REVIEW_20261104_B.md](REVIEW_20261104_B.md)입니다.

## 0. 작업 규칙 (꼭 지킬 것)
- **보고는 항상 한국어로.** 중간 보고, 최종 보고 모두 예외 없음.
- **결정은 묻지 말고 알아서 정해서 반영.** 정한 내용은 보고에 한 줄로 적기. (단, 약관 동의·외부 게시·결제처럼 되돌리기 어려운 일은 먼저 확인)
- 사용자는 "시작/중지"만 지시함. 개발·테스트·판단은 Claude가 직접 함.
- **테스트도 Claude가 직접.** 사용자에게 테스트를 부탁하지 말 것. UI/UX도 직접 실행·스크린샷으로 판단해 개선.
- 하위 작업자: 프로젝트 설정(11/4)은 "워커는 opus 중간". Fable이나 Opus high 이상을 쓰면 사용자에게 알릴 것.
- **버전 규칙(11/4 사용자):** 이름은 `docs/VERSION` 한 줄. v2.0부터 사용자에게 보이는 변경을 배포할 때마다 0.1씩 올림(v2.1 … v2.9, v3.0). 문서만 바꾸거나 같은 회차 재배포는 그대로. 빌드 번호(10xx)는 업데이트 순서에만 씀. 올릴 때 `docs/UPDATE_NOTE.txt` 첫 줄도 "v2.x: …"로.
- **설계도와 구현이 다르면(11/4 사용자):** 어느 한쪽을 기준으로 삼지 말고 생각해서 맞는 쪽으로 구현한 뒤 설계도를 고친다. 정한 것은 설계도 "v2.0 대조 결정" 절에 남긴다.
- 설계도를 먼저 고치고 → 설계도대로 개발. 병렬 개발 권장.
- 지나치게 방어적이거나 필요 없는 코드는 지워도 됨.
- 비밀번호·토큰·키는 절대 파일이나 대화에 남기지 말 것. 저장소 생성, 클라우드 권한 부여는 사용자가 직접.
- **"통과했다"고 보고하기 전에 종료 코드와 결과 파일을 직접 확인할 것.** (`| tail` 등으로 종료 코드를 가리면 실패를 놓침 — 10/30에 한 번 잘못 보고함)
- **인수인계 문서(이 파일)의 원본은 작업 브랜치 `feature/lean-calls` 하나뿐.** 시험 브랜치(`feature/…`)에서는 이 파일을 고치지 말고, CI 통과 후 lean-calls에 합친 다음 lean-calls에서만 고칠 것. 합친 시험 브랜치는 원격에서 바로 지움. 나중에 lean-calls를 main에 합치면 그때부터 main이 원본(11/4 사용자).
- 진행 안내, 질문, 마무리 문구까지 모두 한국어로(11/4 사용자 재확인). 설계도를 먼저 고치고 설계도대로 개발(11/4 "모든 과정에서 설계도를 철저히 반영").

## 1. 앱 목적과 사용자
- 아내가 건설사 직원으로 업무 통화를 하루 약 2시간 함 → 저녁이면 통화 내용을 기억하지 못함 → 통화를 자동으로 정리해 할 일·요약·업체/사람 맥락을 보여주는 앱.
- 사용 기기: Galaxy S25 (아내 폰). 현장은 하나, 협력업체·직원과 통화가 많음.
- **UI/UX 기준:** 50대 남자가 봐도 편하고, 어떤 기능이 있는지 바로 알 수 있게. **사용자 의견(10/30): "UI가 미관상 너무 엉망" → 다음 1순위 작업.**

## 2. 핵심 흐름 (전부 자동, 버튼 없음)
```
통화 종료 → 통화 녹음 감지 → 폰에서 Whisper로 자막 생성
→ 자막만 비공개 저장소 ieum-inbox/inbox/ 에 업로드 (녹음 파일은 폰 밖으로 안 나감)
→ Claude 예약 작업(매시간 정각, 07~23시, Sonnet 5.5)이 분석 → ieum-inbox/results/ 에 결과
→ 폰이 결과를 받아 통화 목록·할 일·업체/사람 장부 갱신, 결과 파일 삭제
→ 저녁 7시 정리 알림, 아침 6시 보고 알림
```
- 분석은 **Claude 구독 한도 방식**(API 키 불필요). API 키 방식은 v2.0에서 코드와 설계에서 모두 삭제.
- 앱 업데이트: `feature/lean-calls`에 push → `release-apk` 워크플로가 빌드해 ieum-inbox 릴리스에 올리고 `app/version.json` 갱신 → 폰 앱이 자동 업데이트.
- 우편함 토큰은 빌드 시 Secret `IEUM_INBOX_TOKEN`에서 주입(설치 직후부터 연결됨).

## 3. 저장소·환경
| 항목 | 값 |
|---|---|
| 앱 저장소 | `github.com/bllue583-commits/ieum-app` (비공개), 작업 브랜치 `feature/lean-calls` |
| 우편함 저장소 | `github.com/bllue583-commits/ieum-inbox` (비공개) — `inbox/`, `results/`, `app/version.json`, 릴리스 APK |
| 예약 작업(루틴) | 세 개(11/6 기준): 매시간 분석 `trig_016vQxos9z6p1pdPJ28MjrsV` 매시 34분 07~22시 Sonnet 5.5, 새벽 정리 `trig_01Sp6piuvasw52ZR1KPupumB` 05:16 Opus 5.5, 통화 목록 검증 `trig_01KyuDNZWkmrQpJj8e8cr661` 09:00 Opus 5.5. 앞의 둘은 11/6 이 세션에서 다시 만듦(옛 것은 ieum-inbox 연결이 빠져 17시부터 분석 0건, 사용자가 옛 것 삭제). **프롬프트는 짧은 안내뿐이고 지시문은 ieum-inbox `routines/hourly.md`·`routines/tidy.md`(원본 `docs/routines/`, release-apk가 배포 때 자동 복사)**. 검증 작업은 프롬프트가 원본. 루틴 추가·수정은 사용자 승인됨(11/4, 11/5 "예약작업은 너가 수정하면 돼") |
| 안드로이드 코드 | `android/app/src/main/java/kr/callbrief/` (패키지 `kr.callbrief`), 파이프라인은 `pipeline/` |
| 장부(DB) | `pipeline/CallRecordStore.kt` — `companies`, `people`, `person_aliases`, `call_links`, `task_companies`, `site_memo` |
| 분석 요청/결과 | `pipeline/ClaudeCallAnalyzer.kt` (요청 구성, 결과 검증·필터) |
| 화면 | `HomeActivity`, `TodayCallsActivity`, `CallTasksActivity`, `CompaniesActivity`(업체·사람 보기), `CallSettingsActivity`, 공통 스타일 `CallUi.kt` |
| Whisper 네이티브 | `android/app/src/main/cpp/ieum_whisper.cpp` |
| 설계도 | Claude Docs "이음 설계도 개정안: 기기 Whisper 자막과 Claude 분석 자동화" — https://claude.ai/artifact/7VeJmAZtXJ8Z5dEJ6sZNqT |
| 테스트용 GCP | `ieum-test-2946626`, 서비스 계정 `ftl-runner@…` (Firebase Test Lab, OIDC 인증) |

### GitHub 워크플로
| 파일 | 하는 일 | 소요 |
|---|---|---|
| `android-ci.yml` (ci) | JVM 단위 테스트 + lint | 약 5분 |
| `release-apk.yml` | 서명 APK 빌드 → ieum-inbox 릴리스·version.json (자동 업데이트) | 약 4분 |
| `device-test.yml` | 에뮬레이터: 모델 확인(SHA-256), 한국어 Whisper(t3·m4a), 화면 6장 스크린샷, 알림. 평소엔 작은 모델(ggml-base-q5_1)로 빠른 시험, 큰 모델+속도 측정 전체 시험은 음성 인식 파일(`cpp/`, `AndroidWhisper*`, `CallTranscription*`, `PipelineAudio*`, `PipelineModel*`, `build_android_whisper.py`)이 바뀐 push나 수동 실행 `full=true`일 때만 | 빠른 시험 약 10~15분 / 전체 약 40분 |
| `real-device-test.yml` | Firebase 실기기(최신 Galaxy S, 현재 S26). **하루 5번 한도.** 수동 실행(`repeat`, `model`) | 약 40분 |
| `inbox-e2e.yml` | 공개 합성 통화를 inbox에 넣고 → 분석 결과를 폰 파서로 검증 후 삭제 | 약 2분 |
| `update-smoke.yml` | 직전 릴리스 APK 위에 새 APK를 덮어 설치해 시작 확인 (수동 실행) | 약 30분 |
- 같은 브랜치에 새 push가 오면 진행 중인 device-test는 취소됨(동시 실행 제한).

### 여러 컴퓨터에서 이어 하기 (Windows 회사 PC ↔ 집 Mac)
- **GitHub가 유일한 원본.** 작업 단위가 끝날 때마다 `feature/lean-calls`에 커밋·push하고, 세션을 마칠 때는 이 파일의 "다음 할 일"을 갱신해 push. 다른 컴퓨터에서는 `git pull` 후 이 파일부터 읽기.
- 로컬에만 있는 것(빌드 산출물, `robolectric-jar/`, `gradle-user/`, 에뮬레이터)은 컴퓨터마다 따로 준비. 코드·문서는 전부 저장소에 있음.
- Mac 준비(처음 한 번): Homebrew로 `git gh python@3.12`, JDK 17(`brew install --cask temurin@17`), Android cmdline-tools(`brew install --cask android-commandlinetools`) → `sdkmanager`로 아래 Windows와 같은 패키지(시스템 이미지는 Apple Silicon이면 `arm64-v8a`) → `gh auth login` → `git clone -b feature/lean-calls https://github.com/bllue583-commits/ieum-app.git`. Mac에는 `make`가 있어 Whisper 네이티브 빌드와 Robolectric 단위 테스트도 로컬에서 될 가능성이 높음(미확인).

### 이 PC(Windows) 로컬 도구 (10/30 저녁 다시 설치: Git·gh·Python 3.12는 winget, `gh auth login` 완료, 저장소는 `%USERPROFILE%\ieum-app`)
- Android SDK: `%USERPROFILE%\Android\Sdk` (platform 36, build-tools 35, NDK 28.2.13676358, cmake 3.22.1, emulator, `system-images;android-34;google_apis;x86_64`), 에뮬레이터 이름 `ieum34`
- JDK 17: `%USERPROFILE%\tools\jdk-17` (Gradle 툴체인이 17을 요구)
- Robolectric 런타임: 저장소의 `robolectric-jar/` (git 제외), `python tools/prepare_host_tests.py robolectric-jar/android-all.jar`로 준비
- **주의:** Windows에서는 Robolectric 단위 테스트 대부분이 `TEXT_STORE_RECOVERY_REVIEW_REQUIRED`로 실패함(테스트용 AtomicFile 흉내가 Linux 이름 바꾸기 방식을 전제 → `.bak` 잔존). 앱 문제 아님. **단위 테스트 판정은 GitHub ci 결과로.**
- Windows에서 Whisper 네이티브 빌드는 `make`가 없어 불가 → APK는 GitHub `release-apk` 산출물 사용.
- 배포본은 화면 캡처 차단(FLAG_SECURE, 의도된 개인정보 보호). 에뮬레이터 확인은 `uiautomator dump`로 글자 확인, 그림 스크린샷은 device-test/real-device-test 결과물(`CallUi.allowCaptureForTests`)로.
- Git Bash에서 adb에 `/sdcard/...` 경로를 줄 때는 `MSYS_NO_PATHCONV=1` 필요.

명령 예시 (Git Bash):
```bash
export JAVA_HOME="C:\\Users\\bllue\\tools\\jdk-17" ANDROID_SDK_ROOT="C:\\Users\\bllue\\Android\\Sdk" IEUM_SKIP_ANDROID_NATIVE=1
python run_gradle.py --log local-$(date +%H%M%S).log :app:compileDebugKotlin
```
```bash
$HOME/Android/Sdk/emulator/emulator.exe -avd ieum34 -no-audio -no-snapshot -gpu swiftshader_indirect
```

## 4. 지금까지 완료된 것
- 자동 흐름 코드 (녹음 감지 → 자막 → 분석 → 할 일 → 알림), "정리 동의" 시 실제 녹음 수집 시작
- 구독 한도 분석 (우편함 + 매시간 예약 작업)
  - **10/30 수정:** 루틴에 ieum-inbox 저장소가 빠져 있어 그동안 한 건도 분석하지 못했음 → 소스에 추가, 수동 실행으로 시험 통화 2건 분석·push 성공 확인
- 자동 업데이트 + 자동 빌드·배포 (현재 v1013)
- 예전 기능 정리 (코드 21,000줄 → 5,800줄, 앱 40MB → 약 5MB)
- 새 디자인 1차, 처음 설정을 한 단계씩 안내 (5단계: 녹음 폴더 → 정리 동의 → 모델 → 알림 → 배터리)
- 아침 보고 알림 실기기 확인 (업체명 포함: "• [△△철강] 김민수 님께 견적서 보내기 — 오늘까지")
- Whisper 실기기 속도 (S26, Android 16): 모델 574MB, **녹음 1분당 85초(6스레드), 통화형(침묵 건너뛰기) 72초**. 빠른 연산 모드는 오히려 느려서 끔. 발열 시 자동 일시정지.
- 맥락 장부: 현장 상황 메모, 업체 장부(공종), 사람 장부(소속·직함), 통화별 맥락을 Sonnet에 전달, 안전장치(자막 근거 없는 할 일·이름 버림, AI는 완료 처리 안 함)
- **10/30 추가:**
  - 사람 이름 고치기: 홈 "업체·사람 보기" → 업체 상세 "사람 (n)" 목록 / 목록 맨 아래 "업체 없는 사람 (우리 직원 등)" → [이름 고치기]. 옛 이름은 `person_aliases`에 별칭으로 남아 같은 오인식도 같은 사람에 연결. 고친 이름은 통화 목록·아침 보고·Sonnet 이름 목록에 바로 반영(`CallRecord.linkedPerson`).
  - 업체 카드에 "사람·메모 자세히 보기 ›" 안내
  - device-test가 장부를 채우고 업체 목록·상세 화면도 스크린샷
- **10/30 검증:** ci 통과, 에뮬레이터 6개 통과, S26 실기기 전 항목 통과
- 설계도에 이름 고치기·해결 항목 반영

## 4-1. 11/4 Mac 세션 (집 Mac, 저장소 `~/Claude/ieum-app`)
- **실사용 확인:** 아내 폰에 앱이 설치돼 10/30 밤부터 실제 요청이 올라옴(11/2~11/3에 15건).
- **주간 한도로 분석 중단:** 11/1~11/3 매시간 예약 분석이 전부 `ROUTINE_RUN_FAILURE_REASON_USAGE_LIMIT_REACHED`(주간 한도, 11/4 23:00 UTC 초기화)로 6초 만에 실패. 11/3 밤 사용자가 실행 기록에서 "다시 시도"해 15건 처리. 개발 세션과 예약 분석이 같은 구독 한도를 쓴다는 점을 계속 주의.
- **버그 수정:** 실제 통화가 모두 "통화 시각: 시각 미상"으로 분석에 감. 녹음 파일 이름의 시각이 `local_files.filename_time_candidate_ms`에만 있고 자막 단계는 비어 있는 `recordings.original_started_at_ms`만 읽었음 → `CallTranscriptionStore` select에서 둘을 합침, `CallRecordStore` 열 때 기존 기록 보정.
- **분석 지연 표시:** 3시간 넘게 기다린 기록은 "정리가 늦어지고 있어요, 순서대로 정리해요"(`CallUi.state(CallRecord)`), 저녁 보고에 "아직 정리 중인 통화 N건", 보고 문구 "체크할 일" 통일.
- **설정 화면:** 구독 방식이면 "통화 정리 방식" 칸 자체를 숨김(사용자 요청, `apiKeyGroup`).
- **장부의 원본 = 저장소 장부 (11/4 사용자 결정, 설계도 "장부의 원본" 절):** `ieum-inbox/ledger/ledger.json`(rev, 현장 메모, 업체, 사람 id·이름·소속·직책·별칭·잠금)을 예약 작업이 시간순으로 기록을 처리하며 직접 고침. 폰은 매시간 결과 적용 → 장부 rev가 오르면 받아 반영(`CallRecordAnalysisRunner.takeLedger`, `CallRecordStore.applyLedger`), 결과의 메모는 쓰지 않음. 사람은 **전화번호(메일은 주소) 기준**: 폰이 번호마다 `p_…` id를 만들고(`ledgerIdFor`, `people.ledger_id`), 번호는 폰에만, 요청에는 `counterpartId`만. [이름 고치기]는 `ledger_edits` → `ledger/edits/<id>.json`으로 올라가고 폰에서는 `name_locked`로 장부 반영 전까지 유지.
- **처음 정리(10월 1일부터, 묶음 시간순):** 코드 `FirstPass.kt`, `CallRecordAnalysisRunner.runInbox`. 시작 시각은 앱 시작 때 `FIRST_PASS_START_MS`에 한 번 기록. 10/1 이전 녹음은 `BEFORE_START`로 건너뜀(그 전에는 녹음 폴더 전체를 최신부터 거꾸로 자막 만들고 있었음). 지난 녹음은 오래된 것부터 자막(`nextQueued(oldBefore)`), 충전 중이나 배터리 50% 이상일 때만. 지난 기록은 15건씩 `inbox/b-<첫 시각>-<해시>.json` 묶음으로, 앞선 기록이 다 준비된 만큼만(`oldestOldJobMs`, 문자·메일 읽기 위치), 2묶음까지. 결과는 묶음마다 `{"id","results":[…]}`. 체크할 일은 최근 7일 기록만. 남은 지난 기록이 없으면 `FIRST_PASS_DONE`. 처음에 만든 "맥락 정리(ctx-) 한 번" 방식은 같은 날 사용자 결정으로 폐기.
- 통화기록(READ_CALL_LOG)·문자(장문·사진 문자 MMS 포함, 개인 제외 번호 적용) 권한은 앱을 열 때 한 번 요청 + 처음 설정 카드 + 설정 화면. 요청 메시지에 전화번호는 넣지 않음("전화부에 없는 번호" + 상대 id).
- **예약 작업 지시(11/4 갱신):** pull → 장부 읽기 → edits 반영·잠금 → 새 기록 먼저, b- 묶음 2개까지, 시간순 → 기록마다 분석 후 장부 갱신 → 결과·장부(rev+1) 한 커밋 → 충돌 시 rebase 후 재적용. 옛 형식(상대 id 없음)도 처리. 스크립트 일괄 생성과 파일 내용 출력 금지 명시(11/3 실행이 15건을 스크립트 하나로 49초 만에 만들고 자막을 로그에 찍었음).
- **11/4 새벽 추가(같은 날 사용자 결정):**
  - 통화기록 대조 규칙: 녹음 시각이 통화기록 시각의 1분 전~2분 후, 길이 차이 20% 또는 15초 이내, 파일 이름의 번호나 이름이 맞아야 함, 후보가 둘 이상이면 연결 안 함(`FirstPass.match`). 녹음 없는 통화는 수신·발신이고 길이 있는 것만 셈.
  - 제한된 설정: 문자·통화기록 권한을 한 번 요청한 뒤에도 없으면 처음 설정 카드와 설정 화면이 "앱 정보 → ⋮ → 제한된 설정 허용" 안내와 [앱 정보 열기]를 보여 줌(`ReadingPermission`). **실기기 확인 전.**
  - 새벽 정리(설계도 "새벽 정리" 절): 처음 정리가 끝난 뒤 밤(21~05시)에 폰이 `tidy/state.json`(열린 체크할 일과 사용자 수정 잠금, 기록 요약을 해시 키로)을 하루 한 번 올리고, 05시 루틴이 장부 `issues`(진행 중인 일)와 업체·사람 합치기(`mergedInto`)를 장부에 쓰고 체크할 일은 `tidy/proposal.json`(rewrite, merge, done_suggested)으로 제안. 폰은 AI가 만든 그대로인 항목에만 적용(`applyTidy`), 합친 항목은 상태 `merged`로 모든 목록에서 빠짐. 진행 중인 일은 업체 상세와 아침 보고(최대 3줄)에 표시. 요청 파일에 `recordKey`(기록 id 해시) 추가, 매시간 루틴은 기존 이슈에 붙이고 새 이슈는 `issueSuggestions`로 제안만.
- **문자 점검 결과(사용자 요청):** 10/30 구현은 SMS만 읽어 장문(LMS)·사진 문자(MMS)를 통째로 놓쳤음 → 추가. 설계도의 주기(15분)를 실제(30분)에 맞춤.
- **업데이트 안내 문구와 배포 안전장치(11/4 사용자가 workflow 권한 부여):** 폰의 "새 버전" 알림 문구는 `docs/UPDATE_NOTE.txt` 첫 줄(한국어)에서 읽음. 사용자에게 보이는 변경을 배포할 때마다 이 파일을 고칠 것. `release-apk`는 같은 커밋의 `ci`가 통과해야만 배포하고, 실패하거나 40분 안에 안 끝나면 배포하지 않음.
- Mac에는 JDK·Android SDK가 아직 없음 → 판정은 GitHub ci/device-test로. 작업 브랜치에서 CI 확인 후 `feature/lean-calls`에 합쳐야 폰 자동 업데이트(release-apk는 lean-calls·main에서만).

## 4-2. 11/4 밤샘 검토 반복 (설계도 "검토 기록" 절이 원본)
- **1회차 → v1026**: Fable 검토서(`docs/REVIEW_20261104.md`)와 갈라진 세션 재확인. 통화 시각 미상의 진짜 원인(자동 발견 `finalizeDiscoveryAlias`가 파일 이름 시각을 NULL로 넣음, 실제 요청 16건 모두 미상이었음) 수정, 요청의 id를 기록 키(해시)로(문자 기록 id에 번호가 있었음), 결과 적용 한 작업 이름으로 일원화와 중복 적용 금지, 깨진 결과는 bad/, 409 재시도, 처음 정리 12시간 조용해야 끝, 인용 비교 정규화와 기한 형식 검사, 개인 번호 녹음은 통화기록 대조로 폰에서 삭제, 대조 자료(통화기록 이름·방향·실제 시각, 같은 상대 최근 7일 업무 문자·메일 원문 3,000자, 같은 날 같은 업체 기록), 설정 "앱 버전", 분석 멈춤 알림, 새벽 정리 팔로업(create).
- **2회차 → v1027**: 하위 작업자 셋(맥락 정확도, 안정성·개인정보, 화면·비용, Sonnet). 앱을 안 열면 자막이 안 되던 문제(싱크가 HomeActivity에서만 설정됨) 수정, 자막 5분 조각 저장과 이어 하기(`call_transcription_parts`), 장부를 먼저 받고 결과를 통화 시각 순으로 적용, 적용 실패 파일은 남겨 3번째에 bad/, 장부·제안 실패 격리, 401/403/404면 "연결이 끊겼어요" 알림, update가 기한을 지우던 문제, 요청 날짜를 통화 시각으로, 같은 제목·최근 끝낸 일 재생성 금지, 새벽 팔로업도 AI 소유, 아침 보고는 열린 체크할 일 전부(요청 날짜, BigText), 권한은 처음 설정 카드에서 이유 먼저(자동 요청 없앰, 차단됐을 때만 제한된 설정 안내), 화면 글 가운데 점·말줄임표 제거, 7일 넘은 지난 기록 요청에서 열린 일 제외, 새벽 자료는 첫 정리 뒤 14일+이슈 기록만.
- **예약 작업(11/4 새벽 상태)**: 매시간은 빈 실행 즉시 종료, atMs 순, 소스는 ieum-inbox만(앱 저장소 제외). 새벽 정리는 Opus 5.5(`claude-opus-5-5`), 첫 실행 전체 대조와 팔로업 지시. 새벽 정리도 11/4 10시에 소스를 ieum-inbox만으로 바꿈(지시문은 같음). 추론 수준(medium)은 API로 못 바꿔 사용자가 claude.ai/code/routines에서 설정해야 함.
- **미룬 것**: 보호 저장 파일 `.new` 복구(ProtectedDatabase, 저장 핵심이라 실기기 검증 후), 사람 하나의 여러 키(번호와 메일), 묶음 구성 저장, 설정 화면과 처음 설정 카드 정리, [다 됐어요] 되돌리기, 약속 섹션 합치기, 시스템 지시 파일 분리(요청 크기), 설치 실패 표시, 릴리스 비디버그 빌드, 서명 시드 분리(사용자 확인 필요), 배포가 CI를 기다리게(워크플로 권한 필요), inbox-e2e 파일 이름 검증(워크플로 권한 필요), 우편함 이력 정리(강제 푸시 시험 먼저).
- **사용자 결정이 필요한 것**: 개인 판정 번호를 폰이 기억해 다음부터 안 올리기, 매시간 분석을 업무 시간만, 새벽 Opus 빈도.
- **원격 브랜치**: `feature/review-r1`, `feature/review-r2`는 lean-calls에 합쳐졌고, `review/20261104`의 유일한 변경(`docs/REVIEW_20261104.md`)은 lean-calls에 같은 내용으로 있음. 셋 다 지워도 되지만 이 세션의 자동 권한 확인에서 원격 삭제가 두 번 막혀 남아 있음. 사용자가 `git push origin --delete feature/review-r1 feature/review-r2 review/20261104`로 지우면 됨.
- **11/4 아침 확인**: 매시간 분석이 07:03에 정상 실행(1건), 저장소 장부가 처음 생김(rev 1, 옛 형식 요청이라 사람·업체는 아직 0). 07:54에 폰이 결과를 받아 감. 새벽 정리 첫 실행(05:07)은 처음 정리가 진행 중이라 할 일 없이 끝남.

## 4-3. v2.0 (11/4 낮, 클라우드 세션, 기준 문서 `docs/REVIEW_20261104_B.md`와 설계도 "개정 3")
- 진행: 종합 검토 B → 작업자 병렬 개발(멈춤 방지, 정확도, 화면, 정리) → Opus 독립 검토 지적 수정 → 설계도 대조(Opus) → 차이 개발(파이프라인, 화면·알림·업데이트, 감시, 요청 v3) → v2.0. 시험 브랜치 `feature/r3-integrate`에서 합쳐 CI·device-test 통과 후 lean-calls로.
- 주요 변경: 앱 시작·부팅·업데이트 때 작업 등록과 하루 자가 복구, 새 녹음 최신순 발견·10/1 이전 제외·통화기록 변화 즉시 발견·150초 재발견, 자막 2분 조각·중지 시 즉시 멈춤·모델 한 번만 읽기(JNI open/transcribeChunk/close)·환각 문구 제거, 결과 받은 기록 재전송 금지, 알림 날짜 고정, 서명 시드 폴백(`IEUM_SIGNING_SEED`), 배포 전 인증서 지문 비교, 이름 키·공유 번호·업체 별칭·중복 판정(숫자 다르면 다른 일)·AI 메모·인용 검증·통화기록 점수 대조, 요청 v3(format 3, promptVersion 3, 달력·용어·공종, 번호 가리기), 개인 번호 기억과 설정에서 되돌리기, 내 정보 세 칸, 처음 정리 자동 후퇴, 폰 하트비트 `status/phone.json`, 화면 토큰·어두운 모드·아이콘 14개·홈 재배치·되돌리기·전화 걸기·검색·신호등 다섯 줄·뒤로 가기 = 홈·직접 적기, 화면 규칙 CI 검사(`tools/check_ui_rules.py`), API 키 경로와 옛 폴더(worker, local_stt 등) 삭제.
- **요청 v3 과도기:** 예약 루틴 지시문 교체가 이 세션의 자동 권한 검사에서 막혔음(사용자 승인 필요). 그래서 `CallInbox.CARRY_RULES=true`로 v3 요청에도 system·schema를 계속 싣고(지금 루틴은 요청의 system을 따름), 새 지시문 `docs/routines/hourly.md`는 두 형식을 모두 처리하게 썼음. 사용자가 루틴 교체를 승인하면: hourly.md·tidy.md의 "---" 아래 전문을 각 루틴 프롬프트에 넣고 → 다음 배포에서 `CARRY_RULES=false`.
- 워치독 원본은 `tools/inbox/watchdog.yml`·`watchdog.py`(ieum-inbox의 `.github/workflows/`, `tools/`로 복사해야 동작). 폰 하트비트가 올라오기 시작한 뒤 설치.
- 수동 시험만 있는 것: `update-smoke.yml`(직전 APK 위 덮어 설치). 실기기 미확인: 모델 핸들 유지, 통화기록 URI 트리거, 무인 설치, 알림 단색 아이콘.

## 4-4. v2.2~v2.5 (11/4 오후~밤, 클라우드 세션, 기준 문서 설계도 "v2.2~v2.5 완료 목록")
- 진행: 설계도(rev 104)와 v2.3 통합 브랜치를 Opus로 대조(차이 51건, 설계도 고침 59곳) → v2.4·v2.5에 모두 구현하거나 설계도에서 이유와 함께 뺌(사용자 11/4 "v2.4~v2.5엔 모두 구현"). 작업자는 Opus 중간, 묶음마다 `feature/r6-<이름>` 브랜치와 `/home/claude/wt-r6-*` 작업 폴더, 공통 지시 `/home/claude/r6-common.md`.
- 배포 기록:

| 버전 | 빌드 | 날짜 | 사용자에게 보이는 변경 |
|---|---|---|---|
| v2.5 | 배포 대기 (`feature/r6-v25` 81f9bd0) | — | 새 버전 받는 진행률(설정 화면 막대와 %), 30분 넘는 긴 통화도 멈추지 않고 자막, 첫 정리 쉬는 중 안내, 금고 정리 |
| v2.4 | 1033 | 11/4 23:30 | 부재중·거절·녹음 없음·개인 통화까지 모든 통화가 목록에, 자막 21% 빠름(armv8.2 dotprod), 문제 생기면 폰 알림, 저녁 보고에 방금 통화 |
| v2.3 | (따로 배포 안 함) | — | v2.4에 포함: 하단 시트 확인 창, 자가 진단 알림 |
| v2.2 | 1031 | 11/4 저녁 | 배터리 덜 씀, 업무 통화가 개인으로 잘못 빠지는 일 줄임 |

- 주요 변경(파일): 통화기록 전부 저장·표시(`CallRecordStore.call_log`, `TodayCallsActivity`, 개인 판정은 저장을 막지 않음), 폰 하트비트에 통화기록 대조 블록과 `status/calllog.txt`(이름·번호 포함, 아내 동의, 비공개 ieum-inbox에만, `CallLogReport.kt`), 개인 번호는 2회 연속 판정일 때만 기억(`personal_streaks`), 열린 할 일에 owner·person·quote 40자(요청 v5, promptVersion 5, 메모 3키는 결과에서 빼고 장부에만), 결과 받기 :45 작업(`ieum.calls.results`), 정리 자료는 내용이 같으면 올리지 않음, 디코더 두 번 읽기 스트리밍(F6, 30분 통화 메모리 ~115MB → 조각 하나), 골든 셋(`androidTest/assets/golden/`, `tools/audio/make_golden.py`·`golden_score.py`), 채널 진단(F1, 하트비트 audioChannels/Corr), 미지원 CPU 거부(`ANDROID_STT_CPU_UNSUPPORTED`), 업데이트 진행률과 sha·크기 불일치 재다운로드(`CallUpdate.kt`), 덮어 설치 시험 수리(`update-smoke.yml`: 디스크 확보·KVM·AVD 캐시·재시도, 첫 성공 41845595967).
- 골든 셋 결정(v2.5): 바꾸지 않음. 무음 기준 0.006, 프롬프트 없음 그대로. 기기 시험(작은 모델)에서 base 3통화가 757초에 정상으로 끝났고(그 전 "멈춤"은 멈춘 게 아니라 변형 하나에 13분씩 걸려 90분 한도에 걸린 것), 0.0025와 프롬프트 비교는 아직 결과 없음. 비교는 device-test 수동 실행 `golden=compare`, `only=tg_goldenSet`(약 45분, push 실행과 동시 실행 그룹이 달라 서로 취소하지 않음)으로 하고, 결과를 보고 v2.6에서 상수(`PipelineAudioMath.SPEECH_FLOOR`, Whisper 프롬프트)만 바꾼다. push 실행은 이제 `golden=base`.
- 30분 통화 메모리(F6, t6): 새 방식은 끝까지 갔고(멈춘 뒤 이어 하기 포함, 조각 수 일치) 예전 방식은 192MB 힙에서 메모리 부족. 다만 "예전의 4분의 1 이하" 판정이 98~101MB로 두 번 떨어짐. 원인 둘: ① 시료 스레드가 재는 힙에는 디코더가 그 사이 만든 쓰레기가 섞임 → 이제 조각을 자막으로 넘기는 순간 같은 스레드에서 GC 뒤 잰 값으로 비교(시료 값은 `newSampledMb`로 기록만) ② 보관 사본을 읽는 `PipelineBytesInput.read`가 정확히 크기만큼 읽은 뒤에도 배열을 두 배로 키워 15MB 녹음이 30MB를 잡음(앱 버그, 수리). JVM 시험 `PipelineAudioMemoryTest`(30분 WAV, 조각마다 40MB 미만)를 더함. 수리 뒤 기기 시험은 아직 못 돌림(아래 Actions 결제).
- 배포가 멈춘 이유(11/5 03시): GitHub Actions가 "recent account payments have failed or your spending limit needs to be increased"로 모든 작업을 시작조차 안 함(ci, device-test, release, ieum-inbox 워치독 모두). 사용자가 GitHub 설정 Billing에서 풀어야 함. 풀리면: `feature/r6-v25`의 ci가 초록인지 확인 → `feature/lean-calls`에 합치고 배포(덮어 설치 시험 관문 엄격) → `app/version.json` 확인 → APK를 사용자에게 보내 아내 폰에서 [지금 받기]/[설치](사이드로드라 무인 설치 안 됨).
- 덮어 설치 시험 관문: v2.4는 사용자 승인으로 경고만(에뮬레이터가 켜지지 않던 때). v2.5부터 다시 엄격, 단 "Emulator did not boot (infra)"만 경고.
- 폰 상태(11/4 23:43 하트비트, v2.4 설치 뒤): 동의·폴더·알림·배터리 모두 켜짐, 대기 139건(10/1 이전 옛 녹음 포함, 처음 정리 중), 분석 대기 33건, 10/1 이후 통화기록 979건 = 저장 908건(개인 71건 제외) 빠짐 0·중복 0. 단 녹음과 짝지어진 통화가 0건(`recorded=0`): 옛 버전이 남긴 기록에 통화 시작 시각이 없을 가능성, 새 자막이 쌓인 뒤나 09:00 검증에서 다시 볼 것(v2.6 통화기록 대조 묶음). 설치 전에는: 아내 폰은 v2.0 이전 버전이라 홈 화면을 열 때만 작업을 다시 등록함. 마지막 실제 업로드 11/4 00:50, 하트비트 한 번도 안 옴. 무인 설치는 앱을 직접 설치(사이드로드)해서 설치 주체가 아니라 실패함 → v2.4 APK를 사용자에게 보내 손으로 설치. 설치 뒤 하트비트(`status/phone.json`)와 `status/calllog.txt`가 올라와야 함.

### 예약 작업 (11/4 밤 기준, 모두 "일반" 세션, 소스 ieum-inbox, 알림 켬)
| 이름 | ID | 시각 | 모델 | 기억 파일 | 보고 |
|---|---|---|---|---|---|
| 이음 통화 분석 (매시간) | `trig_01WhkNFfBvGYvgaraXKuwL5K` | 매시 30분 | Sonnet 5.5 | `status/last-run.json` | 세 줄(처리 건수 종류별, 실패, 남은 요청, 폰 요청 형식, 장부 rev) |
| 이음 새벽 정리 (매일) | `trig_01BJ7psN5c92FCshDpqF6maz` | 05:16 | Opus 5.5 | `tidy/project.md`(공사 전체, 지우지 않음), `tidy/history/YYYY-MM.md`(날짜별 일지, 지우지 않음), `tidy/briefing.md`(오늘 현황판) | 현황 파일 `이음_현황_YYYYMMDD.md` + 다섯 줄 알림(할 일, 전화할 곳, 답 대기, 진행 중, 제안) |
| 이음 통화 목록 검증 (매일) | `trig_01KyuDNZWkmrQpJj8e8cr661` | 09:00 | Opus 5.5 | `status/verify-log.md`(숫자만) | 검증 파일 `이음_통화목록_검증_YYYYMMDD.txt`(이름·번호 포함) + 다섯 줄 알림 |
- 지시문 원본: `docs/routines/hourly.md`(v5), `docs/routines/tidy.md`, 검증은 루틴 프롬프트가 원본. `RoutineDocTest`가 hourly·tidy 원본과 앱 상수를 대조.
- 옛 루틴(`trig_01F9MoxF…`, `trig_012D6jrC…`, `trig_01AK4Fj…`)은 모두 지움.
- 루틴에 Docs·Drive·Calendar·Code Remote 연결이 붙어 있음. 이 세션에서는 뗄 수 없어 사용자가 루틴 설정에서 떼야 함(안 떼도 동작에는 지장 없음).
- 워치독: 11/5 ieum-inbox에 설치(커밋 2d121ae, 수동 실행 41853205350 이상 0건). 처음 정리 중에는 옛 대기열을 "자막이 밀려요"로 치지 않고, 마지막 자막이 24시간 넘었을 때만 알림(`tools/inbox/watchdog.py`, 시험 14개).
- 기기 시험 고침(`tools/run_device_test.sh`): 결과를 logcat에서 실시간으로 모으고(`results.live`), instrument 자체 시간 제한 70분(`IEUM_INSTRUMENT_MIN`), 골든 통화마다 `golden_start` 기록, `IEUM_TESTS`로 시험 골라 돌리기.

## 4-5. v2.6 (11/5 새벽, Fable 클라우드 세션, 기준 docs/DESIGN_V3.md 3절)
- 상태(11/5 08시): **lean-calls에 합침(ca9acf2), 배포 대기.** 통합 브랜치 `feature/r7-v26`(v2.5 `feature/r6-v25` 81f9bd0 포함)에서 로컬 전체 단위 시험 423건 통과, lint 0, 화면 규칙 0건. v2.5는 따로 배포하지 않기로 함(v2.6이 포함). push로 시작된 네 run(ci 41929642402, update-smoke 41929642491, device-test 41929642529, release-apk 41929642417)은 Actions 결제 차단으로 단계 없이 실패 → **저장소가 공개로 바뀌면 다시 실행**(6절 0번). 배포 뒤 하트비트에서 `firstPass.blockedBy`, `newQueuedCount`, `callLogMatch`로 처음 정리가 흐르는지 확인. 아내 폰은 v2.4(1033)라 v2.6은 손으로 설치해야 하고, 그 뒤부터 r7-3 무인 설치가 v2.7을 받음.
- 묶음(모두 Opus 작업자, 각자 worktree·브랜치 `feature/r7-v26-r1`~`r6`, 공통 지시 `/home/claude/worker-brief.md`):
  - r7-1 처음 정리 관문: 열 때 10/1 이전 QUEUED→SKIPPED 한 문장, `oldestOldJobMs`는 10/1 이후만, offer 루프 트랜잭션 1회+중지 신호, **live=기록 시각 7일 이내**(`FirstPass.LIVE_DAYS`, `liveFrom`, `isOld(now)`, `stale`; `beforeStart` 삭제), 덜 찬 묶음은 6시간(createdMs) 뒤 전송, `LAST_INBOX_ERROR`에 모든 코드+`LAST_INBOX_ERROR_MS`, 하트비트 `firstPass.bound/blockedBy`.
  - r7-2 발견 경량화: 이름 시각 우선 10/1 컷오프(`DiscoveryCutoff`), SAF 목록은 옛 녹음을 세지 않고 2000 한도는 남긴 것에만(넘치면 최신 유지+`inventoryTruncated`), 큐 UPSERT·audit 100개, 시도마다 `lastDiscoveryAttemptMs/lastDiscoveryOutcome`, 트리거 finally 재등록·지연 140초, 녹음 한도 60MB(옛 DB의 25MB CHECK는 `VaultSizeLimit.widen`으로 한 번 넓힘, 금고·스키마 숫자 포함).
  - r7-3 무인 설치: `CallUpdate.installInBackground`(USER_ACTION_NOT_REQUIRED, API31+, 세션 id 보관), PENDING이면 알림, 성공 시 키 0, 실패는 `LAST_UPDATE_ERROR` 80자, 설정 스위치 "새 버전은 자동으로 설치"(`AUTO_INSTALL`), 키 `UPDATE_AVAILABLE_CODE`·`UPDATE_READY_SINCE_MS`. 사이드로드 폰에서는 첫 한 번 확인을 물을 수 있음(그 뒤 앱이 설치자).
  - r7-4 정확도: `FirstPass.match`가 이름 불일치·CACHED_NAME null이어도 후보 하나면 잇고 실패 사유·시작 차이 히스토그램을 `callLogMatchStats`에 쌓음, `NameKey` 호칭 추가·초성 비교·성+직책 규칙·사무실 제외, 제목 중복은 이름·업체·동작어 제거 뒤 비교(근사 중복은 `mergeCandidates`), 반복 인용은 핵심어 근처만·창 기반 퍼지, 자막 줄바꿈, 열린 일 `done_ms` 조건, 별칭 이동·trade "기타"·updated_ms 유지, 전화부 이름·메일 주소·10자리 유선 가리기, 자막 비우기 `analyzed_ms` 기준, `MIGRATION_VERSION=3`(합치기는 같은 번호·업체만, `mergeLog`).
  - r7-5 지시문 v6: `docs/routines/hourly.md`(장부 사람·업체 추가 규칙, 화자·날짜·확실성·update 조건·성+직책·명절 인사), `tidy.md`(제안에 rev, mergedInto 유지, mergeCandidates), `PROMPT_VERSION=6`, `tidyState` rev·mergeCandidates, `applyTidy` rev 비교, `RoutineDocTest`. **루틴 프롬프트 교체는 자동 권한 검사가 막아 사용자가 직접**(붙여 넣을 전문을 11/5 03시 사용자에게 보냄: `hourly_v6_routine.txt`, `tidy_routine.txt`; 저장소 `docs/routines/*.md`의 "---" 아래와 같음). 10번(제목에서 사람 이름 제거)은 v2.7에서 taskRow META가 이름을 보여 준 뒤 적용.
  - r7-6 감시 정확화: 신호등 입력을 실제 상태로(폴더 권한+발견 24시간, 새 녹음 대기열 진입 시각, live 지연, 업데이트 24시간), 하트비트 새 필드(newQueuedCount 등, dbPercent는 sealed 파일·행 수, standbyBucket EXEMPTED/NEVER), 워치독 새 규칙(ieum-inbox에 복사함, 커밋 1e52b1b), 저장 공간 % 수리, 긴 통화 stall 완화(진척 있는 시간 한도 중지는 안 셈).
- 통합 중 고친 것: 신호등 대기열 판정에 시계 인자(7일 live 기준과 맞춤), `RoutineDocTest`가 새벽 정리 실제 답 형식("정리할 것 없음: 이유")을 허용, 지시문 문서를 실제 루틴 문구에 맞춤.
- 로컬 빌드 환경(이 세션의 클라우드 작업 환경): `/home/claude/toolchain/env.sh`(JDK 17, SDK 36, Robolectric jar), `flock /home/claude/gradle.lock python3 run_gradle.py ...`로 한 번에 하나만. 메이븐이 429를 자주 내므로 첫 의존성 받기는 재시도 필요.

## 4-6. v2.7 (11/5 아침, Fable 세션, 기준 DESIGN_V3.md 4절) — **배포 완료(11/5 08:49, 빌드 1035)**
- 배포(11/5, Opus): device-test 다시 실행 성공, 스크린샷 6장(홈, 오늘 통화, 체크, 설정, 업체, 업체 상세) 확인 — 잘림·겹침 없음. 하네스는 통화 상세와 1.3배 홈을 찍지 않음(설계 4.2의 7+1장 중 2장 없음, v3.0 call-card 단계 넣을 때 같이 추가). lean-calls 9da92aa로 합쳐 ci·update-smoke·release 성공.
- 상태: `feature/r8-v27` **964fd1b**(푸시됨). 전체 단위 시험 **441건 통과**(int27-test-7), 화면 규칙 0, 비밀 스캔 0. `docs/VERSION` 2.7, `UPDATE_NOTE` 첫 줄 "v2.7: 화면이 바로 반응하고, 탭과 뒤로 가기가 한결같아졌어요. 아침 알림에서 바로 다 됐어요를 누를 수 있어요". 설계 4.2 "device-test 결과를 기다려 스크린샷 7장+1.3배 홈 1장 확인"이 남음(Actions가 살아나면 feature/r8-v27의 ci·device-test run을 다시 실행).
- 1단계(병렬, 보고서 `docs/orchestration/reports/feature-r8-v27-r1.md` 등):
  - r8-1 저장소 성능: `IeumApplication.store(context)`가 프로세스 수명 `CallRecordStore`(놓는 곳 `shutdown()`·`onTrimMemory`), Keystore 확인 한 번(`State.verified`), 변경 없는 트랜잭션 스냅샷 생략, 스키마 단계 상태마다 한 번(`needs/markDone`), 홈 read 1회 열기(`HomeScreen.readOnce`, 측정 `homeReadMs`·`dbOpenMs` 설정 키 → 하트비트 `dbOpenMs/homeReadMs`), `oldestUnansweredSentMs()` 한 쿼리, 통화 화면 `reconcileCallLog` 제거(발견·업로드 패스에서만). 시험 `StorePerformanceTest`. 시험에서 저장소 파일을 지우기 전 `IeumApplication.shutdown()` 필요.
  - r8-2 화면 하나: `MainActivity`(유일 Activity, `enum Screen{HOME,CALLS,TASKS,COMPANIES,SETTINGS}`, `AppScreen(a)` 기반 `screens/*Screen.kt`, `switchTo/openCall(id,dayMs)/openCompany(name)`, `MainActivity.intent(c,screen,dayMs,callId)`, singleTop·portrait, `putBack(y)` 스크롤 복원). 옛 Activity 4개·`CallUi.go` 삭제. 시험은 `launchMain(screen,dayMs,callId)`(`MainLaunch.kt`).
  - r8-3 즉시 반응 부품: `CallUi.once/onTap(600ms)`, `busy/idle(busyText)`, `loading`, `retryCard`, `sheetDialog(validate)`+키보드, `signalRow(onPress)`, `icon(filled)`, 밝은 MUTED `#6B7684`, `tabLook`(굵기·채운 아이콘), `TaskActions` 즉시 반응·`inFlight`·실패 복원, `StatusLights.Light.target`. 시험 주의: 600ms 안 두 번 누름 무시 → `idleFor(700)`.
- 2단계(병렬): r8-4 홈·통화(라벨 "오늘 통화/오늘 생긴 체크/남은 체크할 일", 먼저 볼 체크할 일 5개+"외 n개 보기", 통화 직후 3단계 문구 `CallsScreen.stateWords`, `EMPTY_TRANSCRIPT`는 버튼 없음, 날짜 넘기기 즉시 교체+loading, 검색 250ms 디바운스·유지, `NotifyAsk`(두 번 거절 → 알림 설정 열기), `ModelWish`, UI prefs `ieum_ui`) · r8-5 체크·업체·설정(`CheckRows.row` G3 행, 통화 항목만 눌림, 30개 나눠 그리기, 업체 이름 고치기 `renameCompany`+`company_aliases`, 설정 스크롤·입력 유지, 스위치·슬라이더 제자리 갱신(700ms 디바운스), 동작별 busy, 신호등 줄 → 묶음 스크롤, `OpenItem.recordId/callMs/fromCall`) · r8-6 알림(`NotificationActions.kt`·`NotificationActionReceiver`, 액션 `kr.callbrief.action.TASK_DONE`, 아침 알림 첫 항목 [다 됐어요] → 다음 항목으로 갱신 → "체크할 일을 다 끝냈어요", 모든 알림이 `MainActivity` extra로, 저녁 알림 dayMs, `CallDigestNotifier.post(...,dayMs,callId,refresh)`, id 700·tag `ieum.calls.<kind>`).
- 통합 때 고친 것: r8-1 변경을 r8-2가 옮긴 `screens/*.kt`에 적용, `TaskActions`·`MainActivity.pickFolder`(폴더 저장 IO) 프로세스 저장소, 홈 체크 행도 `CheckRows.row`, 날짜 넘기기 시험 `CallsScreen.readHook` 게이트(경쟁 제거), `UiV2Test.aFailedInstall…`을 r7-3 동작(성공 시 오류 지움)에 맞춤.
- 안 한 것(선택): "AI 메모" → "이음 메모" 표기, 업체 이름 고치기의 ledger_edits 전송(`renameCompany` edit 제안), 설계의 "상위 2개 [다 됐어요]"는 1개로(버튼 둘이면 구분 불가).

## 4-7. v2.8 (11/5 09시, Opus 통합 — lean-calls에 합쳐 배포)
- 통합(`feature/r9-v28` e368728 → lean-calls): r9-2 → r9-1(충돌: `CallRecordStore` issues 반영 끝과 클래스 끝 함수 — 둘 다 살림) → r9-3(클래스 끝 — 둘 다 살림). 마이그레이션이 돈 열기에서는 `migratedThisOpen`으로 생성자 끝에 `ContactCards.touchAll` 한 번(카드 파일 쓰기로 홈이 늦지 않게, 설계 5.5).
- 마저 한 것: `rememberPersonal` 뒤 카드 삭제(`ContactCards.touch`), 업체 상세 최근 통화 이름 → 사람 화면(`CompanyCall.personId`), 통화 검색에 "사람" 절(`peopleForSearch`, 최대 5명, `PersonLinks.row` 공용 행), 설정 [상대 카드 다시 만들기](`rebuild_cards`), 체크할 일 META 이름에 `noNumber`(r10-v29 f0b4b96, v2.9에 들어감).
- 시험: 전체 단위 시험 **466건 통과**(`v28-testn-1`), 화면 규칙 0, 비밀 스캔 0. `PersonScreenTest`에 2건 추가. device-test는 v2.8에서 기다리지 않음.
- 아래는 통합 전 기록.
- 통합 브랜치 `feature/r9-v28` = v2.7 964fd1b + `core/PhoneKey.kt`(f7d091b, `PhoneKey.of(raw)`·`isKey`, `PhoneKeyTest` 5건). 묶음 지시문 `docs/orchestration/briefs/r9-*.md`, 보고서 `docs/orchestration/reports/feature-r9-v28-r*.md`.
  - r9-1 `feature/r9-v28-r1` **65e105d(+빈 WIP 커밋 e5beb1e)**: PhoneKey 호출처 전부 교체(`PrivacyRules.normalizePhone` 삭제, PhoneKey가 +없는 82+8자리도 허용), `MIGRATION_VERSION=4`(키 재계산·같은 번호 사람 합치기, `STORE_V4_SCHEMA` 파일 끝), 인덱스 7개·`issue_companies`, `MAIN_THREAD_CHECK`(실제 앱에서 메인 스레드 접근 시 예외 — 화면은 IO에서만), `inTransaction{}`, 보호 파일 복구(`takeRecovery()` → `dbRecoveryLast`), WorkManager `use{}` 교체(담당 밖 `IeumApplication`·`CallSinkImpl`·`PhoneStatus`·`CallRecordAnalysisRunner` 소폭). 시험 `StoreFoundationTest`, P04~P06 수정. **전체 단위 시험 미실행**(종료 지시로 중단).
  - r9-2 `feature/r9-v28-r2` **78ffef6(완성)**: `pipeline/ContactCards.kt`, 파일 `contact-cards-v1.sealed`, 표 `contact_cards(phone_key PK,json,updated_ms)`. 공개 함수 `get(phoneKey)`, `get(context,phoneKey)`, `preload(context)`, `warm(context)`, `touch(store,keys)`, `touchAll(store)`, `flushPending(store)`, `refresh(store,phoneKeys)`, `rebuildAll(store)`, `shutdown()`. 카드 JSON v1 필드는 보고서 참고. 훅: applyAnalysis·completeItem·undoDone·addUserItem·renamePerson·reconcileCallLog·forgetPersonal·insertMessage(applyLedger·applyTidy는 touchAll). **남은 것: r9-1 마이그레이션 직후 `ContactCards.touchAll(this)` 한 줄.** 300장 읽기 68ms(목표 60), 캐시 85µs.
  - r9-3 `feature/r9-v28-r3` **7404a5e(WIP, 기능은 전부)**: `screens/PersonScreen.kt`, `Screen.PERSON`, `MainActivity.openPerson(personId)`, 진입점 전부(통화 목록·상세, 업체 사람 행, 체크 META, 홈 메모·체크), `CheckRows.row(personId)`, `NameSheet` object, `CallRecordStore.personCard/personIdsOf`. `PersonScreenTest` 6건 등 86건 통과. 남은 것: `ContactCards` 연결(개인으로 두기 뒤 카드 삭제), 검색 결과(사람)·업체 상세 최근 통화 이름 링크, 회귀 11개 클래스 재확인.
- 통합 순서 제안: r9-2 → r9-1(충돌: `CallRecordStore` 훅·클래스 끝 함수, `IeumApplication`) → r9-3(충돌: `CallRecordStore` 끝, `screens/*`) → 마이그레이션 뒤 `touchAll` 한 줄 → `:app:compileDebugKotlin :app:testDebugUnitTest` 전체 → `check_ui_rules`·`scan_export` → VERSION 2.8, UPDATE_NOTE "v2.8: 사람을 누르면 그 사람과의 통화, 문자, 체크할 일이 한 화면에 보이고 바로 전화와 문자를 보낼 수 있어요" → ci → lean-calls 배포(기기 시험은 기다리지 않음).
- 오케스트레이션 방법(이 세션에서 쓴 것): 작업자는 Agent 도구 `subagent_type=general-purpose, model=opus, isolation=worktree`로 메인 체크아웃이 기준 브랜치에 있을 때 호출. worktree가 main(67fac72)에서 시작되는 일이 있어 지시문에 "HEAD가 기준 브랜치와 다르면 `git fetch origin && git reset --hard origin/<기준>`"을 넣음. 작업자는 worktree 밖에 못 쓰므로 보고서는 마지막 답으로 받아 `docs/orchestration/reports/`에 저장. 로컬 툴체인은 `tools/local_toolchain.sh`(JDK 17 apt, SDK 36, Robolectric jar; `/home/claude/toolchain/env.sh`를 만듦)로 새 환경에서 다시 준비(메이븐 429는 재시도). Gradle은 `flock /home/claude/gradle.lock python3 run_gradle.py --log <라벨>.log …`로 한 번에 하나. 전체 단위 시험 약 4분.

## 4-8. v2.9 (11/5 오전, Opus 통합 — lean-calls 배포, 기준 DESIGN_V3.md 6절, 다르게 정한 것은 6.5)
- 통합 `feature/r10-v29` 0ca80a6: r10-1(372adbe 전화 상태 감지) → r10-3(289d4c1 결과 10분 체인·ETag) → r10-2(6265c2a 통화별 알림) — 충돌 없음. `CallSessionStub.kt` 삭제, `CallSession` IDLE 끝 `CallFollowupNotice.onCallEnded` 켬. 전체 단위 시험 **492건 통과** + lint(`v29-test-1`), 화면 규칙 0, 비밀 스캔 0. 보고서 `docs/orchestration/reports/feature-r10-v29-r*.md`.
- 새 권한 `READ_PHONE_STATE`(처음 설정 8단계 중 "통화가 끝난 걸 바로 알아차리기"), 수신기 `pipeline.call.CallStateReceiver`, 작업 `ieum.discovery.aftercall`·`ieum.calls.results`(10분 체인), 채널 `ieum.calls.followup`, 액션 `kr.callbrief.action.CALL_MEMO`, 하트비트 `resultChain`.
- 예약 작업(11/5 17시 다시 만듦, 옛것 삭제): "이음 통화 분석 (매시간)" `trig_01Q8HXh1MKsfDHZsPovxRtLa` — 지시문 **버전 6**(그때까지 루틴은 버전 5였음), `CRON_TZ=Asia/Seoul 34 7-22 * * *`(30분 간격은 예약 작업이 허용하지 않음), Sonnet 5.5. "이음 새벽 정리 (매일)" `trig_018q8SX7vHbVqFVpCX3fzg7Q` — tidy v2.6판(rev, mergeCandidates), 05:16, Opus 5.5, 알림 켬. "이음 통화 목록 검증 (매일)"은 그대로. 워치독 새 규칙(`tools/inbox/watchdog.py`)은 ieum-inbox `tools/watchdog.py`에 다시 복사해야 반영.
- 기기 확인 못 한 것: 실제 PHONE_STATE 흐름, RemoteInput 메모 — v3.0 device-test `call-card` 단계에서 같이 본다.

## 4-9. v3.0 (11/5 오후, Opus 통합 — lean-calls 배포, 기준 DESIGN_V3.md 7절, 다르게 정한 것은 7.5)
- 통합 `feature/r11-v30` d48a9d4: r11-1(cf4edb1 OutgoingCallService·CallCardService·처음 설정 10단계·설정 "통화 카드") + r11-2(6ebe4ad CallCardOverlay·CallCardView) → 임시 정의 삭제·설정 키 통일(77b544b, 522건) → r11-3(f413488 CardStats·신호등·watchdog·`tools/device/call_card_test.sh`·`CallCardSeedTest`, 533건). 보고서 `docs/orchestration/reports/feature-r11-v30-r*.md`.
- device-test에 "Call card on emulator" 단계(오케스트레이터가 추가, `CARD_WINDOW_MS=3000`)와 화면 7_call_detail·8_person·9_home_large(글자 1.3배). 결과(run 41973214685): 화면 9장 모두 그려짐, `evidence/call-card.json` 일곱 항목 모두 true(벨 → 창 1158ms, 받으면 3초 뒤 띠, 끊으면 창·서비스 없음, 발신 placeCallUnmodified+카드, 모르는 번호 꺼짐이면 조용·켜면 카드, 개인 번호 아무것도 없음).
- 알려진 것: `evidence/shots/card_*.png`는 0바이트(창이 FLAG_SECURE라 원래 검게 찍힘, `adb exec-out screencap`도 실패) — 판정에는 쓰지 않음. 사람 화면 시험 데이터(PreviewSeed)에 걸 수 있는 번호가 없어 [전화 걸기][문자 보내기]가 안 보임(실제 번호가 있는 사람에게는 보임, PersonScreenTest가 확인).
- 아내 폰에서 한 번씩 허용할 것: 처음 설정 카드의 "통화가 끝난 걸 바로 알아차리기"(전화 상태), "통화 화면 위에 이음 카드 보기"(다른 앱 위에 표시), 선택 "거는 전화에도 카드 보기"(통화 연결 앱 역할). 하트비트 `callCard`로 실제 카드가 뜨는지(shown·noPermission) 확인.

## 4-10. v3.1 (11/5 밤, Opus 통합 — lean-calls 배포, 기준 DESIGN_V4.md 2절, 다르게 정한 것은 2.8)
- 통합 `feature/r12-v31`: 키 커밋 9654798(하트비트 `PhoneStatus.V31`·권한 네 가지, `PipelineSettingsKeys` v3.1 묶음, 묶음 사이 함수 자리 `RecordingGap`·`CallUpdate.state/setupStep/checkNow/notInstalledForADay`·`oldestQueuedMs`) → r12-2(60436f2) → r12-4(e4b25d2) → r12-3(d908e9b) → r12-1(e057cf8) → r12-5(bcea3e4), 충돌 없음. 보고서 `docs/orchestration/reports/feature-r12-v31-r*.md`, 지시문 `docs/orchestration/briefs/r12-*.md`.
- 오케스트레이터 몫: `sawNew` 한 줄, tidy.md 지시문 버전 5(personCandidates 규칙, 자가 보고 promptVersion "5", `RoutineDocTest.tidySelfReportCarriesTheHeadVersion`), release-apk에 `tools/release/version_fields.py --merge`(promptVersion·tidyVersion·certSha256)와 이전 APK를 못 받을 때 certSha256 비교, `tools/update_smoke.sh` 앱 자신 설치 단계(이번엔 경고, `IEUM_SELF_INSTALL_STRICT=1`이면 실패), 통화 목록 줄에 r12-1 `stateWords(LoggedCall)` 연결, [다시 정리하기] 즉시 재전송(`CallRecordStore.forgetSent`), 설정 [지금 업데이트]의 설치 바람을 상태 줄보다 먼저(회귀 수정), StatusLights 모델 문구의 Wi-Fi 삭제, 업데이트 문구 조사("새 버전(v3.1)이 …").
- 시험: 전체 `:core:test :app:testDebugUnitTest :app:lintDebug` 로컬 BUILD SUCCESSFUL(int31-ci-2, 582건), ci 42020014550 성공. 화면 규칙 0, 비밀 스캔 0, 파이썬(워치독 29, version_fields 4) OK. ci 1차 실패 둘: FirstSetupHomeTest 경쟁(화면 자체 읽기가 시험 상태를 덮음 → 먼저 기다림), PhoneStatus RoleManager NewApi.
- 1.4절 0번: r12-4의 v2.4 픽스처 직행 마이그레이션 시험이 고치기 전 코드(=157ffab 저장소)에서 통과 — 손 설치 막을 이유 없음.
- 아내 폰(11/5 18:43): v2.4(1033), 통화기록 1,026건 중 녹음 이어짐 0(자막은 돌고 있음, 16:38 마지막) — v2.4 대조 규칙이 원인일 가능성이 가장 큼(v2.6 r7-4가 고침).
- 배포 뒤 할 일: 워치독 `tools/inbox/watchdog.py`를 ieum-inbox `tools/watchdog.py`로 복사, 사용자에게 tidy.md·hourly.md 전문 다시 붙여넣기 안내.

## 4-11. v3.2와 v3.2 수정판 (11/5 밤~11/6 01시, Opus 통합 — 빌드 1041, 1042)
- v3.2 「홈은 요약, 맨 위에 오늘 이야기」(DESIGN_V4 2-1, r12-6): 홈은 요약만, 진행 상황·처음 설정·업데이트는 설정으로. 매시간 루틴 5-1이 `story/today.json`을 쓰고 폰이 `STORY`로 받음.
- 실측(11/5 23시 하트비트): 녹음 25개 중 통화와 이어진 것 1개. **원인은 짝짓기보다 긴 통화 자막이 끝나지 않은 것**(새 통화 우선 대기열, 시간 한도·540초 기한을 실패로 셈). 수정판 1042 = r12-7(파일 이름의 상대 우선 짝짓기, DESIGN_V4 2-2) + r12-8(긴 통화 완주 A1~A5·A8, 2-3). 보고서 `feature-r12-v32-r7/r8.md`. 워치독 규칙 (마) "긴 통화의 자막이 끝나지 않아요".
- 사용자 결정: 통화기록 총수는 앱 검증용일 뿐, 화면·보고는 "녹음 n개 중 m개"로. 개인 통화가 "개인 내용" 한 줄인 것은 정상.

## 4-12. v3.3 「믿을 수 있는 숫자」 (11/6 02~03시, Opus 통합 — 빌드 1043)
- 통합 `feature/r13-v33`: 키 커밋 07f4b80(`Segment.uncertainNumbers`, `LOCK_SCREEN_DETAIL`·`GUARD_STATS`·`GOLDEN`) → r13-1(24c21d9 숫자 가드·확인 필요 배지·thin 카드·잠금 한 줄) ∥ r13-2(4053f86·4309977 토큰 확률·A8·골든 측정 `kr.callbrief.pipeline.GoldenMeasureActivity`·지시문 v7) → r13-3(1d2c0f2 합치기 되돌리기·18sp·다듬기). 오케스트레이터: SYSTEM/SCHEMA v7·`PROMPT_VERSION=7`·이름 목록 순서(dca5e1e), 루틴 mergePeople/unmergePeople·keepApart 규칙(1c74eae). 다르게 정한 것 DESIGN_V4 3.5.
- 기기 시험 t4 "1.3배 제목 폭"은 안드로이드 16 에뮬레이터에서 1.0배라 기록만으로(53c41a5).
- 사용자 할 일: 실녹음 골든(설정 › 상태와 도움 › 골든 측정, 통화 3~5건 받아쓰기, 숫자는 아라비아 숫자로, 충전 중 측정). 그 결과 뒤에 3.2 3단계 상수(B1~B7).

## 4-13. v3.4 「모든 전화에 카드, 스스로 지키는 루틴」 (11/6 03~06시, Opus 통합 — 빌드 V34BUILD)
- 사용자 "v3.4까지 배포" → 48시간 실측을 기다리지 않고 r14-1~4를 한 밤에(DESIGN_V4 4절 머리 결정). 키 커밋 8293f5b → r14-1(7b88d9b 역할 조건 제거·콜드 캐시·통화 대기·통계·카드 JSON v2) ∥ r14-3(13e968e·4dda580 지시문 v8/tidy v6·워치독 루틴 안·결과 받기 시각·버전 1시간·메일 주소 가림) → r14-2(74d55a4 카드 글·위치·잠금·통화 알림·신호등) ∥ r14-4(d453e22 기기 시나리오·S26 체크리스트·문서). 다르게 정한 것 DESIGN_V4 4.6.
- 오케스트레이터: release-apk가 루틴 지시문 둘과 워치독을 우편함에 올림(c420dda), ci 카드 샷·device-test 경로·golden `'off'`(03bcd1a), ieum-inbox `.github/workflows/watchdog.yml` 삭제.
- 기기 시험: 벨→창 수신 1,081ms·역할 없이 발신 1,276ms·콜드 1,371ms. 통화 대기는 에뮬레이터가 재현 못 함(null).

## 5. 현재 UI 상태와 문제점 (S26 스크린샷 기준, 다음 작업의 출발점)
사용자 평가(10/30): "미관상 너무 엉망", "**깔끔하긴 한데 세련되지 않았다**". 목표는 깔끔함을 넘어 세련됨. 개편 방향은 [PRODUCT.md 4.5](PRODUCT.md). Claude가 본 구체적 문제:
1. **모든 버튼이 같은 연청색 큰 덩어리** — 전날/다음날, 할 일 전체 보기, 이름 고치기, 완료했어요, 업체 목록으로가 모두 같은 크기·색. 주요 동작과 보조 동작의 구분이 없음.
2. **위계가 약함** — 제목/본문/보조 글자 크기 차이는 있으나 화면마다 글자 덩어리 나열. 아이콘이 전혀 없어 기능을 한눈에 알기 어려움.
3. **같은 정보가 화면마다 다르게 보임** — 오늘의 통화의 할 일은 글머리표 한 줄, 할 일 화면은 카드, 업체 상세는 또 다른 카드.
4. **시스템 내비게이션 바와 겹침** — 화면 아래 글자가 내비게이션 바 뒤로 들어감(edge-to-edge 인셋 미처리). 설정·업체 상세 하단에서 확인됨. **버그로 먼저 고칠 것.**
5. **줄 간격이 너무 넓고 문단이 길다** — 요약 문단이 3~4줄로 늘어져 카드가 길어짐.
6. **상단 바·뒤로 가기 없음** — "← 업체 목록으로"가 본문 버튼으로 들어가 있음. 화면 간 이동 구조(하단 탭 등)가 없음.
7. "약속 견적서 보내기 (내일까지) · 내가 할 일" 같은 어색한 문구, 공종 배지 "철근"이 태그처럼만 보임.
8. 홈의 처음 설정 카드가 화면 대부분을 차지함(설정 완료 후에는 사라지는지 확인 필요).
- 디자인 방향(설계도 "화면 디자인" 절): 콘크리트 회색 바탕 #F2F4F5, 흰 시트 + 얇은 테두리 #DCE1E5, 도면 청색 #1F5C99, 주의 앰버 #A86F00/#E3A21A, 기한 지남 #B23A2B, 그림자·이모지 없음, 할 일 카드 왼쪽 "공정표 막대". 이 방향은 유지하되 완성도를 올리는 것이 목표.
- 화면은 XML/Compose가 아니라 코드로 View를 만드는 방식(`CallUi.kt` 헬퍼). 디자인 개편 시 `CallUi`부터 정리하면 전 화면에 반영됨.
- 테스트 제약: `ContextLedgerTest`·`CallUiSeniorTest`가 버튼 외 글자 18sp 이상, 영어 상태값 노출 금지를 검사함(50대 기준 유지).

## 5-1. 사용자 결정 (10/30 16시)
- **"모든 권한 허용"** — 시안 선택·UI 개편 범위·도구 설치까지 묻지 말고 끝까지 진행 (시안도 Claude가 고름).
- **화면 캡처 허용** — 앱의 캡처 차단(`CallUi.page`의 `FLAG_SECURE`)을 없애도 됨. 이렇게 하면 별도 스위치 없이 이 PC 에뮬레이터에서 `adb exec-out screencap`으로 바로 화면 확인 가능. (주의: 배포 APK가 `assembleDebug`로 만들어지므로 "debug 빌드만 캡처 허용" 같은 구분은 의미 없음)
- 개발은 사용자가 "시작"할 때까지 일시 중지.

### UI 작업 진행 순서 (확정)
1. 내비게이션 바 겹침 수정 — 원인: `CallUi.page`의 ScrollView가 `clipToPadding=false`라 글자가 시스템 바 뒤로 들어감. 스크롤 패딩은 인셋만, 여백은 안쪽 column으로 옮기고 `clipToPadding=true`.
2. 빠른 확인 환경 — `FLAG_SECURE` 제거 + 이 PC 전용 시험 데이터(업체·사람·통화·할 일)를 넣는 방법(예: `IEUM_LOCAL_PREVIEW=1` 빌드 필드 또는 adb로 실행하는 시험 데이터 채우기). 네이티브 없이(`IEUM_SKIP_ANDROID_NATIVE=1`) 빌드해 에뮬레이터 `ieum34`에 설치 → 몇 분 주기로 고치고 캡처.
3. 시안 2~3개 → Claude가 하나 선택 (PRODUCT.md 4.5 방향, "깔끔함"을 넘어 "세련됨": 글꼴·숫자 강조·아이콘·밀도)
4. 설계도 "화면 디자인" 절 개정
5. `CallUi` 개편 → 전 화면 적용 (18sp 테스트 규칙 유지 여부 결정: 보조 글자 16sp를 쓰려면 `CallUiSeniorTest`/`ContextLedgerTest` 기준을 의도적으로 바꿀 것)
6. device-test(40분) + 실기기 1회로 최종 확인

## 5-2. UI 개편 결과 (10/30 저녁, Windows PC 세션)
- **2차 (사용자: "이게 세련됐냐, 애플 디자인 어워드·iF 등 참고해라, 폰트도 바꿔라")**: 네이비 띠를 없애고 애플 건강·피트니스, Things 3, 토스 수준을 기준으로 다시 잡음. 글꼴 **Pretendard Std**(OFL, `res/font`, 굵기 4종, 약 1.3MB) 하나로 굵기·회색 단계 위계, 강조색 파랑 하나(#3268F0), 테두리 없는 흰 둥근 시트(22dp), 큰 숫자에 작은 단위("5건"), 목록은 그룹 시트 + 동그란 이니셜 배지(`CallUi.list`, `avatar`, `memoRow`), 제목은 화면 위에 바로(색 띠 없음). 시작 화면도 밝은 바탕.
- **1차 "도면" 디자인**(아래 내용 중 색·띠는 2차로 대체됨): 회색 바탕 위 흰 시트, 큰 숫자, 선 아이콘(그려서 만듦, 이미지 파일 없음), **하단 메뉴 5개(홈, 통화, 체크, 업체, 설정)**. 공통 부품은 전부 `CallUi`(band, tabBar, stats, BarChart, StackBar, barRow, taskRow, memoRow, list, avatar, icon).
- **시작 화면 복원**: `LaunchIntro.kt` — 인사("좋은 저녁이에요") → "당신의 통화 에이전트 / 이음" 떠오름 → 사라짐(약 2초, 누르면 건너뜀, 애니메이션 꺼짐·화면읽기 중엔 안 보임).
- **홈 = 대시보드**: 숫자 3개(오늘 통화, 새 체크, 체크할 일) → 처음 설정 → **오늘 통화 메모**(한 문장, 누르면 상세) → 업체별 체크할 일 막대 → 이번 주 통화 막대 그래프.
- **사용자 결정 (꼭 지킬 것)**
  - 모든 화면 글에 가운데 점(·) 금지. 쉼표나 띄어쓰기, 자연스러운 문장으로.
  - 사용자는 부장이라 시키는 일이 많음 → "할 일"이 아니라 **"체크할 일"**. 기한 대신 **요청 날짜**("오늘 요청")를 보여주고, 빨간색 등 급박한 표시는 쓰지 않음. 분석 지시(`ClaudeCallAnalyzer.SYSTEM` 10번)도 상대가 할 일까지 체크할 일로 넣게 바꿈.
  - 목록 글은 "…"로 자르지 말고 첫 문장만(`CallUi.firstSentence`), 자세한 건 눌러서.
  - 기준: "전문가가 오래 공들인 앱처럼, 간단하고 직관적이면서 세련되게, 원하는 정보가 한눈에".
- **이 PC 빠른 확인**: `IEUM_LOCAL_PREVIEW=1 IEUM_SKIP_ANDROID_NATIVE=1`로 `assembleDebug` → 에뮬레이터 설치 → `powershell -File tools/preview_shots.ps1 <이름>` (시험 데이터 `PreviewSeed`가 첫 실행 때 채워짐, 아무것도 밖으로 안 나감). 캡처는 `evidence/shots/`.
- 아직: 설정 화면은 제목 띠만 바뀜(카드 묶음 정리 필요), 아침·저녁 알림 문구의 "할 일" → "체크할 일" 통일, 설계도(Claude Docs) "화면 디자인" 절 개정, 앱 아이콘 다듬기, 모모콜에서 본 "전화 올 때 지난 통화 요약 띄우기" 검토.

## 6. 다음 할 일 (11/6 새벽 기준 — 상세는 [DESIGN_V4.md](DESIGN_V4.md), 묶음 지시문·보고서는 `docs/orchestration/`)
1. **아침 확인(07:34 이후)**: ieum-inbox `status/last-run-hourly.json`이 예정 슬롯마다 갱신되고 `pushed=true`인지, 워치독 이슈가 이상하게 열리지 않았는지. 예약 작업이 `routines/hourly.md`를 실제로 읽는지(새 방식 첫 정규 실행). 안 되면 프롬프트를 확인.
2. **아내 폰 v3.4 설치 확인**: 하트비트 `versionCode`가 V34BUILD로 오르는지(r14-3으로 1시간 안). 안 오르면 `update.error`.
3. **48시간 지표**: 2-3(100초 넘는 통화 완료율 `transcription.long48h.ratePct` ≥ 80, 녹음 연결 matched/withFile ≥ 0.8, `callLogMatch.last.before`·`missGapHour`로 남은 noMatch 원인), 4.5(`callCard` shown·shownOutgoing/eligibleOutgoing ≥ 90%, `ringToCardMs` p50 < 1000, cold, fgsRefused, locked). 결과로 v3.4.x 다듬기(카드 위치 상수, 1.0배 배지 카드 "외 1개" 줄 넘김).
4. **사용자 할 일**: S26 체크리스트(`docs/REAL_DEVICE_CHECKLIST.md`, 10분), 실녹음 골든 받아쓰기 3~5건, 저장소 비공개 복귀 전 Actions 분량 조정(4.4-4), `IEUM_SIGNING_SEED` 복사, 예약 작업 알림 설정(정상 답도 매시간 옴).
5. v3.5: 설계는 Fable이 쓰기로 함(사용자 11/6). 아직 DESIGN_V4에 없음.

### 지난 기록 (11/5)
**11/5 23시 추가(사용자 결정):** v3.2를 새로 끼워 넣음 — 「홈은 요약, 맨 위에 오늘 이야기」(DESIGN_V4 2-1절, 묶음 r12-6, 작업자 진행 중, 브랜치 `feature/r12-v31-r6`). 원래 v3.2 「믿을 수 있는 숫자」는 v3.3, v3.3 「어디서나 보이는 카드」는 v3.4로 밀림. 매시간 루틴 지시문에 5-1(오늘 통화 이야기 `story/today.json`) 추가. 새벽 정리 예약 작업은 tidy v5로 교체 완료(사용자 "예약작업은 너가 수정하면 돼"). 아내 폰은 22:04부터 v3.1(요청 promptVersion 6).

**다음 세션 (11/5 밤 기준):**
1. ieum-inbox `status/phone.json`에서 `versionCode`가 1038 이상(v3.0) 또는 v3.1 빌드인지 본다. 아직 1033이면 사용자에게 DESIGN_V4 1.4절 1~3번(Wi-Fi에서 앱 열기 → 알림 눌러 설치 → 권한·배터리·자동 녹음)을 다시 안내한다. "서명이 다르다"면 지우지 말게 하고 release-apk 로그의 서명 비교를 본다.
2. v3.1이 깔린 뒤 하트비트 24시간: `permissions` 넷, `recordingGapVerdict`, `callLogMatch.missGapMin`·`byEnd`, `latency`, `results`로 녹음 0의 원인을 가려 DESIGN_V4 2절 머리에 적고, `RecordingGap` 임계값(0.3/0.7/0.5)을 실측으로 조정할지 정한다.
3. 배포 48시간 지표(DESIGN_V4 2.7) 확인 뒤 v3.2(DESIGN_V4 3절, 통합 브랜치 `feature/r13-v32`, 묶음 r13-1~3)로. 같은 방식: 키·자리 커밋 먼저 → Opus 작업자 병렬(worktree) → 통합 → ci → lean-calls.
4. 사용자 할 일: tidy.md(v5)·hourly.md 전문을 각 예약 작업에 다시 붙여넣기, `IEUM_SIGNING_SEED` 복사, v3.3 뒤 저장소 비공개 복귀.

**v3.0 평가(11/5 저녁, Fable + Opus 검토 넷 + 설계 검토):** 종합 5/10. 코드는 설계대로지만 아내 폰은 v2.4(1033) 그대로, 녹음과 이어진 통화 0건, 장부 사람 4·업체 4·진행 중인 일 0. 다음 세 버전(v3.1 스스로 말하는 이음, v3.2 믿을 수 있는 숫자, v3.3 어디서나 보이는 카드)은 DESIGN_V4.md 0~4절. **v3.1 착수 조건과 사용자가 지금 할 일(손 설치, 권한·배터리, 자동 녹음 확인)은 DESIGN_V4.md 0절·1.4절** — 손 설치 전에 오케스트레이터가 v2.4 픽스처 직행 마이그레이션 시험(2.4-1)을 157ffab 위에서 먼저 통과시킨다. 묶음은 파일 소유 표대로 겹치지 않게, r12-2의 하트비트 키 커밋이 먼저.
0. ~~저장소 공개와 배포 재개~~ 끝(11/5 08:07 사용자가 공개 전환). **v3.0 배포 뒤 다시 비공개로**(사용자가 직접). 아내 폰은 v2.4에서 v2.6을 한 번 손으로 설치해야 하고(사용자에게 안내함), 그 뒤는 무인 설치. 하트비트 `version`·`updateReadySinceMs`로 무인 설치가 되는지 확인할 것.
1. ~~v2.7 배포~~ 끝(1035). ~~v2.8 통합~~ 끝 — 배포 결과 확인.
2. ~~v2.9~~ 배포(4-8). 루틴 cron 30분 변경은 사용자가.
3. ~~v3.0~~ 배포(4-9). 남은 것: 아내 폰 권한 세 가지 안내(4-9), **저장소 비공개 복귀(사용자가 Settings → Danger Zone → Change to private)**, 하트비트 `callCard`·`resultChain`·`version`으로 실제 동작 확인.
4. 워크플로 절약(제안): 묶음 브랜치 push마다 ci·device-test가 다 돈다. 공개 저장소에서는 무료라 급하지 않음.
5. 남은 사용자 할 일: 루틴 지시문 교체 확인(v2.6 r7-5), 비밀 `IEUM_SIGNING_SEED` 복사, "병합 후 브랜치 자동 삭제" 켜기, 옛 시험 브랜치 삭제.
6. 그대로 남는 것: 문자·메일 실제 확인(MMS, RCS), inbox-e2e 정기 점검, 우편함 이력 압축(J3), 골든 셋 실험(F3, F4).

## 7. 알아 둘 함정
- 예약 작업 프롬프트는 저장소 파일을 읽는 짧은 안내다. 지시문을 바꿀 때는 `docs/routines/*.md`를 고치고 배포하면(release-apk) 우편함에 올라간다. 배포 없이 급히 바꾸려면 ieum-inbox `routines/`에 직접 커밋.
- 예약 작업을 이 세션에서 만들면 이 세션에 붙은 저장소(ieum-app, ieum-inbox)가 소스로 붙는다. 다른 세션에서 만들면 ieum-inbox가 빠져 "성공"인데 아무것도 못 하는 일이 생긴다(11/5 17시). `fire_trigger`(지금 실행)와 끄기·지우기는 자동 권한 검사가 막는 경우가 있어 사용자가 화면에서.
- 이 세션은 CPU 2개라 작업자 둘이 Gradle을 번갈아 기다린다. 작업자는 컴파일·자기 시험만 로컬, 전체 시험·lint는 GitHub ci로(작업자 지침 11/6).
- 통합 브랜치에 `[skip ci]` 커밋만 올리면 ci가 돌지 않는다. 합친 뒤에는 코드나 워크플로가 바뀐 커밋을 머리에 두고 push.
- 이 클라우드 환경에서 repo.maven.apache.org가 429를 자주 낸다(특히 robolectric `nativeruntime-dist-compat`). `$GRADLE_USER_HOME/init.d/mirror.gradle`에 `maven-central.storage-download.googleapis.com/maven2/`를 맨 앞 저장소로 넣으면 풀린다(저장소 설정은 안 바꿈). JDK 17은 `apt-get update` 뒤에 설치된다(`tools/local_toolchain.sh`가 update를 안 하므로 처음 한 번 수동).
- Actions "The job was not started because recent account payments have failed or your spending limit needs to be increased"는 비공개 저장소의 무료 2,000분/월 소진 + 지출 한도 $0일 때 나온다(11/5 02시~, 실행 323회·device-test 최근 100회 약 400분). 공개 저장소는 무제한.
- 이 클라우드 세션의 gh는 저장소 설정 변경(`gh repo edit`)과 계정 billing API가 막힘(저장소 범위 REST만). 저장소 공개/비공개는 사용자가.
- Agent worktree는 때때로 main(67fac72)에서 시작된다 → 작업자 지시문의 reset 문장. 작업자는 worktree 밖(`/home/claude/reports`)에 못 쓴다. 작업자 커밋 꼬리말이 "Claude Opus 5.5"로 붙는 경우가 있음(무해).
- Robolectric 화면 시험의 경쟁: IO 코루틴이 메인 루퍼 idle 전에 끝나 "불러오는 중" 검사가 흔들린다 → `CallsScreen.readHook` 같은 게이트를 쓴다. `UpdateStatusReceiver.later{}`는 별도 스레드라 결과는 `settle`로 기다린다.
- v2.4의 업데이트는 Wi-Fi에서만 APK를 받고(6시간 주기, 앱 열면 30초 뒤 한 번 더), 내려받은 뒤에야 "새 버전" 알림·[지금 업데이트]가 생긴다. 모바일 데이터만 쓰는 폰은 영영 안 받는다 → v3.1에서 네트워크 무관으로(DESIGN_V4 2.2-5).
- release-apk의 서명 인증서 비교는 1034~1038 모두 "not compared"(이전 APK 내려받기 실패)로 실제로 돌지 않았다. 1033과 1038의 인증서가 같다는 자동 증거가 없다(시드가 비어 토큰 파생). 설치가 서명 불일치로 실패하면 지우지 말 것. v3.1 r12-2가 version.json에 `certSha256`을 넣는다.
- 예약 작업은 1시간보다 짧은 간격을 허용하지 않는다(설계 6.3의 30분 불가). 매시간 루틴은 매시 34분 07~22시.
- GitHub Actions 사용량: 에뮬레이터 시험이 한 번에 약 90분을 쓴다. 한도나 결제에 걸리면 모든 워크플로가 몇 초 만에 단계 없이 실패하고 주석에 결제 안내가 뜬다(11/5 03시 발생). 기기 시험은 꼭 필요할 때만, 골든 비교는 `only=tg_goldenSet`으로 따로.
- 이 클라우드 세션에서는 gh의 GraphQL이 막혀 `gh workflow run`/`gh run list`가 안 됨. REST로: `gh api repos/<owner>/<repo>/actions/workflows/<file>/dispatches -f ref=<branch>`, `.../actions/runs/<id>/jobs`, `.../actions/jobs/<id>/logs`.
- 루틴 실행 기록: `RemoteTrigger list_runs` → `get_run_log`. 실행 로그에 통화 내용이 남지 않게 프롬프트에 명시돼 있음.
- ieum-inbox `results/`의 `selftest-*.json`은 inbox-e2e가 검증 후 지움. 폰은 `selftest-`로 시작하는 파일을 무시.
- Firebase 실기기는 하루 5번 — 화면 확인은 에뮬레이터(device-test)로 먼저.
- `CallRecordStore`의 이름 매칭은 공백 무시(`REPLACE(name,' ','')`), 별칭도 공백 제거 키로 저장.
