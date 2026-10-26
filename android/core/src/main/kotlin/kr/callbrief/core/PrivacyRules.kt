package kr.callbrief.core

import java.security.MessageDigest
import java.text.Normalizer

/** These are matching fingerprints, NOT anonymization or encryption of contact data. */
data class ExcludedAlias(val fingerprint: String, val length: Int)
data class PrivacyRules(val phones: Set<String>, val aliases: List<ExcludedAlias>) {
    fun excluded(nameOrAlias: String, explicitPhone: String? = null): Boolean {
        if (explicitPhone != null && validatedPhone(explicitPhone)?.let { hash(it) in phones } == true) return true
        if (phoneTokens(nameOrAlias).any { hash(it) in phones }) return true
        val alias = if (explicitPhone != null || !ContactBoundary.isAudioFilename(nameOrAlias)) nameOrAlias
            else ContactBoundary.parse(nameOrAlias)?.alias ?: return false
        val canonical = normalizeAlias(alias)
        // A legacy short fingerprint denotes the whole alias, never every substring of a filename.
        return canonical.isNotEmpty() && aliases.any { rule -> canonical.length == rule.length && hash(canonical) == rule.fingerprint }
    }
    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun normalizePhone(value: String): String {
            var digits = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("[^0-9]"), "")
            if (digits.startsWith("0082")) digits = digits.drop(4).let { if (it.startsWith("0")) it else "0" + it }
            else if (digits.startsWith("82")) digits = digits.drop(2).let { if (it.startsWith("0")) it else "0" + it }
            return digits
        }
        fun normalizeAlias(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
        fun validKoreanPhone(number: String): Boolean = Regex("(?:010[0-9]{8}|02[0-9]{7,8}|0(?:31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64)[0-9]{7,8}|070[0-9]{8}|1[568][0-9]{6})").matches(number)
        fun validatedPhone(value: String): String? {
            val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            if (!Regex("\\+?[0-9][0-9 _().-]*").matches(normalized)) return null
            return normalizePhone(normalized).takeIf { validKoreanPhone(it) }
        }
        private val national = "(?:010|02|0(?:31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64)|070)"
        private val phonePattern = Regex("(?<![\\p{L}\\p{N}])(?:\\+?82[ _().-]*(?:0[ _().-]*)?(?:10|2|31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64|70)|0082[ _().-]*(?:0[ _().-]*)?(?:10|2|31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64|70)|$national)[ _().-]*(?:[0-9][ _().-]*){7,8}(?![\\p{L}\\p{N}])")
        private val servicePattern = Regex("(?<![\\p{L}\\p{N}])1[568][0-9][0-9][ _().-]*[0-9]{4}(?![\\p{L}\\p{N}])")
        private fun phoneMatches(value: String): List<MatchResult> {
            val selected=mutableListOf<MatchResult>()
            for(match in (phonePattern.findAll(value)+servicePattern.findAll(value)).filter { validKoreanPhone(normalizePhone(it.value)) }
                .sortedWith(compareBy<MatchResult> { it.range.first }.thenByDescending { it.range.last })) {
                if(selected.none { it.range.first<=match.range.last && match.range.first<=it.range.last }) selected.add(match)
            }
            return selected
        }
        fun phoneTokens(filename: String): Set<String> {
            val value = Normalizer.normalize(filename, Normalizer.Form.NFKC)
            return phoneMatches(value).map { normalizePhone(it.value) }.toSet()
        }
        fun phoneTokenCount(value: String): Int = phoneMatches(Normalizer.normalize(value, Normalizer.Form.NFKC)).size
        fun removePhoneTokens(value: String): String {
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
                result=result.removeRange(starts[match.range.first],ends[match.range.last])
            }
            return result
        }
    }
}
