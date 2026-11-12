#!/usr/bin/env python3
"""Check of the morning report file report/YYYY-MM-DD.json (DESIGN_V5 1절, 2.2절) before the tidy routine commits it.

The routine (docs/routines/tidy.md 9-1) writes the report; the phone sends it as it is to KakaoTalk at 05:30. This check
fails (exit code 1) on: a broken format or field, too many lines, a line over 60 characters, a banned word or sign
(middle dot, ellipsis, an English status value, "기한", "지남", a due expression ending in "까지", the "~해요" style) and
anything that looks like a Korean phone number. It prints the reasons only (field path and rule), never the text itself,
so the routine's log carries no names or numbers. Standard library only (the inbox repository has nothing else).

Usage: python3 tools/report_check.py report/2026-11-07.json [more files]
"""
import json, os, re, sys

LINE_MAX = 60
LIMITS = {"yesterday": 8, "today": 10, "inProgress": 3, "check": 3, "late": 5}
HEAD_KEYS = ("calls", "newChecks", "todayChecks", "issues")
TOP_KEYS = ("day", "generatedMs", "tidyRev", "head", "yesterday", "today", "inProgress", "check")
# v3.7 (DESIGN_V7 3.2 M3, M4; tidy.md v8): head.pendingCalls and late[]. Optional, so a v7 report (before the routine moves to
# v8 on the release day) still passes; when present they are checked like the rest.
HEAD_OPTIONAL = ("pendingCalls",)
TOP_OPTIONAL = ("late",)
# r18 (tidy.md v9, user 11/13): today[].tag, one of five in this order (the KakaoTalk list's thumbnails). Optional, so a v8
# report (before the routine moves to v9 on the release day) still passes; the phone reads a missing tag as confirm or request.
TAGS = ("important", "confirm", "request", "regular", "optional")
MAX_IMPORTANT = 3
ITEM_OPTIONAL = {"today": {"tag": str}}
ITEM_KEYS = {
    "yesterday": {"who": str, "what": str},
    "today": {"id": str, "what": str, "who": str, "since": str, "status": str, "check": bool},
    "inProgress": {"what": str},
    "check": {"what": str, "who": str},
    "late": {"who": str, "what": str},
}
# Fields that must not be empty (the rest may be "").
REQUIRED_TEXT = {"yesterday": ("who", "what"), "today": ("id", "what", "since"), "inProgress": ("what",), "check": ("what",), "late": ("who", "what")}

DAY_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$")
SINCE_RE = re.compile(r"^(오늘|어제|그제|\d{1,3}일 전|\d{1,2}주 전|\d{1,2}/\d{1,2}) 요청$")
SIGNS = ("·", "･", "・", "…", "...")
BANNED = ("기한", "지남", "지났")
# A due expression: a field, or a clause of it, that ends in "까지" ("금요일까지", "견적서 11/7까지,"); "금요일까지 보내기로" is a
# promise said in a call and stays.
DUE_RE = re.compile(r"까지\s*([,)]|$)")
# The "~해요" style of the screens; a report line ends like a report ("보내기로", "입회 요청", "확인 필요").
HAEYO_RE = re.compile(r"(해요|어요|아요|에요|예요|세요|네요|게요|래요|대요|죠)[.!]?$")
# The screen rule's list (tools/check_ui_rules.py) in capitals, plus the check items' own status values as the state file
# carries them (lower case). Case-sensitive, so an English company name ("Ready Mix") is no status value.
STATE_WORDS = ("QUEUED", "FAILED", "FAILURE", "READY", "PENDING", "RUNNING", "SKIPPED", "TRANSCRIBED", "DOWNLOADING", "NOT_DOWNLOADED",
               "UNSUPPORTED", "ARCHIVED", "ANALYZED", "ERROR", "TIMEOUT", "STOPPED", "MORE", "FIRM", "TENTATIVE",
               "OPEN", "DONE", "WAITING", "MERGED", "NEEDS_CONFIRMATION", "TODO", "DONE_SUGGESTED")
