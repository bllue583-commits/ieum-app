#!/usr/bin/env python3
"""CI check of the screen rules (design H5). Exit code 1 when a rule is broken.

Looks at app sources under android/app/src/main (Kotlin/Java and res/values*/strings.xml). Comments and log lines are ignored.
Rules:
  1. No middle dot (U+00B7, U+30FB) or ellipsis (U+2026, "...") inside a Korean on-screen string.
  2. No red-family color constant (hex #RRGGBB / 0xFFRRGGBB), and never #D63B3B / #F04452 / #E5xxxx.
  3. No ellipsize, no WARN_SOFT, no "기한" word on screen.
  4. No English status value (QUEUED, FAILED, ...) inside a Korean on-screen string.
Usage: python3 tools/check_ui_rules.py [source_root ...]
"""
import os, re, sys

HANGUL = re.compile(r"[가-힣]")
STATE_WORDS = ("QUEUED", "FAILED", "FAILURE", "READY", "PENDING", "RUNNING", "SKIPPED", "TRANSCRIBED", "DOWNLOADING", "NOT_DOWNLOADED",
               "UNSUPPORTED", "ARCHIVED", "ANALYZED", "ERROR", "TIMEOUT", "STOPPED", "MORE", "FIRM", "TENTATIVE")
STATE_RE = re.compile(r"(?<![A-Za-z_])(" + "|".join(STATE_WORDS) + r")(?![A-Za-z])")
LOG_LINE = re.compile(r"\b(Log\.[a-z]\(|println\(|Timber\.|logger\.|check\(|require\(|error\(|IllegalStateException\(|IllegalArgumentException\(|Exception\()")
BANNED_WORDS = ("ellipsize", "WARN_SOFT")
# Text sent to the model, never shown on a screen.
PROMPT_FILES = ("ClaudeCallAnalyzer.kt",)


def is_red(hexstr):
    h = hexstr[-6:]
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return h.upper() in ("D63B3B", "F04452") or h[0:2].upper() == "E5" and r - max(g, b) >= 0x60 or (r >= 0xC0 and r - max(g, b) >= 0x60)


def kotlin_strings(text):
    """Yield (line, kind, content) for code, comment-free: kind is 'str' for string literal content or 'code' for the remaining line text."""
    i, n, line = 0, len(text), 1
    code_line, out = [], []
    def flush(ln):
        out.append((ln, "code", "".join(code_line))); code_line.clear()
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            while i < n and text[i] != "\n": i += 1
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); j = n if j < 0 else j + 2
            line += text.count("\n", i, j)
            i = j; continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j
            out.append((line, "str", text[i + 3:j])); line += text.count("\n", i, j + 3); i = j + 3; code_line.append('""'); continue
        if c == '"':
            j = i + 1; depth = 0
            while j < n:
                if text[j] == "\\": j += 2; continue
                if text.startswith("${", j): depth += 1; j += 2; continue
                if depth and text[j] == "}": depth -= 1; j += 1; continue
                if text[j] == '"' and not depth: break
                if text[j] == "\n" and not depth: break
                j += 1
            out.append((line, "str", text[i + 1:j])); code_line.append('""'); i = j + 1; continue
        if c == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append((line, "str", text[i + 1:j])) if j - i > 2 else None
            code_line.append("''"); i = j + 1; continue
        if c == "\n":
            flush(line); line += 1
        else:
            code_line.append(c)
        i += 1
    flush(line)
    return out


def check_kotlin(path, text):
    v = []
    prompt_file = os.path.basename(path) in PROMPT_FILES
    lines = text.split("\n")
    pattern_chars = ("·", "･", "…")
    for ln, kind, content in kotlin_strings(text):
        src = lines[ln - 1] if ln - 1 < len(lines) else ""
        if kind == "code":
            for w in BANNED_WORDS:
                if w in content: v.append((path, ln, f"banned word {w}"))
            continue
        if LOG_LINE.search(src) or prompt_file:
            continue
        if re.fullmatch(r"#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?", content) and is_red(content[1:]):
            v.append((path, ln, f"red color {content}"))
        if content == "기한" or "기한" in content and HANGUL.search(content) and re.search(r"(^|[^가-힣])기한([^가-힣]|$)", content):
            v.append((path, ln, "word 기한 on screen"))
        if HANGUL.search(content):
            if any(ch in content for ch in pattern_chars) or "..." in content:
                v.append((path, ln, f"middle dot or ellipsis in '{content[:40]}'"))
            m = STATE_RE.search(re.sub(r"\$\{[^}]*\}|\$[A-Za-z_]\w*", "", content))
            if m: v.append((path, ln, f"English status {m.group(1)} in '{content[:40]}'"))
    for m in re.finditer(r"(?<![0-9A-Za-z])0x(?:FF)?([0-9A-Fa-f]{6})\b", "\n".join(c for _, k, c in kotlin_strings(text) if k == "code")):
        if is_red(m.group(1)): v.append((path, 0, f"red color 0x{m.group(1)}"))
    return v


def check_xml(path, text):
    v = []
    text_nc = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    for ln, line in enumerate(text_nc.split("\n"), 1):
        for m in re.finditer(r"#(?:[0-9A-Fa-f]{2})?([0-9A-Fa-f]{6})\b", line):
            if is_red(m.group(1)): v.append((path, ln, f"red color #{m.group(1)}"))
        if "ellipsize" in line: v.append((path, ln, "banned word ellipsize"))
        m = re.search(r">([^<]*[가-힣][^<]*)<", line)
        if m:
            s = m.group(1)
            if any(ch in s for ch in ("·", "･", "…")) or "..." in s: v.append((path, ln, f"middle dot or ellipsis in '{s[:40]}'"))
            if re.search(r"(^|[^가-힣])기한([^가-힣]|$)", s) and s.strip() == "기한": v.append((path, ln, "word 기한 on screen"))
            sm = STATE_RE.search(s)
            if sm: v.append((path, ln, f"English status {sm.group(1)} in '{s[:40]}'"))
    return v


def scan(roots):
    found = []
    for root in roots:
        for d, dirs, files in os.walk(root):
            dirs[:] = [x for x in dirs if x not in ("build", ".gradle")]
            for f in files:
                p = os.path.join(d, f)
                if f.endswith((".kt", ".java")):
                    found += check_kotlin(p, open(p, encoding="utf-8").read())
                elif f.endswith(".xml") and "/res/values" in p.replace("\\", "/"):
                    found += check_xml(p, open(p, encoding="utf-8").read())
    return found


def main(argv):
    roots = argv or ["android/app/src/main"]
    found = scan(roots)
    for p, ln, msg in found:
        print(f"{p}:{ln}: {msg}")
    print(f"ui rules: {len(found)} violation(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
