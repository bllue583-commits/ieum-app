#!/usr/bin/env python3
"""이음 채점기: 우편함(ieum-inbox) 저장소의 원시 이력에서 채점표 10항목을 계산한다.

    python3 tools/inbox/score.py --inbox /path/to/ieum-inbox [--asof 2026-11-10] [--json out.json]

- 문턱은 tools/inbox/scorecard.json(미리 등록)에서만 읽는다. 이 파일은 계산만 한다.
- 읽는 것: status/phone.json 이력(하트비트), status/last-run-hourly.json 이력, status/last-run-tidy.json 이력,
  report/*.json의 head 숫자, status/calllog.txt 이력의 시각·길이·상태 칸(이름·번호 칸은 읽어서 버림),
  app/version.json, 있으면 status/gold-commit.json·status/gold-transcript.json·status/survey.json·status/secrets-scan.json.
- 내보내는 것: 숫자와 통과 여부뿐. 통화 내용·이름·번호는 출력하지 않는다.
- 점수 = 통과한 항목 수(10점 만점). 관측 일수가 모자라면 통과가 아니다(이유에 적는다).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys

KST = dt.timezone(dt.timedelta(hours=9))
HERE = os.path.dirname(os.path.abspath(__file__))


# ---------- 원시 데이터 읽기 ----------

def _git(inbox: str, *args: str) -> str:
    return subprocess.run(["git", "-C", inbox, *args], capture_output=True, text=True, check=False).stdout


def history(inbox: str, path: str) -> list[tuple[int, str]]:
    """(커밋 시각 초, 파일 내용) 오래된 것부터."""
    out = []
    for line in _git(inbox, "log", "--reverse", "--format=%H %ct", "--", path).splitlines():
        sha, ts = line.split()
        out.append((int(ts), _git(inbox, "show", f"{sha}:{path}")))
    return out


def json_history(inbox: str, path: str) -> list[dict]:
    out = []
    for _, text in history(inbox, path):
        try:
            value = json.loads(text)
        except ValueError:
            continue
        if isinstance(value, dict):
            out.append(value)
    return out


def read_json(inbox: str, path: str) -> dict | None:
    full = os.path.join(inbox, path)
    if not os.path.exists(full):
        return None
    try:
        with open(full, encoding="utf-8") as handle:
            value = json.load(handle)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


CALL_ROW = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d)$")
DURATION = re.compile(r"(\d+)분 (\d+)초")


def parse_calllog(text: str, year: int) -> list[dict]:
    """대조표 한 판에서 통화 줄을 읽는다. 이름·번호 칸(3, 4)은 구분용 해시로만 쓰고 버린다."""
    rows = []
    for line in text.splitlines():
        parts = line.split(" | ")
        if len(parts) != 9:
            continue
        match = CALL_ROW.match(parts[0])
        if not match:
            continue
        month, day, hour, minute = (int(x) for x in match.groups())
        dur = DURATION.search(parts[2])
        seconds = int(dur.group(1)) * 60 + int(dur.group(2)) if dur else 0
        rows.append({
            "key": (parts[0], parts[1], hash((parts[3], parts[4]))),  # 길이는 키에서 뺀다(나중에 고쳐진 같은 통화)
            "at": dt.datetime(year, month, day, hour, minute, tzinfo=KST),
            "seconds": seconds,
            "recorded": parts[6] == "O",
            "personal": parts[7] == "개인",
        })
    return rows


def calls_from_history(inbox: str, year: int) -> list[dict]:
    seen: dict = {}
    for _, text in history(inbox, "status/calllog.txt"):
        for row in parse_calllog(text, year):
            old = seen.get(row["key"])
            if old is None:
                seen[row["key"]] = row
            else:
                old["recorded"] = old["recorded"] or row["recorded"]
                old["personal"] = row["personal"]
                old["seconds"] = max(old["seconds"], row["seconds"])
    return list(seen.values())


# ---------- 날짜 도우미 ----------

def kst_day(ms: int) -> dt.date:
    return dt.datetime.fromtimestamp(ms / 1000, KST).date()


def weekdays_before(asof: dt.date, count: int) -> list[dt.date]:
    """asof 전날부터 거슬러 평일 count개(오래된 것부터)."""
    days, day = [], asof - dt.timedelta(days=1)
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day -= dt.timedelta(days=1)
    return days[::-1]


def last_per_day(beats: list[dict]) -> dict[dt.date, dict]:
    out: dict[dt.date, dict] = {}
    for beat in beats:
        at = beat.get("atMs")
        if isinstance(at, (int, float)) and beat.get("consent") is not False:
            out[kst_day(int(at))] = beat
    return out


def result(ok: bool, value, reason: str = "") -> dict:
    return {"pass": bool(ok), "value": value, "reason": reason}


def enough(per_day: dict, window: dict) -> tuple[bool, str, dict]:
    """per_day: {날짜: (통과 여부, 값)}. 관측된 가장 최근 평일 weekdays개로 판정한다(통화 없는 날·휴일은 건너뜀)."""
    need_seen, need_ok = window["weekdays"], window["min_pass_days"]
    recent = dict(sorted(per_day.items())[-need_seen:])
    days_ok = sum(1 for ok, _ in recent.values() if ok)
    values = {day: value for day, (_, value) in recent.items()}
    if len(recent) < need_seen:
        return False, f"관측 {len(recent)}/{need_seen}일(통과 {days_ok}일)", values
    return days_ok >= need_ok, f"통과 {days_ok}/{len(recent)}일(기준 {need_ok}일)", values


# ---------- 항목 ----------

def item_report_coverage(cfg, window, days, daily, reports, calls):
    """1. 아침 보고 완전성: 어제 통화(녹음 대상, 개인 제외) 중 보고에 들어간 비율."""
    per_day = {}
    for day in days:
        report_day = day + dt.timedelta(days=1)
        beat = daily.get(report_day) or {}
        cov = (beat.get("report") or {}).get("coverage") or {}
        approx = sum(1 for c in calls if c["at"].date() == day and c["seconds"] >= 20 and not c["personal"])
        if cov.get("yesterdayCalls"):
            total, inside, how = cov["yesterdayCalls"], cov.get("inReport", 0), "phone"
        else:  # v3.7 전: 대조표에서 어림(연결 20초 이상, 개인 제외)
            head = (reports.get(report_day.isoformat()) or {}).get("head") or {}
            total, inside, how = approx, head.get("calls"), "approx"
            if inside is None:
                continue
        if total < cfg["min_calls"]:
            continue
        ratio = min(1.0, inside / total)
        trusted = how == "approx" or approx == 0 or total >= 0.7 * approx  # 폰이 분모를 줄여 보고하면 통과가 아님
        per_day[day.isoformat()] = (ratio >= cfg["min_ratio"] and trusted,
                                    {"ratio": round(ratio, 3), "in": inside, "of": total, "calllog": approx, "how": how})
    passed, why, values = enough(per_day, window)
    return result(passed, values, why)


def item_timeliness(cfg, window, days, daily):
    """2. 적시성: 통화 끝에서 결과 적용까지."""
    per_day = {}
    for day in days:
        lat = (daily.get(day) or {}).get("latency") or {}
        lat = lat.get("day") or lat  # v3.7부터 그날 통화만의 값(latency.day), 그 전은 7일 이동값
        if lat.get("p50Min") is None:
            continue
        ok = lat["p50Min"] <= cfg["p50_max_min"] and (lat.get("p90Min") or 10 ** 9) <= cfg["p90_max_min"]
        per_day[day.isoformat()] = (ok, {"p50": lat["p50Min"], "p90": lat.get("p90Min")})
    passed, why, values = enough(per_day, window)
    return result(passed, values, why)


def item_call_card(cfg, window, asof, daily):
    """3. 통화 카드: 최근 14일의 날짜별 today 묶음 합."""
    since = asof - dt.timedelta(days=window["calendar_days"])
    calls = reached = refused = 0
    per_day, latest = {}, None
    for day, beat in sorted(daily.items()):
        card = beat.get("callCard") or {}
        today = card.get("today") or {}
        if not (since <= day < asof) or str(card.get("day")) != day.strftime("%Y%m%d"):
            continue
        refused += today.get("fgsRefused", 0)
        # v3.6.1부터 drawn(창이 실제로 그려진 통화). 그 전은 shown+locked(창 추가만 확인 — 숨겨진 창도 셈)
        got = today["drawn"] if "drawn" in today else today.get("shown", 0) + today.get("locked", 0)
        # calls가 덜 세진 날(프로세스가 먼저 죽음)도 실패가 있으면 분모에 넣는다
        # v3.6.1부터는 한 통화가 noPermission과 fallback에 함께 세질 수 있어 drawn+fallback만 더한다
        missed = today.get("fallback", 0) if "drawn" in today else today.get("noPermission", 0)
        base = max(today.get("calls", 0) - today.get("ignored", 0) - today.get("off", 0), got + missed)
        if base <= 0:
            continue
        calls, reached, latest = calls + base, reached + min(got, base), card
        per_day[day.isoformat()] = {"reached": min(got, base), "of": base, "noPermission": today.get("noPermission", 0)}
    if calls < cfg["min_calls"] or latest is None:
        return result(False, per_day, f"통화 {calls}건(기준 {cfg['min_calls']}건 이상)")
    reach = reached / calls
    ring = (latest.get("ringToCardMs") or {}).get("p50")
    cold = (latest.get("ringToCardMsCold") or {}).get("p50")
    ok = (reach >= cfg["min_reach"] and ring is not None and ring <= cfg["ring_p50_max_ms"]
          and (cold is None or cold <= cfg["cold_p50_max_ms"]) and refused == 0)
    return result(ok, {"reach": round(reach, 3), "calls": calls, "ringP50": ring, "coldP50": cold, "days": per_day},
                  f"도달 {reached}/{calls}")


def item_gold(cfg, gold, keys, mins):
    """4·5. 정답지 대비 수치(status/gold-commit.json, status/gold-transcript.json — 측정기가 숫자만 올린다)."""
    if not gold:
        return result(False, None, "정답지 측정 없음(status/gold-commit.json 또는 gold-transcript.json)")
    block = {k: gold.get(k) for k in list(keys) + list(mins)}
    for key, (op, limit) in {**keys, **mins}.items():
        value = gold.get(key)
        if value is None:
            return result(False, block, f"{key} 없음")
        if (op == ">=" and value < limit) or (op == "<=" and value > limit):
            return result(False, block, f"{key} {value} (기준 {op} {limit})")
    return result(True, block, "")


def item_battery(cfg, window, days, daily, survey):
    """6. 배터리: 배터리로 돈 자막 분(다음 날 하트비트의 prev) + 배터리 화면 비중 1회."""
    per_day = {}
    for day in days:
        prev = ((daily.get(day + dt.timedelta(days=1)) or {}).get("power") or {}).get("prev") or {}
        if prev.get("day") != day.isoformat():
            continue
        minutes = (prev.get("battery") or {}).get("min")
        if minutes is None:
            continue
        per_day[day.isoformat()] = (minutes <= cfg["max_battery_run_min"], minutes)
    passed, why, values = enough(per_day, window)
    share = (survey or {}).get("batterySharePct")
    if share is None:
        return result(False, {"batteryRunMin": values, "sharePct": None}, why + ", 배터리 화면 비중 미확인")
    return result(passed and share <= cfg["max_share_pct"], {"batteryRunMin": values, "sharePct": share}, why)


def item_storage(cfg, asof, daily):
    """7. 저장소: 봉인 DB 사용률(7일 최대), 7일 기울기(회귀), 열기 시간."""
    points = [(day, beat["dbPercent"]) for day, beat in sorted(daily.items())
              if beat.get("dbPercent") is not None and asof - dt.timedelta(days=8) <= day < asof]
    if len(points) < 5:
        return result(False, None, f"관측 {len(points)}/5일")
    xs = [(day - points[0][0]).days for day, _ in points]
    ys = [value for _, value in points]
    mean_x, mean_y = statistics.mean(xs), statistics.mean(ys)
    spread = sum((x - mean_x) ** 2 for x in xs)
    slope = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / spread if spread else 0.0
    opens = [beat["dbOpenMs"] for day, beat in daily.items()
             if asof - dt.timedelta(days=8) <= day < asof and (beat.get("dbOpenMs") or 0) > 0]
    open_ms = statistics.median(opens) if opens else None
    ok = (max(ys) <= cfg["max_db_percent"] and slope <= cfg["max_slope_per_day"]
          and open_ms is not None and open_ms <= cfg["max_open_ms"])
    return result(ok, {"dbPercentMax": max(ys), "slopePerDay": round(slope, 2), "openMsMedian": open_ms}, "")


def item_security(cfg, version, latest, scan):
    """8. 보안: 배포본 표시(version.json), 폰 표시(하트비트 security), 비밀 스캔 결과."""
    sec = (latest or {}).get("security") or {}
    value = {
        "buildType": (version or {}).get("buildType"),
        "seedIndependent": (version or {}).get("seedIndependent"),
        "certCheck": sec.get("certCheck"),
        "saltedHash": sec.get("saltedHash"),
        "secretFindings": (scan or {}).get("findings"),
    }
    ok = (value["buildType"] == cfg["build_type"] and value["seedIndependent"] is True and value["certCheck"] is True
          and value["saltedHash"] is True and value["secretFindings"] is not None
          and value["secretFindings"] <= cfg["max_secret_findings"])
    missing = [k for k, v in value.items() if v in (None, False) or (k == "buildType" and v != cfg["build_type"])]
    return result(ok, value, "미충족: " + ", ".join(missing) if missing else "")


def cron_hours(cron: str) -> list[int]:
    fields = cron.replace("CRON_TZ=Asia/Seoul", "").split()
    hours: list[int] = []
    for part in fields[1].split(","):
        if "-" in part:
            low, high = part.split("-")
            hours += list(range(int(low), int(high) + 1))
        elif part != "*":
            hours.append(int(part))
    return hours or list(range(24))


def item_operations(cfg, window, asof, hourly_runs, tidy_runs, daily):
    """9. 운영: 예약 작업 슬롯, 아침 보고서, 카톡 전송일, 자막 실패율."""
    span = window["calendar_days"]
    since = asof - dt.timedelta(days=span)
    ran, cron_by_day = set(), {}
    for run in hourly_runs:
        started = run.get("startedMs")
        if not isinstance(started, (int, float)):
            continue
        when = dt.datetime.fromtimestamp(started / 1000, KST)
        ran.add((when.date(), when.hour))
        if run.get("cron"):
            cron_by_day[when.date()] = run["cron"]  # 그날의 cron(바뀐 날 이전을 새 cron으로 세지 않는다)
    first = min((d for d, _ in ran), default=asof)
    expected, cron = [], None
    for i in range(span):
        day = since + dt.timedelta(days=i)
        earlier = [c for d, c in sorted(cron_by_day.items()) if d <= day]
        cron = earlier[-1] if earlier else cron
        if day >= first:
            expected += [(day, hour) for hour in (cron_hours(cron) if cron else list(range(7, 23)))]
    days_seen = len({d for d, _ in expected})
    slot_ratio = sum(1 for slot in expected if slot in ran) / len(expected) if expected else 0.0
    report_days = {kst_day(int(run["startedMs"])) for run in tidy_runs
                   if run.get("reportWritten") and isinstance(run.get("startedMs"), (int, float))}
    tidy_expected = [since + dt.timedelta(days=i) for i in range(span)
                     if since + dt.timedelta(days=i) >= min(report_days, default=asof)]
    report_ratio = sum(1 for d in tidy_expected if d in report_days) / len(tidy_expected) if tidy_expected else 0.0
    beats = [b for d, b in sorted(daily.items()) if since <= d < asof]
    fail_rate = None
    win14 = ((beats[-1].get("transcription") or {}).get("window14") or {}) if beats else {}
    if win14.get("created"):  # v3.7부터: 창 안에 생긴 녹음 중 최종 실패한 서로 다른 녹음 수 ÷ 그 녹음 수
        fail_rate = win14.get("failed", 0) / win14["created"]
    elif len(beats) >= 2:  # 그 전: 누적 실패 수 차이로 어림(재큐된 녹음이 다시 실패하면 또 세므로 높게 나온다)
        failed = (beats[-1].get("transcriptionFailed") or 0) - (beats[0].get("transcriptionFailed") or 0)
        done = sum(sum(v for v in ((b.get("transcription") or {}).get("doneByLen48h") or {}).values()) for b in beats) / 2
        if failed < 0:
            fail_rate = 1.0  # 카운터가 줄었다 = 초기화. 통과로 치지 않는다
        elif failed + done > 0:
            fail_rate = failed / (failed + done)
    # 카톡: 달력 날짜 기준. 하트비트가 없는 날은 안 간 날로 센다
    kakao_days = kakao_sent = 0
    previous = None
    for day in sorted(daily):
        beat = daily[day]
        sent_now = (beat.get("kakao") or {}).get("sent")
        if since <= day < asof and (beat.get("kakao") or {}).get("on") and previous is not None and sent_now is not None:
            kakao_sent += sent_now > previous
        if sent_now is not None:
            previous = sent_now
    kakao_on_since = min((d for d, b in daily.items() if (b.get("kakao") or {}).get("on")), default=None)
    if kakao_on_since is not None:
        kakao_days = sum(1 for i in range(span) if since + dt.timedelta(days=i) > kakao_on_since)
    kakao_ratio = min(1.0, kakao_sent / kakao_days) if kakao_days else None
    value = {"slotRatio": round(slot_ratio, 3), "reportRatio": round(report_ratio, 3),
             "failRate": None if fail_rate is None else round(fail_rate, 3),
             "kakaoRatio": None if kakao_ratio is None else round(kakao_ratio, 3), "daysSeen": days_seen}
    if days_seen < span:
        return result(False, value, f"관측 {days_seen}/{span}일")
    ok = (slot_ratio >= cfg["min_slot_ratio"] and report_ratio >= cfg["min_report_days_ratio"]
          and fail_rate is not None and fail_rate <= cfg["max_fail_rate"]
          and kakao_ratio is not None and kakao_ratio >= cfg["min_kakao_days_ratio"])
    return result(ok, value, "")


def item_use(cfg, window, asof, daily, survey):
    """10. 쓰임과 만족: 하트비트 use(날짜별 횟수) + 설문(status/survey.json)."""
    since = asof - dt.timedelta(days=window["calendar_days"])
    open_days = done = seen = 0
    for day, beat in daily.items():
        use = ((beat.get("use") or {}).get("prev")) or {}
        if not (since < day <= asof) or not use:
            continue
        seen += 1
        open_days += (use.get("opens", 0) + use.get("kakaoOpen", 0)) > 0
        done += use.get("taskDone", 0)
    weeks = window["calendar_days"] / 7
    value = {"daysSeen": seen, "openDaysPerWeek": round(open_days / weeks, 1), "taskDonePerWeek": round(done / weeks, 1),
             "sus": (survey or {}).get("sus"), "veryDisappointed": (survey or {}).get("veryDisappointed")}
    if seen < window["calendar_days"]:
        return result(False, value, f"사용 숫자 관측 {seen}/{window['calendar_days']}일")
    ok = (value["openDaysPerWeek"] >= cfg["min_open_days_per_week"] and value["taskDonePerWeek"] >= cfg["min_task_done_per_week"]
          and (value["sus"] or 0) >= cfg["min_sus"] and value["veryDisappointed"] is True)
    return result(ok, value, "")


# ---------- 전체 ----------

def score(inbox: str, asof: dt.date, card: dict) -> dict:
    window, items = card["window"], card["items"]
    beats = json_history(inbox, "status/phone.json")
    daily = last_per_day(beats)
    days = weekdays_before(asof, window["weekdays"] + 5)  # 통화 없는 날·휴일을 건너뛸 여유(최대 평일 15일 전까지)
    reports = {}
    report_dir = os.path.join(inbox, "report")
    if os.path.isdir(report_dir):
        for name in os.listdir(report_dir):
            if name.endswith(".json"):
                reports[name[:-5]] = read_json(inbox, f"report/{name}") or {}
    calls = calls_from_history(inbox, asof.year)
    survey = read_json(inbox, "status/survey.json")
    gold_commit = read_json(inbox, "status/gold-commit.json")
    gold_text = read_json(inbox, "status/gold-transcript.json")
    latest = beats[-1] if beats else {}
    c4, c5 = items["4_commitments"], items["5_transcript"]
    out = {
        "1_report_coverage": item_report_coverage(items["1_report_coverage"], window, days, daily, reports, calls),
        "2_timeliness": item_timeliness(items["2_timeliness"], window, days, daily),
        "3_call_card": item_call_card(items["3_call_card"], window, asof, daily),
        "4_commitments": item_gold(c4, gold_commit, {"commitRecall": (">=", c4["min_recall"]),
                                              "commitPrecision": (">=", c4["min_precision"]),
                                              "numberExact": (">=", c4["min_number_exact"])},
                                   {"commitCalls": (">=", c4["min_calls"]), "commitNumbers": (">=", c4["min_numbers"])}),
        "5_transcript": item_gold(c5, gold_text, {"cer": ("<=", c5["max_cer"]), "numberAcc": (">=", c5["min_number_acc"]),
                                             "nameAcc": (">=", c5["min_name_acc"])},
                                  {"transcriptMinutes": (">=", c5["min_minutes"])}),
        "6_battery": item_battery(items["6_battery"], window, days, daily, survey),
        "7_storage": item_storage(items["7_storage"], asof, daily),
        "8_security": item_security(items["8_security"], read_json(inbox, "app/version.json"), latest,
                                    read_json(inbox, "status/secrets-scan.json")),
        "9_operations": item_operations(items["9_operations"], window, asof,
                                        json_history(inbox, "status/last-run-hourly.json"),
                                        json_history(inbox, "status/last-run-tidy.json"), daily),
        "10_use_and_satisfaction": item_use(items["10_use_and_satisfaction"], window, asof, daily, survey),
    }
    return {"asof": asof.isoformat(), "scorecardVersion": card["version"],
            "score": sum(1 for v in out.values() if v["pass"]), "of": len(out), "items": out}


def table(report: dict) -> str:
    lines = [f"채점 {report['asof']} (채점표 v{report['scorecardVersion']}): **{report['score']}/{report['of']}**", "",
             "| 항목 | 판정 | 값 | 이유 |", "| --- | --- | --- | --- |"]
    for name, item in report["items"].items():
        value = json.dumps(item["value"], ensure_ascii=False)
        if len(value) > 160:
            value = value[:157] + "..."
        lines.append(f"| {name} | {'통과' if item['pass'] else '실패'} | {value} | {item['reason']} |")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inbox", required=True)
    parser.add_argument("--asof", default=dt.datetime.now(KST).date().isoformat())
    parser.add_argument("--json")
    args = parser.parse_args(argv)
    with open(os.path.join(HERE, "scorecard.json"), encoding="utf-8") as handle:
        card = json.load(handle)
    report = score(args.inbox, dt.date.fromisoformat(args.asof), card)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=1)
    print(table(report))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
