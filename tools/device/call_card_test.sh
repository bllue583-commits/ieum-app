#!/usr/bin/env bash
# v3.0 call card on an emulator (design 7.4, r11-3). Needs a booted emulator (adb + the emulator console: adb emu gsm ...)
# and the built debug + androidTest APKs. Made-up numbers only (PreviewSeed): 010-1111-2222 has a contact card,
# 010-7777-8888 is a personal number, 010-9999-0000 is unknown.
#
# Writes evidence/call-card.json {ringingWindow, stripAfter3s, goneAfterIdle, serviceGone, outgoing, unknownOff,
# personalNothing} (all true/false), screenshots evidence/shots/card_{ringing,strip,outgoing,unknown}.png and the log lines
# it judged from to evidence/call-card.log. Exit 1 when any check is false.
#
# The card window is FLAG_SECURE: on the screenshots it is a dark box where the card sits (its place, not its text). The
# card's text is checked by the host tests (CallCardOverlayTest); here the window, its log lines and the service are.
#
# CARD_WINDOW_MS: how soon the card must be there after the call rings (design: 1 second; default 1000).
set -u
pkg=kr.callbrief
known=01011112222; unknown=01099990000; personal=01077778888
limit_ms=${CARD_WINDOW_MS:-1000}
ev=evidence; shots=$ev/shots; mkdir -p "$shots"
log=$ev/call-card.log; : > "$log"
apk=android/app/build/outputs/apk/debug/app-debug.apk
test_apk=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=kr.callbrief.test/androidx.test.runner.AndroidJUnitRunner

say() { echo "call-card: $*" | tee -a "$log"; }
now_ms() { date +%s%3N; }
shot() { adb exec-out screencap -p > "$shots/card_$1.png" 2>/dev/null || say "screenshot $1 failed"; }
# The card's overlay window: its title, or this app's window drawn with the "draw over other apps" app op.
window_up() { adb shell dumpsys window windows 2>/dev/null | grep -qE "이음 통화 카드|package=$pkg.*appop=SYSTEM_ALERT_WINDOW"; }
service_up() { adb shell dumpsys activity services "$pkg" 2>/dev/null | grep -q "CallCardService"; }
# The card's heads-up notice (tag callcard, id 3011) or the service's own notice (id 741).
notice_up() { adb shell dumpsys notification 2>/dev/null | grep -E "pkg=$pkg" | grep -qE "id=3011|id=741"; }
logged() { adb logcat -d -s "$1:I" 2>/dev/null | grep -q "$2"; }
# Waits up to $1 seconds for "$2" (a function name) to succeed.
wait_for() { local end=$(( $(now_ms)+$1*1000 )); while [ "$(now_ms)" -lt "$end" ]; do "$2" && return 0; sleep 0.2; done; return 1; }
not_window() { ! window_up; }
not_service() { ! service_up; }
instrument() { adb shell am instrument -w -e class "kr.callbrief.pipeline.CallCardSeedTest#$1" "$runner" 2>&1 | tee -a "$log" | grep -q '^OK ('; }
gsm() { adb emu gsm "$@" >> "$log" 2>&1; }
# Ends any call and waits until the card and the service are gone (IDLE + 3 s).
hang_up() { gsm cancel "$1"; adb shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1; wait_for 5 not_window; wait_for 10 not_service; sleep 1; }
b() { if "$@"; then echo true; else echo false; fi; }

ringingWindow=false; stripAfter3s=false; goneAfterIdle=false; serviceGone=false; outgoing=false; unknownOff=false; personalNothing=false
write_result() {
  printf '{"ringingWindow":%s,"stripAfter3s":%s,"goneAfterIdle":%s,"serviceGone":%s,"outgoing":%s,"unknownOff":%s,"personalNothing":%s}\n' \
    "$ringingWindow" "$stripAfter3s" "$goneAfterIdle" "$serviceGone" "$outgoing" "$unknownOff" "$personalNothing" > "$ev/call-card.json"
  cat "$ev/call-card.json"
}
trap write_result EXIT

