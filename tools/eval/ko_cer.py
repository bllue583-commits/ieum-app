"""Korean CER with normalization + paired bootstrap. Usage: python -I ko_cer.py data.jsonl
data.jsonl lines: {"id":..,"ref":..,"a":..,"b":..}  (a,b = two STT systems)
Normalization (applied identically to ref and hyp):
  NFC -> spoken/mixed numbers to integers ("삼십만 원"/"30만원" -> 300000원) -> native counters ("두 장" -> 2장)
  -> 퍼센트/프로 -> % -> remove punctuation and ALL whitespace (spacing-insensitive CER)."""
import json, re, sys, random, unicodedata
import jiwer

SINO = {'영':0,'공':0,'일':1,'이':2,'삼':3,'사':4,'오':5,'육':6,'륙':6,'칠':7,'팔':8,'구':9}
SMALL = {'십':10,'백':100,'천':1000}
BIG = {'만':10**4,'억':10**8,'조':10**12}
NATIVE = {'한':1,'두':2,'세':3,'네':4,'다섯':5,'여섯':6,'일곱':7,'여덟':8,'아홉':9,'열':10}
COUNTERS = '시|개|명|번|살|장|대|권|달|병|잔|통'
NUMCH = '영공일이삼사오육륙칠팔구십백천만억조'
# a spoken number must start a word and be followed by a unit, so "이사", "사과", "내일" are untouched
UNIT = r'(?=\s*(?:원|만|억|조|시|분|초|일|월|년|개|명|번|층|호|퍼센트|프로|%|km|kg|살|장|대|권|달|주|회|차))'
TOK = r'(?:\d[\d,]*|[' + NUMCH + '])'
SPAN = re.compile(r'(?<![가-힣\d])' + TOK + r'(?:\s?' + TOK + r')*' + UNIT)

def parse_num(span):
    """digits, sino-Korean digits and units mixed: '3억 5천만', '30만', '천이백', '1,200'"""
    total, chunk, num = 0, 0, None
    for t in re.findall(r'\d[\d,]*|[' + NUMCH + ']', span):
        if t[0].isdigit(): num = int(t.replace(',', ''))
        elif t in SINO: num = SINO[t]
        elif t in SMALL: chunk += (1 if num is None else num) * SMALL[t]; num = None
        elif t in BIG: total += ((chunk + (num or 0)) or 1) * BIG[t]; chunk, num = 0, None
    return total + chunk + (num or 0)

def normalize(t):
    t = unicodedata.normalize('NFC', t)
    t = SPAN.sub(lambda m: str(parse_num(m.group(0))), t)
    t = re.sub(r'(?<![가-힣])(다섯|여섯|일곱|여덟|아홉|한|두|세|네|열)\s*(?=(' + COUNTERS + '))',
               lambda m: str(NATIVE[m.group(1)]), t)
    t = t.replace('퍼센트', '%').replace('프로', '%')
    t = re.sub(r'[^\w%]', '', t)
    return t.lower()

def cer(refs, hyps):
    o = jiwer.process_characters(refs, hyps)
    return o.cer, o.substitutions + o.deletions + o.insertions, sum(len(r) for r in refs)

def paired_bootstrap(rows, n=10000, seed=0):
    """resample utterances with replacement (same indices for A and B); p = share of resamples where B is not better"""
    rnd = random.Random(seed)
    stats = [(cer([r], [a])[1], cer([r], [b])[1], len(r)) for r, a, b in rows]
    wins = 0
    for _ in range(n):
        s = [stats[rnd.randrange(len(stats))] for _ in stats]
        wins += sum(x[1] for x in s) < sum(x[0] for x in s)
    return 1 - wins / n

if __name__ == '__main__':
    data = [json.loads(l) for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
    for mode, f in (('raw(공백만 제거)', lambda x: re.sub(r'\s', '', x)), ('normalized', normalize)):
        R = [f(d['ref']) for d in data]; A = [f(d['a']) for d in data]; B = [f(d['b']) for d in data]
        print(f'{mode}: CER A={cer(R, A)[0]:.4f}  B={cer(R, B)[0]:.4f}')
    rows = [(normalize(d['ref']), normalize(d['a']), normalize(d['b'])) for d in data]
    for d, (r, a, b) in zip(data, rows):
        if r != a or r != b: print(' diff', d['id'], '| ref', r, '| A', a, '| B', b)
    print('paired bootstrap p(B not better than A) =', paired_bootstrap(rows))
