#!/usr/bin/env bash
# Update smoke test (design H1): install the previous release, install the new APK over it, start the app,
# and check that it does not crash and that its scheduled work is registered. Run on an emulator with adb.
# Usage: tools/update_smoke.sh PREVIOUS_APK NEW_APK [OUT_DIR]
# v3.8 r18-lock (DESIGN_V7 4.2): the previous release is a debug build, the new one a release build (not debuggable), so
#   - "the data stayed" is checked by a marker file written into the app's own folder before the update (run-as, the
#     previous build is debuggable) and read after it as root (su; the emulator image has it); without su, by the
#     unchanged first install time (an update, never a reinstall);
#   - the app's own certificate check ([UpdateCert], logcat tag IEUM_CERT) must not call its own APK unrelated, and with
#     IEUM_SMOKE_ROTATED=1 (the new APK is signed with the rotated key and the stored lineage) it must read the lineage:
#     the verdict of the old certificate is ANCESTOR, the one a phone still on the old key reaches;
#   - IEUM_SMOKE_NEXT=APK (one more build with the same key and lineage, a higher version code) is installed over the new
#     one: first by the app itself (the file planted as root: the path a phone takes, with the certificate check) when su
#     works, then with adb: it must install, keep the data and start.
set -u
prev="$1"; new="$2"; out="${3:-smoke-results}"; pkg=kr.callbrief; fail=0
next="${IEUM_SMOKE_NEXT:-}"; [ -n "$next" ] && [ -f "$next" ] || next=""
rotated="${IEUM_SMOKE_ROTATED:-0}"
mkdir -p "$out"
adb wait-for-device
vcode() { adb shell dumpsys package "$pkg" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
first_install() { adb shell dumpsys package "$pkg" | sed -n 's/.*firstInstallTime=//p' | head -1 | tr -d '\r'; }
su_ok() { [ "$(adb shell su 0 id -u 2>/dev/null | tr -d '\r')" = "0" ]; }
marker() { adb shell su 0 cat "/data/data/$pkg/files/smoke-marker" 2>/dev/null | tr -d '\r'; }
# The data is still there after an update: the marker when root can read it, else the same first install time.
kept() { # $1 = what, $2 = first install time before
  local now; now=$(first_install)
  if [ -n "$2" ] && [ "$now" != "$2" ]; then echo "$1: FIRST INSTALL TIME CHANGED ($2 -> $now): the app was reinstalled, data gone"; return 1; fi
  if [ "${has_marker:-0}" = "1" ] && su_ok; then
    [ "$(marker)" = "ieum-smoke" ] || { echo "$1: THE APP'S DATA IS GONE (marker missing)"; return 1; }
    echo "$1: data kept (marker read as root, first install $now)"
  else echo "$1: data kept (first install time unchanged: $now; marker not usable here)"; fi
}
# The IEUM_CERT self check of the app that started last: never UNRELATED; when rotated, the old key's verdict is ANCESTOR.
cert_line() { # $1 = logcat file, $2 = label
  local line; line=$(grep -m1 "IEUM_CERT.*self " "$1" | sed 's/.*IEUM_CERT[^:]*: //')
  echo "$2 cert self check: ${line:-none}" | tee -a "$out/cert.txt"
  case "$line" in
    *UNRELATED*) echo "THE APP CALLS ITS OWN APK UNRELATED: it would refuse its own updates"; return 1;;
    *verdicts=*UNKNOWN*|"") echo "::warning title=update-smoke::$2: the certificate check could not read the certificates here (let through; certCheck false)"; return 0;;
  esac
  if [ "$rotated" = "1" ]; then
    case "$line" in *verdicts=ANCESTOR*) echo "$2: the installed lineage holds the old key (ANCESTOR): a phone on the old key accepts this APK";;
      *) echo "ROTATED APK, BUT THE OLD KEY'S VERDICT IS NOT ANCESTOR ($line): a phone on the old key would refuse it"; return 1;; esac
  fi
}
start_and_watch() { # $1 = label, $2 = logcat file
  # A fresh process (the update's broadcast may have started one already), so its start-up self check is in this log.
  adb shell am force-stop "$pkg"; adb logcat -c
  adb shell am start -n "$pkg/.pipeline.MainActivity" || adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1
  sleep 10
  adb logcat -d > "$2"
  if grep -E "FATAL EXCEPTION|ANR in $pkg" "$2" > "$out/crash-$1.txt"; then echo "$1: crash or ANR:"; head -5 "$out/crash-$1.txt"; return 1; fi
  rm -f "$out/crash-$1.txt"
}

