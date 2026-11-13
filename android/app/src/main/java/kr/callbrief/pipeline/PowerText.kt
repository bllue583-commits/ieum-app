package kr.callbrief.pipeline

import org.json.JSONObject

/** v3.6 (DESIGN_V6 3.1, 3.2, r16-2): what the screens, the notices and the heartbeat read from the power ledger r16-1 writes
 * under [PipelineSettingsKeys.TRANSCRIPTION_POWER] ([PowerStats]). Read only, pure (JVM-tested). Every missing key is 0. */
internal object PowerText {
    /** Per-call words while its recording waits for the charger (call list line, the call's notice). */
    const val CHARGER_WORDS="충전하면 이어서 정리해요"
    /** The call's words change to [CHARGER_WORDS] this long after the call ended (before it: "녹음을 찾는 중이에요"). */
    const val SAY_AFTER_MS=10*60_000L

    /** The ledger from a settings snapshot (stored as a JSON text, or an object); empty when there is none. */
    fun ledger(s:JSONObject?):JSONObject = when(val v=s?.opt(PipelineSettingsKeys.TRANSCRIPTION_POWER)) {
        is JSONObject -> v
        is String -> PowerStats.parse(v)
        else -> JSONObject() }
    fun chargingOnly(s:JSONObject?)=s?.optBoolean(PipelineSettingsKeys.TRANSCRIBE_CHARGING_ONLY,false) ?: false

    /** "today" when it is today's KST day, else null (another day: today's numbers are 0). */
    fun today(o:JSONObject?,now:Long):JSONObject? = o?.optJSONObject("today")?.takeIf { it.optInt("day")==PowerPolicy.day(now) }
    /** Minutes of battery runs today. */
    fun batteryMinToday(o:JSONObject?,now:Long):Long = today(o,now)?.optJSONObject("battery")?.optLong("min",0)?.coerceAtLeast(0) ?: 0
    /** Today's battery budget is used up ("min" or "pct" recorded, or the minutes reached). */
    fun budgetHitToday(o:JSONObject?,now:Long):Boolean {
        val t=today(o,now) ?: return false
        val hit=t.optString("budgetHit")
        return hit=="min" || hit=="pct" || (t.optJSONObject("battery")?.optLong("ms",0) ?: 0)>=TranscriptionPower.BATTERY_BUDGET_MS
    }
    /** Recordings waiting for the charger, and since when the oldest of them waits (queue entry, ms; null: unknown). */
    data class Waiting(val n:Int,val oldestMs:Long?)
    fun waiting(o:JSONObject?):Waiting {
        val w=o?.optJSONObject("waiting") ?: return Waiting(0,null)
        return Waiting(w.optInt("n",0).coerceAtLeast(0),w.optLong("oldestMs",0).takeIf { it>0 })
    }

    /** Whether a call's recording still waiting for its text waits for the charger (DESIGN_V6 2.2): not plugged in (the last
     * snapshot says "none"; unknown counts only with the switch on), and the switch on, or the battery too low or in power
     * saving, or the call not "today, short" (outside [TranscriptionPower.SHORT_MIN_MS]..[TranscriptionPower.SHORT_MAX_MS], or
     * not of today KST), or today's battery budget used up. Without the transcription DB: from the ledger [power] only. */
    fun waitsForCharger(callStartMs:Long,callDurationMs:Long,nowMs:Long,power:JSONObject?,chargingOnly:Boolean):Boolean {
        val b=power?.optJSONObject("battery")
        val plug=b?.optString("plugged")?.takeIf { it.isNotEmpty() }
        if(plug!=null && plug!="none") return false
        if(chargingOnly) return true
        if(b==null || plug==null) return false
        if(b.optInt("batteryPct",100)<TranscriptionPower.BATTERY_MIN_PCT || b.optBoolean("powerSave",false)) return true
        if(callDurationMs<TranscriptionPower.SHORT_MIN_MS || callDurationMs>TranscriptionPower.SHORT_MAX_MS) return true
        if(PowerPolicy.day(callStartMs)!=PowerPolicy.day(nowMs)) return true
        return budgetHitToday(power,nowMs)
    }

