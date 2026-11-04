package kr.callbrief.core

import java.text.Normalizer

/** The one phone-number key (design v2.8 r9-1, review problem 14). Every stored number, call-log line, text address and
 * file-name number goes through [of], so one phone always gets one key: Korean national digits ("01012345678", "021234567",
 * "15881234"). Names, mail addresses, foreign numbers and short codes give null. */
object PhoneKey {
    /** [raw] as a key, or null when it is not a Korean phone number. */
    fun of(raw:String?):String? {
        val v=raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if(v.contains('@')) return null
        // NFKC folds full-width digits; "(0)" after the country code is the trunk prefix written twice.
        val s=Normalizer.normalize(v,Normalizer.Form.NFKC).replace(Regex("\\(\\s*0\\s*\\)"),"")
        val firstDigit=s.indexOfFirst { it.isDigit() };if(firstDigit<0) return null
        val plus=s.substring(0,firstDigit).contains('+')
        val digits=s.filter { it.isDigit() }
        fun national(rest:String)=if(rest.startsWith("0")) rest else "0$rest"
        val candidates=buildList {
            when {
                plus && digits.startsWith("82") -> add(national(digits.drop(2)))
                digits.startsWith("0082") -> add(national(digits.drop(4)))
                // "82" without a plus: only when a Korean number of 9 to 11 digits follows (no national number starts with 82).
                digits.startsWith("82") && digits.length in 11..13 -> add(national(digits.drop(2)))
            }
            add(digits)
        }
        return candidates.firstOrNull { PrivacyRules.validKoreanPhone(it) }
    }
    /** True when [key] came out of [of]: digits only and a valid Korean number. */
    fun isKey(key:String?):Boolean = key!=null && key.all { it.isDigit() } && PrivacyRules.validKoreanPhone(key)
}
