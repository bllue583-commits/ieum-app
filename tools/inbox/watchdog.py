#!/usr/bin/env python3
"""Watchdog decision logic for the ieum-inbox repository (design B2). Pure functions plus a small CLI.

Reads only times, counts and yes/no values from status/phone.json, status/last-run.json and the age of files under inbox/,
plus app/version.json (version numbers) and earlier versions of status/phone.json from git history (v3.1: how long a
heartbeat value has held, since the watchdog keeps no state of its own).
Never reads or prints transcripts, names or numbers. Prints a JSON list of {"title","body"} problems, or with
--open-issues the plan {"create": [problems], "close": [issue numbers]} the workflow carries out.
The mailbox token's expiry comes from the phone (heartbeat tokenExpiresMs): only the phone's replies carry
the github-authentication-token-expiration header; the workflow's own token has none.

v3.4 (DESIGN_V4 4.3-2): it no longer runs in Actions (billing on the private repository stopped it on 11/5). The hourly and
the tidy routine run it as their last step with --apply OWNER/REPO: it lists the open ieum-watch issues and opens and closes
issues itself through the REST API (`gh api`), printing "watchdog: n opened, m closed". The self-reports are per routine
(status/last-run-hourly.json, status/last-run-tidy.json); the old shared status/last-run.json is read only when the new file
is missing. New rules: the hourly self-report's status commit is missing for two scheduled slots in a row, and a run whose
result commit did not go up (pushed=false).

v3.5 (DESIGN_V5 2.2-4): two morning report rules. Past 05:30 KST without report/<today>.json (only whether the file is
there; its text is never read), and KakaoTalk linked in the heartbeat but kakao.lastSentMs not from today past 09:00 KST
for two days in a row. r15-4: the second rule only while kakao.on is true (a heartbeat without the key counts as on), and
"카카오톡 연결이 풀렸어요" when kakao.linked is false with an error code starting TOKEN_ (and kakao.on is not false).

v3.6 (DESIGN_V6 3.3): three rules on the heartbeat's power block: battery runs over 60 minutes two days running, a recording
waiting for the charger over 36 hours, and killed plus deadline piece time over 20% of the run time two days running. The
recordings waiting for the charger by design are not counted in "자막이 밀려요".
"""
import argparse, datetime, json, os, subprocess, sys, time

HOUR = 3_600_000
PHONE_SILENT_MS = 12 * HOUR
FIRST_PASS_STUCK_MS = 24 * HOUR
QUEUE_STUCK_MS = 6 * HOUR
INBOX_STUCK_MS = 3 * HOUR
DISCOVERY_STUCK_MS = 12 * HOUR
CALL_LOG_MISMATCH_MS = 2 * HOUR
UPDATE_NOT_INSTALLED_MS = 24 * HOUR
INBOX_ERROR_STUCK_MS = 2 * HOUR
DB_FULL_PERCENT = 80
TOKEN_WARN_DAYS = 14

T_PHONE = "폰이 조용해요"
T_QUEUE = "자막이 밀려요"
T_INBOX = "분석이 멈췄어요"
T_ROUTINE = "루틴이 일을 안 해요"
T_LIMIT = "루틴이 한도에 걸렸어요"
T_PROMPT = "루틴 지시문 버전이 달라요"
T_UPDATE = "업데이트 실패"
T_TOKEN = "우편함 토큰이 곧 만료돼요"
T_DISCOVERY = "녹음 가져오기가 멈췄어요"
T_CALL_LOG = "통화 목록을 빠뜨리고 있어요"
T_NOT_INSTALLED = "새 버전이 설치되지 않아요"
T_INBOX_ERROR = "우편함 오류가 계속돼요"
T_DB_FULL = "폰 저장 공간이 거의 찼어요"
T_CARD_PERMISSION = "통화 카드 권한이 꺼져 있어요"
CARD_NO_PERMISSION_PER_DAY = 3

# v3.1 (DESIGN_V4 2.2-3): four rules. Titles stay fixed (plan() matches open issues by title); numbers go in the body.
T_OLD_VERSION = "폰이 옛 버전에 머물러 있어요"
T_ROUTINE_OLD = "루틴 지시문이 옛 버전이에요"
T_PHONE_STATE = "전화 상태 권한이 빠졌어요"
T_GAP = {
    "noRecording": "통화가 녹음되지 않고 있어요",
    "partial": "통화가 일부만 녹음되고 있어요",
    "folder": "녹음 폴더를 읽지 못해요",
    "tooMany": "녹음 폴더에 파일이 너무 많아요",
    "noMatch": "녹음은 있는데 통화기록과 이어지지 않아요",
}
GAP_BODY = {
    "noRecording": "최근 하루 통화 {calls}건 중 녹음 파일이 있는 통화가 {withFile}건이에요.\n\n"
                   "추정 원인: 전화 앱의 자동 통화 녹음이 꺼졌어요. 전화 앱 설정의 통화 녹음에서 자동으로 통화 녹음을 모든 통화로 켜야 해요.",
    "partial": "최근 하루 통화 {calls}건 중 녹음 파일이 있는 통화가 {withFile}건뿐이에요.\n\n"
               "추정 원인: 자동 통화 녹음이 일부 번호(연락처에 없는 번호 등)만 녹음하도록 되어 있어요.",
    "folder": "앱이 녹음 폴더를 읽지 못해요.\n\n추정 원인: 녹음 폴더 권한이 풀렸어요. 앱에서 녹음 폴더를 다시 골라야 해요.",
    "tooMany": "녹음 폴더의 파일이 너무 많아 목록이 잘려요.\n\n추정 원인: 녹음 폴더에 오래된 파일이 많이 쌓였어요. 오래된 녹음을 다른 폴더로 옮겨 주세요.",
    "noMatch": "녹음 파일이 있는 통화 {withFile}건 중 통화기록과 이어진 것이 {matched}건이에요.\n\n"
               "추정 원인: 녹음 파일 이름이나 시각이 통화기록과 맞지 않아요(아내에게는 알리지 않는 문제예요).",
}
OLD_VERSION_MS = 24 * HOUR
OLD_VERSION_FRESH_MS = 12 * HOUR
PHONE_STATE_OFF_MS = 24 * HOUR
GAP_HELD_MS = 48 * HOUR
# r12-8 (DESIGN_V4 2-3-6): calls over 100 s finished under this share of the last 48 hours, for this long.
T_LONG_CALLS = "긴 통화의 자막이 끝나지 않아요"
LONG_CALL_RATE_PCT = 80
LONG_CALL_LOW_MS = 24 * HOUR
HISTORY_MS = 50 * HOUR
HISTORY_MAX = 200

