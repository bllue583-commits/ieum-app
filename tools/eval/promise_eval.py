"""Promise extraction against a gold set (SCORECARD item 4, DESIGN_V7 7). Precision, recall and number exactness.

Usage:
  python3 -I promise_eval.py GOLD.json PRED.json                      # print the numbers
  python3 -I promise_eval.py GOLD.json --results DIR [--write OUT]    # PRED from the inbox's analysis results
  python3 -I promise_eval.py GOLD.json PRED.json --write OUT          # OUT in the status/gold-commit.json format

GOLD and PRED (the same format, README "정답지 형식"): a JSON list of calls
  {"call_id": str, "promises": [{"who": "me"|"them", "what": str, "evidence": str, "numbers": [str], "due": "YYYY-MM-DD"|null}]}
--results DIR: every *.json in DIR in the hourly task's result format ({"id", "analysis"} or {"id", "results": [{"id",
"analysis"}]}; docs/routines/hourly.md 5). Each analysis's tasks with op "create" or "update" are its promises: who = owner,
what = title, evidence = quote, numbers = the numbers with a unit in the title, due = due.

Rules (fixed with the gold set; changing them changes every earlier number):
- Only calls in GOLD are scored. A GOLD call with no PRED entry at all (its result was not kept) is left out and counted in
  missingCalls; a PRED entry with an empty list is a call where nothing was found.
- A pair matches when who is the same and the content similarity of what is at least 0.6 (the best match of the shorter text
  against every window of the longer one of its length, difflib ratio, after ko_cer.normalize). Greedy 1:1 per call, the most
  similar first.
- A content match with the same set of normalized numbers is a true positive. One whose numbers differ is not (it counts in
  numberMismatch, and its promise counts as missed and as wrong): a promise with a wrong amount is a wrong promise.
- numberExact = over the content matches whose GOLD promise has numbers: the numbers both have / the numbers either has.
- commitNumbers = all numbers in the scored GOLD calls; commitCalls = the scored GOLD calls.
No dependency outside the standard library (ko_cer's jiwer is not needed for normalize)."""
import argparse, difflib, json, os, re, sys, time, types

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_stub = False
try:
    import jiwer  # noqa: F401  (ko_cer imports it at the top; only its cer() uses it)
except ImportError:
    sys.modules['jiwer'] = types.ModuleType('jiwer'); _stub = True
import ko_cer  # noqa: E402
if _stub:
    del sys.modules['jiwer']  # nothing else in the process should take the stand-in for the real jiwer

THRESHOLD = 0.6
OPS = ('create', 'update')
VERSION = 2


def norm(text):
    return ko_cer.normalize(text or '')


UNIT_AFTER = re.compile(r'\s*(?:원|만|억|조|시|분|초|일|월|년|개|명|번|층|호|퍼센트|프로|%|km|kg|살|장|대|권|달|주|회|차)+')


def numbers_in(text):
    """Numbers with a unit in [text] ("철근 30만원 입금" -> ["30만원"]), as written (normalized when compared)."""
    text = text or ''
    out = []
    for m in ko_cer.SPAN.finditer(text):
        unit = UNIT_AFTER.match(text, m.end())
        out.append(text[m.start():unit.end() if unit else m.end()])
    return out


def nums(p):
    return {norm(n) for n in p.get('numbers') or [] if norm(n)}


def partial(a, b):
    """Similarity of the shorter of [a] and [b] to its best window in the longer (0..1)."""
    a, b = norm(a), norm(b)
    if not a or not b:
        return 0.0
    s, l = (a, b) if len(a) <= len(b) else (b, a)
    best = 0.0
    for i in range(len(l) - len(s) + 1):
        best = max(best, difflib.SequenceMatcher(None, s, l[i:i + len(s)]).ratio())
        if best == 1.0:
            break
    return best


def similarity(g, p):
    return partial(g['what'], p['what']) if g.get('who') == p.get('who') else 0.0


def load_calls(path):
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    if not isinstance(data, list):
        raise SystemExit(f'{path}: a JSON list of calls is expected')
    out = {}
    for c in data:
        out[str(c['call_id'])] = list(c.get('promises') or [])
    return out


