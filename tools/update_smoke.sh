#!/usr/bin/env bash
# Update smoke test (design H1): install the previous release, install the new APK over it, start the app,
# and check that it does not crash and that its scheduled work is registered. Run on an emulator with adb.
# Usage: tools/update_smoke.sh PREVIOUS_APK NEW_APK [OUT_DIR]
set -u
prev="$1"; new="$2"; out="${3:-smoke-results}"; pkg=kr.callbrief; fail=0
mkdir -p "$out"
adb wait-for-device
adb install -r -g "$prev" || { echo "previous APK did not install"; exit 1; }
adb install -r -g "$new" || { echo "NEW APK DID NOT INSTALL OVER THE PREVIOUS ONE"; exit 1; }
adb logcat -c
adb shell am start -n "$pkg/.pipeline.HomeActivity" || adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1
sleep 10
adb logcat -d > "$out/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $pkg" "$out/logcat.txt" > "$out/crash.txt"; then echo "crash or ANR after the update:"; head -5 "$out/crash.txt"; fail=1; fi
adb shell dumpsys jobscheduler > "$out/jobs.txt"
jobs=$(grep -c "$pkg/androidx.work.impl.background.systemjob.SystemJobService" "$out/jobs.txt")
echo "scheduled jobs of $pkg: $jobs"
[ "$jobs" -ge 5 ] || { echo "fewer than five scheduled jobs"; fail=1; }
adb shell screencap -p /sdcard/home.png && adb pull /sdcard/home.png "$out/home.png" >/dev/null
adb shell dumpsys package "$pkg" | grep -m2 -E "versionName|versionCode" > "$out/version.txt"
exit $fail
