#!/usr/bin/env python3
"""우편함의 통화 대조표 이력에서 시뮬레이션 입력(calls.json)을 만든다. 시각·길이·개인 여부만 남긴다.

    python3 tools/sim/make_calls.py --inbox /path/to/ieum-inbox --out calls.json [--year 2026]

결과 파일에는 이름·번호가 없지만 통화 시각이 들어 있으므로 저장소에 올리지 않는다.
"""
import argparse, json, os, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "inbox"))
import score  # noqa: E402


def main(argv):
    ap = argparse.ArgumentParser(); ap.add_argument("--inbox", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--year", type=int, default=2026)
    a = ap.parse_args(argv)
    rows = [{"dt": c["at"].replace(tzinfo=None).isoformat(), "dur": c["seconds"], "status": "개인" if c["personal"] else "업무"}
            for c in sorted(score.calls_from_history(a.inbox, a.year), key=lambda c: c["at"])]
    with open(a.out, "w", encoding="utf-8") as handle:
        json.dump(rows, handle, ensure_ascii=False)
    print(f"통화 {len(rows)}건 → {a.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
