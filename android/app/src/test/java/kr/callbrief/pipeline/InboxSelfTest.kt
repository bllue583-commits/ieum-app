package kr.callbrief.pipeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** End-to-end check of subscription mode with a public synthetic call (CI workflow inbox-e2e):
 * IEUM_SELFTEST_JOB=<file>    writes the job exactly as the phone would (id "selftest-...").
 * IEUM_SELFTEST_RESULT=<file> checks the scheduled task's answer with the phone's own parser and filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class InboxSelfTest {
    companion object {
        const val TRANSCRIPT="여보세요, 김민수 과장님. 네, 3공구 철근 건으로 전화드렸어요. 견적서는 이번 주 금요일까지 메일로 보내드릴게요. " +
            "그리고 계약서 초안은 과장님이 다음 주 월요일에 보내주시기로 하셨죠? 네 맞아요. 아, 지난번 샘플 비용은 처리 끝났습니다. 감사합니다."
        internal val REQUEST=ClaudeCallRequest(TRANSCRIPT,"2026-10-29 10:00 (목)","김민수",95_000,
            listOf(ClaudeCallRequest.OpenTask("wf_item_sample","샘플 비용 처리","2026-10-28","open")),listOf("김민수 과장과 3공구 철근 납품 일정을 맞췄다."))
    }

    @Test fun writeJob() {
        val out=System.getenv("IEUM_SELFTEST_JOB").orEmpty();assumeTrue(out.isNotEmpty())
        val id="selftest-"+(System.getenv("GITHUB_RUN_ID") ?: System.currentTimeMillis().toString())
        File(out).writeText(CallInbox.job(id,REQUEST,System.currentTimeMillis()))
    }

    @Test fun checkResult() {
        val path=System.getenv("IEUM_SELFTEST_RESULT").orEmpty();assumeTrue(path.isNotEmpty())
        val (id,raw)=CallInbox.parseResult(File(path).readText())
        assertTrue(id.startsWith("selftest-"))
        val a=ClaudeCallAnalyzer().filter(raw,REQUEST)
        val report=JSONObject().put("id",id).put("analysis",a.toJson())
            .put("droppedTasks",raw.tasks.size-a.tasks.size).put("droppedPromises",raw.promises.size-a.promises.size)
        File(path+".report.json").writeText(report.toString(2))
        assertTrue("empty summary",a.summary.isNotBlank())
        assertTrue("견적서 task missing",a.tasks.any { it.op=="create" && it.title.contains("견적") })
        assertTrue("sample cost should be suggested done",a.tasks.any { it.op=="done_suggested" && it.taskId=="wf_item_sample" })
        assertEquals("every quote must be in the transcript",raw.tasks.size,a.tasks.size)
    }
}
