package kr.callbrief.core

import kotlin.test.*

/** Fabricated identifiers only; no real excluded contact values in test fixtures. */
class PrivacyRulesTest {
    private val number = "01000001111"
    private val rules = PrivacyRules(setOf(PrivacyRules.hash(number)), listOf(ExcludedAlias(PrivacyRules.hash("가상인물"),4)))
    @Test fun allPhoneFormsAreBlocked() {
        for (value in listOf("01000001111","010-0000-1111","010 0000 1111","+82 10 0000 1111","0082 10 0000 1111","+82 (0)10 0000 1111","0082 (0)10 0000 1111","０１０００００１１１１")) {
            assertTrue(rules.excluded("통화 녹음 ${value}_261009_112233.m4a"))
            assertTrue(rules.excluded("unresolved-name",value))
        }
    }
    @Test fun emojiSpacesAndPunctuationCannotBypassAlias() {
        for (value in listOf("가상인물~~❤️","가 상 인 물 😊","가상인물\uFE0F","가상인물👍🏽","가상인물·♡")) {
            assertTrue(rules.excluded("통화 녹음 ${value}_261009_112233.m4a"))
        }
    }
    @Test fun timestampsAndDisplayNamesAreNotPhoneIdentity() {
        assertTrue(PrivacyRules.phoneTokens("통화 녹음 가상인물_261009_112233.m4a").isEmpty())
        assertTrue(PrivacyRules.phoneTokens("20261009_112233.wav").isEmpty())
        assertTrue(PrivacyRules.phoneTokens("이름_101000001111.m4a").isEmpty())
        assertFalse(rules.excluded("다른 상대_261009_112233.wav"))
    }
    @Test fun phoneFormatsAndScopedAliasExtraction() {
        for(value in listOf("02-1234-5678","02-1588-1234","031 123 4567","070-1234-5678","1588-1234","+82 2 1234 5678")) assertEquals(1,PrivacyRules.phoneTokens("통화 녹음 ${value}_261009_112233.m4a").size)
        assertEquals("가상업체",RecordingNames.callerAlias("통화 녹음 가상업체_261009_112233.m4a"))
        assertEquals("가상업체",RecordingNames.callerAlias("통화 녹음 가상업체 02-1234-5678_261009_112233.m4a"))
        assertEquals("Ａ회사",RecordingNames.callerAlias("통화 녹음 Ａ회사 ０２-１２３４-５６７８_261009_112233.m4a"))
        assertEquals("가상업체-",RecordingNames.callerAlias("통화 녹음 가상업체-_261009_112233.m4a"))
        assertNull(RecordingNames.callerAlias("시각없는파일.m4a"))
        assertFalse(PrivacyRules.validKoreanPhone("20261009"))
        assertFalse(PrivacyRules.validKoreanPhone("12345678"))
    }
    @Test fun explicitPhoneDominatesHarmlessAlias() {
        assertTrue(rules.excluded("안전하다고 쓴 별칭",number))
        assertTrue(rules.excluded("가상인물", "01000002222"))
        assertFalse(rules.excluded("다른 상대", "01000002222"))
    }
}
