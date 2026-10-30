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
        var held=false;try{AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":10,"to":5},"text":"x"}]}""".toByteArray())}catch(_:IllegalStateException){held=true};assertTrue(held)
    }
    @Test fun W3404SilenceOrEmptyNativeResultCannotBecomeSuccessfulHistory(){
        for(raw in listOf("{}","{\"transcription\":[]}","{\"transcription\":[{\"offsets\":{\"from\":0,\"to\":1},\"text\":\" \"}]}")){var held=false;try{AndroidWhisperEngine.decodeOutput(raw.toByteArray())}catch(_:Exception){held=true};assertTrue(held)}
    }
}