def from_analysis(analysis):
    out = []
    for t in (analysis or {}).get('tasks') or []:
        if str(t.get('op', '')).lower() not in OPS:
            continue
        title = str(t.get('title') or '').strip()
        if not title:
            continue
        out.append({'who': str(t.get('owner') or 'unknown').lower(), 'what': title, 'evidence': str(t.get('quote') or ''),
                    'numbers': numbers_in(title), 'due': t.get('due')})
    return out


def load_results(folder):
    """call id -> promises, from the hourly task's result files in [folder]."""
    out = {}
    for name in sorted(os.listdir(folder)):
        if not name.endswith('.json'):
            continue
        with open(os.path.join(folder, name), encoding='utf-8') as f:
            o = json.load(f)
        if isinstance(o.get('results'), list):
            for r in o['results']:
                if isinstance(r, dict) and r.get('id') is not None:
                    out[str(r['id'])] = from_analysis(r.get('analysis'))
        elif o.get('id') is not None:
            out[str(o['id'])] = from_analysis(o.get('analysis'))
    return out


def evaluate(gold, pred):
    tp = fp = fn = mismatch = 0
    both = either = 0
    due_same = due_seen = 0
    unknown = 0
    scored = [cid for cid in gold if cid in pred]
    missing = sorted(cid for cid in gold if cid not in pred)
    for cid in scored:
        G, P = gold[cid], pred[cid]
        unknown += sum(1 for p in P if p.get('who') not in ('me', 'them'))
        pairs = sorted(((similarity(g, p), i, j) for i, g in enumerate(G) for j, p in enumerate(P)), key=lambda x: (-x[0], x[1], x[2]))
        used_g, used_p = set(), set()
        for s, i, j in pairs:
            if s < THRESHOLD or i in used_g or j in used_p:
                continue
            used_g.add(i); used_p.add(j)
            gn, pn = nums(G[i]), nums(P[j])
            if gn:
                both += len(gn & pn); either += len(gn | pn)
            if G[i].get('due'):
                due_seen += 1; due_same += G[i].get('due') == P[j].get('due')
            if gn == pn:
                tp += 1
            else:
                mismatch += 1; fp += 1; fn += 1
        fp += len(P) - len(used_p); fn += len(G) - len(used_g)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        'commitRecall': round(recall, 4), 'commitPrecision': round(precision, 4),
        'numberExact': round(both / either, 4) if either else None,
        'commitCalls': len(scored), 'commitNumbers': sum(len(nums(p)) for cid in scored for p in gold[cid]),
        'tp': tp, 'fp': fp, 'fn': fn, 'numberMismatch': mismatch, 'unknownOwner': unknown,
        'dueExact': round(due_same / due_seen, 4) if due_seen else None,
        'f1': round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0,
        'missingCalls': len(missing), 'goldCalls': len(gold),
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('gold')
    ap.add_argument('pred', nargs='?')
    ap.add_argument('--results', help="a folder of the hourly task's result files")
    ap.add_argument('--write', help='write the status/gold-commit.json form here')
    a = ap.parse_args(argv)
    if (a.pred is None) == (a.results is None):
        ap.error('give PRED.json or --results DIR (one of them)')
    gold = load_calls(a.gold)
    pred = load_results(a.results) if a.results else load_calls(a.pred)
    out = evaluate(gold, pred)
    out = {**out, 'atMs': int(time.time() * 1000), 'tool': f'promise_eval v{VERSION}'}
    if a.write:
        with open(a.write, 'w', encoding='utf-8') as f:
            json.dump(out, f, ensure_ascii=False, sort_keys=True)
            f.write('\n')
    print(f"TP={out['tp']} FP={out['fp']} FN={out['fn']} number_mismatch={out['numberMismatch']} "
          f"precision={out['commitPrecision']:.3f} recall={out['commitRecall']:.3f} F1={out['f1']:.3f} "
          f"numberExact={out['numberExact']} calls={out['commitCalls']} numbers={out['commitNumbers']} missing={out['missingCalls']}")
    return out


if __name__ == '__main__':
    main()
