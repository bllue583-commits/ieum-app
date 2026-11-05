package kr.callbrief

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AndroidWhisperContractTest {
    @Test fun W3402NativeUtf8SegmentsRemainExactAndMonotonic(){
        val raw="""{"transcription":[{"offsets":{"from":0,"to":100},"text":" 공개 😀 확인."}]}""".toByteArray()
        assertEquals(" 공개 😀 확인.",AndroidWhisperEngine.decodeOutput(raw).text)
        // v3.3 A8: a segment whose end is before its start is set right (end=start+1) and counted, not dropped.
        val bad=AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":10,"to":5},"text":"x"}]}""".toByteArray())
        assertEquals(0,bad.dropped);assertEquals(1,bad.repaired);assertEquals(1,bad.segments.length());assertEquals("x",bad.text)
        assertEquals(10L,bad.segments.getJSONObject(0).getLong("startMs"));assertEquals(11L,bad.segments.getJSONObject(0).getLong("endMs"))
    }
    @Test fun W3404SilenceGivesNoSegmentsAndOnlyBrokenJsonIsAnError(){
        for(raw in listOf("{\"transcription\":[]}","{\"transcription\":[{\"offsets\":{\"from\":0,\"to\":1},\"text\":\" \"}]}")){
            val out=AndroidWhisperEngine.decodeOutput(raw.toByteArray());assertEquals(0,out.segments.length());assertTrue(out.text.isBlank())
        }
        for(raw in listOf("{}","not json")){var held=false;try{AndroidWhisperEngine.decodeOutput(raw.toByteArray())}catch(_:Exception){held=true};assertTrue(held)}
    }
    @Test fun W3405OneBadSegmentKeepsTheRestOfThePiece(){
        val raw="""{"transcription":[{"offsets":{"from":0,"to":100},"text":" 가"},{"offsets":{"from":50,"to":40},"text":" 나"},{"offsets":{"from":100,"to":200},"text":" "},{"offsets":{"from":100,"to":300},"text":" 다"}]}"""
        val out=AndroidWhisperEngine.decodeOutput(raw.toByteArray())
        // v3.3 A8: the backwards one is kept with start=last end (100) and end=start+1; only the blank one is dropped.
        assertEquals(" 가 나 다",out.text);assertEquals(3,out.segments.length());assertEquals(1,out.dropped);assertEquals(2,out.repaired)
        assertEquals(100L,out.segments.getJSONObject(1).getLong("startMs"));assertEquals(101L,out.segments.getJSONObject(1).getLong("endMs"))
        assertEquals(101L,out.segments.getJSONObject(2).getLong("startMs"));assertEquals(300L,out.segments.getJSONObject(2).getLong("endMs"))
    }
    @Test fun W3406SegmentLimitIsTheNativeOneOf2048(){
        fun raw(n:Int)=("""{"transcription":["""+(0 until n).joinToString(","){"""{"offsets":{"from":${it*10},"to":${it*10+5}},"text":" a"}"""}+"]}").toByteArray()
        assertEquals(2048,AndroidWhisperEngine.decodeOutput(raw(2048)).segments.length())
        var held=false;try{AndroidWhisperEngine.decodeOutput(raw(2049))}catch(_:IllegalStateException){held=true};assertTrue(held)
    }
    /** The arm64 runtime (armv8.2-a+dotprod+fp16) is refused, not loaded, on a CPU where any core lacks dotprod or fp16. */
    @Test fun W3407ArmRuntimeNeedsDotprodAndFp16OnEveryCore(){
        val s25="processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\nprocessor\t: 1\nFeatures\t: fp asimd asimdhp asimddp i8mm\n"
        assertEquals(emptyList<String>(),AndroidWhisperEngine.missingArmFeatures(s25))
        val a53="processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n"
        assertEquals(listOf("asimddp","asimdhp"),AndroidWhisperEngine.missingArmFeatures(a53))
        val mixed="Features\t: fp asimd asimdhp asimddp\nFeatures\t: fp asimd asimdhp\n"
        assertEquals(listOf("asimddp"),AndroidWhisperEngine.missingArmFeatures(mixed))
        assertEquals(listOf("asimddp","asimdhp"),AndroidWhisperEngine.missingArmFeatures(""))
    }
}
