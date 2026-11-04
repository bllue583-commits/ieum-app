package kr.callbrief.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CallLog
import org.json.JSONObject
import java.time.LocalDate

/** "처음 정리" (design: 처음 정리 (10월 1일부터)). Everything since [FROM_MS] is gathered once and the old records go to
 * the scheduled task in time order, in batches, so it can carry the repository ledger from one record to the next.
 * While it runs (from [PipelineSettingsKeys.FIRST_PASS_START_MS] until [done]) a record is "old" when it is more than
 * [LIVE_DAYS] days before now; newer ones are "live" and analysed one at a time as usual (v2.6, r7-1: the moment the
 * update arrived no longer decides, so today's calls reach today's report). */
internal object FirstPass {
    /** 2026-10-01 00:00 KST: nothing older is transcribed or read. */
    val FROM_MS:Long=LocalDate.of(2026,10,1).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
    /** Old records per batch file, and batches in the inbox at once (the task takes two per run). */
    const val BATCH=15
    const val BATCHES_IN_FLIGHT=2
    /** At most this many batches a day, and none while no result came for [STALL_MS] (design C7: the first pass steps
     * back by itself when the routine is held by the subscription limit). */
    const val BATCHES_PER_DAY=4
    const val STALL_MS=6*3_600_000L
    /** Old records make check items only when this recent; older work is long settled. */
    const val TASK_DAYS=7L
    const val BATCH_PREFIX="batch:"

    /** Records this recent are "live" while the first pass runs (sent one at a time, no charger needed). */
    const val LIVE_DAYS=7L
    const val LIVE_MS=LIVE_DAYS*86_400_000L
    /** A batch with fewer than [BATCH] records goes up anyway once its oldest record has waited this long (r7-1). */
    const val PARTIAL_AFTER_MS=6*3_600_000L
    /** Settings keys the hourly pass writes for the heartbeat: the time bound of the batches (0: none) and what holds them. */
    const val BOUND_KEY="firstPassBoundMs"
    const val BLOCKED_KEY="firstPassBlockedBy"
    const val BLOCKED_NONE="none";const val BLOCKED_MESSAGES="waiting_messages";const val BLOCKED_TRANSCRIPTS="waiting_transcripts";const val BLOCKED_BACKOFF="backoff"

    /** When the first pass started; it marks that it runs (with [done]) and starts the 3 days old texts may hold it. */
    fun start(s:JSONObject):Long? = s.optLong(PipelineSettingsKeys.FIRST_PASS_START_MS,0).takeIf { it>0 }
    fun done(s:JSONObject)=s.optBoolean(PipelineSettingsKeys.FIRST_PASS_DONE,false)
    /** The start of the live window while the first pass runs (records before it are old), else null. */
    fun liveFrom(s:JSONObject,now:Long):Long? = if(start(s)!=null && !done(s)) now-LIVE_MS else null
    /** Old = more than [LIVE_DAYS] days before [now], while the first pass is running. */
    fun isOld(dayMs:Long,s:JSONObject,now:Long=System.currentTimeMillis()):Boolean = liveFrom(s,now)?.let { dayMs<it } ?: false
    fun beforeRange(ms:Long?)=ms!=null && ms>0 && ms<FROM_MS
    /** More than [LIVE_DAYS] days old, whether or not the first pass is over: such a record follows the old-record rules
     * (work only from the last 7 days, the ledger only gains facts). Sending mode is [isOld]. */
    fun stale(dayMs:Long,now:Long)=now-dayMs>LIVE_MS

