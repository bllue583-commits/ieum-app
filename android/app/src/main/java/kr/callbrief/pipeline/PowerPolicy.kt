package kr.callbrief.pipeline

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** v3.6 (DESIGN_V6 2, r16-0): the numbers of the transcription power policy. */
internal object TranscriptionPower {
    /** Battery runs only from this charge up (2.4). Below it the queue waits for the charger. */
    const val BATTERY_MIN_PCT=40
    /** A day's battery budget: this many minutes of runs that started a piece, or this share of the battery, whichever comes first.
     * v4.3 r24-3 (DESIGN_V8 6-4): the minutes come from the mailbox's app/config.json `power.budgetMin` (a whole number
     * [BUDGET_MIN_LOW]..[BUDGET_MIN_HIGH], kept in settings [PipelineSettingsKeys.POWER_BUDGET_MIN] as last read), else
     * [DEFAULT_BUDGET_MIN]. [load] puts the stored value in force (a transcription run, the heartbeat, the settings screen read it). */
    const val DEFAULT_BUDGET_MIN=20
    const val BUDGET_MIN_LOW=10
    const val BUDGET_MIN_HIGH=60
    @Volatile var budgetMin:Int=DEFAULT_BUDGET_MIN;private set
    /** "config" when [budgetMin] came from the mailbox's config, else "default" (the heartbeat's `power.budgetSrc`). */
    val budgetSrc:String get()=if(fromConfig) "config" else "default"
    @Volatile private var fromConfig=false
    val BATTERY_BUDGET_MIN:Int get()=budgetMin
    val BATTERY_BUDGET_MS:Long get()=budgetMin*60_000L
    const val BATTERY_BUDGET_PCT=5
    /** [v] as a budget: a whole number in range, else null (a string, a fraction, out of range: the default holds). */
    fun validBudget(v:Any?):Int? = when(v) {
        is Int -> v;is Long -> v.toInt().takeIf { it.toLong()==v };is Double -> v.toInt().takeIf { it.toDouble()==v };else -> null
    }?.takeIf { it in BUDGET_MIN_LOW..BUDGET_MIN_HIGH }
    /** Puts the stored budget ([PipelineSettingsKeys.POWER_BUDGET_MIN] in the settings [s]) in force. */
    fun load(s:JSONObject?) { val v=validBudget(s?.opt(PipelineSettingsKeys.POWER_BUDGET_MIN));budgetMin=v ?: DEFAULT_BUDGET_MIN;fromConfig=v!=null }
    /** Host tests: the default again. */
    internal fun resetBudgetForHost() { budgetMin=DEFAULT_BUDGET_MIN;fromConfig=false }
    /** Battery runs at least this far apart (heat, and no "every call starts it at once" loop). */
    const val BATTERY_RUN_GAP_MS=30*60_000L
    /** "Today, short" on battery: calls of this length (from the call log, else estimated from the file size). */
    const val SHORT_MIN_MS=20_000L
    const val SHORT_MAX_MS=3*60_000L
    /** A plug that dropped out for less than this still counts as plugged in (wireless pads). v4.3 r24-5 (DESIGN_V8 6-4): 2
     * minutes -> 60 s: each time the phone came off the charger a plugged-in run went on 2 to 3 minutes on the battery (11/20,
     * 11/21: 3 minutes a day). With the mid-piece look every 20 s, a pad's drop of up to 40 s is never taken for gone. */
    const val PLUG_DEBOUNCE_MS=60_000L
    /** Hot: thermal headroom at least this, or the last piece this many times the cold median, or the battery this warm (0.1 °C). */
    const val HOT_HEADROOM=0.85f
    const val HOT_RATIO=1.5
    const val HOT_TEMP_DECI_C=400
    /** While plugged in and hot: try again this much later (an explicit delay, not WorkManager's backoff). v3.6.1 (T1): one
     * such rest clears a "slow" verdict, and the first piece after it is a cold one ([PowerStats.slow], [PowerStats.coldStart]),
     * so one slow piece costs one rest, not two ([COLD_AFTER_MS] is longer than this delay). */
    const val HOT_DELAY_MS=10*60_000L
    /** 05:00 to 07:00 KST while plugged in: charging first unless the battery is at least this full. */
    const val MORNING_REST_FROM_HOUR=5
    const val MORNING_REST_TO_HOUR=7
    const val MORNING_REST_PCT=90
    /** A recording waiting for the charger longer than this turns the 글자로 바꾸기 light orange. */
    const val CHARGING_WAIT_WARN_MS=36*3_600_000L
    /** W1: a run ends itself before this (a minute before WorkManager's 10 minute limit). */
    const val SELF_END_MS=9*60_000L
    /** W1: the expected length of a piece is the measured one times this (a margin). */
    const val PIECE_MARGIN=1.25
    /** W2: a piece expected to take more than this share of the native deadline is done as two halves from the start. */
    const val SPLIT_SHARE=0.8
    /** The plug falls short (car USB, a PC port) when the charge dropped this much over [LEVEL_WINDOW_MS] while plugged in. */
    const val LEVEL_WINDOW_MS=10*60_000L
    /** Thread caps: wired charger 6, wireless pad and battery 4 (P7 then lowers them to the cores the process may use). */
    const val THREADS_WIRED=6
    const val THREADS_OTHER=4
    /** A piece that starts a run at least this long after the previous run is a "cold" piece (its speed is the reference). */
    const val COLD_AFTER_MS=20*60_000L
    /** Without a measurement: ms of work per ms of audio (2-minute piece in 300 s, DESIGN_V6 1.1). v3.6.1 (T1): per ms of
     * encoder windows ([WINDOW_MS]). v3.6.1 fix (review, high): per ms of the expected windows, one more than the audio fills
     * ([PowerPolicy.expectedWindows]): a 2-minute piece (5 windows) still 300 s. */
    const val DEFAULT_RATE=2.0
    /** v3.6.1 fix (review, high): only pieces of at least this much audio say how long a whole piece takes (W1, W2); the cost
     * of a tail or a short call follows its words, so it reads low per window. 60 s (final review): the halves of a split
     * 2-minute piece count too, or a slow measurement that split the pieces could never be replaced by a faster one. */
    const val LONG_PIECE_MS=60_000L
    /** v3.6.1 (T1, DESIGN_V7 2): Whisper encodes a whole 30-second window however little audio is in it, so a piece costs by
     * its windows: speed = work ms per (windows x 30 s), and the expected time of a piece = speed x windows x 30 s. */
    const val WINDOW_MS=30_000L
    val KST:ZoneId=ZoneId.of("Asia/Seoul")
}

