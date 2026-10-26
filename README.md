# 이음 (IEUM)

`integration/major-features-20261026`의 후속 개발은 공개 음성의 실제 JNI 분석 → 맥락 검토후보 → 명시 사용자 검토/결속 → 보호 저장 → 로컬 아침보고 outbox를 연결합니다. 지원 범위·시험 화면·현재 증거와 미검증은 [통합 기록](docs/INTEGRATION_20261026.md)을 참고합니다. 외부 수집/의미 분석·실제 발송·S25 인수 완료를 의미하지 않습니다. 아래 Git 이관 설명은 기준 main의 기록입니다.

이음은 허용된 통화 기록·녹음과 관련 문자·메일의 근거를 연결해 업무 기억, 진행 상황, 필요한 서류와 할 일을 돕는 Android 앱입니다. 현재 버전은 `kr.callbrief`, versionCode 34, `0.13.4-android-local-whisper-dev`입니다. 이 저장소는 code34 v2의 검증된 맥북 소스를 Git 개발용으로 이관한 것입니다. 이번 이관에서 앱 기능·권한·계약·의존성 버전은 변경하지 않았습니다.

## 구현 경계

- 날짜별·업무별·할일·보관 화면과 보호 저장, 요청/결과 binding, 수동 수정·DONE 보호 코드가 있습니다.
- code34 공개 한국어·영어 합성 WAV의 기기 내부 CPU Whisper 시험과 기존 provider → consumer → Coordinator → Store.accept 경로가 있습니다. 실제 일반 통화 자동 분석 완료를 의미하지 않습니다.
- 일반 개인 녹음의 생산 Android 연결, 실제 Google/GMS/Drive 왕복, dot 자동 음성 수신·실행, high/dot 의미 검토, 문자·네이버 종합, KST 23시/증분/06시 운영 인수, 실제 카카오 수신은 미완료입니다. 무인 카카오 발송은 DISABLED입니다.
- `docs/plans/`는 **미구현 설계·제안 계약·향후 인수 시나리오**입니다. Windows의 이전 작업은 제품 변경 0이며 이관된 신규 구현으로 세지 않습니다.
- 과거 `android/README.md`, `android/BUILD_STATUS.md`는 code30 이하 역사 기록을 포함합니다. 현재 상태는 이 문서와 `docs/CODE34_HANDOFF_KO.txt`를 먼저 읽습니다. 과거 시험 수와 현재 재실행 수는 합산하지 않습니다.

## 구성

| 경로 | 내용 |
| --- | --- |
| `android/` | Kotlin/Java 앱, 저장·Drive·메일·카카오 모듈, JVM/기기 시험, Gradle wrapper |
| `worker/` | 기존 Node 처리·검증 코드와 공개 합성 시험. 새 Cloudflare worker 생성/배포 없음 |
| `local_stt/` | 이전 Linux x86_64 고정 실행 bridge. Mac/Windows 일반 STT 실행기로 확장하지 않음 |
| `tools/`, `run_gradle.py` | 명시적 로컬 도구경로를 받는 빌드/시험 도구 |
| `docs/` | 자산 핀·출처·이관 범위·병렬 개발 계약·미구현 계획 |
| `third_party/` | Whisper MIT 고지와 SHA 고정 v1.7.6 소스 tar (모델·SDK 아님) |

## 요구 환경

macOS와 Windows 공통: Git, JDK 17, Python 3.10+, Node 22.13 이상(`node:sqlite` 사용), Android SDK platform 36, Build Tools 35.0.0. Gradle 8.13 wrapper, AGP 8.11.1, Kotlin 2.1.21은 소스에 고정되어 있습니다. 도구/라이선스는 본인이 준비하고 동의하며 스크립트는 SDK를 자동 설치하지 않습니다.

Android JNI 자산 생성: NDK r28c `28.2.13676358`, CMake 3.22 이상, make, 공개 tiny-q5_1 모델. Mac은 기존 준비된 도구를 사용합니다. Windows native 빌드는 CMake의 Unix Makefiles generator와 `IEUM_MAKE_BINARY`로 지정한 make가 필요합니다. 이 경로의 Windows 실빌드는 아직 검증하지 않았습니다. 필요하면 기존 승인된 Mac에서 JNI를 생성해 로컬 빌드 산출물로만 전달합니다. 생성 자산을 Git에 커밋하지 않습니다.

