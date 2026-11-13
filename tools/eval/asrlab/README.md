# 자막 실험실 (r19, 11/13)

정답이 있는 공개 한국어 음성으로 앱과 같은 엔진·같은 설정의 자막 정확도를 잰다. 결과와 판정은 [`docs/eval/ASR_LAB_1113.md`](../../../docs/eval/ASR_LAB_1113.md).
받은 음성·모델·자막은 전부 저장소 밖(`/home/claude/asrlab/`)에 둔다. 저장소에는 스크립트와 숫자 요약만.

| 파일 | 하는 일 |
| --- | --- |
| `apppipe.py` | 앱의 조각 나누기(2분 원조각, 묵음 지우기 1·2차, 2분 이하로 잇기)를 옮긴 것 + 원래 시각 지도 |
| `whisper_app_run.cpp` | whisper.cpp 1.7.6에 링크해 앱 JNI와 같은 `whisper_full` 설정으로 조각을 돌리는 실행기 |
| `run_engines.py` | 엔진 실행: `q5`(가) `q8`(나) `sv`(다, SenseVoice+Silero VAD) `lv3`(faster-whisper large-v3) `zip`(zipformer-korean) |
| `prepare.py` | 묶음 A(낭독) 만들기, B(전화 음질 4조건), E(긴 통화, `long_calls.py`) |
| `prepare_c.py` | 묶음 C(YODAS ko000 즉흥 발화) |
| `script_d.py`, `tts_d.py` | 묶음 D: 지어낸 업무 통화 대본 → Supertonic 2 한국어 목소리 8개 → 좁은 대역 전화 음질 |
| `labscore.py` | 정규화(`../ko_cer.py`), 녹음 자막을 문장별로 나누는 글자 정렬, 숫자·이름 대조(앱 `KoCer.kt`와 같은 규칙), 앱 `TranscriptClean`, 묵음 환각, 부트스트랩 |
| `score.py` | 묶음×엔진 표, 짝 비교, 초벌·확정 불일치, 기준 자막 방법 비교 → `<lab>/scores/<묶음>.json` |
| `reference.py` | **실제 표본용**: 폴더의 WAV/m4a → 엔진별 자막 → 기준 자막 → 기준 대비 오류율·숫자·이름 불일치 목록 |
| `sources.json` | 받은 자료의 출처·허가·체크섬 |

시험: `python3 -m unittest discover -s tools -p 'test_*.py'` (`tools/test_asrlab.py`, numpy·rapidfuzz·jiwer가 없으면 건너뜀).

## 다시 돌리기

```sh
L=/home/claude/asrlab; T=tools/eval/asrlab
python3 -m venv $L/venv && $L/venv/bin/pip install jiwer==4.0.0 rapidfuzz soundfile numpy sherpa-onnx==2.0.1 faster-whisper pyarrow onnxruntime huggingface_hub
# whisper.cpp 1.7.6(앱과 같은 판) 빌드가 /home/claude/lab-b/build-whisper.cpp-1.7.6 에 있다고 할 때
S=/home/claude/lab-b/src17/whisper.cpp-1.7.6 B=/home/claude/lab-b/build-whisper.cpp-1.7.6
g++ -O2 -std=c++17 -I$S/include -I$S/ggml/include $T/whisper_app_run.cpp -L$B/src -L$B/ggml/src -lwhisper -lggml -lggml-base \
    -Wl,-rpath,$B/src:$B/ggml/src -o $L/bin/whisper_app_run
$L/venv/bin/python -I $T/fetch.py --dl $L/dl                       # 자료 받기 + 체크섬 대조(sources.json)
$L/venv/bin/python -I $T/prepare.py a --dl $L/dl --lab $L --n 30    # A: 출처마다 30문장
$L/venv/bin/python -I $T/prepare.py b --dl $L/dl --lab $L --b-recs 3
$L/venv/bin/python -I $T/prepare_c.py --dl $L/dl --lab $L --videos 5
$L/venv/bin/python -I $T/script_d.py $L/sets/D_script.json && $L/venv/bin/python -I $T/tts_d.py --model $L/dl/supertonic-2 --lab $L
$L/venv/bin/python -I $T/prepare.py e --dl $L/dl --lab $L
# 엔진(긴 계산은 nice, 이미 끝난 녹음은 건너뜀). CPU 2개면 Whisper는 1스레드 두 개를 나란히.
nice -n 10 $L/venv/bin/python -I $T/run_engines.py --set A --engine q5 --threads 1 --recs A00,A01,A02
$L/venv/bin/python -I $T/score.py --set A --engines q5,q8,sv,lv3,zip --original
```

실제 표본(정답 없음):

```sh
nice -n 10 $L/venv/bin/python -I $T/reference.py <WAV 폴더> --out <저장소 밖 폴더> --engines q5,sv --ref lv3 --check sv --names <이름 목록.txt>
```

표준 출력에는 숫자만 나온다. 자막 본문(`runs/real/<엔진>/<녹음>.txt`)과 확인할 곳(`check/<녹음>.tsv`)은 `--out` 아래에만 생긴다.
