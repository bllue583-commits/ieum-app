#!/usr/bin/env bash
# v3.0 call card on an emulator (design 7.4, r11-3), v3.4 four more cases (DESIGN_V4 4.4-1, r14-4). Needs a booted emulator
# (adb + the emulator console: adb emu gsm ...) and the built debug + androidTest APKs. Made-up numbers only (PreviewSeed):
# 010-1111-2222 has a contact card, 010-7777-8888 is a personal number, 010-9999-0000 is unknown.
#
# Writes evidence/call-card.json: the checks (all true/false)
#   ringingWindow, stripAfter3s, goneAfterIdle, serviceGone, outgoing, unknownOff, personalNothing   (v3.0, seven)
#   outgoingNoRole  without the call redirection role: a made call still has its number at OFFHOOK and shows the card
#   callWaiting     a second call ringing during a call hides the first card; nothing until IDLE; no second card
#                   (null when the emulator does not announce the second ring: then it cannot be judged here)
#   coldWindow      the app's process killed (am kill; when a job keeps it busy, its own kill through run-as, as the
#                   low-memory killer would; never force-stop), then a call: the card within the limit
#   lockedNotice    screen off and locked (keyevent 26), then a call: the card's notice with its words
# and "info" (not checks, for the report): ms from ring (or dial) to the card window, and whether the window showed over the
# locked screen (DESIGN_V4 4.2-1: to be seen on the phone). Screenshots evidence/shots/card_*.png. The log lines it judged
# from, and per case the logcat of CallCardService, CallCardOverlay, ContactCards, CallSession (and CardStats,
# CallStateReceiver, OutgoingCallService, the process starts and deaths of the app) go to evidence/call-card.log.
# Exit 1 when any check is false (null is not false).
#
# The card window is FLAG_SECURE: on the screenshots it is a dark box where the card sits (its place, not its text). The
# card's text is checked by the host tests (CallCardOverlayTest); here the window, its log lines and the service are.
#
# CARD_WINDOW_MS: how soon the card must be there after the call rings (design: 1 second on a phone; device-test: 3000).
# COLD_WINDOW_MS: the same for a call that starts the app's process (default CARD_WINDOW_MS).
set -u
pkg=kr.callbrief
known=01011112222; unknown=01099990000; personal=01077778888
limit_ms=${CARD_WINDOW_MS:-1000}
cold_limit_ms=${COLD_WINDOW_MS:-$limit_ms}
ev=evidence; shots=$ev/shots; mkdir -p "$shots"
log=$ev/call-card.log; : > "$log"
apk=android/app/build/outputs/apk/debug/app-debug.apk
test_apk=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=kr.callbrief.test/androidx.test.runner.AndroidJUnitRunner
role=android.app.role.CALL_REDIRECTION