# v3.4 (DESIGN_V4 4.3-1, 4.3-2): the routine checks itself.
T_ROUTINE_MISSED = "매시간 분석이 예정 시각에 돌지 않았어요"
T_NOT_PUSHED = "루틴이 결과를 저장소에 올리지 못했어요"
LABEL = "ieum-watch"
MINUTE = 60_000
# A slot counts once this long has passed since it (a run takes minutes; the scheduler may start it late).
SLOT_DUE_MS = 40 * MINUTE
# A status commit from shortly before the slot (a clock a little fast) up to an hour after it is that slot's.
SLOT_EARLY_MS = 5 * MINUTE
SLOT_WINDOW_MS = 60 * MINUTE
SLOTS_MISSED = 2
SLOT_LOOKBACK_MS = 30 * HOUR
DEFAULT_CRON = "CRON_TZ=Asia/Seoul 34 7-22 * * *"
# Fixed offsets only: the routines run in Korea (no daylight saving) or in UTC.
ZONES_MS = {"Asia/Seoul": 9 * HOUR, "UTC": 0, "Etc/UTC": 0}
STATUS_FILES = {"hourly": "status/last-run-hourly.json", "tidy": "status/last-run-tidy.json"}
OLD_STATUS = "status/last-run.json"

# v3.5 (DESIGN_V5 2.2-4): the morning report. The tidy (04:46) writes report/<today>.json; the phone sends it at 05:30 and
# tries until 09:00.
T_REPORT_MISSING = "아침 보고서가 안 만들어졌어요"
T_KAKAO_NOT_SENT = "카카오톡 보고가 안 갔어요"
# v3.5 r15-4 (M2): the heartbeat says the link is down because Kakao no longer takes the token.
T_KAKAO_UNLINKED = "카카오톡 연결이 풀렸어요"
KAKAO_TOKEN_PREFIX = "TOKEN_"
REPORT_DUE_MIN = 5 * 60 + 30
KAKAO_DUE_MIN = 9 * 60
REPORT_DIR = "report"
REPORT_PROMPT_VERSION = 7

# v3.6 (DESIGN_V6 3.3): the heartbeat's power block (numbers and codes only).
T_BATTERY_HEAVY = "폰 배터리를 많이 써요"
T_CHARGER_WAIT = "충전을 기다리는 녹음이 쌓여요"
T_PIECES_WASTED = "자막 조각이 버려지고 있어요"
BATTERY_HEAVY_MIN = 60
CHARGER_WAIT_MIN = 36 * 60
PIECES_WASTED_SHARE = 0.2


def cron_field(text, lo, hi):
    """One cron field ("*", "34", "7-22", "*/30", "4,34", "8-22/2") as a sorted list, or None when it cannot be read."""
    out = set()
    for part in text.split(","):
        step = 1
        if "/" in part:
            part, s = part.split("/", 1)
            if not s.isdigit() or int(s) == 0:
                return None
            step = int(s)
        if part == "*":
            a, b = lo, hi
        elif "-" in part:
            x, y = part.split("-", 1)
            if not (x.isdigit() and y.isdigit()):
                return None
            a, b = int(x), int(y)
        elif part.isdigit():
            a = b = int(part)
        else:
            return None
        if a < lo or b > hi or a > b:
            return None
        out.update(range(a, b + 1, step))
    return sorted(out)


def parse_cron(text):
    """{"offset", "minutes", "hours"} of a daily cron like "CRON_TZ=Asia/Seoul 34 7-22 * * *", or None. Day, month and
    weekday must be "*" (the routines run every day)."""
    if not isinstance(text, str):
        return None
    parts = text.split()
    offset = 0
    if parts and parts[0].startswith("CRON_TZ="):
        zone = parts[0][len("CRON_TZ="):]
        if zone not in ZONES_MS:
            return None
        offset = ZONES_MS[zone]
        parts = parts[1:]
    if len(parts) != 5 or parts[2:] != ["*", "*", "*"]:
        return None
    minutes, hours = cron_field(parts[0], 0, 59), cron_field(parts[1], 0, 23)
    if not minutes or not hours:
        return None
    return {"offset": offset, "minutes": minutes, "hours": hours}


def report_crons(report):
    """The cron strings a self-report names ("crons" list or the one "cron"), parsed; unreadable ones are left out."""
    if not isinstance(report, dict):
        return []
    raw = report.get("crons") if isinstance(report.get("crons"), list) else [report.get("cron")]
    return [c for c in (parse_cron(x) for x in raw) if c]


def slots(crons, from_ms, to_ms):
    """Every scheduled time of the crons with from_ms <= t <= to_ms, sorted, without repeats."""
    out = set()
    day = 24 * HOUR
    for c in crons:
        first = (from_ms + c["offset"]) // day
        last = (to_ms + c["offset"]) // day
        for d in range(first, last + 1):
            for h in c["hours"]:
                for m in c["minutes"]:
                    t = d * day - c["offset"] + h * HOUR + m * MINUTE
                    if from_ms <= t <= to_ms:
                        out.add(t)
    return sorted(out)


def missed_slots(now_ms, crons, commit_ms):
    """The last SLOTS_MISSED scheduled slots that are due (SLOT_DUE_MS past), when none of them has a status commit in its
    window; else []. commit_ms: times of the commits that changed the hourly self-report."""
    due = slots(crons, now_ms - SLOT_LOOKBACK_MS, now_ms - SLOT_DUE_MS)[-SLOTS_MISSED:]
    if len(due) < SLOTS_MISSED:
        return []
    def kept(s):
        return any(s - SLOT_EARLY_MS <= t < s + SLOT_WINDOW_MS for t in commit_ms)
    return [] if any(kept(s) for s in due) else due


def kst(ms_value):
    t = (ms_value + KST_OFFSET_MS) // MINUTE
    return f"{(t // 60) % 24:02d}:{t % 60:02d}"


def routine_rules(now_ms, hourly, tidy, status_commits, add):
    """v3.4: (1) the hourly self-report's status commit is missing for two scheduled slots in a row (by the cron the report
    names; the analyze commit is no measure, a quiet day has none); (2) a routine's last result commit did not go up."""
    # Without any hourly self-report (a repository the routine never wrote to) there is nothing to miss.
    if status_commits is not None and isinstance(hourly, dict):
        crons =report_crons(hourly) or [parse_cron(DEFAULT_CRON)]
        gone = missed_slots(now_ms, crons, status_commits)
        if gone:
            last = max(status_commits) if status_commits else 0
            add(T_ROUTINE_MISSED, f"예정된 매시간 분석 {len(gone)}번({', '.join(kst(s) for s in gone)}, 한국 시각)에 자가 보고 커밋이 없어요 (기준 연속 2번).\n"
                + (f"마지막 자가 보고 커밋: {hours(now_ms - last)} 전.\n" if last else "자가 보고 커밋을 찾지 못했어요.\n")
                + "\n추정 원인: 예약 작업이 멈췄거나 일시 중지됐어요, 예약 작업에 저장소 연결이나 push 권한이 없어요, 구독 사용 한도예요. "
                  "예약 작업의 실행 기록과 마지막 답(\"확인 필요: 저장소 접근\")을 확인해 주세요.")
    names = [name for name, report in (("매시간 분석", hourly), ("새벽 정리", tidy)) if isinstance(report, dict) and report.get("pushed") is False]
    if names:
        add(T_NOT_PUSHED, f"{', '.join(names)}의 마지막 실행이 결과 커밋을 저장소에 올리지 못했어요 (pushed=false).\n\n"
            "추정 원인: 저장소 push 권한, 충돌이 풀리지 않음, 네트워크예요. 다음 실행이 다시 올리면 저절로 닫혀요.")


