"""시험 묶음 만들기. 받은 자료(믿을 수 없는 자료)는 읽기만 하고, 만든 녹음·정답은 --lab 폴더(저장소 밖) 아래에만 쓴다.

python3 -I tools/eval/asrlab/prepare.py a --dl /home/claude/asrlab/dl --lab /home/claude/asrlab
  A 낭독: TeamUNIVA/ko_asr_benchmarks(손으로 고친 Zeroth·FLEURS 시험 세트, CC BY 4.0)에서 고정 시드로 뽑아
  문장 10개(≈100초)씩 0.8초 묵음을 두고 이어 '짧은 통화' 녹음을 만든다(앱의 조각 나누기 그대로 돌리기 위해).
python3 -I tools/eval/asrlab/prepare.py b ...   B 전화 음질: A의 앞 녹음들을 좁은 대역·넓은 대역 코덱 + AAC m4a로.
python3 -I tools/eval/asrlab/prepare.py e ...   E 긴 통화 모양: A·C 문장을 두 화자로 번갈아, 0.5~8초 묵음과 잡음.
(C는 prepare_c.py, D는 tts_d.py)

녹음 목록: <lab>/sets/<묶음>.json = [{rec, wav, utts:[{id, ref, ref_original, start_ms, end_ms, names}]}]."""
import argparse
import io
import json
import os
import random
import subprocess
import sys

import numpy as np
import soundfile as sf

RATE = 16000


def read_parquet(path):
    import pyarrow.parquet as pq
    return pq.read_table(path).to_pylist()


def wav_bytes_to_float(b):
    x, sr = sf.read(io.BytesIO(b), dtype='float32', always_2d=True)
    x = x.mean(axis=1)
    if sr != RATE:
        raise SystemExit('unexpected rate %d' % sr)
    return x


def write_wav(path, x):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sf.write(path, np.clip(x, -1, 1), RATE, subtype='PCM_16')


TARGET_DBFS = -26.0


