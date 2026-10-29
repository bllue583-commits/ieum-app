#!/usr/bin/env bash
# Installs the debug + test APKs on the connected device/emulator, runs CallPipelineDeviceTest
# (public audio only), and collects results into device-results/. Exit status = test result.
set -u
out=device-results; mkdir -p "$out"
adb wait-for-device
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb install -r -g android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r -e class kr.callbrief.pipeline.CallPipelineDeviceTest -e repeat "${IEUM_REPEAT:-3}" \
  kr.callbrief.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrument.txt" 2>&1
adb logcat -d -s IEUM_RESULT:I > "$out/results.txt"
adb logcat -d '*:E' | tail -300 > "$out/errors.txt"
adb pull /sdcard/Android/data/kr.callbrief/files/device-test "$out/" >/dev/null 2>&1 || true
adb shell getprop ro.product.model > "$out/device.txt"
cat "$out/results.txt"; tail -30 "$out/instrument.txt"
grep -q '^OK (' "$out/instrument.txt"
