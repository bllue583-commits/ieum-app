package kr.callbrief.pipeline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.util.Log
import kr.callbrief.R
import kr.callbrief.SecureSettings
import kr.callbrief.core.PhoneKey
import kr.callbrief.core.PrivacyRules
import kr.callbrief.pipeline.call.CallSession
import org.json.JSONArray
import org.json.JSONObject

/** One notice per finished work call (design 6.2, r10-2): "김민수 대표와 통화를 정리하고 있어요" as soon as the call ends, the same
 * notice turned into "정리됐어요" with the summary's first sentence and one check-item line when the result comes in, "정리가 늦어지고
 * 있어요" after [LATE_MS] without one, and gone once the evening report has gone out. A [메모 남기기] button takes one line
 * (RemoteInput) and keeps it as the user's own check item, linked to the call.
 *
 * Only calls with a confirmed work counterpart (a contact card, or a name in the phone book) get a notice; personal and excluded
 * numbers get nothing. What the notices are about is kept in [SecureSettings] under [STATE_KEY] (a JSON text, so the heartbeat
 * never carries it): the notice id, the counterpart's words, the call's times and a hash of its number (never the number itself),
 * the call record once known, and the state. Every public function never throws. */
internal object CallFollowupNotice {
    const val CHANNEL="ieum.calls.followup"
    /** Notification tag of every follow-up notice; the id tells them apart ([idFor]). */
    const val TAG="ieum.calls.followup"
    const val STATE_KEY="callFollowups"
    /** When the evening report last went out (ms): notices of calls before it are gone. */
    const val EVENING_SENT_KEY="followupEveningSentMs"
    /** Without a result for this long after the call ended, the notice says it is late (it goes into the evening report). */
    const val LATE_MS=6*3_600_000L
    /** A record whose call time is this close to a notice's call (either way) is that call. */
    const val MATCH_MS=2*60_000L
    /** Notices kept at most (the oldest go first) and for at most this long whatever happens. */
    const val MAX_ENTRIES=40
    const val MAX_AGE_MS=36*3_600_000L
    const val WORKING="working";const val DONE="done";const val LATE="late"
    /** v3.4 (DESIGN_V4 4.2-4 가): a call that went out, kept without a notice until the call log says it was connected (its
     * length > 0); a call the log says was 0 seconds goes without a word. */
    const val HOLD="hold"
    /** How long a call going out may stay [HOLD] without the call log saying anything about it. */
    const val HOLD_MAX_MS=10*60_000L
    /** v3.1 (DESIGN_V4 2.1-3): a good discovery pass that started after the call found no file in the call's window. */
    const val NO_RECORDING="norec"
    /** A pass this long after the call ended can see its recording (the recorder closed its file). */
    const val NO_RECORDING_AFTER_MS=2*60_000L
    private const val LOG="ieum.followup"

    /** The notice id of a call record: `("followup"+recordId).hashCode()`. A notice posted before its record existed keeps the id
     * it was posted with (phone key + end time); the record is then mapped onto it in [STATE_KEY]. */
    fun idFor(recordId:String)=("followup"+recordId).hashCode()
    internal fun tempId(phoneKey:String,endedMs:Long)=("followup"+phoneKey+endedMs).hashCode()

    /** The phone-book name of [phoneKey] (the call log's cached name), or null. Replaceable in tests. */
    internal var phoneBookName:(Context,String)->String? = ::callLogName
    /** The call store. Replaceable in tests. */
    internal var store:(Context)->CallRecordStore ={ kr.callbrief.IeumApplication.store(it) }
    /** v3.4: the call log's length of the call of [Entry] (ms; 0: not connected), null when the log does not say (yet),
     * [UNREADABLE] when the app may not read the call log (then a call going out is told as before). Replaceable in tests. */
    internal var logDuration:(Context,Entry)->Long? = ::callLogDuration
    /** How long [onCallEnded] waits for the call log of a call going out (the phone writes it a moment after the call), in steps. */
    internal var logWaitMs:Long=1_000L
    const val LOG_TRIES=3
    const val UNREADABLE=-1L
    internal fun resetForTest() { phoneBookName=::callLogName;store={ kr.callbrief.IeumApplication.store(it) };logDuration=::callLogDuration;logWaitMs=1_000L }

