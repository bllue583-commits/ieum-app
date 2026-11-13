"""앱의 녹음 → 조각 나누기를 파이썬으로 옮긴 것 (android/app/src/main/java/kr/callbrief/pipeline/PipelineAudioMath.kt,
PipelineAudioDecoder.kt). 앱이 Whisper·SenseVoice에 넘기는 것과 같은 조각을 만든다.

1. 녹음을 16 kHz 모노로 푼다(앱: MediaCodec + 자체 리샘플러, 여기: ffmpeg — 리샘플 필터가 달라 미세한 차이는 있다).
2. 2분(MAX_CHUNK_MS) 원조각으로 자른다: 경계 ±2초 안에서 100 ms 창 에너지가 가장 낮은 곳(cutPoints).
3. 묵음 지우기 1차(PipelineSpeechPlan): 30 ms 창 RMS가 max(0.006, min(3×20분위, 0.3×90분위))보다 크면 말소리,
   1.5초 미만 틈은 유지, 앞뒤 0.3초 여유. 줄어드는 양이 10% 미만이면 적용하지 않는다(applies).
4. 2차(PipelineSpeechAssembler): 말소리 구간을 2분 이하 조각으로 이어 붙인다. 2분을 넘는 구간 하나는 따로 2분씩 자른다.
각 조각의 시작 시각은 원래 녹음의 첫 표본 위치. 숫자 상수는 앱 값 그대로(시험 test_asrlab.py가 고정)."""
import numpy as np

RATE = 16000
MAX_CHUNK_MS = 120_000
CUT_SEARCH_MS = 2_000
CUT_WINDOW_MS = 100
CUT_STEP_MS = 10
MIN_TAIL_MS = 100
SPEECH_FLOOR = 0.006
LOUD_SHARE = 0.3
KEEP_GAP_MS = 1500
PAD_MS = 300


