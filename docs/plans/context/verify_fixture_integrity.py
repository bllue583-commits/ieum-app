"""Check acceptance-fixture integrity only; does not run an IEUM engine or DB."""
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def verify(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    events = data["events"]
    cases = data["cases"]
    results = []

    def check(name, predicate):
        passed = bool(predicate())
        results.append({"check": name, "status": "PASS" if passed else "FAIL"})

    check("explicit_synthetic_and_nonproduct_scope", lambda:
          data["allDataSynthetic"] and data["kind"] == "synthetic_acceptance_oracles_not_product_results"
          and all(e["syntheticOnly"] and e["nativeAuthorization"] == "NOT_PROVIDED_BY_EVENT" for e in events.values()))
    check("exact_utf8_body_hashes", lambda: all(
          hashlib.sha256(e["body"].encode("utf-8")).hexdigest() == e["contentSha256"] for e in events.values()))
    check("quote_codepoint_ranges", lambda: all(
          c["offsetUnit"] == "unicode_codepoint"
          and 0 <= c["start"] < c["end"] <= len(e["body"])
          and e["body"][c["start"]:c["end"]] == c["quote"]
          and c["sourceVersion"] == e["sourceVersion"]
          and c["sourceSha256"] == e["contentSha256"]
          for e in events.values() for c in e.get("claims", [])))
    check("timestamps_have_explicit_timezones", lambda: all(
          datetime.fromisoformat(e[field]).tzinfo is not None
          for e in events.values() for field in ("occurredAt", "observedAt") if e[field] is not None))
    check("event_reference_completeness", lambda: all(
          ref in events or ref.startswith(("manual:", "control:", "fault:"))
          for case in cases for ref in case["input"]))
    check("causal_and_origin_references_resolve", lambda: all(
          e[field] in events and events[e[field]]["ownerId"] == e["ownerId"]
          for e in events.values() for field in ("causalRef", "originEvidenceRef") if field in e))
    check("thirty_unique_unrun_acceptance_cases", lambda:
          len(cases) == 30 and len({c["id"] for c in cases}) == 30
          and all(c["executionStatus"] == "NOT_RUN_PRODUCT" and c["expected"] for c in cases))
    check("same_hash_different_source_fixture", lambda:
          events["sms-1"]["contentSha256"] == events["mail-copy"]["contentSha256"]
          and events["sms-1"]["channel"] != events["mail-copy"]["channel"])
    identity = lambda e: tuple(e[k] for k in ("ownerId", "channel", "namespace", "externalId", "sourceVersion"))
    check("same_version_different_hash_fixture", lambda:
          identity(events["mail-1"]) == identity(events["mail-conflict"])
          and events["mail-1"]["contentSha256"] != events["mail-conflict"]["contentSha256"])
    check("edit_tombstone_source_identity_fixture", lambda:
          [events[k]["sourceVersion"] for k in ("mail-1", "mail-rev2", "mail-tombstone")] == [1, 2, 3]
          and len({events[k]["externalId"] for k in ("mail-1", "mail-rev2", "mail-tombstone")}) == 1
          and events["mail-tombstone"]["operation"] == "TOMBSTONE"
          and not events["mail-tombstone"]["claims"])
    check("cross_owner_same_content_fixture", lambda:
          events["call-1"]["ownerId"] != events["owner-b"]["ownerId"]
          and events["call-1"]["externalId"] == events["owner-b"]["externalId"]
          and events["call-1"]["contentSha256"] == events["owner-b"]["contentSha256"])
    check("chain_oracle_covers_real_state_changes", lambda:
          cases[0]["expected"]["snapshots"][0]["tasks"]["submit-quote-v1"]["due"] == "2026-10-05"
          and cases[0]["expected"]["snapshots"][1]["tasks"]["submit-quote-v2"]["due"] == "2026-10-07"
          and cases[0]["expected"]["snapshots"][2]["tasks"]["submit-quote-v2"]["due"] == "2026-10-09"
          and "registration-copy" in cases[0]["expected"]["snapshots"][2]["documents"])
    return {
        "scope": "FIXTURE_INTEGRITY_ONLY",
        "engineExecuted": False,
        "dbExecuted": False,
        "androidExecuted": False,
        "productCasesExecuted": 0,
        "productCasesNotRun": len(cases),
        "fixtureSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "passed": sum(x["status"] == "PASS" for x in results),
        "failed": sum(x["status"] == "FAIL" for x in results),
        "checks": results,
    }


if __name__ == "__main__":
    try:
        report = verify(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "synthetic_scenarios.json")
    except (ValueError, KeyError, TypeError, OSError) as exc:
        print(json.dumps({"scope": "FIXTURE_INTEGRITY_ONLY", "error": str(exc), "failed": 1}))
        raise SystemExit(1)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1 if report["failed"] else 0)
