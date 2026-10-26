package kr.callbrief

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.callagent.drive.*
import org.json.JSONObject
import java.util.UUID

/** Native encrypted source + durable read-only lease. No person/task/time is invented. */
internal class GoogleNativeSources(private val context:Context,private val session:GoogleConnectionSession,
    private val settings:SecureSettings,private val native:GeneralLocalStore,
    private val selected:()->SelectedRecordingSource,private val now:()->Long=System::currentTimeMillis) : AutoCloseable {
    private val owner=session.ownerBinding();private val epoch=session.epoch()
    private val sid=session.nativeSessionBinding().getString("sessionId")
    private lateinit var db:SQLiteDatabase
    private var activeVaultRead:LocalJobStore.Source?=null
    private val cancel:()->Unit={ native.cancel() }
    init {
        check(context is AccountStorageContext && context.ownerId==owner) { "NATIVE_ACCOUNT_STORAGE_MISMATCH" }
        session.registerCancellation(epoch,cancel)
    }
    fun install(same:SQLiteDatabase) {
        db=same
        db.execSQL("CREATE TABLE IF NOT EXISTS google_native_leases(recording_id TEXT NOT NULL,lease_epoch INTEGER NOT NULL,owner_id TEXT NOT NULL,auth_epoch INTEGER NOT NULL,session_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,audio_bytes INTEGER NOT NULL,policy_sha TEXT NOT NULL,lease_id TEXT NOT NULL UNIQUE,expires_ms INTEGER NOT NULL,synthetic_creator_confirmed INTEGER NOT NULL CHECK(synthetic_creator_confirmed=1),active INTEGER NOT NULL CHECK(active IN(0,1)),PRIMARY KEY(recording_id,lease_epoch))")
        db.execSQL("CREATE TABLE IF NOT EXISTS google_native_heads(recording_id TEXT PRIMARY KEY,lease_epoch INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS google_native_policy_epochs(policy_sha TEXT PRIMARY KEY,epoch INTEGER NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS google_native_proposals(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,job_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,digest TEXT NOT NULL,summary_text TEXT,raw_text TEXT,analysis_json TEXT,base_user_revision INTEGER NOT NULL,base_lock_revision INTEGER NOT NULL,conflict INTEGER NOT NULL,hidden INTEGER NOT NULL,PRIMARY KEY(job_id,kind,schema_version,result_revision))")
        db.execSQL("CREATE TABLE IF NOT EXISTS google_native_proposal_heads(recording_id TEXT PRIMARY KEY,source_version INTEGER NOT NULL,job_id TEXT NOT NULL,kind TEXT NOT NULL,schema_version INTEGER NOT NULL,result_revision INTEGER NOT NULL,digest TEXT NOT NULL)")
        for(action in listOf("UPDATE","DELETE"))db.execSQL("CREATE TRIGGER IF NOT EXISTS native_proposals_${action.lowercase()} BEFORE $action ON google_native_proposals BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS native_proposals_no_replace BEFORE INSERT ON google_native_proposals WHEN EXISTS(SELECT 1 FROM google_native_proposals WHERE job_id=NEW.job_id AND kind=NEW.kind AND schema_version=NEW.schema_version AND result_revision=NEW.result_revision) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END")
    }
    private fun record(same:SQLiteDatabase,key:String):JSONObject? {
        val query="""SELECT r.*,v.audio_sha256,v.byte_count AS version_bytes,l.root_uri,l.actual_document_id,l.actual_uri,l.source_signature,
            a.archived,a.revision AS archive_revision,g.active AS grant_active,g.selected_by_user,g.root_uri AS grant_root,
            b.revision AS board_revision,t.task_revision,t.case_id,q.plaintext_sha256,q.plaintext_bytes,q.key_alias,q.ciphertext_sha256,q.storage_bytes
            FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
            JOIN local_files l ON l.recording_id=r.recording_id JOIN local_visibility a ON a.recording_id=r.recording_id
            JOIN folder_grants g ON g.grant_id=r.grant_id JOIN board_state b ON b.owner_key=r.owner_key
            LEFT JOIN tasks t ON t.task_id=r.task_id JOIN vault2_refs q ON q.recording_id=r.recording_id AND q.source_version=r.source_version AND q.owner_key=r.owner_key
            WHERE r.recording_id=? AND r.owner_key=?""".trimIndent()
        return same.rawQuery(query,arrayOf(key,GeneralLocalStore.OWNER)).use { c ->
            if(!c.moveToFirst())null else JSONObject().apply { for(i in 0 until c.columnCount)put(c.getColumnName(i),when { c.isNull(i)->JSONObject.NULL;c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i);else->c.getString(i) }) }
        }
    }
    private fun doc(r:JSONObject)=SourceDocument(r.getString("actual_document_id"),r.getString("actual_uri"),r.getString("display_name"),r.getLong("byte_count"),r.getString("mime_type"),r.getLong("modified_at_ms"))
    private fun ref(r:JSONObject)=kr.co.callsummary.vault.VaultReference(kr.co.callsummary.vault.AudioBinding(GeneralLocalStore.OWNER,r.getString("recording_id"),r.getLong("source_version"),r.getString("plaintext_sha256"),r.getLong("plaintext_bytes")),r.getString("key_alias"),r.getString("ciphertext_sha256"),r.getLong("storage_bytes"))
    private fun available(r:JSONObject):Boolean {
        // Do not acquire a second vault lease while its fully verified reader owns the first.
        activeVaultRead?.let { if(it.sourceKey==r.getString("recording_id") && it.sourceVersion==r.getLong("source_version") && it.audioSha==r.getString("plaintext_sha256") && it.audioBytes==r.getLong("plaintext_bytes"))return true }
        return native.encrypted.readOnlyAvailability(ref(r))==kr.co.callsummary.vault.EncryptedAudioVault.Status.ENCRYPTED_REFERENCE_PRESENT
    }
    fun preflight(key:String)=session.commit(epoch,owner) {
        val r=record(db,key) ?: error("NATIVE_SOURCE_NOT_REGISTERED")
        check(allowed(r)) { "NATIVE_SOURCE_POLICY_HELD" }
        native.encrypted.verify(ref(r)) // Outside the relay transaction; complete AEAD+plaintext hash.
    }
    /** Metadata/ref availability only for preparing an existing handoff. Full
     * audio AEAD verification remains in the explicit relay retrieval path. */
    fun metadataPreflight(key:String)=session.commit(epoch,owner) {
        val r=record(db,key) ?: error("NATIVE_SOURCE_NOT_REGISTERED")
        check(allowed(r) && available(r)) { "NATIVE_SOURCE_POLICY_HELD" }
    }
    private fun policySha(recording:String):String {
        val s=settings.snapshot()
        val value=JSONObject().put("treeUri",s.optString("treeUri")).put("exclusions",s.optJSONArray("exclusions") ?: org.json.JSONArray())
            .put("personalPhoneDisabled",s.optBoolean("personalPhoneDisabled")).put("personalAliasDisabled",s.optBoolean("personalAliasDisabled"))
            .put("identities",s.optJSONObject("identities") ?: JSONObject()).put("aliasMappings",s.optJSONObject("aliasMappings") ?: JSONObject())
        if(db.rawQuery("SELECT name FROM sqlite_master WHERE name='workflow_analysis_authority'",null).use { it.moveToFirst() })
            db.rawQuery("SELECT epoch FROM workflow_analysis_authority WHERE recording_id=?",arrayOf(recording)).use { c ->if(c.moveToFirst()&&c.getLong(0)>0)value.put("relatedEvidenceRevocationEpoch",c.getLong(0)) }
        return DriveRelay.sha256(StrictJson.bytes(StrictJson.`object`(StrictJson.parse(value.toString().toByteArray()))))
    }
    private fun allowed(r:JSONObject):Boolean = try {
        session.requireEpoch(epoch);check(session.state()==AuthorizationPort.State.ACTIVE && session.ownerBinding()==owner)
        check(r.getInt("grant_active")==1 && r.getInt("selected_by_user")==1 && r.getString("grant_root")==r.getString("root_uri"))
        val source=selected();source.checkSelected();check(source.rootUri==r.getString("root_uri"));val d=doc(r)
        check(!source.excluded(d));check(source.stat(d)==d)
        val a=settings.snapshot().optJSONObject("nativeSyntheticApprovals")?.optJSONObject(SyntheticApprovedSource.approvalKey(source.rootUri,d))
        check(a!=null && a.optBoolean("syntheticCreatorConfirmed") && a.optString("ownerId")==owner && a.optLong("authEpoch",-1)==epoch)
        check(r.getString("audio_sha256")==r.getString("plaintext_sha256") && r.getLong("version_bytes")==r.getLong("plaintext_bytes"))
        check(r.getLong("plaintext_bytes") in 44..DriveRelay.MAX_AUDIO_BYTES.toLong() && r.getString("mime_type") in listOf("audio/wav","audio/x-wav"))
        true
    } catch(_:Exception) { false }
    private fun lease(same:SQLiteDatabase,key:String):JSONObject? = same.rawQuery("SELECT l.* FROM google_native_leases l JOIN google_native_heads h ON h.recording_id=l.recording_id AND h.lease_epoch=l.lease_epoch WHERE l.recording_id=?",arrayOf(key)).use { c ->
        if(!c.moveToFirst())null else JSONObject().apply { for(i in 0 until c.columnCount)put(c.getColumnName(i),if(c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER)c.getLong(i) as Any else c.getString(i) as Any) }
    }
    fun enroll(recording:String):LocalJobStore.Source = session.commit(epoch,owner) {
        preflight(recording)
        ProtectedDatabase.transaction(db) {
            val r=record(db,recording) ?: error("NATIVE_SOURCE_NOT_REGISTERED")
            check(allowed(r) && r.getInt("archived")==0) { "NATIVE_SOURCE_APPROVAL_OR_GRANT_REQUIRED" }
            val sha=policySha(recording);val old=lease(db,recording)
            if(old==null || old.getString("owner_id")!=owner || old.getLong("auth_epoch")!=epoch || old.getString("session_id")!=sid ||
                old.getLong("source_version")!=r.getLong("source_version") || old.getString("audio_sha")!=r.getString("audio_sha256") || old.getString("policy_sha")!=sha || old.getLong("expires_ms")<=now() || old.getInt("active")!=1) {
                val next=db.rawQuery("SELECT COALESCE(MAX(lease_epoch),0)+1 FROM google_native_leases WHERE recording_id=?",arrayOf(recording)).use { c->c.moveToFirst();c.getLong(0) }
                if(db.rawQuery("SELECT epoch FROM google_native_policy_epochs WHERE policy_sha=?",arrayOf(sha)).use { !it.moveToFirst() }) {
                    val n=db.rawQuery("SELECT COALESCE(MAX(epoch),0)+1 FROM google_native_policy_epochs",null).use { c->c.moveToFirst();c.getLong(0) }
                    db.execSQL("INSERT INTO google_native_policy_epochs VALUES(?,?)",arrayOf(sha,n))
                }
                db.insertOrThrow("google_native_leases",null,ContentValues().apply {
                    put("recording_id",recording);put("lease_epoch",next);put("owner_id",owner);put("auth_epoch",epoch);put("session_id",sid)
                    put("source_version",r.getLong("source_version"));put("audio_sha",r.getString("audio_sha256"));put("audio_bytes",r.getLong("plaintext_bytes"));put("policy_sha",sha)
                    put("lease_id","native_lease_"+UUID.randomUUID().toString().replace("-",""));put("expires_ms",now()+30*60_000);put("synthetic_creator_confirmed",1);put("active",1)
                })
                val values=ContentValues().apply { put("recording_id",recording);put("lease_epoch",next) }
                if(db.update("google_native_heads",values,"recording_id=?",arrayOf(recording))==0)db.insertOrThrow("google_native_heads",null,values)
            }
            checkNotNull(source(db,recording))
        }
    }
    fun source(same:SQLiteDatabase,key:String):LocalJobStore.Source? {
        val r=record(same,key) ?: return null;val l=lease(same,key) ?: return null
        if(!allowed(r) || !available(r) || l.getInt("active")!=1 || l.getLong("expires_ms")<=now() || l.getString("owner_id")!=owner || l.getLong("auth_epoch")!=epoch || l.getString("session_id")!=sid ||
            l.getLong("source_version")!=r.getLong("source_version") || l.getString("audio_sha")!=r.getString("audio_sha256") || l.getString("policy_sha")!=policySha(key))return null
        val policy=same.rawQuery("SELECT epoch FROM google_native_policy_epochs WHERE policy_sha=?",arrayOf(l.getString("policy_sha"))).use { c->check(c.moveToFirst());c.getLong(0) }
        val context=JSONObject().put("synthetic",true).put("nativeBinding","UNVERIFIED").put("syntheticCreatorConfirmed",l.getInt("synthetic_creator_confirmed")==1)
            .put("authSubject",session.nativeSessionBinding()).put("leaseId",l.getString("lease_id")).put("leaseExpiresMs",l.getLong("expires_ms"))
            .put("originalStartedAtMs",r.opt("original_started_at_ms")).put("personId",r.opt("person_id")).put("companyId",r.opt("company_id")).put("taskId",r.opt("task_id")).put("caseId",r.opt("case_id"))
            .put("nativeHeadRevision",r.getLong("head_revision")).put("boardRevision",r.getLong("board_revision"))
        if(same.rawQuery("SELECT name FROM sqlite_master WHERE name='workflow_source_analysis_policy'",null).use { it.moveToFirst() }) {
            val related=org.json.JSONArray();var total=0
            same.rawQuery("SELECT s.source_key,s.kind,s.content_sha,s.raw_text,s.title,s.sent_at_text,p.epoch FROM workflow_sources s JOIN workflow_source_analysis_policy p ON p.source_key=s.source_key AND p.recording_id=? AND p.allowed=1 JOIN workflow_source_links link ON link.source_key=s.source_key AND link.recording_id=p.recording_id ORDER BY s.source_key",arrayOf(key)).use { c ->while(c.moveToNext()) {
                val raw=c.getString(3);check(DriveRelay.sha256(raw.toByteArray(Charsets.UTF_8))==c.getString(2)) { "RELATED_EVIDENCE_HASH_MISMATCH" }
                total+=raw.toByteArray(Charsets.UTF_8).size;check(total<=20000&&related.length()<8) { "ANALYSIS_CONTEXT_TOO_LARGE" }
                val value=JSONObject().put("sourceKey",c.getString(0)).put("kind",c.getString(1)).put("contentSha",c.getString(2)).put("rawText",raw).put("title",c.getString(4)).put("originalTimeText",c.getString(5)).put("consentEpoch",c.getLong(6)).put("dataIsInstruction",false).put("relation","USER_LINKED_REVIEW_REQUIRED")
                same.rawQuery("SELECT candidate.payload_json FROM workflow_naver_links link JOIN workflow_naver_candidates candidate ON candidate.digest=link.proposal_digest WHERE link.recording_id=? AND link.source_key=?",arrayOf(key,c.getString(0))).use { m->if(m.moveToFirst()) {
                    val mail=JSONObject(m.getString(0));value.put("mailOrigin",mail.getString("origin")).put("truncated",mail.getBoolean("truncated")).put("coverageIncomplete",mail.getBoolean("coverageIncomplete"))
                } }
                related.put(value)
            } }
            if(related.length()>0)context.put("relatedEvidence",related)
        }
        val canonical=String(StrictJson.bytes(StrictJson.`object`(StrictJson.parse(context.toString().toByteArray()))),Charsets.UTF_8)
        return LocalJobStore.Source(key,r.getString("audio_sha256"),r.getLong("source_version"),r.getLong("plaintext_bytes"),r.getLong("board_revision"),r.getLong("user_revision"),r.getLong("lock_revision"),r.optLong("task_revision",0),r.getLong("archive_revision"),l.getLong("lease_epoch"),owner,session.driveAccount(),session.driveOwnerPermissionId(),canonical,policy,l.getInt("synthetic_creator_confirmed")==1,false,r.getInt("archived")==1)
    }
    fun read(expected:LocalJobStore.Source,maximum:Int,check:RetryPolicy.Check):ByteArray {
        session.commit(epoch,owner) { check.run();check(expected.same(source(db,expected.sourceKey)) && !expected.archived) { "NATIVE_SOURCE_LEASE_STALE" } }
        val out=java.io.ByteArrayOutputStream()
        activeVaultRead=expected
        try { native.encrypted.withReadOnlySourceAudio(expected.sourceKey,expected.sourceVersion,{ audio ->
            session.commit(epoch,owner) { expected.same(source(db,expected.sourceKey)) && audio.plaintextSha256==expected.audioSha && audio.sourceVersion==expected.sourceVersion && !expected.archived }
        }) { input ->
            val buffer=ByteArray(8192)
            try { while(true) {
                val n=session.commit(epoch,owner) { ProtectedDatabase.transaction(db) {
                    check.run();check(expected.same(source(db,expected.sourceKey)) && !expected.archived) { "NATIVE_SOURCE_LEASE_STALE" }
                    input.read(buffer)
                } }
                if(n<0)break;require(out.size()+n<=maximum);out.write(buffer,0,n)
            } } finally { buffer.fill(0) }
        } } finally { activeVaultRead=null }
        return out.toByteArray().also { check(DriveRelay.sha256(it)==expected.audioSha && it.size.toLong()==expected.audioBytes) { "NATIVE_AUDIO_BINDING_MISMATCH" } }
    }
    fun registration(job:LocalJobStore.Job,requireFrozen:Boolean=false):JSONObject = session.commit(epoch,owner) {
        val s=checkNotNull(source(db,job.source.sourceKey));val current=checkNotNull(record(db,s.sourceKey))
        if(requireFrozen)check(s.same(job.source) && !s.archived && !s.excluded && s.participantConfirmed) { "NATIVE_EXPORT_BINDING_STALE" }
        val state=JSONObject().put("ownerBinding",JSONObject().put("ownerId",owner).put("driveAccount",session.driveAccount()))
            .put("sourceId",s.sourceKey).put("sourceVersion",s.sourceVersion).put("audioSha",s.audioSha).put("leaseEpoch",s.leaseEpoch).put("stateRevision",current.getLong("head_revision"))
            .put("counterpartConfirmed",false).put("syntheticAudioApproved",s.participantConfirmed).put("consentGranted",s.participantConfirmed).put("excluded",s.excluded).put("revoked",lease(db,s.sourceKey)!!.getInt("active")!=1).put("deleted",record(db,s.sourceKey)==null).put("archived",s.archived).put("audioAvailable",available(current))
            .put("contextJson",s.contextJson).put("baseRevision",s.baseRevision).put("userRevision",s.userRevision).put("lockRevision",s.lockRevision).put("taskRevision",s.taskRevision).put("archiveRevision",s.archiveRevision)
        JSONObject().put("state",state).put("files",JSONObject().put("audio",JSONObject().put("fileId",job.audioFileId)).put("request",JSONObject().put("fileId",job.requestFileId)).put("result",JSONObject().put("fileId",job.resultFileId)))
            .put("jobId",job.jobId).put("folderId",job.folderId).put("creatorModes",org.json.JSONArray().put("ANDROID_APP")).put("sessionBinding",session.nativeSessionBinding())
            .put("driveOwnerPermissionId",session.driveOwnerPermissionId()).put("sessionEvidence","LOCAL_AUTHORIZATIONCLIENT_AND_DRIVE_ABOUT_OWNER_VERIFIED")
            .put("nativeAppSessionAuthentication","GOOGLE_DRIVE_OWNER_VERIFIED_NOT_OIDC_SERVER_ATTESTED").put("requestDigest",job.requestDigest)
    }
    /** Same transaction as provider receipt; only a proposal pointer, never native effective state. */
    fun attachProposal(job:LocalJobStore.Job,reply:SqliteProfileInbox.Reply) {
        check(db.inTransaction()) { "NATIVE_PROPOSAL_ATOMIC_TRANSACTION_REQUIRED" }
        val receipt=reply.receipt ?: return
        if(reply.status !in listOf(SqliteProfileInbox.Status.READ_ONLY_CANDIDATE,SqliteProfileInbox.Status.PROVENANCE_PENDING,SqliteProfileInbox.Status.LEGACY_CANDIDATE,SqliteProfileInbox.Status.DUPLICATE,SqliteProfileInbox.Status.HISTORY_ONLY))return
        val decoded=ProfileResultDispatcher().validate(receipt.bytes,job)
        if(!decoded.proposal())return
        val current=checkNotNull(source(db,job.source.sourceKey));check(job.source.sameIdentity(current)) { "NATIVE_RESULT_SOURCE_STALE" }
        val envelope=JSONObject(String(receipt.bytes,Charsets.UTF_8))
        val raw=if(decoded.api!=null)decoded.api.rawText else if(decoded.readOnly!=null)decoded.readOnly.rawText else envelope.optJSONObject("stt")?.optString("text")?.takeIf { it.isNotBlank() && it!="null" }
        val analysis=if(decoded.api!=null)decoded.api.analysisJson else if(decoded.readOnly!=null)StrictJson.`object`(decoded.readOnly.envelope["readOnlyAnalysis"])["rawAnalysisJson"] as? String else if(WireV0Codec.candidateStructureValid(receipt.bytes,job.requestBytes))envelope.optJSONObject("high")?.optString("candidateJson")?.takeIf { it.isNotBlank() && it!="null" } else null
        val summary=analysis?.let { runCatching { JSONObject(it).optString("summary") }.getOrNull() }?.takeIf { it.isNotBlank() }
        val exists=db.rawQuery("SELECT digest FROM google_native_proposals WHERE job_id=? AND kind=? AND schema_version=? AND result_revision=?",arrayOf(job.jobId,receipt.kind,receipt.schemaVersion.toString(),receipt.revision.toString())).use { c->if(c.moveToFirst())c.getString(0) else null }
        if(exists==null)db.insertOrThrow("google_native_proposals",null,ContentValues().apply {
            put("recording_id",job.source.sourceKey);put("source_version",job.source.sourceVersion);put("audio_sha",job.source.audioSha);put("job_id",job.jobId);put("kind",receipt.kind);put("schema_version",receipt.schemaVersion);put("result_revision",receipt.revision);put("digest",receipt.digest)
            put("summary_text",summary);put("raw_text",raw);put("analysis_json",analysis);put("base_user_revision",job.source.userRevision);put("base_lock_revision",job.source.lockRevision);put("conflict",if(receipt.conflict)1 else 0);put("hidden",if(receipt.hidden)1 else 0)
        }) else check(exists==receipt.digest) { "NATIVE_PROPOSAL_DIGEST_CONFLICT" }
        val isHead=db.rawQuery("SELECT 1 FROM drive_profiles_heads WHERE owner_id=? AND result_id=? AND job_id=? AND kind=? AND schema_version=? AND result_revision=? AND digest=?",arrayOf(owner,job.resultFileId,job.jobId,receipt.kind,receipt.schemaVersion.toString(),receipt.revision.toString(),receipt.digest)).use { it.moveToFirst() }
        if(isHead) {
            val value=ContentValues().apply { put("recording_id",job.source.sourceKey);put("source_version",job.source.sourceVersion);put("job_id",job.jobId);put("kind",receipt.kind);put("schema_version",receipt.schemaVersion);put("result_revision",receipt.revision);put("digest",receipt.digest) }
            if(db.update("google_native_proposal_heads",value,"recording_id=?",arrayOf(job.source.sourceKey))==0)db.insertOrThrow("google_native_proposal_heads",null,value)
        }
    }
    override fun close() { session.unregisterCancellation(cancel) }
}
