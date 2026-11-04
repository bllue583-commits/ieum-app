package kr.callbrief.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The request text and the check of the answer, on public made-up calls; no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ClaudeCallAnalyzerTest {
    companion object {
        const val TRANSCRIPT="네 김과장님. 견적서는 내일까지 메일로 보내드릴게요. 그리고 계약서 초안은 다음 주 월요일에 받기로 하죠."
        fun analysis(tasks:JSONArray=JSONArray(),promises:JSONArray=JSONArray())=JSONObject().put("summary","김과장과 견적서와 계약서 일정을 정했다.")
            .put("keyPoints",JSONArray().put("견적서 내일 발송")).put("counterpartName","김과장").put("counterpartCompany",JSONObject.NULL)
            .put("promises",promises).put("tasks",tasks)
        fun task(op:String,id:String?,title:String,quote:String,due:String?=null)=JSONObject().put("op",op).put("taskId",id ?: JSONObject.NULL)
            .put("title",title).put("due",due ?: JSONObject.NULL).put("quote",quote)
        internal fun request(open:List<ClaudeCallRequest.OpenTask> =emptyList(),transcript:String=TRANSCRIPT,texts:String="")=
            ClaudeCallRequest(transcript,"2026-10-29 10:00 (목)","김과장",125_000,open,listOf("지난 통화 요약"),recentTexts=texts)
        internal fun parsed(tasks:JSONArray=JSONArray(),promises:JSONArray=JSONArray())=ClaudeCallAnalysis.parse(analysis(tasks,promises))
    }
    private val filter=ClaudeCallAnalyzer()
    private fun tasks(vararg t:JSONObject)=JSONArray(t.toList())

    @Test fun schemaHasTasksWithOwnerAndNoSeparatePromises() {
        val schema=ClaudeCallAnalyzer.SCHEMA;assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(setOf("summary","keyPoints","counterpartName","counterpartCompany","counterpart","siteMemo","companyMemo","companyTrade","business","tasks"),
            (0 until schema.getJSONArray("required").length()).map { schema.getJSONArray("required").getString(it) }.toSet())
        val item=schema.getJSONObject("properties").getJSONObject("tasks").getJSONObject("items").getJSONObject("properties")
        assertEquals(setOf("op","taskId","title","due","quote","company","owner","from"),item.keys().asSequence().toSet())
        assertFalse(schema.toString().contains("maxItems"))
    }

    @Test fun messageStartsWithTheUserProfileAndCarriesOpenItemDetail() {
        val open=listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서 보내기","2026-10-30","open","△△철강","2026-10-27"))
        val content=ClaudeCallAnalyzer.userMessage(request(open).copy(personDetails=mapOf("김민수" to "△△철강 과장, 오인식 김민서 김민숙"),personNames=listOf("김민수","박대리"),
            sameNumberPeople=listOf("박대리(△△철강)")))
        assertTrue(content.startsWith("[사용자] 건설사 직원(부장)"))
        assertTrue(content.contains(TRANSCRIPT));assertTrue(content.contains("wf_item_1"));assertTrue(content.contains("지난 통화 요약"));assertTrue(content.contains("2026-10-29 10:00"))
        assertTrue(content.contains("\"askedOn\":\"2026-10-27\""));assertTrue(content.contains("\"company\":\"△△철강\""))
        assertTrue(content.contains("김민수(△△철강 과장, 오인식 김민서 김민숙), 박대리"));assertTrue(content.contains("[같은 번호를 쓰는 다른 사람] 박대리(△△철강)"))
        listOf("audio","base64","wav","m4a").forEach { assertFalse(it,content.lowercase().contains(it)) }
    }

    @Test fun instructionsAreGroupedHaveTwoExamplesAndTellWhoSpoke() {
        val sys=ClaudeCallAnalyzer.SYSTEM
        listOf("[사용자와 현장]","[출력 형식]","[근거와 인용]","[누가 말했나]","[체크할 일]","[이름]","[장부]","[문자와 메일]","[개인 내용]","[처음 정리]","[예시 1","[예시 2").forEach { assertTrue(it,sys.contains(it)) }
        assertTrue(sys.contains("화자")||sys.contains("누가 말했는지 표시가 없습니다"));assertTrue(sys.contains("owner를 \"unknown\""));assertTrue(sys.contains("데이터"))
        assertFalse("promises section is gone",sys.contains("promises"))
    }

    @Test fun quotesNotInTranscriptAndUnknownTaskIdsAreDropped() {
        val t=tasks(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"),task("create",null,"지어낸 일","전혀 없는 말은 어디에도 없어요"),
            task("update","wf_item_unknown","계약서 받기","계약서 초안은 다음 주 월요일에 받기로 하죠"),task("done_suggested","wf_item_1","견적서","견적서는 내일까지"))
        val result=filter.filter(parsed(t),request(listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서","","open"))))
        assertEquals(listOf("견적서 보내기","견적서"),result.tasks.map { it.title });assertEquals(listOf("create","done_suggested"),result.tasks.map { it.op })
        assertEquals("김과장",result.counterpartName);assertNull(result.counterpartCompany)
    }

    @Test fun shortQuotesProveNothingAndRepeatedOnesAreKeptAsFuzzy() {
        val said="네 알겠습니다 그렇게 할게요. 철근 반입은 금요일 오전으로 확정했습니다. 네 알겠습니다 그렇게 할게요."
        val t=tasks(task("create",null,"짧은 인용","네네"),task("create",null,"일곱 글자","철근 반입은 금요일"),task("create",null,"두 번 나온 말","네 알겠습니다 그렇게 할게요"),
            task("create",null,"한 번 나온 말","철근 반입은 금요일 오전으로 확정했습니다"))
        val r=filter.filter(parsed(t),request(transcript=said))
        // "철근 반입은 금요일" has 8 letters once in the call: it passes the length rule. The repeated line (Whisper loops) stays, but only as a close match.
        assertEquals(listOf("일곱 글자","두 번 나온 말","한 번 나온 말"),r.tasks.map { it.title });assertEquals(listOf(false,true,false),r.tasks.map { it.quoteFuzzy })
        assertEquals(0,filter.filter(parsed(tasks(task("create",null,"일곱","철근 반입은 금"))),request(transcript=said)).tasks.size)
        // Said twice in the texts only: also kept as close.
        val inTexts=filter.filter(parsed(tasks(task("create",null,"문자","도면은 금요일까지 보내주세요"))),request(transcript="네 알겠습니다 그렇게 할게요",texts="도면은 금요일까지 보내주세요 / 도면은 금요일까지 보내주세요"))
        assertTrue(inTexts.tasks.single().quoteFuzzy)
    }

    @Test fun aQuoteTidiedFromTheSpeechIsKeptAsFuzzyOnlyWhenNearlyAllItsPiecesAreThere() {
        val said="견적서는 내일까지 메일로 보내드릴께요 그리고 계약서는 다음 주에 받기로 했어요"
        val r=filter.filter(parsed(tasks(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"))),request(transcript=said))
        // 보내드릴게요 vs 보내드릴께요: only 13 of the quote's 15 3-letter pieces are there (0.87): below 0.9, dropped.
        assertEquals(0,r.tasks.size)
        val long="네 그래서 정리하면 견적서는 내일까지 메일로 보내드릴께요 그리고 나머지 도면도 같이 보내드리겠습니다 확인 부탁드립니다"
        val q="네 그래서 정리하면 견적서는 내일까지 메일로 보내드릴께요 그리고 나머지 도면도 같이 보내드리겠습니다"
        val ok=filter.filter(parsed(tasks(task("create",null,"견적서와 도면 보내기",q.replace("드릴께요","드릴게요")))),request(transcript=long))
        assertEquals(1,ok.tasks.size);assertTrue(ok.tasks.single().quoteFuzzy)
        val exact=filter.filter(parsed(tasks(task("create",null,"견적서와 도면 보내기",q))),request(transcript=long))
        assertFalse(exact.tasks.single().quoteFuzzy)
        // The flag survives the stored copy of the analysis.
        assertTrue(ClaudeCallAnalysis.parse(ok.toJson()).tasks.single().quoteFuzzy)
    }

    @Test fun aQuoteMayComeFromTheTextsAndMoreThanFiveTasksAreCut() {
        val texts="[10/28 14:00 문자]\n받은 문자: 도면은 금요일까지 보내주세요"
        val r=filter.filter(parsed(tasks(task("create",null,"도면 받기","도면은 금요일까지 보내주세요"))),request(texts=texts))
        assertEquals(1,r.tasks.size)
        val many=tasks(*(1..7).map { task("create",null,"일 $it","견적서는 내일까지 메일로 보내드릴게요") }.toTypedArray())
        assertEquals(5,filter.filter(parsed(many),request()).tasks.size)
    }

    @Test fun ownerIsKeptWhenMeOrThemElseUnknown() {
        val t=tasks(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요").put("owner","me"),task("create",null,"계약서 받기","계약서 초안은 다음 주 월요일에 받기로 하죠").put("owner","그 쪽"))
        assertEquals(listOf("me","unknown"),filter.filter(parsed(t),request()).tasks.map { it.owner })
    }

    @Test fun namesAreCheckedWithOneKeyHonorificsAndCompanyMarksIgnored() {
        val raw=parsed().copy(counterpart=ClaudeCallAnalysis.Counterpart("김민수 과장","(주)△△철강","과장",false))
        val said=request(transcript="네 김민수 과장님 안녕하세요 △△철강입니다")
        val f=filter.filter(raw,said)
        assertEquals("김민수 과장",f.counterpart!!.personName);assertEquals("(주)△△철강",f.counterpart!!.companyName)
        // A name nobody said and nobody listed is dropped.
        val g=filter.filter(raw.copy(counterpart=ClaudeCallAnalysis.Counterpart("박지훈","□□건설",null,false)),said)
        assertNull(g.counterpart)
    }
}
