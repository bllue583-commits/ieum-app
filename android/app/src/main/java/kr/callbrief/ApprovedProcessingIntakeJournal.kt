package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject

/** Local acceptance journal in the already initialized owner protected DB. No new key/store.
 * PREPARED is durable before SharedPreferences can change. It is never automatically resumed,
 * rolled back or promoted. Original ciphertext remains available for later explicit review. */
internal class ApprovedProcessingIntakeJournal private constructor(
    private val store: ProtectedDatabase, private val scope: DriveALocalScope
) : AutoCloseable {
    enum class BindingState { ABSENT, EXACT, CHANGED, REVOKED, STORE_UNAVAILABLE, CORRUPT }
    private val db = store.database()
    private val table = "approved_processing_intakes"
    init { ProtectedDatabase.requireSealedPersistence(db) }
    companion object {
        private const val WITHDRAWALS="approved_processing_intake_withdrawals"
        /** Only an explicit withdrawal mutates this tombstone; an intake never creates it. */
        fun withdraw(db: SQLiteDatabase, scope: DriveALocalScope) {
            ProtectedDatabase.requireSealedPersistence(db)
            check(db.inTransaction()) { "APPROVED_PROCESSING_WITHDRAW_TRANSACTION_REQUIRED" }
            db.execSQL("CREATE TABLE IF NOT EXISTS $WITHDRAWALS(scope TEXT PRIMARY KEY)")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS approved_processing_withdrawal_no_update BEFORE UPDATE ON $WITHDRAWALS BEGIN SELECT RAISE(ABORT,'APPROVED_PROCESSING_WITHDRAWAL_IMMUTABLE'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS approved_processing_withdrawal_no_delete BEFORE DELETE ON $WITHDRAWALS BEGIN SELECT RAISE(ABORT,'APPROVED_PROCESSING_WITHDRAWAL_IMMUTABLE'); END")
            db.execSQL("INSERT OR IGNORE INTO $WITHDRAWALS(scope) VALUES(?)",arrayOf(scope.key))
            if(db.rawQuery("SELECT name FROM sqlite_master WHERE name='approved_processing_intakes'",null).use{it.moveToFirst()})
                db.execSQL("UPDATE approved_processing_intakes SET state='REVOKED' WHERE scope=?",arrayOf(scope.key))
        }
        fun inspectBinding(context: Context, scope: DriveALocalScope, configuration: AutomationApiConfiguration): BindingState {
            if(!ProtectedDatabase.file(context,GeneralLocalStore.DATABASE).isFile)return BindingState.STORE_UNAVAILABLE
            return try {openExisting(context,scope).use { j->ProtectedDatabase.read(j.db){j.bindingState(configuration)} }}
                catch(e:Exception){if(e.message in setOf("TEXT_STORE_KEY_UNAVAILABLE","TEXT_STORE_NOT_INITIALIZED"))BindingState.STORE_UNAVAILABLE else BindingState.CORRUPT}
        }
        fun openExisting(context: Context, scope: DriveALocalScope): ApprovedProcessingIntakeJournal {
            check(ProtectedDatabase.file(context, GeneralLocalStore.DATABASE).isFile) { "APPROVED_PROCESSING_EXISTING_DB_REQUIRED" }
            return ApprovedProcessingIntakeJournal(ProtectedDatabase.open(context, GeneralLocalStore.DATABASE, null), scope)
        }
        fun checkEmptyReader(context:Context,scope:DriveALocalScope) {
            if(!ProtectedDatabase.file(context,GeneralLocalStore.DATABASE).isFile)return
            openExisting(context,scope).use {j->ProtectedDatabase.read(j.db){
                check(j.record()==null && !(j.hasTable(WITHDRAWALS) && j.db.rawQuery("SELECT scope FROM $WITHDRAWALS WHERE scope=?",arrayOf(scope.key)).use{it.moveToFirst()})) {
                    "APPROVED_PROCESSING_INTAKE_REVIEW_REQUIRED"
                }
            }}
        }
        fun checkReader(context: Context, scope: DriveALocalScope, settings: JSONObject, payload: ByteArray, now: Long) {
            openExisting(context, scope).use { journal -> ProtectedDatabase.read(journal.db) {
                val configuration=ApprovedProcessingSetupWriter.canonicalSetup(payload,scope).second
                check(journal.bindingState(configuration) in setOf(BindingState.ABSENT,BindingState.EXACT)) {
                    "APPROVED_PROCESSING_BINDING_REVIEW_REQUIRED"
                }
                val marker = settings.optJSONObject(ApprovedProcessingSetupWriter.MARKER)
                val record = journal.record()
                // Unmarked legacy setup retains its existing synthetic-only contract. A new writer
                // always commits its required marker with the payload; missing records cannot migrate it.
                if (marker == null && !settings.has(ApprovedProcessingSetupWriter.MARKER) && record == null) return@read
                check(marker != null && record != null) { "APPROVED_PROCESSING_INTAKE_RECORD_REQUIRED" }
                val actual=checkNotNull(settings.optJSONObject(ApprovedProcessingSetupWriter.SETUP)){"APPROVED_PROCESSING_SETUP_REQUIRED"}
                check(ApprovedProcessingSetupWriter.canonicalSetup(actual.toString().toByteArray(),scope).first.contentEquals(payload)) {
                    "APPROVED_PROCESSING_CURRENT_SETUP_CHANGED"
                }
                val frozen = ApprovedProcessingSetupWriter.policy(scope, settings)
                check(record.state == "COMMITTED" && record.payloadSha == DriveRelay.sha256(payload) &&
                    record.policyEpoch == frozen.policyEpoch && record.policySha == frozen.policySha &&
                    record.validFrom <= now && now < record.expiresAt) { "APPROVED_PROCESSING_INTAKE_REVIEW_REQUIRED" }
                check(StrictJson.`object`(StrictJson.parse(marker.toString().toByteArray())) == journal.marker(record)) {
                    "APPROVED_PROCESSING_INTAKE_MARKER_MISMATCH"
                }
            }}
        }
        /** Called again inside the final binding transaction, and for an existing binding read.
         * The supplied factory reads only local protected settings; no supplier or HTTP callback. */
        fun checkBindingAdmission(context:Context,scope:DriveALocalScope,configuration:AutomationApiConfiguration,
            settings:(Context)->SecureSettings,now:Long) {
            configuration.validate(scope)
            val account=AccountStorageContext(AutomationConnectionBindings.base(context),scope.owner)
            val payload=StrictJson.bytes(mapOf("schema" to "ieum.owner-approved-processing-setup.v1",
                "approvalState" to ApprovedProcessingSetupWriter.USAGE,"scope" to scope.binding(),
                "origin" to configuration.origin,"zone" to configuration.reportZone,"credential" to configuration.bearerCredential))
            checkReader(account,scope,settings(account).snapshot(),payload,now)
        }
    }
    private fun hasTable(name: String) = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
    private fun processingBytes(c: AutomationApiConfiguration) = StrictJson.bytes(mapOf("enabled" to c.enabled,
        "origin" to c.origin, "owner" to c.owner, "account" to c.account, "epoch" to c.epoch,
        "zone" to c.reportZone, "credential" to c.bearerCredential))

    /** Must be called while guarded holds the existing protected DB read/transaction monitor.
     * No table, binding, key or DB is initialized by this query. */
    fun bindingState(c: AutomationApiConfiguration): BindingState {
        if(hasTable(WITHDRAWALS) && db.rawQuery("SELECT scope FROM $WITHDRAWALS WHERE scope=?",arrayOf(scope.key)).use{it.moveToFirst()})
            return BindingState.REVOKED
        if (!hasTable("automation_connection_bindings")) return BindingState.ABSENT
        return db.rawQuery("SELECT payload,digest,revoked FROM automation_connection_bindings WHERE scope=? AND kind='PROCESSING_V1'", arrayOf(scope.key)).use { q ->
            if (!q.moveToFirst()) BindingState.ABSENT
            else {
                val bytes = q.getBlob(0)
                if (bytes.size > 16_384 || DriveRelay.sha256(bytes) != q.getString(1)) BindingState.CORRUPT
                else if (q.getInt(2) != 0) BindingState.REVOKED
                else if (bytes.contentEquals(processingBytes(c))) BindingState.EXACT else BindingState.CHANGED
            }
        }
    }
    fun <T> guarded(c: AutomationApiConfiguration, action: () -> T): T = ProtectedDatabase.read(db) {
        ProtectedDatabase.requireSealedPersistence(db)
        check(!db.inTransaction()) { "APPROVED_PROCESSING_TOP_LEVEL_REQUIRED" }
        c.validate(scope)
        check(bindingState(c) in setOf(BindingState.ABSENT, BindingState.EXACT)) { "APPROVED_PROCESSING_BINDING_REVIEW_REQUIRED" }
        action() // withdraw uses the same ProtectedDatabase transaction monitor.
    }
    private class Record(val payloadSha: String, val approvalSha: String, val policyEpoch: Long,
        val policySha: String, val validFrom: Long, val expiresAt: Long, val state: String)
    private fun marker(r: Record): Map<String,Any?> = mapOf("schema" to "ieum.local-approved-processing-intake.v1",
        "scope" to scope.binding(),"payloadSha" to r.payloadSha,"approvalSha" to r.approvalSha,
        "policyEpoch" to r.policyEpoch,"policySha" to r.policySha,"validFrom" to r.validFrom,"expiresAt" to r.expiresAt)
    private fun record(): Record? {
        if (!hasTable(table)) return null
        return db.rawQuery("SELECT payload_sha,approval_sha,policy_epoch,policy_sha,valid_from,expires_at,state FROM $table WHERE scope=?", arrayOf(scope.key)).use { q ->
            if (!q.moveToFirst()) null else Record(q.getString(0),q.getString(1),q.getLong(2),q.getString(3),q.getLong(4),q.getLong(5),q.getString(6))
        }
    }
    fun prepare(settings: JSONObject, originalSealed: String, frozen: ApprovedProcessingIntakeScope,
        approval: ApprovedProcessingApproval, approvalSha: String, payloadSha: String, requiredMarker: JSONObject): Boolean {
        val old = settings.opt(ApprovedProcessingSetupWriter.SETUP)
        val previous = record()
        if (old != null) {
            check(old is JSONObject && previous != null && previous.state == "COMMITTED" &&
                previous.approvalSha == approvalSha && previous.payloadSha == payloadSha &&
                settings.optJSONObject(ApprovedProcessingSetupWriter.MARKER)?.toString()?.let {
                    StrictJson.parse(it.toByteArray()) == StrictJson.parse(requiredMarker.toString().toByteArray())
                } == true &&
                DriveRelay.sha256(ApprovedProcessingSetupWriter.canonicalSetup(old.toString().toByteArray(),scope).first) == payloadSha) {
                "APPROVED_PROCESSING_EXISTING_SETUP_REVIEW_REQUIRED"
            }
            return false
        }
        check(previous == null && !settings.has(ApprovedProcessingSetupWriter.MARKER) && originalSealed.toByteArray().size <= 512 * 1024) { "APPROVED_PROCESSING_INCOMPLETE_REVIEW_REQUIRED" }
        check(!db.inTransaction()) { "APPROVED_PROCESSING_TOP_LEVEL_REQUIRED" }
        ProtectedDatabase.transaction(db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $table(scope TEXT PRIMARY KEY,payload_sha TEXT NOT NULL,approval_sha TEXT NOT NULL,policy_epoch INTEGER NOT NULL,policy_sha TEXT NOT NULL,valid_from INTEGER NOT NULL,expires_at INTEGER NOT NULL,original_sealed TEXT NOT NULL,state TEXT NOT NULL)")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS approved_processing_intake_immutable BEFORE UPDATE OF scope,payload_sha,approval_sha,policy_epoch,policy_sha,valid_from,expires_at,original_sealed ON $table BEGIN SELECT RAISE(ABORT,'APPROVED_PROCESSING_INTAKE_IMMUTABLE'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS approved_processing_intake_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'APPROVED_PROCESSING_INTAKE_IMMUTABLE'); END")
            db.execSQL("INSERT INTO $table VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(scope.key,payloadSha,approvalSha,frozen.policyEpoch,frozen.policySha,approval.validFromMs,approval.expiresAtMs,originalSealed,"PREPARED"))
        }
        return true
    }
    fun committed(payloadSha: String, approvalSha: String) {
        check(!db.inTransaction()) { "APPROVED_PROCESSING_TOP_LEVEL_REQUIRED" }
        ProtectedDatabase.transaction(db) {
        val r = checkNotNull(record())
        check(r.state == "PREPARED" && r.payloadSha == payloadSha && r.approvalSha == approvalSha) { "APPROVED_PROCESSING_INTAKE_REVIEW_REQUIRED" }
        db.execSQL("UPDATE $table SET state='COMMITTED' WHERE scope=? AND state='PREPARED'", arrayOf(scope.key))
        }
    }
    override fun close() = store.close()
}