    /** What one notice is about. [who]: "김민수 대표"; [hash]: SHA-256 of the phone key; [record]: the call record once known.
     * [dir]: "in" or "out" (v3.4; null in older entries). */
    internal data class Entry(val id:Int,val who:String,val company:String?,val hash:String,val startedMs:Long,val endedMs:Long,
                              val record:String?=null,val state:String=WORKING,
                              /** Check items written with [메모 남기기] on this notice (bound to [record] once it is known). */ val items:List<String> =emptyList(),
                              val dir:String?=null) {
        val memos:Int get()=items.size
        fun json():JSONObject=JSONObject().put("id",id).put("who",who).put("co",company ?: JSONObject.NULL).put("h",hash).put("s",startedMs).put("e",endedMs)
            .put("r",record ?: JSONObject.NULL).put("st",state).put("i",JSONArray(items)).apply { dir?.let { put("d",it) } }
        companion object {
            fun parse(o:JSONObject)=Entry(o.getInt("id"),o.getString("who"),o.optString("co").takeIf { !o.isNull("co") && it.isNotEmpty() },o.optString("h"),
                o.optLong("s"),o.optLong("e"),o.optString("r").takeIf { !o.isNull("r") && it.isNotEmpty() },o.optString("st",WORKING),
                o.optJSONArray("i")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
                o.optString("d").takeIf { it.isNotEmpty() })
        }
    }

    // ---- 1. the call ended ------------------------------------------------------------------------------------------
    /** Called by r10-1's [CallSession] when a call is over (on a background thread: it reads the store and the call log).
     * Posts "정리하고 있어요" for an answered call with a confirmed work counterpart; does nothing otherwise. */
    fun onCallEnded(context:Context,end:CallSession.CallEnd) {
        try {
            val s=settings(context)
            if(!enabled(s) || !end.answered) return
            // 2.1-3: while calls are not being recorded at all, the home banner speaks instead of one notice per call.
            if(RecordingGap.last(s)?.let { it.code==PipelineSettingsKeys.GAP_NO_RECORDING && end.endedMs-it.atMs<RecordingGap.STALE_MS }==true) return
            val key=PhoneKey.of(end.phoneKey) ?: return
            val rules=kr.callbrief.LocalPrivacy.rules(s)
            if(rules.excluded(key,key)) return
            val db=store(context)
            if(db.isPersonal(key)) return
            val card=ContactCards.get(context,key)
            val person=card?.people?.firstOrNull()
            val named=person?.let { listOfNotNull(it.name,it.role?.takeIf { r -> r.isNotBlank() }).joinToString(" ") }
                ?: card?.company?.name ?: phoneBookName(context,key)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if(rules.excluded(named,key)) return
            val out=end.direction==CallSession.Direction.OUT
            var e=Entry(tempId(key,end.endedMs),named,card?.company?.name,PrivacyRules.hash(key),end.startedMs,end.endedMs,dir=if(out) "out" else "in")
            // v3.4 (4.2-4 가): "answered" of a call going out only says it was placed. Only a call the log says was connected
            // gets a notice; one it does not know yet waits ([HOLD]) for the next discovery pass.
            if(out) {
                var length:Long?=null
                for(i in 0 until LOG_TRIES) { length=runCatching { logDuration(context,e) }.getOrNull();if(length!=null) break;if(logWaitMs>0 && i<LOG_TRIES-1) Thread.sleep(logWaitMs) }
                if(length==0L) return
                if(length==null) e=e.copy(state=HOLD)
            }
            save(context) { list -> list.filter { it.id!=e.id }+e }
            if(e.state!=HOLD) post(context,e,null)
        } catch(t:Throwable) { Log.w(LOG,"not posted: ${t.javaClass.simpleName}") }
    }

