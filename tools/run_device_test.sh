#!/usr/bin/env bash
# Installs the debug + test APKs on the connected device/emulator, runs CallPipelineDeviceTest
# (public audio only), and collects results into device-results/. Exit status = test result.
# IEUM_GOLDEN=compare|base|off (default compare: every variant the build has) runs the golden set (tg_goldenSet); its transcripts are scored here by
# tools/audio/golden_score.py into device-results/golden-score.json and golden-score.txt (per-call CER, entities, comparison table).
# IEUM_MODEL=small|large (default large) picks the Whisper model. IEUM_MODEL_FILE, when set, is a runner-cached copy:
# it is written (run-as, debug build) as the app's finished partial download, and the app's own size + SHA-256 check
# decides whether it is used; a bad copy is deleted and downloaded again.
set -u
out=device-results; mkdir -p "$out"
adb wait-for-device
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb install -r -g android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ -n "${IEUM_MODEL_FILE:-}" ] && [ -f "$IEUM_MODEL_FILE" ]; then
  part="no_backup/pipeline-stt/$(basename "$IEUM_MODEL_FILE").part"
  started=$(date +%s)
  if adb exec-in "run-as kr.callbrief sh -c 'mkdir -p no_backup/pipeline-stt && cat > $part'" < "$IEUM_MODEL_FILE"; then
    echo "model copy: $(adb shell run-as kr.callbrief stat -c %s "$part") bytes in $(( $(date +%s)-started )) s"
  elif adb push "$IEUM_MODEL_FILE" /data/local/tmp/ieum-model.bin >/dev/null \
    && adb shell "run-as kr.callbrief sh -c 'mkdir -p no_backup/pipeline-stt && cat /data/local/tmp/ieum-model.bin > $part'"; then
    echo "model copy (push + run-as): $(( $(date +%s)-started )) s"
  else
    echo "model copy failed; the test downloads it"; adb shell run-as kr.callbrief rm -f "$part" || true
  fi
  adb shell rm -f /data/local/tmp/ieum-model.bin || true
fi
adb logcat -c
# Results are streamed as they come, and the run has its own time limit (IEUM_INSTRUMENT_MIN, default 70 minutes), so a test
# that hangs still leaves what ran before it and where it stopped (the job's own limit would cancel the step with nothing).
adb logcat -s IEUM_RESULT:I > "$out/results.live" 2>/dev/null &
live=$!
# IEUM_TESTS=name[,name] runs only those tests (e.g. tg_goldenSet for a golden-only run).
cls=kr.callbrief.pipeline.CallPipelineDeviceTest
if [ -n "${IEUM_TESTS:-}" ]; then cls=$(echo "$IEUM_TESTS" | tr ',' '\n' | sed "s/^/$cls#/" | paste -sd, -); fi
timeout "${IEUM_INSTRUMENT_MIN:-70}m" adb shell am instrument -w -r -e class "$cls" -e model "${IEUM_MODEL:-large}" \
  -e repeat "${IEUM_REPEAT:-3}" -e threads "${IEUM_THREADS:-6,8}" -e callLike "${IEUM_CALL_LIKE:-true}" -e golden "${IEUM_GOLDEN:-compare}" \
  kr.callbrief.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrument.txt" 2>&1
code=$?
kill $live 2>/dev/null || true
if [ $code -eq 124 ]; then echo "instrument timed out after ${IEUM_INSTRUMENT_MIN:-70} minutes; last result: $(tail -1 "$out/results.live")" | tee -a "$out/instrument.txt"; adb shell am force-stop kr.callbrief || true; fi
adb logcat -d -s IEUM_RESULT:I > "$out/results.txt"
[ -s "$out/results.txt" ] || cp "$out/results.live" "$out/results.txt"
adb logcat -d '*:E' | tail -300 > "$out/errors.txt"
adb pull /sdcard/Android/data/kr.callbrief/files/device-test "$out/" >/dev/null 2>&1 || true
adb shell getprop ro.product.model > "$out/device.txt"
# Golden set: score the pulled transcripts (or, if the pull failed, the logcat lines).
golden_in="$out/device-test/golden_runs.json"; [ -f "$golden_in" ] || golden_in="$out/results.txt"
if [ -f "$out/device-test/golden_runs.json" ] || grep -q ' golden_text ' "$out/results.txt"; then
  python3 tools/audio/golden_score.py android/app/src/androidTest/assets/golden/golden.json "$golden_in" --out "$out/golden-score.json" > "$out/golden-score.txt" 2>&1 \
    || echo "golden score failed (see golden-score.txt)" >> "$out/golden-score.txt"
  cat "$out/golden-score.txt"
fi
grep -v "golden_text" "$out/results.txt"; tail -30 "$out/instrument.txt"
grep -q '^OK (' "$out/instrument.txt"
