package kr.callbrief.pipeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real Claude call with a public synthetic call transcript. Runs only when ANTHROPIC_API_KEY is set
 * (CI secret); the key is never printed. The result is written to build/claude-live.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ClaudeLiveTest {
    private val transcript="여보세요, 김민수 과장님. 네, 견적서 건으로 전화드렸어요. 견적서는 이번 주 금요일까지 메일로 보내드릴게요. " +
        "그리고 계약서 초안은 과장님이 다음 주 월요일에 보내주시기로 하셨죠? 네 맞아요. 아, 그리고 지난번 샘플 비용은 처리 끝났습니다. 감사합니다."

    @Test fun realClaudeSummarizesAndExtractsTasks() {
        val key=System.getenv("ANTHROPIC_API_KEY").orEmpty().trim()
        assumeTrue("no key: live test skipped",key.isNotEmpty())
        val request=ClaudeCallRequest(transcript,"2026-10-29 10:00 (목)","김민수",95_000,
            listOf(ClaudeCallRequest.OpenTask("wf_item_sample","샘플 비용 처리","2026-10-28","open")),emptyList())
        val started=System.currentTimeMillis()
        val a=ClaudeCallAnalyzer().analyze(key,request)
        val ms=System.currentTimeMillis()-started
        val out=JSONObject().put("model",ClaudeCallAnalyzer.MODEL).put("seconds",ms/1000.0).put("analysis",a.toJson())
        File("build").mkdirs();File("build/claude-live.json").writeText(out.toString(2))
        println("CLAUDE_LIVE "+out.toString())
        assertTrue(a.summary.isNotBlank())
        assertTrue("견적서 task missing",a.tasks.any{it.op=="create"&&it.title.contains("견적")})
        assertTrue("done sample task not suggested",a.tasks.any{it.op=="done_suggested"&&it.taskId=="wf_item_sample"})
        a.tasks.forEach{assertTrue("quote not in transcript: ${it.quote}",transcript.contains(it.quote))}
    }
}
