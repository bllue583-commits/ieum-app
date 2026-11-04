#!/usr/bin/env python3
"""Watchdog decision logic for the ieum-inbox repository (design B2). Pure functions plus a small CLI.

Reads only times and counts from status/phone.json, status/last-run.json and the age of files under inbox/.
Never reads or prints transcripts, names or numbers. Prints a JSON list of {"title","body"} problems.
"""
import argparse, json, os, subprocess, sys, time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

HOUR = 3_600_000
PHONE_SILENT_MS = 12 * HOUR
QUEUE_STUCK_MS = 6 * HOUR
INBOX_STUCK_MS = 3 * HOUR
TOKEN_WARN_DAYS = 14

T_PHONE = "폰이 조용해요"
T_QUEUE = "자막이 밀려요"
T_INBOX = "분석이 멈췄어요"
T_ROUTINE = "루틴이 일을 안 해요"
T_LIMIT = "루틴이 한도에 걸렸어요"
T_PROMPT = "루틴 지시문 버전이 달라요"
T_UPDATE = "업데이트 실패"
T_TOKEN = "우편함 토큰이 곧 만료돼요"


def hours(ms):
    return f"{ms / HOUR:.1f}시간"


def evaluate(now_ms, phone, last_run, inbox_oldest_ms, inbox_count, token_expiry_ms=None):
    """phone / last_run: dict or None (file missing). inbox_oldest_ms: epoch ms of the oldest waiting request or None."""
    out = []

    def add(title, body):
        out.append({"title": title, "body": body})

    if phone is None:
        add(T_PHONE, "status/phone.json이 없어요.\n\n추정 원인: 앱이 아직 한 번도 상태를 보내지 못했거나 우편함 토큰이 막혔어요.")
    else:
        age = now_ms - int(phone.get("atMs", 0) or 0)
        if age > PHONE_SILENT_MS:
            add(T_PHONE, f"폰이 마지막으로 상태를 보낸 지 {hours(age)} 지났어요 (기준 12시간).\n\n"
                "추정 원인: 절전 모드로 앱이 멈췄거나, 폰이 꺼졌거나, 우편함 토큰이 만료됐어요.")
        oq = phone.get("oldestQueuedMs")
        if oq:
            qage = now_ms - int(oq)
            if qage > QUEUE_STUCK_MS:
                add(T_QUEUE, f"글자로 바꾸기를 기다리는 가장 오래된 녹음이 {hours(qage)} 됐어요 (기준 6시간). "
                    f"기다리는 녹음 {phone.get('queuedCount', '?')}개.\n\n추정 원인: 충전하지 않아 폰이 작업을 미루거나, 음성 인식 모델이 없거나, 절전 모드예요.")
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

    if token_expiry_ms is not None:
        left = (token_expiry_ms - now_ms) / (24 * HOUR)
        if left <= TOKEN_WARN_DAYS:
            add(T_TOKEN, f"우편함 토큰이 {max(left, 0):.0f}일 뒤에 만료돼요 (기준 14일 전).\n\n해야 할 일: 새 토큰을 만들어 저장소 비밀을 바꿔 주세요.")
    return out


def parse_expiry(header):
    """github-authentication-token-expiration header, e.g. '2026-12-01 10:00:00 UTC'. Empty or odd values give None."""
    if not header:
        return None
    header = header.strip()
    try:
        return int(datetime.strptime(header.replace(" UTC", ""), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp() * 1000)
    except ValueError:
        pass
    try:
        return int(parsedate_to_datetime(header).timestamp() * 1000)
    except (TypeError, ValueError):
        return None


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
    ap.add_argument("--token-expiry", default="", help="value of github-authentication-token-expiration, if any")
    ap.add_argument("--out", default="-")
    a = ap.parse_args(argv)
    files = inbox_files(a.root)
    times = [t for t in (commit_time_ms(a.root, f"inbox/{f}") for f in files) if t]
    problems = evaluate(a.now_ms, read_json(os.path.join(a.root, "status/phone.json")), read_json(os.path.join(a.root, "status/last-run.json")),
                        min(times) if times else None, len(files), parse_expiry(a.token_expiry))
    text = json.dumps(problems, ensure_ascii=False)
    if a.out == "-":
        print(text)
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
