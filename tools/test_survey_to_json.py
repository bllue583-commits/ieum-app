"""v4.0 r20-6: the survey page's line becomes status/survey.json with the keys tools/inbox/score.py reads."""
import importlib.util, io, json, os, pathlib, re, tempfile, unittest
from contextlib import redirect_stderr, redirect_stdout

ROOT = pathlib.Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


st = load("survey_to_json_under_test", ROOT / "survey" / "survey_to_json.py")
score = load("score_for_survey", ROOT / "inbox" / "score.py")
LINE = "IEUM-SURVEY 1 | sus=5,1,5,1,5,1,5,1,5,1 | miss=very | battery=6 | tasks=1,1,2,0,3 | date=2026-11-20"


class SurveyTest(unittest.TestCase):
    def test_sus_scoring(self):
        self.assertEqual(st.sus_score([5, 1] * 5), 100.0)
        self.assertEqual(st.sus_score([1, 5] * 5), 0.0)
        self.assertEqual(st.sus_score([3] * 10), 50.0)
        self.assertEqual(st.sus_score([4, 2, 4, 1, 5, 2, 4, 1, 4, 2]), 82.5)
        with self.assertRaises(ValueError):
            st.sus_score([3] * 9)

    def test_line_to_survey_json_that_score_reads(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "survey.json")
            with redirect_stdout(io.StringIO()):
                st.main([LINE, "--write", path])
            with open(path, encoding="utf-8") as f:
                out = json.load(f)
        self.assertEqual(out["sus"], 100.0)
        self.assertIs(out["veryDisappointed"], True)
        self.assertEqual(out["batterySharePct"], 6)
        self.assertEqual(out["tasks"], {"findSummary": "easy", "checkAmount": "easy", "finishTask": "struggled", "findPastCall": None, "leaveMemo": "failed"})
        # score.py's item 10 reads sus and veryDisappointed, item 6 batterySharePct.
        with open(ROOT / "inbox" / "scorecard.json", encoding="utf-8") as f:
            card = json.load(f)
        items, window = card["items"], card["window"]
        r = score.item_use(items["10_use_and_satisfaction"], window, __import__("datetime").date(2026, 11, 20), {}, out)
        self.assertEqual((r["value"]["sus"], r["value"]["veryDisappointed"]), (100.0, True))
        b = score.item_battery(items["6_battery"], window, [], {}, out)
        self.assertEqual(b["value"]["sharePct"], 6)

    def test_bad_lines_are_refused(self):
        for bad in ["hello", LINE.replace("miss=very", "miss=maybe"), LINE.replace("battery=6", "battery=120"),
                    LINE.replace("sus=5,1,5,1,5,1,5,1,5,1", "sus=5,1,5"), LINE.replace("IEUM-SURVEY 1", "IEUM-SURVEY 2")]:
            with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
                st.main([bad])

    def test_the_page_and_the_tool_agree(self):
        page = (ROOT.parent / "docs" / "survey" / "ieum-survey.html").read_text(encoding="utf-8")
        items = re.search(r"const SUS = \[(.*?)\];", page, re.S).group(1)
        self.assertEqual(10, len(re.findall(r'^\s*"', items, re.M)))
        self.assertIn("IEUM-SURVEY 1 | sus=", page)
        self.assertIn('name="miss" value="very"', page)
        self.assertEqual(5, len(re.findall(r'^\s*"', re.search(r"const TASKS = \[(.*?)\];", page, re.S).group(1), re.M)))
        self.assertNotIn("fetch(", page)  # the page sends nothing


if __name__ == "__main__":
    unittest.main()