    // ---- 2. the result came in --------------------------------------------------------------------------------------
    /** Called right after a result was applied to [recordId] ([CallRecordAnalysisRunner]). The notice of that call, if there is
     * one, becomes "정리됐어요" (a personal result takes it away). [db]: the store the caller holds, else the process store. */
    fun onResult(context:Context,recordId:String,db:CallRecordStore?=null) {
        try {
            val entries=entries(context)
            if(entries.isEmpty()) return
            if(!enabled(settings(context))) return
            val s=db ?: store(context)
            val record=s.record(recordId) ?: return
            val e=entries.firstOrNull { it.record==recordId } ?: match(entries,record) ?: return
            finish(context,e,record,s)
        } catch(t:Throwable) { Log.w(LOG,"result not shown: ${t.javaClass.simpleName}") }
    }

    /** The notice of [record] among [entries] not yet linked: same number (by hash) or, with no number, the only one, and the call
     * time within [MATCH_MS] of the notice's call. */
    internal fun match(entries:List<Entry>,record:CallRecord):Entry? {
        if(record.kind!="통화") return null
        val at=record.startedMs ?: return null
        val near=entries.filter { it.record==null && at>=it.startedMs-MATCH_MS && at<=it.endedMs+MATCH_MS }
        val key=PhoneKey.of(record.counterpartKey)
        return if(key!=null) near.firstOrNull { it.hash==PrivacyRules.hash(key) } else near.singleOrNull()
    }

    private fun finish(context:Context,e:Entry,record:CallRecord,db:CallRecordStore?=null) {
        // Memos written before the record existed now belong to its call.
        if(e.record==null && e.items.isNotEmpty()) runCatching { (db ?: store(context)).bindUserItems(e.items,record.recordId) }
        if(record.analysis?.business==false) { cancel(context,e.id);save(context) { list -> list.filter { it.id!=e.id } };return }
        if(record.state!=CallRecordStore.ANALYZED || record.summary.isNullOrBlank()) {
            // Linked, the result is not in yet: only remember which record it is.
            if(e.record==null) save(context) { list -> list.map { if(it.id==e.id) it.copy(record=record.recordId) else it } }
            return
        }
        val done=e.copy(record=record.recordId,state=DONE)
        save(context) { list -> list.map { if(it.id==e.id) done else it } }
        post(context,done,record)
    }

