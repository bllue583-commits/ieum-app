"""r19 자막 실험실의 순수 로직: 정규화(ko_cer.py 그대로), 녹음 단위 자막을 정답 문장별로 나누는 글자 정렬, 숫자·이름 대조
(앱 KoCer.kt numberHits/nameHits와 같은 규칙), 묵음 환각·반복 고리, 부트스트랩 구간, 모델 간 불일치 목록.
시험: tools/eval/test_asrlab.py."""
import os
import random
import re
import sys
import unicodedata

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import ko_cer  # noqa: E402  (tools/eval/ko_cer.py — 오류율은 반드시 이 정규화)
from rapidfuzz.distance import Levenshtein  # noqa: E402

normalize = ko_cer.normalize


def edits(ref, hyp):
    """jiwer.process_characters의 S+D+I와 같은 값(글자 편집 거리)."""
    return Levenshtein.distance(ref, hyp)


def split_by_ref(refs, hyp):
    """refs: 정규화한 정답 문장들(녹음 안 순서), hyp: 정규화한 녹음 전체 자막.
    글자 정렬로 hyp의 각 글자를 정답 위치에 붙이고, 문장 경계로 잘라 문장별 hyp를 돌려준다.
    정답 사이에 끼어든 글자(삽입)는 앞 문장에 붙인다(첫 문장 앞이면 첫 문장)."""
    ref = ''.join(refs)
    bounds = []
    pos = 0
    for r in refs:
        bounds.append((pos, pos + len(r)))
        pos += len(r)
    # hyp 글자 j → 정답 위치(삽입이면 직전 정답 글자 위치)
    owner = [None] * len(hyp)
    ops = Levenshtein.opcodes(ref, hyp)
    for tag, i1, i2, j1, j2 in ops:
        if tag in ('equal', 'replace'):
            n1, n2 = i2 - i1, j2 - j1
            for k in range(n2):
                owner[j1 + k] = i1 + min(n1 - 1, k * n1 // max(1, n2)) if n1 else max(0, i1 - 1)
        elif tag == 'insert':
            for k in range(j1, j2):
                owner[k] = max(0, i1 - 1)
    out = [[] for _ in refs]
    u = 0
    for j, o in enumerate(owner):
        if o is None:
            o = 0
        while u + 1 < len(bounds) and o >= bounds[u][1]:
            u += 1
        while u > 0 and o < bounds[u][0]:
            u -= 1
        if not refs:
            break
        out[u].append(hyp[j])
    return [''.join(x) for x in out]


def number_hits(ref_norm, hyp_norm):
    """KoCer.numberHits: 정답의 숫자 토큰(숫자 연속) 중 자막에도 있는 것(중복 집합), 정답의 숫자 토큰 수."""
    r = {}
    for m in re.findall(r'\d+', ref_norm):
        r[m] = r.get(m, 0) + 1
    h = {}
    for m in re.findall(r'\d+', hyp_norm):
        h[m] = h.get(m, 0) + 1
    return sum(min(n, h.get(k, 0)) for k, n in r.items()), sum(r.values())


def number_list(norm):
    return re.findall(r'\d+', norm)


def name_hits(ref_norm, hyp_norm, names):
    """KoCer.nameHits: 정답에 나온 이름(정규화, 2글자 이상) 중 자막에도 있는 것."""
    said = [n for n in names if len(n) >= 2 and n in ref_norm]
    return sum(1 for n in said if n in hyp_norm), len(said)


def bootstrap_ci(stats, n=2000, seed=0):
    """stats: [(편집 수, 정답 글자 수)] 문장별. 문장을 복원 추출해 비율의 95% 구간."""
    if not stats:
        return None, None
    rnd = random.Random(seed)
    vals = []
    m = len(stats)
    for _ in range(n):
        e = t = 0
        for _ in range(m):
            a, b = stats[rnd.randrange(m)]
            e += a
            t += b
        vals.append(e / t if t else 0.0)
    vals.sort()
    return vals[int(0.025 * n)], vals[int(0.975 * n) - 1]


def paired(stats_a, stats_b, n=2000, seed=0):
    """짝 부트스트랩(ko_cer.paired_bootstrap과 같은 방식): 같은 문장 표본에서 B/A 오류율 비의 95% 구간과
    p(B가 A보다 낫지 않음)."""
    rnd = random.Random(seed)
    m = len(stats_a)
    ratios = []
    wins = 0
    for _ in range(n):
        ea = eb = 0
        for _ in range(m):
            i = rnd.randrange(m)
            ea += stats_a[i][0]
            eb += stats_b[i][0]
        wins += eb < ea
        ratios.append(eb / ea if ea else float('inf'))
    ratios.sort()
    return ratios[int(0.025 * n)], ratios[int(0.975 * n) - 1], 1 - wins / n


# 앱 TranscriptClean.kt(A9)와 같은 정리: 알려진 환각 문구 조각 제거, 같은 조각 3번 이상 연속이면 1번, 조각 안 같은 문장 3번 이상이면 1번.
PHRASES = ["시청해 주셔서 감사합니다", "다음 시간에 뵙겠습니다", "영상 시청해 주셔서 감사합니다", "끝까지 시청해 주셔서 감사합니다", "오늘도 시청해 주셔서 감사합니다",
           "구독과 좋아요", "구독과 좋아요 부탁드립니다", "좋아요와 구독 부탁드립니다", "구독 좋아요 알림 설정까지", "다음 영상에서 만나요",
           "다음 시간에 만나요", "자막 제공", "자막 제작", "한글 자막", "번역 자막",
           "이 영상은 유료 광고를 포함하고 있습니다", "MBC 뉴스", "KBS 뉴스", "SBS 뉴스", "YTN 뉴스"]


def _key(t):
    return ''.join(c.lower() for c in t if c.isalnum())


KNOWN = {_key(p) for p in PHRASES}


def _sentences(t):
    return [s.strip() for s in re.split(r'(?<=[.!?。！？])\s*', t) if s.strip()]


def transcript_clean(segments):
    """segments: [dict(text=...)] → (정리된 조각들, 지운 수, 그중 알려진 문구 수, 반복으로 지운 수)."""
    kept = [s for s in segments if _key(s['text']) not in KNOWN]
    phrases = len(segments) - len(kept)
    out = []
    rep = 0
    i = 0
    while i < len(kept):
        k = _key(kept[i]['text'])
        j = i + 1
        while j < len(kept) and k and _key(kept[j]['text']) == k:
            j += 1
        if j - i >= 3:
            out.append(kept[i])
            rep += j - i - 1
        else:
            out.extend(kept[i:j])
        i = j
    final = []
    for s in out:
        parts = _sentences(s['text'])
        if len(parts) < 3:
            final.append(s)
            continue
        res = []
        cut = 0
        i = 0
        while i < len(parts):
            k = _key(parts[i])
            j = i + 1
            while j < len(parts) and k and _key(parts[j]) == k:
                j += 1
            if j - i >= 3:
                res.append(parts[i])
                cut += j - i - 1
            else:
                res.extend(parts[i:j])
            i = j
        rep += cut
        final.append(dict(s, text=' '.join(res)) if cut else s)
    return final, phrases + rep, phrases, rep


def loops(text, min_unit=2, min_repeat=4):
    """반복 고리: 정규화 글자열에서 길이 min_unit~20인 같은 단위가 min_repeat번 이상 붙어 반복되는 곳의 수."""
    t = normalize(text)
    found = 0
    i = 0
    pat = re.compile(r'(.{%d,20}?)\1{%d,}' % (min_unit, min_repeat - 1))
    for m in pat.finditer(t):
        found += 1
        i = m.end()
    return found


def silence_hallucinations(segments, speech_spans, min_inside=0.8):
    """segments: [(시작 ms, 끝 ms, 글자)] 원래 녹음 시각. speech_spans: 정답 말소리 구간 [(시작 ms, 끝 ms)].
    정규화 글자가 1자 이상이고 시간의 min_inside 이상이 정답 말소리 밖(묵음)에 있는 조각 수."""
    n = 0
    spans = sorted(speech_spans)
    for a, b, text in segments:
        if not normalize(text):
            continue
        dur = max(1, b - a)
        overlap = 0
        for s, e in spans:
            if e <= a:
                continue
            if s >= b:
                break
            overlap += min(b, e) - max(a, s)
        if (dur - overlap) / dur >= min_inside:
            n += 1
    return n


def disagreements(a_norm, b_norm, context=4):
    """두 자막(정규화)을 글자 정렬해 다른 곳 목록: [(a 조각, b 조각, 숫자가 걸렸나)]. 앞뒤 context 글자를 붙인다."""
    out = []
    for tag, i1, i2, j1, j2 in Levenshtein.opcodes(a_norm, b_norm):
        if tag == 'equal':
            continue
        x = a_norm[max(0, i1 - context):i2 + context]
        y = b_norm[max(0, j1 - context):j2 + context]
        out.append((x, y, bool(re.search(r'\d', a_norm[i1:i2] + b_norm[j1:j2]))))
    return out


def disagree_mask(a_norm, b_norm):
    """a 기준으로 b와 다른 글자 위치 표시(삽입은 앞 글자에 표시). 사람 확인 대상 범위 계산용."""
    mask = [False] * len(a_norm)
    for tag, i1, i2, j1, j2 in Levenshtein.opcodes(a_norm, b_norm):
        if tag == 'equal':
            continue
        if i2 > i1:
            for k in range(i1, i2):
                mask[k] = True
        elif a_norm:
            mask[min(len(a_norm) - 1, max(0, i1 - 1))] = True
    return mask


def nfc(t):
    return unicodedata.normalize('NFC', t)
