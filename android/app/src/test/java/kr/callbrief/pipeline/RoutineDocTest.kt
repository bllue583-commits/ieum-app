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
        listOf("- siteMemo: 현장의 지금 상황","memo는 그 업체의 지금 상태","[공종 목록] 중 하나로","\"promptVersion\": 4는 폰이 업데이트되기 전의 과도기 요청",
            "옛 형식","\"system\"을 규칙으로").forEach { assertTrue(it,text.contains(it)) }
        assertTrue(text.contains(REPO))
        // Same v5 round: the open items' request fields (design C5) are named next to rule 12, the rules unchanged.
        listOf("askedOn(요청한 날)","owner(그때 정한 맡는 쪽)","person(요청한 통화 상대 이름)","quote(그때 근거 원문의 앞 40자)").forEach { assertTrue(it,block(text,"SYSTEM","text").contains(it)) }
    }

    @Test fun tidyDocEndsWithTheSelfReport() {
        val text=File(doc().parentFile,"tidy.md").readText()
        assertTrue(text.contains("\"routine\":\"tidy\""));assertTrue(text.contains("status/last-run.json"))
        listOf("firstPassDone","tidiedOnce","mergedInto","tidy/proposal.json","\"정리할 것 없음\"","중복 방지","\"이미 정리됨\"").forEach { assertTrue(it,text.contains(it)) }
        assertTrue(text.contains(REPO))
    }

    private companion object {
        /** Both routines find the inbox repository the same way. */
        const val REPO="저장소는 bllue583-commits/ieum-inbox 이다. 작업 폴더에 없으면 ls /home/claude 로 찾고, 그래도 없으면 gh repo clone bllue583-commits/ieum-inbox 로 받는다."
    }
}
