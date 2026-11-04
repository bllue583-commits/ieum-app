package kr.callbrief.core

import kotlin.test.*

/** Design v2.8 5.4: the six spellings of review table 6.3 (and more) all give one key. */
class PhoneKeyTest {
    @Test fun theReviewTableSpellingsGiveOneKey() {
        for(raw in listOf("+82 10-1234-5678","+82 (0)10-1234-5678","010-1234-5678","0082 10 1234 5678","821012345678","+82-10-1234-5678","(010) 1234 5678","010.1234.5678"))
            assertEquals("01012345678",PhoneKey.of(raw),raw)
        assertEquals("021234567",PhoneKey.of("+82 2-123-4567"))
        assertEquals("021234567",PhoneKey.of("02-123-4567"))
        assertEquals("0212345678",PhoneKey.of("+82 (0)2 1234 5678"))
        assertEquals("0311234567",PhoneKey.of("031 123 4567"))
        assertEquals("07012345678",PhoneKey.of("+82 70-1234-5678"))
        // Without the plus: a Seoul number of 7 digits after 82 is still Seoul (the exclusion rules read it so before r9-1).
        assertEquals("021234567",PhoneKey.of("82 2 123 4567"))
    }
    @Test fun theExclusionRulesUseTheSameKey() {
        for(raw in listOf("+82 10-1234-5678","0082 10 1234 5678","82 2 123 4567","1588-1234"))
            assertEquals(PhoneKey.of(raw),PrivacyRules.validatedPhone(raw),raw)
        assertEquals(setOf("01012345678"),PrivacyRules.phoneTokens("통화 녹음 +82 10-1234-5678_251029.m4a"))
    }
    @Test fun fullWidthAndWrappedNumbersFold() {
        assertEquals("01012345678",PhoneKey.of("０１０－１２３４－５６７８"))
        assertEquals("01012345678",PhoneKey.of("tel:+82 10 1234 5678"))
        assertEquals("01012345678",PhoneKey.of("  010 1234 5678  "))
    }
    @Test fun serviceNumbersKeepTheirEightDigits() {
        assertEquals("15881234",PhoneKey.of("1588-1234"))
        assertEquals("16001234",PhoneKey.of("1600 1234"))
        assertEquals("18001234",PhoneKey.of("1800-1234"))
    }
    @Test fun whatIsNotAKoreanPhoneGivesNull() {
        for(raw in listOf(null,"","   ","홍길동","홍길동 대리","kim@example.com","+1 415 555 0100","12345","0101234","01012345678901","011-123-4567","82"))
            assertNull(PhoneKey.of(raw),raw ?: "null")
    }
    @Test fun aKeyIsRecognisedAndIsStableUnderItself() {
        val k=PhoneKey.of("+82 10-1234-5678")!!
        assertTrue(PhoneKey.isKey(k));assertEquals(k,PhoneKey.of(k))
        assertFalse(PhoneKey.isKey("821012345678"));assertFalse(PhoneKey.isKey("홍길동"));assertFalse(PhoneKey.isKey(null))
    }
}
