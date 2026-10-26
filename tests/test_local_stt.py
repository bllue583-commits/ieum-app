from pathlib import Path
from fractions import Fraction
import json,unittest,os
from local_stt.adapter import *

ROOT=Path(os.environ.get('IEUM_PRIOR_OBSERVED_DIR', 'external-fixtures/prior-observed'))
JOB='00000000-0000-4000-8000-000000000041'

class LocalSttTest(unittest.TestCase):
    @unittest.skipUnless((ROOT/'raw.json').is_file() and (ROOT/'raw.txt').is_file(), 'External prior-observation fixture not provisioned; see docs/ASSETS.md')
    def test_actual_prior_raw_bytes_become_exact_segment_requests_without_speaker_inference(self):
        raw_json,raw_txt=(ROOT/'raw.json').read_bytes(),(ROOT/'raw.txt').read_bytes()
        request,evidence=prepare_request(JOB,raw_json,raw_txt,Fraction(1025100000,16000),{'state':'IMPORTED_PREVIOUS_ACTUAL_EXECUTION_NOT_CURRENT'})
        r,e=parse(request),parse(evidence);self.assertEqual(len(r['transcript']),7)
        original=parse(raw_json)['transcription']
        self.assertEqual([t['text'] for t in r['transcript']],[t['text'] for t in original])
        self.assertTrue(all(t['speaker']=='미확인' for t in r['transcript']))
        self.assertEqual(e['request_sha256'],sha(request));self.assertEqual(e['raw_txt_sha256'],OBSERVED_TXT_SHA)
        self.assertFalse(e['request_raw_segment_text_modified']);self.assertFalse(e['cloud_uploaded']);self.assertFalse(e['canonical_write_allowed'])
    @unittest.skipUnless((ROOT/'raw.json').is_file() and (ROOT/'raw.txt').is_file(), 'External prior-observation fixture not provisioned; see docs/ASSETS.md')
    def test_out_of_audio_time_and_raw_text_mismatch_are_held_without_repair(self):
        raw=parse((ROOT/'raw.json').read_bytes());raw['transcription'][-1]['offsets']['to']=65000
        with self.assertRaises(Held):segments(json_bytes(raw),(ROOT/'raw.txt').read_bytes(),Fraction(1025100000,16000))
        with self.assertRaises(Held):segments((ROOT/'raw.json').read_bytes(),b'invented transcript',Fraction(1025100000,16000))
    def test_missing_existing_runtime_cannot_start_a_model_or_download(self):
        cfg={'binary':'/workspace/shared/callbrief-worker-stt-20261011/runtime/whisper-cli','model':'/workspace/scratch/2dc3185cd912/callbrief-worker-stt-20261011/models/ggml-small.bin'}
        out=probe(cfg);self.assertEqual(out['state'],'HELD');self.assertFalse(out['inference_executed']);self.assertFalse(out['model_download'])
    def test_command_never_uses_reference_prompt_translation_or_network(self):
        cfg={'binary':'/existing/pinned/whisper-cli','model':'/existing/pinned/ggml-small.bin'}
        argv=command(cfg,Path('/approved/input.wav'),Path('/local/raw'))
        self.assertIn('--no-gpu',argv);self.assertEqual(argv[argv.index('--language')+1],'ko')
        self.assertNotIn('--prompt',argv);self.assertNotIn('--translate',argv);self.assertNotIn('curl',argv)
        with self.assertRaises(Held):parse(b'{"a":1,"a":2}')

class LocalSttCancellationTest(unittest.TestCase):
    def test_interrupt_terminates_only_owned_process_group_and_preserves_held_journal(self):
        from unittest.mock import patch,Mock
        from tempfile import TemporaryDirectory
        process=Mock(pid=424242);process.wait.side_effect=[KeyboardInterrupt(),0];process.poll.return_value=None
        with TemporaryDirectory() as directory:
            output=Path(directory)/'new-synthetic-run'
            with patch('local_stt.adapter.probe',return_value={'state':'READY'}), patch('local_stt.adapter.approved_audio',return_value=(b'PUBLIC_MOCK_AUDIO_ONLY',Fraction(256275,4))), patch('local_stt.adapter.subprocess.Popen',return_value=process) as popen, patch('local_stt.adapter.os.killpg') as kill:
                with self.assertRaises(KeyboardInterrupt):transcribe({'binary':'/mock/existing','model':'/mock/model','audio':'/mock/approved'},JOB,output)
                kill.assert_called_once_with(process.pid,signal.SIGTERM)
                self.assertEqual(parse((output/'execution.json').read_bytes())['code'],'LOCAL_STT_INTERRUPTED_NO_AUTO_REEXECUTION')
                self.assertFalse((output/'prepared').exists())
                with self.assertRaisesRegex(Held,'EXISTING_RUN_RECONCILE_REQUIRED_NO_AUTO_REEXECUTION'):transcribe({'binary':'/mock/existing','model':'/mock/model','audio':'/mock/approved'},JOB,output)
                self.assertEqual(popen.call_count,1)

if __name__=='__main__':unittest.main()
