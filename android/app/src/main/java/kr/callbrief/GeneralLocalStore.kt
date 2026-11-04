package kr.callbrief

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import kr.co.callsummary.core.*
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.JsonBridge
import kr.co.callsummary.vault.*
import kr.co.callsummary.vault.android.AndroidVaultFactory
import kr.co.callsummary.vault.android.AndroidVaultKeys
import java.io.File
import java.nio.file.Path
import java.util.UUID

/** The app's protected local store: encrypted recording vault plus the canonical call database. No outgoing bytes. */
class GeneralLocalStore(private val context: Context,
    factory: (Context)->VaultAccess = ::androidVault,
    private val now: ()->Long = { System.currentTimeMillis() },
    private val fault: (String)->Unit = {}
) : AutoCloseable {
    data class VaultAccess(val vault: EncryptedAudioVault,val root: Path,val provision: EncryptedAudioVault.KeyProvisioner)
    companion object {
        const val DATABASE="callbrief-local-v2.db"
        const val OWNER="local-device-v2"
        /** A transcript DONE this long ago no longer needs the encrypted original. */
        const val PRUNE_AFTER_MS=14L*24*60*60*1000
        /** Skip reasons that end a recording's way for good (#39): before 10/1 never changes; excluded is private and undoing
         * an exclusion counts from then on; no speech was decided from the audio; an older version has a newer one. */
        val FINAL_SKIPS=listOf("BEFORE_START","EXCLUDED","NO_SPEECH","NOT_CURRENT")
        const val ALIAS="callsummary.audio.v2.local_device_v2"
        private const val OVERLAY="""
CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),root_uri TEXT NOT NULL,actual_document_id TEXT NOT NULL,actual_uri TEXT NOT NULL,source_signature TEXT NOT NULL,plaintext_sha256 TEXT NOT NULL,filename_time_candidate_ms INTEGER,UNIQUE(root_uri,actual_document_id));
CREATE TABLE IF NOT EXISTS local_visibility(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1)),revision INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS local_visibility_history(event_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,archived INTEGER NOT NULL,revision INTEGER NOT NULL,at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS local_intakes(event_id TEXT PRIMARY KEY,root_uri TEXT NOT NULL,actual_document_id TEXT NOT NULL,source_signature TEXT NOT NULL,plaintext_sha256 TEXT NOT NULL,metadata_json TEXT NOT NULL,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)));
"""
        private fun androidVault(context: Context): VaultAccess {
            val keys=AndroidVaultKeys();val vault=AndroidVaultFactory.open(context,keys)
            return VaultAccess(vault,context.noBackupFilesDir.toPath().resolve("audio-vault-v2"),keys::createNewKey)
        }
        /** Once per loaded store state (r8-1): a later open of the same in-memory store skips it. */
        internal fun overlay(port: AndroidSqlPort) { if(port.needs(OVERLAY_STEP)) port.transaction { OVERLAY.split(';').filter { it.isNotBlank() }.forEach { port.execute(it) };port.markDone(OVERLAY_STEP) } }
        private const val OVERLAY_STEP="generalLocalStore.overlay"
        private const val DISCOVERY_STEP="generalLocalStore.discoveryTables"
        private const val WIDEN_STEP="generalLocalStore.vaultSizeLimit"
        private const val SCOPE_STEP="generalLocalStore.localScope"
        private fun id(prefix: String)=prefix+"_"+UUID.randomUUID().toString().replace("-","")
        private fun signature(d: SourceDocument)=JsonCanonical.digest(SqlCallRepository.map("document_id",d.documentId,"uri",d.uri,"name",d.name,"size",d.size,"mime",d.mime,"modified",d.modified))
    }
    private val port: AndroidSqlPort
    private val repository: SqlCallRepository
    private val token=Cancellation()
    val encrypted: EncryptedRecordingStore
    init {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "LOCAL_IO_THREAD_REQUIRED" }
        port=AndroidSqlPort(context,DATABASE,context.assets.open("call_core_schema.sql"));port.requireProtectedPersistence();overlay(port)
        repository=SqlCallRepository(port,OWNER)
        if(port.needs(SCOPE_STEP)) { repository.initializeLocalScope();port.markDone(SCOPE_STEP) }
        val privateContext=object:ContextWrapper(context) { override fun getNoBackupFilesDir()=File(super.getNoBackupFilesDir(),"local-recordings-v2").apply { mkdirs() } }
        try {
            val v=factory(privateContext)
            val ledger=context.assets.open("vault2_ledger.sql").bufferedReader().use { it.readText() }
            encrypted=EncryptedRecordingStore(port,repository,OWNER,v.vault,v.root,ALIAS,v.provision,token,
                ledger,now,fault) { recording ->
                val row=port.query("SELECT * FROM local_visibility WHERE recording_id=?",recording).singleOrNull()
                if((row?.get("archived") as? Number)?.toInt()==1) null else (row?.get("revision") as? Number)?.toLong() ?: 0L
            }
            // v2.6: 25 MB -> 60 MB in a store made before (only the CHECK number). A failure keeps 25 MB (audioByteLimit).
            if(port.needs(WIDEN_STEP)) runCatching { VaultSizeLimit.widen(port,ledger);port.markDone(WIDEN_STEP) }
        } catch(error: Exception) { port.close();throw error }
    }
    /** Largest recording this store's database accepts (discovery holds bigger ones as HELD_UNSUPPORTED). */
    internal fun audioByteLimit():Long = runCatching { VaultSizeLimit.current(port) }.getOrDefault(VaultSizeLimit.OLD_BYTES)
    fun setupState(): String=when {
        encrypted.initializedKeyReady()->"READY"
        encrypted.mayInitialize()->"EMPTY"
        encrypted.mayRecoverInitialization()->"PENDING"
        else->"REVIEW"
    }
    fun initializeEmpty()=encrypted.initializeNewEmptySyntheticStore()
    fun recoverInitialization()=encrypted.recoverEmptySyntheticInitialization()
    fun cancel()=token.cancel()
    private fun number(row:Map<String,Any?>,key:String)=(row[key] as Number).toLong()
    // Automatic discovery uses the SAME protected port and canonical receipt/source CAS.
    internal fun discoveryQueue(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection)=ProtectedDiscoveryQueue(port,selection)
    private fun discoveryTables() { if(port.needs(DISCOVERY_STEP)) port.transaction {
        port.requireProtectedPersistence()
        port.execute("CREATE TABLE IF NOT EXISTS discovery_documents(root TEXT NOT NULL,document_id TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL,mime TEXT NOT NULL,modified INTEGER NOT NULL,partial INTEGER NOT NULL,virtual INTEGER NOT NULL,PRIMARY KEY(root,document_id))")
        port.execute("CREATE TABLE IF NOT EXISTS discovery_intake_requests(operation_id TEXT PRIMARY KEY,owner_key TEXT NOT NULL,commit_payload TEXT NOT NULL,metadata_json TEXT NOT NULL,request_digest TEXT NOT NULL,root TEXT NOT NULL,document_id TEXT NOT NULL)")
        port.execute("CREATE TRIGGER IF NOT EXISTS discovery_request_update BEFORE UPDATE ON discovery_intake_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISCOVERY_REQUEST');END")
        port.execute("CREATE TRIGGER IF NOT EXISTS discovery_request_delete BEFORE DELETE ON discovery_intake_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISCOVERY_REQUEST');END")
        port.markDone(DISCOVERY_STEP) } }
    internal fun rememberDiscoveryDocuments(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection,documents:List<SourceDocument>)=port.transaction {
        discoveryTables()
        documents.forEach { d ->
            if(port.query("SELECT document_id FROM discovery_documents WHERE root=? AND document_id=?",selection.treeUri,d.documentId).isEmpty())
                port.execute("INSERT INTO discovery_documents VALUES(?,?,?,?,?,?,?,?,?)",selection.treeUri,d.documentId,d.uri,d.name,d.size,d.mime,d.modified,if(d.partial)1 else 0,if(d.virtual)1 else 0)
            else port.execute("UPDATE discovery_documents SET uri=?,name=?,size=?,mime=?,modified=?,partial=?,virtual=? WHERE root=? AND document_id=?",d.uri,d.name,d.size,d.mime,d.modified,if(d.partial)1 else 0,if(d.virtual)1 else 0,selection.treeUri,d.documentId)
        }
    }
    internal fun discoveryDocument(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection,m:kr.co.ieum.discovery.DiscoveryTypes.Metadata):SourceDocument {
        discoveryTables()
        val row=port.query("SELECT uri FROM discovery_documents WHERE root=? AND document_id=?",selection.treeUri,m.documentId).singleOrNull()
            ?: port.query("SELECT actual_uri AS uri FROM local_files WHERE root_uri=? AND actual_document_id=?",selection.treeUri,m.documentId).singleOrNull()
            ?: error("DISCOVERY_DOCUMENT_BINDING_REQUIRED")
        return SourceDocument(m.documentId,row["uri"].toString(),m.displayName,m.bytes ?: -1,m.mimeType,m.modifiedMs ?: -1,m.partial,m.virtualDocument)
    }
    internal fun discoveryDocumentById(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection,id:String,state:kr.co.ieum.discovery.DiscoveryStore.State):SourceDocument=
        discoveryDocument(selection,checkNotNull(state.rows[id]).metadata)
    internal fun discoverySeed(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection,m:kr.co.ieum.discovery.DiscoveryTypes.Metadata):kr.co.ieum.discovery.DiscoveryTypes.Seed?=port.transaction {
        val local=port.query("SELECT recording_id FROM local_files WHERE root_uri=? AND actual_document_id=?",selection.treeUri,m.documentId).singleOrNull() ?: return@transaction null
        val recording=local["recording_id"].toString()
        val current=repository.recording(recording)
        val versions=port.query("SELECT v.*,b.ciphertext_sha256,b.plaintext_bytes FROM source_versions v JOIN vault2_refs b ON b.recording_id=v.recording_id AND b.source_version=v.source_version WHERE v.recording_id=? AND b.owner_key=? ORDER BY v.source_version",recording,OWNER).map { row ->
            kr.co.ieum.discovery.DiscoveryTypes.Version(number(row,"source_version"),row["audio_sha256"].toString(),number(row,"plaintext_bytes"),"native-history-$recording-${row["source_version"]}","vault2:"+row["ciphertext_sha256"])
        }
        check(versions.lastOrNull()?.sourceVersion==number(current,"source_version")) { "NATIVE_BOOTSTRAP_REFERENCE_REQUIRED" }
        kr.co.ieum.discovery.DiscoveryTypes.Seed(recording,versions)
    }
    private fun discoveryPlan(c:kr.co.ieum.discovery.DiscoveryTypes.Commit):Map<String,Any?> = port.transaction {
        discoveryTables();check(c.selection.ownerId.startsWith(OWNER)) { "DISCOVERY_OWNER_MISMATCH" }
        val encoded=ProtectedDiscoveryQueue.encodeCommit(c)
        val existing=port.query("SELECT * FROM discovery_intake_requests WHERE operation_id=? AND owner_key=?",c.operationId,OWNER).singleOrNull()
        if(existing!=null){check(existing["commit_payload"]==encoded) { "IDEMPOTENCY_CONFLICT" };return@transaction existing}
        val d=discoveryDocument(c.selection,c.metadata)
        val mapped=port.query("SELECT recording_id FROM local_files WHERE root_uri=? AND actual_document_id=?",c.selection.treeUri,d.documentId).singleOrNull()
        check(mapped==null || mapped["recording_id"]==c.recordingId) { "NATIVE_RECORDING_BINDING_CONFLICT" }
        val old=port.query("SELECT r.*,v.audio_sha256 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version WHERE r.recording_id=? AND r.owner_key=?",c.recordingId,OWNER).singleOrNull()
        val expectedVersion=if(old==null)1L else number(old,"source_version")+if(old["audio_sha256"]==c.audioSha256)0 else 1
        check(expectedVersion==c.sourceVersion) { "NATIVE_SOURCE_VERSION_CONFLICT" }
        val grant="selected_"+JsonCanonical.digest(SqlCallRepository.map("root",c.selection.treeUri))
        repository.registerUserSelectedFolder(grant,c.selection.treeUri,1L)
        val p=SqlCallRepository.map("event_id",c.operationId,"recording_id",c.recordingId,"owner_key",OWNER,"grant_id",grant,"policy_revision",1L,
            "document_id",old?.get("document_id") ?: id("local_document"),"source_seq",(old?.get("source_seq") as? Number)?.toLong()?.plus(1) ?: 1L,
            "display_name",d.name,"mime_type",d.mime,"byte_count",c.bytes,"modified_at_ms",d.modified,
            "original_started_at_ms",old?.get("original_started_at_ms"),"phone_group_ref",old?.get("phone_group_ref"),
            "person_id",old?.get("person_id"),"company_id",old?.get("company_id"),"task_id",old?.get("task_id"))
        val request=JsonCanonical.digest(SqlCallRepository.map("metadata",p,"plaintext_sha256",c.audioSha256))
        port.execute("INSERT INTO discovery_intake_requests VALUES(?,?,?,?,?,?,?)",c.operationId,OWNER,encoded,JsonCanonical.encode(p),request,c.selection.treeUri,d.documentId)
        port.query("SELECT * FROM discovery_intake_requests WHERE operation_id=?",c.operationId).single()
    }
    internal fun recoverDiscoveryReceipt(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection,operation:String):kr.co.ieum.discovery.DiscoveryTypes.Receipt?=port.transaction {
        discoveryTables()
        val plan=port.query("SELECT * FROM discovery_intake_requests WHERE operation_id=? AND owner_key=? AND root=?",operation,OWNER,selection.treeUri).singleOrNull() ?: return@transaction null
        val c=ProtectedDiscoveryQueue.decodeCommit(plan["commit_payload"].toString());check(c.selection==selection) { "DISCOVERY_SCOPE_MISMATCH" }
        val intake=port.query("SELECT * FROM vault2_intake_receipts WHERE event_id=? AND owner_key=?",operation,OWNER).singleOrNull() ?: return@transaction null
        check(intake["request_digest"]==plan["request_digest"] && intake["recording_id"]==c.recordingId && number(intake,"source_version")==c.sourceVersion) { "NATIVE_RECEIPT_BINDING_CONFLICT" }
        val native=repository.receipt(operation) ?: error("NATIVE_RECEIPT_MISSING")
        check(number(native,"source_version")==c.sourceVersion) { "NATIVE_RECEIPT_BINDING_CONFLICT" }
        val ref=encrypted.reference(c.recordingId,c.sourceVersion)
        check(ref.source.plaintextSha256==c.audioSha256 && ref.source.plaintextBytes==c.bytes && ref.ciphertextSha256==intake["ciphertext_sha256"]) { "NATIVE_RECEIPT_BINDING_CONFLICT" }
        finalizeDiscoveryAlias(c,plan)
        kr.co.ieum.discovery.DiscoveryTypes.Receipt(c,ref.storageRef())
    }
    private fun finalizeDiscoveryAlias(c:kr.co.ieum.discovery.DiscoveryTypes.Commit,plan:Map<String,Any?>) {
        val current=repository.recording(c.recordingId)
        check(number(current,"source_version")==c.sourceVersion && current["audio_sha256"]==c.audioSha256) { "NATIVE_RECEIPT_SUPERSEDED" }
        val d=discoveryDocument(c.selection,c.metadata);val sig=signature(d)
        // The call time from the recorder's filename: automatic discovery is how every real recording arrives (review 11/4, bug 1).
        val candidate=kr.callbrief.core.RecordingNames.startedAt(d.name)?.toEpochMilli()
        if(port.query("SELECT recording_id FROM local_files WHERE recording_id=?",c.recordingId).isEmpty()) {
            port.execute("INSERT INTO local_files VALUES(?,?,?,?,?,?,?)",c.recordingId,c.selection.treeUri,d.documentId,d.uri,sig,c.audioSha256,candidate)
            port.execute("INSERT OR IGNORE INTO local_visibility(recording_id) VALUES(?)",c.recordingId)
        } else port.execute("UPDATE local_files SET actual_document_id=?,actual_uri=?,source_signature=?,plaintext_sha256=?,filename_time_candidate_ms=COALESCE(?,filename_time_candidate_ms) WHERE recording_id=?",d.documentId,d.uri,sig,c.audioSha256,candidate,c.recordingId)
    }
    internal fun collectDiscovery(c:kr.co.ieum.discovery.DiscoveryTypes.Commit,bytes:ByteArray,permit:()->Unit,hook:kr.co.ieum.discovery.DiscoveryTypes.FaultHook):kr.co.ieum.discovery.DiscoveryTypes.Receipt {
        permit();check(encrypted.initializedKeyReady()) { "LOCAL_STORE_NOT_READY" }
        recoverDiscoveryReceipt(c.selection,c.operationId)?.let{return it}
        val plan=discoveryPlan(c);val p=JsonBridge.parseObject(plan["metadata_json"].toString())
        val observation=FileCollector.Observation(p,c.selection.treeUri,true,true)
        val excluded={permit();false}
        val guarded={permit();object:java.io.ByteArrayInputStream(bytes){override fun read():Int{permit();return super.read()};override fun read(b:ByteArray,o:Int,n:Int):Int{permit();return super.read(b,o,n)}}}
        if(c.operationId in encrypted.pendingEvents()) {
            try { encrypted.recoverPending(c.operationId,c.selection.treeUri,true,true,excluded) }
            catch(e:VaultException){if(e.code!="ORPHAN_REFERENCE_NOT_FOUND")throw e;encrypted.collect(observation,c.audioSha256,excluded,guarded)}
        } else encrypted.collect(observation,c.audioSha256,excluded,guarded)
        hook.reached("AFTER_NATIVE_RECEIPT_BEFORE_DISCOVERY_ACK")
        permit();return checkNotNull(recoverDiscoveryReceipt(c.selection,c.operationId)) { "NATIVE_RECEIPT_MISSING" }
    }
    internal fun observeDiscoverySame(c:kr.co.ieum.discovery.DiscoveryTypes.Commit,permit:()->Unit,hook:kr.co.ieum.discovery.DiscoveryTypes.FaultHook):kr.co.ieum.discovery.DiscoveryTypes.Receipt {
        permit();recoverDiscoveryReceipt(c.selection,c.operationId)?.let{return it}
        val plan=discoveryPlan(c)
        port.transaction {
            permit();val current=repository.recording(c.recordingId)
            check(number(current,"source_version")==c.sourceVersion && current["audio_sha256"]==c.audioSha256) { "NATIVE_SOURCE_VERSION_CONFLICT" }
            val ref=encrypted.reference(c.recordingId,c.sourceVersion)
            check(ref.source.plaintextSha256==c.audioSha256 && ref.source.plaintextBytes==c.bytes) { "NATIVE_SOURCE_BINDING_CONFLICT" }
            val p=JsonBridge.parseObject(plan["metadata_json"].toString());val observed=repository.observe(p,c.audioSha256,now())
            check(observed["outcome"]=="SAME_CONTENT" && number(observed,"source_version")==c.sourceVersion) { "NATIVE_SOURCE_VERSION_CONFLICT" }
            port.execute("INSERT INTO vault2_intake_receipts VALUES(?,?,?,?,?,?)",c.operationId,OWNER,plan["request_digest"],c.recordingId,c.sourceVersion,ref.ciphertextSha256)
            finalizeDiscoveryAlias(c,plan)
        }
        hook.reached("AFTER_NATIVE_RECEIPT_BEFORE_DISCOVERY_ACK")
        return checkNotNull(recoverDiscoveryReceipt(c.selection,c.operationId))
    }

    /** Deletes the encrypted original of recordings whose transcript has been DONE for 14 days (review B21), and of the ones
     * skipped for good for 14 days ([FINAL_SKIPS], #39: before 10/1, excluded, no speech, an older version). Skips the user
     * can undo (archived, folder access removed) keep their blob. The reference rows are immutable and stay; `vault_pruned`
     * remembers which blobs are gone (a negative at_ms: deleting it failed, not retried), and the transcription line no
     * longer offers a pruned recording ([kr.callbrief.pipeline.CallTranscriptionStore]). Returns how many blobs were deleted. */
    fun pruneVault(nowMs:Long=now(),limit:Int=200):Int {
        token.check()
        if(port.query("SELECT name FROM sqlite_master WHERE type='table' AND name='call_transcription_jobs'").isEmpty()) return 0
        port.transaction { port.execute("CREATE TABLE IF NOT EXISTS vault_pruned(ciphertext_sha256 TEXT PRIMARY KEY,at_ms INTEGER NOT NULL)") }
        val due=port.query("""SELECT q.recording_id,q.source_version,q.ciphertext_sha256 FROM vault2_refs q
            JOIN call_transcription_jobs j ON j.recording_id=q.recording_id AND j.source_version=q.source_version AND j.audio_sha=q.plaintext_sha256
            WHERE q.owner_key=? AND (j.state='DONE' OR (j.state='SKIPPED' AND j.reason IN (${FINAL_SKIPS.joinToString(",") { "'$it'" }}))) AND j.updated_ms<=?
            AND NOT EXISTS(SELECT 1 FROM vault_pruned p WHERE p.ciphertext_sha256=q.ciphertext_sha256) LIMIT ?""",OWNER,nowMs-PRUNE_AFTER_MS,limit.toLong())
        var deleted=0
        for(row in due) {
            token.check()
            // One bad row (CORRUPT_STORAGE...) must not block the rest every day: it is marked failed (negative at_ms) and skipped from now on.
            val ok=runCatching { encrypted.deleteBlob(encrypted.reference(row["recording_id"].toString(),number(row,"source_version"))) }
            if(ok.getOrDefault(false)) deleted++
            port.transaction { port.execute("INSERT OR IGNORE INTO vault_pruned VALUES(?,?)",row["ciphertext_sha256"],if(ok.isSuccess) nowMs else -nowMs) }
        }
        return deleted
    }
    override fun close() { encrypted.close();port.close() }
}
