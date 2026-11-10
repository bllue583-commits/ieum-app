package kr.callbrief.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import java.util.concurrent.TimeUnit

/** Text messages (SMS and MMS), taken in like calls: each becomes a record the hourly analysis summarises into the same
 * ledger and check items. All of them are read (user decision 10/30): the analysis decides work or personal, and a
 * personal one is deleted, never stored or summarised. Machine messages (ads, codes, card and bank alerts) are dropped
 * on the phone first and never sent. Nothing is read before the user's 정리 동의. Mail was removed in v3.7. */
internal object MessageIntake {
    private const val FIRST_LOOK_MS=2*86_400_000L
    private fun hhmm(ms:Long)=CallUi.time(ms)

    /** A person's number as the one phone key ([kr.callbrief.core.PhoneKey]); service numbers (1588 and the like), short
     * codes and foreign senders give null and are not read. */
    internal fun phoneKey(address:String?):String? = kr.callbrief.core.PhoneKey.of(address)?.takeIf { it.startsWith("0") }
    /** Sent by a machine, not a person: advertising, verification codes, card and bank notices. */
    private val MACHINE=Regex("\\(광고\\)|무료\\s*수신\\s*거부|인증\\s*번호|인증코드|승인\\s*[0-9,]+\\s*원|[0-9,]+원\\s*승인|입금\\s*[0-9,]+원|출금\\s*[0-9,]+원|배송\\s*(완료|출발|예정)")
    internal fun automated(body:String)=MACHINE.containsMatchIn(body)

    /** One record per number with the texts that arrived since the last look, oldest first; [byDay] splits the
     * first pass's batches (weeks of texts) into one record per number and day. */
    internal data class Sms(val id:Long,val atMs:Long,val sent:Boolean,val phone:String,val body:String)
    internal fun threads(messages:List<Sms>,byDay:Boolean=false):List<Pair<String,List<Sms>>> =
        messages.filter { it.body.isNotBlank() && !automated(it.body) }
            .groupBy { if(byDay) it.phone+"@"+CallRecordStore.kstDay(it.atMs) else it.phone }.map { (k,l) -> k.substringBefore('@') to l.sortedBy { it.atMs } }
    internal fun threadText(list:List<Sms>)=list.joinToString("\n") { "[${hhmm(it.atMs)} ${if(it.sent) "보낸 문자" else "받은 문자"}] ${it.body.trim().take(2000)}" }

