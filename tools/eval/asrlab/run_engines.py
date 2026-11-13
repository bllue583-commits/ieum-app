"""엔진 실행. 녹음 → 앱과 같은 조각(apppipe.pieces_of) → 엔진 → 원래 시각의 조각 자막.
결과는 <lab>/runs/<묶음>/<엔진>/<녹음>.json (이미 있으면 건너뛰어 이어 하기). 긴 계산은 nice -n 10 으로.

엔진 이름
  q5   whisper.cpp 1.7.6 + large-v3-turbo q5_0, 앱 JNI와 같은 설정(whisper_app_run.cpp) — (가)
  q8   같은 것 + q8_0 — (나)
  sv   SenseVoice-Small int8 2024-07-17 + Silero VAD, sherpa-onnx 2.0.1, 앱 BenchEngines.kt와 같은 설정 — (다)
  lv3  faster-whisper large-v3(CTranslate2 int8, 빔 5, 조건부 문맥 끔) — 기준 자막 후보 1
  zip  sherpa-onnx zipformer-korean 2024-06-24(계열이 다른 기준 자막 후보 2, Silero VAD로 나눔)

python3 -I tools/eval/asrlab/run_engines.py --lab /home/claude/asrlab --set A --engine q5 [--recs A00,A01] [--threads 1]"""
import argparse
import json
import os
import subprocess
import sys
import time

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apppipe  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
LAB_DEFAULT = '/home/claude/asrlab'
MODELS = {
    'q5': '/home/claude/lab-b/models/ggml-large-v3-turbo-q5_0.bin',
    'q8': '/home/claude/lab-b/models/ggml-large-v3-turbo-q8_0.bin',
    'sv': '/home/claude/lab-a/models/sv2024/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17',
    'vad': '/home/claude/lab-a/models/silero_vad.onnx',
    'zip': '/home/claude/lab-a/models/x_sherpa-onnx-zipformer-korean-2024-06-24/sherpa-onnx-zipformer-korean-2024-06-24',
    'lv3': '/home/claude/asrlab/models/faster-whisper-large-v3',
}


def load16k(path):
    x, sr = sf.read(path, dtype='float32', always_2d=True)
    x = x.mean(axis=1)
    assert sr == 16000, sr
    return x


def piece_files(lab, set_name, rec):
    """조각을 float32 파일로 남겨 두고(엔진끼리 같은 조각) 목록을 돌려준다."""
    d = os.path.join(lab, 'pieces', set_name, rec['rec'])
    meta = os.path.join(d, 'pieces.json')
    if os.path.exists(meta):
        return json.load(open(meta))
    os.makedirs(d, exist_ok=True)
    x = load16k(rec['wav'])
    out = []
    for i, (ms, data, vad, tmap) in enumerate(apppipe.pieces_of(x)):
        f = os.path.join(d, 'p%03d.f32' % i)
        data.astype('<f4').tofile(f)
        out.append(dict(i=i, ms=int(ms), samples=int(len(data)), vad=bool(vad), file=f, map=[[int(a), int(b), int(c)] for a, b, c in tmap]))
    json.dump(out, open(meta, 'w'))
    return out


def seg_out(piece, t0, t1, text, extra=None):
    m = [tuple(v) for v in piece['map']]
    d = dict(piece=piece['i'], t0=apppipe.to_original_ms(m, int(t0)), t1=apppipe.to_original_ms(m, max(int(t0), int(t1) - 1)) + 1, text=text)
    if extra:
        d.update(extra)
    return d


def run_whisper_cpp(model, pieces, threads, workdir):
    lst = os.path.join(workdir, 'list.tsv')
    out = os.path.join(workdir, 'raw.jsonl')
    # 앱 네이티브는 1600 표본 미만 조각을 거부한다(ANDROID_STT_PCM_SIZE) — 같은 조건으로 뺀다
    with open(lst, 'w') as f:
        for p in pieces:
            if p['samples'] >= 1600:
                f.write('%d\t%s\n' % (p['i'], p['file']))
    subprocess.run([os.path.join(LAB_DEFAULT, 'bin', 'whisper_app_run'), model, str(threads), lst, out], check=True,
                   stderr=open(os.path.join(workdir, 'stderr.log'), 'a'))
    raw = {}
    for line in open(out):
        r = json.loads(line)
        raw[int(r['id'])] = r
    segs = []
    work = 0
    for p in pieces:
        r = raw.get(p['i'])
        if not r:
            continue
        work += r['workMs']
        for s in r['segments']:
            probs = [t[1] for t in s['tokens']]
            segs.append(seg_out(p, s['t0'], s['t1'], s['text'], dict(pmin=min(probs) if probs else None)))
    return segs, work


