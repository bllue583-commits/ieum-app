package kr.callbrief.pipeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** docs/routines/hourly.md is the routine's prompt (design C1, C2): its [분석 규칙] and [결과 형식] must be the app's
 * SYSTEM and SCHEMA, since requests no longer carry them. IEUM_WRITE_ROUTINE_DOC=1 rewrites the two blocks from the code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoutineDocTest {
    private fun doc():File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it,"docs/routines/hourly.md") }.first { it.isFile }
    private fun block(text:String,name:String,fence:String):String {
        val start=text.indexOf("<!-- $name:BEGIN -->");val end=text.indexOf("<!-- $name:END -->")
        assertTrue("$name block missing",start>=0 && end>start)
        val inner=text.substring(start,end)
        val open=inner.indexOf("```$fence\n")+fence.length+4;val close=inner.lastIndexOf("\n```")
        return inner.substring(open,close)
    }
    private fun replaceBlock(text:String,name:String,fence:String,body:String):String {
        val start=text.indexOf("<!-- $name:BEGIN -->");val end=text.indexOf("<!-- $name:END -->")
        return text.substring(0,start)+"<!-- $name:BEGIN -->\n```$fence\n"+body+"\n```\n"+text.substring(end)
    }

    @Test fun hourlyDocCarriesTheCodeRulesAndFormat() {
        val f=doc()
        if(System.getenv("IEUM_WRITE_ROUTINE_DOC")=="1")
            f.writeText(replaceBlock(replaceBlock(f.readText(),"SYSTEM","text",ClaudeCallAnalyzer.SYSTEM),"SCHEMA","json",ClaudeCallAnalyzer.SCHEMA.toString(2)))
        val text=f.readText()
        assertEquals("[분석 규칙] must be ClaudeCallAnalyzer.SYSTEM",ClaudeCallAnalyzer.SYSTEM,block(text,"SYSTEM","text"))
        assertEquals("[결과 형식] must be ClaudeCallAnalyzer.SCHEMA",ClaudeCallAnalyzer.SCHEMA.toString(),JSONObject(block(text,"SCHEMA","json")).toString())
        assertTrue("version in the doc",text.contains("지시문 버전 ${ClaudeCallAnalyzer.PROMPT_VERSION}."))
        assertTrue(text.contains("\"promptVersion\":\"${ClaudeCallAnalyzer.PROMPT_VERSION}\""))
        // The routine's steps that must not get lost when the prompt is replaced.
        listOf("\"새 기록 없음\"","ledger/edits","b- 파일을 이름순으로 최대 2개","Read 도구","스크립트로 일괄 생성하지 않는다","\"format\": 3이 있으면(새 형식) 이 문서의 [분석 규칙]을 따르고",
            "promptMismatch","rev를 1 올리고","pull --rebase","bad/","status/last-run.json","처리 건수만").forEach { assertTrue(it,text.contains(it)) }
        // The CI end-to-end job (inbox-e2e.yml, InboxSelfTest) names its file selftest-…: answered, never put into the ledger.
        assertTrue(text.contains("이름이 selftest-로 시작하는 파일"));assertTrue(text.contains("장부(현장 메모, 업체, 사람, issues"))
        // v5: the memos and the trade left the result; the task still keeps them in the ledger, and phones not yet updated still send 4.
        listOf("siteMemo","companyMemo","companyTrade").forEach { assertFalse(it,ClaudeCallAnalyzer.SCHEMA.getJSONObject("properties").has(it)) }
        listOf("- siteMemo: 현장의 지금 상황","memo는 그 업체의 지금 상태","[공종 목록] 중 하나로","\"promptVersion\": 4와 5는 폰이 업데이트되기 전의 과도기 요청",
            "옛 형식","\"system\"을 규칙으로").forEach { assertTrue(it,text.contains(it)) }
        assertTrue(text.contains(REPO))
        // v2.9 (r10-3): the routine runs every 30 minutes; the line sits above "---", so it is not part of the prompt.
        val head=text.substringBefore("\n---\n")
        // 11/5: the task was made again at minute 34, 07:34 to 22:34 (a schedule cannot run more often than hourly).
        assertTrue("routine time",head.contains("루틴 시간: 매시간 34분") && head.contains("`CRON_TZ=Asia/Seoul 34 7-22 * * *`"))
        // Same v5 round: the open items' request fields (design C5) are named next to rule 12, the rules unchanged.
        listOf("askedOn(요청한 날)","owner(그때 정한 맡는 쪽)","person(요청한 통화 상대 이름)","quote(그때 근거 원문의 앞 40자)").forEach { assertTrue(it,block(text,"SYSTEM","text").contains(it)) }
        // v6: the ledger gets a people entry for every counterpartId (the repository ledger had rev 3 with no people and no companies).
        val people="- 사람: 요청에 counterpartId가 있고 business가 true이면, 장부 people에 그 id가 없을 때 {\"id\": counterpartId, \"name\": 결과 counterpart.personName(null이면 \"\"), "+
            "\"company\": counterpart.companyName(직원이면 \"\"), \"role\": counterpart.role, \"isStaff\": counterpart.isStaff, \"aliases\": [], \"locked\": []}를 더한다."
        assertTrue("rule 4: people",text.contains(people))
        listOf("있으면 locked에 없는 칸만 결과 counterpart로 고치고","aliases에 들린 표기를 더한다","결과 analysis의 counterpart 이름은 장부의 name과 같게 쓴다",
            "- 업체: counterpart.companyName과 tasks의 company 중 장부 companies에 없는 이름은 {\"name\", \"trade\": \"\", \"memo\": \"\"}로 더한다",
            "같은 업체의 다른 표기로 보이면 더하지 말고 원래 표기를 쓴다").forEach { assertTrue(it,text.contains(it)) }
        // The ledger entry is built from the result's own keys: they must be in the result format.
        val props=ClaudeCallAnalyzer.SCHEMA.getJSONObject("properties")
        val cp=props.getJSONObject("counterpart").getJSONObject("properties")
        listOf("personName","companyName","role","isStaff").forEach { assertTrue(it,cp.has(it));assertTrue(it,people.contains("counterpart.$it")) }
        listOf("business","tasks").forEach { assertTrue(it,props.has(it)) }
        assertTrue(props.getJSONObject("tasks").getJSONObject("items").getJSONObject("properties").has("company"))
        // v6 rule wording (data review 4.3), in the code's rules and so in the doc.
        val rules=block(text,"SYSTEM","text")
        listOf("(5) 상대가 '부장님', '김 부장님'처럼 [사용자]의 직책으로 부르는 문장은 상대가 한 말",
            "(6) 받은 전화의 첫 인사('네, ○○현장입니다')는 사용자, 건 전화의 첫 인사('네, △△철강입니다')는 상대",
            "'내일'은 통화일 다음 날","'모레'는 이틀 뒤","'글피'는 사흘 뒤","'이번 주 안'은 그 주 금요일","'주말'은 그 주 토요일","'다음 달 초'는 다음 달 첫 평일",
            "'이번 주/다음 주'가 없으면 앞으로 처음 오는 그 요일","[달력]이 없거나 통화 시각이 '시각 미상'이면 due는 모두 null",
            "'확인해 보고 연락드릴게요'","update는 대상이 같고 '날짜', '수량', '방법' 중 하나가 바뀌었을 때만",
            "성과 직책만 들리고","personName은 '김'이 아니라 null로 두고 role만 '과장'으로",
            "명절 인사, 경조사 안내는 업무 관계이므로 business true").forEach { assertTrue(it,rules.contains(it)) }
        // The time label rule 8 names is the one the message uses.
        assertEquals("시각 미상",CallRecordStore.kstTime(null))
        // Rule 10 (no names in titles) waits for v2.7, when the check item row shows the person.
        assertTrue(rules.contains("\"김민수 과장 견적서 회신 확인\"처럼"))
        // The routine never logs a transcript.
        assertTrue(text.contains("\"loggedTranscript\":false"));assertTrue(text.contains("`loggedTranscript`: 자막이나 문자 원문을 출력하거나 로그에 남기지 않았으므로 항상 `false`"))
    }

    @Test fun tidyDocEndsWithTheSelfReport() {
        val text=File(doc().parentFile,"tidy.md").readText()
        assertTrue(text.contains("\"routine\":\"tidy\""));assertTrue(text.contains("status/last-run.json"))
        listOf("firstPassDone","tidiedOnce","mergedInto","tidy/proposal.json","\"정리할 것 없음","중복 방지","\"이미 정리됨\"").forEach { assertTrue(it,text.contains(it)) }
        // v2.6: proposals hand back the state's rev (the phone skips an item changed since), merges only mark mergedInto.
        listOf("locked, rev}","mergeCandidates[]","{\"op\":\"rewrite\",\"id\",\"rev\",","{\"op\":\"merge\",\"id\",\"rev\",","{\"op\":\"done_suggested\",\"id\",\"rev\"}",
            "각 제안 항목에는 state의 rev를 그대로 적는다","create에는 rev가 없다","state에 mergeCandidates가 있으면",
            "없어지는 쪽 업체와 사람 항목은 지우지 말고 mergedInto만 적어 둔다").forEach { assertTrue(it,text.contains(it)) }
        assertTrue(text.contains(REPO))
        // v3.1 (r12-3, DESIGN_V4 2.3-1): the first pass no longer stops the tidy; until it ends only the last 14 days, no tidiedOnce.
        assertFalse("firstPassDone no longer stops step 1",text.contains("firstPassDone이 false이거나"))
        listOf("firstPassDone이 false여도 멈추지 않는다","state의 records(최근 14일)를 날짜순으로 전부 읽고","이때는 7번의 tidiedOnce를 쓰지 않는다",
            "firstPassDone이 true이고 장부의 tidiedOnce가 true가 아니면 10월 1일부터","firstPassDone이 false인 날(3번)에는 tidiedOnce를 쓰지 않고").forEach { assertTrue(it,text.contains(it)) }
        // 2.3-3: the hourly self-report carries the cron and the real start and end.
        assertTrue(doc().readText().contains("\"cron\":\"CRON_TZ=Asia/Seoul 34 7-22 * * *\""))
    }

    private companion object {
        /** Both routines find the inbox repository the same way. */
        const val REPO="저장소는 bllue583-commits/ieum-inbox 이다. 작업 폴더에 없으면 ls /home/claude 로 찾고, 그래도 없으면 gh repo clone bllue583-commits/ieum-inbox 로 받는다."
    }
}
