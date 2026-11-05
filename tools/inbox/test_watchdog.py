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


class V31RulesTest(unittest.TestCase):
    """DESIGN_V4 2.2-3: old version, old routine prompt, phone state permission, recording gap verdict."""

    def run_rules(self, ph=None, last_run=None, version=None, version_ms=None, history=None):
        return [p["title"] for p in w.evaluate(NOW, ph, last_run if last_run is not None else {"processed": 3}, None, 0,
                                               version=version, version_ms=version_ms, history=history)]

    # (가) versionCode of version.json above the phone's for 24 hours, heartbeat at most 12 hours old.
    def test_old_version(self):
        v = {"versionCode": 1045, "promptVersion": "6", "tidyVersion": "3"}
        self.assertEqual([w.T_OLD_VERSION], self.run_rules(phone(), version=v, version_ms=NOW - 25 * H))
        body = w.evaluate(NOW, phone(updateAvailableCode=1045, lastUpdateError="USER_ACTION_REQUIRED"), {"processed": 3}, None, 0,
                          version=v, version_ms=NOW - 25 * H)[0]["body"]
        self.assertIn("1040", body)
        self.assertIn("1045", body)
        self.assertIn("USER_ACTION_REQUIRED", body)
        self.assertEqual([], self.run_rules(phone(), version=v, version_ms=NOW - 23 * H))  # published less than a day ago
        self.assertEqual([], self.run_rules(phone(versionCode=1045), version=v, version_ms=NOW - 30 * H))  # up to date
        self.assertEqual([], self.run_rules(phone(versionCode=1046), version=v, version_ms=NOW - 30 * H))
        # A heartbeat older than 12 hours says nothing about the version (the silent phone rule speaks instead).
        self.assertEqual([w.T_PHONE], self.run_rules(phone(atMs=NOW - 13 * H), version=v, version_ms=NOW - 30 * H))
        self.assertEqual([], self.run_rules(phone(), version=None, version_ms=NOW - 30 * H))
        self.assertEqual([], self.run_rules(phone(), version=v, version_ms=None))
        # The two v3.1 install codes are not an "update failed" issue of their own.
        self.assertEqual([], self.run_rules(phone(lastUpdateError="USER_ACTION_REQUIRED")))
        self.assertEqual([], self.run_rules(phone(lastUpdateError="INSTALL_SOURCE_NOT_ALLOWED")))

    # (나) promptVersion of last-run.json per routine against version.json.
    def test_routine_prompt_old(self):
        v = {"versionCode": 1040, "promptVersion": 6, "tidyVersion": "3"}
        self.assertEqual([], self.run_rules(phone(), last_run={"routine": "hourly", "processed": 1, "promptVersion": "6"}, version=v))
        self.assertEqual([w.T_ROUTINE_OLD], self.run_rules(phone(), last_run={"routine": "hourly", "processed": 1, "promptVersion": "5"}, version=v))
        self.assertEqual([], self.run_rules(phone(), last_run={"routine": "tidy", "processed": 1, "promptVersion": "3"}, version=v))
        tidy_old = w.evaluate(NOW, phone(), {"routine": "tidy", "processed": 1, "promptVersion": ""}, None, 0, version=v)
        self.assertEqual([w.T_ROUTINE_OLD], [p["title"] for p in tidy_old])
        self.assertIn("tidy.md", tidy_old[0]["body"])
        # The tidy routine's "6" is not compared with the hourly prompt version.
        self.assertEqual([w.T_ROUTINE_OLD], self.run_rules(phone(), last_run={"routine": "tidy", "processed": 1, "promptVersion": "6"}, version=v))
        # No such field in version.json (release before v3.1) or an unknown routine: nothing.
        self.assertEqual([], self.run_rules(phone(), last_run={"routine": "tidy", "processed": 1, "promptVersion": ""}, version={"versionCode": 1040}))
        self.assertEqual([], self.run_rules(phone(), last_run={"routine": "other", "processed": 1, "promptVersion": "1"}, version=v))
        self.assertEqual([], self.run_rules(phone(), last_run={"processed": 1}, version=v))

    # (다) permissions.phoneState false for 24 hours, only when the heartbeat carries permissions.
    def test_phone_state_off_for_a_day(self):
        perms = lambda ok: {"phoneState": ok, "overlay": True, "redirectRole": False, "installAllowed": True}
        hist = lambda at, ok: phone(atMs=at, permissions=perms(ok))
        now_off = phone(permissions=perms(False))
        self.assertEqual([w.T_PHONE_STATE], self.run_rules(now_off, history=[hist(NOW - 26 * H, False), hist(NOW - 10 * H, False)]))
        # Turned off 20 hours ago: not yet.
        self.assertEqual([], self.run_rules(now_off, history=[hist(NOW - 26 * H, True), hist(NOW - 20 * H, False)]))
        # Off, on for a moment, off again: counted from the last time it went off.
        self.assertEqual([], self.run_rules(now_off, history=[hist(NOW - 40 * H, False), hist(NOW - 30 * H, True), hist(NOW - 12 * H, False)]))
        self.assertEqual([], self.run_rules(phone(permissions=perms(True)), history=[hist(NOW - 30 * H, False)]))
        # An old v2.4 heartbeat without permissions never counts, now or in history.
        self.assertEqual([], self.run_rules(phone(), history=[hist(NOW - 30 * H, False)]))
        self.assertEqual([], self.run_rules(now_off, history=[phone(atMs=NOW - 30 * H)]))
        self.assertEqual([], self.run_rules(phone(permissions=None), history=None))

    # (라) recordingGapVerdict the same for 2 days: one issue per cause; ok never.
    def test_recording_gap_two_days(self):
        gap = {"calls": 10, "withFile": 0, "matched": 0, "newestFileMs": 1, "sinceMs": 2}
        def hb(at, verdict):
            return phone(atMs=at, recordingGapVerdict=verdict, recordingGap=gap)
        for verdict in ("noRecording", "partial", "folder", "tooMany", "noMatch"):
            got = self.run_rules(hb(NOW - H, verdict), history=[hb(NOW - 50 * H, verdict), hb(NOW - 24 * H, verdict)])
            self.assertEqual([w.T_GAP[verdict]], got, verdict)
            self.assertEqual([], self.run_rules(hb(NOW - H, verdict), history=[hb(NOW - 40 * H, verdict)]), verdict)
        body = w.evaluate(NOW, hb(NOW - H, "noRecording"), {"processed": 3}, None, 0, history=[hb(NOW - 50 * H, "noRecording")])[0]["body"]
        self.assertIn("10건", body)
        self.assertIn("자동으로 통화 녹음", body)
        self.assertEqual("녹음은 있는데 통화기록과 이어지지 않아요", w.T_GAP["noMatch"])
        # A different cause in between restarts the count; ok and unknown codes never alarm; v2.4 heartbeats have no verdict.
        self.assertEqual([], self.run_rules(hb(NOW - H, "noRecording"), history=[hb(NOW - 50 * H, "noRecording"), hb(NOW - 30 * H, "partial")]))
        self.assertEqual([], self.run_rules(hb(NOW - H, "ok"), history=[hb(NOW - 60 * H, "ok")]))
        self.assertEqual([], self.run_rules(hb(NOW - H, "김부장"), history=[hb(NOW - 60 * H, "김부장")]))
        self.assertEqual([], self.run_rules(phone(), history=[phone(atMs=NOW - 60 * H)]))

    def test_old_v24_heartbeat_with_everything_new(self):
        v = {"versionCode": 1040, "promptVersion": "6", "tidyVersion": "3"}
        self.assertEqual([], self.run_rules(phone(), last_run={"routine": "hourly", "processed": 2, "promptVersion": "6"}, version=v,
                                            version_ms=NOW - 90 * DAY, history=[phone(atMs=NOW - 50 * H), "x", None]))

    def test_cli_reads_version_and_history_from_git(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "status"))
            os.makedirs(os.path.join(root, "app"))
            env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@e", GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@e")
            subprocess.run(["git", "init", "-q", root], check=True)

            def commit(rel, data, at_ms):
                with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                    json.dump(data, f)
                stamp = f"@{at_ms // 1000} +0000"
                e = dict(env, GIT_AUTHOR_DATE=stamp, GIT_COMMITTER_DATE=stamp)
                subprocess.run(["git", "-C", root, "add", rel], check=True, env=e)
                subprocess.run(["git", "-C", root, "commit", "-q", "-m", "s", "--", rel], check=True, env=e)

            off = {"phoneState": False, "overlay": True, "redirectRole": True, "installAllowed": True}
            commit("app/version.json", {"versionCode": 1045, "promptVersion": "6", "tidyVersion": "3"}, NOW - 30 * H)
            commit("status/phone.json", phone(atMs=NOW - 30 * H, permissions=off), NOW - 30 * H)
            commit("status/phone.json", phone(atMs=NOW - 10 * H, permissions=off), NOW - 10 * H)
            commit("status/phone.json", phone(atMs=NOW - H, permissions=off), NOW - H)
            out = os.path.join(root, "out.json")
            self.assertEqual(0, w.main(["--root", root, "--now-ms", str(NOW), "--out", out]))
            with open(out, encoding="utf-8") as f:
                got = sorted(p["title"] for p in json.load(f))
            self.assertEqual(sorted([w.T_OLD_VERSION, w.T_PHONE_STATE]), got)
            self.assertEqual(NOW - 30 * H, w.last_commit_ms(root, "app/version.json"))
            self.assertEqual(3, len(w.phone_history(root, NOW)))


if __name__ == "__main__":
    unittest.main()
