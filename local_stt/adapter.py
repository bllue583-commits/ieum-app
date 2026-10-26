"""Offline production bridge: existing pinned whisper.cpp -> exact scheduled-dot text input.

No download, install, provider API, cloud upload, scheduler, or personal audio path.
Only the single previously approved WAV SHA is accepted. Raw engine outputs are preserved.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import platform
import re
import signal
import subprocess
import time
import uuid
import wave
from datetime import datetime, timezone
from fractions import Fraction
from pathlib import Path

AUDIO_SHA = '3ee1442ed8ac61b95ac1ff2985d2857126042b99141c05f7947c25ae4f7c56b5'
AUDIO_BYTES = 2050244
ENGINE_SHA = '69eb69f304a83a86946887ae6afc4c011211027beb24e807764d533f3e949371'
MODEL_SHA = '1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b'
MODEL_BYTES = 487601967
OBSERVED_JSON_SHA = 'f5cb5014b6552d00563afb301a737fa72dafe96157ca35f1a7cf3906b8e4e29a'
OBSERVED_TXT_SHA = 'b51da118ffe7279f0b6db4e0654cbc6bb868203a550991822837aa98753ab312'
OBSERVED_RESULT_SHA = '19420009b4d9b8a8be576cac89c85109aaafe266f2665f4b3a5d15d1dd42c646'
OBSERVED_METRICS_SHA = '60f02c014350626318b3e13ee93673c439660859e7167825de0ec2a9fada0607'
OBSERVED_INDEX_SHA = 'ade421ce2c80975076dfe0b3432ce3f184c18ad59563f365baf438525106affe'


class Held(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise Held(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def json_bytes(value):
    # ASCII schema keys have identical ordering in Python and the existing Java StrictJson.
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def parse(raw, cap=10 * 1024 * 1024):
    require(len(raw) <= cap, 'JSON_SIZE_LIMIT')
    def pairs(values):
        out = {}
        for k, v in values:
            require(k not in out, 'DUPLICATE_JSON_KEY')
            out[k] = v
        return out
    def constant(_):
        raise Held('NONFINITE_JSON')
    try:
        return json.loads(raw.decode('utf-8', 'strict'), object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, ValueError, TypeError) as e:
        raise Held('INVALID_JSON') from e


def utc():
    return datetime.now(timezone.utc).isoformat()


def approved_audio(path):
    p = Path(path)
    require(p.is_file() and p.stat().st_size == AUDIO_BYTES, 'APPROVED_SYNTHETIC_WAV_REQUIRED')
    data = p.read_bytes()
    require(sha(data) == AUDIO_SHA, 'SYNTHETIC_AUDIO_SHA_MISMATCH')
    try:
        with wave.open(io.BytesIO(data), 'rb') as w:
            require(w.getnchannels() == 1 and w.getsampwidth() == 2 and w.getframerate() == 16000 and w.getcomptype() == 'NONE', 'APPROVED_PCM_FORMAT_REQUIRED')
            frames = w.getnframes()
            require(frames == 1025100, 'AUDIO_FRAME_BINDING_MISMATCH')
    except (wave.Error, EOFError) as e:
        raise Held('INVALID_WAV') from e
    return data, Fraction(frames * 1000, 16000)


def probe(configuration):
    missing = [str(configuration[k]) for k in ('binary', 'model') if not Path(configuration[k]).is_file()]
    info = {'host_system': platform.system(), 'host_architecture': platform.machine(), 'missing_paths': missing,
            'inference_executed': False, 'installation': False, 'model_download': False}
    if missing:
        return dict(info, state='HELD', code='EXISTING_RUNTIME_FILES_MISSING')
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        return dict(info, state='HELD', code='PINNED_LINUX_X86_64_RUNTIME_REQUIRED')
    binary, model = Path(configuration['binary']), Path(configuration['model'])
    require(os.access(binary, os.X_OK), 'RUNTIME_NOT_EXECUTABLE')
    require(file_sha(binary) == ENGINE_SHA, 'ENGINE_PIN_MISMATCH')
    require(model.stat().st_size == MODEL_BYTES and file_sha(model) == MODEL_SHA, 'MODEL_PIN_MISMATCH')
    return dict(info, state='READY', code=None, engine_sha256=ENGINE_SHA, model_sha256=MODEL_SHA)


def command(configuration, audio, prefix):
    return [str(configuration['binary']), '--model', str(configuration['model']), '--file', str(audio),
            '--language', 'ko', '--threads', '2', '--no-gpu', '--output-json-full', '--output-txt', '--output-file', str(prefix)]


def segments(raw_json, raw_txt, duration_ms):
    x = parse(raw_json)
    require(x.get('params', {}).get('language') == 'ko' and x.get('params', {}).get('translate') is False, 'KOREAN_NO_TRANSLATION_REQUIRED')
    require(x.get('result', {}).get('language') == 'ko', 'OBSERVED_LANGUAGE_MISMATCH')
    values = x.get('transcription')
    require(isinstance(values, list) and 1 <= len(values) <= 32, 'SCHEDULED_SEGMENT_CAPACITY')
    entries, previous = [], 0
    for i, s in enumerate(values):
        require(isinstance(s, dict) and isinstance(s.get('offsets'), dict), 'SEGMENT_OFFSETS_REQUIRED')
        start, end, text = s['offsets'].get('from'), s['offsets'].get('to'), s.get('text')
        require(type(start) is int and type(end) is int and 0 <= start < end and start >= previous and Fraction(end) <= duration_ms, 'SEGMENT_TIME_OUT_OF_SOURCE')
        require(isinstance(text, str) and text.strip() and len(text) <= 2000 and '\x00' not in text, 'SEGMENT_TEXT_REQUIRED')
        entries.append({'index': i, 'start_ms': start, 'end_ms': end, 'text': text, 'text_sha256': sha(text.encode('utf-8'))})
        previous = end
    # The CLI owns TXT formatting. Record the exact relation; never repair either artifact.
    provider_text = raw_txt.decode('utf-8', 'strict')
    join_original = '\n'.join(e['text'] for e in entries) + '\n'
    join_one_space_removed = '\n'.join(e['text'][1:] if e['text'].startswith(' ') else e['text'] for e in entries) + '\n'
    if provider_text == join_original:
        relation = 'SEGMENT_JOIN_LF'
    elif provider_text == join_one_space_removed:
        relation = 'CLI_TXT_REMOVE_ONE_LEADING_ASCII_SPACE_PER_SEGMENT_JOIN_LF'
    else:
        raise Held('RAW_JSON_TXT_RELATION_UNSUPPORTED')
    return entries, relation


def prepare_request(job_id, raw_json, raw_txt, duration_ms, execution):
    try: valid_job = str(uuid.UUID(job_id)) == job_id
    except (ValueError, AttributeError): valid_job = False
    require(valid_job, 'CANONICAL_PROOF_UUID_REQUIRED')
    entries, relation = segments(raw_json, raw_txt, duration_ms)
    request = {'schema_version': 'scheduler-proof-v1', 'job_id': job_id, 'synthetic': True, 'source_id': 'synthetic_call001',
               'transcript': [{'speaker': '미확인', 'text': s['text']} for s in entries]}
    request_raw = json_bytes(request)
    require(len(request_raw) <= 65536, 'SCHEDULED_REQUEST_SIZE_LIMIT')
    evidence = {'schema_version': 'ieum.local-whisper-request-evidence.v1', 'job_id': job_id,
                'synthetic': True, 'audio_sha256': AUDIO_SHA, 'audio_bytes': AUDIO_BYTES,
                'engine_sha256': ENGINE_SHA, 'model_sha256': MODEL_SHA, 'model_name': 'small multilingual',
                'raw_json_sha256': sha(raw_json), 'raw_txt_sha256': sha(raw_txt), 'raw_txt_bytes': len(raw_txt),
                'request_sha256': sha(request_raw), 'request_raw_segment_text_modified': False,
                'raw_artifacts_modified': False, 'raw_txt_relation': relation,
                'audio_duration_ms': {'numerator': duration_ms.numerator, 'denominator': duration_ms.denominator},
                'segments': [{k: v for k, v in s.items() if k != 'text'} for s in entries],
                'quote_coordinate_domain': 'Each transcript[i].text Unicode codepoints; quote must be an exact substring',
                'speaker_assignment_verified': False, 'transcription_quality': 'RAW_REVIEW_REQUIRED',
                'semantic_validity_verified': False, 'execution': execution, 'dot_executed': False,
                'scheduler_created': False, 'cloud_uploaded': False, 'canonical_write_allowed': False, 'applied': False}
    return request_raw, json_bytes(evidence)


def save_prepared(output, job_id, raw_json, raw_txt, duration_ms, execution):
    out = Path(output)
    request, evidence = prepare_request(job_id, raw_json, raw_txt, duration_ms, execution)
    out.mkdir(parents=True, exist_ok=False, mode=0o700)
    for name, data in [('whisper-original.json', raw_json), ('whisper-original.txt', raw_txt), ('scheduled-input.json', request), ('stt-request-evidence.json', evidence)]:
        with (out / name).open('xb') as f:
            f.write(data); f.flush(); os.fsync(f.fileno())
        os.chmod(out / name, 0o600)
    receipt = {'state': 'PREPARED_READ_ONLY_STT_REQUEST', 'job_id': job_id, 'output': str(out),
               'request_sha256': sha(request), 'evidence_sha256': sha(evidence), 'segments': len(parse(request)['transcript']),
               'inference_in_current_process': execution['state'] == 'EXECUTED_IN_THIS_PROCESS',
               'cloud_uploaded': False, 'scheduler_created': False, 'canonical_write_allowed': False}
    (out / 'PREPARATION_RECEIPT.json').write_bytes(json_bytes(receipt))
    return receipt


def transcribe(configuration, job_id, output):
    try: valid_job = str(uuid.UUID(job_id)) == job_id
    except (ValueError, AttributeError): valid_job = False
    require(valid_job, 'CANONICAL_PROOF_UUID_REQUIRED')
    readiness = probe(configuration)
    if readiness['state'] != 'READY':
        return readiness
    data, duration = approved_audio(configuration['audio'])
    run = Path(output)
    require(not run.exists(), 'EXISTING_RUN_RECONCILE_REQUIRED_NO_AUTO_REEXECUTION')
    run.mkdir(parents=True, mode=0o700)
    started = utc(); began = time.monotonic()
    journal = {'state': 'CLAIMED', 'job_id': job_id, 'audio_sha256': AUDIO_SHA, 'engine_sha256': ENGINE_SHA, 'model_sha256': MODEL_SHA, 'started_at_utc': started}
    (run / 'execution.json').write_bytes(json_bytes(journal))
    input_path = run / 'approved-input.wav'; input_path.write_bytes(data); os.chmod(input_path,0o600)
    argv = command(configuration, input_path, run / 'raw')
    proc = None
    try:
        with (run / 'stdout.log').open('xb') as stdout, (run / 'stderr.log').open('xb') as stderr:
            proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, start_new_session=True,
                                    env={'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'OMP_NUM_THREADS': '2'})
            try:
                code = proc.wait(timeout=180)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGTERM)
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGKILL); proc.wait()
                raise Held('LOCAL_STT_TIMEOUT_NO_AUTO_REEXECUTION')
        require(code == 0, 'LOCAL_STT_PROCESS_FAILED')
        require(file_sha(input_path) == AUDIO_SHA and file_sha(configuration['binary']) == ENGINE_SHA and file_sha(configuration['model']) == MODEL_SHA, 'SOURCE_OR_RUNTIME_CHANGED_DURING_STT')
        raw_json, raw_txt = (run / 'raw.json').read_bytes(), (run / 'raw.txt').read_bytes()
        execution = {'state': 'EXECUTED_IN_THIS_PROCESS', 'return_code': code, 'started_at_utc': started, 'finished_at_utc': utc(),
                     'elapsed_ms': round((time.monotonic()-began)*1000), 'language': 'ko', 'initial_prompt_used': False, 'reference_text_used': False, 'gpu_used': False}
        receipt = save_prepared(run / 'prepared', job_id, raw_json, raw_txt, duration, execution)
        journal.update(state='PREPARED', finished_at_utc=utc(), request_sha256=receipt['request_sha256'])
        (run / 'execution.json').write_bytes(json_bytes(journal))
        return receipt
    except BaseException as e:
        if proc is not None and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL); proc.wait()
            except ProcessLookupError:
                proc.wait()
        code = str(e) if isinstance(e,Held) else ('LOCAL_STT_INTERRUPTED_NO_AUTO_REEXECUTION' if isinstance(e,(KeyboardInterrupt,SystemExit)) else 'LOCAL_STT_OPERATION_FAILED')
        journal.update(state='HELD', code=code, finished_at_utc=utc())
        (run / 'execution.json').write_bytes(json_bytes(journal))
        raise


def package_prior_observed(configuration, job_id, prior_dir, output):
    _, duration = approved_audio(configuration['audio'])
    p=Path(prior_dir)
    raw_json,raw_txt,result,metrics=[(p/name).read_bytes() for name in ('raw.json','raw.txt','result.json','metrics.json')]
    for data,want in [(raw_json,OBSERVED_JSON_SHA),(raw_txt,OBSERVED_TXT_SHA),(result,OBSERVED_RESULT_SHA),(metrics,OBSERVED_METRICS_SHA)]:
        require(sha(data)==want,'PRIOR_OBSERVED_ARTIFACT_PIN_MISMATCH')
    r,m=parse(result),parse(metrics)
    require(m.get('exit_code')==0 and r.get('model_sha256')==MODEL_SHA and r.get('engine_sha256')==ENGINE_SHA,'PRIOR_RUN_BINDING_MISMATCH')
    index_raw=Path(configuration['observed_index']).read_bytes()
    require(sha(index_raw)==OBSERVED_INDEX_SHA,'PRIOR_REGISTRY_PIN_MISMATCH')
    index=parse(index_raw)
    entry=[e for e in index['entries'] if e.get('run_id')=='korun_bdd02d097a784656b7a9d50cb763cfbb']
    require(len(entry)==1 and entry[0]['wav_sha256']==AUDIO_SHA and entry[0]['result_sha256']==sha(result)
            and entry[0]['metrics_sha256']==sha(metrics) and entry[0]['exit_code']==0 and entry[0]['reference_opened'] is False,'PRIOR_AUDIO_REGISTRY_BINDING_MISMATCH')
    execution={'state':'IMPORTED_PREVIOUS_ACTUAL_EXECUTION_NOT_CURRENT','current_process_model_invocations':0,
               'observed_run':'korun_bdd02d097a784656b7a9d50cb763cfbb','prior_registry_sha256':sha(index_raw),'prior_result_sha256':sha(result),'prior_metrics_sha256':sha(metrics),'return_code_reported':0}
    return save_prepared(output,job_id,raw_json,raw_txt,duration,execution)
