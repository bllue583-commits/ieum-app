"""채점: <lab>/runs/<묶음>/<엔진>/*.json 과 <lab>/sets/<묶음>.json → <lab>/scores/<묶음>.json, 표준 출력에는 숫자만.
python3 -I tools/eval/asrlab/score.py --lab /home/claude/asrlab --set A [--engines q5,q8,sv,lv3,zip] [--base q5]

- Whisper 계열(q5, q8, lv3)은 앱이 저장 직전에 하는 정리(TranscriptClean)를 거친 뒤 잰다(지운 수는 따로 셈).
- 녹음 전체 자막을 정규화(ko_cer.normalize)하고 글자 정렬로 문장별로 나눠 문장별 편집 수를 낸다 → 오류율 = 합/합,
  95% 구간 = 문장 부트스트랩, 엔진 간 = 짝 부트스트랩(같은 문장 표본).
- 숫자·이름은 녹음(통화) 단위로 앱 KoCer.numberHits/nameHits와 같은 규칙.
- 묵음 환각: 원래 시각으로 되돌린 자막 조각 중 80% 이상이 정답 말소리 밖인 것(E에서 의미 있음)."""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labscore as L  # noqa: E402

WHISPER = {'q5', 'q8', 'lv3'}


def load_engine(lab, set_name, engine, recs):
    d = os.path.join(lab, 'runs', set_name, engine)
    out = {}
    for r in recs:
        p = os.path.join(d, r['rec'] + '.json')
        if os.path.exists(p):
            out[r['rec']] = json.load(open(p))
    return out


def text_of(run, engine):
    segs = run['segments']
    removed = phrases = reps = 0
    if engine in WHISPER:
        segs, removed, phrases, reps = L.transcript_clean(segs)
    return segs, removed, phrases, reps


def score_engine(recs, runs, engine, with_original=False):
    utt_stats = {}
    orig_stats = {}
    num_hit = num_tot = name_hit = name_tot = 0
    removed = loops = halluc = 0
    audio = work = 0
    per_rec = {}
    for r in recs:
        run = runs.get(r['rec'])
        if not run:
            continue
        segs, rem, _, _ = text_of(run, engine)
        removed += rem
        hyp_raw = ' '.join(s['text'] for s in segs)
        loops += L.loops(hyp_raw)
        hyp = L.normalize(hyp_raw)
        refs = [L.normalize(u['ref']) for u in r['utts']]
        parts = L.split_by_ref(refs, hyp)
        for u, rn, hp in zip(r['utts'], refs, parts):
            utt_stats[u['id']] = (L.edits(rn, hp), len(rn))
        if with_original:
            origs = [L.normalize(u.get('ref_original') or u['ref']) for u in r['utts']]
            for u, rn, hp in zip(r['utts'], origs, L.split_by_ref(origs, hyp)):
                orig_stats[u['id']] = (L.edits(rn, hp), len(rn))
        ref_all = ''.join(refs)
        h, t = L.number_hits(ref_all, hyp)
        num_hit += h
        num_tot += t
        names = sorted({L.normalize(n) for u in r['utts'] for n in u.get('names', [])})
        if names:
            h, t = L.name_hits(ref_all, hyp, names)
            name_hit += h
            name_tot += t
        spans = [(u['start_ms'], u['end_ms']) for u in r['utts']]
        hs = L.silence_hallucinations([(s['t0'], s['t1'], s['text']) for s in segs], spans)
        halluc += hs
        audio += run['audio_ms']
        work += run['work_ms']
        per_rec[r['rec']] = dict(hyp_norm=hyp, ref_norm=ref_all, halluc=hs, numbers=(L.number_hits(ref_all, hyp)), names=names)
    ids = sorted(utt_stats)
    st = [utt_stats[i] for i in ids]
    e = sum(x[0] for x in st)
    n = sum(x[1] for x in st)
    lo, hi = L.bootstrap_ci(st)
    res = dict(engine=engine, recs=len(per_rec), utts=len(st), ref_chars=n, edits=e, cer=e / n if n else None, ci=[lo, hi],
               numbers=[num_hit, num_tot], names=[name_hit, name_tot], cleaned=removed, loops=loops, silence_halluc=halluc,
               halluc_per_call=halluc / len(per_rec) if per_rec else None, audio_min=audio / 60000, rtf=work / audio if audio else None)
    if with_original and orig_stats:
        so = [orig_stats[i] for i in sorted(orig_stats)]
        res['cer_original_ref'] = sum(x[0] for x in so) / sum(x[1] for x in so)
        res['ci_original_ref'] = list(L.bootstrap_ci(so))
    return res, dict(zip(ids, st)), per_rec


