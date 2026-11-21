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

/** v4.3 r24-3 (DESIGN_V8 6-4): the day's battery budget from the mailbox's app/config.json `power.budgetMin` (10..60, else
 * 20), read with the bench's switch at the heartbeat, the last value kept on a network error; the piece cap and the plug
 * tail use the same minutes; the heartbeat says the minutes in force and where they came from. Generated values only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R24BudgetConfigTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L
    private val base=java.time.ZonedDateTime.of(2026,11,21,14,0,0,0,TranscriptionPower.KST).toInstant().toEpochMilli()
    private fun snap()=BatterySnapshot(60,"none","discharging",false,0,null,300,false)
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
    private fun stored()=SecureSettings(context).snapshot().opt(PipelineSettingsKeys.POWER_BUDGET_MIN)

    @Test fun noConfigIsTwentyMinutes() {
        heartbeat { throw RuntimeException("INBOX_HTTP_404") }
        assertEquals(20,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals("default",TranscriptionPower.budgetSrc);assertNull(stored())
        heartbeat { """{"bench":{"on":false}}""" }
        assertEquals(20,TranscriptionPower.BATTERY_BUDGET_MIN);assertNull(stored())
    }

    @Test fun fortyStopsAtFortyMinutes() {
        heartbeat { """{"power":{"budgetMin":40}}""" }
        assertEquals(40,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals("config",TranscriptionPower.budgetSrc);assertEquals(40,stored())
        // The policy: a five-minute piece still fits at 34 minutes, not at 36; a run's limit is what is left of forty.
        assertTrue(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=34*minute,pieceMs=5*minute),base) is PowerPolicy.Decision.Run)
        assertEquals("budget",(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=36*minute,pieceMs=5*minute),base) as PowerPolicy.Decision.Wait).reason)
        assertEquals(9*minute,(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=2*minute,pieceMs=5*minute),base) as PowerPolicy.Decision.Run).runLimitMs)
        assertEquals(6*minute,(PowerPolicy.decide(snap(),PowerBudget(batteryRunMs=34*minute,pieceMs=5*minute),base) as PowerPolicy.Decision.Run).runLimitMs)
        // The piece cap is half of it, the plug tail uses it, and the settings line says it.
        assertEquals(20*minute,PowerPolicy.PIECE_CAP_MS)
        assertEquals(20*minute,PowerPolicy.pieceMs(PowerBudget(pieceMs=30*minute)))
        assertTrue(PowerPolicy.plugDropFits(PowerBudget(batteryRunMs=30*minute),2*minute,8*minute))
        assertFalse(PowerPolicy.plugDropFits(PowerBudget(batteryRunMs=30*minute),2*minute,9*minute))
        assertTrue(PowerText.settingsLine(3),PowerText.settingsLine(3).endsWith("(한도 40분)"))
        assertTrue(PowerText.budgetHitToday(JSONObjectOf(40*minute),base));assertFalse(PowerText.budgetHitToday(JSONObjectOf(39*minute),base))
        // The heartbeat's power block.
        val p=PowerText.heartbeat(null,base)
        assertEquals(40,p.getInt("budgetMin"));assertEquals("config",p.getString("budgetSrc"));assertEquals(40,p.getJSONObject("today").getInt("budgetMin"))
        // A network error keeps the last value; the file gone (404) is the default again.
        heartbeat { throw java.io.IOException("offline") }
        assertEquals(40,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals(40,stored())
        heartbeat { throw RuntimeException("INBOX_HTTP_404") }
        assertEquals(20,TranscriptionPower.BATTERY_BUDGET_MIN);assertNull(stored())
        assertEquals("default",PowerText.heartbeat(null,base).getString("budgetSrc"))
    }

    @Test fun outOfRangeOrNotANumberIsTwenty() {
        for(bad in listOf("5","100","\"40\"","40.5","true","null")) {
            heartbeat { """{"power":{"budgetMin":40}}""" };assertEquals(40,TranscriptionPower.BATTERY_BUDGET_MIN)
            heartbeat { """{"power":{"budgetMin":$bad}}""" }
            assertEquals(bad,20,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals(bad,"default",TranscriptionPower.budgetSrc);assertNull(bad,stored())
        }
        assertNull(PowerConfig.budgetOf("{oops"));assertNull(PowerConfig.budgetOf("""{"power":40}"""))
        assertEquals(10,PowerConfig.budgetOf("""{"power":{"budgetMin":10}}"""));assertEquals(60,PowerConfig.budgetOf("""{"power":{"budgetMin":60}}"""))
        assertEquals(40,PowerConfig.budgetOf("""{"power":{"budgetMin":40.0}}"""))
    }

    @Test fun aStoredValueIsPutInForceByTheRun() {
        // A new process: the stored value (not yet read from the mailbox again) is what [TranscriptionPower.load] puts in force.
        SecureSettings(context).update { it.put(PipelineSettingsKeys.POWER_BUDGET_MIN,30) }
        TranscriptionPower.load(SecureSettings(context).snapshot())
        assertEquals(30,TranscriptionPower.BATTERY_BUDGET_MIN);assertEquals(30*minute,TranscriptionPower.BATTERY_BUDGET_MS)
        SecureSettings(context).update { it.put(PipelineSettingsKeys.POWER_BUDGET_MIN,"30") }
        TranscriptionPower.load(SecureSettings(context).snapshot())
        assertEquals(20,TranscriptionPower.BATTERY_BUDGET_MIN)
    }

    /** A power ledger whose today has [ms] of battery runs. */
    private fun JSONObjectOf(ms:Long)=org.json.JSONObject().put("today",org.json.JSONObject().put("day",PowerPolicy.day(base)).put("battery",org.json.JSONObject().put("ms",ms)))
}
