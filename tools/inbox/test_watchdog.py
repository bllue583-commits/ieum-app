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

    def test_first_pass_old_queue_is_not_stuck(self):
        fp = {"done": False, "toTranscribe": 139, "audioMinutesLeft": 225}
        self.assertEqual([], titles(phone=phone(oldestQueuedMs=NOW - 90 * DAY, queuedCount=139, firstPass=fp)))
        self.assertEqual([w.T_QUEUE], titles(phone=phone(oldestQueuedMs=NOW - 90 * DAY, queuedCount=139, firstPass=fp,
                                                         lastTranscribedMs=NOW - 25 * H)))

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

    def test_inbox_age_counts_only_routine_hours(self):
        # v2.9: the routine runs 07:00 to 22:30 KST. NOW is 17:00 KST; 07:00 KST that day is NOW - 10 h.
        seven = NOW - 10 * H
        self.assertEqual(2 * H, w.routine_ms(seven + H, seven + 3 * H))
        # Sent at 22:00, read at 08:30 the next day: 1 h before 23:00 and 1.5 h after 07:00.
        self.assertEqual(int(2.5 * H), w.routine_ms(seven + 15 * H - DAY, seven + int(1.5 * H)))
        self.assertEqual(0, w.routine_ms(seven - 5 * H, seven))
        # A request from 23:30 the night before is not stuck at 08:30; at 10:30 it is.
        self.assertEqual([], [p["title"] for p in w.evaluate(seven + int(1.5 * H), {"atMs": seven + H}, {"processed": 3}, seven - int(7.5 * H), 1)])
        self.assertEqual([w.T_INBOX], [p["title"] for p in w.evaluate(seven + int(3.5 * H), {"atMs": seven + 3 * H}, {"processed": 3}, seven - int(7.5 * H), 1)])

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

    # ---- v2.6 (design 3.6, r7-6) ----
    def test_queue_alarm_uses_new_recordings_by_entry_time(self):
        # A v2.6 heartbeat: the old oldestQueuedMs (call time of a first-pass recording) says nothing any more.
        self.assertEqual([], titles(phone=phone(queuedCount=40, oldestQueuedMs=NOW - 90 * DAY, newQueuedCount=0,
                                                newOldestQueuedMs=None, oldQueuedCount=40)))
        self.assertEqual([w.T_QUEUE], titles(phone=phone(queuedCount=3, oldestQueuedMs=NOW - 7 * H, newQueuedCount=3,
                                                         newOldestQueuedMs=NOW - 7 * H, oldQueuedCount=0)))
        self.assertIn("새 녹음 3개", problems(phone=phone(newQueuedCount=3, newOldestQueuedMs=NOW - 7 * H))[0]["body"])
        self.assertEqual([], titles(phone=phone(newQueuedCount=3, newOldestQueuedMs=NOW - 5 * H)))
        # Old recordings waiting a long time after the first pass is over: still no alarm (only new ones count).
        self.assertEqual([], titles(phone=phone(newQueuedCount=0, newOldestQueuedMs=None, oldQueuedCount=5,
                                                firstPass={"done": True})))

    def test_first_pass_running_never_alarms_on_old(self):
        fp = {"done": False, "toTranscribe": 139, "audioMinutesLeft": 225}
        self.assertEqual([], titles(phone=phone(firstPass=fp, newQueuedCount=0, oldQueuedCount=139, oldestQueuedMs=NOW - 90 * DAY)))

    def test_discovery_tries_without_success(self):
        # Tries are reported and none ended well for 12 hours, also when none ever did (lastDiscoveryMs 0, the 11/4 case).
        self.assertEqual([w.T_DISCOVERY], titles(phone=phone(lastDiscoveryMs=0, lastDiscoveryAttemptMs=NOW - H,
                                                             lastDiscoveryOutcome="TIME_LIMIT")))
        body = problems(phone=phone(lastDiscoveryMs=NOW - 13 * H, lastDiscoveryAttemptMs=NOW - H,
                                    lastDiscoveryOutcome="FAILED 김 010", inventoryTruncated=True))[0]["body"]
        self.assertIn("FAILED", body)
        self.assertIn("inventoryTruncated", body)
        self.assertNotIn("김", body)
        self.assertNotIn(" 010", body)
        self.assertEqual([], titles(phone=phone(lastDiscoveryMs=NOW - 11 * H, lastDiscoveryAttemptMs=NOW - H)))
        # No tries reported (app before v2.6) and never a good cycle: unknown, as before.
        self.assertEqual([], titles(phone=phone(lastDiscoveryMs=0, lastDiscoveryAttemptMs=None)))

    def test_update_not_installed_for_a_day(self):
        self.assertEqual([w.T_NOT_INSTALLED], titles(phone=phone(updateAvailableCode=1050, updateReadySinceMs=NOW - 25 * H)))
        self.assertEqual([], titles(phone=phone(updateAvailableCode=1050, updateReadySinceMs=NOW - 23 * H)))
        self.assertEqual([], titles(phone=phone(updateAvailableCode=1040, updateReadySinceMs=NOW - 30 * H)))  # already running it
        self.assertEqual([], titles(phone=phone(updateAvailableCode=None, updateReadySinceMs=None)))

    def test_same_inbox_error_for_two_hours(self):
        self.assertEqual([w.T_INBOX_ERROR], titles(phone=phone(lastInboxError="INBOX_HTTP_401", lastInboxErrorSinceMs=NOW - 3 * H)))
        self.assertIn("INBOX_HTTP_401", problems(phone=phone(lastInboxError="INBOX_HTTP_401", lastInboxErrorSinceMs=NOW - 3 * H))[0]["body"])
        self.assertEqual([], titles(phone=phone(lastInboxError="INBOX_HTTP_401", lastInboxErrorSinceMs=NOW - H)))
        self.assertEqual([], titles(phone=phone(lastInboxError="INBOX_HTTP_401")))  # since unknown
        self.assertEqual([], titles(phone=phone(lastInboxErrorSinceMs=NOW - 3 * H)))  # no error now

    def test_db_percent_over_80(self):
        self.assertEqual([w.T_DB_FULL], titles(phone=phone(dbPercent=81)))
        self.assertIn("81%", problems(phone=phone(dbPercent=81))[0]["body"])
        self.assertEqual([], titles(phone=phone(dbPercent=80)))
        self.assertEqual([], titles(phone=phone(dbPercent=None)))

    def test_call_card_permission_off_three_times_a_day(self):
        card = lambda np, shown=0: {"shown": 9, "noPermission": 20, "noNumber": 1, "noCard": 2, "day": 20261105,
                                    "today": {"shown": shown, "noPermission": np, "noNumber": 0, "noCard": 0}}
        self.assertEqual([w.T_CARD_PERMISSION], titles(phone=phone(callCard=card(3))))
        body = problems(phone=phone(callCard=card(4, shown=1)))[0]["body"]
        self.assertIn("4번", body)
        self.assertIn("카드가 뜬 횟수: 1번", body)
        self.assertEqual([], titles(phone=phone(callCard=card(2))))
        # Totals over many days are no reason; nothing counted or an old app without the field: nothing.
        self.assertEqual([], titles(phone=phone(callCard={"noPermission": 50})))
        self.assertEqual([], titles(phone=phone(callCard=None)))
        self.assertEqual([], titles(phone=phone()))
        self.assertEqual([], titles(phone=phone(callCard={"today": {"noPermission": True}})))
        self.assertEqual([], titles(phone=phone(callCard={"today": {"noPermission": "5"}})))

    def test_exempted_bucket_is_no_cause(self):
        body = problems(phone=phone(atMs=NOW - 20 * H, standbyBucket="EXEMPTED"))[0]["body"]
        self.assertNotIn("EXEMPTED", body)
        self.assertIn("NEVER", problems(phone=phone(atMs=NOW - 20 * H, standbyBucket="NEVER"))[0]["body"])


if __name__ == "__main__":
    unittest.main()
