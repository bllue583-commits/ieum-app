"""v4.2 r23-4: when a gradle step fails in ci or release-apk, print why to the job's console.

Prints each "* What went wrong:" block of the gradle log with a few lines before and after, then the log's last 80 lines.
Only to the console (GitHub masks the secrets it knows there; an uploaded file it does not mask), never into a file. Every
line goes out behind "| ", so a line of the log can never be read as a workflow command ("::add-mask::", "::error::" ...).
Standard library only. Usage: python3 tools/release/gradle_log_tail.py evidence/release-build.log
"""
import sys

MARK = "* What went wrong:"
BEFORE, AFTER, TAIL = 5, 25, 80


def wrong_blocks(lines, before=BEFORE, after=AFTER):
    """(start, end) line ranges around each What-went-wrong mark, overlapping ones joined."""
    out = []
    for i, line in enumerate(lines):
        if line.strip() == MARK:
            a, b = max(0, i - before), min(len(lines), i + after + 1)
            if out and a <= out[-1][1]:
                out[-1] = (out[-1][0], max(out[-1][1], b))
            else:
                out.append((a, b))
    return out


def safe(line):
    return "| " + line.rstrip("\r\n")[:500]


def report(text, tail=TAIL):
    lines = text.splitlines()
    out = []
    blocks = wrong_blocks(lines)
    for a, b in blocks:
        out.append(f"--- gradle: What went wrong (lines {a + 1}-{b}) ---")
        out += [safe(l) for l in lines[a:b]]
    if not blocks:
        out.append("--- gradle: no \"What went wrong\" in the log ---")
    start = max(0, len(lines) - tail)
    out.append(f"--- gradle: last {len(lines) - start} lines ---")
    out += [safe(l) for l in lines[start:]]
    return out


def main(argv):
    if len(argv) != 2:
        print("usage: gradle_log_tail.py <gradle log>")
        return 2
    try:
        with open(argv[1], encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError as e:
        print(f"--- gradle: no log to show ({type(e).__name__}) ---")
        return 0
    for line in report(text):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
