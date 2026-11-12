"""r18 (user 11/13): the morning report's web view, docs/report/index.html.

The phone puts the masked report in the URL fragment (#d=): zlib deflate, then base64url without padding
(KakaoReport.packWeb; its own round trip is in KakaoReportTest). Here: the page's decoder (the DECODE block, run in node
when node 18 or later is there) gives back the same JSON from a fragment made the phone's way, and the page calls nothing
outside itself (no script, font, image or analytics address, a policy that forbids requests, noindex, no-referrer).
"""
import base64, json, os, re, shutil, subprocess, tempfile, unittest, zlib

PAGE = os.path.join(os.path.dirname(__file__), "..", "docs", "report", "index.html")


def page():
    with open(PAGE, encoding="utf-8") as f:
        return f.read()


def pack(obj):
    """What the phone does: compact JSON, zlib at level 9, base64url without '='."""
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(zlib.compress(raw, 9)).decode("ascii").rstrip("=")


SAMPLE = {"v": 1, "day": "2026-11-13", "label": "11/13(금)", "phone": False,
          "head": {"calls": 7, "newChecks": 3, "todayChecks": 2, "issues": 1, "pendingCalls": 0},
          "today": [{"what": "견적서 보내기", "who": "△△철강", "since": "어제 요청", "status": "", "tag": "important"},
                    {"what": "안전 점검 서류", "who": "본사", "since": "3일 전 요청", "status": "메일 발송 확인 완료", "tag": "request"}],
          "inProgress": [{"what": "철근 단가 재견적: 업체 회신 대기"}], "check": [{"what": "13층/30층 중 어느 쪽인지", "who": "△△철강"}],
          "yesterday": [{"who": "○○전기", "what": "배선 자재 내일 입고"}], "late": [], "cut": True}


class ReportPageTest(unittest.TestCase):
    def test_nothing_leaves_the_page(self):
        text = page()
        self.assertIn('<meta name="robots" content="noindex', text)
        self.assertIn('<meta name="referrer" content="no-referrer">', text)
        self.assertIn("default-src 'none'", text)
        self.assertIn("connect-src 'none'", text)
        self.assertNotRegex(text, r"<script[^>]+src=", "no outside script")
        self.assertNotRegex(text, r"<link[^>]+href=", "no outside style or font")
        self.assertNotRegex(text, r"https?://", "no address at all")
        self.assertNotIn("innerHTML", text, "the report's words are set as text only")
        self.assertNotIn("…", text)
        self.assertIn("prefers-color-scheme: dark", text)
        self.assertIn("width=device-width", text)
        self.assertIn("나머지는 이음에서", text)
        for tag in ("important", "confirm", "request", "regular", "optional"):
            self.assertIn(f"--t-{tag}", text)
        for gone in ("urgent", "waiting"):
            self.assertNotIn(gone, text)

    def test_pack_is_base64url_without_padding(self):
        d = pack(SAMPLE)
        self.assertRegex(d, r"^[A-Za-z0-9_-]+$")
        pad = "=" * (-len(d) % 4)
        self.assertEqual(SAMPLE, json.loads(zlib.decompress(base64.urlsafe_b64decode(d + pad)).decode("utf-8")))

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_the_page_decodes_the_phone_fragment(self):
        block = re.search(r"/\* DECODE:BEGIN.*?\*/(.*?)/\* DECODE:END \*/", page(), re.S).group(1)
        version = subprocess.run(["node", "-e", "process.stdout.write(String(typeof DecompressionStream))"],
                                 capture_output=True, text=True, timeout=30).stdout
        if version != "function":
            self.skipTest("node has no DecompressionStream")
        with tempfile.TemporaryDirectory() as tmp:
            script = os.path.join(tmp, "decode.js")
            with open(script, "w", encoding="utf-8") as f:
                f.write(block + "\ndecodeReport(process.argv[2]).then(r=>process.stdout.write(JSON.stringify(r)),"
                        "e=>{console.error(String(e));process.exit(1)});\n")
            out = subprocess.run(["node", script, pack(SAMPLE)], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual(SAMPLE, json.loads(out.stdout))


if __name__ == "__main__":
    unittest.main()
