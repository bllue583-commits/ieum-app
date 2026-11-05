package kr.callbrief.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v3.3 r13-1 (DESIGN_V4 3.1-1, 3, 6): the number guard on made-up calls. "삼 층" said and "3층" written is fine; a number said
 * only elsewhere in the call, a date nobody said, a misheard number and a loosely copied quote are kept as tentative. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NumberGuardTest {
    private val filter=ClaudeCallAnalyzer()
    private fun task(title:String,quote:String,due:String?=null,op:String="create",id:String?=null)=JSONObject().put("op",op).put("taskId",id ?: JSONObject.NULL)
        .put("title",title).put("due",due ?: JSONObject.NULL).put("quote",quote).put("owner","them").put("certainty","sure")
    private fun answer(vararg t:JSONObject)=ClaudeCallAnalysis.parse(JSONObject().put("summary","철근 업체와 타설 일정을 정했어요.").put("keyPoints",JSONArray())
        .put("counterpartName",JSONObject.NULL).put("counterpartCompany",JSONObject.NULL).put("tasks",JSONArray(t.toList())))
    private fun request(lines:List<String>,times:NumberGuard.Times?=null,texts:String="")=
        ClaudeCallRequest(lines.joinToString("\n"),"2026-11-04 10:00 (수)","김과장",600_000,emptyList(),emptyList(),recentTexts=texts,segmentTimes=times)
    private fun one(r:ClaudeCallRequest,vararg t:JSONObject)=filter.filter(answer(*t),r).tasks.single()
    /** One line every 5 seconds. */
    private fun every5s(n:Int)=NumberGuard.Times((0 until n).map { it*5_000L },(0 until n).map { it*5_000L+4_000 })

    @Test fun koreanNumeralsBecomeDigits() {
        assertEquals("3층타설",NumberGuard.squash("삼 층 타설"))
        assertEquals("30층",NumberGuard.squash("삼십 층"))
        assertEquals("13층",NumberGuard.squash("십삼층"))
        assertEquals("25mpa",NumberGuard.squash("이십오 MPa"))
        assertEquals("500000원",NumberGuard.squash("오십만 원"))
        assertEquals("15000000원",NumberGuard.squash("1,500만원"))
        assertEquals("d13철근",NumberGuard.squash("디 십삼 철근"))
        assertEquals("100mm",NumberGuard.squash("백 미리"))
        assertEquals("3대",NumberGuard.squash("세 대"))
        assertEquals("12개",NumberGuard.squash("열두 개"))
        assertEquals("30%",NumberGuard.squash("삼십 프로"))
        assertEquals("30일",NumberGuard.squash("삼십일"))
        assertEquals("11월",NumberGuard.squash("십일월"))
        // Words that only look like numbers stay words.
        assertEquals("이번주일정",NumberGuard.squash("이번 주 일정"))
        assertEquals("사장님구분",NumberGuard.squash("사장님 구분"))
        assertEquals("천천히",NumberGuard.squash("천천히"))
        assertEquals(listOf("3층"),NumberGuard.tokens("3층 타설 일정 확인").map { it.toString() })
        assertEquals(listOf("D13"),NumberGuard.tokens("D13 철근 반입").map { it.toString() })
    }

    @Test fun saidAsWordsWrittenAsDigitsIsNoBadge() {
        val r=request(listOf("네 부장님","삼 층 타설은 금요일로 할게요"))
        val t=one(r,task("3층 타설 확인","삼 층 타설은 금요일로 할게요","2026-11-06"))
        assertFalse(t.guard.toString(),t.tentative);assertTrue(t.guard.isEmpty());assertEquals("2026-11-06",t.due)
    }

    @Test fun aNumberSaidOnlyElsewhereIsANumberBadge() {
        val lines=listOf("삼 층 타설은 금요일로 할게요")+List(20) { "다른 이야기 ${it}번째 줄이에요".replace(Regex("\\d+"),"") }+listOf("철근은 삼십 톤 들어와요")
        val t=one(request(lines),task("30층 타설 확인","삼 층 타설은 금요일로 할게요"))
        assertTrue(t.tentative);assertEquals(listOf(NumberGuard.NUMBER),t.guard)
        // Within 30 seconds of the quote (segment times) it passes; 40 seconds away it does not.
        val near=listOf("삼 층 타설은 금요일로 할게요","아 그게 아니라 삼십 층이요")
        assertTrue(one(request(near,every5s(2)),task("30층 타설 확인","삼 층 타설은 금요일로 할게요")).guard.isEmpty())
        val far=NumberGuard.Times(listOf(0L,40_000L),listOf(4_000L,44_000L))
        assertEquals(listOf(NumberGuard.NUMBER),one(request(near,far),task("30층 타설 확인","삼 층 타설은 금요일로 할게요")).guard)
        // Without times: lines around the quote stand in (six either side).
        assertTrue(one(request(near),task("30층 타설 확인","삼 층 타설은 금요일로 할게요")).guard.isEmpty())
    }

    @Test fun datesNotSaidBeforeTheCallOrFarAway() {
        val r=request(listOf("견적서는 메일로 보내드릴게요","도면도 같이 드릴게요"))
        assertEquals(listOf(NumberGuard.DATE),one(r,task("견적서 회신 확인","견적서는 메일로 보내드릴게요","2026-11-06")).guard)
        // Before the call day: the date goes, no badge for it.
        val past=one(r,task("견적서 회신 확인","견적서는 메일로 보내드릴게요","2026-11-01"))
        assertNull(past.due);assertFalse(past.tentative)
        val said=request(listOf("견적서는 다음 주 월요일까지 메일로 보내드릴게요"))
        assertTrue(one(said,task("견적서 회신 확인","견적서는 다음 주 월요일까지 메일로 보내드릴게요","2026-11-09")).guard.isEmpty())
        assertEquals(listOf(NumberGuard.DATE),one(said,task("견적서 회신 확인","견적서는 다음 주 월요일까지 메일로 보내드릴게요","2027-02-09")).guard)
    }

    @Test fun anUnsureNumberOfTheRecogniserIsAnAsrBadge() {
        val lines=listOf("철근은 삼십 톤 들어와요","네 알겠습니다")
        val times=NumberGuard.Times(listOf(0L,5_000L),listOf(4_000L,9_000L),mapOf(0 to listOf("30")))
        assertEquals(listOf(NumberGuard.ASR),one(request(lines,times),task("철근 30톤 반입 확인","철근은 삼십 톤 들어와요")).guard)
        // The seam is empty until r13-2 fills it: nothing then.
        assertTrue(one(request(lines,every5s(2)),task("철근 30톤 반입 확인","철근은 삼십 톤 들어와요")).guard.isEmpty())
    }

    @Test fun aLooselyCopiedQuoteIsKeptAsTentative() {
        val r=request(listOf("타설 일정표는 금요일까지 꼭 드릴게요 부장님"))
        // About 80% of its 3-letter pieces: dropped before v3.3, kept now with the quote reason.
        val loose="타설 일정표는 금요일까지 꼭 드려요 부장님"
        val cover=NameKey.windowCoverage3(NameKey.bare(loose),NameKey.bare(r.transcript))
        assertTrue("$cover",cover>=NumberGuard.QUOTE_LOW && cover<ClaudeCallAnalyzer.FUZZY_COVERAGE)
        val t=one(r,task("타설 일정표 받기",loose))
        assertTrue(t.tentative);assertTrue(t.quoteFuzzy);assertTrue(t.guard.toString(),NumberGuard.QUOTE in t.guard)
        assertTrue(filter.filter(answer(task("타설 일정표 받기","전혀 다른 말을 지어낸 인용문이에요")),r).tasks.isEmpty())
    }

    @Test fun confusablePairsMakeAMemoOtherNumbersDoNot() {
        assertEquals("앞의 일과 숫자가 달라요(13층/30층)",NumberGuard.confusable("13층 타설 확인","30층 타설 확인"))
        assertEquals("앞의 일과 숫자가 달라요(50층/15층)",NumberGuard.confusable("50층 타설 확인","15층 타설 확인"))
        assertNull(NumberGuard.confusable("13층 타설 확인","31층 타설 확인"))
        assertNull(NumberGuard.confusable("13층 타설 확인","30층 거푸집 확인"))
        assertNull(NumberGuard.confusable("13층 타설 확인","13층 타설 확인"))
    }

    @Test fun segmentTimesAreSmallAndRoundTrip() {
        val segs=(0 until 600).map { CallTranscript.Segment(it*6_000L,it*6_000L+5_500,"한 줄 $it",if(it==3) listOf("30") else emptyList()) }
        val text=segs.joinToString("\n") { it.text }
        val times=NumberGuard.Times.of(segs,text)!!
        val json=times.toJson()
        assertTrue("an hour of segments in a few KB: ${json.length}",json.length<4_000)
        val back=NumberGuard.Times.parse(json)!!
        assertEquals(600,back.size);assertEquals(18_000L,back.startsMs[3]);assertEquals(listOf("30"),back.uncertain[3])
        assertFalse("no words kept",json.contains("한 줄"))
        assertNull("lines that do not match the text are not kept",NumberGuard.Times.of(segs,"다른 글"))
        assertNull(NumberGuard.Times.parse("[]"))
    }

    @Test fun correctedIsKeptNotDropped() {
        val raw=JSONObject().put("summary","요약").put("keyPoints",JSONArray()).put("counterpartName",JSONObject.NULL).put("counterpartCompany",JSONObject.NULL)
            .put("corrected",JSONArray().put("김민수"))
            .put("tasks",JSONArray().put(task("견적서 회신 확인","견적서는 메일로 보내드릴게요").put("corrected",true)))
        val a=filter.filter(ClaudeCallAnalysis.parse(raw),request(listOf("견적서는 메일로 보내드릴게요")))
        assertEquals("[\"김민수\"]",a.corrected);assertEquals("true",a.tasks.single().corrected)
        val stored=ClaudeCallAnalysis.parse(JSONObject(a.toJson().toString()))
        assertEquals(a.corrected,stored.corrected);assertEquals("true",stored.tasks.single().corrected)
        assertEquals(true,a.toJson().getJSONArray("tasks").getJSONObject(0).get("corrected"))
    }
}