## 최초 준비와 빌드

먼저 본인 GitHub 계정으로 비공개 저장소 접근을 설정합니다. 저장소는 https://github.com/bllue583-commits/ieum-app 이며 접근 권한이 있는 계정으로 clone합니다. 암호·토큰·키는 파일/채팅/커밋에 넣지 않습니다. 각 컴퓨터에서 별도로 인증하며 Mac 자격증명을 Windows로 복사하지 않습니다. 공식 GitHub CLI를 사용하는 경우 `gh auth login --hostname github.com --git-protocol https --web`의 권한을 확인하고 직접 승인합니다. [공식 인증 안내](https://cli.github.com/manual/gh_auth_login)를 참고합니다.

macOS (각 경로를 로컬 설치 위치로 바꾸기):

```sh
git clone https://github.com/bllue583-commits/ieum-app.git ieum-app
cd ieum-app
export JAVA_HOME="/path/to/jdk17"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export IEUM_NODE_BINARY="$(command -v node)"
export IEUM_ANDROID_NDK_ROOT="/path/to/android-ndk-r28c"
export IEUM_CMAKE_BINARY="/path/to/cmake"
export IEUM_ANDROID_WHISPER_MODEL="/path/to/ggml-tiny-q5_1.bin"
python3 tools/prepare_host_tests.py /path/to/android-all-instrumented-15-robolectric-13954326-i7.jar
python3 run_gradle.py --log first-build.log :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleValidation
```

Windows PowerShell:

```powershell
git clone https://github.com/bllue583-commits/ieum-app.git ieum-app
cd ieum-app
$env:JAVA_HOME = 'C:\Tools\jdk-17'
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:IEUM_NODE_BINARY = (Get-Command node).Source
$env:IEUM_ANDROID_NDK_ROOT = 'C:\Tools\android-ndk-r28c'
$env:IEUM_CMAKE_BINARY = 'C:\Tools\cmake\bin\cmake.exe'
$env:IEUM_MAKE_BINARY = 'C:\Tools\make\bin\make.exe'
$env:IEUM_ANDROID_WHISPER_MODEL = 'C:\Models\ggml-tiny-q5_1.bin'
py -3 tools\prepare_host_tests.py C:\Tools\android-all-instrumented-15-robolectric-13954326-i7.jar
py -3 run_gradle.py --log first-build.log :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleValidation
```

첫 빌드에는 온라인 의존성 준비가 필요합니다. 캐시가 준비되어 있으면 `run_gradle.py --offline --log ...`를 사용합니다.

Robolectric는 기존 빌드 설정이 offline JAR 위치를 고정합니다. `prepare_host_tests.py`는 제공된 공개 JAR를 SHA 검증 후 생성 디렉터리로 복사합니다. 자산 출처/해시는 [ASSETS](docs/ASSETS.md)를 따릅니다. `IEUM_SKIP_ANDROID_NATIVE=1`은 JNI 생성만 생략합니다. JVM/lint 용도로만 사용하고 이 상태의 APK를 정상 STT APK로 인수하지 않습니다.

원래 앱에 업데이트 설치하려면 기존 서명키가 필요합니다. **그 키는 이 저장소에 없습니다.** 필요한 경우 기존 승인된 로컬 키 경로를 `IEUM_EXISTING_DEBUG_KEYSTORE`에 지정합니다. 기존 설치와 서명이 다른 APK를 덮어쓰기 위해 앱/데이터를 지우지 않습니다. 새 개발 서명으로 만든 APK는 별도 시험 기기에서 사용합니다. `android/app/build/outputs/apk/`에 APK가 생성됩니다.

## 시험과 첫 실행

```sh
cd worker
node --test tests/*.test.mjs
node src/cli.mjs --check config.example.json
cd ..
python3 -m unittest discover -s tests -v
python3 docs/plans/context/verify_fixture_integrity.py
python3 docs/plans/kst-report/verify_contract.py
python3 tools/scan_export.py
```

