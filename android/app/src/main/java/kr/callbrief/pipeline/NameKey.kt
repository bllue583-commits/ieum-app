package kr.callbrief.pipeline

/** One way to compare names, titles and quotes, used by the ledger and by the check of the model's answer (review B10, B11, B13).
 * [canon] is the key every name is stored and looked up by; [similar] allows one jamo of difference. */
internal object NameKey {
    /** Longest first, so "대표이사" goes before "이사". */
    val TITLES=listOf("대표이사","공장장","본부장","선생님","팀장","실장","소장","차장","과장","부장","대리","사장","대표","이사","주임","사원","상무","전무","반장","기사","감리","형님","님","씨")
    /** Honorifics only, never a role. */
    private val HONORIFICS=setOf("님","씨","형님","선생님")
    /** Words that name a place or a desk, not a person: "△△철강 사무실" is never made a person (design 3.4). */
    val NOT_PEOPLE=setOf("사무실","현장","경리","총무")
    /** Words of a check-item title that say what to do, not about what: left out when titles are compared (design 3.4). */
    val ACTIONS=listOf("보내기","받기","확인","회신","공유")
    private val CORPORATE=Regex("[(（\\[]\\s*[주유합사재]\\s*[)）\\]]|㈜|㈲|주식회사|유한회사|유한책임회사")
    private fun keep(c:Char)=c.isLetterOrDigit() || c.code in 0x25A0..0x25FF // letters, digits and the geometric signs used as placeholder names

    private fun cleaned(raw:String)=CORPORATE.replace(raw," ").map { if(keep(it)) it else ' ' }.joinToString("")

    /** A person's name without spaces, honorifics (과장, 부장, 님 ...), company-form marks and punctuation, in lower case.
     * Spaces go first and the honorific suffix is cut from the joined text, so "김 과장" and "김과장" are one key; but a
     * suffix is cut only when two or more letters stay, so a surname with a title ("김과장", "박 대리") keeps it and
     * different people never share the key of a bare surname. A title standing alone before the name ("소장 김민수") goes too. */
    fun canon(name:String?):String=canonParts(name).first

    /** [canon] and the title found on the name, if any: a role candidate ("김민수 과장님" gives ("김민수", "과장"); "김과장"
     * keeps its key and still gives "과장"). Honorifics alone (님, 씨, 형님, 선생님) are not a role. */
    fun canonParts(name:String?):Pair<String,String?> {
        val raw=name.orEmpty().trim()
        var title:String?=null
        fun note(t:String) { if(title==null && t !in HONORIFICS) title=t }
        var tokens=cleaned(raw).split(' ').filter { it.isNotEmpty() }
        if(tokens.size>1 && tokens.first() in TITLES) { note(tokens.first());tokens=tokens.drop(1) }
        var s=tokens.joinToString("")
        var again=true
        while(again) { again=false
            for(t in TITLES) if(s.endsWith(t) && s.length-t.length>=2) { note(t);s=s.removeSuffix(t);again=true;break } }
        if(title==null) TITLES.firstOrNull { s.endsWith(it) && s.length>it.length }?.let { note(it) }
        return s.lowercase().ifEmpty { raw.filter { keep(it) }.lowercase() } to title
    }

