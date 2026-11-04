#!/usr/bin/env bash
# Installs the debug + test APKs on the connected device/emulator, runs CallPipelineDeviceTest
# (public audio only), and collects results into device-results/. Exit status = test result.
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
adb shell am instrument -w -r -e class kr.callbrief.pipeline.CallPipelineDeviceTest -e model "${IEUM_MODEL:-large}" \
  -e repeat "${IEUM_REPEAT:-3}" -e threads "${IEUM_THREADS:-6,8}" -e callLike "${IEUM_CALL_LIKE:-true}" \
  kr.callbrief.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrument.txt" 2>&1
adb logcat -d -s IEUM_RESULT:I > "$out/results.txt"
adb logcat -d '*:E' | tail -300 > "$out/errors.txt"
adb pull /sdcard/Android/data/kr.callbrief/files/device-test "$out/" >/dev/null 2>&1 || true
adb shell getprop ro.product.model > "$out/device.txt"
cat "$out/results.txt"; tail -30 "$out/instrument.txt"
grep -q '^OK (' "$out/instrument.txt"