def kst_day_start(ms_value):
    """00:00 KST of the day ms_value falls in (epoch ms)."""
    day = 24 * HOUR
    return (ms_value + KST_OFFSET_MS) // day * day - KST_OFFSET_MS


def kst_date(ms_value):
    """YYYY-MM-DD of ms_value in Korea (the report file's name)."""
    t = datetime.datetime.fromtimestamp((ms_value + KST_OFFSET_MS) / 1000, tz=datetime.timezone.utc)
    return t.strftime("%Y-%m-%d")


def report_rules(now_ms, phone, history, tidy, report_exists, add):
    """v3.5 (DESIGN_V5 2.2-4). (1) Past 05:30 KST and report/<today>.json is not there (report_exists None: not checked), once
    the tidy's self-report names prompt 7 or later; the body says what the tidy reported (reportWritten). The issue closes by
    itself the next run before 05:30 or once the file is there. (2) The heartbeat says KakaoTalk is linked, it is past
    09:00 KST and the last send is not from today, and the same held yesterday (two days in a row): lastSentMs before
    yesterday's 00:00 while linked since yesterday's 09:00 at the latest. phone: the current heartbeat (12 hours at most)."""
    today0 = kst_day_start(now_ms)
    # Only once the tidy runs prompt 7 (the one that writes the report): on the release day the last tidy self-report is still
    # prompt 6's, and a report missing that day is no fault.
    writes_reports = isinstance(tidy, dict) and (num(tidy.get("promptVersion")) or 0) >= REPORT_PROMPT_VERSION
    if writes_reports and report_exists is False and now_ms >= today0 + REPORT_DUE_MIN * MINUTE:
        ended = num(tidy.get("endedMs"))
        if ended and ended >= today0:
            why = f"오늘 새벽 정리는 {kst(ended)}에 끝났고 보고서를 " + ("썼다고 했어요(reportWritten=true).\n"
                  if tidy.get("reportWritten") is True else "쓰지 못했어요(reportWritten=false).\n")
        else:
            why = "오늘 새벽 정리의 자가 보고가 없어요" + (f"(마지막 실행 {hours(now_ms - ended)} 전).\n" if ended else ".\n")
        add(T_REPORT_MISSING, f"한국 시각 05:30이 지났는데 report/{kst_date(now_ms)}.json이 없어요.\n" + why
            + "\n추정 원인: 새벽 정리(04:46)가 돌지 않았거나 늦게 끝났어요, 폰 자료가 오지 않아 \"정리할 것 없음\"이었어요, "
              "보고서가 검사(report_check.py)를 통과하지 못했어요. 폰은 06:00에 자기 자료로 짧은 보고를 보내요.")
    if not isinstance(phone, dict):
        return

    def kakao(h):
        k = h.get("kakao") if isinstance(h, dict) else None
        return k if isinstance(k, dict) else {}
    # r15-4 (M2): the switch [카카오톡으로 받기]; an older heartbeat without "on" counts as on.
    if kakao(phone).get("on", True) is False:
        return
    err0 = w_code(kakao(phone).get("error"))
    if kakao(phone).get("linked") is False and err0.startswith(KAKAO_TOKEN_PREFIX):
        add(T_KAKAO_UNLINKED, f"카카오가 폰의 토큰을 더 받지 않아 카카오톡 연결이 풀렸어요 (오류 코드: {err0}).\n"
            "그동안 아침 보고는 이음 앱 알림으로만 가요.\n"
            "\n추정 원인: 카카오 토큰이 만료됐거나 동의가 철회됐어요. 앱 설정의 아침 보고 묶음에서 [다시 연결]을 눌러 주세요.")
        return
    if now_ms < today0 + KAKAO_DUE_MIN * MINUTE:
        return
    if kakao(phone).get("linked") is not True:
        return
    last = num(kakao(phone).get("lastSentMs")) or 0
    yesterday0 = today0 - 24 * HOUR
    if last >= yesterday0:
        return  # sent today, or yesterday (one missed day only)
    since = held_since(phone, history, lambda h: kakao(h).get("linked") is True)
    if since is None or since > yesterday0 + KAKAO_DUE_MIN * MINUTE:
        return  # linked too recently for two missed mornings
    k = kakao(phone)
    err = w_code(k.get("error"))
    add(T_KAKAO_NOT_SENT, "카카오톡이 연결돼 있는데 어제와 오늘 아침 보고가 카카오톡으로 가지 않았어요 (기준 2일 연속, 09:00).\n"
        + (f"마지막 전송: {hours(now_ms - last)} 전.\n" if last else "카카오톡으로 보낸 적이 없어요.\n")
        + f"보낸 횟수 {num(k.get('sent')) or 0}, 실패 횟수 {num(k.get('failed')) or 0}.\n"
        + (f"마지막 오류 코드: {err}.\n" if err else "")
        + "\n추정 원인: 카카오 토큰이 만료됐거나 동의가 철회됐어요, 아침 보고서가 없었어요, 폰이 09:00까지 인터넷에 닿지 못했어요. "
          "앱 설정의 아침 보고 묶음에서 연결 상태를 확인해 주세요.")


def power(h):
    p = h.get("power") if isinstance(h, dict) else None
    return p if isinstance(p, dict) else {}


def sub(o, *keys):
    for k in keys:
        o = o.get(k) if isinstance(o, dict) else None
    return o if isinstance(o, dict) else {}


def charger_waiting(phone):
    """Recordings the heartbeat says wait for the charger (power.waiting.n), 0 without the block."""
    return num(sub(power(phone), "waiting").get("n")) or 0


def wasted_share(day):
    """(killedChunkMs + deadlineMs) / run ms of one power day ({today|prev}), None without run time."""
    if not day:
        return None
    run = 0
    for mode in ("battery", "plugged"):
        m = sub(day, mode)
        run += num(m.get("ms")) or (num(m.get("min")) or 0) * MINUTE
    if run <= 0:
        return None
    return ((num(day.get("killedChunkMs")) or 0) + (num(day.get("deadlineMs")) or 0)) / run


