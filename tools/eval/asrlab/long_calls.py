"""묶음 E 긴 통화 모양: A에 안 쓴 Zeroth·FLEURS 문장을 두 '화자'(Zeroth 쪽, FLEURS 쪽)로 번갈아 잇고, 사이에 0.5~8초 묵음,
전체에 낮은 바닥 잡음(분홍, -62 dBFS), 긴 틈의 절반에는 0.3~1.2초 잡음 덩어리(-38 dBFS, 부스럭·키보드 흉내)를 넣는다.
그다음 B의 좁은 대역(μ-law + AAC)으로 바꾼다. 길이: 5분 2개, 15분 1개(--long 이면 30분 1개 더).
정답 말소리 구간(start_ms, end_ms)이 있어 묵음 환각(말소리 밖 자막)을 셀 수 있다."""
import json
import os
import random

import numpy as np

import prepare


def build(dl, lab, lengths=(300, 300, 900), seed=1913):
    base = os.path.join(dl, 'TeamUNIVA__ko_asr_benchmarks', 'data')
    z = prepare.read_parquet(os.path.join(base, 'zeroth', 'test-00000-of-00001.parquet'))
    f = prepare.read_parquet(os.path.join(base, 'fleurs_ko', 'test-00000-of-00001.parquet'))
    used = set()
    A = json.load(open(os.path.join(lab, 'sets', 'A.json')))
    for r in A:
        for u in r['utts']:
            used.add(u['id'])
    rnd = random.Random(seed)
    zs = [r for r in z if r['id'] not in used]
    fs = [r for r in f if r['id'] not in used]
    rnd.shuffle(zs)
    rnd.shuffle(fs)
    rng = np.random.default_rng(seed)
    out_dir = os.path.join(lab, 'audio', 'E_clean')
    recs = []
    zi = fi = 0
    for k, sec in enumerate(lengths):
        parts = [np.zeros(16000, np.float32)]
        pos = 16000
        utts = []
        turn = 0
        target = sec * 16000
        while pos < target:
            if turn % 2 == 0:
                r = zs[zi % len(zs)]
                zi += 1
            else:
                r = fs[fi % len(fs)]
                fi += 1
            x = prepare.to_level(prepare.wav_bytes_to_float(r['audio']['bytes']))
            utts.append(dict(id='%s@E%02d' % (r['id'], k), ref=r['text'], ref_original=r['text_original'], start_ms=pos // 16, end_ms=(pos + len(x)) // 16, names=[]))
            parts.append(x)
            pos += len(x)
            gap = rnd.choice([0.5, 0.8, 1.2, 2.0, 3.0, 5.0, 8.0])
            g = np.zeros(int(gap * 16000), np.float32)
            if gap >= 2.0 and rnd.random() < 0.5:
                n = int(rnd.uniform(0.3, 1.2) * 16000)
                at = rnd.randrange(int(0.3 * 16000), len(g) - n - int(0.3 * 16000))
                g[at:at + n] = prepare.pink(n, rng) * 10 ** (-38 / 20)
            parts.append(g)
            pos += len(g)
            turn += 1
        x = np.concatenate(parts)
        x = x + prepare.pink(len(x), rng) * 10 ** (-62 / 20)
        wav = os.path.join(out_dir, 'E%02d.wav' % k)
        prepare.write_wav(wav, x)
        recs.append(dict(rec='E%02d' % k, wav=wav, utts=utts, ms=pos // 16))
    json.dump(recs, open(os.path.join(lab, 'sets', 'E_clean.json'), 'w'), ensure_ascii=False, indent=1)
    out = []
    for r in recs:
        wav = prepare.phone_convert(r['wav'], 'nb_ulaw', os.path.join(lab, 'audio', 'E'), dl, seed=11)
        out.append(dict(r, rec='E_' + r['rec'], wav=wav))
    json.dump(out, open(os.path.join(lab, 'sets', 'E.json'), 'w'), ensure_ascii=False, indent=1)
    for r in out:
        sp = sum(u['end_ms'] - u['start_ms'] for u in r['utts'])
        print('E', r['rec'], '%.1f min' % (r['ms'] / 60000), 'speech %.0f%%' % (100 * sp / r['ms']), len(r['utts']), 'utts')
