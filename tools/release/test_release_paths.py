"""v4.0 r21-4 (DESIGN_V8 6 v4.1): release-apk starts only for what reaches the phone or the mailbox.

Reads the push paths of .github/workflows/release-apk.yml and matches files against them the way GitHub's path filter does
(`*` within one path part, `**` across parts, a pattern without a slash only at the top). Standard library only.
"""
import os, re, unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORKFLOW = os.path.join(ROOT, ".github", "workflows", "release-apk.yml")
CI = os.path.join(ROOT, ".github", "workflows", "android-ci.yml")


def push_paths(text):
    """The list under on.push.paths (a flow list, possibly over several lines)."""
    at = text.find("\n  push:")
    start = text.find("paths: [", at) if at >= 0 else -1
    end = text.find("]", start)
    if at < 0 or start < 0 or end < 0:
        raise AssertionError("no on.push.paths")
    return re.findall(r"'([^']+)'", text[start:end])


def glob_re(pattern):
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += r"(?:.*/)?"; i += 3
        elif pattern.startswith("**", i):
            out += r".*"; i += 2
        elif pattern[i] == "*":
            out += r"[^/]*"; i += 1
        elif pattern[i] == "?":
            out += r"[^/]"; i += 1
        else:
            out += re.escape(pattern[i]); i += 1
    return re.compile("^" + out + "$")


def triggers(paths, changed):
    pats = [glob_re(p) for p in paths]
    return any(p.match(f) for f in changed for p in pats)


class ReleasePathsTest(unittest.TestCase):
    def setUp(self):
        with open(WORKFLOW, encoding="utf-8") as f:
            self.paths = push_paths(f.read())

    def test_tools_alone_do_not_release(self):
        self.assertNotIn("tools/**", self.paths)
        for f in ("tools/inbox/score.py", "tools/inbox/scorecard.json", "tools/inbox/test_watchdog.py", "tools/sim/queue_sim.py",
                  "tools/test_report_check.py", "tools/release/version_fields.py", "tools/eval/x.py", "tools/check_ui_rules.py",
                  "docs/DESIGN.md", "docs/eval/EVAL_1114.md", "docs/orchestration/briefs/r21-v40.md", ".github/workflows/android-ci.yml"):
            self.assertFalse(triggers(self.paths, [f]), f)

    def test_what_reaches_the_phone_or_the_mailbox_releases(self):
        # The four files the workflow copies to the mailbox, the app, its build inputs and the version files.
        with open(WORKFLOW, encoding="utf-8") as f:
            copied = re.findall(r'"([^":]+):[^"]+"', re.search(r"for pair in (.*?); do", f.read()).group(1))
        self.assertEqual({"docs/routines/hourly.md", "docs/routines/tidy.md", "tools/inbox/watchdog.py", "tools/report_check.py"}, set(copied))
        for f in copied + ["android/app/src/main/java/kr/callbrief/pipeline/CallUi.kt", "android/app/build.gradle.kts", "docs/routines/self-report.md",
                           "docs/VERSION", "docs/UPDATE_NOTE.txt", "tools/build_android_whisper.py", "tools/fetch_sherpa_onnx.py",
                           "run_gradle.py", "third_party/whisper.cpp-1.7.4/src/whisper.cpp", ".github/workflows/release-apk.yml",
                           "tools/audio/public-ko.wav"]:
            self.assertTrue(triggers(self.paths, [f]), f)
        # A push with a tool and an app file still releases.
        self.assertTrue(triggers(self.paths, ["tools/inbox/score.py", "android/app/src/main/AndroidManifest.xml"]))

    def test_every_release_path_runs_ci(self):
        # Review M1: release-apk waits for ci on the same commit; a path that releases but does not run ci waits 40 minutes
        # and fails. Each release pattern, and samples of what it matches, must start ci too.
        with open(CI, encoding="utf-8") as f:
            ci = push_paths(f.read())
        samples = {"android/**": "android/x/y.kt", "third_party/**": "third_party/a/b.c", "docs/routines/**": "docs/routines/tidy.md",
                   "tools/audio/**": "tools/audio/public-ko.wav"}
        for p in self.paths:
            f = samples.get(p, p)
            self.assertTrue(triggers(ci, [f]), f"{p} releases but does not run ci")

    def test_the_matcher_follows_githubs_globs(self):
        self.assertTrue(triggers(["android/**"], ["android/a/b.kt"]))
        self.assertFalse(triggers(["docs/VERSION"], ["docs/VERSION.bak"]))
        self.assertFalse(triggers(["tools/report_check.py"], ["tools/test_report_check.py"]))
        self.assertFalse(triggers(["docs/*"], ["docs/routines/tidy.md"]))


if __name__ == "__main__":
    unittest.main()