def power_rules(now_ms, phone, history, add):
    """v3.6 (DESIGN_V6 3.3). phone: the current heartbeat (12 hours at most). (1) Battery runs over 60 minutes on the phone's
    today in this heartbeat and in an earlier one of another day (two days; the budget is 20). (2) The oldest recording
    waiting for the charger waits over 36 hours. (3) Killed and deadline piece time over 20% of the run time on today and on
    prev (two days in a row: W1 and W2 are not working)."""
    if not isinstance(phone, dict) or not power(phone):
        return
    p = power(phone)
    today = sub(p, "today")

    def heavy(day):
        return (num(sub(day, "battery").get("min")) or 0) > BATTERY_HEAVY_MIN

    if heavy(today):
        # The other day: prev of this heartbeat, or an earlier heartbeat whose today was another day.
        day0 = num(today.get("day"))
        other = sub(p, "prev") if heavy(sub(p, "prev")) else None
        if other is None:
            for h in history or []:
                t = sub(power(h), "today")
                if t and num(t.get("day")) != day0 and heavy(t):
                    other = t
                    break
        if other is not None:
            add(T_BATTERY_HEAVY, f"이틀 연속 배터리로 자막을 오래 돌렸어요: 오늘 {num(sub(today, 'battery').get('min'))}분, "
                f"전날 {num(sub(other, 'battery').get('min'))}분 (기준 60분, 하루 예산 20분).\n"
                + (f"오늘 화면 꺼진 실행의 소모 {num(sub(today, 'battery').get('mAhScreenOff'))}mAh.\n"
                   if num(sub(today, 'battery').get('mAhScreenOff')) else "")
                + "\n추정 원인: 배터리 예산(20분)이 지켜지지 않아요. 실행 시간 계산이나 30분 간격, 예산 판정을 확인해 주세요.")
    waiting = sub(p, "waiting")
    oldest = num(waiting.get("oldestMin"))
    if (num(waiting.get("n")) or 0) > 0 and oldest is not None and oldest > CHARGER_WAIT_MIN:
        add(T_CHARGER_WAIT, f"충전을 기다리는 녹음 {num(waiting.get('n'))}개 중 가장 오래된 것이 {oldest // 60}시간 기다렸어요 (기준 36시간).\n"
            + (f"마지막 배터리 {num(p.get('batteryPct'))}%, 충전 {w_code(p.get('plugged')) or '?'}.\n" if num(p.get("batteryPct")) is not None else "")
            + "\n추정 원인: 폰을 밤에 충전기에 꽂지 않았거나, 충전 중에도 발열이나 아침 휴식으로 쉬었어요. 폰 신호등이 주황으로 알려요.")
    a, b = wasted_share(today), wasted_share(sub(p, "prev"))
    if a is not None and b is not None and a > PIECES_WASTED_SHARE and b > PIECES_WASTED_SHARE:
        add(T_PIECES_WASTED, f"자막 실행 시간 중 버려진 조각의 몫이 오늘 {round(a * 100)}%, 전날 {round(b * 100)}%예요 (기준 20%, 2일 연속).\n"
            f"오늘 버린 조각 {(num(today.get('killedChunkMs')) or 0) // 1000}초, 기한 초과 {(num(today.get('deadlineMs')) or 0) // 1000}초.\n"
            "\n추정 원인: 실행에 안 들어갈 조각을 시작하지 않기(W1)나 미리 반으로 나누기(W2)가 작동하지 않아요.")


def held_since(phone, history, pred):
    """Since when pred has held without a break: the heartbeat time (atMs) of the oldest heartbeat in the unbroken run
    that ends with the current one. None when the current heartbeat does not satisfy pred.
    history: earlier heartbeats as dicts (any order); entries that are not dicts are skipped."""
    if not isinstance(phone, dict) or not pred(phone):
        return None
    since = ms(phone, "atMs")
    older = sorted((h for h in (history or []) if isinstance(h, dict) and ms(h, "atMs") and ms(h, "atMs") < since),
                   key=lambda h: ms(h, "atMs"), reverse=True)
    for h in older:
        if not pred(h):
            break
        since = ms(h, "atMs")
    return since


def num(raw):
    """A whole number from a heartbeat or version.json field, or None (bools and free text are no numbers)."""
    if isinstance(raw, bool):
        return None
    if isinstance(raw, int):
        return raw
    if isinstance(raw, str) and raw.strip().isdigit():
        return int(raw.strip())
    return None


def v31_rules(now_ms, phone, last_run, version, version_ms, history, add):
    """The four v3.1 rules (DESIGN_V4 2.2-3). phone: the current heartbeat when it is at most 12 hours old, else None."""
    # (가) A newer version has been published for 24 hours and the phone still runs an older one.
    if isinstance(version, dict) and phone is not None:
        latest, running = num(version.get("versionCode")), num(phone.get("versionCode"))
        if latest and running and latest > running and version_ms and now_ms - version_ms > OLD_VERSION_MS:
            available = num(phone.get("updateAvailableCode"))
            err = w_code(phone.get("lastUpdateError"))
            add(T_OLD_VERSION, f"폰이 옛 버전({running})에 머물러 있어요. 새 버전({latest})이 나온 지 {hours(now_ms - version_ms)} 지났어요 (기준 24시간).\n"
                + (f"폰이 받아 둔 새 버전: {available}.\n" if available and available > running else "폰이 새 버전을 아직 받지 못했어요.\n")
                + (f"마지막 업데이트 오류 코드: {err}.\n" if err else "")
                + "\n추정 원인: 설치에 확인이 필요하거나(USER_ACTION_REQUIRED), 이 앱의 앱 설치 허용이 꺼져 있거나(INSTALL_SOURCE_NOT_ALLOWED), "
                  "새 버전 확인이 인터넷 연결을 기다리고 있어요. v3.0 이하에서 v3.1로는 알림을 한 번 눌러야 설치돼요.")
    # (나) A routine's self-report names an older prompt than the one the app repository published (v3.4: each routine's own
    # report; several behind make one issue).
    runs = last_run if isinstance(last_run, list) else [last_run]
    old_bodies = []
    for report in runs:
        old_bodies += routine_old(report, version, version_ms)
    if old_bodies:
        add(T_ROUTINE_OLD, "\n\n".join(old_bodies))
    if phone is None:
        return
    v31_phone_rules(now_ms, phone, history, add)


def routine_old(last_run, version, version_ms):
    """The (나) sentence for one self-report, as a list of zero or one body."""
    out = []
    if isinstance(version, dict) and isinstance(last_run, dict):
        routine = last_run.get("routine")
        field = {"hourly": "promptVersion", "tidy": "tidyVersion"}.get(routine)
        want = version.get(field) if field else None
        want = "" if want is None or isinstance(want, bool) else str(want).strip()
        # Only a run that ended after version.json last changed can be behind it: an empty run writes no report, so an old report
        # from before a release says nothing about the routine's text (11/5: the 16:36 v5 report stood after the 17:00 v6 paste).
        ended = num(last_run.get("endedMs"))
        if want and version_ms and (not ended or ended < version_ms):
            want = ""
        if want:
            got = last_run.get("promptVersion")
            got = "" if got is None or isinstance(got, bool) else str(got).strip()
            if got != want:
                out.append(f"{'매시간 분석' if routine == 'hourly' else '새벽 정리'} 루틴이 보고한 지시문 버전은 "
                    f"{w_code(got) or '없음'}인데 지금 앱 저장소의 지시문은 {w_code(want)}이에요.\n\n"
                    f"해야 할 일: 앱 저장소의 docs/routines/{routine}.md 를 이 저장소의 routines/{routine}.md 로 복사해 주세요"
                    f"(예약 작업은 그 파일을 읽어 따라요). 예약 작업 프롬프트가 아직 옛 전문이면 짧은 안내로 바꿔 주세요.")
    return out


