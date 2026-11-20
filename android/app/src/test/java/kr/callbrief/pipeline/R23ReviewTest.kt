package kr.callbrief.pipeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** r23 review: the plug-drop tail within the battery budget, a dead battery run counted as long as it lived, the piece cap
 * and the short call's own piece, the user's yields per recording, the old notice channel. Numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class R23ReviewTest {
    private val minute=60_000L
    private val base=java.time.ZonedDateTime.of(2026,11,20,14,0,0,0,TranscriptionPower.KST).toInstant().toEpochMilli()
    private fun snap(plugged:String="none")=BatterySnapshot(60,plugged,if(plugged=="none") "discharging" else "charging",false,0,null,300,false)

    @Test fun thePlugDropTailRunsOnlyWithinTheBudget() {
        // 10 minutes used today, 2 on battery since the plug went, 5 left of the piece: 17 fits.
        assertTrue(PowerPolicy.plugDropFits(PowerBudget(batteryRunMs=10*minute),2*minute,5*minute))
        // 15 used: 22 does not.
        assertFalse(PowerPolicy.plugDropFits(PowerBudget(batteryRunMs=15*minute),2*minute,5*minute))
        // A piece past its expected end still counts the time on battery.
        assertFalse(PowerPolicy.plugDropFits(PowerBudget(batteryRunMs=19*minute),2*minute,-minute))
        assertTrue(PowerPolicy.plugDropFits(PowerBudget(batteryRunMs=20*minute-1),0,0))
    }

    @Test fun theTailIsCountedInTheHeartbeat() {
        var o=PowerStats.add(null,base,"plugDropMs",7*minute)
        o=PowerStats.add(o,base,"plugDropStops")
        val t=PowerText.heartbeat(o,base+minute).getJSONObject("today")
        assertEquals(7,t.getInt("plugDropMin"));assertEquals(1,t.getInt("plugDropStops"))
        val empty=PowerText.heartbeat(null,base).getJSONObject("today")
        assertEquals(0,empty.getInt("plugDropMin"));assertEquals(0,empty.getInt("plugDropStops"))
    }

    @Test fun aDeadRunCountsUntilItWasLastSeenAlive() {
        var o=PowerStats.noteBatteryStart(null,base)
        o=PowerStats.noteBatteryAlive(o,base+3*minute)
        // Not dead yet (it may still run): nothing booked.
        assertTrue(PowerStats.settleDead(o,base+5*minute).has("batteryRunStartMs"))
        // Before booking, the policy already counts 3 minutes, not 9.
        assertEquals(3*minute,PowerStats.budget(o,snap(),base+15*minute).batteryRunMs)
        val x=PowerStats.settleDead(o,base+15*minute)
        assertFalse(x.has("batteryRunStartMs"));assertFalse(x.has("batteryRunAliveMs"))
        val b=x.getJSONObject("today").getJSONObject("battery")
        assertEquals(3*minute,b.getLong("ms"));assertEquals(1,b.getInt("killedRuns"));assertEquals(3,b.getInt("killedMin"));assertEquals(1,b.getInt("runs"))
        assertEquals(base+3*minute,x.getLong("lastBatteryRunEndMs"))
        // Booked once: a second wake adds nothing, and the budget keeps the minutes.
        val y=PowerStats.settleDead(x,base+20*minute)
        assertEquals(3*minute,y.getJSONObject("today").getJSONObject("battery").getLong("ms"))
        assertEquals(3*minute,PowerStats.budget(y,snap(),base+20*minute).batteryRunMs)
        val hb=PowerText.heartbeat(y,base+20*minute).getJSONObject("today").getJSONObject("battery")
        assertEquals(1,hb.getInt("killedRuns"));assertEquals(3,hb.getInt("killedMin"))
    }
    @Test fun aDeadRunIsNeverMoreThanNineMinutesAndDyingInAPieceCountsItsExpectedTime() {
        var o=PowerStats.noteBatteryAlive(PowerStats.noteBatteryStart(null,base),base+30*minute)
        assertEquals(TranscriptionPower.SELF_END_MS,PowerStats.settleDead(o,base+40*minute).getJSONObject("today").getJSONObject("battery").getLong("ms"))
        // r23 review 2: dead in its first piece (expected 4 minutes): 4 minutes, not 0.
        o=PowerStats.noteBatteryAlive(PowerStats.noteBatteryStart(null,base),base,4*minute)
        val x=PowerStats.settleDead(o,base+40*minute)
        assertEquals(4*minute,x.getJSONObject("today").getJSONObject("battery").getLong("ms"));assertEquals(1,x.getJSONObject("today").getJSONObject("battery").getInt("killedRuns"))
        // The piece's end puts the real time back (here earlier than expected).
        val y=PowerStats.noteBatteryAlive(o,base+2*minute)
        assertEquals(2*minute,PowerStats.settleDead(y,base+40*minute).getJSONObject("today").getJSONObject("battery").getLong("ms"))
        // The expectation is capped too.
        val z=PowerStats.noteBatteryAlive(PowerStats.noteBatteryStart(null,base),base+minute,60*minute)
        assertEquals(TranscriptionPower.SELF_END_MS,PowerStats.settleDead(z,base+40*minute).getJSONObject("today").getJSONObject("battery").getLong("ms"))
        // Only the start mark (no piece begun): nothing.
        assertEquals(0L,PowerStats.settleDead(PowerStats.noteBatteryStart(null,base),base+40*minute).getJSONObject("today").getJSONObject("battery").getLong("ms"))
        // A run that ended as usual leaves nothing to book.
        val done=PowerStats.noteRun(PowerStats.noteBatteryStart(null,base),base+4*minute,PowerPolicy.Mode.BATTERY,4*minute,60_000,null)
        assertFalse(done.has("batteryRunStartMs"));assertFalse(done.has("batteryRunAliveMs"))
        assertEquals(4*minute,PowerStats.settleDead(done,base+60*minute).getJSONObject("today").getJSONObject("battery").getLong("ms"))
    }
    @Test fun aDeadRunOfYesterdayGoesToPrev() {
        val late=base-15*60*minute // 23:00 the day before
        val o=PowerStats.noteBatteryAlive(PowerStats.noteBatteryStart(null,late),late+2*minute)
        val x=PowerStats.settleDead(o,base)
        assertEquals(2*minute,x.getJSONObject("prev").getJSONObject("battery").getLong("ms"))
        assertFalse(x.getJSONObject("today").has("battery"))
    }

    @Test fun thePieceIsCappedAtHalfTheBudget() {
        assertEquals(10*minute,PowerPolicy.pieceMs(PowerBudget(pieceMs=15*minute)))
        assertTrue("9 + 10 fits",PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=9*minute,pieceMs=15*minute),base) is PowerPolicy.Decision.Run)
        // From the ledger: one very slow long piece (2 minutes in 20).
        val o=PowerStats.notePiece(null,20*minute,120_000L,TranscriptionPower.THREADS_OTHER,cold=true,now=base)
        assertEquals(10*minute,PowerStats.budget(o,snap(),base+minute).pieceMs)
    }
    @Test fun aShortCallHasItsOwnPiece() {
        // 40 s: 2 windows + 1 at 1.2 ms per ms = 108 s.
        assertEquals(108_000L,PowerPolicy.shortPieceMs(40_000L,1.2))
        // 3 minutes: its first piece is a whole one (2 minutes, 5 windows).
        assertEquals(PowerPolicy.splitExpectMs(120_000L,1.2),PowerPolicy.shortPieceMs(180_000L,1.2))
        // With 17 minutes used a 5-minute piece waits, a 108 s one runs.
        val b=PowerBudget(batteryRunMs=17*minute,pieceMs=5*minute)
        assertEquals("budget",(PowerPolicy.decide(snap(),b,base) as PowerPolicy.Decision.Wait).reason)
        assertTrue(PowerPolicy.decide(snap(),b.copy(pieceMs=PowerPolicy.shortPieceMs(40_000L,1.2)),base) is PowerPolicy.Decision.Run)
    }

    @Test fun theUsersYieldsAreCountedPerRecording() {
        var o:JSONObject?=null
        assertEquals(0,PowerStats.userYields(o,"r1"))
        o=PowerStats.noteUserYield(o,"r1");o=PowerStats.noteUserYield(o,"r1")
        assertEquals(TranscriptionStops.USER_YIELDS,PowerStats.userYields(o,"r1"))
        assertEquals("another recording starts again",0,PowerStats.userYields(o,"r2"))
        o=PowerStats.noteUserYield(o,"r2");assertEquals(1,PowerStats.userYields(o,"r2"));assertEquals(0,PowerStats.userYields(o,"r1"))
        assertEquals(0,PowerStats.userYields(o,null))
        o=PowerStats.noteStop(o,base,"user")
        assertEquals(1,PowerText.heartbeat(o,base).getJSONObject("today").getJSONObject("stopReasons").getInt("user"))
    }

    /** r23 review 2: a finished piece ends the stops in a row (the user's and the plug's). */
    @Test fun aFinishedPieceEndsTheStreaks() {
        var o=PowerStats.noteUserYield(PowerStats.noteUserYield(null,"r1"),"r1")
        o=PowerStats.noteStreak(PowerStats.noteStreak(o,"plugDrop","r1"),"plugDrop","r1")
        assertEquals(2,PowerStats.userYields(o,"r1"));assertEquals(PowerPolicy.PLUG_DROP_STOPS,PowerStats.streak(o,"plugDrop","r1"))
        o=PowerStats.clearStreaks(PowerStats.notePiece(o,100_000,120_000,4,false,base))
        assertEquals(0,PowerStats.userYields(o,"r1"));assertEquals(0,PowerStats.streak(o,"plugDrop","r1"))
        assertEquals(1,PowerStats.userYields(PowerStats.noteUserYield(o,"r1"),"r1"))
    }
    /** r23 review 2: the mid-piece check looks at the plug itself and the debounce only, and never stops on an unknown plug. */
    @Test fun thePlugIsGoneOnlyWhenPhysicallyOutPastTheDebounce() {
        val t=base
        assertFalse("plugged, even with the charge going down",PowerPolicy.plugGone(snap("usb"),t,t))
        assertFalse("out for a minute",PowerPolicy.plugGone(snap(),t-minute,t))
        assertTrue("out for 3 minutes",PowerPolicy.plugGone(snap(),t-3*minute,t))
        assertTrue("never seen plugged",PowerPolicy.plugGone(snap(),0,t))
        assertFalse("the phone told nothing",PowerPolicy.plugGone(snap().copy(plugKnown=false),t-3*minute,t))
    }

    @Test fun theOldNoticeChannelIsDeleted() {
        val context=RuntimeEnvironment.getApplication()
        val m=context.getSystemService(android.app.NotificationManager::class.java)
        TranscriptionStops.dropOldChannel(context) // none: quiet
        m.createNotificationChannel(android.app.NotificationChannel(TranscriptionStops.OLD_CHANNEL,"x",android.app.NotificationManager.IMPORTANCE_MIN))
        assertNotNull(m.getNotificationChannel(TranscriptionStops.OLD_CHANNEL))
        TranscriptionStops.dropOldChannel(context)
        assertNull(m.getNotificationChannel(TranscriptionStops.OLD_CHANNEL))
    }
}
