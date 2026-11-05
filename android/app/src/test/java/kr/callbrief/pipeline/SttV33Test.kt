package kr.callbrief.pipeline

import kr.callbrief.AndroidWhisperEngine
import kr.callbrief.SttDecode
import kr.callbrief.SttPrompt
import kr.callbrief.WhisperNativeApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** v3.3 r13-2 (DESIGN_V4 3.2-1, 2, 5..8): token probabilities and timestamps, the A8 repair count, the token-limited
 * prompt, the decoding switches and the piece numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SttV33Test {
    private val v33="""{"transcription":[
        {"offsets":{"from":0,"to":3000},"text":" 13층 타설은 금요일","tokens":[[" 1",0.41,0,200],["3",0.92,200,400],["층",0.97,400,600],[" 타",0.99,600,800],["설은",0.98,800,1000],[" 금요일",0.95,1000,1500]]},
        {"offsets":{"from":3000,"to":5000},"text":" 20톤 2,400원","tokens":[[" 20",0.88,3000,3300],["톤",0.9,3300,3500],[" 2",0.3,3500,3600],[",",0.9,3600,3620],["400",0.95,3620,3700],["원",0.99,3700,3800]]}],
        "timings":{"encodeMs":1234.5,"decodeMs":456.7,"batchdMs":12.0,"promptMs":3.5,"sampleMs":7.1,"fallbackP":2,"fallbackH":1},"runtime":"whisper.cpp-v1.7.6-android-cpu"}"""

    @Test fun tokenProbabilitiesMarkTheNumbersHeardWithLittleCertainty() {
        val out=AndroidWhisperEngine.decodeOutput(v33.toByteArray())
        assertEquals(2,out.segments.length())
        // "13" is split over two tokens: the lower one (0.41) decides. "20" is sure. "2,400" takes its "2" (0.3).
        assertEquals(listOf("13"),strings(out.segments.getJSONObject(0).getJSONArray("uncertainNumbers")))
        assertEquals(listOf("2,400"),strings(out.segments.getJSONObject(1).getJSONArray("uncertainNumbers")))
        val t=out.timings!!
        assertEquals(1234.5,t.getDouble("encodeMs"),0.0);assertEquals(2,t.getInt("fallbackP"));assertEquals(1,t.getInt("fallbackH"))
        // The segments the runner hands on carry them (r13-1's number guard reads CallTranscript.Segment.uncertainNumbers).
        val segments=WhisperCallTranscriber.segmentsOf(out)
        assertEquals(listOf("13"),segments[0].uncertainNumbers);assertEquals(listOf("2,400"),segments[1].uncertainNumbers)
        assertEquals(" 13층 타설은 금요일",segments[0].text)
    }
    @Test fun theOldOutputWithoutTokensStillReads() {
        val out=AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":0,"to":100},"text":" 3층"}],"runtime":"whisper.cpp-v1.7.6-android-cpu"}""".toByteArray())
        assertEquals(" 3층",out.text);assertNull(out.timings);assertFalse(out.segments.getJSONObject(0).has("uncertainNumbers"))
        assertEquals(emptyList<String>(),WhisperCallTranscriber.segmentsOf(out).single().uncertainNumbers)
    }
    @Test fun unknownOrBrokenProbabilitiesCountAsSure() {
        val tokens=JSONArray("""[["7",null,0,1],["시",0.2,1,2],["에",0.9,2,3]]""")
        assertEquals(emptyList<String>(),AndroidWhisperEngine.uncertainNumbers(tokens))
        assertEquals(listOf("5"),AndroidWhisperEngine.uncertainNumbers(JSONArray("""[["5",0.49,0,1]]""")))
        assertEquals(emptyList<String>(),AndroidWhisperEngine.uncertainNumbers(JSONArray("""[["5",0.5,0,1]]""")))
    }
    @Test fun a8RepairsAreCountedApartFromDrops() {
        val raw="""{"transcription":[{"offsets":{"from":0,"to":100},"text":" 가"},{"offsets":{"from":90,"to":90},"text":" 나"},{"offsets":{"from":-5,"to":-1},"text":" 다"},{"text":" 라"}]}"""
        val out=AndroidWhisperEngine.decodeOutput(raw.toByteArray())
        assertEquals(3,out.segments.length());assertEquals(2,out.repaired);assertEquals(1,out.dropped)
        val ends=(0 until out.segments.length()).map { out.segments.getJSONObject(it).getLong("endMs") }
        assertEquals(listOf(100L,101L,102L),ends) // still in order, each at least 1 ms long
        val st=SttPieceStats.of(out,WhisperCallTranscriber.segmentsOf(out))
        assertEquals(2,st.repaired);assertEquals(1,st.dropped);assertEquals(3,st.segments)
    }
    @Test fun pieceNumbersAddUpWithoutText() {
        val out=AndroidWhisperEngine.decodeOutput(v33.toByteArray())
        val st=SttPieceStats.of(out,WhisperCallTranscriber.segmentsOf(out))
        val once=SttPieceStats.add(null,st);val twice=SttPieceStats.add(once,st)
        assertEquals(2L,twice.getLong("pieces"));assertEquals(4L,twice.getLong("uncertainNumbers"));assertEquals(4L,twice.getLong("fallbackP"))
        assertEquals(2469.0,twice.getDouble("encodeMs"),0.01);assertEquals(1234.5,twice.getJSONObject("last").getDouble("encodeMs"),0.0)
        assertFalse(twice.toString().contains("층"))
        // The heartbeat keeps it whole (numbers only).
        assertEquals(twice.toString(),PhoneStatus.counts(twice).toString())
    }

    // ---- prompt limited in tokens (3.2-7), context (3.2-6) ----
    /** A tokenizer where every Hangul syllable is 2 tokens and anything else 1: letters and tokens differ. */
    private val twoPerSyllable={ s:String -> s.count { it in '가'..'힣' }+s.length }
    @Test fun thePromptLimitIsInTokensNotLetters() {
        val names=(1..40).map { "업체$it 김과장" }
        val p=SttPrompt.sentence(names,maxTokens=120,count=twoPerSyllable)
        assertTrue(twoPerSyllable(p)<=120)
        assertTrue("the limit is tokens: far fewer letters than 120 fit",p.length<120)
        assertTrue(p.startsWith(SttPrompt.LEAD));assertTrue(p.endsWith("."));assertTrue(p.contains("업체1 김과장"))
        // The same names with a one-token-per-letter counter fit more: the counter decides, not the letter count.
        val q=SttPrompt.sentence(names,maxTokens=120,count={ it.length })
        assertTrue(q.length>p.length);assertTrue(q.length<=120)
        // Order is kept (recent counterparts first, as the caller gave them) and repeats go.
        assertEquals("${SttPrompt.LEAD} 가, 나.",SttPrompt.sentence(listOf("가","나","가"),count={ it.length }))
        assertEquals("",SttPrompt.sentence(listOf("가"),maxTokens=3,count={ it.length }))
    }
    @Test fun contextIsTheLastSentencesWithinItsTokens() {
        val count={ s:String -> s.length }
        assertEquals("둘째 문장입니다. 셋째요.",SttPrompt.context("첫째 문장. 둘째 문장입니다. 셋째요.",40,count))
        assertEquals("셋째요.",SttPrompt.context("첫째 문장. 둘째 문장입니다. 셋째요.",10,count))
        val long=SttPrompt.context("가".repeat(100),20,count);assertTrue(long.length<=20);assertTrue(long.isNotEmpty())
        assertEquals("",SttPrompt.context("",40,count))
        assertEquals("앞 이야기. 힌트",SttPrompt.join("앞 이야기.","힌트",100,count));assertEquals("앞 이야기.",SttPrompt.join("앞 이야기.","힌트".repeat(60),20,count))
    }

    // ---- decoding switches reach the runtime only for a variant (3.2-5, 8) ----
    private class Fake:WhisperNativeApi {
        val prompts=mutableListOf<String>();val options=mutableListOf<List<Float>?>();var n=0
        override fun open(model:String,threads:Int)=7L
        override fun cancel(handle:Long){}
        override fun close(handle:Long){}
        override fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String):ByteArray { prompts+=prompt;options+=null;return out() }
        override fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String,options:FloatArray):ByteArray { prompts+=prompt;this.options+=options.toList();return out() }
        override fun countTokens(handle:Long,text:String)=text.length
        private fun out()=("""{"transcription":[{"offsets":{"from":0,"to":1000},"text":" 조각${++n} 끝."}]}""").toByteArray()
    }
    private fun model(f:File)=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=f }
    private fun modelFile()=File.createTempFile("model",".bin").apply { writeBytes(byteArrayOf(1));deleteOnExit() }
    @Test fun theAppKeepsTodaysDecodingAndAVariantSendsItsSwitches() {
        assertEquals(listOf(-1f,0f,0f,0f),SttDecode.DEFAULT.native().toList())
        val f=modelFile()
        val plain=Fake();WhisperCallTranscriber(AndroidWhisperEngine(RuntimeEnvironment.getApplication(),plain,loadNative=false),model(f),threadCount=6).transcribe(f,FloatArray(16000)){false}
        assertEquals(listOf<List<Float>?>(null),plain.options);assertEquals(listOf(""),plain.prompts)
        val beam=Fake();WhisperCallTranscriber(AndroidWhisperEngine(RuntimeEnvironment.getApplication(),beam,loadNative=false),model(f),threadCount=6,
            decode=SttDecode(temperatureInc=0.4f,bestOf=2,beamSize=2,suppressNonSpeech=true)).transcribe(f,FloatArray(16000)){false}
        assertEquals(listOf(0.4f,2f,2f,1f),beam.options.single())
    }
    @Test fun contextCarriesThePreviousPieceAndTheNamePromptIsTokenLimited() {
        val f=modelFile();val api=Fake()
        val t=WhisperCallTranscriber(AndroidWhisperEngine(RuntimeEnvironment.getApplication(),api,loadNative=false),model(f),threadCount=6,
            promptNames=listOf("탄탄이엔씨 김호중 과장","대한철강 박민수 소장"),carryContext=true)
        t.open(f){false}.use { s -> s.transcribe(FloatArray(16000)){false};s.transcribe(FloatArray(16000)){false} }
        assertEquals("${SttPrompt.LEAD} 탄탄이엔씨 김호중 과장, 대한철강 박민수 소장.",api.prompts[0])
        assertEquals("조각1 끝. ${SttPrompt.LEAD} 탄탄이엔씨 김호중 과장, 대한철강 박민수 소장.",api.prompts[1])
        assertTrue(api.prompts.all { it.length<=SttPrompt.MAX_TOKENS })
    }

    // ---- B7 option: the default threshold is unchanged ----
    @Test fun theLoudShareOptionDefaultsToTodaysThreshold() {
        assertEquals(0.3f,PipelineAudioMath.LOUD_SHARE);assertEquals(0.006f,PipelineAudioMath.SPEECH_FLOOR)
        val x=FloatArray(16000*6) { i -> if(i/16000%2==0) 0.2f*(if(i%2==0) 1 else -1) else 0.03f*(if(i%2==0) 1 else -1) }
        fun kept(share:Float?)=(if(share==null) PipelineSpeechPlan() else PipelineSpeechPlan(loudShare=share)).apply { add(0,x,0,x.size) }.kept
        assertEquals(kept(null),kept(0.3f))
        assertTrue(kept(PipelineAudioMath.LOUD_SHARE_LOW)>=kept(null))
    }

    private fun strings(a:JSONArray)=(0 until a.length()).map { a.getString(it) }
    @Suppress("unused") private fun json(s:String)=JSONObject(s)
}
