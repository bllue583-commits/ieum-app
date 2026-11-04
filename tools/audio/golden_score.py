#!/usr/bin/env python3
"""Scores the golden-set transcripts of CallPipelineDeviceTest.tg_goldenSet (v24-2, design F5).

  golden_score.py <golden.json> <golden_runs.json | results.txt> [--out score.json]

golden.json is android/app/src/androidTest/assets/golden/golden.json (answers + entities). The second argument is the
test's output file (device-test/golden_runs.json) or, when that could not be pulled, the logcat lines (IEUM_RESULT
golden_text ...). Prints per-call CER and entity accuracy for each variant and a comparison table against "base";
writes the same as JSON with --out. Exit 0 when something was scored, 1 when nothing could be.

CER: Levenshtein distance / reference length after the same normalization on both sides (lower case, spaces and
punctuation removed, digits read out in Korean, Latin letters read as Korean letter names), so "20톤" and "이십 톤",
"D13" and "디 십삼" are the same. Entity accuracy: an entity counts when its text or one of its variants appears in
the normalized transcript. Standard library only (runs on the CI runner as is).
"""
import json
import re
import sys

SINO = "영일이삼사오육칠팔구"
NATIVE_ONES = ["", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"]
NATIVE_TENS = ["", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔"]
# Counters read with native numbers (1..99): 일곱 시, 한 대, 열 개. 개월 and 시간's sino uses are excluded below.
NATIVE_COUNTERS = ("시간", "시", "개", "대", "명", "장", "살", "군데", "가지", "통")
LETTERS = {"a": "에이", "b": "비", "c": "씨", "d": "디", "e": "이", "f": "에프", "g": "지", "h": "에이치", "i": "아이",
           "j": "제이", "k": "케이", "l": "엘", "m": "엠", "n": "엔", "o": "오", "p": "피", "q": "큐", "r": "알",
           "s": "에스", "t": "티", "u": "유", "v": "브이", "w": "더블유", "x": "엑스", "y": "와이", "z": "지"}
SYMBOLS = {"㎥": "루베", "%": "퍼센트", "㎜": "밀리", "㎝": "센티", "㎏": "킬로"}


def sino_under_10000(n):
    out = ""
    for unit, value in (("천", 1000), ("백", 100), ("십", 10)):
        d = n // value
        if d:
            out += ("" if d == 1 else SINO[d]) + unit
        n %= value
    if n:
        out += SINO[n]
    return out


def sino(n):
    if n == 0:
        return "영"
    out = ""
    for unit, value in (("조", 10 ** 12), ("억", 10 ** 8), ("만", 10 ** 4)):
        d = n // value
        if d:
            out += sino_under_10000(d) + unit
        n %= value
    return out + sino_under_10000(n)


def native(n):
    if n == 20:
        return "스무"
    return NATIVE_TENS[n // 10] + NATIVE_ONES[n % 10]


def read_numbers(text):
    """Digits -> Korean reading. Commas inside numbers are dropped first ("2,405,000" -> 2405000)."""
    text = re.sub(r"(?<=\d),(?=\d{3})", "", text)

    def one(m):
        digits, rest = m.group(1), text[m.end():]
        rest = rest.lstrip()
        n = int(digits)
        if 0 < n < 100 and rest.startswith(NATIVE_COUNTERS) and not rest.startswith("개월"):
            return native(n)
        if len(digits) > 1 and digits.startswith("0"):
            return "".join(SINO[int(c)] if c != "0" else "공" for c in digits)
        return sino(n)

    return re.sub(r"(\d+)", one, text)


def normalize(text):
    text = text.lower()
    for k, v in SYMBOLS.items():
        text = text.replace(k, v)
    text = re.sub(r"(?<=\d)\s*m3\b", "루베", text)
    text = read_numbers(text)
    text = "".join(LETTERS.get(c, c) for c in text)
    return "".join(c for c in text if c.isalnum())


def distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def reference(call):
    return " ".join(t["say"] for t in call["turns"])


def score_call(call, hypothesis):
    ref, hyp = normalize(reference(call)), normalize(hypothesis)
    entities = []
    for e in call["entities"]:
        forms = {normalize(f) for f in [e["text"]] + e.get("variants", [])}
        entities.append({"type": e["type"], "text": e["text"], "found": any(f and f in hyp for f in forms)})
    return {"id": call["id"], "cer": round(distance(ref, hyp) / max(1, len(ref)), 4), "refChars": len(ref),
            "hypChars": len(hyp), "entities": entities,
            "entityAccuracy": round(sum(e["found"] for e in entities) / max(1, len(entities)), 4)}


def summarize(calls):
    entities = [e for c in calls for e in c["entities"]]
    by_type = {}
    for e in entities:
        t = by_type.setdefault(e["type"], [0, 0])
        t[0] += e["found"]
        t[1] += 1
    chars = sum(c["refChars"] for c in calls)
    return {"cer": round(sum(c["cer"] * c["refChars"] for c in calls) / max(1, chars), 4),
            "entityAccuracy": round(sum(e["found"] for e in entities) / max(1, len(entities)), 4),
            # F4 "용어 정확도": company names and specs (what the prompt is meant to help).
            "termAccuracy": round(sum(e["found"] for e in entities if e["type"] in ("company", "spec"))
                                  / max(1, sum(1 for e in entities if e["type"] in ("company", "spec"))), 4),
            "byType": {k: f"{v[0]}/{v[1]}" for k, v in sorted(by_type.items())}}


def load_runs(path):
    """golden_runs.json, or logcat lines 'IEUM_RESULT: golden_text {...}' / 'golden_variant {...}'."""
    raw = open(path, encoding="utf-8").read()
    try:
        return json.loads(raw)
    except ValueError:
        pass
    runs, order = {}, []
    for line in raw.splitlines():
        m = re.search(r"\bgolden_(text|variant) (\{.*\})\s*$", line)
        if not m:
            continue
        x = json.loads(m.group(2))
        v = runs.get(x["variant"])
        if v is None:
            v = runs[x["variant"]] = {"variant": x["variant"], "calls": []}
            order.append(x["variant"])
        if m.group(1) == "text":
            v["calls"].append(x)
        else:
            v.update({k: val for k, val in x.items() if k != "calls"})
    return {"model": None, "variants": [runs[k] for k in order]}


def pct(x):
    return "-" if x is None else f"{x * 100:.1f}"


def main(argv):
    args = [a for a in argv if not a.startswith("--out")]
    out = None
    if "--out" in argv:
        out = argv[argv.index("--out") + 1]
        args.remove(out)
    if len(args) != 2:
        print(__doc__)
        return 2
    golden = json.load(open(args[0], encoding="utf-8"))
    runs = load_runs(args[1])
    calls = {c["id"]: c for c in golden["calls"]}
    result = {"model": runs.get("model"), "variants": []}
    for v in runs.get("variants", []):
        row = {k: v.get(k) for k in ("variant", "floor", "prompt", "available", "why", "seconds")}
        if v.get("available", True) and v.get("calls"):
            row["calls"] = [dict(score_call(calls[c["id"]], c.get("text", "")), seconds=c.get("seconds"))
                            for c in v["calls"] if c["id"] in calls]
            row.update(summarize(row["calls"]))
        result["variants"].append(row)
    scored = [v for v in result["variants"] if v.get("calls")]
    base = next((v for v in scored if v["variant"] == "base"), None)
    print(f"== 골든 셋 채점 (model={result['model']}) ==")
    for v in scored:
        for c in v["calls"]:
            missed = ", ".join(e["text"] for e in c["entities"] if not e["found"]) or "-"
            print(f"[{v['variant']}] {c['id']}: CER {pct(c['cer'])}% 개체 {pct(c['entityAccuracy'])}% "
                  f"({sum(e['found'] for e in c['entities'])}/{len(c['entities'])}) {c['seconds']}s 놓침: {missed}")
    print("== 비교표 (CER 낮을수록, 개체·용어 높을수록 좋음; 차이는 base 대비 %p) ==")
    print("variant      | CER%  | 개체% | 용어% | dCER  | d개체 | d용어 | 유형별")
    for v in result["variants"]:
        if not v.get("calls"):
            print(f"{v['variant']:<12} | 측정 안 함: {v.get('why') or '결과 없음'}")
            continue
        d = (lambda k: "-" if base is None or v is base else f"{(v[k] - base[k]) * 100:+.1f}")
        print(f"{v['variant']:<12} | {pct(v['cer']):>5} | {pct(v['entityAccuracy']):>5} | {pct(v['termAccuracy']):>5} | "
              f"{d('cer'):>5} | {d('entityAccuracy'):>5} | {d('termAccuracy'):>5} | "
              + " ".join(f"{k} {x}" for k, x in v["byType"].items()))
    # Decision rules of design-gap #4 / #5 (the decision itself is made in v2.5-4 from these numbers).
    hints = []
    for v in scored:
        if base is None or v is base:
            continue
        dc, de, dt = v["cer"] - base["cer"], v["entityAccuracy"] - base["entityAccuracy"], v["termAccuracy"] - base["termAccuracy"]
        if v.get("prompt"):
            ok = dt > 0 and dc <= 0.02
            hints.append(f"{v['variant']}: prompt 규칙(용어 오름 & CER 2점 넘게 나빠지지 않음) {'충족' if ok else '불충족'}")
        elif v.get("floor") is not None:
            ok = dc < 0 and de >= 0
            hints.append(f"{v['variant']}: 하한 규칙(CER 낮아지고 개체 정확도 안 떨어짐) {'충족' if ok else '불충족'}")
    for h in hints:
        print("판정 참고 " + h)
    result["hints"] = hints
    if out:
        with open(out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
    return 0 if scored else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