TASK_STATES = ("open", "done", "waiting", "merged", "needs_confirmation", "done_suggested", "tentative", "firm", "todo")
STATE_RE = re.compile(r"(?<![A-Za-z_])(" + "|".join(STATE_WORDS + TASK_STATES) + r")(?![A-Za-z_])")
# r15-4 (L10): up to three separator signs ("010 - 1234 - 5678", "(031) 123-4567").
SEP = r"[-.\s)]{0,3}"
PHONE_RES = (
    # Mobile, Seoul, area codes and 070, with or without separators; +82 drops the leading 0.
    re.compile(r"(?<![\d])(?:\+?82[-.\s]?\(?0?|\(?0)(?:1[016789]|2|[3-6][1-5]|70|50\d)" + SEP + r"\d{3,4}" + SEP + r"\d{4}(?!\d)"),
    # Nationwide numbers (1588-1234, 1600 1234).
    re.compile(r"(?<![\d])1[5-9]\d{2}[-.\s]\d{4}(?!\d)"),
)


# r15-4 (L10): a mail address is personal too (name@company.co.kr).
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")


def has_phone(text):
    return any(p.search(text) for p in PHONE_RES)


def has_email(text):
    return EMAIL_RE.search(text) is not None


def text_problems(path, value, style=True):
    """Rule breaks inside one string value, as reasons without the value. style: check the "~해요" ending (not for names)."""
    out = []
    if any(s in value for s in SIGNS):
        out.append(f"{path}: 가운데 점이나 말줄임표")
    for w in BANNED:
        if w in value:
            out.append(f"{path}: 금지어 '{w}'")
    if DUE_RE.search(value):
        out.append(f"{path}: '까지'로 끝나는 기한 표현")
    m = STATE_RE.search(value)
    if m:
        out.append(f"{path}: 영어 상태값 {m.group(1).upper()}")
    if style and HAEYO_RE.search(value.strip()):
        out.append(f"{path}: 해요체 대신 보고체(명사형 종결)")
    if has_phone(value):
        out.append(f"{path}: 전화번호로 보이는 숫자")
    if has_email(value):
        out.append(f"{path}: 메일 주소")
    return out


