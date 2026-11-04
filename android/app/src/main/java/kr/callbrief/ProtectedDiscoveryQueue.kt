package kr.callbrief

import kr.co.callsummary.core.JsonCanonical
import kr.co.callsummary.core.SqlCallRepository
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.ieum.discovery.DiscoveryStore
import kr.co.ieum.discovery.DiscoveryStateCodec
import kr.co.ieum.discovery.DiscoveryTypes.*
import java.util.Base64

/** Same registered protected SQL port as the canonical app. Never a plaintext queue file. */
internal class ProtectedDiscoveryQueue(private val port: AndroidSqlPort, private val selection: Selection) : DiscoveryStore {
    private val scope=JsonCanonical.digest(SqlCallRepository.map("owner",selection.ownerId,"root",selection.treeUri,"selection",selection.revision))
    init {
        port.requireProtectedPersistence()
        port.transaction {
            port.execute("CREATE TABLE IF NOT EXISTS discovery_queue_roots(scope TEXT PRIMARY KEY,header TEXT NOT NULL)")
            port.execute("CREATE TABLE IF NOT EXISTS discovery_queue_rows(scope TEXT NOT NULL,document_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(scope,document_id))")
            port.execute("CREATE TABLE IF NOT EXISTS discovery_policy_revisions(scope TEXT PRIMARY KEY,digest TEXT NOT NULL,revision INTEGER NOT NULL)")
        }
    }
    private fun encode(state:DiscoveryStore.State):String=Base64.getEncoder().encodeToString(DiscoveryStateCodec.encode(state)).also {
        check(it.length<900000) { "DISCOVERY_ROW_CAPACITY_REVIEW_REQUIRED" }
    }
    private fun decode(text:String)=DiscoveryStateCodec.decode(Base64.getDecoder().decode(text))
    /** Loaded state plus the stored text of the header and of every row, so a transaction writes only what changed. */
    private class Loaded(val state:DiscoveryStore.State,val header:String?,val payloads:Map<String,String>)
    private fun loadStored():Loaded {
        port.requireProtectedPersistence()
        val header=port.query("SELECT header FROM discovery_queue_roots WHERE scope=?",scope).singleOrNull()?.get("header")?.toString()
        val state=if(header==null)DiscoveryStore.State() else decode(header)
        check(state.selection==null || state.selection==selection) { "DISCOVERY_SCOPE_MISMATCH" }
        val payloads=LinkedHashMap<String,String>()
        for(row in port.query("SELECT document_id,payload FROM discovery_queue_rows WHERE scope=? ORDER BY document_id",scope)) {
            val text=row["payload"].toString();val part=decode(text)
            check(part.selection==selection && part.rows.size==1 && part.rows.containsKey(row["document_id"])) { "DISCOVERY_QUEUE_CORRUPT" }
            state.rows.putAll(part.rows);payloads[row["document_id"].toString()]=text
        }
        return Loaded(state,header,payloads)
    }
    private fun load():DiscoveryStore.State=loadStored().state
    /** Design 3.2 #3 (review pipeline #7): rows are written only when their encoded text changed, a removed row is
     * deleted, and the header keeps the latest [kr.co.ieum.discovery.RecordingDiscovery.AUDIT_KEEP] audit notes. */
    override fun <T> transact(action:DiscoveryStore.Transaction<T>):T=port.transaction {
        val loaded=loadStored();val state=loaded.state;val result=action.run(state);port.requireProtectedPersistence()
        kr.co.ieum.discovery.RecordingDiscovery.trimAudit(state)
        val header=encode(state.copy().apply { rows.clear() })
        if(loaded.header==null)port.execute("INSERT INTO discovery_queue_roots VALUES(?,?)",scope,header)
        else if(loaded.header!=header)port.execute("UPDATE discovery_queue_roots SET header=? WHERE scope=?",header,scope)
        // These are only private queue projections. No source/version/history or audio is deleted.
        loaded.payloads.keys.filter { it !in state.rows }.forEach { port.execute("DELETE FROM discovery_queue_rows WHERE scope=? AND document_id=?",scope,it) }
        state.rows.forEach { (id,row) ->
            val part=encode(DiscoveryStore.State().apply { this.selection=state.selection;rows[id]=row.copy() })
            val old=loaded.payloads[id]
            if(old==null)port.execute("INSERT INTO discovery_queue_rows VALUES(?,?,?)",scope,id,part)
            else if(old!=part)port.execute("UPDATE discovery_queue_rows SET payload=? WHERE scope=? AND document_id=?",part,scope,id)
        }
        result
    }
    override fun snapshot():DiscoveryStore.State=port.transaction { load().copy() }
    fun policyRevision(digest:String):Long=port.transaction {
        val old=port.query("SELECT digest,revision FROM discovery_policy_revisions WHERE scope=?",scope).singleOrNull()
        if(old?.get("digest")==digest)return@transaction (old["revision"] as Number).toLong()
        val revision=(old?.get("revision") as? Number)?.toLong()?.plus(1L) ?: 1L
        if(old==null)port.execute("INSERT INTO discovery_policy_revisions VALUES(?,?,?)",scope,digest,revision)
        else port.execute("UPDATE discovery_policy_revisions SET digest=?,revision=? WHERE scope=?",digest,revision,scope)
        revision
    }
    companion object {
        fun encodeCommit(commit:Commit):String {
            val row=Row().apply { recordingId=commit.recordingId;metadata=commit.metadata;decision=commit.decision;pending=commit }
            val state=DiscoveryStore.State().apply { selection=commit.selection;rows[commit.metadata.documentId]=row }
            return Base64.getEncoder().encodeToString(DiscoveryStateCodec.encode(state))
        }
        fun decodeCommit(value:String):Commit=DiscoveryStateCodec.decode(Base64.getDecoder().decode(value)).rows.values.single().pending
    }
}
