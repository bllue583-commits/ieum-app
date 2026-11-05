"""v3.4 r14-4 (DESIGN_V4 4.4-1): checks of the emulator call-card script tools/device/call_card_test.sh without an emulator.

The script itself runs in device-test; here: it parses, it has the eleven checks, the cold case uses `am kill` and never
`am force-stop`, every case keeps its logcat, and the locked-notice reader tells a card notice with words from others."""
import os
import re
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "device", "call_card_test.sh")
CHECKS = ["ringingWindow", "stripAfter3s", "goneAfterIdle", "serviceGone", "outgoing", "unknownOff", "personalNothing",
          "outgoingNoRole", "callWaiting", "coldWindow", "lockedNotice"]


def source():
    with open(SCRIPT, encoding="utf-8") as f:
        return f.read()


def commands(text):
    """The script's lines without comments (what it runs)."""
    return "\n".join(line.split(" #")[0] for line in text.splitlines() if not line.lstrip().startswith("#"))


def function(text, name):
    m = re.search(r"^%s\(\) \{\n.*?^\}\n" % re.escape(name), text, re.S | re.M)
    if not m:
        raise AssertionError(name + " not found")
    return m.group(0)


def notice_words(dump):
    """Runs the script's notice_words with `adb` answering [dump]."""
    fn = function(source(), "notice_words")
    prog = "pkg=kr.callbrief\nadb() { cat <<'EOF'\n%s\nEOF\n}\n%s\nnotice_words" % (dump, fn)
    return subprocess.run(["bash", "-c", prog]).returncode == 0


CARD = """  NotificationRecord(0x0aa: pkg=kr.callbrief user=UserHandle{0} id=3011 tag=callcard importance=4 key=0|kr.callbrief|3011|callcard|10190: Notification(channel=ieum.calls.card shortcut=null)
      mChannel=NotificationChannel{mId='ieum.calls.card', mName=이음 통화 카드}
      extras={
        android.title=String (length=3)
        android.text=String (length=14)
      }"""
OTHER = """  NotificationRecord(0x0bb: pkg=kr.callbrief user=UserHandle{0} id=741 tag=null importance=2 key=0|kr.callbrief|741|null|10190: Notification(channel=ieum.calls.service shortcut=null)
      mChannel=NotificationChannel{mId='ieum.calls.service', mName=통화 중}
      extras={
        android.title=String (length=5)
        android.text=String (length=9)
      }"""
FOREIGN = CARD.replace("pkg=kr.callbrief", "pkg=com.example.other")
NO_TEXT = """  NotificationRecord(0x0cc: pkg=kr.callbrief user=UserHandle{0} id=3011 tag=callcard importance=4 key=0|kr.callbrief|3011|callcard|10190: Notification(channel=ieum.calls.card shortcut=null)
      extras={
        android.title=String (length=3)
      }"""


class CallCardScriptTest(unittest.TestCase):
    def test_parses(self):
        self.assertEqual(0, subprocess.run(["bash", "-n", SCRIPT]).returncode)

    def test_eleven_checks_in_the_result(self):
        text = source()
        line = re.search(r'^checks="([^"]+)"', text, re.M).group(1).split()
        self.assertEqual(CHECKS, line)
        for c in CHECKS:
            self.assertIn("%s=true" % c, text, c + " is never set")

    def test_cold_is_made_with_am_kill_never_force_stop(self):
        run = commands(source())
        self.assertIn('am kill "$pkg"', run)
        self.assertNotIn("force-stop", run)

    def test_every_case_keeps_its_logcat(self):
        text = source()
        cases = re.findall(r"^begin ([\w-]+)$", text, re.M)
        kept = re.findall(r'^keep_log "([\w-]+)"$', text, re.M)
        self.assertEqual(["incoming", "outgoing", "outgoing-no-role", "unknown", "call-waiting", "personal", "cold", "locked"], cases)
        for c in cases:
            self.assertIn(c, kept)
        tags = function(text, "keep_log")
        for tag in ("CallCardService", "CallCardOverlay", "ContactCards", "CallSession"):
            self.assertIn(tag + ":V", tags)

    def test_no_role_case_takes_the_role_back_and_gives_it_again(self):
        run = commands(source())
        self.assertIn('remove-role-holder "$role" "$pkg"', run)
        self.assertLess(run.index('remove-role-holder "$role"'), run.rindex('add-role-holder "$role"'))

    def test_locked_notice_needs_the_card_channel_and_words(self):
        self.assertTrue(notice_words(CARD))
        self.assertTrue(notice_words(OTHER + "\n" + CARD))
        self.assertTrue(notice_words(CARD + "\n" + OTHER))
        self.assertFalse(notice_words(OTHER), "the service notice without the card is not the card")
        self.assertFalse(notice_words(FOREIGN), "another app's notice")
        self.assertFalse(notice_words(NO_TEXT), "a title alone")
        self.assertFalse(notice_words(""))

    def test_locked_notice_reads_both_dump_spellings(self):
        # API 34 writes the redacted lengths in square brackets.
        square = CARD.replace("String (length=3)", "String [length=3]").replace("String (length=14)", "String [length=14]")
        self.assertTrue(notice_words(square))
        self.assertFalse(notice_words(square.replace("android.text=String [length=14]", "")))

    def test_null_is_not_a_failure_but_false_is(self):
        text = source()
        self.assertIn('[ "${!c}" = false ] && exit 1', text)
        self.assertIn("callWaiting=null", text)


if __name__ == "__main__":
    unittest.main()
