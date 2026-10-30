# 이음 앱 인수인계 (2026-10-30 15:00 기준, 앱 v1013)

> 다음 세션은 이 파일부터 읽고 "6. 다음 할 일"부터 진행하세요. 이전 인수인계(오전 12:40)를 대체합니다.
> 앱의 목적·기능·UX 설계 상세는 [PRODUCT.md](PRODUCT.md)에 있습니다.

## 0. 작업 규칙 (꼭 지킬 것)
- **보고는 항상 한국어로.** 중간 보고, 최종 보고 모두 예외 없음.
- **결정은 묻지 말고 알아서 정해서 반영.** 정한 내용은 보고에 한 줄로 적기. (단, 약관 동의·외부 게시·결제처럼 되돌리기 어려운 일은 먼저 확인)
- 사용자는 "시작/중지"만 지시함. 개발·테스트·판단은 Claude가 직접 함.
- **테스트도 Claude가 직접.** 사용자에게 테스트를 부탁하지 말 것. UI/UX도 직접 실행·스크린샷으로 판단해 개선.
- 하위 에이전트는 Haiku ~ Sonnet 5.5 high 범위로. Fable이나 Opus high 이상을 쓰면 사용자에게 알릴 것.
- 설계도를 먼저 고치고 → 설계도대로 개발. 병렬 개발 권장.
- 지나치게 방어적이거나 필요 없는 코드는 지워도 됨.
- 비밀번호·토큰·키는 절대 파일이나 대화에 남기지 말 것. 저장소 생성, 클라우드 권한 부여는 사용자가 직접.
- **"통과했다"고 보고하기 전에 종료 코드와 결과 파일을 직접 확인할 것.** (`| tail` 등으로 종료 코드를 가리면 실패를 놓침 — 10/30에 한 번 잘못 보고함)

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
- 분석은 **Claude 구독 한도 방식**(API 키 불필요). API 키 방식은 설정에 남아 있음(우편함 토큰이 없는 빌드에서만 보임).
- 앱 업데이트: `feature/lean-calls`에 push → `release-apk` 워크플로가 빌드해 ieum-inbox 릴리스에 올리고 `app/version.json` 갱신 → 폰 앱이 자동 업데이트.
- 우편함 토큰은 빌드 시 Secret `IEUM_INBOX_TOKEN`에서 주입(설치 직후부터 연결됨).

## 3. 저장소·환경
| 항목 | 값 |
|---|---|
| 앱 저장소 | `github.com/bllue583-commits/ieum-app` (비공개), 작업 브랜치 `feature/lean-calls` |
| 우편함 저장소 | `github.com/bllue583-commits/ieum-inbox` (비공개) — `inbox/`, `results/`, `app/version.json`, 릴리스 APK |
| 예약 분석 루틴 | `trig_01F9MoxFQsQrBHKsx1uhQNtd` "이음 통화 분석 (매시간)", cron `CRON_TZ=Asia/Seoul 0 7-23 * * *`, 소스: ieum-app + **ieum-inbox**(10/30 추가) |
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
| `device-test.yml` | 에뮬레이터: 모델 다운로드, 한국어 Whisper, m4a, 화면 6장 스크린샷, 알림 | 약 40분 |
| `real-device-test.yml` | Firebase 실기기(최신 Galaxy S, 현재 S26). **하루 5번 한도.** 수동 실행(`repeat`, `model`) | 약 40분 |
| `inbox-e2e.yml` | 공개 합성 통화를 inbox에 넣고 → 분석 결과를 폰 파서로 검증 후 삭제 | 약 2분 |
| `claude-live.yml` | API 방식 실분석 시험 | — |
- 같은 브랜치에 새 push가 오면 진행 중인 device-test는 취소됨(동시 실행 제한).

### 여러 컴퓨터에서 이어 하기 (Windows 회사 PC ↔ 집 Mac)
- **GitHub가 유일한 원본.** 작업 단위가 끝날 때마다 `feature/lean-calls`에 커밋·push하고, 세션을 마칠 때는 이 파일의 "다음 할 일"을 갱신해 push. 다른 컴퓨터에서는 `git pull` 후 이 파일부터 읽기.
- 로컬에만 있는 것(빌드 산출물, `robolectric-jar/`, `gradle-user/`, 에뮬레이터)은 컴퓨터마다 따로 준비. 코드·문서는 전부 저장소에 있음.
- Mac 준비(처음 한 번): Homebrew로 `git gh python@3.12`, JDK 17(`brew install --cask temurin@17`), Android cmdline-tools(`brew install --cask android-commandlinetools`) → `sdkmanager`로 아래 Windows와 같은 패키지(시스템 이미지는 Apple Silicon이면 `arm64-v8a`) → `gh auth login` → `git clone -b feature/lean-calls https://github.com/bllue583-commits/ieum-app.git`. Mac에는 `make`가 있어 Whisper 네이티브 빌드와 Robolectric 단위 테스트도 로컬에서 될 가능성이 높음(미확인).

### 이 PC(Windows) 로컬 도구 (10/30 저녁 다시 설치: Git·gh·Python 3.12는 winget, `gh auth login` 완료, 저장소는 `C:\Users\bllue\ieum-app`)
- Android SDK: `C:\Users\bllue\Android\Sdk` (platform 36, build-tools 35, NDK 28.2.13676358, cmake 3.22.1, emulator, `system-images;android-34;google_apis;x86_64`), 에뮬레이터 이름 `ieum34`
- JDK 17: `C:\Users\bllue\tools\jdk-17` (Gradle 툴체인이 17을 요구)
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
/c/Users/bllue/Android/Sdk/emulator/emulator.exe -avd ieum34 -no-audio -no-snapshot -gpu swiftshader_indirect
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