    /** "김과장", "박 기사님": a one-syllable surname and a title, nothing else. */
    fun surnameTitle(name:String?):Boolean {
        val (k,t)=canonParts(name);if(t==null) return false
        return k.length==1+t.length && k.endsWith(t) && k[0].code in 0xAC00..0xD7A3
    }
    /** The surname and title rule (design 3.4): one side is "김과장", the other a fuller name starting with the same
     * letter ("김민수 과장"). Callers use it only on the same phone number. */
    fun sameBySurname(a:String?,b:String?):Boolean {
        val x=canon(a);val y=canon(b);if(x.isEmpty() || y.isEmpty()) return false
        val sa=surnameTitle(a);val sb=surnameTitle(b)
        return when {
            sa && !sb -> y.length>=2 && y[0]==x[0]
            sb && !sa -> x.length>=2 && x[0]==y[0]
            else -> false
        }
    }
    /** A name as shown, without the titles at its end ("김민수 과장님" -> "김민수"); unchanged when fewer than two letters
     * would be left ("김과장" stays). */
    fun withoutTitle(name:String):String {
        var s=name.trim();var again=true
        while(again) { again=false
            for(t in TITLES) { val cut=s.removeSuffix(t).trimEnd()
                if(cut!=s && cut.count { keep(it) }>=2) { s=cut;again=true;break } } }
        return s
    }
    /** Not a person at all: an office, a site, a desk ("△△철강 사무실", "현장", "경리"). */
    fun notAPerson(name:String?):Boolean {
        val tokens=cleaned(name.orEmpty()).split(' ').filter { it.isNotEmpty() }
        if(tokens.isEmpty()) return false
        val joined=tokens.joinToString("")
        return tokens.any { it in NOT_PEOPLE } || NOT_PEOPLE.any { joined.endsWith(it) }
    }
    /** The same name for the call log (design 3.4): the same [canon] key, or one key inside the other ("홍길동" and
     * "홍길동 △△철강"); two letters at least. */
    fun sameName(a:String?,b:String?):Boolean {
        val x=canon(a);val y=canon(b);if(x.isEmpty() || y.isEmpty()) return false
        if(x==y) return true
        return minOf(x.length,y.length)>=2 && (x.contains(y) || y.contains(x))
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

    /** The first consonant of a syllable (the character itself for anything else). */
    private fun initial(c:Char):Char=if(c.code in 0xAC00..0xD7A3) (0x1100+(c.code-0xAC00)/588).toChar() else c
    /** Same key, or the same first consonant and one jamo apart, the first syllable's too ("정민수"/"전민수", a misheard
     * vowel or final consonant). Three letters at least; two only with [sameNumber] (design 3.4). Callers use the second
     * form only for the same company or the same phone number. [company]: compare company keys. */
    fun similar(a:String?,b:String?,company:Boolean=false,sameNumber:Boolean=false):Boolean {
        val x=if(company) canonCompany(a) else canon(a);val y=if(company) canonCompany(b) else canon(b)
        if(x.isEmpty() || y.isEmpty()) return false
        if(x==y) return true
        val min=if(sameNumber && !company) 2 else 3
        return x.length>=min && y.length>=min && initial(x[0])==initial(y[0]) && distance(jamo(x),jamo(y))<=1
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
    /** What a title is about, bare: without the [names] (people and companies), titles and action words
     * ("김민수 과장 철근 반입 일정 확인" -> "철근반입일정"). */
    fun core(title:String,names:Collection<String> =emptyList()):String {
        var t=bare(title)
        val cut=(names.map { bare(it) }+names.map { canon(it) }).plus(ACTIONS).plus(TITLES).filter { it.length>=2 }.distinct().sortedByDescending { it.length }
        for(w in cut) t=t.replace(w,"")
        return t
    }
    /** Near, not identical: the same numbers and at least [SAME_TITLE] of the 2-letter pieces in common, compared on
     * [core] so a shared person's name or "확인" does not make two jobs one (design 3.4). A core shorter than two letters
     * falls back to the whole titles. */
    fun nearTitle(a:String,b:String,names:Collection<String> =emptyList()):Boolean {
        if(numbers(a)!=numbers(b)) return false
        val x=core(a,names);val y=core(b,names)
        return if(x.length<2 || y.length<2) jaccard2(a,b)>=SAME_TITLE else jaccard2(x,y)>=SAME_TITLE
    }

    fun trigrams(s:String)=grams(s,3)
    /** Share of [quote]'s 3-letter pieces (bare text) found among the pieces [source] of the text it should come from. */
    fun coverage3(quote:String,source:Set<String>):Double {
        val q=grams(quote,3);if(q.isEmpty()) return 0.0
        return q.count { it in source }.toDouble()/q.size
    }
    /** The best share of [quote]'s 3-letter pieces found inside one stretch of [source] (both bare) as long as the quote
     * plus 20% (design E7, 3.4): two phrases far apart glued into one quote do not fit in one stretch. A shorter stretch
     * never holds more pieces, so only the longest one is slid. */
    fun windowCoverage3(quote:String,source:String):Double {
        val q=grams(quote,3);if(q.isEmpty() || source.isEmpty()) return 0.0
        val w=kotlin.math.ceil(quote.length*1.2).toInt()
        if(source.length<=w) return grams(source,3).count { it in q }.toDouble()/q.size
        val at=(0..source.length-3).map { source.substring(it,it+3) }
        val span=w-2 // pieces in one stretch
        val seen=HashMap<String,Int>();var have=0;var best=0
        for(i in at.indices) {
            val g=at[i]
            if(g in q) { val n=(seen[g] ?: 0)+1;seen[g]=n;if(n==1) have++ }
            if(i>=span) { val o=at[i-span];if(o in q) { val n=seen.getValue(o)-1;seen[o]=n;if(n==0) have-- } }
            if(have>best) best=have
        }
        return best.toDouble()/q.size
    }
}
