package kr.callbrief.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.callbrief.SecureSettings
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Text messages and mail, taken in like calls: each becomes a record the hourly analysis summarises into the same
 * ledger and check items. All of them are read (user decision 10/30): the analysis decides work or personal, and a
 * personal one is deleted, never stored or summarised. Machine messages (ads, codes, card and bank alerts, mass mail)
 * are dropped on the phone first and never sent. Nothing is read before the user's 정리 동의. */
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

    // ---- mail -------------------------------------------------------------------------------------------------------
    /** Mail services the settings screen offers; "직접 입력" takes any IMAP host. */
    val MAIL_HOSTS=linkedMapOf("네이버" to "imap.naver.com","다음" to "imap.daum.net","Gmail" to "imap.gmail.com")

    private fun connect(host:String,user:String,password:String):javax.mail.Store {
        val props=Properties().apply {
            put("mail.store.protocol","imaps");put("mail.imaps.ssl.enable","true");put("mail.imaps.ssl.checkserveridentity","true")
            put("mail.imaps.connectiontimeout","20000");put("mail.imaps.timeout","20000")
        }
        return javax.mail.Session.getInstance(props).getStore("imaps").apply { connect(host,993,user,password) }
    }
    /** Settings screen's [연결 확인]: null when the mailbox opened, else the reason in plain words. */
    fun checkMail(host:String,user:String,password:String):String? = try { connect(host,user,password).close();null }
        catch(_:javax.mail.AuthenticationFailedException) { "메일 주소나 앱 비밀번호가 맞지 않아요. 메일 서비스에서 IMAP 사용과 앱 비밀번호를 확인해 주세요." }
        catch(_:Exception) { "메일 서버에 연결하지 못했어요. 인터넷 연결과 서버 주소를 확인해 주세요." }

    /** Mass mail is skipped: newsletters carry an unsubscribe header or a bulk precedence, and no-reply senders. */
    internal fun isBulk(listUnsubscribe:Boolean,precedence:String?,from:String?)=
        listUnsubscribe || precedence?.lowercase()?.let { it=="bulk" || it=="list" || it=="junk" }==true || from?.lowercase()?.contains(Regex("no-?reply"))==true

    /** The new part of a reply: everything before the quoted earlier mail. */
    internal fun newPart(body:String):String {
        val lines=body.replace("\r","").lines()
        val cut=lines.indexOfFirst { l -> val t=l.trim()
            t.startsWith(">") || t.contains(Regex("^-{2,}\\s*(Original Message|원본 메시지|Forwarded message|전달된 메시지)")) ||
                (t.startsWith("On ") && t.endsWith("wrote:")) || t.matches(Regex("^(보낸 사람|From)\\s*:.*")) }
        return (if(cut>0) lines.take(cut) else lines).joinToString("\n").replace(Regex("\n{3,}"),"\n\n").trim()
    }
    /** "첨부: 견적서.pdf, 도면.dwg" under the body (design C4 rule 17: a sent file is a sign the "보내기" item is done); empty without files. */
    internal fun attachedLine(names:List<String>)=names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10)
        .takeIf { it.isNotEmpty() }?.joinToString(", ",prefix="\n첨부: ") { it.take(80) } ?: ""
    private fun attachments(part:javax.mail.Part):List<String> = when {
        part.isMimeType("multipart/*") -> (part.content as javax.mail.Multipart).let { mp -> (0 until mp.count).flatMap { attachments(mp.getBodyPart(it)) } }
        else -> listOfNotNull(part.fileName?.let { runCatching { javax.mail.internet.MimeUtility.decodeText(it) }.getOrDefault(it) }
            ?.takeIf { javax.mail.Part.ATTACHMENT.equals(part.disposition,true) || !part.isMimeType("text/*") })
    }
    private fun text(part:javax.mail.Part):String? = when {
        part.isMimeType("text/plain") -> part.content as? String
        part.isMimeType("multipart/*") -> (part.content as javax.mail.Multipart).let { mp ->
            val parts=(0 until mp.count).map { mp.getBodyPart(it) }
            parts.firstNotNullOfOrNull { p -> if(p.isMimeType("text/plain")) text(p) else null } ?: parts.firstNotNullOfOrNull { text(it) } }
        part.isMimeType("text/html") -> (part.content as? String)?.let { android.text.Html.fromHtml(it,android.text.Html.FROM_HTML_MODE_COMPACT).toString() }
        else -> null
    }

    fun takeMail(store:CallRecordStore,settings:SecureSettings):Int {
        val s=settings.snapshot()
        val host=s.optString(PipelineSettingsKeys.MAIL_HOST);val user=s.optString(PipelineSettingsKeys.MAIL_USER);val pass=s.optString(PipelineSettingsKeys.MAIL_PASSWORD)
        if(host.isBlank() || user.isBlank() || pass.isBlank())return 0
        val since=s.optLong(PipelineSettingsKeys.LAST_MAIL_MS,0).takeIf { it>0 } ?: (System.currentTimeMillis()-FIRST_LOOK_MS)
        if(s.optLong(PipelineSettingsKeys.MAIL_BACKFILL_UNTIL,0)==0L) {
            val until=listOfNotNull(store.earliestMessage(MessageKind.MAIL),since).minOrNull()!!
            settings.update { it.put(PipelineSettingsKeys.MAIL_BACKFILL_UNTIL,until).put(PipelineSettingsKeys.MAIL_BACKFILL_CURSOR,FirstPass.FROM_MS) }
        }
        var added=0;var newest=since
        try {
            connect(host,user,pass).use { box ->
                val inbox=box.getFolder("INBOX");inbox.open(javax.mail.Folder.READ_ONLY)
                try {
                    val uidFolder=inbox as? javax.mail.UIDFolder
                    fun between(from:Long,end:Long)=inbox.search(javax.mail.search.ReceivedDateTerm(javax.mail.search.ComparisonTerm.GE,java.util.Date(from-86_400_000L)))
                        .filter { val t=it.receivedDate?.time ?: 0L;t>=from && t<end }.sortedBy { it.receivedDate.time }.take(30)
                    val recent=between(since+1,Long.MAX_VALUE)
                    // First pass: one batch of mail from 10/1 up to where the regular intake began.
                    val cursor=settings.snapshot().optLong(PipelineSettingsKeys.MAIL_BACKFILL_CURSOR,0);val end=settings.snapshot().optLong(PipelineSettingsKeys.MAIL_BACKFILL_UNTIL,0)
                    val old=if(cursor>0 && cursor<end) between(cursor,end) else emptyList()
                    for(m in recent+old) {
                        val at=m.receivedDate.time;if(m in recent) newest=maxOf(newest,at)
                        val from=(m.from?.firstOrNull() as? javax.mail.internet.InternetAddress)
                        if(isBulk(m.getHeader("List-Unsubscribe")!=null,m.getHeader("Precedence")?.firstOrNull(),from?.address))continue
                        val body=newPart(runCatching { text(m) }.getOrNull().orEmpty()).take(3000)
                        // The address identifies the sender (design: 장부의 원본); the name is in the text.
                        val who=from?.address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: from?.personal
                        val files=runCatching { attachments(m) }.getOrDefault(emptyList())
                        val content="제목: ${m.subject.orEmpty()}\n보낸 사람: ${listOfNotNull(from?.personal,from?.address).joinToString(" ")}\n\n$body"+attachedLine(files)
                        val id=MessageKind.MAIL+(uidFolder?.let { "${it.uidValidity}:${it.getUID(m)}" } ?: "${m.messageNumber}:$at")
                        if(store.insertMessage(id,at,who,content)) added++
                    }
                    if(cursor>0 && cursor<end) settings.update { it.put(PipelineSettingsKeys.MAIL_BACKFILL_CURSOR,if(old.size>=30) old.last().receivedDate.time+1 else end) }
                } finally { runCatching { inbox.close(false) } }
            }
            settings.update { it.put(PipelineSettingsKeys.LAST_MAIL_MS,newest).remove(PipelineSettingsKeys.MAIL_ERROR) }
        } catch(_:javax.mail.AuthenticationFailedException) {
            settings.update { it.put(PipelineSettingsKeys.MAIL_ERROR,"메일 앱 비밀번호가 맞지 않아요. 설정에서 다시 넣어 주세요.") }
        } catch(_:Exception) { /* network: the next run tries again */ }
        return added
    }
    private inline fun <T> javax.mail.Store.use(block:(javax.mail.Store)->T):T = try { block(this) } finally { runCatching { close() } }
}

/** Every 30 minutes (and right after the user connects mail): take in new texts and mail, then queue the analysis. */
internal class MessageWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        if(kr.callbrief.BuildConfig.LOCAL_PREVIEW)return@withContext Result.success()
        val storage=CallPipelineStartup.storage(applicationContext);val settings=SecureSettings(storage)
        if(!settings.snapshot().optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false))return@withContext Result.success()
        val added=kr.callbrief.IeumApplication.store(storage).let { store ->
            runCatching { MessageIntake.takeSms(applicationContext,store,settings) }.getOrDefault(0)+runCatching { MessageIntake.takeMail(store,settings) }.getOrDefault(0) }
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
