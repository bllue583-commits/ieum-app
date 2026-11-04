#!/usr/bin/env python3
"""Makes the golden-set call audio (v24-2, design F5) from golden.json with an offline TTS. No human recording.

  python3 -m venv /tmp/tts && /tmp/tts/bin/pip install piper-tts==1.8.0
  /tmp/tts/bin/python tools/audio/make_golden.py [--voice-dir DIR]

Voice: piper ko_KR-kss-medium (the only Korean piper voice), downloaded once into --voice-dir and checked against
the SHA-256 in golden.json. One voice plays both sides; the far side is pitched down (ffmpeg asetrate + atempo, same
length) and made quieter by the call's farGainDb. Then like a phone call: 300-3400 Hz band, a low seeded noise bed,
turn pauses (pauseMs, default 500 ms; some over the 1.5 s that speech-only cutting drops), 16 kHz mono AAC (m4a,
as Samsung recordings are m4a and go through the app's MediaCodec path). Needs ffmpeg on PATH.

Prints, per call, the length, the file size and how much audio the speech-only cut would keep with the silence
floor 0.006 (now) and 0.0025 (the F3 candidate), so the floor comparison has something to show.
"""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import urllib.request
import wave

import numpy as np
from piper import PiperVoice, SynthesisConfig

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GOLDEN = os.path.join(ROOT, "android/app/src/androidTest/assets/golden")
VOICE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/ko/ko_KR/kss/medium/"
RATE = 16000
LIMIT_BYTES = 5 * 1024 * 1024


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def voice(voice_dir, spec):
    os.makedirs(voice_dir, exist_ok=True)
    onnx = os.path.join(voice_dir, spec["model"] + ".onnx")
    for name in (spec["model"] + ".onnx", spec["model"] + ".onnx.json"):
        path = os.path.join(voice_dir, name)
        if not os.path.isfile(path):
            urllib.request.urlretrieve(VOICE_URL + name, path)
    if sha256(onnx) != spec["sha256"]:
        raise SystemExit(f"voice SHA-256 mismatch: {onnx}")
    return PiperVoice.load(onnx)


def ffmpeg(args, data=None):
    return subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"] + args, input=data,
                          stdout=subprocess.PIPE, check=True).stdout


def turn_pcm(v, text, far, gain_db, tmp):
    path = os.path.join(tmp, "turn.wav")
    with wave.open(path, "wb") as w:
        v.synthesize_wav(text, w, syn_config=SynthesisConfig(length_scale=1.0 if far else 0.95, noise_scale=0.5))
    sr = v.config.sample_rate
    pitch = f"asetrate={int(sr * 0.84)},aresample={sr},atempo={1 / 0.84:.5f}," if far else ""
    raw = ffmpeg(["-i", path, "-af", f"{pitch}volume={gain_db}dB", "-ar", str(RATE), "-ac", "1", "-f", "f32le", "-"])
    return np.frombuffer(raw, dtype=np.float32)


def speech_kept(pcm, floor):
    """Share of samples PipelineAudioMath.speechOnly keeps (30 ms windows, 1.5 s gap, 300 ms pad; one chunk)."""
    win = RATE * 30 // 1000
    n = len(pcm) // win
    rms = np.sqrt(np.mean(pcm[: n * win].reshape(n, win).astype(np.float64) ** 2, axis=1))
    s = np.sort(rms)
    threshold = max(floor, min(s[n // 5] * 3, s[n * 9 // 10] * 0.3))
    gap, pad, kept, i = 1500 // 30, 300 // 30, 0, 0
    while i < n:
        if rms[i] < threshold:
            i += 1
            continue
        end, quiet, j = i, 0, i
        while j < n and quiet <= gap:
            if rms[j] >= threshold:
                end, quiet = j, 0
            else:
                quiet += 1
            j += 1
        kept += min(n, end + 1 + pad) - max(0, i - pad)
        i = end + 1 + pad
    saved = 1 - kept / max(1, n)
    return (kept / max(1, n)) if saved >= 0.1 else 1.0  # under 10 % saving speechOnly keeps everything


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice-dir", default=os.path.join(tempfile.gettempdir(), "ieum-piper-voice"))
    ap.add_argument("--only", help="one call id")
    a = ap.parse_args()
    golden = json.load(open(os.path.join(GOLDEN, "golden.json"), encoding="utf-8"))
    v = voice(a.voice_dir, golden["voice"])
    rng = np.random.default_rng(20261104)
    total = 0
    with tempfile.TemporaryDirectory() as tmp:
        for call in golden["calls"]:
            if a.only and call["id"] != a.only:
                continue
            parts = [np.zeros(RATE * 400 // 1000, np.float32)]
            for t in call["turns"]:
                far = t["who"] == "far"
                parts.append(turn_pcm(v, t["say"], far, call["farGainDb"] if far else -3, tmp))
                parts.append(np.zeros(RATE * t.get("pauseMs", 500) // 1000, np.float32))
            pcm = np.concatenate(parts)
            # Line/room noise: brown-ish (integrated white), about -64 dBFS RMS.
            noise = np.cumsum(rng.normal(0, 1, len(pcm))).astype(np.float64)
            noise -= np.convolve(noise, np.ones(801) / 801, mode="same")
            noise *= 0.0006 / max(1e-9, noise.std())
            pcm = np.clip(pcm + noise.astype(np.float32), -1, 1)
            out = os.path.join(GOLDEN, call["file"])
            band = ffmpeg(["-f", "f32le", "-ar", str(RATE), "-ac", "1", "-i", "-", "-af", "highpass=f=300,lowpass=f=3400",
                           "-f", "f32le", "-"], pcm.tobytes())
            pcm = np.frombuffer(band, dtype=np.float32)
            ffmpeg(["-f", "f32le", "-ar", str(RATE), "-ac", "1", "-i", "-", "-c:a", "aac", "-b:a", "40k",
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", out], pcm.tobytes())
            size = os.path.getsize(out)
            total += size
            print(f"{call['id']}: {len(pcm) / RATE:.1f} s, {size} bytes, speech-only keeps "
                  f"{speech_kept(pcm, 0.006) * 100:.0f}% at floor 0.006, {speech_kept(pcm, 0.0025) * 100:.0f}% at 0.0025")
    print(f"total {total} bytes")
    if total > LIMIT_BYTES:
        raise SystemExit("golden audio over 5 MB")


if __name__ == "__main__":
    main()
