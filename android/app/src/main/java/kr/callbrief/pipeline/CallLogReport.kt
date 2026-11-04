package kr.callbrief.pipeline

import android.content.Context
import android.provider.CallLog
import android.util.Log
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

/** status/calllog.txt (decided 11/4 with the user and his wife): the phone's call log line by line next to what the app
 * stored, so a person (or the hourly task) can check that no call is missed or doubled. Unlike status/phone.json it holds
 * names and full numbers, so it goes ONLY to the private mailbox: never to a screen, a log line or a notification.
 * Numbers the user excluded (LocalPrivacy, "기록 안 함") are shown as "(제외 번호)". The app side is read only. */
internal object CallLogReport {
    const val PATH="status/calllog.txt"
    private const val TAG="ieum.status"
    /** Settings: hash of the last file that went up (without its time line), and when the full section was last filled. */
    internal const val HASH="callLogReportHash"
    internal const val FULL_DAY="callLogReportFullDay"
    internal const val FULL_MS="callLogReportFullMs"
    const val RECENT_MS=48*3_600_000L
    const val HIDDEN="(제외 번호)"

    /** One entry of the CallLog provider. [excluded]: the user's exclusion rules match its number or name. */
    data class DeviceCall(val id:Long,val number:String,val name:String?,val startMs:Long,val durationSec:Long,val type:Int,val excluded:Boolean=false) {
        val numberKey get()=MessageIntake.phoneKey(number)
    }
    data class Report(val text:String,val hash:String,val full:Boolean)

    private val DAY_TIME=DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(CallRecordStore.KST)
    private val FULL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(CallRecordStore.KST)
    fun kind(type:Int)=when(type) { CallLog.Calls.INCOMING_TYPE -> "받은";CallLog.Calls.OUTGOING_TYPE -> "건";CallLog.Calls.MISSED_TYPE -> "부재중"
        CallLog.Calls.REJECTED_TYPE -> "거절";else -> "기타" }
    private fun length(sec:Long)="통화 ${sec/60}분 ${sec%60}초"
    /** Record ids may hold a number; the 8 characters are those of the record's mailbox file name (inbox/, results/). */
    fun shortId(recordId:String?)=recordId?.let { CallInbox.fileName(it).take(8) } ?: "-"
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Whether the file is due a full section: the first tick after the KST date changed. */
    fun fullDue(lastFullDay:String,now:Long)=lastFullDay!=CallRecordStore.kstDay(now).toString()

    /** The whole file. [device] null: no permission to read the call log. The first line (time made) is left out of [Report.hash]. */
    fun render(now:Long,appVersion:String,device:List<DeviceCall>?,stored:List<CallRecordStore.StoredLog>,personalKeys:Set<String>,
               full:Boolean,lastFullMs:Long,excluded:(number:String?,name:String?)->Boolean={ _,_ -> false }):Report {
        val byId=stored.associateBy { it.logId }
        val calls=(device ?: emptyList()).sortedWith(compareByDescending<DeviceCall> { it.startMs }.thenByDescending { it.id })
        val deviceIds=calls.map { it.id }.toSet()
        fun personal(c:DeviceCall)=c.excluded || c.numberKey?.let { it in personalKeys }==true || byId[c.id]?.business==false
        fun analysis(c:DeviceCall):String { val s=byId[c.id]
            return when { personal(c) -> "개인";s?.recordId==null -> "없음";s.state==CallRecordStore.ANALYZED -> "완료";else -> "대기" } }
        fun line(c:DeviceCall):String { val s=byId[c.id]
            return listOf(DAY_TIME.format(Instant.ofEpochMilli(c.startMs)),kind(c.type),length(c.durationSec),
                if(c.excluded) HIDDEN else c.name?.trim()?.takeIf { it.isNotEmpty() } ?: "-",if(c.excluded) HIDDEN else c.number.ifBlank { "-" },
                if(s!=null) "O" else "X",if(s?.recordId!=null) "O" else "X",analysis(c),shortId(s?.recordId)).joinToString(" | ") }
        fun storedLine(s:CallRecordStore.StoredLog,excluded:Boolean)=listOf(DAY_TIME.format(Instant.ofEpochMilli(s.startMs)),kind(s.type),length(s.durationMs/1000),
            if(excluded) HIDDEN else s.name ?: "-",if(excluded) HIDDEN else s.numberKey ?: "-","O",if(s.recordId!=null) "O" else "X",
            when { excluded || s.business==false || s.numberKey in personalKeys -> "개인";s.recordId==null -> "없음";s.state==CallRecordStore.ANALYZED -> "완료";else -> "대기" },
            shortId(s.recordId)).joinToString(" | ")

        val missing=calls.filter { it.id !in byId }
        val appOnly=if(device==null) emptyList() else stored.filter { it.logId !in deviceIds }.sortedByDescending { it.startMs }
        // Doubled: same number, same second and same length more than once; or one recording tied to two calls.
        val sameCall=calls.groupBy { Triple(it.number,it.startMs/1000,it.durationSec) }.values.filter { it.size>1 }.flatten()
        val sharedRecording=stored.filter { it.recordingId!=null }.groupBy { it.recordingId }.values.filter { it.size>1 }.flatten().map { it.logId }.toSet()
        val doubled=(sameCall+calls.filter { it.id in sharedRecording }).distinctBy { it.id }

        val b=StringBuilder()
        b.append("이음 통화 목록 대조 (만든 시각 ").append(FULL_TIME.format(Instant.ofEpochMilli(now))).append(" KST, 앱 ").append(appVersion.ifBlank { "?" }).append(")\n")
        val body=StringBuilder()
        if(device==null) body.append("폰 통화 기록을 읽을 권한이 없어요. 앱 저장 ${stored.size}건만 보여요.\n")
        else body.append("폰 기록 ${calls.size}건, 앱 저장 ${stored.size}건, 빠진 ${missing.size}건, 앱에만 ${appOnly.size}건, 중복 ${doubled.size}건 (10/1 이후)\n")
        body.append("줄 형식: 날짜 시각 | 종류 | 통화 시간 | 전화부 이름 | 번호 | 앱 저장 | 녹음 | 분석 | 기록 id\n")
        fun section(title:String,lines:List<String>) { body.append("\n[").append(title).append("]\n");if(lines.isEmpty()) body.append("(없음)\n") else lines.forEach { body.append(it).append('\n') } }
        if(device!=null) {
            section("빠진 통화 (폰에 있고 앱에 없음)",missing.map(::line))
            section("앱에만 있는 기록",appOnly.map { s -> storedLine(s,excluded(s.numberKey,s.name)) })
            section("중복",doubled.map(::line))
            section("최근 48시간",calls.filter { now-it.startMs<=RECENT_MS }.map(::line))
            body.append("\n[10/1 이후 전체]\n")
            if(full) { if(calls.isEmpty()) body.append("(없음)\n") else calls.forEach { body.append(line(it)).append('\n') } }
            else body.append("(하루 한 번 갱신, 마지막 갱신 ").append(if(lastFullMs>0) FULL_TIME.format(Instant.ofEpochMilli(lastFullMs)) else "아직 없음").append(")\n")
        }
        val rest=body.toString()
        return Report(b.append(rest).toString(),sha(rest),full)
    }

