package kr.callbrief.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v3.3 r13-2: the real-recording golden measurement (DESIGN_V4 3.2-4), its heartbeat block, the guard block, and the
 * mail sender without an address (3.2-3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GoldenV33Test {
    private fun parity():JSONObject = JSONObject(generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it,"tools/audio/golden_parity.json") }.first { it.isFile }.readText())
    private fun names(o:JSONObject):Map<String,List<String>> = o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }

    /** tools/audio/golden_score.py made golden_parity.json (test_golden_score.py checks it there): the app's copy gives the same numbers. */
    @Test fun kotlinScoreIsThePythonScore() {
        val p=parity();val n=names(p.getJSONObject("names"));val cases=p.getJSONArray("cases")
        assertTrue(cases.length()>=5)
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val ref=c.getString("reference");val e=c.getJSONObject("expected")
            assertEquals("normalize $i",c.getString("normalizedReference"),GoldenScore.normalize(ref))
            assertEquals("numbers $i",(0 until c.getJSONArray("numbers").length()).map { c.getJSONArray("numbers").getString(it) },GoldenScore.numberEntities(ref))
            val s=GoldenScore.scoreReal(ref,c.getString("hypothesis"),n)
            assertEquals("distance $i",e.getInt("distance"),s.distance);assertEquals("refChars $i",e.getInt("refChars"),s.refChars);assertEquals("hypChars $i",e.getInt("hypChars"),s.hypChars)
            assertEquals("cer $i",e.getDouble("cer"),s.cer,1e-12)
            for(kind in listOf("company","person")) {
                val h=e.getJSONObject(kind);assertEquals("$kind $i",GoldenScore.Hits(h.getInt("total"),h.getInt("hit")),s.names[kind])
            }
            val num=e.getJSONObject("number");assertEquals("number $i",GoldenScore.Hits(num.getInt("total"),num.getInt("hit")),s.number)
        }
    }
    @Test fun normalizationReadsWrittenAndSpokenFormsAlike() {
        assertEquals(GoldenScore.normalize("이십 톤"),GoldenScore.normalize("20톤"))
        assertEquals(GoldenScore.normalize("오전 일곱 시"),GoldenScore.normalize("오전 7시"))
        assertEquals("삼개월",GoldenScore.normalize("3개월"))
        assertEquals(GoldenScore.normalize("디 십삼 철근"),GoldenScore.normalize("D13 철근."))
        assertEquals(GoldenScore.normalize("이백사십만 오천 원"),GoldenScore.normalize("2,405,000원"))
        assertEquals(GoldenScore.normalize("백이십 루베"),GoldenScore.normalize("120㎥"))
        assertEquals(GoldenScore.normalize("일곱 시"),GoldenScore.normalize("07시"))
    }

    private fun state():JSONObject {
        val s=GoldenPlan.empty()
        s.getJSONArray("samples").put(JSONObject().put("id","a").put("uri","content://x/1").put("answer","탄탄이엔씨 김호중 과장입니다. 3층 타설은 11월 18일입니다."))
            .put(JSONObject().put("id","b").put("uri","content://x/2").put("answer","대한철강입니다. 20톤 들어가요."))
        return s
    }
    @Test fun thePlanGoesVariantByVariantThenSampleBySample() {
        val s=state()
        assertEquals("a" to GoldenVariant.BASE,GoldenPlan.next(s)!!.let { it.first.getString("id") to it.second })
        GoldenPlan.run(s,"a",GoldenVariant.BASE).put("done",true)
        GoldenPlan.run(s,"a",GoldenVariant.FALLBACK).put("error","GOLDEN_NO_RECORDING")
        assertEquals(GoldenVariant.CONTEXT,GoldenPlan.next(s)!!.second)
        GoldenVariant.entries.forEach { GoldenPlan.run(s,"a",it).put("done",true) }
        assertEquals("b",GoldenPlan.next(s)!!.first.getString("id"))
        GoldenVariant.entries.forEach { GoldenPlan.run(s,"b",it).put("done",true) }
        assertNull(GoldenPlan.next(s));assertEquals(14,GoldenPlan.units(s));assertEquals(14,GoldenPlan.finished(s))
        assertEquals(7,GoldenVariant.entries.size);assertEquals(GoldenVariant.BASE.decode,kr.callbrief.SttDecode.DEFAULT)
        assertEquals(PipelineAudioMath.SPEECH_FLOOR,GoldenVariant.BASE.floor);assertFalse(GoldenVariant.BASE.context||GoldenVariant.BASE.sentence)
    }
    @Test fun theHeartbeatCarriesNumbersOnly() {
        val s=state()
        fun piece(sample:String,v:GoldenVariant,i:Int,text:String,ms:Long) { val r=GoldenPlan.run(s,sample,v)
            (r.optJSONObject("pieces") ?: JSONObject().also { r.put("pieces",it) }).put(i.toString(),JSONObject().put("text",text).put("ms",ms)) }
        piece("a",GoldenVariant.BASE,1,"3층 타설은 11월 18일입니다.",90_000);piece("a",GoldenVariant.BASE,0,"단단 이엔씨 김호중 과장입니다.",80_000)
        GoldenPlan.run(s,"a",GoldenVariant.BASE).put("done",true).put("durationMs",125_000)
        piece("a",GoldenVariant.VAD_LOW,0,"탄탄이엔씨 김호중 과장입니다. 3층 타설은 11월 18일입니다.",100_000)
        GoldenPlan.run(s,"a",GoldenVariant.VAD_LOW).put("done",true)
        assertEquals("단단 이엔씨 김호중 과장입니다. 3층 타설은 11월 18일입니다.",GoldenPlan.hypothesis(GoldenPlan.run(s,"a",GoldenVariant.BASE)))
        val names=mapOf("company" to listOf("탄탄이엔씨","대한철강"),"person" to listOf("김호중"))
        val hb=GoldenPlan.heartbeat(s,names,1_000L)
        val base=hb.getJSONObject("variants").getJSONObject("base");val low=hb.getJSONObject("variants").getJSONObject("vadLow")
        assertEquals(0,base.getJSONObject("company").getInt("hit"));assertEquals(1,low.getJSONObject("company").getInt("hit"))
        assertEquals(1,base.getJSONObject("person").getInt("hit"));assertEquals(3,base.getJSONObject("number").getInt("total"));assertEquals(3,base.getJSONObject("number").getInt("hit"))
        assertTrue(base.getDouble("cerPct")>0);assertEquals(0.0,low.getDouble("cerPct"),0.0)
        assertEquals(85_000L,base.getLong("msPerPiece"));assertEquals(2L,base.getLong("pieces"))
        assertEquals(125L,hb.getJSONArray("bySample").getJSONObject(0).getLong("lenSec"))
        assertEquals(2,hb.getInt("samples"));assertEquals(14,hb.getInt("units"));assertEquals(2,hb.getInt("done"))
        // Nothing of the answers, the transcripts or the names; the heartbeat's filter leaves it as it is.
        val text=hb.toString()
        listOf("탄탄","김호중","타설","층","content").forEach { assertFalse(it,text.contains(it)) }
        assertEquals(text,PhoneStatus.counts(hb).toString())
    }
    @Test fun goldenAndGuardBlocksGoUpAsNumbersOnly() {
        val golden=JSONObject().put("samples",3).put("variants",JSONObject().put("base",JSONObject().put("cerPct",12.5).put("label","몰래 넣은 글")))
        val guard=JSONObject().put("number",4).put("date",1).put("asr",2).put("quote",0).put("confusable",1).put("note","글자")
        val o=PhoneStatus.build(PhoneStatus.Input(1L,"3.3",1L,0,null,0L,0L,"READY","","",1,v33=PhoneStatus.V33(golden,guard)))
        assertEquals(12.5,o.getJSONObject("golden").getJSONObject("variants").getJSONObject("base").getDouble("cerPct"),0.0)
        assertFalse(o.getJSONObject("golden").toString().contains("몰래"))
        assertEquals(4,o.getJSONObject("guard").getInt("number"));assertFalse(o.getJSONObject("guard").has("note"))
        val none=PhoneStatus.build(PhoneStatus.Input(1L,"3.3",1L,0,null,0L,0L,"READY","","",1))
        assertTrue(none.isNull("golden"));assertTrue(none.isNull("guard"))
    }

    // ---- 3.2-3: the mail sender is a name, never an address ----
    @Test fun mailContentHasNoAddress() {
        val c=MessageIntake.mailContent("견적 회신 (문의 kim.ms@example.co.kr)","김민수","안녕하세요.\n보낸 사람: lee@test.com\n단가 @15,000원입니다.\n-- \nkim.ms@example.co.kr",listOf("견적서.pdf"))
        assertTrue(c.contains("보낸 사람: 김민수\n"));assertFalse(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.").containsMatchIn(c))
        assertTrue("a unit price stays",c.contains("단가 @15,000원"));assertTrue(c.contains(MessageIntake.ADDRESS_MASK));assertTrue(c.contains("견적서.pdf"))
        val line={ s:String -> s.lines().first { it.startsWith("보낸 사람:") } }
        assertEquals("보낸 사람: 이름 없음",line(MessageIntake.mailContent("제목",null,"본문",emptyList())))
        assertEquals("보낸 사람: 이름 없음",line(MessageIntake.mailContent("제목","kim@example.com","본문",emptyList())))
        assertFalse(line(MessageIntake.mailContent("제목","\"Kim\" <kim@example.com>","본문",emptyList())).contains("@"))
    }

    @Suppress("unused") private fun arr(vararg s:String)=JSONArray(s.toList())
}
