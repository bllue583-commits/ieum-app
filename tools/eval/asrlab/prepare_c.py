"""묶음 C 즉흥 발화: espnet/yodas ko000 조각 0(CC BY 3.0 유튜브 영상, 올린 사람이 붙인 '수동' 자막 — 사람이 받아 적었다는 보장은 없다).
Pansori-TEDxKR(OpenSLR 58)은 CC BY-NC-ND 4.0(비영리)이라 회사 앱 평가에 쓰지 않았다.
고르기(고정 시드): 영상별로 앞에서부터 이어지는 자막 조각을 모아 말소리 90~130초, 자막이 한글 위주(한글 비율 ≥ 0.85, 괄호·음표 없음)인 영상.
조각 사이의 실제 틈(자막 시각)을 0~2초 묵음으로 되살려 녹음 하나로 잇는다.
python3 -I tools/eval/asrlab/prepare_c.py --dl /home/claude/asrlab/dl --lab /home/claude/asrlab --videos 5"""
import argparse
import json
import os
import random
import re
import subprocess
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prepare  # noqa: E402


def vad_filter(recs, min_ratio=0.5):
    """자막이 말소리가 아닌 영상(화면 글자 자막 등)을 뺀다: Silero VAD 말소리 시간 < 자막 시간 × min_ratio 이면 제외.
    ASR 결과가 아니라 VAD로만 정한다(시험 엔진에 유리하게 고르지 않으려고)."""
    import run_engines
    sv = run_engines.SenseVoice(1)
    keep = []
    for r in recs:
        x, _ = sf.read(r['wav'], dtype='float32')
        speech = sum(len(s) for _, s in sv.parts(x)) / 16.0
        cap = sum(u['end_ms'] - u['start_ms'] for u in r['utts'])
        r['vad_speech_ms'] = int(speech)
        if speech >= min_ratio * cap:
            keep.append(r)
        else:
            print('C 제외', r['rec'], 'VAD 말소리 %.0f초 / 자막 %.0f초' % (speech / 1000, cap / 1000))
    return keep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dl', required=True)
    ap.add_argument('--lab', required=True)
    ap.add_argument('--videos', type=int, default=5)
    a = ap.parse_args()
    base = os.path.join(a.dl, 'espnet__yodas', 'data', 'ko000')
    by_vid = {}
    for line in open(os.path.join(base, 'text', '00000000.txt'), encoding='utf-8'):
        if ' ' not in line:
            continue
        uid, text = line.rstrip('\n').split(' ', 1)
        m = re.match(r'(.+)-(\d{5})-(\d{8})-(\d{8})$', uid)
        if not m:
            continue
        by_vid.setdefault(m.group(1), []).append((int(m.group(2)), int(m.group(3)) * 10, int(m.group(4)) * 10, uid, text.strip()))
    cands = []
    for vid, segs in by_vid.items():
        segs.sort()
        take = []
        speech = 0
        for s in segs:
            if speech >= 110_000:
                break
            take.append(s)
            speech += s[2] - s[1]
        text = ''.join(t[4] for t in take)
        if not 90_000 <= speech <= 130_000 or not text:
            continue
        hangul = len(re.findall(r'[가-힣]', text)) / max(1, len(re.sub(r'\s', '', text)))
        if hangul < 0.85 or re.search(r'[\[\]()♪♬<>]', text):
            continue
        cands.append((vid, take))
    rnd = random.Random(1913)
    cands.sort()
    pick = rnd.sample(cands, min(a.videos, len(cands)))
    xdir = os.path.join(a.dl, 'yodas_x')
    os.makedirs(xdir, exist_ok=True)
    names = ['./00000000/%s.wav' % t[3] for _, take in pick for t in take]
    lst = os.path.join(a.lab, 'sets', 'C_members.txt')
    open(lst, 'w').write('\n'.join(names) + '\n')
    subprocess.run(['tar', '-xzf', os.path.join(base, 'audio', '00000000.tar.gz'), '-C', xdir, '-T', lst], check=True)
    recs = []
    out_dir = os.path.join(a.lab, 'audio', 'C')
    os.makedirs(out_dir, exist_ok=True)
    for k, (vid, take) in enumerate(pick):
        parts = [np.zeros(8000, np.float32)]
        pos = 8000
        utts = []
        prev_end = None
        for (_, t0, t1, uid, text) in take:
            if prev_end is not None:
                gap = min(2000, max(0, t0 - prev_end))
                g = np.zeros(gap * 16, np.float32)
                parts.append(g)
                pos += len(g)
            x, sr = sf.read(os.path.join(xdir, '00000000', uid + '.wav'), dtype='float32', always_2d=True)
            x = x.mean(axis=1)
            assert sr == 16000
            utts.append(dict(id=uid, ref=text, ref_original=text, start_ms=pos // 16, end_ms=(pos + len(x)) // 16, names=[]))
            parts.append(x)
            pos += len(x)
            prev_end = t1
        x = np.concatenate(parts)
        # 영상 전체 수준을 -26 dBFS로(문장마다 따로 맞추지 않는다: 영상 안의 크고 작은 소리는 그대로)
        x = prepare.to_level(x)
        wav = os.path.join(out_dir, 'C%02d.wav' % k)
        prepare.write_wav(wav, x)
        recs.append(dict(rec='C%02d' % k, wav=wav, utts=utts, ms=pos // 16, source='https://www.youtube.com/watch?v=' + vid))
    recs = vad_filter(recs)
    json.dump(recs, open(os.path.join(a.lab, 'sets', 'C.json'), 'w'), ensure_ascii=False, indent=1)
    print('C', len(recs), 'recordings', sum(len(r['utts']) for r in recs), 'utts', sum(r['ms'] for r in recs) / 60000, 'min', 'candidates', len(cands))


if __name__ == '__main__':
    main()
