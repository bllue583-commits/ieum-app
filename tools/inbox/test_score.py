"""채점기 시험: 합성 우편함 저장소를 만들어 '전부 통과'와 '기준선 같은 실패'를 확인한다."""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score  # noqa: E402

KST = score.KST
ASOF = dt.date(2026, 12, 21)  # 월요일


def ms(day: dt.date, hour: int, minute: int = 0) -> int:
    return int(dt.datetime(day.year, day.month, day.day, hour, minute, tzinfo=KST).timestamp() * 1000)


class Inbox:
    def __init__(self):
        self.dir = tempfile.mkdtemp()
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")

    def git(self, *args):
        subprocess.run(["git", "-C", self.dir, *args], check=True, capture_output=True)

    def put(self, path, value, commit=True):
        full = os.path.join(self.dir, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False))
        if commit:
            self.git("add", "-A")
            self.git("commit", "-q", "-m", path)


def beat(day, good=True, **over):
    prev = day - dt.timedelta(days=1)
    value = {
        "atMs": ms(day, 22), "consent": True, "versionCode": 1060,
        "dbPercent": 22 if good else 46, "dbOpenMs": 400 if good else 1500,
        "transcriptionFailed": 3,
        "transcription": {"doneByLen48h": {"lt1m": 20, "m1to3": 20}, "window14": {"created": 300, "failed": 2 if good else 30}},
        "kakao": {"on": True, "sent": (day - dt.date(2026, 11, 1)).days if good else 3},
        "latency": {"p50Min": 2286, "p90Min": 7915, "day": {"p50Min": 90 if good else 2286, "p90Min": 600 if good else 7915}},
        "report": {"day": day.isoformat(), "coverage": {"yesterdayCalls": 20, "inReport": 20 if good else 10}},
        "callCard": {"day": int(day.strftime("%Y%m%d")), "fgsRefused": 0,
                     "today": {"calls": 10, "drawn": 10 if good else 0, "shown": 10, "locked": 0, "ignored": 0, "off": 0,
                               "noPermission": 0 if good else 10},
                     "ringToCardMs": {"p50": 60}, "ringToCardMsCold": {"p50": 1200}},
        "power": {"prev": {"day": prev.isoformat(), "battery": {"min": 12 if good else 40}}},
        "use": {"prev": {"opens": 2, "taskDone": 3, "kakaoOpen": 1}} if good else None,
        "security": {"certCheck": True, "saltedHash": True} if good else None,
    }
    value.update(over)
    return value


def build(good: bool) -> str:
    box = Inbox()
    start = ASOF - dt.timedelta(days=16)
    for i in range(17):
        day = start + dt.timedelta(days=i)
        box.put("status/phone.json", beat(day, good), commit=False)
        for hour in range(7, 23):
            if good or hour % 2:
                box.put("status/last-run-hourly.json",
                        {"startedMs": ms(day, hour, 34), "cron": "CRON_TZ=Asia/Seoul 34 7-22 * * *"}, commit=True)
        box.put("status/last-run-tidy.json", {"startedMs": ms(day, 5, 5), "reportWritten": True})
    box.put("app/version.json", {"versionCode": 1060, "buildType": "release" if good else None,
                                 "seedIndependent": True if good else None})
    if good:
        box.put("status/gold-commit.json", {"commitRecall": 0.93, "commitPrecision": 0.9, "numberExact": 0.99,
                                            "commitCalls": 30, "commitNumbers": 45})
        box.put("status/gold-transcript.json", {"cer": 0.11, "numberAcc": 0.97, "nameAcc": 0.92, "transcriptMinutes": 25})
        box.put("status/survey.json", {"sus": 85, "veryDisappointed": True, "batterySharePct": 6})
        box.put("status/secrets-scan.json", {"findings": 0})
    return box.dir


class ScoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(os.path.dirname(os.path.abspath(score.__file__)), "scorecard.json"), encoding="utf-8") as handle:
            cls.card = json.load(handle)

    def test_all_pass(self):
        report = score.score(build(True), ASOF, self.card)
        failed = {k: v["reason"] for k, v in report["items"].items() if not v["pass"]}
        self.assertEqual(failed, {})
        self.assertEqual(report["score"], 10)

    def test_bad_phone_fails_every_item(self):
        report = score.score(build(False), ASOF, self.card)
        self.assertEqual(report["score"], 0)
        self.assertIn("정답지", report["items"]["4_commitments"]["reason"])
        self.assertEqual(report["items"]["3_call_card"]["value"]["reach"], 0.0)

    def test_too_few_days_is_not_a_pass(self):
        box = Inbox()
        day = ASOF - dt.timedelta(days=1)
        box.put("status/phone.json", beat(day, True))
        report = score.score(box.dir, ASOF, self.card)
        self.assertFalse(report["items"]["2_timeliness"]["pass"])
        self.assertIn("관측", report["items"]["2_timeliness"]["reason"])

    def test_calllog_parser_drops_names_and_numbers(self):
        text = "11-09 10:05 | 건 | 통화 3분 12초 | 홍길동 | 01012345678 | O | O | 완료 | ab12\n" \
               "11-09 10:09 | 받은 | 통화 0분 5초 | 김개인 | 01099998888 | O | X | 개인 | -\n머리 줄"
        rows = score.parse_calllog(text, 2026)
        self.assertEqual([(r["seconds"], r["recorded"], r["personal"]) for r in rows], [(192, True, False), (5, False, True)])
        self.assertNotIn("홍길동", json.dumps([{k: v for k, v in r.items() if k not in ("key", "at")} for r in rows], ensure_ascii=False))

    def test_cron_hours(self):
        self.assertEqual(score.cron_hours("CRON_TZ=Asia/Seoul 34 3-4,7-9 * * *"), [3, 4, 7, 8, 9])



class SecurityReasonTest(unittest.TestCase):
    """r20 review: secretFindings 0 is met (0 == False made the reason list it as unmet; the verdict was right)."""
    def test_zero_findings_is_not_listed_as_unmet(self):
        cfg = {"build_type": "release", "max_secret_findings": 0}
        version = {"buildType": "release", "seedIndependent": True}
        latest = {"security": {"certCheck": True, "saltedHash": True}}
        r = score.item_security(cfg, version, latest, {"findings": 0})
        self.assertTrue(r["pass"]); self.assertEqual("", r["reason"])
        r = score.item_security(cfg, version, latest, {"findings": 2})
        self.assertFalse(r["pass"]); self.assertEqual("미충족: secretFindings", r["reason"])
        r = score.item_security(cfg, {**version, "seedIndependent": False}, latest, None)
        self.assertFalse(r["pass"]); self.assertEqual("미충족: seedIndependent, secretFindings", r["reason"])

if __name__ == "__main__":
    unittest.main()
