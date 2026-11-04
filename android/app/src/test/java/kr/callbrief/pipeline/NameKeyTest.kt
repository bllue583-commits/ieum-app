package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test

/** The one name key (review B10) and the text measures behind duplicate titles (B11) and quotes (B13), on made-up names. */
class NameKeyTest {
    @Test fun spacesHonorificsAndCompanyMarksAreDropped() {
        assertEquals("김민수",NameKey.canon("김민수"))
        assertEquals("김민수",NameKey.canon(" 김 민수 "))
        assertEquals("김민수",NameKey.canon("김민수 과장"));assertEquals("김민수",NameKey.canon("김민수과장님"));assertEquals("김민수",NameKey.canon("김민수 부장님"))
        assertEquals("김민수",NameKey.canon("김민수 팀장"));assertEquals("김민수",NameKey.canon("소장 김민수"));assertEquals("김민수",NameKey.canon("김민수님"))
        assertEquals("△△철강",NameKey.canon("(주)△△철강"));assertEquals("△△철강",NameKey.canon("△△철강(주)"));assertEquals("△△철강",NameKey.canon("주식회사 △△ 철강"))
        assertEquals("△△철강",NameKey.canon("㈜△△철강"));assertEquals("△△철강",NameKey.canon("(유) △△철강"));assertEquals("△△철강",NameKey.canon("△△철강 대표님"))
        assertEquals(NameKey.canon("△△철강"),NameKey.canon("(주) △△ 철강"))
        assertEquals("abc건설",NameKey.canon("ABC 건설"))
    }

    @Test fun anHonorificStuckToAShortNameIsPartOfTheName() {
        assertEquals("박대리",NameKey.canon("박대리"));assertEquals("이과장",NameKey.canon("이과장"));assertEquals("박대리",NameKey.canon("박 대리"))
        assertEquals("대표",NameKey.canon("대표")) // nothing left otherwise
        assertEquals("",NameKey.canon(null));assertEquals("",NameKey.canon("  "))
        // Different people never share a key just because both are called 과장.
        assertNotEquals(NameKey.canon("김과장"),NameKey.canon("이과장"))
        assertNotEquals(NameKey.canon("△△철강"),NameKey.canon("□□철강"))
    }

    @Test fun spacingDoesNotChangeAPersonKeyAndSurnamesWithDifferentTitlesStayApart() {
        assertEquals("김과장",NameKey.canon("김 과장"));assertEquals(NameKey.canon("김과장"),NameKey.canon("김 과장"))
        assertEquals(3,setOf(NameKey.canon("김 과장"),NameKey.canon("김 대리"),NameKey.canon("김 소장")).size)
        assertEquals("김민수",NameKey.canon("김민수 과장"));assertEquals("김민수",NameKey.canon("김민수과장님"));assertEquals("김과장",NameKey.canon("김과장님"))
        assertEquals("김민수",NameKey.canon("김 민수 과장님"))
    }

    @Test fun aCompanyKeyKeepsATitleLikeEnding() {
        assertNotEquals(NameKey.canonCompany("△△이사"),NameKey.canonCompany("△△"))
        assertEquals("△△이사",NameKey.canonCompany("(주) △△ 이사"))
        assertEquals("△△철강",NameKey.canonCompany("(주)△△철강"));assertEquals("△△철강",NameKey.canonCompany("주식회사 △△ 철강"));assertEquals("abc건설",NameKey.canonCompany("ABC 건설"))
        assertEquals("",NameKey.canonCompany(null))
        assertTrue(NameKey.similar("△△철강","△△철깅",company=true));assertFalse(NameKey.similar("△△이사","△△",company=true))
    }

    @Test fun titlesWithDifferentNumbersAreNeverTheSameItemHoweverSimilarTheWords() {
        listOf("3층 타설 일정 확인" to "4층 타설 일정 확인","□□레미콘 11/5 타설 확인" to "□□레미콘 11/12 타설 확인","2공구 철근 반입 확인" to "3공구 철근 반입 확인").forEach { (x,y) ->
            assertFalse("$x / $y",NameKey.nearTitle(x,y));assertFalse(NameKey.sameWords(x,y)) }
        assertTrue(NameKey.nearTitle("△△철강 김민수 과장 3층 철근 견적서 회신 확인","△△철강 김민수 과장 3층 철근 견적서 회신 여부 확인"))
        assertTrue(NameKey.sameWords("3층 타설 일정 확인","3층타설일정확인"));assertEquals(NameKey.numbers("05일 5번"),setOf("5"))
    }

    @Test fun oneJamoApartIsTheSameNameOnlyForLongEnoughNamesWithTheSameFirstLetter() {
        assertTrue(NameKey.similar("김민수","김민서")) // vowel
        assertTrue(NameKey.similar("김민수","김민숙")) // final consonant
        assertTrue(NameKey.similar("김민수 과장","김민서"))
        assertFalse(NameKey.similar("김민수","김민호"))   // two jamo
        assertFalse(NameKey.similar("김민수","이민수"))   // first letter differs
        assertFalse(NameKey.similar("김수","김서"))       // too short to guess
        assertTrue(NameKey.similar("(주)△△철강","△△철강"));assertFalse(NameKey.similar("△△철강","□□철강"))
        assertFalse(NameKey.similar(null,"김민수"))
        assertEquals(1,NameKey.distance(NameKey.jamo("수"),NameKey.jamo("서")));assertEquals(1,NameKey.distance(NameKey.jamo("수"),NameKey.jamo("숙")))
    }

