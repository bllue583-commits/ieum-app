"""v3.9 r20-6 (DESIGN_V7 6, SCORECARD items 6 and 10): the one line the survey page (docs/survey/ieum-survey.html) makes, to the
inbox's status/survey.json, in the keys tools/inbox/score.py reads (`sus`, `veryDisappointed`, `batterySharePct`).

Usage:  python3 -I tools/survey/survey_to_json.py "IEUM-SURVEY 1 | sus=4,2,... | miss=very | battery=6 | tasks=1,1,2,0,1 | date=2026-11-20" [--write status/survey.json]
        (or the line in a file: --file line.txt; the first line starting with IEUM-SURVEY is read)

SUS (Brooke 1996): odd items score answer-1, even items 5-answer, the sum times 2.5 (0..100). Numbers only come out; the
page sends no words. Nothing is written into the inbox by this tool unless --write points there (the person running it
commits the file)."""
import argparse, json, re, sys, time

FORM = 1
MISS = {"very": "매우 아쉽다", "some": "조금 아쉽다", "none": "상관없다"}
TASK_WORDS = {0: None, 1: "easy", 2: "struggled", 3: "failed"}
TASKS = ["findSummary", "checkAmount", "finishTask", "findPastCall", "leaveMemo"]


def sus_score(answers):
    if len(answers) != 10 or any(a not in (1, 2, 3, 4, 5) for a in answers):
        raise ValueError("SUS needs 10 answers from 1 to 5")
    return sum((a - 1) if i % 2 == 0 else (5 - a) for i, a in enumerate(answers)) * 2.5


def parse(line):
    line = line.strip()
    if not line.startswith("IEUM-SURVEY"):
        raise ValueError("not a survey line (it starts with IEUM-SURVEY)")
    head, *parts = [p.strip() for p in line.split("|")]
    version = int(head.split()[1]) if len(head.split()) > 1 else 0
    if version != FORM:
        raise ValueError(f"form {version} is not {FORM}")
    fields = {}
    for p in parts:
        if "=" in p:
            k, v = p.split("=", 1)
            fields[k.strip()] = v.strip()
    answers = [int(x) for x in re.findall(r"\d+", fields.get("sus", ""))]
    miss = fields.get("miss", "")
    if miss not in MISS:
        raise ValueError("miss must be very, some or none")
    battery = float(fields.get("battery", "nan"))
    if not (0 <= battery <= 100):
        raise ValueError("battery must be a percent from 0 to 100")
    tasks = [int(x) for x in re.findall(r"\d", fields.get("tasks", ""))]
    tasks = (tasks + [0] * len(TASKS))[:len(TASKS)]
    day = fields.get("date", "")
    if day and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
        raise ValueError("date must be YYYY-MM-DD")
    return {
        "sus": sus_score(answers),
        "veryDisappointed": miss == "very",
        "disappointment": miss,
        "batterySharePct": int(battery) if battery == int(battery) else battery,
        "susItems": answers,
        "tasks": {name: TASK_WORDS.get(v) for name, v in zip(TASKS, tasks)},
        "day": day or None,
        "form": FORM,
        "note": "SUS는 검증된 번역본이 아닌 번역·역번역본, 응답자 1명의 참고치",
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description="survey line -> status/survey.json")
    ap.add_argument("line", nargs="?")
    ap.add_argument("--file")
    ap.add_argument("--write")
    a = ap.parse_args(argv)
    if (a.line is None) == (a.file is None):
        ap.error("give the line or --file (one of them)")
    text = a.line
    if a.file:
        with open(a.file, encoding="utf-8") as f:
            text = next((l for l in f if l.strip().startswith("IEUM-SURVEY")), "")
    try:
        out = parse(text)
    except ValueError as e:
        ap.error(str(e))
    out["atMs"] = int(time.time() * 1000)
    body = json.dumps(out, ensure_ascii=False, sort_keys=True)
    if a.write:
        with open(a.write, "w", encoding="utf-8") as f:
            f.write(body + "\n")
    print(body)
    return out


if __name__ == "__main__":
    main()
