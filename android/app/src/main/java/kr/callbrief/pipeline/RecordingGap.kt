package kr.callbrief.pipeline

import android.content.Context
import android.util.Log
import kr.callbrief.SecureSettings
import org.json.JSONObject

/** DESIGN_V4 2.1 (v3.1, r12-1): are calls being recorded? After every discovery pass the last day of the call log is held
 * against the files of the recording folder ([judge]); the verdict ([PipelineSettingsKeys.GAP_OK] and friends) is kept under
 * [PipelineSettingsKeys.RECORDING_GAP] for the heartbeat (recordingGap, recordingGapVerdict). The wife hears about it
 * ([banner], [lightText], the evening self-check) only for noRecording, partial and folder, and only once the problem has held
 * across two judgements more than [CONFIRM_MS] apart; noMatch goes to the heartbeat and the watchdog only. Counts and times
 * only: no number, name or file name is kept. */
internal object RecordingGap {
    /** One judgement: n target calls, how many had a file in their window, how many of those paired with a record. [sinceMs]:
     * since when this same code holds; [atMs]: when it was judged. */
    data class Verdict(val code:String,val calls:Int=0,val withFile:Int=0,val matched:Int=0,val newestFileMs:Long?=null,val sinceMs:Long=0,val atMs:Long=0,
                       /** v3.4: calls with a file whose text is still being made (its job queued or running). */ val pending:Int=0) {
        fun json():JSONObject=JSONObject().put("calls",calls).put("withFile",withFile).put("matched",matched)
            .put("newestFileMs",newestFileMs ?: JSONObject.NULL).put("sinceMs",sinceMs).put("verdict",code).put("atMs",atMs)
            .apply { if(pending>0) put("pending",pending) }
    }
    /** v3.4 (DESIGN_V4 4.2-5, r14-2): a verdict code beside the key commit's: the calls have their files, but the files are
     * still waiting to become text (not a failure: [GAP_NO_MATCH][PipelineSettingsKeys.GAP_NO_MATCH] is kept for files whose
     * text failed or whose kind is not supported). Not told as a problem; the recording light says it in green ([pendingText]). */
    const val PENDING_TEXT="pendingText"
    /** The one line under the home numbers (null: nothing to say); tapping it opens settings at the recording row
     * ([StatusLights] tag "status_import"). */
    data class Banner(val text:String)

    // ---- the rule (2.1-1) -----------------------------------------------------------------------------------------
    /** A call's window: a file named (or, without a time in its name, modified) from 2 minutes before the call started to
     * 5 minutes after it ended belongs to it. The per-call "녹음 없음" check uses the same window. */
    const val BEFORE_MS=2*60_000L
    const val AFTER_MS=5*60_000L
    const val DAY_MS=24*3_600_000L
    const val MIN_CALL_MS=30_000L
    /** A call counts once the last good discovery pass started this long after it ended (its file had time to arrive). */
    const val SETTLE_MS=15*60_000L
    const val MIN_CALLS=3
    /** Two judgements this far apart before the wife is told (2.1-2). */
    const val CONFIRM_MS=6*3_600_000L
    /** A verdict older than this (no discovery since) is not shown any more; the folder light says why. */
    const val STALE_MS=48*3_600_000L
    /** The codes the wife is told about. */
    val SPOKEN=setOf(PipelineSettingsKeys.GAP_NO_RECORDING,PipelineSettingsKeys.GAP_PARTIAL,PipelineSettingsKeys.GAP_FOLDER)

    /** Settings keys of this file (r12-1): since when a spoken code holds without a break (noRecording and partial may take
     * turns, the clock keeps going), and the KST days of the first and the last self-check notice of that run. */
    const val BAD_SINCE_KEY="recordingGapBadSinceMs"
    const val NOTICE_FIRST_DAY_KEY="recordingGapNoticeFirstDay"
    const val NOTICE_LAST_DAY_KEY="recordingGapNoticeLastDay"

