package kr.callbrief.core

import kotlin.test.*

/** Fabricated numbers/names only. The app's private exclusion fingerprints are not inspected. */
class ContactBoundaryRegressionTest {
    private val number="01000001111"
    private val rules=PrivacyRules(setOf(PrivacyRules.hash(number)),listOf(ExcludedAlias(PrivacyRules.hash("민솔"),2)))
    private fun file(value:String)="통화 녹음_${value}_20261010_100000.wav"
    @Test fun P01ShortAliasDoesNotExcludeUnrelatedLongerFullName() {
        assertFalse(rules.excluded(file("시민솔루션")))
        assertEquals("시민솔루션",ContactBoundary.parse(file("시민솔루션"))!!.alias)
    }
    @Test fun P02WholeAliasWithEmojiAndPunctuationStillExcludes() {
        for(value in listOf("민솔🌸","민 솔 ❤️","민·솔~~","민솔👍🏽","민솔\uFE0F")) assertTrue(rules.excluded(file(value)))
    }
    @Test fun P03InternationalAndSeparatedNumbersMatchExactly() {
        for(value in listOf(number,"010-0000-1111","010 0000 1111","010_0000_1111","+82 10 0000 1111","0082 (0)10 0000 1111","０１０００００１１１１")) {
            assertTrue(rules.excluded(file(value)));assertEquals(setOf(number),ContactBoundary.parse(file(value))!!.phones)
        }
    }
    @Test fun P04DifferentPhoneWithSharedDigitsIsNotExcluded() {
        assertFalse(rules.excluded(file("01000002222")));assertEquals(setOf("01000002222"),ContactBoundary.parse(file("01000002222"))!!.phones)
    }
    @Test fun P05LongOrShortEmbeddedDigitStringsAreHeld() {
        for(value in listOf("101000001111","010000011111","0100000111","문자01000001111문자")) assertNull(ContactBoundary.parse(file(value)))
    }
    @Test fun P06UnknownFilenameAndBareUnmarkedNameAreHeld() {
        for(value in listOf("일반 자료.wav","민솔_20261010_100000.wav","자료_20261010_100000.wav","민솔.wav")) assertNull(ContactBoundary.parse(value))
    }
    @Test fun P07InvalidOrMultipleCalendarFieldsAreHeld() {
        for(value in listOf("통화 녹음_합성_20260230_100000.wav","통화 녹음_합성_20261010_100000_20261011_100000.wav")) assertNull(ContactBoundary.parse(value))
    }
    @Test fun P08TrailingUnrecognisedFieldsAreHeld() {
        for(value in listOf("통화 녹음_합성_20261010_100000_수정.wav","통화 녹음_합성_20261010_100000_2.wav")) assertNull(ContactBoundary.parse(value))
    }
    @Test fun P09TwoNumbersIncludingRepeatedOneAreHeld() {
        for(value in listOf("$number 01000002222","$number $number")) assertNull(ContactBoundary.parse(file(value)))
    }
    @Test fun P10UnconfirmedPhoneCannotBeApprovedByDiscardingLetters() {
        for(value in listOf("x${number}y","++82 10 0000 1111","010/0000/1111")) assertNull(PrivacyRules.validatedPhone(value))
    }
    @Test fun P11AliasPhoneFieldsHaveIndependentExclusionRules() {
        assertTrue(rules.excluded(file("민솔 01000002222")));assertTrue(rules.excluded(file("가상기관 $number")))
        assertFalse(rules.excluded(file("시민솔루션 01000002222")))
    }
    @Test fun P12DateDigitsAreNotPhoneEvidence() {
        assertTrue(ContactBoundary.parse(file("가상기관"))!!.phones.isEmpty());assertNull(ContactBoundary.parse("20261010_100000.wav"))
    }
    @Test fun P13WholePhoneFilenameCanRemainDateUnknown() {
        assertEquals(setOf(number),ContactBoundary.parse("$number.wav")!!.phones);assertTrue(rules.excluded("$number.wav"))
    }
    @Test fun P14KnownRecorderNameWithoutTimeRemainsOnlyContactMetadata() {
        assertEquals("가상기관🌼",ContactBoundary.parse("통화 녹음_가상기관🌼.wav")!!.alias)
        assertNull(RecordingNames.startedAt("통화 녹음_가상기관🌼.wav"))
    }
    @Test fun P15PathControlsAndUnsupportedExtensionCannotSupplyContact() {
        for(value in listOf("통화 녹음_가상/기관.wav","통화 녹음_가상\n기관.wav","통화 녹음_가상기관.zip")) assertNull(ContactBoundary.parse(value))
    }
    @Test fun P16FullWidthRecorderPhoneAndAliasKeepExactNormalization() {
        assertEquals("A회사",ContactBoundary.parse(file("Ａ회사 ０１０００００２２２２"))!!.alias)
        assertFalse(rules.excluded(file("Ａ회사 ０１０００００２２２２")))
    }
}
