package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.bench.Bench
import kr.callbrief.pipeline.bench.BenchScheduler
import kr.callbrief.pipeline.bench.PowerConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v4.6 r27-1: the battery share (%) of the day's budget from the mailbox's app/config.json `power.budgetPct` (3..15, else
 * 5), read like `budgetMin`; [PowerPolicy.budgetHit]'s "pct" uses it; the heartbeat and the day's ledger say it; the settings
 * line does not promise minutes on a day stopped by the share. Generated values only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R27BudgetPctTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L
    private val cap=5_000_000L // a made-up battery: 5,000,000 µAh
    private val base=java.time.ZonedDateTime.of(2026,11,23,14,0,0,0,TranscriptionPower.KST).toInstant().toEpochMilli()
    @Before fun setup() { context=RuntimeEnvironment.getApplication();TranscriptionPower.resetBudgetForHost() }
    @After fun done() { TranscriptionPower.resetBudgetForHost() }

    private class Inbox(private val answer:()->String):CallInboxStore {
        override fun put(path:String,json:String) {}
        override fun replace(path:String,json:String) {}
        override fun list(dir:String):List<Pair<String,String>> = emptyList()
        override fun get(path:String):String = answer()
        override fun delete(path:String,sha:String) {}
    }
    private val sched=object:BenchScheduler { override fun benchOn(app:Context) {};override fun benchOff(app:Context) {} }
    private fun heartbeat(answer:()->String) { Bench.onHeartbeat(context,Inbox(answer),base,samples={ _,_,_ -> },work=sched) }
    private fun stored()=SecureSettings(context).snapshot().opt(PipelineSettingsKeys.POWER_BUDGET_PCT)
    /** A day of battery runs: [min] minutes, [pct]% of the made-up battery. */
    private fun used(min:Long,pct:Double)=PowerBudget(batteryRunMs=min*minute,pieceMs=minute,batteryUah=(cap*pct/100).toLong(),capacityUah=cap)

    @Test fun defaultFiveStopsAtFivePercent() {
        heartbeat { """{"bench":{"on":false}}""" }
        assertEquals(5,TranscriptionPower.BATTERY_BUDGET_PCT);assertEquals("default",TranscriptionPower.budgetPctSrc);assertNull(stored())
        assertNull(PowerPolicy.budgetHit(used(10,4.9)))
        assertEquals("pct",PowerPolicy.budgetHit(used(10,5.0)))
    }

    @Test fun tenGoesOnFromFiveToNineAndStopsAtTen() {
        heartbeat { """{"power":{"budgetMin":40,"budgetPct":10}}""" }
        assertEquals(10,TranscriptionPower.BATTERY_BUDGET_PCT);assertEquals("config",TranscriptionPower.budgetPctSrc);assertEquals(10,stored())
        assertEquals(40,TranscriptionPower.BATTERY_BUDGET_MIN)
        for(p in listOf(5.0,7.0,9.0,9.99)) assertNull("$p%",PowerPolicy.budgetHit(used(22,p)))
        assertEquals("pct",PowerPolicy.budgetHit(used(22,10.0)))
        // The minutes still come first when they run out (the rest of the policy is the same).
        assertEquals("min",PowerPolicy.budgetHit(used(40,6.0)))
        // decide: the 11/23 shape (22 minutes, 5% of the battery) runs on with 10, waits with the default.
        val s=BatterySnapshot(60,"none","discharging",false,0,null,300,false)
        assertTrue(PowerPolicy.decide(s,used(22,5.0),base) is PowerPolicy.Decision.Run)
        // The heartbeat's power block.
        val beat=PowerText.heartbeat(null,base)
        assertEquals(10,beat.getInt("budgetPct"));assertEquals("config",beat.getString("budgetPctSrc"))
        assertEquals(10,beat.getJSONObject("today").getInt("budgetPct"))
        // A network error keeps the last value; the file gone is the default again.
        heartbeat { throw java.io.IOException("offline") }
        assertEquals(10,TranscriptionPower.BATTERY_BUDGET_PCT);assertEquals(10,stored())
        heartbeat { throw RuntimeException("INBOX_HTTP_404") }
        assertEquals(5,TranscriptionPower.BATTERY_BUDGET_PCT);assertNull(stored())
        assertEquals("pct",PowerPolicy.budgetHit(used(10,5.0)))
        assertEquals("default",PowerText.heartbeat(null,base).getString("budgetPctSrc"))
    }

    @Test fun outOfRangeOrNotANumberIsFive() {
        for(bad in listOf("2","16","\"10\"","null","10.5","true")) {
            heartbeat { """{"power":{"budgetPct":10}}""" };assertEquals(10,TranscriptionPower.BATTERY_BUDGET_PCT)
            heartbeat { """{"power":{"budgetPct":$bad}}""" }
            assertEquals(bad,5,TranscriptionPower.BATTERY_BUDGET_PCT);assertEquals(bad,"default",TranscriptionPower.budgetPctSrc);assertNull(bad,stored())
        }
        assertEquals(3,PowerConfig.budgetPctOf("""{"power":{"budgetPct":3}}"""));assertEquals(15,PowerConfig.budgetPctOf("""{"power":{"budgetPct":15}}"""))
        assertEquals(10,PowerConfig.budgetPctOf("""{"power":{"budgetPct":10.0}}"""))
        assertNull(PowerConfig.budgetPctOf("{oops"))
    }

    @Test fun anOldConfigWithMinutesOnlyIsAsBefore() {
        heartbeat { """{"power":{"budgetMin":40}}""" }
        assertEquals(40,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals("config",TranscriptionPower.budgetSrc)
        assertEquals(5,TranscriptionPower.BATTERY_BUDGET_PCT);assertEquals("default",TranscriptionPower.budgetPctSrc);assertNull(stored())
        assertEquals("pct",PowerPolicy.budgetHit(used(22,5.0)))
        // The share alone leaves the minutes at their default.
        heartbeat { """{"power":{"budgetPct":8}}""" }
        assertEquals(20,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals(8,TranscriptionPower.BATTERY_BUDGET_PCT)
    }

    @Test fun aStoredValueIsPutInForceAndEachDayKeepsItsOwn() {
        SecureSettings(context).update { it.put(PipelineSettingsKeys.POWER_BUDGET_PCT,12) }
        TranscriptionPower.load(SecureSettings(context).snapshot())
        assertEquals(12,TranscriptionPower.BATTERY_BUDGET_PCT)
        val o=PowerStats.noteRun(null,base,PowerPolicy.Mode.BATTERY,60_000L,60_000L,null)
        assertEquals(12,o.getJSONObject("today").getInt("budgetPct"))
        TranscriptionPower.resetBudgetForHost()
        val next=PowerStats.noteRun(o,base+86_400_000L,PowerPolicy.Mode.BATTERY,60_000L,60_000L,null)
        val beat=PowerText.heartbeat(next,base+86_400_000L)
        assertEquals(5,beat.getJSONObject("today").getInt("budgetPct"))
        assertEquals("yesterday says its own",12,beat.getJSONObject("prev").getInt("budgetPct"))
    }

    @Test fun theSettingsLineDoesNotPromiseMinutesOnADayStoppedByTheShare() {
        val day=PowerPolicy.day(base)
        val l=org.json.JSONObject().put("today",org.json.JSONObject().put("day",day).put("budgetHit","pct").put("budgetMin",40)
            .put("battery",org.json.JSONObject().put("ms",22*minute).put("min",22)))
        assertTrue(PowerText.pctHitToday(l,base))
        val line=PowerText.settingsLine(PowerText.batteryMinToday(l,base),PowerText.dayBudget(PowerText.today(l,base)),PowerText.pctHitToday(l,base))
        assertTrue(line,line.contains("22분 썼어요"));assertFalse(line,line.contains("한도"));assertTrue(line,line.contains("충전할 때"))
        // Not stopped, or stopped by the minutes: the line as before.
        assertTrue(PowerText.settingsLine(22,40,false).endsWith("(한도 40분)"))
        l.getJSONObject("today").put("budgetHit","min")
        assertFalse(PowerText.pctHitToday(l,base))
    }
}
