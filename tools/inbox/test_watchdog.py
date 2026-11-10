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
        self.assertIn("통화 순간에 카드 창을 띄우지 못했어요", body)
        self.assertNotIn("권한이 꺼졌어요", body)

    def test_call_card_fallback_and_drawn_v361(self):
        # v3.6.1: fallback (window refused, hidden or the check said no) is what counts; drawn is what showed.
        recent = [{"day": 20261110, "at": "09:01", "result": "fallback", "cause": "hidden", "mode": 1},
                  {"day": 20261110, "at": "09:30", "result": "fallback", "cause": "addView:BadTokenException", "mode": 2},
                  {"day": 20261110, "at": "10:02", "result": "fallback", "cause": "hidden"},
                  {"day": 20261109, "at": "18:00", "result": "fallback", "cause": "short"},
                  {"day": 20261110, "at": "11:00", "result": "drawn", "cause": "drawnDespiteFalse"}]
        card = {"day": 20261110, "recent": recent,
                "today": {"shown": 0, "noPermission": 0, "fallback": 3, "drawn": 1, "hidden": 2}}
        self.assertEqual([w.T_CARD_PERMISSION], titles(phone=phone(callCard=card)))
        body = problems(phone=phone(callCard=card))[0]["body"]
        self.assertIn("3번", body)
        self.assertIn("카드가 뜬 횟수: 1번", body)
        self.assertIn("callCard.recent", body)
        self.assertIn("hidden 2번, addView:BadTokenException 1번", body)
        self.assertNotIn("short", body)
        # fallback is there: noPermission alone (it may count the same calls) is not used.
        self.assertEqual([], titles(phone=phone(callCard={"today": {"noPermission": 5, "fallback": 2, "drawn": 9}})))
        # Bad recent entries are skipped.
        bad = dict(card, recent=[None, "x", {"result": "fallback", "day": 20261110, "cause": "김 010"}])
        self.assertNotIn("김", problems(phone=phone(callCard=bad))[0]["body"])

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
        # A report that ended before version.json last changed is no evidence (an empty hourly run writes none): nothing until a
        # run after the release still reports the old version.
        old = {"routine": "hourly", "processed": 1, "promptVersion": "5", "endedMs": NOW - 5 * H}
        self.assertEqual([], self.run_rules(phone(), last_run=old, version=v, version_ms=NOW - 4 * H))
        self.assertEqual([w.T_ROUTINE_OLD], self.run_rules(phone(), last_run=dict(old, endedMs=NOW - 1 * H), version=v, version_ms=NOW - 4 * H))
        self.assertEqual([], self.run_rules(phone(), last_run=dict(old, endedMs=NOW - 1 * H, promptVersion="6"), version=v, version_ms=NOW - 4 * H))

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

    # (마) r12-8: long calls (over 100 s) of the last 48 hours finished under 80% for 24 hours.
    def test_long_calls_under_eighty_percent_for_a_day(self):
        def hb(at, total, done, **extra):
            lg = {"total": total, "done": done}
            if total:
                lg["ratePct"] = done * 100 // total
            return phone(atMs=at, transcription=dict({"long48h": lg}, **extra))
        now_low = hb(NOW - H, 27, 3, oldestWaitMin=125, failReasons={"ANDROID_STT_PCM_SIZE": 5, "STALLED": 3})
        got = w.evaluate(NOW, now_low, {"processed": 3}, None, 0, history=[hb(NOW - 30 * H, 20, 2), hb(NOW - 12 * H, 25, 3)])
        self.assertEqual([w.T_LONG_CALLS], [p["title"] for p in got])
        self.assertIn("27건", got[0]["body"])
        self.assertIn("11%", got[0]["body"])
        self.assertIn("2시간 5분", got[0]["body"])
        self.assertIn("ANDROID_STT_PCM_SIZE 5", got[0]["body"])
        # Under 80% for only 20 hours, or broken by a good heartbeat: not yet.
        self.assertEqual([], self.run_rules(now_low, history=[hb(NOW - 20 * H, 20, 2)]))
        self.assertEqual([], self.run_rules(now_low, history=[hb(NOW - 40 * H, 20, 2), hb(NOW - 20 * H, 10, 9)]))
        # 80% or more, no long call at all, or a heartbeat without the numbers (before v3.2): never.
        self.assertEqual([], self.run_rules(hb(NOW - H, 10, 8), history=[hb(NOW - 40 * H, 10, 8)]))
        self.assertEqual([], self.run_rules(hb(NOW - H, 0, 0), history=[hb(NOW - 40 * H, 0, 0)]))
        self.assertEqual([], self.run_rules(now_low, history=[phone(atMs=NOW - 40 * H)]))
        self.assertEqual([], self.run_rules(phone(transcription="x"), history=None))

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