    // ---- 2b. no recording (v3.1) -------------------------------------------------------------------------------------
    /** Called after every good discovery pass that started at [passStartedMs] ([RecordingGap.afterPass]): a call still waiting
     * whose pass came [NO_RECORDING_AFTER_MS] after it ended and found no file in its window ([RecordingGap.window];
     * [hasFile] answers for the folder) gets "이 통화는 녹음이 없어 정리할 수 없어요" with [메모 남기기], and is counted in
     * [PipelineSettingsKeys.FOLLOWUP_NO_RECORDING]. No timer of its own. Returns how many notices changed. */
    fun checkNoRecording(context:Context,passStartedMs:Long,now:Long=System.currentTimeMillis(),hasFile:(Long,Long)->Boolean):Int {
        return try {
            val all=entries(context)
            if(all.isEmpty() || !enabled(settings(context))) return 0
            // v3.4 (4.2-4 가): first the call log. A call it says was 0 seconds (not connected) goes without a word; a call going
            // out it confirms gets its notice now; one it still does not know after [HOLD_MAX_MS] goes too.
            val waiting=all.filter { e -> (e.state==WORKING || e.state==HOLD) && e.record==null && passStartedMs>=e.endedMs+NO_RECORDING_AFTER_MS }
            val length=waiting.associate { e -> e.id to runCatching { logDuration(context,e) }.getOrNull() }
            val drop=waiting.filter { e -> length[e.id]==0L || (e.state==HOLD && length[e.id]==null && now-e.endedMs>=HOLD_MAX_MS) }.map { it.id }.toSet()
            val confirmed=waiting.filter { e -> e.state==HOLD && length[e.id].let { it!=null && it!=0L } }.map { it.id }.toSet()
            if(drop.isNotEmpty() || confirmed.isNotEmpty()) {
                drop.forEach { cancel(context,it) }
                save(context) { list -> list.filter { it.id !in drop }.map { if(it.id in confirmed && it.state==HOLD) it.copy(state=WORKING) else it } }
            }
            val entries=all.filter { it.id !in drop }.map { if(it.id in confirmed) it.copy(state=WORKING) else it }
            val none=entries.filter { e -> e.state==WORKING && e.record==null && passStartedMs>=e.endedMs+NO_RECORDING_AFTER_MS && now-e.endedMs<MAX_AGE_MS &&
                RecordingGap.window(e.startedMs,e.endedMs-e.startedMs).let { w -> !hasFile(w.first,w.last) } }
            entries.filter { it.id in confirmed && none.none { n -> n.id==it.id } }.forEach { post(context,it,null) }
            if(none.isEmpty()) return confirmed.size
            val ids=none.map { it.id }.toSet()
            save(context) { list -> list.map { if(it.id in ids && it.state==WORKING) it.copy(state=NO_RECORDING) else it } }
            runCatching { SecureSettings(CallPipelineStartup.storage(context)).update {
                it.put(PipelineSettingsKeys.FOLLOWUP_NO_RECORDING,it.optLong(PipelineSettingsKeys.FOLLOWUP_NO_RECORDING,0)+none.size) } }
            none.forEach { post(context,it.copy(state=NO_RECORDING),null) }
            none.size
        } catch(t:Throwable) { Log.w(LOG,"no-recording check failed: ${t.javaClass.simpleName}");0 }
    }

    // ---- 3. the hourly tick and the evening report ------------------------------------------------------------------
    /** The hourly pass and the evening report call this. Notices of calls before an evening report that went out are taken away;
     * one still waiting [LATE_MS] after its call says it is late; one whose result came in without [onResult] is finished. With
     * the switch off every notice goes. */
    fun sweep(context:Context,now:Long=System.currentTimeMillis()) {
        try {
            val entries=entries(context)
            if(entries.isEmpty()) return
            val s=settings(context)
            if(!enabled(s)) { entries.forEach { cancel(context,it.id) };save(context) { emptyList() };return }
            // Calls that ended before the last evening report are in it: their notices go.
            val evening=s.optLong(EVENING_SENT_KEY,0)
            val gone=entries.filter { it.endedMs<evening || now-it.endedMs>MAX_AGE_MS }
            gone.forEach { cancel(context,it.id) }
            val goneIds=gone.map { it.id }.toSet()
            if(goneIds.isNotEmpty()) save(context) { list -> list.filter { it.id !in goneIds } }
            val open=entries.filter { it.id !in goneIds && it.state!=DONE }
            if(open.isEmpty()) return
            // A result that came in by another way (the report's own pass, a notice posted after the result).
            val db=runCatching { store(context) }.getOrNull()
            for(e in open) {
                val record=db?.let { d -> e.record?.let(d::record) ?: runCatching { d.day(CallRecordStore.kstDay(e.startedMs)) }.getOrDefault(emptyList()).firstNotNullOfOrNull { r -> r.takeIf { match(listOf(e),it)!=null } } }
                if(record!=null) { finish(context,e,record,db);if(record.state==CallRecordStore.ANALYZED) continue }
                if(e.state==WORKING && now-e.endedMs>=LATE_MS) {
                    val late=(entries(context).firstOrNull { it.id==e.id } ?: e).copy(state=LATE)
                    save(context) { list -> list.map { if(it.id==e.id) late else it } }
                    post(context,late,null)
                }
            }
        } catch(t:Throwable) { Log.w(LOG,"sweep failed: ${t.javaClass.simpleName}") }
    }

