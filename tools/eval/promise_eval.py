"""Promise-extraction P/R/F1. Usage: python -I promise_eval.py gold.json pred.json
item = {"call_id","promises":[{"who":"me|them","what":str,"evidence":str,"numbers":[str]}]}
Match rule: same who AND what-similarity>=0.6 (rapidfuzz partial ratio on normalized text) AND numbers equal as sets
(after ko_cer.normalize). Greedy 1:1 matching per call. Also reports number-mismatch count separately."""
import json, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rapidfuzz import fuzz
from ko_cer import normalize
def nums(p): return {normalize(n) for n in p.get('numbers', [])}
def score(g, p):
    if g['who'] != p['who']: return 0
    return fuzz.partial_ratio(normalize(g['what']), normalize(p['what'])) / 100
gold = {c['call_id']: c['promises'] for c in json.load(open(sys.argv[1]))}
pred = {c['call_id']: c['promises'] for c in json.load(open(sys.argv[2]))}
tp = fp = fn = nummis = 0
for cid in gold.keys() | pred.keys():
    G, P = list(gold.get(cid, [])), list(pred.get(cid, []))
    pairs = sorted(((score(g, p), i, j) for i, g in enumerate(G) for j, p in enumerate(P)), reverse=True)
    ug, up = set(), set()
    for s, i, j in pairs:
        if s < 0.6 or i in ug or j in up: continue
        if nums(G[i]) != nums(P[j]): nummis += 1; continue
        ug.add(i); up.add(j); tp += 1
    fp += len(P) - len(up); fn += len(G) - len(ug)
pr = tp / (tp + fp) if tp + fp else 0; rc = tp / (tp + fn) if tp + fn else 0
print(f'TP={tp} FP={fp} FN={fn} number_mismatch={nummis} precision={pr:.3f} recall={rc:.3f} F1={2*pr*rc/(pr+rc) if pr+rc else 0:.3f}')
