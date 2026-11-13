package kr.callbrief.pipeline.bench

import org.json.JSONArray
import org.json.JSONObject

/** r18-bench: the bench's progress and numbers under the settings key [KEY] (sealed on the phone). The plan names the local
 * recording versions it measures; everything else is numbers and codes. [heartbeat] makes the mailbox block from it: numbers
 * per candidate and per length bucket only, never a recording, a text, a name or a number from a call. */
internal object BenchState {
    const val KEY="benchState"
    /** Numbers of one finished piece (or a part of one) of one call for one candidate. */
    data class Piece(val audioMs:Long,val wallMs:Long,val cpuMs:Long?,val refChars:Int,val edits:Int,val numRef:Int,val numHit:Int,
        val nameRef:Int,val nameHit:Int,val halluc:Int,val segments:Int,val foreground:Boolean)

    fun parse(text:String?):JSONObject = runCatching { JSONObject(text ?: "") }.getOrNull() ?: JSONObject()

    /** A fresh state for [run] (the old numbers go). */
    fun fresh(run:Int,now:Long):JSONObject = JSONObject().put("run",run).put("state","WAITING").put("atMs",now).put("cands",JSONObject())

    fun plan(s:JSONObject):List<JSONObject> = s.optJSONArray("plan")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    fun setPlan(s:JSONObject,calls:List<BenchCall>):JSONObject = s.put("plan",JSONArray().apply { calls.forEach { c ->
        put(JSONObject().put("id",c.recordingId).put("sv",c.sourceVersion).put("sha",c.audioSha).put("mime",c.mime).put("bytes",c.bytes)
            .put("at",c.startedMs).put("ms",c.durationMs)) } })
    fun call(o:JSONObject)=BenchCall(o.optString("id"),o.optLong("sv"),o.optString("sha"),o.optString("mime"),o.optLong("bytes"),o.optLong("at"),o.optLong("ms"))

    private fun cand(s:JSONObject,c:String):JSONObject { val all=s.optJSONObject("cands") ?: JSONObject().also { s.put("cands",it) }
        return all.optJSONObject(c) ?: JSONObject().also { all.put(c,it) } }
    fun entry(s:JSONObject,c:String,index:Int):JSONObject { val cs=cand(s,c);return cs.optJSONObject(index.toString()) ?: JSONObject().put("piece",0).also { cs.put(index.toString(),it) } }
    /** The next piece of call [index] still to do for [c] (pieces before it are done and counted). */
    fun nextPiece(s:JSONObject,c:String,index:Int)=entry(s,c,index).optInt("piece",0)
    fun done(s:JSONObject,c:String,index:Int)=entry(s,c,index).optBoolean("done",false)

    fun addPiece(s:JSONObject,c:String,index:Int,pieceIndex:Int,p:Piece):JSONObject {
        val e=entry(s,c,index)
        fun plus(k:String,v:Long) { e.put(k,e.optLong(k,0)+v) }
        plus("audioMs",p.audioMs);plus("wallMs",p.wallMs);plus("refChars",p.refChars.toLong());plus("edits",p.edits.toLong())
        plus("numRef",p.numRef.toLong());plus("numHit",p.numHit.toLong());plus("nameRef",p.nameRef.toLong());plus("nameHit",p.nameHit.toLong())
        plus("halluc",p.halluc.toLong());plus("segments",p.segments.toLong());plus("pieces",1)
        if(p.cpuMs!=null) { plus("cpuMs",p.cpuMs);plus("cpuAudioMs",p.audioMs) }
        if(p.foreground) plus("fgAudioMs",p.audioMs)
        e.put("piece",pieceIndex+1);return s
    }
    /** The call is over for [c]: measured to the end ([code] null) or given up with [code]. [lengthMs] is the call's length. */
    fun finish(s:JSONObject,c:String,index:Int,lengthMs:Long,code:String?):JSONObject {
        val e=entry(s,c,index).put("done",true).put("lenMs",lengthMs)
        if(code!=null) e.put("code",code) else e.remove("code")
        return s
    }
    /** A try that failed but may succeed next time (counted; at [MAX_TRIES] the call is given up). Returns the tries so far. */
    fun tried(s:JSONObject,c:String,index:Int):Int { val e=entry(s,c,index);val n=e.optInt("tries",0)+1;e.put("tries",n);return n }
    const val MAX_TRIES=3

    fun setStatus(s:JSONObject,state:String,code:String?,now:Long):JSONObject {
        s.put("state",state).put("atMs",now);if(code==null) s.remove("code") else s.put("code",code);return s
    }
    fun setModel(s:JSONObject,c:String,state:String):JSONObject { (s.optJSONObject("models") ?: JSONObject().also { s.put("models",it) }).put(c,state);return s }