    /** Old recordings are transcribed only while charging or with at least half the battery (no heat during work calls). */
    fun powerOk(context:Context):Boolean {
        val b=context.getSystemService(BatteryManager::class.java) ?: return true
        return b.isCharging || b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)>=50
    }

    /** An old record's result keeps its summary and names; old work is not listed. Memos come from the repository ledger. */
    fun restrict(a:ClaudeCallAnalysis,dayMs:Long,now:Long):ClaudeCallAnalysis {
        val recent=now-dayMs<=TASK_DAYS*86_400_000L
        return a.copy(siteMemo=null,companyMemo=null,tasks=if(recent) a.tasks else emptyList(),promises=if(recent) a.promises else emptyList())
    }

    // ---- call log --------------------------------------------------------------------------------------------------
    /** One call of the phone's call log. [excluded]: a number the user's own exclusion rule ([kr.callbrief.LocalPrivacy]:
     * the 일상 통화 상대 and the numbers added in settings) keeps off every record, the call log included; such an entry is
     * only used to recognise its recording and is never stored. Numbers found personal by the analysis (personal_numbers)
     * are not excluded here: their calls are stored and listed as "개인 통화" (11/4 user). */
    data class LogEntry(val id:Long,val numberKey:String?,val name:String?,val startMs:Long,val durationMs:Long,val type:Int,val excluded:Boolean=false) {
        val direction:String? get()=when(type) { CallLog.Calls.INCOMING_TYPE -> "받은 전화";CallLog.Calls.OUTGOING_TYPE -> "건 전화";else -> null }
    }
    /** How the call list shows a call-log entry that has no recording to open. */
    enum class LogKind { MISSED, NOT_CONNECTED, BLOCKED, NO_RECORDING }
    fun kindOf(type:Int,durationMs:Long):LogKind=when {
        durationMs>0 && type in setOf(CallLog.Calls.INCOMING_TYPE,CallLog.Calls.OUTGOING_TYPE) -> LogKind.NO_RECORDING
        type==CallLog.Calls.OUTGOING_TYPE -> LogKind.NOT_CONNECTED
        type==CallLog.Calls.BLOCKED_TYPE -> LogKind.BLOCKED
        else -> LogKind.MISSED // missed, rejected, voicemail, and a ringing call with no length
    }
    data class RecordForMatch(val recordId:String,val startedMs:Long,val counterpart:String?,val counterpartKey:String?,val durationMs:Long=0)
    /** Why recordings did or did not find their call-log entry in one pass (design 3.4, data review 7): settings
     * [PipelineSettingsKeys.CALLLOG_MATCH_STATS], the heartbeat's callLog.match. [gapSec]: for each pair made, the
     * recording's start minus the entry's, in 10 s bins ("-10" = -10..-1 s, "0" = 0..9 s). */
    data class MatchStats(val noTime:Int=0,val noCandidate:Int=0,val length:Int=0,val nameNull:Int=0,val nameDiffers:Int=0,val numberDiffers:Int=0,
                          val several:Int=0,val matched:Int=0,val gapSec:Map<Int,Int> =emptyMap()) {
        fun reasons()=JSONObject().put("noTime",noTime).put("noCandidate",noCandidate).put("length",length).put("nameNull",nameNull)
            .put("nameDiffers",nameDiffers).put("numberDiffers",numberDiffers).put("several",several).put("matched",matched)
        /** The stored JSON with this pass added: "last" is this pass's reasons (the records still waiting are counted again
         * every pass, so they are not summed), "matched" and "gapSec" add up over all passes, "runs" counts passes. */
        fun mergeInto(previous:String?,now:Long):String {
            val o=runCatching { JSONObject(previous.orEmpty()) }.getOrNull() ?: JSONObject()
            val gaps=o.optJSONObject("gapSec") ?: JSONObject()
            for((bin,n) in gapSec) gaps.put(bin.toString(),gaps.optInt(bin.toString(),0)+n)
            return o.put("runs",o.optInt("runs",0)+1).put("lastMs",now).put("last",reasons()).put("matched",o.optInt("matched",0)+matched).put("gapSec",gaps).toString()
        }
    }
    /** Answered calls only: missed and rejected calls have no recording. */
    fun answered(e:LogEntry)=e.durationMs>0 && e.type in setOf(CallLog.Calls.INCOMING_TYPE,CallLog.Calls.OUTGOING_TYPE)

    /** Each recording to its call-log entry (design: 처음 정리, 대조). The log time is when the call was placed and the
     * recording starts once connected, so the recording may be up to 1 minute early or 2 minutes late; the lengths must
     * agree within 20% or 15 s. A number in the filename must match the entry's number. A name matches the entry's
     * phone-book name by [NameKey.sameName] (one key, or one inside the other); when no entry has that name (the phone
     * book was changed, CACHED_NAME is empty, the file name lost a character), time and length alone pair it when exactly
     * one entry fits, as for a recording with neither number nor name (design 3.4). When several pairs fit (a call placed
     * again within a minute or two), every fitting pair gets a score, the start gap in 30 s units plus the length gap in
     * 5% units (a pair by time alone after the named ones), and the best pairs are taken first across all recordings and
     * entries, each recording and each entry once (review B14). [stats] gets why each recording did or did not pair. */
    fun match(records:List<RecordForMatch>,log:List<LogEntry>,stats:((MatchStats)->Unit)?=null):Map<String,LogEntry> {
        class Fit(val r:RecordForMatch,val e:LogEntry,val score:Double)
        val answeredLog=log.filter { answered(it) }.sortedBy { it.startMs }
        val starts=LongArray(answeredLog.size) { answeredLog[it].startMs }
        val fits=ArrayList<Fit>()
        val why=HashMap<String,String>() // recordId -> reason when it has no fit at all
        for(r in records) {
            val key=r.counterpartKey
            val inTime=ArrayList<LogEntry>();val fitting=ArrayList<Fit>()
            // Entries placed from 2 minutes before to 1 minute after the recording started.
            var i=java.util.Arrays.binarySearch(starts,r.startedMs-120_000L).let { if(it>=0) { var k=it;while(k>0 && starts[k-1]==starts[it]) k--;k } else -it-1 }
            while(i<starts.size && starts[i]<=r.startedMs+60_000L) {
                val e=answeredLog[i++];inTime+=e
                val longer=maxOf(r.durationMs,e.durationMs)
                val gap=kotlin.math.abs(r.durationMs-e.durationMs)
                if(r.durationMs>0 && gap>maxOf(15_000L,longer/5)) continue
                val lengthTerm=if(r.durationMs>0 && longer>0) gap.toDouble()/longer/0.05 else 0.0
                fitting+=Fit(r,e,kotlin.math.abs(r.startedMs-e.startMs)/30_000.0+lengthTerm)
            }
            if(inTime.isEmpty()) { why[r.recordId]="noCandidate";continue }
            if(fitting.isEmpty()) { why[r.recordId]="length";continue }
            when {
                key!=null && key.all { it.isDigit() } -> {
                    val same=fitting.filter { it.e.numberKey==key }
                    if(same.isEmpty()) why[r.recordId]="numberDiffers" else fits.addAll(same)
                }
                key!=null -> {
                    val named=fitting.filter { it.e.name!=null && NameKey.sameName(it.e.name,r.counterpart ?: key) }
                    when {
                        named.isNotEmpty() -> fits+=named
                        // By time and length alone, after every pair by name. Never to an excluded entry: that would wipe a
                        // recording whose name says it is someone else.
                        fitting.size==1 && !fitting[0].e.excluded -> fits+=Fit(r,fitting[0].e,fitting[0].score+1_000.0)
                        else -> why[r.recordId]=if(fitting.all { it.e.name==null }) "nameNull" else "nameDiffers"
                    }
                }
                // Nothing in the file name says who it was (no number, no name): only a single fitting entry is certain enough,
                // excluded ones included; two or more candidates are left unpaired (review M4).
                fitting.size==1 -> fits+=fitting
                else -> why[r.recordId]="several"
            }
        }
        val usedEntries=mutableSetOf<Long>();val out=LinkedHashMap<String,LogEntry>()
        for(f in fits.sortedWith(compareBy<Fit>({ it.score },{ it.r.startedMs },{ it.r.recordId },{ it.e.id }))) {
            if(f.r.recordId in out || f.e.id in usedEntries) continue
            out[f.r.recordId]=f.e;usedEntries+=f.e.id
        }
        if(stats!=null) {
            val counts=records.filter { it.recordId !in out }.groupingBy { why[it.recordId] ?: "several" }.eachCount() // with fits but every entry went to a closer recording
            val gaps=HashMap<Int,Int>()
            for(r in records) { val e=out[r.recordId] ?: continue
                val bin=Math.floorDiv(r.startedMs-e.startMs,10_000L).toInt()*10;gaps[bin]=(gaps[bin] ?: 0)+1 }
            stats(MatchStats(noCandidate=counts["noCandidate"] ?: 0,length=counts["length"] ?: 0,nameNull=counts["nameNull"] ?: 0,nameDiffers=counts["nameDiffers"] ?: 0,
                numberDiffers=counts["numberDiffers"] ?: 0,several=counts["several"] ?: 0,matched=out.size,gapSec=gaps))
        }
        return out
    }

    /** The phone's call log since [FROM_MS], every kind (missed, rejected, unanswered, answered); numbers the user's
     * exclusion rule keeps off are marked [LogEntry.excluded]. Stays on the phone. */
    fun readCallLog(context:Context,excluded:(String)->Boolean):List<LogEntry> {
        if(context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED) return emptyList()
        val out=mutableListOf<LogEntry>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.CACHED_NAME,CallLog.Calls.DATE,CallLog.Calls.DURATION,CallLog.Calls.TYPE),
            "${CallLog.Calls.DATE}>=?",arrayOf(FROM_MS.toString()),"${CallLog.Calls.DATE} ASC")?.use { c ->
            while(c.moveToNext() && out.size<20_000) {
                val number=c.getString(1).orEmpty()
                out+=LogEntry(c.getLong(0),MessageIntake.phoneKey(number),c.getString(2)?.trim()?.takeIf { it.isNotEmpty() },c.getLong(3),c.getLong(4)*1000,c.getInt(5),
                    number.isNotBlank() && excluded(number))
            }
        }
        return out
    }
    /** [readCallLog] with the exclusion rules saved on this phone. */
    fun readCallLog(context:Context,settings:JSONObject):List<LogEntry> {
        val rules=kr.callbrief.LocalPrivacy.rules(settings)
        return readCallLog(context) { n -> rules.excluded(n,n) }
    }
}
