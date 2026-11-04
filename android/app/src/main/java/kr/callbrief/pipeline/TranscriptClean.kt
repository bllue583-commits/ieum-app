package kr.callbrief.pipeline

/** A9: hallucination clean-up applied to the finished segments right before the transcript is saved.
 * (1) a segment that is exactly a known hallucinated phrase is removed; (2) the same sentence three or more times in a row is kept once.
 * The count of removed segments and sentences goes to the TRANSCRIPT_CLEANED setting. */
internal object TranscriptClean {
    data class Result(val segments:List<CallTranscript.Segment>,val removed:Int)

    /** Whisper's usual silence/noise phrases for Korean audio. Fixed by a test: change both together. */
    val PHRASES:List<String> = listOf(
        "시청해 주셔서 감사합니다","다음 시간에 뵙겠습니다","영상 시청해 주셔서 감사합니다","끝까지 시청해 주셔서 감사합니다","오늘도 시청해 주셔서 감사합니다",
        "구독과 좋아요","구독과 좋아요 부탁드립니다","좋아요와 구독 부탁드립니다","구독 좋아요 알림 설정까지","다음 영상에서 만나요",
        "다음 시간에 만나요","자막 제공","자막 제작","한글 자막","번역 자막",
        "이 영상은 유료 광고를 포함하고 있습니다","MBC 뉴스","KBS 뉴스","SBS 뉴스","YTN 뉴스",
    )
    private val known:Set<String> = PHRASES.map(::key).toSet()
    private const val MIN_REPEAT=3

    /** Letters and digits only, lower case: "시청해 주셔서 감사합니다." and "시청해주셔서  감사합니다" are the same. */
    private fun key(text:String)=buildString { text.forEach { if(it.isLetterOrDigit())append(it.lowercaseChar()) } }
    private fun sentences(text:String):List<String> = text.split(Regex("(?<=[.!?。！？])\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    fun clean(segments:List<CallTranscript.Segment>):Result {
        var removed=0
        val kept=ArrayList<CallTranscript.Segment>(segments.size)
        for(s in segments) {
            if(key(s.text) in known){removed++;continue}
            kept+=s
        }
        // Same segment text three or more times in a row (also across the pieces of a long call): once.
        val out=ArrayList<CallTranscript.Segment>(kept.size)
        var i=0
        while(i<kept.size) {
            val k=key(kept[i].text)
            var j=i+1
            while(j<kept.size&&k.isNotEmpty()&&key(kept[j].text)==k)j++
            val run=j-i
            if(run>=MIN_REPEAT){out+=kept[i];removed+=run-1} else for(x in i until j)out+=kept[x]
            i=j
        }
        // Sentences repeated inside one segment ("네. 네. 네. 네.") are counted too.
        var inside=0
        val final=out.map { s -> val c=collapseSentences(s.text);if(c.second>0){inside+=c.second;s.copy(text=c.first)} else s }
        return Result(final,removed+inside)
    }
    /** Text with runs of three or more identical sentences cut to one, and the number of sentences cut. */
    private fun collapseSentences(text:String):Pair<String,Int> {
        val parts=sentences(text)
        if(parts.size<MIN_REPEAT)return text to 0
        val out=ArrayList<String>();var cut=0;var i=0
        while(i<parts.size) {
            val k=key(parts[i]);var j=i+1
            while(j<parts.size&&k.isNotEmpty()&&key(parts[j])==k)j++
            if(j-i>=MIN_REPEAT){out+=parts[i];cut+=j-i-1} else for(x in i until j)out+=parts[x]
            i=j
        }
        return if(cut==0) text to 0 else out.joinToString(" ") to cut
    }
}