def line_of(section, item):
    """The one line the phone shows for an item (tidy.md 9-1: the 60-character rule)."""
    if section in ("yesterday", "late"):
        return f"{item.get('who', '')}: {item.get('what', '')}"
    if section == "today":
        extra = [x for x in (item.get("who"), item.get("since"), item.get("status")) if isinstance(x, str) and x]
        return item.get("what", "") + (f" ({', '.join(extra)})" if extra else "")
    if section == "check":
        who = item.get("who")
        return item.get("what", "") + (f" ({who})" if isinstance(who, str) and who else "")
    return item.get("what", "")


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def check(report, file_day=None):
    """Reasons the report breaks the format or the rules; [] when it passes. file_day: the date in the file name, if any."""
    if not isinstance(report, dict):
        return ["맨 위가 JSON 객체가 아님"]
    out = []
    for k in TOP_KEYS:
        if k not in report:
            out.append(f"{k}: 빠짐")
    for k in report:
        if k not in TOP_KEYS and k not in TOP_OPTIONAL:
            out.append(f"{k}: 모르는 키")
    day = report.get("day")
    if "day" in report:
        if not isinstance(day, str) or not DAY_RE.match(day):
            out.append("day: YYYY-MM-DD 형식이 아님")
        elif file_day and day != file_day:
            out.append("day: 파일 이름의 날짜와 다름")
    for k in ("generatedMs", "tidyRev"):
        if k in report and (not is_int(report[k]) or report[k] < 0):
            out.append(f"{k}: 0 이상의 정수가 아님")
    head = report.get("head")
    if "head" in report:
        if not isinstance(head, dict):
            out.append("head: 객체가 아님")
        else:
            for k in HEAD_KEYS:
                if not is_int(head.get(k)) or head[k] < 0:
                    out.append(f"head.{k}: 0 이상의 정수가 아님")
            for k in HEAD_OPTIONAL:
                if k in head and (not is_int(head[k]) or head[k] < 0):
                    out.append(f"head.{k}: 0 이상의 정수가 아님")
            for k in head:
                if k not in HEAD_KEYS and k not in HEAD_OPTIONAL:
                    out.append(f"head.{k}: 모르는 키")
    for section, limit in LIMITS.items():
        items = report.get(section)
        if section not in report:
            continue
        if not isinstance(items, list):
            out.append(f"{section}: 배열이 아님")
            continue
        if len(items) > limit:
            out.append(f"{section}: {len(items)}줄 (최대 {limit}줄)")
        for i, item in enumerate(items):
            p = f"{section}[{i}]"
            if not isinstance(item, dict):
                out.append(f"{p}: 객체가 아님")
                continue
            shape = ITEM_KEYS[section]
            ok = True
            for k, t in shape.items():
                if k not in item:
                    out.append(f"{p}.{k}: 빠짐")
                    ok = False
                elif not isinstance(item[k], t):
                    out.append(f"{p}.{k}: {'참/거짓' if t is bool else '문자열'}이 아님")
                    ok = False
            optional = ITEM_OPTIONAL.get(section, {})
            for k in item:
                if k not in shape and k not in optional:
                    out.append(f"{p}.{k}: 모르는 키")
            if section == "today" and "tag" in item:
                if item["tag"] not in TAGS:
                    out.append(f"{p}.tag: 분류 부호가 아님({', '.join(TAGS)})")
                    ok = False
            for k in REQUIRED_TEXT[section]:
                if isinstance(item.get(k), str) and not item[k].strip():
                    out.append(f"{p}.{k}: 비어 있음")
            for k, v in item.items():
                if isinstance(v, str) and k not in ("id", "tag"):
                    out += text_problems(f"{p}.{k}", v, style=k != "who")
            if section == "today" and isinstance(item.get("id"), str) and has_phone(item["id"]):
                out.append(f"{p}.id: 전화번호로 보이는 숫자")
            if section == "today" and isinstance(item.get("since"), str) and item["since"].strip() and not SINCE_RE.match(item["since"]):
                out.append(f"{p}.since: 요청 날짜 표현이 아님('오늘 요청', '어제 요청', '3일 전 요청')")
            if ok:
                n = len(line_of(section, item))
                if n > LINE_MAX:
                    out.append(f"{p}: 한 줄 {n}자 (최대 {LINE_MAX}자)")
    today = report.get("today")
    if isinstance(today, list):
        ranks = [TAGS.index(t["tag"]) for t in today if isinstance(t, dict) and t.get("tag") in TAGS]
        if any(a > b for a, b in zip(ranks, ranks[1:])):
            out.append("today: tag 순서가 아님(" + ", ".join(TAGS) + ")")
        # 11/13: the first v9 report tagged 8 of 10 lines important; when everything is, nothing is.
        if sum(1 for t in today if isinstance(t, dict) and t.get("tag") == "important") > MAX_IMPORTANT:
            out.append(f"today: important가 {MAX_IMPORTANT}줄을 넘음(기한이 급한 것, 금액이 큰 것 순으로 {MAX_IMPORTANT}개만, 나머지는 request)")
    if isinstance(head, dict) and isinstance(report.get("today"), list) and is_int(head.get("todayChecks")) \
            and head["todayChecks"] != len(report["today"]):
        out.append("head.todayChecks: today 줄 수와 다름")
    return out


def check_file(path):
    name = os.path.basename(path)
    file_day = name[:-5] if name.endswith(".json") and DAY_RE.match(name[:-5]) else None
    try:
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
    except OSError:
        return ["파일을 읽지 못함"]
    except ValueError:
        return ["JSON 형식이 깨짐"]
    return check(report, file_day)


def main(argv=None):
    paths = sys.argv[1:] if argv is None else argv
    if not paths:
        print("사용법: python3 tools/report_check.py report/YYYY-MM-DD.json")
        return 2
    failed = False
    for path in paths:
        reasons = check_file(path)
        name = os.path.basename(path)
        if reasons:
            failed = True
            print(f"report_check: {name} 실패 {len(reasons)}건")
            for r in reasons:
                print("- " + r)
        else:
            print(f"report_check: {name} 통과")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
