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
        assertEquals(setOf("summary","keyPoints","counterpartName","counterpartCompany","counterpart","business","speakerNotes","tasks","corrected"),
            (0 until schema.getJSONArray("required").length()).map { schema.getJSONArray("required").getString(it) }.toSet())
        val item=schema.getJSONObject("properties").getJSONObject("tasks").getJSONObject("items").getJSONObject("properties")
        assertEquals(setOf("op","taskId","title","due","quote","company","owner","certainty"),item.keys().asSequence().toSet())
        assertFalse(schema.toString().contains("maxItems"))
    }

    @Test fun messageStartsWithTheUserProfileAndCarriesOpenItemDetail() {
        val open=listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서 보내기","2026-10-30","open","△△철강","2026-10-27"))
        val content=ClaudeCallAnalyzer.userMessage(request(open).copy(personDetails=mapOf("김민수" to "△△철강 과장, 오인식 김민서 김민숙"),personNames=listOf("김민수","박대리"),
            sameNumberPeople=listOf("박대리(△△철강)")))
        assertTrue(content.startsWith("[사용자] 건설사 직원(부장)"))
        assertTrue(content.contains(TRANSCRIPT));assertTrue(content.contains("wf_item_1"));assertTrue(content.contains("지난 통화 요약"));assertTrue(content.contains("2026-10-29 10:00"))
        assertTrue(content.contains("\"askedOn\":\"2026-10-27\""));assertTrue(content.contains("\"company\":\"△△철강\""))
        assertTrue(content.contains("김민수(△△철강 과장, 오인식 김민서 김민숙), 박대리"));assertTrue(content.contains("[이 번호를 쓰는 사람들] 박대리(△△철강)"))
        listOf("audio","base64","wav","m4a").forEach { assertFalse(it,content.lowercase().contains(it)) }
    }

    /** Design C5: an open item carries who does it, who asked for it (by name) and the head of its evidence; numbers masked, unknown keys left out. */
    @Test fun openItemsCarryOwnerPersonAndQuoteHeadOnlyWhenKnown() {
        val quote="견적서는 010-1234-5678로 연락 주시면 내일까지 메일로 다시 보내드리겠습니다 꼭이요"
        val open=listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서 회신 확인","2026-10-30","open","△△철강","2026-10-27",owner="them",person="김민수",quote=quote),
            ClaudeCallRequest.OpenTask("wf_item_2","직접 적은 일","","open",owner=null,person=null,quote=""),
            ClaudeCallRequest.OpenTask("wf_item_3","번호만 아는 사람","","open",owner="boss",person="010-9999-8888",quote="  "))
        val m=ClaudeCallAnalyzer.userMessage(request(open))
        val json=Regex("\\[이 상대의 열린 체크할 일\\(JSON\\)\\] (\\[.*])").find(m)!!.groupValues[1]
        val items=JSONArray(json);assertEquals(3,items.length())
        val a=items.getJSONObject(0)
        assertEquals("them",a.getString("owner"));assertEquals("김민수",a.getString("person"));assertEquals("2026-10-27",a.getString("askedOn"))
        val sent=a.getString("quote")
        assertEquals(ClaudeCallAnalyzer.maskNumbers(quote).take(ClaudeCallAnalyzer.QUOTE_HEAD),sent);assertEquals(40,sent.length)
        assertTrue(sent.startsWith("견적서는 [번호]로"));assertFalse(m.contains("1234"));assertFalse(m.contains("5678"))
        listOf(items.getJSONObject(1),items.getJSONObject(2)).forEach { o -> listOf("owner","person","quote","askedOn").forEach { k -> assertFalse("${o.getString("taskId")} $k",o.has(k)) } }
        assertFalse(m.contains("9999"))
        // The open item limit stays.
        val many=(1..45).map { ClaudeCallRequest.OpenTask("wf_$it","일 $it","","open",owner="me",quote="근거 원문 $it 번째입니다") }
        val n=JSONArray(Regex("\\[이 상대의 열린 체크할 일\\(JSON\\)\\] (\\[.*])").find(ClaudeCallAnalyzer.userMessage(request(many)))!!.groupValues[1]).length()
        assertEquals(30,n)
        assertTrue(ClaudeCallAnalyzer.SYSTEM.contains("owner(그때 정한 맡는 쪽), person(요청한 통화 상대 이름), quote(그때 근거 원문의 앞 40자)"))
    }

    @Test fun instructionsAreGroupedHaveTwoExamplesAndTellWhoSpoke() {
        val sys=ClaudeCallAnalyzer.SYSTEM
        listOf("[데이터]","[출력 형식]","[근거와 인용]","[누가 말했나]","[날짜]","[숫자와 용어]","[체크할 일]","[이름]","[문자와 메일]","[개인 내용]","[예시 1","[예시 2").forEach { assertTrue(it,sys.contains(it)) }
        assertTrue(sys.contains("화자")||sys.contains("누가 말했는지 표시가 없습니다"));assertTrue(sys.contains("owner는 \"unknown\""));assertTrue(sys.contains("데이터"))
        assertFalse("promises section is gone",sys.contains("promises"))
    }

    @Test fun rulesV4CarryTheNewWordingWithTheKeptExceptions() {
        val sys=ClaudeCallAnalyzer.SYSTEM
        assertEquals(8,ClaudeCallAnalyzer.PROMPT_VERSION)
        listOf("speakerNotes","줄바꿈은 말이 끊긴 자리일 뿐 화자가 바뀐 표시가 아닙니다","(숫자 확인 필요)","\"다음 주 초\"는 다음 주 월요일","\"내일모레\"는 이틀 뒤",
            "호칭(과장, 소장, 님)은 이름에 붙이지 말고 role","[이 번호를 쓰는 사람들]","[통화 방향]","\"첨부: 견적서.pdf\"",
            "certainty \"sure\"","<transcript>","<texts>").forEach { assertTrue(it,sys.contains(it)) }
        // Exceptions decided at the v2.0 check: certainty stays sure/tentative, memos stay whole strings.
        // v5: the memos and the trade left the result; the task writes them into the ledger itself (hourly.md).
        listOf("firm","siteMemo","companyMemo","companyTrade","]]>").forEach { assertFalse(it,sys.contains(it)) }
        assertEquals(2,Regex("\"certainty\":\"sure\"").findAll(sys).count())
    }

    @Test fun blockNamesTheRulesUseAreInTheMessage() {
        val m=ClaudeCallAnalyzer.userMessage(request().copy(direction="받은 전화",sameNumberPeople=listOf("박대리(△△철강)"),personNames=listOf("김민수"),personDetails=mapOf("김민수" to "오인식 김민서")))
        listOf("[통화 방향] 받은 전화","[이 번호를 쓰는 사람들] 박대리(△△철강)","[달력]","[용어]","[공종 목록]","김민수(오인식 김민서)").forEach { assertTrue(it,m.contains(it)) }
        val logged=ClaudeCallAnalyzer.userMessage(request().copy(callLog=ClaudeCallRequest.CallLogView(null,"건 전화","2026-10-29 10:00 (목)",60_000)))
        assertTrue("direction from the call log when the record has none",logged.contains("[통화 방향] 건 전화"))
    }

    @Test fun speakerNotesAreReadButNotKept() {
        val withNotes=analysis(tasks(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요").put("owner","me").put("certainty","sure")))
            .put("speakerNotes","건 전화이고 상대가 부장님이라고 불러 보내드린다는 쪽이 사용자다.").put("siteMemo",JSONObject.NULL).put("companyMemo",JSONObject.NULL)
        val a=ClaudeCallAnalysis.parse(withNotes)
        assertEquals("me",a.tasks.single().owner);assertFalse(a.tasks.single().tentative)
        assertFalse(a.toJson().has("speakerNotes"))
        assertEquals("null notes too",1,ClaudeCallAnalysis.parse(withNotes.put("speakerNotes",JSONObject.NULL)).tasks.size)
        // An old answer (promises, string memos, no speakerNotes) still reads.
        val old=ClaudeCallAnalysis.parse(analysis(promises=JSONArray().put(JSONObject().put("text","견적서 보내기").put("owner","me").put("quote","견적서는 내일까지"))).put("siteMemo","3층 타설 준비 중"))
        assertEquals(1,old.promises.size);assertEquals("3층 타설 준비 중",old.siteMemo)
    }

    @Test fun quotesNotInTranscriptAndUnknownTaskIdsAreDropped() {
        val t=tasks(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"),task("create",null,"지어낸 일","전혀 없는 말은 어디에도 없어요"),
            task("update","wf_item_unknown","계약서 받기","계약서 초안은 다음 주 월요일에 받기로 하죠"),task("done_suggested","wf_item_1","견적서","견적서는 내일까지"))
        val result=filter.filter(parsed(t),request(listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서","","open"))))
        assertEquals(listOf("견적서 보내기","견적서"),result.tasks.map { it.title });assertEquals(listOf("create","done_suggested"),result.tasks.map { it.op })
        assertEquals("김과장",result.counterpartName);assertNull(result.counterpartCompany)
    }

    @Test fun shortQuotesProveNothingAndRepeatedOnesOnlyNextToTheTitlesSubject() {
        val said="네 알겠습니다 그렇게 할게요. 철근 반입은 금요일 오전으로 확정했습니다. 네 알겠습니다 그렇게 할게요."
        val t=tasks(task("create",null,"짧은 인용","네네"),task("create",null,"일곱 글자","철근 반입은 금요일"),task("create",null,"두 번 나온 말","네 알겠습니다 그렇게 할게요"),
            task("create",null,"한 번 나온 말","철근 반입은 금요일 오전으로 확정했습니다"))
        val r=filter.filter(parsed(t),request(transcript=said))
        // "철근 반입은 금요일" has 8 letters once in the call: it passes the length rule. A line said twice with nothing of the
        // title near it (Whisper loops, a polite reply) proves nothing (design E7, 3.4).
        assertEquals(listOf("일곱 글자","한 번 나온 말"),r.tasks.map { it.title });assertEquals(listOf(false,false),r.tasks.map { it.quoteFuzzy })
        assertEquals(0,filter.filter(parsed(tasks(task("create",null,"일곱","철근 반입은 금"))),request(transcript=said)).tasks.size)
        // The title's subject in the sentence next to one of its times: kept, as a close match.
        val near=filter.filter(parsed(tasks(task("create",null,"철근 반입 확인","네 알겠습니다 그렇게 할게요"))),request(transcript=said))
        assertTrue(near.tasks.single().quoteFuzzy)
        // Said twice in the texts only, about what the title says: also kept as close.
        val inTexts=filter.filter(parsed(tasks(task("create",null,"도면 받기","도면은 금요일까지 보내주세요"))),request(transcript="네 알겠습니다 그렇게 할게요",texts="도면은 금요일까지 보내주세요 / 도면은 금요일까지 보내주세요"))
        assertTrue(inTexts.tasks.single().quoteFuzzy)
    }

    @Test fun twoPhrasesFarApartGluedIntoOneQuoteAreDropped() {
        val said="견적서는 내일까지 메일로 보내드릴게요\n그건 그렇고 오늘 현장 날씨가 참 좋네요\n점심은 다들 드셨어요\n다음 주에 현장에서 뵙겠습니다"
        val glued=filter.filter(parsed(tasks(task("create",null,"견적서 보내기","견적서는 내일까지 다음 주에 현장에서 뵙겠습니다"))),request(transcript=said))
        assertTrue(glued.tasks.isEmpty())
        // Line ends in the transcript change nothing for a quote that runs over one.
        val over=filter.filter(parsed(tasks(task("create",null,"견적서 보내기","메일로 보내드릴게요 그건 그렇고"))),request(transcript=said))
        assertFalse(over.tasks.single().quoteFuzzy)
    }

    @Test fun aQuoteTidiedFromTheSpeechIsKeptAsFuzzyOnlyWhenNearlyAllItsPiecesAreThere() {
        val said="견적서는 내일까지 메일로 보내드릴께요 그리고 계약서는 다음 주에 받기로 했어요"
        val r=filter.filter(parsed(tasks(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"))),request(transcript=said))
        // 보내드릴게요 vs 보내드릴께요: only 13 of the quote's 15 3-letter pieces are there (0.87): below 0.9, dropped before v3.3;
        // kept now as tentative with the quote reason (DESIGN_V4 3.1-1 (6)).
        assertEquals(1,r.tasks.size);assertTrue(r.tasks.single().tentative);assertTrue(NumberGuard.QUOTE in r.tasks.single().guard)
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
