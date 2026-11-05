# 이음 (IEUM)

통화가 끝나면 자동으로 통화 내용을 정리해, 홈 맨 위에 오늘 통화 이야기와 먼저 볼 체크할 일을, 저녁에는 오늘 통화를, 아침에는 체크할 일을 알려 주고, 전화가 오거나 걸 때는 통화 화면 위 카드로 그 상대와 약속한 일을 보여 주는 Android 앱입니다(패키지 `kr.callbrief`, 지금 v3.4). 녹음 파일은 폰 밖으로 나가지 않고, 폰에서 만든 자막만 비공개 우편함 저장소(`ieum-inbox`)로 보내 Claude 예약 작업이 분석합니다.

## 구조

| 경로 | 내용 |
| --- | --- |
| `android/app/` | 앱. 통화 흐름은 `kr.callbrief.pipeline`(통화 폴더 발견, 기기 내 Whisper 자막, 우편함 전송·결과 수신, 화면), 암호화 금고·저장은 `kr.callbrief` |
| `android/core`, `android/call-core-android`, `android/audio-vault-android`, `android/recording-discovery-android` | 순서대로 개인정보·전화번호 키 규칙(순수 Kotlin), 보호 DB(`ProtectedDatabase`)와 SQL 포트, 녹음 암호화 금고, 녹음 폴더 발견 모듈(`settings.gradle.kts`의 다섯 모듈 중 앱 밖 넷) |
| `android/scripts/` | 로컬 빌드 확인 스크립트 |
| `android/app/src/main/cpp/` | Whisper 네이티브 코드(JNI). 소스 tar는 `third_party/` |
| `tools/` | 서명 키 유도, 네이티브 빌드, 호스트 시험 준비, 화면 규칙·내보내기 검사, 로컬 툴체인(`local_toolchain.sh`), 기기 시험(`device/`), 워치독 원본(`inbox/`), 배포 필드(`release/`), 시험 음성(`audio/`) |
| `run_gradle.py` | Gradle 실행 도우미(로그를 `evidence/`에 남김) |
| `.github/workflows/` | ci(`android-ci.yml`), 자동 배포(`release-apk.yml`), 덮어 설치 시험(`update-smoke.yml`), 기기 시험(`device-test.yml`, `real-device-test.yml`), 우편함 종단 시험(`inbox-e2e.yml`), 브랜치 정리(`cleanup-branches.yml`) |
| `docs/` | 문서 (아래) |

## 개발

작업 브랜치는 `feature/lean-calls`입니다. 새 시험은 `feature/…` 브랜치에서 하고 CI 통과 후 합칩니다. 작업 방식(설계도 먼저, 작업자 병렬, 통합, 배포)과 클라우드 작업 공간의 빌드 환경은 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)에 있습니다. push하면 ci가 단위 시험과 lint를 돌리고, 통과한 커밋만 `release-apk`가 서명 APK로 빌드해 우편함 릴리스에 올립니다. 폰 앱이 이를 받아 자동으로 업데이트됩니다.

로컬 빌드와 시험(JDK 17, Android SDK 36, Build Tools 35.0.0, Python 3.10 이상 필요):

```sh
export JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/android-sdk
export IEUM_SKIP_ANDROID_NATIVE=1   # Whisper 네이티브 빌드를 건너뜀(시험·lint 전용)
python3 tools/prepare_host_tests.py /path/to/android-all.jar   # Robolectric JAR, 처음 한 번
python3 run_gradle.py --log first.log :core:test :app:testDebugUnitTest :app:lintDebug
```

APK에는 네이티브 빌드(NDK 28.2.13676358, CMake 3.22 이상)가 필요하며 배포 워크플로가 대신 합니다. 비밀번호·토큰·키는 파일이나 커밋에 넣지 않습니다.

## 문서

- [docs/DESIGN.md](docs/DESIGN.md): 현재 설계도(기술). 구현된 그대로의 구조, 흐름, 저장, 시험. 설계를 바꿀 때 먼저 고칩니다.
- [docs/DESIGN_V5.md](docs/DESIGN_V5.md): 다음 버전(v3.5 카톡 아침 보고) 설계. 배포 뒤 `docs/history/`로.
- [docs/HANDOFF.md](docs/HANDOFF.md): 인수인계. 현재 상태, 다음 할 일, 워크플로, 함정. 새 세션은 이 파일부터 읽습니다. 지난 세션 이력은 `docs/history/HANDOFF_HISTORY.md`.
- [docs/PRODUCT.md](docs/PRODUCT.md): 앱 목적, 기능, 화면과 UX 설계(무엇을, 누구를 위해, 어떻게 보여 줄지).
- [docs/OPERATIONS.md](docs/OPERATIONS.md): 예약 작업, 우편함, 상태 파일, 워치독, 배포 흐름. 지시문 원본은 `docs/routines/`.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): 작업 방식, 지켜야 할 것, 로컬 빌드 환경.
- [docs/REAL_DEVICE_CHECKLIST.md](docs/REAL_DEVICE_CHECKLIST.md): 아내 폰(S26) 10분 확인표.
- `docs/history/`: 버전별 설계·구현 대조 이력(`DESIGN_V3.md` v2.6~v3.0, `DESIGN_V4.md` v3.1~v3.4)과 HANDOFF 이력.
- `docs/reviews/`: 검토 원문(11/4 종합 검토, 11/5 화면·파이프라인·데이터, v3.3 자막 협의안, v3.4 설계 검토). 작업자 지시문과 보고서는 `docs/orchestration/`.
- [docs/MIGRATION.md](docs/MIGRATION.md), [docs/ASSETS.md](docs/ASSETS.md), [docs/FIXTURES.md](docs/FIXTURES.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): 저장소 이전 기록, 모델·시험 자료 출처와 라이선스.

개발용 저장소이며(11/5부터 Actions 때문에 공개, 비밀은 들어 있지 않음) 공개 라이선스는 부여하지 않았습니다.
