import json
import os
import unittest

import config_check as cc

HERE = os.path.dirname(os.path.abspath(__file__))


class ConfigCheckTest(unittest.TestCase):
    def test_the_file_in_the_repository_is_valid(self):
        with open(os.path.join(HERE, "..", "..", "ops", "inbox-config.json"), encoding="utf-8") as f:
            self.assertEqual(cc.problems(json.load(f)), [])

    def test_the_documented_example_is_valid_and_on(self):
        cfg = {"bench": {"on": True, "candidates": ["b", "c"], "calls": 20, "run": 1},
               "samples": {"on": True, "calls": 12, "maxMinutes": 40}}
        self.assertEqual(cc.problems(cfg), [])
        self.assertEqual(cc.summary(cfg), "bench=on samples=on budgetMin=20 webMax=2000")

    def test_a_string_true_is_refused_because_the_phone_reads_it_as_off(self):
        self.assertTrue(cc.problems({"bench": {"on": "true"}}))
        self.assertTrue(cc.problems({"samples": {"on": 1}}))

    def test_typos_and_out_of_range_values_are_refused(self):
        self.assertTrue(cc.problems({"bnch": {"on": True}}))
        self.assertTrue(cc.problems({"bench": {"on": True, "candidate": ["b"]}}))
        self.assertTrue(cc.problems({"bench": {"on": True, "candidates": ["d"]}}))
        self.assertTrue(cc.problems({"bench": {"on": True, "calls": 41}}))
        self.assertTrue(cc.problems({"bench": {"on": True, "threads": {"b": 9}}}))
        self.assertTrue(cc.problems({"samples": {"on": True, "calls": 31}}))
        self.assertTrue(cc.problems({"samples": {"on": True, "maxMinutes": 121}}))
        self.assertTrue(cc.problems([]))

    def test_an_empty_object_is_valid_and_off(self):
        self.assertEqual(cc.problems({}), [])
        self.assertEqual(cc.summary({}), "bench=off samples=off budgetMin=20 webMax=2000")

    def test_the_battery_budget(self):
        # v4.3 r24-3: a whole number 10..60; the phone takes anything else as 20, so the copy step refuses it.
        self.assertEqual(cc.problems({"power": {"budgetMin": 40}}), [])
        self.assertEqual(cc.summary({"power": {"budgetMin": 40}}), "bench=off samples=off budgetMin=40 webMax=2000")
        self.assertEqual(cc.problems({"power": {}}), [])
        for bad in (5, 100, "40", 40.5, True, None):
            self.assertTrue(cc.problems({"power": {"budgetMin": bad}}), bad)
        self.assertTrue(cc.problems({"power": {"budget": 40}}))
        self.assertTrue(cc.problems({"power": 40}))

    def test_the_web_view_cap(self):
        # v4.4 r25 review: a whole number 1000..6000; the phone takes anything else as 2000, so the copy step refuses it.
        self.assertEqual(cc.problems({"kakao": {"webMax": 3500}}), [])
        self.assertEqual(cc.summary({"kakao": {"webMax": 3500}}), "bench=off samples=off budgetMin=20 webMax=3500")
        for bad in (999, 6001, "3500", 3500.5, True, None):
            self.assertTrue(cc.problems({"kakao": {"webMax": bad}}), bad)
        self.assertTrue(cc.problems({"kakao": {"max": 3000}}))
        self.assertTrue(cc.problems({"kakao": 3000}))


if __name__ == "__main__":
    unittest.main()
