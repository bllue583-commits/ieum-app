import json, os, subprocess, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import watchdog as w

H = w.HOUR
DAY = 24 * H
NOW = 1_800_000_000_000


def phone(**kw):
    """A healthy v2.4 heartbeat (status/phone.json) with the given changes."""
    p = {"atMs": NOW - H, "appVersion": "2.4", "versionCode": 1040, "queuedCount": 0, "oldestQueuedMs": None,
         "lastTranscribedMs": NOW - 2 * H, "lastResultMs": NOW - 2 * H, "modelState": "READY", "storageUsedPercent": 12,
         "consent": True, "folder": True, "notifyAllowed": True, "batteryFree": True, "standbyBucket": "ACTIVE",
         "lastDiscoveryMs": NOW - H, "lastUploadMs": NOW - 3 * H, "pendingAnalysis": 0, "rescheduledMs": 0,
         "tokenExpiresMs": NOW + 60 * DAY, "audioChannels": 1, "audioChannelCorr": None}
    p.update(kw)
    return p


def problems(**kw):
    args = dict(phone={"atMs": NOW - H}, last_run={"processed": 3}, inbox_oldest_ms=None, inbox_count=0)
    args.update(kw)
    return w.evaluate(NOW, args["phone"], args["last_run"], args["inbox_oldest_ms"], args["inbox_count"])


def titles(**kw):
    return [p["title"] for p in problems(**kw)]


