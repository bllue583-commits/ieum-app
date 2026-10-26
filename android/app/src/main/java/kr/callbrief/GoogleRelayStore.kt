package kr.callbrief

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kr.co.callsummary.core.android.ProtectedDatabase
import android.os.Looper
import kr.callagent.drive.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONObject

/** Actual relay/inbox, initially for an explicit app-generated WAV only. No native vault bypass. */
internal class GoogleRelayStore(context: Context, private val session: GoogleConnectionSession, transport: DriveTransport,
    private val nativeSources:GoogleNativeSources?=null,
    private val postingCapability:(String)->Boolean={FrozenDotCapability(context).supports(it)}) : AutoCloseable {
    data class JobView(val id: String, val phase: String, val folderId: String, val requestId: String, val resultId: String, val rawText: String?, val analysis: String?, val remoteState: String, val retryOf: String?, val sourceId:String?=null)
    data class ResultObservation(val jobId:String,val disposition:String,val kind:String?=null,val schemaVersion:Long?=null,val revision:Long?=null,val digest:String?=null)
    private val snapshot = session.view()
    private val owner = snapshot.ownerId
    private val epoch = snapshot.epoch
    private val accountContext = AccountStorageContext(context, owner)
    private val protectedStore:ProtectedDatabase
    private val db: SQLiteDatabase
    private val jobs: SqliteJobStore
    private val inbox: SqliteProfileInbox
    private val relay: DriveRelay
    private val drive = transport
    private var sourceId = "eum_generated_wav_v1"
    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "DRIVE_IO_THREAD_REQUIRED" }
        require(owner.isNotBlank()); session.commit(epoch, owner) { GeneralLocalStore.catalog(accountContext) }
        protectedStore=ProtectedDatabase.open(accountContext,GeneralLocalStore.DATABASE,null)
        db=protectedStore.database()
        db.setForeignKeyConstraintsEnabled(true)
        session.commit(epoch, owner) {
            ProtectedDatabase.transaction(db) {
                SqliteProfileInbox.installTables(db)
                nativeSources?.install(db)
                db.execSQL("CREATE TABLE IF NOT EXISTS eum_google_sources(source_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,audio_bytes INTEGER NOT NULL,consented INTEGER NOT NULL CHECK(consented IN(0,1)),source_uri TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS eum_google_versions(source_id TEXT NOT NULL,source_version INTEGER NOT NULL,owner_id TEXT NOT NULL,audio_sha TEXT NOT NULL,audio_bytes INTEGER NOT NULL,source_uri TEXT,PRIMARY KEY(source_id,source_version))")
                db.execSQL("CREATE TABLE IF NOT EXISTS eum_google_retries(original_job_id TEXT PRIMARY KEY,new_job_id TEXT NOT NULL UNIQUE,owner_id TEXT NOT NULL,source_sha TEXT NOT NULL,source_version INTEGER NOT NULL,failure_digest TEXT NOT NULL,created_epoch INTEGER NOT NULL)")
                for (table in listOf("drive_profiles_receipts", "drive_v0_receipts")) {
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS google_${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS google_${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END")
                }
                immutable("drive_profiles_receipts", "owner_id=NEW.owner_id AND job_id=NEW.job_id AND kind=NEW.kind AND schema_version=NEW.schema_version AND result_revision=NEW.result_revision")
                immutable("drive_v0_receipts", "owner_id=NEW.owner_id AND job_id=NEW.job_id AND result_revision=NEW.result_revision")
                immutable("eum_google_versions", "source_id=NEW.source_id AND source_version=NEW.source_version")
                immutable("eum_google_retries", "original_job_id=NEW.original_job_id OR new_job_id=NEW.new_job_id")
            }
        }
        val reader = SqliteJobStore.SourceReader { same, key ->
            if(nativeSources!=null) return@SourceReader nativeSources.source(same,key)
            if (session.view().ownerId != owner || session.state() != AuthorizationPort.State.ACTIVE) null
            else same.query("eum_google_sources", null, "source_id=? AND owner_id=?", arrayOf(key, owner), null, null, null).use { c ->
                if (!c.moveToFirst() || c.getInt(c.getColumnIndexOrThrow("consented")) != 1) null
                else LocalJobStore.Source(key, c.getString(c.getColumnIndexOrThrow("audio_sha")), c.getLong(c.getColumnIndexOrThrow("source_version")), c.getLong(c.getColumnIndexOrThrow("audio_bytes")),
                    0,0,0,0,0,1,owner,session.driveAccount(),owner,"{\"synthetic\":true,\"nativeBinding\":\"UNVERIFIED\"}",1,true,false,false)
            }
        }
        jobs = SqliteJobStore(db, reader)
        inbox = SqliteProfileInbox(db, reader, { _, _ -> SqliteProfileInbox.Evidence.PENDING }, { session.requireEpoch(epoch) })
        val retry = RetryPolicy({ millis -> Thread.sleep(millis) }, { bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound) }, 2)
        val fencedJobs=object:LocalJobStore {
            override fun <T> transaction(work:LocalJobStore.Work<T>):T = session.commit(epoch,owner) { jobs.transaction(work) }
        }
        relay = DriveRelay(session, drive, fencedJobs, WireV0Codec(), retry, { _, _, _ -> false })
    }
    private fun requirePostingCapability() { session.requireEpoch(epoch);check(postingCapability(session.driveAccount())) { "DOT_PROFILE_ACCOUNT_UNSUPPORTED" } }
    private fun immutable(table: String, duplicate: String) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS google_${table}_no_replace BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE $duplicate) BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END")
        for(action in listOf("UPDATE","DELETE")) db.execSQL("CREATE TRIGGER IF NOT EXISTS google_${table}_deny_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END")
    }
    fun submitSynthetic(): DriveRelay.Reply {
        requirePostingCapability()
        sourceId="eum_generated_wav_v1"
        val wav = syntheticWav()
        return submit(wav, null)
    }
    fun submitNative(recording:String,onReserved:(LocalJobStore.Job)->Unit={}):DriveRelay.Reply {
        requirePostingCapability()
        val source=checkNotNull(nativeSources) { "NATIVE_SOURCE_PORT_REQUIRED" }.enroll(recording)
        return relay.submit(recording,"eum_"+UUID.randomUUID().toString().replace("-",""),{ _,maximum,check -> nativeSources.read(source,maximum,check) },onReserved)
    }
    fun resumeNative(jobId:String,onReserved:(LocalJobStore.Job)->Unit):DriveRelay.Reply {
        requirePostingCapability()
        val job=load(jobId) ?: error("UNKNOWN_JOB")
        val sources=checkNotNull(nativeSources) { "NATIVE_SOURCE_PORT_REQUIRED" }
        // Do not enroll/rotate a lease or create a successor. Relay verifies this frozen source.
        sources.preflight(job.source.sourceKey)
        return relay.resume(jobId,{ _,maximum,check->sources.read(job.source,maximum,check) },onReserved)
    }
    fun nativeRegistration(jobId:String):JSONObject {
        val job=load(jobId) ?: error("UNKNOWN_JOB")
        return checkNotNull(nativeSources).registration(job)
    }
    fun submitSelectedSynthetic(uri: android.net.Uri, explicitlySynthetic: Boolean): DriveRelay.Reply {
        requirePostingCapability()
        require(explicitlySynthetic) { "SYNTHETIC_SCOPE_CONSENT_REQUIRED" }
        val wav = readSelected(uri)
        sourceId="external_synthetic_"+GoogleConnectionSession.scopeKey(uri.toString()).take(32)
        return submit(wav, uri.toString())
    }
    private fun readSelected(uri: android.net.Uri): ByteArray {
        require(uri.scheme == "content") { "SELECTED_DOCUMENT_REQUIRED" }
        return accountContext.contentResolver.openInputStream(uri)?.use { input ->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { session.requireEpoch(epoch);val n=input.read(buffer);if(n<0)break;require(output.size()+n<=DriveRelay.MAX_AUDIO_BYTES);output.write(buffer,0,n) }
            output.toByteArray()
        } ?: error("SELECTED_AUDIO_UNAVAILABLE")
    }
    private fun submit(wav: ByteArray, uri: String?): DriveRelay.Reply {
        require(wav.size in 44..DriveRelay.MAX_AUDIO_BYTES && String(wav,0,4)=="RIFF" && String(wav,8,4)=="WAVE") { "SYNTHETIC_WAV_REQUIRED" }
        session.commit(epoch, owner) { jobs.transaction {
            val hash=DriveRelay.sha256(wav)
            val prior=db.query("eum_google_sources",arrayOf("source_version","audio_sha"),"source_id=? AND owner_id=?",arrayOf(sourceId,owner),null,null,null).use { c -> if(c.moveToFirst()) c.getLong(0) to c.getString(1) else null }
            val version=if(prior==null)1L else if(prior.second==hash)prior.first else prior.first+1
            val value = ContentValues().apply { put("source_id",sourceId);put("owner_id",owner);put("source_version",version);put("audio_sha",hash);put("audio_bytes",wav.size);put("consented",1);put("source_uri",uri) }
            if(db.update("eum_google_sources",value,"source_id=? AND owner_id=?",arrayOf(sourceId,owner))==0)db.insertOrThrow("eum_google_sources",null,value)
            if(prior==null || prior.first!=version) db.insertOrThrow("eum_google_versions",null,ContentValues(value).apply { remove("consented") })
        } }
        try { return relay.submit(sourceId,"eum_"+UUID.randomUUID().toString().replace("-","")) { _, _, check -> check.run(); session.requireEpoch(epoch); wav.clone() } }
        finally { wav.fill(0) }
    }
    private fun load(id: String) = session.commit(epoch,owner) { jobs.transaction { it.job(id) } }
    private fun latestFailure(job: LocalJobStore.Job): Pair<String,String>? = db.query("drive_profiles_receipts", arrayOf("payload","digest"), "owner_id=? AND job_id=?",arrayOf(owner,job.jobId),null,null,"result_revision DESC","1").use { c ->
        if(!c.moveToFirst()) null else {
            val value=StrictJson.`object`(StrictJson.parse(c.getBlob(0)))
            val state=value["state"] as? String ?: ""
            if(state in listOf("ERROR","CANCELLED")) state to c.getString(1) else null
        }
    }
    /** Explicit retry preserves the failure and records one durable successor before remote I/O. */
    fun retryFailed(originalId: String,onReserved:(LocalJobStore.Job)->Unit={}): DriveRelay.Reply {
        requirePostingCapability()
        val original=load(originalId) ?: error("UNKNOWN_JOB")
        val uri=if(nativeSources!=null)null else session.commit(epoch,owner) {
            db.query("eum_google_sources",arrayOf("source_uri"),"source_id=? AND owner_id=?",arrayOf(original.source.sourceKey,owner),null,null,null).use { c -> check(c.moveToFirst());if(c.isNull(0)) null else c.getString(0) }
        }
        val wav=if(nativeSources!=null) nativeSources.read(original.source,DriveRelay.MAX_AUDIO_BYTES,{ session.requireEpoch(epoch) }) else if(uri==null)syntheticWav() else readSelected(android.net.Uri.parse(uri))
        try {
            check(DriveRelay.sha256(wav)==original.source.audioSha && wav.size.toLong()==original.source.audioBytes) { "RETRY_SOURCE_CHANGED" }
            val next=session.commit(epoch,owner) { jobs.transaction { tx ->
                val old=tx.job(originalId) ?: error("UNKNOWN_JOB")
                check(old.source.ownerBinding==owner && old.source.sameIdentity(tx.source(old.source.sourceKey))) { "RETRY_SOURCE_CHANGED" }
                val failure=latestFailure(old) ?: error("EXPLICIT_FAILURE_REQUIRED")
                val known=db.query("eum_google_retries",arrayOf("new_job_id","created_epoch"),"original_job_id=? AND owner_id=?",arrayOf(originalId,owner),null,null,null).use { c -> if(c.moveToFirst()) c.getString(0) to c.getLong(1) else null }
                if(known!=null) { check(known.second==epoch) { "RETRY_ACCOUNT_SESSION_CHANGED" };return@transaction known.first }
                check(!old.terminal() && tx.activeJobId(old.source.sourceKey)==old.jobId) { "RETRY_NO_LONGER_CURRENT" }
                val id="eum_"+UUID.randomUUID().toString().replace("-","")
                db.insertOrThrow("eum_google_retries",null,ContentValues().apply { put("original_job_id",old.jobId);put("new_job_id",id);put("owner_id",owner);put("source_sha",old.source.audioSha);put("source_version",old.source.sourceVersion);put("failure_digest",failure.second);put("created_epoch",epoch) })
                tx.putJob(old.phase(LocalJobStore.Phase.CANCELLED)); id
            } }
            return relay.submit(original.source.sourceKey,next,{ _,_,check -> check.run();session.requireEpoch(epoch);wav.clone() },onReserved)
        } finally { wav.fill(0) }
    }
    fun pollReadOnly(id:String)=pollObservation(id).disposition
    fun pollObservation(id: String): ResultObservation {
        val job = load(id) ?: return ResultObservation(id,"UNKNOWN_JOB")
        if(job.phase==LocalJobStore.Phase.CANCELLED)return ResultObservation(id,"CANCELLED")
        session.commit(epoch, owner) { check(job.source.ownerBinding == owner && job.authorizationEpoch == epoch); check(job.phase == LocalJobStore.Phase.SENT || job.phase == LocalJobStore.Phase.CANDIDATE) }
        checkRestoredDriveAState(id)
        checkRetrievalSource(job)
        val folder = drive.metadata(job.folderId); checkMeta(folder, job.folderId, null, DriveTransport.FOLDER_MIME)
        checkRetrievalSource(job)
        val before = drive.metadata(job.resultFileId); checkMeta(before, job.resultFileId, job.folderId, DriveTransport.JSON_MIME)
        require(before.size in 0..524_288L)
        checkRetrievalSource(job)
        val bytes = drive.download(job.resultFileId, 524_288).bytes
        val after = drive.metadata(job.resultFileId); checkMeta(after, job.resultFileId, job.folderId, DriveTransport.JSON_MIME)
        check(before.version == after.version && before.size == after.size && after.size == bytes.size.toLong()) { "RESULT_CHANGED_DURING_READ" }
        return session.commit(epoch, owner) {
            checkRetrievalSource(job)
            ProtectedDatabase.transaction(db) {
            checkRestoredDriveAState(id)
            val reply=inbox.receive(id,job.resultFileId,bytes)
            nativeSources?.attachProposal(job,reply)
            // Receipt deduplication is not evidence that a proposal exists. Decode the exact
            // validated receipt, including a repeated revision0 NOT_RUN placeholder.
            val received=reply.receipt?.let { ProfileResultDispatcher().validate(it.bytes,job) }
            val disposition=if(reply.status in listOf(SqliteProfileInbox.Status.HISTORY_ONLY,SqliteProfileInbox.Status.REVISION_CONFLICT))reply.status.name else when(received?.state) {
                "PENDING","ERROR","CANCELLED" -> received.state
                "PROPOSAL" -> if(reply.status==SqliteProfileInbox.Status.DUPLICATE)"DUPLICATE_PROPOSAL" else reply.status.name
                else -> reply.status.name
            }
            ResultObservation(id,disposition,received?.kind,received?.schemaVersion,received?.revision,received?.digest)
            }
        }
    }
    /** Worker endpoint proposal; no assertion that Drive result bytes were written/read. */
    fun receiveAutomationProposal(id:String,bytes:ByteArray,expectedBusinessContextSha:String):ResultObservation {
        val job=load(id) ?: return ResultObservation(id,"UNKNOWN_JOB")
        if(job.phase==LocalJobStore.Phase.CANCELLED)return ResultObservation(id,"CANCELLED")
        session.commit(epoch,owner) { check(job.source.ownerBinding==owner && job.authorizationEpoch==epoch);check(job.phase==LocalJobStore.Phase.SENT || job.phase==LocalJobStore.Phase.CANDIDATE) }
        checkRestoredDriveAState(id);checkRetrievalSource(job)
        val decoded=ProfileResultDispatcher().validate(bytes,job)
        check(decoded.api!=null && decoded.api.businessContextSha==expectedBusinessContextSha) { "AUTOMATION_CONTEXT_BINDING_MISMATCH" }
        return session.commit(epoch,owner) {
            ProtectedDatabase.transaction(db) {
                checkRestoredDriveAState(id);checkRetrievalSource(job)
                val reply=inbox.receive(id,job.resultFileId,bytes)
                nativeSources?.attachProposal(job,reply)
                val received=reply.receipt?.let { ProfileResultDispatcher().validate(it.bytes,job) }
                val disposition=if(reply.status==SqliteProfileInbox.Status.DUPLICATE && received?.proposal()==true)"DUPLICATE_PROPOSAL" else reply.status.name
                ResultObservation(id,disposition,received?.kind,received?.schemaVersion,received?.revision,received?.digest)
            }
        }
    }
    private fun checkRetrievalSource(job:LocalJobStore.Job,verifyAudio:Boolean=true) = session.commit(epoch,owner) { if(verifyAudio)nativeSources?.preflight(job.source.sourceKey) else nativeSources?.metadataPreflight(job.source.sourceKey);jobs.transaction { tx ->
        val current=tx.job(job.jobId) ?: error("UNKNOWN_JOB")
        check(!current.terminal() && tx.activeJobId(current.source.sourceKey)==current.jobId && current.source.sameIdentity(tx.source(current.source.sourceKey))) { "RESULT_SOURCE_LEASE_BLOCKED" }
    } }
    private fun checkMeta(m: DriveTransport.Metadata, id: String, parent: String?, mime: String) {
        session.requireEpoch(epoch)
        check(!m.version.isNullOrBlank() && m.version!="null" && m.id == id && (parent==null || m.parentId == parent) && m.mimeType == mime && m.ownerBinding == owner && m.isAppAuthorized && m.privateToOwner && !m.trashed) { "DRIVE_FILE_BINDING_MISMATCH" }
    }
    /** SQLite getLong() truncates REAL values; local restored state must retain
     * the exact integer token/domain instead of normalizing a corrupted cursor. */
    private fun checkDriveAStoredCursor(job:LocalJobStore.Job) {
        db.rawQuery("SELECT result_revision,result_digest FROM drive_v0_jobs WHERE job_id=?",arrayOf(job.jobId)).use { c ->
            check(c.moveToFirst() && c.getType(0)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getType(1)==android.database.Cursor.FIELD_TYPE_STRING &&
                c.getLong(0) in -1..StrictJson.MAX_INTEGER && c.getLong(0)==job.resultRevision && c.getString(1)==job.acceptedResultDigest &&
                (job.resultRevision<0 && job.acceptedResultDigest.isEmpty() || job.resultRevision>=0 && job.acceptedResultDigest.matches(Regex("[0-9a-f]{64}")))) { "DRIVE_STORE_REVIEW_REQUIRED" }
        }
    }
    /** Explicit Drive-A local snapshot only. No network, ID reservation, raw read,
     * consent, operator callback or remote writer/CAS inference. */
    fun driveAExport(jobId:String):DriveAExportSnapshot = session.commit(epoch,owner) {
        requirePostingCapability();val job=load(jobId) ?: error("UNKNOWN_JOB")
        check(job.phase in listOf(LocalJobStore.Phase.SENT,LocalJobStore.Phase.CANDIDATE)) { "REQUEST_NOT_COMMITTED" }
        checkRetrievalSource(job,false)
        ProtectedDatabase.transaction(db) {
            restoredDriveAReceipt(job)
            val registration=checkNotNull(nativeSources).registration(job,true)
            DriveAExportSnapshot(job.jobId,job.folderId,job.requestBytes.clone(),registration.toString())
        }
    }
    fun driveAFrozenRequest(jobId:String):ByteArray = session.commit(epoch,owner) {
        val job=load(jobId) ?: error("UNKNOWN_JOB");checkRetrievalSource(job,false);ProtectedDatabase.transaction(db){restoredDriveAReceipt(job);job.requestBytes.clone()}
    }
    private fun checkRestoredDriveAState(jobId:String) = session.commit(epoch,owner) {
        ProtectedDatabase.transaction(db) {
            val current=load(jobId) ?: error("UNKNOWN_JOB")
            checkRetrievalSource(current,false)
            restoredDriveAReceipt(current)
        }
    }
    fun driveAReopenedReceipt(jobId:String):DriveAReadOnlyReceipt?=checkRestoredDriveAState(jobId)
    private fun restoredDriveAReceipt(job:LocalJobStore.Job):DriveAReadOnlyReceipt? {
        check(db.inTransaction()) { "DRIVE_RESTORE_ATOMIC_TRANSACTION_REQUIRED" }
        checkDriveAStoredCursor(job)
        return db.rawQuery("SELECT kind,schema_version,result_revision,digest FROM drive_profiles_heads WHERE owner_id=? AND result_id=? AND job_id=?",arrayOf(owner,job.resultFileId,job.jobId)).use { head ->
                if(!head.moveToFirst()) { check(job.resultRevision<0 && job.acceptedResultDigest.isEmpty()) { "DRIVE_STORE_REVIEW_REQUIRED" };return null }
                check(head.getType(0)==android.database.Cursor.FIELD_TYPE_STRING && head.getType(1)==android.database.Cursor.FIELD_TYPE_INTEGER && head.getType(2)==android.database.Cursor.FIELD_TYPE_INTEGER && head.getType(3)==android.database.Cursor.FIELD_TYPE_STRING) { "DRIVE_STORE_REVIEW_REQUIRED" }
                val kind=head.getString(0);val version=head.getLong(1);val revision=head.getLong(2);val digest=head.getString(3)
                check(ProfileResultDispatcher().supports(kind,version) && revision in 0..StrictJson.MAX_INTEGER && digest.matches(Regex("[0-9a-f]{64}"))) { "DRIVE_STORE_REVIEW_REQUIRED" }
                check(job.resultRevision==revision && job.acceptedResultDigest==digest) { "DRIVE_STORE_REVIEW_REQUIRED" }
                db.rawQuery("SELECT payload,result_id,digest,conflict,hidden FROM drive_profiles_receipts WHERE owner_id=? AND job_id=? AND kind=? AND schema_version=? AND result_revision=?",arrayOf(owner,job.jobId,kind,version.toString(),revision.toString())).use { receipt ->
                    check(receipt.moveToFirst()) { "DRIVE_STORE_REVIEW_REQUIRED" }
                    val bytes=receipt.getBlob(0);val decoded=try {ProfileResultDispatcher().validate(bytes,job)}catch(e:IllegalArgumentException){throw IllegalStateException("DRIVE_STORE_REVIEW_REQUIRED",e)}
                    check(receipt.getString(1)==job.resultFileId && receipt.getString(2)==digest && decoded.kind==kind && decoded.schemaVersion==version && decoded.revision==revision && decoded.digest==digest) { "DRIVE_STORE_REVIEW_REQUIRED" }
                    check(receipt.getType(3)==android.database.Cursor.FIELD_TYPE_INTEGER && receipt.getType(4)==android.database.Cursor.FIELD_TYPE_INTEGER && receipt.getInt(3) in 0..1 && receipt.getInt(4) in 0..1) { "DRIVE_STORE_REVIEW_REQUIRED" }
                    val now=jobs.transaction { tx ->
                        val current=tx.source(job.source.sourceKey)
                        check(job.source.sameIdentity(current)) { "RESULT_SOURCE_LEASE_BLOCKED" };checkNotNull(current)
                    }
                    val view=inbox.visible(job.jobId)
                    val conflict=receipt.getInt(3)==1 || job.source.manualConflict(now) || view?.conflict==true
                    val hidden=receipt.getInt(4)==1 || now.archived || now.archiveRevision!=job.source.archiveRevision || view?.hidden==true || decoded.proposal()&&view==null
                    DriveAReadOnlyReceipt(job.jobId,job.resultFileId,kind,version,revision,digest,DriveRelay.sha256(bytes),decoded.state,if(hidden)null else view?.rawText,if(hidden)null else view?.rawAnalysisJson,conflict,hidden)
                }
            }
    }
    fun list(): List<JobView> = session.commit(epoch, owner) {
        val ids = mutableListOf<String>()
        db.query("drive_v0_jobs",arrayOf("job_id"),null,null,null,null,"rowid").use { c -> while(c.moveToNext())ids.add(c.getString(0)) }
        ids.mapNotNull { id -> val j=load(id) ?: return@mapNotNull null; val v=inbox.visible(id)
            val retryOf=db.query("eum_google_retries",arrayOf("original_job_id"),"new_job_id=?",arrayOf(id),null,null,null).use { c -> if(c.moveToFirst())c.getString(0) else null }
            JobView(id,j.phase.name,j.folderId,j.requestFileId,j.resultFileId,v?.rawText,v?.rawAnalysisJson,latestFailure(j)?.first ?: "",retryOf,j.source.sourceKey) }
    }
    override fun close() { protectedStore.close() }
    companion object {
        fun syntheticWav(): ByteArray = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(44);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16);put("data".toByteArray());putInt(8);putLong(0)
        }.array()
    }
}
