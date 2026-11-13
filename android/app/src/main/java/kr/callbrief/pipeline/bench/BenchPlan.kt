package kr.callbrief.pipeline.bench

import org.json.JSONArray
import org.json.JSONObject

/** r18-bench (DESIGN_V7 4.4): what the administrator asks for in the mailbox's app/config.json, read at the heartbeat.
 * `{"bench":{"on":true,"candidates":["b","c"],"calls":20,"run":1},"samples":{"on":true,"calls":12,"maxMinutes":40}}`.
 * A missing file, a file that cannot be parsed, or a missing block means off (the default); nothing on the phone's screens
 * changes either way. */
internal data class BenchConfig(val on:Boolean=false,val candidates:List<String> =DEFAULT_CANDIDATES,val calls:Int=DEFAULT_CALLS,
    /** A new number starts a fresh measurement (the old numbers are dropped); the same number goes on where it stopped. */
    val run:Int=1,
    /** Threads per candidate (null: (가)/(나) the plugged-in run's count, (다) [SENSE_VOICE_THREADS]). */
    val threads:Map<String,Int> =emptyMap(),
    /** Foreground minutes a day the whisper candidates may use (the system's limit is shared with the transcription run). */
    val fgMinutes:Int=DEFAULT_FG_MINUTES) {
    fun toJson():JSONObject = JSONObject().put("on",on).put("candidates",JSONArray(candidates)).put("calls",calls).put("run",run)
        .put("threads",JSONObject().apply { threads.forEach { (k,v) -> put(k,v) } }).put("fgMinutes",fgMinutes)
    companion object {
        /** (다) first: it is the most valuable number (DESIGN_V7 5.2 rule 2) and the quickest one to get. */
        val ORDER=listOf("c","b","a")
        val DEFAULT_CANDIDATES=listOf("b","c")
        const val DEFAULT_CALLS=20
        const val MAX_CALLS=40
        const val DEFAULT_FG_MINUTES=60
        const val SENSE_VOICE_THREADS=2
        fun parse(o:JSONObject?):BenchConfig {
            o ?: return BenchConfig()
            val cands=o.optJSONArray("candidates")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } }
                ?.filter { it in ORDER }?.distinct()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CANDIDATES
            val th=o.optJSONObject("threads")?.let { t -> ORDER.mapNotNull { k -> t.optInt(k,0).takeIf { it in 1..8 }?.let { k to it } }.toMap() } ?: emptyMap()
            return BenchConfig(o.optBoolean("on",false),ORDER.filter { it in cands },o.optInt("calls",DEFAULT_CALLS).coerceIn(1,MAX_CALLS),
                o.optInt("run",1),th,o.optInt("fgMinutes",DEFAULT_FG_MINUTES).coerceIn(0,240))
        }
    }
}

/** r18-bench (item 9, user 11/13): recording samples to the private mailbox's release `bench-samples`, so a bigger recognizer in
 * the workspace can make the reference transcripts. Off unless `samples.on` is true. */
internal data class SamplesConfig(val on:Boolean=false,val calls:Int=DEFAULT_CALLS,val maxMinutes:Int=DEFAULT_MAX_MINUTES) {
    fun toJson():JSONObject = JSONObject().put("on",on).put("calls",calls).put("maxMinutes",maxMinutes)
    companion object {
        const val DEFAULT_CALLS=12
        const val DEFAULT_MAX_MINUTES=40
        fun parse(o:JSONObject?):SamplesConfig {
            o ?: return SamplesConfig()
            return SamplesConfig(o.optBoolean("on",false),o.optInt("calls",DEFAULT_CALLS).coerceIn(1,30),o.optInt("maxMinutes",DEFAULT_MAX_MINUTES).coerceIn(1,120))
        }
    }
}

internal object BenchConfigs {
    const val PATH="app/config.json"
    /** The whole file; anything unreadable is off for both. */
    fun parse(text:String?):Pair<BenchConfig,SamplesConfig> {
        val o=runCatching { JSONObject(text ?: return BenchConfig() to SamplesConfig()) }.getOrNull() ?: return BenchConfig() to SamplesConfig()
        return BenchConfig.parse(o.optJSONObject("bench")) to SamplesConfig.parse(o.optJSONObject("samples"))
    }
}

/** One recent call the bench (or the sample upload) may take: a work call (the analysis said business, its number is not one
 * found personal, not excluded), with its recording in the vault and the phone's own transcript still kept. Local only. */
internal data class BenchCall(val recordingId:String,val sourceVersion:Long,val audioSha:String,val mime:String,val bytes:Long,
    val startedMs:Long,val durationMs:Long) {
    /** Local key of the recording version (never sent). */
    val key get()="$recordingId|$sourceVersion|$audioSha"
}

/** r18-bench: which calls to measure and which to send as samples. Pure, so the host tests pin the rules. */
internal object BenchPick {
    const val MIN=60_000L
    const val LONG_MS=10*MIN
    /** The bench: the newest calls of 1 to 5 minutes, and the newest calls over 10 minutes up to [longCount] of them. */
    fun bench(calls:List<BenchCall>,count:Int,longCount:Int=2):List<BenchCall> {
        val sorted=calls.sortedByDescending { it.startedMs }
        val long=sorted.filter { it.durationMs>LONG_MS }.take(minOf(longCount,count))
        val mid=sorted.filter { it.durationMs in MIN..5*MIN }.take(count-long.size)
        return (mid+long).sortedByDescending { it.startedMs }
    }
    /** Samples: within [sinceMs], not sent before ([sent] keys), 1 to 5 minutes first, at most two over 10 minutes, all
     * together no more than [maxMinutes] and [count] calls. */
    fun samples(calls:List<BenchCall>,sinceMs:Long,sent:Set<String>,count:Int,maxMinutes:Int,longCount:Int=2):List<BenchCall> {
        val pool=calls.filter { it.startedMs>=sinceMs && it.key !in sent }.sortedByDescending { it.startedMs }
        val budget=maxMinutes*MIN
        val out=ArrayList<BenchCall>();var used=0L;var longs=0
        for(c in pool.filter { it.durationMs in MIN..5*MIN }+pool.filter { it.durationMs>LONG_MS }) {
            if(out.size>=count) break
            if(c.durationMs>LONG_MS && longs>=longCount) continue
            if(used+c.durationMs>budget) continue
            out+=c;used+=c.durationMs;if(c.durationMs>LONG_MS) longs++
        }
        return out
    }
    /** The length bucket the heartbeat groups calls by (never a single call). */
    fun bucket(durationMs:Long)=when { durationMs<MIN -> "lt1m";durationMs<3*MIN -> "m1to3";durationMs<=LONG_MS -> "m3to10";else -> "m10plus" }
    val BUCKETS=listOf("lt1m","m1to3","m3to10","m10plus")
}

/** r18-bench: when the bench (and the sample upload) may run at all, as a code (null = go). Lowest priority of everything:
 * only on the charger, not in a call, with no recording waiting for its text and no transcription run going. */
internal object BenchGate {
    /** Thermal status from which the bench rests (PowerManager: 2 = moderate). */
    const val HOT=2
    fun stop(plugged:Boolean,inCall:Boolean,thermal:Int,transcriptionBusy:Boolean,queueWaiting:Boolean?):String? = when {
        !plugged -> "NOT_PLUGGED"
        inCall -> "IN_CALL"
        thermal>=HOT -> "HOT"
        transcriptionBusy -> "TRANSCRIBING"
        queueWaiting==true -> "QUEUE"
        else -> null
    }
}