def v31_phone_rules(now_ms, phone, history, add):
    """(다) to (마): the rules that read the heartbeat (at most 12 hours old)."""

    # (다) Phone state permission off for 24 hours (only heartbeats that carry permissions, v3.1 on).
    def phone_state_off(h):
        p = h.get("permissions")
        return isinstance(p, dict) and p.get("phoneState") is False
    since = held_since(phone, history, phone_state_off)
    if since is not None and now_ms - since > PHONE_STATE_OFF_MS:
        add(T_PHONE_STATE, f"전화 상태 권한이 {hours(now_ms - since)} 동안 꺼져 있어요 (기준 24시간). "
            "통화가 끝난 것을 바로 알지 못해 녹음 찾기와 통화 카드가 늦어요.\n\n"
            "해야 할 일: 휴대폰 설정의 애플리케이션에서 이음의 권한에 들어가 전화를 허용해 주세요.")
    # (라) The recording gap verdict (r12-1) the same for 2 days.
    verdict = phone.get("recordingGapVerdict")
    if isinstance(verdict, str) and verdict in T_GAP:
        since = held_since(phone, history, lambda h: h.get("recordingGapVerdict") == verdict)
        if since is not None and now_ms - since > GAP_HELD_MS:
            gap = phone.get("recordingGap") if isinstance(phone.get("recordingGap"), dict) else {}
            n = {k: (num(gap.get(k)) if num(gap.get(k)) is not None else "?") for k in ("calls", "withFile", "matched")}
            add(T_GAP[verdict], f"녹음 판정({verdict})이 {hours(now_ms - since)} 동안 같아요 (기준 2일).\n" + GAP_BODY[verdict].format(**n))
    # (마) r12-8 (DESIGN_V4 2-3-6): calls over 100 s of the last 48 hours finished under 80% for 24 hours (v3.2 heartbeats on).
    def long_low(h):
        r = long_rate(h)
        return r is not None and r < LONG_CALL_RATE_PCT
    since = held_since(phone, history, long_low)
    if since is not None and now_ms - since > LONG_CALL_LOW_MS:
        t = phone.get("transcription") if isinstance(phone.get("transcription"), dict) else {}
        lg = t.get("long48h") if isinstance(t.get("long48h"), dict) else {}
        wait = num(t.get("oldestWaitMin"))
        fails = t.get("failReasons") if isinstance(t.get("failReasons"), dict) else {}
        top = sorted(((w_code(k), num(v)) for k, v in fails.items() if w_code(k) and num(v)), key=lambda kv: -kv[1])[:3]
        add(T_LONG_CALLS, f"최근 48시간 100초 넘는 통화 {num(lg.get('total'))}건 중 자막이 끝난 것이 {num(lg.get('done'))}건"
            f"({long_rate(phone)}%)이에요. {hours(now_ms - since)} 동안 80% 아래예요 (기준 24시간).\n"
            + (f"가장 오래 기다린 녹음: {wait // 60}시간 {wait % 60}분.\n" if wait is not None else "")
            + (("자막 실패 사유: " + ", ".join(f"{k} {v}" for k, v in top) + ".\n") if top else "")
            + "\n추정 원인: 긴 녹음이 실행 시간 한도(10분)에 계속 잘리거나, 새 통화에 밀려 차례가 오지 않아요.")


def long_rate(h):
    """The heartbeat's transcription.long48h.ratePct (0..100), or None when it has no long call or no such numbers."""
    t = h.get("transcription") if isinstance(h, dict) else None
    lg = t.get("long48h") if isinstance(t, dict) else None
    if not isinstance(lg, dict) or not num(lg.get("total")):
        return None
    r = num(lg.get("ratePct"))
    return r if r is not None and 0 <= r <= 100 else None




def hours(ms):
    return f"{ms / HOUR:.1f}시간"


# v2.9 (r10-3): the hourly routine runs every 30 minutes from 07:00 to 22:30 KST (cron "*/30 7-22 * * *", Asia/Seoul).
# A request that went up at night waits for 07:00 by design, so its age counts only the time the routine runs.
KST_OFFSET_MS = 9 * HOUR
ROUTINE_FIRST_HOUR = 7
ROUTINE_END_HOUR = 23  # hourly cron is 34 7-22 KST: the last run is 22:34, the next one is 07:34


def routine_ms(from_ms, to_ms):
    """Milliseconds between from_ms and to_ms that fall in the routine's hours (07:00 to 23:00 KST)."""
    if to_ms <= from_ms:
        return 0
    day = 24 * HOUR
    total = 0
    start_day = (from_ms + KST_OFFSET_MS) // day
    end_day = (to_ms + KST_OFFSET_MS) // day
    for d in range(start_day, end_day + 1):
        lo = d * day - KST_OFFSET_MS + ROUTINE_FIRST_HOUR * HOUR
        hi = d * day - KST_OFFSET_MS + ROUTINE_END_HOUR * HOUR
        total += max(0, min(hi, to_ms) - max(lo, from_ms))
    return total


def ms(phone, key):
    try:
        return int(phone.get(key) or 0)
    except (TypeError, ValueError):
        return 0


def causes(phone, now_ms):
    """Likely reasons from the phone's last heartbeat (design B1). Only fixed sentences; nothing from the file is copied
    except the standby bucket, which is checked against a fixed list."""
    out = []
    if phone.get("consent") is False:
        out.append("정리 동의가 꺼져 있었어요(consent=false).")
    if phone.get("folder") is False:
        out.append("녹음 폴더 권한이 풀려 있었어요(folder=false). 앱에서 폴더를 다시 골라야 해요.")
    if phone.get("batteryFree") is False:
        out.append("배터리 최적화 예외가 꺼져 있었어요(batteryFree=false).")
    bucket = phone.get("standbyBucket")
    if bucket in ("RARE", "RESTRICTED", "NEVER"):
        out.append(f"절전 버킷이 {bucket}였어요. 앱을 한 번 열면 풀려요.")
    if phone.get("notifyAllowed") is False:
        out.append("알림이 꺼져 있었어요(notifyAllowed=false).")
    d = ms(phone, "lastDiscoveryMs")
    if d and now_ms - d > DISCOVERY_STUCK_MS:
        out.append(f"마지막 녹음 찾기가 {hours(now_ms - d)} 전이었어요.")
    return out