## 6. 다음 할 일 (순서대로)
1. **UI 개편 (1순위)** — 위 5번 문제 해결. 설계도 "화면 디자인" 절을 먼저 개정 → `CallUi` 정리(버튼 2단계: 주요/보조·텍스트 버튼, 아이콘, 상단 바, 인셋 처리, 줄 간격) → 각 화면 적용 → device-test 스크린샷으로 직접 판단 → 필요 시 실기기 1회.
1-1. **이메일·문자 내역도 확인 (사용자 요구, 10/30 저녁)** — (10/30 밤 구현: `MessageWork.kt`. 문자는 모든 번호를(사용자 결정: "개인 문자·메일까지 읽어서 업무용인지 판단") 번호별로 묶되 광고·인증번호·카드/은행/배송 알림·짧은 번호는 폰에서 버리고, 분석의 `business`가 false면 문자·메일 기록은 삭제, 통화는 내용을 지우고 숨김. 메일은 IMAP(JavaMail Android, 네이버·다음·Gmail·직접 입력, 앱 비밀번호는 SecureSettings에 암호화)으로 받은편지함 새 메일을, 30분마다 `sms:`/`mail:` 기록으로 넣어 통화와 같은 분석·장부·체크할 일로 처리. 분석 지시 17번에 종류 설명. 설계도 "문자와 메일" 절에 반영. **실제 폰의 문자·메일로는 아직 확인 안 함**, 장부에 없는 새 번호의 문자와 M365 같은 별도 로그인 메일은 범위 밖.) 처음 설정에 넣은 것은 설정 화면에서 언제든 다시 바꿀 수 있어야 함(사용자 요구). — 10/30 오전 "예전 기능 정리"(56fb9d7)에서 네이버 메일·문자(`AndroidSmsReadOnlySource`) 읽기가 통째로 지워졌지만, 사용자는 통화와 함께 이메일·문자도 확인돼야 한다고 함. UI 개편 직후 착수: 옛 코드(`56fb9d7~1`의 `AndroidSmsReadOnlySource.kt`, `naver-mail-*` 모듈)를 참고해 문자·메일을 통화와 같은 장부(업체·사람·할 일)로 정리하는 설계부터. 개인정보 원칙(원문은 폰 밖으로 최소한만)은 통화와 같게.
1-2. **UI 요구 (10/30 저녁, 사용자)** — 홈은 막대 그래프 등으로 현황을 한눈에, 하단 메뉴(아이콘 버튼)로 자세히 보기, 시작 시 "당신의 통화 에이전트 · 이음"이 잠깐 나타났다 사라지는 시작 화면(옛 `LaunchIntro.kt`). 기준: "아름답고 깔끔하지만 세련되게".
2. **아내 폰(S25) 설치 확인** — 사용자가 APK 파일로 직접 설치 예정(`C:\Users\bllue\Downloads\ieum-v1013.apk`, sha256 `b29705d8…a50f7`). 설치 후 실제 녹음 폴더 연결 → 첫 통화가 1시간 안에 정리되는지 확인. 이후 업데이트는 자동.
3. **inbox-e2e 정기 점검이 실제로 돌지 않음** — `schedule` 트리거는 기본 브랜치(`main`)의 워크플로만 실행되는데 워크플로가 `feature/lean-calls`에만 있음. main에 합치거나 다른 방법 필요. (10/30 16시 수동 실행: Sonnet 실제 분석 결과가 폰 검증기 **PASS**. 결과 2개를 한 번에 검사하다 로그 이름 충돌로 두 번째가 FAIL → 워크플로 수정함. 다음 수동 실행에서 재확인)
4. **예약 분석 루틴의 effort** — API로 확인·변경 불가. claude.ai/code/routines 에서 사용자가 medium으로 설정해야 함(안내만).
5. **구독 5시간 한도와 충돌** — 10/30 12시 실행이 한도 초과로 실패함. 개발 세션과 매시간 분석이 같은 구독 한도를 씀. 실패해도 다음 정각에 다시 처리되지만, 저녁 7시 정리 직전 실패 시 누락 가능 → 저녁 알림 전에 미처리 건 표시 여부 확인.
6. 토큰 만료 전 서명용 비밀 따로 고정 (설계도 남은 일)
7. `feature/lean-calls`를 `main`에 합치는 시점 결정 (자동 업데이트·schedule 트리거 정리와 함께)

## 7. 알아 둘 함정
- 루틴 실행 기록: `RemoteTrigger list_runs` → `get_run_log`. 실행 로그에 통화 내용이 남지 않게 프롬프트에 명시돼 있음.
- ieum-inbox `results/`의 `selftest-*.json`은 inbox-e2e가 검증 후 지움. 폰은 `selftest-`로 시작하는 파일을 무시.
- Firebase 실기기는 하루 5번 — 화면 확인은 에뮬레이터(device-test)로 먼저.
- `CallRecordStore`의 이름 매칭은 공백 무시(`REPLACE(name,' ','')`), 별칭도 공백 제거 키로 저장.
