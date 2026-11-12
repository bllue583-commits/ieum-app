package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.ieum.discovery.DiscoveryStateCodec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64

/** v3.8 r18 (DESIGN_V7 4.1, r18-storage): removes the finished lines of the recording intake ledger that nothing reads again.
 *
 * What goes, and why it is safe (audit, code positions in DESIGN_V7 4.1 and the r18 report):
 *  - `discovery_intake_requests` rows of a **same-content look** (the stored file was read again and its hash had not
 *    changed: native receipt OBSERVE/SAME_CONTENT): the request is read only by [GeneralLocalStore.discoveryPlan] (the same
 *    operation id again, which only the queue row's `pending` carries) and by [GeneralLocalStore.recoverDiscoveryReceipt]
 *    (called with a `pending` id, or with a version's id by `localReceipt`). A same-content look adds no version, so after
 *    its `pending` is cleared no queue row names it. A row goes only when its native receipt and vault receipt are both
 *    there with the same request digest, the receipt is older than [KEEP_MS], no source version was made by it, no open
 *    vault intake has its id, and no queue row of any scope names it (pending, versions, audit notes).
 *  - `vault2_pending` rows with `completed=1`: [EncryptedRecordingStore.collect] looks for its committed vault receipt
 *    first and returns before it reads this row; [EncryptedRecordingStore.recoverPending] runs only for `completed=0`.
 *    Same age and receipt conditions; no queue row's pending (or audit note) names it.
 *
 * Everything else stays (the immutable receipts, the vault receipts and references, the queue, the documents, every
 * new-content request a version points at). One transaction; running it again changes nothing. Before the first run that
 * deletes anything the sealed file is copied once ([copyFile]; given up after [COPY_GIVE_UP_MS] of failures) and the copy is deleted [COPY_KEEP_MS] after (it still
 * holds what was deleted, including private calls). The copy opens only from the original path (its key name and
 * associated data come from that path): see docs/OPERATIONS.md. */
internal object LedgerPrune {
    const val KEEP_MS=3L*86_400_000L
    const val COPY_KEEP_MS=7L*86_400_000L
    /** A safety copy that could not be made this long (phone storage full, say) no longer holds the prune back. */
    const val COPY_GIVE_UP_MS=3L*86_400_000L
    const val REQUESTS="discovery_intake_requests"
    const val PENDING="vault2_pending"
    private const val STATE="ledger_prune_state"
    /** When the copy was made; -1: given up after [COPY_GIVE_UP_MS] of failures (the prune runs without one). */
    private const val COPY_MS="copy_ms"
    private const val COPY_FAIL_MS="copy_fail_ms"
    private const val CHUNK=400
    /** Skip codes (heartbeat dbPrune.skipped). */
    const val SKIP_IN_CALL="IN_CALL"
    const val SKIP_QUEUE_UNREADABLE="QUEUE_UNREADABLE"
    const val SKIP_COPY_FAILED="COPY_FAILED"
    const val SKIP_NO_LEDGER="NO_LEDGER"

    /** [deleted]: rows per table; [copyAtMs]: when the safety copy was made (null: none now); [copy]: "made" (this run),
     * "kept", "deleted" (after its 7 days), "none" (given up, the prune runs without one), null (no prune yet). */
    data class Result(val deleted:Map<String,Int>,val charsBefore:Long,val charsAfter:Long,val skipped:String?,val ms:Long,val copyAtMs:Long?,val copy:String?=null)
    const val COPY_MADE="made";const val COPY_KEPT="kept";const val COPY_DELETED="deleted";const val COPY_NONE="none"

    /** Deletes a safety copy older than [COPY_KEEP_MS] by its file time, whatever the store says (it holds the whole store as it
     * was). Run first in the daily work, before the store is opened. */
    fun dropOldCopy(context:Context,now:Long):Boolean { val f=copyFile(context);File(f.path+".tmp").delete()
        return f.isFile && f.lastModified()>0 && now-f.lastModified()>=COPY_KEEP_MS && f.delete() }

    fun copyFile(context:Context):File=File(File((context.applicationContext ?: context).noBackupFilesDir,"protected-databases-prune-copy-v1"),GeneralLocalStore.DATABASE+".sealed")

    private fun tables(port:AndroidSqlPort)=port.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").map { it["name"].toString() }.toSet()

