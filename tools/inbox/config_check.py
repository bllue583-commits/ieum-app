#!/usr/bin/env python3
"""Checks ops/inbox-config.json before it is copied to the mailbox as app/config.json.

The phone reads app/config.json once per heartbeat (docs/OPERATIONS.md, "엔진 측정대와 측정 표본"). A typo there
silently means "off", so the copy step refuses anything the phone would not understand.
Usage: config_check.py <file>   (exit 0 and one summary line when fine, exit 1 with the reasons otherwise)
"""
import json
import re
import sys

CANDIDATES = {"a", "b", "c"}


def _int(v, lo, hi):
    return isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi


def problems(cfg):
    out = []
    if not isinstance(cfg, dict):
        return ["top level must be an object"]
    for k in cfg:
        if k not in ("bench", "samples", "power", "kakao", "site"):
            out.append(f"unknown block: {k}")
    b = cfg.get("bench")
    if b is not None:
        if not isinstance(b, dict):
            out.append("bench must be an object")
        else:
            for k in b:
                if k not in ("on", "candidates", "calls", "run", "threads", "fgMinutes"):
                    out.append(f"bench: unknown key {k}")
            if not isinstance(b.get("on"), bool):
                out.append("bench.on must be true or false (not a string)")
            c = b.get("candidates")
            if c is not None and not (isinstance(c, list) and c and all(x in CANDIDATES for x in c)
                                      and len(set(c)) == len(c)):
                out.append("bench.candidates must be a list out of a, b, c")
            if "calls" in b and not _int(b["calls"], 1, 40):
                out.append("bench.calls must be 1..40")
            if "run" in b and not _int(b["run"], 0, 1_000_000):
                out.append("bench.run must be a whole number")
            t = b.get("threads")
            if t is not None and not (isinstance(t, dict) and all(k in CANDIDATES and _int(v, 1, 8)
                                                                   for k, v in t.items())):
                out.append("bench.threads must map a/b/c to 1..8")
            if "fgMinutes" in b and not _int(b["fgMinutes"], 1, 360):
                out.append("bench.fgMinutes must be 1..360")
    s = cfg.get("samples")
    if s is not None:
        if not isinstance(s, dict):
            out.append("samples must be an object")
        else:
            for k in s:
                if k not in ("on", "calls", "maxMinutes"):
                    out.append(f"samples: unknown key {k}")
            if not isinstance(s.get("on"), bool):
                out.append("samples.on must be true or false (not a string)")
            if "calls" in s and not _int(s["calls"], 1, 30):
                out.append("samples.calls must be 1..30")
            if "maxMinutes" in s and not _int(s["maxMinutes"], 1, 120):
                out.append("samples.maxMinutes must be 1..120")
    # v4.3 r24-3: the day's battery budget in minutes (absent: 20). The phone takes anything else as the default.
    p = cfg.get("power")
    if p is not None:
        if not isinstance(p, dict):
            out.append("power must be an object")
        else:
            for k in p:
                if k not in ("budgetMin", "budgetPct"):
                    out.append(f"power: unknown key {k}")
            if "budgetMin" in p and not _int(p["budgetMin"], 10, 60):
                out.append("power.budgetMin must be a whole number 10..60")
            # v4.6 r27-1: the share of the battery (%) a day's battery runs may take (absent: 5).
            if "budgetPct" in p and not _int(p["budgetPct"], 3, 15):
                out.append("power.budgetPct must be a whole number 3..15")
    # v4.4 r25 review: the web view's fragment cap in characters (absent: 2000). The phone takes anything else as the default.
    kk = cfg.get("kakao")
    if kk is not None:
        if not isinstance(kk, dict):
            out.append("kakao must be an object")
        else:
            for k in kk:
                if k != "webMax":
                    out.append(f"kakao: unknown key {k}")
            if "webMax" in kk and not _int(kk["webMax"], 1000, 6000):
                out.append("kakao.webMax must be a whole number 1000..6000")
    # v4.5 r26-1: the forecast's site (absent: near Incheon City Hall). The phone takes anything else as the default.
    st = cfg.get("site")
    if st is not None:
        if not isinstance(st, dict):
            out.append("site must be an object")
        else:
            for k in st:
                if k not in ("lat", "lon", "label"):
                    out.append(f"site: unknown key {k}")
            if not _num(st.get("lat"), 33, 39):
                out.append("site.lat must be a number 33..39")
            if not _num(st.get("lon"), 124, 132):
                out.append("site.lon must be a number 124..132")
            if st.get("label") is not None and not (isinstance(st["label"], str) and LABEL.fullmatch(st["label"].strip() or "-")):
                out.append("site.label must be 1..12 Korean or Latin letters")
    return out


LABEL = re.compile(r"[가-힣A-Za-z ]{1,12}")


def _num(v, lo, hi):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v == v and lo <= v <= hi


DEFAULT_BUDGET_MIN = 20
DEFAULT_BUDGET_PCT = 5
DEFAULT_WEB_MAX = 2000


def summary(cfg):
    b, s = cfg.get("bench") or {}, cfg.get("samples") or {}
    budget = (cfg.get("power") or {}).get("budgetMin", DEFAULT_BUDGET_MIN)
    pct = (cfg.get("power") or {}).get("budgetPct", DEFAULT_BUDGET_PCT)
    web = (cfg.get("kakao") or {}).get("webMax", DEFAULT_WEB_MAX)
    site = cfg.get("site")
    where = "default" if not site else "%s,%s" % (site.get("lat"), site.get("lon"))
    return "bench=%s samples=%s budgetMin=%d budgetPct=%d webMax=%d site=%s" % ("on" if b.get("on") is True else "off", "on" if s.get("on") is True else "off", budget, pct, web, where)


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    try:
        with open(argv[1], encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        print(f"cannot read {argv[1]}: {e}")
        return 1
    bad = problems(cfg)
    for p in bad:
        print(p)
    if bad:
        return 1
    print(summary(cfg))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
