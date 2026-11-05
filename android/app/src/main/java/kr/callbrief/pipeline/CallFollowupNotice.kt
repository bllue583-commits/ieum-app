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
    private const val LOG="ieum.followup"

    /** The notice id of a call record: `("followup"+recordId).hashCode()`. A notice posted before its record existed keeps the id
     * it was posted with (phone key + end time); the record is then mapped onto it in [STATE_KEY]. */
    fun idFor(recordId:String)=("followup"+recordId).hashCode()
    internal fun tempId(phoneKey:String,endedMs:Long)=("followup"+phoneKey+endedMs).hashCode()

    /** The phone-book name of [phoneKey] (the call log's cached name), or null. Replaceable in tests. */
    internal var phoneBookName:(Context,String)->String? = ::callLogName
    /** The call store. Replaceable in tests. */
    internal var store:(Context)->CallRecordStore ={ kr.callbrief.IeumApplication.store(it) }
    internal fun resetForTest() { phoneBookName=::callLogName;store={ kr.callbrief.IeumApplication.store(it) } }

    /** What one notice is about. [who]: "김민수 대표"; [hash]: SHA-256 of the phone key; [record]: the call record once known. */
    internal data class Entry(val id:Int,val who:String,val company:String?,val hash:String,val startedMs:Long,val endedMs:Long,
                              val record:String?=null,val state:String=WORKING,
                              /** Check items written with [메모 남기기] on this notice (bound to [record] once it is known). */ val items:List<String> =emptyList()) {
        val memos:Int get()=items.size
        fun json():JSONObject=JSONObject().put("id",id).put("who",who).put("co",company ?: JSONObject.NULL).put("h",hash).put("s",startedMs).put("e",endedMs)
            .put("r",record ?: JSONObject.NULL).put("st",state).put("i",JSONArray(items))
        companion object {
            fun parse(o:JSONObject)=Entry(o.getInt("id"),o.getString("who"),o.optString("co").takeIf { !o.isNull("co") && it.isNotEmpty() },o.optString("h"),
                o.optLong("s"),o.optLong("e"),o.optString("r").takeIf { !o.isNull("r") && it.isNotEmpty() },o.optString("st",WORKING),
                o.optJSONArray("i")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty())
        }
    }

    // ---- 1. the call ended ------------------------------------------------------------------------------------------
    /** Called by r10-1's [CallSession] when a call is over (on a background thread: it reads the store and the call log).
     * Posts "정리하고 있어요" for an answered call with a confirmed work counterpart; does nothing otherwise. */
    fun onCallEnded(context:Context,end:CallSession.CallEnd) {
        try {
            val s=settings(context)
            if(!enabled(s) || !end.answered) return
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
            val e=Entry(tempId(key,end.endedMs),named,card?.company?.name,PrivacyRules.hash(key),end.startedMs,end.endedMs)
            save(context) { list -> list.filter { it.id!=e.id }+e }
            post(context,e,null)
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
            LATE -> Words("${withParticle(e.who)} 통화","정리가 늦어지고 있어요, 저녁 보고에 넣어 드릴게요","정리가 늦어지고 있어요, 저녁 보고에 넣어 드릴게요$memo")
            else -> Words("${withParticle(e.who)} 통화를 정리하고 있어요","끝나면 여기에 요약이 와요","끝나면 여기에 요약이 와요$memo")
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
            // Lock screen: no name, no summary.
            .setPublicVersion(Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum).setContentTitle("통화 정리 중").build())
            .addAction(action).build()
        manager.notify(TAG,e.id,n)
        return true
    }
    private fun cancel(context:Context,id:Int) { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(TAG,id) } }

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
