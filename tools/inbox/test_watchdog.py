import os, sys, unittest
sys.path.insert(0, os.path.dirname(__file__))
import watchdog as w

H = w.HOUR
NOW = 1_800_000_000_000


def titles(**kw):
    args = dict(phone={"atMs": NOW - H}, last_run={"processed": 3}, inbox_oldest_ms=None, inbox_count=0)
    args.update(kw)
    return [p["title"] for p in w.evaluate(NOW, args["phone"], args["last_run"], args["inbox_oldest_ms"], args["inbox_count"], args.get("token_expiry_ms"))]


class WatchdogTest(unittest.TestCase):
    def test_healthy(self):
        self.assertEqual([], titles())

    def test_phone_silent(self):
        self.assertEqual([w.T_PHONE], titles(phone={"atMs": NOW - 13 * H}))
        self.assertEqual([], titles(phone={"atMs": NOW - 11 * H}))
        self.assertEqual([w.T_PHONE], titles(phone=None))

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

    def test_token_expiry(self):
        self.assertEqual([w.T_TOKEN], titles(token_expiry_ms=NOW + 10 * 24 * H))
        self.assertEqual([], titles(token_expiry_ms=NOW + 20 * 24 * H))

    def test_expiry_header(self):
        self.assertIsNone(w.parse_expiry(""))
        self.assertIsNone(w.parse_expiry("nonsense"))
        self.assertEqual(1764583200000, w.parse_expiry("2025-12-01 10:00:00 UTC"))

    def test_no_content_in_bodies(self):
        for p in w.evaluate(NOW, {"atMs": 0, "oldestQueuedMs": 1, "lastUpdateError": "X"}, {"processed": 0, "promptMismatch": True, "limitHit": True}, 1, 3, NOW):
            self.assertNotIn("http", p["body"])


if __name__ == "__main__":
    unittest.main()