/** What the phone's power looks like now (DESIGN_V6 2.1). Read by the app itself, not through WorkManager constraints. */
internal data class BatterySnapshot(
    val pct:Int,
    /** ac | usb | wireless | none ([BatteryManager.EXTRA_PLUGGED]; a dock counts as ac). */
    val plugged:String,
    /** charging | discharging | notCharging | full | unknown. Samsung's battery protection shows notCharging/full while plugged. */
    val status:String="unknown",
    val powerSave:Boolean=false,
    /** [PowerManager.getCurrentThermalStatus], 0 when unknown. */
    val thermal:Int=0,
    /** [PowerManager.getThermalHeadroom] (API 30+), null when unknown. */
    val headroom:Float?=null,
    val tempDeciC:Int?=null,
    val inCall:Boolean=false,
    val screenOn:Boolean=false,
    /** µAh left ([BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER]), null when the phone does not tell (0 or less). */
    val chargeUah:Long?=null,
    /** µA ([BatteryManager.BATTERY_PROPERTY_CURRENT_NOW]); sign and unit differ between phones, recorded only. */
    val currentUa:Long?=null,
    /** r23 review 2: false when the phone told nothing of the plug (neither the battery broadcast nor BatteryManager). */
    val plugKnown:Boolean=true,
) {
    val isPlugged get()=plugged!="none"
    companion object {
        fun pluggedCode(v:Int)=when { v and BatteryManager.BATTERY_PLUGGED_WIRELESS!=0 -> "wireless";v and BatteryManager.BATTERY_PLUGGED_USB!=0 -> "usb";v!=0 -> "ac";else -> "none" }
        fun statusCode(v:Int)=when(v) { BatteryManager.BATTERY_STATUS_CHARGING -> "charging";BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "notCharging";BatteryManager.BATTERY_STATUS_FULL -> "full";else -> "unknown" }
        fun thermalCode(v:Int)=when(v) { 0 -> "none";1 -> "light";2 -> "moderate";3 -> "severe";4 -> "critical";5 -> "emergency";6 -> "shutdown";else -> "unknown" }
        /** The sticky ACTION_BATTERY_CHANGED plus PowerManager; everything optional is null when the phone does not say. */
        fun read(context:Context):BatterySnapshot {
            val i=runCatching { context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            val bm=context.getSystemService(BatteryManager::class.java)
            val pm=context.getSystemService(PowerManager::class.java)
            val level=i?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1;val scale=i?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
            val pct=if(level>=0 && scale>0) level*100/scale else bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
            val plugged=pluggedCode(i?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: if(bm?.isCharging==true) BatteryManager.BATTERY_PLUGGED_AC else 0)
            val status=statusCode(i?.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN)
            val temp=i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE)?.takeIf { it!=Int.MIN_VALUE }
            val thermal=if(Build.VERSION.SDK_INT>=29) runCatching { pm?.currentThermalStatus ?: 0 }.getOrDefault(0) else 0
            val headroom=if(Build.VERSION.SDK_INT>=30) runCatching { pm?.getThermalHeadroom(30) }.getOrNull()?.takeIf { it.isFinite() && it>=0f } else null
            val charge=runCatching { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()?.takeIf { it>0 && it!=Long.MIN_VALUE }
            val current=runCatching { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()?.takeIf { it!=Long.MIN_VALUE && it!=0L }
            return BatterySnapshot(pct.coerceIn(0,100),plugged,status,runCatching { pm?.isPowerSaveMode==true }.getOrDefault(false),thermal,headroom,temp,
                inCall(context),runCatching { pm?.isInteractive==true }.getOrDefault(false),charge,current,plugKnown=i!=null || bm!=null)
        }
        private fun inCall(context:Context):Boolean {
            if(context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return runCatching { context.getSystemService(android.telecom.TelecomManager::class.java)?.isInCall==true }.getOrDefault(false)
        }
    }
}

/** What the policy remembers between runs (kept in [PowerStats], passed in so [PowerPolicy.decide] stays pure). */
internal data class PowerBudget(
    /** Today's (KST) battery run time and charge, of runs that started a piece. */
    val batteryRunMs:Long=0,
    val batteryUah:Long=0,
    /** Full battery in µAh when it can be told (charge counter / percent), else null: then only minutes count. */
    val capacityUah:Long?=null,
    /** When the last battery run that started a piece ended (0: none). */
    val lastBatteryRunEndMs:Long=0,
    /** When a plug was last seen (for [TranscriptionPower.PLUG_DEBOUNCE_MS]). */
    val lastPluggedMs:Long=0,
    /** Plugged in, yet the charge went down over [TranscriptionPower.LEVEL_WINDOW_MS] ([PowerStats.levelFalling]). */
    val levelFalling:Boolean=false,
    /** The last piece ran at least [TranscriptionPower.HOT_RATIO] times the cold median ([PowerStats.slow]). */
    val slow:Boolean=false,
    /** v4.2 r23-1: how long one whole piece is expected to take on battery (the recent median speed, no margin), 0: unknown
     * ([TranscriptionPower.DEFAULT_RATE] then). A battery run starts only while at least this much of the day's budget is left. */
    val pieceMs:Long=0,
)

/** v3.6 (DESIGN_V6 2.2): whether a transcription run may start now and how. Pure: everything comes in as arguments. */
internal object PowerPolicy {
    enum class Mode { PLUGGED, BATTERY }
    sealed class Decision {
        /** Run in [mode]: on battery only today's short calls, newest first; [runLimitMs] is how long the run may go (W1),
         * [threads] the cap before P7. */
        data class Run(val mode:Mode,val threads:Int,val runLimitMs:Long,val plug:String):Decision()
        /** Do nothing now. [reason] is counted (gap, hot, inCall, morning, budget, low, powerSave);
         * [retryInMs]: queue the run again after this long, null: the charger trigger or the hourly tick will. */
        data class Wait(val reason:String,val retryInMs:Long?):Decision()
    }

    /** Plugged in for the policy: a plug now, or one seen within the debounce; not when the charge goes down while plugged. */
    fun plugged(s:BatterySnapshot,b:PowerBudget,now:Long)=(s.isPlugged || b.lastPluggedMs>0 && now-b.lastPluggedMs in 0 until TranscriptionPower.PLUG_DEBOUNCE_MS) && !b.levelFalling
    fun hot(s:BatterySnapshot,b:PowerBudget)=hotWhy(s,b)!=null
    /** v3.6.1 (T1): which of the three makes it hot, the first that holds: headroom, temp, slow (null: not hot). Counted under
     * today's `hot` in the ledger, so the heartbeat tells a real heat from a slow-piece verdict. */
    fun hotWhy(s:BatterySnapshot,b:PowerBudget):String? = when {
        (s.headroom ?: 0f)>=TranscriptionPower.HOT_HEADROOM -> "headroom"
        (s.tempDeciC ?: 0)>=TranscriptionPower.HOT_TEMP_DECI_C -> "temp"
        b.slow -> "slow"
        else -> null }
    fun morning(now:Long)=Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST).hour.let { it>=TranscriptionPower.MORNING_REST_FROM_HOUR && it<TranscriptionPower.MORNING_REST_TO_HOUR }
    private fun untilSeven(now:Long):Long {
        val z=Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST)
        val seven=z.toLocalDate().atTime(TranscriptionPower.MORNING_REST_TO_HOUR,0).atZone(TranscriptionPower.KST).toInstant().toEpochMilli()
        return (seven-now).coerceIn(60_000L,TranscriptionPower.BATTERY_RUN_GAP_MS)
    }
    /** v4.2 r23-1: one whole piece as [PowerBudget.pieceMs] says, else at the default speed (5 minutes). r23 review: never above
     * half the budget, so one median that jumped does not starve the battery runs for the day. */
    fun pieceMs(b:PowerBudget)=(b.pieceMs.takeIf { it>0 } ?: PowerPolicy.splitExpectMs(PipelineAudioMath.MAX_CHUNK_MS,null)).coerceAtMost(PIECE_CAP_MS)
    val PIECE_CAP_MS:Long get()=TranscriptionPower.BATTERY_BUDGET_MS/2
    /** r23 review: the expected time of the first piece of a short call of [lenMs] (a battery run takes those only): its own
     * windows, at most a whole piece's. */
    fun shortPieceMs(lenMs:Long,rate:Double?)=PowerPolicy.splitExpectMs(lenMs.coerceIn(1L,PipelineAudioMath.MAX_CHUNK_MS),rate)
    /** r23 review (r23-1 completed): a plugged-in run whose plug went away mid-piece finishes that piece only when the day's
     * battery budget holds what it used already, the time on battery since the plug went ([tailMs]) and what the piece is
     * still expected to take ([remainingMs]). Not a switch (v4.0's UnplugStop): the budget decides. */
    /** r23 review 2: the plug is gone for the mid-piece check: physically out ([BatterySnapshot.isPlugged]) for longer than
     * the debounce. Not [levelFalling] (a charge going down is left to the check between pieces), and never when the phone
     * did not tell. */
    fun plugGone(s:BatterySnapshot,lastPluggedMs:Long,now:Long)=s.plugKnown && !s.isPlugged &&
        !(lastPluggedMs>0 && now-lastPluggedMs in 0 until TranscriptionPower.PLUG_DEBOUNCE_MS)
    /** r23 review 2: a piece stopped this many times in a row for the plug going away runs to its end the next time (a
     * wireless pad put on and taken off often must not make the same piece again and again). */
    const val PLUG_DROP_STOPS=2
    fun plugDropFits(b:PowerBudget,tailMs:Long,remainingMs:Long)=
        b.batteryRunMs+tailMs.coerceAtLeast(0)+remainingMs.coerceAtLeast(0)<=TranscriptionPower.BATTERY_BUDGET_MS
    /** The battery budget is used up: [TranscriptionPower.BATTERY_BUDGET_MIN] minutes, or [TranscriptionPower.BATTERY_BUDGET_PCT]% of the battery.
     * v4.2 r23-1: "min" also when less than one piece ([pieceMs]) of the minutes is left. Before, the run limit was raised to
     * a minute and the first piece of a run always starts, so a run begun at 19 minutes did a whole piece (5 minutes and more)
     * and the day ended at 29 and 34 minutes (11/16, 11/19). */
    fun budgetHit(b:PowerBudget):String? = when {
        b.batteryRunMs+pieceMs(b)>TranscriptionPower.BATTERY_BUDGET_MS -> "min"
        b.capacityUah!=null && b.capacityUah>0 && b.batteryUah*100>=b.capacityUah*TranscriptionPower.BATTERY_BUDGET_PCT -> "pct"
        else -> null
    }

    /** SEVERE heat or worse (it stopped every run before v3.6 too): rest 20 minutes whatever the plug says. */
    const val SEVERE=3
    /** [midRun]: asked between pieces of a run already going ([PowerStats.stillRuns]); the plug debounce then holds as before. */
    fun decide(s:BatterySnapshot,b:PowerBudget,now:Long,midRun:Boolean=false):Decision {
        if(s.thermal>=SEVERE) return Decision.Wait("hot",TranscriptionPower.HOT_DELAY_MS*2)
        val plugged=plugged(s,b,now)
        // v4.1 r22-3: the switch "충전 중에만" is gone (user 11/19): the battery runs within the guards below, always.
        if(plugged) {
            if(hot(s,b)) return Decision.Wait("hot",TranscriptionPower.HOT_DELAY_MS)
            // v3.7 (T3, review r16-1 #5): Samsung's protection (85%) holds the battery at notCharging/full below 90%: no rest then.
            if(morning(now) && s.pct<TranscriptionPower.MORNING_REST_PCT && s.status!="full" && s.status!="notCharging") return Decision.Wait("morning",untilSeven(now))
            val wireless=s.plugged=="wireless"
            return Decision.Run(Mode.PLUGGED,if(wireless) TranscriptionPower.THREADS_OTHER else TranscriptionPower.THREADS_WIRED,TranscriptionPower.SELF_END_MS,if(s.isPlugged) s.plugged else "debounce")
        }
        if(s.pct<TranscriptionPower.BATTERY_MIN_PCT) return Decision.Wait("low",null)
        if(s.powerSave) return Decision.Wait("powerSave",null)
        if(s.inCall) return Decision.Wait("inCall",null)
        if(hot(s,b)) return Decision.Wait("hot",null)
        if(budgetHit(b)!=null) return Decision.Wait("budget",null)
        if(b.lastBatteryRunEndMs>0) {
            val since=now-b.lastBatteryRunEndMs
            if(since in 0 until TranscriptionPower.BATTERY_RUN_GAP_MS) return Decision.Wait("gap",TranscriptionPower.BATTERY_RUN_GAP_MS-since)
        }
        // At least one piece is left (budgetHit): the first piece fits, the next ones are checked against what is left (W1).
        val left=TranscriptionPower.BATTERY_BUDGET_MS-b.batteryRunMs
        return Decision.Run(Mode.BATTERY,TranscriptionPower.THREADS_OTHER,minOf(TranscriptionPower.SELF_END_MS,left),"none")
    }

    /** P7: threads never above the cores the process may run on ([coresAllowed], null: unknown). */
    fun threads(cap:Int,coresAllowed:Int?)=if(coresAllowed==null || coresAllowed<=0) cap else minOf(cap,coresAllowed).coerceAtLeast(1)

    /** v3.6.1 (T1): the encoder windows a piece of [audioMs] takes (a 7-second tail is one window, a 2-minute piece four). */
    fun windows(audioMs:Long)=if(audioMs<=0) 0L else (audioMs+TranscriptionPower.WINDOW_MS-1)/TranscriptionPower.WINDOW_MS
    /** v3.6.1 (T1): the audio a piece is charged for: its windows x 30 s. */
    fun windowMs(audioMs:Long)=windows(audioMs)*TranscriptionPower.WINDOW_MS
    /** v3.6.1 (T1): the speed of a finished piece, work ms per window ms (null for nothing done). */
    fun rate(workMs:Long,audioMs:Long):Double? = if(workMs<=0 || audioMs<=0) null else workMs.toDouble()/windowMs(audioMs)
    /** v3.6.1 fix (review, high): the windows a piece is expected to take for W1 and W2: one more than its audio fills
     * (Whisper moves on by its timestamps, so a 2-minute piece often takes 5 windows, not 4). */
    fun expectedWindows(audioMs:Long)=if(audioMs<=0) 0L else windows(audioMs)+1
    /** v3.6.1 fix: the speed of a finished piece as W1/W2 count it: work ms per ms of [expectedWindows]. */
    fun expectRate(workMs:Long,audioMs:Long):Double? = if(workMs<=0 || audioMs<=0) null else workMs.toDouble()/(expectedWindows(audioMs)*TranscriptionPower.WINDOW_MS)
    /** W1: how long a piece of [audioMs] audio is expected to take at [rate] (work ms per expected window ms,
     * [PowerStats.rate]), with the margin. v3.6.1 fix: [expectedWindows] x 30 s. */
    fun expectedPieceMs(audioMs:Long,rate:Double?)=(expectedWindows(audioMs)*TranscriptionPower.WINDOW_MS*(rate ?: TranscriptionPower.DEFAULT_RATE)*TranscriptionPower.PIECE_MARGIN).toLong()
    /** W1: start this piece? The first piece of a run always starts (else a hot phone would only wake and end, DESIGN_V6 W1). */
    fun startPiece(firstOfRun:Boolean,elapsedMs:Long,expectedMs:Long,limitMs:Long)=firstOfRun || elapsedMs+expectedMs<=limitMs
    /** W2: split a whole piece in two before it is begun when it is expected to run into the native deadline. */
    fun splitAhead(expectedMs:Long,deadlineSeconds:Int)=expectedMs>deadlineSeconds*1000L*TranscriptionPower.SPLIT_SHARE
    /** v3.6.1 fix (final review): what W2 compares: the expectation without [TranscriptionPower.PIECE_MARGIN]. [SPLIT_SHARE]
     * is already its margin; with both, a 2-minute piece measured at 376 s (the S26, under the 540 s deadline) read as 470 s
     * and every whole piece was halved. W1 keeps the margin. */
    fun splitExpectMs(audioMs:Long,rate:Double?)=(expectedWindows(audioMs)*TranscriptionPower.WINDOW_MS*(rate ?: TranscriptionPower.DEFAULT_RATE)).toLong()

    /** KST yyyymmdd. */
    fun day(now:Long)=Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST).toLocalDate().let { it.year*10000+it.monthValue*100+it.dayOfMonth }
    fun dayStartMs(now:Long)=Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST).toLocalDate().atStartOfDay(TranscriptionPower.KST).toInstant().toEpochMilli()
}