def with_causes(text, phone, now_ms):
    c = causes(phone, now_ms) if phone else []
    return text + ("\n\n마지막 하트비트 기준:\n" + "\n".join("- " + x for x in c) if c else "")


def evaluate(now_ms, phone, last_run, inbox_oldest_ms, inbox_count, version=None, version_ms=None, history=None, tidy_run=None,
             status_commits=None, report_exists=None):
    """phone / last_run: dict or None (file missing); last_run is the hourly routine's self-report (v3.4), tidy_run the tidy's.
    inbox_oldest_ms: epoch ms of the oldest waiting request or None.
    version: app/version.json (dict or None), version_ms: when it last changed (commit time). history: earlier heartbeats
    (dicts) of about the last two days, for the rules that need a value to hold for a while. status_commits: times (ms) of
    the commits that changed the hourly self-report in about the last day, or None to skip the missed-slot rule.
    report_exists: whether report/<today KST>.json is in the checkout (v3.5), or None to skip the missing-report rule."""
    out = []

    def add(title, body):
        out.append({"title": title, "body": body})

    if phone is None:
        add(T_PHONE, "status/phone.json이 없어요.\n\n추정 원인: 앱이 아직 한 번도 상태를 보내지 못했거나 우편함 토큰이 막혔어요.")
    else:
        age = now_ms - int(phone.get("atMs", 0) or 0)
        if age > PHONE_SILENT_MS:
            add(T_PHONE, with_causes(f"폰이 마지막으로 상태를 보낸 지 {hours(age)} 지났어요 (기준 12시간).\n\n"
                "추정 원인: 절전 모드로 앱이 멈췄거나, 폰이 꺼졌거나, 재부팅 뒤 앱을 열지 않았거나, 우편함 토큰이 만료됐어요.", phone, now_ms))
        else:
            d = ms(phone, "lastDiscoveryMs")
            attempt = ms(phone, "lastDiscoveryAttemptMs")
            # The phone is alive but no discovery cycle ended well for 12 hours. With tries reported (v2.6), a never-good
            # cycle (0) counts too: that was the silent failure of 11/4. Without tries (before v2.6), 0 stays "unknown".
            if attempt and now_ms - d > DISCOVERY_STUCK_MS:
                outcome = phone.get("lastDiscoveryOutcome")
                code = w_code(outcome)
                when = f"녹음 폴더를 끝까지 살펴본 지 {hours(now_ms - d)} 지났어요" if d else "녹음 폴더를 한 번도 끝까지 살펴보지 못했어요"
                add(T_DISCOVERY, with_causes(f"폰은 살아 있고 마지막 시도는 {hours(max(0, now_ms - attempt))} 전인데, {when} (기준 12시간)."
                    + (f" 마지막 결과 코드: {code}." if code else "")
                    + ("\n폴더의 녹음이 너무 많아 목록이 잘렸어요(inventoryTruncated)." if phone.get("inventoryTruncated") is True else "")
                    + "\n\n추정 원인: 녹음 폴더 권한이 풀렸거나, 찾기가 시간 한도에 걸리거나, 오류로 끝나요.", phone, now_ms))
            elif d and now_ms - d > DISCOVERY_STUCK_MS:
                add(T_DISCOVERY, with_causes(f"폰은 살아 있지만 녹음 폴더를 마지막으로 살펴본 지 {hours(now_ms - d)} 지났어요 (기준 12시간).\n\n"
                    "추정 원인: 녹음 폴더 권한이 풀렸거나, 자동 찾기 작업이 멈췄어요.", phone, now_ms))
            log = phone.get("callLog") if isinstance(phone.get("callLog"), dict) else {}
            device, stored, since = log.get("deviceTotal"), log.get("storedTotal"), ms(log, "mismatchSinceMs")
            # The phone's call log (10/1 on) and what the app stored differ for more than 2 hours: the app is missing entries.
            if isinstance(device, int) and isinstance(stored, int) and device != stored and since and now_ms - since > CALL_LOG_MISMATCH_MS:
                add(T_CALL_LOG, f"폰 통화 기록은 {device}건인데 앱이 저장한 것은 {stored}건이에요. {hours(now_ms - since)} 동안 달라요 (기준 2시간).\n\n"
                    + ("추정 원인: 앱이 통화 기록을 다 저장하지 못했어요(개인 번호·부재중 저장 전 버전이면 그 수만큼 적어요)."
                       if device > stored else "추정 원인: 폰에서 통화 기록을 지웠거나, 기록 읽기가 중간에 끊겼어요."))
        oq = phone.get("oldestQueuedMs")
        fp = phone.get("firstPass") if isinstance(phone.get("firstPass"), dict) else {}
        if fp and fp.get("done") is False:
            # First pass (10/1 on) still running: old recordings wait at the back by design, so the oldest one says nothing.
            # Stuck means no new subtitle for a day while recordings are left.
            lt = ms(phone, "lastTranscribedMs")
            if (fp.get("toTranscribe") or 0) > 0 and lt and now_ms - lt > FIRST_PASS_STUCK_MS:
                add(T_QUEUE, f"처음 정리 중인데 마지막 자막이 {hours(now_ms - lt)} 전이에요 (기준 24시간). "
                    f"남은 녹음 {fp.get('toTranscribe')}개, 약 {fp.get('audioMinutesLeft', '?')}분.\n\n"
                    "추정 원인: 충전하지 않아 폰이 작업을 미루거나, 음성 인식 모델이 없거나, 절전 모드예요.")
        elif "newQueuedCount" in phone:
            # v2.6: only new recordings, by when they entered the queue (old first-pass ones may rightly wait for the charger).
            # v3.6: recordings waiting for the charger by design (power.waiting) are not counted; their own rule is below.
            nq = phone.get("newOldestQueuedMs")
            if ms(phone, "newQueuedCount") > charger_waiting(phone) and nq and now_ms - int(nq) > QUEUE_STUCK_MS:
                add(T_QUEUE, f"글자로 바꾸기를 기다리는 새 녹음 중 가장 오래된 것이 대기열에 들어온 지 {hours(now_ms - int(nq))} 됐어요 (기준 6시간). "
                    f"기다리는 새 녹음 {ms(phone, 'newQueuedCount')}개.\n\n추정 원인: 배터리가 부족하거나, 음성 인식 모델이 없거나, 절전 모드예요.")
        elif oq:
            qage = now_ms - int(oq)
            if qage > QUEUE_STUCK_MS:
                add(T_QUEUE, f"글자로 바꾸기를 기다리는 가장 오래된 녹음이 {hours(qage)} 됐어요 (기준 6시간). "
                    f"기다리는 녹음 {ms(phone, 'queuedCount')}개.\n\n추정 원인: 충전하지 않아 폰이 작업을 미루거나, 음성 인식 모델이 없거나, 절전 모드예요.")
        available, ready_since = ms(phone, "updateAvailableCode"), ms(phone, "updateReadySinceMs")
        if available and ready_since and available > ms(phone, "versionCode") and now_ms - ready_since > UPDATE_NOT_INSTALLED_MS:
            add(T_NOT_INSTALLED, f"새 버전({available})이 폰에 준비된 지 {hours(now_ms - ready_since)} 지났는데 아직 설치되지 않았어요 (기준 24시간). "
                f"지금 버전: {ms(phone, 'versionCode')}.\n\n추정 원인: 설치에 사람의 확인이 필요하거나, 자동 설치가 꺼져 있거나, 앱을 열지 않았어요.")
        db = phone.get("dbPercent")
        if isinstance(db, (int, float)) and not isinstance(db, bool) and db > DB_FULL_PERCENT:
            add(T_DB_FULL, f"폰의 보호 저장소를 {int(db)}% 쓰고 있어요 (기준 80%). 다 차면 모든 저장이 멈춰요.\n\n"
                "해야 할 일: 오래된 기록 정리 규칙을 확인하거나 저장소 한도를 늘린 새 버전을 배포해 주세요.")
        ie, ie_since = w_code(phone.get("lastInboxError")), ms(phone, "lastInboxErrorSinceMs")
        # The phone keeps since when the code has been the same (lastInboxErrorSinceMs), so no state is kept here.
        if ie and ie_since and now_ms - ie_since > INBOX_ERROR_STUCK_MS:
            add(T_INBOX_ERROR, f"폰이 {hours(now_ms - ie_since)} 동안 같은 우편함 오류를 보내요 (기준 2시간). 코드: {ie}\n\n"
                "추정 원인: 우편함 토큰 만료나 권한 문제, 요청 형식 오류예요.")
        # v3.0 (r11-3): the call card fell back to a notice for lack of the overlay permission 3 times or more on one day
        # (callCard.today: the phone's counts of its current KST day, zeros on a day without such calls, so the issue closes).
        card = phone.get("callCard") if isinstance(phone.get("callCard"), dict) else {}
        today = card.get("today") if isinstance(card.get("today"), dict) else {}
        np = today.get("noPermission")
        if isinstance(np, int) and not isinstance(np, bool) and np >= CARD_NO_PERMISSION_PER_DAY:
            add(T_CARD_PERMISSION, f"하루에 {np}번 통화 카드 대신 알림으로 보여 드렸어요 (기준 3번). "
                f"카드가 뜬 횟수: {ms(today, 'shown')}번.\n\n"
                "추정 원인: 휴대폰 설정에서 이음의 다른 앱 위에 표시 권한이 꺼졌어요. 앱 설정의 통화 카드 묶음에서 다시 허용해 주세요.")
        err = phone.get("lastUpdateError")
        # v3.1 codes that are no install failure of their own: the old-version rule says them when they last.
        if err and w_code(err) not in ("USER_ACTION_REQUIRED", "INSTALL_SOURCE_NOT_ALLOWED"):
            add(T_UPDATE, f"앱 업데이트가 실패했어요. 코드: {err}\n앱 버전: {phone.get('appVersion', '?')}\n\n추정 원인: 설치 권한, 저장 공간, 서명 불일치예요.")

    fresh = phone if phone is not None and now_ms - ms(phone, "atMs") <= OLD_VERSION_FRESH_MS else None
    v31_rules(now_ms, fresh, [last_run, tidy_run], version, version_ms, history, add)
    routine_rules(now_ms, last_run, tidy_run, status_commits, add)
    report_rules(now_ms, fresh, history, tidy_run, report_exists, add)
    power_rules(now_ms, fresh, history, add)

    if inbox_oldest_ms is not None:
        # Counted in the routine's hours only (07:00 to 23:00 KST): six missed 30-minute runs and more.
        iage = routine_ms(inbox_oldest_ms, now_ms)
        if iage > INBOX_STUCK_MS:
            add(T_INBOX, f"분석을 기다리는 요청 {inbox_count}개 중 가장 오래된 것이 루틴이 도는 시간으로 {hours(iage)} 됐어요 (기준 3시간).\n\n"
                "추정 원인: 루틴 실행 한도, 루틴 저장소 토큰 만료, 루틴 일시 중지예요.")

    if last_run is not None:
        if inbox_count > 0 and int(last_run.get("processed", 0) or 0) == 0 and not last_run.get("limitHit"):
            add(T_ROUTINE, f"마지막 루틴 실행에서 처리한 건수가 0인데 분석을 기다리는 요청이 {inbox_count}개 있어요.\n\n"
                "추정 원인: 지시문 오류, 저장소 접근 실패, 모델 오류예요.")
        if last_run.get("limitHit"):
            add(T_LIMIT, f"마지막 루틴 실행이 사용 한도 오류로 끝났어요. 모델: {last_run.get('model', '?')}, "
                f"실패 {last_run.get('failed', '?')}건.\n\n추정 원인: 구독 사용 한도예요. 한도가 풀리면 다음 실행에서 이어져요.")
        if last_run.get("promptMismatch"):
            add(T_PROMPT, "루틴이 자기 지시문과 다른 버전의 요청을 받았어요 (promptMismatch).\n\n"
                "해야 할 일: 앱 저장소 docs/routines 의 최신 지시문을 이 저장소의 routines/ 로 복사해 주세요.")

    token_expiry_ms = ms(phone, "tokenExpiresMs") if phone else 0
    if token_expiry_ms:
        left = (token_expiry_ms - now_ms) / (24 * HOUR)
        if left <= TOKEN_WARN_DAYS:
            when = f"{left:.0f}일 뒤에 만료돼요" if left > 0 else "만료됐어요"
            add(T_TOKEN, f"폰에 든 우편함 토큰이 {when} (기준 14일 전).\n\n"
                "해야 할 일: 새 토큰을 만들어 앱 저장소 비밀 IEUM_INBOX_TOKEN을 바꾸고 새 APK를 배포해 주세요. "
                "만료 뒤에는 폰이 업데이트도 받지 못하니 그 전에 해야 해요. 서명 키가 토큰에서 나오지 않도록 "
                "IEUM_SIGNING_SEED 비밀이 있는지 먼저 확인해 주세요(없으면 서명이 바뀌어 업데이트가 막혀요). "
                "폰이 새 버전을 받으면 다음 하트비트에서 저절로 닫혀요.")
    return out