    fun window(startMs:Long,durationMs:Long)=(startMs-BEFORE_MS)..(startMs+durationMs.coerceAtLeast(0)+AFTER_MS)

    /** One stored call-log line: answered/made ([type] 1 or 2), on a number found personal, tied to a recording. */
    data class GapCall(val startMs:Long,val durationMs:Long,val type:Int,val personal:Boolean,val matched:Boolean)
    /** What one judgement looks at. [fileTimes]: the folder's recordings by the time in their name, else their modified time.
     * [lastDiscoveryMs]: when the last good pass ended (0: never). */
    data class Facts(val calls:List<GapCall>,val fileTimes:List<Long>,val folderError:Boolean=false,val truncated:Boolean=false,val lastDiscoveryMs:Long,
                     /** v3.4: the times of the files whose text is still being made (transcription job queued or running). */
                     val pendingTimes:List<Long> =emptyList())

    /** 2.1-1, pure: the targets are the last day's answered and made calls of 30 seconds or more, not personal (excluded
     * numbers are never stored), that ended at least [SETTLE_MS] before the last good pass. The order: folder error, a cut
     * listing, too few calls, then the shares. [previous] keeps [Verdict.sinceMs] while the code stays the same. */
    fun judge(f:Facts,now:Long,previous:Verdict?=null):Verdict {
        fun verdict(code:String,n:Int=0,with:Int=0,matched:Int=0,pending:Int=0)=Verdict(code,n,with,matched,f.fileTimes.maxOrNull(),
            previous?.takeIf { it.code==code && it.sinceMs>0 }?.sinceMs ?: now,now,pending)
        if(f.folderError) return verdict(PipelineSettingsKeys.GAP_FOLDER)
        if(f.truncated) return verdict(PipelineSettingsKeys.GAP_TOO_MANY)
        val targets=f.calls.filter { c -> (c.type==1 || c.type==2) && c.durationMs>=MIN_CALL_MS && !c.personal &&
            c.startMs>=now-DAY_MS && c.startMs+c.durationMs<=f.lastDiscoveryMs-SETTLE_MS }
        val sorted=f.fileTimes.sorted()
        val withFile=targets.filter { c -> window(c.startMs,c.durationMs).let { w -> anyIn(sorted,w.first,w.last) } }
        val n=targets.size;val w=withFile.size;val m=withFile.count { it.matched }
        // v3.4: a call with its file whose text is still being made is not "no match" (yet).
        val waiting=f.pendingTimes.sorted()
        val p=withFile.count { c -> !c.matched && window(c.startMs,c.durationMs).let { win -> anyIn(waiting,win.first,win.last) } }
        val code=when {
            n<MIN_CALLS -> PipelineSettingsKeys.GAP_OK
            w<0.3*n -> PipelineSettingsKeys.GAP_NO_RECORDING
            w<0.7*n -> PipelineSettingsKeys.GAP_PARTIAL
            m<0.5*w && m+p>=0.5*w -> PENDING_TEXT
            m<0.5*w -> PipelineSettingsKeys.GAP_NO_MATCH
            else -> PipelineSettingsKeys.GAP_OK }
        return verdict(code,n,w,m,p)
    }
    /** Is there a value of the sorted [times] within [from]..[to]? */
    internal fun anyIn(times:List<Long>,from:Long,to:Long):Boolean {
        var i=java.util.Collections.binarySearch(times,from);if(i<0) i=-i-1
        return i<times.size && times[i]<=to
    }

