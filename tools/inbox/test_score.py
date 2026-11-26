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



class UseV4Test(unittest.TestCase):
    """채점표 v4(11/20): 10번은 접점으로 잰다 — 카톡이 간 평일 ≥ 90%, 통화 카드 ≥ 95%(3번과 같은 식), SUS ≥ 80."""
    window = {"weekdays": 10, "min_pass_days": 9, "calendar_days": 14}
    cfg = {"min_kakao_weekday_ratio": 0.9, "min_card_reach": 0.95, "min_card_calls": 30, "min_sus": 80, "very_disappointed": True}
    survey = {"sus": 85, "veryDisappointed": True}

    def daily(self, skip=(), drawn=10, use=None):
        """ASOF 전 16일. skip: 카톡이 가지 않은 날(sent가 그대로)."""
        out, sent = {}, 0
        for i in range(16, 0, -1):
            day = ASOF - dt.timedelta(days=i)
            if day not in skip:
                sent += 1
            b = beat(day, True, kakao={"on": True, "sent": sent})
            b["callCard"]["today"]["drawn"] = drawn
            if use is not None:
                b["use"] = use
            out[day] = b
        return out

    def test_weekends_do_not_count(self):
        weekends = {ASOF - dt.timedelta(days=i) for i in range(1, 15) if (ASOF - dt.timedelta(days=i)).weekday() >= 5}
        r = score.item_use(self.cfg, self.window, ASOF, self.daily(skip=weekends), self.survey)
        self.assertTrue(r["pass"], r["reason"])
        self.assertEqual({"sent": 10, "of": 10, "ratio": 1.0}, r["value"]["kakaoWeekdays"])

    def test_two_weekdays_without_kakao_fail(self):
        days = [ASOF - dt.timedelta(days=i) for i in range(1, 15) if (ASOF - dt.timedelta(days=i)).weekday() < 5]
        one = score.item_use(self.cfg, self.window, ASOF, self.daily(skip={days[0]}), self.survey)
        self.assertTrue(one["pass"], "9/10 = 90%")
        two = score.item_use(self.cfg, self.window, ASOF, self.daily(skip=set(days[:2])), self.survey)
        self.assertFalse(two["pass"]); self.assertEqual("미충족: kakaoWeekdays", two["reason"])

    def test_the_card_and_the_survey(self):
        r = score.item_use(self.cfg, self.window, ASOF, self.daily(drawn=9), self.survey)
        self.assertFalse(r["pass"]); self.assertEqual("미충족: card", r["reason"]); self.assertEqual(0.9, r["value"]["card"]["ratio"])
        r = score.item_use(self.cfg, self.window, ASOF, self.daily(), {"sus": 79, "veryDisappointed": True})
        self.assertEqual("미충족: sus", r["reason"])
        r = score.item_use(self.cfg, self.window, ASOF, self.daily(), None)
        self.assertEqual("미충족: sus, veryDisappointed", r["reason"])

    def test_the_old_numbers_are_reference_only(self):
        # 앱을 한 번도 열지 않고 [다 됐어요]도 없어도(옛 기준 0) 접점이 되면 통과. 옛 숫자는 그대로 나온다.
        r = score.item_use(self.cfg, self.window, ASOF, self.daily(use={"prev": {"opens": 0, "taskDone": 0, "kakaoOpen": 0}}), self.survey)
        self.assertTrue(r["pass"], r["reason"])
        self.assertEqual({"daysSeen": 13, "openDaysPerWeek": 0.0, "taskDonePerWeek": 0.0}, r["value"]["reference"])
        r = score.item_use(self.cfg, self.window, ASOF, self.daily(), self.survey)
        self.assertEqual(6.5, r["value"]["reference"]["openDaysPerWeek"]); self.assertEqual(19.5, r["value"]["reference"]["taskDonePerWeek"])

    def test_too_few_days_or_calls(self):
        d = self.daily()
        late = {k: v for k, v in d.items() if k >= ASOF - dt.timedelta(days=5)}
        r = score.item_use(self.cfg, self.window, ASOF, late, self.survey)
        self.assertFalse(r["pass"]); self.assertIn("카톡 관측 평일", r["reason"])
        for b in d.values():
            b["callCard"]["today"].update(calls=1, drawn=1)
        r = score.item_use(self.cfg, self.window, ASOF, d, self.survey)
        self.assertFalse(r["pass"]); self.assertIn("통화 14건", r["reason"])

    def test_the_card_says_v5(self):
        with open(os.path.join(os.path.dirname(os.path.abspath(score.__file__)), "scorecard.json"), encoding="utf-8") as handle:
            card = json.load(handle)
        self.assertEqual(5, card["version"])
        self.assertEqual(0.95, card["items"]["3_call_card"]["min_reach"])
        self.assertEqual(0.95, card["items"]["10_use_and_satisfaction"]["min_card_reach"])
        self.assertEqual(80, card["items"]["10_use_and_satisfaction"]["min_sus"])