def w_code(raw):
    """A code from the heartbeat (INBOX_HTTP_401, DISCOVERY_ACCESS_CHANGED): letters, digits and _-: only, at most 60."""
    if not isinstance(raw, str):
        return ""
    return "".join(c for c in raw.strip()[:60] if c.isascii() and (c.isalnum() or c in "_-:"))


def plan(problems, open_issues):
    """open_issues: [{"number", "title"}] of open issues with the watchdog label. A problem with an open issue of the same
    title is left alone (no comment either); an open issue whose problem is gone is closed."""
    open_titles = {i.get("title") for i in open_issues}
    now_titles = {p["title"] for p in problems}
    return {"create": [p for p in problems if p["title"] not in open_titles],
            "close": [i["number"] for i in open_issues if i.get("title") not in now_titles]}


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def inbox_files(root):
    d = os.path.join(root, "inbox")
    if not os.path.isdir(d):
        return []
    return [f for f in sorted(os.listdir(d)) if f.endswith(".json")]


def commit_time_ms(root, rel):
    """Time the file was added to the repository (first commit), not the checkout time."""
    try:
        r = subprocess.run(["git", "log", "--diff-filter=A", "--format=%ct", "--", rel], cwd=root, capture_output=True, text=True, check=True)
        times = [int(x) for x in r.stdout.split()]
        return min(times) * 1000 if times else None
    except (subprocess.SubprocessError, ValueError, OSError):
        return None