# ---- 준비 -------------------------------------------------------------------------------------------------------
adb wait-for-device
# A fresh app (the pipeline device test may have run before): no data but the preview day.
adb uninstall "$pkg" >/dev/null 2>&1 || true
adb install -r -g "$apk" >> "$log" 2>&1 || { say "install failed"; exit 1; }
adb install -r -g "$test_apk" >> "$log" 2>&1 || { say "test apk install failed"; exit 1; }
for p in READ_PHONE_STATE READ_CALL_LOG POST_NOTIFICATIONS; do adb shell pm grant "$pkg" "android.permission.$p" >/dev/null 2>&1 || true; done
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION "$pkg" >> "$log" 2>&1 || say "role not given"
# Screen on and unlocked (an overlay cannot show above the lock screen; that case is the notice, a host test).
adb shell svc power stayon true; adb shell locksettings set-disabled true >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
instrument seed || { say "seed failed"; exit 1; }
# The app's process once up, as on a phone in use, then the home screen.
adb shell am start -W -n "$pkg/.pipeline.MainActivity" >> "$log" 2>&1; sleep 3; adb shell input keyevent KEYCODE_HOME; sleep 1

# ---- 수신: 벨 → 카드, 받음 → 3초 뒤 띠, 끊음 → 창과 서비스 없음 -----------------------------------------------
adb logcat -c
start=$(now_ms); gsm call "$known"
if wait_for 10 window_up; then
  took=$(( $(now_ms)-start )); say "ringing: card window after ${took} ms (limit ${limit_ms})"
  [ "$took" -le "$limit_ms" ] && ringingWindow=true
else say "ringing: no card window"; fi
sleep 0.5; shot ringing
gsm accept "$known"
sleep 1.5
early=$(b logged CallCardOverlay "card collapsed")
sleep 3
if [ "$early" = false ] && logged CallCardOverlay "card collapsed" && window_up; then stripAfter3s=true; fi
say "answered: folded early=$early, folded after 4.5 s=$(b logged CallCardOverlay 'card collapsed'), window=$(b window_up)"
shot strip
gsm cancel "$known"
wait_for 3 not_window && goneAfterIdle=true
wait_for 10 not_service && serviceGone=true
say "ended: window gone=$goneAfterIdle, service gone=$serviceGone"
hang_up "$known"

# ---- 발신: 역할로 번호를 받고 그대로 건다 ------------------------------------------------------------------------
adb logcat -c
adb shell am start -a android.intent.action.CALL -d "tel:$known" >> "$log" 2>&1
placed=false; unmodified=false; card=false
on_place() { logged OutgoingCallService "onPlaceCall"; }
wait_for 10 on_place && placed=true
logged OutgoingCallService "placeCallUnmodified" && unmodified=true
wait_for 10 window_up && card=true
sleep 0.5; shot outgoing
say "outgoing: onPlaceCall=$placed placeCallUnmodified=$unmodified card=$card"
[ "$placed" = true ] && [ "$unmodified" = true ] && [ "$card" = true ] && outgoing=true
hang_up "$known"

# ---- 모르는 번호: 설정 꺼짐이면 창 없음, 켜짐이면 "전화부에 없는 번호예요" ----------------------------------------
adb logcat -c
gsm call "$unknown"
sleep 3; off_quiet=$(b not_window)
hang_up "$unknown"
instrument unknownOn || say "unknownOn failed"
adb logcat -c
gsm call "$unknown"
on_card=false; unknown_line() { window_up && logged CallCardOverlay "card shown unknown=true"; }
wait_for 10 unknown_line && on_card=true
sleep 0.5; shot unknown
hang_up "$unknown"
instrument unknownOff || say "unknownOff failed"
say "unknown: off quiet=$off_quiet, on card=$on_card"
[ "$off_quiet" = true ] && [ "$on_card" = true ] && unknownOff=true

# ---- 개인 번호: 창도 알림도 없음 --------------------------------------------------------------------------------
adb logcat -c
gsm call "$personal"
sleep 3
p_window=$(b window_up); p_notice=$(b notice_up); p_service=$(b service_up)
say "personal: window=$p_window notice=$p_notice service=$p_service"
[ "$p_window" = false ] && [ "$p_notice" = false ] && [ "$p_service" = false ] && personalNothing=true
hang_up "$personal"

adb logcat -d -s CallCardOverlay:I CallCardService:I OutgoingCallService:I CardStats:I >> "$log" 2>/dev/null || true
trap - EXIT
write_result
grep -q false "$ev/call-card.json" && exit 1
exit 0
