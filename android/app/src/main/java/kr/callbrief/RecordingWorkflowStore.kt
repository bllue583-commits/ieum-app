package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callbrief.core.PrivacyRules
import kr.co.callsummary.core.JsonCanonical
import kr.co.callsummary.core.SqlCallRepository
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Explicit local user commands and evidence on the existing protected canonical port.
 * No model result, phone match or inbound text can execute one of these commands.
 * All per-owner isolation comes from AccountStorageContext, not a sender-provided owner. */
internal class RecordingWorkflowStore(private val context:Context,private val clock:()->Long=System::currentTimeMillis,
    private val policySnapshot:()->JSONObject={SecureSettings(context).snapshot()},
    private val scopeCurrent:()->Boolean={true},private val analysisActor:()->JSONObject?={null}):AutoCloseable {
    private val port:AndroidSqlPort
    private val repository:SqlCallRepository
    init {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "LOCAL_IO_THREAD_REQUIRED" }
        port=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
        try {
            port.requireProtectedPersistence();GeneralLocalStore.overlay(port)
            repository=SqlCallRepository(port,GeneralLocalStore.OWNER);repository.initializeLocalScope()
            port.transaction { SCHEMA.forEach { port.execute(it) };null }
        } catch(e:Exception) { port.close();throw e }
    }
    private fun rows(sql:String,vararg args:Any?)=port.query(sql,*args)
    private fun objectHash(text:String)=kr.callagent.drive.DriveRelay.sha256(kr.callagent.drive.StrictJson.bytes(kr.callagent.drive.StrictJson.parse(text.toByteArray(Charsets.UTF_8))))
    private fun n(r:Map<String,Any?>,key:String)=(r[key] as? Number)?.toLong() ?: 0L
    private fun obj(r:Map<String,Any?>)=JSONObject().apply { r.forEach { (k,v)->put(k,v ?: JSONObject.NULL) } }
    private fun array(r:List<Map<String,Any?>>)=JSONArray().apply { r.forEach { put(obj(it)) } }
    private fun record(id:String)=rows("SELECT r.*,v.audio_sha256,a.archived,a.revision AS visibility_revision,b.revision AS board_revision,t.task_revision,t.case_id AS task_case_id,t.status AS task_status,t.manual_locks AS task_manual_locks FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN local_visibility a ON a.recording_id=r.recording_id JOIN board_state b ON b.owner_key=r.owner_key LEFT JOIN tasks t ON t.task_id=r.task_id WHERE r.recording_id=? AND r.owner_key=?",id,GeneralLocalStore.OWNER).single()
    private fun expect(id:String,snapshot:JSONObject):Map<String,Any?> {
        val r=record(id)
        for(k in listOf("user_revision","lock_revision","head_revision","source_version","visibility_revision"))
            check(n(r,k)==snapshot.getLong(k)) { "USER_REVISION_CONFLICT" }
        return r
    }
    private fun changeBoard()=port.execute("UPDATE board_state SET revision=revision+1 WHERE owner_key=?",GeneralLocalStore.OWNER)
    private fun label(value:String,max:Int=120)=value.trim().also { require(it.isNotEmpty()&&it.length<=max&&!it.any { c->c=='\u0000' }) { "INVALID_USER_VALUE" } }
    private fun event(kind:String,id:String,request:JSONObject):String {
        val e="wf_"+UUID.randomUUID().toString().replace("-","")
        port.execute("INSERT INTO workflow_user_receipts VALUES(?,?,?,?,?)",e,id,kind,request.toString(),clock())
        return e
    }
    fun snapshot(id:String):JSONObject=port.transaction {
        recoverInterruptedRequests(id)
        val r=record(id);val link=rows("SELECT * FROM workflow_links WHERE recording_id=?",id).singleOrNull()
        fun name(k:String)=link?.get(k)?.toString()?.let { key->rows("SELECT label FROM workflow_entities WHERE id=?",key).singleOrNull()?.get("label") }
        val allProposals=if(rows("SELECT name FROM sqlite_master WHERE name='google_native_proposals'").isEmpty())emptyList()
            else rows("SELECT * FROM google_native_proposals WHERE recording_id=? ORDER BY source_version,result_revision,job_id",id)
        val reviewed=rows("SELECT * FROM workflow_reviewed_summaries WHERE recording_id=? ORDER BY revision",id)
        val current=reviewed.lastOrNull { it["source_version"]==r["source_version"] && it["audio_sha"]==r["audio_sha256"] }
        obj(r).put("person_label",name("person_id") ?: JSONObject.NULL).put("company_label",name("company_id") ?: JSONObject.NULL)
            .put("work_label",name("case_id") ?: JSONObject.NULL).put("phone_confirmed",link?.get("phone_normalized")?.toString()?.takeIf { it.isNotBlank() }!=null)
            .put("phone_ending",link?.get("phone_normalized")?.toString()?.takeLast(4) ?: "")
            .put("link_revision",link?.get("revision") ?: 0).put("case_id",link?.get("case_id") ?: JSONObject.NULL)
            .put("items",array(rows("SELECT * FROM workflow_items WHERE recording_id=? ORDER BY created_ms,id",id)))
            .put("progress_history",array(rows("SELECT h.* FROM task_history h JOIN recordings r ON r.task_id=h.task_id WHERE r.recording_id=? ORDER BY h.task_revision,h.event_id",id)))
            .put("related",array(rows("SELECT s.*,l.relation,l.same_phone,l.confirmed_ms FROM workflow_sources s JOIN workflow_source_links l ON l.source_key=s.source_key WHERE l.recording_id=? ORDER BY confirmed_ms",id)))
            .put("requests",JSONArray().apply { rows("SELECT id FROM workflow_analysis_requests WHERE recording_id=? ORDER BY created_ms,id",id).forEach { put(request(it["id"].toString())) } })
            .put("proposals",array(allProposals)).put("reviewed_history",array(reviewed))
            .put("naver_candidates",array(rows("SELECT c.* FROM workflow_naver_candidates c LEFT JOIN workflow_naver_revoked x ON x.account_key=c.account_key WHERE (c.recording_id=? OR EXISTS(SELECT 1 FROM workflow_naver_links l WHERE l.recording_id=? AND l.proposal_digest=c.digest)) AND x.account_key IS NULL ORDER BY c.created_ms,c.digest",id,id).filter { c ->
                val epoch=rows("SELECT epoch FROM workflow_naver_epochs WHERE account_key=?",c["account_key"]).singleOrNull()?.get("epoch") as? Number
                epoch?.toLong()==JSONObject(c["payload_json"].toString()).optLong("hostConnectionEpoch",-1)
            }))
            .put("reviewed_summary",current?.get("text") ?: JSONObject.NULL)
            .put("manual_history",array(rows("SELECT * FROM workflow_user_receipts WHERE recording_id=? ORDER BY created_ms,event_id",id)))
    }
    fun cases():JSONArray=port.transaction { array(rows("SELECT e.id,e.label,t.task_id,t.person_id,t.company_id FROM workflow_entities e JOIN tasks t ON t.case_id=e.id WHERE e.kind='CASE' AND t.owner_key=? ORDER BY e.label,e.id",GeneralLocalStore.OWNER)) }
    fun naverCandidates():JSONArray=port.transaction { array(rows("SELECT c.* FROM workflow_naver_candidates c LEFT JOIN workflow_naver_revoked x ON x.account_key=c.account_key WHERE x.account_key IS NULL ORDER BY c.created_ms,c.digest").filter { c ->
        val epoch=rows("SELECT epoch FROM workflow_naver_epochs WHERE account_key=?",c["account_key"]).singleOrNull()?.get("epoch") as? Number
        epoch?.toLong()==JSONObject(c["payload_json"].toString()).optLong("hostConnectionEpoch",-1)
    }) }
    /** Shared phone numbers do not select IDs; reuse requires an explicitly selected existing case. */
    fun confirmWork(id:String,expected:JSONObject,person:String,company:String,work:String,phone:String,selectedCase:String?=null):String=port.transaction {
        val r=expect(id,expected)
        val normalized=if(phone.isBlank())rows("SELECT phone_normalized FROM workflow_links WHERE recording_id=?",id).singleOrNull()?.get("phone_normalized")?.toString().orEmpty()
            else PrivacyRules.validatedPhone(phone) ?: error("EXACT_PHONE_REQUIRED")
        fun entity(kind:String,value:String):String?=value.trim().takeIf { it.isNotEmpty() }?.let {
            val key="wf_"+kind.lowercase()+"_"+UUID.randomUUID().toString().replace("-","")
            port.execute("INSERT INTO workflow_entities VALUES(?,?,?)",key,kind,label(it));key
        }
        val task:String;val case:String;val personId:String?;val companyId:String?
        if(selectedCase!=null) {
            val selected=rows("SELECT t.* FROM tasks t JOIN workflow_entities e ON e.id=t.case_id AND e.kind='CASE' WHERE t.case_id=? AND t.owner_key=?",selectedCase,GeneralLocalStore.OWNER).single()
            task=selected["task_id"].toString();case=selectedCase;personId=selected["person_id"] as? String;companyId=selected["company_id"] as? String
        } else {
            case=entity("CASE",label(work))!!;personId=entity("PERSON",person);companyId=entity("COMPANY",company)
            task="wf_task_"+UUID.randomUUID().toString().replace("-","")
            repository.registerExplicitTask(task,case,personId,companyId)
        }
        repository.userCommand("CONFIRM",SqlCallRepository.map("event_id","wf_confirm_"+UUID.randomUUID().toString().replace("-",""),
            "recording_id",id,"expected_user_revision",r["user_revision"],"expected_lock_revision",r["lock_revision"],
            "person_id",personId,"company_id",companyId,"task_id",task,"original_started_at_ms",r["original_started_at_ms"],"now_ms",clock()))
        val old=rows("SELECT revision FROM workflow_links WHERE recording_id=?",id).singleOrNull();val revision=(old?.get("revision") as? Number)?.toLong()?.plus(1) ?: 1L
        if(old==null)port.execute("INSERT INTO workflow_links VALUES(?,?,?,?,?,?,?)",id,revision,personId,companyId,case,task,normalized)
        else port.execute("UPDATE workflow_links SET revision=?,person_id=?,company_id=?,case_id=?,task_id=?,phone_normalized=? WHERE recording_id=?",revision,personId,companyId,case,task,normalized,id)
        event("CONFIRM_WORK",id,JSONObject().put("caseId",case).put("personId",personId ?: JSONObject.NULL).put("companyId",companyId ?: JSONObject.NULL).put("explicitCaseReuse",selectedCase!=null))
        changeBoard();case
    }
    fun createItem(id:String,expected:JSONObject,kind:String,title:String,edition:String,due:String,sourceKey:String?=null,quote:String="",proposalDigest:String?=null,initialStatus:String="open"):String=port.transaction {
        expect(id,expected);require(kind in listOf("TODO","DOCUMENT"));label(title,240);require(edition.length<=80&&due.length<=120)
        require(initialStatus in listOf("open","waiting","in_progress","needs_confirmation","done")) { "INVALID_ITEM_STATUS" }
        require(sourceKey==null || proposalDigest==null) { "ONE_EVIDENCE_BINDING_REQUIRED" }
        if(sourceKey!=null) {
            val body=rows("SELECT s.raw_text FROM workflow_sources s JOIN workflow_source_links l ON l.source_key=s.source_key WHERE l.recording_id=? AND s.source_key=?",id,sourceKey).single()["raw_text"].toString()
            require(quote.isNotBlank() && body.contains(quote)) { "EXACT_EVIDENCE_REQUIRED" }
        }
        if(proposalDigest!=null) {
            val r=record(id)
            val proposal=rows("SELECT * FROM google_native_proposals WHERE recording_id=? AND digest=?",id,proposalDigest).single()
            check(n(proposal,"source_version")==n(r,"source_version") && proposal["audio_sha"]==r["audio_sha256"] && n(proposal,"hidden")==0L) { "PROPOSAL_SOURCE_CHANGED" }
            require(quote.isNotBlank() && proposal["raw_text"].toString().contains(quote)) { "EXACT_EVIDENCE_REQUIRED" }
        }
        val case=rows("SELECT case_id FROM workflow_links WHERE recording_id=?",id).singleOrNull()?.get("case_id")
        val item="wf_item_"+UUID.randomUUID().toString().replace("-","")
        port.execute("INSERT INTO workflow_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",item,id,case,kind,title.trim(),edition.trim(),due.trim(),initialStatus,1,sourceKey,quote,clock())
        event("CREATE_"+kind,id,JSONObject().put("itemId",item).put("title",title).put("status",initialStatus).put("sourceKey",sourceKey ?: JSONObject.NULL).put("proposalDigest",proposalDigest ?: JSONObject.NULL).put("evidenceQuote",quote).put("meaningConfirmedByUser",true));changeBoard();item
    }
    fun editItem(item:String,revision:Long,title:String,status:String,due:String,edition:String)=port.transaction {
        val old=rows("SELECT * FROM workflow_items WHERE id=?",item).single();require(n(old,"revision")==revision) { "ITEM_REVISION_CONFLICT" }
        require(status in listOf("open","waiting","in_progress","needs_confirmation","done"));label(title,240);require(due.length<=120&&edition.length<=80)
        val id=old["recording_id"].toString();record(id)
        port.execute("UPDATE workflow_items SET title=?,status=?,due_text=?,edition=?,revision=revision+1 WHERE id=?",title.trim(),status,due.trim(),edition.trim(),item)
        val e=event("EDIT_ITEM",id,JSONObject().put("itemId",item).put("before",obj(old)).put("after",JSONObject().put("title",title).put("status",status).put("due",due).put("edition",edition)))
        port.execute("INSERT INTO workflow_item_history VALUES(?,?,?,?)",e,item,revision+1,obj(old).toString());changeBoard();null
    }
    /** Separate direct user command: a locked progress value is never changed by an analysis proposal. */
    fun editBusinessProgress(id:String,expected:JSONObject,status:String,note:String,confirmed:Boolean):String=port.transaction {
        check(scopeCurrent() && confirmed) { "USER_PROGRESS_CONFIRMATION_REQUIRED" }
        val r=expect(id,expected)
        check(r["task_id"] is String && n(r,"archived")==0L && n(r,"task_revision")==expected.getLong("task_revision") && n(r,"board_revision")==expected.getLong("board_revision")) { "BUSINESS_REVISION_CONFLICT" }
        require(status in listOf("open","waiting","in_progress","needs_confirmation","done"));require(note.length<=240)
        val taskEvent="wf_progress_"+UUID.randomUUID().toString().replace("-","")
        repository.userCommand("TASK_EDIT",SqlCallRepository.map("event_id",taskEvent,"task_id",r["task_id"],"expected_task_revision",r["task_revision"],"expected_board_revision",r["board_revision"],"new_status",status,"now_ms",clock()))
        val e=event("USER_EDIT_BUSINESS_PROGRESS",id,JSONObject().put("taskReceipt",taskEvent).put("before",r["task_status"] ?: JSONObject.NULL).put("after",status).put("reason",note).put("sourceVersion",r["source_version"]).put("audioHash",r["audio_sha256"]).put("meaningConfirmedByUser",true))
        check(scopeCurrent()) { "ANALYSIS_SCOPE_CHANGED" };e
    }
    /** SMS number equality is evidence only; the caller separately chooses this recording. */
    fun addRelated(id:String,expected:JSONObject,kind:String,text:String,phone:String,sentAt:String,title:String,externalIdentity:String?=null,includeAnalysis:Boolean=false):String=port.transaction {
        expect(id,expected);require(kind in listOf("SMS","MAIL"));label(text,20000);require(title.length<=240&&sentAt.length<=120)
        val normalized=if(phone.isBlank())"" else PrivacyRules.validatedPhone(phone) ?: error("EXACT_PHONE_REQUIRED")
        val link=rows("SELECT phone_normalized FROM workflow_links WHERE recording_id=?",id).singleOrNull()
        val own=link?.get("phone_normalized")?.toString().orEmpty();val same=normalized.isNotBlank() && own==normalized
        if(normalized.isNotBlank())check(!LocalPrivacy.rules(policySnapshot()).excluded("",normalized)) { "EXCLUDED_BEFORE_READ" }
        if(kind=="SMS" && own.isNotBlank() && normalized.isNotBlank())require(same) { "COUNTERPART_MISMATCH" }
        val bodySha=PrivacyRules.hash(text);val identity=externalIdentity ?: JsonCanonical.digest(listOf(kind,normalized,sentAt,text))
        val key=JsonCanonical.digest(listOf("workflow-evidence-v1",kind,identity));val old=rows("SELECT * FROM workflow_sources WHERE source_key=?",key).singleOrNull()
        if(old==null)port.execute("INSERT INTO workflow_sources VALUES(?,?,?,?,?,?,?,?)",key,kind,bodySha,text,title,sentAt,"USER_SELECTED",clock())
        else require(old["content_sha"]==bodySha) { "EVIDENCE_CONTENT_CHANGED_REVIEW_REQUIRED" }
        if(rows("SELECT source_key FROM workflow_source_links WHERE source_key=? AND recording_id=?",key,id).isEmpty()) {
            port.execute("INSERT INTO workflow_source_links VALUES(?,?,?,?,?)",id,key,if(same)"SAME_NUMBER_USER_LINKED" else "USER_LINKED_IDENTITY_UNCONFIRMED",if(same)1 else 0,clock())
            event("LINK_"+kind,id,JSONObject().put("sourceKey",key).put("samePhone",same).put("automaticCaseMerge",false));changeBoard()
        }
        if(rows("SELECT source_key FROM workflow_source_analysis_policy WHERE recording_id=? AND source_key=?",id,key).isEmpty())
            port.execute("INSERT INTO workflow_source_analysis_policy VALUES(?,?,?,0)",id,key,if(includeAnalysis)1 else 0)
        else if(includeAnalysis)setEvidenceAnalysisConsent(id,key,true)
        key
    }
    fun setEvidenceAnalysisConsent(id:String,key:String,allowed:Boolean)=port.transaction {
        record(id);val old=rows("SELECT * FROM workflow_source_analysis_policy WHERE recording_id=? AND source_key=?",id,key).single()
        if(allowed)rows("SELECT * FROM workflow_naver_links WHERE recording_id=? AND source_key=?",id,key).singleOrNull()?.let { link ->
            val live=rows("SELECT epoch FROM workflow_naver_epochs WHERE account_key=?",link["account_key"]).single()
            require(rows("SELECT account_key FROM workflow_naver_revoked WHERE account_key=?",link["account_key"]).isEmpty() && live["epoch"]==link["connection_epoch"]) { "MAIL_CONNECTION_REVIEW_REQUIRED" }
        }
        if(n(old,"allowed")!=(if(allowed)1L else 0L)) {
            port.execute("UPDATE workflow_source_analysis_policy SET allowed=?,epoch=epoch+1 WHERE recording_id=? AND source_key=?",if(allowed)1 else 0,id,key)
            if(!allowed) {
                if(rows("SELECT recording_id FROM workflow_analysis_authority WHERE recording_id=?",id).isEmpty())port.execute("INSERT INTO workflow_analysis_authority VALUES(?,1)",id)
                else port.execute("UPDATE workflow_analysis_authority SET epoch=epoch+1 WHERE recording_id=?",id)
            }
            event("EVIDENCE_ANALYSIS_CONSENT",id,JSONObject().put("sourceKey",key).put("allowed",allowed));changeBoard()
        };null
    }
    /** Called only by the current host MailIntake SPI, not by incoming message text or JSON. */
    fun storeNaverBatch(batch:kr.callbrief.navermail.ScanBatch,recording:String?,ownerCurrent:()->Boolean)=port.transaction {
        check(ownerCurrent()) { "MAIL_OWNER_CHANGED" }
        if(recording!=null)record(recording)
        require(batch.account==batch.scope.account && batch.scope.fingerprint.isNotBlank()) { "MAIL_SCOPE_CHANGED" }
        val tag=batch.intakeRunTag.takeIf { it.isNotBlank() } ?: if(batch.origin==kr.callbrief.navermail.Origin.SYNTHETIC_FIXTURE)"synthetic-host-fixture" else error("MAIL_INTAKE_RUN_REQUIRED")
        val oldRun=rows("SELECT * FROM workflow_naver_epochs WHERE account_key=?",batch.account.key).singleOrNull()
        val connectionEpoch=if(oldRun==null || oldRun["run_tag"]!=tag) {
            val next=(oldRun?.get("epoch") as? Number)?.toLong()?.plus(1) ?: 1L
            if(oldRun==null)port.execute("INSERT INTO workflow_naver_epochs VALUES(?,?,?)",batch.account.key,next,tag)
            else port.execute("UPDATE workflow_naver_epochs SET epoch=?,run_tag=? WHERE account_key=?",next,tag,batch.account.key)
            port.execute("DELETE FROM workflow_naver_revoked WHERE account_key=?",batch.account.key);next
        } else {
            check(rows("SELECT account_key FROM workflow_naver_revoked WHERE account_key=?",batch.account.key).isEmpty()) { "MAIL_CONNECTION_REVIEW_REQUIRED" };n(oldRun,"epoch")
        }
        for(candidate in batch.candidates) {
            val h=candidate.header;require(h.ref.accountKey==batch.account.key && h.origin==batch.origin) { "MAIL_SOURCE_BINDING_CHANGED" }
            val body=batch.selectedBodies[h.ref]
            if(body!=null)require(body.ref==h.ref&&body.origin==batch.origin&&body.text.length<=32768) { "MAIL_BODY_BINDING_CHANGED" }
            val payload=JSONObject().put("accountKey",batch.account.key).put("scopeFingerprint",batch.scope.fingerprint).put("mailbox",h.ref.mailbox)
                .put("uidValidity",h.ref.uidValidity).put("uid",h.ref.uid).put("subject",h.subject).put("headerSha",h.rawHeaderSha)
                .put("internalDate",h.internalDate.toString()).put("origin",batch.origin.name).put("bodyState",candidate.bodyState.name)
                .put("rawText",body?.text ?: JSONObject.NULL).put("contentSha",body?.let { PrivacyRules.hash(it.text) } ?: JSONObject.NULL)
                .put("truncated",body?.truncated ?: false).put("coverageIncomplete",batch.coverageIncomplete).put("uncertainties",JSONArray(candidate.uncertainties))
                .put("hostConnectionEpoch",connectionEpoch)
                .put("proposals",JSONArray().apply { candidate.proposals.forEach { p ->
                    put(JSONObject().put("kind",p.kind.name).put("text",p.text).put("quote",p.evidence.quote).put("start",p.evidence.start).put("end",p.evidence.end).put("offsetUnit","UTF16").put("originalBodySha",p.evidence.contentSha).put("requiresUserConfirmation",true))
                } })
            val digest=PrivacyRules.hash(payload.toString())
            if(rows("SELECT digest FROM workflow_naver_candidates WHERE digest=?",digest).isEmpty())
                port.execute("INSERT INTO workflow_naver_candidates VALUES(?,?,?,?,?,?,?,?,?)",digest,recording,batch.account.key,batch.scope.fingerprint,batch.epoch,h.subject,body?.text,payload.toString(),clock())
        }
        check(ownerCurrent()) { "MAIL_OWNER_CHANGED" };null
    }
    fun confirmNaverCandidate(id:String,expected:JSONObject,digest:String):String=port.transaction {
        expect(id,expected)
        val candidate=rows("SELECT c.* FROM workflow_naver_candidates c LEFT JOIN workflow_naver_revoked r ON r.account_key=c.account_key WHERE c.digest=? AND (c.recording_id=? OR c.recording_id IS NULL) AND r.account_key IS NULL",digest,id).single()
        val p=JSONObject(candidate["payload_json"].toString());val text=p.optString("rawText").takeUnless { it.isBlank()||it=="null" } ?: error("MAIL_BODY_NOT_SELECTED")
        check(p.getLong("hostConnectionEpoch")==n(rows("SELECT epoch FROM workflow_naver_epochs WHERE account_key=?",candidate["account_key"]).single(),"epoch")) { "MAIL_CONNECTION_REVIEW_REQUIRED" }
        require(PrivacyRules.hash(text)==p.getString("contentSha")) { "MAIL_CONTENT_HASH_MISMATCH" }
        val baseIdentity="naver-v1:${p.getString("accountKey")}:${p.getString("mailbox")}:${p.getLong("uidValidity")}:${p.getLong("uid")}"
        val legacyKey=JsonCanonical.digest(listOf("workflow-evidence-v1","MAIL",baseIdentity))
        val old=rows("SELECT content_sha FROM workflow_sources WHERE source_key=?",legacyKey).singleOrNull()
        // A UID identifies a message, not immutable body bytes. Preserve older body evidence;
        // a byte-identical legacy source retains its existing identity and consent history.
        val identity=if(old?.get("content_sha")==p.getString("contentSha"))baseIdentity else baseIdentity+":body:"+p.getString("contentSha")
        val key=addRelated(id,expected,"MAIL",text,"",p.getString("internalDate"),p.getString("subject"),identity)
        if(rows("SELECT source_key FROM workflow_naver_links WHERE recording_id=? AND source_key=?",id,key).isEmpty())
            port.execute("INSERT INTO workflow_naver_links VALUES(?,?,?,?,?)",id,key,candidate["account_key"],digest,p.getLong("hostConnectionEpoch"))
        else port.execute("UPDATE workflow_naver_links SET proposal_digest=?,connection_epoch=? WHERE recording_id=? AND source_key=?",digest,p.getLong("hostConnectionEpoch"),id,key)
        key
    }
    fun revokeNaverAccount(accountKey:String)=port.transaction {
        if(rows("SELECT account_key FROM workflow_naver_revoked WHERE account_key=?",accountKey).isEmpty())
            port.execute("INSERT INTO workflow_naver_revoked VALUES(?,?)",accountKey,clock())
        rows("SELECT recording_id,source_key FROM workflow_naver_links WHERE account_key=?",accountKey).forEach { r ->setEvidenceAnalysisConsent(r["recording_id"].toString(),r["source_key"].toString(),false) }
        null
    }
    /** A user's own summary is distinct from any unverified external producer result. */
    fun reviewOwnSummary(id:String,expected:JSONObject,text:String,proposalDigest:String?=null):String=port.transaction {
        val r=expect(id,expected);label(text,3000)
        if(proposalDigest!=null) {
            val p=rows("SELECT * FROM google_native_proposals WHERE recording_id=? AND digest=?",id,proposalDigest).single()
            require(p["source_version"]==r["source_version"]&&p["audio_sha"]==r["audio_sha256"]&&n(p,"hidden")==0L) { "REVIEW_SOURCE_STALE" }
        }
        val revision=n(rows("SELECT coalesce(max(revision),0) AS revision FROM workflow_reviewed_summaries WHERE recording_id=?",id).single(),"revision")+1
        val receipt=event("USER_REVIEW_SUMMARY",id,JSONObject().put("summaryRevision",revision).put("sourceVersion",r["source_version"]).put("proposalDigest",proposalDigest ?: JSONObject.NULL).put("origin","USER_REVIEWED_LOCAL_NOT_MODEL_ATTESTATION"))
        changeBoard();val board=n(rows("SELECT revision FROM board_state WHERE owner_key=?",GeneralLocalStore.OWNER).single(),"revision")
        port.execute("INSERT INTO workflow_reviewed_summaries VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,revision,r["source_version"],r["audio_sha256"],text,receipt,r["user_revision"],r["lock_revision"],r["head_revision"],board,proposalDigest,clock())
        receipt
    }
    /** Re-read the original immutable receipt and request; never interpret readonly-v1 as v0. */
    private fun verifiedBusinessProposal(id:String,digest:String):JSONObject {
        val p=rows("SELECT * FROM google_native_proposals WHERE recording_id=? AND digest=?",id,digest).single()
        check(n(p,"schema_version")==0L) { "ANALYSIS_PROFILE_REVIEW_ONLY" }
        val job=rows("SELECT frozen FROM drive_v0_jobs WHERE job_id=?",p["job_id"]).single()["frozen"] as ByteArray
        val request=JSONObject(String(job,Charsets.UTF_8)).getString("requestJson").toByteArray(Charsets.UTF_8)
        val req=JSONObject(String(request,Charsets.UTF_8));kr.callagent.drive.WireV0Codec.validateRequest(request)
        val owner=req.getJSONObject("ownerBinding").getString("ownerId")
        val receipt=rows("SELECT * FROM drive_profiles_receipts WHERE owner_id=? AND job_id=? AND kind=? AND schema_version=? AND result_revision=? AND digest=?",owner,p["job_id"],p["kind"],p["schema_version"],p["result_revision"],digest).single()
        val payload=receipt["payload"] as ByteArray
        val envelope=JSONObject(String(payload,Charsets.UTF_8))
        check(envelope.getString("resultDigest")==digest && kr.callagent.drive.WireV0Codec.digest("result",kr.callagent.drive.StrictJson.`object`(kr.callagent.drive.StrictJson.parse(payload)))==digest) { "ANALYSIS_RECEIPT_CHANGED" }
        for(k in listOf("jobId","sourceId","sourceVersion","audioSha","baseRevision","userRevision","leaseEpoch","requestDigest","resultFileId"))
            check(envelope.get(k)==req.get(k)) { "ANALYSIS_BINDING_CHANGED" }
        check(req.getString("sourceId")==id && req.getLong("sourceVersion")==n(p,"source_version") && req.getString("audioSha")==p["audio_sha"]) { "ANALYSIS_BINDING_CHANGED" }
        val inspected=AnalysisBusinessContract.inspect(payload,request)
        check(inspected.getString("raw_text")==p["raw_text"] && inspected.getString("candidate_json")==p["analysis_json"]) { "ANALYSIS_RECEIPT_CHANGED" }
        return inspected.put("digest",digest).put("result_revision",p["result_revision"]).put("source_version",p["source_version"])
            .put("request",req).put("proposal",obj(p)).put("owner",owner)
    }
    private fun requireBusinessCurrent(id:String,expected:JSONObject,p:JSONObject) {
        requireBusinessActor(p)
        val r=expect(id,expected);val req=p.getJSONObject("request");val old=p.getJSONObject("proposal")
        check(n(r,"board_revision")==expected.getLong("board_revision") && n(r,"task_revision")==expected.optLong("task_revision")) { "BUSINESS_REVISION_CONFLICT" }
        check(n(r,"archived")==0L && r["presence"]=="PRESENT") { "ANALYSIS_SOURCE_UNAVAILABLE" }
        check(rows("SELECT active FROM folder_grants WHERE grant_id=? AND active=1",r["grant_id"]).size==1) { "ANALYSIS_SOURCE_UNAVAILABLE" }
        val ref=rows("SELECT actual_uri FROM local_files WHERE recording_id=?",id).singleOrNull()
        check(ref!=null) { "ANALYSIS_SOURCE_UNAVAILABLE" }
        val policy=policySnapshot();val file=RecordingRef(android.net.Uri.parse(ref["actual_uri"].toString()),r["display_name"].toString(),n(r,"byte_count"),r["mime_type"].toString(),
            (r["original_started_at_ms"] as? Number)?.toLong()?.let(java.time.Instant::ofEpochMilli),n(r,"modified_at_ms"))
        check(!policy.optBoolean("pendingRevocation") && !LocalPrivacy.blocked(file,policy)) { "ANALYSIS_POLICY_CHANGED" }
        p.put("review_policy_sha",objectHash(policy.toString()))
        check(old.optInt("hidden")==0 && old.optInt("conflict")==0) { "ANALYSIS_USER_STATE_CHANGED" }
        for((key,native) in listOf("sourceVersion" to "source_version","userRevision" to "user_revision","lockRevision" to "lock_revision","baseRevision" to "board_revision","taskRevision" to "task_revision","archiveRevision" to "visibility_revision"))
            check(req.getLong(key)==n(r,native)) { "ANALYSIS_BASE_REVISION_CHANGED" }
        val ctx=JSONObject(req.getString("contextJson"))
        check(req.getString("audioSha")==r["audio_sha256"] && ctx.getLong("nativeHeadRevision")==n(r,"head_revision")) { "ANALYSIS_SOURCE_STALE" }
        check(r["person_id"] is String && r["task_id"] is String && r["original_started_at_ms"]!=null && ctx.optString("personId")==r["person_id"] && ctx.optString("taskId")==r["task_id"]) { "ANALYSIS_IDENTITY_TIME_REVIEW_REQUIRED" }
        check(r["task_case_id"] is String && ctx.optString("caseId")==r["task_case_id"] && rows("SELECT recording_id FROM workflow_links WHERE recording_id=? AND task_id=? AND person_id=? AND case_id=?",id,r["task_id"],r["person_id"],r["task_case_id"]).size==1) { "ANALYSIS_IDENTITY_TIME_REVIEW_REQUIRED" }
        val head=rows("SELECT * FROM google_native_proposal_heads WHERE recording_id=?",id).single()
        val global=rows("SELECT * FROM drive_profiles_heads WHERE owner_id=? AND result_id=?",p.getString("owner"),req.getString("resultFileId")).single()
        for(h in listOf(head,global))check(h["job_id"]==req.getString("jobId") && h["digest"]==p.getString("digest") && n(h,"result_revision")==p.getLong("result_revision")) { "ANALYSIS_RESULT_SUPERSEDED" }
        // ERROR/PENDING receipts need not replace the proposal head; they still supersede its reviewability.
        check(rows("SELECT digest FROM drive_profiles_receipts WHERE owner_id=? AND result_id=? AND result_revision>?",p.getString("owner"),req.getString("resultFileId"),p.getLong("result_revision")).isEmpty()) { "ANALYSIS_RESULT_SUPERSEDED" }
        check(rows("SELECT digest FROM drive_profiles_collisions WHERE owner_id=? AND job_id=? AND kind=? AND schema_version=? AND result_revision=?",p.getString("owner"),req.getString("jobId"),head["kind"],head["schema_version"],head["result_revision"]).isEmpty()) { "ANALYSIS_RESULT_COLLISION" }
        val active=rows("SELECT job_id FROM drive_v0_active WHERE source_id=?",id).single()
        check(active["job_id"]==req.getString("jobId")) { "ANALYSIS_REQUEST_SUPERSEDED" }
        check(rows("SELECT id FROM workflow_analysis_requests WHERE job_id=? AND state='CANCELLED'",req.getString("jobId")).isEmpty()) { "ANALYSIS_REQUEST_CANCELLED" }
    }
    private fun requireBusinessActor(p:JSONObject) {
        check(scopeCurrent()) { "ANALYSIS_SCOPE_CHANGED" }
        val actor=analysisActor() ?: error("ANALYSIS_ACTOR_REQUIRED")
        check(objectHash(actor.toString())==objectHash(JSONObject(p.getJSONObject("request").getString("contextJson")).getJSONObject("authSubject").toString())) { "ANALYSIS_ACTOR_CHANGED" }
    }
    fun businessReview(id:String,digest:String):JSONObject=port.transaction {
        val p=verifiedBusinessProposal(id,digest)
        val current=snapshot(id)
        val reason=runCatching { requireBusinessCurrent(id,current,p) }.exceptionOrNull()?.message
        p.remove("request");p.remove("proposal");p.remove("owner")
        p.put("eligible",reason==null).put("hold_reason",reason ?: JSONObject.NULL)
    }
    /** Atomic explicit user batch. Individual facts never select identity, merge by name or run themselves. */
    fun applyBusinessReview(id:String,expected:JSONObject,digest:String,actions:JSONArray,summary:String?,meaningConfirmed:Boolean):String=port.transaction {
        check(meaningConfirmed) { "USER_MEANING_REVIEW_REQUIRED" };require(actions.length()<=40 && (actions.length()>0 || !summary.isNullOrBlank()))
        val p=verifiedBusinessProposal(id,digest)
        requireBusinessActor(p)
        val operation=JSONObject().put("digest",digest).put("expected",JSONObject(expected.toString())).put("actions",JSONArray(actions.toString())).put("summary",summary ?: JSONObject.NULL)
        val actionHash=objectHash(operation.toString())
        val prior=rows("SELECT event_id FROM workflow_business_reviews WHERE action_hash=?",actionHash).singleOrNull()
        if(prior!=null)return@transaction prior["event_id"].toString()
        requireBusinessCurrent(id,expected,p)
        val claims=p.getJSONArray("claims");val seen=mutableSetOf<String>();var progress=false
        for(i in 0 until actions.length()) {
            val a=actions.getJSONObject(i);val claim=a.getString("claim_id");check(seen.add(claim)) { "DUPLICATE_CLAIM_ACTION" }
            val c=(0 until claims.length()).map { claims.getJSONObject(it) }.single { it.getString("claim_id")==claim }
            check(c.getBoolean("selectable") && a.getString("kind")==c.getString("suggested_kind")) { "CLAIM_REVIEW_ONLY" }
            val status=a.getString("status");require(status in listOf("open","waiting","in_progress","needs_confirmation","done"))
            check(status!="done" || a.optBoolean("completion_confirmed")) { "EXPLICIT_COMPLETION_REQUIRED" }
            val quote=c.getJSONArray("evidence").getJSONObject(0).getString("quote")
            if(a.getString("kind")=="PROGRESS") {
                check(!progress) { "ONE_PROGRESS_ACTION_REQUIRED" };progress=true
                val r=record(id)
                check(n(r,"task_manual_locks")==0L && n(r,"completed_lock")==0L && n(r,"edited_lock")==0L && r["manual_status"]!="done") { "MANUAL_PROGRESS_PROTECTED" }
                repository.userCommand("TASK_EDIT",SqlCallRepository.map("event_id","wf_progress_"+UUID.randomUUID().toString().replace("-",""),"task_id",r["task_id"],"expected_task_revision",r["task_revision"],"expected_board_revision",r["board_revision"],"new_status",status,"now_ms",clock()))
            } else {
                val target=a.optString("target_id").takeIf { it.isNotBlank() && it!="null" }
                if(target==null)createItem(id,snapshot(id),a.getString("kind"),a.getString("title"),a.optString("edition"),a.optString("due"),quote=quote,proposalDigest=digest,initialStatus=status)
                else {
                    val old=rows("SELECT * FROM workflow_items WHERE id=? AND recording_id=?",target,id).single()
                    check(old["kind"]==a.getString("kind") && n(old,"revision")==a.getLong("target_revision")) { "ITEM_REVISION_CONFLICT" }
                    check(old["status"]!="done" && rows("SELECT item_id FROM workflow_item_history WHERE item_id=?",target).isEmpty()) { "MANUAL_ITEM_PROTECTED" }
                    editItem(target,a.getLong("target_revision"),a.getString("title"),status,a.optString("due"),a.optString("edition"))
                }
            }
        }
        if(!summary.isNullOrBlank())reviewOwnSummary(id,snapshot(id),summary,digest)
        val e=event("USER_REVIEW_ANALYSIS_BUSINESS",id,operation.put("origin","USER_REVIEWED_SYNTHETIC_PROPOSAL_NOT_MODEL_ATTESTATION").put("sourcePackSha",p.getString("source_pack_sha")).put("packetJson",p.getString("packet_json")).put("candidateJson",p.getString("candidate_json")).put("meaningConfirmedByUser",true))
        port.execute("INSERT INTO workflow_business_reviews VALUES(?,?,?,?)",e,id,actionHash,digest)
        check(objectHash(policySnapshot().toString())==p.getString("review_policy_sha")) { "ANALYSIS_POLICY_CHANGED" }
        requireBusinessActor(p);e
    }
    fun prepareAnalysis(id:String,expected:JSONObject,actor:JSONObject?=null,retryOf:String?=null):String=port.transaction {
        val r=expect(id,expected)
        val binding=JSONObject().put("sourceVersion",r["source_version"]).put("audioSha",r["audio_sha256"]).put("userRevision",r["user_revision"])
            .put("lockRevision",r["lock_revision"]).put("headRevision",r["head_revision"]).put("visibilityRevision",r["visibility_revision"]).put("boardRevision",r["board_revision"])
        if(actor!=null)binding.put("actor",JSONObject(actor.toString()))
        if(retryOf!=null)binding.put("retryOf",retryOf)
        val digest=JsonCanonical.digest(binding.toString());val old=rows("SELECT id FROM workflow_analysis_requests WHERE recording_id=? AND binding_digest=? AND state!='CANCELLED'",id,digest).singleOrNull()
        if(old!=null)return@transaction old["id"].toString()
        val request="wf_request_"+UUID.randomUUID().toString().replace("-","")
        port.execute("INSERT INTO workflow_analysis_requests VALUES(?,?,?,?,?,?,?,?)",request,id,digest,binding.toString(),"PREPARED",null,null,clock());request
    }
    fun request(id:String):JSONObject=port.transaction {
        val request=obj(rows("SELECT * FROM workflow_analysis_requests WHERE id=?",id).single())
        request.put("claim_current_process",rows("SELECT id FROM workflow_analysis_claims WHERE id=? AND process_token=?",id,PROCESS_TOKEN).isNotEmpty())
            .put("has_reserved_binding",rows("SELECT request_id FROM workflow_analysis_bindings WHERE request_id=?",id).isNotEmpty())
    }
    /** Display commit is serialized with the durable accepted revision. I/O completion order
     * and duplicate receipt presence cannot move a newer result back to an older state. */
    fun commitResultObservation(id:String,o:GoogleRelayStore.ResultObservation):String=port.transaction {
        val current=request(id);val previous=current.getString("state")
        if(previous=="CANCELLED")return@transaction previous
        check(!current.isNull("job_id")&&current.getString("job_id")==o.jobId) { "RESULT_REQUEST_JOB_MISMATCH" }
        fun hold(code:String):String { transitionRequest(id,previous,o.jobId,code);return previous }
        val advances=o.disposition in listOf("PROVENANCE_PENDING","READ_ONLY_CANDIDATE","LEGACY_CANDIDATE","DUPLICATE_PROPOSAL","ERROR","CANCELLED","PENDING")
        if(!advances)return@transaction hold(o.disposition)
        val saved=rows("SELECT * FROM workflow_analysis_result_cursor WHERE request_id=?",id).singleOrNull()
        val revision=o.revision
        if(revision==null) {
            if(saved!=null)return@transaction hold("UNVERSIONED_RESULT_HELD")
        } else {
            require(revision>=0 && o.schemaVersion!=null && o.schemaVersion>=0 && !o.kind.isNullOrBlank() && o.digest?.matches(Regex("[0-9a-f]{64}"))==true) { "INVALID_RESULT_OBSERVATION" }
            if(saved!=null) {
                val before=n(saved,"result_revision")
                if(revision<before)return@transaction hold("OLD_RESULT_HISTORY_PRESERVED")
                if(revision==before) {
                    if(o.digest!=saved["result_digest"]||o.kind!=saved["kind"]||o.schemaVersion!=n(saved,"schema_version"))return@transaction hold("RESULT_REVISION_CONFLICT")
                    return@transaction hold("RESULT_DUPLICATE_CURRENT")
                }
            }
            // Another poll can accept a newer receipt before this earlier I/O commits to the UI.
            if(rows("SELECT name FROM sqlite_master WHERE name='drive_profiles_heads'").isNotEmpty()) {
                val head=rows("SELECT * FROM drive_profiles_heads WHERE job_id=?",o.jobId).singleOrNull()
                if(head!=null) {
                    val highest=n(head,"result_revision")
                    if(revision<highest)return@transaction hold("OLD_RESULT_HISTORY_PRESERVED")
                    if(revision==highest && (o.digest!=head["digest"] || o.kind!=head["kind"] || o.schemaVersion!=n(head,"schema_version")))return@transaction hold("RESULT_REVISION_CONFLICT")
                }
            }
            if(saved==null)port.execute("INSERT INTO workflow_analysis_result_cursor VALUES(?,?,?,?,?,?,?)",id,o.jobId,o.kind,o.schemaVersion,revision,o.digest,clock())
            else port.execute("UPDATE workflow_analysis_result_cursor SET kind=?,schema_version=?,result_revision=?,result_digest=?,observed_ms=? WHERE request_id=?",o.kind,o.schemaVersion,revision,o.digest,clock(),id)
        }
        val state=when(o.disposition) {
            "PROVENANCE_PENDING","READ_ONLY_CANDIDATE","LEGACY_CANDIDATE","DUPLICATE_PROPOSAL"->"REVIEW"
            "ERROR","CANCELLED"->"ERROR"
            "PENDING"->if(previous=="REVIEW")previous else "PENDING"
            else->previous
        }
        transitionRequest(id,state,o.jobId,o.disposition);state
    }
    fun commitSyncObservation(id:String,o:RecordingAnalysisPort.SyncObservation):String=port.transaction {
        val state=if(o.result!=null)commitResultObservation(id,o.result) else request(id).getString("state")
        // An abstract sync success/error without a revision cannot change result state.
        if(o.result==null || o.disposition in listOf("AWAITING_SUPPORTED_DOT_ENTRY","AWAITING_DOT_OPERATOR","REQUEST_NOT_COMMITTED","LOCAL_BINDING_HELD","DOT_PROFILE_ACCOUNT_UNSUPPORTED"))
            transitionRequest(id,state,reason=o.disposition)
        state
    }
    private fun recoverInterruptedRequests(recording:String) {
        val old=rows("SELECT r.* FROM workflow_analysis_requests r LEFT JOIN workflow_analysis_claims c ON c.id=r.id WHERE r.recording_id=? AND r.state='SUBMITTING' AND (c.process_token IS NULL OR c.process_token!=?)",recording,PROCESS_TOKEN)
        old.forEach { row->port.execute("UPDATE workflow_analysis_requests SET state='UNKNOWN',reason='INTERRUPTED_DELIVERY_REQUIRES_RECONCILIATION' WHERE id=?",row["id"]);reconcileRemoteJob(row["id"].toString()) }
    }
    /** Called by the relay reservation observer, before any folder/result/audio/request publication. */
    fun bindReservedRequest(id:String,job:kr.callagent.drive.LocalJobStore.Job,actor:JSONObject)=port.transaction {
        val r=request(id);check(r.getString("state")=="SUBMITTING") { "REQUEST_NOT_CLAIMED" };assertRequestCurrent(id)
        val binding=JSONObject(r.getString("binding_json"));val frozenActor=binding.optJSONObject("actor") ?: error("REQUEST_ACTOR_UNBOUND")
        check(objectHash(frozenActor.toString())==objectHash(actor.toString())) { "REQUEST_ACTOR_CHANGED" }
        val wire=JSONObject(String(job.requestBytes,Charsets.UTF_8));kr.callagent.drive.WireV0Codec.validateRequest(job.requestBytes)
        val native=JSONObject(wire.getString("contextJson"))
        check(job.source.sourceKey==r.getString("recording_id") && job.source.sourceVersion==binding.getLong("sourceVersion") && job.source.audioSha==binding.getString("audioSha")) { "RESERVED_SOURCE_MISMATCH" }
        for((saved,key) in listOf("boardRevision" to "baseRevision","userRevision" to "userRevision","lockRevision" to "lockRevision","visibilityRevision" to "archiveRevision"))
            check(binding.getLong(saved)==wire.getLong(key)) { "RESERVED_PREPARED_BINDING_MISMATCH" }
        check(native.getLong("nativeHeadRevision")==binding.getLong("headRevision")) { "RESERVED_HEAD_MISMATCH" }
        check(job.authorizationEpoch==actor.getLong("epoch") && objectHash(native.getJSONObject("authSubject").toString())==objectHash(actor.toString())) { "RESERVED_ACTOR_MISMATCH" }
        check(job.source.ownerBinding==(context as? AccountStorageContext)?.ownerId && objectHash(wire.getJSONObject("ownerBinding").toString())==objectHash(actor.getJSONObject("ownerBinding").toString())) { "RESERVED_OWNER_MISMATCH" }
        val stored=rows("SELECT frozen FROM drive_v0_jobs WHERE job_id=?",job.jobId).single()["frozen"] as ByteArray
        val envelope=JSONObject(String(stored,Charsets.UTF_8));check(envelope.getString("requestJson").toByteArray().contentEquals(job.requestBytes)) { "RESERVED_REQUEST_MISMATCH" }
        val capture=JSONObject().put("jobId",job.jobId).put("requestDigest",job.requestDigest).put("frozenJobSha",kr.callagent.drive.DriveRelay.sha256(stored))
            .put("requestSha",kr.callagent.drive.DriveRelay.sha256(job.requestBytes)).put("folderId",job.folderId).put("requestFileId",job.requestFileId).put("resultFileId",job.resultFileId).put("audioFileId",job.audioFileId)
            .put("preparedBindingDigest",r.getString("binding_digest")).put("actor",actor).put("leaseEpoch",job.source.leaseEpoch).put("contextSha",wire.getString("contextSha"))
        val old=rows("SELECT binding_json FROM workflow_analysis_bindings WHERE request_id=?",id).singleOrNull()
        if(old==null)port.execute("INSERT INTO workflow_analysis_bindings VALUES(?,?,?,?)",id,job.jobId,capture.toString(),clock())
        else check(objectHash(old["binding_json"].toString())==objectHash(capture.toString())) { "RESERVED_BINDING_IMMUTABLE" }
        port.execute("UPDATE workflow_analysis_requests SET job_id=? WHERE id=?",job.jobId,id);null
    }
    /** Never searches an active source to infer which job belonged to a failed attempt. */
    fun reconcileRemoteJob(id:String,actor:JSONObject?=null):String?=port.transaction {
        if(actor==null)return@transaction null
        val link=rows("SELECT * FROM workflow_analysis_bindings WHERE request_id=?",id).singleOrNull() ?: return@transaction null
        val captured=JSONObject(link["binding_json"].toString());val request=request(id)
        if(captured.getString("preparedBindingDigest")!=request.getString("binding_digest") || objectHash(captured.getJSONObject("actor").toString())!=objectHash(actor.toString()))return@transaction null
        val job=rows("SELECT * FROM drive_v0_jobs WHERE job_id=?",link["job_id"]).singleOrNull() ?: return@transaction null
        val bytes=job["frozen"] as ByteArray
        if(kr.callagent.drive.DriveRelay.sha256(bytes)!=captured.getString("frozenJobSha"))return@transaction null
        val envelope=JSONObject(String(bytes,Charsets.UTF_8));val wire=JSONObject(envelope.getString("requestJson"));kr.callagent.drive.WireV0Codec.validateRequest(envelope.getString("requestJson").toByteArray())
        if(envelope.getLong("authorizationEpoch")!=actor.getLong("epoch") || wire.getString("requestDigest")!=captured.getString("requestDigest") || wire.getString("jobId")!=link["job_id"] ||
            rows("SELECT job_id FROM drive_v0_active WHERE source_id=? AND job_id=?",request.getString("recording_id"),link["job_id"]).isEmpty() || job["phase"] !in listOf("RESERVED","PLACEHOLDER_READY","AUDIO_READY","SENT","CANDIDATE"))return@transaction null
        val jobId=link["job_id"].toString();if(!request.isNull("job_id")&&request.getString("job_id")!=jobId)return@transaction null
        port.execute("UPDATE workflow_analysis_requests SET job_id=? WHERE id=?",jobId,id);jobId
    }
    fun transitionRequest(id:String,state:String,job:String?=null,reason:String?=null)=port.transaction {
        require(state in listOf("PREPARED","HELD_AUTH","HELD_SOURCE","HELD_CAPABILITY","SUBMITTING","SENT","PENDING","REVIEW","ERROR","UNKNOWN","CANCELLED"))
        val old=rows("SELECT * FROM workflow_analysis_requests WHERE id=?",id).single()
        if(old["state"]=="CANCELLED")require(state=="CANCELLED") { "REQUEST_CANCELLED" }
        if(old["state"]=="SUBMITTING" && state=="SUBMITTING")error("REQUEST_IN_PROGRESS")
        if(state=="SUBMITTING") {
            val claim=rows("SELECT id FROM workflow_analysis_claims WHERE id=?",id).singleOrNull()
            if(claim==null)port.execute("INSERT INTO workflow_analysis_claims VALUES(?,?)",id,PROCESS_TOKEN)
            else port.execute("UPDATE workflow_analysis_claims SET process_token=? WHERE id=?",PROCESS_TOKEN,id)
        }
        port.execute("UPDATE workflow_analysis_requests SET state=?,job_id=coalesce(?,job_id),reason=? WHERE id=?",state,job,reason,id);null
    }
    fun assertRequestCurrent(id:String):String=port.transaction {
        val request=request(id);val recording=request.getString("recording_id");val frozen=JSONObject(request.getString("binding_json"));val r=record(recording)
        for((saved,current) in listOf("sourceVersion" to "source_version","userRevision" to "user_revision","lockRevision" to "lock_revision","headRevision" to "head_revision","visibilityRevision" to "visibility_revision","boardRevision" to "board_revision"))
            check(frozen.getLong(saved)==n(r,current)) { "REQUEST_BINDING_CHANGED" }
        check(frozen.getString("audioSha")==r["audio_sha256"] && n(r,"archived")==0L) { "REQUEST_SOURCE_UNAVAILABLE" };recording
    }
    override fun close()=port.close()
    companion object {
        private val PROCESS_TOKEN=UUID.randomUUID().toString()
        private val SCHEMA=listOf(
            "CREATE TABLE IF NOT EXISTS workflow_entities(id TEXT PRIMARY KEY,kind TEXT NOT NULL CHECK(kind IN ('PERSON','COMPANY','CASE')),label TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_links(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER NOT NULL,person_id TEXT,company_id TEXT,case_id TEXT NOT NULL,task_id TEXT NOT NULL,phone_normalized TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_items(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),case_id TEXT,kind TEXT NOT NULL CHECK(kind IN ('TODO','DOCUMENT')),title TEXT NOT NULL,edition TEXT NOT NULL,due_text TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,source_key TEXT,evidence_quote TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_item_history(event_id TEXT PRIMARY KEY,item_id TEXT NOT NULL,revision INTEGER NOT NULL,before_json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_sources(source_key TEXT PRIMARY KEY,kind TEXT NOT NULL,content_sha TEXT NOT NULL,raw_text TEXT NOT NULL,title TEXT NOT NULL,sent_at_text TEXT NOT NULL,origin TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_source_links(recording_id TEXT NOT NULL REFERENCES recordings(recording_id),source_key TEXT NOT NULL REFERENCES workflow_sources(source_key),relation TEXT NOT NULL,same_phone INTEGER NOT NULL,confirmed_ms INTEGER NOT NULL,PRIMARY KEY(recording_id,source_key))",
            "CREATE TABLE IF NOT EXISTS workflow_source_analysis_policy(recording_id TEXT NOT NULL REFERENCES recordings(recording_id),source_key TEXT NOT NULL REFERENCES workflow_sources(source_key),allowed INTEGER NOT NULL CHECK(allowed IN(0,1)),epoch INTEGER NOT NULL,PRIMARY KEY(recording_id,source_key))",
            "CREATE TABLE IF NOT EXISTS workflow_analysis_authority(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),epoch INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_naver_candidates(digest TEXT PRIMARY KEY,recording_id TEXT REFERENCES recordings(recording_id),account_key TEXT NOT NULL,scope_fingerprint TEXT NOT NULL,epoch INTEGER NOT NULL,subject TEXT NOT NULL,raw_text TEXT,payload_json TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_naver_links(recording_id TEXT NOT NULL REFERENCES recordings(recording_id),source_key TEXT NOT NULL REFERENCES workflow_sources(source_key),account_key TEXT NOT NULL,proposal_digest TEXT NOT NULL,connection_epoch INTEGER NOT NULL,PRIMARY KEY(recording_id,source_key))",
            "CREATE TABLE IF NOT EXISTS workflow_naver_epochs(account_key TEXT PRIMARY KEY,epoch INTEGER NOT NULL,run_tag TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_naver_revoked(account_key TEXT PRIMARY KEY,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_user_receipts(event_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),operation TEXT NOT NULL,request_json TEXT NOT NULL,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_reviewed_summaries(recording_id TEXT NOT NULL REFERENCES recordings(recording_id),revision INTEGER NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,text TEXT NOT NULL,receipt TEXT NOT NULL REFERENCES workflow_user_receipts(event_id),user_revision INTEGER NOT NULL,lock_revision INTEGER NOT NULL,head_revision INTEGER NOT NULL,board_revision INTEGER NOT NULL,proposal_digest TEXT,created_ms INTEGER NOT NULL,PRIMARY KEY(recording_id,revision))",
            "CREATE TABLE IF NOT EXISTS workflow_analysis_requests(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),binding_digest TEXT NOT NULL,binding_json TEXT NOT NULL,state TEXT NOT NULL,job_id TEXT,reason TEXT,created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_analysis_claims(id TEXT PRIMARY KEY REFERENCES workflow_analysis_requests(id),process_token TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS workflow_business_reviews(event_id TEXT PRIMARY KEY REFERENCES workflow_user_receipts(event_id),recording_id TEXT NOT NULL,action_hash TEXT NOT NULL UNIQUE,proposal_digest TEXT NOT NULL)")+
            listOf("CREATE TABLE IF NOT EXISTS workflow_analysis_bindings(request_id TEXT PRIMARY KEY REFERENCES workflow_analysis_requests(id),job_id TEXT NOT NULL,binding_json TEXT NOT NULL,created_ms INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS workflow_analysis_result_cursor(request_id TEXT PRIMARY KEY REFERENCES workflow_analysis_requests(id),job_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,result_digest TEXT NOT NULL,observed_ms INTEGER NOT NULL)",
                "CREATE TRIGGER IF NOT EXISTS workflow_business_reviews_no_replace BEFORE INSERT ON workflow_business_reviews WHEN EXISTS(SELECT 1 FROM workflow_business_reviews WHERE event_id=NEW.event_id OR action_hash=NEW.action_hash) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
                "CREATE TRIGGER IF NOT EXISTS workflow_analysis_bindings_no_replace BEFORE INSERT ON workflow_analysis_bindings WHEN EXISTS(SELECT 1 FROM workflow_analysis_bindings WHERE request_id=NEW.request_id) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END")+
            listOf("workflow_user_receipts","workflow_sources","workflow_reviewed_summaries","workflow_item_history","workflow_naver_candidates","workflow_analysis_bindings","workflow_business_reviews").flatMap { table ->
                listOf("CREATE TRIGGER IF NOT EXISTS ${table}_immutable_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END",
                    "CREATE TRIGGER IF NOT EXISTS ${table}_immutable_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY'); END")
            }
    }
}
