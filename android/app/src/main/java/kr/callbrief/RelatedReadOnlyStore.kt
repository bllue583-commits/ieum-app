package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callbrief.core.PrivacyRules
import kr.callbrief.navermail.*
import kr.callbrief.navermail.android.NaverCredentialVault
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject

/** Protected per-recording read scopes and candidate versions. No canonical task writer. */
internal class RelatedReadOnlyStore(private val context:Context,private val policy:()->JSONObject={SecureSettings(context).snapshot()}):AutoCloseable {
    data class Scope(val recording:String,val revision:Long,val sourceVersion:Long,val audioSha:String,val linkRevision:Long,
        val person:String,val case:String,val phone:String,val fromMs:Long,val untilMs:Long,val enabled:Boolean)
    data class MailBinding(val recording:String,val revision:Long,val savedScopeSha:String,val selected:MailScope,val bodies:Boolean)
    private val db:AndroidSqlPort
    init {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "LOCAL_IO_THREAD_REQUIRED" }
        RecordingWorkflowStore(context).close()
        db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
        db.requireProtectedPersistence();db.transaction { schema.forEach { db.execute(it) };null }
    }
    private fun rows(sql:String,vararg args:Any?)=db.query(sql,*args)
    private fun number(r:Map<String,Any?>,k:String)=(r[k] as? Number)?.toLong() ?: 0
    private fun json(r:Map<String,Any?>)=JSONObject().apply{r.forEach{(k,v)->put(k,v ?: JSONObject.NULL)}}
    private fun identity(id:String)=rows("SELECT r.source_version,v.audio_sha256,l.* FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN workflow_links l ON l.recording_id=r.recording_id JOIN local_visibility a ON a.recording_id=r.recording_id WHERE r.recording_id=? AND a.archived=0",id).singleOrNull() ?: error("RELATED_IDENTITY_CONFIRMATION_REQUIRED")
    private fun confirmed(id:String):Map<String,Any?> {
        val r=identity(id)
        check(!r["person_id"].toString().let{it=="null"||it.isBlank()}&&!r["case_id"].toString().let{it=="null"||it.isBlank()}) { "RELATED_IDENTITY_CONFIRMATION_REQUIRED" }
        val phone=PrivacyRules.validatedPhone(r["phone_normalized"].toString()) ?: error("RELATED_PHONE_CONFIRMATION_REQUIRED")
        val label=rows("SELECT label FROM workflow_entities WHERE id=?",r["person_id"]).single()["label"].toString()
        check(!LocalPrivacy.rules(policy()).excluded(label,phone)) { "EXCLUDED_BEFORE_READ" }
        return r
    }
    private fun expectedIdentity(id:String,expected:JSONObject):Map<String,Any?> {
        val r=confirmed(id)
        check(number(r,"revision")==expected.getLong("link_revision")&&number(r,"source_version")==expected.getLong("source_version")&&r["audio_sha256"]==expected.getString("audio_sha256")) { "RELATED_SCOPE_CHANGED" }
        return r
    }
    fun configureSms(id:String,expected:JSONObject,fromMs:Long,untilMs:Long,enabled:Boolean):Scope=db.transaction {
        val r=expectedIdentity(id,expected);require(fromMs>=0&&untilMs>fromMs&&untilMs-fromMs<=31L*24*60*60*1000) { "MAX_31_DAYS" }
        val old=rows("SELECT revision FROM related_sms_scopes WHERE recording_id=?",id).singleOrNull();val rev=(old?.get("revision") as? Number)?.toLong()?.plus(1) ?: 1
        val s=Scope(id,rev,number(r,"source_version"),r["audio_sha256"].toString(),number(r,"revision"),r["person_id"].toString(),r["case_id"].toString(),r["phone_normalized"].toString(),fromMs,untilMs,enabled)
        db.execute("INSERT OR REPLACE INTO related_sms_scopes VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,rev,s.sourceVersion,s.audioSha,s.linkRevision,s.person,s.case,s.phone,fromMs,untilMs,if(enabled)1 else 0);s
    }
    fun smsScope(id:String):Scope?=db.transaction {
        val r=rows("SELECT * FROM related_sms_scopes WHERE recording_id=?",id).singleOrNull() ?: return@transaction null
        Scope(id,number(r,"revision"),number(r,"source_version"),r["audio_sha"].toString(),number(r,"link_revision"),r["person_id"].toString(),r["case_id"].toString(),r["phone"].toString(),number(r,"from_ms"),number(r,"until_ms"),number(r,"enabled")==1L)
    }
    fun current(s:Scope):Boolean=runCatching {
        val r=confirmed(s.recording)
        s.enabled&&smsScope(s.recording)==s&&number(r,"source_version")==s.sourceVersion&&r["audio_sha256"]==s.audioSha&&number(r,"revision")==s.linkRevision&&r["person_id"]==s.person&&r["case_id"]==s.case&&r["phone_normalized"]==s.phone
    }.getOrDefault(false)
    fun storeSms(s:Scope,batch:AndroidSmsReadOnlySource.Batch,authorized:()->Boolean):Int=db.transaction {
        check(authorized()&&current(s)) { "RELATED_SCOPE_CHANGED" };var added=0
        for(m in batch.messages) {
            require(m.phone==s.phone&&m.dateMs in s.fromMs until s.untilMs&&m.providerId>0&&m.type in 1..2&&m.text.length in 1..20000)
            val sha=PrivacyRules.hash(m.text)
            val old=rows("SELECT h.*,c.scope_revision,c.phone FROM related_sms_heads h JOIN related_sms_candidates c ON c.digest=h.digest WHERE h.recording_id=? AND h.message_id=?",s.recording,m.providerId).singleOrNull()
            val same=old!=null&&old["content_sha"]==sha&&number(old,"date_ms")==m.dateMs&&number(old,"type")==m.type.toLong()&&old["phone"]==m.phone
            if(same&&number(old!!,"scope_revision")==s.revision)continue
            val version=if(same)number(old!!,"version") else (old?.get("version") as? Number)?.toLong()?.plus(1) ?: 1
            val digest=PrivacyRules.hash(listOf(s.recording,s.revision,m.providerId,version,m.dateMs,m.type,sha).joinToString("\u0000"))
            db.execute("INSERT INTO related_sms_candidates VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",digest,s.recording,s.revision,m.providerId,version,m.phone,m.dateMs,m.type,m.text,sha,if(batch.incomplete)1 else 0,System.currentTimeMillis())
            db.execute("INSERT OR REPLACE INTO related_sms_heads VALUES(?,?,?,?,?,?,?)",s.recording,m.providerId,version,digest,sha,m.dateMs,m.type);added++
        }
        db.execute("INSERT INTO related_sms_scans VALUES(?,?,?,?,?,?,?,?)",java.util.UUID.randomUUID().toString(),s.recording,s.revision,batch.messages.size,added,if(batch.incomplete)1 else 0,batch.skipped,System.currentTimeMillis())
        check(authorized()&&current(s)) { "RELATED_SCOPE_CHANGED" };added
    }
    fun smsCandidates(id:String):JSONArray=db.transaction {
        JSONArray().apply { rows("SELECT c.* FROM related_sms_candidates c JOIN related_sms_heads h ON h.recording_id=c.recording_id AND h.message_id=c.message_id AND h.digest=c.digest WHERE c.recording_id=? ORDER BY c.date_ms DESC,c.message_id DESC LIMIT 50",id).forEach{put(json(it))} }
    }
    fun lastSmsScan(id:String):JSONObject?=db.transaction { rows("SELECT * FROM related_sms_scans WHERE recording_id=? ORDER BY created_ms DESC,rowid DESC LIMIT 1",id).singleOrNull()?.let{json(it)} }
    fun confirmSms(id:String,digest:String,expected:JSONObject):String=db.transaction {
        val s=smsScope(id) ?: error("RELATED_SCOPE_CHANGED");check(current(s)) { "RELATED_SCOPE_CHANGED" }
        val c=rows("SELECT c.* FROM related_sms_candidates c JOIN related_sms_heads h ON h.recording_id=c.recording_id AND h.message_id=c.message_id AND h.digest=c.digest WHERE c.recording_id=? AND c.digest=?",id,digest).single()
        require(number(c,"scope_revision")==s.revision) { "RELATED_SCOPE_CHANGED" }
        RecordingWorkflowStore(context).use { it.addRelated(id,expected,"SMS",c["raw_text"].toString(),c["phone"].toString(),java.time.Instant.ofEpochMilli(number(c,"date_ms")).toString(),"동일 번호 문자 · 관계 직접 확인",
            "android-sms-v1:${c["message_id"]}:${c["date_ms"]}:${c["version"]}:${c["content_sha"]}:${PrivacyRules.hash(s.phone)}") }
    }
    fun configureMail(id:String,expected:JSONObject,saved:MailScope,bodies:Boolean):MailBinding=db.transaction {
        val r=expectedIdentity(id,expected)
        val owner=(context as? AccountStorageContext)?.ownerId.orEmpty()
        require(saved.account.ownerId==ScopedNaverMailSettingsActivity.ownerNamespace(owner)) { "MAIL_OWNER_CHANGED" }
        val scope=saved.copy(caseId=r["case_id"].toString(),counterpartId=r["person_id"].toString())
        val old=rows("SELECT revision FROM related_mail_scopes WHERE recording_id=?",id).singleOrNull();val rev=(old?.get("revision") as? Number)?.toLong()?.plus(1) ?: 1
        db.execute("INSERT OR REPLACE INTO related_mail_scopes VALUES(?,?,?,?,?,?,?,?,?)",id,rev,saved.fingerprint,NaverCredentialVault.scopeJson(scope).toString(),saved.account.address,if(bodies)1 else 0,number(r,"revision"),number(r,"source_version"),r["audio_sha256"])
        MailBinding(id,rev,saved.fingerprint,scope,bodies)
    }
    fun mailBinding(id:String):MailBinding?=db.transaction {
        val r=rows("SELECT * FROM related_mail_scopes WHERE recording_id=?",id).singleOrNull() ?: return@transaction null
        val owner=ScopedNaverMailSettingsActivity.ownerNamespace((context as? AccountStorageContext)?.ownerId.orEmpty())
        val account=AccountBinding(owner,r["address"].toString())
        MailBinding(id,number(r,"revision"),r["saved_scope_sha"].toString(),NaverCredentialVault.parseScope(account,JSONObject(r["scope_json"].toString())),number(r,"bodies")==1L)
    }
    fun currentMail(b:MailBinding):Boolean=runCatching {
        val r=confirmed(b.recording);val stored=rows("SELECT * FROM related_mail_scopes WHERE recording_id=?",b.recording).single()
        mailBinding(b.recording)==b&&number(r,"revision")==number(stored,"link_revision")&&number(r,"source_version")==number(stored,"source_version")&&r["audio_sha256"]==stored["audio_sha"]&&r["case_id"]==b.selected.caseId&&r["person_id"]==b.selected.counterpartId
    }.getOrDefault(false)
    fun disableSms(id:String)=db.transaction { db.execute("UPDATE related_sms_scopes SET enabled=0,revision=revision+1 WHERE recording_id=?",id);null }
    override fun close()=db.close()
    companion object {
        private val schema=listOf(
            "CREATE TABLE IF NOT EXISTS related_sms_scopes(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER,source_version INTEGER,audio_sha TEXT,link_revision INTEGER,person_id TEXT,case_id TEXT,phone TEXT,from_ms INTEGER,until_ms INTEGER,enabled INTEGER)",
            "CREATE TABLE IF NOT EXISTS related_sms_candidates(digest TEXT PRIMARY KEY,recording_id TEXT REFERENCES recordings(recording_id),scope_revision INTEGER,message_id INTEGER,version INTEGER,phone TEXT,date_ms INTEGER,type INTEGER,raw_text TEXT,content_sha TEXT,incomplete INTEGER,created_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS related_sms_heads(recording_id TEXT,message_id INTEGER,version INTEGER,digest TEXT,content_sha TEXT,date_ms INTEGER,type INTEGER,PRIMARY KEY(recording_id,message_id))",
            "CREATE TABLE IF NOT EXISTS related_sms_scans(scan_id TEXT PRIMARY KEY,recording_id TEXT REFERENCES recordings(recording_id),scope_revision INTEGER,message_count INTEGER,added_count INTEGER,incomplete INTEGER,skipped INTEGER,created_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS related_mail_scopes(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER,saved_scope_sha TEXT,scope_json TEXT,address TEXT,bodies INTEGER,link_revision INTEGER,source_version INTEGER,audio_sha TEXT)"
        )+listOf("related_sms_candidates","related_sms_scans").flatMap { table->listOf("UPDATE","DELETE").map { "CREATE TRIGGER IF NOT EXISTS ${table}_immutable_${it.lowercase()} BEFORE $it ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END" } }+
            listOf("CREATE TRIGGER IF NOT EXISTS sms_candidates_no_replace BEFORE INSERT ON related_sms_candidates WHEN EXISTS(SELECT 1 FROM related_sms_candidates WHERE digest=NEW.digest) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
                "CREATE TRIGGER IF NOT EXISTS sms_scans_no_replace BEFORE INSERT ON related_sms_scans WHEN EXISTS(SELECT 1 FROM related_sms_scans WHERE scan_id=NEW.scan_id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END")
    }
}
