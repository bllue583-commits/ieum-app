# 자막 실험실 11/13 (r19-lab) — 정답이 있는 공개 음성으로 엔진 (가)·(나)·(다)와 기준 자막 방법을 잰다

> 초안(파이프라인 확인 단계). 표는 `tools/eval/asrlab/summarize.py`가 `scores/*.json`에서 채운다 — 손으로 옮긴 숫자는 없다.

## 0. 결론

(규모를 늘린 뒤 채운다)

## 1. 방법

- **앱과 같은 조각**: 녹음 → 16 kHz 모노 → 2분 원조각(경계 ±2초 최저 에너지) → 묵음 지우기(30 ms 창, 하한 0.006, 1.5초 미만 틈 유지, 앞뒤 0.3초) → 2분 이하 조각(`apppipe.py`, 앱 `PipelineAudioMath.kt` 옮김, 상수는 시험으로 고정). 세 엔진이 같은 조각을 받는다(앱 측정대 `BenchEngines.kt`와 같음).
- **(가)/(나)**: whisper.cpp **1.7.6**(앱 판)에 링크한 `whisper_app_run.cpp` — 앱 JNI `transcribeChunk`의 기본값 그대로(그리디, temperature_inc·best_of 라이브러리 기본, suppress_nst 끔, token_timestamps, no_context, language ko, 초기 프롬프트 없음 — 앱 `PROMPT_ENABLED=false`, flash_attn 끔). 저장 직전 정리(`TranscriptClean`: 알려진 환각 문구, 3번 이상 반복)도 같게. 스레드는 1(폰은 4~6) — 그리디라 결과 차이는 부동소수 순서 정도.
- **(다)**: SenseVoice-Small int8 2024-07-17, sherpa-onnx **2.0.1**(pip, 앱과 같은 판), language ko, ITN 켬, Silero VAD(threshold 0.5, 최소 묵음 0.5초, 최소 말 0.25초, 창 512, 최대 20초) — `BenchEngines.kt`와 같음.
- **기준 자막 후보**: faster-whisper large-v3(CTranslate2 int8, 빔 5, 문맥 끔), 계열이 다른 것으로 SenseVoice(다)와 zipformer-korean.
- **채점**: `tools/eval/ko_cer.py`의 정규화(숫자 → 정수, 문장부호·띄어쓰기 무시). 녹음 전체 자막을 정규화해 글자 정렬로 정답 문장별로 나누고, 문장별 편집 수의 합/정답 글자 수의 합. 95% 구간은 문장 부트스트랩 2,000번, 엔진 간은 같은 문장의 짝 부트스트랩. 숫자·이름은 통화(녹음) 단위로 앱 `KoCer.numberHits/nameHits`와 같은 규칙(정규화한 숫자 연속의 중복 집합, 이름 포함 여부). 묵음 환각 = 원래 시각으로 되돌린 자막 조각의 80% 이상이 정답 말소리 밖. 반복 고리 = 정규화 글자열에서 2~20자 단위가 4번 이상 연속.
- **수준 맞추기**: 원본 음량이 -54~-10 dBFS로 제각각이라(FLEURS) 문장마다 말소리 수준을 -26 dBFS로 맞췄다(1차 실행에서 앱의 하한 0.006이 조용한 FLEURS 문장을 통째로 지웠다 — 6절 발견 1).

## 2. 자료 (출처·허가·분량)

| 묶음 | 출처 | 허가 | 분량 | 비고 |
| --- | --- | --- | --- | --- |
| A 낭독 | TeamUNIVA/ko_asr_benchmarks의 `zeroth`·`fleurs_ko` 시험 세트(Zeroth-Korean OpenSLR 40, Google FLEURS ko_kr를 손으로 다시 확인한 정답) | CC BY 4.0(원자료도 CC BY 4.0) | 출처마다 30문장(고정 시드 19), 10문장씩 녹음 6개, 12.1분 | 원래 공개 정답(`text_original`)으로도 잼(문헌 대조). Zeroth 원본은 10 GB라 받지 않음 |
| B 전화 음질 | A의 앞 녹음 3개(30문장)를 변환 | A와 같음 | 조건 4 × 6.1분 | AMR 인코더가 ffmpeg에 없어 좁은 대역은 μ-law, 넓은 대역은 Opus 12 kbps로 대신, 둘 다 AAC-LC 48 kbps m4a로 한 번 더 → 16 kHz로 풂 |
| C 즉흥 | espnet/yodas `ko000` 조각 0(유튜브 CC BY 영상, 올린 사람의 '수동' 자막) | CC BY 3.0 | 영상 5개 × 약 2분, 184 조각, 10.7분 | 자막이 사람 받아쓰기라는 보장이 없다(YODAS 설명) — 정답 자체의 오류가 섞인다. Pansori-TEDxKR(OpenSLR 58)은 CC BY-NC-ND 4.0(비영리)이라 회사 앱 평가에서 뺌 |
| D 숫자·이름 | 지어낸 업무 통화 대본 80문장(`script_d.py`, 금액·날짜·시각·수량·전화번호 형식·지어낸 사람·업체) → Supertonic 2 한국어 목소리 8개(남 4·여 4) | 대본은 이 저장소, TTS 가중치 OpenRAIL-M(상업 사용 허용, 용도 제한), 추론 코드 MIT. `facebook/mms-tts-kor`는 CC BY-NC 4.0이라 뺌 | 통화 8개, 7.0분, 숫자 토큰 125, 이름 32 | 전화번호는 가운데 자리가 0으로 시작(배정되지 않는 형식). TTS라 실제보다 쉬울 수 있다 |
| E 긴 통화 | A에 안 쓴 Zeroth·FLEURS 문장, 두 화자 번갈아, 0.5~8초 묵음, 바닥 잡음 -62 dBFS, 긴 틈 절반에 잡음 덩어리 -38 dBFS, 좁은 대역 | A와 같음 | 5.1분·5.3분·15.1분 | 말소리 76~80% |

체크섬: `tools/eval/asrlab/sources.json`.

## 3. 결과

<!-- tables:start -->
<!-- tables:end -->

## 4. 판정

(채움)

## 5. 한계 — 실제 표본으로만 확인되는 것

(채움)

## 6. 발견

(채움)

## 7. 다시 돌리기

`tools/eval/asrlab/README.md`.