adb install -r -g "$prev" || { echo "previous APK did not install"; exit 1; }
# Written by the previous (debuggable) build's own user; read back as root after the update.
adb shell am start -n "$pkg/.pipeline.MainActivity" >/dev/null 2>&1; sleep 4; adb shell am force-stop "$pkg"
# adb shell joins its arguments into one command line: the inner command is quoted once more, or `&&` and `>` would run
# outside run-as (r18-lock, run 43335800599: the marker was never written that way).
adb shell "run-as $pkg sh -c 'mkdir -p files && echo ieum-smoke > files/smoke-marker'"
if [ "$(adb shell "run-as $pkg cat files/smoke-marker" 2>/dev/null | tr -d '\r')" = "ieum-smoke" ]; then has_marker=1
else has_marker=0; echo "::warning title=update-smoke::marker not written: data checked by the first install time only"; fi
before=$(first_install); echo "previous release installed: versionCode $(vcode), first install $before"
adb install -r -g "$new" || { echo "NEW APK DID NOT INSTALL OVER THE PREVIOUS ONE"; exit 1; }
kept "new over previous" "$before" || fail=1
start_and_watch new "$out/logcat.txt" || fail=1
if [ -f "$out/crash-new.txt" ]; then cp "$out/crash-new.txt" "$out/crash.txt"; fi
adb shell dumpsys jobscheduler > "$out/jobs.txt"
jobs=$(grep -c "$pkg/androidx.work.impl.background.systemjob.SystemJobService" "$out/jobs.txt")
echo "scheduled jobs of $pkg: $jobs"
[ "$jobs" -ge 5 ] || { echo "fewer than five scheduled jobs"; fail=1; }
cert_line "$out/logcat.txt" new || fail=1
adb shell screencap -p /sdcard/home.png && adb pull /sdcard/home.png "$out/home.png" >/dev/null
adb shell dumpsys package "$pkg" | grep -m2 -E "versionName|versionCode" > "$out/version.txt"
adb shell dumpsys package "$pkg" | grep -iE "signatures|pastSigningCertificates|flags=\[" | head -6 > "$out/signing.txt" || true

