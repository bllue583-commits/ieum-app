"""Validate handoff fixtures only. Does NOT import or test the IEUM Android product.

Run: python3 verify_contract.py
Outputs: contract-validation.json and contract-validation.log in this directory.
Python stdlib only; no network, account access, database, message send or scheduler.
KST here is the fixed +09:00 offset for these modern synthetic fixture dates.
Android implementation must use ZoneId.of("Asia/Seoul") and its existing clock port.
"""
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
import io
import json
import sys
import unittest

ROOT = Path(__file__).resolve().parent
KST = timezone(timedelta(hours=9), "KST")
UTC = timezone.utc


def read(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def instant(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Instant requires explicit timezone")
    return parsed


def iso(value):
    return value.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def reference_window(report_day):
    """Calendar arithmetic oracle, NOT a production scheduling implementation."""
    day = date.fromisoformat(report_day)
    midnight = datetime.combine(day, time(), KST)
    return {
        "dailyStart": iso(midnight - timedelta(days=1)),
        "weekStart": iso(midnight - timedelta(days=7)),
        "end": iso(midnight),
        "due": iso(midnight + timedelta(hours=6)),
        "primary": iso(midnight - timedelta(hours=1)),
    }


def reference_latest_due(value):
    kst = instant(value).astimezone(KST)
    day = kst.date()
    if kst.hour < 6:
        day -= timedelta(days=1)
    return day.isoformat()


class ContractFixtures(unittest.TestCase):
    def test_product_cases_are_unexecuted(self):
        cases = read("acceptance-cases.json")
        self.assertEqual(40, len(cases))
        self.assertEqual(len(cases), len({c["id"] for c in cases}))
        self.assertTrue(all(c["status"] == "NOT_RUN" and c["evidence"] is None for c in cases))

    def test_scope_is_explicit(self):
        p = read("provenance.json")
        self.assertFalse(p["sourcePresenceVerified"])
        self.assertFalse(p["sourceHashVerified"])
        self.assertEqual(0, p["changes"]["productFiles"])
        self.assertEqual(0, p["changes"]["integrationPatches"])
        self.assertEqual(0, p["externalActions"]["realSends"])
        self.assertEqual("PROPOSED_NOT_PRODUCT_IMPLEMENTED", read("reporting-contract.json")["status"])

    def test_naive_instant_rejected(self):
        with self.assertRaises(ValueError):
            instant("2026-10-26T06:00:00")


def window_test(vector):
    def test(self):
        actual = reference_window(vector["reportDay"])
        self.assertEqual({k: vector[k] for k in actual}, actual)
        self.assertEqual(timedelta(days=1), instant(actual["end"]) - instant(actual["dailyStart"]))
        self.assertEqual(timedelta(days=7), instant(actual["end"]) - instant(actual["weekStart"]))
    return test


def due_test(vector):
    def test(self):
        self.assertEqual(vector["expected"], reference_latest_due(vector["instant"]))
    return test


def membership_test(vector):
    def test(self):
        start = instant("2026-10-24T15:00:00Z")
        end = instant("2026-10-25T15:00:00Z")
        self.assertEqual(vector["included"], start <= instant(vector["at"]) < end)
    return test


if __name__ == "__main__":
    vectors = read("temporal-vectors.json")
    for group, factory in (("windows", window_test), ("due", due_test), ("membership", membership_test)):
        for vector in vectors[group]:
            setattr(ContractFixtures, "test_" + vector["id"], factory(vector))
    stream = io.StringIO()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ContractFixtures)
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    report = {
        "scope": "INDEPENDENT_DESIGN_FIXTURES_ONLY_NOT_ANDROID_PRODUCT_TESTS",
        "executedAtUtc": datetime.now(UTC).isoformat(),
        "python": sys.version,
        "testsRun": result.testsRun,
        "passed": result.testsRun - len(result.failures) - len(result.errors) - len(result.skipped),
        "failures": [{"id": str(t), "traceback": trace} for t, trace in result.failures],
        "errors": [{"id": str(t), "traceback": trace} for t, trace in result.errors],
        "skipped": [{"id": str(t), "reason": reason} for t, reason in result.skipped],
        "productAcceptance": {"passed": 0, "failed": 0, "notRun": 40},
        "realDeviceExecuted": False,
        "liveServicesAccessed": False,
    }
    (ROOT / "contract-validation.log").write_text(stream.getvalue(), encoding="utf-8")
    (ROOT / "contract-validation.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(stream.getvalue())
    print(json.dumps(report, ensure_ascii=False))
    sys.exit(0 if result.wasSuccessful() else 1)