/** P7 (DESIGN_V6 2.3, 1.3): which cores this process may use, read from /proc (the same as sched_getaffinity for this thread). */
internal object CpuSet {
    /** "0-3,6" -> 5; null when it cannot be read. */
    fun count(list:String?):Int? {
        if(list.isNullOrBlank()) return null
        var n=0
        for(part in list.trim().split(',')) {
            val p=part.trim();if(p.isEmpty()) continue
            val a=p.substringBefore('-').toIntOrNull() ?: return null;val z=p.substringAfter('-',p).toIntOrNull() ?: return null
            if(z<a) return null;n+=z-a+1
        }
        return n.takeIf { it>0 }
    }
    /** Cpus_allowed_list of /proc/self/status. */
    fun allowed(status:String?):Int? = count(status?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':'))
    /** "/top-app" -> "top-app", "/" -> "root"; short codes only (no paths kept). */
    fun code(cpuset:String?):String? = cpuset?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> s.trimEnd('/').substringAfterLast('/').ifEmpty { "root" }.filter { it.isLetterOrDigit() || it=='-' || it=='_' }.take(24) }
    fun readAllowed():Int? = runCatching { allowed(java.io.File("/proc/self/status").readText()) }.getOrNull()
    fun readCode():String? = runCatching { code(java.io.File("/proc/self/cpuset").readText()) }.getOrNull()
}

