#!/usr/bin/env python3
"""Watchdog decision logic for the ieum-inbox repository (design B2). Pure functions plus a small CLI.

Reads only times, counts and yes/no values from status/phone.json, status/last-run.json and the age of files under inbox/.
Never reads or prints transcripts, names or numbers. Prints a JSON list of {"title","body"} problems, or with
--open-issues the plan {"create": [problems], "close": [issue numbers]} the workflow carries out.
The mailbox token's expiry comes from the phone (heartbeat tokenExpiresMs): only the phone's replies carry
the github-authentication-token-expiration header; the workflow's own token has none.
"""
import argparse, json, os, subprocess, sys, time

HOUR = 3_600_000
PHONE_SILENT_MS = 12 * HOUR
FIRST_PASS_STUCK_MS = 24 * HOUR
QUEUE_STUCK_MS = 6 * HOUR
INBOX_STUCK_MS = 3 * HOUR
DISCOVERY_STUCK_MS = 12 * HOUR
CALL_LOG_MISMATCH_MS = 2 * HOUR
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


def hours(ms):
    return f"{ms / HOUR:.1f}시간"


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
    if bucket in ("RARE", "RESTRICTED"):
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


def evaluate(now_ms, phone, last_run, inbox_oldest_ms, inbox_count):
    """phone / last_run: dict or None (file missing). inbox_oldest_ms: epoch ms of the oldest waiting request or None."""
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
            # The phone is alive but has not looked at the recording folder for 12 hours (0: an app before v2.4, unknown).
            if d and now_ms - d > DISCOVERY_STUCK_MS:
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
        elif oq:
            qage = now_ms - int(oq)
            if qage > QUEUE_STUCK_MS:
                add(T_QUEUE, f"글자로 바꾸기를 기다리는 가장 오래된 녹음이 {hours(qage)} 됐어요 (기준 6시간). "
                    f"기다리는 녹음 {ms(phone, 'queuedCount')}개.\n\n추정 원인: 충전하지 않아 폰이 작업을 미루거나, 음성 인식 모델이 없거나, 절전 모드예요.")
        err = phone.get("lastUpdateError")
        if err:
            add(T_UPDATE, f"앱 업데이트가 실패했어요. 코드: {err}\n앱 버전: {phone.get('appVersion', '?')}\n\n추정 원인: 설치 권한, 저장 공간, 서명 불일치예요.")

    if inbox_oldest_ms is not None:
        iage = now_ms - inbox_oldest_ms
        if iage > INBOX_STUCK_MS:
            add(T_INBOX, f"분석을 기다리는 요청 {inbox_count}개 중 가장 오래된 것이 {hours(iage)} 됐어요 (기준 3시간).\n\n"
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
                "해야 할 일: docs/routines 의 최신 지시문을 루틴에 붙여넣어 주세요.")

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


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--now-ms", type=int, default=int(time.time() * 1000))
    ap.add_argument("--open-issues", default="", help="JSON file of open watchdog issues ([{number, title}]); output becomes a plan")
    ap.add_argument("--out", default="-")
    a = ap.parse_args(argv)
    files = inbox_files(a.root)
    times = [t for t in (commit_time_ms(a.root, f"inbox/{f}") for f in files) if t]
    problems = evaluate(a.now_ms, read_json(os.path.join(a.root, "status/phone.json")), read_json(os.path.join(a.root, "status/last-run.json")),
                        min(times) if times else None, len(files))
    result = problems
    if a.open_issues:
        with open(a.open_issues, encoding="utf-8") as f:
            result = plan(problems, json.load(f))
    text = json.dumps(result, ensure_ascii=False)
    if a.out == "-":
        print(text)
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
