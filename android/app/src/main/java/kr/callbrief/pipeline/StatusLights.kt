package kr.callbrief.pipeline

import android.content.Context
import org.json.JSONObject

/** The five lights of settings "지금 상태" (design B5): 녹음 가져오기, 글자로 바꾸기, 통화 정리, 알림, 업데이트.
 * Each is green ("정상") or amber with the reason and one thing the user can do, in a single sentence. Never red. */
internal object StatusLights {
    data class Light(val tag:String,val label:String,val ok:Boolean,val words:String)
    /** Everything the lights are judged from; all of it is read from settings, permissions and the store already on the phone. */
    data class Input(
        val now:Long,
        /** A folder was chosen once ([folderConnected]), Android still lets the app read it ([folderReadable]), and when the
         * last discovery cycle ended well (0: never). Review #9: the treeUri text alone said nothing about the permission. */
        val folderConnected:Boolean,
        val modelState:String,
        /** Recordings waiting for their text (QUEUED since 10/1, skipped ones not counted), when the oldest of them entered
         * the queue (review #10, #11: not the call time), and when the last one was done (0: never). */
        val transcribeBacklog:Int=0,val lastTranscribedMs:Long=0,
        val consent:Boolean,
        /** Recent (live) calls without a result for over [LIVE_LATE_MS], the failed ones (an empty transcript is no failure the
         * user can fix), how long the oldest late one has waited, and when the last result came. */
        val waiting:Int=0,val failed:Int=0,val oldestWaitMs:Long?=null,val lastAnalyzedMs:Long=0,
        val notifyAllowed:Boolean=true,
        val updateError:String="",val updateReady:Boolean=false,
        val folderReadable:Boolean=folderConnected,val lastDiscoveryMs:Long=now,
        val oldestQueuedMs:Long?=null,
        /** Since when a downloaded newer version waits to be installed (0: none or unknown). */
        val updateReadySinceMs:Long=0)

    const val TRANSCRIBE_LATE_MS=6*3_600_000L
    const val ANALYZE_LATE_MS=3*3_600_000L
    /** A live call without its result for this long turns the light amber (design 3.6). */
    const val LIVE_LATE_MS=6*3_600_000L
    const val NO_RESULT_MS=12*3_600_000L
    /** The folder light wants a good discovery cycle within a day, and an update installed within a day of being ready. */
    const val DISCOVERY_LATE_MS=24*3_600_000L
    const val UPDATE_LATE_MS=24*3_600_000L
    const val OK="정상"

    fun waitWords(ms:Long):String { val m=(ms/60_000).coerceAtLeast(0);return if(m<60) "${m}분" else "${m/60}시간"+(if(m%60>0) " ${m%60}분" else "") }

    fun lights(i:Input):List<Light> {
        fun light(tag:String,label:String,problem:String?)=Light(tag,label,problem==null,problem ?: OK)
        val import=when {
            !i.folderConnected -> "녹음 폴더를 아직 연결하지 않았어요. 위 연결 묶음에서 폴더를 골라 주세요."
            !i.folderReadable -> "녹음 폴더 연결이 풀렸어요. 위 연결 묶음에서 폴더를 다시 골라 주세요."
            i.lastDiscoveryMs<=0 || i.now-i.lastDiscoveryMs>DISCOVERY_LATE_MS ->
                "하루 넘게 녹음을 가져오지 못했어요. 위 연결 묶음에서 폴더를 다시 골라 주세요."
            else -> null }
        val transcribe=when {
            i.modelState=="UNSUPPORTED" -> "이 휴대폰에서는 통화를 글자로 바꿀 수 없어요."
            i.modelState=="FAILED" -> "음성 인식 모델을 받지 못했어요. Wi-Fi에서 아래 모델 받기를 눌러 주세요."
            i.modelState!="READY" -> "음성 인식 모델을 아직 받지 않았어요. Wi-Fi에서 아래 모델 받기를 눌러 주세요."
            i.transcribeBacklog>0 && (i.oldestQueuedMs?.let { i.now-it>TRANSCRIBE_LATE_MS } ?: true) && i.now-i.lastTranscribedMs>TRANSCRIBE_LATE_MS ->
                "녹음 ${i.transcribeBacklog}개가 밀려 있어요. 충전기에 꽂아 두면 밤에 끝나요."
            else -> null }
        val analyze=when {
            !i.consent -> "정리 동의가 꺼져 있어요. 아래 정리에서 켜 주세요."
            i.failed>0 -> "정리하지 못한 통화가 ${i.failed}건 있어요. 아래 기다리는 통화 다시 정리하기를 눌러 주세요."
            i.waiting>0 -> (i.oldestWaitMs?.let { "통화 ${i.waiting}건이 ${waitWords(it)} 밀렸어요. " } ?: "정리가 늦어지고 있어요. ")+"순서대로 정리하고 있으니 기다려 주세요."
            else -> null }
        val notify=if(!i.notifyAllowed) "알림이 꺼져 있어요. 휴대폰 설정에서 이음 알림을 켜 주세요." else null
        val update=when {
            // The stored value is a code for the heartbeat; the light shows only its Korean words (a cancel is no error).
            CallUpdate.errorWords(i.updateError)!=null -> CallUpdate.errorWords(i.updateError)!!.sentence
            i.updateReady && i.updateReadySinceMs>0 && i.now-i.updateReadySinceMs>UPDATE_LATE_MS -> "새 버전이 하루 넘게 설치되지 않았어요. 아래 지금 설치를 눌러 주세요."
            i.updateReady -> "새 버전이 준비됐어요. 아래 지금 설치를 눌러 주세요."
            else -> null }
        return listOf(light("status_import","녹음 가져오기",import),light("status_transcribe","글자로 바꾸기",transcribe),
            light("status_analyze","통화 정리",analyze),light("status_notify","알림",notify),light("status_update","업데이트",update))
    }

