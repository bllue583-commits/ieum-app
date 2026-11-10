#!/usr/bin/env python3
"""이음 자막 대기열 시뮬레이션: 실제 통화 도착 기록으로 정책별 결과를 미리 계산한다.

    python3 tools/sim/queue_sim.py --calls calls.json [--speech 0.7] [--json out.json]

calls.json: [{"dt": "2026-10-01T09:12:00", "dur": 77, "status": "없음"|"개인"...}] — 시각·길이·상태만(이름·번호 없음).
1분 단위로 흘린다. 한 번에 녹음 하나만 글자로 바꾼다(폰과 같음).
- rate: 녹음 1분을 바꾸는 데 드는 실행 분(지금 3.5).
- util: 꽂힌 시간 중 실제로 도는 비율(달궈짐 건너뜀·재시작 손실 반영. v3.6 실측 0.43, 오탐 제거 뒤 0.9 가정).
- order: fifo(24시간 넘은 것 오래된 순 먼저 — 지금 코드), recent(어제·오늘 통화 최신순 먼저).
- battery: 하루 배터리 실행 예산(분). 0이면 충전 중에만. battery_max_sec: 배터리로 바꾸는 통화 길이 상한.
- slots: 분석 루틴이 도는 시(매시 34분). 한 회차 cap건. 결과는 회차 시작 +12분에 폰에 적용된다고 본다.
- cutoff: 아침 보고에 들어가려면 결과가 적용돼 있어야 하는 시각(보고일 기준 분, 음수는 전날).
충전 시각은 관측 3밤(23:48, 00:40, 03:30 시작 ~ 07:45)과 낮 두 번(12:50~13:50, 18:50~19:50)을 날짜마다 돌려 쓴다.
"""
import argparse, datetime as dt, json, statistics, sys

NIGHT_STARTS = [23 * 60 + 48 - 1440, 40, 210]  # 자정 기준 분
NIGHT_END = 7 * 60 + 45
DAY_PLUGS = [(12 * 60 + 50, 13 * 60 + 50), (18 * 60 + 50, 19 * 60 + 50)]


def plugged(day_index, minute, night_start):
    start = NIGHT_STARTS[day_index % 3] if night_start is None else night_start
    if minute < NIGHT_END and minute >= max(start, 0):
        return True
    if start < 0 and minute >= 1440 + start:  # 전날 밤에 꽂음(다음 날 것)
        return False
    return any(a <= minute < b for a, b in DAY_PLUGS)


def plugged_abs(t, day0, night_start):
    day_index, minute = divmod(t, 1440)
    start_today = NIGHT_STARTS[day_index % 3] if night_start is None else night_start
    start_next = NIGHT_STARTS[(day_index + 1) % 3] if night_start is None else night_start
    if minute < NIGHT_END and minute >= max(start_today, 0):
        return True
    if start_today < 0 and minute < NIGHT_END:
        return True
    if start_next < 0 and minute >= 1440 + start_next:
        return True
    return any(a <= minute < b for a, b in DAY_PLUGS)


