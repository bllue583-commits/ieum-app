package kr.callbrief.pipeline

import org.json.JSONArray
import org.json.JSONObject

/** The number guard of v3.3 (DESIGN_V4 3.1-1): numbers, dates and specs in a check item's title and date are compared with
 * what was really said around its quote. An item whose numbers are not there is kept, marked tentative with a reason
 * ([NUMBER], [DATE], [ASR], [QUOTE], [CONFUSABLE]); the screens show it as "확인 필요". Pure functions, no Android. */
internal object NumberGuard {
    const val NUMBER="number";const val DATE="date";const val ASR="asr";const val QUOTE="quote";const val CONFUSABLE="confusable"
    val REASONS=listOf(NUMBER,DATE,ASR,QUOTE,CONFUSABLE)
    /** What is read around the lines a quote was found in: 30 seconds either side (segment times), else this many lines. */
    const val WINDOW_MS=30_000L
    const val WINDOW_LINES=6
    /** A quote whose 3-letter pieces are found at this share (and below [ClaudeCallAnalyzer.FUZZY_COVERAGE]) is kept as [QUOTE]. */
    const val QUOTE_LOW=0.7
    /** A date further than this after the call is kept as [DATE]. */
    const val LATE_DAYS=60L
    /** Same company, asked within this many days, numbers a confusable pair apart: the later item is [CONFUSABLE]. */
    const val CONFUSABLE_DAYS=7L
    /** Numbers the recogniser mixes up ("십삼" and "삼십"). */
    val CONFUSABLE_PAIRS:Set<Pair<String,String>> =(3..9).map { "1$it" to "${it}0" }.toSet()
    /** Segments kept per record at most (an hour and a half of short segments). */
    const val MAX_SEGMENTS=3000

