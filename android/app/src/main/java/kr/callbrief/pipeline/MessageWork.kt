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

/** Work text messages and mail, taken in like calls: each becomes a record the hourly analysis summarises into the
 * same ledger and check items. Nothing is read before the user's 정리 동의; only text leaves the phone, and only
 * through the same analysis path as call transcripts. */
internal object MessageIntake {
    private const val FIRST_LOOK_MS=2*86_400_000L
    private fun hhmm(ms:Long)=CallUi.time(ms)

    /** Business numbers only: a text from a number the ledger has never seen is not read. */
    internal fun phoneKey(address:String?):String? {
        val d=address.orEmpty().filter { it.isDigit() }.let { if(it.startsWith("82") && it.length>=11) "0"+it.drop(2) else it }
        return d.takeIf { it.length>=8 }
    }

    /** One record per number with the texts that arrived since the last look, oldest first. */
    internal data class Sms(val id:Long,val atMs:Long,val sent:Boolean,val phone:String,val body:String)
    internal fun threads(messages:List<Sms>,known:Set<String>):List<Pair<String,List<Sms>>> =
        messages.filter { it.phone in known && it.body.isNotBlank() }.groupBy { it.phone }.map { (p,l) -> p to l.sortedBy { it.atMs } }
    internal fun threadText(list:List<Sms>)=list.joinToString("\n") { "[${hhmm(it.atMs)} ${if(it.sent) "보낸 문자" else "받은 문자"}] ${it.body.trim().take(2000)}" }

    fun takeSms(context:Context,store:CallRecordStore,settings:SecureSettings):Int {
        if(context.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return 0
        val since=settings.snapshot().optLong(PipelineSettingsKeys.LAST_SMS_MS,0).takeIf { it>0 } ?: (System.currentTimeMillis()-FIRST_LOOK_MS)
        val found=mutableListOf<Sms>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id","address","date","type","body"),
            "date>? AND type IN (1,2)",arrayOf(since.toString()),"date ASC")?.use { c ->
            while(c.moveToNext() && found.size<500) {
                val phone=phoneKey(c.getString(1)) ?: continue
                found+=Sms(c.getLong(0),c.getLong(2),c.getInt(3)==2,phone,c.getString(4).orEmpty())
            }
        }
        if(found.isEmpty())return 0
        var added=0
        for((phone,list) in threads(found,store.knownPhones()))
            if(store.insertMessage("${MessageKind.SMS}$phone:${list.last().id}",list.first().atMs,phone,threadText(list))) added++
        settings.update { it.put(PipelineSettingsKeys.LAST_SMS_MS,found.maxOf { m -> m.atMs }) }
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
        var added=0;var newest=since
        try {
            connect(host,user,pass).use { box ->
                val inbox=box.getFolder("INBOX");inbox.open(javax.mail.Folder.READ_ONLY)
                try {
                    val uidFolder=inbox as? javax.mail.UIDFolder
                    val recent=inbox.search(javax.mail.search.ReceivedDateTerm(javax.mail.search.ComparisonTerm.GE,java.util.Date(since-86_400_000L)))
                        .filter { (it.receivedDate?.time ?: 0)>since }.sortedBy { it.receivedDate.time }.take(30)
                    for(m in recent) {
                        val at=m.receivedDate.time;newest=maxOf(newest,at)
                        val from=(m.from?.firstOrNull() as? javax.mail.internet.InternetAddress)
                        if(isBulk(m.getHeader("List-Unsubscribe")!=null,m.getHeader("Precedence")?.firstOrNull(),from?.address))continue
                        val body=newPart(runCatching { text(m) }.getOrNull().orEmpty()).take(3000)
                        val who=from?.personal?.takeIf { it.isNotBlank() } ?: from?.address
                        val content="제목: ${m.subject.orEmpty()}\n보낸 사람: ${listOfNotNull(from?.personal,from?.address).joinToString(" ")}\n\n$body"
                        val id=MessageKind.MAIL+(uidFolder?.let { "${it.uidValidity}:${it.getUID(m)}" } ?: "${m.messageNumber}:$at")
                        if(store.insertMessage(id,at,who,content)) added++
                    }
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
        val added=CallRecordStore(storage).use { store ->
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