    @Test fun titlesAreNearlyTheSameByTwoLetterPieces() {
        val a="△△철강 김민수 과장 3층 철근 견적서 회신 확인"
        assertTrue(NameKey.jaccard2(a,"△△철강 김민수 과장 3층 철근 견적서 회신 여부 확인")>=NameKey.SAME_TITLE)
        assertEquals(1.0,NameKey.jaccard2(a,a.replace(" ","")),0.0001)
        assertTrue(NameKey.jaccard2(a,"전혀 다른 일 도면 받기")<NameKey.SAME_TITLE)
        assertTrue(NameKey.jaccard2("3층 타설 일정 확인","11/12 타설 일정 확인")<NameKey.SAME_TITLE)
        assertEquals(0.0,NameKey.jaccard2("","견적서"),0.0)
    }

    /** Design 3.4: more titles, the role candidate, the first syllable by its consonant, the surname and title rule. */
    @Test fun moreTitlesAndTheRoleCandidate() {
        assertEquals(NameKey.canon("김민수"),NameKey.canon("김민수 씨"));assertEquals("김민수",NameKey.canon("김민수씨"))
        assertEquals("박철수",NameKey.canon("박철수 기사"));assertEquals("박철수",NameKey.canon("박철수 기사님"));assertEquals("이영호",NameKey.canon("이영호 감리"))
        assertEquals("최민수",NameKey.canon("최민수 선생님"));assertEquals("정민수",NameKey.canon("정민수 형님"))
        assertEquals("박기사",NameKey.canon("박기사님"));assertEquals("이감리",NameKey.canon("이감리"))
        assertEquals("김민수" to "과장",NameKey.canonParts("김민수 과장님"));assertEquals("김과장" to "과장",NameKey.canonParts("김과장"))
        assertEquals("김민수" to null,NameKey.canonParts("김민수 씨"));assertEquals("김민수" to "소장",NameKey.canonParts("소장 김민수"))
        assertEquals("김민수",NameKey.withoutTitle("김민수 과장님"));assertEquals("김과장",NameKey.withoutTitle("김과장"))
    }

    @Test fun theFirstSyllableNeedsOnlyItsConsonantAndTwoLettersOnlyOnTheSameNumber() {
        assertTrue(NameKey.similar("정민수","전민수"));assertTrue(NameKey.similar("이민수","임민수"))
        assertFalse(NameKey.similar("정민수","김민수"))
        assertFalse(NameKey.similar("민수","민서"));assertTrue(NameKey.similar("민수","민서",sameNumber=true))
        assertFalse(NameKey.similar("민수","철수",sameNumber=true))
    }

    @Test fun aSurnameWithATitleIsTheFullNameWithThatSurname() {
        assertTrue(NameKey.surnameTitle("김과장"));assertTrue(NameKey.surnameTitle("박 기사님"));assertFalse(NameKey.surnameTitle("김민수 과장"));assertFalse(NameKey.surnameTitle("김민수"))
        assertTrue(NameKey.sameBySurname("김과장","김민수 과장"));assertTrue(NameKey.sameBySurname("김민수","김과장"))
        assertFalse(NameKey.sameBySurname("김과장","이민수"));assertFalse(NameKey.sameBySurname("김과장","김대리"))
        assertTrue(NameKey.notAPerson("△△철강 사무실"));assertTrue(NameKey.notAPerson("현장"));assertTrue(NameKey.notAPerson("경리"));assertTrue(NameKey.notAPerson("총무"))
        assertFalse(NameKey.notAPerson("김민수"))
        assertTrue(NameKey.sameName("홍 길동","홍길동"));assertTrue(NameKey.sameName("홍길동 △△철강","홍길동"));assertFalse(NameKey.sameName("박철수","홍길동"))
    }

    @Test fun titlesAreComparedWithoutNamesAndActionWords() {
        val names=listOf("김민수","△△철강")
        assertEquals("철근반입일정",NameKey.core("김민수 과장 철근 반입 일정 확인",names))
        assertFalse(NameKey.nearTitle("김민수 과장 철근 반입 일정 확인","김민수 과장 철근 반입 수량 확인",names))
        assertFalse(NameKey.nearTitle("철근 반입 일정 확인","철근 반입 수량 확인"))
        assertTrue(NameKey.nearTitle("△△철강 김민수 과장 3층 철근 견적서 회신 확인","3층 철근 견적서 회신 여부 확인",names))
    }

    @Test fun aQuoteMustBeCoveredWithinOneStretchOfTheText() {
        val text=NameKey.bare("견적서는 내일까지 메일로 보내드릴게요. 그건 그렇고 오늘 날씨가 참 좋네요. 다음 주에 현장에서 뵙겠습니다")
        assertEquals(1.0,NameKey.windowCoverage3(NameKey.bare("견적서는 내일까지 메일로"),text),0.0)
        // Two phrases far apart glued together: all pieces are in the text, but not in one stretch.
        val glued=NameKey.bare("견적서는 내일까지 다음 주에 현장에서 뵙겠습니다")
        assertTrue(NameKey.coverage3(glued,NameKey.trigrams(text))>=0.8)
        assertTrue(NameKey.windowCoverage3(glued,text)<0.9)
    }

    @Test fun trigramCoverageCountsTheShareOfAQuotesPiecesFoundInTheText() {
        val text=NameKey.trigrams(NameKey.bare("견적서는 내일까지 메일로 보내드릴게요"))
        assertEquals(1.0,NameKey.coverage3(NameKey.bare("견적서는 내일까지 메일로"),text),0.0)
        assertTrue(NameKey.coverage3(NameKey.bare("견적서는 모레까지 메일로"),text)<0.9)
        assertEquals(0.0,NameKey.coverage3("",text),0.0)
    }
}
