"""v4.0 r20-3 (DESIGN_V7 7, SCORECARD item 4): tools/eval/promise_eval.py scores the inbox's check items against a gold set and
writes status/gold-commit.json in the keys tools/inbox/score.py's item_gold reads. Made-up calls only."""
import importlib.util, io, json, os, pathlib, sys, tempfile, unittest
from contextlib import redirect_stdout

ROOT = pathlib.Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


pe = load('promise_eval_under_test', ROOT / 'eval' / 'promise_eval.py')
score = load('score_under_test', ROOT / 'inbox' / 'score.py')

GOLD = [
    {"call_id": "r_a", "promises": [
        {"who": "me", "what": "계약금 30만원 송금", "evidence": "계약금 30만원은 금요일까지 보내드릴게요", "numbers": ["30만원"], "due": "2026-11-20"},
        {"who": "them", "what": "카탈로그 보내기", "evidence": "카탈로그 보내드릴게요", "numbers": []}]},
    {"call_id": "r_b", "promises": [
        {"who": "them", "what": "샘플 5개 발송", "evidence": "샘플 다섯 개 보낼게요", "numbers": ["5개"]}]},
    {"call_id": "r_c", "promises": [
        {"who": "me", "what": "3층 도면 회신", "evidence": "3층 도면은 제가 답 드릴게요", "numbers": ["3층"]}]},
]


def write(folder, name, obj):
    with open(os.path.join(folder, name), 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False)


class PromiseEvalTest(unittest.TestCase):
    def test_matching_rules(self):
        pred = {"r_a": [{"who": "me", "what": "계약금 삼십만 원 송금하기", "numbers": ["삼십만 원"], "due": "2026-11-20"},
                        {"who": "me", "what": "카탈로그 보내기", "numbers": []}],  # wrong owner: no match
                "r_b": [{"who": "them", "what": "샘플 50개 발송", "numbers": ["50개"]}],  # content matches, number differs
                "r_x": [{"who": "me", "what": "다른 통화의 일", "numbers": []}]}      # not in the gold set: not scored
        out = pe.evaluate({c["call_id"]: c["promises"] for c in GOLD}, pred)
        self.assertEqual((out["tp"], out["fp"], out["fn"], out["numberMismatch"]), (1, 2, 2, 1))
        self.assertEqual(out["commitCalls"], 2)          # r_c has no result at all: left out, counted as missing
        self.assertEqual(out["missingCalls"], 1)
        self.assertEqual(out["commitNumbers"], 2)        # 30만원 and 5개
        self.assertAlmostEqual(out["numberExact"], 1 / 3, places=3)  # {300000원} both; {5개} vs {50개} neither
        self.assertEqual(out["commitPrecision"], round(1 / 3, 4))
        self.assertEqual(out["commitRecall"], round(1 / 3, 4))
        self.assertEqual(out["dueExact"], 1.0)

    def test_numbers_in_a_title(self):
        self.assertEqual(pe.numbers_in("철근 30만원 입금, 3층 2개, 금요일까지"), ["30만원", "3층", "2개"])
        self.assertEqual(pe.nums({"numbers": ["삼십만 원", "30만원"]}), {"300000원"})
        self.assertEqual(pe.numbers_in("이사 갈 때 사과"), [])

    def test_results_folder_to_gold_commit_that_score_reads(self):
        with tempfile.TemporaryDirectory() as d:
            res = os.path.join(d, "results"); os.mkdir(res)
            # A single result and a batch, the hourly task's two forms; done_suggested is not a new promise.
            write(res, "one.json", {"id": "r_a", "analysis": {"summary": "s", "tasks": [
                {"op": "create", "title": "계약금 30만원 송금", "owner": "me", "quote": "q", "due": "2026-11-20"},
                {"op": "update", "taskId": "w1", "title": "카탈로그 보내기", "owner": "them", "quote": "q"},
                {"op": "done_suggested", "taskId": "w2", "title": "", "owner": "me"}]}})
            write(res, "batch.json", {"id": "b_1", "results": [
                {"id": "r_b", "analysis": {"tasks": [{"op": "create", "title": "샘플 5개 발송", "owner": "them"}]}},
                {"id": "r_c", "analysis": {"tasks": [{"op": "create", "title": "3층 도면 회신", "owner": "me"}]}}]})
            gold = os.path.join(d, "gold.json")
            with open(gold, "w", encoding="utf-8") as f:
                json.dump(GOLD, f, ensure_ascii=False)
            out_path = os.path.join(d, "gold-commit.json")
            with redirect_stdout(io.StringIO()) as printed:
                pe.main([gold, "--results", res, "--write", out_path])
            self.assertIn("precision=1.000", printed.getvalue())
            with open(out_path, encoding="utf-8") as f:
                written = json.load(f)
        for k in ("commitRecall", "commitPrecision", "numberExact", "commitCalls", "commitNumbers"):
            self.assertIn(k, written)
        self.assertEqual((written["commitRecall"], written["commitPrecision"], written["numberExact"]), (1.0, 1.0, 1.0))
        self.assertEqual((written["commitCalls"], written["commitNumbers"]), (3, 3))
        # score.py's item 4 reads exactly these keys: too few calls fails on the count, not on a missing key.
        with open(ROOT / "inbox" / "scorecard.json", encoding="utf-8") as f:
            c4 = json.load(f)["items"]["4_commitments"]
        keys = {"commitRecall": (">=", c4["min_recall"]), "commitPrecision": (">=", c4["min_precision"]), "numberExact": (">=", c4["min_number_exact"])}
        mins = {"commitCalls": (">=", c4["min_calls"]), "commitNumbers": (">=", c4["min_numbers"])}
        r = score.item_gold(c4, written, keys, mins)
        self.assertFalse(r["pass"]); self.assertIn("commitCalls 3", r["reason"])
        r = score.item_gold(c4, {**written, "commitCalls": 30, "commitNumbers": 40}, keys, mins)
        self.assertTrue(r["pass"], r)

    def test_needs_one_prediction_source(self):
        with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
            import contextlib
            with contextlib.redirect_stderr(io.StringIO()):
                pe.main(["gold.json"])


if __name__ == "__main__":
    unittest.main()