M = w.MINUTE
# NOW is 17:00 KST; the day's 00:00 KST:
KST_MIDNIGHT = NOW - 17 * H


def at(hh, mm, day=0):
    return KST_MIDNIGHT + day * DAY + hh * H + mm * M


class V34RoutineTest(unittest.TestCase):
    """DESIGN_V4 4.3-1, 4.3-2: per-routine self-reports, the missed-slot rule, pushed=false, and --apply through `gh api`."""

    def test_now_is_five_in_the_afternoon_kst(self):
        self.assertEqual("17:00", w.kst(NOW))

    def test_parse_cron(self):
        c = w.parse_cron("CRON_TZ=Asia/Seoul 34 7-22 * * *")
        self.assertEqual({"offset": 9 * H, "minutes": [34], "hours": list(range(7, 23))}, c)
        self.assertEqual([4, 34], w.parse_cron("CRON_TZ=Asia/Seoul 4,34 8-22 * * *")["minutes"])
        self.assertEqual([0, 30], w.parse_cron("*/30 7-22 * * *")["minutes"])
        self.assertEqual(0, w.parse_cron("*/30 7-22 * * *")["offset"])
        for bad in (None, "", "x", "34 7-22 * * 1-5", "CRON_TZ=Mars/Base 34 7 * * *", "61 7 * * *", "34 25 * * *", "34 9-7 * * *", 34):
            self.assertIsNone(w.parse_cron(bad), bad)

    def test_slots_follow_the_cron_in_kst(self):
        crons = [w.parse_cron(w.DEFAULT_CRON)]
        self.assertEqual([at(14, 34), at(15, 34), at(16, 34)], w.slots(crons, at(14, 0), NOW))
        # Night: nothing between 22:34 and the next 07:34.
        self.assertEqual([at(22, 34), at(7, 34, 1)], w.slots(crons, at(22, 0), at(7, 40, 1)))
        two = [w.parse_cron("CRON_TZ=Asia/Seoul 34 7-22 * * *"), w.parse_cron("CRON_TZ=Asia/Seoul 4 8-22 * * *")]
        self.assertEqual([at(15, 4), at(15, 34), at(16, 4), at(16, 34)], w.slots(two, at(15, 0), NOW))

    def run_rules(self, commits, hourly=None, now=NOW, tidy=None):
        hourly = {"routine": "hourly", "processed": 1, "cron": w.DEFAULT_CRON, "pushed": True} if hourly is None else hourly
        return [p["title"] for p in w.evaluate(now, phone(atMs=now - H, lastDiscoveryMs=now - H), hourly, None, 0, tidy_run=tidy, status_commits=commits)]

    def test_two_slots_in_a_row_without_a_status_commit(self):
        # At 17:00 the due slots are 14:34 and 15:34 (16:34 is under 40 minutes old).
        self.assertEqual([], self.run_rules([at(15, 36)]))
        self.assertEqual([], self.run_rules([at(14, 37)]))  # one of the two kept: not two in a row
        self.assertEqual([w.T_ROUTINE_MISSED], self.run_rules([at(13, 36)]))
        self.assertEqual([w.T_ROUTINE_MISSED], self.run_rules([]))
        body = w.evaluate(NOW, phone(), {"routine": "hourly", "cron": w.DEFAULT_CRON}, None, 0, status_commits=[at(13, 36)])[0]["body"]
        self.assertIn("14:34, 15:34", body)
        self.assertIn("저장소 접근", body)
        # A commit more than an hour after the slot is not that slot's.
        self.assertEqual([w.T_ROUTINE_MISSED], self.run_rules([at(13, 33), at(16, 40)]))
        # A commit a little before the slot (a fast clock) counts.
        self.assertEqual([], self.run_rules([at(15, 31)]))

    def test_the_night_is_no_miss(self):
        # 07:10 next morning: the last two slots are 21:34 and 22:34 of the evening before.
        morning = at(7, 10, 1)
        self.assertEqual([], self.run_rules([at(22, 36)], now=morning))
        self.assertEqual([w.T_ROUTINE_MISSED], self.run_rules([at(20, 36)], now=morning))
        body = w.evaluate(morning, phone(atMs=morning - H, lastDiscoveryMs=morning - H),{"routine": "hourly", "cron": w.DEFAULT_CRON}, None, 0,
                          status_commits=[at(20, 36)])[0]["body"]
        self.assertIn("21:34, 22:34", body)

    def test_the_report_cron_and_a_second_cron(self):
        # Every 30 minutes (4.3-6): at 17:00 the due slots are 15:34 and 16:04.
        two = {"routine": "hourly", "crons": ["CRON_TZ=Asia/Seoul 34 7-22 * * *", "CRON_TZ=Asia/Seoul 4 8-22 * * *"]}
        self.assertEqual([], self.run_rules([at(16, 6)], hourly=two))
        self.assertEqual([w.T_ROUTINE_MISSED], self.run_rules([at(15, 0)], hourly=two))
        # An unreadable cron falls back to minute 34, 07 to 22.
        self.assertEqual([], self.run_rules([at(15, 36)], hourly={"routine": "hourly", "cron": "매시간"}))

    def test_no_history_or_no_report_says_nothing(self):
        self.assertEqual([], self.run_rules(None))
        self.assertEqual([], [p["title"] for p in w.evaluate(NOW, phone(), None, None, 0, status_commits=[])])

    def test_pushed_false(self):
        self.assertEqual([w.T_NOT_PUSHED], self.run_rules([at(15, 36)], hourly={"routine": "hourly", "cron": w.DEFAULT_CRON, "pushed": False}))
        got = w.evaluate(NOW, phone(), {"routine": "hourly", "pushed": False}, None, 0, tidy_run={"routine": "tidy", "pushed": False})
        self.assertEqual([w.T_NOT_PUSHED], [p["title"] for p in got])
        self.assertIn("매시간 분석, 새벽 정리", got[0]["body"])
        # Before v8 there is no such field: nothing.
        self.assertEqual([], self.run_rules([at(15, 36)], hourly={"routine": "hourly", "cron": w.DEFAULT_CRON}))

    def test_both_routines_behind_make_one_issue(self):
        v = {"versionCode": 1040, "promptVersion": "8", "tidyVersion": "6"}
        got = w.evaluate(NOW, phone(), {"routine": "hourly", "processed": 1, "promptVersion": "7"}, None, 0, version=v,
                         tidy_run={"routine": "tidy", "processed": 1, "promptVersion": "5"})
        self.assertEqual([w.T_ROUTINE_OLD], [p["title"] for p in got])
        self.assertIn("hourly.md", got[0]["body"])
        self.assertIn("tidy.md", got[0]["body"])
        ok = w.evaluate(NOW, phone(), {"routine": "hourly", "processed": 1, "promptVersion": "8"}, None, 0, version=v,
                        tidy_run={"routine": "tidy", "processed": 1, "promptVersion": "6"})
        self.assertEqual([], ok)

    def write(self, root, rel, data):
        os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
        with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
            json.dump(data, f)

    def test_each_routine_reads_its_own_file_then_the_old_one(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual((None, None), w.status_reports(root))
            self.write(root, "status/last-run.json", {"routine": "tidy", "promptVersion": "5"})
            self.assertEqual((None, {"routine": "tidy", "promptVersion": "5"}), w.status_reports(root))
            self.write(root, "status/last-run.json", {"routine": "hourly", "promptVersion": "7"})
            self.assertEqual(({"routine": "hourly", "promptVersion": "7"}, None), w.status_reports(root))
            self.write(root, "status/last-run-tidy.json", {"routine": "tidy", "promptVersion": "6"})
            self.write(root, "status/last-run-hourly.json", {"routine": "hourly", "promptVersion": "8"})
            self.assertEqual(({"routine": "hourly", "promptVersion": "8"}, {"routine": "tidy", "promptVersion": "6"}), w.status_reports(root))

    class FakeGh:
        def __init__(self, issues, list_ok=True):
            self.issues, self.list_ok, self.calls = issues, list_ok, []

        def __call__(self, args):
            self.calls.append(args)
            if args[:2] == ["-X", "GET"]:
                return (0, json.dumps(self.issues)) if self.list_ok else (1, "")
            return 0, "{}"

    def test_apply_opens_and_closes_through_the_rest_api(self):
        gh = self.FakeGh([{"number": 7, "title": w.T_TOKEN}, {"number": 9, "title": w.T_PHONE, "pull_request": {}}])
        problems = [{"title": w.T_ROUTINE_MISSED, "body": "b1"}]
        p, opened, closed = w.apply_plan("o/r", problems, gh)
        self.assertEqual({"create": problems, "close": [7]}, p)  # a pull request is no issue
        self.assertEqual((1, 1), (opened, closed))
        self.assertEqual(["-X", "GET", "repos/o/r/issues", "-f", "labels=ieum-watch", "-f", "state=open", "-f", "per_page=100"], gh.calls[0])
        self.assertIn(["-X", "POST", "repos/o/r/issues", "-f", f"title={w.T_ROUTINE_MISSED}", "-f", "body=b1", "-f", "labels[]=ieum-watch"], gh.calls)
        self.assertIn(["-X", "PATCH", "repos/o/r/issues/7", "-f", "state=closed"], gh.calls)
        # An issue already open for the problem: nothing, not even a comment.
        gh2 = self.FakeGh([{"number": 3, "title": w.T_ROUTINE_MISSED}])
        self.assertEqual(({"create": [], "close": []}, 0, 0), w.apply_plan("o/r", problems, gh2))
        self.assertEqual(1, len(gh2.calls))
        # The open issues cannot be listed: nothing is opened (it would make a second copy of each).
        gh3 = self.FakeGh([], list_ok=False)
        with self.assertRaises(RuntimeError):
            w.apply_plan("o/r", problems, gh3)
        self.assertEqual(1, len(gh3.calls))

    def test_cli_apply_in_a_repository(self):
        with tempfile.TemporaryDirectory() as root:
            env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@e", GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@e")
            subprocess.run(["git", "init", "-q", root], check=True)

            def commit(rel, data, at_ms):
                self.write(root, rel, data)
                stamp = f"@{at_ms // 1000} +0000"
                e = dict(env, GIT_AUTHOR_DATE=stamp, GIT_COMMITTER_DATE=stamp)
                subprocess.run(["git", "-C", root, "add", rel], check=True, env=e)
                subprocess.run(["git", "-C", root, "commit", "-q", "-m", "status", "--", rel], check=True, env=e)

            commit("status/phone.json", phone(), NOW - H)
            commit("status/last-run-hourly.json", {"routine": "hourly", "processed": 0, "cron": w.DEFAULT_CRON, "pushed": True}, at(12, 36))
            commit("status/last-run-tidy.json", {"routine": "tidy", "processed": 3, "pushed": True}, at(6, 10))
            gh = self.FakeGh([])
            out = os.path.join(root, "plan.json")
            self.assertEqual(0, w.main(["--root", root, "--now-ms", str(NOW), "--apply", "o/r", "--out", out], gh=gh))
            with open(out, encoding="utf-8") as f:
                self.assertEqual([w.T_ROUTINE_MISSED], [p["title"] for p in json.load(f)["create"]])
            # The 15:34 run reports: the issue closes on the next run.
            commit("status/last-run-hourly.json", {"routine": "hourly", "processed": 1, "cron": w.DEFAULT_CRON, "pushed": True}, at(15, 37))
            gh = self.FakeGh([{"number": 4, "title": w.T_ROUTINE_MISSED}])
            self.assertEqual(0, w.main(["--root", root, "--now-ms", str(NOW), "--apply", "o/r", "--out", out], gh=gh))
            with open(out, encoding="utf-8") as f:
                self.assertEqual({"create": [], "close": [4]}, json.load(f))
            self.assertEqual(1, w.main(["--root", root, "--now-ms", str(NOW), "--apply", "o/r", "--out", out], gh=self.FakeGh([], list_ok=False)))