    // ---- what is kept -----------------------------------------------------------------------------------------------
    /** The last stored verdict, or null before the first judgement. */
    fun last(s:JSONObject):Verdict? {
        val o=when(val v=s.opt(PipelineSettingsKeys.RECORDING_GAP)) { is JSONObject -> v;is String -> runCatching { JSONObject(v) }.getOrNull();else -> null } ?: return null
        val code=o.optString("verdict").takeIf { it.isNotBlank() } ?: return null
        return Verdict(code,o.optInt("calls"),o.optInt("withFile"),o.optInt("matched"),
            o.optLong("newestFileMs",0).takeIf { !o.isNull("newestFileMs") && it>0 },o.optLong("sinceMs"),o.optLong("atMs"),o.optInt("pending",0))
    }
    /** v3.4: "녹음은 있는데 아직 글자로 바꾸는 중 2건이에요" while the last verdict ([PENDING_TEXT], not older than [STALE_MS]) says
     * so; null otherwise. Said in green on the recording light: nothing is wrong, the text just is not made yet. */
    fun pendingText(s:JSONObject,now:Long=System.currentTimeMillis()):String? {
        val v=last(s)?.takeIf { it.code==PENDING_TEXT && now-it.atMs<=STALE_MS && it.pending>0 } ?: return null
        return "녹음은 있는데 아직 글자로 바꾸는 중 ${v.pending}건이에요"
    }
    /** Writes [v] into the settings snapshot [s]: the verdict, the spoken run's start, and the notice days reset once it ends. */
    fun keep(s:JSONObject,v:Verdict):JSONObject {
        s.put(PipelineSettingsKeys.RECORDING_GAP,v.json().toString())
        if(v.code in SPOKEN) { if(s.optLong(BAD_SINCE_KEY,0)<=0) s.put(BAD_SINCE_KEY,v.atMs) }
        else { s.remove(BAD_SINCE_KEY);s.remove(NOTICE_FIRST_DAY_KEY);s.remove(NOTICE_LAST_DAY_KEY) }
        return s
    }
    /** The verdict to tell the wife about now: a spoken code held across two judgements [CONFIRM_MS] apart (for tooMany, its
     * own run), not older than [STALE_MS]; null otherwise. */
    fun confirmed(s:JSONObject,now:Long=System.currentTimeMillis()):Verdict? {
        val v=last(s) ?: return null
        if(now-v.atMs>STALE_MS) return null
        val since=when(v.code) {
            in SPOKEN -> s.optLong(BAD_SINCE_KEY,0).takeIf { it>0 } ?: v.sinceMs
            PipelineSettingsKeys.GAP_TOO_MANY -> v.sinceMs
            else -> return null }
        return v.takeIf { it.atMs-since>=CONFIRM_MS }
    }

    // ---- the words (2.1-2) ------------------------------------------------------------------------------------------
    const val PHONE_PATH="전화 앱 › 설정 › 통화 녹음 › 자동으로 통화 녹음 › 모든 통화를 켜 주세요"
    fun counted(v:Verdict)="최근 하루 통화 ${v.calls}건 중 녹음은 ${v.withFile}건이에요."
    /** The status light's words for [v] (the folder light's own problems come first in [StatusLights]). */
    fun lightWords(v:Verdict):String? = when(v.code) {
        PipelineSettingsKeys.GAP_NO_RECORDING -> "${counted(v)} $PHONE_PATH."
        PipelineSettingsKeys.GAP_PARTIAL -> "일부만 녹음되고 있어요. ${counted(v)} $PHONE_PATH."
        PipelineSettingsKeys.GAP_FOLDER -> "녹음 폴더를 다시 골라 주세요. 위 연결 묶음에 있어요."
        PipelineSettingsKeys.GAP_TOO_MANY -> "녹음 폴더에 파일이 너무 많아요. 오래된 녹음을 다른 폴더로 옮겨 주세요."
        else -> null }
    /** The settings group the light points to: the folder in 연결; the phone's own recording switch is outside the app. */
    fun lightTarget(v:Verdict):String? = if(v.code==PipelineSettingsKeys.GAP_FOLDER || v.code==PipelineSettingsKeys.GAP_TOO_MANY) StatusLights.GROUP_CONNECT else null
    /** The orange text of the "녹음 가져오기" light, or null when the light keeps its own words. */
    fun lightText(s:JSONObject):String? = confirmed(s)?.let(::lightWords)
    /** The home banner's words for [v] (spoken codes only). */
    fun bannerWords(v:Verdict):String? = when(v.code) {
        PipelineSettingsKeys.GAP_NO_RECORDING -> "통화 녹음이 꺼져 있는 것 같아요. ${counted(v)}"
        PipelineSettingsKeys.GAP_PARTIAL -> "통화가 일부만 녹음되고 있어요. ${counted(v)}"
        PipelineSettingsKeys.GAP_FOLDER -> "녹음 폴더 연결이 풀렸어요. 녹음 폴더를 다시 골라 주세요."
        else -> null }
    fun banner(s:JSONObject,now:Long=System.currentTimeMillis()):Banner? = confirmed(s,now)?.takeIf { it.code in SPOKEN }?.let(::bannerWords)?.let(::Banner)
    /** The home banner — only for noRecording, partial and folder, and only from the second judgement 6 hours apart. */
    fun banner(c:Context):Banner? = runCatching { banner(SecureSettings(CallPipelineStartup.storage(c)).snapshot()) }.getOrNull()

