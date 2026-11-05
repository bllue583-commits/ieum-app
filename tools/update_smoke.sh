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
adb shell am start -n "$pkg/.pipeline.MainActivity" || adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1
sleep 10
adb logcat -d > "$out/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $pkg" "$out/logcat.txt" > "$out/crash.txt"; then echo "crash or ANR after the update:"; head -5 "$out/crash.txt"; fail=1; fi
adb shell dumpsys jobscheduler > "$out/jobs.txt"
jobs=$(grep -c "$pkg/androidx.work.impl.background.systemjob.SystemJobService" "$out/jobs.txt")
echo "scheduled jobs of $pkg: $jobs"
[ "$jobs" -ge 5 ] || { echo "fewer than five scheduled jobs"; fail=1; }
adb shell screencap -p /sdcard/home.png && adb pull /sdcard/home.png "$out/home.png" >/dev/null
adb shell dumpsys package "$pkg" | grep -m2 -E "versionName|versionCode" > "$out/version.txt"

# v3.1 (DESIGN_V4 2.2-6): the app installs the new APK by itself, the way a phone gets it. The previous release (the
# installed app here) is what runs the install, so until it is a v3.1 build (which declares UPDATE_PACKAGES_WITHOUT_USER_ACTION)
# Android may ask for a tap: a warning unless IEUM_SELF_INSTALL_STRICT=1.
vcode() { adb shell dumpsys package "$pkg" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
self_install() {
  adb uninstall "$pkg" >/dev/null 2>&1; adb install -r -g "$prev" >/dev/null || return 1
  local before; before=$(vcode); [ -n "$before" ] || return 1
  adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow
  adb shell am start -n "$pkg/.pipeline.MainActivity" >/dev/null; sleep 5; adb shell am force-stop "$pkg"
  adb push "$new" /data/local/tmp/ieum.apk >/dev/null || return 1
  adb shell run-as "$pkg" sh -c 'mkdir -p cache/update && cp /data/local/tmp/ieum.apk cache/update/ieum.apk' || return 1
  local sha size code; sha=$(sha256sum "$new" | cut -d' ' -f1); size=$(stat -c %s "$new"); code=$((before+1))
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><int name=\"code\" value=\"%s\" /><string name=\"sha\">%s</string><long name=\"size\" value=\"%s\" /><string name=\"name\">v-smoke</string></map>" "$code" "$sha" "$size" \
    | adb shell run-as "$pkg" sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ieum.update.xml' || return 1
  adb shell am start -n "$pkg/.pipeline.MainActivity" --ez install_update true >/dev/null
  local now=""; for i in $(seq 1 30); do now=$(vcode); [ -n "$now" ] && [ "$now" -gt "$before" ] && break; sleep 2; done
  echo "self-install: before=$before after=$now" | tee "$out/self-install.txt"
  [ -n "$now" ] && [ "$now" -gt "$before" ]
}
if self_install; then echo "self-install raised versionCode without a tap"
else
  adb shell dumpsys window | grep -m1 mCurrentFocus >> "$out/self-install.txt" 2>/dev/null
  if [ "${IEUM_SELF_INSTALL_STRICT:-0}" = "1" ]; then echo "SELF-INSTALL DID NOT RAISE versionCode"; fail=1
  else echo "::warning title=update-smoke::self-install did not raise versionCode (the previous build may ask for a tap)"; fi
fi
exit $fail