    /** The phone's call log since 10/1 straight from the provider, with the user's exclusion rules applied. Null without the permission. */
    fun readDevice(context:Context,settings:org.json.JSONObject):List<DeviceCall>? {
        if(context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val rules=kr.callbrief.LocalPrivacy.rules(settings)
        val out=mutableListOf<DeviceCall>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.CACHED_NAME,CallLog.Calls.DATE,CallLog.Calls.DURATION,CallLog.Calls.TYPE),
            "${CallLog.Calls.DATE}>=?",arrayOf(FirstPass.FROM_MS.toString()),"${CallLog.Calls.DATE} ASC")?.use { c ->
            while(c.moveToNext() && out.size<20_000) {
                val number=c.getString(1).orEmpty();val name=c.getString(2)?.trim()?.takeIf { it.isNotEmpty() }
                val excluded=number.isNotBlank() && rules.excluded(number,number) || name!=null && rules.excluded(name)
                out+=DeviceCall(c.getLong(0),number,name,c.getLong(3),c.getLong(4),c.getInt(5),excluded)
            }
        }
        return out
    }

    /** Hourly tick, after the heartbeat: sends the file when it changed. Never throws; on failure nothing is remembered,
     * so the next tick tries again. */
    fun publish(context:Context,store:CallInboxStore,appVersion:String,now:Long=System.currentTimeMillis(),
                device:(Context,org.json.JSONObject)->List<DeviceCall>? =::readDevice) {
        try {
            val app=CallPipelineStartup.storage(context.applicationContext)
            val settings=kr.callbrief.SecureSettings(app);val s=settings.snapshot()
            val calls=device(app,s)
            val full=calls!=null && fullDue(s.optString(FULL_DAY),now)
            val (stored,personal)=CallRecordStore(app).use { db -> db.callLogRows(FirstPass.FROM_MS) to db.personalNumbers().map { it.first }.toSet() }
            val rules=kr.callbrief.LocalPrivacy.rules(s)
            val r=render(now,appVersion,calls,stored,personal,full,s.optLong(FULL_MS,0L)) { n,name -> n!=null && rules.excluded(n,n) || name!=null && rules.excluded(name) }
            if(r.hash==s.optString(HASH)) return
            store.replace(PATH,r.text)
            settings.update { it.put(HASH,r.hash).apply { if(r.full) put(FULL_DAY,CallRecordStore.kstDay(now).toString()).put(FULL_MS,now) } }
        } catch(e:Exception) { Log.w(TAG,"call log file not sent: ${e.javaClass.simpleName}") }
    }
}
