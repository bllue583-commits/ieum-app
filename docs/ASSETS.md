# Git 외부 자산과 재생성

공개 다운로드 URL만 기록합니다. 사설 다운로드 URL, 토큰, 키와 서명 파일은 저장하지 않습니다. 모든 다운로드 뒤 크기와 SHA256을 검증하고 불일치하면 사용하지 않습니다.

| 자산 | 크기(bytes) | SHA256 | 출처/용도 |
| --- | ---: | --- | --- |
| code34 v2 원본 ZIP | 47058330 | `4d610dd012a2760c0a1ea2cf132141f80d4bfd19276d0e5176aa857e6fd06cf0` | Library `libfile_73a12fce61548191acd85aa98e06ddf9`; 소스/APK/과거 증거 백업 |
| code34 v2 validation APK | 38417366 | `440629f74494459c33bc9b2b349845291add6e7ff9a9cda74cfae752ad746b1e` | Mac 원본의 deliverables 및 위 백업. Git 제외; 기존 서명 identity 유지 |
| ggml-tiny-q5_1.bin | 32152673 | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` | [Whisper 공식 모델 저장소](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin), Android 공개 시험 |
| whisper.cpp-v1.7.6.tar.gz | 7307730 | `166140e9a6d8a36f787a2bd77f8f44dd64874f12dd8359ff7c1f4f9acb86202e` | [upstream v1.7.6](https://github.com/ggml-org/whisper.cpp/tree/v1.7.6), third_party에 고정 포함 |
| Robolectric android-all-instrumented-15-robolectric-13954326-i7.jar | 200643142 | `06c4c8602d6db7486266a982bc55834ed6dfebd62a0b88daca204139ecf142cb` | [Maven Central](https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7/android-all-instrumented-15-robolectric-13954326-i7.jar), 기존 offline host 시험 |

모델을 개인 로컬 자산 디렉터리에 준비하고 `IEUM_ANDROID_WHISPER_MODEL`로 지정합니다. `tools/build_android_whisper.py`는 NDK r28c와 위 tar/model을 검증하고 arm64-v8a/x86_64의 JNI·runtime-pin·공개 WAV를 `android/app/build/` 아래 생성합니다. 생성된 JNI는 빌드 경로 등 환경에 따라 기존 바이너리 SHA와 달라질 수 있으며 APK byte 재현을 약속하지 않습니다. 생성 runtime-pin과 자체 JNI/model SHA의 일치가 필요합니다.

SDK/NDK는 OS별 공식 Android 설치 패키지를 준비합니다. Mac r28c 원본 ZIP의 과거 SHA256은 `0d4599e8bbf1a1668a0d51a541729b2246360f350018a2081d0b302dbb594f2a`(952495160 bytes)이며 Windows 패키지 해시로 사용하면 안 됩니다. SDK/JDK/NDK/Gradle 캐시는 Git에 넣지 않습니다.

Robolectric JAR는 준비 후 `python3 tools/prepare_host_tests.py <local-jar>`로 SHA 검증·복사합니다. 현재 코드의 offline dependency 경로를 바꾸지 않고 생성 `build/synthetic-test-home/`에 둡니다. `clean`으로 지워지면 다시 준비합니다.

기존 서명키는 Mac의 기존 승인 위치에서만 참조하고 복사·업로드하지 않습니다. 기존 APK와 개발용 새 서명이 다르면 기존 설치에 업데이트할 수 없습니다. 이관에서 서명키 생성/교체·휴대폰 자료 삭제를 수행하지 않았습니다.

## 과거 합성 입력의 외부 준비

Android T81~T84는 `IEUM_HISTORICAL_SYNTHETIC_AUDIO`로 원래 승인된 합성 WAV를 지정합니다. 2050244 bytes/SHA256 `3ee1442ed8ac61b95ac1ff2985d2857126042b99141c05f7947c25ae4f7c56b5`를 코드가 검사합니다. 원본 Mac의 기존 code8 `drive-integration-ro1/core-handoff/code8-dot-work/source/dot-core/handoff/connector-pilot-v0/audio.wav`를 소유자가 승인된 저장 위치에서 확보할 수 있습니다. 이 Git에는 재배포하지 않았으며 출처 불명 음성을 대신하지 않습니다. 미준비 시 네 시험은 명시 SKIP입니다. 이는 실제 신규 STT 시험이 아닙니다.

P3302/P3303는 `IEUM_LOCAL_STT_PIN_FILE`로 코드33의 기존 준비된 whisper-cli와 모델 해시를 지정할 때만 실행됩니다. native Android 모델 핀과 다릅니다. 미준비 시 두 시험은 SKIP입니다.

## 실사용 모델

이 표의 `ggml-tiny-q5_1.bin`은 공개 시험용이다. 아내 폰이 쓰는 모델은 large-v3-turbo q5_0(약 574MB)이고 앱이 처음 실행 때 받아 SHA-256을 확인한다(자세한 것은 [DESIGN.md](DESIGN.md) 3.2절). 엔진·양자화 후보의 측정 계획은 [DESIGN_V7.md](DESIGN_V7.md) 4절.