def draft_final(per_a, per_b):
    """초벌 b(SenseVoice)와 확정 a(Whisper)의 숫자·이름 불일치: a의 숫자 토큰 중 b에 없는 비율, 불일치가 하나라도 있는 통화 비율."""
    tok = miss = calls = bad = 0
    ntok = nmiss = 0
    for rec, a in per_a.items():
        b = per_b.get(rec)
        if not b:
            continue
        calls += 1
        h, t = L.number_hits(a['hyp_norm'], b['hyp_norm'])
        tok += t
        miss += t - h
        rev = L.number_hits(b['hyp_norm'], a['hyp_norm'])
        nh = nt = 0
        if a['names']:
            said = [n for n in a['names'] if n in a['hyp_norm']]
            nt = len(said)
            nh = sum(1 for n in said if n in b['hyp_norm'])
        ntok += nt
        nmiss += nt - nh
        if t - h > 0 or rev[1] - rev[0] > 0 or nt - nh > 0:
            bad += 1
    return dict(number_tokens=tok, number_mismatch=miss, number_mismatch_rate=miss / tok if tok else None,
                name_tokens=ntok, name_mismatch=nmiss, name_mismatch_rate=nmiss / ntok if ntok else None,
                calls=calls, calls_with_mismatch=bad)


def reference_methods(per, recs, audio_min):
    """기준 자막 방법별: 기준 자막(lv3 등)의 정답 대비 오류율, 숫자 오류, 다른 모델과의 불일치 구간만 사람이 볼 때의 양과 남는 오류."""
    out = {}
    cands = [k for k in ('lv3', 'q5', 'q8', 'sv', 'zip') if k in per]
    for ref_eng in cands:
        for other in [None] + [c for c in cands if c != ref_eng]:
            tot_ref = tot_err = flagged = flagged_num = residual = residual_numonly = err_total = 0
            num_err = num_tot = 0
            num_err_unflagged = 0
            for rec in per[ref_eng]:
                if other and rec not in per[other]:
                    continue
                g = per[ref_eng][rec]
                truth = g['ref_norm']
                hyp = g['hyp_norm']
                err = L.disagree_mask(hyp, truth)
                tot_ref += len(truth)
                tot_err += L.edits(truth, hyp)
                h, t = L.number_hits(truth, hyp)
                num_err += t - h
                num_tot += t
                if other:
                    flag = L.disagree_mask(hyp, per[other][rec]['hyp_norm'])
                    # 앞뒤 2글자 여유(사람은 구간을 본다)
                    wide = [any(flag[max(0, i - 2):i + 3]) for i in range(len(flag))]
                    numflag = [w and any(c.isdigit() for c in hyp[max(0, i - 4):i + 5]) for i, w in enumerate(wide)]
                    flagged += sum(wide)
                    flagged_num += sum(numflag)
                    residual += sum(1 for e, w in zip(err, wide) if e and not w)
                    residual_numonly += sum(1 for e, w in zip(err, numflag) if e and not w)
                    err_total += sum(err)
            key = ref_eng + ('+' + other if other else '')
            r = dict(ref=ref_eng, check_against=other, cer=tot_err / tot_ref if tot_ref else None, number_errors=[num_err, num_tot])
            if other:
                r.update(check_chars_per_min=flagged / audio_min if audio_min else None, check_num_chars_per_min=flagged_num / audio_min if audio_min else None,
                         residual_err_char_rate=residual / tot_ref if tot_ref else None, residual_err_char_rate_numonly=residual_numonly / tot_ref if tot_ref else None,
                         flagged_share_of_errors=1 - residual / err_total if err_total else None)
            out[key] = r
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lab', default='/home/claude/asrlab')
    ap.add_argument('--set', required=True)
    ap.add_argument('--engines', default='q5,q8,sv,lv3,zip')
    ap.add_argument('--base', default='q5')
    ap.add_argument('--original', action='store_true', help='원래 공개 정답(text_original)으로도 잰다(문헌 대조)')
    a = ap.parse_args()
    recs = json.load(open(os.path.join(a.lab, 'sets', a.set + '.json')))
    res = {}
    stats = {}
    per = {}
    for e in a.engines.split(','):
        runs = load_engine(a.lab, a.set, e, recs)
        if not runs:
            continue
        res[e], stats[e], per[e] = score_engine(recs, runs, e, a.original)
    # 짝 비교는 모든 엔진이 끝낸 녹음만
    common = None
    for e in per:
        common = set(per[e]) if common is None else common & set(per[e])
    pairs = {}
    if a.base in stats:
        for e in stats:
            if e == a.base:
                continue
            ids = sorted(set(stats[e]) & set(stats[a.base]))
            sa = [stats[a.base][i] for i in ids]
            sb = [stats[e][i] for i in ids]
            if sa and sum(x[0] for x in sa):
                lo, hi, p = L.paired(sa, sb)
                pairs[e] = dict(vs=a.base, utts=len(ids), cer_base=sum(x[0] for x in sa) / sum(x[1] for x in sa),
                                cer=sum(x[0] for x in sb) / sum(x[1] for x in sb), ratio=(sum(x[0] for x in sb) / sum(x[0] for x in sa)),
                                ratio_ci=[lo, hi], p_not_better=p)
    df = {}
    for fin in ('q5', 'q8'):
        if fin in per and 'sv' in per:
            df[fin + '_vs_sv'] = draft_final(per[fin], per['sv'])
    audio_min = None
    if common:
        audio_min = sum(r['ms'] for r in recs if r['rec'] in common) / 60000
    refm = reference_methods({e: {k: v for k, v in per[e].items() if k in common} for e in per}, recs, audio_min) if common else {}
    out = dict(set=a.set, engines=res, paired=pairs, draft_final=df, reference=refm, common_recs=sorted(common or []))
    os.makedirs(os.path.join(a.lab, 'scores'), exist_ok=True)
    json.dump(out, open(os.path.join(a.lab, 'scores', a.set + '.json'), 'w'), ensure_ascii=False, indent=1)
    for e, r in res.items():
        print('%-4s %-3s recs=%d utts=%d CER=%.4f [%.4f,%.4f] num=%d/%d name=%d/%d cleaned=%d loops=%d sil_halluc=%d rtf=%.2f%s' % (
            a.set, e, r['recs'], r['utts'], r['cer'], r['ci'][0], r['ci'][1], r['numbers'][0], r['numbers'][1], r['names'][0], r['names'][1],
            r['cleaned'], r['loops'], r['silence_halluc'], r['rtf'] or 0,
            ' orig=%.4f' % r['cer_original_ref'] if 'cer_original_ref' in r else ''))
    for e, p in pairs.items():
        print('pair %s/%s utts=%d ratio=%.3f [%.3f,%.3f] p=%.3f' % (e, p['vs'], p['utts'], p['ratio'], p['ratio_ci'][0], p['ratio_ci'][1], p['p_not_better']))
    for k, v in df.items():
        print('draft', k, json.dumps(v))


if __name__ == '__main__':
    main()