class CardReachV5Test(unittest.TestCase):
    """채점표 v5(11/21): 3번과 10번의 분자는 카드가 그려졌거나 대체 알림이 실제로 뜬 통화. 문턱 95%는 그대로, 옛 식은 참고."""
    window = {"weekdays": 10, "min_pass_days": 9, "calendar_days": 14}
    cfg3 = {"min_reach": 0.95, "min_calls": 30, "ring_p50_max_ms": 1000, "cold_p50_max_ms": 1500}
    cfg10 = UseV4Test.cfg

    def daily(self, today):
        out = {}
        for i in range(16, 0, -1):
            day = ASOF - dt.timedelta(days=i)
            b = beat(day, True, kakao={"on": True, "sent": 17 - i})
            b["callCard"]["today"] = dict(today)
            out[day] = b
        return out

    def test_r28_repeat_keys_are_optional_and_change_nothing(self):
        # v4.7 r28-2: repeatToday/repeatPending/repeatShown are counts only; the card's score reads none of them.
        base = {"calls": 10, "drawn": 9, "fallback": 1, "fallbackShown": 1, "ignored": 0, "off": 0}
        plain = score.item_call_card(self.cfg3, self.window, ASOF, self.daily(base))
        more = score.item_call_card(self.cfg3, self.window, ASOF, self.daily(dict(base, repeatToday=4, repeatPending=3, repeatShown=3)))
        self.assertEqual(plain, more)

    def test_a_shown_fallback_notice_counts(self):
        # 10통화 중 2통화만 그려지고 8통화는 대체 알림이 실제로 떴다(삼성 제한): v5는 닿음, 옛 식은 20%.
        d = self.daily({"calls": 10, "drawn": 2, "fallback": 8, "fallbackShown": 8, "ignored": 0, "off": 0})
        r = score.item_call_card(self.cfg3, self.window, ASOF, d)
        self.assertTrue(r["pass"], r["reason"]); self.assertEqual(1.0, r["value"]["reach"])
        self.assertEqual({"drawn": 28, "reach": 0.2}, r["value"]["reference_v4"])
        u = score.item_use(self.cfg10, self.window, ASOF, d, UseV4Test.survey)
        self.assertTrue(u["pass"], u["reason"]); self.assertEqual(1.0, u["value"]["card"]["ratio"])
        self.assertEqual({"drawn": 28, "ratio": 0.2}, u["value"]["card"]["reference_v4"])

    def test_a_notice_that_did_not_show_does_not(self):
        d = self.daily({"calls": 10, "drawn": 2, "fallback": 8, "fallbackShown": 7, "ignored": 0, "off": 0})
        r = score.item_call_card(self.cfg3, self.window, ASOF, d)
        self.assertFalse(r["pass"]); self.assertEqual(0.9, r["value"]["reach"])

    def test_the_notice_beside_a_drawn_card_is_not_counted_twice(self):
        # 잠금 화면: 그려진 창 곁에도 알림이 뜬다. 알림은 안 그려진 통화 수(fallback+unconfirmed)까지만.
        d = self.daily({"calls": 10, "drawn": 9, "fallback": 1, "fallbackShown": 5, "ignored": 0, "off": 0})
        r = score.item_call_card(self.cfg3, self.window, ASOF, d)
        self.assertEqual(1.0, r["value"]["reach"])
        d = self.daily({"calls": 10, "drawn": 5, "fallback": 0, "fallbackShown": 5, "ignored": 0, "off": 0})
        self.assertEqual(0.5, score.item_call_card(self.cfg3, self.window, ASOF, d)["value"]["reach"])

    def test_fallback_seen_comes_first_r24(self):
        # 폰이 센 fallbackSeen이 있으면 fallbackShown(잠금 화면 곁 알림도 섞임) 대신 그것으로
        d = self.daily({"calls": 10, "drawn": 2, "fallback": 8, "fallbackShown": 8, "fallbackSeen": 6, "ignored": 0, "off": 0})
        self.assertEqual(0.8, score.item_call_card(self.cfg3, self.window, ASOF, d)["value"]["reach"])
        d = self.daily({"calls": 10, "drawn": 2, "fallback": 8, "fallbackShown": 0, "fallbackSeen": 8, "ignored": 0, "off": 0})
        self.assertEqual(1.0, score.item_call_card(self.cfg3, self.window, ASOF, d)["value"]["reach"])

    def test_old_heartbeats_count_drawn_only(self):
        d = self.daily({"calls": 10, "drawn": 2, "fallback": 8, "ignored": 0, "off": 0})
        r = score.item_call_card(self.cfg3, self.window, ASOF, d)
        self.assertFalse(r["pass"]); self.assertEqual(0.2, r["value"]["reach"])


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