# r18-lock: one more build with the same new key and the stored lineage, over the new one.
if [ -n "$next" ]; then
  mid=$(vcode)
  if su_ok; then
    # The app installs it itself, as on the phone: its download folder and update state written as root, then [지금 업데이트].
    uid=$(adb shell su 0 stat -c %u "/data/data/$pkg" | tr -d '\r')
    # The app's own SELinux label (with its categories), copied from its data folder onto what root writes there.
    ctx=$(adb shell su 0 ls -Zd "/data/data/$pkg" | tr -d '\r' | awk '{print $1}')
    adb shell am force-stop "$pkg"
    adb push "$next" /data/local/tmp/ieum-next.apk >/dev/null
    sha=$(sha256sum "$next" | cut -d' ' -f1); size=$(stat -c %s "$next"); code=$((mid+1))
    printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><int name=\"code\" value=\"%s\" /><string name=\"sha\">%s</string><long name=\"size\" value=\"%s\" /><string name=\"name\">v-smoke</string></map>" "$code" "$sha" "$size" > "$out/ieum.update.xml"
    adb push "$out/ieum.update.xml" /data/local/tmp/ieum.update.xml >/dev/null
    adb shell su 0 sh -c "'mkdir -p /data/data/$pkg/cache/update /data/data/$pkg/shared_prefs && cp /data/local/tmp/ieum-next.apk /data/data/$pkg/cache/update/ieum.apk && cp /data/local/tmp/ieum.update.xml /data/data/$pkg/shared_prefs/ieum.update.xml && chown -R $uid:$uid /data/data/$pkg/cache /data/data/$pkg/shared_prefs && chcon -R $ctx /data/data/$pkg/cache /data/data/$pkg/shared_prefs'"
    adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow
    adb logcat -c
    adb shell am start -n "$pkg/.pipeline.MainActivity" --ez install_update true >/dev/null
    now=""; for i in $(seq 1 30); do now=$(vcode); [ -n "$now" ] && [ "$now" -gt "$mid" ] && break; sleep 2; done
    adb logcat -d > "$out/logcat-self-next.txt"
    grep "IEUM_CERT" "$out/logcat-self-next.txt" | tee -a "$out/cert.txt" || true
    echo "self-install of the next build by the new app: before=$mid after=${now:-?}" | tee "$out/self-install-next.txt"
    if grep -q "IEUM_CERT.*update apk verdict=UNRELATED" "$out/logcat-self-next.txt"; then echo "THE NEW APP REFUSED THE NEXT BUILD AS UNRELATED"; fail=1
    elif [ -z "$now" ] || [ "$now" -le "$mid" ]; then
      adb shell dumpsys window | grep -m1 mCurrentFocus >> "$out/self-install-next.txt" 2>/dev/null
      echo "::warning title=update-smoke::the new app did not install the next build by itself (a tap may be needed); installing it with adb"
    else kept "next over new (by the app)" "$before" || fail=1; fi
  else
    echo "::warning title=update-smoke::no su on this emulator: the next build is installed with adb only (the app's own certificate check is not exercised)"
  fi
  adb install -r -g "$next" || { echo "THE NEXT BUILD (same new key and lineage) DID NOT INSTALL OVER THE NEW ONE"; exit 1; }
  kept "next over new" "$before" || fail=1
  [ "$(vcode)" -gt "$mid" ] || { echo "versionCode did not go up with the next build"; fail=1; }
  start_and_watch next "$out/logcat-next.txt" || fail=1
  cert_line "$out/logcat-next.txt" next || fail=1
  echo "next build: versionCode $(vcode)" >> "$out/version.txt"
fi

# v3.1 (DESIGN_V4 2.2-6): the app installs the new APK by itself, the way a phone gets it. The previous release (the
# installed app here) is what runs the install, so until it is a v3.1 build (which declares UPDATE_PACKAGES_WITHOUT_USER_ACTION)
# Android may ask for a tap: a warning unless IEUM_SELF_INSTALL_STRICT=1.
self_install() {
  adb uninstall "$pkg" >/dev/null 2>&1; adb install -r -g "$prev" >/dev/null || return 1
  local before; before=$(vcode); [ -n "$before" ] || return 1
  adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow
  adb shell am start -n "$pkg/.pipeline.MainActivity" >/dev/null; sleep 5; adb shell am force-stop "$pkg"
  adb push "$new" /data/local/tmp/ieum.apk >/dev/null || return 1
  adb shell "run-as $pkg sh -c 'mkdir -p cache/update && cp /data/local/tmp/ieum.apk cache/update/ieum.apk'" || return 1
  local sha size code; sha=$(sha256sum "$new" | cut -d' ' -f1); size=$(stat -c %s "$new"); code=$((before+1))
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><int name=\"code\" value=\"%s\" /><string name=\"sha\">%s</string><long name=\"size\" value=\"%s\" /><string name=\"name\">v-smoke</string></map>" "$code" "$sha" "$size" \
    | adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ieum.update.xml'" || return 1
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
