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
    private fun load():DiscoveryStore.State {
        port.requireProtectedPersistence()
        val header=port.query("SELECT header FROM discovery_queue_roots WHERE scope=?",scope).singleOrNull()
        val state=if(header==null)DiscoveryStore.State() else decode(header["header"].toString())
        check(state.selection==null || state.selection==selection) { "DISCOVERY_SCOPE_MISMATCH" }
        for(row in port.query("SELECT document_id,payload FROM discovery_queue_rows WHERE scope=? ORDER BY document_id",scope)) {
            val part=decode(row["payload"].toString())
            check(part.selection==selection && part.rows.size==1 && part.rows.containsKey(row["document_id"])) { "DISCOVERY_QUEUE_CORRUPT" }
            state.rows.putAll(part.rows)
        }
        return state
    }
    override fun <T> transact(action:DiscoveryStore.Transaction<T>):T=port.transaction {
        val state=load();val result=action.run(state);port.requireProtectedPersistence()
        val header=state.copy().apply { rows.clear() }
        val existing=port.query("SELECT scope FROM discovery_queue_roots WHERE scope=?",scope).isNotEmpty()
        if(existing)port.execute("UPDATE discovery_queue_roots SET header=? WHERE scope=?",encode(header),scope)
        else port.execute("INSERT INTO discovery_queue_roots VALUES(?,?)",scope,encode(header))
        // These are only private queue projections. No source/version/history or audio is deleted.
        port.execute("DELETE FROM discovery_queue_rows WHERE scope=?",scope)
        state.rows.forEach { (id,row) ->
            val part=DiscoveryStore.State().apply { this.selection=state.selection;rows[id]=row.copy() }
            port.execute("INSERT INTO discovery_queue_rows VALUES(?,?,?)",scope,id,encode(part))
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