def kakao(linked=True, last=None, sent=0, failed=0, error=None, on=None):
    k = {"linked": linked, "sent": sent, "failed": failed, "lastSentMs": last, "error": error}
    if on is not None:
        k["on"] = on
    return k


class V35ReportTest(unittest.TestCase):
    """DESIGN_V5 2.2-4: the morning report file and the KakaoTalk send."""
    TIDY7 = {"routine": "tidy", "processed": 3, "promptVersion": "7", "endedMs": at(4, 58), "reportWritten": False, "pushed": True}

    def report(self, now, tidy=TIDY7, exists=False, ph=None, history=None):
        got = w.evaluate(now, ph if ph is not None else phone(atMs=now - H), {"routine": "hourly", "processed": 1}, None, 0,
                         tidy_run=tidy, report_exists=exists, history=history)
        return [p for p in got if p["title"] in (w.T_REPORT_MISSING, w.T_KAKAO_NOT_SENT, w.T_KAKAO_UNLINKED)]

    def test_kakao_not_sent_only_while_the_switch_is_on(self):
        """r15-4 (M2): kakao.on false (the switch off) says nothing; on true or missing (an older heartbeat) as before."""
        linked_long = [phone(atMs=at(10, 0, day=-2), kakao=kakao(last=at(5, 30, day=-2)))]
        for on, want in ((False, []), (True, [w.T_KAKAO_NOT_SENT]), (None, [w.T_KAKAO_NOT_SENT])):
            ph = phone(atMs=at(8, 50), kakao=kakao(last=at(5, 31, day=-2), on=on))
            self.assertEqual(want, [p["title"] for p in self.report(at(9, 1), exists=True, ph=ph, history=linked_long)], on)

    def test_kakao_unlinked_by_a_token_error(self):
        """r15-4 (M2): linked=false with a TOKEN_ error is an issue at any hour; another error, linked, or the switch off is not."""
        got = self.report(at(7, 0), exists=True, ph=phone(atMs=at(6, 50), kakao=kakao(linked=False, error="TOKEN_EXPIRED")))
        self.assertEqual([w.T_KAKAO_UNLINKED], [p["title"] for p in got])
        self.assertIn("TOKEN_EXPIRED", got[0]["body"]);self.assertIn("다시 연결", got[0]["body"])
        self.assertEqual([], self.report(at(7, 0), exists=True, ph=phone(atMs=at(6, 50), kakao=kakao(linked=False, error="SEND_IO"))))
        self.assertEqual([], self.report(at(7, 0), exists=True, ph=phone(atMs=at(6, 50), kakao=kakao(linked=False, error=None))))
        self.assertEqual([], self.report(at(7, 0), exists=True, ph=phone(atMs=at(6, 50), kakao=kakao(linked=False, error="TOKEN_EXPIRED", on=False))))
        # Past 09:00 the unlinked issue alone (not also "안 갔어요": the link is down, not the send).
        got = self.report(at(9, 30), exists=True, ph=phone(atMs=at(9, 10), kakao=kakao(linked=False, error="TOKEN_X", last=at(5, 31, day=-3))))
        self.assertEqual([w.T_KAKAO_UNLINKED], [p["title"] for p in got])

    def test_kst_date(self):
        self.assertEqual("2027-01-15", w.kst_date(NOW))
        self.assertEqual(KST_MIDNIGHT, w.kst_day_start(NOW))
        self.assertEqual("2027-01-14", w.kst_date(KST_MIDNIGHT - 1))

    def test_report_missing_after_half_past_five(self):
        got = self.report(at(5, 31))
        self.assertEqual([w.T_REPORT_MISSING], [p["title"] for p in got])
        self.assertIn("report/2027-01-15.json", got[0]["body"])
        self.assertIn("04:58", got[0]["body"])
        self.assertIn("reportWritten=false", got[0]["body"])
        self.assertEqual([], self.report(at(5, 29)))  # not due yet
        self.assertEqual([], self.report(at(5, 31), exists=True))
        self.assertEqual([], self.report(at(5, 31), exists=None))  # not checked
        # A tidy still on prompt 6 (the release day) writes no report: no issue.
        self.assertEqual([], self.report(at(5, 31), tidy=dict(self.TIDY7, promptVersion="6")))
        self.assertEqual([], self.report(at(5, 31), tidy=None))
        # Today's tidy did not run: the body says the last run was yesterday.
        got = self.report(at(6, 0), tidy=dict(self.TIDY7, endedMs=at(4, 58, day=-1)))
        self.assertIn("자가 보고가 없어요(마지막 실행 25.0시간 전)", got[0]["body"])

    def test_report_rule_through_the_cli(self):
        with tempfile.TemporaryDirectory() as root:
            for rel, data in (("status/phone.json", phone(atMs=at(5, 0))), ("status/last-run-tidy.json", self.TIDY7)):
                os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
                with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                    json.dump(data, f)
            out = os.path.join(root, "out.json")

            def run():
                self.assertEqual(0, w.main(["--root", root, "--now-ms", str(at(5, 40)), "--out", out]))
                with open(out, encoding="utf-8") as f:
                    return [p["title"] for p in json.load(f)]
            self.assertIn(w.T_REPORT_MISSING, run())
            os.makedirs(os.path.join(root, "report"))
            with open(os.path.join(root, "report", "2027-01-15.json"), "w", encoding="utf-8") as f:
                f.write("{}")
            self.assertNotIn(w.T_REPORT_MISSING, run())

    def test_kakao_not_sent_two_days_in_a_row(self):
        linked_long = [phone(atMs=at(10, 0, day=-2), kakao=kakao(last=at(5, 30, day=-2)))]
        two_days = kakao(last=at(5, 31, day=-2), sent=4, failed=2, error="KAKAO_HTTP_401")
        got = self.report(at(9, 1), exists=True, ph=phone(atMs=at(8, 50), kakao=two_days), history=linked_long)
        self.assertEqual([w.T_KAKAO_NOT_SENT], [p["title"] for p in got])
        self.assertIn("KAKAO_HTTP_401", got[0]["body"])
        self.assertIn("실패 횟수 2", got[0]["body"])
        # Before 09:00 the phone may still be trying.
        self.assertEqual([], self.report(at(8, 59), exists=True, ph=phone(atMs=at(8, 50), kakao=two_days), history=linked_long))
        # Sent yesterday, not today: one day only, no issue yet.
        one_day = kakao(last=at(5, 31, day=-1))
        self.assertEqual([], self.report(at(9, 1), exists=True, ph=phone(atMs=at(8, 50), kakao=one_day), history=linked_long))
        # Sent today.
        self.assertEqual([], self.report(at(9, 1), exists=True, ph=phone(atMs=at(8, 50), kakao=kakao(last=at(5, 31))), history=linked_long))
        # Not linked (the feature off, or the link broke: the phone's own lights say so).
        self.assertEqual([], self.report(at(9, 1), exists=True, ph=phone(atMs=at(8, 50), kakao=kakao(linked=False, last=at(5, 31, day=-3)))))
        # Older heartbeats without the kakao block (before v3.5).
        self.assertEqual([], self.report(at(9, 1), exists=True, ph=phone(atMs=at(8, 50))))

    def test_kakao_linked_only_since_yesterday_noon_is_one_morning(self):
        never = kakao(last=None)
        recent = [phone(atMs=at(12, 0, day=-1), kakao=never), phone(atMs=at(10, 0, day=-1), kakao=kakao(linked=False))]
        self.assertEqual([], self.report(at(9, 30), exists=True, ph=phone(atMs=at(9, 10), kakao=never), history=recent))
        # Linked since before yesterday's 09:00 and never sent: an issue.
        early = [phone(atMs=at(8, 0, day=-1), kakao=never)]
        got = self.report(at(9, 30), exists=True, ph=phone(atMs=at(9, 10), kakao=never), history=early)
        self.assertEqual([w.T_KAKAO_NOT_SENT], [p["title"] for p in got])
        self.assertIn("보낸 적이 없어요", got[0]["body"])

    def test_silent_phone_says_nothing_about_kakao(self):
        old = phone(atMs=at(9, 30) - 13 * H, kakao=kakao(last=at(5, 31, day=-3)))
        self.assertEqual([], self.report(at(9, 30), exists=True, ph=old, history=[phone(atMs=at(5, 0, day=-3), kakao=kakao())]))


