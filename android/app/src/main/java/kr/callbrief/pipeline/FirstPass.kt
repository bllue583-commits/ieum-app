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
    /** Old records make check items only when this recent; older work is long settled. */
    const val TASK_DAYS=7L
    const val BATCH_PREFIX="batch:"

    fun start(s:JSONObject):Long? = s.optLong(PipelineSettingsKeys.FIRST_PASS_START_MS,0).takeIf { it>0 }
    fun done(s:JSONObject)=s.optBoolean(PipelineSettingsKeys.FIRST_PASS_DONE,false)
    /** Old = before the first pass started, while it is still running. */
    fun isOld(dayMs:Long,s:JSONObject):Boolean = !done(s) && start(s)?.let { dayMs<it } ?: false
    fun beforeRange(ms:Long?)=ms!=null && ms>0 && ms<FROM_MS

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
    data class LogEntry(val id:Long,val numberKey:String?,val name:String?,val startMs:Long,val durationMs:Long,val type:Int) {
        val direction:String? get()=when(type) { CallLog.Calls.INCOMING_TYPE -> "받은 전화";CallLog.Calls.OUTGOING_TYPE -> "건 전화";else -> null }
    }
    data class RecordForMatch(val recordId:String,val startedMs:Long,val counterpart:String?,val counterpartKey:String?,val durationMs:Long=0)
    private fun noSpace(s:String?)=s.orEmpty().filterNot { it.isWhitespace() }
    /** Answered calls only: missed and rejected calls have no recording. */
    fun answered(e:LogEntry)=e.durationMs>0 && e.type in setOf(CallLog.Calls.INCOMING_TYPE,CallLog.Calls.OUTGOING_TYPE)

    /** Each recording to its call-log entry (design: 처음 정리, 대조). The log time is when the call was placed and the
     * recording starts once connected, so the recording may be up to 1 minute early or 2 minutes late; the lengths must
     * agree within 20% or 15 s. A number in the filename must match the entry's number, a name the entry's phone-book
     * name; with neither, time and length alone. Only a single candidate is taken; two or more leave it unlinked. */
    fun match(records:List<RecordForMatch>,log:List<LogEntry>):Map<String,LogEntry> {
        val used=mutableSetOf<Long>();val out=LinkedHashMap<String,LogEntry>()
        for(r in records.sortedBy { it.startedMs }) {
            val key=r.counterpartKey
            val candidates=log.filter { e -> e.id !in used && answered(e) && r.startedMs-e.startMs in -60_000L..120_000L &&
                (r.durationMs<=0 || kotlin.math.abs(r.durationMs-e.durationMs)<=maxOf(15_000L,maxOf(r.durationMs,e.durationMs)/5)) &&
                when {
                    key!=null && key.all { it.isDigit() } -> e.numberKey==key
                    key!=null -> e.name!=null && noSpace(e.name)==noSpace(r.counterpart ?: key)
                    else -> true
                } }
            val pick=candidates.singleOrNull() ?: continue
            used+=pick.id;out[r.recordId]=pick
        }
        return out
    }

    /** The phone's call log since [FROM_MS], without numbers the user excluded as personal. Stays on the phone. */
    fun readCallLog(context:Context,excluded:(String)->Boolean):List<LogEntry> {
        if(context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED) return emptyList()
        val out=mutableListOf<LogEntry>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.CACHED_NAME,CallLog.Calls.DATE,CallLog.Calls.DURATION,CallLog.Calls.TYPE),
            "${CallLog.Calls.DATE}>=?",arrayOf(FROM_MS.toString()),"${CallLog.Calls.DATE} ASC")?.use { c ->
            while(c.moveToNext() && out.size<20_000) {
                val number=c.getString(1).orEmpty()
                if(number.isNotBlank() && excluded(number)) continue
                out+=LogEntry(c.getLong(0),MessageIntake.phoneKey(number),c.getString(2)?.trim()?.takeIf { it.isNotEmpty() },c.getLong(3),c.getLong(4)*1000,c.getInt(5))
            }
        }
        return out
    }
}