class SenseVoice:
    def __init__(self, threads):
        import sherpa_onnx
        d = MODELS['sv']
        self.rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(model=os.path.join(d, 'model.int8.onnx'), tokens=os.path.join(d, 'tokens.txt'),
                                                                   num_threads=threads, language='ko', use_itn=True, debug=False, provider='cpu')
        self.vad_cfg = sherpa_onnx.VadModelConfig()
        self.vad_cfg.silero_vad.model = MODELS['vad']
        self.vad_cfg.silero_vad.threshold = 0.5
        self.vad_cfg.silero_vad.min_silence_duration = 0.5
        self.vad_cfg.silero_vad.min_speech_duration = 0.25
        self.vad_cfg.silero_vad.window_size = 512
        self.vad_cfg.silero_vad.max_speech_duration = 20
        self.vad_cfg.sample_rate = 16000
        self.vad_cfg.num_threads = 1
        self.sherpa = sherpa_onnx

    def parts(self, pcm):
        vad = self.sherpa.VoiceActivityDetector(self.vad_cfg, buffer_size_in_seconds=150)
        out = []
        i = 0
        while i < len(pcm):
            vad.accept_waveform(pcm[i:i + 512])
            while not vad.empty():
                out.append((vad.front.start, np.array(vad.front.samples, dtype=np.float32)))
                vad.pop()
            i += 512
        vad.flush()
        while not vad.empty():
            out.append((vad.front.start, np.array(vad.front.samples, dtype=np.float32)))
            vad.pop()
        return out

    def piece(self, pcm, rec=None):
        rec = rec or self.rec
        segs = []
        for start, samples in self.parts(pcm):
            st = rec.create_stream()
            st.accept_waveform(16000, samples)
            rec.decode_stream(st)
            text = st.result.text.strip()
            s_ms = start * 1000 // 16000
            if text:
                segs.append((s_ms, s_ms + len(samples) * 1000 // 16000, text))
        return segs


def run_sherpa(kind, pieces, threads):
    sv = SenseVoice(threads)
    rec = None
    if kind == 'zip':
        d = MODELS['zip']
        rec = sv.sherpa.OfflineRecognizer.from_transducer(encoder=os.path.join(d, 'encoder-epoch-99-avg-1.int8.onnx'), decoder=os.path.join(d, 'decoder-epoch-99-avg-1.onnx'),
                                                          joiner=os.path.join(d, 'joiner-epoch-99-avg-1.int8.onnx'), tokens=os.path.join(d, 'tokens.txt'),
                                                          num_threads=threads, decoding_method='greedy_search', provider='cpu')
    segs = []
    t = time.time()
    for p in pieces:
        pcm = np.fromfile(p['file'], dtype='<f4')
        for a, b, text in sv.piece(pcm, rec):
            segs.append(seg_out(p, a, b, text))
    return segs, int((time.time() - t) * 1000)


def run_lv3(pieces, threads):
    from faster_whisper import WhisperModel
    m = WhisperModel(MODELS['lv3'], device='cpu', compute_type='int8', cpu_threads=threads)
    segs = []
    t = time.time()
    for p in pieces:
        pcm = np.fromfile(p['file'], dtype='<f4')
        if len(pcm) < 1600:
            continue
        it, _ = m.transcribe(pcm, language='ko', beam_size=5, condition_on_previous_text=False, vad_filter=False, without_timestamps=False)
        for s in it:
            segs.append(seg_out(p, int(s.start * 1000), int(s.end * 1000), s.text))
    return segs, int((time.time() - t) * 1000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lab', default=LAB_DEFAULT)
    ap.add_argument('--set', required=True)
    ap.add_argument('--engine', required=True, choices=['q5', 'q8', 'sv', 'lv3', 'zip'])
    ap.add_argument('--recs', default='')
    ap.add_argument('--threads', type=int, default=1)
    a = ap.parse_args()
    recs = json.load(open(os.path.join(a.lab, 'sets', a.set + '.json')))
    if a.recs:
        want = set(a.recs.split(','))
        recs = [r for r in recs if r['rec'] in want or r['rec'].split('_')[-1] in want]
    out_dir = os.path.join(a.lab, 'runs', a.set, a.engine)
    os.makedirs(out_dir, exist_ok=True)
    for rec in recs:
        dst = os.path.join(out_dir, rec['rec'] + '.json')
        if os.path.exists(dst):
            continue
        pieces = piece_files(a.lab, a.set, rec)
        t = time.time()
        if a.engine in ('q5', 'q8'):
            work = os.path.join(out_dir, rec['rec'] + '.work')
            os.makedirs(work, exist_ok=True)
            segs, work_ms = run_whisper_cpp(MODELS[a.engine], pieces, a.threads, work)
        elif a.engine in ('sv', 'zip'):
            segs, work_ms = run_sherpa(a.engine, pieces, a.threads)
        else:
            segs, work_ms = run_lv3(pieces, a.threads)
        audio_ms = rec['ms']
        json.dump(dict(rec=rec['rec'], engine=a.engine, audio_ms=audio_ms, wall_ms=int((time.time() - t) * 1000), work_ms=work_ms,
                       threads=a.threads, pieces=len(pieces), piece_ms=[p['samples'] // 16 for p in pieces], segments=segs),
                  open(dst + '.tmp', 'w'), ensure_ascii=False)
        os.replace(dst + '.tmp', dst)
        print(a.set, a.engine, rec['rec'], 'audio %.0fs work %.0fs' % (audio_ms / 1000, work_ms / 1000), flush=True)


if __name__ == '__main__':
    main()