def pw(today=None, prev=None, waiting=None, **kw):
    """A v3.6 power block (DESIGN_V6 3.1)."""
    p = {"batteryPct": 70, "plugged": "none", "today": dict({"day": 20270115}, **(today or {})),
         "prev": dict({"day": 20270114}, **prev) if prev is not None else None,
         "waiting": waiting or {"n": 0, "oldestMin": None}, "chargingOnly": False}
    p.update(kw)
    return p


class V36PowerTest(unittest.TestCase):
    """DESIGN_V6 3.3: the battery, the charger wait and the wasted pieces."""
    POWER = (w.T_BATTERY_HEAVY, w.T_CHARGER_WAIT, w.T_PIECES_WASTED)

    def got(self, ph, history=None):
        return [p for p in w.evaluate(NOW, ph, {"processed": 1}, None, 0, history=history) if p["title"] in self.POWER]

    def test_older_heartbeats_and_a_quiet_phone_say_nothing(self):
        self.assertEqual([], self.got(phone()))
        self.assertEqual([], self.got(phone(power=pw())))
        self.assertEqual([], self.got(phone(power={"today": None, "waiting": "x"})))
        # A silent phone (over 12 hours) is told by its own rule only.
        self.assertEqual([], self.got(phone(atMs=NOW - 13 * H, power=pw(today={"battery": {"min": 90}}, prev={"battery": {"min": 90}}))))

    def test_battery_heavy_two_days(self):
        heavy = {"battery": {"min": 75, "mAhScreenOff": 640}}
        got = self.got(phone(power=pw(today=heavy, prev={"battery": {"min": 61}})))
        self.assertEqual([w.T_BATTERY_HEAVY], [p["title"] for p in got])
        self.assertIn("75분", got[0]["body"]);self.assertIn("640mAh", got[0]["body"])
        # One day only, or exactly 60.
        self.assertEqual([], self.got(phone(power=pw(today=heavy, prev={"battery": {"min": 20}}))))
        self.assertEqual([], self.got(phone(power=pw(today={"battery": {"min": 60}}, prev={"battery": {"min": 90}}))))
        # prev missing, an earlier heartbeat of another day was heavy too.
        old = phone(atMs=NOW - 20 * H, power=pw(today=dict(heavy, day=20270114)))
        self.assertEqual([w.T_BATTERY_HEAVY], [p["title"] for p in self.got(phone(power=pw(today=heavy)), history=[old])])
        # An earlier heartbeat of the same day does not make two days.
        same = phone(atMs=NOW - 3 * H, power=pw(today=heavy))
        self.assertEqual([], self.got(phone(power=pw(today=heavy)), history=[same]))

    def test_charger_wait_over_36_hours(self):
        got = self.got(phone(power=pw(waiting={"n": 4, "oldestMin": 37 * 60})))
        self.assertEqual([w.T_CHARGER_WAIT], [p["title"] for p in got])
        self.assertIn("4개", got[0]["body"]);self.assertIn("37시간", got[0]["body"])
        self.assertEqual([], self.got(phone(power=pw(waiting={"n": 4, "oldestMin": 36 * 60}))))
        self.assertEqual([], self.got(phone(power=pw(waiting={"n": 0, "oldestMin": 40 * 60}))))

    def test_charger_waiting_is_not_a_queue_stuck(self):
        late = dict(newQueuedCount=2, newOldestQueuedMs=NOW - 8 * H)
        self.assertEqual([w.T_QUEUE], titles(phone=phone(**late)))
        self.assertEqual([], titles(phone=phone(power=pw(waiting={"n": 2, "oldestMin": 8 * 60}), **late)))
        self.assertEqual([w.T_QUEUE], titles(phone=phone(power=pw(waiting={"n": 1, "oldestMin": 8 * 60}), **late)))

    def test_wasted_pieces_two_days(self):
        bad = {"battery": {"ms": 20 * w.MINUTE, "min": 20}, "plugged": {"ms": 80 * w.MINUTE, "min": 80}, "killedChunkMs": 15 * w.MINUTE, "deadlineMs": 6 * w.MINUTE}
        got = self.got(phone(power=pw(today=bad, prev=bad)))
        self.assertEqual([w.T_PIECES_WASTED], [p["title"] for p in got])
        self.assertIn("21%", got[0]["body"])
        ok = dict(bad, killedChunkMs=10 * w.MINUTE, deadlineMs=0)
        self.assertEqual([], self.got(phone(power=pw(today=bad, prev=ok))))
        self.assertEqual([], self.got(phone(power=pw(today=bad))))
        # Minutes only (no ms): run time from the minutes.
        mins = {"battery": {"min": 10}, "plugged": {"min": 40}, "killedChunkMs": 11 * w.MINUTE}
        self.assertEqual([w.T_PIECES_WASTED], [p["title"] for p in self.got(phone(power=pw(today=mins, prev=mins)))])
        # No run time at all.
        self.assertEqual([], self.got(phone(power=pw(today={"killedChunkMs": 5}, prev={"killedChunkMs": 5}))))


if __name__ == "__main__":
    unittest.main()
