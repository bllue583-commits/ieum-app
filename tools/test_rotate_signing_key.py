"""tools/rotate_signing_key.sh with throwaway seeds (r18-lock): the lineage is made once from the old key, every later
APK is signed with the new key and that stored lineage only, and `check` refuses what would not chain.
Runs where build-tools 35 (apksigner, aapt2), android-36 and the cryptography package are there; skipped otherwise.
Never uses a real seed: every value here is random and lives only in this test's temporary folder."""
import os, secrets, shutil, subprocess, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "rotate_signing_key.sh")
DERIVE = os.path.join(HERE, "derive_signing_key.py")
SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
BT = os.path.join(SDK, "build-tools", "35.0.0")
JAR = os.path.join(SDK, "platforms", "android-36", "android.jar")


def usable():
    try:
        import cryptography  # noqa: F401
    except ImportError:
        return False
    return all(os.path.exists(p) for p in (os.path.join(BT, "apksigner"), os.path.join(BT, "aapt2"), JAR))


@unittest.skipUnless(usable(), "needs build-tools 35.0.0, android-36 and cryptography")
class RotateSigningKeyTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d)
        manifest = os.path.join(self.d, "AndroidManifest.xml")
        with open(manifest, "w") as f:
            f.write('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="kr.callbrief.sigtest" '
                    'android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="26" '
                    'android:targetSdkVersion="36"/><application android:hasCode="false"/></manifest>')
        self.base = os.path.join(self.d, "base.apk")
        subprocess.run([os.path.join(BT, "aapt2"), "link", "-I", JAR, "--manifest", manifest, "-o", self.base], check=True)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("IEUM_")}
        self.env.update(APKSIGNER=os.path.join(BT, "apksigner"), JAVA_TOOL_OPTIONS="",
                        IEUM_INBOX_TOKEN="ghp_" + secrets.token_hex(20), IEUM_SIGNING_SEED_V2=secrets.token_hex(24))

    def run_sh(self, *args, ok=True, env=None):
        r = subprocess.run(["bash", SCRIPT, *args], env=env or self.env, capture_output=True, text=True)
        if ok:
            self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return r

    def apk(self, name):
        p = os.path.join(self.d, name)
        shutil.copy(self.base, p)
        return p

    def sign_with_p12(self, apk, p12):
        subprocess.run([self.env["APKSIGNER"], "sign", "--ks", p12, "--ks-type", "PKCS12", "--ks-key-alias", "ieum",
                        "--ks-pass", "pass:ieum-signing", "--key-pass", "pass:ieum-signing", apk],
                       env=self.env, check=True, capture_output=True)

    def current_release(self):
        """What every release so far is: the key derived from the token (IEUM_SIGNING_SEED empty)."""
        p12 = os.path.join(self.d, "current.p12")
        subprocess.run(["python3", DERIVE, p12], env=self.env, check=True, capture_output=True)
        prev = self.apk("prev.apk")
        self.sign_with_p12(prev, p12)
        os.remove(p12)
        return prev

    def test_lineage_once_then_new_key_only(self):
        prev = self.current_release()
        old = self.run_sh("fp", prev).stdout.strip()
        k = os.path.join(self.d, "k"); lin = os.path.join(self.d, "signing-lineage.bin")
        self.run_sh("newkey", k)
        new = open(os.path.join(k, "new.sha256")).read().strip()
        self.run_sh("lineage", k, lin)
        self.assertEqual(["new.p12", "new.sha256"], sorted(os.listdir(k)))  # the old key is gone at once
        self.assertEqual([old, new], self.run_sh("certs", lin).stdout.split())
        # The token is no longer needed from here on: sign twice with the new key and the stored lineage only.
        env = dict(self.env); env.pop("IEUM_INBOX_TOKEN")
        first = self.apk("first.apk"); self.run_sh("sign", k, lin, first, env=env)
        self.assertEqual(new, self.run_sh("fp", first).stdout.strip())
        self.run_sh("check", lin, new, prev, env=env)
        second = self.apk("second.apk"); self.run_sh("sign", k, lin, second, env=env)
        self.run_sh("check", lin, new, first, env=env)
        v = subprocess.run([self.env["APKSIGNER"], "verify", "-v", second], env=env, capture_output=True, text=True).stdout
        self.assertIn("Verified using v3 scheme (APK Signature Scheme v3): true", v)
        self.assertIn("Verified using v2 scheme (APK Signature Scheme v2): false", v)  # v3 only (Android 9+)

    def test_check_refuses_what_does_not_chain(self):
        prev = self.current_release()
        k = os.path.join(self.d, "k"); lin = os.path.join(self.d, "signing-lineage.bin")
        self.run_sh("newkey", k); self.run_sh("lineage", k, lin)
        new = open(os.path.join(k, "new.sha256")).read().strip()
        # An unrelated previous release.
        other = os.path.join(self.d, "other.p12")
        subprocess.run(["python3", DERIVE, other], env=dict(self.env, IEUM_SIGNING_SEED=secrets.token_hex(24)), check=True, capture_output=True)
        unrelated = self.apk("unrelated.apk"); self.sign_with_p12(unrelated, other)
        self.assertNotEqual(0, self.run_sh("check", lin, new, unrelated, ok=False).returncode)
        # A lineage that does not end at this new key.
        self.assertNotEqual(0, self.run_sh("check", lin, "0" * 64, prev, ok=False).returncode)
        k2 = os.path.join(self.d, "k2")
        self.run_sh("newkey", k2, env=dict(self.env, IEUM_SIGNING_SEED_V2=secrets.token_hex(24)))
        self.assertNotEqual(0, self.run_sh("sign", k2, lin, self.apk("x.apk"), ok=False).returncode)
        # Nothing known about the previous release: refused while rotating.
        self.assertNotEqual(0, self.run_sh("check", lin, new, "", "", ok=False).returncode)
        # version.json only: the previous certificate, or this lineage file.
        vj = os.path.join(self.d, "version.json")
        with open(vj, "w") as f:
            f.write('{"certSha256": "%s"}' % self.run_sh("fp", prev).stdout.strip())
        self.run_sh("check", lin, new, "", vj)
        import hashlib
        with open(vj, "w") as f:
            f.write('{"certSha256": "%s", "lineageSha256": "%s"}' % (new, hashlib.sha256(open(lin, "rb").read()).hexdigest()))
        self.run_sh("check", lin, new, "", vj)
        with open(vj, "w") as f:
            f.write('{"certSha256": "%s", "lineageSha256": "%s"}' % (new, "1" * 64))
        self.assertNotEqual(0, self.run_sh("check", lin, new, "", vj, ok=False).returncode)

    def test_short_or_missing_new_seed(self):
        self.assertNotEqual(0, self.run_sh("newkey", os.path.join(self.d, "a"), ok=False,
                                           env=dict(self.env, IEUM_SIGNING_SEED_V2="short-value-only-20chars")).returncode)
        env = dict(self.env); env.pop("IEUM_SIGNING_SEED_V2")
        self.assertNotEqual(0, self.run_sh("newkey", os.path.join(self.d, "b"), ok=False, env=env).returncode)


if __name__ == "__main__":
    unittest.main()