class WatchdogTest(unittest.TestCase):
    def test_healthy(self):
        self.assertEqual([], titles())
        self.assertEqual([], titles(phone=phone()))

    def test_phone_silent(self):
        self.assertEqual([w.T_PHONE], titles(phone={"atMs": NOW - 13 * H}))
        self.assertEqual([], titles(phone={"atMs": NOW - 11 * H}))
        self.assertEqual([w.T_PHONE], titles(phone=None))

    def test_silent_phone_says_why_from_its_last_heartbeat(self):
        body = problems(phone=phone(atMs=NOW - 20 * H, folder=False, standbyBucket="RESTRICTED", consent=False,
                                    lastDiscoveryMs=NOW - 30 * H))[0]["body"]
        self.assertIn("folder=false", body)
        self.assertIn("RESTRICTED", body)
        self.assertIn("consent=false", body)
        self.assertIn("녹음 찾기", body)
        self.assertNotIn("마지막 하트비트 기준", problems(phone=phone(atMs=NOW - 20 * H))[0]["body"])
        # An unknown bucket value is never copied into an issue.
        self.assertNotIn("x", problems(phone=phone(atMs=NOW - 20 * H, standbyBucket="x"))[0]["body"])

    def test_discovery_stopped_while_phone_alive(self):
        self.assertEqual([w.T_DISCOVERY], titles(phone=phone(lastDiscoveryMs=NOW - 13 * H)))
        self.assertEqual([], titles(phone=phone(lastDiscoveryMs=NOW - 11 * H)))
        self.assertEqual([], titles(phone=phone(lastDiscoveryMs=0)))  # app before v2.4: unknown

    def test_call_log_mismatch_over_two_hours(self):
        def log(device, stored, since):
            return {"allowed": True, "deviceTotal": device, "storedTotal": stored, "mismatchSinceMs": since}
        self.assertEqual([w.T_CALL_LOG], titles(phone=phone(callLog=log(120, 117, NOW - 3 * H))))
        self.assertIn("120건", problems(phone=phone(callLog=log(120, 117, NOW - 3 * H)))[0]["body"])
        self.assertEqual([], titles(phone=phone(callLog=log(120, 117, NOW - H))))  # a call that just ended
        self.assertEqual([], titles(phone=phone(callLog=log(120, 120, 0))))
        self.assertEqual([], titles(phone=phone(callLog=log(None, 117, 0))))  # no permission
        self.assertEqual([w.T_PHONE], titles(phone=phone(atMs=NOW - 13 * H, callLog=log(120, 117, NOW - 30 * H))))

    def test_queue_stuck(self):
        self.assertEqual([w.T_QUEUE], titles(phone={"atMs": NOW, "oldestQueuedMs": NOW - 7 * H, "queuedCount": 2}))
        self.assertEqual([], titles(phone={"atMs": NOW, "oldestQueuedMs": None}))
        self.assertEqual([], titles(phone={"atMs": NOW, "oldestQueuedMs": NOW - 5 * H}))

    def test_inbox_stuck_and_routine(self):
        self.assertEqual([w.T_INBOX], titles(inbox_oldest_ms=NOW - 4 * H, inbox_count=2))
        self.assertEqual([], titles(inbox_oldest_ms=NOW - 2 * H, inbox_count=1))
        self.assertEqual([w.T_ROUTINE], titles(last_run={"processed": 0}, inbox_oldest_ms=NOW - H, inbox_count=1))
        self.assertEqual([], titles(last_run={"processed": 0}))

    def test_limit_replaces_idle_routine(self):
        self.assertEqual([w.T_LIMIT], titles(last_run={"processed": 0, "limitHit": True}, inbox_oldest_ms=NOW - H, inbox_count=1))

    def test_prompt_mismatch_and_update_error(self):
        self.assertEqual([w.T_PROMPT], titles(last_run={"processed": 1, "promptMismatch": True}))
        self.assertEqual([w.T_UPDATE], titles(phone={"atMs": NOW, "lastUpdateError": "STATUS_FAILURE"}))

    def test_token_expiry_comes_from_the_phone(self):
        p = problems(phone=phone(tokenExpiresMs=NOW + 13 * DAY))
        self.assertEqual([w.T_TOKEN], [x["title"] for x in p])
        self.assertIn("13일", p[0]["body"])
        self.assertEqual([], titles(phone=phone(tokenExpiresMs=NOW + 15 * DAY)))
        self.assertEqual([], titles(phone=phone(tokenExpiresMs=None)))  # not seen yet: skipped
        self.assertIn("만료됐어요", problems(phone=phone(tokenExpiresMs=NOW - DAY))[0]["body"])

    def test_plan_opens_once_and_closes_when_healthy(self):
        expiring = problems(phone=phone(tokenExpiresMs=NOW + 13 * DAY))
        first = w.plan(expiring, [])
        self.assertEqual([w.T_TOKEN], [p["title"] for p in first["create"]])
        self.assertEqual([], first["close"])
        # Next run, issue already open: nothing new, nothing closed.
        self.assertEqual({"create": [], "close": []}, w.plan(expiring, [{"number": 7, "title": w.T_TOKEN}]))
        # Token renewed: the open issue is closed.
        healthy = problems(phone=phone(tokenExpiresMs=NOW + 90 * DAY))
        self.assertEqual({"create": [], "close": [7]}, w.plan(healthy, [{"number": 7, "title": w.T_TOKEN}]))

    def test_cli_with_fake_repository(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "status"))
            subprocess.run(["git", "init", "-q", root], check=True)

            def run(heartbeat, open_issues):
                with open(os.path.join(root, "status/phone.json"), "w") as f:
                    json.dump(heartbeat, f)
                with open(os.path.join(root, "open.json"), "w") as f:
                    json.dump(open_issues, f)
                out = os.path.join(root, "plan.json")
                self.assertEqual(0, w.main(["--root", root, "--now-ms", str(NOW), "--open-issues", os.path.join(root, "open.json"), "--out", out]))
                with open(out, encoding="utf-8") as f:
                    return json.load(f)

            got = run(phone(tokenExpiresMs=NOW + 13 * DAY), [])
            self.assertEqual([w.T_TOKEN], [p["title"] for p in got["create"]])
            got = run(phone(), [{"number": 3, "title": w.T_TOKEN}])
            self.assertEqual({"create": [], "close": [3]}, got)

    def test_no_content_in_bodies(self):
        for p in w.evaluate(NOW, {"atMs": 0, "oldestQueuedMs": 1, "lastUpdateError": "X", "tokenExpiresMs": NOW, "folder": False},
                            {"processed": 0, "promptMismatch": True, "limitHit": True}, 1, 3):
            self.assertNotIn("http", p["body"])


if __name__ == "__main__":
    unittest.main()