/** v3.6 (DESIGN_V6 3.1): the power ledger under [PipelineSettingsKeys.TRANSCRIPTION_POWER]. Pure JSON helpers (JVM-tested).
 * Layout: {"today":{day, battery{runs,ms,min,audioMs,audioMin,mAhScreenOff}, plugged{runs,ms,min,audioMs,audioMin}, budgetHit,
 * gapSkips,hotSkips,inCallSkips,morningSkips,lowSkips,powerSaveSkips,budgetSkips,levelFallingRuns,plugDropped,
 * killedChunkMs,deadlineMs,yieldCount,yieldMs,modelLoads,modelLoadMs,stopReasons{timeout,constraint,other},
 * (v4.1 r22-3: unplugStops and unplugLostMs went with the switch "충전 중에만")}, "prev":{same},
 * hot{headroom,temp,slow,severe} (v3.6.1), "chunk":{rates[], coldRates[] (work ms per encoder-window ms, v3.6.1), lastMs,
 * lastAudioMs, lastRate, lastEndMs, threads, coldMedianMs, p50Ms (a 2-minute piece at that speed)}, "battery":{the last snapshot},
 * "hotRestMs" (v3.6.1: the last wake that found it hot), v3.7 (r17-1): chunk.byThreads{"<n>":{rates,coldRates}},
 * today.pieceRates{bg[]} (plugged-in pieces; v4.2 r23-2: today.fg, pieceRates.fg, "fgSetting" and "fgOn" went with the
 * foreground run),
 * "lastBatteryRunEndMs","lastRunEndMs","lastPluggedMs","plugSampleMs","plugSamplePct","waiting":{n,oldestMs}}. */
