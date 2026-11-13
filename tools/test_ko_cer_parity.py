"""r18-bench (DESIGN_V7 4.4): tools/eval/ko_cer.py and the phone's KoCer.kt give the same normalized text and character
edits for the inputs pinned in tools/eval/ko_cer_parity.json (the Kotlin side is KoCerTest). jiwer is only needed by
ko_cer.cer(); without it installed, the edits are checked with a plain Levenshtein (what jiwer.process_characters counts)."""
import importlib.util, json, pathlib, sys, types, unittest

ROOT = pathlib.Path(__file__).resolve().parent
FIXTURE = ROOT / 'eval' / 'ko_cer_parity.json'


def load_ko_cer():
    try:
        import jiwer  # noqa: F401
        real = True
    except ImportError:
        sys.modules.setdefault('jiwer', types.ModuleType('jiwer'))
        real = False
    spec = importlib.util.spec_from_file_location('ko_cer_under_test', ROOT / 'eval' / 'ko_cer.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, real


def levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


class KoCerParity(unittest.TestCase):
    def setUp(self):
        self.ko, self.real_jiwer = load_ko_cer()
        self.cases = json.loads(FIXTURE.read_text(encoding='utf-8'))['cases']

    def test_fixture_has_cases(self):
        self.assertGreaterEqual(len(self.cases), 15)

    def test_normalize_matches_fixture(self):
        for c in self.cases:
            self.assertEqual(self.ko.normalize(c['ref']), c['refNorm'], c['ref'])
            self.assertEqual(self.ko.normalize(c['hyp']), c['hypNorm'], c['hyp'])

    def test_edits_match_fixture(self):
        for c in self.cases:
            self.assertEqual(levenshtein(c['refNorm'], c['hypNorm']), c['edits'], c['ref'])
            if self.real_jiwer and c['refNorm']:
                _, edits, _ = self.ko.cer([c['refNorm']], [c['hypNorm']])
                self.assertEqual(edits, c['edits'], c['ref'])


if __name__ == '__main__':
    unittest.main()
