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
        if(System.getenv("IEUM_WRITE_ROUTINE_DOC")=="1" && codeAtDocVersion)
            f.writeText(replaceBlock(replaceBlock(f.readText(),"SYSTEM","text",ClaudeCallAnalyzer.SYSTEM),"SCHEMA","json",ClaudeCallAnalyzer.SCHEMA.toString(2)))
        val text=f.readText()
        if(codeAtDocVersion) {
            assertEquals("[분석 규칙] must be ClaudeCallAnalyzer.SYSTEM",ClaudeCallAnalyzer.SYSTEM,block(text,"SYSTEM","text"))
            assertEquals("[결과 형식] must be ClaudeCallAnalyzer.SCHEMA",ClaudeCallAnalyzer.SCHEMA.toString(),JSONObject(block(text,"SCHEMA","json")).toString())
        } else {
            // v7 hand-over (r13-2 wrote the doc; ClaudeCallAnalyzer is r13-1's): the doc's format is the code's plus "corrected".
            // The orchestrator copies [분석 규칙] and [결과 형식] into SYSTEM and SCHEMA with PROMPT_VERSION=7; then the strict check above holds.
            assertEquals("the code is one version behind the doc",DOC_VERSION-1,ClaudeCallAnalyzer.PROMPT_VERSION)
            val docSchema=JSONObject(block(text,"SCHEMA","json"))
            docSchema.getJSONObject("properties").remove("corrected")
            val required=docSchema.getJSONArray("required")
            docSchema.put("required",org.json.JSONArray((0 until required.length()).map { required.getString(it) }.filter { it!="corrected" }))
            assertEquals("[결과 형식] is the code's plus corrected",ClaudeCallAnalyzer.SCHEMA.toString(),docSchema.toString())
        }
        assertTrue("version in the doc",text.contains("지시문 버전 $DOC_VERSION."))
        assertTrue(text.contains("\"promptVersion\":\"$DOC_VERSION\""))
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
        // v7 (DESIGN_V4 3.2-3): no names or titles in check item titles (the screen shows the person); numbers that may be
        // misheard stay as heard and tentative; names misheard are written as in [이름 목록] and listed in corrected, never a number.
        assertFalse(rules.contains("김민수 과장 견적서 회신 확인"));assertFalse(rules.contains("김민수 과장 수정 견적서"))
        listOf("title에 사람 이름이나 호칭(김민수 과장, 소장님)을 넣지 마세요","들린 대로 적되 그 일의 certainty를 \"tentative\"로",
            "숫자를 짐작해 바꾸지 마세요","corrected에 {\"heard\": 자막에 들린 표기, \"written\": 고쳐 쓴 표기}",
            "숫자, 날짜, 시각, 금액, 규격(철근 규격, 강도, 수량)은 비슷하게 들려도 절대 고치지 말고 corrected에도 넣지 마세요",
            "quote는 고치지 말고 자막 그대로","메일의 \"보낸 사람\"에는 이름만 있고 주소는 없습니다").forEach { assertTrue(it,rules.contains(it)) }
        val corrected=JSONObject(block(text,"SCHEMA","json")).getJSONObject("properties").getJSONObject("corrected")
        assertEquals(listOf("heard","written"),corrected.getJSONObject("items").getJSONObject("properties").keys().asSequence().toList())
        assertTrue(corrected.getString("description").contains("숫자, 날짜, 규격은 고치지 않음"))
        assertTrue(text.contains("\"promptVersion\": 6도 폰이 업데이트되기 전의 과도기 요청"))
        // The routine never logs a transcript.
        assertTrue(text.contains("\"loggedTranscript\":false"));assertTrue(text.contains("`loggedTranscript`: 자막이나 문자 원문을 출력하거나 로그에 남기지 않았으므로 항상 `false`"))
        // v8 (DESIGN_V4 4.3-1, 4.3-2, 4.3-7): the failure path, 20 items a run, its own self-report with repoOk and pushed, the
        // watchdog as the last step through gh api, the company rename edit; request version 7 is a hand-over one.
        val prompt=text.substringAfter("\n---\n")
        listOf("\"확인 필요: 저장소 접근\"","`git pull`이 실패하면","결과를 만드는 항목은 20건까지","status/last-run-hourly.json","\"repoOk\":true","\"pushed\":true",
            "python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox","\"확인 필요: 감시 이슈 n건\"","renameCompany({\"from\",\"to\"}",
            "\"promptVersion\": 7도 과도기 요청","과도기 버전 4, 5, 6, 7은 제외").forEach { assertTrue(it,prompt.contains(it)) }
        assertFalse("hourly no longer writes the shared file",prompt.contains("`status/last-run.json`을 새로 써서"))
        // v3.3's rules for the merge edits stay.
        listOf("mergePeople({\"from\",\"into\"}","unmergePeople({\"from\",\"into\"}","keepApart").forEach { assertTrue(it,prompt.contains(it)) }
    }

    /** Tidy v6 on (DESIGN_V4 4.3): the tidy routine's own self-report, the failure path, the number rule for rewrites and the watchdog. */
    @Test fun tidyKeepsItsOwnReportAndTheNumbers() {
        val text=File(doc().parentFile,"tidy.md").readText()
        val prompt=text.substringAfter("\n---\n")
        listOf("status/last-run-tidy.json","\"repoOk\":true","\"pushed\":true","\"확인 필요: 저장소 접근\"","renameCompany({\"from\",\"to\"}",
            "원래 제목의 숫자(금액, 수량, 규격, 회차, 날짜)를 하나도 바꾸거나 빼거나 더하지 않고","폰은 제목의 숫자가 달라진 다시 쓰기를 거부한다",
            "python3 tools/watchdog.py --root . --apply bllue583-commits/ieum-inbox",
            "mergePeople({\"from\",\"into\"}","unmergePeople({\"from\",\"into\"}","keepApart","한 쌍은 [남겨 둔 체크할 일 id, 비슷한 일을 냈다가 빠진 기록 key]이다").forEach { assertTrue(it,prompt.contains(it)) }
        assertFalse("tidy no longer writes the shared file",prompt.contains("`status/last-run.json`을 새로 써서"))
        assertTrue(text.contains("지시문 버전 $TIDY_VERSION\n"))
    }

    /** v3.5 (DESIGN_V5 1절, 2.2절, r15-2): prompt 7 writes the morning report after step 9 (section 9-1), checks it with
     * tools/report_check.py, keeps 30 days, reports reportWritten, and starts at 04:46 so the phone can send it at 05:30. */
    @Test fun tidyV7WritesTheMorningReport() {
        val text=File(doc().parentFile,"tidy.md").readText()
        val head=text.substringBefore("\n---\n");val prompt=text.substringAfter("\n---\n")
        assertTrue(head.contains("지시문 버전 7\n"))
        assertTrue("04:46",head.contains("`CRON_TZ=Asia/Seoul 46 4 * * *`") && prompt.contains("04:46"))
        assertFalse("the old time is gone from the prompt",prompt.contains("05:16"))
        val nine=prompt.indexOf("\n9. 기억 파일을 쓴다.");val nineOne=prompt.indexOf("\n9-1. 아침 보고서.");val ten=prompt.indexOf("\n10. 장부의 rev를 1 올리고")
        assertTrue("9-1 sits between 9 and 10",nine in 0 until nineOne && nineOne<ten)
        val section=prompt.substring(nineOne,ten)
        // The format of DESIGN_V5 1절: every key the phone reads (r15-1 KakaoReport.compose).
        val format=JSONObject(section.substringAfter("```json\n").substringBefore("\n```"))
        assertEquals(listOf("day","generatedMs","tidyRev","head","yesterday","today","inProgress","check"),format.keys().asSequence().toList())
        assertEquals(listOf("calls","newChecks","todayChecks","issues"),format.getJSONObject("head").keys().asSequence().toList())
        assertEquals(listOf("who","what"),format.getJSONArray("yesterday").getJSONObject(0).keys().asSequence().toList())
        assertEquals(listOf("id","what","who","since","status","check"),format.getJSONArray("today").getJSONObject(0).keys().asSequence().toList())
        assertEquals(listOf("what","who"),format.getJSONArray("check").getJSONObject(0).keys().asSequence().toList())
        listOf("`report/YYYY-MM-DD.json`","한국 시각 오늘","state의 records","매시간 결과 요약","업체(업체가 없으면 사람) 단위로 묶어",
            "약속, 금액, 날짜가 있는 줄을 먼저 두고 최대 8줄","개인 통화(business가 false)는 넣지 않는다","빈 배열 `[]`",
            "오늘과 어제 요청한 것(createdMs)과 확인 필요(status가 needs_confirmation)를 먼저","\"오늘 요청\", \"어제 요청\", \"3일 전 요청\"",
            "흔적이 없으면 빈 문자열 `\"\"`","완료 처리는 하지 않는다","어제 움직임이 있던 것 먼저 최대 3줄","한 줄 60자",
            "가운데 점, 말줄임표, 영어 상태값","\"기한\", \"지남\"","\"까지\"로 끝나는 기한 표현","전화번호는 절대 넣지 않는다",
            "python3 tools/report_check.py report/YYYY-MM-DD.json","30일 넘게 지난 report 파일은 `git rm` 한다",
            "10번의 \"tidy\" 커밋에 함께 넣는다").forEach { assertTrue(it,section.contains(it)) }
        assertTrue(prompt.contains("아침 보고서(9-1번), 지운 파일을 한 커밋 \"tidy\"로 push한다"))
        assertTrue(prompt.contains("\" | 확인 필요: 아침 보고서\""))
        // The self-report: reportWritten, and prompt 7 in the line the watchdog compares with version.json's tidyVersion.
        assertTrue(prompt.contains("\"promptVersion\":\"7\""));assertTrue(prompt.contains("\"reportWritten\":true}"))
        assertTrue(prompt.contains("- `reportWritten`:"))
        assertTrue(File(doc().parentFile,"self-report.md").readText().contains("reportWritten"))
    }

    /** v3.1: the watchdog compares the routine's self-reported promptVersion with version.json's tidyVersion (the head line), so the
     * number written in the pasted text must be the head's. */
    @Test fun tidySelfReportCarriesTheHeadVersion() {
        val text=File(doc().parentFile,"tidy.md").readText()
        val head=Regex("""^지시문 버전\s*(\d+)\s*$""",RegexOption.MULTILINE).find(text.substringBefore("\n---"))!!.groupValues[1]
        assertTrue(text.substringAfter("\n---").contains("`promptVersion`은 \"$head\""))
        assertTrue(text.contains("personCandidates"))
    }
    @Test fun tidyDocEndsWithTheSelfReport() {
        val text=File(doc().parentFile,"tidy.md").readText()
        assertTrue(text.contains("\"routine\":\"tidy\""));assertTrue(text.contains("status/last-run.json"))
        listOf("firstPassDone","tidiedOnce","mergedInto","tidy/proposal.json","\"정리할 것 없음","중복 방지","\"이미 정리됨\"").forEach { assertTrue(it,text.contains(it)) }
        // v2.6: proposals hand back the state's rev (the phone skips an item changed since), merges only mark mergedInto.
        listOf("locked, rev}","mergeCandidates[]","{\"op\":\"rewrite\",\"id\",\"rev\",","{\"op\":\"merge\",\"id\",\"rev\",","{\"op\":\"done_suggested\",\"id\",\"rev\"}",
            "각 제안 항목에는 state의 rev를 그대로 적는다","create에는 rev가 없다","state에 mergeCandidates가 있으면",
            "없어지는 쪽 업체와 사람 항목은 지우지 말고 mergedInto만 적어 둔다").forEach { assertTrue(it,text.contains(it)) }
        // v3.3 (review r3 D9): a pair is what the phone sends, [kept item id, record key] (PipelineSettingsKeys.MERGE_CANDIDATES).
        assertTrue(text.contains("한 쌍은 [남겨 둔 체크할 일 id, 비슷한 일을 냈다가 빠진 기록 key]이다"));assertFalse(text.contains("체크할 일의 id 쌍 목록"))
        assertTrue(text.contains(REPO))
        // v3.1 (r12-3, DESIGN_V4 2.3-1): the first pass no longer stops the tidy; until it ends only the last 14 days, no tidiedOnce.
        assertFalse("firstPassDone no longer stops step 1",text.contains("firstPassDone이 false이거나"))
        listOf("firstPassDone이 false여도 멈추지 않는다","state의 records(최근 14일)를 날짜순으로 전부 읽고","이때는 7번의 tidiedOnce를 쓰지 않는다",
            "firstPassDone이 true이고 장부의 tidiedOnce가 true가 아니면 10월 1일부터","firstPassDone이 false인 날(3번)에는 tidiedOnce를 쓰지 않고").forEach { assertTrue(it,text.contains(it)) }
        // 2.3-3: the hourly self-report carries the cron and the real start and end.
        assertTrue(doc().readText().contains("\"cron\":\"CRON_TZ=Asia/Seoul 34 7-22 * * *\""))
    }

    private companion object {
        /** The version docs/routines/hourly.md is at (v7: DESIGN_V4 3.2-3, r13-2; v8: DESIGN_V4 4.3, r14-3, same rules and format). */
        const val DOC_VERSION=8
        /** The version docs/routines/tidy.md is at (v7: DESIGN_V5 2.2, r15-2, the morning report). */
        const val TIDY_VERSION=7
        /** True once ClaudeCallAnalyzer (r13-1's file) carries the doc's version (the orchestrator's integration step). */
        val codeAtDocVersion get()=ClaudeCallAnalyzer.PROMPT_VERSION>=DOC_VERSION
        /** Both routines find the inbox repository the same way. */
        const val REPO="저장소는 bllue583-commits/ieum-inbox 이다. 작업 폴더에 없으면 ls /home/claude 로 찾고, 그래도 없으면 gh repo clone bllue583-commits/ieum-inbox 로 받는다."
    }
}