internal object PowerStats {
    private const val KEEP=12
    fun parse(s:String?):JSONObject = runCatching { JSONObject(s ?: "") }.getOrNull() ?: JSONObject()
    private fun copy(o:JSONObject?)=o?.let { JSONObject(it.toString()) } ?: JSONObject()

    /** [o] with "today" for KST day [day]; an older today moves to prev. */
    fun roll(o:JSONObject,day:Int):JSONObject {
        val t=o.optJSONObject("today")
        if(t==null || t.optInt("day")!=day) {
            if(t!=null && t.optInt("day")>0) o.put("prev",t)
            o.put("today",JSONObject().put("day",day))
        }
        return o
    }
    private fun today(o:JSONObject,day:Int)=roll(o,day).getJSONObject("today")
    private fun inc(o:JSONObject,k:String,v:Long=1) { o.put(k,o.optLong(k,0)+v) }
    private fun sub(o:JSONObject,k:String)=o.optJSONObject(k) ?: JSONObject().also { o.put(k,it) }

    /** A run that started a piece: its time ([ms], start to end), audio done and, on battery with the screen off, the charge it used. */
    fun noteRun(old:JSONObject?,now:Long,mode:PowerPolicy.Mode,ms:Long,audioMs:Long,uahScreenOff:Long?):JSONObject {
        val o=copy(old);val t=today(o,PowerPolicy.day(now))
        val m=sub(t,if(mode==PowerPolicy.Mode.BATTERY) "battery" else "plugged")
        inc(m,"runs");inc(m,"ms",ms.coerceAtLeast(0));m.put("min",m.optLong("ms")/60_000)
        inc(m,"audioMs",audioMs.coerceAtLeast(0));m.put("audioMin",m.optLong("audioMs")/60_000)
        if(mode==PowerPolicy.Mode.BATTERY) {
            if(uahScreenOff!=null && uahScreenOff>0) { inc(m,"uahScreenOff",uahScreenOff);m.put("mAhScreenOff",m.optLong("uahScreenOff")/1000) }
            o.put("lastBatteryRunEndMs",now);o.remove("batteryRunStartMs");o.remove("batteryRunAliveMs")
        }
        o.put("lastRunEndMs",now)
        return o
    }
    /** A wake that only decided (no piece): counted under "<reason>Skips". */
    fun noteSkip(old:JSONObject?,now:Long,reason:String):JSONObject { val o=copy(old);inc(today(o,PowerPolicy.day(now)),reason+"Skips");return o }
    /** Today's budget was found used up ("min" or "pct"). */
    fun noteBudgetHit(old:JSONObject?,now:Long,how:String):JSONObject { val o=copy(old);today(o,PowerPolicy.day(now)).put("budgetHit",how);return o }
    /** Adds [v] to today's counter [k] (killedChunkMs, deadlineMs, yieldCount, yieldMs, modelLoads, modelLoadMs, levelFallingRuns, plugDropped). */
    fun add(old:JSONObject?,now:Long,k:String,v:Long=1):JSONObject { val o=copy(old);inc(today(o,PowerPolicy.day(now)),k,v);return o }
    fun noteStop(old:JSONObject?,now:Long,reason:String):JSONObject { val o=copy(old);inc(sub(today(o,PowerPolicy.day(now)),"stopReasons"),reason);return o }

