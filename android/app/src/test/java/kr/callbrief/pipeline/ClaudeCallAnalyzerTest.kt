package kr.callbrief.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake HTTP only; no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ClaudeCallAnalyzerTest {
    companion object {
        const val TRANSCRIPT="네 김과장님. 견적서는 내일까지 메일로 보내드릴게요. 그리고 계약서 초안은 다음 주 월요일에 받기로 하죠."
        internal fun reply(analysis:JSONObject)=ClaudeCallResponse(200,JSONObject().put("type","message").put("stop_reason","end_turn")
            .put("content",JSONArray().put(JSONObject().put("type","text").put("text",analysis.toString()))).toString())
        fun analysis(tasks:JSONArray=JSONArray(),promises:JSONArray=JSONArray())=JSONObject().put("summary","김과장과 견적서와 계약서 일정을 정했다.")
            .put("keyPoints",JSONArray().put("견적서 내일 발송")).put("counterpartName","김과장").put("counterpartCompany",JSONObject.NULL)
            .put("promises",promises).put("tasks",tasks)
        fun task(op:String,id:String?,title:String,quote:String,due:String?=null)=JSONObject().put("op",op).put("taskId",id ?: JSONObject.NULL)
            .put("title",title).put("due",due ?: JSONObject.NULL).put("quote",quote)
        internal fun request(open:List<ClaudeCallRequest.OpenTask> =emptyList())=ClaudeCallRequest(TRANSCRIPT,"2026-10-29 10:00 (목)","김과장",125_000,open,listOf("지난 통화 요약"))
    }
    private class Fake(vararg val responses:ClaudeCallResponse):ClaudeCallHttp {
        val bodies=mutableListOf<String>();val keys=mutableListOf<String>()
        override fun post(apiKey:String,body:String):ClaudeCallResponse { keys+=apiKey;bodies+=body;return responses[minOf(bodies.size-1,responses.size-1)] }
    }
    private fun analyzer(fake:Fake)=ClaudeCallAnalyzer(fake) {}

    @Test fun requestBodyHasSchemaOutputConfigAndTextOnly() {
        val fake=Fake(reply(analysis()))
        analyzer(fake).analyze("sk-test",request(listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서 보내기","2026-10-30","open"))))
        assertEquals(listOf("sk-test"),fake.keys)
        val body=JSONObject(fake.bodies.single())
        assertEquals("claude-sonnet-5-5",body.getString("model"));assertEquals(4000,body.getInt("max_tokens"))
        val format=body.getJSONObject("output_config").getJSONObject("format");assertEquals("json_schema",format.getString("type"))
        val schema=format.getJSONObject("schema");assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(setOf("summary","keyPoints","counterpartName","counterpartCompany","counterpart","siteMemo","companyMemo","companyTrade","business","promises","tasks"),
            (0 until schema.getJSONArray("required").length()).map { schema.getJSONArray("required").getString(it) }.toSet())
        assertFalse(fake.bodies.single().contains("maxItems"))
        val content=body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(content.contains(TRANSCRIPT));assertTrue(content.contains("wf_item_1"));assertTrue(content.contains("지난 통화 요약"));assertTrue(content.contains("2026-10-29 10:00"))
        assertTrue(body.getString("system").contains("데이터"))
        listOf("audio","base64","\"source\"","wav","m4a").forEach { assertFalse(it,fake.bodies.single().lowercase().contains(it)) }
    }

    @Test fun quotesNotInTranscriptAndUnknownTaskIdsAreDropped() {
        val tasks=JSONArray().put(task("create",null,"견적서 보내기","견적서는 내일까지 메일로 보내드릴게요"))
            .put(task("create",null,"지어낸 일","전혀 없는 말"))
            .put(task("update","wf_item_unknown","계약서 받기","계약서 초안은 다음 주 월요일에 받기로 하죠"))
            .put(task("done_suggested","wf_item_1","견적서","견적서는 내일까지"))
        val promises=JSONArray().put(JSONObject().put("text","견적서 발송").put("due","2026-10-30").put("owner","me").put("quote","견적서는 내일까지 메일로 보내드릴게요"))
            .put(JSONObject().put("text","가짜").put("due",JSONObject.NULL).put("owner","them").put("quote","없는 인용"))
        val result=analyzer(Fake(reply(analysis(tasks,promises)))).analyze("k",request(listOf(ClaudeCallRequest.OpenTask("wf_item_1","견적서","","open"))))
        assertEquals(listOf("견적서 보내기","견적서"),result.tasks.map { it.title })
        assertEquals(listOf("create","done_suggested"),result.tasks.map { it.op })
        assertEquals(listOf("견적서 발송"),result.promises.map { it.text });assertEquals("2026-10-30",result.promises.single().due)
        assertEquals("김과장",result.counterpartName);assertNull(result.counterpartCompany)
    }

    @Test fun retriesOverloadedAndServerErrorsThenSucceeds() {
        val sleeps=mutableListOf<Long>()
        val fake=Fake(ClaudeCallResponse(529,"{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"),ClaudeCallResponse(429,"{}"),reply(analysis()))
        val result=ClaudeCallAnalyzer(fake) { sleeps+=it }.analyze("k",request())
        assertEquals(3,fake.bodies.size);assertEquals(2,sleeps.size);assertTrue(sleeps[1]>sleeps[0])
        assertEquals("김과장과 견적서와 계약서 일정을 정했다.",result.summary)
    }

    @Test fun clientErrorFailsWithoutRetry() {
        val fake=Fake(ClaudeCallResponse(401,"{\"type\":\"error\"}"))
        try { analyzer(fake).analyze("bad",request());fail() } catch(e:ClaudeCallException) { assertEquals("HTTP_401",e.code) }
        assertEquals(1,fake.bodies.size)
    }

    @Test fun threeServerErrorsFail() {
        val fake=Fake(ClaudeCallResponse(500,"{}"))
        try { analyzer(fake).analyze("k",request());fail() } catch(e:ClaudeCallException) { assertEquals("HTTP_500",e.code) }
        assertEquals(3,fake.bodies.size)
    }
}