    /** Called by the evening report once it went out ([CallDigestWork]): remembers when, then [sweep] takes away the notices of
     * every call that ended before it. */
    fun afterEvening(context:Context,now:Long=System.currentTimeMillis()) {
        runCatching { SecureSettings(CallPipelineStartup.storage(context)).update { it.put(EVENING_SENT_KEY,now) } }
        sweep(context,now)
    }

    // ---- 4. [메모 남기기]--------------------------------------------------------------------------------------------
    /** The memo button's broadcast ([NotificationActions.ACTION_MEMO]): the line becomes the user's own check item with the call's
     * company, linked to the call record when it is known. The notice is posted again (Android waits for that after a reply). */
    fun handleMemo(context:Context,intent:Intent):Boolean {
        val id=intent.getIntExtra(NotificationActions.EXTRA_FOLLOWUP,0)
        val e=entries(context).firstOrNull { it.id==id }
        return try {
            val text=RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationActions.KEY_MEMO)?.toString()?.trim()?.replace(Regex("\\s+")," ").orEmpty()
            if(text.isEmpty()) { e?.let { repost(context,it) };return false }
            val recordId=intent.getStringExtra(NotificationActions.EXTRA_RECORD) ?: e?.record
            val db=store(context)
            val company=e?.company ?: recordId?.let { db.record(it)?.analysis?.counterpartCompany }
            val item=db.addUserItem(text,company,recordId)
            if(item!=null && e!=null) {
                val next=e.copy(items=e.items+item,record=e.record ?: recordId)
                save(context) { list -> list.map { if(it.id==e.id) next else it } }
                repost(context,next)
            } else e?.let { repost(context,it) }
            item!=null
        } catch(t:Throwable) {
            Log.w(LOG,"memo not kept: ${t.javaClass.simpleName}")
            e?.let { repost(context,it) };false
        }
    }
    private fun repost(context:Context,e:Entry) { post(context,e,e.record?.let { r -> runCatching { store(context).record(r) }.getOrNull() }) }

    // ---- the notice -----------------------------------------------------------------------------------------------
    /** "김민수 대표와", "박지성과": the particle after the counterpart's words. */
    internal fun withParticle(who:String):String {
        val last=who.trimEnd().lastOrNull() ?: return who
        val batchim=last.code in 0xAC00..0xD7A3 && (last.code-0xAC00)%28!=0
        return who+if(batchim) "과" else "와"
    }
    /** v3.5 r15-0: what the notice says while the call is being sorted out, and once that is late (with its record). */
    const val WORKING_WORDS="정리하고 있어요, 끝나면 통화 목록에 보여요"
    const val LATE_WORDS="정리가 늦어지고 있어요, 끝나면 통화 목록에 보여요"
    /** Title, short text and expanded text of the notice in its state. */
    internal data class Words(val title:String,val text:String,val big:String)
    internal fun words(e:Entry,record:CallRecord?):Words {
        val memo=if(e.memos>0) "\n메모 ${e.memos}개를 체크할 일에 넣었어요" else ""
        return when(e.state) {
            DONE -> {
                val first=record?.summary?.let(CallUi::firstSentence)?.takeIf { it.isNotBlank() } ?: "요약이 왔어요"
                val made=record?.applied.orEmpty().filter { it.applied && it.op=="create" }
                val task=when(made.size) { 0 -> "새 체크할 일은 없어요";1 -> "체크할 일: ${made[0].title}";else -> "체크할 일: ${made[0].title} 외 ${made.size-1}개" }
                Words("${withParticle(e.who)} 통화가 정리됐어요",first,"$first\n$task$memo")
            }
            // v3.5 r15-0: facts only, no promise of a report or a time. A call with its record shows up in the call list once done.
            LATE -> if(e.record!=null) Words("${withParticle(e.who)} 통화",LATE_WORDS,"$LATE_WORDS$memo")
                else Words("${withParticle(e.who)} 통화","정리가 늦어지고 있어요","정리가 늦어지고 있어요$memo")
            NO_RECORDING -> Words("${withParticle(e.who)} 통화","이 통화는 녹음이 없어 정리할 수 없어요",
                "이 통화는 녹음이 없어 정리할 수 없어요. 메모를 남기면 체크할 일에 넣어 드려요$memo")
            else -> Words("${withParticle(e.who)} 통화를 정리하고 있어요",WORKING_WORDS,"$WORKING_WORDS$memo")
        }
    }

    private fun post(context:Context,e:Entry,record:CallRecord?):Boolean {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false
        val manager=context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"이음 통화 정리",NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        val w=words(e,record)
        // Done: the call's page; before that the day's calls.
        val open=MainActivity.intent(context,Screen.CALLS,dayMs=e.startedMs,callId=e.record.takeIf { e.state==DONE })
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content=PendingIntent.getActivity(context,e.id,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val memo=Intent(context,NotificationActionReceiver::class.java).setAction(NotificationActions.ACTION_MEMO).setPackage(context.packageName)
            .putExtra(NotificationActions.EXTRA_FOLLOWUP,e.id).apply { e.record?.let { putExtra(NotificationActions.EXTRA_RECORD,it) } }
        // Mutable: the system writes the typed line into it (explicit component, so nothing else can receive it).
        val reply=PendingIntent.getBroadcast(context,e.id,memo,PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0))
        val input=RemoteInput.Builder(NotificationActions.KEY_MEMO).setLabel("메모를 한 줄로 적어 주세요").build()
        val action=Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context,R.drawable.ic_stat_ieum),"메모 남기기",reply)
            .addRemoteInput(input).build()
        val n=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setOnlyAlertOnce(true)
            .setContentTitle(w.title).setContentText(w.text).setContentIntent(content).setAutoCancel(true).setLocalOnly(true)
            .setStyle(Notification.BigTextStyle().setBigContentTitle(w.title).bigText(w.big))
            .setWhen(e.endedMs).setShowWhen(true).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // Lock screen: no name, no summary; only what state the call is in (v3.4, 4.2-4 다).
            .setPublicVersion(Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setContentTitle(publicTitle(e.state)).build())
            .setGroup(GROUP)
            .addAction(action).build()
        manager.notify(TAG,e.id,n)
        summary(context,manager)
        return true
    }
    private fun cancel(context:Context,id:Int) { runCatching { context.getSystemService(NotificationManager::class.java)?.let { m -> m.cancel(TAG,id);summary(context,m) } } }

    // ---- v3.5 r15-0: one bundle for the day's notices ----
    /** The group of every follow-up notice, so 20 to 30 calls a day fold into one entry with a summary. */
    const val GROUP="ieum.calls.followup.group"
    /** The summary's id under [TAG] (no call's [idFor] is this, by the "followup" prefix of theirs). */
    const val SUMMARY_ID=0x1EE0
    /** From this many notices the bundle gets its summary (one notice stands by itself). */
    const val SUMMARY_FROM=2
    /** The summary's title and text for the shown notices' states, e.g. "통화 알림 5개" and "정리됨 3건, 정리 중 2건". */
    internal fun summaryWords(states:List<String>):Pair<String,String> {
        val done=states.count { it==DONE };val late=states.count { it==LATE || it==NO_RECORDING };val working=states.size-done-late
        val parts=listOfNotNull("정리됨 ${done}건".takeIf { done>0 },"정리 중 ${working}건".takeIf { working>0 },"확인할 통화 ${late}건".takeIf { late>0 })
        return "통화 알림 ${states.size}개" to parts.joinToString(", ")
    }
    /** Posts, updates or takes away the bundle's summary after a notice changed. Quiet: the notices themselves alert (once). */
    private fun summary(context:Context,manager:NotificationManager) {
        runCatching {
            val shown=manager.activeNotifications.filter { it.tag==TAG && it.id!=SUMMARY_ID }.map { it.id }.toSet()
            if(shown.size<SUMMARY_FROM) { manager.cancel(TAG,SUMMARY_ID);return }
            val states=entries(context).filter { it.id in shown }.map { it.state }.ifEmpty { shown.map { WORKING } }
            val (title,text)=summaryWords(states)
            val open=MainActivity.intent(context,Screen.CALLS,dayMs=System.currentTimeMillis())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val content=PendingIntent.getActivity(context,SUMMARY_ID,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(TAG,SUMMARY_ID,Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
                .setContentTitle(title).setContentText(text).setContentIntent(content).setAutoCancel(false).setLocalOnly(true)
                .setGroup(GROUP).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setContentTitle(title).build())
                .build())
        }.onFailure { Log.w(LOG,"summary not shown: ${it.javaClass.simpleName}") }
    }
    /** The locked screen's words of a notice in [state]: "통화 정리됨", "확인할 통화" (late, no recording) or "통화 정리 중". */
    internal fun publicTitle(state:String)=when(state) { DONE -> "통화 정리됨";LATE,NO_RECORDING -> "확인할 통화";else -> "통화 정리 중" }

    /** The call log's length (ms) of the call [e] is about: a log line on the same number (by hash) that began within
     * [MATCH_MS] of the call; null when the log has none (yet) or may not be read. */
    private fun callLogDuration(context:Context,e:Entry):Long? {
        if(context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED) return UNREADABLE
        return runCatching {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.DATE,CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE}>=? AND ${CallLog.Calls.DATE}<=?",arrayOf((e.startedMs-MATCH_MS).toString(),(e.endedMs+MATCH_MS).toString()),
                "${CallLog.Calls.DATE} DESC")?.use { c ->
                var found:Long?=null
                while(c.moveToNext()) {
                    val k=PhoneKey.of(c.getString(0)) ?: continue
                    if(PrivacyRules.hash(k)==e.hash) { found=c.getLong(2)*1_000L;break }
                }
                found
            }
        }.getOrNull()
    }

    // ---- state ----------------------------------------------------------------------------------------------------
    private fun settings(context:Context):JSONObject=runCatching { SecureSettings(CallPipelineStartup.storage(context)).snapshot() }.getOrDefault(JSONObject())
    fun enabled(s:JSONObject)=s.optBoolean(PipelineSettingsKeys.FOLLOWUP_NOTICE,true)
    internal fun entries(context:Context):List<Entry> =parse(settings(context).optString(STATE_KEY,""))
    private fun parse(text:String):List<Entry> =if(text.isBlank()) emptyList() else runCatching {
        JSONArray(text).let { a -> (0 until a.length()).mapNotNull { runCatching { Entry.parse(a.getJSONObject(it)) }.getOrNull() } } }.getOrDefault(emptyList())
    private fun save(context:Context,edit:(List<Entry>)->List<Entry>) {
        runCatching { SecureSettings(CallPipelineStartup.storage(context)).update { s ->
            val next=edit(parse(s.optString(STATE_KEY,""))).sortedBy { it.endedMs }.takeLast(MAX_ENTRIES)
            s.put(STATE_KEY,JSONArray().apply { next.forEach { put(it.json()) } }.toString())
        } }.onFailure { Log.w(LOG,"state not saved: ${it.javaClass.simpleName}") }
    }

    /** The call log's cached (phone-book) name of the latest call with [phoneKey]; the call store's copy of the log when the
     * phone's own has none yet (the entry is written a moment after the call). */
    private fun callLogName(context:Context,phoneKey:String):String? {
        if(context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED) runCatching {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.CACHED_NAME),null,null,"${CallLog.Calls.DATE} DESC")?.use { c ->
                var n=0
                while(c.moveToNext() && n++<300) if(PhoneKey.of(c.getString(0))==phoneKey) c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return runCatching { store(context).cardRows("SELECT name FROM call_log WHERE number_key=? AND name IS NOT NULL AND name!='' ORDER BY start_ms DESC LIMIT 1",phoneKey)
            .firstOrNull()?.get("name") as? String }.getOrNull()
    }
}
