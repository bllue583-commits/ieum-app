#!/usr/bin/env python3
"""Checks ops/inbox-config.json before it is copied to the mailbox as app/config.json.

The phone reads app/config.json once per heartbeat (docs/OPERATIONS.md, "엔진 측정대와 측정 표본"). A typo there
silently means "off", so the copy step refuses anything the phone would not understand.
Usage: config_check.py <file>   (exit 0 and one summary line when fine, exit 1 with the reasons otherwise)
"""
import json
import sys

CANDIDATES = {"a", "b", "c"}


def _int(v, lo, hi):
    return isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi


def problems(cfg):
    out = []
    if not isinstance(cfg, dict):
        return ["top level must be an object"]
    for k in cfg:
        if k not in ("bench", "samples"):
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
    return out


def summary(cfg):
    b, s = cfg.get("bench") or {}, cfg.get("samples") or {}
    return "bench=%s samples=%s" % ("on" if b.get("on") is True else "off", "on" if s.get("on") is True else "off")


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
