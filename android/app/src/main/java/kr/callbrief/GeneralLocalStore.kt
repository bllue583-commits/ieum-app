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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** The app's protected local store: encrypted recording vault plus the canonical call database. No outgoing bytes. */
class GeneralLocalStore(private val context: Context,
    factory: (Context)->VaultAccess = ::androidVault,
    private val now: ()->Long = { System.currentTimeMillis() },
    private val fault: (String)->Unit = {}
) : AutoCloseable {
    data class VaultAccess(val vault: EncryptedAudioVault,val root: Path,val provision: EncryptedAudioVault.KeyProvisioner)
    data class ImportReport(val newVersions: Int,val sameContent: Int,val excluded: Int,val held: Int,val incomplete: Boolean)
    companion object {
        const val DATABASE="callbrief-local-v2.db"
        const val OWNER="local-device-v2"
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
        internal fun overlay(port: AndroidSqlPort) = port.transaction { OVERLAY.split(';').filter { it.isNotBlank() }.forEach { port.execute(it) } }
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
        repository=SqlCallRepository(port,OWNER);repository.initializeLocalScope()
        val privateContext=object:ContextWrapper(context) { override fun getNoBackupFilesDir()=File(super.getNoBackupFilesDir(),"local-recordings-v2").apply { mkdirs() } }
        try {
            val v=factory(privateContext)
            encrypted=EncryptedRecordingStore(port,repository,OWNER,v.vault,v.root,ALIAS,v.provision,token,
                context.assets.open("vault2_ledger.sql").bufferedReader().use { it.readText() },now,fault) { recording ->
                val row=port.query("SELECT * FROM local_visibility WHERE recording_id=?",recording).singleOrNull()
                if((row?.get("archived") as? Number)?.toInt()==1) null else (row?.get("revision") as? Number)?.toLong() ?: 0L
            }
        } catch(error: Exception) { port.close();throw error }
    }
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
    private fun stable(source:SelectedRecordingSource,d:SourceDocument) {
        token.check();source.checkSelected()
        source.checkReadable(d)
        check(source.stat(d)==d) { "SOURCE_CHANGED_DURING_READ" }
    }
    private fun hash(source:SelectedRecordingSource,d:SourceDocument):String {
        stable(source,d);val digest=MessageDigest.getInstance("SHA-256");var count=0L
        guardedOpen(source,d).use { input -> val bytes=ByteArray(8192);while(true) {
            token.check();val n=input.read(bytes);if(n<0)break
            count+=n;check(count<=d.size) { "SOURCE_SIZE_CHANGED" };digest.update(bytes,0,n)
        } }
        check(count==d.size) { "SOURCE_SIZE_CHANGED" };stable(source,d)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun guardedOpen(source:SelectedRecordingSource,d:SourceDocument):java.io.InputStream {
        stable(source,d)
        return object:java.io.FilterInputStream(source.open(d)) {
            private fun permit() { token.check();source.checkSelected();source.checkReadable(d) }
            override fun read():Int { permit();return `in`.read() }
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int { permit();return `in`.read(bytes,offset,length) }
        }
    }
    fun importSelected(source:SelectedRecordingSource):ImportReport {
        check(encrypted.initializedKeyReady()) { "LOCAL_STORE_NOT_READY" };source.checkSelected();token.check()
        val grant="selected_"+JsonCanonical.digest(SqlCallRepository.map("root",source.rootUri))
        repository.registerUserSelectedFolder(grant,source.rootUri,1L)
        val inventory=source.inventory();check(inventory.documents.map { it.documentId }.toSet().size==inventory.documents.size) { "AMBIGUOUS_DOCUMENT_INVENTORY" }
        var added=0;var same=0;var excluded=0;var held=0
        for(d in inventory.documents) {
            token.check();source.checkSelected()
            if(source.excluded(d)) { excluded++;continue }
            if(!source.explicitlySyntheticScope) { held++;continue }
            if(d.size !in 1..AudioBinding.MAX_BYTES || d.mime !in setOf("audio/wav","audio/x-wav","audio/mpeg","audio/mp4","audio/aac","audio/3gpp","audio/ogg","audio/flac","application/ogg") ||
                d.modified<=0 || d.modified>now()-120000) { held++;continue }
            try {
                val hash=hash(source,d);val sig=signature(d)
                val mapped=port.query("SELECT * FROM local_files WHERE root_uri=? AND actual_document_id=?",source.rootUri,d.documentId).singleOrNull()
                if(mapped!=null && mapped["plaintext_sha256"]==hash && mapped["source_signature"]==sig &&
                    repository.recording(mapped["recording_id"].toString())["audio_sha256"]==hash) {
                    encrypted.verify(encrypted.reference(mapped["recording_id"].toString(),number(repository.recording(mapped["recording_id"].toString()),"source_version")))
                    same++;continue
                }
                val plan=port.transaction {
                    val pending=port.query("SELECT * FROM local_intakes WHERE root_uri=? AND actual_document_id=? AND source_signature=? AND plaintext_sha256=? AND completed=0",source.rootUri,d.documentId,sig,hash)
                    check(pending.size<=1) { "INTAKE_REVIEW_REQUIRED" }
                    if(pending.isNotEmpty()) return@transaction pending.single()
                    if(mapped==null && !inventory.complete) check(port.query("SELECT recording_id FROM local_files WHERE root_uri=? AND plaintext_sha256=?",source.rootUri,hash).isEmpty()) { "INCOMPLETE_RENAME_REVIEW_REQUIRED" }
                    val rename=if(mapped==null && inventory.complete) port.query("SELECT * FROM local_files WHERE root_uri=? AND plaintext_sha256=?",source.rootUri,hash)
                        .filter { it["actual_document_id"] !in inventory.allDocumentIds } else emptyList()
                    check(rename.size<=1) { "AMBIGUOUS_RENAME_REVIEW_REQUIRED" }
                    val prior=mapped ?: rename.singleOrNull()
                    val recording=prior?.get("recording_id")?.toString() ?: id("local_recording")
                    val old=port.query("SELECT * FROM recordings WHERE recording_id=? AND owner_key=?",recording,OWNER).singleOrNull()
                    val p=SqlCallRepository.map("event_id",id("local_intake"),"recording_id",recording,"owner_key",OWNER,"grant_id",grant,
                        "policy_revision",1L,"document_id",old?.get("document_id") ?: id("local_document"),"source_seq",(old?.get("source_seq") as? Number)?.toLong()?.plus(1L) ?: 1L,
                        "display_name",d.name,"mime_type",d.mime,"byte_count",d.size,"modified_at_ms",d.modified,
                        "original_started_at_ms",old?.get("original_started_at_ms"),"phone_group_ref",old?.get("phone_group_ref"),
                        "person_id",old?.get("person_id"),"company_id",old?.get("company_id"),"task_id",old?.get("task_id"))
                    port.execute("INSERT INTO local_intakes VALUES(?,?,?,?,?,?,0)",p["event_id"],source.rootUri,d.documentId,sig,hash,JsonCanonical.encode(p))
                    port.query("SELECT * FROM local_intakes WHERE event_id=?",p["event_id"]).single()
                }
                val metadata=JsonBridge.parseObject(plan["metadata_json"].toString())
                // child/identity flags attest a selected provider DOCUMENT binding, not a person or business.
                val observation=FileCollector.Observation(metadata,source.rootUri,true,true)
                val fence={ stable(source,d);false }
                val event=metadata["event_id"].toString()
                val receipt=if(event in encrypted.pendingEvents()) {
                    try { encrypted.recoverPending(event,source.rootUri,true,true,fence) }
                    catch(error:VaultException) { if(error.code!="ORPHAN_REFERENCE_NOT_FOUND") throw error
                        encrypted.collect(observation,hash,fence) { guardedOpen(source,d) } }
                } else encrypted.collect(observation,hash,fence) { guardedOpen(source,d) }
                fault("AFTER_CANONICAL_RECEIPT_BEFORE_ALIAS")
                port.transaction {
                    token.check();stable(source,d)
                    val recording=metadata["recording_id"].toString()
                    val candidate=kr.callbrief.core.RecordingNames.startedAt(d.name)?.toEpochMilli()
                    if(port.query("SELECT recording_id FROM local_files WHERE recording_id=?",recording).isEmpty()) {
                        port.execute("INSERT INTO local_files VALUES(?,?,?,?,?,?,?)",recording,source.rootUri,d.documentId,d.uri,sig,hash,candidate)
                        port.execute("INSERT INTO local_visibility(recording_id) VALUES(?)",recording)
                    } else port.execute("UPDATE local_files SET actual_document_id=?,actual_uri=?,source_signature=?,plaintext_sha256=?,filename_time_candidate_ms=? WHERE recording_id=?",d.documentId,d.uri,sig,hash,candidate,recording)
                    port.execute("UPDATE local_intakes SET completed=1 WHERE event_id=?",event)
                }
                if(receipt["outcome"]=="NEW_CONTENT_VERSION") added++ else same++
            } catch(error:Exception) {
                // No raw filename/URI/phone or stack trace in public results. Earlier receipts remain durable.
                if(error is VaultException && error.code=="CANCELLED") throw error
                held++
            }
        }
        return ImportReport(added,same,excluded,held,!inventory.complete)
    }
    // Automatic discovery uses the SAME protected port and canonical receipt/source CAS.
    internal fun discoveryQueue(selection:kr.co.ieum.discovery.DiscoveryTypes.Selection)=ProtectedDiscoveryQueue(port,selection)
    private fun discoveryTables()=port.transaction {
        port.requireProtectedPersistence()
        port.execute("CREATE TABLE IF NOT EXISTS discovery_documents(root TEXT NOT NULL,document_id TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL,mime TEXT NOT NULL,modified INTEGER NOT NULL,partial INTEGER NOT NULL,virtual INTEGER NOT NULL,PRIMARY KEY(root,document_id))")
        port.execute("CREATE TABLE IF NOT EXISTS discovery_intake_requests(operation_id TEXT PRIMARY KEY,owner_key TEXT NOT NULL,commit_payload TEXT NOT NULL,metadata_json TEXT NOT NULL,request_digest TEXT NOT NULL,root TEXT NOT NULL,document_id TEXT NOT NULL)")
        port.execute("CREATE TRIGGER IF NOT EXISTS discovery_request_update BEFORE UPDATE ON discovery_intake_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISCOVERY_REQUEST');END")
        port.execute("CREATE TRIGGER IF NOT EXISTS discovery_request_delete BEFORE DELETE ON discovery_intake_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISCOVERY_REQUEST');END")
    }
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
        if(port.query("SELECT recording_id FROM local_files WHERE recording_id=?",c.recordingId).isEmpty()) {
            port.execute("INSERT INTO local_files VALUES(?,?,?,?,?,?,NULL)",c.recordingId,c.selection.treeUri,d.documentId,d.uri,sig,c.audioSha256)
            port.execute("INSERT OR IGNORE INTO local_visibility(recording_id) VALUES(?)",c.recordingId)
        } else port.execute("UPDATE local_files SET actual_document_id=?,actual_uri=?,source_signature=?,plaintext_sha256=? WHERE recording_id=?",d.documentId,d.uri,sig,c.audioSha256,c.recordingId)
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

    fun snapshot():JSONArray=LocalRecordingCatalog(context,DATABASE,OWNER,true).listFiles()
    fun history(recording:String):JSONObject=LocalRecordingCatalog(context,DATABASE,OWNER,true).history(recording)
    private fun checkSnapshot(recording:String,expected:JSONObject):Map<String,Any?> {
        val r=repository.recording(recording)
        check(number(r,"user_revision")==expected.getLong("user_revision") && number(r,"lock_revision")==expected.getLong("lock_revision") &&
            number(r,"head_revision")==expected.getLong("head_revision") && number(r,"source_version")==expected.getLong("source_version")) { "USER_REVISION_CONFLICT" }
        val v=port.query("SELECT revision FROM local_visibility WHERE recording_id=?",recording).single()
        check(number(v,"revision")==expected.getLong("visibility_revision")) { "VISIBILITY_REVISION_CONFLICT" };return r
    }
    fun edit(recording:String,expected:JSONObject,note:String,status:String) = port.transaction {
        token.check();val r=checkSnapshot(recording,expected)
        check(note.length<=20000 && status in setOf("open","in_progress","waiting","needs_confirmation","done")) { "INVALID_MANUAL_EDIT" }
        repository.userCommand("EDIT",SqlCallRepository.map("event_id",id("local_edit"),"recording_id",recording,
            "expected_user_revision",r["user_revision"],"expected_lock_revision",r["lock_revision"],"edit_text",1,"new_text",note,
            "edit_status",1,"new_status",status,"now_ms",now()))
    }
    fun confirmTime(recording:String,expected:JSONObject,epochMs:Long) = port.transaction {
        token.check();val r=checkSnapshot(recording,expected);check(epochMs in 946684800000L..now()) { "INVALID_ORIGINAL_TIME" }
        repository.userCommand("CONFIRM",SqlCallRepository.map("event_id",id("local_time"),"recording_id",recording,"expected_user_revision",r["user_revision"],
            "expected_lock_revision",r["lock_revision"],"person_id",r["person_id"],"company_id",r["company_id"],"task_id",r["task_id"],"original_started_at_ms",epochMs,"now_ms",now()))
    }
    fun archive(recording:String,expected:JSONObject,archived:Boolean) = port.transaction {
        token.check();checkSnapshot(recording,expected)
        port.execute("UPDATE local_visibility SET archived=?,revision=revision+1 WHERE recording_id=?",if(archived)1 else 0,recording)
        port.execute("INSERT INTO local_visibility_history VALUES(?,?,?,?,?)",id("local_visibility"),recording,if(archived)1 else 0,expected.getLong("visibility_revision")+1,now())
    }
    override fun close() { encrypted.close();port.close() }
}
