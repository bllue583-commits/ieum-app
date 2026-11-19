package kr.callbrief.pipeline

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v4.2 r23-1: the day's battery budget is a real ceiling: a run starts only while one whole piece still fits in it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class R23BudgetTest {
    private val minute=60_000L
    private val base=java.time.ZonedDateTime.of(2026,11,20,14,0,0,0,TranscriptionPower.KST).toInstant().toEpochMilli()
    private fun snap(plugged:String="none",status:String="discharging")=BatterySnapshot(60,plugged,status,false,0,null,300,false)

    @Test fun eighteenMinutesUsedDoesNotStartAFiveMinutePiece() {
        val d=PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=18*minute,pieceMs=5*minute),base)
        assertEquals("budget",(d as PowerPolicy.Decision.Wait).reason)
        assertEquals("min",PowerPolicy.budgetHit(PowerBudget(batteryRunMs=18*minute,pieceMs=5*minute)))
    }
    @Test fun twoMinutesUsedStartsWithTheRestAsTheLimit() {
        val d=PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=2*minute,pieceMs=5*minute),base) as PowerPolicy.Decision.Run
        assertEquals(PowerPolicy.Mode.BATTERY,d.mode)
        assertEquals(9*minute,d.runLimitMs)
        val late=PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=14*minute,pieceMs=5*minute),base) as PowerPolicy.Decision.Run
        assertEquals("what is left, never raised past it",6*minute,late.runLimitMs)
    }
    @Test fun withoutAMeasurementThePieceIsFiveMinutes() {
        assertEquals(5*minute,PowerPolicy.pieceMs(PowerBudget()))
        assertTrue(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=15*minute),base) is PowerPolicy.Decision.Run)
        assertEquals("budget",(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=15*minute+1),base) as PowerPolicy.Decision.Wait).reason)
    }
    @Test fun whileChargingTheBudgetDoesNotCount() {
        val d=PowerPolicy.decide(snap(plugged="ac",status="charging"),PowerBudget(batteryRunMs=19*minute,pieceMs=5*minute),base)
        assertEquals(PowerPolicy.Mode.PLUGGED,(d as PowerPolicy.Decision.Run).mode)
    }
    @Test fun theLedgerGivesTheMeasuredPiece() {
        // A long piece of 2 minutes (5 expected windows) in 180 s on 4 threads: 1.2 ms per window ms, one piece 180 s.
        val o=PowerStats.notePiece(null,180_000L,120_000L,TranscriptionPower.THREADS_OTHER,cold=true,now=base)
        assertEquals(180_000L,PowerStats.budget(o,snap(),base+minute).pieceMs)
        // A finished run is never thrown away by the new rule: its minutes are counted as before.
        val r=PowerStats.noteRun(o,base+2*minute,PowerPolicy.Mode.BATTERY,7*minute,120_000L,null)
        assertEquals(7*minute,PowerStats.budget(r,snap(),base+3*minute).batteryRunMs)
    }
}
