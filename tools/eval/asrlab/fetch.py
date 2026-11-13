"""자료 받기: Hugging Face에서 정해진 파일만 받아 --dl 아래에 두고 sha256을 sources.json과 대조한다(--write 면 새로 적는다).
받은 것은 믿을 수 없는 자료다: 실행하지 않고 읽기만 한다(압축은 prepare_c.py가 새 빈 폴더에 푼다).
python3 -I tools/eval/asrlab/fetch.py --dl /home/claude/asrlab/dl [--write]"""
import argparse
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = os.path.join(HERE, 'sources.json')

FILES = [
    # (저장소, 종류, 파일, 묶음, 허가)
    ('TeamUNIVA/ko_asr_benchmarks', 'dataset', 'data/zeroth/test-00000-of-00001.parquet', 'A,B,E', 'CC BY 4.0 (원자료 Zeroth-Korean OpenSLR 40, CC BY 4.0)'),
    ('TeamUNIVA/ko_asr_benchmarks', 'dataset', 'data/fleurs_ko/test-00000-of-00001.parquet', 'A,B,E', 'CC BY 4.0 (원자료 Google FLEURS ko_kr, CC BY 4.0)'),
    ('espnet/yodas', 'dataset', 'data/ko000/text/00000000.txt', 'C', 'CC BY 3.0 (CC BY 유튜브 영상)'),
    ('espnet/yodas', 'dataset', 'data/ko000/audio/00000000.tar.gz', 'C', 'CC BY 3.0'),
    ('Supertone/supertonic-2', 'model', 'onnx/text_encoder.onnx', 'D', 'BigScience OpenRAIL-M (가중치), 추론 코드 MIT'),
    ('Supertone/supertonic-2', 'model', 'onnx/duration_predictor.onnx', 'D', 'OpenRAIL-M'),
    ('Supertone/supertonic-2', 'model', 'onnx/vector_estimator.onnx', 'D', 'OpenRAIL-M'),
    ('Supertone/supertonic-2', 'model', 'onnx/vocoder.onnx', 'D', 'OpenRAIL-M'),
    ('Supertone/supertonic-2', 'model', 'onnx/tts.json', 'D', 'OpenRAIL-M'),
    ('Supertone/supertonic-2', 'model', 'onnx/unicode_indexer.json', 'D', 'OpenRAIL-M'),
] + [('Supertone/supertonic-2', 'model', 'voice_styles/%s.json' % v, 'D', 'OpenRAIL-M') for v in ('M1', 'M2', 'M3', 'M4', 'F1', 'F2', 'F3', 'F4')] + [
    ('Systran/faster-whisper-large-v3', 'model', 'model.bin', '기준 자막', 'MIT (OpenAI Whisper large-v3 변환)'),
    ('Systran/faster-whisper-large-v3', 'model', 'config.json', '기준 자막', 'MIT'),
    ('Systran/faster-whisper-large-v3', 'model', 'tokenizer.json', '기준 자막', 'MIT'),
    ('Systran/faster-whisper-large-v3', 'model', 'vocabulary.json', '기준 자막', 'MIT'),
    ('Systran/faster-whisper-large-v3', 'model', 'preprocessor_config.json', '기준 자막', 'MIT'),
]
LOCAL = {'Supertone/supertonic-2': 'supertonic-2', 'Systran/faster-whisper-large-v3': '../models/faster-whisper-large-v3'}


def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dl', required=True)
    ap.add_argument('--write', action='store_true')
    a = ap.parse_args()
    from huggingface_hub import hf_hub_download
    known = {}
    if os.path.exists(SOURCES):
        known = {(x['repo'], x['file']): x for x in json.load(open(SOURCES))}
    out = []
    bad = 0
    for repo, typ, f, sets, lic in FILES:
        local = os.path.join(a.dl, LOCAL.get(repo, repo.replace('/', '__')))
        p = os.path.join(local, f)
        if not os.path.exists(p):
            p = hf_hub_download(repo, f, repo_type=typ, local_dir=local)
        h = sha256(p)
        k = known.get((repo, f))
        if k and k['sha256'] != h:
            bad += 1
            print('체크섬 다름', repo, f)
        out.append(dict(repo=repo, type=typ, file=f, sets=sets, license=lic, bytes=os.path.getsize(p), sha256=h))
    if a.write:
        json.dump(out, open(SOURCES, 'w'), ensure_ascii=False, indent=1)
    print('files', len(out), 'mismatch', bad)
    raise SystemExit(1 if bad else 0)


if __name__ == '__main__':
    main()
