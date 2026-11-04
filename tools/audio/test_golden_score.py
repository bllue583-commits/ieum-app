"""python3 tools/audio/test_golden_score.py — the golden-set scorer (normalization, CER, entities, logcat fallback)."""
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import golden_score as g  # noqa: E402

GOLDEN = os.path.join(HERE, "../../android/app/src/androidTest/assets/golden/golden.json")


class NormalizeTest(unittest.TestCase):
    def test_numbers_read_out(self):
        self.assertEqual(g.normalize("20톤"), g.normalize("이십 톤"))
        self.assertEqual(g.normalize("11월 18일"), g.normalize("십일 월 십팔 일"))
        self.assertEqual(g.normalize("87만 원"), g.normalize("팔십칠만 원"))
        self.assertEqual(g.normalize("2,405,000원"), g.normalize("이백사십만 오천 원"))
        self.assertEqual(g.normalize("240만 5천 원"), g.normalize("이백사십만 오천 원"))

    def test_native_counters(self):
        self.assertEqual(g.normalize("오전 7시"), g.normalize("오전 일곱 시"))
        self.assertEqual(g.normalize("10개"), g.normalize("열 개"))
        self.assertEqual(g.normalize("1대"), g.normalize("한 대"))
        self.assertEqual(g.normalize("3개월"), "삼개월")

    def test_letters_and_punctuation(self):
        self.assertEqual(g.normalize("D13 철근."), g.normalize("디 십삼 철근"))
        self.assertEqual(g.normalize("100A"), g.normalize("백 에이"))
        self.assertEqual(g.normalize("120㎥"), g.normalize("백이십 루베"))


class ScoreTest(unittest.TestCase):
    def setUp(self):
        self.golden = json.load(open(GOLDEN, encoding="utf-8"))

    def test_reference_scores_perfect(self):
        for call in self.golden["calls"]:
            s = g.score_call(call, g.reference(call))
            self.assertEqual(0, s["cer"], call["id"])
            missed = [e["text"] for e in s["entities"] if not e["found"]]
            self.assertEqual([], missed, call["id"])

    def test_written_form_scores_like_spoken(self):
        call = self.golden["calls"][0]
        text = g.reference(call).replace("디 십삼", "D13").replace("이십 톤", "20톤").replace("십일 월 십팔 일", "11월 18일")
        self.assertEqual(0, g.score_call(call, text)["cer"])

    def test_empty_transcript(self):
        s = g.score_call(self.golden["calls"][1], "")
        self.assertEqual(1, s["cer"])
        self.assertEqual(0, s["entityAccuracy"])

    def test_logcat_fallback_and_table(self):
        call = self.golden["calls"][2]
        lines = [
            '11-04 I IEUM_RESULT: golden_text ' + json.dumps({"variant": "base", "id": call["id"], "text": "네, 실장님.", "seconds": 1}, ensure_ascii=False),
            '11-04 I IEUM_RESULT: golden_variant {"variant":"base","floor":null,"prompt":false,"available":true,"seconds":1}',
            '11-04 I IEUM_RESULT: golden_text ' + json.dumps({"variant": "floor0025", "id": call["id"], "text": g.reference(call), "seconds": 1}, ensure_ascii=False),
            '11-04 I IEUM_RESULT: golden_variant {"variant":"floor0025","floor":0.0025,"prompt":false,"available":true,"seconds":1}',
            '11-04 I IEUM_RESULT: golden_variant {"variant":"prompt","floor":null,"prompt":true,"available":false,"why":"no switch"}',
        ]
        with tempfile.TemporaryDirectory() as d:
            src, out = os.path.join(d, "results.txt"), os.path.join(d, "score.json")
            open(src, "w", encoding="utf-8").write("\n".join(lines))
            self.assertEqual(0, g.main([GOLDEN, src, "--out", out]))
            r = json.load(open(out, encoding="utf-8"))
        names = [v["variant"] for v in r["variants"]]
        self.assertEqual(["base", "floor0025", "prompt"], names)
        self.assertLess(r["variants"][1]["cer"], r["variants"][0]["cer"])
        self.assertNotIn("calls", r["variants"][2])
        self.assertTrue(any("floor0025" in h and "충족" in h for h in r["hints"]))


if __name__ == "__main__":
    unittest.main()
