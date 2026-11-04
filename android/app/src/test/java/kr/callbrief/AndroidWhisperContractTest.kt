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
        // A segment that goes backwards is dropped on its own (it used to void the whole piece).
        val bad=AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":10,"to":5},"text":"x"}]}""".toByteArray())
        assertEquals(1,bad.dropped);assertEquals(0,bad.segments.length());assertTrue(bad.text.isEmpty())
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
        assertEquals(" 가 다",out.text);assertEquals(2,out.segments.length());assertEquals(2,out.dropped)
        assertEquals(300L,out.segments.getJSONObject(1).getLong("endMs"))
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
