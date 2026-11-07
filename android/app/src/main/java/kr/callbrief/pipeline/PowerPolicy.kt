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
    /** A day's battery budget: this many minutes of runs that started a piece, or this share of the battery, whichever comes first. */
    const val BATTERY_BUDGET_MIN=20
    const val BATTERY_BUDGET_MS=BATTERY_BUDGET_MIN*60_000L
    const val BATTERY_BUDGET_PCT=5
    /** Battery runs at least this far apart (heat, and no "every call starts it at once" loop). */
    const val BATTERY_RUN_GAP_MS=30*60_000L
    /** "Today, short" on battery: calls of this length (from the call log, else estimated from the file size). */
    const val SHORT_MIN_MS=20_000L
    const val SHORT_MAX_MS=3*60_000L
    /** A plug that dropped out for less than this still counts as plugged in (wireless pads). */
    const val PLUG_DEBOUNCE_MS=2*60_000L
    /** Hot: thermal headroom at least this, or the last piece this many times the cold median, or the battery this warm (0.1 °C). */
    const val HOT_HEADROOM=0.85f
    const val HOT_RATIO=1.5
    const val HOT_TEMP_DECI_C=400
    /** While plugged in and hot: try again this much later (an explicit delay, not WorkManager's backoff). */
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
    /** Without a measurement: ms of work per ms of audio (2-minute piece in 300 s, DESIGN_V6 1.1). */
    const val DEFAULT_RATE=2.5
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
                inCall(context),runCatching { pm?.isInteractive==true }.getOrDefault(false),charge,current)
        }
        private fun inCall(context:Context):Boolean {
            if(context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return runCatching { context.getSystemService(android.telecom.TelecomManager::class.java)?.isInCall==true }.getOrDefault(false)
        }
    }
}

/** What the policy remembers between runs (kept in [PowerStats], passed in so [PowerPolicy.decide] stays pure). */
internal data class PowerBudget(
    val chargingOnly:Boolean=false,
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
)

/** v3.6 (DESIGN_V6 2.2): whether a transcription run may start now and how. Pure: everything comes in as arguments. */
internal object PowerPolicy {
    enum class Mode { PLUGGED, BATTERY }
    sealed class Decision {
        /** Run in [mode]: on battery only today's short calls, newest first; [runLimitMs] is how long the run may go (W1),
         * [threads] the cap before P7. */
        data class Run(val mode:Mode,val threads:Int,val runLimitMs:Long,val plug:String):Decision()
        /** Do nothing now. [reason] is counted (gap, hot, inCall, morning, budget, low, powerSave, chargingOnly);
         * [retryInMs]: queue the run again after this long, null: the charger trigger or the hourly tick will. */
        data class Wait(val reason:String,val retryInMs:Long?):Decision()
    }

