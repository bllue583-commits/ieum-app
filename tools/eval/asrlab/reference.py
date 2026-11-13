"""실제 표본용: WAV(또는 m4a) 폴더 → 앱과 같은 조각 → 엔진별 자막 → 기준 자막 → 엔진별 정규화 오류율(기준 자막 대비),
숫자·이름 불일치 목록(사람이 볼 곳).

python3 -I tools/eval/asrlab/reference.py <WAV 폴더> --out <폴더> [--engines q5,sv] [--ref lv3] [--check sv] [--names 이름목록.txt]

- 자막 본문·불일치 목록은 --out 폴더(저장소 밖)에만 쓴다. 표준 출력에는 숫자만.
- 기준 자막 = --ref 엔진(기본 faster-whisper large-v3). 사람 확인 대상 = 기준 자막과 --check 엔진(기본 SenseVoice, 계열이 다른 것)이
  다른 곳 중 숫자가 걸린 곳 + 이름 목록의 이름이 한쪽에만 있는 곳 (실험실 11/13 권고, docs/eval/ASR_LAB_1113.md).
- 녹음은 16 kHz 모노로 풀어(ffmpeg) <out>/audio 에 둔다. 다시 돌리면 끝난 것은 건너뛴다.
<out>/report.json: 녹음별 {엔진: CER(기준 대비)}, 숫자 불일치 수, 확인할 글자 수. <out>/check/<녹음>.tsv: 불일치 목록."""
import argparse
import json
import os
import subprocess
import sys

import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labscore as L  # noqa: E402
import run_engines as R  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder')
    ap.add_argument('--out', required=True)
    ap.add_argument('--engines', default='q5,sv')
    ap.add_argument('--ref', default='lv3')
    ap.add_argument('--check', default='sv')
    ap.add_argument('--names', default='')
    ap.add_argument('--threads', type=int, default=1)
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
    if out.startswith(repo + os.sep):
        sys.exit('--out 은 저장소 밖이어야 한다')
    files = sorted(f for f in os.listdir(a.folder) if f.lower().endswith(('.wav', '.m4a', '.mp3', '.amr', '.3gp', '.aac')))
    os.makedirs(os.path.join(out, 'audio'), exist_ok=True)
    recs = []
    for f in files:
        name = os.path.splitext(f)[0]
        wav = os.path.join(out, 'audio', name + '.wav')
        if not os.path.exists(wav):
            subprocess.run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-i', os.path.join(a.folder, f), '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', wav], check=True)
        ms = int(sf.info(wav).frames * 1000 // 16000)
        recs.append(dict(rec=name, wav=wav, utts=[], ms=ms))
    os.makedirs(os.path.join(out, 'sets'), exist_ok=True)
    json.dump(recs, open(os.path.join(out, 'sets', 'real.json'), 'w'), ensure_ascii=False)
    engines = list(dict.fromkeys(a.engines.split(',') + [a.ref, a.check]))
    for e in engines:
        subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'run_engines.py'), '--lab', out, '--set', 'real',
                        '--engine', e, '--threads', str(a.threads)], check=True, stdout=subprocess.DEVNULL)
    names = [L.normalize(x) for x in open(a.names, encoding='utf-8').read().split('\n') if x.strip()] if a.names else []
    os.makedirs(os.path.join(out, 'check'), exist_ok=True)
    report = {}
    tot = dict(audio_ms=0, check_chars=0, number_disagree=0)
    for r in recs:
        texts = {}
        for e in engines:
            run = json.load(open(os.path.join(out, 'runs', 'real', e, r['rec'] + '.json')))
            segs = run['segments']
            if e in ('q5', 'q8', 'lv3', 'lv3fw'):
                segs = L.transcript_clean(segs)[0]
            texts[e] = L.normalize(' '.join(s['text'] for s in segs))
            open(os.path.join(out, 'runs', 'real', e, r['rec'] + '.txt'), 'w', encoding='utf-8').write('\n'.join(s['text'] for s in segs))
        ref = texts[a.ref]
        row = dict(audio_s=r['ms'] / 1000)
        # 말소리 수준과 앱 묵음 지우기 하한(0.006) 아래로 떨어지는 말소리 창 비율 — 공개 음성(FLEURS)에서는 이것이 크게 문제였다
        import numpy as np
        import prepare
        x, _ = sf.read(r['wav'], dtype='float32')
        w = x[:len(x) // 480 * 480].reshape(-1, 480)
        rms = np.sqrt((w.astype(np.float64) ** 2).mean(axis=1))
        act = rms >= 0.1 * np.percentile(rms, 95)
        row.update(level_dbfs=round(prepare.level(x), 1), active_under_floor=round(float(((rms < 0.006) & act).sum() / max(1, act.sum())), 3))
        for e in engines:
            if e != a.ref:
                row['cer_' + e] = L.edits(ref, texts[e]) / len(ref) if ref else None
        dis = L.disagreements(ref, texts[a.check])
        num = [d for d in dis if d[2]]
        nm = [n for n in names if (n in ref) != (n in texts[a.check])]
        h, t = L.number_hits(ref, texts[a.check])
        row.update(numbers_ref=t, numbers_missing_in_check=t - h, check_spans=len(num) + len(nm), check_chars=sum(len(x) for x, _, _ in num))
        if 'q5' in texts and 'sv' in texts:
            h2, t2 = L.number_hits(texts['q5'], texts['sv'])
            row.update(draft_final_numbers=t2, draft_final_number_mismatch=t2 - h2)
        with open(os.path.join(out, 'check', r['rec'] + '.tsv'), 'w', encoding='utf-8') as f:
            f.write('kind\t%s\t%s\n' % (a.ref, a.check))
            for x, y, _ in num:
                f.write('number\t%s\t%s\n' % (x, y))
            for n in nm:
                f.write('name\t%s\t%s\n' % (n if n in ref else '', n if n in texts[a.check] else ''))
        report[r['rec']] = row
        tot['audio_ms'] += r['ms']
        tot['check_chars'] += row['check_chars']
        tot['number_disagree'] += len(num)
    json.dump(dict(recordings=report, total=tot, ref=a.ref, check=a.check, engines=engines), open(os.path.join(out, 'report.json'), 'w'), ensure_ascii=False, indent=1)
    for k, row in report.items():
        print(k, json.dumps({x: (round(v, 4) if isinstance(v, float) else v) for x, v in row.items()}))
    m = tot['audio_ms'] / 60000
    print('total recordings=%d audio_min=%.1f number_spans_to_check=%d check_chars_per_min=%.1f' % (len(recs), m, tot['number_disagree'], tot['check_chars'] / m if m else 0))


if __name__ == '__main__':
    main()
