# 이음 앱 인수인계 (2026-11-05 기준, Fable 클라우드 세션)

> 다음 세션은 이 파일부터 읽고 "4-3. v2.0"과 "6. 다음 할 일"부터 진행하세요. 설계도(Claude Docs)가 기준이며, 모든 변경은 설계도를 먼저 고친 뒤 개발합니다(11/4 사용자: "모든 과정에서 설계도를 철저히 반영").
> 앱의 목적·기능·UX 설계 상세는 [PRODUCT.md](PRODUCT.md)에 있습니다.
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
| 예약 작업(루틴) | 세 개(매시간 분석 `trig_01WhkNFfBvGYvgaraXKuwL5K` 매시 30분 Sonnet 5.5, 새벽 정리 `trig_01BJ7psN5c92FCshDpqF6maz` 05:16 Opus 5.5, 통화 목록 검증 `trig_01KyuDNZWkmrQpJj8e8cr661` 09:00 Opus 5.5), 소스 ieum-inbox. 상세와 기억 파일은 "4-4"의 표와 OPERATIONS.md 2절. 옛 루틴 셋은 지움. 루틴 추가·수정은 사용자 승인됨(11/4), 바꿀 때마다 이름, 실행 시각, 링크를 사용자에게 알리고 이 표와 OPERATIONS.md를 고칠 것 |
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
- 상태: **코드 완성, 배포 대기.** 통합 브랜치 `feature/r7-v26`(v2.5 `feature/r6-v25` 81f9bd0과 lean-calls 문서를 모두 포함)에서 로컬 전체 단위 시험 423건 통과, lint 오류 0, 화면 규칙 0건. GitHub Actions가 결제 문제로 멈춰 ci·배포는 못 함. 결제가 풀리면: v2.5 배포(4-4 담당 세션) → `feature/r7-v26`의 ci 확인 → lean-calls에 합쳐 배포(v2.6) → 하트비트에서 `firstPass.blockedBy`, `newQueuedCount`, `callLogMatch`로 처음 정리가 흐르기 시작했는지 확인.
- 묶음(모두 Opus 작업자, 각자 worktree·브랜치 `feature/r7-v26-r1`~`r6`, 공통 지시 `/home/claude/worker-brief.md`):
  - r7-1 처음 정리 관문: 열 때 10/1 이전 QUEUED→SKIPPED 한 문장, `oldestOldJobMs`는 10/1 이후만, offer 루프 트랜잭션 1회+중지 신호, **live=기록 시각 7일 이내**(`FirstPass.LIVE_DAYS`, `liveFrom`, `isOld(now)`, `stale`; `beforeStart` 삭제), 덜 찬 묶음은 6시간(createdMs) 뒤 전송, `LAST_INBOX_ERROR`에 모든 코드+`LAST_INBOX_ERROR_MS`, 하트비트 `firstPass.bound/blockedBy`.
  - r7-2 발견 경량화: 이름 시각 우선 10/1 컷오프(`DiscoveryCutoff`), SAF 목록은 옛 녹음을 세지 않고 2000 한도는 남긴 것에만(넘치면 최신 유지+`inventoryTruncated`), 큐 UPSERT·audit 100개, 시도마다 `lastDiscoveryAttemptMs/lastDiscoveryOutcome`, 트리거 finally 재등록·지연 140초, 녹음 한도 60MB(옛 DB의 25MB CHECK는 `VaultSizeLimit.widen`으로 한 번 넓힘, 금고·스키마 숫자 포함).
  - r7-3 무인 설치: `CallUpdate.installInBackground`(USER_ACTION_NOT_REQUIRED, API31+, 세션 id 보관), PENDING이면 알림, 성공 시 키 0, 실패는 `LAST_UPDATE_ERROR` 80자, 설정 스위치 "새 버전은 자동으로 설치"(`AUTO_INSTALL`), 키 `UPDATE_AVAILABLE_CODE`·`UPDATE_READY_SINCE_MS`. 사이드로드 폰에서는 첫 한 번 확인을 물을 수 있음(그 뒤 앱이 설치자).
  - r7-4 정확도: `FirstPass.match`가 이름 불일치·CACHED_NAME null이어도 후보 하나면 잇고 실패 사유·시작 차이 히스토그램을 `callLogMatchStats`에 쌓음, `NameKey` 호칭 추가·초성 비교·성+직책 규칙·사무실 제외, 제목 중복은 이름·업체·동작어 제거 뒤 비교(근사 중복은 `mergeCandidates`), 반복 인용은 핵심어 근처만·창 기반 퍼지, 자막 줄바꿈, 열린 일 `done_ms` 조건, 별칭 이동·trade "기타"·updated_ms 유지, 전화부 이름·메일 주소·10자리 유선 가리기, 자막 비우기 `analyzed_ms` 기준, `MIGRATION_VERSION=3`(합치기는 같은 번호·업체만, `mergeLog`).
  - r7-5 지시문 v6: `docs/routines/hourly.md`(장부 사람·업체 추가 규칙, 화자·날짜·확실성·update 조건·성+직책·명절 인사), `tidy.md`(제안에 rev, mergedInto 유지, mergeCandidates), `PROMPT_VERSION=6`, `tidyState` rev·mergeCandidates, `applyTidy` rev 비교, `RoutineDocTest`. **루틴 프롬프트 교체는 자동 권한 검사가 막아 사용자가 직접**(붙여 넣을 전문을 11/5 03시 사용자에게 보냄: `hourly_v6_routine.txt`, `tidy_routine.txt`; 저장소 `docs/routines/*.md`의 "---" 아래와 같음). 10번(제목에서 사람 이름 제거)은 v2.7에서 taskRow META가 이름을 보여 준 뒤 적용.
  - r7-6 감시 정확화: 신호등 입력을 실제 상태로(폴더 권한+발견 24시간, 새 녹음 대기열 진입 시각, live 지연, 업데이트 24시간), 하트비트 새 필드(newQueuedCount 등, dbPercent는 sealed 파일·행 수, standbyBucket EXEMPTED/NEVER), 워치독 새 규칙(ieum-inbox에 복사함, 커밋 1e52b1b), 저장 공간 % 수리, 긴 통화 stall 완화(진척 있는 시간 한도 중지는 안 셈).