    private const val LIMIT=500
    /** Texts with after < time < before: SMS and, as negative ids, MMS (long texts over 80 bytes and picture texts
     * arrive as MMS in Korea). Group MMS sent to several people are left out. [Batch.upTo] is set when a source was
     * cut at [LIMIT]: only messages up to it are returned, the rest come next time. */
    internal data class Batch(val messages:List<Sms>,val upTo:Long?)
    private fun read(context:Context,after:Long,before:Long,excluded:(String)->Boolean):Batch {
        val sms=mutableListOf<Sms>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id","address","date","type","body"),
            "date>? AND date<? AND type IN (1,2)",arrayOf(after.toString(),before.toString()),"date ASC")?.use { c ->
            while(c.moveToNext() && sms.size<LIMIT) {
                val address=c.getString(1).orEmpty();val phone=phoneKey(address) ?: continue
                if(excluded(address)) continue
                sms+=Sms(c.getLong(0),c.getLong(2),c.getInt(3)==2,phone,c.getString(4).orEmpty())
            }
        }
        val mms=runCatching { readMms(context,after,before,excluded) }.getOrDefault(emptyList()) // a picture-text reader that fails must not stop the plain texts
        val upTo=listOfNotNull(sms.takeIf { it.size>=LIMIT }?.last()?.atMs,mms.takeIf { it.size>=LIMIT }?.last()?.atMs).minOrNull()
        return Batch((sms+mms).filter { upTo==null || it.atMs<=upTo }.sortedBy { it.atMs },upTo)
    }
    private fun readMms(context:Context,after:Long,before:Long,excluded:(String)->Boolean):List<Sms> {
        val out=mutableListOf<Sms>();val resolver=context.contentResolver
        resolver.query(android.net.Uri.parse("content://mms"),arrayOf("_id","date","msg_box"),"date>? AND date<? AND msg_box IN (1,2)",
            arrayOf((after/1000).toString(),((before+999)/1000).toString()),"date ASC")?.use { c ->
            while(c.moveToNext() && out.size<LIMIT) {
                val id=c.getLong(0);val at=c.getLong(1)*1000;val sent=c.getInt(2)==2
                if(at<=after || at>=before) continue
                // 137 = from, 151 = to
                val addresses=resolver.query(android.net.Uri.parse("content://mms/$id/addr"),arrayOf("address","type"),"type=?",arrayOf(if(sent) "151" else "137"),null)
                    ?.use { a -> buildList { while(a.moveToNext()) a.getString(0)?.let { add(it) } } }.orEmpty()
                val address=addresses.singleOrNull() ?: continue
                val phone=phoneKey(address) ?: continue
                if(excluded(address)) continue
                val body=resolver.query(android.net.Uri.parse("content://mms/part"),arrayOf("ct","text"),"mid=?",arrayOf(id.toString()),null)
                    ?.use { p -> buildList { while(p.moveToNext()) if(p.getString(0)=="text/plain") p.getString(1)?.let { add(it) } } }.orEmpty().joinToString("\n")
                if(body.isNotBlank()) out+=Sms(-id,at,sent,phone,body)
            }
        }
        return out
    }

    /** New texts since the last look, then one batch of the first pass (10/1 up to where the regular intake began). */
    fun takeSms(context:Context,store:CallRecordStore,settings:SecureSettings):Int {
        if(context.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return 0
        val s=settings.snapshot();val now=System.currentTimeMillis()
        val rules=kr.callbrief.LocalPrivacy.rules(s);val excluded={ a:String -> rules.excluded(a,a) }
        val last=s.optLong(PipelineSettingsKeys.LAST_SMS_MS,0)
        if(s.optLong(PipelineSettingsKeys.SMS_BACKFILL_UNTIL,0)==0L) {
            val until=listOfNotNull(store.earliestMessage(MessageKind.SMS),last.takeIf { it>0 }).minOrNull() ?: (now-FIRST_LOOK_MS)
            settings.update { it.put(PipelineSettingsKeys.SMS_BACKFILL_UNTIL,until).put(PipelineSettingsKeys.SMS_BACKFILL_CURSOR,FirstPass.FROM_MS-1) }
        }
        var added=0
        val fresh=read(context,last.takeIf { it>0 } ?: (now-FIRST_LOOK_MS),Long.MAX_VALUE,excluded)
        if(fresh.messages.isNotEmpty()) {
            for((phone,list) in threads(fresh.messages))
                if(store.insertMessage("${MessageKind.SMS}$phone:${list.last().id}",list.first().atMs,phone,threadText(list))) added++
            settings.update { it.put(PipelineSettingsKeys.LAST_SMS_MS,fresh.upTo ?: fresh.messages.maxOf { m -> m.atMs }) }
        }
        val s2=settings.snapshot()
        val cursor=s2.optLong(PipelineSettingsKeys.SMS_BACKFILL_CURSOR,0);val end=s2.optLong(PipelineSettingsKeys.SMS_BACKFILL_UNTIL,0)
        if(cursor>0 && cursor<end) {
            val old=read(context,cursor,end,excluded)
            for((phone,list) in threads(old.messages,byDay=true))
                if(store.insertMessage("${MessageKind.SMS}$phone:${list.last().id}",list.first().atMs,phone,threadText(list))) added++
            settings.update { it.put(PipelineSettingsKeys.SMS_BACKFILL_CURSOR,old.upTo ?: end) }
        }
        return added
    }

    /** "[메일 주소]": the mask [ClaudeCallAnalyzer.maskNumbers] puts over a mail address in a transcript or a text. Mail
     * intake itself was removed in v3.7 (DESIGN_V7 3.3, user decision 11/7); addresses can still arrive inside texts. */
    internal const val ADDRESS_MASK="[메일 주소]"

    /** Settings keys the mail intake (v2.x to v3.6) kept: the mailbox server, address and app password, its progress and its
     * errors. v3.7 removes them once at app start ([forgetMail]); nothing writes them any more. */
    internal val REMOVED_MAIL_KEYS=listOf("mailImapHost","mailUser","mailAppPassword","messagesLastMailMs","mailLastError",
        "firstPassMailCursor","firstPassMailUntil","mailErrorCodes")
    /** Takes [REMOVED_MAIL_KEYS] out of [s]; true when one was there. */
    internal fun clearMail(s:org.json.JSONObject):Boolean = REMOVED_MAIL_KEYS.filter { s.has(it) }.onEach { s.remove(it) }.isNotEmpty()
    /** The one-time cleanup after the upgrade to v3.7 (DESIGN_V7 3.3): a saved mail address and app password are deleted from
     * the sealed settings. A store without them is not written again, so this costs one read on later starts. */
    fun forgetMail(settings:SecureSettings) {
        if(!REMOVED_MAIL_KEYS.any { settings.snapshot().has(it) }) return
        settings.update { clearMail(it) }
    }
}

/** Every 30 minutes (and right after consent): take in new texts, then queue the analysis. */
internal class MessageWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        if(kr.callbrief.BuildConfig.LOCAL_PREVIEW)return@withContext Result.success()
        val storage=CallPipelineStartup.storage(applicationContext);val settings=SecureSettings(storage)
        if(!settings.snapshot().optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false))return@withContext Result.success()
        val added=kr.callbrief.IeumApplication.store(storage).let { store -> runCatching { MessageIntake.takeSms(applicationContext,store,settings) }.getOrDefault(0) }
        if(added>0) CallPipelineStartup.enqueueAnalysis(applicationContext)
        Result.success()
    }
    companion object {
        private const val PERIODIC="ieum.messages"
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MessageWork>(30,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun now(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("$PERIODIC.now",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MessageWork>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
