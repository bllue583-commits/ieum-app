package kr.callbrief.pipeline

import kr.callbrief.AndroidWhisperEngine
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.WhisperNativeApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Round 4 pipeline: A6 (RUNNING recovery), A7 (model opened once), A9 (clean-up), F2 (threads), A4/A5 (discovery works). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PipelineV2Test:ProtectedTextHostTestBase() {
    // ---- A6 ----
    private fun candidate(id:String)=CallTranscriptionStore.Candidate(CallTranscriptionStore.Key(id,1,"a".repeat(64)),"x.m4a","content://x","audio/mp4",1L,false,true)
    @Test fun recoverRunningTouchesOnlyRowsOlderThanElevenMinutes() {
        var clock=1_000_000_000L
        CallTranscriptionStore(RuntimeEnvironment.getApplication(),{clock}).use { s ->
            val old=candidate("old");val fresh=candidate("fresh");val oldMaxed=candidate("maxed")
            for(c in listOf(old,fresh,oldMaxed))s.offer(c,true,"")
            assertTrue(s.claim(old.key))                                   // RUNNING at t0 (attempt 1)
            assertTrue(s.claim(oldMaxed.key));s.finish(oldMaxed.key,"QUEUED","");assertTrue(s.claim(oldMaxed.key));s.finish(oldMaxed.key,"QUEUED","");assertTrue(s.claim(oldMaxed.key)) // attempt 3
            clock+=CallTranscriptionStore.STALE_RUNNING_MS+1_000            // 11 min later
            assertTrue(s.claim(fresh.key))                                  // another worker started just now
            s.recoverRunning(CallTranscriptionRunner.MAX_ATTEMPTS)
            val states=s.rows().associate { it.first.recordingId to it.second }
            assertEquals("QUEUED",states["old"]);assertEquals("RUNNING",states["fresh"]);assertEquals("FAILED",states["maxed"])
        }
    }
    @Test fun recoverRunningLeavesRowsThatAreYoungerThanTheLimit() {
        var clock=5_000_000_000L
        CallTranscriptionStore(RuntimeEnvironment.getApplication(),{clock}).use { s ->
            val c=candidate("r");s.offer(c,true,"");assertTrue(s.claim(c.key))
            clock+=CallTranscriptionStore.STALE_RUNNING_MS-1_000
            s.recoverRunning(CallTranscriptionRunner.MAX_ATTEMPTS)
            assertEquals("RUNNING",s.rows().single().second)
        }
    }
    @Test fun hourlyJobsOnlyQueueTheSameNamedOneTimeWork() {
        assertEquals("calls-transcription-hourly",CallTranscriptionWork.PERIODIC)
        assertTrue(CallTranscriptionTick::class.java.superclass==androidx.work.Worker::class.java)
    }

    // ---- A7 ----
    private class FakeNative(val failOn:Int=-1):WhisperNativeApi {
        val log=mutableListOf<String>();var next=7L;var transcribed=0
        override fun open(model:String,threads:Int):Long {log+="open:$threads";return next++}
        override fun cancel(handle:Long){log+="cancel:$handle"}
        override fun close(handle:Long){log+="close:$handle"}
        override fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int):ByteArray {
            log+="chunk:$handle:$seconds"
            if(transcribed++==failOn)throw IllegalStateException("ANDROID_STT_NOT_COMPLETED")
            return """{"transcription":[{"offsets":{"from":0,"to":1000},"text":" 가"}]}""".toByteArray()
        }
    }
    private fun modelFile()=File.createTempFile("model",".bin").apply { writeBytes(byteArrayOf(1));deleteOnExit() }
    private fun model(f:File)=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=f }
    @Test fun modelIsOpenedOnceForAllPiecesOfARecordingAndClosedAtTheEnd() {
        val api=FakeNative();val f=modelFile();val engine=AndroidWhisperEngine(RuntimeEnvironment.getApplication(),api,loadNative=false)
        val t=WhisperCallTranscriber(engine,model(f),threadCount=6)
        t.open(f){false}.use { s -> repeat(3){ assertEquals("가",s.transcribe(FloatArray(16000)){false}.single().text.trim()) } }
        assertEquals(listOf("open:6","chunk:7:540","chunk:7:540","chunk:7:540","close:7"),api.log)
    }
    @Test fun modelIsClosedWhenAPieceFails() {
        val api=FakeNative(failOn=1);val f=modelFile();val engine=AndroidWhisperEngine(RuntimeEnvironment.getApplication(),api,loadNative=false)
        val t=WhisperCallTranscriber(engine,model(f),threadCount=4)
        try { t.open(f){false}.use { s -> s.transcribe(FloatArray(16000)){false};s.transcribe(FloatArray(16000)){false} };fail() } catch(e:IllegalStateException) { assertEquals("ANDROID_STT_NOT_COMPLETED",e.message) }
        assertEquals("close:7",api.log.last());assertEquals(1,api.log.count{it.startsWith("open")});assertEquals(1,api.log.count{it.startsWith("close")})
    }
    @Test fun cancelGoesToTheOpenHandleAndNothingRunsAfterAStop() {
        val api=FakeNative();val f=modelFile();val engine=AndroidWhisperEngine(RuntimeEnvironment.getApplication(),api,loadNative=false)
        val s=engine.openVerifiedModel(f,{true},6){false}
        engine.cancel();assertTrue("cancel:7" in api.log)
        var stop=false
        s.transcribe(FloatArray(16000),"ko",540){stop}
        stop=true
        try { s.transcribe(FloatArray(16000),"ko",540){stop};fail() } catch(e:IllegalStateException) { assertEquals("ANDROID_STT_STOPPED",e.message) }
        assertEquals(1,api.log.count{it.startsWith("chunk")})
        s.close();s.close() // closing twice frees once
        assertEquals(1,api.log.count{it.startsWith("close")})
        val before=api.log.size;engine.cancel();assertEquals(before,api.log.size) // no handle left to cancel
    }
    @Test fun aStoppedRunDoesNotOpenTheModel() {
        val api=FakeNative();val f=modelFile();val engine=AndroidWhisperEngine(RuntimeEnvironment.getApplication(),api,loadNative=false)
        try { engine.openVerifiedModel(f,{true},6){true};fail() } catch(e:IllegalStateException) { assertEquals("ANDROID_STT_STOPPED",e.message) }
        assertTrue(api.log.isEmpty())
    }
    @Test fun fakesWithoutASessionStillWorkThroughTheDefaultOpen() {
        var calls=0
        val t=CallTranscriber { _,pcm,_ -> calls++;listOf(CallTranscript.Segment(0,pcm.size.toLong(),"가")) }
        t.open(File("m")){false}.use { it.transcribe(FloatArray(5)){false};it.transcribe(FloatArray(6)){false} }
        assertEquals(2,calls)
    }

    // ---- F2 ----
    @Test fun threadsFollowChargerAndBattery() {
        assertEquals(6,TranscriptionThreads.threadsFor(true,10));assertEquals(6,TranscriptionThreads.threadsFor(false,50))
        assertEquals(6,TranscriptionThreads.threadsFor(false,100));assertEquals(4,TranscriptionThreads.threadsFor(false,49));assertEquals(4,TranscriptionThreads.threadsFor(false,25))
    }

    // ---- A4 / A5 ----
    @Test fun callLogWorkWaitsForCallLogChangesAndFollowupWaits150Seconds() {
        val spec=AutomaticRecordingDiscovery.callLogRequest().workSpec.constraints
        assertTrue(spec.hasContentUriTriggers())
        val trigger=spec.contentUriTriggers.single()
        assertEquals(android.provider.CallLog.Calls.CONTENT_URI,trigger.uri);assertTrue(trigger.isTriggeredForDescendants)
        assertEquals(45_000L,spec.contentTriggerUpdateDelayMillis)
        val follow=AutomaticRecordingDiscovery.followupRequest(AutomaticRecordingDiscovery.Binding("a".repeat(64))).workSpec
        assertEquals(150_000L,follow.initialDelay)
        assertEquals("ieum.discovery.followup",AutomaticRecordingDiscovery.FOLLOWUP_NAME);assertEquals("ieum.discovery.calllog",AutomaticRecordingDiscovery.CALLLOG_NAME)
    }
    @Test fun callLogWorkIsNotRegisteredWithoutThePermission() {
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALL_LOG)
        assertFalse(AutomaticRecordingDiscovery.ensureCallLogTrigger(app))
    }

    // ---- A9 ----
    private fun seg(vararg t:String)=t.mapIndexed { i,x -> CallTranscript.Segment(i*1000L,i*1000L+900,x) }
    @Test fun hallucinationPhraseListIsFixed() {
        assertEquals(20,TranscriptClean.PHRASES.size)
        assertEquals(TranscriptClean.PHRASES.size,TranscriptClean.PHRASES.map{it.filter(Char::isLetterOrDigit).lowercase()}.toSet().size)
        for(p in listOf("시청해 주셔서 감사합니다","구독과 좋아요","다음 영상에서 만나요","자막 제공","MBC 뉴스","KBS 뉴스"))assertTrue(p,p in TranscriptClean.PHRASES)
    }
    @Test fun knownPhraseSegmentsAreRemovedWhateverThePunctuation() {
        val r=TranscriptClean.clean(seg("안녕하세요. 견적 문의드립니다."," 시청해 주셔서 감사합니다.","구독과  좋아요!","MBC 뉴스","납기는 언제인가요?"))
        assertEquals(listOf("안녕하세요. 견적 문의드립니다.","납기는 언제인가요?"),r.segments.map{it.text});assertEquals(3,r.removed)
    }
    @Test fun aPhraseInsideARealSentenceIsKept() {
        val r=TranscriptClean.clean(seg("시청해 주셔서 감사합니다라고 적혀 있네요","KBS 뉴스에서 봤어요"))
        assertEquals(2,r.segments.size);assertEquals(0,r.removed)
    }
    @Test fun threeOrMoreSameSegmentsInARowBecomeOne() {
        val r=TranscriptClean.clean(seg("네.","네.","네.","네.","알겠습니다.","네.","네."))
        assertEquals(listOf("네.","알겠습니다.","네.","네."),r.segments.map{it.text});assertEquals(3,r.removed)
        assertEquals(0L,r.segments.first().startMs)
    }
    @Test fun twoInARowAreKept() { val r=TranscriptClean.clean(seg("네.","네.","좋아요."));assertEquals(3,r.segments.size);assertEquals(0,r.removed) }
    @Test fun repeatedSentencesInsideOneSegmentAreCut() {
        val r=TranscriptClean.clean(seg("감사합니다. 감사합니다. 감사합니다. 감사합니다. 그럼 내일 뵙겠습니다."))
        assertEquals("감사합니다. 그럼 내일 뵙겠습니다.",r.segments.single().text);assertEquals(3,r.removed)
    }
    @Test fun cleanTextOfNormalSpeechIsUntouched() {
        val input=seg("오늘 납품 가능할까요?","네 가능합니다.","그럼 오후에 받겠습니다.")
        val r=TranscriptClean.clean(input);assertEquals(input,r.segments);assertEquals(0,r.removed)
    }
    @Test fun anEmptyOrAllHallucinatedTranscriptBecomesEmpty() {
        assertTrue(TranscriptClean.clean(emptyList()).segments.isEmpty())
        val r=TranscriptClean.clean(seg("시청해 주셔서 감사합니다.","구독과 좋아요"));assertTrue(r.segments.isEmpty());assertEquals(2,r.removed)
    }
}
