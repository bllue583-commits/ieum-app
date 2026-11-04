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
 * Records older than the moment this started ([PipelineSettingsKeys.FIRST_PASS_START_MS]) are "old"; newer ones are
 * analysed as usual all along. */
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

    fun start(s:JSONObject):Long? = s.optLong(PipelineSettingsKeys.FIRST_PASS_START_MS,0).takeIf { it>0 }
    fun done(s:JSONObject)=s.optBoolean(PipelineSettingsKeys.FIRST_PASS_DONE,false)
    /** Old = before the first pass started, while it is still running. */
    fun isOld(dayMs:Long,s:JSONObject):Boolean = !done(s) && start(s)?.let { dayMs<it } ?: false
    fun beforeRange(ms:Long?)=ms!=null && ms>0 && ms<FROM_MS
    /** Before the first pass started, whether or not it is over: a late old recording still follows the old-record
     * rules (work only from the last 7 days, the ledger only gains facts). Sending mode is [isOld]. */
    fun beforeStart(dayMs:Long,s:JSONObject)=start(s)?.let { dayMs<it } ?: false

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
    /** [personal]: a number excluded as personal (일상 통화 상대). Kept only to recognise its recordings, never stored. */
    data class LogEntry(val id:Long,val numberKey:String?,val name:String?,val startMs:Long,val durationMs:Long,val type:Int,val personal:Boolean=false) {
        val direction:String? get()=when(type) { CallLog.Calls.INCOMING_TYPE -> "받은 전화";CallLog.Calls.OUTGOING_TYPE -> "건 전화";else -> null }
    }
    data class RecordForMatch(val recordId:String,val startedMs:Long,val counterpart:String?,val counterpartKey:String?,val durationMs:Long=0)
    private fun noSpace(s:String?)=s.orEmpty().filterNot { it.isWhitespace() }
    /** Answered calls only: missed and rejected calls have no recording. */
    fun answered(e:LogEntry)=e.durationMs>0 && e.type in setOf(CallLog.Calls.INCOMING_TYPE,CallLog.Calls.OUTGOING_TYPE)

    /** Each recording to its call-log entry (design: 처음 정리, 대조). The log time is when the call was placed and the
     * recording starts once connected, so the recording may be up to 1 minute early or 2 minutes late; the lengths must
     * agree within 20% or 15 s. A number in the filename must match the entry's number, a name the entry's phone-book
     * name; with neither, time and length alone. A recording with neither number nor name pairs only when exactly one entry fits. When several pairs fit (a call placed again within a minute or two),
     * every fitting pair gets a score, the start gap in 30 s units plus the length gap in 5% units, and the best pairs
     * are taken first across all recordings and entries, each recording and each entry once (review B14). */
    fun match(records:List<RecordForMatch>,log:List<LogEntry>):Map<String,LogEntry> {
        class Fit(val r:RecordForMatch,val e:LogEntry,val score:Double)
        val answeredLog=log.filter { answered(it) }.sortedBy { it.startMs }
        val starts=LongArray(answeredLog.size) { answeredLog[it].startMs }
        val fits=ArrayList<Fit>()
        for(r in records) {
            val key=r.counterpartKey
            val mine=ArrayList<Fit>()
            // Entries placed from 2 minutes before to 1 minute after the recording started.
            var i=java.util.Arrays.binarySearch(starts,r.startedMs-120_000L).let { if(it>=0) { var k=it;while(k>0 && starts[k-1]==starts[it]) k--;k } else -it-1 }
            while(i<starts.size && starts[i]<=r.startedMs+60_000L) {
                val e=answeredLog[i++]
                val longer=maxOf(r.durationMs,e.durationMs)
                val gap=kotlin.math.abs(r.durationMs-e.durationMs)
                if(r.durationMs>0 && gap>maxOf(15_000L,longer/5)) continue
                val named=when {
                    key!=null && key.all { it.isDigit() } -> e.numberKey==key
                    key!=null -> e.name!=null && noSpace(e.name)==noSpace(r.counterpart ?: key)
                    else -> true
                }
                if(!named) continue
                val lengthTerm=if(r.durationMs>0 && longer>0) gap.toDouble()/longer/0.05 else 0.0
                mine+=Fit(r,e,kotlin.math.abs(r.startedMs-e.startMs)/30_000.0+lengthTerm)
            }
            // Nothing in the file name says who it was (no number, no name): only a single fitting entry is certain enough,
            // personal ones included; two or more candidates are left unpaired (review M4).
            if(key!=null || mine.size==1) fits+=mine
        }
        val usedEntries=mutableSetOf<Long>();val out=LinkedHashMap<String,LogEntry>()
        for(f in fits.sortedWith(compareBy<Fit>({ it.score },{ it.r.startedMs },{ it.r.recordId },{ it.e.id }))) {
            if(f.r.recordId in out || f.e.id in usedEntries) continue
            out[f.r.recordId]=f.e;usedEntries+=f.e.id
        }
        return out
    }

    /** The phone's call log since [FROM_MS]; numbers excluded as personal are marked. Stays on the phone. */
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
}
