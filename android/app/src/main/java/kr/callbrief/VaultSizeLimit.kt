package kr.callbrief

import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.vault.AudioBinding

/** The recording size line in the sealed local database (design 3.2 #5). Stores made before v2.6 carry 25,000,000 in
 * the vault reference CHECK and in the core observe trigger; both are widened once to [AudioBinding.MAX_BYTES].
 * Only the number changes: no row, key, blob or encryption is touched. The rows of vault2_refs are copied back
 * unchanged inside one transaction and the ledger triggers are created again in that same transaction. */
internal object VaultSizeLimit {
    const val OLD_BYTES=25_000_000L
    private const val REFS_CHECK="plaintext_bytes<="
    private const val OBSERVE_CHECK="NEW.byte_count>"
    private fun sql(port:AndroidSqlPort,type:String,name:String)=
        port.query("SELECT sql FROM sqlite_master WHERE type=? AND name=?",type,name).singleOrNull()?.get("sql")?.toString()
    /** The lower of the two database lines (a store that could not be widened keeps 25 MB; discovery then holds bigger files). */
    fun current(port:AndroidSqlPort):Long {
        val refs=sql(port,"table","vault2_refs");val observe=sql(port,"trigger","observe_execute")
        val old=(refs?.contains(REFS_CHECK+OLD_BYTES)==true) || (observe?.contains(OBSERVE_CHECK+OLD_BYTES)==true)
        return if(old) OLD_BYTES else AudioBinding.MAX_BYTES
    }
    /** Idempotent; [ledgerSql] is the vault ledger (its CREATE ... IF NOT EXISTS statements restore the dropped triggers). */
    fun widen(port:AndroidSqlPort,ledgerSql:String,from:Long=OLD_BYTES,to:Long=AudioBinding.MAX_BYTES) = port.transaction {
        val refs=sql(port,"table","vault2_refs")
        if(refs!=null && refs.contains(REFS_CHECK+from)) {
            // A parent table rebuild: child rows (vault2_health, vault2_intake_receipts) are checked again at commit.
            port.execute("PRAGMA defer_foreign_keys=ON")
            // Its own guard triggers go with the table; they come back from the ledger below in this transaction.
            port.query("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='vault2_refs'")
                .forEach { port.execute("DROP TRIGGER \""+it["name"].toString().replace("\"","")+"\"") }
            port.execute("CREATE TABLE vault2_refs_resize AS SELECT * FROM vault2_refs")
            port.execute("DROP TABLE vault2_refs")
            port.execute(refs.replace(REFS_CHECK+from,REFS_CHECK+to))
            port.execute("INSERT INTO vault2_refs SELECT * FROM vault2_refs_resize")
            port.execute("DROP TABLE vault2_refs_resize")
            ledgerSql.split("\n-- statement-break\n").forEach { port.execute(it) }
        }
        val observe=sql(port,"trigger","observe_execute")
        if(observe!=null && observe.contains(OBSERVE_CHECK+from)) {
            port.execute("DROP TRIGGER observe_execute")
            port.execute(observe.replace(OBSERVE_CHECK+from,OBSERVE_CHECK+to))
        }
        null
    }
}
