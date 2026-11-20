"""v4.2 r23-4: the gradle failure report (gradle_log_tail.py) and the workflow steps that call it."""
import os, re, subprocess, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(__file__))
import gradle_log_tail as g  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORKFLOWS = os.path.join(ROOT, ".github", "workflows")


class GradleLogTailTest(unittest.TestCase):
    def log(self):
        lines = [f"line {i}" for i in range(200)]
        lines[150] = "* What went wrong:"
        lines[151] = "Execution failed for task ':app:compileReleaseKotlin'."
        lines[160] = "::add-mask::nothing"
        return "\n".join(lines)

    def test_the_block_and_the_tail(self):
        out = g.report(self.log())
        self.assertIn("--- gradle: What went wrong (lines 146-176) ---", out)
        self.assertIn("| Execution failed for task ':app:compileReleaseKotlin'.", out)
        self.assertIn("--- gradle: last 80 lines ---", out)
        self.assertEqual("| line 199", out[-1])
        self.assertEqual(31 + 80 + 2, len(out))

    def test_no_line_can_be_a_workflow_command(self):
        for line in g.report(self.log()):
            self.assertFalse(line.startswith("::"), line)

    def test_no_mark_and_short_log(self):
        out = g.report("a\nb")
        self.assertEqual(['--- gradle: no "What went wrong" in the log ---', "--- gradle: last 2 lines ---", "| a", "| b"], out)

    def test_tokens_are_masked_before_the_cut(self):
        pat = "github_pat_" + "A1b2" * 20
        self.assertEqual("| key *** end", g.safe("key " + pat + " end"))
        self.assertEqual("| ***", g.safe("ghp_" + "x" * 36))
        self.assertEqual("| a gho_abc123 b", g.safe("a gho_abc123 b"), "too short to be a token")
        self.assertEqual("| ***", g.safe("Ab1" * 14))
        # A token past the 500-character cut is masked too, and its first part never shows.
        line = "x " * 240 + "ghp_" + "Z9" * 18
        self.assertNotIn("Z9Z9", g.safe(line)); self.assertNotIn("ghp_", g.safe(line))
        self.assertTrue(all("ghp_" not in l for l in g.report("ghp_" + "Q" * 36 + "\n* What went wrong:\nx")))

    def test_names_paths_and_shas_stay(self):
        for keep in ("> Task :app:compileReleaseKotlin FAILED",
                     "R23ReviewTest > aDeadRunIsNeverMoreThanNineMinutesAndNoSignOfLifeIsNothing FAILED",
                     "kr.callbrief.pipeline.R16OneTest.theLedgerRollsDaysAndCountsRunsSkipsAndPieces",
                     "commit 3aa4b6627c84f35277ea9decab2e2eb0958b5c52",
                     "/home/runner/work/ieum-app/android/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar",
                     "a" * 60):
            self.assertEqual("| " + keep, g.safe(keep), keep)

    def test_overlapping_marks_join(self):
        lines = ["x"] * 50
        lines[10] = lines[20] = "* What went wrong:"
        self.assertEqual([(5, 46)], g.wrong_blocks(lines))

    def test_a_missing_log_is_no_error(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "gradle_log_tail.py"), os.path.join(d, "none.log")],
                               capture_output=True, text=True)
        self.assertEqual(0, r.returncode)
        self.assertIn("no log to show", r.stdout)


class WorkflowStepTest(unittest.TestCase):
    """release-apk and ci print the gradle log on failure, to the console only, and echo no secret."""

    def step(self, name, log):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as f:
            text = f.read()
        m = re.search(r"\n      - name: Gradle log on failure\n((?:        .*\n)+)", text)
        self.assertIsNotNone(m, name)
        body = m.group(1)
        self.assertIn("if: failure()", body)
        self.assertIn(f"python3 tools/release/gradle_log_tail.py evidence/{log}", body)
        self.assertNotIn("secrets.", body)
        self.assertNotIn("env:", body)
        self.assertNotRegex(body, r"echo .*\$")
        return text

    def test_release(self):
        text = self.step("release-apk.yml", "release-build.log")
        self.assertNotIn("upload-artifact", text, "the release log is never uploaded as a file")

    def test_ci(self):
        self.step("android-ci.yml", "ci-gradle.log")


if __name__ == "__main__":
    unittest.main()
