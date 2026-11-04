package kr.callbrief.pipeline

/** One way to compare names, titles and quotes, used by the ledger and by the check of the model's answer (review B10, B11, B13).
 * [canon] is the key every name is stored and looked up by; [similar] allows one jamo of difference. */
internal object NameKey {
    /** Longest first, so "대표이사" goes before "이사". */
    private val TITLES=listOf("대표이사","공장장","본부장","팀장","실장","소장","차장","과장","부장","대리","사장","대표","이사","주임","사원","상무","전무","반장","님")
    private val CORPORATE=Regex("[(（\\[]\\s*[주유합사재]\\s*[)）\\]]|㈜|㈲|주식회사|유한회사|유한책임회사")
    private fun keep(c:Char)=c.isLetterOrDigit() || c.code in 0x25A0..0x25FF // letters, digits and the geometric signs used as placeholder names

    private fun cleaned(raw:String)=CORPORATE.replace(raw," ").map { if(keep(it)) it else ' ' }.joinToString("")

    /** A person's name without spaces, honorifics (과장, 부장, 님 ...), company-form marks and punctuation, in lower case.
     * Spaces go first and the honorific suffix is cut from the joined text, so "김 과장" and "김과장" are one key; but a
     * suffix is cut only when two or more letters stay, so a surname with a title ("김과장", "박 대리") keeps it and
     * different people never share the key of a bare surname. A title standing alone before the name ("소장 김민수") goes too. */
    fun canon(name:String?):String {
        val raw=name.orEmpty().trim()
        var tokens=cleaned(raw).split(' ').filter { it.isNotEmpty() }
        if(tokens.size>1 && tokens.first() in TITLES) tokens=tokens.drop(1)
        var s=tokens.joinToString("")
        var again=true
        while(again) { again=false
            for(t in TITLES) if(s.endsWith(t) && s.length-t.length>=2) { s=s.removeSuffix(t);again=true;break } }
        return s.lowercase().ifEmpty { raw.filter { keep(it) }.lowercase() }
    }

    /** A company's key: spaces, company-form marks and punctuation out, lower case. Titles stay: "○○이사" is not "○○" (review L1). */
    fun canonCompany(name:String?):String {
        val raw=name.orEmpty().trim()
        return cleaned(raw).filter { it!=' ' }.lowercase().ifEmpty { raw.filter { keep(it) }.lowercase() }
    }

    /** The syllables of [s] taken apart into jamo, so that one wrong vowel or final consonant is one difference. */
    fun jamo(s:String):String=buildString { for(c in s) {
        if(c.code in 0xAC00..0xD7A3) { val i=c.code-0xAC00
            append((0x1100+i/588).toChar());append((0x1161+i%588/28).toChar());if(i%28>0) append((0x11A7+i%28).toChar())
        } else append(c) } }

    fun distance(a:String,b:String):Int {
        if(a==b) return 0
        var prev=IntArray(b.length+1){it}
        for(i in 1..a.length) { val cur=IntArray(b.length+1);cur[0]=i
            for(j in 1..b.length) cur[j]=minOf(prev[j]+1,cur[j-1]+1,prev[j-1]+if(a[i-1]==b[j-1]) 0 else 1)
            prev=cur }
        return prev[b.length]
    }

    /** Same key, or at least three letters, the same first letter and one jamo apart (a misheard vowel or final consonant).
     * Callers use the second form only for the same company or the same phone number. [company]: compare company keys. */
    fun similar(a:String?,b:String?,company:Boolean=false):Boolean {
        val x=if(company) canonCompany(a) else canon(a);val y=if(company) canonCompany(b) else canon(b)
        if(x.isEmpty() || y.isEmpty()) return false
        if(x==y) return true
        return x.length>=3 && y.length>=3 && x[0]==y[0] && distance(jamo(x),jamo(y))<=1
    }

    /** Letters, digits and placeholder signs only, lower case: a text to look names up in. */
    fun squash(t:String)=t.filter { keep(it) }.lowercase()
    /** Letters and digits only, lower case: titles and quotes are compared without spaces and punctuation. */
    fun bare(t:String)=t.filter { it.isLetterOrDigit() }.lowercase()
    private fun grams(s:String,n:Int):Set<String> = if(s.length<n) (if(s.isEmpty()) emptySet() else setOf(s)) else (0..s.length-n).map { s.substring(it,it+n) }.toSet()

    /** Share of the 2-letter pieces two check-item titles have in common (review B11: 0.6 and above is the same item). */
    fun jaccard2(a:String,b:String):Double {
        val x=grams(bare(a),2);val y=grams(bare(b),2)
        if(x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble()/x.union(y).size
    }
    const val SAME_TITLE=0.6

    private val DIGITS=Regex("\\d+")
    /** The numbers in a title (floor, date, site number), leading zeros ignored: "3층" and "4층" differ here although 2-letter pieces hardly do. */
    fun numbers(t:String):Set<String> = DIGITS.findAll(t).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()
    /** Same words once spaces and punctuation are ignored. */
    fun sameWords(a:String,b:String):Boolean { val x=bare(a);return x.isNotEmpty() && x==bare(b) }
    /** Near, not identical: the same numbers and at least [SAME_TITLE] of the 2-letter pieces in common. */
    fun nearTitle(a:String,b:String):Boolean = numbers(a)==numbers(b) && jaccard2(a,b)>=SAME_TITLE

    fun trigrams(s:String)=grams(s,3)
    /** Share of [quote]'s 3-letter pieces (bare text) found among the pieces [source] of the text it should come from. */
    fun coverage3(quote:String,source:Set<String>):Double {
        val q=grams(quote,3);if(q.isEmpty()) return 0.0
        return q.count { it in source }.toDouble()/q.size
    }
}
