# 합성 시험 자료 출처

이 저장소의 fixture는 실제 통화·문자·메일·연락처 내보내기가 아닙니다. 공개 합성 시험에 필요한 작은 파일만 포함했습니다. 기존 frozen fixture의 identity/digest를 바꿔 실제 모델 실행이나 실제 계정 연결의 증거로 승격하지 않습니다.

| 자료 | 출처·성격 | 무결성 |
| --- | --- | --- |
| `tools/audio/public-ko.wav` | code33 `evidence/generate_public_audio.py`가 macOS `say` Yuna로 만든 공개 합성 문장, mono PCM16/16kHz | 245870 bytes, SHA256 `69ac671cd328486d14dfdaa413ec0a511c21e5708953fbe3dc2a280a6a2cf20d` |
| `tools/audio/public-en.wav` | 같은 생성기의 Samantha 공개 합성 문장, mono PCM16/16kHz | 213210 bytes, SHA256 `7d45a0f1af47bc5a212dd2e53d22566c6037b190708bff51782e387caed20df7` |
| `android/app/src/androidTest/assets/golden/` (call1~3.m4a, golden.json) | v2.4 자막 골든 셋. `tools/audio/make_golden.py`가 piper `ko_KR-kss-medium`(KSS 데이터셋 CC BY-NC-SA 4.0으로 학습, 시험용)으로 만든 합성 건설 현장 통화 3건, 가상 업체·사람, 16kHz mono AAC. 정답·개체는 golden.json, 채점은 `tools/audio/golden_score.py` | call1 310956 bytes `d39f4ddd179d68b6d770d50ce63169ff6c321d99271e38070c342c87df163bcb`, call2 265736 bytes `64a1c3575aa9fce7c93abfa31a28a69831cddc94a82b7e3a614dba154aca39d4`, call3 239877 bytes `4c002563fe51d293ff30b1f246947d214ba6030c74c85de2cc2661c67d7bbf36` |
| Android `src/test`, `src/debug/assets`, `src/androidTest`, `sg-v1-golden-result.json` | code34 v2 백업의 고정 합성 요청/결과·회귀·device 시험 | 이관 전 355개 파일 SHA 전수 일치, 원 해시는 (지금 저장소에는 없는 이관 때 파일) docs/source-baseline-manifest.json |

한국어 합성 원문: “이것은 공개 합성 음성입니다. 다음 주 화요일에 견적서를 확인합니다. 담당자는 아직 정하지 않았습니다.” 영어 원문: “This is public synthetic audio. Please review the proposal tomorrow. The responsible person is not assigned.” Whisper 오인식을 수정 정답처럼 바꾸지 않습니다. TTS 재생성은 OS voice/version에 따라 바이트가 달라질 수 있습니다.

고정 시험 내 계정 모양 문자열·번호·이름·dummy bearer/password는 합성 역할/경계 canary입니다. 실제 계정 메일함·접근 토큰·통화 원문·주소록 DB는 포함하지 않습니다. 예시 worker 설정의 계정은 `developer@example.invalid`로 비식별화했습니다. Google Android OAuth client ID는 기존 공개 앱 식별자이며 비밀키가 아닙니다. 발급·접속 권한을 제공하지 않고 기존 인증 정책을 바꾸지 않습니다.
