import json, os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import report_check as rc


def good():
    """DESIGN_V5 1절's example report (ids filled in)."""
    return {"day": "2026-11-07", "generatedMs": 1793996000000, "tidyRev": 12,
            "head": {"calls": 7, "newChecks": 3, "todayChecks": 2, "issues": 2},
            "yesterday": [{"who": "△△철강 김민수 대표", "what": "철근 단가 재협의, 견적서 금요일까지 보내기로"},
                          {"who": "○○전기", "what": "배선 자재 내일 입고, 검수 입회 요청"}],
            "today": [{"id": "t_a1b2c3d4", "what": "견적서 보내기", "who": "△△철강", "since": "어제 요청", "status": "", "check": False},
                      {"id": "t_e5f6a7b8", "what": "안전 점검 서류", "who": "본사 박과장", "since": "3일 전 요청",
                       "status": "메일 발송 확인 완료", "check": False}],
            "inProgress": [{"what": "철근 단가 재견적: 업체 회신 대기, 금요일 계약 예정"}],
            "check": [{"what": "13층/30층 중 어느 쪽인지", "who": "△△철강"}]}


def board():
    """good() with the v12 sections (tidy.md 9-1, r25-1); every name and number made up."""
    r = good()
    r["inProgress"] = [{"what": "철근 단가 재견적", "status": "업체 회신 대기, 금요일 계약 예정", "days": 12},
                       {"what": "3층 슬래브 타설", "days": 3, "unsure": True}, {"what": "옛 형식 한 줄"}]
    r["calls"] = [{"who": "박하늘 과장", "company": "가나건설", "what": "거푸집 반입 시간 다시 묻기", "since": "11/5"},
                  {"who": "이도윤", "what": "견적 회신 독촉", "since": "어제", "unsure": True}]
    r["waiting"] = [{"who": "박하늘 과장", "company": "가나건설", "what": "수정 도면", "since": "10/20", "days": 18},
                    {"who": "최서준 대리", "company": "다라전기", "what": "배선 자재 견적서", "since": "11/4", "days": 3}]
    r["week"] = [{"date": "2026-11-07", "items": ["3층 슬래브 타설", "철근 반입 오전"]}, {"date": "2026-11-10", "items": ["소방 검사"]}]
    r["doneCheck"] = [{"what": "안전 교육 일지 제출", "who": "본사"}]
    r["changed"] = ["철근 반입 11/9로 바뀜", "새 일 2건"]
    return r


