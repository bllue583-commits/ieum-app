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

    @Test fun trigramCoverageCountsTheShareOfAQuotesPiecesFoundInTheText() {
        val text=NameKey.trigrams(NameKey.bare("견적서는 내일까지 메일로 보내드릴게요"))
        assertEquals(1.0,NameKey.coverage3(NameKey.bare("견적서는 내일까지 메일로"),text),0.0)
        assertTrue(NameKey.coverage3(NameKey.bare("견적서는 모레까지 메일로"),text)<0.9)
        assertEquals(0.0,NameKey.coverage3("",text),0.0)
    }
}