    // ---- segment times kept with a record --------------------------------------------------------------------
    /** The time of each transcript line (a line is one recognised segment) and the uncertain numbers of some lines.
     * Stored in call_records.segments_json as {"v":1,"s":[start of each line, seconds after the start of the one before],
     * "d":[length of each line in seconds],"u":{"line":["30"]}}: a few bytes a line, no words (the words are the transcript). */
    data class Times(val startsMs:List<Long>,val endsMs:List<Long>,val uncertain:Map<Int,List<String>> =emptyMap()) {
        val size get()=startsMs.size
        fun toJson():String {
            val s=JSONArray();val d=JSONArray();var prev=0L
            for(i in startsMs.indices) { val st=startsMs[i]/1000;s.put(st-prev);prev=st;d.put(maxOf(0L,(endsMs[i]+999)/1000-st)) }
            val o=JSONObject().put("v",1).put("s",s).put("d",d)
            if(uncertain.isNotEmpty()) o.put("u",JSONObject().apply { uncertain.forEach { (k,v) -> put(k.toString(),JSONArray(v.map { it.take(12) }.take(6))) } })
            return o.toString()
        }
        companion object {
            /** From a transcript's segments, when its text is their non-empty lines (as the transcription stores it); else null. */
            fun of(segments:List<CallTranscript.Segment>,text:String):Times? {
                val segs=segments.filter { it.text.trim().isNotEmpty() }
                if(segs.isEmpty() || segs.size>MAX_SEGMENTS) return null
                if(segs.size!=text.split("\n").size) return null
                val u=segs.withIndex().filter { it.value.uncertainNumbers.isNotEmpty() }.associate { it.index to it.value.uncertainNumbers }
                return Times(segs.map { maxOf(0L,it.startMs) },segs.map { maxOf(it.startMs,it.endMs) },u)
            }
            fun parse(json:String?):Times? = runCatching {
                val o=JSONObject(json?.takeIf { it.startsWith("{") } ?: return null)
                val s=o.getJSONArray("s");val d=o.getJSONArray("d")
                if(s.length()!=d.length()) return null
                var at=0L;val starts=ArrayList<Long>();val ends=ArrayList<Long>()
                for(i in 0 until s.length()) { at+=s.getLong(i);starts+=at*1000;ends+=(at+d.getLong(i))*1000 }
                val u=o.optJSONObject("u")?.let { m -> m.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { i -> i to m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } } }.toMap() }.orEmpty()
                Times(starts,ends,u)
            }.getOrNull()
        }
    }

    // ---- normalising -----------------------------------------------------------------------------------------
    private val SINO=mapOf('일' to 1,'이' to 2,'삼' to 3,'사' to 4,'오' to 5,'육' to 6,'륙' to 6,'칠' to 7,'팔' to 8,'구' to 9,'영' to 0,'공' to 0)
    private val MULT=mapOf('십' to 10L,'백' to 100L,'천' to 1000L)
    private val BIG=mapOf('만' to 10_000L,'억' to 100_000_000L)
    /** Units and counters a number may carry, longest first (after [synonyms]). */
    val UNITS=listOf("개월","세대","박스","트럭","군데","가지","시간","루베","미터","층","개","대","일","월","시","분","명","장","톤","회","차","번","원","주","동","호","평",
        "곳","통","달","살","mm","kg","cm","km","m","%").sortedByDescending { it.length }
    private val UNIT_AFTER=Regex("^\\s?(?:"+UNITS.joinToString("|") { Regex.escape(it) }+")")
    /** A Sino digit with a counter that is a common word, not a number ("이번 주", "사장", "구분"). */
    private val NOT_NUMBERS=listOf("이번","이분","이동","이대","일대","일시","일단","일반","일부","일정","사장","사대","오시","구분","구장","이장","공장","공사","영업",
        "오일","이주","사주","오분","육개장","이호","일호","천천","천장","십장","백호","이만","만나","만들","만약","만큼","만일")
    private val NATIVE_TENS=mapOf("열" to 10,"스물" to 20,"스무" to 20,"서른" to 30,"마흔" to 40,"쉰" to 50)
    private val NATIVE_ONES=mapOf("하나" to 1,"한" to 1,"둘" to 2,"두" to 2,"셋" to 3,"세" to 3,"석" to 3,"넷" to 4,"네" to 4,"넉" to 4,"다섯" to 5,"여섯" to 6,"일곱" to 7,"여덟" to 8,"아홉" to 9)
    private val COUNTERS=listOf("개","대","명","장","번","시","곳","군데","가지","통","달","박스","트럭","차례","층","사람","살")
    private val NATIVE=Regex("(?<![가-힣0-9])(?!세대)(열|스물|스무|서른|마흔|쉰)?(하나|한|둘|두|셋|세|석|넷|네|넉|다섯|여섯|일곱|여덟|아홉)?\\s?(?=(?:"+COUNTERS.joinToString("|")+"))")
    private val SYNONYMS=listOf(Regex("밀리미터|미리|밀리") to "mm",Regex("킬로그램|키로|킬로") to "kg",Regex("퍼센트|프로") to "%",Regex("센티미터|센치|센티") to "cm",Regex("메다|메타") to "m",
        Regex("미터") to "m",Regex("디(?=\\s?[0-9일이삼사오육칠팔구십])") to "d",Regex("에이치(?=\\s?[0-9일이삼사오육칠팔구십])") to "h")

    /** [text] with numbers written in digits: Korean numerals ("삼십", "오백만", "세 개") become digits, commas inside numbers
     * go, units are unified (미리 = mm, 키로 = kg, 프로 = %, 디 = D, 에이치 = H), lower case. Spaces stay (see [squash]). */
    fun normalize(text:String):String {
        var t=text.lowercase().replace(Regex("(?<=\\d),(?=\\d{3})"),"")
        for((re,to) in SYNONYMS) t=re.replace(t,to)
        t=NATIVE.replace(t) { m -> val tens=m.groupValues[1];val ones=m.groupValues[2]
            if(tens.isEmpty() && ones.isEmpty()) m.value else ((NATIVE_TENS[tens] ?: 0)+(NATIVE_ONES[ones] ?: 0)).toString() }
        return sino(t)
    }
    /** Without spaces, hyphens and brackets: what the comparisons read. */
    fun squash(text:String)=normalize(text).replace(Regex("[\\s\\-()\\[\\]~·,]"),"")

    private fun isNumberChar(c:Char)=c.isDigit() || c in SINO || c in MULT || c in BIG
    /** Sino-Korean numbers (and digits joined to 만/천/억) to digits. A single digit word is a number only with a counter after it. */
    private fun sino(t:String):String {
        val out=StringBuilder();var i=0
        while(i<t.length) {
            val c=t[i]
            if(!isNumberChar(c) || c.isDigit() && (i+1>=t.length || !(t[i+1] in MULT || t[i+1] in BIG || t[i+1].isDigit()))) { out.append(c);i++;continue }
            val parsed=parse(t,i)
            if(parsed==null) { out.append(c);i++;continue }
            val (value,end,korean,hasMult)=parsed
            if(!korean) { out.append(t,i,end);i=end;continue }
            val wordStart=i==0 || !(t[i-1] in '가'..'힣')
            val rest=t.substring(end)
            val unit=UNIT_AFTER.find(rest)!=null
            val word=t.substring(i,minOf(t.length,end+3))
            val ok=when {
                NOT_NUMBERS.any { word.startsWith(it) } -> false
                hasMult && end-i>=2 -> true
                hasMult -> unit // "십 층", not "백화점"
                else -> wordStart && unit && c !in setOf('영','공') // "삼 층", "오 톤"
            }
            if(ok) { out.append(value);i=end } else { out.append(c);i++ }
        }
        return out.toString()
    }
    private data class Parsed(val value:Long,val end:Int,val korean:Boolean,val hasMult:Boolean)
    /** The longest valid number starting at [from]: digits, a Sino digit before a multiplier, multipliers, 만/억 groups. */
    private fun parse(t:String,from:Int):Parsed? {
        var total=0L;var section=0L;var num=-1L;var i=from;var korean=false;var mult=false;var lastWasDigit=false;var arabic=false
        var lastEnd=from;var lastValue=-1L;var lastMultEnd=-1
        while(i<t.length) {
            val c=t[i]
            when {
                c.isDigit() -> { if(lastWasDigit && !arabic) break
                    num=(if(arabic && num>=0) num*10 else 0L)+(c-'0');arabic=true;lastWasDigit=true }
                c in SINO -> { if(lastWasDigit) break
                    num=SINO[c]!!.toLong();korean=true;lastWasDigit=true;arabic=false }
                c in MULT -> { section+=(if(num<0) 1 else num)*MULT[c]!!;num=-1;korean=true;mult=true;lastWasDigit=false;arabic=false;lastMultEnd=i+1 }
                c in BIG -> { val s=section+maxOf(num,0);total+=(if(s==0L) 1 else s)*BIG[c]!!;section=0;num=-1;korean=true;mult=true;lastWasDigit=false;arabic=false;lastMultEnd=i+1 }
                else -> break
            }
            i++;lastEnd=i;lastValue=total+section+maxOf(num,0)
        }
        if(lastEnd==from || lastValue<0) return null
        // "삼십일": 30 and the unit 일 (a 31st is said "삼십일 일"); "십일월" stays 11.
        if(korean && mult && lastEnd-1==lastMultEnd && t[lastEnd-1]=='일' && (lastEnd>=t.length || t[lastEnd]!='월') && !(lastEnd<t.length && t[lastEnd]=='일'))
            return Parsed(lastValue-1,lastEnd-1,true,true)
        return Parsed(lastValue,lastEnd,korean,mult)
    }

    // ---- tokens ------------------------------------------------------------------------------------------------
    /** A number in a title: [prefix] "d" or "h" (rebar and steel specs), the [value] as written in digits, the [unit] after it. */
    data class Token(val prefix:String,val value:String,val unit:String) { override fun toString()=prefix.uppercase()+value+unit }
    private val TOKEN=Regex("(?<![a-z0-9.])([dh]?)([0-9]+(?:\\.[0-9]+)?)")
    /** The numbers of a (squashed, normalised) text with their units. */
    fun tokens(text:String):List<Token> { val s=squash(text)
        return TOKEN.findAll(s).map { m -> val end=m.range.last+1
            Token(m.groupValues[1],m.groupValues[2].trimStart('0').ifEmpty { "0" }.let { if(it.startsWith(".")) "0$it" else it },UNITS.firstOrNull { s.startsWith(it,end) }.orEmpty()) }.toList() }
    /** [token] is said in [haystack] (squashed): the same value, not part of a longer number, with its unit when it has one. */
    fun said(token:Token,haystack:String):Boolean {
        val re=Regex("(?<![0-9.])0*"+Regex.escape(token.value)+"(?![.]?[0-9])"+(if(token.unit.isEmpty()) "" else Regex.escape(token.unit)))
        return re.containsMatchIn(haystack)
    }
    private val DATE_WORDS=Regex("(월|화|수|목|금|토|일)요일|[월화수목금토일]욜|내일|낼|모레|글피|오늘|금일|명일|당일|이번주|다음주|담주|다다음주|이번달|다음달|담달|주말|주초|주중|월말|월초|말일|연말|초순|중순|하순|퇴근|오전|오후|[0-9]+일|[0-9]+월|[0-9]+주|[0-9]+/[0-9]+")
    /** A day is named in [text] (squashed): 요일, 내일, 모레, 글피, 이번 주, 다음 주, n일, n월. */
    fun hasDateWords(text:String)=DATE_WORDS.containsMatchIn(text)

    /** The memo of a [CONFUSABLE] item, or null when [earlier] and [later] are not the same item with numbers a confusable pair apart. */
    fun confusable(earlier:String,later:String):String? {
        val a=tokens(earlier);val b=tokens(later)
        if(a.isEmpty() || a.size!=b.size) return null
        fun shape(t:String)=TOKEN.replace(squash(t),"#")
        if(shape(earlier)!=shape(later)) return null
        val diff=a.indices.filter { a[it].value!=b[it].value }
        if(diff.isEmpty()) return null
        if(!diff.all { i -> (a[i].value to b[i].value).let { it in CONFUSABLE_PAIRS || it.second to it.first in CONFUSABLE_PAIRS } }) return null
        val i=diff.first()
        return "앞의 일과 숫자가 달라요(${a[i]}/${b[i]})"
    }

    // ---- the guard of one item ----------------------------------------------------------------------------------
    /** Where a quote was found: lines [first]..[last] of the call transcript ([inCall]) or of the texts. */
    data class Place(val inCall:Boolean,val first:Int,val last:Int)
    /** The lines of [lines] holding [quote] (bare, letters and digits), exactly or, failing that, the stretch that holds most of its 3-letter pieces. */
    fun locate(quote:String,lines:List<String>):IntRange? {
        val b=NameKey.bare(quote);if(b.isEmpty() || lines.isEmpty()) return null
        val bare=lines.map { NameKey.bare(it) }
        val ends=IntArray(bare.size);var at=0
        bare.forEachIndexed { i,s -> at+=s.length;ends[i]=at }
        fun lineAt(pos:Int)=ends.indexOfFirst { pos<it }.let { if(it<0) bare.size-1 else it }
        val joined=bare.joinToString("")
        val p=joined.indexOf(b)
        if(p>=0) return lineAt(p)..lineAt(p+b.length-1)
        var best=0.0;var range:IntRange?=null
        for(i in bare.indices) {
            var j=i;var len=bare[i].length
            while(len<b.length*1.2 && j+1<bare.size) { j++;len+=bare[j].length }
            val cov=NameKey.windowCoverage3(b,bare.subList(i,j+1).joinToString(""))
            if(cov>best) { best=cov;range=i..j }
        }
        return range?.takeIf { best>=QUOTE_LOW }
    }
    /** The lines around [found]: 30 seconds either side when [times] fits the lines, else [WINDOW_LINES] either side. */
    fun window(found:IntRange,size:Int,times:Times?):IntRange {
        if(times!=null && times.size==size) {
            val from=times.startsMs[found.first]-WINDOW_MS;val to=times.endsMs[found.last]+WINDOW_MS
            val inside=(0 until size).filter { times.endsMs[it]>=from && times.startsMs[it]<=to }
            if(inside.isNotEmpty()) return minOf(inside.first(),found.first)..maxOf(inside.last(),found.last)
        }
        return maxOf(0,found.first-WINDOW_LINES)..minOf(size-1,found.last+WINDOW_LINES)
    }

    /** What the guard found for one check item. [due]: the date kept (null when it was before the call). */
    data class Verdict(val reasons:List<String>,val due:String?)
    /** Checks [title] and [due] of one item against [quote] and the lines around it ([callLines] with [times], else [textLines]).
     * [callDay]: the day of the call, when known. [lowQuote]: the quote matched only at [QUOTE_LOW]..0.9. */
    fun check(title:String,due:String?,quote:String,callLines:List<String>,textLines:List<String>,times:Times?,callDay:java.time.LocalDate?,lowQuote:Boolean=false):Verdict {
        val reasons=mutableListOf<String>()
        if(lowQuote) reasons+=QUOTE
        val inCall=locate(quote,callLines)
        val (lines,found,t)=if(inCall!=null) Triple(callLines,inCall,times) else Triple(textLines,locate(quote,textLines),null)
        val around=found?.let { window(it,lines.size,t) }
        val near=squash(quote)+"|"+(around?.let { r -> lines.subList(r.first,r.last+1).joinToString("\n") }?.let(::squash) ?: "")
        val tokens=tokens(title)
        if(tokens.any { !said(it,near) }) reasons+=NUMBER
        // (5) The recogniser was unsure of a number the title carries (token probability under 0.5, filled in by the transcription).
        if(inCall!=null && t!=null && t.size==lines.size && around!=null) {
            val unsure=(around).flatMap { t.uncertain[it].orEmpty() }.flatMap { tokens(it) }.map { it.value }.toSet()
            if(tokens.any { it.value in unsure }) reasons+=ASR
        }
        var kept=due
        if(due!=null) {
            val day=runCatching { java.time.LocalDate.parse(due) }.getOrNull()
            when {
                day!=null && callDay!=null && day.isBefore(callDay) -> kept=null
                !hasDateWords(near) -> reasons+=DATE
                day!=null && callDay!=null && day.isAfter(callDay.plusDays(LATE_DAYS)) -> reasons+=DATE
            }
        }
        return Verdict(reasons.distinct(),kept)
    }
}
