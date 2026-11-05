# 이음 (IEUM)

통화가 끝나면 자동으로 통화 내용을 정리해, 저녁에는 오늘 통화와 할 일을, 아침에는 오늘 챙길 일을 알려 주는 Android 앱입니다(패키지 `kr.callbrief`). 녹음 파일은 폰 밖으로 나가지 않고, 폰에서 만든 자막만 비공개 우편함 저장소(`ieum-inbox`)로 보내 Claude 예약 작업이 분석합니다.

## 구조

| 경로 | 내용 |
| --- | --- |
| `android/app/` | 앱. 통화 흐름은 `kr.callbrief.pipeline`(통화 폴더 발견, 기기 내 Whisper 자막, 우편함 전송·결과 수신, 화면), 암호화 금고·저장은 `kr.callbrief` |
| `android/core`, `call-core-android`, `audio-vault-android`, `recording-discovery-android` | 저장·금고·녹음 발견 모듈 |
| `android/app/src/main/cpp/` | Whisper 네이티브 코드(JNI). 소스 tar는 `third_party/` |
| `tools/` | 서명 키 유도, 네이티브 빌드, 호스트 시험 준비, 기기 시험 도구 (`tools/audio/`는 공개 합성 음성 2개) |
| `run_gradle.py` | Gradle 실행 도우미(로그를 `evidence/`에 남김) |
| `.github/workflows/` | ci(`android-ci.yml`), 자동 배포(`release-apk.yml`), 기기 시험 등 |
| `docs/` | 문서 (아래) |

## 개발

작업 브랜치는 `feature/lean-calls`입니다. 새 시험은 `feature/…` 브랜치에서 하고 CI 통과 후 합칩니다. push하면 ci가 단위 시험과 lint를 돌리고, 통과한 커밋만 `release-apk`가 서명 APK로 빌드해 우편함 릴리스에 올립니다. 폰 앱이 이를 받아 자동으로 업데이트됩니다.

로컬 빌드와 시험(JDK 17, Android SDK 36, Build Tools 35.0.0, Python 3.10 이상 필요):

```sh
export JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/android-sdk
export IEUM_SKIP_ANDROID_NATIVE=1   # Whisper 네이티브 빌드를 건너뜀(시험·lint 전용)
python3 tools/prepare_host_tests.py /path/to/android-all.jar   # Robolectric JAR, 처음 한 번
python3 run_gradle.py --log first.log :core:test :app:testDebugUnitTest :app:lintDebug
```

APK에는 네이티브 빌드(NDK 28.2.13676358, CMake 3.22 이상)가 필요하며 배포 워크플로가 대신 합니다. 비밀번호·토큰·키는 파일이나 커밋에 넣지 않습니다.

## 문서

- [docs/HANDOFF.md](docs/HANDOFF.md): 인수인계. 현재 상태, 다음 할 일, 워크플로, 함정. 새 세션은 이 파일부터 읽습니다.
- [docs/DESIGN_V4.md](docs/DESIGN_V4.md): 지금 설계 기준(v3.1~v3.4, 진행 상태와 근거). 그 전은 [docs/DESIGN_V3.md](docs/DESIGN_V3.md).
- [docs/OPERATIONS.md](docs/OPERATIONS.md): 예약 작업, 우편함, 워치독 운영. 지시문 원본은 `docs/routines/`.
- [docs/REAL_DEVICE_CHECKLIST.md](docs/REAL_DEVICE_CHECKLIST.md): 아내 폰(S26) 10분 확인표.
- [docs/PRODUCT.md](docs/PRODUCT.md): 앱 목적, 기능, UX 설계.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): 작업 방식과 변경 경계.
- `docs/reviews/`: 검토 원문(11/4 종합 검토, 11/5 화면·파이프라인·데이터, v3.3 자막 협의안, v3.4 설계 검토). 작업자 지시문과 보고서는 `docs/orchestration/`.
- [docs/ASSETS.md](docs/ASSETS.md), [docs/FIXTURES.md](docs/FIXTURES.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): 모델·시험 자료 출처와 라이선스.

개발용 저장소이며(11/5부터 Actions 때문에 공개, 비밀은 들어 있지 않음) 공개 라이선스는 부여하지 않았습니다.