    /** The evening self-check notice of a confirmed spoken verdict ([SelfCheckNotices]): the first day, the second, then
     * every 7 days ([rememberNotice] after it was posted). */
    fun noticeDue(s:JSONObject,now:Long):Verdict? {
        val v=confirmed(s,now)?.takeIf { it.code in SPOKEN } ?: return null
        val today=SelfCheckNotices.day(now)
        val first=s.optString(NOTICE_FIRST_DAY_KEY);val last=s.optString(NOTICE_LAST_DAY_KEY)
        if(first.isBlank()) return v
        if(last==today) return null
        fun days(a:String)=runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a),java.time.LocalDate.parse(today)) }.getOrDefault(Long.MAX_VALUE)
        return v.takeIf { days(first)==1L || last.isBlank() || days(last)>=7 }
    }
    fun rememberNotice(s:JSONObject,now:Long):JSONObject {
        val today=SelfCheckNotices.day(now)
        if(s.optString(NOTICE_FIRST_DAY_KEY).isBlank()) s.put(NOTICE_FIRST_DAY_KEY,today)
        return s.put(NOTICE_LAST_DAY_KEY,today)
    }

    // ---- after a discovery pass ---------------------------------------------------------------------------------------
    /** Pass codes that mean Android no longer lets the app read the chosen folder. */
    val FOLDER_CODES=setOf("HELD_SOURCE:SELECTED_FOLDER_READ_GRANT_REQUIRED","DISCOVERY_CHECKED:FOLDER_READ_GRANT_MISSING")
    const val CHECKED="DISCOVERY_CHECKED"
    private const val LOG="ieum.recgap"

    /** Called at the end of every discovery pass ([kr.callbrief.AutomaticRecordingDiscovery.cycle]) with its code and when it
     * started. A good pass judges the day and decides the per-call "녹음 없음" ([CallFollowupNotice.checkNoRecording]); a
     * folder error is judged as such; anything else (stopped, no folder chosen yet, already running) leaves the verdict.
     * Never throws. */
    fun afterPass(context:Context,code:String,startedMs:Long,now:Long=System.currentTimeMillis()) {
        val folderError=code in FOLDER_CODES
        if(!folderError && code!=CHECKED) return
        try {
            val app=CallPipelineStartup.storage(context)
            val settings=SecureSettings(app);val s=settings.snapshot()
            val read=if(folderError) null else read(app,s,now)
            val facts=read ?: Facts(emptyList(),emptyList(),folderError,false,0)
            val v=judge(facts.copy(folderError=folderError,truncated=s.optBoolean(SecureSettings.INVENTORY_TRUNCATED,false),
                lastDiscoveryMs=s.optLong(kr.callbrief.AutomaticRecordingDiscovery.LAST_DISCOVERY_MS,0)),now,last(s))
            settings.update { keep(it,v) }
            if(read!=null) { val times=read.fileTimes.sorted()
                CallFollowupNotice.checkNoRecording(app,startedMs,now) { from,to -> anyIn(times,from,to) } }
        } catch(e:Exception) { Log.w(LOG,"not judged: ${e.javaClass.simpleName}") }
    }

    /** The stored call log of the last [DAY_MS] (plus what the per-call check needs: [CallFollowupNotice.MAX_AGE_MS]) and the
     * folder's file times, read from the local store. */
    internal fun read(app:Context,s:JSONObject,now:Long):Facts? = runCatching {
        kr.co.callsummary.core.android.AndroidSqlPort(app,kr.callbrief.GeneralLocalStore.DATABASE,app.assets.open("call_core_schema.sql")).use { p ->
            fun n(v:Any?)=(v as? Number)?.toLong()
            val tables=p.query("SELECT name FROM sqlite_master WHERE type='table'").map { it["name"].toString() }.toSet()
            val from=now-maxOf(DAY_MS,CallFollowupNotice.MAX_AGE_MS)-BEFORE_MS-AFTER_MS
            val calls=if("call_log" !in tables) emptyList() else {
                val personal=if("personal_numbers" in tables) "(SELECT 1 FROM personal_numbers n WHERE n.phone_key=l.number_key)" else "NULL"
                val matched=if("call_record_log" in tables) "(SELECT 1 FROM call_record_log x WHERE x.log_id=l.log_id)" else "NULL"
                p.query("SELECT l.start_ms,l.duration_ms,l.type,$personal AS personal,$matched AS matched FROM call_log l WHERE l.start_ms>=CAST(? AS INTEGER)",now-DAY_MS)
                    .map { GapCall(n(it["start_ms"]) ?: 0,n(it["duration_ms"]) ?: 0,(n(it["type"]) ?: 0).toInt(),it["personal"]!=null,it["matched"]!=null) }
            }
            val times=ArrayList<Long>()
            val tree=s.optString("treeUri")
            if("discovery_documents" in tables && tree.isNotBlank())
                p.query("SELECT name,modified FROM discovery_documents WHERE root=?",tree).forEach { r ->
                    val t=kr.callbrief.DiscoveryCutoff.startedAtMs(r["name"]?.toString().orEmpty()) ?: n(r["modified"])?.takeIf { it>0 }
                    if(t!=null && t>=from) times+=t }
            if("recordings" in tables) {
                val at=if("local_files" in tables) "COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms)" else "COALESCE(r.original_started_at_ms,r.modified_at_ms)"
                val join=if("local_files" in tables) "LEFT JOIN local_files l ON l.recording_id=r.recording_id" else ""
                p.query("SELECT $at AS t FROM recordings r $join WHERE $at>=CAST(? AS INTEGER)",from).forEach { r -> n(r["t"])?.let { times+=it } }
            }
            // v3.4: files whose text is still being made (their transcription job is queued or running).
            val pending=ArrayList<Long>()
            if("recordings" in tables && "call_transcription_jobs" in tables) runCatching {
                val at=if("local_files" in tables) "COALESCE(r.original_started_at_ms,l.filename_time_candidate_ms,r.modified_at_ms)" else "COALESCE(r.original_started_at_ms,r.modified_at_ms)"
                val join=if("local_files" in tables) "LEFT JOIN local_files l ON l.recording_id=r.recording_id" else ""
                p.query("SELECT DISTINCT $at AS t FROM recordings r $join JOIN call_transcription_jobs j ON j.recording_id=r.recording_id WHERE j.state IN('QUEUED','RUNNING') AND $at>=CAST(? AS INTEGER)",from)
                    .forEach { r -> n(r["t"])?.let { pending+=it } }
            }
            Facts(calls,times,lastDiscoveryMs=0,pendingTimes=pending)
        }
    }.onFailure { Log.w(LOG,"store not read: ${it.javaClass.simpleName}") }.getOrNull()
}