def level(x):
    """말소리 구간 수준(dBFS): 30 ms 창 RMS가 95분위의 1/10 이상인 창들의 RMS."""
    w = x[:len(x) // 480 * 480].reshape(-1, 480)
    rms = np.sqrt((w.astype(np.float64) ** 2).mean(axis=1))
    act = rms[rms >= 0.1 * np.percentile(rms, 95)]
    return 20 * np.log10(np.sqrt((act ** 2).mean()) + 1e-12)


def to_level(x, dbfs=TARGET_DBFS):
    """원본 수준이 -54~-10 dBFS로 제각각이라(FLEURS) 통화 녹음의 흔한 수준(ITU-T P.56 공칭 -26 dBov)에 맞춘다.
    맞추지 않으면 앱의 묵음 지우기 하한(SPEECH_FLOOR 0.006 ≈ -44 dBFS)이 조용한 문장을 통째로 지운다(보고서 참고)."""
    g = 10 ** ((dbfs - level(x)) / 20)
    y = x * g
    peak = np.max(np.abs(y))
    return (y / peak * 0.99 if peak > 0.99 else y).astype(np.float32)


def build_recordings(items, per_rec, gap_s, prefix, out_dir, lead_s=0.5, normalize_level=True):
    """items: [(id, ref, ref_original, float 표본, names)] → 녹음 목록."""
    recs = []
    for r in range(0, len(items), per_rec):
        group = items[r:r + per_rec]
        parts = [np.zeros(int(lead_s * RATE), dtype=np.float32)]
        pos = len(parts[0])
        utts = []
        for (uid, ref, orig, x, names) in group:
            if normalize_level:
                x = to_level(x)
            utts.append(dict(id=uid, ref=ref, ref_original=orig, start_ms=pos * 1000 // RATE, end_ms=(pos + len(x)) * 1000 // RATE, names=names))
            parts.append(x)
            pos += len(x)
            g = np.zeros(int(gap_s * RATE), dtype=np.float32)
            parts.append(g)
            pos += len(g)
        rec = '%s%02d' % (prefix, r // per_rec)
        wav = os.path.join(out_dir, rec + '.wav')
        write_wav(wav, np.concatenate(parts))
        recs.append(dict(rec=rec, wav=wav, utts=utts, ms=pos * 1000 // RATE))
    return recs


def sample_a(dl, n_each, seed=19):
    base = os.path.join(dl, 'TeamUNIVA__ko_asr_benchmarks', 'data')
    z = read_parquet(os.path.join(base, 'zeroth', 'test-00000-of-00001.parquet'))
    f = read_parquet(os.path.join(base, 'fleurs_ko', 'test-00000-of-00001.parquet'))
    rnd = random.Random(seed)
    zs = rnd.sample(range(len(z)), n_each)
    fs = rnd.sample(range(len(f)), n_each)
    # 녹음마다 Zeroth 5 + FLEURS 5 (앞 녹음 몇 개만 돌려도 두 출처가 섞이게)
    out = []
    for k in range(0, n_each, 5):
        for i in zs[k:k + 5]:
            r = z[i]
            out.append((r['id'], r['text'], r['text_original'], wav_bytes_to_float(r['audio']['bytes']), []))
        for i in fs[k:k + 5]:
            r = f[i]
            out.append((r['id'], r['text'], r['text_original'], wav_bytes_to_float(r['audio']['bytes']), []))
    return out


def ffmpeg(args):
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y'] + args, check=True)


def decode16k(src, dst):
    """앱처럼 m4a를 16 kHz 모노로 푼다(앱은 MediaCodec, 여기는 ffmpeg)."""
    ffmpeg(['-i', src, '-ac', '1', '-ar', str(RATE), '-c:a', 'pcm_s16le', dst])


def pink(n, rnd):
    """Voss-McCartney 근사 분홍 잡음."""
    rows = 16
    out = np.zeros(n)
    for k in range(rows):
        step = 1 << k
        v = rnd.standard_normal(n // step + 2)
        out += np.repeat(v, step)[:n]
    out /= np.std(out) + 1e-9
    return out.astype(np.float32)


def add_noise(x, noise, snr_db):
    sp = x[np.abs(x) > 1e-4]
    ps = np.mean(sp ** 2) if len(sp) else 1e-6
    pn = np.mean(noise ** 2) + 1e-12
    return x + noise * np.sqrt(ps / (pn * 10 ** (snr_db / 10)))


def babble(dl, n, seed):
    """잡담 잡음: A에 쓰지 않은 Zeroth 문장 6개를 겹친 것."""
    base = os.path.join(dl, 'TeamUNIVA__ko_asr_benchmarks', 'data')
    z = read_parquet(os.path.join(base, 'zeroth', 'test-00000-of-00001.parquet'))
    rnd = random.Random(seed)
    used = set()
    out = np.zeros(n, dtype=np.float32)
    for _ in range(6):
        track = []
        size = 0
        while size < n:
            i = rnd.randrange(len(z))
            used.add(z[i]['id'])
            x = wav_bytes_to_float(z[i]['audio']['bytes'])
            track.append(x)
            size += len(x)
        t = np.concatenate(track)[:n]
        out += t / (np.std(t) + 1e-9)
    return out / 6, used


CONDITIONS = {
    # 좁은 대역: 300~3400 Hz, 8 kHz G.711 μ-law(AMR-NB 인코더가 이 ffmpeg에 없어 대신), 그다음 AAC m4a(통화 녹음 파일)
    'nb_ulaw': dict(noise=None),
    # 넓은 대역: 16 kHz Opus 12 kbps(AMR-WB 12.65 kbps와 비슷한 비트율의 대신), 그다음 AAC m4a
    'wb_opus12': dict(noise=None),
    # 넓은 대역 + 잡담 잡음 SNR 10 dB
    'wb_babble10': dict(noise=('babble', 10)),
    # 좁은 대역 + 분홍 잡음 SNR 20 dB
    'nb_pink20': dict(noise=('pink', 20)),
}


def phone_convert(src_wav, cond, dst_dir, dl, seed):
    x, _ = sf.read(src_wav, dtype='float32')
    name = os.path.splitext(os.path.basename(src_wav))[0]
    os.makedirs(dst_dir, exist_ok=True)
    spec = CONDITIONS[cond]
    if spec['noise']:
        kind, snr = spec['noise']
        if kind == 'pink':
            noise = pink(len(x), np.random.default_rng(seed))
        else:
            noise, _ = babble(dl, len(x), seed)
        x = add_noise(x, noise, snr)
        x = x / max(1.0, np.max(np.abs(x)) / 0.99)
    pre = os.path.join(dst_dir, name + '.pre.wav')
    write_wav(pre, x)
    mid = os.path.join(dst_dir, name + '.mid.wav')
    if cond.startswith('nb'):
        ul = os.path.join(dst_dir, name + '.ulaw.wav')
        ffmpeg(['-i', pre, '-af', 'highpass=f=300,lowpass=f=3400', '-ar', '8000', '-c:a', 'pcm_mulaw', ul])
        ffmpeg(['-i', ul, '-ar', str(RATE), '-c:a', 'pcm_s16le', mid])
        os.remove(ul)
    else:
        op = os.path.join(dst_dir, name + '.opus')
        ffmpeg(['-i', pre, '-c:a', 'libopus', '-b:a', '12k', '-application', 'voip', '-ar', '16000', op])
        ffmpeg(['-i', op, '-ar', str(RATE), '-c:a', 'pcm_s16le', mid])
        os.remove(op)
    m4a = os.path.join(dst_dir, name + '.m4a')
    # 삼성 통화 녹음처럼 AAC-LC m4a로 한 번 더(모노, 48 kbps)
    ffmpeg(['-i', mid, '-c:a', 'aac', '-b:a', '48k', '-ac', '1', m4a])
    out = os.path.join(dst_dir, name + '.wav')
    decode16k(m4a, out)
    os.remove(pre)
    os.remove(mid)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('what', choices=['a', 'a_raw', 'b', 'e'])
    ap.add_argument('--dl', required=True)
    ap.add_argument('--lab', required=True)
    ap.add_argument('--n', type=int, default=30, help='A: 출처마다 문장 수')
    ap.add_argument('--b-recs', type=int, default=3, help='B: A의 앞 녹음 몇 개를 바꿀지')
    a = ap.parse_args()
    sets = os.path.join(a.lab, 'sets')
    os.makedirs(sets, exist_ok=True)
    if a.what == 'a':
        items = sample_a(a.dl, a.n)
        recs = build_recordings(items, 10, 0.8, 'A', os.path.join(a.lab, 'audio', 'A'))
        json.dump(recs, open(os.path.join(sets, 'A.json'), 'w'), ensure_ascii=False, indent=1)
        print('A', len(recs), 'recordings', sum(len(r['utts']) for r in recs), 'utts', sum(r['ms'] for r in recs) / 60000, 'min')
    elif a.what == 'a_raw':
        # 발견 1 확인용: 수준을 맞추지 않은 원래 음량 그대로(같은 문장, 같은 순서)
        items = sample_a(a.dl, a.n)
        recs = build_recordings(items, 10, 0.8, 'R', os.path.join(a.lab, 'audio', 'A_raw'), normalize_level=False)
        json.dump(recs, open(os.path.join(sets, 'A_raw.json'), 'w'), ensure_ascii=False, indent=1)
        print('A_raw', len(recs))
    elif a.what == 'b':
        A = json.load(open(os.path.join(sets, 'A.json')))[:a.b_recs]
        for k, cond in enumerate(CONDITIONS):
            recs = []
            for r in A:
                wav = phone_convert(r['wav'], cond, os.path.join(a.lab, 'audio', 'B_' + cond), a.dl, seed=100 + k)
                recs.append(dict(r, rec='B_%s_%s' % (cond, r['rec']), wav=wav))
            json.dump(recs, open(os.path.join(sets, 'B_%s.json' % cond), 'w'), ensure_ascii=False, indent=1)
            print('B', cond, len(recs))
    elif a.what == 'e':
        import long_calls
        long_calls.build(a.dl, a.lab)


if __name__ == '__main__':
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