PowerShell에서도 Node 명령은 같습니다. Python은 `py -3`로 바꿀 수 있습니다. Python의 이전 관측 입력 두 시험은 외부 자료 미준비 시 명시적으로 SKIP됩니다. Android T81~T84 네 시험은 `IEUM_HISTORICAL_SYNTHETIC_AUDIO`의 승인된 과거 합성 WAV가 필요하고 미준비 시 SKIP됩니다. Android P3302/P3303 두 시험은 별도 `IEUM_LOCAL_STT_PIN_FILE`과 일치하는 기존 로컬 엔진/모델이 필요해 미준비 시 SKIP됩니다. `IEUM_PRIOR_OBSERVED_DIR`에 승인된 원 관측 자료를 준비해야 하며 새로운 음성 실행으로 세지 않습니다. `local_stt`의 Linux 전용 실행 및 POSIX process group 시험은 Windows 제품 기능을 보증하지 않습니다.

별도 승인된 시험 단말에 APK를 설치합니다. 초기 설정 → Google 계정 관리 → 공개 음성으로 기기 요약 시험 → 공개 음성 2개 처리를 사용합니다. 이 공개 시험에는 Google 로그인/Drive 연결이 필요하지 않습니다. 원음/실계정/새 권한/원격 처리/유료 실행을 임의로 켜지 않습니다. 기존 `launch_private_avd.py`는 Mac ARM64의 사전 준비된 별도 AVD 전용이며 Windows용 AVD 생성기가 아닙니다. 실제 기기 시험 도구는 포트 5044/serial emulator-5594의 별도 AVD 전용이며 일반 휴대폰에 자동 실행하지 않습니다. Windows Node의 symlink 시험에는 해당 OS의 symlink 생성 권한이 필요합니다. Windows 시험을 실제 실행하기 전에는 통과로 기록하지 않습니다.

## Git 작업 흐름

이전 Mac 원본은 보존용입니다. 앞으로 개발은 **이 저장소의 clone/checkout**에서 진행합니다. 시작할 때 `git status`를 확인하고, 작업 중인 변경을 다른 사람 코드로 덮지 않습니다.

새 clone에서는 커밋 작성자도 한 번 설정합니다: `git config user.name "<your-name>"`, `git config user.email "<verified-email-or-GitHub-noreply>"`. 기존 설정이 있으면 먼저 확인하고 임의로 바꾸지 않습니다.

```sh
git switch main
git pull --ff-only
git switch -c feature/<topic>
# 파일 변경, 관련 시험 실행
python3 tools/scan_export.py
git diff --check
git add <reviewed-paths>
git diff --cached --stat
git commit -m "Describe the concrete change"
git push -u origin feature/<topic>
```

기능마다 다른 branch와 작업 폴더를 씁니다. `git worktree add ../ieum-<topic> -b feature/<topic> main`도 가능합니다. 공유 checkout에서 여러 worker가 동시에 수정하지 않습니다. `main`에 직접 동시 push하거나 force push하지 않습니다. 담당자별 경로와 공유 DB/계약 변경을 먼저 조율하고 PR을 검토해 순서대로 통합합니다. 최신 main 반영 후 관련 회귀를 재실행합니다. [개발 경계](docs/DEVELOPMENT.md)를 따릅니다.

파일명은 새 파일에 영문 소문자/숫자/하이픈을 권장하고 Windows 예약명·대소문자만 다른 파일·끝 점/공백·콜론을 피합니다. 기존 한글 문서명은 보존합니다. UTF-8, 기본 LF, `.bat/.ps1`은 CRLF이며 `.gitattributes`를 따릅니다. 기존 AGENTS/skills가 있는 작업에서는 보존·준수합니다.

## 자산·개인정보·라이선스

APK·모델·SDK·캐시·생성 build·실사용 DB·통화/문자/메일/연락처 원본·키·토큰·`.env`·서명키·불필요한 개인 로그는 제외합니다. 공개 합성 fixture와 테스트 전용 값의 출처는 [FIXTURES](docs/FIXTURES.md)에 있습니다. 기존 frozen fixture 안의 계정 모양 값과 토큰 canary는 실제 서비스 접근이나 실사용 메시지 자료가 아닙니다. 임의로 해시/ID를 바꿔 동결 계약을 깨지 않습니다.

제품 소스에 새로운 공개 라이선스는 부여하지 않았습니다. 비공개 개발용이며 upstream MIT 및 Gradle wrapper의 고지를 유지합니다. [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)를 참고합니다. 현재 이관 검증 결과와 미실행 범위는 [MIGRATION](docs/MIGRATION.md)에 기록합니다.
