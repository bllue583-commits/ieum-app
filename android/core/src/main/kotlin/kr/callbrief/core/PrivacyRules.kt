package kr.callbrief.core

import java.security.MessageDigest
import java.text.Normalizer

/** These are matching fingerprints, NOT anonymization or encryption of contact data. */
data class ExcludedAlias(val fingerprint: String, val length: Int)
/** [salt] (v3.8 r18-lock, DESIGN_V7 4.2-5): the device's secret salt as 64 hex characters, or null. A value matches a rule
 * by its salted fingerprint ([salted]) or by the old unsalted one ([hash]): fingerprints written before the salt existed
 * cannot be made again without the original value, so both forms are compared. */
data class PrivacyRules(val phones: Set<String>, val aliases: List<ExcludedAlias>, val salt: String? = null) {
    private fun fingerprints(value: String): List<String> = listOfNotNull(hash(value), salt?.let { salted(it, value) })
    private fun phoneRule(value: String) = fingerprints(value).any { it in phones }
    fun excluded(nameOrAlias: String, explicitPhone: String? = null): Boolean {
        if (explicitPhone != null && validatedPhone(explicitPhone)?.let(::phoneRule) == true) return true
        if (phoneTokens(nameOrAlias).any(::phoneRule)) return true
        val alias = if (explicitPhone != null || !ContactBoundary.isAudioFilename(nameOrAlias)) nameOrAlias
            else ContactBoundary.parse(nameOrAlias)?.alias ?: return false
        val canonical = normalizeAlias(alias)
        if (canonical.isEmpty()) return false
        // A legacy short fingerprint denotes the whole alias, never every substring of a filename.
        val forms = fingerprints(canonical)
        return aliases.any { rule -> canonical.length == rule.length && rule.fingerprint in forms }
    }
    companion object {
        /** The old, unsalted fingerprint (SHA-256 of the value). Kept for matching what was written with it. */
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private val SALT_RE = Regex("[0-9a-f]{64}")
        /** A salt as stored: 32 bytes as 64 lower-case hex characters. */
        fun validSalt(value: String?): Boolean = value != null && SALT_RE.matches(value)
        /** The salted fingerprint: HMAC-SHA256 keyed with the device salt (64 hex characters). */
        fun salted(salt: String, value: String): String {
            require(validSalt(salt)) { "salt" }
            val key = ByteArray(32) { i -> salt.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
            return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        fun normalizeAlias(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
        fun validKoreanPhone(number: String): Boolean = Regex("(?:010[0-9]{8}|02[0-9]{7,8}|0(?:31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64)[0-9]{7,8}|070[0-9]{8}|1[568][0-9]{6})").matches(number)
        fun validatedPhone(value: String): String? {
            val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            if (!Regex("\\+?[0-9][0-9 _().-]*").matches(normalized)) return null
            // One number rule for the whole app (PhoneKey, design v2.8 r9-1).
            return PhoneKey.of(normalized)
        }
        private val national = "(?:010|02|0(?:31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64)|070)"
        private val phonePattern = Regex("(?<![\\p{L}\\p{N}])(?:\\+?82[ _().-]*(?:0[ _().-]*)?(?:10|2|31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64|70)|0082[ _().-]*(?:0[ _().-]*)?(?:10|2|31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64|70)|$national)[ _().-]*(?:[0-9][ _().-]*){7,8}(?![\\p{L}\\p{N}])")
        private val servicePattern = Regex("(?<![\\p{L}\\p{N}])1[568][0-9][0-9][ _().-]*[0-9]{4}(?![\\p{L}\\p{N}])")
        private fun phoneMatches(value: String): List<MatchResult> {
            val selected=mutableListOf<MatchResult>()
            for(match in (phonePattern.findAll(value)+servicePattern.findAll(value)).filter { PhoneKey.of(it.value) != null }
                .sortedWith(compareBy<MatchResult> { it.range.first }.thenByDescending { it.range.last })) {
                if(selected.none { it.range.first<=match.range.last && match.range.first<=it.range.last }) selected.add(match)
            }
            return selected
        }
        fun phoneTokens(filename: String): Set<String> {
            val value = Normalizer.normalize(filename, Normalizer.Form.NFKC)
            return phoneMatches(value).mapNotNull { PhoneKey.of(it.value) }.toSet()
        }
        fun phoneTokenCount(value: String): Int = phoneMatches(Normalizer.normalize(value, Normalizer.Form.NFKC)).size
        fun removePhoneTokens(value: String): String = replacePhoneTokens(value, "")
        /** Every phone-number span (mobile, 9 and 10 digit landlines like "031 123 4567", +82 forms, 1588 numbers) replaced
         * by [replacement]; the rest stays character for character. */
        fun replacePhoneTokens(value: String, replacement: String): String {
            // Detect normalized phone spans but retain the original alias character-for-character.
            // Compatibility folding is safe for exclusions, never for permission/identity reuse.
            val normalized=StringBuilder();val starts=mutableListOf<Int>();val ends=mutableListOf<Int>()
            var offset=0
            while(offset<value.length) {
                val end=offset+Character.charCount(value.codePointAt(offset))
                val folded=Normalizer.normalize(value.substring(offset,end),Normalizer.Form.NFKC)
                normalized.append(folded);repeat(folded.length) { starts.add(offset);ends.add(end) };offset=end
            }
            var result=value
            for(match in phoneMatches(normalized.toString()).sortedByDescending { it.range.first }) {
                result=result.replaceRange(starts[match.range.first],ends[match.range.last],replacement)
            }
            return result
        }
    }
}
