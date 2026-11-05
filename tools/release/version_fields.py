#!/usr/bin/env python3
"""The fields release-apk adds to app/version.json from v3.1 on (DESIGN_V4 2.2-2), so the watchdog can compare without
reading the app repository:

  promptVersion  the hourly analysis rules' version, ClaudeCallAnalyzer.PROMPT_VERSION (as text, like last-run.json)
  tidyVersion    the morning tidy prompt's version, the "지시문 버전 n" line at the top of docs/routines/tidy.md
  certSha256     the new APK's signing certificate (SHA-256, lower-case hex without colons), so the next release can
                 compare even when the previous APK cannot be downloaded

Usage:
  python3 tools/release/version_fields.py [--root .] [--apk APK | --cert HEX] [--merge version.json]
Prints the three fields as one JSON object; with --merge it also writes them into that version.json (other keys kept).
The certificate comes from --cert, or from --apk through tools/rotate_signing_key.sh fp (needs APKSIGNER); when it cannot be
read it is left out with a warning (the release still goes out). An unreadable prompt or tidy version is an error (exit 1).
"""
import argparse, json, os, re, subprocess, sys

ANALYZER = "android/app/src/main/java/kr/callbrief/pipeline/ClaudeCallAnalyzer.kt"
TIDY = "docs/routines/tidy.md"
PROMPT_RE = re.compile(r"\bconst\s+val\s+PROMPT_VERSION\s*=\s*(\d+)\b")
TIDY_RE = re.compile(r"^지시문 버전\s*(\d+)\s*$")
HEX_RE = re.compile(r"^[0-9a-f]{64}$")


def prompt_version(root):
    with open(os.path.join(root, ANALYZER), encoding="utf-8") as f:
        m = PROMPT_RE.search(f.read())
    if not m:
        raise ValueError(f"PROMPT_VERSION not found in {ANALYZER}")
    return m.group(1)


def tidy_version(root):
    """The "지시문 버전 n" line in the head of tidy.md (before the "---" that starts the text pasted into the routine)."""
    with open(os.path.join(root, TIDY), encoding="utf-8") as f:
        for line in f:
            if line.strip() == "---":
                break
            m = TIDY_RE.match(line.strip())
            if m:
                return m.group(1)
    raise ValueError(f'"지시문 버전 n" not found in the head of {TIDY}')


def clean_cert(raw):
    """"AB:CD:..." or "abcd..." to 64 lower-case hex characters, or "" when it is not a SHA-256 digest."""
    v = (raw or "").strip().lower().replace(":", "").replace(" ", "")
    return v if HEX_RE.match(v) else ""


def cert_of(root, apk):
    try:
        r = subprocess.run(["bash", os.path.join(root, "tools/rotate_signing_key.sh"), "fp", apk], capture_output=True, text=True, timeout=120)
        return clean_cert(r.stdout.strip().splitlines()[-1] if r.stdout.strip() else "")
    except (subprocess.SubprocessError, OSError):
        return ""


def fields(root, cert=""):
    out = {"promptVersion": prompt_version(root), "tidyVersion": tidy_version(root)}
    c = clean_cert(cert)
    if c:
        out["certSha256"] = c
    return out


def merge(path, extra):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} is not a JSON object")
    data.update(extra)
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False))
    return data


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--apk", default="")
    ap.add_argument("--cert", default="")
    ap.add_argument("--merge", default="")
    a = ap.parse_args(argv)
    cert = a.cert or (cert_of(a.root, a.apk) if a.apk else "")
    try:
        out = fields(a.root, cert)
    except (OSError, ValueError) as e:
        print(f"version_fields: {e}", file=sys.stderr)
        return 1
    if (a.cert or a.apk) and "certSha256" not in out:
        print("::warning title=release::signing certificate not read: certSha256 left out of version.json", file=sys.stderr)
    if a.merge:
        merge(a.merge, out)
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
