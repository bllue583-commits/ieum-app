"""r19 자막 실험실의 순수 로직 시험: python3 -m unittest discover -s tools -p 'test_*.py' (jiwer·rapidfuzz·numpy 필요)."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, 'asrlab'))
sys.path.insert(0, HERE)

try:
    import numpy as np
    import labscore as L
    import apppipe as P
    HAVE = True
except ImportError:  # 실험실 의존성이 없는 환경
    HAVE = False


@unittest.skipUnless(HAVE, 'numpy/rapidfuzz/jiwer 없음')
class AlignTest(unittest.TestCase):
    def test_split_exact(self):
        refs = ['가나다', '라마', '바사아']
        self.assertEqual(L.split_by_ref(refs, '가나다라마바사아'), refs)

    def test_split_with_errors_keeps_total(self):
        refs = [L.normalize('견적서를 보내 드릴게요'), L.normalize('삼십만 원입니다')]
        hyp = L.normalize('견척서를 보내드릴께요 30만원 입니다 네')
        parts = L.split_by_ref(refs, hyp)
        self.assertEqual(''.join(parts), hyp)
        self.assertIn('300000', parts[1])

    def test_insertion_between_goes_to_previous(self):
        parts = L.split_by_ref(['가나', '다라'], '가나xx다라')
        self.assertEqual(parts, ['가나xx', '다라'])

    def test_edits_match_jiwer(self):
        import jiwer
        r, h = '오늘견적서를보냅니다', '오늘견척서보냅니다요'
        o = jiwer.process_characters([r], [h])
        self.assertEqual(L.edits(r, h), o.substitutions + o.deletions + o.insertions)


@unittest.skipUnless(HAVE, 'numpy/rapidfuzz/jiwer 없음')
class NumbersNamesTest(unittest.TestCase):
    def test_number_hits_multiset(self):
        r = L.normalize('3시에 30만 원, 3시에 다시')
        h = L.normalize('세 시에 삼십만 원 4시에 다시')
        self.assertEqual(L.number_hits(r, h), (2, 3))

    def test_name_hits(self):
        names = [L.normalize('한결테크'), L.normalize('도윤')]
        self.assertEqual(L.name_hits(L.normalize('한결테크 도윤 과장'), L.normalize('한결텍 도윤 과장'), names), (1, 2))

    def test_disagreements_flag_numbers(self):
        d = L.disagreements('300000원입니다', '350000원입니다', 0)
        self.assertTrue(any(x[2] for x in d))


@unittest.skipUnless(HAVE, 'numpy/rapidfuzz/jiwer 없음')
class CleanTest(unittest.TestCase):
    def test_known_phrase_and_repeats(self):
        segs = [dict(text='시청해 주셔서 감사합니다.'), dict(text='네.'), dict(text='네.'), dict(text='네.'), dict(text='좋아요. 좋아요. 좋아요. 네')]
        out, removed, phrases, reps = L.transcript_clean(segs)
        self.assertEqual([s['text'] for s in out], ['네.', '좋아요. 네'])
        self.assertEqual((removed, phrases, reps), (5, 1, 4))

    def test_loops(self):
        self.assertEqual(L.loops('그래서 그래서 그래서 그래서 그래서'), 1)
        self.assertEqual(L.loops('오늘 견적서를 보냅니다'), 0)

    def test_silence_hallucination(self):
        segs = [(0, 1000, '네'), (5000, 6000, '감사합니다'), (5000, 6000, '...')]
        self.assertEqual(L.silence_hallucinations(segs, [(0, 2000)]), 1)


@unittest.skipUnless(HAVE, 'numpy/rapidfuzz/jiwer 없음')
class PipelineTest(unittest.TestCase):
    def test_constants_match_app(self):
        # android/.../PipelineAudioMath.kt, PipelineSpeechPlan 기본값
        self.assertEqual((P.MAX_CHUNK_MS, P.CUT_SEARCH_MS, P.CUT_WINDOW_MS, P.CUT_STEP_MS, P.MIN_TAIL_MS), (120000, 2000, 100, 10, 100))
        self.assertEqual((P.SPEECH_FLOOR, P.LOUD_SHARE, P.KEEP_GAP_MS, P.PAD_MS), (0.006, 0.3, 1500, 300))

    def test_cut_points_within_max(self):
        rng = np.random.default_rng(0)
        x = (rng.standard_normal(16000 * 300) * 0.1).astype(np.float32)
        x[16000 * 119:16000 * 119 + 800] = 0
        cuts = P.cut_points(x)
        self.assertEqual(cuts[0], 0)
        self.assertTrue(all(b - a <= 16000 * 120 for a, b in zip(cuts, cuts[1:] + [len(x)])))
        self.assertTrue(abs(cuts[1] - (16000 * 119 + 400)) < 900)

    def test_silence_removed_and_map_back(self):
        sr = 16000
        tone = (0.1 * np.sin(np.arange(sr * 3) * 0.2)).astype(np.float32)
        x = np.concatenate([tone, np.zeros(sr * 10, np.float32), tone])
        pieces = P.pieces_of(x)
        self.assertEqual(len(pieces), 1)
        ms, data, vad, tmap = pieces[0]
        self.assertTrue(vad)
        self.assertLess(len(data), len(x) * 0.6)
        # 조각 안 두 번째 소리의 시작은 원래 13초 근처
        second_local = (len(tmap) > 1 and tmap[1][0]) * 1000 // sr
        self.assertAlmostEqual(P.to_original_ms(tmap, second_local + 300) / 1000, 13.0, delta=0.4)

    def test_quiet_speech_below_floor_is_dropped(self):
        # 앱의 하한 0.006: -50 dBFS 수준 소리는 말소리로 치지 않는다(실험실 발견 1의 근거)
        sr = 16000
        quiet = (0.003 * np.sin(np.arange(sr * 3) * 0.2)).astype(np.float32)
        loud = (0.1 * np.sin(np.arange(sr * 3) * 0.2)).astype(np.float32)
        x = np.concatenate([loud, np.zeros(sr * 5, np.float32), quiet, np.zeros(sr * 5, np.float32), loud])
        total = sum(len(p[1]) for p in P.pieces_of(x))
        self.assertLess(total, sr * 7.5)


if __name__ == '__main__':
    unittest.main()