def simulate(calls, p):
    day0 = min(c["end"] for c in calls) // 1440
    horizon = (max(c["end"] for c in calls) // 1440 + 3) * 1440
    pending = sorted(calls, key=lambda c: c["end"])
    queue, done, i = [], [], 0
    budget_left, budget_day, battery_used = p["battery"], None, {}
    for t in range(day0 * 1440, horizon):
        while i < len(pending) and pending[i]["end"] <= t:
            c = pending[i]; queue.append({"end": c["end"], "left": c["audio"], "audio": c["audio"], "sec": c["sec"]}); i += 1
        day = t // 1440
        if day != budget_day:
            budget_day, budget_left = day, p["battery"]
        if not queue:
            continue
        on_power = plugged_abs(t - day0 * 1440, day0, p.get("night_start"))
        minute = t % 1440
        if on_power:
            share, pool = p["util"], queue
        elif budget_left >= 1 and 7 * 60 <= minute < 23 * 60:
            pool = [q for q in queue if q["end"] // 1440 == day and q["sec"] <= p["battery_max_sec"]]
            share = 1.0
            if not pool:
                continue
        else:
            continue
        if p["order"] == "recent":
            fresh = [q for q in pool if q["end"] // 1440 >= (t + 1140) // 1440 - 1]  # 다음 보고일 - 1일 이후
            job = max(fresh, key=lambda q: q["end"]) if fresh else min(pool, key=lambda q: q["end"])
        else:
            old = [q for q in pool if t - q["end"] > 1440]
            job = min(old, key=lambda q: q["end"]) if old else max(pool, key=lambda q: q["end"])
        job["left"] -= share / (p["rate"] if on_power else p.get("battery_rate", p["rate"]))
        if not on_power:
            budget_left -= 1; battery_used[day] = battery_used.get(day, 0) + 1
        if job["left"] <= 0:
            queue.remove(job); job["text"] = t; done.append(job)
    # 분석 회차
    slots, used = sorted(p["slots"]), {}
    for job in sorted(done, key=lambda j: j["text"]):
        t = job["text"] + 1
        while True:
            day, minute = divmod(t, 1440)
            nxt = next((h * 60 + 34 for h in slots if h * 60 + 34 >= minute), None)
            slot = day * 1440 + nxt if nxt is not None else (day + 1) * 1440 + slots[0] * 60 + 34
            cap = p["cap_night"] if (slot % 1440) < 7 * 60 else p["cap"]
            if used.get(slot, 0) < cap:
                used[slot] = used.get(slot, 0) + 1; job["applied"] = slot + 12; break
            t = slot + 1
    # 지표(평일 통화만)
    lat, cover, evening = [], [], []
    by_day = {}
    for job in done:
        by_day.setdefault(job["end"] // 1440, []).append(job)
    for c in queue:
        by_day.setdefault(c["end"] // 1440, []).append(c)
    for day, jobs in sorted(by_day.items()):
        if dt.date.fromordinal(day + 719163).weekday() >= 5 or len(jobs) < 5:
            continue
        cutoff = (day + 1) * 1440 + p["cutoff"]
        cover.append(sum(1 for j in jobs if j.get("applied", 10 ** 12) <= cutoff) / len(jobs))
        early = [j for j in jobs if j["end"] % 1440 < 18 * 60]
        if early:
            evening.append(sum(1 for j in early if j.get("applied", 10 ** 12) <= day * 1440 + 19 * 60) / len(early))
        lat += [j["applied"] - j["end"] for j in jobs if "applied" in j]
    lat.sort()
    used_days = [v for v in battery_used.values()]
    return {
        "coverage_mean": round(statistics.mean(cover), 3), "coverage_days_ge95": round(sum(c >= .95 for c in cover) / len(cover), 2),
        "coverage_min": round(min(cover), 2), "evening_mean": round(statistics.mean(evening), 2),
        "lat_p50_h": round(lat[len(lat) // 2] / 60, 1), "lat_p90_h": round(lat[int(len(lat) * .9)] / 60, 1),
        "unfinished": len(queue), "battery_min_p90": sorted(used_days)[int(len(used_days) * .9)] if used_days else 0,
        "days": len(cover),
    }


V36 = dict(rate=3.5, util=0.43, order="fifo", battery=0, battery_max_sec=180, slots=range(7, 23), cap=20, cap_night=20, cutoff=-165)
POLICIES = {
    "v3.6 지금(충전 중에만, 오탐, 선입선출, 07~22시 분석)": V36,
    "+T1 오탐 제거": {**V36, "util": 0.9},
    "+T1+T2 어제·오늘 먼저": {**V36, "util": 0.9, "order": "recent"},
    "v3.7 = +T1+T2+밤 분석 03:34·04:34, 마감 05:00": {**V36, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300},
    "v3.7 + 포그라운드(조각 70%)": {**V36, "rate": 2.45, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300},
    "v3.7 + 배터리 20분(3분 이하 통화)": {**V36, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300, "battery": 20},
    "v3.7 + 밤 분석 매시(00~04)": {**V36, "util": 0.9, "order": "recent", "slots": list(range(0, 5)) + list(range(7, 23)), "cap_night": 40, "cutoff": 300},
    "엔진 1.0배 + 배터리 30분(전부)": {**V36, "rate": 1.0, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300, "battery": 30, "battery_max_sec": 10 ** 6},
    "엔진 0.5배 + 배터리 30분(전부)": {**V36, "rate": 0.5, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300, "battery": 30, "battery_max_sec": 10 ** 6},
    "엔진 0.25배 + 배터리 25분(전부)": {**V36, "rate": 0.25, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300, "battery": 25, "battery_max_sec": 10 ** 6},
    "엔진 0.25배 + 충전 중에만": {**V36, "rate": 0.25, "util": 0.9, "order": "recent", "slots": [3, 4] + list(range(7, 23)), "cap_night": 40, "cutoff": 300},
}


def load(path, speech):
    out = []
    for c in json.load(open(path, encoding="utf-8")):
        if c.get("status") == "개인" or not c.get("dur") or c["dur"] < 20:
            continue
        start = dt.datetime.fromisoformat(c["dt"])
        end = (start.toordinal() - 719163) * 1440 + start.hour * 60 + start.minute + c["dur"] // 60 + 1
        out.append({"end": end, "audio": c["dur"] / 60 * speech, "sec": c["dur"]})
    return out


def main(argv):
    ap = argparse.ArgumentParser(); ap.add_argument("--calls", required=True); ap.add_argument("--speech", type=float, default=0.7)
    ap.add_argument("--night-start", type=int, default=None, help="밤 충전 시작(자정 기준 분, 예: 210 = 03:30)"); ap.add_argument("--json")
    a = ap.parse_args(argv)
    calls = load(a.calls, a.speech)
    rows = {}
    print(f"통화 {len(calls)}건, 말소리 계수 {a.speech}, 밤 충전 시작 {'관측 3밤 순환' if a.night_start is None else a.night_start}")
    print("| 정책 | 아침 보고 포함률 평균 | 95% 넘는 날 | 최저 | 저녁 7시 포함률 | 지연 p50(시간) | p90 | 배터리 분 p90 | 못 끝낸 통화 |\n|---|---|---|---|---|---|---|---|---|")
    for name, p in POLICIES.items():
        r = simulate([dict(c) for c in calls], {**p, "night_start": a.night_start}); rows[name] = r
        print(f"| {name} | {r['coverage_mean']:.0%} | {r['coverage_days_ge95']:.0%} | {r['coverage_min']:.0%} | {r['evening_mean']:.0%} | {r['lat_p50_h']} | {r['lat_p90_h']} | {r['battery_min_p90']} | {r['unfinished']} |")
    if a.json:
        json.dump(rows, open(a.json, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(sys.argv[1:])
