package kr.callbrief.pipeline

import android.provider.CallLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v4.2 r23-3: the records left "length" by their recording length and age, the "noCandidate" ones by age (numbers only).
 * Made-up times and lengths, no names or numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class R23MatchTest {
    private val day=86_400_000L;private val min=60_000L
    private val now=java.time.ZonedDateTime.of(2026,11,20,9,0,0,0,CallRecordStore.KST).toInstant().toEpochMilli()
    private fun r(id:String,start:Long,lenMs:Long)=FirstPass.RecordForMatch(id,start,null,null,lenMs)
    private fun e(id:Long,start:Long,lenMs:Long)=FirstPass.LogEntry(id,null,null,start,lenMs,CallLog.Calls.INCOMING_TYPE)

    @Test fun lengthAndNoCandidateAreBinnedByRecordingLengthAndAge() {
        val records=listOf(
            r("short-today",now-2*60*min,30_000L),        // a 30 s recording against a 5-minute call: length, lt60, le1
            r("mid-week",now-3*day,90_000L),               // 90 s against 6 minutes: length, lt180, le7
            r("long-old",now-10*day,200_000L),             // 200 s against 10 minutes: length, ge180, gt7
            r("lost-old",now-9*day,60_000L),               // no entry near: noCandidate, gt7
            r("lost-new",now-30*min,60_000L),              // noCandidate, le1
            r("paired",now-5*day,120_000L))                // a fitting entry: paired, in no bin
        val log=listOf(e(1,now-2*60*min,5*min),e(2,now-3*day,6*min),e(3,now-10*day,10*min),e(4,now-5*day,121_000L))
        var stats:FirstPass.MatchStats?=null
        val out=FirstPass.match(records,log,now) { stats=it }
        assertEquals(setOf("paired"),out.keys)
        val s=stats!!
        assertEquals(3,s.length);assertEquals(2,s.noCandidate)
        val last=s.reasons()
        val len=last.getJSONObject("lengthBy")
        assertEquals("""{"0":0,"lt60":1,"lt180":1,"ge180":1}""",norm(len.getJSONObject("recLenSec")))
        assertEquals("""{"le1":1,"le7":1,"gt7":1}""",norm(len.getJSONObject("ageDays")))
        assertEquals("""{"le1":1,"le7":0,"gt7":1}""",norm(last.getJSONObject("noCandidateBy").getJSONObject("ageDays")))
        // The heartbeat keeps the shape: every key passes PhoneStatus.counts (letters and digits only).
        val hb=PhoneStatus.counts(JSONObject().put("last",last)).getJSONObject("last")
        assertEquals(1,hb.getJSONObject("lengthBy").getJSONObject("recLenSec").getInt("ge180"))
        assertEquals(0,hb.getJSONObject("lengthBy").getJSONObject("recLenSec").getInt("0"))
        assertEquals(1,hb.getJSONObject("noCandidateBy").getJSONObject("ageDays").getInt("gt7"))
        // The stored JSON carries it under last.
        val merged=JSONObject(s.mergeInto(null,now)).getJSONObject("last")
        assertTrue(merged.has("lengthBy") && merged.has("noCandidateBy"))
    }
    @Test fun noRecordsGiveZeroBins() {
        var stats:FirstPass.MatchStats?=null
        FirstPass.match(emptyList(),emptyList(),now) { stats=it }
        val last=stats!!.reasons()
        assertEquals("""{"0":0,"lt60":0,"lt180":0,"ge180":0}""",norm(last.getJSONObject("lengthBy").getJSONObject("recLenSec")))
        assertEquals("""{"le1":0,"le7":0,"gt7":0}""",norm(last.getJSONObject("noCandidateBy").getJSONObject("ageDays")))
    }
    @Test fun theBinEdges() {
        assertEquals("0",FirstPass.recLenBin(0));assertEquals("lt60",FirstPass.recLenBin(59_999));assertEquals("lt180",FirstPass.recLenBin(60_000))
        assertEquals("lt180",FirstPass.recLenBin(179_999));assertEquals("ge180",FirstPass.recLenBin(180_000))
        assertEquals("le1",FirstPass.ageBin(day));assertEquals("le7",FirstPass.ageBin(day+1));assertEquals("le7",FirstPass.ageBin(7*day));assertEquals("gt7",FirstPass.ageBin(7*day+1))
    }
    /** The keys in a fixed order, for a plain string comparison. */
    private fun norm(o:JSONObject)=o.keys().asSequence().toList().joinToString(",","{","}") { "\"$it\":${o.getInt(it)}" }
}