    // ---- what the lights, the heartbeat and the self-check read from the store (one place, so they agree) ----

    /** Transcription queue split by the first pass: [newCount] recent recordings, [oldCount] first-pass ones; each with the
     * time its oldest row entered the queue (jobs.updated_ms), not the call time. Recordings before 10/1 are not counted. */
    data class Queue(val newCount:Int=0,val newOldestMs:Long?=null,val oldCount:Int=0,val oldOldestMs:Long?=null) {
        val count get()=newCount+oldCount
        val oldestMs get()=listOfNotNull(newOldestMs,oldOldestMs).minOrNull()
    }
    /** One QUEUED row: the recording's call time (null: unknown) and when the row entered the queue. */
    data class QueuedRow(val callMs:Long?,val enteredMs:Long)
    fun queue(rows:List<QueuedRow>,s:JSONObject):Queue {
        val kept=rows.filterNot { FirstPass.beforeRange(it.callMs) }
        val (old,new)=kept.partition { r -> r.callMs?.takeIf { it>0 }?.let { FirstPass.isOld(it,s) } ?: false }
        return Queue(new.size,new.minOfOrNull { it.enteredMs },old.size,old.minOfOrNull { it.enteredMs })
    }
    /** One call record waiting for its result: when it was stored and the call's time. */
    data class WaitingRow(val createdMs:Long,val dayMs:Long)
    /** Live (not first-pass) records without a result for over [LIVE_LATE_MS], and how long the oldest of them waited. */
    fun lateLive(rows:List<WaitingRow>,s:JSONObject,now:Long):Pair<Int,Long?> {
        val late=rows.filter { !FirstPass.isOld(it.dayMs,s) && now-it.createdMs>LIVE_LATE_MS }
        return late.size to late.minOfOrNull { it.createdMs }?.let { now-it }
    }
    /** The protected store's share in use (design B1, review data #4): the sealed file against its 16 MB limit and the rows
     * against the 100,000 row limit of a snapshot, whichever is fuller. */
    const val ROW_LIMIT=100_000L
    fun dbPercent(bytes:Long?,rows:Long?):Int? {
        if(bytes==null && rows==null) return null
        val b=bytes?.let { it*100/CallRecordStore.DB_LIMIT_BYTES } ?: 0
        val r=rows?.let { it*100/ROW_LIMIT } ?: 0
        return maxOf(b,r).toInt().coerceIn(0,100)
    }

    /** Everything above, read in one go from the store. Counts and times only. Each part is null when it could not be read. */
    data class Store(val queue:Queue?,val lateLive:Int?,val oldestLateMs:Long?,val failed:Int?,val dbBytes:Long?,val dbRows:Long?) {
        val dbPercent:Int? get()=dbPercent(dbBytes,dbRows)
    }
    fun dbFile(app:Context)=kr.co.callsummary.core.android.ProtectedDatabase.file(app,kr.callbrief.GeneralLocalStore.DATABASE)
    fun read(app:Context,s:JSONObject,now:Long):Store {
        val bytes=runCatching { dbFile(app).takeIf { it.isFile }?.length() }.getOrNull()
        var queue:Queue?=null;var late:Int?=null;var oldestLate:Long?=null;var failed:Int?=null;var rowsTotal:Long?=null
        runCatching {
            kr.co.callsummary.core.android.AndroidSqlPort(app,kr.callbrief.GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p ->
                fun n(v:Any?)=(v as? Number)?.toLong()
                val tables=p.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").map { it["name"].toString() }.toSet()
                runCatching { rowsTotal=tables.sumOf { t -> n(p.query("SELECT count(*) AS n FROM \"${t.replace("\"","\"\"")}\"").single()["n"]) ?: 0L } }
                if("call_transcription_jobs" in tables) runCatching {
                    val call=if("local_files" in tables) "coalesce(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms)" else "coalesce(r.original_started_at_ms,r.modified_at_ms)"
                    val join=if("local_files" in tables) "LEFT JOIN local_files l ON l.recording_id=j.recording_id" else ""
                    queue=queue(p.query("SELECT j.updated_ms AS entered,$call AS call_ms FROM call_transcription_jobs j LEFT JOIN recordings r ON r.recording_id=j.recording_id $join WHERE j.state='QUEUED'")
                        .map { QueuedRow(n(it["call_ms"]),n(it["entered"]) ?: now) },s)
                } else queue=Queue()
                if("call_records" in tables) runCatching {
                    val (c,o)=lateLive(p.query("SELECT created_ms,coalesce(started_ms,created_ms) AS day_ms FROM call_records WHERE state IN ('TRANSCRIBED','WAITING_CONSENT')")
                        .map { WaitingRow(n(it["created_ms"]) ?: now,n(it["day_ms"]) ?: now) },s,now)
                    late=c;oldestLate=o
                    failed=(n(p.query("SELECT count(*) AS n FROM call_records WHERE state='FAILED' AND coalesce(error,'')!='EMPTY_TRANSCRIPT'").single()["n"]) ?: 0L).toInt()
                } else { late=0;failed=0 }
            }
        }.onFailure { android.util.Log.w("ieum.status","store not read: ${it.javaClass.simpleName}") }
        return Store(queue,late,oldestLate,failed,bytes,rowsTotal)
    }
    /** Android still lets the app read the chosen folder (the heartbeat's "folder" and the folder light). */
    fun folderReadable(app:Context,s:JSONObject):Boolean {
        val tree=s.optString("treeUri")
        return tree.isNotBlank() && runCatching { app.contentResolver.persistedUriPermissions.any { it.uri.toString()==tree && it.isReadPermission } }.getOrDefault(false)
    }
}