    /** Characters in the text and blob columns of every table (the measure of `dbTables.totalChars`). */
    fun totalChars(port:AndroidSqlPort):Long = tables(port).sumOf { t ->
        runCatching {
            val q="\""+t.replace("\"","\"\"")+"\""
            val cols=port.query("PRAGMA table_info($q)").filter { c -> val type=(c["type"]?.toString() ?: "").uppercase();type.isEmpty() || "TEXT" in type || "BLOB" in type || "CHAR" in type }
                .map { "coalesce(length(\""+it["name"].toString().replace("\"","\"\"")+"\"),0)" }
            if(cols.isEmpty()) 0L else (port.query("SELECT coalesce(sum(${cols.joinToString("+")}),0) AS c FROM $q").single()["c"] as? Number)?.toLong() ?: 0L
        }.getOrDefault(0L) }

    /** Operation ids any discovery queue row or header still names: pending, versions, and ids in audit notes. */
    /** [all]: pending, version and audit ids (a request a version points at is read by `localReceipt`); [open]: pending and
     * audit ids only (a vault intake row is read while its intake is open, never through a version). */
    private class Named(val all:Set<String>,val open:Set<String>)
    private fun referenced(port:AndroidSqlPort,names:Set<String>):Named? {
        val out=HashSet<String>();val open=HashSet<String>()
        fun add(text:String):Boolean {
            val state=try { DiscoveryStateCodec.decode(Base64.getDecoder().decode(text)) } catch(e:Exception) { return false }
            state.rows.values.forEach { r -> r.pending?.let { out+=it.operationId;open+=it.operationId };r.versions.forEach { v -> out+=v.operationId } }
            state.audit.forEach { a -> a.substringAfter(':',"").takeIf { it.isNotEmpty() }?.let { out+=it;open+=it } }
            return true
        }
        if("discovery_queue_rows" in names) for(row in port.query("SELECT payload FROM discovery_queue_rows")) if(!add(row["payload"].toString())) return null
        if("discovery_queue_roots" in names) for(row in port.query("SELECT header FROM discovery_queue_roots")) if(!add(row["header"].toString())) return null
        return Named(out,open)
    }

