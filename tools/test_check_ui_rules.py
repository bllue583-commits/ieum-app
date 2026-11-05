import os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import check_ui_rules as c


def kt(src):
    return [m for _, _, m in c.check_kotlin("X.kt", src)]


class UiRulesTest(unittest.TestCase):
    def test_clean(self):
        self.assertEqual([], kt('val a = "정리하고 있어요" // 가운데 점 · 주석\n/* … */ val b = 1'))

    def test_middle_dot_ellipsis(self):
        self.assertEqual(1, len(kt('val a = "공종 · 현장"')))
        self.assertEqual(1, len(kt('val a = "불러오는 중…"')))
        self.assertEqual(1, len(kt('val a = "불러오는 중..."')))

    def test_log_and_comment_ignored(self):
        self.assertEqual([], kt('Log.w(TAG, "받기 실패 · 다시 시도…")'))
        self.assertEqual([], kt('// "공종 · 현장"'))

    def test_red_colors(self):
        self.assertEqual(1, len(kt('val r = "#D63B3B"')))
        self.assertEqual(1, len(kt('val r = "#E5484D"')))
        self.assertEqual(1, len(kt('val r = 0xFFF04452')))
        self.assertEqual([], kt('val ok = "#3268F0"; val amber = "#F5A524"'))

    def test_banned_words(self):
        self.assertEqual(1, len(kt('tv.ellipsize = null')))
        self.assertEqual(1, len(kt('val x = WARN_SOFT')))
        self.assertEqual(1, len(kt('val t = "기한"')))
        self.assertEqual([], kt('val t = "기한이 지났어요"'.replace("기한이", "마감이")))

    def test_english_status(self):
        self.assertEqual(1, len(kt('val t = "상태 QUEUED"')))
        self.assertEqual([], kt('val t = "QUEUED"'))
        self.assertEqual([], kt('val t = "상태 ${state}"'))

    def test_xml(self):
        v = c.check_xml("strings.xml", '<string name="a">보내는 중…</string>\n<color name="r">#D63B3B</color>')
        self.assertEqual(2, len(v))

    def test_new_caption_use_is_warned_not_counted(self):
        # v3.3 (DESIGN_V4 3.3-2): a screen with no caption( before gets a warning for its first one; comments do not count.
        self.assertEqual([], c.caption_warnings("NewScreen.kt", 'val t = CallUi.text(a, "본문") // CallUi.caption(a, "주석")'))
        w = c.caption_warnings("NewScreen.kt", 'val t = CallUi.caption(a, "곁들이는 글")')
        self.assertEqual(1, len(w)); self.assertIn("warning", w[0][2])
        self.assertEqual([], c.caption_warnings("HomeScreen.kt", 'CallUi.caption(a, "하나"); CallUi.caption(a, "둘")'))
        self.assertEqual([], kt('val t = CallUi.caption(a, "곁들이는 글")'))


if __name__ == "__main__":
    unittest.main()
