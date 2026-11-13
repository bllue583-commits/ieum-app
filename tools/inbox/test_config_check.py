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
        self.assertEqual(cc.summary(cfg), "bench=on samples=on")

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
        self.assertEqual(cc.summary({}), "bench=off samples=off")


if __name__ == "__main__":
    unittest.main()