    /** Plugged in for the policy: a plug now, or one seen within the debounce; not when the charge goes down while plugged. */
    fun plugged(s:BatterySnapshot,b:PowerBudget,now:Long)=(s.isPlugged || b.lastPluggedMs>0 && now-b.lastPluggedMs in 0 until TranscriptionPower.PLUG_DEBOUNCE_MS) && !b.levelFalling
    fun hot(s:BatterySnapshot,b:PowerBudget)=(s.headroom ?: 0f)>=TranscriptionPower.HOT_HEADROOM || (s.tempDeciC ?: 0)>=TranscriptionPower.HOT_TEMP_DECI_C || b.slow
    fun morning(now:Long)=Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST).hour.let { it>=TranscriptionPower.MORNING_REST_FROM_HOUR && it<TranscriptionPower.MORNING_REST_TO_HOUR }
    private fun untilSeven(now:Long):Long {
        val z=Instant.ofEpochMilli(now).atZone(TranscriptionPower.KST)
        val seven=z.toLocalDate().atTime(TranscriptionPower.MORNING_REST_TO_HOUR,0).atZone(TranscriptionPower.KST).toInstant().toEpochMilli()
        return (seven-now).coerceIn(60_000L,TranscriptionPower.BATTERY_RUN_GAP_MS)
    }
    /** The battery budget is used up: [TranscriptionPower.BATTERY_BUDGET_MIN] minutes, or [TranscriptionPower.BATTERY_BUDGET_PCT]% of the battery. */
    fun budgetHit(b:PowerBudget):String? = when {
        b.batteryRunMs>=TranscriptionPower.BATTERY_BUDGET_MS -> "min"
        b.capacityUah!=null && b.capacityUah>0 && b.batteryUah*100>=b.capacityUah*TranscriptionPower.BATTERY_BUDGET_PCT -> "pct"
        else -> null
    }

    /** SEVERE heat or worse (it stopped every run before v3.6 too): rest 20 minutes whatever the plug says. */
    const val SEVERE=3
    fun decide(s:BatterySnapshot,b:PowerBudget,now:Long):Decision {
        if(s.thermal>=SEVERE) return Decision.Wait("hot",TranscriptionPower.HOT_DELAY_MS*2)
        val plugged=plugged(s,b,now)
        if(b.chargingOnly && !plugged) return Decision.Wait("chargingOnly",null)
        if(plugged) {
            if(hot(s,b)) return Decision.Wait("hot",TranscriptionPower.HOT_DELAY_MS)
            if(morning(now) && s.pct<TranscriptionPower.MORNING_REST_PCT) return Decision.Wait("morning",untilSeven(now))
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
        val left=(TranscriptionPower.BATTERY_BUDGET_MS-b.batteryRunMs).coerceAtLeast(60_000L)
        return Decision.Run(Mode.BATTERY,TranscriptionPower.THREADS_OTHER,minOf(TranscriptionPower.SELF_END_MS,left),"none")
    }

    /** P7: threads never above the cores the process may run on ([coresAllowed], null: unknown). */
    fun threads(cap:Int,coresAllowed:Int?)=if(coresAllowed==null || coresAllowed<=0) cap else minOf(cap,coresAllowed).coerceAtLeast(1)

    /** W1: how long a piece of [audioMs] audio is expected to take at [rate] (work ms per audio ms), with the margin. */
    fun expectedPieceMs(audioMs:Long,rate:Double?)=(audioMs*(rate ?: TranscriptionPower.DEFAULT_RATE)*TranscriptionPower.PIECE_MARGIN).toLong()
    /** W1: start this piece? The first piece of a run always starts (else a hot phone would only wake and end, DESIGN_V6 W1). */
    fun startPiece(firstOfRun:Boolean,elapsedMs:Long,expectedMs:Long,limitMs:Long)=firstOfRun || elapsedMs+expectedMs<=limitMs
    /** W2: split a whole piece in two before it is begun when it is expected to run into the native deadline. */
    fun splitAhead(expectedMs:Long,deadlineSeconds:Int)=expectedMs>deadlineSeconds*1000L*TranscriptionPower.SPLIT_SHARE

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
 * gapSkips,hotSkips,inCallSkips,morningSkips,lowSkips,powerSaveSkips,chargingOnlySkips,budgetSkips,levelFallingRuns,plugDropped,
 * killedChunkMs,deadlineMs,yieldCount,yieldMs,modelLoads,modelLoadMs,stopReasons{timeout,constraint,other}}, "prev":{same},
 * "chunk":{rates[], coldRates[], lastMs, lastAudioMs, threads, coldMedianMs, p50Ms}, "battery":{the last snapshot},
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
            o.put("lastBatteryRunEndMs",now)
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

    /** A finished piece: [ms] of work for [audioMs] of audio on [threads] threads; [cold]: the first piece of a run after a rest. */
    fun notePiece(old:JSONObject?,ms:Long,audioMs:Long,threads:Int,cold:Boolean):JSONObject {
        val o=copy(old);val c=sub(o,"chunk")
        if(audioMs<=0 || ms<=0) return o
        val rate=Math.round(ms.toDouble()/audioMs*1000)/1000.0
        fun push(k:String) { val a=c.optJSONArray(k) ?: JSONArray();a.put(rate);while(a.length()>KEEP) a.remove(0);c.put(k,a) }
        // A piece on other thread counts is not compared with the others: the list starts again.
        if(c.optInt("threads",threads)!=threads) { c.remove("rates") }
        push("rates");if(cold) push("coldRates")
        c.put("lastMs",ms).put("lastAudioMs",audioMs).put("threads",threads).put("lastRate",rate)
        median(c.optJSONArray("coldRates"))?.let { c.put("coldMedianMs",(it*PipelineAudioMath.MAX_CHUNK_MS).toLong()) }
        median(c.optJSONArray("rates"))?.let { c.put("p50Ms",(it*PipelineAudioMath.MAX_CHUNK_MS).toLong()) }
        return o
    }
    fun median(a:JSONArray?):Double? {
        if(a==null || a.length()==0) return null
        val v=(0 until a.length()).map { a.optDouble(it) }.filter { it.isFinite() && it>0 }.sorted()
        if(v.isEmpty()) return null
        return if(v.size%2==1) v[v.size/2] else (v[v.size/2-1]+v[v.size/2])/2
    }
    /** Work ms per audio ms for W1: the median of the recent pieces (same threads), null without any. */
    fun rate(o:JSONObject?):Double? = median(o?.optJSONObject("chunk")?.optJSONArray("rates"))
    /** The last piece ran at least [TranscriptionPower.HOT_RATIO] times the cold median (needs both). */
    fun slow(o:JSONObject?):Boolean {
        val c=o?.optJSONObject("chunk") ?: return false
        val cold=median(c.optJSONArray("coldRates")) ?: return false
        val last=c.optDouble("lastRate",Double.NaN);if(!last.isFinite()) return false
        return last>=cold*TranscriptionPower.HOT_RATIO
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
    /** Plugged in for at least [TranscriptionPower.LEVEL_WINDOW_MS] and the charge is lower than when the plug was sampled. */
    fun levelFalling(o:JSONObject?,s:BatterySnapshot,now:Long):Boolean {
        if(o==null || !s.isPlugged) return false
        val ms=o.optLong("plugSampleMs",0);if(ms<=0 || now-ms<TranscriptionPower.LEVEL_WINDOW_MS) return false
        return s.pct<o.optInt("plugSamplePct",0)
    }
    /** The policy's memory for [PowerPolicy.decide]. */
    fun budget(o:JSONObject?,s:BatterySnapshot,now:Long,chargingOnly:Boolean):PowerBudget {
        val t=o?.optJSONObject("today")?.takeIf { it.optInt("day")==PowerPolicy.day(now) }
        val b=t?.optJSONObject("battery")
        val capacity=s.chargeUah?.let { c -> if(s.pct in 5..100) c*100/s.pct else null }
        return PowerBudget(chargingOnly,b?.optLong("ms",0) ?: 0,b?.optLong("uahScreenOff",0) ?: 0,capacity,
            o?.optLong("lastBatteryRunEndMs",0) ?: 0,o?.optLong("lastPluggedMs",0) ?: 0,levelFalling(o,s,now),slow(o))
    }
    /** The battery as last seen (heartbeat `power` head). */
    fun noteSnapshot(old:JSONObject?,s:BatterySnapshot,cpusAllowed:Int?,cpuset:String?):JSONObject {
        val o=copy(old)
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