say() { echo "call-card: $*" | tee -a "$log"; }
now_ms() { date +%s%3N; }
shot() { adb exec-out screencap -p > "$shots/card_$1.png" 2>/dev/null || say "screenshot $1 failed"; }
# The card's overlay window: its title, or this app's window drawn with the "draw over other apps" app op.
window_up() { adb shell dumpsys window windows 2>/dev/null | grep -qE "이음 통화 카드|package=$pkg.*appop=SYSTEM_ALERT_WINDOW"; }
service_up() { adb shell dumpsys activity services "$pkg" 2>/dev/null | grep -q "CallCardService"; }
# The card's heads-up notice (tag callcard, id 3011) or the service's own notice (id 741).
notice_up() { adb shell dumpsys notification 2>/dev/null | grep -E "pkg=$pkg" | grep -qE "id=3011|id=741"; }
# The card's notice with words in it: a notice of this app in the card's channel (ieum.calls.card: the card's own notice 3011,
# or the service's 741 once it carries the card, DESIGN_V4 4.2-1) with a title and a text. The dump is read redacted (only
# the lengths of the words) and only true/false is kept.
notice_words() {
  adb shell dumpsys notification 2>/dev/null | awk -v p="pkg=$pkg" '
    function done() { if(on && ch && t && x) ok=1; on=0; ch=0; t=0; x=0 }
    /NotificationRecord\(/ { done(); on=(index($0,p)>0) }
    on && /ieum\.calls\.card/ { ch=1 }
    on && /android\.title=[A-Za-z]*String [([].+[])]/ { t=1 }
    on && /android\.(text|bigText)=[A-Za-z]*String [([].+[])]/ { x=1 }
    END { done(); exit !ok }'
}
app_pid() { adb shell pidof "$pkg" 2>/dev/null | tr -d '\r'; }
# The call state the system last announced (0 idle, 1 ringing, 2 off hook), for the log.
call_state() { adb shell dumpsys telephony.registry 2>/dev/null | tr -d '\r' | grep -m1 -oE "mCallState=[0-9]+" || echo "mCallState=?"; }
# This app's notices for the log: record lines, channels and the lengths of title and text (the dump is redacted by default;
# no words are written).
notice_lines() {
  { echo "-- notices of $pkg --"
    adb shell dumpsys notification 2>/dev/null | tr -d '\r' | awk -v p="pkg=$pkg" '
      /NotificationRecord\(/ { on=(index($0,p)>0); if(on) print }
      on && /mChannel=|android\.(title|text|bigText)=/ { print }'
  } >> "$log"
}
logged() { adb logcat -d -s "$1:I" 2>/dev/null | grep -q "$2"; }
count_logged() { adb logcat -d -s "$1:I" 2>/dev/null | grep -c "$2"; }
# Waits up to $1 seconds for "$2" (a function name) to succeed.
wait_for() { local end=$(( $(now_ms)+$1*1000 )); while [ "$(now_ms)" -lt "$end" ]; do "$2" && return 0; sleep 0.2; done; return 1; }
not_window() { ! window_up; }
not_service() { ! service_up; }
instrument() { adb shell am instrument -w -e class "kr.callbrief.pipeline.CallCardSeedTest#$1" "$runner" 2>&1 | tee -a "$log" | grep -q '^OK ('; }
gsm() { adb emu gsm "$@" >> "$log" 2>&1; }
# Ends any call and waits until the card and the service are gone (IDLE + 3 s).
hang_up() { gsm cancel "$1"; adb shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1; wait_for 5 not_window; wait_for 10 not_service; sleep 1; }
b() { if "$@"; then echo true; else echo false; fi; }
# r14-4 (DESIGN_V4 4.4-1): keeps the logcat of one case in the log, then clears it for the next case. r14-1's first run once had
# no window at the first ring and no log to see why.
keep_log() {
  { echo "---- logcat: $1 ----"
    adb logcat -d -v threadtime -s CallCardService:V CallCardOverlay:V ContactCards:V CallSession:V CardStats:V \
      CallStateReceiver:V OutgoingCallService:V 2>/dev/null
    adb logcat -d -v threadtime -s ActivityManager:I 2>/dev/null | grep -E "$pkg" | grep -E "Start proc|Killing|has died|kill" || true
  } >> "$log"
  adb logcat -c
}
# Starts a case: a clean logcat with a mark.
begin() { adb logcat -c; adb shell log -t CallCardTest "case $1" >/dev/null 2>&1 || true; say "== $1"; }
unlock() { adb shell locksettings set-disabled true >/dev/null 2>&1 || true; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true; }

checks="ringingWindow stripAfter3s goneAfterIdle serviceGone outgoing unknownOff personalNothing outgoingNoRole callWaiting coldWindow lockedNotice"
for c in $checks; do eval "$c=false"; done
ms_ringing=null; ms_noRole=null; ms_cold=null; locked_window=null
write_result() {
  { printf '{'
    for c in $checks; do printf '"%s":%s,' "$c" "${!c}"; done
    printf '"info":{"ringToWindowMs":%s,"noRoleDialToWindowMs":%s,"coldRingToWindowMs":%s,"lockedWindow":%s}}\n' \
      "$ms_ringing" "$ms_noRole" "$ms_cold" "$locked_window"
  } > "$ev/call-card.json"
  cat "$ev/call-card.json"
}
finish() { keep_log "end"; write_result; }
trap finish EXIT

# ---- 준비 -------------------------------------------------------------------------------------------------------
adb wait-for-device
# A fresh app (the pipeline device test may have run before): no data but the preview day.
adb uninstall "$pkg" >/dev/null 2>&1 || true
adb install -r -g "$apk" >> "$log" 2>&1 || { say "install failed"; exit 1; }
adb install -r -g "$test_apk" >> "$log" 2>&1 || { say "test apk install failed"; exit 1; }
for p in READ_PHONE_STATE READ_CALL_LOG POST_NOTIFICATIONS; do adb shell pm grant "$pkg" "android.permission.$p" >/dev/null 2>&1 || true; done
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
adb shell cmd role add-role-holder "$role" "$pkg" >> "$log" 2>&1 || say "role not given"
# Screen on and unlocked (the locked case comes last and turns the lock on by itself).
adb shell svc power stayon true; unlock
instrument seed || { say "seed failed"; exit 1; }
# The app's process once up, as on a phone in use, then the home screen.
adb shell am start -W -n "$pkg/.pipeline.MainActivity" >> "$log" 2>&1; sleep 3; adb shell input keyevent KEYCODE_HOME; sleep 1
keep_log "setup"

# ---- 수신: 벨 → 카드, 받음 → 3초 뒤 띠, 끊음 → 창과 서비스 없음 -----------------------------------------------
begin incoming
start=$(now_ms); gsm call "$known"
if wait_for 10 window_up; then
  took=$(( $(now_ms)-start )); ms_ringing=$took; say "ringing: card window after ${took} ms (limit ${limit_ms})"
  [ "$took" -le "$limit_ms" ] && ringingWindow=true
else say "ringing: no card window (pid=$(app_pid), service=$(b service_up), notice=$(b notice_up))"; fi
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
keep_log "incoming"

# ---- 발신: 역할로 번호를 받고 그대로 건다 ------------------------------------------------------------------------
begin outgoing
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
keep_log "outgoing"

# ---- 발신, 역할 없이: OFFHOOK에 온 번호로 카드 (v3.4, DESIGN_V4 4.1-1) ---------------------------------------------
begin outgoing-no-role
adb shell cmd role remove-role-holder "$role" "$pkg" >> "$log" 2>&1 || say "role not taken back"
held=$(adb shell cmd role get-role-holders "$role" 2>/dev/null | tr -d '\r' | grep -c "$pkg")
start=$(now_ms); adb shell am start -a android.intent.action.CALL -d "tel:$known" >> "$log" 2>&1
nr_card=false; nr_shown() { window_up && logged CallCardOverlay "card shown"; }
if wait_for 10 nr_shown; then nr_card=true; ms_noRole=$(( $(now_ms)-start )); fi
nr_place=$(b logged OutgoingCallService "onPlaceCall")
sleep 0.5; shot outgoing_norole
say "outgoing without role: role held=$held, onPlaceCall=$nr_place, card=$nr_card (${ms_noRole} ms)"
[ "$held" = 0 ] && [ "$nr_place" = false ] && [ "$nr_card" = true ] && outgoingNoRole=true
hang_up "$known"
adb shell cmd role add-role-holder "$role" "$pkg" >> "$log" 2>&1 || say "role not given again"
keep_log "outgoing-no-role"

# ---- 모르는 번호: 설정 꺼짐이면 창 없음, 켜짐이면 "전화부에 없는 번호예요" ----------------------------------------
begin unknown
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
say "unknown: off quiet=$off_quiet, on card=$on_card"
[ "$off_quiet" = true ] && [ "$on_card" = true ] && unknownOff=true
keep_log "unknown"

# ---- 통화 대기: 통화 중 둘째 벨 → 첫 카드 숨김, IDLE까지 아무것도 없음 (v3.4, DESIGN_V4 4.1-3) ----------------------
# The first call is from the number with a card (its strip stays in view while answered: a card with nothing in it is
# taken away at OFFHOOK since r14-2), the second from the unknown number with "전화부에 없는 번호도" still on: a card that
# would show by itself, so its absence is the waiting rule and not a missing card.
begin call-waiting
gsm call "$known"
first_card=false; first_line() { window_up && logged CallCardOverlay "card shown"; }
wait_for 10 first_line && first_card=true
gsm accept "$known"; sleep 4
strip_up=$(b window_up)
gsm call "$unknown"
# The rule can only be seen when the system announces the second ring (RINGING while off hook). Some emulator images keep
# announcing OFFHOOK: then the case cannot be judged here (null, not false) and stays with the host tests (R14OneCallTest).
rang=false; ringing_now() { [ "$(call_state)" = "mCallState=1" ]; }
wait_for 3 ringing_now && rang=true
hidden=false; wait_for 3 not_window && hidden=true
say "call waiting: second ring announced=$rang, $(call_state), calls in telecom=$(adb shell dumpsys telecom 2>/dev/null | grep -cE 'Call TC@|TC@[0-9]+:')"
sleep 3; quiet_ring=$(b not_window)
shot waiting
gsm accept "$unknown"; sleep 3; quiet_answered=$(b not_window)
shown_lines=$(count_logged CallCardOverlay "card shown")
gsm cancel "$unknown"; sleep 2; quiet_after_second=$(b not_window)
gsm cancel "$known"
w_gone=false; wait_for 3 not_window && w_gone=true
w_service=false; wait_for 10 not_service && w_service=true
say "call waiting: first card=$first_card, strip before second ring=$strip_up, hidden at second ring=$hidden, quiet while ringing=$quiet_ring, quiet after answer=$quiet_answered, quiet after second ended=$quiet_after_second, card lines=$shown_lines, gone=$w_gone, service gone=$w_service"
[ "$strip_up" = true ] && [ "$first_card" = true ] && [ "$hidden" = true ] && [ "$quiet_ring" = true ] && [ "$quiet_answered" = true ] && \
  [ "$quiet_after_second" = true ] && [ "$shown_lines" = 1 ] && [ "$w_gone" = true ] && [ "$w_service" = true ] && callWaiting=true
if [ "$rang" = false ] && [ "$callWaiting" = false ]; then callWaiting=null; say "call waiting: not judged (no RINGING announced during the call)"; fi
hang_up "$unknown"; hang_up "$known"
instrument unknownOff || say "unknownOff failed"
keep_log "call-waiting"

# ---- 개인 번호: 창도 알림도 없음 --------------------------------------------------------------------------------
begin personal
gsm call "$personal"
sleep 3
p_window=$(b window_up); p_notice=$(b notice_up); p_service=$(b service_up)
say "personal: window=$p_window notice=$p_notice service=$p_service"
[ "$p_window" = false ] && [ "$p_notice" = false ] && [ "$p_service" = false ] && personalNothing=true
hang_up "$personal"
keep_log "personal"

# ---- 콜드: 프로세스 없이 벨 (v3.4, DESIGN_V4 4.4-1). am kill: a stopped app (force-stop) gets no PHONE_STATE, that is normal --
begin cold
adb shell input keyevent KEYCODE_HOME; sleep 2
how=none
for i in 1 2 3 4 5 6; do adb shell am kill "$pkg" >> "$log" 2>&1; sleep 1; [ -z "$(app_pid)" ] && { how=amKill; break; }; sleep 4; done
if [ -n "$(app_pid)" ]; then
  # Still busy (a job after the last call keeps it out of the background): the process ends the way the low-memory killer
  # ends it. The app is not stopped, so the call's broadcast still starts it.
  adb shell run-as "$pkg" kill -9 "$(app_pid)" >> "$log" 2>&1 || true; sleep 1
  [ -z "$(app_pid)" ] && how=runAsKill
fi
before=$(app_pid)
say "cold: process gone by $how"
if [ -n "$before" ]; then say "cold: the process did not go"; else
  start=$(now_ms); gsm call "$known"
  if wait_for 10 window_up; then
    took=$(( $(now_ms)-start )); ms_cold=$took; say "cold: card window after ${took} ms (limit ${cold_limit_ms})"
    [ "$took" -le "$cold_limit_ms" ] && coldWindow=true
  else say "cold: no card window (pid=$(app_pid), service=$(b service_up), notice=$(b notice_up))"; fi
  sleep 0.5; shot cold
  hang_up "$known"
fi
keep_log "cold"

# ---- 잠금: 화면을 끄고 잠근 채 벨 → 카드 알림(글 있음), 창은 기록만 (v3.4, DESIGN_V4 4.4-1, 4.2-1) -------------------
begin locked
adb shell locksettings set-disabled false >/dev/null 2>&1 || true
adb shell input keyevent 26; sleep 2
lock_on=$(adb shell dumpsys window 2>/dev/null | grep -cE "mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true|KeyguardShowing=true")
gsm call "$known"
l_notice=false; wait_for 10 notice_words && l_notice=true
locked_window=$(b window_up)
say "locked: $(call_state), pid=$(app_pid), any notice=$(b notice_up), service=$(b service_up)"; notice_lines
sleep 0.5; shot locked
say "locked: lock screen on=$([ "$lock_on" -gt 0 ] && echo true || echo false), notice with words=$l_notice, window=$locked_window"
[ "$l_notice" = true ] && lockedNotice=true
hang_up "$known"
unlock
keep_log "locked"

trap - EXIT
finish
# null: a case the emulator could not make (call waiting without a RINGING announcement); not a failure, shown in the JSON.
for c in $checks; do [ "${!c}" = false ] && exit 1; done
exit 0