class ReportCheckTest(unittest.TestCase):
    def assertBreaks(self, report, *fragments):
        got = rc.check(report)
        self.assertTrue(got, "expected a failure")
        for f in fragments:
            self.assertTrue(any(f in r for r in got), (f, got))

    def test_design_example_passes(self):
        self.assertEqual([], rc.check(good()))

    def test_empty_yesterday_passes(self):
        r = good()
        r["yesterday"] = []
        r["head"]["calls"] = 0
        self.assertEqual([], rc.check(r))
        r.update(today=[], inProgress=[], check=[])
        r["head"]["todayChecks"] = 0
        self.assertEqual([], rc.check(r))

    def test_too_many_lines(self):
        r = good()
        r["yesterday"] = [{"who": f"업체{i}", "what": "자재 입고"} for i in range(9)]
        self.assertBreaks(r, "yesterday: 9줄 (최대 8줄)")
        r = good()
        r["inProgress"] = [{"what": "공정 진행"}] * 21
        self.assertBreaks(r, "inProgress: 21줄 (최대 20줄)")

    def test_v12_board_passes_and_v11_still_passes(self):
        self.assertEqual([], rc.check(board()))
        self.assertEqual([], rc.check(good()), "every v12 key is optional")
        r = board()
        r.update(calls=[], waiting=[], week=[], doneCheck=[], changed=[])
        self.assertEqual([], rc.check(r))

    def test_v12_limits(self):
        r = board()
        r["calls"] = [{"who": "박하늘", "what": "전화", "since": "어제"}] * 11
        self.assertBreaks(r, "calls: 11줄 (최대 10줄)")
        r = board()
        r["waiting"] = [{"who": "박하늘", "what": "도면", "since": "어제", "days": 1}] * 21
        self.assertBreaks(r, "waiting: 21줄 (최대 20줄)")
        r = board()
        r["changed"] = ["바뀐 것"] * 7
        self.assertBreaks(r, "changed: 7줄 (최대 6줄)")
        r = board()
        r["week"][0]["items"] = ["일정"] * 7
        self.assertBreaks(r, "week[0].items: 7줄 (최대 6줄)")
        r = board()
        r["week"] = [{"date": f"2026-11-{d:02d}", "items": ["일정"]} for d in range(7, 15)]
        self.assertBreaks(r, "week: 8일 (최대 7일)", "week[7].date: 보고 날짜부터 7일 안이 아님")

    def test_v12_shapes(self):
        r = board()
        r["waiting"][0]["days"] = "18일째"
        self.assertBreaks(r, "waiting[0].days: 정수가 아님")
        r = board()
        del r["waiting"][1]["days"]
        self.assertBreaks(r, "waiting[1].days: 빠짐")
        r = board()
        r["inProgress"][0]["days"] = True
        self.assertBreaks(r, "inProgress[0].days: 정수가 아님")
        r = board()
        r["inProgress"][0]["days"] = -1
        self.assertBreaks(r, "inProgress[0].days: 0 이상의 정수가 아님")
        r = board()
        r["calls"][0]["unsure"] = "yes"
        self.assertBreaks(r, "calls[0].unsure: 참/거짓이 아님")
        r = board()
        r["calls"][0]["since"] = "지난주"
        self.assertBreaks(r, "calls[0].since: 날짜 표현이 아님")
        r = board()
        r["calls"][0]["phone"] = "x"
        self.assertBreaks(r, "calls[0].phone: 모르는 키")
        r = board()
        r["week"] = {"2026-11-07": ["타설"]}
        self.assertBreaks(r, "week: 배열이 아님")
        r = board()
        r["week"][0]["date"] = "11/7"
        self.assertBreaks(r, "week[0].date: YYYY-MM-DD 형식이 아님")
        r = board()
        r["week"][1]["date"] = "2026-11-07"
        self.assertBreaks(r, "week[1].date: 날짜순이 아님")
        r = board()
        r["week"][1]["items"] = []
        self.assertBreaks(r, "week[1].items: 비어 있음")
        r = board()
        r["week"][0]["date"] = "2026-11-06"
        self.assertBreaks(r, "week[0].date: 보고 날짜부터 7일 안이 아님")
        r = board()
        r["changed"] = [{"what": "바뀜"}]
        self.assertBreaks(r, "changed[0]: 문자열이 아님")

    def test_review_since_days_and_week_words(self):
        # r25 review: an unknown date stays as "" (the line is not dropped), the days follow the dates, a promise ahead is no wait.
        r = board()
        r["calls"][0]["since"] = ""
        r["waiting"][1]["since"] = ""
        self.assertEqual([], rc.check(r))
        r = board()
        r["waiting"][1]["days"] = 4
        self.assertBreaks(r, "waiting[1].days: 보고 날짜에서 약속한 날짜를 뺀 날 수(3)와 다름")
        r = board()
        r["waiting"][1].update(since="11/9", days=0)
        self.assertBreaks(r, "waiting[1].since: 약속한 날이 아직 오지 않음")
        r = board()
        r["waiting"][1].update(since="오늘", days=0)
        self.assertEqual([], rc.check(r))
        r = board()
        r["waiting"] = [{"who": "박하늘", "what": "견적", "since": "12/20", "days": 322}]
        self.assertEqual([], rc.check(r), "a later month/day far ahead is last year's")
        r = board()
        r["week"][0]["items"][0] = "구조 검토서 제출 기한"
        self.assertEqual([], rc.check(r))
        r["changed"][0] = "제출 기한 바뀜"
        self.assertBreaks(r, "changed[0]: 금지어 '기한'")

    def test_v12_waiting_oldest_first(self):
        r = board()
        r["waiting"].reverse()
        self.assertBreaks(r, "waiting: 오래된 것부터가 아님")

    def test_v12_doubt_is_a_flag_not_a_tail(self):
        for section, key in (("calls", "what"), ("waiting", "what"), ("inProgress", "status")):
            r = board()
            r[section][0][key] = "철근 13톤 (숫자 확인 필요)"
            self.assertBreaks(r, f"{section}[0].{key}: 확인 필요 꼬리 대신 unsure:true")
        r = board()
        r["calls"][0]["what"] = "견적 금액 (확인 필요)"
        self.assertBreaks(r, "calls[0].what: 확인 필요 꼬리")

    def test_v12_text_rules_and_lines(self):
        r = board()
        r["changed"][0] = "도면 회신 010-1234-5678"
        self.assertBreaks(r, "changed[0]: 전화번호로 보이는 숫자")
        r = board()
        r["week"][0]["items"][0] = "타설 · 반입"
        self.assertBreaks(r, "week[0].items[0]: 가운데 점이나 말줄임표")
        r = board()
        r["waiting"][0]["what"] = "도면 보내 준대요"
        self.assertBreaks(r, "waiting[0].what: 해요체")
        r = board()
        r["calls"][0]["what"] = "가" * (60 - len("가나건설 박하늘 과장: ") - len(" (11/5)") + 1)
        self.assertBreaks(r, "calls[0]: 한 줄 61자")
        r["calls"][0]["what"] = r["calls"][0]["what"][1:]
        self.assertEqual([], rc.check(r))
        r = board()
        r["inProgress"][0]["status"] = "가" * 60
        self.assertBreaks(r, "inProgress[0]: 한 줄")
        r = board()
        r["changed"][0] = "가" * 61
        self.assertBreaks(r, "changed[0]: 한 줄 61자")
        # Names are not held to the report style ("김하늘 대리님이요" is not a sentence ending).
        r = board()
        r["calls"][0]["company"] = "가나건설이에요"
        self.assertEqual([], rc.check(r))

    def test_line_over_sixty(self):
        r = good()
        r["yesterday"][0]["what"] = "가" * 60
        self.assertBreaks(r, "yesterday[0]: 한 줄")
        # The today line counts who, since and status with the title.
        r = good()
        r["today"][1]["status"] = "메일 발송 확인 완료, 첨부 서류 두 건 회신 받음, 원본 우편 발송 예정"
        self.assertBreaks(r, "today[1]: 한 줄")
        r = good()
        r["yesterday"][0]["what"] = "가" * (60 - len("△△철강 김민수 대표: "))
        self.assertEqual([], rc.check(r))

    def test_phone_numbers(self):
        for number in ("010-1234-5678", "01012345678", "010 1234 5678", "02-123-4567", "031)123-4567", "+82 10-1234-5678",
                       "070-1234-5678", "1588-1234"):
            r = good()
            r["yesterday"][1]["what"] = f"배선 자재 입고, {number}로 연락 요청"
            self.assertBreaks(r, "yesterday[1].what: 전화번호")
            r = good()
            r["check"][0]["who"] = f"김 소장 {number}"
            self.assertBreaks(r, "check[0].who: 전화번호")
        # Amounts, quantities, dates and floors are no phone numbers.
        r = good()
        r["yesterday"][1]["what"] = "철근 120톤 1,250만원, 11/12 반입, 2026-11-20 타설, 13층"
        self.assertEqual([], rc.check(r))

    def test_wider_separators_and_mail_addresses(self):
        """r15-4 (L10): three separator signs between the parts, and mail addresses."""
        for number in ("010 - 1234 - 5678", "(031) 123-4567", "02 .123. 4567"):
            r = good()
            r["yesterday"][1]["what"] = f"배선 자재 입고, {number}로 연락 요청"
            self.assertBreaks(r, "yesterday[1].what: 전화번호")
        for mail in ("kim.ms@steel.co.kr", "a_b+1@x-y.com"):
            r = good()
            r["today"][0]["what"] = f"견적서 {mail}로 보내기"
            self.assertBreaks(r, "today[0].what: 메일 주소")
            self.assertFalse(any(mail in x for x in rc.check(r)))
        r = good()
        r["yesterday"][1]["what"] = "철근 120톤 1,250만원, 11/12 반입, 2026-11-20 타설, 13층 @현장"
        self.assertEqual([], rc.check(r))

    def test_reasons_never_carry_the_text(self):
        r = good()
        r["yesterday"][1]["what"] = "010-1234-5678로 연락 요청"
        self.assertFalse(any("1234" in x for x in rc.check(r)))

    def test_english_status_value(self):
        for word in ("FAILED", "needs_confirmation", "done", "PENDING"):
            r = good()
            r["today"][0]["status"] = f"{word}"
            self.assertBreaks(r, "today[0].status: 영어 상태값")
        # An English company name is no status value.
        r = good()
        r["yesterday"][1]["who"] = "Ready Mix 레미콘"
        self.assertEqual([], rc.check(r))

    def test_banned_words_and_signs(self):
        cases = [("what", "견적서 보내기, 기한 오늘", "금지어 '기한'"), ("status", "회신 기한 지남", "금지어 '지남'"),
                 ("status", "3일 지났어요", "금지어 '지났'"), ("what", "서류 정리 · 제출", "가운데 점"),
                 ("what", "서류 정리…", "가운데 점이나 말줄임표"), ("status", "금요일까지", "'까지'로 끝나는"),
                 ("status", "메일 보냈어요", "해요체")]
        for key, value, reason in cases:
            r = good()
            r["today"][0][key] = value
            self.assertBreaks(r, f"today[0].{key}: {reason}")

    def test_format_and_fields(self):
        r = good()
        del r["head"]
        self.assertBreaks(r, "head: 빠짐")
        r = good()
        r["day"] = "2026-11-7"
        self.assertBreaks(r, "day: YYYY-MM-DD")
        r = good()
        r["today"][0]["check"] = "false"
        self.assertBreaks(r, "today[0].check: 참/거짓")
        r = good()
        r["today"][0]["since"] = "11월 4일"
        self.assertBreaks(r, "today[0].since: 요청 날짜 표현")
        r = good()
        r["head"]["todayChecks"] = 5
        self.assertBreaks(r, "head.todayChecks: today 줄 수와 다름")
        r = good()
        r["yesterday"][0]["extra"] = "x"
        self.assertBreaks(r, "yesterday[0].extra: 모르는 키")
        r = good()
        r["generatedMs"] = True
        self.assertBreaks(r, "generatedMs")
        self.assertEqual(["맨 위가 JSON 객체가 아님"], rc.check([]))

    def test_file_and_exit_code(self):
        with tempfile.TemporaryDirectory() as d:
            ok = os.path.join(d, "2026-11-07.json")
            with open(ok, "w", encoding="utf-8") as f:
                json.dump(good(), f, ensure_ascii=False)
            self.assertEqual(0, rc.main([ok]))
            other_day = os.path.join(d, "2026-11-08.json")
            with open(other_day, "w", encoding="utf-8") as f:
                json.dump(good(), f, ensure_ascii=False)
            self.assertEqual(["day: 파일 이름의 날짜와 다름"], rc.check_file(other_day))
            self.assertEqual(1, rc.main([other_day]))
            broken = os.path.join(d, "2026-11-09.json")
            with open(broken, "w", encoding="utf-8") as f:
                f.write("{")
            self.assertEqual(["JSON 형식이 깨짐"], rc.check_file(broken))
            self.assertEqual(1, rc.main([ok, broken]))
            self.assertEqual(2, rc.main([]))

    def test_v8_pending_and_late(self):
        """tidy.md v8 (DESIGN_V7 3.2 M3, M4): head.pendingCalls and late[] pass, are optional and are checked like the rest."""
        r = good()
        r["head"]["pendingCalls"] = 9
        r["late"] = [{"who": "○○전기 이과장", "what": "분전반 교체 일정 확정, 다음 주 화요일 시공"}]
        self.assertEqual([], rc.check(r))
        r["late"] = []
        self.assertEqual([], rc.check(r))
        self.assertEqual([], rc.check(good()), "a v7 report without them still passes")
        bad = good()
        bad["head"]["pendingCalls"] = -1
        self.assertBreaks(bad, "head.pendingCalls: 0 이상의 정수가 아님")
        bad["head"]["pendingCalls"] = "9"
        self.assertBreaks(bad, "head.pendingCalls: 0 이상의 정수가 아님")
        r["late"] = [{"who": f"업체{i}", "what": "도면 회신"} for i in range(6)]
        self.assertBreaks(r, "late: 6줄 (최대 5줄)")
        r["late"] = [{"who": "○○전기", "what": "회신 받아요"}]
        self.assertBreaks(r, "late[0].what: 해요체")
        r["late"] = [{"who": "○○전기", "what": "가" * 60}]
        self.assertBreaks(r, "late[0]: 한 줄")
        r["late"] = [{"who": "○○전기", "what": "010-1234-5678로 회신"}]
        self.assertBreaks(r, "late[0].what: 전화번호")
        r["late"] = [{"who": "", "what": "회신"}]
        self.assertBreaks(r, "late[0].who: 비어 있음")
        r["late"] = [{"who": "○○전기", "what": "회신", "atMs": 1}]
        self.assertBreaks(r, "late[0].atMs: 모르는 키")

    def test_v9_tags_and_ten_lines(self):
        """tidy.md v9 (r18): today[].tag is one of five, in their order, and today has 10 lines at most; a v8 report still passes."""
        r = good()
        r["today"][0]["tag"] = "important"
        r["today"][1]["tag"] = "request"
        self.assertEqual([], rc.check(r))
        self.assertEqual([], rc.check(good()), "a v8 report without tags still passes")
        r["today"] = [{"id": f"t_{i}", "what": "서류 보내기", "who": "△△철강", "since": "어제 요청", "status": "", "check": False,
                       "tag": "request"} for i in range(10)]
        r["head"]["todayChecks"] = 10
        self.assertEqual([], rc.check(r))
        r["today"].append(dict(r["today"][0], id="t_x"))
        r["head"]["todayChecks"] = 11
        self.assertBreaks(r, "today: 11줄 (최대 10줄)")
        bad = good()
        bad["today"][0]["tag"] = "urgent"
        self.assertBreaks(bad, "today[0].tag: 분류 부호가 아님")
        bad["today"][0]["tag"] = "waiting"
        self.assertBreaks(bad, "today[0].tag: 분류 부호가 아님")
        bad["today"][0]["tag"] = 1
        self.assertBreaks(bad, "today[0].tag: 분류 부호가 아님")
        order = good()
        order["today"][0]["tag"] = "optional"
        order["today"][1]["tag"] = "important"
        self.assertBreaks(order, "today: tag 순서가 아님")
        self.assertEqual(("important", "confirm", "request", "regular", "optional"), rc.TAGS)
        # 11/13: important is three lines at most (the first v9 report tagged 8 of 10).
        many = good()
        many["today"] = [{"id": f"t_{i}", "what": "서류 보내기", "who": "△△철강", "since": "어제 요청", "status": "", "check": False,
                          "tag": "important" if i < 4 else "request"} for i in range(6)]
        many["head"]["todayChecks"] = 6
        self.assertBreaks(many, "today: important가 3줄을 넘음")
        many["today"][3]["tag"] = "request"
        self.assertEqual([], rc.check(many))
        got = rc.check(bad)
        self.assertFalse(any("서류" in g or "견적" in g for g in got), "reasons only")


    def test_v10_state_wrong_items_stay_out_of_today(self):
        """tidy.md v10 (v3.9 r20): with --state, a today line that is an item marked [틀렸어요] fails; no state file is no failure."""
        with tempfile.TemporaryDirectory() as d:
            report = os.path.join(d, "2026-11-07.json")
            with open(report, "w", encoding="utf-8") as f:
                json.dump(good(), f, ensure_ascii=False)
            state = os.path.join(d, "state.json")
            with open(state, "w", encoding="utf-8") as f:
                json.dump({"wrong": [{"id": "t_e5f6a7b8", "title": "안전 점검 서류", "company": None, "createdMs": 1, "wrongMs": 2}]}, f, ensure_ascii=False)
            reasons = rc.check_file(report, rc.wrong_ids(rc.read_state(state)))
            self.assertEqual(["today[1].id: 틀린 일로 뺀 일(state wrong)"], reasons)
            self.assertEqual(1, rc.main([report, "--state", state]))
            self.assertEqual(0, rc.main([report, "--state", os.path.join(d, "none.json")]))
            self.assertEqual(0, rc.main([report]))
            self.assertEqual(2, rc.main([report, "--state"]))
        self.assertEqual({"a", "b"}, rc.wrong_ids({"wrong": [{"id": "a"}, "b", 3]}))
        self.assertEqual(set(), rc.wrong_ids({"wrong": None}))
    def test_v11_failed_calls(self):
        """tidy.md v11 (v4.0 r21-1): head.failedCalls is optional, a non-negative integer, and with --state the state's
        pending.yesterdayFailed (0 when the state has none)."""
        r = good()
        r["head"]["failedCalls"] = 2
        self.assertEqual([], rc.check(r))
        r["head"]["failedCalls"] = -1
        self.assertBreaks(r, "head.failedCalls")
        r["head"]["failedCalls"] = "2"
        self.assertBreaks(r, "head.failedCalls")
        with tempfile.TemporaryDirectory() as d:
            report = os.path.join(d, "2026-11-07.json")
            r["head"]["failedCalls"] = 2
            with open(report, "w", encoding="utf-8") as f:
                json.dump(r, f, ensure_ascii=False)
            state = os.path.join(d, "state.json")
            with open(state, "w", encoding="utf-8") as f:
                json.dump({"pending": {"yesterdayCalls": 1, "yesterdayMin": 3, "yesterdayFailed": 2}}, f)
            self.assertEqual(0, rc.main([report, "--state", state]))
            with open(state, "w", encoding="utf-8") as f:
                json.dump({"pending": {"yesterdayCalls": 1, "yesterdayMin": 3}}, f)
            self.assertEqual(["head.failedCalls: state의 pending.yesterdayFailed와 다름"],
                             rc.check_file(report, set(), rc.state_failed(rc.read_state(state))))
            self.assertEqual(1, rc.main([report, "--state", state]))
            self.assertEqual(0, rc.main([report]), "without a state only the format is checked")
            del r["head"]["failedCalls"]
            with open(report, "w", encoding="utf-8") as f:
                json.dump(r, f, ensure_ascii=False)
            self.assertEqual(0, rc.main([report, "--state", state]), "a v10 report without the key passes")
        self.assertIsNone(rc.state_failed(None))
        self.assertEqual(0, rc.state_failed({"pending": None}))


if __name__ == "__main__":
    unittest.main()
