package kr.callbrief.pipeline.bench

import java.math.BigInteger
import java.text.Normalizer
import java.util.Locale

/** r18-bench (DESIGN_V7 4.4): the normalized Korean CER of tools/eval/ko_cer.py, rule for rule, so the phone's bench and the
 * workspace give the same number for the same text. tools/eval/ko_cer_parity.json pins both (KoCerTest, test_ko_cer_parity.py).
 * Normalization (the same for reference and hypothesis): NFC -> spoken/mixed numbers before a unit to integers ("삼십만 원" and
 * "30만원" -> "300000원") -> native counters ("두 장" -> "2장") -> 퍼센트/프로 -> % -> every character that is not a letter, a
 * number, '_' or '%' removed (spacing and punctuation do not count) -> lower case. Nothing here leaves the phone; only the
 * counts the bench makes from it do. */
internal object KoCer {
    private val SINO=mapOf('영' to 0,'공' to 0,'일' to 1,'이' to 2,'삼' to 3,'사' to 4,'오' to 5,'육' to 6,'륙' to 6,'칠' to 7,'팔' to 8,'구' to 9)
    private val SMALL=mapOf('십' to 10L,'백' to 100L,'천' to 1000L)
    private val BIG=mapOf('만' to BigInteger.TEN.pow(4),'억' to BigInteger.TEN.pow(8),'조' to BigInteger.TEN.pow(12))
    private val NATIVE=mapOf("한" to 1,"두" to 2,"세" to 3,"네" to 4,"다섯" to 5,"여섯" to 6,"일곱" to 7,"여덟" to 8,"아홉" to 9,"열" to 10)
    private const val COUNTERS="시|개|명|번|살|장|대|권|달|병|잔|통"
    private const val NUMCH="영공일이삼사오육륙칠팔구십백천만억조"
    /** Python's \s is str.isspace(): Unicode white space plus the separators U+001C..U+001F. */
    private const val WS="[\\s\\x1c-\\x1f]"
    // (?U): \d and \s are Unicode classes, as in Python 3's re on str.
    private const val UNIT="(?=$WS*(?:원|만|억|조|시|분|초|일|월|년|개|명|번|층|호|퍼센트|프로|%|km|kg|살|장|대|권|달|주|회|차))"
    private const val TOK="(?:\\d[\\d,]*|[$NUMCH])"
    private val SPAN=Regex("(?U)(?<![가-힣\\d])$TOK(?:$WS?$TOK)*$UNIT")
    private val TOKENS=Regex("(?U)\\d[\\d,]*|[$NUMCH]")
    private val NATIVE_COUNT=Regex("(?U)(?<![가-힣])(다섯|여섯|일곱|여덟|아홉|한|두|세|네|열)$WS*(?=($COUNTERS))")

    /** ko_cer.parse_num: digits, sino-Korean digits and units mixed ("3억 5천만", "30만", "천이백", "1,200"). */
    fun parseNum(span:String):BigInteger {
        var total=BigInteger.ZERO;var chunk=BigInteger.ZERO;var num:BigInteger?=null
        for(m in TOKENS.findAll(span)) {
            val t=m.value;val c=t[0]
            when {
                Character.isDigit(c) -> num=BigInteger(t.filter { it!=',' }.map { Character.digit(it,10) }.joinToString(""))
                c in SINO -> num=BigInteger.valueOf(SINO.getValue(c).toLong())
                c in SMALL -> { chunk+=(num ?: BigInteger.ONE)*BigInteger.valueOf(SMALL.getValue(c));num=null }
                c in BIG -> { val head=chunk+(num ?: BigInteger.ZERO);total+=(if(head.signum()==0) BigInteger.ONE else head)*BIG.getValue(c);chunk=BigInteger.ZERO;num=null }
            }
        }
        return total+chunk+(num ?: BigInteger.ZERO)
    }

    /** Python's \w on str: letters (L*), numbers (Nd, Nl, No) and '_'; '%' is kept besides. */
    private fun kept(cp:Int):Boolean = cp=='_'.code || cp=='%'.code || when(Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER,Character.LOWERCASE_LETTER,Character.TITLECASE_LETTER,Character.MODIFIER_LETTER,Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER,Character.LETTER_NUMBER,Character.OTHER_NUMBER -> true
        else -> false }

    fun normalize(text:String):String {
        var t=Normalizer.normalize(text,Normalizer.Form.NFC)
        t=SPAN.replace(t) { parseNum(it.value).toString() }
        t=NATIVE_COUNT.replace(t) { NATIVE.getValue(it.groupValues[1]).toString() }
        t=t.replace("퍼센트","%").replace("프로","%")
        val out=StringBuilder(t.length)
        var i=0
        while(i<t.length) { val cp=t.codePointAt(i);if(kept(cp)) out.appendCodePoint(cp);i+=Character.charCount(cp) }
        return out.toString().lowercase(Locale.ROOT)
    }

    /** Character edit distance (substitutions + deletions + insertions), by code point: jiwer.process_characters' count. */
    fun edits(ref:String,hyp:String):Int {
        val a=ref.codePoints().toArray();val b=hyp.codePoints().toArray()
        if(a.isEmpty()) return b.size
        if(b.isEmpty()) return a.size
        var prev=IntArray(b.size+1) { it };var cur=IntArray(b.size+1)
        for(i in 1..a.size) {
            cur[0]=i
            for(j in 1..b.size) cur[j]=minOf(prev[j]+1,cur[j-1]+1,prev[j-1]+if(a[i-1]==b[j-1]) 0 else 1)
            val x=prev;prev=cur;cur=x
        }
        return prev[b.size]
    }

    /** Length in code points (jiwer counts characters). */
    fun length(s:String)=s.codePointCount(0,s.length)

    private val DIGITS=Regex("\\d+")
    /** Number tokens of a normalized text (digit runs), as a multiset. */
    fun numbers(normalized:String):Map<String,Int> = DIGITS.findAll(normalized).map { it.value }.groupingBy { it }.eachCount()
    /** How many of the reference's number tokens the hypothesis has too (multiset), and how many the reference has. */
    fun numberHits(refNorm:String,hypNorm:String):Pair<Int,Int> {
        val r=numbers(refNorm);val h=numbers(hypNorm)
        return r.entries.sumOf { (k,n) -> minOf(n,h[k] ?: 0) } to r.values.sum()
    }
    /** Ledger names (normalized, at least two letters) said in the reference, and how many of those the hypothesis has too. */
    fun nameHits(refNorm:String,hypNorm:String,names:Collection<String>):Pair<Int,Int> {
        val said=names.filter { it.length>=2 && refNorm.contains(it) }
        return said.count { hypNorm.contains(it) } to said.size
    }
    /** A silence hallucination: a piece of speech shorter than a second whose output is one or two letters. */
    fun hallucination(durationMs:Long,text:String):Boolean = durationMs in 0 until 1000 && length(normalize(text)) in 1..2
}
