package kr.callbrief

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AndroidWhisperContractTest {
    @Test fun W3401HeaderOnlyWavAndUnsupportedFormatAreRejected(){
        val file=java.io.File("../../worker/tests/audio/public-ko.wav");val wav=file.readBytes();assertTrue(AndroidWhisperEngine.decodePcm(wav).isNotEmpty())
        for(b in listOf(wav.copyOf(44),wav.clone().also{it[22]=2})){var held=false;try{AndroidWhisperEngine.decodePcm(b)}catch(_:IllegalStateException){held=true};assertTrue(held)}
    }
    @Test fun W3402NativeUtf8SegmentsRemainExactAndMonotonic(){
        val raw="""{"transcription":[{"offsets":{"from":0,"to":100},"text":" 공개 😀 확인."}]}""".toByteArray()
        assertEquals(" 공개 😀 확인.",AndroidWhisperEngine.decodeOutput(raw).text)
        var held=false;try{AndroidWhisperEngine.decodeOutput("""{"transcription":[{"offsets":{"from":10,"to":5},"text":"x"}]}""".toByteArray())}catch(_:IllegalStateException){held=true};assertTrue(held)
    }
    @Test fun W3403ExtractiveTaskCannotInferDoneAssigneeOrDue(){
        val text=" 공개 음성입니다. 내일 자료를 확인합니다.";val a=AndroidWhisperRunner.extract(text,JSONObject().put("proposedSummary","앞선 요약"));val t=a.getJSONArray("tasks").getJSONObject(0)
        assertEquals("앞선 요약\n공개 음성입니다.",a.getString("cumulativeSummary"));assertEquals(t.getString("quote"),t.getString("title"));assertEquals("needs_confirmation",t.getString("status"));assertEquals("확인 필요",t.getString("assignee"));assertTrue(t.isNull("due")&&t.isNull("taskId"))
    }
    @Test fun W3404SilenceOrEmptyNativeResultCannotBecomeSuccessfulHistory(){
        for(raw in listOf("{}","{\"transcription\":[]}","{\"transcription\":[{\"offsets\":{\"from\":0,\"to\":1},\"text\":\" \"}]}")){var held=false;try{AndroidWhisperEngine.decodeOutput(raw.toByteArray())}catch(_:Exception){held=true};assertTrue(held)}
    }
}
