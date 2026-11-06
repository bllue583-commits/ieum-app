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
        r["inProgress"] = [{"what": "공정 진행"}] * 4
        self.assertBreaks(r, "inProgress: 4줄 (최대 3줄)")

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


if __name__ == "__main__":
    unittest.main()
