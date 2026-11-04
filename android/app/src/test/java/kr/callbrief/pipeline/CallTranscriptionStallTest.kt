package kr.callbrief.pipeline

import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** The stop counter that halves a piece after three stops in a row, and the saved pieces of a long call (B4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallTranscriptionStallTest:ProtectedTextHostTestBase() {
    private val key=CallTranscriptionStore.Key("rec1",1,"a".repeat(64))
    @Test fun stopsAreCountedPerChunkAndClearedWithTheParts() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            assertEquals(0,s.stalls(key,2))
            repeat(3){ s.noteStall(key,2) }
            s.noteStall(key,3)
            assertEquals(3,s.stalls(key,2));assertEquals(1,s.stalls(key,3))
            assertTrue(s.stalls(key,2)>=CallTranscriptionRunner.SPLIT_AFTER_STOPS)
            s.clearParts(key)
            assertEquals(0,s.stalls(key,2));assertEquals(0,s.stalls(key,3))
        }
    }
    @Test fun halvesAreSavedUnderTheirOwnIdsNextToTheWholeChunks() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            s.savePart(key,0,listOf(CallTranscript.Segment(0,1000,"가")))
            s.savePart(key,-3,listOf(CallTranscript.Segment(120000,121000,"나")))   // first half of chunk 1
            s.savePart(key,-4,listOf(CallTranscript.Segment(180000,181000,"다")))   // second half of chunk 1
            val parts=s.parts(key)
            assertEquals(setOf(0,-3,-4),parts.keys)
            assertEquals("나",parts.getValue(-3).single().text)
            s.clearParts(key);assertTrue(s.parts(key).isEmpty())
        }
    }
    @Test fun piecesMadeWithAnotherPieceLengthAreNotReused() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            s.savePart(key,0,listOf(CallTranscript.Segment(0,1000,"옛")),300_000L)      // saved by the 5-minute version
            s.savePart(key,-1,listOf(CallTranscript.Segment(0,500,"옛반")),300_000L)
            s.noteStall(key,0)
            assertTrue(s.parts(key).isEmpty())                                           // current length is 2 minutes
            assertEquals(0,s.stalls(key,0))                                              // counters of the old numbering go too
            s.savePart(key,0,listOf(CallTranscript.Segment(0,1000,"새")))
            assertEquals("새",s.parts(key).getValue(0).single().text)
            assertEquals(setOf(0),s.parts(key).keys)
        }
    }
    @Test fun stallsOfAllPiecesAreTotalledAndLimitTheRefund() {
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            assertEquals(0,s.totalStalls(key))
            repeat(4){ s.noteStall(key,0) };repeat(2){ s.noteStall(key,1) }
            assertEquals(6,s.totalStalls(key))
        }
        assertTrue(CallTranscriptionRunner.refundStop(5));assertFalse(CallTranscriptionRunner.refundStop(6))
        assertTrue(CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS<540+1)
        assertTrue(CallTranscriptionRunner.requeueNow("MORE",0));assertTrue(CallTranscriptionRunner.requeueNow("STOPPED",1))
        assertFalse(CallTranscriptionRunner.requeueNow("STOPPED",0));assertFalse(CallTranscriptionRunner.requeueNow("IDLE",3))
        assertTrue(CallTranscriptionRunner.harmlessStop(true,1));assertFalse(CallTranscriptionRunner.harmlessStop(true,0));assertFalse(CallTranscriptionRunner.harmlessStop(false,3))
    }

    // ---- review #12: a call of about an hour needs ~10 runs of the 10 minute limit and must not end as STALLED ----
    private val rate=8000
    /** A synthetic 8 kHz mono WAV of [seconds]: tones with pauses, written straight into one array. */
    private fun wav(seconds:Int):ByteArray {
        val n=rate*seconds;val out=ByteArray(44+n*2)
        val h=java.nio.ByteBuffer.wrap(out,0,44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(n*2)
        val r=kotlin.random.Random(7);var t=0
        fun put(v:Int){ if(t<n){ out[44+2*t]=(v and 0xff).toByte();out[45+2*t]=((v shr 8) and 0xff).toByte();t++ } }
        while(t<n){ val speak=rate*(20+r.nextInt(40));val f=200+r.nextInt(300);repeat(speak){ put((kotlin.math.sin(2*Math.PI*f*t/rate)*6000).toInt()) }
            repeat(rate/2+r.nextInt(rate*2)){ put(r.nextInt(40)-20) } }
        return out
    }
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=java.io.File("m") }

    /** Runs a 60-minute recording the way the worker does: each run finishes two pieces and is stopped by the time limit
     * on the third. Returns the job's final state and the transcript when it came through. */
    private fun longCall(timeLimit:Boolean,bytes:ByteArray,id:String):Pair<String,List<CallTranscript.Segment>?> {
        var calls=0
        val transcriber=CallTranscriber { _,pcm,_ -> calls++;listOf(CallTranscript.Segment(0,pcm.size*1000L/16000,"조각${pcm.size}")) }
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            val k=CallTranscriptionStore.Key(id,1,"a".repeat(64))
            s.offer(CallTranscriptionStore.Candidate(k,"long.wav","content://generated/long","audio/wav",null,false,true),true,"")
            val runner=CallTranscriptionRunner(s,model,PipelineAudioDecoder(),transcriber,read={error("unused")},skipReason={null},sink={null},
                stopped={ calls>2 },timeLimited={ timeLimit })
            repeat(40) {
                if(!s.claim(k)) return s.rows().single { it.first==k }.second to null
                calls=0
                try { val (_,segments)=runner.transcribeRecording(k,PipelineBytesInput(bytes),"audio/wav",java.io.File("m"));s.finish(k,"DONE","");return "DONE" to segments }
                catch(_:CallTranscriptionRunner.Stopped) { runner.endStopped(k) }
            }
            return s.rows().single { it.first==k }.second to null
        }
    }

    @Test fun aSixtyMinuteCallIsTranscribedToTheEndAcrossTimeLimitStops() {
        val bytes=wav(60*60)
        // Reference: the same recording in one go.
        val reference=CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            CallTranscriptionRunner(s,model,PipelineAudioDecoder(),CallTranscriber { _,pcm,_ -> listOf(CallTranscript.Segment(0,pcm.size*1000L/16000,"조각${pcm.size}")) },
                read={error("unused")},skipReason={null},sink={null}).transcribeRecording(CallTranscriptionStore.Key("ref",1,"a".repeat(64)),PipelineBytesInput(bytes),"audio/wav",java.io.File("m"))
        }
        assertTrue("an hour is many pieces",reference.first.chunks>=20)
        val (state,segments)=longCall(true,bytes,"hour")
        assertEquals("DONE",state);assertEquals(reference.second,segments)
        CallTranscriptionStore(RuntimeEnvironment.getApplication()).use { s ->
            val k=CallTranscriptionStore.Key("hour",1,"a".repeat(64))
            assertEquals("no stop was counted as a stall",0,s.totalStalls(k));assertEquals(1,s.attempts(k))
        }
        // The old rule (every stop a stall) ends the same call as STALLED part way.
        assertEquals("FAILED",longCall(false,bytes,"hour-old-rule").first)
    }
}