def min_tail(rate=RATE):
    return max(1, rate * MIN_TAIL_MS // 1000)


def cut_points(x, rate=RATE, max_chunk_ms=MAX_CHUNK_MS, search_ms=CUT_SEARCH_MS, window_ms=CUT_WINDOW_MS, step_ms=CUT_STEP_MS):
    n = len(x)
    max_len = rate * max_chunk_ms // 1000
    search = rate * search_ms // 1000
    half = max(1, rate * window_ms // 2000)
    step = max(1, rate * step_ms // 1000)
    cuts = [0]
    start = 0
    sq = np.concatenate([[0.0], np.cumsum(x.astype(np.float64) ** 2)])
    while n - start > max_len:
        nominal = start + max_len - search
        lo = max(start + 1, nominal - search)
        hi = start + max_len
        c = np.arange(lo, hi + 1, step)
        a = np.maximum(0, c - half)
        b = np.minimum(n, c + half)
        e = (sq[b] - sq[a]) / np.maximum(1, b - a)
        best_e = e.min()
        cand = c[np.abs(e - best_e) <= 1e-12]
        best = int(cand[np.argmin(np.abs(cand - nominal))])
        cuts.append(best)
        start = best
    if len(cuts) > 1 and n - cuts[-1] < min_tail(rate):
        cuts.pop()
    return cuts


def rms_windows(x, win):
    n = len(x)
    cnt = (n + win - 1) // win
    pad = np.zeros(cnt * win, dtype=np.float64)
    pad[:n] = x
    s = (pad.reshape(cnt, win) ** 2).sum(axis=1)
    lens = np.full(cnt, win, dtype=np.float64)
    lens[-1] = n - (cnt - 1) * win
    return np.sqrt(s / lens)


def speech_plan(chunks, rate=RATE, keep_gap_ms=KEEP_GAP_MS, pad_ms=PAD_MS, floor=SPEECH_FLOOR, loud_share=LOUD_SHARE):
    """chunks: [(offset_ms, samples)]. 돌려줌: (pieces, total) — piece = [origin(원래 표본 위치), [(chunk, from, to)], length]."""
    pieces = []
    total = 0
    win = rate * 30 // 1000
    for index, (offset_ms, data) in enumerate(chunks):
        size = len(data)
        total += size
        if size == 0:
            continue
        rms = rms_windows(data, win)
        srt = np.sort(rms)
        low = srt[len(rms) // 5]
        loud = srt[len(rms) * 9 // 10]
        threshold = max(floor, min(low * 3, loud * loud_share))
        gap = keep_gap_ms // 30
        pad = pad_ms // 30
        start = offset_ms * rate // 1000
        i = 0
        speech = rms >= threshold
        while i < len(rms):
            if not speech[i]:
                i += 1
                continue
            end = i
            quiet = 0
            j = i
            while j < len(rms) and quiet <= gap:
                if speech[j]:
                    end = j
                    quiet = 0
                else:
                    quiet += 1
                j += 1
            a = max(0, (i - pad) * win)
            b = min(size, (end + 1 + pad) * win)
            last = pieces[-1] if pieces else None
            if last is not None and last[0] + last[2] >= start + a:
                frm = max(a, last[0] + last[2] - start)
                if frm < b:
                    last[1].append((index, frm, b))
                    last[2] += b - frm
            else:
                pieces.append([start + a, [(index, a, b)], b - a])
            i = end + 1 + pad
    return pieces, total


def applies(pieces, total, min_saved_ratio=0.1):
    k = sum(p[2] for p in pieces)
    return not (total == 0 or k == 0 or k > total * (1 - min_saved_ratio))


def split(x, rate=RATE, max_chunk_ms=MAX_CHUNK_MS):
    if len(x) == 0:
        return []
    cuts = cut_points(x, rate, max_chunk_ms)
    out = []
    for i, s in enumerate(cuts):
        e = cuts[i + 1] if i + 1 < len(cuts) else len(x)
        out.append((s * 1000 // rate, x[s:e]))
    return out


def pieces_of(x, rate=RATE):
    """앱이 엔진에 넘기는 조각: [(원래 시작 ms, float32 표본, vad 적용 여부, 시각 지도)].
    시각 지도 = [(조각 안 시작 표본, 원래 녹음 표본 위치, 길이)] — 묵음을 지운 조각 안의 시각을 원래 시각으로 되돌릴 때 쓴다
    (앱은 이것을 저장하지 않는다. 실험실의 묵음 환각 판정에만 쓴다)."""
    cuts = cut_points(x, rate) if len(x) else []
    chunks = split(x, rate)
    plan, total = speech_plan(chunks, rate)
    if not applies(plan, total):
        return [(ms, c.astype(np.float32), False, [(0, cuts[i], len(c))]) for i, (ms, c) in enumerate(chunks)]
    max_len = rate * MAX_CHUNK_MS // 1000
    groups = []
    buf, size = [], 0
    for p in plan:
        if p[2] > max_len:
            if size:
                groups.append((buf, False))
            buf, size = [], 0
            groups.append(([p], True))
            continue
        if size + p[2] > max_len:
            groups.append((buf, False))
            buf, size = [], 0
        buf.append(p)
        size += p[2]
    if size:
        groups.append((buf, False))
    out = []
    for g, long in groups:
        segs = [(c, a, b) for p in g for (c, a, b) in p[1]]
        data = np.concatenate([chunks[c][1][a:b] for (c, a, b) in segs]).astype(np.float32)
        tmap = []
        pos = 0
        for (c, a, b) in segs:
            tmap.append((pos, cuts[c] + a, b - a))
            pos += b - a
        origin_ms = g[0][0] * 1000 // rate
        if long:
            sub = cut_points(data, rate)
            for i, s0 in enumerate(sub):
                e0 = sub[i + 1] if i + 1 < len(sub) else len(data)
                m = [(lp - s0, op, ln) for (lp, op, ln) in clip_map(tmap, s0, e0)]
                out.append((origin_ms + s0 * 1000 // rate, data[s0:e0], True, m))
        else:
            out.append((origin_ms, data, True, tmap))
    return out


def clip_map(tmap, s0, e0):
    res = []
    for lp, op, ln in tmap:
        a, b = max(lp, s0), min(lp + ln, e0)
        if a < b:
            res.append((a, op + (a - lp), b - a))
    return res


def to_original_ms(tmap, local_ms, rate=RATE):
    """조각 안 시각(ms) → 원래 녹음 시각(ms)."""
    s = local_ms * rate // 1000
    for lp, op, ln in tmap:
        if s < lp + ln:
            return (op + max(0, s - lp)) * 1000 // rate
    if not tmap:
        return local_ms
    lp, op, ln = tmap[-1]
    return (op + ln) * 1000 // rate
