# Third-party notices

제품 소스의 공개 라이선스를 새로 부여하지 않습니다. 비공개 개발 저장소입니다.

- whisper.cpp v1.7.6: MIT. `third_party/WHISPER_CPP_LICENSE`, 고정 upstream source archive와 `android/app/src/main/assets/local-stt/THIRD_PARTY_NOTICES.txt`의 코드·모델 고지를 유지합니다.
- Whisper 모델: MIT 고지를 위 APK 자산에 포함합니다. 모델 자체는 Git 외부입니다.
- Gradle wrapper: Gradle 8.13, Apache-2.0. wrapper 스크립트의 저작권·라이선스 헤더를 유지하며 wrapper JAR를 포함합니다. https://github.com/gradle/gradle/tree/v8.13.0
- Android/AndroidX/Kotlin/Google auth/OkHttp/Robolectric/JUnit 및 Kakao SDK는 각 Gradle 선언과 upstream 조건을 따릅니다. 다운로드된 SDK/JAR/캐시는 이 저장소에 재배포하지 않습니다.
- Pretendard Std v1.3.9 (Regular, Medium, SemiBold, Bold): SIL Open Font License 1.1, Copyright (c) 2021 Kil Hyung-jin, Reserved Font Name Pretendard. `android/app/src/main/res/font/pretendard_*.otf`, 전문은 `third_party/PRETENDARD_LICENSE`. https://github.com/orioncactus/pretendard
- 공개 합성 WAV/JSON fixture의 구성·사용 경계는 `docs/FIXTURES.md`를 따릅니다.
- sherpa-onnx v2.0.1 (k2-fsa, Xiaomi Corporation 외): Apache-2.0. 엔진 측정대(DESIGN_V7 4.4)에만 쓴다. 공식 AAR의 `classes.jar`를 `third_party/sherpa-onnx-2.0.1/`에, arm64 `.so`는 빌드 때 같은 AAR에서 꺼낸다. 전문은 `third_party/sherpa-onnx-2.0.1/LICENSE`. https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1
- ONNX Runtime (sherpa-onnx AAR에 들어 있는 `libonnxruntime.so`): MIT, Copyright (c) Microsoft Corporation. 전문은 `third_party/ONNXRUNTIME_LICENSE`. https://github.com/microsoft/onnxruntime
- SenseVoice-Small (FunAudioLLM/SenseVoice, Alibaba Group; sherpa-onnx 변환 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`의 int8 가중치): FunASR Model Open Source License 1.1 — 사용·복사·수정·공유 허용, **출처·저작자 표기와 모델 이름 유지 의무**. 가중치는 저장소·APK에 없고 측정대가 켜졌을 때만 폰이 받는다. 전문은 `third_party/FUNASR_MODEL_LICENSE`. https://github.com/FunAudioLLM/SenseVoice , https://github.com/modelscope/FunASR
- Silero VAD (`silero_vad.onnx`, sherpa-onnx 배포본): MIT, Copyright (c) 2020-present Silero Team. 측정대가 켜졌을 때만 폰이 받는다. 전문은 `third_party/SILERO_VAD_LICENSE`. https://github.com/snakers4/silero-vad
- 위 네 고지는 APK 자산 `android/app/src/main/assets/local-stt/THIRD_PARTY_NOTICES.txt`에도 있다. 설정 화면에는 "정보" 절이 없고 기능 동결이라 화면 고지는 더하지 않았다(DESIGN_V7 11절).