    /** A finished piece: [ms] of work for [audioMs] of audio on [threads] threads; [cold]: the first piece of a run after a rest.
     * v3.7 (T3, review r16-1 #4): the samples are kept per thread count under chunk.byThreads{"<n>":{rates,coldRates}}, so the
     * battery's 4 threads by day and the charger's 6 by night no longer throw each other's samples away; chunk.rates and
     * chunk.coldRates are the lists of the last piece's thread count. [plugged]: a plugged-in piece, counted per day under
     * today.pieceRates.bg (v4.2 r23-2: every run is in the background now; battery pieces count in none). */
    fun notePiece(old:JSONObject?,ms:Long,audioMs:Long,threads:Int,cold:Boolean,now:Long=System.currentTimeMillis(),plugged:Boolean=false):JSONObject {
        val o=copy(old);val c=sub(o,"chunk")
        if(audioMs<=0 || ms<=0) return o
        // v3.6.1 (T1): per encoder window, so a short tail piece is no "slow" piece.
        val rate=Math.round(PowerPolicy.rate(ms,audioMs)!!*1000)/1000.0
        val by=sub(c,"byThreads")
        val lists=listOf("rates","coldRates","expect","expectLong")
        // A ledger from before v3.7: its lists belong to the thread count it last ran on.
        val was=c.optInt("threads",0)
        if(was>0 && !by.has(was.toString()) && (c.has("rates") || c.has("coldRates")))
            by.put(was.toString(),JSONObject().apply { for(k in lists) c.optJSONArray(k)?.let { put(k,JSONArray(it.toString())) } })
        val mine=sub(by,threads.toString())
        fun push(k:String,v:Any=rate) { val a=mine.optJSONArray(k) ?: JSONArray();a.put(v);while(a.length()>KEEP) a.remove(0);mine.put(k,a) }
        push("rates");if(cold) push("coldRates")
        // v3.6.1 fix (review, high): W1/W2's own samples, work ms per expected window ms ([rate]); the long pieces' kept apart,
        // so a run of short calls does not push them out. Kept per thread count like the others (v3.7 T3).
        val expect=Math.round(PowerPolicy.expectRate(ms,audioMs)!!*1000)/1000.0
        push("expect",expect);if(audioMs>=TranscriptionPower.LONG_PIECE_MS) push("expectLong",expect)
        // The top-level lists are this thread count's (the "slow" check compares pieces of one thread count only).
        for(k in lists) c.remove(k)
        c.remove("coldMedianMs")
        for(k in lists) mine.optJSONArray(k)?.let { c.put(k,JSONArray(it.toString())) }
        c.put("lastMs",ms).put("lastAudioMs",audioMs).put("threads",threads).put("lastRate",rate).put("lastEndMs",now)
        median(c.optJSONArray("coldRates"))?.let { c.put("coldMedianMs",(it*PipelineAudioMath.MAX_CHUNK_MS).toLong()) }
        median(c.optJSONArray("rates"))?.let { c.put("p50Ms",(it*PipelineAudioMath.MAX_CHUNK_MS).toLong()) }
        if(plugged) {
            val pr=sub(today(o,PowerPolicy.day(now)),"pieceRates")
            val a=pr.optJSONArray("bg") ?: JSONArray();a.put(rate);while(a.length()>DAY_PIECES) a.remove(0);pr.put("bg",a)
        }
        return o
    }
    /** v3.7 r17-3 (DESIGN_V7 3.3): one transcription run's process CPU time ([cpuMs], /proc/self/stat) and, on battery, the
     * charge it took ([uah], the CHARGE_COUNTER difference, the whole phone's use: an upper bound) into today.cpuMs,
     * today.batteryUah and today.cpuRuns. */
    fun noteCpu(old:JSONObject?,now:Long,cpuMs:Long?,uah:Long?):JSONObject {
        val o=copy(old);val t=today(o,PowerPolicy.day(now))
        if(cpuMs!=null && cpuMs>=0) { inc(t,"cpuMs",cpuMs);inc(t,"cpuRuns") }
        if(uah!=null && uah>0) inc(t,"batteryUah",uah)
        return o
    }
    /** Plugged-in pieces of one day kept for the day's median (v3.7 T5). */
    private const val DAY_PIECES=80
    fun median(a:JSONArray?):Double? {
        if(a==null || a.length()==0) return null
        val v=(0 until a.length()).map { a.optDouble(it) }.filter { it.isFinite() && it>0 }.sorted()
        if(v.isEmpty()) return null
        return if(v.size%2==1) v[v.size/2] else (v[v.size/2-1]+v[v.size/2])/2
    }
    /** Work ms per expected window ms for W1/W2 ([PowerPolicy.expectedPieceMs]), null without any piece (the default then).
     * v3.6.1 fix (review, high): never smaller than whole pieces really take. The median of the recent pieces (same threads)
     * of at least [TranscriptionPower.LONG_PIECE_MS] audio ("expectLong"); without one, the largest of all of them ("expect"; a
     * ledger from before this has no `expect` yet: the largest of its `rates`); never below [TranscriptionPower.DEFAULT_RATE]
     * while no long piece was measured. The slow verdict keeps its own per-window median ([slow]).
     * v3.7 (T3): [threads] picks that thread count's samples (falling back to the last piece's when it has none). */
    fun rate(o:JSONObject?,threads:Int?=null):Double? {
        val top=o?.optJSONObject("chunk") ?: return null
        val c=threads?.let { t -> top.optJSONObject("byThreads")?.optJSONObject(t.toString())?.takeIf { it.has("rates") || it.has("expect") } } ?: top
        median(c.optJSONArray("expectLong"))?.let { return it }
        val any=listOf("expect","rates").firstNotNullOfOrNull { k -> c.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optDouble(it) }.filter { it.isFinite() && it>0 }.maxOrNull() } }
            ?: return null
        return maxOf(any,TranscriptionPower.DEFAULT_RATE)
    }

    /** The last piece ran at least [TranscriptionPower.HOT_RATIO] times the cold median (needs both), and it ended less than
     * [TranscriptionPower.COLD_AFTER_MS] ago: after a rest the phone is taken as cool again, else one slow piece would stop
     * every later run for good (review r16-1 #1). */
    fun slow(o:JSONObject?,now:Long=System.currentTimeMillis()):Boolean {
        val c=o?.optJSONObject("chunk") ?: return false
        val end=c.optLong("lastEndMs",0);if(end<=0 || now-end>=TranscriptionPower.COLD_AFTER_MS) return false
        // v3.6.1 (T1): a hot rest taken since that piece, and over: the verdict is spent (one slow piece, one rest).
        val rest=o.optLong("hotRestMs",0);if(rest>=end && now-rest>=TranscriptionPower.HOT_DELAY_MS) return false
        val cold=median(c.optJSONArray("coldRates")) ?: return false
        val last=c.optDouble("lastRate",Double.NaN);if(!last.isFinite()) return false
        return last>=cold*TranscriptionPower.HOT_RATIO
    }
    /** v3.6.1 (T1): a wake found it hot ([why] from [PowerPolicy.hotWhy], or "severe" for the thermal status): counted under
     * today's hot{headroom,temp,slow,severe}, and the time of the rest kept ("hotRestMs") for [slow] and [coldStart]. */
    fun noteHot(old:JSONObject?,now:Long,why:String?):JSONObject {
        val o=copy(old)
        // The rest starts at the first hot wake after the last piece: a tick during the rest does not make it longer.
        val rest=o.optLong("hotRestMs",0);val piece=o.optJSONObject("chunk")?.optLong("lastEndMs",0) ?: 0
        // v3.6.1 fix (review, medium): why the rest was taken, so only a rest for a slow piece makes the next piece a cold one.
        if(rest<=0 || rest<piece || now<rest || now-rest>=TranscriptionPower.HOT_DELAY_MS) { o.put("hotRestMs",now);o.put("hotRestWhy",why ?: "") }
        why?.let { inc(sub(today(o,PowerPolicy.day(now)),"hot"),it) }
        return o
    }
    /** v3.6.1 (T1): the first piece of a run starting [now] counts as cold: the last run ended at least
     * [TranscriptionPower.COLD_AFTER_MS] ago, or a hot rest was taken since it ended. v3.6.1 fix (review, medium): only a rest
     * taken for a slow piece ("hotRestWhy" slow); after a rest for the headroom or the heat the phone may still be warm, and a
     * warm piece among the cold ones would raise the cold reference until "slow" never holds. */
    fun coldStart(o:JSONObject?,now:Long):Boolean {
        val last=o?.optLong("lastRunEndMs",0) ?: 0
        return now-last>=TranscriptionPower.COLD_AFTER_MS ||
            (o?.optLong("hotRestMs",0) ?: 0).let { it>0 && it>=last } && o?.optString("hotRestWhy","")=="slow"
    }
    /** Remembers the plug: when it was last seen, and a sample of the charge to tell a plug that falls short. Counts a
     * plug that stayed away past the debounce once (plugDropped). */
    fun notePlug(old:JSONObject?,s:BatterySnapshot,now:Long):JSONObject {
        val o=copy(old)
        if(s.isPlugged) {
            val last=o.optLong("lastPluggedMs",0)
            val sampleMs=o.optLong("plugSampleMs",0)
            // No sample (a new plug), no word from the plug for half an hour (what happened between is unknown), or a sample
            // old enough while the charge did not go down: sample again. While plugged in the runs look every 20 seconds.
            if(sampleMs<=0 || last<=0 || now-last>=3*TranscriptionPower.LEVEL_WINDOW_MS || now-sampleMs>=3*TranscriptionPower.LEVEL_WINDOW_MS && s.pct>=o.optInt("plugSamplePct",0))
                o.put("plugSampleMs",now).put("plugSamplePct",s.pct)
            o.put("lastPluggedMs",now)
        } else {
            val last=o.optLong("lastPluggedMs",0)
            if(last>0 && now-last>=TranscriptionPower.PLUG_DEBOUNCE_MS && o.optLong("plugSampleMs",0)>0) {
                inc(today(o,PowerPolicy.day(now)),"plugDropped");o.remove("plugSampleMs");o.remove("plugSamplePct")
            }
        }
        return o
    }
    /** Plugged in for at least [TranscriptionPower.LEVEL_WINDOW_MS] and the charge is [LEVEL_DROP_PCT] lower than when the plug
     * was sampled. Never while the battery is full or Samsung's protection holds it (it sways a percent there; review r16-1 #2). */
    fun levelFalling(o:JSONObject?,s:BatterySnapshot,now:Long):Boolean {
        if(o==null || !s.isPlugged || s.status=="full" || s.status=="notCharging") return false
        val ms=o.optLong("plugSampleMs",0);if(ms<=0 || now-ms<TranscriptionPower.LEVEL_WINDOW_MS) return false
        return s.pct<=o.optInt("plugSamplePct",0)-LEVEL_DROP_PCT
    }
    const val LEVEL_DROP_PCT=2
    /** A battery run began a piece (written before it, cleared by [noteRun]): a run the process died in still counts. */
    fun noteBatteryStart(old:JSONObject?,now:Long):JSONObject { val o=copy(old);o.put("batteryRunStartMs",now).put("batteryRunAliveMs",now);return o }
    /** r23 review: stops in a row of one piece of the recording [rid] under [key] ("userYield": the user's yields,
     * [TranscriptionStops.USER_YIELDS]; "plugDrop": stops for the plug, [PowerPolicy.PLUG_DROP_STOPS]); 0 for another
     * recording. r23 review 2: back to 0 once a piece is finished ([clearStreaks]). */
    fun streak(o:JSONObject?,key:String,rid:String?):Int { val u=o?.optJSONObject(key) ?: return 0
        return if(rid!=null && u.optString("rid")==rid) u.optInt("n",0) else 0 }
    fun noteStreak(old:JSONObject?,key:String,rid:String?):JSONObject { val o=copy(old);if(rid==null) return o
        o.put(key,JSONObject().put("rid",rid).put("n",streak(o,key,rid)+1));return o }
    fun clearStreaks(old:JSONObject?):JSONObject { val o=copy(old);o.remove("userYield");o.remove("plugDrop");return o }
    fun userYields(o:JSONObject?,rid:String?)=streak(o,"userYield",rid)
    fun noteUserYield(old:JSONObject?,rid:String?)=noteStreak(old,"userYield",rid)
    /** r23 review: the battery run was alive at [now] (a piece's end: the real time), for [deadMs]. r23 review 2: at a piece's
     * start [expectedMs] is added (the piece is taken as run to its expected end if the process dies in it; [deadMs] keeps
     * the 9-minute cap), so a run that dies in its first piece is no 0 minutes. */
    fun noteBatteryAlive(old:JSONObject?,now:Long,expectedMs:Long=0):JSONObject { val o=copy(old)
        if(o.optLong("batteryRunStartMs",0)>0) o.put("batteryRunAliveMs",now+expectedMs.coerceIn(0L,TranscriptionPower.SELF_END_MS));return o }
    /** A battery run's start mark this old without its end: the process died in it (the user closing the app from the recent
     * list kills it with the run). */
    fun dead(open:Long,now:Long)=now-open>=TranscriptionPower.SELF_END_MS+2*60_000L
    /** r23 review: a dead run's time: from its start to when it was last seen alive, at most [TranscriptionPower.SELF_END_MS]
     * (before: always the whole 9 minutes, and only in the next wake's decision). */
    fun deadMs(o:JSONObject?,open:Long):Long = ((o?.optLong("batteryRunAliveMs",0) ?: 0).takeIf { it>=open } ?: open).minus(open).coerceIn(0L,TranscriptionPower.SELF_END_MS)
    /** r23 review: books a dead battery run for good, once: its [deadMs] into the battery minutes of its day (today, or prev
     * when that is its day), counted as battery.killedRuns and killedMs, and its end for the gap. Before, the start mark was
     * only overwritten by the next run, and a dead run never reached battery.min. */
    fun settleDead(old:JSONObject?,now:Long):JSONObject {
        val o=copy(old);val open=o.optLong("batteryRunStartMs",0)
        if(open<=0 || !dead(open,now)) return o
        if(open>o.optLong("lastBatteryRunEndMs",0)) {
            val ms=deadMs(o,open);val day=PowerPolicy.day(open)
            // The run's day gets its block first (a ledger whose today is older), then today's comes.
            if((o.optJSONObject("today")?.optInt("day") ?: 0)<day) roll(o,day)
            roll(o,PowerPolicy.day(now))
            val t=listOf("today","prev").firstNotNullOfOrNull { k -> o.optJSONObject(k)?.takeIf { it.optInt("day")==day } }
            if(t!=null) { val b=sub(t,"battery");inc(b,"runs");inc(b,"ms",ms);b.put("min",b.optLong("ms")/60_000)
                inc(b,"killedRuns");inc(b,"killedMs",ms);b.put("killedMin",b.optLong("killedMs")/60_000) }
            o.put("lastBatteryRunEndMs",open+ms)
        }
        o.remove("batteryRunStartMs");o.remove("batteryRunAliveMs")
        return o
    }
    /** The policy's memory for [PowerPolicy.decide]. [ownStartMs]: asked from inside the battery run that wrote this start
     * ([noteBatteryStart]); that mark is the run itself, not another one, so it holds no gap (v3.6.1 T3a: before, the run's
     * own mark made every check between pieces a "gap" and a battery run did one piece). */
    fun budget(o:JSONObject?,s:BatterySnapshot,now:Long,ownStartMs:Long?=null):PowerBudget {
        val t=o?.optJSONObject("today")?.takeIf { it.optInt("day")==PowerPolicy.day(now) }
        val b=t?.optJSONObject("battery")
        val capacity=s.chargeUah?.let { c -> if(s.pct in 5..100) c*100/s.pct else null }
        var used=b?.optLong("ms",0) ?: 0;var last=o?.optLong("lastBatteryRunEndMs",0) ?: 0
        // A battery run that never wrote its end (the process died, review r16-1 #4): it counts as a full run that ended then,
        // once it is older than a run can be.
        val open=(o?.optLong("batteryRunStartMs",0) ?: 0).takeUnless { ownStartMs!=null && it==ownStartMs } ?: 0
        // r23 review: as long as it was last seen alive ([deadMs]), not a whole 9 minutes ([settleDead] books it for good).
        if(open>last && dead(open,now)) { val d=deadMs(o,open);last=open+d;if(PowerPolicy.day(open)==PowerPolicy.day(now)) used+=d }
        else if(open>last) last=now // one is running now: the gap holds
        val plugMs=o?.optLong("lastPluggedMs",0) ?: 0
        return PowerBudget(used,b?.optLong("uahScreenOff",0) ?: 0,capacity,last,plugMs,levelFalling(o,s,now),slow(o,now),
            PowerPolicy.splitExpectMs(PipelineAudioMath.MAX_CHUNK_MS,rate(o,TranscriptionPower.THREADS_OTHER)).coerceAtMost(PowerPolicy.PIECE_CAP_MS))
    }
    /** v3.6.1 (T3a): between pieces, the run in [mode] may go on (the same mode still applies). [ownStartMs]: this battery
     * run's own start mark, null for a plugged-in run or before its first piece. */
    fun stillRuns(o:JSONObject?,s:BatterySnapshot,now:Long,mode:PowerPolicy.Mode,ownStartMs:Long?):Boolean =
        PowerPolicy.decide(s,budget(o,s,now,ownStartMs),now,midRun=true).let { it is PowerPolicy.Decision.Run && it.mode==mode }
    /** The battery as last seen (heartbeat `power` head). */
    fun noteSnapshot(old:JSONObject?,s:BatterySnapshot,cpusAllowed:Int?,cpuset:String?):JSONObject {
        val o=copy(old)
        // v4.2 r23-2: the foreground run's keys of older builds are dropped (today.fg goes with the day roll).
        o.remove("fgSetting");o.remove("fgOn")
        o.put("battery",JSONObject().put("batteryPct",s.pct).put("plugged",s.plugged).put("status",s.status).put("powerSave",s.powerSave)
            .put("thermal",BatterySnapshot.thermalCode(s.thermal)).apply {
                s.headroom?.let { put("headroom",Math.round(it*100)/100.0) };s.tempDeciC?.let { put("tempDeciC",it) }
                s.currentUa?.let { put("currentUa",it) };cpusAllowed?.let { put("cpusAllowed",it) };cpuset?.let { put("cpuset",it) }
            })
        return o
    }
    /** Recordings waiting for the charger: how many and since when (queue entry, ms). */
    fun noteWaiting(old:JSONObject?,n:Int,oldestMs:Long?,now:Long=System.currentTimeMillis()):JSONObject {
        val o=copy(old);o.put("waiting",JSONObject().put("n",n).put("atMs",now).apply { oldestMs?.let { put("oldestMs",it) } });return o
    }
}