    private fun makeCopy(sealed:File,copy:File,now:Long):Boolean = runCatching {
        if(!sealed.isFile) return@runCatching false
        copy.parentFile?.mkdirs()
        val tmp=File(copy.path+".tmp");tmp.delete()
        FileInputStream(sealed).use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out);out.flush();out.fd.sync() } }
        if(tmp.length()!=sealed.length() || !tmp.renameTo(copy)) { tmp.delete();return@runCatching false }
        copy.setLastModified(now);true
    }.getOrDefault(false)

    private fun state(port:AndroidSqlPort,key:String):Long? = (port.query("SELECT v FROM $STATE WHERE k=?",key).singleOrNull()?.get("v") as? Number)?.toLong()

    /** [busy]: a skip code decided outside (a call now), or null. */
    fun run(port:AndroidSqlPort,owner:String,now:Long,sealed:File,copy:File,busy:String?):Result = port.transaction {
        val t0=System.nanoTime()
        fun ms()=(System.nanoTime()-t0)/1_000_000L
        // No change of rows or schema when it is there already: an unchanged transaction does not rewrite the file.
        port.execute("CREATE TABLE IF NOT EXISTS $STATE(k TEXT PRIMARY KEY,v INTEGER NOT NULL)")
        // The safety copy: kept COPY_KEEP_MS after it was made, then deleted, also on a day the prune itself is skipped.
        var copyAt=state(port,COPY_MS)
        if(copy.isFile && copyAt==null) { copyAt=copy.lastModified().takeIf { it>0 } ?: now;port.execute("INSERT OR REPLACE INTO $STATE VALUES(?,?)",COPY_MS,copyAt) }
        if(copy.isFile && copyAt!=null && copyAt>0 && now-copyAt>=COPY_KEEP_MS) copy.delete()
        File(copy.path+".tmp").delete()
        val kept=if(copy.isFile) copyAt else null
        val copyState=when { copyAt==-1L -> COPY_NONE;copy.isFile -> COPY_KEPT;copyAt!=null -> COPY_DELETED;else -> null }
        val names=tables(port)
        fun skip(code:String)=Result(emptyMap(),-1,-1,code,ms(),kept,copyState)
        if(busy!=null) return@transaction skip(busy)
        // No skip while a transcription runs: every write of the store is one transaction under the same lock
        // (ProtectedDatabase.State.transaction is synchronized) and no transcription code reads or writes these two tables.
        if(listOf("vault2_intake_receipts","receipts","source_versions").any { it !in names } || (REQUESTS !in names && PENDING !in names)) return@transaction skip(SKIP_NO_LEDGER)
        val named=referenced(port,names) ?: return@transaction skip(SKIP_QUEUE_UNREADABLE)
        val cutoff=now-KEEP_MS
        val requests=if(REQUESTS !in names) emptyList() else port.query("""SELECT d.operation_id AS id FROM $REQUESTS d
            JOIN vault2_intake_receipts v ON v.event_id=d.operation_id AND v.owner_key=d.owner_key AND v.request_digest=d.request_digest
            JOIN receipts r ON r.event_id=d.operation_id AND r.recording_id=v.recording_id AND r.source_version=v.source_version
            WHERE d.owner_key=? AND r.operation='OBSERVE' AND r.outcome='SAME_CONTENT' AND r.created_at_ms<=?
            AND NOT EXISTS(SELECT 1 FROM source_versions s WHERE s.observation_event_id=d.operation_id)
            ${if(PENDING in names) "AND NOT EXISTS(SELECT 1 FROM $PENDING p WHERE p.event_id=d.operation_id AND p.completed=0)" else ""}""",owner,cutoff)
            .map { it["id"].toString() }.filter { it !in named.all }
        val pending=if(PENDING !in names) emptyList() else port.query("""SELECT p.event_id AS id FROM $PENDING p
            JOIN vault2_intake_receipts v ON v.event_id=p.event_id AND v.owner_key=p.owner_key AND v.request_digest=p.request_digest
            JOIN receipts r ON r.event_id=p.event_id AND r.recording_id=v.recording_id AND r.source_version=v.source_version
            WHERE p.owner_key=? AND p.completed=1 AND r.created_at_ms<=?""",owner,cutoff)
            .map { it["id"].toString() }.filter { it !in named.open }
        if(requests.isEmpty() && pending.isEmpty()) return@transaction totalChars(port).let { c -> Result(mapOf(REQUESTS to 0,PENDING to 0),c,c,null,ms(),kept,copyState) }
        // The first run that deletes anything copies the sealed file as it is now (nothing of this transaction is in it yet).
        var madeAt=kept;var copyNow=copyState
        if(state(port,COPY_MS)==null) {
            if(makeCopy(sealed,copy,now)) { port.execute("INSERT OR REPLACE INTO $STATE VALUES(?,?)",COPY_MS,now);madeAt=now;copyNow=COPY_MADE }
            else {
                // A copy that keeps failing (the phone's storage is full, say) must not keep the store on its way to 100%.
                val since=state(port,COPY_FAIL_MS) ?: now.also { port.execute("INSERT OR REPLACE INTO $STATE VALUES(?,?)",COPY_FAIL_MS,it) }
                if(now-since<COPY_GIVE_UP_MS) return@transaction skip(SKIP_COPY_FAILED)
                port.execute("INSERT OR REPLACE INTO $STATE VALUES(?,?)",COPY_MS,-1L);madeAt=null;copyNow=COPY_NONE
            }
        }
        val before=totalChars(port)
        if(requests.isNotEmpty()) {
            // The request rows are guarded against delete; the guard goes for these rows only and comes back in this transaction.
            val guard=port.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='discovery_request_delete'").singleOrNull()?.get("sql")?.toString()
            if(guard!=null) port.execute("DROP TRIGGER discovery_request_delete")
            requests.chunked(CHUNK).forEach { c -> port.execute("DELETE FROM $REQUESTS WHERE owner_key=? AND operation_id IN (${c.joinToString(",") { "?" }})",owner,*c.toTypedArray()) }
            if(guard!=null) port.execute(guard)
        }
        pending.chunked(CHUNK).forEach { c -> port.execute("DELETE FROM $PENDING WHERE owner_key=? AND completed=1 AND event_id IN (${c.joinToString(",") { "?" }})",owner,*c.toTypedArray()) }
        Result(mapOf(REQUESTS to requests.size,PENDING to pending.size),before,totalChars(port),null,ms(),madeAt,copyNow)
    }
}