    /** Foreground ms used today (KST day) by the whisper candidates. */
    fun fgUsed(s:JSONObject,day:String)=if(s.optString("fgDay")==day) s.optLong("fgMs",0) else 0L
    fun addFg(s:JSONObject,day:String,ms:Long):JSONObject { val prev=fgUsed(s,day);return s.put("fgDay",day).put("fgMs",prev+ms) }

    private val CODE=Regex("[A-Za-z0-9_]{1,60}")
    private fun code(v:String?)=v?.takeIf { CODE.matches(it) }
    private fun ratio(a:Long,b:Long,scale:Double=1.0,digits:Int=3):Any = if(b<=0) JSONObject.NULL else Math.round(a.toDouble()/b*scale*Math.pow(10.0,digits.toDouble()))/Math.pow(10.0,digits.toDouble())

    /** The sums of a set of call entries as the heartbeat shows them (numbers only). */
    private fun sums(entries:List<JSONObject>):JSONObject {
        fun sum(k:String)=entries.sumOf { it.optLong(k,0) }
        val finished=entries.filter { it.optBoolean("done") && !it.has("code") }
        val audio=sum("audioMs");val cpuAudio=sum("cpuAudioMs")
        return JSONObject().put("calls",finished.size).put("audioMin",ratio(audio,60_000,digits=1))
            .put("wallRtf",ratio(sum("wallMs"),audio)).put("cpuRtf",ratio(sum("cpuMs"),cpuAudio))
            .put("cpuSecPerAudioMin",ratio(sum("cpuMs"),cpuAudio,60.0,1))
            .put("cerPct",ratio(sum("edits"),sum("refChars"),100.0,2)).put("refChars",sum("refChars"))
            .put("numberPct",ratio(sum("numHit"),sum("numRef"),100.0,1)).put("numbers",sum("numRef"))
            .put("namePct",ratio(sum("nameHit"),sum("nameRef"),100.0,1)).put("names",sum("nameRef"))
            .put("halluc",sum("halluc")).put("hallucPerCall",ratio(finished.sumOf { it.optLong("halluc",0) },finished.size.toLong(),digits=2))
            .put("fgSharePct",ratio(sum("fgAudioMs"),audio,100.0,0))
    }

    /** The heartbeat's `bench` block ([BENCH_SHAPE] in docs/OPERATIONS.md), or null while the bench has never been on. */
    fun heartbeat(s:JSONObject,cfg:BenchConfig):JSONObject? {
        if(!cfg.on && !s.has("run")) return null
        val out=JSONObject().put("on",cfg.on).put("run",s.optInt("run",cfg.run)).put("state",code(s.optString("state")) ?: "OFF")
            .put("code",code(s.optString("code").takeIf { s.has("code") }) ?: JSONObject.NULL).put("atMs",s.optLong("atMs",0))
            .put("planned",plan(s).size).put("candidates",JSONArray(cfg.candidates))
        s.optJSONObject("models")?.let { m -> out.put("models",JSONObject().apply { BenchConfig.ORDER.forEach { k -> code(m.optString(k).takeIf { m.has(k) })?.let { put(k,it) } } }) }
        val cands=JSONObject()
        val all=s.optJSONObject("cands") ?: JSONObject()
        for(c in BenchConfig.ORDER) {
            val cs=all.optJSONObject(c) ?: continue
            val entries=cs.keys().asSequence().mapNotNull { cs.optJSONObject(it) }.toList()
            val block=sums(entries)
            val failed=entries.filter { it.optBoolean("done") && it.has("code") }
            block.put("failed",failed.size).put("fail",JSONObject().apply { failed.groupingBy { code(it.optString("code")) ?: "OTHER" }.eachCount().forEach { (k,n) -> put(k,n) } })
            block.put("inProgress",entries.count { !it.optBoolean("done") && it.optInt("piece",0)>0 })
            // Charging the whole time: the battery's drop cannot be read, so the energy is the CPU time (DESIGN_V7 4.4).
            block.put("mAh",JSONObject.NULL).put("energyBasis","cpu")
            block.put("byLen",JSONObject().apply { BenchPick.BUCKETS.forEach { b ->
                val inB=entries.filter { it.optBoolean("done") && it.has("lenMs") && BenchPick.bucket(it.optLong("lenMs"))==b }
                if(inB.isNotEmpty()) put(b,sums(inB).let { x -> JSONObject().put("calls",x.get("calls")).put("audioMin",x.get("audioMin")).put("wallRtf",x.get("wallRtf"))
                    .put("cpuRtf",x.get("cpuRtf")).put("cerPct",x.get("cerPct")).put("hallucPerCall",x.get("hallucPerCall")) }) } })
            cands.put(c,block)
        }
        out.put("cand",cands)
        return out
    }
}