- 통합 중 고친 것: 신호등 대기열 판정에 시계 인자(7일 live 기준과 맞춤), `RoutineDocTest`가 새벽 정리 실제 답 형식("정리할 것 없음: 이유")을 허용, 지시문 문서를 실제 루틴 문구에 맞춤.
- 로컬 빌드 환경(이 세션의 클라우드 작업 환경): `/home/claude/toolchain/env.sh`(JDK 17, SDK 36, Robolectric jar), `flock /home/claude/gradle.lock python3 run_gradle.py ...`로 한 번에 하나만. 메이븐이 429를 자주 내므로 첫 의존성 받기는 재시도 필요.
- v2.7은 `feature/r8-v27`(v2.6 통합 HEAD에서 시작)에서 1단계 r8-1(저장소 성능)·r8-2(Activity 하나)·r8-3(즉시 반응 부품) 진행 중.

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

## 6. 다음 할 일 (순서대로, 11/5 v2.5 이후 — 상세는 [DESIGN_V3.md](DESIGN_V3.md))
0. **먼저 v2.5 배포**(코드는 `feature/r6-v25`에 완성): 사용자가 GitHub Actions 결제·한도를 풀면 ci 확인 → lean-calls에 합침 → 배포 → 아내 폰 설치. 이어서 골든 비교 수동 실행 한 번(4-4). v2.6은 v2.5를 합친 lean-calls에서 시작.
1. **v2.6 제대로 정리되는 이음** (`feature/r7-v26`, 묶음 r7-1~r7-6): 처음 정리 관문(b- 묶음) 풀기와 10/1 이전 대기열 정리, 발견 경량화(2000 한도·이름 시각), 무인 설치, 통화기록 대조와 이름 정규화, 지시문 v6(루틴 교체 포함), 감시 정확화. ci 통과 후 배포.
2. **v2.7 매끄러운 화면** (`feature/r8-v27`, r8-1~r8-6): 저장소 프로세스 수명 단일 열기, Activity 하나(G1), 즉시 반응 부품, 화면 31건 수정, 아침 알림 [다 됐어요]. **device-test 스크린샷을 보고** 배포.
3. **v2.8 상대 카드와 사람 화면** (`feature/r9-v28`, r9-1~r9-3): PhoneKey 한 곳, 상대 카드 저장소, 인덱스·복구·용량, 사람 화면. ci 통과 후 배포.
4. **v2.9 통화 직후 정리** (`feature/r10-v29`, r10-1~r10-3): 전화 상태 감지, 통화별 알림과 메모 남기기, 결과 10분 체인과 루틴 30분 주기. ci 통과 후 배포.
5. **v3.0 통화 중 이음 카드** (`feature/r11-v30`, r11-1~r11-3): 오버레이 카드, 발신 번호(통화 연결 역할), 통화 중 서비스, 잠금 화면 폴백. **device-test(통화 시뮬레이션)와 실기기 확인** 뒤 배포.
6. 남은 사용자 할 일: 비밀 `IEUM_SIGNING_SEED`에 현재 토큰 값 복사, 저장소 "병합 후 브랜치 자동 삭제" 켜기, 옛 시험 브랜치 삭제. v2.9·v3.0 배포 뒤 아내 폰에서 권한 각 한 번 허용(DESIGN_V3.md 9절).
7. 그대로 남는 것: 문자·메일 실제 확인(MMS, RCS), inbox-e2e 정기 점검(main 합칠 때), 우편함 이력 압축(J3), 골든 셋 실험(F3, F4).

## 7. 알아 둘 함정
- GitHub Actions 사용량: 에뮬레이터 시험이 한 번에 약 90분을 쓴다. 한도나 결제에 걸리면 모든 워크플로가 몇 초 만에 단계 없이 실패하고 주석에 결제 안내가 뜬다(11/5 03시 발생). 기기 시험은 꼭 필요할 때만, 골든 비교는 `only=tg_goldenSet`으로 따로.
- 이 클라우드 세션에서는 gh의 GraphQL이 막혀 `gh workflow run`/`gh run list`가 안 됨. REST로: `gh api repos/<owner>/<repo>/actions/workflows/<file>/dispatches -f ref=<branch>`, `.../actions/runs/<id>/jobs`, `.../actions/jobs/<id>/logs`.
- 루틴 실행 기록: `RemoteTrigger list_runs` → `get_run_log`. 실행 로그에 통화 내용이 남지 않게 프롬프트에 명시돼 있음.
- ieum-inbox `results/`의 `selftest-*.json`은 inbox-e2e가 검증 후 지움. 폰은 `selftest-`로 시작하는 파일을 무시.
- Firebase 실기기는 하루 5번 — 화면 확인은 에뮬레이터(device-test)로 먼저.
- `CallRecordStore`의 이름 매칭은 공백 무시(`REPLACE(name,' ','')`), 별칭도 공백 제거 키로 저장.