    // ---- words (DESIGN_V6 3.2) ----
    fun settingsLine(minToday:Long)="배터리를 아끼려고 긴 녹음은 충전 중에 글자로 바꿔요. 오늘 배터리로 ${minToday}분 썼어요(한도 ${TranscriptionPower.BATTERY_BUDGET_MIN}분)"
    const val SWITCH="충전 중에만 글자로 바꾸기"
    fun nowLine(n:Int)="녹음 ${n}개는 충전기에 꽂으면 글자로 바꿔요"
    fun lightLong(n:Int)="녹음 ${n}개가 이틀째 충전을 기다려요. 밤에 충전기에 꽂아 두면 정리돼요"
    fun lightGreen(n:Int)="충전하면 ${n}개를 바꿔요"
    fun eveningLine(n:Int)="충전하면 ${n}개를 이어서 정리해요"

    // ---- the heartbeat's `power` block (DESIGN_V6 3.1): numbers and short codes only ----
    private val DAY_COUNTS=listOf("gapSkips","hotSkips","inCallSkips","morningSkips","lowSkips","powerSaveSkips","chargingOnlySkips","budgetSkips",
        "plugDropped","killedChunkMs","deadlineMs","yieldCount","yieldMs","modelLoads","modelLoadMs","decodeAudioMs",
        // v3.7 r17-3: the runs' process CPU time and the charge the battery runs took ([PowerStats.noteCpu]).
        "cpuMs","cpuRuns","batteryUah")
    /** v3.6.1 (T1): the causes counted under today's `hot` ([PowerStats.noteHot]). */
    val HOT_WHY=listOf("headroom","temp","slow","severe")
    private fun n(o:JSONObject?,k:String)=(o?.opt(k) as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L
    /** One day of the ledger ([d] null: a day with nothing, [day] its KST day). */
    fun dayBlock(d:JSONObject?,day:Int):JSONObject {
        val b=d?.optJSONObject("battery");val p=d?.optJSONObject("plugged")
        // The day as "yyyy-MM-dd": a yyyymmdd number can look like a phone number to the heartbeat's leak check ("010").
        val o=JSONObject().put("day",(if(d!=null && d.optInt("day")>0) d.optInt("day") else day).let { if(it>0) "%04d-%02d-%02d".format(it/10000,it/100%100,it%100) else JSONObject.NULL })
        o.put("battery",JSONObject().put("runs",n(b,"runs")).put("ms",n(b,"ms")).put("min",n(b,"min")).put("audioMin",n(b,"audioMin"))
            .put("mAhScreenOff",(b?.opt("mAhScreenOff") as? Number)?.toLong()?.takeIf { it>0 } ?: JSONObject.NULL))
        o.put("plugged",JSONObject().put("runs",n(p,"runs")).put("ms",n(p,"ms")).put("min",n(p,"min")).put("audioMin",n(p,"audioMin")))
        o.put("budgetMin",TranscriptionPower.BATTERY_BUDGET_MIN)
        o.put("budgetHit",d?.optString("budgetHit")?.takeIf { it=="min" || it=="pct" } ?: "none")
        DAY_COUNTS.forEach { o.put(it,n(d,it)) }
        o.put("levelFallingSkips",n(d,"levelFallingRuns"))
        // v3.6.1 (T1): why the wakes found it hot (hotSkips split by cause; severe = the thermal status).
        val hot=d?.optJSONObject("hot")
        o.put("hot",JSONObject().apply { HOT_WHY.forEach { put(it,n(hot,it)) } })
        o.put("stopReasons",d?.optJSONObject("stopReasons")?.let(PhoneStatus::counts) ?: JSONObject())
        // v3.7 T5 (r17-1): the plugged-in run in the foreground, and the day's piece median per way (ms for a 2-minute piece).
        val f=d?.optJSONObject("fg")
        o.put("fg",JSONObject().put("runs",n(f,"runs")).put("min",n(f,"min")).put("refused",n(f,"refused")).put("timeout",n(f,"timeout"))
            .put("cpus",(f?.opt("cpus") as? Number) ?: JSONObject.NULL).put("threads",(f?.opt("threads") as? Number) ?: JSONObject.NULL)
            .put("cpuset",f?.optString("cpuset")?.let(PhoneStatus::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
        val pr=d?.optJSONObject("pieceRates")
        fun med(k:String)=PowerStats.median(pr?.optJSONArray(k))?.let { Math.round(it*PipelineAudioMath.MAX_CHUNK_MS) } ?: JSONObject.NULL
        o.put("pieceMedianMs",JSONObject().put("fg",med("fg")).put("bg",med("bg"))
            .put("fgN",pr?.optJSONArray("fg")?.length() ?: 0).put("bgN",pr?.optJSONArray("bg")?.length() ?: 0))
        return o
    }
    fun heartbeat(ledger:JSONObject?,chargingOnly:Boolean,now:Long):JSONObject {
        val o=JSONObject();val l=ledger ?: JSONObject()
        val b=l.optJSONObject("battery")
        fun code(k:String)=b?.optString(k)?.let(PhoneStatus::code)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL
        fun num(k:String)=(b?.opt(k) as? Number)?.takeUnless { it is Double && !it.isFinite() } ?: JSONObject.NULL
        o.put("batteryPct",num("batteryPct")).put("plugged",code("plugged")).put("status",code("status"))
            .put("powerSave",(b?.opt("powerSave") as? Boolean) ?: JSONObject.NULL).put("thermal",code("thermal"))
            .put("headroom",num("headroom")).put("tempDeciC",num("tempDeciC")).put("currentUa",num("currentUa"))
            .put("cpuset",code("cpuset")).put("cpusAllowed",num("cpusAllowed"))
        // A ledger not rolled since yesterday: its today is prev, and today is empty (PowerStats.roll's rule).
        val dayNow=PowerPolicy.day(now)
        val t=l.optJSONObject("today")
        val (today,prev)=if(t!=null && t.optInt("day")==dayNow) t to l.optJSONObject("prev")
            else null to (t?.takeIf { it.optInt("day") in 1 until dayNow } ?: l.optJSONObject("prev"))
        o.put("today",dayBlock(today,dayNow))
        o.put("prev",prev?.let { dayBlock(it,0) } ?: JSONObject.NULL)
        val c=l.optJSONObject("chunk")
        o.put("chunk",JSONObject().put("coldMedianMs",(c?.opt("coldMedianMs") as? Number) ?: JSONObject.NULL)
            .put("p50Ms",(c?.opt("p50Ms") as? Number) ?: JSONObject.NULL).put("lastMs",(c?.opt("lastMs") as? Number) ?: JSONObject.NULL)
            .put("threads",(c?.opt("threads") as? Number) ?: JSONObject.NULL)
            // The native side does not count tokens yet (r16-3).
            .put("tokensLast",JSONObject.NULL).put("perTokenMsLast",JSONObject.NULL))
        val w=waiting(l)
        o.put("waiting",JSONObject().put("n",w.n).put("oldestMin",w.oldestMs?.let { ((now-it)/60_000).coerceAtLeast(0) } ?: JSONObject.NULL))
        o.put("chargingOnly",chargingOnly)
        // v3.7 T5: the [transcribeForeground] setting the last run saw, and whether that day was a foreground day.
        o.put("fgSetting",l.optString("fgSetting").takeIf { it=="auto" || it=="on" || it=="off" } ?: JSONObject.NULL)
        o.put("fgOn",(l.opt("fgOn") as? Boolean) ?: JSONObject.NULL)
        return o
    }
}