def last_commit_ms(root, rel):
    """Time of the last commit that changed the file (git log -1), or None."""
    try:
        r = subprocess.run(["git", "log", "-1", "--format=%ct", "--", rel], cwd=root, capture_output=True, text=True, check=True)
        return int(r.stdout.strip()) * 1000 if r.stdout.strip() else None
    except (subprocess.SubprocessError, ValueError, OSError):
        return None


def phone_history(root, now_ms, rel="status/phone.json"):
    """Earlier heartbeats from git history, about the last two days (needs a checkout with history: fetch-depth 0)."""
    out = []
    try:
        since = (now_ms - HISTORY_MS) // 1000
        r = subprocess.run(["git", "log", f"--since=@{since}", f"-{HISTORY_MAX}", "--format=%H", "--", rel],
                           cwd=root, capture_output=True, text=True, check=True)
        for sha in r.stdout.split():
            s = subprocess.run(["git", "show", f"{sha}:{rel}"], cwd=root, capture_output=True, text=True)
            if s.returncode != 0:
                continue
            try:
                data = json.loads(s.stdout)
            except ValueError:
                continue
            if isinstance(data, dict):
                out.append(data)
    except (subprocess.SubprocessError, OSError):
        return []
    return out


def status_reports(root):
    """(hourly, tidy) self-reports: each routine's own file (v3.4), else the old shared status/last-run.json when its routine
    field names that routine (the transition until both routines run prompt v8)."""
    old = read_json(os.path.join(root, OLD_STATUS))
    out = []
    for routine in ("hourly", "tidy"):
        r = read_json(os.path.join(root, STATUS_FILES[routine]))
        if r is None and isinstance(old, dict) and old.get("routine") == routine:
            r = old
        out.append(r)
    return out[0], out[1]


def commit_times_ms(root, rels, since_ms):
    """Times (ms) of the commits since since_ms that changed any of rels; None when the history cannot be read."""
    try:
        r = subprocess.run(["git", "log", f"--since=@{since_ms // 1000}", "--format=%ct", "--", *rels],
                           cwd=root, capture_output=True, text=True, check=True)
        return [int(x) * 1000 for x in r.stdout.split()]
    except (subprocess.SubprocessError, ValueError, OSError):
        return None


def gh_api(args):
    """One REST call through the GitHub CLI (`gh api ...`): (exit code, stdout)."""
    try:
        r = subprocess.run(["gh", "api", *args], capture_output=True, text=True, timeout=60)
        return r.returncode, r.stdout
    except (subprocess.SubprocessError, OSError):
        return 1, ""


def apply_plan(repo, problems, gh=gh_api, label=LABEL):
    """What watchdog.yml did, through the REST API (v3.4: the routine runs it). Lists the open issues with the label, makes the
    plan, opens one issue per new problem and closes those whose problem is gone. Returns (plan, opened, closed); raises
    RuntimeError when the open issues cannot be listed (nothing is opened then: a second copy of every issue would follow)."""
    code, out = gh(["-X", "GET", f"repos/{repo}/issues", "-f", f"labels={label}", "-f", "state=open", "-f", "per_page=100"])
    try:
        listed = json.loads(out) if code == 0 else None
    except ValueError:
        listed = None
    if not isinstance(listed, list):
        raise RuntimeError("cannot list open issues")
    open_issues = [{"number": i.get("number"), "title": i.get("title")} for i in listed if isinstance(i, dict) and "pull_request" not in i]
    p = plan(problems, open_issues)
    opened = closed = 0
    if p["create"]:
        gh(["-X", "POST", f"repos/{repo}/labels", "-f", f"name={label}", "-f", "color=D93F0B", "-f", "description=ieum watchdog"])  # already there: fine
    for x in p["create"]:
        c, _ = gh(["-X", "POST", f"repos/{repo}/issues", "-f", f"title={x['title']}", "-f", f"body={x['body']}", "-f", f"labels[]={label}"])
        opened += c == 0
    for num in p["close"]:
        gh(["-X", "POST", f"repos/{repo}/issues/{num}/comments", "-f", "body=정상으로 돌아왔어요."])
        c, _ = gh(["-X", "PATCH", f"repos/{repo}/issues/{num}", "-f", "state=closed"])
        closed += c == 0
    return p, opened, closed


def main(argv=None, gh=gh_api):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--now-ms", type=int, default=int(time.time() * 1000))
    ap.add_argument("--open-issues", default="", help="JSON file of open watchdog issues ([{number, title}]); output becomes a plan")
    ap.add_argument("--apply", default="", metavar="OWNER/REPO",
                    help="list the open issues and open and close issues with `gh api` (v3.4, run by the routines); output is the plan")
    ap.add_argument("--out", default="-")
    a = ap.parse_args(argv)
    files = inbox_files(a.root)
    times = [t for t in (commit_time_ms(a.root, f"inbox/{f}") for f in files) if t]
    hourly, tidy = status_reports(a.root)
    commits = commit_times_ms(a.root, [STATUS_FILES["hourly"], OLD_STATUS], a.now_ms - SLOT_LOOKBACK_MS - HOUR)
    problems = evaluate(a.now_ms, read_json(os.path.join(a.root, "status/phone.json")), hourly,
                        min(times) if times else None, len(files),
                        version=read_json(os.path.join(a.root, "app/version.json")), version_ms=last_commit_ms(a.root, "app/version.json"),
                        history=phone_history(a.root, a.now_ms), tidy_run=tidy, status_commits=commits,
                        report_exists=os.path.isfile(os.path.join(a.root, REPORT_DIR, kst_date(a.now_ms) + ".json")))
    result = problems
    summary = None
    if a.apply:
        try:
            result, opened, closed = apply_plan(a.apply, problems, gh)
        except RuntimeError:
            print("watchdog: cannot list open issues (gh api); nothing opened")
            return 1
        summary = f"watchdog: {opened} opened, {closed} closed"
    elif a.open_issues:
        with open(a.open_issues, encoding="utf-8") as f:
            result = plan(problems, json.load(f))
    text = json.dumps(result, ensure_ascii=False)
    if a.out == "-":
        print(text)
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    if summary:
        print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
