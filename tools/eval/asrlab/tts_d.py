"""묶음 D 녹음: 지어낸 대본(script_d.py)을 Supertonic 2(Supertone, 가중치 BigScience OpenRAIL-M, 추론 코드 MIT)의 한국어
목소리 4개로 읽힌다. 추론은 공식 py/helper.py(MIT)를 줄여 옮긴 것이다(받은 코드는 실행하지 않는다).
대본 10문장씩 한 '통화', 두 화자(남·여)가 번갈아. 그다음 prepare.phone_convert로 B와 같은 전화 음질(좁은 대역 μ-law + AAC).
python3 -I tools/eval/asrlab/tts_d.py --model /home/claude/asrlab/dl/supertonic-2 --lab /home/claude/asrlab"""
import argparse
import json
import os
import re
import sys
from unicodedata import normalize

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prepare  # noqa: E402

VOICE_PAIRS = [('M1', 'F2'), ('F1', 'M3'), ('M2', 'F3'), ('F4', 'M4')]


class TTS:
    def __init__(self, d, threads=1):
        import onnxruntime as ort
        o = ort.SessionOptions()
        o.intra_op_num_threads = threads
        o.inter_op_num_threads = 1
        p = ['CPUExecutionProvider']
        on = os.path.join(d, 'onnx')
        self.dp = ort.InferenceSession(os.path.join(on, 'duration_predictor.onnx'), o, providers=p)
        self.te = ort.InferenceSession(os.path.join(on, 'text_encoder.onnx'), o, providers=p)
        self.ve = ort.InferenceSession(os.path.join(on, 'vector_estimator.onnx'), o, providers=p)
        self.vo = ort.InferenceSession(os.path.join(on, 'vocoder.onnx'), o, providers=p)
        self.cfg = json.load(open(os.path.join(on, 'tts.json')))
        self.idx = json.load(open(os.path.join(on, 'unicode_indexer.json')))
        self.sr = self.cfg['ae']['sample_rate']
        self.base = self.cfg['ae']['base_chunk_size']
        self.ccf = self.cfg['ttl']['chunk_compress_factor']
        self.ldim = self.cfg['ttl']['latent_dim']
        self.dir = d

    def style(self, name):
        v = json.load(open(os.path.join(self.dir, 'voice_styles', name + '.json')))
        t = np.array(v['style_ttl']['data'], dtype=np.float32).reshape(1, *v['style_ttl']['dims'][1:])
        p = np.array(v['style_dp']['data'], dtype=np.float32).reshape(1, *v['style_dp']['dims'][1:])
        return t, p

    def ids(self, text):
        text = normalize('NFKD', text)
        text = re.sub(r'\s+', ' ', text).strip()
        if not re.search(r'[.!?]$', text):
            text += '.'
        text = '<ko>' + text + '</ko>'
        ids = np.array([[self.idx[ord(c)] for c in text]], dtype=np.int64)
        mask = np.ones((1, 1, ids.shape[1]), dtype=np.float32)
        return ids, mask

    def say(self, text, voice, rng, steps=8, speed=1.05):
        st_ttl, st_dp = self.style(voice)
        ids, mask = self.ids(text)
        dur, *_ = self.dp.run(None, {'text_ids': ids, 'style_dp': st_dp, 'text_mask': mask})
        dur = dur / speed
        emb, *_ = self.te.run(None, {'text_ids': ids, 'style_ttl': st_ttl, 'text_mask': mask})
        wav_len = int(dur[0] * self.sr)
        chunk = self.base * self.ccf
        lat_len = (wav_len + chunk - 1) // chunk
        xt = rng.standard_normal((1, self.ldim * self.ccf, lat_len)).astype(np.float32)
        lmask = np.ones((1, 1, lat_len), dtype=np.float32)
        tot = np.array([steps], dtype=np.float32)
        for s in range(steps):
            xt, *_ = self.ve.run(None, {'noisy_latent': xt, 'text_emb': emb, 'style_ttl': st_ttl, 'text_mask': mask, 'latent_mask': lmask,
                                        'current_step': np.array([s], dtype=np.float32), 'total_step': tot})
        wav, *_ = self.vo.run(None, {'latent': xt})
        return wav[0, :wav_len].astype(np.float32), self.sr


def resample(x, sr, to=16000):
    if sr == to:
        return x
    import subprocess
    p = subprocess.run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-f', 'f32le', '-ar', str(sr), '-ac', '1', '-i', 'pipe:0', '-f', 'f32le', '-ar', str(to), 'pipe:1'],
                       input=x.astype('<f4').tobytes(), capture_output=True, check=True)
    return np.frombuffer(p.stdout, dtype='<f4').copy()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', required=True)
    ap.add_argument('--lab', required=True)
    ap.add_argument('--dl', default='/home/claude/asrlab/dl')
    a = ap.parse_args()
    script = json.load(open(os.path.join(a.lab, 'sets', 'D_script.json')))
    tts = TTS(a.model)
    rng = np.random.default_rng(1913)
    cache = os.path.join(a.lab, 'audio', 'D_utts')
    os.makedirs(cache, exist_ok=True)
    items = []
    for i, s in enumerate(script):
        call = i // 10
        voice = VOICE_PAIRS[call % len(VOICE_PAIRS)][i % 2]
        f = os.path.join(cache, s['id'] + '.npy')
        if os.path.exists(f):
            x = np.load(f)
        else:
            w, sr = tts.say(s['spoken'], voice, rng)
            x = resample(w, sr)
            np.save(f, x)
        items.append((s['id'], s['ref'], s['ref'], x, s['names']))
        s['voice'] = voice
    recs = prepare.build_recordings(items, 10, 0.7, 'D', os.path.join(a.lab, 'audio', 'D'))
    json.dump(recs, open(os.path.join(a.lab, 'sets', 'D_clean.json'), 'w'), ensure_ascii=False, indent=1)
    out = []
    for r in recs:
        wav = prepare.phone_convert(r['wav'], 'nb_ulaw', os.path.join(a.lab, 'audio', 'D_nb'), a.dl, seed=7)
        out.append(dict(r, rec='D_nb_' + r['rec'], wav=wav))
    json.dump(out, open(os.path.join(a.lab, 'sets', 'D_nb.json'), 'w'), ensure_ascii=False, indent=1)
    print('D', len(recs), 'calls', sum(r['ms'] for r in recs) / 60000, 'min', 'voices', sorted({s['voice'] for s in script}))


if __name__ == '__main__':
    main()
