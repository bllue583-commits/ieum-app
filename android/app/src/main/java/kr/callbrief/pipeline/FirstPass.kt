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

    /** Old recordings are transcribed only while plugged in (v3.6, DESIGN_V6 2.2-6: the "half the battery" branch is gone;
     * a battery run takes today's short calls only). */
    fun powerOk(context:Context):Boolean = BatterySnapshot.read(context).isPlugged

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
                          val several:Int=0,val matched:Int=0,val gapSec:Map<Int,Int> =emptyMap(),
                          /** r12-4 (DESIGN_V4 2.4-4): for each record left noCandidate, the recording's start minus the nearest
                           * answered entry's start in minutes, in signed 10-minute bins from -60 to 60 ("-10" = -10..-1 min);
                           * [MISS_FAR] when no answered entry is within 60 minutes, [MISS_BEFORE] for a record before 10/1 (r12-7). */
                          val missGapMin:Map<String,Int> =emptyMap(),
                          /** Pairs made by the end-time reading (the file name's time is the end of the call), r12-4. */
                          val byEnd:Int=0,
                          /** r12-7: for each [MISS_FAR] record, the signed hour to the nearest entry ([missHour]). */
                          val missGapHour:Map<String,Int> =emptyMap(),
                          /** r12-7: pairs made by the person in the file name outside the usual window (byEnd among them). */
                          val byParty:Int=0,
                          /** r12-7, set by the store: call records of the last 24 hours, those of them still without an entry,
                           * and records named by a phone-book name that got the one number of that name without an entry. */
                          val records24h:Int=0,val open24h:Int=0,val byPhoneBook:Int=0) {
        fun reasons()=JSONObject().put("noTime",noTime).put("noCandidate",noCandidate).put("length",length).put("nameNull",nameNull)
            .put("nameDiffers",nameDiffers).put("numberDiffers",numberDiffers).put("several",several).put("matched",matched).put("byEnd",byEnd)
            .put("missGapMin",JSONObject().apply { missGapMin.forEach { (k,v) -> put(k,v) } })
            .put("missGapHour",JSONObject().apply { missGapHour.forEach { (k,v) -> put(k,v) } }).put("byParty",byParty)
            .put("records24h",records24h).put("open24h",open24h).put("byPhoneBook",byPhoneBook)
        /** The stored JSON with this pass added: "last" is this pass's reasons (the records still waiting are counted again
         * every pass, so they are not summed), "matched", "byEnd" and "gapSec" add up over all passes, "runs" counts passes.
         * "missGapMin" is this pass's distribution (the same waiting records come back every pass, like "last"). */
        fun mergeInto(previous:String?,now:Long):String {
            val o=runCatching { JSONObject(previous.orEmpty()) }.getOrNull() ?: JSONObject()
            val gaps=o.optJSONObject("gapSec") ?: JSONObject()
            for((bin,n) in gapSec) gaps.put(bin.toString(),gaps.optInt(bin.toString(),0)+n)
            return o.put("runs",o.optInt("runs",0)+1).put("lastMs",now).put("last",reasons()).put("matched",o.optInt("matched",0)+matched).put("gapSec",gaps)
                .put("byEnd",o.optInt("byEnd",0)+byEnd).put("byParty",o.optInt("byParty",0)+byParty).put("byPhoneBook",o.optInt("byPhoneBook",0)+byPhoneBook)
                .put("missGapMin",JSONObject().apply { missGapMin.forEach { (k,v) -> put(k,v) } }).toString()
        }
    }
    /** [MatchStats.missGapMin] key of a noCandidate record with no answered entry within [MISS_RANGE_MS]. */
    const val MISS_FAR="far"
    /** r12-7: [MatchStats.missGapMin] key of a record from before [FROM_MS]: the call log is read from 10/1 only, so such a
     * record can never find its entry. */
    const val MISS_BEFORE="before"
    const val MISS_RANGE_MS=60*60_000L
    /** The bin of a start difference in ms (recording minus entry): signed 10-minute bins from -60 to 60 ("-10" = -10..-1
     * min, "0" = 0..9 min), [MISS_FAR] beyond an hour either way. */
    fun missBin(diffMs:Long):String = if(kotlin.math.abs(diffMs)>MISS_RANGE_MS) MISS_FAR
        else (Math.floorDiv(diffMs,600_000L)*10).coerceIn(-60,60).toString()
    /** r12-7: for a [MISS_FAR] record, the signed hour of the difference ("1" = 1..2 h after the entry, "-2" = 2..1 h before),
     * capped at [MISS_HOURS_MAX] either way: is the recording's time hours off, or is there no call at all? */
    const val MISS_HOURS_MAX=24L
    fun missHour(diffMs:Long):String = Math.floorDiv(diffMs,3_600_000L).coerceIn(-MISS_HOURS_MAX,MISS_HOURS_MAX).toString()
    /** Answered calls only: missed and rejected calls have no recording. */
    fun answered(e:LogEntry)=e.durationMs>0 && e.type in setOf(CallLog.Calls.INCOMING_TYPE,CallLog.Calls.OUTGOING_TYPE)
    /** r12-7 (user 11/5 "녹음 파일명에 정보가 있잖아"): how far from the file name's time a call of the person the file name
     * names (number or phone-book name) is still taken. Covers a recorder that names the file by the end of the call. */
    const val PARTY_RANGE_MS=30*60_000L
    /** A pair by the person outside the usual window comes after every pair inside it (another recording may own it). */
    private const val OUTSIDE_WINDOW=500.0
    /** The usual window: the entry placed from 2 minutes before to 1 minute after the recording started. */
    fun inWindow(recordingMs:Long,entryMs:Long)=entryMs>=recordingMs-120_000L && entryMs<=recordingMs+60_000L
    /** The lengths agree within 20% or 15 s (an unknown recording length agrees with any). */
    fun lengthFits(recordingMs:Long,entryMs:Long):Boolean { if(recordingMs<=0) return true
        return kotlin.math.abs(recordingMs-entryMs)<=maxOf(15_000L,maxOf(recordingMs,entryMs)/5) }
    /** The entry is the person the file name names: the same number, or the phone-book name by [NameKey.sameName]. */
    fun sameParty(r:RecordForMatch,e:LogEntry):Boolean {
        val key=r.counterpartKey ?: return false
        return if(key.all { it.isDigit() }) e.numberKey==key else e.name!=null && NameKey.sameName(e.name,r.counterpart ?: key)
    }

    /** Each recording to its call-log entry (design: 처음 정리, 대조; r12-7).
     * 1. The file name says who (a number, or a phone-book name): that person's answered calls within [PARTY_RANGE_MS] of the
     *    file name's time, on the same KST day (or in the usual window), whose length agrees (20% or 15 s) are the candidates,
     *    outside the usual window too. One is taken; of several, those in the usual window go on to the scoring below; several
     *    and none in the window: left unpaired ("several": time and length cannot pick one).
     * 2. Otherwise the usual window (the recording may start up to 1 minute early or 2 minutes late against the log time) and
     *    the length: a number in the file name that is not the entry's never pairs; a name not in the log pairs by time and
     *    length alone when exactly one entry fits (design 3.4); a recording with neither pairs only when exactly one entry
     *    fits (review M4).
     * When several pairs fit, every fitting pair gets a score, the start gap in 30 s units plus the length gap in 5% units (a
     * pair by time alone after the named ones, a pair outside the window after both), and the best pairs are taken first
     * across all recordings and entries, each recording and each entry once (review B14). [stats] gets why each recording did
     * or did not pair. */
    fun match(records:List<RecordForMatch>,log:List<LogEntry>,stats:((MatchStats)->Unit)?=null):Map<String,LogEntry> {
        class Fit(val r:RecordForMatch,val e:LogEntry,val score:Double)
        val answeredLog=log.filter { answered(it) }.sortedBy { it.startMs }
        val starts=LongArray(answeredLog.size) { answeredLog[it].startMs }
        val fits=ArrayList<Fit>()
        val why=HashMap<String,String>() // recordId -> reason when it has no fit at all
        fun firstFrom(from:Long)=java.util.Arrays.binarySearch(starts,from).let { if(it>=0) { var k=it;while(k>0 && starts[k-1]==starts[it]) k--;k } else -it-1 }
        fun between(from:Long,to:Long):List<LogEntry> { val out=ArrayList<LogEntry>();var i=firstFrom(from)
            while(i<starts.size && starts[i]<=to) out+=answeredLog[i++];return out }
        fun score(r:RecordForMatch,e:LogEntry):Double { val longer=maxOf(r.durationMs,e.durationMs)
            val lengthTerm=if(r.durationMs>0 && longer>0) kotlin.math.abs(r.durationMs-e.durationMs).toDouble()/longer/0.05 else 0.0
            return kotlin.math.abs(r.startedMs-e.startMs)/30_000.0+lengthTerm+if(inWindow(r.startedMs,e.startMs)) 0.0 else OUTSIDE_WINDOW }
        for(r in records) {
            val key=r.counterpartKey
            // 1. The person the file name names, first.
            if(key!=null) {
                val day=CallRecordStore.kstDay(r.startedMs)
                val party=between(r.startedMs-PARTY_RANGE_MS,r.startedMs+PARTY_RANGE_MS).filter { e -> sameParty(r,e) && lengthFits(r.durationMs,e.durationMs) &&
                    (inWindow(r.startedMs,e.startMs) || CallRecordStore.kstDay(e.startMs)==day) }
                val pick=if(party.size<=1) party else party.filter { inWindow(r.startedMs,it.startMs) }
                if(pick.isNotEmpty()) { pick.forEach { fits+=Fit(r,it,score(r,it)) };continue }
                if(party.size>1) { why[r.recordId]="several";continue }
            }
            // 2. The usual window.
            val inTime=between(r.startedMs-120_000L,r.startedMs+60_000L)
            if(inTime.isEmpty()) { why[r.recordId]="noCandidate";continue }
            val fitting=inTime.filter { lengthFits(r.durationMs,it.durationMs) }.map { Fit(r,it,score(r,it)) }
            if(fitting.isEmpty()) { why[r.recordId]="length";continue }
            when {
                key!=null && key.all { it.isDigit() } -> why[r.recordId]="numberDiffers" // its own number was looked for above
                key!=null -> when {
                    // By time and length alone, after every pair by name. Never to an excluded entry: that would wipe a
                    // recording whose name says it is someone else.
                    fitting.size==1 && !fitting[0].e.excluded -> fits+=Fit(r,fitting[0].e,fitting[0].score+1_000.0)
                    else -> why[r.recordId]=if(fitting.all { it.e.name==null }) "nameNull" else "nameDiffers"
                }
                // Nothing in the file name says who it was: only a single fitting entry is certain enough, excluded ones
                // included; two or more candidates are left unpaired (review M4).
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
            // With fits but every entry went to a closer recording: "several".
            val counts=records.filter { it.recordId !in out }.groupingBy { why[it.recordId] ?: "several" }.eachCount()
            // A pair outside the usual window whose "file time - length" is inside it: the file was named by the end of the call (r12-4).
            fun byEnd(r:RecordForMatch,e:LogEntry)=!inWindow(r.startedMs,e.startMs) && r.durationMs>0 && inWindow(r.startedMs-r.durationMs,e.startMs)
            val gaps=HashMap<Int,Int>()
            for(r in records) { val e=out[r.recordId] ?: continue
                if(!inWindow(r.startedMs,e.startMs)) continue // the start-gap bins describe the usual window only
                val bin=Math.floorDiv(r.startedMs-e.startMs,10_000L).toInt()*10;gaps[bin]=(gaps[bin] ?: 0)+1 }
            // How far off the nearest answered entry is for each recording left without a candidate (r12-4), the far ones also
            // by the signed hour, and records from before 10/1 apart (the call log does not go back that far), r12-7.
            val miss=HashMap<String,Int>();val hours=HashMap<String,Int>()
            for(r in records) { if(r.recordId in out || why[r.recordId]!="noCandidate") continue
                if(beforeRange(r.startedMs)) { miss[MISS_BEFORE]=(miss[MISS_BEFORE] ?: 0)+1;continue }
                val k=firstFrom(r.startedMs)
                val near=listOfNotNull(starts.getOrNull(k-1),starts.getOrNull(k)).minByOrNull { kotlin.math.abs(r.startedMs-it) }
                val bin=near?.let { missBin(r.startedMs-it) } ?: MISS_FAR
                miss[bin]=(miss[bin] ?: 0)+1
                if(bin==MISS_FAR && near!=null) missHour(r.startedMs-near).let { h -> hours[h]=(hours[h] ?: 0)+1 } }
            stats(MatchStats(noCandidate=counts["noCandidate"] ?: 0,length=counts["length"] ?: 0,nameNull=counts["nameNull"] ?: 0,nameDiffers=counts["nameDiffers"] ?: 0,
                numberDiffers=counts["numberDiffers"] ?: 0,several=counts["several"] ?: 0,matched=out.size,gapSec=gaps,missGapMin=miss,
                byEnd=records.count { r -> out[r.recordId]?.let { byEnd(r,it) }==true },missGapHour=hours,
                byParty=records.count { r -> out[r.recordId]?.let { !inWindow(r.startedMs,it.startMs) }==true }))
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
                out+=LogEntry(c.getLong(0),kr.callbrief.core.PhoneKey.of(number),c.getString(2)?.trim()?.takeIf { it.isNotEmpty() },c.getLong(3),c.getLong(4)*1000,c.getInt(5),
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
