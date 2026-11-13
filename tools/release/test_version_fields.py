import json, os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import version_fields as v

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CERT = "AB:" * 31 + "CD"


class VersionFieldsTest(unittest.TestCase):
    def fake_root(self, tidy_head="지시문 버전 4", prompt="        const val PROMPT_VERSION=6\n"):
        d = tempfile.mkdtemp()
        os.makedirs(os.path.join(d, os.path.dirname(v.ANALYZER)))
        os.makedirs(os.path.join(d, "docs/routines"))
        with open(os.path.join(d, v.ANALYZER), "w", encoding="utf-8") as f:
            f.write("object X {\n" + prompt + "}\n")
        with open(os.path.join(d, v.TIDY), "w", encoding="utf-8") as f:
            f.write(f"# 이음 새벽 정리 (매일) 지시문\n{tidy_head}\n\n---\n\n지시문 버전 99\n")
        return d

    def test_the_repository_itself_has_both_versions(self):
        got = v.fields(ROOT)
        self.assertTrue(got["promptVersion"].isdigit())
        self.assertTrue(got["tidyVersion"].isdigit())
        self.assertNotIn("certSha256", got)

    def test_values_and_cert(self):
        root = self.fake_root()
        self.assertEqual({"promptVersion": "6", "tidyVersion": "4", "certSha256": "ab" * 31 + "cd"}, v.fields(root, CERT))
        self.assertEqual("", v.clean_cert("not a digest"))
        self.assertNotIn("certSha256", v.fields(root, "xyz"))

    def test_only_the_head_counts_and_missing_is_an_error(self):
        with self.assertRaises(ValueError):
            v.tidy_version(self.fake_root(tidy_head="버전 없음"))
        with self.assertRaises(ValueError):
            v.prompt_version(self.fake_root(prompt="val OTHER=1\n"))

    def test_merge_keeps_the_other_keys(self):
        root = self.fake_root()
        path = os.path.join(root, "version.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"versionCode": 1045, "versionName": "v3.1", "apk": "releases/assets/1", "sha256": "x", "size": 9, "notes": "새 버전"}, f)
        self.assertEqual(0, v.main(["--root", root, "--cert", CERT, "--merge", path]))
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(1045, data["versionCode"])
        self.assertEqual("새 버전", data["notes"])
        self.assertEqual("6", data["promptVersion"])
        self.assertEqual("4", data["tidyVersion"])
        self.assertEqual(64, len(data["certSha256"]))
        self.assertEqual(1, v.main(["--root", self.fake_root(tidy_head="")]))

    def test_build_type_seed_independence_and_lineage(self):
        root = self.fake_root()
        lin = os.path.join(root, "signing-lineage.bin")
        with open(lin, "wb") as f:
            f.write(b"lineage bytes")
        import hashlib
        got = v.fields(root, CERT, "release", True, lin)
        self.assertEqual("release", got["buildType"])
        self.assertIs(True, got["seedIndependent"])
        self.assertEqual(hashlib.sha256(b"lineage bytes").hexdigest(), got["lineageSha256"])
        off = v.fields(root, CERT, "release", False)
        self.assertIs(False, off["seedIndependent"])
        self.assertNotIn("lineageSha256", off)
        with self.assertRaises(ValueError):
            v.fields(root, CERT, "release", True)  # rotated without its lineage file
        with self.assertRaises(ValueError):
            v.fields(root, CERT, "profile")
        path = os.path.join(root, "version.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"versionCode": 1060}, f)
        self.assertEqual(0, v.main(["--root", root, "--cert", CERT, "--build-type", "release", "--seed-independent", "false", "--merge", path]))
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(("release", False), (data["buildType"], data["seedIndependent"]))
        self.assertEqual(1, v.main(["--root", root, "--seed-independent", "true"]))

    def test_scorecard_reads_these_keys(self):
        """tools/inbox/score.py item 8 reads buildType and seedIndependent from app/version.json."""
        with open(os.path.join(ROOT, "tools/inbox/score.py"), encoding="utf-8") as f:
            score = f.read()
        for key in ("buildType", "seedIndependent"):
            self.assertIn(f'(version or {{}}).get("{key}")', score)


if __name__ == "__main__":
    unittest.main()
