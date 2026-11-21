package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v4.3 r24-5 (DESIGN_V8 6-4): off the charger a plugged-in run stops its piece after 60 s, not 2 minutes; a wireless pad's
 * short drops (seconds to some tens of seconds) are not taken for the plug going away. Generated times only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class R24PlugTailTest {
    private val sec=1_000L
    private val base=java.time.ZonedDateTime.of(2026,11,21,14,0,0,0,TranscriptionPower.KST).toInstant().toEpochMilli()
    private fun snap(plugged:String)=BatterySnapshot(60,plugged,if(plugged=="none") "discharging" else "charging",false,0,null,300,false)
    private val off=snap("none");private val pad=snap("wireless")

    @Test fun theTailIsSixtySeconds() {
        assertEquals(60*sec,TranscriptionPower.PLUG_DEBOUNCE_MS)
        // Last seen on the pad at base: gone from 60 s on (it was 2 minutes).
        assertFalse(PowerPolicy.plugGone(off,base,base+59*sec))
        assertTrue(PowerPolicy.plugGone(off,base,base+60*sec))
        assertTrue("90 s off the charger: no longer plugged in",PowerPolicy.plugGone(off,base,base+90*sec))
        // Between pieces the same: the run counts as plugged in for 60 s, then the battery rules hold.
        assertTrue(PowerPolicy.plugged(off,PowerBudget(lastPluggedMs=base),base+59*sec))
        assertFalse(PowerPolicy.plugged(off,PowerBudget(lastPluggedMs=base),base+60*sec))
    }

    /** The watch looks every 20 s ([CallTranscriptionRunner]) and notes the plug each time it is in; a pad that drops for
     * [dropS] seconds starting [startS] seconds after the last look. True when some look took the plug for gone. */
    private fun dropSeenAsGone(dropS:Long,startS:Long):Boolean {
        var last=base;var t=base
        while(t<=base+(startS+dropS+60)*sec) {
            t+=20*sec
            val inDrop=t>=base+startS*sec && t<base+(startS+dropS)*sec
            if(!inDrop) { last=t;continue }
            if(PowerPolicy.plugGone(off,last,t)) return true
        }
        return false
    }
    @Test fun aWirelessPadsShortDropsAreNotTakenForGone() {
        for(drop in listOf(2L,5L,15L,30L,40L)) for(start in 0L until 20L)
            assertFalse("a drop of $drop s starting $start s after a look",dropSeenAsGone(drop,start))
        // A real lift-off (several minutes) is seen within 80 s of the last look on the pad.
        assertTrue(dropSeenAsGone(300,1))
        assertFalse(PowerPolicy.plugGone(pad,base,base+5*60*sec))
    }
}
