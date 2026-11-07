package kr.callbrief.pipeline

import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** v3.6 r16-2 (DESIGN_V6 3.1, 3.2): the heartbeat's power block, the 글자로 바꾸기 light at 36 hours, the per-call words
 * "충전하면 이어서 정리해요" ([PowerText.waitsForCharger], 10 minutes after the call) and the evening's last line. Generated
 * ledgers only (the layout r16-1 writes, [PowerStats]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class R16TwoTest {
    private fun iso(d:Int)="%04d-%02d-%02d".format(d/10000,d/100%100,d%100)
    private val h=3_600_000L;private val min=60_000L
    /** 2027-01-15 14:00 KST. */
    private val now=ZonedDateTime.of(2027,1,15,14,0,0,0,TranscriptionPower.KST).toInstant().toEpochMilli()
    private val today=PowerPolicy.day(now)
    private fun ledger(plugged:String="none",pct:Int=70,batteryMs:Long=5*min,hit:String?=null,day:Int=today,waiting:Pair<Int,Long?>?=null):JSONObject {
        var o=PowerStats.noteRun(JSONObject().put("today",JSONObject().put("day",day)),now,PowerPolicy.Mode.BATTERY,0,0,null)
        o.getJSONObject("today").put("day",day).put("battery",JSONObject().put("runs",2).put("ms",batteryMs).put("min",batteryMs/min).put("audioMs",90_000).put("audioMin",1).put("uahScreenOff",42_000).put("mAhScreenOff",42))
            .put("plugged",JSONObject().put("runs",3).put("ms",40*min).put("min",40).put("audioMs",20*min).put("audioMin",20))
            .put("gapSkips",4).put("hotSkips",1).put("levelFallingRuns",2).put("killedChunkMs",12_000).put("modelLoads",3).put("modelLoadMs",9_000)
            .put("stopReasons",JSONObject().put("timeout",1).put("other",2))
        hit?.let { o.getJSONObject("today").put("budgetHit",it) }
        o.put("prev",JSONObject().put("day",day-1).put("battery",JSONObject().put("min",19)))
        o=PowerStats.notePiece(o,240_000,120_000,4,true)
        o=PowerStats.noteSnapshot(o,BatterySnapshot(pct,plugged,"discharging",false,1,0.42f,365,currentUa=-350_000),4,"bg")
        waiting?.let { (n,at) -> o=PowerStats.noteWaiting(o,n,at) }
        return o
    }

    // ---- 1. the heartbeat's power block ----
    @Test fun powerBlockIsNumbersAndCodesOnlyAndAlwaysThere() {
        val input=PhoneStatus.Input(now,"3.6",1047,2,null,0,0,"READY","","",12)
        val empty=PhoneStatus.build(input).getJSONObject("power")
        assertEquals(iso(today),empty.getJSONObject("today").getString("day"))
        assertEquals(0,empty.getJSONObject("today").getJSONObject("battery").getInt("min"))
        assertEquals("none",empty.getJSONObject("today").getString("budgetHit"))
        assertEquals(0,empty.getJSONObject("waiting").getInt("n"));assertTrue(empty.getJSONObject("waiting").isNull("oldestMin"))
        assertTrue(empty.isNull("prev"));assertFalse(empty.getBoolean("chargingOnly"))

        val o=PhoneStatus.build(input.copy(powerLedger=ledger(hit="min",waiting=3 to now-90*min),chargingOnly=true)).getJSONObject("power")
        assertEquals(70,o.getInt("batteryPct"));assertEquals("none",o.getString("plugged"));assertEquals("light",o.getString("thermal"))
        assertEquals(365,o.getInt("tempDeciC"));assertEquals(4,o.getInt("cpusAllowed"));assertEquals("bg",o.getString("cpuset"))
        val t=o.getJSONObject("today")
        assertEquals(5,t.getJSONObject("battery").getInt("min"));assertEquals(42,t.getJSONObject("battery").getInt("mAhScreenOff"))
        assertEquals(40,t.getJSONObject("plugged").getInt("min"));assertEquals(20,t.getInt("budgetMin"));assertEquals("min",t.getString("budgetHit"))
        assertEquals(4,t.getInt("gapSkips"));assertEquals(2,t.getInt("levelFallingSkips"));assertEquals(12_000,t.getInt("killedChunkMs"))
        assertEquals(3,t.getInt("modelLoads"));assertEquals(1,t.getJSONObject("stopReasons").getInt("timeout"))
        assertEquals(19,o.getJSONObject("prev").getJSONObject("battery").getInt("min"))
        val c=o.getJSONObject("chunk")
        assertEquals(240_000,c.getInt("lastMs"));assertEquals(4,c.getInt("threads"));assertEquals(240_000L,c.getLong("coldMedianMs"))
        assertTrue(c.isNull("tokensLast"));assertTrue(c.isNull("perTokenMsLast"))
        assertEquals(3,o.getJSONObject("waiting").getInt("n"));assertEquals(90,o.getJSONObject("waiting").getInt("oldestMin"))
        assertTrue(o.getBoolean("chargingOnly"))
        // Numbers, yes/no and short ASCII codes only: no Korean, nothing long.
        fun walk(v:Any?) { when(v) {
            is JSONObject -> v.keys().forEach { walk(v.opt(it)) }
            is JSONArray -> (0 until v.length()).forEach { walk(v.opt(it)) }
            is String -> assertTrue(v,v.length<=24 && v.all { it.code<128 && (it.isLetterOrDigit() || it in "_-:") })
            else -> assertTrue("$v",v is Number || v is Boolean || v==JSONObject.NULL) } }
        walk(o)
    }
    @Test fun aLedgerFromYesterdayGivesEmptyTodayAndItAsPrev() {
        val o=PowerText.heartbeat(ledger(day=today-1),false,now)
        assertEquals(iso(today),o.getJSONObject("today").getString("day"));assertEquals(0,o.getJSONObject("today").getJSONObject("battery").getInt("min"))
        assertEquals(iso(today-1),o.getJSONObject("prev").getString("day"));assertEquals(5,o.getJSONObject("prev").getJSONObject("battery").getInt("min"))
        // A broken ledger still gives the block.
        assertEquals(0,PowerText.heartbeat(JSONObject().put("today","x").put("waiting",5),false,now).getJSONObject("waiting").getInt("n"))
        assertEquals(0L,PowerText.batteryMinToday(ledger(day=today-1),now));assertEquals(5L,PowerText.batteryMinToday(ledger(),now))
        // Settings keep it as a JSON text.
        val s=JSONObject().put(PipelineSettingsKeys.TRANSCRIPTION_POWER,ledger().toString())
        assertEquals(5L,PowerText.batteryMinToday(PowerText.ledger(s),now));assertEquals(0,PowerText.ledger(JSONObject()).length())
    }

    // ---- 2. the 글자로 바꾸기 light ----
    private val ok=StatusLights.Input(now=now,folderConnected=true,modelState="READY",consent=true,lastTranscribedMs=now-h,lastAnalyzedMs=now-h,lastDiscoveryMs=now)
    private fun text(i:StatusLights.Input)=StatusLights.lights(i).single { it.tag=="status_transcribe" }
    @Test fun lightIsGreenWhileWaitingForTheChargerAndOrangeAfter36Hours() {
        assertEquals(StatusLights.OK,text(ok).words)
        val young=text(ok.copy(transcribeBacklog=3,chargeWaiting=3,chargeWaitOldestMs=now-20*h,oldestQueuedMs=now-20*h,lastTranscribedMs=now-20*h))
        assertTrue(young.ok);assertEquals("충전하면 3개를 바꿔요",young.words)
        val edge=text(ok.copy(transcribeBacklog=3,chargeWaiting=3,chargeWaitOldestMs=now-TranscriptionPower.CHARGING_WAIT_WARN_MS))
        assertTrue(edge.ok)
        val long=text(ok.copy(transcribeBacklog=3,chargeWaiting=3,chargeWaitOldestMs=now-37*h,oldestQueuedMs=now-37*h,lastTranscribedMs=now-37*h))
        assertFalse(long.ok);assertEquals("녹음 3개가 이틀째 충전을 기다려요. 밤에 충전기에 꽂아 두면 정리돼요",long.words);assertNull(long.target)
        // Others waiting too, for long: the backlog words as before (worse); failures still told.
        val more=text(ok.copy(transcribeBacklog=5,chargeWaiting=3,chargeWaitOldestMs=now-10*h,oldestQueuedMs=now-10*h,lastTranscribedMs=now-10*h))
        assertTrue(more.words,more.words.startsWith("녹음 5개가 밀려 있어요"))
        assertEquals(StatusLights.textFailedWords(2),text(ok.copy(textFailed=2,chargeWaiting=1,chargeWaitOldestMs=now-h)).words)
        StatusLights.lights(ok.copy(transcribeBacklog=3,chargeWaiting=3,chargeWaitOldestMs=now-37*h)).forEach { assertFalse(it.words,Regex("[·…]").containsMatchIn(it.words)) }
    }
    @Test fun lightReadsTheLedgerFromSettings() {
        val s=JSONObject().put(PipelineSettingsKeys.TRANSCRIPTION_POWER,ledger(waiting=2 to now-40*h).toString())
        val i=StatusLights.withPhone(ok,org.robolectric.RuntimeEnvironment.getApplication(),s,null)
        assertEquals(2,i.chargeWaiting);assertEquals(now-40*h,i.chargeWaitOldestMs)
        assertFalse(text(i).ok)
    }

    // ---- 3. per call: waits for the charger, from 10 minutes after the call ----
    @Test fun waitsForChargerCases() {
        val start=now-30*min
        fun w(dur:Long,p:JSONObject?,only:Boolean=false,at:Long=start)=PowerText.waitsForCharger(at,dur,now,p,only)
        // Today, short, budget left, on battery: it goes on battery.
        assertFalse(w(60_000,ledger()))
        // Long or very short, the switch, the budget, another day.
        assertTrue(w(4*min,ledger()));assertTrue(w(10_000,ledger()))
        assertTrue(w(60_000,ledger(),only=true));assertTrue(w(60_000,ledger(hit="pct")))
        assertTrue(w(60_000,ledger(batteryMs=TranscriptionPower.BATTERY_BUDGET_MS)))
        assertTrue(w(60_000,ledger(),at=now-15*h))
        // Low battery.
        assertTrue(w(60_000,ledger(pct=30)))
        // Plugged in: nothing waits, even with the switch on.
        assertFalse(w(4*min,ledger(plugged="ac")));assertFalse(w(4*min,ledger(plugged="wireless"),only=true))
        // No ledger yet: only the switch says so; a budget from yesterday does not count.
        assertFalse(w(4*min,null));assertTrue(w(4*min,null,only=true))
        assertFalse(w(60_000,ledger(hit="min",day=today-1)))
    }
    @Test fun callListSaysChargerTenMinutesAfterTheCall() {
        val end=now-11*min;val dur=4*min
        val e=LoggedCall(1,"01000000000","박정우",end-dur,dur,CallLog.Calls.INCOMING_TYPE,false)
        val jobs=listOf(e.startMs+1_000)
        val p=ledger()
        fun words(at:Long)=CallsScreen.stateWords(e,at,jobs) { l -> PowerText.waitsForCharger(l.startMs,l.durationMs,at,p,false) }
        assertEquals(PowerText.CHARGER_WORDS,words(now))
        assertEquals(CallsScreen.TRANSCRIBING,words(end+9*min))
        // Without a queued recording in its window it is never said (no promise without a recording).
        assertEquals(CallsScreen.NO_RECORDING,CallsScreen.stateWords(e,now,emptyList()) { true })
        // A short call of today on battery with budget: still being turned into text.
        val short=e.copy(durationMs=60_000)
        assertEquals(CallsScreen.TRANSCRIBING,CallsScreen.stateWords(short,now,listOf(short.startMs)) { l -> PowerText.waitsForCharger(l.startMs,l.durationMs,now,p,false) })
    }
    @Test fun noticeWordsForACallWaitingForTheCharger() {
        val e=CallFollowupNotice.Entry(1,"김민수 대표",null,"h",now-30*min,now-26*min,state=CallFollowupNotice.CHARGER)
        val w=CallFollowupNotice.words(e,null)
        assertEquals("김민수 대표와 통화",w.title);assertEquals("충전하면 이어서 정리해요",w.text)
        assertEquals("확인할 통화",CallFollowupNotice.publicTitle(CallFollowupNotice.LATE))
        assertEquals("통화 정리 중",CallFollowupNotice.publicTitle(CallFollowupNotice.CHARGER))
        assertEquals("통화 알림 2개" to "정리 중 2건",CallFollowupNotice.summaryWords(listOf(CallFollowupNotice.CHARGER,CallFollowupNotice.WORKING)))
        assertTrue(CallFollowupNotice.waitsForCharger(e,now,ledger(),false))
        assertFalse(CallFollowupNotice.waitsForCharger(e,now,ledger(plugged="usb"),false))
    }

    // ---- 4. the evening's last line ----
    @Test fun eveningEndsWithTheChargerLine() {
        val m=CallDigestText.Message("녹음 3개 중 정리 1개, 새 체크할 일 0개","첫 줄",listOf("a","b"))
        assertSame(m,CallDigestText.withCharger(m,0))
        val c=CallDigestText.withCharger(m,2)
        assertEquals(m.title,c.title);assertEquals(m.text,c.text);assertEquals("충전하면 2개를 이어서 정리해요",c.lines.last())
        val full=CallDigestText.withCharger(m.copy(lines=(1..9).map { "$it" }),1)
        assertEquals(CallDigestText.MAX_LINES,full.lines.size);assertEquals(PowerText.eveningLine(1),full.lines.last())
    }

    // ---- 5. settings words ----
    @Test fun settingsWords() {
        assertEquals("배터리를 아끼려고 긴 녹음은 충전 중에 글자로 바꿔요. 오늘 배터리로 7분 썼어요(한도 20분)",PowerText.settingsLine(7))
        assertEquals("녹음 4개는 충전기에 꽂으면 글자로 바꿔요",PowerText.nowLine(4))
        assertEquals("충전 중에만 글자로 바꾸기",PowerText.SWITCH)
        assertFalse(PowerText.chargingOnly(JSONObject()));assertTrue(PowerText.chargingOnly(JSONObject().put(PipelineSettingsKeys.TRANSCRIBE_CHARGING_ONLY,true)))
    }
}
